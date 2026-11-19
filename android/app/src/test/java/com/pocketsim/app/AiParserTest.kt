package com.pocketsim.app

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * AiParser + FollowUpLogic 单元测试（JVM 纯逻辑）：
 * 合法/非法 JSON、schema 校验、重试路径、追问项裁剪 ≤2。
 */
class AiParserTest {

    /** 测试用模板集：悬臂搁板（含 1 个必填无默认参数 load_kg） */
    private val templates = listOf(
        TemplateInfo(
            id = "cantilever_shelf",
            name = "悬臂搁板",
            description = "一端固定于墙体的搁板",
            param_schema = listOf(
                ParamSpec(key = "length_mm", label = "悬挑长度", unit = "mm", type = "float", default = JsonPrimitive(300)),
                ParamSpec(key = "thickness_mm", label = "板厚", unit = "mm", type = "float", default = JsonPrimitive(18)),
                ParamSpec(key = "width_mm", label = "宽度", unit = "mm", type = "float", default = JsonPrimitive(200)),
                ParamSpec(key = "load_kg", label = "重物质量", unit = "kg", type = "float", required = true),
                ParamSpec(key = "material_name", label = "材料", type = "material", default = JsonPrimitive("松木"))
            )
        ),
        TemplateInfo(id = "nail_on_wall", name = "墙上钉挂重", param_schema = emptyList()),
        TemplateInfo(id = "simply_supported_board", name = "简支层板", param_schema = emptyList())
    )

    // ------------------------------------------------------------------
    // 合法 / 非法 JSON
    // ------------------------------------------------------------------

    @Test
    fun `parse 合法JSON带围栏与闲聊前后缀`() {
        val content = """
            好的，以下是解析结果：
            ```json
            {"template_id":"cantilever_shelf","params":{"load_kg":3,"length_mm":400},
             "missing_params":["thickness_mm"],"questions":["板厚大约多少毫米？"]}
            ```
            希望对你有帮助
        """.trimIndent()

        val result = AiParser.parse(content, templates)

        assertEquals("cantilever_shelf", result.template_id)
        assertEquals(2, result.params.size)
        assertEquals("3", (result.params["load_kg"] as JsonPrimitive).content)
        assertEquals(listOf("thickness_mm"), result.missing_params)
        assertEquals(1, result.questions.size)
    }

    @Test
    fun `parse 非法JSON抛AiParseException`() {
        assertThrows(AiParseException::class.java) {
            AiParser.parse("完全不是 JSON 的回复", templates)
        }
    }

    @Test
    fun `parse JSON语法错误抛AiParseException`() {
        assertThrows(AiParseException::class.java) {
            AiParser.parse("{\"template_id\": \"cantilever_shelf\", oops}", templates)
        }
    }

    // ------------------------------------------------------------------
    // schema 校验
    // ------------------------------------------------------------------

    @Test
    fun `parse 未知模板id抛异常`() {
        val content = """{"template_id":"unknown_template","params":{},"missing_params":[],"questions":[]}"""
        assertThrows(AiParseException::class.java) {
            AiParser.parse(content, templates)
        }
    }

    @Test
    fun `parse 模板列表为null时跳过模板校验`() {
        val content = """{"template_id":"any_id","params":{},"missing_params":[],"questions":[]}"""
        val result = AiParser.parse(content, null)
        assertEquals("any_id", result.template_id)
    }

    @Test
    fun `parse 参数值为嵌套对象抛异常`() {
        val content = """{"template_id":"cantilever_shelf","params":{"load_kg":{"value":3}},"missing_params":[],"questions":[]}"""
        assertThrows(AiParseException::class.java) {
            AiParser.parse(content, templates)
        }
    }

    @Test
    fun `parse template_id为空抛异常`() {
        val content = """{"template_id":"","params":{},"missing_params":[],"questions":[]}"""
        assertThrows(AiParseException::class.java) {
            AiParser.parse(content, templates)
        }
    }

    // ------------------------------------------------------------------
    // 重试路径（schema 校验失败自动重试一次，追加错误信息）
    // ------------------------------------------------------------------

    @Test
    fun `parseWithRetry 首次失败重试成功`() = runTest {
        val good = """{"template_id":"cantilever_shelf","params":{"load_kg":3},"missing_params":[],"questions":[]}"""
        val client = FakeAiClient(listOf("抱歉，我给不出结构化结果。", good))

        val result = AiParser.parseWithRetry(client, listOf(LlmMessage("user", "问题")), templates)

        assertEquals("cantilever_shelf", result.template_id)
        assertEquals(2, client.calls.size)
        // 第二次请求应追加了错误提示
        val retryLast = client.calls[1].last()
        assertTrue(retryLast.content.contains("无法解析"))
    }

    @Test
    fun `parseWithRetry 首次未知模板重试成功`() = runTest {
        val bad = """{"template_id":"nope","params":{},"missing_params":[],"questions":[]}"""
        val good = """{"template_id":"nail_on_wall","params":{"load_kg":3},"missing_params":[],"questions":[]}"""
        val client = FakeAiClient(listOf(bad, good))

        val result = AiParser.parseWithRetry(client, listOf(LlmMessage("user", "问题")), templates)

        assertEquals("nail_on_wall", result.template_id)
        assertEquals(2, client.calls.size)
    }

    @Test
    fun `parseWithRetry 两次失败最终抛异常`() = runTest {
        val client = FakeAiClient(listOf("bad1", "bad2"))
        val err = runCatching {
            AiParser.parseWithRetry(client, listOf(LlmMessage("user", "问题")), templates)
        }.exceptionOrNull()
        assertTrue("应抛出 AiParseException，实际是 $err", err is AiParseException)
        assertEquals(2, client.calls.size)
    }

    // ------------------------------------------------------------------
    // 框架模式：parseFrame / validateFrameModel / parseFrameWithRetry
    // ------------------------------------------------------------------

    /** 合法的两层书架模型（松木，4 杆 5 节点，跨中挂重） */
    private val frameModelJson = """
        {"name":"两层书架",
         "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":800,"y":0},{"id":3,"x":0,"y":1500},
                  {"id":4,"x":800,"y":1500},{"id":5,"x":400,"y":1500}],
         "members":[{"id":1,"i":1,"j":2,"width_b":30,"depth_h":40,"material":"松木"},
                    {"id":2,"i":1,"j":3,"width_b":30,"depth_h":40,"material":"松木"},
                    {"id":3,"i":2,"j":4,"width_b":30,"depth_h":40,"material":"松木"},
                    {"id":4,"i":3,"j":5,"width_b":30,"depth_h":40,"material":"松木"}],
         "supports":[{"node":1,"type":"fixed"},{"node":2,"type":"fixed"}],
         "loads":[{"type":"force","node":5,"fx":0,"fy":-294},
                  {"type":"udl","member":1,"qy":-0.3}]}
    """.trimIndent()

    @Test
    fun `parseFrame 合法模型`() {
        val result = AiParser.parseFrame("""{"ready":true,"questions":[],"model":$frameModelJson}""")

        assertTrue(result.ready)
        val model = result.model!!
        assertEquals("两层书架", model.name)
        assertEquals(5, model.nodes.size)
        assertEquals(4, model.members.size)
        assertEquals(2, model.supports.size)
        assertEquals(2, model.loads.size)
        assertEquals(-294.0, model.loads[0].fy, 1e-9)
    }

    @Test
    fun `parseFrame 面荷载 area 保留 members 与 total_N`() {
        val json = """
            {"ready":true,"questions":[],"model":{"name":"椅面均布","dim":2,
             "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":400,"y":0},
                      {"id":3,"x":0,"y":400},{"id":4,"x":400,"y":400}],
             "members":[{"id":1,"i":1,"j":2,"width_b":30,"depth_h":40,"material":"松木"},
                        {"id":2,"i":3,"j":4,"width_b":30,"depth_h":40,"material":"松木"}],
             "supports":[{"node":1,"type":"fixed"},{"node":2,"type":"fixed"},
                         {"node":3,"type":"fixed"},{"node":4,"type":"fixed"}],
             "loads":[{"type":"area","members":[1,2],"total_N":-600}]}}
        """.trimIndent()

        val model = AiParser.parseFrame(json).model!!

        val ld = model.loads.single()
        assertEquals("area", ld.type)
        assertEquals(listOf(1, 2), ld.members)
        assertEquals(-600.0, ld.total_N, 1e-9)
    }

    @Test
    fun `parseFrame 面荷载缺 members 被打回`() {
        val json = """
            {"ready":true,"questions":[],"model":{"name":"面荷载缺杆件","dim":2,
             "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":400,"y":0}],
             "members":[{"id":1,"i":1,"j":2,"width_b":30,"depth_h":40,"material":"松木"}],
             "supports":[{"node":1,"type":"fixed"},{"node":2,"type":"fixed"}],
             "loads":[{"type":"area","total_N":-600}]}}
        """.trimIndent()

        assertThrows(AiParseException::class.java) { AiParser.parseFrame(json) }
    }

    @Test
    fun `荷载类型判定规则写入建模提示词`() {
        val auto = ChatSession.FRAME_AUTO_SYSTEM_PROMPT
        assertTrue("自动判别提示词应包含 area 载荷类型", auto.contains("\"area\""))
        assertTrue("应有作用宽度阈值 0.1L", auto.contains("0.1L"))
        assertTrue("应有作用宽度阈值 0.3L", auto.contains("0.3L"))
        assertTrue("应说明面状构件按面荷载分配", auto.contains("面状构件"))

        val three = ChatSession.FRAME3D_SYSTEM_PROMPT
        assertTrue(three.contains("\"type\":\"area\""))
        assertTrue(three.contains("0.3L"))
        assertTrue("三维提示词应覆盖座椅场景", three.contains("座椅"))
    }

    @Test
    fun `parseFrame 带代码围栏与前后缀闲聊`() {
        val content = """
            好的，我还需要了解一些信息：
            ```json
            {"ready":false,"questions":["结构用什么材料？"],"model":null}
            ```
            请补充后我再建模
        """.trimIndent()
        val result = AiParser.parseFrame(content)

        assertEquals(false, result.ready)
        assertEquals(listOf("结构用什么材料？"), result.questions)
        assertEquals(null, result.model)
    }

    @Test
    fun `parseFrame 非JSON抛异常`() {
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame("完全不是 JSON 的回复")
        }
    }

    @Test
    fun `parseFrame ready为true但缺model抛异常`() {
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame("""{"ready":true,"questions":[],"model":null}""")
        }
    }

    @Test
    fun `parseFrame 字段结构不符抛异常`() {
        // nodes 不是数组 → 反序列化失败
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame("""{"ready":true,"questions":[],"model":{"name":"x","nodes":123}}""")
        }
    }

    // ------------------------------------------------------------------
    // 几何归一化（normalizeFrameGeometry）：共面投影 / 重复节点合并 / 零长杆剔除 / 尺寸量级
    // ------------------------------------------------------------------

    @Test
    fun `parseFrame 平面模型节点z不一致自动投影共面`() {
        val json = """{"ready":true,"questions":[],"model":{"name":"t","dim":2,
            "nodes":[{"id":1,"x":0,"y":0,"z":10},{"id":2,"x":800,"y":0,"z":20},
                     {"id":3,"x":0,"y":1500,"z":10}],
            "members":[{"id":1,"i":1,"j":2,"width_b":30,"depth_h":40,"material":"松木"},
                       {"id":2,"i":1,"j":3,"width_b":30,"depth_h":40,"material":"松木"}],
            "supports":[{"node":1,"type":"pinned"},{"node":2,"type":"roller"}],
            "loads":[{"type":"force","node":3,"fy":-100}]}}"""
        val result = AiParser.parseFrame(json)
        val zs = result.model!!.nodes.map { it.z }.distinct()
        assertEquals("投影后所有节点 z 应相同", 1, zs.size)
        assertTrue("answer 应说明已投影", result.answer.contains("投影"))
    }

    @Test
    fun `parseFrame 重复节点自动合并且杆件支座载荷重连`() {
        // 节点6 与节点1 距离 1mm（<5mm），杆件2 连 6-3、支座在 6 → 合并后 6→1
        val json = """{"ready":true,"questions":[],"model":{"name":"t","dim":2,
            "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":800,"y":0},
                     {"id":3,"x":400,"y":1500},{"id":6,"x":1,"y":0}],
            "members":[{"id":1,"i":1,"j":2,"width_b":30,"depth_h":40,"material":"松木"},
                       {"id":2,"i":6,"j":3,"width_b":30,"depth_h":40,"material":"松木"}],
            "supports":[{"node":6,"type":"pinned"},{"node":2,"type":"roller"}],
            "loads":[{"type":"force","node":6,"fy":-100}]}}"""
        val result = AiParser.parseFrame(json)
        val model = result.model!!
        assertTrue("节点6 应被合并掉", model.nodes.none { it.id == 6 })
        assertEquals("合并后剩 3 个节点", 3, model.nodes.size)
        assertEquals("杆件2 的 i 应重连到 1", 1, model.members.first { it.id == 2 }.i)
        assertEquals("支座应重连到节点 1", 1, model.supports.first { it.type == "pinned" }.node)
        assertEquals("集中力应重连到节点 1", 1, model.loads.first { it.type == "force" }.node)
        assertTrue("answer 应说明合并", result.answer.contains("合并"))
    }

    @Test
    fun `parseFrame 合并后零长杆自动剔除`() {
        // 节点1、2 距离 1mm → 合并为 1；杆件2 连 1-2 合并后退化为 1-1 零长杆，应剔除
        val json = """{"ready":true,"questions":[],"model":{"name":"t","dim":2,
            "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":1,"y":0},{"id":3,"x":400,"y":1500}],
            "members":[{"id":1,"i":1,"j":3,"width_b":30,"depth_h":40,"material":"松木"},
                       {"id":2,"i":1,"j":2,"width_b":30,"depth_h":40,"material":"松木"}],
            "supports":[{"node":1,"type":"pinned"},{"node":3,"type":"roller"}],
            "loads":[{"type":"force","node":3,"fy":-100}]}}"""
        val result = AiParser.parseFrame(json)
        val model = result.model!!
        assertTrue("零长杆 2 应被剔除", model.members.none { it.id == 2 })
        assertTrue("answer 应说明剔除零长度杆", result.answer.contains("零长度杆"))
    }

    @Test
    fun `parseFrame 坐标数量级异常抛G04语义错误`() {
        // x=200000 mm = 200m，远超常规结构尺度，疑似 m/mm 混淆
        val json = """{"ready":true,"questions":[],"model":{"name":"t","dim":2,
            "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":200000,"y":0}],
            "members":[{"id":1,"i":1,"j":2,"width_b":30,"depth_h":40,"material":"松木"}],
            "supports":[{"node":1,"type":"pinned"},{"node":2,"type":"roller"}],
            "loads":[{"type":"force","node":2,"fy":-100}]}}"""
        val e = assertThrows(AiSemanticException::class.java) { AiParser.parseFrame(json) }
        assertTrue("应贴 [G-04] 标签", e.message!!.contains("[G-04]"))
        assertTrue("应提示数量级异常", e.message!!.contains("数量级"))
    }

    @Test
    fun `parseFrame 合法模型不触发几何修复`() {
        val result = AiParser.parseFrame("""{"ready":true,"questions":[],"model":$frameModelJson}""")
        assertTrue(result.ready)
        assertEquals("无修复时 answer 应为空", "", result.answer)
        assertEquals(5, result.model!!.nodes.size)
    }

    // ------------------------------------------------------------------
    // 自动补缺失约束（autoCompleteSupports）：支座级平动缺失自动补，内部机构不补
    // ------------------------------------------------------------------

    @Test
    fun `parseFrame 平面全roller支座自动升级为pinned补齐水平约束`() {
        // 两个 roller 都只约束竖向 uy，水平 ux 缺失 → 结构可整体水平飘移，
        // autoComplete 应把第一个 roller（节点1）升级为 pinned 补齐 ux
        val json = """{"ready":true,"questions":[],"model":{"name":"t","dim":2,
            "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":800,"y":0}],
            "members":[{"id":1,"i":1,"j":2,"width_b":30,"depth_h":40,"material":"松木"}],
            "supports":[{"node":1,"type":"roller"},{"node":2,"type":"roller"}],
            "loads":[{"type":"force","node":2,"fy":-100}]}}"""
        val result = AiParser.parseFrame(json)
        val sups = result.model!!.supports
        assertEquals("支座数量不变", 2, sups.size)
        assertEquals("节点1 的 roller 应升级为 pinned", "pinned", sups.first { it.node == 1 }.type)
        assertEquals("节点2 保持 roller", "roller", sups.first { it.node == 2 }.type)
        assertTrue("answer 应说明自动补约束", result.answer.contains("pinned") && result.answer.contains("补齐"))
    }

    @Test
    fun `parseFrame 三维全roller支座自动升级为pinned补齐平动`() {
        // 三维 roller 只约束 uz，两个 roller 缺 ux/uy → 升级第一个为 pinned（补齐 ux/uy/uz）
        val json = """{"ready":true,"questions":[],"model":{"name":"t","dim":3,
            "nodes":[{"id":1,"x":0,"y":0,"z":0},{"id":2,"x":800,"y":0,"z":0}],
            "members":[{"id":1,"i":1,"j":2,"width_b":30,"depth_h":40,"material":"松木"}],
            "supports":[{"node":1,"type":"roller"},{"node":2,"type":"roller"}],
            "loads":[{"type":"force","node":2,"fz":-100}]}}"""
        val result = AiParser.parseFrame(json)
        val sups = result.model!!.supports
        assertEquals("节点1 的 roller 应升级为 pinned", "pinned", sups.first { it.node == 1 }.type)
        assertTrue("answer 应说明自动补约束", result.answer.contains("pinned"))
    }

    @Test
    fun `parseFrame 内部机构不自动补约束`() {
        // 平动约束本就完备（pinned+roller），但结构是缺斜撑的四边形 → 内部机构。
        // autoComplete 检测到平动无缺失，应原样返回、不动支座，由 Python 特征值分析兜底报错。
        val json = """{"ready":true,"questions":[],"model":{"name":"t","dim":2,
            "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":800,"y":0},
                     {"id":3,"x":800,"y":1000},{"id":4,"x":0,"y":1000}],
            "members":[{"id":1,"i":1,"j":2,"width_b":30,"depth_h":40,"material":"松木"},
                       {"id":2,"i":2,"j":3,"width_b":30,"depth_h":40,"material":"松木"},
                       {"id":3,"i":3,"j":4,"width_b":30,"depth_h":40,"material":"松木"},
                       {"id":4,"i":4,"j":1,"width_b":30,"depth_h":40,"material":"松木"}],
            "supports":[{"node":1,"type":"pinned"},{"node":2,"type":"roller"}],
            "loads":[{"type":"force","node":3,"fy":-100}]}}"""
        val result = AiParser.parseFrame(json)
        val sups = result.model!!.supports.sortedBy { it.node }
        assertEquals("支座不应被改动", listOf("pinned", "roller"), sups.map { it.type })
        assertEquals("内部机构不由 autoComplete 修复，answer 应为空", "", result.answer)
    }

    @Test
    fun `parseFrame 已稳定模型支座不被自动改动`() {
        // pinned+roller 平动已完备 → autoComplete 直接返回原模型，answer 为空
        val json = """{"ready":true,"questions":[],"model":{"name":"t","dim":2,
            "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":800,"y":0},{"id":3,"x":400,"y":1000}],
            "members":[{"id":1,"i":1,"j":2,"width_b":30,"depth_h":40,"material":"松木"},
                       {"id":2,"i":1,"j":3,"width_b":30,"depth_h":40,"material":"松木"},
                       {"id":3,"i":2,"j":3,"width_b":30,"depth_h":40,"material":"松木"}],
            "supports":[{"node":1,"type":"pinned"},{"node":2,"type":"roller"}],
            "loads":[{"type":"force","node":3,"fy":-100}]}}"""
        val result = AiParser.parseFrame(json)
        assertEquals("answer 为空（无修复）", "", result.answer)
        val sups = result.model!!.supports.sortedBy { it.node }
        assertEquals("已稳定模型支座保持不变", listOf("pinned", "roller"), sups.map { it.type })
    }

    // ------------------------------------------------------------------
    // 可选字段 observation 向后兼容（带图建模时的几何观测，不展示给用户）
    // ------------------------------------------------------------------

    @Test
    fun `parseFrame 带 observation 字段的JSON解析成功不报错`() {
        // observation 是可选字段：带图建模时 AI 先写几何观测再建模型。
        // 向后兼容要求：出现该字段时 parseFrame 不得报错，且模型照常解析；
        // 旧 JSON（无 observation）也照常解析（字段带默认值）。
        val json = """{"ready":true,"questions":[],"answer":"",
            "observation":"两张照片一致：4 根立杆、2 层搁板，跨度约 800mm、层高 1500mm",
            "model":$frameModelJson}"""
        val result = AiParser.parseFrame(json)
        assertTrue("带 observation 字段应解析成功", result.ready)
        assertEquals("observation 不影响模型解析", 5, result.model!!.nodes.size)
        assertEquals("observation 原样透传",
            "两张照片一致：4 根立杆、2 层搁板，跨度约 800mm、层高 1500mm",
            result.observation)
    }

    // ------------------------------------------------------------------
    // 流体模型（kind="flow"）
    // ------------------------------------------------------------------

    private val flowJson = """
        {"name":"砖墙","kind":"wind_wall",
         "wall":{"height_m":2.5,"length_m":4.0,"thickness_m":0.24},
         "beaufort":10,"material":"砖砌体"}
    """.trimIndent()

    @Test
    fun `parseFrame 识别流体模型 kind=flow`() {
        val result = AiParser.parseFrame(
            """{"ready":true,"questions":[],"model":null,"kind":"flow","flow":$flowJson}"""
        )
        assertTrue(result.ready)
        assertEquals("flow", result.kind)
        assertEquals(null, result.model)
        val flow = result.flow!!
        assertEquals(2.5, flow.wall.height_m, 1e-9)
        assertEquals(10, flow.beaufort)
        assertEquals("砖砌体", flow.material)
    }

    @Test
    fun `parseFrame 流体模型缺风速抛异常`() {
        val noWind = """{"name":"砖墙","kind":"wind_wall",
            "wall":{"height_m":2.5,"length_m":4.0,"thickness_m":0.24},"material":"砖砌体"}"""
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"flow","flow":$noWind}"""
            )
        }
    }

    @Test
    fun `parseFrame 流体模型墙厚用毫米时提示换算`() {
        val mm = """{"name":"砖墙","kind":"wind_wall",
            "wall":{"height_m":2.5,"length_m":4.0,"thickness_m":240},"beaufort":10,"material":"砖砌体"}"""
        val e = assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"flow","flow":$mm}"""
            )
        }
        assertTrue(e.message!!.contains("0.24"))
    }

    @Test
    fun `parseFrame 流体模型材料缺失抛异常`() {
        val noMat = """{"name":"砖墙","kind":"wind_wall",
            "wall":{"height_m":2.5,"length_m":4.0,"thickness_m":0.24},"beaufort":10,"material":""}"""
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"flow","flow":$noMat}"""
            )
        }
    }

    @Test
    fun `parseFrame 不支持的流体场景抛异常`() {
        val bad = """{"name":"大坝","kind":"dam_overflow",
            "wall":{"height_m":2.5,"length_m":4.0,"thickness_m":0.24},"beaufort":10,"material":"混凝土"}"""
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"flow","flow":$bad}"""
            )
        }
    }

    @Test
    fun `parseFrame flow 缺少 flow 字段抛异常`() {
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame("""{"ready":true,"questions":[],"model":null,"kind":"flow","flow":null}""")
        }
    }

    // ------------------------------------------------------------------
    // 渗流模型（kind="seepage"）
    // ------------------------------------------------------------------

    private val seepageJson = """
        {"name":"混凝土重力坝","kind":"dam_seepage",
         "dam":{"height_m":12,"crest_width_m":5,"upstream_slope":0,"downstream_slope":0.75},
         "upstream_level_m":11,"downstream_level_m":1,"material":"混凝土C20",
         "foundation_permeability_m_s":1e-5,"foundation_depth_m":18,"friction_coeff":0.6}
    """.trimIndent()

    @Test
    fun `parseFrame 识别渗流模型 kind=seepage`() {
        val result = AiParser.parseFrame(
            """{"ready":true,"questions":[],"model":null,"kind":"seepage","seepage":$seepageJson}"""
        )
        assertTrue(result.ready)
        assertEquals("seepage", result.kind)
        assertEquals(null, result.model)
        val s = result.seepage!!
        assertEquals("dam_seepage", s.kind)
        assertEquals(12.0, s.dam.height_m, 1e-9)
        assertEquals(11.0, s.upstream_level_m, 1e-9)
        assertEquals(1e-5, s.foundation_permeability_m_s!!, 1e-15)
        assertEquals("混凝土C20", s.material)
    }

    @Test
    fun `parseFrame 渗流模型缺 seepage 字段抛异常`() {
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame("""{"ready":true,"questions":[],"model":null,"kind":"seepage","seepage":null}""")
        }
    }

    @Test
    fun `parseFrame 渗流模型材料缺失抛异常`() {
        val noMat = """{"name":"大坝","kind":"dam_seepage",
            "dam":{"height_m":12,"crest_width_m":5},"upstream_level_m":11,"material":""}"""
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"seepage","seepage":$noMat}"""
            )
        }
    }

    @Test
    fun `parseFrame 坝高用毫米时提示超出范围`() {
        val mm = """{"name":"大坝","kind":"dam_seepage",
            "dam":{"height_m":12000,"crest_width_m":5},"upstream_level_m":11,"material":"混凝土C20"}"""
        val e = assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"seepage","seepage":$mm}"""
            )
        }
        assertTrue(e.message!!.contains("坝高"))
    }

    @Test
    fun `parseFrame 上游水位超过坝高抛异常`() {
        val high = """{"name":"大坝","kind":"dam_seepage",
            "dam":{"height_m":12,"crest_width_m":5},"upstream_level_m":14,"material":"混凝土C20"}"""
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"seepage","seepage":$high}"""
            )
        }
    }

    @Test
    fun `parseFrame 下游水位不低于上游抛异常`() {
        val bad = """{"name":"大坝","kind":"dam_seepage",
            "dam":{"height_m":12,"crest_width_m":5},"upstream_level_m":8,
            "downstream_level_m":9,"material":"混凝土C20"}"""
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"seepage","seepage":$bad}"""
            )
        }
    }

    @Test
    fun `parseFrame 不支持的渗流场景抛异常`() {
        val bad = """{"name":"大坝","kind":"dam_overflow",
            "dam":{"height_m":12,"crest_width_m":5},"upstream_level_m":11,"material":"混凝土C20"}"""
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"seepage","seepage":$bad}"""
            )
        }
    }

    @Test
    fun `parseFrame 坝基渗透系数越界抛异常`() {
        val bad = """{"name":"大坝","kind":"dam_seepage",
            "dam":{"height_m":12,"crest_width_m":5},"upstream_level_m":11,
            "material":"混凝土C20","foundation_permeability_m_s":1.0}"""
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"seepage","seepage":$bad}"""
            )
        }
    }

    // ------------------------------------------------------------------
    // 三维实体模型（kind="solid"）
    // ------------------------------------------------------------------

    private val solidJson = """
        {"name":"砖墙三维抗风","kind":"solid_wall",
         "wall":{"length_m":4.0,"height_m":3.0,"thickness_m":0.24},
         "beaufort":10,"material":"砖砌体"}
    """.trimIndent()

    @Test
    fun `parseFrame 识别三维实体模型 kind=solid`() {
        val result = AiParser.parseFrame(
            """{"ready":true,"questions":[],"model":null,"kind":"solid","solid":$solidJson}"""
        )
        assertTrue(result.ready)
        assertEquals("solid", result.kind)
        assertNull(result.model)
        val s = result.solid!!
        assertEquals("solid_wall", s.kind)
        assertEquals(4.0, s.wall.length_m, 1e-9)
        assertEquals(3.0, s.wall.height_m, 1e-9)
        assertEquals(10, s.beaufort)
        assertEquals("砖砌体", s.material)
    }

    @Test
    fun `parseFrame 实体模型缺风压抛异常`() {
        val noWind = """{"name":"砖墙","kind":"solid_wall",
            "wall":{"length_m":4.0,"height_m":3.0,"thickness_m":0.24},"material":"砖砌体"}"""
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"solid","solid":$noWind}"""
            )
        }
    }

    @Test
    fun `parseFrame 实体模型墙厚用毫米时提示换算`() {
        val mm = """{"name":"砖墙","kind":"solid_wall",
            "wall":{"length_m":4.0,"height_m":3.0,"thickness_m":240},"beaufort":10,"material":"砖砌体"}"""
        val e = assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"solid","solid":$mm}"""
            )
        }
        assertTrue(e.message!!.contains("0.24"))
    }

    @Test
    fun `parseFrame 实体模型材料缺失抛异常`() {
        val noMat = """{"name":"砖墙","kind":"solid_wall",
            "wall":{"length_m":4.0,"height_m":3.0,"thickness_m":0.24},"beaufort":10,"material":""}"""
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"solid","solid":$noMat}"""
            )
        }
    }

    @Test
    fun `parseFrame 实体模型缺 solid 字段抛异常`() {
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame("""{"ready":true,"questions":[],"model":null,"kind":"solid","solid":null}""")
        }
    }

    @Test
    fun `parseFrame 实体模型直接给风压与网格分段`() {
        val json = """{"name":"混凝土墙","kind":"solid_wall",
            "wall":{"length_m":6.0,"height_m":4.0,"thickness_m":0.3},
            "pressure_Pa":900,"cp_net":1.3,"mesh_level":"coarse","material":"混凝土C20"}"""
        val s = AiParser.parseFrame(
            """{"ready":true,"questions":[],"model":null,"kind":"solid","solid":$json}"""
        ).solid!!
        assertEquals(900.0, s.pressure_Pa!!, 1e-9)
        assertEquals(1.3, s.cp_net!!, 1e-9)
        assertEquals("coarse", s.mesh_level)
    }

    @Test
    fun `parseFrame 不支持的实体场景抛异常`() {
        val bad = """{"name":"梁","kind":"beam3d",
            "wall":{"length_m":4.0,"height_m":3.0,"thickness_m":0.24},"beaufort":10,"material":"砖砌体"}"""
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"solid","solid":$bad}"""
            )
        }
    }

    @Test
    fun `validateFrameModel 覆盖各校验分支`() {
        fun model(
            nodes: List<FrameNode> = listOf(FrameNode(1, 0.0, 0.0), FrameNode(2, 400.0, 0.0)),
            members: List<FrameMember> = listOf(FrameMember(1, 1, 2, 40.0, 18.0, "松木")),
            supports: List<FrameSupport> = listOf(FrameSupport(1, "fixed")),
            loads: List<FrameLoad> = listOf(FrameLoad("force", node = 2, fy = -294.0))
        ) = FrameModel("t", nodes, members, supports, loads)

        // 合法模型不抛
        AiParser.validateFrameModel(model())

        // 节点不足 2 个
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(model(nodes = listOf(FrameNode(1, 0.0, 0.0))))
        }
        // 无杆件
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(model(members = emptyList()))
        }
        // 无支座
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(model(supports = emptyList()))
        }
        // 无载荷
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(model(loads = emptyList()))
        }
        // 节点编号重复
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(model(nodes = listOf(FrameNode(1, 0.0, 0.0), FrameNode(1, 100.0, 0.0))))
        }
        // 杆件引用不存在的节点
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(model(members = listOf(FrameMember(1, 1, 9, 40.0, 18.0, "松木"))))
        }
        // 杆件两端同节点（零长度）
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(model(members = listOf(FrameMember(1, 1, 1, 40.0, 18.0, "松木"))))
        }
        // 截面尺寸非正
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(model(members = listOf(FrameMember(1, 1, 2, 0.0, 18.0, "松木"))))
        }
        // 材料为空
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(model(members = listOf(FrameMember(1, 1, 2, 40.0, 18.0, " "))))
        }
        // 支座引用不存在的节点
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(model(supports = listOf(FrameSupport(9, "fixed"))))
        }
        // 集中力作用在不存在的节点
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(model(loads = listOf(FrameLoad("force", node = 9, fy = -1.0))))
        }
        // 均布载荷作用在不存在的杆件
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(model(loads = listOf(FrameLoad("udl", member = 9, qy = -0.5))))
        }
        // 不支持的载荷类型
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(model(loads = listOf(FrameLoad("torque", node = 2))))
        }
        // 壁厚为负
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(
                model(members = listOf(FrameMember(1, 1, 2, 40.0, 40.0, "低碳钢", wall_t = -1.0)))
            )
        }
        // 壁厚过大（≥ 短边的一半）
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(
                model(members = listOf(FrameMember(1, 1, 2, 40.0, 40.0, "低碳钢", wall_t = 20.0)))
            )
        }
        // 合法空心方管不抛
        AiParser.validateFrameModel(
            model(members = listOf(FrameMember(1, 1, 2, 40.0, 40.0, "低碳钢", wall_t = 2.0)))
        )
    }

    @Test
    fun `parseFrame 解析空心截面壁厚 wall_t`() {
        val json = """{"ready":true,"questions":[],"model":{
            "name":"方管悬臂",
            "nodes":[{"id":1,"x":0,"y":0,"z":0},{"id":2,"x":600,"y":0,"z":0}],
            "members":[{"id":1,"i":1,"j":2,"width_b":40,"depth_h":40,"wall_t":2,
                        "material":"低碳钢"}],
            "supports":[{"node":1,"type":"fixed"}],
            "loads":[{"type":"force","node":2,"fz":-49}],"dim":3}}"""

        val model = AiParser.parseFrame(json).model!!

        assertEquals(2.0, model.members[0].wall_t, 1e-9)
        assertEquals("低碳钢", model.members[0].material)
        // 省略 wall_t 的旧模型解析为实心（0.0），零回归
        val solid = AiParser.parseFrame("""{"ready":true,"questions":[],"model":$frameModelJson}""")
        assertEquals(0.0, solid.model!!.members[0].wall_t, 1e-9)
    }

    // ------------------------------------------------------------------
    // 时间历程（kind="transient"）
    // ------------------------------------------------------------------

    private val transientJson = """
        {"name":"悬臂杆温度循环","frame":$frameModelJson,
         "duration_s":7200,"steps":12,"temp_base_c":20,"temp_peak_c":80,
         "profile":"triangle","members":[1,2]}
    """.trimIndent()

    @Test
    fun `parseFrame 识别时间历程 kind=transient`() {
        val result = AiParser.parseFrame(
            """{"ready":true,"questions":[],"model":null,"kind":"transient","transient":$transientJson}"""
        )
        assertTrue(result.ready)
        assertEquals("transient", result.kind)
        assertEquals(null, result.model)
        val t = result.transient!!
        assertEquals(7200.0, t.duration_s, 1e-9)
        assertEquals(12, t.steps)
        assertEquals(20.0, t.temp_base_c, 1e-9)
        assertEquals(80.0, t.temp_peak_c, 1e-9)
        assertEquals("triangle", t.profile)
        assertEquals(listOf(1, 2), t.members)
        // 恒载随 frame 一起带入
        assertEquals(2, t.frame.loads.size)
    }

    @Test
    fun `parseFrame 时间历程缺 transient 字段抛异常`() {
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"transient","transient":null}"""
            )
        }
    }

    @Test
    fun `parseFrame 时间历程步数越界提示 5~40`() {
        val bad = """{"name":"温度循环","frame":$frameModelJson,
            "duration_s":7200,"steps":50,"temp_base_c":20,"temp_peak_c":80,"profile":"triangle"}"""
        val e = assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"transient","transient":$bad}"""
            )
        }
        assertTrue(e.message!!.contains("5~40"))
    }

    @Test
    fun `parseFrame 时间历程缺恒定机械载荷抛异常`() {
        val onlyThermal = """
            {"name":"两端固定杆","nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":800,"y":0}],
             "members":[{"id":1,"i":1,"j":2,"width_b":40,"depth_h":60,"material":"铝合金"}],
             "supports":[{"node":1,"type":"fixed"},{"node":2,"type":"fixed"}],
             "loads":[{"type":"thermal","member":1,"delta_temp":40}]}
        """.trimIndent()
        val bad = """{"name":"温度循环","frame":$onlyThermal,
            "duration_s":3600,"steps":12,"temp_base_c":20,"temp_peak_c":80,"profile":"sine"}"""
        val e = assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"transient","transient":$bad}"""
            )
        }
        assertTrue(e.message!!.contains("恒定机械载荷"))
    }

    @Test
    fun `parseFrame 时间历程接受仅含面荷载的恒载`() {
        val areaOnly = """
            {"name":"双梁搁板","nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":600,"y":0},
                      {"id":3,"x":0,"y":300},{"id":4,"x":600,"y":300}],
             "members":[{"id":1,"i":1,"j":2,"width_b":40,"depth_h":60,"material":"松木"},
                        {"id":2,"i":3,"j":4,"width_b":40,"depth_h":60,"material":"松木"}],
             "supports":[{"node":1,"type":"fixed"},{"node":2,"type":"fixed"},
                         {"node":3,"type":"fixed"},{"node":4,"type":"fixed"}],
             "loads":[{"type":"area","members":[1,2],"total_N":-600}]}
        """.trimIndent()
        val bad = """{"name":"搁板温度循环","frame":$areaOnly,
            "duration_s":3600,"steps":12,"temp_base_c":20,"temp_peak_c":80,
            "profile":"sine","members":[1,2]}"""
        val result = AiParser.parseFrame(
            """{"ready":true,"questions":[],"model":null,"kind":"transient","transient":$bad}"""
        )
        // 面荷载属于恒定机械载荷，不能被"缺机械载荷"误拒
        assertEquals("area", result.transient!!.frame.loads.single().type)
    }

    @Test
    fun `parseFrame 时间历程杆件编号不存在抛异常`() {
        val bad = """{"name":"温度循环","frame":$frameModelJson,
            "duration_s":7200,"steps":12,"temp_base_c":20,"temp_peak_c":80,
            "profile":"triangle","members":[99]}"""
        val e = assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"questions":[],"model":null,"kind":"transient","transient":$bad}"""
            )
        }
        assertTrue(e.message!!.contains("杆件 99"))
    }

    @Test
    fun `parseFrameWithRetry 首次失败重试成功`() = runTest {
        val good = """{"ready":true,"questions":[],"model":$frameModelJson}"""
        val client = FakeAiClient(listOf("抱歉，我说不清楚。", good))

        val result = AiParser.parseFrameWithRetry(client, listOf(LlmMessage("user", "帮我建模一个书架")))

        assertTrue(result.ready)
        assertEquals("两层书架", result.model!!.name)
        assertEquals(2, client.calls.size)
        // 第二次请求追加了错误提示
        val retryLast = client.calls[1].last()
        assertTrue(retryLast.content.contains("无法解析"))
    }

    @Test
    fun `parseFrameWithRetry 校验失败重试成功`() = runTest {
        // 首次：ready=true 但缺支座（引用完整性校验失败）→ 重试
        val bad = """{"ready":true,"questions":[],"model":{
            "name":"飘","nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":400,"y":0}],
            "members":[{"id":1,"i":1,"j":2,"width_b":40,"depth_h":18,"material":"松木"}],
            "supports":[],"loads":[{"type":"force","node":2,"fy":-294}]}}"""
        val client = FakeAiClient(listOf(bad, """{"ready":true,"questions":[],"model":$frameModelJson}"""))

        val result = AiParser.parseFrameWithRetry(client, listOf(LlmMessage("user", "问题")))

        assertTrue(result.ready)
        assertEquals(2, result.model!!.supports.size)
        assertEquals(2, client.calls.size)
        assertTrue(client.calls[1].last().content.contains("缺少支座"))
    }

    @Test
    fun `parseFrameWithRetry 数量级异常被拦截后语义重试成功`() = runTest {
        // 首次：坐标数量级异常（x=200000mm=200m，疑似 m/mm 混淆）→ normalize 不修数量级，
        // validateFrameModel 抛 [G-04] 语义错误 → 语义重试（注意：支座级平动缺失会被
        // autoCompleteSupports 自动补齐，不再走重试；这里用 autoComplete 覆盖不到的数量级错误触发）
        val bad = """{"ready":true,"questions":[],"model":{
            "name":"t","dim":2,
            "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":200000,"y":0}],
            "members":[{"id":1,"i":1,"j":2,"width_b":30,"depth_h":40,"material":"松木"}],
            "supports":[{"node":1,"type":"pinned"},{"node":2,"type":"roller"}],
            "loads":[{"type":"force","node":2,"fy":-100}]}}"""
        val client = FakeAiClient(listOf(bad, """{"ready":true,"questions":[],"model":$frameModelJson}"""))

        val result = AiParser.parseFrameWithRetry(client, listOf(LlmMessage("user", "建模")))

        assertTrue(result.ready)
        assertEquals("两层书架", result.model!!.name)
        assertEquals(2, client.calls.size)
        // 重试消息应携带确定性的数量级错误信息，指导 AI 定点修正坐标
        assertTrue(client.calls[1].last().content.contains("数量级"))
    }

    @Test
    fun `parseFrameWithRetry 自动判别失败时提示可改 dim=3`() = runTest {
        val good = """{"ready":true,"questions":[],"model":$frameModelJson}"""
        val client = FakeAiClient(listOf("不是 JSON", good))

        val result = AiParser.parseFrameWithRetry(
            client, listOf(LlmMessage("user", "四腿桌子")), autoDim = true
        )

        assertTrue(result.ready)
        assertTrue(client.calls[1].last().content.contains("dim 设为 3"))
    }

    @Test
    fun `自动判别提示词占位符全部替换且含三维约定`() {
        val prompt = ChatSession.FRAME_AUTO_SYSTEM_PROMPT

        // 材料库占位符由 ChatSession 在建模时替换，模板里只保留这一个占位符
        assertFalse("不应残留未替换的占位符", prompt.replace("{{AVAILABLE_MATERIALS}}", "").contains("{{"))
        assertTrue(prompt.contains("dim 填 3"))
        assertTrue(prompt.contains("z 竖直向上"))
        assertTrue(prompt.contains("wall_t"))
        assertTrue("必须约束载荷加在横梁跨中", prompt.contains("跨中"))
    }

    @Test
    fun `parseFrameWithRetry 两次失败最终抛异常`() = runTest {
        val client = FakeAiClient(listOf("bad1", "bad2"))
        val err = runCatching {
            AiParser.parseFrameWithRetry(client, listOf(LlmMessage("user", "问题")))
        }.exceptionOrNull()
        assertTrue("应抛出 AiParseException，实际是 $err", err is AiParseException)
        assertEquals(2, client.calls.size)
    }

    @Test
    fun `同一对节点上重复建杆件被判为解析失败`() {
        val dup = """{"ready":true,"questions":[],"model":{"name":"重复杆件",
            "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":600,"y":0}],
            "members":[{"id":1,"i":1,"j":2,"width_b":30,"depth_h":40,"material":"松木"},
            {"id":2,"i":2,"j":1,"width_b":30,"depth_h":60,"material":"松木"}],
            "supports":[{"node":1,"type":"fixed"}],
            "loads":[{"type":"force","node":2,"fy":-98}]}}"""
        val err = runCatching { AiParser.parseFrame(dup) }.exceptionOrNull()
        assertTrue("应因重复杆件报错，实际是 $err", err is AiParseException)
        assertTrue(
            "错误信息应说明是同一对节点重复建杆件",
            err?.message?.contains("同一对节点") == true
        )
    }

    @Test
    fun `强制三维实体模式下梁杆回复会重试并最终抛契约异常`() = runTest {
        val frameReply = """{"ready":true,"questions":[],"model":{"name":"钢圆柱",
            "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":0,"y":300}],
            "members":[{"id":1,"i":1,"j":2,"width_b":200,"depth_h":200,"material":"45号钢"}],
            "supports":[{"node":1,"type":"fixed"}],
            "loads":[{"type":"force","node":2,"fy":-50000}]}}"""
        val client = FakeAiClient(listOf(frameReply, frameReply))
        val err = runCatching {
            AiParser.parseFrameWithRetry(
                client, listOf(LlmMessage("user", "圆柱压缩")), forceSolidGeneric = true
            )
        }.exceptionOrNull()
        assertTrue("应抛 AiContractException，实际是 $err", err is AiContractException)
        assertEquals(2, client.calls.size)
        assertTrue(
            "重试提示应要求 solid_generic 契约",
            client.calls[1].last().content.contains("solid_generic")
        )
    }

    @Test
    fun `强制三维实体模式接受 solid_generic 契约`() = runTest {
        val ok = """{"ready":true,"kind":"solid_generic","questions":[],"model":null,
            "solid_generic":{"name":"圆柱压缩","kind":"solid_generic",
            "geometry":{"type":"cylinder","radius_m":0.1,"height_m":0.3},
            "material":"45号钢",
            "supports":[{"type":"plane","axis":"y","value":0,"dofs":[0,1,2]}],
            "loads":[{"type":"pressure","face":{"axis":"y","value":0.3},"magnitude":1591549}]}}"""
        val client = FakeAiClient(listOf(ok))
        val result = AiParser.parseFrameWithRetry(
            client, listOf(LlmMessage("user", "圆柱压缩")), forceSolidGeneric = true
        )
        assertEquals("solid_generic", result.kind.trim().lowercase())
        assertEquals(1, client.calls.size)
    }


    // ------------------------------------------------------------------
    // 三维实体挖孔（geometry.holes）解码前预检（B5）：
    // AppJson 的 coerceInputValues 会把非法类型静默兜底成默认值（center 字符串→0.0、
    // radius 字符串→0.0），必须在解码前拦截，非法抛 AiSemanticException 触发定点重试。
    // ------------------------------------------------------------------

    private fun solidGenericPlateWithHole(holeJson: String): String = """
        {"ready":true,"kind":"solid_generic","questions":[],"model":null,
         "solid_generic":{"name":"带孔板","kind":"solid_generic",
           "geometry":{"type":"plate","length_m":1.0,"width_m":0.5,"thickness_m":0.02,
             "holes":[$holeJson]},
           "material":"钢",
           "supports":[{"type":"plane","axis":"x","value":0.0,"dofs":[0,1,2]}],
           "loads":[{"type":"pressure","face":{"axis":"x","value":1.0},"magnitude":20000000}]}}
    """.trimIndent()

    @Test
    fun `parseFrame solid_generic 合法贯穿圆孔解析成功且保留`() {
        val res = AiParser.parseFrame(
            solidGenericPlateWithHole("""{"axis":"z","center":[0.5,0.25],"radius":0.08}""")
        )
        val g = res.solid_generic!!.geometry
        assertEquals("plate", g.type)
        val holes = g.holes!!
        assertEquals(1, holes.size)
        assertEquals("z", holes[0].axis)
        assertEquals(listOf(0.5, 0.25), holes[0].center)
        assertEquals(0.08, holes[0].radius, 1e-9)
    }

    @Test
    fun `parseFrame solid_generic 缺 holes 的旧模型零回归`() {
        val json = """{"ready":true,"kind":"solid_generic","questions":[],"model":null,
            "solid_generic":{"name":"无孔板","kind":"solid_generic",
            "geometry":{"type":"plate","length_m":1.0,"width_m":0.5,"thickness_m":0.02},
            "material":"钢",
            "supports":[{"type":"plane","axis":"x","value":0.0,"dofs":[0,1,2]}],
            "loads":[{"type":"pressure","face":{"axis":"x","value":1.0},"magnitude":20000000}]}}"""
        assertNull(AiParser.parseFrame(json).solid_generic!!.geometry.holes)
    }

    @Test
    fun `parseFrame solid_generic 非法 axis 抛语义错误`() {
        val e = assertThrows(AiSemanticException::class.java) {
            AiParser.parseFrame(solidGenericPlateWithHole(
                """{"axis":"w","center":[0.5,0.25],"radius":0.05}"""))
        }
        assertTrue(e.message!!.contains("axis 必须是 x/y/z"))
    }

    @Test
    fun `parseFrame solid_generic center 混字符串抛语义错误`() {
        // 关键回归：coerceInputValues 会把 "abc" 静默兜底成 0.0，预检必须在解码前拦下
        val e = assertThrows(AiSemanticException::class.java) {
            AiParser.parseFrame(solidGenericPlateWithHole(
                """{"axis":"z","center":["abc",0.25],"radius":0.05}"""))
        }
        assertTrue(e.message!!.contains("center"))
        assertTrue(e.message!!.contains("数字"))
    }

    @Test
    fun `parseFrame solid_generic radius 非正抛语义错误`() {
        val e = assertThrows(AiSemanticException::class.java) {
            AiParser.parseFrame(solidGenericPlateWithHole(
                """{"axis":"z","center":[0.5,0.25],"radius":0}"""))
        }
        assertTrue(e.message!!.contains("radius"))
    }

    @Test
    fun `parseFrame solid_generic 孔越界抛语义错误`() {
        // center[0]=0.0 贴边：留 5% 余量后 lo=0.084 > 0 → 越界
        val e = assertThrows(AiSemanticException::class.java) {
            AiParser.parseFrame(solidGenericPlateWithHole(
                """{"axis":"z","center":[0.0,0.25],"radius":0.08}"""))
        }
        assertTrue(e.message!!.contains("center") && e.message!!.contains("超出范围"))
    }

    @Test
    fun `parseFrame solid_generic 圆柱带孔抛语义错误`() {
        val json = """{"ready":true,"kind":"solid_generic","questions":[],"model":null,
            "solid_generic":{"name":"带孔圆柱","kind":"solid_generic",
            "geometry":{"type":"cylinder","radius_m":0.1,"height_m":0.3,
              "holes":[{"axis":"y","center":[0.0,0.0],"radius":0.05}]},
            "material":"钢",
            "supports":[{"type":"plane","axis":"y","value":0.0,"dofs":[0,1,2]}],
            "loads":[{"type":"pressure","face":{"axis":"y","value":0.3},"magnitude":100000}]}}"""
        val e = assertThrows(AiSemanticException::class.java) { AiParser.parseFrame(json) }
        assertTrue(e.message!!.contains("不支持开孔"))
    }

    @Test
    fun `criticalMissing 只统计未提供且必填或无默认值的参数`() {
        val parse = AiParseResult(
            template_id = "cantilever_shelf",
            params = mapOf("load_kg" to JsonPrimitive(3)),
            missing_params = listOf("thickness_mm", "width_mm", "material_name"),
            questions = emptyList()
        )
        // thickness/width/material 都有默认值 → 不算关键缺失；load_kg 已提供
        assertEquals(emptyList<String>(), FollowUpLogic.criticalMissing(parse, templates))
    }

    @Test
    fun `selectQuestions 多于2个关键缺失时裁剪为2个`() {
        val spec = listOf(
            ParamSpec(key = "a", label = "甲", required = true),
            ParamSpec(key = "b", label = "乙", required = true),
            ParamSpec(key = "c", label = "丙", required = true),
            ParamSpec(key = "d", label = "丁", required = true),
            ParamSpec(key = "e", label = "戊", required = true)
        )
        val tpl = TemplateInfo(id = "t", name = "T", param_schema = spec)
        val parse = AiParseResult(
            template_id = "t",
            params = emptyMap(),
            missing_params = listOf("a", "b", "c", "d", "e"),
            questions = listOf("甲是多少？")
        )
        val questions = FollowUpLogic.selectQuestions(
            FollowUpLogic.criticalMissing(parse, listOf(tpl)),
            parse.questions,
            listOf(tpl)
        )
        assertEquals(FollowUpLogic.MAX_QUESTIONS_PER_ROUND, questions.size)
        assertEquals("甲是多少？", questions[0])                      // LLM 问题优先
        assertTrue(questions[1].contains("乙"))                       // 不足的自动生成
    }

    @Test
    fun `shouldAskAnotherRound 轮数与缺失控制`() {
        assertTrue(FollowUpLogic.shouldAskAnotherRound(0, listOf("a")))
        assertTrue(FollowUpLogic.shouldAskAnotherRound(1, listOf("a")))
        assertEquals(false, FollowUpLogic.shouldAskAnotherRound(2, listOf("a")))  // 超过最大轮数
        assertEquals(false, FollowUpLogic.shouldAskAnotherRound(0, emptyList()))  // 无关键缺失
    }

    // ------------------------------------------------------------------
    // 三维空间建模（P3D-2）：dim=3 / z 坐标 / fz 载荷 / 3D 支座
    // ------------------------------------------------------------------

    /** 三维四腿桌：4 腿 + 顶部四边连梁，桌面四角各 245N 向下 */
    private val frame3dModelJson = """
        {"name":"四腿书桌","dim":3,
         "nodes":[{"id":1,"x":-300,"y":-300,"z":0},{"id":2,"x":-300,"y":-300,"z":750},
                  {"id":3,"x":300,"y":-300,"z":0},{"id":4,"x":300,"y":-300,"z":750},
                  {"id":5,"x":300,"y":300,"z":0},{"id":6,"x":300,"y":300,"z":750},
                  {"id":7,"x":-300,"y":300,"z":0},{"id":8,"x":-300,"y":300,"z":750}],
         "members":[{"id":1,"i":1,"j":2,"width_b":40,"depth_h":40,"material":"松木"},
                    {"id":2,"i":3,"j":4,"width_b":40,"depth_h":40,"material":"松木"},
                    {"id":3,"i":5,"j":6,"width_b":40,"depth_h":40,"material":"松木"},
                    {"id":4,"i":7,"j":8,"width_b":40,"depth_h":40,"material":"松木"},
                    {"id":5,"i":2,"j":4,"width_b":40,"depth_h":80,"material":"松木"},
                    {"id":6,"i":4,"j":6,"width_b":40,"depth_h":80,"material":"松木"},
                    {"id":7,"i":6,"j":8,"width_b":40,"depth_h":80,"material":"松木"},
                    {"id":8,"i":8,"j":2,"width_b":40,"depth_h":80,"material":"松木"}],
         "supports":[{"node":1,"type":"fixed"},{"node":3,"type":"fixed"},
                     {"node":5,"type":"fixed"},{"node":7,"type":"fixed"}],
         "loads":[{"type":"force","node":2,"fz":-245},{"type":"force","node":4,"fz":-245},
                  {"type":"force","node":6,"fz":-245},{"type":"force","node":8,"fz":-245}]}
    """.trimIndent()

    @Test
    fun `parseFrame 三维四腿桌模型`() {
        val result = AiParser.parseFrame("""{"ready":true,"questions":[],"model":$frame3dModelJson}""")

        assertTrue(result.ready)
        val model = result.model!!
        assertEquals(3, model.dim)
        assertEquals(8, model.nodes.size)
        assertEquals(750.0, model.nodes[1].z, 1e-9)      // 桌腿顶端高度
        assertEquals(0.0, model.nodes[0].z, 1e-9)        // 桌腿落地
        assertEquals(-245.0, model.loads[0].fz, 1e-9)    // 竖向向下载荷
    }

    @Test
    fun `validateFrameModel 三维维度与支座校验`() {
        val nodes = listOf(
            FrameNode(1, 0.0, 0.0, 0.0), FrameNode(2, 0.0, 0.0, 750.0)
        )
        val members = listOf(FrameMember(1, 1, 2, 40.0, 40.0, "松木"))
        fun m(
            supports: List<FrameSupport> = listOf(FrameSupport(1, "fixed")),
            loads: List<FrameLoad> = listOf(FrameLoad("force", node = 2, fz = -98.0)),
            dim: Int = 3
        ) = FrameModel("t", nodes, members, supports, loads, dim)

        // 合法三维模型
        AiParser.validateFrameModel(m())
        // 三维专用滚轴支座合法（roller_x 与提供完备平动的 pinned 组合，不触发机构预检）
        AiParser.validateFrameModel(m(supports = listOf(FrameSupport(1, "pinned"), FrameSupport(2, "roller_x"))))
        // 三维 fix 额外约束合法
        AiParser.validateFrameModel(m(supports = listOf(FrameSupport(1, "pinned", listOf("rx")))))
        // dim 非法
        assertThrows(AiParseException::class.java) { AiParser.validateFrameModel(m(dim = 4)) }
        // 三维但支座类型非法
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(m(supports = listOf(FrameSupport(1, "welded"))))
        }
        // fix 自由度名非法
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(m(supports = listOf(FrameSupport(1, "fixed", listOf("qq")))))
        }
        // 平面模型不允许 fix 额外约束
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(
                FrameModel("t", nodes, members,
                    listOf(FrameSupport(1, "fixed", listOf("rx"))), emptyList(), 2)
            )
        }
        // 平面模型不允许 fz（面外）载荷
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(
                FrameModel("t", nodes, members,
                    listOf(FrameSupport(1, "fixed")),
                    listOf(FrameLoad("force", node = 2, fz = -98.0)), 2)
            )
        }
        // 平面模型不允许 qz（面外）均布载荷
        assertThrows(AiParseException::class.java) {
            AiParser.validateFrameModel(
                FrameModel("t", nodes, members,
                    listOf(FrameSupport(1, "fixed")),
                    listOf(FrameLoad("udl", member = 1, qz = -0.5)), 2)
            )
        }
        // 三维全滚轴支座 → 缺 ux/uy 平动约束，是机构，应被语义校验拦截（触发 AI 重修）
        assertThrows(AiSemanticException::class.java) {
            AiParser.validateFrameModel(m(supports = listOf(
                FrameSupport(1, "roller"), FrameSupport(2, "roller"))))
        }
        // 三维仅 roller_y → 缺 ux/uz，机构
        assertThrows(AiSemanticException::class.java) {
            AiParser.validateFrameModel(m(supports = listOf(FrameSupport(1, "roller_y"))))
        }
        // 平面全滚轴 → 缺 ux，机构
        assertThrows(AiSemanticException::class.java) {
            AiParser.validateFrameModel(
                FrameModel("t", nodes, members,
                    listOf(FrameSupport(1, "roller")),
                    listOf(FrameLoad("force", node = 2, fy = -98.0)), 2)
            )
        }
        // 平面一端 fixed 一端 roller（简支梁）→ 平动完备，通过
        AiParser.validateFrameModel(
            FrameModel("t", nodes, members,
                listOf(FrameSupport(1, "fixed"), FrameSupport(2, "roller")),
                listOf(FrameLoad("force", node = 2, fy = -98.0)), 2)
        )
    }
}

/** 测试用假 LLM 客户端：按顺序返回预设回复，记录全部请求 */
class FakeAiClient(private val responses: List<String>) : AiClient {
    val calls = mutableListOf<List<LlmMessage>>()
    val seedOverrides = mutableListOf<Int?>()

    override suspend fun complete(messages: List<LlmMessage>, jsonMode: Boolean, seedOverride: Int?, tag: String?): String {
        calls.add(messages)
        seedOverrides.add(seedOverride)
        return responses[calls.size - 1]
    }
}

/**
 * P0-1：通用三维实体几何-约束-载荷一致性校验。
 * 约束面/加载面必须落在实体外表面、点必须在包围盒内。
 */
class SolidGenericValidationTest {
    private fun validModel(
        supports: List<SolidGenericSupport> = listOf(
            SolidGenericSupport(type = "plane", axis = "x", value = 0.0)
        ),
        loads: List<SolidGenericLoad> = listOf(
            SolidGenericLoad(
                type = "pressure",
                face = SolidGenericFace(axis = "x", value = 1.0),
                magnitude = 1e5
            )
        ),
    ) = SolidGenericModel(
        geometry = SolidGenericGeometryInput(
            type = "box", length_m = 1.0, height_m = 0.3, thickness_m = 0.3
        ),
        material = "钢",
        supports = supports,
        loads = loads,
    )

    @Test
    fun `合法模型通过校验`() {
        AiParser.validateSolidGenericModel(validModel())
    }

    @Test
    fun `约束面不在边界上抛异常`() {
        val m = validModel(supports = listOf(
            SolidGenericSupport(type = "plane", axis = "x", value = 0.5)
        ))
        assertThrows(AiParseException::class.java) {
            AiParser.validateSolidGenericModel(m)
        }
    }

    @Test
    fun `压力加载面不在边界上抛异常`() {
        val m = validModel(loads = listOf(
            SolidGenericLoad(
                type = "pressure",
                face = SolidGenericFace(axis = "x", value = 0.5),
                magnitude = 1e5
            )
        ))
        assertThrows(AiParseException::class.java) {
            AiParser.validateSolidGenericModel(m)
        }
    }

    @Test
    fun `点约束在实体外抛异常`() {
        val m = validModel(supports = listOf(
            SolidGenericSupport(type = "point", xyz = listOf(5.0, 0.15, 0.15))
        ))
        assertThrows(AiParseException::class.java) {
            AiParser.validateSolidGenericModel(m)
        }
    }

    @Test
    fun `集中力作用点在实体外抛异常`() {
        val m = validModel(loads = listOf(
            SolidGenericLoad(
                type = "force",
                point = listOf(5.0, 0.15, 0.15),
                vector = listOf(0.0, -1000.0, 0.0)
            )
        ))
        assertThrows(AiParseException::class.java) {
            AiParser.validateSolidGenericModel(m)
        }
    }

    // ---------------------------------------------------------- point_at 语义锚点 + unit 声明
    @Test
    fun `point 约束用 point_at 顶面中心通过校验`() {
        // box 1×0.3×0.3：["center","max","center"] → [0.5, 0.3, 0.15]，在包围盒内
        val m = validModel(
            supports = listOf(
                SolidGenericSupport(type = "plane", axis = "x", at = "min"),
                SolidGenericSupport(
                    type = "point",
                    point_at = listOf("center", "max", "center")
                )
            )
        )
        AiParser.validateSolidGenericModel(m)
    }

    @Test
    fun `force 用 point_at 顶面中心通过校验`() {
        val m = validModel(
            loads = listOf(
                SolidGenericLoad(
                    type = "force",
                    point_at = listOf("center", "max", "center"),
                    vector = listOf(0.0, -1000.0, 0.0)
                )
            )
        )
        AiParser.validateSolidGenericModel(m)
    }

    @Test
    fun `cylinder 的 point_at 按对称包围盒解析`() {
        // cylinder r=0.1 h=0.3：x/z ∈ [-0.1, 0.1]（中心→0），y ∈ [0, 0.3]
        // ["min","max","center"] → [-0.1, 0.3, 0.0]；force ["center","max","center"] → [0, 0.3, 0]
        val m = SolidGenericModel(
            geometry = SolidGenericGeometryInput(
                type = "cylinder", radius_m = 0.1, height_m = 0.3
            ),
            material = "钢",
            supports = listOf(
                SolidGenericSupport(type = "plane", axis = "y", at = "min"),
                SolidGenericSupport(
                    type = "point",
                    point_at = listOf("min", "max", "center")
                )
            ),
            loads = listOf(
                SolidGenericLoad(
                    type = "force",
                    point_at = listOf("center", "max", "center"),
                    vector = listOf(0.0, -100.0, 0.0)
                )
            ),
        )
        AiParser.validateSolidGenericModel(m)
    }

    @Test
    fun `point_at 非法锚点抛异常`() {
        val m = validModel(
            loads = listOf(
                SolidGenericLoad(
                    type = "force",
                    point_at = listOf("mid", "max", "center"),
                    vector = listOf(0.0, -1000.0, 0.0)
                )
            )
        )
        val e = assertThrows(AiParseException::class.java) {
            AiParser.validateSolidGenericModel(m)
        }
        assertTrue(e.message!!.contains("point_at"))
        assertTrue(e.message!!.contains("min"))
    }

    @Test
    fun `point_at 长度不为3抛异常`() {
        val m = validModel(
            supports = listOf(
                SolidGenericSupport(type = "plane", axis = "x", at = "min"),
                SolidGenericSupport(
                    type = "point",
                    point_at = listOf("max", "center")
                )
            )
        )
        val e = assertThrows(AiParseException::class.java) {
            AiParser.validateSolidGenericModel(m)
        }
        assertTrue(e.message!!.contains("三个元素"))
    }

    @Test
    fun `point_at 与 point 同时给时 point_at 优先`() {
        // point_at 合法（顶面中心，界内），point 越界 [5,5,5]；
        // 若 point 优先会报「不在实体范围内」，point_at 优先则通过校验
        val m = validModel(
            loads = listOf(
                SolidGenericLoad(
                    type = "force",
                    point_at = listOf("center", "max", "center"),
                    point = listOf(5.0, 5.0, 5.0),
                    vector = listOf(0.0, -1000.0, 0.0)
                )
            )
        )
        AiParser.validateSolidGenericModel(m)
    }

    @Test
    fun `unit 非法值抛异常`() {
        val m = validModel().copy(unit = "mm")
        val e = assertThrows(AiParseException::class.java) {
            AiParser.validateSolidGenericModel(m)
        }
        assertTrue(e.message!!.contains("unit 必须是"))
    }

    // ---------------------------------------------------------- at 语义锚点（L-01）
    @Test
    fun `at 语义锚点模型通过校验`() {
        val m = validModel(
            supports = listOf(
                SolidGenericSupport(type = "plane", axis = "y", at = "min")
            ),
            loads = listOf(
                SolidGenericLoad(
                    type = "pressure",
                    face = SolidGenericFace(axis = "y", at = "max"),
                    magnitude = 1e5
                )
            )
        )
        AiParser.validateSolidGenericModel(m)
    }

    @Test
    fun `outline 约束用 at 锚点通过校验`() {
        val m = SolidGenericModel(
            geometry = SolidGenericGeometryInput(
                type = "plate", length_m = 0.4, width_m = 0.3, thickness_m = 0.02
            ),
            material = "铝",
            supports = listOf(
                SolidGenericSupport(
                    type = "outline",
                    face = SolidGenericFace(axis = "z", at = "min")
                )
            ),
            loads = listOf(
                SolidGenericLoad(
                    type = "pressure",
                    face = SolidGenericFace(axis = "z", at = "max"),
                    magnitude = 5e4
                )
            ),
        )
        AiParser.validateSolidGenericModel(m)
    }

    @Test
    fun `非法 at 值抛异常`() {
        val m = validModel(supports = listOf(
            SolidGenericSupport(type = "plane", axis = "y", at = "middle")
        ))
        val e = assertThrows(AiParseException::class.java) {
            AiParser.validateSolidGenericModel(m)
        }
        assertTrue(e.message!!.contains("at 只能是"))
    }

    @Test
    fun `约束面缺少 at 和 value 抛异常`() {
        val m = validModel(supports = listOf(
            SolidGenericSupport(type = "plane", axis = "y")
        ))
        val e = assertThrows(AiParseException::class.java) {
            AiParser.validateSolidGenericModel(m)
        }
        assertTrue(e.message!!.contains("缺少定位"))
    }

    @Test
    fun `压力面缺少 at 和 value 抛异常`() {
        val m = validModel(loads = listOf(
            SolidGenericLoad(
                type = "pressure",
                face = SolidGenericFace(axis = "y"),
                magnitude = 1e5
            )
        ))
        val e = assertThrows(AiParseException::class.java) {
            AiParser.validateSolidGenericModel(m)
        }
        assertTrue(e.message!!.contains("缺少定位"))
    }

    @Test
    fun `cylinder 曲面约束被拒绝只允许 y 端面`() {
        val m = SolidGenericModel(
            geometry = SolidGenericGeometryInput(
                type = "cylinder", radius_m = 0.1, height_m = 0.3
            ),
            material = "钢",
            supports = listOf(
                SolidGenericSupport(type = "plane", axis = "x", at = "min")
            ),
            loads = listOf(
                SolidGenericLoad(
                    type = "pressure",
                    face = SolidGenericFace(axis = "y", at = "max"),
                    magnitude = 1e5
                )
            ),
        )
        val e = assertThrows(AiParseException::class.java) {
            AiParser.validateSolidGenericModel(m)
        }
        assertTrue(e.message!!.contains("端面") || e.message!!.contains("侧压"))
    }

    // ---------------------------------------------------------- L-05 刚体约束秩预检
    private val boxBounds = mapOf(
        "x" to (0.0 to 1.0), "y" to (0.0 to 0.3), "z" to (0.0 to 0.3)
    )

    private fun faceX0Points() = listOf(
        doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.3, 0.0),
        doubleArrayOf(0.0, 0.3, 0.3), doubleArrayOf(0.0, 0.0, 0.3)
    )

    @Test
    fun `秩预检 无约束时 6 个刚体模式全缺`() {
        val modes = AiParser.rigidBodyMissingModes(emptyList(), boxBounds)
        assertEquals(6, modes.size)
    }

    @Test
    fun `秩预检 完整面三向固定时无缺失模式`() {
        val anchors = listOf(SolidConstraintAnchor(faceX0Points(), listOf(0, 1, 2)))
        assertEquals(emptyList<String>(), AiParser.rigidBodyMissingModes(anchors, boxBounds))
    }

    @Test
    fun `秩预检 单面仅约束 x 向剩两平动一转动`() {
        val anchors = listOf(SolidConstraintAnchor(faceX0Points(), listOf(0)))
        assertEquals(
            listOf("y 方向整体平动", "z 方向整体平动", "绕 x 轴整体转动"),
            AiParser.rigidBodyMissingModes(anchors, boxBounds)
        )
    }

    @Test
    fun `秩预检 单点三向固定剩三个转动`() {
        val anchors = listOf(
            SolidConstraintAnchor(listOf(doubleArrayOf(0.0, 0.0, 0.0)), listOf(0, 1, 2))
        )
        assertEquals(
            listOf("绕 x 轴整体转动", "绕 y 轴整体转动", "绕 z 轴整体转动"),
            AiParser.rigidBodyMissingModes(anchors, boxBounds)
        )
    }

    @Test
    fun `秩预检 x 轴上两点固定只剩绕 x 转动`() {
        val anchors = listOf(
            SolidConstraintAnchor(listOf(doubleArrayOf(0.0, 0.0, 0.0)), listOf(0, 1, 2)),
            SolidConstraintAnchor(listOf(doubleArrayOf(1.0, 0.0, 0.0)), listOf(0, 1, 2))
        )
        assertEquals(
            listOf("绕 x 轴整体转动"),
            AiParser.rigidBodyMissingModes(anchors, boxBounds)
        )
    }

    @Test
    fun `欠约束模型端到端报 L-05 与具体模式名`() {
        val m = validModel(supports = listOf(
            SolidGenericSupport(type = "plane", axis = "x", at = "min", dofs = listOf(0))
        ))
        val e = assertThrows(AiParseException::class.java) {
            AiParser.validateSolidGenericModel(m)
        }
        val msg = e.message!!
        assertTrue(msg.contains("[L-05]"))
        assertTrue(msg.contains("y 方向整体平动"))
        assertTrue(msg.contains("z 方向整体平动"))
        assertTrue(msg.contains("绕 x 轴整体转动"))
        assertTrue(msg.contains("共 3 个未约束模式"))
    }

    @Test
    fun `空 dofs 抛异常`() {
        val m = validModel(supports = listOf(
            SolidGenericSupport(type = "plane", axis = "x", at = "min", dofs = emptyList())
        ))
        val e = assertThrows(AiParseException::class.java) {
            AiParser.validateSolidGenericModel(m)
        }
        assertTrue(e.message!!.contains("dofs 不能为空"))
    }

    // ---------------------------------------------------------- 语义错误分级重试
    private val badL05Json = """
        {"ready":true,"kind":"solid_generic","questions":[],"model":null,
         "solid_generic":{"name":"欠约束","kind":"solid_generic",
         "geometry":{"type":"box","length_m":1,"height_m":0.3,"thickness_m":0.3},
         "material":"钢",
         "supports":[{"type":"plane","axis":"x","at":"min","dofs":[0]}],
         "loads":[{"type":"pressure","face":{"axis":"x","at":"max"},"magnitude":100000}]}}
    """.trimIndent()

    private val goodSgJson = """
        {"ready":true,"kind":"solid_generic","questions":[],"model":null,
         "solid_generic":{"name":"合格","kind":"solid_generic",
         "geometry":{"type":"box","length_m":1,"height_m":0.3,"thickness_m":0.3},
         "material":"钢",
         "supports":[{"type":"plane","axis":"y","at":"min","dofs":[0,1,2]}],
         "loads":[{"type":"pressure","face":{"axis":"y","at":"max"},"magnitude":100000}]}}
    """.trimIndent()

    @Test
    fun `语义错误最多重试2次耗尽抛 AiRetryExhaustedException`() = runTest {
        val client = FakeAiClient(listOf(badL05Json, badL05Json, badL05Json))
        val err = runCatching {
            AiParser.parseFrameWithRetry(
                client, listOf(LlmMessage("user", "问题")), forceSolidGeneric = true
            )
        }.exceptionOrNull()
        assertTrue("应抛 AiRetryExhaustedException，实际是 $err", err is AiRetryExhaustedException)
        val exhausted = err as AiRetryExhaustedException
        assertEquals(3, exhausted.attempts)
        assertTrue(exhausted.firstError!!.contains("[L-05]"))
        assertEquals(3, client.calls.size)
        // 两次重试都带确定性修改单
        assertTrue(client.calls[1].last().content.contains("确定性校验结果"))
        assertTrue(client.calls[2].last().content.contains("确定性校验结果"))
    }

    @Test
    fun `语义错误第二次重试修正后成功`() = runTest {
        val client = FakeAiClient(listOf(badL05Json, badL05Json, goodSgJson))
        val result = AiParser.parseFrameWithRetry(
            client, listOf(LlmMessage("user", "问题")), forceSolidGeneric = true
        )
        assertTrue(result.ready)
        assertEquals(3, client.calls.size)
    }

    @Test
    fun `结构+语义重试叠加受全局上限3次兜底`() = runTest {
        // 无全局上限时最坏 4 次调用（1 结构 + 2 语义）；P2-7 后第 3 次仍语义失败即抛，
        // 第 4 条 goodSgJson 不应被消费（FakeAiClient 越界会抛 IndexOutOfBounds）
        val client = FakeAiClient(listOf("抱歉，说不清楚。", badL05Json, badL05Json, goodSgJson))
        val err = runCatching {
            AiParser.parseFrameWithRetry(
                client, listOf(LlmMessage("user", "问题")), forceSolidGeneric = true
            )
        }.exceptionOrNull()
        assertTrue("应抛 AiRetryExhaustedException，实际是 $err", err is AiRetryExhaustedException)
        val exhausted = err as AiRetryExhaustedException
        assertEquals(3, exhausted.attempts)
        assertEquals(3, client.calls.size)
        // 首轮结构错误记录在 firstError；结构重试消息（第 2 次调用）带格式示例
        assertTrue(exhausted.firstError!!.isNotBlank())
        assertTrue(client.calls[1].last().content.contains("无法解析"))
        assertTrue(exhausted.message!!.contains("[L-05]"))
    }

    // ---- G-01/G-02 可网格化预检（预览前 fail-fast） ----

    @Test
    fun `极端尺寸比 box 被 G-01 拦截`() {
        // length 受几何上限 <10 约束，故用 9m；高/厚 0.001m → 比例 9000:1
        val m = validModel(
            supports = listOf(SolidGenericSupport(type = "plane", axis = "x", value = 0.0)),
            loads = listOf(SolidGenericLoad(
                type = "pressure", face = SolidGenericFace(axis = "x", value = 9.0), magnitude = 1e5
            )),
        ).copy(geometry = SolidGenericGeometryInput(
            type = "box", length_m = 9.0, height_m = 0.001, thickness_m = 0.001
        ))
        val ex = assertThrows(AiParseException::class.java) { AiParser.validateSolidGenericModel(m) }
        assertTrue("message=${ex.message}", ex.message!!.contains("[G-01]"))
    }

    @Test
    fun `退化几何维度趋近零被 G-01 拦截`() {
        val m = validModel(
            loads = listOf(SolidGenericLoad(
                type = "pressure", face = SolidGenericFace(axis = "x", value = 1.0), magnitude = 1e5
            )),
        ).copy(geometry = SolidGenericGeometryInput(
            type = "box", length_m = 1.0, height_m = 0.3, thickness_m = 1e-6
        ))
        val ex = assertThrows(AiParseException::class.java) { AiParser.validateSolidGenericModel(m) }
        assertTrue("message=${ex.message}", ex.message!!.contains("[G-01]"))
    }

    @Test
    fun `非法网格档位被 G-02 拦截`() {
        val m = validModel().copy(mesh_level = "super_fine")
        val ex = assertThrows(AiParseException::class.java) { AiParser.validateSolidGenericModel(m) }
        assertTrue("message=${ex.message}", ex.message!!.contains("[G-02]"))
    }

    @Test
    fun `cylinder 极端尺寸比被 G-01 拦截`() {
        // 半径 0.001m（直径 0.002）、高 50m → 比例 25000:1；半径 <10 不触发几何上限
        val m = SolidGenericModel(
            geometry = SolidGenericGeometryInput(type = "cylinder", radius_m = 0.001, height_m = 50.0),
            material = "钢",
            supports = listOf(SolidGenericSupport(type = "plane", axis = "y", value = 0.0)),
            loads = listOf(SolidGenericLoad(type = "gravity", direction = listOf(0.0, -1.0, 0.0), magnitude = 1.0)),
        )
        val ex = assertThrows(AiParseException::class.java) { AiParser.validateSolidGenericModel(m) }
        assertTrue("message=${ex.message}", ex.message!!.contains("[G-01]"))
    }

    @Test
    fun `plate 大尺寸比豁免不被拦截`() {
        // 薄板长 9.9m、厚 0.005m（比例 1980:1）属合法工程场景，尺寸比校验应对 plate 豁免
        val m = SolidGenericModel(
            geometry = SolidGenericGeometryInput(type = "plate", length_m = 9.9, width_m = 1.0, thickness_m = 0.005),
            material = "钢",
            supports = listOf(SolidGenericSupport(type = "plane", axis = "z", value = 0.0)),
            loads = listOf(SolidGenericLoad(
                type = "pressure", face = SolidGenericFace(axis = "z", value = 0.005), magnitude = 1e5
            )),
        )
        AiParser.validateSolidGenericModel(m)
    }

    // ===== P1：荷载 Schema 一致性检查（量级互斥 / 分布 / force 完整性 / 重复约束）=====

    @Test
    fun `压力量级来源冲突被 L-03 拦截`() {
        // 同时填 magnitude 和 wind_speed_m_s → 五选一互斥冲突
        val m = validModel(loads = listOf(SolidGenericLoad(
            type = "pressure", face = SolidGenericFace(axis = "x", value = 1.0),
            magnitude = 1e5, wind_speed_m_s = 15.0
        )))
        val ex = assertThrows(AiParseException::class.java) { AiParser.validateSolidGenericModel(m) }
        assertTrue("message=${ex.message}", ex.message!!.contains("[L-03]"))
    }

    @Test
    fun `fluid 未配 depth_m 被 L-04 拦截`() {
        // fluid 表示静水/土压，必须配 depth_m；只给 fluid + wind_speed 会拦截
        val m = validModel(loads = listOf(SolidGenericLoad(
            type = "pressure", face = SolidGenericFace(axis = "x", value = 1.0),
            fluid = "water", wind_speed_m_s = 15.0
        )))
        val ex = assertThrows(AiParseException::class.java) { AiParser.validateSolidGenericModel(m) }
        assertTrue("message=${ex.message}", ex.message!!.contains("[L-04]"))
    }

    @Test
    fun `force 零向量被 L-03 拦截`() {
        val m = validModel(loads = listOf(SolidGenericLoad(
            type = "force", point = listOf(1.0, 0.15, 0.15), vector = listOf(0.0, 0.0, 0.0)
        )))
        val ex = assertThrows(AiParseException::class.java) { AiParser.validateSolidGenericModel(m) }
        assertTrue("message=${ex.message}", ex.message!!.contains("[L-03]"))
    }

    @Test
    fun `force 非单位 direction 被 L-03 拦截`() {
        val m = validModel(loads = listOf(SolidGenericLoad(
            type = "force", point = listOf(1.0, 0.15, 0.15),
            mass_kg = 10.0, direction = listOf(0.0, 2.0, 0.0)
        )))
        val ex = assertThrows(AiParseException::class.java) { AiParser.validateSolidGenericModel(m) }
        assertTrue("message=${ex.message}", ex.message!!.contains("[L-03]"))
    }

    @Test
    fun `重复面约束被 L-05 拦截`() {
        // 同一面 x=0 被两个 plane 约束且 dofs 重叠（都含 0）→ 过约束/重复
        val m = validModel(supports = listOf(
            SolidGenericSupport(type = "plane", axis = "x", value = 0.0, dofs = listOf(0, 1, 2)),
            SolidGenericSupport(type = "plane", axis = "x", value = 0.0, dofs = listOf(0))
        ))
        val ex = assertThrows(AiParseException::class.java) { AiParser.validateSolidGenericModel(m) }
        assertTrue("message=${ex.message}", ex.message!!.contains("[L-05]"))
    }

    @Test
    fun `合法静水压力 fluid 配 depth_m 不被拦截`() {
        val m = validModel(loads = listOf(SolidGenericLoad(
            type = "pressure", face = SolidGenericFace(axis = "x", value = 1.0),
            fluid = "water", depth_m = 2.0
        )))
        AiParser.validateSolidGenericModel(m)
    }

    @Test
    fun `合法 hydrostatic 配 magnitude 不被拦截`() {
        // hydrostatic=true + magnitude 合法（magnitude 作底部最大压强），不得误伤
        val m = validModel(loads = listOf(SolidGenericLoad(
            type = "pressure", face = SolidGenericFace(axis = "x", value = 1.0),
            magnitude = 1e5, hydrostatic = true
        )))
        AiParser.validateSolidGenericModel(m)
    }
}

/**
 * P0：薄壁容器（shell_vessel）模型 round-trip 与 validateShellVesselModel 全分支覆盖。
 * 直接调用校验抛 AiParseException；经 parseFrame 时统一包装为 AiSemanticException（其子类）。
 */
class ShellVesselValidationTest {

    /** rect 合法基准：feature = min(2,1,1.5) = 1.0，t = 0.005 ≪ 0.2·feature */
    private fun validRect(
        section: String = "rect",
        kind: String = "shell_vessel",
        fill: JsonElement? = JsonPrimitive("water"),
        material: String = "不锈钢",
        meshLevel: String = "medium",
        nu: Double? = 0.3,
    ) = ShellVesselModel(
        name = "矩形水箱",
        kind = kind,
        geometry = ShellVesselGeometry(
            section = section,
            length_m = 2.0, width_m = 1.0, height_m = 1.5, thickness_m = 0.005
        ),
        material = material,
        fill = fill,
        fill_depth_m = 1.2,
        eMPa = 200000.0,
        nu = nu,
        density_kg_m3 = 7850.0,
        mesh_level = meshLevel,
    )

    /** circle 合法基准：feature = min(1.0,3.0) = 1.0，fill_depth == height（边界放行） */
    private fun validCircle(
        section: String = "circle",
        kind: String = "shell_tank",
        fill: JsonElement? = JsonPrimitive("dry_sand"),
        fillDepth: Double? = 3.0,
    ) = ShellVesselModel(
        name = "圆柱料仓",
        kind = kind,
        geometry = ShellVesselGeometry(
            section = section,
            radius_m = 1.0, height_m = 3.0, thickness_m = 0.004
        ),
        material = "钢",
        fill = fill,
        fill_depth_m = fillDepth,
        eMPa = 210000.0,
        nu = 0.28,
        density_kg_m3 = 7800.0,
    )

    /** 断言抛 AiParseException 且消息含关键字 */
    private fun assertFail(keyword: String, block: () -> Unit) {
        val e = assertThrows(AiParseException::class.java) { block() }
        assertTrue("错误消息应包含「$keyword」，实际：${e.message}", e.message!!.contains(keyword))
    }

    // ------------------------------------------------------------------
    // round-trip：AppJson 编解码无损（含 fill 两种形态）
    // ------------------------------------------------------------------

    @Test
    fun `rect 字符串 fill 序列化往返无损`() {
        val model = validRect()
        val json = AppJson.encodeToString(ShellVesselModel.serializer(), model)
        val back = AppJson.decodeFromString(ShellVesselModel.serializer(), json)

        assertEquals(model, back)
        assertEquals("water", back.fill!!.jsonPrimitive.content)
        // 与 Python 契约一致的字段名：E_MPa 大写、geometry 嵌套
        val root = AppJson.parseToJsonElement(json).jsonObject
        assertEquals("200000.0", root["E_MPa"]!!.jsonPrimitive.content)
        assertEquals("rect", root["geometry"]!!.jsonObject["section"]!!.jsonPrimitive.content)
        assertEquals("1.2", root["fill_depth_m"]!!.jsonPrimitive.content)
    }

    @Test
    fun `circle 对象 fill 序列化往返无损`() {
        val fillObj = buildJsonObject {
            put("type", "fluid")
            put("bulk_density_kg_m3", 1000.0)
        }
        val model = validCircle(fill = fillObj)
        val json = AppJson.encodeToString(ShellVesselModel.serializer(), model)
        val back = AppJson.decodeFromString(ShellVesselModel.serializer(), json)

        assertEquals(model, back)
        assertEquals("fluid", back.fill!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("1000.0", back.fill!!.jsonObject["bulk_density_kg_m3"]!!.jsonPrimitive.content)
        // 校验归一化不回写 model：section 别名原样保留
        val aliasModel = validCircle(section = "cylinder")
        val aliasBack = AppJson.decodeFromString(
            ShellVesselModel.serializer(),
            AppJson.encodeToString(ShellVesselModel.serializer(), aliasModel)
        )
        assertEquals("cylinder", aliasBack.geometry.section)
    }

    // ------------------------------------------------------------------
    // 合法用例：不抛异常
    // ------------------------------------------------------------------

    @Test
    fun `rect 合法模型通过校验`() {
        AiParser.validateShellVesselModel(validRect())
    }

    @Test
    fun `circle 合法模型通过校验`() {
        AiParser.validateShellVesselModel(validCircle())
    }

    @Test
    fun `section 别名归一化通过`() {
        AiParser.validateShellVesselModel(validRect(section = "box"))
        AiParser.validateShellVesselModel(validRect(section = "rectangle"))
        AiParser.validateShellVesselModel(validRect(section = "square"))
        AiParser.validateShellVesselModel(validCircle(section = "cylinder"))
        AiParser.validateShellVesselModel(validCircle(section = "circular"))
        AiParser.validateShellVesselModel(validCircle(section = "round"))
    }

    @Test
    fun `kind 别名通过`() {
        AiParser.validateShellVesselModel(validRect(kind = "tank"))
        AiParser.validateShellVesselModel(validRect(kind = "silo"))
        AiParser.validateShellVesselModel(validRect(kind = "vessel"))
        AiParser.validateShellVesselModel(validCircle(kind = "shell_tank"))
    }

    @Test
    fun `fill 缺省按 water 放行`() {
        AiParser.validateShellVesselModel(validRect(fill = null))
    }

    @Test
    fun `fill 对象 granular 放行`() {
        val fill = buildJsonObject { put("type", "granular"); put("preset", "dry_sand") }
        AiParser.validateShellVesselModel(validRect(fill = fill))
    }

    @Test
    fun `fill_depth 等于高度放行`() {
        // validCircle 默认 fill_depth = 3.0 == height 3.0，边界 d == h 不抛
        AiParser.validateShellVesselModel(validCircle())
    }

    @Test
    fun `nu 在上限以内通过`() {
        AiParser.validateShellVesselModel(validRect(nu = 0.49))
    }

    @Test
    fun `壁厚略小于临界特征尺寸通过`() {
        // feature = 1.0 → 0.2·feature = 0.2；t = 0.199 严格小于，放行
        val g = validRect().geometry.copy(thickness_m = 0.199)
        AiParser.validateShellVesselModel(validRect().copy(geometry = g))
    }

    @Test
    fun `mesh_level 合法档位与空白通过`() {
        AiParser.validateShellVesselModel(validRect(meshLevel = "coarse"))
        AiParser.validateShellVesselModel(validRect(meshLevel = "fine"))
        AiParser.validateShellVesselModel(validRect(meshLevel = "ultra"))
        AiParser.validateShellVesselModel(validRect(meshLevel = ""))
    }

    // ------------------------------------------------------------------
    // 非法用例：抛 AiParseException（直接调用，未经 parseFrame 包装）
    // ------------------------------------------------------------------

    @Test
    fun `非法 kind 抛异常`() =
        assertFail("暂不支持的容器场景") { AiParser.validateShellVesselModel(validRect(kind = "sphere")) }

    @Test
    fun `非法 section 抛异常`() =
        assertFail("不支持的容器截面") { AiParser.validateShellVesselModel(validRect(section = "triangle")) }

    @Test
    fun `缺 height_m 抛异常`() {
        val g = validRect().geometry.copy(height_m = null)
        assertFail("缺少 height_m") { AiParser.validateShellVesselModel(validRect().copy(geometry = g)) }
    }

    @Test
    fun `缺 thickness_m 抛异常`() {
        val g = validRect().geometry.copy(thickness_m = null)
        assertFail("缺少 thickness_m") { AiParser.validateShellVesselModel(validRect().copy(geometry = g)) }
    }

    @Test
    fun `高度或壁厚非正抛异常`() {
        val g = validRect().geometry.copy(height_m = 0.0)
        assertFail("必须为正") { AiParser.validateShellVesselModel(validRect().copy(geometry = g)) }
    }

    @Test
    fun `高度超 100m 抛异常`() {
        val g = validRect().geometry.copy(height_m = 150.0)
        assertFail("高度过大") { AiParser.validateShellVesselModel(validRect().copy(geometry = g)) }
    }

    @Test
    fun `壁厚超 5m 毫米误用抛异常`() {
        val g = validRect().geometry.copy(thickness_m = 6.0)
        assertFail("壁厚过大") { AiParser.validateShellVesselModel(validRect().copy(geometry = g)) }
    }

    @Test
    fun `rect 缺 length_m 抛异常`() {
        val g = validRect().geometry.copy(length_m = null)
        assertFail("缺少 length_m") { AiParser.validateShellVesselModel(validRect().copy(geometry = g)) }
    }

    @Test
    fun `rect 缺 width_m 抛异常`() {
        val g = validRect().geometry.copy(width_m = null)
        assertFail("缺少 width_m") { AiParser.validateShellVesselModel(validRect().copy(geometry = g)) }
    }

    @Test
    fun `rect 尺寸过大抛异常`() {
        val g = validRect().geometry.copy(length_m = 200.0)
        assertFail("尺寸过大") { AiParser.validateShellVesselModel(validRect().copy(geometry = g)) }
    }

    @Test
    fun `circle 缺 radius_m 抛异常`() {
        val g = validCircle().geometry.copy(radius_m = null)
        assertFail("缺少 radius_m") { AiParser.validateShellVesselModel(validCircle().copy(geometry = g)) }
    }

    @Test
    fun `circle 半径过大抛异常`() {
        val g = validCircle().geometry.copy(radius_m = 60.0)
        assertFail("半径过大") { AiParser.validateShellVesselModel(validCircle().copy(geometry = g)) }
    }

    @Test
    fun `厚壁 t 等于临界特征尺寸抛异常`() {
        // feature = 1.0 → 0.2·feature = 0.2；严格 >=，刚好等于也抛
        val g = validRect().geometry.copy(thickness_m = 0.2)
        assertFail("过大") { AiParser.validateShellVesselModel(validRect().copy(geometry = g)) }
    }

    @Test
    fun `厚壁 t 大于临界特征尺寸抛异常`() {
        val g = validRect().geometry.copy(thickness_m = 0.3)
        assertFail("过大") { AiParser.validateShellVesselModel(validRect().copy(geometry = g)) }
    }

    @Test
    fun `未知填料预设抛异常`() =
        assertFail("未知的填料预设") { AiParser.validateShellVesselModel(validRect(fill = JsonPrimitive("oil"))) }

    @Test
    fun `fill 对象缺 type 抛异常`() {
        val fill = buildJsonObject { put("density", 1000.0) }
        assertFail("缺少合法 type") { AiParser.validateShellVesselModel(validRect(fill = fill)) }
    }

    @Test
    fun `fill 对象 type 非法抛异常`() {
        val fill = buildJsonObject { put("type", "gas") }
        assertFail("缺少合法 type") { AiParser.validateShellVesselModel(validRect(fill = fill)) }
    }

    @Test
    fun `fill 为数组抛异常`() {
        val fill = JsonArray(listOf(JsonPrimitive("water")))
        assertFail("必须是字符串预设名或对象") { AiParser.validateShellVesselModel(validRect(fill = fill)) }
    }

    @Test
    fun `fill_depth 为负抛异常`() =
        assertFail("不能为负") { AiParser.validateShellVesselModel(validRect().copy(fill_depth_m = -0.1)) }

    @Test
    fun `fill_depth 超高度抛异常`() {
        // h = 1.5，d = 1.6 > h + 1e-6
        assertFail("超过容器高度") { AiParser.validateShellVesselModel(validRect().copy(fill_depth_m = 1.6)) }
    }

    @Test
    fun `mesh_level 非法抛异常`() =
        assertFail("不支持的网格档位") { AiParser.validateShellVesselModel(validRect(meshLevel = "extreme")) }

    @Test
    fun `eMPa 非正抛异常`() =
        assertFail("弹性模量 E_MPa 必须为正") { AiParser.validateShellVesselModel(validRect().copy(eMPa = 0.0)) }

    @Test
    fun `nu 等于上边界抛异常`() =
        assertFail("泊松比 nu 超出范围") { AiParser.validateShellVesselModel(validRect(nu = 0.5)) }

    @Test
    fun `nu 等于 -1 下边界抛异常`() =
        assertFail("泊松比 nu 超出范围") { AiParser.validateShellVesselModel(validRect(nu = -1.0)) }

    @Test
    fun `density 非正抛异常`() =
        assertFail("材料密度 density_kg_m3 必须为正") {
            AiParser.validateShellVesselModel(validRect().copy(density_kg_m3 = 0.0))
        }

    @Test
    fun `material 空白抛异常`() =
        assertFail("容器缺少材料") { AiParser.validateShellVesselModel(validRect(material = "   ")) }

    // ------------------------------------------------------------------
    // 端到端：parseFrame 把校验错误包装为 AiSemanticException
    // ------------------------------------------------------------------

    @Test
    fun `parseFrame shell_vessel 合法模型解析成功`() {
        val shellJson = """{"name":"矩形水箱","kind":"shell_vessel",
            "geometry":{"section":"rect","length_m":2.0,"width_m":1.0,"height_m":1.5,"thickness_m":0.005},
            "material":"不锈钢","fill":"water","fill_depth_m":1.2,"E_MPa":200000.0,"nu":0.3,
            "density_kg_m3":7850.0,"mesh_level":"medium"}"""
        val result = AiParser.parseFrame(
            """{"ready":true,"kind":"shell_vessel","questions":[],"model":null,"shell":$shellJson}"""
        )
        val s = result.shell!!
        assertEquals("shell_vessel", result.kind)
        assertEquals("rect", s.geometry.section)
        assertEquals(2.0, s.geometry.length_m!!, 1e-9)
        assertEquals(0.005, s.geometry.thickness_m!!, 1e-9)
        assertEquals("water", s.fill!!.jsonPrimitive.content)
        assertEquals(200000.0, s.eMPa!!, 1e-9)
    }

    @Test
    fun `parseFrame shell_tank 别名解析成功`() {
        val shellJson = """{"name":"料仓","kind":"shell_tank",
            "geometry":{"section":"cylinder","radius_m":1.0,"height_m":3.0,"thickness_m":0.004},
            "material":"钢","fill":"dry_sand","fill_depth_m":3.0}"""
        val result = AiParser.parseFrame(
            """{"ready":true,"kind":"shell_tank","questions":[],"model":null,"shell":$shellJson}"""
        )
        assertEquals("shell_tank", result.kind)
        assertNotNull(result.shell)
        // section 别名 cylinder 被校验接受，且不回写（保留原串）
        assertEquals("cylinder", result.shell!!.geometry.section)
    }

    @Test
    fun `parseFrame 缺 shell 字段抛语义异常`() {
        val e = assertThrows(AiParseException::class.java) {
            AiParser.parseFrame("""{"ready":true,"kind":"shell_vessel","questions":[],"model":null,"shell":null}""")
        }
        assertTrue("应被包装为 AiSemanticException", e is AiSemanticException)
        assertTrue(e.message!!.contains("缺少 shell"))
    }

    @Test
    fun `parseFrame shell 模型非法被包装成语义错误`() {
        // 外层 kind=shell_vessel 路由到 shell 分支；内层模型 kind=sphere 触发校验失败
        val shellJson = """{"name":"x","kind":"sphere","geometry":{"section":"rect",
            "length_m":2.0,"width_m":1.0,"height_m":1.5,"thickness_m":0.005},"material":"钢"}"""
        val e = assertThrows(AiParseException::class.java) {
            AiParser.parseFrame("""{"ready":true,"kind":"shell_vessel","questions":[],"model":null,"shell":$shellJson}""")
        }
        assertTrue("应被包装为 AiSemanticException", e is AiSemanticException)
        assertTrue(e.message!!.contains("暂不支持的容器场景"))
    }

    // ------------------------------------------------------------------
    // 热输入 thermal：序列化往返 / 校验 / parseFrame 端到端 / 提示词
    // ------------------------------------------------------------------

    @Test
    fun `thermal 序列化往返无损且键名对齐 Python 契约`() {
        // TR-4.1 / TR-4.3：构造含 thermal 的模型 → 编码串（与 PythonBridge.runShell 同一条序列化路径）
        // 必须含 inner_temp_c / outer_temp_c / ref_temp_c 键
        val model = validRect().copy(
            thermal = ShellThermalSpec(innerTempC = 100.0, outerTempC = 0.0, refTempC = 20.0)
        )
        val json = AppJson.encodeToString(ShellVesselModel.serializer(), model)
        assertTrue("序列化串应含 thermal 字段", json.contains("\"thermal\""))
        val back = AppJson.decodeFromString(ShellVesselModel.serializer(), json)
        assertEquals(100.0, back.thermal?.innerTempC)
        assertEquals(0.0, back.thermal?.outerTempC)
        assertEquals(20.0, back.thermal?.refTempC)
        // 键名与 Python 严格对齐 snake_case
        val th = AppJson.parseToJsonElement(json).jsonObject["thermal"]!!.jsonObject
        assertEquals("100.0", th["inner_temp_c"]!!.jsonPrimitive.content)
        assertEquals("0.0", th["outer_temp_c"]!!.jsonPrimitive.content)
        assertEquals("20.0", th["ref_temp_c"]!!.jsonPrimitive.content)
    }

    @Test
    fun `旧版无 thermal 的 JSON 反序列化后 thermal 为 null 且校验通过`() {
        // TR-4.2 / TR-6.2：旧模型 JSON 缺 thermal → 不抛错、thermal==null
        val json = """{"name":"x","kind":"shell_vessel",
            "geometry":{"section":"rect","length_m":2,"width_m":1,"height_m":1.5,"thickness_m":0.005},
            "material":"不锈钢","fill":"water"}"""
        val back = AppJson.decodeFromString(ShellVesselModel.serializer(), json)
        assertNull(back.thermal)
        AiParser.validateShellVesselModel(back)
    }

    @Test
    fun `thermal 合法值通过校验`() {
        // TR-5.1：带合法 thermal 的模型校验通过
        AiParser.validateShellVesselModel(
            validRect().copy(thermal = ShellThermalSpec(innerTempC = 100.0, outerTempC = 0.0))
        )
        // 只给一侧温度也通过（另一侧 Python 兜底）
        AiParser.validateShellVesselModel(
            validCircle().copy(thermal = ShellThermalSpec(innerTempC = 80.0))
        )
    }

    @Test
    fun `thermal 温度越界抛异常`() {
        assertFail("超出合理范围") {
            AiParser.validateShellVesselModel(
                validRect().copy(thermal = ShellThermalSpec(innerTempC = 5000.0))
            )
        }
        assertFail("超出合理范围") {
            AiParser.validateShellVesselModel(
                validRect().copy(thermal = ShellThermalSpec(outerTempC = -300.0))
            )
        }
    }

    @Test
    fun `parseFrame 带 thermal 解析成功并保留缺省字段`() {
        val shellJson = """{"name":"玻璃热水杯","kind":"shell_vessel",
            "geometry":{"section":"circle","radius_m":0.05,"height_m":0.2,"thickness_m":0.003},
            "fill":"water","material":"玻璃","mesh_level":"medium",
            "thermal":{"inner_temp_c":100,"outer_temp_c":0}}"""
        val result = AiParser.parseFrame(
            """{"ready":true,"kind":"shell_vessel","questions":[],"model":null,"shell":$shellJson}"""
        )
        val s = result.shell!!
        assertEquals(100.0, s.thermal?.innerTempC)
        assertEquals(0.0, s.thermal?.outerTempC)
        assertNull("未传 ref_temp_c 应保持 null（Python 兜底 20）", s.thermal?.refTempC)
    }

    @Test
    fun `parseFrame thermal 非数字字符串抛温度字段语义错误`() {
        // TR-5.2：inner_temp_c="abc" 在解码前被原始 JSON 预检拦截，给出点名温度字段的错误
        val shellJson = """{"name":"x","kind":"shell_vessel",
            "geometry":{"section":"circle","radius_m":0.05,"height_m":0.2,"thickness_m":0.003},
            "fill":"water","material":"玻璃","thermal":{"inner_temp_c":"abc"}}"""
        val e = assertThrows(AiParseException::class.java) {
            AiParser.parseFrame("""{"ready":true,"kind":"shell_vessel","questions":[],"model":null,"shell":$shellJson}""")
        }
        assertTrue("应被包装为 AiSemanticException 以触发定点重试", e is AiSemanticException)
        assertTrue("错误应点名 inner_temp_c，实际：${e.message}", e.message!!.contains("inner_temp_c"))
        assertTrue("错误应说明需为数字，实际：${e.message}", e.message!!.contains("数字"))
    }

    @Test
    fun `parseFrame 无 thermal 时 thermal 为 null 且不报错`() {
        val shellJson = """{"name":"x","kind":"shell_vessel",
            "geometry":{"section":"circle","radius_m":0.05,"height_m":0.2,"thickness_m":0.003},
            "fill":"water","material":"玻璃"}"""
        val result = AiParser.parseFrame(
            """{"ready":true,"kind":"shell_vessel","questions":[],"model":null,"shell":$shellJson}"""
        )
        assertNull(result.shell?.thermal)
    }

    @Test
    fun `FakeAiClient 端到端解析带 thermal 的 shell 模型`() = runTest {
        // TR-6.1：AI 响应含 thermal → 走完整重试链路，断言温度字段被正确提取
        val resp = """{"ready":true,"kind":"shell_vessel","questions":[],"model":null,
            "shell":{"name":"玻璃热水杯","kind":"shell_vessel",
            "geometry":{"section":"circle","radius_m":0.05,"height_m":0.2,"thickness_m":0.003},
            "fill":"water","material":"玻璃","mesh_level":"medium",
            "thermal":{"inner_temp_c":100,"outer_temp_c":0}}}"""
        val client = FakeAiClient(listOf(resp))
        val result = AiParser.parseFrameWithRetry(
            client, listOf(LlmMessage("user", "玻璃杯装 100 度热水放 0 度冰箱会不会裂")), forceShell = true
        )
        assertTrue(result.ready)
        assertEquals(100.0, result.shell?.thermal?.innerTempC)
        assertEquals(0.0, result.shell?.thermal?.outerTempC)
        // 能继续喂给求解器：序列化不丢 thermal
        val encoded = AppJson.encodeToString(ShellVesselModel.serializer(), result.shell!!)
        assertTrue(encoded.contains("\"inner_temp_c\":100"))
        assertTrue(encoded.contains("\"outer_temp_c\":0"))
    }

    @Test
    fun `薄壁容器提示词含 thermal 字段说明与热水杯示例`() {
        // TR-5.3：提示词文本必须教会 AI 提取温度
        val p = ChatSession.SHELL_VESSEL_SYSTEM_PROMPT
        assertTrue(p.contains("thermal"))
        assertTrue(p.contains("inner_temp_c"))
        assertTrue(p.contains("outer_temp_c"))
        assertTrue("应含热水杯场景示例", p.contains("热水"))
        assertTrue("应含冰箱（外露面低温）示例", p.contains("冰箱"))
    }

    // ------------------------------------------------------------------
    // out_of_scope：超范围拒答契约（TR-1.1 / TR-1.2）
    // ------------------------------------------------------------------

    @Test
    fun `parseFrame out_of_scope 合法透传且不校验模型`() {
        val result = AiParser.parseFrame(
            """{"ready":false,"kind":"out_of_scope","category":"电磁-结构耦合","questions":[]}"""
        )
        assertEquals("out_of_scope", result.kind)
        assertEquals("电磁-结构耦合", result.category)
        assertFalse(result.ready)
        assertNull(result.model)
        // 不进入任何模型校验分支：questions 空也不抛错
    }

    @Test
    fun `parseFrame out_of_scope 缺 category 抛语义异常`() {
        val e = assertThrows(AiParseException::class.java) {
            AiParser.parseFrame("""{"ready":false,"kind":"out_of_scope","questions":[]}""")
        }
        assertTrue("应包装为 AiSemanticException 触发定点重试", e is AiSemanticException)
        assertTrue(e.message!!.contains("category"))
    }

    @Test
    fun `parseFrame out_of_scope category 为数字抛语义异常`() {
        val e = assertThrows(AiParseException::class.java) {
            AiParser.parseFrame("""{"ready":false,"kind":"out_of_scope","category":123,"questions":[]}""")
        }
        assertTrue(e is AiSemanticException)
        assertTrue(e.message!!.contains("category"))
    }

    @Test
    fun `parseFrame out_of_scope category 为空串抛语义异常`() {
        val e = assertThrows(AiParseException::class.java) {
            AiParser.parseFrame("""{"ready":false,"kind":"out_of_scope","category":"","questions":[]}""")
        }
        assertTrue(e is AiSemanticException)
        assertTrue(e.message!!.contains("不能为空"))
    }

    @Test
    fun `其他 kind 缺 category 时反序列化为默认空串且不报错`() {
        val json = """{"ready":true,"kind":"shell_vessel","questions":[],"model":null,
            "shell":{"name":"x","kind":"shell_vessel","geometry":{"section":"rect","length_m":2,"width_m":1,"height_m":1.5,"thickness_m":0.005},"material":"钢","fill":"water"}}"""
        val result = AiParser.parseFrame(json)
        assertEquals("", result.category)
        assertNotNull(result.shell)
    }

    // ------------------------------------------------------------------
    // shell_buried_pipe：埋地管契约校验（TR-4.1 / TR-4.2）
    // ------------------------------------------------------------------

    private fun buriedPipeJson(
        geometry: String = """"outer_diameter_m":0.05,"thickness_m":0.003,"cover_depth_m":2.0""",
        modelExtra: String = "",
        ready: Boolean = true
    ) = """{"ready":$ready,"kind":"shell_buried_pipe","questions":[],"model":null,
        "shell_buried_pipe":{"name":"埋地管","kind":"shell_buried_pipe",
        "geometry":{$geometry},"material":"PVC"$modelExtra}}"""

    @Test
    fun `parseFrame shell_buried_pipe 合法模型通过`() {
        val result = AiParser.parseFrame(buriedPipeJson())
        assertEquals("shell_buried_pipe", result.kind)
        assertTrue(result.ready)
        val bp = result.shell_buried_pipe!!
        assertEquals(0.05, bp.geometry.outer_diameter_m!!, 1e-9)
        assertEquals(0.003, bp.geometry.thickness_m!!, 1e-9)
        assertEquals(2.0, bp.geometry.cover_depth_m!!, 1e-9)
        assertEquals("PVC", bp.material)
    }

    @Test
    fun `parseFrame shell_buried_pipe 可选土参数与材料覆盖可解码`() {
        val json = buriedPipeJson(
            geometry = """"outer_diameter_m":0.05,"thickness_m":0.003,"cover_depth_m":2.0,"soil_gamma_kN_m3":20,"soil_phi_deg":35,"wheel_load_kN":100,"internal_pressure_kPa":50""",
            modelExtra = ""","E_MPa":3000,"nu":0.4,"strength_MPa":50"""
        )
        val bp = AiParser.parseFrame(json).shell_buried_pipe!!
        assertEquals(20.0, bp.geometry.soil_gamma_kN_m3!!, 1e-9)
        assertEquals(35.0, bp.geometry.soil_phi_deg!!, 1e-9)
        assertEquals(3000.0, bp.eMPa!!, 1e-9)
        assertEquals(50.0, bp.geometry.internal_pressure_kPa!!, 1e-9)
    }

    @Test
    fun `parseFrame shell_buried_pipe ready为false不校验模型`() {
        val result = AiParser.parseFrame(
            """{"ready":false,"kind":"shell_buried_pipe","questions":["管径多少毫米？"],"model":null,"shell_buried_pipe":null}"""
        )
        assertFalse(result.ready)
        assertEquals("shell_buried_pipe", result.kind)
        assertNull(result.shell_buried_pipe)
    }

    @Test
    fun `parseFrame shell_buried_pipe 缺外径抛异常`() {
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(buriedPipeJson(geometry = """"thickness_m":0.003,"cover_depth_m":2.0"""))
        }
    }

    @Test
    fun `parseFrame shell_buried_pipe 缺壁厚抛异常`() {
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(buriedPipeJson(geometry = """"outer_diameter_m":0.05,"cover_depth_m":2.0"""))
        }
    }

    @Test
    fun `parseFrame shell_buried_pipe 缺埋深抛异常`() {
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(buriedPipeJson(geometry = """"outer_diameter_m":0.05,"thickness_m":0.003"""))
        }
    }

    @Test
    fun `parseFrame shell_buried_pipe 缺材料抛异常`() {
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(
                """{"ready":true,"kind":"shell_buried_pipe","questions":[],"model":null,
                "shell_buried_pipe":{"name":"x","kind":"shell_buried_pipe",
                "geometry":{"outer_diameter_m":0.05,"thickness_m":0.003,"cover_depth_m":2.0},"material":""}}"""
            )
        }
    }

    @Test
    fun `parseFrame shell_buried_pipe 尺寸非正抛异常`() {
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(buriedPipeJson(geometry = """"outer_diameter_m":0,"thickness_m":0.003,"cover_depth_m":2.0"""))
        }
    }

    @Test
    fun `parseFrame shell_buried_pipe 壁厚不小于外径抛异常`() {
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(buriedPipeJson(geometry = """"outer_diameter_m":0.003,"thickness_m":0.003,"cover_depth_m":2.0"""))
        }
    }

    @Test
    fun `parseFrame shell_buried_pipe 外径字符串触发预检语义异常`() {
        val e = assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(buriedPipeJson(geometry = """"outer_diameter_m":"0.05","thickness_m":0.003,"cover_depth_m":2.0"""))
        }
        assertTrue(e is AiSemanticException)
        assertTrue(e.message!!.contains("outer_diameter_m"))
    }

    @Test
    fun `parseFrame shell_buried_pipe 土参数字符串触发预检语义异常`() {
        val e = assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(buriedPipeJson(
                geometry = """"outer_diameter_m":0.05,"thickness_m":0.003,"cover_depth_m":2.0,"soil_gamma_kN_m3":"18""""
            ))
        }
        assertTrue(e is AiSemanticException)
        assertTrue(e.message!!.contains("soil_gamma_kN_m3"))
    }

    @Test
    fun `parseFrame shell_buried_pipe E_MPa字符串触发预检语义异常`() {
        val e = assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(buriedPipeJson(modelExtra = ""","E_MPa":"3000""""))
        }
        assertTrue(e is AiSemanticException)
        assertTrue(e.message!!.contains("E_MPa"))
    }

    @Test
    fun `parseFrame shell_buried_pipe 外径量纲误用提示检查单位`() {
        val e = assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(buriedPipeJson(geometry = """"outer_diameter_m":50,"thickness_m":0.003,"cover_depth_m":2.0"""))
        }
        assertTrue(e.message!!.contains("外径"))
    }

    @Test
    fun `parseFrame shell_buried_pipe 土内摩擦角越界抛异常`() {
        assertThrows(AiParseException::class.java) {
            AiParser.parseFrame(buriedPipeJson(
                geometry = """"outer_diameter_m":0.05,"thickness_m":0.003,"cover_depth_m":2.0,"soil_phi_deg":120"""
            ))
        }
    }

    @Test
    fun `parseFrame shell_buried_pipe 缺模型对象由分发拦截`() {
        val e = assertThrows(AiParseException::class.java) {
            AiParser.parseFrame("""{"ready":true,"kind":"shell_buried_pipe","questions":[],"model":null}""")
        }
        assertTrue(e.message!!.contains("shell_buried_pipe"))
    }
}
