/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import android.app.Application
import android.content.Context

/**
 * 自定义 Application：在进程启动时根据 [LocaleStore] 中的语言偏好覆盖 Locale，
 * 使所有 values-en / values-zh 资源在 Activity 创建前就已就位。
 */
class PocketSimApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.apply(base))
    }

    override fun onCreate() {
        super.onCreate()
        // 单位制偏好须在 UI 创建前就位：结果页/预览页的换算依赖 Units.system
        Units.init(this)
    }
}
