/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.ActivationClient
import com.pocketsim.app.AppContainer
import com.pocketsim.app.AppSettings
import com.pocketsim.app.LocaleManager
import com.pocketsim.app.LocaleStore
import com.pocketsim.app.R
import kotlinx.coroutines.launch

/**
 * 邀请码激活页：填邀请码 → POST /v1/activate 到固定的扣子中转地址 → app_token 写入 API Key，
 * 同时把活跃配置切到扣子（Custom 槽）并记录每日额度。激活后聊天页的 Bearer 就是这个 token，
 * 聊天请求经扣子中转到上游大模型。服务地址框仅显示当前路由，激活调用固定走 [COZE_BASE_URL]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivationScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val repo = container.settingsRepository
    val settings by repo.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var baseText by remember(settings.llmBaseUrl) { mutableStateOf(settings.llmBaseUrl) }
    var code by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf(settings.deviceId) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var messageIsError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        deviceId = repo.ensureDeviceId()
        // 首次进入时预填当前部署地址与模型，避免用户不知道填什么；之后仍可在设置页修改
        val s = repo.current()
        if (s.llmBaseUrl.isBlank()) {
            repo.setBaseUrl(DEFAULT_BASE_URL)
            baseText = DEFAULT_BASE_URL
        }
        if (s.llmModel.isBlank()) repo.setModel(DEFAULT_MODEL)
    }

    // 只有当前配置用的就是云端 token 时才算"已激活"；改用自备 Key 后不再显示已激活
    val activated = settings.cloudToken.isNotBlank() && settings.llmApiKey == settings.cloudToken
    // 曾激活过、但现在临时用了别的 Key → 提供一键恢复，不必重新激活（服务端会返回 409）
    val canRestore = settings.cloudToken.isNotBlank() && settings.llmApiKey != settings.cloudToken

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activation_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                stringResource(R.string.activation_desc),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (activated) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(stringResource(R.string.activation_activated), fontWeight = FontWeight.Bold)
                        Text(
                            if (settings.activatedDailyN > 0)
                                stringResource(R.string.activation_daily_fmt, settings.activatedDailyN)
                            else stringResource(R.string.activation_manual_key),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.activation_device_id_fmt, settings.deviceId.ifBlank { stringResource(R.string.activation_not_generated) }),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.activation_credential_fmt, maskToken(settings.llmApiKey)),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    repo.clearActivation()
                                    messageIsError = false
                                    message = context.getString(R.string.activation_cleared)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.activation_clear_btn)) }
                    }
                }
            }

            if (canRestore) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            if (repo.restoreCloudActivation()) {
                                messageIsError = false
                                message = context.getString(R.string.activation_restored)
                                baseText = repo.current().llmBaseUrl
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.activation_restore_btn)) }
            }

            Text(stringResource(R.string.activation_server_addr), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = baseText,
                onValueChange = {
                    baseText = it
                    scope.launch { repo.setBaseUrl(it) }
                },
                label = { Text("API Base URL") },
                placeholder = { Text(COZE_BASE_URL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(stringResource(R.string.activation_invite_code), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = code,
                onValueChange = {
                    code = it.trim()
                    message = ""
                },
                label = { Text(stringResource(R.string.activation_invite_code)) },
                placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                stringResource(R.string.activation_local_device_id_fmt, deviceId.ifBlank { stringResource(R.string.activation_generating) }),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                enabled = !loading && code.isNotBlank() && deviceId.isNotBlank(),
                onClick = {
                    loading = true
                    message = ""
                    scope.launch {
                        try {
                            // 激活固定走扣子中转：扣子域名 → Custom 槽，发纯净 OpenAI 请求，
                            // 上游模型与参数由扣子服务端决定；baseText 仅用于显示，不参与激活调用
                            val info = ActivationClient(
                                COZE_BASE_URL,
                                english = LocaleManager.currentLanguageCode(context) == LocaleStore.LANG_EN
                            ).activate(code, deviceId)
                            val cur = repo.current()
                            repo.applyCozeActivation(
                                info.appToken,
                                info.dailyN,
                                info.deviceId,
                                COZE_BASE_URL,
                                model = cur.llmModel.ifBlank { DEFAULT_MODEL }
                            )
                            baseText = COZE_BASE_URL
                            messageIsError = false
                            message = context.getString(R.string.activation_success_fmt, info.dailyN)
                            code = ""
                        } catch (e: Exception) {
                            val raw = e.message ?: context.getString(R.string.activation_failed_default)
                            if (raw.contains("device_already_activated")) {
                                // 设备已在服务端登记过：优先用本地保存的凭据恢复，无需重新激活
                                if (repo.restoreCloudActivation()) {
                                    messageIsError = false
                                    message = context.getString(R.string.activation_restored)
                                    baseText = repo.current().llmBaseUrl
                                } else {
                                    messageIsError = true
                                    message = context.getString(R.string.activation_device_taken_fmt, deviceId)
                                }
                            } else {
                                messageIsError = true
                                message = raw
                            }
                        } finally {
                            loading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 10.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(if (activated) stringResource(R.string.activation_reactivate) else stringResource(R.string.activation_activate), fontSize = 16.sp)
            }

            if (message.isNotBlank()) {
                Text(
                    message,
                    fontSize = 13.sp,
                    color = if (messageIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    stringResource(R.string.activation_admin_note),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 凭据脱敏：保留前 6 位与后 4 位 */
internal fun maskToken(token: String): String =
    if (token.length <= 12) "***" else token.take(6) + "…" + token.takeLast(4)

/** 默认 Base URL：方舟直连（Base URL 必须带 /v1 或 /api/v3） */
private const val DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"

/**
 * 邀请码激活固定走的扣子中转地址。
 * coze.site 不命中任何厂商判定 → LlmProvider.Custom，LlmClient 发纯净 OpenAI 请求
 * （不带 reasoning_effort / thinking），上游模型与参数由扣子服务端决定。
 */
private const val COZE_BASE_URL = "https://8yxx6n3dqj.coze.site/v1"

/** 当前部署实际生效的模型 ID（聊天返回里的 model 字段） */
private const val DEFAULT_MODEL = "doubao-seed-2-0-pro-260215"
