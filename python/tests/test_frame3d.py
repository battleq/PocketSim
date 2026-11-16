# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""P3D-1 空间梁元解析解验证：悬臂/简支/竖向柱/扭转/温度/零回归。"""
import math

import pytest

from pocketsim import frame, frame3d

E = 206000.0                       # 低碳钢弹性模量 MPa
NU = 0.3
G = E / (2.0 * (1.0 + NU))
B, H = 50.0, 100.0
A = B * H
IY = B * H ** 3 / 12.0             # 绕局部 y（竖直挠曲，强轴）
IZ = H * B ** 3 / 12.0             # 绕局部 z（水平挠曲，弱轴）
J = frame3d._rect_torsion_j(B, H)
L = 1000.0

REL = 2e-3


def _model(nodes, members, supports, loads, **kw):
    m = {"name": "test", "nodes": nodes, "members": members,
         "supports": supports, "loads": loads}
    m.update(kw)
    return m


def _member(i, j, mid=1):
    return {"id": mid, "i": i, "j": j, "width_b": B, "depth_h": H, "material": "低碳钢"}


# --------------------------------------------------------------------- T1
def test_cantilever_vertical_tip_force():
    """沿 X 悬臂、自由端竖向 -Z 力：δ=PL³/(3EIy)，弯矩 P·L。"""
    m = _model(
        [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": L, "y": 0, "z": 0}],
        [_member(0, 1)],
        [{"node": 0, "type": "fixed"}],
        [{"type": "force", "node": 1, "fz": -49.0}],
        dim=3,
    )
    res = frame3d.run_frame3d(m)
    assert res["kind"] == "frame3d"
    delta = 49.0 * L ** 3 / (3.0 * E * IY)
    assert res["max_disp_mm"] == pytest.approx(delta, rel=REL)
    mem = res["members"][0]
    assert mem["My_max_Nmm"] == pytest.approx(49.0 * L, rel=REL)
    sigma = 49.0 * L * (H / 2.0) / IY
    assert mem["sigma_MPa"] == pytest.approx(sigma, rel=REL)


# --------------------------------------------------------------------- T2
def test_cantilever_horizontal_tip_force():
    """沿 X 悬臂、自由端横向 -Y 力：δ=PL³/(3EIz)（弱轴）。"""
    m = _model(
        [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": L, "y": 0, "z": 0}],
        [_member(0, 1)],
        [{"node": 0, "type": "fixed"}],
        [{"type": "force", "node": 1, "fy": -49.0}],
        dim=3,
    )
    res = frame3d.run_frame3d(m)
    delta = 49.0 * L ** 3 / (3.0 * E * IZ)
    assert res["max_disp_mm"] == pytest.approx(delta, rel=REL)
    assert res["members"][0]["Mz_max_Nmm"] == pytest.approx(49.0 * L, rel=REL)
    sigma = 49.0 * L * (B / 2.0) / IZ
    assert res["members"][0]["sigma_MPa"] == pytest.approx(sigma, rel=REL)


# --------------------------------------------------------------------- T3
def test_vertical_column_tip_force():
    """竖向柱（沿 Z）顶端水平 -X 力：局部 y=全局 X，δ=PL³/(3EIz)。"""
    m = _model(
        [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": 0, "y": 0, "z": L}],
        [_member(0, 1)],
        [{"node": 0, "type": "fixed"}],
        [{"type": "force", "node": 1, "fx": -30.0}],
        dim=3,
    )
    res = frame3d.run_frame3d(m)
    delta = 30.0 * L ** 3 / (3.0 * E * IZ)
    assert res["max_disp_mm"] == pytest.approx(delta, rel=REL)
    assert abs(res["node_disp"][1]["dx"]) == pytest.approx(delta, rel=REL)


# --------------------------------------------------------------------- T4
def test_cantilever_torsion():
    """沿 X 悬臂、自由端施加扭矩 mx：扭转角 φ=T·L/(G·J)。"""
    T = 5000.0
    m = _model(
        [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": L, "y": 0, "z": 0}],
        [_member(0, 1)],
        [{"node": 0, "type": "fixed"}],
        [{"type": "force", "node": 1, "mx": T}],
        dim=3,
    )
    res = frame3d.run_frame3d(m)
    phi = T * L / (G * J)
    assert abs(res["node_disp"][1]["rx"]) == pytest.approx(phi, rel=REL)
    assert res["members"][0]["torsion_Nmm"] == pytest.approx(T, rel=REL)


# --------------------------------------------------------------------- T5
def test_simply_supported_udl_vertical():
    """简支梁竖向均布：跨中 δ=5wL⁴/(384EIy)，M=wL²/8。一端补 rx 约束扭转模态。"""
    w = 0.2
    m = _model(
        [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": L, "y": 0, "z": 0}],
        [_member(0, 1)],
        [{"node": 0, "type": "pinned", "fix": ["rx"]}, {"node": 1, "type": "pinned"}],
        [{"type": "udl", "member": 1, "qz": -w}],
        dim=3,
    )
    res = frame3d.run_frame3d(m)
    delta = 5.0 * w * L ** 4 / (384.0 * E * IY)
    assert res["max_disp_mm"] == pytest.approx(delta, rel=5e-3)
    assert res["members"][0]["My_max_Nmm"] == pytest.approx(w * L ** 2 / 8.0, rel=5e-3)


# --------------------------------------------------------------------- T6
def test_spatial_45deg_load_superposition():
    """斜向 45° 载荷：δy 与 δz 分别由弱/强轴控制，验证双轴解耦。"""
    P = 35.0
    m = _model(
        [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": L, "y": 0, "z": 0}],
        [_member(0, 1)],
        [{"node": 0, "type": "fixed"}],
        [{"type": "force", "node": 1, "fy": -P, "fz": -P}],
        dim=3,
    )
    res = frame3d.run_frame3d(m)
    assert abs(res["node_disp"][1]["dy"]) == pytest.approx(
        P * L ** 3 / (3.0 * E * IZ), rel=REL)
    assert abs(res["node_disp"][1]["dz"]) == pytest.approx(
        P * L ** 3 / (3.0 * E * IY), rel=REL)


# --------------------------------------------------------------------- T7
def test_thermal_fixed_bar():
    """两端固定杆升温：约束热应力 σ=E·α·ΔT。"""
    dt = 50.0
    alpha = 1.2e-5
    m = _model(
        [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": L, "y": 0, "z": 0}],
        [_member(0, 1)],
        [{"node": 0, "type": "fixed"}, {"node": 1, "type": "fixed"}],
        [{"type": "thermal", "member": 1, "delta_temp": dt}],
        dim=3,
    )
    res = frame3d.run_frame3d(m)
    assert res["max_stress_MPa"] == pytest.approx(E * alpha * dt, rel=REL)
    assert res["thermal"]["has_thermal"] is True


# --------------------------------------------------------------------- T8
def test_planar_model_delegates_to_2d():
    """零回归：全部 z=0 且无面外载荷 → 走 2D 求解器，结果与 frame 完全一致。"""
    model = {
        "name": "平面简支",
        "nodes": [{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": 900, "y": 0}],
        "members": [{"id": 0, "i": 0, "j": 1, "width_b": 200, "depth_h": 18,
                     "material": "松木"}],
        "supports": [{"node": 0, "type": "pinned"}, {"node": 1, "type": "pinned"}],
        "loads": [{"type": "udl", "member": 0, "qy": -0.2}],
    }
    r3 = frame3d.run_frame3d(model)
    r2 = frame.run_frame(model)
    assert r3["kind"] == "frame"
    assert r3["max_disp_mm"] == pytest.approx(r2["max_disp_mm"], rel=1e-9)
    assert r3["max_stress_MPa"] == pytest.approx(r2["max_stress_MPa"], rel=1e-9)


# --------------------------------------------------------------------- T9
def test_planar_nodes_with_out_of_plane_load_uses_3d():
    """z=0 但有面外载荷 fz 时不能退化为 2D。"""
    m = _model(
        [{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": L, "y": 0}],
        [_member(0, 1)],
        [{"node": 0, "type": "fixed"}],
        [{"type": "force", "node": 1, "fz": -10.0}],
    )
    res = frame3d.run_frame3d(m)
    assert res["kind"] == "frame3d"


# --------------------------------------------------------------------- T10
def test_eccentric_load_bending_torsion_coupling():
    """偏心竖向力（偏移 e）等效为竖向力 + 扭矩：My=P·L、T=P·e 同时出现。"""
    P, e = 40.0, 20.0
    m = _model(
        [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": L, "y": 0, "z": 0}],
        [_member(0, 1)],
        [{"node": 0, "type": "fixed"}],
        [{"type": "force", "node": 1, "fz": -P, "mx": -P * e}],
        dim=3,
    )
    res = frame3d.run_frame3d(m)
    mem = res["members"][0]
    assert mem["My_max_Nmm"] == pytest.approx(P * L, rel=REL)
    assert mem["torsion_Nmm"] == pytest.approx(P * e, rel=REL)
    assert mem["tau_MPa"] == pytest.approx(P * e * max(B, H) / (2.0 * J), rel=REL)


# --------------------------------------------------------------------- T11
def test_four_legged_table_symmetry():
    """四腿桌子：对称加载时四角竖向下沉一致、腿轴力各 P/4。"""
    half, height = 300.0, 750.0
    corners = [(-half, -half), (half, -half), (half, half), (-half, half)]
    nodes = [{"id": i, "x": x, "y": y, "z": 0} for i, (x, y) in enumerate(corners)]
    nodes += [{"id": 4 + i, "x": x, "y": y, "z": height} for i, (x, y) in enumerate(corners)]
    members = [{"id": i, "i": i, "j": 4 + i, "width_b": 40, "depth_h": 40,
                "material": "松木"} for i in range(4)]
    for k, (a, b) in enumerate([(4, 5), (5, 6), (6, 7), (7, 4)]):
        members.append({"id": 4 + k, "i": a, "j": b, "width_b": 60, "depth_h": 80,
                        "material": "松木"})
    model = _model(nodes, members,
                   [{"node": i, "type": "fixed"} for i in range(4)],
                   [{"type": "force", "node": 4 + i, "fz": -245.0} for i in range(4)],
                   dim=3)
    res = frame3d.run_frame3d(model)
    dz = [res["node_disp"][4 + i]["dz"] for i in range(4)]
    assert max(dz) - min(dz) < 1e-9                 # 四角一致
    assert dz[0] < 0                                 # 向下
    for mem in res["members"][:4]:
        assert mem["N_N"] == pytest.approx(-245.0, rel=1e-6)   # 各腿受压 245N


# --------------------------------------------------------------------- T12
def test_error_cases():
    """非法自由度名与无支座应给出中文错误。"""
    m = _model(
        [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": L, "y": 0, "z": 0}],
        [_member(0, 1)],
        [{"node": 0, "type": "fixed"}],
        [{"type": "force", "node": 1, "fz": -1.0}],
        dim=3,
    )
    bad = {**m, "supports": [{"node": 0, "type": "pinned", "fix": ["qq"]}]}
    with pytest.raises(ValueError, match="未知自由度"):
        frame3d.run_frame3d(bad)
    with pytest.raises(ValueError, match="缺少支座"):
        frame3d.run_frame3d({**m, "supports": []})


# --------------------------------------------------------------------- T13
def test_hollow_square_section_props():
    """空心方管 40×40×2：A=304、I=73365.3、J=109744（薄壁闭口 Bredt）。"""
    props = frame3d._section_props(40.0, 40.0, 2.0)
    assert props["A"] == pytest.approx(304.0, rel=1e-9)
    assert props["Iy"] == pytest.approx(73365.3333, rel=1e-6)
    assert props["Iz"] == pytest.approx(73365.3333, rel=1e-6)
    assert props["J"] == pytest.approx(109744.0, rel=1e-9)
    assert props["A_m"] == pytest.approx(1444.0, rel=1e-9)
    assert props["hollow"] is True
    # 实心同外形：I 是空心的 2.9 倍（证明壁厚确实生效）
    solid = frame3d._section_props(40.0, 40.0, 0.0)
    assert solid["Iy"] / props["Iy"] == pytest.approx(2.908, rel=1e-3)


# --------------------------------------------------------------------- T14
def test_hollow_square_cantilever_and_torsion():
    """空心方管悬臂：δ=PL³/(3EI)、φ=TL/(GJ)、τ=T/(2·A_m·t)（Bredt）。"""
    t = 2.0
    props = frame3d._section_props(40.0, 40.0, t)
    m = _model(
        [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": L, "y": 0, "z": 0}],
        [{"id": 1, "i": 0, "j": 1, "width_b": 40, "depth_h": 40, "wall_t": t,
          "material": "低碳钢"}],
        [{"node": 0, "type": "fixed"}],
        [{"type": "force", "node": 1, "fz": -49.0, "mx": 5000.0}],
        dim=3,
    )
    res = frame3d.run_frame3d(m)
    delta = 49.0 * L ** 3 / (3.0 * E * props["Iy"])
    assert res["max_disp_mm"] == pytest.approx(delta, rel=REL)
    mem = res["members"][0]
    assert mem["section"] == "40×40×2 mm（空心）"
    assert mem["tau_MPa"] == pytest.approx(5000.0 / (2.0 * props["A_m"] * t), rel=REL)
    assert abs(res["node_disp"][1]["rx"]) == pytest.approx(
        5000.0 * L / (G * props["J"]), rel=REL)
    assert mem["sigma_vm_MPa"] > mem["sigma_MPa"]     # 扭转折算进等效应力


# --------------------------------------------------------------------- T15
def test_hollow_wall_thickness_validation():
    """壁厚非法（过大/为负）给出中文报错，提示可能把外形尺寸填成了壁厚。"""
    base = _model(
        [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": L, "y": 0, "z": 0}],
        [{"id": 1, "i": 0, "j": 1, "width_b": 40, "depth_h": 40, "wall_t": 20,
          "material": "低碳钢"}],
        [{"node": 0, "type": "fixed"}],
        [{"type": "force", "node": 1, "fz": -1.0}],
        dim=3,
    )
    with pytest.raises(ValueError, match="壁厚"):
        frame3d.run_frame3d(base)
    negative = {**base, "members": [{**base["members"][0], "wall_t": -1.0}]}
    with pytest.raises(ValueError, match="不能为负"):
        frame3d.run_frame3d(negative)


# --------------------------------------------------------------------- T16
def test_aluminum_profile_torsion_uses_library_section():
    """欧标4040铝型材：A/I/W 取库值，J 按等外形薄壁闭口（不再用 J≈2I）。"""
    a, t = 40.0, 2.2
    j_sq = t * (a - t) ** 3                      # t·(a−t)³ = 118822.3 mm⁴
    m = _model(
        [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": L, "y": 0, "z": 0}],
        [{"id": 1, "i": 0, "j": 1, "width_b": 40, "depth_h": 40,
          "material": "欧标4040铝型材"}],
        [{"node": 0, "type": "fixed"}],
        [{"type": "force", "node": 1, "fz": -49.0, "mx": 5000.0}],
        dim=3,
    )
    res = frame3d.run_frame3d(m)
    mem = res["members"][0]
    assert mem["section"] == "40×40×2.2 mm（空心）"
    # 弯曲应力用库截面模量 4600 mm³，而不是按实心外形算
    assert mem["My_max_Nmm"] == pytest.approx(49.0 * L, rel=REL)
    assert mem["sigma_MPa"] == pytest.approx(49.0 * L / 4600.0, rel=REL)
    # 扭转：Bredt τ = T/(2·A_m·t)，A_m=(a−t)²
    assert mem["tau_MPa"] == pytest.approx(5000.0 / (2.0 * (a - t) ** 2 * t), rel=REL)
    g_al = 70000.0 / (2.0 * (1.0 + 0.33))
    assert abs(res["node_disp"][1]["rx"]) == pytest.approx(5000.0 * L / (g_al * j_sq), rel=REL)


# --------------------------------------------------------------------- T17
def test_mechanism_detected_with_node_and_direction():
    """两端铰接的杆绕自身轴扭转自由（P3D-5）：报"机构"并定位节点与方向。"""
    m = _model(
        [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": L, "y": 0, "z": 0}],
        [_member(0, 1)],
        [{"node": 0, "type": "pinned"}, {"node": 1, "type": "pinned"}],
        [{"type": "force", "node": 1, "fz": -49.0, "mx": 5000.0}],
        dim=3,
    )
    with pytest.raises(ValueError, match="机构") as exc:
        frame3d.run_frame3d(m)
    assert "节点" in str(exc.value)
    # 铰接只约束平动：绕杆轴 rx 必有一个自由度未被约束
    assert "rx" in str(exc.value)

    # 同样的杆，把一端改为固定（约束全部 6 个自由度）即不再是机构
    stable = {**m, "supports": [{"node": 0, "type": "fixed"}]}
    res = frame3d.run_frame3d(stable)
    assert res["max_disp_mm"] > 0.0


# --------------------------------------------------------------------- T18
def test_member_hole_net_section_stress():
    """杆上开孔：净截面 + 孔边应力集中放大，刚度仍按全截面。"""
    m = _model(
        [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": L, "y": 0, "z": 0}],
        [dict(_member(0, 1), holes=[
            {"at_mm": L / 2.0, "shape": "circle", "d_mm": 20.0}
        ])],
        [{"node": 0, "type": "fixed"}],
        [{"type": "force", "node": 1, "fz": -49.0}],
        dim=3,
    )
    res = frame3d.run_frame3d(m)
    # 挠度不受开孔影响（刚度按全截面）
    assert res["max_disp_mm"] == pytest.approx(49.0 * L ** 3 / (3.0 * E * IY), rel=REL)
    mem = res["members"][0]
    assert len(mem["holes"]) == 1
    assert mem["holes"][0]["d_mm"] == pytest.approx(20.0)
    assert mem["A_net_mm2"] == pytest.approx(B * H - B * 20.0, rel=1e-9)
    iy_net = IY - B * 20.0 ** 3 / 12.0
    wy_net = iy_net / (H / 2.0)
    expect = 49.0 * L / wy_net * frame3d.HOLE_KT["circle"]
    assert mem["sigma_MPa"] == pytest.approx(expect, rel=REL)
    assert mem["hole_kt"] == pytest.approx(frame3d.HOLE_KT["circle"])
    assert any("开孔" in s for s in res["narrative"])


def test_member_hole_invalid_cases():
    """开孔参数越界/非贯穿孔给出明确错误；空心截面开孔按两侧壁扣除。"""
    base_nodes = [{"id": 0, "x": 0, "y": 0, "z": 0},
                  {"id": 1, "x": L, "y": 0, "z": 0}]
    base_sup = [{"node": 0, "type": "fixed"}]
    base_load = [{"type": "force", "node": 1, "fz": -49.0}]

    for holes, match in (
        ([{"at_mm": L / 2.0, "d_mm": H}], "不小于截面高度"),
        ([{"at_mm": L + 10.0, "d_mm": 10.0}], "超出杆长"),
        ([{"at_mm": L / 2.0, "d_mm": 10.0, "shape": "star"}], "不支持"),
        ([{"at_mm": L / 2.0, "d_mm": 10.0, "through": False}], "贯穿孔"),
    ):
        m = _model(base_nodes, [dict(_member(0, 1), holes=holes)],
                   base_sup, base_load, dim=3)
        with pytest.raises(ValueError, match=match):
            frame3d.run_frame3d(m)

    # 孔心正好落在分段节点的杆端（AI 常犯）→ 自动微调进杆件内，不再报错
    at_end = _model(base_nodes,
                    [dict(_member(0, 1), holes=[
                        {"at_mm": L, "shape": "circle", "d_mm": 10.0}
                    ])],
                    base_sup, base_load, dim=3)
    res_end = frame3d.run_frame3d(at_end)
    assert res_end["members"][0]["holes"][0]["at_mm"] == pytest.approx(L - 5.0)

    # 空心截面（壁厚 5 mm）开孔：孔贯穿两侧壁，净面积扣 2·t·d
    hollow = _model(base_nodes,
                    [dict(_member(0, 1), wall_t=5.0,
                          holes=[{"at_mm": L / 2.0, "d_mm": 10.0}])],
                    base_sup, base_load, dim=3)
    res = frame3d.run_frame3d(hollow)
    mem = res["members"][0]
    assert len(mem["holes"]) == 1
    assert mem["A_net_mm2"] == pytest.approx(B * H - (B - 10.0) * (H - 10.0)
                                             - 2.0 * 5.0 * 10.0, rel=1e-9)
    assert mem["hole_kt"] == pytest.approx(frame3d.HOLE_KT["circle"])


# ===================================================================== 型钢截面库
# 国标 GB/T 706-2016 工字钢 20a：A=3557.8mm²，强轴 Ix=2.37e7mm⁴（→局部 y），
# 弱轴 Iy=1.58e6mm⁴（→局部 z），Wx=2.37e5mm³，Wy=3.15e4mm³，J≈1.19e5mm⁴。
# 强/弱轴惯性矩比 Ix/Iy = 15.0。

# --------------------------------------------------------------------- T19
def test_steel_i20a_library_override_dual_axis():
    """库值覆盖几何计算：Iy=Ix(强轴)、Iz=Iy(弱轴)、Wy/Wz 取库截面模量、J 取库值。"""
    model = _model(
        [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": 1000, "y": 0, "z": 0}],
        [{"id": 1, "i": 0, "j": 1, "width_b": 100, "depth_h": 200,
          "material": "工字钢20a"}],
        [{"node": 0, "type": "fixed"}],
        [{"type": "force", "node": 1, "fz": -1.0}],
        dim=3,
    )
    mem = frame3d._validate(model)["members"][0]
    assert mem["A"] == pytest.approx(3557.8, rel=1e-9)
    assert mem["Iy"] == pytest.approx(23700000.0, rel=1e-9)   # 强轴 → 局部 y
    assert mem["Iz"] == pytest.approx(1580000.0, rel=1e-9)    # 弱轴 → 局部 z
    assert mem["Iy"] / mem["Iz"] == pytest.approx(15.0, rel=1e-9)
    assert mem["Wy"] == pytest.approx(237000.0, rel=1e-9)
    assert mem["Wz"] == pytest.approx(31500.0, rel=1e-9)
    # J 取开口薄壁近似 1.19e5，而不是旧近似 2Ix≈4.74e7
    assert mem["J"] == pytest.approx(119029.47, rel=1e-3)
    assert mem["J"] < 2.0 * 23700000.0 / 300.0


# --------------------------------------------------------------------- T20
def test_steel_i20a_cantilever_strong_weak_ratio():
    """20a 悬臂：弱轴挠度 / 强轴挠度 = Ix/Iy = 15.0（证明未强行等轴 Iy=Iz）。"""
    L, P, E = 2000.0, 1000.0, 206000.0
    Ix, Iy = 23700000.0, 1580000.0
    nodes = [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": L, "y": 0, "z": 0}]
    member = [{"id": 1, "i": 0, "j": 1, "width_b": 100, "depth_h": 200,
               "material": "工字钢20a"}]
    sup = [{"node": 0, "type": "fixed"}]
    d_s = frame3d.run_frame3d(
        _model(nodes, member, sup, [{"type": "force", "node": 1, "fz": -P}], dim=3)
    )["max_disp_mm"]
    d_w = frame3d.run_frame3d(
        _model(nodes, member, sup, [{"type": "force", "node": 1, "fy": -P}], dim=3)
    )["max_disp_mm"]
    assert d_s == pytest.approx(P * L ** 3 / (3.0 * E * Ix), rel=2e-3)
    assert d_w == pytest.approx(P * L ** 3 / (3.0 * E * Iy), rel=2e-3)
    assert d_w / d_s == pytest.approx(Ix / Iy, rel=1e-6)
    assert d_w / d_s == pytest.approx(15.0, rel=1e-6)


# --------------------------------------------------------------------- T21
def test_steel_i20a_simply_supported_udl():
    """20a 简支梁满跨 UDL：跨中挠度 5wL⁴/(384EIx)，误差 ≤5%。"""
    L, w, E, Ix = 2000.0, 1.0, 206000.0, 23700000.0
    m = _model(
        [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": L, "y": 0, "z": 0}],
        [{"id": 1, "i": 0, "j": 1, "width_b": 100, "depth_h": 200,
          "material": "工字钢20a"}],
        [{"node": 0, "type": "pinned", "fix": ["rx"]},
         {"node": 1, "type": "pinned"}],
        [{"type": "udl", "member": 1, "qz": -w}],
        dim=3,
    )
    res = frame3d.run_frame3d(m)
    assert res["max_disp_mm"] == pytest.approx(
        5.0 * w * L ** 4 / (384.0 * E * Ix), rel=5e-3)


# --------------------------------------------------------------------- T22
def test_steel_i20a_torsion_library_j():
    """20a 悬臂受扭：φ=TL/(GJ) 用库 J≈1.19e5；若误用 2Ix≈4.74e7 会小约 400 倍。"""
    L, T = 2000.0, 1.0e6
    G = 206000.0 / (2.0 * 1.3)
    J = 119029.47
    m = _model(
        [{"id": 0, "x": 0, "y": 0, "z": 0}, {"id": 1, "x": L, "y": 0, "z": 0}],
        [{"id": 1, "i": 0, "j": 1, "width_b": 100, "depth_h": 200,
          "material": "工字钢20a"}],
        [{"node": 0, "type": "fixed"}],
        [{"type": "force", "node": 1, "mx": T}],
        dim=3,
    )
    res = frame3d.run_frame3d(m)
    assert abs(res["node_disp"][1]["rx"]) == pytest.approx(T * L / (G * J), rel=2e-3)


# --------------------------------------------------------------------- T23
def test_steel_profile_alias_resolution():
    """find_material 对工字钢/H型钢/角钢的各种别名（含 × 与 x、∠ 与 L）均命中。"""
    from pocketsim.materials import load_library
    lib = load_library()
    for q in ["工字钢20a", "I20a", "20a", "20a工字钢", "工20a"]:
        assert lib.find_material(q) is not None, q
    for q in ["HN400×200", "HN400x200", "H型钢HW200×200", "HM300x200"]:
        assert lib.find_material(q) is not None, q
    for q in ["角钢L50×5", "L50×5", "L50x5", "∠50×5", "等边角钢L50×5"]:
        assert lib.find_material(q) is not None, q
    rec = lib.find_material("工字钢20a")
    assert rec["inertia_x_mm4"] == 23700000.0
    assert rec["inertia_y_mm4"] == 1580000.0
    assert rec["torsion_constant_mm4"] == pytest.approx(119029.47, rel=1e-3)


# ------------------------------------------------ 面荷载（荷载类型自动识别）
def test_area_load_distributed_to_supporting_members():
    """椅子坐面：600 N 面荷载传给 2 根坐面横杆 → 每根 q=0.75 N/mm（均布，不是集中力）。"""
    span = 400.0
    nodes = [
        {"id": 1, "x": 0, "y": 0, "z": 0}, {"id": 2, "x": span, "y": 0, "z": 0},
        {"id": 3, "x": 0, "y": 400, "z": 0}, {"id": 4, "x": span, "y": 400, "z": 0},
    ]
    members = [
        {"id": 1, "i": 1, "j": 2, "width_b": B, "depth_h": H, "material": "低碳钢"},
        {"id": 2, "i": 3, "j": 4, "width_b": B, "depth_h": H, "material": "低碳钢"},
    ]
    supports = [
        {"node": 1, "type": "pinned", "fix": ["rx"]}, {"node": 2, "type": "pinned"},
        {"node": 3, "type": "pinned", "fix": ["rx"]}, {"node": 4, "type": "pinned"},
    ]
    m = _model(nodes, members, supports,
               [{"type": "area", "members": [1, 2], "total_N": -600.0}], dim=3)
    res = frame3d.run_frame3d(m)

    q = 600.0 / (2.0 * span)
    for mem in res["members"]:
        assert mem["My_max_Nmm"] == pytest.approx(q * span ** 2 / 8.0, rel=5e-3)
    delta = 5.0 * q * span ** 4 / (384.0 * E * IY)
    assert res["max_disp_mm"] == pytest.approx(delta, rel=5e-3)


def test_area_load_not_equivalent_to_midspan_point_load():
    """同总重若误建成跨中集中力，弯矩是均布工况的 2 倍——用于确认面荷载没有被折算成集中力。"""
    span = 400.0
    q = 600.0 / span
    area_nodes = [
        {"id": 1, "x": 0, "y": 0, "z": 0}, {"id": 2, "x": span, "y": 0, "z": 0},
    ]
    area_members = [{"id": 1, "i": 1, "j": 2, "width_b": B, "depth_h": H,
                     "material": "低碳钢"}]
    supp_area = [{"node": 1, "type": "pinned", "fix": ["rx"]},
                 {"node": 2, "type": "pinned"}]
    res_area = frame3d.run_frame3d(_model(
        area_nodes, area_members, supp_area,
        [{"type": "area", "members": [1], "total_N": -600.0}], dim=3))

    pt_nodes = [{"id": 1, "x": 0, "y": 0, "z": 0},
                {"id": 2, "x": span, "y": 0, "z": 0},
                {"id": 3, "x": span / 2.0, "y": 0, "z": 0}]
    pt_members = [
        {"id": 1, "i": 1, "j": 3, "width_b": B, "depth_h": H, "material": "低碳钢"},
        {"id": 2, "i": 3, "j": 2, "width_b": B, "depth_h": H, "material": "低碳钢"},
    ]
    res_pt = frame3d.run_frame3d(_model(
        pt_nodes, pt_members, supp_area,
        [{"type": "force", "node": 3, "fz": -600.0}], dim=3))

    m_area = max(mem["My_max_Nmm"] for mem in res_area["members"])
    m_pt = max(mem["My_max_Nmm"] for mem in res_pt["members"])
    assert m_area == pytest.approx(q * span ** 2 / 8.0, rel=5e-3)
    # 同总重：跨中集中力 M=PL/4，均布 M=PL/8，集中力弯矩是均布的 2 倍
    assert m_pt == pytest.approx(m_area * 2.0, rel=1e-2)


def test_area_load_requires_members_and_total():
    """面荷载缺 members 或 total_N=0 → 明确报错，不退化成集中力。"""
    nodes = [{"id": 1, "x": 0, "y": 0, "z": 0}, {"id": 2, "x": L, "y": 0, "z": 0}]
    members = [_member(1, 2)]
    supports = [{"node": 1, "type": "fixed"}]
    for bad in ([{"type": "area", "total_N": -100.0}],
                [{"type": "area", "members": [1], "total_N": 0.0}],
                [{"type": "area", "members": [99], "total_N": -100.0}]):
        with pytest.raises(ValueError):
            frame3d.run_frame3d(_model(nodes, members, supports, bad, dim=3))


def test_chair_seat_area_load_end_to_end():
    """空间杆系座椅端到端：784 N 坐面荷载分给 2 根坐面横杆，再经 4 条腿传至地面。

    断言：① 没有为面荷载新增跨中节点（未被折算成集中力）；
    ② 四条腿轴力各 -196 N、合计 784 N，与面荷载总重平衡；
    ③ 横杆按均布受弯，弯矩落在两端嵌固 qL²/24 与简支 qL²/8 的包络之间。
    """
    half, height = 200.0, 450.0
    corners = [(-half, -half), (half, -half), (half, half), (-half, half)]
    nodes = [{"id": k + 1, "x": x, "y": y, "z": 0.0} for k, (x, y) in enumerate(corners)]
    nodes += [{"id": k + 5, "x": x, "y": y, "z": height} for k, (x, y) in enumerate(corners)]
    members = [{"id": k + 1, "i": k + 1, "j": k + 5, "width_b": 40.0, "depth_h": 40.0,
                "material": "低碳钢"} for k in range(4)]
    # 坐面横杆：y=-200 由节点 5→6，y=+200 由节点 8→7（均沿 X 跨 400 mm）
    members += [
        {"id": 5, "i": 5, "j": 6, "width_b": 40.0, "depth_h": 80.0, "material": "低碳钢"},
        {"id": 6, "i": 8, "j": 7, "width_b": 40.0, "depth_h": 80.0, "material": "低碳钢"},
    ]
    total = -784.0
    m = _model(nodes, members,
               [{"node": k + 1, "type": "fixed"} for k in range(4)],
               [{"type": "area", "members": [5, 6], "total_N": total}], dim=3)
    res = frame3d.run_frame3d(m)

    # ① 仍只有 8 个节点：面荷载没有被拆成"跨中新增节点 + 集中力"
    assert len(res["node_disp"]) == len(nodes)

    # ② 传力路径完整：两条横杆各 392 N，四腿各 196 N 受压
    legs = res["members"][:4]
    for mem in legs:
        assert mem["N_N"] == pytest.approx(-196.0, rel=1e-3)
    assert sum(mem["N_N"] for mem in legs) == pytest.approx(total, rel=1e-3)

    # ③ 横杆均布 q = 784/800 = 0.98 N/mm
    q = 784.0 / (2.0 * 2.0 * half)
    lo = q * (2.0 * half) ** 2 / 24.0
    hi = q * (2.0 * half) ** 2 / 8.0
    for mem in res["members"][4:]:
        assert lo * 0.95 <= mem["My_max_Nmm"] <= hi * 1.05
    assert res["max_disp_mm"] > 0.0


def test_area_load_unequal_members_and_duplicate_ids():
    """不等长承托杆件按真实长度分配；members 重复 id 去重，不重复叠加均布值。"""
    l1, l2 = 400.0, 600.0
    nodes = [{"id": 1, "x": 0, "y": 0, "z": 0}, {"id": 2, "x": l1, "y": 0, "z": 0},
             {"id": 3, "x": 0, "y": 300, "z": 0}, {"id": 4, "x": l2, "y": 300, "z": 0}]
    members = [
        {"id": 1, "i": 1, "j": 2, "width_b": B, "depth_h": H, "material": "低碳钢"},
        {"id": 2, "i": 3, "j": 4, "width_b": B, "depth_h": H, "material": "低碳钢"},
    ]
    supports = [{"node": 1, "type": "pinned", "fix": ["rx"]}, {"node": 2, "type": "pinned"},
                {"node": 3, "type": "pinned", "fix": ["rx"]}, {"node": 4, "type": "pinned"}]
    total = 1000.0
    q = total / (l1 + l2)          # 去重后跨度 1000 mm
    res = frame3d.run_frame3d(_model(
        nodes, members, supports,
        [{"type": "面荷载", "members": [1, 1, 2], "total_N": -total}], dim=3))

    for mem, span in zip(res["members"], (l1, l2)):
        assert mem["My_max_Nmm"] == pytest.approx(q * span ** 2 / 8.0, rel=5e-3)


def test_area_load_with_point_force_superposition():
    """面荷载与集中力可共存：跨中弯矩 = 均布分量 qL²/8 + 集中力分量 PL/4。"""
    span, q, P = 400.0, 0.5, 100.0
    nodes = [{"id": 1, "x": 0, "y": 0, "z": 0}, {"id": 2, "x": span, "y": 0, "z": 0},
             {"id": 3, "x": span / 2.0, "y": 0, "z": 0}]
    members = [
        {"id": 1, "i": 1, "j": 3, "width_b": B, "depth_h": H, "material": "低碳钢"},
        {"id": 2, "i": 3, "j": 2, "width_b": B, "depth_h": H, "material": "低碳钢"},
    ]
    supports = [{"node": 1, "type": "pinned", "fix": ["rx"]},
                {"node": 2, "type": "pinned"}]
    res = frame3d.run_frame3d(_model(
        nodes, members, supports,
        [{"type": "area", "members": [1, 2], "total_N": -(q * span)},
         {"type": "force", "node": 3, "fz": -P}], dim=3))

    expect = q * span ** 2 / 8.0 + P * span / 4.0
    assert max(mem["My_max_Nmm"] for mem in res["members"]) == pytest.approx(
        expect, rel=5e-3)
