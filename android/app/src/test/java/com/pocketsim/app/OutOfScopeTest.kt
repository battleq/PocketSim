package com.pocketsim.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 超范围拒答（out_of_scope）端到端测试：
 *  - 各建模模式提示词均追加 out_of_scope 规则（TR-2.1 / TR-2.2）；
 *  - LLM 返回 out_of_scope 时展示统一话术且不跳预览（TR-3.1）；
 *  - 描述不清导致的解析失败仍走 notUnderstoodMessage，不显示超范围话术（TR-3.2）。
 */
class OutOfScopeTest {

    private fun session(
        threeD: Boolean = false,
        forceSolid: Boolean = false,
        forceSolidGeneric: Boolean = false,
        forceShell: Boolean = false,
        language: String = LocaleStore.LANG_ZH,
        onFrameReady: (FrameModel) -> Unit = { throw AssertionError("onFrameReady 不应被调用") },
        onShellReady: (ShellVesselModel) -> Unit = { throw AssertionError("onShellReady 不应被调用") },
        onSolidGenericReady: (SolidGenericModel) -> Unit =
            { throw AssertionError("onSolidGenericReady 不应被调用") },
        onFlowReady: (FlowModel) -> Unit = { throw AssertionError("onFlowReady 不应被调用") },
        onCandidatesReady: (List<ModelCandidate>) -> Unit =
            { throw AssertionError("onCandidatesReady 不应被调用") },
        client: AiClient = FakeAiClient(emptyList())
    ) = ChatSession(
        aiClientProvider = { client },
        greeting = "你好",
        threeD = threeD,
        forceSolid = forceSolid,
        forceSolidGeneric = forceSolidGeneric,
        forceShell = forceShell,
        language = language,
        candidateCountProvider = { 1 },
        onFrameReady = onFrameReady,
        onShellReady = onShellReady,
        onSolidGenericReady = onSolidGenericReady,
        onFlowReady = onFlowReady,
        onCandidatesReady = onCandidatesReady
    )

    // TR-2.1 / TR-2.2：每种模式的系统提示词都必须含 out_of_scope 规则，且说明描述不清仍走 questions
    @Test
    fun `各建模模式提示词均追加 out_of_scope 规则`() {
        val sessions = listOf(
            session() to "自动路由",
            session(threeD = true) to "3D 框架",
            session(forceShell = true) to "薄壁容器",
            session(forceSolidGeneric = true) to "通用三维实体",
            session(forceSolid = true) to "三维实体墙体"
        )
        for ((s, name) in sessions) {
            val p = s.buildSystemPrompt()
            assertTrue("$name 提示词应含 out_of_scope 规则", p.contains("out_of_scope"))
            assertTrue("$name 提示词应含 category 示例", p.contains("category"))
            assertTrue("$name 提示词应说明描述不清仍走 questions 追问", p.contains("questions"))
        }
    }

    @Test
    fun `英文模式提示词追加英文版 out_of_scope 规则`() {
        val p = session(language = LocaleStore.LANG_EN).buildSystemPrompt()
        assertTrue(p.contains("OUT-OF-SCOPE"))
        assertTrue(p.contains("out_of_scope"))
        assertTrue(p.contains("category"))
    }

    // TR-3.1：LLM 返回 out_of_scope → 展示统一话术，不跳任何预览/候选
    @Test
    fun `LLM 返回 out_of_scope 时展示统一话术且不跳预览`() = runTest {
        val resp = """{"ready":false,"kind":"out_of_scope","category":"电磁-结构耦合","questions":[]}"""
        var readyFired = false
        val s = session(
            client = FakeAiClient(listOf(resp)),
            onFrameReady = { readyFired = true },
            onShellReady = { readyFired = true },
            onSolidGenericReady = { readyFired = true },
            onFlowReady = { readyFired = true },
            onCandidatesReady = { readyFired = true }
        )
        s.send("帮我分析一下电磁场对这个钢结构的影响")

        val texts = s.messages.value.map { it.text }
        assertTrue(
            "对话应含超范围话术：${texts.joinToString("|")}",
            texts.any { it.contains("电磁-结构耦合") && it.contains("超出本软件的分析范畴") }
        )
        assertFalse("不应触发任何预览/候选回调", readyFired)
    }

    // TR-3.2：描述不清/无法解析 → 仍走 notUnderstoodMessage，不显示超范围话术
    @Test
    fun `描述不清导致解析失败时不显示超范围话术`() = runTest {
        // 完全不像力学问题、也不是合法 JSON → AiParseException → notUnderstoodMessage
        val s = session(client = FakeAiClient(listOf("今天天气真好", "今天天气真好")))
        s.send("今天天气真好")

        val texts = s.messages.value.map { it.text }
        assertTrue(
            "应走引导补充描述：${texts.joinToString("|")}",
            texts.any { it.contains("力学问题") || it.contains("理解") }
        )
        assertFalse(
            "不应显示超范围话术：${texts.joinToString("|")}",
            texts.any { it.contains("超出本软件的分析范畴") }
        )
    }
}
