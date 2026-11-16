# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""容器填料载荷剖面（与壳单元几何无关的"装什么"层）。

壳单元只认节点力，不关心里面装的是水还是砂。本模块把任意填料统一成三条
沿埋深 h（自料面往下，m）的载荷剖面，供 shell.run_vessel 装配：

- ``normal(h)``  ：侧壁**法向**压力 pₙ(h)（Pa，向箱内）
- ``shear(h)``   ：侧壁**切向**剪应力 τ(h)（Pa，竖直向下；散体壁摩擦）
- ``floor(h)``   ：底板竖向均布压力 p_b（Pa，料柱在底部的竖向应力）

填料类型
========
流体（type="fluid"）
    各向等压 pₙ = γh、τ = 0、p_b = γH（γ=ρg）。

散体（type="granular"）
    水平侧压 pₙ = K·σv（黏性土主动/被动态再叠加 c 项）：
      - at_rest（静止，K₀=1−sinφ）：刚填满、壁面无变形；
      - active （主动，Ka=tan²(45°−φ/2)）：薄壁仓壁外挠时的极限平衡，
        钢板仓/料斗常规设计工况（默认）；
      - passive（被动，Kp=1/Ka）：壁面挤入料体（本 APP 一般不出现）。
    竖向应力/壁摩擦两种**整体平衡自洽**的模型：
      - 浅仓（janssen=false，默认）：σv = γh，物料重量全部由底板承担；
        Rankine 解对应光滑壁（δ=0 的极限状态），**不加壁面剪应力**
        （若同时加 τ=μKγh 又加底板 γH 会把料重重复计算 25%~40%）；
      - 深仓（janssen=true）：Janssen 仓压公式，壁摩擦把料"挂"在壁上，
        σv 指数饱和，此时 τ = pₙ·tanδ（δ=wall_friction_deg）竖直向下：
            σv(h) = γ/(μK/R)·(1 − e^{−(μK/R)·h})，R = A/P 水力半径
        p_b = σv(H)（小于 γH，差值即壁面总摩擦力；底板 + 壁面摩擦合计
        恰好等于料重，FE 反力自动平衡）。
    黏性填料（c>0，如湿砂/土）：主动态 pₙ=max(0, Ka·σv−2c√Ka)。

明确不包含（静力模型边界）：卸料成拱/偏心卸料超压、筒仓振动
（silo quake）、动态冲击、料流——这些需要动力分析或规范经验系数。

单位：长度 m、密度 kg/m³、应力量 Pa。
"""
from __future__ import annotations

import math
from typing import Any, Callable, Dict, Optional, Tuple

from .flow import GRAVITY

# 常用填料预设（散装/堆积密度；参数为工程常用代表值，用户可在 fill 中覆盖）
FILL_PRESETS: Dict[str, Dict[str, Any]] = {
    "water": {
        "label_zh": "水", "label_en": "Water",
        "type": "fluid", "bulk_density_kg_m3": 1000.0,
    },
    "dry_sand": {
        "label_zh": "干砂（松散）", "label_en": "Dry sand (loose)",
        "type": "granular", "bulk_density_kg_m3": 1600.0,
        "friction_angle_deg": 30.0, "wall_friction_deg": 20.0,
        "cohesion_pa": 0.0,
    },
    "wet_sand": {
        "label_zh": "湿砂（微黏）", "label_en": "Wet sand (slightly cohesive)",
        "type": "granular", "bulk_density_kg_m3": 1900.0,
        "friction_angle_deg": 32.0, "wall_friction_deg": 22.0,
        "cohesion_pa": 2000.0,
    },
    "grain_wheat": {
        "label_zh": "小麦（粮食）", "label_en": "Wheat grain",
        "type": "granular", "bulk_density_kg_m3": 850.0,
        "friction_angle_deg": 28.0, "wall_friction_deg": 18.0,
        "cohesion_pa": 0.0,
    },
    "cement_powder": {
        "label_zh": "水泥粉", "label_en": "Cement powder",
        "type": "granular", "bulk_density_kg_m3": 1300.0,
        "friction_angle_deg": 33.0, "wall_friction_deg": 24.0,
        "cohesion_pa": 0.0,
    },
    "plastic_pellets": {
        "label_zh": "塑料颗粒", "label_en": "Plastic pellets",
        "type": "granular", "bulk_density_kg_m3": 650.0,
        "friction_angle_deg": 25.0, "wall_friction_deg": 15.0,
        "cohesion_pa": 0.0,
    },
}

_GRANULAR_STATES = ("at_rest", "active", "passive")


# ====================================================================== 土力学
def lateral_coefficient(friction_angle_deg: float,
                        state: str = "active") -> float:
    """侧压力系数 K：K₀=1−sinφ（静止）、Ka=tan²(45°−φ/2)（主动）、Kp=1/Ka。"""
    phi = math.radians(float(friction_angle_deg))
    if state == "at_rest":
        return max(1.0 - math.sin(phi), 0.0)
    if state == "passive":
        return 1.0 / max(math.tan(math.pi / 4.0 - phi / 2.0) ** 2, 1e-12)
    if state != "active":
        raise ValueError(
            f"散体状态必须是 at_rest/active/passive，得到「{state}」")
    return math.tan(math.pi / 4.0 - phi / 2.0) ** 2


def janssen_sigma_v(depth_m: float, gamma: float, k: float, mu: float,
                    hydraulic_radius: float) -> float:
    """Janssen 竖向应力 σv(h) = γ/λ·(1−e^{−λh})，λ=μK/R。

    μK→0 时退化为 σv=γh（调用方负责兜底）。
    """
    lam = mu * k / max(float(hydraulic_radius), 1e-12)
    if lam < 1e-9:
        return gamma * float(depth_m)
    return gamma / lam * (1.0 - math.exp(-lam * float(depth_m)))


# ====================================================================== 解析
class FillLoad:
    """一种填料在给定容器中的三条载荷剖面 + 描述信息。"""

    def __init__(self, *, kind: str, bulk_density: float, depth_m: float,
                 normal: Optional[Callable[[float], float]],
                 shear: Optional[Callable[[float], float]],
                 floor_pressure_pa: float,
                 info: Dict[str, Any]):
        self.kind = kind                      # "fluid" | "granular" | "empty"
        self.bulk_density = float(bulk_density)
        self.depth_m = float(depth_m)
        # 剖面输入为埋深 h(m, 自料面向下)；空仓时为 None
        self.normal_h = normal
        self.shear_h = shear
        self.floor_pressure_pa = float(floor_pressure_pa)
        self.info = info

    # shell 装配层按整体坐标 y（底=0）调用：埋深 = depth − y，料面以上为 0
    def normal_xyz(self):
        if self.normal_h is None:
            return None
        import numpy as np
        f = np.vectorize(self.normal_h, otypes=[float])
        d = self.depth_m

        def _fn(xyz):
            return f(np.maximum(d - xyz[:, 1], 0.0))
        return _fn

    def shear_xyz(self):
        if self.shear_h is None:
            return None
        import numpy as np
        f = np.vectorize(self.shear_h, otypes=[float])
        d = self.depth_m

        def _fn(xyz):
            return f(np.maximum(d - xyz[:, 1], 0.0))
        return _fn


def parse_fill(model: Dict[str, Any], geom_height: float,
               plan_area_m2: float, perimeter_m: float) -> FillLoad:
    """从模型 JSON 解析填料。

    新契约：``fill`` 为预设名或对象：
      {"type":"fluid","bulk_density_kg_m3":1000}
      {"type":"granular","bulk_density_kg_m3":1600,"friction_angle_deg":30,
       "wall_friction_deg":20,"cohesion_pa":0,"state":"active",
       "janssen":false}
    料深用 ``fill_depth_m``（向后兼容旧字段 water_depth_m；缺省=满仓）。

    旧契约（无 fill）：按水处理，密度取 fluid_density_kg_m3（缺省 1000）。
    """
    height = float(geom_height)
    # 注意：键存在但显式为 null（如 AI 返回 "fill_depth_m": null）时 dict.get
    # 不会启用默认值，float(None) 会抛 TypeError；故逐级判 None，缺省=满仓。
    raw_depth = model.get("fill_depth_m")
    if raw_depth is None:
        raw_depth = model.get("water_depth_m")
    depth = float(height if raw_depth is None else raw_depth)
    if depth < 0.0 or depth > height + 1e-9:
        raise ValueError(
            f"装料深度（fill_depth_m / 旧称水深 water_depth_m）需在 0 ~ "
            f"容器高 {height} m 之间，得到 {depth}")
    if depth <= 1e-12:
        return FillLoad(kind="empty", bulk_density=0.0, depth_m=0.0,
                        normal=None, shear=None, floor_pressure_pa=0.0,
                        info={"empty": True})

    spec = _resolve_spec(model)
    rho = float(spec["bulk_density_kg_m3"])
    if rho <= 0.0:
        raise ValueError("填料堆积密度必须为正")
    gamma = rho * GRAVITY
    ftype = str(spec.get("type") or "fluid").strip().lower()

    if ftype == "fluid":
        info = {
            "empty": False, "kind": "fluid", "rho": rho, "gamma": gamma,
            "depth": depth, "K": 1.0,
            "label": spec.get("label_zh") or "流体",
            "label_en": spec.get("label_en") or "Fluid",
            "janssen": False, "state": None, "mu": 0.0,
        }
        return FillLoad(
            kind="fluid", bulk_density=rho, depth_m=depth,
            normal=lambda h, g=gamma: g * max(float(h), 0.0),
            shear=None, floor_pressure_pa=gamma * depth, info=info)

    if ftype != "granular":
        raise ValueError(f"未知填料类型「{ftype}」，仅支持 fluid / granular")

    phi = float(spec.get("friction_angle_deg", 30.0))
    delta = float(spec.get("wall_friction_deg",
                           spec.get("friction_angle_deg", 30.0) * 2.0 / 3.0))
    cohesion = float(spec.get("cohesion_pa", 0.0))
    state = str(spec.get("state") or "active").strip().lower()
    if state not in _GRANULAR_STATES:
        raise ValueError(
            f"散体侧压状态必须是 {'/'.join(_GRANULAR_STATES)}，得到「{state}」")
    if not (0.0 <= phi < 60.0):
        raise ValueError(f"内摩擦角需在 0~60° 之间，得到 {phi}")
    if not (0.0 <= delta <= 60.0):
        raise ValueError(f"壁摩擦角需在 0~60° 之间，得到 {delta}")
    if cohesion < 0.0:
        raise ValueError("黏聚力不能为负")
    use_janssen = bool(spec.get("janssen", False))

    k = lateral_coefficient(phi, state)
    mu = math.tan(math.radians(delta))
    radius_h = (float(plan_area_m2) / float(perimeter_m)
                if perimeter_m > 0.0 else 0.0)

    def sigma_v(h: float) -> float:
        h = max(float(h), 0.0)
        if use_janssen and mu * k > 1e-9 and radius_h > 0.0:
            return janssen_sigma_v(h, gamma, k, mu, radius_h)
        return gamma * h

    sqrt_k = math.sqrt(k)

    def normal(h: float) -> float:
        sv = sigma_v(h)
        if state == "active":
            return max(0.0, k * sv - 2.0 * cohesion * sqrt_k)
        if state == "passive":
            return k * sv + 2.0 * cohesion * sqrt_k
        return k * sv  # at_rest 不叠加黏聚项

    def shear(h: float) -> float:
        return mu * normal(h)

    floor_pa = sigma_v(depth)
    info = {
        "empty": False, "kind": "granular", "rho": rho, "gamma": gamma,
        "depth": depth, "K": k, "phi_deg": phi, "delta_deg": delta,
        "mu": mu, "cohesion_pa": cohesion, "state": state,
        "janssen": use_janssen, "hydraulic_radius_m": radius_h,
        "sigma_v_base_pa": floor_pa,
        "label": spec.get("label_zh") or "散体",
        "label_en": spec.get("label_en") or "Granular",
    }
    # 壁面剪应力只在 Janssen 深仓模型下施加：浅仓 σv=γh 意味着料重全部
    # 走底板（Rankine 光滑壁），再施加摩擦会破坏整体竖向平衡。
    shear_fn = shear if (use_janssen and mu > 1e-12) else None
    return FillLoad(
        kind="granular", bulk_density=rho, depth_m=depth,
        normal=normal, shear=shear_fn,
        floor_pressure_pa=floor_pa, info=info)


def _resolve_spec(model: Dict[str, Any]) -> Dict[str, Any]:
    """把 fill 字段解析为完整参数 dict（预设名/预设+覆盖/自定义/旧契约）。"""
    fill = model.get("fill")
    legacy = not ("fill" in model and fill is not None)
    if fill is None:
        # 旧契约：水，可用 fluid_density_kg_m3 覆盖
        spec = dict(FILL_PRESETS["water"])
        rho = model.get("fluid_density_kg_m3")
        if rho is not None:
            spec["bulk_density_kg_m3"] = float(rho)
        return spec
    if isinstance(fill, str):
        key = fill.strip().lower()
        if key not in FILL_PRESETS:
            raise ValueError(
                f"未知填料预设「{fill}」，可选：{'、'.join(FILL_PRESETS)}")
        return dict(FILL_PRESETS[key])
    if not isinstance(fill, dict):
        raise ValueError("fill 必须是预设名或参数对象")
    spec = dict(fill)
    base_key = spec.pop("preset", None)
    if isinstance(base_key, str):
        base_key = base_key.strip().lower()
        if base_key not in FILL_PRESETS:
            raise ValueError(f"未知填料预设「{base_key}」")
        merged = dict(FILL_PRESETS[base_key])
        merged.update(spec)
        spec = merged
    if "type" not in spec:
        raise ValueError("fill 对象必须包含 type（fluid/granular）")
    if "bulk_density_kg_m3" not in spec:
        raise ValueError("fill 对象必须包含 bulk_density_kg_m3（堆积密度）")
    return spec
