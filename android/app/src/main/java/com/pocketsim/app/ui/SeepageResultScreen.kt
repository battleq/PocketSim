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
import com.pocketsim.app.R
import com.pocketsim.app.SafetyLogic
import com.pocketsim.app.SeepageRenderData
import com.pocketsim.app.SeepageResult
import com.pocketsim.app.Units
import com.pocketsim.app.ui.components.ColorLegend
import com.pocketsim.app.ui.components.ColorRamp
import com.pocketsim.app.ui.components.MetricCard
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 渗流求解结果页（大坝坝基）：
 *  - 结论横幅（注意/危险）：薄弱部位 + 破坏形式 + 临界水位 + 加强建议
 *  - 指标三联卡（单宽渗漏 / 坝底扬压力 / 综合安全系数）
 *  - 水头云图 + 渗流线（坝体轮廓与上下游水位叠加）
 *  - 三项校核（抗滑/抗倾覆/出逸比降）、通俗分析过程、计算假定
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeepageResultScreen(
    result: SeepageResult,
    onBack: () -> Unit
) {
    val grade = SafetyLogic.grade(result.safety_factor)
        .takeIf { it != SafetyLogic.Grade.UNKNOWN }
        ?: SafetyLogic.gradeFromVerdict(result.verdict)
    val defaultTitle = stringResource(R.string.seepage_result_title)

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
                                text = if (danger) stringResource(R.string.seepage_verdict_danger)
                                    else stringResource(R.string.seepage_verdict_caution),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = accent
                            )
                            Text(
                                text = stringResource(
                                    R.string.seepage_safety_summary_fmt,
                                    formatNum(result.safety_factor),
                                    formatNum(result.slide_sf),
                                    formatNum(result.overturn_sf),
                                    formatNum(result.piping_sf)
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
                                    R.string.seepage_critical_level_fmt,
                                    Units.meters(result.critical_level_m).toString(),
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
                    val qSeep = Units.seepageRate(result.seepage_q_l_s)
                    MetricCard(
                        title = stringResource(R.string.seepage_metric_seepage),
                        value = formatSeepage(qSeep.value),
                        unit = qSeep.unit,
                        footer = stringResource(R.string.seepage_permeability_footer_fmt, formatSci(Units.permeability(result.permeability_m_s).value) + " " + Units.permeability(result.permeability_m_s).unit),
                        modifier = Modifier.weight(1f)
                    )
                    val upliftQ = Units.lineLoadK(result.uplift_kN_per_m)
                    MetricCard(
                        title = stringResource(R.string.seepage_metric_uplift),
                        value = upliftQ.text,
                        unit = upliftQ.unit,
                        footer = stringResource(R.string.seepage_uplift_ratio_fmt, formatNum(result.uplift_ratio * 100)),
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = stringResource(R.string.seepage_metric_safety),
                        value = formatNum(result.safety_factor),
                        unit = "",
                        accent = gradeColor(grade),
                        footer = gradeLabel(grade),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ---- 水头云图 + 渗流线 ----
            result.render?.let { render ->
                item {
                    Card(shape = RoundedCornerShape(4.dp)) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.seepage_field_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            SeepageFieldPlot(
                                render = render,
                                upstreamLevel = result.upstream_level_m,
                                downstreamLevel = result.downstream_level_m,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(230.dp)
                            )
                            val headMinFmt = stringResource(R.string.seepage_head_min_fmt)
                            val headMaxFmt = stringResource(R.string.seepage_head_max_fmt)
                            ColorLegend(
                                minLabel = headMinFmt.format(Units.meters(render.head_min).toString()),
                                maxLabel = headMaxFmt.format(Units.meters(render.head_max).toString())
                            )
                            Text(
                                text = stringResource(
                                    R.string.seepage_uplift_exit_fmt,
                                    Units.lineLoadK(result.uplift_kN_per_m).toString(),
                                    formatNum(result.exit_gradient),
                                    formatNum(result.exit_gradient_allow)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.seepage_field_explain),
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
                                R.string.seepage_dam_weight_fmt,
                                Units.lineLoadK(result.dam.weight_kN_per_m).toString(),
                                Units.meters(result.dam.height_m).toString(),
                                Units.meters(result.dam.base_width_m).toString()
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
                            val bulletFmt = stringResource(R.string.seepage_bullet_fmt)
                            result.advice.forEach { advice ->
                                Text(text = bulletFmt.format(advice), style = MaterialTheme.typography.bodySmall)
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
                        R.string.seepage_mesh_info_fmt,
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

/** 渗流量格式化：小于 0.01 的用三位小数，其余沿用 formatNum */
private fun formatSeepage(v: Double): String =
    if (abs(v) < 0.01 && v != 0.0) String.format(java.util.Locale.US, "%.3f", v)
    else formatNum(v)

/** 小数值格式化：0.564 → 0.564（三位小数），其余沿用 formatNum */
private fun fmtSmall(v: Double): String =
    if (abs(v) < 1.0 && v != 0.0) String.format(java.util.Locale.US, "%.3f", v)
    else formatNum(v)

/**
 * 水头云图 + 渗流线绘制：
 *  - tris 每个三角形填色（三个节点水头均值 → ColorRamp），蓝/青（低水头）到红（高水头）；
 *  - 叠加深色渗流流线；
 *  - 坝体画成灰色梯形，上下游水体半透明蓝色，地表一条实线。
 */
@Composable
private fun SeepageFieldPlot(
    render: SeepageRenderData,
    upstreamLevel: Double,
    downstreamLevel: Double,
    modifier: Modifier
) {
    Canvas(modifier = modifier) {
        val nodes = render.nodes
        if (nodes.size < 3 || render.tris.isEmpty()) return@Canvas
        val bounds = render.bounds
        val xmin = (if (bounds.size >= 4) bounds[0] else nodes.minOf { it.getOrElse(0) { 0.0 } })
        val ymin = (if (bounds.size >= 4) bounds[1] else nodes.minOf { it.getOrElse(1) { 0.0 } })
        val xmax = (if (bounds.size >= 4) bounds[2] else nodes.maxOf { it.getOrElse(0) { 0.0 } })
        val ymax = (if (bounds.size >= 4) bounds[3] else nodes.maxOf { it.getOrElse(1) { 0.0 } })
        // 坝高 = 显示范围顶部（坝顶），用于选取显示窗口
        val hDam = ymax.coerceAtLeast(0.5)
        val damPoints = render.dam?.points.orEmpty()
        val baseRight = damPoints.maxOfOrNull { it.getOrElse(0) { 0.0 } } ?: (xmax * 0.5)
        // 只显示坝体附近：整个坝基区域太深，坝体会被压成一条细带
        val viewXmin = max(xmin, -1.4 * hDam)
        val viewXmax = min(xmax, baseRight + 1.4 * hDam)
        val viewYmin = max(ymin, -0.95 * hDam)
        val viewYmax = min(ymax, 1.12 * hDam)
        val spanX = (viewXmax - viewXmin).coerceAtLeast(1e-9)
        val spanY = (viewYmax - viewYmin).coerceAtLeast(1e-9)
        val pad = 10.dp.toPx()
        val scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
        val offX = (size.width - spanX * scale) / 2f
        val offY = (size.height - spanY * scale) / 2f
        fun px(x: Double) = (offX + (x - viewXmin) * scale).toFloat()
        fun py(y: Double) = (offY + (viewYmax - y) * scale).toFloat()

        clipRect(px(viewXmin), py(viewYmax), px(viewXmax), py(viewYmin)) {
            val vmin = render.values.minOrNull() ?: 0.0
            val vmax = render.values.maxOrNull() ?: 1.0
            val vspan = (vmax - vmin).coerceAtLeast(1e-9)

            // 三角形填色（按节点水头均值映射色带）
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

            // 渗流线
            render.streamlines.forEach { line ->
                if (line.size < 2) return@forEach
                val path = Path().apply {
                    moveTo(px(line[0][0]), py(line[0][1]))
                    line.drop(1).forEach { pt -> lineTo(px(pt[0]), py(pt[1])) }
                }
                drawPath(
                    path,
                    Color(0xFF1A237E).copy(alpha = 0.8f),
                    style = Stroke(1.1.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // 上下游水体
            if (upstreamLevel > 0.0) {
                drawRect(
                    color = Color(0xFF42A5F5).copy(alpha = 0.28f),
                    topLeft = Offset(px(viewXmin), py(upstreamLevel)),
                    size = Size(px(0.0) - px(viewXmin), py(0.0) - py(upstreamLevel))
                )
            }
            if (downstreamLevel > 0.0) {
                drawRect(
                    color = Color(0xFF42A5F5).copy(alpha = 0.22f),
                    topLeft = Offset(px(baseRight), py(downstreamLevel)),
                    size = Size(px(viewXmax) - px(baseRight), py(0.0) - py(downstreamLevel))
                )
            }

            // 地表线
            drawLine(
                Color(0xFF4E342E),
                Offset(px(viewXmin), py(0.0)),
                Offset(px(viewXmax), py(0.0)),
                strokeWidth = 2.dp.toPx()
            )

            // 坝体
            if (damPoints.size >= 3) {
                val path = Path().apply {
                    moveTo(px(damPoints[0][0]), py(damPoints[0][1]))
                    damPoints.drop(1).forEach { pt -> lineTo(px(pt[0]), py(pt[1])) }
                    close()
                }
                drawPath(path, Color(0xFF90A4AE))
                drawPath(path, Color(0xFF263238), style = Stroke(1.2.dp.toPx()))
            }
        } // clipRect
    }
}
