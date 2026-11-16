# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""仿真引擎入口（Android 唯一入口，通过 Chaquopy 调用）。

run_scenario(template_id, params, mesh_level) 完整流程：
1. 参数校验（schema 范围/必填检查）→ 不合法返回 {"ok": false, "errors": [...]}；
2. 材料解析（内置库模糊匹配）→ 找不到返回
   {"ok": false, "need_material_lookup": 材料名}（Android 端据此触发联网检索）；
3. 有限元求解（记录耗时与峰值内存）；
4. 网格收敛自检：中档解与细档解的最大位移差异 >10% 时自动采用细档结果
   并标注 mesh.refined=true（仅当中档 DOF < 3 万时启用，避免端上耗时失控）；
5. 性能降级：求解耗时超过 timeout_s（默认 60s）时自动降级粗档重算并标注
   mesh.degraded=true；
6. 汇总输出：指标（挠度 mm / von Mises MPa / 安全系数与分级）、理论解对照、
   简化校核项、建模假设、渲染数据、计时与网格信息，全部可 JSON 序列化。

list_templates() 返回模板元信息与参数表（供 AI 抽参与表单 UI 使用）。
"""
from __future__ import annotations

import time
from typing import Any, Dict, List, Optional, Tuple

from . import interpret
from .materials import find_material
from .narrative import build_narrative
from .solver import run_solve_config
from .templates import ALL_TEMPLATES, TEMPLATES, ScenarioTemplate

# 网格收敛自检启用阈值：中档 DOF 低于该值才做 medium vs fine 对比
CONVERGENCE_CHECK_DOF_LIMIT = 30000
# 收敛判定阈值：最大位移相对差异超过 10% 视为未收敛，改用细档结果
CONVERGENCE_TOLERANCE = 0.10
# 默认性能降级超时（秒）
DEFAULT_TIMEOUT_S = 60.0

# ---- 内存保护（经验系数，由 benchmark.py 实测校准，见 benchmark_report.md）----
# 默认内存预算（MB）：中端安卓机 App 可用堆上限的安全估计
DEFAULT_MEM_LIMIT_MB = 900.0
# 稀疏直接法（LU）峰值内存经验系数：100k DOF ≈ 1.1GB → 约 11 KB/DOF
DIRECT_MEM_KB_PER_DOF = 11.0
# CG 迭代法峰值内存经验系数：100k DOF ≈ 0.3GB → 约 3 KB/DOF
CG_MEM_KB_PER_DOF = 3.0
# 直接法/迭代法切换阈值（与 solver.CG_DOF_THRESHOLD 保持一致）
CG_DOF_THRESHOLD = 50000


def estimate_dof(nx: int, ny: int) -> int:
    """按网格分段数估算总自由度（P2 向量单元：2×(节点+边)≈8×顶点数）。"""
    return int(8.0 * (nx + 1) * (ny + 1))


def estimate_memory_mb(dof: int) -> float:
    """按 DOF 估算求解峰值内存（MB）。求解器 DOF>5 万自动切 CG，故用对应系数。"""
    kb_per_dof = DIRECT_MEM_KB_PER_DOF if dof <= CG_DOF_THRESHOLD else CG_MEM_KB_PER_DOF
    return dof * kb_per_dof / 1024.0


def _downgrade_level(level: str) -> str:
    """网格档位降一级：fine→medium→coarse（已是 coarse 则保持）。"""
    return {"fine": "medium", "medium": "coarse"}.get(level, "coarse")


# ---------------------------------------------------------------------- 内部
def _resolve_materials(
    template: ScenarioTemplate, params: Dict
) -> Tuple[Optional[Dict], Optional[str]]:
    """预解析模板全部材料参数。

    返回 (回填了材料记录的 params 副本, None) 或 (None, 未找到的材料名)。
    已是 dict（上游已解析）的材料原样保留；字符串材料名查库命中后替换为
    完整记录，后续 build/theory_check 无需再次查库。
    """
    resolved = dict(params)
    for key, default_name in template.material_param_keys:
        raw = resolved.get(key, default_name)
        if raw is None or isinstance(raw, dict):
            continue  # 缺失必填材料由 validate 捕获；dict 为已解析记录
        record = find_material(str(raw))
        if record is None:
            return None, str(raw)
        resolved[key] = record
    return resolved, None


def _theory_payload(theory: Dict, fea_value: float) -> Dict:
    """理论解对照输出：理论值 + FEA 同口径读数 + 相对误差百分比。"""
    value = float(theory.get("value", 0.0))
    payload = dict(theory)
    payload["fea_value"] = float(fea_value)
    payload["fea_value_mm"] = float(fea_value * 1000.0)
    if value > 0.0:
        payload["error_pct"] = round(abs(fea_value - value) / value * 100.0, 3)
    else:
        payload["error_pct"] = None
    return payload


# ---------------------------------------------------------------------- 主入口
def run_scenario(
    template_id: str,
    params: Optional[Dict] = None,
    mesh_level: str = "medium",
    timeout_s: float = DEFAULT_TIMEOUT_S,
    mem_limit_mb: float = DEFAULT_MEM_LIMIT_MB,
) -> Dict:
    """运行一个生活场景仿真，返回可 JSON 序列化的结果字典。

    参数
    ----
    template_id : str   模板 id（见 list_templates）
    params : dict       场景参数（mm/kg/N/MPa 等生活单位）
    mesh_level : str    网格档位 coarse / medium / fine
    timeout_s : float   单次求解耗时上限，超过自动降级粗档重算
    mem_limit_mb : float 求解内存预算（MB），估算超限自动降一档网格
    """
    template = TEMPLATES.get(template_id)
    if template is None:
        return {
            "ok": False,
            "errors": [f"未知模板 {template_id!r}，可选：{sorted(TEMPLATES)}"],
        }
    if params is None:
        params = {}
    if not isinstance(params, dict):
        return {
            "ok": False,
            "errors": [f"params 必须是字典，当前类型为 {type(params).__name__}"],
        }

    # ---- 1. 参数校验 ----
    errors = template.validate(params)
    if errors:
        return {"ok": False, "errors": errors}

    # ---- 2. 材料解析 ----
    resolved, missing = _resolve_materials(template, params)
    if missing is not None:
        return {
            "ok": False,
            "need_material_lookup": missing,
            "errors": [
                f"材料库中未找到“{missing}”：请换用内置材料名，"
                f"或联网检索后通过材料库接口 upsert_material 写入再重试"
            ],
        }

    # ---- 3. 求解（含内存保护、网格收敛自检与性能降级） ----
    t_engine = time.perf_counter()
    final_level = mesh_level
    refined = False
    degraded = False
    degrade_reason: Optional[str] = None

    config = template.build(resolved, final_level)
    # 内存保护：按 DOF 估算峰值内存，超预算自动降一档网格（coarse 为最后防线）
    est_dof = estimate_dof(config.nx, config.ny)
    while (estimate_memory_mb(est_dof) > mem_limit_mb
           and final_level != "coarse"):
        final_level = _downgrade_level(final_level)
        config = template.build(resolved, final_level)
        est_dof = estimate_dof(config.nx, config.ny)
        degraded = True
        degrade_reason = "memory"

    try:
        result = run_solve_config(config)
    except MemoryError:
        # 实际内存不足：降一档重算一次（coarse 档失败则向上抛出）
        if final_level == "coarse":
            raise
        final_level = _downgrade_level(final_level)
        config = template.build(resolved, final_level)
        result = run_solve_config(config)
        degraded = True
        degrade_reason = "memory"

    if result.total_time_s > timeout_s:
        # 性能降级：超时则降级粗档重算（仍超时就以粗档结果为准并如实标注）
        coarse = run_solve_config(template.build(resolved, "coarse"))
        degraded = True
        degrade_reason = "timeout"
        final_level = "coarse"
        if coarse.total_time_s < result.total_time_s:
            result = coarse
    elif mesh_level == "medium" and result.dof < CONVERGENCE_CHECK_DOF_LIMIT:
        # 网格收敛自检：medium vs fine 最大位移差异 >10% 时采用细档结果
        fine = run_solve_config(template.build(resolved, "fine"))
        d_medium = result.max_displacement
        d_fine = fine.max_displacement
        if d_fine > 1e-12 and abs(d_fine - d_medium) / d_fine > CONVERGENCE_TOLERANCE:
            result = fine
            refined = True
            final_level = "fine"

    # ---- 4. 读数与通俗化解读 ----
    theory = template.theory_check(resolved)
    fea_delta_m = template.fea_reading(resolved, result)   # 与理论同口径，m
    theory_payload = _theory_payload(theory, fea_delta_m)

    vm_mpa = result.max_von_mises / 1e6
    primary = template._material(resolved, template.primary_material_key)
    strength_mpa = float(primary["strength"])
    label, factor = interpret.safety_class(vm_mpa, strength_mpa)

    delta_mm = fea_delta_m * 1000.0
    metrics = {
        "max_deflection_mm": round(delta_mm, 4),
        "max_deflection_desc": interpret.deflection_summary(delta_mm, label),
        "deflection_analogy": interpret.life_analogy(delta_mm),
        "max_von_mises_mpa": round(vm_mpa, 3),
        "safety_factor": round(factor, 3),
        "safety_class": label,
        "safety_factor_analogy": interpret.safety_factor_analogy(factor),
        "reactions_N": {
            "fx": round(result.reactions["fx"], 3),
            "fy": round(result.reactions["fy"], 3),
        },
    }

    engine_total_s = time.perf_counter() - t_engine
    effective = template.effective_params(params)
    extra_checks = template.extra_checks(resolved, result)
    assumptions = template.assumptions(resolved)
    narrative = build_narrative(
        template, effective, metrics, extra_checks, assumptions, theory_payload
    )
    return {
        "ok": True,
        "template": template_id,
        "template_name": template.name,
        "params": effective,
        "metrics": metrics,
        "theory_check": theory_payload,
        "extra_checks": extra_checks,
        "assumptions": assumptions,
        "narrative": narrative,
        "render": result.nodal_field_for_render(),
        "timing": {
            "assemble_s": round(result.assemble_time_s, 3),
            "solve_s": round(result.solve_time_s, 3),
            "total_s": round(result.total_time_s, 3),
            "engine_total_s": round(engine_total_s, 3),
            "peak_memory_mb": round(result.peak_memory_mb, 1),
            "solver_method": result.solver_method,
        },
        "mesh": {
            "level": final_level,
            "requested_level": mesh_level,
            "dof": int(result.dof),
            "n_elements": int(result.n_elements),
            "n_vertices": int(result.n_vertices),
            "refined": refined,
            "degraded": degraded,
            "degrade_reason": degrade_reason,
        },
    }


def list_templates() -> Dict:
    """列出全部模板元信息与参数表（供 AI 抽参与手动填参 UI 使用）。"""
    return {
        "ok": True,
        "templates": [t.meta() for t in ALL_TEMPLATES],
        "mesh_levels": ["coarse", "medium", "fine"],
    }
