"""简支层板/桌板模板测试：跨中挠度 vs δ=PL³/(48EI)，门槛 5%。

平面外宽度 b 的处理自检：力按 P/b 化为单位厚度平面问题（总力直接进入
右端项、刚度按 b 缩放），挠度应与 b 无关。
"""
import pytest

from pocketsim.solver import run_solve_config
from pocketsim.templates import TEMPLATES

E_PLYWOOD = 9.0e9   # 胶合板 E = 9 GPa（材料库代表值）
PARAMS = {
    "length_mm": 800.0,
    "thickness_mm": 18.0,
    "width_mm": 600.0,
    "material": "胶合板",
    "load_kg": 10.0,   # P = 98 N
}


def _solve(params, mesh_level="medium"):
    tpl = TEMPLATES["simply_supported_panel"]
    errors = tpl.validate(params)
    assert errors == [], f"参数校验失败：{errors}"
    return tpl, run_solve_config(tpl.build(params, mesh_level))


def _theory_delta_mm(P, L, t, b, E):
    """简支梁跨中集中力挠度 δ=PL³/(48EI)（mm）。"""
    inertia = b * t ** 3 / 12.0
    return P * L ** 3 / (48.0 * E * inertia) * 1000.0


def test_mid_deflection_medium():
    """medium 档跨中挠度误差 ≤5%。"""
    tpl, res = _solve(PARAMS, "medium")
    fea_mm = tpl.fea_reading(PARAMS, res) * 1000.0
    theory_mm = _theory_delta_mm(98.0, 0.8, 0.018, 0.6, E_PLYWOOD)
    assert fea_mm == pytest.approx(theory_mm, rel=0.05)


def test_mid_deflection_fine():
    """fine 档跨中挠度误差 ≤5%。"""
    tpl, res = _solve(PARAMS, "fine")
    fea_mm = tpl.fea_reading(PARAMS, res) * 1000.0
    theory_mm = _theory_delta_mm(98.0, 0.8, 0.018, 0.6, E_PLYWOOD)
    assert fea_mm == pytest.approx(theory_mm, rel=0.05)


def test_deflection_scales_with_width():
    """挠度与板宽 b 的关系符合梁理论 δ ∝ 1/b（板越宽越刚），两档都对照理论。

    平面外宽度处理：刚度按 b 缩放、总力 P 直接进入右端项——等价于
    “力按 P/b 化为单位厚度平面问题”，挠度与应力和三维梁理论一致
    （δ=PL³/(48EI)，I=bt³/12）。
    """
    narrow = dict(PARAMS, width_mm=600.0)
    wide = dict(PARAMS, width_mm=1200.0)
    tpl, res_narrow = _solve(narrow, "medium")
    tpl2, res_wide = _solve(wide, "medium")
    d1 = tpl.fea_reading(narrow, res_narrow)
    d2 = tpl2.fea_reading(wide, res_wide)
    assert d1 * 1000.0 == pytest.approx(
        _theory_delta_mm(98.0, 0.8, 0.018, 0.6, E_PLYWOOD), rel=0.05
    )
    assert d2 * 1000.0 == pytest.approx(
        _theory_delta_mm(98.0, 0.8, 0.018, 1.2, E_PLYWOOD), rel=0.05
    )
    assert d2 == pytest.approx(d1 / 2.0, rel=0.01)   # 宽度翻倍 → 挠度减半


def test_reactions_balance():
    """两简支座竖向反力合计 = +P（支撑对板的作用力，向上为正）。"""
    _, res = _solve(PARAMS, "medium")
    assert res.reactions["fy"] == pytest.approx(98.0, abs=1e-2)
    assert res.reactions["fx"] == pytest.approx(0.0, abs=1e-2)


def test_mid_bending_stress():
    """跨中弯曲应力 σ=PL/(4W) vs FEA（节点平均读数，门槛 10%）。"""
    tpl, res = _solve(PARAMS, "medium")
    W = 0.6 * 0.018 ** 2 / 6.0
    sigma_theory = 98.0 * 0.8 / 4.0 / W          # Pa
    assert res.max_von_mises == pytest.approx(sigma_theory, rel=0.10)


def test_load_n_direct():
    """load_n 直接给牛顿：理论对照（避免 kg→N 换算路径遗漏）。"""
    params = dict(PARAMS, load_n=150.0, load_kg=None)
    tpl, res = _solve(params, "medium")
    fea_mm = tpl.fea_reading(params, res) * 1000.0
    theory_mm = _theory_delta_mm(150.0, 0.8, 0.018, 0.6, E_PLYWOOD)
    assert fea_mm == pytest.approx(theory_mm, rel=0.05)
