package com.pocketsim.app

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线智能预填数据流测试（pocketsim.parser.parse_problem 冻结契约的 fake 驱动）：
 * ProblemParser 用 fake 实现（不依赖 Chaquopy / Android 运行时），验证
 * OfflinePrefill 的模板命中、参数回填、来源标记（OFFLINE）、missing 过滤与
 * "待补充"标记，以及识别失败 / 引擎异常路径返回 null（UI 留在当前页提示手动选模板）。
 */
class ParserFlowTest {

    // ------------------------------------------------------------------
    // 测试替身
    // ------------------------------------------------------------------

    /** 假解析器：返回预设 outcome（null 模拟 ok=false 未识别） */
    private class FakeParser(private val outcome: ParseOutcome?) : ProblemParser {
        var lastText: String? = null
        override suspend fun parseProblem(text: String): ParseOutcome? {
            lastText = text
            return outcome
        }
    }

    /** 抛异常的假解析器（模拟 pocketsim.parser 模块缺失 / 字段异常） */
    private class ThrowingParser : ProblemParser {
        override suspend fun parseProblem(text: String): ParseOutcome? =
            throw FeaEngineException("pocketsim.parser 模块不可用")
    }

    /** 模板列表（schema 与 ParamFlowTest 的 fake 引擎保持一致） */
    private val templates = listOf(
        TemplateInfo(
            id = "cantilever_shelf", name = "悬臂搁板", description = "一端固定于墙体",
            param_schema = listOf(
                ParamSpec(key = "length_mm", label = "悬挑长度", unit = "mm", type = "float", default = JsonPrimitive(300)),
                ParamSpec(key = "thickness_mm", label = "板厚", unit = "mm", type = "float", default = JsonPrimitive(18)),
                ParamSpec(key = "load_kg", label = "重物质量", unit = "kg", type = "float", required = true),
                ParamSpec(key = "material_name", label = "材料", type = "material", default = JsonPrimitive("松木"))
            )
        ),
        TemplateInfo(
            id = "nail_on_wall", name = "墙上钉挂重", description = "钉悬臂弯曲 + 墙承压简化校核",
            param_schema = listOf(
                ParamSpec(key = "nail_diameter_mm", label = "钉径", unit = "mm", type = "float", default = JsonPrimitive(4)),
                ParamSpec(key = "load_kg", label = "挂重", unit = "kg", type = "float", required = true),
                ParamSpec(key = "material_name", label = "钉材料", type = "material", default = JsonPrimitive("低碳钢"))
            )
        )
    )

    // ------------------------------------------------------------------
    // 成功路径：识别 → 模板命中 → 参数回填 → 参数确认页
    // ------------------------------------------------------------------

    @Test
    fun `识别成功 模板命中与参数回填`() = runTest {
        val parser = FakeParser(
            ParseOutcome(
                templateId = "cantilever_shelf",
                params = mapOf(
                    "length_mm" to JsonPrimitive(400),
                    "load_kg" to JsonPrimitive(3),
                    "material_name" to JsonPrimitive("松木")
                ),
                missing = listOf("thickness_mm"),
                confidence = 0.87
            )
        )

        val scenario = OfflinePrefill.run(parser, "墙上搁板悬挑 400 毫米挂 3 公斤", templates)

        // 跳转参数确认页的载荷
        assertNotNull(scenario)
        assertEquals("cantilever_shelf", scenario!!.template.id)
        // 参数回填（字符串形式）
        assertEquals("400", scenario.params["length_mm"])
        assertEquals("3", scenario.params["load_kg"])
        assertEquals("松木", scenario.params["material_name"])
        // 来源诚实标记为离线识别（而非 AI 提取）
        assertEquals(ParamSource.OFFLINE, scenario.sources["length_mm"])
        assertEquals(ParamSource.OFFLINE, scenario.sources["load_kg"])
        assertEquals(ParamSource.OFFLINE, scenario.sources["material_name"])
        // 离线标记 + 待补充项
        assertTrue(scenario.offline)
        assertEquals(listOf("thickness_mm"), scenario.missing)
        // parser 收到原始用户描述
        assertEquals("墙上搁板悬挑 400 毫米挂 3 公斤", parser.lastText)
    }

    @Test
    fun `识别成功后经 ParamForm 初始化参数确认页表单`() = runTest {
        // 模拟路由跳转后 ParamConfirmScreen 的表单初始化
        val scenario = OfflinePrefill.toScenario(
            ParseOutcome(
                templateId = "cantilever_shelf",
                params = mapOf("length_mm" to JsonPrimitive(400)),
                missing = listOf("load_kg", "bogus_key"),   // bogus_key 不在 schema 中，应被过滤
                confidence = 0.7
            ),
            templates
        )
        assertNotNull(scenario)

        val form = ParamForm(
            template = scenario!!.template,
            prefilled = scenario.params,
            initialSources = scenario.sources
        )
        // 预填项来源为离线识别；未抽取项回填默认值（内置）
        assertEquals("400", form.values["length_mm"])
        assertEquals(ParamSource.OFFLINE, form.sources["length_mm"])
        assertEquals("18", form.values["thickness_mm"])
        assertEquals(ParamSource.BUILTIN, form.sources["thickness_mm"])
        // load_kg 必填无默认 → 校验不通过，与 UI"待补充"提示一致（该项必须人工补充后才能计算）
        assertTrue(form.validationErrors().containsKey("load_kg"))
        // bogus 键被 schema 过滤，不会渲染出无意义的"待补充"标记
        assertEquals(listOf("load_kg"), scenario.missing)
        // 待补充标记的判定与 ParamConfirmScreen 一致：offline && key in missing
        val pendingKeys = scenario.template.param_schema.map { it.key }.filter { it in scenario.missing }
        assertEquals(listOf("load_kg"), pendingKeys)
    }

    // ------------------------------------------------------------------
    // 失败路径：未识别 / 未知模板 / 引擎异常 → null（留在当前页）
    // ------------------------------------------------------------------

    @Test
    fun `识别失败 未识别与未知模板返回 null`() = runTest {
        // parser 返回 null（契约 ok=false）→ 未识别
        assertNull(OfflinePrefill.run(FakeParser(null), "今天天气怎么样", templates))
        // template_id 不在模板列表中 → 同样视为失败（无法跳转）
        assertNull(
            OfflinePrefill.toScenario(
                ParseOutcome(templateId = "no_such_template"),
                templates
            )
        )
        // 引擎异常 → 吞掉并按未识别处理（UI 提示"未能识别，请手动选择模板"）
        assertNull(OfflinePrefill.run(ThrowingParser(), "任意文本", templates))
    }

    @Test
    fun `空值与非标量参数被过滤`() = runTest {
        val scenario = OfflinePrefill.toScenario(
            ParseOutcome(
                templateId = "nail_on_wall",
                params = mapOf(
                    "nail_diameter_mm" to JsonPrimitive(4.5),
                    "load_kg" to JsonPrimitive(""),        // 空字符串 → 过滤
                    "material_name" to JsonPrimitive("  ") // 空白 → 过滤
                ),
                missing = listOf("load_kg", "material_name"),
                confidence = 0.6
            ),
            templates
        )
        assertNotNull(scenario)
        // 只有非空标量进入预填
        assertEquals(setOf("nail_diameter_mm"), scenario!!.params.keys)
        assertEquals("4.5", scenario.params["nail_diameter_mm"])
        assertFalse(scenario.params.containsKey("load_kg"))
        // 抽取到空值的参数归入待补充
        assertEquals(listOf("load_kg", "material_name"), scenario.missing)
    }
}
