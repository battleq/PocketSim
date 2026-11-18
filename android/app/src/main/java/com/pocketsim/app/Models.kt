/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 全部跨层数据模型（Kotlin ↔ Python JSON 契约见 README「Python 侧接口契约」一节）。
 *
 * 设计约定：
 *  - 所有字段均带默认值 + AppJson.ignoreUnknownKeys，Python 侧增删字段不会导致解析崩溃；
 *  - 字段名与 Python 侧 JSON 完全一致（含 max_stress_MPa 这类下划线+大写混合命名），
 *    与 pocketsim Python 包（悬臂搁板/墙上钉挂重/简支层板三模板）逐一对应。
 */
/** 全局 JSON 实例：宽松解析，容错未知字段与类型弱不匹配 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
}

/**
 * 提示词/锚定块专用序列化：省略 null 字段（AppJson 的 encodeDefaults 会把
 * "_case_meta":null 也写出来，锚定块要求埋点键完全不进 LLM 提示词）。
 * 与 Python 求解契约无关——bridge 调用仍用 AppJson（_case_meta 键名必须保留）。
 */
val PromptJson: Json = Json(from = AppJson) {
    explicitNulls = false
}

// =====================================================================
// 模板与参数 schema（对应 pocketsim.bridge.list_templates 的返回元素）
// =====================================================================

/** 场景模板信息（如"悬臂搁板"、"墙上钉挂重"、"简支层板"） */
@Serializable
data class TemplateInfo(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val category: String = "",
    /** 参数 schema：参数确认页按此渲染卡片 */
    val param_schema: List<ParamSpec> = emptyList()
) {
    /** 材料型参数的 spec（模板至多一个；无则返回 null） */
    val materialSpec: ParamSpec? get() = param_schema.firstOrNull { it.type == ParamSpec.TYPE_MATERIAL }
}

/** 单个参数的 schema 描述 */
@Serializable
data class ParamSpec(
    /** 参数键名，如 length_mm / load_kg / material_name */
    val key: String = "",
    /** 展示名，如"悬挑长度" */
    val label: String = "",
    /** 单位，如 mm / kg；材料等无单位参数为空串 */
    val unit: String = "",
    /** 类型：float | int | choice | text | material */
    val type: String = TYPE_FLOAT,
    /** 默认值（数字或字符串；load_kg 这类必填参数可能没有默认值） */
    val default: JsonPrimitive? = null,
    val min: Double? = null,
    val max: Double? = null,
    /** type=choice 时的候选值 */
    val choices: List<String> = emptyList(),
    /** 是否必填（缺失时 AI 会追问、手动模式下必须输入） */
    val required: Boolean = false,
    /** 帮助文案 */
    val help: String = ""
) {
    /** 默认值的展示字符串（无默认值返回空串） */
    fun defaultDisplay(): String = default?.content ?: ""

    companion object {
        const val TYPE_FLOAT = "float"
        const val TYPE_INT = "int"
        const val TYPE_CHOICE = "choice"
        const val TYPE_TEXT = "text"
        const val TYPE_MATERIAL = "material"
    }
}

// =====================================================================
// 材料记录（对应 pocketsim.bridge.list_materials / upsert_material）
// =====================================================================

/** 材料记录：字段名与 Python 材料库 JSON（materials.json）一致 */
@Serializable
data class MaterialRecord(
    val id: String = "",
    /** 中文名（库内唯一），如"松木" */
    val name: String = "",
    val category: String = "",
    /** 弹性模量（MPa） */
    @SerialName("E_MPa") val eMPa: Double = 0.0,
    /** 泊松比 */
    val nu: Double = 0.3,
    /** 密度（kg/m³） */
    val density_kg_m3: Double? = null,
    /** 强度代表值（MPa） */
    val strength_MPa: Double? = null,
    val strength_type: String = "",
    val notes: String = "",
    /** 来源：builtin（内置库）/ web（联网检索）/ user（用户自填） */
    val source: String = "builtin",
    /** 是否可删除：联网检索与用户自填为 true，内置库为 false */
    val deletable: Boolean = false
)

/** 删除材料接口返回（bridge.delete_material） */
@Serializable
data class DeleteMaterialResult(val ok: Boolean = false)

// =====================================================================
// 求解请求（Kotlin → Python：pocketsim.bridge.run_scenario 的入参）
// =====================================================================

/** 一次求解请求。params 的值保留 JSON 原生类型（数字/字符串） */
@Serializable
data class ScenarioRequest(
    val template_id: String = "",
    /** 参数表：普通参数 + material_name（材料以名称传递，Python 侧查库解析） */
    val params: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    /** 网格档位：coarse | medium | fine */
    val mesh_level: String = "medium",
    /** App 私有目录（context.filesDir），Python 侧材料缓存库的存放位置 */
    val data_dir: String = ""
)

// =====================================================================
// 求解结果（Python → Kotlin：run_scenario 的返回）
// =====================================================================

/** FEA 结果：任务要求的六大块 —— metrics / extra_checks / assumptions / render / timing / mesh */
@Serializable
data class FeaResult(
    val template_id: String = "",
    /** 核心指标 */
    val metrics: Metrics = Metrics(),
    /** 额外校核项（强度、挠度建议、墙承压等混合模型的简化校核） */
    val extra_checks: List<CheckItem> = emptyList(),
    /** 建模假设清单（AI 分析与免责的依据） */
    val assumptions: List<String> = emptyList(),
    /** 云图渲染数据（可能为空：极端简化场景无网格） */
    val render: RenderData? = null,
    /** 计时 */
    val timing: Timing = Timing(),
    /** 网格信息（含降级/加密提示） */
    val mesh: MeshInfo = MeshInfo(),
    /** 理论解对比（自校验用） */
    val theory_check: TheoryCheck? = null,
    /** 生活化类比文案（Python 侧生成；为空时 UI 用兜底文案） */
    val analogy: String = "",
    /** 通俗"分析过程"段落（Python 侧确定性生成，离线可用；空列表时 UI 回退端侧规则模板） */
    val narrative: List<String> = emptyList(),
    /** 实际生效参数（默认值回填后的完整参数表） */
    val params_effective: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()
)

/** 核心指标（字段名与 Python 侧保持一致） */
@Serializable
data class Metrics(
    /** 最大变形（mm） */
    val max_deflection_mm: Double = 0.0,
    /** 最大变形的通俗描述 */
    val max_deflection_desc: String = "",
    /** 最大 von Mises 应力（MPa） */
    val max_stress_MPa: Double = 0.0,
    /** 整体安全系数（各强度类校核 sf 的最小值；None 表示无强度校核） */
    val safety_factor: Double? = null,
    /** 结论词：安全 / 注意 / 危险（与 SafetyLogic 阈值一致） */
    val verdict: String = "",
    /** 求解总耗时（ms） */
    val solve_time_ms: Double = 0.0
)

/** 单条校核项（value_MPa 为通用字段名，建议类项中承载的可能是挠度 mm 值） */
@Serializable
data class CheckItem(
    val name: String = "",
    val value_MPa: Double = 0.0,
    val limit_MPa: Double = 0.0,
    val safety_factor: Double = 0.0,
    val pass: Boolean = true
)

/** 云图渲染数据：节点 + 三角形单元 + 节点标量场 + 节点位移 */
@Serializable
data class RenderData(
    /** 默认标量场：von_mises（等效应力） */
    val field: String = "von_mises",
    /** 标量场单位（MPa / mm） */
    val unit: String = "",
    /** 节点坐标 [x, y]（mm，未变形） */
    val nodes: List<List<Double>> = emptyList(),
    /** 三角形单元的节点索引 [i, j, k] */
    val tris: List<List<Int>> = emptyList(),
    /** 节点标量值（与 nodes 等长；field=von_mises 时为 MPa） */
    val values: List<Double> = emptyList(),
    /** 标量位置：node（当前 Python 侧固定输出节点平均应力场） */
    val value_location: String = "node",
    /** 节点位移 [dx, dy]（mm；乘以放大系数得到变形位置） */
    val disp: List<List<Double>>? = null
)

/** 计时信息 */
@Serializable
data class Timing(
    val total_ms: Double = 0.0,
    /** 网格生成 + 组装 */
    val assemble_ms: Double = 0.0,
    /** 线性方程组求解 */
    val solve_ms: Double = 0.0
)

/** 网格信息 */
@Serializable
data class MeshInfo(
    /** 总自由度数 */
    val dofs: Int = 0,
    /** 单元数 */
    val elements: Int = 0,
    /** 实际使用的网格档位 */
    val mesh_level: String = "medium",
    /** 自动调整："downgraded"（超时/内存自动降粗）| "refined"（收敛自检自动加密）| null */
    val adjusted: String? = null,
    /** 调整原因（展示给用户） */
    val adjusted_reason: String? = null,
    /** 求解器标识，如 "scikit-fem/CST" */
    val solver: String = ""
)

/** 理论解对比（误差 ≤5% 为达标，用于持续校验） */
@Serializable
data class TheoryCheck(
    val max_deflection_mm: Double = 0.0,
    val stress_MPa: Double = 0.0,
    /** FEA 挠度相对理论解的误差百分比 */
    val error_pct: Double = 0.0
)

// =====================================================================
// AI 解析结果（LlmClient → AiParser → ParamConfirm 的数据流）
// =====================================================================

/** LLM 输出的结构化解析结果 */
@Serializable
data class AiParseResult(
    val template_id: String = "",
    /** 已抽取的参数（值为 JSON 原生类型） */
    val params: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    /** 仍缺失的参数键名 */
    val missing_params: List<String> = emptyList(),
    /** LLM 生成的追问（一次最多取 2 条，见 FollowUpLogic） */
    val questions: List<String> = emptyList()
)

/**
 * 通用框架建模模式下 LLM 的输出契约（frameMode，见 ChatSession.FRAME_SYSTEM_PROMPT）：
 * {"ready":bool,"questions":[...],"answer":"...","model":{...FrameModel...}}
 * 流体问题（风把墙吹倒）：kind="flow"，model 为 null，flow 给出墙体与风速。
 */
@Serializable
data class AiFrameResult(
    /** 信息是否足以建立完整模型 */
    val ready: Boolean = false,
    /** 模型类别：frame（梁杆框架，缺省）/ flow（风荷载墙体）/ shell_buried_pipe（埋地管）
     *  / out_of_scope（超出软件分析范畴，此时 ready=false 且 [category] 给出任务类别） */
    val kind: String = "frame",
    /** 当 kind="out_of_scope" 时，由 LLM 给出的简短任务类别名（如「电磁-结构耦合」），
     *  用于拼接待客话术；其他 kind 下为空串。缺省空串，向后兼容。 */
    val category: String = "",
    /** 信息不足时的追问（每轮 ≤2 条） */
    val questions: List<String> = emptyList(),
    /**
     * 助理的通俗回答/说明：建模模式一般留空；结果追问页（FrameFollowUpSession）用于
     * 承载纯问答的回答，或修改模型时说明改了什么
     */
    val answer: String = "",
    /**
     * 带图建模时的几何观测（杆件清单/连接关系/尺寸比例/多图一致性结论），可选字段：
     * 仅用于内部稳定建模（坐标须与观测一致、多图矛盾时追问而非强行建模），不展示给用户；
     * 不带图或纯文字建模时省略（缺省空串）。向后兼容，不破坏旧 JSON 解析。
     */
    val observation: String = "",
    /** 建立的框架模型（ready=true 且 kind=frame 时必填） */
    val model: FrameModel? = null,
    /** 流体模型（ready=true 且 kind=flow 时必填） */
    val flow: FlowModel? = null,
    /** 渗流模型（ready=true 且 kind=seepage 时必填） */
    val seepage: SeepageModel? = null,
    /** 时间历程参数（ready=true 且 kind=transient 时必填） */
    val transient: TransientSpec? = null,
    /** 三维实体模型（ready=true 且 kind=solid 时必填） */
    val solid: SolidWallModel? = null,
    /** 通用三维实体模型（ready=true 且 kind=solid_generic 时必填） */
    val solid_generic: SolidGenericModel? = null,
    /** 薄壁容器模型（ready=true 且 kind=shell_vessel / shell_tank 时必填） */
    val shell: ShellVesselModel? = null,
    /** 埋地管模型（ready=true 且 kind=shell_buried_pipe 时必填） */
    val shell_buried_pipe: ShellBuriedPipeModel? = null
)

// =====================================================================
// 离线规则解析（pocketsim.parser.parse_problem，无需网络与 LLM Key）
// =====================================================================

/** 离线规则解析成功产物（契约：{"ok":true,"template_id":...,"params":{...},"missing":[...],"confidence":f}） */
data class ParseOutcome(
    /** 命中的模板 id */
    val templateId: String,
    /** 已抽取参数（值为 JSON 原生类型） */
    val params: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    /** 未抽取到的参数键（参数确认页标"待补充"） */
    val missing: List<String> = emptyList(),
    /** 解析置信度 0~1 */
    val confidence: Double = 0.0
)

/** pocketsim.parser.parse_problem 返回 dict 的 JSON 契约（Python 侧经 json.dumps 跨边界） */
@Serializable
data class ParseProblemResponse(
    val ok: Boolean = false,
    val template_id: String = "",
    val params: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    val missing: List<String> = emptyList(),
    val confidence: Double = 0.0,
    /** ok=false 时的失败原因（UI 用固定文案提示，不直接展示） */
    val reason: String = ""
) {
    /** ok=true 且 template_id 非空 → [ParseOutcome]；否则视为识别失败返回 null */
    fun toOutcome(): ParseOutcome? =
        if (ok && template_id.isNotBlank()) {
            ParseOutcome(
                templateId = template_id,
                params = params,
                missing = missing,
                confidence = confidence
            )
        } else null
}

/** 参数来源（参数卡上的标签） */
enum class ParamSource {
    /** 模板内置默认值 */
    BUILTIN,
    /** AI 从用户描述中抽取 */
    AI,
    /** 联网检索的材料参数（MaterialLookup） */
    WEB,
    /** 用户手动输入/修改 */
    USER,
    /** 离线规则解析器从用户描述中抽取（pocketsim.parser，无需网络与 LLM Key） */
    OFFLINE;

    companion object {
        /** 从 Python 侧 source 字符串解析（unknown 容错为 BUILTIN） */
        fun fromString(s: String?): ParamSource = when (s?.trim()?.lowercase()) {
            "builtin", "内置" -> BUILTIN
            "ai" -> AI
            "web", "联网检索" -> WEB
            "user", "自填" -> USER
            "offline", "离线识别" -> OFFLINE
            else -> BUILTIN
        }
    }
}

/** AI / 离线解析完成后交给参数确认页的载荷 */
data class ParsedScenario(
    val template: TemplateInfo,
    /** 已抽取参数（字符串形式，键与 param_schema 对齐） */
    val params: Map<String, String>,
    /** 各参数来源（AI 流程缺省为 AI；离线预填为 OFFLINE） */
    val sources: Map<String, ParamSource>,
    /** 解析器未能抽取的参数键（参数确认页标"待补充"；AI 对话流程不填充） */
    val missing: List<String> = emptyList(),
    /** true = 离线规则解析产物（pocketsim.parser）；false = AI 对话解析 */
    val offline: Boolean = false
)

// =====================================================================
// 2D 梁杆框架模型（AI 建模模式：mode=frame；对应 pocketsim.frame 契约）
// =====================================================================

/** 框架节点（坐标单位 mm；2D 时 y 轴向上，3D 时 z 轴竖直向上、重力沿 -z） */
@Serializable
data class FrameNode(
    val id: Int = 0,
    val x: Double = 0.0,
    val y: Double = 0.0,
    /** 3D 竖向坐标（mm，向上为正）；2D 模型恒为 0 */
    val z: Double = 0.0
)

/** 杆件开孔：孔轴沿截面宽度方向贯穿；d_mm 为孔径/洞高（截面高度方向），at_mm 为孔心距节点 i 的轴向距离 */
@Serializable
data class FrameHole(
    /** 孔心距节点 i 的轴向距离（mm） */
    val at_mm: Double = 0.0,
    /** 形状：circle（圆孔）/ rect（矩形洞），缺省 circle */
    val shape: String = "circle",
    /** 孔径（圆孔）/ 洞高（矩形洞），mm */
    val d_mm: Double = 0.0,
    /** 是否贯穿（当前仅支持 true） */
    val through: Boolean = true
)

/** 框架杆件（矩形截面：width_b 面外宽度 × depth_h 面内高度；圆管按外径 + 壁厚，单位 mm） */
@Serializable
data class FrameMember(
    val id: Int = 0,
    /** 端节点 id（nodes 中必须存在） */
    val i: Int = 0,
    val j: Int = 0,
    val width_b: Double = 0.0,
    val depth_h: Double = 0.0,
    /** 材料中文名（Python 侧查材料库） */
    val material: String = "",
    /** 空心截面壁厚（mm）：>0 按空心矩形/方管或圆管（外形减内腔）计算，0 为实心 */
    val wall_t: Double = 0.0,
    /** 杆上开孔（实心/空心矩形截面可用，按净截面 + 孔边应力集中校核） */
    val holes: List<FrameHole> = emptyList(),
    /** 截面形状："rect"（默认矩形）/ "circle"（圆形/圆管，按 outer_diameter_mm） */
    val shape: String = "rect",
    /** 圆管/圆截面的外径（mm），shape=circle 时必填 */
    val outer_diameter_mm: Double = 0.0
) {
    val isCircle: Boolean get() = shape.equals("circle", true) || shape.equals("tube", true) ||
        shape.equals("pipe", true) || shape.equals("round", true)

    /** 截面文案：矩形 / 矩形管 / 圆管 / 实心圆 */
    val sectionText: String get() = if (isCircle) {
        if (wall_t > 0.0) "⌀${fmt(outer_diameter_mm)}×${fmt(wall_t)} mm（圆管）"
        else "⌀${fmt(outer_diameter_mm)} mm（实心圆）"
    } else if (wall_t > 0.0) {
        "${fmt(width_b)}×${fmt(depth_h)}×${fmt(wall_t)} mm（空心）"
    } else {
        "${fmt(width_b)}×${fmt(depth_h)} mm"
    }

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
}

/** 支座：fixed（固定）/ pinned（铰接）/ roller（滚轴·仅竖向约束）；3D 另支持 roller_x / roller_y */
@Serializable
data class FrameSupport(
    val node: Int = 0,
    val type: String = "pinned",
    /** 额外约束自由度（3D 用，如 ["rx"] 约束杆件扭转刚体模态；2D 留空） */
    val fix: List<String> = emptyList()
)

/**
 * 载荷（扁平结构兼容四种类型：force 节点集中力 N / udl 杆件均布 N·mm⁻¹ /
 * area 面荷载（按作用杆件长度自动分配为均布）/ thermal 杆件温度变化 °C）。
 * 2D：竖直向下为 -y；3D：竖直向下为 -z（重力沿 -z）。
 */
@Serializable
data class FrameLoad(
    val type: String = "force",
    /** force：作用节点 id */
    val node: Int? = null,
    /** udl / thermal：作用杆件 id */
    val member: Int? = null,
    /** area：承托面荷载的杆件 id 列表（椅子坐面、桌面等面状构件的传力杆件） */
    val members: List<Int>? = null,
    /** area：作用在该组杆件上的总力（N，向下为负）；按各杆件长度占比分配为均布载荷 */
    val total_N: Double = 0.0,
    /** force：全局坐标分量（N） */
    val fx: Double = 0.0,
    val fy: Double = 0.0,
    /** 3D：竖向力分量（N，向下为负） */
    val fz: Double = 0.0,
    /** force：力偶（N·mm），mx/my 为 3D 补充 */
    val mx: Double = 0.0,
    val my: Double = 0.0,
    val mz: Double = 0.0,
    /** udl：全局线载荷（N/mm）；2D 用 qy，3D 用 qx/qy/qz */
    val qx: Double = 0.0,
    val qy: Double = 0.0,
    val qz: Double = 0.0,
    /** wind：迎风节点 id 列表（净风力由代码侧换算后均摊到这些节点） */
    val nodes: List<Int>? = null,
    /** wind：风速（m/s）；与 beaufort 二选一互斥 */
    val wind_speed_m_s: Double? = null,
    /** wind：蒲福风级（0~17）；与 wind_speed_m_s 二选一互斥 */
    val beaufort: Int? = null,
    /** wind：受力方向（2D 为 x/y，3D 为 x/y/z），风沿该轴正向吹 */
    val dir: String = "x",
    /** wind：受风投影面积（m²，>0） */
    val area_m2: Double = 0.0,
    /** wind：净体型系数（<=0 时求解器取默认 1.2） */
    val cp: Double = 0.0,
    /** thermal：杆件温度变化（°C，升温为正、降温为负，基准 20°C） */
    val delta_temp: Double = 0.0
)

/**
 * AI 生成的完整框架模型（预览确认页渲染 + bridge.run_frame / run_frame3d 的入参）。
 * dim=2：平面框架（3 DOF/节点）；dim=3：空间框架（6 DOF/节点，z 为竖向、重力 -z）。
 */
@Serializable
data class FrameModel(
    val name: String = "框架模型",
    val nodes: List<FrameNode> = emptyList(),
    val members: List<FrameMember> = emptyList(),
    val supports: List<FrameSupport> = emptyList(),
    val loads: List<FrameLoad> = emptyList(),
    /** 维度：2 平面 / 3 空间（缺省 2，保证旧模型行为不变） */
    val dim: Int = 2,
    /** 案例埋点上下文（不参与求解，由 ChatSession 注入；JSON 键 _case_meta，bridge 弹出后不入求解器） */
    @SerialName("_case_meta") val case_meta: CaseMeta? = null
)

// =====================================================================
// 框架求解结果（Python → Kotlin：pocketsim.bridge.run_frame 的返回）
// =====================================================================

/** 单杆件结果（N 拉为正；compress_kN 为压力幅值） */
@Serializable
data class FrameMemberResult(
    val id: Int = 0,
    val material: String = "",
    /** 截面尺寸文案（如 "30×40 mm"） */
    val section: String = "",
    /** 端部轴力（N） */
    val N_N: Double = 0.0,
    /** 包络最大弯矩（N·mm） */
    val M_max_Nmm: Double = 0.0,
    /** 弯曲+轴向保守叠加应力（MPa） */
    val sigma_MPa: Double = 0.0,
    /** 3D：绕局部 y 的包络弯矩（N·mm） */
    val My_max_Nmm: Double = 0.0,
    /** 3D：绕局部 z 的包络弯矩（N·mm） */
    val Mz_max_Nmm: Double = 0.0,
    /** 3D：包络扭矩（N·mm） */
    val torsion_Nmm: Double = 0.0,
    /** 3D：扭转剪应力（MPa） */
    val tau_MPa: Double = 0.0,
    /** 3D：冯·米塞斯等效应力（MPa，含扭转） */
    val sigma_vm_MPa: Double = 0.0,
    /** 最大压力（kN，受拉杆为 0） */
    val compress_kN: Double = 0.0,
    /** 强度利用率（sigma / 材料强度） */
    val util: Double? = null,
    /** 杆件温度变化（°C，无温度载荷时为 null） */
    val delta_temp: Double? = null,
    /** 杆上开孔（无开孔时为空） */
    val holes: List<FrameHole> = emptyList(),
    /** 孔边应力集中系数（无开孔时为 1.0） */
    val hole_kt: Double = 1.0,
    /** 开孔处净截面面积（mm²，无开孔时为 null） */
    val A_net_mm2: Double? = null
)

/** UI 变形折线：pts 每项 [x, y, dx, dy]（2D）或 [x, y, z, dx, dy, dz]（3D），mm；放大系数由 UI 决定 */
@Serializable
data class FramePolyline(
    val member: Int = 0,
    val pts: List<List<Double>> = emptyList()
)

/** 节点位移（2D 用 dx/dy/rz；3D 另含 dz/rx/ry） */
@Serializable
data class FrameNodeDisp(
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val rz: Double = 0.0,
    /** 3D：竖向位移（mm） */
    val dz: Double = 0.0,
    /** 3D：绕 x 转角（rad） */
    val rx: Double = 0.0,
    /** 3D：绕 y 转角（rad） */
    val ry: Double = 0.0
)

/** 热分析分区（无温度载荷时整个对象为 null） */
@Serializable
data class FrameThermalInfo(
    val has_thermal: Boolean = false,
    /** 最大 |ΔT|（°C） */
    val max_delta_temp: Double = 0.0,
    /** 是否有杆件超出材料耐温 */
    val over_temp: Boolean = false,
    /** 超温提醒文案 */
    val note: String = "",
    /** 每杆 ΔT（key=杆件 id 字符串） */
    val by_member: Map<String, Double> = emptyMap()
)

/** 框架求解结果（字段名与 Python frame.run_frame 输出一一对应） */
@Serializable
data class FrameResult(
    val kind: String = "frame",
    val ok: Boolean = true,
    val model_name: String = "",
    val n_nodes: Int = 0,
    val n_members: Int = 0,
    val n_supports: Int = 0,
    /** 最大变形（mm，总位移幅值） */
    val max_disp_mm: Double = 0.0,
    val max_disp_desc: String = "",
    val max_stress_MPa: Double = 0.0,
    /** 受力最大的杆件 id */
    val governing_member: Int = 0,
    val safety_factor: Double = 0.0,
    /** 安全 / 注意 / 危险 */
    val verdict: String = "",
    /** 欧拉屈曲利用率（受压杆最大值） */
    val buckling_ratio: Double = 0.0,
    val buckling_note: String = "",
    /** 热分析分区（无温度载荷时为 null） */
    val thermal: FrameThermalInfo? = null,
    val members: List<FrameMemberResult> = emptyList(),
    val node_disp: List<FrameNodeDisp> = emptyList(),
    val polylines: List<FramePolyline> = emptyList(),
    /** 通俗"分析过程"段落（Python 侧确定性生成） */
    val narrative: List<String> = emptyList(),
    val solve_ms: Double = 0.0
)

// =====================================================================
// 温度-载荷时间历程（P5；对应 pocketsim.transient 契约）
// =====================================================================

/**
 * 时间历程参数（AI 建模产物 + bridge.run_transient 入参）。
 *
 * frame 是"恒载工况"：其中的机械载荷（挂重/均布）在整个历程内保持不变；
 * 温度在 duration_s 内从 temp_base_c 升到 temp_peak_c 再回到 temp_base_c。
 * members 为参与温度变化的杆件 id，null 表示全部杆件。
 */
@Serializable
data class TransientSpec(
    val name: String = "温度历程分析",
    val frame: FrameModel = FrameModel(),
    /** 持续时间（秒） */
    val duration_s: Double = 3600.0,
    /** 时间步数（5~40，实际计算 steps+1 个时刻） */
    val steps: Int = 12,
    /** 起始环境温度（°C，与框架模块的参考温度一致，默认 20） */
    val temp_base_c: Double = 20.0,
    /** 峰值温度（°C） */
    val temp_peak_c: Double = 80.0,
    /** 温度曲线：triangle 匀速升降温 / sine 余弦平滑升降温 */
    val profile: String = "triangle",
    /** 参与温度变化的杆件 id；null 表示全部杆件 */
    val members: List<Int>? = null,
    /** 案例埋点上下文（不参与求解，由 ChatSession 注入；JSON 键 _case_meta） */
    @SerialName("_case_meta") val case_meta: CaseMeta? = null
)

/** 时间历程曲线（各数组等长，长度 = steps + 1） */
@Serializable
data class TransientCurves(
    val t_s: List<Double> = emptyList(),
    val temp_c: List<Double> = emptyList(),
    val max_disp_mm: List<Double> = emptyList(),
    val max_stress_MPa: List<Double> = emptyList(),
    val safety_factor: List<Double> = emptyList()
)

/** 某一时刻的完整快照（变形折线/杆件结果用于时间滑杆回放） */
@Serializable
data class TransientFrame(
    val index: Int = 0,
    val t_s: Double = 0.0,
    val temp_c: Double = 0.0,
    val delta_temp_c: Double = 0.0,
    val max_disp_mm: Double = 0.0,
    val max_disp_desc: String = "",
    val max_stress_MPa: Double = 0.0,
    val safety_factor: Double = 0.0,
    val buckling_ratio: Double = 0.0,
    val governing_member: Int = 0,
    /** 安全 / 注意 / 危险 */
    val verdict: String = "",
    val over_temp: Boolean = false,
    /** 该时刻的自由热伸长量（mm，α·ΔT·L 的最大值）：静定结构的温度效应主要体现于此 */
    val thermal_elong_mm: Double = 0.0,
    val node_disp: List<FrameNodeDisp> = emptyList(),
    val polylines: List<FramePolyline> = emptyList(),
    val members: List<FrameMemberResult> = emptyList()
)

/** 最不利时刻（应力最大） */
@Serializable
data class TransientWorst(
    val index: Int = 0,
    val t_s: Double = 0.0,
    val temp_c: Double = 0.0,
    val max_stress_MPa: Double = 0.0,
    val max_disp_mm: Double = 0.0,
    val safety_factor: Double = 0.0,
    val governing_member: Int = 0,
    val verdict: String = ""
)

/** 变形最大时刻 */
@Serializable
data class TransientPeakDisp(
    val index: Int = 0,
    val t_s: Double = 0.0,
    val temp_c: Double = 0.0,
    val max_disp_mm: Double = 0.0,
    val max_disp_desc: String = ""
)

/** 安全系数最低时刻 */
@Serializable
data class TransientSafetyMin(
    val index: Int = 0,
    val t_s: Double = 0.0,
    val temp_c: Double = 0.0,
    val safety_factor: Double = 0.0
)

/** 时间历程求解结果（字段名与 Python transient.run_transient 输出一一对应） */
@Serializable
data class TransientResult(
    val kind: String = "transient",
    val ok: Boolean = true,
    val model_name: String = "",
    val scenario: String = "thermal_cycle",
    val duration_s: Double = 0.0,
    /** "2.0 小时" 这类可读文案 */
    val duration_desc: String = "",
    val steps: Int = 0,
    val n_samples: Int = 0,
    val profile: String = "triangle",
    val profile_label: String = "",
    val temp_base_c: Double = 0.0,
    val temp_peak_c: Double = 0.0,
    val temp_amplitude_c: Double = 0.0,
    /** 参与温度变化杆件的材料长期耐温（°C；无限制时为 null） */
    val temp_limit_c: Double? = null,
    val limit_material: String = "",
    val thermal_members: List<Int> = emptyList(),
    val n_nodes: Int = 0,
    val n_members: Int = 0,
    val n_supports: Int = 0,
    /** 是否有时刻超过材料长期耐温 */
    val over_temp: Boolean = false,
    /** 静定结构：温度变化不产生温度应力，各时刻应力/安全系数基本不变（UI 需给出解释） */
    val thermal_stress_constant: Boolean = false,
    /** 上述情况的力学解释文案（非静定结构为空串） */
    val thermal_stress_note: String = "",
    /** 整个历程中的最大自由热伸长量（mm） */
    val max_thermal_elong_mm: Double = 0.0,
    val worst: TransientWorst = TransientWorst(),
    val peak_disp: TransientPeakDisp = TransientPeakDisp(),
    val safety_min: TransientSafetyMin = TransientSafetyMin(),
    val max_disp_mm: Double = 0.0,
    val max_stress_MPa: Double = 0.0,
    val safety_factor: Double = 0.0,
    /** 整个历程中最严重的档位 */
    val verdict: String = "",
    val curves: TransientCurves = TransientCurves(),
    val frames: List<TransientFrame> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val narrative: List<String> = emptyList()
)

// =====================================================================
// 流体模型（风荷载墙体，P4；对应 pocketsim.flow 契约）
// =====================================================================

/** 墙体几何（单位米：墙高 h、墙长（沿墙方向）length、墙厚 t） */
@Serializable
data class FlowWall(
    val height_m: Double = 0.0,
    val length_m: Double = 0.0,
    val thickness_m: Double = 0.0
)

/**
 * 风荷载墙体模型（AI 建模产物 + bridge.run_flow 入参）。
 * 风速与风级二选一：wind_speed_m_s 优先，其次 beaufort（0~17 级）。
 */
@Serializable
data class FlowModel(
    val name: String = "风荷载墙体",
    /** 场景：wind_wall（当前唯一支持） */
    val kind: String = "wind_wall",
    val wall: FlowWall = FlowWall(),
    val wind_speed_m_s: Double? = null,
    val beaufort: Int? = null,
    /** 墙体材料中文名（砖砌体/混凝土C20/松木…） */
    val material: String = "",
    /** 密度覆盖（kg/m³，缺省取材料库） */
    val density_kg_m3: Double? = null,
    /** 墙底摩擦系数（缺省 0.6） */
    val friction_coeff: Double? = null,
    val mesh_level: String = "medium",
    /** 案例埋点上下文（不参与求解，由 ChatSession 注入；JSON 键 _case_meta） */
    @SerialName("_case_meta") val case_meta: CaseMeta? = null
)

/** 流场云图：节点/三角形/节点压力 + 流线折线 + 墙体轮廓 + 域范围 */
@Serializable
data class FlowRenderData(
    val field: String = "pressure",
    val unit: String = "Pa",
    /** 节点坐标 [x, y]（m） */
    val nodes: List<List<Double>> = emptyList(),
    val tris: List<List<Int>> = emptyList(),
    /** 节点压力（Pa，已按 ±动压限幅） */
    val values: List<Double> = emptyList(),
    val value_location: String = "node",
    /** 流线折线（每条为 [[x, y], ...]，m） */
    val streamlines: List<List<List<Double>>> = emptyList(),
    /** 墙体轮廓：x0~x1、0~y1（m） */
    val wall: FlowWallGeom? = null,
    /** 流体域范围 [xmin, ymin, xmax, ymax]（m） */
    val bounds: List<Double> = emptyList(),
    val speed_max: Double = 0.0
)

/** 墙体轮廓（m） */
@Serializable
data class FlowWallGeom(
    val x0: Double = 0.0,
    val x1: Double = 0.0,
    val y1: Double = 0.0
)

/** 单条流体校核（抗倾覆/抗滑移/抗弯） */
@Serializable
data class FlowCheck(
    val name: String = "",
    val value: Double = 0.0,
    val limit: Double = 0.0,
    val unit: String = "",
    val safety_factor: Double = 0.0,
    val pass: Boolean = true
)

/** 墙体规模信息 */
@Serializable
data class FlowWallInfo(
    val height_m: Double = 0.0,
    val length_m: Double = 0.0,
    val thickness_m: Double = 0.0,
    val area_m2: Double = 0.0,
    val weight_kN: Double = 0.0
)

/** 风荷载求解结果（字段名与 Python flow.run_flow 输出一一对应） */
@Serializable
data class FlowResult(
    val kind: String = "flow",
    val ok: Boolean = true,
    val model_name: String = "",
    val scenario: String = "wind_wall",
    val material: String = "",
    val density_kg_m3: Double = 0.0,
    val wall: FlowWallInfo = FlowWallInfo(),
    val wind_speed_m_s: Double = 0.0,
    val beaufort: Int = 0,
    val beaufort_label: String = "",
    /** 动压 ½ρU²（Pa） */
    val q_Pa: Double = 0.0,
    val cp_net: Double = 1.2,
    /** 墙面平均风压（Pa，已含体型系数） */
    val pressure_Pa: Double = 0.0,
    val force_N: Double = 0.0,
    val force_per_m: Double = 0.0,
    val overturn_sf: Double = 0.0,
    val slide_sf: Double = 0.0,
    val bend_MPa: Double = 0.0,
    val bend_limit_MPa: Double = 0.0,
    val bend_sf: Double = 0.0,
    /** 综合安全系数（三项校核最小值） */
    val safety_factor: Double = 0.0,
    val verdict: String = "",
    /** 临界风速与该风速对应风级 */
    val critical_speed_m_s: Double = 0.0,
    val critical_beaufort: Int = 0,
    val critical_mode: String = "",
    val stagnation_Pa: Double = 0.0,
    val min_pressure_Pa: Double = 0.0,
    val max_speed_ratio: Double = 1.0,
    val checks: List<FlowCheck> = emptyList(),
    val weak_part: String = "",
    val failure_modes: List<String> = emptyList(),
    val advice: List<String> = emptyList(),
    val render: FlowRenderData? = null,
    val assumptions: List<String> = emptyList(),
    val narrative: List<String> = emptyList(),
    val solve_ms: Double = 0.0
)

// =====================================================================
// 渗流模型（大坝坝基渗流，P4；对应 pocketsim.seepage 契约）
// =====================================================================

/** 坝体梯形截面（单位米：坝高、坝顶宽、上下游坡比——每 1 m 高对应的水平宽度） */
@Serializable
data class SeepageDam(
    val height_m: Double = 0.0,
    val crest_width_m: Double = 0.0,
    val upstream_slope: Double = 0.0,
    val downstream_slope: Double = 0.7
)

/**
 * 大坝渗流模型（AI 建模产物 + bridge.run_seepage 入参）。
 * 水位以坝基底面为基准（m）；渗透系数与坝基深度可省略，Python 侧取默认值。
 */
@Serializable
data class SeepageModel(
    val name: String = "大坝渗流",
    /** 场景：dam_seepage（当前唯一支持） */
    val kind: String = "dam_seepage",
    val dam: SeepageDam = SeepageDam(),
    val upstream_level_m: Double = 0.0,
    val downstream_level_m: Double = 0.0,
    /** 坝体材料中文名（混凝土C20/砖砌体…） */
    val material: String = "",
    /** 坝基渗透系数（m/s，缺省 1e-6） */
    val foundation_permeability_m_s: Double? = null,
    /** 坝基深度（m，缺省 1.5 倍坝高） */
    val foundation_depth_m: Double? = null,
    /** 坝基摩擦系数（缺省 0.6） */
    val friction_coeff: Double? = null,
    val mesh_level: String = "medium",
    /** 案例埋点上下文（不参与求解，由 ChatSession 注入；JSON 键 _case_meta） */
    @SerialName("_case_meta") val case_meta: CaseMeta? = null
)

/** 坝体规模信息（单宽指标，长度沿坝轴方向取 1 m） */
@Serializable
data class SeepageDamInfo(
    val height_m: Double = 0.0,
    val crest_width_m: Double = 0.0,
    val base_width_m: Double = 0.0,
    val upstream_slope: Double = 0.0,
    val downstream_slope: Double = 0.0,
    val area_m2: Double = 0.0,
    val weight_kN_per_m: Double = 0.0
)

/** 单条渗流校核（抗滑/抗倾覆/出逸比降） */
@Serializable
data class SeepageCheck(
    val name: String = "",
    val value: Double = 0.0,
    val limit: Double = 0.0,
    val unit: String = "",
    val safety_factor: Double = 0.0,
    val pass: Boolean = true
)

/** 坝体轮廓多边形（坝踵→坝顶→坝趾，m） */
@Serializable
data class SeepageDamGeom(
    val points: List<List<Double>> = emptyList()
)

/** 渗流场云图：节点/三角形/节点水头 + 渗流流线 + 坝体轮廓 + 域范围 */
@Serializable
data class SeepageRenderData(
    val field: String = "head",
    val unit: String = "m",
    /** 节点坐标 [x, y]（m，坝基区域 y≤0，坝体轮廓另给） */
    val nodes: List<List<Double>> = emptyList(),
    val tris: List<List<Int>> = emptyList(),
    /** 节点水头（m） */
    val values: List<Double> = emptyList(),
    val value_location: String = "node",
    /** 渗流流线（每条为 [[x, y], ...]，m） */
    val streamlines: List<List<List<Double>>> = emptyList(),
    /** 坝体轮廓（坝体不透水，不参与渗流网格） */
    val dam: SeepageDamGeom? = null,
    /** 显示范围 [xmin, ymin, xmax, ymax]（m，含坝高） */
    val bounds: List<Double> = emptyList(),
    val head_min: Double = 0.0,
    val head_max: Double = 0.0
)

/** 大坝渗流结果（字段名与 Python seepage.run_seepage 输出一一对应） */
@Serializable
data class SeepageResult(
    val kind: String = "seepage",
    val ok: Boolean = true,
    val model_name: String = "",
    val scenario: String = "dam_seepage",
    val material: String = "",
    val density_kg_m3: Double = 0.0,
    val dam: SeepageDamInfo = SeepageDamInfo(),
    val upstream_level_m: Double = 0.0,
    val downstream_level_m: Double = 0.0,
    val head_diff_m: Double = 0.0,
    /** 上下游净水压力（kN/m） */
    val water_pressure_kN_per_m: Double = 0.0,
    /** 坝底扬压力（kN/m） */
    val uplift_kN_per_m: Double = 0.0,
    /** 扬压力 / 坝体自重 */
    val uplift_ratio: Double = 0.0,
    val permeability_m_s: Double = 0.0,
    val foundation_depth_m: Double = 0.0,
    /** 单宽渗流量（m²/s）与 L/s·m */
    val seepage_q_m3_s: Double = 0.0,
    val seepage_q_l_s: Double = 0.0,
    val exit_gradient: Double = 0.0,
    val exit_gradient_allow: Double = 0.5,
    val piping_sf: Double = 0.0,
    val slide_sf: Double = 0.0,
    val overturn_sf: Double = 0.0,
    /** 综合安全系数（三项校核最小值） */
    val safety_factor: Double = 0.0,
    val verdict: String = "",
    /** 临界上游水位（已按坝高封顶）与未封顶值 */
    val critical_level_m: Double = 0.0,
    val critical_level_raw_m: Double = 0.0,
    val critical_mode: String = "",
    val checks: List<SeepageCheck> = emptyList(),
    val weak_part: String = "",
    val failure_modes: List<String> = emptyList(),
    val advice: List<String> = emptyList(),
    val render: SeepageRenderData? = null,
    val assumptions: List<String> = emptyList(),
    val narrative: List<String> = emptyList(),
    val solve_ms: Double = 0.0
)

// =====================================================================
// 三维实体模型（风压墙体，V0.5.0；对应 pocketsim.solid 契约）
// =====================================================================

/** 墙体几何（单位米：墙高 h、墙长（沿墙方向）length、墙厚 t） */
@Serializable
data class SolidWall(
    val length_m: Double = 0.0,
    val height_m: Double = 0.0,
    val thickness_m: Double = 0.0
)

/** 显式网格分段（缺省时按 mesh_level 自动选取） */
@Serializable
data class SolidMesh(
    val nx: Int = 0,
    val ny: Int = 0,
    val nz: Int = 0
)

/**
 * 三维实体墙体模型（AI 建模产物 + bridge.run_solid 入参）。
 *
 * 风压来源三选一：pressure_Pa（直接均布压力，优先）> wind_speed_m_s > beaufort。
 * E_MPa / nu / density_kg_m3 可覆盖材料库代表值（缺省取库值）。
 */
@Serializable
data class SolidWallModel(
    val name: String = "三维实体墙体",
    /** 场景：solid_wall（当前唯一支持） */
    val kind: String = "solid_wall",
    val wall: SolidWall = SolidWall(),
    /** 墙体材料中文名（砖砌体/混凝土C20/松木…） */
    val material: String = "",
    /** 弹性模量覆盖（MPa） */
    @SerialName("E_MPa") val eMPa: Double? = null,
    /** 泊松比覆盖 */
    val nu: Double? = null,
    /** 密度覆盖（kg/m³） */
    val density_kg_m3: Double? = null,
    /** 直接均布风压（Pa，优先于风速） */
    val pressure_Pa: Double? = null,
    val wind_speed_m_s: Double? = null,
    /** 风级（0~17），仅当未给风速/风压时使用 */
    val beaufort: Int? = null,
    /** 净体型系数（缺省 1.2） */
    val cp_net: Double? = null,
    /** 网格档位：coarse | medium | fine | ultra | custom（自定义分段时由 mesh 决定） */
    val mesh_level: String = "medium",
    /** 显式分段（提供时优先于 mesh_level） */
    val mesh: SolidMesh? = null,
    /** 案例埋点上下文（不参与求解，由 ChatSession 注入；JSON 键 _case_meta） */
    @SerialName("_case_meta") val case_meta: CaseMeta? = null
)

/** 材料参数（求解实际采用值） */
@Serializable
data class SolidMaterialParams(
    @SerialName("E_MPa") val eMPa: Double = 0.0,
    val nu: Double = 0.0,
    val density_kg_m3: Double = 0.0,
    val strength_MPa: Double = 0.0,
    val tension_limit_MPa: Double = 0.0
)

/** 墙体规模信息（m²、m³、kN） */
@Serializable
data class SolidGeometry(
    val length_m: Double = 0.0,
    val height_m: Double = 0.0,
    val thickness_m: Double = 0.0,
    val area_m2: Double = 0.0,
    val volume_m3: Double = 0.0,
    val weight_kN: Double = 0.0
)

/** 风荷载信息（type=wind 按风速/风级，pressure=直接给定均布压力） */
@Serializable
data class SolidLoad(
    val type: String = "wind",
    val wind_speed_m_s: Double = 0.0,
    val beaufort: Int = 0,
    val beaufort_label: String = "",
    /** 动压 ½ρU²（Pa） */
    val q_Pa: Double = 0.0,
    val cp_net: Double = 1.2,
    /** 墙面平均风压（Pa，已含体型系数） */
    val pressure_Pa: Double = 0.0,
    val force_N: Double = 0.0
)

/** 核心指标（应力单位 MPa、位移 mm） */
@Serializable
data class SolidMetrics(
    val max_disp_mm: Double = 0.0,
    /** 墙顶侧移（mm） */
    val top_disp_mm: Double = 0.0,
    val max_stress_MPa: Double = 0.0,
    val max_tension_MPa: Double = 0.0,
    val max_compression_MPa: Double = 0.0,
    /** 墙根弯曲应力（MPa） */
    val bend_MPa: Double = 0.0,
    /** 扣除自重压应力后的净拉应力（MPa） */
    val net_tension_MPa: Double = 0.0,
    val safety_factor: Double = 0.0,
    /** 安全 / 注意 / 危险 */
    val verdict: String = ""
)

/** 单条实体校核（抗弯/抗压/抗倾覆；value 单位见 unit，MPa 或 N·m） */
@Serializable
data class SolidCheck(
    val name: String = "",
    val value: Double = 0.0,
    val limit: Double = 0.0,
    val unit: String = "MPa",
    val safety_factor: Double = 0.0,
    val pass: Boolean = true
)

/** 理论解对比（悬臂条带梁） */
@Serializable
data class SolidTheoryCheck(
    val deflection_mm: Double = 0.0,
    val deflection_error_pct: Double = 0.0,
    val bend_stress_MPa: Double = 0.0,
    val bend_error_pct: Double = 0.0,
    val model: String = ""
)

/** 三维渲染数据：节点/四面体/节点标量场/节点位移/范围与网格线 */
@Serializable
data class SolidRenderData(
    val type: String = "solid3d",
    val unit: String = "MPa",
    val field: String = "von_mises",
    val value_location: String = "node",
    /** 节点坐标 [x, y, z]（m，未变形） */
    val nodes: List<List<Double>> = emptyList(),
    /** 四面体单元节点索引 [i, j, k, l] */
    val tets: List<List<Int>> = emptyList(),
    /** 节点位移 [dx, dy, dz]（m） */
    val disp: List<List<Double>> = emptyList(),
    /** 节点 von Mises 应力（MPa） */
    val values: List<Double> = emptyList(),
    /** 包围盒 [xmin, ymin, zmin, xmax, ymax, zmax]（m） */
    val bounds: List<Double> = emptyList(),
    /** 结构化网格分段（画网格线用） */
    val mesh_lines: SolidMesh = SolidMesh(),
    /**
     * 竖直向上轴（"x"|"y"|"z"）：墙体为 y；水平板 plate 为 z。
     * 渲染时按此轴决定屏幕竖直方向，避免重力/几何方向显示错误。
     */
    val up_axis: String = "y"
)

/** 实体网格信息 */
@Serializable
data class SolidMeshInfo(
    val level: String = "medium",
    val nx: Int = 0,
    val ny: Int = 0,
    val nz: Int = 0,
    val n_elements: Int = 0,
    val n_vertices: Int = 0,
    val dof: Int = 0,
    val solver: String = ""
)

/** 实体求解计时（ms） */
@Serializable
data class SolidTiming(
    val total_ms: Double = 0.0,
    val assemble_ms: Double = 0.0,
    val solve_ms: Double = 0.0,
    val mesh_ms: Double = 0.0
)

/** 墙底反力（N） */
@Serializable
data class SolidReactions(
    val fx_N: Double = 0.0,
    val fy_N: Double = 0.0,
    val fz_N: Double = 0.0
)

// =====================================================================
// 通用三维实体模型（box/cylinder/plate + supports + loads，V0.5.3；
// 对应 pocketsim.solid.run_solid_generic 契约）
// =====================================================================

/**
 * 通用三维实体贯穿圆孔（通孔）。axis 为贯穿方向，center 为孔心在垂直于 axis 平面上的坐标（m）。
 */
@Serializable
data class SolidGenericHole(
    /** 孔的贯穿方向 "x"|"y"|"z" */
    val axis: String = "z",
    /** 孔心坐标（m）：axis=x→[y,z]，axis=y→[x,z]，axis=z→[x,y] */
    val center: List<Double> = emptyList(),
    /** 孔半径（m），必须 > 0 */
    val radius: Double = 0.0
)

/**
 * 通用三维实体几何输入（kind=solid_generic）。
 * 按 type 选取不同尺寸字段：
 *  - box：length_m × height_m × thickness_m
 *  - plate：length_m × width_m × thickness_m
 *  - cylinder：radius_m × height_m
 * 未用到的字段留 null，序列化时省略。
 */
@Serializable
data class SolidGenericGeometryInput(
    val type: String = "box",
    /** box/plate：长度（m） */
    val length_m: Double? = null,
    /** box：高度（m）；cylinder：高度（m） */
    val height_m: Double? = null,
    /** box/plate：厚度（m） */
    val thickness_m: Double? = null,
    /** plate：宽度（m） */
    val width_m: Double? = null,
    /** cylinder：半径（m） */
    val radius_m: Double? = null,
    /**
     * 竖直向上轴覆盖（"x"|"y"|"z" 或 [x,y,z]）。
     * 缺省按图元取：box/cylinder 沿 y 向上，plate 沿厚度 z 向上（水平板）。
     * 例如竖直放置的板可置 "y"。
     */
    val up_axis: String? = null,
    /**
     * box/plate 贯穿圆孔列表（通孔）；无孔省略或空列表。
     * 每个孔 {"axis":"x|y|z","center":[c1,c2],"radius":r}，单位米。
     */
    val holes: List<SolidGenericHole>? = null
)

/**
 * 通用约束定义。
 *  - plane：{type:"plane", axis:"x|y|z", value:坐标值, dofs:[0,1,2]}
 *  - point：{type:"point", xyz:[x,y,z], dofs:[0,1,2]}
 *  - outline：{type:"outline", face:{axis,value}, dofs:[0,1,2]}
 *      约束该面的外轮廓（板四边/四周固定），等效边框嵌固，避免点约束的应力奇异
 * dofs 为约束的自由度分量（0=x,1=y,2=z），默认三向全约束；
 * Python 侧也接受 "fixed" 字符串，Kotlin 统一用列表避免多态序列化。
 */
@Serializable
data class SolidGenericSupport(
    val type: String = "plane",
    /** plane 用：坐标轴 "x"|"y"|"z" */
    val axis: String? = null,
    /** plane 用：平面坐标值（m）；提供 at 时可省略，由几何边界解析 */
    val value: Double? = null,
    /** plane 用：语义锚点 "min"|"max"（如底面=y 最小值面、顶面=y 最大值面），
     *  由代码按几何尺寸解析成 value，避免 AI 心算坐标写错 */
    val at: String? = null,
    /** point 用：点坐标 [x,y,z]（m） */
    val xyz: List<Double>? = null,
    /** point 用：语义锚点 [x,y,z] 三轴各取 "min"|"max"|"center"，
     *  由代码按几何包围盒解析成坐标（如顶面中心 ["center","max","center"]），
     *  与 xyz 二选一，point_at 优先，避免 AI 心算坐标写错 */
    val point_at: List<String>? = null,
    /** outline 用：轮廓所在面 */
    val face: SolidGenericFace? = null,
    /** 约束自由度分量（0=x,1=y,2=z），默认三向全约束 */
    val dofs: List<Int> = listOf(0, 1, 2)
)

/** 压力载荷的作用面：axis + at（优先）或 value 确定坐标平面 */
@Serializable
data class SolidGenericFace(
    val axis: String = "",
    /** 数值坐标值（m）；与 at 二选一，都缺时确定性报错（不静默落默认面） */
    val value: Double? = null,
    /** 语义锚点 "min"|"max"：存在时代替 value，由代码按几何边界解析
     *  （如顶面 {"axis":"y","at":"max"}），避免 AI 直接填数值坐标出错 */
    val at: String? = null
)

/**
 * 通用载荷定义。
 *  - pressure：{type:"pressure", face:{axis,value}, magnitude:Pa, hydrostatic?:bool}
 *    hydrostatic=true 时按静水/土压力线性梯度：magnitude 为底部最大压强，顶部为 0
 *    量纲换算（由代码确定性完成，避免 AI 心算错误）：可任选其一替代 magnitude——
 *      wind_speed_m_s（m/s）、beaufort（蒲福风级 0~17）、cp_net（净体型系数，默认1.2）
 *      mass_kg + area_m2（质量/受力面积 → 压强）
 *      fluid（"water"/"soil"）+ depth_m（水深/土深 → 静水压/静止土压，自动线性梯度）
 *  - gravity：{type:"gravity", direction?:[x,y,z]}（缺省沿竖直轴向下：
 *    box/cylinder 为 -y，plate 为 -z；可用 geometry.up_axis 覆盖）
 *  - force：{type:"force", point:[x,y,z], vector:[fx,fy,fz]}（N）
 *    或 mass_kg + direction（单位向量）：质量×g → 沿 direction 方向的重力
 */
@Serializable
data class SolidGenericLoad(
    val type: String = "pressure",
    /** pressure 用：作用面 */
    val face: SolidGenericFace? = null,
    /** pressure 用：压力大小（Pa；hydrostatic 时为底部最大压强） */
    val magnitude: Double? = null,
    /** pressure 用：是否静水/土压力线性梯度（顶部 0 → 底部 magnitude） */
    val hydrostatic: Boolean? = null,
    /** gravity 用：方向向量（缺省沿竖直轴向下：box/cylinder -y、plate -z） */
    val direction: List<Double>? = null,
    /** force 用：作用点坐标（m） */
    val point: List<Double>? = null,
    /** force 用：作用点语义锚点 [x,y,z] 三轴各取 "min"|"max"|"center"，
     *  由代码按几何包围盒解析成坐标（如顶面中心 ["center","max","center"]），
     *  与 point 二选一，point_at 优先，避免 AI 心算坐标写错 */
    val point_at: List<String>? = null,
    /** force 用：力向量（N） */
    val vector: List<Double>? = null,
    // ---- 量纲换算源字段（代码确定性换算，原始值只读、不被 AI 改写）----
    /** pressure/force 用：风速（m/s）→ 风压 */
    val wind_speed_m_s: Double? = null,
    /** pressure 用：蒲福风级（0~17）→ 风压 */
    val beaufort: Int? = null,
    /** pressure 用：净体型系数（默认 1.2） */
    val cp_net: Double? = null,
    /** pressure/force 用：质量（kg）→ 力或压强 */
    val mass_kg: Double? = null,
    /** pressure 用：受力面积（m²），mass_kg 换算压强时必填 */
    val area_m2: Double? = null,
    /** pressure 用：流体类型 water/soil */
    val fluid: String? = null,
    /** pressure 用：水深/土深（m）→ 静水/静止土压力 */
    val depth_m: Double? = null,
    /** pressure 用：作用方向，"in"=指向实体内部（默认，兼容旧行为），"out"=向外（吸力/负压） */
    val pressure_dir: String? = null
)

/**
 * 通用三维实体模型（AI 建模产物 + bridge.run_solid_generic 入参）。
 * 与 SolidWallModel（墙体专用）并列：几何由 geometry.type 决定，
 * 约束由 supports 定义，载荷由 loads 定义。
 */
@Serializable
data class SolidGenericModel(
    val name: String = "三维实体",
    val kind: String = "solid_generic",
    val geometry: SolidGenericGeometryInput = SolidGenericGeometryInput(),
    /** 材料中文名（混凝土C20/钢/砖砌体…） */
    val material: String = "",
    /** 弹性模量覆盖（MPa） */
    @SerialName("E_MPa") val eMPa: Double? = null,
    /** 泊松比覆盖 */
    val nu: Double? = null,
    /** 密度覆盖（kg/m³） */
    val density_kg_m3: Double? = null,
    val supports: List<SolidGenericSupport> = emptyList(),
    val loads: List<SolidGenericLoad> = emptyList(),
    /** 网格档位：coarse | medium | fine | ultra */
    val mesh_level: String = "medium",
    /** 案例埋点用的用户上下文（不参与求解，由 ChatSession 注入，bridge 写入 case_log.jsonl；JSON 键 _case_meta） */
    @SerialName("_case_meta") val case_meta: CaseMeta? = null,
    /** 显式长度单位声明（v1 仅支持 "m"，内部一律 SI 米）；缺省 "m"，AI 应显式输出 */
    val unit: String = "m"
)

/** 案例埋点上下文：对齐《CAE 失败案例记录表》C/D/E 列，随模型透传到 Python 侧。 */
@Serializable
data class CaseMeta(
    /** 来源会话 ID（C 列） */
    val session_id: String = "",
    /** 用户原话（D 列） */
    val user_text: String = "",
    /** 输入形态：text / image / text+image（E 列） */
    val input_form: String = "text"
)

/** 通用三维实体几何输出信息（m³、kN） */
@Serializable
data class SolidGenericGeometry(
    val type: String = "",
    val length_m: Double? = null,
    val height_m: Double? = null,
    val thickness_m: Double? = null,
    val width_m: Double? = null,
    val radius_m: Double? = null,
    val volume_m3: Double = 0.0,
    val weight_kN: Double = 0.0,
    /** 实际采用的竖直向上轴（"x"|"y"|"z"） */
    val up_axis: String = "y"
)

/** 通用三维实体核心指标（应力 MPa、位移 mm） */
@Serializable
data class SolidGenericMetrics(
    val max_disp_mm: Double = 0.0,
    val max_disp_node: Int = 0,
    val max_von_mises_MPa: Double = 0.0,
    /**
     * 排除应力奇点区域（约束面/集中力点附近）后的最大 von Mises（MPa）。
     * 用于"优化是否有效"的评估，避免理论奇异点主导结论；Python 未提供时为 null。
     */
    val max_von_mises_excl_singular_MPa: Double? = null,
    val max_tension_MPa: Double = 0.0,
    val max_compression_MPa: Double = 0.0,
    /** 综合安全系数（拉/压安全系数的较小值） */
    val safety_factor: Double = 0.0,
    val sf_tension: Double = 0.0,
    val sf_compression: Double = 0.0,
    /** 安全 / 注意 / 危险 */
    val verdict: String = ""
)

/** 通用三维实体网格信息（segments 为各维度分段数的映射） */
@Serializable
data class SolidGenericMeshInfo(
    val level: String = "medium",
    val type: String = "",
    val segments: Map<String, Int> = emptyMap(),
    val n_elements: Int = 0,
    val n_vertices: Int = 0,
    val dof: Int = 0,
    val solver: String = ""
)

/**
 * 通用三维实体求解结果（字段名与 Python solid.run_solid_generic 输出一一对应）。
 * 复用 SolidMaterialParams / SolidCheck / SolidReactions / SolidTiming / SolidRenderData。
 */
@Serializable
data class SolidGenericResult(
    val kind: String = "solid_generic",
    val ok: Boolean = true,
    val model_name: String = "",
    val scenario: String = "solid_generic",
    val material: String = "",
    val material_params: SolidMaterialParams = SolidMaterialParams(),
    val geometry: SolidGenericGeometry = SolidGenericGeometry(),
    val metrics: SolidGenericMetrics = SolidGenericMetrics(),
    val checks: List<SolidCheck> = emptyList(),
    val reactions: SolidReactions = SolidReactions(),
    val mesh: SolidGenericMeshInfo = SolidGenericMeshInfo(),
    val timing: SolidTiming = SolidTiming(),
    val render: SolidRenderData? = null,
    val assumptions: List<String> = emptyList(),
    /** 结论/建议（shell 含热冲击提示等）；Python 未输出时为空 */
    val advice: List<String> = emptyList(),
    /** 载荷来源说明（shell 含温度载荷条目）；Python 未输出时为空 */
    val load_sources: List<String> = emptyList(),
    /** 温度场摘要（shell 热应力输出：alpha/inner/outer/ref/wall_delta_t_K）；无热应力时 null */
    val thermal: JsonElement? = null
)

// =====================================================================
// 薄壁容器模型（水箱 / 料仓 / 充液罐体；对应 pocketsim.shell.run_vessel，
// 经 bridge.run_shell 跨边界）。输出 JSON 与 SolidGenericResult 同构，可直接解码。
// =====================================================================

/**
 * 薄壁容器几何输入（kind=shell_vessel）。
 * section 决定所需尺寸字段：
 *  - rect（矩形水箱）：length_m × width_m × height_m × thickness_m
 *  - circle（圆柱料仓/罐体）：radius_m × height_m × thickness_m
 * 未用到的字段留 null。
 */
@Serializable
data class ShellVesselGeometry(
    /** "rect" | "circle"（兼容 rectangle/box/circular/cylinder 等别名，校验时归一化） */
    val section: String = "rect",
    /** rect：长度（m） */
    val length_m: Double? = null,
    /** rect：宽度（m） */
    val width_m: Double? = null,
    /** 容器高度（m） */
    val height_m: Double? = null,
    /** 壁厚（m） */
    val thickness_m: Double? = null,
    /** circle：半径（m） */
    val radius_m: Double? = null,
    /** 竖直向上轴覆盖（"x"|"y"|"z"），缺省 y */
    val up_axis: String? = null
)

/**
 * 薄壁容器稳态热输入（壁厚温差），对应 Python run_vessel 的 model.thermal。
 * inner=浸湿面（内壁/底板上表面，局部 −z）、outer=外露面（外壁/底板下表面，
 * 局部 +z）；温度沿壁厚线性分布。缺省字段由 Python 按参考温度兜底
 * （无温差→不产生热应力）；整个对象为 null 时不计温度应力。
 */
@Serializable
data class ShellThermalSpec(
    /** 浸湿面温度（°C） */
    @SerialName("inner_temp_c") val innerTempC: Double? = null,
    /** 外露面温度（°C） */
    @SerialName("outer_temp_c") val outerTempC: Double? = null,
    /** 无应力参考温度（°C），缺省 20 */
    @SerialName("ref_temp_c") val refTempC: Double? = null
)

/**
 * 薄壁容器模型（AI 建模产物 + bridge.run_shell 入参）。
 * 与框架/实体并列：几何 + 材料 + 填料（fill）即可，
 * 载荷（静水压 / 散体侧压 / 底板反力 / 自重）由求解器按填料类型自动生成，
 * 无需用户手写 supports / loads。
 *
 * fill 字段两种形态（用 JsonElement 承载，原样透传 Python，解析时兼容两者）：
 *  - 字符串预设名：water / dry_sand / wet_sand / grain_wheat / cement_powder / plastic_pellets
 *  - 对象：{"type":"fluid","bulk_density_kg_m3":1000}
 *           或 {"type":"granular","preset":"dry_sand", ...可覆盖内摩擦角/重度等}
 *  - 缺省按 water 处理。
 */
@Serializable
data class ShellVesselModel(
    val name: String = "薄壁容器",
    val kind: String = "shell_vessel",
    val geometry: ShellVesselGeometry = ShellVesselGeometry(),
    /** 材料中文名（钢/混凝土/塑料…） */
    val material: String = "",
    /** 填料预设名或参数对象（见类注释）；null/缺失时按 water 处理 */
    val fill: JsonElement? = null,
    /** 填料深度（m）；缺省/非正值时默认装满（= height_m） */
    val fill_depth_m: Double? = null,
    /** 是否计入容器自重（缺省 true） */
    val self_weight: Boolean? = null,
    /** 弹性模量覆盖（MPa） */
    @SerialName("E_MPa") val eMPa: Double? = null,
    /** 泊松比覆盖 */
    val nu: Double? = null,
    /** 密度覆盖（kg/m³） */
    val density_kg_m3: Double? = null,
    /** 网格档位：coarse | medium | fine | ultra */
    val mesh_level: String = "medium",
    /** 网格分段覆盖（circle：n_circum/n_radial；rect：n_length/n_width/n_height） */
    val mesh: JsonElement? = null,
    /** 案例埋点上下文（不参与求解，由 ChatSession 注入，bridge 写入 case_log.jsonl；JSON 键 _case_meta） */
    @SerialName("_case_meta") val case_meta: CaseMeta? = null,
    /** 稳态热输入（壁厚温差）；null/缺失时不计温度应力 */
    val thermal: ShellThermalSpec? = null
)

// =====================================================================
// 埋地管模型（kind=shell_buried_pipe；对应 pocketsim.buried_pipe.run_buried_pipe，
// 经 bridge.run_buried_pipe 跨边界）。工程公式法，无 supports/loads，载荷由求解器自动生成。
// =====================================================================

/**
 * 埋地管几何输入。内部单位：长度 m、力 kN、应力 kPa/MPa。
 * 必填：outer_diameter_m / thickness_m / cover_depth_m；
 * 土参数 / 车载 / 内压缺省时由求解器按默认值兜底，并在结果 assumptions 中告知用户。
 */
@Serializable
data class ShellBuriedPipeGeometry(
    /** 外径（m），必填 */
    val outer_diameter_m: Double? = null,
    /** 壁厚（m），必填 */
    val thickness_m: Double? = null,
    /** 管顶覆土深度（m），必填 */
    val cover_depth_m: Double? = null,
    /** 土重度（kN/m³），缺省 18 */
    val soil_gamma_kN_m3: Double? = null,
    /** 土内摩擦角（°），缺省 30 */
    val soil_phi_deg: Double? = null,
    /** 土黏聚力（kPa），缺省 0 */
    val soil_cohesion_kPa: Double? = null,
    /** 车轮荷载（kN），缺省 70（公路-Ⅰ级单轮） */
    val wheel_load_kN: Double? = null,
    /** 轮胎接地尺寸（m），缺省 0.3 */
    val tire_contact_m: Double? = null,
    /** 管内压（kPa），缺省 0（空管，最不利外压屈曲工况） */
    val internal_pressure_kPa: Double? = null
)

/**
 * 埋地管模型（AI 建模产物 + bridge.run_buried_pipe 入参）。
 * 材料走材料库，可用 E_MPa / nu / density_kg_m3 / strength_MPa 覆盖。
 */
@Serializable
data class ShellBuriedPipeModel(
    val name: String = "埋地管",
    val kind: String = "shell_buried_pipe",
    val geometry: ShellBuriedPipeGeometry = ShellBuriedPipeGeometry(),
    /** 材料名（材料库） */
    val material: String = "",
    /** 弹性模量覆盖（MPa） */
    @SerialName("E_MPa") val eMPa: Double? = null,
    /** 泊松比覆盖 */
    val nu: Double? = null,
    /** 密度覆盖（kg/m³） */
    val density_kg_m3: Double? = null,
    /** 强度覆盖（MPa），用于应力比校核 */
    val strength_MPa: Double? = null,
    /** 案例埋点上下文（不参与求解，由 ChatSession 注入；JSON 键 _case_meta） */
    @SerialName("_case_meta") val case_meta: CaseMeta? = null
)

/** 埋地管验算结果渲染数据（Canvas 2D 用，单位 mm，圆心在原点） */
@Serializable
data class BuriedPipeRender(
    /** 原始圆环点集 [[x, y], ...]（mm） */
    val original: List<List<Double>> = emptyList(),
    /** 变形后环点集 [[x, y], ...]（mm，已乘放大倍数便于观察） */
    val deformed: List<List<Double>> = emptyList(),
    /** 变形显示放大倍数（实际变形 × amplification = 显示位移） */
    val amplification: Double = 1.0,
    /** 管径（mm，标注用） */
    val diameter_mm: Double = 0.0
)

/** 埋地管单条校核项（挠度比 / 应力比 / 屈曲安全系数），status: pass / warn / fail */
@Serializable
data class BuriedPipeCheck(
    val label: String = "",
    val value: String = "",
    val status: String = ""
)

/**
 * 埋地管工程公式验算结果（字段名与 Python buried_pipe.run_buried_pipe 输出一一对应）。
 * metrics 为宽松映射（数值为主，含材料名字符串），UI 按键提取；
 * 多余字段由 AppJson.ignoreUnknownKeys 兜底，不影响解码。
 */
@Serializable
data class BuriedPipeResult(
    val kind: String = "shell_buried_pipe",
    val ok: Boolean = true,
    /** 结论类别：safe / caution / unsafe */
    val verdict: String = "safe",
    val verdict_zh: String = "安全",
    /** 关键数值（土压/侧压/车载/挠度/应力/屈曲等），键值见 Python 输出契约 */
    val metrics: Map<String, JsonPrimitive> = emptyMap(),
    /** 校核项清单（含状态 pass/warn/fail） */
    val checks: List<BuriedPipeCheck> = emptyList(),
    /** 默认值与方法假设说明（展示给用户） */
    val assumptions: List<String> = emptyList(),
    /** 采用的工程公式（展示给用户） */
    val formulas: List<String> = emptyList(),
    /** 结论建议 */
    val advice: List<String> = emptyList(),
    /** 变形环可视化数据 */
    val render: BuriedPipeRender = BuriedPipeRender()
)

/** 三维实体求解结果（字段名与 Python solid.run_solid 输出一一对应） */
@Serializable
data class SolidResult(
    val kind: String = "solid",
    val ok: Boolean = true,
    val model_name: String = "",
    val scenario: String = "solid_wall",
    val material: String = "",
    val material_params: SolidMaterialParams = SolidMaterialParams(),
    val geometry: SolidGeometry = SolidGeometry(),
    val load: SolidLoad = SolidLoad(),
    val metrics: SolidMetrics = SolidMetrics(),
    val checks: List<SolidCheck> = emptyList(),
    val theory_check: SolidTheoryCheck? = null,
    val weak_part: String = "",
    val failure_modes: List<String> = emptyList(),
    val advice: List<String> = emptyList(),
    /** 临界风速与对应风级（无临界状态时为 null） */
    val critical_speed_m_s: Double? = null,
    val critical_beaufort: Int? = null,
    val critical_mode: String? = null,
    val render: SolidRenderData? = null,
    val mesh: SolidMeshInfo = SolidMeshInfo(),
    val timing: SolidTiming = SolidTiming(),
    val reactions: SolidReactions = SolidReactions(),
    val assumptions: List<String> = emptyList(),
    val narrative: List<String> = emptyList()
)
