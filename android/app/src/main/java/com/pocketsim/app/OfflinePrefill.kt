/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import kotlinx.serialization.json.JsonPrimitive

/**
 * 离线智能预填的编排与映射逻辑（纯 Kotlin，JVM 可单测）。
 *
 * 流程：ProblemParser.parseProblem(用户描述) → 命中模板 + 抽取参数
 *  → [ParsedScenario]（来源标 [ParamSource.OFFLINE]）→ 参数确认页，
 *    未抽到的参数记入 missing，参数卡标"待补充"；
 *  → 识别失败 / 模板未命中 / 引擎异常 → null，UI 提示"未能识别，请手动选择模板"并留在当前页。
 */
object OfflinePrefill {

    /**
     * 完整离线预填流程（HomeScreen / ChatScreen 调用）。
     * 引擎异常（模块缺失、字段不符等）一律吞掉并按"未识别"处理，不向用户暴露崩溃。
     */
    suspend fun run(
        parser: ProblemParser,
        text: String,
        templates: List<TemplateInfo>
    ): ParsedScenario? = try {
        toScenario(parser.parseProblem(text), templates)
    } catch (e: Exception) {
        null
    }

    /**
     * 解析结果 → 参数确认页载荷。
     * @return null 表示未识别 / 未知模板（调用方留在当前页并提示手动选模板）
     */
    fun toScenario(outcome: ParseOutcome?, templates: List<TemplateInfo>): ParsedScenario? {
        if (outcome == null) return null
        val template = templates.firstOrNull { it.id == outcome.templateId } ?: return null

        // 参数值统一转字符串（数字保留字面值）；跳过空值与非标量（嵌套对象等）
        val params = LinkedHashMap<String, String>()
        outcome.params.forEach { (key, value) ->
            val text = (value as? JsonPrimitive)?.content?.trim().orEmpty()
            if (text.isNotEmpty()) params[key] = text
        }

        // missing 只保留该模板 schema 中真实存在的键，避免 bogus 键渲染出无意义的"待补充"标记
        val schemaKeys = template.param_schema.map { it.key }.toSet()
        val missing = outcome.missing.filter { it in schemaKeys }

        return ParsedScenario(
            template = template,
            params = params,
            sources = params.keys.associateWith { ParamSource.OFFLINE },
            missing = missing,
            offline = true
        )
    }
}
