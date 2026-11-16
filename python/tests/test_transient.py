# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""时间历程（准静态热-力）求解器测试。"""
from __future__ import annotations

import math

import pytest

from pocketsim import transient
from pocketsim.frame import run_frame


def _beam_model() -> dict:
    """三节点简支-固结梁：节点 1 固定、节点 3 铰接，节点 2 挂 10 kg 重物。"""
    return {
        "name": "温度循环试验梁",
        "nodes": [
            {"id": 1, "x": 0, "y": 0},
            {"id": 2, "x": 150, "y": 0},
            {"id": 3, "x": 300, "y": 0},
        ],
        "members": [
            {"id": 1, "i": 1, "j": 2, "width_b": 20, "depth_h": 30, "material": "松木"},
            {"id": 2, "i": 2, "j": 3, "width_b": 20, "depth_h": 30, "material": "松木"},
        ],
        "supports": [{"node": 1, "type": "fixed"}, {"node": 3, "type": "pinned"}],
        "loads": [{"type": "force", "node": 2, "fx": 0, "fy": -98}],
    }


def _spec(**over) -> dict:
    spec = {
        "name": "悬臂挂重升温再降温",
        "frame": _beam_model(),
        "duration_s": 3600,
        "steps": 10,
        "temp_base_c": 20,
        "temp_peak_c": 90,
        "profile": "triangle",
    }
    spec.update(over)
    return spec


def _step_model(t_s: float, temp: float) -> dict:
    """按同一口径重建某时刻的框架模型，用于与逐帧结果比对。"""
    model = _beam_model()
    dt = temp - 20.0
    if abs(dt) > 1e-9:
        for mid in (1, 2):
            model["loads"].append({"type": "thermal", "member": mid, "delta_temp": dt})
    return model


def test_triangle_curve_endpoints_and_peak():
    r = transient.run_transient(_spec())
    assert r["ok"] and r["kind"] == "transient"
    c = r["curves"]
    assert len(c["t_s"]) == 11
    assert c["t_s"][0] == 0.0 and c["t_s"][-1] == 3600.0
    assert c["temp_c"][0] == pytest.approx(20.0)
    assert c["temp_c"][5] == pytest.approx(90.0)
    assert c["temp_c"][-1] == pytest.approx(20.0)
    # 三角曲线线性升温：0.2 时长处为 20 + 70*0.4 = 48
    assert c["temp_c"][2] == pytest.approx(48.0)
    assert c["temp_c"][1] == pytest.approx(34.0)


def test_sine_curve_smooth_and_symmetric():
    r = transient.run_transient(_spec(profile="sine"))
    c = r["curves"]
    assert c["temp_c"][0] == pytest.approx(20.0)
    assert c["temp_c"][5] == pytest.approx(90.0)
    assert c["temp_c"][-1] == pytest.approx(20.0)
    # 正弦曲线在 0.2 时长处为 20 + 70*(1-cos(0.4π))/2 ≈ 44.18
    assert c["temp_c"][2] == pytest.approx(20.0 + 70.0 * (1 - math.cos(0.4 * math.pi)) / 2,
                                          abs=0.01)
    # 对称：t 与 duration-t 温度相同
    for i in range(1, 5):
        assert c["temp_c"][i] == pytest.approx(c["temp_c"][10 - i], abs=1e-6)


def test_worst_time_is_thermal_peak_and_matches_single_frame():
    r = transient.run_transient(_spec())
    assert r["worst"]["t_s"] == pytest.approx(1800.0)
    assert r["worst"]["temp_c"] == pytest.approx(90.0)
    # 与单独调用 frame.run_frame 在该时刻的结果一致
    single = run_frame(_step_model(1800.0, 90.0))
    assert r["worst"]["max_stress_MPa"] == pytest.approx(single["max_stress_MPa"], abs=1e-4)
    assert r["worst"]["max_disp_mm"] == pytest.approx(single["max_disp_mm"], abs=1e-4)
    # 应力随升温单调增加：峰值温度时应力大于起始常温
    assert r["worst"]["max_stress_MPa"] > r["frames"][0]["max_stress_MPa"]


def test_deformation_max_at_peak_and_curves_consistent():
    r = transient.run_transient(_spec())
    assert r["peak_disp"]["max_disp_mm"] == pytest.approx(r["max_disp_mm"])
    assert r["peak_disp"]["max_disp_mm"] == max(r["curves"]["max_disp_mm"])
    for i, f in enumerate(r["frames"]):
        assert f["max_disp_mm"] == pytest.approx(r["curves"]["max_disp_mm"][i])
        assert f["max_stress_MPa"] == pytest.approx(r["curves"]["max_stress_MPa"][i])
        assert f["temp_c"] == pytest.approx(r["curves"]["temp_c"][i])


def test_member_subset_only_heats_selected():
    r = transient.run_transient(_spec(members=[2], steps=6))
    assert r["thermal_members"] == [2]
    worst = r["frames"][r["worst"]["index"]]
    # 杆件 1 不参与温度变化 → delta_temp 为空；杆件 2 有温度变化
    assert worst["members"][0]["delta_temp"] is None
    assert worst["members"][1]["delta_temp"] == pytest.approx(70.0)


def test_over_temperature_marks_danger():
    # 松木长期耐温 120°C，峰值 150°C 应判定危险并给出提醒
    r = transient.run_transient(_spec(temp_peak_c=150, steps=6))
    assert r["over_temp"] is True
    assert r["temp_limit_c"] == pytest.approx(120.0)
    assert r["verdict"] == "危险"
    assert any("耐温" in line for line in r["narrative"])


def test_existing_thermal_loads_replaced():
    model = _beam_model()
    model["loads"].append({"type": "thermal", "member": 1, "delta_temp": 40})
    r = transient.run_transient(_spec(frame=model, steps=5))
    # 起始时刻不再保留原来 40°C 的温度载荷：应力应与纯恒载一致
    bare = run_frame(_beam_model())
    assert r["frames"][0]["max_stress_MPa"] == pytest.approx(bare["max_stress_MPa"], abs=1e-4)
    assert any("取代" in a for a in r["assumptions"])


def test_result_is_json_serializable():
    import json
    r = transient.run_transient(_spec(steps=5))
    text = json.dumps(r, ensure_ascii=False, allow_nan=False)
    assert '"kind": "transient"' in text


@pytest.mark.parametrize("over,keyword", [
    ({"steps": 3}, "steps"),
    ({"steps": 41}, "steps"),
    ({"duration_s": 0}, "duration_s"),
    ({"duration_s": -5}, "duration_s"),
    ({"temp_peak_c": 20}, "温度没有变化"),
    ({"profile": "sawtooth"}, "profile"),
    ({"members": [99]}, "不存在"),
    ({"members": []}, "members"),
])
def test_invalid_specs(over, keyword):
    with pytest.raises(ValueError) as exc:
        transient.run_transient(_spec(**over))
    assert keyword in str(exc.value)


def test_missing_peak_and_frame():
    spec = _spec()
    spec.pop("temp_peak_c")
    with pytest.raises(ValueError, match="temp_peak_c"):
        transient.run_transient(spec)
    with pytest.raises(ValueError, match="frame"):
        transient.run_transient({"duration_s": 10, "temp_peak_c": 80})


def test_requires_mechanical_load():
    model = _beam_model()
    model["loads"] = []
    with pytest.raises(ValueError, match="恒定机械载荷"):
        transient.run_transient(_spec(frame=model))


def test_step_solve_error_mentions_time():
    model = _beam_model()
    model["supports"] = []
    with pytest.raises(ValueError, match="秒"):
        transient.run_transient(_spec(frame=model, steps=5))
