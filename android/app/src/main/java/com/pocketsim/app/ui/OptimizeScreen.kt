/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import android.graphics.Bitmap
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketsim.app.AppContainer
import com.pocketsim.app.AppJson
import com.pocketsim.app.ChangeKind
import com.pocketsim.app.FrameModel
import com.pocketsim.app.LocaleManager
import com.pocketsim.app.LocaleStore
import com.pocketsim.app.MetricDelta
import com.pocketsim.app.OptimizeCompare
import com.pocketsim.app.OptimizeRunner
import com.pocketsim.app.OptimizeSession
import com.pocketsim.app.OptimizeState
import com.pocketsim.app.OptimizeTarget
import com.pocketsim.app.R
import com.pocketsim.app.SolidGenericModel
import com.pocketsim.app.VerifiedOptimization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 优化建议页（第一期：Frame / SolidGeneric）。
 *
 * 进入即执行一次"诊断 + 优化 + 有限元验证"闭环，展示：
 * 诊断 → 方案说明 → B 级拟物化效果图（新增/修改构件高亮）→ 指标对比 → 辅助标注。
 * 结果页只在「注意 / 危险」时提供入口（见 OptimizeGate）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptimizeScreen(
    container: AppContainer,
    target: OptimizeTarget,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportDoneTpl = stringResource(R.string.optimize_export_done_fmt)
    val exportFailedTpl = stringResource(R.string.optimize_export_failed_fmt)
    val language = LocaleManager.currentLanguageCode(context)
    val english = language == LocaleStore.LANG_EN
    val settings by container.settingsRepository.settings.collectAsState(initial = null)
    var reload by remember { mutableStateOf(0) }

    val count = settings?.optimizeSuggestionCount ?: 1
    val session = remember(count, reload) {
        OptimizeSession(
            aiClientProvider = container.aiClientProvider,
            target = target,
            suggestionCount = count,
            language = language,
            verifier = OptimizeRunner.verifier(
                container.engine, target.kind, language == LocaleStore.LANG_EN
            )
        )
    }
    LaunchedEffect(session) { session.run() }
    val state by session.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.optimize_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.optimize_back)
                        )
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))
            when (val s = state) {
                is OptimizeState.Idle, is OptimizeState.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.optimize_analyzing),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.optimize_verifying),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is OptimizeState.Failed -> {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { reload += 1 }) {
                        Text(stringResource(R.string.optimize_retry))
                    }
                }

                is OptimizeState.Done -> {
                    if (s.reply.diagnosis.isNotBlank()) {
                        SectionTitle(stringResource(R.string.optimize_diagnosis_header))
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = s.reply.diagnosis,
                                modifier = Modifier.padding(14.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    s.verified.forEachIndexed { index, item ->
                        VerifiedCard(
                            index = index + 1,
                            item = item,
                            english = english,
                            labels = FigureLabels(
                                title = stringResource(R.string.optimize_figure_title),
                                // 图内只留标题；"B 级拟物化示意图"等说明文字不上图（用户要求）
                                note = "",
                                newLabel = stringResource(R.string.optimize_highlight_new),
                                modifiedLabel = stringResource(R.string.optimize_highlight_mod),
                                originalLabel = stringResource(R.string.optimize_legend_original)
                            ),
                            onExport = { bmp ->
                                scope.launch {
                                    val msg = try {
                                        String.format(exportDoneTpl, exportPng(context, bmp))
                                    } catch (e: Exception) {
                                        String.format(exportFailedTpl, e.message ?: "unknown")
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    OutlinedButton(onClick = { reload += 1 }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.optimize_retry))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.optimize_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun VerifiedCard(
    index: Int,
    item: VerifiedOptimization,
    english: Boolean,
    labels: FigureLabels,
    onExport: (Bitmap) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.optimize_solution_fmt, index) +
                    stringResource(R.string.optimize_label_sep) + item.suggestion.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (item.suggestion.desc.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(item.suggestion.desc, style = MaterialTheme.typography.bodyMedium)
            }
            if (item.suggestion.expected.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.optimize_expected) +
                        stringResource(R.string.optimize_label_sep) + item.suggestion.expected,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 改动内容由程序化 diff 生成，编号与图中数字气泡一一对应（AI 自带编号不作准）
            if (item.diff.changes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.optimize_changes),
                    style = MaterialTheme.typography.labelLarge
                )
                item.diff.changes.forEach { ch ->
                    Text(
                        text = "· ${ch.label}" + if (ch.detail.isNotBlank()) stringResource(R.string.optimize_label_sep) + ch.detail else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = when (ch.kind) {
                            ChangeKind.ADDED -> MaterialTheme.colorScheme.primary
                            ChangeKind.MODIFIED -> MaterialTheme.colorScheme.tertiary
                            ChangeKind.REMOVED -> MaterialTheme.colorScheme.error
                        }
                    )
                }
            }

            if (!item.verified) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.optimize_unverified_fmt, item.failureReason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                return@Column
            }

            if (item.diff.loadOrSupportChanged) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.optimize_cheat_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (item.diff.isEmpty) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.optimize_no_change),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val frame = remember(item) { decodeFrame(item) }
                if (frame != null) {
                    // 框架：可手指旋转 / 缩放的三维实体图，导出按当前视角
                    var view by remember(item) { mutableStateOf(View3D()) }
                    Spacer(Modifier.height(10.dp))
                    OptimizeFigureView(
                        model = frame,
                        diff = item.diff,
                        labels = labels,
                        view = view,
                        onViewChange = { view = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.optimize_figure_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            onExport(
                                OptimizeFigureRenderer.renderFrame(
                                    frame, item.diff, labels, W, H, view
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.optimize_export_png))
                    }
                } else {
                    val bmp = remember(item) {
                        runCatching { renderSolidFigure(item, labels) }.getOrNull()
                    }
                    if (bmp != null) {
                        Spacer(Modifier.height(10.dp))
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = labels.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = { onExport(bmp) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.optimize_export_png))
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.optimize_metric_header),
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(4.dp))
            val deltas = OptimizeCompare.deltas(item.before, item.after, english)
            MetricsTable(deltas)

            if (item.after.stressExcludesSingularity) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.optimize_metric_excl_singular),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            val safe = OptimizeCompare.isSafeAfter(item.after)
            Text(
                text = stringResource(
                    if (safe) R.string.optimize_safe_after else R.string.optimize_still_unsafe
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (safe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun MetricsTable(deltas: List<MetricDelta>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("", modifier = Modifier.width(96.dp))
            Text(
                text = stringResource(R.string.optimize_metric_before),
                modifier = Modifier.width(78.dp),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.optimize_metric_after),
                modifier = Modifier.width(78.dp),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
            Text("", modifier = Modifier.width(56.dp))
        }
        deltas.forEach { d ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Text(
                    text = d.label,
                    modifier = Modifier.width(96.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(d.beforeText, modifier = Modifier.width(78.dp), style = MaterialTheme.typography.bodySmall)
                Text(d.afterText, modifier = Modifier.width(78.dp), style = MaterialTheme.typography.bodySmall)
                Text(
                    text = d.changePct?.let { String.format(Locale.US, "%+.0f%%", it) } ?: "—",
                    modifier = Modifier.width(56.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d.better) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 优化模型是框架（含 members 字段）时解码为 FrameModel，否则交给实体分支 */
private fun decodeFrame(item: VerifiedOptimization): FrameModel? {
    val json = item.suggestion.modelJson ?: return null
    if (!json.contains("\"members\"")) return null
    return runCatching { AppJson.decodeFromString(FrameModel.serializer(), json) }.getOrNull()
}

private fun renderSolidFigure(item: VerifiedOptimization, labels: FigureLabels): Bitmap? {
    val json = item.suggestion.modelJson ?: return null
    val model = AppJson.decodeFromString(SolidGenericModel.serializer(), json)
    return OptimizeFigureRenderer.renderSolid(model, item.diff, labels, W, H)
}

private suspend fun exportPng(
    context: android.content.Context,
    bmp: Bitmap
): String {
    return withContext(Dispatchers.IO) {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.cacheDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "optimize_$stamp.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.absolutePath
    }
}

private const val W = 1080
private const val H = 720
