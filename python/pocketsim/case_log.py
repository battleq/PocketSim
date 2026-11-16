# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""案例埋点：把预览-运行结果写入本地 JSONL，字段对齐《CAE 失败案例记录表》A-S 列。

设计原则：
- A-S 列的字段名/标签是唯一真相源（CASE_COLUMNS），Kotlin 侧不重复定义；
- 记录构建集中在 build_record()，写入集中在 append_case_log()；
- 失败模式 ID 优先从错误信息正则提取（G-01/L-02/BCL-03/PST-01 等），
  提取不到再按关键词推断类别，避免硬编码经验系数。
"""
from __future__ import annotations

import json
import os
import re
from datetime import datetime
from typing import Any, Dict, List, Optional

# A-S 列定义：col=Excel 列字母，key=JSON 字段名，label=表头中文名。
# 顺序即 Excel 列顺序，后续如需回写 Excel 可直接按此顺序展开。
CASE_COLUMNS: List[Dict[str, str]] = [
    {"col": "A", "key": "case_id",         "label": "案例编号"},
    {"col": "B", "key": "record_date",     "label": "记录日期"},
    {"col": "C", "key": "session_id",      "label": "来源会话"},
    {"col": "D", "key": "user_text",       "label": "用户原话"},
    {"col": "E", "key": "input_form",      "label": "输入形态"},
    {"col": "F", "key": "category",        "label": "问题类别"},
    {"col": "G", "key": "failure_mode",    "label": "失败模式ID"},
    {"col": "H", "key": "phenomenon",      "label": "失败现象"},
    {"col": "I", "key": "expected",        "label": "期望结果"},
    {"col": "J", "key": "actual",          "label": "实际结果"},
    {"col": "K", "key": "signal",          "label": "可观测信号"},
    {"col": "L", "key": "ai_actions",      "label": "AI中间动作"},
    {"col": "M", "key": "detected",        "label": "是否检出"},
    {"col": "N", "key": "user_correction", "label": "用户修正"},
    {"col": "O", "key": "correction_rounds", "label": "修正轮次"},
    {"col": "P", "key": "final_status",    "label": "最终状态"},
    {"col": "Q", "key": "root_cause",      "label": "根因假设"},
    {"col": "R", "key": "severity",        "label": "严重度"},
    {"col": "S", "key": "remarks",         "label": "备注"},
]

CASE_FIELD_KEYS = [c["key"] for c in CASE_COLUMNS]

# 失败模式 ID 正则：G-01 / L-02 / BCL-03 / PST-01 / GEO-* / SCP-* / SOL-* / AI-*
_MODE_ID_RE = re.compile(r"\b([GL]|BCL|PST|GEO|SCP|SOL|AI)-\d{2}\b")

# 关键词 → 失败模式 ID（错误信息不含显式 ID 时的兜底推断，按命中顺序）
# 与《失败模式字典》G-01..G-10 / L-01..L-10 及扩展 BCL/PST 对齐
_MODE_KEYWORDS = [
    (("pressure_dir", "法向", "外法线", "方向"), "L-02"),
    (("量纲", "Pa/MPa", "N/kN", "单位", "mm/m", "毫米", "尺度"), "G-04"),
    (("与约束面重合", "约束面重合"), "BCL-03"),
    (("不在实体边界", "不在实体范围", "加载面", "约束面", "作用点"), "L-01"),
    (("机构", "约束不足", "刚体", "奇异", "NaN", "无穷", "平衡", "反力"), "L-05"),
    (("虚假", "假安全", "误报安全"), "PST-01"),
    (("风速", "风级", "beaufort"), "L-03"),
    (("mass_kg", "area_m2", "depth_m", "密度"), "L-03"),
]

# 失败模式 ID → 问题类别（对齐失败模式字典）
_MODE_CATEGORY = {
    "G": "几何构建",
    "L": "荷载施加",
    "BCL": "边界条件",
    "PST": "求解后处理",
    "GEO": "几何构建",
    "SCP": "工况组合",
    "SOL": "求解",
    "AI": "AI解析",
}

# 关键词 → 问题类别（当错误信息里没有模式 ID 时兜底推断）
_CATEGORY_KEYWORDS = [
    ("几何", "几何构建"),
    ("尺寸", "几何构建"),
    ("单位", "几何构建"),
    ("尺度", "几何构建"),
    ("网格", "几何构建"),
    ("面", "荷载施加"),
    ("压力", "荷载施加"),
    ("荷载", "荷载施加"),
    ("集中力", "荷载施加"),
    ("方向", "荷载施加"),
    ("约束", "边界条件"),
    ("固定", "边界条件"),
    ("刚体", "边界条件"),
    ("平衡", "边界条件"),
    ("材料", "材料"),
    ("求解", "求解"),
    ("奇异", "求解"),
    ("NaN", "求解"),
    ("无穷", "求解"),
]

# 关键词 → 严重度（S=严重/M=中等/I=轻微），命中越靠前优先级越高
_SEVERITY_KEYWORDS = [
    ("机构", "S"),
    ("约束不足", "S"),
    ("刚体", "S"),
    ("奇异", "S"),
    ("NaN", "S"),
    ("无穷", "S"),
    ("虚假", "S"),
    ("不安全", "S"),
    ("超出范围", "M"),
    ("必须", "M"),
    ("不能", "M"),
    ("不在", "M"),
    ("重合", "M"),
    ("缺少", "M"),
    ("非法", "M"),
    ("警告", "I"),
    ("建议", "I"),
]


def _now() -> datetime:
    return datetime.now()


def new_case_id(data_dir: str = "") -> str:
    """生成案例编号 FC-YYYYMMDD-NNN（同日按已有记录数递增）。"""
    today = _now().strftime("%Y%m%d")
    prefix = f"FC-{today}-"
    seq = 1
    if data_dir:
        path = case_log_path(data_dir)
        if os.path.exists(path):
            try:
                with open(path, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if not line:
                            continue
                        try:
                            rec = json.loads(line)
                        except json.JSONDecodeError:
                            continue
                        cid = str(rec.get("case_id") or "")
                        if cid.startswith(prefix):
                            try:
                                seq = max(seq, int(cid[len(prefix):]) + 1)
                            except ValueError:
                                pass
            except OSError:
                pass
    return f"{prefix}{seq:03d}"


def case_log_path(data_dir: str) -> str:
    """JSONL 文件路径：{data_dir}/case_log.jsonl。"""
    return os.path.join(str(data_dir), "case_log.jsonl")


def failure_mode_from_error(msg: str) -> str:
    """从错误信息提取失败模式 ID：优先正则匹配 G-01/L-02 等，否则按关键词推断。"""
    if not msg:
        return ""
    m = _MODE_ID_RE.search(msg)
    if m:
        return m.group(0)
    for keywords, mode_id in _MODE_KEYWORDS:
        if any(kw in msg for kw in keywords):
            return mode_id
    return ""


def category_from(msg: str, mode_id: str = "") -> str:
    """推断问题类别：优先按模式 ID 前缀，再按关键词。"""
    if mode_id:
        prefix = mode_id.split("-", 1)[0]
        if prefix in _MODE_CATEGORY:
            return _MODE_CATEGORY[prefix]
    if msg:
        for kw, cat in _CATEGORY_KEYWORDS:
            if kw in msg:
                return cat
    return "其他"


def severity_from(msg: str, ok: bool) -> str:
    """推断严重度：成功为 OK；失败按关键词 S/M/I，默认 M。"""
    if ok:
        return "OK"
    if msg:
        for kw, sev in _SEVERITY_KEYWORDS:
            if kw in msg:
                return sev
    return "M"


def _model_summary(model: Dict[str, Any]) -> str:
    """从模型字典提炼一句话摘要（几何+材料+约束+载荷数）。"""
    geom = model.get("geometry") or {}
    gtype = str(geom.get("type") or "")
    dims = []
    for k in ("length_m", "height_m", "thickness_m", "width_m", "radius_m"):
        if geom.get(k) is not None:
            dims.append(f"{k}={geom[k]}")
    parts = [f"{gtype}({', '.join(dims)})"] if gtype else []
    mat = model.get("material")
    if mat:
        parts.append(f"材料={mat}")
    sups = model.get("supports") or []
    loads = model.get("loads") or []
    parts.append(f"约束={len(sups)} 载荷={len(loads)}")
    return "；".join(parts)


def _result_summary(result: Optional[Dict[str, Any]]) -> str:
    """从求解结果提炼实际结果摘要（verdict + 关键指标）。"""
    if not result:
        return ""
    metrics = result.get("metrics") or {}
    verdict = metrics.get("verdict")
    items = []
    if verdict:
        items.append(f"结论={verdict}")
    if metrics.get("max_von_mises_MPa") is not None:
        items.append(f"σ_vm={metrics['max_von_mises_MPa']}MPa")
    if metrics.get("max_disp_mm") is not None:
        items.append(f"δ_max={metrics['max_disp_mm']}mm")
    if metrics.get("safety_factor") is not None:
        items.append(f"SF={metrics['safety_factor']}")
    return "；".join(items)


def _checks_summary(result: Optional[Dict[str, Any]]) -> str:
    """把结果里的 checks 转成可观测信号文本。"""
    if not result:
        return ""
    checks = result.get("checks") or []
    parts = []
    for c in checks:
        name = c.get("name")
        val = c.get("value")
        limit = c.get("limit")
        unit = c.get("unit", "")
        pf = "通过" if c.get("pass") else "不通过"
        if name is not None and val is not None:
            parts.append(f"{name}:{val}{unit}/{limit}{unit}({pf})")
    return "；".join(parts)


def build_record(
    model: Optional[Dict[str, Any]] = None,
    result: Optional[Dict[str, Any]] = None,
    error: Optional[str] = None,
    user_text: str = "",
    session_id: str = "",
    input_form: str = "text",
    stage: str = "solve",
    detected: Optional[bool] = None,
    data_dir: str = "",
    llm_attempts: Optional[int] = None,
    first_error: Optional[str] = None,
) -> Dict[str, Any]:
    """构建一条对齐 A-S 列的案例记录（不写盘）。

    stage: "validate"（Kotlin 校验失败）或 "solve"（求解阶段）。
    detected: True=校验阶段已捕获，False=求解才发现，None=成功或未判定。
    llm_attempts/first_error：LLM 建模尝试总次数与首轮错误（仅校验失败埋点有），
    用于统计一次通过率/重试后通过率；A-S 列结构不变，附加在 S 列 remarks 中。
    """
    model = model or {}
    ok = error is None and result is not None and bool(result.get("ok", False))
    mode_id = failure_mode_from_error(error or "")
    cat = category_from(error or "", mode_id)
    sev = severity_from(error or "", ok)

    phenomenon = error if error else ("求解成功" if ok else "未知")
    signal = error if error else _checks_summary(result)
    actual = _result_summary(result) if ok else (error or "")
    ai_actions = []
    if result:
        for s in (result.get("load_sources") or []):
            ai_actions.append(str(s))
        for a in (result.get("assumptions") or []):
            ai_actions.append(str(a))
    if not ai_actions:
        ai_actions.append(_model_summary(model))

    final_status = "通过" if ok else ("警告" if sev == "I" else "失败")
    detected_text = ""
    if stage == "validate":
        detected_text = "是"
    elif stage == "solve" and not ok:
        detected_text = "否"  # 求解阶段才暴露 = 前置校验未检出

    # S 列备注：保持 A-S 19 列结构不变，把重试统计以 key=value 追加在 remarks。
    # first_error 去掉换行/分号（分号是 remarks 的分隔符）并截断，避免污染列结构。
    remarks_parts = [f"stage={stage}"]
    if llm_attempts is not None:
        remarks_parts.append(f"llm_attempts={int(llm_attempts)}")
    if first_error:
        cleaned = (
            str(first_error)
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(";", "；")
            .strip()[:200]
        )
        if cleaned:
            remarks_parts.append(f"first_error={cleaned}")

    record: Dict[str, Any] = {
        "case_id": new_case_id(data_dir),
        "record_date": _now().strftime("%Y-%m-%d"),
        "session_id": session_id,
        "user_text": user_text,
        "input_form": input_form,
        "category": cat,
        "failure_mode": mode_id,
        "phenomenon": phenomenon,
        "expected": "模型通过校验并给出应力/位移结果" if ok else "",
        "actual": actual,
        "signal": signal,
        "ai_actions": " | ".join(ai_actions),
        "detected": detected_text,
        "user_correction": "",
        "correction_rounds": 0,
        "final_status": final_status,
        "root_cause": "",
        "severity": sev,
        "remarks": ";".join(remarks_parts),
    }
    # 只保留 A-S 白名单字段，防止多余键混入
    return {k: record.get(k, "") for k in CASE_FIELD_KEYS}


def append_case_log(data_dir: str, record: Dict[str, Any]) -> str:
    """把一条记录追加到 {data_dir}/case_log.jsonl，返回写入的文件路径。

    只写入 A-S 白名单字段；data_dir 不存在则创建。
    """
    if not data_dir:
        raise ValueError("append_case_log 需要 data_dir（Android 端传 context.getFilesDir()）")
    os.makedirs(str(data_dir), exist_ok=True)
    path = case_log_path(data_dir)
    clean = {k: record.get(k, "") for k in CASE_FIELD_KEYS}
    line = json.dumps(clean, ensure_ascii=False, allow_nan=False)
    with open(path, "a", encoding="utf-8") as f:
        f.write(line + "\n")
    return path


def read_case_log(data_dir: str) -> List[Dict[str, Any]]:
    """读取 {data_dir}/case_log.jsonl 的全部记录（跳过无法解析的行）。"""
    path = case_log_path(data_dir)
    records: List[Dict[str, Any]] = []
    if not os.path.exists(path):
        return records
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return records


def export_to_excel(data_dir: str, output_path: str = "") -> str:
    """把 case_log.jsonl 导出为 xlsx，列顺序严格对齐 CASE_COLUMNS（A-S）。

    表头：第 1 行 A-S 写各列 label；第 2 行起每条记录一行，按 CASE_FIELD_KEYS
    顺序取值，缺字段/None 写空串。返回输出文件路径。
    """
    if not data_dir:
        raise ValueError("export_to_excel 需要 data_dir")
    try:
        from openpyxl import Workbook
    except ImportError as exc:
        raise RuntimeError("导出 Excel 需要 openpyxl，请先安装：pip install openpyxl") from exc

    records = read_case_log(data_dir)
    wb = Workbook()
    ws = wb.active
    ws.title = "失败案例记录"

    # 表头：A1..S1
    headers = [c["label"] for c in CASE_COLUMNS]
    ws.append(headers)

    # 数据行：严格按 CASE_FIELD_KEYS 顺序，避免列错位
    for rec in records:
        row = []
        for key in CASE_FIELD_KEYS:
            val = rec.get(key)
            if val is None:
                row.append("")
            else:
                row.append(str(val))
        ws.append(row)

    # 列宽按列字母给一个可读默认值（A 编号窄、D/J/K/L 描述宽）
    widths = {
        "A": 16, "B": 12, "C": 20, "D": 36, "E": 12, "F": 12, "G": 12,
        "H": 36, "I": 24, "J": 36, "K": 36, "L": 48, "M": 10, "N": 16,
        "O": 10, "P": 10, "Q": 24, "R": 10, "S": 16,
    }
    for c in CASE_COLUMNS:
        ws.column_dimensions[c["col"]].width = widths.get(c["col"], 16)
    # 表头加粗
    from openpyxl.styles import Font
    for cell in ws[1]:
        cell.font = Font(bold=True)

    if not output_path:
        output_path = os.path.join(str(data_dir), "case_log.xlsx")
    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    wb.save(output_path)
    return output_path
