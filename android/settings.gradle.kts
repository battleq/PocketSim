/*
 * 口袋物理仿真 · Android 工程设置
 *
 * 仓库布局（monorepo）：
 *   /workspace/pocketsim/
 *     ├── android/   ← 本 Gradle 工程
 *     └── python/    ← pocketsim Python 包（经 Chaquopy srcDir 引入，见 app/build.gradle.kts）
 */
pluginManagement {
    repositories {
        // 沙箱直连 dl.google.com / repo1.maven.org / plugins.gradle.org 均不可达
        // （TCP SYN 超时），全部走阿里云镜像：
        //   google   → https://maven.aliyun.com/repository/google
        //   central  → https://maven.aliyun.com/repository/public
        //   plugins  → https://maven.aliyun.com/repository/gradle-plugin
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "PocketSim"
include(":app")
