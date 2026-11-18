/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 框架结果页的追问/改条件会话（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 与 ChatSession 的区别：这里以"当前模型 + 求解结果"为固定上下文，用户消息分三类——
 *  1. 就结果提问 → AI 在 answer 字段里通俗回答（展示为普通对话）；
 *  2. 修改条件（加重/减重、挪位置、换材料、改截面…）→ AI 基于当前模型输出修改后的
 *     【完整】新模型（不是增量 patch），经 [AiParser.parseFrame] 校验通过后回调
 *     [onModelReady]，由 UI 跳预览确认页重算；
 *  3. 修改意图不明确 → questions 追问（≤2 条）。
 *
 * 输出契约与建模模式共用 AiFrameResult（ready/questions/answer/model），
 * 差别仅在 system prompt；AI 忘记 JSON 格式时把原文当普通回答兜底展示。
 */
class FrameFollowUpSession(
    private val aiClientProvider: suspend () -> AiClient,
    private val model: FrameModel,
    private val result: FrameResult,
    /** 修改通过校验 → 携带新模型跳预览确认页（用户确认后重算） */
    private val onModelReady: (FrameModel) -> Unit,
    greeting: String,
    /** 当前语言（"zh"/"en"）：决定 AI 回答语言与 App 侧提示文案 */
    private val language: String = LocaleStore.LANG_ZH
) {

    private val english = language == LocaleStore.LANG_EN

    private fun t(zh: String, en: String): String = if (english) en else zh

    private val _messages = MutableStateFlow<List<ChatMessage>>(listOf(ChatMessage(false, greeting)))
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _fatalError = MutableStateFlow(false)
    val fatalError: StateFlow<Boolean> = _fatalError.asStateFlow()

    /** 对话历史（发给 LLM 的完整消息，assistant 侧保留原始回复） */
    private val history = mutableListOf<LlmMessage>()

    /** 固定上下文：改模型/答疑都以它为准 */
    val systemPrompt: String = buildString {
        append(FOLLOW_UP_PROMPT)
        if (model.dim == 3) {
            append(
                "\n\n【三维模型补充规则（覆盖上文的 2D 约定）】当前模型是空间框架（dim=3）：" +
                    "x/y 为水平面、z 竖直向上，重力沿 -z；集中力可用 fx/fy/fz/mx/my/mz，" +
                    "杆件均布可用 qx/qy/qz；节点必须带 z 坐标；修改后必须保留 \"dim\":3；" +
                    "空心方管/矩形管用 \"wall_t\":壁厚（mm），实心截面省略该字段；" +
                    "圆管/圆钢写 \"shape\":\"circle\"、\"outer_diameter_mm\":外径、\"wall_t\":壁厚" +
                    "（实心圆填 0），此时不写 width_b/depth_h，且圆管不支持开孔。"
            )
        }
        append("\n\n当前模型（JSON，修改时以它为基准）：\n")
        // 案例埋点上下文（_case_meta）不进 LLM 提示词（规则 LOG-01 防泄露）
        append(PromptJson.encodeToString(FrameModel.serializer(), model.copy(case_meta = null)))
        append("\n\n本次分析结果摘要：\n")
        append(
            buildString {
                if (english) {
                    append("Model: ").append(result.model_name).append('\n')
                    append("Max deflection ").append(Units.displacement(result.max_disp_mm).toString()).append(" (")
                    .append(result.max_disp_desc).append("); ")
                    append("Max stress ").append(Units.stress(result.max_stress_MPa).toString())
                    .append(" (governing member: ").append(result.governing_member).append("); ")
                    append("Safety factor ")
                    .append(if (result.safety_factor >= 999.0) "well above 999" else fmt(result.safety_factor))
                    .append(", verdict: ").append(verdictEn()).append(".")
                    if (result.narrative.isNotEmpty()) {
                        append("\nPlain-language explanation: ")
                        result.narrative.forEach { append(it) }
                    }
                } else {
                    append("模型：").append(result.model_name).append('\n')
                    append("最大变形 ").append(fmt(result.max_disp_mm)).append(" mm（")
                    .append(result.max_disp_desc).append("）；")
                    append("最大应力 ").append(fmt(result.max_stress_MPa)).append(" MPa")
                    .append("（受力最大：杆件 ").append(result.governing_member).append("）；")
                    append("安全系数 ")
                    .append(if (result.safety_factor >= 999.0) "远大于 999" else fmt(result.safety_factor))
                    .append("，结论：").append(result.verdict).append("。")
                    if (result.narrative.isNotEmpty()) {
                        append("\n通俗分析：")
                        result.narrative.forEach { append(it) }
                    }
                }
            }
        )
        if (english) {
            append("\n\n").append(FOLLOW_UP_ENGLISH_RULES)
        }
    }

    /** 用户发送一条追问/修改请求 */
    suspend fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return

        append(ChatMessage(fromUser = true, text = trimmed))
        _busy.value = true
        try {
            val userMsg = LlmMessage("user", trimmed)
            val client = aiClientProvider()
            val raw = client.complete(
                listOf(LlmMessage("system", systemPrompt)) + history + userMsg,
                jsonMode = true
            )
            history.add(userMsg)
            history.add(LlmMessage("assistant", raw))
            try {
                val parse = AiParser.parseFrame(raw)
                handle(parse)
            } catch (e: AiParseException) {
                if (AiParser.extractJson(raw) != null) {
                    // 回复是 JSON 但模型校验没过：修改不合法，引导说得更具体
                    append(
                        ChatMessage(
                            false,
                            t(
                                "这次的修改没能通过校验：${e.message}。请把改动说得更具体一些。",
                                "The requested change failed validation: ${parserErrorDetailEn(e.message)}. Please describe the change more specifically."
                            )
                        )
                    )
                } else {
                    // AI 忘了 JSON 格式，当作普通回答兜底展示（常见于纯答疑）
                    append(ChatMessage(false, raw))
                }
            }
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

    private fun handle(parse: AiFrameResult) {
        val newModel = parse.model
        if (parse.ready && newModel != null) {
            append(
                ChatMessage(
                    false,
                    parse.answer.ifBlank { t("已按新条件改好模型，请前往预览确认。", "Model updated to the new conditions — please review and confirm.") }
                )
            )
            onModelReady(newModel)
            return
        }
        // 未就绪：优先展示 answer（纯问答），否则展示追问
        val reply = parse.answer.ifBlank {
            parse.questions.filter { it.isNotBlank() }.take(2).joinToString("\n")
        }
        append(
            ChatMessage(
                false,
                reply.ifBlank { t("请把你想问或想改的内容再说一次。", "Please repeat what you want to ask or change.") }
            )
        )
    }

    /** Python 返回的结论词固定为中文，英文上下文里必须转成英文，避免 AI 跟着说中文 */
    private fun verdictEn(): String = when (result.verdict) {
        "安全" -> "safe"
        "注意" -> "caution"
        "危险" -> "danger"
        else -> result.verdict
    }

    private fun fmt(v: Double): String =
        if (v >= 100) String.format("%.0f", v) else String.format("%.2f", v)

    private fun append(message: ChatMessage) {
        _messages.value = _messages.value + message
    }

    companion object {
        /**
         * 英文界面追加的输出约束：JSON 契约与字段单位不变（内部 SI），
         * 面向用户的 answer/questions/model.name 一律英文，材料用美标名称。
         */
        private val FOLLOW_UP_ENGLISH_RULES = """
            IMPORTANT — LANGUAGE & UNIT SYSTEM (overrides the Chinese wording above):
            - The user communicates in English and the app is running in US (imperial) mode.
            - Write "answer", all "questions" and "model.name" in English; never reply in Chinese.
            - Keep the JSON contract and field units exactly as specified (mm, N, N/mm, °C).
              Convert user-supplied US units to these fields: 1 ft = 304.8 mm, 1 in = 25.4 mm,
              1 lb = 4.4482 N, 1 kip = 4448.2 N, 1 psi = 6894.76 Pa.
            - Use US customary material names (e.g. ASTM A36 steel, Douglas Fir) when the user
              refers to them.
            - You may quote results to the user in psi/in/lb, but the returned model JSON must
              keep the documented units.
        """.trimIndent()

        /** 结果追问模式的系统提示词（输出契约与建模模式一致，复用 AiParser.parseFrame） */
        private val FOLLOW_UP_PROMPT = """
            你是"口袋仿真"App 的结构分析助手。下面给你一次 2D 梁杆框架有限元分析的
            当前模型与结果。用户会就结果提问，或要求修改计算条件，你的任务分三类：

            1. 用户在提问/求解释（"安全系数是什么意思""为什么会弯"等）：
               输出 {"ready":false,"questions":[],"answer":"通俗中文回答","model":null}，
               面向不懂力学的普通人，用生活语言，少用术语，必要时用打比方；
            2. 用户要求修改条件（加重/减重、挪动载荷位置、换材料、改杆件截面或尺寸、
               增删支座等）：基于"当前模型"输出修改后的【完整】新模型（不是只给改动量）：
               {"ready":true,"questions":[],"answer":"一句话说明改了什么","model":{完整 FrameModel}}。
               保持与当前模型一致的节点/杆件编号习惯；换材料只改 material 字段；
               重量 kg×9.8 换算为 N（向下为负）；均布载荷单位 N/mm；
               材料名注意：用户提到具体铝型材型号（如"3030铝型材"、"4040铝型材"）时，
               material 必须写精确名 "欧标2020铝型材" / "欧标3030铝型材" / "欧标4040铝型材" /
               "欧标5050铝型材"（材料库内置空心截面 I/A，求解器自动按真实值算，避免实心矩形高估刚度）；
               截面尺寸填外形宽度（如 3030 型材 width_b=30, depth_h=30）；
               【载荷类型 type 必须且只能用 "force"（节点集中力）、"udl"（杆件均布）、
               "area"（面荷载，按杆件长度自动分配为均布）、"wind"（风载源字段，代码换算）
               或 "thermal"（杆件温度变化），
               禁止用 distributed/point/concentrated 等别名】：
                 - 集中力：{"type":"force","node":节点编号,"fx":0,"fy":-294,"mz":0}
                 - 均布：{"type":"udl","member":杆件编号,"qy":-0.5}
                 - 面荷载：{"type":"area","members":[杆件编号...],"total_N":-600}
                   （面状构件整面承重、作用宽度 ≥0.3 倍跨度时用；总重自动按杆件长度分配）
                 - 风载：{"type":"wind","nodes":[迎风节点编号],"wind_speed_m_s":15,"dir":"x","area_m2":6.0}
                   （wind_speed_m_s 与 beaufort 二选一；净风力 F=cp×½×1.225×v²×A 由代码换算，
                    禁止自己心算成 fx）
                 - 温度：{"type":"thermal","member":杆件编号,"delta_temp":40}
                   （°C，升温为正、降温为负，基准 20°C；用户提到暴晒/结冰/采暖/热胀冷缩时加）
            3. 用户想改但信息不够（如"改重点"没说改成多少）：
               {"ready":false,"questions":["最多 2 个追问"],"answer":"","model":null}。

            口径与单位：坐标毫米、力牛顿、应力 MPa。回答里给出的任何数值都要与
            当前模型/结果一致，不要凭空编造。
            只输出一个 JSON 对象，不要代码围栏或任何其他文字。
        """.trimIndent()
    }
}
