package com.pocketsim.app

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * LlmClient 降随机性契约（FR-5 / AC-8）：
 *  - 建模通道（jsonMode=true）temperature=0.1 + 稳定 seed；
 *  - 纯答疑（jsonMode=false）temperature=0.2 且不带 seed；
 *  - 服务因 seed 返回 4xx（非 401/403）时自动去 seed 重试一次。
 * 用 OkHttp 拦截器模拟 HTTP，无需新增 mockwebserver 依赖。
 */
class LlmClientTest {

    private val messages = listOf(
        LlmMessage("system", "你是结构建模助手"),
        LlmMessage("user", "建模一个悬臂搁板")
    )

    private fun openAiContent(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":$content}}]}"""

    private fun newClient(http: OkHttpClient) = LlmClientImpl(
        baseUrl = "https://api.example.com/v1",
        apiKey = "k",
        model = "m",
        language = "zh",
        httpClient = http
    )

    // ------------------------------------------------------------------
    // TR-5.1：请求体 temperature / seed 契约
    // ------------------------------------------------------------------

    @Test
    fun `buildRequestBody 建模通道 temperature 0_1 且带稳定 seed`() {
        val client = newClient(OkHttpClient())
        val body = client.buildRequestBody(messages, jsonMode = true, withSeed = true)
        val obj = body.jsonObject
        assertEquals("建模 temperature 应为 0.1", 0.1, obj["temperature"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertTrue("建模请求应带 seed 字段", obj.containsKey("seed"))
        val seed = obj["seed"]!!.jsonPrimitive.content.toInt()
        assertTrue("seed 应为非负整数", seed >= 0)
        // 同输入 → 同 seed（稳定性核心）
        val again = client.buildRequestBody(messages, jsonMode = true, withSeed = true)
            .jsonObject["seed"]!!.jsonPrimitive.content.toInt()
        assertEquals("同输入应得同 seed", seed.toLong(), again.toLong())
    }

    @Test
    fun `buildRequestBody 纯答疑 temperature 0_2 且不带 seed`() {
        val client = newClient(OkHttpClient())
        val body = client.buildRequestBody(messages, jsonMode = false, withSeed = false)
        val obj = body.jsonObject
        assertEquals("答疑 temperature 应保持 0.2", 0.2, obj["temperature"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertFalse("答疑请求不应带 seed", obj.containsKey("seed"))
    }

    // ------------------------------------------------------------------
    // TR-5.2：seed 触发 4xx → 去 seed 重试一次成功
    // ------------------------------------------------------------------

    @Test
    fun `complete seed 触发 4xx 时去 seed 重试成功`() = runTest {
        var callCount = 0
        var retryHadSeed = true
        val interceptor = Interceptor { chain ->
            callCount++
            val hasSeed = requestText(chain).contains("\"seed\"")
            when (callCount) {
                1 -> {
                    assertTrue("首次请求应带 seed", hasSeed)
                    mockResponse(chain.request(), 400,
                        """{"error":{"message":"unrecognized field: seed"}}""")
                }
                else -> {
                    retryHadSeed = hasSeed
                    mockResponse(chain.request(), 200, openAiContent("\"重试成功\""))
                }
            }
        }
        val client = newClient(OkHttpClient.Builder().addInterceptor(interceptor).build())
        val content = client.complete(messages, jsonMode = true)
        assertEquals("去 seed 重试成功应返回内容", "重试成功", content)
        assertEquals("应恰好重试一次（共 2 次请求）", 2, callCount)
        assertFalse("重试请求应已去掉 seed", retryHadSeed)
    }

    // A3：Kimi 可能以 200 + rate_limit_reached_error 体返回组织级并发限流，
    // complete 应识别后退避重试 1 次成功（多候选 3 路并发的关键兜底）。
    @Test
    fun `complete 限流响应体触发退避重试成功`() = runTest {
        var callCount = 0
        val interceptor = Interceptor { chain ->
            callCount++
            when (callCount) {
                1 -> mockResponse(chain.request(), 200,
                    """{"error":{"type":"rate_limit_reached_error","message":"触发组织级并发限制"}}""")
                else -> mockResponse(chain.request(), 200, openAiContent("\"重试成功\""))
            }
        }
        val client = newClient(OkHttpClient.Builder().addInterceptor(interceptor).build())
        assertEquals("限流退避后应成功返回内容", "重试成功", client.complete(messages, jsonMode = true))
        assertEquals("应退避重试一次（共 2 次请求）", 2, callCount)
    }

    // 限流重试后仍失败时，必须抛出带原因的可读异常；否则界面兜底会显示
    // 「AI 接口不可用：未知错误」（HttpError 本身没有 message）。
    @Test
    fun `complete 持续限流抛出可读的 429 提示`() = runTest {
        var callCount = 0
        val interceptor = Interceptor { chain ->
            callCount++
            mockResponse(
                chain.request(), 200,
                """{"error":{"type":"rate_limit_reached_error","message":"触发组织级并发限制"}}"""
            )
        }
        val client = newClient(OkHttpClient.Builder().addInterceptor(interceptor).build())
        val ex = try {
            client.complete(messages, jsonMode = true)
            null
        } catch (e: IOException) {
            e
        }
        assertNotNull("持续限流应抛 IOException", ex)
        assertTrue("提示应包含状态码 429", ex!!.message!!.contains("429"))
        assertTrue("提示应说明并发超限原因", ex.message!!.contains("并发超限"))
        assertEquals("限流应退避重试一次（共 2 次请求）", 2, callCount)
    }

    @Test
    fun `complete 授权错误 401 不重试且抛授权提示`() = runTest {        var callCount = 0
        val interceptor = Interceptor { chain ->
            callCount++
            mockResponse(chain.request(), 401,
                """{"error":{"message":"unauthorized"}}""")
        }
        val client = newClient(OkHttpClient.Builder().addInterceptor(interceptor).build())
        val ex = try {
            client.complete(messages, jsonMode = true)
            null
        } catch (e: IOException) {
            e
        }
        assertNotNull("401 应抛 IOException", ex)
        assertEquals("401 不应去 seed 重试，只请求 1 次", 1, callCount)
        assertTrue("错误信息应含授权失效提示", ex!!.message!!.contains("授权失效"))
    }

    // ------------------------------------------------------------------
    // TR-3.1 / TR-3.2：seedOverride 覆盖稳定 seed（多候选多样性）
    // ------------------------------------------------------------------

    @Test
    fun `buildRequestBody seedOverride 覆盖稳定 seed 且不同值产生不同seed`() {
        val client = newClient(OkHttpClient())
        val body = client.buildRequestBody(messages, jsonMode = true, withSeed = true, seedOverride = 123)
        assertEquals("seedOverride=123 应写入请求体",
            123, body.jsonObject["seed"]!!.jsonPrimitive.content.toInt())

        val body2 = client.buildRequestBody(messages, jsonMode = true, withSeed = true, seedOverride = 124)
        assertEquals("seedOverride=124 应写入请求体",
            124, body2.jsonObject["seed"]!!.jsonPrimitive.content.toInt())
        assertNotEquals("不同 override 应产生不同 seed",
            123, body2.jsonObject["seed"]!!.jsonPrimitive.content.toInt())

        // null → 回退稳定 seed
        val stable = client.buildRequestBody(messages, jsonMode = true, withSeed = true)
            .jsonObject["seed"]!!.jsonPrimitive.content.toInt()
        assertNotEquals("null override 应走稳定 seed（不等于 123）", 123, stable)
    }

    // ------------------------------------------------------------------
    // json_schema 结构化输出 + Kimi reasoning_effort + 降级重试
    // ------------------------------------------------------------------

    @Test
    fun `FRAME_OUTPUT_SCHEMA 可加载且顶层 required 含 ready`() {
        val s = FRAME_OUTPUT_SCHEMA
        assertEquals("object", s["type"]!!.jsonPrimitive.content)
        val required = s["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("required 应含 ready", "ready" in required)
        val props = s["properties"]!!.jsonObject
        assertTrue("应含 model 字段", props.containsKey("model"))
        val model = props["model"]!!.jsonObject
        assertTrue("model 应用 anyOf 允许 null", model.containsKey("anyOf"))
        assertTrue("应含 flow/solid 等宽松字段（不破坏其他建模通道）",
            props.containsKey("flow") && props.containsKey("shell"))
    }

    @Test
    fun `buildRequestBody 传 schema 时用 json_schema strict 并设 max_tokens`() {
        val client = newClient(OkHttpClient())
        val obj = client.buildRequestBody(
            messages, jsonMode = true, withSeed = true, schema = FRAME_OUTPUT_SCHEMA
        ).jsonObject
        val rf = obj["response_format"]!!.jsonObject
        assertEquals("json_schema", rf["type"]!!.jsonPrimitive.content)
        val js = rf["json_schema"]!!.jsonObject
        assertEquals("frame_output", js["name"]!!.jsonPrimitive.content)
        assertTrue("strict 应为 true", js["strict"]!!.jsonPrimitive.content.toBoolean())
        assertTrue("schema 字段应内嵌", js.containsKey("schema"))
        assertEquals(8192, obj["max_tokens"]!!.jsonPrimitive.content.toInt())
    }

    // C1：Kimi 域名带 schema 时用 max_completion_tokens（K3.1 Pro 文档字段），
    // 不再传 max_tokens，避免默认 131072 放任过度思考拖慢多候选生成。
    @Test
    fun `buildRequestBody Kimi 域名带 schema 用 max_completion_tokens 而非 max_tokens`() {
        val client = LlmClientImpl(
            baseUrl = "https://api.moonshot.cn/v1", apiKey = "k", model = "kimi-k3.1-pro",
            language = "zh", httpClient = OkHttpClient()
        )
        val obj = client.buildRequestBody(
            messages, jsonMode = true, withSeed = true, schema = FRAME_OUTPUT_SCHEMA
        ).jsonObject
        assertTrue("Kimi 带 schema 应使用 max_completion_tokens", obj.containsKey("max_completion_tokens"))
        assertEquals(8192, obj["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
        assertFalse("Kimi 不应再传 max_tokens", obj.containsKey("max_tokens"))
    }

    // 高速建模：Kimi 域名 + preferHighSpeed → 模型自动追加 -highspeed（同效果 2–3 倍速）
    @Test
    fun `buildRequestBody Kimi 域名开高速模式时模型切换为高速版`() {
        val client = LlmClientImpl(
            baseUrl = "https://api.moonshot.cn/v1", apiKey = "k", model = "kimi-k3.1-pro",
            language = "zh", preferHighSpeed = true, httpClient = OkHttpClient()
        )
        val obj = client.buildRequestBody(messages, jsonMode = true, withSeed = true).jsonObject
        assertEquals("kimi-k3.1-pro-highspeed", obj["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildRequestBody 非 Kimi 域名开高速模式时模型不变`() {
        val client = LlmClientImpl(
            baseUrl = "https://api.example.com/v1", apiKey = "k", model = "some-model",
            language = "zh", preferHighSpeed = true, httpClient = OkHttpClient()
        )
        val obj = client.buildRequestBody(messages, jsonMode = true, withSeed = true).jsonObject
        assertEquals("some-model", obj["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildRequestBody 已是高速版时不重复追加 highspeed 后缀`() {
        val client = LlmClientImpl(
            baseUrl = "https://api.moonshot.cn/v1", apiKey = "k", model = "kimi-k3.1-pro-highspeed",
            language = "zh", preferHighSpeed = true, httpClient = OkHttpClient()
        )
        val obj = client.buildRequestBody(messages, jsonMode = true, withSeed = true).jsonObject
        assertEquals("kimi-k3.1-pro-highspeed", obj["model"]!!.jsonPrimitive.content)
        assertFalse("不应变成 -highspeed-highspeed",
            obj["model"]!!.jsonPrimitive.content.endsWith("-highspeed-highspeed"))
    }

    @Test
    fun `buildRequestBody 不传 schema 时用 json_object 且无 max_tokens`() {
        val client = newClient(OkHttpClient())
        val obj = client.buildRequestBody(messages, jsonMode = true, withSeed = true).jsonObject
        assertEquals("json_object", obj["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("不传 schema 不应设 max_tokens", obj.containsKey("max_tokens"))
    }

    @Test
    fun `buildRequestBody Kimi 域名建模通道附加 reasoning_effort`() {
        val client = LlmClientImpl(
            baseUrl = "https://api.moonshot.cn/v1",
            apiKey = "k", model = "kimi-k3.1-pro",
            language = "zh", reasoningEffort = "low", httpClient = OkHttpClient()
        )
        val obj = client.buildRequestBody(messages, jsonMode = true, withSeed = true).jsonObject
        assertEquals("low", obj["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildRequestBody 非 Kimi 域名不附加 reasoning_effort`() {
        val client = newClient(OkHttpClient())
        val obj = client.buildRequestBody(messages, jsonMode = true, withSeed = true).jsonObject
        assertFalse("非 Kimi 域名不应附加 reasoning_effort", obj.containsKey("reasoning_effort"))
    }

    @Test
    fun `buildRequestBody Kimi 域名不显式传 temperature`() {
        val client = LlmClientImpl(
            baseUrl = "https://api.moonshot.cn/v1",
            apiKey = "k", model = "kimi-k3.1-pro",
            language = "zh", httpClient = OkHttpClient()
        )
        val obj = client.buildRequestBody(messages, jsonMode = true, withSeed = true).jsonObject
        assertFalse("Kimi 域名不应显式传 temperature", obj.containsKey("temperature"))
    }

    @Test
    fun `buildRequestBody 非 Kimi 域名建模 temperature 保持低位`() {
        val client = newClient(OkHttpClient())
        val obj = client.buildRequestBody(messages, jsonMode = true, withSeed = true).jsonObject
        assertEquals(0.1, obj["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun `buildRequestBody 方舟托管 Kimi 模型 temperature 0_6 且不附加 thinking`() {
        val client = LlmClientImpl(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            apiKey = "k", model = "kimi-k3-1-pro-261024",
            language = "zh", httpClient = OkHttpClient()
        )
        val obj = client.buildRequestBody(messages, jsonMode = true, withSeed = true).jsonObject
        assertEquals("方舟托管 Kimi 只接受 temperature=0.6", 0.6,
            obj["temperature"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertFalse("Kimi K3 始终思考，不应附加 thinking:disabled", obj.containsKey("thinking"))
    }

    @Test
    fun `buildRequestBody 方舟豆包模型仍关思考且 temperature 低位`() {
        val client = LlmClientImpl(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            apiKey = "k", model = "doubao-seed-2-0-pro-260215",
            language = "zh", httpClient = OkHttpClient()
        )
        val obj = client.buildRequestBody(messages, jsonMode = true, withSeed = true).jsonObject
        assertEquals(0.1, obj["temperature"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertTrue("豆包深度思考模型仍应附加 thinking:disabled", obj.containsKey("thinking"))
    }

    @Test
    fun `complete schema 触发 400 时降级为 json_object 重试成功`() = runTest {
        var callCount = 0
        var secondHadSchema = true
        val interceptor = Interceptor { chain ->
            callCount++
            val text = requestText(chain)
            when (callCount) {
                1 -> {
                    assertTrue("首次应带 json_schema", text.contains("\"type\":\"json_schema\""))
                    mockResponse(chain.request(), 400,
                        """{"error":{"message":"response_format json_schema is not supported"}}""")
                }
                else -> {
                    secondHadSchema = text.contains("\"type\":\"json_schema\"")
                    mockResponse(chain.request(), 200, openAiContent("\"降级成功\""))
                }
            }
        }
        val client = newClient(OkHttpClient.Builder().addInterceptor(interceptor).build())
        val content = client.complete(
            messages, jsonMode = true, seedOverride = null, schema = FRAME_OUTPUT_SCHEMA
        )
        assertEquals("降级成功", content)
        assertEquals("应降级重试一次（共 2 次）", 2, callCount)
        assertFalse("降级请求应改用 json_object", secondHadSchema)
    }

    // ------------------------------------------------------------------
    // Kimi 流式输出（stream）+ SSE 解析 + 读超时兜底
    // ------------------------------------------------------------------

    @Test
    fun `buildRequestBody stream 为 true 时附加 stream 字段`() {
        val client = newClient(OkHttpClient())
        val obj = client.buildRequestBody(messages, jsonMode = true, withSeed = true, stream = true).jsonObject
        assertTrue("stream=true 应写入请求体", obj["stream"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `buildRequestBody stream 为 false 时不附加 stream 字段`() {
        val client = newClient(OkHttpClient())
        val obj = client.buildRequestBody(messages, jsonMode = true, withSeed = true).jsonObject
        assertFalse("默认不应带 stream 字段", obj.containsKey("stream"))
    }

    @Test
    fun `parseSseContent 累积 content 并忽略 reasoning_content`() {
        val client = newClient(OkHttpClient())
        // 三引号原始字符串：反斜杠原样保留，正好是 JSON 字符串内的转义引号
        val sse = """
            data: {"choices":[{"delta":{"reasoning_content":"思考中"}}]}

            data: {"choices":[{"delta":{"content":"{\"ready\":true"}}]}

            data: {"choices":[{"delta":{"content":",\"model\":null}"}}]}

            data: [DONE]
        """.trimIndent()
        assertEquals("{\"ready\":true,\"model\":null}", client.parseSseContent(sse))
    }

    @Test
    fun `parseSseContent 遇到 error 分片抛 IOException`() {
        val client = newClient(OkHttpClient())
        val ex = try {
            client.parseSseContent("data: {\"error\":{\"message\":\"流式故障\"}}\n"); null
        } catch (e: IOException) { e }
        assertNotNull("error 分片应抛 IOException", ex)
        assertTrue("错误信息应包含原始 message", ex!!.message!!.contains("流式故障"))
    }

    @Test
    fun `parseSseContent 非 SSE 响应降级为普通解析`() {
        val client = newClient(OkHttpClient())
        assertEquals("降级应返回 content", "hi", client.parseSseContent(openAiContent("\"hi\"")))
    }

    @Test
    fun `complete Kimi 域名请求带 stream 并解析 SSE 返回内容`() = runTest {
        var sawStream = false
        val interceptor = Interceptor { chain ->
            sawStream = requestText(chain).contains("\"stream\":true")
            val sse = """
                data: {"choices":[{"delta":{"reasoning_content":"思考"}}]}

                data: {"choices":[{"delta":{"content":"流式成功"}}]}

                data: [DONE]
            """.trimIndent()
            mockResponse(chain.request(), 200, sse)
        }
        val client = LlmClientImpl(
            baseUrl = "https://api.moonshot.cn/v1", apiKey = "k", model = "kimi-k3.1-pro",
            language = "zh", httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
        assertEquals("流式成功", client.complete(messages, jsonMode = true))
        assertTrue("Kimi 请求体应带 stream:true", sawStream)
    }

    @Test
    fun `effectiveReadTimeoutSeconds 各厂商都等于用户设置`() {
        val kimi = LlmClientImpl(
            baseUrl = "https://api.moonshot.cn/v1", apiKey = "k", model = "kimi-k3.1-pro",
            timeoutSeconds = 60, language = "zh", httpClient = OkHttpClient()
        )
        // 用户反馈等待 4 分钟过久：设置多少就是多少，不再对 Kimi 自动放宽
        assertEquals("Kimi 也按用户设置的 60 秒", 60, kimi.effectiveReadTimeoutSeconds())
        val kimiLong = LlmClientImpl(
            baseUrl = "https://api.moonshot.cn/v1", apiKey = "k", model = "kimi-k3.1-pro",
            timeoutSeconds = 200, language = "zh", httpClient = OkHttpClient()
        )
        assertEquals("用户设 200 则 200", 200, kimiLong.effectiveReadTimeoutSeconds())
        assertEquals("非 Kimi 同样是 60", 60, newClient(OkHttpClient()).effectiveReadTimeoutSeconds())
    }

    @Test
    fun `readableMessage 空 message 退回异常类型名`() {
        // 读超时的 SocketTimeoutException 没有 message，不能再显示成「未知错误」
        assertEquals("SocketTimeoutException", java.net.SocketTimeoutException().readableMessage())
        assertEquals("boom", IllegalStateException("boom").readableMessage())
    }

    // ------------------------------------------------------------------
    // v1.2.21：多厂商适配（LlmProvider 判定 + 请求体按厂商塑形）
    // ------------------------------------------------------------------

    @Test
    fun `LlmProvider fromBaseUrl 按域名识别厂商`() {
        assertEquals(LlmProvider.Kimi, LlmProvider.fromBaseUrl("https://api.moonshot.cn/v1"))
        assertEquals(LlmProvider.Kimi, LlmProvider.fromBaseUrl("https://kimi.ai/api/v1"))
        assertEquals(LlmProvider.Doubao, LlmProvider.fromBaseUrl("https://ark.cn-beijing.volces.com/api/v3"))
        assertEquals(LlmProvider.Doubao, LlmProvider.fromBaseUrl("https://ark.byteplus.com/api/v3"))
        assertEquals(LlmProvider.DeepSeek, LlmProvider.fromBaseUrl("https://api.deepseek.com"))
        assertEquals(LlmProvider.DeepSeek, LlmProvider.fromBaseUrl("https://api.deepseek.com/v1"))
        assertEquals(LlmProvider.Custom, LlmProvider.fromBaseUrl("https://api.openai.com/v1"))
        assertEquals(LlmProvider.Custom, LlmProvider.fromBaseUrl("https://api.groq.com/openai/v1"))
        assertEquals(LlmProvider.Custom, LlmProvider.fromBaseUrl("https://api.example.com/v1"))
        // 大小写不敏感
        assertEquals(LlmProvider.Kimi, LlmProvider.fromBaseUrl("HTTPS://API.MOONSHOT.CN/V1"))
    }

    @Test
    fun `LlmProvider 预设默认值正确`() {
        assertEquals("https://api.moonshot.cn/v1", LlmProvider.Kimi.defaultBaseUrl)
        assertEquals("kimi-k3.1-pro", LlmProvider.Kimi.defaultModel)
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", LlmProvider.Doubao.defaultBaseUrl)
        assertEquals("doubao-seed-2-0-pro-260215", LlmProvider.Doubao.defaultModel)
        assertEquals("https://api.deepseek.com", LlmProvider.DeepSeek.defaultBaseUrl)
        assertEquals("deepseek-chat", LlmProvider.DeepSeek.defaultModel)
        assertEquals("", LlmProvider.Custom.defaultBaseUrl)
        assertEquals("", LlmProvider.Custom.defaultModel)
    }

    @Test
    fun `buildRequestBody DeepSeek 域名恒发 reasoning_effort none 建模与答疑都发`() {
        val client = LlmClientImpl(
            baseUrl = "https://api.deepseek.com", apiKey = "k", model = "deepseek-chat",
            language = "zh", httpClient = OkHttpClient()
        )
        val modeling = client.buildRequestBody(messages, jsonMode = true, withSeed = true).jsonObject
        assertEquals("DeepSeek 建模应发 reasoning_effort=none",
            "none", modeling["reasoning_effort"]!!.jsonPrimitive.content)
        val qa = client.buildRequestBody(messages, jsonMode = false, withSeed = false).jsonObject
        assertEquals("DeepSeek 答疑也应发 reasoning_effort=none（恒发，对标 Doubao thinking disabled）",
            "none", qa["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildRequestBody byteplus 域名附加 thinking disabled`() {
        val client = LlmClientImpl(
            baseUrl = "https://ark.byteplus.com/api/v3", apiKey = "k", model = "doubao-seed-2-0-pro",
            language = "zh", httpClient = OkHttpClient()
        )
        val obj = client.buildRequestBody(messages, jsonMode = true, withSeed = true).jsonObject
        assertEquals("byteplus 应识别为 Doubao 并附加 thinking:disabled",
            "disabled", obj["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildRequestBody volces 域名附加 thinking disabled`() {
        val client = LlmClientImpl(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3", apiKey = "k", model = "doubao-seed-2-0-pro",
            language = "zh", httpClient = OkHttpClient()
        )
        val obj = client.buildRequestBody(messages, jsonMode = true, withSeed = true).jsonObject
        assertEquals("volces 应附加 thinking:disabled",
            "disabled", obj["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildRequestBody Custom 域名不发任何厂商专属字段`() {
        // Custom = 通用 OpenAI 兼容，国际用户接 OpenAI/Groq/OpenRouter/本地代理必须保持纯净
        val client = LlmClientImpl(
            baseUrl = "https://api.openai.com/v1", apiKey = "k", model = "gpt-4o-mini",
            language = "en", httpClient = OkHttpClient()
        )
        val modeling = client.buildRequestBody(messages, jsonMode = true, withSeed = true).jsonObject
        assertFalse("Custom 不应发 reasoning_effort", modeling.containsKey("reasoning_effort"))
        assertFalse("Custom 不应发 thinking", modeling.containsKey("thinking"))
        assertFalse("Custom 不应发 max_completion_tokens（用 max_tokens）",
            modeling.containsKey("max_completion_tokens"))
        assertFalse("Custom 不应发 stream", modeling.containsKey("stream"))
        val qa = client.buildRequestBody(messages, jsonMode = false, withSeed = false).jsonObject
        assertFalse("Custom 答疑不应发 reasoning_effort", qa.containsKey("reasoning_effort"))
        assertFalse("Custom 答疑不应发 thinking", qa.containsKey("thinking"))
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // TR-5.3：Base URL 容错 + Anthropic Messages 协议
    // ------------------------------------------------------------------

    @Test
    fun `resolveEndpoint 已带接口路径时不再重复拼接`() {
        assertEquals(
            "https://g.example.com/v1/chat/completions",
            resolveEndpoint("https://g.example.com/v1/chat/completions")!!.url
        )
        assertEquals(
            "填到 /v1 应补 /chat/completions",
            "https://g.example.com/v1/chat/completions",
            resolveEndpoint("https://g.example.com/v1/")!!.url
        )
        val anth = resolveEndpoint("https://g.example.com/v1/messages")!!
        assertEquals("https://g.example.com/v1/messages", anth.url)
        assertEquals(ApiProtocol.ANTHROPIC, anth.protocol)
        assertEquals(
            "anthropic 官方域名应走 messages",
            ApiProtocol.ANTHROPIC,
            resolveEndpoint("https://api.anthropic.com/v1")!!.protocol
        )
        assertEquals(
            "非 http 开头应判为无效",
            null,
            resolveEndpoint("g.example.com/v1")
        )
    }

    @Test
    fun `complete 走 Anthropic 时发送 messages 与 x-api-key 并解析 content 文本`() = runTest {
        var seenUrl = ""
        var seenKey = ""
        var seenBody = ""
        val interceptor = Interceptor { chain ->
            seenUrl = chain.request().url.toString()
            seenKey = chain.request().header("x-api-key").orEmpty()
            seenBody = requestText(chain)
            mockResponse(
                chain.request(), 200,
                """{"id":"m1","type":"message","role":"assistant","content":[{"type":"text","text":"建好了"}]}"""
            )
        }
        val client = LlmClientImpl(
            baseUrl = "https://g.example.com/v1/messages",
            apiKey = "sk-abc",
            model = "claude-haiku-5-5",
            language = "zh",
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
        val content = client.complete(messages, jsonMode = true)
        assertEquals("应解析 content[].text", "建好了", content)
        assertEquals("应请求 /v1/messages", "https://g.example.com/v1/messages", seenUrl)
        assertEquals("应带 x-api-key", "sk-abc", seenKey)
        assertTrue("system 应为顶层字段", seenBody.contains("\"system\""))
        assertTrue("应有 max_tokens", seenBody.contains("\"max_tokens\""))
        assertTrue("模型名应透传", seenBody.contains("claude-haiku-5-5"))
        assertFalse("Anthropic 不应带 response_format", seenBody.contains("response_format"))
        assertFalse("Anthropic 不应带 seed", seenBody.contains("\"seed\""))
    }

    @Test
    fun `parseAnthropicSse 只累加 text_delta 忽略思考内容`() {
        val client = newClient(OkHttpClient())
        val raw = buildString {
            append("event: message_start\n")
            append("data: {\"type\":\"message_start\"}\n\n")
            append("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"想一想\"}}\n\n")
            append("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"{\\\"nodes\\\":\"}}\n\n")
            append("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"[]}\"}}\n\n")
            append("data: {\"type\":\"message_stop\"}\n")
        }
        assertEquals("{\"nodes\":[]}", client.parseAnthropicSse(raw))
    }

    @Test
    fun `parseAnthropicSse 遇到 error 事件抛出可读异常`() {
        val client = newClient(OkHttpClient())
        val raw = "data: {\"type\":\"error\",\"error\":{\"message\":\"模型不存在\"}}\n"
        val e = runCatching { client.parseAnthropicSse(raw) }.exceptionOrNull()
        assertNotNull("应抛异常", e)
        assertTrue("应包含服务端原因", e!!.message.orEmpty().contains("模型不存在"))
    }

    @Test
    fun `complete 遇 200+error 参数不兼容时逐级降级并成功`() = runTest {
        var callCount = 0
        var lastBody = ""
        val interceptor = Interceptor { chain ->
            callCount++
            lastBody = requestText(chain)
            if (lastBody.contains("response_format")) {
                // OpenRouter 风格：HTTP 200 但 error 体，说明参数不被任何线路接受
                mockResponse(chain.request(), 200,
                    """{"error":{"message":"没有可处理当前参数的已配置线路，请检查请求兼容性"}}""")
            } else {
                mockResponse(chain.request(), 200, openAiContent("\"降级成功\""))
            }
        }
        val client = newClient(OkHttpClient.Builder().addInterceptor(interceptor).build())
        val content = client.complete(messages, jsonMode = true)
        assertEquals("降级后应返回内容", "降级成功", content)
        assertTrue("应逐级降级后成功（>=2 次请求）", callCount >= 2)
        assertFalse("降级后不应再带 response_format", lastBody.contains("response_format"))
        assertFalse("降级后不应再带 seed", lastBody.contains("\"seed\""))
    }

    @Test
    fun `Claude 模型名首发即不带 response_format 与 seed`() = runTest {
        var body = ""
        val interceptor = Interceptor { chain ->
            body = requestText(chain)
            mockResponse(chain.request(), 200, openAiContent("\"ok\""))
        }
        val client = LlmClientImpl(
            baseUrl = "https://g.example.com/v1",
            apiKey = "k",
            model = "claude-haiku-5-5",
            language = "zh",
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
        assertEquals("ok", client.complete(messages, jsonMode = true))
        assertFalse("Claude 经 OpenAI 兼容通道不应带 response_format", body.contains("response_format"))
        assertFalse("Claude 经 OpenAI 兼容通道不应带 seed", body.contains("\"seed\""))
        assertTrue("仍应带 temperature", body.contains("\"temperature\""))
    }

    @Test
    fun `Anthropic 请求体只带官方最少字段，不带 temperature 与 stream`() {
        val client = newClient(OkHttpClient.Builder().build())
        val body = client.buildAnthropicRequestBody(
            listOf(
                LlmMessage("system", "你是结构工程师"),
                LlmMessage("user", "建一个简支梁")
            )
        ).toString()
        assertTrue("应带 model", body.contains("\"model\""))
        assertTrue("应带 max_tokens", body.contains("\"max_tokens\""))
        assertTrue("system 应在顶层", body.contains("\"system\""))
        assertTrue("应带 messages", body.contains("\"messages\""))
        assertFalse("中转网关对 temperature 支持不一，不应发送", body.contains("temperature"))
        assertFalse("不应发送 stream", body.contains("\"stream\""))
        assertFalse("Anthropic 不支持 response_format", body.contains("response_format"))
    }

    private fun requestText(chain: Interceptor.Chain): String {
        val body = chain.request().body ?: return ""
        val buf = Buffer()
        body.writeTo(buf)
        return buf.readUtf8()
    }

    private fun mockResponse(req: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
}
