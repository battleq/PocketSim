"""框架几何不变性（机构 / 近机构）拦截测试。

覆盖两层：
1. pocketsim.stability.classify_stability 的阈值边界（合成矩阵，确定性）；
2. pocketsim.bridge.check_frame_stability 的端到端路由与 JSON 契约（真实组装）；
3. 求解路径对近机构的拦截回归（run_frame 抛中文异常）。

阈值约定（与 stability.py 一致）：
    ratio = λ_min / λ_max
    ratio ≤ 1e-14            → mechanism（机构，几何可变，硬拦）
    1e-14 < ratio ≤ 1e-10     → near_mechanism（近机构，弱刚度）
    ratio > 1e-10             → stable（稳定，放行）
"""
from __future__ import annotations

import json

import numpy as np
import pytest

from pocketsim import bridge
from pocketsim.stability import (
    DOF_LABELS_2D,
    MECH_TOL,
    NEAR_MECH_TOL,
    classify_stability,
)


# ---------------------------------------------------------------------- classify 阈值边界
def _synth(ratio: float) -> np.ndarray:
    """对角刚度阵：最小特征值 = ratio，最大 = 1。"""
    return np.diag([max(ratio, 0.0), 1.0, 1.0])


@pytest.mark.parametrize(
    "ratio, expect",
    [
        (1e-16, "mechanism"),
        (MECH_TOL, "mechanism"),          # 边界：≤ 1e-14 判机构
        (1e-12, "near_mechanism"),
        (NEAR_MECH_TOL, "near_mechanism"),  # 边界：≤ 1e-10 判近机构
        (1.01e-9, "stable"),              # 健康地板附近：略高于 1e-10 放行
        (1.0, "stable"),
    ],
)
def test_classify_stability_threshold_boundaries(ratio, expect):
    nodes = [{"id": 1}, {"id": 2}]
    free = np.array([0, 1, 2])
    verdict = classify_stability(_synth(ratio), free, nodes, DOF_LABELS_2D, 3)
    assert verdict.level == expect


def test_classify_stability_localizes_soft_dof():
    """最小特征值振型最大分量应定位到对应节点+方向。"""
    # 第 4 个自由度（节点 1 的 uy，索引 = 3*1+1）刚度极小：ratio = 1e-5/1e6 = 1e-11 → 近机构
    k = np.diag([1e6, 1e6, 1e6, 1e-5, 1e6, 1e6])
    free = np.arange(6)
    nodes = [{"id": 0}, {"id": 1}, {"id": 2}]
    verdict = classify_stability(k, free, nodes, DOF_LABELS_2D, 3)
    assert verdict.level == "near_mechanism"
    assert "节点 1" in (verdict.reason or "")


# ---------------------------------------------------------------------- 模型构造器
def _frame_model(nodes, members, supports, loads, dim=None):
    model = {
        "name": "t",
        "nodes": nodes,
        "members": members,
        "supports": supports,
        "loads": loads,
    }
    if dim is not None:
        model["dim"] = dim
    return model


def _member(i, j, b=50, h=50, material="低碳钢"):
    return {"id": 0, "i": i, "j": j, "width_b": b, "depth_h": h, "material": material}


def _check(model):
    return json.loads(bridge.check_frame_stability(json.dumps(model, ensure_ascii=False), data_dir=""))


# ---------------------------------------------------------------------- check_frame_stability 端到端
def test_precheck_healthy_beam_is_stable():
    model = _frame_model(
        nodes=[{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": 1000, "y": 0}],
        members=[_member(0, 1)],
        supports=[{"node": 0, "type": "pinned"}, {"node": 1, "type": "roller"}],
        loads=[{"type": "force", "node": 1, "fy": -1000.0}],
    )
    assert _check(model) == {"ok": True}


def test_precheck_mechanism_both_roller_blocked():
    """两端仅滚轴 → 水平可整体滑移 = 机构，应拦截并定位到 ux。"""
    model = _frame_model(
        nodes=[{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": 1000, "y": 0}],
        members=[_member(0, 1)],
        supports=[{"node": 0, "type": "roller"}, {"node": 1, "type": "roller"}],
        loads=[{"type": "force", "node": 1, "fy": -1000.0}],
    )
    res = _check(model)
    assert res["ok"] is False
    assert res["level"] == "mechanism"
    assert "ux" in res["reason"]


def test_precheck_near_mechanism_slender_cantilever_blocked():
    """悬臂梁 10×0.02 L2000：一阶弯曲最柔，ratio≈2.5e-11 → 近机构，定位到自由端 uy。"""
    model = _frame_model(
        nodes=[{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": 2000, "y": 0}],
        members=[_member(0, 1, b=10, h=0.02)],
        supports=[{"node": 0, "type": "fixed"}],
        loads=[{"type": "force", "node": 1, "fy": -1.0}],
    )
    res = _check(model)
    assert res["ok"] is False
    assert res["level"] == "near_mechanism"
    assert "节点 1" in res["reason"] and "uy" in res["reason"]


def test_precheck_boundary_above_threshold_stays_stable():
    """悬臂梁 10×0.05 L2000：ratio≈1.6e-10 略高于 NEAR_MECH_TOL，应放行（不误拦合法柔结构）。"""
    model = _frame_model(
        nodes=[{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": 2000, "y": 0}],
        members=[_member(0, 1, b=10, h=0.05)],
        supports=[{"node": 0, "type": "fixed"}],
        loads=[{"type": "force", "node": 1, "fy": -1.0}],
    )
    assert _check(model) == {"ok": True}


# ---------------------------------------------------------------------- 求解路径回归（步骤2：近机构也抛异常）
def test_run_frame_raises_on_near_mechanism():
    """近机构模型进入求解时应抛中文异常（含"机构"），不再算出无意义大变形。"""
    model = _frame_model(
        nodes=[{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": 2000, "y": 0}],
        members=[_member(0, 1, b=10, h=0.02)],
        supports=[{"node": 0, "type": "fixed"}],
        loads=[{"type": "force", "node": 1, "fy": -1.0}],
    )
    with pytest.raises(Exception) as exc_info:
        bridge.run_frame(json.dumps(model, ensure_ascii=False), data_dir="")
    assert "机构" in str(exc_info.value)
