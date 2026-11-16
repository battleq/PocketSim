# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""P4 流体阶段（风压）测试：蒲福风级换算、势流解析解、砖墙抗风算例。"""
import json
import math

import numpy as np
import pytest

from pocketsim import flow
from pocketsim.meshgen import make_rect_mesh


# ---------------------------------------------------------------------- 风级
def test_beaufort_conversions():
    assert flow.speed_from_beaufort(8) == pytest.approx(20.7)
    assert flow.beaufort_from_speed(20.7) == 8
    assert flow.beaufort_from_speed(20.71) == 9
    assert flow.beaufort_from_speed(19.9) == 8
    assert flow.beaufort_from_speed(0.1) == 0
    assert flow.beaufort_from_speed(80.0) == 17
    assert flow.beaufort_label(10) == "10 级风"


# ---------------------------------------------------------------------- 势流解析解
def test_uniform_channel_flow():
    """无墙时势流应严格等于均匀来流：v=(U,0)，p≈0。"""
    U = 12.0
    mesh = make_rect_mesh(16, 8, 8.0, 3.0)
    phi, vx, vy = flow._solve_potential(mesh, U)
    assert np.max(np.abs(vx - U)) < 1e-6
    assert np.max(np.abs(vy)) < 1e-6
    # φ = U·x（入口 x=0 处 φ=0）
    assert np.max(np.abs(phi - U * mesh.p[0])) < 1e-6


def test_mesh_has_wall_hole():
    """墙体区域必须被挖空：没有三角形重心落在墙内。"""
    h, t = 2.0, 0.24
    mesh = flow._build_domain_mesh(h, t, "coarse")
    p = mesh.p
    cx = p[0, mesh.t].mean(axis=0)
    cy = p[1, mesh.t].mean(axis=0)
    inside = (cx > 0.0) & (cx < t) & (cy > 0.0) & (cy < h)
    assert not np.any(inside)
    # 墙面与墙顶必须是网格线，便于无穿透边界精确落在墙上
    assert np.any(np.abs(p[0] - 0.0) < 1e-12)
    assert np.any(np.abs(p[0] - t) < 1e-12)
    assert np.any(np.abs(p[1] - h) < 1e-12)


# ---------------------------------------------------------------------- 砖墙算例
def _brick_wall_case(**overrides):
    model = {
        "name": "砖墙抗风",
        "kind": "wind_wall",
        "wall": {"height_m": 2.0, "length_m": 3.0, "thickness_m": 0.24},
        "beaufort": 8,
        "material": "砖砌体",
        "mesh_level": "medium",
    }
    model.update(overrides)
    return model


def test_brick_wall_level8():
    """2 m 高 240 mm 厚砖墙遇 8 级风：风压、抗倾覆与临界风级应落在合理区间。"""
    r = flow.run_flow(_brick_wall_case())
    assert r["ok"] and r["kind"] == "flow"
    assert r["beaufort"] == 8
    assert r["wind_speed_m_s"] == pytest.approx(20.7)

    U = r["wind_speed_m_s"]
    q = 0.5 * flow.AIR_DENSITY * U ** 2
    assert r["q_Pa"] == pytest.approx(q, rel=1e-2)
    assert r["force_N"] == pytest.approx(flow.CP_NET * q * 2.0 * 3.0, rel=1e-2)

    # 自重 = 1900 kg/m³ × 9.8 × 2 × 0.24 × 3
    assert r["wall"]["weight_kN"] == pytest.approx(26.8, rel=2e-2)
    # 抗倾覆 SF = W·t/(F·h) ≈ 1.7（注意区）
    assert r["overturn_sf"] == pytest.approx(1.7, abs=0.1)
    assert r["verdict"] == "注意"
    assert r["safety_factor"] == pytest.approx(r["overturn_sf"], abs=1e-6)

    # 驻点压力应接近 ½ρU²（P1 网格误差 < 10%）
    assert r["stagnation_Pa"] == pytest.approx(q, rel=0.1)
    # 墙顶加速比在 1.2~3 之间（排除拐角奇异后）
    assert 1.2 < r["max_speed_ratio"] < 3.0

    # 临界风级：10 级上下（约 26~28 m/s）
    assert 24.0 < r["critical_speed_m_s"] < 30.0
    assert r["critical_beaufort"] in (9, 10)
    assert "倾覆" in r["failure_modes"][0]
    assert r["weak_part"]
    assert any("加厚" in a or "墙高" in a for a in r["advice"])


def test_wind_load_raises_with_speed_squared():
    slow = flow.run_flow(_brick_wall_case(beaufort=5))
    fast = flow.run_flow(_brick_wall_case(beaufort=11))
    ratio = fast["force_N"] / slow["force_N"]
    expected = (flow.speed_from_beaufort(11) / flow.speed_from_beaufort(5)) ** 2
    assert ratio == pytest.approx(expected, rel=1e-2)
    assert slow["safety_factor"] > 2.0
    assert fast["verdict"] == "危险"


def test_streamlines_stay_out_of_wall():
    r = flow.run_flow(_brick_wall_case(mesh_level="coarse"))
    lines = r["render"]["streamlines"]
    assert len(lines) >= 5
    h = r["wall"]["height_m"]
    t = r["wall"]["thickness_m"]
    for line in lines:
        assert len(line) >= 3
        for x, y in line:
            assert not (0.0 < x < t and 0.0 < y < h), "流线不得穿入墙体"


def test_result_is_json_safe():
    r = flow.run_flow(_brick_wall_case())
    text = json.dumps(r, ensure_ascii=False, allow_nan=False)
    assert len(text) > 1000
    back = json.loads(text)
    assert len(back["render"]["nodes"]) == len(back["render"]["values"])
    assert len(back["render"]["tris"]) > 1000


# ---------------------------------------------------------------------- 参数校验
def test_missing_material_rejected():
    model = _brick_wall_case()
    model.pop("material")
    with pytest.raises(ValueError, match="材料"):
        flow.run_flow(model)


def test_thickness_in_mm_rejected():
    """把 240（毫米）误填进 thickness_m 应给出明确提示，而不是算出荒谬结果。"""
    model = _brick_wall_case(wall={"height_m": 2.0, "length_m": 3.0, "thickness_m": 240})
    with pytest.raises(ValueError, match="墙厚"):
        flow.run_flow(model)


def test_missing_wind_rejected():
    model = _brick_wall_case()
    model.pop("beaufort")
    with pytest.raises(ValueError, match="风速"):
        flow.run_flow(model)


def test_unknown_kind_rejected():
    with pytest.raises(ValueError, match="流体场景"):
        flow.run_flow(_brick_wall_case(kind="flood"))
