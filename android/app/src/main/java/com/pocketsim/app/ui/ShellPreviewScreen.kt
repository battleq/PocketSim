/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.AppContainer
import com.pocketsim.app.LocaleManager
import com.pocketsim.app.LocaleStore
import com.pocketsim.app.R
import com.pocketsim.app.ShellVesselModel
import com.pocketsim.app.SolidGenericResult
import com.pocketsim.app.materialDisplayName
import com.pocketsim.app.ui.components.engineeringChipColors
import kotlinx.coroutines.launch

/**
 * 薄壁容器模型预览确认页：展示 AI 建立的容器几何 / 材料 / 填料摘要，
 * 选择网格档位后调用 [AppContainer.engine.runShell] 求解，结果交给 [ShellResultScreen]。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShellPreviewScreen(
    container: AppContainer,
    model: ShellVesselModel,
    onBack: () -> Unit,
    onResult: (ShellVesselModel, SolidGenericResult) -> Unit,
    onRegenRequest: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val failedFmt = stringResource(R.string.param_run_failed)
    val defaultName = stringResource(R.string.shell_default_name)
    val english = LocaleManager.currentLanguageCode(LocalContext.current) == LocaleStore.LANG_EN

    var meshLevel by remember { mutableStateOf(model.mesh_level.ifBlank { "medium" }) }
    var running by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val section = model.geometry.section.trim().lowercase()
    val isCircle = section == "circle" || section == "circular" || section == "cylinder"
    val fineHeavy = meshLevel == "fine" || meshLevel == "ultra"

    val geomText = if (isCircle) {
        val r = model.geometry.radius_m
        val h = model.geometry.height_m
        stringResource(
            R.string.shell_geom_circle_fmt,
            r?.let { formatNum(it * 2.0) } ?: "—",
            h?.let { formatNum(it) } ?: "—"
        )
    } else {
        val l = model.geometry.length_m
        val w = model.geometry.width_m
        val h = model.geometry.height_m
        stringResource(
            R.string.shell_geom_rect_fmt,
            l?.let { formatNum(it) } ?: "—",
            w?.let { formatNum(it) } ?: "—",
            h?.let { formatNum(it) } ?: "—"
        )
    }
    val thicknessText = model.geometry.thickness_m?.let {
        stringResource(R.string.shell_thickness_fmt, formatNum(it))
    } ?: ""

    val fillText = run {
        val f = model.fill
        when {
            f == null -> stringResource(R.string.shell_fill_default)
            f is kotlinx.serialization.json.JsonPrimitive && f.isString -> f.content
            else -> stringResource(R.string.shell_fill_custom)
        }
    }
    val depthText = model.fill_depth_m?.let { stringResource(R.string.shell_fill_depth_fmt, formatNum(it)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(model.name.ifBlank { defaultName }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.shell_preview_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---- 模型摘要 ----
            item {
                Card(shape = RoundedCornerShape(4.dp)) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 简易示意图（矩形 / 圆柱轮廓）
                        val line = MaterialTheme.colorScheme.primary
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        ) {
                            val stroke = Stroke(width = 4f)
                            if (isCircle) {
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val r = minOf(size.width, size.height) / 2f - 20f
                                drawCircle(color = line, radius = r, center = Offset(cx, cy), style = stroke)
                                // 中心竖线示意轴线
                                drawLine(line, Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
                            } else {
                                val left = 40f
                                val top = 20f
                                val right = size.width - 40f
                                val bottom = size.height - 20f
                                drawRect(color = line, topLeft = Offset(left, top), size = Size(right - left, bottom - top), style = stroke)
                            }
                        }
                        Text(
                            text = stringResource(R.string.solid_generic_geometry_fmt, geomText + thicknessText),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.solid_generic_material_fmt, materialDisplayName(model.material, english), ""),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.shell_fill_fmt, fillText) + (depthText?.let { "，$it" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.shell_compute_content),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.shell_preview_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- 网格档位 ----
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.shell_mesh_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // FlowRow：窄屏放不下 4 个档位时自动换行，避免溢出；
                    // 标签文字居中，长文案换行后各行仍对齐
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("coarse" to R.string.mesh_coarse, "medium" to R.string.mesh_medium,
                            "fine" to R.string.mesh_fine, "ultra" to R.string.mesh_ultra).forEach { (key, res) ->
                            FilterChip(
                                selected = meshLevel == key,
                                onClick = { meshLevel = key },
                                colors = engineeringChipColors(),
                                label = {
                                    Text(
                                        text = stringResource(res),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            )
                        }
                    }
                    if (fineHeavy) {
                        Text(
                            text = stringResource(R.string.shell_mesh_warn_heavy),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ---- 运行 / 错误 ----
            item {
                errorMsg?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Button(
                    enabled = !running,
                    onClick = {
                        running = true
                        errorMsg = null
                        scope.launch {
                            try {
                                val m = model.copy(mesh_level = meshLevel)
                                val r = container.engine.runShell(m)
                                onResult(m, r)
                            } catch (e: Exception) {
                                errorMsg = failedFmt.format(e.message ?: "")
                            } finally {
                                running = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.shell_running))
                    } else {
                        Text(stringResource(R.string.param_btn_run))
                    }
                }
            }

            // ---- 让 AI 重新建模 ----
            item {
                RegenFeedbackCard(onRegenRequest)
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

/** "修改模型"：弹出对话框让用户描述哪里不对，带回对话让 AI 重新建模。 */
@Composable
private fun RegenFeedbackCard(onRegenRequest: (String) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    OutlinedButton(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.solid_modify_btn))
    }
    if (showDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.solid_regen_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.shell_regen_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = feedback,
                        onValueChange = { feedback = it },
                        placeholder = { Text(stringResource(R.string.solid_generic_regen_placeholder)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        if (feedback.isNotBlank()) {
                            onRegenRequest(feedback)
                            showDialog = false
                        }
                    },
                    enabled = feedback.isNotBlank()
                ) { Text(stringResource(R.string.solid_regen_btn)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
