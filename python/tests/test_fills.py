# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""填料载荷层（pocketsim.fills）测试。

覆盖：
- Rankine 侧压系数 Ka/K0/Kp 数值；
- Janssen 深仓 σv 指数饱和、渐近值 γR/(μK)、不超过 γh；
- 旧契约（无 fill=水，fluid_density/water_depth 覆盖）；
- 预设名/参数对象/预设覆盖解析；
- 浅仓无壁摩擦（整体平衡自洽）、深仓 τ=μpₙ；
- 黏性填料主动态 c 项；空仓。
"""
from __future__ import annotations

import math

import numpy as np
import pytest

from pocketsim import fills
from pocketsim.flow import GRAVITY


# ---------------------------------------------------------------------- 系数
def test_lateral_coefficients_rankine():
    # φ=30°：Ka=1/3、K0=0.5、Kp=3
    assert fills.lateral_coefficient(30.0, "active") == pytest.approx(1.0 / 3.0)
    assert fills.lateral_coefficient(30.0, "at_rest") == pytest.approx(0.5)
    assert fills.lateral_coefficient(30.0, "passive") == pytest.approx(3.0)
    # φ=0：三系数均为 1（静水式分布）
    assert fills.lateral_coefficient(0.0, "active") == pytest.approx(1.0)
    assert fills.lateral_coefficient(0.0, "at_rest") == pytest.approx(1.0)


# ---------------------------------------------------------------------- Janssen
def test_janssen_sigma_v_saturation():
    gamma = 1600.0 * GRAVITY
    k, mu, radius_h = 1.0 / 3.0, math.tan(math.radians(20.0)), 0.5
    lam = mu * k / radius_h
    assert fills.janssen_sigma_v(0.0, gamma, k, mu, radius_h) == pytest.approx(0.0)
    # h→∞ 渐近 γ/λ = γR/(μK)
    asym = gamma / lam
    assert fills.janssen_sigma_v(500.0, gamma, k, mu, radius_h) \
        == pytest.approx(asym, rel=1e-6)
    # 单调递增且处处低于浅仓 γh（挂料效应）
    for h in (0.1, 0.5, 2.0, 10.0):
        sv = fills.janssen_sigma_v(h, gamma, k, mu, radius_h)
        assert 0.0 < sv < gamma * h


# ---------------------------------------------------------------------- 旧契约
def test_legacy_water_defaults():
    """无 fill 字段：按满仓水，pₙ=ρgh、p_b=ρgH、无剪应力。"""
    H = 1.5
    fl = fills.parse_fill({}, H, plan_area_m2=2.0, perimeter_m=6.0)
    assert fl.kind == "fluid"
    assert fl.bulk_density == 1000.0
    assert fl.depth_m == H
    assert fl.normal_h(1.0) == pytest.approx(1000.0 * GRAVITY * 1.0)
    # 料面以上的裁剪在 xyz 装配层（埋深入参约定 ≥0）
    xyz = np.array([[0.0, H + 0.5, 0.0]])
    assert fl.normal_xyz()(xyz)[0] == 0.0
    assert fl.floor_pressure_pa == pytest.approx(1000.0 * GRAVITY * H)
    assert fl.shear_h is None
    assert fl.shear_xyz() is None


def test_legacy_overrides():
    fl = fills.parse_fill(
        {"fluid_density_kg_m3": 800.0, "water_depth_m": 0.4},
        geom_height=1.0, plan_area_m2=1.0, perimeter_m=4.0)
    assert fl.bulk_density == 800.0
    assert fl.depth_m == 0.4
    assert fl.floor_pressure_pa == pytest.approx(800.0 * GRAVITY * 0.4)


def test_bad_depth_raises():
    with pytest.raises(ValueError, match="水深"):  # 文案保留旧称以兼容
        fills.parse_fill({"water_depth_m": 3.0}, 1.0, 1.0, 4.0)
    with pytest.raises(ValueError):
        fills.parse_fill({"fill_depth_m": -0.1}, 1.0, 1.0, 4.0)


def test_explicit_null_depth_defaults_full():
    """键存在但显式为 null（AI 返回 "fill_depth_m": null）按满仓处理，
    不能 float(None) 抛 TypeError（真机回归：装满干砂料仓求解失败）。"""
    H = 2.0
    fl = fills.parse_fill(
        {"fill": "dry_sand", "fill_depth_m": None}, H,
        plan_area_m2=4.0, perimeter_m=8.0)
    assert fl.depth_m == H
    # 旧字段显式 null 同样兜底
    fl2 = fills.parse_fill({"water_depth_m": None}, H,
                           plan_area_m2=2.0, perimeter_m=6.0)
    assert fl2.depth_m == H


def test_unknown_preset_raises():
    with pytest.raises(ValueError, match="预设|preset"):
        fills.parse_fill({"fill": "not_a_material"}, 1.0, 1.0, 4.0)


# ---------------------------------------------------------------------- 散体
def test_granular_preset_string_shallow_bin():
    """干砂预设（浅仓）：pₙ=Ka·γh、p_b=γH、无壁摩擦（光滑壁 Rankine）。"""
    H = 2.0
    fl = fills.parse_fill({"fill": "dry_sand"}, H,
                          plan_area_m2=4.0, perimeter_m=8.0)
    assert fl.kind == "granular"
    assert fl.bulk_density == 1600.0
    gamma = 1600.0 * GRAVITY
    assert fl.normal_h(1.0) == pytest.approx((1.0 / 3.0) * gamma * 1.0)
    assert fl.floor_pressure_pa == pytest.approx(gamma * H)
    assert fl.shear_h is None                      # 浅仓不挂料


def test_granular_janssen_friction_and_saturation():
    """深仓：底板 σv(H) < γH，壁摩擦 τ=μpₙ；底板+壁摩擦合计=料重。"""
    R, H = 1.0, 4.0
    area, perim = math.pi * R ** 2, 2.0 * math.pi * R
    fl = fills.parse_fill(
        {"fill": {"preset": "dry_sand", "janssen": True}},
        H, plan_area_m2=area, perimeter_m=perim)
    gamma = 1600.0 * GRAVITY
    assert fl.shear_h is not None
    mu = math.tan(math.radians(20.0))
    assert fl.shear_h(1.5) == pytest.approx(mu * fl.normal_h(1.5))
    assert fl.floor_pressure_pa < gamma * H
    # 微分平衡校核：A·σv(H) + P·∫τ dh = γAH（料重全部有着落）
    hs = [i * H / 2000.0 for i in range(2001)]
    integral = sum(fl.shear_h(h) for h in hs) * (H / 2000.0)
    total = area * fl.floor_pressure_pa + perim * integral
    assert total == pytest.approx(gamma * area * H, rel=2e-3)


def test_granular_dict_with_preset_override():
    fl = fills.parse_fill(
        {"fill": {"preset": "grain_wheat", "state": "at_rest",
                  "bulk_density_kg_m3": 900.0}},
        2.0, plan_area_m2=1.0, perimeter_m=4.0)
    assert fl.bulk_density == 900.0
    # 预设 φ=28°，静止 K0=1−sin28°
    assert fl.info["K"] == pytest.approx(1.0 - math.sin(math.radians(28.0)))
    assert fl.info["state"] == "at_rest"


def test_cohesive_active_pressure_has_tension_cutoff():
    """湿砂 c=2 kPa：主动态近料面 pₙ 被截断为 0（拉应力区不能给壁加压）。"""
    fl = fills.parse_fill({"fill": "wet_sand"}, 2.0,
                          plan_area_m2=4.0, perimeter_m=8.0)
    ka = math.tan(math.radians(45.0 - 32.0 / 2.0)) ** 2
    assert fl.normal_h(0.0) == 0.0
    assert fl.normal_h(0.01) == 0.0
    deep = fl.normal_h(1.5)
    assert deep == pytest.approx(
        ka * 1900.0 * GRAVITY * 1.5 - 2.0 * 2000.0 * math.sqrt(ka))


def test_xyz_callables_follow_depth_and_height():
    """normal_xyz/shear_xyz 按世界坐标 y 计算（y 向上、料面为 depth）。"""
    fl = fills.parse_fill(
        {"fill": {"preset": "dry_sand", "janssen": True}},
        geom_height=3.0, plan_area_m2=4.0, perimeter_m=8.0)
    pn = fl.normal_xyz()
    tau = fl.shear_xyz()
    xyz = np.array([[0.0, 2.0, 0.0],   # 料深 1 m
                    [0.0, 0.0, 0.0],   # 料深 3 m
                    [0.0, 3.5, 0.0]])  # 料面以上 → 0
    assert pn(xyz)[0] == pytest.approx(fl.normal_h(1.0))
    assert pn(xyz)[1] == pytest.approx(fl.normal_h(3.0))
    assert pn(xyz)[2] == 0.0
    assert tau(xyz)[1] == pytest.approx(fl.shear_h(3.0))
    assert tau(xyz)[2] == 0.0


def test_empty_fill():
    fl = fills.parse_fill({"fill_depth_m": 0.0}, 2.0,
                          plan_area_m2=4.0, perimeter_m=8.0)
    assert fl.kind == "empty"
    assert fl.floor_pressure_pa == 0.0
    assert fl.normal_h is None and fl.normal_xyz() is None
    assert fl.shear_h is None and fl.shear_xyz() is None
