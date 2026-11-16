/*
 * 口袋物理仿真 · app 模块
 *
 * 技术栈：Kotlin + Jetpack Compose（Material3）+ Chaquopy 内嵌 Python 求解引擎。
 */
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.chaquo.python")
}

android {
    namespace = "com.pocketsim.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pocketsim.app"
        minSdk = 26          // Chaquopy 17 要求 ≥24；26 起可直接用自适应图标且覆盖 97%+ 设备
        targetSdk = 34
        versionCode = 83
        versionName = "1.2.35"

        // Chaquopy：Python 解释器为原生组件，必须显式声明 ABI。
        // v0.1.1 起仅保留 arm64-v8a（覆盖几乎所有真机；体积约省一半）。
        // 如需模拟器（x86_64）再加回。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Release 签名：密钥存项目外（D:\ChuangXin\70_口袋仿真\keystore\），绝不入库。
    // 密码优先从用户环境变量 KEYSTORE_PASS 读取（setx KEYSTORE_PASS "密码"）；
    // 缺失时回退到同目录 keystore.properties。生成密钥：
    //   keytool -genkeypair -v -keystore pocketsim-release.jks -alias pocketsim \
    //           -keyalg RSA -keysize 2048 -validity 10950 \
    //           -storepass:env KEYSTORE_PASS -keypass:env KEYSTORE_PASS \
    //           -dname "CN=PocketSim, OU=Dev, O=PocketSim, L=Beijing, ST=Beijing, C=CN"
    // 注意：更换密钥会导致无法覆盖升级已发布的旧版本。
    val externalKeystoreDir = File("D:/ChuangXin/70_口袋仿真/keystore")
    val keystoreProps = Properties().apply {
        val f = File(externalKeystoreDir, "keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val keystorePassFromEnv = System.getenv("KEYSTORE_PASS") ?: ""

    signingConfigs {
        create("release") {
            storeFile = File(externalKeystoreDir, keystoreProps.getProperty("storeFile") ?: "pocketsim-release.jks")
            storePassword = keystorePassFromEnv.ifEmpty { keystoreProps.getProperty("storePassword") ?: "" }
            keyAlias = keystoreProps.getProperty("keyAlias") ?: "pocketsim"
            keyPassword = keystorePassFromEnv.ifEmpty { keystoreProps.getProperty("keyPassword") ?: "" }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // R8 暂缓：Chaquopy/反射链路无真机 release 验证前不冒险，等 v0.1.1 真机回归后再开
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // AGP 8 默认不生成 BuildConfig；案例埋点需要读 BuildConfig.DEBUG 区分调试/正式包
        buildConfig = true
    }
    composeOptions {
        // 与根构建脚本中 Kotlin 1.9.24 严格配对（官方兼容对照：1.5.14 ↔ 1.9.24）
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // Robolectric 需要 Android 资源（strings.xml 等）参与 JVM 测试
            isIncludeAndroidResources = true
            // 被测代码（如 LlmClient）调用 android.util.Log 等 Android API 时，
            // 纯 JVM 测试的 android.jar 桩默认抛 "not mocked"；开启后返回默认值，不影响 Robolectric 测试
            isReturnDefaultValues = true
            all { test ->
                // 容器/沙箱等代理环境下把 https_proxy 透传给测试 JVM
                // （Robolectric 首次运行需联网下载 android-all 运行时；本机无代理时为空操作）
                System.getenv("https_proxy")?.removePrefix("http://")?.let { hostPort ->
                    val (host, port) = hostPort.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "80" } }
                    test.systemProperty("https.proxyHost", host)
                    test.systemProperty("https.proxyPort", port)
                }
                System.getenv("http_proxy")?.removePrefix("http://")?.let { hostPort ->
                    val (host, port) = hostPort.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "80" } }
                    test.systemProperty("http.proxyHost", host)
                    test.systemProperty("http.proxyPort", port)
                }
                // Maven Central 对本出口 IP 429 限流，Robolectric 的 android-all
                // 运行时改从阿里云 Central 镜像下载（Gradle 侧镜像见 settings.gradle.kts）
                test.systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public")
                test.systemProperty("robolectric.dependency.repo.id", "aliyunmaven")

                // Trae 沙箱只放行 C:\Users\a 下的特定子目录（.gradle / AppData\Local\Temp 等），
                // 不放行 C:\Users\a 根目录；而 Robolectric 的下载锁文件硬编码在 user.home 下
                // （MavenDependencyResolver.createLockFile() → new File(user.home, ".robolectric-download-lock")），
                // 在测试 JVM 里 new RandomAccessFile(..., "rw") 会被「拒绝访问」，导致全部截图用例级联失败。
                // 把测试 JVM 的 user.home 与 maven.repo.local 重定向到可写的 .gradle 子目录即可绕开。
                val roboHome = File(System.getenv("USERPROFILE"), ".gradle/robolectric-home").apply { mkdirs() }
                test.systemProperty("user.home", roboHome.absolutePath)
                test.systemProperty("maven.repo.local", File(roboHome, ".m2/repository").absolutePath)
            }
        }
    }
}

dependencies {
    // ---------- AndroidX 基础 ----------
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")

    // ---------- Compose ----------
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // ---------- 序列化 / 协程 ----------
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ---------- 网络（OpenAI 兼容 LLM 接口） ----------
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ---------- 持久化设置（DataStore） ----------
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ---------- JVM 单元测试（纯逻辑，不依赖 Android 框架） ----------
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // ---------- Robolectric + Compose UI 截图测试（电脑上模拟手机界面） ----------
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit-ktx:1.1.5")
    // ui-test-manifest：其 AAR 清单声明了 androidx.activity.ComponentActivity，
    // debugImplementation 使其合并进 debug 变体清单——createComposeRule() 在 Robolectric
    // 上启动该 Activity 时才能解析成功；仅 debug 生效，不影响 release 包。
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

/*
 * Chaquopy 配置（Kotlin DSL，官方 17.1 写法）
 *
 * - version "3.11"：App 内嵌 Python 3.11（3.10–3.15 可选；3.11 兼顾生态与 32/64 位 ABI）。
 *   注意：Chaquopy 17 起 buildPython（构建机上的 Python）必须与 App 同为大版本.小版本（即 3.11）。
 * - pip：numpy/scipy 使用 Chaquopy 官方预编译 Android wheel（构建时自动从 Chaquopy 仓库解析），
 *   scikit-fem 为纯 Python 包，直接从 PyPI 安装 wheel。
 * - sourceSets：Python 源码不在 app/src/main/python，而是 monorepo 上一级的
 *   /workspace/pocketsim/python（内含 pocketsim 包），用相对路径 srcDir 指向。
 */
chaquopy {
    // pip 代理仅在通过 -PchaquopyProxy=... 或环境变量 CHAQUOPY_PIP_PROXY
    // 指定时启用（云端沙箱传 http://127.0.0.1:18080；CI/本地直连不传，避免硬编码代理导致 pip 失败）。
    val chaquopyProxy = (project.findProperty("chaquopyProxy") as? String)
        ?.takeIf { it.isNotBlank() }
        ?: System.getenv("CHAQUOPY_PIP_PROXY")?.takeIf { it.isNotBlank() }
    defaultConfig {
        // Python 3.10：chaquo 官方仓库的 scipy wheel（1.8.1，cp310）最高仅支持 3.10，
        // 3.11+ 无 scipy Android wheel；numpy 1.26.2 / scikit-fem 均兼容。
        // 注意：Chaquopy 17 起 buildPython（构建机上的 Python）必须与 App 同为大版本.小版本（即 3.10）。
        version = "3.10"
        pip {
            // - 保留 Chaquopy 默认索引（含 numpy/scipy 的 Android wheel），纯 Python 包
            //   （reportlab/scikit-fem）走 PyPI 官方源。
            // - 不追加清华镜像：其 scikit-fem wheel 文件返回 403，且 --extra-index-url
            //   不保证优先级，会把官方源解析结果顶掉导致构建失败。
            // - 代理按需启用（见文件头 chaquopyProxy 说明，pip 子进程不继承 JVM 代理）
            val pipOpts = buildList {
                if (chaquopyProxy != null) {
                    add("--proxy"); add(chaquopyProxy)
                }
            }.toTypedArray()
            options(*pipOpts)
            install("numpy")
            install("scipy")
            install("scikit-fem")
            install("reportlab")
        }
    }
    sourceSets {
        getByName("main") {
            // 相对 app 模块目录：../../python → /workspace/pocketsim/python
            // 该目录下的 pocketsim/ 包会被打进 APK，import pocketsim.bridge 即可调用
            srcDir("../../python")
        }
    }
}
