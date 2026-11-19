/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */
package com.pocketsim.app

/**
 * 开源依赖声明 —— App 内 "设置 → 开源许可" 页面数据源。
 *
 * 合规说明：
 *  口袋仿真 Android App 闭源商用，但在运行时动态链接以下开源库，
 *  各自遵守其上游 License。本页面旨在透明披露合规义务，
 *  尤其是 LGPL-2.1 组件（scipy 传递依赖 libquadmath）的合规声明。
 *
 * 所有依赖均为**动态链接**（Python import 或 JVM 类库），
 * 非静态编译进自有二进制，因此 LGPL 不要求闭源码开源。
 */
object OpenSourceLicenses {

    data class Dep(
        val name: String,
        val version: String,
        val license: String,     // 短名称，如 "MIT" / "BSD-3-Clause" / "Apache-2.0" / "LGPL-2.1"
        val isCopyleft: Boolean, // copyleft 许可证（GPL/LGPL/AGPL）需特别标注
        val scope: String,       // 用途简述
        val note: String = "",   // 合规备注
        val licenseAsset: String? = null // assets/licenses/ 下的许可证全文
    )

    /** 第三方依赖清单（按 License 类型分组，copyleft 组件置顶醒目） */
    val all: List<Dep> = listOf(

        // ----- Copyleft 组件（合规重点）-----
        Dep(
            name = "libquadmath (GCC)",
            version = "libquadmath.so",
            license = "LGPL-2.1-or-later",
            isCopyleft = true,
            scope = "scipy 传递依赖 · 动态链接 .so",
            note = "GCC 四倍精度数学库。scipy wheel 动态链接此 .so 文件。" +
                   "LGPL 合规义务：本 App 闭源但在设置页声明此组件，" +
                   "并允许技术用户替换 .so 文件。非静态链接，故不触发 GPL 传染性。",
            licenseAsset = "licenses/LGPL-2.1.txt"
        ),
        Dep(
            name = "libgfortran (GCC)",
            version = "libgfortran.so",
            license = "GPL-3.0-or-later + GCC Runtime Library Exception",
            isCopyleft = true,
            scope = "scipy 传递依赖 · Fortran 运行时",
            note = "GCC 的 Fortran 运行时。GPLv3 第 7 条的 GCC 运行时例外允许与闭源程序链接分发，" +
                   "义务是随附 GPL-3.0 全文、例外条款与版权声明；本页所附文本已含三者。",
            licenseAsset = "licenses/GPL-3.0-libgfortran.txt"
        ),

        // ----- 宽松许可组件 -----
        Dep(
            name = "scipy",
            version = "1.8.1 (Chaquopy Android wheel)",
            license = "BSD-3-Clause",
            isCopyleft = false,
            scope = "稀疏矩阵求解 / 线性代数（solver.py / frame.py）",
            note = "项目整体 BSD 许可，二进制 wheel 含动态链接的 libquadmath",
            licenseAsset = "licenses/BSD-3-Clause-scipy.txt"
        ),
        Dep(
            name = "scikit-fem",
            version = "12.0.2",
            license = "BSD-3-Clause",
            isCopyleft = false,
            scope = "2D 线弹性有限元单元库（solver.py）",
            licenseAsset = "licenses/BSD-3-Clause-scikit-fem.txt"
        ),
        Dep(
            name = "numpy",
            version = "1.23.3 (Chaquopy Android wheel)",
            license = "BSD-3-Clause",
            isCopyleft = false,
            scope = "数组计算基础（全引擎依赖）",
            licenseAsset = "licenses/BSD-3-Clause-numpy.txt"
        ),

        // ----- Python 运行时传递依赖（均由 pip 打包进 APK）-----
        Dep(
            name = "reportlab",
            version = "5.1.0",
            license = "BSD (ReportLab)",
            isCopyleft = false,
            scope = "PDF 报告生成（python/pdf_report）",
            note = "ReportLab 自有 BSD 变体，再分发须保留其版权声明",
            licenseAsset = "licenses/BSD-ReportLab.txt"
        ),
        Dep(
            name = "Pillow",
            version = "11.0.0",
            license = "HPND",
            isCopyleft = false,
            scope = "图像读写 / PDF 内嵌图片（reportlab 依赖）",
            licenseAsset = "licenses/HPND-Pillow.txt"
        ),
        Dep(
            name = "charset-normalizer",
            version = "3.5.2",
            license = "MIT",
            isCopyleft = false,
            scope = "reportlab 传递依赖 · 字符编码探测",
            licenseAsset = "licenses/MIT-charset-normalizer.txt"
        ),
        Dep(
            name = "FreeType",
            version = "2.9.1 (Chaquopy wheel)",
            license = "FTL",
            isCopyleft = false,
            scope = "Pillow 传递依赖 · 字体渲染",
            licenseAsset = "licenses/FTL-freetype.txt"
        ),
        Dep(
            name = "libjpeg",
            version = "1.5.3 (Chaquopy wheel)",
            license = "IJG",
            isCopyleft = false,
            scope = "Pillow 传递依赖 · JPEG 编解码",
            licenseAsset = "licenses/IJG-libjpeg.txt"
        ),
        Dep(
            name = "libc++ (LLVM)",
            version = "18.0 (Chaquopy wheel)",
            license = "Apache-2.0 WITH LLVM-exception",
            isCopyleft = false,
            scope = "CPython / 原生扩展的 C++ 运行库",
            licenseAsset = "licenses/Apache-2.0-LLVM-exception.txt"
        ),
        Dep(
            name = "OpenBLAS",
            version = "0.2.20 (Chaquopy wheel)",
            license = "BSD-3-Clause",
            isCopyleft = false,
            scope = "numpy / scipy 线性代数后端",
            licenseAsset = "licenses/BSD-3-Clause-openblas.txt"
        ),
        Dep(
            name = "Chaquopy",
            version = "17.1.0",
            license = "MIT",
            isCopyleft = false,
            scope = "Python SDK for Android · 内嵌 CPython 解释器",
            licenseAsset = "licenses/MIT-chaquopy.txt"
        ),
        Dep(
            name = "Jetpack Compose (Material3)",
            version = "BOM 2024.06.00",
            license = "Apache-2.0",
            isCopyleft = false,
            scope = "UI 框架",
            licenseAsset = "licenses/Apache-2.0.txt"
        ),
        Dep(
            name = "AndroidX",
            version = "1.7.0 / Lifecycle 2.8.0",
            license = "Apache-2.0",
            isCopyleft = false,
            scope = "Android 支持库",
            licenseAsset = "licenses/Apache-2.0.txt"
        ),
        Dep(
            name = "OkHttp",
            version = "4.12.0",
            license = "Apache-2.0",
            isCopyleft = false,
            scope = "OpenAI 兼容 LLM HTTP 客户端（LlmClient.kt）",
            licenseAsset = "licenses/Apache-2.0.txt"
        ),
        Dep(
            name = "kotlinx.serialization",
            version = "1.6.3",
            license = "Apache-2.0",
            isCopyleft = false,
            scope = "JSON 序列化（Kotlin ↔ Python 契约）",
            licenseAsset = "licenses/Apache-2.0.txt"
        ),
        Dep(
            name = "DataStore Preferences",
            version = "1.1.1",
            license = "Apache-2.0",
            isCopyleft = false,
            scope = "LLM 设置持久化（SettingsRepository.kt）",
            licenseAsset = "licenses/Apache-2.0.txt"
        ),
        Dep(
            name = "Kotlin (stdlib / coroutines)",
            version = "1.9.24",
            license = "Apache-2.0",
            isCopyleft = false,
            scope = "JVM 语言标准库与异步协程",
            licenseAsset = "licenses/Apache-2.0.txt"
        ),
        Dep(
            name = "Gradle AGP",
            version = "8.5.2",
            license = "Apache-2.0",
            isCopyleft = false,
            scope = "Android 构建工具链（构建时依赖，不随 APK 分发）",
            licenseAsset = "licenses/Apache-2.0.txt"
        ),
    )

    /** PocketSim 自有代码的 License 声明（分层授权，详见仓库 NOTICE.md） */
    val selfLicenses: List<Pair<String, String>> = listOf(
        "pocketsim/python/（有限元求解引擎）" to "AGPL-3.0-or-later — 开源，需履行源码开放义务；商业授权见 LICENSE-COMMERCIAL",
        "pocketsim/android/app/（Kotlin/Compose 前端 + AI 交互逻辑）" to "专有代码 — All Rights Reserved",
    )
}
