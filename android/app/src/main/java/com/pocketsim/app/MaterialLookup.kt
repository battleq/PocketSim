/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * AI 联网检索材料参数：
 * 让 LLM（可能带联网能力）返回材料力学代表值 JSON → 校验数值合理范围 → [MaterialRecord]（source=web）。
 * 随后由调用方经 [FeaEngine.upsertMaterial] 写入本地缓存库，参数来源标签显示"联网检索"。
 */
class MaterialLookup(private val aiClientProvider: suspend () -> AiClient) {

    /** 数值合理性范围（超出即视为检索结果不可靠而拒绝） */
    private object Range {
        const val E_MIN = 0.5        // MPa，软橡胶下限
        const val E_MAX = 1_500_000.0  // MPa，超高模量材料上限
        const val NU_MIN = 0.0
        const val NU_MAX = 0.49
        const val DENSITY_MIN = 50.0    // kg/m³
        const val DENSITY_MAX = 25000.0
        const val STRENGTH_MIN = 0.01   // MPa
        const val STRENGTH_MAX = 5000.0
    }

    /**
     * 检索一种材料的代表值。
     * @param query 材料名称或描述（如"橡木""304 不锈钢"）
     * @param english 英文界面：错误文案双语，且要求 LLM 用英文给出材料名/原因
     * @throws AiParseException LLM 未返回合法材料卡
     * @throws IllegalArgumentException 数值超出合理范围
     */
    suspend fun lookup(query: String, english: Boolean = false): MaterialRecord {
        fun t(zh: String, en: String): String = if (english) en else zh
        val client = aiClientProvider()
        val langRule = if (english) {
            "\nWhen the app language is English: output \"name\" as the English material name " +
                "and write any \"error\" reason in English."
        } else ""
        val system = """
            你是材料数据助手，具备联网检索能力。请给出指定材料的力学工程代表值（多条来源不同时取常见代表值）。
            只输出一个 JSON 对象，字段如下，不要输出任何其他文字：
            {"name":"材料中文名","category":"分类","E_MPa":数字,"nu":数字,
             "density_kg_m3":数字,"strength_MPa":数字,
             "strength_type":"屈服强度代表值/抗弯强度代表值等",
             "notes":"数据来源说明"}
            其中 E_MPa 为弹性模量（MPa）、nu 为泊松比、density_kg_m3 为密度（kg/m³）、strength_MPa 为强度代表值（MPa）。
            若无法可靠给出该材料数据，只输出 {"error":"原因"}。$langRule
        """.trimIndent()

        val content = client.complete(
            listOf(LlmMessage("system", system), LlmMessage("user", "材料：$query")),
            jsonMode = true
        )

        val jsonText = AiParser.extractJson(content)
            ?: throw AiParseException(t("材料检索回复中未找到 JSON", "No JSON object in the material lookup reply"))
        val root = AppJson.parseToJsonElement(jsonText)
        if (root !is JsonObject) {
            throw AiParseException(t("材料检索回复顶层不是 JSON 对象", "The material lookup reply is not a JSON object"))
        }

        root["error"]?.let {
            throw AiParseException(
                t("AI 未能给出可靠数据：${it.jsonPrimitive.content}", "No reliable data from the AI: ${it.jsonPrimitive.content}")
            )
        }

        val record = AppJson.decodeFromJsonElement(MaterialRecord.serializer(), root)
        validate(record, english)
        return record.copy(source = "web")
    }

    /** 数值合理性校验（防 LLM 幻觉产生离谱值） */
    private fun validate(r: MaterialRecord, english: Boolean = false) {
        fun t(zh: String, en: String): String = if (english) en else zh
        require(r.name.isNotBlank()) { t("材料名为空", "Material name is empty") }
        require(r.eMPa in Range.E_MIN..Range.E_MAX) {
            t("弹性模量 ${r.eMPa} MPa 超出合理范围", "Elastic modulus ${r.eMPa} MPa is out of plausible range")
        }
        require(r.nu in Range.NU_MIN..Range.NU_MAX) {
            t("泊松比 ${r.nu} 超出合理范围", "Poisson's ratio ${r.nu} is out of plausible range")
        }
        r.density_kg_m3?.let {
            require(it in Range.DENSITY_MIN..Range.DENSITY_MAX) {
                t("密度 $it kg/m³ 超出合理范围", "Density $it kg/m³ is out of plausible range")
            }
        }
        r.strength_MPa?.let {
            require(it in Range.STRENGTH_MIN..Range.STRENGTH_MAX) {
                t("强度 $it MPa 超出合理范围", "Strength $it MPa is out of plausible range")
            }
        }
    }
}
