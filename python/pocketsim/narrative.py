# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""通俗“分析过程”叙述生成（确定性、离线可用，不依赖 LLM）。

build_narrative(...) 把仿真结果翻译成 4~6 段非专业用户能读懂的中文：
①怎么把生活问题简化成模型；②算了什么（载荷与方法）；
③结果怎么解读（变形/应力/安全系数）；④理论公式交叉验证；
⑤逐项简化校核结论；⑥假设与免责提示。

输入均为 engine.run_scenario 内部数据（材料已解析、指标已汇总）。
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional


def _fmt(value: Any, unit: str = "") -> str:
    """数值友好显示：整数不带小数点，小数最多两位。"""
    try:
        f = float(value)
    except (TypeError, ValueError):
        return f"{value}{unit}"
    if f == int(f) and abs(f) < 1e15:
        s = f"{int(f)}"
    else:
        s = f"{f:.2f}".rstrip("0").rstrip(".")
    return f"{s}{unit}"


def _param_summary(effective_params: Dict[str, Any],
                   schema_by_key: Dict[str, Dict[str, Any]]) -> str:
    """把有效参数拼成“名称=值 单位”的摘要串（跳过空值与 None）。"""
    parts: List[str] = []
    for key, value in effective_params.items():
        spec = schema_by_key.get(key)
        if spec is None or value is None:
            continue
        unit = spec.get("unit", "")
        if unit == "-":
            unit = ""
        parts.append(f"{spec.get('label', key)} {_fmt(value)}{unit}")
    return "、".join(parts)


def build_narrative(
    template,
    effective_params: Dict[str, Any],
    metrics: Dict[str, Any],
    extra_checks: List[Dict[str, Any]],
    assumptions: List[str],
    theory_check: Optional[Dict[str, Any]],
) -> List[str]:
    """生成通俗分析过程段落（输入为 engine 内部数据，输出 4~6 段中文）。"""
    schema_by_key = {spec.key: spec.to_dict() for spec in template.param_schema}
    paragraphs: List[str] = []

    # ① 模型简化
    summary = _param_summary(effective_params, schema_by_key)
    p1 = (f"我们把你描述的「{template.name}」问题简化成了一个可以精确计算的模型："
          f"{template.description}。")
    if summary:
        p1 += f"本次采用的关键参数：{summary}。"
    paragraphs.append(p1)

    # ② 计算内容与方法
    p2 = ("计算按静载考虑——相当于重物稳稳地放着不动，不额外计算突然放下、"
          "晃动或冲击的影响；载荷按重力加速度（约 9.8 m/s²）换算成作用力施加在结构上。"
          "求解采用有限元方法：把结构划分成许多小三角块分别计算、再拼回整体，"
          "得到每一处的变形和受力。")
    paragraphs.append(p2)

    # ③ 结果解读
    delta_mm = metrics.get("max_deflection_mm")
    vm_mpa = metrics.get("max_von_mises_mpa")
    sf = metrics.get("safety_factor")
    label = metrics.get("safety_class", "")
    p3 = "计算结果显示："
    if delta_mm is not None:
        p3 += f"最大变形约 {_fmt(delta_mm)} 毫米（{metrics.get('deflection_analogy', '')}）；"
    if vm_mpa is not None:
        p3 += f"结构内部最大应力约 {_fmt(vm_mpa)} MPa；"
    if sf is not None:
        p3 += (f"相对材料强度代表值的安全系数约 {_fmt(sf)}"
               f"（{metrics.get('safety_factor_analogy', '')}），综合评级：{label}。")
    paragraphs.append(p3)

    # ④ 理论公式交叉验证
    if theory_check and theory_check.get("error_pct") is not None:
        formula = theory_check.get("formula", "")
        theory_mm = theory_check.get("value_mm")
        p4 = (f"作为交叉验证，我们同时用材料力学公式（{formula}）计算了同一位置的挠度，"
              f"理论值约 {_fmt(theory_mm)} 毫米，与有限元结果相差约 "
              f"{_fmt(theory_check['error_pct'])}%，两者吻合，说明结果可信。")
        paragraphs.append(p4)

    # ⑤ 逐项简化校核
    if extra_checks:
        items = "；".join(
            f"{c.get('name', '')}——{c.get('conclusion', '')}" for c in extra_checks
        )
        paragraphs.append(f"除了整体评估，我们还做了以下逐项校核：{items}。")

    # ⑥ 假设与免责
    highlights = "；".join(assumptions[:3]) if assumptions else "详见假设列表"
    p6 = (f"为了让计算能够进行，我们做了一些简化假设，例如：{highlights}"
          f"（完整假设见下方列表）。请特别注意：以上结果基于材料代表值与简化模型，"
          f"仅供日常生活参考，不能替代专业工程计算；涉及人身安全时请咨询专业人员。")
    paragraphs.append(p6)

    return paragraphs
