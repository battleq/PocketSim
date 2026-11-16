"""
面向用户文案的语言 / 单位制辅助（仅影响叙述与结论文字，内部计算始终使用 SI）。

调用链：bridge.run_* 收到 Kotlin 传入的 lang / units 后调用 [set_locale]，
各求解器在生成 narrative / advice / assumptions 等文案时使用 [t] 与下方量纲格式化函数。
中文路径保持原有字符串不变（截图测试逐字节比对），英文路径按单位制换算。
"""

from typing import Any

_LANG = "zh"
_UNITS = "metric"

# 精确换算系数（与 Kotlin UnitFormat.kt 保持一致）
IN_PER_MM = 1.0 / 25.4
FT_PER_M = 3.280839895
PSI_PER_MPA = 145.0377377302
LBF_PER_N = 1.0 / 4.4482216152605
PCF_PER_KGM3 = 1.0 / 16.01846337396014
PSF_PER_PA = 0.020885434
MPH_PER_MS = 2.236936


def set_locale(lang: str = "zh", units: str = "metric") -> None:
    """由 bridge 在每次求解入口调用，切换本进程后续文案语言与单位制。"""
    global _LANG, _UNITS
    _LANG = "en" if str(lang or "").strip().lower().startswith("en") else "zh"
    _UNITS = "imperial" if str(units or "").strip().lower() in ("imperial", "us", "in") else "metric"


def is_en() -> bool:
    return _LANG == "en"


def is_imperial() -> bool:
    return _UNITS == "imperial"


def t(zh: str, en: str) -> str:
    """按当前语言二选一。"""
    return en if _LANG == "en" else zh


def num(v: float) -> str:
    """数值格式化（与 Kotlin Units.num 规则一致）。"""
    try:
        v = float(v)
    except (TypeError, ValueError):
        return "—"
    if v != v or v in (float("inf"), float("-inf")):
        return "—"
    if v == 0:
        return "0"
    a = abs(v)
    if a >= 1e6 or a < 1e-3:
        return f"{v:.2e}"
    if a >= 100:
        return f"{v:.0f}"
    if a >= 10:
        return f"{v:.1f}"
    return f"{v:.2f}"


def _q(value: float, unit_si: str, unit_imp: str, factor: float) -> str:
    if is_imperial():
        return f"{num(value * factor)} {unit_imp}"
    return f"{num(value)} {unit_si}"


def length_mm(v: float) -> str:
    return _q(v, "mm", "in", IN_PER_MM)


def length_unit() -> str:
    """当前单位制下的长度单位符号（"in"/"mm"）。"""
    return "in" if is_imperial() else "mm"


def dim_mm(v: float) -> str:
    """截面尺寸数字（不含单位，按当前单位制换算）。"""
    return num(float(v) * IN_PER_MM) if is_imperial() else num(float(v))


def length_m(v: float) -> str:
    return _q(v, "m", "ft", FT_PER_M)


def disp_mm(v: float) -> str:
    if is_imperial():
        mil = v / 0.0254
        return f"{num(mil)} mil" if mil < 1.0 else f"{num(v * IN_PER_MM)} in"
    return f"{num(v * 1000.0)} μm" if abs(v) < 0.01 else f"{num(v)} mm"


def stress_mpa(v: float) -> str:
    return _q(v, "MPa", "psi", PSI_PER_MPA)


def force_n(v: float) -> str:
    if is_imperial():
        lbf = v * LBF_PER_N
        return f"{num(lbf / 1000.0)} kip" if abs(lbf) >= 1000.0 else f"{num(lbf)} lbf"
    return f"{num(v)} N"


def moment_nmm(v: float) -> str:
    return _q(v, "N·mm", "lbf·in", 1.0 / 112.9997)


def density_kgm3(v: float) -> str:
    return _q(v, "kg/m³", "pcf", PCF_PER_KGM3)


def pressure_pa(v: float) -> str:
    return _q(v, "Pa", "psf", PSF_PER_PA)


def speed_ms(v: float) -> str:
    return _q(v, "m/s", "mph", MPH_PER_MS)


def linear_nmm(v: float) -> str:
    return _q(v, "N/mm", "lbf/in", 5.710147)


def area_mm2(v: float) -> str:
    return _q(v, "mm²", "in²", 1.0 / 645.16)


def _fmt_any(v: Any) -> str:
    return num(v) if isinstance(v, (int, float)) else str(v)
