package com.pocketsim.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 厂商配置槽：每家独立保存 baseUrl / apiKey / model；chip 选中态由显式 activeProvider 决定。
 * 覆盖 v1.2.21 的两个回归：① 各厂商字段被错误共享（删 Doubao 的 key 连带删 Kimi）；
 * ② Custom chip 点击无反应。
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryProviderTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun repo() = SettingsRepository(ctx)

    @Before
    fun resetStore() = runTest {
        ctx.settingsDataStore.edit { prefs ->
            prefs.asMap().keys.toList().forEach { prefs.remove(it) }
        }
    }

    @Test
    fun selectProvider_fillsDefaultsAndSetsActive() = runTest {
        val r = repo()
        r.selectProvider(LlmProvider.Kimi)
        val s = r.current()
        assertEquals(LlmProvider.Kimi.name, s.activeProvider)
        assertEquals(LlmProvider.Kimi.defaultBaseUrl, s.llmBaseUrl)
        assertEquals(LlmProvider.Kimi.defaultModel, s.llmModel)
    }

    @Test
    fun providerApiKeyAndModelAreIndependent() = runTest {
        val r = repo()
        r.selectProvider(LlmProvider.Kimi)
        r.setApiKey("kimi-key")
        r.setModel("kimi-custom-model")

        r.selectProvider(LlmProvider.Doubao)
        r.setApiKey("doubao-key")
        r.setModel("doubao-custom-model")

        // 当前生效的是 Doubao
        var s = r.current()
        assertEquals("doubao-key", s.llmApiKey)
        assertEquals("doubao-custom-model", s.llmModel)

        // 切回 Kimi：之前填的 key / 模型应保留，不被 Doubao 覆盖（修复字段共享）
        r.selectProvider(LlmProvider.Kimi)
        s = r.current()
        assertEquals("kimi-key", s.llmApiKey)
        assertEquals("kimi-custom-model", s.llmModel)

        // 切到 DeepSeek：应为默认值，不受前两者污染
        r.selectProvider(LlmProvider.DeepSeek)
        s = r.current()
        assertEquals("", s.llmApiKey)
        assertEquals(LlmProvider.DeepSeek.defaultBaseUrl, s.llmBaseUrl)
        assertEquals(LlmProvider.DeepSeek.defaultModel, s.llmModel)
    }

    @Test
    fun visionModelIsIndependentPerProvider() = runTest {
        val r = repo()
        // 在 Kimi 下填视觉模型，不能带到豆包/其他厂商（本次修复的字段串用 bug）
        r.selectProvider(LlmProvider.Kimi)
        r.setVisionModel("kimi-latest")

        r.selectProvider(LlmProvider.Doubao)
        assertEquals("", r.current().llmVisionModel)

        r.selectProvider(LlmProvider.Kimi)
        assertEquals("kimi-latest", r.current().llmVisionModel)
    }

    @Test
    fun selectingCustomWorksAndDoesNotClobberOthers() = runTest {
        val r = repo()
        r.selectProvider(LlmProvider.Kimi)
        r.setApiKey("kimi-key")

        // 点 Custom：必须能选中（此前点击无反应）
        r.selectProvider(LlmProvider.Custom)
        var s = r.current()
        assertEquals(LlmProvider.Custom.name, s.activeProvider)
        // Custom 槽为空，不应带出 Kimi 的 key
        assertEquals("", s.llmApiKey)

        // 在 Custom 下填自己的通用地址 + key
        r.setBaseUrl("https://my-proxy.example.com/v1")
        r.setApiKey("proxy-key")

        // 切回 Kimi：Kimi 的 key 应还在，没被 Custom 覆盖
        r.selectProvider(LlmProvider.Kimi)
        s = r.current()
        assertEquals("kimi-key", s.llmApiKey)
        assertEquals(LlmProvider.Kimi.defaultBaseUrl, s.llmBaseUrl)

        // 再切回 Custom：自己填的地址和 key 应保留
        r.selectProvider(LlmProvider.Custom)
        s = r.current()
        assertEquals("https://my-proxy.example.com/v1", s.llmBaseUrl)
        assertEquals("proxy-key", s.llmApiKey)
    }

    @Test
    fun switchingAwayAndBackPreservesEditedModel() = runTest {
        val r = repo()
        r.selectProvider(LlmProvider.Doubao)
        r.setModel("doubao-tuned-001")          // 手动改过模型
        r.selectProvider(LlmProvider.Kimi)      // 切走
        r.selectProvider(LlmProvider.Doubao)    // 切回
        val s = r.current()
        assertEquals("doubao-tuned-001", s.llmModel)  // 不应被默认值覆盖
    }

    @Test
    fun legacySingleConfigMigratesToProviderSlot() = runTest {
        // 模拟旧版本：只有一套 llm_base_url / api_key / model，没有厂商槽
        val legacyUrl = "https://api.moonshot.cn/v1"
        ctx.settingsDataStore.edit {
            it[stringPreferencesKey("llm_base_url")] = legacyUrl
            it[stringPreferencesKey("llm_api_key")] = "legacy-key"
            it[stringPreferencesKey("llm_model")] = "kimi-k3.1-pro"
        }
        val s = repo().current()
        assertEquals(LlmProvider.Kimi.name, s.activeProvider)
        assertEquals(legacyUrl, s.llmBaseUrl)
        assertEquals("legacy-key", s.llmApiKey)
        assertEquals("kimi-k3.1-pro", s.llmModel)
    }

    private val cozeUrl = "https://8yxx6n3dqj.coze.site/v1"

    @Test
    fun applyCozeActivation_switchesActiveToCustomSlot() = runTest {
        val r = repo()
        // 激活前停在 Doubao，且用户自备了一个 key
        r.selectProvider(LlmProvider.Doubao)
        r.setApiKey("doubao-self-key")

        r.applyCozeActivation(
            appToken = "coze-app-token-001",
            dailyN = 50,
            deviceId = "android-testdev01",
            cozeBaseUrl = cozeUrl,
            model = "doubao-seed-2-0-pro-260215"
        )

        val s = r.current()
        // 活跃配置切到扣子（coze.site → Custom），Key 被 app_token 覆盖
        assertEquals(LlmProvider.Custom.name, s.activeProvider)
        assertEquals(cozeUrl, s.llmBaseUrl)
        assertEquals("coze-app-token-001", s.llmApiKey)
        // activated 判定：cloudToken == llmApiKey
        assertEquals(s.cloudToken, s.llmApiKey)
        assertEquals(cozeUrl, s.cloudBaseUrl)
        assertEquals(50, s.activatedDailyN)
        assertEquals(50, s.cloudDailyN)
        assertEquals("android-testdev01", s.deviceId)
    }

    @Test
    fun applyCozeActivation_doesNotClobberOtherProviderKeys() = runTest {
        val r = repo()
        // Kimi 槽里有用户自备 Key，激活切到扣子不应丢失
        r.selectProvider(LlmProvider.Kimi)
        r.setApiKey("kimi-self-key")
        r.setModel("kimi-k3.1-pro")

        r.applyCozeActivation(
            appToken = "coze-app-token-002",
            dailyN = 30,
            deviceId = "android-testdev02",
            cozeBaseUrl = cozeUrl
        )

        // 切回 Kimi：自备 Key 与模型仍在（厂商槽独立）
        r.selectProvider(LlmProvider.Kimi)
        var s = r.current()
        assertEquals("kimi-self-key", s.llmApiKey)
        assertEquals("kimi-k3.1-pro", s.llmModel)

        // 再切回扣子（Custom）：仍是激活写入的 token / 地址
        r.selectProvider(LlmProvider.Custom)
        s = r.current()
        assertEquals("coze-app-token-002", s.llmApiKey)
        assertEquals(cozeUrl, s.llmBaseUrl)
    }

    @Test
    fun restoreCloudActivation_returnsToCozeAfterSwitchingAway() = runTest {
        val r = repo()
        r.applyCozeActivation(
            appToken = "coze-app-token-003",
            dailyN = 20,
            deviceId = "android-testdev03",
            cozeBaseUrl = cozeUrl
        )
        // 用户临时切去 Kimi 用自备 Key
        r.selectProvider(LlmProvider.Kimi)
        r.setApiKey("kimi-self-key")
        assertEquals("kimi-self-key", r.current().llmApiKey)

        // 一键恢复：应回到扣子（Custom）槽，纯本地操作不触发 device_already_activated
        val restored = r.restoreCloudActivation()
        assertEquals(true, restored)

        val s = r.current()
        assertEquals(LlmProvider.Custom.name, s.activeProvider)
        assertEquals(cozeUrl, s.llmBaseUrl)
        assertEquals("coze-app-token-003", s.llmApiKey)
        assertEquals(s.cloudToken, s.llmApiKey)
        assertEquals(20, s.activatedDailyN)
    }
}
