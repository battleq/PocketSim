/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import android.content.Context
import java.util.Locale

/**
 * 单位制：内部计算与 Python 求解一律使用公制 SI（mm / N / MPa / kg / m³），
 * 仅在界面的输入、输出边界按 [LocaleStore.unitSystem] 换算，避免求解链路出现两套单位。
 */
enum class UnitSystem {
    METRIC,
    IMPERIAL;

    companion object {
        fun from(value: String?): UnitSystem =
            if (value == LocaleStore.UNIT_IMPERIAL) IMPERIAL else METRIC
    }
}

/** 一个已换算到当前单位制的量（value 已是展示单位下的数值） */
data class Quantity(val value: Double, val unit: String) {
    /** 数值文本（不含单位） */
    val text: String get() = Units.num(value)

    /** "2030 psi" 形式 */
    override fun toString(): String = "$text $unit"
}

/**
 * 单位换算与格式化（SI ↔ 英制）。
 *
 * 换算系数（精确值）：
 *  - 长度 1 in = 25.4 mm；1 ft = 304.8 mm
 *  - 力   1 lbf = 4.4482216152605 N；1 kip = 1000 lbf
 *  - 应力 1 psi = 0.006894757293168361 MPa（1 MPa = 145.0377377302 psi）
 *  - 密度 1 pcf = 16.01846337396014 kg/m³
 *  - 压强 1 psf = 0.047880258977532 Pa（1 Pa = 0.020885434 psf）
 *  - 线载 1 N/mm = 25.4 N/in = 5.710147 lbf/in
 *  - 弯矩 1 N·mm = 0.008850746 lbf·in（1 lbf·in = 112.9997 N·mm）
 *  - 速度 1 m/s = 2.236936 mph
 *
 * 默认单位制跟随系统 locale：英语区（US/LR/MM）默认英制，其余默认公制；
 * 用户在启动页 / 设置页手动选择后以手动值为准。
 */
object Units {

    /** 当前全局单位制（Application / MainActivity 启动时按偏好初始化） */
    @Volatile
    var system: UnitSystem = UnitSystem.METRIC
        private set

    val isImperial: Boolean get() = system == UnitSystem.IMPERIAL

    /** 按当前偏好初始化；未手动设置时跟随系统 locale */
    fun init(context: Context) {
        val store = LocaleStore(context)
        val explicit = store.rawUnitSystem
        system = if (explicit != null) {
            UnitSystem.from(explicit)
        } else {
            defaultSystem(context)
        }
    }

    /** 手动切换单位制（立即写入偏好） */
    fun select(context: Context, value: String) {
        LocaleStore(context).unitSystem = value
        system = UnitSystem.from(value)
    }

    /** 未手动选择时的默认：跟随生效 locale 的国家 */
    fun defaultSystem(context: Context): UnitSystem {
        val locale = LocaleManager.resolveLocale(LocaleStore(context).language)
        return when (locale.country.uppercase(Locale.US)) {
            "US", "LR", "MM" -> UnitSystem.IMPERIAL
            else -> UnitSystem.METRIC
        }
    }

    // ------------------------------------------------------------------ 输出
    /** 长度（输入 mm），英文默认英寸 */
    fun length(mm: Double): Quantity = if (isImperial) {
        Quantity(mm / 25.4, "in")
    } else {
        Quantity(mm, "mm")
    }

    /** 大尺寸（输入 m，墙体/坝体等），英制显示英尺 */
    fun meters(m: Double): Quantity = if (isImperial) {
        Quantity(m * 3.280839895, "ft")
    } else {
        Quantity(m, "m")
    }

    /** 位移（输入 mm）：公制不足 0.01 mm 用 μm；英制不足 0.001 in 用 mil */
    fun displacement(mm: Double): Quantity = if (isImperial) {
        val mil = mm / 0.0254
        if (mil < 1.0) Quantity(mil, "mil") else Quantity(mm / 25.4, "in")
    } else {
        if (kotlin.math.abs(mm) < 0.01) Quantity(mm * 1000.0, "μm") else Quantity(mm, "mm")
    }

    /** 应力（输入 MPa），英制 psi */
    fun stress(mpa: Double): Quantity = if (isImperial) {
        Quantity(mpa * 145.0377377302, "psi")
    } else {
        Quantity(mpa, "MPa")
    }

    /** 力（输入 N），英制 lbf，超过 1000 lbf 用 kip */
    fun force(n: Double): Quantity = if (isImperial) {
        val lbf = n / 4.4482216152605
        if (kotlin.math.abs(lbf) >= 1000.0) Quantity(lbf / 1000.0, "kip") else Quantity(lbf, "lbf")
    } else {
        Quantity(n, "N")
    }

    /** 质量（输入 kg），英制 lb */
    fun mass(kg: Double): Quantity = if (isImperial) {
        Quantity(kg * 2.2046226218, "lb")
    } else {
        Quantity(kg, "kg")
    }

    /** 密度（输入 kg/m³），英制 pcf（lb/ft³） */
    fun density(kgPerM3: Double): Quantity = if (isImperial) {
        Quantity(kgPerM3 / 16.01846337396014, "pcf")
    } else {
        Quantity(kgPerM3, "kg/m³")
    }

    /** 压强（输入 Pa），英制 psf */
    fun pressure(pa: Double): Quantity = if (isImperial) {
        Quantity(pa * 0.020885434, "psf")
    } else {
        Quantity(pa, "Pa")
    }

    /** 线分布载荷（输入 N/mm），英制 lbf/in */
    fun linearLoad(nPerMm: Double): Quantity = if (isImperial) {
        Quantity(nPerMm * 5.710147, "lbf/in")
    } else {
        Quantity(nPerMm, "N/mm")
    }

    /** 框架内力展示（输入 N）：公制 kN，英制 lbf（超过 1000 lbf 用 kip） */
    fun forceK(n: Double): Quantity = if (isImperial) {
        val lbf = n / 4.4482216152605
        if (kotlin.math.abs(lbf) >= 1000.0) Quantity(lbf / 1000.0, "kip") else Quantity(lbf, "lbf")
    } else {
        Quantity(n / 1000.0, "kN")
    }

    /** 弯矩（输入 N·mm），英制 lbf·in */
    fun moment(nMm: Double): Quantity = if (isImperial) {
        Quantity(nMm / 112.9997, "lbf·in")
    } else {
        Quantity(nMm, "N·mm")
    }

    /** 框架弯矩展示（输入 N·mm）：公制 kN·m，英制 lbf·in */
    fun momentK(nMm: Double): Quantity = if (isImperial) {
        Quantity(nMm / 112.9997, "lbf·in")
    } else {
        Quantity(nMm / 1e6, "kN·m")
    }

    /** 风速（输入 m/s），英制 mph */
    fun speed(mPerS: Double): Quantity = if (isImperial) {
        Quantity(mPerS * 2.236936, "mph")
    } else {
        Quantity(mPerS, "m/s")
    }

    /** 线分布载荷汇总（输入 kN/m，与 N/mm 数值相同），英制 lbf/in */
    fun lineLoadK(kNPerM: Double): Quantity = if (isImperial) {
        Quantity(kNPerM * 5.710147, "lbf/in")
    } else {
        Quantity(kNPerM, "kN/m")
    }

    /** 弯矩（输入 N·m），英制 lbf·ft */
    fun momentNm(nm: Double): Quantity = if (isImperial) {
        Quantity(nm * 0.7375621, "lbf·ft")
    } else {
        Quantity(nm, "N·m")
    }

    /** 体积（输入 m³），英制 ft³ */
    fun volume(m3: Double): Quantity = if (isImperial) {
        Quantity(m3 * 35.314666721489, "ft³")
    } else {
        Quantity(m3, "m³")
    }

    /** 渗透系数（输入 m/s），英制 ft/s */
    fun permeability(mPerS: Double): Quantity = if (isImperial) {
        Quantity(mPerS * 3.280839895, "ft/s")
    } else {
        Quantity(mPerS, "m/s")
    }

    /** 单宽渗流量（输入 L/s·m；1 L/s·m ≈ 4.8313 gpm/ft），英制 gpm/ft */
    fun seepageRate(lPerSPerM: Double): Quantity = if (isImperial) {
        Quantity(lPerSPerM * 4.8313, "gpm/ft")
    } else {
        Quantity(lPerSPerM, "L/s·m")
    }

    /**
     * Python 求解结果自带的 (值, 单位) → 当前单位制。
     * 未识别的单位原样返回，保证中文公制输出逐字节不变。
     */
    fun convert(value: Double, unit: String): Quantity = when (unit.trim()) {
        "MPa" -> stress(value)
        "Pa" -> pressure(value)
        "N" -> force(value)
        "kN" -> forceK(value * 1000.0)
        "N·m" -> momentNm(value)
        "kN·m" -> momentNm(value * 1000.0)
        "kN·m/m" -> if (isImperial) Quantity(value * 2697.65, "lbf·ft/ft") else Quantity(value, "kN·m/m")
        "N/mm" -> linearLoad(value)
        "kN/m" -> lineLoadK(value)
        "mm" -> length(value)
        "m" -> meters(value)
        "m/s" -> speed(value)
        "kg/m³" -> density(value)
        else -> Quantity(value, unit)
    }

    // ------------------------------------------------------------------ 输入
    /** 界面输入的长度（当前展示单位）→ mm */
    fun toMm(value: Double): Double = if (isImperial) value * 25.4 else value

    /** 界面输入的力（当前展示单位）→ N */
    fun toNewton(value: Double): Double = if (isImperial) value * 4.4482216152605 else value

    /** 界面输入的应力（当前展示单位）→ MPa */
    fun toMPa(value: Double): Double = if (isImperial) value / 145.0377377302 else value

    /** 界面输入的密度（当前展示单位）→ kg/m³ */
    fun toKgPerM3(value: Double): Double =
        if (isImperial) value * 16.01846337396014 else value

    // ------------------------------------------------------------------ 格式化
    /** 数值格式化（与 UI 的 formatNum 规则一致，但不随 locale 变化） */
    fun num(v: Double): String = when {
        v.isNaN() || v.isInfinite() -> "—"
        v == 0.0 -> "0"
        kotlin.math.abs(v) >= 1e6 || kotlin.math.abs(v) < 1e-3 ->
            String.format(Locale.US, "%.2e", v)
        kotlin.math.abs(v) >= 100 -> String.format(Locale.US, "%.0f", v)
        kotlin.math.abs(v) >= 10 -> String.format(Locale.US, "%.1f", v)
        else -> String.format(Locale.US, "%.2f", v)
    }
}
