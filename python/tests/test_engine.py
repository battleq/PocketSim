"""引擎入口测试：JSON 进出、材料解析失败路径、参数校验失败路径。"""
import json

import pytest

from pocketsim.engine import list_templates, run_scenario

CANTILEVER_PARAMS = {
    "length_mm": 300.0,
    "thickness_mm": 20.0,
    "width_mm": 200.0,
    "material": "松木",
    "load_kg": 10.0,
}


def test_run_cantilever_full_payload():
    """悬臂搁板完整跑通：结构齐全、理论对照 ≤5%、可 JSON 序列化。"""
    result = run_scenario("cantilever_shelf", CANTILEVER_PARAMS)
    assert result["ok"] is True
    assert result["template"] == "cantilever_shelf"
    assert result["template_name"] == "悬臂搁板"

    # 指标字段
    metrics = result["metrics"]
    for key in ("max_deflection_mm", "max_deflection_desc", "deflection_analogy",
                "max_von_mises_mpa", "safety_factor", "safety_class",
                "safety_factor_analogy"):
        assert key in metrics, f"metrics 缺少 {key}"
    assert metrics["safety_class"] in ("安全", "注意", "危险")
    assert metrics["max_deflection_mm"] == pytest.approx(0.66, abs=0.05)

    # 理论对照 ≤5%
    theory = result["theory_check"]
    assert theory["error_pct"] is not None
    assert theory["error_pct"] <= 5.0
    assert theory["fea_value_mm"] == pytest.approx(theory["value_mm"], rel=0.05)

    # 校核项 / 假设 / 渲染 / 计时 / 网格
    assert len(result["extra_checks"]) >= 1
    for check in result["extra_checks"]:
        for key in ("name", "formula", "value", "unit", "conclusion"):
            assert key in check
    assert len(result["assumptions"]) >= 5
    render = result["render"]
    assert set(render) >= {"nodes", "elements", "ux", "uy", "von_mises",
                           "x_range", "y_range"}
    assert len(render["nodes"]) == len(render["ux"]) == len(render["uy"]) == len(render["von_mises"])
    assert len(render["elements"]) > 0
    assert result["timing"]["total_s"] > 0
    mesh = result["mesh"]
    assert mesh["level"] == "medium" and mesh["dof"] > 0
    assert mesh["refined"] is False and mesh["degraded"] is False

    # 全部可 JSON 序列化且无 NaN/Infinity
    json.dumps(result, ensure_ascii=False, allow_nan=False)


def test_run_wall_nail_defaults():
    """钉挂重：只给质量，钉/墙材料取默认（低碳钢/砖砌体）。"""
    result = run_scenario("wall_nail", {"mass_kg": 3.0})
    assert result["ok"] is True
    assert result["theory_check"]["error_pct"] <= 5.0
    names = {c["name"] for c in result["extra_checks"]}
    assert {"墙孔承压校核", "抗拉拔校核（摩擦简化）", "钉身弯曲强度校核"} <= names
    # 假设列表应同时说明钉与墙两种材料
    joined = "\n".join(result["assumptions"])
    assert "低碳钢" in joined and "砖砌体" in joined
    json.dumps(result, ensure_ascii=False, allow_nan=False)


def test_unknown_material_returns_lookup_hint():
    """未知材料 → ok=false + need_material_lookup=材料名。"""
    result = run_scenario(
        "cantilever_shelf", dict(CANTILEVER_PARAMS, material="航空钛合金xyz")
    )
    assert result["ok"] is False
    assert result["need_material_lookup"] == "航空钛合金xyz"
    assert result["errors"]


def test_invalid_params_return_errors():
    """非法参数 → ok=false + 中文错误列表。"""
    # 缺必填参数
    result = run_scenario("cantilever_shelf", {"material": "松木", "load_kg": 10.0})
    assert result["ok"] is False
    assert any("length_mm" in e for e in result["errors"])

    # 厚度低于最小值
    result = run_scenario(
        "cantilever_shelf", dict(CANTILEVER_PARAMS, thickness_mm=0.5)
    )
    assert result["ok"] is False
    assert result["errors"]

    # 非数字参数
    result = run_scenario(
        "cantilever_shelf", dict(CANTILEVER_PARAMS, length_mm="三厘米")
    )
    assert result["ok"] is False
    assert result["errors"]

    # 载荷二选一缺失
    result = run_scenario(
        "cantilever_shelf",
        {"length_mm": 300.0, "thickness_mm": 20.0, "material": "松木"},
    )
    assert result["ok"] is False
    assert any("载荷" in e for e in result["errors"])


def test_unknown_template_id():
    """未知模板 id → ok=false + 可用模板列表提示。"""
    result = run_scenario("no_such_template", {})
    assert result["ok"] is False
    assert result["errors"]


def test_list_templates():
    """模板元信息：3 个模板、参数表字段齐全。"""
    info = list_templates()
    assert info["ok"] is True
    assert info["mesh_levels"] == ["coarse", "medium", "fine"]
    by_id = {t["id"]: t for t in info["templates"]}
    assert set(by_id) == {"cantilever_shelf", "wall_nail", "simply_supported_panel"}
    for tpl in info["templates"]:
        assert tpl["name"] and tpl["description"]
        assert len(tpl["params"]) >= 4
        for spec in tpl["params"]:
            for key in ("key", "label", "unit", "type", "required",
                        "default", "min", "max"):
                assert key in spec, f"{tpl['id']} 参数 {spec.get('key')} 缺少 {key}"
    # 钉模板：质量必填
    nail_params = {p["key"]: p for p in by_id["wall_nail"]["params"]}
    assert nail_params["mass_kg"]["required"] is True
    assert nail_params["nail_diameter_mm"]["default"] == 3.5
    json.dumps(info, ensure_ascii=False, allow_nan=False)


def test_effective_params_fill_defaults():
    """输出 params 回填默认值（宽度、钉直径等）。"""
    result = run_scenario("wall_nail", {"mass_kg": 3.0})
    params = result["params"]
    assert params["mass_kg"] == 3.0
    assert params["nail_diameter_mm"] == 3.5
    assert params["exposed_length_mm"] == 25
    assert params["nail_material"] == "低碳钢"
    assert params["wall_material"] == "砖砌体"


def test_coarse_level_runs():
    """coarse 档直接运行（跳过收敛自检）。"""
    result = run_scenario("cantilever_shelf", CANTILEVER_PARAMS, mesh_level="coarse")
    assert result["ok"] is True
    assert result["mesh"]["level"] == "coarse"
    assert result["mesh"]["refined"] is False
    assert result["theory_check"]["error_pct"] <= 5.0


def test_degrades_on_timeout():
    """timeout_s 极小时自动降级 coarse 重算并标注 degraded（性能降级路径）。"""
    result = run_scenario("cantilever_shelf", CANTILEVER_PARAMS, timeout_s=1e-6)
    assert result["ok"] is True
    assert result["mesh"]["degraded"] is True
    assert result["mesh"]["level"] == "coarse"
    assert result["theory_check"]["error_pct"] <= 5.0


def test_mesh_refinement_on_divergence(monkeypatch):
    """medium 与 fine 最大位移差异 >10% 时自动采用 fine 结果并标注 refined。

    通过放大 medium 档读数模拟"未收敛"，验证收敛自检的切换逻辑。
    """
    from pocketsim import engine

    calls = {"n": 0}
    real_solve = engine.run_solve_config

    def fake_solve(config):
        res = real_solve(config)
        if calls["n"] == 0:   # 第一次调用 = medium 档：读数放大 100% 制造发散
            res.max_displacement *= 2.0
        calls["n"] += 1
        return res

    monkeypatch.setattr(engine, "run_solve_config", fake_solve)
    result = engine.run_scenario("cantilever_shelf", CANTILEVER_PARAMS)
    assert result["ok"] is True
    assert result["mesh"]["refined"] is True
    assert result["mesh"]["level"] == "fine"
    assert calls["n"] == 2   # medium + fine 各一次
    # 采用 fine 结果后理论对照仍达标
    assert result["theory_check"]["error_pct"] <= 5.0
