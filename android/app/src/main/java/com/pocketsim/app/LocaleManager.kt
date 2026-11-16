/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Locale 切换工具：内置语言走 values-zh / values-en 资源，
 * 由 [android.app.Application.attachBaseContext] 在进程启动时统一覆盖，
 * 避免运行时 setText 与资源翻译两套机制并存。
 */
object LocaleManager {

    /**
     * 解析语言偏好为 [Locale]。
     * "system" 时跟随系统当前语言；不支持的语言回退英文。
     */
    fun resolveLocale(language: String): Locale = when (language) {
        LocaleStore.LANG_ZH -> Locale.SIMPLIFIED_CHINESE
        LocaleStore.LANG_EN -> Locale.US
        else -> Locale.getDefault()
    }

    /**
     * 应用语言偏好：返回一个 wrap 后的 [Context]，其 Resources 使用目标 Locale。
     * 在 Application.attachBaseContext 与 Activity.attachBaseContext 中调用。
     */
    fun apply(context: Context): Context {
        val language = LocaleStore(context).language
        val locale = resolveLocale(language)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(android.os.LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    /** 当前生效的语言 code（"zh" / "en"），用于判断是否需要切换单位制等 */
    fun currentLanguageCode(context: Context): String {
        val language = LocaleStore(context).language
        val locale = resolveLocale(language)
        return if (locale.language == "zh") LocaleStore.LANG_ZH else LocaleStore.LANG_EN
    }
}
