/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 参数确认页的表单状态（纯 Kotlin，可 JVM 单测）。
 *
 * ParamConfirm → Result 数据流：
 *   ParamForm(template, AI 预填) → 校验 → [buildRequest] → FeaEngine.runScenario → FeaResult
 */
class ParamForm(
    val template: TemplateInfo,
    prefilled: Map<String, String> = emptyMap(),
    initialSources: Map<String, ParamSource> = emptyMap()
) {

    /** 参数键 → 当前输入（字符串形式） */
    val values = LinkedHashMap<String, String>()

    /** 参数键 → 来源标签 */
    val sources = LinkedHashMap<String, ParamSource>()

    init {
        template.param_schema.forEach { spec ->
            val pre = prefilled[spec.key]
            if (!pre.isNullOrBlank()) {
                values[spec.key] = pre
                sources[spec.key] = initialSources[spec.key] ?: ParamSource.AI
            } else {
                values[spec.key] = spec.defaultDisplay()
                sources[spec.key] = ParamSource.BUILTIN
            }
        }
    }

    /** 更新一个参数（用户输入，来源变为自填；除非显式指定来源，如联网检索回填） */
    fun set(key: String, value: String, source: ParamSource = ParamSource.USER) {
        values[key] = value
        sources[key] = source
    }

    /** 当前材料名（无材料参数返回 null） */
    fun materialName(): String? =
        template.materialSpec?.let { values[it.key]?.takeIf { v -> v.isNotBlank() } }

    /**
     * 校验全部参数。
     * @return 键 → 错误文案（空 Map 表示全部通过）；[english] 控制错误文案语言
     */
    fun validationErrors(english: Boolean = false): Map<String, String> {
        fun t(zh: String, en: String): String = if (english) en else zh
        val errors = LinkedHashMap<String, String>()
        template.param_schema.forEach { spec ->
            val raw = values[spec.key]?.trim().orEmpty()
            when (spec.type) {
                ParamSpec.TYPE_FLOAT, ParamSpec.TYPE_INT -> {
                    if (raw.isEmpty()) {
                        if (spec.required || spec.default == null) errors[spec.key] = t("该项必填", "Required")
                    } else {
                        val num = if (spec.type == ParamSpec.TYPE_INT) raw.toIntOrNull()?.toDouble()
                        else raw.toDoubleOrNull()
                        if (num == null) {
                            errors[spec.key] = t("数值无效", "Invalid number")
                        } else {
                            spec.min?.let { if (num < it) errors[spec.key] = t("不能小于 $it", "Must be ≥ $it") }
                            spec.max?.let { if (num > it) errors[spec.key] = t("不能大于 $it", "Must be ≤ $it") }
                        }
                    }
                }
                ParamSpec.TYPE_CHOICE -> {
                    if (raw.isEmpty()) {
                        if (spec.required) errors[spec.key] = t("该项必填", "Required")
                    } else if (spec.choices.isNotEmpty() && raw !in spec.choices) {
                        errors[spec.key] = t(
                            "只能是：${spec.choices.joinToString(" / ")}",
                            "Must be one of: ${spec.choices.joinToString(" / ")}"
                        )
                    }
                }
                else -> {
                    // text / material：非空校验（材料必填与否由模板语义决定，这里只拦"必填"）
                    if (raw.isEmpty() && spec.required) errors[spec.key] = t("该项必填", "Required")
                }
            }
        }
        return errors
    }

    /**
     * 组装求解请求（数值参数转 JSON 数字，其余保持字符串）。
     * 调用前应先通过 [validationErrors]。
     */
    fun buildRequest(meshLevel: String, dataDir: String): ScenarioRequest {
        val params: JsonObject = buildJsonObject {
            template.param_schema.forEach { spec ->
                val raw = values[spec.key]?.trim().orEmpty()
                when (spec.type) {
                    ParamSpec.TYPE_FLOAT -> put(spec.key, raw.toDoubleOrNull() ?: 0.0)
                    ParamSpec.TYPE_INT -> put(spec.key, raw.toIntOrNull() ?: 0)
                    else -> put(spec.key, raw)
                }
            }
        }
        return ScenarioRequest(
            template_id = template.id,
            params = params,
            mesh_level = meshLevel,
            data_dir = dataDir
        )
    }
}
