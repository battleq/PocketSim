/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.FrameModel
import com.pocketsim.app.FrameResult
import com.pocketsim.app.R
import com.pocketsim.app.ui.components.ColorRamp
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// =====================================================================
// 投影数学（纯函数，JVM 单测覆盖）
// =====================================================================

/** 世界坐标点：x/y 为水平面，z 竖直向上（与 Python frame3d 一致，重力沿 -z） */
data class WorldPoint(val x: Double, val y: Double, val z: Double)

/** 相机坐标：sx 屏幕水平（向右为正）、sy 屏幕竖直（向上为正）、depth 离相机距离（越大越远） */
data class ViewPoint(val sx: Double, val sy: Double, val depth: Double)

/**
 * 三维视图参数（角度制）：
 *  - yaw：绕 z 轴的方位角（水平旋转）；
 *  - pitch：仰角（0 = 平视，正值 = 从斜上方俯视）；
 *  - zoom：画布缩放倍率（双指缩放）。
 */
data class View3D(
    val yawDeg: Double = 35.0,
    val pitchDeg: Double = 22.0,
    val zoom: Float = 1f
) {
    /**
     * 世界 → 相机：先绕 z 轴转 yaw，再绕旋转后的 x 轴抬 pitch。
     * 结果满足：pitch=0 时 z 向上映射到屏幕上方，depth 取水平进深。
     */
    fun project(x: Double, y: Double, z: Double): ViewPoint {
        val yaw = Math.toRadians(yawDeg)
        val pitch = Math.toRadians(pitchDeg)
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        val x1 = x * cy - y * sy
        val y1 = x * sy + y * cy
        val y2 = y1 * cp - z * sp      // 进深
        val z2 = y1 * sp + z * cp      // 屏幕竖直
        return ViewPoint(x1, z2, y2)
    }

    fun project(p: WorldPoint): ViewPoint = project(p.x, p.y, p.z)
}

/** 旋转后屏幕坐标的包围盒 */
data class ViewBounds(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double
)

/** 计算一组世界点在当前视角下的屏幕包围盒 */
fun viewBounds(points: List<WorldPoint>, view: View3D): ViewBounds {
    var minX = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    points.forEach { p ->
        val v = view.project(p)
        if (v.sx < minX) minX = v.sx
        if (v.sx > maxX) maxX = v.sx
        if (v.sy < minY) minY = v.sy
        if (v.sy > maxY) maxY = v.sy
    }
    if (minX > maxX) return ViewBounds(0.0, 1.0, 0.0, 1.0)
    return ViewBounds(minX, maxX, minY, maxY)
}

/** 画布像素坐标 + 进深（用于画家算法排序） */
data class PixelPoint(val x: Float, val y: Float, val depth: Double)

/**
 * 场景投影器：把世界坐标等比适配到画布（居中 + padding），并叠加缩放倍率。
 * 纯计算、无 Android 依赖，可在 JVM 单测中直接验证。
 */
class SceneProjector(
    private val view: View3D,
    private val bounds: ViewBounds,
    canvasWidth: Float,
    canvasHeight: Float,
    padPx: Float
) {
    private val spanX = (bounds.maxX - bounds.minX).coerceAtLeast(1e-6)
    private val spanY = (bounds.maxY - bounds.minY).coerceAtLeast(1e-6)
    private val scale: Double = (
        min((canvasWidth - 2 * padPx) / spanX, (canvasHeight - 2 * padPx) / spanY)
            .coerceAtLeast(1e-6)
        ) * view.zoom
    private val offX: Double = (canvasWidth - spanX * scale) / 2.0 - bounds.minX * scale
    private val offY: Double = (canvasHeight - spanY * scale) / 2.0

    fun project(p: WorldPoint): PixelPoint {
        val v = view.project(p)
        return PixelPoint(
            x = (offX + v.sx * scale).toFloat(),
            y = (offY + (bounds.maxY - v.sy) * scale).toFloat(),
            depth = v.depth
        )
    }
}

/** 画家算法：按进深从远到近排序（先画远的） */
fun <T> painterOrder(items: List<T>, depthOf: (T) -> Double): List<T> =
    items.sortedByDescending(depthOf)

/** 杆件世界长度标注文案（mm，整数不带小数，否则 1 位小数 + " mm"） */
internal fun memberLengthLabel(a: WorldPoint, b: WorldPoint): String {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val dz = b.z - a.z
    val len = sqrt(dx * dx + dy * dy + dz * dz)
    val text = if (len == len.toLong().toDouble()) {
        len.toLong().toString()
    } else {
        "%.1f".format(len)
    }
    return "$text mm"
}

/** 网格间距取整（1/2/5×10^n），让坐标系刻度落在整数上 */
internal fun niceStep(rough: Double): Double {
    if (rough <= 0.0 || !rough.isFinite()) return 1.0
    val exp = floor(log10(rough))
    val base = 10.0.pow(exp)
    val f = rough / base
    val nice = when {
        f <= 1.0 -> 1.0
        f <= 2.0 -> 2.0
        f <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * base
}

// =====================================================================
// 3D 视图（Compose Canvas）
// =====================================================================

/** 绘图主色（与 2D 线框图保持一致） */
private val MEMBER_COLOR_3D = Color(0xFF37474F)
private val NODE_COLOR_3D = Color(0xFF0D47A1)
private val SUPPORT_COLOR_3D = Color(0xFF455A64)
private val LOAD_COLOR_3D = Color(0xFFC62828)
private val GRID_COLOR_3D = Color(0xFFB0BEC5)
private val DEFORM_ORIGINAL_COLOR_3D = Color.Gray

/**
 * 三维框架视图（P3D-3）：
 *  - 透视/正交投影（自研正交投影 + 深度着色，≤80 杆件用画家算法，不引 OpenGL）；
 *  - 单指拖拽旋转（yaw/pitch）、双指捏合缩放，右上角可重置视角；
 *  - 预览模式：杆件 + 节点 + 支座 + 载荷箭头 + z=0 地面网格；
 *  - 结果模式：传入 [result] 后按杆件应力着色，并按 [deformScale] 显示变形形状
 *    （3D polylines 每点 [x,y,z,dx,dy,dz]）。
 */
@Composable
fun Frame3DPlot(
    model: FrameModel,
    modifier: Modifier = Modifier,
    result: FrameResult? = null,
    deformScale: Float = 1f,
    showNodeLabels: Boolean = true
) {
    var view by remember { mutableStateOf(View3D()) }
    var showDims by remember { mutableStateOf(true) }

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
            val nodes = model.nodes
            if (nodes.isEmpty()) return@Canvas

            val has3dResult = result != null && is3dPolyline(result)
            val nodeMap = nodes.associateBy { it.id }
            fun nodeWorld(id: Int): WorldPoint? = nodeMap[id]?.let { WorldPoint(it.x, it.y, it.z) }

            // ---- 世界点包围盒（原始 ∪ 变形） ----
            val worldPoints = ArrayList<WorldPoint>()
            nodes.forEach { worldPoints.add(WorldPoint(it.x, it.y, it.z)) }
            if (has3dResult) {
                result!!.polylines.forEach { pl ->
                    pl.pts.forEach { pt ->
                        if (pt.size >= 6) {
                            worldPoints.add(
                                WorldPoint(
                                    pt[0] + pt[3] * deformScale,
                                    pt[1] + pt[4] * deformScale,
                                    pt[2] + pt[5] * deformScale
                                )
                            )
                        }
                    }
                }
            }
            val pad = 26.dp.toPx()
            val projector = SceneProjector(
                view = view,
                bounds = viewBounds(worldPoints, view),
                canvasWidth = size.width,
                canvasHeight = size.height,
                padPx = pad
            )

            // ---- 地面网格（z=0） ----
            drawGroundGrid(nodes, projector)

            // ---- 原始形状（虚线，仅变形放大时显示） ----
            if (has3dResult && deformScale > 1.05f) {
                val color = DEFORM_ORIGINAL_COLOR_3D.copy(alpha = 0.5f)
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                painterOrder(result!!.polylines) { pl -> polylineDepth(pl.pts, projector, deform = false) }
                    .forEach { pl ->
                        drawPolyline(
                            pl.pts, projector, deform = false, color = color,
                            strokeWidth = 1.2.dp.toPx(), pathEffect = dash
                        )
                    }
            }

            // ---- 杆件 / 变形折线（按进深排序，应力着色） ----
            val maxSigma = (result?.max_stress_MPa ?: 0.0).coerceAtLeast(1e-9)
            val depthRange = depthRange3d(model, result, projector, deformScale, has3dResult)

            if (has3dResult) {
                // 每根杆拆成小线段，按线段中点进深排序后逐段绘制（画家算法）
                data class Seg(
                    val a: PixelPoint,
                    val b: PixelPoint,
                    val color: Color,
                    val member: Int
                )
                val segs = ArrayList<Seg>()
                result!!.polylines.forEach { pl ->
                    val sigma = result.members.firstOrNull { it.id == pl.member }?.sigma_MPa ?: 0.0
                    val base = ColorRamp.colorAt((sigma / maxSigma).toFloat())
                    for (k in 0 until pl.pts.size - 1) {
                        val p0 = deformedPoint(pl.pts[k], deformScale)
                        val p1 = deformedPoint(pl.pts[k + 1], deformScale)
                        if (p0 == null || p1 == null) continue
                        segs.add(Seg(projector.project(p0), projector.project(p1), base, pl.member))
                    }
                }
                painterOrder(segs) { (it.a.depth + it.b.depth) / 2.0 }.forEach { seg ->
                    val near = depthFade((seg.a.depth + seg.b.depth) / 2.0, depthRange)
                    drawLine(
                        color = seg.color.copy(alpha = 0.55f + 0.45f * near),
                        start = Offset(seg.a.x, seg.a.y),
                        end = Offset(seg.b.x, seg.b.y),
                        strokeWidth = (3.0f + 2.0f * near) * density,
                        cap = StrokeCap.Round
                    )
                }
            } else {
                // 预览：按杆件中点进深排序，整杆绘制
                val members = model.members.filter { nodeMap.containsKey(it.i) && nodeMap.containsKey(it.j) }
                painterOrder(members) { m ->
                    val a = nodeMap[m.i]!!
                    val b = nodeMap[m.j]!!
                    (projector.project(WorldPoint(a.x, a.y, a.z)).depth +
                        projector.project(WorldPoint(b.x, b.y, b.z)).depth) / 2.0
                }.forEach { m ->
                    val a = projector.project(nodeWorld(m.i)!!)
                    val b = projector.project(nodeWorld(m.j)!!)
                    val near = depthFade((a.depth + b.depth) / 2.0, depthRange)
                    drawLine(
                        color = MEMBER_COLOR_3D.copy(alpha = 0.55f + 0.45f * near),
                        start = Offset(a.x, a.y),
                        end = Offset(b.x, b.y),
                        strokeWidth = (3.0f + 2.0f * near) * density,
                        cap = StrokeCap.Round
                    )
                }
            }

            // ---- 支座符号（屏幕空间，画在杆件之后） ----
            model.supports.forEach { s ->
                val w = nodeWorld(s.node) ?: return@forEach
                val p = projector.project(w)
                drawSupport3d(p, s.type)
            }

            // ---- 载荷箭头 ----
            val span = worldSpan(nodes)
            model.loads.forEach { ld ->
                when (ld.type.trim().lowercase()) {
                    "force" -> {
                        val nodeId = ld.node ?: return@forEach
                        val w = nodeWorld(nodeId) ?: return@forEach
                        drawForce3d(
                            head = projector.project(w),
                            dir = WorldPoint(ld.fx, ld.fy, ld.fz),
                            lenWorld = span * 0.16,
                            project = { projector.project(it) }
                        )
                    }
                    "area" -> {
                        // 面荷载：按杆件长度分配给各承托杆件（与 Python 侧分配口径一致）
                        val mems = ld.members.orEmpty().mapNotNull { id ->
                            model.members.firstOrNull { it.id == id }
                        }
                        val totalLen = mems.sumOf { mem ->
                            val a = nodeWorld(mem.i)
                            val b = nodeWorld(mem.j)
                            if (a == null || b == null) 0.0 else sqrt(
                                (b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y) +
                                    (b.z - a.z) * (b.z - a.z)
                            )
                        }
                        if (totalLen > 0.0) {
                            val qz = ld.total_N / totalLen
                            mems.mapNotNull { mem ->
                                val a = nodeWorld(mem.i) ?: return@mapNotNull null
                                val b = nodeWorld(mem.j) ?: return@mapNotNull null
                                a to b
                            }.forEach { (a, b) ->
                                drawUdl3d(
                                    a = a, b = b,
                                    q = WorldPoint(0.0, 0.0, qz),
                                    lenWorld = span * 0.12,
                                    project = { projector.project(it) }
                                )
                            }
                        }
                    }
                    "udl" -> {
                        val mem = ld.member?.let { id -> model.members.firstOrNull { it.id == id } }
                            ?: return@forEach
                        val a = nodeWorld(mem.i) ?: return@forEach
                        val b = nodeWorld(mem.j) ?: return@forEach
                        drawUdl3d(
                            a = a, b = b,
                            q = WorldPoint(ld.qx, ld.qy, ld.qz),
                            lenWorld = span * 0.12,
                            project = { projector.project(it) }
                        )
                    }
                }
            }

            // ---- 节点与编号 ----
            nodes.forEach { n ->
                val p = projector.project(WorldPoint(n.x, n.y, n.z))
                drawCircle(Color.White, radius = 4.2.dp.toPx(), center = Offset(p.x, p.y))
                drawCircle(NODE_COLOR_3D, radius = 3.0.dp.toPx(), center = Offset(p.x, p.y))
            }
            if (showNodeLabels) {
                drawLabels3d(model, projector)
            }
            // 仅预览模式标杆件原始长度：结果页是变形后形状，标原始长度会误导
            if (showDims && result == null) {
                drawDimLabels3d(model, projector)
            }
        }

        // 视角重置（拖动/缩放后一键回到默认视角）
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { showDims = !showDims }) {
                Text(
                    text = stringResource(R.string.frame_3d_dims),
                    fontSize = 11.sp,
                    color = if (showDims) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { view = View3D() }) {
                Text(
                    text = stringResource(R.string.frame_3d_reset),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// =====================================================================
// 绘制辅助
// =====================================================================

/** 结果折线是否为 3D（每点 [x,y,z,dx,dy,dz]） */
fun is3dPolyline(result: FrameResult): Boolean =
    result.polylines.any { pl -> pl.pts.any { it.size >= 6 } }

private fun deformedPoint(pt: List<Double>, scale: Float): WorldPoint? {
    if (pt.size < 6) return null
    return WorldPoint(
        x = pt[0] + pt[3] * scale,
        y = pt[1] + pt[4] * scale,
        z = pt[2] + pt[5] * scale
    )
}

private fun polylineDepth(
    pts: List<List<Double>>,
    projector: SceneProjector,
    deform: Boolean,
    scale: Float = 1f
): Double {
    var sum = 0.0
    var n = 0
    pts.forEach { pt ->
        val w = if (deform) deformedPoint(pt, scale) else if (pt.size >= 3) {
            WorldPoint(pt[0], pt[1], pt[2])
        } else null
        if (w != null) {
            sum += projector.project(w).depth
            n++
        }
    }
    return if (n == 0) 0.0 else sum / n
}

/** 进深范围（用于近实远淡的深度提示） */
private fun depthRange3d(
    model: FrameModel,
    result: FrameResult?,
    projector: SceneProjector,
    deformScale: Float,
    has3dResult: Boolean
): ClosedFloatingPointRange<Double> {
    val depths = ArrayList<Double>()
    model.nodes.forEach { depths.add(projector.project(WorldPoint(it.x, it.y, it.z)).depth) }
    if (has3dResult && result != null) {
        result.polylines.forEach { pl ->
            pl.pts.forEach { pt ->
                deformedPoint(pt, deformScale)?.let { depths.add(projector.project(it).depth) }
            }
        }
    }
    if (depths.isEmpty()) return 0.0..1.0
    val lo = depths.minOrNull() ?: 0.0
    val hi = depths.maxOrNull() ?: 1.0
    return if (hi - lo < 1e-9) lo..(lo + 1.0) else lo..hi
}

/** 进深 → 近端 1、远端 0 */
private fun depthFade(depth: Double, range: ClosedFloatingPointRange<Double>): Float {
    val span = (range.endInclusive - range.start).coerceAtLeast(1e-9)
    return (1.0 - (depth - range.start) / span).coerceIn(0.0, 1.0).toFloat()
}

private fun worldSpan(nodes: List<com.pocketsim.app.FrameNode>): Double {
    if (nodes.isEmpty()) return 1.0
    val minX = nodes.minOf { it.x }
    val maxX = nodes.maxOf { it.x }
    val minY = nodes.minOf { it.y }
    val maxY = nodes.maxOf { it.y }
    val minZ = nodes.minOf { it.z }
    val maxZ = nodes.maxOf { it.z }
    return max(maxX - minX, max(maxY - minY, maxZ - minZ)).coerceAtLeast(1.0)
}

/** z=0 地面网格：范围取模型水平包围盒外扩，间距取 1/2/5×10^n */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGroundGrid(
    nodes: List<com.pocketsim.app.FrameNode>,
    projector: SceneProjector
) {
    if (nodes.isEmpty()) return
    val minX = nodes.minOf { it.x }
    val maxX = nodes.maxOf { it.x }
    val minY = nodes.minOf { it.y }
    val maxY = nodes.maxOf { it.y }
    val spanX = (maxX - minX).coerceAtLeast(1.0)
    val spanY = (maxY - minY).coerceAtLeast(1.0)
    val step = niceStep(max(spanX, spanY) / 4.0)
    val padX = spanX * 0.15 + step
    val padY = spanY * 0.15 + step
    val x0 = floor((minX - padX) / step) * step
    val x1 = ceil((maxX + padX) / step) * step
    val y0 = floor((minY - padY) / step) * step
    val y1 = ceil((maxY + padY) / step) * step

    val gridColor = GRID_COLOR_3D.copy(alpha = 0.45f)
    val stroke = 1f * density
    var count = 0
    var x = x0
    while (x <= x1 + 1e-6 && count < 80) {
        val a = projector.project(WorldPoint(x, y0, 0.0))
        val b = projector.project(WorldPoint(x, y1, 0.0))
        drawLine(gridColor, Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = stroke)
        x += step
        count++
    }
    var y = y0
    while (y <= y1 + 1e-6 && count < 160) {
        val a = projector.project(WorldPoint(x0, y, 0.0))
        val b = projector.project(WorldPoint(x1, y, 0.0))
        drawLine(gridColor, Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = stroke)
        y += step
        count++
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPolyline(
    pts: List<List<Double>>,
    projector: SceneProjector,
    deform: Boolean,
    color: Color,
    strokeWidth: Float,
    pathEffect: PathEffect? = null
) {
    for (k in 0 until pts.size - 1) {
        val w0 = if (deform) deformedPoint(pts[k], 1f)
        else if (pts[k].size >= 3) WorldPoint(pts[k][0], pts[k][1], pts[k][2]) else null
        val w1 = if (deform) deformedPoint(pts[k + 1], 1f)
        else if (pts[k + 1].size >= 3) WorldPoint(pts[k + 1][0], pts[k + 1][1], pts[k + 1][2]) else null
        if (w0 == null || w1 == null) continue
        val a = projector.project(w0)
        val b = projector.project(w1)
        drawLine(
            color = color,
            start = Offset(a.x, a.y),
            end = Offset(b.x, b.y),
            strokeWidth = strokeWidth,
            pathEffect = pathEffect
        )
    }
}

/** 支座符号：fixed（方块+斜线）/ roller（三角+双轮）/ 其他按铰接三角 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSupport3d(
    c: PixelPoint,
    type: String
) {
    val color = SUPPORT_COLOR_3D
    when (type.trim().lowercase()) {
        "fixed" -> {
            val half = 8.dp.toPx()
            drawLine(
                color,
                Offset(c.x - half, c.y + 7.dp.toPx()),
                Offset(c.x + half, c.y + 7.dp.toPx()),
                strokeWidth = 2.5.dp.toPx()
            )
            var dx = -half
            while (dx <= half - 1f) {
                drawLine(
                    color,
                    Offset(c.x + dx, c.y + 7.dp.toPx()),
                    Offset(c.x + dx + 5.dp.toPx(), c.y + 11.dp.toPx()),
                    strokeWidth = 1.2.dp.toPx()
                )
                dx += 5.dp.toPx()
            }
        }
        "roller" -> {
            val baseY = c.y + 12.dp.toPx()
            val half = 8.dp.toPx()
            val tri = androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x, c.y)
                lineTo(c.x - half, baseY)
                lineTo(c.x + half, baseY)
                close()
            }
            drawPath(tri, color, style = Stroke(1.8.dp.toPx()))
            drawCircle(color, radius = 2.2.dp.toPx(), center = Offset(c.x - 4.dp.toPx(), baseY + 3.5.dp.toPx()))
            drawCircle(color, radius = 2.2.dp.toPx(), center = Offset(c.x + 4.dp.toPx(), baseY + 3.5.dp.toPx()))
        }
        else -> {
            val baseY = c.y + 12.dp.toPx()
            val half = 8.dp.toPx()
            val tri = androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x, c.y)
                lineTo(c.x - half, baseY)
                lineTo(c.x + half, baseY)
                close()
            }
            drawPath(tri, color)
        }
    }
}

/** 节点集中力箭头：方向取 (fx,fy,fz) 的三维投影，箭头长度按模型跨度取比例 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawForce3d(
    head: PixelPoint,
    dir: WorldPoint,
    lenWorld: Double,
    project: (WorldPoint) -> PixelPoint
) {
    val mag = sqrt(dir.x * dir.x + dir.y * dir.y + dir.z * dir.z)
    if (mag < 1e-9) return
    // 正交投影下「方向向量」的屏幕差与位置无关：用原点与该方向向量的投影差取屏幕方向
    val unit = WorldPoint(dir.x / mag * lenWorld, dir.y / mag * lenWorld, dir.z / mag * lenWorld)
    val p0 = project(WorldPoint(0.0, 0.0, 0.0))
    val p1 = project(unit)
    val dx = (p1.x - p0.x).toFloat()
    val dy = (p1.y - p0.y).toFloat()
    val norm = hypot(dx, dy).coerceAtLeast(1e-6f)
    val ux = dx / norm
    val uy = dy / norm
    val len = 34.dp.toPx()
    val tail = Offset(head.x - ux * len, head.y - uy * len)
    val tip = Offset(head.x, head.y)
    drawLine(LOAD_COLOR_3D, tail, tip, strokeWidth = 2.2.dp.toPx(), cap = StrokeCap.Round)
    val wing = 7.dp.toPx()
    drawLine(
        LOAD_COLOR_3D, tip,
        Offset(tip.x - ux * wing + uy * wing * 0.55f, tip.y - uy * wing - ux * wing * 0.55f),
        strokeWidth = 2.2.dp.toPx()
    )
    drawLine(
        LOAD_COLOR_3D, tip,
        Offset(tip.x - ux * wing - uy * wing * 0.55f, tip.y - uy * wing + ux * wing * 0.55f),
        strokeWidth = 2.2.dp.toPx()
    )
}

/** 杆件均布载荷：沿杆 5 个位置画同向小箭头 + 尾部连线 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUdl3d(
    a: WorldPoint,
    b: WorldPoint,
    q: WorldPoint,
    lenWorld: Double,
    project: (WorldPoint) -> PixelPoint
) {
    val mag = sqrt(q.x * q.x + q.y * q.y + q.z * q.z)
    if (mag < 1e-9) return
    val unit = WorldPoint(q.x / mag * lenWorld, q.y / mag * lenWorld, q.z / mag * lenWorld)
    val p0 = project(WorldPoint(0.0, 0.0, 0.0))
    val p1 = project(unit)
    val dx = (p1.x - p0.x).toFloat()
    val dy = (p1.y - p0.y).toFloat()
    val norm = hypot(dx, dy).coerceAtLeast(1e-6f)
    val ux = dx / norm
    val uy = dy / norm

    var prevTail: Offset? = null
    for (k in 1..5) {
        val t = k / 6.0
        val w = WorldPoint(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t
        )
        val head = project(w)
        val tail = Offset(head.x - ux * 22.dp.toPx(), head.y - uy * 22.dp.toPx())
        val tip = Offset(head.x, head.y)
        drawLine(LOAD_COLOR_3D, tail, tip, strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
        val wing = 4.5.dp.toPx()
        drawLine(
            LOAD_COLOR_3D, tip,
            Offset(tip.x - ux * wing + uy * wing * 0.55f, tip.y - uy * wing - ux * wing * 0.55f),
            strokeWidth = 1.8.dp.toPx()
        )
        drawLine(
            LOAD_COLOR_3D, tip,
            Offset(tip.x - ux * wing - uy * wing * 0.55f, tip.y - uy * wing + ux * wing * 0.55f),
            strokeWidth = 1.8.dp.toPx()
        )
        prevTail?.let { drawLine(LOAD_COLOR_3D, it, tail, strokeWidth = 1.2.dp.toPx()) }
        prevTail = tail
    }
}

/** 节点/杆件编号 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabels3d(
    model: FrameModel,
    projector: SceneProjector
) {
    val nc = drawContext.canvas.nativeCanvas
    val nodePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.sp.toPx()
        color = android.graphics.Color.GRAY
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
    }
    val memberPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.sp.toPx()
        color = 0xFF607D8B.toInt()
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val nodeMap = model.nodes.associateBy { it.id }
    model.nodes.forEach { n ->
        val p = projector.project(WorldPoint(n.x, n.y, n.z))
        nc.drawText("${n.id}", p.x, p.y - 8f, nodePaint)
    }
    model.members.forEach { m ->
        val a = nodeMap[m.i] ?: return@forEach
        val b = nodeMap[m.j] ?: return@forEach
        val pa = projector.project(WorldPoint(a.x, a.y, a.z))
        val pb = projector.project(WorldPoint(b.x, b.y, b.z))
        nc.drawText("${m.id}", (pa.x + pb.x) / 2f, (pa.y + pb.y) / 2f - 4f, memberPaint)
    }
}

/** 杆件长度标注：预览模式下在杆件中点下方显示原始长度（mm），屏幕过短的杆件跳过 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDimLabels3d(
    model: FrameModel,
    projector: SceneProjector
) {
    val nc = drawContext.canvas.nativeCanvas
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8.sp.toPx()
        color = 0xFF607D8B.toInt()
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val minScreen = 18f
    val nodeMap = model.nodes.associateBy { it.id }
    model.members.forEach { m ->
        val a = nodeMap[m.i] ?: return@forEach
        val b = nodeMap[m.j] ?: return@forEach
        val pa = projector.project(WorldPoint(a.x, a.y, a.z))
        val pb = projector.project(WorldPoint(b.x, b.y, b.z))
        if (hypot(pa.x - pb.x, pa.y - pb.y) < minScreen) return@forEach
        val label = memberLengthLabel(WorldPoint(a.x, a.y, a.z), WorldPoint(b.x, b.y, b.z))
        nc.drawText(label, (pa.x + pb.x) / 2f, (pa.y + pb.y) / 2f + 10.dp.toPx(), paint)
    }
}
