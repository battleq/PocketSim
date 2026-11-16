# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""P4 流体阶段（渗流）测试：一维达西解析解、大坝坝基渗流算例、参数校验。"""
import json

import numpy as np
import pytest

from pocketsim import seepage
from pocketsim.meshgen import make_rect_mesh


# ---------------------------------------------------------------------- 一维达西
def test_one_dimensional_darcy_flow():
    """矩形域左右两侧给定水头：单宽流量必须等于 q = k·D·Δh/L。"""
    length, depth, k = 20.0, 6.0, 1e-5
    mesh = make_rect_mesh(20, 8, length, depth)
    p = mesh.p
    left = np.where(np.abs(p[0]) < 1e-9)[0]
    right = np.where(np.abs(p[0] - length) < 1e-9)[0]
    dirichlet = np.concatenate([left, right])
    values = np.concatenate([np.full(left.size, 1.0), np.full(right.size, 0.0)])
    head = seepage._solve_head(mesh, dirichlet, values)

    # 水头沿 x 线性下降
    assert np.max(np.abs(head - (1.0 - p[0] / length))) < 1e-9
    q = seepage._flux_vertical_section(mesh, head, 0.5 * length, k)
    assert q == pytest.approx(k * depth * 1.0 / length, rel=1e-3)


def test_impermeable_base_zero_flux():
    """上下边界不透水时，竖向截面流量仍守恒（任意截面流量相同）。"""
    length, depth, k = 12.0, 5.0, 2e-6
    mesh = make_rect_mesh(12, 10, length, depth)
    p = mesh.p
    left = np.where(np.abs(p[0]) < 1e-9)[0]
    right = np.where(np.abs(p[0] - length) < 1e-9)[0]
    head = seepage._solve_head(
        mesh, np.concatenate([left, right]),
        np.concatenate([np.full(left.size, 3.0), np.full(right.size, 1.0)]),
    )
    q1 = seepage._flux_vertical_section(mesh, head, 0.25 * length, k)
    q2 = seepage._flux_vertical_section(mesh, head, 0.75 * length, k)
    assert q1 == pytest.approx(q2, rel=1e-9)


# ---------------------------------------------------------------------- 梯形坝算例
def _dam_case(**overrides):
    model = {
        "name": "混凝土重力坝",
        "kind": "dam_seepage",
        "dam": {
            "height_m": 12.0,
            "crest_width_m": 5.0,
            "upstream_slope": 0.0,
            "downstream_slope": 0.75,
        },
        "upstream_level_m": 11.0,
        "downstream_level_m": 1.0,
        "material": "混凝土C20",
        "foundation_permeability_m_s": 1e-6,
        "foundation_depth_m": 18.0,
        "friction_coeff": 0.6,
        "mesh_level": "medium",
    }
    model.update(overrides)
    return model


def test_dam_seepage_basic():
    r = seepage.run_seepage(_dam_case())
    assert r["ok"] and r["kind"] == "seepage"
    # 坝底宽 = 坝顶宽 + 下游坡比 × 坝高 = 5 + 0.75×12 = 14 m
    assert r["dam"]["base_width_m"] == pytest.approx(14.0)
    assert r["dam"]["area_m2"] == pytest.approx((5 + 14) / 2 * 12, rel=1e-6)
    assert r["dam"]["weight_kN_per_m"] == pytest.approx(2400 * 9.8 * 114 / 1000, rel=1e-3)
    # 上游水压力 = ½ρg·h1²
    assert r["water_pressure_kN_per_m"] == pytest.approx(
        0.5 * 1000 * 9.8 * 11.0 ** 2 / 1000, rel=2e-2
    )
    # 扬压力应显著小于自重（否则坝体会浮起），且大于 0
    assert 0.0 < r["uplift_kN_per_m"] < 0.9 * r["dam"]["weight_kN_per_m"]
    # 单宽渗流量与渗透系数成正比（量级 ~1e-5 m²/s）
    assert 1e-7 < r["seepage_q_m3_s"] < 1e-3
    # 出逸比降应落在合理区间（0.05~2）
    assert 0.05 < r["exit_gradient"] < 2.0
    assert r["safety_factor"] == pytest.approx(
        min(r["slide_sf"], r["overturn_sf"], r["piping_sf"]), abs=1e-6
    )
    assert r["verdict"] in ("安全", "注意", "危险")
    assert r["weak_part"]
    assert r["failure_modes"] and "形式一" in r["failure_modes"][0]


def test_uplift_grows_with_water_level():
    low = seepage.run_seepage(_dam_case(upstream_level_m=6.0))
    high = seepage.run_seepage(_dam_case(upstream_level_m=11.0))
    assert high["uplift_kN_per_m"] > low["uplift_kN_per_m"]
    assert high["slide_sf"] < low["slide_sf"]
    assert high["seepage_q_m3_s"] > low["seepage_q_m3_s"]


def test_permeability_scales_flow_not_head():
    """均质坝基中水头场与 k 无关，只有渗流量随 k 线性变化。"""
    slow = seepage.run_seepage(_dam_case(foundation_permeability_m_s=1e-7))
    fast = seepage.run_seepage(_dam_case(foundation_permeability_m_s=1e-5))
    assert fast["seepage_q_m3_s"] / slow["seepage_q_m3_s"] == pytest.approx(100.0, rel=0.05)
    assert fast["uplift_kN_per_m"] == pytest.approx(slow["uplift_kN_per_m"], rel=1e-3)
    assert fast["exit_gradient"] == pytest.approx(slow["exit_gradient"], rel=1e-3)


def test_streamlines_stay_in_foundation():
    r = seepage.run_seepage(_dam_case(mesh_level="coarse"))
    lines = r["render"]["streamlines"]
    assert len(lines) >= 4
    depth = r["foundation_depth_m"]
    for line in lines:
        assert len(line) >= 3
        for x, y in line:
            assert -depth - 1e-6 <= y <= 1e-6
            assert -2 * r["dam"]["height_m"] - 1e-6 <= x


def test_render_is_json_safe():
    r = seepage.run_seepage(_dam_case())
    text = json.dumps(r, ensure_ascii=False, allow_nan=False)
    back = json.loads(text)
    assert len(back["render"]["nodes"]) == len(back["render"]["values"])
    assert len(back["render"]["tris"]) > 500
    assert len(back["render"]["dam"]["points"]) == 4


# ---------------------------------------------------------------------- 参数校验
def test_missing_material_rejected():
    model = _dam_case()
    model.pop("material")
    with pytest.raises(ValueError, match="材料"):
        seepage.run_seepage(model)


def test_upstream_level_above_dam_rejected():
    with pytest.raises(ValueError, match="上游水位"):
        seepage.run_seepage(_dam_case(upstream_level_m=12.5))


def test_downstream_level_above_upstream_rejected():
    with pytest.raises(ValueError, match="下游水位"):
        seepage.run_seepage(_dam_case(upstream_level_m=5.0, downstream_level_m=6.0))


def test_unknown_kind_rejected():
    with pytest.raises(ValueError, match="暂不支持"):
        seepage.run_seepage(_dam_case(kind="dam_overflow"))


def test_bad_permeability_rejected():
    with pytest.raises(ValueError, match="渗透系数"):
        seepage.run_seepage(_dam_case(foundation_permeability_m_s=1.0))
