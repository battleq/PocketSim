# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""3D 空间梁框架求解器（P3D-1）。

模型描述：节点（6 DOF：ux, uy, uz, rx, ry, rz）+ 空间梁元（矩形截面 b×h）
        + 支座（固定/铰接/滚轴，6 DOF 约束）+ 载荷（节点力/杆件均布/温度）。
求解：12×12 空间梁元局部刚度阵 + 方向余弦变换，scipy 稀疏直接法。
单位：长度 mm、力 N、力矩 N·mm、分布力 N/mm、应力与模量 MPa。

坐标约定（与项目 P3D 路线一致）：
- 全局 Z 轴竖直向上，重力沿 -Z；水平地面为 XY 平面。
- 局部 x 轴 = 杆件 i→j 方向；局部 z 轴取"尽量竖直"（全局 Z 在垂直于杆轴平面内的投影）；
  局部 y 轴 = z × x（右手系）。竖直杆件（平行全局 Z）退化为局部 z = 全局 Y。
- 截面：width_b 为局部 y 向宽度 b，depth_h 为局部 z 向高度 h。
  Iy = b·h³/12（绕局部 y，抵抗局部 z 向挠曲 = 竖直挠曲，强轴）
  Iz = h·b³/12（绕局部 z，抵抗局部 y 向挠曲 = 水平挠曲，弱轴）
  截面强弱轴只由 b/h 决定：把抗弯高度写在 depth_h 就得到强轴竖直的效果；
  立柱等轴向构件建议 b=h 方形，避免强弱轴朝向歧义。
- 空心截面（P3D-4）：杆件可选 "wall_t": 壁厚（mm，0/缺省 = 实心）。
  A = b·h − b_i·h_i，Iy/Iz 按外形减内腔，扭转常数用薄壁闭口（Bredt）公式
  J = 2t·(b−t)²·(h−t)²/((b−t)+(h−t))，扭转剪应力 τ = T/(2·A_m·t)；
  实心矩形仍用 St.Venant 近似 J 与 τ = T·max(b,h)/(2J)。
- 型材（材料库给 A/I/外形/壁厚，如欧标铝型材）：A、I 取库值，J 按等外形薄壁闭口估算，
  弯曲应力用库截面模量，避免用户写的 b/h 与型材实际截面不一致造成偏差。

零回归策略：节点全部 z=0 且无面外载荷（fz/mx/my/qx/qz）时，自动转交 2D 求解器
（frame.run_frame），现有 2D 算例行为完全不变。可用 model["dim"]=3 强制走 3D。

假设：线弹性小变形、忽略构件自重与剪切变形（欧拉-伯努利）、
     铰接计算长度系数 K=1、扭转按矩形截面近似公式、温度按杆件均匀变化。
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
from .stability import DOF_LABELS_3D, check_mechanism

# 规模上限（与 2D 一致，防 AI 生成失控模型拖垮手机）
MAX_NODES = 60
MAX_MEMBERS = 80
SAMPLE_N = 33          # 每杆件内力/挠度采样点数
SAMPLE_N_UI = 9        # UI 变形折线采样点数

# 6 DOF 约束位：[ux, uy, uz, rx, ry, rz]
SUPPORT_DOF_3D = {
    "fixed":  (True, True, True, True, True, True),
    "pinned": (True, True, True, False, False, False),
    "roller": (False, False, True, False, False, False),   # 竖向滚轴（约束 uz）
    "roller_x": (True, False, False, False, False, False),
    "roller_y": (False, True, False, False, False, False),
}

SAFETY_SAFE = 2.0
SAFETY_CAUTION = 1.2
BUCKLING_WARN = 0.5
BUCKLING_FAIL = 1.0


# ====================================================================== 工具
def _err(msg: str) -> None:
    raise ValueError(msg)


def _is_planar_2d(model: Dict[str, Any]) -> bool:
    """是否可安全退化为 2D：全部节点 z=0 且无面外载荷/3D 专用支座。"""
    for n in model.get("nodes") or []:
        try:
            if abs(float(n.get("z", 0.0))) > 1e-9:
                return False
        except (TypeError, ValueError):
            return False
    for ld in model.get("loads") or []:
        for key in ("fz", "mx", "my", "qx", "qz", "mz"):
            try:
                if abs(float(ld.get(key, 0.0))) > 1e-9:
                    return False
            except (TypeError, ValueError):
                return False
    for s in model.get("supports") or []:
        if str(s.get("type", "")).strip().lower() in ("roller_x", "roller_y"):
            return False
    return True


def _rect_torsion_j(b: float, h: float) -> float:
    """矩形截面扭转常数 J（St.Venant 近似）。

    a = max(b,h), c = min(b,h)：J = a·c³·(1/3 − 0.21·(c/a)·(1 − (c/a)⁴/12))
    """
    a = max(b, h)
    c = min(b, h)
    r = c / a
    return a * c ** 3 * (1.0 / 3.0 - 0.21 * r * (1.0 - r ** 4 / 12.0))


def _hollow_torsion_j(b: float, h: float, t: float) -> float:
    """薄壁闭口矩形截面扭转常数 J（Bredt 公式）。

    J = 4·A_m²/∮(ds/t)，等壁厚时为 2t·(b−t)²·(h−t)²/((b−t)+(h−t))，
    其中 A_m=(b−t)(h−t) 为中线（中面）包围面积。
    """
    bm, hm = b - t, h - t
    return 2.0 * t * bm ** 2 * hm ** 2 / (bm + hm)


def _section_text(mem: Dict[str, Any]) -> str:
    """结果页的截面描述（矩形/矩形管/圆管/实心圆）。"""
    if is_en():
        u = length_unit()
        if mem.get("shape") == "circle":
            if mem["wall_t"] > 0:
                return f"⌀{dim_mm(mem['b'])}×{dim_mm(mem['wall_t'])} {u} (tube)"
            return f"⌀{dim_mm(mem['b'])} {u} (solid round)"
        if mem["hollow"] and mem["wall_t"] > 0:
            return (f"{dim_mm(mem['b'])}×{dim_mm(mem['h'])}×{dim_mm(mem['wall_t'])}"
                    f" {u} (hollow)")
        return f"{dim_mm(mem['b'])}×{dim_mm(mem['h'])} {u}"
    if mem.get("shape") == "circle":
        if mem["wall_t"] > 0:
            return f"⌀{mem['b']:g}×{mem['wall_t']:g} mm（圆管）"
        return f"⌀{mem['b']:g} mm（实心圆）"
    if mem["hollow"] and mem["wall_t"] > 0:
        return f"{mem['b']:g}×{mem['h']:g}×{mem['wall_t']:g} mm（空心）"
    return f"{mem['b']:g}×{mem['h']:g} mm"


def _circle_section(D: float, t: float) -> Dict[str, Any]:
    """圆形/圆环截面特性：I = π(D⁴−d⁴)/64，J = 2I（闭口圆管精确成立）。"""
    d = max(D - 2.0 * t, 0.0) if t > 0 else 0.0
    I = math.pi * (D ** 4 - d ** 4) / 64.0
    return {
        "A": math.pi * (D ** 2 - d ** 2) / 4.0,
        "Iy": I,
        "Iz": I,
        "J": 2.0 * I,
        "Wy": I / (D / 2.0),
        "Wz": I / (D / 2.0),
        "A_m": math.pi * (D - t) ** 2 / 4.0 if t > 0 else None,
        "wall_t": t,
        "hollow": t > 0,
    }


def _section_props(b: float, h: float, t: float) -> Dict[str, Any]:
    """矩形截面特性：实心（t=0）或等壁厚空心矩形（t>0）。

    返回 A、Iy（强轴，绕局部 y）、Iz（弱轴，绕局部 z）、J、Wy、Wz，
    空心时另含中线面积 A_m 与壁厚 t，供 Bredt 扭转剪应力使用。
    """
    if t <= 0.0:
        return {
            "A": b * h,
            "Iy": b * h ** 3 / 12.0,
            "Iz": h * b ** 3 / 12.0,
            "J": _rect_torsion_j(b, h),
            "Wy": b * h ** 2 / 6.0,
            "Wz": h * b ** 2 / 6.0,
            "A_m": None,
            "wall_t": 0.0,
            "hollow": False,
        }
    bi, hi = b - 2.0 * t, h - 2.0 * t
    iy = (b * h ** 3 - bi * hi ** 3) / 12.0
    iz = (h * b ** 3 - hi * bi ** 3) / 12.0
    return {
        "A": b * h - bi * hi,
        "Iy": iy,
        "Iz": iz,
        "J": _hollow_torsion_j(b, h, t),
        "Wy": iy / (h / 2.0),
        "Wz": iz / (b / 2.0),
        "A_m": (b - t) * (h - t),
        "wall_t": t,
        "hollow": True,
    }


def _square_hollow_torsion_j(a: float, t: float) -> float:
    """方形薄壁闭口截面扭转常数 J = t·(a−t)³（Bredt 公式的方形特例）。"""
    return t * (a - t) ** 3


# 杆件开孔：形状别名与孔边应力集中系数（净截面名义应力放大）
_HOLE_SHAPE_ALIASES = {
    "circle": "circle", "round": "circle", "circular": "circle",
    "hole": "circle", "drill": "circle", "drilled": "circle",
    "rect": "rect", "rectangle": "rect", "slot": "rect", "square": "rect",
}
HOLE_KT = {"circle": 2.5, "rect": 3.0}


def parse_member_holes(
    mid: Any, raw_holes: Any, b: float, h: float, L: float, t: float
) -> List[Dict[str, Any]]:
    """解析并校验杆件开孔（钻孔/矩形开洞）。

    孔轴沿截面局部 y（宽度 b 方向），即从杆件侧面贯穿的孔；
    d_mm 为孔在局部 z（截面高度 h）方向的孔径/洞高，at_mm 为孔心距节点 i 的
    轴向距离。开孔处按净截面 + 孔边应力集中系数做强度校核（刚度仍按全截面，
    偏安全地忽略开孔对整体刚度的削弱）。
    """
    if not raw_holes:
        return []
    if not isinstance(raw_holes, list):
        _err(f"杆件 {mid} 的 holes 必须是列表")
    holes: List[Dict[str, Any]] = []
    for k, hd in enumerate(raw_holes):
        if not isinstance(hd, dict):
            _err(f"杆件 {mid} 的第 {k + 1} 个开孔必须是对象")
        shape_raw = str(hd.get("shape", "circle")).strip().lower()
        shape = _HOLE_SHAPE_ALIASES.get(shape_raw)
        if shape is None:
            _err(f"杆件 {mid} 的开孔形状「{hd.get('shape')}」不支持（可用 circle / rect）")
        try:
            at = float(hd.get("at_mm", hd.get("at", 0.0)))
            d = float(hd.get("d_mm", hd.get("diameter_mm", 0.0)))
        except (TypeError, ValueError):
            _err(f"杆件 {mid} 的开孔 at_mm / d_mm 必须是数字（毫米）")
        if d <= 0.0:
            _err(f"杆件 {mid} 的开孔尺寸 d_mm 必须为正数")
        if not (-1e-9 <= at <= L + 1e-9):
            _err(f"杆件 {mid} 的开孔位置 at_mm={at:g} 超出杆长 0~{L:g} mm")
        if d >= h:
            _err(f"杆件 {mid} 的开孔尺寸 {d:g} mm 不小于截面高度 {h:g} mm")
        if shape == "circle" and d >= b:
            _err(f"杆件 {mid} 圆孔直径 {d:g} mm 不小于截面宽度 {b:g} mm")
        if not bool(hd.get("through", True)):
            _err(f"杆件 {mid} 暂仅支持贯穿孔（through=true）")
        if d > L:
            _err(f"杆件 {mid} 的开孔尺寸 {d:g} mm 大于杆长 {L:g} mm")
        # 孔心落在杆端附近（含正好落在节点上）时，把孔心微调到孔洞完全落在杆件内，
        # 避免 AI 把跨中孔写在分段的端点造成建模失败（几何偏差仅 d/2 以内）
        at = min(max(at, d / 2.0), L - d / 2.0)
        holes.append({"at_mm": at, "shape": shape, "d_mm": d, "through": True})
    return holes


def hole_net_section(
    b: float, h: float, A: float, Iy: float, Iz: float, J: float,
    holes: List[Dict[str, Any]], t: float = 0.0,
) -> Dict[str, Any]:
    """最不利开孔处的净截面特性与孔边应力集中系数。

    孔轴沿局部 y（宽度 b 方向贯穿）：
    - 实心矩形：切面内被挖去 b×d 的矩形（孔居中于截面形心）：A 减 b·d，
      绕 y 惯性矩减 b·d³/12，绕 z 减 d·b³/12，扭转常数扣除被挖矩形的
      St.Venant 扭转常数。
    - 空心矩形（t>0）：孔贯穿两侧壁，切面被挖去 2 块 t×d 的壁条
      （按孔居中、未切到上下翼缘的常见情形），A 减 2·t·d，
      绕 y 惯性矩减 2·t·d³/12，绕 z 惯性矩按两侧壁到形心距离扣除，
      扭转常数按面积比折减。
    """
    if not holes:
        return {"has_hole": False, "kt": 1.0}
    d = max(float(x["d_mm"]) for x in holes)
    # 多个孔取孔径最大者对应的应力集中系数（同尺寸时取较大 Kt）
    shape = max(
        (x["shape"] for x in holes if float(x["d_mm"]) == d),
        key=lambda s: HOLE_KT[s],
    )
    if t > 0.0:
        a_loss = 2.0 * t * d
        y_arm = (b - t) / 2.0
        A_net = max(A - a_loss, 1e-9)
        Iy_net = max(Iy - 2.0 * t * d ** 3 / 12.0, 1e-9)
        Iz_net = max(
            Iz - 2.0 * (d * t ** 3 / 12.0 + d * t * y_arm ** 2), 1e-9
        )
        J_net = max(J * (A_net / A), 1e-9) if A > 0 else J
    else:
        A_net = max(A - b * d, 1e-9)
        Iy_net = max(Iy - b * d ** 3 / 12.0, 1e-9)
        Iz_net = max(Iz - d * b ** 3 / 12.0, 1e-9)
        J_net = max(J - _rect_torsion_j(b, d), 1e-9)
    return {
        "has_hole": True,
        "kt": float(HOLE_KT[shape]),
        "A_net": A_net,
        "Iy_net": Iy_net,
        "Iz_net": Iz_net,
        "J_net": J_net,
    }



def _axes3d(dx: float, dy: float, dz: float, L: float) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """返回局部三轴 (e1, e2, e3)：e1 杆轴，e3 竖向优先，e2 = e3 × e1。"""
    e1 = np.array([dx, dy, dz], dtype=float) / L
    gz = np.array([0.0, 0.0, 1.0])
    ref = np.array([0.0, 1.0, 0.0]) if abs(float(e1 @ gz)) > 0.999 else gz
    proj = ref - float(ref @ e1) * e1
    n = float(np.linalg.norm(proj))
    if n < 1e-9:                       # 极端退化，换 X 轴为参考
        ref = np.array([1.0, 0.0, 0.0])
        proj = ref - float(ref @ e1) * e1
        n = float(np.linalg.norm(proj))
    e3 = proj / n
    e2 = np.cross(e3, e1)
    e2 = e2 / float(np.linalg.norm(e2))
    return e1, e2, e3


def _transform3d(e1: np.ndarray, e2: np.ndarray, e3: np.ndarray) -> np.ndarray:
    """global→local 位移变换矩阵 T（12×12，四个 3×3 旋转块）。"""
    R = np.vstack([e1, e2, e3])
    T = np.zeros((12, 12))
    for k in range(4):
        T[3 * k:3 * k + 3, 3 * k:3 * k + 3] = R
    return T


def _local_k3d(E: float, G: float, A: float, Iy: float, Iz: float,
               J: float, L: float) -> np.ndarray:
    """空间梁元局部刚度矩阵（12×12）。

    DOF 顺序：[u1,v1,w1,θx1,θy1,θz1, u2,v2,w2,θx2,θy2,θz2]
    """
    k = np.zeros((12, 12))
    # 轴向
    ea = E * A / L
    k[0, 0] = k[6, 6] = ea
    k[0, 6] = k[6, 0] = -ea
    # 扭转
    gj = G * J / L
    k[3, 3] = k[9, 9] = gj
    k[3, 9] = k[9, 3] = -gj
    # xy 平面弯曲（v, θz），惯性矩 Iz
    a1 = 12.0 * E * Iz / L ** 3
    a2 = 6.0 * E * Iz / L ** 2
    a3 = 4.0 * E * Iz / L
    a4 = 2.0 * E * Iz / L
    k[1, 1] = k[7, 7] = a1
    k[1, 7] = k[7, 1] = -a1
    k[1, 5] = k[5, 1] = a2
    k[1, 11] = k[11, 1] = a2
    k[5, 7] = k[7, 5] = -a2
    k[7, 11] = k[11, 7] = -a2
    k[5, 5] = k[11, 11] = a3
    k[5, 11] = k[11, 5] = a4
    # xz 平面弯曲（w, θy），惯性矩 Iy
    b1 = 12.0 * E * Iy / L ** 3
    b2 = 6.0 * E * Iy / L ** 2
    b3 = 4.0 * E * Iy / L
    b4 = 2.0 * E * Iy / L
    k[2, 2] = k[8, 8] = b1
    k[2, 8] = k[8, 2] = -b1
    k[2, 4] = k[4, 2] = -b2
    k[2, 10] = k[10, 2] = -b2
    k[4, 8] = k[8, 4] = b2
    k[8, 10] = k[10, 8] = b2
    k[4, 4] = k[10, 10] = b3
    k[4, 10] = k[10, 4] = b4
    return k


def _feq_local3d(q: np.ndarray, L: float) -> np.ndarray:
    """局部均布载荷 q=[qx,qy,qz]（N/mm）的等效节点载荷（12）。"""
    qx, qy, qz = float(q[0]), float(q[1]), float(q[2])
    v = np.zeros(12)
    # 轴向
    v[0] = v[6] = qx * L / 2.0
    # xy 弯曲（v, θz）
    v[1] = v[7] = qy * L / 2.0
    v[5] = qy * L ** 2 / 12.0
    v[11] = -qy * L ** 2 / 12.0
    # xz 弯曲（w, θy）：θy 与 θz 符号相反
    v[2] = v[8] = qz * L / 2.0
    v[4] = -qz * L ** 2 / 12.0
    v[10] = qz * L ** 2 / 12.0
    return v


# ====================================================================== 校验
def _validate(model: Dict[str, Any]) -> Dict[str, Any]:
    """校验并规范化 3D 模型。"""
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

    # 节点
    nodes: List[Dict[str, float]] = []
    ids = {}
    for idx, n in enumerate(raw_nodes):
        nid = n.get("id", idx)
        if nid in ids:
            _err(f"节点 id 重复：{nid}")
        try:
            x = float(n["x"]); y = float(n["y"])
            z = float(n.get("z", 0.0))
        except (KeyError, TypeError, ValueError):
            _err(f"第 {idx + 1} 个节点缺少有效的 x/y 坐标（单位毫米）")
        ids[nid] = idx
        nodes.append({"id": nid, "x": x, "y": y, "z": z})

    # 杆件
    members: List[Dict[str, Any]] = []
    for idx, m in enumerate(raw_members):
        mid = m.get("id", idx)
        try:
            i = ids[m["i"]]; j = ids[m["j"]]
        except KeyError:
            _err(f"杆件 {mid} 引用了不存在的节点（i/j 必须是 nodes 中的 id）")
        dx = nodes[j]["x"] - nodes[i]["x"]
        dy = nodes[j]["y"] - nodes[i]["y"]
        dz = nodes[j]["z"] - nodes[i]["z"]
        L = math.sqrt(dx * dx + dy * dy + dz * dz)
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
                _err(f"杆件 {mid} 缺少截面尺寸 width_b（局部 y 向宽度）/depth_h（局部 z 向高度，毫米）")
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
        sec = _circle_section(b, t) if circle else _section_props(b, h, t)
        holes = parse_member_holes(mid, m.get("holes"), b, h, L, t)
        if circle and holes:
            _err(f"杆件 {mid} 是圆管/圆截面，暂不支持开孔（开孔请改用矩形截面）")
        net = hole_net_section(
            b, h, sec["A"], sec["Iy"], sec["Iz"], sec["J"], holes, t
        )
        members.append({
            "id": mid, "i": i, "j": j, "L": L, "dx": dx, "dy": dy, "dz": dz,
            "b": b, "h": h, "shape": "circle" if circle else "rect", **sec, **net,
            "holes": holes,
            "material": str(mat),
        })

    # 支座（支持 fix 额外约束自由度，如简支梁一端加 rx 约束扭转刚体模态）
    _DOF_INDEX = {"ux": 0, "uy": 1, "uz": 2, "rx": 3, "ry": 4, "rz": 5}
    supports: List[Dict[str, Any]] = []
    for s in raw_supports:
        try:
            ni = ids[s["node"]]
        except KeyError:
            _err(f"支座引用了不存在的节点：{s.get('node')}")
        stype = str(s.get("type", "pinned")).strip().lower()
        if stype not in SUPPORT_DOF_3D:
            _err(f"支座类型 {stype} 不支持（可用：fixed 固定 / pinned 铰接 / "
                 f"roller 竖向滚轴 / roller_x / roller_y）")
        extra: List[int] = []
        for name in (s.get("fix") or []):
            idx = _DOF_INDEX.get(str(name).strip().lower())
            if idx is None:
                _err(f"支座额外约束 fix 含未知自由度「{name}」"
                     f"（可用：ux/uy/uz/rx/ry/rz）")
            extra.append(idx)
        supports.append({"node": ni, "type": stype, "fix": extra})
    if not supports:
        _err("模型缺少支座（supports），结构无法承载")

    # 载荷
    loads: List[Dict[str, Any]] = []
    _LOAD_ALIASES = {
        "point": "force", "concentrated": "force", "f": "force", "node": "force",
        "distributed": "udl", "uniform": "udl", "uniformly_distributed": "udl",
        "w": "udl", "dist": "udl",
        "surface": "area", "panel": "area", "areaload": "area",
        "面荷载": "area", "面载荷": "area",
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
                "fx": float(ld.get("fx", 0.0)), "fy": float(ld.get("fy", 0.0)),
                "fz": float(ld.get("fz", 0.0)),
                "mx": float(ld.get("mx", 0.0)), "my": float(ld.get("my", 0.0)),
                "mz": float(ld.get("mz", 0.0)),
            })
        elif ltype == "udl":
            try:
                mi = next(k for k, mm in enumerate(members) if mm["id"] == ld["member"])
            except (KeyError, StopIteration):
                _err(f"均布载荷引用了不存在的杆件：{ld.get('member')}")
            q = np.array([float(ld.get("qx", 0.0)), float(ld.get("qy", 0.0)),
                          float(ld.get("qz", 0.0))])
            if not np.any(np.abs(q) > 1e-12):
                # 零值均布是无载荷占位（常见于 AI 建模），忽略而不是报错
                continue
            loads.append({"type": "udl", "member": mi, "q": q})
        elif ltype == "area":
            # 面荷载：作用在承托面上的一组杆件（椅面/桌面下方横杆），
            # 按杆件长度占比分配为竖向均布载荷（重力沿 -z），不折算成跨中集中力。
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
            span = sum(float(members[k]["L"]) for k in mis)
            if span <= 0.0:
                _err("面荷载作用的杆件长度全为 0，无法分配")
            q = np.array([0.0, 0.0, total / span])
            for k in mis:
                loads.append({"type": "udl", "member": k, "q": q})
        elif ltype == "thermal":
            try:
                mi = next(k for k, mm in enumerate(members) if mm["id"] == ld["member"])
            except (KeyError, StopIteration):
                _err(f"温度载荷引用了不存在的杆件：{ld.get('member')}")
            dt = float(ld.get("delta_temp", 0.0))
            if dt == 0.0:
                _err("温度载荷 delta_temp 不能为 0（升温为正，降温为负，单位 °C）")
            loads.append({"type": "thermal", "member": mi, "delta_temp": dt})
        else:
            _err(f"载荷类型 {ltype} 不支持（可用：force 节点力 / udl 杆件均布 / area 面荷载 / thermal 温度变化）")
    if not loads:
        _err("模型没有有效载荷（loads 为空或数值全为 0）；水平风载请用节点集中力 fx 分摊到迎风节点")

    # 材料属性回填
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
        m["alpha"] = float(rec.get("thermal_expansion", 0.0))
        m["max_temp"] = float(rec.get("max_service_temp", float("inf")))
        nu = float(rec.get("poisson_ratio", 0.3))
        m["G_MPa"] = m["E_MPa"] / (2.0 * (1.0 + nu))
        # 型材：材料库给出截面特性时覆盖几何计算值
        # （欧标铝型材等 T 型槽空心截面，A/I 与同外形实心矩形差异很大，必须用库值；
        #  工字钢/H 型钢/角钢等开口薄壁型钢强/弱轴不等，给出双轴惯性矩与扭转常数）
        A_lib = rec.get("section_area_mm2")
        Ix_lib = rec.get("inertia_x_mm4")
        Iy_lib = rec.get("inertia_y_mm4")
        if A_lib is not None and Ix_lib is not None and Iy_lib is not None:
            # 开口薄壁型钢：强轴（国标 X-X）→ 局部 y，弱轴（国标 Y-Y）→ 局部 z
            m["A"] = float(A_lib)
            m["Iy"] = float(Ix_lib)
            m["Iz"] = float(Iy_lib)
            Wx_lib = rec.get("section_modulus_x_mm3")
            Wy_lib = rec.get("section_modulus_y_mm3")
            if Wx_lib is not None:
                m["Wy"] = float(Wx_lib)
            if Wy_lib is not None:
                m["Wz"] = float(Wy_lib)
            J_lib = rec.get("torsion_constant_mm4")
            m["J"] = float(J_lib) if J_lib is not None else 2.0 * float(Ix_lib)
            m["A_m"] = None
            m["hollow"] = False
        elif A_lib is not None and rec.get("inertia_mm4") is not None:
            I_lib = rec.get("inertia_mm4")
            outer = rec.get("outer_size_mm")
            wall = rec.get("wall_thickness_mm")
            W_lib = rec.get("section_modulus_mm3")
            m["A"] = float(A_lib)
            m["Iy"] = m["Iz"] = float(I_lib)
            if W_lib is not None:
                m["Wy"] = m["Wz"] = float(W_lib)
            elif outer is not None:
                m["Wy"] = m["Wz"] = float(I_lib) / (float(outer) / 2.0)
            # 否则沿用按用户 b/h 计算的截面模量
            if outer is not None and wall is not None and float(wall) > 0:
                # 等外形薄壁闭口方形截面：J = t·(a−t)³，τ 用 Bredt 公式
                a_, t_ = float(outer), float(wall)
                m["b"] = m["h"] = a_          # 截面文案与真实型材外形一致
                m["J"] = _square_hollow_torsion_j(a_, t_)
                m["A_m"] = (a_ - t_) ** 2
                m["wall_t"] = t_
                m["hollow"] = True
            else:
                # 库数据不足时的旧近似（圆管 J≈2I），τ 走实心矩形公式
                m["J"] = 2.0 * float(I_lib)
                m["A_m"] = None
                m["hollow"] = False

    if missing:
        names = "、".join(sorted(missing))
        avail = "、".join(r["name"] for r in lib.all_materials())
        _err(f"材料库中没有「{names}」。可选材料：{avail}")

    return {"name": str(model.get("name", "空间框架模型")),
            "nodes": nodes, "members": members,
            "supports": supports, "loads": loads}


# ====================================================================== 求解
def run_frame3d(model: Dict[str, Any],
                library: Optional[MaterialsLibrary] = None) -> Dict[str, Any]:
    """求解 3D 空间框架并返回结果 dict（错误抛 ValueError，中文消息）。

    零回归：可退化为平面的模型（全部 z=0 且无面外载荷）自动走 2D 求解器。
    """
    import time
    t0 = time.perf_counter()

    force_3d = str(model.get("dim", "")).strip() in ("3", "3d") if isinstance(model, dict) else False
    if not force_3d and isinstance(model, dict) and _is_planar_2d(model):
        from . import frame as frame_2d
        return frame_2d.run_frame(model, library=library)

    if library is not None:
        model = dict(model)
        model["_library"] = library
    result = _solve3d(model)
    result["solve_ms"] = round((time.perf_counter() - t0) * 1000.0, 1)
    for pl in result.get("polylines", []):
        pl["pts"] = [[round(v, 4) for v in pt] for pt in pl["pts"]]
    return result


def _assemble_free_stiffness(model: Dict[str, Any]
                             ) -> Tuple[np.ndarray, np.ndarray, Dict[str, Any]]:
    """只组装总刚度阵与支座自由掩码，不组装载荷、不求解（供预检与求解共享）。

    组装循环会给每个 member 写入 _dofs/_T/_R/_e1/_k 缓存——_solve3d 的载荷
    组装与内力后处理依赖这些缓存，因此本函数返回的 members（在 m 中）已带缓存。
    返回 (K, free, m)：K 为整体刚度阵，free 为自由自由度布尔掩码，m 为校验后模型。
    """
    m = _validate(model)
    nodes = m["nodes"]; members = m["members"]
    supports = m["supports"]
    nn = len(nodes)
    ndof = 6 * nn
    K = np.zeros((ndof, ndof))
    for mi, mem in enumerate(members):
        L = mem["L"]
        e1, e2, e3 = _axes3d(mem["dx"], mem["dy"], mem["dz"], L)
        T = _transform3d(e1, e2, e3)
        k = _local_k3d(mem["E_MPa"], mem["G_MPa"], mem["A"],
                       mem["Iy"], mem["Iz"], mem["J"], L)
        kg = T.T @ k @ T
        dofs = [6 * mem["i"] + d for d in range(6)] + [6 * mem["j"] + d for d in range(6)]
        K[np.ix_(dofs, dofs)] += kg
        mem["_dofs"] = dofs
        mem["_T"] = T
        mem["_R"] = np.vstack([e1, e2, e3])
        mem["_e1"] = e1
        mem["_k"] = k
    fixed = np.zeros(ndof, dtype=bool)
    for sup in supports:
        base = 6 * sup["node"]
        for k, flag in enumerate(SUPPORT_DOF_3D[sup["type"]]):
            if flag:
                fixed[base + k] = True
        for k in sup.get("fix", []):
            fixed[base + k] = True
    free = ~fixed
    return K, free, m


def _solve3d(model: Dict[str, Any]) -> Dict[str, Any]:
    K, free, m = _assemble_free_stiffness(model)

    nodes = m["nodes"]; members = m["members"]
    supports = m["supports"]; loads = m["loads"]
    nn = len(nodes)

    ndof = 6 * nn
    F = np.zeros(ndof)
    udl_of_member: Dict[int, np.ndarray] = {}
    thermal_of_member: Dict[int, float] = {}

    # ---- 外载向量 ----
    for ld in loads:
        if ld["type"] == "force":
            base = 6 * ld["node"]
            F[base] += ld["fx"]; F[base + 1] += ld["fy"]; F[base + 2] += ld["fz"]
            F[base + 3] += ld["mx"]; F[base + 4] += ld["my"]; F[base + 5] += ld["mz"]
        elif ld["type"] == "thermal":
            mi = ld["member"]; mem = members[mi]
            Nth = mem["E_MPa"] * mem["A"] * mem["alpha"] * ld["delta_temp"]
            feq_l = np.zeros(12)
            feq_l[0] = -Nth; feq_l[6] = Nth
            F[mem["_dofs"]] += mem["_T"].T @ feq_l
            thermal_of_member[mi] = ld["delta_temp"]
        else:
            mi = ld["member"]; mem = members[mi]
            q_l = mem["_R"] @ ld["q"]          # 全局分布力 → 局部分布力（R 为正交阵）
            feq_l = _feq_local3d(q_l, mem["L"])
            F[mem["_dofs"]] += mem["_T"].T @ feq_l
            udl_of_member[mi] = q_l

    # ---- 支座约束 ----（free 掩码已由 _assemble_free_stiffness 计算）
    if not free.any() and not thermal_of_member:
        _err("所有自由度均被约束，结构无法变形")

    # ---- 机构（约束不足）预检：定位到具体节点与方向 ----
    if free.any():
        bad = check_mechanism(K[np.ix_(free, free)], np.flatnonzero(free),
                              nodes, DOF_LABELS_3D, 6)
        if bad:
            _err(bad)

    # ---- 求解 ----
    K_sp = sp.csr_matrix(K[np.ix_(free, free)])
    try:
        u = np.zeros(ndof)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            u[free] = spla.spsolve(K_sp, F[free])
    except Exception as e:  # noqa: BLE001
        _err(f"求解失败（通常是约束不足导致结构不稳定）：{e}")
    if not np.all(np.isfinite(u)):
        _err("求解结果异常（可能是约束不足或结构为机构），请检查支座设置")

    # ---- 节点位移 ----
    node_disp = [{"dx": float(u[6 * i]), "dy": float(u[6 * i + 1]),
                  "dz": float(u[6 * i + 2]), "rx": float(u[6 * i + 3]),
                  "ry": float(u[6 * i + 4]), "rz": float(u[6 * i + 5])}
                 for i in range(nn)]

    # ---- 逐杆内力 / 应力 / 变形 ----
    max_disp = 0.0
    max_disp_desc = ""
    polylines = []
    member_results = []

    for mi, mem in enumerate(members):
        L = mem["L"]
        T = mem["_T"]; R = mem["_R"]; e1 = mem["_e1"]
        k = mem["_k"]
        ul = T @ u[mem["_dofs"]]
        q_l = udl_of_member.get(mi, np.zeros(3))
        qx, qy, qz = float(q_l[0]), float(q_l[1]), float(q_l[2])
        feq_l = _feq_local3d(q_l, L)
        dt = thermal_of_member.get(mi, 0.0)
        if dt != 0.0:
            Nth = mem["E_MPa"] * mem["A"] * mem["alpha"] * dt
            feq_l[0] -= Nth; feq_l[6] += Nth
        f = k @ ul - feq_l                  # 节点作用于杆的端部力（局部）

        EIz = mem["E_MPa"] * mem["Iz"]
        EIy = mem["E_MPa"] * mem["Iy"]
        V0z = f[1]; M0z = f[5]              # xy 平面：剪力/弯矩（绕 z）
        M0y = -f[4]; V0y = f[2]             # xz 平面：弯矩（绕 y）/剪力

        Mz_abs = 0.0; My_abs = 0.0
        N_comp = 0.0
        for kk in range(SAMPLE_N + 1):
            x = L * kk / SAMPLE_N
            Mz = M0z - V0z * x - qy * x * x / 2.0
            My = M0y - V0y * x - qz * x * x / 2.0
            Nx = -f[0] + qx * x
            Mz_abs = max(Mz_abs, abs(Mz))
            My_abs = max(My_abs, abs(My))
            N_comp = min(N_comp, Nx)        # 最大压力（负得最多）

        # 应力（保守线性叠加轴向 + 双轴弯曲；开孔处改用净截面并按 Kt 放大）
        if mem.get("has_hole"):
            A_s = mem["A_net"]
            Wy_s = mem["Iy_net"] / (mem["h"] / 2.0)
            Wz_s = mem["Iz_net"] / (mem["b"] / 2.0)
            J_s = mem["J_net"]
            kt = mem["kt"]
        else:
            A_s = mem["A"]; Wy_s = mem["Wy"]; Wz_s = mem["Wz"]
            J_s = mem["J"]; kt = 1.0
        sigma_a = max(abs(f[0]), abs(f[6])) / A_s
        sigma_by = My_abs / Wy_s
        sigma_bz = Mz_abs / Wz_s
        sigma = (sigma_a + sigma_by + sigma_bz) * kt
        T_tor = max(abs(f[3]), abs(f[9]))
        if mem["hollow"] and mem["A_m"]:
            # 薄壁闭口截面：Bredt 公式 τ = T/(2·A_m·t)
            tau = T_tor / (2.0 * mem["A_m"] * mem["wall_t"])
        else:
            tau = T_tor * max(mem["b"], mem["h"]) / (2.0 * J_s) if J_s > 0 else 0.0
        tau *= kt
        sigma_vm = math.sqrt(sigma * sigma + 3.0 * tau * tau)
        mem["_sigma"] = sigma_vm
        mem["_N_comp"] = -N_comp

        # 变形折线（精确积分弯矩二重积分，含均布载荷特解）
        xs = nodes[mem["i"]]["x"]; ys = nodes[mem["i"]]["y"]; zs = nodes[mem["i"]]["z"]
        seg_pts = []
        seg_defl = 0.0; seg_defl_at = 0.0
        for kk in range(SAMPLE_N + 1):
            xi = kk / SAMPLE_N
            x = L * xi
            u_l = ul[0] + (ul[6] - ul[0]) * xi
            v_l = (ul[1] + ul[5] * x
                   - (M0z * x * x / 2.0 - V0z * x ** 3 / 6.0 - qy * x ** 4 / 24.0) / EIz)
            w_l = (ul[2] - ul[4] * x
                   - (M0y * x * x / 2.0 - V0y * x ** 3 / 6.0 - qz * x ** 4 / 24.0) / EIy)
            dg = R.T @ np.array([u_l, v_l, w_l])   # 局部 → 全局位移
            d = float(np.linalg.norm(dg))
            if d > seg_defl:
                seg_defl = d; seg_defl_at = x
            if kk % (SAMPLE_N // (SAMPLE_N_UI - 1)) == 0 or kk == SAMPLE_N:
                gx = xs + e1[0] * x; gy = ys + e1[1] * x; gz = zs + e1[2] * x
                seg_pts.append([gx, gy, gz, float(dg[0]), float(dg[1]), float(dg[2])])
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
            "N_N": float(-f[0]),
            "My_max_Nmm": float(My_abs),
            "Mz_max_Nmm": float(Mz_abs),
            "torsion_Nmm": float(T_tor),
            "sigma_MPa": float(sigma),
            "sigma_vm_MPa": float(sigma_vm),
            "tau_MPa": float(tau),
            "compress_kN": float(mem["_N_comp"] / 1000.0),
            "util": float(sigma_vm / mem["strength_MPa"]) if mem["strength_MPa"] else None,
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

    # ---- 屈曲校核（取弱轴惯性矩 I_min） ----
    buck_ratio = 0.0; buck_member = None
    for mem in members:
        if mem["_N_comp"] <= 1e-9:
            continue
        I_min = min(
            mem["Iy_net"] if mem.get("has_hole") else mem["Iy"],
            mem["Iz_net"] if mem.get("has_hole") else mem["Iz"],
        )
        p_cr = math.pi ** 2 * mem["E_MPa"] * I_min / mem["L"] ** 2
        ratio = mem["_N_comp"] / p_cr if p_cr > 0 else float("inf")
        if ratio > buck_ratio:
            buck_ratio = ratio; buck_member = mem["id"]
    buck_note = ""
    if buck_ratio >= BUCKLING_FAIL:
        buck_note = (f"member {buck_member} is at or beyond the Euler critical load "
                     f"(weak-axis utilization {buck_ratio:.0%}) — buckling risk"
                     if is_en() else
                     f"杆件 {buck_member} 受压接近或超过欧拉临界力（弱轴利用率 {buck_ratio:.0%}），有失稳（压弯）风险")
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
    over_temp_members = []
    for mi, mem in enumerate(members):
        dt = thermal_of_member.get(mi)
        if dt is None:
            continue
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
                mag = math.sqrt(ld["fx"] ** 2 + ld["fy"] ** 2 + ld["fz"] ** 2)
                load_desc.append(f"node {nid} point load {force_n(mag)}")
            else:
                mid = members[ld["member"]]["id"]
                if ld["type"] == "thermal":
                    sign = "rise" if ld["delta_temp"] > 0 else "drop"
                    load_desc.append(f"member {mid} temperature {sign} {abs(ld['delta_temp']):.0f}°C")
                else:
                    qmag = float(np.linalg.norm(ld["q"]))
                    load_desc.append(f"member {mid} distributed load {linear_nmm(qmag)}")
        verdict_en = {"安全": "safe", "注意": "caution", "危险": "danger"}.get(verdict, verdict)
        narrative = [
            f"Model: your problem is simplified to a 3D frame of {len(members)} members and "
            f"{len(nodes)} nodes (material: {mats}), solved with spatial beam elements for "
            "bi-axial bending, axial load and torsion; member self-weight is neglected.",
            f"Loads: {'; '.join(load_desc)}. Constraints: {len(supports)} supports "
            f"({', '.join(sup['type'] for sup in supports)}).",
            f"Maximum deflection {disp_mm(max_disp)} ({max_disp_desc}); {hair_analogy(max_disp)}",
            f"The most loaded member is member {gov['id']}: max equivalent stress "
            f"{stress_mpa(sigma_max)} (including torsional von Mises conversion), "
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
                nid = nodes[ld["node"]]["id"]
                mag = math.sqrt(ld["fx"] ** 2 + ld["fy"] ** 2 + ld["fz"] ** 2)
                load_desc.append(f"节点 {nid} 集中力 {mag:.1f} N")
            else:
                mid = members[ld["member"]]["id"]
                if ld["type"] == "thermal":
                    sign = "升温" if ld["delta_temp"] > 0 else "降温"
                    load_desc.append(f"杆件 {mid} 温度{sign} {abs(ld['delta_temp']):.0f}°C")
                else:
                    qmag = float(np.linalg.norm(ld["q"]))
                    load_desc.append(f"杆件 {mid} 均布载荷 {qmag:.2f} N/mm")
        narrative = [
            f"建模：把你的问题简化为 {len(members)} 根空间杆件、{len(nodes)} 个节点的三维框架"
            f"（材料：{mats}），用空间梁单元计算双向弯曲、轴向与扭转，忽略构件自重。",
            f"载荷：{('；'.join(load_desc))}。约束：{len(supports)} 处支座"
            f"（{'、'.join(sup['type'] for sup in supports)}）。",
            f"最大变形 {max_disp:.3f} 毫米（{max_disp_desc}），{hair_analogy(max_disp)}",
            f"受力最大的杆件是杆件 {gov['id']}：等效最大应力 {sigma_max:.2f} MPa"
            f"（含扭转冯·米塞斯折算），材料强度代表值 {strength:.0f} MPa，"
            f"安全系数 {safety:.2f}（{verdict}）。",
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
        "kind": "frame3d",
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
