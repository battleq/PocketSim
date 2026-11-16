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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.AppContainer
import com.pocketsim.app.FrameLoad
import com.pocketsim.app.FrameModel
import com.pocketsim.app.FrameResult
import com.pocketsim.app.LocaleManager
import com.pocketsim.app.LocaleStore
import com.pocketsim.app.R
import com.pocketsim.app.Units
import com.pocketsim.app.modelDisplayName
import com.pocketsim.app.materialDisplayName
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 框架模型预览确认页（AI 通用建模模式，P1）：
 *  - Canvas 线框图：杆件粗线 + 节点圆点/编号 + 支座符号（固定/铰接/滚轴）+ 载荷箭头（集中力/均布）
 *  - 模型信息卡：规模、材料、支座、载荷、杆件清单（截面与连接）
 *  - "不满意，修改模型" → 弹出对话框让用户写反馈 → 重建模；"开始计算" → engine.runFrame → 结果页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FramePreviewScreen(
    container: AppContainer,
    model: FrameModel,
    onBack: () -> Unit,
    onResult: (FrameModel, FrameResult) -> Unit,
    onRegenRequest: (String) -> Unit = {}
) {
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // "不满意，修改模型"对话框
    var showRegenDialog by remember { mutableStateOf(false) }
    var regenFeedback by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val unknownError = stringResource(R.string.error_unknown_default)
    val english = LocaleManager.currentLanguageCode(LocalContext.current) == LocaleStore.LANG_EN

    if (showRegenDialog) {
        AlertDialog(
            onDismissRequest = { showRegenDialog = false },
            title = { Text(stringResource(R.string.solid_regen_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.frame_regen_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = regenFeedback,
                        onValueChange = { regenFeedback = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        placeholder = { Text(stringResource(R.string.frame_regen_placeholder)) }
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
                title = { Text(stringResource(R.string.frame_preview_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)) {
                    error?.let { raw ->
                        // 机构/近机构错误：给用户可操作的下一步（换候选或补斜撑/改约束）
                        val isStability = raw.contains("机构") || raw.contains("约束不足") ||
                            raw.contains("几何可变") || raw.contains("刚度")
                        val shown = if (isStability) {
                            raw + stringResource(R.string.frame_stability_hint)
                        } else raw
                        Text(
                            text = stringResource(R.string.frame_run_failed, shown),
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
                        modifier = Modifier.fillMaxWidth()
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
                                    val result = container.engine.runFrame(model)
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
                            Text(
                                text = " " + stringResource(R.string.frame_running),
                                style = MaterialTheme.typography.labelLarge
                            )
                        } else {
                            Text(stringResource(R.string.frame_btn_run))
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
                    text = stringResource(R.string.frame_preview_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---- 线框图 ----
            item {
                Card(shape = RoundedCornerShape(4.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = modelDisplayName(model.name).ifBlank { stringResource(R.string.frame_model_default_name) },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        FrameModelPlot(
                            model = model,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                        )
                        if (model.dim == 3) {
                            Text(
                                text = stringResource(R.string.frame_3d_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ---- 模型信息 ----
            item { FrameSummaryCard(model) }

            // ---- 杆件清单 ----
            item {
                val holesPrefix = stringResource(R.string.frame_holes_prefix)
                val holeRect = stringResource(R.string.frame_hole_rect)
                val holeCircle = stringResource(R.string.frame_hole_circle)
                val holeFmt = stringResource(R.string.frame_hole_fmt)
                val memberFmt = stringResource(R.string.frame_member_fmt)
                val strongAxis = stringResource(R.string.frame_member_strong_axis)
                val nodesFmt = stringResource(R.string.frame_member_nodes_fmt)
                val pipeLabel = stringResource(R.string.frame_section_pipe)
                val solidRoundLabel = stringResource(R.string.frame_section_solid_round)
                val hollowLabel = stringResource(R.string.frame_section_hollow)
                Card(shape = RoundedCornerShape(4.dp)) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.frame_members_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        model.members.forEach { m ->
                            val section = buildString {
                                append(sectionTextOf(m, pipeLabel, solidRoundLabel, hollowLabel))
                                if (m.holes.isNotEmpty()) {
                                    append(holesPrefix).append(m.holes.joinToString("、") { h ->
                                        val shape = if (h.shape.lowercase() == "rect") holeRect else holeCircle
                                        holeFmt.format(shape, Units.length(h.d_mm).toString(), Units.length(h.at_mm).toString())
                                    })
                                }
                            }
                            Text(
                                text = memberFmt.format(m.id, materialDisplayName(m.material, english), section) +
                                    (if (model.dim == 3) strongAxis else "") +
                                    nodesFmt.format(m.i, m.j),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // ---- 免责声明 ----
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

/** 模型信息卡：规模 / 材料 / 支座 / 载荷 */
@Composable
private fun FrameSummaryCard(model: FrameModel) {
    val english = LocaleManager.currentLanguageCode(LocalContext.current) == LocaleStore.LANG_EN
    // stringResource 只能在组合上下文调用：标签先取出，joinToString 的普通 lambda 里只做拼接
    val fixedLabel = stringResource(R.string.frame_support_fixed)
    val pinnedLabel = stringResource(R.string.frame_support_pinned)
    val rollerLabel = stringResource(R.string.frame_support_roller)
    fun supportLabel(type: String): String = when (type.trim().lowercase()) {
        "fixed" -> fixedLabel
        "roller", "roller_x", "roller_y" -> rollerLabel
        else -> pinnedLabel
    }
    val materials = model.members.map { materialDisplayName(it.material, english) }.filter { it.isNotBlank() }.distinct().joinToString(if (english) ", " else "、")
    val supportNodeFmt = stringResource(R.string.frame_support_node_fmt)
    val supportsDesc = model.supports.joinToString("、") { supportNodeFmt.format(it.node, supportLabel(it.type)) }
    val loadsSb = StringBuilder()
    for (ld in model.loads) {
        if (loadsSb.isNotEmpty()) loadsSb.append("；")
        loadsSb.append(describeLoad(ld))
    }
    val loadsDesc = loadsSb.toString()

    Card(shape = RoundedCornerShape(4.dp)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.frame_summary_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(
                    R.string.frame_summary_fmt,
                    model.nodes.size, model.members.size, model.supports.size
                ),
                style = MaterialTheme.typography.bodySmall
            )
            if (materials.isNotBlank()) {
                Text(
                    text = stringResource(R.string.frame_materials_fmt, materials),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (supportsDesc.isNotBlank()) {
                Text(
                    text = stringResource(R.string.frame_supports_fmt, supportsDesc),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (loadsDesc.isNotBlank()) {
                Text(
                    text = stringResource(R.string.frame_loads_fmt, loadsDesc),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** 单条载荷 → 通俗描述（节点力/力偶/杆件均布，2D/3D 通用） */
@Composable
private fun describeLoad(ld: FrameLoad): String = when (ld.type.trim().lowercase()) {
    "force" -> {
        val node = ld.node ?: 0
        val forceMag = sqrt(ld.fx * ld.fx + ld.fy * ld.fy + ld.fz * ld.fz)
        if (forceMag > 0.0) {
            stringResource(R.string.frame_load_force_fmt, node, Units.force(forceMag).toString())
        } else {
            val momentMag = sqrt(ld.mx * ld.mx + ld.my * ld.my + ld.mz * ld.mz)
            stringResource(R.string.frame_load_moment_fmt, node, Units.moment(momentMag).toString())
        }
    }
    "udl" -> stringResource(
        R.string.frame_load_udl_fmt,
        ld.member ?: 0,
        Units.linearLoad(sqrt(ld.qx * ld.qx + ld.qy * ld.qy + ld.qz * ld.qz)).toString()
    )
    "area" -> stringResource(
        R.string.frame_load_area_fmt,
        ld.members.orEmpty().size,
        Units.force(abs(ld.total_N)).toString()
    )
    "thermal" -> stringResource(R.string.frame_load_thermal_fmt, ld.member ?: 0, formatNum(ld.delta_temp))
    else -> stringResource(
        R.string.frame_load_udl_fmt,
        ld.member ?: 0,
        Units.linearLoad(sqrt(ld.qx * ld.qx + ld.qy * ld.qy + ld.qz * ld.qz)).toString()
    )
}

// =====================================================================
// 线框图绘制
// =====================================================================

/** 模型视图入口：dim=3 用可旋转三维视图，dim=2 保持原线框图 */
@Composable
private fun FrameModelPlot(model: FrameModel, modifier: Modifier) {
    if (model.dim == 3) {
        Frame3DPlot(model = model, modifier = modifier)
    } else {
        FrameModelCanvas(model = model, modifier = modifier)
    }
}

/** 绘图主色 */
private val MEMBER_COLOR = Color(0xFF37474F)   // 杆件：深蓝灰
private val NODE_COLOR = Color(0xFF0D47A1)     // 节点：深蓝
private val SUPPORT_COLOR = Color(0xFF455A64)  // 支座符号：青灰
private val LOAD_COLOR = Color(0xFFC62828)     // 载荷箭头：红
private val HOLE_COLOR = Color(0xFFE53935)     // 开孔标记：红

/**
 * 模型线框图（Compose Canvas）：
 *  - y 轴翻转（模型 y 向上、画布 y 向下），等比缩放居中；
 *  - 支座符号：固定（短线+斜线阵）、铰接（三角）、滚轴（三角+双轮）；
 *  - 载荷：集中力画指向节点的箭头，均布载荷沿杆画一排小箭头 + 尾部连线；
 *  - 节点编号与杆件编号用小号文字标注（nativeCanvas）。
 */
@Composable
private fun FrameModelCanvas(model: FrameModel, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val nodes = model.nodes
        if (nodes.size < 2 || model.members.isEmpty()) return@Canvas

        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        nodes.forEach { n ->
            if (n.x < minX) minX = n.x; if (n.x > maxX) maxX = n.x
            if (n.y < minY) minY = n.y; if (n.y > maxY) maxY = n.y
        }
        val spanX = (maxX - minX).coerceAtLeast(1e-6)
        val spanY = (maxY - minY).coerceAtLeast(1e-6)

        val pad = 30.dp.toPx()
        val scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
        val offX = (size.width - spanX * scale) / 2f
        val offY = (size.height - spanY * scale) / 2f
        fun px(x: Double) = (offX + (x - minX) * scale).toFloat()
        fun py(y: Double) = (offY + (maxY - y) * scale).toFloat()

        val nodeMap = nodes.associateBy { it.id }
        fun nodePos(id: Int): Offset? = nodeMap[id]?.let { Offset(px(it.x), py(it.y)) }

        // ---- 杆件 ----
        model.members.forEach { m ->
            val a = nodePos(m.i) ?: return@forEach
            val b = nodePos(m.j) ?: return@forEach
            drawLine(MEMBER_COLOR, a, b, strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        }

        // ---- 开孔标记：按 at_mm 定位，白底红圈直观显示孔位 ----
        model.members.forEach { m ->
            if (m.holes.isEmpty()) return@forEach
            val a = nodePos(m.i) ?: return@forEach
            val b = nodePos(m.j) ?: return@forEach
            val ni = nodeMap[m.i] ?: return@forEach
            val nj = nodeMap[m.j] ?: return@forEach
            val lenMm = hypot(nj.x - ni.x, nj.y - ni.y).coerceAtLeast(1e-9)
            m.holes.forEach { h ->
                val f = (h.at_mm / lenMm).coerceIn(0.0, 1.0).toFloat()
                val c = Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
                drawCircle(Color.White, radius = 4.dp.toPx(), center = c)
                drawCircle(HOLE_COLOR, radius = 4.dp.toPx(), center = c,
                    style = Stroke(1.8.dp.toPx()))
            }
        }

        // ---- 支座符号 ----
        model.supports.forEach { s ->
            val c = nodePos(s.node) ?: return@forEach
            when (s.type.trim().lowercase()) {
                "fixed" -> {
                    // 短粗横线 + 斜线阵（嵌固）
                    val halfW = 16.dp.toPx()
                    val baseY = c.y + 12.dp.toPx()
                    drawLine(SUPPORT_COLOR, Offset(c.x - halfW, baseY), Offset(c.x + halfW, baseY),
                        strokeWidth = 3.dp.toPx())
                    var x = -halfW
                    while (x <= halfW - 1f) {
                        drawLine(SUPPORT_COLOR,
                            Offset(c.x + x, baseY),
                            Offset(c.x + x + 8.dp.toPx(), baseY + 9.dp.toPx()),
                            strokeWidth = 1.5f)
                        x += 8.dp.toPx()
                    }
                }
                "roller" -> {
                    // 三角 + 双轮（滚轴）
                    val apex = c
                    val baseY = c.y + 18.dp.toPx()
                    val halfW = 12.dp.toPx()
                    val tri = androidx.compose.ui.graphics.Path().apply {
                        moveTo(apex.x, apex.y)
                        lineTo(c.x - halfW, baseY)
                        lineTo(c.x + halfW, baseY)
                        close()
                    }
                    drawPath(tri, SUPPORT_COLOR, style = Stroke(2.dp.toPx()))
                    drawCircle(SUPPORT_COLOR, radius = 3.dp.toPx(), center = Offset(c.x - 5.dp.toPx(), baseY + 5.dp.toPx()))
                    drawCircle(SUPPORT_COLOR, radius = 3.dp.toPx(), center = Offset(c.x + 5.dp.toPx(), baseY + 5.dp.toPx()))
                }
                else -> {
                    // 铰接：实心三角
                    val baseY = c.y + 18.dp.toPx()
                    val halfW = 12.dp.toPx()
                    val tri = androidx.compose.ui.graphics.Path().apply {
                        moveTo(c.x, c.y)
                        lineTo(c.x - halfW, baseY)
                        lineTo(c.x + halfW, baseY)
                        close()
                    }
                    drawPath(tri, SUPPORT_COLOR)
                }
            }
        }

        // ---- 载荷 ----
        fun drawUdlOnMember(mid: Int, qy: Double) {
            val mem = model.members.firstOrNull { it.id == mid } ?: return
            val pa = nodePos(mem.i) ?: return
            val pb = nodePos(mem.j) ?: return
            // 画布竖直方向：qy<0（模型向下）→ 画布 +y，箭头从上方指向杆件
            val dirY = if (qy < 0) 1f else -1f
            val h = 24.dp.toPx()
            val nArrows = 5
            var prevTail: Offset? = null
            for (k in 1..nArrows) {
                val t = k.toFloat() / (nArrows + 1)
                val hx = pa.x + (pb.x - pa.x) * t
                val hy = pa.y + (pb.y - pa.y) * t
                val head = Offset(hx, hy)
                val tail = Offset(hx, hy - dirY * h)
                drawLine(LOAD_COLOR, tail, head, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                val wing = 5.dp.toPx()
                drawLine(LOAD_COLOR, head,
                    Offset(head.x - wing * 0.6f, head.y - dirY * wing),
                    strokeWidth = 2.dp.toPx())
                drawLine(LOAD_COLOR, head,
                    Offset(head.x + wing * 0.6f, head.y - dirY * wing),
                    strokeWidth = 2.dp.toPx())
                prevTail?.let { drawLine(LOAD_COLOR, it, tail, strokeWidth = 1.5.dp.toPx()) }
                prevTail = tail
            }
        }
        model.loads.forEach { ld ->
            when (ld.type.trim().lowercase()) {
                "force" -> {
                    val head = ld.node?.let { nodePos(it) } ?: return@forEach
                    if (abs(ld.fx) + abs(ld.fy) > 0.0) {
                        // 力方向（画布 y 翻转）：模型 fy<0 向下 → 画布 +y
                        var dx = ld.fx.toFloat()
                        var dy = (-ld.fy).toFloat()
                        val norm = hypot(dx, dy).coerceAtLeast(1e-6f)
                        dx /= norm; dy /= norm
                        val len = 34.dp.toPx()
                        val tail = Offset(head.x - dx * len, head.y - dy * len)
                        drawLine(LOAD_COLOR, tail, head, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        // 箭头两翼
                        val wing = 7.dp.toPx()
                        val left = Offset(head.x - dx * wing + dy * wing * 0.6f, head.y - dy * wing - dx * wing * 0.6f)
                        val right = Offset(head.x - dx * wing - dy * wing * 0.6f, head.y - dy * wing + dx * wing * 0.6f)
                        drawLine(LOAD_COLOR, head, left, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(LOAD_COLOR, head, right, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
                "udl" -> {
                    ld.member?.let { drawUdlOnMember(it, ld.qy) }
                }
                "area" -> {
                    // 面荷载：总力按参与杆件长度分配为均布（与 Python 侧口径一致）
                    val mems = ld.members.orEmpty().mapNotNull { id ->
                        model.members.firstOrNull { it.id == id }
                    }
                    val totalLen = mems.sumOf { mem ->
                        val a = model.nodes.firstOrNull { it.id == mem.i }
                        val b = model.nodes.firstOrNull { it.id == mem.j }
                        if (a == null || b == null) 0.0 else hypot(b.x - a.x, b.y - a.y)
                    }
                    if (totalLen > 0.0) {
                        mems.forEach { drawUdlOnMember(it.id, ld.total_N / totalLen) }
                    }
                }
            }
        }

        // ---- 节点圆点 ----
        nodes.forEach { n ->
            drawCircle(NODE_COLOR, radius = 4.dp.toPx(), center = Offset(px(n.x), py(n.y)))
        }

        // ---- 编号文字（节点/杆件） ----
        drawContext.canvas.nativeCanvas.let { nc ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 11.sp.toPx()
                color = android.graphics.Color.GRAY
                textAlign = android.graphics.Paint.Align.CENTER
                isFakeBoldText = true
            }
            val memberPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 10.sp.toPx()
                color = 0xFF607D8B.toInt()
                textAlign = android.graphics.Paint.Align.CENTER
            }
            nodes.forEach { n ->
                nc.drawText("${n.id}", px(n.x), py(n.y) - 12f, paint)
            }
            model.members.forEach { m ->
                val a = nodePos(m.i) ?: return@forEach
                val b = nodePos(m.j) ?: return@forEach
                nc.drawText("${m.id}", (a.x + b.x) / 2f, (a.y + b.y) / 2f - 6f, memberPaint)
            }
        }
    }
}
