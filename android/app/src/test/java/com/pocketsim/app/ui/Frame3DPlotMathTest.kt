/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * 本文件为商业专有资产，未经授权不得复制/修改/分发，见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import com.pocketsim.app.FramePolyline
import com.pocketsim.app.FrameResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3D-3 三维投影纯函数单测：旋转/俯仰映射、包围盒适配、画家算法排序、网格间距。
 * 不依赖 Android，可直接在 JVM 运行。
 */
class Frame3DPlotMathTest {

    private val eps = 1e-6

    /** pitch=0 时 z 向上映射到屏幕上方（sy>0），depth 取 y 进深 */
    @Test
    fun pitchZeroKeepsZUp() {
        val v = View3D(yawDeg = 0.0, pitchDeg = 0.0)
        val p = v.project(0.0, 0.0, 100.0)
        assertEquals(0.0, p.sx, eps)
        assertEquals(100.0, p.sy, eps)
        assertEquals(0.0, p.depth, eps)

        val y = v.project(0.0, 250.0, 0.0)
        assertEquals(0.0, y.sx, eps)
        assertEquals(0.0, y.sy, eps)
        assertEquals(250.0, y.depth, eps)
    }

    /** yaw=90°：原 x 轴转到进深方向，原 y 轴转到屏幕水平方向 */
    @Test
    fun yawRotatesHorizontalPlane() {
        val v = View3D(yawDeg = 90.0, pitchDeg = 0.0)
        val px = v.project(100.0, 0.0, 0.0)
        assertEquals(0.0, px.sx, eps)
        assertEquals(0.0, px.sy, eps)
        assertEquals(100.0, px.depth, eps)

        val py = v.project(0.0, 100.0, 0.0)
        assertEquals(-100.0, py.sx, eps)
        assertEquals(0.0, py.sy, eps)
        assertEquals(0.0, py.depth, eps)
    }

    /** pitch=90° 俯视：水平进深变成屏幕竖直，z 变成相机进深（高度） */
    @Test
    fun pitchNinetyLooksFromTop() {
        val v = View3D(yawDeg = 0.0, pitchDeg = 90.0)
        val p = v.project(0.0, 100.0, 50.0)
        assertEquals(0.0, p.sx, eps)
        assertEquals(100.0, p.sy, eps)
        assertEquals(-50.0, p.depth, eps)
    }

    /** 包围盒 + 等比适配：所有点应落在画布 padding 内 */
    @Test
    fun projectorFitsAllPointsInsidePadding() {
        val view = View3D()
        val points = listOf(
            WorldPoint(0.0, 0.0, 0.0),
            WorldPoint(800.0, 500.0, 900.0),
            WorldPoint(400.0, 200.0, 400.0)
        )
        val project = SceneProjector(view, viewBounds(points, view), 400f, 300f, 20f)
        points.forEach { p ->
            val px = project.project(p)
            assertTrue("x=${px.x}", px.x >= 20f && px.x <= 380f)
            assertTrue("y=${px.y}", px.y >= 20f && px.y <= 280f)
        }
    }

    /** 画家算法：按进深降序（远的先画） */
    @Test
    fun painterOrderDrawsFarFirst() {
        val items = listOf("near", "far", "mid")
        val depths = mapOf("near" to 1.0, "far" to 3.0, "mid" to 2.0)
        assertEquals(
            listOf("far", "mid", "near"),
            painterOrder(items) { depths.getValue(it) }
        )
    }

    /** 网格间距取 1/2/5×10^n */
    @Test
    fun niceGridStep() {
        assertEquals(50.0, niceStep(37.0), eps)
        assertEquals(0.5, niceStep(0.3), eps)
        assertEquals(200.0, niceStep(120.0), eps)
        assertEquals(1.0, niceStep(0.0), eps)
    }

    /** 结果折线维度识别：6 分量 = 3D，4 分量 = 2D */
    @Test
    fun detectPolylineDimension() {
        val r3d = FrameResult(
            polylines = listOf(FramePolyline(member = 1, pts = listOf(listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0))))
        )
        assertTrue(is3dPolyline(r3d))
        val r2d = FrameResult(
            polylines = listOf(FramePolyline(member = 1, pts = listOf(listOf(0.0, 0.0, 0.0, 0.0))))
        )
        assertFalse(is3dPolyline(r2d))
    }

    /** 杆件长度文案：整数不带小数，小数保留 1 位，3D 斜杆取欧氏距离，单位 mm */
    @Test
    fun memberLengthLabelFormats() {
        assertEquals("2400 mm", memberLengthLabel(WorldPoint(0.0, 0.0, 0.0), WorldPoint(2400.0, 0.0, 0.0)))
        assertEquals("1234.5 mm", memberLengthLabel(WorldPoint(0.0, 0.0, 0.0), WorldPoint(1234.5, 0.0, 0.0)))
        assertEquals("5 mm", memberLengthLabel(WorldPoint(0.0, 0.0, 0.0), WorldPoint(3.0, 4.0, 0.0)))
        assertEquals("3 mm", memberLengthLabel(WorldPoint(0.0, 0.0, 0.0), WorldPoint(1.0, 2.0, 2.0)))
    }
}
