package com.pocketsim.app

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选差异控制（AC-4 / AC-5）+ ModelCandidate.from（AC-6）的纯逻辑单测。
 * 无 Android 依赖，直接在 JVM 运行。
 */
class CandidateDivergenceTest {

    /** 构造指定节点/杆件数的框架（仅读尺寸，不校验几何合法性） */
    private fun frameModelJson(nodeCount: Int, memberCount: Int): String {
        val nodes = (1..nodeCount).joinToString(",") { """{"id":$it,"x":${(it - 1) * 100},"y":0}""" }
        val members = (1..memberCount).joinToString(",") {
            """{"id":$it,"i":$it,"j":${it + 1},"width_b":40,"depth_h":18,"material":"松木"}"""
        }
        return """{"name":"m","nodes":[$nodes],"members":[$members],"supports":[],"loads":[]}"""
    }

    private fun frame(nodeCount: Int, memberCount: Int): FrameModel =
        Json.decodeFromString(frameModelJson(nodeCount, memberCount))

    private fun frameCand(n: Int, m: Int) = ModelCandidate.Frame(frame(n, m), "概要")

    private fun shellOf(height: Double): ShellVesselModel =
        Json.decodeFromString("""{"name":"t","geometry":{"section":"box","height_m":$height,"thickness_m":0.01}}""")

    // TR-4.1：框架节点差 ≤3、杆件差 ≤4
    @Test
    fun `框架差异 节点差不超过3 且 杆件差不超过4`() {
        val base = frameCand(10, 12)
        assertTrue("完全相同应相似", CandidateDivergence.areSimilar(base, frameCand(10, 12), true))
        assertTrue("节点+3/杆件+4 边界应保留", CandidateDivergence.areSimilar(base, frameCand(13, 16), true))
        assertFalse("节点+4 应被过滤", CandidateDivergence.areSimilar(base, frameCand(14, 12), true))
        assertFalse("杆件+5 应被过滤", CandidateDivergence.areSimilar(base, frameCand(10, 17), true))
    }

    // TR-4.2：非框架关键尺寸相对偏差 ≤20%
    @Test
    fun `容器关键尺寸相对偏差阈值`() {
        val base = ModelCandidate.Shell(shellOf(1.0), "概要")
        assertTrue("+10% 应保留", CandidateDivergence.areSimilar(base, ModelCandidate.Shell(shellOf(1.1), "s"), true))
        assertFalse("+30% 应过滤", CandidateDivergence.areSimilar(base, ModelCandidate.Shell(shellOf(1.3), "s"), true))
    }

    // A2 回归：候选漏填可选尺寸（null）不应因列表长度不同被误杀
    @Test
    fun `候选漏填可选尺寸不应被误杀`() {
        val base = ModelCandidate.Shell(shellOf(1.0), "概要") // height=1.0, thickness=0.01
        val missingThickness: ShellVesselModel = Json.decodeFromString(
            """{"name":"t","geometry":{"section":"box","height_m":1.0}}"""
        )
        assertTrue("共同尺寸一致、仅漏填可选厚度不应被误杀",
            CandidateDivergence.areSimilar(base, ModelCandidate.Shell(missingThickness, "s"), true))
    }

    @Test
    fun `关闭差异控制时全部通过`() {
        val base = frameCand(10, 12)
        assertTrue(CandidateDivergence.areSimilar(base, frameCand(100, 100), false))
    }

    // TR-4.3：类型不同判为不相似；from 对未就绪返回 null
    @Test
    fun `不同类型判为不相似`() {
        val base = frameCand(10, 12)
        val shell = ModelCandidate.Shell(shellOf(1.0), "s")
        assertFalse(CandidateDivergence.areSimilar(base, shell, true))
    }

    @Test
    fun `from 对未就绪返回 null`() {
        val parse = AiFrameResult(ready = false, kind = "frame", model = frame(2, 1))
        assertNull("ready=false 不应构造候选", ModelCandidate.from(parse))
    }

    @Test
    fun `from 对就绪框架返回 Frame 候选`() {
        val parse = AiFrameResult(ready = true, kind = "frame", model = frame(2, 1))
        val c = ModelCandidate.from(parse)
        assertNotNull(c)
        assertEquals("frame", c!!.kind)
        assertTrue(c.summary.contains("2节点"))
    }
}
