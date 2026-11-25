package com.pocketsim.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 优化建议（第一期 Frame / SolidGeneric）纯逻辑单测：
 * 入口门控、AI 回复解析与模型校验、模型差异 diff、指标对比、会话验证闭环。
 */
class OptimizeTest {

    private val baseModel = """
        {"name":"两层书架","dim":2,
         "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":800,"y":0},
                  {"id":3,"x":0,"y":1500},{"id":4,"x":800,"y":1500},{"id":5,"x":400,"y":1500}],
         "members":[{"id":1,"i":1,"j":2,"width_b":30,"depth_h":40,"material":"松木"},
                    {"id":2,"i":1,"j":3,"width_b":30,"depth_h":40,"material":"松木"},
                    {"id":3,"i":2,"j":4,"width_b":30,"depth_h":40,"material":"松木"},
                    {"id":4,"i":3,"j":5,"width_b":30,"depth_h":40,"material":"松木"}],
         "supports":[{"node":1,"type":"fixed"},{"node":2,"type":"fixed"}],
         "loads":[{"type":"force","node":5,"fx":0,"fy":-294},
                  {"type":"udl","member":1,"qy":-0.3}]}
    """.trimIndent()

    /** 新增一根斜撑（5→1）并加大 1 号杆截面 */
    private val optimizedModel = """
        {"name":"两层书架","dim":2,
         "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":800,"y":0},
                  {"id":3,"x":0,"y":1500},{"id":4,"x":800,"y":1500},{"id":5,"x":400,"y":1500}],
         "members":[{"id":1,"i":1,"j":2,"width_b":40,"depth_h":50,"material":"松木"},
                    {"id":2,"i":1,"j":3,"width_b":30,"depth_h":40,"material":"松木"},
                    {"id":3,"i":2,"j":4,"width_b":30,"depth_h":40,"material":"松木"},
                    {"id":4,"i":3,"j":5,"width_b":30,"depth_h":40,"material":"松木"},
                    {"id":5,"i":5,"j":1,"width_b":30,"depth_h":40,"material":"松木"}],
         "supports":[{"node":1,"type":"fixed"},{"node":2,"type":"fixed"}],
         "loads":[{"type":"force","node":5,"fx":0,"fy":-294},
                  {"type":"udl","member":1,"qy":-0.3}]}
    """.trimIndent()

    private fun frame(json: String): FrameModel =
        AppJson.decodeFromString(FrameModel.serializer(), json)

    // ------------------------------------------------------------------
    // 门控：只有注意/危险才提示优化
    // ------------------------------------------------------------------
    @Test
    fun `OptimizeGate 安全与未知不提供建议`() {
        assertFalse(OptimizeGate.shouldOffer(SafetyLogic.Grade.SAFE))
        assertFalse(OptimizeGate.shouldOffer(SafetyLogic.Grade.UNKNOWN))
    }

    @Test
    fun `OptimizeGate 注意与危险提供建议`() {
        assertTrue(OptimizeGate.shouldOffer(SafetyLogic.Grade.CAUTION))
        assertTrue(OptimizeGate.shouldOffer(SafetyLogic.Grade.DANGER))
    }

    // ------------------------------------------------------------------
    // diff：新增 / 修改 / 载荷与约束防作弊
    // ------------------------------------------------------------------
    @Test
    fun `diffFrame 识别新增杆件与截面修改`() {
        val diff = OptimizeDiff.diffFrame(frame(baseModel), frame(optimizedModel))
        assertTrue(diff.addedMemberIds.contains(5))
        assertTrue(diff.modifiedMemberIds.contains(1))
        assertFalse(diff.loadOrSupportChanged)
        assertTrue(diff.changes.any { it.kind == ChangeKind.ADDED })
        assertTrue(diff.changes.any { it.detail.contains("40") && it.detail.contains("50") })
    }

    @Test
    fun `diffFrame 载荷被改动判定为不合规优化`() {
        val changedLoad = baseModel.replace("\"fy\":-294", "\"fy\":-100")
        val diff = OptimizeDiff.diffFrame(frame(baseModel), frame(changedLoad))
        assertTrue(diff.loadOrSupportChanged)
    }

    @Test
    fun `diffFrame 删除原有约束判定为不合规优化`() {
        val relaxed = baseModel.replace("\"supports\":[{\"node\":1,\"type\":\"fixed\"},{\"node\":2,\"type\":\"fixed\"}]",
            "\"supports\":[{\"node\":1,\"type\":\"fixed\"}]")
        val diff = OptimizeDiff.diffFrame(frame(baseModel), frame(relaxed))
        assertTrue(diff.loadOrSupportChanged)
    }

    @Test
    fun `diffFrame 未改动结构时差异为空`() {
        val diff = OptimizeDiff.diffFrame(frame(baseModel), frame(baseModel))
        assertTrue(diff.isEmpty)
        assertFalse(diff.loadOrSupportChanged)
    }

    /**
     * 增设中柱：横杆 1-2 被节点 6 拆成 7/8 两段（编号全新），中柱为 9。
     * 期望：横杆段判「修改（拆分）」而不是新增；只有中柱 #9 判新增。
     */
    private val splitModel = """
        {"name":"增设中柱","dim":2,
         "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":800,"y":0},
                  {"id":3,"x":0,"y":1500},{"id":4,"x":800,"y":1500},{"id":5,"x":400,"y":1500},
                  {"id":6,"x":400,"y":0},{"id":7,"x":400,"y":1500}],
         "members":[{"id":7,"i":1,"j":6,"width_b":30,"depth_h":40,"material":"松木"},
                    {"id":8,"i":6,"j":2,"width_b":30,"depth_h":40,"material":"松木"},
                    {"id":2,"i":1,"j":3,"width_b":30,"depth_h":40,"material":"松木"},
                    {"id":3,"i":2,"j":4,"width_b":30,"depth_h":40,"material":"松木"},
                    {"id":4,"i":3,"j":5,"width_b":30,"depth_h":40,"material":"松木"},
                    {"id":9,"i":6,"j":7,"width_b":30,"depth_h":40,"material":"松木"}],
         "supports":[{"node":1,"type":"fixed"},{"node":2,"type":"fixed"}],
         "loads":[{"type":"force","node":5,"fx":0,"fy":-294},
                  {"type":"udl","member":7,"qy":-0.3},{"type":"udl","member":8,"qy":-0.3}]}
    """.trimIndent()

    /** 简支梁 → 增设中间支座的连续梁：udl 随拆分子段重新挂载，强度与合力不变 */
    private val beamModel = """
        {"name":"简支梁","dim":2,
         "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":4000,"y":0}],
         "members":[{"id":1,"i":1,"j":2,"width_b":100,"depth_h":200,"material":"Q235钢"}],
         "supports":[{"node":1,"type":"pinned"},{"node":2,"type":"roller"}],
         "loads":[{"type":"udl","member":1,"qy":-5}]}
    """.trimIndent()

    private val continuousModel = """
        {"name":"两跨连续梁","dim":2,
         "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":4000,"y":0},{"id":3,"x":2000,"y":0}],
         "members":[{"id":1,"i":1,"j":3,"width_b":100,"depth_h":200,"material":"Q235钢"},
                    {"id":2,"i":3,"j":2,"width_b":100,"depth_h":200,"material":"Q235钢"}],
         "supports":[{"node":1,"type":"pinned"},{"node":2,"type":"roller"},{"node":3,"type":"roller"}],
         "loads":[{"type":"udl","member":1,"qy":-5},{"type":"udl","member":2,"qy":-5}]}
    """.trimIndent()

    @Test
    fun `FrameProfile 识别单跨简支梁并给出编号起点`() {
        val p = FrameProfile.describe(frame(beamModel), english = false)
        assertTrue(p.contains("单跨简支梁"))
        assertTrue(p.contains("最大节点号 2"))
        assertTrue(p.contains("最大杆件号 1"))
        assertTrue(p.contains("L=4000 mm 水平"))
    }

    @Test
    fun `FrameProfile 识别悬臂与悬空水平跨`() {
        val p = FrameProfile.describe(frame(baseModel), english = false)
        // 4 号杆 3→5：节点 5 只连一根杆且无支座 → 悬臂
        assertTrue(p.contains("#4 为悬臂"))
        val en = FrameProfile.describe(frame(baseModel), english = true)
        assertTrue(en.contains("cantilever"))
    }

    @Test
    fun `diffFrame 简支改连续梁拆分杆件不算改载荷`() {
        val diff = OptimizeDiff.diffFrame(frame(beamModel), frame(continuousModel))
        assertFalse(diff.loadOrSupportChanged)
    }

    @Test
    fun `diffFrame 拆分后漏挂一段均布载荷判为改载荷`() {
        val dropped = continuousModel.replace(",{\"type\":\"udl\",\"member\":2,\"qy\":-5}", "")
        val diff = OptimizeDiff.diffFrame(frame(beamModel), frame(dropped))
        assertTrue(diff.loadOrSupportChanged)
    }

    @Test
    fun `diffFrame 拆分横杆判为修改而不是新增`() {
        val diff = OptimizeDiff.diffFrame(frame(baseModel), frame(splitModel))
        assertFalse(diff.addedMemberIds.contains(7))
        assertFalse(diff.addedMemberIds.contains(8))
        assertTrue(diff.modifiedMemberIds.contains(7))
        assertTrue(diff.modifiedMemberIds.contains(8))
        assertEquals(setOf(9), diff.addedMemberIds)
        assertTrue(diff.changes.any { it.kind == ChangeKind.MODIFIED && it.label.contains("拆分") })
        assertFalse(diff.loadOrSupportChanged)
    }

    @Test
    fun `diffFrame 复用旧编号但几何不同判为新增`() {
        // 4 号杆件由 3→5 改为 4→5（几何换掉一部分），不能因为 id 没变就当成"修改"
        val moved = baseModel.replace("{\"id\":4,\"i\":3,\"j\":5", "{\"id\":4,\"i\":4,\"j\":5")
        val diff = OptimizeDiff.diffFrame(frame(baseModel), frame(moved))
        assertTrue(diff.addedMemberIds.contains(4))
        assertFalse(diff.modifiedMemberIds.contains(4))
        assertTrue(diff.changes.any { it.kind == ChangeKind.REMOVED && it.label.contains("#4") })
    }

    @Test
    fun `diffFrame 坐标微差在容差内不算新增`() {
        // 节点 2 平移 0.2 mm（< 0.5 mm 容差），不应产生任何差异
        val nudged = baseModel.replace("\"x\":800,\"y\":0}", "\"x\":800.2,\"y\":0}")
        val diff = OptimizeDiff.diffFrame(frame(baseModel), frame(nudged))
        assertTrue(diff.addedMemberIds.isEmpty())
        assertTrue(diff.modifiedMemberIds.isEmpty())
        assertTrue(diff.isEmpty)
    }

    // ------------------------------------------------------------------
    // 指标对比
    // ------------------------------------------------------------------
    @Test
    fun `OptimizeCompare 安全系数提升且位移下降判为改善`() {
        val before = OptimizeMetrics(safetyFactor = 1.1, maxDispMm = 8.0, signatureStressMPa = 90.0)
        val after = OptimizeMetrics(safetyFactor = 2.4, maxDispMm = 4.0, signatureStressMPa = 55.0)
        val deltas = OptimizeCompare.deltas(before, after)
        assertEquals(3, deltas.size)
        assertTrue(deltas[0].better)
        assertTrue(deltas[1].better)
        assertTrue(deltas[2].better)
        assertTrue(OptimizeCompare.isSafeAfter(after))
    }

    @Test
    fun `OptimizeCompare 安全系数下降判为变差`() {
        val before = OptimizeMetrics(safetyFactor = 2.5)
        val after = OptimizeMetrics(safetyFactor = 1.8)
        assertFalse(OptimizeCompare.deltas(before, after)[0].better)
        assertFalse(OptimizeCompare.isSafeAfter(after))
    }

    // ------------------------------------------------------------------
    // AI 回复解析 + 模型校验
    // ------------------------------------------------------------------
    private fun replyJson(model: String, n: Int = 1): String = buildString {
        append("""{"ready":true,"diagnosis":"支座处弯矩过大","suggestions":[""")
        repeat(n) { i ->
            if (i > 0) append(',')
            append("""{"title":"新增斜撑$i","desc":"在自由端加斜撑","expected":"挠度下降","changes":["新增杆件"],"model":""")
            append(model)
            append('}')
        }
        append("""],"disclaimer":"AI 辅助建议，不能替代正规工程验算"}""")
    }

    @Test
    fun `parseOptimize 合法方案通过并规范化模型`() {
        val reply = AiParser.parseOptimize(replyJson(optimizedModel), OptimizeKind.FRAME)
        assertEquals(1, reply.suggestions.size)
        assertNotNull(reply.suggestions[0].modelJson)
        assertTrue(reply.diagnosis.isNotBlank())
    }

    @Test
    fun `parseOptimize 非法模型全部被拒时抛异常`() {
        assertThrows(AiParseException::class.java) {
            AiParser.parseOptimize(
                """{"ready":true,"diagnosis":"x","suggestions":[{"title":"坏方案","model":{"name":"x","nodes":123}}],"disclaimer":""}""",
                OptimizeKind.FRAME
            )
        }
    }

    @Test
    fun `parseOptimize 无suggestions返回空列表`() {
        val reply = AiParser.parseOptimize(
            """{"ready":true,"diagnosis":"无方案","suggestions":[],"disclaimer":""}""",
            OptimizeKind.FRAME
        )
        assertTrue(reply.suggestions.isEmpty())
    }

    // ------------------------------------------------------------------
    // O-07：增量补丁契约（patch 解析 / Applier 合成 / ID 校验 / 完整模型回退）
    // ------------------------------------------------------------------

    /** 构造 O-07 增量补丁契约的优化回复 JSON（suggestions 只带 patch 字段） */
    private fun patchReplyJson(patch: String, title: String = "新增中间支座"): String =
        """{"ready":true,"diagnosis":"支座处弯矩过大","suggestions":[{"title":"$title","desc":"跨中加节点与铰支座改连续梁","expected":"弯矩下降","changes":["新增节点","新增支座"],"patch":$patch}],"disclaimer":"AI 辅助建议，不能替代正规工程验算"}"""

    @Test
    fun `O-07 patch 应用合成结果与期望完整模型一致`() {
        val patch = AppJson.decodeFromString(
            OptimizePatch.serializer(),
            """{"add_nodes":[{"id":3,"x":2000,"y":0}],
               "modify_members":[{"id":1,"j":3}],
               "add_members":[{"id":2,"i":3,"j":2,"width_b":100,"depth_h":200,"material":"Q235钢"}],
               "add_supports":[{"node":3,"type":"roller"}]}"""
        )
        val merged = OptimizePatchApplier.apply(frame(beamModel), patch)
        // 合成结果 = 连续梁（name 与载荷不进 patch，原样透传 → 保持基准模型不变）
        assertEquals(
            frame(continuousModel).copy(name = frame(beamModel).name, loads = frame(beamModel).loads),
            merged
        )
        // LeanPromptJson 风格：空 patch 序列化省略全部默认值字段（P-07）
        assertEquals("{}", LeanPromptJson.encodeToString(OptimizePatch.serializer(), OptimizePatch()))
    }

    @Test
    fun `O-07 patch ID 冲突与引用不存在时应用失败并给出中文原因`() {
        val base = frame(beamModel)
        fun apply(patchJson: String) = runCatching {
            OptimizePatchApplier.apply(base, AppJson.decodeFromString(OptimizePatch.serializer(), patchJson))
        }
        // 新增节点 id 不大于原模型最大节点号（与现有编号冲突必然 ≤ 最大号，先触发该检查）
        val c1 = apply("""{"add_nodes":[{"id":2,"x":2000,"y":0}]}""")
        assertTrue(c1.exceptionOrNull() is AiParseException)
        assertTrue(c1.exceptionOrNull()!!.message!!.contains("大于"))
        // 补丁内新增节点 id 互相重复 → 冲突
        val c2 = apply("""{"add_nodes":[{"id":3,"x":1000,"y":0},{"id":3,"x":2000,"y":0}]}""")
        assertTrue(c2.exceptionOrNull()!!.message!!.contains("冲突"))
        // modify 引用不存在的杆件 id
        val c3 = apply("""{"modify_members":[{"id":99,"width_b":120}]}""")
        assertTrue(c3.exceptionOrNull()!!.message!!.contains("不存在"))
        // 新增杆件端点节点不存在
        val c4 = apply("""{"add_members":[{"id":5,"i":9,"j":2,"width_b":100,"depth_h":200,"material":"Q235钢"}]}""")
        assertTrue(c4.exceptionOrNull()!!.message!!.contains("端点节点"))
        // 新增支座挂载节点不存在
        val c5 = apply("""{"add_supports":[{"node":99,"type":"roller"}]}""")
        assertTrue(c5.exceptionOrNull()!!.message!!.contains("不存在"))
    }

    @Test
    fun `O-07 patch 回复合成完整模型后单请求通过`() = runTest {
        val target = OptimizeTarget(
            kind = OptimizeKind.FRAME,
            modelJson = AppJson.encodeToString(FrameModel.serializer(), frame(baseModel)),
            summary = "危险",
            before = OptimizeMetrics(safetyFactor = 1.0)
        )
        // 合法 patch（加斜撑 5→1 + 加大 1 号杆截面，等价于 optimizedModel）→ 一次请求即合成校验通过
        val goodPatch =
            """{"modify_members":[{"id":1,"width_b":40,"depth_h":50}],"add_members":[{"id":5,"i":5,"j":1,"width_b":30,"depth_h":40,"material":"松木"}]}"""
        val fake = FakeAiClient(listOf(patchReplyJson(goodPatch)))
        val session = OptimizeSession(
            aiClientProvider = { fake },
            target = target,
            suggestionCount = 1,
            fastMode = false,
            verifier = {
                VerificationResult(ok = true, afterModelJson = it.modelJson!!, afterMetrics = OptimizeMetrics(safetyFactor = 2.4))
            }
        )
        session.run()
        val done = session.state.value as OptimizeState.Done
        assertEquals(1, fake.calls.size)
        assertTrue(done.verified[0].verified)
        assertTrue(done.verified[0].diff.addedMemberIds.contains(5))
        assertTrue(done.verified[0].diff.modifiedMemberIds.contains(1))
    }

    @Test
    fun `O-07 patch 应用失败自动回退完整模型请求`() = runTest {
        val target = OptimizeTarget(
            kind = OptimizeKind.FRAME,
            modelJson = AppJson.encodeToString(FrameModel.serializer(), frame(baseModel)),
            summary = "危险",
            before = OptimizeMetrics(safetyFactor = 1.0)
        )
        // 第一次回复：patch 引用不存在的杆件 → 应用失败；第二次回复：完整模型 → 成功
        val badPatch = """{"modify_members":[{"id":99,"width_b":120}]}"""
        val fake = FakeAiClient(listOf(patchReplyJson(badPatch), replyJson(optimizedModel)))
        val session = OptimizeSession(
            aiClientProvider = { fake },
            target = target,
            suggestionCount = 1,
            fastMode = false,
            verifier = {
                VerificationResult(ok = true, afterModelJson = it.modelJson!!, afterMetrics = OptimizeMetrics(safetyFactor = 2.4))
            }
        )
        session.run()
        val done = session.state.value as OptimizeState.Done
        assertEquals(2, fake.calls.size)
        // 第二次请求须带"改输出完整模型"的回退指令（不升思考档：沿用同一客户端）
        assertTrue(fake.calls[1].last().content.contains("完整"))
        assertTrue(done.verified[0].verified)
        assertTrue(done.verified[0].diff.addedMemberIds.contains(5))
    }

    @Test
    fun `O-07 空补丁与原模型全等时按失败回退完整模型请求`() = runTest {
        val target = OptimizeTarget(
            kind = OptimizeKind.FRAME,
            modelJson = AppJson.encodeToString(FrameModel.serializer(), frame(baseModel)),
            summary = "危险",
            before = OptimizeMetrics(safetyFactor = 1.0)
        )
        // v1.2.45 实测：qwen 在 none 档返回合法但全空的补丁 → 合成结果与原模型一致，
        // 必须按失败处理并回退"改输出完整模型"重发，而不是静默丢弃方案
        val emptyPatch = """{"add_nodes":[],"add_members":[],"add_supports":[],"modify_members":[]}"""
        val fake = FakeAiClient(listOf(patchReplyJson(emptyPatch), replyJson(optimizedModel)))
        val session = OptimizeSession(
            aiClientProvider = { fake },
            target = target,
            suggestionCount = 1,
            fastMode = false,
            verifier = {
                VerificationResult(ok = true, afterModelJson = it.modelJson!!, afterMetrics = OptimizeMetrics(safetyFactor = 2.4))
            }
        )
        session.run()
        val done = session.state.value as OptimizeState.Done
        assertEquals(2, fake.calls.size)
        assertTrue(fake.calls[1].last().content.contains("完整"))
        assertTrue(done.verified[0].verified)
    }

    // ------------------------------------------------------------------
    // 会话闭环：AI → 校验 → 有限元验证 → 差异
    // ------------------------------------------------------------------
    @Test
    fun `OptimizeSession 方案验证成功并产出差异`() = runTest {
        val base = frame(baseModel)
        val target = OptimizeTarget(
            kind = OptimizeKind.FRAME,
            modelJson = AppJson.encodeToString(FrameModel.serializer(), base),
            summary = "安全系数 1.1，危险",
            before = OptimizeMetrics(safetyFactor = 1.1, maxDispMm = 8.0)
        )
        val session = OptimizeSession(
            aiClientProvider = { FakeAiClient(listOf(replyJson(optimizedModel))) },
            target = target,
            suggestionCount = 1,
            verifier = {
                VerificationResult(
                    ok = true,
                    afterModelJson = it.modelJson!!,
                    afterMetrics = OptimizeMetrics(safetyFactor = 2.4, maxDispMm = 4.0)
                )
            }
        )
        session.run()
        val done = session.state.value as OptimizeState.Done
        assertEquals(1, done.verified.size)
        assertTrue(done.verified[0].verified)
        assertTrue(done.verified[0].diff.addedMemberIds.contains(5))
        assertEquals(2.4, done.verified[0].after.safetyFactor)
    }

    @Test
    fun `OptimizeSession 验证失败时标记未通过`() = runTest {
        val base = frame(baseModel)
        val target = OptimizeTarget(
            kind = OptimizeKind.FRAME,
            modelJson = AppJson.encodeToString(FrameModel.serializer(), base),
            summary = "危险",
            before = OptimizeMetrics(safetyFactor = 1.0)
        )
        val session = OptimizeSession(
            aiClientProvider = { FakeAiClient(listOf(replyJson(optimizedModel))) },
            target = target,
            suggestionCount = 1,
            verifier = { VerificationResult(ok = false, failureReason = "求解不收敛") }
        )
        session.run()
        val done = session.state.value as OptimizeState.Done
        assertFalse(done.verified[0].verified)
        assertEquals("求解不收敛", done.verified[0].failureReason)
    }

    @Test
    fun `OptimizeSession AI回复不可解析时进入失败态`() = runTest {
        val session = OptimizeSession(
            aiClientProvider = { FakeAiClient(listOf("抱歉我无法给出结构化结果", "抱歉，仍无法给出结构化结果")) },
            target = OptimizeTarget(
                kind = OptimizeKind.FRAME,
                modelJson = AppJson.encodeToString(FrameModel.serializer(), frame(baseModel)),
                summary = "危险",
                before = OptimizeMetrics(safetyFactor = 1.0)
            ),
            suggestionCount = 1,
            verifier = { error("不应被调用") }
        )
        session.run()
        assertTrue(session.state.value is OptimizeState.Failed)
    }

    @Test
    fun `OptimizeSession 方案数量限制在1到3`() {
        val target = OptimizeTarget(
            kind = OptimizeKind.FRAME,
            modelJson = AppJson.encodeToString(FrameModel.serializer(), frame(baseModel)),
            summary = "x"
        )
        assertEquals(3, OptimizeSession({ FakeAiClient(emptyList()) }, target, 9) {
            VerificationResult(false)
        }.count)
        assertEquals(1, OptimizeSession({ FakeAiClient(emptyList()) }, target, 0) {
            VerificationResult(false)
        }.count)
    }

    @Test
    fun `leanModelJson 省略默认字段且往返语义不变`() {
        val base = frame(baseModel)
        val full = AppJson.encodeToString(FrameModel.serializer(), base)
        val lean = OptimizeSession.leanModelJson(OptimizeKind.FRAME, full)
        assertTrue(lean.length < full.length)
        assertFalse(lean.contains("outer_diameter_mm"))
        assertEquals(base.copy(case_meta = null), AppJson.decodeFromString(FrameModel.serializer(), lean))
    }

    @Test
    fun `OptimizeSession 多方案逐个并发请求并按序合并`() = runTest {
        val target = OptimizeTarget(
            kind = OptimizeKind.FRAME,
            modelJson = AppJson.encodeToString(FrameModel.serializer(), frame(baseModel)),
            summary = "危险",
            before = OptimizeMetrics(safetyFactor = 1.0)
        )
        val fake = FakeAiClient(listOf(replyJson(optimizedModel), replyJson(optimizedModel)))
        val session = OptimizeSession(
            aiClientProvider = { fake },
            target = target,
            suggestionCount = 2,
            verifier = {
                VerificationResult(ok = true, afterModelJson = it.modelJson!!, afterMetrics = OptimizeMetrics(safetyFactor = 2.2))
            }
        )
        session.run()
        val done = session.state.value as OptimizeState.Done
        assertEquals(2, fake.calls.size)
        assertTrue(fake.calls[0].last().content.contains("方案 1"))
        assertTrue(fake.calls[1].last().content.contains("方案 2"))
        assertEquals(2, done.verified.size)
    }

    @Test
    fun `OptimizeSession 快速模式把优化通道切到 none 思考档`() = runTest {
        val target = OptimizeTarget(
            kind = OptimizeKind.FRAME,
            modelJson = AppJson.encodeToString(FrameModel.serializer(), frame(baseModel)),
            summary = "危险",
            before = OptimizeMetrics(safetyFactor = 1.0)
        )
        val efforts = mutableListOf<String?>()
        val session = OptimizeSession(
            aiClientProvider = { EffortFake(replyJson(optimizedModel), efforts) },
            target = target,
            suggestionCount = 1,
            fastMode = true,
            verifier = {
                VerificationResult(ok = true, afterModelJson = it.modelJson!!, afterMetrics = OptimizeMetrics(safetyFactor = 2.5))
            }
        )
        session.run()
        assertTrue(session.state.value is OptimizeState.Done)
        assertEquals(listOf("none"), efforts)
    }

    @Test
    fun `OptimizeSession 关闭快速模式沿用原客户端思考档`() = runTest {
        val target = OptimizeTarget(
            kind = OptimizeKind.FRAME,
            modelJson = AppJson.encodeToString(FrameModel.serializer(), frame(baseModel)),
            summary = "危险",
            before = OptimizeMetrics(safetyFactor = 1.0)
        )
        val efforts = mutableListOf<String?>()
        val session = OptimizeSession(
            aiClientProvider = { EffortFake(replyJson(optimizedModel), efforts) },
            target = target,
            suggestionCount = 1,
            fastMode = false,
            verifier = {
                VerificationResult(ok = true, afterModelJson = it.modelJson!!, afterMetrics = OptimizeMetrics(safetyFactor = 2.5))
            }
        )
        session.run()
        // 未调用 withReasoningEffort：记录到的 current 为 null（沿用配置档）
        assertEquals(listOf<String?>(null), efforts)
    }

    @Test
    fun `OptimizeSession 多方案流水线先完成先呈现且按编号顺序合并`() = runTest {
        val target = OptimizeTarget(
            kind = OptimizeKind.FRAME,
            modelJson = AppJson.encodeToString(FrameModel.serializer(), frame(baseModel)),
            summary = "危险",
            before = OptimizeMetrics(safetyFactor = 1.0)
        )
        // 方案 1 的求解验证慢（3s，大于 1.2s 错峰间隔）、方案 2 快：方案 2 应先进入 Running，Done 仍按 1→2 排序
        val fake = FakeAiClient(
            listOf(
                replyJson(optimizedModel).replace("新增斜撑0", "慢方案"),
                replyJson(optimizedModel).replace("新增斜撑0", "快方案")
            )
        )
        val session = OptimizeSession(
            aiClientProvider = { fake },
            target = target,
            suggestionCount = 2,
            fastMode = false,
            verifier = { s ->
                if (s.title == "慢方案") delay(3000)
                VerificationResult(ok = true, afterModelJson = s.modelJson!!, afterMetrics = OptimizeMetrics(safetyFactor = 2.2))
            }
        )
        val states = mutableListOf<OptimizeState>()
        val collect = launch { session.state.toList(states) }
        session.run()
        collect.cancel()
        val partials = states.filterIsInstance<OptimizeState.Running>().map { r -> r.verified.map { it.suggestion.title } }
        assertTrue("方案 2 完成后应立即以 Running 呈现", partials.contains(listOf("快方案")))
        val done = session.state.value as OptimizeState.Done
        assertEquals(listOf("慢方案", "快方案"), done.verified.map { it.suggestion.title })
    }

    /** 记录 withReasoningEffort 切档、固定返回优化回复的假客户端（efforts 跨副本共享） */
    private class EffortFake(
        private val reply: String,
        val efforts: MutableList<String?> = mutableListOf(),
        private val current: String? = null
    ) : AiClient {
        override fun withReasoningEffort(effort: String): AiClient = EffortFake(reply, efforts, effort)
        override suspend fun complete(
            messages: List<LlmMessage>, jsonMode: Boolean, seedOverride: Int?, tag: String?
        ): String {
            efforts.add(current)
            return reply
        }
    }
}
