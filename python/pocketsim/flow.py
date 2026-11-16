# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""二维势流有限元（P4 流体阶段，风压算例）。

场景：风垂直吹向一堵独立的矩形墙（砖墙、围墙、隔断），回答"会不会被吹倒/吹裂"。

做法
----
1. 流体域：竖直剖面 x∈[-4h, 7h]、y∈[0, 3h]（h 为墙高），墙是域内被挖掉的矩形
   （x∈[0, t]，y∈[0, h]，t 为墙厚），网格线严格落在墙面与墙顶；
2. 控制方程：不可压、无粘、无旋 → 速度势 φ 满足 Laplace ∇²φ=0；
   边界：入口 φ=0、出口 φ=U·Lx（Dirichlet），地面/天空/墙面 ∂φ/∂n=0（自然边界，
   不可穿透）；
3. P1 三角单元组装 Laplace，稀疏直接法求解；单元梯度 → 节点平均得速度场；
4. Bernoulli 得压力场 p = ½ρ(U² − |v|²)（远场gage 0）；
5. 结构校核不走势流积分（势流无分离，前后压差会抵销成 0，即 d'Alembert 佯谬），
   而按规范体型系数取净风压 Cp=1.2，再算抗倾覆、抗滑移、墙体根部抗弯；
6. 由校核式反解临界风速/临界风级（几级风会倒）。

单位：长度 m、速度 m/s、压力 Pa、力 N。返回 JSON 的所有数值均为原生类型。
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
    PSI_PER_MPA,
    dim_mm,
    force_n,
    is_en,
    is_imperial,
    length_m,
    length_mm,
    length_unit,
    num,
    pressure_pa,
    speed_ms,
    stress_mpa,
    t,
)
from .materials import MaterialsLibrary, load_library

# 空气密度（kg/m³，20°C 标准大气压）
AIR_DENSITY = 1.225
GRAVITY = 9.8

# 竖直墙面（独立墙/围墙）净风压体型系数：迎风 +0.8、背风 -0.4，取 1.2（工程常用）
CP_NET = 1.2

# 安全判定阈值（与框架结果一致）
SAFETY_SAFE = 2.0
SAFETY_CAUTION = 1.2

# 蒲福风级上限风速（m/s）：0~17 级，取每级上限（偏保守）
BEAUFORT_UPPER = [
    0.2, 1.5, 3.3, 5.4, 7.9, 10.7, 13.8, 17.1, 20.7, 24.4,
    28.4, 32.6, 36.9, 41.4, 46.1, 50.9, 56.0, 61.2,
]

MESH_FACTORS = {"coarse": 0.65, "medium": 1.0, "fine": 1.5}


# ====================================================================== 风级
def speed_from_beaufort(level: int) -> float:
    """蒲福风级 → 代表风速（m/s，取该级上限，偏安全）。"""
    lv = int(max(0, min(17, level)))
    return BEAUFORT_UPPER[lv]


def beaufort_from_speed(speed: float) -> int:
    """风速（m/s）→ 蒲福风级（落入区间的级数）。"""
    v = float(speed)
    for i, upper in enumerate(BEAUFORT_UPPER):
        if v <= upper + 1e-9:
            return i
    return 17


def beaufort_label(level: int) -> str:
    lv = int(max(0, min(17, level)))
    return t(f"{lv} 级风", f"Beaufort {lv}")


# ====================================================================== 网格
def _build_domain_mesh(h: float, t: float, level: str = "medium") -> MeshTri:
    """带矩形孔洞（墙体）的结构化三角网格。

    x、y 网格线分别严格包含墙的两个立面与墙顶，保证孔的边界与网格一致；
    孔洞区域的四边形单元被剔除后重新编号节点。
    """
    factor = MESH_FACTORS.get(level, 1.0)
    x_in, x_out, y_top = -4.0 * h, 7.0 * h, 3.0 * h

    n_up = max(6, int(round(22 * factor)))
    n_dn = max(6, int(round(38 * factor)))
    n_wall = max(4, int(round(14 * factor)))
    n_top = max(4, int(round(14 * factor)))

    xs = np.concatenate([
        np.linspace(x_in, 0.0, n_up + 1)[:-1],
        [0.0, t],
        np.linspace(t, x_out, n_dn + 1)[1:],
    ])
    ys = np.concatenate([
        np.linspace(0.0, h, n_wall + 1)[:-1],
        [h],
        np.linspace(h, y_top, n_top + 1)[1:],
    ])

    nx, ny = xs.size, ys.size
    idx = np.arange(nx * ny).reshape(ny, nx)          # idx[j, i] → 节点编号
    pts = np.array([[xs[i], ys[j]] for j in range(ny) for i in range(nx)]).T

    tris: List[Tuple[int, int, int]] = []
    for j in range(ny - 1):
        yc = 0.5 * (ys[j] + ys[j + 1])
        for i in range(nx - 1):
            xc = 0.5 * (xs[i] + xs[i + 1])
            # 剔除墙体内部单元（墙体从地面 y=0 起，高 h、厚 t）
            if 0.0 < xc < t and yc < h:
                continue
            v00 = int(idx[j, i]); v10 = int(idx[j, i + 1])
            v11 = int(idx[j + 1, i + 1]); v01 = int(idx[j + 1, i])
            tris.append((v00, v10, v11))
            tris.append((v00, v11, v01))

    used = np.unique(np.array(tris, dtype=int))
    remap = -np.ones(nx * ny, dtype=int)
    remap[used] = np.arange(used.size)
    tri = remap[np.array(tris, dtype=int)]
    p = pts[:, used]
    return MeshTri(p, tri.T.copy())


# ====================================================================== 速度场
def _nodal_velocity(mesh, phi: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
    """P1 势函数 → 单元梯度 → 节点平均速度 (vx, vy)。"""
    p = mesh.p
    t = mesh.t
    x0, x1, x2 = p[0, t[0]], p[0, t[1]], p[0, t[2]]
    y0, y1, y2 = p[1, t[0]], p[1, t[1]], p[1, t[2]]
    det = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
    det = np.where(np.abs(det) < 1e-14, 1e-14, det)

    phi_t = phi[t]                                   # (3, nelem)
    bx = np.stack([(y1 - y2), (y2 - y0), (y0 - y1)]) / det
    by = np.stack([(x2 - x1), (x0 - x2), (x1 - x0)]) / det
    gx = (bx * phi_t).sum(axis=0)
    gy = (by * phi_t).sum(axis=0)

    nv = mesh.nvertices
    vx = np.zeros(nv); vy = np.zeros(nv); cnt = np.zeros(nv)
    for k in range(3):
        np.add.at(vx, t[k], gx)
        np.add.at(vy, t[k], gy)
        np.add.at(cnt, t[k], 1.0)
    cnt = np.maximum(cnt, 1.0)
    return vx / cnt, vy / cnt


def _solve_potential(mesh: MeshTri, speed: float) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """在给定网格上求解势流（入口 φ=0、出口 φ=U·Lx，其余边界不可穿透）。

    返回 (phi, vx, vy)，速度已由 P1 势函数的单元梯度做节点平均。
    """
    basis = Basis(mesh, ElementTriP1())
    stiffness = asm(laplace, basis).tocsr()
    p = mesh.p
    x_in, x_out = float(p[0].min()), float(p[0].max())
    dirichlet = np.where((p[0] <= x_in + 1e-9) | (p[0] >= x_out - 1e-9))[0]
    phi = np.zeros(basis.N)
    phi[dirichlet] = np.where(p[0, dirichlet] <= x_in + 1e-9, 0.0,
                              speed * (x_out - x_in))
    f = np.zeros(basis.N)
    # condense 的第三个参数是载有 Dirichlet 值的解向量：必须传入 phi，
    # 否则边界势值会被当成 0 而得到全零解（solver.py 的边界值恒为 0 才可省略）
    reduced, rhs, phi, free = condense(stiffness, f, phi, D=dirichlet)
    phi[free] = spla.spsolve(reduced.tocsc(), rhs)
    vx, vy = _nodal_velocity(mesh, phi)
    return phi, vx, vy


class _VelocityField:
    """速度场插值：给定点 → 所在三角形内的线性插值速度（含包围盒粗筛）。"""

    def __init__(self, mesh, vx: np.ndarray, vy: np.ndarray):
        self.p = mesh.p
        self.t = mesh.t
        self.vx = vx
        self.vy = vy
        self.centroids = np.stack([
            self.p[0, self.t].mean(axis=0),
            self.p[1, self.t].mean(axis=0),
        ])
        self.bbox_min = np.stack([
            self.p[0, self.t].min(axis=0),
            self.p[1, self.t].min(axis=0),
        ])
        self.bbox_max = np.stack([
            self.p[0, self.t].max(axis=0),
            self.p[1, self.t].max(axis=0),
        ])
        self._tri_x = self.p[0, self.t]      # (3, nelem)
        self._tri_y = self.p[1, self.t]

    def at(self, x: float, y: float) -> Optional[Tuple[float, float, int]]:
        """返回 (vx, vy, 单元号)；落在孔洞/域外返回 None。"""
        inside_bbox = (
            (self.bbox_min[0] <= x) & (self.bbox_max[0] >= x)
            & (self.bbox_min[1] <= y) & (self.bbox_max[1] >= y)
        )
        cand = np.where(inside_bbox)[0]
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
        return vx, vy, int(k)


def _trace_streamlines(field: _VelocityField, x_seed: float, x_stop: float,
                       y_top: float, step: float) -> List[List[List[float]]]:
    """从入口一侧出发积分流线（单位方向 RK2），返回折线列表（m）。"""
    seeds = np.array([0.06, 0.18, 0.30, 0.43, 0.56, 0.69, 0.82, 0.94]) * y_top
    lines: List[List[List[float]]] = []
    max_steps = 260
    for y0 in seeds:
        x, y = x_seed, float(y0)
        pts: List[List[float]] = [[round(x, 4), round(y, 4)]]
        for _ in range(max_steps):
            s1 = field.at(x, y)
            if s1 is None:
                break
            n1 = math.hypot(s1[0], s1[1])
            if n1 < 1e-9:
                break
            dx1, dy1 = s1[0] / n1, s1[1] / n1
            mid = field.at(x + 0.5 * step * dx1, y + 0.5 * step * dy1)
            if mid is None:
                break
            n2 = math.hypot(mid[0], mid[1])
            if n2 < 1e-9:
                break
            x += step * mid[0] / n2
            y += step * mid[1] / n2
            if x >= x_stop or y <= 0.0 or y >= y_top:
                break
            # 步进后落点必须在流体域内（避免穿进墙体一个步长）
            if field.at(x, y) is None:
                break
            pts.append([round(x, 4), round(y, 4)])
        if len(pts) >= 3:
            lines.append(pts)
    return lines


# ====================================================================== 结构校核
def _tension_limit(name: str, strength: float) -> float:
    """墙体根部受弯的拉应力限值（MPa）。

    砌体沿通缝弯曲抗拉强度远低于抗压强度（GB 50003 设计值约 0.11 MPa），
    这里取 0.15 MPa 作为代表值；混凝土取抗压的 1/10；其余材料按材料强度取用。
    """
    lowered = name.lower()
    if "砖" in name or "砌体" in name or "brick" in lowered or "masonry" in lowered:
        return 0.15
    if "混凝土" in name or "concrete" in lowered:
        return max(0.1 * float(strength), 0.5)
    return float(strength)


def _verdict_from_sf(sf: float) -> str:
    if sf >= SAFETY_SAFE:
        return "安全"
    if sf >= SAFETY_CAUTION:
        return "注意"
    return "危险"


_VERDICT_EN = {"安全": "safe", "注意": "caution", "危险": "danger"}


def _moment_disp(v_nm: float) -> Tuple[float, str]:
    """力矩（N·m）→ 显示值与单位（公制 N·m / 英制 lbf·ft）。"""
    if is_imperial():
        return round(v_nm * LBF_PER_N * FT_PER_M, 1), "lbf·ft"
    return round(v_nm, 1), "N·m"


def _force_disp(v_kn: float) -> Tuple[float, str]:
    """力（kN）→ 显示值与单位（公制 kN / 英制 kip）。"""
    if is_imperial():
        return round(v_kn * LBF_PER_N, 2), "kip"
    return round(v_kn, 2), "kN"


def _force_kn_str(v_kn: float) -> str:
    """力（kN）→ 带单位文本（公制 kN / 英制 kip 或 lbf）。"""
    if is_imperial():
        return force_n(v_kn * 1000.0)
    return f"{num(v_kn)} kN"


def _force_per_len_str(v_n_m: float) -> str:
    """线荷载（N/m）→ 带单位文本（公制 kN/m / 英制 lbf/ft）。"""
    if is_imperial():
        return f"{num(v_n_m * LBF_PER_N / FT_PER_M)} lbf/ft"
    return f"{num(v_n_m / 1000.0)} kN/m"


def _stress_disp(v_mpa: float) -> Tuple[float, str]:
    """应力（MPa）→ 显示值与单位（公制 MPa / 英制 psi）。"""
    if is_imperial():
        return round(v_mpa * PSI_PER_MPA, 0), "psi"
    return round(v_mpa, 3), "MPa"


# ====================================================================== 主流程
def run_flow(model: Dict[str, Any],
             library: Optional[MaterialsLibrary] = None) -> Dict[str, Any]:
    """求解"风把一堵墙吹倒"问题，错误抛 ValueError（中文消息）。"""
    import time as _time
    t0 = _time.perf_counter()
    out = _solve(model, library)
    out["solve_ms"] = round((_time.perf_counter() - t0) * 1000.0, 1)
    return out


def _solve(model: Dict[str, Any], library: Optional[MaterialsLibrary]) -> Dict[str, Any]:
    if not isinstance(model, dict):
        raise ValueError("流体模型必须是 JSON 对象")

    kind = str(model.get("kind") or model.get("scenario") or "wind_wall").strip().lower()
    if kind not in ("wind_wall", "wall", "wind"):
        raise ValueError(f"暂不支持的流体场景「{kind}」（当前支持：wind_wall 风荷载墙体）")

    wall = model.get("wall") or {}
    if not isinstance(wall, dict):
        raise ValueError("wall 必须是对象（含 height_m / length_m / thickness_m）")

    def _num(src: Dict[str, Any], *keys: str, default: Optional[float] = None) -> Optional[float]:
        for key in keys:
            if key in src and src[key] is not None:
                try:
                    return float(src[key])
                except (TypeError, ValueError):
                    raise ValueError(f"参数 {key} 必须是数字")
        return default

    h = _num(wall, "height_m", "height", default=_num(model, "height_m"))
    t = _num(wall, "thickness_m", "thickness", default=_num(model, "thickness_m"))
    length = _num(wall, "length_m", "length", "width_m", default=_num(model, "length_m"))
    if h is None or t is None or length is None:
        raise ValueError("缺少墙体尺寸：需要 height_m（墙高）、thickness_m（墙厚）、length_m（墙长）")
    if not (0.2 <= h <= 30.0):
        raise ValueError(f"墙高 h={h:g} m 超出范围（0.2~30 m）")
    if not (0.03 <= t <= 3.0):
        raise ValueError(f"墙厚 t={t:g} m 超出范围（0.03~3 m），请检查是否填成了毫米")
    if not (0.2 <= length <= 200.0):
        raise ValueError(f"墙长 L={length:g} m 超出范围（0.2~200 m）")

    # ---- 风速（给出风速优先，其次蒲福风级） ----
    speed = _num(model, "wind_speed_m_s", "speed_m_s", "wind_speed")
    level = model.get("beaufort")
    if speed is None:
        if level is None:
            raise ValueError("缺少风速：请给 wind_speed_m_s（m/s）或 beaufort（几级风）")
        try:
            level = int(level)
        except (TypeError, ValueError):
            raise ValueError("beaufort 风级必须是 0~17 的整数")
        speed = speed_from_beaufort(level)
    if not (0.5 <= speed <= 80.0):
        raise ValueError(f"风速 {speed:g} m/s 超出范围（0.5~80 m/s）")
    level = beaufort_from_speed(speed)

    # ---- 材料 ----
    name = str(model.get("material") or "").strip()
    if not name:
        raise ValueError("缺少墙体材料：请说明是砖砌体、混凝土还是其他材料")
    lib = library if library is not None else load_library()
    rec = lib.find_material(name)
    if rec is None:
        avail = "、".join(r["name"] for r in lib.all_materials())
        raise ValueError(f"材料库中没有「{name}」，可选材料：{avail}")
    density = _num(model, "density_kg_m3")
    if density is None:
        density = float(rec.get("density") or 1800.0)
    strength = float(rec.get("strength") or 1.0)
    mat_name = str(rec.get("name") or name)

    friction = _num(model, "friction_coeff")
    if friction is None:
        friction = 0.6
    if not (0.1 <= friction <= 1.5):
        raise ValueError("摩擦系数 friction_coeff 应在 0.1~1.5 之间")

    mesh_level = str(model.get("mesh_level") or "medium")

    # ---- 流体有限元 ----
    t_start = time.perf_counter()
    mesh = _build_domain_mesh(h, t, mesh_level)
    basis = Basis(mesh, ElementTriP1())
    phi, vx, vy = _solve_potential(mesh, speed)
    p = mesh.p
    x_in, x_out = float(p[0].min()), float(p[0].max())
    y_top = float(p[1].max())
    vm = np.hypot(vx, vy)
    t_solved = time.perf_counter()

    # 压力：Bernoulli（gauge，远场 0）
    pressure = 0.5 * AIR_DENSITY * (speed ** 2 - vm ** 2)
    q = 0.5 * AIR_DENSITY * speed ** 2
    # 锐角墙顶处势流速度存在奇异性，云图按 ±1 倍动压限幅，避免误导
    display_pressure = np.clip(pressure, -q, q)

    # 迎风面（x≈0）中部节点：驻点压力校核（避开墙脚与墙顶拐角）
    face = np.where((np.abs(p[0]) <= 1e-7)
                    & (p[1] > 0.15 * h) & (p[1] < 0.85 * h))[0]
    stag = float(pressure[face].max()) if face.size else 0.0
    min_p = float(display_pressure.min())
    # 锐角拐点处势流速度理论无穷大，P1 网格给出的局部峰值无物理意义：
    # 统计最大风速比时排除墙四个拐角 0.12h 范围内的节点
    corners = [(0.0, 0.0), (t, 0.0), (0.0, h), (t, h)]
    near = np.zeros(p.shape[1], dtype=bool)
    for cx, cy in corners:
        near |= np.hypot(p[0] - cx, p[1] - cy) <= 0.2 * h
    max_speed_ratio = float(vm[~near].max() / speed) if np.any(~near) else 1.0

    # 流线
    field = _VelocityField(mesh, vx, vy)
    step = (x_out - x_in) / 150.0
    streamlines = _trace_streamlines(
        field, x_in + 0.01 * (x_out - x_in), x_out - 0.01 * (x_out - x_in), y_top, step
    )
    t_done = time.perf_counter()

    # ---- 结构校核 ----
    weight = density * GRAVITY * h * t * length          # N
    q_net = CP_NET * q                                    # Pa（净风压）
    force = q_net * h * length                            # N（总风荷载）
    force_per_m = q_net * h                               # N/m

    m_overturn = force * h / 2.0
    m_resist = weight * t / 2.0
    sf_overturn = m_resist / m_overturn if m_overturn > 0 else 999.0

    sf_slide = friction * weight / force if force > 0 else 999.0

    sigma_bend = 3.0 * q_net * h ** 2 / t ** 2            # Pa
    sigma_bend_mpa = sigma_bend / 1e6
    tension_limit = _tension_limit(mat_name, strength)
    sf_bend = tension_limit / sigma_bend_mpa if sigma_bend_mpa > 0 else 999.0

    if is_en():
        ov_val, ov_unit = _moment_disp(m_overturn)
        ov_lim, _ = _moment_disp(m_resist)
        sl_val, sl_unit = _force_disp(force / 1000.0)
        sl_lim, _ = _force_disp(friction * weight / 1000.0)
        b_val, b_unit = _stress_disp(sigma_bend_mpa)
        b_lim, _ = _stress_disp(tension_limit)
        checks = [
            {"name": "Overturning", "value": ov_val, "limit": ov_lim,
             "unit": ov_unit, "safety_factor": round(sf_overturn, 2),
             "pass": sf_overturn >= SAFETY_CAUTION},
            {"name": "Sliding", "value": sl_val, "limit": sl_lim,
             "unit": sl_unit, "safety_factor": round(sf_slide, 2),
             "pass": sf_slide >= SAFETY_CAUTION},
            {"name": "Root bending", "value": b_val, "limit": b_lim,
             "unit": b_unit, "safety_factor": round(sf_bend, 2),
             "pass": sf_bend >= SAFETY_CAUTION},
        ]
    else:
        checks = [
            {"name": "抗倾覆", "value": round(m_overturn, 1), "limit": round(m_resist, 1),
             "unit": "N·m", "safety_factor": round(sf_overturn, 2),
             "pass": sf_overturn >= SAFETY_CAUTION},
            {"name": "抗滑移", "value": round(force / 1000.0, 2), "limit": round(friction * weight / 1000.0, 2),
             "unit": "kN", "safety_factor": round(sf_slide, 2),
             "pass": sf_slide >= SAFETY_CAUTION},
            {"name": "墙根抗弯", "value": round(sigma_bend_mpa, 3), "limit": round(tension_limit, 3),
             "unit": "MPa", "safety_factor": round(sf_bend, 2),
             "pass": sf_bend >= SAFETY_CAUTION},
        ]
    sf_min = min(sf_overturn, sf_slide, sf_bend)
    verdict = _verdict_from_sf(sf_min)

    # 临界风速：各校核式反解 q_crit（Pa）
    crit = []
    crit.append(("overturning " if is_en() else "整体倾覆",
                 math.sqrt(2.0 * density * GRAVITY * t ** 2
                           / (CP_NET * h) / AIR_DENSITY)))
    crit.append(("base sliding " if is_en() else "沿基础滑移",
                 math.sqrt(2.0 * friction * density * GRAVITY * t
                           / CP_NET / AIR_DENSITY)))
    crit.append(("root cracking " if is_en() else "墙根开裂",
                 math.sqrt(2.0 * tension_limit * 1e6 * t ** 2
                           / (3.0 * CP_NET * h ** 2) / AIR_DENSITY)))
    crit.sort(key=lambda item: item[1])
    crit_name, crit_speed = crit[0]
    crit_level = beaufort_from_speed(crit_speed)

    if sf_overturn <= sf_slide and sf_overturn <= sf_bend:
        weak_part = "the wall base (windward toe)" if is_en() else "墙体底部（迎风侧墙脚）"
        failure = ("overturning: the wind-pressure resultant acts at mid-height and tips "
                   "the wall over about its windward toe" if is_en()
                   else "整体倾覆：风压合力作用在墙高中部，墙体绕迎风侧墙脚翻转倒地")
    elif sf_bend <= sf_slide:
        weak_part = ("the wall root (horizontal joints on the leeward face)" if is_en()
                     else "墙体根部（背风面水平灰缝）")
        failure = ("root cracking: the leeward joints crack first under tension, the crack "
                   "grows upward from the wall base, and the whole wall may collapse" if is_en()
                   else "根部开裂：背风面灰缝受拉先裂，裂缝自墙脚向上发展，严重时整片墙倒伏")
    else:
        weak_part = "the wall-foundation interface" if is_en() else "墙体与基础接触面"
        failure = ("sliding: the wall is pushed off the foundation surface" if is_en()
                   else "整体滑移：墙体沿基础顶面被推开")

    if is_en():
        failure_modes = [
            f"Mode 1 (governing): {failure}; governing safety factor {num(sf_min)}",
            f"Mode 2: bending tension at the wall root {stress_mpa(sigma_bend_mpa)} "
            f"against the representative masonry tensile strength {stress_mpa(tension_limit)} "
            f"(safety factor {num(sf_bend)})",
            f"Mode 3: sliding safety factor {num(sf_slide)} (friction coefficient {num(friction)})",
        ]
    else:
        failure_modes = [
            f"形式一（控制）：{failure}；控制工况下安全系数 {sf_min:.2f}",
            f"形式二：墙根受弯拉应力 {sigma_bend_mpa:.3f} MPa，砌体沿通缝弯曲抗拉代表值 "
            f"{tension_limit:.2f} MPa（安全系数 {sf_bend:.2f}）",
            f"形式三：抗滑移安全系数 {sf_slide:.2f}（摩擦系数 {friction:.2f}）",
        ]

    # ---- 加强建议（量化）----
    need = SAFETY_SAFE / max(sf_min, 1e-6)
    advice: List[str] = []
    if is_en():
        if sf_min < SAFETY_SAFE:
            if sf_bend < sf_overturn:
                t_req = t * math.sqrt(need)
                advice.append(
                    f"Thicken the wall: increase the thickness from {length_mm(t * 1000)} to "
                    f"{length_mm(math.ceil(t_req * 1000 / 10) * 10)} (about {num(need)}×), "
                    f"raising the bending safety factor above 2.0"
                )
            else:
                t_req = t * math.sqrt(need)
                advice.append(
                    f"Thicken the wall or reduce its height: increase the thickness from "
                    f"{length_mm(t * 1000)} to {length_mm(math.ceil(t_req * 1000 / 10) * 10)}, "
                    f"or lower the wall below {length_m(h / need)}, so the overturning "
                    f"safety factor reaches 2.0"
                )
            advice.append(
                f"Add brick pilasters {dim_mm(240)}×{dim_mm(240)} {length_unit()} "
                f"(or reinforced concrete columns) every {length_m(2)} to {length_m(3)} of wall, "
                f"turning the free-standing wall into laterally supported panels"
            )
            advice.append("Provide a coping at the wall top and tie the wall base securely to the "
                          "foundation; add temporary diagonal braces on both sides when a gale is "
                          "forecast")
            advice.append("If the wall already has cracks or weathered mortar, its tensile strength "
                          "drops sharply — treat it as a hazardous wall")
        else:
            advice.append("The safety factor is adequate at the current wind level; before typhoon "
                          "season still check the coping and the base tie-downs")
    elif sf_min < SAFETY_SAFE:
        if sf_bend < sf_overturn:
            t_req = t * math.sqrt(need)
            advice.append(
                f"加厚墙体：墙厚由 {t * 1000:.0f} mm 增至 {math.ceil(t_req * 1000 / 10) * 10:.0f} mm"
                f"（约 {need:.2f} 倍），抗弯安全系数可提高到 2.0 以上"
            )
        else:
            t_req = t * math.sqrt(need)
            advice.append(
                f"加厚或降低墙高：墙厚由 {t * 1000:.0f} mm 增至 {math.ceil(t_req * 1000 / 10) * 10:.0f} mm，"
                f"或墙高降到 {h / need:.2f} m 以下，抗倾覆安全系数可达 2.0"
            )
        advice.append("每隔 2~3 m 加设 240×240 mm 砖砌扶壁柱（或钢筋混凝土构造柱），把独立墙变成有侧向支点的墙段")
        advice.append("墙顶做压顶、墙脚与基础可靠拉结；大风预警时可在墙两侧临时加设斜撑")
        advice.append("若墙体本身已有裂缝或砂浆风化，砌体抗拉强度会大幅下降，应按危险墙体处理")
    else:
        advice.append("当前风级下安全系数达标；台风季仍建议检查墙顶压顶与墙脚拉结是否牢固")

    # ---- 通俗叙述 ----
    if is_en():
        narrative = [
            f"Model: the problem is simplified to a free-standing {mat_name} wall "
            f"{length_m(h)} high, {length_mm(t * 1000)} thick and {length_m(length)} long, "
            f"with wind blowing square onto its face; a 2D potential-flow FE model "
            f"(P1 triangles, {mesh.nelements} elements) computes the velocity and pressure "
            f"distribution near the wall.",
            f"Wind: {beaufort_label(level)} corresponds to about {speed_ms(speed)}, "
            f"dynamic pressure q=½ρU²={pressure_pa(q)}; with the code net shape coefficient "
            f"{num(CP_NET)}, the mean wall pressure is {pressure_pa(q_net)} and the total wind "
            f"load is {_force_kn_str(force / 1000.0)} "
            f"({_force_per_len_str(force_per_m)} along the wall).",
            f"Flow field: stagnation pressure on the windward face is about "
            f"{pressure_pa(stag)} (theory ½ρU²={pressure_pa(q)}); flow accelerating over the "
            f"wall top reaches {num(max_speed_ratio)}× the approach speed, suction of about "
            f"{pressure_pa(abs(min_p))} appears on the leeward side of the top, and the "
            f"streamlines clearly contract above the wall.",
            f"Stability: self-weight {_force_kn_str(weight / 1000.0)}, overturning safety factor "
            f"{num(sf_overturn)}, sliding {num(sf_slide)}, root bending {num(sf_bend)}; "
            f"overall verdict \"{_VERDICT_EN.get(verdict, verdict)}\".",
            f"The weakest part is {weak_part}, and the main failure mode is {failure}.",
            f"Critical wind: above about {speed_ms(crit_speed)} (~{beaufort_label(crit_level)}), "
            f"the {crit_name}safety factor falls below 1.0.",
        ]
        narrative.append(
            "The fluid solution is inviscid potential flow and cannot model leeward separation "
            "and eddies (real wind pressure is less favourable than potential flow), so the "
            "structural wind load uses code shape coefficients; the potential-flow results only "
            "show the velocity/pressure distribution and streamline patterns.")
        narrative.append(
            "These results rest on representative material values and simplified assumptions and "
            "are for everyday reference only; consult a professional engineer for anything "
            "involving personal or structural safety.")
        assumptions = [
            "Steady, incompressible, inviscid and irrotational potential flow; leeward separation "
            "and eddies are ignored",
            "Wall pressure follows a uniform net shape coefficient of 1.2 "
            "(windward +0.8, leeward −0.4)",
            "The wall is treated as a free-standing cantilever checked for overturning and sliding "
            "under its own weight; foundation depth and restraint from adjoining walls are ignored",
            "Coping, constructional columns and scatter in mortar strength are ignored",
        ]
    else:
        narrative = [
            f"建模：把问题简化为一堵 {h:g} m 高、{t * 1000:.0f} mm 厚、{length:g} m 长的"
            f"{mat_name}独立墙，风从墙面正前方吹来；用二维势流有限元（P1 三角单元，"
            f"{mesh.nelements} 个单元）计算墙面附近的流速与压力分布。",
            f"风：{beaufort_label(level)}对应风速约 {speed:.1f} m/s，动压 q=½ρU²={q:.0f} Pa；"
            f"按规范取净体型系数 {CP_NET}，墙面平均风压 {q_net:.0f} Pa，"
            f"总风荷载 {force / 1000:.2f} kN（每米墙长 {force_per_m / 1000:.2f} kN/m）。",
            f"流场：迎风面驻点压力约 {stag:.0f} Pa（理论 ½ρU²={q:.0f} Pa）；"
            f"气流翻过墙顶时加速到来流的 {max_speed_ratio:.1f} 倍，墙顶背风侧出现负压"
            f"（吸力）约 {min_p:.0f} Pa，流线在墙顶明显收窄。",
            f"稳定性：自重 {weight / 1000:.2f} kN，抗倾覆安全系数 {sf_overturn:.2f}、"
            f"抗滑移 {sf_slide:.2f}、墙根抗弯 {sf_bend:.2f}，综合判定「{verdict}」。",
            f"最薄弱部位是{weak_part}，主要破坏形式为{failure}。",
            f"临界风级：风速超过约 {crit_speed:.1f} m/s（约 {crit_level} 级风）时"
            f"{crit_name}安全系数将降到 1.0 以下。",
        ]
        narrative.append("流体计算为无粘势流，不能模拟背风侧漩涡与分离（真实风压会比势流更不利），"
                         "因此结构风荷载按规范体型系数取值，势流结果仅用于看流速/压力分布与流线走向。")
        narrative.append("以上基于材料代表值与简化假定，结果仅供生活参考，涉及人身或结构安全请咨询专业工程师。")

        assumptions = [
            "风为定常、不可压、无粘、无旋的势流；忽略背风侧分离与漩涡",
            "墙面风压按净体型系数 1.2 均匀分布（迎风 +0.8、背风 -0.4）",
            "墙体视为独立悬臂墙，按自重抗倾覆与抗滑移校核，不计基础埋深与相邻墙体约束",
            "忽略墙顶压顶、构造柱与砂浆强度的离散性",
        ]

    # ---- 渲染数据 ----
    display_vals = np.round(display_pressure, 1)
    render = {
        "field": "pressure",
        "unit": "Pa",
        "nodes": [[round(float(x), 4), round(float(y), 4)]
                  for x, y in zip(p[0], p[1])],
        "tris": [[int(i) for i in tri] for tri in mesh.t.T],
        "values": [float(v) for v in display_vals],
        "value_location": "node",
        "streamlines": streamlines,
        "wall": {"x0": 0.0, "x1": float(t), "y1": float(h)},
        "bounds": [round(float(p[0].min()), 4), 0.0,
                   round(float(p[0].max()), 4), round(y_top, 4)],
        "speed_max": round(float(vm.max()), 2),
    }

    return {
        "kind": "flow",
        "ok": True,
        "model_name": str(model.get("name") or
                          (f"{mat_name} wall wind check" if is_en() else f"{mat_name}墙抗风")),
        "scenario": "wind_wall",
        "material": mat_name,
        "density_kg_m3": round(density, 1),
        "wall": {
            "height_m": round(h, 3),
            "length_m": round(length, 3),
            "thickness_m": round(t, 3),
            "area_m2": round(h * length, 2),
            "weight_kN": round(weight / 1000.0, 2),
        },
        "wind_speed_m_s": round(speed, 1),
        "beaufort": level,
        "beaufort_label": beaufort_label(level),
        "q_Pa": round(q, 1),
        "cp_net": CP_NET,
        "pressure_Pa": round(q_net, 1),
        "force_N": round(force, 0),
        "force_per_m": round(force_per_m, 0),
        "overturn_sf": round(sf_overturn, 2),
        "slide_sf": round(sf_slide, 2),
        "bend_MPa": round(sigma_bend_mpa, 3),
        "bend_limit_MPa": round(tension_limit, 3),
        "bend_sf": round(sf_bend, 2),
        "safety_factor": round(sf_min, 2),
        "verdict": verdict,
        "critical_speed_m_s": round(crit_speed, 1),
        "critical_beaufort": crit_level,
        "critical_mode": crit_name,
        "stagnation_Pa": round(stag, 1),
        "min_pressure_Pa": round(min_p, 1),
        "max_speed_ratio": round(max_speed_ratio, 2),
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
            "dof": int(basis.N),
        },
    }
