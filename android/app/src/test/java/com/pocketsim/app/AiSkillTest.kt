package com.pocketsim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 技能单测：内置草稿完整性、自定义技能校验、系统提示词注入。
 */
class AiSkillTest {

    private class NoAiClient : AiClient {
        override suspend fun complete(messages: List<LlmMessage>, jsonMode: Boolean, seedOverride: Int?, tag: String?): String =
            error("单测不应调用 AI 接口")
    }

    @Test
    fun `内置技能两条、字段完整且 id 唯一`() {
        val builtIns = AiSkill.BUILT_INS
        assertEquals(2, builtIns.size)
        assertEquals(builtIns.size, builtIns.map { it.id }.distinct().size)
        builtIns.forEach { skill ->
            assertTrue(skill.builtin)
            assertTrue(skill.name.isNotBlank())
            assertTrue(skill.brief.isNotBlank())
            assertTrue(skill.instruction.length > 20)
        }
        assertEquals(
            listOf("保守校核", "快速估算"),
            builtIns.map { it.name }
        )
    }

    @Test
    fun `选中技能时提示词末尾追加技能段`() {
        val skill = AiSkill.BUILT_INS.first()
        val session = ChatSession(
            aiClientProvider = { NoAiClient() },
            greeting = "你好",
            skill = skill
        )
        // 材料库占位符在建模时被替换，故只比较占位符之前的部分
        val head = ChatSession.FRAME_AUTO_SYSTEM_PROMPT.substringBefore("{{AVAILABLE_MATERIALS}}")
        assertTrue(session.buildSystemPrompt().startsWith(head))
        assertTrue(session.buildSystemPrompt().contains("本次附加技能：${skill.name}"))
        assertTrue(session.buildSystemPrompt().contains("安全校核为先"))
        // 硬性规则仍然声明优先
        assertTrue(session.buildSystemPrompt().contains("硬性规则仍然优先"))
        // JSON 输出契约保留
        assertTrue(session.buildSystemPrompt().contains("只输出一个 JSON"))
    }

    @Test
    fun `三维模式也追加技能段且互不影响`() {
        val skill = AiSkill.BUILT_INS.last()
        val session = ChatSession(
            aiClientProvider = { NoAiClient() },
            greeting = "你好",
            threeD = true,
            skill = skill
        )
        val head = ChatSession.FRAME3D_SYSTEM_PROMPT.substringBefore("{{AVAILABLE_MATERIALS}}")
        assertTrue(session.buildSystemPrompt().startsWith(head))
        assertTrue(session.buildSystemPrompt().contains("本次附加技能：${skill.name}"))
    }

    @Test
    fun `未选技能时提示词不含技能段`() {
        val session = ChatSession(aiClientProvider = { NoAiClient() }, greeting = "你好")
        assertFalse(session.buildSystemPrompt().contains("本次附加技能"))
        // 提示词 = 模板（占位符前）+ 当前材料库说明 + 模板剩余部分
        val head = ChatSession.FRAME_AUTO_SYSTEM_PROMPT.substringBefore("{{AVAILABLE_MATERIALS}}")
        assertEquals(head, session.buildSystemPrompt().substringBefore("当前材料库"))
    }

    @Test
    fun `自定义技能校验与规范化`() {
        // 名称或指令为空 → 不可保存
        assertNull(AiSkill.normalize("", "  ", "", "指令"))
        assertNull(AiSkill.normalize("", "名称", "", "   "))
        // 内置 id 不可被覆盖
        assertNull(AiSkill.normalize("builtin.conservative", "名称", "", "指令"))

        val saved = AiSkill.normalize("", "  我的技能  ", " 场景说明 ", " 按我的思路建模 ")
        assertNotNull(saved)
        assertEquals("我的技能", saved!!.name)
        assertEquals("场景说明", saved.brief)
        assertEquals("按我的思路建模", saved.instruction)
        assertFalse(saved.builtin)
        assertTrue(saved.id.startsWith("custom-"))

        // 编辑已有自定义技能：保留 id
        val edited = AiSkill.normalize(saved.id, "改名", "", "新指令")
        assertEquals(saved.id, edited!!.id)
        assertEquals("改名", edited.name)
    }

    @Test
    fun `超长字段按上限截断`() {
        val saved = AiSkill.normalize("", "名".repeat(50), "景".repeat(200), "指".repeat(900))
        assertNotNull(saved)
        assertEquals(AiSkill.MAX_NAME, saved!!.name.length)
        assertEquals(AiSkill.MAX_BRIEF, saved.brief.length)
        assertEquals(AiSkill.MAX_INSTRUCTION, saved.instruction.length)
    }
}
