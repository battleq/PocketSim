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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.AppContainer
import com.pocketsim.app.R
import com.pocketsim.app.SeepageModel
import com.pocketsim.app.SeepageResult
import com.pocketsim.app.Units
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * 渗流模型预览确认页（水从大坝坝基渗过）：
 *  - Canvas 示意：坝体梯形截面 + 上下游水位 + 坝基（按比例）
 *  - 模型信息卡：坝体尺寸、材料、上下游水位、坝基参数
 *  - "开始计算" → engine.runSeepage → 渗流结果页（水头云图 + 渗流线 + 稳定性结论）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeepagePreviewScreen(
    container: AppContainer,
    model: SeepageModel,
    onBack: () -> Unit,
    onResult: (SeepageModel, SeepageResult) -> Unit
) {
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val unknownError = stringResource(R.string.error_unknown_default)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.seepage_preview_title)) },
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
                                    val result = container.engine.runSeepage(model)
                                    onResult(model, result)
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
                            Text(text = stringResource(R.string.seepage_running), style = MaterialTheme.typography.labelLarge)
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
                    text = stringResource(R.string.seepage_preview_desc),
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
                        val defaultName = stringResource(R.string.seepage_default_name)
                        Text(
                            text = model.name.ifBlank { defaultName },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        SeepageDamSketch(
                            model = model,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                        )
                        Text(
                            text = stringResource(R.string.seepage_schematic_note),
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
                            text = stringResource(R.string.seepage_info_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        val baseWidth = model.dam.crest_width_m +
                            (model.dam.upstream_slope + model.dam.downstream_slope) * model.dam.height_m
                        Text(
                            text = stringResource(
                                R.string.seepage_dim_fmt,
                                Units.meters(model.dam.height_m).toString(),
                                Units.meters(model.dam.crest_width_m).toString(),
                                Units.meters(baseWidth).toString()
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(
                                R.string.seepage_slope_fmt,
                                formatSlope(model.dam.upstream_slope),
                                formatSlope(model.dam.downstream_slope)
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(
                                R.string.seepage_water_level_fmt,
                                Units.meters(model.upstream_level_m).toString(),
                                Units.meters(model.downstream_level_m).toString()
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        val unspecified = stringResource(R.string.unspecified)
                        val permFmt = stringResource(R.string.seepage_permeability_fmt)
                        Text(
                            text = model.material.ifBlank { unspecified } +
                                (model.foundation_permeability_m_s?.let {
                                    permFmt.format(formatSci(it))
                                } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(R.string.seepage_compute_content),
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

/** 坡比文案：1:0.75 表示每 1 m 高对应 0.75 m 水平宽度 */
@Composable
internal fun formatSlope(slope: Double): String =
    if (slope <= 1e-9) stringResource(R.string.seepage_slope_vertical)
    else stringResource(R.string.seepage_slope_ratio_fmt, formatNum(slope))

/** 科学计数法（渗透系数这类跨数量级数值） */
internal fun formatSci(v: Double): String =
    java.lang.String.format(java.util.Locale.US, "%.1e", v)

/** 坝体截面示意图：坝基方块 + 梯形坝体 + 上下游水体 + 水位标注 */
@Composable
private fun SeepageDamSketch(model: SeepageModel, modifier: Modifier) {
    val h = model.dam.height_m.coerceAtLeast(0.5)
    val crest = model.dam.crest_width_m.coerceAtLeast(0.1)
    val mUp = model.dam.upstream_slope.coerceAtLeast(0.0)
    val mDn = model.dam.downstream_slope.coerceAtLeast(0.0)
    val base = crest + (mUp + mDn) * h
    val h1 = model.upstream_level_m.coerceIn(0.0, h)
    val h2 = model.downstream_level_m.coerceIn(0.0, h)

    val damHeightFmt = stringResource(R.string.seepage_dam_height_fmt)
    val upstreamFmt = stringResource(R.string.seepage_upstream_fmt)
    val downstreamFmt = stringResource(R.string.seepage_downstream_fmt)
    val permeableFoundation = stringResource(R.string.seepage_permeable_foundation)

    Canvas(modifier = modifier) {
        val xmin = -1.6 * h
        val xmax = base + 1.6 * h
        val ymin = -0.85 * h
        val ymax = 1.25 * h
        val pad = 16.dp.toPx()
        val scale = min((size.width - 2 * pad) / (xmax - xmin),
                        (size.height - 2 * pad) / (ymax - ymin))
        val offX = (size.width - (xmax - xmin) * scale) / 2f
        val offY = (size.height - (ymax - ymin) * scale) / 2f
        fun px(x: Double) = (offX + (x - xmin) * scale).toFloat()
        fun py(y: Double) = (offY + (ymax - y) * scale).toFloat()

        // 坝基
        drawRect(
            color = Color(0xFFD7CCC8),
            topLeft = Offset(px(xmin), py(0.0)),
            size = Size(px(xmax) - px(xmin), py(ymin) - py(0.0))
        )
        // 地表线
        drawLine(Color(0xFF6D4C41), Offset(px(xmin), py(0.0)), Offset(px(xmax), py(0.0)),
            strokeWidth = 2.dp.toPx())

        // 上游水体（左侧到坝踵）
        if (h1 > 0) {
            drawRect(
                color = Color(0xFF64B5F6).copy(alpha = 0.45f),
                topLeft = Offset(px(xmin), py(h1)),
                size = Size(px(0.0) - px(xmin), py(0.0) - py(h1))
            )
        }
        // 下游水体（坝趾到右侧）
        if (h2 > 0) {
            drawRect(
                color = Color(0xFF64B5F6).copy(alpha = 0.35f),
                topLeft = Offset(px(base), py(h2)),
                size = Size(px(xmax) - px(base), py(0.0) - py(h2))
            )
        }

        // 坝体梯形
        val damPath = Path().apply {
            moveTo(px(0.0), py(0.0))
            lineTo(px(mUp * h), py(h))
            lineTo(px(mUp * h + crest), py(h))
            lineTo(px(base), py(0.0))
            close()
        }
        drawPath(damPath, Color(0xFFB0BEC5))
        drawPath(damPath, Color(0xFF37474F), style = Stroke(1.5.dp.toPx()))

        // 标注
        drawContext.canvas.nativeCanvas.let { nc ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 11.sp.toPx()
                color = android.graphics.Color.DKGRAY
            }
            nc.drawText(damHeightFmt.format(Units.meters(h).toString()), px(mUp * h + crest) + 8f, py(h / 2), paint)
            nc.drawText(upstreamFmt.format(Units.meters(h1).toString()), px(xmin + 0.1 * h), py(h1) - 4f, paint)
            if (h2 > 0) {
                nc.drawText(downstreamFmt.format(Units.meters(h2).toString()), px(base + 0.15 * h), py(h2) - 4f, paint)
            }
            nc.drawText(permeableFoundation, px(xmin + 0.1 * h), py(-0.55 * h), paint)
        }
    }
}
