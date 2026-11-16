/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

/**
 * 安全分级与来源标签的纯逻辑（JVM 可单测，不依赖 Android/Compose）。
 *
 * 阈值与 Python 侧 pocketsim/templates/__init__.py 的判定约定严格一致：
 *  - 强度类校核 sf = limit / value；
 *  - 整体 safety_factor = 各强度类校核 sf 的最小值；
 *  - sf ≥ 2.0 → 安全；1.2 ≤ sf < 2.0 → 注意；sf < 1.2 → 危险。
 * UI 侧颜色映射（安全绿/注意黄/危险红）只在本文件给出枚举，颜色值由 Compose 层决定。
 */
object SafetyLogic {

    /** 安全线：达到即认为有充足裕度 */
    const val SAFE_SF = 2.0

    /** 注意线：低于此值判危险 */
    const val CAUTION_SF = 1.2

    /** 安全分级。UNKNOWN：无安全系数（无强度校核项） */
    enum class Grade { SAFE, CAUTION, DANGER, UNKNOWN }

    /** 按整体安全系数分级（null / 非法值 → UNKNOWN） */
    fun grade(safetyFactor: Double?): Grade {
        if (safetyFactor == null || safetyFactor.isNaN() || safetyFactor <= 0.0) return Grade.UNKNOWN
        return when {
            safetyFactor >= SAFE_SF -> Grade.SAFE
            safetyFactor >= CAUTION_SF -> Grade.CAUTION
            else -> Grade.DANGER
        }
    }

    /** 兼容 Python verdict 词 → 分级（结果里已有结论词时直接映射） */
    fun gradeFromVerdict(verdict: String?): Grade = when (verdict?.trim()) {
        "安全" -> Grade.SAFE
        "注意" -> Grade.CAUTION
        "危险" -> Grade.DANGER
        else -> Grade.UNKNOWN
    }

    /**
     * 来源标签逻辑：参数显示的来源标签文案。
     * 标签文案不进 strings.xml 是刻意的——本对象要在 JVM 单元测试中断言文案，
     * 而 strings.xml 需要 Android 运行时。（其余 UI 静态文案仍集中在 strings.xml。）
     */
    fun sourceLabel(source: ParamSource): String = when (source) {
        ParamSource.BUILTIN -> "内置"
        ParamSource.AI -> "AI 提取"
        ParamSource.WEB -> "联网检索"
        ParamSource.USER -> "自填"
        ParamSource.OFFLINE -> "离线识别"
    }

    /** 材料记录 → 来源枚举（Python 材料库 source 字段） */
    fun materialSource(record: MaterialRecord): ParamSource = ParamSource.fromString(record.source)
}
