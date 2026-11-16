/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 激活成功后的凭据与额度 */
data class ActivationInfo(
    val appToken: String,
    val deviceId: String,
    val dailyN: Int,
    val expiresAt: String
)

/**
 * 邀请码激活客户端：POST {root}/v1/activate。
 *
 * 用户填写的 Base URL 可能是 `https://host` 或 `https://host/v1`，
 * activationEndpoint 会统一归一化到 `{root}/v1/activate`（避免出现 /v1/v1/activate）。
 */
class ActivationClient(
    private val baseUrl: String,
    private val timeoutSeconds: Int = 30,
    private val english: Boolean = false
) {

    private fun t(zh: String, en: String): String = if (english) en else zh

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
    }

    suspend fun activate(code: String, deviceId: String): ActivationInfo =
        withContext(Dispatchers.IO) {
            val url = activationEndpoint(baseUrl, english)
            val body = buildJsonObject {
                put("code", code.trim())
                put("device_id", deviceId)
            }
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = errorMessage(text)
                    throw IOException(
                        when (resp.code) {
                            401, 403 -> t(
                                "激活失败：授权被拒绝（HTTP ${resp.code}）$msg",
                                "Activation failed: access denied (HTTP ${resp.code})$msg"
                            )
                            404 -> t(
                                "激活失败：接口地址不存在（HTTP 404），请检查 API Base URL$msg",
                                "Activation failed: endpoint not found (HTTP 404), please check the API Base URL$msg"
                            )
                            else -> t("激活失败：HTTP ${resp.code}$msg", "Activation failed: HTTP ${resp.code}$msg")
                        }
                    )
                }
                parseActivation(text, deviceId, english)
            }
        }

    /** 服务端错误体形如 {"error":{"code":"invalid_code","message":"邀请码无效"}} */
    private fun errorMessage(raw: String): String = runCatching {
        val err = AppJson.parseToJsonElement(raw).jsonObject["error"]?.jsonObject
        val code = err?.get("code")?.jsonPrimitive?.content.orEmpty()
        val message = err?.get("message")?.jsonPrimitive?.content.orEmpty()
        if (message.isBlank()) "" else
            if (english) ": $message" + if (code.isBlank()) "" else " ($code)"
            else "：$message" + if (code.isBlank()) "" else "（$code）"
    }.getOrDefault("")
}

/** 归一化激活端点：`https://host/v1` 与 `https://host` 都得到 `https://host/v1/activate` */
internal fun activationEndpoint(baseUrl: String, english: Boolean = false): String {
    fun t(zh: String, en: String): String = if (english) en else zh
    val trimmed = baseUrl.trim().trimEnd('/')
    if (trimmed.isBlank()) throw IOException(
        t(
            "请先在设置页填写 API Base URL（如 https://ark.cn-beijing.volces.com/api/v3）",
            "Please set the API Base URL first (e.g. https://ark.cn-beijing.volces.com/api/v3)"
        )
    )
    if (!trimmed.startsWith("http")) throw IOException(
        t("API Base URL 无效：$baseUrl", "Invalid API Base URL: $baseUrl")
    )
    val root = if (trimmed.endsWith("/v1")) trimmed.dropLast(3).trimEnd('/') else trimmed
    return "$root/v1/activate"
}

/** 解析激活响应：{"app_token":"...","device_id":"...","daily_n":50,"expires_at":"..."} */
internal fun parseActivation(raw: String, fallbackDeviceId: String, english: Boolean = false): ActivationInfo {
    fun t(zh: String, en: String): String = if (english) en else zh
    val root = try {
        AppJson.parseToJsonElement(raw).jsonObject
    } catch (e: Exception) {
        throw IOException(
            t("激活接口返回的不是合法 JSON：${raw.take(120)}", "The activation endpoint returned invalid JSON: ${raw.take(120)}"),
            e
        )
    }
    root["error"]?.let { err ->
        val msg = runCatching { err.jsonObject["message"]?.jsonPrimitive?.content }
            .getOrNull() ?: err.toString()
        throw IOException(t("激活失败：$msg", "Activation failed: $msg"))
    }
    val token = root["app_token"]?.jsonPrimitive?.content.orEmpty()
    if (token.isBlank()) throw IOException(
        t("激活失败：服务端未返回 app_token", "Activation failed: the server returned no app_token")
    )
    return ActivationInfo(
        appToken = token,
        deviceId = root["device_id"]?.jsonPrimitive?.content ?: fallbackDeviceId,
        dailyN = root["daily_n"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        expiresAt = root["expires_at"]?.jsonPrimitive?.content.orEmpty()
    )
}
