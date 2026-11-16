/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Chaquopy 调用封装。
 *
 * 跨语言契约（与 /workspace/pocketsim/python 的 pocketsim.bridge 对齐，详见 README）：
 *  - pocketsim.bridge.list_templates() -> str            JSON 数组 [TemplateInfo, ...]
 *  - pocketsim.bridge.run_scenario(request_json: str) -> str   JSON FeaResult
 *  - pocketsim.bridge.run_frame(model_json: str, data_dir: str) -> str   JSON FrameResult（2D 梁杆框架）
 *  - pocketsim.bridge.check_frame_stability(model_json, data_dir) -> str  JSON {"ok":bool,"level"?,"reason"?}（几何不变性预检，不求解）
 *  - pocketsim.bridge.run_flow(model_json: str, data_dir: str) -> str    JSON FlowResult（风荷载墙体）
 *  - pocketsim.bridge.run_seepage(model_json: str, data_dir: str) -> str JSON SeepageResult（大坝渗流）
 *  - pocketsim.bridge.run_transient(spec_json: str, data_dir: str) -> str JSON TransientResult（温度历程）
 *  - pocketsim.bridge.run_solid(model_json: str, data_dir: str) -> str   JSON SolidResult（三维实体墙体）
 *  - pocketsim.bridge.run_solid_generic(model_json: str, data_dir: str) -> str   JSON SolidGenericResult（通用三维实体）
 *  - pocketsim.bridge.run_shell(model_json: str, data_dir: str) -> str   JSON SolidGenericResult（薄壁容器，输出与 SolidGenericResult 同构）
 *  - pocketsim.bridge.export_frame_pdf(model_json, result_json, filepath, data_dir) -> str  PDF 路径
 *  - pocketsim.bridge.list_materials(data_dir: str) -> str     JSON 数组 [MaterialRecord, ...]
 *  - pocketsim.bridge.upsert_material(record_json: str, data_dir: str) -> str  JSON MaterialRecord
 *  - pocketsim.bridge.delete_material(material_id: str, data_dir: str) -> str  JSON {"ok": bool}
 *  - pocketsim.parser.parse_problem(text: str) -> dict   离线规则解析（dict 经 json.dumps 跨边界）
 *
 * bridge 为 Android 专用适配层：字段名/单位已按 Kotlin 侧模型映射
 * （max_stress_MPa、E_MPa、tris、disp 等），错误统一以 ValueError 抛出。
 *
 * 统一以 JSON 字符串跨边界（避免 Chaquopy proxy 的动态类型坑），
 * Python 侧参数/求解错误以异常抛出，这里转成 [FeaEngineException] 带回友好信息。
 */
interface FeaEngine {
    /** 场景模板列表（含参数 schema） */
    suspend fun listTemplates(): List<TemplateInfo>

    /** 执行一次 FEA 求解 */
    suspend fun runScenario(request: ScenarioRequest): FeaResult

    /** 2D 梁杆框架求解（AI 通用建模模式的 FrameModel） */
    suspend fun runFrame(model: FrameModel): FrameResult

    /** 框架几何不变性预检：稳定（或预检自身异常）返回 null；机构 / 近机构返回 [FrameStability]。
     *  默认 null = 不预检，保持旧行为（测试 fake 零改动）；仅 [ChaquopyEngine] 覆写为真实预检。 */
    suspend fun checkFrameStability(model: FrameModel): FrameStability? = null

    /** 风荷载墙体流体仿真（AI 流体建模的 FlowModel） */
    suspend fun runFlow(model: FlowModel): FlowResult

    /** 大坝坝基渗流仿真（AI 渗流建模的 SeepageModel） */
    suspend fun runSeepage(model: SeepageModel): SeepageResult

    /** 温度-载荷时间历程（AI 时间变化建模的 TransientSpec） */
    suspend fun runTransient(spec: TransientSpec): TransientResult

    /** 三维实体墙体风压仿真（AI 实体建模的 SolidWallModel） */
    suspend fun runSolid(model: SolidWallModel): SolidResult

    /** 通用三维实体 TET4 仿真（box/cylinder/plate + supports + loads） */
    suspend fun runSolidGeneric(model: SolidGenericModel): SolidGenericResult

    /** 薄壁容器壳单元仿真（矩形水箱 / 圆柱料仓，填料载荷由求解器自动生成） */
    suspend fun runShell(model: ShellVesselModel): SolidGenericResult

    /** 埋地管工程公式验算（覆土 + 车载，纯公式不跑 FEM） */
    suspend fun runBuriedPipe(model: ShellBuriedPipeModel): BuriedPipeResult

    /** 把框架模型+结果导出为 PDF 报告，返回文件绝对路径 */
    suspend fun exportFramePdf(model: FrameModel, result: FrameResult, filepath: String): String

    /** 把通用三维实体模型+结果导出为 PDF 报告，返回文件绝对路径 */
    suspend fun exportSolidGenericPdf(
        model: SolidGenericModel,
        result: SolidGenericResult,
        filepath: String
    ): String

    /** 材料列表（内置库 + 联网检索缓存，缓存位于 data_dir）；system="gb"/"us" 按单位制筛选 */
    suspend fun listMaterials(system: String = ""): List<MaterialRecord>

    /** 写入/更新材料缓存（联网检索结果入库，返回规范化后的记录） */
    suspend fun upsertMaterial(record: MaterialRecord): MaterialRecord

    /** 删除联网检索/用户自填材料（内置材料不可删除），返回是否成功 */
    suspend fun deleteMaterial(id: String): Boolean

    /** 案例埋点：把一条非求解阶段案例（如校验失败）写入 {dataDir}/case_log.jsonl。
     *  contextJson 字段：model(对象)、error、user_text、session_id、input_form、
     *  stage(默认 validate)、detected(默认 true)。返回写入的 JSONL 路径。 */
    suspend fun logCaseContext(contextJson: String): String

    /** 案例埋点：把 {dataDir}/case_log.jsonl 导出为 xlsx，列顺序对齐 A-S。
     *  返回输出 xlsx 的绝对路径。 */
    suspend fun exportCaseLogExcel(): String
}

/** 引擎层异常：message 已转为可直接展示的中文错误 */
class FeaEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 框架几何不变性预检结果（仅在不稳定时返回）。
 *  - level: "mechanism"（机构，几何可变，硬拦）/ "near_mechanism"（近机构，弱刚度）
 *  - reason: 带节点与自由度方向定位的中文修复指引，可直接展示给用户
 */
data class FrameStability(val level: String, val reason: String)

/** check_frame_stability 的 Python 返回（宽松解析，缺字段走默认值） */
@Serializable
private data class FrameStabilityResponse(
    val ok: Boolean = false,
    val level: String? = null,
    val reason: String? = null,
)

/**
 * 离线规则解析器（pocketsim.parser.parse_problem）——独立于 [FeaEngine] 的窄接口，
 * 便于 JVM 单测注入 fake；识别失败（ok=false）或引擎异常统一返回 null。
 */
interface ProblemParser {
    /** 解析自然语言问题 → 模板与参数；未识别 / 引擎异常返回 null */
    suspend fun parseProblem(text: String): ParseOutcome?
}

/**
 * 基于 Chaquopy 的 [FeaEngine] + [ProblemParser] 实现。
 *
 * - 首次调用时懒启动 Python（Python.start 仅允许每进程一次，加锁防并发）；
 * - 全部调用切到 Dispatchers.Default，不阻塞主线程。
 *   注：Python 求解本身不可中途取消（C 层阻塞），协程取消只影响等待方。
 */
class ChaquopyEngine(private val context: Context) : FeaEngine, ProblemParser {

    private val startLock = Any()

    /** 懒启动并返回 Python 单例（官方推荐的手动启动方式） */
    private fun python(): Python = synchronized(startLock) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        Python.getInstance()
    }

    override suspend fun listTemplates(): List<TemplateInfo> = withContext(Dispatchers.Default) {
        val json = pyCall { py ->
            py.getModule("pocketsim.bridge").callAttr("list_templates").toString()
        }
        AppJson.decodeFromString(ListSerializer(TemplateInfo.serializer()), json)
    }

    override suspend fun runScenario(request: ScenarioRequest): FeaResult = withContext(Dispatchers.Default) {
        val requestJson = AppJson.encodeToString(ScenarioRequest.serializer(), request)
        val json = pyCall { py ->
            py.getModule("pocketsim.bridge").callAttr("run_scenario", requestJson).toString()
        }
        AppJson.decodeFromString(FeaResult.serializer(), json)
    }

    override suspend fun runFrame(model: FrameModel): FrameResult = withContext(Dispatchers.Default) {
        val modelJson = AppJson.encodeToString(FrameModel.serializer(), model)
        // dim=3 走空间梁求解器（run_frame3d）；平面模型保持 run_frame 原路径，行为不变
        val fn = if (model.dim == 3) "run_frame3d" else "run_frame"
        val json = pyCall { py ->
            py.getModule("pocketsim.bridge").callAttr(fn, modelJson, dataDir(), lang(), unitsTag()).toString()
        }
        AppJson.decodeFromString(FrameResult.serializer(), json)
    }

    /** 框架几何不变性预检：只组装刚度阵 + 特征值判定，不求解（毫秒级）。
     *  稳定 / 预检异常 → null（调用方 runCatching 兜住异常，按"稳定"放行，避免预检 bug 卡死生成）；
     *  机构 / 近机构 → 非 null [FrameStability]，供候选阶段剔除。 */
    override suspend fun checkFrameStability(model: FrameModel): FrameStability? = withContext(Dispatchers.Default) {
        val modelJson = AppJson.encodeToString(FrameModel.serializer(), model)
        val json = pyCall { py ->
            py.getModule("pocketsim.bridge")
                .callAttr("check_frame_stability", modelJson, dataDir(), lang(), unitsTag()).toString()
        }
        val resp = AppJson.decodeFromString(FrameStabilityResponse.serializer(), json)
        if (resp.ok || resp.level.isNullOrBlank()) null else FrameStability(resp.level, resp.reason ?: "")
    }

    override suspend fun runFlow(model: FlowModel): FlowResult = withContext(Dispatchers.Default) {
        val modelJson = AppJson.encodeToString(FlowModel.serializer(), model)
        val json = pyCall { py ->
            py.getModule("pocketsim.bridge").callAttr("run_flow", modelJson, dataDir(), lang(), unitsTag()).toString()
        }
        AppJson.decodeFromString(FlowResult.serializer(), json)
    }

    override suspend fun runSeepage(model: SeepageModel): SeepageResult = withContext(Dispatchers.Default) {
        val modelJson = AppJson.encodeToString(SeepageModel.serializer(), model)
        val json = pyCall { py ->
            py.getModule("pocketsim.bridge").callAttr("run_seepage", modelJson, dataDir(), lang(), unitsTag()).toString()
        }
        AppJson.decodeFromString(SeepageResult.serializer(), json)
    }

    override suspend fun runTransient(spec: TransientSpec): TransientResult = withContext(Dispatchers.Default) {
        val specJson = AppJson.encodeToString(TransientSpec.serializer(), spec)
        val json = pyCall { py ->
            py.getModule("pocketsim.bridge").callAttr("run_transient", specJson, dataDir(), lang(), unitsTag()).toString()
        }
        AppJson.decodeFromString(TransientResult.serializer(), json)
    }

    override suspend fun runSolid(model: SolidWallModel): SolidResult = withContext(Dispatchers.Default) {
        val modelJson = AppJson.encodeToString(SolidWallModel.serializer(), model)
        val json = pyCall { py ->
            py.getModule("pocketsim.bridge").callAttr("run_solid", modelJson, dataDir(), lang(), unitsTag()).toString()
        }
        AppJson.decodeFromString(SolidResult.serializer(), json)
    }

    override suspend fun runSolidGeneric(model: SolidGenericModel): SolidGenericResult = withContext(Dispatchers.Default) {
        val modelJson = AppJson.encodeToString(SolidGenericModel.serializer(), model)
        val json = pyCall { py ->
            py.getModule("pocketsim.bridge").callAttr("run_solid_generic", modelJson, dataDir(), lang(), unitsTag(), caseLogTag()).toString()
        }
        AppJson.decodeFromString(SolidGenericResult.serializer(), json)
    }

    override suspend fun runShell(model: ShellVesselModel): SolidGenericResult = withContext(Dispatchers.Default) {
        val modelJson = AppJson.encodeToString(ShellVesselModel.serializer(), model)
        val json = pyCall { py ->
            py.getModule("pocketsim.bridge").callAttr("run_shell", modelJson, dataDir(), lang(), unitsTag(), caseLogTag()).toString()
        }
        // run_vessel 输出与 SolidGenericResult 同构；未建模的额外字段由 ignoreUnknownKeys 忽略
        AppJson.decodeFromString(SolidGenericResult.serializer(), json)
    }

    override suspend fun runBuriedPipe(model: ShellBuriedPipeModel): BuriedPipeResult = withContext(Dispatchers.Default) {
        val modelJson = AppJson.encodeToString(ShellBuriedPipeModel.serializer(), model)
        // Python 入参顺序与 pocketsim.bridge.run_buried_pipe 签名严格一致：
        // model_json, data_dir, case_log_enabled, lang, units
        val json = pyCall { py ->
            py.getModule("pocketsim.bridge")
                .callAttr("run_buried_pipe", modelJson, dataDir(), caseLogTag(), lang(), unitsTag()).toString()
        }
        AppJson.decodeFromString(BuriedPipeResult.serializer(), json)
    }

    override suspend fun exportFramePdf(model: FrameModel, result: FrameResult, filepath: String): String =
        withContext(Dispatchers.Default) {
            val modelJson = AppJson.encodeToString(FrameModel.serializer(), model)
            val resultJson = AppJson.encodeToString(FrameResult.serializer(), result)
            pyCall { py ->
                py.getModule("pocketsim.bridge")
                    .callAttr("export_frame_pdf", modelJson, resultJson, filepath, dataDir(), lang(), unitsTag()).toString()
            }
        }

    override suspend fun exportSolidGenericPdf(
        model: SolidGenericModel,
        result: SolidGenericResult,
        filepath: String
    ): String = withContext(Dispatchers.Default) {
        val modelJson = AppJson.encodeToString(SolidGenericModel.serializer(), model)
        val resultJson = AppJson.encodeToString(SolidGenericResult.serializer(), result)
        pyCall { py ->
            py.getModule("pocketsim.bridge")
                .callAttr("export_solid_generic_pdf", modelJson, resultJson, filepath, dataDir(), lang(), unitsTag())
                .toString()
        }
    }

    override suspend fun listMaterials(system: String): List<MaterialRecord> = withContext(Dispatchers.Default) {
        val json = pyCall { py ->
            py.getModule("pocketsim.bridge")
                .callAttr("list_materials", dataDir(), system).toString()
        }
        AppJson.decodeFromString(ListSerializer(MaterialRecord.serializer()), json)
    }

    override suspend fun upsertMaterial(record: MaterialRecord): MaterialRecord = withContext(Dispatchers.Default) {
        val recordJson = AppJson.encodeToString(MaterialRecord.serializer(), record)
        val json = pyCall { py ->
            py.getModule("pocketsim.bridge")
                .callAttr("upsert_material", recordJson, dataDir()).toString()
        }
        AppJson.decodeFromString(MaterialRecord.serializer(), json)
    }

    override suspend fun deleteMaterial(id: String): Boolean = withContext(Dispatchers.Default) {
        val json = pyCall { py ->
            py.getModule("pocketsim.bridge")
                .callAttr("delete_material", id, dataDir()).toString()
        }
        AppJson.decodeFromString(DeleteMaterialResult.serializer(), json).ok
    }

    override suspend fun logCaseContext(contextJson: String): String = withContext(Dispatchers.Default) {
        pyCall { py ->
            py.getModule("pocketsim.bridge")
                .callAttr("log_case_context", contextJson, dataDir(), caseLogTag()).toString()
        }
    }

    override suspend fun exportCaseLogExcel(): String = withContext(Dispatchers.Default) {
        pyCall { py ->
            py.getModule("pocketsim.bridge")
                .callAttr("export_case_log_excel", dataDir()).toString()
        }
    }

    /**
     * 离线规则解析：pocketsim.parser.parse_problem(text) 返回 Python dict，
     * 经 json.dumps 序列化后按契约解码；ok=false / 字段不符 / 异常 → null（调用方按"未识别"提示）。
     */
    override suspend fun parseProblem(text: String): ParseOutcome? = withContext(Dispatchers.Default) {
        try {
            val json = pyCall { py ->
                val parsed = py.getModule("pocketsim.parser").callAttr("parse_problem", text)
                py.getModule("json").callAttr("dumps", parsed).toString()
            }
            AppJson.decodeFromString(ParseProblemResponse.serializer(), json).toOutcome()
        } catch (e: Exception) {
            null
        }
    }

    /** 材料缓存库目录：App 私有文件目录（context.filesDir） */
    private fun dataDir(): String = context.filesDir.absolutePath

    /** 当前界面语言（"zh"/"en"），供 Python 侧生成对应语言的叙述文案 */
    private fun lang(): String = LocaleManager.currentLanguageCode(context)

    /** 当前单位制标记（"imperial"/"metric"），供 Python 侧换算叙述文案里的单位 */
    private fun unitsTag(): String = if (Units.isImperial) "imperial" else "metric"

    /** 案例埋点开关：仅调试包（BuildConfig.DEBUG）写 case_log.jsonl，正式包传 "0" 完全不写 */
    private fun caseLogTag(): String = if (BuildConfig.DEBUG) "1" else "0"

    /** 统一调用入口：Python 异常 → FeaEngineException（保留关键信息） */
    private fun pyCall(block: (Python) -> String): String = try {
        block(python())
    } catch (e: PyException) {
        throw FeaEngineException(cleanPyMessage(e.message ?: "Python 调用失败"), e)
    } catch (e: Exception) {
        throw FeaEngineException(e.message ?: "引擎调用失败", e)
    }

    /** PyException 的 message 可能带多行 traceback，取末端的异常正文供 UI 展示 */
    private fun cleanPyMessage(raw: String): String {
        val lastLine = raw.lines().lastOrNull { it.isNotBlank() } ?: return raw
        return lastLine.take(300)
    }
}
