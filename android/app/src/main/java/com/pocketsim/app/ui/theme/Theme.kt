/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ============================================================================
//  硬核工程工具视觉系统：CAD 视口深色 + 工程青高亮 + 等宽数据字体
//  灵感源自 ANSYS / Abaqus / COMSOL 等有限元软件的深色视口与高亮选色。
// ============================================================================

// ---- 深色（工程蓝图 / CAD 视口） ----
private val DarkColors = darkColorScheme(
    primary = Color(0xFF00E5FF),            // 工程青：激活、选中、关键数值
    onPrimary = Color(0xFF002B33),
    primaryContainer = Color(0xFF003B45),   // 按钮/标签填充
    onPrimaryContainer = Color(0xFFB4EBF0),
    secondary = Color(0xFF4DD0E1),          // 次要青
    onSecondary = Color(0xFF002B33),
    secondaryContainer = Color(0xFF1A3A40),
    onSecondaryContainer = Color(0xFFB2EBF2),
    tertiary = Color(0xFFFFB300),           // 琥珀：单位、警示、维度标注
    onTertiary = Color(0xFF332200),
    tertiaryContainer = Color(0xFF4D3500),
    onTertiaryContainer = Color(0xFFFFE0A3),
    error = Color(0xFFFF5252),
    onError = Color(0xFF330000),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDADA),
    background = Color(0xFF0A0E14),         // 视口底色：深空黑蓝
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0F1620),            // 面板底色
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF16202C),     // 卡片/次级面板
    onSurfaceVariant = Color(0xFF8B9BAE),
    surfaceTint = Color(0xFF00E5FF),
    outline = Color(0xFF1E2D3D),            // 发丝描边
    outlineVariant = Color(0xFF243447),
    scrim = Color(0xCC0A0E14)
)

// ---- 浅色（工程白纸 / 蓝图白底） ----
private val LightColors = lightColorScheme(
    primary = Color(0xFF006064),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2EBF2),
    onPrimaryContainer = Color(0xFF001F23),
    secondary = Color(0xFF00838F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB2EBF2),
    onSecondaryContainer = Color(0xFF001F23),
    tertiary = Color(0xFFEF6C00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE0A3),
    onTertiaryContainer = Color(0xFF332200),
    error = Color(0xFFC62828),
    background = Color(0xFFF4F7F9),
    onBackground = Color(0xFF102027),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF102027),
    surfaceVariant = Color(0xFFE1ECEF),
    onSurfaceVariant = Color(0xFF455A64),
    outline = Color(0xFFB0BEC5),
    outlineVariant = Color(0xFFCFD8DC)
)

// ---- 字体：数据用等宽（工程数值），UI 用无衬线 ----
private val EngineeringTypography = Typography(
    // 大数值：等宽粗体，模拟仪器读数
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 36.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 30.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 26.sp
    ),
    // 标题：无衬线中等
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    // 正文：无衬线常规
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    // 标签：无衬线，技术文档风
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    )
)

/** 应用主题：硬核工程工具风（默认深色 CAD 视口） */
@Composable
fun PocketSimTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = EngineeringTypography,
        content = content
    )
}
