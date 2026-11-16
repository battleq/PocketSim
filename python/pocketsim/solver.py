# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""2D 线弹性有限元求解器（基于 scikit-fem）。

核心约定
--------
- 单位制：SI（长度 m、应力 Pa、力 N、体力 N/m³）；
- 单元：ElementVector(ElementTriP2) 二阶三角形（6 节点）向量单元，弯曲主导问题
  精度高，可避开剪切自锁；网格本身仍是 P1 顶点三角形（渲染/读数用）；
- 平面应力/平面应变：平面应力通过修正拉梅常数 λ* = E·ν/(1-ν²) 实现；
- 平面外厚度 thickness：整体刚度矩阵与体力项按 thickness 缩放（2D 弱形式默认
  单位厚度）。载荷（集中力/边界线载荷）一律按“物理总力（N / N每米边长）”
  给定，直接进入右端项，无需再乘厚度——这与“力按 P/b 化为单位厚度平面问题”
  的做法完全等价（挠度不变、应力一致）；
- 求解：DOF ≤ 5 万用 scipy.sparse spsolve 直接法（对称友好的 MMD_AT_PLUS_A
  排序）；DOF > 5 万用 CG 迭代 + Jacobi 对角预条件，容差 1e-8；
- 应力恢复：每个单元在重心处计算应变/应力与 von Mises（P2 重心应变具有
  超收敛性），再对顶点做节点平均；最大 von Mises 取“非固支边界节点”上的
  最大值，避开固支角点约束奇异对读数的污染；
- 性能记录：耗时（组装/求解）与 tracemalloc 峰值内存。

注意：tracemalloc 只统计 Python/numpy 层分配，稀疏 LU 内部 C 层内存不在其内，
端上内存预算评估请结合 benchmark 的 RSS 一并参考。
"""
from __future__ import annotations

import time
import tracemalloc
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Sequence, Tuple

import numpy as np
import scipy.sparse as sp
import scipy.sparse.linalg as spla
from skfem import Basis, ElementTriP2, ElementVector, LinearForm, asm, condense
from skfem.helpers import dot
from skfem.models.elasticity import linear_elasticity

# 标准重力加速度（kg→N 换算）
GRAVITY_M_S2 = 9.8
# DOF 阈值：超过则改用 CG + Jacobi 迭代法
CG_DOF_THRESHOLD = 50000
# CG 相对残差容差
CG_TOLERANCE = 1e-8
# 坐标匹配容差（米）：远小于网格尺寸，远大于 linspace 浮点误差
XY_TOL = 1e-9


@dataclass
class FieldResult:
    """线弹性求解结果（顶点量供渲染与读数，单元量供统计）。

    位移单位 m，应力单位 Pa；nodes/elements 为 P1 顶点三角形（渲染拓扑）。
    """

    nodes: np.ndarray                 # (n, 2) 顶点坐标（未变形），m
    elements: np.ndarray              # (m, 3) 三角形顶点索引（P1 拓扑）
    ux: np.ndarray                    # (n,) 顶点 x 位移，m
    uy: np.ndarray                    # (n,) 顶点 y 位移，m
    von_mises: np.ndarray             # (n,) 节点平均 von Mises 应力，Pa
    element_von_mises: np.ndarray     # (m,) 单元重心 von Mises 应力，Pa
    max_displacement: float           # 全域最大位移幅值，m
    max_von_mises: float              # 非固支边界节点最大 von Mises，Pa
    global_max_von_mises: float       # 含固支边界节点的全域最大 von Mises，Pa
    reactions: Dict[str, float]       # 支反力合力 {"fx": N, "fy": N, "magnitude": N}
    dof: int                          # 总自由度数（含约束 DOF）
    n_elements: int
    n_vertices: int
    assemble_time_s: float            # 网格 + 组装 + 载荷构建耗时
    solve_time_s: float               # 线性方程组求解耗时
    total_time_s: float               # 含应力恢复的总耗时
    peak_memory_mb: float             # tracemalloc 峰值内存（MB）
    solver_method: str                # "direct(spsolve)" 或 "cg+jacobi"
    fixed_vertices: np.ndarray = field(default_factory=lambda: np.empty(0, int))

    # ---------------------------------------------------------------- 渲染输出
    def nodal_field_for_render(self) -> Dict:
        """输出供 Android 渲染的精简结构（全部为原生类型，可 JSON 序列化）。

        坐标单位 m（Android 端按屏幕尺寸换算）；ux/uy 为顶点位移（m），
        von_mises 为节点平均应力（Pa）。
        """
        x = self.nodes[:, 0]
        y = self.nodes[:, 1]
        return {
            "nodes": [[round(float(a), 6), round(float(b), 6)] for a, b in zip(x, y)],
            "elements": [[int(i) for i in tri] for tri in self.elements],
            "ux": [float(v) for v in self.ux],
            "uy": [float(v) for v in self.uy],
            "von_mises": [round(float(v), 1) for v in self.von_mises],
            "x_range": [round(float(x.min()), 6), round(float(x.max()), 6)],
            "y_range": [round(float(y.min()), 6), round(float(y.max()), 6)],
        }

    # ---------------------------------------------------------------- 便捷读数
    def uy_at_x_section(self, x_target: float, tol: float = 1e-6) -> float:
        """x ≈ x_target 截面上所有顶点 uy 的平均值（梁式构件的截面平均挠度）。

        截面平均可消除自由端截面刚性转动（±t/2·θ 项）对读数的影响，
        与梁理论的中性轴挠度定义一致。
        """
        mask = np.abs(self.nodes[:, 0] - x_target) <= tol
        if not np.any(mask):
            # 未命中时退化为取最近一列顶点
            col = float(self.nodes[int(np.argmin(np.abs(self.nodes[:, 0] - x_target))), 0])
            mask = np.abs(self.nodes[:, 0] - col) <= tol
        return float(np.mean(self.uy[mask]))


# ---------------------------------------------------------------------- 求解配置
@dataclass
class SolveConfig:
    """场景模板产出的完整求解配置（engine 据此生成网格并调用求解器）。

    几何为 x ∈ [0, x_len]、y ∈ [y0, y0+y_len] 的矩形域（SI 单位：米）。
    """

    nx: int                                # x 方向网格分段数
    ny: int                                # y 方向网格分段数
    x_len: float                           # 域长度（跨度方向），m
    y_len: float                           # 域高度（厚度方向），m
    y0: float                              # 域底部 y 坐标（常取 -y_len/2 使中性轴过原点），m
    thickness: float                       # 平面外厚度（宽度方向），m
    E: float                               # 弹性模量，Pa
    nu: float                              # 泊松比
    body_force: Optional[Tuple[float, float]] = None      # 体力 (bx, by) N/m³
    dirichlet_boundary_fn: Optional[Callable] = None       # (basis, mesh) → 约束 DOF
    load_region_fn: Optional[Callable] = None              # (mesh) → 集中力顶点索引
    load_vector_N: Optional[Tuple[float, float]] = None    # 集中力合力 (fx, fy) N
    plane_stress: bool = True
    extra_edge_loads: Optional[List[Dict]] = None          # 边界均布线载荷列表


def run_solve_config(config: SolveConfig):
    """按 SolveConfig 生成网格并求解，返回 FieldResult。"""
    from .meshgen import make_rect_mesh

    mesh = make_rect_mesh(config.nx, config.ny, config.x_len, config.y_len, 0.0, config.y0)
    return solve_linear_elasticity(
        mesh,
        thickness=config.thickness,
        E=config.E,
        nu=config.nu,
        body_force=config.body_force,
        dirichlet_boundary_fn=config.dirichlet_boundary_fn,
        load_region_fn=config.load_region_fn,
        load_vector_N=config.load_vector_N,
        plane_stress=config.plane_stress,
        extra_edge_loads=config.extra_edge_loads,
    )


# ---------------------------------------------------------------------- 边界/几何查找
def nodes_at_x(mesh, x_value: float, tol: float = XY_TOL) -> np.ndarray:
    """位于 x ≈ x_value 竖直截面上的顶点索引。"""
    return np.where(np.abs(mesh.p[0, :] - x_value) <= tol)[0]


def nodes_at_y(mesh, y_value: float, tol: float = XY_TOL) -> np.ndarray:
    """位于 y ≈ y_value 水平线上的顶点索引。"""
    return np.where(np.abs(mesh.p[1, :] - y_value) <= tol)[0]


def facets_at_x(mesh, x_value: float, tol: float = XY_TOL) -> np.ndarray:
    """两端点均位于 x ≈ x_value 竖直线上的边（facet）索引。"""
    fac = mesh.facets
    mask = (np.abs(mesh.p[0, fac[0]] - x_value) <= tol) & (
        np.abs(mesh.p[0, fac[1]] - x_value) <= tol
    )
    return np.where(mask)[0]


def facets_at_y(mesh, y_value: float, tol: float = XY_TOL) -> np.ndarray:
    """两端点均位于 y ≈ y_value 水平线上的边（facet）索引。"""
    fac = mesh.facets
    mask = (np.abs(mesh.p[1, fac[0]] - y_value) <= tol) & (
        np.abs(mesh.p[1, fac[1]] - y_value) <= tol
    )
    return np.where(mask)[0]


def fixed_dofs_clamped_x(basis: Basis, mesh, x_value: float, tol: float = XY_TOL) -> np.ndarray:
    """固支边界：x ≈ x_value 截面上全部自由度（含 P2 边中点 DOF，x、y 两向）。"""
    verts = nodes_at_x(mesh, x_value, tol)
    facets = facets_at_x(mesh, x_value, tol)
    if verts.size == 0:
        raise ValueError(f"固支截面 x={x_value} 上没有节点")
    dofs = [basis.nodal_dofs[:, verts].ravel()]
    if facets.size:
        dofs.append(basis.facet_dofs[:, facets].ravel())
    return np.unique(np.concatenate(dofs))


def fixed_dofs_point_supports(
    basis: Basis, mesh, supports: Sequence[Tuple[float, float, str]]
) -> np.ndarray:
    """点支座：在给定点附近最近顶点上约束指定分量。

    supports 为 (x, y, comps) 列表，comps 为 "x"、"y" 或 "xy"。
    例如简支梁：[(0, -t/2, "xy"), (L, -t/2, "y")]——左支座防水平漂移，
    右支座仅约束竖向，两端均可绕支点转动。
    """
    collected: List[np.ndarray] = []
    for x_value, y_value, comps in supports:
        dist = np.hypot(mesh.p[0, :] - x_value, mesh.p[1, :] - y_value)
        vid = int(np.argmin(dist))
        comps = comps.lower()
        if "x" in comps:
            collected.append(basis.nodal_dofs[0, vid: vid + 1])
        if "y" in comps:
            collected.append(basis.nodal_dofs[1, vid: vid + 1])
    return np.unique(np.concatenate(collected))


# ---------------------------------------------------------------------- 载荷辅助
def apply_point_load(
    f: np.ndarray,
    basis: Basis,
    node_ids: Sequence[int],
    fx: float,
    fy: float,
) -> None:
    """节点集中力：把总力 (fx, fy) N 均匀分配到给定顶点（就地累加进 f）。

    多个节点均分与“集中力施加于截面”的静力等效做法一致；
    单节点时即为真正的单点集中力（会有局部应力集中，注意读数位置）。
    """
    ids = np.asarray(node_ids, dtype=int)
    if ids.size == 0:
        raise ValueError("集中力载荷区域上没有节点")
    weight = 1.0 / ids.size
    f[basis.nodal_dofs[0, ids]] += float(fx) * weight
    f[basis.nodal_dofs[1, ids]] += float(fy) * weight


def apply_distributed_load(
    f: np.ndarray,
    basis: Basis,
    mesh,
    facet_ids: Sequence[int],
    fx_per_m: float,
    fy_per_m: float,
) -> None:
    """边界均布线载荷（一致/静力等效节点力，就地累加进 f）。

    force_n_per_m 为沿边长的合力密度（N/m，已含平面外厚度——即物理上
    沿跨度方向每米的总力）。P2 边上二次形函数的一致节点力：
    两端节点各 h/6、中间节点 2h/3（h 为边长），单元合力恰为密度×边长。
    """
    fids = np.asarray(facet_ids, dtype=int)
    if fids.size == 0:
        raise ValueError("均布载荷边上没有单元边（facet）")
    fac = mesh.facets
    p = mesh.p
    dx = p[0, fac[0, fids]] - p[0, fac[1, fids]]
    dy = p[1, fac[0, fids]] - p[1, fac[1, fids]]
    h = np.hypot(dx, dy)
    for comp, value in ((0, fx_per_m), (1, fy_per_m)):
        if value == 0.0:
            continue
        f[basis.nodal_dofs[comp, fac[0, fids]]] += value * h / 6.0
        f[basis.nodal_dofs[comp, fac[1, fids]]] += value * h / 6.0
        f[basis.facet_dofs[comp, fids]] += 2.0 * value * h / 3.0


def _body_force_vector(basis: Basis, body_force: Sequence[float]) -> np.ndarray:
    """体积力（N/m³，每单位体积）的单位厚度一致节点力向量。"""
    bf = np.asarray(body_force, dtype=float)

    @LinearForm
    def body_form(v, w):  # noqa: ANN001  (skfem 表单签名由框架约定)
        return dot(bf, v)

    return asm(body_form, basis)


# ---------------------------------------------------------------------- 本构
def _lame_parameters(E: float, nu: float, plane_stress: bool) -> Tuple[float, float]:
    """拉梅常数 (λ, μ)。平面应力用修正 λ* = E·ν/(1-ν²)（skfem 弱形式默认为
    平面应变型本构，代入 λ* 即得平面应力）。"""
    mu = E / (2.0 * (1.0 + nu))
    if plane_stress:
        lam = E * nu / (1.0 - nu ** 2)
    else:
        lam = E * nu / ((1.0 + nu) * (1.0 - 2.0 * nu))
    return lam, mu


def _stress_matrix(E: float, nu: float, plane_stress: bool) -> np.ndarray:
    """3×3 弹性矩阵 D：[σxx, σyy, τxy]^T = D·[εxx, εyy, γxy]^T。"""
    if plane_stress:
        return E / (1.0 - nu ** 2) * np.array(
            [[1.0, nu, 0.0], [nu, 1.0, 0.0], [0.0, 0.0, (1.0 - nu) / 2.0]]
        )
    return E / ((1.0 + nu) * (1.0 - 2.0 * nu)) * np.array(
        [[1.0 - nu, nu, 0.0], [nu, 1.0 - nu, 0.0], [0.0, 0.0, (1.0 - 2.0 * nu) / 2.0]]
    )


# ---------------------------------------------------------------------- 应力恢复
# 参考单元 3 个顶点处 6 个 P2 形函数的梯度（行序：v0, v1, v2, m01, m12, m02）。
# P2 位移场的应变为线性，在顶点处直接求值即得该单元离散场的顶点应变
#（比形心采样更贴近真实极值纤维应力），再做节点平均即为平滑应力场。
_REF_GRADS_AT_VERTICES = np.array(
    [
        [  # 顶点 v0 = (0,0)
            [-3.0, -3.0],
            [-1.0, 0.0],
            [0.0, -1.0],
            [4.0, 0.0],
            [0.0, 0.0],
            [0.0, 4.0],
        ],
        [  # 顶点 v1 = (1,0)
            [1.0, 1.0],
            [3.0, 0.0],
            [0.0, -1.0],
            [-4.0, -4.0],
            [0.0, 4.0],
            [0.0, 0.0],
        ],
        [  # 顶点 v2 = (0,1)
            [1.0, 1.0],
            [-1.0, 0.0],
            [0.0, 3.0],
            [0.0, 0.0],
            [4.0, 0.0],
            [-4.0, -4.0],
        ],
    ]
)


def _element_von_mises(
    mesh, basis: Basis, u: np.ndarray, E: float, nu: float, plane_stress: bool
) -> Tuple[np.ndarray, np.ndarray]:
    """单元应力恢复：每个单元在其 3 个顶点处求 von Mises，再对顶点做节点平均。

    返回 (element_von_mises (m,) 单元内 3 顶点均值, nodal_von_mises (n,) 节点平均)，
    单位 Pa。顶点处求值保证弯曲问题的极值纤维应力不被形心平滑低估。
    """
    t = mesh.t                       # (3, nelem) 顶点索引
    p = mesh.p
    nvert = mesh.nvertices

    # 顶点对 → facet（边）索引映射（结构化网格上 O(n log n) 查找）
    fac = mesh.facets
    fa = np.minimum(fac[0], fac[1])
    fb = np.maximum(fac[0], fac[1])
    key = fa * nvert + fb
    order = np.argsort(key)
    sorted_key = key[order]

    def pair_facet(vi: np.ndarray, vj: np.ndarray) -> np.ndarray:
        lo = np.minimum(vi, vj)
        hi = np.maximum(vi, vj)
        k = lo * nvert + hi
        idx = np.searchsorted(sorted_key, k)
        idx = np.clip(idx, 0, sorted_key.size - 1)
        return order[idx]

    f01 = pair_facet(t[0], t[1])
    f12 = pair_facet(t[1], t[2])
    f02 = pair_facet(t[0], t[2])

    # 6 个局部节点（v0,v1,v2,m01,m12,m02）的 x/y 方向自由度 → 位移 (6, nelem)
    ndx = np.vstack(
        [
            basis.nodal_dofs[0, t[0]],
            basis.nodal_dofs[0, t[1]],
            basis.nodal_dofs[0, t[2]],
            basis.facet_dofs[0, f01],
            basis.facet_dofs[0, f12],
            basis.facet_dofs[0, f02],
        ]
    )
    ndy = np.vstack(
        [
            basis.nodal_dofs[1, t[0]],
            basis.nodal_dofs[1, t[1]],
            basis.nodal_dofs[1, t[2]],
            basis.facet_dofs[1, f01],
            basis.facet_dofs[1, f12],
            basis.facet_dofs[1, f02],
        ]
    )
    ux6 = u[ndx]
    uy6 = u[ndy]

    # 仿射映射 Jacobi 逆（数学上对顺/逆时针单元均成立）：
    # ∂ξ/∂x=(y2-y0)/detJ, ∂η/∂x=-(y1-y0)/detJ,
    # ∂ξ/∂y=-(x2-x0)/detJ, ∂η/∂y=(x1-x0)/detJ
    x0, x1, x2 = p[0, t[0]], p[0, t[1]], p[0, t[2]]
    y0, y1, y2 = p[1, t[0]], p[1, t[1]], p[1, t[2]]
    det_j = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
    a11 = (y2 - y0) / det_j
    a12 = -(y1 - y0) / det_j
    a21 = -(x2 - x0) / det_j
    a22 = (x1 - x0) / det_j

    dmat = _stress_matrix(E, nu, plane_stress)
    szz_factor = 0.0 if plane_stress else nu

    nelem = mesh.nelements
    vm_at_vertices = np.empty((3, nelem))
    for k, grad in enumerate(_REF_GRADS_AT_VERTICES):
        dndx = grad[:, 0:1] * a11 + grad[:, 1:2] * a12   # (6, nelem)
        dndy = grad[:, 0:1] * a21 + grad[:, 1:2] * a22
        eps_xx = (dndx * ux6).sum(axis=0)
        eps_yy = (dndy * uy6).sum(axis=0)
        gam_xy = (dndx * uy6).sum(axis=0) + (dndy * ux6).sum(axis=0)
        sxx = dmat[0, 0] * eps_xx + dmat[0, 1] * eps_yy
        syy = dmat[1, 0] * eps_xx + dmat[1, 1] * eps_yy
        txy = dmat[2, 2] * gam_xy
        szz = szz_factor * (sxx + syy)
        vm_at_vertices[k] = np.sqrt(
            0.5 * ((sxx - syy) ** 2 + (syy - szz) ** 2 + (szz - sxx) ** 2)
            + 3.0 * txy ** 2
        )

    element_vm = vm_at_vertices.mean(axis=0)

    # 顶点处单元值 → 节点平均（局部节点 k 的值累加到顶点 t[k]）
    nodal = np.zeros(nvert)
    count = np.zeros(nvert)
    for k in range(3):
        np.add.at(nodal, t[k], vm_at_vertices[k])
        np.add.at(count, t[k], 1.0)
    nodal /= np.maximum(count, 1.0)
    return element_vm, nodal


# ---------------------------------------------------------------------- 主求解
def solve_linear_elasticity(
    mesh,
    thickness: float,
    E: float,
    nu: float,
    body_force: Optional[Sequence[float]] = None,
    dirichlet_boundary_fn: Optional[Callable[[Basis, object], np.ndarray]] = None,
    load_region_fn: Optional[Callable[[object], np.ndarray]] = None,
    load_vector_N: Optional[Sequence[float]] = None,
    plane_stress: bool = True,
    extra_edge_loads: Optional[List[Dict]] = None,
) -> FieldResult:
    """2D 线弹性静力求解（平面应力/平面应变）。

    参数
    ----
    mesh : skfem MeshTri          坐标单位 m 的三角网格
    thickness : float             平面外厚度（m），刚度与体力按其缩放；
                                  平面应变问题请传 1.0
    E, nu : float                 弹性模量（Pa）与泊松比
    body_force : (bx, by)         体积力（N/m³，每单位体积），如 (0, -ρ·g)
    dirichlet_boundary_fn : fn    (basis, mesh) → 约束 DOF 数组（必填）
    load_region_fn : fn           (mesh) → 施加集中力的顶点索引数组
    load_vector_N : (fx, fy)      集中力合力（N，物理总力，向下传负 fy）
    plane_stress : bool           True 平面应力 / False 平面应变
    extra_edge_loads : list       附加边界均布线载荷
                                 [{"facets_fn": (mesh)→边索引,
                                   "force_n_per_m": (fx, fy)}]

    返回 FieldResult（位移 m、应力 Pa、支反力 N、耗时与峰值内存）。
    """
    if dirichlet_boundary_fn is None:
        raise ValueError("必须提供 dirichlet_boundary_fn，否则系统奇异")
    if thickness <= 0.0:
        raise ValueError(f"平面外厚度必须为正：{thickness}")

    tracemalloc.start()
    t_start = time.perf_counter()

    # ---- 组装（P2 向量单元） ----
    basis = Basis(mesh, ElementVector(ElementTriP2()))
    lam, mu = _lame_parameters(float(E), float(nu), plane_stress)
    stiffness = asm(linear_elasticity(lam, mu), basis).tocsr()
    if thickness != 1.0:
        stiffness = (thickness * stiffness).tocsr()

    dirichlet = np.unique(np.asarray(dirichlet_boundary_fn(basis, mesh), dtype=int))
    if dirichlet.size == 0:
        raise ValueError("约束自由度为空：至少需要消除刚体位移的边界条件")

    f = np.zeros(basis.N)
    loaded_vertices: List[int] = []  # 集中力直接作用的顶点（最大应力读数时排除）
    if load_region_fn is not None and load_vector_N is not None:
        node_ids = np.asarray(load_region_fn(mesh), dtype=int)
        apply_point_load(f, basis, node_ids, float(load_vector_N[0]), float(load_vector_N[1]))
        loaded_vertices.extend(int(v) for v in node_ids)
    if body_force is not None:
        # 体力按每单位体积给定：右端项需与刚度一样乘平面外厚度
        f = f + thickness * _body_force_vector(basis, body_force)
    for item in extra_edge_loads or []:
        facet_ids = item["facets_fn"](mesh)
        force = item["force_n_per_m"]
        apply_distributed_load(f, basis, mesh, facet_ids, float(force[0]), float(force[1]))

    t_assembled = time.perf_counter()

    # ---- 求解（消去约束 DOF 后：直接法 / CG+Jacobi） ----
    reduced, rhs, _, free = condense(stiffness, f, D=dirichlet)
    if basis.N > CG_DOF_THRESHOLD:
        method = "cg+jacobi"
        jacobi = sp.diags(1.0 / reduced.diagonal())
        # scipy ≥1.12 用 rtol，≤1.11（含 Android 端 chaquo wheel 1.8.1）用 tol
        try:
            x_free, info = spla.cg(reduced, rhs, rtol=CG_TOLERANCE, M=jacobi)
        except TypeError:
            x_free, info = spla.cg(reduced, rhs, tol=CG_TOLERANCE, M=jacobi)
        if info != 0:
            raise RuntimeError(f"CG 迭代未收敛（info={info}），请尝试加密网格或细化模型")
    else:
        method = "direct(spsolve)"
        x_free = spla.spsolve(reduced.tocsc(), rhs, permc_spec="MMD_AT_PLUS_A")
    u = np.zeros(basis.N)
    u[free] = x_free

    t_solved = time.perf_counter()

    # ---- 支反力（约束 DOF 上的不平衡力） ----
    residual = stiffness @ u - f
    comp = np.empty(basis.N, dtype=int)
    comp[basis.nodal_dofs[0]] = 0
    comp[basis.nodal_dofs[1]] = 1
    comp[basis.facet_dofs[0]] = 0
    comp[basis.facet_dofs[1]] = 1
    react_fx = float(residual[dirichlet][comp[dirichlet] == 0].sum())
    react_fy = float(residual[dirichlet][comp[dirichlet] == 1].sum())

    # ---- 应力恢复与读数 ----
    element_vm, nodal_vm = _element_von_mises(mesh, basis, u, float(E), float(nu), plane_stress)
    ux = u[basis.nodal_dofs[0]]
    uy = u[basis.nodal_dofs[1]]
    disp_mag = np.hypot(ux, uy)

    # 读数排除集：固支边界顶点 + 集中力直接作用顶点（均为应力奇异位置，
    # 排除后得到的最大 von Mises 才是构件真实受力水平的可靠估计）
    fixed_dof_mask = np.zeros(basis.N, dtype=bool)
    fixed_dof_mask[dirichlet] = True
    fixed_verts_mask = fixed_dof_mask[basis.nodal_dofs[0]] | fixed_dof_mask[basis.nodal_dofs[1]]
    if loaded_vertices:
        fixed_verts_mask = fixed_verts_mask.copy()
        fixed_verts_mask[np.asarray(loaded_vertices, dtype=int)] = True
    fixed_vertices = np.where(fixed_verts_mask)[0]
    interior_vm = nodal_vm[~fixed_verts_mask]
    max_vm = float(interior_vm.max()) if interior_vm.size else float(nodal_vm.max())

    t_done = time.perf_counter()
    peak_mb = tracemalloc.get_traced_memory()[1] / (1024.0 * 1024.0)
    tracemalloc.stop()

    return FieldResult(
        nodes=mesh.p.T.copy(),
        elements=mesh.t.T.copy().astype(int),
        ux=ux.copy(),
        uy=uy.copy(),
        von_mises=nodal_vm,
        element_von_mises=element_vm,
        max_displacement=float(disp_mag.max()),
        max_von_mises=max_vm,
        global_max_von_mises=float(nodal_vm.max()),
        reactions={
            "fx": react_fx,
            "fy": react_fy,
            "magnitude": float(np.hypot(react_fx, react_fy)),
        },
        dof=int(basis.N),
        n_elements=int(mesh.nelements),
        n_vertices=int(mesh.nvertices),
        assemble_time_s=t_assembled - t_start,
        solve_time_s=t_solved - t_assembled,
        total_time_s=t_done - t_start,
        peak_memory_mb=peak_mb,
        solver_method=method,
        fixed_vertices=fixed_vertices,
    )
