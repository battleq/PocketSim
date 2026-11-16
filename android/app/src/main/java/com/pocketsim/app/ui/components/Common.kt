/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.ParamSource
import com.pocketsim.app.R
import com.pocketsim.app.SafetyLogic

/** 顶部免责声明条：琥珀底 + 暗色字，工程警示风 */
@Composable
fun DisclaimerBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFFFFE0A3),
        contentColor = Color(0xFF332200)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = Color(0xFFEF6C00),
                modifier = Modifier.height(16.dp)
            )
            Text(
                text = stringResource(R.string.disclaimer_short),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp
            )
        }
    }
}

/** 参数来源标签 chip（内置 / AI 提取 / 联网检索 / 自填 / 离线识别） */
@Composable
fun SourceTagChip(source: ParamSource, modifier: Modifier = Modifier) {
    val (bg, fg) = when (source) {
        ParamSource.BUILTIN -> Color(0xFF1B5E20) to Color(0xFFA5D6A7)
        ParamSource.AI -> Color(0xFF0D47A1) to Color(0xFF90CAF9)
        ParamSource.WEB -> Color(0xFF4A148C) to Color(0xFFCE93D8)
        ParamSource.USER -> Color(0xFFE65100) to Color(0xFFFFCC80)
        ParamSource.OFFLINE -> Color(0xFF004D40) to Color(0xFF80CBC4)
    }
    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
        color = bg,
        contentColor = fg
    ) {
        Text(
            text = SafetyLogic.sourceLabel(source),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * 指标卡（结果页三联卡：标题 + 数值 + 单位 + 可选角标）。
 * 工程仪器读数风：发丝描边、等宽粗体数值、琥珀单位。
 */
@Composable
fun MetricCard(
    title: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    footer: String = ""
) {
    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.5.sp
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                Text(
                    text = " $unit",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
            if (footer.isNotEmpty()) {
                Text(
                    text = footer,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 云图色标图例：min → 渐变条 → max */
@Composable
fun ColorLegend(minLabel: String, maxLabel: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = minLabel,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Start,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(ColorRamp.colors),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(1.dp)
                )
                .border(1.dp, MaterialTheme.colorScheme.outline)
        )
        Text(
            text = maxLabel,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 数值→颜色的分段线性色带（经典 jet 风：深蓝→青→黄→橙→深红） */
object ColorRamp {
    private val stops: List<Pair<Float, Color>> = listOf(
        0.00f to Color(0xFF313695),
        0.25f to Color(0xFF74ADD1),
        0.50f to Color(0xFFFFFFBF),
        0.75f to Color(0xFFF46D43),
        1.00f to Color(0xFFA50026)
    )

    val colors: List<Color> = stops.map { it.second }

    /** t∈[0,1] → 色带颜色 */
    fun colorAt(t: Float): Color {
        val x = t.coerceIn(0f, 1f)
        for (i in 0 until stops.size - 1) {
            val (t0, c0) = stops[i]
            val (t1, c1) = stops[i + 1]
            if (x <= t1) {
                val f = if (t1 == t0) 0f else (x - t0) / (t1 - t0)
                return lerpColor(c0, c1, f)
            }
        }
        return stops.last().second
    }

    private fun lerpColor(a: Color, b: Color, f: Float): Color = Color(
        red = a.red + (b.red - a.red) * f,
        green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f,
        alpha = 1f
    )
}

/**
 * 工程风 FilterChip 配色：
 * - 未选中：容器用 outlineVariant（比 surface 明显深一层，避免与卡片底色混淆），
 *   文字用 onSurface（高对比）；
 * - 选中：primaryContainer 底 + onPrimaryContainer 字。
 */
@Composable
fun engineeringChipColors() = FilterChipDefaults.filterChipColors(
    labelColor = MaterialTheme.colorScheme.onSurface,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    containerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
)
