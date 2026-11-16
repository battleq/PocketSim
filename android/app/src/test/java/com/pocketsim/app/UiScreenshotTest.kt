package com.pocketsim.app

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.pocketsim.app.ui.FlowPreviewScreen
import com.pocketsim.app.ui.FlowResultScreen
import com.pocketsim.app.ui.FrameFollowUpScreen
import com.pocketsim.app.ui.FramePreviewScreen
import com.pocketsim.app.ui.FrameResultScreen
import com.pocketsim.app.ui.HomeScreen
import com.pocketsim.app.ui.LauncherScreen
import com.pocketsim.app.ui.MaterialLibraryScreen
import com.pocketsim.app.ui.SeepagePreviewScreen
import com.pocketsim.app.ui.SeepageResultScreen
import com.pocketsim.app.ui.SkillManagerContent
import com.pocketsim.app.ui.SolidPreviewScreen
import com.pocketsim.app.ui.SolidResultScreen
import com.pocketsim.app.ui.TransientPreviewScreen
import com.pocketsim.app.ui.TransientResultScreen
import com.pocketsim.app.ui.theme.PocketSimTheme
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 手机 UI 界面的电脑端模拟验证（Robolectric + Compose，无需模拟器/真机）。
 *
 * 框架模型/结果来自 app/src/test/resources/snapshot 目录下由 pocketsim Python 引擎
 * （bridge.run_frame）离线生成的真实求解结果，因此截图内容 = 真实引擎输出 × 真实
 * Compose UI 渲染，等价于真机上的显示效果。
 *
 * 运行：./gradlew :app:testDebugUnitTest
 * 产物：app/build/robo-screenshots 目录下的 png（1080×2340 规格，w411dp-h891dp-420dpi）
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
class UiScreenshotTest {

    @get:Rule
    val composeRule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule()

    @org.junit.Before
    fun forceChineseLocale() {
        // 截图用例断言的是中文文案；i18n 框架下 Robolectric 默认 en_US 会加载 values-en，
        // 导致 awaitText("新建项目") 等超时。这里显式将语言偏好置为中文。
        java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE)
        LocaleStore(ApplicationProvider.getApplicationContext()).language = LocaleStore.LANG_ZH
    }

    // ---------------------------------------------------------------- 数据回放
    private fun readText(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("snapshot/$name")!!.readBytes().decodeToString()

    private val materials by lazy {
        AppJson.decodeFromString(ListSerializer(MaterialRecord.serializer()), readText("materials.json"))
    }

    /** 框架模型回放包：model + result（Python run_frame 离线生成的真实求解结果） */
    private fun frameReplay(file: String): Pair<FrameModel, FrameResult> {
        val root = AppJson.parseToJsonElement(readText(file)).jsonObject
        return AppJson.decodeFromJsonElement(FrameModel.serializer(), root["model"]!!) to
            AppJson.decodeFromJsonElement(FrameResult.serializer(), root["result"]!!)
    }

    /** 流体回放包：model + result（Python run_flow 离线生成的真实求解结果） */
    private fun flowReplay(file: String): Pair<FlowModel, FlowResult> {
        val root = AppJson.parseToJsonElement(readText(file)).jsonObject
        return AppJson.decodeFromJsonElement(FlowModel.serializer(), root["model"]!!) to
            AppJson.decodeFromJsonElement(FlowResult.serializer(), root["result"]!!)
    }

    /** 渗流回放包：model + result（Python run_seepage 离线生成的真实求解结果） */
    private fun seepageReplay(file: String): Pair<SeepageModel, SeepageResult> {
        val root = AppJson.parseToJsonElement(readText(file)).jsonObject
        return AppJson.decodeFromJsonElement(SeepageModel.serializer(), root["model"]!!) to
            AppJson.decodeFromJsonElement(SeepageResult.serializer(), root["result"]!!)
    }

    /** 时间历程回放包：spec + result（Python run_transient 离线生成的真实求解结果） */
    private fun transientReplay(file: String): Pair<TransientSpec, TransientResult> {
        val root = AppJson.parseToJsonElement(readText(file)).jsonObject
        return AppJson.decodeFromJsonElement(TransientSpec.serializer(), root["spec"]!!) to
            AppJson.decodeFromJsonElement(TransientResult.serializer(), root["result"]!!)
    }

    /** 三维实体回放包：model + result（Python run_solid 离线生成的真实求解结果） */
    private fun solidReplay(file: String): Pair<SolidWallModel, SolidResult> {
        val root = AppJson.parseToJsonElement(readText(file)).jsonObject
        return AppJson.decodeFromJsonElement(SolidWallModel.serializer(), root["model"]!!) to
            AppJson.decodeFromJsonElement(SolidResult.serializer(), root["result"]!!)
    }

    // ---------------------------------------------------------------- 假引擎
    /** 回放数据的假引擎：材料与框架结果全部来自 Python 引擎离线生成 */
    private class ReplayEngine(
        private val materials: List<MaterialRecord>,
        private val frameResult: FrameResult,
        private val flowResult: FlowResult? = null,
        private val seepageResult: SeepageResult? = null,
        private val transientResult: TransientResult? = null,
        private val solidResult: SolidResult? = null
    ) : FeaEngine {
        override suspend fun listTemplates(): List<TemplateInfo> = emptyList()
        override suspend fun runScenario(request: ScenarioRequest): FeaResult =
            error("场景模板链路已移除，测试不再提供场景回放")
        override suspend fun runFrame(model: FrameModel): FrameResult = frameResult
        override suspend fun exportFramePdf(
            model: FrameModel, result: FrameResult, filepath: String
        ): String = filepath
        override suspend fun exportSolidGenericPdf(
            model: SolidGenericModel, result: SolidGenericResult, filepath: String
        ): String = filepath
        override suspend fun runFlow(model: FlowModel): FlowResult =
            flowResult ?: error("本测试未提供流体回放数据")
        override suspend fun runSeepage(model: SeepageModel): SeepageResult =
            seepageResult ?: error("本测试未提供渗流回放数据")
        override suspend fun runTransient(spec: TransientSpec): TransientResult =
            transientResult ?: error("本测试未提供时间历程回放数据")
        override suspend fun runSolid(model: SolidWallModel): SolidResult =
            solidResult ?: error("本测试未提供三维实体回放数据")
        override suspend fun runSolidGeneric(model: SolidGenericModel): SolidGenericResult =
            error("本测试未提供通用三维实体回放数据")
        override suspend fun runShell(model: ShellVesselModel): SolidGenericResult =
            error("本测试未提供薄壁容器回放数据")
        override suspend fun runBuriedPipe(model: ShellBuriedPipeModel): BuriedPipeResult =
            error("本测试未提供埋地管回放数据")
        override suspend fun listMaterials(system: String): List<MaterialRecord> = materials
        override suspend fun upsertMaterial(record: MaterialRecord): MaterialRecord = record
        override suspend fun deleteMaterial(id: String): Boolean = false
        override suspend fun logCaseContext(contextJson: String): String = ""
        override suspend fun exportCaseLogExcel(): String = ""
    }

    /** AppContainer 测试替身：engine/parser 换成回放实现，其余（设置等）走真实逻辑 */
    private class ReplayContainer(context: Context, feaEngine: FeaEngine) : AppContainer(context) {
        override val engine: FeaEngine = feaEngine
        override val parser: ProblemParser = object : ProblemParser {
            override suspend fun parseProblem(text: String): ParseOutcome? = null
        }
    }

    private fun container(
        frameResult: FrameResult,
        flowResult: FlowResult? = null,
        seepageResult: SeepageResult? = null,
        transientResult: TransientResult? = null,
        solidResult: SolidResult? = null
    ): ReplayContainer {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return ReplayContainer(
            ctx,
            ReplayEngine(materials, frameResult, flowResult, seepageResult, transientResult, solidResult)
        )
    }

    // ---------------------------------------------------------------- 截图
    /**
     * 直接把 decorView 绘制到位图（Robolectric NATIVE 图形模式下可真实栅格化）。
     * 不用 captureToImage()：其内部 forceRedraw 依赖真实渲染帧回调，
     * Robolectric 无 Choreographer 帧循环会一直超时。
     */
    private fun snap(name: String) {
        composeRule.waitForIdle()
        val decorView = composeRule.activity.window.decorView
        check(decorView.width > 0 && decorView.height > 0) { "decorView 尚未完成布局: ${decorView.width}x${decorView.height}" }
        val bmp = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        decorView.draw(canvas)
        val dir = File("build/robo-screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 折叠区（CollapsibleSection）默认收起、内容不组合；点区标题展开后再操作内部节点 */
    private fun expandSection(title: String) {
        composeRule.onNodeWithText(title).performClick()
        composeRule.waitForIdle()
    }

    // ---------------------------------------------------------------- 用例

    /** 启动页：能力说明（力学 + 温度 + 流体）+ 新建/打开项目 */
    @Test
    fun screenshot00Launcher() {
        val c = container(FrameResult())
        composeRule.setContent {
            PocketSimTheme {
                LauncherScreen(container = c, onNewProject = {}, onOpenFrameProject = { _, _ -> })
            }
        }
        awaitText("新建项目")
        snap("00_launcher")
    }

    /** 主页：免责声明 + 问题输入 + 自动/三维切换 + 附图 + AI 入口 */
    @Test
    fun screenshot01Home() {
        val c = container(FrameResult())
        composeRule.setContent {
            PocketSimTheme {
                HomeScreen(container = c, onOpenChat = { _, _, _, _, _, _, _, _, _ -> }, onOpenSettings = {}, onOpenMaterialLibrary = {})
            }
        }
        awaitText("AI 建模")
        snap("01_home")
    }

    /** 主页示例区：点击示例整句填入输入框（点击后"AI 建模"由禁用变可点） */
    @Test
    fun screenshot02HomeExamples() {
        val c = container(FrameResult())
        composeRule.setContent {
            PocketSimTheme {
                HomeScreen(container = c, onOpenChat = { _, _, _, _, _, _, _, _, _ -> }, onOpenSettings = {}, onOpenMaterialLibrary = {})
            }
        }
        awaitText("AI 建模")
        expandSection("示例场景")
        val example = "墙上用膨胀螺栓固定一块 80 厘米长"
        composeRule.onNodeWithText(example, substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()
        // 截图取点击示例后的首页：输入框已被整句填入，"AI 建模"由禁用变可点
        composeRule.onNodeWithText("AI 建模").assertIsEnabled()
        snap("02_home_examples")
    }

    /** 主页示例分类：切到「风与渗流」后只显示流体类示例 */
    @Test
    fun screenshot02bHomeExamplesFluid() {
        val c = container(FrameResult())
        composeRule.setContent {
            PocketSimTheme {
                HomeScreen(container = c, onOpenChat = { _, _, _, _, _, _, _, _, _ -> }, onOpenSettings = {}, onOpenMaterialLibrary = {})
            }
        }
        awaitText("AI 建模")
        expandSection("示例场景")
        composeRule.onNodeWithText("风与渗流").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("砖砌体围墙", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        snap("02b_home_examples_fluid")
    }

    /** 主页 AI 技能区：选中内置「保守校核」后显示其适用场景 */
    @Test
    fun screenshot02cHomeSkillSelected() {
        val c = container(FrameResult())
        composeRule.setContent {
            PocketSimTheme {
                HomeScreen(container = c, onOpenChat = { _, _, _, _, _, _, _, _, _ -> }, onOpenSettings = {}, onOpenMaterialLibrary = {})
            }
        }
        awaitText("AI 建模")
        expandSection("AI技能")
        composeRule.onNodeWithText("保守校核").performScrollTo().performClick()
        awaitText("取最不利工况与偏保守取值，安全优先")
        snap("02c_home_skill_selected")
    }

    /** 自定义技能管理面板：已有自定义技能 + 新建表单 */
    @Test
    fun screenshot02dSkillManager() {
        composeRule.setContent {
            PocketSimTheme {
                Surface {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SkillManagerContent(
                            customSkills = listOf(
                                AiSkill(
                                    id = "custom-1",
                                    name = "厨房台面校核",
                                    brief = "台面开孔后是否还够强",
                                    instruction = "先按开孔最大、台面最薄的情况校核，再补充日常荷载。"
                                )
                            ),
                            onSave = {},
                            onDelete = {}
                        )
                    }
                }
            }
        }
        awaitText("厨房台面校核")
        snap("02d_skill_manager")
    }

    /** 材料库页面：内置材料列表 + 联网检索 + 自定义材料表单 */
    @Test
    fun screenshot02eMaterialLibrary() {
        val c = container(FrameResult())
        composeRule.setContent {
            PocketSimTheme {
                MaterialLibraryScreen(container = c, onBack = {})
            }
        }
        awaitText("材料库")
        snap("02e_material_library")
    }

    /** 框架模型预览页（松木两层书架）：线框图 + 支座/载荷符号 + 模型信息 + 杆件清单 */
    @Test
    fun screenshot03FramePreview() {
        val (model, result) = frameReplay("frame_bookshelf.json")
        val c = container(result)
        composeRule.setContent {
            PocketSimTheme {
                FramePreviewScreen(container = c, model = model, onBack = {}, onResult = { _, _ -> })
            }
        }
        awaitText("开始计算")
        snap("03_frame_preview")
    }

    /** 框架结果页（松木两层书架）：首屏 = 指标卡 + 应力着色变形图；
     *  杆件受力明细在 LazyColumn 下方，滚动后补一张（LazyColumn 只组合可见项，
     *  视口外的文本永远不会出现，必须先 scrollToNode 再等待） */
    @Test
    fun screenshot04FrameResult() {
        val (model, result) = frameReplay("frame_bookshelf.json")
        val c = container(result)
        composeRule.setContent {
            PocketSimTheme {
                FrameResultScreen(container = c, model = model, result = result, onBack = {})
            }
        }
        awaitText("变形图")
        snap("04_frame_result")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("杆件受力"))
        awaitText("杆件受力")
        snap("05_frame_members")
    }

    /** 深色模式结果页：结论横幅必须深底浅字（浅底 + 默认浅色文字会看不清） */
    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-night-420dpi")
    fun screenshot04bFrameResultDark() {
        val (model, result) = frameReplay("frame_bookshelf.json")
        val c = container(result)
        composeRule.setContent {
            PocketSimTheme {
                FrameResultScreen(container = c, model = model, result = result, onBack = {})
            }
        }
        awaitText("结论：撑不住，会损坏")
        snap("09_frame_result_dark")
    }

    /** 框架结果追问页（v0.3.0，松木两层书架）：结果摘要卡 + 引导语气泡 + 输入行。
     *  初始状态只有 greeting，不触发 AI 请求，展示首启即所见。 */
    @Test
    fun screenshot05FrameFollowUp() {
        val (model, result) = frameReplay("frame_bookshelf.json")
        val c = container(result)
        composeRule.setContent {
            PocketSimTheme {
                FrameFollowUpScreen(
                    container = c,
                    model = model,
                    result = result,
                    onBack = {},
                    onModelReady = {}
                )
            }
        }
        awaitText("继续追问")
        snap("06_frame_followup")
    }

    /** 三维模型预览（P3D-3，松木方桌）：可旋转 3D 线框图 + 地面网格 + 支座/载荷 */
    @Test
    fun screenshot06Frame3DPreview() {
        val (model, result) = frameReplay("frame_3d_table.json")
        val c = container(result)
        composeRule.setContent {
            PocketSimTheme {
                FramePreviewScreen(container = c, model = model, onBack = {}, onResult = { _, _ -> })
            }
        }
        awaitText("开始计算")
        snap("07_frame_3d_preview")
    }

    /** 三维结果页（P3D-3，松木方桌）：3D 变形图（可旋转/缩放）+ 指标卡 + 图例 */
    @Test
    fun screenshot07Frame3DResult() {
        val (model, result) = frameReplay("frame_3d_table.json")
        val c = container(result)
        composeRule.setContent {
            PocketSimTheme {
                FrameResultScreen(container = c, model = model, result = result, onBack = {})
            }
        }
        awaitText("变形图")
        snap("08_frame_3d_result")
    }

    /** 流体模型预览（风把砖墙吹倒）：墙体示意图 + 尺寸/风级 + 免责声明 */
    @Test
    fun screenshot08FlowPreview() {
        val (model, result) = flowReplay("flow_wall.json")
        val c = container(frameResult = FrameResult(), flowResult = result)
        composeRule.setContent {
            PocketSimTheme {
                FlowPreviewScreen(container = c, model = model, onBack = {}, onResult = { _, _ -> })
            }
        }
        awaitText("开始计算")
        snap("10_flow_preview")
    }

    /** 流体结果页（风把砖墙吹倒）：结论横幅 + 三次校核 + 压力云图/流线 */
    @Test
    fun screenshot08bFlowResult() {
        val (_, result) = flowReplay("flow_wall.json")
        composeRule.setContent {
            PocketSimTheme {
                FlowResultScreen(result = result, onBack = {})
            }
        }
        awaitText("流场：压力云图与流线")
        snap("11_flow_result")
    }

    /** 深色模式流体结果页：结论横幅必须深底浅字 */
    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-night-420dpi")
    fun screenshot08cFlowResultDark() {
        val (_, result) = flowReplay("flow_wall.json")
        composeRule.setContent {
            PocketSimTheme {
                FlowResultScreen(result = result, onBack = {})
            }
        }
        awaitText("流场：压力云图与流线")
        snap("12_flow_result_dark")
    }

    /** 渗流模型预览（大坝坝基渗流）：坝体剖面示意图 + 水位/坡比 + 免责声明 */
    @Test
    fun screenshot09SeepagePreview() {
        val (model, result) = seepageReplay("seepage_dam.json")
        val c = container(frameResult = FrameResult(), seepageResult = result)
        composeRule.setContent {
            PocketSimTheme {
                SeepagePreviewScreen(container = c, model = model, onBack = {}, onResult = { _, _ -> })
            }
        }
        awaitText("开始计算")
        snap("13_seepage_preview")
    }

    /** 渗流结果页（大坝坝基渗流）：结论横幅 + 三项指标 + 水头云图/渗流线 */
    @Test
    fun screenshot09bSeepageResult() {
        val (_, result) = seepageReplay("seepage_dam.json")
        composeRule.setContent {
            PocketSimTheme {
                SeepageResultScreen(result = result, onBack = {})
            }
        }
        awaitText("渗流场：水头云图与渗流线")
        snap("14_seepage_result")
    }

    /** 深色模式渗流结果页：危险横幅必须深底浅字，云图坝体对比度足够 */
    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-night-420dpi")
    fun screenshot09cSeepageResultDark() {
        val (_, result) = seepageReplay("seepage_dam.json")
        composeRule.setContent {
            PocketSimTheme {
                SeepageResultScreen(result = result, onBack = {})
            }
        }
        awaitText("渗流场：水头云图与渗流线")
        snap("15_seepage_result_dark")
    }

    /** 时间历程预览（悬臂/门式支架 + 温度先升后降）：温度曲线 + 线框示意图 + 模型信息 */
    @Test
    fun screenshot10TransientPreview() {
        val (spec, result) = transientReplay("transient_canopy.json")
        val c = container(frameResult = FrameResult(), transientResult = result)
        composeRule.setContent {
            PocketSimTheme {
                TransientPreviewScreen(container = c, spec = spec, onBack = {}, onResult = { _, _ -> })
            }
        }
        awaitText("开始计算")
        snap("16_transient_preview")
    }

    /** 时间历程结果页：结论 + 指标 + 三联曲线 + 时间滑杆 + 选中时刻变形/杆件明细 */
    @Test
    fun screenshot10bTransientResult() {
        val (spec, result) = transientReplay("transient_canopy.json")
        composeRule.setContent {
            PocketSimTheme {
                TransientResultScreen(spec = spec, result = result, onBack = {})
            }
        }
        awaitText("随时间变化")
        snap("17_transient_result")
    }

    /** 深色模式时间历程结果页：曲线与横幅在深底上必须清晰可读 */
    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-night-420dpi")
    fun screenshot10cTransientResultDark() {
        val (spec, result) = transientReplay("transient_canopy.json")
        composeRule.setContent {
            PocketSimTheme {
                TransientResultScreen(spec = spec, result = result, onBack = {})
            }
        }
        awaitText("随时间变化")
        snap("18_transient_result_dark")
    }

    /** 三维实体预览（240 砖墙 + 10 级风）：等轴测墙体示意图 + 尺寸/风荷载/网格 + 开始计算 */
    @Test
    fun screenshot11SolidPreview() {
        val (model, result) = solidReplay("solid_wall.json")
        val c = container(frameResult = FrameResult(), solidResult = result)
        composeRule.setContent {
            PocketSimTheme {
                SolidPreviewScreen(container = c, model = model, onBack = {}, onResult = { _, _ -> })
            }
        }
        awaitText("开始计算")
        snap("19_solid_preview")
    }

    /** 三维实体结果页（240 砖墙 + 10 级风）：结论横幅 + 指标卡 + 三维应力云图 + 校核 */
    @Test
    fun screenshot11bSolidResult() {
        val (model, result) = solidReplay("solid_wall.json")
        composeRule.setContent {
            PocketSimTheme {
                SolidResultScreen(model = model, result = result, onBack = {})
            }
        }
        awaitText("应力云图（von Mises）")
        snap("20_solid_result")
    }

    /** 深色模式三维实体结果页：危险横幅必须深底浅字，云图色带对比度足够 */
    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-night-420dpi")
    fun screenshot11cSolidResultDark() {
        val (model, result) = solidReplay("solid_wall.json")
        composeRule.setContent {
            PocketSimTheme {
                SolidResultScreen(model = model, result = result, onBack = {})
            }
        }
        awaitText("应力云图（von Mises）")
        snap("21_solid_result_dark")
    }
}
