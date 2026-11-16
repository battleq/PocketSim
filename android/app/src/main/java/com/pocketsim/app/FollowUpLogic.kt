/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

/**
 * 缺参追问策略（纯逻辑，JVM 可单测）：
 *  - 每轮最多追问 [MAX_QUESTIONS_PER_ROUND] 个"关键缺失项"（必填或无默认值的参数）；
 *  - 最多追问 [MAX_ROUNDS] 轮，其余缺失项在参数确认页用默认值兜底（来源标"内置"）。
 */
object FollowUpLogic {

    /** 每轮最多追问的缺失项数 */
    const val MAX_QUESTIONS_PER_ROUND = 2

    /** 最多追问轮数 */
    const val MAX_ROUNDS = 2

    /**
     * 计算关键缺失项：未提供、且（必填 或 无默认值）的参数。
     * 只有这些才值得追问——其余缺失项有内置默认值兜底。
     */
    fun criticalMissing(parse: AiParseResult, templates: List<TemplateInfo>): List<String> {
        val spec = templates.firstOrNull { it.id == parse.template_id } ?: return parse.missing_params
        val provided = parse.params.keys
        return spec.param_schema
            .filter { it.key !in provided && (it.required || it.default == null) }
            .map { it.key }
    }

    /**
     * 生成本轮追问文案（≤ [MAX_QUESTIONS_PER_ROUND] 条）：
     * 优先使用 LLM 给出的 questions（按顺序配对），不足的按 schema 自动生成。
     */
    fun selectQuestions(
        critical: List<String>,
        llmQuestions: List<String>,
        templates: List<TemplateInfo>,
        english: Boolean = false
    ): List<String> {
        if (critical.isEmpty()) return emptyList()
        // key→spec 映射（跨全部模板收集，键名在模板间不冲突）
        val specByKey = templates.flatMap { it.param_schema }.associateBy { it.key }
        val chosen = critical.take(MAX_QUESTIONS_PER_ROUND)
        return chosen.mapIndexed { i, key ->
            val llmQ = llmQuestions.getOrNull(i)?.takeIf { it.isNotBlank() }
            llmQ ?: autoQuestion(specByKey[key], english)
        }
    }

    /** 是否还应再追问一轮 */
    fun shouldAskAnotherRound(roundsAsked: Int, stillCritical: List<String>): Boolean =
        roundsAsked < MAX_ROUNDS && stillCritical.isNotEmpty()

    /** 按 schema 自动生成的兜底追问 */
    private fun autoQuestion(spec: ParamSpec?, english: Boolean = false): String {
        if (spec == null) {
            return if (english) "Please provide the missing key information."
            else "请补充缺失的关键信息。"
        }
        val name = spec.label.ifBlank { spec.key }
        return if (english) {
            val unit = spec.unit.takeIf { it.isNotBlank() }?.let { " (unit: $it)" } ?: ""
            "Please provide \"$name\"$unit: roughly how much?"
        } else {
            val unit = spec.unit.takeIf { it.isNotBlank() }?.let { "（单位：$it）" } ?: ""
            "请补充「$name」$unit：大约是多少？"
        }
    }
}
