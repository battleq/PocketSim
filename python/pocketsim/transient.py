# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""二维准静态热-力时间历程（P5 时间变化阶段）。

场景：悬臂杆端部挂重物，环境温度先升高再降低，回答"温度变化过程中结构什么时候
最危险、变形怎么变"。

做法
----
1. 用户给的框架模型（节点/杆件/支座/恒载）作为"恒载工况"，恒载在各时刻保持不变；
2. 温度历程由参数给出：温度在 duration_s 内从 temp_base_c 升到 temp_peak_c 再回到
   temp_base_c，profile=triangle（匀速升降）或 sine（余弦平滑升降）；
3. 在 [0, duration] 上等分 steps 段取 steps+1 个时刻，逐时刻把温度换算成各杆件的
   均匀温度变化 ΔT，作为 thermal 载荷替换进模型，调用 frame.run_frame 求一次；
4. 汇总曲线（温度/最大位移/最大应力/安全系数）与最不利时刻（应力最大）与最大变形
   时刻，判定取整个历程中最严重的档位。

单位：时间 s、温度 °C、长度 mm、应力 MPa。
假设：准静态（温度变化远慢于结构振动，忽略惯性力与热冲击）、各时刻杆件均匀温度
（忽略截面温差与瞬态导热）、材料模量/强度取常温代表值、恒载不随时间变化。
"""
from __future__ import annotations

import copy
import math
import time
from typing import Any, Dict, List, Optional

from .frame import BUCKLING_FAIL, SAFETY_CAUTION, SAFETY_SAFE, run_frame
from .i18n import disp_mm, is_en, stress_mpa
from .interpret import hair_analogy
from .materials import MaterialsLibrary, load_library

MAX_STEPS = 40
MIN_STEPS = 5

PROFILES = {
    "triangle": "三角（匀速升温再降温）",
    "sine": "正弦（平滑升温再降温）",
}

PROFILES_EN = {
    "triangle": "triangle (uniform heating then cooling)",
    "sine": "sine (smooth heating then cooling)",
}

VERDICT_ORDER = {"安全": 0, "注意": 1, "危险": 2}

# 判定档位 → 英文叙述用词（返回字段 verdict 仍保持中文，供 Kotlin 配色/排序）
VERDICT_EN = {"安全": "safe", "注意": "caution", "危险": "danger"}


def _err(msg: str) -> None:
    raise ValueError(msg)


def _temp_at(profile: str, base: float, peak: float, t: float, duration: float) -> float:
    """t 时刻温度：两端为 base、中点（duration/2）为 peak。"""
    if profile == "sine":
        ratio = 0.5 * (1.0 - math.cos(2.0 * math.pi * t / duration))
    else:  # triangle
        ratio = 1.0 - abs(2.0 * t / duration - 1.0)
    return base + (peak - base) * ratio


def _verdict(safety: float, buckling: float, over_temp: bool) -> str:
    if over_temp or buckling >= BUCKLING_FAIL:
        return "危险"
    if safety >= SAFETY_SAFE:
        return "安全"
    if safety >= SAFETY_CAUTION:
        return "注意"
    return "危险"


def _format_duration(duration: float) -> str:
    if is_en():
        if duration >= 3600.0:
            return f"{duration / 3600.0:.1f} h"
        if duration >= 60.0:
            return f"{duration / 60.0:.1f} min"
        return f"{duration:.0f} s"
    if duration >= 3600.0:
        return f"{duration / 3600.0:.1f} 小时"
    if duration >= 60.0:
        return f"{duration / 60.0:.1f} 分钟"
    return f"{duration:.0f} 秒"


def run_transient(spec: Dict[str, Any],
                  library: Optional[MaterialsLibrary] = None) -> Dict[str, Any]:
    """时间历程求解（温度循环 + 恒载），返回结果 dict（错误抛 ValueError）。"""
    if not isinstance(spec, dict):
        _err("时间历程参数必须是 JSON 对象")

    frame_model = spec.get("frame")
    if not isinstance(frame_model, dict):
        _err("时间历程缺少框架模型 frame（节点/杆件/支座/恒载）")

    try:
        duration = float(spec.get("duration_s", 0.0))
    except (TypeError, ValueError):
        _err("持续时间 duration_s 必须是数字（秒）")
    if not math.isfinite(duration) or duration <= 0:
        _err("持续时间 duration_s 必须大于 0（单位秒）")

    try:
        steps = int(spec.get("steps", 12))
    except (TypeError, ValueError):
        _err("时间步数 steps 必须是整数")
    if steps < MIN_STEPS or steps > MAX_STEPS:
        _err(f"时间步数 steps 需在 {MIN_STEPS}~{MAX_STEPS} 之间（当前 {steps}）")

    try:
        base = float(spec.get("temp_base_c", 20.0))
        peak = float(spec["temp_peak_c"])
    except (KeyError, TypeError, ValueError):
        _err("缺少目标温度 temp_peak_c（单位 °C，例如 150）")
    if not math.isfinite(base) or not math.isfinite(peak):
        _err("温度参数必须是有效数字")
    if abs(peak - base) < 1e-6:
        _err("目标温度与起始温度相同，温度没有变化；请给出不同的 temp_peak_c")

    profile = str(spec.get("profile", "triangle")).strip().lower()
    if profile not in PROFILES:
        _err(f"温度曲线 profile={profile} 不支持（可用：triangle 三角 / sine 正弦）")

    raw_members = frame_model.get("members") or []
    if not raw_members:
        _err("框架模型缺少杆件（members）")
    member_ids = [m.get("id", idx) for idx, m in enumerate(raw_members)]
    material_of = {m.get("id", idx): str(m.get("material", ""))
                   for idx, m in enumerate(raw_members)}

    target = spec.get("members", "all")
    if target in (None, "", "all", "ALL", "全部"):
        thermal_ids = list(member_ids)
    elif isinstance(target, (list, tuple)):
        if not target:
            _err("members 列表为空；如全部杆件都随温度变化请填 all")
        thermal_ids = []
        for mid in target:
            try:
                key = int(mid)
            except (TypeError, ValueError):
                _err(f"温度杆件编号 {mid!r} 必须是整数")
            if key not in material_of:
                _err(f"温度杆件编号 {key} 在模型中不存在")
            if key not in thermal_ids:
                thermal_ids.append(key)
    else:
        _err("members 需为 all 或杆件编号数组（例如 [1, 2]）")

    # 原始载荷：去掉可能预先存在的温度载荷（由本历程取代），并确认存在恒载
    raw_loads = frame_model.get("loads") or []
    mech_loads = [ld for ld in raw_loads
                  if str(ld.get("type", "")).strip().lower() != "thermal"]
    had_thermal = len(mech_loads) != len(raw_loads)
    if not mech_loads:
        _err("时间历程需要至少一个恒定机械载荷（例如挂在杆端的重物），"
             "否则没有温度变化的时刻结构不受力，无法形成完整历程")

    # 材料耐温上限（取参与温度变化的杆件中最严格者）
    if library is None:
        library = load_library()
    temp_limit = float("inf")
    limit_material = ""
    for mid in thermal_ids:
        rec = library.find_material(material_of[mid])
        if rec is None:
            _err(f"材料库中没有「{material_of[mid]}」（杆件 {mid}）")
        lim = float(rec.get("max_service_temp", float("inf")))
        if lim < temp_limit:
            temp_limit = lim
            limit_material = material_of[mid]

    t_start = time.perf_counter()
    # 自由热伸长量（mm）：Σ 受影响杆件按 α·ΔT·L 估计的最大伸缩量，
    # 用于向用户解释「温度变化到底改变了什么」（静定结构不产生温度应力，
    # 应力/安全系数不随温度变化，唯一可见的热效应就是自由伸缩）
    node_of_member = {}
    for mem in frame_model.get("members") or []:
        try:
            node_of_member[int(mem["id"])] = (int(mem["i"]), int(mem["j"]))
        except (KeyError, TypeError, ValueError):
            continue
    node_xy = {}
    for nd in frame_model.get("nodes") or []:
        try:
            node_xy[int(nd.get("id"))] = (float(nd["x"]), float(nd["y"]))
        except (KeyError, TypeError, ValueError):
            continue
    alpha_of = {}
    for mid in thermal_ids:
        rec = library.find_material(material_of[mid])
        alpha_of[mid] = float(rec.get("thermal_expansion", 0.0) or 0.0) if rec else 0.0
    length_of = {}
    for mid in thermal_ids:
        ij = node_of_member.get(mid)
        if not ij or ij[0] not in node_xy or ij[1] not in node_xy:
            length_of[mid] = 0.0
            continue
        (x1, y1), (x2, y2) = node_xy[ij[0]], node_xy[ij[1]]
        length_of[mid] = math.hypot(x2 - x1, y2 - y1)

    frames: List[Dict[str, Any]] = []
    for i in range(steps + 1):
        t = duration * i / steps
        temp = _temp_at(profile, base, peak, t, duration)
        dt = temp - base
        model = copy.deepcopy(frame_model)
        model["loads"] = copy.deepcopy(mech_loads)
        if abs(dt) > 1e-9:
            for mid in thermal_ids:
                model["loads"].append(
                    {"type": "thermal", "member": mid, "delta_temp": dt})
        try:
            r = run_frame(model, library=library)
        except ValueError as exc:
            hint = "（温度变化为 0 的时刻没有热载荷）" if abs(dt) <= 1e-9 else ""
            _err(f"第 {t:.1f} 秒（温度 {temp:.1f}°C）求解失败{hint}：{exc}")

        over_temp = temp > temp_limit
        verdict = _verdict(r["safety_factor"], r["buckling_ratio"], over_temp)
        # 自由热伸长（mm）：受影响杆件中最长的 α·ΔT·L 绝对值
        elong = max(
            (abs(alpha_of[m] * dt * length_of[m]) for m in thermal_ids),
            default=0.0,
        )
        frames.append({
            "index": i,
            "t_s": round(t, 2),
            "temp_c": round(temp, 2),
            "delta_temp_c": round(dt, 2),
            "max_disp_mm": round(float(r["max_disp_mm"]), 4),
            "max_disp_desc": r["max_disp_desc"],
            "max_stress_MPa": round(float(r["max_stress_MPa"]), 4),
            "safety_factor": round(float(r["safety_factor"]), 3),
            "buckling_ratio": round(float(r["buckling_ratio"]), 4),
            "governing_member": r["governing_member"],
            "verdict": verdict,
            "over_temp": bool(over_temp),
            "thermal_elong_mm": round(float(elong), 4),
            "node_disp": r["node_disp"],
            "polylines": r["polylines"],
            "members": r["members"],
        })

    t_done = time.perf_counter()

    worst = max(frames, key=lambda f: f["max_stress_MPa"])
    peak_disp = max(frames, key=lambda f: f["max_disp_mm"])
    overall = max(frames, key=lambda f: VERDICT_ORDER[f["verdict"]])
    verdict = overall["verdict"]
    safety_min = min(frames, key=lambda f: f["safety_factor"])

    curves = {
        "t_s": [f["t_s"] for f in frames],
        "temp_c": [f["temp_c"] for f in frames],
        "max_disp_mm": [f["max_disp_mm"] for f in frames],
        "max_stress_MPa": [f["max_stress_MPa"] for f in frames],
        "safety_factor": [f["safety_factor"] for f in frames],
    }

    over_temp = any(f["over_temp"] for f in frames)
    base_step = frames[0]
    temp_effect = worst["max_stress_MPa"] - base_step["max_stress_MPa"]

    # 静定结构的典型表现：温度变化不产生温度应力，应力/安全系数几乎不随时刻变化，
    # 唯一的热效应是自由伸缩。UI 需要据此给出解释，否则用户会误以为「拖时间没反应 = 算错了」
    stress_span = max(f["max_stress_MPa"] for f in frames) - min(
        f["max_stress_MPa"] for f in frames)
    sf_span = max(f["safety_factor"] for f in frames) - min(
        f["safety_factor"] for f in frames)
    stress_constant = stress_span <= max(0.005 * max(worst["max_stress_MPa"], 1.0), 0.01) \
        and sf_span <= max(0.005 * max(frames[0]["safety_factor"], 1.0), 0.01)


    if is_en():
        mats = ", ".join(sorted({material_of[mid] for mid in member_ids}))
        members_text = ("all members" if len(thermal_ids) == len(member_ids)
                        else ", ".join(str(m) for m in thermal_ids))
        narrative = [
            f"Model: the problem is simplified to a plane frame of {len(raw_members)} "
            f"member{'s' if len(raw_members) != 1 else ''} and "
            f"{len(frame_model.get('nodes') or [])} "
            f"node{'s' if len(frame_model.get('nodes') or []) != 1 else ''} "
            f"(materials: {mats}); the permanent load stays constant at every instant, and "
            f"{steps + 1} instants are computed.",
            f"Temperature history: {PROFILES_EN[profile]}, rising from {base:.0f}°C to "
            f"{peak:.0f}°C and back to {base:.0f}°C over {_format_duration(duration)}; members "
            f"affected by temperature: {members_text}.",
            f"Worst instant: t = {worst['t_s']:.0f} s ({worst['temp_c']:.0f}°C), with maximum "
            f"stress {stress_mpa(worst['max_stress_MPa'])} (member "
            f"{worst['governing_member']}) and safety factor {worst['safety_factor']:.2f}; "
            f"verdict {VERDICT_EN[worst['verdict']]}.",
            f"The temperature change moves the maximum stress from "
            f"{stress_mpa(base_step['max_stress_MPa'])} at the start to "
            f"{stress_mpa(worst['max_stress_MPa'])} "
            f"({'an increase' if temp_effect > 0 else 'a decrease'} of "
            f"{stress_mpa(abs(temp_effect))}).",
            f"Maximum deformation occurs at t = {peak_disp['t_s']:.0f} s "
            f"({peak_disp['temp_c']:.0f}°C): {disp_mm(peak_disp['max_disp_mm'])} "
            f"({peak_disp['max_disp_desc']}); "
            f"{hair_analogy(peak_disp['max_disp_mm'])}",
        ]
        if len(frames) > 1 and safety_min["index"] != worst["index"]:
            narrative.append(
                f"The safety factor is lowest at t = {safety_min['t_s']:.0f} s "
                f"({safety_min['temp_c']:.0f}°C): {safety_min['safety_factor']:.2f}.")
        if over_temp:
            narrative.append(
                f"Temperature warning: the peak {peak:.0f}°C exceeds the long-term service "
                f"limit of {temp_limit:.0f}°C for {limit_material}; above this limit the "
                f"material strength drops markedly, so stress results at high temperature are "
                f"no longer reliable.")
        narrative.append(
            "Heating a restrained member produces compressive stress, and cooling a restrained "
            "member produces tensile stress; parts free to expand only change shape and develop "
            "no thermal stress.")
        narrative.append(
            "This is a quasi-static approximation: temperature changes slowly, each member has "
            "a uniform temperature, and thermal shock and inertia are ignored; the permanent "
            "load and material properties stay constant throughout. Results are for everyday "
            "reference only; consult a professional engineer for anything involving personal or "
            "structural safety.")

        assumptions = [
            "Quasi-static thermo-mechanical coupling: temperature changes far slower than "
            "structural vibration, so inertia and thermal shock are neglected",
            "Every member has a uniform temperature at each instant; through-section "
            "temperature gradients and transient heat conduction are ignored",
            "Elastic modulus, strength and thermal expansion coefficients use room-temperature "
            "representative values and are not reduced with temperature (over-temperature is "
            "flagged separately)",
            "Mechanical permanent loads (weights, distributed loads, etc.) remain constant "
            "throughout the history",
            "Linear-elastic small deformation; member self-weight and shear deformation are "
            "neglected",
        ]
        if had_thermal:
            assumptions.append(
                "Any pre-existing thermal loads in the model were replaced by this temperature "
                "history")
    else:
        mats = "、".join(sorted({material_of[mid] for mid in member_ids}))
        narrative = [
            f"建模：把问题简化为 {len(raw_members)} 根杆件、{len(frame_model.get('nodes') or [])} "
            f"个节点的平面框架（材料：{mats}），恒载在各时刻保持不变，"
            f"共计算 {steps + 1} 个时刻。",
            f"温度历程：{PROFILES[profile]}，{_format_duration(duration)}内从 {base:.0f}°C "
            f"升到 {peak:.0f}°C 再回到 {base:.0f}°C；"
            f"参与温度变化的杆件：{'全部' if len(thermal_ids) == len(member_ids) else '、'.join(str(m) for m in thermal_ids)}。",
            f"最不利时刻在第 {worst['t_s']:.0f} 秒（{worst['temp_c']:.0f}°C）："
            f"最大应力 {worst['max_stress_MPa']:.2f} MPa（杆件 {worst['governing_member']}），"
            f"安全系数 {worst['safety_factor']:.2f}，判定{worst['verdict']}。",
            f"温度变化使最大应力由起始的 {base_step['max_stress_MPa']:.2f} MPa 变化到 "
            f"{worst['max_stress_MPa']:.2f} MPa（{'增加' if temp_effect > 0 else '减少'}"
            f" {abs(temp_effect):.2f} MPa）。",
            f"最大变形出现在第 {peak_disp['t_s']:.0f} 秒（{peak_disp['temp_c']:.0f}°C）："
            f"{peak_disp['max_disp_mm']:.3f} 毫米（{peak_disp['max_disp_desc']}），"
            f"{hair_analogy(peak_disp['max_disp_mm'])}",
        ]
        if len(frames) > 1 and safety_min["index"] != worst["index"]:
            narrative.append(
                f"安全系数最低出现在第 {safety_min['t_s']:.0f} 秒（{safety_min['temp_c']:.0f}°C）："
                f"{safety_min['safety_factor']:.2f}。")
        if over_temp:
            narrative.append(
                f"温度提醒：峰值 {peak:.0f}°C 已超过{limit_material}的长期耐温 "
                f"{temp_limit:.0f}°C，超出后材料强度显著下降，高温段的应力结果不再可靠。")
        narrative.append(
            "升温时受约束的杆件膨胀受阻会产生压应力，降温时收缩受阻会产生拉应力；"
            "可自由伸缩的部分只改变形状、不产生热应力。")
        narrative.append(
            "本结果是准静态近似：假设温度缓慢变化、各杆件温度均匀、忽略热冲击与惯性，"
            "恒载与材料参数在各时刻保持不变；结果仅供生活参考，涉及人身或结构安全请咨询专业工程师。")

        assumptions = [
            "准静态热-力耦合：温度变化远慢于结构振动，忽略惯性力与热冲击",
            "各时刻每根杆件温度均匀，忽略截面温差与瞬态导热过程",
            "材料弹性模量、强度与线膨胀系数取常温代表值，不随温度折减（超过耐温时单独提示）",
            "机械恒载（重物、均布载荷等）在整个历程中保持不变",
            "线弹性小变形，忽略构件自重与剪切变形",
        ]
        if had_thermal:
            assumptions.append("模型中原有的温度载荷已被本次温度历程取代")

    return {
        "kind": "transient",
        "ok": True,
        "model_name": str(spec.get("name") or frame_model.get("name")
                          or ("Temperature history analysis" if is_en()
                              else "温度历程分析")),
        "scenario": "thermal_cycle",
        "duration_s": round(duration, 2),
        "duration_desc": _format_duration(duration),
        "steps": steps,
        "n_samples": len(frames),
        "profile": profile,
        "profile_label": PROFILES_EN[profile] if is_en() else PROFILES[profile],
        "temp_base_c": round(base, 2),
        "temp_peak_c": round(peak, 2),
        "temp_amplitude_c": round(abs(peak - base), 2),
        "temp_limit_c": (round(temp_limit, 1) if math.isfinite(temp_limit) else None),
        "limit_material": limit_material,
        "thermal_members": thermal_ids,
        "n_nodes": len(frame_model.get("nodes") or []),
        "n_members": len(raw_members),
        "n_supports": len(frame_model.get("supports") or []),
        "over_temp": bool(over_temp),
        # 静定结构热应力为 0 时，各时刻应力/安全系数几乎不变；
        # UI 依据此标志给出「为什么拖时间数值不变」的力学解释
        "thermal_stress_constant": bool(stress_constant),
        "thermal_stress_note": (
            ("The structure is statically determinate, so nothing restrains thermal expansion "
             "or contraction: a uniform temperature change only makes the members expand or "
             "contract freely and creates no thermal stress, which is why stress and safety "
             "factors stay essentially constant over time — this is a valid mechanical result, "
             "not stale data. What temperature really affects is the thermal elongation (see "
             "the value below) and the overall dimensions.")
            if stress_constant else ""
        ) if is_en() else (
            "本结构没有被约束阻止热胀冷缩（静定），均匀温度变化只让杆件自由伸缩，"
            "不产生温度应力，所以各时刻的应力与安全系数基本不变——这是正常的力学结果，"
            "不是数据没有刷新。温度真正影响的是热伸长量（见下方热伸长数值）与整体尺寸。"
            if stress_constant else ""
        ),
        "max_thermal_elong_mm": round(
            max(f["thermal_elong_mm"] for f in frames), 4),
        "worst": {
            "index": worst["index"],
            "t_s": worst["t_s"],
            "temp_c": worst["temp_c"],
            "max_stress_MPa": worst["max_stress_MPa"],
            "max_disp_mm": worst["max_disp_mm"],
            "safety_factor": worst["safety_factor"],
            "governing_member": worst["governing_member"],
            "verdict": worst["verdict"],
        },
        "peak_disp": {
            "index": peak_disp["index"],
            "t_s": peak_disp["t_s"],
            "temp_c": peak_disp["temp_c"],
            "max_disp_mm": peak_disp["max_disp_mm"],
            "max_disp_desc": peak_disp["max_disp_desc"],
        },
        "safety_min": {
            "index": safety_min["index"],
            "t_s": safety_min["t_s"],
            "temp_c": safety_min["temp_c"],
            "safety_factor": safety_min["safety_factor"],
        },
        "max_disp_mm": peak_disp["max_disp_mm"],
        "max_stress_MPa": worst["max_stress_MPa"],
        "safety_factor": safety_min["safety_factor"],
        "verdict": verdict,
        "curves": curves,
        "frames": frames,
        "assumptions": assumptions,
        "narrative": narrative,
        "timing": {"total_ms": round((t_done - t_start) * 1000.0, 1)},
    }
