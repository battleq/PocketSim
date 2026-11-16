# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""埋地柔性管工程公式校核（pocketsim.buried_pipe）测试。

覆盖：
- 典型场景端到端：Φ50×t3 PVC 管埋 2 m、公路-Ⅰ级单轮 70 kN → 字段齐全、结论合理；
- verdict 三分支映射（纯函数 + 全量极端工况）；
- 默认值显式告知 + 用户覆盖生效；
- 公式自洽性/精度：与手算基准（Boussinesq 车载扩散、圆环对径压缩挠度/弯矩、
  Timoshenko 长管外压屈曲）对比，偏差 ≤ 2%（同公式实现，验证无实现错误）；
- bridge 跨边界 JSON 往返；非法参数报错。
"""
from __future__ import annotations

import json
import math

import pytest

from pocketsim import bridge, buried_pipe


def _typical_model(**overrides):
    """Φ50 mm×t3 mm PVC 管，埋深 2 m，其余缺省（触发默认值告知）。"""
    geom = {
        "outer_diameter_m": 0.05,
        "thickness_m": 0.003,
        "cover_depth_m": 2.0,
    }
    geom.update({k: v for k, v in overrides.items() if k.startswith("soil_")
                 or k in ("wheel_load_kN", "tire_contact_m", "internal_pressure_kPa")})
    base = {"name": "埋地管", "kind": "shell_buried_pipe",
            "geometry": geom, "material": "PVC"}
    for k, v in overrides.items():
        if k not in geom:
            base[k] = v
    return base


# ---------------------------------------------------------------- TR-6.1
def test_typical_pvc_scene_fields_and_safe_verdict():
    r = buried_pipe.run_buried_pipe(_typical_model())
    assert r["kind"] == "shell_buried_pipe"
    assert r["ok"] is True
    m = r["metrics"]
    for k in ("deflection_mm", "deflection_ratio_pct", "max_ring_stress_MPa",
              "buckling_sf", "stress_ratio", "p_soil_kPa", "p_live_kPa"):
        assert k in m, f"缺少指标 {k}"
        assert math.isfinite(m[k]), f"{k} 不是有限值"
    assert m["outer_diameter_mm"] == pytest.approx(50.0, abs=1e-6)
    assert m["thickness_mm"] == pytest.approx(3.0, abs=1e-6)
    assert m["cover_depth_m"] == pytest.approx(2.0, abs=1e-6)
    # 直觉：Φ50 PVC 埋 2 m 过单轮 70 kN 不应损坏
    assert r["verdict"] == "safe"
    assert r["verdict_zh"] == "安全"
    # 渲染契约：原始圆与变形环点数一致
    assert len(r["render"]["original"]) == len(r["render"]["deformed"]) == 72
    assert r["render"]["amplification"] >= 1.0


# ---------------------------------------------------------------- TR-6.2（纯函数映射）
@pytest.mark.parametrize("dpct,sf,sratio,expected", [
    (2.0, 3.0, 0.5, "safe"),
    (4.5, 3.0, 0.5, "caution"),   # 挠度比接近限值
    (6.0, 3.0, 0.5, "unsafe"),    # 挠度过大
    (2.0, 1.5, 0.5, "caution"),   # 屈曲 SF 偏低
    (2.0, 1.0, 0.5, "unsafe"),    # 屈曲 SF 不足
    (2.0, 3.0, 0.9, "caution"),   # 应力比偏高
    (2.0, 3.0, 1.1, "unsafe"),    # 应力超限
    (4.5, 1.0, 1.1, "unsafe"),    # 多项异常 → 不安全优先
])
def test_verdict_mapping(dpct, sf, sratio, expected):
    assert buried_pipe._verdict(dpct, sf, sratio) == expected


def test_extreme_thin_pipe_yields_unsafe():
    """大口径薄壁钢管深埋：挠度比应超限 → unsafe。"""
    model = _typical_model(
        outer_diameter_m=0.6, thickness_m=0.004, cover_depth_m=3.0,
        material="钢", wheel_load_kN=140.0,
    )
    # 用覆盖字段修正几何
    model["geometry"]["outer_diameter_m"] = 0.6
    model["geometry"]["thickness_m"] = 0.004
    model["geometry"]["cover_depth_m"] = 3.0
    model["geometry"]["wheel_load_kN"] = 140.0
    r = buried_pipe.run_buried_pipe(model)
    # D/t=150 的薄壁管在 3 m 覆土 + 140 kN 下挠度比应远大于 5%
    assert r["metrics"]["deflection_ratio_pct"] > 5.0
    assert r["verdict"] == "unsafe"


# ---------------------------------------------------------------- TR-6.3（默认值告知 + 用户覆盖）
def test_defaults_reported_when_absent():
    r = buried_pipe.run_buried_pipe(_typical_model())
    blob = " ".join(r["assumptions"])
    assert "18" in blob and "kN/m³" in blob          # 默认土重度
    assert "70" in blob and "kN" in blob             # 默认车轮荷载
    assert "默认值" in blob


def test_user_soil_override_takes_effect():
    model = _typical_model(soil_gamma_kN_m3=20.0, soil_phi_deg=25.0,
                           soil_cohesion_kPa=5.0)
    model["geometry"]["soil_gamma_kN_m3"] = 20.0
    model["geometry"]["soil_phi_deg"] = 25.0
    model["geometry"]["soil_cohesion_kPa"] = 5.0
    r = buried_pipe.run_buried_pipe(model)
    assert r["metrics"]["soil_gamma_kN_m3"] == pytest.approx(20.0)
    assert r["metrics"]["soil_phi_deg"] == pytest.approx(25.0)
    # p_soil = 20*2 = 40 kPa（使用了用户值而非默认 18→36）
    assert r["metrics"]["p_soil_kPa"] == pytest.approx(40.0, abs=1e-6)
    # 不应再声明「采用默认值 18 kN/m³」
    assert not any("采用默认值 18" in a for a in r["assumptions"])


# ---------------------------------------------------------------- TR-6.4（精度：与手算基准对比）
def test_formulas_match_hand_calculation():
    """Φ50×t3 PVC 埋 2 m、单轮 70 kN 的手算基准（同公式，验证实现无错误）。

    手算：
      γ=18 kN/m³, H=2 m → p_soil = 36 kPa
      K0 = 1−sin30° = 0.5 → p_lat = 18 kPa
      Boussinesq: p_live = 3·70000/(2π·2²) = 8357 Pa ≈ 8.36 kPa
      p_drive = 36+8.36−18 = 26.36 kPa → W = 26360·0.05 = 1318 N/m
      r = 0.0235 m, I = 0.003³/12 = 2.25e-9 m⁴, E' = 3e9/(1−0.16) = 3.571e9 Pa
      Δy = 0.1488·W·r³/(E'I) ≈ 3.16e-4 m = 0.316 mm → 挠度比 ≈ 0.633%
      M = 0.1817·W·r ≈ 5.63 N·m/m → σ_b = 6M/t² ≈ 3.75 MPa
      σ_m = p_soil·r/t ≈ 0.282 MPa → σ_total ≈ 4.03 MPa, 应力比 ≈ 0.081
      p_cr = 2E(t/D)³/(1−ν²) ≈ 1543 kPa; p_applied ≈ 40.2 kPa → SF ≈ 38.4
    这些系数来自 Timoshenko《板壳理论》圆环对径压缩与长管外压屈曲经典解。
    """
    r = buried_pipe.run_buried_pipe(_typical_model())
    m = r["metrics"]
    assert m["p_soil_kPa"] == pytest.approx(36.0, abs=1e-6)
    assert m["p_lateral_kPa"] == pytest.approx(18.0, abs=1e-6)
    assert m["p_live_kPa"] == pytest.approx(8.357, abs=0.05)
    assert m["deflection_mm"] == pytest.approx(0.316, rel=0.02)
    assert m["deflection_ratio_pct"] == pytest.approx(0.633, rel=0.02)
    assert m["max_ring_stress_MPa"] == pytest.approx(4.03, rel=0.03)
    assert m["stress_ratio"] == pytest.approx(0.081, rel=0.03)
    assert m["buckling_pcr_kPa"] == pytest.approx(1543.0, rel=0.02)
    assert m["buckling_sf"] == pytest.approx(38.4, rel=0.03)


def test_internal_pressure_reduces_buckling_risk():
    """内压抵消外压 → 屈曲 SF 升高（验证 p_int 计入 p_applied）。"""
    r0 = buried_pipe.run_buried_pipe(_typical_model())
    model = _typical_model(internal_pressure_kPa=30.0)
    model["geometry"]["internal_pressure_kPa"] = 30.0
    r1 = buried_pipe.run_buried_pipe(model)
    assert r1["metrics"]["p_applied_kPa"] < r0["metrics"]["p_applied_kPa"]
    assert r1["metrics"]["buckling_sf"] > r0["metrics"]["buckling_sf"]


# ---------------------------------------------------------------- 桥接往返 / 非法参数
def test_bridge_roundtrip():
    out = bridge.run_buried_pipe(json.dumps(_typical_model()))
    r = json.loads(out)
    assert r["kind"] == "shell_buried_pipe"
    assert r["metrics"]["deflection_ratio_pct"] > 0.0


@pytest.mark.parametrize("bad,msg", [
    ({"outer_diameter_m": 0.0, "thickness_m": 0.003, "cover_depth_m": 2.0}, "outer_diameter"),
    ({"outer_diameter_m": 0.05, "thickness_m": 0.003}, "cover_depth"),
    ({"outer_diameter_m": 0.05, "thickness_m": 0.06, "cover_depth_m": 2.0}, "壁厚"),
])
def test_invalid_geometry_raises(bad, msg):
    model = {"name": "x", "kind": "shell_buried_pipe",
             "geometry": bad, "material": "PVC"}
    with pytest.raises(ValueError) as e:
        buried_pipe.run_buried_pipe(model)
    assert msg in str(e.value)


def test_missing_material_raises():
    model = {"geometry": {"outer_diameter_m": 0.05, "thickness_m": 0.003,
                          "cover_depth_m": 2.0}}
    with pytest.raises(ValueError):
        buried_pipe.run_buried_pipe(model)
