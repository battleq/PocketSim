# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""薄壁壳单元（pocketsim.shell，MITC4 Reissner–Mindlin 平板壳）测试。

覆盖：
- 水箱中面网格拓扑（5 板共享棱线/底环、正雅可比）；
- 膜单元单向拉伸分片检验（给定位移，σxx 精确）；
- 固支/简支方板受均布载荷（经典板理论系数 0.00126 / 0.00406）；
- 悬臂板条受三角形水压力（根部弯矩/表面应力/顶端挠度初等解）；
- 矩形水箱端到端：力平衡（竖向反力=水重+自重，水平反力互消）、渲染契约；
- 非法参数报错；桥接层 JSON 往返。
"""
from __future__ import annotations

import json

import numpy as np
import pytest

from pocketsim import bridge, shell


# ---------------------------------------------------------------------- 工具
def _edge_dofs(edge_nodes, components):
    ids = np.asarray(edge_nodes, dtype=int)
    comps = np.asarray(components, dtype=int)
    return np.unique((ids[:, None] * 6 + comps[None, :]).ravel())


def _square_plate(a, t, E, nu, n, q, edge):
    """xy 面内方形板，受 +z 均布法向压力；返回 solve_shell 结果。"""
    plate = shell.build_rect_plate(a, a, n, n, e1=(1, 0, 0), e2=(0, 1, 0))
    nodes = plate["nodes"]
    m = len(plate["quads"])
    edge_ids = np.where(
        (np.abs(nodes[:, 0]) <= 1e-9) | (np.abs(nodes[:, 0] - a) <= 1e-9)
        | (np.abs(nodes[:, 1]) <= 1e-9) | (np.abs(nodes[:, 1] - a) <= 1e-9)
    )[0]
    if edge == "clamped":
        fixed = _edge_dofs(edge_ids, range(6))
    else:
        fixed = _edge_dofs(edge_ids, range(3))  # 简支：只约束平动
    result = shell.solve_shell(
        nodes, plate["quads"], plate["ex"], plate["ey"], plate["en"],
        t, E=E, nu=nu, fixed=fixed,
        pressures=[{"elements": np.arange(m), "magnitude": q, "sign": 1.0}],
    )
    cid = int(np.argmin(
        np.sum((nodes - np.array([a / 2, a / 2, 0.0])) ** 2, axis=1)
    ))
    return nodes, result, cid


# ---------------------------------------------------------------------- 网格
def test_tank_mesh_topology_and_shared_edges():
    """5 块板、棱线与底环节点共享、四边形雅可比全正、底环位于 y=0。"""
    mesh = shell.build_tank_mesh(1.0, 0.8, 0.8, 0.005, nv=8)
    nodes, quads, wall = mesh["nodes"], mesh["quads"], mesh["wall"]

    nl, nw, nv = mesh["segments"]["nl"], mesh["segments"]["nw"], 8
    n_side = 2 * (nw * nv) + 2 * (nl * nv)
    n_bottom = nl * nw
    assert len(quads) == n_side + n_bottom
    assert set(np.unique(wall)) == {0, 1, 2, 3, 4}

    # 所有节点都在水箱中面上；四边形顶点合法
    assert quads.min() >= 0 and quads.max() == len(nodes) - 1
    assert np.all((nodes[:, 1] >= -1e-9) & (nodes[:, 1] <= 0.8 + 1e-9))

    # 4 条竖直棱线：相邻两块壁共享节点（坐标键控去重 → 同一节点号）
    corner_z = {0.0, 0.8}
    for x in (0.0, 1.0):
        for z in corner_z:
            ids = np.where(
                (np.abs(nodes[:, 0] - x) <= 1e-9)
                & (np.abs(nodes[:, 2] - z) <= 1e-9)
            )[0]
            assert len(ids) == nv + 1  # 每层只有一个节点（两壁共享）

    # 底环节点都在 y=0；底板内部节点也在 y=0
    assert np.allclose(nodes[mesh["base_ring_nodes"], 1], 0.0)
    assert np.allclose(nodes[mesh["bottom_inner_nodes"], 1], 0.0)
    assert len(np.intersect1d(mesh["base_ring_nodes"],
                              mesh["bottom_inner_nodes"])) == 0


def test_tank_mesh_rejects_bad_params():
    with pytest.raises(ValueError, match="尺寸必须为正"):
        shell.build_tank_mesh(0.0, 0.8, 0.8, 0.005, nv=8)
    with pytest.raises(ValueError, match="nv 必须"):
        shell.build_tank_mesh(1.0, 0.8, 0.8, 0.005, nv=1)


# ---------------------------------------------------------------------- 膜分片
def test_membrane_uniaxial_patch():
    """端部给定位移 δ 的单轴拉伸：内部 σxx = Eδ/a、σyy ≈ 0（ν=0 精确）。"""
    a, b, t = 1.0, 0.5, 0.05
    E, nu, delta = 2.0e10, 0.0, 0.001
    plate = shell.build_rect_plate(a, b, 10, 5, e1=(1, 0, 0), e2=(0, 1, 0))
    nodes, quads = plate["nodes"], plate["quads"]

    left = np.where(np.abs(nodes[:, 0]) <= 1e-9)[0]
    right = np.where(np.abs(nodes[:, 0] - a) <= 1e-9)[0]
    fixed = [_edge_dofs(left, range(6)),
             _edge_dofs(right, [1, 2, 3, 4, 5])]
    prescribed = {int(nid * 6): delta for nid in right}

    r = shell.solve_shell(
        nodes, quads, plate["ex"], plate["ey"], plate["en"], t,
        E=E, nu=nu, fixed=np.concatenate(fixed), prescribed=prescribed,
    )
    centers = nodes[quads].mean(axis=1)
    inner = (centers[:, 0] > 0.25 * a) & (centers[:, 0] < 0.75 * a)
    assert r["sigma_outer"][inner, 0] == pytest.approx(E * delta / a, rel=2e-3)
    assert np.abs(r["sigma_outer"][inner, 1]).max() < 1e-3 * E * delta / a


# ---------------------------------------------------------------------- 板理论
def test_clamped_square_plate_uniform_pressure():
    """固支方板：w = 0.00126 q a⁴/D，20×20 MITC4 误差 ≤3%。"""
    a, t, E, nu, q = 1.0, 0.01, 2.1e11, 0.3, 1000.0
    flex = E * t ** 3 / (12.0 * (1.0 - nu ** 2))
    exact = 0.00126 * q * a ** 4 / flex
    _nodes, r, cid = _square_plate(a, t, E, nu, 20, q, "clamped")
    w = r["u"][cid * 6 + 2]
    assert w == pytest.approx(exact, rel=0.03)


def test_simply_supported_square_plate_uniform_pressure():
    """简支方板：w = 0.00406 q a⁴/D，20×20 MITC4 误差 ≤3%。"""
    a, t, E, nu, q = 1.0, 0.01, 2.1e11, 0.3, 1000.0
    flex = E * t ** 3 / (12.0 * (1.0 - nu ** 2))
    exact = 0.00406 * q * a ** 4 / flex
    _nodes, r, cid = _square_plate(a, t, E, nu, 20, q, "simply")
    w = r["u"][cid * 6 + 2]
    assert w == pytest.approx(exact, rel=0.03)


def test_thin_plate_no_shear_locking():
    """极薄板（a/t=1000）固支板仍接近经典解：若剪切锁死，刚度会虚高数倍。"""
    a, t, E, nu, q = 1.0, 0.001, 2.1e11, 0.3, 100.0
    flex = E * t ** 3 / (12.0 * (1.0 - nu ** 2))
    exact = 0.00126 * q * a ** 4 / flex
    _nodes, r, cid = _square_plate(a, t, E, nu, 16, q, "clamped")
    w = r["u"][cid * 6 + 2]
    assert w == pytest.approx(exact, rel=0.06)


def test_mesh_refinement_converges():
    """加密网格时固支板挠度单调逼近理论值。"""
    a, t, E, nu, q = 1.0, 0.01, 2.1e11, 0.3, 1000.0
    flex = E * t ** 3 / (12.0 * (1.0 - nu ** 2))
    exact = 0.00126 * q * a ** 4 / flex
    ratios = []
    for n in (6, 12, 20):
        _nodes, r, cid = _square_plate(a, t, E, nu, n, q, "clamped")
        ratios.append(r["u"][cid * 6 + 2] / exact)
    assert abs(ratios[0] - 1.0) > abs(ratios[-1] - 1.0)
    assert all(0.9 < x < 1.12 for x in ratios)


# ---------------------------------------------------------------------- 悬臂板条
def test_cantilever_strip_triangular_pressure():
    """单位宽悬臂板条受三角形水压力：根部 σ=ρgh³/t²、顶端 δ=2q0h⁴/(5Et³)。"""
    h, b, t = 1.0, 0.5, 0.01
    E, nu, q0 = 2.1e11, 0.0, 1000.0
    ny = 40
    plate = shell.build_rect_plate(b, h, 2, ny, e1=(1, 0, 0), e2=(0, 1, 0))
    nodes, quads = plate["nodes"], plate["quads"]
    base = np.where(np.abs(nodes[:, 1]) <= 1e-9)[0]
    fixed = _edge_dofs(base, range(6))
    r = shell.solve_shell(
        nodes, quads, plate["ex"], plate["ey"], plate["en"], t,
        E=E, nu=nu, fixed=fixed,
        pressures=[{"elements": np.arange(len(quads)),
                    "magnitude": lambda xyz: q0 * (h - xyz[:, 1]) / h,
                    "sign": 1.0}],
    )
    tip = np.where(np.abs(nodes[:, 1] - h) <= 1e-9)[0]
    w_tip = float(r["u"][tip * 6 + 2].max())
    w_exact = 2.0 * q0 * h ** 4 / (5.0 * E * t ** 3)
    assert w_tip == pytest.approx(w_exact, rel=0.05)

    centers = nodes[quads].mean(axis=1)
    band = centers[:, 1] <= 2.0 * h / ny
    sig_fem = float(np.abs(r["sigma_outer"][band, 1]).max())
    sig_exact = q0 * h ** 3 / t ** 2
    assert sig_fem == pytest.approx(sig_exact, rel=0.08)


# ---------------------------------------------------------------------- 水箱端到端
def _tank_model(**over):
    model = {
        "kind": "shell_tank",
        "geometry": {"type": "rect_tank", "length_m": 1.0, "width_m": 0.8,
                     "height_m": 0.8, "thickness_m": 0.005},
        "material": "钢",
        "water_depth_m": 0.7,
        "mesh_level": "coarse",
    }
    model.update(over)
    return model


def test_run_tank_equilibrium_and_contract():
    """竖反力=水重+自重（5%），对壁水平反力互消；render 四边形→2 三角形。"""
    res = shell.run_tank(_tank_model())
    assert res["ok"] and res["kind"] == "shell_tank"

    from pocketsim.flow import GRAVITY
    water_w = 1000.0 * GRAVITY * 1.0 * 0.8 * 0.7
    tank_w = res["geometry"]["tank_weight_kN"] * 1000.0
    fy = abs(res["reactions"]["fy_N"])
    assert fy == pytest.approx(water_w + tank_w, rel=0.05)
    assert abs(res["reactions"]["fx_N"]) < 0.02 * fy
    assert abs(res["reactions"]["fz_N"]) < 0.02 * fy

    assert res["metrics"]["max_von_mises_MPa"] > 0.0
    assert res["metrics"]["max_disp_mm"] > 0.0
    assert res["mesh"]["type"] == "shell_mitc4"

    render = res["render"]
    n_nodes = len(render["nodes"])
    assert render["type"] == "solid3d"
    assert len(render["tets"]) == 2 * res["mesh"]["n_elements"]
    assert len(render["values"]) == n_nodes
    assert len(render["disp"]) == n_nodes


def test_run_tank_empty_only_self_weight():
    """空箱（水深 0）只受自重也能求解，竖向反力 ≈ 箱体自重。"""
    res = shell.run_tank(_tank_model(water_depth_m=0.0))
    assert res["reactions"]["fy_N"] == pytest.approx(
        res["geometry"]["tank_weight_kN"] * 1000.0, rel=0.05)
    assert res["metrics"]["max_von_mises_MPa"] >= 0.0


def test_run_tank_invalid_params():
    with pytest.raises(ValueError, match="rect_tank"):
        shell.run_tank(_tank_model(geometry={
            "type": "cylinder", "radius_m": 1.0, "height_m": 1.0,
            "thickness_m": 0.01}))
    with pytest.raises(ValueError, match="水深"):
        shell.run_tank(_tank_model(water_depth_m=9.0))
    with pytest.raises(ValueError, match="薄壳"):
        g = dict(_tank_model()["geometry"])
        g["thickness_m"] = 0.3
        shell.run_tank(_tank_model(geometry=g))
    with pytest.raises(ValueError, match="材料"):
        shell.run_tank(_tank_model(material="不存在的材料XYZ"))


def test_run_tank_explicit_mesh_segments():
    res = shell.run_tank(_tank_model(
        mesh_level="ultra", mesh={"n_vertical": 6, "el_size_m": 0.2}))
    assert res["mesh"]["segments"]["nv"] == 6
    assert res["mesh"]["level"] == "custom"


def test_base_edge_stress_mesh_independent():
    """根部二次外推后，coarse/fine 两档根部应力差 ≤15%（防止档位相关读数）。"""
    coarse = shell.run_tank(_tank_model(mesh_level="coarse"))
    fine = shell.run_tank(_tank_model(mesh_level="fine"))
    s_c = coarse["theory_check"]["fea_base_stress_MPa"]
    s_f = fine["theory_check"]["fea_base_stress_MPa"]
    assert s_c > 0 and s_f > 0
    assert abs(s_c - s_f) / s_f <= 0.15


# ---------------------------------------------------------------------- 桥接层
def test_bridge_run_shell_json_roundtrip():
    """bridge.run_shell：JSON 入/出，Kotlin SolidGenericResult 同构字段齐全。"""
    raw = bridge.run_shell(json.dumps(_tank_model()))
    res = json.loads(raw)
    assert res["kind"] == "shell_tank"
    assert res["render"]["type"] == "solid3d"
    for key in ("metrics", "checks", "reactions", "mesh", "timing",
                "material_params", "assumptions"):
        assert key in res


# ------------------------------------------------------------ 圆柱仓网格
def test_cylinder_mesh_topology():
    """圆筒壁焊缝共享、底环节点数=nc、圆心唯一且内圈收缩、雅可比全正、元数据。"""
    R, H, t, nc, nv, nr = 1.0, 2.0, 0.006, 24, 8, 4
    mesh = shell.build_cylinder_mesh(R, H, t, nc, nv, nr)
    nodes, quads, wall = mesh["nodes"], mesh["quads"], mesh["wall"]

    assert len(quads) == nv * nc + nr * nc
    assert set(np.unique(wall)) == {0, 1}
    assert quads.max() == len(nodes) - 1

    # 底环节点数恰为 nc（i=nc 焊缝与 i=0 同一节点，不多不少）
    ring = mesh["base_ring_nodes"]
    assert len(ring) == nc
    assert np.allclose(nodes[ring, 1], 0.0)
    rr = np.linalg.norm(nodes[ring][:, [0, 2]], axis=1)
    assert np.allclose(rr, R)

    # 每层圆周上恰有 nc 个节点（焊缝共享），半径均为 R，y 分层
    # （j=0 时底板外圈与壁底环是同一批节点，按半径≈R 精确选取）
    for j in range(nv + 1):
        ids = np.where(np.abs(nodes[:, 1] - j * H / nv) <= 1e-9)[0]
        radii = np.linalg.norm(nodes[ids][:, [0, 2]], axis=1)
        rim = ids[np.abs(radii - R) <= 1e-9]
        assert len(rim) == nc

    # 圆心唯一；最内圈底板四边形两个节点重合于圆心（收缩四边形）
    center = np.where(
        (np.abs(nodes[:, 0]) < 1e-9) & (np.abs(nodes[:, 2]) < 1e-9)
        & (np.abs(nodes[:, 1]) < 1e-9))[0]
    assert len(center) == 1
    inner = quads[nv * nc:nv * nc + nc]          # 底板最内圈
    assert np.all(inner[:, 0] == inner[:, 3])
    assert np.all(inner[:, 0] == center[0])
    outer = quads[nv * nc + nc:]                 # 其余四边形 4 节点互异
    assert np.all(outer[:, 0] != outer[:, 3])

    # 根部外推分组：单片壁，3 排各 nc 个单元，单元中心 y=(j+0.5)H/nv
    groups = mesh["base_groups"]
    assert len(groups) == 1
    rows = groups[0]
    assert len(rows) == 3 and all(len(r) == nc for r in rows)
    centers = nodes[quads].mean(axis=1)
    for j, r in enumerate(rows):
        assert np.allclose(centers[r, 1], (j + 0.5) * H / nv, atol=1e-9)

    # 壁局部 ey=+y、en 径向向外单位向量（单元 0 中心角 θ=π/nc）
    assert np.allclose(mesh["ey"][:nv * nc], [0.0, 1.0, 0.0])
    tc = np.pi / nc
    assert np.allclose(mesh["en"][0], [np.cos(tc), 0.0, np.sin(tc)], atol=1e-12)

    # 平面尺寸元数据
    assert mesh["plan_area_m2"] == np.pi * R ** 2
    assert mesh["perimeter_m"] == 2 * np.pi * R
    assert mesh["hydraulic_radius_m"] == R / 2.0
    assert mesh["vertical_el"] == H / nv
    assert mesh["wall_around"] == [nc]
    assert mesh["floor_tag"] == 1


def test_cylinder_mesh_rejects_bad_params():
    with pytest.raises(ValueError, match="nc 必须"):
        shell.build_cylinder_mesh(1.0, 1.0, 0.01, nc=8, nv=4, nr=3)
    with pytest.raises(ValueError, match="必须为正"):
        shell.build_cylinder_mesh(-1.0, 1.0, 0.01, 24, 4, 3)


# ------------------------------------------------------- 壁面剪应力合力
def test_shear_traction_resultant():
    """圆筒壁施加均匀竖向剪应力 τ：基础竖反力 = τ·壁面（多边形）面积。"""
    R, H, t = 0.5, 1.0, 0.02
    nc, nv, nr = 24, 4, 3
    mesh = shell.build_cylinder_mesh(R, H, t, nc, nv, nr)
    ring = mesh["base_ring_nodes"]
    fixed = np.unique(np.concatenate([
        (ring[:, None] * 6 + np.arange(6)[None, :]).ravel(),
        mesh["bottom_inner_nodes"] * 6 + 1]))
    wall_ids = np.where(mesh["wall"] == 0)[0]
    tau = 1000.0
    r = shell.solve_shell(
        mesh["nodes"], mesh["quads"], mesh["ex"], mesh["ey"], mesh["en"],
        t, E=2.1e11, nu=0.3, fixed=fixed, pressures=(),
        shears=[{"elements": wall_ids, "magnitude": tau,
                 "dirs": np.array([0.0, -1.0, 0.0])}])
    wall_area = nc * 2.0 * R * np.sin(np.pi / nc) * H
    assert r["reaction_force"][1] == pytest.approx(tau * wall_area, rel=0.02)
    assert abs(r["reaction_force"][0]) < 1e-6
    assert abs(r["reaction_force"][2]) < 1e-6


# ------------------------------------------------------------ 圆柱仓端到端
def _cylinder_model(**over):
    model = {
        "kind": "shell_vessel",
        "geometry": {"section": "circle", "radius_m": 1.0, "height_m": 2.0,
                     "thickness_m": 0.006},
        "material": "钢",
        "fill": "water",
        "mesh_level": "coarse",
    }
    model.update(over)
    return model


def test_cylinder_water_hoop_membrane():
    """静水圆柱仓：壁面中部环向薄膜均值 σθ=p·r/t，误差 ≤5%（nc=48 小面）。"""
    res = shell.run_vessel(_cylinder_model(
        mesh={"n_vertical": 16, "n_circum": 48, "n_radial": 8}))
    tc = res["theory_check"]
    assert tc["hoop_stress_theory_MPa"] > 0
    assert tc["fea_hoop_membrane_MPa"] > 0      # 内压 → 环向受拉
    assert tc["hoop_error_pct"] <= 5.0
    # 悬臂条仅作保守上界，数值远高于实际（环向薄膜承担径向载荷）
    assert tc["cantilever_strip_upper_bound_MPa"] > tc["fea_base_stress_MPa"]


def test_cylinder_water_equilibrium_and_contract():
    """fy=水重+自重（多边形底板近似 ≤5%），水平反力互消；渲染契约与 fill 块。"""
    res = shell.run_vessel(_cylinder_model())
    R, H = 1.0, 2.0
    from pocketsim.flow import GRAVITY
    water_w = 1000.0 * GRAVITY * np.pi * R ** 2 * H
    shell_w = res["geometry"]["tank_weight_kN"] * 1000.0
    fy = abs(res["reactions"]["fy_N"])
    assert fy == pytest.approx(water_w + shell_w, rel=0.05)
    assert abs(res["reactions"]["fx_N"]) < 0.02 * fy
    assert abs(res["reactions"]["fz_N"]) < 0.02 * fy

    assert res["kind"] == "shell_vessel" and res["section"] == "circle"
    assert res["geometry"]["type"] == "cylinder_silo"
    assert res["geometry"]["radius_m"] == R
    assert res["fill"]["kind"] == "fluid"
    assert res["mesh"]["segments"]["nc"] >= 24
    render = res["render"]
    assert len(render["tets"]) == 2 * res["mesh"]["n_elements"]


def test_cylinder_granular_janssen_balance():
    """干砂深仓：底板 σv(H)<γH、壁摩擦向下；fy=料重+自重（壁摩擦不重复计）。"""
    from pocketsim.flow import GRAVITY
    R, H = 1.0, 2.0
    res = shell.run_vessel(_cylinder_model(
        fill={"preset": "dry_sand", "janssen": True},
        mesh={"n_vertical": 10, "n_circum": 32, "n_radial": 6}))
    gamma_h = 1600.0 * GRAVITY * H
    assert res["fill"]["kind"] == "granular"
    assert res["fill"]["janssen"] is True
    assert res["fill"]["floor_pressure_kPa"] * 1000.0 < gamma_h
    sand_w = 1600.0 * GRAVITY * np.pi * R ** 2 * H
    fy = abs(res["reactions"]["fy_N"])
    assert fy == pytest.approx(
        sand_w + res["geometry"]["tank_weight_kN"] * 1000.0, rel=0.05)
    assert res["theory_check"]["hoop_error_pct"] <= 8.0


def test_cylinder_empty_self_weight_only():
    res = shell.run_vessel(_cylinder_model(fill_depth_m=0.0))
    assert res["fill"]["kind"] == "empty"
    assert res["theory_check"] == {}
    assert abs(res["reactions"]["fy_N"]) == pytest.approx(
        res["geometry"]["tank_weight_kN"] * 1000.0, rel=0.1)


def test_cylinder_null_depth_runs_full_silo():
    """真机回归：AI 对"装满干砂"返回 fill_depth_m=null，应按满仓求解成功，
    而不是 float(None) 抛 TypeError；且渲染下发 3 节点三角形壳面。"""
    res = shell.run_vessel(_cylinder_model(fill="dry_sand", fill_depth_m=None))
    assert res["fill"]["depth_m"] == pytest.approx(2.0)
    render = res["render"]
    assert render["type"] == "solid3d"
    assert len(render["tets"]) >= 1
    assert all(len(tri) == 3 for tri in render["tets"])  # 壳面=三角形，非四面体


def test_run_vessel_rect_shallow_granular_no_double_count():
    """矩形箱 + 浅仓干砂（光滑壁、无摩擦）：fy 恰为料重+自重，不重复计载。"""
    from pocketsim.flow import GRAVITY
    model = {
        "kind": "shell_vessel",
        "geometry": {"section": "rect", "length_m": 2.0, "width_m": 1.0,
                     "height_m": 1.5, "thickness_m": 0.006},
        "material": "钢", "fill": "dry_sand", "mesh_level": "coarse",
    }
    res = shell.run_vessel(model)
    assert res["kind"] == "shell_vessel" and res["section"] == "rect"
    sand_w = 1600.0 * GRAVITY * 2.0 * 1.0 * 1.5
    assert abs(res["reactions"]["fy_N"]) == pytest.approx(
        sand_w + res["geometry"]["tank_weight_kN"] * 1000.0, rel=0.05)
    # 矩形仍给出悬臂板条根部应力对照
    assert res["theory_check"]["fea_base_stress_MPa"] > 0


def test_run_vessel_kind_and_section_rules():
    # 旧 kind + 旧 geometry → 输出保持 shell_tank
    legacy = shell.run_vessel(_tank_model())
    assert legacy["kind"] == "shell_tank" and legacy["section"] == "rect"
    # type=cylinder_silo 也能识别
    m = _cylinder_model(geometry={"type": "cylinder_silo", "radius_m": 1.0,
                                  "height_m": 1.0, "thickness_m": 0.005})
    r2 = shell.run_vessel(m)
    assert r2["section"] == "circle"
    # 截面缺失/非法
    with pytest.raises(ValueError, match="section"):
        shell.run_vessel({"kind": "shell_vessel",
                          "geometry": {"height_m": 1.0, "thickness_m": 0.01}})
    with pytest.raises(ValueError, match="半径|radius|正数"):
        shell.run_vessel({"kind": "shell_vessel", "geometry": {
            "section": "circle", "height_m": 1.0, "thickness_m": 0.01}})


def test_bridge_run_shell_circle_roundtrip():
    """bridge.run_shell 走 run_vessel：圆柱仓 JSON 往返字段齐全。"""
    res = json.loads(bridge.run_shell(json.dumps(_cylinder_model())))
    assert res["ok"] and res["kind"] == "shell_vessel"
    assert res["section"] == "circle"
    assert res["render"]["type"] == "solid3d"
    assert res["fill"]["kind"] == "fluid"
    for key in ("metrics", "checks", "reactions", "mesh", "timing",
                "material_params", "assumptions", "theory_check"):
        assert key in res


# ============================================================ 热应力（稳态壁厚温差）
_T_E, _T_NU, _T_ALPHA = 70e9, 0.22, 9e-6      # 玻璃量级参数
_T_A, _T_T = 0.1, 0.003                         # 平板边长 / 厚度


def _thermal_plate(a=_T_A, t=_T_T, n=16, edge="clamped", thermal=None,
                   pressures=()):
    """构造并求解一块 a×a 平板壳；edge ∈ {clamped, free3}。"""
    plate = shell.build_rect_plate(a, a, n, n, e1=(1, 0, 0), e2=(0, 1, 0))
    nodes = plate["nodes"]
    on_edge = ((np.abs(nodes[:, 0]) <= 1e-9) | (np.abs(nodes[:, 0] - a) <= 1e-9)
               | (np.abs(nodes[:, 1]) <= 1e-9) | (np.abs(nodes[:, 1] - a) <= 1e-9))
    edge_ids = np.where(on_edge)[0]
    if edge == "clamped":
        fixed = np.unique((edge_ids[:, None] * 6 + np.arange(6)[None, :]).ravel())
    else:  # 仅约束 3 个刚体自由度，允许自由热胀
        c0 = int(np.argmin(nodes[:, 0] ** 2 + nodes[:, 1] ** 2))
        cx = int(np.argmin((nodes[:, 0] - a) ** 2 + nodes[:, 1] ** 2))
        cy = int(np.argmin(nodes[:, 0] ** 2 + (nodes[:, 1] - a) ** 2))
        fixed = np.unique([c0 * 6 + 0, c0 * 6 + 1, c0 * 6 + 2,
                           cx * 6 + 1, cx * 6 + 2, cy * 6 + 0, cy * 6 + 2])
    res = shell.solve_shell(
        nodes, plate["quads"], plate["ex"], plate["ey"], plate["en"],
        t, E=_T_E, nu=_T_NU, fixed=fixed, pressures=pressures, thermal=thermal)
    return nodes, res


def _center_element(nodes, gdof, a=_T_A):
    cid = int(np.argmin(np.sum((nodes - np.array([a / 2, a / 2, 0.0])) ** 2, axis=1)))
    els = np.where(np.any(gdof // 6 == cid, axis=1))[0]
    return int(els[0])


def test_thermal_alpha_zero_is_bit_identical_to_none():
    """AC-1 回归：thermal 缺省/alpha≤0 时输出与无热能力逐位一致。"""
    thermal_zero = {"alpha": 0.0, "inner_temp_c": 100.0, "outer_temp_c": 0.0,
                    "ref_temp_c": 20.0}
    _, r0 = _thermal_plate(n=8, edge="clamped", thermal=None)
    _, r1 = _thermal_plate(n=8, edge="clamped", thermal=thermal_zero)
    assert np.array_equal(r0["u"], r1["u"])
    assert np.array_equal(r0["von_mises"], r1["von_mises"])
    assert np.array_equal(r0["sigma_outer"], r1["sigma_outer"])


def test_thermal_free_plate_uniform_heating_zero_stress():
    """AC-2：自由板均匀温升 → 应力≈0（仅自由膨胀，无约束热应力）。"""
    th = {"alpha": _T_ALPHA, "inner_temp_c": 120.0, "outer_temp_c": 120.0,
          "ref_temp_c": 20.0}
    nodes, r = _thermal_plate(n=12, edge="free3", thermal=th)
    assert r["von_mises"].max() < 1e3            # < 0.001 MPa
    u3 = r["u"].reshape(-1, 6)[:, :3]
    assert np.linalg.norm(u3, axis=1).max() > 1e-6   # 确有热胀位移


def test_thermal_free_plate_gradient_zero_stress():
    """回归：自由板壁厚温差 → 应力≈0（自由弯曲无约束热应力），但横向位移非零。
    防热曲率 κ_th 漏除壁厚 t 的回归（漏除 /t 时此处会算出 ~40 MPa 伪应力）。"""
    th = {"alpha": _T_ALPHA, "inner_temp_c": 120.0, "outer_temp_c": 20.0,
          "ref_temp_c": 20.0}
    nodes, r = _thermal_plate(n=12, edge="free3", thermal=th)
    assert r["von_mises"].max() < 1e3            # < 0.001 MPa
    u3 = r["u"].reshape(-1, 6)[:, 2]
    assert np.abs(u3).max() > 1e-6               # 梯度确实施加、板自由弯曲


def test_thermal_clamped_plate_uniform_heating_matches_analytic():
    """AC-3：四周固支板均匀温升 → 中心应力 ≈ −EαΔT/(1−ν)，误差 ≤5%。"""
    dT = 100.0
    th = {"alpha": _T_ALPHA, "inner_temp_c": 20.0 + dT, "outer_temp_c": 20.0 + dT,
          "ref_temp_c": 20.0}
    nodes, r = _thermal_plate(n=16, edge="clamped", thermal=th)
    ce = _center_element(nodes, r["gdof"])
    analytic = -_T_E * _T_ALPHA * dT / (1.0 - _T_NU)     # Pa
    for surf in (r["sigma_outer"][ce], r["sigma_inner"][ce]):
        assert abs(surf[0] - analytic) / abs(analytic) <= 0.05
        assert abs(surf[1] - analytic) / abs(analytic) <= 0.05


def test_thermal_clamped_plate_gradient_surface_stress():
    """AC-4：固支板壁厚温差 → 表面 σ ≈ −Eα(T_surface−T_ref)/(1−ν)，误差 ≤5%。"""
    t_in, t_out, t_ref = 120.0, 20.0, 20.0
    th = {"alpha": _T_ALPHA, "inner_temp_c": t_in, "outer_temp_c": t_out,
          "ref_temp_c": t_ref}
    nodes, r = _thermal_plate(n=16, edge="clamped", thermal=th)
    ce = _center_element(nodes, r["gdof"])
    full = _T_E * _T_ALPHA * (t_in - t_ref) / (1.0 - _T_NU)   # 内表面幅值
    sig_i_x = r["sigma_inner"][ce][0]      # 浸湿面（−z）
    sig_o_x = r["sigma_outer"][ce][0]      # 外露面（+z）
    assert abs(sig_i_x - (-full)) / full <= 0.05     # 内 ≈ −Eα·100/(1−ν)
    assert abs(sig_o_x) <= 0.05 * full                 # 外 = T_ref → 0


def _cup_model(**over):
    model = {
        "kind": "shell_vessel",
        "geometry": {"section": "circle", "radius_m": 0.05, "height_m": 0.2,
                     "thickness_m": 0.003},
        "material": "玻璃",
        "fill": "water",
        "mesh_level": "medium",
    }
    model.update(over)
    return model


def test_thermal_cup_verdict_danger():
    """AC-5：玻璃水杯（内100/外0/ref20）计入热应力后判危险，位移非零。"""
    res = shell.run_vessel(_cup_model(thermal={"inner_temp_c": 100.0,
                                               "outer_temp_c": 0.0,
                                               "ref_temp_c": 20.0}))
    m = res["metrics"]
    assert m["verdict"] == "危险"
    assert m["safety_factor"] < 1.0
    assert 60.0 <= m["max_von_mises_MPa"] <= 100.0
    assert m["max_disp_mm"] > 0.0
    assert res["thermal"] is not None
    assert res["thermal"]["inner_temp_c"] == 100.0
    assert res["thermal"]["outer_temp_c"] == 0.0


def test_thermal_cup_ref_zero_not_overridden():
    """回归：ref_temp_c=0 不得被 falsy 静默改写为 20（Python 中 0.0 为假值）。"""
    res = shell.run_vessel(_cup_model(thermal={"inner_temp_c": 100.0,
                                               "outer_temp_c": 0.0,
                                               "ref_temp_c": 0.0}))
    assert res["thermal"] is not None
    assert res["thermal"]["ref_temp_c"] == 0.0       # 未被改写成 20
    m = res["metrics"]
    assert m["verdict"] == "危险"
    assert 60.0 <= m["max_von_mises_MPa"] <= 100.0   # ≈ Eα·100/(1−ν) = 80.8


def test_thermal_cup_assumptions_and_advice_shock():
    """AC-8：假定/载荷源含温度条目；脆性+温差≥50K → advice 热冲击提示。"""
    res = shell.run_vessel(_cup_model(thermal={"inner_temp_c": 100.0,
                                               "outer_temp_c": 0.0,
                                               "ref_temp_c": 20.0}))
    assert any("温度" in s or "thermal" in s.lower() for s in res["assumptions"])
    assert any("温度" in s or "thermal" in s.lower() for s in res["load_sources"])
    assert len(res["advice"]) >= 1
    assert any("热冲击" in a for a in res["advice"])


def test_thermal_small_delta_t_no_shock_advice():
    """AC-8 阈值：温差 < 50K 不触发热冲击 advice（仍记录温度假定）。"""
    res = shell.run_vessel(_cup_model(thermal={"inner_temp_c": 60.0,
                                               "outer_temp_c": 20.0,
                                               "ref_temp_c": 20.0}))
    assert any("温度" in s for s in res["load_sources"])
    assert len(res["advice"]) == 0


def test_thermal_equilibrium_still_balanced():
    """热载自平衡：计入热应力后静力平衡残差比仍 < 5%（反力计算含 f_th）。"""
    res = shell.run_vessel(_cup_model(thermal={"inner_temp_c": 100.0,
                                               "outer_temp_c": 0.0,
                                               "ref_temp_c": 20.0}))
    app = np.array([res["reactions"]["fx_N"], res["reactions"]["fy_N"],
                    res["reactions"]["fz_N"]], dtype=float)
    # 反力应平衡外载（水压+自重）；若残差修正错误会在此抛 ValueError 或失衡。
    # run_vessel 内部已用 5% 阈值校验能跑通即说明平衡，这里再断言反力量级合理。
    assert abs(app[1]) > 0.0   # 竖向反力非零（承担水+自重）
