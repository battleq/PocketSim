/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.FlowRenderData
import com.pocketsim.app.FlowResult
import com.pocketsim.app.R
import com.pocketsim.app.SafetyLogic
import com.pocketsim.app.Units
import com.pocketsim.app.ui.components.ColorLegend
import com.pocketsim.app.ui.components.ColorRamp
import com.pocketsim.app.ui.components.MetricCard
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 流体求解结果页（P4 风荷载）：
 *  - 结论横幅（注意/危险）：薄弱部位 + 破坏形式 + 临界风级 + 加强建议
 *  - 指标三联卡（风速 / 总风荷载 / 综合安全系数）
 *  - 压力云图 + 流线（ColorRamp 色带，墙体轮廓叠加）
 *  - 三项校核（抗倾覆/抗滑移/墙根抗弯）、通俗分析过程、计算假定
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowResultScreen(
    result: FlowResult,
    onBack: () -> Unit
) {
    val grade = SafetyLogic.grade(result.safety_factor)
        .takeIf { it != SafetyLogic.Grade.UNKNOWN }
        ?: SafetyLogic.gradeFromVerdict(result.verdict)
    val defaultTitle = stringResource(R.string.flow_result_title)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(result.model_name.ifBlank { defaultTitle }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back)
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Text(
                    text = stringResource(R.string.disclaimer_full),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- 结论横幅（注意/危险时置顶） ----
            if (grade == SafetyLogic.Grade.DANGER || grade == SafetyLogic.Grade.CAUTION) {
                item {
                    val danger = grade == SafetyLogic.Grade.DANGER
                    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                    val bannerBg = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
                    val bodyColor = MaterialTheme.colorScheme.onSurface
                    val subtleColor = MaterialTheme.colorScheme.onSurfaceVariant

                    Card(
                        shape = RoundedCornerShape(4.dp),
                        colors = CardDefaults.cardColors(containerColor = bannerBg)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (danger) stringResource(R.string.flow_verdict_danger)
                                    else stringResource(R.string.flow_verdict_caution),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = accent
                            )
                            Text(
                                text = stringResource(
                                    R.string.flow_safety_summary_fmt,
                                    formatNum(result.safety_factor),
                                    formatNum(result.overturn_sf),
                                    formatNum(result.slide_sf),
                                    formatNum(result.bend_sf)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = bodyColor
                            )
                            Text(
                                text = stringResource(R.string.seepage_weak_part_fmt, result.weak_part),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = bodyColor
                            )
                            result.failure_modes.forEach { mode ->
                                Text(
                                    text = mode,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = subtleColor
                                )
                            }
                            Text(
                                text = stringResource(
                                    R.string.flow_critical_speed_fmt,
                                    Units.speed(result.critical_speed_m_s).toString(),
                                    result.critical_beaufort,
                                    result.critical_mode
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = accent
                            )
                        }
                    }
                }
            }

            // ---- 指标三联卡 ----
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val windQ = Units.speed(result.wind_speed_m_s)
                    MetricCard(
                        title = stringResource(R.string.flow_metric_wind_speed),
                        value = windQ.text,
                        unit = windQ.unit,
                        footer = stringResource(
                            R.string.flow_wind_footer_fmt,
                            result.beaufort_label,
                            Units.pressure(result.q_Pa).toString()
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    val forceQ = Units.force(result.force_N)
                    MetricCard(
                        title = stringResource(R.string.flow_metric_total_force),
                        value = forceQ.text,
                        unit = forceQ.unit,
                        footer = stringResource(
                            R.string.flow_force_per_m_fmt,
                            Units.lineLoadK(result.force_per_m / 1000.0).toString()
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = stringResource(R.string.flow_metric_safety),
                        value = formatNum(result.safety_factor),
                        unit = "",
                        accent = gradeColor(grade),
                        footer = gradeLabel(grade),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ---- 压力云图 + 流线 ----
            result.render?.let { render ->
                item {
                    Card(shape = RoundedCornerShape(4.dp)) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.flow_field_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            FlowFieldPlot(
                                render = render,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(230.dp)
                            )
                            val vmin = render.values.minOrNull() ?: 0.0
                            val vmax = render.values.maxOrNull() ?: 0.0
                            val pressMinFmt = stringResource(R.string.flow_pressure_min_fmt)
                            val pressMaxFmt = stringResource(R.string.flow_pressure_max_fmt)
                            ColorLegend(
                                minLabel = pressMinFmt.format(Units.pressure(vmin).toString()),
                                maxLabel = pressMaxFmt.format(Units.pressure(vmax).toString())
                            )
                            Text(
                                text = stringResource(
                                    R.string.flow_stagnation_fmt,
                                    Units.pressure(result.stagnation_Pa).toString(),
                                    Units.pressure(result.q_Pa).toString(),
                                    formatNum(result.max_speed_ratio)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(
                                    R.string.flow_potential_note_fmt,
                                    formatNum(result.cp_net)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ---- 三项校核 ----
            item {
                Card(shape = RoundedCornerShape(4.dp)) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.seepage_checks_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        val checkItemFmt = stringResource(R.string.seepage_check_item_fmt)
                        val forceResistFmt = stringResource(R.string.seepage_check_force_resist_fmt)
                        result.checks.forEachIndexed { index, check ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = checkItemFmt.format(check.name, formatNum(check.safety_factor)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = if (check.pass) Color(0xFF2E7D32) else Color(0xFFC62828)
                                )
                                val vq = Units.convert(check.value, check.unit)
                                val lq = Units.convert(check.limit, check.unit)
                                Text(
                                    text = forceResistFmt.format(
                                        fmtSmall(vq.value),
                                        vq.unit,
                                        fmtSmall(lq.value)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            text = stringResource(
                                R.string.flow_wall_weight_fmt,
                                Units.forceK(result.wall.weight_kN * 1000.0).toString(),
                                Units.meters(result.wall.height_m).toString(),
                                Units.length(result.wall.thickness_m * 1000).toString(),
                                Units.meters(result.wall.length_m).toString()
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // ---- 加强建议 ----
            if (result.advice.isNotEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(4.dp)) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.seepage_advice_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            val adviceBulletFmt = stringResource(R.string.seepage_bullet_fmt)
                            result.advice.forEach { advice ->
                                Text(text = adviceBulletFmt.format(advice), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // ---- 分析过程 ----
            if (result.narrative.isNotEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(4.dp)) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.frame_narrative_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            result.narrative.forEach { paragraph ->
                                Text(
                                    text = paragraph,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // ---- 计算假定 ----
            if (result.assumptions.isNotEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(4.dp)) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.seepage_assumptions_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            val assumptionBulletFmt = stringResource(R.string.seepage_bullet_fmt)
                            result.assumptions.forEach { a ->
                                Text(
                                    text = assumptionBulletFmt.format(a),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(
                        R.string.flow_mesh_info_fmt,
                        result.material,
                        Units.density(result.density_kg_m3).toString(),
                        formatNum(result.solve_ms)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 小数值格式化：0.066 → 0.066（三位小数），其余沿用 formatNum */
private fun fmtSmall(v: Double): String =
    if (abs(v) < 1.0 && v != 0.0) String.format(java.util.Locale.US, "%.3f", v)
    else formatNum(v)

/**
 * 压力云图 + 流线绘制：
 *  - tris 每个三角形填色（三个节点压力均值 → ColorRamp），青色到红色；
 *  - 叠加黑色流线折线；
 *  - 墙体画成灰色矩形，地面为一条实线。
 */
@Composable
private fun FlowFieldPlot(render: FlowRenderData, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val nodes = render.nodes
        if (nodes.size < 3 || render.tris.isEmpty()) return@Canvas
        val bounds = render.bounds
        val xmin = (if (bounds.size >= 4) bounds[0] else nodes.minOf { it.getOrElse(0) { 0.0 } })
        val ymin = (if (bounds.size >= 4) bounds[1] else nodes.minOf { it.getOrElse(1) { 0.0 } })
        val xmax = (if (bounds.size >= 4) bounds[2] else nodes.maxOf { it.getOrElse(0) { 0.0 } })
        val ymax = (if (bounds.size >= 4) bounds[3] else nodes.maxOf { it.getOrElse(1) { 0.0 } })
        // 只画墙体附近的核心区：整个流体域 x∈[-4h,7h] 宽高比 ~3.7:1，
        // 直接铺满会让墙体缩成一条细线、云图看不出近墙的加速/驻点
        val hWall = render.wall?.y1?.takeIf { it > 0.0 } ?: (ymax - ymin)
        val wx0 = render.wall?.x0 ?: 0.0
        val viewXmin = max(xmin, wx0 - 2.2 * hWall)
        val viewXmax = min(xmax, wx0 + 3.6 * hWall)
        val viewYmin = max(ymin, 0.0)
        val viewYmax = min(ymax, viewYmin + 2.6 * hWall)
        val spanX = (viewXmax - viewXmin).coerceAtLeast(1e-9)
        val spanY = (viewYmax - viewYmin).coerceAtLeast(1e-9)
        val pad = 10.dp.toPx()
        val scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
        val offX = (size.width - spanX * scale) / 2f
        val offY = (size.height - spanY * scale) / 2f
        fun px(x: Double) = (offX + (x - viewXmin) * scale).toFloat()
        fun py(y: Double) = (offY + (viewYmax - y) * scale).toFloat()
        // 裁掉窗口外的三角形/流线（否则会画到留白里）
        clipRect(px(viewXmin), py(viewYmax), px(viewXmax), py(viewYmin)) {

        val vmin = render.values.minOrNull() ?: 0.0
        val vmax = render.values.maxOrNull() ?: 1.0
        val vspan = (vmax - vmin).coerceAtLeast(1e-9)

        // 三角形填色（按节点压力均值映射色带）
        render.tris.forEach { tri ->
            if (tri.size < 3) return@forEach
            val i0 = tri[0]; val i1 = tri[1]; val i2 = tri[2]
            if (i0 >= nodes.size || i1 >= nodes.size || i2 >= nodes.size) return@forEach
            val v = (render.values.getOrElse(i0) { 0.0 } +
                render.values.getOrElse(i1) { 0.0 } +
                render.values.getOrElse(i2) { 0.0 }) / 3.0
            val t = ((v - vmin) / vspan).toFloat()
            val path = Path().apply {
                moveTo(px(nodes[i0][0]), py(nodes[i0][1]))
                lineTo(px(nodes[i1][0]), py(nodes[i1][1]))
                lineTo(px(nodes[i2][0]), py(nodes[i2][1]))
                close()
            }
            drawPath(path, ColorRamp.colorAt(t))
        }

        // 流线
        render.streamlines.forEach { line ->
            if (line.size < 2) return@forEach
            val path = Path().apply {
                moveTo(px(line[0][0]), py(line[0][1]))
                line.drop(1).forEach { pt -> lineTo(px(pt[0]), py(pt[1])) }
            }
            drawPath(
                path,
                Color(0xFF263238).copy(alpha = 0.75f),
                style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // 地面
        drawLine(
            Color(0xFF37474F),
            Offset(px(viewXmin), py(viewYmin)),
            Offset(px(viewXmax), py(viewYmin)),
            strokeWidth = 2.dp.toPx()
        )

        // 墙体
        render.wall?.let { wall ->
            val w = max(px(wall.x1) - px(wall.x0), 2f)
            val hPx = max(py(0.0) - py(wall.y1), 2f)
            drawRect(
                color = Color(0xFF78909C),
                topLeft = Offset(px(wall.x0), py(wall.y1)),
                size = Size(w, hPx)
            )
            drawRect(
                color = Color(0xFF263238),
                topLeft = Offset(px(wall.x0), py(wall.y1)),
                size = Size(w, hPx),
                style = Stroke(1.2.dp.toPx())
            )
        }
        } // clipRect
    }
}
