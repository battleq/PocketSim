/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.AppContainer
import com.pocketsim.app.AppJson
import com.pocketsim.app.BuriedPipeRender
import com.pocketsim.app.BuriedPipeResult
import com.pocketsim.app.LocaleManager
import com.pocketsim.app.LocaleStore
import com.pocketsim.app.R
import com.pocketsim.app.SavedProject
import com.pocketsim.app.ShellBuriedPipeModel
import com.pocketsim.app.materialDisplayName
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.max

/** 从 metrics 宽松映射取数值（缺失返回 null，UI 显示「—」） */
private fun Map<String, JsonPrimitive>.num(key: String): Double? = this[key]?.doubleOrNull

/** 安全格式化（null/非有限 → 「—」） */
private fun Double?.f(digits: Int = 2): String =
    if (this == null || !isFinite()) "—" else "%.${digits}f".format(this)

private data class VerdictTheme(val container: Color, val content: Color, val ring: Color)

private fun verdictTheme(v: String): VerdictTheme = when (v) {
    "safe" -> VerdictTheme(Color(0xFFE8F5E9), Color(0xFF1B5E20), Color(0xFF2E7D32))
    "caution" -> VerdictTheme(Color(0xFFFFF8E1), Color(0xFFE65100), Color(0xFFF9A825))
    else -> VerdictTheme(Color(0xFFFFEBEE), Color(0xFFB71C1C), Color(0xFFC62828))
}

private fun checkColor(status: String): Color = when (status) {
    "safe", "pass" -> Color(0xFF2E7D32)
    "caution", "warn" -> Color(0xFFF9A825)
    else -> Color(0xFFC62828)
}

/**
 * 埋地管验算结果页：工程公式法（不跑 FEM）。
 * - [result] 为 null（新建模）时页面内调用求解；非 null（已保存项目回放）时直接展示。
 * 展示：变形椭圆环（挠度放大）+ 关键数值卡片 + 校核清单 + 公式与假设 + 结论建议。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuriedPipeResultScreen(
    container: AppContainer,
    model: ShellBuriedPipeModel,
    result: BuriedPipeResult? = null,
    onBack: () -> Unit,
    /** 返回对话修改模型并重算（为空时不显示"修改模型"） */
    onModify: (() -> Unit)? = null
) {
    var solved by remember { mutableStateOf(result) }
    var solving by remember { mutableStateOf(result == null) }
    var solveError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val defaultTitle = stringResource(R.string.buried_pipe_result_title)

    // 新建模：页面内求解（result 来自已保存项目时直接展示，不再重算）
    LaunchedEffect(Unit) {
        if (result == null) {
            try {
                solved = container.engine.runBuriedPipe(model)
            } catch (e: Exception) {
                solveError = e.message ?: context.getString(R.string.buried_pipe_solve_failed)
            } finally {
                solving = false
            }
        }
    }

    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    fun saveProject(r: BuriedPipeResult) {
        if (saving) return
        saving = true
        saveMessage = null
        scope.launch {
            try {
                val modelJson = AppJson.encodeToString(ShellBuriedPipeModel.serializer(), model)
                val resultJson = AppJson.encodeToString(BuriedPipeResult.serializer(), r)
                val m = r.metrics
                container.projectRepository.save(
                    SavedProject(
                        id = System.currentTimeMillis(),
                        name = model.name.ifBlank { context.getString(R.string.buried_pipe_default_name) },
                        kind = "shell_buried_pipe",
                        modelJson = modelJson,
                        resultJson = resultJson,
                        verdict = r.verdict_zh,
                        maxStressMPa = m.num("max_ring_stress_MPa") ?: 0.0,
                        safetyFactor = m.num("buckling_sf") ?: 0.0,
                        createdAt = System.currentTimeMillis()
                    )
                )
                saveMessage = context.getString(R.string.frame_saved_message)
            } catch (e: Exception) {
                saveMessage = context.getString(R.string.frame_save_failed_fmt, e.message ?: "")
            } finally {
                saving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(model.name.ifBlank { defaultTitle }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.buried_pipe_back))
                    }
                },
                actions = {
                    if (onModify != null) {
                        IconButton(onClick = onModify) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.buried_pipe_modify))
                        }
                    }
                    val s = solved
                    if (s != null) {
                        IconButton(onClick = { saveProject(s) }, enabled = !saving) {
                            Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.buried_pipe_save))
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            solving -> BoxCentered { CircularProgressIndicator() }
            solveError != null -> BoxCentered {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.buried_pipe_solve_failed), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    Text(solveError ?: "", modifier = Modifier.padding(12.dp))
                }
            }
            solved != null -> BuriedPipeBody(model, solved!!, padding, saveMessage)
        }
    }
}

@Composable
private fun BoxCentered(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun BuriedPipeBody(
    model: ShellBuriedPipeModel,
    r: BuriedPipeResult,
    padding: androidx.compose.foundation.layout.PaddingValues,
    saveMessage: String?
) {
    val theme = verdictTheme(r.verdict)
    val m = r.metrics
    val english = LocaleManager.currentLanguageCode(LocalContext.current) == LocaleStore.LANG_EN
    val verdictText = when (r.verdict) {
        "safe" -> stringResource(R.string.buried_pipe_verdict_safe)
        "caution" -> stringResource(R.string.buried_pipe_verdict_caution)
        else -> stringResource(R.string.buried_pipe_verdict_unsafe)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 结论横幅
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = theme.container)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.buried_pipe_verdict_fmt, verdictText), color = theme.content,
                        fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (r.advice.isNotEmpty()) {
                        Text(r.advice.first(), color = theme.content,
                            fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text(
                        stringResource(R.string.buried_pipe_basis_note),
                        color = theme.content, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        // 变形环可视化
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.buried_pipe_ring_title),
                        fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    RingCanvas(render = r.render, lineColor = theme.ring)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.buried_pipe_amp_fmt, r.render.amplification.toString()), fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.buried_pipe_dim_fmt,
                            m.num("outer_diameter_mm").f(1), m.num("cover_depth_m").f(2)),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // 关键数值卡片（2 列）
        item {
            MetricRow(
                stringResource(R.string.buried_pipe_metric_dy), "${m.num("deflection_mm").f(4)}", "mm",
                stringResource(R.string.buried_pipe_metric_dratio), "${m.num("deflection_ratio_pct").f(3)}", "%"
            )
        }
        item {
            MetricRow(
                stringResource(R.string.buried_pipe_metric_stress), "${m.num("max_ring_stress_MPa").f(3)}", "MPa",
                stringResource(R.string.buried_pipe_metric_sratio), "${m.num("stress_ratio").f(3)}", ""
            )
        }
        item {
            MetricRow(
                stringResource(R.string.buried_pipe_metric_pcr), "${m.num("buckling_pcr_kPa").f(1)}", "kPa",
                stringResource(R.string.buried_pipe_metric_bsf), "${m.num("buckling_sf").f(2)}", ""
            )
        }
        item {
            MetricRow(
                stringResource(R.string.buried_pipe_metric_psoil), "${m.num("p_soil_kPa").f(2)}", "kPa",
                stringResource(R.string.buried_pipe_metric_plive), "${m.num("p_live_kPa").f(2)}", "kPa"
            )
        }
        item {
            MetricRow(
                stringResource(R.string.buried_pipe_metric_plat), "${m.num("p_lateral_kPa").f(2)}", "kPa",
                stringResource(R.string.buried_pipe_metric_pint), "${m.num("internal_pressure_kPa").f(1)}", "kPa"
            )
        }

        // 校核清单
        if (r.checks.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.buried_pipe_checks_title), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 6.dp))
                        r.checks.forEach { c ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(c.label, fontSize = 13.sp)
                                Text(c.value, fontSize = 13.sp, color = checkColor(c.status),
                                    fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }

        // 材料与几何参数
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.buried_pipe_params_title), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 6.dp))
                    ParamLine(stringResource(R.string.buried_pipe_param_material),
                        stringResource(R.string.buried_pipe_param_material_fmt,
                            materialDisplayName(m["material"]?.content ?: model.material, english),
                            m.num("E_MPa").f(0), m.num("nu").f(3), m.num("strength_MPa").f(1)))
                    ParamLine(stringResource(R.string.buried_pipe_param_dim), "${m.num("outer_diameter_mm").f(1)} mm / ${m.num("thickness_mm").f(2)} mm")
                    ParamLine(stringResource(R.string.buried_pipe_param_cover), "${m.num("cover_depth_m").f(2)} m / ${m.num("internal_pressure_kPa").f(1)} kPa")
                    ParamLine(stringResource(R.string.buried_pipe_param_soil), "${m.num("soil_gamma_kN_m3").f(1)} kN/m³ / ${m.num("soil_phi_deg").f(1)}° / ${m.num("soil_cohesion_kPa").f(1)} kPa")
                    ParamLine(stringResource(R.string.buried_pipe_param_wheel), "${m.num("wheel_load_kN").f(1)} kN / ${m.num("tire_contact_m").f(2)} m")
                }
            }
        }

        // 公式与假设
        if (r.formulas.isNotEmpty() || r.assumptions.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.buried_pipe_formulas_title), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 6.dp))
                        if (r.formulas.isNotEmpty()) {
                            Text(stringResource(R.string.buried_pipe_formulas_used), fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 2.dp))
                            r.formulas.forEach { f ->
                                Text("· $f", fontSize = 12.sp,
                                    modifier = Modifier.padding(vertical = 1.dp))
                            }
                        }
                        if (r.assumptions.isNotEmpty()) {
                            Text(stringResource(R.string.buried_pipe_assumptions_title), fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                            r.assumptions.forEach { a ->
                                Text("· $a", fontSize = 12.sp,
                                    modifier = Modifier.padding(vertical = 1.dp))
                            }
                        }
                    }
                }
            }
        }

        // 结论建议
        if (r.advice.size > 1) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.buried_pipe_advice_title), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 6.dp))
                        r.advice.drop(1).forEach { a ->
                            Text("· $a", fontSize = 12.sp, modifier = Modifier.padding(vertical = 1.dp))
                        }
                    }
                }
            }
        }

        // 保存提示
        saveMessage?.let {
            item { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
        }

        item {
            Text(
                stringResource(R.string.buried_pipe_disclaimer),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun MetricRow(
    leftLabel: String, leftValue: String, leftUnit: String,
    rightLabel: String, rightValue: String, rightUnit: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MetricCell(leftLabel, leftValue, leftUnit)
        MetricCell(rightLabel, rightValue, rightUnit)
    }
}

@Composable
private fun RowScope.MetricCell(label: String, value: String, unit: String) {
    Card(
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (unit.isNotEmpty()) {
                    Text(" $unit", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun ParamLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp)
    }
}

/** Canvas 2D 绘制原始圆环（虚线）与变形后椭圆环（实线，已放大），坐标单位 mm */
@Composable
private fun RingCanvas(render: BuriedPipeRender, lineColor: Color) {
    Canvas(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        val orig = render.original
        val def = render.deformed
        if (orig.isEmpty()) return@Canvas
        val all = orig + def
        val maxExt = max(all.maxOf { abs(it.getOrElse(0) { 0.0 }) },
            all.maxOf { abs(it.getOrElse(1) { 0.0 }) }).coerceAtLeast(1.0)
        val pad = 28f
        val scale = (minOf(size.width, size.height) - pad * 2) / (maxExt * 2)
        val cx = size.width / 2
        val cy = size.height / 2
        fun tx(x: Double, y: Double) = Offset(cx + (x * scale).toFloat(), cy - (y * scale).toFloat())

        val origPath = Path().apply {
            orig.forEachIndexed { i, p ->
                val o = tx(p.getOrElse(0) { 0.0 }, p.getOrElse(1) { 0.0 })
                if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
            }
            close()
        }
        drawPath(
            origPath, color = Color.Gray,
            style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        )

        if (def.isNotEmpty()) {
            val defPath = Path().apply {
                def.forEachIndexed { i, p ->
                    val o = tx(p.getOrElse(0) { 0.0 }, p.getOrElse(1) { 0.0 })
                    if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                }
                close()
            }
            drawPath(defPath, color = lineColor, style = Stroke(width = 3f))
        }

        drawCircle(color = Color.Gray, radius = 3f, center = Offset(cx, cy))
    }
}
