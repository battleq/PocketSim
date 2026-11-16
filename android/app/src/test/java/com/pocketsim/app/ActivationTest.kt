package com.pocketsim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 邀请码激活的纯逻辑测试：端点归一化 + 响应解析（不发起真实网络请求）。 */
class ActivationTest {

    @Test
    fun `baseUrl 带 v1 与不带 v1 都归一化到同一激活端点`() {
        assertEquals(
            "https://api.example.com/v1/activate",
            activationEndpoint("https://api.example.com/v1")
        )
        assertEquals(
            "https://api.example.com/v1/activate",
            activationEndpoint("https://api.example.com/v1/")
        )
        assertEquals(
            "https://api.example.com/v1/activate",
            activationEndpoint(" https://api.example.com ")
        )
    }

    @Test
    fun `baseUrl 为空或非 http 时给出中文提示`() {
        val empty = runCatching { activationEndpoint("") }.exceptionOrNull()
        assertTrue(empty is java.io.IOException && empty.message!!.contains("API Base URL"))

        val bad = runCatching { activationEndpoint("api.example.com") }.exceptionOrNull()
        assertTrue(bad is java.io.IOException && bad.message!!.contains("无效"))
    }

    @Test
    fun `解析激活成功响应`() {
        val info = parseActivation(
            """{"app_token":"nGdy-test-fixture-not-a-real-token-0000",
                "device_id":"android-abc","daily_n":50,"expires_at":"2026-11-01T00:00:00+08:00"}""",
            "android-fallback"
        )
        assertEquals(50, info.dailyN)
        assertEquals("android-abc", info.deviceId)
        assertTrue(info.appToken.startsWith("nGdy"))
    }

    @Test
    fun `解析错误响应带中文信息`() {
        val e = runCatching {
            parseActivation("""{"error":{"code":"invalid_code","message":"邀请码无效"}}""", "d")
        }.exceptionOrNull()
        assertTrue(e is java.io.IOException && e.message!!.contains("邀请码无效"))
    }
}
