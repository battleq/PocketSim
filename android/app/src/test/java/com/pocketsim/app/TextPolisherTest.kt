package com.pocketsim.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入框 AI 润色单测：
 * 合法润色、代码围栏/引号清洗、数值原样保留、编造数值拦截、超时与空输入。
 */
class TextPolisherTest {

    private class FakeAiClient(
        private val reply: String = "",
        private val error: Throwable? = null,
        private val delayMs: Long = 0
    ) : AiClient {
        var lastMessages: List<LlmMessage> = emptyList()
        override suspend fun complete(messages: List<LlmMessage>, jsonMode: Boolean, seedOverride: Int?, tag: String?): String {
            lastMessages = messages
            if (delayMs > 0) delay(delayMs)
            error?.let { throw it }
            return reply
        }
    }

    private fun polisher(client: AiClient) = TextPolisher { client }

    @Test
    fun `润色返回正文并清洗围栏与引号`() = runTest {
        val client = FakeAiClient(
            """
            ```
            润色后：“靠墙装一块松木搁板，长 80 厘米，上面放 10 公斤重物，一端固定在墙上。”
            ```
            """.trimIndent()
        )
        val out = polisher(client).polish("墙上装搁板 80 厘米 放 10 公斤 会不会弯")
        assertEquals("靠墙装一块松木搁板，长 80 厘米，上面放 10 公斤重物，一端固定在墙上。", out)
    }

    @Test
    fun `提示词要求不新增数字且保留原值`() = runTest {
        val client = FakeAiClient("松木搁板长 80 厘米，上面放 10 公斤重物。")
        polisher(client).polish("墙上装一块 80 厘米长的松木搁板，放 10 公斤重物")
        val system = client.lastMessages.first { it.role == "system" }.content
        assertTrue(system.contains("不要新增任何数字"))
        assertTrue(system.contains("原样照抄"))
        assertTrue(client.lastMessages.last().content.contains("80 厘米"))
    }

    @Test
    fun `编造新数值时拒绝结果`() = runTest {
        // 把 80 厘米换算成 0.8 米也属于新增数值，直接拦截
        val client = FakeAiClient("松木搁板长 0.8 米，上面放 10 公斤重物。")
        val e = runCatching { polisher(client).polish("80 厘米长的搁板，放 10 公斤") }.exceptionOrNull()
        assertTrue("应抛 PolishException，实际是 $e", e is PolishException)
        assertTrue(e!!.message!!.contains("0.8"))
    }

    @Test
    fun `仅格式差异的数值不算新增`() = runTest {
        // 1.50 → 1.5、08 → 8 属于同一数值
        val client = FakeAiClient("跨度为 1.5 米，挂 8 公斤重物。")
        val out = polisher(client).polish("跨度 1.50 米，挂 08 公斤")
        assertEquals("跨度为 1.5 米，挂 8 公斤重物。", out)
    }

    @Test
    fun `空输入直接报错`() = runTest {
        val e = runCatching { polisher(FakeAiClient("x")).polish("   ") }.exceptionOrNull()
        assertTrue(e is PolishException)
        assertTrue(e!!.message!!.contains("请先输入"))
    }

    @Test
    fun `AI 返回空文本报错`() = runTest {
        val e = runCatching { polisher(FakeAiClient("   ")).polish("一个书架") }.exceptionOrNull()
        assertTrue(e is PolishException)
        assertTrue(e!!.message!!.contains("没有返回"))
    }

    @Test
    fun `超时转换成中文提示`() = runTest {
        // runTest 虚拟时间会自动推进到超时点
        val client = FakeAiClient(reply = "慢慢整理", delayMs = 30_000)
        val e = runCatching { polisher(client).polish("一个书架") }.exceptionOrNull()
        assertTrue("应抛 PolishException，实际是 $e", e is PolishException)
        assertTrue(e!!.message!!.contains("超时"))
    }
}
