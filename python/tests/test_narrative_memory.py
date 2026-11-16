"""内存保护降级与通俗叙述（narrative）的单元测试。"""
from __future__ import annotations

import pytest

from pocketsim.engine import (
    CG_MEM_KB_PER_DOF,
    DIRECT_MEM_KB_PER_DOF,
    _downgrade_level,
    estimate_dof,
    estimate_memory_mb,
    run_scenario,
)
from pocketsim.narrative import build_narrative
from pocketsim.templates import TEMPLATES


def test_estimate_dof_matches_benchmark_scale():
    """DOF 估算与实测基准同量级（benchmark 100k DOF 算例 nx=345, ny=36）。"""
    est = estimate_dof(345, 36)
    assert est == pytest.approx(100_886, rel=0.05)


def test_estimate_memory_mb_uses_solver_branch():
    """≤5 万 DOF 用直接法系数、>5 万用 CG 系数（与 solver 切换阈值一致）。"""
    assert estimate_memory_mb(50_000) == pytest.approx(50_000 * DIRECT_MEM_KB_PER_DOF / 1024)
    assert estimate_memory_mb(60_000) == pytest.approx(60_000 * CG_MEM_KB_PER_DOF / 1024)
    # 100k DOF 的 CG 估算应低于 1GB 预算（与基准实测 294MB 一致）
    assert estimate_memory_mb(100_000) < 900.0


def test_downgrade_level_order():
    assert _downgrade_level("fine") == "medium"
    assert _downgrade_level("medium") == "coarse"
    assert _downgrade_level("coarse") == "coarse"


def test_memory_guard_downgrades_mesh():
    """内存预算极小时：自动降到 coarse 档并标注 degrade_reason=memory。"""
    result = run_scenario(
        "wall_nail", {"mass_kg": 3.0}, mesh_level="fine", mem_limit_mb=1.0
    )
    assert result["ok"] is True
    assert result["mesh"]["degraded"] is True
    assert result["mesh"]["degrade_reason"] == "memory"
    assert result["mesh"]["level"] == "coarse"
    assert result["mesh"]["requested_level"] == "fine"


def test_normal_budget_keeps_requested_level():
    """正常预算下不触发内存降级（默认档位保持 medium）。"""
    result = run_scenario("wall_nail", {"mass_kg": 3.0}, mesh_level="medium")
    assert result["ok"] is True
    assert result["mesh"]["degrade_reason"] is None


def test_narrative_paragraphs_present():
    """run_scenario 输出含非空中文叙述段落（4~6 段）。"""
    result = run_scenario("wall_nail", {"mass_kg": 3.0})
    assert result["ok"] is True
    narrative = result["narrative"]
    assert isinstance(narrative, list) and 4 <= len(narrative) <= 6
    for p in narrative:
        assert isinstance(p, str) and len(p) > 10
        assert "参考" in narrative[-1] or "专业" in narrative[-1]


def test_build_narrative_mentions_theory_check():
    """叙述段包含理论公式交叉验证与逐项校核结论。"""
    result = run_scenario("wall_nail", {"mass_kg": 3.0})
    joined = "".join(result["narrative"])
    assert "交叉验证" in joined
    assert "逐项校核" in joined
    assert "免责" not in joined  # 免责表述用“不能替代专业工程计算”等通俗说法


def test_build_narrative_direct_smoke():
    """直接调用 build_narrative：空校核/空理论时也能产出关键段落。"""
    template = TEMPLATES["wall_nail"]
    params = {"mass_kg": 3.0, "nail_diameter_mm": 3.5}
    effective = template.effective_params(params)
    metrics = {
        "max_deflection_mm": 0.42, "deflection_analogy": "类比A",
        "max_von_mises_mpa": 102.6, "safety_factor": 2.29,
        "safety_class": "安全", "safety_factor_analogy": "类比B",
    }
    paragraphs = build_narrative(
        template, effective, metrics, [], ["假设一", "假设二"], None
    )
    assert any("简化成" in p for p in paragraphs)
    assert any("0.42" in p for p in paragraphs)
    assert any("假设" in p for p in paragraphs)
