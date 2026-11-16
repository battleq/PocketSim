# 口袋物理仿真 · Android 端

> [English](README.md) · 中文

把生活中的力学问题变成一次 30 秒的有限元仿真：
Kotlin + Jetpack Compose（Material3）负责 UI，**Chaquopy 内嵌 Python 3.10 + scikit-fem**
负责离线求解，可选接入 OpenAI 兼容 LLM 做"自然语言 → 参数"的理解与追问。
两种计算模式：**场景模板**（钉子挂重 / 悬臂搁板 / 简支层板，参数化模板）与
**通用结构**（v0.2.0 新增：任意杆件/梁结构由 AI 直接建模为 2D 框架模型，
预览确认后本地求解，适合书架、桌子、晾衣架等）。
v0.3.0 新增：**拍照建模**（附 1–3 张结构照片，视觉模型识别结构建模，看不清的信息
以文字追问确认）与**结果追问**（就计算结果提问答疑，或让 AI 修改荷载/位置/材料后
确认重算）；v0.3.1 起**通用结构为默认模式，照片输入前移到首页**——描述问题的同时
即可附照片，照片与文字一起构成完整输入。

> ⚠️ **免责声明**：本 App 的仿真结果仅供日常生活参考，不可用于任何专业工程用途
> （详见 App 内置免责声明全文与结果页常驻提示，分享图自动附加"仅供参考"水印）。

---

## 技术栈与版本依据

| 组件 | 版本 | 说明 |
|---|---|---|
| AGP | 8.5.2 | 与 Gradle 8.14 兼容；Chaquopy 17 支持 AGP 7.3–9.4 |
| Gradle | 8.14 | 见 `gradle/wrapper/gradle-wrapper.properties` |
| Kotlin | 1.9.24 | Compose Compiler 1.5.14 严格配对 1.9.24（官方兼容对照表） |
| Compose BOM | 2024.06.00 | Material3 |
| Chaquopy | **17.1.0** | 2026-10 发布的最新稳定版，免费开源（Apache-2.0），经 Maven Central 解析 |
| kotlinx.serialization | 1.6.3 | Kotlin ↔ Python JSON 契约 |
| OkHttp | 4.12.0 | OpenAI 兼容 `/chat/completions` |
| DataStore Preferences | 1.1.1 | 设置持久化 |

**Chaquopy 17.1.0 版本依据**（相对常见认知"16.x"的更新）：
- 官方文档：https://chaquo.com/chaquopy/doc/17.1/current/index.html
- 支持 AGP 7.3–9.4（覆盖本工程 8.5.2）、Python 3.10–3.15（本工程选 3.10）、minSdk ≥ 24（本工程 26）；
- 支持 16KB 页设备与 Gradle 配置缓存；
- Kotlin DSL 官方写法：`chaquopy { defaultConfig { version / pip { install(...) } }; sourceSets { getByName("main") { srcDir(...) } } }`。

---

## 工程结构

```
pocketsim/android/
├── settings.gradle.kts            # 仓库与模块声明
├── build.gradle.kts               # 根插件版本（AGP/Kotlin/Chaquopy）
├── gradle.properties              # JVM 参数、AndroidX 开关
├── gradle/wrapper/
│   └── gradle-wrapper.properties  # Gradle 8.14 分发地址（wrapper 脚本见下文生成）
├── docs/
│   └── solver-decision.md         # 求解器选型决策记录（路线 A 定案）
├── README.md
└── app/
    ├── build.gradle.kts           # Chaquopy 配置 + 依赖（本工程核心）
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml       # INTERNET 权限、FileProvider（分享图）
        │   ├── java/com/pocketsim/app/
        │   │   ├── MainActivity.kt        # 单 Activity + 自研路由栈
        │   │   ├── Models.kt             # Kotlin ↔ Python JSON 契约（@Serializable）
        │   │   ├── PythonBridge.kt        # Chaquopy 封装（FeaEngine 接口）
        │   │   ├── AppContainer.kt       # 手工依赖容器
        │   │   ├── SettingsRepository.kt # DataStore 设置
        │   │   ├── LlmClient.kt          # OpenAI 兼容 HTTP 客户端（AiClient 接口；
        │   │   │                         #   v0.3.0 多模态消息 + 带图自动切换视觉模型）
        │   │   ├── AiParser.kt           # LLM 输出 → 结构化解析（模板/框架两模式，含一次重试）
        │   │   ├── ChatSession.kt        # 对话理解状态机（纯 Kotlin 可单测；v0.3.0 支持带图发送）
        │   │   ├── FrameFollowUpSession.kt # 结果追问状态机（v0.3.0：问答/改条件/追问三分支）
        │   │   ├── FollowUpLogic.kt      # 缺参追问策略（≤2 项/轮，≤2 轮）
        │   │   ├── MaterialLookup.kt     # AI 联网检索材料参数 → 入库
        │   │   ├── ParamForm.kt          # 参数确认页表单状态（纯 Kotlin 可单测）
        │   │   ├── SafetyLogic.kt        # 安全分级/来源标签（纯 Kotlin 可单测）
        │   │   └── ui/                   # Home/Chat/ParamConfirm/Result/Settings/ManualMode
        │   │                             # + FramePreview/FrameResult/FrameFollowUp（v0.3.0）
        │   │                             # + components/（聊天气泡共用组件）
        │   └── res/                      # 中文文案 strings.xml、主题、自适应图标
        └── test/java/com/pocketsim/app/  # JVM 单元测试（不依赖设备）
            ├── AiParserTest.kt           # 含框架模式 parseFrame/validateFrameModel 用例
            ├── ModelsTest.kt
            ├── SafetyLogicTest.kt
            ├── ParserFlowTest.kt
            ├── ParamFlowTest.kt          # FeaEngine fake：参数流 → 求解 → 结果解读 + ChatSession 框架模式
            ├── FollowUpSessionTest.kt    # v0.3.0：多模态消息形状、带图发送、结果追问三分支
            └── UiScreenshotTest.kt       # Robolectric 真渲染截图（回放 Python 真实求解结果）
```

**Python 源码不在本目录**：`app/build.gradle.kts` 中
`chaquopy { sourceSets { getByName("main") { srcDir("../../python") } } }`
指向仓库上一级的 `pocketsim/python/`（内含 `pocketsim` 包，由 Python 侧任务交付）。
该目录就位前 APK 可构建，但运行时进入求解会提示引擎错误。

---

## 构建前提

| 依赖 | 要求 | 用途 |
|---|---|---|
| Android Studio | Koala（2024.1.1）或更新 | 推荐 IDE 方式构建 |
| JDK | 17 | AGP 8.x 要求 |
| Android SDK | 34（compileSdk/targetSdk） | SDK Platform 34 + Build-Tools |
| 本机 Python | **3.10**（大版本.小版本必须与 App 内嵌一致） | Chaquopy 17 起 `buildPython` 强制同版本；用于构建期 pip 解析。选 3.10 的原因：chaquo 官方仓库的 scipy wheel（1.8.1）最高仅支持 cp310 |
| 真机（建议） | arm64-v8a，**8GB+ RAM** | 10 万自由度求解目标 ≤60s；模拟器（x86_64）可跑但慢 |

### 生成 Gradle Wrapper

仓库只提交了 `gradle-wrapper.properties`（wrapper 脚本与 jar 为生成物）。首次构建前二选一：

```bash
# 方式一：本机已装任意 Gradle 8.x
gradle wrapper --gradle-version 8.14

# 方式二：用 Android Studio 打开 android/ 目录，Sync 时按提示生成
```

---

## 构建步骤

```bash
# 1. 调试包
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 2. 安装到已连接设备
./gradlew installDebug

# 3. JVM 单元测试（不需要设备/SDK 34 之外的任何东西）
./gradlew testDebugUnitTest
```

### 首次构建会发生什么（Chaquopy）

首次（或 clean 后）构建时 Chaquopy 会：

1. 下载 Android 版 **Python 3.10 运行时**（arm64-v8a + x86_64 两个 ABI，各约 30–60 MB）；
2. 联网解析并下载 pip 依赖：**numpy 1.26.2 / scipy 1.8.1**（Chaquopy 官方预编译 Android wheel，cp310）、
   **scikit-fem**（纯 Python 包，PyPI wheel）；
3. 把 `../../python` 下的 `pocketsim` 包打进 APK 的 assets。

整体约需 **10–30 分钟、数百 MB 网络与磁盘流量**，属一次性成本；后续增量构建走缓存。
若在无网环境构建，请先在有网机器上完成一次并把 Gradle 缓存带过去。

---

## 电脑端 UI 模拟验证（无需模拟器/真机）

`UiScreenshotTest` 用 **Robolectric + Compose 在 JVM 上渲染真实 UI 并截图**，
回放数据来自 `app/src/test/resources/snapshot/*.json`——由 Python 引擎
（`pocketsim.bridge`）离线生成的真实求解结果，因此截图 = 真实引擎输出 × 真实 UI 渲染：

```bash
./gradlew :app:testDebugUnitTest
# 产物：app/build/robo-screenshots/*.png（1080×2340 规格）
# 构建目录会被 clean 清掉，工程已同步一份到 ../screenshots/
```

覆盖页面：主页 / 手动模式 / 参数确认（钉子挂 3KG 原例预填）/ 结果页（云图+指标卡）/
结果页展开通俗分析 / 悬臂搁板第二场景 / **框架模型预览（线框图+支座/载荷符号）** /
**框架结果页（指标卡+应力着色变形图）** / **框架结果页滚动后（杆件受力明细）** /
**框架结果追问页（v0.3.0：结果摘要卡+引导语气泡+输入行）**。
重新生成回放数据：

```bash
cd ../python && python -m pocketsim.cli demo-snapshots   # 或参考 UiScreenshotTest 注释手动生成
```

> 想要交互式预览：用 Android Studio 打开本工程，任选 `@Composable` 函数（如
> `ResultScreen`）点击行号旁的 Split/Design 即可静态预览；连接模拟器可完整交互。

## 部署到真机

```bash
# 方式一：数据线 + adb（需在手机上开启"开发者选项 → USB 调试"）
./gradlew installDebug          # 或 adb install app/build/outputs/apk/debug/app-debug.apk

# 方式二：直接传 APK
# debug 包：app/build/outputs/apk/debug/app-debug.apk（双 ABI，约 92MB，调试用）
# release 包：app/build/outputs/apk/release/app-release.apk（仅 arm64，约 54MB，正式签名）
# 发到手机（微信/网盘/局域网均可），文件管理器点击安装，
# 系统提示"未知来源"时选择允许（HarmonyOS/ColorOS 等路径略有差异）。
```

要求：Android 8.0+（minSdk 26）、arm64-v8a（覆盖当前主流真机）、约 500MB 剩余空间
（App 安装后约 300–400MB，含 Python 运行时与科学计算库）。首次启动会解压 Python
环境，冷启动约 3–8 秒属正常。

> **debug → release 换装注意**：两者签名不同，安装 release 前需先卸载已装的 debug
> 版（设置里的 LLM Key 等需重新填写一次）；今后 release 版本之间升级可直接覆盖安装。
> release 签名密钥只存本地 `keystore/`（已 gitignore，**绝不入库**）；克隆本仓库后
> 出 release 包需自备密钥，见 `app/build.gradle.kts` 签名配置处的说明。

### 真机验证清单（装好后按此走一遍）

1. **首启**：打开 App，允许存储权限（如提示）；等待 Python 环境解压完成（首启 3–8 秒）。
2. **原例复现**：主页输入"在墙上打进一个钉子，钉子上悬挂3KG重物，墙和钉子是否能够承重"，
   未配 AI 时点"离线智能预填"或"手动选模板 → 墙上钉挂重"，确认参数页自动预填
   挂重 3.0kg（与电脑端截图 03 一致），点"开始计算"。
3. **结果核对**：指标应与电脑端一致——最大变形 ≈ 0.06 mm、最大应力 ≈ 103 MPa、
   安全系数 ≈ 2.29（安全）；云图可切换"等效应力/位移"，展开"AI 分析过程"看通俗解读。
4. **性能观察**：从点"开始计算"到出结果应 < 10 秒（网格 medium 档）；手机发烫明显或
   超过 60 秒请记录机型与耗时反馈。
5. **可选**：设置页（右上角齿轮）配置 OpenAI 兼容 LLM 后，回到主页直接输入自然语言，
   走 AI 解析 → 参数确认 → 计算全链路。
6. **IME 修复验证（v0.2.0）**：AI 对话页点输入框用拼音输入法打字，输入框应随键盘
   上移不被遮挡（参数确认/设置/主页输入框同理）。
7. **通用结构模式（v0.2.0）**：主页默认即"通用结构"模式，输入如"用松木 30×40 木方做一个
   靠墙的单立柱双层书架，宽 400、高 1500，下层放一箱 15 公斤书，上层摆满约 25 公斤书"，
   AI 建模后进入预览页：核对线框图（杆件/支座/载荷符号与你的描述一致）→ 开始计算 →
   结果页应显示变形图（可拖放大滑杆）、安全分级与杆件受力明细。
8. **拍照建模（v0.3.0 起，v0.3.1 输入前移首页）**：首页默认已是"通用结构"模式——
   输入描述的同时点"添加结构照片"选 1–3 张（缩略图可点 × 删除），点"AI 理解"后
   照片与文字一起发出；AI 应按照片建模，看不清的尺寸/截面/材料以文字追问确认。
   进入对话页后仍可点输入框左侧"+"补充照片。（设置页需配置支持视觉的模型，
   如火山方舟 `doubao-seed-2.1-pro`；不填视觉模型则沿用主模型。）
9. **结果追问（v0.3.0）**：框架结果页底部点"继续追问 / 修改重算"→
   ① 提问"安全系数是什么意思"应得通俗解答；② 说"重量加到 30 公斤"或"换成钢材"，
   AI 改好模型后自动跳预览确认页，确认后重算出新结果。

---

## LLM Key 配置入口

App **设置页**（首页右上角齿轮）内配置，DataStore 持久化，无需改代码：

- **API Base URL**：OpenAI 兼容服务地址（App 会拼接 `/chat/completions`），
  如 `https://api.example.com/v1`；
- **API Key**：Bearer 鉴权；
- **模型名**：如 `gpt-4o-mini` 等；
- **视觉模型**（v0.3.0，可选）：发送照片时使用的多模态模型名；留空则沿用主模型。
  火山方舟 `doubao-seed-2.1-pro` 等视觉模型可直接填模型 ID；
- 默认网格档位、请求超时秒数。

未配置/无网络时，AI 对话页会提示并引导走**手动选模板**模式（功能完整，仅无自然语言理解）。

---

## Python 侧接口契约

Kotlin 与 Python 之间统一以 **JSON 字符串**跨边界（避免动态代理的类型坑），
字段定义见 `app/src/main/java/com/pocketsim/app/Models.kt`（`@Serializable` + `@SerialName`）。

| Python 入口 | 签名 | 返回 |
|---|---|---|
| `pocketsim.bridge.list_templates` | `() -> str` | `[TemplateInfo, ...]` JSON 数组 |
| `pocketsim.bridge.run_scenario` | `(request_json: str) -> str` | `FeaResult` JSON |
| `pocketsim.bridge.run_frame` | `(model_json: str, data_dir: str) -> str` | `FrameResult` JSON（2D 梁杆框架） |
| `pocketsim.bridge.list_materials` | `(data_dir: str) -> str` | `[MaterialRecord, ...]` JSON 数组 |
| `pocketsim.bridge.upsert_material` | `(record_json: str, data_dir: str) -> str` | `MaterialRecord` JSON |
| `pocketsim.bridge.export_frame_pdf` | `(model_json: str, result_json: str, filepath: str, data_dir: str) -> str` | 导出 PDF 报告，返回文件路径 |
| `pocketsim.parser.parse_problem` | `(text: str) -> dict` | 解析结果 dict（Chaquopy 侧经 `json.dumps` 跨边界） |

`pocketsim.bridge` 为 Android 专用适配层（内部调用 engine / materials / templates）：
字段名与单位已按 Kotlin 模型映射（如 `max_von_mises_mpa`→`max_stress_MPa`、渲染数据 m→mm），
求解失败统一以 `ValueError` 抛出（Kotlin 侧转 `FeaEngineException`）。

`run_scenario` 请求（Kotlin `ScenarioRequest` 序列化）：

```json
{
  "template_id": "cantilever_shelf",
  "params": { "length_mm": 400.0, "load_kg": 3.0, "material_name": "松木" },
  "mesh_level": "medium",
  "data_dir": "/data/user/0/com.pocketsim.app/files"
}
```

`FeaResult` 关键字段：`metrics`（`max_deflection_mm` / `max_stress_MPa` / `safety_factor` /
`verdict`）、`extra_checks`、`assumptions`、`render`（`nodes`/`tris`/`values`/`disp` 云图数据）、
`timing`、`mesh`（含 `adjusted` 降级/加密标记）、`theory_check`（理论解自检，误差目标 ≤5%）、
`analogy`、`narrative`（通俗"分析过程"段落列表，离线生成）、`params_effective`。
安全分级阈值与 Python 侧严格一致：
**sf ≥ 2.0 安全；1.2 ≤ sf < 2.0 注意；sf < 1.2 危险**。

`run_frame`（通用结构模式）请求为 Kotlin `FrameModel` 序列化（坐标单位 mm，x 右 y 上）：

```json
{
  "name": "单立柱双层书架",
  "nodes":    [{"id": 1, "x": 0, "y": 0}],
  "members":  [{"id": 1, "i": 1, "j": 2, "width_b": 30, "depth_h": 40, "material": "松木"}],
  "supports": [{"node": 1, "type": "fixed"}],
  "loads":    [{"type": "force", "node": 2, "fx": 0, "fy": -294},
               {"type": "udl", "member": 1, "qy": -0.6125}]
}
```

支座 `fixed`（嵌固）/`pinned`（铰接）/`roller`（滚轴）；集中力单位 N（向下为负 fy），
均布载荷 N/mm（向下为负 qy）；温度变化 `thermal`（°C，升温为正 delta_temp，基准 20°C，
如"钢管晒到 60°C"→ delta_temp=40）。`FrameResult` 关键字段：`max_disp_mm`（带 `max_disp_desc`
位置描述）、`max_stress_MPa` 与 `governing_member`（控制杆）、`safety_factor`/`verdict`
（同上分级）、`buckling_ratio`/`buckling_note`（欧拉屈曲利用率 ≥50% 提示、≥100% 危险）、
`thermal`（热分析分区：`has_thermal`/`max_delta_temp`/`over_temp`/`note`/`by_member`，
杆件温度超材料耐温时 verdict="危险"）、`members`（每杆件 N/M/应力/利用率/`delta_temp`）、
`polylines`（变形折线：原始坐标+位移分量，UI 自行放大）、`narrative`（通俗分析段落）、`solve_ms`。
求解前由 Python 校验引用完整性与机构/约束不足，错误以中文 `ValueError` 抛出。
热分析精度边界：每杆均匀温度，不含导热梯度/瞬态/热冲击。

`parse_problem` 返回契约（离线规则解析，无需网络与 LLM Key）：

```json
{ "ok": true, "template_id": "cantilever_shelf",
  "params": { "length_mm": 400 }, "missing": ["load_kg"], "confidence": 0.86 }
{ "ok": false, "reason": "..." }
```

---

## 离线预填与通俗分析

两个**完全离线**（无网络、无 LLM Key）的辅助能力，均由 Python 侧在 Chaquopy 内完成：

1. **通俗分析（`narrative`）**：`run_scenario` 返回顶层新增 `narrative`，为确定性生成的
   通俗"分析过程"段落列表。结果页"AI 分析过程"卡片优先逐段渲染它；为空时回退端侧
   规则模板生成的步骤文案（旧版 Python 返回无该字段同样兼容）。内容基于计算结果
   规则化陈述，**不是**生成式 AI 输出，因此离线可用、结果稳定可复现。

2. **离线智能预填（`pocketsim.parser.parse_problem`）**：未配置 AI（主页展示按钮）或
   AI 接口不可用（对话页错误条展示按钮）时，用规则解析器从用户描述中识别模板与参数，
   直接跳参数确认页：已抽取参数的来源标签为"离线识别"（`ParamSource.OFFLINE`，诚实
   区分于 AI 提取），解析器未获取到的参数标"待补充"，由用户补充或确认后计算。
   识别失败（`ok=false` / 未知模板 / 引擎异常）提示"未能识别，请手动选择模板"并留在当前页。

**规则预填的局限**：解析器基于关键词与数值抽取的离线规则，只覆盖内置模板的常见中文
表述；识别不了、参数不全时不会臆测——要么提示手动选模板，要么在参数确认页把缺失项
标为待补充。复杂表述仍建议配置 AI 接口（对话理解）或使用手动模式。

---

## 已知限制（v1）

1. **物理范围**：线弹性、2D、静力、小变形。场景模板模式覆盖 3 个模板（悬臂搁板 /
   墙上钉挂重 / 简支层板）；通用结构模式覆盖平面梁杆框架（≤25 节点 / 40 杆件的常见
   生活结构），AI 建模质量依赖提示词约束，异常模型靠预览页人工确认 + Python 校验
   双重兜底。3D 与非线性属后续阶段（演进路线见 `docs/solver-decision.zh-CN.md` 路线 B）。
2. **scipy 依赖注意项**：Chaquopy 对 scipy 提供官方预编译 wheel，但**并非每个版本组合都有**。
   若构建期 pip 解析失败，按序尝试：
   - a) 在 `app/build.gradle.kts` 的 `pip {}` 中固定 Chaquopy 有 wheel 的版本
     （可用版本清单见 https://chaquo.com/pypi-licenses/ ）；
   - b) 本地 wheel 方式：`pip { install("scipy", "-r", "requirements.txt") }` 或
     `install("/path/to/scipy-xxx-cp311-android.whl")`；
   - c) 切换纯 numpy 求解器分支（Python 侧以 numpy 稠密分解替代 scipy.sparse 求解，
     2D 规模下性能可接受）。
3. **R8/混淆**：v1 骨架阶段关闭（`isMinifyEnabled = false`），规则文件已备好
   （keep `com.chaquo.**`、kotlinx.serialization），后续开启前需回归验证。
4. **Python 求解不可中途取消**（C 层阻塞），超时保护由 Python 侧网格自动降级实现，
   Kotlin 侧仅取消等待。
5. **结果仅供生活参考**：材料参数为工程代表值（非保证值），边界条件为教学级简化。

---

## 单元测试

```bash
./gradlew testDebugUnitTest
```

七个纯 JVM 测试类（不依赖 Android 框架与设备）：

- `AiParserTest`：LLM 输出解析（合法/非法 JSON、schema 校验、重试路径）+ 追问裁剪 ≤2 +
  框架模式（`parseFrame` 围栏/字段/ready 校验、`validateFrameModel` 全分支、重试路径）；
- `ModelsTest`：`FeaResult`（含 `narrative`）等模型的 JSON 反序列化（含默认值容错），
  以及 `parse_problem` 成功/失败契约；
- `SafetyLogicTest`：安全分级边界值与来源标签（含"离线识别"）；
- `ParamFlowTest`：`FeaEngine` fake 实现驱动 ParamConfirm → 求解 → 结果解读全链路，
  `ChatSession` 缺参追问 → 补齐 → 就绪的状态机流程，以及框架模式（追问 → 建模就绪 →
  `onFrameReady` 回调、轮数用尽引导）；
- `ParserFlowTest`：`ProblemParser` fake 实现驱动离线预填全链路——识别成功的模板命中、
  参数回填、`OFFLINE` 来源标记、missing 过滤与"待补充"标记，未识别 / 未知模板 /
  引擎异常返回 null 的失败路径；
- `FollowUpSessionTest`（v0.3.0）：多模态请求体形状（无图纯文本 / 带图 text+image_url
  数组）、`ChatSession` 带图发送与只发照片、`FrameFollowUpSession` 三分支
  （纯问答 / 改条件输出完整新模型回调 / 意图不明追问）+ JSON 校验失败引导 +
  自由文本兜底 + 接口异常 fatalError；
- `UiScreenshotTest`：Robolectric + Compose JVM 真渲染 10 张 1080×2340 截图（回放
  Python 引擎离线生成的真实求解结果，含框架模式预览/结果/杆件明细/追问页四张；
  结果页为 LazyColumn，杆件受力明细需 scrollToNode 滚动后才能截到）。
