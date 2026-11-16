package com.pocketsim.app

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ParamConfirm → Result 数据流测试：PythonBridge（FeaEngine）接口用 Fake 实现，
 * 验证 ParamForm 默认值回填 → 校验 → buildRequest → 引擎求解 → 结果解读全链路；
 * 附 ChatSession（Fake LLM）与 MaterialLookup（Fake LLM）的关键流程。
 */
class ParamFlowTest {

    // ------------------------------------------------------------------
    // 测试替身
    // ------------------------------------------------------------------

    /** 假引擎：3 模板 + 材料库，runScenario 返回内嵌样例结果 */
    private class FakeFeaEngine : FeaEngine {
        var lastRequest: ScenarioRequest? = null
        var lastUpsert: MaterialRecord? = null
        var lastFrameModel: FrameModel? = null

        private val templates = listOf(
            TemplateInfo(
                id = "cantilever_shelf", name = "悬臂搁板", description = "一端固定于墙体",
                param_schema = listOf(
                    ParamSpec(key = "length_mm", label = "悬挑长度", unit = "mm", type = "float", default = JsonPrimitive(300)),
                    ParamSpec(key = "thickness_mm", label = "板厚", unit = "mm", type = "float", default = JsonPrimitive(18)),
                    ParamSpec(key = "width_mm", label = "宽度", unit = "mm", type = "float", default = JsonPrimitive(200)),
                    ParamSpec(key = "load_kg", label = "重物质量", unit = "kg", type = "float", required = true),
                    ParamSpec(key = "material_name", label = "材料", type = "material", default = JsonPrimitive("松木"))
                )
            ),
            TemplateInfo(
                id = "nail_on_wall", name = "墙上钉挂重", description = "钉悬臂弯曲 + 墙承压简化校核",
                param_schema = listOf(
                    ParamSpec(key = "nail_diameter_mm", label = "钉径", unit = "mm", type = "float", default = JsonPrimitive(4)),
                    ParamSpec(key = "embed_depth_mm", label = "打入深度", unit = "mm", type = "float", default = JsonPrimitive(30)),
                    ParamSpec(key = "load_kg", label = "挂重", unit = "kg", type = "float", required = true),
                    ParamSpec(key = "material_name", label = "钉材料", type = "material", default = JsonPrimitive("低碳钢"))
                )
            ),
            TemplateInfo(
                id = "simply_supported_board", name = "简支层板", description = "两端支承跨中载荷",
                param_schema = listOf(
                    ParamSpec(key = "span_mm", label = "跨度", unit = "mm", type = "float", default = JsonPrimitive(900)),
                    ParamSpec(key = "thickness_mm", label = "板厚", unit = "mm", type = "float", default = JsonPrimitive(18)),
                    ParamSpec(key = "load_kg", label = "跨中重物", unit = "kg", type = "float", required = true),
                    ParamSpec(key = "material_name", label = "材料", type = "material", default = JsonPrimitive("胶合板"))
                )
            )
        )

        private var materials = mutableListOf(
            MaterialRecord(id = "pine", name = "松木", category = "木材", eMPa = 10000.0, nu = 0.3, strength_MPa = 13.0, source = "builtin"),
            MaterialRecord(id = "oak", name = "橡木", category = "木材", eMPa = 12000.0, nu = 0.3, strength_MPa = 18.0, source = "web")
        )

        override suspend fun listTemplates(): List<TemplateInfo> = templates

        override suspend fun runScenario(request: ScenarioRequest): FeaResult {
            lastRequest = request
            // 未知模板模拟 Python 侧抛错
            if (templates.none { it.id == request.template_id }) {
                throw FeaEngineException("材料库中没有名为“不存在的模板”的模板")
            }
            val base = AppJson.decodeFromString(FeaResult.serializer(), SAMPLE_RESULT)
            return base.copy(template_id = request.template_id)
        }

        /** 框架求解：按传入模型组装一份与 Python 契约一致的结果 */
        override suspend fun runFrame(model: FrameModel): FrameResult {
            lastFrameModel = model
            return FrameResult(
                model_name = model.name,
                n_nodes = model.nodes.size,
                n_members = model.members.size,
                n_supports = model.supports.size,
                max_disp_mm = 72.585,
                max_disp_desc = "节点 4 下垂约 72.6 毫米",
                max_stress_MPa = 12.45,
                governing_member = 3,
                safety_factor = 1.04,
                verdict = "危险",
                buckling_ratio = 0.03,
                members = model.members.map {
                    FrameMemberResult(
                        id = it.id, material = it.material,
                        section = "${it.width_b}×${it.depth_h}",
                        N_N = -245.0, M_max_Nmm = 6.9e6,
                        sigma_MPa = 12.45, compress_kN = 0.25, util = 96.2
                    )
                },
                node_disp = model.nodes.map { FrameNodeDisp(dx = 0.0, dy = -72.585) },
                polylines = model.members.map {
                    FramePolyline(member = it.id, pts = listOf(listOf(0.0, 0.0, 0.0, 0.0)))
                },
                narrative = listOf("书架立柱像旗杆一样被书压弯了。"),
                solve_ms = 2.5
            )
        }

        override suspend fun exportFramePdf(
            model: FrameModel, result: FrameResult, filepath: String
        ): String = filepath

        override suspend fun exportSolidGenericPdf(
            model: SolidGenericModel, result: SolidGenericResult, filepath: String
        ): String = filepath

        /** 流体求解：返回与 Python 契约一致的最小结果（单元测试不校验数值） */
        override suspend fun runFlow(model: FlowModel): FlowResult = FlowResult(
            model_name = model.name,
            scenario = model.kind,
            material = model.material,
            wall = FlowWallInfo(
                height_m = model.wall.height_m,
                length_m = model.wall.length_m,
                thickness_m = model.wall.thickness_m,
                area_m2 = model.wall.height_m * model.wall.length_m,
                weight_kN = 0.0
            ),
            wind_speed_m_s = 20.7,
            beaufort = 8,
            beaufort_label = "8 级（大风）",
            safety_factor = 1.70,
            verdict = "注意",
            solve_ms = 3.0
        )

        /** 渗流求解：返回与 Python 契约一致的最小结果（单元测试不校验数值） */
        override suspend fun runSeepage(model: SeepageModel): SeepageResult = SeepageResult(
            model_name = model.name,
            material = model.material,
            upstream_level_m = model.upstream_level_m,
            downstream_level_m = model.downstream_level_m,
            head_diff_m = model.upstream_level_m - model.downstream_level_m,
            dam = SeepageDamInfo(
                height_m = model.dam.height_m,
                crest_width_m = model.dam.crest_width_m,
                base_width_m = model.dam.crest_width_m +
                    (model.dam.upstream_slope + model.dam.downstream_slope) * model.dam.height_m
            ),
            safety_factor = 2.5,
            verdict = "安全",
            solve_ms = 4.0
        )

        /** 时间历程求解：返回与 Python 契约一致的最小结果（单元测试不校验数值） */
        override suspend fun runTransient(spec: TransientSpec): TransientResult = TransientResult(
            model_name = spec.name,
            duration_s = spec.duration_s,
            steps = spec.steps,
            n_samples = spec.steps + 1,
            profile = spec.profile,
            temp_base_c = spec.temp_base_c,
            temp_peak_c = spec.temp_peak_c,
            n_nodes = spec.frame.nodes.size,
            n_members = spec.frame.members.size,
            n_supports = spec.frame.supports.size,
            safety_factor = 2.5,
            verdict = "安全"
        )

        /** 三维实体求解：返回与 Python 契约一致的最小结果（单元测试不校验数值） */
        override suspend fun runSolid(model: SolidWallModel): SolidResult = SolidResult(
            model_name = model.name,
            material = model.material,
            geometry = SolidGeometry(
                length_m = model.wall.length_m,
                height_m = model.wall.height_m,
                thickness_m = model.wall.thickness_m,
                area_m2 = model.wall.length_m * model.wall.height_m,
                volume_m3 = model.wall.length_m * model.wall.height_m * model.wall.thickness_m
            ),
            metrics = SolidMetrics(
                max_disp_mm = 1.989,
                top_disp_mm = 1.99,
                max_stress_MPa = 0.292,
                safety_factor = 0.59,
                verdict = "危险"
            )
        )

        override suspend fun runSolidGeneric(model: SolidGenericModel): SolidGenericResult =
            SolidGenericResult(
                model_name = model.name,
                material = model.material,
                metrics = SolidGenericMetrics(
                    max_disp_mm = 0.05,
                    max_von_mises_MPa = 1.0,
                    safety_factor = 2.0,
                    verdict = "安全"
                )
            )

        override suspend fun runShell(model: ShellVesselModel): SolidGenericResult =
            SolidGenericResult(
                model_name = model.name,
                material = model.material,
                metrics = SolidGenericMetrics(
                    max_disp_mm = 0.02,
                    max_von_mises_MPa = 0.5,
                    safety_factor = 3.0,
                    verdict = "安全"
                )
            )

        override suspend fun runBuriedPipe(model: ShellBuriedPipeModel): BuriedPipeResult =
            BuriedPipeResult(verdict = "safe", verdict_zh = "安全", ok = true)

        override suspend fun listMaterials(system: String): List<MaterialRecord> = materials.toList()

        override suspend fun upsertMaterial(record: MaterialRecord): MaterialRecord {
            lastUpsert = record
            materials = materials.filterNot { it.name == record.name }.toMutableList()
            materials.add(record)
            return record
        }

        override suspend fun deleteMaterial(id: String): Boolean {
            val hit = materials.firstOrNull { it.id == id } ?: return false
            materials = materials.filterNot { it.id == id }.toMutableList()
            return hit.deletable
        }

        override suspend fun logCaseContext(contextJson: String): String = ""

        override suspend fun exportCaseLogExcel(): String = ""
    }

    // 嵌套类 FakeFeaEngine 无法访问外部实例属性，样例结果放 companion
    companion object {
        /** 与 Python 契约一致的样例结果（危险档 + 降级提示 + 云图数据） */
        private val SAMPLE_RESULT = """
    {
      "template_id": "sample_template_id",
      "metrics": {
        "max_deflection_mm": 2.3456,
        "max_deflection_desc": "自由端下垂约 2.35 毫米",
        "max_stress_MPa": 12.87,
        "safety_factor": 1.009,
        "verdict": "危险",
        "solve_time_ms": 812
      },
      "extra_checks": [
        {"name": "搁板弯曲强度", "value_MPa": 12.87, "limit_MPa": 13.0, "safety_factor": 1.01, "pass": false}
      ],
      "assumptions": ["线弹性匀质悬臂梁建模", "忽略自重"],
      "render": {
        "field": "von_mises", "unit": "MPa",
        "nodes": [[0.0, -9.0], [10.0, -9.0], [10.0, 9.0], [0.0, 9.0]],
        "tris": [[0, 1, 2], [0, 2, 3]],
        "values": [1.0, 2.0, 3.0, 2.5],
        "value_location": "node",
        "disp": [[0.0, 0.0], [0.1, -0.2], [0.2, -0.4], [0.0, 0.0]]
      },
      "timing": {"total_ms": 812.5, "assemble_ms": 300.1, "solve_ms": 500.2},
      "mesh": {"dofs": 20402, "elements": 19500, "mesh_level": "medium",
               "adjusted": "downgraded", "adjusted_reason": "求解超时，自动降级为粗网格",
               "solver": "scikit-fem/CST"},
      "theory_check": {"max_deflection_mm": 2.3, "stress_MPa": 12.5, "error_pct": 1.98},
      "analogy": "大约相当于一根头发丝的弯曲程度",
      "params_effective": {"length_mm": 400}
    }
    """.trimIndent()
    }

    // ------------------------------------------------------------------
    // ParamConfirm → Result 数据流（fake 引擎）
    // ------------------------------------------------------------------

    @Test
    fun `模板加载与schema结构`() = runTest {
        val engine = FakeFeaEngine()
        val templates = engine.listTemplates()
        assertEquals(3, templates.size)
        // 每个模板都有材料参数与至少一个必填参数
        templates.forEach { t ->
            assertNotNull("模板 ${t.id} 应有材料参数", t.materialSpec)
            assertTrue(t.param_schema.any { it.required })
        }
    }

    @Test
    fun `ParamForm 默认值回填与AI预填合并`() = runTest {
        val template = FakeFeaEngine().listTemplates()[0]
        val form = ParamForm(
            template = template,
            prefilled = mapOf("load_kg" to "3", "length_mm" to "450"),
            initialSources = emptyMap()
        )
        // AI 预填项
        assertEquals("3", form.values["load_kg"])
        assertEquals(ParamSource.AI, form.sources["load_kg"])
        assertEquals("450", form.values["length_mm"])
        assertEquals(ParamSource.AI, form.sources["length_mm"])
        // 其余回填默认值，来源=内置
        assertEquals("18", form.values["thickness_mm"])
        assertEquals(ParamSource.BUILTIN, form.sources["thickness_mm"])
        assertEquals("松木", form.values["material_name"])
        assertEquals(ParamSource.BUILTIN, form.sources["material_name"])
        // 齐备 → 校验通过
        assertTrue(form.validationErrors().isEmpty())
    }

    @Test
    fun `ParamForm 校验错误路径`() = runTest {
        val template = FakeFeaEngine().listTemplates()[0]
        val form = ParamForm(template)
        // load_kg 必填且无默认 → 空
        val errors0 = form.validationErrors()
        assertTrue(errors0.containsKey("load_kg"))

        // 非数字
        form.set("length_mm", "abc")
        assertTrue(form.validationErrors().containsKey("length_mm"))
        // 越界（schema 无 min/max 时不会触发，这里验证 set 后来源变化）
        form.set("length_mm", "500", ParamSource.USER)
        assertEquals(ParamSource.USER, form.sources["length_mm"])
        // 必填项补齐后校验通过
        form.set("load_kg", "5", ParamSource.USER)
        assertTrue(form.validationErrors().isEmpty())
    }

    @Test
    fun `完整数据流 ParamForm到buildRequest到runScenario到结果解读`() = runTest {
        val engine = FakeFeaEngine()
        val template = engine.listTemplates()[0]

        // ① 参数确认（AI 预填 + 默认回填）
        val form = ParamForm(template, prefilled = mapOf("load_kg" to "3"))
        assertTrue(form.validationErrors().isEmpty())

        // ② 组装请求（ParamConfirm → 引擎）
        val request = form.buildRequest(
            meshLevel = "medium",
            dataDir = "/data/user/0/com.pocketsim.app/files"
        )
        assertEquals("cantilever_shelf", request.template_id)
        assertEquals("medium", request.mesh_level)
        assertEquals("/data/user/0/com.pocketsim.app/files", request.data_dir)
        // 数值参数以 JSON 数字传递，材料以名称传递
        assertEquals("3.0", request.params["load_kg"]!!.jsonPrimitive.content)
        assertEquals("300.0", request.params["length_mm"]!!.jsonPrimitive.content)
        assertEquals("松木", request.params["material_name"]!!.jsonPrimitive.content)

        // ③ 引擎求解（PythonBridge 接口的 fake 实现）
        val result = engine.runScenario(request)
        assertEquals(engine.lastRequest, request)

        // ④ 结果解读（ResultScreen 使用的映射逻辑）
        assertEquals("cantilever_shelf", result.template_id)
        assertEquals(2.3456, result.metrics.max_deflection_mm, 1e-9)
        assertEquals(12.87, result.metrics.max_stress_MPa, 1e-9)
        assertEquals(SafetyLogic.Grade.DANGER, SafetyLogic.grade(result.metrics.safety_factor))
        // 云图数据齐备（节点/单元/标量/位移）
        val render = result.render!!
        assertEquals(render.nodes.size, render.values.size)
        assertEquals(render.nodes.size, render.disp!!.size)
        assertTrue(render.tris.all { it.size == 3 })
        // 降级提示与理论自检
        assertEquals("downgraded", result.mesh.adjusted)
        assertTrue(result.theory_check!!.error_pct <= 5.0)
    }

    // ------------------------------------------------------------------
    // ChatSession 框架模式（frameMode）：LLM 直接输出 FrameModel
    // ------------------------------------------------------------------

    /** 框架模式用合法模型 JSON（松木悬臂梁，端部挂 30kg） */
    private val frameModelJson = """
        {"name":"悬臂搁板",
         "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":400,"y":0}],
         "members":[{"id":1,"i":1,"j":2,"width_b":40,"depth_h":18,"material":"松木"}],
         "supports":[{"node":1,"type":"fixed"}],
         "loads":[{"type":"force","node":2,"fx":0,"fy":-294}]}
    """.trimIndent()

    @Test
    fun `ChatSession 框架模式追问后建模就绪`() = runTest {
        var frameReady: FrameModel? = null
        val client = FakeAiClient(
            listOf(
                // 第一轮：信息不足 → 追问材料
                """{"ready":false,"questions":["用什么材料做搁板？"],"model":null}""",
                // 第二轮（用户回答后）：建模完成
                """{"ready":true,"questions":[],"model":$frameModelJson}"""
            )
        )
        val session = ChatSession(
            aiClientProvider = { client },
            greeting = "你好",
            onFrameReady = { frameReady = it }
        )

        session.send("帮我算一个墙上搁板")
        // 第一轮：ready=false → 追问 1 条，不触发就绪回调
        assertTrue(session.messages.value.any { !it.fromUser && it.text.contains("用什么材料做搁板？") })
        assertNull("尚未建模完成，不应触发就绪回调", frameReady)

        // 第二轮：ready=true → 触发 onFrameReady（跳模型预览页）
        session.send("松木")
        assertNotNull(frameReady)
        assertEquals("悬臂搁板", frameReady!!.name)
        assertEquals(2, frameReady!!.nodes.size)
        assertEquals(1, frameReady!!.members.size)
        assertEquals("松木", frameReady!!.members[0].material)
        assertTrue(session.messages.value.any { !it.fromUser && it.text.contains("预览确认") })
    }

    @Test
    fun `框架提示词要求材料不明确时必须追问`() {
        val client = FakeAiClient(emptyList())
        val auto = ChatSession(aiClientProvider = { client }, greeting = "你好").buildSystemPrompt()
        val three = ChatSession(aiClientProvider = { client }, greeting = "你好", threeD = true).buildSystemPrompt()

        listOf(auto, three).forEach { prompt ->
            assertTrue("提示词缺少材料必需规则", prompt.contains("材料是必需信息"))
            assertTrue("提示词未禁止默认材料", prompt.contains("严禁自行默认"))
            assertTrue("提示词未要求材质不明时追问", prompt.contains("必须追问"))
            assertTrue("提示词缺少三维实体场景", prompt.contains("三维实体契约"))
            assertTrue("三维实体示例缺少 solid_wall", prompt.contains("solid_wall"))
        }
    }

    @Test
    fun `ChatSession 框架模式轮数用尽引导一次性说全`() = runTest {
        var frameReady: FrameModel? = null
        val notReady = """{"ready":false,"questions":["材料是什么？"],"model":null}"""
        val client = FakeAiClient(listOf(notReady, notReady, notReady))
        val session = ChatSession(
            aiClientProvider = { client },
            greeting = "你好",
            onFrameReady = { frameReady = it }
        )

        session.send("帮我算一个书架")
        session.send("不太清楚")
        session.send("还是不清楚")
        // 三轮后：超过最大追问轮数 → 引导一次性说全，而不是继续追问
        assertTrue(session.messages.value.any { !it.fromUser && it.text.contains("一次性说清") })
        assertNull(frameReady)
    }

    @Test
    fun `ChatSession 时间历程建模就绪触发 onTransientReady`() = runTest {
        var specReady: TransientSpec? = null
        val transientJson = """
            {"name":"悬臂杆温度循环","frame":$frameModelJson,
             "duration_s":7200,"steps":12,"temp_base_c":20,"temp_peak_c":80,
             "profile":"triangle","members":null}
        """.trimIndent()
        val client = FakeAiClient(
            listOf(
                """{"ready":true,"questions":[],"model":null,"kind":"transient","transient":$transientJson}"""
            )
        )
        val session = ChatSession(
            aiClientProvider = { client },
            greeting = "你好",
            onTransientReady = { specReady = it }
        )

        session.send("悬臂杆端挂 30 公斤，温度从 20 升到 80 再降回来")

        assertNotNull("时间历程建模完成应触发回调", specReady)
        assertEquals(7200.0, specReady!!.duration_s, 1e-9)
        assertEquals(12, specReady!!.steps)
        assertEquals("triangle", specReady!!.profile)
        assertTrue(session.messages.value.any { !it.fromUser && it.text.contains("时间历程") })
    }

    @Test
    fun `ChatSession 三维实体建模就绪触发 onSolidReady`() = runTest {
        var solidReady: SolidWallModel? = null
        val solidJson = """
            {"name":"砖墙三维抗风","kind":"solid_wall",
             "wall":{"length_m":10.0,"height_m":3.0,"thickness_m":0.24},
             "beaufort":10,"material":"砖砌体"}
        """.trimIndent()
        val client = FakeAiClient(
            listOf(
                """{"ready":true,"questions":[],"model":null,"kind":"solid","solid":$solidJson}"""
            )
        )
        val session = ChatSession(
            aiClientProvider = { client },
            greeting = "你好",
            onSolidReady = { solidReady = it }
        )

        session.send("10 米长 3 米高的砖墙，10 级风会不会被吹裂")

        assertNotNull("三维实体建模完成应触发回调", solidReady)
        assertEquals(3.0, solidReady!!.wall.height_m, 1e-9)
        assertEquals(0.24, solidReady!!.wall.thickness_m, 1e-9)
        assertEquals(10, solidReady!!.beaufort)
        assertEquals("砖砌体", solidReady!!.material)
        assertTrue(session.messages.value.any { !it.fromUser && it.text.contains("三维实体") })
    }
}
