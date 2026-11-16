/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.R
import com.pocketsim.app.SafetyLogic
import com.pocketsim.app.SolidRenderData
import com.pocketsim.app.SolidResult
import com.pocketsim.app.SolidWallModel
import com.pocketsim.app.Units
import com.pocketsim.app.ui.components.ColorLegend
import com.pocketsim.app.ui.components.ColorRamp
import com.pocketsim.app.ui.components.MetricCard

/**
 * 三维实体求解结果页（墙体风压下的内部应力）：
 *  - 结论横幅（注意/危险）：薄弱部位 + 破坏形式 + 临界风速
 *  - 指标三联卡（墙顶侧移 / 墙根弯曲应力 / 综合安全系数）
 *  - 三维应力云图（四面体表面 von Mises 着色，可旋转/缩放）
 *  - 校核项、与悬臂梁理论对比、加强建议、通俗分析过程、计算假定
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolidResultScreen(
    model: SolidWallModel,
    result: SolidResult,
    onBack: () -> Unit
) {
    val grade = SafetyLogic.grade(result.metrics.safety_factor)
        .takeIf { it != SafetyLogic.Grade.UNKNOWN }
        ?: SafetyLogic.gradeFromVerdict(result.metrics.verdict)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(result.model_name.ifBlank { model.name.ifBlank { stringResource(R.string.solid_result_default_name) } }) },
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
                                text = if (danger) stringResource(R.string.solid_verdict_danger) else stringResource(R.string.solid_verdict_caution),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = accent
                            )
                            Text(
                                text = stringResource(R.string.solid_safety_fmt, formatNum(result.metrics.safety_factor)) +
                                    "（" + result.checks.joinToString(" / ") {
                                        "${it.name} ${formatNum(it.safety_factor)}"
                                    } + "）",
                                style = MaterialTheme.typography.bodySmall,
                                color = bodyColor
                            )
                            Text(
                                text = stringResource(R.string.solid_weak_part_fmt, result.weak_part),
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
                            if (result.critical_speed_m_s != null) {
                                val beaufortPart = result.critical_beaufort?.let {
                                    stringResource(R.string.solid_critical_beaufort_fmt, it)
                                } ?: ""
                                Text(
                                    text = stringResource(
                                        R.string.solid_critical_speed_fmt,
                                        Units.speed(result.critical_speed_m_s).toString(),
                                        beaufortPart,
                                        result.critical_mode ?: stringResource(R.string.solid_critical_mode_default)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = accent
                                )
                            }
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
                    val dispQ = Units.displacement(result.metrics.top_disp_mm)
                    val bendQ = Units.stress(result.metrics.bend_MPa)
                    val compQ = Units.stress(result.metrics.max_compression_MPa)
                    MetricCard(
                        title = stringResource(R.string.solid_top_disp_title),
                        value = dispQ.text,
                        unit = dispQ.unit,
                        footer = stringResource(R.string.solid_max_disp_fmt, Units.displacement(result.metrics.max_disp_mm).toString()),
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = stringResource(R.string.solid_bending_stress_title),
                        value = bendQ.text,
                        unit = bendQ.unit,
                        footer = stringResource(R.string.solid_max_compression_fmt, compQ.toString()),
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = stringResource(R.string.solid_safety_title),
                        value = formatNum(result.metrics.safety_factor),
                        unit = "",
                        accent = gradeColor(grade),
                        footer = gradeLabel(grade),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ---- 三维应力云图 ----
            result.render?.let { render ->
                item {
                    Card(shape = RoundedCornerShape(4.dp)) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.solid_vonmises_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            SolidStressPlot(
                                render = render,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(300.dp)
                            )
                            val vmax = render.values.maxOrNull() ?: Units.stress(result.metrics.max_stress_MPa).value
                            ColorLegend(
                                minLabel = stringResource(R.string.solid_stress_low_fmt, render.unit),
                                maxLabel = stringResource(R.string.solid_stress_high_fmt, "${formatNum(vmax)} ${render.unit}")
                            )
                            Text(
                                text = stringResource(R.string.solid_3d_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(
                                    R.string.solid_result_summary_fmt,
                                    Units.displacement(result.metrics.top_disp_mm).toString(),
                                    Units.stress(result.metrics.bend_MPa).toString(),
                                    Units.stress(result.metrics.max_compression_MPa).toString()
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ---- 工程校核 ----
            item {
                Card(shape = RoundedCornerShape(4.dp)) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.solid_checks_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        result.checks.forEachIndexed { index, check ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = stringResource(R.string.solid_check_safety_fmt, check.name, formatNum(check.safety_factor)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = if (check.pass) Color(0xFF2E7D32) else Color(0xFFC62828)
                                )
                                val vq = Units.convert(check.value, check.unit)
                                val lq = Units.convert(check.limit, check.unit)
                                Text(
                                    text = stringResource(R.string.solid_check_load_resist_fmt, formatNum(vq.value), vq.unit, formatNum(lq.value), lq.unit),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        val load = result.load
                        Text(
                            text = stringResource(R.string.solid_wind_load_summary_fmt, Units.pressure(load.pressure_Pa).toString(), formatNum(load.cp_net), Units.forceK(load.force_N).toString(), Units.forceK(result.geometry.weight_kN * 1000.0).toString()),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // ---- 与理论对比 ----
            result.theory_check?.let { theory ->
                item {
                    Card(shape = RoundedCornerShape(4.dp)) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.solid_theory_compare_title_fmt, theory.model),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.solid_theory_disp_fmt, Units.displacement(result.metrics.top_disp_mm).toString(), Units.displacement(theory.deflection_mm).toString(), formatNum(theory.deflection_error_pct)),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = stringResource(R.string.solid_theory_bend_fmt, Units.stress(result.metrics.bend_MPa).toString(), Units.stress(theory.bend_stress_MPa).toString(), formatNum(theory.bend_error_pct)),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = stringResource(R.string.solid_theory_converged),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                                text = stringResource(R.string.solid_advice_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            result.advice.forEach { advice ->
                                Text(text = "· $advice", style = MaterialTheme.typography.bodySmall)
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
                                text = stringResource(R.string.solid_narrative_title),
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
                                text = stringResource(R.string.solid_assumptions_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            result.assumptions.forEach { a ->
                                Text(
                                    text = "· $a",
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
                        R.string.solid_generic_mesh_info_fmt,
                        result.material,
                        Units.stress(result.material_params.eMPa).toString(),
                        formatNum(result.material_params.nu),
                        result.mesh.n_elements,
                        result.mesh.n_vertices,
                        result.mesh.dof,
                        formatNum(result.timing.total_ms),
                        result.mesh.solver
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 三维实体默认视角 */
private val SOLID_VIEW = View3D(yawDeg = 34.0, pitchDeg = 20.0)

/**
 * 三维应力云图：
 *  - 由四面体单元提取外表面三角形（只画表面，内部单元不可见）；
 *  - 每个三角形按三个节点的平均 von Mises 值映射 ColorRamp；
 *  - 画家算法按进深从远到近绘制，叠极淡的单元边界线；
 *  - 单指拖拽旋转、双指捏合缩放，右上角重置视角。
 */
@Composable
fun SolidStressPlot(render: SolidRenderData, modifier: Modifier = Modifier) {
    var view by remember { mutableStateOf(SOLID_VIEW) }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        view = view.copy(
                            yawDeg = view.yawDeg + pan.x * 0.35,
                            pitchDeg = (view.pitchDeg + pan.y * 0.35).coerceIn(-85.0, 85.0),
                            zoom = (view.zoom * zoom).coerceIn(0.4f, 4f)
                        )
                    }
                }
        ) {
            val nodes = render.nodes
            if (nodes.size < 4 || render.tets.isEmpty()) return@Canvas

            // 世界坐标：按 render.up_axis 决定屏幕竖直轴。
            // 墙体/box/cylinder 的竖直轴是 Python 的 y（映射到显示 z，厚度 z→显示 y）；
            // 水平板 plate 的竖直轴是 Python 的 z（不交换 y/z）。
            val upAxis = render.up_axis.lowercase()
            fun world(i: Int): WorldPoint {
                val n = nodes[i]
                val x = n.getOrElse(0) { 0.0 }
                val y = n.getOrElse(1) { 0.0 }
                val z = n.getOrElse(2) { 0.0 }
                return when (upAxis) {
                    "z" -> WorldPoint(x = x, y = y, z = z)
                    "x" -> WorldPoint(x = y, y = z, z = x)
                    else -> WorldPoint(x = x, y = z, z = y)
                }
            }

            val worldPoints = nodes.indices.map { world(it) }
            val projector = SceneProjector(
                view = view,
                bounds = viewBounds(worldPoints, view),
                canvasWidth = size.width,
                canvasHeight = size.height,
                padPx = 22.dp.toPx()
            )
            val projected = worldPoints.map { projector.project(it) }

            val vmin = render.values.minOrNull() ?: 0.0
            val vmax = render.values.maxOrNull() ?: 1.0
            val vspan = (vmax - vmin).coerceAtLeast(1e-9)

            data class FaceDraw(
                val a: PixelPoint,
                val b: PixelPoint,
                val c: PixelPoint,
                val color: Color
            )

            val faces = renderFaces(render.tets).mapNotNull { f ->
                if (f.any { it < 0 || it >= nodes.size }) return@mapNotNull null
                val v = (render.values.getOrElse(f[0]) { vmin } +
                    render.values.getOrElse(f[1]) { vmin } +
                    render.values.getOrElse(f[2]) { vmin }) / 3.0
                FaceDraw(
                    a = projected[f[0]],
                    b = projected[f[1]],
                    c = projected[f[2]],
                    color = ColorRamp.colorAt(((v - vmin) / vspan).toFloat())
                )
            }

            painterOrder(faces) { (it.a.depth + it.b.depth + it.c.depth) / 3.0 }.forEach { f ->
                val path = Path().apply {
                    moveTo(f.a.x, f.a.y)
                    lineTo(f.b.x, f.b.y)
                    lineTo(f.c.x, f.c.y)
                    close()
                }
                drawPath(path, f.color)
                drawPath(path, Color(0xFF212121).copy(alpha = 0.10f), style = Stroke(0.8f))
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { view = SOLID_VIEW }) {
                Text(
                    text = stringResource(R.string.frame_3d_reset),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 渲染面提取：实体网格（4 节点四面体）走 [boundaryFaces] 提取外表面；
 * 壳网格（shell_vessel 下发的就是 3 节点三角形壳面，无体单元）直接作为面列表，
 * 否则会被四面体提取逻辑按 size<4 全部跳过，导致云图空白。
 */
private fun renderFaces(cells: List<List<Int>>): List<IntArray> =
    if (cells.all { it.size == 3 }) cells.map { it.toIntArray() }
    else boundaryFaces(cells)

/**
 * 提取四面体网格的外表面三角形：内部面被两个单元共享（计数 2），只保留计数 1 的面。
 * 返回每面三个节点索引（保持单元内的原始顶点顺序，保证三角形绕向一致）。
 */
private fun boundaryFaces(tets: List<List<Int>>): List<IntArray> {
    val local = arrayOf(
        intArrayOf(0, 1, 2),
        intArrayOf(0, 1, 3),
        intArrayOf(0, 2, 3),
        intArrayOf(1, 2, 3)
    )
    val counts = HashMap<Long, Int>()
    val firstFace = HashMap<Long, IntArray>()

    tets.forEach { tet ->
        if (tet.size < 4) return@forEach
        local.forEach { f ->
            val a = tet[f[0]]
            val b = tet[f[1]]
            val c = tet[f[2]]
            val sorted = intArrayOf(a, b, c).apply { sort() }
            // 节点数远小于 2^21（~200 万），三段 21 位可无冲突打包
            val key = (sorted[0].toLong() shl 42) or
                (sorted[1].toLong() shl 21) or
                sorted[2].toLong()
            counts[key] = (counts[key] ?: 0) + 1
            if (!firstFace.containsKey(key)) firstFace[key] = intArrayOf(a, b, c)
        }
    }

    val out = ArrayList<IntArray>()
    counts.forEach { (key, count) ->
        if (count == 1) firstFace[key]?.let { out.add(it) }
    }
    return out
}
