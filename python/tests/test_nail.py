"""墙上钉挂重模板测试。

- 钉尖挠度 vs 悬臂梁理论 δ=F·la³/(3EI)（I=d⁴/12 方截面等效），门槛 5%；
- 墙孔承压 / 抗拉拔 / 钉身弯曲三个简化校核的公式数值手算对照。
"""
import math

import pytest

from pocketsim.solver import run_solve_config
from pocketsim.templates import TEMPLATES

PARAMS = {"mass_kg": 3.0}   # 其余取模板默认：d=3.5mm, la=25mm, lb=30mm, 低碳钢钉+砖砌体墙
# 手算基准
F_N = 3.0 * 9.8                                  # 29.4 N
D_MM, LA_MM, LB_MM, TAU_MPA = 3.5, 25.0, 30.0, 0.5
E_STEEL = 206.0e9


def _solve(params=None, mesh_level="medium"):
    params = params or PARAMS
    tpl = TEMPLATES["wall_nail"]
    errors = tpl.validate(params)
    assert errors == [], f"参数校验失败：{errors}"
    return tpl, run_solve_config(tpl.build(params, mesh_level))


def _checks_by_name(params, result):
    tpl = TEMPLATES["wall_nail"]
    return {c["name"]: c for c in tpl.extra_checks(params, result)}


def test_nail_tip_deflection_medium():
    """钉尖挠度 vs δ=F·la³/(3EI)（P2 单元 medium 档），误差 ≤5%。"""
    tpl, res = _solve(mesh_level="medium")
    fea_mm = tpl.fea_reading(PARAMS, res) * 1000.0
    inertia = (D_MM / 1000.0) ** 4 / 12.0        # 方截面等效 m⁴
    theory_mm = F_N * (LA_MM / 1000.0) ** 3 / (3.0 * E_STEEL * inertia) * 1000.0
    assert fea_mm == pytest.approx(theory_mm, rel=0.05)


def test_nail_tip_deflection_fine():
    """fine 档一致性与收敛。"""
    tpl, res = _solve(mesh_level="fine")
    fea_mm = tpl.fea_reading(PARAMS, res) * 1000.0
    inertia = (D_MM / 1000.0) ** 4 / 12.0
    theory_mm = F_N * (LA_MM / 1000.0) ** 3 / (3.0 * E_STEEL * inertia) * 1000.0
    assert fea_mm == pytest.approx(theory_mm, rel=0.05)


def test_theory_extras_root_stress():
    """theory_check 附加输出的根部弯曲应力 = F·la/W（W=d³/6）手算对照。"""
    tpl, _ = _solve()
    theory = tpl.theory_check(PARAMS)
    W_mm3 = D_MM ** 3 / 6.0
    sigma_mpa = F_N * LA_MM / W_mm3              # N/mm² = MPa
    assert theory["extras"]["root_bending_stress_mpa"] == pytest.approx(sigma_mpa, rel=1e-9)


def test_wall_bearing_hand_check():
    """墙孔承压 σ = F/(d×lb) 手算对照：29.4/(3.5×30) = 0.28 MPa。"""
    _, res = _solve()
    checks = _checks_by_name(PARAMS, res)
    bearing = checks["墙孔承压校核"]
    sigma_c = F_N / (D_MM * LB_MM)               # N/mm² = MPa
    assert sigma_c == pytest.approx(0.28, rel=1e-9)
    assert bearing["value"] == pytest.approx(sigma_c, rel=1e-3)
    assert bearing["limit"] == pytest.approx(2.0)   # 砖砌体抗压代表值
    assert bearing["safety_factor"] == pytest.approx(2.0 / sigma_c, rel=1e-3)


def test_pullout_hand_check():
    """抗拉拔 F_pull = τ×π×d×lb 手算对照：0.5×π×3.5×30 ≈ 164.93 N。"""
    _, res = _solve()
    checks = _checks_by_name(PARAMS, res)
    pull = checks["抗拉拔校核（摩擦简化）"]
    f_pull = TAU_MPA * math.pi * D_MM * LB_MM    # N
    assert f_pull == pytest.approx(164.9336, rel=1e-4)
    assert pull["limit"] == pytest.approx(f_pull, rel=1e-3)
    assert pull["value"] == pytest.approx(F_N, rel=1e-3)
    assert pull["safety_factor"] == pytest.approx(f_pull / F_N, rel=1e-3)


def test_nail_bending_strength_check():
    """钉身弯曲强度校核：FEA 最大 von Mises vs 手算 σ=F·la/W。

    注：FEA 读数含剪切与节点平均影响，门槛 10%（挠度 5% 门槛见上方用例）。
    """
    tpl, res = _solve()
    checks = _checks_by_name(PARAMS, res)
    bending = checks["钉身弯曲强度校核"]
    W_mm3 = D_MM ** 3 / 6.0
    sigma_mpa = F_N * LA_MM / W_mm3
    assert res.max_von_mises / 1e6 == pytest.approx(sigma_mpa, rel=0.10)
    assert bending["value"] == pytest.approx(res.max_von_mises / 1e6, rel=1e-3)
    assert bending["limit"] == pytest.approx(235.0)  # 低碳钢屈服代表值


def test_custom_bond_strength_changes_pullout():
    """粘结强度 τ 翻倍 → 抗拉拔限值翻倍（参数正确进入公式）。"""
    params = dict(PARAMS, bond_strength_mpa=1.0)
    tpl, res = _solve(params)
    checks = {c["name"]: c for c in tpl.extra_checks(params, res)}
    pull = checks["抗拉拔校核（摩擦简化）"]
    assert pull["limit"] == pytest.approx(2.0 * TAU_MPA * math.pi * D_MM * LB_MM, rel=1e-3)


def test_heavier_mass_scales_linearly():
    """线弹性：质量翻倍 → 挠度翻倍（叠加原理自检）。"""
    tpl, res_light = _solve({"mass_kg": 3.0})
    tpl2, res_heavy = _solve({"mass_kg": 6.0})
    d1 = tpl.fea_reading({"mass_kg": 3.0}, res_light)
    d2 = tpl2.fea_reading({"mass_kg": 6.0}, res_heavy)
    assert d2 == pytest.approx(2.0 * d1, rel=1e-9)
