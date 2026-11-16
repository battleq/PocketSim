/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import android.content.Context

/**
 * 语言 / 单位制偏好存储。
 *
 * 为什么单独用 SharedPreferences 而不进 DataStore：
 * Application.attachBaseContext 需要在进程启动时同步读取语言选择来覆盖 Locale，
 * 而 DataStore 是异步 API，无法在 attachBaseContext 中同步取值。
 * 故语言 / 单位制两个轻量开关单独走 SharedPreferences，其余设置仍由 SettingsRepository 管理。
 *
 * language 取值：
 *   - "system"：跟随系统 Locale（默认）
 *   - "zh"：简体中文
 *   - "en"：英语（美式）
 * unitSystem 取值：
 *   - "metric"：公制（mm / N / MPa / kg / m³）
 *   - "imperial"：英制（in / lb / psi）
 */
class LocaleStore(context: Context) {

    // 注意：不能用 context.applicationContext，因为 attachBaseContext 执行时
    // Application 尚未挂载，applicationContext 可能为 null（Robolectric 测试中必现）。
    // SharedPreferences 本身是按文件名单例的，直接用 context 取值不会持有 context 引用，
    // 因此不存在内存泄漏风险。
    private val prefs = context.getSharedPreferences("pocketsim_locale", Context.MODE_PRIVATE)

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var unitSystem: String
        get() = prefs.getString(KEY_UNIT_SYSTEM, DEFAULT_UNIT_SYSTEM) ?: DEFAULT_UNIT_SYSTEM
        set(value) = prefs.edit().putString(KEY_UNIT_SYSTEM, value).apply()

    /** 用户是否手动选择过单位制（null = 从未设置，应跟随系统 locale） */
    val rawUnitSystem: String?
        get() = prefs.getString(KEY_UNIT_SYSTEM, null)

    companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_UNIT_SYSTEM = "unit_system"
        const val DEFAULT_LANGUAGE = "system"
        const val DEFAULT_UNIT_SYSTEM = "metric"

        const val LANG_SYSTEM = "system"
        const val LANG_ZH = "zh"
        const val LANG_EN = "en"

        const val UNIT_METRIC = "metric"
        const val UNIT_IMPERIAL = "imperial"
    }
}
