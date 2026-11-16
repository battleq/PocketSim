/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import android.content.Context

/**
 * 轻量依赖容器（AppContainer 模式，避免引入 DI 框架）。
 * MainActivity 创建一次，逐层传递给各屏。
 *
 * open + engine/parser 可覆写：Robolectric 截图测试注入回放数据用
 * （见 app/src/test 的 UiScreenshotTest），生产路径不受影响。
 */
open class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** 设置仓储（DataStore） */
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    /** 项目仓储（DataStore：已保存的仿真项目） */
    val projectRepository: ProjectRepository by lazy { ProjectRepository(appContext) }

    /** AI 技能仓储（内置 3 条 + 用户自定义，DataStore 持久化） */
    val skillRepository: SkillRepository by lazy { SkillRepository(appContext) }

    /** FEA 引擎 + 离线规则解析器（同一 Chaquopy 实例，共享 Python 启动） */
    private val chaquopy by lazy { ChaquopyEngine(appContext) }
    open val engine: FeaEngine get() = chaquopy
    open val parser: ProblemParser get() = chaquopy

    /** LLM 客户端工厂：每次调用按当前设置构建（设置页改完即时生效） */
    val aiClientProvider: suspend () -> AiClient = {
        val s = settingsRepository.current()
        LlmClientImpl(
            baseUrl = s.llmBaseUrl,
            apiKey = s.llmApiKey,
            model = s.llmModel,
            timeoutSeconds = s.timeoutSeconds,
            // 视觉模型缺省沿用主模型（用户的主模型若本身支持图片则无需单独配置）
            visionModel = s.llmVisionModel,
            // 错误提示语言跟随 App 当前语言
            language = LocaleManager.currentLanguageCode(appContext),
            // Kimi 建模通道推理强度（仅 moonshot/kimi 域名生效）
            reasoningEffort = s.llmReasoningEffort,
            // 高速建模：Kimi 域名下自动切换高速版模型（默认关，保持当前质量）
            preferHighSpeed = s.highSpeedMode
        )
    }

    /** 材料联网检索 */
    val materialLookup: MaterialLookup by lazy { MaterialLookup(aiClientProvider) }

    /** 输入框 AI 润色（只整理结构、不编数值） */
    val textPolisher: TextPolisher by lazy { TextPolisher(aiClientProvider) }
}
