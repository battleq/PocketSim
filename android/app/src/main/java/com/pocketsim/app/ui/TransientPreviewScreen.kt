/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.AppContainer
import com.pocketsim.app.R
import com.pocketsim.app.TransientResult
import com.pocketsim.app.TransientSpec
import com.pocketsim.app.Units
import com.pocketsim.app.modelDisplayName
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI

/**
 * 时间历程预览确认页（悬臂杆挂重 + 温度先升后降）：
 *  - 温度曲线示意图（三角/正弦，横轴为时间）
 *  - 框架线框示意图（确认结构与支座）
 *  - 模型信息卡：恒载、参与温度变化的杆件、持续时间/步数、材料耐温提示
 *  - "开始计算" → engine.runTransient → 结果页（曲线 + 时间滑杆）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransientPreviewScreen(
    container: AppContainer,
    spec: TransientSpec,
    onBack: () -> Unit,
    onResult: (TransientSpec, TransientResult) -> Unit
) {
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val unknownError = stringResource(R.string.error_unknown_default)

    val frame = spec.frame
    val base = spec.temp_base_c
    val peak = spec.temp_peak_c
    val allMembers = stringResource(R.string.transient_all_members)
    val membersPrefix = stringResource(R.string.transient_members_prefix)
    val enumSep = stringResource(R.string.common_enum_sep)
    val membersText = spec.members?.let { ids ->
        if (ids.isEmpty()) allMembers else membersPrefix + ids.joinToString(enumSep)
    } ?: allMembers

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transient_preview_title)) },
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    error?.let {
                        Text(
                            text = stringResource(R.string.frame_run_failed, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    Button(
                        onClick = {
                            if (running) return@Button
                            scope.launch {
                                running = true
                                error = null
                                try {
                                    val result = container.engine.runTransient(spec)
                                    onResult(spec, result)
                                } catch (e: Exception) {
                                    error = e.message ?: unknownError
                                } finally {
                                    running = false
                                }
                            }
                        },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (running) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Text(
                                text = stringResource(R.string.transient_running),
                                style = MaterialTheme.typography.labelLarge
                            )
                        } else {
                            Text(stringResource(R.string.param_btn_run))
                        }
                    }
                }
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
            item {
                Text(
                    text = stringResource(R.string.transient_preview_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Card(shape = RoundedCornerShape(4.dp)) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val defaultName = stringResource(R.string.transient_default_name)
                        Text(
                            text = modelDisplayName(spec.name).ifBlank { defaultName },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TemperatureProfilePlot(
                            base = base,
                            peak = peak,
                            profile = spec.profile,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                        )
                        Text(
                            text = stringResource(
                                R.string.transient_profile_note_fmt,
                                durationText(spec.duration_s),
                                spec.steps + 1,
                                formatNum(base),
                                formatNum(peak),
                                formatNum(base)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Card(shape = RoundedCornerShape(4.dp)) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val frameDefaultName = stringResource(R.string.transient_frame_default_name)
                        Text(
                            text = modelDisplayName(frame.name).ifBlank { frameDefaultName },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        TransientFrameSketch(
                            spec = spec,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        )
                        Text(
                            text = stringResource(R.string.transient_schematic_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Card(shape = RoundedCornerShape(4.dp)) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.transient_info_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(
                                R.string.transient_structure_fmt,
                                frame.nodes.size,
                                frame.members.size,
                                frame.supports.size
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        val loadUdlFmt = stringResource(R.string.transient_load_udl_fmt)
                        val loadForceFmt = stringResource(R.string.transient_load_force_fmt)
                        val loadAreaFmt = stringResource(R.string.frame_load_area_fmt)
                        val dirUp = stringResource(R.string.transient_dir_up)
                        val dirDown = stringResource(R.string.transient_dir_down)
                        val loadSep = stringResource(R.string.solid_generic_separator)
                        Text(
                            text = stringResource(
                                R.string.transient_dead_load_fmt,
                                frame.loads.joinToString(loadSep) {
                                    describeSpecLoad(
                                        it, loadUdlFmt, loadForceFmt, loadAreaFmt, dirUp, dirDown
                                    )
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(
                                R.string.transient_temp_profile_fmt,
                                profileLabel(spec.profile),
                                durationText(spec.duration_s),
                                spec.steps
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(R.string.transient_thermal_members_fmt, membersText),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(R.string.transient_compute_content),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.disclaimer_full),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 恒载一行文案（与框架预览页口径一致，省略 0 分量） */
private fun describeSpecLoad(
    ld: com.pocketsim.app.FrameLoad,
    udlFmt: String,
    forceFmt: String,
    areaFmt: String,
    dirUp: String,
    dirDown: String
): String = when (ld.type.trim().lowercase()) {
    "udl" -> udlFmt.format(
        ld.member,
        Units.linearLoad(abs(ld.qy)).toString(),
        if (ld.qy >= 0) dirUp else dirDown
    )
    "area" -> areaFmt.format(ld.members.orEmpty().size, Units.force(abs(ld.total_N)).toString())
    else -> {
        val f = Units.force(kotlin.math.hypot(ld.fx, ld.fy)).toString()
        forceFmt.format(ld.node, f)
    }
}

/** 持续时间文案：≥1 小时用小时，≥1 分钟用分钟，否则秒 */
@Composable
internal fun durationText(seconds: Double): String = when {
    seconds >= 3600 -> String.format(
        java.util.Locale.US, stringResource(R.string.transient_duration_hours_fmt), seconds / 3600.0
    )
    seconds >= 60 -> String.format(
        java.util.Locale.US, stringResource(R.string.transient_duration_minutes_fmt), seconds / 60.0
    )
    else -> String.format(
        java.util.Locale.US, stringResource(R.string.transient_duration_seconds_fmt), seconds
    )
}

/** 时刻文案（结果页滑杆标签用）：3600 → "1.0 h"，600 → "10 min" */
internal fun timeLabel(seconds: Double): String = when {
    seconds >= 3600 -> String.format(java.util.Locale.US, "%.2f h", seconds / 3600.0)
    seconds >= 60 -> String.format(java.util.Locale.US, "%.0f min", seconds / 60.0)
    else -> String.format(java.util.Locale.US, "%.0f s", seconds)
}

@Composable
internal fun profileLabel(profile: String): String = when (profile.trim().lowercase()) {
    "sine" -> stringResource(R.string.transient_profile_sine)
    else -> stringResource(R.string.transient_profile_triangle)
}

/** 温度随时间变化曲线（与 Python transient._temp_at 同一公式） */
internal fun tempAt(profile: String, base: Double, peak: Double, t: Double, duration: Double): Double {
    if (duration <= 0.0) return base
    val ratio = if (profile.trim().lowercase() == "sine") {
        0.5 * (1.0 - cos(2.0 * PI * t / duration))
    } else {
        1.0 - abs(2.0 * t / duration - 1.0)
    }
    return base + (peak - base) * ratio
}

/** 温度曲线图：横轴时间 0~duration，纵轴 base~peak */
@Composable
private fun TemperatureProfilePlot(
    base: Double,
    peak: Double,
    profile: String,
    modifier: Modifier
) {
    val span = (abs(peak - base)).coerceAtLeast(1e-6)
    val yMin = min(base, peak)
    val peakLabelFmt = stringResource(R.string.transient_peak_label_fmt)
    val timeLabel = stringResource(R.string.transient_time_label)
    Canvas(modifier = modifier) {
        val padL = 6.dp.toPx(); val padR = 6.dp.toPx()
        val padT = 12.dp.toPx(); val padB = 18.dp.toPx()
        val w = size.width - padL - padR
        val h = size.height - padT - padB
        fun px(t: Double) = (padL + (t / 1.0) * w).toFloat()
        fun py(temp: Double) = (padT + h * (1.0 - (temp - yMin) / span)).toFloat()

        // 网格与基准线
        drawLine(
            Color.Gray.copy(alpha = 0.35f),
            Offset(padL, padT + h),
            Offset(padL + w, padT + h),
            strokeWidth = 1.dp.toPx()
        )
        val steps = 120
        val path = Path()
        for (i in 0..steps) {
            val x = i.toDouble() / steps
            val temp = tempAt(profile, base, peak, x, 1.0)
            if (i == 0) path.moveTo(px(x), py(temp)) else path.lineTo(px(x), py(temp))
        }
        drawPath(path, Color(0xFFE53935), style = Stroke(2.5.dp.toPx()))

        // 起止/峰值标注
        drawCircle(Color(0xFFE53935), 3.dp.toPx(), Offset(px(0.0), py(base)))
        drawCircle(Color(0xFFE53935), 3.dp.toPx(), Offset(px(1.0), py(base)))
        drawCircle(Color(0xFFE53935), 3.dp.toPx(), Offset(px(0.5), py(peak)))
        drawContext.canvas.nativeCanvas.let { nc ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 11.sp.toPx()
                color = android.graphics.Color.DKGRAY
            }
            nc.drawText(peakLabelFmt.format(formatNum(peak)), px(0.5) + 6f, py(peak) - 4f, paint)
            nc.drawText("${formatNum(base)}°C", padL, py(base) + 14f, paint)
            nc.drawText("0", padL, padT + h + 14f, paint)
            nc.drawText(timeLabel, padL + w - 28f, padT + h + 14f, paint)
        }
    }
}

/**
 * 框架线框示意图：节点圆点 + 杆件连线 + 支座方块；
 * 参与温度变化的杆件用橙色强调，其余灰色。等比缩放居中。
 */
@Composable
private fun TransientFrameSketch(spec: TransientSpec, modifier: Modifier) {
    val frame = spec.frame
    val nodes = frame.nodes
    if (nodes.size < 2 || frame.members.isEmpty()) return
    val thermal = spec.members?.toSet() ?: emptySet()
    Canvas(modifier = modifier) {
        val xs = nodes.map { it.x }
        val ys = nodes.map { it.y }
        val minX = xs.min(); val maxX = xs.max()
        val minY = ys.min(); val maxY = ys.max()
        val spanX = (maxX - minX).coerceAtLeast(1e-6)
        val spanY = (maxY - minY).coerceAtLeast(1e-6)
        val pad = 14.dp.toPx()
        val scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
        val offX = (size.width - spanX * scale) / 2f
        val offY = (size.height - spanY * scale) / 2f
        fun px(x: Double) = (offX + (x - minX) * scale).toFloat()
        fun py(y: Double) = (offY + (maxY - y) * scale).toFloat()
        val nodeMap = nodes.associateBy { it.id }

        frame.members.forEach { mem ->
            val i = nodeMap[mem.i] ?: return@forEach
            val j = nodeMap[mem.j] ?: return@forEach
            val hot = thermal.isEmpty() || mem.id in thermal
            drawLine(
                if (hot) Color(0xFFEF6C00) else Color(0xFF78909C),
                Offset(px(i.x), py(i.y)),
                Offset(px(j.x), py(j.y)),
                strokeWidth = (if (hot) 3.5f else 2.5f).dp.toPx()
            )
        }
        nodes.forEach { n ->
            drawCircle(Color(0xFF546E7A), 3.dp.toPx(), Offset(px(n.x), py(n.y)))
        }
        frame.supports.forEach { sup ->
            val n = nodeMap[sup.node] ?: return@forEach
            val sizePx = 8.dp.toPx()
            drawRect(
                Color(0xFF37474F),
                topLeft = Offset(px(n.x) - sizePx / 2, py(n.y) + 2.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(sizePx, sizePx)
            )
        }
    }
}
