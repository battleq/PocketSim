/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.AppContainer
import com.pocketsim.app.AppSettings
import com.pocketsim.app.LocaleManager
import com.pocketsim.app.LocaleStore
import com.pocketsim.app.R
import com.pocketsim.app.SolidResult
import com.pocketsim.app.SolidWallModel
import com.pocketsim.app.Units
import com.pocketsim.app.materialDisplayName
import kotlinx.coroutines.launch

/**
 * 三维实体模型预览确认页（墙体开脆弱 / 风压下的墙体内部应力）：
 *  - Canvas 等轴测示意图：按比例的墙体方块 + 墙底固定 + 风压箭头
 *  - 模型信息卡：墙体尺寸、材料、风荷载、网格档位
 *  - "开始计算" → engine.runSolid → 三维实体结果页（应力云图 + 变形/安全系数）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SolidPreviewScreen(
    container: AppContainer,
    model: SolidWallModel,
    onBack: () -> Unit,
    onResult: (SolidWallModel, SolidResult) -> Unit,
    onRegenRequest: (String) -> Unit = {}
) {
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showRegenDialog by remember { mutableStateOf(false) }
    var regenFeedback by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val english = LocaleManager.currentLanguageCode(context) == LocaleStore.LANG_EN

    // 网格档位：AI 未指定时按设置页的"默认网格档位"预选，用户也可在预览页直接改
    val settings by container.settingsRepository.settings.collectAsState(initial = AppSettings())
    val initialMesh = remember(model) { normalizeSolidMesh(model.mesh_level) }
    var meshLevel by remember(model) { mutableStateOf(initialMesh) }
    var meshTouched by remember(model) { mutableStateOf(false) }
    LaunchedEffect(settings.defaultMeshLevel) {
        if (!meshTouched && initialMesh == "medium") {
            meshLevel = normalizeSolidMesh(settings.defaultMeshLevel)
        }
    }

    if (showRegenDialog) {
        AlertDialog(
            onDismissRequest = { showRegenDialog = false },
            title = { Text(stringResource(R.string.solid_regen_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.solid_regen_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = regenFeedback,
                        onValueChange = { regenFeedback = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        placeholder = { Text(stringResource(R.string.solid_regen_placeholder)) }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val fb = regenFeedback.trim()
                        if (fb.isNotBlank()) {
                            showRegenDialog = false
                            regenFeedback = ""
                            onRegenRequest(fb)
                        }
                    },
                    enabled = regenFeedback.trim().isNotBlank()
                ) { Text(stringResource(R.string.solid_regen_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { showRegenDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.solid_preview_title)) },
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
                    OutlinedButton(
                        onClick = {
                            regenFeedback = ""
                            showRegenDialog = true
                        },
                        enabled = !running,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Text(stringResource(R.string.solid_modify_btn))
                    }
                    Button(
                        onClick = {
                            if (running) return@Button
                            scope.launch {
                                running = true
                                error = null
                                try {
                                    val runModel = model.copy(mesh_level = meshLevel)
                                    val result = container.engine.runSolid(runModel)
                                    onResult(runModel, result)
                                } catch (e: Exception) {
                                    error = e.message ?: context.getString(R.string.error_unknown_default)
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
                            Text(text = stringResource(R.string.solid_running), style = MaterialTheme.typography.labelLarge)
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
                    text = stringResource(R.string.solid_preview_desc),
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
                        Text(
                            text = model.name.ifBlank { stringResource(R.string.solid_default_name) },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        SolidWallSketch(
                            model = model,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                        )
                        Text(
                            text = stringResource(R.string.solid_schematic_note),
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
                            text = stringResource(R.string.solid_info_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(
                                R.string.solid_dim_fmt,
                                Units.meters(model.wall.length_m).toString(),
                                Units.meters(model.wall.height_m).toString(),
                                Units.length(model.wall.thickness_m * 1000).toString()
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(R.string.solid_material_fmt, materialDisplayName(model.material, english).ifBlank { stringResource(R.string.unspecified) }) + solidMaterialText(context, model),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(R.string.solid_load_fmt, solidWindText(context, model)),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(R.string.solid_mesh_hint),
                            style = MaterialTheme.typography.bodySmall
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            SolidMeshChip("coarse", meshLevel) { meshLevel = it; meshTouched = true }
                            SolidMeshChip("medium", meshLevel) { meshLevel = it; meshTouched = true }
                            SolidMeshChip("fine", meshLevel) { meshLevel = it; meshTouched = true }
                            SolidMeshChip("ultra", meshLevel) { meshLevel = it; meshTouched = true }
                        }
                        Text(
                            text = stringResource(R.string.solid_compute_content),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                val heavy = meshLevel in listOf("fine", "ultra")
                Card(
                    shape = RoundedCornerShape(4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (heavy) MaterialTheme.colorScheme.errorContainer
                                         else MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (heavy) MaterialTheme.colorScheme.onErrorContainer
                                   else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (heavy) {
                                stringResource(R.string.solid_fine_warn)
                            } else {
                                stringResource(R.string.solid_normal_warn)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (heavy) MaterialTheme.colorScheme.onErrorContainer
                                    else MaterialTheme.colorScheme.onPrimaryContainer
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

/** 材料补充参数（E/ν/密度可选，未给出时留空） */
private fun solidMaterialText(context: Context, model: SolidWallModel): String {
    val parts = ArrayList<String>()
    model.eMPa?.let { parts.add(context.getString(R.string.solid_mat_e_fmt, Units.stress(it).toString())) }
    model.nu?.let { parts.add(context.getString(R.string.solid_mat_nu_fmt, formatNum(it))) }
    model.density_kg_m3?.let { parts.add(context.getString(R.string.solid_mat_density_fmt, Units.density(it).toString())) }
    return if (parts.isEmpty()) "" else "（${parts.joinToString("，")}）"
}

/** 风荷载文案：优先显式风压，其次风级，最后风速 */
private fun solidWindText(context: Context, model: SolidWallModel): String {
    val cp = model.cp_net?.let { context.getString(R.string.solid_wind_cp_fmt, formatNum(it)) } ?: ""
    return when {
        model.pressure_Pa != null && model.pressure_Pa > 0 ->
            context.getString(R.string.solid_wind_pressure_fmt, Units.pressure(model.pressure_Pa).toString(), cp)
        model.beaufort != null ->
            context.getString(R.string.solid_wind_beaufort_fmt, model.beaufort, Units.speed(beaufortSpeed(model.beaufort)).toString(), cp)
        model.wind_speed_m_s != null && model.wind_speed_m_s > 0 ->
            context.getString(R.string.solid_wind_speed_fmt, Units.speed(model.wind_speed_m_s).toString(), cp)
        else -> context.getString(R.string.unspecified)
    }
}

/** 网格档位规整：未知值回落到 medium */
private fun normalizeSolidMesh(raw: String?): String =
    raw?.trim()?.lowercase()?.takeIf { it in setOf("coarse", "medium", "fine", "ultra") }
        ?: "medium"

/** 网格档位选择芯片（预览页可直接改，默认值来自设置页） */
@Composable
private fun SolidMeshChip(value: String, selected: String, onSelect: (String) -> Unit) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelect(value) },
        label = {
            Text(
                text = stringResource(
                    when (value) {
                        "coarse" -> R.string.mesh_coarse
                        "fine" -> R.string.mesh_fine
                        "ultra" -> R.string.mesh_ultra
                        else -> R.string.mesh_medium
                    }
                )
            )
        }
    )
}

/** 墙体等轴测示意图：长方体 + 底面/顶面/迎风面着色 + 墙底固定 + 风压箭头 + 尺寸标注 */
@Composable
private fun SolidWallSketch(model: SolidWallModel, modifier: Modifier) {
    val l = model.wall.length_m.coerceAtLeast(0.1)
    val h = model.wall.height_m.coerceAtLeast(0.1)
    val t = model.wall.thickness_m.coerceAtLeast(0.01)

    // Canvas drawScope 不是 Composable 上下文，字符串需在外层预取
    val labelLength = stringResource(R.string.solid_dim_length_fmt, Units.meters(l).toString())
    val labelHeight = stringResource(R.string.solid_dim_height_fmt, Units.meters(h).toString())
    val labelThickness = stringResource(R.string.solid_dim_thickness_fmt, Units.length(t * 1000).toString())
    val labelWind = stringResource(R.string.solid_wind_arrow)

    Canvas(modifier = modifier) {
        // 世界坐标：x 沿墙长、y 沿墙厚、z 竖直（与 Frame3DPlot 的投影约定一致）
        val view = View3D(yawDeg = 34.0, pitchDeg = 18.0)
        fun wp(x: Double, y: Double, z: Double) = WorldPoint(x, y, z)

        // 8 个角点：0-3 地面（前左/前右/后右/后左），4-7 顶部同序
        val corners = listOf(
            wp(0.0, 0.0, 0.0), wp(l, 0.0, 0.0), wp(l, t, 0.0), wp(0.0, t, 0.0),
            wp(0.0, 0.0, h), wp(l, 0.0, h), wp(l, t, h), wp(0.0, t, h)
        )
        val pad = 34.dp.toPx()
        val projector = SceneProjector(
            view = view,
            bounds = viewBounds(corners, view),
            canvasWidth = size.width,
            canvasHeight = size.height,
            padPx = pad
        )
        val p = corners.map { projector.project(it) }

        fun quad(a: Int, b: Int, c: Int, d: Int) = Path().apply {
            moveTo(p[a].x, p[a].y)
            lineTo(p[b].x, p[b].y)
            lineTo(p[c].x, p[c].y)
            lineTo(p[d].x, p[d].y)
            close()
        }

        // 面：迎风面（y=0，面向观察者）→ 顶面 → 底面（柔和灰阶，避免遮挡）
        drawPath(quad(0, 1, 5, 4), Color(0xFFE3E7EA))
        drawPath(quad(4, 5, 6, 7), Color(0xFFB0BEC5))
        drawPath(quad(0, 1, 2, 3), Color(0xFFCFD8DC))

        // 棱线：可见的 9 条（底面后边与背风面棱线省略）
        val edgeColor = Color(0xFF37474F)
        listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 0,
            4 to 5, 5 to 6, 6 to 7, 7 to 4,
            0 to 4, 1 to 5, 2 to 6, 3 to 7
        ).forEach { (a, b) ->
            drawLine(edgeColor, Offset(p[a].x, p[a].y), Offset(p[b].x, p[b].y),
                strokeWidth = 1.4.dp.toPx())
        }

        // 墙底固定：沿前底边（0-1）与左侧底边（0-3）画斜线阴影
        fun hatch(a: Int, b: Int) {
            val steps = 7
            for (k in 1 until steps) {
                val t0 = k / steps.toFloat()
                val x = p[a].x + (p[b].x - p[a].x) * t0
                val y = p[a].y + (p[b].y - p[a].y) * t0
                drawLine(
                    Color(0xFF546E7A),
                    Offset(x, y),
                    Offset(x - 6.dp.toPx(), y + 7.dp.toPx()),
                    strokeWidth = 1.4.dp.toPx()
                )
            }
            drawLine(
                Color(0xFF37474F),
                Offset(p[a].x, p[a].y), Offset(p[b].x, p[b].y),
                strokeWidth = 2.6.dp.toPx()
            )
        }
        hatch(0, 1)
        hatch(0, 3)

        // 风压箭头：沿 +y（墙厚方向）指向迎风面
        val arrowColor = Color(0xFF1E88E5)
        val xs = listOf(0.18, 0.5, 0.82)
        val zs = listOf(0.25, 0.62, 0.92)
        xs.forEach { fx ->
            zs.forEach { fz ->
                val x = l * fx
                val z = h * fz
                val tail = projector.project(wp(x, -0.55 * t - 0.25 * h, z))
                val head = projector.project(wp(x, 0.0, z))
                drawLine(arrowColor, Offset(tail.x, tail.y), Offset(head.x, head.y),
                    strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                val dx = head.x - tail.x
                val dy = head.y - tail.y
                val norm = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-6f)
                val ux = dx / norm
                val uy = dy / norm
                val wing = 5.dp.toPx()
                drawLine(arrowColor, Offset(head.x, head.y),
                    Offset(head.x - ux * wing + uy * wing * 0.55f,
                        head.y - uy * wing - ux * wing * 0.55f),
                    strokeWidth = 1.8.dp.toPx())
                drawLine(arrowColor, Offset(head.x, head.y),
                    Offset(head.x - ux * wing - uy * wing * 0.55f,
                        head.y - uy * wing + ux * wing * 0.55f),
                    strokeWidth = 1.8.dp.toPx())
            }
        }

        // 尺寸标注
        val nc = drawContext.canvas.nativeCanvas
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.sp.toPx()
            color = android.graphics.Color.DKGRAY
        }
        nc.drawText(
            labelLength,
            (p[0].x + p[1].x) / 2f - 18.dp.toPx(),
            (p[0].y + p[1].y) / 2f + 22.dp.toPx(),
            paint
        )
        nc.drawText(
            labelHeight,
            p[5].x + 8.dp.toPx(),
            (p[1].y + p[5].y) / 2f,
            paint
        )
        nc.drawText(
            labelThickness,
            (p[4].x + p[7].x) / 2f - 34.dp.toPx(),
            (p[4].y + p[7].y) / 2f - 10.dp.toPx(),
            paint
        )
        // 风压标注放在墙脚左侧，避开顶部尺寸标注
        nc.drawText(
            labelWind,
            p[0].x + 4.dp.toPx(),
            p[0].y + 42.dp.toPx(),
            paint
        )
    }
}
