package com.pocketsim.app

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * v0.3.0 新增能力的 JVM 单测：
 *  - 多模态消息形状（buildLlmMessagesArray：无图纯文本 / 带图 image_url 数组）
 *  - ChatSession 带图发送（图片随消息进历史、指引文本只给 LLM、允许只发照片）
 *  - FrameFollowUpSession 四分支（问答 / 修改出模型 / 追问 / 自由文本兜底 / 校验失败）
 */
class FollowUpSessionTest {

    private val img1 = "data:image/jpeg;base64,AAAA"
    private val img2 = "data:image/jpeg;base64,BBBB"

    // ------------------------------------------------------------------
    // 多模态消息 JSON 形状（LlmClient 请求体组装）
    // ------------------------------------------------------------------

    @Test
    fun `buildLlmMessagesArray 无图时content为纯文本`() {
        val arr = buildLlmMessagesArray(
            listOf(LlmMessage("system", "提示词"), LlmMessage("user", "算一个书架"))
        )
        assertEquals(2, arr.size)
        val system = arr[0].jsonObject
        assertEquals("system", system["role"]!!.jsonPrimitive.content)
        assertTrue("无图消息 content 应为字符串", system["content"] is JsonPrimitive)
        val user = arr[1].jsonObject
        assertEquals("算一个书架", user["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildLlmMessagesArray 带图时content为text加image_url数组`() {
        val arr = buildLlmMessagesArray(
            listOf(LlmMessage("user", "照照片建模", listOf(img1, img2)))
        )
        val user = arr[0].jsonObject
        assertEquals("user", user["role"]!!.jsonPrimitive.content)
        val content = user["content"]!!.jsonArray
        assertEquals("text 在前 + 2 张图", 3, content.size)
        // 首段文本
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("照照片建模", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        // 图片按传入顺序内联
        for ((i, uri) in listOf(img1, img2).withIndex()) {
            val part = content[1 + i].jsonObject
            assertEquals("image_url", part["type"]!!.jsonPrimitive.content)
            assertEquals(uri, part["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        }
    }

    // ------------------------------------------------------------------
    // ChatSession 带图发送（frameMode）
    // ------------------------------------------------------------------

    private val frameModelJson = """
        {"name":"悬臂搁板",
         "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":400,"y":0}],
         "members":[{"id":1,"i":1,"j":2,"width_b":40,"depth_h":18,"material":"松木"}],
         "supports":[{"node":1,"type":"fixed"}],
         "loads":[{"type":"force","node":2,"fx":0,"fy":-294}]}
    """.trimIndent()

    @Test
    fun `ChatSession 带图发送图片随消息进入请求与历史`() = runTest {
        var frameReady: FrameModel? = null
        val client = FakeAiClient(listOf("""{"ready":true,"questions":[],"model":$frameModelJson}"""))
        val session = ChatSession(
            aiClientProvider = { client },
            greeting = "你好",
            onFrameReady = { frameReady = it }
        )

        session.send("照这个照片做个搁板", listOf(img1, img2))
        assertNotNull(frameReady)

        // 首次请求：user 消息带图，文本附加了图片识别指引（只给 LLM，不进气泡）
        val first = client.calls[0]
        val userMsg = first.last()
        assertEquals("user", userMsg.role)
        assertEquals(listOf(img1, img2), userMsg.images)
        assertTrue(userMsg.content.contains("照这个照片做个搁板"))
        assertTrue(userMsg.content.contains("[附图]"))
        // 气泡显示原始文字，不含机器指引
        val userBubble = session.messages.value.first { it.fromUser }
        assertEquals("照这个照片做个搁板", userBubble.text)
    }

    @Test
    fun `ChatSession 只发照片也会发送并占位气泡`() = runTest {
        val client = FakeAiClient(listOf("""{"ready":false,"questions":["拍的是什么结构？"],"model":null}"""))
        val session = ChatSession(
            aiClientProvider = { client },
            greeting = "你好"
        )

        session.send("", listOf(img1))
        // 调用发生：无文字但带图 → 正常发送
        assertEquals(1, client.calls.size)
        assertTrue(client.calls[0].last().content.contains("[附图]"))
        // 用户气泡以占位说明代替空文本
        assertTrue(session.messages.value.any { it.fromUser && it.text.contains("1 张照片") })
    }

    @Test
    fun `ChatSession 空文字空图片不发送`() = runTest {
        val client = FakeAiClient(listOf(""))
        val session = ChatSession(
            aiClientProvider = { client },
            greeting = "你好"
        )
        session.send("   ")
        assertEquals(0, client.calls.size)
    }

    // ------------------------------------------------------------------
    // ChatSession 修改模型强锚定（FR-3 / AC-2）：当前模型 JSON 注入提示词，只改指定字段
    // ------------------------------------------------------------------

    @Test
    fun `ChatSession 修改模型时锚定当前模型且未提及字段保持不变`() = runTest {
        val original = sampleModel  // 悬臂搁板：集中力 fy=-294
        // AI 遵守锚定：只把集中力改成均布，其余完全不变
        val modified = original.copy(
            loads = listOf(FrameLoad(type = "udl", member = 1, qy = -0.3))
        )
        val modifiedJson = AppJson.encodeToString(FrameModel.serializer(), modified)
        var frameReady: FrameModel? = null
        val client = FakeAiClient(listOf(
            """{"ready":true,"questions":[],"answer":"已把集中力改为均布载荷","model":$modifiedJson}"""
        ))
        val session = ChatSession(
            aiClientProvider = { client },
            greeting = "你好",
            regenAnchor = original,
            onFrameReady = { frameReady = it }
        )
        session.send("把集中力改成均布载荷")

        val got = frameReady!!
        // AC-2：除 loads 外，节点/杆件/支座/名称/dim 与原模型 deep-equals
        assertEquals("节点应保持不变", original.nodes, got.nodes)
        assertEquals("杆件应保持不变", original.members, got.members)
        assertEquals("支座应保持不变", original.supports, got.supports)
        assertEquals("名称应保持不变", original.name, got.name)
        assertEquals("维度应保持不变", original.dim, got.dim)
        // loads 被修改
        assertEquals(1, got.loads.size)
        assertEquals("udl", got.loads[0].type)
        assertEquals(-0.3, got.loads[0].qy, 1e-9)
        // 强锚定注入到 system 消息
        val systemMsg = client.calls[0][0]
        assertEquals("system", systemMsg.role)
        assertTrue("system 应含强锚定指令", systemMsg.content.contains("强锚定"))
        assertTrue("system 应含当前模型 JSON", systemMsg.content.contains("悬臂搁板"))
    }

    @Test
    fun `ChatSession 首轮建模无锚定时不注入锚定块`() = runTest {
        val client = FakeAiClient(listOf("""{"ready":true,"questions":[],"model":$frameModelJson}"""))
        val session = ChatSession(
            aiClientProvider = { client },
            greeting = "你好"
        )
        session.send("建模一个悬臂搁板")
        val systemMsg = client.calls[0][0]
        assertEquals("system", systemMsg.role)
        assertFalse("首轮建模不应含强锚定块", systemMsg.content.contains("强锚定"))
    }

    // ------------------------------------------------------------------
    // ChatSession 系统提示词：两阶段观测 + 多图印证 + 文字优先 + 降维（FR-4 / AC-6/7/9）
    // ------------------------------------------------------------------

    @Test
    fun `ChatSession 系统提示词含观测多图文字优先降维规则`() {
        val client = FakeAiClient(listOf("""{"ready":true,"questions":[],"model":$frameModelJson}"""))
        val session = ChatSession(
            aiClientProvider = { client },
            greeting = "你好"
        )
        val prompt = session.buildSystemPrompt()
        assertTrue("提示词应说明 observation 可选字段", prompt.contains("observation"))
        assertTrue("提示词应要求多图矛盾时不强行建模", prompt.contains("互相矛盾"))
        assertTrue("提示词应要求照片看不清优先采用用户文字", prompt.contains("优先采用用户文字"))
        assertTrue("提示词应要求降维时 fz/qz 改写到 fy/qy", prompt.contains("fz/qz 改写到 fy/qy"))
    }

    // ------------------------------------------------------------------
    // FrameFollowUpSession（结果追问 / 改条件重算）
    // ------------------------------------------------------------------

    /** 与 Python 契约一致的最小结果样例（危险档） */
    private val sampleResult = FrameResult(
        model_name = "悬臂搁板", n_nodes = 2, n_members = 1, n_supports = 1,
        max_disp_mm = 3.2, max_disp_desc = "端部下垂约 3.2 毫米",
        max_stress_MPa = 12.0, governing_member = 1, safety_factor = 1.08, verdict = "危险",
        buckling_ratio = 0.0,
        members = listOf(
            FrameMemberResult(
                id = 1, material = "松木", section = "40×18",
                N_N = 0.0, M_max_Nmm = 3.5e6, sigma_MPa = 12.0, compress_kN = 0.0, util = 92.3
            )
        ),
        node_disp = emptyList(), polylines = emptyList(),
        narrative = listOf("搁板像跳水板一样弯了下来。"), solve_ms = 2.0
    )

    private val sampleModel = AppJson.decodeFromString(
        FrameModel.serializer(), frameModelJson
    )

    private fun session(
        responses: List<String>,
        onModelReady: (FrameModel) -> Unit = { }
    ): Pair<FrameFollowUpSession, FakeAiClient> {
        val client = FakeAiClient(responses)
        return FrameFollowUpSession(
            aiClientProvider = { client },
            model = sampleModel,
            result = sampleResult,
            onModelReady = onModelReady,
            greeting = "有什么想问的？"
        ) to client
    }

    @Test
    fun `FollowUp 上下文包含当前模型与结果摘要`() = runTest {
        val (s, client) = session(listOf("""{"ready":false,"questions":[],"answer":"好的","model":null}"""))
        s.send("安全系数是什么意思？")
        // system 消息固定携带当前模型 JSON 与结果指标
        val system = client.calls[0].first()
        assertEquals("system", system.role)
        assertTrue(system.content.contains("当前模型"))
        assertTrue(system.content.contains("\"悬臂搁板\""))
        assertTrue(system.content.contains("1.08"))
        assertTrue(system.content.contains("跳水板"))
    }

    @Test
    fun `FollowUp 纯问答回答不触发重建模`() = runTest {
        val (s, _) = session(
            listOf("""{"ready":false,"questions":[],"answer":"安全系数 1.08 意思是强度储备只剩 8%。","model":null}""")
        )
        var newModel: FrameModel? = null
        // answer 展示为普通回答
        s.send("安全系数是什么意思？")
        assertTrue(s.messages.value.any { !it.fromUser && it.text.contains("强度储备") })
        assertNull(newModel)
    }

    @Test
    fun `FollowUp 修改条件输出完整新模型并回调`() = runTest {
        val modifiedJson = """
            {"ready":true,"questions":[],"answer":"已把挂重加到 60 公斤。",
             "model":{"name":"悬臂搁板",
              "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":400,"y":0}],
              "members":[{"id":1,"i":1,"j":2,"width_b":40,"depth_h":18,"material":"松木"}],
              "supports":[{"node":1,"type":"fixed"}],
              "loads":[{"type":"force","node":2,"fx":0,"fy":-588}]}}
        """.trimIndent()
        var newModel: FrameModel? = null
        val (s, _) = session(listOf(modifiedJson), onModelReady = { newModel = it })

        s.send("重量加到 60 公斤")
        // 新模型完整（不是增量）：载荷已翻倍
        assertNotNull(newModel)
        assertEquals(-588.0, newModel!!.loads[0].fy, 1e-9)
        assertEquals(1, newModel!!.supports.size)
        // 会话里展示 AI 的改动说明
        assertTrue(s.messages.value.any { !it.fromUser && it.text.contains("60 公斤") })
    }

    @Test
    fun `FollowUp 修改意图不明确转为追问`() = runTest {
        val (s, _) = session(
            listOf("""{"ready":false,"questions":["重量想改成多少公斤？"],"answer":"","model":null}""")
        )
        s.send("改重点")
        assertTrue(s.messages.value.any { !it.fromUser && it.text.contains("改成多少公斤") })
    }

    @Test
    fun `FollowUp 自由文本回复兜底展示`() = runTest {
        val (s, _) = session(listOf("安全系数 1.08 表示强度储备只剩 8%，有点悬。"))
        var newModel: FrameModel? = null
        s.send("结果靠谱吗？")
        // AI 忘了 JSON 格式 → 原文当普通回答展示，不崩溃、不触发重建模
        assertTrue(s.messages.value.any { !it.fromUser && it.text.contains("有点悬") })
        assertNull(newModel)
    }

    @Test
    fun `FollowUp 新模型校验失败引导说具体`() = runTest {
        // ready=true 但模型缺支座 → parseFrame 校验抛异常 → 引导文案
        val badModel = """
            {"ready":true,"questions":[],"answer":"改好了",
             "model":{"name":"悬臂搁板",
              "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":400,"y":0}],
              "members":[{"id":1,"i":1,"j":2,"width_b":40,"depth_h":18,"material":"松木"}],
              "supports":[],
              "loads":[{"type":"force","node":2,"fx":0,"fy":-588}]}}
        """.trimIndent()
        var newModel: FrameModel? = null
        val (s, _) = session(listOf(badModel), onModelReady = { newModel = it })

        s.send("把支座去掉再算")
        assertTrue(s.messages.value.any { !it.fromUser && it.text.contains("没能通过校验") })
        assertNull("校验失败的模型不应进入预览页", newModel)
    }

    @Test
    fun `FollowUp 接口异常置fatalError`() = runTest {
        val client = object : AiClient {
            override suspend fun complete(messages: List<LlmMessage>, jsonMode: Boolean, seedOverride: Int?): String =
                throw java.io.IOException("HTTP 500")
        }
        val s = FrameFollowUpSession(
            aiClientProvider = { client },
            model = sampleModel,
            result = sampleResult,
            onModelReady = { },
            greeting = "有什么想问的？"
        )
        s.send("再算一次")
        assertTrue(s.fatalError.value)
        assertTrue(s.messages.value.any { !it.fromUser && it.text.contains("AI 接口不可用") })
    }
}
