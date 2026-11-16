/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import com.pocketsim.app.AppJson
import com.pocketsim.app.FrameModel
import com.pocketsim.app.ModelDiff
import com.pocketsim.app.OptimizeDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 优化效果图渲染冒烟测试（Robolectric + 真实 android.graphics）：
 * 含「新增中柱 + 横杆拆分」的模型在各种视角下都能正常出图，且确实画出了内容。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OptimizeFigureRenderTest {

    private val before = """
        {"name":"门式刚架","dim":2,
         "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":800,"y":0},
                  {"id":3,"x":0,"y":1500},{"id":4,"x":800,"y":1500},{"id":5,"x":400,"y":1500}],
         "members":[{"id":1,"i":1,"j":2,"width_b":30,"depth_h":40,"material":"Q235"},
                    {"id":2,"i":1,"j":3,"width_b":30,"depth_h":40,"material":"Q235"},
                    {"id":3,"i":2,"j":4,"width_b":30,"depth_h":40,"material":"Q235"},
                    {"id":4,"i":3,"j":5,"width_b":30,"depth_h":40,"material":"Q235"}],
         "supports":[{"node":1,"type":"fixed"},{"node":2,"type":"fixed"}],
         "loads":[{"type":"force","node":5,"fx":0,"fy":-294}]}
    """.trimIndent()

    /** 横杆 1-2 被节点 6 拆成 7/8 两段，并新增中柱 9（6→7） */
    private val after = """
        {"name":"增设中柱","dim":2,
         "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":800,"y":0},
                  {"id":3,"x":0,"y":1500},{"id":4,"x":800,"y":1500},{"id":5,"x":400,"y":1500},
                  {"id":6,"x":400,"y":0},{"id":7,"x":400,"y":1500}],
         "members":[{"id":7,"i":1,"j":6,"width_b":30,"depth_h":40,"material":"Q235"},
                    {"id":8,"i":6,"j":2,"width_b":30,"depth_h":40,"material":"Q235"},
                    {"id":2,"i":1,"j":3,"width_b":30,"depth_h":40,"material":"Q235"},
                    {"id":3,"i":2,"j":4,"width_b":30,"depth_h":40,"material":"Q235"},
                    {"id":4,"i":3,"j":5,"width_b":30,"depth_h":40,"material":"Q235"},
                    {"id":9,"i":6,"j":7,"width_b":30,"depth_h":40,"material":"Q235"}],
         "supports":[{"node":1,"type":"fixed"},{"node":2,"type":"fixed"}],
         "loads":[{"type":"force","node":5,"fx":0,"fy":-294}]}
    """.trimIndent()

    private val labels = FigureLabels(
        title = "优化效果图",
        note = "拟物化示意",
        newLabel = "新增",
        modifiedLabel = "修改",
        originalLabel = "原结构"
    )

    private fun frame(json: String): FrameModel =
        AppJson.decodeFromString(FrameModel.serializer(), json)

    private fun nonBackgroundCount(pixels: IntArray): Int =
        pixels.count { it != 0xFFF6F8F9.toInt() }

    /** 把效果图存到 app/build/robo-screenshots，便于人工核对渲染效果 */
    private fun save(bmp: android.graphics.Bitmap, name: String) {
        val dir = java.io.File("build/robo-screenshots").apply { mkdirs() }
        java.io.File(dir, "$name.png").outputStream().use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun `默认视角渲染不抛异常且画出内容`() {
        val diff = OptimizeDiff.diffFrame(frame(before), frame(after))
        val bmp = OptimizeFigureRenderer.renderFrame(frame(after), diff, labels, 640, 400)
        assertEquals(640, bmp.width)
        assertEquals(400, bmp.height)
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        assertTrue(nonBackgroundCount(pixels) > 500)
        save(bmp, "optimize-figure-default")
    }

    @Test
    fun `旋转到背面视角仍能正常出图`() {
        val diff = OptimizeDiff.diffFrame(frame(before), frame(after))
        val bmp = OptimizeFigureRenderer.renderFrame(
            frame(after), diff, labels, 640, 400, View3D(yawDeg = 215.0, pitchDeg = -35.0)
        )
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        assertTrue(nonBackgroundCount(pixels) > 500)
    }

    @Test
    fun `空间框架三维渲染正常`() {
        val json3d = """
            {"name":"空间框架","dim":3,
             "nodes":[{"id":1,"x":0,"y":0,"z":0},{"id":2,"x":2000,"y":0,"z":0},
                      {"id":3,"x":2000,"y":1500,"z":0},{"id":4,"x":0,"y":1500,"z":0},
                      {"id":5,"x":0,"y":0,"z":2500},{"id":6,"x":2000,"y":0,"z":2500}],
             "members":[{"id":1,"i":1,"j":2,"width_b":100,"depth_h":200,"material":"Q235"},
                        {"id":2,"i":2,"j":3,"width_b":100,"depth_h":200,"material":"Q235"},
                        {"id":3,"i":3,"j":4,"width_b":100,"depth_h":200,"material":"Q235"},
                        {"id":4,"i":1,"j":5,"width_b":150,"depth_h":150,"material":"Q235"},
                        {"id":5,"i":2,"j":6,"width_b":150,"depth_h":150,"material":"Q235"}],
             "supports":[{"node":1,"type":"fixed"},{"node":2,"type":"fixed"}],
             "loads":[{"type":"force","node":5,"fx":0,"fy":0,"fz":-10000}]}
        """.trimIndent()
        val model = frame(json3d)
        // 3D 支架没有"原模型"对照，这里直接用空 diff 验证渲染路径
        val bmp = OptimizeFigureRenderer.renderFrame(model, ModelDiff.EMPTY, labels, 640, 400)
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        assertTrue(nonBackgroundCount(pixels) > 500)
    }
}
