# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""三维实体 TET4 求解器（pocketsim.solid）测试。

覆盖：网格生成、单轴拉/压、纯弯、刚体位移零应力、网格收敛、墙厚/风级单调性、
非法参数、JSON 契约与力平衡。理论基准均为悬臂梁/杆件初等解。
"""
from __future__ import annotations

import json

import numpy as np
import pytest

from pocketsim import bridge, solid


# ---------------------------------------------------------------------- 工具
def _wall_model(**over):
    model = {
        "kind": "solid_wall",
        "wall": {"length_m": 4.0, "height_m": 3.0, "thickness_m": 0.24},
        "material": "砖砌体",
        "pressure_Pa": 600.0,
    }
    model.update(over)
    return model


def _free_dofs(base_nodes, comps=(0, 1, 2)):
    """按指定平动分量固定一组节点，返回自由度编号。"""
    ids = np.asarray(base_nodes, dtype=int)
    return np.unique((ids[:, None] * 3 + np.array(comps)[None, :]).ravel())


# ---------------------------------------------------------------------- 网格
def test_build_box_mesh_topology_and_volume():
    """节点/单元数量与解析式一致，所有四面体体积为正。"""
    nx, ny, nz = 3, 4, 2
    nodes, tets = solid.build_box_mesh(nx, ny, nz, 1.2, 0.8, 0.3)
    assert nodes.shape == ((nx + 1) * (ny + 1) * (nz + 1), 3)
    assert tets.shape == (6 * nx * ny * nz, 4)
    assert np.allclose(nodes[:, 0].max(), 1.2)
    assert np.allclose(nodes[:, 1].max(), 0.8)
    assert np.allclose(nodes[:, 2].max(), 0.3)

    _g, V = solid._shape_gradients(nodes, tets)
    assert V.min() > 0.0
    assert V.sum() == pytest.approx(1.2 * 0.8 * 0.3)

    faces = solid.boundary_faces(tets)
    # 每个表面四边形被剖分成 2 个三角形
    expected = 2 * 2 * (nx * ny + ny * nz + nx * nz)
    assert faces.shape == (expected, 3)


def test_wall_segments_levels_and_custom():
    """档位派生合理分段；显式 mesh 优先；未知档位/非法分段报错。"""
    nx, ny, nz = solid.wall_segments(10.0, 3.0, 0.24, level="medium")
    assert solid.MESH_LEVELS["medium"]["ny"] == ny
    assert solid.MESH_LEVELS["medium"]["nz"] == nz
    assert 4 <= nx <= 12

    assert solid.wall_segments(10.0, 3.0, 0.24, "fine",
                               {"nx": 5, "ny": 9, "nz": 3}) == (5, 9, 3)
    with pytest.raises(ValueError, match="未知网格档位"):
        solid.wall_segments(4.0, 3.0, 0.24, level="super")
    with pytest.raises(ValueError, match="≥1"):
        solid.wall_segments(4.0, 3.0, 0.24, "medium", {"nx": 0, "ny": 8, "nz": 4})


# ---------------------------------------------------------------------- 基本力学
def test_uniaxial_tension_and_compression():
    """长条单轴拉伸/压缩：中段平均 σxx = 施加面力（误差 ≤2%）。"""
    nx, ny, nz = 6, 4, 4
    L, H, T = 2.0, 0.4, 0.4
    nodes, tets = solid.build_box_mesh(nx, ny, nz, L, H, T)
    faces = solid.boundary_faces(tets)
    load_faces = solid.faces_on_plane(nodes, faces, 0, L)
    fixed = _free_dofs(solid.nodes_on_plane(nodes, 0, 0.0), comps=(0,))

    for sigma in (1000.0, -1000.0):
        result = solid.solve_solid(
            nodes, tets, E=2.0e9, nu=0.25, fixed=fixed,
            face_loads=[{"faces": load_faces,
                         "traction_fn": lambda _p, s=sigma: np.array([s, 0.0, 0.0])}],
        )
        mean_sxx = float(result["sigma"][:, 0].mean())
        assert mean_sxx == pytest.approx(sigma, rel=0.02)
        # 施加总力 = 面力 × 截面积
        assert result["applied_force"][0] == pytest.approx(sigma * H * T, rel=1e-6)


def test_pure_bending_matches_beam_theory():
    """悬臂梁端部施加纯弯矩：跨中极值纤维应力 ≈ M·c/I（误差 ≤8%）。"""
    nx, ny, nz = 24, 32, 6
    L, H, T = 2.0, 0.4, 0.4
    E, M = 2.0e9, 500.0
    I = T * H ** 3 / 12.0
    nodes, tets = solid.build_box_mesh(nx, ny, nz, L, H, T)
    faces = solid.boundary_faces(tets)
    end_faces = solid.faces_on_plane(nodes, faces, 0, L)
    fixed = solid.fixed_dofs(nodes, solid.nodes_on_plane(nodes, 0, 0.0))

    result = solid.solve_solid(
        nodes, tets, E=E, nu=0.0, fixed=fixed,
        face_loads=[{"faces": end_faces,
                     "traction_fn": lambda p: np.array([M * (p[1] - 0.5 * H) / I, 0.0, 0.0])}],
    )
    # 端部弯矩不应带来净轴力
    assert abs(result["applied_force"][0]) < 1e-6

    cent = nodes[tets].mean(axis=1)
    mid = np.abs(cent[:, 0] - 0.5 * L) <= (L / nx) * 0.6
    max_bend = float(result["sigma"][mid, 0].max())
    sigma_extreme = M * (0.5 * H) / I
    assert max_bend == pytest.approx(sigma_extreme, rel=0.08)

    # 端部挠度 ≈ M·L²/(2EI)（误差 ≤10%，TET4 弯曲收敛较慢）
    u = result["u"].reshape(-1, 3)
    tip = (np.abs(nodes[:, 0] - L) < 1e-9) & (np.abs(nodes[:, 1] - 0.5 * H) < 1e-9)
    tips = (np.abs(nodes[:, 2] - 0.5 * T) < 1e-9) & tip
    assert abs(u[tips, 1].mean()) == pytest.approx(M * L ** 2 / (2 * E * I), rel=0.10)


def test_rigid_body_motion_zero_strain():
    """微小刚体转动 + 平移：单元应变恒为 0（B 矩阵一致性）。"""
    nodes, tets = solid.build_box_mesh(3, 3, 3, 1.0, 1.0, 1.0)
    theta = np.array([0.001, -0.002, 0.003])
    u = (np.cross(theta, nodes) + np.array([1.0, 2.0, 3.0])).ravel()

    g, _V = solid._shape_gradients(nodes, tets)
    B = solid._strain_matrix(g)
    eps = np.einsum("eij,ej->ei", B, u[solid._element_dofs(tets)])
    assert np.abs(eps).max() < 1e-12


# ---------------------------------------------------------------------- 风压墙
def test_wall_wind_medium_accuracy_and_contract():
    """默认 medium 档：挠度/弯曲应力与梁理论误差 ≤10%，输出字段完整。"""
    model = _wall_model()
    result = solid.run_solid(model)

    assert result["kind"] == "solid" and result["ok"] is True
    assert result["scenario"] == "solid_wall"
    assert result["mesh"]["level"] == "medium"
    assert result["mesh"]["dof"] > 0 and result["mesh"]["n_elements"] > 0

    theory = result["theory_check"]
    assert theory["deflection_error_pct"] <= 10.0
    assert theory["bend_error_pct"] <= 10.0
    assert result["metrics"]["top_disp_mm"] > 0
    assert result["metrics"]["bend_MPa"] > 0
    assert result["metrics"]["max_compression_MPa"] > 0
    assert result["metrics"]["verdict"] in ("安全", "注意", "危险")
    assert result["metrics"]["safety_factor"] > 0

    names = [c["name"] for c in result["checks"]]
    assert names == ["墙根抗弯", "墙体抗压", "抗倾覆"]
    for check in result["checks"]:
        assert check["unit"] and "pass" in check and "safety_factor" in check

    assert result["weak_part"] and result["failure_modes"]
    assert len(result["narrative"]) >= 5
    assert result["assumptions"]
    assert result["timing"]["total_ms"] > 0


def test_mesh_convergence_errors_decrease():
    """墙高/墙厚方向加密：挠度与弯曲应力误差单调下降，最细档 ≤10%。"""
    errors = []
    for ny, nz in ((20, 6), (40, 8), (64, 8)):
        model = _wall_model(mesh={"nx": 6, "ny": ny, "nz": nz})
        result = solid.run_solid(model)
        errors.append((result["theory_check"]["deflection_error_pct"],
                       result["theory_check"]["bend_error_pct"]))
    assert errors[0][0] > errors[1][0] > errors[2][0]
    assert errors[0][1] > errors[1][1] > errors[2][1]
    assert errors[2][0] <= 10.0 and errors[2][1] <= 10.0


def test_thickness_monotonic_stiffness():
    """墙厚增大：弯曲应力下降、挠度下降、安全系数上升。"""
    rows = []
    for thickness in (0.12, 0.24, 0.36):
        model = _wall_model(
            wall={"length_m": 4.0, "height_m": 3.0, "thickness_m": thickness},
            mesh_level="coarse",
        )
        r = solid.run_solid(model)
        rows.append((r["metrics"]["bend_MPa"], r["metrics"]["top_disp_mm"],
                     r["metrics"]["safety_factor"]))
    assert rows[0][0] > rows[1][0] > rows[2][0]
    assert rows[0][1] > rows[1][1] > rows[2][1]
    assert rows[0][2] < rows[1][2] < rows[2][2]


def test_wind_speed_monotonic_and_quadratic_force():
    """风速增大：总风荷载 ∝ U²、弯曲应力上升、安全系数下降。"""
    rows = []
    for speed in (10.0, 20.0, 30.0):
        model = _wall_model(mesh_level="coarse")
        model.pop("pressure_Pa")
        model["wind_speed_m_s"] = speed
        r = solid.run_solid(model)
        rows.append((r["load"]["force_N"], r["metrics"]["bend_MPa"],
                     r["metrics"]["safety_factor"]))

    # 总力 ∝ U²（20/10 → 4 倍，30/10 → 9 倍）
    assert rows[1][0] / rows[0][0] == pytest.approx(4.0, rel=1e-3)
    assert rows[2][0] / rows[0][0] == pytest.approx(9.0, rel=1e-3)
    assert rows[0][1] < rows[1][1] < rows[2][1]
    assert rows[0][2] > rows[1][2] > rows[2][2]


def test_reactions_balance_applied_loads():
    """支反力合力与风压/自重平衡，且渲染数组长度一致。"""
    result = solid.run_solid(_wall_model(mesh_level="coarse"))
    load = result["load"]
    geom = result["geometry"]
    reactions = result["reactions"]

    assert reactions["fz_N"] == pytest.approx(-load["force_N"], abs=1.0)
    # weight_kN 只保留两位小数，放宽到 ±10 N
    assert reactions["fy_N"] == pytest.approx(geom["weight_kN"] * 1000.0, abs=10.0)
    assert abs(reactions["fx_N"]) < 1.0

    render = result["render"]
    n = len(render["nodes"])
    assert render["type"] == "solid3d" and render["unit"] == "MPa"
    assert len(render["values"]) == n and len(render["disp"]) == n
    assert len(render["tets"]) == result["mesh"]["n_elements"]
    assert max(max(t) for t in render["tets"]) < n
    disp = np.array(render["disp"])
    assert np.all(np.isfinite(disp)) and np.abs(disp).max() < 0.1


# ---------------------------------------------------------------------- 非法输入
@pytest.mark.parametrize("mutate, match", [
    (lambda m: m.pop("wall"), "缺少墙体尺寸"),
    (lambda m: m["wall"].update(thickness_m=240.0), "超出范围"),
    (lambda m: m.pop("material"), "缺少墙体材料"),
    (lambda m: m.update(material="不存在的材料"), "材料库中没有"),
])
def test_invalid_model_raises(mutate, match):
    """缺几何/墙厚填毫米/缺材料/未知材料 → 中文 ValueError。"""
    model = _wall_model()
    mutate(model)
    with pytest.raises(ValueError, match=match):
        solid.run_solid(model)


def test_missing_wind_and_bad_level_raise():
    """缺风速/未知网格档位/未知场景 → ValueError。"""
    model = _wall_model()
    model.pop("pressure_Pa")
    with pytest.raises(ValueError, match="缺少风压"):
        solid.run_solid(model)

    with pytest.raises(ValueError, match="未知网格档位"):
        solid.run_solid(_wall_model(mesh_level="super"))

    with pytest.raises(ValueError, match="暂不支持的实体场景"):
        solid.run_solid(_wall_model(kind="beam3d"))


def test_wall_ultra_mesh_level():
    """ultra 档：分段取 MESH_LEVELS["ultra"]，短墙可正常求解。"""
    nx, ny, nz = solid.wall_segments(1.5, 3.0, 0.24, level="ultra")
    assert ny == solid.MESH_LEVELS["ultra"]["ny"]
    assert nz == solid.MESH_LEVELS["ultra"]["nz"]

    model = _wall_model(
        wall={"length_m": 1.5, "height_m": 3.0, "thickness_m": 0.24},
        mesh_level="ultra",
    )
    result = solid.run_solid(model)
    assert result["mesh"]["level"] == "ultra"
    assert result["mesh"]["dof"] > solid.run_solid(
        _wall_model(
            wall={"length_m": 1.5, "height_m": 3.0, "thickness_m": 0.24},
            mesh_level="fine",
        )
    )["mesh"]["dof"]


def test_spr_constant_field_exact():
    """SPR：常应力场在节点上应精确复现（不产生外推振荡）。"""
    nodes, tets = solid.build_box_mesh(3, 4, 3, 1.0, 1.0, 1.0)
    sig = np.tile(np.array([1.0, -2.0, 0.5, 0.3, -0.4, 0.2]), (len(tets), 1))
    rec = solid.recover_stress_spr(nodes, tets, sig)
    assert rec.shape == (len(nodes), 6)
    assert np.abs(rec - sig[0]).max() < 1e-9


# ---------------------------------------------------------------------- 桥接层
def test_bridge_run_solid_json_roundtrip():
    """bridge.run_solid：JSON 入/出、禁 NaN、字段与直接调用一致。"""
    model = _wall_model(mesh={"nx": 4, "ny": 20, "nz": 6})
    raw = bridge.run_solid(json.dumps(model, ensure_ascii=False))
    data = json.loads(raw)  # allow_nan=False 已在 _dumps 内部生效

    assert data["kind"] == "solid"
    assert data["mesh"]["n_elements"] == 6 * 4 * 20 * 6
    assert data["mesh"]["dof"] > 0
    assert data["render"]["type"] == "solid3d"
    # 再序列化一次必须仍然合法（无 NaN/Infinity）
    json.dumps(data, allow_nan=False)


def test_bridge_run_solid_invalid_json_and_type():
    """非法 JSON / 非对象 → ValueError（Kotlin 侧统一错误通道）。"""
    with pytest.raises(ValueError, match="不是合法 JSON"):
        bridge.run_solid("{not json")
    with pytest.raises(ValueError, match="必须是 JSON 对象"):
        bridge.run_solid("[1, 2, 3]")


# ============================================================== run_solid_generic
def _generic_model(**over):
    base = {
        "kind": "solid_generic",
        "geometry": {"type": "box", "length_m": 2.0, "height_m": 0.3, "thickness_m": 0.3},
        "material": "钢",
        "supports": [{"type": "plane", "axis": "x", "value": 0.0, "dofs": "fixed"}],
        "loads": [{"type": "pressure", "face": {"axis": "x", "value": 2.0}, "magnitude": 1e6}],
        "mesh_level": "coarse",
    }
    base.update(over)
    return base


def test_build_cylinder_mesh_volume_and_topology():
    """圆柱网格：体积逼近 πR²H，所有单元体积为正，节点/单元数与解析式一致。"""
    R, H = 1.0, 2.0
    n_grid, ny = 10, 8
    nodes, tets = solid.build_cylinder_mesh(R, H, n_grid, ny)
    assert nodes.shape == ((n_grid + 1) ** 2 * (ny + 1), 3)
    assert tets.shape == (6 * n_grid ** 2 * ny, 4)
    _, V = solid._shape_gradients(nodes, tets)
    assert V.min() > 0.0
    vol_exact = np.pi * R ** 2 * H
    assert abs(V.sum() - vol_exact) / vol_exact < 0.01  # < 1% 体积误差


def test_generic_box_uniaxial_compression():
    """box 单轴受压：应力 = 压力，反力 = 压力×面积。"""
    L, H, T = 2.0, 0.3, 0.3
    p = 1e6
    model = _generic_model(
        geometry={"type": "box", "length_m": L, "height_m": H, "thickness_m": T},
        loads=[{"type": "pressure", "face": {"axis": "x", "value": L}, "magnitude": p}],
    )
    r = solid.run_solid_generic(model)
    assert abs(r["metrics"]["max_compression_MPa"] - p / 1e6) < 0.05
    assert abs(r["reactions"]["fx_N"] - p * H * T) < 1.0


def test_generic_cylinder_compression():
    """圆柱轴向受压：反力 ≈ 压力×截面积，压应力 ≈ 压力。"""
    import math
    R, H = 0.5, 2.0
    p = 2e6
    model = _generic_model(
        geometry={"type": "cylinder", "radius_m": R, "height_m": H},
        supports=[{"type": "plane", "axis": "y", "value": 0.0, "dofs": "fixed"}],
        loads=[{"type": "pressure", "face": {"axis": "y", "value": H}, "magnitude": p}],
    )
    r = solid.run_solid_generic(model)
    area = math.pi * R ** 2
    assert abs(r["reactions"]["fy_N"] - p * area) / (p * area) < 0.02
    # 方格→圆盘映射使边界面积略小，应力略高于名义值
    assert abs(r["metrics"]["max_compression_MPa"] - p / 1e6) < 0.25


def test_generic_plate_compression():
    """厚板板面受压：反力 = 压力×长×宽。"""
    L, W, T = 1.0, 1.0, 0.1
    p = 1e5
    model = _generic_model(
        geometry={"type": "plate", "length_m": L, "width_m": W, "thickness_m": T},
        supports=[{"type": "plane", "axis": "z", "value": 0.0, "dofs": "fixed"}],
        loads=[{"type": "pressure", "face": {"axis": "z", "value": T}, "magnitude": p}],
    )
    r = solid.run_solid_generic(model)
    assert abs(r["reactions"]["fz_N"] - p * L * W) < 1.0


def test_generic_gravity_equilibrium():
    """自重：y 向反力 = 体积×密度×g。"""
    import math
    R, H = 0.5, 2.0
    rho = 2400.0
    model = _generic_model(
        geometry={"type": "cylinder", "radius_m": R, "height_m": H},
        material="混凝土C20",
        supports=[{"type": "plane", "axis": "y", "value": 0.0, "dofs": "fixed"}],
        loads=[{"type": "gravity"}],
    )
    r = solid.run_solid_generic(model)
    weight = rho * 9.81 * math.pi * R ** 2 * H
    assert abs(r["reactions"]["fy_N"] - weight) / weight < 0.02


def test_generic_plate_gravity_points_down_thickness():
    """水平板的竖直方向是厚度 z：自重默认 -z，y 向反力约为 0。"""
    L, W, T = 1.0, 1.0, 0.1
    rho = 2400.0
    model = _generic_model(
        geometry={"type": "plate", "length_m": L, "width_m": W, "thickness_m": T},
        material="混凝土C20",
        supports=[{"type": "plane", "axis": "z", "value": 0.0, "dofs": "fixed"}],
        loads=[{"type": "gravity"}],
        mesh_level="coarse",
    )
    r = solid.run_solid_generic(model)
    weight = rho * 9.81 * L * W * T
    assert abs(r["reactions"]["fz_N"] - weight) / weight < 0.02
    assert abs(r["reactions"]["fy_N"]) < 1.0
    assert r["geometry"]["up_axis"] == "z"
    assert r["render"]["up_axis"] == "z"


def test_generic_up_axis_override():
    """geometry.up_axis 覆盖默认竖直轴（竖直放置的板用 y 向上）。"""
    L, W, T = 1.0, 1.0, 0.1
    rho = 2400.0
    model = _generic_model(
        geometry={
            "type": "plate", "length_m": L, "width_m": W, "thickness_m": T,
            "up_axis": "y",
        },
        material="混凝土C20",
        supports=[{"type": "plane", "axis": "y", "value": 0.0, "dofs": "fixed"}],
        loads=[{"type": "gravity"}],
        mesh_level="coarse",
    )
    r = solid.run_solid_generic(model)
    weight = rho * 9.81 * L * W * T
    assert abs(r["reactions"]["fy_N"] - weight) / weight < 0.02
    assert abs(r["reactions"]["fz_N"]) < 1.0
    assert r["geometry"]["up_axis"] == "y"


def test_generic_ultra_mesh_level_builds():
    """ultra 档：各图元分段数大于 fine，网格可正常构建。"""
    for geometry, key in (
        ({"type": "box", "length_m": 1.0, "height_m": 0.3, "thickness_m": 0.3}, "nz"),
        ({"type": "plate", "length_m": 1.0, "width_m": 1.0, "thickness_m": 0.1}, "nz"),
        ({"type": "cylinder", "radius_m": 0.3, "height_m": 1.0}, "n_grid"),
    ):
        nodes_u, tets_u, _, seg_u = solid._build_generic_mesh(geometry, "ultra")
        _, _, _, seg_f = solid._build_generic_mesh(geometry, "fine")
        assert seg_u[key] > seg_f[key]
        assert len(nodes_u) > 0 and len(tets_u) > 0


def test_generic_concentrated_force_equilibrium():
    """集中力：反力与施加力大小相等、方向相反。"""
    L, H, b = 2.0, 0.3, 0.3
    F = 5000.0
    model = _generic_model(
        geometry={"type": "box", "length_m": L, "height_m": H, "thickness_m": b},
        loads=[{"type": "force", "point": [L, H / 2, b / 2], "vector": [0.0, -F, 0.0]}],
    )
    r = solid.run_solid_generic(model)
    assert abs(r["reactions"]["fy_N"] - F) < 1.0


def test_generic_partial_dof_constraint():
    """部分约束：x=0 面只固定 x/y，原点固定 z，板面受压反力平衡。"""
    L, H, T = 1.0, 0.3, 0.3
    p = 1e5
    model = _generic_model(
        geometry={"type": "box", "length_m": L, "height_m": H, "thickness_m": T},
        supports=[
            {"type": "plane", "axis": "x", "value": 0.0, "dofs": [0, 1]},  # x/y 固定
            {"type": "point", "xyz": [0.0, 0.0, 0.0], "dofs": [2]},        # 原点 z 固定
        ],
        loads=[{"type": "pressure", "face": {"axis": "z", "value": T}, "magnitude": p}],
    )
    r = solid.run_solid_generic(model)
    # z 向压力由原点 z 约束承担
    assert abs(r["reactions"]["fz_N"] - p * L * H) < 1.0
    # x/y 向无载荷，反力近似为 0
    assert abs(r["reactions"]["fx_N"]) < 1.0
    assert abs(r["reactions"]["fy_N"]) < 1.0


def test_generic_invalid_geometry_type():
    """未知几何类型 → ValueError。"""
    model = _generic_model(geometry={"type": "sphere", "radius_m": 1.0})
    with pytest.raises(ValueError, match="未知几何类型"):
        solid.run_solid_generic(model)


def test_generic_missing_supports():
    """无约束 → ValueError。"""
    model = _generic_model(supports=[])
    with pytest.raises(ValueError, match="至少需要一个约束"):
        solid.run_solid_generic(model)


def test_generic_missing_loads():
    """无载荷 → ValueError。"""
    model = _generic_model(loads=[])
    with pytest.raises(ValueError, match="至少需要一个载荷"):
        solid.run_solid_generic(model)


def test_generic_unknown_load_type():
    """未知载荷类型 → ValueError。"""
    model = _generic_model(loads=[{"type": "thermal", "delta_T": 100.0}])
    with pytest.raises(ValueError, match="未知载荷类型"):
        solid.run_solid_generic(model)


def test_generic_invalid_mesh_level_tagged():
    """非法网格档位 → [G-02] 标签（前移到 AiParser 后，Python 侧仍兜底）。"""
    model = _generic_model(mesh_level="super_fine")
    with pytest.raises(ValueError, match=r"\[G-02\].*网格档位"):
        solid.run_solid_generic(model)


def test_generic_invalid_geometry_type_tagged():
    """未知几何类型 → [G-02] 标签（与 AiParser 预检对齐）。"""
    model = _generic_model(geometry={"type": "sphere", "radius_m": 1.0})
    with pytest.raises(ValueError, match=r"\[G-02\]"):
        solid.run_solid_generic(model)


def test_generic_material_not_found_tagged():
    """材料库未命中 → [M-01] 标签 + 修正建议。"""
    model = _generic_model(material="不存在的材料XYZ")
    with pytest.raises(ValueError, match=r"\[M-01\]"):
        solid.run_solid_generic(model)


def test_generic_plate_thin_ratio_solvable():
    """薄板大尺寸比（plate 在 AiParser 侧豁免尺寸比）应能正常求解。"""
    model = _generic_model(
        geometry={"type": "plate", "length_m": 2.0, "width_m": 0.5, "thickness_m": 0.005},
        supports=[{"type": "plane", "axis": "z", "value": 0.0, "dofs": "fixed"}],
        loads=[{"type": "pressure", "face": {"axis": "z", "value": 0.005}, "magnitude": 1e5}],
        mesh_level="coarse",
    )
    r = solid.run_solid_generic(model)
    assert r["metrics"]["max_compression_MPa"] >= 0


def test_generic_render_data_complete():
    """渲染数据完整：nodes/tets/disp/values 长度一致。"""
    model = _generic_model()
    r = solid.run_solid_generic(model)
    rd = r["render"]
    n = len(rd["nodes"])
    assert len(rd["tets"]) > 0
    assert len(rd["disp"]) == n
    assert len(rd["values"]) == n
    assert rd["type"] == "solid3d"
    json.dumps(r, allow_nan=False)  # 确保可序列化


# ------------------------------------------------- 鱼缸竖直玻璃板（outline + 静水压）
def _fish_tank_model(
    level: str = "medium", hydrostatic: bool = True, four_edges: bool = False
):
    """竖直玻璃板：默认底边 + 左右两侧固定、上沿自由（真实鱼缸侧壁）。

    水压作用在另一侧厚度面上，静水梯度沿竖直轴 y（底部最大）。
    four_edges=True 时改为四边轮廓固定（用于对照/对称性用例）。
    """
    L, H, T = 0.6, 0.4, 0.008
    p_bottom = 1000.0 * 9.8 * H  # 底部最大压强（Pa）
    if four_edges:
        supports = [{"type": "outline", "face": {"axis": "z", "value": 0.0},
                     "dofs": "fixed"}]
    else:
        supports = [
            {"type": "plane", "axis": axis, "value": value, "dofs": "fixed"}
            for axis, value in ((1, 0.0), (0, 0.0), (0, L))
        ]
    return {
        "kind": "solid_generic",
        "geometry": {
            "type": "plate", "length_m": L, "width_m": H,
            "thickness_m": T, "up_axis": "y",
        },
        "material": "玻璃",
        "supports": supports,
        "loads": [{"type": "pressure", "face": {"axis": "z", "value": T},
                   "magnitude": p_bottom, "hydrostatic": hydrostatic}],
        "mesh_level": level,
    }


def test_outline_support_reaction_matches_hydrostatic_resultant():
    """四边轮廓约束 + 静水压：水平反力 = 三角形分布合力 = ½·p_max·A。"""
    L, H, T = 0.6, 0.4, 0.008
    p_bottom = 1000.0 * 9.8 * H
    r = solid.run_solid_generic(_fish_tank_model())
    expected = 0.5 * p_bottom * L * H
    assert abs(abs(r["reactions"]["fz_N"]) - expected) / expected < 0.03


def test_outline_support_hydrostatic_peak_at_bottom_not_diagonal():
    """静水压：应力峰值靠近底边（y≈0），顶边应明显更小；
    不再出现约束角点的“对角高应力”伪影（角点应力接近 0）。"""
    L, H = 0.6, 0.4
    r = solid.run_solid_generic(_fish_tank_model())
    nodes = np.array(r["render"]["nodes"], dtype=float)
    vals = np.array(r["render"]["values"], dtype=float)
    peak = nodes[int(np.argmax(vals))]
    assert peak[1] < 0.1 * H, f"峰值不在底部区域：{peak}"

    y = nodes[:, 1]
    bottom = vals[y <= 0.1 * H]
    top = vals[y >= 0.9 * H]
    assert bottom.max() > top.max(), "静水压底部应力应大于顶部"

    corners = (np.isclose(y, 0.0) | np.isclose(y, H)) & (
        np.isclose(nodes[:, 0], 0.0) | np.isclose(nodes[:, 0], L)
    )
    # 角点只是场的光滑过渡，不应高于内部峰值（旧的 SPR 外推会让角点成为全场最大）
    assert vals[corners].max() < 0.75 * vals.max(), "角点出现假高应力"
    assert not (np.isclose(peak[0], 0.0) or np.isclose(peak[0], L)), "峰值落在角点"


def test_generic_uniform_pressure_symmetric_top_bottom():
    """对照（四边固定）：均布压力时上下边应力基本对称。"""
    L, H = 0.6, 0.4
    r = solid.run_solid_generic(_fish_tank_model(hydrostatic=False, four_edges=True))
    nodes = np.array(r["render"]["nodes"], dtype=float)
    vals = np.array(r["render"]["values"], dtype=float)
    y = nodes[:, 1]
    top = vals[y >= 0.9 * H].max()
    bottom = vals[y <= 0.1 * H].max()
    assert abs(bottom - top) / max(top, bottom) < 0.15, "均布压力上下应近似对称"


def test_fish_tank_cloud_is_left_right_symmetric():
    """主对角线 + 镜像对角线双剖分后，云图应关于 x=L/2 严格对称（不再对角偏置）。"""
    L, H = 0.6, 0.4
    r = solid.run_solid_generic(_fish_tank_model())
    nodes = np.array(r["render"]["nodes"], dtype=float)
    vals = np.array(r["render"]["values"], dtype=float)
    z = nodes[:, 2]
    for zi in np.unique(z):
        for yi in np.unique(nodes[:, 1]):
            left = vals[np.isclose(nodes[:, 0], 0.0)
                        & np.isclose(nodes[:, 1], yi)
                        & np.isclose(z, zi)]
            right = vals[np.isclose(nodes[:, 0], L)
                         & np.isclose(nodes[:, 1], yi)
                         & np.isclose(z, zi)]
            if left.size and right.size:
                assert abs(left[0] - right[0]) < 0.01 * max(vals.max(), 1e-9), (
                    f"左右不对称：y={yi}, z={zi}, {left[0]} vs {right[0]}"
                )


def test_outline_support_requires_existing_nodes():
    """轮廓约束面不在几何上 → ValueError（先命中边界校验，报实际范围与合法面）。"""
    model = _fish_tank_model()
    model["supports"] = [{"type": "outline", "face": {"axis": "x", "value": 2.0},
                          "dofs": "fixed"}]
    with pytest.raises(ValueError, match=r"不在实体边界上|没有节点"):
        solid.run_solid_generic(model)


# --------------------------------------------------------- P0：几何-约束-载荷一致性
def _box_model(**over):
    model = {
        "kind": "solid_generic",
        "geometry": {"type": "box", "length_m": 1.0, "height_m": 0.3,
                     "thickness_m": 0.3},
        "material": "钢",
        "supports": [{"type": "plane", "axis": "x", "value": 0.0,
                      "dofs": "fixed"}],
        "loads": [{"type": "pressure", "face": {"axis": "x", "value": 1.0},
                   "magnitude": 1e5}],
        "mesh_level": "coarse",
    }
    model.update(over)
    return model


def test_support_plane_must_be_on_boundary():
    """P0-1：约束面坐标不在实体边界上 → 提前报错（L-01/G-04）。"""
    model = _box_model(supports=[{"type": "plane", "axis": "x", "value": 0.5,
                                  "dofs": "fixed"}])
    with pytest.raises(ValueError, match="不在实体边界上"):
        solid.run_solid_generic(model)


def test_pressure_face_must_be_on_boundary():
    """P0-1：加载面坐标不在实体边界上 → 提前报错（L-01）。"""
    model = _box_model(loads=[{"type": "pressure",
                               "face": {"axis": "x", "value": 0.5},
                               "magnitude": 1e5}])
    with pytest.raises(ValueError, match="不在实体边界上"):
        solid.run_solid_generic(model)


def test_pressure_face_must_not_overlap_support():
    """P0-1：加载面与约束面重合 → 报错（BCL-03）。"""
    model = _box_model(
        supports=[{"type": "plane", "axis": "x", "value": 0.0,
                   "dofs": "fixed"}],
        loads=[{"type": "pressure", "face": {"axis": "x", "value": 0.0},
                "magnitude": 1e5}],
    )
    with pytest.raises(ValueError, match="与约束面重合"):
        solid.run_solid_generic(model)


def test_point_support_outside_bbox_rejected():
    """P0-1：点约束落在实体外 → 报错（L-01）。"""
    model = _box_model(supports=[{"type": "point", "xyz": [5.0, 0.15, 0.15],
                                  "dofs": "fixed"}])
    with pytest.raises(ValueError, match="不在实体范围内"):
        solid.run_solid_generic(model)


def test_point_force_outside_bbox_rejected():
    """P0-1：集中力作用点落在实体外 → 报错（L-01）。"""
    model = _box_model(loads=[{"type": "force", "point": [5.0, 0.15, 0.15],
                               "vector": [0.0, -1000.0, 0.0]}])
    with pytest.raises(ValueError, match="不在实体范围内"):
        solid.run_solid_generic(model)


def test_under_constrained_solid_rejected():
    """P0-2：仅固定 x 向 + y 向集中力 → 激发未约束刚体模态，解无效并报错。"""
    model = _box_model(
        supports=[{"type": "plane", "axis": "x", "value": 0.0, "dofs": [0]}],
        loads=[{"type": "force", "point": [1.0, 0.15, 0.15],
                "vector": [0.0, -1000.0, 0.0]}],
    )
    with pytest.raises(ValueError, match="无效|约束不足|机构"):
        solid.run_solid_generic(model)


def test_well_constrained_box_still_solves():
    """P0 回归：正常工况不受新增校验影响，且静力平衡通过。"""
    r = solid.run_solid_generic(_box_model())
    assert r["metrics"]["max_disp_mm"] > 0.0
    assert r["metrics"]["verdict"] in ("安全", "警告", "不安全")


# --------------------------------------------------------- 确定性单位换算（L-03）
def _base_unit_conv_model(load):
    """带 x=0 面固定的钢块（1×0.3×0.3 m），用于单位换算回归。"""
    return {
        "kind": "solid_generic",
        "geometry": {"type": "box", "length_m": 1.0, "height_m": 0.3,
                     "thickness_m": 0.3},
        "material": "钢",
        "supports": [{"type": "plane", "axis": "x", "value": 0.0,
                      "dofs": "fixed"}],
        "loads": [load],
        "mesh_level": "coarse",
    }


def test_wind_speed_to_pressure_deterministic():
    """风速 → 风压：q=½ρv²，净风压=cp×q（cp 默认 1.2）。"""
    v = 20.0
    expected = 1.2 * 0.5 * 1.225 * v ** 2
    r = solid.run_solid_generic(_base_unit_conv_model({
        "type": "pressure", "face": {"axis": "x", "value": 1.0},
        "wind_speed_m_s": v,
    }))
    src = r["load_sources"][0]
    assert f"{v:g} m/s" in src
    assert f"{expected:g} Pa" in src
    # 反应力应等于压力×面积
    fx = r["reactions"]["fx_N"]
    assert abs(abs(fx) - expected * 0.3 * 0.3) < 1.0


def test_beaufort_to_pressure_deterministic():
    """蒲福风级 → 风压，公式与风速一致。"""
    from pocketsim.flow import speed_from_beaufort
    lv = 8
    v = speed_from_beaufort(lv)
    expected = 1.2 * 0.5 * 1.225 * v ** 2
    r = solid.run_solid_generic(_base_unit_conv_model({
        "type": "pressure", "face": {"axis": "x", "value": 1.0},
        "beaufort": lv,
    }))
    src = r["load_sources"][0]
    assert f"{lv} 级风" in src
    assert f"{expected:g} Pa" in src


def test_mass_over_area_to_pressure():
    """质量/面积 → 压强 = m·g / A。"""
    m, a = 100.0, 0.09  # 100kg 压在 0.3×0.3 m² 顶面上
    expected = m * 9.8 / a
    r = solid.run_solid_generic(_base_unit_conv_model({
        "type": "pressure", "face": {"axis": "y", "value": 0.3},
        "mass_kg": m, "area_m2": a,
    }))
    src = r["load_sources"][0]
    assert f"{m:g} kg" in src and f"{a:g} m²" in src
    assert f"{expected:g} Pa" in src


def test_water_depth_hydrostatic_pressure():
    """水深 → 静水压力 ρgh，自动线性梯度。"""
    h = 0.4
    expected = 1000.0 * 9.8 * h
    r = solid.run_solid_generic(_base_unit_conv_model({
        "type": "pressure", "face": {"axis": "y", "value": 0.3},
        "fluid": "water", "depth_m": h,
    }))
    src = r["load_sources"][0]
    assert "水" in src and f"{h:g} m" in src
    assert f"{expected:g} Pa" in src


def test_soil_depth_pressure():
    """土深 → 静止土压力 K0·γ·h。"""
    h = 1.0
    expected = 0.5 * 1800.0 * 9.8 * h
    r = solid.run_solid_generic(_base_unit_conv_model({
        "type": "pressure", "face": {"axis": "y", "value": 0.3},
        "fluid": "soil", "depth_m": h,
    }))
    src = r["load_sources"][0]
    assert "土" in src and f"{h:g} m" in src
    assert f"{expected:g} Pa" in src


def test_mass_to_force_deterministic():
    """集中力：质量 → 力 = m·g，方向由 direction 决定。"""
    m = 100.0
    expected = m * 9.8
    r = solid.run_solid_generic(_base_unit_conv_model({
        "type": "force", "point": [1.0, 0.15, 0.15],
        "mass_kg": m, "direction": [0.0, -1.0, 0.0],
    }))
    src = r["load_sources"][0]
    assert f"{m:g} kg" in src and f"{expected:g} N" in src
    # 力沿 -y，反力沿 +y
    assert abs(r["reactions"]["fy_N"] - expected) < 1.0


def test_mass_without_area_rejected():
    """mass_kg 换算压强缺 area_m2 → 报错。"""
    with pytest.raises(ValueError, match="area_m2"):
        solid.run_solid_generic(_base_unit_conv_model({
            "type": "pressure", "face": {"axis": "x", "value": 1.0},
            "mass_kg": 100.0,
        }))


def test_wind_speed_out_of_range_rejected():
    """风速超出 0.5~80 m/s → 报错。"""
    with pytest.raises(ValueError, match="风速"):
        solid.run_solid_generic(_base_unit_conv_model({
            "type": "pressure", "face": {"axis": "x", "value": 1.0},
            "wind_speed_m_s": 0.1,
        }))


# --------------------------------------------------------- 压力方向显式化（L-02）
def _box_model_xface(pressure_dir=None):
    """钢块 1×0.3×0.3 m，x=0 面固定，x=1 面受均布压（可指定 pressure_dir）。"""
    load = {"type": "pressure", "face": {"axis": "x", "value": 1.0},
            "magnitude": 1e5}
    if pressure_dir is not None:
        load["pressure_dir"] = pressure_dir
    return _base_unit_conv_model(load)


def test_pressure_dir_default_equals_explicit_in():
    """L-02 兼容：省略 pressure_dir 与显式 'in' 的反力完全一致。"""
    r_default = solid.run_solid_generic(_box_model_xface())
    r_in = solid.run_solid_generic(_box_model_xface("in"))
    fx0 = r_default["reactions"]["fx_N"]
    fx1 = r_in["reactions"]["fx_N"]
    assert abs(fx0 - fx1) < 1e-6
    # 向内压：x=1 面外法线 +x，向内即 -x，反力沿 +x
    area = 0.3 * 0.3
    assert abs(fx0 - 1e5 * area) < 1.0


def test_pressure_dir_out_opposite_sign():
    """L-02 外吸：'out' 反力与 'in' 大小相等、符号相反。"""
    r_in = solid.run_solid_generic(_box_model_xface("in"))
    r_out = solid.run_solid_generic(_box_model_xface("out"))
    fx_in = r_in["reactions"]["fx_N"]
    fx_out = r_out["reactions"]["fx_N"]
    assert abs(fx_in + fx_out) < 1.0, "out 应与 in 反号"
    assert abs(abs(fx_out) - 1e5 * 0.3 * 0.3) < 1.0
    # 外吸：力沿 +x，反力沿 -x
    assert fx_out < 0 < fx_in


def test_pressure_dir_two_side_difference_balances():
    """L-02 两侧压差：x=0 面内压(+x) + x=1 面外吸(+x)，反力平衡 2·P·A。"""
    model = {
        "kind": "solid_generic",
        "geometry": {"type": "box", "length_m": 1.0, "height_m": 0.3,
                     "thickness_m": 0.3},
        "material": "钢",
        "supports": [{"type": "plane", "axis": "y", "value": 0.0,
                      "dofs": "fixed"}],
        "loads": [
            {"type": "pressure", "face": {"axis": "x", "value": 0.0},
             "magnitude": 1e5, "pressure_dir": "in"},   # +x
            {"type": "pressure", "face": {"axis": "x", "value": 1.0},
             "magnitude": 1e5, "pressure_dir": "out"},  # +x
        ],
        "mesh_level": "coarse",
    }
    r = solid.run_solid_generic(model)
    area = 0.3 * 0.3
    # 两个面压力都指向 +x，总荷载 2·P·A，反力沿 -x
    assert abs(r["reactions"]["fx_N"] + 2.0 * 1e5 * area) < 1.0


def test_pressure_dir_invalid_raises():
    """L-02 非法 pressure_dir → ValueError。"""
    with pytest.raises(ValueError, match="pressure_dir"):
        solid.run_solid_generic(_box_model_xface("sideways"))


# --------------------------------------------------------- 语义面锚点 at（L-01）
def _at_anchor_model(**over):
    """底面（y at min）固定、顶面（y at max）受压的钢块 1×0.3×0.3 m。"""
    model = {
        "kind": "solid_generic",
        "geometry": {"type": "box", "length_m": 1.0, "height_m": 0.3,
                     "thickness_m": 0.3},
        "material": "钢",
        "supports": [{"type": "plane", "axis": "y", "at": "min",
                      "dofs": [0, 1, 2]}],
        "loads": [{"type": "pressure", "face": {"axis": "y", "at": "max"},
                   "magnitude": 1e5}],
        "mesh_level": "coarse",
    }
    model.update(over)
    return model


def test_at_anchor_resolves_to_geometry_bounds():
    """at=min/max 由代码解析为实际边界坐标，求解正常且反力平衡 P·A。"""
    r = solid.run_solid_generic(_at_anchor_model())
    assert r["metrics"]["max_disp_mm"] > 0.0
    # 顶面面积 = 1.0 × 0.3，压力 1e5 Pa → 总反力 30 kN
    assert abs(abs(r["reactions"]["fy_N"]) - 1e5 * 1.0 * 0.3) < 1.0


def test_at_anchor_equivalent_to_numeric_value():
    """at 锚点与等价数值 value 的反力完全一致（同一真相源）。"""
    r_at = solid.run_solid_generic(_at_anchor_model())
    r_num = solid.run_solid_generic({
        "kind": "solid_generic",
        "geometry": {"type": "box", "length_m": 1.0, "height_m": 0.3,
                     "thickness_m": 0.3},
        "material": "钢",
        "supports": [{"type": "plane", "axis": "y", "value": 0.0,
                      "dofs": [0, 1, 2]}],
        "loads": [{"type": "pressure", "face": {"axis": "y", "value": 0.3},
                   "magnitude": 1e5}],
        "mesh_level": "coarse",
    })
    assert abs(r_at["reactions"]["fy_N"] - r_num["reactions"]["fy_N"]) < 1e-6


def test_bad_at_value_rejected():
    """at 只允许 min/max，其他值确定性报错。"""
    model = _at_anchor_model(
        supports=[{"type": "plane", "axis": "y", "at": "middle",
                   "dofs": [0, 1, 2]}]
    )
    with pytest.raises(ValueError, match="at 只能是"):
        solid.run_solid_generic(model)


def test_face_without_at_or_value_rejected():
    """面既没有 at 也没有 value → 明确报错而不是 KeyError。"""
    model = _at_anchor_model(
        supports=[{"type": "plane", "axis": "y", "dofs": [0, 1, 2]}]
    )
    with pytest.raises(ValueError, match="缺少定位"):
        solid.run_solid_generic(model)


def test_load_face_bad_at_rejected():
    """载荷面的 at 非法同样报错。"""
    model = _at_anchor_model(
        loads=[{"type": "pressure", "face": {"axis": "y", "at": "top"},
                "magnitude": 1e5}]
    )
    with pytest.raises(ValueError, match="at 只能是"):
        solid.run_solid_generic(model)


def test_cylinder_curved_face_support_rejected():
    """cylinder 的曲面（x 面）不允许平面约束/面压，只能选 y 端面。"""
    model = {
        "kind": "solid_generic",
        "geometry": {"type": "cylinder", "radius_m": 0.1, "height_m": 0.3},
        "material": "钢",
        "supports": [{"type": "plane", "axis": "x", "at": "min",
                      "dofs": [0, 1, 2]}],
        "loads": [{"type": "pressure", "face": {"axis": "y", "at": "max"},
                   "magnitude": 1e5}],
        "mesh_level": "coarse",
    }
    with pytest.raises(ValueError, match="端面|侧压"):
        solid.run_solid_generic(model)


def test_plate_outline_at_anchor_solves():
    """薄板四边轮廓约束（outline + at）秩充分，板面受压可求解。"""
    model = {
        "kind": "solid_generic",
        "geometry": {"type": "plate", "length_m": 0.4, "width_m": 0.3,
                     "thickness_m": 0.02},
        "material": "铝",
        "supports": [{"type": "outline", "face": {"axis": "z", "at": "min"},
                      "dofs": [0, 1, 2]}],
        "loads": [{"type": "pressure", "face": {"axis": "z", "at": "max"},
                   "magnitude": 5e4}],
        "mesh_level": "coarse",
    }
    r = solid.run_solid_generic(model)
    assert r["metrics"]["max_disp_mm"] > 0.0


# --------------------------------------------------------- L-05 刚体约束秩预检
def _unit_box_nodes():
    """仅用于提供包围盒范围（1×0.3×0.3）的合成节点集。"""
    return np.array([
        [0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [1.0, 0.3, 0.0], [0.0, 0.3, 0.0],
        [0.0, 0.0, 0.3], [1.0, 0.0, 0.3], [1.0, 0.3, 0.3], [0.0, 0.3, 0.3],
    ])


def test_rigid_rank_no_anchors_six_modes():
    modes = solid._rigid_body_missing_modes([], _unit_box_nodes())
    assert len(modes) == 6


def test_rigid_rank_full_face_fully_constrained():
    """一个完整面三向固定 → 6 个刚体模式全部消除。"""
    anchors = [(
        [(0.0, 0.0, 0.0), (0.0, 0.3, 0.0), (0.0, 0.3, 0.3), (0.0, 0.0, 0.3)],
        [0, 1, 2],
    )]
    assert solid._rigid_body_missing_modes(anchors, _unit_box_nodes()) == []


def test_rigid_rank_single_dof_face_leaves_three_modes():
    """x=0 面只约束 x 向：剩余 y/z 平动 + 绕 x 转动。"""
    anchors = [(
        [(0.0, 0.0, 0.0), (0.0, 0.3, 0.0), (0.0, 0.3, 0.3), (0.0, 0.0, 0.3)],
        [0],
    )]
    modes = solid._rigid_body_missing_modes(anchors, _unit_box_nodes())
    assert modes == [
        "y 方向整体平动", "z 方向整体平动", "绕 x 轴整体转动",
    ]


def test_rigid_rank_single_point_leaves_three_rotations():
    """单点三向固定：3 个平动被约束，3 个转动自由。"""
    anchors = [([(0.0, 0.0, 0.0)], [0, 1, 2])]
    modes = solid._rigid_body_missing_modes(anchors, _unit_box_nodes())
    assert modes == [
        "绕 x 轴整体转动", "绕 y 轴整体转动", "绕 z 轴整体转动",
    ]


def test_rigid_rank_two_points_leave_axis_rotation():
    """x 轴上两点三向固定：只剩绕连线（x 轴）的转动。"""
    anchors = [
        ([(0.0, 0.0, 0.0)], [0, 1, 2]),
        ([(1.0, 0.0, 0.0)], [0, 1, 2]),
    ]
    modes = solid._rigid_body_missing_modes(anchors, _unit_box_nodes())
    assert modes == ["绕 x 轴整体转动"]


def test_under_constrained_l05_names_specific_modes():
    """端到端：单面单分量约束 → [L-05] 且报出具体模式名与数量。"""
    model = _box_model(
        supports=[{"type": "plane", "axis": "x", "at": "min", "dofs": [0]}],
        loads=[{"type": "force", "point": [1.0, 0.15, 0.15],
                "vector": [0.0, -1000.0, 0.0]}],
    )
    with pytest.raises(ValueError) as exc:
        solid.run_solid_generic(model)
    msg = str(exc.value)
    assert "[L-05]" in msg
    assert "约束不足" in msg
    assert "y 方向整体平动" in msg
    assert "z 方向整体平动" in msg
    assert "绕 x 轴整体转动" in msg
    assert "共 3 个未约束模式" in msg


def test_empty_dofs_rejected():
    """dofs 空列表 → 确定性报错（不能静默忽略该约束）。"""
    model = _box_model(
        supports=[{"type": "plane", "axis": "x", "at": "min", "dofs": []}],
    )
    with pytest.raises(ValueError, match="dofs 不能为空"):
        solid.run_solid_generic(model)


# --------------------------------------------------------- point_at 点语义锚点 + unit
def _box_force_base():
    """钢块 1×0.3×0.3 m，x=0 面三向固定，用于集中力对照（point_at vs 数值 point）。"""
    return {
        "kind": "solid_generic",
        "geometry": {"type": "box", "length_m": 1.0, "height_m": 0.3,
                     "thickness_m": 0.3},
        "material": "钢",
        "supports": [{"type": "plane", "axis": "x", "value": 0.0,
                      "dofs": "fixed"}],
        "mesh_level": "coarse",
    }


def test_resolve_point_at_box_and_cylinder():
    """_resolve_point_at 按网格实际包围盒解析：box 角点在原点，cylinder x/z 居中。"""
    box_nodes, _ = solid.build_box_mesh(2, 2, 2, 1.0, 0.3, 0.3)
    # ["center","max","center"] → [0.5, 0.3, 0.15]
    np.testing.assert_allclose(
        solid._resolve_point_at(box_nodes, ["center", "max", "center"], "t"),
        [0.5, 0.3, 0.15], atol=1e-12,
    )
    cyl_nodes, _ = solid.build_cylinder_mesh(0.1, 0.3, 6, 4)
    # cylinder: x/z ∈ [-0.1, 0.1]（center→0），y ∈ [0, 0.3]
    np.testing.assert_allclose(
        solid._resolve_point_at(cyl_nodes, ["min", "max", "center"], "t"),
        [-0.1, 0.3, 0.0], atol=1e-12,
    )
    np.testing.assert_allclose(
        solid._resolve_point_at(cyl_nodes, ["center", "min", "max"], "t"),
        [0.0, 0.0, 0.1], atol=1e-12,
    )


def test_resolve_point_at_invalid_raises():
    """point_at 长度≠3 或锚点非法 → ValueError。"""
    nodes, _ = solid.build_box_mesh(2, 2, 2, 1.0, 0.3, 0.3)
    with pytest.raises(ValueError, match="三个元素"):
        solid._resolve_point_at(nodes, ["max", "center"], "t")
    with pytest.raises(ValueError, match="只能是"):
        solid._resolve_point_at(nodes, ["mid", "max", "center"], "t")


def test_force_point_at_equivalent_to_numeric_point():
    """force 用 point_at 与等价数值 point 的求解结果完全一致（同一真相源）。"""
    F = 5000.0
    base = _box_force_base()
    # ["max","center","center"] → [1.0, 0.15, 0.15]
    m_at = dict(base)
    m_at["loads"] = [{"type": "force", "point_at": ["max", "center", "center"],
                      "vector": [0.0, -F, 0.0]}]
    m_num = dict(base)
    m_num["loads"] = [{"type": "force", "point": [1.0, 0.15, 0.15],
                       "vector": [0.0, -F, 0.0]}]
    r_at = solid.run_solid_generic(m_at)
    r_num = solid.run_solid_generic(m_num)
    assert abs(r_at["reactions"]["fy_N"] - F) < 1.0
    assert abs(r_at["reactions"]["fy_N"] - r_num["reactions"]["fy_N"]) < 1e-6
    # 作用点坐标相同 → 位移场逐点一致
    assert np.allclose(r_at["render"]["disp"], r_num["render"]["disp"], atol=1e-9)


def test_point_support_point_at_equivalent_to_xyz():
    """point 约束用 point_at 与等价 xyz 的反力一致（替代 test_generic_partial_dof_constraint）。"""
    L, H, T = 1.0, 0.3, 0.3
    p = 1e5
    base = {
        "kind": "solid_generic",
        "geometry": {"type": "box", "length_m": L, "height_m": H,
                     "thickness_m": T},
        "material": "钢",
        "mesh_level": "coarse",
    }
    # ["min","min","min"] → 原点 [0,0,0]
    m_at = dict(base)
    m_at["supports"] = [
        {"type": "plane", "axis": "x", "value": 0.0, "dofs": [0, 1]},
        {"type": "point", "point_at": ["min", "min", "min"], "dofs": [2]},
    ]
    m_at["loads"] = [{"type": "pressure", "face": {"axis": "z", "value": T},
                      "magnitude": p}]
    m_xyz = dict(base)
    m_xyz["supports"] = [
        {"type": "plane", "axis": "x", "value": 0.0, "dofs": [0, 1]},
        {"type": "point", "xyz": [0.0, 0.0, 0.0], "dofs": [2]},
    ]
    m_xyz["loads"] = [{"type": "pressure", "face": {"axis": "z", "value": T},
                       "magnitude": p}]
    r_at = solid.run_solid_generic(m_at)
    r_xyz = solid.run_solid_generic(m_xyz)
    assert abs(r_at["reactions"]["fz_N"] - p * L * H) < 1.0
    assert abs(r_at["reactions"]["fz_N"] - r_xyz["reactions"]["fz_N"]) < 1e-6


def test_force_point_at_invalid_rejected():
    """端到端：force 的 point_at 锚点非法 → ValueError（触发 AI 重修）。"""
    model = _box_force_base()
    model["loads"] = [{"type": "force", "point_at": ["mid", "max", "center"],
                       "vector": [0.0, -1000.0, 0.0]}]
    with pytest.raises(ValueError, match="point_at"):
        solid.run_solid_generic(model)


def test_force_point_at_takes_precedence_over_outside_point():
    """point_at 合法（界内）但 point 越界 → point_at 优先，模型正常求解不报错。"""
    F = 3000.0
    model = _box_force_base()
    model["loads"] = [{"type": "force",
                       "point_at": ["center", "max", "center"],
                       "point": [5.0, 5.0, 5.0],
                       "vector": [0.0, -F, 0.0]}]
    r = solid.run_solid_generic(model)
    assert abs(r["reactions"]["fy_N"] - F) < 1.0


# --------------------------------------------------------- 实体挖孔（贯穿圆孔）
def _holed_plate_model(level="fine", hole=None):
    """带中心圆孔的钢板 1.0×0.5×0.02 m：x=0 面固定、x=L 面受压 20 MPa。"""
    L, W, T = 1.0, 0.5, 0.02
    if hole is None:
        hole = {"axis": "z", "center": [0.5, 0.25], "radius": 0.08}
    return {
        "kind": "solid_generic",
        "geometry": {"type": "plate", "length_m": L, "width_m": W,
                     "thickness_m": T, "holes": [hole]},
        "material": "钢",
        "supports": [{"type": "plane", "axis": "x", "value": 0.0, "dofs": "fixed"}],
        "loads": [{"type": "pressure", "face": {"axis": "x", "value": L},
                   "magnitude": 20e6}],
        "mesh_level": level,
    }


def test_box_with_holes_mesh_volume_and_positive_jacobian():
    """medium 档带孔板体积误差 ≤1%，所有四面体体积为正，双剖分节点/单元数一致。"""
    L, W, T = 1.0, 0.5, 0.02
    r = 0.08
    hole = {"axis": "z", "center": [L / 2, W / 2], "radius": r}
    cfg = solid.GENERIC_MESH_LEVELS["plate"]["medium"]
    nodes, tets = solid.build_box_with_holes_mesh(
        cfg["nx"], cfg["ny"], cfg["nz"], L, W, T, [hole])
    _, V = solid._shape_gradients(nodes, tets)
    assert V.min() > 0.0
    exact = L * W * T - np.pi * r ** 2 * T
    assert abs(V.sum() - exact) / exact < 0.01
    # main/alt 双剖分移除相同单元 → 节点集与单元数一致
    n_alt, t_alt = solid.build_box_with_holes_mesh(
        cfg["nx"], cfg["ny"], cfg["nz"], L, W, T, [hole], split="alt")
    assert len(n_alt) == len(nodes) and len(t_alt) == len(tets)


def test_holed_plate_stress_concentration_at_hole_edge():
    """带孔板受压：孔边应力集中，Kt（毛截面名义应力）∈[2,4]，最大应力节点在孔边。"""
    r = 0.08
    res = solid.run_solid_generic(_holed_plate_model(level="fine"))
    nodes = np.array(res["render"]["nodes"], dtype=float)
    vals = np.array(res["render"]["values"], dtype=float)
    imax = int(np.argmax(vals))
    pmax = nodes[imax]
    sigma_nom = 20e6 / 1e6  # 毛截面名义应力 = 施加压力
    kt = float(vals.max()) / sigma_nom
    assert 2.0 <= kt <= 4.0, f"Kt={kt:.3f} 超出预期范围 [2,4]"
    dist = float(np.hypot(pmax[0] - 0.5, pmax[1] - 0.25))
    assert 0.5 * r <= dist <= 1.15 * r, f"最大应力不在孔边：dist={dist:.3f}"


def test_hole_increases_displacement_over_solid_plate():
    """挖孔削弱净截面：带孔板最大位移比无孔板大 >5%。"""
    L, W, T = 1.0, 0.5, 0.02
    with_hole = solid.run_solid_generic(_holed_plate_model(level="fine"))
    no_hole = solid.run_solid_generic({
        "kind": "solid_generic",
        "geometry": {"type": "plate", "length_m": L, "width_m": W,
                     "thickness_m": T},
        "material": "钢",
        "supports": [{"type": "plane", "axis": "x", "value": 0.0, "dofs": "fixed"}],
        "loads": [{"type": "pressure", "face": {"axis": "x", "value": L},
                   "magnitude": 20e6}],
        "mesh_level": "fine",
    })
    ratio = with_hole["metrics"]["max_disp_mm"] / no_hole["metrics"]["max_disp_mm"]
    assert ratio > 1.05, f"挖孔后位移放大仅 {ratio:.3f}，应 >1.05"


def test_validate_holes_rejects_invalid_input():
    """_validate_holes 对非法 axis/radius/center/越界给出中文错误。"""
    dims = (1.0, 0.5, 0.02)
    for holes, match in (
        ("not_a_list", r"holes 必须是数组"),
        ([{"axis": "w", "center": [0.5, 0.25], "radius": 0.05}], r"axis 必须是 x/y/z"),
        ([{"axis": "z", "center": [0.5, 0.25], "radius": 0.0}], r"radius 必须 > 0"),
        ([{"axis": "z", "center": [0.5], "radius": 0.05}], r"center 必须是 \[c1, c2\]"),
        ([{"axis": "z", "center": ["a", 0.25], "radius": 0.05}], r"center 必须是数字"),
        ([{"axis": "z", "center": [0.5, 0.25], "radius": 0.3}], r"半径.*过大"),
        ([{"axis": "z", "center": [0.0, 0.25], "radius": 0.08}], r"center\[0\].*超出范围"),
    ):
        with pytest.raises(ValueError, match=match):
            solid._validate_holes(holes, dims)
    # 合法孔归一化：axis 转小写、center 保持
    norm = solid._validate_holes(
        [{"axis": "Z", "center": [0.5, 0.25], "radius": 0.08}], dims)
    assert norm[0]["axis"] == "z"
    assert norm[0]["center"] == [0.5, 0.25]


def test_generic_model_with_hole_renders_without_nan():
    """端到端：带孔模型渲染数据完整、无 NaN/Infinity，可再序列化。"""
    res = solid.run_solid_generic(_holed_plate_model(level="medium"))
    rd = res["render"]
    n = len(rd["nodes"])
    assert len(rd["tets"]) > 0 and len(rd["disp"]) == n and len(rd["values"]) == n
    assert np.all(np.isfinite(np.array(rd["values"], dtype=float)))
    json.dumps(res, allow_nan=False)


def test_cylinder_does_not_support_holes():
    """圆柱开孔 → [G-03] 中文报错（与 AiParser 预检对齐，不静默忽略）。"""
    model = {
        "kind": "solid_generic",
        "geometry": {"type": "cylinder", "radius_m": 0.1, "height_m": 0.3,
                     "holes": [{"axis": "y", "center": [0.0, 0.0], "radius": 0.05}]},
        "material": "钢",
        "supports": [{"type": "plane", "axis": "y", "value": 0.0, "dofs": "fixed"}],
        "loads": [{"type": "pressure", "face": {"axis": "y", "value": 0.3},
                   "magnitude": 1e5}],
        "mesh_level": "coarse",
    }
    with pytest.raises(ValueError, match=r"\[G-03\]"):
        solid.run_solid_generic(model)

# ============================================================== 应力奇点过滤（AI 优化评估用）
def test_generic_singularity_excluded_stress_bounded():
    """排除约束/集中力影响区后的最大 von Mises 必须存在、为正且不超过全局最大值。"""
    model = _generic_model()
    r = solid.run_solid_generic(model)
    m = r["metrics"]
    assert "max_von_mises_excl_singular_MPa" in m
    excl = m["max_von_mises_excl_singular_MPa"]
    assert excl > 0.0
    assert excl <= m["max_von_mises_MPa"] + 1e-9


def test_generic_singularity_excluded_stress_ignores_constraint_peak():
    """集中力作用点附近的峰值不应主导排除奇点后的特征应力。"""
    model = _generic_model(
        loads=[{"type": "pressure", "face": {"axis": "x", "value": 2.0}, "magnitude": 1e6},
               {"type": "force", "point": [1.0, 0.15, 0.15],
                "vector": [0.0, 0.0, -5000.0]}],
    )
    r = solid.run_solid_generic(model)
    m = r["metrics"]
    assert m["max_von_mises_excl_singular_MPa"] <= m["max_von_mises_MPa"] + 1e-9
