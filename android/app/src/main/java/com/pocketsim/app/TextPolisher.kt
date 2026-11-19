/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** 润色失败（提示直接展示给用户） */
class PolishException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 输入框 AI 润色：把用户的口语化描述整理成更清晰的中文，便于后续建模理解。
 *
 * 只做“整理”，不做“创造”：
 *  - 原文的数值与单位必须原样保留（提示词约束 + 输出数值校验双保险）；
 *  - 输出中若出现原文没有的数字，直接判定失败并提示，避免 AI 自行编造尺寸/荷载；
 *  - 手动触发、结果可撤销（由界面负责）；失败不影响原输入。
 */
class TextPolisher(private val clientProvider: suspend () -> AiClient) {

    /** 润色描述并返回整理后的文本；失败抛 [PolishException]。english=true 时提示词与报错均走英文 */
    suspend fun polish(text: String, english: Boolean = false): String {
        val src = text.trim()
        if (src.isEmpty()) {
            throw PolishException(if (english) ERR_EMPTY_EN else ERR_EMPTY_ZH)
        }
        if (src.length > MAX_INPUT_CHARS) {
            throw PolishException(
                if (english) "Description too long (over $MAX_INPUT_CHARS characters); please shorten it before polishing"
                else "描述太长（超过 $MAX_INPUT_CHARS 字），请精简后再润色"
            )
        }
        val client = clientProvider()
        val raw = try {
            withTimeout(TIMEOUT_MS) {
                client.complete(
                    listOf(
                        LlmMessage("system", if (english) SYSTEM_PROMPT_EN else SYSTEM_PROMPT),
                        LlmMessage("user", src)
                    ),
                    jsonMode = false, tag = "polish"
                )
            }
        } catch (e: TimeoutCancellationException) {
            throw PolishException(
                if (english) "Polish timed out; check your network and try again" else "润色超时，请检查网络后重试", e
            )
        }
        val polished = clean(raw)
        if (polished.isBlank()) {
            throw PolishException(if (english) ERR_EMPTYRESULT_EN else ERR_EMPTYRESULT_ZH)
        }
        if (polished.length > MAX_OUTPUT_CHARS) {
            throw PolishException(
                if (english) "Polished result is too long; please split the input and try again" else "润色结果过长，请拆分后再试"
            )
        }
        val invented = introducedNumbers(src, polished)
        if (invented.isNotEmpty()) {
            throw PolishException(
                if (english) "The polished text added numbers not present in the original (${invented.joinToString(", ")}); cancelled. Please retry or edit manually"
                else "润色结果出现了原文没有的数值（${invented.joinToString("、")}），已取消，请重试或手动修改"
            )
        }
        return polished
    }

    /** 去掉代码围栏/引号/“整理后：”之类的前后缀，只留正文 */
    private fun clean(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("^```[\\w-]*\\s*", RegexOption.MULTILINE), "")
            .replace("```", "")
            .trim()
        s = s.removePrefix("润色后：").removePrefix("润色后:")
            .removePrefix("整理后：").removePrefix("整理后:")
            .removePrefix("Polished:").removePrefix("Cleaned:")
            .trim()
        if (s.length >= 2 &&
            ((s.startsWith("“") && s.endsWith("”")) || (s.startsWith("\"") && s.endsWith("\"")))
        ) {
            s = s.substring(1, s.length - 1).trim()
        }
        return s.replace(Regex("\n{3,}"), "\n\n")
    }

    /** 输出中出现、但原文没有的数值（用于拦截 AI 编造参数） */
    private fun introducedNumbers(src: String, out: String): List<String> {
        val srcNumbers = numberTokens(src)
        return numberTokens(out).filterNot { it in srcNumbers }.distinct()
    }

    private fun numberTokens(s: String): Set<String> =
        Regex("""\d+(?:\.\d+)?""").findAll(s).map { normalizeNumber(it.value) }.toSet()

    /** "1.50"→"1.5"、"080"→"80"，避免仅格式差异被误判为新增数值 */
    private fun normalizeNumber(v: String): String {
        val parts = v.split('.')
        val intPart = parts[0].trimStart('0').ifEmpty { "0" }
        if (parts.size == 1) return intPart
        val fracPart = parts[1].trimEnd('0')
        return if (fracPart.isEmpty()) intPart else "$intPart.$fracPart"
    }

    private companion object {
        const val MAX_INPUT_CHARS = 1200
        const val MAX_OUTPUT_CHARS = 2400
        const val TIMEOUT_MS = 20_000L

        const val ERR_EMPTY_EN = "Please enter a description to polish first"
        const val ERR_EMPTY_ZH = "请先输入要润色的描述"
        const val ERR_EMPTYRESULT_EN = "AI returned no usable polished text"
        const val ERR_EMPTYRESULT_ZH = "AI 没有返回可用的润色结果"

        // 英文界面下必须用英文提示词，否则润色结果返回中文（v1.2.34 修复）
        val SYSTEM_PROMPT_EN = """
            You are an input-cleanup assistant for an engineering simulation app.
            The user describes a structure, load, support or material problem in everyday language.
            Rewrite it into a clear, complete, concise English description for downstream automatic modeling:
            1. Keep every number and unit exactly as given — no conversion, no rounding, never invent new numbers;
            2. Only adjust wording, sentence order and connectors; make the subject explicit and drop chit-chat unrelated to the structure;
            3. Do not fabricate missing information, do not assume dimensions, materials, loads or boundary conditions, and do not ask questions back;
            4. Use one or two plain paragraphs — no headings, numbering, lists or Markdown;
            5. Output only the cleaned description itself, no explanations, no quotes.
        """.trimIndent()

        val SYSTEM_PROMPT = """
            你是工程仿真 App 的输入整理助手。用户会用口语描述一个结构、荷载、支承或材料问题。
            请把这段描述整理成清晰、完整、简短的中文，便于后续自动建模：
            1. 保留用户给出的全部数值和单位，原样照抄，不要换算、不要四舍五入、不要新增任何数字；
            2. 只调整语序、断句和连接词，补上明确的主语，删去与结构无关的闲聊；
            3. 不要编造缺失的信息，不要自行假设尺寸、材料、荷载或边界条件，也不要反问；
            4. 用一两段普通中文，不要标题、编号、列表或 Markdown；
            5. 只输出整理后的描述本身，不要解释，不要加引号。
        """.trimIndent()
    }
}
