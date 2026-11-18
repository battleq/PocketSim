# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""2D 梁杆框架求解器（P1 通用框架阶段核心）。

模型描述：节点（3 DOF：ux, uy, rz）+ 梁杆件（矩形截面 b×h，欧拉-伯努利梁元）
        + 支座（固定/铰接/滚轴）+ 载荷（节点力/杆件竖向均布）。
求解：教科书梁元刚度矩阵组装，scipy 稀疏直接法。
单位：长度 mm、力 N、力矩 N·mm、分布力 N/mm、应力与模量 MPa。
约定：局部 x 轴 i→j，局部 y 垂直（逆时针 90°）；内力 f = k·u - f_eq（节点作用于杆）；
     弯矩 M(x) = f[2] - f[1]·x - w·x²/2（下侧受拉为正）；轴力拉正 N = -f[0]。
假设：线弹性小变形、忽略构件自重与剪切变形、铰接计算长度系数 K=1（欧拉屈曲）。
"""
from __future__ import annotations

import math
import warnings
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import scipy.sparse as sp
import scipy.sparse.linalg as spla

from .i18n import (
    area_mm2,
    dim_mm,
    disp_mm,
    force_n,
    is_en,
    length_unit,
    linear_nmm,
    stress_mpa,
)
from .interpret import hair_analogy
from .materials import MaterialsLibrary
from .stability import DOF_LABELS_2D, check_mechanism
from .frame3d import hole_net_section, parse_member_holes
from .flow import AIR_DENSITY, CP_NET, speed_from_beaufort

# 规模上限（生活场景足够；防 AI 生成失控模型拖垮手机）
MAX_NODES = 60
MAX_MEMBERS = 80
SAMPLE_N = 33          # 每杆件内力/挠度采样点数
SAMPLE_N_UI = 9        # UI 变形折线采样点数

SUPPORT_DOF = {
    "fixed":  (True, True, True),   # ux, uy, rz
    "pinned": (True, True, False),
    "roller": (False, True, False),  # 仅竖向约束（水平可滑）
}

SAFETY_SAFE = 2.0
SAFETY_CAUTION = 1.2
BUCKLING_WARN = 0.5   # 受压承载力利用率超过该值提示失稳风险
BUCKLING_FAIL = 1.0


# ====================================================================== 校验
def _err(msg: str) -> None:
    raise ValueError(msg)


def _section_text(mem: Dict[str, Any]) -> str:
    """结果页的截面描述（矩形/矩形管/圆管/实心圆）。"""
    if is_en():
        u = length_unit()
        if mem.get("shape") == "circle":
            if mem["wall_t"] > 0:
                return f"⌀{dim_mm(mem['b'])}×{dim_mm(mem['wall_t'])} {u} (tube)"
            return f"⌀{dim_mm(mem['b'])} {u} (solid round)"
        if mem["wall_t"] > 0:
            return (f"{dim_mm(mem['b'])}×{dim_mm(mem['h'])}×{dim_mm(mem['wall_t'])}"
                    f" {u} (hollow)")
        return f"{dim_mm(mem['b'])}×{dim_mm(mem['h'])} {u}"
    if mem.get("shape") == "circle":
        if mem["wall_t"] > 0:
            return f"⌀{mem['b']:g}×{mem['wall_t']:g} mm（圆管）"
        return f"⌀{mem['b']:g} mm（实心圆）"
    if mem["wall_t"] > 0:
        return f"{mem['b']:g}×{mem['h']:g}×{mem['wall_t']:g} mm（空心）"
    return f"{mem['b']:g}×{mem['h']:g} mm"


def _validate(model: Dict[str, Any]) -> Dict[str, Any]:
    """校验并规范化模型：返回带索引结构的内部表示。"""
    if not isinstance(model, dict):
        _err("框架模型必须是 JSON 对象")

    raw_nodes = model.get("nodes") or []
    raw_members = model.get("members") or []
    raw_supports = model.get("supports") or []
    raw_loads = model.get("loads") or []

    if not raw_nodes:
        _err("模型缺少节点（nodes）")
    if not raw_members:
        _err("模型缺少杆件（members）")
    if len(raw_nodes) > MAX_NODES or len(raw_members) > MAX_MEMBERS:
        _err(f"模型规模超限（节点≤{MAX_NODES}、杆件≤{MAX_MEMBERS}），"
             f"当前 {len(raw_nodes)} 节点 / {len(raw_members)} 杆件")

    # 节点：id 可省略（按序号）
    nodes: List[Dict[str, float]] = []
    ids = {}
    for idx, n in enumerate(raw_nodes):
        nid = n.get("id", idx)
        if nid in ids:
            _err(f"节点 id 重复：{nid}")
        try:
            x = float(n["x"]); y = float(n["y"])
        except (KeyError, TypeError, ValueError):
            _err(f"第 {idx + 1} 个节点缺少有效的 x/y 坐标（单位毫米）")
        ids[nid] = idx
        nodes.append({"id": nid, "x": x, "y": y})

    # 杆件：截面 b×h 必须显式给出（AI 建模时确认过）
    members: List[Dict[str, Any]] = []
    for idx, m in enumerate(raw_members):
        mid = m.get("id", idx)
        try:
            i = ids[m["i"]]; j = ids[m["j"]]
        except KeyError:
            _err(f"杆件 {mid} 引用了不存在的节点（i/j 必须是 nodes 中的 id）")
        dx = nodes[j]["x"] - nodes[i]["x"]
        dy = nodes[j]["y"] - nodes[i]["y"]
        L = math.hypot(dx, dy)
        if L < 1e-9:
            _err(f"杆件 {mid} 长度为 0，请检查节点坐标")
        # 截面：矩形（默认）或圆管（shape=circle/tube/pipe，按外径 + 壁厚）
        shape_raw = str(m.get("shape") or "rect").strip().lower()
        circle = shape_raw in ("circle", "round", "circular", "tube", "pipe", "圆管", "圆")
        if circle:
            try:
                D = float(m.get("outer_diameter_mm", m.get("diameter_mm", 0.0)) or 0.0)
            except (KeyError, TypeError, ValueError):
                _err(f"杆件 {mid} 的圆管外径 outer_diameter_mm 必须是数字（毫米）")
            if D <= 0:
                _err(f"杆件 {mid} 是圆管截面，缺少外径 outer_diameter_mm（毫米）")
            b = h = D
        else:
            try:
                b = float(m["width_b"]); h = float(m["depth_h"])
            except (KeyError, TypeError, ValueError):
                _err(f"杆件 {mid} 缺少截面尺寸 width_b（面外宽度）/depth_h（面内高度，毫米）")
        if b <= 0 or h <= 0:
            _err(f"杆件 {mid} 截面尺寸必须为正数")
        try:
            t = float(m.get("wall_t", 0.0) or 0.0)
        except (TypeError, ValueError):
            _err(f"杆件 {mid} 的壁厚 wall_t 必须是数字（毫米，实心截面可省略）")
        if t < 0:
            _err(f"杆件 {mid} 的壁厚 wall_t 不能为负（实心截面请省略或填 0）")
        if t > 0 and 2.0 * t >= min(b, h):
            _err(f"杆件 {mid} 的壁厚 {t:g} mm 过大（需小于 min(width_b, depth_h)/2 = "
                 f"{min(b, h) / 2.0:g} mm），请检查是否把截面外形尺寸填成了壁厚")
        mat = m.get("material")
        if not mat:
            _err(f"杆件 {mid} 缺少材料（material）")
        if circle:
            # 圆形/圆环截面：I = π(D⁴−d⁴)/64，J = 2I（闭口圆管精确成立）
            d_in = max(b - 2.0 * t, 0.0) if t > 0 else 0.0
            area = math.pi * (b ** 2 - d_in ** 2) / 4.0
            inertia = math.pi * (b ** 4 - d_in ** 4) / 64.0
        elif t > 0:                       # 空心矩形/方管：外形减内腔
            bi, hi = b - 2.0 * t, h - 2.0 * t
            area = b * h - bi * hi
            inertia = (b * h ** 3 - bi * hi ** 3) / 12.0
        else:                           # 实心矩形
            area = b * h
            inertia = b * h ** 3 / 12.0
        holes = parse_member_holes(mid, m.get("holes"), b, h, L, t)
        if circle and holes:
            _err(f"杆件 {mid} 是圆管/圆截面，暂不支持开孔（开孔请改用矩形截面）")
        net = hole_net_section(
            b, h, area, inertia, h * b ** 3 / 12.0, 0.0, holes, t
        )
        members.append({
            "id": mid, "i": i, "j": j, "L": L,
            "b": b, "h": h, "wall_t": t, "A": area, "I": inertia,
            "shape": "circle" if circle else "rect",
            "holes": holes,
            "has_hole": net["has_hole"],
            "kt": net["kt"],
            "A_net": net.get("A_net", area),
            "I_net": net.get("Iy_net", inertia),
            "material": str(mat), "E_MPa": None, "strength_MPa": None,
        })

    # 支座
    supports: List[Dict[str, Any]] = []
    for s in raw_supports:
        try:
            ni = ids[s["node"]]
        except KeyError:
            _err(f"支座引用了不存在的节点：{s.get('node')}")
        stype = str(s.get("type", "pinned")).lower()
        if stype not in SUPPORT_DOF:
            _err(f"支座类型 {stype} 不支持（可用：fixed 固定 / pinned 铰接 / roller 滚轴）")
        supports.append({"node": ni, "type": stype})
    if not supports:
        _err("模型缺少支座（supports），结构无法承载")

    # 载荷
    loads: List[Dict[str, Any]] = []
    # AI 常用别名 → 合法类型（force/udl），LLM 偶尔用 "distributed" / "point" / "concentrated"
    _LOAD_ALIASES = {
        "point": "force", "concentrated": "force", "f": "force", "node": "force",
        "distributed": "udl", "uniform": "udl", "uniformly_distributed": "udl",
        "w": "udl", "dist": "udl",
        "surface": "area", "panel": "area", "areaload": "area",
        "面荷载": "area", "面载荷": "area",
        "windload": "wind", "风载": "wind", "风荷载": "wind",
    }
    for ld in raw_loads:
        ltype_raw = str(ld.get("type", "")).strip().lower()
        ltype = _LOAD_ALIASES.get(ltype_raw, ltype_raw)
        if ltype == "force":
            try:
                ni = ids[ld["node"]]
            except KeyError:
                _err(f"节点力引用了不存在的节点：{ld.get('node')}")
            loads.append({
                "type": "force", "node": ni,
                "fx": float(ld.get("fx", 0.0)),
                "fy": float(ld.get("fy", 0.0)),
                "mz": float(ld.get("mz", 0.0)),
            })
        elif ltype == "udl":
            try:
                mi = next(k for k, mm in enumerate(members) if mm["id"] == ld["member"])
            except (KeyError, StopIteration):
                _err(f"均布载荷引用了不存在的杆件：{ld.get('member')}")
            qy = float(ld.get("qy", 0.0))
            if qy == 0.0:
                # 零值均布是无载荷占位（常见于 AI 建模），直接忽略而不是报错
                continue
            loads.append({"type": "udl", "member": mi, "qy": qy})
        elif ltype == "area":
            # 面荷载：作用在承托面上的一组杆件，按各杆件长度占比分配为均布载荷。
            # 典型场景：椅子坐面/桌面把总重传给下方若干横杆（避免一律折算成跨中集中力）。
            mids = ld.get("members") or []
            if not mids:
                _err("面荷载必须用 members 列出承托它的杆件")
            # 重复 id 去重：否则同一杆件会叠加多份 q，总力虽守恒但分配失真
            mids = list(dict.fromkeys(mids))
            total = float(ld.get("total_N", 0.0))
            if total == 0.0:
                _err("面荷载的 total_N 不能为 0（单位 N，向下为负）")
            try:
                mis = [next(k for k, mm in enumerate(members) if mm["id"] == mid) for mid in mids]
            except StopIteration:
                _err(f"面荷载引用了不存在的杆件：{mids}")
            lens = [float(members[k]["L"]) for k in mis]
            span = sum(lens)
            if span <= 0.0:
                _err("面荷载作用的杆件长度全为 0，无法分配")
            q = total / span
            for k in mis:
                loads.append({"type": "udl", "member": k, "qy": q})
        elif ltype == "wind":
            # 风载源字段（P1-5）：LLM 只报风速/风级与受风面积，动压换算在代码侧完成
            # （净风力 F = cp·½ρv²·A），按迎风节点均摊为水平集中力，禁止模型心算风荷载。
            nids = ld.get("nodes") or []
            if not nids:
                _err("风载必须用 nodes 列出迎风节点 id")
            try:
                nis = [ids[n] for n in nids]
            except KeyError:
                _err(f"风载引用了不存在的节点：{nids}")
            v = ld.get("wind_speed_m_s")
            lv = ld.get("beaufort")
            if v is not None and lv is not None:
                _err("风载风速来源冲突：wind_speed_m_s 与 beaufort 二选一，不得同时提供")
            if v is None and lv is None:
                _err("风载缺少风速来源：请给 wind_speed_m_s（m/s）或 beaufort（0~17 级风）")
            speed = float(v) if v is not None else speed_from_beaufort(int(lv))
            if not (0.0 < speed <= 75.0):
                _err(f"风载风速 {speed:.1f} m/s 超出合理范围（0~75 m/s）")
            area = float(ld.get("area_m2", 0.0))
            if area <= 0.0:
                _err("风载必须提供受风投影面积 area_m2（单位 m²，>0）")
            cp = float(ld.get("cp") or CP_NET)
            if cp <= 0.0:
                _err("风载体型系数 cp 必须为正")
            direction = str(ld.get("dir", "x")).strip().lower()
            if direction not in ("x", "y"):
                _err(f"平面风载方向 dir 只支持 x/y（收到 {ld.get('dir')}）")
            share = cp * 0.5 * AIR_DENSITY * speed * speed * area / len(nis)
            for ni in nis:
                loads.append({
                    "type": "force", "node": ni,
                    "fx": share if direction == "x" else 0.0,
                    "fy": share if direction == "y" else 0.0,
                    "mz": 0.0,
                })
        elif ltype == "thermal":
            # 杆件均匀温度变化（相对参考温度，°C 等价于 K）
            try:
                mi = next(k for k, mm in enumerate(members) if mm["id"] == ld["member"])
            except (KeyError, StopIteration):
                _err(f"温度载荷引用了不存在的杆件：{ld.get('member')}")
            dt = float(ld.get("delta_temp", 0.0))
            if dt == 0.0:
                _err("温度载荷 delta_temp 不能为 0（升温为正，降温为负，单位 °C）")
            loads.append({"type": "thermal", "member": mi, "delta_temp": dt})
        else:
            _err(f"载荷类型 {ltype} 不支持（可用：force 节点力 / udl 杆件均布 / area 面荷载 / wind 风载 / thermal 杆件温度变化）")
    if not loads:
        _err("模型没有有效载荷（loads 为空或数值全为 0）；水平风载请用 type=wind（风速/风级+受风面积，代码自动换算）")

    # 材料属性回填（失败给出可用材料列表）
    lib = model.get("_library")
    if lib is None:
        from .materials import load_library
        lib = load_library()
        model["_library"] = lib
    missing = set()
    for m in members:
        rec = lib.find_material(m["material"])
        if rec is None:
            missing.add(m["material"])
            continue
        m["E_MPa"] = float(rec["elastic_modulus"]) / 1e6
        m["strength_MPa"] = float(rec["strength"])
        m["alpha"] = float(rec.get("thermal_expansion", 0.0))        # 线膨胀系数 1/K
        m["max_temp"] = float(rec.get("max_service_temp", float("inf")))  # 最高长期使用温度 °C
        # 型材截面特性覆盖：如果材料库直接给出空心截面 A/I，覆盖实心矩形计算值
        # （欧标铝型材等空心截面的惯性矩远小于同外形实心矩形，必须用库值；
        #  工字钢/H 型钢/角钢给出强轴 inertia_x_mm4，平面内弯曲取强轴 Ix）
        A_from_lib = rec.get("section_area_mm2")
        I_from_lib = rec.get("inertia_x_mm4")
        if I_from_lib is None:
            I_from_lib = rec.get("inertia_mm4")
        if A_from_lib is not None and I_from_lib is not None:
            m["A"] = float(A_from_lib)
            m["I"] = float(I_from_lib)
    if missing:
        names = "、".join(sorted(missing))
        avail = "、".join(r["name"] for r in lib.all_materials())
        _err(f"材料库中没有「{names}」。可选材料：{avail}")

    return {"name": str(model.get("name", "框架模型")),
            "nodes": nodes, "members": members,
            "supports": supports, "loads": loads}


# ====================================================================== 单元
def _element_k(E: float, A: float, I: float, L: float) -> np.ndarray:
    """局部坐标系梁元刚度矩阵（6×6，DOF 顺序 [u1 v1 θ1 u2 v2 θ2]）。"""
    k = np.zeros((6, 6))
    ea_l = E * A / L
    ei = E * I
    k[0, 0] = k[3, 3] = ea_l
    k[0, 3] = k[3, 0] = -ea_l
    # 弯曲项
    k[1, 1] = k[4, 4] = 12.0 * ei / L ** 3
    k[1, 4] = k[4, 1] = -12.0 * ei / L ** 3
    k[1, 2] = k[2, 1] = k[1, 5] = k[5, 1] = 6.0 * ei / L ** 2
    k[2, 4] = k[4, 2] = k[4, 5] = k[5, 4] = -6.0 * ei / L ** 2
    k[2, 2] = k[5, 5] = 4.0 * ei / L
    k[2, 5] = k[5, 2] = 2.0 * ei / L
    return k


def _transform(c: float, s: float) -> np.ndarray:
    """global→local 位移变换矩阵 T（6×6），[u_l] = T [u_g]。"""
    t = np.zeros((6, 6))
    t[0, 0] = t[3, 3] = c
    t[0, 1] = t[3, 4] = s
    t[1, 0] = t[4, 3] = -s
    t[1, 1] = t[4, 4] = c
    t[2, 2] = t[5, 5] = 1.0
    return t


def _udl_local(qy_global: float, c: float, s: float, L: float) -> Tuple[float, float]:
    """全局竖向均布 → 局部轴向 a（x 向）与横向 w（y 向）。"""
    return s * qy_global * L / 2.0, c * qy_global * L / 2.0  # 返回端部合计


def _hermite(xi: float) -> Tuple[float, float, float, float]:
    """v(ξ) = H1·v1 + L·H2·θ1 + H3·v2 + L·H4·θ2 的形状函数。"""
    h1 = 1 - 3 * xi ** 2 + 2 * xi ** 3
    h2 = xi - 2 * xi ** 2 + xi ** 3
    h3 = 3 * xi ** 2 - 2 * xi ** 3
    h4 = -xi ** 2 + xi ** 3
    return h1, h2, h3, h4


# ====================================================================== 求解
def run_frame(model: Dict[str, Any], library: Optional[MaterialsLibrary] = None) -> Dict[str, Any]:
    """求解 2D 框架并返回结果 dict（错误抛 ValueError，中文消息）。"""
    import time
    t0 = time.perf_counter()
    if library is not None:
        model = dict(model)
        model["_library"] = library
    result = _solve(model)
    result["solve_ms"] = round((time.perf_counter() - t0) * 1000.0, 1)
    # JSON 序列化前把 tuple 折线转为标准数组（bridge._dumps 要求标准类型）
    for pl in result.get("polylines", []):
        pl["pts"] = [[round(v, 4) for v in pt] for pt in pl["pts"]]
    return result


def _assemble_free_stiffness(model: Dict[str, Any]
                             ) -> Tuple[np.ndarray, np.ndarray, Dict[str, Any]]:
    """只组装总刚度阵与支座自由掩码，不组装载荷、不求解（供预检与求解共享）。

    组装循环会给每个 member 写入 _dofs/_c/_s/_T 缓存——_solve 的载荷组装与
    内力后处理依赖这些缓存，因此返回的 members（在 m 中）已带缓存。
    返回 (K, free, m)：K 为整体刚度阵，free 为自由自由度布尔掩码，m 为校验后模型。
    """
    m = _validate(model)
    nodes = m["nodes"]; members = m["members"]
    supports = m["supports"]
    nn = len(nodes)
    ndof = 3 * nn
    K = np.zeros((ndof, ndof))
    for mi, mem in enumerate(members):
        dx = nodes[mem["j"]]["x"] - nodes[mem["i"]]["x"]
        L = mem["L"]
        c, s = dx / L, (nodes[mem["j"]]["y"] - nodes[mem["i"]]["y"]) / L
        k = _element_k(mem["E_MPa"], mem["A"], mem["I"], L)
        T = _transform(c, s)
        kg = T.T @ k @ T
        dofs = [3 * mem["i"], 3 * mem["i"] + 1, 3 * mem["i"] + 2,
                3 * mem["j"], 3 * mem["j"] + 1, 3 * mem["j"] + 2]
        K[np.ix_(dofs, dofs)] += kg
        mem["_dofs"] = dofs
        mem["_c"], mem["_s"], mem["_T"] = c, s, T
    fixed = np.zeros(ndof, dtype=bool)
    for sup in supports:
        base = 3 * sup["node"]
        for k, flag in enumerate(SUPPORT_DOF[sup["type"]]):
            if flag:
                fixed[base + k] = True
    free = ~fixed
    return K, free, m


def _solve(model: Dict[str, Any]) -> Dict[str, Any]:
    K, free, m = _assemble_free_stiffness(model)

    nodes = m["nodes"]; members = m["members"]
    supports = m["supports"]; loads = m["loads"]
    nn = len(nodes)

    # ---- 组装总刚度矩阵与外载向量 ----
    ndof = 3 * nn
    F = np.zeros(ndof)
    udl_of_member: Dict[int, Dict[str, float]] = {}
    thermal_of_member: Dict[int, float] = {}

    for ld in loads:
        if ld["type"] == "force":
            base = 3 * ld["node"]
            F[base] += ld["fx"]; F[base + 1] += ld["fy"]; F[base + 2] += ld["mz"]
        elif ld["type"] == "thermal":
            # 均匀温度变化 ΔT 的等效节点力（局部坐标）：杆件自由膨胀受约束时产生
            #   N_th = E·A·α·ΔT（升温受压为正）；等效节点力 [ -Nth, 0, 0, +Nth, 0, 0 ]
            # 解出位移后，自由杆自动给出 ΔL=αTL 且内力为 0；约束杆给出真实热应力。
            mi = ld["member"]; mem = members[mi]
            Nth = mem["E_MPa"] * mem["A"] * mem["alpha"] * ld["delta_temp"]
            feq_l = np.array([-Nth, 0.0, 0.0, Nth, 0.0, 0.0])
            feq_g = mem["_T"].T @ feq_l
            F[mem["_dofs"]] += feq_g
            thermal_of_member[mi] = ld["delta_temp"]
        else:
            mi = ld["member"]; mem = members[mi]
            c, s, L = mem["_c"], mem["_s"], mem["L"]
            a_total = s * ld["qy"] * L      # 轴向合力
            w_total = c * ld["qy"] * L      # 横向合力
            w_line = c * ld["qy"]           # 局部横向线载荷（N/mm）
            a_line = s * ld["qy"]
            # 等效节点荷载（局部）：轴向 [aL/2, aL/2]，横向 [wL/2, wL²/12, wL/2, -wL²/12]
            feq_l = np.array([
                a_total / 2, w_total / 2, w_line * L ** 2 / 12,
                a_total / 2, w_total / 2, -w_line * L ** 2 / 12])
            feq_g = mem["_T"].T @ feq_l
            F[mem["_dofs"]] += feq_g
            udl_of_member[mi] = {"w": w_line, "a": a_line}

    # ---- 支座约束 ----（free 掩码已由 _assemble_free_stiffness 计算）
    if not free.any() and not thermal_of_member:
        _err("所有自由度均被约束，结构无法变形")
    # 全约束 + 温度载荷：位移恒为 0，但杆件因膨胀受约束产生热应力（f = -feq），
    # 跳过自由自由度求解，直接用零位移计算内力。

    # ---- 机构（约束不足）预检：定位到具体节点与方向 ----
    if free.any():
        bad = check_mechanism(K[np.ix_(free, free)], np.flatnonzero(free),
                              nodes, DOF_LABELS_2D, 3)
        if bad:
            _err(bad)

    # ---- 求解 ----
    K_sp = sp.csr_matrix(K[np.ix_(free, free)])
    try:
        u = np.zeros(ndof)
        with warnings.catch_warnings():
            # 机构（约束不足）时 scipy 给 MatrixRankWarning，解为 nan，
            # 下方 isfinite 检查统一转成中文报错，这里不再重复告警
            warnings.simplefilter("ignore")
            u[free] = spla.spsolve(K_sp, F[free])
    except Exception as e:  # noqa: BLE001
        _err(f"求解失败（通常是约束不足导致结构不稳定）：{e}")
    if not np.all(np.isfinite(u)):
        _err("求解结果异常（可能是约束不足或结构为机构），请检查支座设置")

    # ---- 位移统计（节点 + 杆内采样） ----
    max_disp = 0.0; max_disp_desc = ""
    node_disp = [{"dx": float(u[3 * i]), "dy": float(u[3 * i + 1]),
                  "rz": float(u[3 * i + 2])} for i in range(nn)]
    polylines = []  # UI 变形折线（原始 + 位移）
    member_results = []

    for mi, mem in enumerate(members):
        c, s, L = mem["_c"], mem["_s"], mem["L"]
        ue = u[mem["_dofs"]]
        ul = mem["_T"] @ ue
        k = _element_k(mem["E_MPa"], mem["A"], mem["I"], L)
        udl = udl_of_member.get(mi, {"w": 0.0, "a": 0.0})
        a_total = udl["a"] * L; w_total = udl["w"] * L
        feq_l = np.array([
            a_total / 2, w_total / 2, udl["w"] * L ** 2 / 12,
            a_total / 2, w_total / 2, -udl["w"] * L ** 2 / 12])
        # 叠加温度等效节点力（与组装阶段一致）：升温受压为正 Nth=EAαΔT
        dt = thermal_of_member.get(mi, 0.0)
        if dt != 0.0:
            Nth = mem["E_MPa"] * mem["A"] * mem["alpha"] * dt
            feq_l[0] -= Nth; feq_l[3] += Nth
        f = k @ ul - feq_l            # 节点作用于杆的端部力（见模块 docstring 约定）

        # 弯矩/轴力包络（采样）
        M_abs_max = 0.0; M_at = 0.0
        N_max_comp = 0.0
        for kk in range(SAMPLE_N + 1):
            x = L * kk / SAMPLE_N
            Mx = f[2] - f[1] * x - udl["w"] * x * x / 2
            Nx = -f[0] + udl["a"] * x
            if abs(Mx) > M_abs_max:
                M_abs_max = abs(Mx); M_at = x
            N_max_comp = min(N_max_comp, Nx)   # 取最大压力（负得最多）
        # 应力（保守线性叠加；开孔处用净截面并按孔边应力集中系数放大）
        A_s = mem["A_net"] if mem.get("has_hole") else mem["A"]
        I_s = mem["I_net"] if mem.get("has_hole") else mem["I"]
        kt = mem["kt"] if mem.get("has_hole") else 1.0
        sigma_b = M_abs_max * (mem["h"] / 2.0) / I_s
        sigma_a = max(abs(-f[0]), abs(f[3])) / A_s
        sigma = (sigma_a + sigma_b) * kt
        mem["_sigma"] = sigma
        mem["_N_comp"] = -N_max_comp   # 正值 = 压力幅值

        # 挠度采样：弯矩二重积分（精确，UDL 四次挠度曲线也准；
        # 三次 Hermite 插值对均布载荷跨中有 ~20% 经典插值误差，弃用）
        #   v(x) = v_i + θ_i·x − (M0·x²/2 − V0·x³/6 − w·x⁴/24)/EI
        xs = nodes[mem["i"]]["x"]; ys = nodes[mem["i"]]["y"]
        EI = mem["E_MPa"] * mem["I"]
        M0 = f[2]; V0 = f[1]
        seg_pts = []
        seg_defl = 0.0; seg_defl_at = 0.0
        for kk in range(SAMPLE_N + 1):
            xi = kk / SAMPLE_N
            x = L * xi
            v_l = (ul[1] + ul[2] * x
                   - (M0 * x ** 2 / 2 - V0 * x ** 3 / 6 - udl["w"] * x ** 4 / 24) / EI)
            u_l = ul[0] + (ul[3] - ul[0]) * xi
            dgx = c * u_l - s * v_l
            dgy = s * u_l + c * v_l
            gx = xs + c * L * xi; gy = ys + s * L * xi
            d = math.hypot(dgx, dgy)
            if d > seg_defl:
                seg_defl = d; seg_defl_at = x
            if kk % (SAMPLE_N // (SAMPLE_N_UI - 1)) == 0 or kk == SAMPLE_N:
                seg_pts.append((gx, gy, dgx, dgy))   # 原始点 + 位移分量（UI 自行放大）
        polylines.append({"member": mem["id"], "pts": seg_pts})
        if seg_defl > max_disp:
            pos = "跨中" if 0.35 * L < seg_defl_at < 0.65 * L else "端部"
            mid_pos = "midspan" if 0.35 * L < seg_defl_at < 0.65 * L else "end"
            max_disp = seg_defl
            max_disp_desc = (f"member {mem['id']} near {mid_pos}" if is_en()
                             else f"杆件 {mem['id']} {pos}附近")

        member_results.append({
            "id": mem["id"], "material": mem["material"],
            "section": _section_text(mem),
            "N_N": float(-f[0]), "M_max_Nmm": float(M_abs_max),
            "sigma_MPa": float(sigma),
            "compress_kN": float(mem["_N_comp"] / 1000.0),
            "util": float(sigma / mem["strength_MPa"]) if mem["strength_MPa"] else None,
            "delta_temp": thermal_of_member.get(mi),
            "holes": mem["holes"],
            "hole_kt": float(kt),
            "A_net_mm2": float(mem["A_net"]) if mem.get("has_hole") else None,
        })

    # ---- 总量指标 ----
    sigma_max = max(mem["_sigma"] for mem in members)
    gov = max(members, key=lambda mm_: mm_["_sigma"])
    strength = gov["strength_MPa"]
    safety = strength / sigma_max if sigma_max > 0 else float("inf")

    # ---- 屈曲校核（受压杆欧拉，K=1） ----
    buck_ratio = 0.0; buck_member = None
    for mem in members:
        if mem["_N_comp"] <= 1e-9:
            continue
        I_b = mem["I_net"] if mem.get("has_hole") else mem["I"]
        p_cr = math.pi ** 2 * mem["E_MPa"] * I_b / mem["L"] ** 2
        ratio = mem["_N_comp"] / p_cr if p_cr > 0 else float("inf")
        if ratio > buck_ratio:
            buck_ratio = ratio; buck_member = mem["id"]
    buck_note = ""
    if buck_ratio >= BUCKLING_FAIL:
        buck_note = (f"member {buck_member} is at or beyond the Euler critical load "
                     f"(utilization {buck_ratio:.0%}) — buckling risk"
                     if is_en() else
                     f"杆件 {buck_member} 受压接近或超过欧拉临界力（利用率 {buck_ratio:.0%}），有失稳（压弯）风险")
    elif buck_ratio >= BUCKLING_WARN:
        buck_note = (f"member {buck_member} compression utilization {buck_ratio:.0%}; "
                     f"watch slender compression members for buckling"
                     if is_en() else
                     f"杆件 {buck_member} 受压承载力利用率 {buck_ratio:.0%}，细长受压件请留意失稳风险")

    # ---- 判定 ----
    if buck_ratio >= BUCKLING_FAIL:
        verdict = "危险"
    elif safety >= SAFETY_SAFE:
        verdict = "安全"
    elif safety >= SAFETY_CAUTION:
        verdict = "注意"
    else:
        verdict = "危险"

    # ---- 温度安全判据 ----
    # 受热杆件若超过材料最高长期使用温度 → 直接红牌（强度与材料耐温哪个更严取哪个）
    over_temp_members = []
    for mi, mem in enumerate(members):
        dt = thermal_of_member.get(mi)
        if dt is None:
            continue
        # 以 20°C 为参考温度，杆件温度 = 20 + ΔT
        t_member = 20.0 + dt
        if t_member > mem["max_temp"]:
            over_temp_members.append((mem["id"], t_member, mem["max_temp"]))
    thermal_note = ""
    if over_temp_members:
        verdict = "危险"
        if is_en():
            names = "; ".join(f"member {mid} ({t:.0f}°C > limit {mx:.0f}°C)"
                              for mid, t, mx in over_temp_members)
            thermal_note = (f"Over temperature: {names}. Strength drops sharply above "
                            "the long-term temperature limit, so the stress / safety factor "
                            "results are no longer valid.")
        else:
            names = "、".join(f"杆{mid}（{t:.0f}°C > 耐温{mx:.0f}°C）" for mid, t, mx in over_temp_members)
            thermal_note = f"温度超标：{names}。材料超出长期耐温后强度显著下降，本结果的应力/安全系数不再有效。"
    max_delta_temp = max((abs(dt) for dt in thermal_of_member.values()), default=0.0)

    # ---- 通俗叙述 ----
    if is_en():
        mats = ", ".join(sorted({mm_["material"] for mm_ in members}))
        load_desc = []
        for ld in loads:
            if ld["type"] == "force":
                nid = nodes[ld["node"]]["id"]
                mag = (abs(ld["fy"]) if abs(ld.get("fy", 0)) > abs(ld.get("fx", 0))
                       else math.hypot(ld["fx"], ld["fy"]))
                load_desc.append(f"node {nid} point load {force_n(mag)}")
            else:
                mid = members[ld["member"]]["id"]
                if ld["type"] == "thermal":
                    sign = "rise" if ld["delta_temp"] > 0 else "drop"
                    load_desc.append(f"member {mid} temperature {sign} {abs(ld['delta_temp']):.0f}°C")
                else:
                    load_desc.append(f"member {mid} distributed load {linear_nmm(abs(ld['qy']))}")
        verdict_en = {"安全": "safe", "注意": "caution", "危险": "danger"}.get(verdict, verdict)
        narrative = [
            f"Model: your problem is simplified to a plane frame of {len(members)} members "
            f"and {len(nodes)} nodes (material: {mats}), solved with beam elements for bending "
            f"and axial load; member self-weight is neglected.",
            f"Loads: {'; '.join(load_desc)}. Constraints: {len(supports)} supports "
            f"({', '.join(sup['type'] for sup in supports)}).",
            f"Maximum deflection {disp_mm(max_disp)} ({max_disp_desc}); {hair_analogy(max_disp)}",
            f"The most loaded member is member {gov['id']}: max stress {stress_mpa(sigma_max)}, "
            f"material strength {stress_mpa(strength)}, safety factor {safety:.2f} ({verdict_en}).",
        ]
        if buck_note:
            narrative.append(f"Stability note: {buck_note}.")
        hole_members = [mem for mem in members if mem.get("has_hole")]
        if hole_members:
            u = length_unit()
            desc = "; ".join(
                f"member {mem['id']} (hole size {dim_mm(max(hh['d_mm'] for hh in mem['holes']))} {u}, "
                f"net area {area_mm2(mem['A_net'])}, Kt {mem['kt']:.1f})"
                for mem in hole_members
            )
            narrative.append(
                f"Holes: {desc}. Local peak stress is estimated from the net section and the "
                "hole stress-concentration factor; global stiffness still uses the full section, "
                "and local plasticity / cracking around holes is not modelled.")
        if thermal_note:
            narrative.append(f"Temperature note: {thermal_note}")
        if thermal_of_member:
            narrative.append(
                "Thermal analysis simplification: thermal expansion and restrained thermal stress "
                "are computed from a uniform temperature change per member; temperature gradients, "
                "transient effects and thermal shock are ignored — qualitative reference only.")
        narrative.append("All results are based on linear-elastic small-deformation assumptions and "
                         "representative material values. They are for everyday reference only; "
                         "consult a professional engineer for anything involving personal or "
                         "structural safety.")
    else:
        mats = "、".join(sorted({mm_["material"] for mm_ in members}))
        load_desc = []
        for ld in loads:
            if ld["type"] == "force":
                nid = nodes[ld["node"]]["id"]  # 内部 0 基索引 → 用户 1 基节点编号
                load_desc.append(f"节点 {nid} 集中力 {abs(ld['fy']):.1f} N"
                                 if abs(ld.get("fy", 0)) > abs(ld.get("fx", 0)) else
                                 f"节点 {nid} 集中力 {math.hypot(ld['fx'], ld['fy']):.1f} N")
            else:
                mid = members[ld["member"]]["id"]  # 内部 0 基索引 → 用户 1 基杆件编号
                if ld["type"] == "thermal":
                    sign = "升温" if ld["delta_temp"] > 0 else "降温"
                    load_desc.append(f"杆件 {mid} 温度{sign} {abs(ld['delta_temp']):.0f}°C")
                else:
                    load_desc.append(f"杆件 {mid} 均布载荷 {abs(ld['qy']):.2f} N/mm")
        narrative = [
            f"建模：把你的问题简化为 {len(members)} 根杆件、{len(nodes)} 个节点的平面框架"
            f"（材料：{mats}），用梁单元计算弯曲与轴向受力，忽略构件自重。",
            f"载荷：{('；'.join(load_desc))}。约束：{len(supports)} 处支座"
            f"（{'、'.join(sup['type'] for sup in supports)}）。",
            f"最大变形 {max_disp:.3f} 毫米（{max_disp_desc}），{hair_analogy(max_disp)}",
            f"受力最大的杆件是杆件 {gov['id']}：最大应力 {sigma_max:.2f} MPa，"
            f"材料强度代表值 {strength:.0f} MPa，安全系数 {safety:.2f}（{verdict}）。",
        ]
        if buck_note:
            narrative.append(f"稳定性提醒：{buck_note}。")
        hole_members = [mem for mem in members if mem.get("has_hole")]
        if hole_members:
            desc = "、".join(
                f"杆{mem['id']}（孔径/洞高 {max(hh['d_mm'] for hh in mem['holes']):g} mm，"
                f"净截面 {mem['A_net']:.0f} mm²，Kt {mem['kt']:.1f}）"
                for mem in hole_members
            )
            narrative.append(
                f"开孔提醒：{desc}。按净截面与孔边应力集中系数放大估算局部峰值应力；"
                "整体刚度仍按全截面计算，未考虑孔洞周边局部塑性/开裂。")
        if thermal_note:
            narrative.append(f"温度提醒：{thermal_note}")
        if thermal_of_member:
            narrative.append(
                "热分析简化说明：按每杆件均匀温度变化计算热膨胀与约束热应力，"
                "未考虑导热温度梯度、瞬态与热冲击，结果仅供定性参考。")
        narrative.append("以上基于线弹性小变形假设与材料代表值，结果仅供生活参考，"
                         "涉及人身或结构安全请咨询专业工程师。")

    return {
        "kind": "frame",
        "ok": True,
        "model_name": m["name"],
        "n_nodes": nn, "n_members": len(members), "n_supports": len(supports),
        "max_disp_mm": float(max_disp), "max_disp_desc": max_disp_desc,
        "max_stress_MPa": float(sigma_max),
        "governing_member": gov["id"],
        "safety_factor": float(safety) if safety != float("inf") else 999.0,
        "verdict": verdict,
        "buckling_ratio": float(buck_ratio),
        "buckling_note": buck_note,
        "thermal": {
            "has_thermal": bool(thermal_of_member),
            "max_delta_temp": float(max_delta_temp),
            "over_temp": thermal_note != "",
            "note": thermal_note,
            "by_member": {str(members[mi]["id"]): float(dt)
                          for mi, dt in thermal_of_member.items()},
        },
        "members": member_results,
        "node_disp": node_disp,
        "polylines": polylines,
        "narrative": narrative,
    }
