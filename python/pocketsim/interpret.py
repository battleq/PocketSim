# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""通俗化解读：安全分级与生活化类比（全部输出中文通俗字符串）。

面向非专业用户：把应力、安全系数、变形量翻译成日常生活语言。
"""
from __future__ import annotations

from typing import Tuple

from .i18n import is_en

# 安全分级阈值（安全系数 = 强度代表值 / 最大 von Mises 应力）
SAFETY_SAFE_SF = 2.0      # n ≥ 2 视为安全
SAFETY_CAUTION_SF = 1.2   # 1.2 ≤ n < 2 注意；n < 1.2 危险
# 安全系数显示上限（避免 JSON 出现 Infinity）
SF_CAP = 999.0


def safety_class(von_mises_max: float, strength: float) -> Tuple[str, float]:
    """按最大 von Mises 应力与材料强度代表值分级。

    两参数需同单位（如均为 MPa）。返回 (等级, 安全系数 n=强度/应力)：
    - n ≥ 2    → "安全"
    - 1.2 ≤ n < 2 → "注意"
    - n < 1.2  → "危险"
    应力为零或负值视为无受力，返回 ("安全", 999.0)。
    """
    if strength is None or strength <= 0:
        raise ValueError(f"材料强度代表值必须为正，当前 {strength!r}")
    if von_mises_max is None or von_mises_max <= 0:
        return "安全", SF_CAP
    n = strength / float(von_mises_max)
    if n >= SAFETY_SAFE_SF:
        label = "安全"
    elif n >= SAFETY_CAUTION_SF:
        label = "注意"
    else:
        label = "危险"
    return label, min(n, SF_CAP)


def life_analogy(deflection_mm: float) -> str:
    """把变形量翻译成生活化类比（输入为毫米）。"""
    if deflection_mm is None or deflection_mm < 0:
        raise ValueError("变形量必须为非负数（mm）")
    if deflection_mm < 0.1:
        return "比一根头发丝（约 0.07 毫米）还小，肉眼基本看不出来"
    if deflection_mm < 1.0:
        return "大约几根头发丝叠起来的厚度，凑近才看得出来"
    if deflection_mm < 5.0:
        return "肉眼可见的轻微弯曲，类似把塑料尺压弯的样子"
    return "明显弯曲，类似跳水板下压的弧度，建议检查支撑"


def hair_analogy(deflection_mm: float) -> str:
    """框架模式变形量的头发丝类比（头发丝直径按 0.07 mm）。

    极小变形（< 0.07mm）输出"1/N 根"而不是"0.0 根"，避免"约 0.0 根头发丝"这类怪文案。
    """
    hair = float(deflection_mm) / 0.07
    if hair < 1e-3:
        return ("essentially zero — completely invisible to the naked eye" if is_en()
                else "几乎为零，肉眼完全看不出来")
    if hair >= 100:
        return "visible to the naked eye" if is_en() else "肉眼可辨"
    if hair >= 1.0:
        return (f"about {hair:.1f} hair diameters" if is_en()
                else f"约 {hair:.1f} 根头发丝直径")
    return (f"less than one hair diameter (~1/{max(1, round(1.0 / hair))})" if is_en()
            else f"不足一根头发丝直径（约 1/{max(1, round(1.0 / hair))}）")


def safety_factor_analogy(n: float) -> str:
    """把安全系数翻译成生活化类比。"""
    if n is None:
        raise ValueError("安全系数不能为空")
    if n > 3.0:
        return "余量充足，像电梯限载 10 人却只坐了 3 人，很放心"
    if n > 2.0:
        return "余量比较充足，像电梯限载 10 人只坐了 5 人，稳"
    if n >= 1.2:
        return "余量偏紧，接近满载的电梯，建议少放点东西或加厚加固"
    return "已经超载，像电梯响起超载警报，应立即减载或加强支撑"


def deflection_summary(deflection_mm: float, safety_label: str) -> str:
    """一句话汇总（引擎解读字段使用）。"""
    return f"最大变形约 {deflection_mm:.2f} 毫米，整体安全等级：{safety_label}。"
