/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketsim.app.AppContainer
import com.pocketsim.app.FrameFollowUpSession
import com.pocketsim.app.FrameModel
import com.pocketsim.app.FrameResult
import com.pocketsim.app.LocaleManager
import com.pocketsim.app.R
import com.pocketsim.app.Units
import com.pocketsim.app.modelDisplayName
import com.pocketsim.app.ui.components.LoadingBubble
import com.pocketsim.app.ui.components.MessageBubble
import kotlinx.coroutines.launch

/**
 * 框架结果追问页（v0.3.0）：以"当前模型 + 求解结果"为上下文的对话。
 *  - 顶部结果摘要卡：结论 + 三指标一行（离开结果页也能看懂在聊什么）；
 *  - 就结果提问 → AI 通俗回答；要改条件 → AI 给出修改后的完整模型，
 *    跳预览确认页 → 重算（历史结果保留在路由栈中可逐层回看）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameFollowUpScreen(
    container: AppContainer,
    model: FrameModel,
    result: FrameResult,
    onBack: () -> Unit,
    /** 修改通过校验 → 携带新模型跳预览确认页 */
    onModelReady: (FrameModel) -> Unit
) {
    val greeting = stringResource(R.string.frame_followup_greeting)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val languageCode = LocaleManager.currentLanguageCode(context)
    val session = remember(model, result, languageCode) {
        FrameFollowUpSession(
            aiClientProvider = container.aiClientProvider,
            model = model,
            result = result,
            onModelReady = onModelReady,
            greeting = greeting,
            language = languageCode
        )
    }

    val messages by session.messages.collectAsState()
    val busy by session.busy.collectAsState()
    val fatal by session.fatalError.collectAsState()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 新消息自动滚动到底部
    LaunchedEffect(messages.size, busy) {
        val lastIndex = messages.lastIndex + if (busy) 1 else 0
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.frame_followup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                }
            )
        }
    ) { padding ->
        // imePadding：同 ChatScreen，edge-to-edge 下让出 IME 高度
        Column(modifier = Modifier.padding(padding).imePadding()) {
            // ---- 结果摘要卡（对话上下文一览） ----
            Card(
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = modelDisplayName(result.model_name).ifBlank { stringResource(R.string.frame_result_title) },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(
                            R.string.frame_followup_summary_fmt,
                            Units.displacement(result.max_disp_mm).toString(),
                            Units.stress(result.max_stress_MPa).toString(),
                            if (result.safety_factor >= 999.0) "999+"
                            else formatNum(result.safety_factor),
                            verdictLabel(result.verdict)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- 接口异常提示条 ----
            if (fatal) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = stringResource(R.string.chat_error_network),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // ---- 消息列表 ----
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { m -> MessageBubble(m) }
                if (busy) item { LoadingBubble() }
            }

            // ---- 输入行 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(stringResource(R.string.frame_followup_input_hint)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 3
                )
                FilledIconButton(
                    onClick = {
                        val text = input
                        input = ""
                        scope.launch { session.send(text) }
                    },
                    enabled = !busy && input.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                }
            }
        }
    }
}
// 数值显示复用 Format.kt 的 internal formatNum（同包）
