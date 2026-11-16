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
import androidx.compose.ui.geometry.Size
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
import com.pocketsim.app.FlowModel
import com.pocketsim.app.FlowResult
import com.pocketsim.app.LocaleManager
import com.pocketsim.app.LocaleStore
import com.pocketsim.app.R
import com.pocketsim.app.Units
import com.pocketsim.app.materialDisplayName
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * 流体模型预览确认页（风把墙吹倒）：
 *  - Canvas 示意：墙体立面 + 地面 + 来流箭头（按真实比例）
 *  - 模型信息卡：墙体尺寸、材料、风速/风级
 *  - "开始计算" → engine.runFlow → 流体结果页（压力云图 + 流线 + 稳定性结论）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowPreviewScreen(
    container: AppContainer,
    model: FlowModel,
    onBack: () -> Unit,
    onResult: (FlowModel, FlowResult) -> Unit
) {
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val english = LocaleManager.currentLanguageCode(context) == LocaleStore.LANG_EN

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.flow_preview_title)) },
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
                                    val result = container.engine.runFlow(model)
                                    onResult(model, result)
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
                            Text(text = stringResource(R.string.flow_running), style = MaterialTheme.typography.labelLarge)
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
                    text = stringResource(R.string.flow_preview_desc),
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
                            text = model.name.ifBlank { stringResource(R.string.flow_default_name) },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        FlowWallSketch(
                            model = model,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                        )
                        Text(
                            text = stringResource(R.string.flow_schematic_note),
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
                            text = stringResource(R.string.flow_info_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(
                                R.string.flow_dim_fmt,
                                Units.meters(model.wall.height_m).toString(),
                                Units.length(model.wall.thickness_m * 1000).toString(),
                                Units.meters(model.wall.length_m).toString()
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(R.string.flow_material_fmt, materialDisplayName(model.material, english).ifBlank { stringResource(R.string.unspecified) }),
                            style = MaterialTheme.typography.bodySmall
                        )
                        val windText = when {
                            model.wind_speed_m_s != null && model.wind_speed_m_s > 0 ->
                                stringResource(R.string.flow_wind_speed_fmt, Units.speed(model.wind_speed_m_s).toString())
                            model.beaufort != null ->
                                stringResource(R.string.flow_wind_beaufort_fmt, model.beaufort, Units.speed(beaufortSpeed(model.beaufort)).toString())
                            else -> stringResource(R.string.flow_wind_unspecified)
                        }
                        Text(text = stringResource(R.string.flow_inflow_fmt, windText), style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = stringResource(R.string.flow_compute_content),
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

/** 蒲福风级代表风速（与 Python flow.speed_from_beaufort 一致的各级上限） */
internal fun beaufortSpeed(level: Int): Double {
    val upper = doubleArrayOf(
        0.2, 1.5, 3.3, 5.4, 7.9, 10.7, 13.8, 17.1, 20.7, 24.4,
        28.4, 32.6, 36.9, 41.4, 46.1, 50.9, 56.0, 61.2
    )
    return upper[level.coerceIn(0, upper.size - 1)]
}

/** 墙体剖面示意图：地面线 + 按比例墙体 + 左侧来流箭头 */
@Composable
private fun FlowWallSketch(model: FlowModel, modifier: Modifier) {
    val h = model.wall.height_m.coerceAtLeast(0.1)
    val t = model.wall.thickness_m.coerceAtLeast(0.01)

    // Canvas drawScope 不是 Composable 上下文，字符串需在外层预取
    val labelHeight = stringResource(R.string.flow_wall_height_fmt, Units.meters(h).toString())
    val labelThickness = stringResource(R.string.flow_thickness_fmt, Units.length(t * 1000).toString())
    val labelWind = stringResource(R.string.flow_wind_arrow)

    Canvas(modifier = modifier) {
        // 显示范围：取流体域（x∈[-4h,7h]、y∈[0,3h]）靠近墙体的核心区，
        // 窗口宽高比 ~1.6 与画布一致，墙体可占满画布高度（否则墙会缩成细线）
        val xmin = -1.0 * h
        val xmax = 1.6 * h
        val ymax = 1.6 * h
        val pad = 16.dp.toPx()
        val scale = min((size.width - 2 * pad) / (xmax - xmin),
                        (size.height - 2 * pad) / ymax)
        val offX = (size.width - (xmax - xmin) * scale) / 2f
        val offY = (size.height - ymax * scale) / 2f
        fun px(x: Double) = (offX + (x - xmin) * scale).toFloat()
        fun py(y: Double) = (offY + (ymax - y) * scale).toFloat()

        // 地面
        drawLine(
            Color(0xFF546E7A), Offset(px(xmin), py(0.0)), Offset(px(xmax), py(0.0)),
            strokeWidth = 3.dp.toPx()
        )
        // 墙体
        val wallW = max(px(t) - px(0.0), 2f)
        val wallH = py(0.0) - py(h)
        drawRect(
            color = Color(0xFFB0BEC5),
            topLeft = Offset(px(0.0), py(h)),
            size = Size(wallW, wallH)
        )
        drawRect(
            color = Color(0xFF37474F),
            topLeft = Offset(px(0.0), py(h)),
            size = Size(wallW, wallH),
            style = Stroke(1.5.dp.toPx())
        )

        // 来流箭头（左侧，指向墙体）
        val arrowColor = Color(0xFF1E88E5)
        listOf(0.3, 0.65, 1.0, 1.35).forEach { yy ->
            val y = py(yy)
            val x0 = px(xmin + 0.15 * h)
            val x1 = px(-0.5 * h)
            drawLine(arrowColor, Offset(x0, y), Offset(x1, y),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            val wing = 6.dp.toPx()
            val path = Path().apply {
                moveTo(x1, y)
                lineTo(x1 - wing, y - wing * 0.55f)
                lineTo(x1 - wing, y + wing * 0.55f)
                close()
            }
            drawPath(path, arrowColor)
        }

        // 标注
        drawContext.canvas.nativeCanvas.let { nc ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 11.sp.toPx()
                color = android.graphics.Color.DKGRAY
            }
            nc.drawText(labelHeight, px(0.0) + wallW + 8f, py(h / 2), paint)
            nc.drawText(labelThickness, px(0.0) + wallW + 8f, py(h / 2) + 16.sp.toPx(), paint)
            nc.drawText(labelWind, px(xmin + 0.15 * h), py(1.5 * h), paint)
        }
    }
}
