/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** 一条对话消息（fromUser=false 为 AI/系统侧文案） */
data class ChatMessage(val fromUser: Boolean, val text: String)

/**
 * AI 对话建模的状态机（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 流程：
 *  1. 用户描述问题（可附结构照片）→ LLM 直接输出框架模型（AiParser.parseFrameWithRetry）；
 *  2. 信息不足 → 每轮追问 ≤2 个，最多 2 轮；
 *  3. 建模完成 → [onFrameReady] 携带模型跳预览确认页。
 */
class ChatSession(
    private val aiClientProvider: suspend () -> AiClient,
    private val greeting: String,
    /** threeD：强制三维空间建模（z 竖向、重力 -Z，输出 dim=3）；false 自动判别平面/空间 */
    private val threeD: Boolean = false,
    /** forceSolid：强制使用三维实体（TET4）墙体契约，忽略梁元/流体等其他分支 */
    private val forceSolid: Boolean = false,
    /** forceSolidGeneric：强制使用通用三维实体（box/cylinder/plate + supports + loads）契约 */
    private val forceSolidGeneric: Boolean = false,
    /** forceShell：强制使用薄壁容器（水箱/料仓/充液罐体）契约，载荷由求解器按填料自动生成 */
    private val forceShell: Boolean = false,
    /** forceBuriedPipe：强制使用埋地管工程公式契约（覆土+车载作用下的柔性管验算） */
    private val forceBuriedPipe: Boolean = false,
    /** 本次附加的 AI 技能（首页选择，可为空）：只追加行为指令，不改输出契约 */
    private val skill: AiSkill? = null,
    /**
     * 当前材料库全部材料名（内置 + 联网检索 + 用户自定义），注入系统提示词让 AI 识别库外新材料。
     * 用「取值函数」而不是固定列表：材料库在进入页面后异步加载，
     * 若作为构造参数传入，加载完成时不得不重建整个 ChatSession（会清空对话与在建请求）。
     */
    private val materialNamesProvider: () -> List<String> = { emptyList() },
    /** 当前语言（"zh"/"en"）：决定 AI 提示词语言、材料体系术语与 App 侧提示文案 */
    private val language: String = LocaleStore.LANG_ZH,
    /** 建模完成 → 携带模型跳预览确认页 */
    private val onFrameReady: (FrameModel) -> Unit = {},
    /** 流体建模完成（风把墙吹倒）→ 携带模型跳流体预览页 */
    private val onFlowReady: (FlowModel) -> Unit = {},
    /** 渗流建模完成（水从大坝坝基渗过）→ 携带模型跳渗流预览页 */
    private val onSeepageReady: (SeepageModel) -> Unit = {},
    /** 时间历程建模完成（温度先升后降）→ 携带参数跳时间历程预览页 */
    private val onTransientReady: (TransientSpec) -> Unit = {},
    /** 三维实体建模完成（墙体开裂/应力分布）→ 携带模型跳实体预览页 */
    private val onSolidReady: (SolidWallModel) -> Unit = {},
    /** 通用三维实体建模完成（box/cylinder/plate 等）→ 携带模型跳通用实体预览页 */
    private val onSolidGenericReady: (SolidGenericModel) -> Unit = {},
    /** 薄壁容器建模完成（水箱/料仓）→ 携带模型跳容器预览页 */
    private val onShellReady: (ShellVesselModel) -> Unit = {},
    /** 埋地管建模完成（覆土+车载）→ 携带模型跳埋地管结果页（页面内求解并展示） */
    private val onBuriedPipeReady: (ShellBuriedPipeModel) -> Unit = {},
    /** 案例埋点：通用三维实体校验失败时回调（写入 case_log.jsonl，stage=validate）。
     *  llmAttempts=LLM 总尝试次数（首轮为 1），firstError=首轮错误信息（含错误码），
     *  用于统计「一次通过率 / 重试后通过率」；非重试路径缺省传 1/null。 */
    private val onCaseLog: suspend (
        error: String, userText: String, sessionId: String, inputForm: String,
        llmAttempts: Int, firstError: String?
    ) -> Unit = { _, _, _, _, _, _ -> },
    /** 案例埋点总开关：仅调试包（BuildConfig.DEBUG=true）注入 case_meta 并回调埋点；正式包为 false */
    private val caseLogEnabled: Boolean = false,
    /** 预览页"修改模型"时传入的当前模型：作为强基准注入系统提示词，要求 AI 只改用户指定字段，
     *  未提及的节点/杆件/坐标/支座/材料/dim 保持不变，禁止整体重画与未被要求的维度变更。
     *  首轮正常建模（非修改）时为 null，不注入锚定。 */
    private val regenAnchor: FrameModel? = null,
    /** 候选数量提供者：每次建模时读取最新设置（避免改设置重建会话清空对话）；1 = 单模型直进预览 */
    private val candidateCountProvider: () -> Int = { 1 },
    /** 候选差异控制开关提供者：开启后过滤掉与基准差异过大的候选 */
    private val candidateDivergenceEnabledProvider: () -> Boolean = { true },
    /** 框架几何不变性预检：返回非 null = 不稳（机构 / 近机构），应在候选阶段剔除。
     *  默认 { null } = 不预检，保持旧行为 / 测试兼容。仅对 Frame 模型生效；多候选路径剔除不稳
     *  候选，单模型路径不稳时换 seed 重试一次；修改模型强锚定（regenAnchor≠null）跳过预检。 */
    private val stabilityChecker: suspend (FrameModel) -> FrameStability? = { null },
    /** 多候选就绪回调：首轮建模成功时携带已过滤候选列表，UI 据此跳候选选择页 */
    private val onCandidatesReady: (List<ModelCandidate>) -> Unit = {},
    /** 多候选诊断日志回调：记录每个候选的 seed / 成功 / 失败 / 过滤原因，便于真机排查限流与过滤。
     *  ChatSession 纯 Kotlin 无 Android 依赖，由 MainActivity 注入 Logcat 输出；默认空操作不影响单测。 */
    private val onCandidateDiag: (String) -> Unit = {}
) {

    /** 会话 ID（C 列「来源会话」），每个 ChatSession 实例唯一 */
    private val sessionId: String = UUID.randomUUID().toString()

    /** 最近一条用户输入原文（D 列「用户原话」），供建模完成时注入 case_meta */
    private var lastUserText: String = ""
    /** 最近一条输入形态（E 列）：text / image / text+image */
    private var lastInputForm: String = "text"

    /** 构造案例埋点上下文（C/D/E 列）：仅调试包（caseLogEnabled）在建模就绪时注入 */
    private fun newCaseMeta(): CaseMeta =
        CaseMeta(session_id = sessionId, user_text = lastUserText, input_form = lastInputForm)

    private val _messages = MutableStateFlow<List<ChatMessage>>(listOf(ChatMessage(false, greeting)))
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 无网络 / 未配置等致命错误（UI 顶部提示 + 引导手动模式） */
    private val _fatalError = MutableStateFlow(false)
    val fatalError: StateFlow<Boolean> = _fatalError.asStateFlow()

    /** 对话历史（发给 LLM 的消息，含 AI 原始 JSON 回复） */
    private val history = mutableListOf<LlmMessage>()
    private var roundsAsked = 0

    /** 当前是否英文界面（AI 语言与提示文案跟随） */
    private val english = language == LocaleStore.LANG_EN

    /** 按当前语言选择文案 */
    private fun t(zh: String, en: String): String = if (english) en else zh

    /** 动态材料库说明：把当前全部材料名注入提示词，AI 才能识别联网检索/用户自定义的新材料 */
    private fun availableMaterialsText(): String = buildString {
        val names = materialNamesProvider()
        if (english) {
            append("Available material library (built-in + web-searched + user-defined): ")
            append(if (names.isNotEmpty()) names.joinToString(", ") else "(empty)")
            append(". If the user mentions a material in this list, use that exact name; ")
            append("otherwise substitute the closest built-in material and note it in model.name or questions.")
        } else {
            append("当前材料库（含内置 + 联网检索 + 用户自定义）：")
            append(if (names.isNotEmpty()) names.joinToString("、") else "（空）")
            append("。用户提到的材料若在此列表中，直接使用该材料名；")
            append("若不在，用最接近的内置材料代替并在 model.name 或 questions 里注明。")
        }
    }

    /** 每次建模时按最新材料库重建提示词（材料库可能在使用过程中被联网检索/自定义扩充）；
     *  预览页"修改模型"时若带 [regenAnchor]，在末尾追加强锚定块。 */
    internal fun buildSystemPrompt(): String {
        val base = (when {
            forceBuriedPipe -> BURIED_PIPE_SYSTEM_PROMPT
            forceShell -> SHELL_VESSEL_SYSTEM_PROMPT
            forceSolidGeneric -> SOLID_GENERIC_SYSTEM_PROMPT
            forceSolid -> SOLID_FORCE_SYSTEM_PROMPT
            threeD -> FRAME3D_SYSTEM_PROMPT
            else -> FRAME_AUTO_SYSTEM_PROMPT
        } + (skill?.promptBlock ?: "")).replace("{{AVAILABLE_MATERIALS}}", availableMaterialsText())
        val anchor = regenAnchor?.let { "\n\n" + anchorBlock(it) } ?: ""
        // 所有建模模式共用：超范围拒答规则（中英文按当前界面语言）
        val outOfScope = "\n\n" + if (english) OUT_OF_SCOPE_RULE_EN else OUT_OF_SCOPE_RULE_ZH
        return base + anchor + outOfScope +
            // 英文界面：结构契约保持中文模板（字段名/单位不变），追加语言与单位制约束
            if (english) "\n\n" + ENGLISH_OUTPUT_RULES else ""
    }

    /**
     * 预览页"修改模型"的强锚定块：把当前模型 JSON 硬塞给 AI，要求以它为基准、
     * 只改用户指定字段，未提及的几何/材料/维度保持不变（解决修改后整体重画的问题）。
     */
    private fun anchorBlock(model: FrameModel): String = buildString {
        if (english) {
            append("[ANCHOR — MODIFY IN PLACE] The user is modifying an existing model. " +
                "The current model JSON below is the fixed baseline: you MUST keep everything the user " +
                "did NOT explicitly ask to change exactly as-is — nodes, member connections, coordinates, " +
                "supports, materials and the dim (2D/3D) must NOT change unless the user asked for it. " +
                "Do NOT redraw the whole structure and do NOT change the dimension or re-estimate sizes " +
                "on your own. Only modify the fields the user requested, then output the COMPLETE updated " +
                "model JSON. The model omits default-valued fields (zero coordinates/components, empty " +
                "arrays, load type=force, support type=pinned, shape=rect) — treat them as their defaults " +
                "and omit them in your output too.\nCurrent model:\n")
        } else {
            append("【修改模型·强锚定】用户正在已有模型基础上修改。下面的当前模型 JSON 是固定基准：" +
                "你必须保持用户未明确要求改动的内容完全不变——节点、杆件连接、坐标、支座、材料、" +
                "dim 维度（2D/3D）都不得自行变更，严禁整体重画、严禁在用户未要求时改变维度或重新估计尺寸。" +
                "只修改用户指定的字段，然后输出修改后的【完整】模型 JSON。" +
                "模型已省略取默认值的字段（坐标/分量为 0、空数组、载荷 type=force、支座 type=pinned、截面 shape=rect），" +
                "请按默认值理解，你输出时也照此省略。\n当前模型：\n")
        }
        // 锚定块只放几何/材料/约束信息，避免把案例埋点上下文（_case_meta）泄露给 LLM
        // （LeanPromptJson 省略 null 键与默认值字段，解析端按数据类默认值补回，语义不变）
        append(LeanPromptJson.encodeToString(FrameModel.serializer(), model.copy(case_meta = null)))
    }

    /**
     * 用户发送一条消息（首轮为问题描述，后续轮为追问的回答）。
     * @param images 随本条消息附带的结构照片（base64 JPEG 数据 URI，仅框架模式首轮使用；
     *               气泡只显示文字，图片作为多模态内容直接发给 LLM 并保留在对话历史中）
     */
    suspend fun send(text: String, images: List<String> = emptyList()) {
        val trimmed = text.trim()
        // 文字与图片至少有一样才发送（允许只发照片）
        if ((trimmed.isEmpty() && images.isEmpty()) || _busy.value) return

        // 用户气泡显示原文；只发照片时以占位说明代替空文本
        append(
            ChatMessage(
                fromUser = true,
                text = trimmed.ifBlank {
                    t("（已附 ${images.size} 张照片）", "(${images.size} photo(s) attached)")
                }
            )
        )
        _busy.value = true
        try {
            // 记录用户原话与输入形态，供案例埋点（case_log.jsonl 的 D/E 列）
            lastUserText = trimmed
            lastInputForm = when {
                trimmed.isNotBlank() && images.isNotEmpty() -> "text+image"
                images.isNotEmpty() -> "image"
                else -> "text"
            }
            // 带图时给 LLM 追加图片识别指引（用户气泡只显示原始文字）
            val llmText = if (images.isEmpty()) trimmed else buildString {
                if (trimmed.isNotBlank()) {
                    append(trimmed)
                    append("\n\n")
                }
                if (english) {
                    append("[Photo] The user attached ")
                    append(images.size)
                    append(" structural photo(s). Build the model from the photos and text: identify the " +
                        "geometry, member connections and supports; estimate dimensions/sections not visible " +
                        "in the photo using common sizes, but you MUST ask about the material when it is not " +
                        "clearly visible—do not assume a default material; put the key uncertainties into questions. " +
                        "CRITICAL: faithfully preserve the tilt/crossing angles of members seen in the photo " +
                        "(e.g. splayed A-frame legs, X-shaped braces, trapezoidal frames). Do NOT straighten " +
                        "slanted members into vertical/parallel bars or idealize the shape into a rectangle; " +
                        "node coordinates must reflect the real geometry in the photo.")
                } else {
                    append("[附图] 用户随本消息提供了 ")
                    append(images.size)
                    append(" 张结构照片。请结合照片与文字建立模型：从照片识别结构的几何形状、" +
                        "杆件连接方式与支承情况；照片看不出来的尺寸/截面按常见规格估计，" +
                        "但材质（材料）看不清时必须追问，不得自行默认；" +
                        "并把最关键的疑点放进 questions 追问用户确认。" +
                        "重要：必须忠实保留照片中杆件的倾斜角度与交叉关系" +
                        "（如 A 字架两腿外撇、X 形斜撑交叉、梯形腿等），" +
                        "不得把斜杆拉直成垂直平行杆、也不得把整体形状理想化成矩形；" +
                        "节点坐标要反映照片里的真实几何位置。")
                }
            }
            val userMsg = LlmMessage("user", llmText, images)
            val system = buildSystemPrompt()
            val messages = if (history.isEmpty()) {
                listOf(LlmMessage("system", system), userMsg)
            } else {
                listOf(LlmMessage("system", system)) + history + userMsg
            }
            val client = aiClientProvider()
            // 多候选（首轮 / 追问回答后、非修改模型、候选数 > 1）：基准与补全在同一协程域内并发起跑，
            // 总耗时 ≈ 单次建模调用，而非原来的"基准串行 + 补全并发"两段相加。
            // 基准先就绪先做分支判断；基准反问/失败时取消补全、走原 handleFrameParse 反问，行为不变。
            // 修改模型（强锚定）或候选数 = 1 时走单模型串行路径（单模型路径含几何预检）。
            val multiMode = regenAnchor == null && candidateCountProvider() > 1
            val outcome = if (multiMode) runMultiCandidate(client, messages) else null
            var parse = outcome?.baseParse ?: AiParser.parseFrameWithRetry(
                client, messages, threeD = threeD, autoDim = !threeD,
                forceSolid = forceSolid, forceSolidGeneric = forceSolidGeneric,
                forceShell = forceShell, forceBuriedPipe = forceBuriedPipe
            )
            // 单模型路径（非强锚定）同样做几何不变性预检：机构 → 换 seed 重试一次；
            // 重试就绪且稳定才采用，否则保留原模型（预览页/求解器会暴露问题，不静默吞掉）。
            // 预检自身异常按"稳定"放行（与 ChaquopyEngine.checkFrameStability 语义一致）。
            if (outcome == null && regenAnchor == null && parse.ready && parse.model != null) {
                val unstable = runCatching { stabilityChecker(parse.model!!) }.getOrNull()
                if (unstable != null) {
                    val retry = runCatching {
                        // P-03 分档：low 档产出被判几何不稳，换 seed 重试直接升 high 兜底
                        AiParser.parseFrameWithRetry(
                            client.withReasoningEffort("high"), messages, threeD = threeD, autoDim = !threeD,
                            forceSolid = forceSolid, forceSolidGeneric = forceSolidGeneric,
                            forceShell = forceShell, forceBuriedPipe = forceBuriedPipe,
                            seedOverride = client.stableSeedOf(messages) + 77
                        )
                    }.getOrNull()
                    if (retry != null) {
                        val retryModel = if (retry.ready) retry.model else null
                        if (retryModel != null &&
                            runCatching { stabilityChecker(retryModel) }.getOrNull() == null
                        ) parse = retry
                    }
                }
            }
            history.add(userMsg)
            history.add(LlmMessage("assistant", AppJson.encodeToString(AiFrameResult.serializer(), parse)))

            if (outcome?.candidates != null) {
                onCandidatesReady(outcome.candidates)
                return
            }
            handleFrameParse(parse)
        } catch (e: AiContractException) {
            // 已选「三维实体」模式，AI 却输出梁杆模型；自动重试后仍不符 → 明确告知，不跳转到错误的预览页
            append(
                ChatMessage(
                    false,
                    t(
                        "AI 没能按「三维实体」模式建模（重试后返回的仍是梁杆模型）。" +
                            "请把几何形状、约束和载荷说得更具体，例如：\n" +
                            "· 直径 200 毫米、高 300 毫米的钢圆柱，底面固定，顶面受 50 千牛均布压力；\n" +
                            "· 400 毫米长、300 毫米宽、20 毫米厚的铝板，四边固定，板面受 0.5 兆帕均布压力。\n" +
                            "（原始错误：${e.message}）",
                        "The AI could not build a 3D solid model (it kept returning a beam/frame model after retrying). " +
                            "Please describe the geometry, supports and loads more concretely, e.g.:\n" +
                            "· A steel cylinder 200 mm in diameter and 300 mm tall, fixed at the bottom, " +
                            "with 50 kN uniform pressure on top;\n" +
                            "· An aluminum plate 400 mm long, 300 mm wide and 20 mm thick, fixed on all four edges, " +
                            "with 0.5 MPa uniform pressure on its face.\n" +
                            "(original error: ${parserErrorDetailEn(e.message)})"
                    )
                )
            )
        } catch (e: AiRetryExhaustedException) {
            // 多轮重试仍失败（结构 1 次 + 语义 2 次）：与解析失败同样引导用户补充描述。
            // 案例埋点带上 LLM 总尝试次数与首轮错误码，用于统计一次/二次通过率（仅调试包）。
            if ((forceSolidGeneric || forceShell || forceBuriedPipe) && caseLogEnabled) {
                runCatching {
                    onCaseLog(
                        e.message ?: "建模校验未通过",
                        lastUserText, sessionId, lastInputForm,
                        e.attempts, e.firstError
                    )
                }
            }
            append(ChatMessage(false, notUnderstoodMessage(forceShell, forceSolidGeneric, forceSolid, threeD, english, e.message, forceBuriedPipe)))
        } catch (e: AiParseException) {
            // 接口正常、模型已应答，但内容无法解析（如输入完全不像力学问题）
            // → 引导用户重新描述，不算接口故障，不置 fatalError
            // 案例埋点：通用三维实体校验失败 → 写入 case_log.jsonl（stage=validate, detected=是）
            // 仅调试包（caseLogEnabled=true）执行，正式包完全跳过
            if ((forceSolidGeneric || forceShell || forceBuriedPipe) && caseLogEnabled) {
                runCatching {
                    onCaseLog(e.message ?: "解析失败", lastUserText, sessionId, lastInputForm, 1, null)
                }
            }
            append(ChatMessage(false, notUnderstoodMessage(forceShell, forceSolidGeneric, forceSolid, threeD, english, e.message, forceBuriedPipe)))
        } catch (e: Exception) {
            _fatalError.value = true
            append(
                ChatMessage(
                    false,
                    t(
                        "AI 接口不可用：${e.readableMessage()}",
                        "AI service unavailable: ${e.readableMessage()}"
                    )
                )
            )
        } finally {
            _busy.value = false
        }
    }

    /**
     * 建模解析处理：
     * 就绪 → [onFrameReady] 跳预览确认；缺信息 → 追问（≤2 轮）；轮数用尽 → 引导一次性说全。
     */
    private fun handleFrameParse(parse: AiFrameResult) {
        val kind = parse.kind.trim().lowercase()
        // 超出软件分析范畴：LLM 已判定（kind=out_of_scope，ready=false），直接展示统一话术，
        // 不建模、不进入追问、不跳转预览。描述不清导致的解析失败不走这里（仍走 notUnderstoodMessage）。
        if (kind == "out_of_scope") {
            val cat = parse.category.trim().ifBlank { "相关专业" }
            append(
                ChatMessage(
                    false,
                    t(
                        "这种场景属于${cat}类型的任务，超出本软件的分析范畴，需要专业计算分析工具解决。",
                        "This type of task ($cat) is outside this app's analysis scope and requires " +
                            "professional computational analysis tools."
                    )
                )
            )
            return
        }
        val flow = parse.flow
        if (parse.ready && parse.kind.trim().lowercase() == "flow" && flow != null) {
            append(ChatMessage(false, t("流体模型已建好，请前往预览确认。", "Flow model ready — please review and confirm.")))
            onFlowReady(if (caseLogEnabled) flow.copy(case_meta = newCaseMeta()) else flow)
            return
        }
        val seepage = parse.seepage
        if (parse.ready && parse.kind.trim().lowercase() == "seepage" && seepage != null) {
            append(ChatMessage(false, t("渗流模型已建好，请前往预览确认。", "Seepage model ready — please review and confirm.")))
            onSeepageReady(if (caseLogEnabled) seepage.copy(case_meta = newCaseMeta()) else seepage)
            return
        }
        val transient = parse.transient
        if (parse.ready && parse.kind.trim().lowercase() == "transient" && transient != null) {
            append(ChatMessage(false, t("时间历程参数已就绪，请前往预览确认。", "Transient parameters ready — please review and confirm.")))
            onTransientReady(if (caseLogEnabled) transient.copy(case_meta = newCaseMeta()) else transient)
            return
        }
        val solid = parse.solid
        if (parse.ready && parse.kind.trim().lowercase() == "solid" && solid != null) {
            append(ChatMessage(false, t("三维实体模型已建好，请前往预览确认。", "3D solid model ready — please review and confirm.")))
            onSolidReady(if (caseLogEnabled) solid.copy(case_meta = newCaseMeta()) else solid)
            return
        }
        val solidGeneric = parse.solid_generic
        if (parse.ready && parse.kind.trim().lowercase() == "solid_generic" && solidGeneric != null) {
            append(ChatMessage(false, t("通用三维实体模型已建好，请前往预览确认。", "General 3D solid model ready — please review and confirm.")))
            // 仅调试包注入案例埋点上下文（用户原话/会话/输入形态），正式包原样透传模型
            val readyModel = if (caseLogEnabled) {
                solidGeneric.copy(
                    case_meta = CaseMeta(
                        session_id = sessionId,
                        user_text = lastUserText,
                        input_form = lastInputForm
                    )
                )
            } else {
                solidGeneric
            }
            onSolidGenericReady(readyModel)
            return
        }
        val shell = parse.shell
        val shellKind = parse.kind.trim().lowercase()
        if (parse.ready && (shellKind == "shell_vessel" || shellKind == "shell_tank") && shell != null) {
            append(ChatMessage(false, t("薄壁容器模型已建好，请前往预览确认。", "Thin-wall vessel model ready — please review and confirm.")))
            val readyModel = if (caseLogEnabled) {
                shell.copy(
                    case_meta = CaseMeta(
                        session_id = sessionId,
                        user_text = lastUserText,
                        input_form = lastInputForm
                    )
                )
            } else {
                shell
            }
            onShellReady(readyModel)
            return
        }
        val buriedPipe = parse.shell_buried_pipe
        if (parse.ready && parse.kind.trim().lowercase() == "shell_buried_pipe" && buriedPipe != null) {
            append(ChatMessage(false, t("埋地管模型已建好，正在分析…", "Buried-pipe model ready — analyzing…")))
            val readyModel = if (caseLogEnabled) {
                buriedPipe.copy(
                    case_meta = CaseMeta(
                        session_id = sessionId,
                        user_text = lastUserText,
                        input_form = lastInputForm
                    )
                )
            } else {
                buriedPipe
            }
            onBuriedPipeReady(readyModel)
            return
        }
        val model = parse.model
        if (parse.ready && model != null) {
            append(ChatMessage(false, t("模型已建好，请前往预览确认。", "Model ready — please review and confirm.")))
            onFrameReady(if (caseLogEnabled) model.copy(case_meta = newCaseMeta()) else model)
            return
        }
        if (roundsAsked >= MAX_FRAME_ROUNDS) {
            append(
                ChatMessage(
                    false,
                    t(
                        "信息还不够建立模型。请一次性说清：用什么材料、各杆件尺寸与连接方式、" +
                            "哪里固定或支撑、承受多大重量，我再帮你建模。",
                        "There is not enough information yet. Please describe in one go: the material, " +
                            "each member's dimensions and connections, where it is fixed/supported, " +
                            "and the load it carries — then I can build the model."
                    )
                )
            )
            return
        }
        roundsAsked++
        val questions = parse.questions.filter { it.isNotBlank() }.take(2)
            .ifEmpty { listOf(t("请补充结构的材料和大致尺寸。", "Please provide the material and approximate dimensions.")) }
        append(ChatMessage(false, questions.joinToString("\n")))
    }

    /** 多候选结果：[baseParse] 为基准原始解析（供写 history / handleFrameParse 反问）；
     *  [candidates] 非空表示已生成候选，null 表示基准未就绪（调用方走 handleFrameParse 反问）。 */
    private data class MultiCandidateOutcome(
        val baseParse: AiFrameResult,
        val candidates: List<ModelCandidate>?
    )

    /**
     * 多候选生成（提速版）：基准与 count-1 个补全在同一 [coroutineScope] 内**同时起跑**，
     * 总耗时 ≈ 单次建模调用，而非原来的"基准串行 + 补全并发"两段相加。
     * - 先 await 基准：基准未就绪（反问/失败）→ 取消补全、返回 candidates=null，由调用方走 handleFrameParse；
     * - 基准就绪 → 等补全（已在跑）→ 几何预检 + 失败换 seed 重试 + 差异控制过滤；
     * - 单候选异常隔离（重抛 CancellationException 保留协程取消）；全部过滤空 → 回退基准。
     * 仅多候选路径（regenAnchor==null 且候选数>1）调用；非多候选路径不进本函数。
     */
    private suspend fun runMultiCandidate(
        client: AiClient,
        messages: List<LlmMessage>
    ): MultiCandidateOutcome {
        val count = candidateCountProvider().coerceIn(1, 3)
        val baseSeed = client.stableSeedOf(messages)
        // 本函数只在多候选路径调用；单模型路径的预检在 send() 内单独进行（P1-4）
        val precheck = count > 1 && regenAnchor == null
        // 基准不稳时的重试 seed 偏移：避开补全槽位 1..count-1 与 10+i，保证换出不同模型
        val baseRetryOffset = 100

        // Frame 候选是否几何不变：预检返回 null（稳定 / 预检自身异常）才保留；非 Frame 候选不过滤
        suspend fun frameStable(c: ModelCandidate?): Boolean {
            if (c == null) return false
            val frame = (c as? ModelCandidate.Frame)?.model ?: return true
            return stabilityChecker(frame) == null
        }

        // 单个候选：失败、未就绪、或（仅 Frame）几何不稳都返回 null，由换 seed 重试兜底。
        // observation 非空时（C2 共享几何观测）注入提示词，让补全复用基准观测、只生成坐标。
        // effortHigh=true（P-03 分档）：用于不稳/缺失后的换 seed 重试，直接以 high 档产出。
        suspend fun runCandidate(seedOffset: Int, observation: String? = null, effortHigh: Boolean = false): ModelCandidate? = try {
            val seed = baseSeed + seedOffset
            val msgs = if (!observation.isNullOrBlank()) {
                messages + LlmMessage("user", observationHint(observation))
            } else {
                messages.toList()
            }
            val p = AiParser.parseFrameWithRetry(
                if (effortHigh) client.withReasoningEffort("high") else client,
                msgs, threeD = threeD, autoDim = !threeD,
                forceSolid = forceSolid, forceSolidGeneric = forceSolidGeneric,
                forceShell = forceShell, forceBuriedPipe = forceBuriedPipe,
                seedOverride = seed
            )
            val c = ModelCandidate.from(p, english)
            when {
                c == null -> { onCandidateDiag("cand seed=$seed: not-ready/empty"); null }
                precheck && !frameStable(c) -> { onCandidateDiag("cand seed=$seed: unstable-filtered"); null }
                else -> { onCandidateDiag("cand seed=$seed: ok ${candSummary(c)}"); c }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onCandidateDiag("cand seed=${baseSeed + seedOffset}: FAIL ${e.javaClass.simpleName}: ${(e.message ?: "").take(120)}")
            null
        }

        return coroutineScope outer@{
            // 基准（不传 seedOverride，等价原 send 的 stableSeed 行为）先起跑
            val baseDef = async {
                AiParser.parseFrameWithRetry(
                    client, messages, threeD = threeD, autoDim = !threeD,
                    forceSolid = forceSolid, forceSolidGeneric = forceSolidGeneric,
                    forceShell = forceShell, forceBuriedPipe = forceBuriedPipe
                )
            }
            // A2 错峰：补全延迟 300ms 起跑，降低 3 路同时冲击 Kimi 并发限流的概率；
            // 基准耗时约 60s，300ms 偏移仍保留绝大部分重叠提速。
            val extraDefs = (1 until count).map { i ->
                async { delay(300); runCandidate(i) }
            }

            val baseParse = baseDef.await()
            val base = ModelCandidate.from(baseParse, english)
            if (base == null) {
                // 基准反问/失败：取消已起跑的补全（避免浪费调用），返回 null 让上层走 handleFrameParse 反问
                extraDefs.forEach { it.cancel() }
                onCandidateDiag("base not-ready -> cancel extras; questions=${baseParse.questions.size}")
                return@outer MultiCandidateOutcome(baseParse, null)
            }
            // C2：取基准观测，供补全重试复用（基准已完成，观测可用）
            val baseObservation = baseParse.observation
            // 基准预检：不稳则换 seed 重试 1 次（带观测，与补全 firstPass 重叠进行）；仍不稳保留原基准兜底
            val effectiveBase = if (precheck && !frameStable(base)) {
                onCandidateDiag("base unstable -> retry seed=${baseSeed + baseRetryOffset}")
                runCandidate(baseRetryOffset, baseObservation, effortHigh = true) ?: base
            } else {
                base
            }
            val firstPass = extraDefs.awaitAll()
            val slots = (1 until count).toList()
            // 补全缺失/不稳时换 seed 再试一次：C2 注入基准观测，帮助失败补全更快更稳地产出
            val retried = coroutineScope {
                slots.mapIndexed { idx, i ->
                    async { if (firstPass[idx] == null) runCandidate(10 + i, baseObservation, effortHigh = true) else null }
                }.awaitAll()
            }
            val extraCandidates = slots.indices.mapNotNull { idx -> firstPass[idx] ?: retried[idx] }
            val all = listOf(effectiveBase) + extraCandidates
            // A1 诊断：记录差异过滤剔除了哪些候选（便于真机区分限流 vs 过滤）
            val filtered = if (candidateDivergenceEnabledProvider()) {
                all.filter { cand ->
                    val similar = CandidateDivergence.areSimilar(effectiveBase, cand, true)
                    if (!similar && cand !== effectiveBase) {
                        onCandidateDiag("cand filtered: divergence vs base (base=${candSummary(effectiveBase)} cand=${candSummary(cand)})")
                    }
                    similar
                }
            } else {
                all
            }
            val result = if (filtered.isEmpty()) listOf(effectiveBase) else filtered
            // 多候选路径：仅调试包把用户上下文注入每个候选模型，预览页选中后透传到求解器
            val logged = if (caseLogEnabled) result.map { it.withCaseMeta() } else result
            onCandidateDiag("done: all=${all.size} filtered=${filtered.size} result=${logged.size} obs=${baseObservation.isNotBlank()}")
            MultiCandidateOutcome(baseParse, logged)
        }
    }

    /** C2 共享几何观测：把基准观测注入补全提示词，要求复用观测、只生成坐标 */
    private fun observationHint(observation: String): String =
        "（生成备选模型）下面是基准模型从照片得到的几何观测（杆件清单、连接关系、尺寸比例）：\n" +
            "$observation\n" +
            "请基于该观测生成一个不同的合理简化：保持杆件清单与连接关系，可调整节点坐标、截面、支座位置形成备选方案；" +
            "model 的节点坐标必须与观测一致。可省略 observation 字段，只输出 model 即可。"

    /** 候选注入案例埋点上下文：保留到预览/求解页，bridge 弹出后写入 case_log.jsonl */
    private fun ModelCandidate.withCaseMeta(): ModelCandidate = when (this) {
        is ModelCandidate.Frame -> copy(model = model.copy(case_meta = newCaseMeta()))
        is ModelCandidate.Flow -> copy(model = model.copy(case_meta = newCaseMeta()))
        is ModelCandidate.Seepage -> copy(model = model.copy(case_meta = newCaseMeta()))
        is ModelCandidate.Transient -> copy(spec = spec.copy(case_meta = newCaseMeta()))
        is ModelCandidate.Solid -> copy(model = model.copy(case_meta = newCaseMeta()))
        is ModelCandidate.SolidGeneric -> copy(model = model.copy(case_meta = newCaseMeta()))
        is ModelCandidate.Shell -> copy(model = model.copy(case_meta = newCaseMeta()))
    }

    /** 诊断用候选概要：Frame 给出节点/杆件数，其他给出类型 */
    private fun candSummary(c: ModelCandidate?): String = when (c) {
        null -> "null"
        is ModelCandidate.Frame -> "frame(n=${c.model.nodes.size},m=${c.model.members.size})"
        else -> c.kind
    }

    /**
     * 解析/校验最终失败（重试耗尽）时展示给用户的引导文案：按当前建模模式给出可照着说的例句，
     * 并附上首轮/本次原始错误（含 [L-xx] 错误码），方便用户针对性补充。
     */
    private fun notUnderstoodMessage(
        forceShell: Boolean,
        forceSolidGeneric: Boolean,
        forceSolid: Boolean,
        threeD: Boolean,
        english: Boolean,
        errorDetail: String?,
        forceBuriedPipe: Boolean = false
    ): String {
        val detail = if (english) parserErrorDetailEn(errorDetail) else errorDetail ?: ""
        return when {
            forceBuriedPipe -> if (english) {
                "The AI's buried-pipe model did not pass validation. Please describe the pipe " +
                    "outer diameter and wall thickness, the cover depth (top of pipe to ground), " +
                    "the material (PVC / steel / concrete, etc.), and optionally the wheel load, e.g.:\n" +
                    "· A PVC pipe 50 mm outer diameter, 3 mm wall thickness, buried 2 m deep, " +
                    "with a 70 kN wheel load on the surface (will it break?).\n" +
                    "(original error: $detail)"
            } else {
                "AI 给出的埋地管模型没能通过校验。请把管道外径和壁厚、覆土深度（管顶到地面）、" +
                    "材料（PVC / 钢 / 混凝土等）说清楚，车载可默认，例如：\n" +
                    "· 外径 50 毫米、壁厚 3 毫米的 PVC 管，埋深 2 米，地面过一辆车（单轮 70 kN），会不会坏？\n" +
                    "（原始错误：$detail）"
            }
            forceShell -> if (english) {
                "The AI's vessel model did not pass validation. Please describe the container shape and " +
                    "dimensions (length/width/height or radius/height, plus wall thickness), the fill " +
                    "(water / dry sand / wet sand / grain / cement powder / plastic pellets, or a custom " +
                    "density), the fill depth, and the material, e.g.:\n" +
                    "· A rectangular steel water tank 2 m long, 1 m wide, 1.5 m tall, wall 6 mm thick, " +
                    "filled to 1.2 m with water;\n" +
                    "· A cylindrical steel silo 3 m in diameter and 10 m tall, wall 10 mm thick, " +
                    "filled with dry sand.\n" +
                    "(original error: $detail)"
            } else {
                "AI 给出的容器模型没能通过校验。请把容器形状与尺寸（长/宽/高 或 半径/高，加壁厚）、" +
                    "装什么填料（水 / 干砂 / 湿砂 / 小麦 / 水泥粉 / 塑料颗粒，或自定义重度）、" +
                    "填料深度、以及材料说得更具体，例如：\n" +
                    "· 长 2 米、宽 1 米、高 1.5 米的钢制矩形水箱，壁厚 6 毫米，装 1.2 米深的水；\n" +
                    "· 直径 3 米、高 10 米的钢制圆柱料仓，壁厚 10 毫米，装满干砂。\n" +
                    "（原始错误：$detail）"
            }
            forceSolidGeneric -> if (english) {
                "The AI's model did not pass validation. Please describe the geometry and dimensions, " +
                    "where it is fixed/supported, and the load (type, location, direction, magnitude, " +
                    "distribution and unit) more concretely, e.g.:\n" +
                    "· A steel cylinder 200 mm in diameter and 300 mm tall, fixed at the bottom face, " +
                    "with 50 kN uniform pressure on the top face;\n" +
                    "· An aluminum plate 400 mm long, 300 mm wide and 20 mm thick, fixed on all four edges, " +
                    "with 0.5 MPa uniform pressure on its face.\n" +
                    "(original error: $detail)"
            } else {
                "AI 给出的模型没能通过校验。请把几何体形状与尺寸、哪里固定或支撑、" +
                    "载荷（类型/位置/方向/大小/分布/单位）说得更具体，例如：\n" +
                    "· 直径 200 毫米、高 300 毫米的钢圆柱，底面固定，顶面受 50 千牛均布压力；\n" +
                    "· 400 毫米长、300 毫米宽、20 毫米厚的铝板，四边固定，板面受 0.5 兆帕均布压力。\n" +
                    "（原始错误：$detail）"
            }
            forceSolid -> if (english) {
                "The AI could not build a valid 3D solid model. Please describe the geometry, supports " +
                    "and loads more concretely, e.g.:\n" +
                    "· A steel cylinder 200 mm in diameter and 300 mm tall, fixed at the bottom, " +
                    "with 50 kN uniform pressure on top;\n" +
                    "· An aluminum plate 400 mm long, 300 mm wide and 20 mm thick, fixed on all four edges, " +
                    "with 0.5 MPa uniform pressure on its face.\n" +
                    "(original error: $detail)"
            } else {
                "AI 没能建立有效的「三维实体」模型。请把几何形状、约束和载荷说得更具体，例如：\n" +
                    "· 直径 200 毫米、高 300 毫米的钢圆柱，底面固定，顶面受 50 千牛均布压力；\n" +
                    "· 400 毫米长、300 毫米宽、20 毫米厚的铝板，四边固定，板面受 0.5 兆帕均布压力。\n" +
                    "（原始错误：$detail）"
            }
            threeD -> if (english) {
                "The AI could not build the model. Please describe in one message: the material, each " +
                    "member's dimensions and connections, where it is fixed, and the load it carries " +
                    "(with its magnitude and direction).\n(original error: $detail)"
            } else {
                "AI 没能建立模型。请一次性说清：用什么材料、各杆件尺寸与连接方式、哪里固定或支撑、" +
                    "承受多大的载荷（数值与方向）。\n（原始错误：$detail）"
            }
            else -> if (english) {
                "I could not turn that into a structural model. Please describe it as a mechanics problem " +
                    "with the material, dimensions, supports and loads, e.g. \"a 40 mm × 60 mm pine " +
                    "cantilever shelf 800 mm long, fixed at the wall, with a 30 kg weight at the free end\".\n" +
                    "(original error: $detail)"
            } else {
                "我没太理解你的力学问题。请换个说法，并带上材料、尺寸、支座和载荷，" +
                    "例如：「40×60 毫米的松木悬臂搁板，长 800 毫米，一端插入墙体固定，自由端挂 30 公斤」。\n" +
                    "（原始错误：$detail）"
            }
        }
    }

    private fun append(message: ChatMessage) {
        _messages.value = _messages.value + message
    }

    companion object {
        /** 框架模式下最多追问轮数 */
        private const val MAX_FRAME_ROUNDS = 2

        /**
         * 英文界面追加在中文模板之后的输出约束：
         * JSON 契约与字段单位保持不变（内部一律 SI），但面向用户文字用英文、
         * 材料用美标术语，用户给出的英制尺寸需先换算为契约要求的米/毫米/牛顿/帕。
         */
        private val ENGLISH_OUTPUT_RULES = """
            IMPORTANT — LANGUAGE & UNIT SYSTEM (overrides the Chinese wording above):
            - The user communicates in English and the app is running in US (imperial) mode.
            - Write every human-readable text in English: all "questions", "answer", "model.name"
              and any free-text field. Never reply in Chinese.
            - Keep the JSON contract exactly as specified: same field names and structure.
              Field units stay as documented (frame: mm / N / N/mm / °C; flow/seepage/solid: m / Pa).
              These are internal SI values — convert user-supplied US units before writing them:
              1 ft = 0.3048 m (304.8 mm), 1 in = 25.4 mm, 1 lb = 4.4482 N, 1 kip = 4448.2 N,
              1 psi = 6894.76 Pa, 1 psf = 47.88 Pa, 1 mph = 0.44704 m/s.
            - Use US customary material names and grades from the available material list above
              (e.g. ASTM A36 steel, A572 Grade 50, 4000 psi concrete, Douglas Fir) instead of
              the Chinese material names used in the examples.
            - If the user gives wind in mph, convert to wind_speed_m_s; "Beaufort N" maps to beaufort.
            - When asking the user for dimensions, you may phrase questions in feet, inches and pounds,
              but the JSON numeric fields must still use the documented units.
        """.trimIndent()

        /**
         * 超范围拒答规则（所有建模模式共用，由 [buildSystemPrompt] 追加到系统提示词末尾）：
         * 用户问题明确超出软件支持范畴时，让 LLM 输出 out_of_scope 信号而非强行套现有 kind；
         * 描述不清但属支持范畴的仍走 ready=false + questions 追问。中英文各一份。
         */
        internal val OUT_OF_SCOPE_RULE_ZH = """
【超出分析范畴】如果用户的问题明确不属于本软件支持的结构/流体/渗流/热/薄壁/三维实体分析类别（例如电磁-结构耦合、爆炸冲击、断裂力学、土-结构动力相互作用、流-固耦合动力、热-结构耦合动力瞬态、整机多体动力学、电磁场、化学反应等），请不要强行套用任何已有 kind，也不要进入追问，直接且仅输出一个 JSON 对象：
{"ready":false,"kind":"out_of_scope","category":"<简短类别名>","questions":[]}
其中 category 用当前界面语言给出简短任务类别（如「电磁-结构耦合」），用于 App 拼接待客话术。
注意：如果问题属于支持范畴、只是描述不清或信息不全，请仍按原契约用 ready=false + questions 追问，绝对不要用 out_of_scope。""".trimIndent()

        internal val OUT_OF_SCOPE_RULE_EN = """
[OUT-OF-SCOPE] If the user's request is clearly OUTSIDE the analysis categories this app supports
(structural frames / 3D solids / thin-wall vessels / buried pipe / wind-wall flow / dam seepage /
transient thermal), such as electromagnetic-structural coupling, blast/impact, fracture mechanics,
dynamic soil-structure interaction, fluid-structure interaction dynamics, transient thermo-structural
dynamics, multibody dynamics, electromagnetic fields, or chemical reactions: do NOT force it into any
supported kind and do NOT ask clarifying questions. Output exactly one JSON object:
{"ready":false,"kind":"out_of_scope","category":"<short category name>","questions":[]}
Use the current UI language for "category" (e.g. "electromagnetic-structural coupling").
IMPORTANT: if the request IS in scope but underspecified, use ready=false + "questions" as usual —
NEVER use out_of_scope for that case.""".trimIndent()

        /**
         * 通用框架建模提示词模板：坐标维度约定（{{DIM_RULES}}）与输出示例（{{EXAMPLE}}）
         * 按"平面 / 自动判别"两套填入，避免两份提示词各自漂移。
         */
        private val FRAME_PROMPT_TEMPLATE = """
            你是"口袋仿真"App 的结构建模助手。用户会用中文描述一个由杆件/梁组成的结构
            （书架、桌子、晾衣架、支架等），你的任务是把它抽象成一个梁杆框架模型。
            坐标单位为毫米。

            {{DIM_RULES}}

            建模规则：
            1. 节点放在杆件连接点、支座处、集中力作用点，编号从 1 开始且不重复；
            2. members 每根杆件连接两个节点（i、j 填节点编号），截面两种写法：
               矩形：width_b×depth_h（mm）；空心方管/矩形管加 "wall_t":壁厚（mm，
               如 40×40×2 方管写 width_b=40, depth_h=40, wall_t=2；实心木方/实心钢条不写）；
               圆管/圆钢：写 "shape":"circle"、"outer_diameter_mm":外径、"wall_t":壁厚
               （实心圆墙厚填 0），如 ⌀48×3.5 钢管写 shape=circle, outer_diameter_mm=48, wall_t=3.5，
               此时不要再写 width_b/depth_h；圆管截面不支持开孔，需要开孔时改用矩形截面；
               杆件需要开孔/打洞时加 "holes"（矩形截面可用）：
               {"at_mm":孔心距节点 i 的距离,"shape":"circle|rect","d_mm":孔径/洞高,"through":true}；
               开孔按净截面 + 孔边应力集中系数校核，孔洞必须落在杆件范围内且小于截面高度；
               用户没给截面时按常见规格估一个（如木方 30×40、钢方管 40×40×2、钢管 ⌀48×3.5）；
               同一根物理构件只能建一根杆件：禁止在同一对节点上重复建两根杆件
               （会让刚度重复计入、变形图上叠出两条线）；
            3. supports 声明支座，每项 {"node":节点编号,"type":"fixed|pinned|roller"}：
               fixed（嵌固，插入墙体/地面）、pinned（铰接）、roller（滚轴，只挡竖向）；
            4. loads 声明载荷（type 必须且只能用 "force" / "udl" / "area" / "wind" / "thermal"，禁止别名）。
               【先判荷载类型再填数值】按作用宽度 b 与杆件跨度 L 的比例判定：
               - b ≤ 0.1L 或 ≤ 50 mm（单个挂点、吊钩、机器地脚、立柱传来的集中反力）
                 → 集中力：{"type":"force","node":编号,"fx":0,"fy":-294}
                 （单位 N，竖直向下为负，重物质量 kg×9.8；水平力填 fx）；
               - b ≥ 0.3L 或连续铺满（一排书、积雪、板面自重、人群、沙发垫）
                 → 均布载荷：{"type":"udl","member":杆件编号,"qy":-0.5}
                 （单位 N/mm，向下为负；qy = 总重 N ÷ 作用长度 mm）；
               - 0.1L < b < 0.3L：按集中力建模（偏保守：同总重下弯矩最多高估约 18%），
                 并在 questions 里说明本可按局部均布处理；
               - 面状构件（桌面、椅面、搁板、层板）把重量传给下方多根横杆时，
                 禁止折算成一个集中力，用面荷载声明总重，由求解器按杆件长度自动分配为均布：
                 {"type":"area","members":[5,6],"total_N":-600}
                 （members 只写真正承托该面板的杆件；total_N 为面板总重的负值）；
               - 风载（用户给风速/风级时用这个，禁止自己心算成 fx）：
                 {"type":"wind","nodes":[迎风节点编号],"wind_speed_m_s":15,"dir":"x","area_m2":6.0}
                 （wind_speed_m_s 与 beaufort 二选一；area_m2 为受风投影面积 m²；
                  净风力 F=cp×½×1.225×v²×A 由代码换算并均摊到 nodes，cp 缺省 1.2）；
               - 温度变化（热胀冷缩）：{"type":"thermal","member":杆件编号,"delta_temp":40}
                 （单位 °C，升温为正、降温为负，基准环境温度 20°C；
                 如"夏天钢管被晒到 60°C"→ delta_temp=40；
                 只在用户明确提到温度变化/热胀冷缩/暴晒/结冰/采暖时才加）；
               不要用 "distributed" "point" "concentrated" "surface" 等其他写法；
               载荷数值不能为 0：没有载荷的构件就不要写进 loads，禁止用 qy=0 占位；
               平面模型里风载必须用 type=wind 源字段（风速/风级+受风面积，代码自动换算），
               其他水平推力用节点集中力 fx；qy 只能表示竖向均布载荷；
            5. 材料是必需信息，必须由用户明确给出，严禁自行默认：
               用户没有说明材质时（例如只说"这张桌子撑得住吗""搁板能放多少"、只给尺寸和重量），
               必须输出 ready=false、model=null，并在 questions 里追问材料，
               例如"桌面和桌腿（横梁）用什么材料？（实木、松木、颗粒板、钢、铝型材…）"，
               同时可一并问清楚截面尺寸。绝对不要先按松木、铝合金等"常见材料"算一遍——
               材料强度相差十倍以上，默认材料会把安全结论算错；
               只有用户写明材料、或照片/文字中材质明确可辨时才建模；
               常见说法对应：实木/木板/木方/木条→松木，钢管/铁管/钢材→低碳钢，不锈钢/不锈铁/304钢/316钢→不锈钢304，
               铝管/铝型材（未给型号）→铝合金；材料库没有的牌号用最接近的内置材料
               （白色金属管可能是不锈钢也可能是铝合金，按用户描述判断；不确定就追问，不要默认）；
            6. 材料名称用常见中文名；如果用户提到具体型材/型钢型号，材料字段必须用精确名称
               （材料库内置对应截面特性 A/I/J，求解器自动按真实值计算，不要写 wall_t）：
               - 铝型材："欧标2020铝型材" / "欧标3030铝型材" / "欧标4040铝型材" / "欧标5050铝型材"
                 （截面写外形宽度，如 3030 型材 width_b=30, depth_h=30）；
               - 工字钢：型号如 "工字钢10"~"工字钢63a"，常用 "工字钢20a"/"工字钢25a"/"工字钢32a"
                 （depth_h 取型号高度，如 20a→200；width_b 取翼缘宽，如 20a→100）；
               - H型钢：型号如 "HN200×100"/"HN300×150"/"HN400×200"/"HW200×200"/"HM300×200"
                 （depth_h 取 h，width_b 取 b）；
               - 角钢：型号如 "角钢L50×5"/"角钢L100×10"（depth_h/width_b 取肢长，如 L50×5→50×50）；
               工字钢/H型钢/角钢强、弱轴惯性矩不同，depth_h 必须填截面高度（强轴）、
               width_b 填翼缘/肢宽（弱轴），不要填反。
               {{AVAILABLE_MATERIALS}}
            7. 信息足够建立完整模型时输出 ready=true 并给出完整 model；
               信息不足时输出 ready=false、model 为 null，并在 questions 里提最多 2 个
               最关键的中文追问；
            8. 模型规模控制在 25 个节点、40 根杆件以内，只建承受主要载荷的杆件，
               忽略装饰性零件；整体必须几何不变（有支座、不缺杆；平面模型需在同一平面内
               几何不变，空间模型需立柱固定、顶层连梁闭合或加斜撑）；支座不能全部是滚轴
               （roller）——至少一个固定/铰支座提供水平约束，否则结构是机构、无法求解；
               避免近机构：细长杆缺侧向支撑、四边形未加斜撑也未刚接闭合、节点悬空只靠单杆
               连接，都会算出无意义大变形——宁可多一根斜撑/连梁，也不要留几何可变区域；
            9. 用户消息可能附带 1-3 张结构照片（多模态 image_url）：照片反映真实几何与
               连接方式时以照片为准，文字描述优先说明材料、载荷与支承；照片看不清的尺寸、
               截面按常见规格估计并在 questions 里确认，但材质看不清也必须追问，
               不要因为照片模糊而拒绝建模；几何上必须忠实照片：照片里倾斜、交叉、外撇的
               杆件（如 A 字架、X 形斜撑、梯形腿）要按真实角度建，不得拉直成垂直平行杆、
               也不得把整体形状简化成矩形；
               带图时先在 observation 字段写出几何观测（杆件清单、连接关系、尺寸比例、
               多张照片是否描述同一结构），再给 model，model 的节点坐标与杆件连接必须与
               观测一致；多张照片必须描述同一结构，若不同照片的腿数、主要尺寸或连接方式
               互相矛盾，不要强行建模，输出 ready=false 并在 questions 里指出矛盾、请用户
               确认以哪张为准；照片看不清的关键尺寸（杆长、层高、跨度等）优先采用用户文字
               给出的数值，文字未给才按常见规格估计并在 questions 确认；
               用户要求把三维模型退化为二维时：去掉各节点的 z 坐标、把原 z 方向的尺寸转写
               到 y，载荷里的 fz/qz 改写到 fy/qy（不得残留 fz/qz），dim 填 2；
            10. 桌面/搁板/平台/椅面承载：先按规则 4 判定荷载类型，再决定加在哪里——
               面状构件整面承重（成排的书、显示器、坐人、积雪）→ 用 area 加在真正承托它的
               横梁上，不得折算成跨中集中力；单件重物（一箱书、一台设备）压在局部 →
               用 force 加在作用点下方横梁的跨中节点（在跨中新增节点、把横梁拆成两段）。
               无论哪种，都不要把载荷直接加在立柱顶端或支座正上方，否则弯曲被忽略、
               变形趋近于零、安全系数会虚高到几百倍。
            11. 如果用户问的是"风会不会把一堵墙（围墙、隔断、女儿墙）吹倒""几级风能把墙吹倒"
                "大风天这堵墙安全吗"这类风荷载问题，不要建梁杆框架，改用流体契约输出
               （墙体尺寸一律用米）：
               {"ready":true,"kind":"flow","questions":[],"model":null,
                "flow":{"name":"砖墙抗风","kind":"wind_wall",
                "wall":{"height_m":2,"length_m":3,"thickness_m":0.24},
                "beaufort":8,"material":"砖砌体"}}
               - wall 三个尺寸单位是米：2 米高写 height_m=2，240 毫米厚写 thickness_m=0.24；
               - 风级与风速二选一：用户说"8 级风"写 beaufort=8；直接给风速写 wind_speed_m_s=20；
               - material 必须由用户明确给出（砖砌体/混凝土C20/松木等），没给就追问；
               - length_m 是沿墙方向（与风向平行的那条边）的长度。
               普通杆件结构问题仍输出下面的框架契约（kind 可省略，默认 frame）。

            12. 如果用户问的是"水流冲刷大坝""大坝会不会渗漏/管涌""水库蓄水后坝基安全吗"
               这类渗流问题，改用渗流契约输出（坝体尺寸与水位一律用米）：
               {"ready":true,"kind":"seepage","questions":[],"model":null,
                "seepage":{"name":"大坝渗流","kind":"dam_seepage",
                "dam":{"height_m":12,"crest_width_m":5,"upstream_slope":0,"downstream_slope":0.75},
                "upstream_level_m":11,"downstream_level_m":1,"material":"混凝土C20"}}
               - dam 四个参数：坝高 height_m、坝顶宽 crest_width_m、上游坡比 upstream_slope、
                 下游坡比 downstream_slope（坡比为每 1 m 高对应的水平宽度，直立写 0，1:0.75 写 0.75）；
               - 水位以坝基底面为基准：上游水位 upstream_level_m、下游水位 downstream_level_m
                 （下游无水写 0，且必须低于上游水位）；
               - 材料必须由用户明确给出（混凝土C20/砖砌体等），没给就追问；
               - 可选：foundation_permeability_m_s（坝基渗透系数，默认 1e-6）、
                 foundation_depth_m（坝基深度，默认 1.5 倍坝高）、friction_coeff（摩擦系数，默认 0.6）。

            13. 如果用户问的是"温度先升高再降低，结构会怎样""白天暴晒升到 60 度、晚上又降回来，
               这根杆会不会变形/被顶坏"这类温度随时间变化的问题（不是一直保持某个温度），
               改用时间历程契约（平面模型）：
               {"ready":true,"kind":"transient","questions":[],"model":null,
                "transient":{"name":"悬臂杆温度循环",
                "frame":{"name":"悬臂杆","nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":800,"y":0}],
                "members":[{"id":1,"i":1,"j":2,"width_b":40,"depth_h":60,"material":"铝合金"}],
                "supports":[{"node":1,"type":"fixed"}],
                "loads":[{"type":"force","node":2,"fx":0,"fy":-98}]},
                "duration_s":7200,"steps":12,"temp_base_c":20,"temp_peak_c":80,
                "profile":"triangle","members":null}}
               - frame 是恒载工况：只放机械载荷（挂重/均布），不要放 thermal；
               - duration_s 单位秒（2 小时写 7200），steps 取 8~16（必须 5~40）；
               - temp_base_c 起始环境温度（默认 20°C），temp_peak_c 峰值温度，二者不能相同；
               - profile："triangle" 匀速升温再降温（多数情况用它）、"sine" 平滑升降温；
               - members：null 或省略 = 全部杆件随温度变化；只有横梁被晒时写 [杆件编号]；
               - 材料、尺寸、挂重必须问清楚；温度只给"会升到多少度"时按峰值处理。

            14. 如果用户问的是"风这么大墙体会不会开裂""墙面/墙根哪里受力最大、应力集中"
                "这堵墙在风里变形多少毫米"这类需要看墙体内部位移与应力分布的问题，
               改用三维实体契约（墙体尺寸一律用米）：
               {"ready":true,"kind":"solid","questions":[],"model":null,
                "solid":{"name":"砖墙三维抗风","kind":"solid_wall",
                "wall":{"length_m":4,"height_m":3,"thickness_m":0.24},
                "beaufort":10,"material":"砖砌体"}}
               - wall 三个尺寸单位是米（240 毫米写 0.24），length_m 是沿墙方向的长度；
               - 风压三选一：pressure_Pa（Pa，直接给定均布压力，优先）、
                 wind_speed_m_s（m/s）、beaufort（几级风）；
               - 可选：cp_net（净体型系数，默认 1.2）、mesh_level（coarse/medium/fine/ultra，
                 默认 medium；ultra 精度最高但耗时最长）、E_MPa / nu / density_kg_m3（覆盖材料库代表值，一般不要填）；
               - 材料必须由用户明确给出（砖砌体/混凝土C20/松木等），没给就追问；
               - 与第 11 条的区别：只问"会不会被吹倒、整体稳不稳"用 flow（抗倾覆/抗滑移），
                 问"会不会开裂、墙面受力多大、变形多少"用 solid（三维实体应力云图）。

            15. 如果用户描述的是杆件/钢管/型材上要开孔（打孔、钻孔、开洞）并关心孔边强度，
               或一根水平放置的钢管/型钢受弯（简支、两端支撑、跨中挂重），一律用梁元模型，
               不要用三维实体（实体网格不含孔，圆柱也画不出水平管）：
               - 平面内受力：dim 省略或填 2；空间架/多方向受力：dim=3；
               - 开孔写在 members[].holes：{"at_mm":孔心距节点 i 的距离,"shape":"circle|rect",
                 "d_mm":孔径或洞高,"through":true}；孔径必须小于截面高度，
                 程序按净截面（A_net/I_net）+ 孔边应力集中系数校核；
               - 圆管/圆钢用 "shape":"circle","outer_diameter_mm":外径,"wall_t":壁厚
                 （实心圆填 0，如 ⌀60 圆钢填 wall_t=0），不要用宽度/高度等效方管；
                 圆管不支持开孔，需开孔时改矩形截面；
               - 例：外径 60 mm、壁厚 4 mm、长 1 m 的水平简支钢管，跨中挂 100 kg：
                 members 写 shape=circle、outer_diameter_mm=60、wall_t=4；
                 节点 0(0,0)、1(500,0)、2(1000,0)，两端 pinned；挂重在跨中节点 1 加 force fy=-980；

            16. 如果用户描述的是充液/装料的容器（水箱、水槽、料仓、料斗、储罐、罐体、筒仓），
                关心罐壁/仓壁在内压或填料侧压下的应力与变形（会不会鼓胀、开裂、变形多大），
               改用薄壁容器契约（尺寸一律用米）：
               {"ready":true,"kind":"shell_vessel","questions":[],"model":null,
                "shell":{"name":"矩形水箱","kind":"shell_vessel",
                "geometry":{"section":"rect","length_m":2,"width_m":1,"height_m":1.5,"thickness_m":0.006},
                "fill":"water","fill_depth_m":1.2,"material":"structural_steel","mesh_level":"medium"}}
               - geometry.section：矩形水箱填 "rect"（需 length_m/width_m/height_m），
                 圆柱料仓/罐体填 "circle"（需 radius_m/height_m）；thickness_m 是壁厚，单位米；
               - fill：填料预设名（water/dry_sand/wet_sand/grain_wheat/cement_powder/plastic_pellets），
                 或对象 {"type":"fluid","bulk_density_kg_m3":1000} /
                 {"type":"granular","preset":"dry_sand","friction_angle_deg":30,...}；
                 没说填料就追问（缺省按水）；
               - fill_depth_m：填料深度（米），省略或非正值默认装满；不能超过 height_m；
               - 圆柱示例："geometry":{"section":"circle","radius_m":1.5,"height_m":10,"thickness_m":0.01},
                 "fill":"dry_sand"；
               - 材料必须由用户明确给出，没给就追问；可选 mesh_level（coarse/medium/fine/ultra）；
               - 涉及温度时（热水/冰箱/环境温度等）加可选 thermal：{"inner_temp_c":浸湿面温度,"outer_temp_c":外露面温度}（°C，数字不加引号），玻璃等脆性材料热冲击可能主控；无温度信息不要填；
               - 与第 14 条区别：封闭/半封闭容器在填料或内压下的壁面应力用 shell_vessel；
                 仅一面受外压的墙体用 solid。

            17. 如果用户描述的是埋在土里的管道（排水管、给水管、穿线管、波纹管等柔性管）
                在覆土与地面车载作用下会不会损坏、变形多大，改用埋地管契约（几何尺寸一律用米）：
               {"ready":true,"kind":"shell_buried_pipe","questions":[],"model":null,
                "shell_buried_pipe":{"name":"埋地PVC管","kind":"shell_buried_pipe",
                "geometry":{"outer_diameter_m":0.05,"thickness_m":0.003,"cover_depth_m":2.0},
                "material":"PVC"}}
               - geometry 必填三项（单位米，注意换算）：outer_diameter_m 外径（50 mm 写 0.05）、
                 thickness_m 壁厚（3 mm 写 0.003）、cover_depth_m 管顶覆土深度（2 m 写 2）；
               - 可选字段（用户明确给出才填，没给一律留空由求解器按默认值兜底，并在结果里告知用户）：
                 soil_gamma_kN_m3 土重度（默认 18 kN/m³）、soil_phi_deg 土内摩擦角（默认 30°）、
                 soil_cohesion_kPa 土黏聚力（默认 0）、wheel_load_kN 单轮车载（默认 70 kN，公路-Ⅰ级）、
                 tire_contact_m 轮胎接地尺寸（默认 0.3 m）、internal_pressure_kPa 管内压（默认 0，空管）；
               - material 必填（材料库名，如 PVC / 钢 / 混凝土），没给就追问；
                 可用 E_MPa / nu / density_kg_m3 / strength_MPa 覆盖材料库代表值（一般不填）；
               - 土参数和车载没给不要追问，直接用默认值；只有材料、管径、壁厚、埋深缺失才追问；
               - 本方法为工程公式估算（线弹性、忽略土弹簧与覆土约束的有利作用），不替代正式设计。

            可选字段 observation（字符串，带图建模时填写，不展示给用户）：简要记录从照片
            观测到的几何（杆件清单、连接关系、尺寸比例、多张照片是否一致），用于稳定建模；
            不带图或纯文字建模时可省略，不影响解析。
            只输出一个 JSON 对象，七选一（流体/渗流/时间历程/三维实体/薄壁容器/埋地管见第 11、12、13、14、16、17 条）：
            {"ready":false,"questions":["..."],"model":null}
            或
            {{EXAMPLE}}
            不要输出任何解释、代码围栏或其他文字。
        """.trimIndent()

        /** 自动判别（P3D-5，v1.2.45 起 3D 优先）：默认三维建模，仅用户明确要求平面简化时才 2D */
        private val DIM_RULES_AUTO = """
            默认按三维建模（用户已确认 3D 质量更好，宁可多花时间）：dim 填 3：
            - x、y 组成水平面，z 竖直向上，地面 z=0，重力沿 -z（不要用 y 当竖直方向）；
            - 节点写 {"id":..,"x":..,"y":..,"z":..}；
            - 支座用 fixed/pinned/roller/roller_x/roller_y，必要时用 "fix":["rx"] 等额外约束；
            - 集中力用 fx/fy/fz 与 mx/my/mz（N、N·mm），均布用 qx/qy/qz（N/mm）；
            - width_b 是截面水平横向宽度，depth_h 是竖直方向高度（强轴）。
            仅当用户明确说"平面/二维/2D 建模"时才用平面约定：竖直平面 x 向右、y 向上，
            dim 填 2 或省略，节点只写 x/y，力用 fx/fy，均布用 qy，支座用 fixed/pinned/roller。
        """.trimIndent()

        /** 自动模式示例（v1.2.45 起 3D 优先）：先给三维短例，再给用户明确要平面时的平面短例 */
        private val EXAMPLE_AUTO = """
            {"ready":true,"questions":[],"model":{"name":"门式刚架","dim":3,
            "nodes":[{"id":1,"x":0,"y":0,"z":0},{"id":2,"x":0,"y":0,"z":2000},
            {"id":3,"x":2000,"y":0,"z":0},{"id":4,"x":2000,"y":0,"z":2000},
            {"id":5,"x":1000,"y":0,"z":2000}],
            "members":[{"id":1,"i":1,"j":2,"width_b":80,"depth_h":80,"material":"低碳钢"},
            {"id":2,"i":3,"j":4,"width_b":80,"depth_h":80,"material":"低碳钢"},
            {"id":3,"i":2,"j":5,"width_b":80,"depth_h":120,"material":"低碳钢"},
            {"id":4,"i":5,"j":4,"width_b":80,"depth_h":120,"material":"低碳钢"}],
            "supports":[{"node":1,"type":"fixed"},{"node":3,"type":"fixed"}],
            "loads":[{"type":"force","node":5,"fz":-980}]}}
            或（仅当用户明确要求平面/2D 建模时）
            {"ready":true,"questions":[],"model":{"name":"两层书架","dim":2,
            "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":600,"y":0}],
            "members":[{"id":1,"i":1,"j":2,"width_b":30,"depth_h":40,"material":"松木"}],
            "supports":[{"node":1,"type":"fixed"}],
            "loads":[{"type":"force","node":2,"fy":-294}]}}
        """.trimIndent()

        /**
         * 通用结构默认的自动判别提示词（P3D-5，v1.2.45 起 3D 优先）：默认输出 dim=3
         * 走空间梁求解器与三维视图，仅用户明确要求平面时才 dim=2 走 2D 求解器。
         */
        val FRAME_AUTO_SYSTEM_PROMPT = FRAME_PROMPT_TEMPLATE
            .replace("{{DIM_RULES}}", DIM_RULES_AUTO)
            .replace("{{EXAMPLE}}", EXAMPLE_AUTO)

        /**
         * 三维空间建模系统提示词（frameMode + threeD 时使用，dim=3 输出契约）。
         *
         * 坐标约定（与 python/pocketsim/frame3d.py 一致）：
         *   x、y 为水平面，z 竖直向上，重力沿 -z；水平地面为 z=0 的 XY 平面。
         *   width_b 为截面局部 y 向宽度（水平横向），depth_h 为局部 z 向高度（竖直）。
         */
        val FRAME3D_SYSTEM_PROMPT = """
            你是"口袋仿真"App 的三维结构建模助手。用户会用中文描述一个空间杆件结构
            （桌子、四层置物架、机架、门式刚架、三角支架、椅子等），你的任务是把它
            抽象成一个三维空间梁模型。坐标单位为毫米。

            坐标约定（必须严格遵守）：
            - x、y 组成水平面，z 竖直向上；重力竖直向下 = -z 方向。
            - 地面/楼面是 z=0 的水平面；桌腿、立柱底端 z=0，顶端 z=高度。
            - 不要用 y 当竖直方向（那是平面模式的约定）；三维里"高度"一律写 z。

            建模规则：
            1. 节点放在杆件连接点、支座处、集中力作用点，编号从 1 开始且不重复；
               每个节点写 {"id":编号,"x":..,"y":..,"z":..}。
            2. dim 固定填 3。
            3. members 每根杆件连接两个节点（i、j 填节点编号），截面（矩形或圆管）：
               - depth_h 是截面竖直方向尺寸（强轴），width_b 是水平横向尺寸；
               - 水平梁：depth_h 取抗弯高度（如木方 40×80 写成 width_b=40, depth_h=80）；
               - 立柱/桌腿：建议用方形截面（width_b=depth_h，如 40×40），避免强弱轴歧义；
               - 空心方管/矩形管另加 "wall_t":壁厚（mm，如 40×40×2 方管写 wall_t=2）；
                 实心木材/实心钢条省略 wall_t；
               - 圆管/圆钢：写 "shape":"circle"、"outer_diameter_mm":外径、"wall_t":壁厚
                 （实心圆填 0），如 ⌀48×3.5 钢管；此时不写 width_b/depth_h，圆管不支持开孔；
               - 杆件需要开孔/打洞时加 "holes"（矩形截面可用）：
                 {"at_mm":孔心距节点 i 的距离,"shape":"circle|rect","d_mm":孔径/洞高,"through":true}；
                 开孔按净截面 + 孔边应力集中系数校核，孔洞必须落在杆件范围内且小于截面高度；
               - 用户没给截面时按常见规格估（方木 40×40、钢方管 40×40×2、3030 铝型材写 30×30）；
               - 同一根物理构件只能建一根杆件，禁止在同一对节点上重复建两根杆件；
               - 只建承受主要载荷的杆件，不要建装饰件、抽屉滑轨等。
            4. supports 声明支座，每项 {"node":编号,"type":"fixed|pinned|roller|roller_x|roller_y"}：
               - fixed（嵌固，插入地面/墙体，6 个自由度全约束）——桌腿、立柱落地常用；
               - pinned（铰接，约束 3 个平动，转动自由）；
               - roller（竖向滚轴，只约束 z 向平动）/ roller_x / roller_y（约束 x / y 向平动）。
               - 可选 "fix":["rx"] 额外约束某个自由度，仅在简支构件需要防止整体绕轴扭转时使用。
               - 多腿落地的空间框架（桌子、货架、晾衣架、置物架等）支座绝不能全部是 roller：
                 全滚轴只挡竖向、水平方向可整体飘移，结构是机构无法求解；必须至少一个 fixed
                 或 pinned 提供水平约束（其余腿可用 roller）。带轮子的家具/货架建模时，轮子
                 视为固定支座（静力分析需要固定参考系），不要把每条腿都设为 roller。
            5. loads 声明载荷（type 必须且只能用 "force" / "udl" / "area" / "wind" / "thermal"，禁止别名）。
               【先判荷载类型再填数值】按作用宽度 b 与杆件跨度 L 的比例判定：
               - b ≤ 0.1L 或 ≤ 50 mm（单个挂点、吊钩、设备地脚、立柱传来的集中反力）
                 → 集中力/挂重：{"type":"force","node":编号,"fx":0,"fy":0,"fz":-294}
                 （单位 N；竖直向下为负，质量 kg×9.8；水平力填 fx/fy；力矩填 mx/my/mz，单位 N·mm）；
               - b ≥ 0.3L 或连续铺满（一排书、积雪、板面自重、坐人）
                 → 均布载荷：{"type":"udl","member":杆件编号,"qx":0,"qy":0,"qz":-0.5}
                 （单位 N/mm，竖直向下为负；qz = 总重 N ÷ 作用长度 mm）；
               - 0.1L < b < 0.3L：按集中力建模（偏保守：同总重下弯矩最多高估约 18%），
                 并在 questions 里说明本可按局部均布处理；
               - 面状构件（椅面、桌面、层板、床板）把重量传给下方多根横杆时，
                 禁止折算成一个集中力，用面荷载声明总重，由求解器按杆件长度自动分配为均布：
                 {"type":"area","members":[9,10,11,12],"total_N":-600}
                 （members 只写真正承托该面板的横杆；total_N 为面板总重的负值）；
               - 风载（用户给风速/风级时用这个，禁止自己心算成 fx/fy）：
                 {"type":"wind","nodes":[迎风节点编号],"beaufort":8,"dir":"x","area_m2":12.0}
                 （wind_speed_m_s 与 beaufort 二选一；dir 取风的水平方向 x/y；area_m2 为受风投影面积 m²；
                  净风力 F=cp×½×1.225×v²×A 由代码换算并均摊到 nodes，cp 缺省 1.2）；
               - 温度变化：{"type":"thermal","member":杆件编号,"delta_temp":40}
                 （°C，升温为正、降温为负，基准 20°C；只在用户明确提到温度变化/暴晒/采暖时才加）；
               - 面状构件整面承重时不要折算成跨中集中力；只有单件重物压在局部时才在
                 作用点下方顶梁跨中新增节点、把梁拆成两段再加 force；两者都不要把载荷
                 直接加在立柱顶端或支座正上方，否则弯曲被忽略、安全系数虚高到几百倍。
            6. 材料是必需信息，必须由用户明确给出，严禁自行默认：
               用户没有说明材质时（只说"四腿餐桌撑得住吗""置物架会不会晃"、只给尺寸和重量），
               必须输出 ready=false、model=null，并在 questions 里追问材料，
               例如"桌面、桌腿和横梁用什么材料？（实木、松木、颗粒板、钢、铝型材…）"，
               可同时问清桌腿/横梁的截面。绝对不要先按松木、铝合金等"常见材料"算一遍——
               材料强度相差十倍以上，默认材料会把安全结论算错；
               只有用户写明材料、或照片/文字中材质明确可辨时才建模；
               常见说法对应：实木/木板/木方/木条→松木，钢管/铁管/钢材→低碳钢，不锈钢/不锈铁/304钢/316钢→不锈钢304，
               铝管/铝型材（未给型号）→铝合金；材料库没有的牌号用最接近的内置材料
               （白色金属管可能是不锈钢也可能是铝合金，按用户描述判断；不确定就追问，不要默认）；
            7. 材料名称用常见中文名；提到型材/型钢型号时用精确名称（A/I/J 由材料库给出，不要写 wall_t）：
               - 铝型材："欧标2020铝型材"/"欧标3030铝型材"/"欧标4040铝型材"/"欧标5050铝型材"
                 （截面写 20×20 / 30×30 / 40×40 / 50×50）；
               - 工字钢：型号如 "工字钢10"~"工字钢63a"，常用 "工字钢20a"/"工字钢25a"/"工字钢32a"
                 （depth_h 取型号高度如 20a→200，width_b 取翼缘宽如 20a→100）；
               - H型钢：型号如 "HN200×100"/"HN300×150"/"HN400×200"/"HW200×200"/"HM300×200"
                 （depth_h 取 h，width_b 取 b）；
               - 角钢：型号如 "角钢L50×5"/"角钢L100×10"（depth_h/width_b 取肢长如 L50×5→50×50）；
               工字钢/H型钢/角钢强、弱轴惯性矩不同，depth_h 必须填截面高度（强轴）、
               width_b 填翼缘/肢宽（弱轴），不要填反；其余写"松木""低碳钢""铝合金"等中文名。
               {{AVAILABLE_MATERIALS}}
            8. 空间结构必须整体几何不变：立柱底部至少一处 fixed/pinned 提供水平约束
               （不能全为 roller，否则是机构算不出）、顶层横梁闭合（或加斜撑），
               不允许出现只靠两根柱撑一块板的悬空结构；4 条腿的桌子要在顶部四边连梁。
               避免近机构：立柱过细长却无连梁/斜撑、开口四边形未闭合，会产生异常大变形。
            9. 信息足够建模时输出 ready=true 并给出完整 model；不足时 ready=false、
               model 为 null，并在 questions 里提最多 2 个最关键的中文追问。
            10. 规模控制在 40 个节点、60 根杆件以内；尺寸取常见生活习惯值
               （桌高 750、书架层高 350、桌面 600×600 等），不要编造极端尺寸。
            11. 用户消息可能附带 1-3 张结构照片：几何与连接方式以照片为准，文字优先
                说明材料、载荷与支承；照片看不清的尺寸、截面按常见规格估计并在 questions
                里确认，但材质看不清也必须追问。几何上必须忠实照片：照片里倾斜、交叉、
                外撇的杆件（如 A 字架两腿外撇、X 形斜撑、梯形腿）要按真实角度建，
                节点坐标反映真实空间位置，不得把斜杆拉直成垂直平行杆、也不得把整体形状
                简化成矩形。
                带图时先在 observation 字段写出几何观测（杆件清单、连接关系、尺寸比例、
                多张照片是否描述同一结构），再给 model，model 的节点坐标与杆件连接必须与
                观测一致；多张照片必须描述同一结构，若不同照片的腿数、主要尺寸或连接方式
                互相矛盾，不要强行建模，输出 ready=false 并在 questions 里指出矛盾、请用户
                确认以哪张为准；照片看不清的关键尺寸优先采用用户文字给出的数值，文字未给
                才按常见规格估计并在 questions 确认；
                用户要求把三维模型退化为二维时：去掉各节点的 z 坐标、把原 z 方向尺寸转写到
                y，载荷里的 fz/qz 改写到 fy/qy（不得残留 fz/qz），dim 填 2；
            12. 如果用户问的是风把墙吹倒这类风荷载问题，不要建空间杆件模型，改用流体契约：
                {"ready":true,"kind":"flow","questions":[],"model":null,
                "flow":{"name":"砖墙抗风","kind":"wind_wall",
                "wall":{"height_m":2,"length_m":3,"thickness_m":0.24},
                "beaufort":8,"material":"砖砌体"}}
                墙体尺寸用米、风级（beaufort）与风速（wind_speed_m_s）二选一、
                材料必须由用户明确给出。
            13. 如果用户问的是水流渗过大坝、坝基管涌这类渗流问题，改用渗流契约：
                {"ready":true,"kind":"seepage","questions":[],"model":null,
                "seepage":{"name":"大坝渗流","kind":"dam_seepage",
                "dam":{"height_m":12,"crest_width_m":5,"upstream_slope":0,"downstream_slope":0.75},
                "upstream_level_m":11,"downstream_level_m":1,"material":"混凝土C20"}}
                坝高/坝顶宽/水位一律用米，坡比为每 1 m 高对应的水平宽度，材料必须由用户给出。
            14. 如果用户问的是温度随时间升高再降低（暴晒升温后降温、昼夜温差、结冰又回暖）
                这类时间变化问题，改用时间历程契约。时间历程当前只支持平面框架，
                即使当前是三维模式也要把结构简化到竖直平面内（frame.dim 省略或填 2）：
                {"ready":true,"kind":"transient","questions":[],"model":null,
                "transient":{"name":"支架温度循环",
                "frame":{"name":"门式支架","nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":0,"y":1800},
                {"id":3,"x":1200,"y":1800},{"id":4,"x":1200,"y":0}],
                "members":[{"id":1,"i":1,"j":2,"width_b":40,"depth_h":40,"material":"铝合金"},
                {"id":2,"i":2,"j":3,"width_b":40,"depth_h":60,"material":"铝合金"},
                {"id":3,"i":3,"j":4,"width_b":40,"depth_h":40,"material":"铝合金"}],
                "supports":[{"node":1,"type":"fixed"},{"node":4,"type":"fixed"}],
                "loads":[{"type":"force","node":3,"fx":0,"fy":-490}]},
                "duration_s":7200,"steps":12,"temp_base_c":20,"temp_peak_c":120,
                "profile":"triangle","members":[2]}}
                frame 只放恒定机械载荷；members 写参与温度变化的杆件（null = 全部）。
            15. 如果用户问的是整面墙体的内部受力（"会不会被吹裂/开裂""墙面哪里应力最大"
                "墙在风里变形多少毫米"），改用三维实体契约（与第 12 条整体倾覆问题区分）：
                {"ready":true,"kind":"solid","questions":[],"model":null,
                "solid":{"name":"砖墙三维抗风","kind":"solid_wall",
                "wall":{"length_m":4,"height_m":3,"thickness_m":0.24},
                "beaufort":10,"material":"砖砌体"}}
                墙体尺寸用米（240 毫米写 0.24）；风压给 pressure_Pa / wind_speed_m_s /
                beaufort 三选一；材料必须由用户明确给出。
            16. 如果用户描述的是充液/装料的容器（水箱、料仓、储罐、筒仓），关心罐壁/仓壁在
                内压或填料侧压下的应力与变形，改用薄壁容器契约（尺寸用米）：
                {"ready":true,"kind":"shell_vessel","questions":[],"model":null,
                "shell":{"name":"矩形水箱","kind":"shell_vessel",
                "geometry":{"section":"rect","length_m":2,"width_m":1,"height_m":1.5,"thickness_m":0.006},
                "fill":"water","fill_depth_m":1.2,"material":"structural_steel","mesh_level":"medium"}}
                圆柱料仓：geometry.section="circle"、给 radius_m/height_m；
                fill 用预设名（water/dry_sand/wet_sand/grain_wheat/cement_powder/plastic_pellets）
                或对象；fill_depth_m 缺省按装满；材料必须由用户明确给出。
                涉及温度时（热水/冰箱/环境温度等）加可选 thermal：{"inner_temp_c":浸湿面温度,"outer_temp_c":外露面温度}（°C，数字不加引号），无温度信息不要填。

            典型空间结构（内部参考）：
            - 四腿桌：4 根立柱（z=0→750）+ 顶部四边连梁，桌面整面荷载用 area 加在顶部连梁（不少于 4 根）；
            - 空间杆系座椅：4 根椅腿 + 两侧纵杆 + 2~3 根坐面横杆 + 靠背立杆，
              坐面荷载（人+坐垫）用 area 加在坐面横杆上，不折算成单个集中力；
            - 多层置物架：4 根立柱 + 每层 4 根横梁，载荷按层高分布；
            - 门式刚架：2 根立柱固定于地面 + 1 根横梁，横梁跨中挂重；
            - 三角支架：墙面竖杆 + 水平挑杆 + 斜撑，自由端挂重。

            示例（600×600、高 750 的四腿桌，桌面共放 100kg 书；注意顶梁跨中新增节点 9-12）：
            {"ready":true,"questions":[],"model":{"name":"四腿书桌","dim":3,
            "nodes":[{"id":1,"x":-300,"y":-300,"z":0},{"id":2,"x":-300,"y":-300,"z":750},
            {"id":3,"x":300,"y":-300,"z":0},{"id":4,"x":300,"y":-300,"z":750},
            {"id":5,"x":300,"y":300,"z":0},{"id":6,"x":300,"y":300,"z":750},
            {"id":7,"x":-300,"y":300,"z":0},{"id":8,"x":-300,"y":300,"z":750},
            {"id":9,"x":0,"y":-300,"z":750},{"id":10,"x":300,"y":0,"z":750},
            {"id":11,"x":0,"y":300,"z":750},{"id":12,"x":-300,"y":0,"z":750}],
            "members":[{"id":1,"i":1,"j":2,"width_b":40,"depth_h":40,"material":"松木"},
            {"id":2,"i":3,"j":4,"width_b":40,"depth_h":40,"material":"松木"},
            {"id":3,"i":5,"j":6,"width_b":40,"depth_h":40,"material":"松木"},
            {"id":4,"i":7,"j":8,"width_b":40,"depth_h":40,"material":"松木"},
            {"id":5,"i":2,"j":9,"width_b":40,"depth_h":80,"material":"松木"},
            {"id":6,"i":9,"j":4,"width_b":40,"depth_h":80,"material":"松木"},
            {"id":7,"i":4,"j":10,"width_b":40,"depth_h":80,"material":"松木"},
            {"id":8,"i":10,"j":6,"width_b":40,"depth_h":80,"material":"松木"},
            {"id":9,"i":6,"j":11,"width_b":40,"depth_h":80,"material":"松木"},
            {"id":10,"i":11,"j":8,"width_b":40,"depth_h":80,"material":"松木"},
            {"id":11,"i":8,"j":12,"width_b":40,"depth_h":80,"material":"松木"},
            {"id":12,"i":12,"j":2,"width_b":40,"depth_h":80,"material":"松木"}],
            "supports":[{"node":1,"type":"fixed"},{"node":3,"type":"fixed"},
            {"node":5,"type":"fixed"},{"node":7,"type":"fixed"}],
            "loads":[{"type":"area","members":[5,6,7,8,9,10,11,12],"total_N":-980}]}}
            只输出一个 JSON 对象，不要输出任何解释、代码围栏或其他文字。
        """.trimIndent()

        /**
         * 强制三维实体（TET4）模式的系统提示词：
         * 用户已显式选择"三维实体"，不再分流到梁元/流体/渗流/时间历程，
         * 一律按墙体三维实体契约（solid_wall）输出，信息不足时追问。
         */
        val SOLID_FORCE_SYSTEM_PROMPT = """
            你是"口袋仿真"App 的三维实体建模助手。用户已选择用三维实体（四节点四面体 TET4）
            分析墙体在风压下的内部位移与应力分布（墙根开裂、应力集中、变形量）。
            无论用户怎么描述，一律只输出三维实体契约，不要输出梁元、流体、渗流或时间历程。

            建模规则（严格遵守）：
            1. 只支持场景 kind="solid_wall"（一块墙底固定的矩形墙体，迎风面受均布风压）。
            2. 墙体尺寸单位为米，字段为 wall.length_m（沿墙方向长度）、wall.height_m（墙高）、
               wall.thickness_m（墙厚，240 毫米写 0.24）；三者都必须由用户给出或从描述中明确，
               缺任何一个就 ready=false 并追问。
            3. 材料必须由用户明确给出（砖砌体/混凝土C20/松木…），没给就追问，严禁自行默认；
               {{AVAILABLE_MATERIALS}}
            4. 风压三选一（优先 pressure_Pa > wind_speed_m_s > beaufort）：
               - pressure_Pa：直接均布风压（Pa，如 500）；
               - wind_speed_m_s：风速（m/s），程序按 cp_net×0.5×ρ×v² 换算风压；
               - beaufort：风级（0~17 整数）。
               三者都没给就追问。
            5. 可选字段：cp_net（净体型系数，默认 1.2）、mesh_level（coarse/medium/fine/ultra，
               默认 medium；fine/ultra 计算量较大，手机上可能耗时数秒到十几秒甚至更久）、
               E_MPa / nu / density_kg_m3（覆盖材料库代表值，一般不要填）。
            6. 信息齐全时输出 ready=true、kind="solid"，并给出完整 solid 模型；
               信息不足时 ready=false、questions 里提最多 2 个最关键的中文追问。

            输出格式（只输出一个 JSON 对象，不要任何解释或代码围栏）：
            {"ready":true,"kind":"solid","questions":[],"model":null,
             "solid":{"name":"砖墙三维抗风","kind":"solid_wall",
             "wall":{"length_m":4,"height_m":3,"thickness_m":0.24},
             "material":"砖砌体","pressure_Pa":500,"mesh_level":"medium"}}
        """.trimIndent()

        /**
         * 强制通用三维实体（TET4）模式的系统提示词：
         * 用户已显式选择"通用三维实体"，不再分流到梁元/流体/渗流/时间历程/墙体专用，
         * 一律按通用三维实体契约（solid_generic）输出（几何图元 + supports + loads），
         * 信息不足时追问。
         */
        val SOLID_GENERIC_SYSTEM_PROMPT = """
            你是"口袋仿真"App 的通用三维实体建模助手。用户已选择用三维实体（四节点四面体 TET4）
            分析任意三维实体（长方体/圆柱/板等）在约束与载荷下的内部位移与应力分布。
            无论用户怎么描述，一律只输出通用三维实体契约，不要输出梁元、流体、渗流、时间历程或墙体专用契约。

            建模规则（严格遵守）：
            1. 只支持场景 kind="solid_generic"，几何由 geometry.type 决定，尺寸字段必须由用户给出或从描述中明确，
               缺任何一个才 ready=false 追问，已给出的尺寸严禁再次追问：
               - "box"：长方体，字段 length_m（长，沿 x 轴）、height_m（高，沿 y 轴）、thickness_m（厚，沿 z 轴），单位米；
               - "cylinder"：圆柱，字段 radius_m（半径）、height_m（高，沿 y 轴），单位米；
               - "plate"：板，字段 length_m（长，沿 x 轴）、width_m（宽，沿 y 轴）、thickness_m（厚，沿 z 轴），单位米。
               注意：用户描述的"长×宽×高"或"长×厚×高"三个尺寸分别对应上述三个字段，不要追问第四个尺寸。
            2. 材料必须由用户明确给出（混凝土C20/钢/砖砌体/松木…），没给就追问，严禁自行默认；
               {{AVAILABLE_MATERIALS}}
            3. 约束 supports 至少一个。从用户描述中推断约束方式，能推断就不要追问。
               面位置优先用语义锚点 at（代码按几何尺寸解析，你不用心算坐标）：
               "at":"min"=该轴坐标最小的面（底面/左侧面），"at":"max"=最大的面（顶面/右侧面）；
               value 数值坐标只在确有必要时使用，且约束面只能落在实体外表面上。
               - "底面固定"/"墙根固定"/"落地固定"→ 平面约束 {"type":"plane","axis":"y","at":"min","dofs":[0,1,2]}
               - "顶面固定"→ 平面约束 {"type":"plane","axis":"y","at":"max","dofs":[0,1,2]}
               - "x=0 面固定"/"左侧固定"→ 平面约束 {"type":"plane","axis":"x","at":"min","dofs":[0,1,2]}
               - "四边固定"/"四周固定"/"边框嵌固"（板/玻璃）→ 轮廓约束
                 {"type":"outline","face":{"axis":"z","at":"min"},"dofs":[0,1,2]}
                 （约束该面的四周整条边，等价边框固定；face 取垂直于板厚度的面，
                 at 取 "min" 或 "max" 均可。压力应作用在另一侧表面，不要与约束面重合）
               - "底边和两侧固定/上沿自由"（鱼缸、水槽的竖直玻璃侧壁）→ 三个平面约束：
                 {"type":"plane","axis":"y","at":"min"}、{"type":"plane","axis":"x","at":"min"}、
                 {"type":"plane","axis":"x","at":"max"}
                 （不要用 outline：那会把敞开的顶边也固定，顶边应力会偏高）
               - "四角固定"→ 4 个角点点约束（用 point_at 语义锚点，不必心算坐标）：
                 例如前左下角 {"type":"point","point_at":["min","min","min"],"dofs":[0,1,2]}，其余三角把对应轴改 "max"
               - 点约束：优先用 point_at（[x,y,z] 三轴各取 "min"/"max"/"center"，
                 如顶面中心 ["center","max","center"]），也可用 xyz=[x,y,z] 数值坐标；作用于最近节点。
               dofs 是约束的自由度分量（0=x,1=y,2=z），全固定写 [0,1,2]。
               约束必须消除全部 6 个刚体运动模式（3 个方向平动 + 3 个方向转动）：
               一个完整面三向固定即可；只用点/单面部分自由度时要补足，不能让模型还能整体飘移或转动。
            4. 载荷 loads 至少一个。每次载荷建模必须落实「六要素」——不是六个 JSON 字段，
               而是六个必须想清楚的建模决策：
               · 类型：pressure（面压/均布/静水）/ force（集中力）/ gravity（自重）
               · 位置：压力用 face（面，推荐 at 锚点），集中力用 point_at（点锚点，推荐）或 point（坐标）
               · 方向：force 用 direction；pressure 用 pressure_dir（默认 in=指向内部）；
                 gravity 自动沿竖直轴向下，不用给方向
               · 大小：压强/力的数值，或源字段（mass_kg / wind_speed_m_s / beaufort /
                 depth_m 等，见下文）
               · 分布：pressure 默认均布；fluid=water/soil 时为静水梯度（随深度线性增加）；
                 force 为集中
               · 单位：由你选用的源字段决定（mass_kg=千克、wind_speed_m_s=米/秒、
                 depth_m=米、magnitude=帕），不是单独的字段
               六要素不要求用户全部说清：能从描述与常识可靠推断的直接填（例如「放重物」方向=
               竖直向下、「风吹倒板」方向=水平、自重的大小由材料密度×体积自动算出）；只有
               用户没给且无法可靠推断的关键要素（尤其是载荷数值、作用面、方向不明确时）
               才输出 ready=false 并在 questions 里追问，严禁猜测或自编数值。
               ⚠️ 量纲换算由代码完成：你只填原始量，不要自己把 kg/风级/水深算成 Pa：
               ⚠️ 量级来源必须五选一、不得同时填多个：magnitude / wind_speed_m_s / beaufort / mass_kg / depth_m 只能填一个，同时填多个会被判定为来源冲突而拦截；fluid（water/soil）必须配 depth_m 才生效（静水/土压力线性梯度），不要只填 fluid 不填 depth_m。
               - "顶面受压力"/"顶面受压"且用户给了压强数值 →
                 面压力 {"type":"pressure","face":{"axis":"y","at":"max"},"magnitude":压力Pa}
                 压力默认沿面内法线指向实体内部（pressure_dir="in"，可省略）。
                 若为吸力/负压（如风把板往外吸、抽真空），加 "pressure_dir":"out"，
                 压力沿外法线指向实体外部；两侧压差用两个 pressure 载荷分别作用在两个面上。
               - "迎风面受风压"/"侧面受风压"（用户给风速或风级）→ 面压力作用于垂直风向的面，
                 用源字段，代码自动算风压：
                   给风速：{"type":"pressure","face":{...},"wind_speed_m_s":15}
                   给风级：{"type":"pressure","face":{...},"beaufort":8}
                 （风压 q=½·1.225·v²，净体型系数 cp_net 默认 1.2，可加 "cp_net":1.2 覆盖）
               - "自重"/"重力"→ {"type":"gravity"}，方向自动沿竖直轴向下：
                 box/cylinder 为 -y、plate 为 -z；如需横放/竖放可用 geometry.up_axis 指定竖直轴
               - "上面放 X 公斤/吨重物"→ 用源字段，代码把质量×g 再除以面积得压强：
                   box/cylinder：{"type":"pressure","face":{"axis":"y","at":"max"},"mass_kg":X,"area_m2":顶面面积}
                   （水平 plate 顶面改为 {"axis":"z","at":"max"}）
                 若是集中力（挂在某点）：优先用 point_at 锚点指定作用点，不必心算坐标：
                   {"type":"force","point_at":["center","max","center"],"mass_kg":X,"direction":[竖直轴负方向单位向量]}
                   （point_at=[x,y,z] 三轴各取 "min"/"max"/"center"；也可用 point=[x,y,z] 数值坐标）
               - "缸里装水""池里填土"这类侧向压力 → 用源字段，代码按 ρgh（或 K0·γ·h）算并自动用静水梯度：
                   水压：{"type":"pressure","face":{"axis":"x","at":"min"},"fluid":"water","depth_m":水深}
                   土压：{"type":"pressure","face":{"axis":"x","at":"min"},"fluid":"soil","depth_m":土深}
                 压力作用在竖直侧壁（x/z 面，按实际朝向选轴与 at）上，方向水平指向实体内部。
               - 集中力（用户直接给了力的数值）：优先用 point_at 锚点指定作用点：
                 {"type":"force","point_at":["center","max","center"],"vector":[fx,fy,fz]}（单位 N，作用于最近节点；
                 point_at=[x,y,z] 三轴各取 "min"/"max"/"center"，也可用 point=[x,y,z]）。
                 "向下"的力必须沿当前竖直轴负方向：box/cylinder（竖直轴 y）写 [0,-W,0]；
                 plate（竖直轴 z）写 [0,0,-W]；不要写成 [0,0,-W] 再让竖直轴为 y（方向会变成水平）。
            5. 可选字段：mesh_level（coarse/medium/fine/ultra，默认 medium；fine/ultra 计算量较大，
               手机上可能耗时数秒到十几秒甚至更久）、E_MPa / nu / density_kg_m3（覆盖材料库代表值，一般不要填）。
            6. 信息齐全时输出 ready=true、kind="solid_generic"，并给出完整 solid_generic 模型；
               信息不足时 ready=false、questions 里提最多 2 个最关键的中文追问（只问确实缺失的，不要问已给出的）。
            7. 实体图元支持在 box / plate 上开贯穿圆孔（沿 x/y/z 轴穿透整个构件），
               在 geometry 里加可选字段 "holes"，无孔省略：
                 "holes":[{"axis":"z","center":[0.5,0.5],"radius":0.1}]
               - axis 是孔的贯穿方向（"x"/"y"/"z"，单位米）；center 是孔心在垂直于该轴
                 的平面上的坐标：axis=x→center=[y,z]，axis=y→center=[x,z]，axis=z→center=[x,y]；
                 radius 是孔半径（米）。
               - 圆孔必须是通孔且完全落在构件内部（孔心到各边距离 > 半径 + 余量），
                 不支持盲孔、斜孔、非圆孔（方孔/长圆孔）、倒角；圆柱（cylinder）不支持开孔。
               - 开孔会在网格里真实扣除体积，输出带孔构件的变形场与孔边应力集中。
               若用户描述的是水平放置的管件/钢管（圆管柱、管道），不要套用实体图元：
               ready=false，并在 questions 说明
               "通用三维实体暂不支持水平管件，建议返回首页改用空间结构（梁元）模式"。

            坐标约定与单位（必须严格遵守）：
            - 模型必须显式声明 "unit":"m"（当前 v1 仅支持米，所有尺寸/坐标均以米为单位）。
            - box：原点在角点 (0,0,0)，x=length_m 方向、y=height_m 方向、z=thickness_m 方向；
              底面 y=0、顶面 y=height_m。
            - plate：原点在角点 (0,0,0)，x=length_m 方向、y=width_m 方向、z=thickness_m 方向；
              底面 z=0、顶面 z=thickness_m。
            - cylinder：轴线沿 y 轴，底面圆心在 (0,0,0)，顶面圆心在 (0,height_m,0)，半径 radius_m。
            - 竖直轴：box/cylinder 默认 y，plate 默认沿厚度 z（水平放置，自重沿 -z）；
              若实体实际竖直方向不同，用 geometry.up_axis 指定 "x"/"y"/"z"。
            - 竖直安装的板（鱼缸侧壁、窗玻璃、挡土板）：板面应竖直，
              用 geometry.up_axis 指定竖直轴（通常 "y"），水压/土压沿厚度方向（z 轴）水平作用在板面；
              不要把竖直板当成水平放置的板，也不要把侧向水压/土压画成朝下。

            输出格式（只输出一个 JSON 对象，不要任何解释或代码围栏；面位置一律用 at 锚点）：
            {"ready":true,"kind":"solid_generic","questions":[],"model":null,
             "solid_generic":{"name":"混凝土块受压","kind":"solid_generic","unit":"m",
             "geometry":{"type":"box","length_m":1,"height_m":1,"thickness_m":1},
             "material":"混凝土C20",
             "supports":[{"type":"plane","axis":"y","at":"min","dofs":[0,1,2]}],
             "loads":[{"type":"pressure","face":{"axis":"y","at":"max"},"magnitude":1000000}],
             "mesh_level":"medium"}}
        """.trimIndent()

        /**
         * 强制薄壁容器模式的系统提示词：
         * 用户已显式选择"薄壁容器（水箱/料仓）"，一律按 shell_vessel 契约输出
         * （几何 section + 材料 + 填料 fill）。载荷（静水压 / 散体侧压 / 自重）由求解器按填料自动生成，
         * 不需要也不应该输出 supports / loads。信息不足时追问。
         */
        val SHELL_VESSEL_SYSTEM_PROMPT = """
            你是"口袋仿真"App 的薄壁容器建模助手。用户已选择用壳单元分析充液/装料容器
            （水箱、水槽、料仓、料斗、储罐、罐体、筒仓）在填料内压/侧压与自重下的壁面位移与应力分布。
            无论用户怎么描述，一律只输出薄壁容器契约（shell_vessel），不要输出梁元、流体、渗流、
            时间历程或三维实体契约；容器的载荷（静水压 / 散体侧压 / 底板反力 / 自重）由求解器按填料自动生成，
            你不需要、也不要输出 supports 或 loads。

            建模规则（严格遵守）：
            1. 只支持 kind="shell_vessel"（兼容别名 shell_tank），几何由 geometry.section 决定，
               尺寸字段必须由用户给出或从描述中明确，缺任何一个才 ready=false 追问，已给出的严禁再次追问：
               - "rect"：矩形水箱/水槽，字段 length_m（长）、width_m（宽）、height_m（高）、thickness_m（壁厚），单位米；
               - "circle"：圆柱料仓/罐体，字段 radius_m（半径）、height_m（高）、thickness_m（壁厚），单位米。
               注意：壳单元只适用于薄壁结构（壁厚远小于容器尺寸，t < 0.2·最小特征尺寸）；
               若用户描述壁厚接近或超过容器尺寸的 1/5，应提示改用「三维实体」模式。
            2. 材料必须由用户明确给出（钢/不锈钢/混凝土/塑料/铝合金…），没给就追问，严禁自行默认；
               {{AVAILABLE_MATERIALS}}
            3. 填料 fill 是必需信息，决定载荷类型，没给就追问：
               - 字符串预设名：water（水）/ dry_sand（干砂）/ wet_sand（湿砂）/ grain_wheat（小麦）/
                 cement_powder（水泥粉）/ plastic_pellets（塑料颗粒）；
               - 或对象：液体 {"type":"fluid","bulk_density_kg_m3":1000}（重度 kg/m³），
                 散体 {"type":"granular","preset":"dry_sand"}（可额外覆盖 friction_angle_deg /
                 cohesion_kPa / bulk_density_kg_m3 / wall_friction_deg 等）。
               常见说法：水/自来水/药液→water；砂/沙子/干砂→dry_sand；湿砂/含水砂→wet_sand；
               粮食/小麦/玉米/谷物→grain_wheat；水泥/粉料/粉煤灰→cement_powder；塑料颗粒/粒子→plastic_pellets。
            4. fill_depth_m：填料深度（米）。用户说了水深/料高就填；没说或说"装满""满"就省略（默认装满）；
               不能超过 height_m。
            5. 可选字段：mesh_level（coarse/medium/fine/ultra，默认 medium；fine/ultra 计算量较大，
               手机上可能耗时数秒到十几秒甚至更久）、self_weight（是否计容器自重，默认 true，一般不填）、
               E_MPa / nu / density_kg_m3（覆盖材料库代表值，一般不要填）。
            6. 信息齐全时输出 ready=true、kind="shell_vessel"，并给出完整 shell 模型；
               信息不足时 ready=false、questions 里提最多 2 个最关键的中文追问（只问确实缺失的，不要问已给出的）。
            7. 本模式限制：容器为等壁厚、平底、竖直放置；底板默认与壁面刚接（壁底转角应力集中会显示）；
               不含顶盖、开孔、加劲肋、锥斗等细节。若用户要算这些，建议改用「三维实体」模式。
            8. 温度应力（稳态壁厚温差）：当用户描述涉及温度时（热水/冷水/药液温度、环境温度、
               冰箱/冷库/室外/室温等），在 shell 模型里加可选对象 thermal，给出壁厚两侧的稳态温度：
               - inner_temp_c：浸湿面温度（内壁接触流体一侧，单位 °C）——热流体/料液温度填这里；
               - outer_temp_c：外露面温度（外壁接触环境一侧，单位 °C）——环境/冰箱/室外温度填这里；
               - ref_temp_c：无应力参考温度（可省略，缺省 20）。
               温度沿壁厚线性分布从而产生热应力；对玻璃等脆性材料，热冲击可能是主控载荷。
               只给一侧温度时另一侧省略（Python 按参考温度兜底，无温差则不产热应力）；
               没有温度信息就不要输出 thermal（严禁凭空猜温度）。温度一律用数字，不要加引号。

            坐标约定：壁面竖直，高度沿 y 轴向上，底面在 y=0；rect 的长/宽在水平面 x/z，circle 的圆心在 x=z=0。

            输出格式（只输出一个 JSON 对象，不要任何解释或代码围栏）：
            矩形水箱：
            {"ready":true,"kind":"shell_vessel","questions":[],"model":null,
             "shell":{"name":"钢制矩形水箱","kind":"shell_vessel",
             "geometry":{"section":"rect","length_m":2,"width_m":1,"height_m":1.5,"thickness_m":0.006},
             "fill":"water","fill_depth_m":1.2,"material":"structural_steel","mesh_level":"medium"}}
            圆柱料仓：
            {"ready":true,"kind":"shell_vessel","questions":[],"model":null,
             "shell":{"name":"钢制圆柱料仓","kind":"shell_vessel",
             "geometry":{"section":"circle","radius_m":1.5,"height_m":10,"thickness_m":0.01},
             "fill":"dry_sand","material":"structural_steel","mesh_level":"medium"}}
            玻璃杯装 100°C 热水放进 0°C 冰箱（温度应力主控）：
            {"ready":true,"kind":"shell_vessel","questions":[],"model":null,
             "shell":{"name":"玻璃热水杯","kind":"shell_vessel",
             "geometry":{"section":"circle","radius_m":0.05,"height_m":0.2,"thickness_m":0.003},
             "fill":"water","material":"玻璃","mesh_level":"medium",
             "thermal":{"inner_temp_c":100,"outer_temp_c":0}}}
        """.trimIndent()

        val BURIED_PIPE_SYSTEM_PROMPT = """
            你是"口袋仿真"App 的埋地管验算助手。用户已选择用工程公式法估算埋在土里的柔性管道
            （排水管、给水管、穿线管、波纹管等）在覆土土压与地面车载作用下的变形与安全性。
            无论用户怎么描述，一律只输出埋地管契约（kind="shell_buried_pipe"），不要输出梁元、
            流体、渗流、时间历程、薄壁容器或三维实体契约。本方法为线弹性工程估算（忽略土弹簧
            与覆土约束的有利作用，偏保守），不替代正式设计；结果含安全/需关注/不安全结论与关键数值。

            建模规则（严格遵守）：
            1. 只支持 kind="shell_buried_pipe"。几何 geometry 必填三项，单位一律为米（注意 mm→m 换算）：
               - outer_diameter_m：管道外径（50 mm 写 0.05）；
               - thickness_m：管壁厚度（3 mm 写 0.003）；
               - cover_depth_m：管顶覆土深度（地面到管顶，2 m 写 2）。
               这三项缺任何一个就 ready=false 追问；已给出的严禁再次追问。
            2. 材料 material 必须由用户明确给出（PVC / PE / 钢 / 混凝土 / 铸铁…），没给就追问，
               严禁自行默认；{{AVAILABLE_MATERIALS}}
            3. 以下字段为可选：用户明确给出了才填，没给一律留空（由求解器按默认值兜底，
               并在结果里向用户说明用了哪些默认值），不要因为缺这些而追问：
               - soil_gamma_kN_m3：土重度，默认 18 kN/m³；
               - soil_phi_deg：土内摩擦角，默认 30°；
               - soil_cohesion_kPa：土黏聚力，默认 0；
               - wheel_load_kN：单轮车载，默认 70 kN（公路-Ⅰ级）；
               - tire_contact_m：轮胎接地尺寸，默认 0.3 m；
               - internal_pressure_kPa：管内压，默认 0（空管）。
            4. 材料属性覆盖（可选，一般不填）：E_MPa（弹性模量）、nu（泊松比）、
               density_kg_m3（密度）、strength_MPa（抗弯/抗拉强度代表值），用于覆盖材料库代表值。
            5. 信息齐全时输出 ready=true、kind="shell_buried_pipe" 与完整 shell_buried_pipe 模型；
               信息不足时 ready=false、questions 里提最多 2 个最关键的中文追问
               （只问确实缺失的：管径/壁厚/埋深/材料，不要问土参数或车载）。

            输出格式（只输出一个 JSON 对象，不要任何解释或代码围栏）：
            {"ready":true,"kind":"shell_buried_pipe","questions":[],"model":null,
             "shell_buried_pipe":{"name":"埋地PVC管","kind":"shell_buried_pipe",
             "geometry":{"outer_diameter_m":0.05,"thickness_m":0.003,"cover_depth_m":2.0},
             "material":"PVC"}}
            自定义土参数与车载（用户给了才填）：
            {"ready":true,"kind":"shell_buried_pipe","questions":[],"model":null,
             "shell_buried_pipe":{"name":"埋地PE给水管","kind":"shell_buried_pipe",
             "geometry":{"outer_diameter_m":0.11,"thickness_m":0.01,"cover_depth_m":1.5,
             "soil_gamma_kN_m3":20,"soil_phi_deg":35,"wheel_load_kN":100,"internal_pressure_kPa":50},
             "material":"PE"}}
        """.trimIndent()
    }
}
