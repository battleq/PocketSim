/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.pocketsim.app.R
import com.pocketsim.app.SafetyLogic
import com.pocketsim.app.Units

/** 数值显示格式化（102000 → 102000，0.85 → 0.85，超大值用科学计数） */
internal fun formatNum(v: Double): String = when {
    v.isNaN() || v.isInfinite() -> "—"
    v == 0.0 -> "0"
    kotlin.math.abs(v) >= 1e6 || kotlin.math.abs(v) < 1e-3 -> String.format(java.util.Locale.US, "%.2e", v)
    kotlin.math.abs(v) >= 100 -> String.format(java.util.Locale.US, "%.0f", v)
    kotlin.math.abs(v) >= 10 -> String.format(java.util.Locale.US, "%.1f", v)
    else -> String.format(java.util.Locale.US, "%.2f", v)
}

/**
 * 位移显示：按当前单位制换算。
 * 公制不足 0.01 mm 时用 μm；英制不足 0.001 in 时用 mil，避免「0.00 mm」的误解。
 */
internal fun formatDisp(vMm: Double): Pair<String, String> {
    val q = Units.displacement(vMm)
    return q.text to q.unit
}

/** 安全分级配色（结果页与框架结果页共用） */
internal fun gradeColor(grade: SafetyLogic.Grade): Color = when (grade) {
    SafetyLogic.Grade.SAFE -> Color(0xFF43A047)     // 安全绿（浅底/深底都够对比）
    SafetyLogic.Grade.CAUTION -> Color(0xFFF9A825)  // 注意黄
    SafetyLogic.Grade.DANGER -> Color(0xFFE53935)   // 危险红（深色模式下也清晰）
    SafetyLogic.Grade.UNKNOWN -> Color(0xFF9E9E9E)
}

/** 安全分级文案（框架结果页与分享共用） */
@Composable
internal fun gradeLabel(grade: SafetyLogic.Grade): String = stringResource(
    when (grade) {
        SafetyLogic.Grade.SAFE -> R.string.safety_safe
        SafetyLogic.Grade.CAUTION -> R.string.safety_caution
        SafetyLogic.Grade.DANGER -> R.string.safety_danger
        SafetyLogic.Grade.UNKNOWN -> R.string.safety_unknown
    }
)

/** Python verdict 词（内部始终为中文）→ 当前语言的分级文案 */
@Composable
internal fun verdictLabel(verdict: String?): String = when (verdict?.trim()) {
    "安全" -> stringResource(R.string.safety_safe)
    "注意" -> stringResource(R.string.safety_caution)
    "危险" -> stringResource(R.string.safety_danger)
    else -> verdict.orEmpty()
}

/**
 * 杆件截面文案：矩形 / 矩形管 / 圆管 / 实心圆。
 * 尺寸按当前单位制换算，圆管/实心圆/空心 三个后缀由调用方预取（本函数不加 @Composable 以便在循环里调用）。
 */
internal fun sectionTextOf(
    m: com.pocketsim.app.FrameMember,
    pipe: String,
    solidRound: String,
    hollow: String
): String {
    val unit = Units.length(1.0).unit
    fun fmt(v: Double): String {
        val x = Units.length(v).value
        return if (x == x.toLong().toDouble()) x.toLong().toString()
        else String.format(java.util.Locale.US, "%.1f", x)
    }
    return if (m.isCircle) {
        if (m.wall_t > 0.0) "⌀${fmt(m.outer_diameter_mm)}×${fmt(m.wall_t)} $unit$pipe"
        else "⌀${fmt(m.outer_diameter_mm)} $unit$solidRound"
    } else if (m.wall_t > 0.0) {
        "${fmt(m.width_b)}×${fmt(m.depth_h)}×${fmt(m.wall_t)} $unit$hollow"
    } else {
        "${fmt(m.width_b)}×${fmt(m.depth_h)} $unit"
    }
}
