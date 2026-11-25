/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * LLM 消息（OpenAI chat 格式的最小子集）。
 * images 非空时为多模态消息：content 转为 [text, image_url...] 数组（见 buildLlmMessagesArray），
 * 图片用 base64 JPEG 数据 URI（"data:image/jpeg;base64,..."）随请求内联发送。
 */
data class LlmMessage(
    val role: String,
    val content: String,
    val images: List<String> = emptyList()
)

/**
 * 一次 LLM 调用的 token 用量（P-05 采集层）。字段兼容 OpenAI 与 Anthropic 两种口径：
 * OpenAI prompt_tokens/completion_tokens（+ details 里的 reasoning/cached），
 * Anthropic input_tokens/output_tokens（+ cache_read_input_tokens）。
 * 服务未返回 usage 或字段缺失时为 null（区别于 0，聚合时不混淆）。
 */
data class LlmUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    /** 思考 token（OpenAI completion_tokens_details.reasoning_tokens；Anthropic 不单独暴露） */
    val reasoningTokens: Int? = null,
    /** 缓存命中的输入 token（OpenAI prompt_tokens_details.cached_tokens / Anthropic cache_read_input_tokens） */
    val cachedTokens: Int? = null
)

/** 一次调用的完整观测记录：写 {filesDir}/llm_stats.jsonl（仅调试包，见规则文档 P-05） */
data class LlmCallStat(
    /** 调用通道标签（frame_model/followup/polish/…），由调用方经 complete(tag=) 传入 */
    val tag: String?,
    val model: String,
    val provider: String,
    /** Kimi 推理强度档（low/high/max）；非 Kimi 无意义，保留供聚合过滤 */
    val effort: String,
    val stream: Boolean,
    /** 端到端耗时（含限流退避重试），毫秒 */
    val ms: Long,
    val ok: Boolean,
    /** 是否发生过限流退避重试 */
    val retried: Boolean,
    val usage: LlmUsage?
)

/** LlmCallStat → llm_stats.jsonl 单行 JSON（internal 便于 JVM 单测验证字段口径） */
internal fun llmUsageJsonLine(s: LlmCallStat, ts: Long): String = buildJsonObject {
    put("ts", ts)
    s.tag?.let { put("tag", it) }
    put("model", s.model)
    put("provider", s.provider)
    put("effort", s.effort)
    put("stream", s.stream)
    put("ms", s.ms)
    put("ok", s.ok)
    put("retried", s.retried)
    s.usage?.let { u ->
        put("usage", buildJsonObject {
            u.promptTokens?.let { put("prompt", it) }
            u.completionTokens?.let { put("completion", it) }
            u.reasoningTokens?.let { put("reasoning", it) }
            u.cachedTokens?.let { put("cached", it) }
        })
    }
}.toString()

/**
 * 组装 OpenAI chat 的 messages JSON 数组（internal 便于 JVM 单测直接验证形状）：
 * 无图 → "content" 为纯文本；带图 → "content" 为
 * [{"type":"text","text":...}, {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}...]
 */
/**
 * 异常 → 可读文案。部分网络异常（如读超时的 SocketTimeoutException）message 为空，
 * 直接兜底成「未知错误」会让超时和额度问题无法区分，这里退回异常类型名。
 */
internal fun Throwable.readableMessage(): String =
    message?.takeIf { it.isNotBlank() }
        ?: (this::class.java.simpleName + (cause?.message?.let { ": $it" } ?: ""))

internal fun buildLlmMessagesArray(messages: List<LlmMessage>): JsonArray = JsonArray(messages.map { m ->
    buildJsonObject {
        put("role", m.role)
        if (m.images.isEmpty()) {
            put("content", m.content)
        } else {
            put("content", kotlinx.serialization.json.buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", m.content)
                    }
                )
                m.images.forEach { uri ->
                    add(
                        buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", uri) })
                        }
                    )
                }
            })
        }
    }
})

/** 接口协议：OpenAI 的 /chat/completions 与 Anthropic 的 /messages 请求/响应格式不同 */
internal enum class ApiProtocol { OPENAI, ANTHROPIC }

/** 解析后的实际请求地址 */
internal data class Endpoint(val url: String, val protocol: ApiProtocol)

/**
 * Base URL → 实际请求地址（internal 便于 JVM 单测）：
 * - 已带 /chat/completions → 原样使用（避免拼成 …/chat/completions/chat/completions 而 404）；
 * - 已带 /messages → 走 Anthropic 协议；
 * - 域名含 anthropic → 补 /v1/messages；
 * - 其余（含只填到 /v1）→ 补 /chat/completions。
 */
internal fun resolveEndpoint(base: String): Endpoint? {
    val b = base.trim().trimEnd('/')
    if (!b.startsWith("http")) return null
    val lower = b.lowercase()
    return when {
        lower.endsWith("/chat/completions") -> Endpoint(b, ApiProtocol.OPENAI)
        lower.endsWith("/messages") -> Endpoint(b, ApiProtocol.ANTHROPIC)
        lower.contains("anthropic") ->
            Endpoint(if (lower.endsWith("/v1")) "$b/messages" else "$b/v1/messages", ApiProtocol.ANTHROPIC)
        else -> Endpoint("$b/chat/completions", ApiProtocol.OPENAI)
    }
}

/**
 * OpenAI 兼容地址 → 同一中转站的 Anthropic 原生 /messages 地址（502 网关回退用，internal 便于单测）：
 * 仅当 URL 以 /chat/completions 结尾时给出候选；中转站（如 ai0011）通常两套端点都开放，
 * OpenAI→Claude 转换线路抖动 502 时，原生 /v1/messages 线路往往还能通。
 */
internal fun anthropicFallbackUrl(url: String): String? {
    val suffix = "/chat/completions"
    if (!url.lowercase().endsWith(suffix)) return null
    return url.substring(0, url.length - suffix.length) + "/messages"
}

/** 从 data URI 拆出 (media_type, base64)；非 data URI 返回 null，由调用方按 jpeg 处理 */
internal fun splitDataUri(uri: String): Pair<String, String>? {
    if (!uri.startsWith("data:")) return null
    val comma = uri.indexOf(',')
    if (comma <= 0) return null
    val meta = uri.substring(5, comma)
    val data = uri.substring(comma + 1)
    val media = meta.substringBefore(';').ifBlank { "image/jpeg" }
    return media to data
}

/**
 * Anthropic Messages 协议的消息数组：system 抽出为顶层字段，
 * 图片从 data URI 还原成 {"type":"image","source":{"type":"base64",...}}。
 */
internal fun buildAnthropicMessagesArray(messages: List<LlmMessage>): JsonArray =
    JsonArray(messages.filter { it.role != "system" }.map { m ->
        buildJsonObject {
            put("role", if (m.role == "assistant") "assistant" else "user")
            if (m.images.isEmpty()) {
                put("content", m.content)
            } else {
                put("content", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", m.content) })
                    m.images.forEach { uri ->
                        val split = splitDataUri(uri)
                        add(
                            buildJsonObject {
                                put("type", "image")
                                put(
                                    "source", buildJsonObject {
                                        put("type", "base64")
                                        put("media_type", split?.first ?: "image/jpeg")
                                        put("data", split?.second ?: uri)
                                    }
                                )
                            }
                        )
                    }
                })
            }
        }
    })

/**
 * AI 客户端抽象：接口化便于测试与替换（单测用 Fake，设置未配置时 UI 引导手动模式）。
 */
interface AiClient {
    /**
     * 一次补全请求。
     * @param jsonMode 尽力请求 JSON 输出（response_format=json_object，服务端不支持时靠提示词兜底）
     * @param tag 调用通道标签（frame_model/followup/polish/…），仅用于调试包 usage 采集归组（P-05），
     *   不参与请求内容，不影响输出。
     * @return 助理消息文本
     * @throws IOException 网络 / HTTP / 响应结构异常（带中文可读信息）
     */
    suspend fun complete(
        messages: List<LlmMessage>,
        jsonMode: Boolean = false,
        seedOverride: Int? = null,
        tag: String? = null
    ): String

    /**
     * 带 JSON Schema 的补全：schema 非空时请求 response_format=json_schema（strict），
     * 服务不支持时由实现自动降级为 json_object。默认实现忽略 schema 走 [complete]，
     * 便于测试 fake 无需改动。
     */
    suspend fun complete(
        messages: List<LlmMessage>,
        jsonMode: Boolean,
        seedOverride: Int?,
        schema: JsonObject?,
        tag: String? = null
    ): String = complete(messages, jsonMode, seedOverride, tag)

    /**
     * 返回一个强制指定思考强度档（low/high/max）的客户端（P-03 分档：重试升档用）。
     * 默认实现忽略并返回自身——测试 fake 与不支持该参数的服务无需改动。
     */
    fun withReasoningEffort(effort: String): AiClient = this

    /**
     * 基于消息内容的稳定非负整数哈希：同输入 → 同 seed（服务端支持 seed 时输出可复现）。
     * 多候选生成时以它为基准，为每个候选叠加不同偏移以保证多样性。
     */
    fun stableSeedOf(messages: List<LlmMessage>): Int {
        var h = 0
        for (m in messages) {
            h = 31 * h + m.role.hashCode()
            h = 31 * h + m.content.hashCode()
        }
        return h and 0x7fffffff
    }
}

/**
 * LLM 服务提供商：按 Base URL 域名自动识别，用于发送各厂商兼容的请求参数，
 * 避免向不支持的服务发专属字段导致 400，或遗漏必要参数导致被思考拖慢。
 * Custom 为通用 OpenAI 兼容通道（国际用户可接 OpenAI / Groq / OpenRouter / 本地代理等）。
 * 预设值供设置页一键填充；用户仍可自由修改 Base URL 与模型名。
 */
enum class LlmProvider(
    val defaultBaseUrl: String,
    val defaultModel: String
) {
    Kimi("https://api.moonshot.cn/v1", "kimi-k3.1-pro"),
    Doubao("https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-2-0-pro-260215"),
    DeepSeek("https://api.deepseek.com", "deepseek-chat"),
    Custom("", "");

    companion object {
        fun fromBaseUrl(url: String): LlmProvider {
            val u = url.lowercase()
            return when {
                u.contains("moonshot") || u.contains("kimi.") -> Kimi
                u.contains("volces.com") || u.contains("byteplus") -> Doubao
                u.contains("deepseek") -> DeepSeek
                else -> Custom
            }
        }
    }
}

/**
 * OpenAI 兼容接口 HTTP 客户端（OkHttp 实现）。
 *
 * - 端点：{baseUrl}/chat/completions，baseUrl 形如 https://api.deepseek.com/v1；
 * - 鉴权：Bearer API Key（Key 为空则不加头，兼容本地代理）；
 * - jsonMode=true 时带 response_format={"type":"json_object"}；
 *   部分兼容服务不支持该字段——失败时由上层提示词重试兜底。
 */
class LlmClientImpl(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val timeoutSeconds: Int = 60,
    /**
     * 视觉模型（消息带图时切换使用；为空则沿用主模型）。
     * 主模型不支持图片输入的服务（HTTP 4xx 提示 model not support image 之类），
     * 用户可在设置页为发图场景单独指定支持视觉的模型。
     */
    private val visionModel: String = "",
    /** 错误提示语言（"zh"/"en"），跟随 App 当前语言 */
    private val language: String = LocaleStore.LANG_ZH,
    /**
     * Kimi（moonshot/kimi）建模通道的推理强度：low / high / max（默认 high）。
     * Kimi 始终开启思考，max 默认档会显著拖慢多候选生成；high 在保留几何不变性推理的同时控速。
     * 仅对 Kimi 域名且 jsonMode=true 时附加，其他服务忽略。
     */
    private val reasoningEffort: String = "high",
    /**
     * 高速建模：Kimi（moonshot/kimi）域名下自动把模型切换为高速版（如 kimi-k3.1-pro → kimi-k3.1-pro-highspeed），
     * 官方承诺同模型效果、速度约 2–3 倍，token 消耗约 2 倍。非 Kimi 域名或已是 *-highspeed 时不改动。
     */
    private val preferHighSpeed: Boolean = false,
    /** 测试用：注入自定义 OkHttpClient（如带拦截器模拟 4xx 降级重试）；null 时按 timeout 自建 */
    private val httpClient: OkHttpClient? = null,
    /**
     * usage 采集回调（P-05）：每次调用（成功或失败）结束时收到一条 [LlmCallStat]。
     * 仅调试包由 AppContainer 接线写 llm_stats.jsonl；正式包传 null——
     * 届时连 usage 解析都不做，零开销。回调内部自行兜异常，绝不影响主流程。
     */
    private val usageSink: ((LlmCallStat) -> Unit)? = null
) : AiClient {

    /** 当前 Base URL 识别出的厂商，集中判定以替代散落的域名字符串判断 */
    private val provider: LlmProvider = LlmProvider.fromBaseUrl(baseUrl)

    /** P-03 分档：复制一个仅思考档不同的客户端（其余参数与 usage 采集接线原样保留） */
    override fun withReasoningEffort(effort: String): AiClient = LlmClientImpl(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        timeoutSeconds = timeoutSeconds,
        visionModel = visionModel,
        language = language,
        reasoningEffort = effort,
        preferHighSpeed = preferHighSpeed,
        httpClient = httpClient,
        usageSink = usageSink
    )

    /** 按当前语言选择错误文案 */
    private fun t(zh: String, en: String): String =
        if (language == LocaleStore.LANG_EN) en else zh

    private val http: OkHttpClient by lazy {
        httpClient ?: OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(effectiveReadTimeoutSeconds().toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
    }

    /**
     * 读超时：直接采用用户设置（连接 / 写入同值），各厂商一致。
     * 此前对 Kimi 自动放宽到 120 秒、后又放大到 240 秒，用户反馈等待过久，
     * 改为"设置多少就是多少"，失败要快、由用户自己决定等多久。
     */
    internal fun effectiveReadTimeoutSeconds(): Int = timeoutSeconds

    /** HTTP 非 2xx：携带状态码与响应体，供 [complete] 决定是否去 seed 重试 */
    private class HttpError(val code: Int, val text: String) : RuntimeException("HTTP $code")

    /** 实际使用的模型：带图且有视觉模型时用它；Kimi 高速建模再追加 -highspeed */
    private fun effectiveModel(messages: List<LlmMessage>): String {
        val hasImages = messages.any { it.images.isNotEmpty() }
        val baseModel = if (hasImages && visionModel.isNotBlank()) visionModel else model
        return if (preferHighSpeed && provider == LlmProvider.Kimi && !baseModel.endsWith("-highspeed")) {
            baseModel + "-highspeed"
        } else baseModel
    }

    /**
     * Anthropic Messages 请求体：只带官方最少必需字段（model / max_tokens / system / messages），
     * 与官方 curl 示例一致。中转网关对 temperature、stream 等可选字段的支持不一致，
     * 多带一个就可能被判「没有可处理当前参数的线路」，因此一律不发。
     */
    internal fun buildAnthropicRequestBody(
        messages: List<LlmMessage>
    ): JsonObject {
        val system = messages.filter { it.role == "system" }
            .joinToString("\n\n") { it.content }
        return buildJsonObject {
            put("model", effectiveModel(messages))
            put("max_tokens", 8192)
            if (system.isNotBlank()) put("system", system)
            put("messages", buildAnthropicMessagesArray(messages))
        }
    }

    /**
     * 组装请求体（internal 便于 JVM 单测验证 temperature/seed 契约）。
     * 建模通道（jsonMode=true）附加稳定 seed 降随机性；非 Kimi 服务 temperature 建模 0.1 / 答疑 0.2。
     * Kimi 域名（moonshot/kimi）下不传 temperature：K3 系列思考模型固定为 1.0，
     * 传其他值返回 400，多样性由 seed 保证。
     *
     * @param withSeed true 时附加 [stableSeed]（仅 jsonMode=true 时传入）
     * @param stream true 时附加 stream:true（Kimi 思考模型走流式，避免非流式超时）
     * @param plainJson true 时只靠提示词要 JSON：不发 response_format 与 seed。
     *   部分模型/网关（如经 OpenRouter 转发的 Claude）拒绝这两个字段，
     *   报「没有可处理当前参数的已配置线路」。
     * @param includeReasoningNone O-06 快速模式：true 时对 Custom 网关直发 reasoning_effort=none；
     *   网关不识别返回 4xx 后由 doRequest 去掉该字段重跑降级阶梯。
     */
    internal fun buildRequestBody(
        messages: List<LlmMessage>,
        jsonMode: Boolean,
        withSeed: Boolean,
        seedOverride: Int? = null,
        schema: JsonObject? = null,
        stream: Boolean = false,
        plainJson: Boolean = false,
        includeReasoningNone: Boolean = false
    ): JsonObject {
        val effModel = effectiveModel(messages)
        return buildJsonObject {
            put("model", effModel)
            put("messages", buildLlmMessagesArray(messages))
            // Kimi 思考模型非流式时，服务端要等思考+输出生成完才发 header，
            // 中间网关会关闭等待过久的连接 → 客户端 timeout。官方推荐 stream=true：
            // header 与首 token 立即返回、分块传输保持连接活跃。Kimi 域名与托管 Kimi 模型开启。
            if (stream) {
                put("stream", true)
                // usage 采集（P-05）：Kimi 官方端点流式默认不回传 usage，
                // 需显式 stream_options.include_usage，最后一个 chunk 带完整 usage。
                // 仅官方域名附加——第三方托管 Kimi（方舟等）可能不识别该字段报 400，
                // 由参数降级阶梯兜底反而浪费一次调用，不如不发。
                if (provider == LlmProvider.Kimi) {
                    put("stream_options", buildJsonObject { put("include_usage", true) })
                }
            }
            // Kimi K3 系列（思考模型）temperature 固定为 1.0，传其他值直接 400，
            // 官方要求不显式传；多样性改由 seed 保证。其他服务保持建模 0.1 / 答疑 0.2。
            // 例外：第三方托管的 Kimi 模型（如火山方舟 ark 上的 kimi-k3-1-pro）
            // 只接受 temperature=0.6，传 0.1/0.2 返回 400
            // "'temperature' only 0.6 is allowed for this model"。
            if (provider != LlmProvider.Kimi) {
                val kimiHosted = effModel.lowercase().contains("kimi")
                put("temperature", if (kimiHosted) 0.6 else if (jsonMode) 0.1 else 0.2)
            }
            if (withSeed && !plainJson) put("seed", seedOverride ?: stableSeedOf(messages))
            if (jsonMode && !plainJson) {
                if (schema != null) {
                    put("response_format", buildJsonObject {
                        put("type", "json_schema")
                        put("json_schema", buildJsonObject {
                            put("name", "frame_output")
                            put("strict", true)
                            put("schema", schema)
                        })
                    })
                    // 严格约束解码下输出可能略长，给足 token 上限防 finish_reason=length 截断。
                    // Kimi K3.1 Pro 文档使用 max_completion_tokens（默认 131072，过大可能放任过度思考拖慢）；
                    // 其他 OpenAI 兼容服务用 max_tokens。
                    if (provider == LlmProvider.Kimi) {
                        put("max_completion_tokens", 8192)
                    } else {
                        put("max_tokens", 8192)
                    }
                } else {
                    put("response_format", buildJsonObject { put("type", "json_object") })
                }
            }
            // Kimi 始终开启思考：建模通道按设置限制推理强度，
            // 避免 max 默认档拖慢多候选生成（3 候选 + 重试最多 6 次调用）。
            if (jsonMode && provider == LlmProvider.Kimi) {
                // Kimi 始终思考、不支持 none：快速模式落到最低 low 档
                put("reasoning_effort", if (reasoningEffort == "none") "low" else reasoningEffort)
            }
            // DeepSeek 默认思考=high，建模抽取会被长时间思考拖慢（与旧版方舟同病）；
            // 显式 reasoning_effort=none 关闭思考以提速。该参数 DeepSeek /chat/completions
            // 原生支持（none/low/high/max），对标方舟的 thinking:disabled。
            if (provider == LlmProvider.DeepSeek) {
                put("reasoning_effort", "none")
            }
            // 火山方舟（volces.com / byteplus）：深度思考模型（doubao-seed 系列）默认开启思考，
            // 参数解析这类受约束抽取任务会被长时间思考卡死（实测思考 7872 token、
            // 耗时 144s，远超 App 的 60s 超时）；显式关闭后 1~5s 返回且质量足够。
            // 其他 OpenAI 兼容服务不识别该字段会报错，故仅对方舟域名附加。
            // 方舟上的 Kimi K3 系列始终思考、不支持该字段（传了报 400），按模型名跳过。
            if (provider == LlmProvider.Doubao && !effModel.lowercase().contains("kimi")) {
                put("thinking", buildJsonObject { put("type", "disabled") })
            }
            // O-06 快速模式（优化通道）：Custom / 通用 OpenAI 兼容中转直发 reasoning_effort=none，
            // 请求服务端关闭或压低深度思考；不识别该字段的网关由 doRequest 去字段重试兜底。
            // Anthropic 协议请求体本就不带 thinking 参数（默认即关闭），无需处理。
            if (includeReasoningNone && provider == LlmProvider.Custom) {
                // qwen 系列实测（v1.2.44 真机横评）接受 none 但输出 230 token 废稿
                // （空补丁、无有效方案），与 Kimi 同病——none 档下限抬到 low。
                val noneFloor = if (effModel.lowercase().contains("qwen")) "low" else "none"
                put("reasoning_effort", noneFloor)
            }
        }
    }

    private fun newRequest(body: JsonObject, url: String, anthropic: Boolean): Request =
        Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .apply {
                if (apiKey.isNotBlank()) {
                    header("Authorization", "Bearer $apiKey")
                    // Anthropic 官方鉴权是 x-api-key + anthropic-version；中转网关多兼容 Bearer，
                    // 两个头同时带上，官方端点与中转站都能用。
                    if (anthropic) {
                        header("x-api-key", apiKey)
                        header("anthropic-version", "2023-06-01")
                    }
                }
            }
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

    /** executeOnce 结果：内容 + usage（仅采集层启用时解析，否则 usage=null） */
    private class Completion(val text: String, val usage: LlmUsage?)

    /** 单次请求：成功返回内容（采集层启用时附带 usage），非 2xx 抛 [HttpError]（可读文案由调用方统一生成） */
    private fun executeOnce(
        messages: List<LlmMessage>,
        jsonMode: Boolean,
        withSeed: Boolean,
        url: String,
        seedOverride: Int? = null,
        schema: JsonObject? = null,
        stream: Boolean = false,
        protocol: ApiProtocol = ApiProtocol.OPENAI,
        plainJson: Boolean = false,
        includeReasoningNone: Boolean = false
    ): Completion {
        val anthropic = protocol == ApiProtocol.ANTHROPIC
        val body = if (anthropic) {
            buildAnthropicRequestBody(messages)
        } else {
            buildRequestBody(messages, jsonMode, withSeed, seedOverride, schema, stream, plainJson, includeReasoningNone)
        }
        val request = newRequest(body, url, anthropic)
        return http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpError(resp.code, text)
            // A3：Kimi 可能以 200 + error 体返回组织级并发限流（rate_limit_reached_error），
            // 统一转成 HttpError(429)，由 complete 退避重试；否则解析不出内容会被当成普通失败吞掉。
            if (text.contains("rate_limit_reached_error") || text.contains("\"rate_limit")) {
                throw HttpError(429, text)
            }
            // 部分网关以 200 + {"error":...} 报告参数不兼容（如 OpenRouter 的
            // 「没有可处理当前参数的已配置线路」）。按 400 处理，才能走参数降级重试。
            val rootError = runCatching {
                AppJson.parseToJsonElement(text).jsonObject.containsKey("error")
            }.getOrDefault(false)
            if (rootError) throw HttpError(400, text)
            val content = when {
                anthropic && stream -> parseAnthropicSse(text)
                anthropic -> parseAnthropicContent(text)
                stream -> parseSseContent(text)
                else -> parseContent(text)
            }
            // usage 二次解析只在采集层启用时进行（正式包 sink=null，零开销）；
            // 失败降级为 null，绝不影响主流程。
            val usage = if (usageSink != null) extractUsage(text, anthropic, stream) else null
            Completion(content, usage)
        }
    }

    /**
     * 从原始响应文本提取 usage（P-05 采集层，internal 便于 JVM 单测）：
     * 按协议（OpenAI/Anthropic）与传输形态（流式 SSE/非流式 JSON）选择口径；
     * 任何解析失败一律返回 null，不抛异常。
     */
    internal fun extractUsage(raw: String, anthropic: Boolean, stream: Boolean): LlmUsage? =
        runCatching {
            if (stream) extractSseUsage(raw, anthropic)
            else {
                val root = AppJson.parseToJsonElement(raw).jsonObject
                if (anthropic) extractAnthropicUsage(root) else extractOpenAiUsage(root)
            }
        }.getOrNull()

    /** OpenAI 口径 usage：prompt/completion_tokens + details 里的 cached/reasoning */
    private fun extractOpenAiUsage(root: JsonObject): LlmUsage? {
        val u = root["usage"] as? JsonObject ?: return null
        val pd = u["prompt_tokens_details"] as? JsonObject
        val cd = u["completion_tokens_details"] as? JsonObject
        return LlmUsage(
            promptTokens = u.intOrNull("prompt_tokens"),
            completionTokens = u.intOrNull("completion_tokens"),
            reasoningTokens = cd?.intOrNull("reasoning_tokens"),
            cachedTokens = pd?.intOrNull("cached_tokens")
        )
    }

    /** Anthropic 口径 usage：input/output_tokens + cache_read_input_tokens（无独立思考计数）。
     *  流式 message_start 事件的 usage 嵌在 message 对象下，这里一并兼容。 */
    private fun extractAnthropicUsage(root: JsonObject): LlmUsage? {
        val u = (root["usage"] as? JsonObject)
            ?: ((root["message"] as? JsonObject)?.get("usage") as? JsonObject)
            ?: return null
        return LlmUsage(
            promptTokens = u.intOrNull("input_tokens"),
            completionTokens = u.intOrNull("output_tokens"),
            cachedTokens = u.intOrNull("cache_read_input_tokens")
        )
    }

    /**
     * SSE 流式 usage：逐 data: 分片找 usage 对象并合并（后到的非空字段覆盖）。
     * OpenAI/Kimi：带 stream_options.include_usage 时最后一个 chunk 才含 usage；
     * Anthropic：message_start 带输入侧、message_delta 带输出侧，需合并两个分片。
     */
    private fun extractSseUsage(raw: String, anthropic: Boolean): LlmUsage? {
        var acc: LlmUsage? = null
        raw.lineSequence().forEach { line ->
            val t = line.trimStart()
            if (!t.startsWith("data:")) return@forEach
            val payload = t.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]" || !payload.contains("\"usage\"")) return@forEach
            val obj = runCatching { AppJson.parseToJsonElement(payload).jsonObject }.getOrNull()
                ?: return@forEach
            val u = if (anthropic) extractAnthropicUsage(obj) else extractOpenAiUsage(obj)
            if (u != null) {
                acc = LlmUsage(
                    promptTokens = u.promptTokens ?: acc?.promptTokens,
                    completionTokens = u.completionTokens ?: acc?.completionTokens,
                    reasoningTokens = u.reasoningTokens ?: acc?.reasoningTokens,
                    cachedTokens = u.cachedTokens ?: acc?.cachedTokens
                )
            }
        }
        return acc
    }

    private fun JsonObject.intOrNull(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.content?.toInt() }.getOrNull()

    /** 可重试的 4xx（非授权 401/403）：seed 不识别 / schema 不支持等 */
    private fun isRetryable4xx(code: Int) = code in 400..499 && code != 401 && code != 403

    /**
     * 网关以 5xx 包装的「参数/线路不兼容」：如 ai0011 把 OpenRouter 的
     * 「没有可处理当前参数的已配置线路」返回成 502。这类错误换个参数集就能过，
     * 需要参与降级重试，不能直接失败。
     */
    private fun isParamReject(throwable: Throwable?): Boolean {
        if (throwable !is HttpError) return false
        val text = throwable.text
        return text.contains("没有可处理当前参数") ||
            text.contains("No endpoints found", ignoreCase = true) ||
            text.contains("support the requested parameters", ignoreCase = true) ||
            text.contains("unsupported parameter", ignoreCase = true)
    }

    /** 从错误响应体里取可读原因（{"error":{"message":...}} 优先，否则原文） */
    private fun errorReason(text: String): String =
        runCatching {
            AppJson.parseToJsonElement(text).jsonObject["error"]!!
                .jsonObject["message"]!!.jsonPrimitive.content
        }.getOrNull() ?: text

    /** HTTP 错误转带中文可读信息的 IOException（保留原有授权失效 / 通用文案） */
    private fun httpErrorToIOException(code: Int, text: String): IOException {
        val reason = errorReason(text).take(200)
        return if (code == 401 || code == 403) {
            IOException(
                t(
                    "LLM 接口返回 HTTP $code：授权失效或邀请码未激活，" +
                        "请到「首页 → 输入邀请码激活 AI」重新激活（${reason.take(120)}）",
                    "LLM endpoint returned HTTP $code: authorization expired or " +
                        "invite code not activated. Reactivate via \"Home → Activate AI with " +
                        "invite code\" (${reason.take(120)})"
                )
            )
        } else if (code in 502..504) {
            IOException(
                t(
                    "LLM 接口返回 HTTP $code：$reason。中转/网关上游暂时不可用，" +
                        "请确认模型名在该服务中存在（可在其官网查模型列表），或稍后再试",
                    "LLM endpoint returned HTTP $code: $reason. The relay/gateway upstream is " +
                        "temporarily unavailable. Verify the model name exists on that service, " +
                        "or try again later"
                )
            )
        } else {
            IOException(
                t(
                    "LLM 接口返回 HTTP $code：$reason",
                    "LLM endpoint returned HTTP $code: $reason"
                )
            )
        }
    }

    /** 组织级并发限流 / 额度受限（HTTP 429 或 200+rate_limit 体）→ 给用户可执行的提示 */
    private fun rateLimitIOException(text: String): IOException =
        IOException(
            t(
                "AI 接口并发超限（HTTP 429）：当前请求过多或账号额度受限，请等几秒再试；" +
                    "若持续出现，请检查 API 账户余额与并发限额（${text.take(120)}）",
                "AI endpoint is rate limited (HTTP 429): too many requests or quota limited. " +
                    "Wait a few seconds and retry; if it persists, check the API balance and " +
                    "concurrency limit (${text.take(120)})"
            )
        )

    override suspend fun complete(
        messages: List<LlmMessage>, jsonMode: Boolean, seedOverride: Int?, tag: String?
    ): String = complete(messages, jsonMode, seedOverride, null, tag)

    override suspend fun complete(
        messages: List<LlmMessage>, jsonMode: Boolean, seedOverride: Int?, schema: JsonObject?, tag: String?
    ): String = withContext(Dispatchers.IO) {
        val endpoint = resolveEndpoint(baseUrl) ?: throw IOException(
            t("API Base URL 无效：$baseUrl", "Invalid API base URL: $baseUrl")
        )
        val modelName = model.lowercase()

        // A3：是否为 Kimi 组织级并发限流（HTTP 429 或响应体含 rate_limit）
        fun isRateLimit(e: Throwable?): Boolean = e is HttpError &&
            (e.code == 429 || e.text.contains("rate_limit", ignoreCase = true))
        // 网关 5xx（中转站包装上游抖动，如 ai0011 的 502）：与请求参数无关，可整请求重试
        fun isGateway5xx(e: Throwable?): Boolean = e is HttpError && e.code in 502..504

        /** 对指定端点跑一遍完整参数降级阶梯；协议相关参数按端点就地计算 */
        fun doRequest(ep: Endpoint): Completion {
            val anthropic = ep.protocol == ApiProtocol.ANTHROPIC
            // 仅建模通道带 seed 降随机性；纯答疑不带（Anthropic 无 seed 字段）
            val withSeed = jsonMode && !anthropic
            // 思考型模型走流式（见 buildRequestBody 注释）：避免非流式网关/客户端超时。
            // 按模型名兼容第三方托管的 Kimi（如火山方舟上的 kimi-k3-1-pro），
            // 它们同样是始终思考的慢模型，非流式极易超时。
            // Anthropic 通道不发 stream：与官方 curl 示例保持一致，中转网关对流式的支持不稳定。
            val useStream = !anthropic && (provider == LlmProvider.Kimi || modelName.contains("kimi"))
            // Anthropic 不支持 response_format，schema 只对 OpenAI 协议生效
            val effSchema = if (anthropic) null else schema
            // 经 OpenAI 兼容接口转发的 Claude（如 OpenRouter）会拒绝 response_format / seed，
            // 直接从「纯提示词」参数起步，省掉一次必然失败的请求。
            val plainPreferred = !anthropic &&
                (modelName.contains("claude") || modelName.contains("anthropic"))

            fun call(useSchema: Boolean, useSeed: Boolean, plain: Boolean, effortNone: Boolean): Result<Completion> =
                runCatching {
                    executeOnce(
                        messages, jsonMode, useSeed, ep.url,
                        if (useSeed) seedOverride else null,
                        if (useSchema) effSchema else null,
                        stream = useStream,
                        protocol = ep.protocol,
                        plainJson = plain,
                        includeReasoningNone = effortNone
                    )
                }
            // 按「参数从多到少」依次降级，只对可重试 4xx 生效：
            // ① schema + seed → ② 去 schema → ③ 去 seed → ④ 纯提示词（去 response_format）
            fun ladder(includeEffortNone: Boolean): Completion {
                val attempts = buildList {
                    add(Triple(effSchema != null, withSeed, plainPreferred))
                    if (effSchema != null) add(Triple(false, withSeed, plainPreferred))
                    if (withSeed) add(Triple(false, false, plainPreferred))
                    if (jsonMode && !plainPreferred) add(Triple(false, false, true))
                }
                var lastError: Throwable? = null
                attempts.forEach { (useSchema, useSeed, plain) ->
                    val r = call(useSchema, useSeed, plain, includeEffortNone)
                    r.getOrNull()?.let { return it }
                    val e = r.exceptionOrNull()
                    // 限流直接抛出，交给外层退避重试，不做无意义的参数降级
                    if (isRateLimit(e)) throw e ?: IOException("rate limit")
                    val retryable = (e is HttpError && isRetryable4xx(e.code)) || isParamReject(e)
                    if (!retryable) throw e ?: IOException(t("LLM 请求失败", "LLM request failed"))
                    lastError = e
                }
                throw lastError ?: IOException(t("LLM 请求失败", "LLM request failed"))
            }
            // O-06 快速模式：Custom 网关先带 reasoning_effort=none 跑阶梯；
            // 整条阶梯都因参数被拒（可重试 4xx）时，去掉该字段再跑一次。
            val sendEffortNone = !anthropic && provider == LlmProvider.Custom && reasoningEffort == "none"
            if (!sendEffortNone) return ladder(false)
            return try {
                ladder(true)
            } catch (e: Throwable) {
                val paramRejected = e is HttpError && !isRateLimit(e) &&
                    (isRetryable4xx(e.code) || isParamReject(e))
                if (paramRejected) ladder(false) else throw e
            }
        }

        // A3：限流退避重试 1 次（多候选 3 路并发可能触发 Kimi rate_limit_reached_error）
        val t0 = System.currentTimeMillis()
        var perfRetried = false
        var gatewayRetried = false
        val first = runCatching { doRequest(endpoint) }
        var outcome = if (first.isFailure && isRateLimit(first.exceptionOrNull())) {
            perfRetried = true
            delay(1500)
            runCatching { doRequest(endpoint) }
        } else {
            first
        }
        // 5xx 网关抖动：原端点退避重试 1 次；Claude 模型再回退到中转站的
        // Anthropic 原生 /messages 端点（OpenAI→Claude 转换线路故障时原生线路常还能通）。
        if (outcome.isFailure && isGateway5xx(outcome.exceptionOrNull())) {
            gatewayRetried = true
            delay(1500)
            outcome = runCatching { doRequest(endpoint) }
            val altUrl = if (endpoint.protocol == ApiProtocol.OPENAI &&
                (modelName.contains("claude") || modelName.contains("anthropic"))
            ) anthropicFallbackUrl(endpoint.url) else null
            if (altUrl != null && outcome.isFailure && isGateway5xx(outcome.exceptionOrNull())) {
                val alt = runCatching { doRequest(Endpoint(altUrl, ApiProtocol.ANTHROPIC)) }
                // 回退端点仍失败时上报原始 5xx（比回退端点的 404 更贴近真实故障）
                if (alt.isSuccess) outcome = alt
            }
        }
        val ms = System.currentTimeMillis() - t0
        Log.i(
            "PocketSimPerf",
            "model=$model effort=$reasoningEffort highspeed=$preferHighSpeed provider=$provider " +
                "ms=$ms retried=${perfRetried || gatewayRetried} ok=${outcome.isSuccess}"
        )
        // P-05 usage 采集：成功带 usage，失败也记一条 ok=false（token 未知、时延已知）。
        // 仅调试包接线非空 sink；观测层任何异常一律吞掉，绝不影响建模主流程。
        runCatching {
            usageSink?.invoke(
                LlmCallStat(
                    tag = tag,
                    model = effectiveModel(messages),
                    provider = provider.name,
                    effort = reasoningEffort,
                    stream = endpoint.protocol == ApiProtocol.OPENAI &&
                        (provider == LlmProvider.Kimi || modelName.contains("kimi")),
                    ms = ms,
                    ok = outcome.isSuccess,
                    retried = perfRetried || gatewayRetried,
                    usage = outcome.getOrNull()?.usage
                )
            )
        }
        // 兜底：限流等路径会把 HttpError 直接抛到上层，它本身没有可读信息，
        // 界面会兜底成「未知错误」。这里统一转成带状态码/原因的可读异常。
        val err = outcome.exceptionOrNull()
        if (err is HttpError) {
            throw if (isRateLimit(err)) rateLimitIOException(err.text)
            else httpErrorToIOException(err.code, err.text)
        }
        outcome.getOrThrow().text
    }

    /**
     * 解析 Kimi 流式（SSE）响应：逐行读取 "data:" 前缀，累积 choices[0].delta.content，
     * 忽略 delta.reasoning_content（思考过程不计入最终输出）；遇到 "data: [DONE]" 结束。
     * 任一分片含 error 字段则抛 IOException。若响应不含任何 data: 行（服务未真正流式、
     * 或返回的是普通 JSON），降级走 [parseContent]。
     */
    internal fun parseSseContent(raw: String): String {
        val sb = StringBuilder()
        var sawDataLine = false
        raw.lineSequence().forEach { line ->
            val t = line.trimStart()
            if (!t.startsWith("data:")) return@forEach
            sawDataLine = true
            val payload = t.removePrefix("data:").trim()
            if (payload == "[DONE]") return@forEach
            val obj = runCatching { AppJson.parseToJsonElement(payload).jsonObject }.getOrNull()
                ?: return@forEach
            obj["error"]?.let { err ->
                val msg = runCatching { err.jsonObject["message"]?.jsonPrimitive?.content }
                    .getOrNull() ?: err.toString()
                throw IOException(t("LLM 流式接口错误：$msg", "LLM stream error: $msg"))
            }
            val content = runCatching {
                obj["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("delta")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.content
            }.getOrNull()
            if (!content.isNullOrEmpty()) sb.append(content)
        }
        if (!sawDataLine) return parseContent(raw)
        return sb.toString()
    }

    /** 解析 Anthropic 非流式响应：拼接 content[] 中所有 type=text 的文本 */
    private fun parseAnthropicContent(raw: String): String {
        val root = try {
            AppJson.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            throw IOException(
                t(
                    "LLM 返回的不是合法 JSON：${raw.take(120)}",
                    "LLM response is not valid JSON: ${raw.take(120)}"
                ),
                e
            )
        }
        val type = runCatching { root["type"]?.jsonPrimitive?.content }.getOrNull()
        if (type == "error") throw IOException(anthropicError(raw, root))
        val sb = StringBuilder()
        root["content"]?.jsonArray?.forEach { block ->
            val o = runCatching { block.jsonObject }.getOrNull() ?: return@forEach
            val isText = runCatching { o["type"]?.jsonPrimitive?.content }.getOrNull() == "text"
            if (isText) sb.append(runCatching { o["text"]?.jsonPrimitive?.content }.getOrNull().orEmpty())
        }
        if (sb.isEmpty()) {
            throw IOException(
                t(
                    "LLM 返回中缺少 content[].text：${raw.take(200)}",
                    "LLM response is missing content[].text: ${raw.take(200)}"
                )
            )
        }
        return sb.toString()
    }

    /** Anthropic 错误体 → 可读文案（{"type":"error","error":{"message":...}}） */
    private fun anthropicError(raw: String, root: JsonObject): String {
        val msg = runCatching {
            root["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        }.getOrNull() ?: raw.take(200)
        return t("LLM 接口错误：$msg", "LLM API error: $msg")
    }

    /**
     * 解析 Anthropic 流式（SSE）响应：只累加 content_block_delta 里的 text_delta，
     * 忽略 thinking_delta 等思考内容；无 data: 行时降级为非流式解析。
     */
    internal fun parseAnthropicSse(raw: String): String {
        val sb = StringBuilder()
        var sawDataLine = false
        raw.lineSequence().forEach { line ->
            val t2 = line.trimStart()
            if (!t2.startsWith("data:")) return@forEach
            sawDataLine = true
            val payload = t2.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") return@forEach
            val obj = runCatching { AppJson.parseToJsonElement(payload).jsonObject }.getOrNull()
                ?: return@forEach
            val type = runCatching { obj["type"]?.jsonPrimitive?.content }.getOrNull()
            if (type == "error") throw IOException(anthropicError(payload, obj))
            if (type == "content_block_delta") {
                val text = runCatching {
                    obj["delta"]!!.jsonObject["text"]!!.jsonPrimitive.content
                }.getOrNull()
                if (!text.isNullOrEmpty()) sb.append(text)
            }
        }
        if (!sawDataLine) return parseAnthropicContent(raw)
        return sb.toString()
    }

    /** 解析 choices[0].message.content；兼容部分服务在 error 字段里返回错误信息 */
    private fun parseContent(raw: String): String {
        val root = try {
            AppJson.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            throw IOException(
                t(
                    "LLM 返回的不是合法 JSON：${raw.take(120)}",
                    "LLM response is not valid JSON: ${raw.take(120)}"
                ),
                e
            )
        }
        root["error"]?.let { err ->
            val msg = runCatching { err.jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
                ?: err.toString()
            throw IOException(t("LLM 接口错误：$msg", "LLM API error: $msg"))
        }
        val content = runCatching {
            root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
        }.getOrNull()
        return content ?: throw IOException(
            t(
                "LLM 返回中缺少 choices[0].message.content",
                "LLM response is missing choices[0].message.content"
            )
        )
    }
}
