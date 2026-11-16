"""悬臂搁板模板测试：端部挠度 vs 欧拉-伯努利理论解 δ=PL³/(3EI)，门槛 5%。

算例选取细长比大的几何（L=300mm、t=20mm，L/t=15），梁理论适用性好；
P2 二阶单元下 medium 档实测误差约 0.2%。
"""
import pytest

from pocketsim.solver import run_solve_config
from pocketsim.templates import TEMPLATES

# 松木：E = 10 GPa（材料库代表值）
E_PINE = 10.0e9
PARAMS = {
    "length_mm": 300.0,
    "thickness_mm": 20.0,
    "width_mm": 200.0,
    "material": "松木",
    "load_kg": 10.0,   # P = 98 N
}


def _solve(params, mesh_level="medium"):
    tpl = TEMPLATES["cantilever_shelf"]
    errors = tpl.validate(params)
    assert errors == [], f"参数校验失败：{errors}"
    return tpl, run_solve_config(tpl.build(params, mesh_level))


def _theory_delta_mm(P, L, t, b, E, q=0.0):
    """欧拉-伯努利悬臂梁端部挠度（mm）：集中力 + 均布载荷。"""
    inertia = b * t ** 3 / 12.0
    return (P * L ** 3 / (3.0 * E * inertia) + q * L ** 4 / (8.0 * E * inertia)) * 1000.0


def test_tip_deflection_medium():
    """medium 档（P2 单元）端部挠度误差 ≤5%。"""
    tpl, res = _solve(PARAMS, "medium")
    fea_mm = tpl.fea_reading(PARAMS, res) * 1000.0
    theory_mm = _theory_delta_mm(98.0, 0.3, 0.02, 0.2, E_PINE)
    assert fea_mm == pytest.approx(theory_mm, rel=0.05)


def test_tip_deflection_fine():
    """fine 档端部挠度误差 ≤5%（加密网格一致性）。"""
    tpl, res = _solve(PARAMS, "fine")
    fea_mm = tpl.fea_reading(PARAMS, res) * 1000.0
    theory_mm = _theory_delta_mm(98.0, 0.3, 0.02, 0.2, E_PINE)
    assert fea_mm == pytest.approx(theory_mm, rel=0.05)


def test_tip_deflection_with_udl():
    """集中力 + 顶部均布载荷 q=500 N/m：理论 δ=PL³/(3EI)+qL⁴/(8EI)，误差 ≤5%。"""
    params = dict(PARAMS, load_n=98.0, udl_n_per_m=500.0)
    tpl, res = _solve(params, "medium")
    fea_mm = tpl.fea_reading(params, res) * 1000.0
    theory_mm = _theory_delta_mm(98.0, 0.3, 0.02, 0.2, E_PINE, q=500.0)
    assert fea_mm == pytest.approx(theory_mm, rel=0.05)


def test_root_bending_stress():
    """固支根部弯曲应力 σ=PL/W vs FEA 最大 von Mises（读数排除奇异节点）。

    注：应力为节点平均读数、且含剪切与局部约束影响，门槛放宽到 10%
    （挠度项的 5% 严格门槛见上方各用例）。
    """
    tpl, res = _solve(PARAMS, "medium")
    W = 0.2 * 0.02 ** 2 / 6.0                 # 抗弯截面模量 m³
    sigma_theory = 98.0 * 0.3 / W             # Pa
    assert res.max_von_mises == pytest.approx(sigma_theory, rel=0.10)


def test_reactions_balance():
    """支反力与外载荷平衡（固支端对搁板的竖向作用力 = +P，向上为正）。"""
    _, res = _solve(PARAMS, "medium")
    assert res.reactions["fy"] == pytest.approx(98.0, abs=1e-3)
    assert res.reactions["fx"] == pytest.approx(0.0, abs=1e-3)


def test_load_n_priority_over_load_kg():
    """load_n 优先于 load_kg：两者同给时按 load_n 计算。"""
    params = dict(PARAMS, load_kg=10.0, load_n=196.0)   # 196 N ≠ 98 N
    tpl, res = _solve(params, "medium")
    fea_mm = tpl.fea_reading(params, res) * 1000.0
    theory_mm = _theory_delta_mm(196.0, 0.3, 0.02, 0.2, E_PINE)
    assert fea_mm == pytest.approx(theory_mm, rel=0.05)
