"""frame.py 理论解验证测试。

全部期望值来自材料力学教科书闭式解（欧拉-伯努利梁）：
    悬臂集中力  δ=PL³/3EI      M=PL
    悬臂均布    δ=wL⁴/8EI      M=wL²/2
    简支跨中力  δ=PL³/48EI     M=PL/4
    简支均布    δ=5wL⁴/384EI   M=wL²/8
材料统一用松木（E=10000 MPa、抗弯强度 13 MPa，来自 materials.json 代表值）。
"""
import math

import pytest

from pocketsim.frame import run_frame

# 通用矩形截面：b=25, h=40 → A=1000 mm², I=25·40³/12=133333.33 mm⁴
B, H = 25.0, 40.0
AREA = B * H
INERTIA = B * H ** 3 / 12.0
E = 10000.0


def _cantilever(load=None, length=300.0):
    model = {
        "name": "悬臂",
        "nodes": [{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": length, "y": 0}],
        "members": [{"id": 0, "i": 0, "j": 1, "width_b": B, "depth_h": H,
                     "material": "松木"}],
        "supports": [{"node": 0, "type": "fixed"}],
        "loads": load or [],
    }
    return run_frame(model)


# --------------------------------------------------------------------- T1
def test_cantilever_tip_force():
    """端部 49N（5kg）集中力：δ=PL³/3EI=0.3308mm，M=PL=14700N·mm。"""
    res = _cantilever([{"type": "force", "node": 1, "fx": 0, "fy": -49.0}])
    delta = 49 * 300 ** 3 / (3 * E * INERTIA)
    assert res["max_disp_mm"] == pytest.approx(delta, rel=2e-3)
    m = res["members"][0]
    assert m["M_max_Nmm"] == pytest.approx(49 * 300, rel=2e-3)
    sigma = 49 * 300 * (H / 2) / INERTIA
    assert m["sigma_MPa"] == pytest.approx(sigma, rel=2e-3)
    assert res["safety_factor"] == pytest.approx(13.0 / sigma, rel=2e-3)
    assert res["verdict"] == "安全"


# --------------------------------------------------------------------- T2
def test_cantilever_udl():
    """均布 0.2N/mm：δ=wL⁴/8EI=0.1519mm，M=wL²/2=9000N·mm。"""
    res = _cantilever([{"type": "udl", "member": 0, "qy": -0.2}])
    delta = 0.2 * 300 ** 4 / (8 * E * INERTIA)
    assert res["max_disp_mm"] == pytest.approx(delta, rel=2e-3)
    m = res["members"][0]
    assert m["M_max_Nmm"] == pytest.approx(0.2 * 300 ** 2 / 2, rel=2e-3)


# --------------------------------------------------------------------- T2b
def test_zero_udl_is_ignored():
    """qy=0 是 AI 常见的无载荷占位：应忽略，而非报错；有效载荷照常计算。"""
    res = _cantilever([
        {"type": "udl", "member": 0, "qy": 0.0},
        {"type": "force", "node": 1, "fx": 0, "fy": -49.0},
    ])
    delta = 49 * 300 ** 3 / (3 * E * INERTIA)
    assert res["max_disp_mm"] == pytest.approx(delta, rel=2e-3)


def test_all_zero_loads_rejected():
    with pytest.raises(ValueError, match="没有有效载荷"):
        _cantilever([{"type": "udl", "member": 0, "qy": 0.0}])


# --------------------------------------------------------------------- T2c
def test_wind_load_matches_manual_equivalent_force():
    """type=wind 源字段（P1-5）：代码侧按 F=cp·½ρv²·A 换算并均摊，
    与手填等效集中力 fx 结果一致；beaufort 按蒲福表换算同一公式。"""
    v, area, cp = 20.0, 0.06, 1.2
    total = cp * 0.5 * 1.225 * v * v * area
    wind = _cantilever([{"type": "wind", "nodes": [1], "wind_speed_m_s": v,
                         "dir": "x", "area_m2": area}])
    manual = _cantilever([{"type": "force", "node": 1, "fx": total, "fy": 0.0}])
    assert wind["max_disp_mm"] == pytest.approx(manual["max_disp_mm"], rel=1e-9)
    # 蒲福 8 级 = 20.7 m/s（flow.speed_from_beaufort 表值）
    v8 = 20.7
    b8 = _cantilever([{"type": "wind", "nodes": [1], "beaufort": 8,
                       "dir": "x", "area_m2": area}])
    manual8 = _cantilever([{"type": "force", "node": 1,
                            "fx": cp * 0.5 * 1.225 * v8 * v8 * area,
                            "fy": 0.0}])
    assert b8["max_disp_mm"] == pytest.approx(manual8["max_disp_mm"], rel=1e-9)


def test_wind_load_field_validation():
    """风载字段校验：风速来源二选一 / 面积必填 / 节点存在性，全部硬拒绝。"""
    with pytest.raises(ValueError, match="二选一"):
        _cantilever([{"type": "wind", "nodes": [1], "wind_speed_m_s": 20,
                      "beaufort": 8, "area_m2": 0.06}])
    with pytest.raises(ValueError, match="缺少风速来源"):
        _cantilever([{"type": "wind", "nodes": [1], "area_m2": 0.06}])
    with pytest.raises(ValueError, match="area_m2"):
        _cantilever([{"type": "wind", "nodes": [1], "wind_speed_m_s": 20}])
    with pytest.raises(ValueError, match="不存在的节点"):
        _cantilever([{"type": "wind", "nodes": [7], "wind_speed_m_s": 20,
                      "area_m2": 0.06}])
    with pytest.raises(ValueError, match="nodes"):
        _cantilever([{"type": "wind", "wind_speed_m_s": 20, "area_m2": 0.06}])


# --------------------------------------------------------------------- T3
def test_simply_supported_mid_force():
    """简支跨中 98N（10kg），板截面 200×18：δ=PL³/48EI=1.531mm，M=PL/4。"""
    model = {
        "nodes": [{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": 450, "y": 0},
                  {"id": 2, "x": 900, "y": 0}],
        "members": [
            {"id": 0, "i": 0, "j": 1, "width_b": 200, "depth_h": 18, "material": "松木"},
            {"id": 1, "i": 1, "j": 2, "width_b": 200, "depth_h": 18, "material": "松木"},
        ],
        "supports": [{"node": 0, "type": "pinned"}, {"node": 2, "type": "pinned"}],
        "loads": [{"type": "force", "node": 1, "fx": 0, "fy": -98.0}],
    }
    res = run_frame(model)
    I = 200 * 18 ** 3 / 12
    delta = 98 * 900 ** 3 / (48 * E * I)
    assert res["max_disp_mm"] == pytest.approx(delta, rel=2e-3)
    # 两杆在跨中节点处杆端弯矩均为 PL/4 = 22050 N·mm
    mid_moment = 98 * 900 / 4
    assert max(mm["M_max_Nmm"] for mm in res["members"]) == pytest.approx(
        mid_moment, rel=2e-3)
    # 固定端节点位移为零
    nd = res["node_disp"][0]
    assert abs(nd["dx"]) < 1e-12 and abs(nd["dy"]) < 1e-12


# --------------------------------------------------------------------- T4
def test_simply_supported_udl():
    """简支均布 1N/mm：δ=5wL⁴/384EI=0.0791mm，M=wL²/8=11250N·mm。"""
    model = {
        "nodes": [{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": 300, "y": 0}],
        "members": [{"id": 0, "i": 0, "j": 1, "width_b": B, "depth_h": H,
                     "material": "松木"}],
        "supports": [{"node": 0, "type": "pinned"}, {"node": 1, "type": "pinned"}],
        "loads": [{"type": "udl", "member": 0, "qy": -1.0}],
    }
    res = run_frame(model)
    delta = 5 * 1.0 * 300 ** 4 / (384 * E * INERTIA)
    assert res["max_disp_mm"] == pytest.approx(delta, rel=2e-3)
    m = res["members"][0]
    assert m["M_max_Nmm"] == pytest.approx(1.0 * 300 ** 2 / 8, rel=2e-3)


# --------------------------------------------------------------------- T5
def test_inclined_member_transform():
    """45° 斜悬臂 + 垂直杆轴的集中力：转换矩阵正确性（δ=PL³/3EI）。"""
    s = c = math.sqrt(0.5)
    L = 300
    tipx, tipy = L * c, L * s
    model = {
        "nodes": [{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": tipx, "y": tipy}],
        "members": [{"id": 0, "i": 0, "j": 1, "width_b": B, "depth_h": H,
                     "material": "松木"}],
        "supports": [{"node": 0, "type": "fixed"}],
        # 垂直于杆轴（局部 +y = 全局 (-s,c)）的 49N
        "loads": [{"type": "force", "node": 1, "fx": -49 * s, "fy": 49 * c}],
    }
    res = run_frame(model)
    delta = 49 * L ** 3 / (3 * E * INERTIA)
    assert res["max_disp_mm"] == pytest.approx(delta, rel=2e-3)


# --------------------------------------------------------------------- T6
def test_l_frame_sanity():
    """L 形框架（书架简化）：固定端零位移、变形有限、应力大于纯悬臂情形。"""
    model = {
        "name": "L形书架",
        "nodes": [{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": 0, "y": 400},
                  {"id": 2, "x": 300, "y": 400}],
        "members": [
            {"id": 0, "i": 0, "j": 1, "width_b": 30, "depth_h": 40, "material": "松木"},
            {"id": 1, "i": 1, "j": 2, "width_b": 30, "depth_h": 40, "material": "松木"},
        ],
        "supports": [{"node": 0, "type": "fixed"}],
        "loads": [{"type": "force", "node": 2, "fx": 0, "fy": -49.0}],
    }
    res = run_frame(model)
    nd = res["node_disp"][0]
    assert abs(nd["dx"]) < 1e-10 and abs(nd["dy"]) < 1e-10
    assert 0 < res["max_disp_mm"] < 50
    assert res["max_stress_MPa"] > 0
    assert res["n_nodes"] == 3 and res["n_members"] == 2
    assert len(res["narrative"]) >= 5
    # 平衡：竖杆弯矩在根部最大（悬臂效应）
    assert res["governing_member"] == 0


# --------------------------------------------------------------------- T7
def test_buckling_flag_on_slender_strut():
    """细长受压杆：强度安全但欧拉利用率超 50% 时必须给出失稳提示。"""
    def _run(force_n):
        return run_frame({
            "nodes": [{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": 0, "y": 1000}],
            "members": [{"id": 0, "i": 0, "j": 1, "width_b": 20, "depth_h": 20,
                         "material": "低碳钢"}],
            # 底部固定 + 顶端自由受压（若顶端再加支座，力会直接传给支座，杆内无轴力）
            "supports": [{"node": 0, "type": "fixed"}],
            "loads": [{"type": "force", "node": 1, "fx": 0, "fy": -force_n}],
        })
    # P_cr = π²EI/L² ≈ 27137N；5000N → 利用率 18%，不提示
    res_low = _run(5000)
    assert res_low["buckling_note"] == ""
    # 15000N → 利用率 55%，提示失稳；强度仍安全（σ=37.5MPa << 235MPa）
    res_high = _run(15000)
    assert res_high["buckling_ratio"] == pytest.approx(
        15000 / (math.pi ** 2 * 206000 * (20 * 20 ** 3 / 12) / 1000 ** 2), rel=2e-3)
    assert res_high["buckling_note"] != ""
    assert res_high["verdict"] == "安全"  # 强度够，但应看到稳定性提醒
    assert "失稳" in res_high["narrative"][-2] + res_high["buckling_note"]


# --------------------------------------------------------------------- T8
def test_error_cases():
    base = {
        "nodes": [{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": 100, "y": 0}],
        "members": [{"id": 0, "i": 0, "j": 1, "width_b": 10, "depth_h": 10,
                     "material": "松木"}],
        "supports": [{"node": 0, "type": "fixed"}],
        "loads": [{"type": "force", "node": 1, "fx": 0, "fy": -10}],
    }
    # 无支座
    with pytest.raises(ValueError, match="支座"):
        run_frame({**base, "supports": []})
    # 无载荷
    with pytest.raises(ValueError, match="载荷"):
        run_frame({**base, "loads": []})
    # 材料不存在
    with pytest.raises(ValueError, match="材料库中没有"):
        run_frame({**base,
                   "members": [{**base["members"][0], "material": "振金"}]})
    # 零长度杆
    with pytest.raises(ValueError, match="长度为 0"):
        run_frame({**base,
                   "nodes": [{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": 0, "y": 0}]})
    # 缺截面
    with pytest.raises(ValueError, match="截面"):
        run_frame({**base, "members": [
            {"id": 0, "i": 0, "j": 1, "material": "松木"}]})
    # 约束不足（机构）
    with pytest.raises(ValueError):
        run_frame({**base, "supports": [{"node": 0, "type": "roller"}]})


# --------------------------------------------------------------------- T9 圆管截面
def _simply_supported_tube(D=48.0, t=3.5, P=49.0, L=1000.0, material="低碳钢"):
    return run_frame({
        "name": "简支圆管",
        "nodes": [{"id": 0, "x": 0, "y": 0},
                  {"id": 1, "x": L / 2, "y": 0},
                  {"id": 2, "x": L, "y": 0}],
        "members": [
            {"id": 0, "i": 0, "j": 1, "shape": "circle",
             "outer_diameter_mm": D, "wall_t": t, "material": material},
            {"id": 1, "i": 1, "j": 2, "shape": "circle",
             "outer_diameter_mm": D, "wall_t": t, "material": material},
        ],
        "supports": [{"node": 0, "type": "pinned"}, {"node": 2, "type": "roller"}],
        "loads": [{"type": "force", "node": 1, "fx": 0, "fy": -P}],
    })


def test_circle_tube_section_matches_beam_theory():
    """⌀48×3.5 钢管简支跨中 49N：I=π(D⁴−d⁴)/64，δ=PL³/48EI。"""
    D, t, L, P = 48.0, 3.5, 1000.0, 49.0
    res = _simply_supported_tube(D=D, t=t, P=P, L=L)
    I = math.pi * (D ** 4 - (D - 2 * t) ** 4) / 64.0
    E = 206000.0   # 低碳钢（materials.json 代表值）
    delta = P * L ** 3 / (48.0 * E * I)
    sigma = (P * L / 4.0) * (D / 2.0) / I
    assert res["max_disp_mm"] == pytest.approx(delta, rel=2e-3)
    assert res["members"][0]["sigma_MPa"] == pytest.approx(sigma, rel=2e-3)
    assert res["members"][0]["section"] == "⌀48×3.5 mm（圆管）"


def test_solid_circle_section():
    """⌀50 实心圆钢（wall_t=0）：I=πD⁴/64，与闭式解一致。"""
    D = 50.0
    res = _simply_supported_tube(D=D, t=0.0)
    I = math.pi * D ** 4 / 64.0
    delta = 49.0 * 1000.0 ** 3 / (48.0 * 206000.0 * I)
    assert res["max_disp_mm"] == pytest.approx(delta, rel=2e-3)
    assert res["members"][0]["section"] == "⌀50 mm（实心圆）"


def test_circle_section_requires_diameter_and_rejects_holes():
    base = {
        "nodes": [{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": 100, "y": 0}],
        "members": [{"id": 0, "i": 0, "j": 1, "shape": "circle",
                     "material": "低碳钢"}],
        "supports": [{"node": 0, "type": "fixed"}],
        "loads": [{"type": "force", "node": 1, "fx": 0, "fy": -10}],
    }
    with pytest.raises(ValueError, match="外径"):
        run_frame(base)
    with pytest.raises(ValueError, match="开孔"):
        run_frame({**base, "members": [{**base["members"][0],
                                       "outer_diameter_mm": 40.0,
                                       "holes": [{"at_mm": 50, "shape": "circle",
                                                  "d_mm": 10, "through": True}]}]})


# ================================================================= 热分析
# Q235: E=206000 MPa, α=1.2e-5 /K；松木: E=10000 MPa, α=5e-6 /K
# PVC: α=8e-5 /K, 耐温 60°C

def _bar(material, supports, loads, length=1000.0):
    return run_frame({
        "nodes": [{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": length, "y": 0}],
        "members": [{"id": 0, "i": 0, "j": 1, "width_b": 10, "depth_h": 10,
                     "material": material}],
        "supports": supports,
        "loads": loads,
    })


# --------------------------------------------------------------------- T9
def test_thermal_fixed_bar_heating():
    """两端固支杆升温 40°C：位移恒为 0，热压应力 σ = E·α·ΔT。"""
    res = _bar("Q235低碳钢",
               [{"node": 0, "type": "fixed"}, {"node": 1, "type": "fixed"}],
               [{"type": "thermal", "member": 0, "delta_temp": 40.0}])
    # 两端位移均为 0
    assert res["node_disp"][0]["dx"] == pytest.approx(0.0, abs=1e-9)
    assert res["node_disp"][1]["dx"] == pytest.approx(0.0, abs=1e-9)
    # 热应力幅值 |σ| = E·α·ΔT = 206000·1.2e-5·40 = 98.88 MPa
    sigma_th = 206000.0 * 1.2e-5 * 40.0
    assert res["members"][0]["sigma_MPa"] == pytest.approx(sigma_th, rel=1e-3)
    # 轴力 N = -σ·A = -9888 N（受压为负）
    assert res["members"][0]["N_N"] == pytest.approx(-sigma_th * 100, rel=1e-3)
    # thermal 分区正确
    assert res["thermal"]["has_thermal"] is True
    assert res["thermal"]["max_delta_temp"] == pytest.approx(40.0)
    assert res["thermal"]["over_temp"] is False  # 60°C << 300°C


# --------------------------------------------------------------------- T10
def test_thermal_cantilever_free_expansion():
    """悬臂自由杆升温 40°C：自由伸长 ΔL = α·L·ΔT，应力为 0。"""
    res = _bar("松木",
               [{"node": 0, "type": "fixed"}],
               [{"type": "thermal", "member": 0, "delta_temp": 40.0}])
    # 自由端伸长 ΔL = α·L·ΔT = 5e-6·1000·40 = 0.2 mm
    delta_L = 5e-6 * 1000.0 * 40.0
    assert res["node_disp"][1]["dx"] == pytest.approx(delta_L, rel=1e-3)
    # 无约束 → 无热应力
    assert res["members"][0]["sigma_MPa"] == pytest.approx(0.0, abs=1e-6)
    assert res["members"][0]["N_N"] == pytest.approx(0.0, abs=1e-6)


# --------------------------------------------------------------------- T11
def test_thermal_over_temp_pvc():
    """PVC 杆升温 60°C（杆温 80°C > 耐温 60°C）→ verdict=危险。"""
    res = _bar("PVC",
               [{"node": 0, "type": "fixed"}, {"node": 1, "type": "fixed"}],
               [{"type": "thermal", "member": 0, "delta_temp": 60.0}])
    assert res["thermal"]["over_temp"] is True
    assert res["verdict"] == "危险"
    assert "80" in res["thermal"]["note"]
    assert "60" in res["thermal"]["note"]


# --------------------------------------------------------------------- T12
def test_thermal_delta_temp_zero_rejected():
    """delta_temp 为 0 应报错。"""
    with pytest.raises(ValueError, match="温度"):
        _bar("Q235低碳钢",
             [{"node": 0, "type": "fixed"}],
             [{"type": "thermal", "member": 0, "delta_temp": 0.0}])


# --------------------------------------------------------------------- T13
def test_hollow_section_cantilever():
    """空心方管 25×40×3：A=354、I=71102，挠度按空心 I 计算（实心的 1.9 倍）。"""
    b, h, t = 25.0, 40.0, 3.0
    area = b * h - (b - 2 * t) * (h - 2 * t)
    inertia = (b * h ** 3 - (b - 2 * t) * (h - 2 * t) ** 3) / 12.0
    res = run_frame({
        "name": "空心悬臂",
        "nodes": [{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": 300, "y": 0}],
        "members": [{"id": 0, "i": 0, "j": 1, "width_b": b, "depth_h": h,
                     "wall_t": t, "material": "松木"}],
        "supports": [{"node": 0, "type": "fixed"}],
        "loads": [{"type": "force", "node": 1, "fx": 0, "fy": -49.0}],
    })
    assert area == pytest.approx(354.0, rel=1e-9)
    assert inertia == pytest.approx(71102.0, rel=2e-3)
    delta = 49 * 300 ** 3 / (3 * E * inertia)
    assert res["max_disp_mm"] == pytest.approx(delta, rel=2e-3)
    assert res["members"][0]["section"] == "25×40×3 mm（空心）"
    assert res["members"][0]["sigma_MPa"] == pytest.approx(
        49 * 300 * (h / 2) / inertia, rel=2e-3)


def test_hair_analogy_handles_tiny_and_normal_deflection():
    """极小变形不再显示"约 0.0 根头发丝"（真机四腿桌 0.003mm 暴露的问题）。"""
    from pocketsim.interpret import hair_analogy

    assert hair_analogy(0.003).startswith("不足一根头发丝直径")
    assert hair_analogy(0.0) == "几乎为零，肉眼完全看不出来"
    assert hair_analogy(6.806).startswith("约 97.2 根")


# --------------------------------------------------------------------- T14
def test_mechanism_detected_with_node_and_direction():
    """只有滚轴支座（水平可滑）的简支梁是机构（P3D-5）：报中文错并定位节点/方向。"""
    model = {
        "name": "机构",
        "nodes": [{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": 600, "y": 0}],
        "members": [{"id": 0, "i": 0, "j": 1, "width_b": 30, "depth_h": 40,
                     "material": "松木"}],
        "supports": [{"node": 0, "type": "roller"}],
        "loads": [{"type": "force", "node": 1, "fx": 0, "fy": -49.0}],
    }
    with pytest.raises(ValueError, match="机构") as exc:
        run_frame(model)
    msg = str(exc.value)
    assert "节点" in msg
    assert ("ux" in msg) or ("uy" in msg) or ("rz" in msg)

    # 改为悬臂（一端固定）即不再是机构
    model["supports"] = [{"node": 0, "type": "fixed"}]
    res = run_frame(model)
    assert res["max_disp_mm"] > 0.0



# --------------------------------------------------------------------- 开孔
def test_member_hole_net_section_stress_2d():
    """2D 杆件开孔：净截面 + 孔边应力集中放大，挠度不变。"""
    length = 300.0
    d = 10.0
    model = {
        "name": "悬臂开孔",
        "nodes": [{"id": 0, "x": 0, "y": 0}, {"id": 1, "x": length, "y": 0}],
        "members": [{"id": 0, "i": 0, "j": 1, "width_b": B, "depth_h": H,
                     "material": "松木",
                     "holes": [{"at_mm": length / 2.0, "d_mm": d}]}],
        "supports": [{"node": 0, "type": "fixed"}],
        "loads": [{"type": "force", "node": 1, "fy": -100.0}],
    }
    res = run_frame(model)
    mem = res["members"][0]
    i_net = INERTIA - B * d ** 3 / 12.0
    expect = (100.0 * length * (H / 2.0) / i_net) * 2.5
    assert mem["sigma_MPa"] == pytest.approx(expect, rel=2e-3)
    assert mem["A_net_mm2"] == pytest.approx(AREA - B * d, rel=1e-9)
    assert mem["hole_kt"] == pytest.approx(2.5)
    plain = {**model, "members": [{k: v for k, v in model["members"][0].items()
                                  if k != "holes"}]}
    assert res["max_disp_mm"] == pytest.approx(
        run_frame(plain)["max_disp_mm"], rel=1e-9)


# ------------------------------------------------ 面荷载（荷载类型自动识别）
def test_area_load_2d_distributed_to_members():
    """二层搁板面荷载：600 N 传给 2 根承托横梁 → 每根 q=0.5 N/mm（M=qL²/8）。"""
    span = 600.0
    model = {
        "name": "双梁搁板",
        "nodes": [{"id": 1, "x": 0, "y": 0}, {"id": 2, "x": span, "y": 0},
                  {"id": 3, "x": 0, "y": 300}, {"id": 4, "x": span, "y": 300}],
        "members": [
            {"id": 1, "i": 1, "j": 2, "width_b": B, "depth_h": H, "material": "松木"},
            {"id": 2, "i": 3, "j": 4, "width_b": B, "depth_h": H, "material": "松木"},
        ],
        "supports": [
            {"node": 1, "type": "pinned"}, {"node": 2, "type": "roller"},
            {"node": 3, "type": "pinned"}, {"node": 4, "type": "roller"},
        ],
        "loads": [{"type": "area", "members": [1, 2], "total_N": -600.0}],
    }
    res = run_frame(model)

    q = 600.0 / (2.0 * span)
    for mem in res["members"]:
        assert mem["M_max_Nmm"] == pytest.approx(q * span ** 2 / 8.0, rel=2e-3)
    delta = 5.0 * q * span ** 4 / (384.0 * E * INERTIA)
    assert res["max_disp_mm"] == pytest.approx(delta, rel=2e-3)


def test_area_load_2d_rejects_bad_spec():
    """面荷载缺 members / total_N=0 / 杆件不存在 → 报错（不静默退化为集中力）。"""
    base = {
        "name": "单梁",
        "nodes": [{"id": 1, "x": 0, "y": 0}, {"id": 2, "x": 300, "y": 0}],
        "members": [{"id": 1, "i": 1, "j": 2, "width_b": B, "depth_h": H,
                     "material": "松木"}],
        "supports": [{"node": 1, "type": "fixed"}],
    }
    for bad in ([{"type": "area", "total_N": -100.0}],
                [{"type": "area", "members": [1], "total_N": 0.0}],
                [{"type": "area", "members": [99], "total_N": -100.0}]):
        with pytest.raises(ValueError):
            run_frame({**base, "loads": bad})


def test_area_load_2d_alias_and_duplicate_members():
    """别名 surface/面荷载 归一化为 area；重复 id 去重后 q 不翻倍。"""
    span = 600.0
    model = {
        "name": "双梁搁板别名",
        "nodes": [{"id": 1, "x": 0, "y": 0}, {"id": 2, "x": span, "y": 0},
                  {"id": 3, "x": 0, "y": 300}, {"id": 4, "x": span, "y": 300}],
        "members": [
            {"id": 1, "i": 1, "j": 2, "width_b": B, "depth_h": H, "material": "松木"},
            {"id": 2, "i": 3, "j": 4, "width_b": B, "depth_h": H, "material": "松木"},
        ],
        "supports": [
            {"node": 1, "type": "pinned"}, {"node": 2, "type": "roller"},
            {"node": 3, "type": "pinned"}, {"node": 4, "type": "roller"},
        ],
        "loads": [{"type": "surface", "members": [1, 1, 2], "total_N": -600.0}],
    }
    res = run_frame(model)

    q = 600.0 / (2.0 * span)       # 去重后跨度 1200 mm
    for mem in res["members"]:
        assert mem["M_max_Nmm"] == pytest.approx(q * span ** 2 / 8.0, rel=2e-3)
