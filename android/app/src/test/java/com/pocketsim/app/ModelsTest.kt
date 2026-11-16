package com.pocketsim.app

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Models 反序列化测试（kotlinx.serialization，样例 JSON 内嵌字符串）。
 */
class ModelsTest {

    /** 与 Python 侧 run_scenario 返回契约一致的完整样例 */
    private val sampleResultJson = """
    {
      "template_id": "cantilever_shelf",
      "metrics": {
        "max_deflection_mm": 2.3456,
        "max_deflection_desc": "自由端下垂约 2.35 毫米",
        "max_stress_MPa": 12.87,
        "safety_factor": 1.009,
        "verdict": "危险",
        "solve_time_ms": 812
      },
      "extra_checks": [
        {"name": "搁板弯曲强度", "value_MPa": 12.87, "limit_MPa": 13.0, "safety_factor": 1.01, "pass": false},
        {"name": "端部挠度建议（mm，建议 ≤ 跨度/250 = 1.60）", "value_MPa": 2.3456, "limit_MPa": 1.6, "safety_factor": 0.68, "pass": false}
      ],
      "assumptions": ["假设一：线弹性匀质悬臂梁", "假设二：忽略自重"],
      "render": {
        "field": "von_mises",
        "unit": "MPa",
        "nodes": [[0.0, -9.0], [10.0, -9.0], [10.0, 9.0], [0.0, 9.0]],
        "tris": [[0, 1, 2], [0, 2, 3]],
        "values": [1.0, 2.0, 3.0, 2.5],
        "value_location": "node",
        "disp": [[0.0, 0.0], [0.1, -0.2], [0.2, -0.4], [0.0, 0.0]]
      },
      "timing": {"total_ms": 812.5, "assemble_ms": 300.1, "solve_ms": 500.2},
      "mesh": {
        "dofs": 20402,
        "elements": 19500,
        "mesh_level": "medium",
        "adjusted": "downgraded",
        "adjusted_reason": "求解超时，自动降级为粗网格",
        "solver": "scikit-fem/CST"
      },
      "theory_check": {"max_deflection_mm": 2.3, "stress_MPa": 12.5, "error_pct": 1.98},
      "analogy": "大约相当于一根头发丝的弯曲程度",
      "narrative": ["先根据悬挑长度与载荷建立悬臂梁模型。", "求解得到最大变形约 2.35 毫米、最大应力约 12.87 兆帕。"],
      "params_effective": {"length_mm": 400, "material_name": "松木"},
      "some_future_field": {"x": 1}
    }
    """.trimIndent()

    @Test
    fun `FeaResult 完整样例反序列化`() {
        val r = AppJson.decodeFromString(FeaResult.serializer(), sampleResultJson)

        assertEquals("cantilever_shelf", r.template_id)
        // metrics
        assertEquals(2.3456, r.metrics.max_deflection_mm, 1e-9)
        assertEquals("自由端下垂约 2.35 毫米", r.metrics.max_deflection_desc)
        assertEquals(12.87, r.metrics.max_stress_MPa, 1e-9)
        assertEquals(1.009, r.metrics.safety_factor!!, 1e-9)
        assertEquals("危险", r.metrics.verdict)
        // extra_checks
        assertEquals(2, r.extra_checks.size)
        assertEquals("搁板弯曲强度", r.extra_checks[0].name)
        assertEquals(false, r.extra_checks[0].pass)
        // assumptions
        assertEquals(2, r.assumptions.size)
        // render
        val render = r.render
        assertNotNull(render)
        assertEquals(4, render!!.nodes.size)
        assertEquals(2, render.tris.size)
        assertEquals(4, render.values.size)
        assertNotNull(render.disp)
        assertEquals(-0.2, render.disp!![1][1], 1e-9)
        // timing / mesh
        assertEquals(812.5, r.timing.total_ms, 1e-9)
        assertEquals(20402, r.mesh.dofs)
        assertEquals("downgraded", r.mesh.adjusted)
        assertEquals("求解超时，自动降级为粗网格", r.mesh.adjusted_reason)
        // theory / analogy / 未知字段被忽略
        assertEquals(1.98, r.theory_check!!.error_pct, 1e-9)
        assertEquals("大约相当于一根头发丝的弯曲程度", r.analogy)
        // narrative：Python 侧确定性生成的通俗分析段落（逐段渲染）
        assertEquals(2, r.narrative.size)
        assertTrue(r.narrative[0].contains("悬臂梁"))
        assertTrue(r.narrative[1].contains("2.35"))
        assertEquals("400", r.params_effective["length_mm"]!!.jsonPrimitive.content)
    }

    @Test
    fun `FeaResult 空对象反序列化为默认值`() {
        val r = AppJson.decodeFromString(FeaResult.serializer(), "{}")
        assertEquals("", r.template_id)
        assertEquals(0.0, r.metrics.max_deflection_mm, 1e-9)
        assertNull(r.metrics.safety_factor)
        assertNull(r.render)
        assertNull(r.mesh.adjusted)
        assertTrue(r.assumptions.isEmpty())
        assertTrue(r.narrative.isEmpty())   // 旧版 JSON 无 narrative → 空列表，UI 回退端侧规则文案
    }

    @Test
    fun `TemplateInfo 含参数schema反序列化`() {
        val json = """
        {
          "id": "nail_on_wall",
          "name": "墙上钉挂重",
          "description": "钉悬臂弯曲 + 墙承压简化校核",
          "category": "混合模型",
          "param_schema": [
            {"key": "nail_diameter_mm", "label": "钉径", "unit": "mm", "type": "float",
             "default": 4, "min": 1, "max": 20, "help": "钉子直径"},
            {"key": "load_kg", "label": "挂重", "unit": "kg", "type": "float", "required": true},
            {"key": "material_name", "label": "钉材料", "type": "material", "default": "低碳钢"}
          ]
        }
        """.trimIndent()

        val t = AppJson.decodeFromString(TemplateInfo.serializer(), json)

        assertEquals("nail_on_wall", t.id)
        assertEquals(3, t.param_schema.size)
        assertEquals("4", t.param_schema[0].defaultDisplay())
        assertEquals(1.0, t.param_schema[0].min!!, 1e-9)
        assertTrue(t.param_schema[1].required)
        assertEquals("", t.param_schema[1].defaultDisplay())   // 必填无默认
        assertEquals("低碳钢", t.param_schema[2].defaultDisplay())
        assertNotNull(t.materialSpec)                          // 材料参数定位
        assertEquals("material_name", t.materialSpec!!.key)
    }

    @Test
    fun `MaterialRecord 字段名与Python材料库一致`() {
        val json = """
        {
          "id": "pine",
          "name": "松木",
          "category": "木材",
          "E_MPa": 10000.0,
          "nu": 0.30,
          "density_kg_m3": 500.0,
          "strength_MPa": 13.0,
          "strength_type": "抗弯强度代表值",
          "notes": "来源：木结构设计手册",
          "source": "builtin"
        }
        """.trimIndent()

        val m = AppJson.decodeFromString(MaterialRecord.serializer(), json)
        assertEquals("松木", m.name)
        assertEquals(10000.0, m.eMPa, 1e-9)
        assertEquals(0.30, m.nu, 1e-9)
        assertEquals(13.0, m.strength_MPa!!, 1e-9)
        assertEquals(ParamSource.BUILTIN, ParamSource.fromString(m.source))
    }

    @Test
    fun `ScenarioRequest 序列化往返保持参数类型`() {
        val request = ScenarioRequest(
            template_id = "cantilever_shelf",
            params = buildJsonObject {
                put("load_kg", 3.0)
                put("length_mm", 400)
                put("material_name", "松木")
            },
            mesh_level = "fine",
            data_dir = "/data/user/0/com.pocketsim.app/files"
        )
        val json = AppJson.encodeToString(ScenarioRequest.serializer(), request)
        val back = AppJson.decodeFromString(ScenarioRequest.serializer(), json)

        assertEquals(request, back)
        assertEquals("3.0", back.params["load_kg"]!!.jsonPrimitive.content)
        assertEquals("松木", back.params["material_name"]!!.jsonPrimitive.content)
        assertEquals("fine", back.mesh_level)
    }

    @Test
    fun `AiParseResult 反序列化`() {
        val json = """
        {"template_id":"cantilever_shelf","params":{"load_kg":3,"material_name":"松木"},
         "missing_params":["thickness_mm"],"questions":["板厚多少？"]}
        """.trimIndent()
        val r = AppJson.decodeFromString(AiParseResult.serializer(), json)
        assertEquals("cantilever_shelf", r.template_id)
        assertEquals("3", r.params["load_kg"]!!.jsonPrimitive.content)
        assertEquals(listOf("thickness_mm"), r.missing_params)
    }

    @Test
    fun `ParseProblemResponse 成功契约反序列化`() {
        // pocketsim.parser.parse_problem 的 ok=true 契约（dict 经 json.dumps 跨边界）
        val json = """
        {"ok": true, "template_id": "nail_on_wall",
         "params": {"nail_diameter_mm": 4.5, "load_kg": 5, "material_name": "低碳钢"},
         "missing": ["embed_depth_mm"], "confidence": 0.86}
        """.trimIndent()
        val outcome = AppJson.decodeFromString(ParseProblemResponse.serializer(), json).toOutcome()

        assertNotNull(outcome)
        assertEquals("nail_on_wall", outcome!!.templateId)
        assertEquals("4.5", outcome.params["nail_diameter_mm"]!!.jsonPrimitive.content)
        assertEquals("5", outcome.params["load_kg"]!!.jsonPrimitive.content)
        assertEquals("低碳钢", outcome.params["material_name"]!!.jsonPrimitive.content)
        assertEquals(listOf("embed_depth_mm"), outcome.missing)
        assertEquals(0.86, outcome.confidence, 1e-9)
    }

    @Test
    fun `ParseProblemResponse 失败契约与容错`() {
        // ok=false（带 reason）→ toOutcome() 返回 null，UI 按"未能识别"处理
        val failed = """{"ok": false, "reason": "no template matched"}"""
        assertNull(AppJson.decodeFromString(ParseProblemResponse.serializer(), failed).toOutcome())
        // ok=true 但 template_id 为空 → 同样视为失败
        val blankId = """{"ok": true, "template_id": "", "confidence": 0.5}"""
        assertNull(AppJson.decodeFromString(ParseProblemResponse.serializer(), blankId).toOutcome())
        // ok=false 缺 reason 字段 → 默认空串不崩溃
        val noReason = """{"ok": false}"""
        assertNull(AppJson.decodeFromString(ParseProblemResponse.serializer(), noReason).toOutcome())
    }

    // ------------------------------------------------------------------
    // 三维实体（pocketsim.solid.run_solid 契约）
    // ------------------------------------------------------------------

    /** 与 Python solid.run_solid 输出一致的样例（小网格真实求解结果，render 已完整保留） */
    private val solidResultJson = """
    {
      "kind": "solid",
      "ok": true,
      "model_name": "小尺寸校验墙",
      "scenario": "solid_wall",
      "material": "砖砌体",
      "material_params": {
        "E_MPa": 2400.0, "nu": 0.15, "density_kg_m3": 1900.0,
        "strength_MPa": 2.0, "tension_limit_MPa": 0.15
      },
      "geometry": {
        "length_m": 2.0, "height_m": 1.5, "thickness_m": 0.24,
        "area_m2": 3.0, "volume_m3": 0.72, "weight_kN": 13.41
      },
      "load": {
        "type": "wind", "wind_speed_m_s": 24.4, "beaufort": 9, "beaufort_label": "9 级风",
        "q_Pa": 364.7, "cp_net": 1.2, "pressure_Pa": 437.6, "force_N": 1313.0
      },
      "metrics": {
        "max_disp_mm": 0.0141, "top_disp_mm": 0.0103, "max_stress_MPa": 0.0226,
        "max_tension_MPa": 0.001, "max_compression_MPa": 0.0105, "bend_MPa": 0.0057,
        "net_tension_MPa": 0.0, "safety_factor": 1.63, "verdict": "注意"
      },
      "checks": [
        {"name": "墙根抗弯", "value": 0.0057, "limit": 0.15, "unit": "MPa", "safety_factor": 26.16, "pass": true},
        {"name": "墙体抗压", "value": 0.0105, "limit": 2.0, "unit": "MPa", "safety_factor": 190.98, "pass": true},
        {"name": "抗倾覆", "value": 984.6, "limit": 1608.8, "unit": "N·m", "safety_factor": 1.63, "pass": true}
      ],
      "theory_check": {
        "deflection_mm": 0.1002, "deflection_error_pct": 89.7,
        "bend_stress_MPa": 0.0513, "bend_error_pct": 88.8, "model": "悬臂条带梁（单位墙宽）"
      },
      "weak_part": "墙体底部（迎风侧墙脚）",
      "failure_modes": ["整体倾覆：风压合力作用在墙高中部，墙体绕迎风侧墙脚翻转"],
      "advice": ["加大墙厚或降低墙高"],
      "critical_speed_m_s": 31.2,
      "critical_beaufort": 11,
      "critical_mode": "整体倾覆",
      "render": {
        "type": "solid3d", "unit": "MPa", "field": "von_mises", "value_location": "node",
        "nodes": [[0.0,0.0,0.0],[2.0,0.0,0.0],[0.0,0.75,0.0],[2.0,0.75,0.0],
                  [0.0,1.5,0.0],[2.0,1.5,0.0],[0.0,0.0,0.24],[2.0,0.0,0.24],
                  [0.0,0.75,0.24],[2.0,0.75,0.24],[0.0,1.5,0.24],[2.0,1.5,0.24]],
        "tets": [[0,1,3,9],[0,3,2,9],[0,2,8,9],[0,8,6,9],[0,6,7,9],[0,7,1,9],
                 [2,3,5,11],[2,5,4,11],[2,4,10,11],[2,10,8,11],[2,8,9,11],[2,9,3,11]],
        "disp": [[0.0,0.0,0.0],[0.0,0.0,0.0],[-4.65e-07,-5.141e-06,4.445e-06],
                 [5.37e-07,-5.885e-06,3.119e-06]],
        "values": [0.0192,0.0198,0.0093,0.013,0.004,0.0058,0.0223,0.0219,0.0136,0.017,0.0052,0.0071],
        "bounds": [0.0,0.0,0.0,2.0,1.5,0.24],
        "mesh_lines": {"nx": 1, "ny": 2, "nz": 1}
      },
      "mesh": {
        "level": "custom", "nx": 1, "ny": 2, "nz": 1,
        "n_elements": 12, "n_vertices": 12, "dof": 36, "solver": "direct(spsolve)"
      },
      "timing": {"total_ms": 6.8, "assemble_ms": 1.9, "solve_ms": 1.4, "mesh_ms": 1.9},
      "reactions": {"fx_N": 0.0, "fy_N": 13406.4, "fz_N": -1312.8},
      "assumptions": ["墙底与基础完全固接"],
      "narrative": ["建模：把问题简化为 1.5 m 高、240 mm 厚的砖砌体墙。"]
    }
    """.trimIndent()

    @Test
    fun `SolidResult 完整样例反序列化`() {
        val r = AppJson.decodeFromString(SolidResult.serializer(), solidResultJson)

        assertEquals("solid", r.kind)
        assertTrue(r.ok)
        assertEquals("小尺寸校验墙", r.model_name)
        assertEquals("solid_wall", r.scenario)
        assertEquals("砖砌体", r.material)
        // 材料参数（E_MPa 含大写下划线，必须与 Python 键名一致）
        assertEquals(2400.0, r.material_params.eMPa, 1e-9)
        assertEquals(0.15, r.material_params.nu, 1e-9)
        assertEquals(0.15, r.material_params.tension_limit_MPa, 1e-9)
        // 几何/荷载
        assertEquals(3.0, r.geometry.area_m2, 1e-9)
        assertEquals(13.41, r.geometry.weight_kN, 1e-9)
        assertEquals("wind", r.load.type)
        assertEquals(9, r.load.beaufort)
        assertEquals(437.6, r.load.pressure_Pa, 1e-9)
        // 指标与校核
        assertEquals(0.0103, r.metrics.top_disp_mm, 1e-9)
        assertEquals("注意", r.metrics.verdict)
        assertEquals(3, r.checks.size)
        assertEquals("抗倾覆", r.checks[2].name)
        assertEquals("N·m", r.checks[2].unit)
        assertEquals(0.1002, r.theory_check!!.deflection_mm, 1e-9)
        assertEquals(11, r.critical_beaufort)
        assertEquals("整体倾覆", r.critical_mode)
        // 渲染
        val render = r.render!!
        assertEquals("solid3d", render.type)
        assertEquals(12, render.nodes.size)
        assertEquals(3, render.nodes[0].size)
        assertEquals(12, render.tets.size)
        assertEquals(4, render.tets[0].size)
        assertEquals(12, render.values.size)
        assertEquals(6, render.bounds.size)
        assertEquals(1, render.mesh_lines.nx)
        // 网格/计时/反力
        assertEquals(36, r.mesh.dof)
        assertEquals(12, r.mesh.n_elements)
        assertEquals(6.8, r.timing.total_ms, 1e-9)
        assertEquals(-1312.8, r.reactions.fz_N, 1e-9)
    }

    @Test
    fun `SolidResult 空对象反序列化为默认值`() {
        val r = AppJson.decodeFromString(SolidResult.serializer(), "{}")
        assertEquals("solid", r.kind)
        assertEquals(0.0, r.metrics.safety_factor, 1e-9)
        assertNull(r.critical_speed_m_s)
        assertNull(r.render)
        assertTrue(r.narrative.isEmpty())
    }

    @Test
    fun `SolidWallModel 序列化字段名与Python契约一致`() {
        val model = SolidWallModel(
            name = "砖墙三维抗风",
            wall = SolidWall(length_m = 10.0, height_m = 3.0, thickness_m = 0.24),
            material = "砖砌体",
            eMPa = 2400.0,
            density_kg_m3 = 1900.0,
            pressure_Pa = 900.0,
            beaufort = 10
        )
        val json = AppJson.encodeToString(SolidWallModel.serializer(), model)
        val root = AppJson.parseToJsonElement(json).jsonObject

        assertEquals("solid_wall", root["kind"]!!.jsonPrimitive.content)
        assertEquals("砖砌体", root["material"]!!.jsonPrimitive.content)
        // Python solid.run_solid 读取顶层 E_MPa / density_kg_m3 / pressure_Pa
        assertEquals("2400.0", root["E_MPa"]!!.jsonPrimitive.content)
        assertEquals("1900.0", root["density_kg_m3"]!!.jsonPrimitive.content)
        assertEquals("900.0", root["pressure_Pa"]!!.jsonPrimitive.content)
        val wall = root["wall"]!!.jsonObject
        assertEquals("0.24", wall["thickness_m"]!!.jsonPrimitive.content)
        // 往返保持一致
        assertEquals(model, AppJson.decodeFromString(SolidWallModel.serializer(), json))
    }

    @Test
    fun `AiFrameResult 可携带 solid 模型`() {
        val json = """
        {"ready":true,"kind":"solid","questions":[],"solid":{
          "name":"砖墙","kind":"solid_wall",
          "wall":{"length_m":4.0,"height_m":3.0,"thickness_m":0.24},
          "beaufort":10,"material":"砖砌体"}}
        """.trimIndent()
        val r = AppJson.decodeFromString(AiFrameResult.serializer(), json)
        assertTrue(r.ready)
        assertEquals("solid", r.kind)
        assertNull(r.model)
        assertEquals(0.24, r.solid!!.wall.thickness_m, 1e-9)
    }

    // ------------------------------------------------------------------
    // 埋地管（pocketsim.buried_pipe.run_buried_pipe 契约）
    // ------------------------------------------------------------------

    /** 与 Python buried_pipe.run_buried_pipe 输出一致的样例（Φ50×t3 PVC 埋 2 m 典型场景）。 */
    private val buriedPipeResultJson = """
    {
      "kind": "shell_buried_pipe",
      "ok": true,
      "verdict": "safe",
      "verdict_zh": "安全",
      "metrics": {
        "outer_diameter_mm": 50.0, "thickness_mm": 3.0, "cover_depth_m": 2.0,
        "material": "PVC", "E_MPa": 3000.0, "nu": 0.4, "density_kg_m3": 1400.0,
        "strength_MPa": 50.0,
        "soil_gamma_kN_m3": 18.0, "soil_phi_deg": 30.0, "soil_cohesion_kPa": 0.0,
        "wheel_load_kN": 70.0, "tire_contact_m": 0.3, "internal_pressure_kPa": 0.0,
        "p_soil_kPa": 36.0, "p_live_kPa": 8.357, "p_lateral_kPa": 18.0,
        "deflection_mm": 0.316, "deflection_ratio_pct": 0.633,
        "max_ring_stress_MPa": 4.03, "membrane_stress_MPa": 0.282,
        "bending_stress_MPa": 3.75, "stress_ratio": 0.081,
        "buckling_pcr_kPa": 1543.0, "p_applied_kPa": 40.2, "buckling_sf": 38.4,
        "some_future_metric": true
      },
      "checks": [
        {"label": "挠度比 Δ/D（限值 5%）", "value": "0.633%", "status": "pass"},
        {"label": "应力比 σ/[σ]（限值 1.0）", "value": "0.081", "status": "pass"},
        {"label": "屈曲安全系数（限值 ≥2.0）", "value": "38.4", "status": "pass"}
      ],
      "assumptions": ["未提供土参数，采用默认值：γ=18 kN/m³、φ=30°、c=0", "未提供车载，采用默认值：公路-Ⅰ级单轮 70 kN"],
      "formulas": ["土柱竖向压力 p_soil = γ·H", "Boussinesq 车载扩散 p_live = 3P/(2πH²)", "Spangler 环向挠度", "Timoshenko 长管外压屈曲 p_cr = 2E(t/D)³/(1−ν²)"],
      "advice": ["该工况下管道安全，可正常使用。", "建议结合现场勘察与规范进行正式设计。"],
      "render": {
        "original": [[25.0, 0.0], [0.0, 25.0], [-25.0, 0.0], [0.0, -25.0]],
        "deformed": [[24.9, 0.1], [0.1, 24.9], [-24.9, -0.1], [-0.1, -24.9]],
        "amplification": 50.0, "diameter_mm": 50.0
      }
    }
    """.trimIndent()

    @Test
    fun `BuriedPipeResult 完整样例反序列化`() {
        val r = AppJson.decodeFromString(BuriedPipeResult.serializer(), buriedPipeResultJson)

        assertEquals("shell_buried_pipe", r.kind)
        assertTrue(r.ok)
        assertEquals("safe", r.verdict)
        assertEquals("安全", r.verdict_zh)
        // metrics 宽松映射：结果页按键提取，数值与字符串均需可取
        val m = r.metrics
        assertEquals(50.0, m["outer_diameter_mm"]!!.doubleOrNull!!, 1e-9)
        assertEquals(3.0, m["thickness_mm"]!!.doubleOrNull!!, 1e-9)
        assertEquals(2.0, m["cover_depth_m"]!!.doubleOrNull!!, 1e-9)
        assertEquals("PVC", m["material"]!!.content)
        assertEquals(0.316, m["deflection_mm"]!!.doubleOrNull!!, 1e-9)
        assertEquals(0.633, m["deflection_ratio_pct"]!!.doubleOrNull!!, 1e-9)
        assertEquals(4.03, m["max_ring_stress_MPa"]!!.doubleOrNull!!, 1e-9)
        assertEquals(0.081, m["stress_ratio"]!!.doubleOrNull!!, 1e-9)
        assertEquals(1543.0, m["buckling_pcr_kPa"]!!.doubleOrNull!!, 1e-9)
        assertEquals(38.4, m["buckling_sf"]!!.doubleOrNull!!, 1e-9)
        assertEquals(36.0, m["p_soil_kPa"]!!.doubleOrNull!!, 1e-9)
        assertEquals(8.357, m["p_live_kPa"]!!.doubleOrNull!!, 1e-9)
        assertEquals(18.0, m["p_lateral_kPa"]!!.doubleOrNull!!, 1e-9)
        // 多余字段由 ignoreUnknownKeys / 宽松映射兜底，不影响已知字段
        assertNull(m["nonexistent"])
        // checks / assumptions / formulas / advice
        assertEquals(3, r.checks.size)
        assertEquals("pass", r.checks[0].status)
        assertEquals(2, r.assumptions.size)
        assertEquals(4, r.formulas.size)
        assertEquals(2, r.advice.size)
        // render：原始圆与变形环点集解码为 List<List<Double>>
        assertEquals(4, r.render.original.size)
        assertEquals(4, r.render.deformed.size)
        assertEquals(25.0, r.render.original[0][0], 1e-9)
        assertEquals(50.0, r.render.amplification, 1e-9)
        assertEquals(50.0, r.render.diameter_mm, 1e-9)
    }

    @Test
    fun `BuriedPipeResult 空对象反序列化为默认值`() {
        val r = AppJson.decodeFromString(BuriedPipeResult.serializer(), "{}")
        assertEquals("shell_buried_pipe", r.kind)
        assertEquals("safe", r.verdict)
        assertTrue(r.metrics.isEmpty())
        assertTrue(r.checks.isEmpty())
        assertTrue(r.assumptions.isEmpty())
        assertTrue(r.formulas.isEmpty())
        assertTrue(r.advice.isEmpty())
        assertTrue(r.render.original.isEmpty())
    }
}
