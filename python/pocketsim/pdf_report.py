# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""框架分析结果导出为 PDF 报告。

使用 reportlab 生成单页/多页 PDF，包含：模型信息、安全结论、关键指标、
杆件受力明细、温度分区（如有）、稳定性提醒、通俗分析过程、免责声明。

CJK 字体策略：优先使用 reportlab 内置的简体中文 CID 字体（STSong-Light，
无需外部字体文件，Android/Chaquopy 上稳定可用）；若内置字体注册失败，
再尝试系统 TTF/TTC 字体；都失败才回退 Helvetica（中文会缺字，但不崩溃）。
"""
from __future__ import annotations

import os
from typing import Any, Dict, List, Optional

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

from .i18n import IN_PER_MM, PSI_PER_MPA, is_en, is_imperial


# 候选系统 CJK 字体路径（仅作内置字体的后备；.otf 因 PostScript outlines 不被 TTFont 支持，已剔除）
_CJK_FONT_CANDIDATES = [
    # Android 系统字体
    "/system/fonts/NotoSansCJK-Regular.ttc",
    "/system/fonts/NotoSansCJK.ttc",
    "/system/fonts/DroidSansFallback.ttf",
    # 构建机 / 沙箱
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
]

_FONT_NAME = "PocketSimCJK"
_font_registered = False
_active_font: Optional[str] = None


def _register_cjk_font() -> str:
    """注册一个可用的 CJK 字体到 reportlab，返回字体名。

    优先级：
    1. reportlab 内置简体中文 CID 字体 STSong-Light（无需外部文件，最稳）
    2. 系统 TTF/TTC 字体
    3. 回退 Helvetica（中文缺字，但保证不崩溃）
    """
    global _font_registered, _active_font
    if _font_registered and _active_font:
        return _active_font
    from reportlab.pdfbase import pdfmetrics

    # 1) 内置 CID 字体（无需外部文件，Android 上可靠）
    try:
        from reportlab.pdfbase.cidfonts import UnicodeCIDFont
        pdfmetrics.registerFont(UnicodeCIDFont("STSong-Light"))
        pdfmetrics.registerFontFamily(
            "STSong-Light", normal="STSong-Light",
            bold="STSong-Light", italic="STSong-Light", boldItalic="STSong-Light",
        )
        _font_registered = True
        _active_font = "STSong-Light"
        return _active_font
    except Exception:
        pass

    # 2) 系统 TTF/TTC 字体
    from reportlab.pdfbase.ttfonts import TTFont
    for path in _CJK_FONT_CANDIDATES:
        if os.path.isfile(path):
            try:
                pdfmetrics.registerFont(TTFont(_FONT_NAME, path, subfontIndex=0))
                _font_registered = True
                _active_font = _FONT_NAME
                return _active_font
            except Exception:
                continue

    # 3) 回退 Helvetica（中文会缺字，但保证不崩溃）
    _font_registered = True
    _active_font = "Helvetica"
    return _active_font


def _fmt(v: Any, ndigits: int = 2) -> str:
    """数值格式化（None → "—"）。"""
    if v is None:
        return "—"
    try:
        f = float(v)
    except (TypeError, ValueError):
        return str(v)
    if abs(f) >= 1000:
        return f"{f:,.{ndigits}f}"
    return f"{f:.{ndigits}f}"


def _l(zh: str, en: str) -> str:
    """按当前语言二选一（与 App 界面语言保持一致）。"""
    return en if is_en() else zh


# Python 求解器返回的结论词固定为中文，报告里按语言转换
_VERDICT_EN = {"安全": "safe", "注意": "caution", "危险": "danger"}


def _verdict_text(verdict: str) -> str:
    if not is_en():
        return verdict
    return _VERDICT_EN.get(verdict, verdict)


# ---- 单位（英文界面按用户选择的单位制换算；内部计算仍为 SI） ----------------
def _len_mm(v: float, ndigits: int = 2) -> str:
    if is_imperial():
        return f"{_fmt(v * IN_PER_MM, ndigits)} in"
    return f"{_fmt(v, ndigits)} mm"


def _stress(v: float, ndigits: int = 2) -> str:
    if is_imperial():
        return f"{_fmt(v * PSI_PER_MPA, ndigits)} psi"
    return f"{_fmt(v, ndigits)} MPa"


def _axial(v_n: float, ndigits: int = 2) -> str:
    if is_imperial():
        return f"{_fmt(v_n / 4448.2216, ndigits)} kip"
    return f"{_fmt(v_n / 1000.0, ndigits)} kN"


def _moment(v_nmm: float, ndigits: int = 2) -> str:
    if is_imperial():
        return f"{_fmt(v_nmm * 7.375621e-7, ndigits)} kip·ft"
    return f"{_fmt(v_nmm / 1e6, ndigits)} kN·m"


def _torsion(v_nm: float, ndigits: int = 2) -> str:
    if is_imperial():
        return f"{_fmt(v_nm * 0.7375621, ndigits)} lbf·ft"
    return f"{_fmt(v_nm / 1e3, ndigits)} N·m"


def _build_styles() -> Dict[str, ParagraphStyle]:
    """构建段落样式（基于 CJK 字体）。"""
    font = _register_cjk_font()
    styles = getSampleStyleSheet()
    return {
        "title": ParagraphStyle(
            "title", parent=styles["Title"], fontName=font,
            fontSize=18, leading=24, alignment=1, spaceAfter=6,
        ),
        "h2": ParagraphStyle(
            "h2", parent=styles["Heading2"], fontName=font,
            fontSize=13, leading=18, spaceBefore=10, spaceAfter=4,
            textColor=colors.HexColor("#37474F"),
        ),
        "body": ParagraphStyle(
            "body", parent=styles["BodyText"], fontName=font,
            fontSize=10, leading=15, spaceAfter=3,
        ),
        "small": ParagraphStyle(
            "small", parent=styles["BodyText"], fontName=font,
            fontSize=8, leading=11, textColor=colors.HexColor("#616161"),
        ),
        "verdict": ParagraphStyle(
            "verdict", parent=styles["Title"], fontName=font,
            fontSize=16, leading=22, alignment=1, spaceAfter=4,
        ),
    }


def _verdict_color(verdict: str) -> colors.Color:
    if verdict == "危险":
        return colors.HexColor("#C62828")
    if verdict == "注意":
        return colors.HexColor("#EF6C00")
    return colors.HexColor("#2E7D32")


def export_frame_pdf(
    result: Dict[str, Any],
    model: Optional[Dict[str, Any]] = None,
    filepath: str = "pocketsim_report.pdf",
) -> str:
    """把框架求解结果导出为 PDF，返回文件路径。

    Args:
        result: frame.run_frame 的输出 dict
        model:  原始模型 dict（可选，用于在报告中列出杆件连接信息）
        filepath: 输出 PDF 路径
    """
    styles = _build_styles()
    doc = SimpleDocTemplate(
        filepath, pagesize=A4,
        leftMargin=15 * mm, rightMargin=15 * mm,
        topMargin=15 * mm, bottomMargin=15 * mm,
        title=_l(f"口袋仿真分析报告 - {result.get('model_name', '')}",
                 f"PocketSim analysis report - {result.get('model_name', '')}"),
        author="口袋仿真 PocketSim",
    )

    story: List[Any] = []
    flow = story.append

    # ---- 标题 ----
    flow(Paragraph(result.get("model_name") or _l("框架分析报告", "Frame analysis report"),
                   styles["title"]))
    flow(Paragraph(
        _l(
            f"{result.get('n_nodes', 0)} 节点 · {result.get('n_members', 0)} 杆件 · "
            f"{result.get('n_supports', 0)} 支座 · 求解 {_fmt(result.get('solve_ms', 0), 1)} ms",
            f"{result.get('n_nodes', 0)} nodes · {result.get('n_members', 0)} members · "
            f"{result.get('n_supports', 0)} supports · solved in {_fmt(result.get('solve_ms', 0), 1)} ms",
        ),
        styles["small"],
    ))
    flow(Spacer(1, 4 * mm))

    # ---- 安全结论 ----
    verdict = result.get("verdict", "")
    sf = result.get("safety_factor", 0.0)
    verdict_text = _l(f"安全结论：{verdict}", f"Verdict: {_verdict_text(verdict)}")
    if sf and sf > 0:
        verdict_text += _l(f"　（安全系数 {_fmt(sf)}）", f" (safety factor {_fmt(sf)})")
    verdict_para = Paragraph(verdict_text, styles["verdict"])
    verdict_para.textColor = _verdict_color(verdict)
    flow(verdict_para)
    flow(Spacer(1, 3 * mm))

    # ---- 关键指标表 ----
    flow(Paragraph(_l("关键指标", "Key metrics"), styles["h2"]))
    metrics = [
        [_l("最大变形", "Max displacement"), _len_mm(result.get('max_disp_mm', 0)),
         result.get("max_disp_desc", "")],
        [_l("最大应力", "Max stress"), _stress(result.get('max_stress_MPa', 0)),
         _l(f"控制杆：杆件 {result.get('governing_member', '—')}",
            f"governing member {result.get('governing_member', '—')}")],
        [_l("安全系数", "Safety factor"), _fmt(sf) if sf else "—", _verdict_text(verdict)],
    ]
    if result.get("buckling_ratio", 0) > 0:
        metrics.append([
            _l("屈曲利用率", "Buckling utilization"),
            f"{_fmt(result['buckling_ratio'] * 100, 1)}%",
            result.get("buckling_note", "") or _l("无", "none"),
        ])
    flow(_metrics_table(metrics, styles))
    flow(Spacer(1, 2 * mm))

    # ---- 温度分区 ----
    thermal = result.get("thermal")
    if thermal and thermal.get("has_thermal"):
        flow(Paragraph(_l("温度分析", "Thermal analysis"), styles["h2"]))
        th_rows = [
            [_l("最大温度变化", "Max temperature change"),
             _l(f"{_fmt(thermal.get('max_delta_temp', 0))} °C（基准 20°C）",
                f"{_fmt(thermal.get('max_delta_temp', 0))} °C (base 20°C)")],
        ]
        by_member = thermal.get("by_member") or {}
        if by_member:
            parts = []
            for mid in sorted(by_member.keys(), key=lambda x: int(x) if x.isdigit() else 0):
                dt = by_member[mid]
                sign = "+" if dt > 0 else ""
                parts.append(_l(f"杆{mid} {sign}{_fmt(dt)}°C", f"member {mid} {sign}{_fmt(dt)}°C"))
            th_rows.append([_l("各杆 ΔT", "ΔT by member"), _l("、", ", ").join(parts)])
        if thermal.get("over_temp"):
            th_rows.append([_l("超温警告", "Over-temperature warning"),
                            thermal.get("note", "") or
                            _l("有杆件超出材料耐温", "some members exceed the material service temperature")])
        flow(_kv_table(th_rows, styles, highlight=thermal.get("over_temp", False)))
        flow(Spacer(1, 2 * mm))

    # ---- 杆件受力明细 ----
    flow(Paragraph(_l("杆件受力明细", "Member forces"), styles["h2"]))
    members = result.get("members", [])
    if members:
        is3d = result.get("kind") == "frame3d"
        if is3d:
            mem_data = [_l(
                ["杆件", "材料", "截面", "轴力", "My", "Mz", "扭矩", "等效应力", "利用率"],
                ["Member", "Material", "Section", "Axial", "My", "Mz", "Torsion",
                 "Eq. stress", "Utilization"],
            )]
        else:
            mem_data = [_l(
                ["杆件", "材料", "截面", "轴力", "弯矩", "应力", "利用率"],
                ["Member", "Material", "Section", "Axial", "Moment", "Stress", "Utilization"],
            )]
        if is3d:
            mem_data[0][3] += _l("(kN)", " (kip)")
            mem_data[0][4] += _l("(kN·m)", " (kip·ft)")
            mem_data[0][5] += _l("(kN·m)", " (kip·ft)")
            mem_data[0][6] += _l("(N·m)", " (lbf·ft)")
            mem_data[0][7] += _l("(MPa)", " (psi)")
        else:
            mem_data[0][3] += _l("(kN)", " (kip)")
            mem_data[0][4] += _l("(kN·m)", " (kip·ft)")
            mem_data[0][5] += _l("(MPa)", " (psi)")
        for m in members:
            util = m.get("util")
            util_str = f"{_fmt(util * 100, 1)}%" if util is not None else "—"
            if is3d:
                mem_data.append([
                    str(m.get("id", "")),
                    str(m.get("material", "")),
                    str(m.get("section", "")),
                    _axial(float(m.get("N_N", 0) or 0)),
                    _moment(float(m.get("My_max_Nmm", 0) or 0)),
                    _moment(float(m.get("Mz_max_Nmm", 0) or 0)),
                    _torsion(float(m.get("torsion_Nmm", 0) or 0) / 1e3),
                    _stress(float(m.get("sigma_vm_MPa", m.get("sigma_MPa", 0)) or 0)),
                    util_str,
                ])
            else:
                mem_data.append([
                    str(m.get("id", "")),
                    str(m.get("material", "")),
                    str(m.get("section", "")),
                    _axial(float(m.get("N_N", 0) or 0)),
                    _moment(float(m.get("M_max_Nmm", 0) or 0)),
                    _stress(float(m.get("sigma_MPa", 0) or 0)),
                    util_str,
                ])
        flow(_data_table(mem_data, styles))
    flow(Spacer(1, 2 * mm))

    # ---- 稳定性提醒 ----
    if result.get("buckling_note"):
        flow(Paragraph(_l("稳定性提醒", "Stability note"), styles["h2"]))
        flow(Paragraph(result["buckling_note"], styles["body"]))
        flow(Spacer(1, 2 * mm))

    # ---- 通俗分析过程 ----
    narrative = result.get("narrative", [])
    if narrative:
        flow(Paragraph(_l("分析过程", "Analysis"), styles["h2"]))
        for para in narrative:
            flow(Paragraph(para, styles["body"]))
        flow(Spacer(1, 2 * mm))

    # ---- 免责声明 ----
    flow(Paragraph(_l("免责声明", "Disclaimer"), styles["h2"]))
    flow(Paragraph(
        _l(
            "本报告由口袋仿真（PocketSim）基于线弹性小变形假设自动生成，结果仅供参考，"
            "不能替代专业结构工程师的计算与认证。实际工程请咨询有资质的结构工程师。"
            "热分析采用每杆均匀温度假设，不含导热梯度、瞬态与热冲击效应。",
            "This report was generated automatically by PocketSim based on linear-elastic, "
            "small-deflection assumptions. Results are for reference only and do not replace "
            "the calculation and certification of a licensed structural engineer. Thermal "
            "analysis assumes a uniform temperature per member and neglects conduction "
            "gradients, transients, and thermal shock.",
        ),
        styles["small"],
    ))

    doc.build(story)
    return filepath


def export_solid_pdf(
    result: Dict[str, Any],
    model: Optional[Dict[str, Any]] = None,
    filepath: str = "pocketsim_solid_report.pdf",
) -> str:
    """把通用三维实体（box/cylinder/plate）求解结果导出为 PDF，返回文件路径。

    Args:
        result: solid.run_solid_generic 的输出 dict
        model:  原始模型 dict（可选，用于列出约束/载荷与网格档位）
        filepath: 输出 PDF 路径
    """
    styles = _build_styles()
    doc = SimpleDocTemplate(
        filepath, pagesize=A4,
        leftMargin=15 * mm, rightMargin=15 * mm,
        topMargin=15 * mm, bottomMargin=15 * mm,
        title=_l(f"口袋仿真三维实体分析报告 - {result.get('model_name', '')}",
                 f"PocketSim 3D solid analysis report - {result.get('model_name', '')}"),
        author="口袋仿真 PocketSim",
    )

    story: List[Any] = []
    flow = story.append

    metrics = result.get("metrics") or {}
    mesh = result.get("mesh") or {}

    # ---- 标题 ----
    flow(Paragraph(result.get("model_name") or _l("三维实体分析报告", "3D solid analysis report"),
                   styles["title"]))
    flow(Paragraph(
        _l(
            f"{mesh.get('n_vertices', 0)} 节点 · {mesh.get('n_elements', 0)} 四面体 · "
            f"自由度 {mesh.get('dof', 0)} · 求解 {_fmt((result.get('timing') or {}).get('total_ms', 0), 1)} ms",
            f"{mesh.get('n_vertices', 0)} nodes · {mesh.get('n_elements', 0)} tetrahedra · "
            f"{mesh.get('dof', 0)} DOF · solved in {_fmt((result.get('timing') or {}).get('total_ms', 0), 1)} ms",
        ),
        styles["small"],
    ))
    flow(Spacer(1, 4 * mm))

    # ---- 安全结论 ----
    verdict = str(metrics.get("verdict", ""))
    sf = float(metrics.get("safety_factor", 0.0) or 0.0)
    verdict_text = _l(f"安全结论：{verdict or '—'}",
                     f"Verdict: {_verdict_text(verdict) if verdict else '—'}")
    if sf > 0:
        verdict_text += _l(f"　（安全系数 {_fmt(sf)}）", f" (safety factor {_fmt(sf)})")
    verdict_para = Paragraph(verdict_text, styles["verdict"])
    verdict_para.textColor = _verdict_color(verdict)
    flow(verdict_para)
    flow(Spacer(1, 3 * mm))

    # ---- 关键指标表 ----
    flow(Paragraph(_l("关键指标", "Key metrics"), styles["h2"]))
    flow(_metrics_table([
        [_l("最大位移", "Max displacement"), _len_mm(metrics.get('max_disp_mm', 0), 4),
         _l(f"节点 {metrics.get('max_disp_node', '—')}",
            f"node {metrics.get('max_disp_node', '—')}")],
        [_l("最大 von Mises 应力", "Max von Mises stress"),
         _stress(metrics.get('max_von_mises_MPa', 0)),
         _l(f"拉 {_stress(metrics.get('max_tension_MPa', 0))} / 压 {_stress(metrics.get('max_compression_MPa', 0))}",
            f"tension {_stress(metrics.get('max_tension_MPa', 0))} / compression {_stress(metrics.get('max_compression_MPa', 0))}")],
        [_l("安全系数", "Safety factor"), _fmt(sf) if sf else "—",
         _l(f"拉 {_fmt(metrics.get('sf_tension', 0))} / 压 {_fmt(metrics.get('sf_compression', 0))}",
            f"tension {_fmt(metrics.get('sf_tension', 0))} / compression {_fmt(metrics.get('sf_compression', 0))}")],
    ], styles))
    flow(Spacer(1, 2 * mm))

    # ---- 几何与材料 ----
    flow(Paragraph(_l("几何与材料", "Geometry and material"), styles["h2"]))
    geo = result.get("geometry") or {}
    material = result.get("material") or "—"
    mp = result.get("material_params") or {}
    geo_rows = [
        [_l("图元", "Primitive"), str(geo.get("type", "—"))],
        [_l("长度 / 高度 / 宽度 / 厚度", "L / H / W / T"),
         " / ".join(
             _len_mm(geo.get(k) * 1000, 0)
             for k in ("length_m", "height_m", "width_m", "thickness_m")
             if geo.get(k) is not None
         ) or _l(f"半径 {_len_mm((geo.get('radius_m') or 0) * 1000, 0)}",
                 f"radius {_len_mm((geo.get('radius_m') or 0) * 1000, 0)}")],
        [_l("体积 / 自重", "Volume / self-weight"),
         _l(f"{_fmt(geo.get('volume_m3', 0), 4)} m³ / {_fmt(geo.get('weight_kN', 0))} kN",
            f"{_fmt(geo.get('volume_m3', 0), 4)} m³ / {_fmt(float(geo.get('weight_kN', 0) or 0) * 1000.0 / 4448.2216)} kip")],
        [_l("材料", "Material"),
         _l(f"{material}（E {_fmt(mp.get('E_MPa', 0), 0)} MPa，ν {_fmt(mp.get('nu', 0))}）",
            f"{material} (E {_stress(mp.get('E_MPa', 0), 0)}, ν {_fmt(mp.get('nu', 0))})")],
    ]
    if geo.get("radius_m") is not None:
        geo_rows.insert(1, [_l("半径", "Radius"), _len_mm(geo["radius_m"] * 1000, 0)])
    flow(_kv_table(geo_rows, styles))
    flow(Spacer(1, 2 * mm))

    # ---- 强度校核 ----
    checks = result.get("checks") or []
    if checks:
        flow(Paragraph(_l("强度校核", "Strength checks"), styles["h2"]))
        rows = [_l(["校核项", "作用", "抗力", "安全系数", "结论"],
                   ["Check", "Demand", "Capacity", "Safety factor", "Result"])]
        for c in checks:
            rows.append([
                str(c.get("name", "")),
                f"{_fmt(c.get('value', 0))} {c.get('unit', '')}",
                f"{_fmt(c.get('limit', 0))} {c.get('unit', '')}",
                _fmt(c.get("safety_factor", 0)),
                _l("通过", "pass") if c.get("pass") else _l("不通过", "fail"),
            ])
        flow(_data_table(rows, styles))
        flow(Spacer(1, 2 * mm))

    # ---- 支反力 ----
    reactions = result.get("reactions") or {}
    flow(Paragraph(_l("支反力", "Reactions"), styles["h2"]))
    flow(_kv_table([
        ["Fx / Fy / Fz",
         _l(f"{_fmt(reactions.get('fx_N', 0))} / {_fmt(reactions.get('fy_N', 0))} / "
            f"{_fmt(reactions.get('fz_N', 0))} N",
            f"{_fmt(float(reactions.get('fx_N', 0) or 0) / 4448.2216)} / "
            f"{_fmt(float(reactions.get('fy_N', 0) or 0) / 4448.2216)} / "
            f"{_fmt(float(reactions.get('fz_N', 0) or 0) / 4448.2216)} kip")],
    ], styles))
    flow(Spacer(1, 2 * mm))

    # ---- 计算假定 ----
    assumptions = result.get("assumptions") or []
    if assumptions:
        flow(Paragraph(_l("计算假定", "Assumptions"), styles["h2"]))
        for a in assumptions:
            flow(Paragraph(f"· {a}", styles["body"]))
        flow(Spacer(1, 2 * mm))

    # ---- 免责声明 ----
    flow(Paragraph(_l("免责声明", "Disclaimer"), styles["h2"]))
    flow(Paragraph(
        _l(
            "本报告由口袋仿真（PocketSim）基于线弹性小变形假设与四节点四面体单元自动生成，"
            "结果仅供日常生活场景参考，不能替代专业工程分析。涉及人身或结构安全请咨询有资质的工程师。",
            "This report was generated automatically by PocketSim based on linear-elastic, "
            "small-deflection assumptions and four-node tetrahedral elements. Results are for "
            "everyday reference only and do not replace professional engineering analysis. "
            "Consult a licensed engineer for anything involving personal or structural safety.",
        ),
        styles["small"],
    ))

    doc.build(story)
    return filepath


def _metrics_table(rows: List[List[str]], styles: Dict[str, ParagraphStyle]) -> Table:
    """关键指标表：三列 [指标, 数值, 说明]。"""
    data = [[Paragraph(r[0], styles["body"]),
             Paragraph(f"<b>{r[1]}</b>", styles["body"]),
             Paragraph(r[2], styles["small"])] for r in rows]
    t = Table(data, colWidths=[30 * mm, 35 * mm, None])
    t.setStyle(TableStyle([
        ("FONTNAME", (0, 0), (-1, -1), _register_cjk_font()),
        ("FONTSIZE", (0, 0), (-1, -1), 10),
        ("BACKGROUND", (0, 0), (0, -1), colors.HexColor("#ECEFF1")),
        ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#B0BEC5")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]))
    return t


def _kv_table(rows: List[List[str]], styles: Dict[str, ParagraphStyle],
              highlight: bool = False) -> Table:
    """键值表：两列 [键, 值]，highlight 时整行红底。"""
    data = [[Paragraph(r[0], styles["body"]), Paragraph(r[1], styles["body"])] for r in rows]
    t = Table(data, colWidths=[35 * mm, None])
    style_cmds = [
        ("FONTNAME", (0, 0), (-1, -1), _register_cjk_font()),
        ("FONTSIZE", (0, 0), (-1, -1), 10),
        ("BACKGROUND", (0, 0), (0, -1), colors.HexColor("#ECEFF1")),
        ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#B0BEC5")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]
    if highlight:
        style_cmds.append(("BACKGROUND", (1, -1), (1, -1), colors.HexColor("#FFEBEE")))
        style_cmds.append(("TEXTCOLOR", (1, -1), (1, -1), colors.HexColor("#C62828")))
    t.setStyle(TableStyle(style_cmds))
    return t


def _data_table(data: List[List[str]], styles: Dict[str, ParagraphStyle]) -> Table:
    """通用数据表（首行表头）。"""
    font = _register_cjk_font()
    styled = [[Paragraph(c, styles["body"]) for c in row] for row in data]
    t = Table(styled, repeatRows=1)
    t.setStyle(TableStyle([
        ("FONTNAME", (0, 0), (-1, -1), font),
        ("FONTSIZE", (0, 0), (-1, -1), 9),
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#37474F")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#B0BEC5")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("ALIGN", (0, 0), (-1, -1), "CENTER"),
        ("LEFTPADDING", (0, 0), (-1, -1), 4),
        ("RIGHTPADDING", (0, 0), (-1, -1), 4),
        ("TOPPADDING", (0, 0), (-1, -1), 3),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
    ]))
    return t
