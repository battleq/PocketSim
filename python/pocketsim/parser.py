# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""离线规则解析器：把一句中文生活问题解析为“场景模板 + 参数”。

用途：
1. 无 LLM / 无网络时的“离线智能预填”（Android 经 Chaquopy 调用）；
2. 样例集评测的确定性基线（tests/test_parser_samples.py）。

接口契约（与 Android 侧冻结，勿改字段名）：
    parse_problem(text) 成功 →
        {"ok": True, "template_id": str, "params": {key: float|str},
         "missing": [key, ...], "confidence": float}
    parse_problem(text) 失败 →
        {"ok": False, "reason": "无法识别场景"}

实现要点：
- 模板识别：关键词加权计分（每个关键词只计一次），最高分 < 2 视为无法识别；
- 数字抽取：阿拉伯数字 + 常见中文数字（一~百），单位 kg/公斤/斤/N/mm/厘米/米等；
- 参数定位：向前后各看一小段文字里的语境词（直径/厚/宽/长/跨度/外露/打入…）
  决定数字归属；无语境的裸长度按模板默认归属（搁板→跨度，钉子→外露长度）；
- 材料识别：材料库名称与别名匹配，取“命中最长别名”的材料（更具体者优先）；
  钉挂重场景再按材料类别分配到钉材质（钢铁类）或墙材质（混凝土/砖/石膏板等）；
- 输出参数只含“确定抽到的值”，其余交给模板默认值或 missing 列表。
"""
from __future__ import annotations

import re
from typing import Any, Dict, List, Optional, Tuple

from .materials import MaterialsLibrary
from .templates import ALL_TEMPLATES, TEMPLATES

# 模板识别关键词与权重（同一关键词只计一次）
_TEMPLATE_KEYWORDS: Dict[str, List[Tuple[str, float]]] = {
    "wall_nail": [
        ("钉", 3.0), ("墙", 1.0), ("挂", 1.0), ("悬挂", 2.0), ("膨胀", 2.0),
    ],
    "cantilever_shelf": [
        ("悬臂", 4.0), ("悬挑", 4.0), ("壁挂", 3.0), ("外挑", 3.0),
        ("搁板", 2.0), ("隔板", 2.0), ("伸出", 2.0),
        ("一端固定", 3.0), ("自由端", 3.0),
    ],
    "simply_supported_panel": [
        ("简支", 4.0), ("跨中", 3.0), ("桌", 3.0),
        ("两端", 2.0), ("两边", 2.0), ("书架", 2.0), ("中间", 2.0),
        ("正中", 2.0), ("支撑", 2.0), ("搭在", 2.0),
    ],
}

# 识别分数阈值：低于该值认为无法识别
SCORE_THRESHOLD = 2.0

# 数字 + 单位（顺序敏感：长单位在前，避免 “厘米” 被 “米” 抢先匹配）
_QTY_RE = re.compile(
    r"([0-9]+(?:\.[0-9]+)?|[零一二两三四五六七八九十百]+)"
    r"\s*"
    r"(公斤|千克|kg|KG|Kg|kG|斤|牛顿|牛|N|毫米|mm|MM|Mm|厘米|cm|CM|公分|米|m)"
)

# 单位归类与换算到基准单位
_UNIT_KIND = {
    "公斤": "mass", "千克": "mass", "kg": "mass", "KG": "mass", "Kg": "mass", "kG": "mass",
    "斤": "jin",
    "牛顿": "force", "牛": "force", "N": "force",
    "毫米": "length", "mm": "length", "MM": "length", "Mm": "length",
    "厘米": "length_cm", "cm": "length_cm", "CM": "length_cm", "公分": "length_cm",
    "米": "length_m", "m": "length_m",
}

# 语境词 → 参数类别（决定数字归属）
_CTX_BACKWARD: List[Tuple[str, str]] = [
    ("打入深度", "embed"), ("埋入", "embed"), ("打入", "embed"), ("入墙", "embed"),
    ("进墙", "embed"), ("嵌入", "embed"), ("深度", "embed"),
    ("外露", "exposed"), ("露出", "exposed"), ("伸出", "exposed"), ("悬出", "exposed"),
    ("外挑", "exposed"), ("悬挑", "exposed"),
    ("直径", "diameter"), ("钉径", "diameter"), ("粗", "diameter"),
    ("厚度", "thickness"), ("厚", "thickness"),
    ("宽度", "width"), ("宽", "width"), ("深", "width"),
    ("跨度", "length"), ("净跨", "length"), ("跨", "length"), ("长", "length"),
]
# 单位后置语境词（如 “3毫米粗” “厚 20” 已在前缀；这里查数字+单位之后的词）
_CTX_FORWARD: List[Tuple[str, str]] = [
    ("粗", "diameter"), ("厚", "thickness"), ("宽", "width"), ("长", "length"),
    ("深", "width"),
]

# 中文数字 → 数值
_CN_DIGITS = {"零": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4,
              "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}

# 墙体类材料 id（钉挂重场景分配到 wall_material）
_WALL_MATERIAL_IDS = {"concrete_c20", "brick_masonry", "gypsum_board"}
# 钉材类材料 id（分配到 nail_material）
_NAIL_MATERIAL_IDS = {"low_carbon_steel"}

GRAVITY = 9.8  # N/kg（与 solver.GRAVITY_M_S2 口径一致，取常用近似值）


def _cn_to_num(s: str) -> Optional[float]:
    """中文数字（一~九十九、一百以内）转数值；无法解析返回 None。"""
    if not s:
        return None
    section, num = 0, 0
    for ch in s:
        if ch in _CN_DIGITS:
            num = _CN_DIGITS[ch]
        elif ch == "十":
            section += (num or 1) * 10
            num = 0
        elif ch == "百":
            section += (num or 1) * 100
            num = 0
        else:
            return None
    total = section + num
    return float(total) if total > 0 else None


def _classify_template(text: str) -> Tuple[Optional[str], float]:
    """关键词加权计分选模板。返回 (模板 id 或 None, 置信度 0~1)。"""
    scores = {}
    for tid, keywords in _TEMPLATE_KEYWORDS.items():
        score = sum(weight for kw, weight in keywords if kw in text)
        scores[tid] = score
    best_id = max(scores, key=lambda k: scores[k])
    best = scores[best_id]
    total = sum(scores.values())
    if best < SCORE_THRESHOLD or total <= 0:
        return None, 0.0
    return best_id, round(best / total, 3)


def _extract_quantities(text: str) -> List[Dict[str, Any]]:
    """抽取全部“数字+单位”及其语境类别。

    返回列表元素：{"value": float, "kind": mass|jin|force|length(mm),
    "start": int, "end": int, "ctx": 类别名或 None}
    """
    out: List[Dict[str, Any]] = []
    for m in _QTY_RE.finditer(text):
        num_str, unit = m.group(1), m.group(2)
        if num_str[0].isdigit() or num_str[0] == ".":
            value = float(num_str)
        else:
            value = _cn_to_num(num_str)
            if value is None:
                continue
        kind_raw = _UNIT_KIND[unit]
        # 换算到基准单位：质量 kg、力 N、长度 mm
        if kind_raw == "jin":
            value, kind = value * 0.5, "mass"
        elif kind_raw == "force":
            kind = "force"
        elif kind_raw == "length":
            kind = "length"
        elif kind_raw == "length_cm":
            value, kind = value * 10.0, "length"
        elif kind_raw == "length_m":
            value, kind = value * 1000.0, "length"
        else:
            kind = "mass"
        # 语境：向后看取“离数字最近”的关键词（避免吞进上一个数字的语境），再看向后
        ctx = None
        best_pos = -1
        back = text[max(0, m.start() - 8):m.start()]
        for kw, cat in _CTX_BACKWARD:
            p = back.rfind(kw)
            if p != -1 and p > best_pos:
                best_pos = p
                ctx = cat
        if ctx is None:
            fwd = text[m.end():m.end() + 4]
            for kw, cat in _CTX_FORWARD:
                if kw in fwd:
                    ctx = cat
                    break
        out.append({"value": value, "kind": kind, "ctx": ctx,
                    "start": m.start(), "end": m.end()})
    return out


def _match_materials(text: str) -> List[Tuple[int, Dict[str, Any], str]]:
    """材料匹配：每个材料取命中的最长别 名，按文本位置排序。

    返回 [(首次出现位置, 材料记录, 命中的别名字符串), ...]
    """
    lib = MaterialsLibrary()
    lib.load_library()
    hits: List[Tuple[int, Dict[str, Any], str]] = []
    for rec in lib.all_materials():
        candidates = [rec["name"]] + list(rec.get("aliases", []))
        best: Optional[Tuple[int, str]] = None  # (位置, 别名)
        for alias in candidates:
            pos = text.find(alias)
            if pos >= 0 and (best is None or len(alias) > len(best[1])):
                best = (pos, alias)
        if best is not None:
            hits.append((best[0], rec, best[1]))
    # 更具体的匹配（别名更长）优先；同长按文本位置
    hits.sort(key=lambda h: (-len(h[2]), h[0]))
    return hits


def _assign_params(template_id: str, quantities: List[Dict[str, Any]],
                   materials: List[Tuple[int, Dict[str, Any], str]]) -> Dict[str, Any]:
    """把抽取到的数字与材料分配到模板参数键。"""
    params: Dict[str, Any] = {}

    # ---- 数字参数 ----
    used_ctx: Dict[str, bool] = {}
    for q in quantities:
        kind, ctx, value = q["kind"], q["ctx"], q["value"]
        if template_id == "wall_nail":
            if kind == "force":
                params.setdefault("mass_kg", round(value / GRAVITY, 3))
            elif kind == "mass":
                params.setdefault("mass_kg", value)
            elif kind == "length":
                if ctx == "diameter":
                    params.setdefault("nail_diameter_mm", value)
                elif ctx == "embed":
                    params.setdefault("embed_length_mm", value)
                else:
                    # 外露语境或裸长度都归外露长度（钉长通常指出墙长度）
                    params.setdefault("exposed_length_mm", value)
        else:  # 悬臂搁板 / 简支层板共用同一套几何与载荷参数
            if kind == "force":
                params.setdefault("load_n", value)
            elif kind == "mass":
                params.setdefault("load_kg", value)
            elif kind == "length":
                if ctx == "thickness":
                    params.setdefault("thickness_mm", value)
                elif ctx == "width":
                    params.setdefault("width_mm", value)
                else:
                    # 长/跨度/伸出/悬挑/裸长度 → 跨度
                    params.setdefault("length_mm", value)
        if ctx is not None:
            used_ctx[ctx] = True
    _ = used_ctx  # 预留（当前未用）

    # ---- 材料参数 ----
    if template_id == "wall_nail":
        nail_set, wall_set = False, False
        for _pos, rec, _alias in materials:
            mid = rec.get("id", "")
            if mid in _NAIL_MATERIAL_IDS:
                if not nail_set:
                    params["nail_material"] = rec["name"]
                    nail_set = True
            elif mid in _WALL_MATERIAL_IDS:
                if not wall_set:
                    params["wall_material"] = rec["name"]
                    wall_set = True
            else:
                # 木材/竹材等出现在钉挂重场景多为墙体（木柱/木墙），归墙材质
                if not wall_set:
                    params["wall_material"] = rec["name"]
                    wall_set = True
    else:
        if materials:
            params["material"] = materials[0][1]["name"]

    return params


def _validate_against_schema(template, params: Dict[str, Any]) -> Dict[str, Any]:
    """对已抽到的参数做 schema 校验：越界/类型非法的值剔除（必需项转 missing）。"""
    cleaned = dict(params)
    for spec in template.param_schema:
        if spec.key not in cleaned:
            continue
        value = cleaned[spec.key]
        if spec.type == "number":
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                cleaned.pop(spec.key)
                continue
            if spec.minimum is not None and value < spec.minimum:
                cleaned.pop(spec.key)
                continue
            if spec.maximum is not None and value > spec.maximum:
                cleaned.pop(spec.key)
        elif spec.type == "material":
            if not isinstance(value, str):
                cleaned.pop(spec.key)
    return cleaned


def _missing_keys(template, params: Dict[str, Any]) -> List[str]:
    """必填但未抽到的参数键（含“载荷二选一”规则）。"""
    missing = [spec.key for spec in template.param_schema
               if spec.required and spec.key not in params]
    if template.id in ("cantilever_shelf", "simply_supported_panel"):
        if "load_kg" not in params and "load_n" not in params:
            missing.append("load_kg")
    return missing


def parse_problem(text: str, templates: Optional[Dict] = None) -> Dict:
    """解析一句中文生活问题（离线规则，不依赖 LLM 与网络）。"""
    if not isinstance(text, str) or not text.strip():
        return {"ok": False, "reason": "无法识别场景：输入为空"}

    template_id, confidence = _classify_template(text)
    if template_id is None:
        return {"ok": False, "reason": "无法识别场景：未命中任何模板关键词"}

    template = (templates or TEMPLATES).get(template_id)
    if template is None:
        return {"ok": False, "reason": f"无法识别场景：模板 {template_id} 未注册"}

    quantities = _extract_quantities(text)
    materials = _match_materials(text)
    params = _assign_params(template_id, quantities, materials)
    params = _validate_against_schema(template, params)

    return {
        "ok": True,
        "template_id": template_id,
        "params": params,
        "missing": _missing_keys(template, params),
        "confidence": confidence,
    }
