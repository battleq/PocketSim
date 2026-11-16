# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""薄壁壳体有限元（Reissner–Mindlin 平板壳，MITC4 抗剪切锁死）。

场景：矩形水箱 / 圆柱仓等薄壁充料容器（流体或散体）。壁厚作为本构参数进入
单元，**不需要**沿壁厚方向画网格，每个中面四边形节点 6 个全局自由度
（3 平动 + 3 转动），因此自由度比 TET4 实体网格低一到两个数量级，可在手机
上直接求解。截面形状与填料剖面均已参数化（build_*_mesh + fills.parse_fill）。

数值方法
--------
- 单元：4 节点四边形平板壳 = 双线性平面应力膜单元 + Reissner–Mindlin 板弯单元；
- 横向剪切采用 MITC4 混合插值（Dvorkin–Bathe 列式）：剪应变在四条边中点
  取样后按线性形函数"绑定"到高斯点，消除薄板极限下的剪切锁死；
- 膜与弯曲用 2×2 高斯积分；平板局部 5 DOF/节点 [u, v, w, βx, βy]，
  钻孔转动（绕法线 rz）无刚度，按 α·E·t·A（α=1e-3）加微小稳定刚度后
  经局部→整体坐标变换组装为 6 DOF/节点；
- 载荷：填料法向压力 pₙ(h)（流体静水 p=ρgh；散体 Rankine Ka/K₀/Kp，
  可选 Janssen 深仓饱和）为面法向一致节点力（2×2 高斯积分，任意沿高剖面
  精确成立）；散体壁摩擦 τ=pₙ·tanδ 为竖直切向一致节点力；自重 ρ·t·g；
- 约束：侧壁底边（与底板交接的底环）6 向固支；底板内部节点仅约束竖向
  （落于地基上的简化）；
- 求解：DOF ≤ 30000 用 scipy 稀疏直接法，超过用 CG + Jacobi 预条件；
- 应力恢复：单元中心求膜应变 ε 与曲率 κ，
  膜力 N = Dm·ε（N/m），弯矩 M = Db·κ（N），
  内外表面应力 σ = D0·ε ± (t/2)·D0·κ，von Mises 按平面应力状态计算。

验证
----
1. 固支/简支方板受均布载荷，与经典板理论系数对比：
   固支 w = 0.00126·q·a⁴/D，简支 w = 0.00406·q·a⁴/D，
   D = E·t³/(12(1−ν²))（见 tests/test_shell.py）；
2. 水箱侧壁按竖直悬臂板条（单位宽度）受三角形水压力：
   根部弯矩 M = ρg·h³/6，表面弯曲应力 σ = ρg·h³/t²，
   顶端挠度 δ = 2·q0·h⁴/(5·E·t³)，q0 = ρg·h；
3. 圆柱仓环向薄膜 σθ=p(h)·r/t（壁面中部、避开固支边界层）。

局限（当前版本）
----------------
- 截面：矩形箱（5 块平板）与直立圆筒 + 平底板；锥斗/多边形截面待后续；
- 填料：流体静力 + 散体 Rankine/Janssen 静侧压与壁摩擦；不考虑卸料成拱、
  偏心装料超压、筒仓振动（silo quake）等动力/料流效应；
- 线弹性小变形静力；不考虑屈曲（外压/真空失稳）、晃动、加劲肋、
  液体-结构耦合动力效应；
- 焊缝/转角处按节点连续建模，未模拟角部构造细节与应力集中。

单位：长度 m、力 N、应力 Pa；对外 JSON 的位移为 mm、应力为 MPa。
"""
from __future__ import annotations

import math
import time
from typing import Any, Callable, Dict, List, Optional, Sequence, Tuple

import numpy as np
import scipy.sparse as sp
import scipy.sparse.linalg as spla

from . import fills as fills_mod
from .flow import GRAVITY, _tension_limit
from .i18n import PSI_PER_MPA, is_en, is_imperial
from .materials import MaterialsLibrary, load_library

# 安全判定阈值（与 solid.py 一致）
SAFETY_SAFE = 2.0
SAFETY_CAUTION = 1.2

# 热冲击提示阈值：脆性材料 + 壁厚温差 ≥ 该值（K）时在结论/建议中提示风险
THERMAL_SHOCK_DT_K = 50.0

# 直接法/迭代法切换阈值。壳刚度含转动 DOF、稀疏性不如纯实体，直接法在
# ~9k DOF 时已明显劣化（实测 spsolve 13.8s vs CG 1.7s），故壳模型阈值取 4000：
# coarse 直接法，medium 及以上 CG + Jacobi 对角预条件（tol 1e-8，与 spsolve
# 的位移差约 1e-13）。
CG_DOF_THRESHOLD = 4000
CG_TOLERANCE = 1e-8

# 钻孔自由度稳定刚度系数（绕板法线的转动，物理上无刚度）
DRILLING_ALPHA = 1.0e-3

# 容器网格档位：nv 为壁竖直方向分段；特征边长 = 高度 / nv，
# 水平方向与底板分段按特征边长派生（薄壁壳单元很便宜，仍控制在端上预算内）
TANK_MESH_LEVELS = {
    "coarse": {"nv": 10},
    "medium": {"nv": 16},
    "fine": {"nv": 24},
    "ultra": {"nv": 32},
}

# 4 节点母单元 ξ,η ∈ [-1,1]，节点顺序：(-,-),(+,-),(+,+),(-,+)
_NODE_SIGN = np.array(
    [[-1.0, -1.0], [1.0, -1.0], [1.0, 1.0], [-1.0, 1.0]]
)
_GP = 1.0 / math.sqrt(3.0)
_GAUSS_POINTS = ((-_GP, -_GP, 1.0), (_GP, -_GP, 1.0),
                 (_GP, _GP, 1.0), (-_GP, _GP, 1.0))

# 局部 5 DOF 排布：[u, v, w, βx, βy]
# 整体 6 DOF 排布：[Ux, Uy, Uz, Rx, Ry, Rz]
_MAP5_TO_6 = np.array([0, 1, 2, 3, 4,
                       6, 7, 8, 9, 10,
                       12, 13, 14, 15, 16,
                       18, 19, 20, 21, 22])
_DRILL_SLOTS = np.array([5, 11, 17, 23])


# ====================================================================== 网格
class _NodeMap:
    """坐标键控的节点去重表：落在同一几何点（1e-9 相对容差）的网格共享节点。"""

    def __init__(self) -> None:
        self._coords: List[np.ndarray] = []
        self._index: Dict[Tuple[int, int, int], int] = {}

    def get(self, x: float, y: float, z: float) -> int:
        key = (round(float(x), 9), round(float(y), 9), round(float(z), 9))
        nid = self._index.get(key)
        if nid is None:
            nid = len(self._coords)
            self._index[key] = nid
            self._coords.append(np.array([x, y, z], dtype=float))
        return nid

    def array(self) -> np.ndarray:
        return np.asarray(self._coords, dtype=float)


def add_plate(
    nodemap: _NodeMap,
    origin: np.ndarray,
    e1: np.ndarray,
    e2: np.ndarray,
    span1: float,
    span2: float,
    n1: int,
    n2: int,
) -> Tuple[np.ndarray, np.ndarray]:
    """在整体坐标系中铺一块结构化四边形板中面。

    P(i,j) = origin + (i/n1)·span1·e1 + (j/n2)·span2·e2，
    要求 e1×e2 指向该板的"外法线"方向（水箱各面已按此约定）。
    返回 (grid_ids (n2+1, n1+1), quads (n1·n2, 4))，
    四边形节点顺序绕外法线逆时针。
    """
    n1, n2 = int(n1), int(n2)
    if n1 < 1 or n2 < 1:
        raise ValueError(f"板网格分段必须 ≥ 1：n1={n1}, n2={n2}")
    grid = np.zeros((n2 + 1, n1 + 1), dtype=int)
    for j in range(n2 + 1):
        for i in range(n1 + 1):
            p = (origin
                 + (float(i) / n1) * float(span1) * e1
                 + (float(j) / n2) * float(span2) * e2)
            grid[j, i] = nodemap.get(float(p[0]), float(p[1]), float(p[2]))
    quads = np.zeros((n1 * n2, 4), dtype=int)
    k = 0
    for j in range(n2):
        for i in range(n1):
            quads[k] = [grid[j, i], grid[j, i + 1],
                        grid[j + 1, i + 1], grid[j + 1, i]]
            k += 1
    return grid, quads


def build_tank_mesh(
    length: float,
    width: float,
    height: float,
    thickness: float,
    nv: int,
    el_size: Optional[float] = None,
) -> Dict[str, Any]:
    """开口矩形水箱中面网格：4 块侧壁 + 1 块底板，棱线/底环节点共享。

    几何：x∈[0,length]、z∈[0,width]、y∈[0,height]，板按中面定位
    （t≪L 的薄壁假设下忽略壁厚偏心）。

    参数
    ----
    nv : 侧壁竖直方向分段数
    el_size : 目标特征边长；None 时取 height/nv。水平方向分段按
        max(2, round(span/el_size)) 派生。

    返回 dict：
        nodes (n,3)、quads (m,4)、ex/ey/en (m,3) 各单元局部基、
        wall (m,) 板编号 0..3=侧壁、4=底板、
        base_ring_nodes 侧壁底环节点（6 向固支）、
        bottom_inner_nodes 底板内部节点（仅竖向约束）、
        segments 实际分段数。
    """
    if min(length, width, height, thickness) <= 0.0:
        raise ValueError(
            f"水箱尺寸必须为正：length={length}, width={width}, "
            f"height={height}, thickness={thickness}"
        )
    nv = int(nv)
    if nv < 2:
        raise ValueError(f"侧壁竖直分段 nv 必须 ≥ 2：{nv}")
    if el_size is None:
        el_size = float(height) / nv
    el_size = float(el_size)
    nl = max(2, int(round(float(length) / el_size)))
    nw = max(2, int(round(float(width) / el_size)))

    x = np.array([1.0, 0.0, 0.0])
    y = np.array([0.0, 1.0, 0.0])
    z = np.array([0.0, 0.0, 1.0])

    nodemap = _NodeMap()
    quads_list: List[np.ndarray] = []
    tags: List[int] = []

    # --- 4 块侧壁（tag 0..3）。e2 一律 +y，e1×e2=外法线 ---
    # tag 0: x=0 面，外法线 -x → e1=+z，原点 (0,0,0)
    g0, q0 = add_plate(nodemap, np.zeros(3), z, y, width, height, nw, nv)
    # tag 1: x=L 面，外法线 +x → e1=-z，原点 (L,0,W)
    g1, q1 = add_plate(nodemap, np.array([length, 0.0, width]), -z, y,
                       width, height, nw, nv)
    # tag 2: z=0 面，外法线 -z → e1=-x，原点 (L,0,0)
    g2, q2 = add_plate(nodemap, np.array([length, 0.0, 0.0]), -x, y,
                       length, height, nl, nv)
    # tag 3: z=W 面，外法线 +z → e1=+x，原点 (0,0,W)
    g3, q3 = add_plate(nodemap, np.array([0.0, 0.0, width]), x, y,
                       length, height, nl, nv)
    for tag, q in ((0, q0), (1, q1), (2, q2), (3, q3)):
        quads_list.append(q)
        tags.append(np.full(len(q), tag, dtype=int))

    # 侧壁底环节点（j=0 行，与底板四边共享）
    base_ring = np.unique(np.concatenate(
        [g0[0], g1[0], g2[0], g3[0]]
    ))

    # --- 底板（tag 4）：y=0，e1=+x、e2=+z，e1×e2 = -y = 外法线（朝下）---
    g4, q4 = add_plate(nodemap, np.zeros(3), x, z, length, width, nl, nw)
    quads_list.append(q4)
    tags.append(np.full(len(q4), 4, dtype=int))

    bottom_perimeter = np.unique(np.concatenate(
        [g4[0], g4[-1], g4[:, 0], g4[:, -1]]
    ))
    bottom_inner = np.setdiff1d(g4.ravel(), bottom_perimeter)

    nodes = nodemap.array()
    quads = np.vstack(quads_list)
    wall = np.concatenate(tags)

    # 每块板的局部基（与上面定义一一对应）
    bases = {
        0: (z, y, -x),
        1: (-z, y, x),
        2: (-x, y, -z),
        3: (x, y, z),
        4: (x, z, -y),
    }
    m = len(quads)
    ex = np.zeros((m, 3))
    ey = np.zeros((m, 3))
    en = np.zeros((m, 3))
    for tag, (a, b, n) in bases.items():
        sel = wall == tag
        ex[sel] = a
        ey[sel] = b
        en[sel] = n

    # 几何自检：所有单元雅可比为正（节点顺序绕外法线逆时针）
    if not _all_positive_jacobian(nodes, quads, ex, ey):
        raise ValueError("水箱网格存在反向/退化四边形，请检查几何尺寸与分段")

    # 根部外推行：每块侧壁单元按 j-major 排列（add_plate 约定），
    # base_groups：每片壁 [底排, 二排, 三排] 的单元索引（根部应力外推用）；
    # base_rows 为其展平版本（通用取用/调试）。
    n1_for_tag = {0: nw, 1: nw, 2: nl, 3: nl}
    base_groups: List[List[np.ndarray]] = []
    for tag in (0, 1, 2, 3):
        ids = np.where(wall == tag)[0]
        n1 = n1_for_tag[tag]
        group = [ids[:n1]]
        if len(ids) >= 2 * n1:
            group.append(ids[n1:2 * n1])
        if len(ids) >= 3 * n1:
            group.append(ids[2 * n1:3 * n1])
        base_groups.append(group)
    base_rows = [row for group in base_groups for row in group]

    area = length * width
    perimeter = 2.0 * (length + width)
    return {
        "nodes": nodes,
        "quads": quads,
        "ex": ex,
        "ey": ey,
        "en": en,
        "wall": wall,
        "floor_tag": 4,
        "base_ring_nodes": base_ring,
        "bottom_inner_nodes": bottom_inner,
        "base_rows": base_rows,
        "base_groups": base_groups,
        "wall_around": [nw, nw, nl, nl],
        "segments": {"nl": nl, "nw": nw, "nv": nv},
        "vertical_el": height / nv,
        "plan_area_m2": area,
        "perimeter_m": perimeter,
        "hydraulic_radius_m": area / perimeter,
    }


def build_rect_plate(
    length: float,
    width: float,
    n1: int,
    n2: int,
    origin: Sequence[float] = (0.0, 0.0, 0.0),
    e1: Sequence[float] = (1.0, 0.0, 0.0),
    e2: Sequence[float] = (0.0, 1.0, 0.0),
) -> Dict[str, Any]:
    """单块矩形平板中面网格（供板单元基准测试/后续通用板功能使用）。

    返回与 build_tank_mesh 同构的 dict（无 base_ring/bottom_inner）。
    """
    if length <= 0.0 or width <= 0.0:
        raise ValueError("板长宽必须为正")
    e1v = np.asarray(e1, dtype=float)
    e2v = np.asarray(e2, dtype=float)
    en = np.cross(e1v, e2v)
    nn = float(np.linalg.norm(en))
    if nn < 1e-12:
        raise ValueError("板局部基 e1、e2 不能平行")
    en = en / nn
    nodemap = _NodeMap()
    grid, quads = add_plate(nodemap, np.asarray(origin, dtype=float),
                            e1v, e2v, length, width, int(n1), int(n2))
    nodes = nodemap.array()
    m = len(quads)
    ex = np.tile(e1v / np.linalg.norm(e1v), (m, 1))
    ey = np.tile(e2v / np.linalg.norm(e2v), (m, 1))
    env = np.tile(en, (m, 1))
    if not _all_positive_jacobian(nodes, quads, ex, ey):
        raise ValueError("平板网格存在反向/退化四边形")
    return {
        "nodes": nodes, "quads": quads, "ex": ex, "ey": ey, "en": env,
        "grid": grid,
    }


def _all_positive_jacobian(
    nodes: np.ndarray, quads: np.ndarray, ex: np.ndarray, ey: np.ndarray
) -> bool:
    """检查所有四边形在 2×2 高斯点上的中面雅可比为正。"""
    p_l, _ = _local_coords(nodes, quads, ex, ey)
    for xi, eta, _w in _GAUSS_POINTS:
        _, dndxi, dndeta = _shapes(xi, eta)
        # 母单元导数 → 局部面内雅可比（对每块板只需标量 det）
        x_xi = p_l[:, :, 0] @ dndxi
        x_eta = p_l[:, :, 0] @ dndeta
        y_xi = p_l[:, :, 1] @ dndxi
        y_eta = p_l[:, :, 1] @ dndeta
        det = x_xi * y_eta - x_eta * y_xi
        if np.any(det <= 0.0):
            return False
    return True


# --------------------------------------------------------------- 圆柱仓几何
def build_cylinder_mesh(
    radius: float,
    height: float,
    thickness: float,
    nc: int,
    nv: int,
    nr: int,
) -> Dict[str, Any]:
    """开口直立圆柱仓中面网格：圆筒壁 + 平底板。

    壁面：环×层四边形（周向 nc 段，竖直 nv 段），i=nc 焊缝与 i=0 共用节点
    （坐标键控去重）；平面小面逼近曲面，建议 nc≥24，膜应力精度即足够。
    底板：nr 圈同心环四边形，最内圈两节点重合于圆心（"收缩四边形"，
    2×2 高斯点不落在退化点上），最外圈与壁底环节点共享。

    坐标约定与矩形水箱一致：y 竖直向上、底在 y=0；壁板外法线为径向 +e_r。
    壁单元局部基：ex=−e_θ（环向）、ey=+y、en=+e_r；
    底板局部基：ex=+e_r、ey=+e_θ、en=−y（朝下）。
    因此单元局部 σx 即环向应力 σθ、σy 即竖直应力 σz。

    tag：0=圆筒壁、1=底板。返回与 build_tank_mesh 同构的 dict，另含
    radius/plan_area/perimeter/hydraulic_radius 与 base_rows（根部外推用）。
    """
    if radius <= 0.0 or height <= 0.0 or thickness <= 0.0:
        raise ValueError("圆柱仓半径/高度/壁厚必须为正")
    nc, nv, nr = int(nc), int(nv), int(nr)
    if nc < 12:
        raise ValueError(f"周向分段 nc 必须 ≥ 12（建议 ≥24）：{nc}")
    if nv < 2 or nr < 2:
        raise ValueError(f"竖直/径向分段必须 ≥ 2：nv={nv}, nr={nr}")

    nodemap = _NodeMap()

    def polar_id(r: float, j: float) -> List[int]:
        ids = []
        for i in range(nc + 1):
            theta = 2.0 * math.pi * i / nc
            ids.append(nodemap.get(r * math.cos(theta), float(j),
                                   r * math.sin(theta)))
        return ids

    # ---- 圆筒壁 ----
    rings = [polar_id(radius, height * j / nv) for j in range(nv + 1)]
    wall_quads = np.zeros((nv * nc, 4), dtype=int)
    wall_bases = np.zeros((nv * nc, 3, 3))
    k = 0
    for j in range(nv):
        for i in range(nc):
            wall_quads[k] = [rings[j][i], rings[j + 1][i],
                             rings[j + 1][i + 1], rings[j][i + 1]]
            tc = 2.0 * math.pi * (i + 0.5) / nc
            wall_bases[k, 0] = (math.sin(tc), 0.0, -math.cos(tc))  # -eθ
            wall_bases[k, 1] = (0.0, 1.0, 0.0)                    # +y
            wall_bases[k, 2] = (math.cos(tc), 0.0, math.sin(tc))  # +e_r
            k += 1

    # ---- 底板（同心环；k=0 整圈收缩到圆心）----
    disk = [polar_id(radius * kk / nr, 0.0) for kk in range(nr + 1)]
    floor_quads = np.zeros((nr * nc, 4), dtype=int)
    floor_bases = np.zeros((nr * nc, 3, 3))
    k = 0
    for kk in range(nr):
        for i in range(nc):
            floor_quads[k] = [disk[kk][i], disk[kk + 1][i],
                              disk[kk + 1][i + 1], disk[kk][i + 1]]
            tc = 2.0 * math.pi * (i + 0.5) / nc
            floor_bases[k, 0] = (math.cos(tc), 0.0, math.sin(tc))  # +e_r
            floor_bases[k, 1] = (-math.sin(tc), 0.0, math.cos(tc))  # +eθ
            floor_bases[k, 2] = (0.0, -1.0, 0.0)                   # -y
            k += 1

    quads = np.vstack([wall_quads, floor_quads])
    ex = np.vstack([wall_bases[:, 0, :], floor_bases[:, 0, :]])
    ey = np.vstack([wall_bases[:, 1, :], floor_bases[:, 1, :]])
    en = np.vstack([wall_bases[:, 2, :], floor_bases[:, 2, :]])
    wall = np.concatenate([
        np.zeros(len(wall_quads), dtype=int),
        np.ones(len(floor_quads), dtype=int)])
    nodes = nodemap.array()

    if not _all_positive_jacobian(nodes, quads, ex, ey):
        raise ValueError("圆柱仓网格存在反向/退化四边形，请检查分段参数")

    ring0 = np.unique(np.array(rings[0], dtype=int))
    perimeter_nodes = np.unique(np.array(disk[nr], dtype=int))
    disk_all = np.unique(np.array(
        [nid for ring in disk for nid in ring], dtype=int))
    bottom_inner = np.setdiff1d(disk_all, perimeter_nodes)

    # 根部外推行：壁面单元在 quads 前部且按 j-major 排列，故 j 排单元索引即
    # j·nc .. (j+1)·nc−1；base_groups 为单片圆筒壁的 [底排, 二排, 三排]。
    cyl_group = [np.arange(0, nc), np.arange(nc, 2 * nc)]
    if nv >= 3:
        cyl_group.append(np.arange(2 * nc, 3 * nc))
    base_groups = [cyl_group]
    base_rows = list(cyl_group)

    area = math.pi * radius ** 2
    perimeter = 2.0 * math.pi * radius
    return {
        "nodes": nodes, "quads": quads, "ex": ex, "ey": ey, "en": en,
        "wall": wall,
        "floor_tag": 1,
        "base_ring_nodes": ring0,
        "bottom_inner_nodes": bottom_inner,
        "base_rows": base_rows,
        "base_groups": base_groups,
        "wall_around": [nc],
        "segments": {"nc": nc, "nv": nv, "nr": nr},
        "vertical_el": height / nv,
        "plan_area_m2": area,
        "perimeter_m": perimeter,
        "hydraulic_radius_m": radius / 2.0,
    }


# ====================================================================== 单元
def _shapes(xi: float, eta: float) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """双线性形函数及其母单元导数（均为 (4,)）。"""
    s = _NODE_SIGN
    n = 0.25 * (1.0 + s[:, 0] * xi) * (1.0 + s[:, 1] * eta)
    dndxi = 0.25 * s[:, 0] * (1.0 + s[:, 1] * eta)
    dndeta = 0.25 * s[:, 1] * (1.0 + s[:, 0] * xi)
    return n, dndxi, dndeta


def _local_coords(
    nodes: np.ndarray, quads: np.ndarray, ex: np.ndarray, ey: np.ndarray
) -> Tuple[np.ndarray, np.ndarray]:
    """整体节点坐标 → 各单元局部中面坐标 (p_l (m,4,2), 参考点 p0 (m,3))。"""
    p = nodes[quads]  # (m,4,3)
    p0 = p[:, 0, :]
    d = p - p0[:, None, :]
    xl = np.einsum("emi,e i->em", d, ex)
    yl = np.einsum("emi,e i->em", d, ey)
    p_l = np.stack([xl, yl], axis=2)
    return p_l, p0


def _b_matrices(
    xi: float, eta: float, p_l: np.ndarray, strict: bool = True
) -> Tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """给定母单元点，计算膜/弯/剪（直接）B 矩阵与雅可比。

    返回 Bm (m,3,20)、Bb (m,3,20)、Bs (m,2,20)、detJ (m,)、N (m,4)。
    strict=False 时不对退化（雅可比≤0）单元报错——供 MITC 边中点取样探测
    圆心"收缩四边形"使用（其退化边上雅可比恰为 0）。
    """
    m = p_l.shape[0]
    n4, dndxi, dndeta = _shapes(xi, eta)

    x_xi = p_l[:, :, 0] @ dndxi
    x_eta = p_l[:, :, 0] @ dndeta
    y_xi = p_l[:, :, 1] @ dndxi
    y_eta = p_l[:, :, 1] @ dndeta
    det = x_xi * y_eta - x_eta * y_xi
    if strict and np.any(det <= 0.0):
        raise ValueError("发现负面积/退化四边形单元")
    # 非严格模式下退化行雅可比为 0：安全求逆（该行结果会被调用方丢弃）
    inv = 1.0 / np.where(det > 0.0, det, 1.0)
    dnx = (y_eta[:, None] * dndxi[None, :]
           - y_xi[:, None] * dndeta[None, :]) * inv[:, None]
    dny = (-x_eta[:, None] * dndxi[None, :]
           + x_xi[:, None] * dndeta[None, :]) * inv[:, None]

    bm = np.zeros((m, 3, 20))
    bb = np.zeros((m, 3, 20))
    bs = np.zeros((m, 2, 20))
    for i in range(4):
        b = 5 * i
        bm[:, 0, b] = dnx[:, i]
        bm[:, 1, b + 1] = dny[:, i]
        bm[:, 2, b] = dny[:, i]
        bm[:, 2, b + 1] = dnx[:, i]

        bb[:, 0, b + 3] = dnx[:, i]
        bb[:, 1, b + 4] = dny[:, i]
        bb[:, 2, b + 3] = dny[:, i]
        bb[:, 2, b + 4] = dnx[:, i]

        bs[:, 0, b + 2] = dnx[:, i]
        bs[:, 0, b + 3] = n4[i]
        bs[:, 1, b + 2] = dny[:, i]
        bs[:, 1, b + 4] = n4[i]
    return bm, bb, bs, det, n4


def _mitc_shear_b(
    xi: float, eta: float, p_l: np.ndarray
) -> np.ndarray:
    """MITC4 绑定剪应变 B 矩阵（Dvork–Bathe 列式）。

    γx̃ 在 η=±1 边中点 A/C 取样后按 η 线性插值；
    γỹ 在 ξ=±1 边中点 D/B 取样后按 ξ 线性插值。
    """
    # strict=False：圆心收缩四边形在退化边上雅可比为 0，取样值含 inf，
    # 这类单元不参与绑定插值，统一在最后退回高斯点直接剪切 B。
    _, _, bs_a, da, _ = _b_matrices(0.0, 1.0, p_l, strict=False)
    _, _, bs_c, dc, _ = _b_matrices(0.0, -1.0, p_l, strict=False)
    _, _, bs_b, db, _ = _b_matrices(-1.0, 0.0, p_l, strict=False)
    _, _, bs_d, dd, _ = _b_matrices(1.0, 0.0, p_l, strict=False)
    degenerate = (da <= 0.0) | (dc <= 0.0) | (db <= 0.0) | (dd <= 0.0)
    out = np.zeros((p_l.shape[0], 2, 20))
    out[:, 0, :] = (0.5 * (1.0 + eta) * bs_a[:, 0, :]
                    + 0.5 * (1.0 - eta) * bs_c[:, 0, :])
    out[:, 1, :] = (0.5 * (1.0 + xi) * bs_d[:, 1, :]
                    + 0.5 * (1.0 - xi) * bs_b[:, 1, :])
    if np.any(degenerate):
        # 收缩单元按三角形拓扑工作：直接用当前高斯点的横向剪切插值
        _, _, bs_g, dg, _ = _b_matrices(xi, eta, p_l, strict=False)
        out[degenerate] = bs_g[degenerate]
        if np.any(dg[degenerate] <= 0.0):
            raise ValueError("圆心收缩四边形在内部高斯点仍退化，请加密径向分段")
    return out


def _constitutive(
    E: float, nu: float, t: np.ndarray
) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """膜 Dm (m,3,3)、弯曲 Db (m,3,3)、剪切 Ds (m,2,2)。"""
    c = float(E) / (1.0 - float(nu) ** 2)
    g = float(E) / (2.0 * (1.0 + float(nu)))
    tt = np.asarray(t, dtype=float)
    m = tt.shape[0]
    dm = np.zeros((m, 3, 3))
    dm[:, 0, 0] = c * tt
    dm[:, 1, 1] = c * tt
    dm[:, 0, 1] = c * nu * tt
    dm[:, 1, 0] = c * nu * tt
    dm[:, 2, 2] = 0.5 * c * (1.0 - nu) * tt

    db = np.zeros((m, 3, 3))
    kb = c * tt ** 3 / 12.0
    db[:, 0, 0] = kb
    db[:, 1, 1] = kb
    db[:, 0, 1] = nu * kb
    db[:, 1, 0] = nu * kb
    db[:, 2, 2] = 0.5 * c * (1.0 - nu) * tt ** 3 / 12.0

    ds = np.zeros((m, 2, 2))
    ks = (5.0 / 6.0) * g * tt
    ds[:, 0, 0] = ks
    ds[:, 1, 1] = ks
    return dm, db, ds


def _element_stiffness_local(
    p_l: np.ndarray, t: np.ndarray, E: float, nu: float
) -> np.ndarray:
    """局部坐标系单元刚度 (m,20,20)：膜 + 弯（2×2 高斯）+ MITC4 剪应变。"""
    dm, db, ds = _constitutive(E, nu, t)
    ke = np.zeros((p_l.shape[0], 20, 20))
    for xi, eta, wgt in _GAUSS_POINTS:
        bm, bb, _, det, _ = _b_matrices(xi, eta, p_l)
        bs = _mitc_shear_b(xi, eta, p_l)
        w = (wgt * det)[:, None, None]
        ke += w * (np.swapaxes(bm, 1, 2) @ (dm @ bm))
        ke += w * (np.swapaxes(bb, 1, 2) @ (db @ bb))
        ke += w * (np.swapaxes(bs, 1, 2) @ (ds @ bs))
    return ke


def _transform_block(ex: np.ndarray, ey: np.ndarray, en: np.ndarray
                     ) -> np.ndarray:
    """每单元局部 6 DOF → 整体 6 DOF 的变换 T (m,24,24)，u_g = T·u_l。"""
    m = ex.shape[0]
    q = np.zeros((m, 6, 6))
    q[:, :3, 0] = ex
    q[:, :3, 1] = ey
    q[:, :3, 2] = en
    q[:, 3:, 3] = ex
    q[:, 3:, 4] = ey
    q[:, 3:, 5] = en
    tmat = np.zeros((m, 24, 24))
    for a in range(4):
        tmat[:, 6 * a:6 * a + 6, 6 * a:6 * a + 6] = q
    return tmat


def assemble_stiffness(
    nodes: np.ndarray,
    quads: np.ndarray,
    ex: np.ndarray,
    ey: np.ndarray,
    en: np.ndarray,
    thickness: Any,
    E: float,
    nu: float,
    drilling_alpha: float = DRILLING_ALPHA,
) -> Tuple[sp.csr_matrix, np.ndarray]:
    """组装整体壳刚度（每节点 6 DOF）。返回 (K, gdof (m,24))。"""
    m = len(quads)
    tv = np.asarray(thickness, dtype=float)
    t_arr = np.full(m, float(tv)) if tv.ndim == 0 else tv
    p_l, _ = _local_coords(nodes, quads, ex, ey)
    ke5 = _element_stiffness_local(p_l, t_arr, float(E), float(nu))

    # 5 DOF → 6 DOF 嵌入 + 钻孔稳定刚度
    ke6 = np.zeros((m, 24, 24))
    ke6[:, _MAP5_TO_6[:, None], _MAP5_TO_6[None, :]] = ke5
    area = _element_area(p_l)
    kd = drilling_alpha * float(E) * t_arr * area / 4.0  # 每节点一份
    for slot in _DRILL_SLOTS:
        ke6[:, slot, slot] += kd

    tmat = _transform_block(ex, ey, en)
    # 整体单元刚度：Kg = T·Kl·Tᵀ（T 为局部 6 DOF → 整体 6 DOF 变换）
    keg = np.einsum("mij,mjk,mlk->mil", tmat, ke6, tmat)

    gdof = np.zeros((m, 24), dtype=int)
    for d in range(6):
        gdof[:, d::6] = quads * 6 + d
    n_dof = 6 * len(nodes)
    rows = np.repeat(gdof, 24, axis=1).ravel()
    cols = np.tile(gdof, (1, 24)).ravel()
    K = sp.coo_matrix((keg.ravel(), (rows, cols)), shape=(n_dof, n_dof)
                      ).tocsr()
    return K, gdof


def _element_area(p_l: np.ndarray) -> np.ndarray:
    """单元中面面积 (m,)（对平行四边形精确，一般四边形用 2×2 高斯积分）。"""
    area = np.zeros(p_l.shape[0])
    for xi, eta, wgt in _GAUSS_POINTS:
        _, dndxi, dndeta = _shapes(xi, eta)
        x_xi = p_l[:, :, 0] @ dndxi
        x_eta = p_l[:, :, 0] @ dndeta
        y_xi = p_l[:, :, 1] @ dndxi
        y_eta = p_l[:, :, 1] @ dndeta
        area += wgt * (x_xi * y_eta - x_eta * y_xi)
    return area


# ====================================================================== 载荷
def apply_pressure(
    f: np.ndarray,
    nodes: np.ndarray,
    quads: np.ndarray,
    ex: np.ndarray,
    ey: np.ndarray,
    en: np.ndarray,
    thickness: Any,
    elements: np.ndarray,
    magnitude: Any,
    sign: float = 1.0,
) -> np.ndarray:
    """法向压力 → 一致节点力（2×2 高斯）。

    magnitude : 常数（Pa）或 callable(world_xyx (k,3)) -> (k,) 压力标量；
    sign=+1 沿板外法线 en，sign=-1 反向（侧壁水压力指向箱内）。
    返回施加的合力矢量 (3,)。
    """
    idx = np.asarray(elements, dtype=int)
    if idx.size == 0:
        return np.zeros(3)
    p = nodes[quads[idx]]  # (k,4,3)
    p_l, _ = _local_coords(nodes, quads[idx], ex[idx], ey[idx])
    total = np.zeros(3)
    for xi, eta, wgt in _GAUSS_POINTS:
        _, _, _, det, n4 = _b_matrices(xi, eta, p_l)
        xyz = n4 @ p  # (k,3)
        if callable(magnitude):
            press = np.asarray(magnitude(xyz), dtype=float)
        else:
            press = np.full(len(idx), float(magnitude))
        # 每节点的力矢量 = w·det·N_i·p·(±en)
        for i in range(4):
            w = (wgt * det * n4[i] * press * float(sign))[:, None] \
                * en[idx]
            np.add.at(f, quads[idx, i] * 6 + 0, w[:, 0])
            np.add.at(f, quads[idx, i] * 6 + 1, w[:, 1])
            np.add.at(f, quads[idx, i] * 6 + 2, w[:, 2])
            total += w.sum(axis=0)
    return total


def apply_shear_traction(
    f: np.ndarray,
    nodes: np.ndarray,
    quads: np.ndarray,
    ex: np.ndarray,
    ey: np.ndarray,
    elements: np.ndarray,
    magnitude: Any,
    dirs: np.ndarray,
) -> np.ndarray:
    """壁面切向面力（散体壁摩擦）→ 一致节点力（2×2 高斯）。

    magnitude 为常数 Pa，或 callable(world_xyz (k,3)) -> (k,)；
    dirs (k,3) 为每单元切向单位矢量（整体坐标，竖壁填料摩擦为 (0,-1,0)）。
    要求 dirs 已单位化（本函数不再归一）。返回施加的合力矢量 (3,)。
    """
    idx = np.asarray(elements, dtype=int)
    if idx.size == 0:
        return np.zeros(3)
    dirs = np.asarray(dirs, dtype=float)
    if dirs.shape == (3,):
        dirs = np.tile(dirs, (len(idx), 1))
    if not callable(magnitude):
        mag_const = float(magnitude)

        def magnitude(xyz):  # type: ignore
            return np.full(len(xyz), mag_const)
    p_l, _ = _local_coords(nodes, quads[idx], ex[idx], ey[idx])
    p = nodes[quads[idx]]
    total = np.zeros(3)
    for xi, eta, wgt in _GAUSS_POINTS:
        _, _, _, det, n4 = _b_matrices(xi, eta, p_l)
        xyz = n4 @ p
        tau = np.asarray(magnitude(xyz), dtype=float)
        for i in range(4):
            w = (wgt * det * n4[i] * tau)[:, None] * dirs
            np.add.at(f, quads[idx, i] * 6 + 0, w[:, 0])
            np.add.at(f, quads[idx, i] * 6 + 1, w[:, 1])
            np.add.at(f, quads[idx, i] * 6 + 2, w[:, 2])
            total += w.sum(axis=0)
    return total


def apply_shell_body_force(
    f: np.ndarray,
    nodes: np.ndarray,
    quads: np.ndarray,
    ex: np.ndarray,
    ey: np.ndarray,
    thickness: Any,
    density: float,
    accel: Sequence[float],
) -> np.ndarray:
    """壳体自重：面密度 ρ·t 上的分布力（N/m² 中面），返回合力 (3,)。"""
    tv = np.asarray(thickness, dtype=float)
    t_arr = np.full(len(quads), float(tv)) if tv.ndim == 0 else tv
    p_l, _ = _local_coords(nodes, quads, ex, ey)
    gvec = np.asarray(accel, dtype=float)
    total = np.zeros(3)
    for xi, eta, wgt in _GAUSS_POINTS:
        _, _, _, det, n4 = _b_matrices(xi, eta, p_l)
        for i in range(4):
            w = (wgt * det * n4[i] * density * t_arr)[:, None] * gvec
            np.add.at(f, quads[:, i] * 6 + 0, w[:, 0])
            np.add.at(f, quads[:, i] * 6 + 1, w[:, 1])
            np.add.at(f, quads[:, i] * 6 + 2, w[:, 2])
            total += w.sum(axis=0)
    return total


# ====================================================================== 热载荷
# 稳态壁厚温差：温度沿壁厚线性分布 T(z)=T_mid + (T_out−T_in)/t · z，
# z∈[−t/2, +t/2]，局部 +z（=en 外法线侧）为外露面 T_out、−z 为浸湿面 T_in。
# 分解为中面均匀温升 ΔT_m=(T_in+T_out)/2−T_ref 与壁厚温差 ΔT_g=T_in−T_out：
#   膜热应变 ε_th_m = α·ΔT_m·[1,1,0]  → 热膜力 N_th = Dm·ε_th_m
#   热曲率   κ_th   = α·(T_out−T_in)·[1,1,0]（M_th = Db·κ_th，因 Db=D0·t³/12）
# 等效节点热载荷 f_th = ∫(Bmᵀ·N_th + Bbᵀ·M_th) dA 加到右端；应力恢复时扣除
# α·(T(z)−T_ref)·[1,1,0]，保证自由板均匀温升→零应力、约束板→解析解。
def _parse_thermal(
    thermal: Optional[Dict[str, Any]], n_elem: int
) -> Optional[Tuple[float, np.ndarray, np.ndarray, float]]:
    """解析 thermal 字典 → (alpha, t_in(m,), t_out(m,), t_ref)；无效返回 None。"""
    if not thermal:
        return None
    alpha = float(thermal.get("alpha") or 0.0)
    if alpha <= 0.0:
        return None

    def _as_arr(v: Any) -> np.ndarray:
        a = np.asarray(v, dtype=float)
        if a.ndim == 0:
            a = np.full(n_elem, float(a))
        return a

    t_in = _as_arr(thermal.get("inner_temp_c"))
    t_out = _as_arr(thermal.get("outer_temp_c"))
    if t_in.shape != (n_elem,) or t_out.shape != (n_elem,):
        raise ValueError("thermal 温度数组长度必须等于单元数")
    t_ref = float(thermal.get("ref_temp_c", 20.0))
    return alpha, t_in, t_out, t_ref


def _assemble_thermal_load(
    p_l: np.ndarray,
    t_arr: np.ndarray,
    ex: np.ndarray,
    ey: np.ndarray,
    en: np.ndarray,
    E: float,
    nu: float,
    alpha: float,
    dT_m: np.ndarray,
    dT_grad: np.ndarray,
    gdof: np.ndarray,
    n_dof: int,
) -> np.ndarray:
    """组装全局热载荷向量 f_th (n_dof,)（与机械 f 同构，可叠加）。"""
    dm, db, _ = _constitutive(E, nu, t_arr)
    one_one_zero = np.array([1.0, 1.0, 0.0])
    eps_th_m = (alpha * dT_m)[:, None] * one_one_zero   # (m,3)
    kappa_th = (alpha * dT_grad)[:, None] * one_one_zero  # (m,3)
    n_th = np.einsum("mij,mj->mi", dm, eps_th_m)           # 热膜力 (m,3)
    m_th = np.einsum("mij,mj->mi", db, kappa_th)           # 热弯矩 (m,3)

    m_count = p_l.shape[0]
    f5 = np.zeros((m_count, 20))
    for xi, eta, wgt in _GAUSS_POINTS:
        bm, bb, _, det, _ = _b_matrices(xi, eta, p_l)
        wdet = (wgt * det)[:, None]
        f5 += wdet * (np.einsum("mji,mj->mi", bm, n_th)
                      + np.einsum("mji,mj->mi", bb, m_th))

    tmat = _transform_block(ex, ey, en)
    f6 = np.zeros((m_count, 24))
    f6[:, _MAP5_TO_6] = f5                       # 嵌入 6DOF（钻孔自由度无热载）
    fg = np.einsum("mij,mj->mi", tmat, f6)       # 局部→整体
    f_th = np.zeros(n_dof)
    np.add.at(f_th, gdof, fg)
    return f_th


# ====================================================================== 求解
def solve_shell(
    nodes: np.ndarray,
    quads: np.ndarray,
    ex: np.ndarray,
    ey: np.ndarray,
    en: np.ndarray,
    thickness: Any,
    E: float,
    nu: float,
    fixed: np.ndarray,
    pressures: Sequence[Dict[str, Any]] = (),
    shears: Sequence[Dict[str, Any]] = (),
    body_accel: Optional[Sequence[float]] = None,
    density: float = 0.0,
    prescribed: Optional[Dict[int, float]] = None,
    thermal: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """平板壳线弹性静力求解。

    pressures : [{"elements": 索引数组, "magnitude": Pa 或 callable, "sign": ±1}]
    shears    : [{"elements", "magnitude": callable(xyz)->Pa, "dirs": (k,3)}]
                 壁面切向面力（散体壁摩擦）。
    fixed : u=0 的整体 DOF 编号；prescribed : {dof: 非零位移值}。
    thermal : 可选稳态壁厚温差 {"alpha":1/K, "inner_temp_c", "outer_temp_c",
              "ref_temp_c"(缺省20)}；inner 为浸湿面（局部 −z）、outer 为外露面
              （局部 +z=en 侧），温度沿壁厚线性分布。None 或 alpha≤0 时不计热
              载荷，输出与无热能力逐位一致。
    返回位移、应力恢复量、反力、计时等（见 recover_stress）。
    """
    t_start = time.perf_counter()
    fixed = np.asarray(fixed, dtype=int)
    if fixed.size == 0 and not prescribed:
        raise ValueError("约束为空：壳体至少需要固支边以消除刚体位移")
    if E <= 0.0:
        raise ValueError(f"弹性模量必须为正：E={E}")
    if not (-0.999 < float(nu) < 0.5):
        raise ValueError(f"泊松比超出范围 (-1, 0.5)：nu={nu}")

    K, gdof = assemble_stiffness(nodes, quads, ex, ey, en, thickness, E, nu)
    f = np.zeros(6 * len(nodes))
    applied = np.zeros(3)
    for item in pressures:
        applied += apply_pressure(
            f, nodes, quads, ex, ey, en, thickness,
            np.asarray(item["elements"]),
            item.get("magnitude", 0.0),
            float(item.get("sign", 1.0)),
        )
    for item in shears:
        applied += apply_shear_traction(
            f, nodes, quads, ex, ey,
            np.asarray(item["elements"]),
            item["magnitude"], item["dirs"],
        )
    if body_accel is not None and density > 0.0:
        applied += apply_shell_body_force(
            f, nodes, quads, ex, ey, thickness, float(density), body_accel
        )

    # ---- 稳态热载荷（自平衡，加到右端；残差反力计算时一并扣除）----
    n_dof = 6 * len(nodes)
    th = _parse_thermal(thermal, len(quads))
    f_th = np.zeros(n_dof)
    th_alpha = 0.0
    th_t_in: Optional[np.ndarray] = None
    th_t_out: Optional[np.ndarray] = None
    th_t_ref = 20.0
    if th is not None:
        th_alpha, th_t_in, th_t_out, th_t_ref = th
        tv = np.asarray(thickness, dtype=float)
        t_arr = (np.full(len(quads), float(tv)) if tv.ndim == 0 else tv)
        p_l, _ = _local_coords(nodes, quads, ex, ey)
        dT_m = 0.5 * (th_t_in + th_t_out) - th_t_ref     # 中面均匀温升
        dT_grad = (th_t_out - th_t_in) / t_arr            # 壁厚温度梯度 dT/dz = (外−内)/t，量纲 K/m
        f_th = _assemble_thermal_load(
            p_l, t_arr, ex, ey, en, E, nu, th_alpha,
            dT_m, dT_grad, gdof, n_dof,
        )
    t_assembled = time.perf_counter()

    prescribed = prescribed or {}
    cons_map = {int(d): 0.0 for d in fixed}
    cons_map.update({int(d): float(v) for d, v in prescribed.items()})
    cons_dofs = np.asarray(sorted(cons_map), dtype=int)
    u_cons = np.asarray([cons_map[int(d)] for d in cons_dofs])
    free = np.setdiff1d(np.arange(n_dof), cons_dofs)

    rhs = (f + f_th)[free].copy()
    if cons_dofs.size:
        rhs -= K[free][:, cons_dofs] @ u_cons
    kff = K[free][:, free].tocsc()
    if free.size > CG_DOF_THRESHOLD:
        method = "cg+jacobi"
        diag = kff.diagonal()
        prec = sp.diags(1.0 / np.maximum(diag, 1e-30))
        try:
            x, info = spla.cg(kff, rhs, rtol=CG_TOLERANCE, M=prec,
                              maxiter=20000)
        except TypeError:  # scipy ≤1.11
            x, info = spla.cg(kff, rhs, tol=CG_TOLERANCE, M=prec,
                              maxiter=20000)
        if info != 0:
            raise RuntimeError(
                f"CG 迭代未收敛（info={info}），请降低网格档位或简化模型"
            )
    else:
        method = "direct(spsolve)"
        x = spla.spsolve(kff, rhs, permc_spec="MMD_AT_PLUS_A")

    u = np.zeros(n_dof)
    u[free] = x
    if cons_dofs.size:
        u[cons_dofs] = u_cons
    t_solved = time.perf_counter()

    if not np.all(np.isfinite(u)):
        raise ValueError(
            "求解结果无效（位移含 nan/inf），通常为约束不足导致结构整体漂移"
        )

    residual = K @ u - f - f_th
    reactions = np.array([
        float(residual[cons_dofs][(cons_dofs % 6) == i].sum())
        for i in range(3)
    ])

    stress = recover_stress(
        nodes, quads, ex, ey, en, thickness, E, nu, u, gdof,
        alpha=th_alpha, temp_inner=th_t_in, temp_outer=th_t_out,
        temp_ref=th_t_ref,
    )

    return {
        "u": u,
        "K": K,
        "f": f,
        "gdof": gdof,
        "applied_force": applied,
        "reaction_force": reactions,
        "dof": int(n_dof),
        "n_elements": int(len(quads)),
        "n_vertices": int(len(nodes)),
        "solver_method": method,
        "assemble_time_s": t_assembled - t_start,
        "solve_time_s": t_solved - t_assembled,
        "total_time_s": time.perf_counter() - t_start,
        **stress,
    }


# ====================================================================== 应力恢复
def recover_stress(
    nodes: np.ndarray,
    quads: np.ndarray,
    ex: np.ndarray,
    ey: np.ndarray,
    en: np.ndarray,
    thickness: Any,
    E: float,
    nu: float,
    u: np.ndarray,
    gdof: Optional[np.ndarray] = None,
    alpha: float = 0.0,
    temp_inner: Any = None,
    temp_outer: Any = None,
    temp_ref: float = 20.0,
) -> Dict[str, Any]:
    """单元中心应力恢复：膜力 N、弯矩 M、内外表面应力与 von Mises。

    局部坐标：x=e1、y=e2；侧壁 e2=+y，故 σyy 即竖直方向应力。
    alpha>0 且给定内/外温度时，在表面应力中扣除热应变 α·(T(z)−T_ref)·[1,1,0]
    （+z 外露面取 T_outer、−z 浸湿面取 T_inner），膜力/弯矩扣除热合量。
    """
    tv = np.asarray(thickness, dtype=float)
    t_arr = np.full(len(quads), float(tv)) if tv.ndim == 0 else tv
    p_l, _ = _local_coords(nodes, quads, ex, ey)
    if gdof is None:
        gdof = np.zeros((len(quads), 24), dtype=int)
        for d in range(6):
            gdof[:, d::6] = quads * 6 + d

    tmat = _transform_block(ex, ey, en)
    u_g = u[gdof]                                            # (m,24)
    u_l6 = np.einsum("mji,mj->mi", tmat, u_g)               # 整体→局部
    u5 = u_l6[:, _MAP5_TO_6]

    bm, bb, _, _, _ = _b_matrices(0.0, 0.0, p_l)
    eps = np.einsum("mij,mj->mi", bm, u5)                    # 膜应变 (m,3)
    kappa = np.einsum("mij,mj->mi", bb, u5)                  # 曲率 (m,3)

    c = float(E) / (1.0 - float(nu) ** 2)
    d0 = np.array([[c, c * nu, 0.0],
                   [c * nu, c, 0.0],
                   [0.0, 0.0, 0.5 * c * (1.0 - nu)]])
    sig_m = eps @ d0.T                                       # 中面膜应力 (m,3)
    sig_b = (0.5 * t_arr)[:, None] * (kappa @ d0.T)          # 表面弯曲应力幅

    sig_plus = sig_m + sig_b                                 # +z 面（外法线侧）
    sig_minus = sig_m - sig_b                                # -z 面

    # 热应变扣除：σ(z) = D0·(ε_m + z·κ − α·(T(z)−T_ref)·[1,1,0])
    # d0 @ [1,1,0] = c·(1+ν)·[1,1,0] = E/(1−ν)·[1,1,0]
    t_in_arr: Optional[np.ndarray] = None
    t_out_arr: Optional[np.ndarray] = None
    if alpha > 0.0 and temp_inner is not None and temp_outer is not None:
        _bi = np.array([c * (1.0 + nu), c * (1.0 + nu), 0.0])
        t_in_arr = np.asarray(temp_inner, dtype=float)
        t_out_arr = np.asarray(temp_outer, dtype=float)
        if t_in_arr.ndim == 0:
            t_in_arr = np.full(len(quads), float(t_in_arr))
        if t_out_arr.ndim == 0:
            t_out_arr = np.full(len(quads), float(t_out_arr))
        sig_plus = sig_plus - (alpha * (t_out_arr - temp_ref))[:, None] * _bi
        sig_minus = sig_minus - (alpha * (t_in_arr - temp_ref))[:, None] * _bi

    def _vm(s: np.ndarray) -> np.ndarray:
        return np.sqrt(s[:, 0] ** 2 - s[:, 0] * s[:, 1]
                       + s[:, 1] ** 2 + 3.0 * s[:, 2] ** 2)

    vm_plus = _vm(sig_plus)
    vm_minus = _vm(sig_minus)
    vm = np.maximum(vm_plus, vm_minus)

    # 平面应力主应力（σzz=0）：求两向主应力后纳入 0
    def _principals(s: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
        avg = 0.5 * (s[:, 0] + s[:, 1])
        r = np.sqrt(0.25 * (s[:, 0] - s[:, 1]) ** 2 + s[:, 2] ** 2)
        return avg + r, avg - r

    p1a, p2a = _principals(sig_plus)
    p1b, p2b = _principals(sig_minus)
    s1 = np.maximum.reduce([p1a, p1b, np.zeros_like(p1a)])
    s3 = np.minimum.reduce([p2a, p2b, np.zeros_like(p2a)])

    # 膜力 N (N/m) 与弯矩 M (N·m)：扣除热合量 N_th/M_th 得真实应力合量
    dm, db, _ = _constitutive(E, nu, t_arr)
    nforce = np.einsum("mij,mj->mi", dm, eps)
    moment = np.einsum("mij,mj->mi", db, kappa)
    if t_in_arr is not None and t_out_arr is not None and alpha > 0.0:
        _ooz = np.array([1.0, 1.0, 0.0])
        dT_m = 0.5 * (t_in_arr + t_out_arr) - temp_ref
        dT_grad = (t_out_arr - t_in_arr) / t_arr
        n_th = np.einsum("mij,mj->mi", dm, (alpha * dT_m)[:, None] * _ooz)
        m_th = np.einsum("mij,mj->mi", db, (alpha * dT_grad)[:, None] * _ooz)
        nforce = nforce - n_th
        moment = moment - m_th

    return {
        "sigma_outer": sig_plus,
        "sigma_inner": sig_minus,
        "von_mises": vm,
        "principal_max": s1,
        "principal_min": s3,
        "membrane_force": nforce,
        "bending_moment": moment,
    }


def nodal_average_quads(values: np.ndarray, quads: np.ndarray,
                        n_nodes: int) -> np.ndarray:
    """单元量 → 四节点平均（云图读数）。"""
    acc = np.zeros(n_nodes)
    cnt = np.zeros(n_nodes)
    vals = np.repeat(np.asarray(values, dtype=float), 4)
    np.add.at(acc, quads.ravel(), vals)
    np.add.at(cnt, quads.ravel(), 1.0)
    return acc / np.maximum(cnt, 1.0)


def _plane_stress_metrics(sig: np.ndarray
                          ) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """平面应力 (m,3)=[σx,σy,τxy] → (von Mises, σ1, σ3（含 σzz=0）)。"""
    vm = np.sqrt(sig[:, 0] ** 2 - sig[:, 0] * sig[:, 1]
                 + sig[:, 1] ** 2 + 3.0 * sig[:, 2] ** 2)
    avg = 0.5 * (sig[:, 0] + sig[:, 1])
    r = np.sqrt(0.25 * (sig[:, 0] - sig[:, 1]) ** 2 + sig[:, 2] ** 2)
    return vm, avg + r, avg - r


def extrapolate_base_edge(
    base_groups: Sequence[Sequence[np.ndarray]],
    sigma_outer: np.ndarray,
    sigma_inner: np.ndarray,
    vm: np.ndarray,
) -> Dict[str, float]:
    """侧壁固支底边应力外推：首排单元中心应力外推到 y=0 固支边（任意截面）。

    base_groups : 每片独立壁面一个分组，每组为按高度排列的底排/二排/三排
        单元索引数组（矩形箱 4 组、圆筒仓 1 组，由网格构造器给出）。
        各排等长，单元中心位于 y=0.5/1.5/2.5·el。

    单元中心采样的首排中心离固支边还有半个单元高，悬臂弯矩在根部最大，
    粗网格会系统性低估（与 solid.py 风墙根部外推同一处理）：
    根部压力最大、弯矩沿高度含二次项，故采用二次 Lagrange 外推
        σ(0) = 1.875·σ1 − 1.25·σ2 + 0.375·σ3
    不足 3 排（nv<3）时退回线性 1.5·σ1 − 0.5·σ2。
    壁面局部 ey 一律取整体 +y，故应力分量第 1 列即竖直 σyy。

    顺带把外推后的根部 von Mises 写回首排单元 vm（供云图显示）。
    返回根部外推峰值 vm/tension/compression/|σyy|（Pa）。
    """
    edge_all: List[np.ndarray] = []
    edge_vm_max = 0.0
    edge_yy_max = 0.0
    for rows in base_groups:
        if len(rows) < 2:
            continue
        row0, row1 = np.asarray(rows[0]), np.asarray(rows[1])
        if len(rows) >= 3:   # 二次外推（匹配根部弯矩的曲率项）
            row2 = np.asarray(rows[2])

            def _fit(face: np.ndarray) -> np.ndarray:
                return (1.875 * face[row0] - 1.25 * face[row1]
                        + 0.375 * face[row2])
        else:                # 线性兜底
            def _fit(face: np.ndarray) -> np.ndarray:
                return 1.5 * face[row0] - 0.5 * face[row1]
        e_o, e_i = _fit(sigma_outer), _fit(sigma_inner)
        edge_all.extend([e_o, e_i])
        edge_yy_max = max(edge_yy_max,
                          float(max(np.abs(e_o[:, 1]).max(),
                                    np.abs(e_i[:, 1]).max())))
        evm_o, _, _ = _plane_stress_metrics(e_o)
        evm_i, _, _ = _plane_stress_metrics(e_i)
        evm = np.maximum(evm_o, evm_i)
        edge_vm_max = max(edge_vm_max, float(evm.max()))
        vm[row0] = np.maximum(vm[row0], evm)  # 云图根部读数不外推低于单元值

    if not edge_all:
        return {"vm": 0.0, "tension": 0.0, "compression": 0.0, "syy": 0.0}
    S = np.vstack(edge_all)
    _, p1, p2 = _plane_stress_metrics(S)
    return {
        "vm": edge_vm_max,
        "tension": float(np.maximum(p1.max(), 0.0)),
        "compression": float(np.maximum(-p2.min(), 0.0)),
        "syy": edge_yy_max,
    }


# ================================================================== 薄壁容器
def _tank_divisions(model: Dict[str, Any]) -> Tuple[int, float]:
    level = str(model.get("mesh_level") or "medium").lower()
    seg = model.get("mesh") if isinstance(model.get("mesh"), dict) else {}
    if seg:
        try:
            nv = int(seg["n_vertical"])
            el = seg.get("el_size_m")
            el_size = float(el) if el is not None else None
        except (TypeError, ValueError, KeyError):
            raise ValueError("mesh 必须包含整数 n_vertical，可选 el_size_m")
        if nv < 2:
            raise ValueError("mesh.n_vertical 必须 ≥ 2")
        return nv, el_size
    if level not in TANK_MESH_LEVELS:
        raise ValueError(
            f"未知网格档位「{level}」，可选：{'、'.join(TANK_MESH_LEVELS)}"
        )
    return int(TANK_MESH_LEVELS[level]["nv"]), None


def run_vessel(
    model: Dict[str, Any], library: Optional[MaterialsLibrary] = None
) -> Dict[str, Any]:
    """通用薄壁容器充填料静力求解（section="rect" | "circle"）。

    模型字段
    --------
    kind : "shell_vessel"（新）；旧 "shell_tank" 仅矩形，输出字段保持兼容
    geometry :
        rect  — {"section":"rect", "length_m","width_m","height_m","thickness_m"}
                （旧 "type":"rect_tank" 仍兼容）
        circle— {"section":"circle", "radius_m","height_m","thickness_m"}
                （或 "type":"cylinder_silo"）
    fill : 填料预设名（water/dry_sand/wet_sand/grain_wheat/cement_powder/
           plastic_pellets）或参数对象，见 pocketsim.fills；
           旧模型无 fill 时按水处理（fluid_density_kg_m3 可覆盖密度）
    fill_depth_m : 料深（默认满仓；旧 water_depth_m 兼容），0 = 空仓
    self_weight : 是否计入容器自重（默认 true）
    material : 材料名；可用 E_MPa / nu / density_kg_m3 覆盖
    mesh_level : coarse|medium|fine|ultra；circle 可在 mesh={} 内另指定
                 n_circum / n_radial

    返回结构与 solid.run_solid_generic 同构（Kotlin SolidGenericResult 解码，
    render.type="solid3d"，四边形以 2 个三角形下发）。
    """
    if not isinstance(model, dict):
        raise ValueError("容器模型必须是 JSON 对象")
    kind_in = str(model.get("kind") or "shell_vessel").strip().lower()
    if kind_in not in ("shell_vessel", "shell_tank", "tank",
                       "rect_tank", "cylinder_silo", "silo"):
        raise ValueError(
            f"期望 kind=shell_vessel（兼容 shell_tank），得到「{kind_in}」")

    geom = model.get("geometry") or {}
    gtype = str(geom.get("type") or "").strip().lower()
    sect = str(geom.get("section") or "").strip().lower()
    if sect in ("rect", "rectangle", "box"):
        section = "rect"
    elif sect in ("circle", "circular", "cylinder"):
        section = "circle"
    elif gtype in ("rect_tank", "rect", "box"):
        section = "rect"
    elif gtype in ("cylinder_silo", "cylinder", "circle", "circular_tank"):
        section = "circle"
    else:
        raise ValueError(
            "geometry 需指定 section=\"rect\"|\"circle\""
            "（或 type=rect_tank / cylinder_silo）")

    try:
        H = float(geom["height_m"])
        t = float(geom["thickness_m"])
        if section == "rect":
            L = float(geom["length_m"])
            W = float(geom["width_m"])
            R = 0.0
        else:
            R = float(geom["radius_m"])
            L = W = 0.0
    except (KeyError, TypeError, ValueError) as exc:
        need = "length_m / width_m" if section == "rect" else "radius_m"
        raise ValueError(
            f"geometry 必须包含正数 {need} / height_m / thickness_m"
        ) from exc
    if H <= 0.0 or t <= 0.0:
        raise ValueError("容器高度/壁厚必须为正")
    if (section == "rect" and min(L, W) <= 0.0) or \
            (section == "circle" and R <= 0.0):
        raise ValueError("容器平面尺寸必须为正")
    thin_ref = min(L, W) if section == "rect" else R
    if t >= 0.2 * thin_ref:
        raise ValueError(
            f"壁厚 {t * 1000:.0f} mm 相对尺寸过大：本模块按薄壳（t < 特征尺寸 1/5）"
            "建模，请改用实体单元"
        )
    self_weight = bool(model.get("self_weight", True))

    # ---- 材料（与 run_solid_generic 同一套解析）----
    name = str(model.get("material") or "").strip()
    if not name:
        raise ValueError("缺少材料：请指定材料名（如 钢、铝合金、塑料）")
    lib = library if library is not None else load_library()
    rec = lib.find_material(name)
    if rec is None:
        avail = "、".join(r["name"] for r in lib.all_materials())
        raise ValueError(f"材料库中没有「{name}」，可选：{avail}")
    mat_name = str(rec.get("name") or name)

    def _num(key: str) -> Optional[float]:
        if key in model and model[key] is not None:
            try:
                return float(model[key])
            except (TypeError, ValueError):
                raise ValueError(f"参数 {key} 必须是数字")
        return None

    e_override = _num("E_MPa")
    E = e_override * 1e6 if e_override is not None \
        else float(rec.get("elastic_modulus") or 0.0)
    nu_o = _num("nu")
    nu = nu_o if nu_o is not None else float(rec.get("poisson_ratio") or 0.3)
    rho_o = _num("density_kg_m3")
    rho_s = rho_o if rho_o is not None else float(rec.get("density") or 0.0)
    strength = float(rec.get("strength") or 1.0)
    tension_limit = float(_tension_limit(mat_name, strength))
    if E <= 0.0:
        raise ValueError("材料弹性模量必须为正")
    if rho_s <= 0.0:
        raise ValueError("材料密度必须为正（壳模型需要自重质量）")

    # ---- 热输入（稳态壁厚温差）----
    # inner=浸湿面（内壁/底板上表面，局部 −z）、outer=外露面（外壁/底板下表面，
    # 局部 +z）；缺省按参考温度处理（无温差→不产生热应力）。
    thermal_in = model.get("thermal") \
        if isinstance(model.get("thermal"), dict) else {}
    alpha_mat = float(rec.get("thermal_expansion") or 0.0)

    def _temp(key: str, default: float) -> float:
        v = thermal_in.get(key)
        if v is None:
            return default
        try:
            return float(v)
        except (TypeError, ValueError):
            raise ValueError(f"thermal.{key} 必须是数字")

    t_ref = _temp("ref_temp_c", 20.0)
    t_in = _temp("inner_temp_c", t_ref)
    t_out = _temp("outer_temp_c", t_ref)
    thermal_active = (alpha_mat > 0.0
                      and (abs(t_in - t_ref) > 1e-9 or abs(t_out - t_ref) > 1e-9))
    thermal_dict: Optional[Dict[str, Any]] = None
    if thermal_active:
        thermal_dict = {"alpha": alpha_mat, "inner_temp_c": t_in,
                        "outer_temp_c": t_out, "ref_temp_c": t_ref}
    notes = str(rec.get("notes") or "")
    thermal_note = str(rec.get("thermal_note") or "")
    is_brittle = (("脆性" in notes) or ("脆性" in thermal_note)
                  or ("热冲击" in thermal_note))
    dT_wall = abs(t_in - t_out)

    # ---- 网格 ----
    nv, el_override = _tank_divisions(model)
    seg = model.get("mesh") if isinstance(model.get("mesh"), dict) else {}
    if section == "rect":
        mesh = build_tank_mesh(L, W, H, t, nv=nv, el_size=el_override)
    else:
        el_c = el_override if el_override is not None else H / nv
        nc = (int(seg["n_circum"]) if seg.get("n_circum") is not None
              else max(24, int(round(2.0 * math.pi * R / el_c))))
        nr = (int(seg["n_radial"]) if seg.get("n_radial") is not None
              else max(3, int(round(R / el_c))))
        mesh = build_cylinder_mesh(R, H, t, nc=nc, nv=nv, nr=nr)
    nodes = mesh["nodes"]
    quads = mesh["quads"]
    ex, ey, en = mesh["ex"], mesh["ey"], mesh["en"]
    wall = mesh["wall"]
    segments = mesh["segments"]
    floor_tag = int(mesh["floor_tag"])

    # ---- 填料（"装什么"与截面无关：流体/散体统一为法向压 + 壁面剪 + 底板压）----
    fill = fills_mod.parse_fill(
        model, H, float(mesh["plan_area_m2"]), float(mesh["perimeter_m"]))

    # ---- 约束 ----
    fixed_list = []
    ring = mesh["base_ring_nodes"]
    fixed_list.append((ring[:, None] * 6 + np.arange(6)[None, :]).ravel())
    inner = mesh["bottom_inner_nodes"]
    fixed_list.append(inner * 6 + 1)  # 底板内部仅竖向（y）支承
    fixed = np.unique(np.concatenate(fixed_list))

    # ---- 载荷：内压沿壁外法线（侧墙向外、底板朝下）；散体壁摩擦竖直向下 ----
    side = np.where(wall != floor_tag)[0]
    bottom = np.where(wall == floor_tag)[0]
    pressures: List[Dict[str, Any]] = []
    shears: List[Dict[str, Any]] = []
    if fill.kind != "empty":
        pressures.append({
            "elements": side,
            "magnitude": fill.normal_xyz(),  # 料面以上自动为 0
            "sign": 1.0,
        })
        pressures.append({
            "elements": bottom,
            "magnitude": fill.floor_pressure_pa,
            "sign": 1.0,
        })
        shear_fn = fill.shear_xyz()
        if shear_fn is not None:
            shears.append({
                "elements": side,
                "magnitude": shear_fn,
                "dirs": np.array([0.0, -1.0, 0.0]),
            })
    body_accel = (0.0, -GRAVITY, 0.0) if self_weight else None

    # ---- 求解 ----
    solved = solve_shell(
        nodes, quads, ex, ey, en, t, E=E, nu=nu, fixed=fixed,
        pressures=pressures, shears=shears,
        body_accel=body_accel, density=rho_s,
        thermal=thermal_dict,
    )
    u = solved["u"]
    applied = np.asarray(solved["applied_force"], dtype=float)
    reactions = np.asarray(solved["reaction_force"], dtype=float)

    # ---- 解的有效性 ----
    diag = float(np.linalg.norm(nodes.max(axis=0) - nodes.min(axis=0)))
    u3 = u.reshape(-1, 6)[:, :3]
    max_disp_m = float(np.linalg.norm(u3, axis=1).max())
    if diag > 0.0 and max_disp_m > 0.5 * diag:
        raise ValueError(
            f"最大位移 {max_disp_m:.4g} m 远超箱体尺寸（对角线 {diag:.4g} m），"
            "结构可能约束不足或为机构"
        )
    residual_norm = float(np.linalg.norm(reactions + applied))
    applied_norm = float(np.linalg.norm(applied))
    eq_ratio = residual_norm / applied_norm if applied_norm > 1e-30 else 0.0
    if eq_ratio > 0.05:
        raise ValueError(
            f"静力不平衡：外载合力 {applied.tolist()} N，"
            f"支反力 {reactions.tolist()} N，相对残差 {eq_ratio:.2%}（>5%）"
        )

    # ---- 应力指标 ----
    # 根部固支边应力由首三排单元二次外推（首排中心采样会低估根部弯矩）；
    # 其余区域（侧壁 j≥2 + 底板）取单元中心值。
    centers = nodes[quads].mean(axis=1)
    h_el = float(mesh["vertical_el"])
    edge = extrapolate_base_edge(
        mesh["base_groups"], solved["sigma_outer"], solved["sigma_inner"],
        solved["von_mises"])
    wall_sel = wall != floor_tag
    interior = (wall == floor_tag) | (wall_sel & (centers[:, 1] > 2.0 * h_el))
    vm = solved["von_mises"]  # 根部首排已被外推值就地更新（供云图）
    s1 = solved["principal_max"]
    s3 = solved["principal_min"]
    vm_node = nodal_average_quads(vm, quads, len(nodes))
    max_vm_pa = max(edge["vm"], float(vm[interior].max()))
    max_tension_pa = max(edge["tension"], float(s1[interior].max()))
    max_compression_pa = max(edge["compression"],
                             float(-s3[interior].min()))

    sf_t = tension_limit * 1e6 / max_tension_pa if max_tension_pa > 1e-6 else 999.0
    sf_c = strength * 1e6 / max_compression_pa if max_compression_pa > 1e-6 else 999.0
    sf_min = min(sf_t, sf_c)
    verdict = "安全" if sf_min >= SAFETY_SAFE else (
        "注意" if sf_min >= SAFETY_CAUTION else "危险")

    disp_mag = np.linalg.norm(u3, axis=1)
    max_disp_node = int(np.argmax(disp_mag))

    # ---- 理论参照 ----
    # 共同项：单位宽度竖直悬臂条，根部弯矩 M=∫pₙ(h)(d−h)dh（流体三角形
    # 剖面即 γd³/6），表面弯曲应力 6M/t²；FEA 取根部外推 |σyy|。
    theory: Dict[str, Any] = {}
    if fill.kind != "empty":
        depth = fill.depth_m
        gamma = float(fill.info["gamma"])
        hs = np.linspace(0.0, depth, 401)
        dh = depth / 400.0
        pn_v = np.array([max(float(fill.normal_h(h)), 0.0) for h in hs])
        m_base = float(np.sum(pn_v * (depth - hs)) * dh)
        strip_stress_pa = 6.0 * m_base / t ** 2
        fea_base_pa = edge["syy"]
        err = abs(fea_base_pa - strip_stress_pa) / max(strip_stress_pa, 1e-30) \
            * 100.0

        if section == "rect":
            theory = {
                "name_en": "cantilever strip (1 m width)",
                "name": "竖直悬臂板条（单位宽度）",
                "base_moment_N_m_per_m": round(m_base, 2),
                "strip_stress_MPa": round(strip_stress_pa / 1e6, 4),
                "fea_base_stress_MPa": round(fea_base_pa / 1e6, 4),
                "note": ("板条理论忽略侧壁水平方向的双向传力与转角约束，"
                         "其根部应力偏保守；FEA 通常更低"),
                "error_pct": round(err, 1),
            }
            if fill.kind == "fluid":
                q0 = gamma * depth
                i_strip = t ** 3 / 12.0
                strip_tip_m = q0 * depth ** 4 / (30.0 * E * i_strip)
                theory["strip_tip_deflection_mm"] = round(strip_tip_m * 1000.0, 3)
        else:
            # 圆筒环向薄膜 σθ=pₙ·r/t：取壁面中部条带（避开固支边界层、
            # 顶部自由边与料面），局部 σx 即环向、膜力 Nx/t 即薄膜应力。
            yc = centers[side, 1]
            band = (yc >= 3.0 * h_el) & (yc <= depth - 3.0 * h_el)
            if int(band.sum()) < 8:
                band = (yc >= 2.0 * h_el) & (yc <= depth - 2.0 * h_el)
            ids_band = side[band]
            yb = centers[ids_band, 1]
            pn_b = np.array([max(float(fill.normal_h(depth - yy)), 0.0)
                             for yy in yb])
            sig_theta_fea = solved["membrane_force"][ids_band, 0] / t
            sig_theta_pred = pn_b * R / t
            hoop_err = float(
                abs(sig_theta_fea.mean() - sig_theta_pred.mean())
                / max(abs(sig_theta_pred.mean()), 1e-30) * 100.0)
            theory = {
                "name_en": "hoop membrane σθ=p·r/t",
                "name": "圆筒环向薄膜应力 σθ=p·r/t",
                "hoop_stress_theory_MPa": round(
                    float(sig_theta_pred.mean()) / 1e6, 4),
                "fea_hoop_membrane_MPa": round(
                    float(sig_theta_fea.mean()) / 1e6, 4),
                "hoop_error_pct": round(hoop_err, 1),
                "fea_base_stress_MPa": round(fea_base_pa / 1e6, 4),
                "cantilever_strip_upper_bound_MPa":
                    round(strip_stress_pa / 1e6, 4),
                "note": ("环向薄膜取壁面中部单元均值（避开根部固支边界层与"
                         "顶部自由边），是圆柱仓的主控应力；根部竖直弯曲为"
                         "局部边界效应，环向薄膜像弹性地基一样承担径向载荷，"
                         "故自由悬臂条解（upper_bound）远高于实际、仅作上界参考"),
            }

    # 温度应力主控时，静水/散体手算不再具有代表性，覆盖为说明性条目
    if thermal_active:
        if is_en():
            theory = {
                "name_en": "steady-state thermal stress",
                "name": "温度应力主控",
                "note": ("Wall temperature difference is the dominant load in this "
                         "scenario; hydrostatic/granular hand-calculations are not "
                         "representative and are omitted"),
            }
        else:
            theory = {
                "name": "温度应力主控",
                "note": ("本工况以壁厚温差引起的温度应力为主，静水/散体理论"
                         "参照不再具有代表性，故略去"),
            }

    # ---- 几何/载荷信息 ----
    area = float(mesh["plan_area_m2"])
    depth = fill.depth_m
    fill_volume = area * depth
    fill_kn = fill.bulk_density * GRAVITY * fill_volume / 1000.0
    if section == "rect":
        shell_area = 2.0 * (L + W) * H + L * W
    else:
        shell_area = 2.0 * math.pi * R * H + math.pi * R ** 2
    shell_kn = rho_s * GRAVITY * shell_area * t / 1000.0
    geom_info: Dict[str, Any] = {
        "section": section,
        "type": "rect_tank" if section == "rect" else "cylinder_silo",
        "height_m": round(H, 4),
        "thickness_m": round(t, 4),
        "up_axis": "y",
        "fill_depth_m": round(depth, 4),
        "plan_area_m2": round(area, 4),
        "volume_m3": round(fill_volume, 4),
        "fill_weight_kN": round(fill_kn, 3),
        "tank_weight_kN": round(shell_kn, 3),
        # 与 SolidGenericGeometry 字段对齐：结果页"自重"卡片读 geometry.weight_kN
        "weight_kN": round(shell_kn, 3),
    }
    if section == "rect":
        geom_info["length_m"] = round(L, 4)
        geom_info["width_m"] = round(W, 4)
    else:
        geom_info["radius_m"] = round(R, 4)
        geom_info["diameter_m"] = round(2.0 * R, 4)
    if fill.kind == "fluid":  # 旧字段兼容（仅流体即"水"语义）
        geom_info["water_depth_m"] = round(depth, 4)
        geom_info["water_weight_kN"] = round(fill_kn, 3)

    # ---- fill 输出块（参数与关键压力，供 App/报告展示）----
    fill_out: Dict[str, Any] = {"kind": fill.kind}
    if fill.kind != "empty":
        fi = fill.info
        fill_out.update({
            "label_zh": fi.get("label"),
            "label_en": fi.get("label_en"),
            "bulk_density_kg_m3": round(fill.bulk_density, 1),
            "depth_m": round(depth, 4),
            "lateral_K": round(float(fi["K"]), 4),
            "floor_pressure_kPa": round(fill.floor_pressure_pa / 1000.0, 3),
        })
        if fill.kind == "granular":
            fill_out.update({
                "state": fi["state"],
                "friction_angle_deg": fi["phi_deg"],
                "wall_friction_deg": fi["delta_deg"],
                "wall_friction_mu": round(float(fi["mu"]), 4),
                "cohesion_kPa": round(float(fi["cohesion_pa"]) / 1000.0, 3),
                "janssen": fi["janssen"],
                "hydraulic_radius_m": round(float(fi["hydraulic_radius_m"]), 4),
            })

    # ---- 载荷来源（双语）----
    load_sources: List[str] = []
    if fill.kind != "empty":
        rho_f = fill.bulk_density
        p_wall_base = max(float(fill.normal_h(depth)), 0.0)
        p_floor = fill.floor_pressure_pa
        if is_en():
            if fill.kind == "fluid":
                load_sources.append(
                    f"Hydrostatic pressure of {rho_f:.0f} kg/m³ "
                    f"{fill.info.get('label_en', 'fluid')}, "
                    f"depth {depth:.3g} m (zero at free surface, {p_wall_base / 1000:.3g} "
                    f"kPa at wall base); floor uniform pressure {p_floor / 1000:.3g} kPa")
            else:
                fi = fill.info
                load_sources.append(
                    f"Granular fill \"{fi.get('label_en')}\" lateral pressure "
                    f"(rho={rho_f:.0f} kg/m³, phi={fi['phi_deg']:g}°, "
                    f"{fi['state']} K={fi['K']:.3f}), depth {depth:.3g} m; "
                    f"wall-base p_n={p_wall_base / 1000:.3g} kPa, "
                    f"floor p_b={p_floor / 1000:.3g} kPa")
                if fill.shear_h is not None:
                    load_sources.append(
                        f"Wall friction shear tau=p_n·tan(delta), "
                        f"delta={fi['delta_deg']:g}° (vertically downward)")
                    load_sources.append(
                        f"Deep-bin Janssen saturation: floor pressure "
                        f"{p_floor / 1000:.3g} kPa < gamma·H="
                        f"{rho_f * GRAVITY * depth / 1000:.3g} kPa; the "
                        f"difference is transferred to the foundation by wall friction")
                else:
                    load_sources.append(
                        "Shallow-bin Rankine model: smooth wall (no vertical "
                        "friction applied), the floor carries the full fill "
                        f"weight gamma·H={p_floor / 1000:.3g} kPa")
        else:
            if fill.kind == "fluid":
                load_sources.append(
                    f"{rho_f:.0f} kg/m³ {fill.info.get('label', '流体')}静压力，"
                    f"料深 {depth:.3g} m（液面为 0，侧壁底部 {p_wall_base / 1000:.3g} kPa）；"
                    f"底板均布压力 {p_floor / 1000:.3g} kPa")
            else:
                fi = fill.info
                state_zh = {"at_rest": "静止 K₀", "active": "主动 Ka",
                            "passive": "被动 Kp"}[fi["state"]]
                load_sources.append(
                    f"散体「{fi.get('label')}」侧压力（堆积密度 {rho_f:.0f} kg/m³，"
                    f"φ={fi['phi_deg']:g}°，{state_zh}={fi['K']:.3f}），"
                    f"料深 {depth:.3g} m；侧壁底部 pₙ={p_wall_base / 1000:.3g} kPa，"
                    f"底板 p_b={p_floor / 1000:.3g} kPa")
                if fill.shear_h is not None:
                    load_sources.append(
                        f"壁面摩擦剪应力 τ=pₙ·tanδ（δ={fi['delta_deg']:g}°，"
                        f"μ={fi['mu']:.3f}），方向竖直向下")
                    load_sources.append(
                        f"深仓 Janssen 饱和：底板压力 {p_floor / 1000:.3g} kPa "
                        f"< γH={rho_f * GRAVITY * depth / 1000:.3g} kPa，"
                        f"差值经壁面摩擦力传向基础（底板+壁摩擦合计=料重）")
                else:
                    load_sources.append(
                        "浅仓 Rankine 模型按光滑壁处理：不计竖向壁摩擦，"
                        f"料重全部由底板承担（p_b=γH={p_floor / 1000:.3g} kPa）")
    if self_weight:
        if is_en():
            load_sources.append(
                f"Vessel self-weight (shell density {rho_s:.0f} kg/m³, g={GRAVITY:.2f})")
        else:
            load_sources.append(
                f"容器自重（壳材料密度 {rho_s:.0f} kg/m³，g={GRAVITY:.2f}）")
    if thermal_active:
        if is_en():
            load_sources.append(
                f"Steady-state thermal stress: temperature varies linearly across the "
                f"wall thickness, inner (wetted) surface {t_in:g}°C, outer (exposed) "
                f"surface {t_out:g}°C, stress-free reference {t_ref:g}°C, "
                f"alpha={alpha_mat:.2g} 1/K")
        else:
            load_sources.append(
                f"稳态温度应力：温度沿壁厚线性分布，浸湿面（内壁/底板上表面）"
                f"{t_in:g}°C，外露面（外壁/底板下表面）{t_out:g}°C，"
                f"无应力参考温度 {t_ref:g}°C，α={alpha_mat:.2g} 1/K")

    # ---- 云图：四边形→2 三角形（直接复用 solid3d 渲染契约）----
    tris = np.zeros((2 * len(quads), 3), dtype=int)
    tris[0::2] = quads[:, [0, 1, 2]]
    tris[1::2] = quads[:, [0, 2, 3]]
    vm_render = vm_node / 1e6
    render_unit = "MPa"
    if is_en() and is_imperial():
        vm_render = vm_render * PSI_PER_MPA
        render_unit = "psi"
    render = {
        "type": "solid3d",
        "unit": render_unit,
        "field": "von_mises",
        "value_location": "node",
        "up_axis": "y",
        "nodes": [[round(float(c), 6) for c in p] for p in nodes],
        "tets": [[int(v) for v in tri] for tri in tris],
        "disp": [[round(float(u3[i, 0]), 9),
                  round(float(u3[i, 1]), 9),
                  round(float(u3[i, 2]), 9)] for i in range(len(nodes))],
        "values": [round(float(v), 4) for v in vm_render],
        "bounds": [
            round(float(nodes[:, 0].min()), 6),
            round(float(nodes[:, 1].min()), 6),
            round(float(nodes[:, 2].min()), 6),
            round(float(nodes[:, 0].max()), 6),
            round(float(nodes[:, 1].max()), 6),
            round(float(nodes[:, 2].max()), 6),
        ],
    }

    checks = [
        {"name": "最大拉应力", "value": round(max_tension_pa / 1e6, 4),
         "limit": round(tension_limit, 4), "unit": "MPa",
         "safety_factor": round(sf_t, 2), "pass": sf_t >= SAFETY_CAUTION},
        {"name": "最大压应力", "value": round(max_compression_pa / 1e6, 4),
         "limit": round(strength, 4), "unit": "MPa",
         "safety_factor": round(sf_c, 2), "pass": sf_c >= SAFETY_CAUTION},
    ]
    if is_en():
        checks[0]["name"] = "Max tensile stress"
        checks[1]["name"] = "Max compressive stress"
        if is_imperial():
            for c in checks:
                c["value"] = round(c["value"] * PSI_PER_MPA, 1)
                c["limit"] = round(c["limit"] * PSI_PER_MPA, 1)
                c["unit"] = "psi"

    if is_en():
        assumptions = [
            "Thin-walled shell model: walls are represented by their mid-surfaces; "
            "wall thickness enters the constitutive law and is NOT meshed through "
            "(valid for t < 1/5 of the characteristic span); the circular silo wall "
            "is a faceted cylinder (flat quads, circumferential subdivision >= 24)",
            "Four-node Reissner–Mindlin flat shell elements with MITC4 tied "
            "transverse-shear interpolation (shear-locking free); 5 DOF per node "
            "locally, transformed to 3 translations + 3 rotations globally",
            "Isotropic linear-elastic material; small deformation; membrane + "
            "bending resultants are recovered at element centers",
        ]
        if fill.kind == "fluid":
            assumptions.append(
                "Hydrostatic pressure p = rho g h acts normal to the walls (zero at "
                "the free surface, maximum at the bottom); the floor carries uniform "
                "pressure rho g (fill depth)")
        elif fill.kind == "granular":
            fi = fill.info
            if fill.shear_h is not None:
                assumptions.append(
                    "Granular fill: lateral wall pressure p_n = K·sigma_v with Rankine "
                    f"coefficient K={fi['K']:.3f} ({fi['state']}); deep-bin Janssen "
                    "sigma_v saturates exponentially; wall friction shear "
                    "tau = p_n·tan(delta) acts vertically downward and transfers the "
                    "fill-weight difference to the foundation")
            else:
                assumptions.append(
                    "Granular fill (shallow bin): lateral wall pressure "
                    "p_n = K·gamma·h with Rankine coefficient "
                    f"K={fi['K']:.3f} ({fi['state']}); smooth-wall model with no "
                    "vertical friction, the floor carries the full fill weight")
        assumptions += [
            "The bottom wall-to-floor ring is fully clamped; interior floor nodes are "
            "supported vertically (vessel resting on its base). Peak stress at the clamped "
            "base edge is extrapolated to the edge with a quadratic fit through the first "
            "three rows of elements (element-center sampling alone under-reads the root "
            "bending moment)",
            "Not considered: discharge arching / eccentric-fill overpressure, silo "
            "quakes and flow-induced vibration, shell buckling, sloshing and dynamic "
            "coupling, stiffeners, weld/detail stress, large deformation and "
            "plasticity, transient thermal shock and temperature-dependent properties",
        ]
    else:
        assumptions = [
            "薄壁壳模型：各壁按中面离散，壁厚作为本构参数进入单元、不沿壁厚方向"
            "画网格（适用于 t < 特征尺寸 1/5 的薄壁）；圆筒壁以平面小面逼近"
            "（周向分段 ≥ 24）",
            "四节点 Reissner–Mindlin 平板壳，横向剪切采用 MITC4 绑定插值"
            "（无剪切锁死）；局部每节点 5 DOF，坐标变换后整体为 3 平动 + 3 转动",
            "各向同性线弹性材料、小变形；单元中心恢复膜力与弯矩并合成表面应力",
        ]
        if fill.kind == "fluid":
            assumptions.append(
                "静水压力 p=ρgh 沿板面法向作用（液面为 0、底部最大）；底板承受"
                "均布压力 ρg·料深")
        elif fill.kind == "granular":
            fi = fill.info
            if fill.shear_h is not None:
                assumptions.append(
                    f"散体填料（深仓）：侧壁侧压 pₙ=K·σv（Rankine 系数 "
                    f"K={fi['K']:.3f}，{fi['state']} 态），σv 按 Janssen 指数饱和；"
                    "壁摩擦剪应力 τ=pₙ·tanδ 竖直向下，料重差值经壁面传向基础")
            else:
                assumptions.append(
                    f"散体填料（浅仓）：侧壁侧压 pₙ=K·γh（Rankine 系数 "
                    f"K={fi['K']:.3f}，{fi['state']} 态）；按光滑壁处理、不计"
                    "竖向壁摩擦，料重全部由底板承担")
        assumptions += [
            "侧壁与底板交接的底环按 6 向固支；底板内部节点仅竖向支承"
            "（容器落于基础上的简化）。固支底边的峰值应力由首三排单元二次外推"
            "到固支边（仅取单元中心会低估根部弯矩）",
            "未考虑：卸料成拱/偏心装料超压、筒仓振动与料流动力、壳体屈曲、"
            "液体晃动与动力耦合、加劲肋、焊缝与构造细节应力、大变形与塑性、"
            "瞬态热冲击与材料参数随温度变化",
        ]

    if thermal_active:
        if is_en():
            assumptions.append(
                f"Steady-state (not transient) thermal stress: temperature is "
                f"assumed to vary linearly across the wall thickness between the "
                f"inner (wetted) surface ({t_in:g}°C) and the outer (exposed) "
                f"surface ({t_out:g}°C); the stress-free reference temperature is "
                f"{t_ref:g}°C; thermal expansion is isotropic "
                f"(alpha={alpha_mat:.2g} 1/K)")
        else:
            assumptions.append(
                f"温度应力按稳态计算（非瞬态）：温度沿壁厚在内（浸湿，"
                f"{t_in:g}°C）、外（外露，{t_out:g}°C）表面间线性分布，"
                f"无应力参考温度为 {t_ref:g}°C；热膨胀各向同性"
                f"（α={alpha_mat:.2g} 1/K）")

    t_total = (solved["assemble_time_s"] + solved["solve_time_s"]) * 1000.0
    legacy_rect = (section == "rect"
                   and kind_in in ("shell_tank", "tank", "rect_tank"))
    out_kind = "shell_tank" if legacy_rect else "shell_vessel"
    if is_en():
        model_name = (f"{mat_name} rectangular tank" if section == "rect"
                      else f"{mat_name} cylindrical silo")
    else:
        model_name = (f"{mat_name}矩形水箱" if section == "rect"
                      else f"{mat_name}圆柱仓")

    # ---- 结论/建议：脆性材料 + 大幅温差 → 热冲击风险提示 ----
    advice: List[str] = []
    if thermal_active and is_brittle and dT_wall >= THERMAL_SHOCK_DT_K:
        if is_en():
            advice.append(
                f"Brittle material with a {dT_wall:g} K wall temperature "
                "difference: risk of thermal-shock cracking. Avoid rapid "
                "cooling/heating; pre-warm or anneal the vessel")
        else:
            advice.append(
                f"脆性材料 + 壁厚温差 {dT_wall:g} K：存在热冲击开裂风险，"
                "避免急冷急热，建议预热/缓冷")

    thermal_out: Optional[Dict[str, Any]] = None
    if thermal_active:
        thermal_out = {
            "alpha_1_K": alpha_mat,
            "inner_temp_c": t_in,
            "outer_temp_c": t_out,
            "ref_temp_c": t_ref,
            "wall_delta_t_K": round(dT_wall, 4),
        }

    return {
        "kind": out_kind,
        "ok": True,
        "section": section,
        "model_name": model_name,
        "scenario": out_kind,
        "material": mat_name,
        "material_params": {
            "E_MPa": round(E / 1e6, 1),
            "nu": round(nu, 3),
            "density_kg_m3": round(rho_s, 1),
            "strength_MPa": round(strength, 3),
            "tension_limit_MPa": round(tension_limit, 3),
        },
        "geometry": geom_info,
        "fill": fill_out,
        "load_sources": load_sources,
        "advice": advice,
        "thermal": thermal_out,
        "metrics": {
            "max_disp_mm": round(max_disp_m * 1000.0, 4),
            "max_disp_node": max_disp_node,
            "max_von_mises_MPa": round(max_vm_pa / 1e6, 4),
            "max_tension_MPa": round(max_tension_pa / 1e6, 4),
            "max_compression_MPa": round(max_compression_pa / 1e6, 4),
            "safety_factor": round(sf_min, 2),
            "sf_tension": round(sf_t, 2),
            "sf_compression": round(sf_c, 2),
            "verdict": verdict,
        },
        "checks": checks,
        "reactions": {
            "fx_N": round(float(reactions[0]), 1),
            "fy_N": round(float(reactions[1]), 1),
            "fz_N": round(float(reactions[2]), 1),
        },
        "theory_check": theory,
        "mesh": {
            "level": ("custom" if isinstance(model.get("mesh"), dict)
                      else str(model.get("mesh_level") or "medium")),
            "type": "shell_mitc4",
            "segments": segments,
            "n_elements": int(len(quads)),
            "n_vertices": int(len(nodes)),
            "dof": int(solved["dof"]),
            "solver": solved["solver_method"],
            "stress_recovery": "element-center N/M",
        },
        "timing": {
            "total_ms": round(t_total, 1),
            "assemble_ms": round(solved["assemble_time_s"] * 1000.0, 1),
            "solve_ms": round(solved["solve_time_s"] * 1000.0, 1),
        },
        "render": render,
        "assumptions": assumptions,
    }


def run_tank(
    model: Dict[str, Any], library: Optional[MaterialsLibrary] = None
) -> Dict[str, Any]:
    """矩形水箱兼容入口：校验 geometry.type=rect_tank 后委托 run_vessel。"""
    if not isinstance(model, dict):
        raise ValueError("水箱模型必须是 JSON 对象")
    geom = model.get("geometry") or {}
    if str(geom.get("type") or "rect_tank").strip().lower() != "rect_tank":
        raise ValueError("run_tank 仅支持矩形水箱 geometry.type=rect_tank")
    return run_vessel(model, library=library)
