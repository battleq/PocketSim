# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""埋地柔性管工程公式校核（kind=shell_buried_pipe）。

典型场景：一根直径 50 mm、壁厚 3 mm 的 PVC 管埋在 2 m 深土中，上面有车辆经过，
会不会损坏？本模块用经典工程公式给出「安全 / 需关注 / 不安全」结论 + 关键数值，
并生成变形椭圆环（挠度放大）用于可视化。

计算内容（均为线弹性、小变形、单位长度管环 / 长管平面应变）：
  1) 覆土自重：管顶竖向土压 p_soil = γ·H（Marston 土柱/沟槽简化）；
  2) 侧向土压：p_lat = K0·γ·H − 2c·√K0（K0 = 1−sinφ，黏聚力折减后 ≥0）；
  3) 地面车载经覆土扩散到管顶的附加压力：Boussinesq 点荷弹性解（近似、偏保守）；
  4) 环向挠度：圆环在「竖向 − 侧向」净荷载下的 Diametral Compression 环理论
     （Spangler/Iowa 体系的简化，忽略土弹簧刚度 → 挠度偏于保守）；
  5) 环向应力：膜力（p·r/t）+ 弯矩（M = 0.1817·W·r）合成，弯曲应力 6M/t²；
  6) 外压屈曲：Timoshenko 长管 n=2 临界外压 p_cr = 2E(t/D)³/(1−ν²)，
     偏保守（未计入覆土对屈曲的有利约束）；
  7) 结论映射：挠度比 5% / 屈曲 SF 1.2·2.0 / 应力比 0.8·1.0（见 spec FR-5）。

非目标（不做）：全 3D 土-管有限元、动力/地震/冲击、管周非线性土弹簧、
高阶后屈曲、接口/接头、施工荷载。
"""
from __future__ import annotations

import math
from typing import Any, Dict, List, Optional, Tuple

from .i18n import t as _t
from .materials import MaterialsLibrary, load_library

# ---- 默认值（用户未提供时采用，并在 assumptions 中显式告知）----
DEFAULT_SOIL_GAMMA = 18.0      # kN/m³
DEFAULT_SOIL_PHI = 30.0        # deg
DEFAULT_SOIL_COHESION = 0.0    # kPa
DEFAULT_WHEEL_LOAD = 70.0      # kN（公路-Ⅰ级单轮）
DEFAULT_TIRE_CONTACT = 0.3     # m（轮胎接地边长，近似方形）
DEFAULT_INTERNAL_PRESSURE = 0.0  # kPa（空管，最不利外压屈曲工况）

# ---- 判定阈值（spec FR-5）----
DEFLECTION_UNSAFE_PCT = 5.0     # 挠度比 > 5% 不安全
DEFLECTION_CAUTION_PCT = 4.0    # 4%~5% 需关注
BUCKLE_SF_SAFE = 2.0            # ≥2.0 安全
BUCKLE_SF_CAUTION = 1.2         # 1.2~2.0 需关注，<1.2 不安全
STRESS_RATIO_UNSAFE = 1.0       # ≥1.0 不安全
STRESS_RATIO_CAUTION = 0.8      # 0.8~1.0 需关注


def _num(model: Dict[str, Any], key: str) -> Optional[float]:
    if key in model and model[key] is not None:
        try:
            return float(model[key])
        except (TypeError, ValueError):
            raise ValueError(f"参数 {key} 必须是数字")
    return None


def _geo(g: Dict[str, Any], key: str) -> Optional[float]:
    if not isinstance(g, dict):
        return None
    if key in g and g[key] is not None:
        try:
            return float(g[key])
        except (TypeError, ValueError):
            raise ValueError(f"geometry.{key} 必须是数字")
    return None


def _resolve_material(model: Dict[str, Any],
                      library: Optional[MaterialsLibrary]) -> Tuple[str, float, float, float, float]:
    """返回 (显示名, E_Pa, nu, density_kg_m3, strength_MPa)。"""
    lib = library if library is not None else load_library(None)
    name = str(model.get("material") or "").strip()
    if not name:
        raise ValueError("缺少材料（material）")
    rec = lib.find_material(name)
    if rec is None:
        avail = "、".join(r["name"] for r in lib.all_materials())
        raise ValueError(f"材料库中没有「{name}」，可选：{avail}")
    mat_name = str(rec.get("name") or name)
    e_o = _num(model, "E_MPa")
    E = e_o * 1e6 if e_o is not None else float(rec.get("elastic_modulus") or 0.0)
    nu_o = _num(model, "nu")
    nu = nu_o if nu_o is not None else float(rec.get("poisson_ratio") or 0.3)
    rho_o = _num(model, "density_kg_m3")
    rho = rho_o if rho_o is not None else float(rec.get("density") or 0.0)
    str_o = _num(model, "strength_MPa")
    strength = str_o if str_o is not None else float(rec.get("strength") or 0.0)
    if E <= 0.0:
        raise ValueError("材料弹性模量必须为正")
    if strength <= 0.0:
        raise ValueError("材料强度必须为正（用于应力比校核）")
    return mat_name, E, nu, rho, strength


def _boussinesq_live_pressure(P_n: float, H: float) -> float:
    """地面集中力 P 正下方深度 H 处的竖向附加应力（Boussinesq 弹性半空间解，r=0）。
    σ_z = 3P/(2πH²)。有限轮胎 footprint 的积分峰值与点荷解同量级，此处取点荷近似偏保守。"""
    if P_n <= 0.0 or H <= 1e-6:
        return 0.0
    return 3.0 * P_n / (2.0 * math.pi * H * H)


def _ring_deflection(W: float, r: float, t: float, E: float, nu: float
                     ) -> Tuple[float, float]:
    """圆环在对径竖向荷载 W（管顶向内 + 管底向外，单位 N/m）下的环顶竖向挠度 Δ_y
    与环侧水平外胀 Δ_x（单位 m）。长管取平面应变 E' = E/(1−ν²)。

    经典解：Δ_y = (π/4 − 2/π)·W·r³/(E'I) ≈ 0.1488·W·r³/(E'I)，
    Δ_x ≈ 0.1366·W·r³/(E'I)（水平直径增大）。I = t³/12（单位长度矩形条）。
    """
    if W <= 0.0:
        return 0.0, 0.0
    I = t * t * t / 12.0
    Eeff = E / (1.0 - nu * nu)
    base = W * r * r * r / (Eeff * I)
    dy = 0.1488 * base
    dx = 0.1366 * base
    return dy, dx


def _max_ring_stress(W: float, r: float, t: float, p0: float) -> Tuple[float, float, float]:
    """环向最大应力（Pa）：膜应力 σ_m = p0·r/t（压）+ 弯曲 σ_b = 6M/t²，
    M = 0.1817·W·r（管顶/管底弯矩幅值）。返回 (σ_m, σ_b, σ_total_mag)。"""
    sigma_m = p0 * r / t if t > 0 else 0.0  # 压
    M = 0.1817 * W * r if W > 0 else 0.0
    sigma_b = 6.0 * M / (t * t) if t > 0 else 0.0
    return sigma_m, sigma_b, abs(sigma_m) + abs(sigma_b)


def _buckling_pcr(E: float, nu: float, t: float, D: float) -> float:
    """Timoshenko 长管（平面应变）n=2 外压屈曲临界压力 p_cr = 2E(t/D)³/(1−ν²)。
    未计入覆土约束，偏保守（安全方向）。返回 Pa。"""
    if D <= 0.0 or t <= 0.0:
        return 0.0
    return 2.0 * E * (t / D) ** 3 / (1.0 - nu * nu)


def _verdict(deflection_pct: float, buckle_sf: float, stress_ratio: float) -> str:
    unsafe = (deflection_pct > DEFLECTION_UNSAFE_PCT
              or buckle_sf < BUCKLE_SF_CAUTION
              or stress_ratio >= STRESS_RATIO_UNSAFE)
    if unsafe:
        return "unsafe"
    caution = (deflection_pct > DEFLECTION_CAUTION_PCT
               or buckle_sf < BUCKLE_SF_SAFE
               or stress_ratio >= STRESS_RATIO_CAUTION)
    return "caution" if caution else "safe"


_VERDICT_ZH = {"safe": "安全", "caution": "需关注", "unsafe": "不安全"}


def _render_ring(D_mm: float, dy_mm: float, dx_mm: float, n: int = 72) -> Dict[str, Any]:
    """生成原始圆与变形椭圆环的 2D 点集（mm，圆心在原点），挠度放大显示。
    径向位移 u_r(θ) = (Δx−Δy)/2 − (Δx+Δy)/2 · cos(2θ)：θ=0 管顶内移 Δy，
    θ=π/2 管侧外胀 Δx。放大倍数取让变形可见的适中值，并回传 amplification。"""
    r = D_mm / 2.0
    delta = max(abs(dy_mm), abs(dx_mm), 1e-6)
    amp = max(1.0, min(500.0, 15.0 / delta))  # 放大到约 15 mm 量级便于观察
    amp = 1.0 if delta < 1e-4 else amp
    orig: List[List[float]] = []
    deformed: List[List[float]] = []
    for i in range(n):
        theta = 2.0 * math.pi * i / n
        x = r * math.cos(theta)
        y = r * math.sin(theta)
        orig.append([round(x, 3), round(y, 3)])
        ur = (dx_mm - dy_mm) / 2.0 - (dx_mm + dy_mm) / 2.0 * math.cos(2.0 * theta)
        rr = r + amp * ur
        deformed.append([round(rr * math.cos(theta), 3), round(rr * math.sin(theta), 3)])
    return {"original": orig, "deformed": deformed, "amplification": round(amp, 2),
            "diameter_mm": D_mm}


def run_buried_pipe(model: Dict[str, Any],
                    library: Optional[MaterialsLibrary] = None) -> Dict[str, Any]:
    """埋地管校核主入口。model 为 AI 建模产物（已通过 AiParser 校验）。

    几何/材料字段单位：长度 m、力 kN、应力 kPa/MPa（见 spec FR-2）。
    返回与 Kotlin BuriedPipeResult 可解码的 dict。
    """
    if not isinstance(model, dict):
        raise ValueError("埋地管模型必须是 JSON 对象")
    g = model.get("geometry") or {}

    D = _geo(g, "outer_diameter_m")
    t = _geo(g, "thickness_m")
    H = _geo(g, "cover_depth_m")
    if D is None or D <= 0.0:
        raise ValueError("[G-01] 缺少外径 outer_diameter_m（m）或为非正")
    if t is None or t <= 0.0:
        raise ValueError("[G-01] 缺少壁厚 thickness_m（m）或为非正")
    if H is None or H <= 0.0:
        raise ValueError("[G-01] 缺少覆土深度 cover_depth_m（m）或为非正")
    if t >= D:
        raise ValueError("[G-01] 壁厚不应大于等于外径")

    mat_name, E, nu, rho, strength_MPa = _resolve_material(model, library)

    # ---- 土参数（默认值 + 用户覆盖，记录 assumptions）----
    assumptions: List[str] = []

    def _take_geo(key: str, default: float, unit: str,
                  label_zh: str, label_en: str) -> float:
        v = _geo(g, key)
        if v is None:
            assumptions.append(_t(
                f"未提供{label_zh}，采用默认值 {default} {unit}",
                f"{label_en} not provided; default {default} {unit} used"))
            return default
        return v

    gamma_kN = _take_geo("soil_gamma_kN_m3", DEFAULT_SOIL_GAMMA, "kN/m³", "土重度", "soil unit weight")
    phi = _take_geo("soil_phi_deg", DEFAULT_SOIL_PHI, "°", "土内摩擦角", "soil friction angle")
    cohesion_kPa = _take_geo("soil_cohesion_kPa", DEFAULT_SOIL_COHESION, "kPa", "土黏聚力", "soil cohesion")
    wheel_kN = _take_geo("wheel_load_kN", DEFAULT_WHEEL_LOAD, "kN", "车轮荷载", "wheel load")
    tire = _take_geo("tire_contact_m", DEFAULT_TIRE_CONTACT, "m", "轮胎接地尺寸", "tire contact size")
    p_int_kPa = _take_geo("internal_pressure_kPa", DEFAULT_INTERNAL_PRESSURE, "kPa", "管内压", "internal pressure")

    # ---- 荷载（统一 SI：Pa, N, m）----
    gamma = gamma_kN * 1000.0          # N/m³
    p_soil = gamma * H                 # 管顶竖向土压 Pa
    phi_rad = math.radians(phi)
    K0 = max(0.0, 1.0 - math.sin(phi_rad))
    p_lat = max(0.0, K0 * gamma * H - 2.0 * (cohesion_kPa * 1000.0) * math.sqrt(K0))
    p_int = p_int_kPa * 1000.0
    p_live = _boussinesq_live_pressure(wheel_kN * 1000.0, H)

    # 驱动（成椭圆化）的净竖向压力：竖向（土+车载） − 侧向
    p_v = p_soil + p_live
    p_drive = max(0.0, p_v - p_lat)
    W = p_drive * D  # 对径竖向荷载 / 单位管长 (N/m)

    r = (D - t) / 2.0
    dy, dx = _ring_deflection(W, r, t, E, nu)

    # 均匀外压分量（用于膜应力与屈曲）：土压 − 内压，截到 ≥0
    p0 = max(0.0, p_soil - p_int)
    sigma_m, sigma_b, sigma_total = _max_ring_stress(W, r, t, p0)
    strength_Pa = strength_MPa * 1e6
    stress_ratio = sigma_total / strength_Pa if strength_Pa > 0 else 0.0

    # 屈曲：施加外压 = 土压（含半量车载近似）− 内压
    p_applied = max(0.0, p_soil + 0.5 * p_live - p_int)
    p_cr = _buckling_pcr(E, nu, t, D)
    buckle_sf = (p_cr / p_applied) if p_applied > 1e-6 else 9999.0

    deflection_mm = abs(dy) * 1000.0
    deflection_pct = deflection_mm / (D * 1000.0) * 100.0
    verdict = _verdict(deflection_pct, buckle_sf, stress_ratio)

    # ---- 结论说明（按界面语言）----
    if verdict == "safe":
        advice = [_t("在当前埋深与车载下，该埋地管的环向挠度、环向应力与外压屈曲均满足限值，判断为不会损坏。",
                    "Under the current cover depth and wheel load, ring deflection, ring stress and "
                    "external-pressure buckling all satisfy the limits; the pipe is judged not to fail.")]
    elif verdict == "caution":
        advice = [_t("某项指标接近限值（挠度/屈曲安全系数/应力比），建议复核管材规格、覆土压实或降低车载。",
                    "One indicator is close to its limit (deflection / buckling safety factor / stress ratio); "
                    "review the pipe specification, backfill compaction, or reduce the wheel load.")]
    else:
        advice = [_t("至少一项指标超限（挠度过大、屈曲安全系数不足或应力超限），判断可能损坏，需加大壁厚/管径或减小埋深/车载。",
                    "At least one indicator exceeds its limit (excessive deflection, insufficient buckling safety "
                    "factor, or overstress); the pipe may fail — increase wall thickness/diameter or reduce cover depth/wheel load.")]
    advice.append(_t("本结果为工程公式估算（线弹性、忽略土弹簧与覆土约束的有利作用），不替代正式设计。",
                    "This is an engineering-formula estimate (linear elastic, ignoring the beneficial effect of "
                    "soil springs and cover restraint); it does not replace formal design."))

    formulas = [
        _t("覆土竖向土压 p_soil = γ·H（Marston 土柱/沟槽简化）",
          "Vertical soil pressure p_soil = γ·H (Marston soil-column/trench simplification)"),
        _t("侧向土压 p_lat = K0·γ·H − 2c·√K0，K0 = 1−sinφ（静止土压力系数）",
          "Lateral soil pressure p_lat = K0·γ·H − 2c·√K0, K0 = 1−sinφ (at-rest earth pressure coefficient)"),
        _t("车载管顶附加压力（Boussinesq 点荷近似）σ_z = 3P/(2πH²)",
          "Wheel-load pressure at pipe crown (Boussinesq point-load approximation) σ_z = 3P/(2πH²)"),
        _t("环向挠度（圆环对径压缩，平面应变 E'=E/(1−ν²)）：Δy = 0.1488·W·r³/(E'I)，I=t³/12",
          "Ring deflection (diametral compression, plane strain E'=E/(1−ν²)): Δy = 0.1488·W·r³/(E'I), I=t³/12"),
        _t("环向应力 = 膜应力 p·r/t + 弯曲应力 6M/t²，M = 0.1817·W·r",
          "Ring stress = membrane p·r/t + bending 6M/t², M = 0.1817·W·r"),
        _t("外压屈曲临界压力（Timoshenko 长管 n=2）p_cr = 2E(t/D)³/(1−ν²)，SF = p_cr / p_applied",
          "External-pressure buckling (Timoshenko long tube, n=2) p_cr = 2E(t/D)³/(1−ν²), SF = p_cr / p_applied"),
    ]
    assumptions.append(_t("按长管/单位长度管环（平面应变）计算，忽略管端约束与沿长度变化；默认空管（内压=0）为最不利外压屈曲工况。",
                         "Computed as a long pipe / unit-length ring (plane strain), ignoring end restraint and "
                         "variation along the length; an empty pipe (internal pressure = 0) is the worst case for buckling."))
    assumptions.append(_t(f"车载默认按公路-Ⅰ级单轮 {DEFAULT_WHEEL_LOAD} kN、接地 {DEFAULT_TIRE_CONTACT} m 经覆土扩散；如实际轴重/轮压不同请提供 wheel_load_kN。",
                         f"Wheel load defaults to Highway Class-I single wheel {DEFAULT_WHEEL_LOAD} kN with "
                         f"{DEFAULT_TIRE_CONTACT} m contact spreading through cover; provide wheel_load_kN if different."))
    assumptions.append(_t("屈曲与挠度未计入覆土对管的约束/土弹簧刚度，结果偏于保守（安全方向）。",
                         "Buckling and deflection ignore soil restraint/spring stiffness around the pipe, "
                         "so results are conservative (safe side)."))

    checks = [
        {"label": _t("环向挠度比 Δ/D", "Ring deflection ratio Δ/D"), "value": f"{deflection_pct:.3f}%",
         "status": "safe" if deflection_pct <= DEFLECTION_CAUTION_PCT else
                   ("caution" if deflection_pct <= DEFLECTION_UNSAFE_PCT else "unsafe")},
        {"label": _t("屈曲安全系数 SF", "Buckling safety factor SF"), "value": f"{buckle_sf:.2f}",
         "status": "safe" if buckle_sf >= BUCKLE_SF_SAFE else
                   ("caution" if buckle_sf >= BUCKLE_SF_CAUTION else "unsafe")},
        {"label": _t("环向应力比 σ/σ_allow", "Ring stress ratio σ/σ_allow"), "value": f"{stress_ratio:.3f}",
         "status": "safe" if stress_ratio < STRESS_RATIO_CAUTION else
                   ("caution" if stress_ratio < STRESS_RATIO_UNSAFE else "unsafe")},
    ]

    metrics = {
        "outer_diameter_mm": round(D * 1000.0, 3),
        "thickness_mm": round(t * 1000.0, 3),
        "cover_depth_m": round(H, 3),
        "material": mat_name,
        "E_MPa": round(E / 1e6, 2),
        "nu": round(nu, 3),
        "density_kg_m3": round(rho, 1),
        "strength_MPa": round(strength_MPa, 2),
        "soil_gamma_kN_m3": round(gamma_kN, 2),
        "soil_phi_deg": round(phi, 2),
        "soil_cohesion_kPa": round(cohesion_kPa, 2),
        "wheel_load_kN": round(wheel_kN, 2),
        "tire_contact_m": round(tire, 3),
        "internal_pressure_kPa": round(p_int_kPa, 2),
        "p_soil_kPa": round(p_soil / 1000.0, 3),
        "p_live_kPa": round(p_live / 1000.0, 3),
        "p_lateral_kPa": round(p_lat / 1000.0, 3),
        "deflection_mm": round(deflection_mm, 4),
        "deflection_ratio_pct": round(deflection_pct, 4),
        "max_ring_stress_MPa": round(sigma_total / 1e6, 3),
        "membrane_stress_MPa": round(abs(sigma_m) / 1e6, 3),
        "bending_stress_MPa": round(sigma_b / 1e6, 3),
        "stress_ratio": round(stress_ratio, 4),
        "buckling_pcr_kPa": round(p_cr / 1000.0, 2),
        "p_applied_kPa": round(p_applied / 1000.0, 3),
        "buckling_sf": round(buckle_sf, 2),
    }

    result: Dict[str, Any] = {
        "kind": "shell_buried_pipe",
        "ok": True,
        "verdict": verdict,
        "verdict_zh": _VERDICT_ZH.get(verdict, verdict),
        "metrics": metrics,
        "checks": checks,
        "assumptions": assumptions,
        "formulas": formulas,
        "advice": advice,
        "render": _render_ring(D * 1000.0, dy * 1000.0, dx * 1000.0),
    }
    return result
