/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.pocketsim.app.ChangeKind
import com.pocketsim.app.FrameMember
import com.pocketsim.app.FrameModel
import com.pocketsim.app.FrameNode
import com.pocketsim.app.FrameSupport
import com.pocketsim.app.ModelDiff
import com.pocketsim.app.SolidGenericModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 效果图内的文案（由 UI 层按语言传入，避免渲染器依赖 Android 资源） */
data class FigureLabels(
    val title: String,
    val note: String,
    val newLabel: String,
    val modifiedLabel: String,
    val originalLabel: String
)

/**
 * B 级「拟物化工程示意图」渲染器。
 *
 * 只使用**优化后模型几何 + 确定性 diff**作图：杆件按截面生成三维实体体块，
 * 复用 [View3D] 轨道投影 + [SceneProjector] + [painterOrder] 画家算法，
 * 支座画成标准符号，新增/修改构件按颜色与数字编号高亮。
 * 同一套绘制既用于页面交互预览（可旋转缩放）也用于 PNG 导出，保证所见即所得；
 * 刻意不做 AI 生图，图与有限元重算所用模型严格一致。
 */
object OptimizeFigureRenderer {

    private const val BG = 0xFFF6F8F9.toInt()
    private const val STRUCT = 0xFF7C8F9C.toInt()
    private const val STRUCT_LIGHT = 0xFFD7E0E4.toInt()
    private const val NEW = 0xFF1B7F5C.toInt()
    private const val MOD = 0xFFB26A00.toInt()
    private const val MOD_LIGHT = 0xFFF0B04C.toInt()
    private const val TEXT = 0xFF2B3A42.toInt()
    private const val TEXT_FAINT = 0xFF6B7C86.toInt()

    // =================================================================
    // 框架：平面 / 空间统一按三维实体体块渲染
    // =================================================================

    /** 生成一张独立 Bitmap（PNG 导出用） */
    fun renderFrame(
        model: FrameModel,
        diff: ModelDiff,
        labels: FigureLabels,
        widthPx: Int,
        heightPx: Int,
        view: View3D = View3D()
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        drawFrame(Canvas(bmp), model, diff, labels, widthPx.toFloat(), heightPx.toFloat(), view)
        return bmp
    }

    /** 把框架效果图画到任意 Canvas（交互预览与 PNG 导出共用，保证所见即所得） */
    fun drawFrame(
        c: Canvas,
        model: FrameModel,
        diff: ModelDiff,
        labels: FigureLabels,
        widthPx: Float,
        heightPx: Float,
        view: View3D
    ) {
        c.drawColor(BG)
        // 无说明文字时压缩头部留白，避免标题和图形之间出现空档
        val headerH = if (labels.note.isBlank()) heightPx * 0.09f else heightPx * 0.13f
        val footerH = heightPx * 0.10f
        drawHeader(c, labels, widthPx, heightPx, headerH)

        val nodes = model.nodes.associateBy { it.id }
        if (nodes.isEmpty() || model.members.isEmpty()) {
            drawCentered(c, "—", widthPx / 2f, heightPx / 2f, heightPx / 22f, TEXT_FAINT)
            drawLegend(c, labels, widthPx, heightPx, footerH)
            return
        }

        // 平面框架把 2D 的 y 当作世界竖直方向（z），面外厚度方向留作 y
        val dim3 = model.dim == 3
        fun world(n: FrameNode): WorldPoint =
            if (dim3) WorldPoint(n.x, n.y, n.z) else WorldPoint(n.x, 0.0, n.y)

        // 视觉下限：细杆也留一点体积感，避免体块看不见
        val ws = model.nodes.map { world(it) }
        val span = max(
            ws.maxOf { it.x } - ws.minOf { it.x },
            max(ws.maxOf { it.y } - ws.minOf { it.y }, ws.maxOf { it.z } - ws.minOf { it.z })
        ).coerceAtLeast(1.0)
        val minHalf = (span * 0.006).coerceAtLeast(0.5)

        val boxes = model.members.mapNotNull { m ->
            val a = nodes[m.i] ?: return@mapNotNull null
            val b = nodes[m.j] ?: return@mapNotNull null
            buildBox(m, world(a), world(b), dim3, minHalf)
        }
        if (boxes.isEmpty()) {
            drawCentered(c, "—", widthPx / 2f, heightPx / 2f, heightPx / 22f, TEXT_FAINT)
            drawLegend(c, labels, widthPx, heightPx, footerH)
            return
        }

        val plotTop = headerH + heightPx * 0.015f
        val plotH = (heightPx - footerH - heightPx * 0.015f - plotTop).coerceAtLeast(1f)
        val allWorld = ArrayList<WorldPoint>(boxes.size * 8)
        boxes.forEach { allWorld.addAll(it.corners) }
        model.nodes.forEach { allWorld.add(world(it)) }
        val projector = SceneProjector(
            view = view,
            bounds = viewBounds(allWorld, view),
            canvasWidth = widthPx,
            canvasHeight = plotH,
            padPx = widthPx * 0.05f
        )

        c.save()
        c.translate(0f, plotTop)
        c.clipRect(0f, 0f, widthPx, plotH)

        // 画家算法：远的体块先画
        painterOrder(boxes) { box ->
            box.corners.sumOf { projector.project(it).depth } / box.corners.size
        }.forEach { box ->
            val isNew = box.memberId in diff.addedMemberIds
            val isMod = box.memberId in diff.modifiedMemberIds
            val base = when {
                isNew -> NEW
                isMod -> MOD
                else -> STRUCT
            }
            drawBox(c, box, projector, base, isNew || isMod, heightPx)
        }

        // 节点与支座
        val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF51636E.toInt() }
        val nodeR = max(2f, heightPx * 0.004f)
        model.nodes.forEach { n ->
            val p = projector.project(world(n))
            c.drawCircle(p.x, p.y, nodeR, nodePaint)
        }
        model.supports.forEach { s ->
            val n = nodes[s.node] ?: return@forEach
            val p = projector.project(world(n))
            drawSupport(c, p.x, p.y, s, heightPx * 0.020f, STRUCT)
        }

        // 编号气泡最后画，保证不被体块遮挡；原结构不编号
        boxes.filter { it.memberId in diff.addedMemberIds || it.memberId in diff.modifiedMemberIds }
            .sortedByDescending { projector.project(it.mid).depth }
            .forEach { box ->
                val p = projector.project(box.mid)
                val ring = if (box.memberId in diff.addedMemberIds) NEW else MOD
                drawBubble(c, p.x, p.y, "#${box.memberId}", ring, heightPx * 0.021f)
            }

        c.restore()
        drawLegend(c, labels, widthPx, heightPx, footerH)
    }

    // =================================================================
    // 三维实体：杆件按截面生成长方体（圆截面按外径生成方柱）
    // =================================================================

    /** 一根杆件的实体体块（8 个角点 + 中点） */
    private class Box3(
        val memberId: Int,
        val corners: List<WorldPoint>,
        val mid: WorldPoint
    )

    /** 轻量三维向量（仅本文件使用） */
    private data class V3(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
        operator fun times(s: Double) = V3(x * s, y * s, z * s)
        fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        fun dot(o: V3): Double = x * o.x + y * o.y + z * o.z
        fun length(): Double = sqrt(x * x + y * y + z * z)
        fun unit(): V3 {
            val l = length()
            return if (l < 1e-9) V3(0.0, 0.0, 0.0) else V3(x / l, y / l, z / l)
        }
        fun toWorld(): WorldPoint = WorldPoint(x, y, z)
    }

    private fun buildBox(
        m: FrameMember,
        a: WorldPoint,
        b: WorldPoint,
        dim3: Boolean,
        minHalf: Double
    ): Box3 {
        val pa = V3(a.x, a.y, a.z)
        val pb = V3(b.x, b.y, b.z)
        val dir = (pb - pa).unit()
        // 截面局部轴：bDir 为面外宽度方向，hDir 为面内高度方向
        val bDir: V3
        val hDir: V3
        if (dim3) {
            var n = V3(0.0, 0.0, 1.0).cross(dir)
            if (n.length() < 1e-6) n = V3(1.0, 0.0, 0.0)
            bDir = n.unit()
            hDir = dir.cross(bDir).unit()
        } else {
            bDir = V3(0.0, 1.0, 0.0)
            hDir = bDir.cross(dir).unit()
        }
        val widthMm = if (m.isCircle) m.outer_diameter_mm else m.width_b
        val depthMm = if (m.isCircle) m.outer_diameter_mm else m.depth_h
        val hb = max(widthMm / 2.0, minHalf)
        val hh = max(depthMm / 2.0, minHalf)
        val e1 = bDir * hb
        val e2 = hDir * hh
        val corners = listOf(
            (pa - e1 - e2).toWorld(), (pa + e1 - e2).toWorld(),
            (pa + e1 + e2).toWorld(), (pa - e1 + e2).toWorld(),
            (pb - e1 - e2).toWorld(), (pb + e1 - e2).toWorld(),
            (pb + e1 + e2).toWorld(), (pb - e1 + e2).toWorld()
        )
        return Box3(m.id, corners, ((pa + pb) * 0.5).toWorld())
    }

    /** 六个面按进深从远到近绘制 + 朗伯明暗 + 卡通描边，形成体积感 */
    private fun drawBox(
        c: Canvas,
        box: Box3,
        projector: SceneProjector,
        base: Int,
        highlight: Boolean,
        heightPx: Float
    ) {
        val pts = box.corners.map { projector.project(it) }
        val faces = listOf(
            intArrayOf(0, 1, 2, 3), // 端面 A
            intArrayOf(4, 5, 6, 7), // 端面 B
            intArrayOf(0, 1, 5, 4),
            intArrayOf(1, 2, 6, 5),
            intArrayOf(2, 3, 7, 6),
            intArrayOf(3, 0, 4, 7)
        )
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (highlight) max(2.4f, heightPx * 0.0045f) else max(1.2f, heightPx * 0.0022f)
        }
        faces.sortedByDescending { f -> f.fold(0.0) { acc, i -> acc + pts[i].depth } / f.size }
            .forEach { f ->
                val path = Path().apply {
                    moveTo(pts[f[0]].x, pts[f[0]].y)
                    for (k in 1 until f.size) lineTo(pts[f[k]].x, pts[f[k]].y)
                    close()
                }
                fill.color = shade(base, faceLight(f, box.corners))
                c.drawPath(path, fill)
                edge.color = darken(base)
                c.drawPath(path, edge)
            }
    }

    /** 简单朗伯光照：面法向与光向夹角的余弦决定明暗（双面） */
    private fun faceLight(f: IntArray, corners: List<WorldPoint>): Float {
        val a = corners[f[0]]
        val b = corners[f[1]]
        val d = corners[f[2]]
        val u = V3(b.x - a.x, b.y - a.y, b.z - a.z)
        val v = V3(d.x - a.x, d.y - a.y, d.z - a.z)
        val n = u.cross(v).unit()
        val light = V3(-0.35, -0.45, 0.82).unit()
        return (0.58 + 0.42 * abs(n.dot(light))).toFloat()
    }

    // =================================================================
    // 三维实体（SolidGeneric）：按优化后几何画参数化体块
    // =================================================================

    fun renderSolid(
        model: SolidGenericModel,
        diff: ModelDiff,
        labels: FigureLabels,
        widthPx: Int,
        heightPx: Int
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)

        val headerH = if (labels.note.isBlank()) heightPx * 0.09f else heightPx * 0.13f
        val footerH = heightPx * 0.10f
        drawHeader(c, labels, widthPx.toFloat(), heightPx.toFloat(), headerH)

        val g = model.geometry
        val changed = diff.changes.any { it.kind == ChangeKind.MODIFIED || it.kind == ChangeKind.ADDED }
        val accent = if (changed) MOD else STRUCT
        val accentLight = if (changed) MOD_LIGHT else STRUCT_LIGHT

        val top = headerH + heightPx * 0.08f
        val bottom = heightPx - footerH - heightPx * 0.06f
        val left = widthPx * 0.10f
        val right = widthPx * 0.90f
        val w = right - left
        val h = bottom - top

        when (g.type.lowercase()) {
            "cylinder" -> {
                val rx = w * 0.26f
                val ry = ry3d(rx)
                val cx = (left + right) / 2f
                val bodyTop = top + h * 0.16f
                val bodyBottom = bottom - h * 0.06f
                // 侧壁
                val body = Path().apply {
                    moveTo(cx - rx, bodyTop)
                    lineTo(cx - rx, bodyBottom)
                    arcTo(RectF(cx - rx, bodyBottom - ry, cx + rx, bodyBottom + ry), 180f, -180f, false)
                    lineTo(cx + rx, bodyTop)
                }
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentLight }
                c.drawPath(body, p)
                val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent; style = Paint.Style.STROKE; strokeWidth = max(4f, heightPx * 0.008f)
                }
                c.drawPath(body, edge)
                // 顶面椭圆
                c.drawOval(RectF(cx - rx, bodyTop - ry, cx + rx, bodyTop + ry), p)
                c.drawOval(RectF(cx - rx, bodyTop - ry, cx + rx, bodyTop + ry), edge)
                if (changed) {
                    drawTag(c, labels.modifiedLabel, cx, bodyTop - ry * 2.2f, accent, heightPx * 0.030f)
                }
            }
            else -> {
                // box / plate：等轴测体块
                val boxW = w * 0.62f
                val boxH = h * 0.58f
                val depth = min(w, h) * 0.20f
                val x0 = left + (w - boxW - depth) / 2f
                val y0 = top + (h - boxH - depth) / 2f

                val front = RectF(x0, y0 + depth, x0 + boxW, y0 + boxH + depth)
                val topFace = Path().apply {
                    moveTo(front.left, front.top)
                    lineTo(front.left + depth, front.top - depth)
                    lineTo(front.right + depth, front.top - depth)
                    lineTo(front.right, front.top)
                    close()
                }
                val sideFace = Path().apply {
                    moveTo(front.right, front.top)
                    lineTo(front.right + depth, front.top - depth)
                    lineTo(front.right + depth, front.bottom - depth)
                    lineTo(front.right, front.bottom)
                    close()
                }
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentLight }
                val fillDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shade(accentLight, 0.86f) }
                val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent; style = Paint.Style.STROKE; strokeWidth = max(4f, heightPx * 0.008f)
                }
                c.drawRect(front, fill)
                c.drawPath(topFace, fillDark)
                c.drawPath(sideFace, fillDark)
                c.drawRect(front, edge)
                c.drawPath(topFace, edge)
                c.drawPath(sideFace, edge)
                if (changed) {
                    drawTag(
                        c, labels.modifiedLabel, front.centerX(), front.top - depth * 1.6f,
                        accent, heightPx * 0.030f
                    )
                }
            }
        }

        // 变化项逐条列出（尺寸/材料），便于理解"改了什么"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_FAINT
            textSize = heightPx * 0.024f
        }
        var ty = bottom + heightPx * 0.035f
        diff.changes.take(3).forEach { ch ->
            c.drawText("· ${ch.label}：${ch.detail}", left, ty, textPaint)
            ty += heightPx * 0.030f
        }
        return bmp
    }

    // =================================================================
    // 文字与图元
    // =================================================================

    /** 标题 + 说明（图外） */
    private fun drawHeader(
        c: Canvas,
        labels: FigureLabels,
        widthPx: Float,
        heightPx: Float,
        headerH: Float
    ) {
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            textSize = heightPx * 0.040f
            isFakeBoldText = true
        }
        c.drawText(labels.title, widthPx * 0.06f, headerH * 0.52f, title)

        val note = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_FAINT
            textSize = heightPx * 0.021f
        }
        val wrapped = wrap(labels.note, note, widthPx * 0.88f)
        var ny = headerH * 0.52f + heightPx * 0.030f
        wrapped.take(2).forEach {
            c.drawText(it, widthPx * 0.06f, ny, note)
            ny += heightPx * 0.025f
        }
    }

    /** 图例：原结构 / 新增 / 修改 */
    private fun drawLegend(
        c: Canvas,
        labels: FigureLabels,
        widthPx: Float,
        heightPx: Float,
        footerH: Float
    ) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_FAINT
            textSize = heightPx * 0.021f
        }
        val baseline = heightPx - footerH * 0.30f
        val chipH = heightPx * 0.020f
        var x = widthPx * 0.06f
        val items = listOf(
            STRUCT to labels.originalLabel,
            NEW to labels.newLabel,
            MOD to labels.modifiedLabel
        )
        items.forEach { (color, text) ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            c.drawRoundRect(
                RectF(x, baseline - chipH * 0.85f, x + chipH * 1.5f, baseline - chipH * 0.15f),
                chipH * 0.2f, chipH * 0.2f, p
            )
            c.drawText(text, x + chipH * 1.9f, baseline, textPaint)
            x += chipH * 1.9f + textPaint.measureText(text) + chipH * 2.2f
        }
    }

    /** 编号气泡：白底圆 + 彩色圈 + #编号（模型上只出现数字，不出现文字说明） */
    private fun drawBubble(c: Canvas, cx: Float, cy: Float, text: String, ring: Int, size: Float) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            textSize = size
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val r = max(size * 0.9f, tp.measureText(text) / 2f + size * 0.45f)
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawCircle(
            cx, cy, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ring
                style = Paint.Style.STROKE
                strokeWidth = max(2f, size * 0.18f)
            }
        )
        val fm = tp.fontMetrics
        c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, tp)
    }

    private fun drawSupport(c: Canvas, x: Float, y: Float, s: FrameSupport, size: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = max(3f, size * 0.16f)
        }
        when (s.type.lowercase()) {
            "fixed" -> {
                // 嵌固：竖线 + 斜剖面线
                c.drawLine(x, y, x, y + size, p)
                for (i in 0..2) {
                    val dx = size * 0.42f
                    val yy = y + size * (0.25f + 0.25f * i)
                    c.drawLine(x, yy, x + dx, yy + size * 0.28f, p)
                }
            }
            "roller" -> {
                val tri = Path().apply {
                    moveTo(x, y)
                    lineTo(x - size * 0.6f, y + size)
                    lineTo(x + size * 0.6f, y + size)
                    close()
                }
                c.drawPath(tri, p)
                val r = size * 0.16f
                c.drawCircle(x - size * 0.28f, y + size + r, r, p)
                c.drawCircle(x + size * 0.28f, y + size + r, r, p)
            }
            else -> {
                // 铰支：三角
                val tri = Path().apply {
                    moveTo(x, y)
                    lineTo(x - size * 0.6f, y + size)
                    lineTo(x + size * 0.6f, y + size)
                    close()
                }
                c.drawPath(tri, p)
                c.drawLine(x - size * 0.75f, y + size, x + size * 0.75f, y + size, p)
            }
        }
    }

    private fun drawTag(c: Canvas, text: String, cx: Float, cy: Float, color: Int, size: Float) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = size
            isFakeBoldText = true
        }
        val pad = size * 0.32f
        val tw = tp.measureText(text)
        val rect = RectF(cx - tw / 2 - pad, cy - size * 0.95f, cx + tw / 2 + pad, cy + size * 0.15f)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawRoundRect(rect, size * 0.28f, size * 0.28f, bg)
        c.drawText(text, rect.left + pad, rect.top + size * 0.78f, tp)
    }

    private fun drawCentered(c: Canvas, text: String, x: Float, y: Float, size: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            textAlign = Paint.Align.CENTER
        }
        c.drawText(text, x, y, p)
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        text.forEach { ch ->
            sb.append(ch)
            if (paint.measureText(sb.toString()) > maxWidth) {
                out += sb.toString()
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun ry3d(rx: Float): Float = rx * 0.34f

    /** 按亮度系数缩放 RGB（保留 alpha） */
    private fun shade(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), r, g, b)
    }

    /** 卡通描边用的加深色 */
    private fun darken(color: Int): Int = shade(color, 0.62f)
}
