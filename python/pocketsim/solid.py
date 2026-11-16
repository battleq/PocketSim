# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""三维实体线弹性有限元（V0.5.0 阶段 1）。

场景：把一堵墙当作三维实体，在风压（或给定均布压力）与自重共同作用下求解位移、
应力与安全系数，回答"墙会不会被吹裂/吹倒"。

数值方法
--------
- 单元：4 节点线性四面体（TET4），每个节点 3 个平动自由度，常应变单元；
  单元刚度 K_e = V·BᵀDB（闭式积分，等价于 4 点高斯积分），位移场线性、
  应力在单元内为常量；
- 网格：参数化长方体（长×高×厚）结构化六面体剖分，每个六面体按主对角线
  拆成 6 个四面体，相邻单元共享面三角剖分一致（协调网格），节点自动去重；
- 本构：各向同性线弹性 D 矩阵（E、ν 取自 materials.json）；
- 载荷：墙面均布压力按三角形面片的一致的节点力（3 点中点积分，对线性面力精确）；
  自重按单元体积均分到 4 个节点（向下 -y）；
- 约束：墙底 y=0 面全部节点三向固定（悬臂墙）；
- 求解：DOF ≤ 阈值用 scipy 稀疏直接法；超过阈值用 CG + Jacobi 预条件；
- 应力恢复：单元常应力 → 超收敛片（SPR）恢复为节点应力（渲染与极值读数）；
  墙体根部弯曲应力另沿墙厚方向线性外推到墙面（避免常应变单元在表层低估
  极值纤维应力）。

验证
----
与悬臂梁（单位墙宽条带）理论对比：
    挠度  δ = p·H⁴ / (8·E·I)，I = T³/12
    弯曲应力 σ = 3·p·H² / T²
结构化网格 + 厚度方向线性外推后，medium 档挠度/弯曲应力误差约 9%，
fine 档约 7%，ultra 档（更密的墙高/墙厚分段）进一步下降
（详见 python/tests/test_solid.py）。

单位：长度 m、力 N、应力 Pa；对外 JSON 的位移为 mm、应力为 MPa。
"""
from __future__ import annotations

import math
import time
from typing import Any, Dict, List, Optional, Sequence, Tuple

import numpy as np
import scipy.sparse as sp
import scipy.sparse.linalg as spla
from scipy.spatial import cKDTree

from .flow import (
    AIR_DENSITY,
    CP_NET,
    GRAVITY,
    _tension_limit,
    beaufort_from_speed,
    beaufort_label,
    speed_from_beaufort,
)
from .i18n import (
    FT_PER_M,
    LBF_PER_N,
    PSI_PER_MPA,
    disp_mm,
    force_n,
    is_en,
    is_imperial,
    length_m,
    length_mm,
    pressure_pa,
    speed_ms,
    stress_mpa,
    t,
)
from .materials import MaterialsLibrary, load_library

# 安全判定阈值（与框架/风压结果一致）
SAFETY_SAFE = 2.0
SAFETY_CAUTION = 1.2

# 判定档位 → 英文叙述用词（返回字段 verdict 仍保持中文，供 Kotlin 配色与排序）
_VERDICT_EN = {"安全": "safe", "注意": "caution", "危险": "danger"}

# CG 迭代阈值：自由度超过该值改用迭代法，避免端上直接法内存爆掉
CG_DOF_THRESHOLD = 30000
CG_TOLERANCE = 1e-8

# 网格档位：墙高方向分段数 ny、墙厚方向分层数 nz
#（长度方向 nx 按长高比派生，风压弯曲问题对长度方向不敏感）
MESH_LEVELS = {
    "coarse": {"ny": 40, "nz": 6},
    "medium": {"ny": 64, "nz": 8},
    "fine": {"ny": 84, "nz": 8},
    "ultra": {"ny": 112, "nz": 12},
}

# run_solid_generic 各图元的网格档位
# box: nx(长) / ny(高) / nz(厚)
# plate: nx(长) / ny(宽) / nz(厚)，面内分段更密
# cylinder: n_grid(截面方格数) / ny(轴向)
GENERIC_MESH_LEVELS = {
    "box": {
        "coarse": {"nx": 4, "ny": 12, "nz": 4},
        "medium": {"nx": 8, "ny": 24, "nz": 6},
        "fine": {"nx": 12, "ny": 36, "nz": 8},
        "ultra": {"nx": 16, "ny": 48, "nz": 12},
    },
    "plate": {
        "coarse": {"nx": 8, "ny": 8, "nz": 4},
        "medium": {"nx": 14, "ny": 14, "nz": 6},
        "fine": {"nx": 20, "ny": 20, "nz": 8},
        "ultra": {"nx": 28, "ny": 28, "nz": 12},
    },
    "cylinder": {
        "coarse": {"n_grid": 8, "ny": 12},
        "medium": {"n_grid": 12, "ny": 20},
        "fine": {"n_grid": 16, "ny": 30},
        "ultra": {"n_grid": 20, "ny": 40},
    },
}

# 标准六面体 → 6 个四面体（绕主对角线 0-6 剖分，相邻六面体共享面对角线一致）
_CUBE_TETS = np.array(
    [
        [0, 1, 2, 6],
        [0, 2, 3, 6],
        [0, 3, 7, 6],
        [0, 7, 4, 6],
        [0, 4, 5, 6],
        [0, 5, 1, 6],
    ],
    dtype=int,
)

# 镜像剖分：绕另一条对角线 1-7 剖分（与 _CUBE_TETS 关于六面体中心对称）。
# 单一剖分方向会给常应变四面体带来沿该方向的偏置（表现为对角高应力伪影）；
# 通用实体求解用两种剖分各解一次再平均，可恢复几何对称性。
_CUBE_TETS_ALT = np.array(
    [
        [1, 0, 3, 7],
        [1, 3, 2, 7],
        [1, 2, 6, 7],
        [1, 6, 5, 7],
        [1, 5, 4, 7],
        [1, 4, 0, 7],
    ],
    dtype=int,
)

# TET4 局部形函数梯度（参考四面体，节点 0 为原点）：
# N0 = 1-ξ-η-ζ，N1 = ξ，N2 = η，N3 = ζ
_LOCAL_GRADS = np.array(
    [[-1.0, -1.0, -1.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]]
)

# B 矩阵装配三元组：(行, 节点内列偏移, 形函数梯度分量)
# 应变顺序 [εxx, εyy, εzz, γxy, γyz, γzx]
_B_ROWS = [0, 1, 2, 3, 3, 4, 4, 5, 5]
_B_COLS = [0, 1, 2, 0, 1, 1, 2, 0, 2]
_B_GRADS = [0, 1, 2, 1, 0, 2, 1, 2, 0]


# ====================================================================== 网格
def build_box_mesh(
    nx: int, ny: int, nz: int, length: float, height: float, thickness: float,
    split: str = "main",
) -> Tuple[np.ndarray, np.ndarray]:
    """参数化长方体网格：x∈[0,length]、y∈[0,height]、z∈[0,thickness]。

    返回 (nodes (n,3), tets (m,4))，节点按 x 最快、y 次之、z 最慢编号；
    每个六面体拆成 6 个四面体，并按有向体积修正节点顺序（保证正体积）。
    split="main" 用主对角线 0-6 剖分；split="alt" 用镜像对角线 1-7 剖分
    （两者节点编号完全相同，单元索引一一对应，便于取平均）。
    """
    if split not in ("main", "alt"):
        raise ValueError(f"split 只能是 main/alt，得到「{split}」")
    _cube_tets = _CUBE_TETS if split == "main" else _CUBE_TETS_ALT
    nx, ny, nz = int(nx), int(ny), int(nz)
    if nx < 1 or ny < 1 or nz < 1:
        raise ValueError(f"网格分段数必须 ≥ 1：nx={nx}, ny={ny}, nz={nz}")
    if length <= 0.0 or height <= 0.0 or thickness <= 0.0:
        raise ValueError(
            f"几何尺寸必须为正：length={length}, height={height}, thickness={thickness}"
        )

    NX, NY = nx + 1, ny + 1
    n_nx, n_ny, n_nz = NX, NY, nz + 1

    def nid(i: int, j: int, k: int) -> int:
        return i + NX * j + NX * NY * k

    # 节点坐标（i 变化最快）
    xs = np.linspace(0.0, float(length), n_nx)
    ys = np.linspace(0.0, float(height), n_ny)
    zs = np.linspace(0.0, float(thickness), n_nz)
    xs_g, ys_g, zs_g = np.meshgrid(xs, ys, zs, indexing="ij")  # (n_nx,n_ny,n_nz)
    nodes = np.stack([xs_g.ravel(order="F"), ys_g.ravel(order="F"), zs_g.ravel(order="F")], axis=1)

    # 六面体（8 个几何角点）→ 6 个四面体
    local = np.array(
        [
            [0, 0, 0],
            [1, 0, 0],
            [1, 1, 0],
            [0, 1, 0],
            [0, 0, 1],
            [1, 0, 1],
            [1, 1, 1],
            [0, 1, 1],
        ],
        dtype=int,
    )
    ii, jj, kk = np.meshgrid(
        np.arange(nx), np.arange(ny), np.arange(nz), indexing="ij"
    )
    cells = np.stack([ii.ravel(order="F"), jj.ravel(order="F"), kk.ravel(order="F")], axis=1)
    corner_ids = np.array(
        [
            nid(i + di, j + dj, k + dk)
            for i, j, k in cells
            for di, dj, dk in local
        ],
        dtype=int,
    ).reshape(-1, 8)
    tets = corner_ids[:, _cube_tets].reshape(-1, 4).copy()

    # 有向体积为负的单元交换节点 2/3，保证 B 矩阵与体积正定
    p = nodes[tets]
    vol6 = np.einsum(
        "ei,ei->e", np.cross(p[:, 1] - p[:, 0], p[:, 2] - p[:, 0]), p[:, 3] - p[:, 0]
    )
    flip = vol6 < 0.0
    if np.any(flip):
        tets[flip] = tets[flip][:, [0, 2, 1, 3]]
    return nodes, tets


def _validate_holes(holes, dims):
    """校验并归一化贯穿圆孔列表，非法时抛中文 ValueError。

    dims = (length, height, thickness) 即 x/y/z 方向尺寸。
    每个孔：{"axis":"x"|"y"|"z", "center":[c1,c2], "radius":r}
      axis=x → 孔沿 x 贯穿，截面在 y-z 平面，center=[y,z]
      axis=y → 孔沿 y 贯穿，截面在 x-z 平面，center=[x,z]
      axis=z → 孔沿 z 贯穿，截面在 x-y 平面，center=[x,y]
    """
    if holes is None:
        return []
    if not isinstance(holes, list):
        raise ValueError("[G-03] 几何 holes 必须是数组")
    length, height, thickness = (float(d) for d in dims)
    plane_map = {
        "x": ("y", "z", height, thickness),
        "y": ("x", "z", length, thickness),
        "z": ("x", "y", length, height),
    }
    norm = []
    for i, h in enumerate(holes):
        if not isinstance(h, dict):
            raise ValueError(f"[G-03] 第 {i+1} 个孔必须是对象")
        axis = str(h.get("axis") or "z").strip().lower()
        if axis not in plane_map:
            raise ValueError(f"[G-03] 第 {i+1} 个孔 axis 必须是 x/y/z，得到「{axis}」")
        try:
            r = float(h["radius"])
        except (KeyError, TypeError, ValueError):
            raise ValueError(f"[G-03] 第 {i+1} 个孔缺少数字 radius")
        if not (r > 0.0):
            raise ValueError(f"[G-03] 第 {i+1} 个孔 radius 必须 > 0")
        c = h.get("center")
        if not isinstance(c, (list, tuple)) or len(c) != 2:
            raise ValueError(f"[G-03] 第 {i+1} 个孔 center 必须是 [c1, c2] 两个数")
        try:
            c1, c2 = float(c[0]), float(c[1])
        except (TypeError, ValueError):
            raise ValueError(f"[G-03] 第 {i+1} 个孔 center 必须是数字")
        a1, a2, d1, d2 = plane_map[axis]
        # 留 5% 余量，避免孔贴边导致孔壁与外表面重合/退化
        margin = 0.05 * r
        if r + margin > min(d1, d2) / 2.0:
            raise ValueError(
                f"[G-03] 第 {i+1} 个孔（{axis}轴）半径 {r} 过大，超过截面半宽的一半"
            )
        if not (r + margin <= c1 <= d1 - r - margin):
            raise ValueError(
                f"[G-03] 第 {i+1} 个孔（{axis}轴）center[0]={c1} 超出范围 "
                f"[{r + margin:.4g}, {d1 - r - margin:.4g}]（{a1} 方向尺寸 {d1}）"
            )
        if not (r + margin <= c2 <= d2 - r - margin):
            raise ValueError(
                f"[G-03] 第 {i+1} 个孔（{axis}轴）center[1]={c2} 超出范围 "
                f"[{r + margin:.4g}, {d2 - r - margin:.4g}]（{a2} 方向尺寸 {d2}）"
            )
        norm.append({"axis": axis, "center": [c1, c2], "radius": r})
    return norm


def _hex_in_hole(centers, hole):
    """返回布尔数组：六面体形心是否落入该贯穿圆柱孔内。"""
    axis = hole["axis"]
    r = float(hole["radius"])
    c1, c2 = float(hole["center"][0]), float(hole["center"][1])
    r2 = r * r
    if axis == "x":
        return (centers[:, 1] - c1) ** 2 + (centers[:, 2] - c2) ** 2 <= r2
    if axis == "y":
        return (centers[:, 0] - c1) ** 2 + (centers[:, 2] - c2) ** 2 <= r2
    return (centers[:, 0] - c1) ** 2 + (centers[:, 1] - c2) ** 2 <= r2


def _project_nodes_to_hole_boundaries(nodes: np.ndarray, holes) -> None:
    """把落入任一贯穿孔内的节点，沿截面径向投影到该孔的边界上（原地修改）。

    仅改变垂直于孔轴平面内的坐标，轴向坐标不变；恰在圆上的节点不动。
    作用：让阶梯挖孔的孔壁变成随网格加密趋近真圆的多边形，避免粗网格下
    圆孔退化为方孔（跨界六面体的内角点被拉到圆上，而非整块删除）。
    """
    if not holes:
        return
    for hole in holes:
        axis = hole["axis"]
        r = float(hole["radius"])
        c1, c2 = float(hole["center"][0]), float(hole["center"][1])
        r2 = r * r
        if axis == "x":
            d1 = nodes[:, 1] - c1
            d2 = nodes[:, 2] - c2
        elif axis == "y":
            d1 = nodes[:, 0] - c1
            d2 = nodes[:, 2] - c2
        else:
            d1 = nodes[:, 0] - c1
            d2 = nodes[:, 1] - c2
        dist2 = d1 * d1 + d2 * d2
        inside = dist2 < r2  # 严格在圆内才投影，圆上/圆外不动
        n_in = int(np.count_nonzero(inside))
        if n_in == 0:
            continue
        d1i = d1[inside]
        d2i = d2[inside]
        dist = np.sqrt(dist2[inside])
        zero = dist < 1e-12
        safe = np.where(zero, 1.0, dist)
        scale = r / safe
        new1 = c1 + d1i * scale
        new2 = c2 + d2i * scale
        # 恰在圆心的节点无径向方向，沿 +c1 轴投影到 (c1+r, c2)
        if np.any(zero):
            new1[zero] = c1 + r
            new2[zero] = c2
        if axis == "x":
            nodes[inside, 1] = new1
            nodes[inside, 2] = new2
        elif axis == "y":
            nodes[inside, 0] = new1
            nodes[inside, 2] = new2
        else:
            nodes[inside, 0] = new1
            nodes[inside, 1] = new2


def _reorient_tets(nodes: np.ndarray, tets: np.ndarray) -> None:
    """有向体积为负的四面体交换节点 2/3，保证 B 矩阵与体积正定（原地修改）。

    节点坐标被投影/映射改变后需重新调用（build_box_mesh 的修正仅基于原始节点）。
    """
    p = nodes[tets]
    vol6 = np.einsum(
        "ei,ei->e", np.cross(p[:, 1] - p[:, 0], p[:, 2] - p[:, 0]), p[:, 3] - p[:, 0]
    )
    flip = vol6 < 0.0
    if np.any(flip):
        tets[flip] = tets[flip][:, [0, 2, 1, 3]]


def build_box_with_holes_mesh(
    nx: int, ny: int, nz: int,
    length: float, height: float, thickness: float,
    holes, split: str = "main",
) -> Tuple[np.ndarray, np.ndarray]:
    """结构化六面体 box 网格，移除形心落入任一贯穿圆孔内的六面体单元。

    以六面体体形心为判据（main/alt 双剖分移除相同单元，保证节点集与单元数一致），
    移除后清理悬空节点并重映射，孔壁自然成为自由表面（boundary_faces 自动暴露）。
    对落入孔内的节点沿径向投影到圆孔边界，孔壁呈多边形并随网格加密趋近真圆
    （避免粗网格下圆孔退化为阶梯方孔）；形心判据仍用原始网格坐标，main/alt
    双剖分移除相同单元，保证节点集与单元数一致。
    """
    nodes, tets = build_box_mesh(nx, ny, nz, length, height, thickness, split=split)
    # 把落入孔内的节点投影到圆孔边界（孔壁多边形化，趋近真圆）
    _project_nodes_to_hole_boundaries(nodes, holes)
    # 投影可能改变个别四面体的有向体积符号，重新修正
    _reorient_tets(nodes, tets)
    n_hex = nx * ny * nz
    # 六面体形心，顺序与 build_box_mesh 的 cells 一致（iz 外 / iy 中 / ix 内）
    cx = 0.5 * (np.linspace(0.0, float(length), nx + 1)[:-1]
                + np.linspace(0.0, float(length), nx + 1)[1:])
    cy = 0.5 * (np.linspace(0.0, float(height), ny + 1)[:-1]
                + np.linspace(0.0, float(height), ny + 1)[1:])
    cz = 0.5 * (np.linspace(0.0, float(thickness), nz + 1)[:-1]
                + np.linspace(0.0, float(thickness), nz + 1)[1:])
    centers = np.empty((n_hex, 3))
    idx = 0
    for iz in range(nz):
        for iy in range(ny):
            for ix in range(nx):
                centers[idx, 0] = cx[ix]
                centers[idx, 1] = cy[iy]
                centers[idx, 2] = cz[iz]
                idx += 1
    keep = np.ones(n_hex, dtype=bool)
    for hole in holes:
        keep &= ~_hex_in_hole(centers, hole)
    # 每个六面体对应 6 个连续 tetra（build_box_mesh 的 reshape 顺序）
    tets = tets[np.repeat(keep, 6)]
    if len(tets) == 0:
        raise ValueError("[G-03] 挖孔后无剩余实体：孔过大或位置越界，已挖空整个构件")
    # 移除悬空节点并重映射为连续编号
    used = np.unique(tets)
    remap = np.full(len(nodes), -1, dtype=int)
    remap[used] = np.arange(len(used))
    tets = remap[tets]
    nodes = nodes[used]
    return nodes, tets


def build_cylinder_mesh(
    radius: float, height: float, n_grid: int, ny: int, split: str = "main"
) -> Tuple[np.ndarray, np.ndarray]:
    """结构化圆柱网格：轴沿 y，半径 radius，高 height（y∈[0,height]）。

    截面用 n_grid×n_grid 方格经「方格→圆盘」映射离散（保证协调网格），
    映射：x = s·√(1-t²/2)，z = t·√(1-s²/2)，(s,t)∈[-1,1]² → 单位圆盘。
    每个六面体按与 build_box_mesh 相同的 6-四面体模式剖分。
    split="main"/"alt" 选择主/镜像对角线剖分（节点编号与单元索引一致）。
    返回 (nodes (n,3), tets (m,4))。
    """
    if split not in ("main", "alt"):
        raise ValueError(f"split 只能是 main/alt，得到「{split}」")
    _cube_tets = _CUBE_TETS if split == "main" else _CUBE_TETS_ALT
    n_grid, ny = int(n_grid), int(ny)
    if n_grid < 1 or ny < 1:
        raise ValueError(f"圆柱网格分段必须 ≥ 1：n_grid={n_grid}, ny={ny}")
    if radius <= 0.0 or height <= 0.0:
        raise ValueError(
            f"圆柱几何尺寸必须为正：radius={radius}, height={height}"
        )

    s = np.linspace(-1.0, 1.0, n_grid + 1)
    t = np.linspace(-1.0, 1.0, n_grid + 1)
    ys = np.linspace(0.0, float(height), ny + 1)
    sg, tg = np.meshgrid(s, t, indexing="ij")  # (ng+1, ng+1)
    # 方格 → 单位圆盘的保形映射（Fong 2015 简化版）
    xs = radius * sg * np.sqrt(np.maximum(1.0 - 0.5 * tg ** 2, 0.0))
    zs = radius * tg * np.sqrt(np.maximum(1.0 - 0.5 * sg ** 2, 0.0))

    def nid(i: int, j: int, k: int) -> int:
        return i + (n_grid + 1) * j + (n_grid + 1) ** 2 * k

    n_nodes = (n_grid + 1) ** 2 * (ny + 1)
    nodes = np.zeros((n_nodes, 3))
    for k in range(ny + 1):
        base = (n_grid + 1) ** 2 * k
        nodes[base:base + (n_grid + 1) ** 2, 0] = xs.ravel(order="F")
        nodes[base:base + (n_grid + 1) ** 2, 1] = ys[k]
        nodes[base:base + (n_grid + 1) ** 2, 2] = zs.ravel(order="F")

    local = np.array(
        [
            [0, 0, 0], [1, 0, 0], [1, 1, 0], [0, 1, 0],
            [0, 0, 1], [1, 0, 1], [1, 1, 1], [0, 1, 1],
        ],
        dtype=int,
    )
    ii, jj, kk = np.meshgrid(
        np.arange(n_grid), np.arange(n_grid), np.arange(ny), indexing="ij"
    )
    cells = np.stack([ii.ravel(order="F"), jj.ravel(order="F"), kk.ravel(order="F")], axis=1)
    corner_ids = np.array(
        [nid(i + di, j + dj, k + dk) for i, j, k in cells for di, dj, dk in local],
        dtype=int,
    ).reshape(-1, 8)
    tets = corner_ids[:, _cube_tets].reshape(-1, 4).copy()

    # 有向体积修正（与 box 相同）
    p = nodes[tets]
    vol6 = np.einsum(
        "ei,ei->e", np.cross(p[:, 1] - p[:, 0], p[:, 2] - p[:, 0]), p[:, 3] - p[:, 0]
    )
    flip = vol6 < 0.0
    if np.any(flip):
        tets[flip] = tets[flip][:, [0, 2, 1, 3]]
    return nodes, tets


def wall_segments(
    length: float,
    height: float,
    thickness: float,
    level: str = "medium",
    segments: Optional[Dict[str, int]] = None,
) -> Tuple[int, int, int]:
    """按网格档位/显式分段返回 (nx, ny, nz)。

    nx 按长高比派生（风压弯曲对墙长方向不敏感，避免无谓自由度）；
    ny 控制墙高方向离散（弯曲精度的主控方向），nz 控制墙厚方向分层。
    """
    if segments:
        try:
            nx = int(segments.get("nx", 0))
            ny = int(segments.get("ny", 0))
            nz = int(segments.get("nz", 0))
        except (TypeError, ValueError):
            raise ValueError("mesh 显式分段必须是整数 nx/ny/nz")
        if nx < 1 or ny < 1 or nz < 1:
            raise ValueError("mesh 显式分段必须是 ≥1 的整数 nx/ny/nz")
        return nx, ny, nz

    key = str(level or "medium").lower()
    if key not in MESH_LEVELS:
        raise ValueError(
            f"未知网格档位「{level}」，可选：{'、'.join(sorted(MESH_LEVELS))}"
        )
    cfg = MESH_LEVELS[key]
    ny = int(cfg["ny"])
    nz = int(cfg["nz"])
    # 墙长方向荷载/几何均匀，解沿 x 不变，nx 只需较少分段；
    # 限制 4~12 段，避免长墙把自由度堆到数十万。
    nx = max(4, min(12, int(round(4.0 * float(length) / float(height)))))
    return nx, ny, nz


# ====================================================================== 本构与刚度
def elastic_matrix(E: float, nu: float) -> np.ndarray:
    """各向同性三维弹性矩阵 D（6×6），应变顺序 [xx,yy,zz,xy,yz,zx]。"""
    if E <= 0.0:
        raise ValueError(f"弹性模量必须为正：E={E}")
    if not (-0.999 < nu < 0.5):
        raise ValueError(f"泊松比超出范围 (-1, 0.5)：nu={nu}")
    lam = E * nu / ((1.0 + nu) * (1.0 - 2.0 * nu))
    mu = E / (2.0 * (1.0 + nu))
    D = np.zeros((6, 6))
    D[:3, :3] = lam
    for i in range(3):
        D[i, i] = lam + 2.0 * mu
    for i in range(3, 6):
        D[i, i] = mu
    return D


def _shape_gradients(
    nodes: np.ndarray, tets: np.ndarray
) -> Tuple[np.ndarray, np.ndarray]:
    """单元形函数梯度 g (m,4,3) 与体积 V (m,)。"""
    p = nodes[tets]  # (m,4,3)
    # 列向量为 p1-p0、p2-p0、p3-p0
    J = np.stack([p[:, 1] - p[:, 0], p[:, 2] - p[:, 0], p[:, 3] - p[:, 0]], axis=2)
    det = np.linalg.det(J)
    V = det / 6.0
    if np.any(V <= 0.0):
        raise ValueError("存在非正体积的四面体单元，请检查网格")
    Jinv = np.linalg.inv(J)
    # g[e,i,a] = Σ_b Jinv[e,b,a] · L[i,b]
    g = np.einsum("eba,ib->eia", Jinv, _LOCAL_GRADS)
    return g, V


def _strain_matrix(g: np.ndarray) -> np.ndarray:
    """TET4 常应变矩阵 B (m,6,12)。"""
    m = g.shape[0]
    B = np.zeros((m, 6, 12))
    for i in range(4):
        for r, c, gc in zip(_B_ROWS, _B_COLS, _B_GRADS):
            B[:, r, 3 * i + c] = g[:, i, gc]
    return B


def _element_dofs(tets: np.ndarray) -> np.ndarray:
    """(m,12) 单元自由度编号（每节点 x/y/z 三个 DOF）。"""
    return (tets[:, :, None] * 3 + np.arange(3)[None, None, :]).reshape(-1, 12)


def assemble_stiffness(
    nodes: np.ndarray, tets: np.ndarray, D: np.ndarray, chunk: int = 8000
) -> Tuple[sp.csr_matrix, np.ndarray, np.ndarray]:
    """组装整体刚度矩阵（分块计算以避免大网格的瞬时内存峰值）。

    返回 (K, B (m,6,12), V (m,))。K_e = V·BᵀDB。
    """
    g, V = _shape_gradients(nodes, tets)
    B = _strain_matrix(g)
    dofs = _element_dofs(tets)
    m = len(tets)
    n = len(nodes)
    rows: List[np.ndarray] = []
    cols: List[np.ndarray] = []
    data: List[np.ndarray] = []
    for start in range(0, m, chunk):
        end = min(m, start + chunk)
        Bc = B[start:end]
        DB = np.einsum("ij,ejk->eik", D, Bc)
        Ke = np.einsum("eji,ejk->eik", Bc, DB) * V[start:end, None, None]
        dc = dofs[start:end]
        rows.append(np.repeat(dc, 12, axis=1).ravel())
        cols.append(np.tile(dc, (1, 12)).ravel())
        data.append(Ke.ravel())
    K = sp.coo_matrix(
        (np.concatenate(data), (np.concatenate(rows), np.concatenate(cols))),
        shape=(3 * n, 3 * n),
    ).tocsr()
    return K, B, V


# ====================================================================== 载荷/边界
def nodes_on_plane(
    nodes: np.ndarray, axis: int, value: float, tol: float = 1e-9
) -> np.ndarray:
    """坐标 axis 分量 ≈ value 的节点索引。"""
    return np.where(np.abs(nodes[:, axis] - float(value)) <= tol)[0]


def _axis_bounds(nodes: np.ndarray, axis: int) -> Tuple[float, float]:
    """返回节点在 axis 方向的 (最小值, 最大值)。"""
    return float(nodes[:, axis].min()), float(nodes[:, axis].max())


def _is_on_boundary(
    nodes: np.ndarray, axis: int, value: float, tol: float = 1e-6
) -> bool:
    """判断坐标值是否落在实体在该轴上的边界（min 或 max）上。

    容差按实体对角线自适应，避免浮点误差把正落在面上的坐标误判为面外。
    """
    lo, hi = _axis_bounds(nodes, axis)
    span = hi - lo
    atol = max(tol, tol * max(abs(lo), abs(hi), 1.0))
    return abs(value - lo) <= atol or abs(value - hi) <= atol


def _point_in_bbox(
    nodes: np.ndarray, xyz: np.ndarray, tol: float = 1e-6
) -> bool:
    """判断点是否落在节点包围盒内（含边界，带自适应容差）。"""
    diag = float(np.linalg.norm(nodes.max(axis=0) - nodes.min(axis=0)))
    atol = max(tol, tol * diag)
    lo = nodes.min(axis=0) - atol
    hi = nodes.max(axis=0) + atol
    return bool(np.all(xyz >= lo) and np.all(xyz <= hi))


# ---- 确定性单位换算（P0：把 LLM 的心算移到代码，避免量纲错误 L-03） ----
# 原则：原始输入只读，换算生成新量，并在输出中同时展示原始值与换算后值。
WATER_DENSITY_KG_M3 = 1000.0
SOIL_DENSITY_KG_M3 = 1800.0  # 湿土容重
SOIL_K0 = 0.5  # 静止侧压力系数


def wind_pressure_pa(wind_speed_m_s: float, cp_net: float = CP_NET) -> float:
    """动压 q=½ρU²，净风压 = cp_net × q（与 solid_wall 口径一致）。"""
    return float(cp_net) * 0.5 * AIR_DENSITY * float(wind_speed_m_s) ** 2


def beaufort_wind_pressure_pa(level: int, cp_net: float = CP_NET) -> float:
    """蒲福风级 → 净风压 Pa。"""
    return wind_pressure_pa(speed_from_beaufort(int(level)), cp_net)


def mass_force_n(mass_kg: float) -> float:
    """质量(kg) → 重力(N) = m·g。"""
    return float(mass_kg) * GRAVITY


def hydrostatic_pressure_pa(depth_m: float, fluid: str = "water") -> float:
    """静水压力 p=ρ·g·h；静止土压力按 K0·γ·h 计算。"""
    fluid = str(fluid or "water").strip().lower()
    if fluid in ("soil", "earth", "土"):
        return SOIL_K0 * SOIL_DENSITY_KG_M3 * GRAVITY * float(depth_m)
    return WATER_DENSITY_KG_M3 * GRAVITY * float(depth_m)


def fixed_dofs(nodes: np.ndarray, node_ids: np.ndarray) -> np.ndarray:
    """给定节点集的三向固定自由度（x/y/z 全部约束）。"""
    ids = np.asarray(node_ids, dtype=int)
    return np.unique((ids[:, None] * 3 + np.arange(3)[None, :]).ravel())


def boundary_faces(tets: np.ndarray) -> np.ndarray:
    """提取表面三角形面片（只被一个单元使用的面）。

    返回 (k,3) 节点索引数组，供面力/压力加载与可视化使用。
    """
    faces = np.vstack(
        [
            tets[:, [0, 1, 2]],
            tets[:, [0, 1, 3]],
            tets[:, [0, 2, 3]],
            tets[:, [1, 2, 3]],
        ]
    )
    key = np.sort(faces, axis=1)
    uniq, inverse, counts = np.unique(
        key, axis=0, return_inverse=True, return_counts=True
    )
    # 用键反查原始面：同一键的节点顺序可能不同，取排序后的键做三角形即可
    #（三角形几何与顶点顺序无关；面力按坐标计算，不依赖绕向）
    return uniq[counts == 1]


def faces_on_plane(
    nodes: np.ndarray, faces: np.ndarray, axis: int, value: float, tol: float = 1e-9
) -> np.ndarray:
    """筛选三个顶点都位于给定坐标平面上的表面三角形。"""
    mask = np.all(np.abs(nodes[faces, axis] - float(value)) <= tol, axis=1)
    return faces[mask]


def apply_face_traction(
    f: np.ndarray,
    nodes: np.ndarray,
    faces: np.ndarray,
    traction_fn,
) -> float:
    """面力/压力 → 一致节点力（3 点中点积分，对线性面力精确）。

    traction_fn(p) 返回该点处的面力矢量（N/m²，指向墙体内部即对墙体的作用力）。
    返回施加的总力矢量（N），供平衡校核。
    """
    if faces.size == 0:
        raise ValueError("[L-02] 加载面上没有三角形面片：请检查压力面定位（axis/at/value）是否落在实体外表面且有面积，或几何是否退化")
    tri = nodes[faces]  # (k,3,3)
    a, b, c = tri[:, 0], tri[:, 1], tri[:, 2]
    area = 0.5 * np.linalg.norm(np.cross(b - a, c - a), axis=1)
    total = np.zeros(3)
    # 3 个边中点积分：端点形函数各 1/2
    mids = [0.5 * (a + b), 0.5 * (b + c), 0.5 * (c + a)]
    pairs = [(faces[:, 0], faces[:, 1]), (faces[:, 1], faces[:, 2]), (faces[:, 2], faces[:, 0])]
    for (n0, n1), mid in zip(pairs, mids):
        tq = np.array([traction_fn(p) for p in mid], dtype=float)  # (k,3)
        w = (area / 3.0)[:, None] * 0.5 * tq
        np.add.at(f, n0 * 3, w[:, 0])
        np.add.at(f, n0 * 3 + 1, w[:, 1])
        np.add.at(f, n0 * 3 + 2, w[:, 2])
        np.add.at(f, n1 * 3, w[:, 0])
        np.add.at(f, n1 * 3 + 1, w[:, 1])
        np.add.at(f, n1 * 3 + 2, w[:, 2])
        total += 2.0 * w.sum(axis=0)  # 两端点各一份
    return total


def apply_body_force(
    f: np.ndarray, tets: np.ndarray, V: np.ndarray, accel: Sequence[float]
) -> float:
    """自重/体积力：按单元体积均分到 4 个节点，返回总力矢量（N）。"""
    a = np.asarray(accel, dtype=float)
    w = V / 4.0
    total = np.zeros(3)
    for comp in range(3):
        contrib = w * a[comp]
        for i in range(4):
            np.add.at(f, tets[:, i] * 3 + comp, contrib)
        total[comp] = contrib.sum() * 4.0
    return total


# ====================================================================== 求解
def solve_solid(
    nodes: np.ndarray,
    tets: np.ndarray,
    E: float,
    nu: float,
    fixed: np.ndarray,
    face_loads: Optional[List[Dict[str, Any]]] = None,
    body_accel: Optional[Sequence[float]] = None,
    point_forces: Optional[Sequence[Tuple[int, Sequence[float]]]] = None,
) -> Dict[str, Any]:
    """TET4 线弹性静力求解。

    face_loads 为 [{"faces": (k,3), "traction_fn": fn}]；body_accel 按单位体积力
    （N/m³）直接使用，自重请传 (0, -ρ·g, 0)；point_forces 为 [(节点号, [Fx,Fy,Fz])]。
    返回位移 u（3n,）、单元应力 sig（m,6）、B、V、反力等。
    """
    t_start = time.perf_counter()
    if fixed.size == 0:
        raise ValueError("约束自由度为空：至少需要固定墙底/支座面")
    D = elastic_matrix(float(E), float(nu))
    K, B, V = assemble_stiffness(nodes, tets, D)
    f = np.zeros(3 * len(nodes))
    applied = np.zeros(3)
    for item in face_loads or []:
        applied += apply_face_traction(f, nodes, item["faces"], item["traction_fn"])
    if body_accel is not None:
        applied += apply_body_force(f, tets, V, body_accel)
    for nid, vec in point_forces or []:
        fv = np.asarray(vec, dtype=float).reshape(3)
        f[int(nid) * 3:int(nid) * 3 + 3] += fv
        applied += fv
    t_assembled = time.perf_counter()

    n_dof = 3 * len(nodes)
    free = np.setdiff1d(np.arange(n_dof), fixed)
    Kff = K[free][:, free].tocsc()
    rhs = f[free]
    if free.size > CG_DOF_THRESHOLD:
        method = "cg+jacobi"
        diag = Kff.diagonal()
        M = sp.diags(1.0 / np.maximum(diag, 1e-30))
        try:
            x, info = spla.cg(Kff, rhs, rtol=CG_TOLERANCE, M=M, maxiter=20000)
        except TypeError:  # scipy ≤1.11
            x, info = spla.cg(Kff, rhs, tol=CG_TOLERANCE, M=M, maxiter=20000)
        if info != 0:
            raise RuntimeError(
                f"[G-03] CG 迭代未收敛（info={info}），模型可能过柔或网格畸变。建议改用更粗网格档位（coarse）、简化模型几何或增大约束后重试"
            )
    else:
        method = "direct(spsolve)"
        x = spla.spsolve(Kff, rhs, permc_spec="MMD_AT_PLUS_A")
    u = np.zeros(n_dof)
    u[free] = x
    t_solved = time.perf_counter()

    # 单元常应变/应力
    ue = u[_element_dofs(tets)]
    eps = np.einsum("eij,ej->ei", B, ue)
    sig = eps @ D.T
    # 支反力（约束 DOF 上的不平衡力）；按自由度分量归并（不是按排序后的位置取模）
    residual = K @ u - f
    reactions = residual[fixed]
    comp = fixed % 3
    reaction_force = np.array(
        [float(reactions[comp == i].sum()) for i in range(3)]
    )

    return {
        "u": u,
        "sigma": sig,
        "B": B,
        "V": V,
        "D": D,
        "K": K,
        "f": f,
        "applied_force": applied,
        "reaction_force": reaction_force,
        "dof": int(n_dof),
        "n_elements": int(len(tets)),
        "n_vertices": int(len(nodes)),
        "solver_method": method,
        "assemble_time_s": t_assembled - t_start,
        "solve_time_s": t_solved - t_assembled,
        "total_time_s": time.perf_counter() - t_start,
    }


# ====================================================================== 应力后处理
def principal_stresses(sig: np.ndarray) -> np.ndarray:
    """单元应力 (m,6) → 主应力 (m,3)，按升序返回 [σ3, σ2, σ1]。"""
    m = sig.shape[0]
    S = np.zeros((m, 3, 3))
    S[:, 0, 0] = sig[:, 0]
    S[:, 1, 1] = sig[:, 1]
    S[:, 2, 2] = sig[:, 2]
    S[:, 0, 1] = S[:, 1, 0] = sig[:, 3]
    S[:, 1, 2] = S[:, 2, 1] = sig[:, 4]
    S[:, 0, 2] = S[:, 2, 0] = sig[:, 5]
    return np.linalg.eigvalsh(S)


def von_mises(sig: np.ndarray) -> np.ndarray:
    """单元 von Mises 等效应力（Pa）。"""
    return np.sqrt(
        0.5
        * (
            (sig[:, 0] - sig[:, 1]) ** 2
            + (sig[:, 1] - sig[:, 2]) ** 2
            + (sig[:, 2] - sig[:, 0]) ** 2
        )
        + 3.0 * (sig[:, 3] ** 2 + sig[:, 4] ** 2 + sig[:, 5] ** 2)
    )


def nodal_average(element_values: np.ndarray, tets: np.ndarray, n_nodes: int) -> np.ndarray:
    """单元量 → 节点平均（供渲染/读数）。"""
    acc = np.zeros(n_nodes)
    cnt = np.zeros(n_nodes)
    idx = tets.ravel()
    vals = np.repeat(np.asarray(element_values, dtype=float), 4)
    np.add.at(acc, idx, vals)
    np.add.at(cnt, idx, 1.0)
    return acc / np.maximum(cnt, 1.0)


def recover_stress_spr(
    nodes: np.ndarray, tets: np.ndarray, sig: np.ndarray, n_samples: int = 32
) -> np.ndarray:
    """超收敛片恢复（SPR）：单元常应力 → 节点应力 (n,6)。

    对每个节点取最近的若干单元形心为采样点（含多层单元，边界节点也能沿法向
    外推），逐个应力分量拟合线性场 σ(x)=a0+a1(x−x₀)+a2(y−y₀)+a3(z−z₀)，
    节点值取 a0。TET4 的应力在单元内为常量，直接平均会在表层/弯曲区低估极值；
    片恢复把线性分布还原后再取节点值，对弯曲为主的实体能明显改善表层应力精度。
    片内采样点共面（秩亏）时退化为邻域算术平均，避免病态外推。
    """
    n_nodes = len(nodes)
    n_comp = sig.shape[1]
    if len(tets) < 4:
        return np.tile(sig.mean(axis=0), (n_nodes, 1))

    cent = nodes[tets].mean(axis=1)
    k = int(min(max(int(n_samples), 8), len(tets)))
    _, idx = cKDTree(cent).query(nodes, k=k)       # (n,k)
    dx = cent[idx] - nodes[:, None, :]             # (n,k,3)
    phi = np.concatenate([np.ones((n_nodes, k, 1)), dx], axis=2)  # (n,k,4)
    A = np.einsum("nkp,nkq->npq", phi, phi)        # (n,4,4)
    b = np.einsum("nkp,nkc->npc", phi, sig[idx])   # (n,4,6)

    # 秩亏保护：片共面时线性项不可辨识，直接取邻域平均
    eig = np.linalg.eigvalsh(A)
    ok = eig[:, 0] > 1e-8 * np.maximum(eig[:, -1], 1e-30)
    out = np.empty((n_nodes, n_comp))
    good = np.where(ok)[0]
    if good.size:
        out[good] = np.linalg.solve(A[good], b[good])[:, 0, :]
    bad = np.where(~ok)[0]
    if bad.size:
        out[bad] = sig[idx[bad]].mean(axis=1)
    return out


def root_bending_fit(
    nodes: np.ndarray,
    tets: np.ndarray,
    sig: np.ndarray,
    nx: int,
    ny: int,
    length: float,
    height: float,
    thickness: float,
) -> Dict[str, float]:
    """墙根弯曲应力：取墙高中部第一层单元，沿墙厚方向线性拟合后外推到墙面。

    常应变单元在表层会低估极值纤维应力，线性外推可恢复梁式弯曲的线性应力分布；
    返回墙面 z=0 / z=T 处的 σyy、弯曲幅值 (front-back)/2 与轴力分量 (front+back)/2。
    """
    cent = nodes[tets].mean(axis=1)
    dx = length / max(nx, 1)
    dy = height / max(ny, 1)
    sel = (np.abs(cent[:, 0] - 0.5 * length) <= dx) & (
        cent[:, 1] <= 1.01 * dy
    )
    if sel.sum() < 4:
        sel = cent[:, 1] <= 2.01 * dy
    if sel.sum() < 4:
        return {"front_Pa": 0.0, "back_Pa": 0.0, "bend_Pa": 0.0, "axial_Pa": 0.0}

    z = cent[sel, 2]
    s = sig[sel, 1]
    z_key = np.round(z, 9)
    z_unique = np.unique(z_key)
    means = np.array([s[z_key == zv].mean() for zv in z_unique])
    if z_unique.size >= 2:
        coef = np.polyfit(z_unique, means, 1)
        front = float(np.polyval(coef, 0.0))
        back = float(np.polyval(coef, float(thickness)))
    else:
        front = back = float(means[0])
    return {
        "front_Pa": front,
        "back_Pa": back,
        "bend_Pa": 0.5 * (front - back),
        "axial_Pa": 0.5 * (front + back),
        "y_eval_m": float(np.mean(cent[sel, 1])),
    }


# ====================================================================== 主流程
def run_solid(
    model: Dict[str, Any], library: Optional[MaterialsLibrary] = None
) -> Dict[str, Any]:
    """求解墙体三维实体风压问题，返回 JSON 安全的结果字典。

    支持的模型字段（Kotlin/JSON 传入）：
        kind: "solid_wall"（默认，也接受 solid/wall）
        wall / 顶层: length_m、height_m、thickness_m
        material: 材料名（materials.json）
        density_kg_m3 / E_MPa / nu: 可选覆盖
        pressure_Pa: 直接均布风压（优先）
        或 wind_speed_m_s / beaufort（几级风）+ cp_net（默认 1.2）
        mesh_level: coarse/medium/fine/ultra，或 mesh={nx,ny,nz} 显式分段
    """
    if not isinstance(model, dict):
        raise ValueError("实体模型必须是 JSON 对象")

    kind = str(model.get("kind") or model.get("scenario") or "solid_wall").strip().lower()
    if kind not in ("solid_wall", "solid", "wall", "wind_wall"):
        raise ValueError(f"暂不支持的实体场景「{kind}」（当前支持：solid_wall 风压墙体）")

    wall = model.get("wall") or {}
    if not isinstance(wall, dict):
        raise ValueError("wall 必须是对象（含 length_m / height_m / thickness_m）")

    def _num(src: Dict[str, Any], *keys: str) -> Optional[float]:
        for key in keys:
            if key in src and src[key] is not None:
                try:
                    return float(src[key])
                except (TypeError, ValueError):
                    raise ValueError(f"参数 {key} 必须是数字")
        return None

    length = _num(wall, "length_m", "length", "width_m")
    if length is None:
        length = _num(model, "length_m", "length", "width_m")
    height = _num(wall, "height_m", "height")
    if height is None:
        height = _num(model, "height_m", "height")
    thickness = _num(wall, "thickness_m", "thickness")
    if thickness is None:
        thickness = _num(model, "thickness_m", "thickness")
    if length is None or height is None or thickness is None:
        raise ValueError(
            "缺少墙体尺寸：需要 length_m（墙长）、height_m（墙高）、thickness_m（墙厚）"
        )
    if not (0.2 <= height <= 30.0):
        raise ValueError(f"墙高 h={height:g} m 超出范围（0.2~30 m）")
    if not (0.03 <= thickness <= 3.0):
        raise ValueError(
            f"墙厚 t={thickness:g} m 超出范围（0.03~3 m），请检查是否填成了毫米"
        )
    if not (0.2 <= length <= 200.0):
        raise ValueError(f"墙长 L={length:g} m 超出范围（0.2~200 m）")

    # ---- 材料 ----
    name = str(model.get("material") or "").strip()
    if not name:
        raise ValueError("缺少墙体材料：请说明是砖砌体、混凝土还是其他材料")
    lib = library if library is not None else load_library()
    rec = lib.find_material(name)
    if rec is None:
        avail = "、".join(r["name"] for r in lib.all_materials())
        raise ValueError(f"材料库中没有「{name}」，可选材料：{avail}")
    mat_name = str(rec.get("name") or name)
    e_override = _num(model, "E_MPa")
    E = e_override * 1e6 if e_override is not None else float(rec.get("elastic_modulus") or 0.0)
    nu_override = _num(model, "nu")
    nu = nu_override if nu_override is not None else float(rec.get("poisson_ratio") or 0.3)
    density_override = _num(model, "density_kg_m3")
    density = (density_override if density_override is not None
               else float(rec.get("density") or 1800.0))
    strength = float(rec.get("strength") or 1.0)
    if E <= 0.0:
        raise ValueError("材料弹性模量必须为正")
    if density <= 0.0:
        raise ValueError("材料密度必须为正")

    # ---- 风压/压力 ----
    pressure = _num(model, "pressure_Pa", "pressure_pa")
    speed: Optional[float] = None
    level: Optional[int] = None
    q = 0.0
    cp = float(model.get("cp_net") or CP_NET)
    if cp <= 0.0:
        raise ValueError("净体型系数 cp_net 必须为正")
    if pressure is not None:
        if pressure < 0.0:
            raise ValueError("均布压力 pressure_Pa 不能为负")
        q = pressure / cp
        speed = math.sqrt(2.0 * q / AIR_DENSITY) if q > 0 else 0.0
        level = beaufort_from_speed(speed)
    else:
        speed = _num(model, "wind_speed_m_s", "speed_m_s", "wind_speed")
        lv = model.get("beaufort")
        if speed is None:
            if lv is None:
                raise ValueError(
                    "缺少风压：请给 wind_speed_m_s（m/s）、beaufort（几级风）"
                    "或 pressure_Pa（均布压力）"
                )
            try:
                lv = int(lv)
            except (TypeError, ValueError):
                raise ValueError("beaufort 风级必须是 0~17 的整数")
            speed = speed_from_beaufort(lv)
        if not (0.5 <= speed <= 80.0):
            raise ValueError(f"风速 {speed:g} m/s 超出范围（0.5~80 m/s）")
        q = 0.5 * AIR_DENSITY * speed ** 2
        pressure = cp * q
        level = beaufort_from_speed(speed)
    if speed is None:
        speed = 0.0
    if level is None:
        level = 0

    # ---- 网格 ----
    mesh_level = str(model.get("mesh_level") or "medium")
    segments = model.get("mesh")
    if segments is not None and not isinstance(segments, dict):
        raise ValueError("mesh 必须是 {nx,ny,nz} 对象")
    nx, ny, nz = wall_segments(length, height, thickness, mesh_level, segments)

    t_start = time.perf_counter()
    nodes, tets = build_box_mesh(nx, ny, nz, length, height, thickness)
    faces = boundary_faces(tets)
    front_faces = faces_on_plane(nodes, faces, 2, 0.0)
    if front_faces.size == 0:
        raise ValueError("未找到迎风面 z=0 的表面单元，请检查网格")

    p_vec = np.array([0.0, 0.0, float(pressure)])

    def traction(_p) -> np.ndarray:  # 均布风压沿 +z 指向墙内
        return p_vec

    base_nodes = nodes_on_plane(nodes, 1, 0.0)
    fixed = fixed_dofs(nodes, base_nodes)

    solved = solve_solid(
        nodes,
        tets,
        E=float(E),
        nu=float(nu),
        fixed=fixed,
        face_loads=[{"faces": front_faces, "traction_fn": traction}],
        body_accel=(0.0, -density * GRAVITY, 0.0),
    )
    t_solved = time.perf_counter()
    u = solved["u"]
    sig = solved["sigma"]
    tets_arr = tets

    # ---- 位移读数 ----
    u3 = u.reshape(-1, 3)
    disp_mag = np.linalg.norm(u3, axis=1)
    top = nodes_on_plane(nodes, 1, height)
    top_center = top[np.abs(nodes[top, 0] - 0.5 * length) <= max(length / nx * 0.51, 1e-9)]
    if top_center.size == 0:
        top_center = top
    delta_fe_m = float(np.mean(np.abs(u3[top_center, 2])))
    max_disp_m = float(disp_mag.max())

    # ---- 应力读数 ----
    pr = principal_stresses(sig)
    sig_node = recover_stress_spr(nodes, tets_arr, sig)
    pr_node = principal_stresses(sig_node)
    vm_node = von_mises(sig_node)
    fixed_node_mask = np.zeros(len(nodes), dtype=bool)
    fixed_node_mask[base_nodes] = True
    touches_base = np.any(fixed_node_mask[tets_arr], axis=1)
    inner = ~touches_base
    if not np.any(inner):
        inner = np.ones(len(tets_arr), dtype=bool)
    max_tension_pa = float(pr[inner, 2].max())
    max_compression_pa = float(-pr[inner, 0].min())
    # 节点 SPR 值同样避开墙底约束面及其相邻节点（约束点存在应力奇异）
    near_base = np.zeros(len(nodes), dtype=bool)
    near_base[base_nodes] = True
    near_base[tets_arr[touches_base].ravel()] = True
    valid_node = ~near_base
    if not np.any(valid_node):
        valid_node = np.ones(len(nodes), dtype=bool)
    max_tension_pa = max(max_tension_pa, float(pr_node[valid_node, 2].max()))
    max_compression_pa = max(max_compression_pa, float(-pr_node[valid_node, 0].min()))
    # 根部拟合（含自重轴力），用第一层单元外推到墙面
    fit = root_bending_fit(nodes, tets_arr, sig, nx, ny, length, height, thickness)
    bend_mpa = abs(fit["bend_Pa"]) / 1e6
    net_tension_mpa = fit["front_Pa"] / 1e6 if fit["front_Pa"] > 0 else 0.0
    max_tension_mpa = max(max_tension_pa / 1e6, net_tension_mpa)
    max_comp_mpa = max_compression_pa / 1e6

    # ---- 理论校核（单位墙宽悬臂条带）----
    I = thickness ** 3 / 12.0
    delta_th_m = pressure * height ** 4 / (8.0 * float(E) * I) if pressure > 0 else 0.0
    sigma_th_pa = 3.0 * pressure * height ** 2 / thickness ** 2
    delta_err_pct = (
        abs(delta_fe_m / delta_th_m - 1.0) * 100.0 if delta_th_m > 1e-12 else 0.0
    )
    bend_err_pct = (
        abs(bend_mpa / (sigma_th_pa / 1e6) - 1.0) * 100.0 if sigma_th_pa > 1e-6 else 0.0
    )

    # ---- 工程校核 ----
    weight = density * GRAVITY * length * height * thickness
    force = pressure * length * height
    m_overturn = force * height / 2.0
    m_resist = weight * thickness / 2.0
    sf_overturn = m_resist / m_overturn if m_overturn > 0 else 999.0

    tension_limit = float(_tension_limit(mat_name, strength))
    comp_limit = strength
    sf_bend = tension_limit / bend_mpa if bend_mpa > 1e-9 else 999.0
    sf_comp = comp_limit / max_comp_mpa if max_comp_mpa > 1e-9 else 999.0
    sf_min = min(sf_overturn, sf_bend, sf_comp)
    if sf_min >= SAFETY_SAFE:
        verdict = "安全"
    elif sf_min >= SAFETY_CAUTION:
        verdict = "注意"
    else:
        verdict = "危险"

    checks = [
        {
            "name": "墙根抗弯",
            "value": round(bend_mpa, 4),
            "limit": round(tension_limit, 4),
            "unit": "MPa",
            "safety_factor": round(sf_bend, 2),
            "pass": sf_bend >= SAFETY_CAUTION,
        },
        {
            "name": "墙体抗压",
            "value": round(max_comp_mpa, 4),
            "limit": round(comp_limit, 4),
            "unit": "MPa",
            "safety_factor": round(sf_comp, 2),
            "pass": sf_comp >= SAFETY_CAUTION,
        },
        {
            "name": "抗倾覆",
            "value": round(m_overturn, 1),
            "limit": round(m_resist, 1),
            "unit": "N·m",
            "safety_factor": round(sf_overturn, 2),
            "pass": sf_overturn >= SAFETY_CAUTION,
        },
    ]
    if is_en():
        checks[0]["name"] = "Bending at wall base"
        checks[1]["name"] = "Wall compression"
        checks[2]["name"] = "Overturning resistance"
        if is_imperial():
            for _c in checks[:2]:
                _c["value"] = round(_c["value"] * PSI_PER_MPA, 1)
                _c["limit"] = round(_c["limit"] * PSI_PER_MPA, 1)
                _c["unit"] = "psi"
            checks[2]["value"] = round(checks[2]["value"] * LBF_PER_N * FT_PER_M, 1)
            checks[2]["limit"] = round(checks[2]["limit"] * LBF_PER_N * FT_PER_M, 1)
            checks[2]["unit"] = "lbf·ft"

    # 临界风速：弯曲与倾覆两条路径反解，取更小者
    crit: List[Tuple[str, float]] = []
    if cp > 0:
        p_bend_crit = tension_limit * 1e6 * thickness ** 2 / (3.0 * height ** 2)
        crit.append(
            ("墙根开裂", math.sqrt(2.0 * p_bend_crit / (cp * AIR_DENSITY)))
        )
        crit.append(
            (
                "整体倾覆",
                math.sqrt(
                    2.0 * density * GRAVITY * thickness ** 2
                    / (cp * height * AIR_DENSITY)
                ),
            )
        )
    crit.sort(key=lambda item: item[1])
    crit_name, crit_speed = crit[0] if crit else ("—", float("inf"))
    if is_en():
        crit_name = {"墙根开裂": "base cracking",
                     "整体倾覆": "overturning"}.get(crit_name, crit_name)
    crit_level = beaufort_from_speed(crit_speed) if math.isfinite(crit_speed) else 0

    if sf_overturn <= sf_bend and sf_overturn <= sf_comp:
        weak_part = "墙体底部（迎风侧墙脚）"
        failure = "整体倾覆：风压合力作用在墙高中部，墙体绕迎风侧墙脚翻转"
        weak_part_en = "wall base (windward toe)"
        failure_en = ("Overturning failure: the resultant wind pressure acts at mid-height and "
                      "the wall rotates about its windward toe")
    elif sf_comp <= sf_bend:
        weak_part = "墙体根部受压侧"
        failure = "受压破坏：根部压应力超过砌体抗压强度"
        weak_part_en = "wall base on the compression side"
        failure_en = ("Compression failure: stress at the base exceeds the masonry "
                      "compressive strength")
    else:
        weak_part = "墙体根部（受拉面水平灰缝）"
        failure = "根部开裂：受拉面灰缝先裂，裂缝自墙脚向上发展，严重时整片墙倒伏"
        weak_part_en = "wall base (horizontal mortar joint on the tension face)"
        failure_en = ("Base cracking: mortar joints on the tension face crack first, the crack "
                      "grows upward from the toe, and the whole wall may fall over in severe cases")
    if is_en():
        weak_part, failure = weak_part_en, failure_en

    # ---- 建议 ----
    need = SAFETY_SAFE / max(sf_min, 1e-6)
    advice: List[str] = []
    if is_en():
        if sf_min < SAFETY_SAFE:
            if sf_bend <= sf_overturn:
                t_req = thickness * math.sqrt(need)
                advice.append(
                    f"Thicken the wall: increase the thickness from "
                    f"{length_mm(thickness * 1000)} to "
                    f"{length_mm(math.ceil(t_req * 1000 / 10) * 10)} "
                    f"(about {need:.2f}×), raising the bending safety factor above 2.0"
                )
            else:
                advice.append(
                    f"Increase the wall thickness to about "
                    f"{length_mm(thickness * math.sqrt(need) * 1000)}, or lower the wall "
                    f"height to below {length_m(height / need)}, so the overturning "
                    f"safety factor reaches 2.0"
                )
            advice.append(
                f"Add brick buttresses or reinforced concrete tie columns every "
                f"{length_m(2.0)}–{length_m(3.0)}, turning the freestanding wall into "
                f"laterally supported panels"
            )
            advice.append(
                "Cap the wall top and tie the base securely to the foundation; during "
                "high-wind warnings, add temporary diagonal bracing on both faces"
            )
        else:
            advice.append(
                "Safety factors are adequate for the current wind pressure; in typhoon "
                "season still check that the wall cap and base tie are sound"
            )
    elif sf_min < SAFETY_SAFE:
        if sf_bend <= sf_overturn:
            t_req = thickness * math.sqrt(need)
            advice.append(
                f"加厚墙体：墙厚由 {thickness * 1000:.0f} mm 增至 "
                f"{math.ceil(t_req * 1000 / 10) * 10:.0f} mm（约 {need:.2f} 倍），"
                f"抗弯安全系数可提高到 2.0 以上"
            )
        else:
            advice.append(
                f"加大墙厚或降低墙高：墙厚增至约 {thickness * math.sqrt(need) * 1000:.0f} mm，"
                f"或墙高降到 {height / need:.2f} m 以下，抗倾覆安全系数可达 2.0"
            )
        advice.append("每隔 2~3 m 设置砖砌扶壁柱或钢筋混凝土构造柱，把独立墙变成有侧向支点的墙段")
        advice.append("墙顶做压顶、墙脚与基础可靠拉结；大风预警时在墙两侧临时加设斜撑")
    else:
        advice.append("当前风压下安全系数达标；台风季仍建议检查墙顶压顶与墙脚拉结是否牢固")

    # ---- 通俗叙述 ----
    if is_en():
        narrative = [
            f"Model: the problem is simplified to a {length_m(height)} high, "
            f"{length_mm(thickness * 1000)} thick, {length_m(length)} long {mat_name} wall, "
            f"discretized into {len(tets)} four-node tetrahedral elements "
            f"({3 * len(nodes)} degrees of freedom); the base is fixed, and wind pressure "
            f"and self-weight act together.",
            f"Wind: Beaufort {level} corresponds to a wind speed of about {speed_ms(speed)}, "
            f"with dynamic pressure q=½ρU²={pressure_pa(q)}; using a net shape factor of "
            f"{cp:g}, the mean pressure on the wall face is {pressure_pa(pressure)} and the "
            f"total wind load is {force_n(force)}; the wall self-weight is {force_n(weight)}.",
            f"Deformation: the top of the wall moves about {disp_mm(delta_fe_m * 1000)} "
            f"sideways, against {disp_mm(delta_th_m * 1000)} estimated from cantilever-beam "
            f"theory ({delta_err_pct:.1f}% error); the overall stiffness is adequate for "
            f"service.",
            f"Stress: bending stress at the wall base is about {stress_mpa(bend_mpa)} "
            f"(beam theory {stress_mpa(sigma_th_pa / 1e6)}, {bend_err_pct:.1f}% error), and "
            f"the peak compressive stress is {stress_mpa(max_comp_mpa)}; the bending, "
            f"compression and overturning safety factors are {sf_bend:.2f}, {sf_comp:.2f} "
            f"and {sf_overturn:.2f}, giving an overall verdict of "
            f"\"{_VERDICT_EN[verdict]}\".",
            f"The weakest part is the {weak_part}. {failure}",
        ]
        if math.isfinite(crit_speed):
            narrative.append(
                f"Critical wind level: above about {speed_ms(crit_speed)} (roughly Beaufort "
                f"{crit_level}), the {crit_name} safety factor drops below 1.0."
            )
        narrative.append(
            "This result assumes four-node constant-strain tetrahedra, linear elastic small "
            "deformation and a fully fixed wall base. Masonry strength and mortar bond strength "
            "vary widely, so the result is for everyday reference only; consult a professional "
            "engineer for anything involving personal or structural safety."
        )
        assumptions = [
            "The wall is treated as a homogeneous isotropic linear-elastic material using "
            "representative library values (E, ν, density, strength)",
            "The wall base is fully fixed to the foundation (zero displacement in all "
            "directions); foundation depth, adjacent walls and tie-column restraint are ignored",
            "Wind pressure is uniform over the wall face using net shape factors (windward "
            "+0.8, leeward −0.4, taken as 1.2)",
            "Four-node constant-strain tetrahedral elements are used; nodal stresses are "
            "recovered with superconvergent patch (SPR) recovery, and the base bending stress "
            "is extrapolated linearly through the wall thickness to the face",
            "Mortar joints, moisture content, temperature and long-term creep effects on "
            "material properties are ignored",
        ]
    else:
        narrative = [
            f"建模：把问题简化为 {height:g} m 高、{thickness * 1000:.0f} mm 厚、{length:g} m 长的"
            f"{mat_name}墙，墙体离散为 {len(tets)} 个四节点四面体单元（{3 * len(nodes)} 个自由度），"
            f"墙底固定，风压与自重共同作用。",
            f"风：{beaufort_label(level)}对应风速约 {speed:.1f} m/s，动压 q=½ρU²={q:.0f} Pa；"
            f"取净体型系数 {cp:g}，墙面平均风压 {pressure:.0f} Pa，总风荷载 {force / 1000:.2f} kN；"
            f"墙体自重 {weight / 1000:.2f} kN。",
            f"变形：墙顶最大侧移约 {delta_fe_m * 1000:.3f} mm，按悬臂梁理论估算 "
            f"{delta_th_m * 1000:.3f} mm（误差 {delta_err_pct:.1f}%），整体刚度满足使用要求。",
            f"应力：墙根弯曲应力约 {bend_mpa:.3f} MPa（梁理论 {sigma_th_pa / 1e6:.3f} MPa，"
            f"误差 {bend_err_pct:.1f}%），最大压应力 {max_comp_mpa:.3f} MPa；"
            f"弯曲、抗压、抗倾覆安全系数分别为 {sf_bend:.2f}、{sf_comp:.2f}、{sf_overturn:.2f}，"
            f"综合判定「{verdict}」。",
            f"最薄弱部位是{weak_part}，主要破坏形式为{failure}。",
        ]
        if math.isfinite(crit_speed):
            narrative.append(
                f"临界风级：风速超过约 {crit_speed:.1f} m/s（约 {crit_level} 级风）时"
                f"{crit_name}安全系数将降到 1.0 以下。"
            )
        narrative.append(
            "本结果采用四节点四面体常应变单元、线弹性小变形与固定墙底假定，"
            "砌体强度与砂浆粘结强度离散性大，结果仅供生活参考；涉及人身或结构安全请咨询专业工程师。"
        )

        assumptions = [
            "墙体为均质各向同性线弹性材料，采用材料库代表值（E、ν、容重、强度）",
            "墙底与基础完全固接（三向位移为零），忽略基础埋深、相邻墙体与构造柱约束",
            "风压按净体型系数沿墙面均匀分布（迎风 +0.8、背风 -0.4，取 1.2）",
            "采用四节点四面体常应变单元；节点应力经超收敛片（SPR）恢复，"
            "根部弯曲应力再沿厚度方向线性外推到墙面",
            "忽略砂浆灰缝、含水率、温度与长期徐变对材料参数的影响",
        ]

    # ---- 渲染数据 ----
    vm_nodal = vm_node / 1e6
    render_unit = "MPa"
    if is_en() and is_imperial():
        vm_nodal = vm_nodal * PSI_PER_MPA
        render_unit = "psi"
    render = {
        "type": "solid3d",
        "unit": render_unit,
        "field": "von_mises",
        "value_location": "node",
        "up_axis": "y",
        "nodes": [[round(float(c), 6) for c in node] for node in nodes],
        "tets": [[int(v) for v in tet] for tet in tets_arr],
        "disp": [
            [round(float(u3[i, 0]), 9), round(float(u3[i, 1]), 9), round(float(u3[i, 2]), 9)]
            for i in range(len(nodes))
        ],
        "values": [round(float(v), 4) for v in vm_nodal],
        "bounds": [
            round(float(nodes[:, 0].min()), 6),
            round(float(nodes[:, 1].min()), 6),
            round(float(nodes[:, 2].min()), 6),
            round(float(nodes[:, 0].max()), 6),
            round(float(nodes[:, 1].max()), 6),
            round(float(nodes[:, 2].max()), 6),
        ],
        "mesh_lines": {"nx": nx, "ny": ny, "nz": nz},
    }

    t_done = time.perf_counter()
    return {
        "kind": "solid",
        "ok": True,
        "model_name": str(model.get("name") or t(f"{mat_name}墙三维抗风",
                                                 f"{mat_name} 3D wind-resistant wall")),
        "scenario": "solid_wall",
        "material": mat_name,
        "material_params": {
            "E_MPa": round(E / 1e6, 1),
            "nu": round(nu, 3),
            "density_kg_m3": round(density, 1),
            "strength_MPa": round(strength, 3),
            "tension_limit_MPa": round(tension_limit, 3),
        },
        "geometry": {
            "length_m": round(length, 3),
            "height_m": round(height, 3),
            "thickness_m": round(thickness, 3),
            "area_m2": round(length * height, 2),
            "volume_m3": round(length * height * thickness, 3),
            "weight_kN": round(weight / 1000.0, 2),
        },
        "load": {
            "type": "pressure" if model.get("pressure_Pa") is not None else "wind",
            "wind_speed_m_s": round(speed, 1),
            "beaufort": int(level),
            "beaufort_label": (f"Beaufort {level}" if is_en()
                               else beaufort_label(level)) if level else "—",
            "q_Pa": round(q, 1),
            "cp_net": round(cp, 2),
            "pressure_Pa": round(pressure, 1),
            "force_N": round(force, 0),
        },
        "metrics": {
            "max_disp_mm": round(max_disp_m * 1000.0, 4),
            "top_disp_mm": round(delta_fe_m * 1000.0, 4),
            "max_stress_MPa": round(float(vm_node[valid_node].max()) / 1e6, 4),
            "max_tension_MPa": round(max_tension_mpa, 4),
            "max_compression_MPa": round(max_comp_mpa, 4),
            "bend_MPa": round(bend_mpa, 4),
            "net_tension_MPa": round(net_tension_mpa, 4),
            "safety_factor": round(sf_min, 2),
            "verdict": verdict,
        },
        "checks": checks,
        "theory_check": {
            "deflection_mm": round(delta_th_m * 1000.0, 4),
            "deflection_error_pct": round(delta_err_pct, 1),
            "bend_stress_MPa": round(sigma_th_pa / 1e6, 4),
            "bend_error_pct": round(bend_err_pct, 1),
            "model": t("悬臂条带梁（单位墙宽）", "Cantilever strip beam (unit wall width)"),
        },
        "weak_part": weak_part,
        "failure_modes": [failure],
        "advice": advice,
        "critical_speed_m_s": round(crit_speed, 1) if math.isfinite(crit_speed) else None,
        "critical_beaufort": int(crit_level) if math.isfinite(crit_speed) else None,
        "critical_mode": crit_name if math.isfinite(crit_speed) else None,
        "render": render,
        "mesh": {
            "level": mesh_level if segments is None else "custom",
            "nx": nx,
            "ny": ny,
            "nz": nz,
            "n_elements": int(len(tets)),
            "n_vertices": int(len(nodes)),
            "dof": int(solved["dof"]),
            "solver": solved["solver_method"],
            "stress_recovery": "spr",
        },
        "timing": {
            "total_ms": round((t_done - t_start) * 1000.0, 1),
            "assemble_ms": round(solved["assemble_time_s"] * 1000.0, 1),
            "solve_ms": round(solved["solve_time_s"] * 1000.0, 1),
            "mesh_ms": round((t_solved - t_start - solved["assemble_time_s"] - solved["solve_time_s"]) * 1000.0, 1),
        },
        "reactions": {
            "fx_N": round(float(solved["reaction_force"][0]), 1),
            "fy_N": round(float(solved["reaction_force"][1]), 1),
            "fz_N": round(float(solved["reaction_force"][2]), 1),
        },
        "assumptions": assumptions,
        "narrative": narrative,
    }


# ====================================================================== 通用实体（run_solid_generic）
_AXIS_INDEX = {"x": 0, "y": 1, "z": 2}


def _axis_idx(axis: Any) -> int:
    """将 'x'/'y'/'z'（或 0/1/2）转换为坐标分量索引。"""
    if isinstance(axis, str):
        key = axis.strip().lower()
        if key not in _AXIS_INDEX:
            raise ValueError(f"未知坐标轴「{axis}」，应为 x/y/z")
        return _AXIS_INDEX[key]
    idx = int(axis)
    if idx not in (0, 1, 2):
        raise ValueError(f"坐标轴索引必须为 0/1/2，得到 {idx}")
    return idx


# 各图元的默认竖直轴：box/cylinder 的高度沿 y；水平板的厚度沿 z
_DEFAULT_UP_AXIS = {"box": "y", "cylinder": "y", "plate": "z"}


def _up_axis_vector(up_axis: Any) -> np.ndarray:
    """竖直向上单位向量：接受 'x'/'y'/'z' 或 [x,y,z] 向量。"""
    if isinstance(up_axis, str):
        key = up_axis.strip().lower()
        if key not in _AXIS_INDEX:
            raise ValueError(f"未知竖直轴「{up_axis}」，应为 x/y/z")
        vec = np.zeros(3)
        vec[_AXIS_INDEX[key]] = 1.0
        return vec
    try:
        vec = np.asarray(up_axis, dtype=float)
    except (TypeError, ValueError):
        raise ValueError("竖直轴必须是 'x'/'y'/'z' 或 [x,y,z] 向量")
    if vec.shape != (3,):
        raise ValueError("竖直轴向量必须是 [x,y,z]")
    norm = float(np.linalg.norm(vec))
    if norm < 1e-30:
        raise ValueError("竖直轴向量不能为零")
    return vec / norm


def _default_up_axis(geometry: Dict[str, Any], gtype: str) -> Any:
    """plate 默认 z 向上（水平板），box/cylinder 默认 y 向上。

    用户在 geometry.up_axis 里给出 "x"/"y"/"z" 或 [x,y,z] 可覆盖，
    例如竖直放置的板可写 "up_axis":"y"。
    """
    up = geometry.get("up_axis")
    if up is not None:
        return up
    return _DEFAULT_UP_AXIS.get(str(gtype).lower(), "y")


def _up_axis_name(up_axis: Any) -> str:
    """把竖直轴（字符串/向量）归一为输出用的 "x"/"y"/"z"（取分量最大者）。"""
    vec = _up_axis_vector(up_axis)
    return ("x", "y", "z")[int(np.argmax(np.abs(vec)))]


def _resolve_boundary_face(
    nodes: np.ndarray,
    face_def: Dict[str, Any],
    tag: str,
    *,
    curved_ok: bool = True,
) -> Tuple[int, float]:
    """面定位的单一真相源：语义锚点 at="min"/"max" 优先（数值由代码按网格实际
    边界解析，AI 不必心算），否则取 value；两者都没有/非法/不在外表面 → 确定性
    报错（带实际范围与合法取值，[L-01]）。返回 (axis_idx, value)。

    curved_ok=False（cylinder）时只允许 y 端面：x/z 方向只能截到一条母线。
    与 Kotlin AiParser.resolveFace/requireBoundaryFace 保持同一套规则。
    """
    axis_raw = face_def.get("axis")
    # 兼容旧 JSON 的数字 axis（0/1/2）；字符串 x/y/z 为推荐写法
    try:
        axis = _axis_idx(axis_raw)
    except (TypeError, ValueError):
        raise ValueError(f"{tag} 的 axis 必须是 x/y/z，当前为「{axis_raw}」")
    axis_name = ("x", "y", "z")[axis]
    if not curved_ok and axis != 1:
        raise ValueError(
            'cylinder 只能在 y=0（at:"min"）或 y=高度（at:"max"）的端面上'
            "加平面约束/面压，曲面侧压暂不支持"
        )
    lo, hi = _axis_bounds(nodes, axis)
    at_raw = face_def.get("at")
    at = str(at_raw).strip().lower() if at_raw is not None and str(at_raw).strip() else None
    if at is not None:
        if at not in ("min", "max"):
            raise ValueError(
                f'{tag} 的 at 只能是 "min" 或 "max"（当前为 "{at_raw}"）；'
                "也可省略 at、用 value 给数值坐标（m）"
            )
        value = lo if at == "min" else hi
    elif face_def.get("value") is not None:
        value = float(face_def["value"])
    else:
        raise ValueError(
            f'{tag} 缺少定位：请给 at="min"/"max"（如底面/顶面，推荐，'
            "数值由代码按几何尺寸解析），或给 value=数值坐标（m）"
        )
    if not _is_on_boundary(nodes, axis, value):
        raise ValueError(
            f"[L-01] {tag} 不在实体边界上：你填的是 {axis_name}={value:.4g}，"
            f"但该轴实际范围为 {lo:.4g}~{hi:.4g} m，合法外表面只有 "
            f'{axis_name}={lo:.4g}（用 {{"axis":"{axis_name}","at":"min"}}）或 '
            f'{axis_name}={hi:.4g}（用 {{"axis":"{axis_name}","at":"max"}}）。请二选一改正。'
        )
    return axis, value


def _resolve_point_at(nodes, point_at, tag):
    """点语义锚点解析（单一真相）：point_at=[x,y,z] 三轴各取 "min"/"max"/"center"，
    由代码按网格实际包围盒解析成坐标（如顶面中心 ["center","max","center"]），
    AI 不必心算数值坐标。与 Kotlin AiParser.resolvePointAt 保持同一套规则。
    返回 np.ndarray shape (3,)。"""
    if not isinstance(point_at, (list, tuple)) or len(point_at) != 3:
        raise ValueError(
            f"{tag} 的 point_at 必须是 [x,y,z] 三个元素（每个取 min/max/center）"
        )
    lo = nodes.min(axis=0)
    hi = nodes.max(axis=0)
    coords = []
    for i, ax in enumerate(("x", "y", "z")):
        a = str(point_at[i]).strip().lower()
        if a == "min":
            coords.append(float(lo[i]))
        elif a == "max":
            coords.append(float(hi[i]))
        elif a == "center":
            coords.append(float((lo[i] + hi[i]) / 2.0))
        else:
            raise ValueError(
                f'{tag} 的 point_at[{i}]（{ax} 轴）只能是 "min"/"max"/"center"'
                f'（当前为 "{point_at[i]}"）'
            )
    return np.asarray(coords, dtype=float)


def _face_anchor_points(
    nodes: np.ndarray, axis: int, value: float, *, curved_ok: bool = True
) -> List[Tuple[float, float, float]]:
    """面约束在刚体秩检查中的采样点：box/plate 取面四角；cylinder 的 y 端面
    取圆心 + 四向半径点（四角会落在圆盘外）。与 Kotlin faceAnchorPoints 对齐。"""
    xlo, xhi = _axis_bounds(nodes, 0)
    ylo, yhi = _axis_bounds(nodes, 1)
    zlo, zhi = _axis_bounds(nodes, 2)
    if not curved_ok:
        radius = xhi  # cylinder x 范围为 [-r, r]
        return [
            (0.0, value, 0.0),
            (radius, value, 0.0),
            (-radius, value, 0.0),
            (0.0, value, radius),
            (0.0, value, -radius),
        ]
    if axis == 0:
        return [
            (value, ylo, zlo), (value, yhi, zlo),
            (value, yhi, zhi), (value, ylo, zhi),
        ]
    if axis == 1:
        return [
            (xlo, value, zlo), (xhi, value, zlo),
            (xhi, value, zhi), (xlo, value, zhi),
        ]
    return [
        (xlo, ylo, value), (xhi, ylo, value),
        (xhi, yhi, value), (xlo, yhi, value),
    ]


# L-05 刚体模式中文名（6 个未知量 [ux,uy,uz,ωx,ωy,ωz] 对应）
_RIGID_MODE_NAMES = [
    "x 方向整体平动", "y 方向整体平动", "z 方向整体平动",
    "绕 x 轴整体转动", "绕 y 轴整体转动", "绕 z 轴整体转动",
]


def _rigid_body_missing_modes(
    anchors: List[Tuple[List[Tuple[float, float, float]], List[int]]],
    nodes: np.ndarray,
) -> List[str]:
    """L-05 刚体约束秩预检（解析法，不组装刚度矩阵）。

    每个被约束采样点 P 对分量 d 的约束方程为刚体速度场 v(P)=u+ω×r 在 d 向为 0：
      d=0: [1,0,0,  0, rz,-ry]
      d=1: [0,1,0, -rz, 0, rx]
      d=2: [0,0,1,  ry,-rx, 0]
    坐标先按包围盒最大边长归一化（平动列/转动列量纲一致），堆叠全部行后做
    高斯-约当消元；秩 < 6 时自由列构造零空间基向量，按最大分量命名缺失模式。
    与 Kotlin AiParser.rigidBodyMissingModes 同一算法、同一容差。

    :return: 未消除的刚体运动模式中文名列表；空列表表示约束充分。
    """
    if not anchors:
        return list(_RIGID_MODE_NAMES)
    spans = [_axis_bounds(nodes, a)[1] - _axis_bounds(nodes, a)[0] for a in range(3)]
    scale = max(max(spans), 1e-9)
    rows: List[List[float]] = []
    for points, comps in anchors:
        for px, py, pz in points:
            rx, ry, rz = px / scale, py / scale, pz / scale
            for d in sorted(set(comps)):
                row = [0.0] * 6
                row[d] = 1.0
                if d == 0:
                    row[4], row[5] = rz, -ry
                elif d == 1:
                    row[3], row[5] = -rz, rx
                else:
                    row[3], row[4] = ry, -rx
                rows.append(row)
    if not rows:
        return list(_RIGID_MODE_NAMES)
    m, n = len(rows), 6
    mat = [list(r) for r in rows]
    tol = 1e-9
    rank = 0
    pivot_row_of_col = [-1] * n
    for c in range(n):
        if rank >= m:
            break
        pivot = max(range(rank, m), key=lambda i: abs(mat[i][c]))
        if abs(mat[pivot][c]) <= tol:
            continue
        mat[rank], mat[pivot] = mat[pivot], mat[rank]
        pv = mat[rank][c]
        for j in range(c, n):
            mat[rank][j] /= pv
        for i in range(m):
            if i == rank:
                continue
            factor = mat[i][c]
            if abs(factor) > tol:
                for j in range(c, n):
                    mat[i][j] -= factor * mat[rank][j]
        pivot_row_of_col[c] = rank
        rank += 1
    if rank == n:
        return []
    result: List[str] = []
    for free in range(n):
        if pivot_row_of_col[free] >= 0:
            continue
        x = [0.0] * n
        x[free] = 1.0
        for c in range(n):
            rr = pivot_row_of_col[c]
            if rr >= 0:
                x[c] = -mat[rr][free]
        dominant = max(range(n), key=lambda i: abs(x[i]))
        name = _RIGID_MODE_NAMES[dominant]
        if name not in result:
            result.append(name)
    return result


def _parse_supports(
    nodes: np.ndarray, supports: List[Dict[str, Any]], gtype: str = "box"
) -> Tuple[np.ndarray, List[Tuple[int, float]]]:
    """解析约束定义，返回 (被固定的自由度编号数组, 约束面列表 [(axis,value)])。

    支持的约束类型：
      - plane: {"type":"plane","axis":"y","value":0.0,"dofs":"fixed"|[0,1,2]}
      - point: {"type":"point","xyz":[x,y,z],"dofs":"fixed"|[0,1,2]}
      - outline: {"type":"outline","face":{"axis":"z","value":0},"dofs":"fixed"|[0,1,2]}
        约束该面的外轮廓（板四边固定/沿边框嵌固），用于避免点约束造成的应力奇异。
    dofs 省略时默认 "fixed"（三向全部约束）。
    """
    fixed_list: List[int] = []
    support_planes: List[Tuple[int, float]] = []
    # 每个约束贡献「采样点 + 约束分量」，供 L-05 刚体约束秩预检
    anchors: List[Tuple[List[Tuple[float, float, float]], List[int]]] = []
    curved_ok = str(gtype).strip().lower() != "cylinder"
    if not supports:
        raise ValueError("至少需要一个约束（supports 不能为空）")
    for idx, sp in enumerate(supports):
        if not isinstance(sp, dict):
            raise ValueError("约束必须是对象")
        stype = str(sp.get("type") or "").strip().lower()
        dofs_raw = sp.get("dofs", "fixed")
        if dofs_raw == "fixed" or dofs_raw is True:
            comps = [0, 1, 2]
        elif isinstance(dofs_raw, (list, tuple)):
            comps = [int(c) for c in dofs_raw]
            if any(c not in (0, 1, 2) for c in comps):
                raise ValueError("约束自由度分量必须是 0/1/2")
        else:
            raise ValueError(f"约束 dofs 格式非法：{dofs_raw}")
        if not comps:
            raise ValueError(
                f"第 {idx + 1} 个约束的 dofs 不能为空（0=x,1=y,2=z，全固定写 [0,1,2]）"
            )

        if stype == "plane":
            tag = f"第 {idx + 1} 个约束面"
            axis, value = _resolve_boundary_face(nodes, sp, tag, curved_ok=curved_ok)
            node_ids = nodes_on_plane(nodes, axis, value)
            if node_ids.size == 0:
                raise ValueError(
                    f"约束面 {tag} 上没有节点，请检查几何尺寸"
                )
            support_planes.append((axis, value))
            anchors.append((_face_anchor_points(nodes, axis, value, curved_ok=curved_ok), comps))
        elif stype == "point":
            point_at = sp.get("point_at")
            xyz = sp.get("xyz")
            if point_at is not None:
                pt = _resolve_point_at(nodes, point_at, f"第 {idx + 1} 个约束点")
            elif isinstance(xyz, (list, tuple)) and len(xyz) == 3:
                pt = np.asarray(xyz, dtype=float)
            else:
                raise ValueError(
                    f'第 {idx + 1} 个 point 约束需提供 point_at'
                    f'（如 ["center","max","center"]，推荐）或 xyz=[x,y,z]'
                )
            if not _point_in_bbox(nodes, pt):
                lo = nodes.min(axis=0)
                hi = nodes.max(axis=0)
                raise ValueError(
                    f"[L-01] 第 {idx + 1} 个约束点 {pt.tolist()} 不在实体范围内 "
                    f"（x∈[{lo[0]:.4g},{hi[0]:.4g}], y∈[{lo[1]:.4g},{hi[1]:.4g}], "
                    f"z∈[{lo[2]:.4g},{hi[2]:.4g}]），请检查坐标"
                )
            dist = np.linalg.norm(nodes - pt, axis=1)
            node_ids = np.array([int(np.argmin(dist))])
            anchors.append(([(float(pt[0]), float(pt[1]), float(pt[2]))], comps))
        elif stype == "outline":
            face_def = sp.get("face") or {}
            tag = f"第 {idx + 1} 个约束轮廓面"
            axis, value = _resolve_boundary_face(
                nodes, face_def, tag, curved_ok=curved_ok
            )
            if nodes_on_plane(nodes, axis, value).size == 0:
                raise ValueError(
                    f"约束面 {tag} 上没有节点，请检查几何尺寸"
                )
            # 轮廓 = 该面四周的整条边（含厚度方向的所有节点），
            # 等效于板边被边框嵌固，避免只在单层节点上点约束造成的应力奇异
            other = [a for a in (0, 1, 2) if a != axis]
            lo = nodes[:, other].min(axis=0)
            hi = nodes[:, other].max(axis=0)
            diag = float(np.linalg.norm(hi - lo))
            tol = max(1e-9, 1e-6 * diag)
            on_edge = np.any(
                np.isclose(nodes[:, other], lo, atol=tol, rtol=0.0)
                | np.isclose(nodes[:, other], hi, atol=tol, rtol=0.0),
                axis=1,
            )
            node_ids = np.where(on_edge)[0]
            if node_ids.size == 0:
                raise ValueError("outline 约束没有命中任何轮廓节点，请检查约束面")
            support_planes.append((axis, value))
            # 秩检查用面四角（轮廓含四角，约束只会更强）
            anchors.append((_face_anchor_points(nodes, axis, value, curved_ok=curved_ok), comps))
        else:
            raise ValueError(f"未知约束类型「{stype}」，支持 plane/point/outline")

        fixed_list.extend(
            (node_ids[:, None] * 3 + np.array(comps)[None, :]).ravel().tolist()
        )

    # L-05：求解前用解析法检查 6 个刚体运动模式（3 平动 + 3 转动）是否全部被约束
    missing_modes = _rigid_body_missing_modes(anchors, nodes)
    if missing_modes:
        raise ValueError(
            "[L-05] 约束不足：当前约束没有消除全部刚体运动模式，模型会发生"
            + "、".join(missing_modes)
            + f"（共 {len(missing_modes)} 个未约束模式），求解会发散或得到错误结果。"
            "请增加或扩大约束（例如把单面简支改为固定端、在其他方向补支座），"
            "保证 3 个方向平动和 3 个方向转动都被限制后再输出。"
        )

    if not fixed_list:
        raise ValueError("约束自由度为空")
    return np.unique(fixed_list), support_planes


def _resolve_pressure_magnitude(ld: Dict[str, Any]) -> Tuple[float, str]:
    """由确定性源字段计算压力 magnitude（Pa），返回 (magnitude, 换算说明)。

    优先级：风速/风级 > 质量/面积 > 流体深度 > 直接 magnitude。
    原始输入不被改写，换算说明用于在预览/结果中向用户展示换算过程。
    """
    speed = ld.get("wind_speed_m_s")
    beaufort = ld.get("beaufort")
    cp_net = float(ld.get("cp_net") or CP_NET)
    if speed is not None:
        speed = float(speed)
        if not (0.5 <= speed <= 80.0):
            raise ValueError(f"风速 {speed:g} m/s 超出范围（0.5~80 m/s）")
        mag = wind_pressure_pa(speed, cp_net)
        return mag, f"风速 {speed:g} m/s → 风压 {mag:g} Pa"
    if beaufort is not None:
        lv = int(beaufort)
        mag = beaufort_wind_pressure_pa(lv, cp_net)
        speed = speed_from_beaufort(lv)
        return mag, f"{lv} 级风（{speed:g} m/s）→ 风压 {mag:g} Pa"
    mass = ld.get("mass_kg")
    if mass is not None:
        area = ld.get("area_m2")
        if area is None or float(area) <= 0.0:
            raise ValueError("mass_kg 换算为面压时必须提供正的 area_m2（受力面积，m²）")
        mag = mass_force_n(float(mass)) / float(area)
        return mag, f"质量 {mass:g} kg（重量 {mass_force_n(float(mass)):g} N）/ 面积 {float(area):g} m² → 压强 {mag:g} Pa"
    depth = ld.get("depth_m")
    fluid = ld.get("fluid")
    if depth is not None:
        mag = hydrostatic_pressure_pa(float(depth), str(fluid or "water"))
        fluid_name = "湿土" if str(fluid or "").strip().lower() in ("soil", "earth", "土") else "水"
        return mag, f"{fluid_name}深 {float(depth):g} m → 压强 {mag:g} Pa"
    # 直接给 magnitude（单位 Pa）
    mag = float(ld.get("magnitude", 0.0))
    if mag <= 0.0:
        raise ValueError("pressure 载荷的 magnitude（Pa）必须为正，或提供 wind_speed_m_s/beaufort/mass_kg/depth_m")
    return mag, f"压强 {mag:g} Pa"


def _parse_loads(
    nodes: np.ndarray,
    faces: np.ndarray,
    loads: List[Dict[str, Any]],
    density: float,
    vertical_axis: Any = "y",
    support_planes: Optional[List[Tuple[int, float]]] = None,
    gtype: str = "box",
) -> Tuple[List[Dict[str, Any]], Optional[Tuple[float, float, float]]]:
    """解析载荷定义，返回 (face_loads, body_accel)。

    支持的载荷类型：
      - pressure: {"type":"pressure","face":{"axis":"z","value":0.0},
                   "magnitude":500.0}
        压力沿面内法线方向（指向实体内部）；magnitude 单位 Pa。
        可选 "hydrostatic": true 表示水压/土压随深度线性增大：
        此时 magnitude 为面底部（竖直最低处）的压强，顶部为零，梯度沿竖直轴。
      - gravity: {"type":"gravity"} 或 {"type":"gravity","direction":[0,-1,0]}
        省略 direction 时沿竖直向下（= -vertical_axis）；
        vertical_axis 由几何类型决定（box/cylinder 默认 +y，plate 默认 +z），
        也可用 geometry.up_axis 覆盖。大小为 density*GRAVITY。
      - force: {"type":"force","point":[x,y,z],"vector":[fx,fy,fz]}
        集中力施加于最近节点（单位 N）。
    """
    face_loads: List[Dict[str, Any]] = []
    body_accel: Optional[Tuple[float, float, float]] = None

    if not loads:
        raise ValueError("至少需要一个载荷（loads 不能为空）")

    for idx, ld in enumerate(loads):
        if not isinstance(ld, dict):
            raise ValueError("载荷必须是对象")
        ltype = str(ld.get("type") or "").strip().lower()

        if ltype == "pressure":
            face_def = ld.get("face") or {}
            # 面定位单一真相源：at="min"/"max" 优先，否则 value；非法/非外表面确定性报错
            axis, value = _resolve_boundary_face(
                nodes, face_def, f"第 {idx + 1} 个压力加载面",
                curved_ok=str(gtype).strip().lower() != "cylinder",
            )
            # 确定性换算：源字段优先于 magnitude（避免 LLM 心算量纲错误 L-03）。
            magnitude, src_desc = _resolve_pressure_magnitude(ld)
            for saxis, svalue in (support_planes or []):
                if saxis == axis and abs(value - svalue) <= 1e-6:
                    raise ValueError(
                        f"加载面 {face_def.get('axis')}={value} 与约束面重合，"
                        f"压力应作用在自由表面上，不能与约束面共用同一表面"
                    )
            sel_faces = faces_on_plane(nodes, faces, axis, value)
            if sel_faces.size == 0:
                raise ValueError(
                    f"加载面 {face_def.get('axis')}={value} 上没有表面单元"
                    f"（圆柱等曲面体只能在 y=0/y=高度 等坐标平面上加面压，"
                    f"曲面侧压暂不支持）"
                )
            # 压力方向：pressure_dir="in"（默认，兼容旧行为）指向实体内部，
            # "out" 指向外部（吸力/负压）。方向由面的外法线单一真相源决定，
            # 不再在多处重复推断坐标语义。
            coord_min = float(nodes[:, axis].min())
            coord_max = float(nodes[:, axis].max())
            # 外法线：面在坐标最小处为 -轴方向，最大处为 +轴方向
            outward = -1.0 if abs(value - coord_min) < abs(value - coord_max) else 1.0
            pdir = str(ld.get("pressure_dir") or "in").strip().lower()
            if pdir not in ("in", "out"):
                raise ValueError(
                    f"pressure_dir 只能是 'in' 或 'out'（当前为 {pdir!r}）"
                )
            sign = -1.0 if pdir == "in" else 1.0  # in = -outward, out = +outward
            normal = np.zeros(3)
            normal[axis] = outward * sign
            vec = normal * magnitude
            dir_label = "向内（压）" if pdir == "in" else "向外（吸）"
            src_desc = f"{src_desc}，{dir_label}"

            def make_traction(v):
                def traction(_p):
                    return v
                return traction

            # 水压/土压随深度线性增大（顶部为 0，底部为 magnitude）。
            # 由 depth_m/fluid 源字段换算时自动按静水线性分布处理。
            hydro = (
                bool(ld.get("hydrostatic"))
                or ld.get("depth_m") is not None
                or str(ld.get("profile") or "").strip().lower()
                in ("hydrostatic", "water", "soil")
            )
            vax = _axis_idx(vertical_axis)
            vlo = vspan = 0.0
            if hydro:
                sel_nodes = np.unique(sel_faces)
                if vax == axis:
                    hydro = False
                else:
                    vcoords = nodes[sel_nodes, vax]
                    vlo = float(vcoords.min())
                    vhi = float(vcoords.max())
                    vspan = vhi - vlo
                    hydro = vspan > 1e-9

            if hydro:
                def make_hydro(v, va, hi, span):
                    def traction(p):
                        # 深度从顶面往下算：竖直最低处（底部）压强最大
                        t = (hi - float(p[va])) / span
                        t = 0.0 if t < 0.0 else (1.0 if t > 1.0 else t)
                        return v * t
                    return traction
                tfn = make_hydro(vec, vax, vhi, vspan)
            else:
                tfn = make_traction(vec)

            face_loads.append({
                "faces": sel_faces,
                "traction_fn": tfn,
                "_source_desc": src_desc,
                "_magnitude_pa": magnitude,
            })

        elif ltype == "gravity":
            direction = ld.get("direction")
            if direction is None:
                d = -_up_axis_vector(vertical_axis)
            else:
                d = np.asarray(direction, dtype=float)
                if d.shape != (3,):
                    raise ValueError("gravity 方向必须是 [x,y,z] 向量")
            norm = np.linalg.norm(d)
            if norm < 1e-30:
                raise ValueError("gravity 方向向量不能为零")
            d = d / norm
            accel = d * density * GRAVITY
            body_accel = (float(accel[0]), float(accel[1]), float(accel[2]))

        elif ltype == "force":
            point_at = ld.get("point_at")
            xyz = ld.get("point")
            vector = ld.get("vector")
            mass = ld.get("mass_kg")
            direction = ld.get("direction")
            if point_at is not None:
                pt = _resolve_point_at(nodes, point_at, f"第 {idx + 1} 个 force 作用点")
            elif isinstance(xyz, (list, tuple)) and len(xyz) == 3:
                pt = np.asarray(xyz, dtype=float)
            else:
                raise ValueError(
                    f'第 {idx + 1} 个 force 载荷需提供 point_at'
                    f'（如 ["center","max","center"]，推荐）或 point=[x,y,z]'
                )
            # 质量→重力：需 direction（单位向量）指明受力方向；否则用 vector（N）。
            src_desc = "集中力"
            if mass is not None:
                if not isinstance(direction, (list, tuple)) or len(direction) != 3:
                    raise ValueError("mass_kg 换算为集中力时必须提供 direction 单位向量 [x,y,z]")
                d = np.asarray(direction, dtype=float)
                dn = float(np.linalg.norm(d))
                if dn < 1e-30:
                    raise ValueError("force 的 direction 不能为零向量")
                f_n = mass_force_n(float(mass))
                vector = (d / dn * f_n).tolist()
                src_desc = f"质量 {float(mass):g} kg → 力 {f_n:g} N，方向 {direction}"
            if not isinstance(vector, (list, tuple)) or len(vector) != 3:
                raise ValueError("force 载荷的 vector 必须是 [fx,fy,fz]（N）")
            if not _point_in_bbox(nodes, pt):
                lo = nodes.min(axis=0)
                hi = nodes.max(axis=0)
                raise ValueError(
                    f"force 载荷作用点 {pt.tolist()} 不在实体范围内 "
                    f"（x∈[{lo[0]:.4g},{hi[0]:.4g}], y∈[{lo[1]:.4g},{hi[1]:.4g}], "
                    f"z∈[{lo[2]:.4g},{hi[2]:.4g}]），请检查坐标"
                )
            dist = np.linalg.norm(nodes - pt, axis=1)
            nid = int(np.argmin(dist))
            f_vec = np.asarray(vector, dtype=float)
            # 用 face_loads 机制不太合适；直接在求解前加到 f 向量
            # 这里通过一个特殊标记传递：traction_fn 只作用于该节点
            # 简化处理：把集中力当作作用在退化面上（单节点三角形不存在），
            # 改为在 run_solid_generic 中直接处理集中力节点。
            face_loads.append({
                "faces": np.array([[nid, nid, nid]], dtype=int),
                "traction_fn": _concentrated_traction(nid, f_vec, len(nodes)),
                "_concentrated": True,
                "_node": nid,
                "_vector": f_vec,
                "_source_desc": src_desc,
            })

        else:
            raise ValueError(f"未知载荷类型「{ltype}」，支持 pressure/gravity/force")

    return face_loads, body_accel


def _concentrated_traction(nid: int, f_vec: np.ndarray, n_nodes: int):
    """集中力：通过退化三角形把力分配到单个节点。

    faces 传 [[nid,nid,nid]]，apply_face_traction 会把三个中点积分都加在 nid 上，
    恰好得到 f_vec（三个 1/2 权重 × 2 = 1.0 倍）。
    """
    cache = {"applied": False}

    def traction(p):
        # p 是退化三角形的中点（= nid 坐标），只在第一次返回力，避免重复
        if not cache["applied"]:
            cache["applied"] = True
            return f_vec
        return np.zeros(3)

    return traction


def _build_generic_mesh(
    geometry: Dict[str, Any], mesh_level: str, split: str = "main"
) -> Tuple[np.ndarray, np.ndarray, str, Dict[str, int]]:
    """按 geometry.type 生成网格，返回 (nodes, tets, geom_type, segments)。

    split="main"/"alt" 决定六面体的四面体剖分方向（节点与单元索引保持一致）。
    """
    gtype = str(geometry.get("type") or "box").strip().lower()
    if gtype not in GENERIC_MESH_LEVELS:
        raise ValueError(
            f"[G-02] 未知几何类型「{gtype}」，支持：{'、'.join(sorted(GENERIC_MESH_LEVELS))}"
        )
    level = str(mesh_level or "medium").lower()
    if level not in GENERIC_MESH_LEVELS[gtype]:
        raise ValueError(
            f"[G-02] 未知网格档位「{level}」，可选："
            f"{'、'.join(GENERIC_MESH_LEVELS[gtype])}"
        )

    if gtype == "box":
        length = float(geometry["length_m"])
        height = float(geometry["height_m"])
        thickness = float(geometry["thickness_m"])
        cfg = GENERIC_MESH_LEVELS["box"][level]
        nx, ny, nz = cfg["nx"], cfg["ny"], cfg["nz"]
        holes = _validate_holes(geometry.get("holes"), (length, height, thickness))
        if holes:
            nodes, tets = build_box_with_holes_mesh(
                nx, ny, nz, length, height, thickness, holes, split=split
            )
        else:
            nodes, tets = build_box_mesh(nx, ny, nz, length, height, thickness, split=split)
        return nodes, tets, gtype, {"nx": nx, "ny": ny, "nz": nz}

    if gtype == "plate":
        length = float(geometry["length_m"])
        width = float(geometry["width_m"])
        thickness = float(geometry["thickness_m"])
        cfg = GENERIC_MESH_LEVELS["plate"][level]
        nx, ny, nz = cfg["nx"], cfg["ny"], cfg["nz"]
        # 板：x=长，y=宽，z=厚
        holes = _validate_holes(geometry.get("holes"), (length, width, thickness))
        if holes:
            nodes, tets = build_box_with_holes_mesh(
                nx, ny, nz, length, width, thickness, holes, split=split
            )
        else:
            nodes, tets = build_box_mesh(nx, ny, nz, length, width, thickness, split=split)
        return nodes, tets, gtype, {"nx": nx, "ny": ny, "nz": nz}

    if gtype == "cylinder":
        if geometry.get("holes"):
            raise ValueError(
                "[G-03] 圆柱（cylinder）暂不支持开孔；如需挖孔请改用 box 或 plate 图元"
            )
        radius = float(geometry["radius_m"])
        height = float(geometry["height_m"])
        cfg = GENERIC_MESH_LEVELS["cylinder"][level]
        n_grid, ny = cfg["n_grid"], cfg["ny"]
        nodes, tets = build_cylinder_mesh(radius, height, n_grid, ny, split=split)
        return nodes, tets, gtype, {"n_grid": n_grid, "ny": ny}

    raise ValueError(f"几何类型 {gtype} 未实现")  # 防御性


def run_solid_generic(
    model: Dict[str, Any], library: Optional[MaterialsLibrary] = None
) -> Dict[str, Any]:
    """通用三维实体 TET4 静力求解（支持 box/cylinder/plate 等图元）。

    与 run_solid（墙体专用）的区别：
      - 几何由 geometry.type 决定，不再限定为墙体；
      - 约束由 supports 列表定义（平面/点），不再限定为墙底固接；
      - 载荷由 loads 列表定义（压力/自重/集中力），不再限定为迎风风压；
      - 后处理只保留通用量（von Mises、主应力、位移、支反力、应力比安全系数），
        不再做墙根弯曲外推/抗倾覆/临界风速等墙体专属校核。

    模型字段：
        kind: "solid_generic"
        geometry: {"type":"box|cylinder|plate", ...尺寸...}
        material: 材料名
        E_MPa / nu / density_kg_m3: 可选覆盖
        supports: [约束列表]
        loads: [载荷列表]
        mesh_level: coarse|medium|fine|ultra
    """
    if not isinstance(model, dict):
        raise ValueError("实体模型必须是 JSON 对象")

    kind = str(model.get("kind") or "solid_generic").strip().lower()
    if kind not in ("solid_generic", "generic_solid", "solid"):
        raise ValueError(
            f"run_solid_generic 期望 kind=solid_generic，得到「{kind}」"
        )

    geometry = model.get("geometry") or {}
    if not isinstance(geometry, dict):
        raise ValueError("geometry 必须是对象（含 type 与尺寸）")

    # ---- 材料 ----
    name = str(model.get("material") or "").strip()
    if not name:
        raise ValueError("缺少材料：请指定材料名（如 混凝土C20、钢、砖砌体）")
    lib = library if library is not None else load_library()
    rec = lib.find_material(name)
    if rec is None:
        avail = "、".join(r["name"] for r in lib.all_materials())
        raise ValueError(f"[M-01] 材料库中没有「{name}」，可选：{avail}。请使用材料库中的名称，或在材料页面自定义材料后再求解")
    mat_name = str(rec.get("name") or name)

    def _num(*keys: str) -> Optional[float]:
        for key in keys:
            if key in model and model[key] is not None:
                try:
                    return float(model[key])
                except (TypeError, ValueError):
                    raise ValueError(f"参数 {key} 必须是数字")
        return None

    e_override = _num("E_MPa")
    E = e_override * 1e6 if e_override is not None else float(rec.get("elastic_modulus") or 0.0)
    nu_override = _num("nu")
    nu = nu_override if nu_override is not None else float(rec.get("poisson_ratio") or 0.3)
    density_override = _num("density_kg_m3")
    density = (density_override if density_override is not None
               else float(rec.get("density") or 1800.0))
    strength = float(rec.get("strength") or 1.0)
    tension_limit = float(_tension_limit(mat_name, strength))
    if E <= 0.0:
        raise ValueError("材料弹性模量必须为正")
    if density <= 0.0:
        raise ValueError("材料密度必须为正")

    # ---- 网格 ----
    mesh_level = str(model.get("mesh_level") or "medium")
    nodes, tets, gtype, segments = _build_generic_mesh(geometry, mesh_level)
    faces = boundary_faces(tets)

    # ---- 约束 ----
    supports = model.get("supports") or []
    fixed, support_planes = _parse_supports(nodes, supports, gtype=gtype)

    # ---- 载荷 ----
    loads = model.get("loads") or []
    up_axis = _default_up_axis(geometry, gtype)

    def _split_loads(mesh_nodes: np.ndarray, mesh_faces: np.ndarray):
        """解析载荷并把集中力从面载荷里拆出来（退化三角形不能按面积积分）。"""
        raw, accel = _parse_loads(
            mesh_nodes, mesh_faces, loads, density, vertical_axis=up_axis,
            support_planes=support_planes, gtype=gtype,
        )
        real_fl: List[Dict[str, Any]] = []
        conc: List[Tuple[int, np.ndarray]] = []
        sources: List[str] = []
        for item in raw:
            sources.append(str(item.get("_source_desc") or ""))
            if item.get("_concentrated"):
                conc.append((item["_node"], item["_vector"]))
            else:
                real_fl.append(item)
        return real_fl, conc, accel, sources

    t_start = time.perf_counter()
    # ---- 双剖分求解 ----
    # 单一方向的六面体剖分（绕同一主对角线）会让常应变四面体产生沿该对角线的
    # 偏置：高应力呈“对角分布”而非按荷载分布。镜像剖分的网格节点编号、单元
    # 索引与主剖分完全一致，因此两种剖分各解一次后按节点取平均即可消除偏置。
    try:
        nodes_alt, tets_alt = _build_generic_mesh(
            geometry, mesh_level, split="alt"
        )[:2]
        dual = bool(np.allclose(nodes_alt, nodes) and len(tets_alt) == len(tets))
    except Exception:  # 极端几何下无法生成镜像网格时退回单剖分
        dual = False

    face_loads, concentrated, body_accel, load_sources = _split_loads(nodes, faces)
    solved = solve_solid(
        nodes, tets, E=float(E), nu=float(nu), fixed=fixed,
        face_loads=face_loads, body_accel=body_accel,
        point_forces=concentrated,
    )
    solved_alt = None
    if dual:
        faces_alt = boundary_faces(tets_alt)
        fl_alt, conc_alt, ba_alt, _ = _split_loads(nodes_alt, faces_alt)
        solved_alt = solve_solid(
            nodes, tets_alt, E=float(E), nu=float(nu), fixed=fixed,
            face_loads=fl_alt, body_accel=ba_alt, point_forces=conc_alt,
        )
    t_done = time.perf_counter()

    u = solved["u"]
    sig = solved["sigma"]
    reaction_force = solved["reaction_force"]
    applied_force = np.asarray(solved["applied_force"], dtype=float)
    if solved_alt is not None:
        u = 0.5 * (u + solved_alt["u"])
        reaction_force = 0.5 * (reaction_force + solved_alt["reaction_force"])
        applied_force = 0.5 * (applied_force + solved_alt["applied_force"])

    # ---- 解的有效性校验（P0-2 兜底） ----
    # 1) 数值有效性：欠约束/奇异矩阵时 spsolve 返回 nan/inf
    if not np.all(np.isfinite(u)):
        raise ValueError(
            "求解结果无效（位移中出现 nan/inf），通常是约束不足导致结构整体飘移，"
            "请检查约束是否足以消除刚体位移"
        )
    # 2) 刚体位移兜底：最大位移超过实体尺寸的一半，几乎必然是约束不足
    diag = float(np.linalg.norm(nodes.max(axis=0) - nodes.min(axis=0)))
    max_disp_preview = float(np.linalg.norm(u.reshape(-1, 3), axis=1).max())
    if diag > 0.0 and max_disp_preview > 0.5 * diag:
        raise ValueError(
            f"最大位移 {max_disp_preview:.4g} m 远超实体尺寸（对角线 {diag:.4g} m），"
            "结构可能约束不足或为机构，请检查支座设置"
        )
    # 3) 平衡校核：支反力与外载应大小相等方向相反
    applied_norm = float(np.linalg.norm(applied_force))
    residual = reaction_force + applied_force
    residual_norm = float(np.linalg.norm(residual))
    equilibrium_ratio = (
        residual_norm / applied_norm if applied_norm > 1e-30 else residual_norm
    )
    if equilibrium_ratio > 0.05:
        raise ValueError(
            f"静力不平衡：外载合力 {applied_force.tolist()} N，"
            f"支反力合力 {reaction_force.tolist()} N，"
            f"相对残差 {equilibrium_ratio:.2%}（>5%），请检查约束与载荷设置"
        )

    # ---- 通用后处理 ----
    u3 = u.reshape(-1, 3)
    disp_mag = np.linalg.norm(u3, axis=1)
    max_disp_m = float(disp_mag.max())
    max_disp_node = int(np.argmax(disp_mag))

    # 节点应力：用“不接触约束节点”的单元做体积加权平均。
    # 约束边框处的单元应力是应力奇异解（越加密越大），既不收敛也不代表真实强度；
    # 只用内层单元既能保证均匀受力工况的读数准确，也避免云图在边框/角点画出
    # 假的“对角高应力”。边界节点用相邻内层节点的值补齐，保证云图连续。
    def _nodal_stress(
        mesh_sig: np.ndarray, mesh_tets: np.ndarray
    ) -> np.ndarray:
        fixed_mask = np.zeros(len(nodes), dtype=bool)
        fixed_mask[np.unique(fixed // 3)] = True
        inner = ~np.any(fixed_mask[mesh_tets], axis=1)
        if not np.any(inner):
            inner = np.ones(len(mesh_tets), dtype=bool)

        p_tet = nodes[mesh_tets[inner]]
        vol = np.abs(np.linalg.det(
            np.stack([p_tet[:, 1] - p_tet[:, 0],
                      p_tet[:, 2] - p_tet[:, 0],
                      p_tet[:, 3] - p_tet[:, 0]], axis=2)
        )) / 6.0
        sig_inner = mesh_sig[inner]
        tets_inner = mesh_tets[inner]
        acc = np.zeros((len(nodes), 6))
        cnt = np.zeros(len(nodes))
        for _k in range(4):
            np.add.at(acc, tets_inner[:, _k], sig_inner * vol[:, None])
            np.add.at(cnt, tets_inner[:, _k], vol)
        node = acc / np.maximum(cnt, 1e-30)[:, None]

        # 边界（接触约束）节点：两轮邻域平均，把内层值向边框平滑过渡
        valid = cnt > 0.0
        if np.any(~valid) and np.any(valid):
            edges = np.vstack([
                mesh_tets[:, [0, 1]], mesh_tets[:, [0, 2]], mesh_tets[:, [0, 3]],
                mesh_tets[:, [1, 2]], mesh_tets[:, [1, 3]], mesh_tets[:, [2, 3]],
            ])
            edges = np.vstack([edges, edges[:, ::-1]])
            for _ in range(2):
                e = edges[valid[edges[:, 1]]]
                if e.size == 0:
                    break
                c = np.zeros(len(nodes))
                np.add.at(c, e[:, 0], 1.0)
                has = c > 0
                for j in range(6):
                    a2 = np.zeros(len(nodes))
                    np.add.at(a2, e[:, 0], node[e[:, 1], j])
                    new = np.where(has, a2 / np.maximum(c, 1.0), node[:, j])
                    node[~valid, j] = new[~valid]
        return node

    sig_node = _nodal_stress(sig, tets)
    if solved_alt is not None:
        sig_node = 0.5 * (sig_node + _nodal_stress(solved_alt["sigma"], tets_alt))
    pr_node = principal_stresses(sig_node)
    vm_node = von_mises(sig_node)

    # 薄板：沿厚度用实体四面体离散时，受载面与背面的应力会因单元锁死/局部承压
    # 出现明显差异（并非真实的板弯曲应力）。云图按 (x,y) 位置沿厚度方向的最大值
    # 着色（等价于“板面最大纤维应力”），既不依赖观察哪一侧，也避免厚度方向伪影。
    thin_plate = False
    if gtype == "plate":
        t_thk = float(geometry["thickness_m"])
        span_min = min(float(geometry["length_m"]), float(geometry["width_m"]))
        thin_plate = t_thk <= 0.05 * span_min
    if thin_plate:
        nx_, ny_, nz_ = int(segments["nx"]), int(segments["ny"]), int(segments["nz"])
        nxp, nyp = nx_ + 1, ny_ + 1
        # 该按厚度取最大的着色依赖完整结构化网格的节点编号顺序；
        # 挖孔后节点被重映射且数量减少，不再是满网格，跳过此步改用原始节点应力着色
        if len(nodes) == nxp * nyp * (nz_ + 1):
            vm_surface = vm_node.copy()
            for j in range(nyp):
                for i in range(nxp):
                    idx = i + nxp * j + nxp * nyp * np.arange(nz_ + 1)
                    vm_surface[idx] = vm_node[idx].max()
            vm_node = vm_surface

    max_tension_pa = float(pr_node[:, 2].max())
    max_compression_pa = float(-pr_node[:, 0].min())
    max_vm_pa = float(vm_node.max())

    # 应力奇点区域（约束面、集中力作用点附近）的峰值会随网格加密不断增大、不收敛，
    # 若用它评估"优化是否有效"，会出现"优化后应力反而变大"的假象。
    # 这里额外给出排除奇点影响区后的最大 von Mises，供 AI 优化前后对比使用。
    singular_refs: List[np.ndarray] = []
    # fixed 存的是自由度编号（node*3+dof），需先还原为节点编号再取坐标
    fixed_nodes = np.unique(np.asarray(fixed, dtype=int) // 3) if len(fixed) else np.empty(0, dtype=int)
    fixed_nodes = fixed_nodes[fixed_nodes < len(nodes)]
    if fixed_nodes.size:
        singular_refs.append(nodes[fixed_nodes])
    for _fl in face_loads:
        if _fl.get("_concentrated") and "_node" in _fl:
            _nid = int(_fl["_node"])
            if 0 <= _nid < len(nodes):
                singular_refs.append(nodes[[_nid]])
    max_vm_excl_singular_pa = max_vm_pa
    if singular_refs and len(tets):
        ref_pts = np.vstack(singular_refs)
        char_len = float(np.median(np.linalg.norm(
            nodes[tets[:, 1]] - nodes[tets[:, 0]], axis=1)))
        radius = max(2.0 * char_len, 1e-9)
        dmin, _ = cKDTree(ref_pts).query(nodes, k=1)
        keep = dmin > radius
        if np.any(keep):
            max_vm_excl_singular_pa = float(vm_node[keep].max())

    # 安全系数：最大拉应力 vs 抗拉限值，最大压应力 vs 抗压强度，取较小者
    sf_tension = tension_limit * 1e6 / max_tension_pa if max_tension_pa > 1e-6 else 999.0
    sf_comp = strength * 1e6 / max_compression_pa if max_compression_pa > 1e-6 else 999.0
    sf_min = min(sf_tension, sf_comp)
    if sf_min >= SAFETY_SAFE:
        verdict = "安全"
    elif sf_min >= SAFETY_CAUTION:
        verdict = "注意"
    else:
        verdict = "危险"

    # 几何信息
    up_axis_name = _up_axis_name(up_axis)
    if gtype == "cylinder":
        radius = float(geometry["radius_m"])
        height = float(geometry["height_m"])
        volume = math.pi * radius ** 2 * height
        geom_info = {
            "type": "cylinder",
            "radius_m": round(radius, 4),
            "height_m": round(height, 4),
            "up_axis": up_axis_name,
            "volume_m3": round(volume, 4),
            "weight_kN": round(density * GRAVITY * volume / 1000.0, 2),
        }
    else:
        if gtype == "plate":
            l, w, t = float(geometry["length_m"]), float(geometry["width_m"]), float(geometry["thickness_m"])
        else:
            l, w, t = float(geometry["length_m"]), float(geometry["height_m"]), float(geometry["thickness_m"])
        volume = l * w * t
        geom_info = {
            "type": gtype,
            "length_m": round(l, 4),
            "width_m" if gtype == "plate" else "height_m": round(w, 4),
            "thickness_m": round(t, 4),
            "up_axis": up_axis_name,
            "volume_m3": round(volume, 4),
            "weight_kN": round(density * GRAVITY * volume / 1000.0, 2),
        }

    vm_nodal = vm_node / 1e6
    render_unit = "MPa"
    if is_en() and is_imperial():
        vm_nodal = vm_nodal * PSI_PER_MPA
        render_unit = "psi"
    render = {
        "type": "solid3d",
        "unit": render_unit,
        "field": "von_mises",
        "value_location": "node",
        "up_axis": up_axis_name,
        "nodes": [[round(float(c), 6) for c in node] for node in nodes],
        "tets": [[int(v) for v in tet] for tet in tets],
        "disp": [
            [round(float(u3[i, 0]), 9), round(float(u3[i, 1]), 9), round(float(u3[i, 2]), 9)]
            for i in range(len(nodes))
        ],
        "values": [round(float(v), 4) for v in vm_nodal],
        "bounds": [
            round(float(nodes[:, 0].min()), 6),
            round(float(nodes[:, 1].min()), 6),
            round(float(nodes[:, 2].min()), 6),
            round(float(nodes[:, 0].max()), 6),
            round(float(nodes[:, 1].max()), 6),
            round(float(nodes[:, 2].max()), 6),
        ],
    }

    if is_en():
        assumptions = [
            "Isotropic linear-elastic material with representative library values "
            "(E, ν, density, strength)",
            "Restraint faces and loaded faces are defined by the user-specified coordinate "
            "planes or points",
            "Pressure acts along the loaded-face normal into the solid; hydrostatic pressure "
            "grows linearly along the vertical axis (zero at the top, maximum at the bottom)",
            "Four-node constant-strain tetrahedral elements are used: the displacement field "
            "is linear and element stress is constant; nodal stresses are volume-weighted "
            "averages of adjacent elements (no extrapolation at restrained edges, avoiding "
            "spurious stress concentration)",
            "Each hexahedron is solved twice, split along the main diagonal and its mirror, "
            "and the results are averaged to remove the diagonal stress bias of a single "
            "split direction",
            "Safety factors are ratios of the maximum tensile / compressive stress to material "
            "strength; stability and fatigue are not considered",
        ]
        if thin_plate:
            assumptions.append(
                "Thin plates (thickness ≤ 1/20 of the smallest in-plane dimension) are still "
                "meshed with solid tetrahedra, so bending stiffness is overestimated while "
                "displacement and stress are underestimated; switch the mesh level to "
                "fine/ultra for more accurate readings"
            )
            assumptions.append(
                "The thin-plate stress contour is colored by the maximum fibre stress across "
                "the thickness; the through-thickness stress gradient is not shown"
            )
    else:
        assumptions = [
            "各向同性线弹性材料，采用材料库代表值（E、ν、容重、强度）",
            "约束面与加载面由用户指定的坐标平面/点确定",
            "压力沿加载面法线指向实体内部；静水压力沿竖直轴线性增大（顶部为 0、底部最大）",
            "采用四节点四面体常应变单元；位移场线性、单元应力为常量，"
            "节点应力由相邻单元按体积加权平均（约束边框处不做外推，避免假应力集中）",
            "六面体按主对角线及其镜像各解一次后取平均，消除单一剖分方向造成的对角应力偏置",
            "安全系数基于最大拉/压应力与材料强度的比值，未考虑稳定与疲劳",
        ]
        if thin_plate:
            assumptions.append(
                "薄板（板厚 ≤ 面内最小尺寸的 1/20）仍按实体四面体离散，"
                "弯曲刚度偏大、位移与应力偏小；需要更准的读数请把网格档位调到 fine/ultra"
            )
            assumptions.append(
                "薄板应力云图按板面最大纤维应力（厚度方向取最大值）着色，"
                "不显示厚度方向的应力梯度"
            )

    checks = [
        {
            "name": "最大拉应力",
            "value": round(max_tension_pa / 1e6, 4),
            "limit": round(tension_limit, 4),
            "unit": "MPa",
            "safety_factor": round(sf_tension, 2),
            "pass": sf_tension >= SAFETY_CAUTION,
        },
        {
            "name": "最大压应力",
            "value": round(max_compression_pa / 1e6, 4),
            "limit": round(strength, 4),
            "unit": "MPa",
            "safety_factor": round(sf_comp, 2),
            "pass": sf_comp >= SAFETY_CAUTION,
        },
    ]
    if is_en():
        checks[0]["name"] = "Max tensile stress"
        checks[1]["name"] = "Max compressive stress"
        if is_imperial():
            for _c in checks:
                _c["value"] = round(_c["value"] * PSI_PER_MPA, 1)
                _c["limit"] = round(_c["limit"] * PSI_PER_MPA, 1)
                _c["unit"] = "psi"

    return {
        "kind": "solid_generic",
        "ok": True,
        "model_name": str(model.get("name") or (f"{mat_name} 3D solid" if is_en()
                                                else f"{mat_name}三维实体")),
        "scenario": "solid_generic",
        "material": mat_name,
        "material_params": {
            "E_MPa": round(E / 1e6, 1),
            "nu": round(nu, 3),
            "density_kg_m3": round(density, 1),
            "strength_MPa": round(strength, 3),
            "tension_limit_MPa": round(tension_limit, 3),
        },
        "geometry": geom_info,
        "load_sources": load_sources,
        "metrics": {
            "max_disp_mm": round(max_disp_m * 1000.0, 4),
            "max_disp_node": max_disp_node,
            "max_von_mises_MPa": round(max_vm_pa / 1e6, 4),
            "max_von_mises_excl_singular_MPa": round(max_vm_excl_singular_pa / 1e6, 4),
            "max_tension_MPa": round(max_tension_pa / 1e6, 4),
            "max_compression_MPa": round(max_compression_pa / 1e6, 4),
            "safety_factor": round(sf_min, 2),
            "sf_tension": round(sf_tension, 2),
            "sf_compression": round(sf_comp, 2),
            "verdict": verdict,
        },
        "checks": checks,
        "reactions": {
            "fx_N": round(float(reaction_force[0]), 1),
            "fy_N": round(float(reaction_force[1]), 1),
            "fz_N": round(float(reaction_force[2]), 1),
        },
        "mesh": {
            "level": mesh_level,
            "type": gtype,
            "segments": segments,
            "n_elements": int(len(tets)),
            "n_vertices": int(len(nodes)),
            "dof": int(solved["dof"]),
            "solver": (
                (f"{solved['solver_method']}×2 (dual split)" if is_en()
                 else f"{solved['solver_method']}×2(双剖分)")
                if solved_alt is not None else solved["solver_method"]
            ),
            "stress_recovery": "volume-average",
        },
        "timing": {
            "total_ms": round((t_done - t_start) * 1000.0, 1),
            "assemble_ms": round(solved["assemble_time_s"] * 1000.0, 1),
            "solve_ms": round(solved["solve_time_s"] * 1000.0, 1),
        },
        "render": render,
        "assumptions": assumptions,
    }
