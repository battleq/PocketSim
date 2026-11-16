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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.FrameMemberResult
import com.pocketsim.app.FramePolyline
import com.pocketsim.app.R
import com.pocketsim.app.SafetyLogic
import com.pocketsim.app.Units
import com.pocketsim.app.modelDisplayName
import com.pocketsim.app.TransientCurves
import com.pocketsim.app.TransientResult
import com.pocketsim.app.TransientSpec
import com.pocketsim.app.ui.components.MetricCard
import kotlin.math.max
import kotlin.math.min

/**
 * 时间历程结果页：
 *  - 结论横幅（注意/危险）：最不利时刻 + 超温提醒
 *  - 指标三联卡（最不利时刻应力 / 最大变形 / 最低安全系数）
 *  - 三联曲线（温度/应力/变形）+ 时间滑杆选择时刻
 *  - 选中时刻的变形图（按杆件应力着色）+ 杆件明细
 *  - 通俗分析过程与计算假定
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransientResultScreen(
    spec: TransientSpec,
    result: TransientResult,
    onBack: () -> Unit
) {
    val grade = SafetyLogic.grade(result.safety_factor)
        .takeIf { it != SafetyLogic.Grade.UNKNOWN }
        ?: SafetyLogic.gradeFromVerdict(result.verdict)
    val defaultTitle = stringResource(R.string.transient_result_title)

    val frames = result.frames
    // 默认定位到最不利时刻，方便一眼看到最危险状态
    var selectedIndex by remember(result) {
        mutableIntStateOf(frames.getOrNull(result.worst.index)?.index ?: 0)
    }
    var deformScale by remember { mutableFloatStateOf(20f) }
    val selected = frames.getOrNull(selectedIndex)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(modelDisplayName(result.model_name).ifBlank { defaultTitle }) },
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
            // ---- 结论横幅 ----
            if (grade == SafetyLogic.Grade.DANGER || grade == SafetyLogic.Grade.CAUTION) {
                item {
                    val danger = grade == SafetyLogic.Grade.DANGER
                    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                    val bannerBg = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
                    val bodyColor = MaterialTheme.colorScheme.onSurface
                    Card(
                        shape = RoundedCornerShape(4.dp),
                        colors = CardDefaults.cardColors(containerColor = bannerBg)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (danger) stringResource(R.string.transient_verdict_danger)
                                    else stringResource(R.string.transient_verdict_caution),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = accent
                            )
                            Text(
                                text = stringResource(
                                    R.string.transient_worst_fmt,
                                    timeLabel(result.worst.t_s),
                                    formatNum(result.worst.temp_c),
                                    Units.stress(result.worst.max_stress_MPa).toString(),
                                    formatNum(result.worst.safety_factor)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = bodyColor
                            )
                            Text(
                                text = stringResource(R.string.transient_advice),
                                style = MaterialTheme.typography.bodySmall,
                                color = bodyColor
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
                    val worstStressQ = Units.stress(result.worst.max_stress_MPa)
                    MetricCard(
                        title = stringResource(R.string.transient_metric_worst_stress),
                        value = worstStressQ.text,
                        unit = worstStressQ.unit,
                        footer = stringResource(
                            R.string.transient_metric_footer_fmt,
                            timeLabel(result.worst.t_s),
                            formatNum(result.worst.temp_c)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    val transientDispQ = Units.displacement(result.max_disp_mm)
                    MetricCard(
                        title = stringResource(R.string.metric_max_deflection),
                        value = transientDispQ.text,
                        unit = transientDispQ.unit,
                        footer = stringResource(
                            R.string.transient_metric_footer_fmt,
                            timeLabel(result.peak_disp.t_s),
                            formatNum(result.peak_disp.temp_c)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = stringResource(R.string.transient_metric_min_safety),
                        value = if (result.safety_factor > 0.0) formatNum(result.safety_factor)
                        else stringResource(R.string.safety_unknown),
                        unit = "",
                        accent = gradeColor(grade),
                        footer = gradeLabel(grade),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ---- 超温提醒 ----
            if (result.over_temp) {
                item {
                    Card(shape = RoundedCornerShape(4.dp)) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.transient_over_temp_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFC62828)
                            )
                            val peakFmt = stringResource(R.string.transient_over_temp_peak_fmt)
                            val limitFmt = stringResource(R.string.transient_over_temp_limit_fmt)
                            val warn = stringResource(R.string.transient_over_temp_warn)
                            val thermalDefault = stringResource(R.string.transient_thermal_members_default)
                            Text(
                                text = peakFmt.format(formatNum(result.temp_peak_c)) +
                                    (result.temp_limit_c?.let {
                                        limitFmt.format(
                                            result.limit_material.ifBlank { thermalDefault },
                                            formatNum(it)
                                        )
                                    } ?: "") + warn,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // ---- 曲线卡（温度 / 应力 / 变形） ----
            item {
                Card(shape = RoundedCornerShape(4.dp)) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.transient_curves_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        TransientCurvePlot(
                            curves = result.curves,
                            selectedIndex = selectedIndex,
                            duration = result.duration_s,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            LegendDot(stringResource(R.string.transient_legend_temp), Color(0xFFE53935))
                            LegendDot(stringResource(R.string.transient_legend_stress), Color(0xFF1E88E5))
                            LegendDot(stringResource(R.string.transient_legend_disp, Units.length(1.0).unit), Color(0xFF43A047))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Text(
                            text = stringResource(
                                R.string.transient_current_moment_fmt,
                                timeLabel(selected?.t_s ?: 0.0),
                                formatNum(selected?.temp_c ?: 0.0),
                                formatNum(selected?.delta_temp_c ?: 0.0),
                                Units.displacement(selected?.thermal_elong_mm ?: 0.0).toString()
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(
                                R.string.transient_slider_hint_fmt,
                                frames.size,
                                profileLabel(result.profile)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (frames.size > 1) {
                            Slider(
                                value = selectedIndex.toFloat(),
                                onValueChange = { selectedIndex = it.toInt() },
                                valueRange = 0f..(frames.size - 1).toFloat(),
                                steps = (frames.size - 2).coerceAtLeast(0)
                            )
                        }
                        // 静定结构说明：应力/安全系数不随温度变化是正常力学结果，避免误判为「没刷新」
                        if (result.thermal_stress_constant) {
                            val staticNote = stringResource(R.string.transient_static_note)
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = result.thermal_stress_note.ifBlank { staticNote },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ---- 选中时刻指标 ----
            if (selected != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val selStressQ = Units.stress(selected.max_stress_MPa)
                        MetricCard(
                            title = stringResource(R.string.transient_metric_selected_stress),
                            value = selStressQ.text,
                            unit = selStressQ.unit,
                            footer = stringResource(R.string.frame_governing_fmt, selected.governing_member),
                            modifier = Modifier.weight(1f)
                        )
                        val selDispQ = Units.displacement(selected.max_disp_mm)
                        MetricCard(
                            title = stringResource(R.string.transient_metric_selected_disp),
                            value = selDispQ.text,
                            unit = selDispQ.unit,
                            footer = selected.max_disp_desc,
                            modifier = Modifier.weight(1f)
                        )
                        MetricCard(
                            title = stringResource(R.string.transient_metric_selected_safety),
                            value = if (selected.safety_factor > 0.0) formatNum(selected.safety_factor)
                            else stringResource(R.string.safety_unknown),
                            unit = "",
                            accent = gradeColor(
                                SafetyLogic.grade(selected.safety_factor)
                                    .takeIf { it != SafetyLogic.Grade.UNKNOWN }
                                    ?: SafetyLogic.gradeFromVerdict(selected.verdict)
                            ),
                            footer = gradeLabel(
                                SafetyLogic.grade(selected.safety_factor)
                                    .takeIf { it != SafetyLogic.Grade.UNKNOWN }
                                    ?: SafetyLogic.gradeFromVerdict(selected.verdict)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // ---- 选中时刻变形图 ----
                item {
                    Card(shape = RoundedCornerShape(4.dp)) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.transient_deform_title_fmt,
                                    timeLabel(selected.t_s),
                                    deformScale.toInt()
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            TransientDeformPlot(
                                polylines = selected.polylines,
                                members = selected.members,
                                deformScale = deformScale,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp)
                            )
                            Text(
                                text = stringResource(R.string.transient_deform_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = deformScale,
                                onValueChange = { deformScale = it },
                                valueRange = 1f..200f
                            )
                        }
                    }
                }

                // ---- 选中时刻杆件明细 ----
                item {
                    Card(shape = RoundedCornerShape(4.dp)) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.transient_members_title_fmt,
                                    timeLabel(selected.t_s)
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            val memberTitleFmt = stringResource(R.string.frame_member_title_fmt)
                            val memberNodesFmt = stringResource(R.string.frame_member_nodes_fmt)
                            val deltaTFmt = stringResource(R.string.transient_member_delta_t_fmt)
                            val stressFmt = stringResource(R.string.transient_member_stress_fmt)
                            val utilFmt = stringResource(R.string.transient_member_util_fmt)
                            val axialFmt = stringResource(R.string.transient_member_axial_fmt)
                            val momentFmt = stringResource(R.string.transient_member_moment_fmt)
                            selected.members.forEachIndexed { index, m ->
                                if (index > 0) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    val conn = spec.frame.members.firstOrNull { it.id == m.id }
                                        ?.let { memberNodesFmt.format(it.i, it.j) }.orEmpty()
                                    Text(
                                        text = memberTitleFmt.format(m.id, m.material, m.section) + conn,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = deltaTFmt.format(
                                            (if (selected.delta_temp_c >= 0) "+" else "") +
                                                formatNum(selected.delta_temp_c)
                                        ) +
                                            stressFmt.format(Units.stress(m.sigma_MPa).toString()) +
                                            (m.util?.let { utilFmt.format(formatNum(it * 100)) } ?: "") +
                                            axialFmt.format(Units.forceK(m.N_N).toString()) +
                                            momentFmt.format(Units.momentK(m.M_max_Nmm).toString()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
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
                                Text(text = paragraph, style = MaterialTheme.typography.bodySmall)
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
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.seepage_assumptions_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            val bulletFmt = stringResource(R.string.seepage_bullet_fmt)
                            result.assumptions.forEach { line ->
                                Text(
                                    text = bulletFmt.format(line),
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
                        R.string.transient_info_fmt,
                        result.n_samples,
                        result.n_members,
                        result.duration_desc
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 图例小圆点 + 文字 */
@Composable
private fun LegendDot(label: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color, radius = size.minDimension / 2f)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 三联曲线：温度/应力/变形各自按自身极值归一化后叠画（共用时间轴），
 * 选中时刻画竖向指示线。三条曲线用不同颜色区分，图例在卡片文字中给出。
 */
@Composable
private fun TransientCurvePlot(
    curves: TransientCurves,
    selectedIndex: Int,
    duration: Double,
    modifier: Modifier
) {
    if (curves.t_s.size < 2) return
    Canvas(modifier = modifier) {
        val padL = 6.dp.toPx(); val padR = 6.dp.toPx()
        val padT = 12.dp.toPx(); val padB = 18.dp.toPx()
        val w = size.width - padL - padR
        val h = size.height - padT - padB
        val n = curves.t_s.size
        val span = duration.coerceAtLeast(1e-9)

        fun px(t: Double) = (padL + (t / span).coerceIn(0.0, 1.0) * w).toFloat()
        fun norm(values: List<Double>, v: Double): Float {
            val lo = values.minOrNull() ?: 0.0
            val hi = values.maxOrNull() ?: 0.0
            val range = (hi - lo).coerceAtLeast(1e-9)
            return ((v - lo) / range).toFloat().coerceIn(0f, 1f)
        }
        fun py(t: Float) = (padT + h * (1f - t)).toFloat()

        // 基线
        drawLine(
            Color.Gray.copy(alpha = 0.35f),
            Offset(padL, padT + h),
            Offset(padL + w, padT + h),
            strokeWidth = 1.dp.toPx()
        )

        fun drawSeries(values: List<Double>, color: Color) {
            if (values.size != n) return
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = px(curves.t_s[i])
                val y = py(norm(values, v))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
        }
        drawSeries(curves.temp_c, Color(0xFFE53935))
        drawSeries(curves.max_stress_MPa, Color(0xFF1E88E5))
        drawSeries(curves.max_disp_mm, Color(0xFF43A047))

        // 选中时刻指示线
        if (selectedIndex in 0 until n) {
            val x = px(curves.t_s[selectedIndex])
            drawLine(
                Color.Gray.copy(alpha = 0.8f),
                Offset(x, padT),
                Offset(x, padT + h),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )
        }

        // 时间轴标注
        drawContext.canvas.nativeCanvas.let { nc ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 11.sp.toPx()
                color = android.graphics.Color.DKGRAY
            }
            nc.drawText("0", padL, padT + h + 14f, paint)
            val endLabel = timeLabel(duration)
            nc.drawText(endLabel, padL + w - paint.measureText(endLabel), padT + h + 14f, paint)
        }
    }
}

/**
 * 选中时刻的变形折线图（Compose Canvas）：
 *  - polylines 每点 [x, y, dx, dy]（mm；变形位置 = 原坐标 + 位移 × 放大系数）；
 *  - 原始形状虚线灰，变形形状实线按杆件应力着色（ColorRamp）。
 */
@Composable
private fun TransientDeformPlot(
    polylines: List<FramePolyline>,
    members: List<FrameMemberResult>,
    deformScale: Float,
    modifier: Modifier
) {
    Canvas(modifier = modifier) {
        if (polylines.isEmpty()) return@Canvas

        fun ox(pt: List<Double>) = pt.getOrElse(0) { 0.0 }.toFloat()
        fun oy(pt: List<Double>) = pt.getOrElse(1) { 0.0 }.toFloat()
        fun dx(pt: List<Double>) = pt.getOrElse(2) { 0.0 }.toFloat()
        fun dy(pt: List<Double>) = pt.getOrElse(3) { 0.0 }.toFloat()

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        polylines.forEach { pl ->
            pl.pts.forEach { pt ->
                val x0 = ox(pt); val y0 = oy(pt)
                val x1 = x0 + dx(pt) * deformScale
                val y1 = y0 + dy(pt) * deformScale
                minX = min(minX, min(x0, x1)); maxX = max(maxX, max(x0, x1))
                minY = min(minY, min(y0, y1)); maxY = max(maxY, max(y0, y1))
            }
        }
        val spanX = (maxX - minX).coerceAtLeast(1e-6f)
        val spanY = (maxY - minY).coerceAtLeast(1e-6f)

        val pad = 14.dp.toPx()
        val scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
        val offX = (size.width - spanX * scale) / 2f
        val offY = (size.height - spanY * scale) / 2f
        fun px(x: Float) = offX + (x - minX) * scale
        fun py(y: Float) = offY + (maxY - y) * scale

        val maxSigma = (members.maxOfOrNull { it.sigma_MPa } ?: 0.0).coerceAtLeast(1e-9)

        // 原始形状（虚线灰）
        if (deformScale > 1.05f) {
            val origPath = Path()
            polylines.forEach { pl ->
                if (pl.pts.isEmpty()) return@forEach
                origPath.moveTo(px(ox(pl.pts.first())), py(oy(pl.pts.first())))
                pl.pts.drop(1).forEach { pt -> origPath.lineTo(px(ox(pt)), py(oy(pt))) }
            }
            drawPath(
                origPath,
                Color.Gray.copy(alpha = 0.55f),
                style = Stroke(
                    1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )
            )
        }

        // 变形形状（按杆件应力着色）
        polylines.forEach { pl ->
            if (pl.pts.size < 2) return@forEach
            val sigma = members.firstOrNull { it.id == pl.member }?.sigma_MPa ?: 0.0
            val t = (sigma / maxSigma).toFloat()
            val path = Path()
            path.moveTo(
                px(ox(pl.pts.first()) + dx(pl.pts.first()) * deformScale),
                py(oy(pl.pts.first()) + dy(pl.pts.first()) * deformScale)
            )
            pl.pts.drop(1).forEach { pt ->
                path.lineTo(px(ox(pt) + dx(pt) * deformScale), py(oy(pt) + dy(pt) * deformScale))
            }
            drawPath(path, com.pocketsim.app.ui.components.ColorRamp.colorAt(t),
                style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
