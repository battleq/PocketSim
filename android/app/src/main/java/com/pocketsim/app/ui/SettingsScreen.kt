/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import com.pocketsim.app.BuildConfig
import com.pocketsim.app.LlmProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketsim.app.AppContainer
import com.pocketsim.app.AppSettings
import com.pocketsim.app.R
import com.pocketsim.app.ui.components.LanguageSelector
import com.pocketsim.app.ui.components.UnitSelector
import com.pocketsim.app.ui.components.engineeringChipColors
import kotlinx.coroutines.launch

/**
 * 设置页：LLM API Base URL / API Key / 模型名（DataStore 持久化）、
 * 默认网格档位、请求超时秒数；附免责声明全文与版本号。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenOss: () -> Unit = {},
    onOpenActivation: () -> Unit = {}
) {
    val repo = container.settingsRepository
    val settings by repo.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()

    // 超时输入的本地缓冲（避免输入中途被协程回写打断）
    var timeoutText by remember(settings.timeoutSeconds) {
        mutableStateOf(settings.timeoutSeconds.toString())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()  // edge-to-edge 下让出 IME 高度
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---- LLM 接口配置 ----
            Text(
                text = stringResource(R.string.settings_llm_header),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            // ---- 厂商预设：每家独立保存配置；选中态由显式 activeProvider 决定 ----
            val currentProvider = runCatching { LlmProvider.valueOf(settings.activeProvider) }
                .getOrDefault(LlmProvider.Doubao)
            Text(
                text = stringResource(R.string.settings_provider_label),
                style = MaterialTheme.typography.bodyMedium
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    LlmProvider.Kimi to R.string.settings_provider_kimi,
                    LlmProvider.Doubao to R.string.settings_provider_doubao,
                    LlmProvider.DeepSeek to R.string.settings_provider_deepseek,
                    LlmProvider.Custom to R.string.settings_provider_custom
                ).forEach { (prov, labelRes) ->
                    FilterChip(
                        selected = currentProvider == prov,
                        onClick = {
                            scope.launch { repo.selectProvider(prov) }
                        },
                        colors = engineeringChipColors(),
                        label = { Text(stringResource(labelRes)) }
                    )
                }
            }
            Text(
                text = stringResource(
                    when (currentProvider) {
                        LlmProvider.Kimi -> R.string.settings_provider_hint_kimi
                        LlmProvider.Doubao -> R.string.settings_provider_hint_doubao
                        LlmProvider.DeepSeek -> R.string.settings_provider_hint_deepseek
                        LlmProvider.Custom -> R.string.settings_provider_hint_custom
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = settings.llmBaseUrl,
                onValueChange = { scope.launch { repo.setBaseUrl(it) } },
                label = { Text(stringResource(R.string.settings_base_url)) },
                placeholder = { Text(stringResource(R.string.settings_base_url_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = settings.llmApiKey,
                onValueChange = { scope.launch { repo.setApiKey(it) } },
                label = { Text(stringResource(R.string.settings_api_key)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = settings.llmModel,
                onValueChange = { scope.launch { repo.setModel(it) } },
                label = { Text(stringResource(R.string.settings_model)) },
                placeholder = { Text(stringResource(R.string.settings_model_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = settings.llmVisionModel,
                onValueChange = { scope.launch { repo.setVisionModel(it) } },
                label = { Text(stringResource(R.string.settings_vision_model)) },
                placeholder = { Text(stringResource(R.string.settings_vision_model_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Kimi 建模推理强度（仅 Kimi 域名生效，由 provider 自动识别）
            if (currentProvider == LlmProvider.Kimi) {
                Text(
                    text = stringResource(R.string.settings_reasoning_effort),
                    style = MaterialTheme.typography.bodyMedium
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        "low" to R.string.settings_reasoning_low,
                        "high" to R.string.settings_reasoning_high,
                        "max" to R.string.settings_reasoning_max
                    ).forEach { (effort, labelRes) ->
                        FilterChip(
                            selected = settings.llmReasoningEffort == effort,
                            onClick = { scope.launch { repo.setReasoningEffort(effort) } },
                            colors = engineeringChipColors(),
                            label = { Text(stringResource(labelRes)) }
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.settings_reasoning_effort_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 高速建模：仅 Kimi 域名生效，开启后自动切换高速版模型（同效果约 2–3 倍速）
            if (currentProvider == LlmProvider.Kimi) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_high_speed),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.settings_high_speed_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.highSpeedMode,
                        onCheckedChange = { v -> scope.launch { repo.setHighSpeedMode(v) } }
                    )
                }
            }

            // 邀请码激活：激活后 API Key 由 app_token 自动管理，无需手工填
            OutlinedButton(
                onClick = onOpenActivation,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.VpnKey, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    if (settings.llmApiKey.isBlank()) stringResource(R.string.settings_activate)
                    else stringResource(R.string.settings_activated_fmt, settings.activatedDailyN)
                )
            }

            // ---- 语言 ----
            Text(
                text = stringResource(R.string.settings_language_header),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            LanguageSelector()
            Text(
                text = stringResource(R.string.settings_language_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- 单位制 ----
            Text(
                text = stringResource(R.string.settings_units_header),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            UnitSelector()
            Text(
                text = stringResource(R.string.settings_units_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- 默认网格档位 ----
            Text(
                text = stringResource(R.string.settings_mesh_default),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = settings.defaultMeshLevel == "coarse",
                    onClick = { scope.launch { repo.setMeshLevel("coarse") } },
                    colors = engineeringChipColors(),
                    label = { Text(stringResource(R.string.mesh_coarse)) }
                )
                FilterChip(
                    selected = settings.defaultMeshLevel == "medium",
                    onClick = { scope.launch { repo.setMeshLevel("medium") } },
                    colors = engineeringChipColors(),
                    label = { Text(stringResource(R.string.mesh_medium)) }
                )
                FilterChip(
                    selected = settings.defaultMeshLevel == "fine",
                    onClick = { scope.launch { repo.setMeshLevel("fine") } },
                    colors = engineeringChipColors(),
                    label = { Text(stringResource(R.string.mesh_fine)) }
                )
                FilterChip(
                    selected = settings.defaultMeshLevel == "ultra",
                    onClick = { scope.launch { repo.setMeshLevel("ultra") } },
                    colors = engineeringChipColors(),
                    label = { Text(stringResource(R.string.mesh_ultra)) }
                )
            }

            // ---- 请求超时 ----
            Text(
                text = stringResource(R.string.settings_timeout),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = timeoutText,
                onValueChange = {
                    timeoutText = it
                    it.toIntOrNull()?.let { v -> scope.launch { repo.setTimeoutSeconds(v) } }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            // ---- AI 建模候选：首轮候选数量 + 差异控制开关 ----
            Text(
                text = stringResource(R.string.settings_candidate_header),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.settings_candidate_count),
                style = MaterialTheme.typography.bodyMedium
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(1, 2, 3).forEach { n ->
                    FilterChip(
                        selected = settings.candidateCount == n,
                        onClick = { scope.launch { repo.setCandidateCount(n) } },
                        colors = engineeringChipColors(),
                        label = { Text(stringResource(R.string.settings_candidate_count_fmt, n)) }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_candidate_divergence),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.settings_candidate_divergence_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.candidateDivergenceEnabled,
                    onCheckedChange = { v -> scope.launch { repo.setCandidateDivergenceEnabled(v) } }
                )
            }

            // ---- AI 结构优化建议：方案数量 ----
            Text(
                text = stringResource(R.string.settings_optimize_header),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.settings_optimize_count),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.settings_optimize_count_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(1, 2, 3).forEach { n ->
                    FilterChip(
                        selected = settings.optimizeSuggestionCount == n,
                        onClick = { scope.launch { repo.setOptimizeSuggestionCount(n) } },
                        colors = engineeringChipColors(),
                        label = { Text(stringResource(R.string.settings_optimize_count_fmt, n)) }
                    )
                }
            }
            // O-06 快速模式：优化通道单独压低/关闭深度思考
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_optimize_fast),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.settings_optimize_fast_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.optimizeFastMode,
                    onCheckedChange = { v -> scope.launch { repo.setOptimizeFastMode(v) } }
                )
            }

            // ---- 关于：版本 + 免责声明全文 ----
            Text(
                text = stringResource(R.string.settings_about_header),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_version_fmt, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = stringResource(R.string.disclaimer_full),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        androidx.compose.material3.TextButton(onClick = onOpenOss) {
                            Text(stringResource(R.string.settings_oss_entry))
                        }
                    }
                }
            }
        }
    }
}
