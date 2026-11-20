/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** 顶部扩展：DataStore 实例（App 私有，单例）；internal 以便单测重置 */
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "pocketsim_settings")

/**
 * 单个厂商的 LLM 配置槽：Base URL / API Key / 模型 / 视觉模型各自独立保存。
 * 切换厂商 chip 时加载对应槽（空则填默认值），编辑字段只写当前槽，互不影响。
 */
@Serializable
internal data class ProviderConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    /** 视觉模型（消息带图时使用；空 = 沿用该厂商的主模型） */
    val visionModel: String = ""
)

/** 应用设置快照 */
data class AppSettings(
    /** OpenAI 兼容接口基地址，形如 https://api.deepseek.com/v1 */
    val llmBaseUrl: String = "",
    val llmApiKey: String = "",
    val llmModel: String = "",
    /**
     * 当前选中的厂商（[LlmProvider.name]）：决定哪套配置生效，
     * 以及设置页 chip 选中态 / 提示文案 / Kimi 专属开关的显隐。
     * 默认 Doubao，与默认 Base URL（方舟）一致。
     */
    val activeProvider: String = LlmProvider.Doubao.name,
    /** 视觉模型（消息带图时使用；空 = 沿用主模型） */
    val llmVisionModel: String = "",
    /** 默认网格档位：coarse / medium / fine */
    val defaultMeshLevel: String = "medium",
    /** LLM 请求超时（秒） */
    val timeoutSeconds: Int = 60,
    /** 激活用设备 ID（首次激活时生成并持久化，换设备即换 ID） */
    val deviceId: String = "",
    /** 邀请码激活后服务端给出的每日次数；0 表示未激活 */
    val activatedDailyN: Int = 0,
    /**
     * 最近一次邀请码激活的云端凭据（与 llmApiKey 分开保存）。
     * 用户临时改用自备 API Key 时会覆盖 llmApiKey，云端凭据保留在此，
     * 之后可一键恢复，无需重新请求 /v1/activate（避免 409 device_already_activated）。
     */
    val cloudToken: String = "",
    val cloudDailyN: Int = 0,
    val cloudBaseUrl: String = "",
    val cloudModel: String = "",
    /** AI 建模首轮生成的候选数量（1~3，默认 1）；设为 1 时行为与单模型一致 */
    val candidateCount: Int = 1,
    /** 候选差异控制开关：开启后过滤掉与基准差异过大的候选（默认开启） */
    val candidateDivergenceEnabled: Boolean = true,
    /**
     * Kimi 建模通道推理强度：low / high / max（默认 low，见 P-03 分档）。
     * Kimi 始终开启思考，max 档显著拖慢多候选生成。
     * P-03 分档（2026-11-19 真机实测）：low 档快 40–50%、思考约 0.5k tok，但首次失败率 ~18%；
     * 故默认 low，结构/语义/几何不稳的重试由代码自动升 high 兜底（见 AiParser.parseFrameWithRetry
     * 与 ChatSession 换 seed 重试），兼顾速度与质量。仅当 Base URL 为 Kimi 域名（moonshot/kimi）时生效。
     */
    val llmReasoningEffort: String = "low",
    /**
     * 高速建模开关（默认 false = 保持当前模型与质量）。
     * 开启后，Kimi（moonshot/kimi）域名的请求自动把模型切换为高速版（如 kimi-k3.1-pro → kimi-k3.1-pro-highspeed），
     * 官方承诺同模型效果、速度约 2–3 倍，token 消耗约 2 倍。非 Kimi 域名不生效。
     */
    val highSpeedMode: Boolean = false,
    /** AI 结构优化建议的方案数量（1~3，默认 1） */
    val optimizeSuggestionCount: Int = 1,
    /**
     * O-06 AI 优化快速模式（默认开启）：优化通道单独把思考强度压到最低
     * （reasoning_effort=none，Kimi 落 low，方舟/DeepSeek 原生关闭，Anthropic 默认不思考），
     * 方案质量由客户端确定性校验 + 有限元重算兜底。关闭则沿用建模通道的思考强度设置。
     */
    val optimizeFastMode: Boolean = true
) {
    /** Base URL 与模型名齐备即视为已配置（Key 允许为空——本地代理场景） */
    val llmConfigured: Boolean get() = llmBaseUrl.isNotBlank() && llmModel.isNotBlank()
}

/** 设置仓储：DataStore(Preferences) 读写，UI 与 LLM 客户端工厂都从这里取值 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("llm_base_url")
        val API_KEY = stringPreferencesKey("llm_api_key")
        val MODEL = stringPreferencesKey("llm_model")
        /** 各厂商配置槽（JSON：{厂商名:{baseUrl,apiKey,model}}），取代旧单套 BASE_URL/API_KEY/MODEL */
        val PROVIDER_CONFIGS = stringPreferencesKey("llm_provider_configs")
        /** 当前选中的厂商（LlmProvider.name）；缺省时由旧 BASE_URL 推导或回退 Doubao */
        val ACTIVE_PROVIDER = stringPreferencesKey("llm_active_provider")
        val VISION_MODEL = stringPreferencesKey("llm_vision_model")
        val MESH_LEVEL = stringPreferencesKey("default_mesh_level")
        val TIMEOUT = intPreferencesKey("timeout_seconds")
        val DEVICE_ID = stringPreferencesKey("activation_device_id")
        val DAILY_N = intPreferencesKey("activation_daily_n")
        val CLOUD_TOKEN = stringPreferencesKey("cloud_token")
        val CLOUD_DAILY_N = intPreferencesKey("cloud_daily_n")
        val CLOUD_BASE_URL = stringPreferencesKey("cloud_base_url")
        val CLOUD_MODEL = stringPreferencesKey("cloud_model")
        val CANDIDATE_COUNT = intPreferencesKey("candidate_count")
        val CANDIDATE_DIVERGENCE = booleanPreferencesKey("candidate_divergence_enabled")
        val REASONING_EFFORT = stringPreferencesKey("llm_reasoning_effort")
        val HIGH_SPEED = booleanPreferencesKey("high_speed_mode")
        val OPTIMIZE_SUGGESTION_COUNT = intPreferencesKey("optimize_suggestion_count")
        val OPTIMIZE_FAST_MODE = booleanPreferencesKey("optimize_fast_mode")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        val configs = providerConfigsWithMigration(p)
        val active = resolveActiveProvider(p)
        val activeCfg = configs[active] ?: ProviderConfig()
        AppSettings(
            llmBaseUrl = activeCfg.baseUrl,
            llmApiKey = activeCfg.apiKey,
            llmModel = activeCfg.model,
            activeProvider = active,
            // 视觉模型按厂商独立：优先取当前厂商槽；槽内为空时仅在"所有厂商都还没配过"的
            // 情况下回退旧的全局值（老版本只有一份），避免一份配置串到所有厂商。
            llmVisionModel = activeCfg.visionModel.ifBlank {
                if (configs.values.any { it.visionModel.isNotBlank() }) "" else p[Keys.VISION_MODEL] ?: ""
            },
            defaultMeshLevel = p[Keys.MESH_LEVEL] ?: "medium",
            timeoutSeconds = (p[Keys.TIMEOUT] ?: 60).coerceIn(5, 600),
            deviceId = p[Keys.DEVICE_ID] ?: "",
            activatedDailyN = p[Keys.DAILY_N] ?: 0,
            cloudToken = p[Keys.CLOUD_TOKEN] ?: "",
            cloudDailyN = p[Keys.CLOUD_DAILY_N] ?: 0,
            cloudBaseUrl = p[Keys.CLOUD_BASE_URL] ?: "",
            cloudModel = p[Keys.CLOUD_MODEL] ?: "",
            candidateCount = (p[Keys.CANDIDATE_COUNT] ?: 1).coerceIn(1, 3),
            candidateDivergenceEnabled = p[Keys.CANDIDATE_DIVERGENCE] ?: true,
            // P-03：未配置时默认 low（重试路径由代码自动升 high）；存量非法值回退 high（从严）
            llmReasoningEffort = (p[Keys.REASONING_EFFORT] ?: "low").let {
                if (it in setOf("low", "high", "max")) it else "high"
            },
            highSpeedMode = p[Keys.HIGH_SPEED] ?: false,
            optimizeSuggestionCount = (p[Keys.OPTIMIZE_SUGGESTION_COUNT] ?: 1).coerceIn(1, 3),
            optimizeFastMode = p[Keys.OPTIMIZE_FAST_MODE] ?: true
        )
    }

    /** 当前设置（一次性读取） */
    suspend fun current(): AppSettings = settings.first()

    // ---- 厂商配置槽：每家独立保存 baseUrl / apiKey / model ----

    private fun readProviderConfigs(p: Preferences): Map<String, ProviderConfig> {
        val raw = p[Keys.PROVIDER_CONFIGS]
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { AppJson.decodeFromString<Map<String, ProviderConfig>>(raw) }
            .getOrDefault(emptyMap())
    }

    /** 旧版只有一套 BASE_URL/API_KEY/MODEL；槽为空时迁移到由地址判定的厂商槽（只读合成，不落库） */
    private fun providerConfigsWithMigration(p: Preferences): Map<String, ProviderConfig> {
        val configs = readProviderConfigs(p)
        if (configs.isNotEmpty()) return configs
        val legacy = p[Keys.BASE_URL] ?: ""
        if (legacy.isBlank()) return emptyMap()
        return mapOf(LlmProvider.fromBaseUrl(legacy).name to ProviderConfig(
            baseUrl = legacy,
            apiKey = p[Keys.API_KEY] ?: "",
            model = p[Keys.MODEL] ?: ""
        ))
    }

    private fun resolveActiveProvider(p: Preferences): String {
        val stored = p[Keys.ACTIVE_PROVIDER]
        if (!stored.isNullOrBlank() && runCatching { LlmProvider.valueOf(stored) }.isSuccess) return stored
        val legacy = p[Keys.BASE_URL] ?: ""
        return if (legacy.isNotBlank()) LlmProvider.fromBaseUrl(legacy).name else LlmProvider.Doubao.name
    }

    /** 改写当前选中厂商的配置槽并写回；内部自动做旧配置迁移 */
    private fun MutablePreferences.updateActiveConfig(transform: (ProviderConfig) -> ProviderConfig) {
        val active = resolveActiveProvider(this)
        val map = providerConfigsWithMigration(this).toMutableMap()
        map[active] = transform(map[active] ?: ProviderConfig())
        this[Keys.PROVIDER_CONFIGS] = AppJson.encodeToString(map)
    }

    suspend fun setBaseUrl(v: String) = context.settingsDataStore.edit { it.updateActiveConfig { c -> c.copy(baseUrl = v.trim()) } }
    suspend fun setApiKey(v: String) = context.settingsDataStore.edit { it.updateActiveConfig { c -> c.copy(apiKey = v.trim()) } }
    suspend fun setModel(v: String) = context.settingsDataStore.edit { it.updateActiveConfig { c -> c.copy(model = v.trim()) } }

    /**
     * 切换当前厂商：加载该厂商已保存的配置（Base URL / 模型为空则填默认值），
     * 并设为生效。各厂商配置相互独立，切换不会丢失其他厂商已填内容；
     * 点 Custom 即选中并加载其槽（空则留空让用户自由填写）。
     */
    suspend fun selectProvider(provider: LlmProvider) = context.settingsDataStore.edit { prefs ->
        val map = providerConfigsWithMigration(prefs).toMutableMap()
        val cur = map[provider.name] ?: ProviderConfig()
        map[provider.name] = cur.copy(
            baseUrl = cur.baseUrl.ifBlank { provider.defaultBaseUrl },
            model = cur.model.ifBlank { provider.defaultModel }
        )
        prefs[Keys.PROVIDER_CONFIGS] = AppJson.encodeToString(map)
        prefs[Keys.ACTIVE_PROVIDER] = provider.name
    }

    /** 视觉模型只写当前厂商槽，切换厂商时互不影响 */
    suspend fun setVisionModel(v: String) = context.settingsDataStore.edit {
        it.updateActiveConfig { c -> c.copy(visionModel = v.trim()) }
    }
    suspend fun setMeshLevel(v: String) = context.settingsDataStore.edit { it[Keys.MESH_LEVEL] = v }
    suspend fun setTimeoutSeconds(v: Int) = context.settingsDataStore.edit { it[Keys.TIMEOUT] = v.coerceIn(5, 600) }
    suspend fun setCandidateCount(v: Int) = context.settingsDataStore.edit { it[Keys.CANDIDATE_COUNT] = v.coerceIn(1, 3) }
    suspend fun setCandidateDivergenceEnabled(v: Boolean) = context.settingsDataStore.edit { it[Keys.CANDIDATE_DIVERGENCE] = v }
    suspend fun setReasoningEffort(v: String) = context.settingsDataStore.edit {
        it[Keys.REASONING_EFFORT] = if (v in setOf("low", "high", "max")) v else "high"
    }
    suspend fun setHighSpeedMode(v: Boolean) = context.settingsDataStore.edit { it[Keys.HIGH_SPEED] = v }

    suspend fun setOptimizeSuggestionCount(v: Int) =
        context.settingsDataStore.edit { it[Keys.OPTIMIZE_SUGGESTION_COUNT] = v.coerceIn(1, 3) }

    suspend fun setOptimizeFastMode(v: Boolean) =
        context.settingsDataStore.edit { it[Keys.OPTIMIZE_FAST_MODE] = v }

    /** 取设备 ID；首次调用时生成并持久化（后续不变，服务端按它统计每日额度） */
    suspend fun ensureDeviceId(): String {
        val existing = current().deviceId
        if (existing.isNotBlank()) return existing
        val id = "android-" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        context.settingsDataStore.edit { it[Keys.DEVICE_ID] = id }
        return id
    }

    /**
     * 激活成功：app_token 写入 API Key（LLM 请求的 Bearer），同时记录每日额度；
     * 另存一份云端凭据（cloud_*），之后改用自备 Key 也不会丢。
     */
    suspend fun setActivation(
        appToken: String,
        dailyN: Int,
        deviceId: String,
        baseUrl: String = "",
        model: String = ""
    ) = context.settingsDataStore.edit {
        val token = appToken.trim()
        it.updateActiveConfig { c -> c.copy(apiKey = token) }
        it[Keys.DAILY_N] = dailyN
        it[Keys.DEVICE_ID] = deviceId
        it[Keys.CLOUD_TOKEN] = token
        it[Keys.CLOUD_DAILY_N] = dailyN
        if (baseUrl.isNotBlank()) it[Keys.CLOUD_BASE_URL] = baseUrl.trim()
        if (model.isNotBlank()) it[Keys.CLOUD_MODEL] = model.trim()
    }

    /**
     * 邀请码激活成功后，把活跃配置原子地切到扣子中转：
     * Base URL 强制为扣子地址、API Key 写入 app_token，覆盖当前槽里用户自备的 Key；
     * 同时把 token / 额度 / 地址 / 模型备份到 cloud_*，供 [restoreCloudActivation] 一键恢复。
     *
     * 扣子域名由 [LlmProvider.fromBaseUrl] 判定为 Custom → LlmClient 发纯净 OpenAI 请求
     * （不带 reasoning_effort / thinking 等专属字段），上游模型与参数由扣子服务端决定。
     * 各厂商配置槽相互独立：切换到扣子不会丢用户在 Kimi / Doubao 等槽里自备的 Key。
     */
    suspend fun applyCozeActivation(
        appToken: String,
        dailyN: Int,
        deviceId: String,
        cozeBaseUrl: String,
        model: String = ""
    ) = context.settingsDataStore.edit { prefs ->
        val token = appToken.trim()
        val url = cozeBaseUrl.trim()
        val prov = LlmProvider.fromBaseUrl(url) // coze.site → Custom
        val map = providerConfigsWithMigration(prefs).toMutableMap()
        val cur = map[prov.name] ?: ProviderConfig()
        map[prov.name] = cur.copy(
            baseUrl = url.ifBlank { cur.baseUrl },
            apiKey = token,
            model = model.trim().ifBlank { cur.model }
        )
        prefs[Keys.PROVIDER_CONFIGS] = AppJson.encodeToString(map)
        prefs[Keys.ACTIVE_PROVIDER] = prov.name
        prefs[Keys.DAILY_N] = dailyN
        prefs[Keys.DEVICE_ID] = deviceId
        prefs[Keys.CLOUD_TOKEN] = token
        prefs[Keys.CLOUD_DAILY_N] = dailyN
        prefs[Keys.CLOUD_BASE_URL] = url
        if (model.isNotBlank()) prefs[Keys.CLOUD_MODEL] = model.trim()
    }

    /**
     * 一键恢复最近一次邀请码激活：把云端 token / Base URL / 模型写入其归属厂商槽并切到该厂商。
     * 纯本地操作，不请求服务端，因此不会再触发 device_already_activated。
     * @return 是否恢复了（没有云端凭据时返回 false）
     */
    suspend fun restoreCloudActivation(): Boolean {
        val s = current()
        if (s.cloudToken.isBlank()) return false
        context.settingsDataStore.edit { prefs ->
            val prov = if (s.cloudBaseUrl.isNotBlank()) LlmProvider.fromBaseUrl(s.cloudBaseUrl) else LlmProvider.Custom
            val map = providerConfigsWithMigration(prefs).toMutableMap()
            val cur = map[prov.name] ?: ProviderConfig()
            map[prov.name] = cur.copy(
                baseUrl = s.cloudBaseUrl.ifBlank { cur.baseUrl },
                apiKey = s.cloudToken,
                model = s.cloudModel.ifBlank { cur.model }
            )
            prefs[Keys.PROVIDER_CONFIGS] = AppJson.encodeToString(map)
            prefs[Keys.ACTIVE_PROVIDER] = prov.name
            prefs[Keys.DAILY_N] = s.cloudDailyN
        }
        return true
    }

    /** 清除激活状态（token、额度与云端备份），保留 Base URL / 模型配置 */
    suspend fun clearActivation() = context.settingsDataStore.edit {
        it.updateActiveConfig { c -> c.copy(apiKey = "") }
        it[Keys.DAILY_N] = 0
        it[Keys.CLOUD_TOKEN] = ""
        it[Keys.CLOUD_DAILY_N] = 0
    }
}
