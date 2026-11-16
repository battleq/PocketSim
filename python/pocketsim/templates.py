# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""生活场景模板：悬臂搁板 / 墙上钉挂重 / 简支层板（桌板）。

每个模板提供：
- param_schema：参数表（key/中文名/单位/类型/必填/默认值/最小/最大），供 AI 参数
  抽取与手动填参 UI 使用；
- validate：参数校验（范围、必填），返回中文错误列表；
- build(params, mesh_level)：产出 SolveConfig（模板负责 mm→SI 换算与建模）；
- assumptions：建模假设列表（结果页展示，通俗中文）；
- theory_check：材料力学理论解（公式名 + 数值），与 FEA 读数对比误差 ≤5%；
- fea_reading：与理论解同口径的 FEA 读数（截面平均挠度，m）；
- extra_checks：简化校核项（名称/公式/数值/结论）。

单位约定：模板参数一律用生活单位（mm、kg、N、MPa），build 内部换算为 SI。

建模细节（对理论对齐很关键，勿随意改动）：
- 端部集中力按“截面节点均分”静力等效施加（合力与作用点不变，避免单节点
  集中力的对数奇异）；挠度读数取加载截面平均（消除截面刚性转动项 ±θ·t/2）；
- 最大 von Mises 读数由求解器自动排除固支边界与集中力作用节点（应力奇异位置）；
- 平面应力 + 平面外厚度 b：力按总力直接施加（等价于“按 P/b 化为单位厚度
  平面问题”），挠度与应力与三维梁理论一致，输出无需再乘 b。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from .materials import MaterialsLibrary
from .meshgen import apply_mesh_level
from .solver import (
    GRAVITY_M_S2,
    SolveConfig,
    facets_at_y,
    fixed_dofs_clamped_x,
    fixed_dofs_point_supports,
    nodes_at_x,
)


def _m(value_mm: float) -> float:
    """毫米 → 米。"""
    return float(value_mm) / 1000.0


@dataclass
class ParamSpec:
    """单个参数的规格说明（供 AI 抽参与 UI 表单使用）。"""

    key: str                          # 参数键名（英文）
    label: str                        # 中文名
    unit: str                         # 单位（mm / kg / N / MPa / -）
    type: str = "number"              # number | material
    required: bool = False            # 是否必填（必填项无默认值）
    default: Any = None               # 默认值
    minimum: Optional[float] = None   # 最小值
    maximum: Optional[float] = None   # 最大值
    description: str = ""             # 通俗说明

    def to_dict(self) -> Dict:
        return {
            "key": self.key,
            "label": self.label,
            "unit": self.unit,
            "type": self.type,
            "required": self.required,
            "default": self.default,
            "min": self.minimum,
            "max": self.maximum,
            "description": self.description,
        }


class ScenarioTemplate:
    """场景模板基类。子类需设置类属性并实现各方法。"""

    id: str = ""
    name: str = ""
    description: str = ""
    param_schema: List[ParamSpec] = []
    # 材料参数键与默认材料名（None 表示必填，由 AI/用户提供）
    material_param_keys: List[Tuple[str, Optional[str]]] = []
    # 安全分级所用主材料键
    primary_material_key: str = ""

    # ---------------------------------------------------------------- 校验
    def validate(self, params: Dict) -> List[str]:
        """通用 schema 校验，返回中文错误列表（空列表表示通过）。"""
        errors: List[str] = []
        for spec in self.param_schema:
            if spec.key not in params or params[spec.key] is None:
                if spec.required:
                    errors.append(f"缺少必填参数 {spec.key}（{spec.label}）")
                continue
            value = params[spec.key]
            if spec.type == "number":
                if isinstance(value, bool) or not isinstance(value, (int, float)):
                    errors.append(
                        f"参数 {spec.key}（{spec.label}）必须是数字，当前为 {value!r}"
                    )
                    continue
                if spec.minimum is not None and value < spec.minimum:
                    errors.append(
                        f"参数 {spec.key}（{spec.label}）= {value} 低于最小值 {spec.minimum}"
                    )
                if spec.maximum is not None and value > spec.maximum:
                    errors.append(
                        f"参数 {spec.key}（{spec.label}）= {value} 超过最大值 {spec.maximum}"
                    )
            elif spec.type == "material":
                if not isinstance(value, (str, dict)):
                    errors.append(
                        f"参数 {spec.key}（{spec.label}）应为材料名称字符串，当前为 {value!r}"
                    )
        self._validate_extra(params, errors)
        return errors

    def _validate_extra(self, params: Dict, errors: List[str]) -> None:
        """子类自定义校验钩子（如“载荷二选一”）。"""

    # ---------------------------------------------------------------- 材料
    def _material(self, params: Dict, key: str, default_name: Optional[str] = None) -> Dict:
        """取出材料记录；允许传入名称字符串（查内置库）或已解析的记录 dict。"""
        raw = params.get(key, default_name)
        if raw is None:
            raise ValueError(f"材料参数 {key} 未提供且无默认材料")
        if isinstance(raw, dict):
            return raw
        record = MaterialsLibrary().find_material(str(raw))
        if record is None:
            raise ValueError(f"材料库中未找到“{raw}”（参数 {key}）")
        return record

    @staticmethod
    def _material_note(record: Dict, label: str) -> str:
        """生成材料来源说明行（放入假设列表）。"""
        return (
            f"{label}“{record['name']}”：E={record['elastic_modulus'] / 1e9:.1f} GPa、"
            f"ν={record['poisson_ratio']}、强度代表值={record['strength']} MPa"
            f"（{record.get('strength_type', '强度代表值')}），来源：{record.get('source', '未知')}"
        )

    # ---------------------------------------------------------------- 子类接口
    def effective_params(self, params: Dict) -> Dict:
        """回填默认值后的有效参数（JSON 友好，材料显示名称）。"""
        eff: Dict[str, Any] = {}
        for spec in self.param_schema:
            value = params.get(spec.key)
            if value is None:
                value = spec.default
            if spec.type == "material":
                if isinstance(value, dict):
                    value = value.get("name")
                value = value if value is not None else self._default_material_name(spec.key)
            eff[spec.key] = value
        return eff

    def _default_material_name(self, key: str) -> Optional[str]:
        for k, default_name in self.material_param_keys:
            if k == key:
                return default_name
        return None

    def build(self, params: Dict, mesh_level: str = "medium",
              mesh_counts: Optional[Tuple[int, int]] = None) -> SolveConfig:
        raise NotImplementedError

    def assumptions(self, params: Dict) -> List[str]:
        raise NotImplementedError

    def theory_check(self, params: Dict) -> Dict:
        raise NotImplementedError

    def fea_reading(self, params: Dict, result) -> float:
        """与理论解同口径的 FEA 挠度读数（m，正值表示下垂量）。"""
        raise NotImplementedError

    def extra_checks(self, params: Dict, result) -> List[Dict]:
        raise NotImplementedError

    # ---------------------------------------------------------------- 公共
    def meta(self) -> Dict:
        """模板元信息（list_templates 用）。"""
        return {
            "id": self.id,
            "name": self.name,
            "description": self.description,
            "params": [spec.to_dict() for spec in self.param_schema],
        }


def _strength_check(name: str, value: float, strength: float, unit: str,
                    formula: str) -> Dict:
    """构造强度类校核项（安全系数 + 中文结论）。"""
    from .interpret import safety_class

    label, n = safety_class(value, strength)
    if label == "安全":
        conclusion = f"通过：{label}（安全系数 n = {n:.2f}，余量充足）"
    elif label == "注意":
        conclusion = f"偏紧：{label}（安全系数 n = {n:.2f}，建议留余量或加强）"
    else:
        conclusion = f"不通过：{label}（安全系数 n = {n:.2f}，已接近或超过强度代表值）"
    return {
        "name": name,
        "formula": formula,
        "value": round(float(value), 4),
        "unit": unit,
        "limit": float(strength),
        "safety_factor": round(float(n), 3),
        "conclusion": conclusion,
    }


def _advisory_check(name: str, value: float, limit: float, unit: str,
                    formula: str) -> Dict:
    """构造建议类校核项（挠度建议值等，不判危险）。"""
    ok = value <= limit
    return {
        "name": name,
        "formula": formula,
        "value": round(float(value), 4),
        "unit": unit,
        "limit": round(float(limit), 4),
        "safety_factor": round(float(limit / value), 3) if value > 0 else None,
        "conclusion": "满足建议值" if ok else "超出建议值，挠度偏大，建议加厚或缩短跨度",
    }


# ==================================================================== 悬臂搁板
class CantileverShelfTemplate(ScenarioTemplate):
    """悬臂搁板：一端固定在墙体上的搁板，自由端放重物。

    建模：平面应力矩形域 L×t（跨度×厚度），x=0 端固支，自由端截面施加
    竖直向下的等效集中力 P = load_kg×9.8 N（或直接给 load_n），可选顶部
    均布载荷 q（N/m，沿跨度）。理论解：δ=PL³/(3EI)+qL⁴/(8EI)，I=bt³/12。
    """

    id = "cantilever_shelf"
    name = "悬臂搁板"
    description = "一端装在墙上的搁板/层板，估算自由端放重物后的下垂量与安全性"
    # 基准网格（medium 档）：P2 单元下端部挠度误差 <1%
    BASE_NX = 60
    BASE_NY = 8

    param_schema = [
        ParamSpec("length_mm", "跨度 L", "mm", required=True, minimum=50, maximum=3000,
                  description="搁板伸出墙面的悬挑长度"),
        ParamSpec("thickness_mm", "厚度 t", "mm", required=True, minimum=2, maximum=300,
                  description="搁板板材厚度"),
        ParamSpec("width_mm", "宽度 b", "mm", default=200, minimum=10, maximum=3000,
                  description="搁板进深方向的宽度（平面外尺寸）"),
        ParamSpec("material", "材质", "-", type="material", required=True,
                  description="板材材料，如 松木、密度板、胶合板、玻璃"),
        ParamSpec("load_kg", "端部载荷质量", "kg", default=None, minimum=0.05, maximum=500,
                  description="自由端放置重物的质量（与 load_n 至少提供一个）"),
        ParamSpec("load_n", "端部载荷力", "N", default=None, minimum=0.5, maximum=5000,
                  description="直接以牛顿给出的端部载荷（优先于 load_kg）"),
        ParamSpec("udl_n_per_m", "均布载荷", "N/m", default=0, minimum=0, maximum=20000,
                  description="沿跨度方向的附加均布载荷（如一排书），0 表示没有"),
    ]
    material_param_keys = [("material", None)]
    primary_material_key = "material"

    def _validate_extra(self, params: Dict, errors: List[str]) -> None:
        if params.get("load_kg") is None and params.get("load_n") is None:
            errors.append("至少提供端部载荷之一：load_kg（千克）或 load_n（牛顿）")

    @staticmethod
    def _load_N(params: Dict) -> float:
        if params.get("load_n") is not None:
            return float(params["load_n"])
        return float(params["load_kg"]) * GRAVITY_M_S2

    def _geom(self, params: Dict) -> Dict:
        """提取几何/载荷（SI 单位）。"""
        mat = self._material(params, "material")
        return {
            "L": _m(params["length_mm"]),
            "t": _m(params["thickness_mm"]),
            "b": _m(params["width_mm"] if params.get("width_mm") is not None else 200.0),
            "E": float(mat["elastic_modulus"]),
            "nu": float(mat["poisson_ratio"]),
            "strength": float(mat["strength"]),
            "P": self._load_N(params),
            "q": float(params.get("udl_n_per_m") or 0.0),
            "mat": mat,
        }

    def build(self, params: Dict, mesh_level: str = "medium",
              mesh_counts: Optional[Tuple[int, int]] = None) -> SolveConfig:
        g = self._geom(params)
        nx, ny = apply_mesh_level(self.BASE_NX, self.BASE_NY,
                                  mesh_counts if mesh_counts is not None else mesh_level)
        extra = []
        if g["q"] != 0.0:
            # 顶部均布线载荷（沿跨度，N/m）：一致边界载荷
            top_y = g["t"] / 2.0
            extra.append(
                {
                    "facets_fn": lambda mesh, y=top_y: facets_at_y(mesh, y),
                    "force_n_per_m": (0.0, -g["q"]),
                }
            )
        return SolveConfig(
            nx=nx, ny=ny, x_len=g["L"], y_len=g["t"], y0=-g["t"] / 2.0,
            thickness=g["b"], E=g["E"], nu=g["nu"], body_force=None,
            dirichlet_boundary_fn=lambda basis, mesh: fixed_dofs_clamped_x(basis, mesh, 0.0),
            load_region_fn=lambda mesh, x=g["L"]: nodes_at_x(mesh, x),
            load_vector_N=(0.0, -g["P"]),
            plane_stress=True,
            extra_edge_loads=extra or None,
        )

    def assumptions(self, params: Dict) -> List[str]:
        g = self._geom(params)
        return [
            "搁板按匀质、各向同性线弹性悬臂梁建模（平面应力），小变形、不考虑失稳",
            "固定端（墙面连接处）视为完全刚性，不考虑托架松动或墙体变形",
            "端部载荷按静力等效均布于自由端截面（合力与作用点不变），避免单点集中力引起的数值奇异",
            "力按 P/b 化为单位厚度平面问题求解（挠度不变、应力与三维梁理论一致），平面外宽度以等刚度计入",
            "忽略搁板自重与板材蠕变、冲击效应，仅考虑静载",
            "强度校核采用材料库代表值（非保证值），实际选材请以相应规范设计值为准",
            self._material_note(g["mat"], "搁板材料"),
        ]

    def theory_check(self, params: Dict) -> Dict:
        g = self._geom(params)
        L, t, b, E = g["L"], g["t"], g["b"], g["E"]
        inertia = b * t ** 3 / 12.0
        section_modulus = b * t ** 2 / 6.0
        delta = g["P"] * L ** 3 / (3.0 * E * inertia) + g["q"] * L ** 4 / (8.0 * E * inertia)
        root_moment = g["P"] * L + g["q"] * L ** 2 / 2.0
        sigma_root = root_moment / section_modulus
        return {
            "name": "悬臂梁端部挠度（欧拉-伯努利梁理论）",
            "formula": "δ = PL³/(3EI) + qL⁴/(8EI)，I = bt³/12",
            "value": float(delta),
            "value_mm": float(delta * 1000.0),
            "unit": "m",
            "extras": {
                "root_bending_stress_mpa": float(sigma_root / 1e6),
                "inertia_m4": float(inertia),
                "section_modulus_m3": float(section_modulus),
            },
        }

    def fea_reading(self, params: Dict, result) -> float:
        """自由端截面平均挠度（m，向下为正）。"""
        g = self._geom(params)
        return abs(result.uy_at_x_section(g["L"], tol=1e-6))

    def extra_checks(self, params: Dict, result) -> List[Dict]:
        g = self._geom(params)
        vm_mpa = result.max_von_mises / 1e6
        delta_mm = self.fea_reading(params, result) * 1000.0
        checks = [
            _strength_check(
                "搁板弯曲强度校核",
                vm_mpa,
                g["strength"],
                "MPa",
                f"σ_max(FEA) = {vm_mpa:.2f} MPa（固支根部）≤ 强度代表值 {g['strength']} MPa",
            )
        ]
        if delta_mm > 0:
            checks.append(
                _advisory_check(
                    "端部挠度建议值",
                    delta_mm,
                    params["length_mm"] / 250.0,
                    "mm",
                    "δ ≤ L/250（搁板/装饰构件挠度建议限值）",
                )
            )
        return checks


# ==================================================================== 墙上钉挂重
class WallNailTemplate(ScenarioTemplate):
    """墙上钉挂重：钉子外露段悬臂弯曲 FEA + 墙体承压/拉拔简化校核。

    建模：钉外露段按悬臂梁平面应力（域 la×d，平面外厚度取 d，即方截面等效，
    I = d⁴/12），x=0（墙面处）固支，钉尖截面施加 F = m×9.8 N。
    简化校核（手算公式）：墙孔承压 σ=F/(d·lb)、抗拉拔 F_pull=τ·π·d·lb、
    钉身弯曲强度 σ=F·la/W（W=d³/6）与 FEA 最大应力对照。
    """

    id = "wall_nail"
    name = "墙上钉挂重"
    description = "钉子（无钉孔挂钉）上挂重物，估算钉尖下垂、钉身应力与墙体承压/抗拉拔安全性"
    BASE_NX = 40
    BASE_NY = 6

    param_schema = [
        ParamSpec("mass_kg", "悬挂重量 m", "kg", required=True, minimum=0.05, maximum=200,
                  description="挂在钉子上的重物质量"),
        ParamSpec("nail_diameter_mm", "钉直径 d", "mm", default=3.5, minimum=0.5, maximum=20,
                  description="普通钢钉直径约 3~4mm"),
        ParamSpec("exposed_length_mm", "钉外露长度 la", "mm", default=25, minimum=5, maximum=200,
                  description="钉尖到墙面的外露长度"),
        ParamSpec("embed_length_mm", "钉打入深度 lb", "mm", default=30, minimum=5, maximum=300,
                  description="钉子打入墙体的深度"),
        ParamSpec("nail_material", "钉材质", "-", type="material", default=None,
                  description="默认低碳钢（钢钉）"),
        ParamSpec("wall_material", "墙材质", "-", type="material", default=None,
                  description="默认砖砌体，可选混凝土等"),
        ParamSpec("bond_strength_mpa", "钉墙粘结强度 τ", "MPa", default=0.5,
                  minimum=0.02, maximum=10,
                  description="钉与墙孔之间的摩擦/粘结代表值，经验值可调"),
    ]
    material_param_keys = [("nail_material", "低碳钢"), ("wall_material", "砖砌体")]
    primary_material_key = "nail_material"

    def _geom(self, params: Dict) -> Dict:
        nail = self._material(params, "nail_material", "低碳钢")
        wall = self._material(params, "wall_material", "砖砌体")
        return {
            "la": _m(params.get("exposed_length_mm") if params.get("exposed_length_mm") is not None else 25.0),
            "d": _m(params.get("nail_diameter_mm") if params.get("nail_diameter_mm") is not None else 3.5),
            "d_mm": float(params.get("nail_diameter_mm") if params.get("nail_diameter_mm") is not None else 3.5),
            "lb_mm": float(params.get("embed_length_mm") if params.get("embed_length_mm") is not None else 30.0),
            "F": float(params["mass_kg"]) * GRAVITY_M_S2,
            "tau": float(params.get("bond_strength_mpa") if params.get("bond_strength_mpa") is not None else 0.5),
            "nail": nail,
            "wall": wall,
        }

    def build(self, params: Dict, mesh_level: str = "medium",
              mesh_counts: Optional[Tuple[int, int]] = None) -> SolveConfig:
        g = self._geom(params)
        nx, ny = apply_mesh_level(self.BASE_NX, self.BASE_NY,
                                  mesh_counts if mesh_counts is not None else mesh_level)
        la, d = g["la"], g["d"]
        return SolveConfig(
            nx=nx, ny=ny, x_len=la, y_len=d, y0=-d / 2.0,
            thickness=d,  # 平面外厚度取钉径（方截面等效）
            E=float(g["nail"]["elastic_modulus"]),
            nu=float(g["nail"]["poisson_ratio"]),
            body_force=None,
            dirichlet_boundary_fn=lambda basis, mesh: fixed_dofs_clamped_x(basis, mesh, 0.0),
            load_region_fn=lambda mesh, x=la: nodes_at_x(mesh, x),
            load_vector_N=(0.0, -g["F"]),
            plane_stress=True,
        )

    def assumptions(self, params: Dict) -> List[str]:
        g = self._geom(params)
        return [
            "钉打入段（墙体内部）按完全固定处理，不考虑钉与墙孔之间的滑移与转动",
            "钉外露段按悬臂梁平面应力建模：平面外厚度取钉径 d，等价于 d×d 方截面（I=d⁴/12）；"
            "实际圆截面 I=πd⁴/64 略小，本模型为保持 2D 一致性采用方截面等效",
            "悬挂重量按静载处理（F = m×9.8 N，竖直向下），施加于钉尖截面（静力等效均布），"
            "不考虑挂绳摆动、冲击与振动",
            "忽略钉帽、螺纹与钉尖锥度的影响，钉身按等截面直杆处理",
            f"墙孔承压按 σ = F/(d×lb) 简化（孔壁均压），拉拔按摩擦简化 F_pull = τ×π×d×lb，"
            f"粘结强度 τ 取经验代表值 {g['tau']} MPa（可调参数），实际与墙体材质/钻孔质量关系很大",
            "假设墙体完好无开裂、钉孔施工正常；空心砖/石膏板墙实际承载力可能显著更低",
            "强度校核采用材料库代表值（非保证值），仅供生活场景快速估算",
            self._material_note(g["nail"], "钉材料"),
            self._material_note(g["wall"], "墙材料"),
        ]

    def theory_check(self, params: Dict) -> Dict:
        g = self._geom(params)
        la, d = g["la"], g["d"]
        E = float(g["nail"]["elastic_modulus"])
        inertia = d ** 4 / 12.0            # 方截面等效
        section_modulus = d ** 3 / 6.0
        delta = g["F"] * la ** 3 / (3.0 * E * inertia)
        sigma = g["F"] * la / section_modulus
        return {
            "name": "钉外露段悬臂挠度（欧拉-伯努利梁理论）",
            "formula": "δ = F·la³/(3EI)，I = d⁴/12（方截面等效）",
            "value": float(delta),
            "value_mm": float(delta * 1000.0),
            "unit": "m",
            "extras": {
                "root_bending_stress_mpa": float(sigma / 1e6),
                "inertia_m4": float(inertia),
            },
        }

    def fea_reading(self, params: Dict, result) -> float:
        """钉尖截面平均挠度（m，向下为正）。"""
        g = self._geom(params)
        return abs(result.uy_at_x_section(g["la"], tol=1e-6))

    def extra_checks(self, params: Dict, result) -> List[Dict]:
        g = self._geom(params)
        F, d_mm, lb_mm, tau = g["F"], g["d_mm"], g["lb_mm"], g["tau"]
        # 1) 墙孔承压（手算公式）
        sigma_c = F / (d_mm * lb_mm)                      # N/mm² = MPa
        wall_strength = float(g["wall"]["strength"])
        # 2) 抗拉拔（摩擦/粘结简化，手算公式）
        f_pull = tau * 3.141592653589793 * d_mm * lb_mm   # N
        # 3) 钉身弯曲强度（FEA 最大应力）
        vm_mpa = result.max_von_mises / 1e6
        nail_strength = float(g["nail"]["strength"])
        checks = [
            _strength_check(
                "墙孔承压校核",
                sigma_c,
                wall_strength,
                "MPa",
                f"σ_c = F/(d×lb) = {F:.1f}/({d_mm}×{lb_mm}) = {sigma_c:.3f} MPa"
                f" ≤ 墙材抗压代表值 {wall_strength} MPa",
            ),
            _strength_check(
                "抗拉拔校核（摩擦简化）",
                F,
                f_pull,
                "N",
                f"F_pull = τ×π×d×lb = {tau}×π×{d_mm}×{lb_mm} = {f_pull:.1f} N ≥ 悬挂重力 {F:.1f} N",
            ),
            _strength_check(
                "钉身弯曲强度校核",
                vm_mpa,
                nail_strength,
                "MPa",
                f"σ_max(FEA) = {vm_mpa:.1f} MPa（钉身弯曲+剪切合成）≤ 钉材强度代表值 {nail_strength} MPa",
            ),
        ]
        return checks


# ==================================================================== 简支层板/桌板
class SimplySupportedPanelTemplate(ScenarioTemplate):
    """简支层板/桌板：两端搁在支撑上的板，跨中放重物。

    建模：平面应力矩形域 L×t，左右端底部角点简支（约束 uy，左端加 ux 防漂移），
    跨中截面施加集中力 P。理论解：δ = PL³/(48EI)。
    平面应力每单位厚度：力按 P/b 化为单位厚度平面问题（挠度不变、应力一致）。
    """

    id = "simply_supported_panel"
    name = "简支层板/桌板"
    description = "两端搭在支撑上的层板/桌板（书架隔板、桌面），估算跨中放重物后的下垂与安全性"
    BASE_NX = 64
    BASE_NY = 8

    param_schema = [
        ParamSpec("length_mm", "跨度 L", "mm", required=True, minimum=100, maximum=4000,
                  description="两端支撑之间的净跨度"),
        ParamSpec("thickness_mm", "厚度 t", "mm", required=True, minimum=3, maximum=200,
                  description="板材厚度"),
        ParamSpec("width_mm", "宽度 b", "mm", default=600, minimum=50, maximum=3000,
                  description="板宽（平面外尺寸，桌面宽 600mm 左右）"),
        ParamSpec("material", "材质", "-", type="material", required=True,
                  description="板材材料，如 胶合板、松木、密度板"),
        ParamSpec("load_kg", "跨中载荷质量", "kg", default=None, minimum=0.05, maximum=1000,
                  description="放在跨度正中的重物质量（与 load_n 至少提供一个）"),
        ParamSpec("load_n", "跨中载荷力", "N", default=None, minimum=0.5, maximum=10000,
                  description="直接以牛顿给出的跨中载荷（优先于 load_kg）"),
    ]
    material_param_keys = [("material", None)]
    primary_material_key = "material"

    def _validate_extra(self, params: Dict, errors: List[str]) -> None:
        if params.get("load_kg") is None and params.get("load_n") is None:
            errors.append("至少提供跨中载荷之一：load_kg（千克）或 load_n（牛顿）")

    def _geom(self, params: Dict) -> Dict:
        mat = self._material(params, "material")
        if params.get("load_n") is not None:
            load = float(params["load_n"])
        else:
            load = float(params["load_kg"]) * GRAVITY_M_S2
        return {
            "L": _m(params["length_mm"]),
            "t": _m(params["thickness_mm"]),
            "b": _m(params["width_mm"] if params.get("width_mm") is not None else 600.0),
            "E": float(mat["elastic_modulus"]),
            "nu": float(mat["poisson_ratio"]),
            "strength": float(mat["strength"]),
            "P": load,
            "mat": mat,
        }

    def build(self, params: Dict, mesh_level: str = "medium",
              mesh_counts: Optional[Tuple[int, int]] = None) -> SolveConfig:
        g = self._geom(params)
        L, t = g["L"], g["t"]
        nx, ny = apply_mesh_level(self.BASE_NX, self.BASE_NY,
                                  mesh_counts if mesh_counts is not None else mesh_level)
        if nx % 2:
            nx += 1  # 保证跨中 x=L/2 处有节点列（载荷施加与读数位置对齐）
        half = L / 2.0
        bottom = -t / 2.0
        return SolveConfig(
            nx=nx, ny=ny, x_len=L, y_len=t, y0=bottom,
            thickness=g["b"], E=g["E"], nu=g["nu"], body_force=None,
            dirichlet_boundary_fn=lambda basis, mesh: fixed_dofs_point_supports(
                basis, mesh, [(0.0, bottom, "xy"), (L, bottom, "y")]
            ),
            load_region_fn=lambda mesh, x=half: nodes_at_x(mesh, x),
            load_vector_N=(0.0, -g["P"]),
            plane_stress=True,
        )

    def assumptions(self, params: Dict) -> List[str]:
        g = self._geom(params)
        return [
            "层板按匀质、各向同性线弹性简支梁建模（平面应力），小变形、不考虑失稳",
            "两端按理想简支处理（底部角点约束竖向位移、允许转动），不考虑支撑下沉与摩擦嵌固作用",
            "跨中载荷按静力等效均布于跨中截面（合力与作用点不变），避免单点集中力的数值奇异",
            "力按 P/b 化为单位厚度平面问题求解：挠度与 b 无关、应力与三维梁理论一致，输出无需再乘 b",
            "忽略板材自重、蠕变与冲击效应，仅考虑静载",
            "跨中挠度读数取跨中截面平均（与梁理论中性轴挠度同口径）",
            "强度校核采用材料库代表值（非保证值），实际选材请以相应规范设计值为准",
            self._material_note(g["mat"], "层板材料"),
        ]

    def theory_check(self, params: Dict) -> Dict:
        g = self._geom(params)
        L, t, b, E = g["L"], g["t"], g["b"], g["E"]
        inertia = b * t ** 3 / 12.0
        section_modulus = b * t ** 2 / 6.0
        delta = g["P"] * L ** 3 / (48.0 * E * inertia)
        sigma_mid = (g["P"] * L / 4.0) / section_modulus
        return {
            "name": "简支梁跨中挠度（欧拉-伯努利梁理论）",
            "formula": "δ = PL³/(48EI)，I = bt³/12",
            "value": float(delta),
            "value_mm": float(delta * 1000.0),
            "unit": "m",
            "extras": {
                "mid_bending_stress_mpa": float(sigma_mid / 1e6),
                "inertia_m4": float(inertia),
            },
        }

    def fea_reading(self, params: Dict, result) -> float:
        """跨中截面平均挠度（m，向下为正）。"""
        g = self._geom(params)
        return abs(result.uy_at_x_section(g["L"] / 2.0, tol=1e-6))

    def extra_checks(self, params: Dict, result) -> List[Dict]:
        g = self._geom(params)
        vm_mpa = result.max_von_mises / 1e6
        delta_mm = self.fea_reading(params, result) * 1000.0
        checks = [
            _strength_check(
                "层板弯曲强度校核",
                vm_mpa,
                g["strength"],
                "MPa",
                f"σ_max(FEA) = {vm_mpa:.2f} MPa（跨中附近）≤ 强度代表值 {g['strength']} MPa",
            )
        ]
        if delta_mm > 0:
            checks.append(
                _advisory_check(
                    "跨中挠度建议值",
                    delta_mm,
                    params["length_mm"] / 250.0,
                    "mm",
                    "δ ≤ L/250（搁板/桌板挠度建议限值）",
                )
            )
        return checks


# 全部模板注册表
ALL_TEMPLATES = [
    CantileverShelfTemplate(),
    WallNailTemplate(),
    SimplySupportedPanelTemplate(),
]
TEMPLATES: Dict[str, ScenarioTemplate] = {t.id: t for t in ALL_TEMPLATES}
