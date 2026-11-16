/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pocketsim.app.AiSkill
import com.pocketsim.app.AppContainer
import com.pocketsim.app.AppSettings
import com.pocketsim.app.ChatSession
import com.pocketsim.app.FlowModel
import com.pocketsim.app.FrameModel
import com.pocketsim.app.LocaleManager
import com.pocketsim.app.LocaleStore
import com.pocketsim.app.ModelCandidate
import com.pocketsim.app.SeepageModel
import com.pocketsim.app.ShellBuriedPipeModel
import com.pocketsim.app.ShellVesselModel
import com.pocketsim.app.SolidWallModel
import com.pocketsim.app.SolidGenericModel
import com.pocketsim.app.TransientSpec
import com.pocketsim.app.BuildConfig
import com.pocketsim.app.R
import com.pocketsim.app.ui.components.LoadingBubble
import com.pocketsim.app.ui.components.MessageBubble
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * AI 对话建模页：消息列表 + 输入框 + 流式占位 loading；
 * 建模完成跳模型预览确认页（FramePreview）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    problemText: String,
    onBack: () -> Unit,
    threeD: Boolean = false,
    /** 强制三维实体（TET4）：忽略梁元/流体等分支，只输出 solid_wall 契约 */
    forceSolid: Boolean = false,
    /** 强制通用三维实体（box/cylinder/plate）：只输出 solid_generic 契约 */
    forceSolidGeneric: Boolean = false,
    /** 强制薄壁容器（水箱/料仓）：只输出 shell_vessel 契约 */
    forceShell: Boolean = false,
    /** 强制埋地管（覆土+车载工程公式）：只输出 shell_buried_pipe 契约 */
    forceBuriedPipe: Boolean = false,
    initialImages: List<String> = emptyList(),
    regenFeedback: String = "",
    /** 预览页"修改模型"时的当前模型：强锚定注入提示词，只改用户指定字段；首轮建模为 null */
    regenAnchor: FrameModel? = null,
    /** 首页选择的 AI 技能（可为空）：只影响建模取向，不改变输出契约 */
    skill: AiSkill? = null,
    onFrameReady: (FrameModel) -> Unit = {},
    onFlowReady: (FlowModel) -> Unit = {},
    onSeepageReady: (SeepageModel) -> Unit = {},
    onTransientReady: (TransientSpec) -> Unit = {},
    onSolidReady: (SolidWallModel) -> Unit = {},
    onSolidGenericReady: (SolidGenericModel) -> Unit = {},
    onShellReady: (ShellVesselModel) -> Unit = {},
    onBuriedPipeReady: (ShellBuriedPipeModel) -> Unit = {},
    onCandidatesReady: (List<ModelCandidate>) -> Unit = {}
) {
    ChatContent(
        container = container,
        problemText = problemText,
        initialImages = initialImages,
        regenFeedback = regenFeedback,
        regenAnchor = regenAnchor,
        onBack = onBack,
        threeD = threeD,
        forceSolid = forceSolid,
        forceSolidGeneric = forceSolidGeneric,
        forceShell = forceShell,
        forceBuriedPipe = forceBuriedPipe,
        skill = skill,
        onFrameReady = onFrameReady,
        onFlowReady = onFlowReady,
        onSeepageReady = onSeepageReady,
        onTransientReady = onTransientReady,
        onSolidReady = onSolidReady,
        onSolidGenericReady = onSolidGenericReady,
        onShellReady = onShellReady,
        onBuriedPipeReady = onBuriedPipeReady,
        onCandidatesReady = onCandidatesReady
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    container: AppContainer,
    problemText: String,
    initialImages: List<String>,
    regenFeedback: String,
    /** 预览页"修改模型"时的当前模型：强锚定注入提示词，只改用户指定字段；首轮建模为 null */
    regenAnchor: FrameModel? = null,
    onBack: () -> Unit,
    threeD: Boolean = false,
    forceSolid: Boolean = false,
    forceSolidGeneric: Boolean = false,
    forceShell: Boolean = false,
    forceBuriedPipe: Boolean = false,
    skill: AiSkill? = null,
    onFrameReady: (FrameModel) -> Unit,
    onFlowReady: (FlowModel) -> Unit = {},
    onSeepageReady: (SeepageModel) -> Unit = {},
    onTransientReady: (TransientSpec) -> Unit = {},
    onSolidReady: (SolidWallModel) -> Unit = {},
    onSolidGenericReady: (SolidGenericModel) -> Unit = {},
    onShellReady: (ShellVesselModel) -> Unit = {},
    onBuriedPipeReady: (ShellBuriedPipeModel) -> Unit = {},
    onCandidatesReady: (List<ModelCandidate>) -> Unit = {}
) {
    val greeting = stringResource(
        if (forceSolid || forceSolidGeneric || forceShell || forceBuriedPipe) R.string.solid_chat_greeting
        else R.string.frame_chat_greeting
    )
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 当前语言对应的材料体系：英文 → 美标（us），其余 → 国标（gb）
    val languageCode = LocaleManager.currentLanguageCode(context)
    val materialSystem = if (languageCode == LocaleStore.LANG_EN) "us" else "gb"
    // 加载当前材料库全部材料名（含联网检索/自定义），注入 ChatSession 系统提示词，
    // 让 AI 能识别橡木等库外材料
    var materialNames by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        runCatching { container.engine.listMaterials(materialSystem) }
            .onSuccess { materialNames = it.map { m -> m.name } }
    }
    // 候选数量与差异控制从设置实时读取（用取值函数传入，改设置不重建会话、不清空对话）
    val settings by container.settingsRepository.settings.collectAsState(initial = AppSettings())
    // 材料库异步加载完成时**不能**重建 ChatSession（会清空已发送的气泡与在建请求，
    // 表现为"输入框里有字、没有用户气泡，过一会却直接跳到结果页"），
    // 因此只把材料名以「取值函数」传给 session，每次建模时现取最新值。
    val session = remember(threeD, forceSolid, forceSolidGeneric, forceShell, forceBuriedPipe, skill?.id, languageCode, regenAnchor) {
        ChatSession(
            aiClientProvider = container.aiClientProvider,
            greeting = greeting,
            threeD = threeD,
            forceSolid = forceSolid,
            forceSolidGeneric = forceSolidGeneric,
            forceShell = forceShell,
            forceBuriedPipe = forceBuriedPipe,
            skill = skill,
            materialNamesProvider = { materialNames },
            language = languageCode,
            onFrameReady = onFrameReady,
            onFlowReady = onFlowReady,
            onSeepageReady = onSeepageReady,
            onTransientReady = onTransientReady,
            onSolidReady = onSolidReady,
            onSolidGenericReady = onSolidGenericReady,
            onShellReady = onShellReady,
            onBuriedPipeReady = onBuriedPipeReady,
            onCaseLog = { error, userText, sessionId, inputForm, llmAttempts, firstError ->
                // 校验失败案例埋点：构建上下文 JSON 交给 Python bridge 写入 case_log.jsonl
                // llmAttempts=LLM 总尝试次数、firstError=首轮错误（含错误码），统计一次/重试后通过率
                val ctx = buildJsonObject {
                    putJsonObject("model") {}
                    put("error", error)
                    put("user_text", userText)
                    put("session_id", sessionId)
                    put("input_form", inputForm)
                    put("stage", "validate")
                    put("detected", true)
                    put("llm_attempts", llmAttempts)
                    put("first_error", firstError)
                }.toString()
                runCatching { container.engine.logCaseContext(ctx) }
            },
            // 案例埋点仅调试包开启：正式包不注入 case_meta、不写 case_log.jsonl
            caseLogEnabled = BuildConfig.DEBUG,
            regenAnchor = regenAnchor,
            candidateCountProvider = { settings.candidateCount },
            candidateDivergenceEnabledProvider = { settings.candidateDivergenceEnabled },
            // 框架几何不变性预检：多候选阶段剔除机构/近机构模型。预检自身异常按"稳定"放行，
            // 避免预检 bug 阻塞生成（runCatching → null）。
            stabilityChecker = { m -> runCatching { container.engine.checkFrameStability(m) }.getOrNull() },
            onCandidatesReady = onCandidatesReady,
            // 多候选诊断日志：release 也打 Logcat，便于真机排查候选缺失是限流还是过滤
            onCandidateDiag = { Log.i("PocketSimCand", it) }
        )
    }

    val messages by session.messages.collectAsState()
    val busy by session.busy.collectAsState()
    val fatal by session.fatalError.collectAsState()

    // 追问输入框：默认空白（首页带来的问题描述已自动发送，不需要再预填）
    var input by remember { mutableStateOf("") }
    // AI 润色：手动触发，成功后可用“撤销润色”恢复原文
    var polishing by remember { mutableStateOf(false) }
    var polishError by remember { mutableStateOf<String?>(null) }
    var beforePolish by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    var autoSent by remember { mutableStateOf(false) }

    // 附图：base64 JPEG 数据 URI + 缩略图，随下一条消息发出
    var attached by remember { mutableStateOf<List<Pair<String, androidx.compose.ui.graphics.ImageBitmap>>>(emptyList()) }
    var importing by remember { mutableStateOf(false) }

    // 系统相册选择（PhotoPicker：新系统原生选择器，旧系统自动回落，均无需存储权限）
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(3)
    ) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            importing = true
            val room = 3 - attached.size
            attached = attached + uris.take(room).mapNotNull { compressToDataUri(context, it) }
            importing = false
        }
    }

    // 进入页面自动发送首页带来的问题描述与附图，或预览页"不满意·修改模型"带回的反馈
    LaunchedEffect(Unit) {
        if (autoSent) return@LaunchedEffect
        autoSent = true
        when {
            regenFeedback.isNotBlank() -> {
                // 重建模反馈：把原始问题和反馈合并，让 AI 重新建模
                val combined = buildString {
                    if (problemText.isNotBlank()) {
                        append(context.getString(R.string.chat_rebuild_original, problemText))
                    }
                    append(context.getString(R.string.chat_rebuild_feedback))
                    append(regenFeedback)
                }
                session.send(combined, emptyList())
            }
            problemText.isNotBlank() || initialImages.isNotEmpty() -> {
                session.send(problemText, initialImages)
            }
        }
    }
    // 新消息自动滚动到底部
    LaunchedEffect(messages.size, busy) {
        val lastIndex = messages.lastIndex + if (busy) 1 else 0
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                }
            )
        }
    ) { padding ->
        // imePadding：edge-to-edge 下 adjustResize 不再压缩窗口，必须手动让出 IME 高度，
        // 否则底部输入行被拼音键盘遮挡（真机反馈修复）
        Column(modifier = Modifier.padding(padding).imePadding()) {
            // 无网络/接口异常提示条
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { m -> MessageBubble(m) }
                if (busy) item { LoadingBubble() }
            }
            // ---- 附图缩略图行 ----
            if (attached.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    attached.forEachIndexed { idx, item ->
                        Box {
                            Image(
                                bitmap = item.second,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                            ) {
                                IconButton(
                                    onClick = { attached = attached.filterIndexed { i, _ -> i != idx } },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.chat_remove_photo),
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (importing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }

            // ---- AI 润色：失败提示 + 已润色可撤销 ----
            polishError?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            if (beforePolish != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = stringResource(R.string.polish_done),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            input = beforePolish.orEmpty()
                            beforePolish = null
                            polishError = null
                        }
                    ) { Text(stringResource(R.string.polish_undo)) }
                }
            }

            // ---- 输入行 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        pickImages.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !busy && !importing && attached.size < 3
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.chat_add_photo)
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 3
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            polishing = true
                            polishError = null
                            try {
                                val polished = container.textPolisher.polish(
                                    input,
                                    english = languageCode == LocaleStore.LANG_EN
                                )
                                beforePolish = input
                                input = polished
                            } catch (e: Exception) {
                                polishError = e.message ?: context.getString(R.string.polish_failed_default)
                            } finally {
                                polishing = false
                            }
                        }
                    },
                    enabled = !busy && !polishing && input.isNotBlank()
                ) {
                    if (polishing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = stringResource(R.string.polish_action),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                FilledIconButton(
                    onClick = {
                        val text = input
                        val imgs = attached.map { it.first }
                        input = ""
                        attached = emptyList() // 图片已随消息进入对话历史（LLM 侧），发送后清空
                        scope.launch { session.send(text, imgs) }
                    },
                    // 文字与附图至少有一样即可发送（允许只发照片）
                    enabled = !busy && (input.isNotBlank() || attached.isNotEmpty())
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                }
            }
        }
    }
}

/**
 * 相册图片 → 随消息发送的形态：最长边 1280px 的 JPEG（质量 80）base64 数据 URI，
 * 外加缩略图 ImageBitmap（输入行预览用）。
 *
 * 两段解码：先只读尺寸按 2 的幂降采样再真解码，避免直接解码 4000px+ 原图导致 OOM；
 * 不处理 EXIF 旋转（结构照片横竖不影响 AI 识别几何）。
 */
internal suspend fun compressToDataUri(
    context: Context,
    uri: Uri
): Pair<String, androidx.compose.ui.graphics.ImageBitmap>? =
    withContext(Dispatchers.Default) {
        runCatching {
            val src = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(src, 0, src.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(
                src, 0, src.size, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return@runCatching null
            val longSide = maxOf(decoded.width, decoded.height)
            val scale = if (longSide > 1280) 1280f / longSide else 1f
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else decoded
            val buf = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, buf)
            val dataUri = "data:image/jpeg;base64," +
                Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP)
            dataUri to scaled.asImageBitmap()
        }.getOrNull()
    }
