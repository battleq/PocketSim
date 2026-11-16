/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketsim.app.FrameModel
import com.pocketsim.app.FrameNode
import com.pocketsim.app.ModelCandidate
import com.pocketsim.app.R
import kotlin.math.min

/**
 * 多候选选择页：展示首轮建模生成的若干候选概要，用户点选其一进入对应预览页。
 * 插在 Chat 与各 Preview 之间；Preview 之后流程不变。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CandidateSelectScreen(
    candidates: List<ModelCandidate>,
    onBack: () -> Unit,
    onSelect: (ModelCandidate) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.candidate_select_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.candidate_select_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(candidates) { index, candidate ->
                    CandidateCard(index, candidate) { onSelect(candidate) }
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(index: Int, candidate: ModelCandidate, onPick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = stringResource(R.string.candidate_card_label, index + 1),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            CandidateThumbnail(candidate, Modifier.fillMaxWidth())
            Text(
                text = candidate.summary,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(2.dp))
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.candidate_select_pick))
            }
        }
    }
}

/** 候选缩略图：框架类按节点坐标投影画线框，其余类型画结构示意，便于用户直观对比方案。 */
@Composable
private fun CandidateThumbnail(candidate: ModelCandidate, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.surfaceVariant
    val foreground = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .height(110.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
    ) {
        when (candidate) {
            is ModelCandidate.Frame -> FrameWireframe(candidate.model, foreground)
            is ModelCandidate.Transient -> FrameWireframe(candidate.spec.frame, foreground)
            else -> ShapeSchematic(candidate.kind, foreground)
        }
    }
}

/** 框架线框：取展开幅度最大的两个坐标轴做正交投影，等比例铺满并居中。 */
@Composable
private fun FrameWireframe(model: FrameModel, color: Color) {
    Canvas(Modifier.fillMaxSize().padding(10.dp)) {
        val nodes = model.nodes
        if (nodes.isEmpty()) return@Canvas
        val spans = listOf(
            0 to (nodes.maxOf { it.x } - nodes.minOf { it.x }),
            1 to (nodes.maxOf { it.y } - nodes.minOf { it.y }),
            2 to (nodes.maxOf { it.z } - nodes.minOf { it.z })
        )
        val axes = spans.sortedByDescending { it.second }.take(2).map { it.first }.sorted()
        val ax = axes[0]
        val ay = axes[1]
        fun px(n: FrameNode): Double = when (ax) { 0 -> n.x; 1 -> n.y; else -> n.z }
        fun py(n: FrameNode): Double = when (ay) { 0 -> n.x; 1 -> n.y; else -> n.z }

        val minX = nodes.minOf { px(it) }
        val maxX = nodes.maxOf { px(it) }
        val minY = nodes.minOf { py(it) }
        val maxY = nodes.maxOf { py(it) }
        val spanX = (maxX - minX).takeIf { it > 1e-6 } ?: 1.0
        val spanY = (maxY - minY).takeIf { it > 1e-6 } ?: 1.0
        val scale = min(size.width / spanX.toFloat(), size.height / spanY.toFloat())
        val offsetX = (size.width - spanX.toFloat() * scale) / 2f
        val offsetY = (size.height - spanY.toFloat() * scale) / 2f
        fun toCanvas(n: FrameNode) = Offset(
            offsetX + ((px(n) - minX) * scale).toFloat(),
            // Canvas 的 y 轴向下，翻转以保持结构正立
            offsetY + ((maxY - py(n)) * scale).toFloat()
        )

        val byId = nodes.associateBy { it.id }
        val strokeWidth = 2.dp.toPx()
        model.members.forEach { m ->
            val a = byId[m.i] ?: return@forEach
            val b = byId[m.j] ?: return@forEach
            drawLine(color, toCanvas(a), toCanvas(b), strokeWidth = strokeWidth)
        }
        nodes.forEach { n -> drawCircle(color, radius = 2.5.dp.toPx(), center = toCanvas(n)) }
    }
}

/** 非框架类型的结构示意（墙体 / 坝体 / 容器），避免卡片只有文字。 */
@Composable
private fun ShapeSchematic(kind: String, color: Color) {
    Canvas(Modifier.fillMaxSize().padding(14.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        when (kind) {
            "seepage" -> {
                val path = Path().apply {
                    moveTo(w * 0.08f, h * 0.95f)
                    lineTo(w * 0.26f, h * 0.08f)
                    lineTo(w * 0.62f, h * 0.08f)
                    lineTo(w * 0.82f, h * 0.95f)
                    close()
                }
                drawPath(path, color, style = Stroke(stroke))
                drawLine(
                    color, Offset(w * 0.14f, h * 0.55f), Offset(w * 0.76f, h * 0.55f),
                    strokeWidth = 1.5.dp.toPx()
                )
            }
            "shell" -> {
                drawRoundRect(
                    color,
                    topLeft = Offset(w * 0.28f, h * 0.08f),
                    size = Size(w * 0.44f, h * 0.84f),
                    cornerRadius = CornerRadius(w * 0.22f, h * 0.06f),
                    style = Stroke(stroke)
                )
                drawLine(
                    color, Offset(w * 0.28f, h * 0.52f), Offset(w * 0.72f, h * 0.52f),
                    strokeWidth = 1.5.dp.toPx()
                )
            }
            "solid_generic" -> {
                // 等轴测盒子：正面 + 顶面 + 侧面
                val x0 = w * 0.22f
                val y0 = h * 0.32f
                val bw = w * 0.44f
                val bh = h * 0.52f
                val dx = w * 0.16f
                val dy = h * 0.16f
                drawRect(color, Offset(x0, y0), Size(bw, bh), style = Stroke(stroke))
                drawLine(color, Offset(x0, y0), Offset(x0 + dx, y0 - dy), strokeWidth = stroke)
                drawLine(color, Offset(x0 + bw, y0), Offset(x0 + bw + dx, y0 - dy), strokeWidth = stroke)
                drawLine(color, Offset(x0 + dx, y0 - dy), Offset(x0 + bw + dx, y0 - dy), strokeWidth = stroke)
                drawLine(color, Offset(x0 + bw, y0 + bh), Offset(x0 + bw + dx, y0 + bh - dy), strokeWidth = stroke)
                drawLine(color, Offset(x0 + bw + dx, y0 - dy), Offset(x0 + bw + dx, y0 + bh - dy), strokeWidth = stroke)
            }
            else -> {
                // flow / solid 等：墙体立面
                drawRect(
                    color,
                    topLeft = Offset(w * 0.16f, h * 0.1f),
                    size = Size(w * 0.68f, h * 0.8f),
                    style = Stroke(stroke)
                )
            }
        }
    }
}
