package com.pocketsim.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多候选建模（AC-3 / AC-7 / AC-8）：ChatSession 首轮生成、seed 多样性、
 * 修改/单模型路径不变、异常隔离、差异过滤。
 */
class ChatSessionCandidateTest {

    /** 已知合法的 2 节点 1 杆悬臂搁板（复用 ParamFlowTest 同款，parseFrame 校验通过） */
    private val ready2Node = """{"ready":true,"questions":[],"model":{"name":"悬臂搁板",
        "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":400,"y":0}],
        "members":[{"id":1,"i":1,"j":2,"width_b":40,"depth_h":18,"material":"松木"}],
        "supports":[{"node":1,"type":"fixed"}],"loads":[{"type":"force","node":2,"fx":0,"fy":-294}]}}"""

    /** N 节点悬臂梁 JSON（固定端 node1，自由端加载）——与 ready2Node 同拓扑，校验应通过 */
    private fun beamJson(nodeCount: Int): String {
        val nodes = (1..nodeCount).joinToString(",") { """{"id":$it,"x":${(it - 1) * 100},"y":0}""" }
        val members = (1 until nodeCount).joinToString(",") {
            """{"id":$it,"i":$it,"j":${it + 1},"width_b":40,"depth_h":18,"material":"松木"}"""
        }
        return """{"ready":true,"questions":[],"model":{"name":"梁","nodes":[$nodes],"members":[$members],
            "supports":[{"node":1,"type":"fixed"}],"loads":[{"type":"force","node":$nodeCount,"fx":0,"fy":-294}]}}"""
    }

    private fun newSession(
        client: AiClient,
        candidateCount: Int = 3,
        divergence: Boolean = true,
        regenAnchor: FrameModel? = null,
        stabilityChecker: suspend (FrameModel) -> FrameStability? = { null },
        onCandidates: (List<ModelCandidate>) -> Unit = {},
        onFrame: (FrameModel) -> Unit = {}
    ) = ChatSession(
        aiClientProvider = { client },
        greeting = "你好",
        candidateCountProvider = { candidateCount },
        candidateDivergenceEnabledProvider = { divergence },
        regenAnchor = regenAnchor,
        stabilityChecker = stabilityChecker,
        onCandidatesReady = onCandidates,
        onFrameReady = onFrame
    )

    /** 与 ready2Node 同拓扑的合法 2 节点悬臂梁，仅名称不同（供 checker 按名判别） */
    private fun beamNamed(name: String): String = """{"ready":true,"questions":[],"model":{"name":"$name",
        "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":400,"y":0}],
        "members":[{"id":1,"i":1,"j":2,"width_b":40,"depth_h":18,"material":"松木"}],
        "supports":[{"node":1,"type":"fixed"}],"loads":[{"type":"force","node":2,"fx":0,"fy":-294}]}}"""

    /** 按 seedOverride 映射响应的假客户端，并记录所有 seedOverride（基准 = null） */
    private fun seedClient(baseSeed: Int, responseFor: (Int?) -> String) = object : AiClient {
        val seedOverrides = mutableListOf<Int?>()
        override suspend fun complete(messages: List<LlmMessage>, jsonMode: Boolean, seedOverride: Int?, tag: String?): String {
            seedOverrides.add(seedOverride)
            return responseFor(seedOverride)
        }
        override fun stableSeedOf(messages: List<LlmMessage>): Int = baseSeed
    }

    // TR-5.1：首轮 3 候选，后 2 个 seed 各异且与基准不同
    @Test
    fun `首轮建模生成3候选 后两个seed各异且与基准不同`() = runTest {
        var got: List<ModelCandidate>? = null
        var frameReady: FrameModel? = null
        val client = FakeAiClient(List(3) { ready2Node })
        val session = newSession(client, candidateCount = 3,
            onCandidates = { got = it }, onFrame = { frameReady = it })

        session.send("帮我建模一个悬臂搁板")

        assertEquals("应得到 3 个候选", 3, got!!.size)
        assertEquals("应调用 3 次 LLM", 3, client.calls.size)
        // 并发后调用顺序不再确定：基准（seedOverride=null，走 stableSeed）与补全同时起跑，
        // 改用集合断言，不依赖下标顺序
        val seeds = client.seedOverrides
        assertEquals("恰好 1 次基准调用（seedOverride=null，走 stableSeed）", 1, seeds.count { it == null })
        val extraSeeds = seeds.filterNotNull()
        assertEquals("2 个补全应带 seedOverride", 2, extraSeeds.size)
        assertNotEquals("两个补全 seed 应不同", extraSeeds[0], extraSeeds[1])
        assertNull("多候选路径不应触发 onFrameReady", frameReady)
    }

    // TR-5.2：修改模型（regenAnchor 非空）不生成多候选，只调 1 次
    @Test
    fun `修改模型路径不生成多候选 只调用一次`() = runTest {
        var got: List<ModelCandidate>? = null
        var frameReady: FrameModel? = null
        val client = FakeAiClient(listOf(ready2Node))
        val session = newSession(client, candidateCount = 3, regenAnchor = FrameModel(name = "锚定基准"),
            onCandidates = { got = it }, onFrame = { frameReady = it })

        session.send("把荷载改成 50kg")

        assertEquals("修改路径只调 1 次 LLM", 1, client.calls.size)
        assertNotNull("应走 onFrameReady", frameReady)
        assertNull("修改路径不应触发多候选", got)
    }

    // TR-5.3：候选数=1 走单模型路径
    @Test
    fun `候选数为1时走单模型路径`() = runTest {
        var got: List<ModelCandidate>? = null
        var frameReady: FrameModel? = null
        val client = FakeAiClient(listOf(ready2Node))
        val session = newSession(client, candidateCount = 1,
            onCandidates = { got = it }, onFrame = { frameReady = it })

        session.send("建模")

        assertEquals(1, client.calls.size)
        assertNotNull(frameReady)
        assertNull(got)
    }

    // TR-5.4：单候选异常被隔离，其余候选正常汇总（该槽位首次与重试均失败）
    @Test
    fun `单候选异常被隔离 其余候选正常`() = runTest {
        var got: List<ModelCandidate>? = null
        val client = object : AiClient {
            override suspend fun complete(
                messages: List<LlmMessage>, jsonMode: Boolean, seedOverride: Int?, tag: String?
            ): String {
                // baseSeed=100：seed 101 的槽位首次与重试（111）都失败，另一个候选正常
                if (seedOverride == 101 || seedOverride == 111) throw RuntimeException("boom")
                return ready2Node
            }
            override fun stableSeedOf(messages: List<LlmMessage>): Int = 100
        }
        val session = newSession(client, candidateCount = 3, onCandidates = { got = it })

        session.send("建模") // 不应抛出

        assertEquals("异常候选被跳过后应剩 2 个", 2, got!!.size)
    }

    // 补全候选首次失败（超时/反问）后自动换 seed 重试成功，不应让用户只剩基准 1 个
    @Test
    fun `补全候选失败后自动重试 仍得到3个候选`() = runTest {
        var got: List<ModelCandidate>? = null
        val client = object : AiClient {
            override suspend fun complete(
                messages: List<LlmMessage>, jsonMode: Boolean, seedOverride: Int?, tag: String?
            ): String {
                if (seedOverride == 101) throw RuntimeException("偶发超时")
                return ready2Node
            }
            override fun stableSeedOf(messages: List<LlmMessage>): Int = 100
        }
        val session = newSession(client, candidateCount = 3, onCandidates = { got = it })

        session.send("建模")

        assertEquals("重试成功后应得到 3 个候选", 3, got!!.size)
    }

    // AI 先反问（ready=false）、用户回答后成功建模时，仍应生成候选（否则用户答完问题看不到选项卡）。
    // 并发后调用顺序不定，按 seed 路由：首轮基准（seedOverride==null）返回反问，其余一律返回就绪模型。
    @Test
    fun `反问轮回答后成功建模仍生成候选`() = runTest {
        var got: List<ModelCandidate>? = null
        val askJson = """{"ready":false,"questions":["材料是什么？"],"answer":""}"""
        var baseAsked = false
        val client = object : AiClient {
            override suspend fun complete(
                messages: List<LlmMessage>, jsonMode: Boolean, seedOverride: Int?, tag: String?
            ): String {
                if (seedOverride == null && !baseAsked) {
                    baseAsked = true
                    return askJson
                }
                return ready2Node
            }
            override fun stableSeedOf(messages: List<LlmMessage>): Int = 100
        }
        val session = newSession(client, candidateCount = 3, onCandidates = { got = it })

        session.send("帮我建模一个悬挑长凳")
        assertNull("反问轮不应出候选", got)

        session.send("松木")
        assertNotNull("回答后成功建模应出候选", got)
        assertEquals("应生成 3 个候选", 3, got!!.size)
    }

    // TR-5.5：差异超阈值的候选被过滤，仅剩基准
    @Test
    fun `差异超阈值候选被过滤 仅剩基准`() = runTest {
        var got: List<ModelCandidate>? = null
        // 基准 2 节点；两个 7 节点候选（节点差 5 > 3）应被过滤
        val client = FakeAiClient(listOf(ready2Node, beamJson(7), beamJson(7)))
        val session = newSession(client, candidateCount = 3, divergence = true,
            onCandidates = { got = it })

        session.send("建模")

        assertEquals("过滤后应只剩基准 1 个", 1, got!!.size)
    }

    // 差异控制关闭时不过滤
    @Test
    fun `关闭差异控制时不过滤`() = runTest {
        var got: List<ModelCandidate>? = null
        val client = FakeAiClient(listOf(ready2Node, beamJson(7), beamJson(7)))
        val session = newSession(client, candidateCount = 3, divergence = false,
            onCandidates = { got = it })

        session.send("建模")

        assertEquals("关闭差异控制应保留全部 3 个", 3, got!!.size)
    }

    // 几何不变性预检（P0）：不稳候选被剔除，该槽位换 seed 重试一次，稳定结果保留
    @Test
    fun `不稳候选被剔除并换seed重试 稳定重试结果保留`() = runTest {
        var got: List<ModelCandidate>? = null
        // baseSeed=100：槽1 首次(101)不稳 → 重试(111)稳定；槽2(102)稳定；基准稳定
        val client = seedClient(100) { seed ->
            when (seed) {
                101 -> beamNamed("不稳A")
                111 -> beamNamed("稳定A")
                102 -> beamNamed("稳定B")
                else -> ready2Node
            }
        }
        val session = newSession(client, candidateCount = 3,
            stabilityChecker = { m -> if (m.name.startsWith("不稳")) FrameStability("mechanism", "t") else null },
            onCandidates = { got = it })

        session.send("建模")

        val names = got!!.map { (it as ModelCandidate.Frame).model.name }
        assertEquals("基准 + 槽2 + 槽1重试 = 3 个", 3, names.size)
        assertEquals("不稳候选不应出现在结果里", false, names.any { it.startsWith("不稳") })
        assertEquals("调用：基准 + 槽1 + 槽2 + 槽1重试", 4, client.seedOverrides.size)
    }

    // 几何不变性预检（P0）：全部候选不稳时回退基准，绝不返回空（让用户在预览页看到错误）
    @Test
    fun `全部候选不稳时回退基准 不返回空`() = runTest {
        var got: List<ModelCandidate>? = null
        val client = seedClient(100) { ready2Node }  // 模型合法，但 checker 全部判不稳
        val session = newSession(client, candidateCount = 3,
            stabilityChecker = { FrameStability("near_mechanism", "t") },
            onCandidates = { got = it })

        session.send("建模")

        assertEquals("全不稳时回退基准，至少返回 1 个", 1, got!!.size)
        // 基准 + 基准重试(200) + 槽1(101) + 槽2(102) + 槽1重试(111) + 槽2重试(112)
        assertEquals("基准与每个槽位都重试 1 次", 6, client.seedOverrides.size)
    }

    // 几何不变性预检（P0）：checker 全返回 null（稳定/预检异常放行）时行为与旧版完全一致
    @Test
    fun `checker全返回null时行为不变 得3候选无额外重试`() = runTest {
        var got: List<ModelCandidate>? = null
        val client = FakeAiClient(List(3) { ready2Node })
        val session = newSession(client, candidateCount = 3,
            stabilityChecker = { null },
            onCandidates = { got = it })

        session.send("建模")

        assertEquals(3, got!!.size)
        assertEquals("无重试：基准 + 2 补全 = 3 次调用", 3, client.seedOverrides.size)
    }

    // 提速契约：基准与补全在同一协程域内重叠起跑（而非基准串行跑完再并发补全两段相加）。
    // 补全有 delay(300) 错峰，故基准 complete 挂起需 > 300ms（取 1000）才能让补全在基准结束前起跑。
    // 记录 (seedOverride, start/end) 事件；断言存在补全的 start 早于基准的 end。
    @Test
    fun `基准与补全重叠起跑 补全起跑早于基准结束`() = runTest {
        var got: List<ModelCandidate>? = null
        val events = mutableListOf<Pair<Int?, String>>()
        val client = object : AiClient {
            override suspend fun complete(
                messages: List<LlmMessage>, jsonMode: Boolean, seedOverride: Int?, tag: String?
            ): String {
                events.add(seedOverride to "start")
                delay(1000) // 挂起 > 补全错峰 300ms，使基准与补全在虚拟时间上交错
                events.add(seedOverride to "end")
                return ready2Node
            }
            override fun stableSeedOf(messages: List<LlmMessage>): Int = 100
        }
        val session = newSession(client, candidateCount = 3, onCandidates = { got = it })

        session.send("建模")

        assertNotNull("应正常生成候选", got)
        val baseEndIdx = events.indexOfFirst { it.first == null && it.second == "end" }
        assertTrue("基准应完成（记录到 end 事件）", baseEndIdx >= 0)
        val hasOverlap = events.indices.any { idx ->
            val e = events[idx]
            e.first != null && e.second == "start" && idx < baseEndIdx
        }
        assertTrue("补全应在基准结束前起跑（并发重叠，而非串行两段）", hasOverlap)
    }

    // C2 共享几何观测：基准 observation 非空时，补全失败重试的 messages 应注入 observationHint，
    // 让补全复用基准观测、只生成坐标（提速 + 一致性）。
    @Test
    fun `补全重试时注入基准 observation 提示`() = runTest {
        var got: List<ModelCandidate>? = null
        val baseWithObs = """{"ready":true,"observation":"照片可见 A 字架两腿外撇",
            "questions":[],"model":{"name":"悬臂","nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":400,"y":0}],
            "members":[{"id":1,"i":1,"j":2,"width_b":40,"depth_h":18,"material":"松木"}],
            "supports":[{"node":1,"type":"fixed"}],"loads":[{"type":"force","node":2,"fx":0,"fy":-294}]}}"""
        val callLog = mutableListOf<Pair<Int?, List<LlmMessage>>>()
        val client = object : AiClient {
            override suspend fun complete(
                messages: List<LlmMessage>, jsonMode: Boolean, seedOverride: Int?, tag: String?
            ): String {
                callLog.add(seedOverride to messages)
                // baseSeed=100：槽1 首次(101)失败 → 触发带观测的重试(111)，其余成功
                if (seedOverride == 101) throw RuntimeException("限流")
                return baseWithObs
            }
            override fun stableSeedOf(messages: List<LlmMessage>): Int = 100
        }
        val session = newSession(client, candidateCount = 2, onCandidates = { got = it })

        session.send("建模")

        assertNotNull("应正常生成候选", got)
        val retry = callLog.firstOrNull { it.first == 111 }
        assertNotNull("槽1 首次失败后应触发 seed=111 重试", retry)
        val retryText = retry!!.second.joinToString("\n") { it.content }
        assertTrue("重试 messages 应注入基准观测提示（observationHint）",
            retryText.contains("（生成备选模型）"))
        assertTrue("重试 messages 应携带基准观测原文", retryText.contains("A 字架两腿外撇"))
        // 首次补全（101）不应带观测注入（基准尚未就绪、观测还取不到）
        val firstExtra = callLog.firstOrNull { it.first == 101 }
        assertNotNull("应有槽1 首次调用", firstExtra)
        assertFalse("首次补全不应注入观测（基准未就绪）",
            firstExtra!!.second.joinToString("\n") { it.content }.contains("（生成备选模型）"))
    }

    // P1-4：单模型路径（候选数=1）也做几何预检——首模型不稳 → 换 seed 重试一次并采用稳定结果
    @Test
    fun `单模型路径不稳时换seed重试 采用稳定结果`() = runTest {
        var frameReady: FrameModel? = null
        val client = FakeAiClient(listOf(beamNamed("不稳"), ready2Node))
        val session = newSession(client, candidateCount = 1,
            stabilityChecker = { m -> if (m.name.startsWith("不稳")) FrameStability("mechanism", "t") else null },
            onFrame = { frameReady = it })

        session.send("建模")

        assertNotNull(frameReady)
        assertEquals("重试就绪且稳定 → 应采用第二个模型", "悬臂搁板", frameReady!!.name)
        assertEquals("不稳后应重试一次（共 2 次 LLM 调用）", 2, client.calls.size)
    }

    // P1-4：单模型路径预检稳定时不多调 LLM；强锚定（修改模型）路径跳过预检
    @Test
    fun `单模型路径稳定时不重试 强锚定跳过预检`() = runTest {
        var frameReady: FrameModel? = null
        val client = FakeAiClient(listOf(ready2Node))
        val session = newSession(client, candidateCount = 1,
            stabilityChecker = { FrameStability("mechanism", "t") },  // 全判不稳
            onFrame = { frameReady = it })
        session.send("建模")
        // 首模型不稳 → 重试（第 2 次响应列表耗尽会抛越界？不会：FakeAiClient 越界抛 IndexOutOfBounds，
        // 被 runCatching 捕获 → 保留原模型）
        assertNotNull("重试失败也应保留原模型跳转", frameReady)

        var anchored: FrameModel? = null
        val client2 = FakeAiClient(listOf(ready2Node))
        val session2 = newSession(client2, candidateCount = 1, regenAnchor = FrameModel(name = "锚"),
            stabilityChecker = { FrameStability("mechanism", "t") },
            onFrame = { anchored = it })
        session2.send("改一下")
        assertEquals("强锚定路径跳过预检：只调 1 次", 1, client2.calls.size)
        assertNotNull(anchored)
    }

    // 基准反问（ready=false）时：取消已起跑的补全、走 handleFrameParse 追问，不返回候选。
    @Test
    fun `基准反问时取消补全并走追问 不返回候选`() = runTest {
        var got: List<ModelCandidate>? = null
        val askJson = """{"ready":false,"questions":["材料是什么？"],"answer":""}"""
        val client = object : AiClient {
            override suspend fun complete(
                messages: List<LlmMessage>, jsonMode: Boolean, seedOverride: Int?, tag: String?
            ): String {
                delay(10)
                return askJson
            }
            override fun stableSeedOf(messages: List<LlmMessage>): Int = 100
        }
        val session = newSession(client, candidateCount = 3, onCandidates = { got = it })

        session.send("帮我建模一个悬挑长凳")

        assertNull("基准反问不应出候选", got)
        assertTrue("应向用户追问材料", session.messages.value.last().text.contains("材料"))
    }
}
