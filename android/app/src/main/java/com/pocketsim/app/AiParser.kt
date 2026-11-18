/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.sqrt

/** AI 输出解析失败（JSON 语法 / schema 不符）；open 以便 [AiSemanticException] 继承 */
open class AiParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 4 位有效数字（%g）：错误信息里给 LLM 的坐标/尺寸统一精度，文件内各校验器共用 */
private fun Double.format4g(): String = String.format("%.4g", this)

/**
 * 语义校验失败：JSON 结构合法、但几何/约束/载荷/刚体约束等物理规则不通过。
 * 与"格式解析失败"区分：这类错误信息是确定性的修改指令，值得多重试（2 次）。
 */
class AiSemanticException(message: String, cause: Throwable? = null) : AiParseException(message, cause)

/**
 * 英文界面下清洗解析/校验错误的原始中文消息（[AiParseException] 等）：
 * 保留 [X-NN] 错误码，常见错误短语映射为英文，其余中文兜底为通用英文文案。
 * 仅供展示层使用；发给 LLM 的重试提示仍用原中文（LLM 理解中文指令更可靠）。
 */
internal fun parserErrorDetailEn(detail: String?): String {
    val d = detail ?: return ""
    val code = Regex("\\[([A-Z]-\\d{2})\\]").find(d)?.groupValues?.get(1)
    val key = when {
        d.contains("未找到 JSON") -> "no JSON object in the AI reply"
        d.contains("JSON 语法错误") -> "invalid JSON syntax in the AI reply"
        d.contains("顶层必须是对象") -> "the AI reply is not a JSON object"
        d.contains("字段结构不符") -> "unexpected JSON structure in the AI reply"
        d.contains("template_id 为空") -> "missing template id"
        d.contains("未知模板") -> "unknown template id"
        d.contains("ready=true 但缺少") -> "the AI reply is ready but contains no model"
        d.contains("缺少优化模型") -> "the optimized model is missing"
        d.contains("优化模型求解失败") -> "the optimized model failed to solve"
        d.contains("优化模型无法求解") -> "the optimized model cannot be solved"
        d.contains("均未通过模型校验") -> "no optimized model passed validation"
        d.contains("物理校验失败") -> "model validation failed"
        d.contains("缺少约束") -> "no supports defined"
        d.contains("缺少载荷") -> "no loads defined"
        d.contains("缺少材料") -> "material is missing"
        d.contains("超出范围") -> "a value is out of range"
        d.contains("必须为正") -> "a required value must be positive"
        d.contains("模式，输出必须是") -> "the AI returned a model of the wrong type for the selected mode"
        else -> "model validation failed"
    }
    return if (code != null) "[$code] $key" else key
}

/**
 * 所有重试轮次耗尽后抛出，携带总尝试次数与首轮错误信息（供案例埋点统计
 * 「一次通过率 / 重试后通过率」）。
 */
class AiRetryExhaustedException(
    message: String,
    cause: Throwable?,
    val attempts: Int,
    val firstError: String?
) : Exception(message, cause)

/**
 * 一个约束对刚体运动的限制：若干采样点（面约束=面角点，点约束=单点）+
 * 被约束的平动分量（0=x,1=y,2=z）。用于 L-05 刚体约束秩预检。
 */
internal data class SolidConstraintAnchor(
    val points: List<DoubleArray>,
    val dofs: List<Int>
)

/**
 * 强制契约违约：用户已选「三维实体」模式，但 AI 返回了梁杆/其他契约的模型。
 * 与解析失败区分开，UI 可给出针对性的提示而不是「没听懂」。
 */
class AiContractException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * LLM 返回 → [AiParseResult] 的解析与校验。
 *
 * 输入契约（system prompt 中约定，见 ChatSession）：
 * {"template_id": "...", "params": {...}, "missing_params": [...], "questions": [...]}
 */
object AiParser {

    /** AI 输出契约的模型类别：流体模型 */
    private const val KIND_FLOW = "flow"

    /** AI 输出契约的模型类别：渗流（大坝）模型 */
    private const val KIND_SEEPAGE = "seepage"

    /** AI 输出契约的模型类别：温度-载荷时间历程 */
    private const val KIND_TRANSIENT = "transient"

    /** AI 输出契约的模型类别：三维实体墙体 */
    private const val KIND_SOLID = "solid"

    /** AI 输出契约的模型类别：通用三维实体（box/cylinder/plate） */
    private const val KIND_SOLID_GENERIC = "solid_generic"

    /** AI 输出契约的模型类别：薄壁容器（水箱/料仓/充液罐体） */
    private const val KIND_SHELL_VESSEL = "shell_vessel"

    /** 兼容别名：旧版 tank 入口；归一化到 shell_vessel */
    private const val KIND_SHELL_TANK = "shell_tank"

    /** AI 输出契约的模型类别：超出软件分析范畴（LLM 判定后给出，不建模、不追问） */
    private const val KIND_OUT_OF_SCOPE = "out_of_scope"
    /** AI 输出契约的模型类别：埋地柔性管工程公式校核（土压 + 车载 → 挠度/应力/屈曲） */
    private const val KIND_SHELL_BURIED_PIPE = "shell_buried_pipe"

    /** 语义（物理规则）校验失败时的最大重试轮数（首轮之外） */
    const val MAX_SEMANTIC_RETRIES = 2

    /**
     * parseFrameWithRetry 全局尝试上限（含首轮）：结构重试与语义重试叠加时
     * 兜底，防止单次建模消耗过多 LLM 调用（P2-7）。
     */
    const val MAX_TOTAL_ATTEMPTS = 3

    /**
     * AI 载荷类型别名归一化（宽松 → 合法 force/udl/area/thermal）。
     * LLM 偶尔用 "distributed" "point" "concentrated" 等别名，统一映射到内部合法值。
     * 原始值若已合规（force/udl/area/thermal）直接返回；非法值原样返回由下游校验抛错。
     */
    private val LOAD_ALIAS_MAP = mapOf(
        "point" to "force",
        "concentrated" to "force",
        "node" to "force",
        "f" to "force",
        "集中力" to "force",
        "distributed" to "udl",
        "uniform" to "udl",
        "uniformly_distributed" to "udl",
        "dist" to "udl",
        "w" to "udl",
        "均布载荷" to "udl",
        "surface" to "area",
        "panel" to "area",
        "areaload" to "area",
        "windload" to "wind",
        "风载" to "wind",
        "风荷载" to "wind",
        "面荷载" to "area",
        "面载荷" to "area",
    )
    private fun normalizeLoadType(raw: String): String {
        val v = raw.trim().lowercase()
        return LOAD_ALIAS_MAP[v] ?: v
    }

    /**
     * 递归遍历 JsonElement，把 loads 数组中各对象的 type 字段做别名归一化。
     * 返回新树（kotlinx.serialization.json JsonElement 不可变）。
     */
    private fun normalizeLoadAliasesInJson(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> {
            val entries = e.entries.map { (k, v) ->
                if (k == "loads" && v is JsonArray) {
                    k to buildJsonArray {
                        v.forEach { load ->
                            if (load is JsonObject) {
                                val typePrim = load["type"] as? JsonPrimitive
                                if (typePrim != null) {
                                    val normalized = normalizeLoadType(typePrim.content)
                                    if (normalized != typePrim.content) {
                                        add(buildJsonObject {
                                            load.entries.forEach { (lk, lv) ->
                                                put(lk, if (lk == "type") JsonPrimitive(normalized) else normalizeLoadAliasesInJson(lv))
                                            }
                                        })
                                        return@forEach
                                    }
                                }
                            }
                            add(normalizeLoadAliasesInJson(load))
                        }
                    }
                } else {
                    k to normalizeLoadAliasesInJson(v)
                }
            }
            buildJsonObject { entries.forEach { (k, v) -> put(k, v) } }
        }
        is JsonArray -> buildJsonArray { e.forEach { add(normalizeLoadAliasesInJson(it)) } }
        else -> e
    }

    /**
     * 解析并校验。
     * @param templates 模板列表（提供时校验 template_id 必须命中；传 null 跳过该检查）
     * @throws AiParseException 语法错误 / 结构不符 / 未知模板 / 参数值非标量
     */
    fun parse(content: String, templates: List<TemplateInfo>? = null): AiParseResult {
        val jsonText = extractJson(content)
            ?: throw AiParseException("回复中未找到 JSON 对象")

        val root = try {
            AppJson.parseToJsonElement(jsonText)
        } catch (e: Exception) {
            throw AiParseException("JSON 语法错误：${e.message?.take(120)}", e)
        }
        if (root !is JsonObject) {
            throw AiParseException("JSON 顶层必须是对象，实际是 ${root::class.simpleName}")
        }

        val result = try {
            AppJson.decodeFromJsonElement(AiParseResult.serializer(), root)
        } catch (e: Exception) {
            throw AiParseException("字段结构不符：${e.message?.take(120)}", e)
        }

        // ---- schema 校验 ----
        if (result.template_id.isBlank()) {
            throw AiParseException("template_id 为空")
        }
        if (templates != null && templates.none { it.id == result.template_id }) {
            val known = templates.joinToString("、") { it.id }
            throw AiParseException("未知模板 id「${result.template_id}」，可用模板：$known")
        }
        result.params.forEach { (k, v) ->
            if (v !is JsonPrimitive) {
                throw AiParseException("参数 $k 的值必须是数字或字符串，不能是嵌套结构")
            }
        }
        return result
    }

    /**
     * 从 LLM 回复文本中抽取 JSON：
     * 1) 剥掉 ```json ... ``` 代码围栏；
     * 2) 取首个 '{' 到末个 '}' 之间的片段（容忍前后缀闲聊文字）。
     */
    fun extractJson(content: String): String? {
        var text = content.trim()
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        fence.find(text)?.let { text = it.groupValues[1].trim() }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    /**
     * 带一次重试的解析流程（ChatSession 调用）：
     * 第一次解析/校验失败 → 把错误信息追加为用户消息再请求一次；仍失败则抛出。
     */
    suspend fun parseWithRetry(
        client: AiClient,
        messages: List<LlmMessage>,
        templates: List<TemplateInfo>? = null
    ): AiParseResult {
        val first = client.complete(messages, jsonMode = true)
        try {
            return parse(first, templates)
        } catch (e: AiParseException) {
            val retryMessages = messages + LlmMessage(
                role = "user",
                content = "你上一条回复无法解析：${e.message}。" +
                    "请严格只输出一个 JSON 对象，格式为 " +
                    "{\"template_id\":\"...\",\"params\":{...},\"missing_params\":[...],\"questions\":[...]}，" +
                    "不要包含任何其他文字或代码围栏。"
            )
            val second = client.complete(retryMessages, jsonMode = true)
            return parse(second, templates)
        }
    }

    // ===================================================================
    // 通用框架建模模式（frameMode，输出契约见 ChatSession.FRAME_SYSTEM_PROMPT）
    // ===================================================================

    /**
     * 框架建模输出解析并校验：
     * {"ready":bool,"questions":[...],"model":{...FrameModel...}}
     * @throws AiParseException 语法错误 / ready 但缺 model / 模型引用完整性校验失败
     */
    fun parseFrame(content: String): AiFrameResult {
        val jsonText = extractJson(content)
            ?: throw AiParseException("回复中未找到 JSON 对象")
        val root = try {
            AppJson.parseToJsonElement(jsonText)
        } catch (e: Exception) {
            throw AiParseException("JSON 语法错误：${e.message?.take(120)}", e)
        }
        if (root !is JsonObject) {
            throw AiParseException("JSON 顶层必须是对象，实际是 ${root::class.simpleName}")
        }
        // 解码前归一化载荷类型别名（distributed/point/concentrated → udl/force）
        val rootNormalized = normalizeLoadAliasesInJson(root)
        // shell.thermal 在宽松解码（coerceInputValues）下可能被静默兜底成 null，
        // 提前按原始 JSON 校验类型/量纲，给出温度字段专属错误并触发定点重试
        validateShellThermalRawIfShell(rootNormalized)
        // solid_generic.geometry.holes 同理：coerceInputValues 会把 center/radius 里的
        // 非法类型静默兜底成默认值（字符串→0.0），提前按原始 JSON 校验结构/范围并触发定点重试
        validateSolidGenericHolesRawIfSolidGeneric(rootNormalized)
        // out_of_scope：kind 明确超出范畴时要求 category 为非空字符串（解码前预检，
        // 避免 coerceInputValues 把非法值静默兜底成空串导致话术残缺）
        validateOutOfScopeRaw(rootNormalized)
        // shell_buried_pipe：几何数值字段（外径/壁厚/埋深及可选土/车载/内压）在宽松解码
        // （coerceInputValues）下可能被静默兜底，提前按原始 JSON 校验类型与量纲并触发定点重试
        validateShellBuriedPipeRaw(rootNormalized)
        val result = try {
            AppJson.decodeFromJsonElement(AiFrameResult.serializer(), rootNormalized)
        } catch (e: Exception) {
            throw AiParseException("字段结构不符：${e.message?.take(120)}", e)
        }
        // 超出软件分析范畴：不做任何模型校验、不进入追问，原样透传给 ChatSession 拦截展示话术
        if (result.kind.trim().lowercase() == KIND_OUT_OF_SCOPE) return result
        if (result.ready) {
            // 结构合法后的物理规则校验统一标记为「语义错误」：
            // 重试循环会拿确定性的错误信息（含合法取值）指导模型定点修正，最多重试 2 次。
            try {
                when (result.kind.trim().lowercase()) {
                    KIND_FLOW -> {
                        val flow = result.flow ?: throw AiParseException("ready=true 但缺少 flow 模型")
                        validateFlowModel(flow)
                    }
                    KIND_SEEPAGE -> {
                        val seepage = result.seepage
                            ?: throw AiParseException("ready=true 但缺少 seepage 模型")
                        validateSeepageModel(seepage)
                    }
                    KIND_TRANSIENT -> {
                        val transient = result.transient
                            ?: throw AiParseException("ready=true 但缺少 transient 时间历程参数")
                        validateTransientSpec(transient)
                    }
                    KIND_SOLID -> {
                        val solid = result.solid
                            ?: throw AiParseException("ready=true 但缺少 solid 三维实体模型")
                        validateSolidWallModel(solid)
                    }
                    KIND_SOLID_GENERIC -> {
                        val sg = result.solid_generic
                            ?: throw AiParseException("ready=true 但缺少 solid_generic 通用三维实体模型")
                        validateSolidGenericModel(sg)
                    }
                    KIND_SHELL_VESSEL, KIND_SHELL_TANK -> {
                        val shell = result.shell
                            ?: throw AiParseException("ready=true 但缺少 shell 薄壁容器模型")
                        validateShellVesselModel(shell)
                    }
                    KIND_SHELL_BURIED_PIPE -> {
                        val bp = result.shell_buried_pipe
                            ?: throw AiParseException("ready=true 但缺少 shell_buried_pipe 埋地管模型")
                        validateShellBuriedPipeModel(bp)
                    }
                    else -> {
                        val raw = result.model ?: throw AiParseException("ready=true 但缺少 model")
                        val (normalized, geoNotes) = normalizeFrameGeometry(raw)
                        val (completed, supNotes) = autoCompleteSupports(normalized)
                        validateFrameModel(completed)
                        val allNotes = geoNotes + supNotes
                        if (allNotes.isEmpty()) return result
                        val prefix = if (result.answer.isBlank()) "" else result.answer + "\n"
                        return result.copy(
                            model = completed,
                            answer = prefix + allNotes.joinToString("；")
                        )
                    }
                }
            } catch (e: AiSemanticException) {
                throw e
            } catch (e: AiParseException) {
                throw AiSemanticException(e.message ?: "模型物理校验失败", e)
            }
        }
        return result
    }

    /**
     * AI 结构优化建议解析：结构解析 + 每个方案的完整模型走既有建模校验链路。
     *
     * 若 AI 给出的模型全部不合法，抛 [AiParseException]；部分不合法则丢弃该方案
     * （由调用方按"可验证方案"数量提示），避免把未验证方案当结论展示。
     */
    fun parseOptimize(content: String, kind: OptimizeKind): OptimizeReply {
        val reply = OptimizeJson.parseStructure(content)
        val validated = reply.suggestions.mapNotNull { s ->
            val raw = s.modelJson ?: return@mapNotNull null
            val normalized = try {
                validateOptimizeModel(raw, kind)
            } catch (e: Exception) {
                null
            }
            normalized?.let { s.copy(modelJson = it) }
        }
        if (validated.isEmpty() && reply.suggestions.isNotEmpty()) {
            throw AiParseException("AI 给出的优化模型均未通过模型校验")
        }
        return reply.copy(suggestions = validated)
    }

    /** 复用建模链路的归一化 + 自动补约束 + 物理校验，返回规范化模型 JSON */
    private fun validateOptimizeModel(modelJson: String, kind: OptimizeKind): String? {
        val modelEl = AppJson.parseToJsonElement(modelJson)
        if (modelEl !is JsonObject) return null
        return when (kind) {
            OptimizeKind.FRAME -> {
                val payload = buildJsonObject {
                    put("ready", true)
                    put("kind", "frame")
                    put("model", modelEl)
                }
                val model = parseFrame(payload.toString()).model ?: return null
                AppJson.encodeToString(FrameModel.serializer(), model)
            }
            OptimizeKind.SOLID_GENERIC -> {
                val payload = buildJsonObject {
                    put("ready", true)
                    put("kind", KIND_SOLID_GENERIC)
                    put("solid_generic", modelEl)
                }
                val model = parseFrame(payload.toString()).solid_generic ?: return null
                AppJson.encodeToString(SolidGenericModel.serializer(), model)
            }
        }
    }

    /** 流体模型基础校验（尺寸范围 / 风速来源 / 材料） */
    fun validateFlowModel(f: FlowModel) {
        val kind = f.kind.trim().lowercase()
        if (kind !in setOf("wind_wall", "wall", "wind")) {
            throw AiParseException("暂不支持的流体场景「${f.kind}」（当前支持：wind_wall 风荷载墙体）")
        }
        if (f.wall.height_m <= 0.0 || f.wall.length_m <= 0.0 || f.wall.thickness_m <= 0.0) {
            throw AiParseException("[G-04] 墙体尺寸必须为正（height_m / length_m / thickness_m，单位米）")
        }
        if (f.wall.height_m < 0.2 || f.wall.height_m > 30.0) {
            throw AiParseException("[G-04] 墙高 ${f.wall.height_m} m 超出范围（0.2~30 m，注意单位是米）")
        }
        if (f.wall.thickness_m < 0.03 || f.wall.thickness_m > 3.0) {
            throw AiParseException("[G-04] 墙厚 ${f.wall.thickness_m} m 超出范围（0.03~3 m，240 毫米应写 0.24）")
        }
        if (f.wind_speed_m_s == null && f.beaufort == null) {
            throw AiParseException("[L-03] 缺少风速：需要 wind_speed_m_s（m/s）或 beaufort（几级风）")
        }
        f.beaufort?.let {
            if (it < 0 || it > 17) throw AiParseException("[L-03] 风级 beaufort 必须在 0~17 之间，实际为 $it")
        }
        if (f.material.isBlank()) throw AiParseException("[M-01] 墙体缺少材料（material）")
    }

    /** 渗流模型基础校验（坝体尺寸 / 水位 / 材料 / 可选参数范围） */
    fun validateSeepageModel(s: SeepageModel) {
        val kind = s.kind.trim().lowercase()
        if (kind !in setOf("dam_seepage", "seepage", "dam")) {
            throw AiParseException("暂不支持的渗流场景「${s.kind}」（当前支持：dam_seepage 大坝坝基渗流）")
        }
        val h = s.dam.height_m
        if (h <= 0.0 || s.dam.crest_width_m <= 0.0) {
            throw AiParseException("[G-04] 坝体尺寸必须为正（height_m 坝高、crest_width_m 坝顶宽，单位米）")
        }
        if (h < 1.0 || h > 60.0) {
            throw AiParseException("[G-04] 坝高 ${h} m 超出范围（1~60 m，注意单位是米）")
        }
        if (s.dam.crest_width_m < 0.3 || s.dam.crest_width_m > 30.0) {
            throw AiParseException("[G-04] 坝顶宽 ${s.dam.crest_width_m} m 超出范围（0.3~30 m）")
        }
        if (s.dam.upstream_slope < 0.0 || s.dam.upstream_slope > 3.0 ||
            s.dam.downstream_slope < 0.0 || s.dam.downstream_slope > 3.0
        ) {
            throw AiParseException("[G-04] 坝坡坡比应在 0~3 之间（每 1 m 高对应的水平宽度）")
        }
        if (s.upstream_level_m <= 0.0 || s.upstream_level_m > h + 1e-9) {
            throw AiParseException("[L-05] 上游水位 ${s.upstream_level_m} m 应在 0~坝高 ${h} m 之间")
        }
        if (s.downstream_level_m < 0.0 || s.downstream_level_m >= s.upstream_level_m) {
            throw AiParseException(
                "[L-05] 下游水位 ${s.downstream_level_m} m 应不小于 0 且低于上游水位 ${s.upstream_level_m} m"
            )
        }
        s.foundation_permeability_m_s?.let {
            if (it < 1e-11 || it > 1e-2) {
                throw AiParseException("[L-03] 坝基渗透系数 ${it} m/s 超出范围（1e-11~1e-2 m/s）")
            }
        }
        s.foundation_depth_m?.let {
            if (it < 0.5 || it > 200.0) {
                throw AiParseException("[G-04] 坝基深度 ${it} m 超出范围（0.5~200 m）")
            }
        }
        s.friction_coeff?.let {
            if (it < 0.1 || it > 1.5) {
                throw AiParseException("[L-03] 坝基摩擦系数 ${it} 应在 0.1~1.5 之间")
            }
        }
        if (s.material.isBlank()) throw AiParseException("[M-01] 坝体缺少材料（material）")
    }

    /**
     * 时间历程参数校验：温度区间 / 步数 / 曲线类型 / 平面框架 / 至少一个恒定机械载荷。
     * 深层校验（机构、材料、求解失败）由 Python transient.run_transient 兜底。
     */
    fun validateTransientSpec(t: TransientSpec) {
        val frame = t.frame
        if (frame.dim != 2) {
            throw AiParseException("时间历程当前只支持平面框架（frame.dim 必须为 2）")
        }
        validateFrameModel(frame)
        val hasMechanical = frame.loads.any {
            normalizeLoadType(it.type) in setOf("force", "udl", "area", "wind")
        }
        if (!hasMechanical) {
            throw AiParseException("[L-05] 时间历程需要至少一个恒定机械载荷（例如挂在杆端的重物）")
        }
        if (t.duration_s <= 0.0 || t.duration_s > 7 * 24 * 3600.0) {
            throw AiParseException("[L-03] 持续时间 duration_s 应在 0~7 天之间（单位秒）")
        }
        if (t.steps < 5 || t.steps > 40) {
            throw AiParseException("[L-03] 时间步数 steps 应在 5~40 之间，实际为 ${t.steps}")
        }
        if (t.temp_peak_c == t.temp_base_c) {
            throw AiParseException("[L-03] 峰值温度 temp_peak_c 不能等于起始温度 temp_base_c（温度没有变化）")
        }
        if (t.temp_base_c < -50.0 || t.temp_base_c > 100.0) {
            throw AiParseException("[L-03] 起始温度 temp_base_c 超出范围（-50~100 °C）")
        }
        if (t.temp_peak_c < -60.0 || t.temp_peak_c > 600.0) {
            throw AiParseException("[L-03] 峰值温度 temp_peak_c 超出范围（-60~600 °C）")
        }
        val profile = t.profile.trim().lowercase()
        if (profile !in setOf("triangle", "sine")) {
            throw AiParseException("温度曲线 profile「${t.profile}」不支持（可用：triangle / sine）")
        }
        t.members?.forEach { mid ->
            if (frame.members.none { it.id == mid }) {
                throw AiParseException("[L-01] 参与温度变化的杆件 $mid 在模型中不存在")
            }
        }
    }

    /**
     * 三维实体模型基础校验（墙体尺寸 / 风压来源 / 材料 / 可选覆盖参数）。
     * 深层物理校验（网格、材料库命中、求解失败）由 Python solid.run_solid 兜底。
     */
    fun validateSolidWallModel(s: SolidWallModel) {
        val kind = s.kind.trim().lowercase()
        if (kind !in setOf("solid_wall", "solid", "wall")) {
            throw AiParseException("暂不支持的实体场景「${s.kind}」（当前支持：solid_wall 风压墙体）")
        }
        val h = s.wall.height_m
        val t = s.wall.thickness_m
        val l = s.wall.length_m
        if (h <= 0.0 || t <= 0.0 || l <= 0.0) {
            throw AiParseException("[G-04] 墙体尺寸必须为正（length_m / height_m / thickness_m，单位米）")
        }
        if (h < 0.2 || h > 30.0) {
            throw AiParseException("[G-04] 墙高 ${h} m 超出范围（0.2~30 m，注意单位是米）")
        }
        if (t < 0.03 || t > 3.0) {
            throw AiParseException("[G-04] 墙厚 ${t} m 超出范围（0.03~3 m，240 毫米应写 0.24）")
        }
        if (l < 0.2 || l > 200.0) {
            throw AiParseException("[G-04] 墙长 ${l} m 超出范围（0.2~200 m，注意单位是米）")
        }
        if (s.pressure_Pa == null && s.wind_speed_m_s == null && s.beaufort == null) {
            throw AiParseException(
                "[L-03] 缺少风压：需要 pressure_Pa（Pa）、wind_speed_m_s（m/s）或 beaufort（几级风）"
            )
        }
        s.pressure_Pa?.let {
            if (it < 0.0) throw AiParseException("[L-03] 均布压力 pressure_Pa 不能为负")
        }
        // 仅在未直接给出风压时按风速校验（给出 pressure_Pa 时风速由 Python 反算，不参与加载）
        if (s.pressure_Pa == null) {
            s.wind_speed_m_s?.let {
                if (it < 0.5 || it > 80.0) {
                    throw AiParseException("[L-03] 风速 ${it} m/s 超出范围（0.5~80 m/s）")
                }
            }
        }
        s.beaufort?.let {
            if (it < 0 || it > 17) throw AiParseException("[L-03] 风级 beaufort 必须在 0~17 之间，实际为 $it")
        }
        s.cp_net?.let {
            if (it <= 0.0) throw AiParseException("[L-03] 净体型系数 cp_net 必须为正")
        }
        s.eMPa?.let {
            if (it <= 0.0) throw AiParseException("[L-03] 材料弹性模量 E_MPa 必须为正")
        }
        s.nu?.let {
            if (it <= -1.0 || it >= 0.5) throw AiParseException("[L-03] 泊松比 nu 超出范围 (-1, 0.5)")
        }
        s.density_kg_m3?.let {
            if (it <= 0.0) throw AiParseException("[L-03] 材料密度 density_kg_m3 必须为正")
        }
        s.mesh?.let {
            if (it.nx < 1 || it.ny < 1 || it.nz < 1) {
                throw AiParseException("[G-02] mesh 显式分段必须是 ≥1 的整数 nx/ny/nz")
            }
        }
        if (s.material.isBlank()) throw AiParseException("[M-01] 墙体缺少材料（material）")
    }

    /**
     * 几何图元的坐标包围盒（各轴 min/max，单位 m）。
     * box/plate 从原点起；cylinder 轴线沿 y，底面 y=0，x/z 关于原点对称。
     */
    private fun solidBounds(g: SolidGenericGeometryInput): Map<String, Pair<Double, Double>> {
        return when (g.type.trim().lowercase()) {
            "box" -> mapOf(
                "x" to (0.0 to (g.length_m ?: 0.0)),
                "y" to (0.0 to (g.height_m ?: 0.0)),
                "z" to (0.0 to (g.thickness_m ?: 0.0)),
            )
            "plate" -> mapOf(
                "x" to (0.0 to (g.length_m ?: 0.0)),
                "y" to (0.0 to (g.width_m ?: 0.0)),
                "z" to (0.0 to (g.thickness_m ?: 0.0)),
            )
            "cylinder" -> {
                val r = g.radius_m ?: 0.0
                mapOf(
                    "x" to (-r to r),
                    "y" to (0.0 to (g.height_m ?: 0.0)),
                    "z" to (-r to r),
                )
            }
            else -> mapOf("x" to (0.0 to 0.0), "y" to (0.0 to 0.0), "z" to (0.0 to 0.0))
        }
    }

    /**
     * G-01/G-02 可网格化预检（预览前 fail-fast）：
     * 把会导致网格畸变、非正体积单元或档位错误的模型，在跳预览页前拦截，
     * 避免用户「点求解 → 等几秒 → 才看到失败」。plate 豁免尺寸比（薄板合法）。
     */
    private fun validateMeshability(s: SolidGenericModel) {
        val minDimM = 1e-4   // 0.1 mm：小于此视为几何退化
        val ratioLimit = 200.0
        val g = s.geometry
        val gtype = g.type.trim().lowercase()
        val dims: List<Double> = when (gtype) {
            "box" -> listOfNotNull(g.length_m, g.height_m, g.thickness_m)
            "plate" -> listOfNotNull(g.length_m, g.width_m, g.thickness_m)
            "cylinder" -> listOfNotNull(g.radius_m?.times(2.0), g.height_m)
            else -> return  // 类型非法已由上层拦截
        }
        // 退化：任一维度过小 → 非正体积单元 / 网格无法生成
        if (dims.any { it < minDimM })
            throw AiParseException("[G-01] 几何尺寸过小（< $minDimM m，即 0.1 mm），几何退化会导致网格无法生成或非正体积单元，请检查单位是否为米")
        // 尺寸比：plate 豁免（薄板厚度远小于面内尺寸是合法工程场景）
        if (gtype != "plate" && dims.size >= 2) {
            val maxD = dims.max()
            val minD = dims.min()
            if (minD > 0.0 && maxD / minD > ratioLimit)
                throw AiParseException("[G-01] 各向尺寸相差超过 ${ratioLimit.toInt()} 倍（最大 ${maxD.format4g()} / 最小 ${minD.format4g()} m），网格可能严重畸变导致求解失败，请核对尺寸或改用更粗网格档位")
        }
        // 网格档位合法性（前移 Python _build_generic_mesh 的档位报错）
        val ml = s.mesh_level.trim().lowercase()
        if (ml.isNotBlank() && ml !in setOf("coarse", "medium", "fine", "ultra"))
            throw AiParseException("[G-02] 不支持的网格档位「${s.mesh_level}」（可用：coarse / medium / fine / ultra）")
    }

    /**
     * L-05 刚体约束秩预检（解析法，不网格、不组装刚度矩阵）。
     *
     * 每个被固定的采样点 P 对分量 i 的约束方程为刚体速度场 v(P) = u + ω×r(P)
     * 在 i 方向为 0，对应 6 维未知量 [ux,uy,uz, ωx,ωy,ωz] 的一行：
     *   i=0: [1,0,0,  0, rz,-ry]
     *   i=1: [0,1,0, -rz, 0, rx]
     *   i=2: [0,0,1,  ry,-rx, 0]
     * 坐标先除以包围盒最大边长归一化（平动列与转动列量纲一致），堆叠全部行后
     * 高斯消元求秩；秩 < 6 时零空间的自由列即未被约束的刚体模式，按零向量的
     * 最大分量给出模式名（3 平动 + 3 转动）。
     *
     * @return 未消除的刚体运动模式中文名列表；空列表表示约束充分。
     */
    internal fun rigidBodyMissingModes(
        anchors: List<SolidConstraintAnchor>,
        bounds: Map<String, Pair<Double, Double>>
    ): List<String> {
        val modeNames = listOf(
            "x 方向整体平动", "y 方向整体平动", "z 方向整体平动",
            "绕 x 轴整体转动", "绕 y 轴整体转动", "绕 z 轴整体转动"
        )
        if (anchors.isEmpty()) return modeNames
        val lx = bounds["x"]!!.second - bounds["x"]!!.first
        val ly = bounds["y"]!!.second - bounds["y"]!!.first
        val lz = bounds["z"]!!.second - bounds["z"]!!.first
        // 归一化尺度：避免薄板（厚度<<长宽）时转动列被平动列的消元容差吃掉
        val scale = maxOf(lx, ly, lz, 1e-9)
        val rows = ArrayList<DoubleArray>()
        anchors.forEach { a ->
            a.points.forEach { p ->
                val rx = p[0] / scale
                val ry = p[1] / scale
                val rz = p[2] / scale
                a.dofs.distinct().forEach { d ->
                    val row = DoubleArray(6)
                    row[d] = 1.0
                    when (d) {
                        0 -> { row[4] = rz; row[5] = -ry }
                        1 -> { row[3] = -rz; row[5] = rx }
                        2 -> { row[3] = ry; row[4] = -rx }
                    }
                    rows.add(row)
                }
            }
        }
        if (rows.isEmpty()) return modeNames
        val n = 6
        val m = rows.size
        val a = Array(m) { rows[it] }
        // 高斯-约当消元（部分主元）→ 行最简形
        val tol = 1e-9
        var r = 0
        val pivotRowOfCol = IntArray(n) { -1 }
        for (c in 0 until n) {
            if (r >= m) break
            var pivot = r
            for (i in r + 1 until m) {
                if (abs(a[i][c]) > abs(a[pivot][c])) pivot = i
            }
            if (abs(a[pivot][c]) <= tol) continue
            val tmp = a[r]; a[r] = a[pivot]; a[pivot] = tmp
            val pv = a[r][c]
            for (j in c until n) a[r][j] /= pv
            for (i in 0 until m) {
                if (i == r) continue
                val factor = a[i][c]
                if (abs(factor) > tol) {
                    for (j in c until n) a[i][j] -= factor * a[r][j]
                }
            }
            pivotRowOfCol[c] = r
            r++
        }
        if (r == n) return emptyList()
        // 自由列构造零空间基向量，取基向量中绝对值最大的分量命名缺失模式
        val result = ArrayList<String>()
        for (f in 0 until n) {
            if (pivotRowOfCol[f] >= 0) continue
            val x = DoubleArray(n)
            x[f] = 1.0
            for (c in 0 until n) {
                val rr = pivotRowOfCol[c]
                if (rr >= 0) x[c] = -a[rr][f]
            }
            var dominant = f
            var best = abs(x[f])
            for (i in 0 until n) {
                if (abs(x[i]) > best) {
                    best = abs(x[i])
                    dominant = i
                }
            }
            result.add(modeNames[dominant])
        }
        return result.distinct()
    }

    /**
     * 通用三维实体模型基础校验（几何图元类型/尺寸 / 约束非空 / 载荷非空 / 材料）。
     * 深层物理校验（网格、求解失败）由 Python solid.run_solid_generic 兜底。
     * 本层新增：约束面/加载面必须落在实体外表面、点必须在包围盒内（P0-1）。
     */
    fun validateSolidGenericModel(s: SolidGenericModel) {
        val kind = s.kind.trim().lowercase()
        if (kind !in setOf("solid_generic", "generic_solid")) {
            throw AiParseException("暂不支持的实体场景「${s.kind}」（当前支持：solid_generic 通用三维实体）")
        }
        val g = s.geometry
        val gtype = g.type.trim().lowercase()
        if (gtype !in setOf("box", "cylinder", "plate")) {
            throw AiParseException("不支持的几何图元类型「${g.type}」（可用：box / cylinder / plate）")
        }
        when (gtype) {
            "box" -> {
                val l = g.length_m ?: throw AiParseException("box 缺少 length_m（长度，m）")
                val h = g.height_m ?: throw AiParseException("box 缺少 height_m（高度，m）")
                val t = g.thickness_m ?: throw AiParseException("box 缺少 thickness_m（厚度，m）")
                if (l <= 0.0 || h <= 0.0 || t <= 0.0)
                    throw AiParseException("box 三维尺寸必须为正（length_m/height_m/thickness_m，单位米）")
                if (l > 100.0 || h > 100.0 || t > 100.0)
                    throw AiParseException("box 尺寸过大（>100 m），请检查单位是否为米")
            }
            "cylinder" -> {
                val r = g.radius_m ?: throw AiParseException("cylinder 缺少 radius_m（半径，m）")
                val h = g.height_m ?: throw AiParseException("cylinder 缺少 height_m（高度，m）")
                if (r <= 0.0 || h <= 0.0)
                    throw AiParseException("cylinder 半径与高度必须为正（radius_m/height_m，单位米）")
                if (r > 50.0 || h > 100.0)
                    throw AiParseException("cylinder 尺寸过大，请检查单位是否为米")
            }
            "plate" -> {
                val l = g.length_m ?: throw AiParseException("plate 缺少 length_m（长度，m）")
                val w = g.width_m ?: throw AiParseException("plate 缺少 width_m（宽度，m）")
                val t = g.thickness_m ?: throw AiParseException("plate 缺少 thickness_m（厚度，m）")
                if (l <= 0.0 || w <= 0.0 || t <= 0.0)
                    throw AiParseException("plate 三维尺寸必须为正（length_m/width_m/thickness_m，单位米）")
                if (l > 100.0 || w > 100.0 || t > 10.0)
                    throw AiParseException("plate 尺寸过大，请检查单位是否为米")
            }
        }
        // 单位显式声明（P0）：v1 仅支持米，所有尺寸/坐标均以米为单位。
        if (s.unit.trim().lowercase() !in setOf("m", "meter", "meters"))
            throw AiParseException("unit 必须是 \"m\"（当前 v1 仅支持米，所有尺寸/坐标均以米为单位；请显式输出 \"unit\":\"m\"）")
        // 几何包围盒：约束面/加载面必须落在实体外表面上（P0-1）。
        val bounds = solidBounds(g)
        // 圆柱只有 y=0/y=高度 是坐标平面外表面，x/z 方向只能取到一条母线。
        val cylinderFaceOk = gtype != "cylinder"

        /** 面定位解析（单一真相）：优先语义锚点 at="min"/"max"（由代码按几何尺寸定
         *  数值，AI 不必心算），否则用 value 数值；两者都没有/非法 → 确定性报错 */
        fun resolveFace(axis: String, at: String?, value: Double?, tag: String): Double {
            val pair = bounds[axis]
                ?: throw AiParseException("$tag 的 axis 必须是 x/y/z，当前为「$axis」")
            val lo = pair.first
            val hi = pair.second
            val atN = at?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            return when {
                atN == "min" -> lo
                atN == "max" -> hi
                atN != null -> throw AiParseException(
                    "$tag 的 at 只能是 \"min\" 或 \"max\"（当前为 \"$at\"）；也可省略 at、用 value 给数值坐标（m）"
                )
                value != null -> value
                else -> throw AiParseException(
                    "$tag 缺少定位：请给 at=\"min\"/\"max\"（如底面/顶面，推荐，数值由代码按几何尺寸解析），" +
                        "或给 value=数值坐标（m）"
                )
            }
        }

        /** 校验面在外表面上，返回解析后的坐标值；错误信息带「实际范围 + 合法取值」供 LLM 定点修正 */
        fun requireBoundaryFace(axis: String, at: String?, value: Double?, tag: String): Double {
            if (!cylinderFaceOk && axis != "y")
                throw AiParseException("cylinder 只能在 y=0（at:\"min\"）或 y=高度（at:\"max\"）的端面上加平面约束/面压，曲面侧压暂不支持")
            val v = resolveFace(axis, at, value, tag)
            val (lo, hi) = bounds[axis]!!
            val tol = maxOf(1e-6, 1e-6 * maxOf(abs(lo), abs(hi), 1.0))
            if (abs(v - lo) > tol && abs(v - hi) > tol)
                throw AiParseException(
                    "[L-01] $tag 不在实体边界上：你填的是 $axis=${v.format4g()}，" +
                        "但该轴实际范围为 ${lo.format4g()}~${hi.format4g()} m，合法外表面只有 " +
                        "$axis=${lo.format4g()}（用 {\"axis\":\"$axis\",\"at\":\"min\"}）或 " +
                        "$axis=${hi.format4g()}（用 {\"axis\":\"$axis\",\"at\":\"max\"}）。请二选一改正。"
                )
            return v
        }

        /** 校验加载面非退化：定位后该面在另外两轴的跨度不能为 0（面积为 0 → 无面片） */
        fun requireFaceHasArea(axis: String, tag: String) {
            val others = listOf("x", "y", "z").filter { it != axis }
            val spans = others.map { ax ->
                val (lo, hi) = bounds[ax]!!
                hi - lo
            }
            if (spans.any { it <= 1e-9 })
                throw AiParseException("[L-02] $tag 退化为线或点（受力面积为 0）：请检查几何尺寸是否有维度为 0，或更换加载面")
        }

        fun requirePointInBounds(xyz: List<Double>, tag: String) {
            val diag = bounds.values.let {
                val xs = it.map { p -> p.second - p.first }
                sqrt(xs.sumOf { d -> d * d })
            }
            val tol = maxOf(1e-6, 1e-6 * diag)
            listOf("x", "y", "z").forEachIndexed { i, ax ->
                val (lo, hi) = bounds[ax]!!
                if (xyz[i] < lo - tol || xyz[i] > hi + tol)
                    throw AiParseException(
                        "[L-01] $tag 坐标 $ax=${xyz[i].format4g()} 不在实体范围内" +
                            "（${lo.format4g()}~${hi.format4g()} m）"
                    )
            }
        }

        /** 点语义锚点解析（单一真相）：point_at=[x,y,z] 三轴各取 "min"|"max"|"center"，
         *  由代码按几何包围盒解析成坐标（如顶面中心 ["center","max","center"]），
         *  AI 不必心算数值坐标。返回 [x,y,z]。 */
        fun resolvePointAt(pointAt: List<String>, tag: String): List<Double> {
            if (pointAt.size != 3)
                throw AiParseException("$tag 的 point_at 必须是 [x,y,z] 三个元素（每个取 min/max/center），当前长度 ${pointAt.size}")
            val coords = mutableListOf<Double>()
            listOf("x", "y", "z").forEachIndexed { i, ax ->
                val (lo, hi) = bounds[ax]!!
                val a = pointAt[i].trim().lowercase()
                coords.add(
                    when (a) {
                        "min" -> lo
                        "max" -> hi
                        "center" -> (lo + hi) / 2.0
                        else -> throw AiParseException(
                            "$tag 的 point_at[$i]（$ax 轴）只能是 \"min\"/\"max\"/\"center\"（当前为 \"${pointAt[i]}\"）"
                        )
                    }
                )
            }
            return coords
        }

        /** 一个约束在面上的采样点（用于刚体约束秩检查）。
         *  box/plate 取面四角；cylinder 端面取圆心+四向半径点（四角会落在圆盘外） */
        fun faceAnchorPoints(axis: String, v: Double): List<DoubleArray> {
            val (xlo, xhi) = bounds["x"]!!
            val (ylo, yhi) = bounds["y"]!!
            val (zlo, zhi) = bounds["z"]!!
            if (!cylinderFaceOk) {
                // 仅 y 端面能走到这里
                val r = xhi
                return listOf(
                    doubleArrayOf(0.0, v, 0.0),
                    doubleArrayOf(r, v, 0.0), doubleArrayOf(-r, v, 0.0),
                    doubleArrayOf(0.0, v, r), doubleArrayOf(0.0, v, -r)
                )
            }
            return when (axis) {
                "x" -> listOf(
                    doubleArrayOf(v, ylo, zlo), doubleArrayOf(v, yhi, zlo),
                    doubleArrayOf(v, yhi, zhi), doubleArrayOf(v, ylo, zhi)
                )
                "y" -> listOf(
                    doubleArrayOf(xlo, v, zlo), doubleArrayOf(xhi, v, zlo),
                    doubleArrayOf(xhi, v, zhi), doubleArrayOf(xlo, v, zhi)
                )
                else -> listOf(
                    doubleArrayOf(xlo, ylo, v), doubleArrayOf(xhi, ylo, v),
                    doubleArrayOf(xhi, yhi, v), doubleArrayOf(xlo, yhi, v)
                )
            }
        }

        // 每个约束贡献「采样点 + 约束分量」，供 L-05 刚体约束秩预检
        val anchors = mutableListOf<SolidConstraintAnchor>()
        // 收集面约束（plane/outline）的 axis+坐标+dofs，供 L-05 过约束/重复约束检测
        val constraintFaces = mutableListOf<Triple<String, Double, Set<Int>>>()

        if (s.supports.isEmpty()) {
            throw AiParseException("缺少约束（supports 不能为空，至少指定一个 plane/point/outline 约束）")
        }
        s.supports.forEachIndexed { idx, sp ->
            val tag = "第 ${idx + 1} 个约束"
            val stype = sp.type.trim().lowercase()
            if (sp.dofs.isEmpty())
                throw AiParseException("$tag 的 dofs 不能为空（0=x,1=y,2=z，全固定写 [0,1,2]）")
            if (sp.dofs.any { it !in 0..2 })
                throw AiParseException("$tag 的 dofs 分量必须是 0/1/2")
            when (stype) {
                "plane" -> {
                    if (sp.axis.isNullOrBlank() || sp.axis !in setOf("x", "y", "z"))
                        throw AiParseException("$tag 的 axis 必须是 x/y/z")
                    val v = requireBoundaryFace(sp.axis, sp.at, sp.value, "$tag 面")
                    anchors.add(SolidConstraintAnchor(faceAnchorPoints(sp.axis, v), sp.dofs))
                    constraintFaces.add(Triple(sp.axis, v, sp.dofs.toSet()))
                }
                "point" -> {
                    val pt = when {
                        sp.point_at != null -> resolvePointAt(sp.point_at, "$tag 点")
                        sp.xyz != null && sp.xyz.size == 3 -> sp.xyz
                        else -> throw AiParseException("$tag 的 point 约束需提供 point_at（如 [\"center\",\"max\",\"center\"]，推荐）或 xyz=[x,y,z]")
                    }
                    requirePointInBounds(pt, "$tag 点")
                    anchors.add(SolidConstraintAnchor(listOf(pt.toDoubleArray()), sp.dofs))
                }
                "outline" -> {
                    val f = sp.face
                        ?: throw AiParseException("$tag 的 outline 约束缺少 face（轮廓所在面）")
                    if (f.axis !in setOf("x", "y", "z"))
                        throw AiParseException("$tag 的 face.axis 必须是 x/y/z")
                    val v = requireBoundaryFace(f.axis, f.at, f.value, "$tag 轮廓面")
                    anchors.add(SolidConstraintAnchor(faceAnchorPoints(f.axis, v), sp.dofs))
                    constraintFaces.add(Triple(f.axis, v, sp.dofs.toSet()))
                }
                else -> throw AiParseException("$tag 类型「${sp.type}」不支持（可用 plane / point / outline）")
            }
        }

        // L-05：过约束/重复约束检测——同一坐标面（axis+坐标，容差 1e-6 m）上 dofs 交集非空视为重复，
        // 会导致过约束或求解奇异。保守策略：只查完全重复（同面+重叠 dofs），不推断组合过约束，避免误伤合法模型。
        run {
            val seen = mutableListOf<Triple<String, Double, Set<Int>>>()
            for (cf in constraintFaces) {
                val (axis, v, dofs) = cf
                for (prev in seen) {
                    val overlap = prev.third.intersect(dofs)
                    if (prev.first == axis && abs(prev.second - v) < 1e-6 && overlap.isNotEmpty()) {
                        throw AiParseException(
                            "[L-05] 过约束/重复约束：在 $axis=${v.format4g()} m 面上存在重复约束（重叠 dofs：${overlap.sorted()}），" +
                                "同一面被多次约束会导致过约束或求解奇异，请合并或删除重复约束。"
                        )
                    }
                }
                seen.add(cf)
            }
        }
        // L-05：求解前用解析法检查 6 个刚体运动模式（3 平动 + 3 转动）是否全部被约束
        val missingModes = rigidBodyMissingModes(anchors, bounds)
        if (missingModes.isNotEmpty()) {
            throw AiParseException(
                "[L-05] 约束不足：当前约束没有消除全部刚体运动模式，模型会发生" +
                    missingModes.joinToString("、") + "（共 ${missingModes.size} 个未约束模式），求解会发散或得到错误结果。" +
                    "请增加或扩大约束（例如把单面简支改为固定端、在其他方向补支座），" +
                    "保证 3 个方向平动和 3 个方向转动都被限制后再输出。"
            )
        }
        if (s.loads.isEmpty()) {
            throw AiParseException("缺少载荷（loads 不能为空，至少指定一个 pressure/gravity/force）")
        }
        s.loads.forEachIndexed { idx, ld ->
            val ltype = ld.type.trim().lowercase()
            when (ltype) {
                "pressure" -> {
                    val f = ld.face
                    if (f == null || f.axis !in setOf("x", "y", "z"))
                        throw AiParseException("第 ${idx + 1} 个 pressure 载荷缺少合法的 face.axis（x/y/z）")
                    // 量级来源：magnitude(Pa) 或任一换算源，五选一互斥（L-03）。
                    // Python 侧是优先级兜底，同时填多个会被静默忽略产生歧义，故前置拦截。
                    val sources = buildList {
                        if (ld.magnitude != null && ld.magnitude > 0.0) add("magnitude")
                        if (ld.wind_speed_m_s != null) add("wind_speed_m_s")
                        if (ld.beaufort != null) add("beaufort")
                        if (ld.mass_kg != null) add("mass_kg")
                        if (ld.depth_m != null) add("depth_m")
                    }
                    if (sources.isEmpty())
                        throw AiParseException("第 ${idx + 1} 个 pressure 载荷需提供量级来源：magnitude(Pa) 或换算源（wind_speed_m_s / beaufort / mass_kg / depth_m），五选一")
                    if (sources.size > 1)
                        throw AiParseException("[L-03] 第 ${idx + 1} 个 pressure 载荷量级来源冲突：同时填了 ${sources.joinToString("、")}，只能五选一（magnitude / wind_speed_m_s / beaufort / mass_kg / depth_m），请删除多余的来源")
                    // 分布形式（L-04）：fluid 表示静水/土压力的流体类型，必须配 depth_m 才会换算压强；
                    // 否则 fluid 会被 Python 忽略（落到 magnitude 分支），产生歧义。
                    // 注意：hydrostatic=true + magnitude 是合法的（magnitude 作底部最大压强），不拦截。
                    if (ld.fluid != null && !sources.contains("depth_m"))
                        throw AiParseException("[L-04] 第 ${idx + 1} 个 pressure 载荷分布非法：fluid=\"${ld.fluid}\" 表示静水/土压力，必须配 depth_m（水深/土深，m）才能换算压强，当前未提供 depth_m，请补上 depth_m 或去掉 fluid")
                    ld.wind_speed_m_s?.let {
                        if (it < 0.5 || it > 80.0)
                            throw AiParseException("第 ${idx + 1} 个载荷 wind_speed_m_s 须在 0.5~80 m/s")
                    }
                    ld.beaufort?.let {
                        if (it !in 0..17)
                            throw AiParseException("第 ${idx + 1} 个载荷 beaufort 须为 0~17 的整数")
                    }
                    ld.cp_net?.let {
                        if (it <= 0.0)
                            throw AiParseException("第 ${idx + 1} 个载荷 cp_net 必须为正")
                    }
                    if (ld.mass_kg != null && (ld.area_m2 == null || ld.area_m2 <= 0.0))
                        throw AiParseException("第 ${idx + 1} 个载荷用 mass_kg 换算压强时必须提供正的 area_m2")
                    if (ld.depth_m != null && ld.depth_m < 0.0)
                        throw AiParseException("第 ${idx + 1} 个载荷 depth_m 不能为负")
                    ld.pressure_dir?.let {
                        if (it.trim().lowercase() !in setOf("in", "out"))
                            throw AiParseException("第 ${idx + 1} 个载荷 pressure_dir 只能是 in/out")
                    }
                    requireBoundaryFace(f.axis, f.at, f.value, "第 ${idx + 1} 个压力加载面")
                    requireFaceHasArea(f.axis, "第 ${idx + 1} 个压力加载面")
                }
                "gravity" -> {
                    ld.direction?.let { d ->
                        if (d.size != 3) throw AiParseException("第 ${idx + 1} 个 gravity 载荷的 direction 必须是 [x,y,z]")
                    }
                }
                "force" -> {
                    val pt = when {
                        ld.point_at != null -> resolvePointAt(ld.point_at, "第 ${idx + 1} 个 force 作用点")
                        ld.point != null && ld.point.size == 3 -> ld.point
                        else -> throw AiParseException("第 ${idx + 1} 个 force 载荷需提供 point_at（如 [\"center\",\"max\",\"center\"]，推荐）或 point=[x,y,z]")
                    }
                    // 集中力来源：vector(N) 或 mass_kg + direction，二选一互斥（L-03）。
                    val hasVec = ld.vector != null && ld.vector.size == 3
                    val hasMass = ld.mass_kg != null && ld.direction != null && ld.direction.size == 3
                    if (hasVec && hasMass)
                        throw AiParseException("[L-03] 第 ${idx + 1} 个 force 载荷量级来源冲突：同时填了 vector 和 mass_kg+direction，只能二选一，请删除多余的来源")
                    if (!hasVec && !hasMass)
                        throw AiParseException("第 ${idx + 1} 个 force 载荷需提供 vector(N) 或 mass_kg+direction")
                    // L-03：force 完整性——vector 不能为零、mass_kg 必须为正、direction 必须近似单位向量。
                    if (hasVec) {
                        val v = ld.vector!!
                        val norm = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                        if (norm <= 1e-9)
                            throw AiParseException("[L-03] 第 ${idx + 1} 个 force 载荷的 vector 为零向量（模长 ${norm.format4g()} N），集中力不能为零，请检查数值或单位")
                    }
                    if (hasMass) {
                        val m = ld.mass_kg!!
                        if (m <= 0.0)
                            throw AiParseException("[L-03] 第 ${idx + 1} 个 force 载荷的 mass_kg 必须为正（当前 ${m.format4g()} kg）")
                        val d = ld.direction!!
                        val dnorm = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
                        if (dnorm <= 1e-9)
                            throw AiParseException("[L-03] 第 ${idx + 1} 个 force 载荷的 direction 为零向量，重力方向不能为零，请给出非零的方向向量")
                        if (abs(dnorm - 1.0) > 1e-3)
                            throw AiParseException("[L-03] 第 ${idx + 1} 个 force 载荷的 direction 不是单位向量（模长 ${dnorm.format4g()}，应为 1.0±0.001），请归一化为 [x,y,z] 单位向量")
                    }
                    requirePointInBounds(pt, "第 ${idx + 1} 个 force 作用点")
                }
                else -> throw AiParseException("第 ${idx + 1} 个载荷类型「${ld.type}」不支持（可用 pressure / gravity / force）")
            }
        }
        s.eMPa?.let {
            if (it <= 0.0) throw AiParseException("材料弹性模量 E_MPa 必须为正")
        }
        s.nu?.let {
            if (it <= -1.0 || it >= 0.5) throw AiParseException("泊松比 nu 超出范围 (-1, 0.5)")
        }
        s.density_kg_m3?.let {
            if (it <= 0.0) throw AiParseException("材料密度 density_kg_m3 必须为正")
        }
        if (s.material.isBlank()) throw AiParseException("实体缺少材料（material）")
        // G-01/G-02 可网格化预检：把网格畸变/退化/档位错误前移到预览前拦截
        validateMeshability(s)
    }

    /**
     * 薄壁容器模型基础校验（截面 / 尺寸 / 材料 / 填料 / 深度）。
     * 深层物理校验（网格、薄厚比、求解收敛）由 Python shell.run_vessel 兜底；
     * 本层只做 fail-fast 的结构与量纲检查，错误信息带合法取值供 LLM 定点修正。
     */
    fun validateShellVesselModel(s: ShellVesselModel) {
        val kind = s.kind.trim().lowercase()
        if (kind !in setOf("shell_vessel", "shell_tank", "tank", "silo", "vessel")) {
            throw AiParseException("暂不支持的容器场景「${s.kind}」（当前支持：shell_vessel 薄壁容器）")
        }
        val g = s.geometry
        val section = when (g.section.trim().lowercase()) {
            "rect", "rectangle", "box", "square" -> "rect"
            "circle", "circular", "cylinder", "cylindrical", "round" -> "circle"
            else -> throw AiParseException("不支持的容器截面「${g.section}」（可用：rect 矩形 / circle 圆形）")
        }
        val h = g.height_m ?: throw AiParseException("容器缺少 height_m（高度，m）")
        val t = g.thickness_m ?: throw AiParseException("容器缺少 thickness_m（壁厚，m）")
        if (h <= 0.0 || t <= 0.0)
            throw AiParseException("容器高度 height_m 与壁厚 thickness_m 必须为正（单位米）")
        if (h > 100.0) throw AiParseException("容器高度过大（>100 m），请检查单位是否为米")
        if (t > 5.0) throw AiParseException("容器壁厚过大（>5 m），请检查单位是否为米；壳单元只适用于薄壁结构")
        val feature = when (section) {
            "rect" -> {
                val l = g.length_m ?: throw AiParseException("矩形水箱缺少 length_m（长度，m）")
                val w = g.width_m ?: throw AiParseException("矩形水箱缺少 width_m（宽度，m）")
                if (l <= 0.0 || w <= 0.0)
                    throw AiParseException("矩形水箱 length_m / width_m 必须为正（单位米）")
                if (l > 100.0 || w > 100.0)
                    throw AiParseException("矩形水箱尺寸过大（>100 m），请检查单位是否为米")
                minOf(l, w, h)
            }
            "circle" -> {
                val r = g.radius_m ?: throw AiParseException("圆柱容器缺少 radius_m（半径，m）")
                if (r <= 0.0) throw AiParseException("圆柱容器 radius_m 必须为正（单位米）")
                if (r > 50.0) throw AiParseException("圆柱容器半径过大（>50 m），请检查单位是否为米")
                minOf(r, h)
            }
            else -> error("unreachable")
        }
        // 薄壳适用范围：壁厚应远小于特征尺寸（与 Python 侧 t < 0.2·min 口径一致，提前 fail-fast）
        if (t >= 0.2 * feature)
            throw AiParseException(
                "壁厚 ${t.format4g()} m 相对特征尺寸 ${feature.format4g()} m 过大（t ≥ 0.2·特征尺寸），" +
                    "壳单元只适用于薄壁结构；请减小 thickness_m 或改用「三维实体」模式"
            )
        // 填料：字符串预设须在白名单内；对象由 Python 深度校验，此处只放行并检查 type
        val fillPresets = setOf("water", "dry_sand", "wet_sand", "grain_wheat", "cement_powder", "plastic_pellets")
        s.fill?.let { f ->
            when {
                f is JsonPrimitive && f.isString -> {
                    val name = f.content.trim().lowercase()
                    if (name !in fillPresets)
                        throw AiParseException("未知的填料预设「${f.content}」（可用：${fillPresets.joinToString(" / ")}）")
                }
                f is JsonObject -> {
                    val type = f["type"]?.let {
                        if (it is JsonPrimitive && it.isString) it.content.trim().lowercase() else null
                    }
                    if (type !in setOf("fluid", "granular"))
                        throw AiParseException("填料对象缺少合法 type（fluid / granular）")
                }
                else -> throw AiParseException("fill 字段必须是字符串预设名或对象，当前类型无法识别")
            }
        }
        s.fill_depth_m?.let { d ->
            if (d < 0.0) throw AiParseException("填料深度 fill_depth_m 不能为负（单位米）")
            if (d > h + 1e-6)
                throw AiParseException("填料深度 ${d.format4g()} m 超过容器高度 ${h.format4g()} m")
        }
        s.mesh_level.takeIf { it.isNotBlank() }?.let { ml ->
            if (ml.lowercase() !in setOf("coarse", "medium", "fine", "ultra"))
                throw AiParseException("不支持的网格档位「$ml」（可用：coarse / medium / fine / ultra）")
        }
        s.eMPa?.let { if (it <= 0.0) throw AiParseException("材料弹性模量 E_MPa 必须为正") }
        s.nu?.let { if (it <= -1.0 || it >= 0.5) throw AiParseException("泊松比 nu 超出范围 (-1, 0.5)") }
        s.density_kg_m3?.let { if (it <= 0.0) throw AiParseException("材料密度 density_kg_m3 必须为正") }
        if (s.material.isBlank()) throw AiParseException("容器缺少材料（material）")
        // 稳态热输入（壁厚温差）：直接构造的模型在此做量纲预检；
        // 经 JSON 解码的模型已由 validateShellThermalRawIfShell 前置拦截非数字
        s.thermal?.let { th ->
            fun chk(v: Double?, label: String) {
                v ?: return
                if (!v.isFinite()) throw AiParseException("温度 $label 不是有限数值（°C），当前 $v")
                if (v < -273.15 || v > 2000.0)
                    throw AiParseException("温度 $label 超出合理范围 [-273.15, 2000] °C（当前 ${v.format4g()}）")
            }
            chk(th.innerTempC, "inner_temp_c")
            chk(th.outerTempC, "outer_temp_c")
            chk(th.refTempC, "ref_temp_c")
        }
    }

    /**
     * 埋地管模型基础校验（几何 / 材料 / 土参数 / 车载 / 内压）。
     * 深层物理校核（挠度/应力/屈曲）由 Python buried_pipe.run_buried_pipe 兜底；
     * 本层只做 fail-fast 的结构与量纲检查，错误信息带合法取值供 LLM 定点修正。
     */
    fun validateShellBuriedPipeModel(b: ShellBuriedPipeModel) {
        val kind = b.kind.trim().lowercase()
        if (kind !in setOf("shell_buried_pipe", "buried_pipe"))
            throw AiParseException("暂不支持的埋地管场景「${b.kind}」（当前支持：shell_buried_pipe 埋地管）")
        val g = b.geometry
        val D = g.outer_diameter_m ?: throw AiParseException("埋地管缺少 outer_diameter_m（外径，m）")
        val t = g.thickness_m ?: throw AiParseException("埋地管缺少 thickness_m（壁厚，m）")
        val H = g.cover_depth_m ?: throw AiParseException("埋地管缺少 cover_depth_m（覆土深度，m）")
        if (D <= 0.0 || t <= 0.0 || H <= 0.0)
            throw AiParseException("[G-01] 埋地管外径/壁厚/埋深必须为正（单位米，注意 50 mm 应写 0.05、3 mm 应写 0.003）")
        if (t >= D)
            throw AiParseException("[G-01] 壁厚 ${t.format4g()} m 不应大于等于外径 ${D.format4g()} m")
        if (D > 30.0) throw AiParseException("[G-01] 外径过大（>30 m），请检查单位是否为米（50 mm 应写 0.05）")
        if (t > 5.0) throw AiParseException("[G-01] 壁厚过大（>5 m），请检查单位是否为米（3 mm 应写 0.003）")
        if (H > 100.0) throw AiParseException("[G-01] 覆土深度过大（>100 m），请检查单位是否为米（2 m 应写 2）")
        // 可选土参数 / 车载 / 内压：提供时必须非负且在合理范围
        g.soil_gamma_kN_m3?.let { if (it < 0.0) throw AiParseException("[G-01] 土重度 soil_gamma_kN_m3 不能为负（kN/m³）") }
        g.soil_phi_deg?.let { if (it < 0.0 || it > 90.0) throw AiParseException("[G-01] 土内摩擦角 soil_phi_deg 超出 [0, 90] °") }
        g.soil_cohesion_kPa?.let { if (it < 0.0) throw AiParseException("[G-01] 土黏聚力 soil_cohesion_kPa 不能为负（kPa）") }
        g.wheel_load_kN?.let { if (it < 0.0) throw AiParseException("[L-01] 车轮荷载 wheel_load_kN 不能为负（kN）") }
        g.tire_contact_m?.let { if (it < 0.0) throw AiParseException("[L-01] 轮胎接地尺寸 tire_contact_m 不能为负（m）") }
        g.internal_pressure_kPa?.let { if (it < 0.0) throw AiParseException("[L-01] 管内压 internal_pressure_kPa 不能为负（kPa）") }
        // 材料覆盖：提供时范围合理
        b.eMPa?.let { if (it <= 0.0) throw AiParseException("[M-01] 材料弹性模量 E_MPa 必须为正") }
        b.nu?.let { if (it <= -1.0 || it >= 0.5) throw AiParseException("[M-01] 泊松比 nu 超出范围 (-1, 0.5)") }
        b.density_kg_m3?.let { if (it <= 0.0) throw AiParseException("[M-01] 材料密度 density_kg_m3 必须为正") }
        b.strength_MPa?.let { if (it <= 0.0) throw AiParseException("[M-01] 材料强度 strength_MPa 必须为正") }
        if (b.material.isBlank()) throw AiParseException("[M-01] 埋地管缺少材料（material）")
    }

    /**
     * 解码前预检 out_of_scope 的原始 JSON：当 kind="out_of_scope" 时，category 必须是
     * 非空字符串，否则 AppJson 的 coerceInputValues 会把缺失/非法值静默兜底成空串，
     * 导致「这种场景属于类型的任务…」句式残缺。非法抛 AiSemanticException → 定点重试，
     * 让 LLM 补上类别；合法则由 [parseFrame] 提前 return 透传，不进任何模型校验。
     */
    private fun validateOutOfScopeRaw(root: JsonElement) {
        val rootObj = root as? JsonObject ?: return
        val kind = (rootObj["kind"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: return
        if (kind != KIND_OUT_OF_SCOPE) return
        val catEl = rootObj["category"]
            ?: throw AiSemanticException(
                "kind=\"out_of_scope\" 时必须提供非空字符串字段 category（简短任务类别名，" +
                    "如「电磁-结构耦合」），用于拼接待客话术；请补上后重新输出"
            )
        val prim = catEl as? JsonPrimitive
            ?: throw AiSemanticException("kind=\"out_of_scope\" 的 category 必须是字符串，当前是对象或数组")
        if (!prim.isString)
            throw AiSemanticException("kind=\"out_of_scope\" 的 category 必须是带引号的字符串，不要用数字")
        if (prim.content.isBlank())
            throw AiSemanticException("kind=\"out_of_scope\" 的 category 不能为空，请给出简短任务类别名")
    }

    /**
     * 解码前预检 shell_buried_pipe 的原始 JSON：AppJson 的 coerceInputValues 会把
     * 类型不符的数值（如外径写成字符串 "0.05"、土参数写成非数字）静默兜底成 null/默认值，
     * 导致坏值被丢弃而无报错。这里在解码前拦截非数字，给出专属错误（AiSemanticException → 定点重试）。
     * 数值范围/非负由 [validateShellBuriedPipeModel] 在解码后判定，此处只校验类型与有限性。
     * 注意：ready=false 追问轮次模型可能缺省（shell_buried_pipe 为 null），此时直接 return，
     * 由解码后的 ready 分发处理缺模型；与 validateShellThermalRawIfShell 的安全导航策略一致。
     */
    private fun validateShellBuriedPipeRaw(root: JsonElement) {
        val rootObj = root as? JsonObject ?: return
        val kind = (rootObj["kind"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: return
        if (kind != KIND_SHELL_BURIED_PIPE) return
        val modelObj = rootObj["shell_buried_pipe"] as? JsonObject ?: return
        val geom = modelObj["geometry"] as? JsonObject ?: return
        // 必填数值：缺/字符串/非有限 → 抛错（防 coerce 兜底成 null 后被上层报「缺少」而丢失类型信息）
        rawNumberTagged(geom["outer_diameter_m"], "geometry.outer_diameter_m", "m")
        rawNumberTagged(geom["thickness_m"], "geometry.thickness_m", "m")
        rawNumberTagged(geom["cover_depth_m"], "geometry.cover_depth_m", "m")
        // 可选数值：存在则必须是有限数字
        val optGeom = listOf(
            "soil_gamma_kN_m3" to "kN/m³", "soil_phi_deg" to "°", "soil_cohesion_kPa" to "kPa",
            "wheel_load_kN" to "kN", "tire_contact_m" to "m", "internal_pressure_kPa" to "kPa"
        )
        for ((key, unit) in optGeom) geom[key]?.let { rawNumberTagged(it, "geometry.$key", unit) }
        // 材料覆盖数值
        val optMat = listOf("E_MPa" to "MPa", "nu" to "", "density_kg_m3" to "kg/m³", "strength_MPa" to "MPa")
        for ((key, unit) in optMat) modelObj[key]?.let { rawNumberTagged(it, key, unit) }
    }

    /** 取一个必须为有限数字的 JsonElement（带自定义单位标签），否则抛 AiSemanticException。 */
    private fun rawNumberTagged(el: JsonElement?, tag: String, unit: String = ""): Double {
        val unitTxt = if (unit.isBlank()) "" else "（$unit）"
        val p = el as? JsonPrimitive
            ?: throw AiSemanticException("$tag 必须是数字$unitTxt，当前是对象或数组")
        if (p.isString)
            throw AiSemanticException("$tag 必须是数字$unitTxt（不要加引号），当前是字符串「${p.content}」")
        val d = p.content.toDoubleOrNull()
            ?: throw AiSemanticException("$tag 不是有效数字，当前值「${p.content}」")
        if (!d.isFinite())
            throw AiSemanticException("$tag 不是有限数值，当前 $d")
        return d
    }

    /**
     * 解码前预检 shell.thermal 的原始 JSON：AppJson 的 coerceInputValues 会把
     * 类型不符的温度值（如字符串 "abc"）静默兜底成 null，导致坏值被丢弃而无报错。
     * 这里在解码前拦截非数字 / 越界温度，给出温度字段专属错误（AiSemanticException → 定点重试）。
     * 仅当 kind 为薄壁容器且存在 shell.thermal 时生效；缺字段由 Python 按参考温度兜底，不报错。
     */
    private fun validateShellThermalRawIfShell(root: JsonElement) {
        val rootObj = root as? JsonObject ?: return
        val kind = (rootObj["kind"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: return
        if (kind !in setOf("shell_vessel", "shell_tank", "tank", "silo", "vessel")) return
        val thermal = (rootObj["shell"] as? JsonObject)?.get("thermal") ?: return
        validateShellThermalRaw(thermal)
    }

    private fun validateShellThermalRaw(thermal: JsonElement) {
        val obj = thermal as? JsonObject
            ?: throw AiSemanticException("thermal 必须是对象 {inner_temp_c, outer_temp_c, ref_temp_c}，当前类型无法识别")
        for (label in listOf("inner_temp_c", "outer_temp_c", "ref_temp_c")) {
            val v = obj[label] ?: continue
            val prim = v as? JsonPrimitive
                ?: throw AiSemanticException("温度 $label 必须是数字（°C），当前是对象或数组")
            if (prim.isString)
                throw AiSemanticException("温度 $label 必须是数字（°C，不要加引号），当前是字符串「${prim.content}」")
            val d = prim.content.toDoubleOrNull()
                ?: throw AiSemanticException("温度 $label 不是有效数字（°C），当前值「${prim.content}」")
            if (!d.isFinite())
                throw AiSemanticException("温度 $label 不是有限数值（°C），当前 $d")
            if (d < -273.15 || d > 2000.0)
                throw AiSemanticException("温度 $label 超出合理范围 [-273.15, 2000] °C（当前 ${d.format4g()}），请检查单位与数值")
        }
    }

    /**
     * 解码前预检 solid_generic.geometry.holes 的原始 JSON：
     * AppJson 的 coerceInputValues 会把类型不符的值（如 center 数组里混了字符串、
     * radius 是字符串）静默兜底成默认值（center 元素→0.0、radius→0.0），导致坏值
     * 被丢弃而无报错。这里在解码前拦截，非法抛 AiSemanticException（→ 定点重试）。
     *
     * 校验项（与 Python solid._validate_holes 公式一致，单一真相）：
     *  - 仅 box / plate 支持开孔，cylinder 不支持；
     *  - 每个孔 axis∈{x,y,z}（缺省 z，与 Python 一致）；
     *  - center=[c1,c2] 两个有限数字（axis=x→[y,z]、axis=y→[x,z]、axis=z→[x,y]）；
     *  - radius 为有限数字且 > 0；
     *  - 孔完全落在构件内：留 5% 半径余量，center∈[r+margin, d-r-margin]，
     *    r+margin ≤ min(d1,d2)/2。
     * 仅当 kind 为 solid_generic/generic_solid 且 geometry.holes 存在时生效；缺 holes 不报错。
     * 几何尺寸本身非法时跳过孔的范围校验（由上层尺寸校验给出专属错误），避免级联误报。
     */
    private fun validateSolidGenericHolesRawIfSolidGeneric(root: JsonElement) {
        val rootObj = root as? JsonObject ?: return
        val kind = (rootObj["kind"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: return
        if (kind !in setOf("solid_generic", "generic_solid")) return
        val geom = (rootObj["solid_generic"] as? JsonObject)?.get("geometry") as? JsonObject ?: return
        val gtype = (geom["type"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: return
        if (gtype == "cylinder") {
            val holes = geom["holes"] ?: return
            if (holes is JsonArray && holes.isNotEmpty())
                throw AiSemanticException("圆柱（cylinder）暂不支持开孔；如需挖孔请改用 box 或 plate 图元")
            return
        }
        if (gtype !in setOf("box", "plate")) return  // 图元类型非法由上层校验拦截
        val holesEl = geom["holes"] ?: return
        if (holesEl !is JsonArray)
            throw AiSemanticException("geometry.holes 必须是数组（每个元素为 {axis,center,radius}）")
        if (holesEl.isEmpty()) return
        // 解析几何尺寸：box → (length, height, thickness)；plate → (length, width, thickness)
        val dim0 = rawNumberOrNull(geom, "length_m")        // x 方向
        val dim1 = rawNumberOrNull(geom, if (gtype == "plate") "width_m" else "height_m")  // y 方向
        val dim2 = rawNumberOrNull(geom, "thickness_m")     // z 方向
        val dimsOk = dim0 != null && dim1 != null && dim2 != null &&
            dim0 > 0.0 && dim1 > 0.0 && dim2 > 0.0
        // axis → 垂直截面的两轴（a1, a2），center=[a1 坐标, a2 坐标]
        val axisInfo = mapOf("x" to Pair("y", "z"), "y" to Pair("x", "z"), "z" to Pair("x", "y"))
        holesEl.forEachIndexed { i, hEl ->
            val tag = "第 ${i + 1} 个孔"
            val h = hEl as? JsonObject
                ?: throw AiSemanticException("$tag 必须是对象 {axis,center,radius}")
            val axis = (h["axis"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "z"
            if (axis !in axisInfo)
                throw AiSemanticException("$tag axis 必须是 x/y/z，当前为「$axis」")
            val r = rawNumber(h["radius"], "$tag radius")
            if (!(r > 0.0))
                throw AiSemanticException("$tag radius 必须 > 0（当前 ${r.format4g()}，单位米）")
            val cEl = h["center"]
            if (cEl !is JsonArray || cEl.size != 2)
                throw AiSemanticException("$tag center 必须是 [c1, c2] 两个数（axis=$axis 时为垂直截面两轴坐标，单位米）")
            val c1 = rawNumber(cEl[0], "$tag center[0]")
            val c2 = rawNumber(cEl[1], "$tag center[1]")
            if (!dimsOk) return@forEachIndexed  // 尺寸非法由上层拦截，跳过孔的范围校验
            val (a1, a2) = axisInfo[axis]!!
            val d1: Double = when (axis) { "x" -> dim1!!; "y" -> dim0!!; else -> dim0!! }
            val d2: Double = when (axis) { "x" -> dim2!!; "y" -> dim2!!; else -> dim1!! }
            val margin = 0.05 * r
            if (r + margin > minOf(d1, d2) / 2.0)
                throw AiSemanticException(
                    "$tag（axis=$axis）半径 ${r.format4g()} 过大，超过垂直截面" +
                        "（${a1}×${a2}，尺寸 ${d1.format4g()}×${d2.format4g()} m）半宽的一半"
                )
            val lo1 = r + margin; val hi1 = d1 - r - margin
            if (c1 < lo1 - 1e-9 || c1 > hi1 + 1e-9)
                throw AiSemanticException(
                    "$tag（axis=$axis）center[0]=${c1.format4g()} 超出范围" +
                        " [${lo1.format4g()}, ${hi1.format4g()}]（${a1} 方向尺寸 ${d1.format4g()} m；已留 5% 半径余量避免孔贴边）"
                )
            val lo2 = r + margin; val hi2 = d2 - r - margin
            if (c2 < lo2 - 1e-9 || c2 > hi2 + 1e-9)
                throw AiSemanticException(
                    "$tag（axis=$axis）center[1]=${c2.format4g()} 超出范围" +
                        " [${lo2.format4g()}, ${hi2.format4g()}]（${a2} 方向尺寸 ${d2.format4g()} m；已留 5% 半径余量避免孔贴边）"
                )
        }
    }

    /** 从 JsonObject 取一个数字字段，缺字段/字符串/非有限 → 返回 null（不抛错，供可选解析）。 */
    private fun rawNumberOrNull(obj: JsonObject, key: String): Double? {
        val p = obj[key] as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.content.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** 取一个必须为有限数字的 JsonElement，否则抛 AiSemanticException（带字段标签，触发定点重试）。 */
    private fun rawNumber(el: JsonElement?, tag: String): Double {
        val p = el as? JsonPrimitive
            ?: throw AiSemanticException("$tag 必须是数字（m），当前是对象或数组")
        if (p.isString)
            throw AiSemanticException("$tag 必须是数字（m，不要加引号），当前是字符串「${p.content}」")
        val d = p.content.toDoubleOrNull()
            ?: throw AiSemanticException("$tag 不是有效数字，当前值「${p.content}」")
        if (!d.isFinite())
            throw AiSemanticException("$tag 不是有限数值，当前 $d")
        return d
    }

    /**
     * 框架几何归一化与自动修复（在 [validateFrameModel] 之前调用）：
     * ①平面模型节点 z 不一致 → 自动投影到同一平面；
     * ②距离过近的重复节点（<5mm）自动合并、杆件/支座/载荷引用重连；
     * ③合并后两端重合的零长度杆自动剔除。
     * 仅做可机械推断的修复，不抛错；修复说明记入返回的 notes，由调用方写入 answer 告知用户。
     */
    private fun normalizeFrameGeometry(m: FrameModel): Pair<FrameModel, List<String>> {
        val notes = mutableListOf<String>()
        val is3d = m.dim == 3
        val nodes = m.nodes.toMutableList()

        // ① dim=2 共面投影：平面约定 z 恒为 0，若 AI 残留了不同 z 则投影到平均值
        if (!is3d) {
            val zVals = nodes.map { it.z }
            val zMin = zVals.minOrNull() ?: 0.0
            val zMax = zVals.maxOrNull() ?: 0.0
            if (zMax - zMin > 1e-6) {
                val avg = zVals.average()
                for (i in nodes.indices) nodes[i] = nodes[i].copy(z = avg)
                notes.add("平面模型节点 z 坐标不一致，已自动投影到同一平面（z=${avg.format4g()} mm）")
            }
        }

        // ② 重复节点合并：两节点距离 < 5mm 视为同一节点，保留较小编号，其余引用重连过来
        val threshold = 5.0
        val remap = mutableMapOf<Int, Int>()
        for (a in nodes.indices) {
            for (b in a + 1 until nodes.size) {
                val na = nodes[a]; val nb = nodes[b]
                if (remap.containsKey(na.id) || remap.containsKey(nb.id)) continue
                val dx = na.x - nb.x; val dy = na.y - nb.y; val dz = na.z - nb.z
                if (dx * dx + dy * dy + dz * dz < threshold * threshold) {
                    val (keep, drop) = if (na.id <= nb.id) na.id to nb.id else nb.id to na.id
                    remap[drop] = keep
                    notes.add("节点 $drop 与节点 $keep 距离过近（<${threshold.toInt()}mm），已自动合并为一个节点")
                }
            }
        }
        fun resolve(id: Int): Int {
            var cur = id
            val seen = mutableSetOf<Int>()
            while (remap.containsKey(cur)) {
                if (!seen.add(cur)) break
                cur = remap[cur]!!
            }
            return cur
        }
        val keepIds = nodes.map { it.id }.filter { it == resolve(it) }.toSet()
        val newNodes = nodes.filter { it.id in keepIds }

        // ③ 重连杆件/支座/载荷的节点引用，剔除合并后两端重合的零长度杆
        val newMembers = m.members.mapNotNull { mem ->
            val ni = resolve(mem.i); val nj = resolve(mem.j)
            if (ni == nj) {
                notes.add("杆件 ${mem.id} 两端节点重合，已自动剔除（零长度杆）")
                null
            } else mem.copy(i = ni, j = nj)
        }
        val newSupports = m.supports.map { it.copy(node = resolve(it.node)) }
        val newLoads = m.loads.map { ld ->
            if (ld.node != null) ld.copy(node = resolve(ld.node)) else ld
        }

        return m.copy(nodes = newNodes, members = newMembers, supports = newSupports, loads = newLoads) to notes
    }

    /**
     * 自动补缺失的支座级平动约束（在 [validateFrameModel] 之前调用）：
     * 若支座未约束全部平动刚体自由度（结构会整体飘移成为机构），把第一个 roller 支座
     * 升级为 pinned 以补齐平动约束；仅覆盖可机械推断的「支座级平动缺失」，
     * 内部机构（缺杆/缺斜撑导致的可变体系，平动约束本就够）不在此处处理，
     * 仍由 validateFrameModel 报错 + Python 特征值分析兜底。
     * 返回（补后模型，说明）；若无需补或补不了则返回原模型。
     */
    private fun autoCompleteSupports(m: FrameModel): Pair<FrameModel, List<String>> {
        val is3d = m.dim == 3
        val notes = mutableListOf<String>()
        val transOf: Map<String, BooleanArray> = if (is3d) mapOf(
            "fixed" to booleanArrayOf(true, true, true),
            "pinned" to booleanArrayOf(true, true, true),
            "roller" to booleanArrayOf(false, false, true),
            "roller_x" to booleanArrayOf(true, false, false),
            "roller_y" to booleanArrayOf(false, true, false)
        ) else mapOf(
            "fixed" to booleanArrayOf(true, true),
            "pinned" to booleanArrayOf(true, true),
            "roller" to booleanArrayOf(false, true)
        )
        val labels = if (is3d) arrayOf("ux", "uy", "uz") else arrayOf("ux", "uy")
        fun constrainedOf(supports: List<FrameSupport>): BooleanArray {
            val c = BooleanArray(labels.size)
            for (sup in supports) {
                val t = transOf[sup.type.trim().lowercase()] ?: continue
                for (k in t.indices) if (t[k]) c[k] = true
                for (raw in sup.fix) when (raw.trim().lowercase()) {
                    "ux" -> c[0] = true
                    "uy" -> c[1] = true
                    "uz" -> if (is3d) c[2] = true
                }
            }
            return c
        }
        val missing = labels.filterIndexed { i, _ -> !constrainedOf(m.supports)[i] }
        if (missing.isEmpty()) return m to notes
        // 自动补：优先升级第一个 roller 为 pinned（补齐全部平动），否则升级第一个支座
        val idx = m.supports.indexOfFirst { it.type.trim().lowercase() == "roller" }
            .takeIf { it >= 0 } ?: 0
        if (idx >= m.supports.size) return m to notes
        val target = m.supports[idx]
        val newSupports = m.supports.toMutableList()
        newSupports[idx] = target.copy(type = "pinned")
        val after = m.copy(supports = newSupports)
        val stillMissing = labels.filterIndexed { i, _ -> !constrainedOf(after.supports)[i] }
        if (stillMissing.isNotEmpty()) return m to notes  // 补后仍缺（非支座能解决），原样返回让校验报错
        notes.add(
            "支座缺少 ${missing.joinToString("、")} 方向约束（结构会整体飘移），" +
                "已自动将节点 ${target.node} 的 ${target.type} 改为 pinned 以补齐约束"
        )
        return after to notes
    }

    /**
     * 框架模型基础校验（引用完整性 / 截面 / 材料 / 载荷类型 / 维度）。
     * 深层物理校验（机构、零长度杆等）由 Python run_frame(_3d) 兜底并给出中文报错。
     */
    fun validateFrameModel(m: FrameModel) {
        if (m.dim != 2 && m.dim != 3) {
            throw AiParseException("模型维度 dim 只能是 2（平面）或 3（空间），实际为 ${m.dim}")
        }
        val is3d = m.dim == 3
        if (m.nodes.size < 2) throw AiParseException("模型至少需要 2 个节点")
        if (m.members.isEmpty()) throw AiParseException("模型至少需要 1 根杆件")
        if (m.supports.isEmpty()) throw AiParseException("模型缺少支座，结构会整体飘移")
        if (m.loads.isEmpty()) throw AiParseException("模型缺少载荷")
        val nodeIds = m.nodes.map { it.id }.toSet()
        if (nodeIds.size != m.nodes.size) throw AiParseException("节点编号有重复")
        // 尺寸数量级检查：坐标单位应为 mm，绝对值 >100m 或 <1μm 疑似 m/mm 混淆（会让模型尺度完全错误）
        m.nodes.forEach { n ->
            listOf(n.x, n.y, n.z).forEach { c ->
                if (Math.abs(c) > 1e5 || (c != 0.0 && Math.abs(c) < 1e-3)) {
                    throw AiSemanticException(
                        "[G-04] 节点 ${n.id} 坐标 ${c.format4g()} 数量级异常（单位应为 mm）：" +
                            "若你按米输入请换算成毫米（×1000），避免 m/mm 混淆导致模型尺度完全错误"
                    )
                }
            }
        }
        // 同一对节点上禁止重复建杆件：AI 偶尔会把同一根构件建成两根（截面还不一样），
        // 会让刚度重复计入，变形图上也会叠出两条线
        val seenPairs = mutableMapOf<Pair<Int, Int>, Int>()
        m.members.forEach { mem ->
            val key = if (mem.i < mem.j) mem.i to mem.j else mem.j to mem.i
            val prev = seenPairs.put(key, mem.id)
            if (prev != null) {
                throw AiParseException(
                    "杆件 $prev 与杆件 ${mem.id} 连接在同一对节点（节点 ${key.first}—节点 ${key.second}）上，" +
                        "同一根构件只能建一根杆件，请合并为一根（保留真实截面）"
                )
            }
        }
        m.members.forEach { mem ->
            if (mem.i !in nodeIds || mem.j !in nodeIds) {
                throw AiParseException("杆件 ${mem.id} 连接了不存在的节点")
            }
            if (mem.i == mem.j) throw AiParseException("杆件 ${mem.id} 的两端是同一个节点")
            if (mem.isCircle) {
                if (mem.outer_diameter_mm <= 0.0) {
                    throw AiParseException(
                        "杆件 ${mem.id} 是圆管截面，缺少外径 outer_diameter_mm（毫米）"
                    )
                }
                if (mem.wall_t > 0.0 && 2.0 * mem.wall_t >= mem.outer_diameter_mm) {
                    throw AiParseException(
                        "杆件 ${mem.id} 的壁厚 ${mem.wall_t} mm 过大（需小于外径的一半 " +
                            "${mem.outer_diameter_mm / 2.0} mm）"
                    )
                }
                if (mem.holes.isNotEmpty()) {
                    throw AiParseException("杆件 ${mem.id} 是圆管截面，暂不支持开孔")
                }
            } else {
                if (mem.width_b <= 0.0 || mem.depth_h <= 0.0) {
                    throw AiParseException("杆件 ${mem.id} 的截面尺寸必须大于 0")
                }
                if (mem.wall_t > 0.0 && 2.0 * mem.wall_t >= minOf(mem.width_b, mem.depth_h)) {
                    throw AiParseException(
                        "杆件 ${mem.id} 的壁厚 ${mem.wall_t} mm 过大（需小于截面短边的一半），" +
                            "请检查是否把外形尺寸填成了壁厚"
                    )
                }
            }
            if (mem.wall_t < 0.0) {
                throw AiParseException("杆件 ${mem.id} 的壁厚 wall_t 不能为负（实心截面请省略或填 0）")
            }
            if (mem.material.isBlank()) throw AiParseException("杆件 ${mem.id} 缺少材料")
        }
        val validSupportTypes = if (is3d) {
            setOf("fixed", "pinned", "roller", "roller_x", "roller_y")
        } else {
            setOf("fixed", "pinned", "roller")
        }
        val validFixDofs = setOf("ux", "uy", "uz", "rx", "ry", "rz")
        m.supports.forEach { sup ->
            if (sup.node !in nodeIds) throw AiParseException("支座引用了不存在的节点：${sup.node}")
            val type = sup.type.trim().lowercase()
            if (type !in validSupportTypes) {
                throw AiParseException(
                    "支座类型「${sup.type}」不支持（${if (is3d) "三维" else "平面"}可用：" +
                        validSupportTypes.joinToString("/") + "）"
                )
            }
            if (!is3d && sup.fix.isNotEmpty()) {
                throw AiParseException("平面模型不支持支座 fix 额外约束自由度")
            }
            sup.fix.forEach { raw ->
                if (raw.trim().lowercase() !in validFixDofs) {
                    throw AiParseException("支座 fix 含未知自由度「$raw」（可用：ux/uy/uz/rx/ry/rz）")
                }
            }
        }
        // 支座平动约束完备性预检：空间/平面框架必须约束全部平动刚体自由度，
        // 否则整体可飘移成为机构（Python 求解器会报"机构/支座约束不足"，用户得重算）。
        // 这里只查平动（几何不变的必要条件，不会误判），转动刚体模态由 Python 特征值分析兜底。
        run {
            val transOf: Map<String, BooleanArray> = if (is3d) mapOf(
                "fixed" to booleanArrayOf(true, true, true),
                "pinned" to booleanArrayOf(true, true, true),
                "roller" to booleanArrayOf(false, false, true),
                "roller_x" to booleanArrayOf(true, false, false),
                "roller_y" to booleanArrayOf(false, true, false)
            ) else mapOf(
                "fixed" to booleanArrayOf(true, true),
                "pinned" to booleanArrayOf(true, true),
                "roller" to booleanArrayOf(false, true)
            )
            val labels = if (is3d) arrayOf("ux（水平 x）", "uy（水平 y）", "uz（竖向 z）")
            else arrayOf("ux（水平）", "uy（竖向）")
            val constrained = BooleanArray(labels.size)
            for (sup in m.supports) {
                val t = transOf[sup.type.trim().lowercase()] ?: continue
                for (k in t.indices) if (t[k]) constrained[k] = true
                for (raw in sup.fix) when (raw.trim().lowercase()) {
                    "ux" -> constrained[0] = true
                    "uy" -> constrained[1] = true
                    "uz" -> if (is3d) constrained[2] = true
                }
            }
            val missing = labels.filterIndexed { i, _ -> !constrained[i] }
            if (missing.isNotEmpty()) {
                throw AiSemanticException(
                    "支座约束不足：缺少 ${missing.joinToString("、")} 方向的约束，" +
                        "结构会整体飘移成为机构。请至少增加一个 fixed 或 pinned 支座提供水平约束" +
                        "（或用 roller_x/roller_y 补齐缺失方向）；带轮子的家具/货架建模时，" +
                        "轮子不能全部设为 roller——至少一处需固定以提供水平参考。"
                )
            }
        }
        // 几何意图断言：标题提到斜杆/斜撑但模型杆件全为轴对齐 → AI 把倾斜几何理想化成了矩形框架，打回重修
        assertGeometryMatchesIntent(m)
        m.loads.forEachIndexed { idx, ld ->
            val normalized = normalizeLoadType(ld.type)
            when (normalized) {
                "force" -> {
                    if (ld.node == null || ld.node !in nodeIds) {
                        throw AiParseException("集中力必须作用在存在的节点上（第 ${idx + 1} 条载荷）")
                    }
                    if (!is3d && (ld.fz != 0.0 || ld.mx != 0.0 || ld.my != 0.0)) {
                        throw AiParseException(
                            "平面模型的集中力只能用 fx/fy/mz（第 ${idx + 1} 条载荷含 fz/mx/my，" +
                                "如需面外受力请把 dim 设为 3）"
                        )
                    }
                }
                "udl" -> {
                    val mid = ld.member
                    if (mid == null || m.members.none { it.id == mid }) {
                        throw AiParseException("均布载荷必须作用在存在的杆件上（第 ${idx + 1} 条载荷）")
                    }
                    if (ld.qx == 0.0 && ld.qy == 0.0 && ld.qz == 0.0) {
                        throw AiParseException(
                            "第 ${idx + 1} 条均布载荷的 qx/qy/qz 全为 0（没有载荷就不要写这条）；" +
                                (if (!is3d) "水平风力请改用 type=wind 源字段（风速/风级+受风面积，代码自动换算）。" else "")
                        )
                    }
                    if (!is3d && (ld.qx != 0.0 || ld.qz != 0.0)) {
                        throw AiParseException(
                            "平面模型的均布载荷只能用 qy（第 ${idx + 1} 条载荷含 qx/qz，" +
                                "如需面外受力请把 dim 设为 3）"
                        )
                    }
                }
                "area" -> {
                    val mids = ld.members.orEmpty()
                    if (mids.isEmpty()) {
                        throw AiParseException(
                            "面荷载必须用 members 列出承托它的杆件（第 ${idx + 1} 条载荷）"
                        )
                    }
                    val bad = mids.filter { id -> m.members.none { it.id == id } }
                    if (bad.isNotEmpty()) {
                        throw AiParseException(
                            "面荷载引用了不存在的杆件 ${bad.joinToString("、")}（第 ${idx + 1} 条载荷）"
                        )
                    }
                    if (ld.total_N == 0.0) {
                        throw AiParseException(
                            "面荷载的 total_N 不能为 0（单位 N，向下为负；没有载荷就不要写这条，第 ${idx + 1} 条）"
                        )
                    }
                }
                "wind" -> {
                    // 风载源字段校验（P1-5）：风速二选一 + 面积必填，动压换算公式在 Python 代码侧
                    val windNids = ld.nodes.orEmpty()
                    if (windNids.isEmpty()) {
                        throw AiParseException("风载必须用 nodes 列出迎风节点 id（第 ${idx + 1} 条载荷）")
                    }
                    val windBad = windNids.filter { it !in nodeIds }
                    if (windBad.isNotEmpty()) {
                        throw AiParseException("风载引用了不存在的节点：${windBad.joinToString()}（第 ${idx + 1} 条载荷）")
                    }
                    if (ld.wind_speed_m_s != null && ld.beaufort != null) {
                        throw AiParseException("[L-03] 风载风速来源冲突：wind_speed_m_s 与 beaufort 二选一（第 ${idx + 1} 条载荷）")
                    }
                    if (ld.wind_speed_m_s == null && ld.beaufort == null) {
                        throw AiParseException("风载缺少风速来源：请给 wind_speed_m_s（m/s）或 beaufort（0~17 级）（第 ${idx + 1} 条载荷）")
                    }
                    if (ld.area_m2 <= 0.0) {
                        throw AiParseException("风载必须提供受风投影面积 area_m2（m²，>0）（第 ${idx + 1} 条载荷）")
                    }
                    val wd = ld.dir.lowercase()
                    val wDirs = if (is3d) setOf("x", "y", "z") else setOf("x", "y")
                    if (wd !in wDirs) {
                        throw AiParseException("风载方向 dir 只支持 ${wDirs.joinToString("/")}（第 ${idx + 1} 条载荷）")
                    }
                }
                "thermal" -> {
                    val mid = ld.member
                    if (mid == null || m.members.none { it.id == mid }) {
                        throw AiParseException("温度载荷必须作用在存在的杆件上（第 ${idx + 1} 条载荷）")
                    }
                    if (ld.delta_temp == 0.0) {
                        throw AiParseException("温度载荷 delta_temp 不能为 0（升温为正、降温为负，第 ${idx + 1} 条载荷）")
                    }
                }
                else -> throw AiParseException("载荷类型「${ld.type}」不支持（可用：force 节点集中力 / udl 杆件均布 / area 面荷载 / wind 风载 / thermal 杆件温度变化）")
            }
        }
    }

    /**
     * 几何意图断言：若模型标题提到斜杆/斜撑/A 字/人字/交叉/剪刀撑/梯形/外撇等倾斜几何，
     * 但所有杆件都是轴对齐（仅水平或竖直，没有任何方向同时有分量的斜杆），说明 AI 把倾斜几何
     * 理想化成了矩形框架 → 抛 [AiSemanticException] 打回 AI 重修（修复 A 字架/X 形斜撑被拉直的问题）。
     */
    private fun assertGeometryMatchesIntent(m: FrameModel) {
        val intent = m.name
        val keywords = listOf(
            "斜杆", "斜撑", "a字", "a 字", "人字", "三角", "交叉", "剪刀撑",
            "梯形", "外撇", "八字", "斜腿", "斜架", "x形", "x 形"
        )
        val mentionsDiagonal = keywords.any { intent.contains(it, ignoreCase = true) }
        if (!mentionsDiagonal) return
        val nodeById = m.nodes.associateBy { it.id }
        val hasDiagonal = m.members.any { mem ->
            val a = nodeById[mem.i] ?: return@any false
            val b = nodeById[mem.j] ?: return@any false
            val dx = Math.abs(b.x - a.x)
            val dy = Math.abs(b.y - a.y)
            val dz = Math.abs(b.z - a.z)
            val len = Math.sqrt(dx * dx + dy * dy + dz * dz)
            if (len < 1e-9) return@any false
            // 斜杆：至少两个方向的分量都占杆长 5% 以上（排除几乎纯水平/竖直的杆）
            listOf(dx, dy, dz).count { it / len > 0.05 } >= 2
        }
        if (!hasDiagonal) {
            throw AiSemanticException(
                "模型标题「${m.name}」提到了斜杆/斜撑，但当前所有杆件都是水平或竖直的轴对齐杆件，" +
                    "没有任何倾斜构件。请按照片/描述的真实倾斜角度建模：让斜杆两端节点的坐标在两个方向都有差值，" +
                    "例如 A 字架两腿从底部向外撇、顶部靠拢，或 X 形斜撑用对角线连接对角节点。"
            )
        }
    }

    /** 强制模式的输出契约类别：<br>forceBuriedPipe→shell_buried_pipe、forceShell→shell_vessel、forceSolidGeneric→solid_generic、forceSolid→solid；无需强制返回 null */
    private fun requiredKindOf(
        forceSolid: Boolean,
        forceSolidGeneric: Boolean,
        forceShell: Boolean = false,
        forceBuriedPipe: Boolean = false
    ): String? = when {
        forceBuriedPipe -> KIND_SHELL_BURIED_PIPE
        forceShell -> KIND_SHELL_VESSEL
        forceSolidGeneric -> KIND_SOLID_GENERIC
        forceSolid -> KIND_SOLID
        else -> null
    }

    /**
     * 检查强制模式的契约类别：ready=false 的追问轮次不算违约；
     * ready=true 但 kind 不是要求的三维实体 → [AiContractException]（触发重试）。
     */
    private fun checkRequiredKind(result: AiFrameResult, requiredKind: String?) {
        if (requiredKind == null || !result.ready) return
        val kind = result.kind.trim().lowercase()
        if (kind == requiredKind) return
        // 强制 shell 模式下允许兼容别名 shell_tank，不计违约
        if (requiredKind == KIND_SHELL_VESSEL && kind == KIND_SHELL_TANK) return
        // 强制埋地管模式下允许兼容别名 buried_pipe，不计违约
        if (requiredKind == KIND_SHELL_BURIED_PIPE && kind == "buried_pipe") return
        val modeName = when (requiredKind) {
            KIND_SHELL_VESSEL -> "薄壁容器"
            KIND_SHELL_BURIED_PIPE -> "埋地管"
            KIND_SOLID_GENERIC -> "通用三维实体"
            else -> "三维实体墙体"
        }
        throw AiContractException(
            "当前已选择「$modeName」模式，输出必须是 kind=\"$requiredKind\" 的模型，" +
                "实际返回的是 kind=\"${kind.ifBlank { "frame" }}\" 的梁杆/其他模型"
        )
    }

    /**
     * 框架/实体建模的分级重试解析：
     *  - 结构错误（JSON/schema/契约不符）：给完整格式示例，仅重试 1 次；
     *  - 语义错误（几何/约束/载荷/刚体约束不通过，[AiSemanticException]）：
     *    给带合法取值的确定性修改单，最多重试 [MAX_SEMANTIC_RETRIES] 次，
     *    耗尽后抛 [AiRetryExhaustedException]（含总尝试次数、首轮错误，供埋点）；
     *  - 结构/契约错误重试后仍失败：原样抛出最后一个异常（AiParseException/
     *    AiContractException），保留错误类型，便于上层区分处理；
     *  - 两类重试叠加时受全局上限 [MAX_TOTAL_ATTEMPTS]（含首轮）兜底，
     *    达到上限后不再重试、按当前错误类型抛出（P2-7）。
     */
    suspend fun parseFrameWithRetry(
        client: AiClient,
        messages: List<LlmMessage>,
        threeD: Boolean = false,
        /** 自动判别模式（P3D-5）：重试示例仍给平面版，但提示空间结构可改用 dim=3 */
        autoDim: Boolean = false,
        /** 强制三维实体（TET4）：重试示例用 solid 契约 */
        forceSolid: Boolean = false,
        /** 强制通用三维实体（box/cylinder/plate + supports + loads）：重试示例用 solid_generic 契约 */
        forceSolidGeneric: Boolean = false,
        /** 强制薄壁容器（水箱/料仓）：重试示例用 shell_vessel 契约 */
        forceShell: Boolean = false,
        /** 强制埋地管（覆土+车载工程公式）：重试示例用 shell_buried_pipe 契约 */
        forceBuriedPipe: Boolean = false,
        /** 多候选生成时为该候选注入的固定 seed（null = 走 stableSeed，保持可复现） */
        seedOverride: Int? = null
    ): AiFrameResult {
        val requiredKind = requiredKindOf(forceSolid, forceSolidGeneric, forceShell, forceBuriedPipe)
        fun contractExample(): String = when {
            forceBuriedPipe ->
                "{\"ready\":true/false,\"kind\":\"shell_buried_pipe\",\"questions\":[...],\"model\":null," +
                    "\"shell_buried_pipe\":{\"name\":\"...\",\"kind\":\"shell_buried_pipe\"," +
                    "\"geometry\":{\"outer_diameter_m\":0.05,\"thickness_m\":0.003,\"cover_depth_m\":2.0}," +
                    "\"material\":\"PVC\"}}"
            forceShell ->
                "{\"ready\":true/false,\"kind\":\"shell_vessel\",\"questions\":[...],\"model\":null," +
                    "\"shell\":{\"name\":\"...\",\"kind\":\"shell_vessel\"," +
                    "\"geometry\":{\"section\":\"rect\",\"length_m\":2,\"width_m\":1,\"height_m\":3," +
                    "\"thickness_m\":0.01},\"fill\":\"water\",\"fill_depth_m\":3," +
                    "\"material\":\"structural_steel\",\"mesh_level\":\"medium\"}}"
            forceSolidGeneric ->
                "{\"ready\":true/false,\"kind\":\"solid_generic\",\"questions\":[...],\"model\":null," +
                    "\"solid_generic\":{\"name\":\"...\",\"kind\":\"solid_generic\"," +
                    "\"geometry\":{\"type\":\"box\",\"length_m\":1,\"height_m\":1,\"thickness_m\":0.1}," +
                    "\"material\":\"混凝土C20\"," +
                    "\"supports\":[{\"type\":\"plane\",\"axis\":\"y\",\"at\":\"min\",\"dofs\":[0,1,2]}]," +
                    "\"loads\":[{\"type\":\"gravity\"}],\"mesh_level\":\"medium\"}}"
            forceSolid ->
                "{\"ready\":true/false,\"kind\":\"solid\",\"questions\":[...],\"model\":null," +
                    "\"solid\":{\"name\":\"...\",\"kind\":\"solid_wall\"," +
                    "\"wall\":{\"length_m\":4,\"height_m\":3,\"thickness_m\":0.24}," +
                    "\"material\":\"砖砌体\",\"pressure_Pa\":500,\"mesh_level\":\"medium\"}}"
            threeD ->
                "{\"ready\":true/false,\"questions\":[...],\"model\":{\"name\":\"...\",\"dim\":3," +
                    "\"nodes\":[{\"id\":1,\"x\":0,\"y\":0,\"z\":0},{\"id\":2,\"x\":0,\"y\":0,\"z\":750}]," +
                    "\"members\":[{\"id\":1,\"i\":1,\"j\":2,\"width_b\":40,\"depth_h\":40," +
                    "\"material\":\"松木\"}],\"supports\":[{\"node\":1,\"type\":\"fixed\"}]," +
                    "\"loads\":[{\"type\":\"force\",\"node\":2,\"fz\":-98}]}}"
            else ->
                "{\"ready\":true/false,\"questions\":[...],\"model\":{\"name\":\"...\"," +
                    "\"nodes\":[{\"id\":1,\"x\":0,\"y\":0}],\"members\":[{\"id\":1,\"i\":1,\"j\":2," +
                    "\"width_b\":30,\"depth_h\":40,\"material\":\"松木\"}]," +
                    "\"supports\":[{\"node\":1,\"type\":\"fixed\"}],\"loads\":[{\"type\":\"force\",\"node\":2,\"fy\":-98}]}}"
        }
        fun autoHintText(): String = if (!threeD && autoDim) {
            "若结构本来是空间受力，请把 model 的 dim 设为 3、节点补 z 坐标、力改用 fz。" +
                "若用户问的是风把墙吹倒这类流体问题，请改用 kind=\"flow\" 与 flow 模型；" +
                "若问的是水流渗过大坝/大坝渗漏管涌这类渗流问题，请改用 kind=\"seepage\" 与 seepage 模型；" +
                "若问的是温度随时间升高再降低（暴晒升温后降温、结冰又回暖）这类时间变化问题，" +
                "请改用 kind=\"transient\" 与 transient 参数（frame 里只放恒定机械载荷）；" +
                "若问的是墙面会不会被吹裂、哪里应力最大这类要看墙体内部受力的三维实体问题，" +
                "请改用 kind=\"solid\" 与 solid 模型（墙体尺寸用米，风压给 pressure_Pa 或 beaufort）。"
        } else ""

        var conversation = messages
        var attempts = 0
        var structuralRetried = false
        var semanticRetries = 0
        var firstError: String? = null
        while (true) {
            attempts++
            // 传 FRAME_OUTPUT_SCHEMA 走 json_schema 严格约束解码（服务不支持时 LlmClient 自动降级 json_object）
            val raw = client.complete(conversation, jsonMode = true, seedOverride = seedOverride, schema = FRAME_OUTPUT_SCHEMA)
            val outcome = runCatching {
                parseFrame(raw).also { checkRequiredKind(it, requiredKind) }
            }
            outcome.getOrNull()?.let { return it }
            val e = outcome.exceptionOrNull()!!
            if (firstError == null) firstError = e.message?.take(300)
            when {
                // 语义错误：确定性修改单（错误信息里含实际边界与合法取值），定点改后重发
                e is AiSemanticException && semanticRetries < MAX_SEMANTIC_RETRIES &&
                    attempts < MAX_TOTAL_ATTEMPTS -> {
                    semanticRetries++
                    conversation = conversation + LlmMessage(
                        role = "user",
                        content = "你上一条输出的 JSON 格式正确，但未通过物理建模校验：\n${e.message}\n" +
                            "以上是确定性校验结果（不是建议）。请只修改报错涉及的字段，其余字段保持不变，" +
                            "重新输出完整 JSON 对象（不要解释、不要代码围栏）。"
                    )
                }
                // 结构/契约错误：给完整格式示例，仅重试 1 次（且受全局上限约束）
                e !is AiSemanticException && !structuralRetried && attempts < MAX_TOTAL_ATTEMPTS -> {
                    structuralRetried = true
                    val reason = when (e) {
                        is AiContractException -> "你上一条回复不符合当前模式：${e.message}"
                        else -> "你上一条回复无法解析：${e.message}"
                    }
                    conversation = conversation + LlmMessage(
                        role = "user",
                        content = "$reason。请严格只输出一个 JSON 对象，格式为 ${contractExample()}，" +
                            "${autoHintText()}不要包含任何其他文字或代码围栏。"
                    )
                }
                else -> if (e is AiSemanticException) {
                    // 仅语义错误多轮耗尽时包装（埋点需要尝试次数与首轮错误）
                    throw AiRetryExhaustedException(e.message ?: "解析失败", e, attempts, firstError)
                } else {
                    // 结构/契约错误：保留原始异常类型抛出
                    throw e
                }
            }
        }
    }
}
