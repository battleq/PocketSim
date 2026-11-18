# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""Chaquopy 桥接层：把 engine / frame / materials / templates 的输出适配为 Android Kotlin 侧 JSON 契约。

Android（PythonBridge.kt）统一以 JSON 字符串跨边界调用以下函数：
    pocketsim.bridge.list_templates() -> str                    JSON 数组 [TemplateInfo, ...]
    pocketsim.bridge.run_scenario(request_json: str) -> str     JSON FeaResult
    pocketsim.bridge.run_frame(model_json: str, data_dir: str) -> str  JSON FrameResult
    pocketsim.bridge.run_frame3d(model_json: str, data_dir: str) -> str  JSON FrameResult（3D）
    pocketsim.bridge.check_frame_stability(model_json, data_dir) -> str  JSON {"ok":bool, ...}（几何不变性预检）
    pocketsim.bridge.run_solid(model_json: str, data_dir: str) -> str   JSON SolidResult（三维实体）
    pocketsim.bridge.run_shell(model_json: str, data_dir: str) -> str   JSON ShellResult（薄壁容器：矩形箱/圆柱仓）
    pocketsim.bridge.list_materials(data_dir: str) -> str       JSON 数组 [MaterialRecord, ...]
    pocketsim.bridge.upsert_material(record_json: str, data_dir: str) -> str  JSON MaterialRecord
    pocketsim.bridge.log_case_context(context_json: str, data_dir: str) -> str  案例埋点（校验失败等非求解阶段）

适配内容（详见 Kotlin 侧 Models.kt 注释）：
- 字段改名：max_von_mises_mpa→max_stress_MPa、safety_class→verdict、level→mesh_level 等；
- 单位换算：渲染数据 m→mm、von Mises Pa→MPa、材料弹性模量 Pa→MPa；
- 错误通道：engine 返回 {"ok": false} 时抛 ValueError（Kotlin 捕获 PyException 转
  FeaEngineException，向用户展示中文错误）；
- 参数宽容：Kotlin 表单传来的数字参数可能是字符串（"300"），按模板 schema 自动转数字；
- 数据目录：request.data_dir / list_materials(data_dir) 用于定位联网检索的材料缓存副本。
"""
from __future__ import annotations

import functools
import json
from typing import Any, Dict, List, Optional

import numpy as np

from . import engine
from .i18n import set_locale
from .materials import MaterialsLibrary, set_default_data_dir
from .templates import TEMPLATES

# 网格自动调整原因 → 用户可读文案（Kotlin MeshInfo.adjusted_reason 直接展示）
_ADJUST_REASONS = {
    "memory": "内存预算不足，已自动降低网格精度",
    "timeout": "计算时间过长，已自动降低网格精度",
}


def _dumps(obj: Any) -> str:
    """统一 JSON 序列化（中文原样、禁 NaN/Infinity，保证跨语言安全）。"""
    return json.dumps(obj, ensure_ascii=False, allow_nan=False)


def _friendly_errors(kind: str):
    """求解期异常翻译装饰器。

    - ValueError：通常是业务校验抛出的中文报错（机构/材料/参数），原样保留；
    - TypeError / KeyError 等底层异常：翻译成用户能看懂的中文提示 + 建议动作，
      避免把 Python 原始堆栈（如 "float() argument must be ... not 'NoneType'"）直接抛给用户。
    """
    def deco(fn):
        @functools.wraps(fn)
        def wrapper(*args, **kwargs):
            try:
                return fn(*args, **kwargs)
            except ValueError:
                raise
            except TypeError as e:
                raise ValueError(
                    f"{kind}计算时遇到数据类型错误（通常是某个参数为空或格式不对）：{e}。"
                    f"请检查模型参数是否完整，或点「修改模型」调整后重试。"
                ) from e
            except KeyError as e:
                raise ValueError(
                    f"{kind}缺少必要参数 {e}，请补全模型信息后重试。"
                ) from e
            except Exception as e:
                raise ValueError(
                    f"{kind}求解失败：{e}。请尝试简化模型、改用更粗的网格档位，或修改模型后重试。"
                ) from e
        return wrapper
    return deco


# ---------------------------------------------------------------------- 模板列表
def list_templates() -> str:
    """模板元信息列表（Kotlin 直接解码为 List<TemplateInfo>）。"""
    data = engine.list_templates()
    out: List[Dict[str, Any]] = []
    for t in data.get("templates", []):
        params: List[Dict[str, Any]] = []
        for p in t.get("params", []):
            params.append(
                {
                    "key": p.get("key", ""),
                    "label": p.get("label", ""),
                    "unit": p.get("unit", ""),
                    # Kotlin ParamSpec 的类型枚举：material 原样，number 统一映射为 float
                    "type": "material" if p.get("type") == "material" else "float",
                    "default": p.get("default"),
                    "min": p.get("min"),
                    "max": p.get("max"),
                    "required": bool(p.get("required", False)),
                    "help": p.get("description", ""),
                }
            )
        out.append(
            {
                "id": t.get("id", ""),
                "name": t.get("name", ""),
                "description": t.get("description", ""),
                "category": "生活场景",
                "param_schema": params,
            }
        )
    return _dumps(out)


# ---------------------------------------------------------------------- 求解
def _coerce_params(template_id: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """Kotlin 表单的数字参数常为字符串（"300"），按模板 schema 转回数字。"""
    template = TEMPLATES.get(template_id)
    if template is None:
        return params
    spec_types = {spec.key: spec.type for spec in template.param_schema}
    out: Dict[str, Any] = {}
    for key, value in params.items():
        if spec_types.get(key) == "number" and isinstance(value, str):
            try:
                out[key] = float(value)
                continue
            except ValueError:
                pass  # 非法数字交给 engine 校验报中文错误
        out[key] = value
    return out


def _reason_text(reason: Optional[str]) -> Optional[str]:
    if not reason:
        return None
    return _ADJUST_REASONS.get(reason, reason)


def _to_render(rd: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """渲染数据适配：坐标/位移 m→mm，von Mises Pa→MPa，键名对齐 RenderData。"""
    if not rd:
        return None
    mm = 1000.0
    ux = rd.get("ux", [])
    uy = rd.get("uy", [])
    return {
        "field": "von_mises",
        "unit": "MPa",
        "nodes": [
            [round(float(a) * mm, 3), round(float(b) * mm, 3)]
            for a, b in rd.get("nodes", [])
        ],
        "tris": rd.get("elements", []),
        "values": [round(float(v) / 1e6, 4) for v in rd.get("von_mises", [])],
        "value_location": "node",
        "disp": [
            [round(float(a) * mm, 4), round(float(b) * mm, 4)]
            for a, b in zip(ux, uy)
        ],
    }


def _to_fea_result(r: Dict[str, Any]) -> Dict[str, Any]:
    """engine 输出 → Kotlin FeaResult 字段结构。"""
    metrics = r.get("metrics", {})
    timing = r.get("timing", {})
    mesh = r.get("mesh", {})
    theory = r.get("theory_check") or {}
    extras = theory.get("extras") or {}

    checks = []
    for c in r.get("extra_checks", []):
        conclusion = str(c.get("conclusion", ""))
        checks.append(
            {
                "name": c.get("name", ""),
                # 通用数值字段：强度类为 MPa，挠度建议类为 mm（Kotlin 注释已说明）
                "value_MPa": c.get("value"),
                "limit_MPa": c.get("limit"),
                "safety_factor": c.get("safety_factor"),
                "pass": conclusion.startswith("通过") or conclusion.startswith("满足"),
            }
        )

    stress_theory = extras.get("root_bending_stress_mpa")
    if stress_theory is None:
        stress_theory = extras.get("mid_bending_stress_mpa", 0.0)

    return {
        "template_id": r.get("template", ""),
        "metrics": {
            "max_deflection_mm": metrics.get("max_deflection_mm", 0.0),
            "max_deflection_desc": metrics.get("max_deflection_desc", ""),
            "max_stress_MPa": metrics.get("max_von_mises_mpa", 0.0),
            "safety_factor": metrics.get("safety_factor"),
            "verdict": metrics.get("safety_class", ""),
            "solve_time_ms": round(float(timing.get("total_s", 0.0)) * 1000.0, 1),
        },
        "extra_checks": checks,
        "assumptions": r.get("assumptions", []),
        "render": _to_render(r.get("render")),
        "timing": {
            "total_ms": round(float(timing.get("total_s", 0.0)) * 1000.0, 1),
            "assemble_ms": round(float(timing.get("assemble_s", 0.0)) * 1000.0, 1),
            "solve_ms": round(float(timing.get("solve_s", 0.0)) * 1000.0, 1),
        },
        "mesh": {
            "dofs": mesh.get("dof", 0),
            "elements": mesh.get("n_elements", 0),
            "mesh_level": mesh.get("level", "medium"),
            "adjusted": (
                "downgraded" if mesh.get("degraded")
                else ("refined" if mesh.get("refined") else None)
            ),
            "adjusted_reason": _reason_text(mesh.get("degrade_reason")),
            "solver": "scikit-fem/P2",
        },
        "theory_check": {
            "max_deflection_mm": theory.get("value_mm", 0.0),
            "stress_MPa": stress_theory,
            "error_pct": theory.get("error_pct") or 0.0,
        },
        "analogy": metrics.get("deflection_analogy", ""),
        "narrative": r.get("narrative", []),
        "params_effective": r.get("params", {}),
    }


def _run_with_case_log(solve, model: Dict[str, Any], data_dir: str,
                       logging_on: bool, meta: Dict[str, Any]) -> Dict[str, Any]:
    """统一案例埋点包装（规则 LOG-01：全部求解入口共用，成功/失败都记录）。

    仅 logging_on（调试包 case_log_enabled="1"）时写 {data_dir}/case_log.jsonl；
    埋点自身失败绝不影响主流程。meta 为从 model._case_meta 弹出的用户上下文。
    solve 为无参闭包，返回求解结果 dict（含 ok 字段）。
    """
    from . import case_log
    user_text = str(meta.get("user_text") or "")
    session_id = str(meta.get("session_id") or "")
    input_form = str(meta.get("input_form") or "text")

    def _append(result, error, detected):
        if not logging_on:
            return
        try:
            rec = case_log.build_record(
                model=model, result=result, error=error,
                user_text=user_text, session_id=session_id,
                input_form=input_form, stage="solve", detected=detected,
                data_dir=str(data_dir),
            )
            case_log.append_case_log(str(data_dir), rec)
        except Exception:
            pass  # 埋点失败不能影响主流程

    try:
        result = solve()
    except Exception as exc:
        # 求解失败也埋点：错误信息即失败现象，detected=False（求解才暴露）
        _append(None, str(exc), False)
        raise
    _append(result, None, None)
    return result


@_friendly_errors("仿真")
def run_scenario(request_json: str, case_log_enabled: str = "0") -> str:
    """执行一次求解（Kotlin ScenarioRequest JSON 入，FeaResult JSON 出）。

    engine 返回 {"ok": false} 时抛 ValueError——Chaquopy 侧表现为 PyException，
    Kotlin 统一转 FeaEngineException 向用户展示中文错误信息。
    案例埋点开关与 run_solid_generic 一致（case_log_enabled="1" 才写）。
    """
    try:
        req = json.loads(request_json)
    except json.JSONDecodeError as exc:
        raise ValueError(f"求解请求不是合法 JSON：{exc}") from exc
    if not isinstance(req, dict):
        raise ValueError("求解请求必须是 JSON 对象")

    template_id = str(req.get("template_id", ""))
    params = dict(req.get("params") or {})
    mesh_level = str(req.get("mesh_level") or "medium")
    data_dir = req.get("data_dir")
    meta = req.pop("_case_meta", None) or {}

    # 联网检索缓存材料位于 App 私有目录：先切换默认库数据目录再解析材料名
    if data_dir:
        set_default_data_dir(str(data_dir))

    def _solve():
        result = engine.run_scenario(
            template_id, _coerce_params(template_id, params), mesh_level=mesh_level
        )
        if not result.get("ok"):
            if result.get("need_material_lookup"):
                raise ValueError(
                    f"材料库中未找到“{result['need_material_lookup']}”："
                    f"请通过联网检索补全材料参数后重试"
                )
            raise ValueError("；".join(result.get("errors", ["求解失败"])))
        return result

    log_model = {"template_id": template_id, "params": params,
                 "mesh_level": mesh_level}
    result = _run_with_case_log(
        _solve, log_model, str(data_dir or ""), case_log_enabled == "1", meta
    )
    return _dumps(_to_fea_result(result))


# ---------------------------------------------------------------------- 框架求解
@_friendly_errors("梁杆框架")
def run_frame(model_json: str, data_dir: str = "", lang: str = "zh",
              units: str = "metric", case_log_enabled: str = "0") -> str:
    """2D 梁杆框架求解（Kotlin FrameModel JSON 入，FrameResult JSON 出）。

    frame.run_frame 校验/求解失败抛 ValueError（与 run_scenario 同一错误通道）。
    返回结构与 Kotlin FrameResult 字段一一对应：指标、杆件结果、变形折线、通俗叙述。
    案例埋点开关与 run_solid_generic 一致（case_log_enabled="1" 才写）。
    """
    from . import frame as frame_mod
    set_locale(lang, units)

    try:
        model = json.loads(model_json)
    except json.JSONDecodeError as exc:
        raise ValueError(f"框架模型不是合法 JSON：{exc}") from exc
    if not isinstance(model, dict):
        raise ValueError("框架模型必须是 JSON 对象")

    meta = model.pop("_case_meta", None) or {}
    lib = MaterialsLibrary(data_dir=str(data_dir) if data_dir else None)
    result = _run_with_case_log(
        lambda: frame_mod.run_frame(model, library=lib),
        model, str(data_dir), case_log_enabled == "1", meta,
    )
    return _dumps(result)


@_friendly_errors("风荷载墙体")
def run_flow(model_json: str, data_dir: str = "", lang: str = "zh",
             units: str = "metric", case_log_enabled: str = "0") -> str:
    """风荷载墙体流体仿真（Kotlin FlowModel JSON 入，FlowResult JSON 出）。

    势流有限元给出压力云图与流线，结构侧按规范体型系数做抗倾覆/抗滑移/抗弯校核。
    校验或求解失败抛 ValueError（与其它求解同一错误通道）。
    案例埋点开关与 run_solid_generic 一致（case_log_enabled="1" 才写）。
    """
    from . import flow as flow_mod
    set_locale(lang, units)

    try:
        model = json.loads(model_json)
    except json.JSONDecodeError as exc:
        raise ValueError(f"流体模型不是合法 JSON：{exc}") from exc
    if not isinstance(model, dict):
        raise ValueError("流体模型必须是 JSON 对象")

    meta = model.pop("_case_meta", None) or {}
    lib = MaterialsLibrary(data_dir=str(data_dir) if data_dir else None)
    result = _run_with_case_log(
        lambda: flow_mod.run_flow(model, library=lib),
        model, str(data_dir), case_log_enabled == "1", meta,
    )
    return _dumps(result)


@_friendly_errors("渗流")
def run_seepage(model_json: str, data_dir: str = "", lang: str = "zh",
                units: str = "metric", case_log_enabled: str = "0") -> str:
    """大坝坝基渗流有限元（Kotlin SeepageModel JSON 入，SeepageResult JSON 出）。

    二维稳态达西渗流给出坝基水头场/渗流线、扬压力、单宽渗流量与出逸比降，
    再做抗滑/抗倾覆/抗管涌校核。校验或求解失败抛 ValueError。
    案例埋点开关与 run_solid_generic 一致（case_log_enabled="1" 才写）。
    """
    from . import seepage as seepage_mod
    set_locale(lang, units)

    try:
        model = json.loads(model_json)
    except json.JSONDecodeError as exc:
        raise ValueError(f"渗流模型不是合法 JSON：{exc}") from exc
    if not isinstance(model, dict):
        raise ValueError("渗流模型必须是 JSON 对象")

    meta = model.pop("_case_meta", None) or {}
    lib = MaterialsLibrary(data_dir=str(data_dir) if data_dir else None)
    result = _run_with_case_log(
        lambda: seepage_mod.run_seepage(model, library=lib),
        model, str(data_dir), case_log_enabled == "1", meta,
    )
    return _dumps(result)


@_friendly_errors("三维实体")
def run_solid(model_json: str, data_dir: str = "", lang: str = "zh",
              units: str = "metric", case_log_enabled: str = "0") -> str:
    """三维实体线弹性求解（外部 JSON 入，SolidResult JSON 出）。

    当前支持参数化墙体在风压（或直接均布压力）与自重下的三维实体计算，
    输出位移/应力/安全系数与逐节点渲染数据。校验或求解失败抛 ValueError。
    案例埋点开关与 run_solid_generic 一致（case_log_enabled="1" 才写）。
    """
    from . import solid as solid_mod
    set_locale(lang, units)

    try:
        model = json.loads(model_json)
    except json.JSONDecodeError as exc:
        raise ValueError(f"实体模型不是合法 JSON：{exc}") from exc
    if not isinstance(model, dict):
        raise ValueError("实体模型必须是 JSON 对象")

    meta = model.pop("_case_meta", None) or {}
    lib = MaterialsLibrary(data_dir=str(data_dir) if data_dir else None)
    result = _run_with_case_log(
        lambda: solid_mod.run_solid(model, library=lib),
        model, str(data_dir), case_log_enabled == "1", meta,
    )
    return _dumps(result)


@_friendly_errors("三维实体")
def run_solid_generic(model_json: str, data_dir: str = "", lang: str = "zh",
                      units: str = "metric", case_log_enabled: str = "0") -> str:
    """通用三维实体 TET4 求解（box/cylinder/plate 等图元，JSON 入/出）。

    与 run_solid（墙体专用）的区别：几何由 geometry.type 决定，约束由 supports
    定义，载荷由 loads 定义；后处理只保留通用量。失败抛 ValueError。

    案例埋点：仅当 case_log_enabled == "1"（调试包）时，才把本次求解写入
    {data_dir}/case_log.jsonl（字段对齐《CAE 失败案例记录表》A-S 列）；正式包
    传 "0"，一次都不写。用户上下文由 model._case_meta 透传，无论是否埋点都会
    在此弹出，避免泄漏给求解器。
    """
    from . import solid as solid_mod
    set_locale(lang, units)

    try:
        model = json.loads(model_json)
    except json.JSONDecodeError as exc:
        raise ValueError(f"实体模型不是合法 JSON：{exc}") from exc
    if not isinstance(model, dict):
        raise ValueError("实体模型必须是 JSON 对象")

    # 用户上下文（Kotlin 侧在调用前注入到 _case_meta，求解器不读取）；
    # 即使埋点关闭也要弹出，避免污染下游求解模型
    meta = model.pop("_case_meta", None) or {}
    lib = MaterialsLibrary(data_dir=str(data_dir) if data_dir else None)
    result = _run_with_case_log(
        lambda: solid_mod.run_solid_generic(model, library=lib),
        model, str(data_dir), case_log_enabled == "1", meta,
    )
    return _dumps(result)


@_friendly_errors("薄壁容器")
def run_shell(model_json: str, data_dir: str = "", lang: str = "zh",
              units: str = "metric", case_log_enabled: str = "0") -> str:
    """薄壁容器壳静力求解（矩形箱 / 圆柱仓，JSON 入/出）。

    支持 kind="shell_vessel"（geometry.section="rect"|"circle"）及兼容的
    "shell_tank"（矩形水箱）：4 节点 Reissner–Mindlin 平板壳（MITC4 抗
    剪切锁死）中面模型，填料载荷（流体静压 / 散体 Rankine·Janssen 侧压 +
    壁摩擦）+ 自重，输出位移/表面应力/安全系数与逐节点渲染数据。返回结构与
    run_solid_generic 同构（Kotlin SolidGenericResult / SolidRenderData 可
    直接解码，四边形以 2 个三角形下发）。失败抛 ValueError；案例埋点开关与
    run_solid_generic 一致。
    """
    from . import shell as shell_mod
    set_locale(lang, units)

    try:
        model = json.loads(model_json)
    except json.JSONDecodeError as exc:
        raise ValueError(f"壳体模型不是合法 JSON：{exc}") from exc
    if not isinstance(model, dict):
        raise ValueError("壳体模型必须是 JSON 对象")

    meta = model.pop("_case_meta", None) or {}
    lib = MaterialsLibrary(data_dir=str(data_dir) if data_dir else None)
    result = _run_with_case_log(
        lambda: shell_mod.run_vessel(model, library=lib),
        model, str(data_dir), case_log_enabled == "1", meta,
    )
    return _dumps(result)


def run_buried_pipe(model_json: str, data_dir: str = "",
                    case_log_enabled: str = "0", lang: str = "zh",
                    units: str = "mm") -> str:
    """埋地管工程公式校核（shell_buried_pipe）的跨边界入口。
    model_json 为 AI 建模产物 JSON；返回结果 JSON 字符串。失败抛 ValueError；
    案例埋点与 run_shell 一致。
    """
    from . import buried_pipe
    set_locale(lang, units)

    try:
        model = json.loads(model_json)
    except json.JSONDecodeError as exc:
        raise ValueError(f"埋地管模型不是合法 JSON：{exc}") from exc
    if not isinstance(model, dict):
        raise ValueError("埋地管模型必须是 JSON 对象")

    meta = model.pop("_case_meta", None) or {}
    lib = MaterialsLibrary(data_dir=str(data_dir) if data_dir else None)
    result = _run_with_case_log(
        lambda: buried_pipe.run_buried_pipe(model, library=lib),
        model, str(data_dir), case_log_enabled == "1", meta,
    )
    return _dumps(result)


def log_case_context(context_json: str, data_dir: str = "",
                     case_log_enabled: str = "0") -> str:
    """Kotlin 侧记录非求解阶段案例（如校验失败）的统一入口。

    context_json 字段：model(对象), error(字符串), user_text, session_id,
    input_form, stage(默认 "validate"), detected(默认 true)。
    仅当 case_log_enabled == "1"（调试包）才写文件；正式包直接返回空串。
    """
    from . import case_log
    if case_log_enabled != "1":
        return ""
    try:
        ctx = json.loads(context_json)
    except json.JSONDecodeError as exc:
        raise ValueError(f"案例上下文不是合法 JSON：{exc}") from exc
    if not isinstance(ctx, dict):
        raise ValueError("案例上下文必须是 JSON 对象")
    # llm_attempts：LLM 总尝试次数（首轮为 1），非数字/缺省为 None；
    # first_error：首轮错误信息（含错误码），用于一次/重试后通过率统计。
    attempts_raw = ctx.get("llm_attempts")
    try:
        llm_attempts = int(attempts_raw) if attempts_raw is not None else None
    except (TypeError, ValueError):
        llm_attempts = None
    first_error = ctx.get("first_error")
    first_error = str(first_error) if first_error else None
    rec = case_log.build_record(
        model=ctx.get("model") or {},
        result=None,
        error=str(ctx.get("error") or ""),
        user_text=str(ctx.get("user_text") or ""),
        session_id=str(ctx.get("session_id") or ""),
        input_form=str(ctx.get("input_form") or "text"),
        stage=str(ctx.get("stage") or "validate"),
        detected=bool(ctx.get("detected", True)),
        data_dir=str(data_dir),
        llm_attempts=llm_attempts,
        first_error=first_error,
    )
    return case_log.append_case_log(str(data_dir), rec)


def export_case_log_excel(data_dir: str = "", output_path: str = "") -> str:
    """把 {data_dir}/case_log.jsonl 导出为 xlsx，列顺序对齐《CAE 失败案例记录表》A-S 列。

    返回输出 xlsx 的绝对路径（默认 {data_dir}/case_log.xlsx）。
    """
    from . import case_log
    return case_log.export_to_excel(str(data_dir), output_path=str(output_path) if output_path else "")


@_friendly_errors("空间梁")
def run_frame3d(model_json: str, data_dir: str = "", lang: str = "zh",
                units: str = "metric", case_log_enabled: str = "0") -> str:
    """3D 空间梁框架求解（P3D-1）。

    输入 JSON 的节点可带 z 坐标；节点全部 z=0 且无面外载荷时自动退化为 2D 求解
    （返回 kind="frame"），保证现有 2D 算例行为不变。可用 "dim": 3 强制走 3D。
    案例埋点开关与 run_solid_generic 一致（case_log_enabled="1" 才写）。
    """
    from . import frame3d as frame3d_mod
    set_locale(lang, units)

    try:
        model = json.loads(model_json)
    except json.JSONDecodeError as exc:
        raise ValueError(f"框架模型不是合法 JSON：{exc}") from exc
    if not isinstance(model, dict):
        raise ValueError("框架模型必须是 JSON 对象")

    meta = model.pop("_case_meta", None) or {}
    lib = MaterialsLibrary(data_dir=str(data_dir) if data_dir else None)
    result = _run_with_case_log(
        lambda: frame3d_mod.run_frame3d(model, library=lib),
        model, str(data_dir), case_log_enabled == "1", meta,
    )
    return _dumps(result)


@_friendly_errors("框架稳定性预检")
def check_frame_stability(model_json: str, data_dir: str = "", lang: str = "zh",
                          units: str = "metric") -> str:
    """框架几何不变性轻量预检：只组装刚度阵 + 特征值判定，不求解。

    用于候选生成阶段剔除机构 / 近机构模型，避免进入预览算出无意义大变形。
    路由与 run_frame3d 一致：dim=3/3d 或非共面模型走 3D 判定，可退化为平面的
    模型走 2D 判定。
    返回 JSON：稳定 {"ok": true}；不稳 {"ok": false, "level": ..., "reason": ...}。
    level 为 "mechanism"（机构，几何可变）或 "near_mechanism"（近机构，弱刚度）。
    """
    from . import frame as frame_mod
    from . import frame3d as frame3d_mod
    from .stability import classify_stability, DOF_LABELS_2D, DOF_LABELS_3D
    set_locale(lang, units)

    try:
        model = json.loads(model_json)
    except json.JSONDecodeError as exc:
        raise ValueError(f"框架模型不是合法 JSON：{exc}") from exc
    if not isinstance(model, dict):
        raise ValueError("框架模型必须是 JSON 对象")

    # 与求解路径同一材料库（含联网检索缓存），保证 E/G 一致、判定可比
    lib = MaterialsLibrary(data_dir=str(data_dir) if data_dir else None)
    model = dict(model)
    model["_library"] = lib

    force_3d = str(model.get("dim", "")).strip() in ("3", "3d")
    if not force_3d and frame3d_mod._is_planar_2d(model):
        K, free, m = frame_mod._assemble_free_stiffness(model)
        labels, dofs_per_node = DOF_LABELS_2D, 3
    else:
        K, free, m = frame3d_mod._assemble_free_stiffness(model)
        labels, dofs_per_node = DOF_LABELS_3D, 6

    if not free.any():
        return _dumps({"ok": True})  # 全约束 → 几何不变（即使无外载位移）
    k_ff = K[np.ix_(free, free)]
    verdict = classify_stability(k_ff, np.flatnonzero(free), m["nodes"],
                                 labels, dofs_per_node)
    if verdict.level == "stable":
        return _dumps({"ok": True})
    return _dumps({"ok": False, "level": verdict.level, "reason": verdict.reason})


@_friendly_errors("温度历程")
def run_transient(spec_json: str, data_dir: str = "", lang: str = "zh",
                  units: str = "metric", case_log_enabled: str = "0") -> str:
    """温度-载荷时间历程（Kotlin TransientSpec JSON 入，TransientResult JSON 出）。

    准静态热-力耦合：温度循环逐时刻替换各杆件温度载荷后复用框架求解，
    返回曲线、逐时刻变形/内力快照与最不利时刻。校验或求解失败抛 ValueError。
    案例埋点开关与 run_solid_generic 一致（case_log_enabled="1" 才写）。
    """
    from . import transient as transient_mod
    set_locale(lang, units)

    try:
        spec = json.loads(spec_json)
    except json.JSONDecodeError as exc:
        raise ValueError(f"时间历程参数不是合法 JSON：{exc}") from exc
    if not isinstance(spec, dict):
        raise ValueError("时间历程参数必须是 JSON 对象")

    meta = spec.pop("_case_meta", None) or {}
    lib = MaterialsLibrary(data_dir=str(data_dir) if data_dir else None)
    result = _run_with_case_log(
        lambda: transient_mod.run_transient(spec, library=lib),
        spec, str(data_dir), case_log_enabled == "1", meta,
    )
    return _dumps(result)


# ---------------------------------------------------------------------- PDF 导出
def export_frame_pdf(model_json: str, result_json: str, filepath: str,
                     data_dir: str = "", lang: str = "zh", units: str = "metric") -> str:
    """把框架模型与求解结果导出为 PDF 报告，返回文件路径。

    Kotlin 侧把已计算的 FrameModel 和 FrameResult JSON 传进来，避免重复求解；
    filepath 由 Kotlin 指定（App 外部存储 / MediaStore）。
    lang/units 决定报告文字语言与输出单位制（求解数值本身仍为 SI）。
    """
    from . import pdf_report

    set_locale(lang, units)

    try:
        model = json.loads(model_json)
        result = json.loads(result_json)
    except json.JSONDecodeError as exc:
        raise ValueError(f"导出 PDF 入参不是合法 JSON：{exc}") from exc

    if data_dir:
        set_default_data_dir(str(data_dir))

    return pdf_report.export_frame_pdf(result, model, filepath)


def export_solid_generic_pdf(model_json: str, result_json: str, filepath: str,
                             data_dir: str = "", lang: str = "zh",
                             units: str = "metric") -> str:
    """把通用三维实体模型与求解结果导出为 PDF 报告，返回文件路径。"""
    from . import pdf_report

    set_locale(lang, units)

    try:
        model = json.loads(model_json)
        result = json.loads(result_json)
    except json.JSONDecodeError as exc:
        raise ValueError(f"导出 PDF 入参不是合法 JSON：{exc}") from exc

    if data_dir:
        set_default_data_dir(str(data_dir))

    return pdf_report.export_solid_pdf(result, model, filepath)


# ---------------------------------------------------------------------- 材料
def _record_to_kotlin(rec: Dict[str, Any]) -> Dict[str, Any]:
    """Python 材料记录 → Kotlin MaterialRecord 字段（E/强度换算为 MPa）。"""
    src = str(rec.get("source", ""))
    if "联网" in src or "web" in src.lower():
        source = "web"
    elif rec.get("system") == "user":
        source = "user"
    else:
        source = "builtin"
    return {
        "id": rec.get("id", ""),
        "name": rec.get("name", ""),
        "category": rec.get("category", ""),
        "E_MPa": round(float(rec.get("elastic_modulus", 0.0)) / 1e6, 1),
        "nu": rec.get("poisson_ratio", 0.3),
        "density_kg_m3": rec.get("density"),
        "strength_MPa": rec.get("strength"),
        "strength_type": rec.get("strength_type", ""),
        "notes": rec.get("notes", ""),
        "source": source,
        # 仅用户材料（联网检索/手动添加）可删除；内置 gb/us 材料只读
        "deletable": rec.get("system") == "user",
    }


def list_materials(data_dir: str = "", system: str = "") -> str:
    """材料列表（内置库 + data_dir 下的联网检索缓存副本合并）。

    system="gb"/"us" 时只返回对应单位制内置库 + 用户材料；空串返回全部。
    """
    lib = MaterialsLibrary(data_dir=str(data_dir) if data_dir else None)
    lib.load_library()
    return _dumps([_record_to_kotlin(r) for r in lib.all_materials(system or None)])


def upsert_material(record_json: str, data_dir: str = "") -> str:
    """联网检索材料入库（Kotlin MaterialRecord JSON 入，规范化记录 JSON 出）。

    写入 data_dir/materials_user.json（App 私有目录），source 标记“联网检索+日期”。
    """
    try:
        rec_in = json.loads(record_json)
    except json.JSONDecodeError as exc:
        raise ValueError(f"材料记录不是合法 JSON：{exc}") from exc
    if not isinstance(rec_in, dict):
        raise ValueError("材料记录必须是 JSON 对象")

    record: Dict[str, Any] = {
        "name": str(rec_in.get("name", "")),
        "elastic_modulus": float(rec_in.get("E_MPa", 0.0)) * 1e6,
        "poisson_ratio": float(rec_in.get("nu", 0.3)),
        "density": rec_in.get("density_kg_m3"),
        "strength": rec_in.get("strength_MPa"),
        "strength_type": str(rec_in.get("strength_type", "") or "强度代表值"),
        "notes": str(rec_in.get("notes", "") or ""),
    }
    if rec_in.get("id"):
        record["id"] = str(rec_in["id"])

    if not data_dir:
        raise ValueError("缺少 data_dir：Android 端请传入 context.getFilesDir()")
    lib = set_default_data_dir(str(data_dir))
    saved, _action = lib.upsert_material(record, source_label="联网检索")
    lib.save_library()
    return _dumps(_record_to_kotlin(saved))


def delete_material(material_id: str, data_dir: str = "") -> str:
    """删除联网检索/用户自填材料（内置材料不可删除），返回 {"ok": bool}。"""
    if not data_dir:
        raise ValueError("缺少 data_dir：Android 端请传入 context.getFilesDir()")
    lib = set_default_data_dir(str(data_dir))
    ok = lib.delete_material(str(material_id))
    if ok:
        lib.save_library()
    return _dumps({"ok": ok})
