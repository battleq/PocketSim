/*
 * 口袋物理仿真 · 根构建脚本
 *
 * 版本组合依据（2026-10 查证）：
 *  - AGP 8.5.2：Chaquopy 17.1 支持的 AGP 区间为 7.3–9.4；Gradle 8.14 满足 AGP 8.5 的最低要求（8.7）。
 *  - Chaquopy 17.1.0（2026-10-12 发布，最新稳定版）：Python 3.10–3.15、minSdk≥24、
 *    支持 16KB 页设备与 Gradle 配置缓存；插件经 Maven Central 解析。
 *  - Kotlin 1.9.24 + Compose Compiler 1.5.14（官方兼容对照表中 1.5.14 ↔ Kotlin 1.9.24）。
 */
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    id("com.chaquo.python") version "17.1.0" apply false
}
