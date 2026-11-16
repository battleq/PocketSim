# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""口袋物理仿真（pocketsim）：与 Android Chaquopy 共用的有限元核心包。

模块结构：
- materials  内置材料库 + 外部可写副本合并（Android 传 context.getFilesDir()）
- meshgen    结构化矩形三角网格与粗/中/细三档网格档位
- solver     2D 线弹性有限元求解器（scikit-fem，二阶三角形单元）
- templates  生活场景模板（悬臂搁板 / 墙上钉挂重 / 简支层板）
- frame      2D 梁杆框架求解器（3 DOF/节点，矩形截面，scipy 稀疏直接法）
- frame3d    3D 空间梁框架求解器（6 DOF/节点，双向弯曲+扭转，z=0 自动退化为 2D）
- interpret  通俗化解读（安全分级、生活化类比）
- parser     离线规则解析器：一句话中文 → 模板 + 参数（无需 LLM）
- narrative  通俗"分析过程"叙述生成（确定性、离线可用）
- engine     Android 唯一入口：run_scenario / list_templates
- cli        命令行调试入口

单位约定：求解器内部统一 SI（m、Pa、N）；模板层负责 mm/MPa 与 SI 的换算。
"""

__version__ = "1.0.0"


def __getattr__(name):
    """按需懒加载子模块属性（避免 import 包即触发 numpy/skfem 导入开销）。"""
    if name in ("run_scenario", "list_templates"):
        from . import engine
        return getattr(engine, name)
    if name == "MaterialsLibrary":
        from .materials import MaterialsLibrary
        return MaterialsLibrary
    if name == "parse_problem":
        from .parser import parse_problem
        return parse_problem
    if name in ("frame", "frame3d"):
        import importlib
        return importlib.import_module(f".{name}", __name__)
    raise AttributeError(f"module 'pocketsim' has no attribute '{name}'")
