/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

/**
 * 优化建议与求解引擎之间的桥接（纯 Kotlin，可 JVM 单测）：
 * ① 把结果压成会话上下文用的摘要与对比指标；
 * ② 提供"优化后模型重算 → 验证结果"的 verifier，供 [OptimizeSession] 注入。
 */
object OptimizeRunner {

    /** 框架结果 → 对比指标（框架为杆系，无三维应力奇点问题） */
    fun metricsOf(r: FrameResult): OptimizeMetrics = OptimizeMetrics(
        safetyFactor = r.safety_factor,
        maxDispMm = r.max_disp_mm,
        signatureStressMPa = r.max_stress_MPa,
        stressExcludesSingularity = false
    )

    /** 三维实体结果 → 对比指标（优先使用已排除奇点的特征应力） */
    fun metricsOf(r: SolidGenericResult): OptimizeMetrics {
        val sig = r.metrics.max_von_mises_excl_singular_MPa
        return OptimizeMetrics(
            safetyFactor = r.metrics.safety_factor,
            maxDispMm = r.metrics.max_disp_mm,
            signatureStressMPa = sig ?: r.metrics.max_von_mises_MPa,
            stressExcludesSingularity = sig != null
        )
    }

    /** 框架结果摘要（含危险源定位：控制杆件 + 失效形态） */
    fun summaryOf(r: FrameResult): String = buildString {
        append("模型：").append(r.model_name).append('\n')
        append("节点数 ").append(r.n_nodes).append("，杆件数 ").append(r.n_members)
        .append("，支座数 ").append(r.n_supports).append('\n')
        append("最大位移 ").append(fmt(r.max_disp_mm)).append(" mm（").append(r.max_disp_desc).append("）\n")
        append("最大应力 ").append(fmt(r.max_stress_MPa)).append(" MPa")
        .append("，控制杆件 #").append(r.governing_member).append('\n')
        append("安全系数 ").append(fmt(r.safety_factor)).append("，结论：").append(r.verdict).append('\n')
        if (r.buckling_ratio > 0.0) {
            append("屈曲利用率 ").append(fmt(r.buckling_ratio))
            if (r.buckling_note.isNotBlank()) append("（").append(r.buckling_note).append("）")
            append('\n')
        }
        val weak = r.members.maxByOrNull { it.sigma_MPa }
        if (weak != null && weak.sigma_MPa > 0.0) {
            append("最危险杆件： #").append(weak.id).append("，应力 ").append(fmt(weak.sigma_MPa))
            .append(" MPa，截面 ").append(weak.section).append("，材料 ").append(weak.material)
            .append("，弯矩 ").append(fmt(weak.M_max_Nmm / 1000.0)).append(" kN·mm")
        }
    }

    /** 三维实体结果摘要 */
    fun summaryOf(r: SolidGenericResult): String = buildString {
        append("模型：").append(r.model_name).append('\n')
        append("几何：").append(r.geometry.type)
        .append("，材料 ").append(r.material).append("，网格 ").append(r.mesh.level).append('\n')
        append("最大位移 ").append(fmt(r.metrics.max_disp_mm)).append(" mm\n")
        append("最大 von Mises ").append(fmt(r.metrics.max_von_mises_MPa)).append(" MPa")
        val excl = r.metrics.max_von_mises_excl_singular_MPa
        if (excl != null) append("（排除奇点后 ").append(fmt(excl)).append(" MPa）")
        append('\n')
        append("安全系数 ").append(fmt(r.metrics.safety_factor))
        .append("，结论：").append(r.metrics.verdict).append('\n')
        r.checks.firstOrNull { !it.pass }?.let {
            append("未通过校核：").append(it.name).append("（")
            append(fmt(it.value)).append(" / 限值 ").append(fmt(it.limit)).append("）")
        }
    }

    /**
     * 构造 verifier：优化后模型重算 → 成功则返回指标；失败返回 ok=false。
     * 重算固定沿用原模型的网格档位（保证对比公平）。
     * [english]：failureReason 会展示在优化结果页（optimize_unverified_fmt），需双语。
     */
    fun verifier(
        engine: FeaEngine,
        kind: OptimizeKind,
        english: Boolean = false
    ): suspend (OptimizeSuggestion) -> VerificationResult =
        { suggestion ->
            val json = suggestion.modelJson
            if (json == null) {
                VerificationResult(ok = false, failureReason = t(english, "缺少优化模型", "The optimized model is missing"))
            } else {
                try {
                    when (kind) {
                        OptimizeKind.FRAME -> {
                            val model = AppJson.decodeFromString(FrameModel.serializer(), json)
                            val r = engine.runFrame(model)
                            if (!r.ok) {
                                VerificationResult(ok = false, failureReason = t(english, "优化模型求解失败", "The optimized model failed to solve"))
                            } else {
                                VerificationResult(ok = true, afterModelJson = json, afterMetrics = metricsOf(r))
                            }
                        }
                        OptimizeKind.SOLID_GENERIC -> {
                            val model = AppJson.decodeFromString(SolidGenericModel.serializer(), json)
                            val r = engine.runSolidGeneric(model)
                            if (!r.ok) {
                                VerificationResult(ok = false, failureReason = t(english, "优化模型求解失败", "The optimized model failed to solve"))
                            } else {
                                VerificationResult(ok = true, afterModelJson = json, afterMetrics = metricsOf(r))
                            }
                        }
                    }
                } catch (e: Exception) {
                    VerificationResult(
                        ok = false,
                        failureReason = if (english) parserErrorDetailEn(e.message)
                        else e.message ?: "优化模型无法求解"
                    )
                }
            }
        }

    private fun t(english: Boolean, zh: String, en: String): String = if (english) en else zh

    private fun fmt(v: Double): String = when {
        v >= 1000 -> String.format("%.0f", v)
        v >= 100 -> String.format("%.0f", v)
        else -> String.format("%.2f", v)
    }
}
