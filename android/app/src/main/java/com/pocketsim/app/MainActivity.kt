/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.pocketsim.app.ui.ActivationScreen
import com.pocketsim.app.ui.CandidateSelectScreen
import com.pocketsim.app.ui.ChatScreen
import com.pocketsim.app.ui.FlowPreviewScreen
import com.pocketsim.app.ui.FlowResultScreen
import com.pocketsim.app.ui.FrameFollowUpScreen
import com.pocketsim.app.ui.FramePreviewScreen
import com.pocketsim.app.ui.FrameResultScreen
import com.pocketsim.app.ui.HomeScreen
import com.pocketsim.app.ui.LauncherScreen
import com.pocketsim.app.ui.MaterialLibraryScreen
import com.pocketsim.app.ui.OpenSourceLicensesScreen
import com.pocketsim.app.ui.OptimizeScreen
import com.pocketsim.app.ui.SeepagePreviewScreen
import com.pocketsim.app.ui.SeepageResultScreen
import com.pocketsim.app.ui.SettingsScreen
import com.pocketsim.app.ui.SolidPreviewScreen
import com.pocketsim.app.ui.SolidResultScreen
import com.pocketsim.app.ui.SolidGenericPreviewScreen
import com.pocketsim.app.ui.SolidGenericResultScreen
import com.pocketsim.app.ui.ShellPreviewScreen
import com.pocketsim.app.ui.BuriedPipeResultScreen
import com.pocketsim.app.ui.ShellResultScreen
import com.pocketsim.app.ui.TransientPreviewScreen
import com.pocketsim.app.ui.TransientResultScreen
import com.pocketsim.app.ui.theme.PocketSimTheme

/**
 * 单 Activity 入口 + Compose 自研路由（简单的路由栈 state，不引入 navigation-compose）。
 */
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 每次 Activity 重建（语言/单位制切换后 recreate）都要按最新偏好重新初始化，
        // 否则切换语言→默认单位制变化时，Units.system 仍停留在进程启动时的旧值。
        Units.init(this)
        enableEdgeToEdge()
        val container = AppContainer(applicationContext)
        // 后台预热求解引擎：把 Python 启动 + numpy/scipy 加载（首次约数秒）提前到启动期，
        // 用户进入 AI 建模/求解时不再等待冷启动（失败静默，求解时懒启动兜底）
        container.warmUpEngine()
        setContent {
            PocketSimTheme {
                AppRoot(container)
            }
        }
    }
}

/** 路由定义：携带跳转所需的全部载荷对象 */
sealed interface Route {
    /** 启动首页（能力说明 + 新建/打开项目入口） */
    data object Launcher : Route

    /** 工作区（问题输入 + AI 建模入口 + 示例） */
    data object Home : Route

    /** AI 对话（problemText 为首页带入的问题描述；
     *  initialImages 为首页随"AI 理解"带入的结构照片 data URI，与文字一起作为第一条消息；
     *  regenFeedback 为预览确认页"不满意·修改模型"带回的反馈，重新进对话后自动发送让 AI 重建模） */
    data class Chat(
        val problemText: String,
        val initialImages: List<String> = emptyList(),
        val regenFeedback: String = "",
        /** 是否强制三维建模（false = 自动判别平面/空间，P3D-5） */
        val threeD: Boolean = false,
        /** 是否强制使用三维实体（TET4）墙体契约（仅 threeD=true 时生效） */
        val forceSolid: Boolean = false,
        /** 是否强制使用通用三维实体（box/cylinder/plate）契约 */
        val forceSolidGeneric: Boolean = false,
        /** 是否强制使用薄壁容器（水箱/料仓）契约 */
        val forceShell: Boolean = false,
        /** 是否强制使用埋地管（覆土+车载工程公式）契约 */
        val forceBuriedPipe: Boolean = false,
        /** 首页选择的 AI 技能（可为空，仅追加行为指令） */
        val skill: AiSkill? = null,
        /** 预览页"修改模型"时的当前模型：作为强基准注入 ChatSession 提示词，只改用户指定字段；
         *  首轮正常建模为 null。仅框架预览页填充，其余类型传 null。 */
        val regenAnchor: FrameModel? = null
    ) : Route

    /** 框架模型预览确认（AI 通用结构建模的产物） */
    data class FramePreview(val model: FrameModel) : Route

    /** 框架求解结果页（模型 + 结果一起携带，页面无状态可直接回放） */
    data class FrameResult(val model: FrameModel, val result: com.pocketsim.app.FrameResult) : Route

    /** 框架结果追问页（以本次模型+结果为上下文，答疑或改条件重建模） */
    data class FrameFollowUp(val model: FrameModel, val result: com.pocketsim.app.FrameResult) : Route

    /** 流体模型预览确认（风把墙吹倒：墙体尺寸 + 风速） */
    data class FlowPreview(val model: FlowModel) : Route

    /** 流体求解结果页（压力云图 + 流线 + 稳定性结论） */
    data class FlowResult(
        val model: FlowModel,
        val result: com.pocketsim.app.FlowResult
    ) : Route

    /** 渗流模型预览确认（大坝坝基渗流：坝体尺寸 + 水位） */
    data class SeepagePreview(val model: SeepageModel) : Route

    /** 渗流求解结果页（水头云图 + 渗流线 + 稳定性结论） */
    data class SeepageResult(
        val model: SeepageModel,
        val result: com.pocketsim.app.SeepageResult
    ) : Route

    /** 时间历程预览确认（温度循环 + 恒载：温度曲线 + 模型信息） */
    data class TransientPreview(val spec: TransientSpec) : Route

    /** 时间历程结果页（曲线 + 时间滑杆 + 选中时刻指标/变形） */
    data class TransientResult(
        val spec: TransientSpec,
        val result: com.pocketsim.app.TransientResult
    ) : Route

    /** 三维实体模型预览确认（墙体风压：尺寸 + 材料 + 风荷载） */
    data class SolidPreview(val model: SolidWallModel) : Route

    /** 三维实体求解结果页（应力云图 + 位移/安全系数） */
    data class SolidResult(
        val model: SolidWallModel,
        val result: com.pocketsim.app.SolidResult
    ) : Route

    /** 通用三维实体模型预览确认（box/cylinder/plate + 约束 + 载荷） */
    data class SolidGenericPreview(val model: SolidGenericModel) : Route

    /** 通用三维实体求解结果页（应力云图 + 位移/安全系数） */
    data class SolidGenericResult(
        val model: SolidGenericModel,
        val result: com.pocketsim.app.SolidGenericResult
    ) : Route

    /** 薄壁容器模型预览确认（水箱/料仓：几何 + 材料 + 填料） */
    data class ShellPreview(val model: ShellVesselModel) : Route

    /** 薄壁容器求解结果页（应力云图 + 位移/安全系数；输出与 SolidGenericResult 同构） */
    data class ShellResult(
        val model: ShellVesselModel,
        val result: com.pocketsim.app.SolidGenericResult
    ) : Route

    /** 埋地管结果页：result 为 null 时页面内调用求解（新建模），非 null 时直接展示（已保存项目回放） */
    data class BuriedPipeResult(
        val model: ShellBuriedPipeModel,
        val result: com.pocketsim.app.BuriedPipeResult? = null
    ) : Route

    /** 多候选选择页：首轮建模生成多个候选，用户点选后进对应预览 */
    data class CandidateSelect(val candidates: List<ModelCandidate>) : Route

    /** AI 优化建议（第一期仅 Frame / SolidGeneric；仅注意/危险时进入） */
    data class Optimize(val target: OptimizeTarget) : Route

    data object Settings : Route

    /** 邀请码激活：输入邀请码换取 app_token 与每日额度 */
    data object Activation : Route

    data object OpenSourceLicenses : Route

    /** 材料库：内置材料列表 + 联网检索 + 自定义材料录入 */
    data object MaterialLibrary : Route
}

/** 候选 → 对应预览路由（7 类全覆盖） */
private fun previewRouteFor(c: ModelCandidate): Route = when (c) {
    is ModelCandidate.Frame -> Route.FramePreview(c.model)
    is ModelCandidate.Flow -> Route.FlowPreview(c.model)
    is ModelCandidate.Seepage -> Route.SeepagePreview(c.model)
    is ModelCandidate.Transient -> Route.TransientPreview(c.spec)
    is ModelCandidate.Solid -> Route.SolidPreview(c.model)
    is ModelCandidate.SolidGeneric -> Route.SolidGenericPreview(c.model)
    is ModelCandidate.Shell -> Route.ShellPreview(c.model)
}

/** 根组合函数：持有路由栈并分发到各屏 */
@Composable
fun AppRoot(container: AppContainer) {
    val backStack = remember { mutableStateListOf<Route>(Route.Launcher) }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /** 候选就绪：仅 1 个直进预览，≥2 个进选择页（AC-7 数量兜底） */
    fun openCandidates(list: List<ModelCandidate>) {
        if (list.isEmpty()) return
        if (list.size == 1) {
            backStack.add(previewRouteFor(list.first()))
        } else {
            backStack.add(Route.CandidateSelect(list))
        }
    }

    // 系统返回键 = 路由栈出栈
    BackHandler(enabled = backStack.size > 1) { pop() }

    when (val route = backStack.last()) {
        is Route.Launcher -> LauncherScreen(
            container = container,
            onNewProject = { backStack.add(Route.Home) },
            onOpenFrameProject = { model, result ->
                backStack.add(Route.FrameResult(model, result))
            },
            onOpenSolidProject = { model, result ->
                backStack.add(Route.SolidGenericResult(model, result))
            },
            onOpenShellProject = { model, result ->
                backStack.add(Route.ShellResult(model, result))
            },
            onOpenBuriedPipeProject = { model, result ->
                backStack.add(Route.BuriedPipeResult(model, result))
            }
        )

        is Route.Home -> HomeScreen(
            container = container,
            onOpenChat = { text, _, images, threeD, forceSolid, forceSolidGeneric, forceShell, forceBuriedPipe, skill ->
                backStack.add(
                    Route.Chat(
                        text, images,
                        threeD = threeD,
                        forceSolid = forceSolid,
                        forceSolidGeneric = forceSolidGeneric,
                        forceShell = forceShell,
                        forceBuriedPipe = forceBuriedPipe,
                        skill = skill
                    )
                )
            },
            onOpenSettings = { backStack.add(Route.Settings) },
            onOpenMaterialLibrary = { backStack.add(Route.MaterialLibrary) }
        )

        is Route.Chat -> ChatScreen(
            container = container,
            problemText = route.problemText,
            initialImages = route.initialImages,
            regenFeedback = route.regenFeedback,
            onBack = ::pop,
            threeD = route.threeD,
            forceSolid = route.forceSolid,
            forceSolidGeneric = route.forceSolidGeneric,
            forceShell = route.forceShell,
            forceBuriedPipe = route.forceBuriedPipe,
            skill = route.skill,
            regenAnchor = route.regenAnchor,
            onFrameReady = { backStack.add(Route.FramePreview(it)) },
            onFlowReady = { backStack.add(Route.FlowPreview(it)) },
            onSeepageReady = { backStack.add(Route.SeepagePreview(it)) },
            onTransientReady = { backStack.add(Route.TransientPreview(it)) },
            onSolidReady = { backStack.add(Route.SolidPreview(it)) },
            onSolidGenericReady = { backStack.add(Route.SolidGenericPreview(it)) },
            onShellReady = { backStack.add(Route.ShellPreview(it)) },
            onBuriedPipeReady = { backStack.add(Route.BuriedPipeResult(it)) },
            onCandidatesReady = ::openCandidates
        )

        is Route.CandidateSelect -> CandidateSelectScreen(
            candidates = route.candidates,
            onBack = ::pop,
            onSelect = { candidate ->
                // 点选候选：在选择页之上叠加预览（返回键回到选择页，可换选另一个方案）
                backStack.add(previewRouteFor(candidate))
            }
        )

        is Route.FramePreview -> FramePreviewScreen(
            container = container,
            model = route.model,
            onBack = ::pop,
            onResult = { m, r -> backStack.add(Route.FrameResult(m, r)) },
            onRegenRequest = { feedback ->
                // 弹掉 FramePreview，把底下的 Chat 替换为带重建模反馈的新 Chat（历史不保留，
                // AI 会以原始问题 + 反馈重新建模；历史对话太短不值得持久）。
                // 把当前模型 route.model 作为强锚定传入：AI 只能改用户指定字段，
                // 未提及的几何/材料/dim 保持不变，避免修改后整体重画（FR-3 / AC-2）。
                backStack.removeAt(backStack.lastIndex)
                val oldChat = backStack.last() as? Route.Chat
                backStack.removeAt(backStack.lastIndex)
                backStack.add(
                    Route.Chat(
                        problemText = oldChat?.problemText ?: "",
                        initialImages = emptyList(),
                        regenFeedback = feedback,
                        threeD = oldChat?.threeD ?: false,
                        forceSolid = oldChat?.forceSolid ?: false,
                        forceSolidGeneric = oldChat?.forceSolidGeneric ?: false,
                        forceShell = oldChat?.forceShell ?: false,
                        skill = oldChat?.skill,
                        regenAnchor = route.model
                    )
                )
            }
        )

        is Route.Optimize -> OptimizeScreen(
            container = container,
            target = route.target,
            onBack = ::pop
        )

        is Route.FrameResult -> FrameResultScreen(
            container = container,
            model = route.model,
            result = route.result,
            onBack = ::pop,
            onFollowUp = { backStack.add(Route.FrameFollowUp(route.model, route.result)) },
            onOptimize = {
                backStack.add(
                    Route.Optimize(
                        OptimizeTarget(
                            kind = OptimizeKind.FRAME,
                            modelJson = AppJson.encodeToString(FrameModel.serializer(), route.model),
                            summary = OptimizeRunner.summaryOf(route.result),
                            before = OptimizeRunner.metricsOf(route.result)
                        )
                    )
                )
            },
            onGoHome = {
                backStack.clear()
                backStack.add(Route.Home)
            }
        )

        is Route.FrameFollowUp -> FrameFollowUpScreen(
            container = container,
            model = route.model,
            result = route.result,
            onBack = ::pop,
            // 改好的模型 → 预览确认 → 重算 → 新结果页（历史结果留在栈中可回看）
            onModelReady = { backStack.add(Route.FramePreview(it)) }
        )

        is Route.FlowPreview -> FlowPreviewScreen(
            container = container,
            model = route.model,
            onBack = ::pop,
            onResult = { m, r -> backStack.add(Route.FlowResult(m, r)) }
        )

        is Route.FlowResult -> FlowResultScreen(
            result = route.result,
            onBack = ::pop
        )

        is Route.SeepagePreview -> SeepagePreviewScreen(
            container = container,
            model = route.model,
            onBack = ::pop,
            onResult = { m, r -> backStack.add(Route.SeepageResult(m, r)) }
        )

        is Route.SeepageResult -> SeepageResultScreen(
            result = route.result,
            onBack = ::pop
        )

        is Route.TransientPreview -> TransientPreviewScreen(
            container = container,
            spec = route.spec,
            onBack = ::pop,
            onResult = { s, r -> backStack.add(Route.TransientResult(s, r)) }
        )

        is Route.TransientResult -> TransientResultScreen(
            spec = route.spec,
            result = route.result,
            onBack = ::pop
        )

        is Route.SolidPreview -> SolidPreviewScreen(
            container = container,
            model = route.model,
            onBack = ::pop,
            onResult = { m, r ->
                // 用实际求解的模型（含预览页选定的网格档位）替换预览页模型，
                // 从结果页返回预览时仍保留刚才的档位选择
                backStack[backStack.lastIndex] = Route.SolidPreview(m)
                backStack.add(Route.SolidResult(m, r))
            },
            onRegenRequest = { feedback ->
                backStack.removeAt(backStack.lastIndex)
                val oldChat = backStack.last() as? Route.Chat
                backStack.removeAt(backStack.lastIndex)
                backStack.add(
                    Route.Chat(
                        problemText = oldChat?.problemText ?: "",
                        initialImages = emptyList(),
                        regenFeedback = feedback,
                        threeD = oldChat?.threeD ?: false,
                        forceSolid = oldChat?.forceSolid ?: false,
                        forceSolidGeneric = oldChat?.forceSolidGeneric ?: false,
                        forceShell = oldChat?.forceShell ?: false,
                        skill = oldChat?.skill
                    )
                )
            }
        )

        is Route.SolidResult -> SolidResultScreen(
            model = route.model,
            result = route.result,
            onBack = ::pop
        )

        is Route.SolidGenericPreview -> SolidGenericPreviewScreen(
            container = container,
            model = route.model,
            onBack = ::pop,
            onResult = { m, r ->
                // 同 SolidPreview：保留预览页选定的网格档位
                backStack[backStack.lastIndex] = Route.SolidGenericPreview(m)
                backStack.add(Route.SolidGenericResult(m, r))
            },
            onRegenRequest = { feedback ->
                backStack.removeAt(backStack.lastIndex)
                val oldChat = backStack.last() as? Route.Chat
                backStack.removeAt(backStack.lastIndex)
                backStack.add(
                    Route.Chat(
                        problemText = oldChat?.problemText ?: "",
                        initialImages = emptyList(),
                        regenFeedback = feedback,
                        threeD = oldChat?.threeD ?: false,
                        forceSolid = oldChat?.forceSolid ?: false,
                        forceSolidGeneric = oldChat?.forceSolidGeneric ?: false,
                        forceShell = oldChat?.forceShell ?: false,
                        skill = oldChat?.skill
                    )
                )
            }
        )

        is Route.SolidGenericResult -> SolidGenericResultScreen(
            container = container,
            model = route.model,
            result = route.result,
            onBack = ::pop,
            // 从预览页算出来的结果：返回预览页即可"不满意，修改模型"；
            // 从"打开老项目"回放的结果没有对话上下文，不显示修改入口
            onModify = if (backStack.size >= 2 &&
                backStack[backStack.lastIndex - 1] is Route.SolidGenericPreview
            ) ::pop else null,
            onOptimize = {
                backStack.add(
                    Route.Optimize(
                        OptimizeTarget(
                            kind = OptimizeKind.SOLID_GENERIC,
                            modelJson = AppJson.encodeToString(SolidGenericModel.serializer(), route.model),
                            summary = OptimizeRunner.summaryOf(route.result),
                            before = OptimizeRunner.metricsOf(route.result)
                        )
                    )
                )
            }
        )

        is Route.ShellPreview -> ShellPreviewScreen(
            container = container,
            model = route.model,
            onBack = ::pop,
            onResult = { m, r ->
                backStack[backStack.lastIndex] = Route.ShellPreview(m)
                backStack.add(Route.ShellResult(m, r))
            },
            onRegenRequest = { feedback ->
                backStack.removeAt(backStack.lastIndex)
                val oldChat = backStack.last() as? Route.Chat
                backStack.removeAt(backStack.lastIndex)
                backStack.add(
                    Route.Chat(
                        problemText = oldChat?.problemText ?: "",
                        initialImages = emptyList(),
                        regenFeedback = feedback,
                        threeD = oldChat?.threeD ?: false,
                        forceSolid = oldChat?.forceSolid ?: false,
                        forceSolidGeneric = oldChat?.forceSolidGeneric ?: false,
                        forceShell = oldChat?.forceShell ?: true,
                        skill = oldChat?.skill
                    )
                )
            }
        )

        is Route.ShellResult -> ShellResultScreen(
            container = container,
            model = route.model,
            result = route.result,
            onBack = ::pop,
            onModify = if (backStack.size >= 2 &&
                backStack[backStack.lastIndex - 1] is Route.ShellPreview
            ) ::pop else null
        )

        is Route.BuriedPipeResult -> BuriedPipeResultScreen(
            container = container,
            model = route.model,
            result = route.result,
            onBack = ::pop,
            onModify = ::pop
        )

        is Route.Settings -> SettingsScreen(
            container = container,
            onBack = ::pop,
            onOpenOss = { backStack.add(Route.OpenSourceLicenses) },
            onOpenActivation = { backStack.add(Route.Activation) }
        )

        is Route.OpenSourceLicenses -> OpenSourceLicensesScreen(onBack = ::pop)

        is Route.Activation -> ActivationScreen(
            container = container,
            onBack = ::pop
        )

        is Route.MaterialLibrary -> MaterialLibraryScreen(
            container = container,
            onBack = ::pop
        )
    }
}
