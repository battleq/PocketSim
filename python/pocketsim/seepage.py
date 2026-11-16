# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""二维稳态渗流有限元（P4 流体阶段，大坝算例）。

场景：水库蓄水后，水从上游通过坝基向下游渗流，回答"大坝会不会被水推走/渗漏/管涌"。

做法
----
1. 坝体按混凝土重力坝处理（坝体自身不透水），渗流发生在坝基：矩形坝基区域
   x∈[-2H, B+2H]、y∈[-D, 0]（B 为坝底宽度、D 为坝基深度）；
2. 控制方程：达西定律 + 不可压稳态 → 水头 H 满足 ∇·(k∇H)=0（即 Laplace）；
3. 边界：上游侧地表（y=0、x≤0）H=上游水位、下游侧地表（y=0、x≥B）H=下游水位
   （Dirichlet）；坝基底面与左右远场 ∂H/∂n=0（不透水/远场，自然边界）；
   坝底接触面不加边界条件，解出的水头即扬压力来源；
4. P1 三角单元组装，稀疏直接法求解；单元梯度 → 节点平均得渗流速度 v=-k∇H；
5. 结果：坝底扬压力 U（水头→压力 p=ρgH 沿坝底积分）、单宽渗流量 Q
   （坝底中部竖直截面上的流量）、坝趾出逸比降 i（管涌校核）；
6. 稳定校核：抗滑 SF=(W−U)tanφ/P（P 为上下游净水压力），抗倾覆按绕坝趾力矩，
   管涌按允许出逸比降；并由校核式反解临界上游水位。

单位：长度 m、水头 m、渗透系数 m/s、压力/力 kN 或 kN/m、流量 m²/s（单宽）。
返回 JSON 的所有数值均为原生类型。
"""
from __future__ import annotations

import math
import time
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import scipy.sparse.linalg as spla
from skfem import Basis, ElementTriP1, MeshTri, asm, condense
from skfem.models.poisson import laplace

from .i18n import (
    FT_PER_M,
    LBF_PER_N,
    is_en,
    is_imperial,
    length_m,
    num,
)
from .materials import MaterialsLibrary, load_library

# 水的密度（kg/m³）与重力加速度（m/s²）
WATER_DENSITY = 1000.0
GRAVITY = 9.8

# 安全判定阈值（与框架/流体结果一致）
SAFETY_SAFE = 2.0
SAFETY_CAUTION = 1.2

# 砂性土地基允许出逸比降（防止管涌，工程经验值 0.4~0.5）
I_EXIT_ALLOW = 0.5

MESH_FACTORS = {"coarse": 0.65, "medium": 1.0, "fine": 1.5}


# ====================================================================== 网格
def _build_foundation_mesh(base_width: float, depth: float,
                           lup: float, ldown: float, level: str = "medium") -> MeshTri:
    """坝基结构化三角网格：在上游侧、坝底、下游侧三段分别加密。

    网格线严格包含 x=0（坝踵）、x=B（坝趾）与 y=0（坝基地表），保证 Dirichlet
    边界与扬压力积分都落在网格线上；坝底中部还保证有一条网格线用于流量截面。
    """
    factor = MESH_FACTORS.get(level, 1.0)
    n_up = max(6, int(round(14 * factor)))
    n_mid = max(6, int(round(20 * factor)))
    if n_mid % 2 == 1:
        n_mid += 1                       # 保证 B/2 处有网格线（渗流量截面）
    n_dn = max(6, int(round(14 * factor)))
    ny = max(6, int(round(18 * factor)))

    # 上游段：-lup → 0，靠近坝踵（x=0）加密
    t = np.linspace(0.0, 1.0, n_up + 1)
    x_up = (-lup * (1.0 - t) ** 1.6)[:-1]
    # 下游段：B → B+ldown，靠近坝趾（x=B）加密
    t = np.linspace(0.0, 1.0, n_dn + 1)
    x_dn = (base_width + ldown * t ** 1.6)[1:]
    x_mid = np.linspace(0.0, base_width, n_mid + 1)
    xs = np.concatenate([x_up, x_mid, x_dn])

    # 竖向：-depth → 0，靠近地表（y=0）加密（扬压力与出逸比降都在地表）
    t = np.linspace(0.0, 1.0, ny + 1)
    ys = -depth * (1.0 - t) ** 1.6

    nx = xs.size
    ny_ = ys.size
    idx = np.arange(nx * ny_).reshape(ny_, nx)
    pts = np.array([[xs[i], ys[j]] for j in range(ny_) for i in range(nx)]).T

    tris: List[Tuple[int, int, int]] = []
    for j in range(ny_ - 1):
        for i in range(nx - 1):
            v00 = int(idx[j, i]); v10 = int(idx[j, i + 1])
            v11 = int(idx[j + 1, i + 1]); v01 = int(idx[j + 1, i])
            tris.append((v00, v10, v11))
            tris.append((v00, v11, v01))
    return MeshTri(pts, np.array(tris, dtype=int).T.copy())


# ====================================================================== 求解
def _solve_head(mesh: MeshTri, dirichlet: np.ndarray,
                values: np.ndarray) -> np.ndarray:
    """求解 ∇·(k∇H)=0（k 均匀，可约去）：给定节点水头，其余 Neumann。"""
    basis = Basis(mesh, ElementTriP1())
    stiffness = asm(laplace, basis).tocsr()
    head = np.zeros(basis.N)
    head[dirichlet] = values
    f = np.zeros(basis.N)
    # 第三个参数必须传入已赋 Dirichlet 值的解向量（否则边界水头会被当成 0）
    reduced, rhs, head, free = condense(stiffness, f, head, D=dirichlet)
    head[free] = spla.spsolve(reduced.tocsc(), rhs)
    return head


def _nodal_gradient(mesh: MeshTri, field: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
    """P1 场 → 单元梯度 → 节点平均梯度 (gx, gy)。"""
    p, t = mesh.p, mesh.t
    x0, x1, x2 = p[0, t[0]], p[0, t[1]], p[0, t[2]]
    y0, y1, y2 = p[1, t[0]], p[1, t[1]], p[1, t[2]]
    det = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
    det = np.where(np.abs(det) < 1e-14, 1e-14, det)
    vals = field[t]
    bx = np.stack([(y1 - y2), (y2 - y0), (y0 - y1)]) / det
    by = np.stack([(x2 - x1), (x0 - x2), (x1 - x0)]) / det
    gx_e = (bx * vals).sum(axis=0)
    gy_e = (by * vals).sum(axis=0)

    nv = mesh.nvertices
    gx = np.zeros(nv); gy = np.zeros(nv); cnt = np.zeros(nv)
    for k in range(3):
        np.add.at(gx, t[k], gx_e)
        np.add.at(gy, t[k], gy_e)
        np.add.at(cnt, t[k], 1.0)
    cnt = np.maximum(cnt, 1.0)
    return gx / cnt, gy / cnt


def _trapz(xs: np.ndarray, ys: np.ndarray) -> float:
    """按 x 排序后梯形积分（避免依赖 numpy 版本差异）。"""
    order = np.argsort(xs)
    x = xs[order]
    y = ys[order]
    if x.size < 2:
        return 0.0
    return float(np.sum(0.5 * (y[:-1] + y[1:]) * np.diff(x)))


def _flux_vertical_section(mesh: MeshTri, head: np.ndarray,
                           x0: float, k: float) -> float:
    """竖直截面 x=x0 上的单宽渗流量（m²/s），v_x=-k·∂H/∂x。"""
    px = mesh.p[0]
    sel = np.where(np.abs(px - x0) <= 1e-9)[0]
    if sel.size < 2:
        # x0 不在网格线上时取最近的网格线
        x0 = float(px[np.argmin(np.abs(px - x0))])
        sel = np.where(np.abs(px - x0) <= 1e-9)[0]
    gx, _ = _nodal_gradient(mesh, head)
    q_local = -k * gx[sel]
    return _trapz(mesh.p[1, sel], q_local)


class _VelocityField:
    """渗流速度场插值（v=-k∇H），用于流线积分。"""

    def __init__(self, mesh: MeshTri, vx: np.ndarray, vy: np.ndarray):
        self.p = mesh.p
        self.t = mesh.t
        self.vx = vx
        self.vy = vy
        self.bbox_min = np.stack([self.p[0, self.t].min(axis=0),
                                  self.p[1, self.t].min(axis=0)])
        self.bbox_max = np.stack([self.p[0, self.t].max(axis=0),
                                  self.p[1, self.t].max(axis=0)])
        self._tri_x = self.p[0, self.t]
        self._tri_y = self.p[1, self.t]

    def at(self, x: float, y: float) -> Optional[Tuple[float, float]]:
        cand = np.where(
            (self.bbox_min[0] <= x) & (self.bbox_max[0] >= x)
            & (self.bbox_min[1] <= y) & (self.bbox_max[1] >= y)
        )[0]
        if cand.size == 0:
            return None
        x1, x2, x3 = self._tri_x[0, cand], self._tri_x[1, cand], self._tri_x[2, cand]
        y1, y2, y3 = self._tri_y[0, cand], self._tri_y[1, cand], self._tri_y[2, cand]
        den = (y2 - y3) * (x1 - x3) + (x3 - x2) * (y1 - y3)
        den = np.where(np.abs(den) < 1e-14, 1e-14, den)
        l1 = ((y2 - y3) * (x - x3) + (x3 - x2) * (y - y3)) / den
        l2 = ((y3 - y1) * (x - x3) + (x1 - x3) * (y - y3)) / den
        l3 = 1.0 - l1 - l2
        tol = -1e-9
        hit = np.where((l1 >= tol) & (l2 >= tol) & (l3 >= tol))[0]
        if hit.size == 0:
            return None
        k = cand[hit[0]]
        verts = self.t[:, k]
        vx = float(l1[hit[0]] * self.vx[verts[0]] + l2[hit[0]] * self.vx[verts[1]]
                   + l3[hit[0]] * self.vx[verts[2]])
        vy = float(l1[hit[0]] * self.vy[verts[0]] + l2[hit[0]] * self.vy[verts[1]]
                   + l3[hit[0]] * self.vy[verts[2]])
        return vx, vy


def _trace_streamlines(field: _VelocityField, seeds: np.ndarray,
                       x_lo: float, x_hi: float, y_lo: float, y_hi: float,
                       step: float) -> List[List[List[float]]]:
    """从上游地表向下游积分渗流流线（RK2，单位方向）。"""
    lines: List[List[List[float]]] = []
    for x0, y0 in seeds:
        x, y = float(x0), float(y0)
        pts: List[List[float]] = [[round(x, 4), round(y, 4)]]
        for _ in range(700):
            s1 = field.at(x, y)
            if s1 is None:
                break
            n1 = math.hypot(s1[0], s1[1])
            if n1 < 1e-12:
                break
            dx1, dy1 = s1[0] / n1, s1[1] / n1
            mid = field.at(x + 0.5 * step * dx1, y + 0.5 * step * dy1)
            if mid is None:
                break
            n2 = math.hypot(mid[0], mid[1])
            if n2 < 1e-12:
                break
            x += step * mid[0] / n2
            y += step * mid[1] / n2
            if x < x_lo or x > x_hi or y < y_lo or y > y_hi:
                break
            if field.at(x, y) is None:
                break
            pts.append([round(x, 4), round(y, 4)])
        if len(pts) >= 3:
            lines.append(pts)
    return lines


# ====================================================================== 校核
def _verdict_from_sf(sf: float) -> str:
    if sf >= SAFETY_SAFE:
        return "安全"
    if sf >= SAFETY_CAUTION:
        return "注意"
    return "危险"


_VERDICT_EN = {"安全": "safe", "注意": "caution", "危险": "danger"}


def _force_per_len_disp(v_kn_m: float) -> Tuple[float, str]:
    """线荷载（kN/m）→ 显示值与单位（公制 kN/m / 英制 kip/ft）。"""
    if is_imperial():
        return round(v_kn_m * LBF_PER_N / FT_PER_M, 1), "kip/ft"
    return round(v_kn_m, 1), "kN/m"


def _moment_per_len_disp(v_knm_m: float) -> Tuple[float, str]:
    """线力矩（kN·m/m）→ 显示值与单位（公制 kN·m/m / 英制 kip·ft/ft）。"""
    if is_imperial():
        return round(v_knm_m * LBF_PER_N, 1), "kip·ft/ft"
    return round(v_knm_m, 1), "kN·m/m"


def _force_per_len_str(v_kn_m: float) -> str:
    """线荷载（kN/m）→ 带单位文本（公制 kN/m / 英制 kip/ft）。"""
    if is_imperial():
        return f"{num(v_kn_m * LBF_PER_N / FT_PER_M)} kip/ft"
    return f"{num(v_kn_m)} kN/m"


def _perm_str(v_ms: float) -> str:
    """渗透系数（m/s）→ 带单位文本（公制 m/s / 英制 ft/s）。"""
    if is_imperial():
        return f"{num(v_ms * FT_PER_M)} ft/s"
    return f"{num(v_ms)} m/s"


def _rate_str(q_m2s: float) -> str:
    """单宽渗流量（m²/s）→ 带单位文本（公制 L/s·m / 英制 gpm/ft）。"""
    if is_imperial():
        # 1 m²/s = 4831.3 gpm/ft（1000 L/s·m，1 L/s·m ≈ 4.83 gpm/ft）
        return f"{num(q_m2s * 4831.3)} gpm/ft"
    return f"{num(q_m2s * 1000.0)} L/s·m"


def _trapezoid_area_centroid(base_width: float, crest_width: float,
                             height: float, m_up: float, m_dn: float) -> Tuple[float, float]:
    """梯形坝截面面积与形心 x（以坝踵为原点）。"""
    a_rect = crest_width * height
    x_rect = m_up * height + crest_width / 2.0
    a_left = 0.5 * m_up * height ** 2
    x_left = 2.0 * m_up * height / 3.0
    a_right = 0.5 * m_dn * height ** 2
    x_right = base_width - m_dn * height / 3.0
    area = a_rect + a_left + a_right
    x_c = (a_rect * x_rect + a_left * x_left + a_right * x_right) / max(area, 1e-12)
    return area, x_c


# ====================================================================== 主流程
def run_seepage(model: Dict[str, Any],
                library: Optional[MaterialsLibrary] = None) -> Dict[str, Any]:
    """求解"水从坝基渗过去，大坝还稳不稳"问题，错误抛 ValueError（中文消息）。"""
    t0 = time.perf_counter()
    out = _solve(model, library)
    out["solve_ms"] = round((time.perf_counter() - t0) * 1000.0, 1)
    return out


def _solve(model: Dict[str, Any], library: Optional[MaterialsLibrary]) -> Dict[str, Any]:
    if not isinstance(model, dict):
        raise ValueError("渗流模型必须是 JSON 对象")

    kind = str(model.get("kind") or "dam_seepage").strip().lower()
    if kind not in ("dam_seepage", "seepage", "dam"):
        raise ValueError(f"暂不支持的渗流场景「{kind}」（当前支持：dam_seepage 大坝坝基渗流）")

    def _num(src: Dict[str, Any], *keys: str, default: Optional[float] = None) -> Optional[float]:
        for key in keys:
            if key in src and src[key] is not None:
                try:
                    return float(src[key])
                except (TypeError, ValueError):
                    raise ValueError(f"参数 {key} 必须是数字")
        return default

    dam = model.get("dam") or {}
    if not isinstance(dam, dict):
        raise ValueError("dam 必须是对象（含 height_m / crest_width_m / upstream_slope / downstream_slope）")

    h = _num(dam, "height_m", "height", default=_num(model, "height_m"))
    crest = _num(dam, "crest_width_m", "crest_width", default=_num(model, "crest_width_m"))
    m_up = _num(dam, "upstream_slope", "m_upstream", default=0.0)
    m_dn = _num(dam, "downstream_slope", "m_downstream", default=0.7)
    if h is None or crest is None:
        raise ValueError("缺少坝体尺寸：需要 height_m（坝高）与 crest_width_m（坝顶宽）")
    if not (1.0 <= h <= 60.0):
        raise ValueError(f"坝高 h={h:g} m 超出范围（1~60 m）")
    if not (0.3 <= crest <= 30.0):
        raise ValueError(f"坝顶宽 crest_width_m={crest:g} m 超出范围（0.3~30 m）")
    if m_up is None:
        m_up = 0.0
    if m_dn is None:
        m_dn = 0.7
    if not (0.0 <= m_up <= 3.0) or not (0.0 <= m_dn <= 3.0):
        raise ValueError("坝坡坡比应在 0~3 之间（单位：每 1 m 高对应的水平宽度）")

    base_width = crest + (m_up + m_dn) * h

    # ---- 水位（以坝基面 y=0 为基准） ----
    h1 = _num(model, "upstream_level_m", "upstream_level", "water_level_m")
    h2 = _num(model, "downstream_level_m", "downstream_level", default=0.0)
    if h1 is None:
        raise ValueError("缺少上游水位：请给 upstream_level_m（m，基准为坝基底面）")
    if h2 is None:
        h2 = 0.0
    if not (0.0 < h1 <= h + 1e-9):
        raise ValueError(f"上游水位 h1={h1:g} m 应在 0~坝高 {h:g} m 之间")
    if not (0.0 <= h2 < h1):
        raise ValueError(f"下游水位 h2={h2:g} m 应不小于 0 且低于上游水位 {h1:g} m")

    # ---- 坝基与坝体材料 ----
    name = str(model.get("material") or "").strip()
    if not name:
        raise ValueError("缺少坝体材料：请说明是混凝土C20、砖砌体还是其他材料")
    lib = library if library is not None else load_library()
    rec = lib.find_material(name)
    if rec is None:
        avail = "、".join(r["name"] for r in lib.all_materials())
        raise ValueError(f"材料库中没有「{name}」，可选材料：{avail}")
    density = _num(model, "density_kg_m3")
    if density is None:
        density = float(rec.get("density") or 2400.0)
    mat_name = str(rec.get("name") or name)

    permeability = _num(model, "foundation_permeability_m_s", "permeability_m_s")
    foundation = model.get("foundation") or {}
    if isinstance(foundation, dict):
        if permeability is None:
            permeability = _num(foundation, "permeability_m_s")
    if permeability is None:
        permeability = 1e-6
    if not (1e-11 <= permeability <= 1e-2):
        raise ValueError(f"坝基渗透系数 k={permeability:g} m/s 超出范围（1e-11~1e-2 m/s）")

    depth = _num(model, "foundation_depth_m", "depth_m")
    if depth is None and isinstance(foundation, dict):
        depth = _num(foundation, "depth_m")
    if depth is None:
        depth = max(1.5 * h, 5.0)
    if not (0.5 <= depth <= 200.0):
        raise ValueError(f"坝基深度 {depth:g} m 超出范围（0.5~200 m）")

    friction = _num(model, "friction_coeff", "friction")
    if friction is None:
        friction = 0.6
    if not (0.1 <= friction <= 1.5):
        raise ValueError("坝基摩擦系数 friction_coeff 应在 0.1~1.5 之间")

    mesh_level = str(model.get("mesh_level") or "medium")

    # ---- 渗流有限元 ----
    t_start = time.perf_counter()
    lup = 2.0 * h
    ldown = 2.0 * h
    mesh = _build_foundation_mesh(base_width, depth, lup, ldown, mesh_level)
    p = mesh.p
    top = np.where(np.abs(p[1]) < 1e-9)[0]
    up_nodes = top[p[0, top] <= 1e-9]
    dn_nodes = top[p[0, top] >= base_width - 1e-9]
    if up_nodes.size < 2 or dn_nodes.size < 2:
        raise ValueError("网格边界异常：未找到上游/下游地表节点")
    dirichlet = np.concatenate([up_nodes, dn_nodes])
    values = np.concatenate([np.full(up_nodes.size, h1), np.full(dn_nodes.size, h2)])
    head = _solve_head(mesh, dirichlet, values)
    gx, gy = _nodal_gradient(mesh, head)
    vx, vy = -permeability * gx, -permeability * gy
    t_solved = time.perf_counter()

    # ---- 渗流量：坝底中部竖直截面 ----
    q_unit = abs(_flux_vertical_section(mesh, head, 0.5 * base_width, permeability))

    # ---- 扬压力：坝底水头 → 压力 p=ρgH 沿坝底积分 ----
    base_nodes = top[(p[0, top] >= -1e-9) & (p[0, top] <= base_width + 1e-9)]
    order = np.argsort(p[0, base_nodes])
    x_base = p[0, base_nodes][order]
    head_base = head[base_nodes][order]
    integral_h = _trapz(x_base, head_base)
    uplift = WATER_DENSITY * GRAVITY * integral_h / 1000.0          # kN/m
    x_uplift = (_trapz(x_base, head_base * x_base) / integral_h
                if integral_h > 1e-12 else 0.5 * base_width)

    # ---- 出逸比降：下游地表 |∂H/∂y| 的最大值 ----
    # 坝趾是坝基不透水面与下游水面的锐角交点，该点势流梯度理论奇异、数值随网格变化；
    # 避开坝趾后 0.1B~0.3B 的"坝趾过程区"内取值，既反映管涌风险又不依赖网格。
    surf_dn = top[(p[0, top] >= base_width + 0.1 * base_width - 1e-9)
                  & (p[0, top] <= base_width + 0.3 * base_width + 1e-9)]
    i_exit = float(np.max(np.abs(gy[surf_dn]))) if surf_dn.size else 0.0

    # ---- 稳定校核 ----
    area, x_c = _trapezoid_area_centroid(base_width, crest, h, m_up, m_dn)
    weight = density * GRAVITY * area / 1000.0                       # kN/m
    p1 = 0.5 * WATER_DENSITY * GRAVITY * h1 ** 2 / 1000.0            # kN/m
    p2 = 0.5 * WATER_DENSITY * GRAVITY * h2 ** 2 / 1000.0
    drive_force = max(p1 - p2, 0.0)
    effective = weight - uplift

    slide_sf = (effective * friction / drive_force) if drive_force > 1e-9 else 999.0
    slide_sf = max(slide_sf, 0.0)

    m_drive = (p1 * h1 - p2 * h2) / 3.0 + uplift * (base_width - x_uplift)
    m_resist = weight * (base_width - x_c)
    overturn_sf = (m_resist / m_drive) if m_drive > 1e-9 else 999.0
    overturn_sf = max(overturn_sf, 0.0)

    piping_sf = (I_EXIT_ALLOW / i_exit) if i_exit > 1e-12 else 999.0
    sf_min = min(slide_sf, overturn_sf, piping_sf)
    verdict = _verdict_from_sf(sf_min)

    if is_en():
        sl_val, sl_unit = _force_per_len_disp(drive_force)
        sl_lim, _ = _force_per_len_disp(effective * friction)
        ov_val, ov_unit = _moment_per_len_disp(m_drive)
        ov_lim, _ = _moment_per_len_disp(m_resist)
        checks = [
            {"name": "Sliding stability", "value": sl_val,
             "limit": sl_lim, "unit": sl_unit,
             "safety_factor": round(slide_sf, 2), "pass": slide_sf >= SAFETY_CAUTION},
            {"name": "Overturning", "value": ov_val, "limit": ov_lim,
             "unit": ov_unit, "safety_factor": round(overturn_sf, 2),
             "pass": overturn_sf >= SAFETY_CAUTION},
            {"name": "Exit gradient (piping)", "value": round(i_exit, 3),
             "limit": round(I_EXIT_ALLOW, 3), "unit": "",
             "safety_factor": round(piping_sf, 2), "pass": piping_sf >= SAFETY_CAUTION},
        ]
    else:
        checks = [
            {"name": "抗滑稳定", "value": round(drive_force, 1),
             "limit": round(effective * friction, 1), "unit": "kN/m",
             "safety_factor": round(slide_sf, 2), "pass": slide_sf >= SAFETY_CAUTION},
            {"name": "抗倾覆", "value": round(m_drive, 1), "limit": round(m_resist, 1),
             "unit": "kN·m/m", "safety_factor": round(overturn_sf, 2),
             "pass": overturn_sf >= SAFETY_CAUTION},
            {"name": "出逸比降（管涌）", "value": round(i_exit, 3),
             "limit": round(I_EXIT_ALLOW, 3), "unit": "",
             "safety_factor": round(piping_sf, 2), "pass": piping_sf >= SAFETY_CAUTION},
        ]

    # ---- 临界上游水位 ----
    # 抗滑：4.9·h² + (U/h1)·tanφ·h − (W·tanφ + 4.9·h2²) = 0
    a_up = (uplift / h1) if h1 > 1e-9 else 0.0
    b_up = 0.5 * WATER_DENSITY * GRAVITY / 1000.0
    tan_phi = friction
    c0 = weight * tan_phi + b_up * h2 ** 2
    disc = (a_up * tan_phi) ** 2 + 4.0 * b_up * c0
    h_crit_slide = (-a_up * tan_phi + math.sqrt(max(disc, 0.0))) / (2.0 * b_up)
    # 管涌：出逸比降近似与上下游水头差成正比
    h_crit_pipe = h2 + (h1 - h2) * (I_EXIT_ALLOW / i_exit) if i_exit > 1e-12 else h1
    candidates = [("sliding " if is_en() else "抗滑", h_crit_slide),
                  ("piping " if is_en() else "管涌", h_crit_pipe)]
    candidates.sort(key=lambda item: item[1])
    crit_mode, crit_level = candidates[0]
    crit_level = max(crit_level, h2)

    # ---- 薄弱部位与破坏形式 ----
    if sf_min == slide_sf:
        weak_part = ("the foundation contact surface (heel to toe)" if is_en()
                     else "坝基接触面（坝踵至坝趾）")
        failure = ("sliding along the foundation base: uplift cancels part of the self-weight "
                   "and the water thrust pushes the dam downstream" if is_en()
                   else "沿坝基面整体滑移：扬压力抵消部分自重，水推力把坝体向下游推走")
    elif sf_min == overturn_sf:
        weak_part = "the toe (downstream dam foot)" if is_en() else "坝趾（下游侧坝脚）"
        failure = ("overturning about the toe: upstream water pressure rotates the dam "
                   "downstream and the heel lifts off the bedrock" if is_en()
                   else "绕坝趾倾覆：上游水压力把坝体向下游翻转，坝踵与基岩脱开")
    else:
        weak_part = ("the foundation surface downstream of the toe" if is_en()
                     else "坝趾下游地基表面")
        failure = ("piping at the seepage exit: fine particles are washed out and the "
                   "undermined foundation can collapse or slide" if is_en()
                   else "渗流出口管涌：细颗粒被渗水带出，坝基被掏空后诱发塌陷或滑移")

    if is_en():
        failure_modes = [
            f"Mode 1 (governing): {failure}; governing safety factor {num(sf_min)}",
            f"Mode 2: uplift on the dam base {_force_per_len_str(uplift)}, "
            f"{num(uplift / max(weight, 1e-9) * 100)}% of the dam self-weight "
            f"{_force_per_len_str(weight)}, leaving an effective weight of "
            f"{_force_per_len_str(effective)}",
            f"Mode 3: exit gradient at the toe {num(i_exit)} "
            f"(allowable {num(I_EXIT_ALLOW)}), unit-width seepage through the foundation "
            f"{_rate_str(q_unit)}",
        ]
    else:
        failure_modes = [
            f"形式一（控制）：{failure}；控制工况下安全系数 {sf_min:.2f}",
            f"形式二：坝底扬压力 {uplift:.1f} kN/m，占坝体自重 {weight:.1f} kN/m 的 "
            f"{uplift / max(weight, 1e-9) * 100:.0f}%，有效自重降到 {effective:.1f} kN/m",
            f"形式三：下游出逸比降 {i_exit:.3f}（允许 {I_EXIT_ALLOW:.2f}），"
            f"坝基单宽渗流量 {q_unit * 1000:.2f} L/s·m",
        ]

    # ---- 加强建议 ----
    advice: List[str] = []
    if is_en():
        if sf_min < SAFETY_SAFE:
            if sf_min == slide_sf:
                widen = base_width * math.sqrt(max(SAFETY_SAFE / max(slide_sf, 1e-6), 1.0))
                advice.append(
                    f"Widen the dam base: increase the base width from {length_m(base_width)} "
                    f"to about {length_m(widen)}, or add a toe key / anti-slide piles "
                    f"downstream to raise the sliding safety factor"
                )
            if piping_sf < SAFETY_SAFE:
                advice.append(
                    f"Add a filter layer and drainage ditch at the toe (slope or blanket "
                    f"drainage) to reduce the exit gradient from {num(i_exit)} to below "
                    f"{num(I_EXIT_ALLOW)} and prevent piping"
                )
            advice.append(
                f"Place a seepage cutoff curtain near the heel and install foundation "
                f"drainage wells: uplift can be cut from {_force_per_len_str(uplift)} by "
                f"20%–40%, which is equivalent to raising the sliding safety factor"
            )
            advice.append("Lengthen the seepage path: a clay blanket or concrete cut-off "
                          "upstream and relief wells downstream lower the head under the "
                          "foundation")
        else:
            advice.append("Seepage stability and sliding are adequate at the current water "
                          "levels; during high-water periods monitor the turbidity of seepage "
                          "at the toe and the uplift")
            advice.append("Check regularly that downstream drainage flows freely — a clogged "
                          "filter layer raises the uplift")
    elif sf_min < SAFETY_SAFE:
        if sf_min == slide_sf:
            widen = base_width * math.sqrt(max(SAFETY_SAFE / max(slide_sf, 1e-6), 1.0))
            advice.append(
                f"加宽坝底：底宽由 {base_width:.1f} m 增至约 {widen:.1f} m，"
                f"或在下游增设齿墙/抗滑桩，提高抗滑安全系数"
            )
        if piping_sf < SAFETY_SAFE:
            advice.append(
                f"坝趾增设反滤层与排水沟（贴坡排水/褥垫排水），把出逸比降从 {i_exit:.3f} "
                f"降到 {I_EXIT_ALLOW:.2f} 以下，防止管涌"
            )
        advice.append(
            f"在坝踵附近设置防渗帷幕并布置坝基排水孔：可把扬压力从 {uplift:.1f} kN/m "
            f"降低 20%~40%，等效增加抗滑安全系数"
        )
        advice.append("延长渗径：上游设置黏土铺盖或混凝土防渗板，下游设减压井降低坝基水头")
    else:
        advice.append("当前水位下渗流稳定与抗滑均达标；高水位期建议监测坝趾渗水浑浊度与扬压力")
        advice.append("定期检查下游排水是否通畅，反滤层淤堵会导致扬压力升高")

    # ---- 通俗叙述 ----
    if is_en():
        narrative = [
            f"Model: the dam is simplified to a {length_m(h)}-high {mat_name} gravity dam "
            f"with a crest {length_m(crest)} wide (upstream slope {num(m_up)}, downstream "
            f"slope {num(m_dn)}, base width {length_m(base_width)}); the foundation is a "
            f"homogeneous permeable layer {length_m(depth)} deep with permeability "
            f"{_perm_str(permeability)}; a 2D steady-seepage FE model (P1 triangles, "
            f"{mesh.nelements} elements) computes the head field in the foundation.",
            f"Water levels: upstream {length_m(h1)}, downstream {length_m(h2)}, head "
            f"difference {length_m(h1 - h2)}; upstream water thrust {_force_per_len_str(p1)} "
            f"(downstream {_force_per_len_str(p2)}), net thrust "
            f"{_force_per_len_str(drive_force)}.",
            f"Seepage: unit-width flow through the foundation about {_rate_str(q_unit)}, "
            f"uplift on the dam base {_force_per_len_str(uplift)}, equal to "
            f"{num(uplift / max(weight, 1e-9) * 100)}% of the dam self-weight; exit gradient "
            f"at the toe {num(i_exit)} (allowable {num(I_EXIT_ALLOW)}).",
            f"Stability: self-weight {_force_per_len_str(weight)}, sliding safety factor "
            f"{num(slide_sf)}, overturning {num(overturn_sf)}, piping {num(piping_sf)}; "
            f"overall verdict \"{_VERDICT_EN.get(verdict, verdict)}\".",
            f"The weakest part is {weak_part}, and the main failure mode is {failure}.",
            f"Critical upstream level about {length_m(crit_level)} ({crit_mode}governs failure); "
            + ("this level already exceeds the dam height, so overtopping would occur first."
               if crit_level >= h else
               "above this level the dam becomes unsafe."),
        ]
        narrative.append(
            "Seepage is modelled as 2D plane-strain steady Darcy flow through a homogeneous "
            "isotropic foundation, with the dam body treated as impermeable; real dams also have "
            "cutoff curtains, drains, faults and layers, so these results only illustrate seepage "
            "concepts — dam safety assessment must be carried out by a professional organisation.")
        narrative.append(
            "These results rest on representative material values and simplified assumptions and "
            "are for everyday reference only; consult a professional engineer for anything "
            "involving personal or structural safety.")
        assumptions = [
            "2D plane-strain steady seepage; homogeneous isotropic foundation following "
            "Darcy's law",
            "The dam body is an impermeable gravity dam and seepage occurs only in the "
            "foundation; the foundation base and the far field are impermeable boundaries",
            "Uplift is integrated from the head distribution as p=ρgH over the full base width; "
            "relief from cutoff curtains and drains is ignored",
            "Sliding resistance is estimated as (W−U)tanφ against the net water thrust at the "
            "foundation contact; toe keys and rock cohesion are ignored",
            "Overturning is estimated from moment balance about the toe; piping compares the "
            "maximum exit gradient with the allowable value of 0.5",
        ]
    else:
        narrative = [
            f"建模：把大坝简化为 {h:g} m 高、坝顶宽 {crest:g} m 的{mat_name}重力坝"
            f"（上游坡比 {m_up:g}、下游坡比 {m_dn:g}，坝底宽 {base_width:.1f} m），"
            f"坝基为 {depth:g} m 深、渗透系数 {permeability:.1e} m/s 的均质透水地基；"
            f"用二维稳态渗流有限元（P1 三角单元，{mesh.nelements} 个单元）计算坝基水头场。",
            f"水位：上游 {h1:g} m、下游 {h2:g} m，水头差 {h1 - h2:g} m；"
            f"上游水压力 {p1:.0f} kN/m（下游 {p2:.0f} kN/m），净推力 {drive_force:.0f} kN/m。",
            f"渗流：坝基单宽渗流量约 {q_unit * 1000:.2f} L/s·m，坝底扬压力 {uplift:.0f} kN/m，"
            f"相当于坝体自重的 {uplift / max(weight, 1e-9) * 100:.0f}%；"
            f"坝趾出逸比降 {i_exit:.3f}（允许 {I_EXIT_ALLOW:.2f}）。",
            f"稳定：自重 {weight:.0f} kN/m，抗滑安全系数 {slide_sf:.2f}、抗倾覆 {overturn_sf:.2f}、"
            f"抗管涌 {piping_sf:.2f}，综合判定「{verdict}」。",
            f"最薄弱部位是{weak_part}，主要破坏形式为{failure}。",
            f"临界上游水位约 {crit_level:.1f} m（{crit_mode}控制）；"
            + ("该水位已超过坝高，实际会先发生漫顶溢流。" if crit_level >= h
               else "超过该水位后大坝将进入不安全状态。"),
        ]
        narrative.append("渗流按二维平面应变、达西定律、均质各向同性地基考虑，坝体视为不透水；"
                         "实际工程还有帷幕、排水孔、断层与分层，结果仅供理解渗流概念，"
                         "大坝安全评定必须由专业单位完成。")
        narrative.append("以上基于材料代表值与简化假定，结果仅供生活参考，涉及人身或结构安全请咨询专业工程师。")

        assumptions = [
            "二维平面应变稳态渗流，坝基均质各向同性，服从达西定律",
            "坝体为不透水重力坝，渗流仅发生在坝基；坝基底面与远场为不透水边界",
            "扬压力按坝底水头 p=ρgH 全截面线性分布积分，未计帷幕与排水孔的减压作用",
            "抗滑按坝基接触面摩擦抗力 (W−U)tanφ 与净水推力之比估算，未计坝趾齿墙与岩体黏聚力",
            "抗倾覆按绕坝趾力矩平衡估算；管涌按下游地表最大出逸比降与允许值 0.5 比较",
        ]

    # ---- 渲染数据（水头云图 + 渗流流线 + 坝体轮廓） ----
    seeds = []
    for frac in (0.12, 0.24, 0.36, 0.48, 0.60, 0.72, 0.84):
        seeds.append((-frac * lup, -0.02 * depth))
    step = max(base_width, depth) / 90.0
    field = _VelocityField(mesh, vx, vy)
    streamlines = _trace_streamlines(
        field, seeds, -lup, base_width + ldown, -depth, 0.0, step
    )

    dam_polygon = [
        [0.0, 0.0],
        [m_up * h, h],
        [m_up * h + crest, h],
        [base_width, 0.0],
    ]
    render = {
        "field": "head",
        "unit": "m",
        "nodes": [[round(float(x), 4), round(float(y), 4)]
                  for x, y in zip(p[0], p[1])],
        "tris": [[int(i) for i in tri] for tri in mesh.t.T],
        "values": [round(float(v), 3) for v in head],
        "value_location": "node",
        "streamlines": streamlines,
        "dam": {"points": dam_polygon},
        "bounds": [round(-lup, 4), round(-depth, 4),
                   round(base_width + ldown, 4), round(h, 4)],
        "head_min": round(float(head.min()), 2),
        "head_max": round(float(head.max()), 2),
    }

    t_done = time.perf_counter()
    return {
        "kind": "seepage",
        "ok": True,
        "model_name": str(model.get("name") or
                          (f"{mat_name} dam seepage" if is_en() else f"{mat_name}大坝渗流")),
        "scenario": "dam_seepage",
        "material": mat_name,
        "density_kg_m3": round(density, 1),
        "dam": {
            "height_m": round(h, 3),
            "crest_width_m": round(crest, 3),
            "base_width_m": round(base_width, 3),
            "upstream_slope": round(m_up, 3),
            "downstream_slope": round(m_dn, 3),
            "area_m2": round(area, 2),
            "weight_kN_per_m": round(weight, 1),
        },
        "upstream_level_m": round(h1, 2),
        "downstream_level_m": round(h2, 2),
        "head_diff_m": round(h1 - h2, 2),
        "water_pressure_kN_per_m": round(drive_force, 1),
        "uplift_kN_per_m": round(uplift, 1),
        "uplift_ratio": round(uplift / max(weight, 1e-9), 3),
        "permeability_m_s": float(permeability),
        "foundation_depth_m": round(depth, 2),
        "seepage_q_m3_s": round(float(q_unit), 12),
        "seepage_q_l_s": round(q_unit * 1000.0, 4),
        "exit_gradient": round(i_exit, 4),
        "exit_gradient_allow": I_EXIT_ALLOW,
        "piping_sf": round(piping_sf, 2),
        "slide_sf": round(slide_sf, 2),
        "overturn_sf": round(overturn_sf, 2),
        "safety_factor": round(sf_min, 2),
        "verdict": verdict,
        "critical_level_m": round(min(crit_level, h), 2),
        "critical_level_raw_m": round(crit_level, 2),
        "critical_mode": crit_mode,
        "checks": checks,
        "weak_part": weak_part,
        "failure_modes": failure_modes,
        "advice": advice,
        "render": render,
        "assumptions": assumptions,
        "narrative": narrative,
        "timing": {
            "total_s": round(t_done - t_start, 3),
            "assemble_s": round(t_solved - t_start, 3),
            "solve_s": round(t_done - t_solved, 3),
        },
        "mesh": {
            "level": mesh_level,
            "n_elements": int(mesh.nelements),
            "n_vertices": int(mesh.nvertices),
            "dof": int(mesh.p.shape[1]),
        },
    }
