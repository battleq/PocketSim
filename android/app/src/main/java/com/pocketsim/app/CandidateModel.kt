/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import kotlin.math.abs
import kotlin.math.max

/**
 * 多候选建模的单个候选：包装任一建模类型的就绪模型，并附带人类可读概要。
 *
 * 设计为 sealed（纯 Kotlin、无 Android 依赖），既可在 [ChatSession] 的 JVM 单测里构造，
 * 也可作为路由载荷在内存 backStack 中传递（无需 Parcelable——backStack 是进程内 List）。
 */
sealed interface ModelCandidate {
    /** 归一化类型标识：frame / flow / seepage / transient / solid / solid_generic / shell */
    val kind: String

    /** 候选卡片展示的概要文案 */
    val summary: String

    data class Frame(val model: FrameModel, override val summary: String) : ModelCandidate {
        override val kind = "frame"
    }

    data class Flow(val model: FlowModel, override val summary: String) : ModelCandidate {
        override val kind = "flow"
    }

    data class Seepage(val model: SeepageModel, override val summary: String) : ModelCandidate {
        override val kind = "seepage"
    }

    data class Transient(val spec: TransientSpec, override val summary: String) : ModelCandidate {
        override val kind = "transient"
    }

    data class Solid(val model: SolidWallModel, override val summary: String) : ModelCandidate {
        override val kind = "solid"
    }

    data class SolidGeneric(val model: SolidGenericModel, override val summary: String) : ModelCandidate {
        override val kind = "solid_generic"
    }

    data class Shell(val model: ShellVesselModel, override val summary: String) : ModelCandidate {
        override val kind = "shell"
    }

    companion object {
        /** 从解析结果构造候选；未就绪（ready=false）或无对应模型时返回 null。 */
        fun from(parse: AiFrameResult, english: Boolean = false): ModelCandidate? {
            if (!parse.ready) return null
            val k = parse.kind.trim().lowercase()
            return when {
                k == "flow" && parse.flow != null ->
                    Flow(parse.flow, CandidateSummary.flow(parse.flow, english))
                k == "seepage" && parse.seepage != null ->
                    Seepage(parse.seepage, CandidateSummary.seepage(parse.seepage, english))
                k == "transient" && parse.transient != null ->
                    Transient(parse.transient, CandidateSummary.transient(parse.transient, english))
                k == "solid" && parse.solid != null ->
                    Solid(parse.solid, CandidateSummary.solid(parse.solid, english))
                k == "solid_generic" && parse.solid_generic != null ->
                    SolidGeneric(parse.solid_generic, CandidateSummary.solidGeneric(parse.solid_generic, english))
                (k == "shell_vessel" || k == "shell_tank") && parse.shell != null ->
                    Shell(parse.shell, CandidateSummary.shell(parse.shell, english))
                parse.model != null ->
                    Frame(parse.model, CandidateSummary.frame(parse.model, english))
                else -> null
            }
        }
    }
}

/**
 * Models.kt 内置的中文默认模型名（"框架模型"/"温度历程分析"）视为"未提供"：
 * 返回空串，让展示处的 ifBlank 本地化兜底生效（英文模式不再漏出默认中文名）。
 */
internal fun modelDisplayName(raw: String): String =
    if (raw == "框架模型" || raw == "温度历程分析") "" else raw

/**
 * 材料名展示翻译：材料库以中文名为键（求解器、LLM 契约、模型 JSON 均存中文名），
 * 英文界面在展示层把内置材料名映射为英文；映射外的自定义/联网检索材料原样显示。
 * 只影响展示文案，不改动任何存储数据。
 */
internal fun materialDisplayName(raw: String, english: Boolean): String {
    if (!english || raw.isBlank()) return raw
    when (raw) {
        "低碳钢" -> return "Low-carbon steel"
        "不锈钢304" -> return "Stainless steel 304"
        "不锈钢316" -> return "Stainless steel 316"
        "铝合金" -> return "Aluminum alloy"
        "松木" -> return "Pine"
        "竹材" -> return "Bamboo"
        "胶合板" -> return "Plywood"
        "密度板" -> return "MDF"
        "混凝土C20" -> return "Concrete C20"
        "钢筋混凝土C30" -> return "Reinforced concrete C30"
        "砖砌体" -> return "Brick masonry"
        "石膏板" -> return "Gypsum board"
        "玻璃" -> return "Glass"
    }
    if (raw.startsWith("工字钢")) return "I-beam " + raw.removePrefix("工字钢")
    if (raw.startsWith("角钢")) return "Angle " + raw.removePrefix("角钢")
    if (raw.startsWith("欧标") && raw.endsWith("铝型材"))
        return "EU ${raw.removePrefix("欧标").removeSuffix("铝型材")} aluminum profile"
    return raw
}

/** 候选概要文案生成：每张卡片一行，突出规模与关键参数，便于横向对比。 */
internal object CandidateSummary {
    private fun t(english: Boolean, zh: String, en: String): String = if (english) en else zh

    fun frame(m: FrameModel, english: Boolean = false): String = buildString {
        append(modelDisplayName(m.name).ifBlank { t(english, "框架模型", "Frame model") })
        append(" · ")
        append(if (english) "${m.nodes.size} nodes, ${m.members.size} members"
        else "${m.nodes.size}节点 ${m.members.size}杆")
        append(" · ")
        append(if (english) "${m.supports.size} supports, ${m.loads.size} loads"
        else "${m.supports.size}支座 ${m.loads.size}载荷")
        if (m.dim == 3) append(" · 3D")
    }

    fun flow(m: FlowModel, english: Boolean = false): String = buildString {
        append(modelDisplayName(m.name).ifBlank { t(english, "风墙", "Wind wall") })
        append(" · ${fmt(m.wall.length_m)}×${fmt(m.wall.height_m)}×${fmt(m.wall.thickness_m)}m")
        if (m.material.isNotBlank()) append(" · ${materialDisplayName(m.material, english)}")
        val wind = m.wind_speed_m_s?.let { "${fmt(it)}m/s" }
            ?: m.beaufort?.let { if (english) "wind force $it" else "${it}级风" }
        if (wind != null) append(" · $wind")
    }

    fun seepage(m: SeepageModel, english: Boolean = false): String = buildString {
        append(modelDisplayName(m.name).ifBlank { t(english, "大坝渗流", "Dam seepage") })
        append(" · ")
        append(if (english) "height ${fmt(m.dam.height_m)} m, crest ${fmt(m.dam.crest_width_m)} m"
        else "坝高${fmt(m.dam.height_m)}m 顶宽${fmt(m.dam.crest_width_m)}m")
        append(" · ")
        append(if (english) "water ${fmt(m.upstream_level_m)}→${fmt(m.downstream_level_m)} m"
        else "水位${fmt(m.upstream_level_m)}→${fmt(m.downstream_level_m)}m")
        if (m.material.isNotBlank()) append(" · ${materialDisplayName(m.material, english)}")
    }

    fun transient(s: TransientSpec, english: Boolean = false): String = buildString {
        append(modelDisplayName(s.name).ifBlank { t(english, "温度历程", "Thermal history") })
        append(" · ")
        append(if (english) "${s.frame.nodes.size} nodes, ${s.frame.members.size} members"
        else "${s.frame.nodes.size}节点 ${s.frame.members.size}杆")
        append(" · ${fmt(s.temp_base_c)}→${fmt(s.temp_peak_c)}°C · ${fmt(s.duration_s)}s")
    }

    fun solid(m: SolidWallModel, english: Boolean = false): String = buildString {
        append(modelDisplayName(m.name).ifBlank { t(english, "三维墙体", "3D wall") })
        append(" · ${fmt(m.wall.length_m)}×${fmt(m.wall.height_m)}×${fmt(m.wall.thickness_m)}m")
        if (m.material.isNotBlank()) append(" · ${materialDisplayName(m.material, english)}")
        val wind = m.pressure_Pa?.let { "${fmt(it)}Pa" } ?: m.wind_speed_m_s?.let { "${fmt(it)}m/s" }
            ?: m.beaufort?.let { if (english) "wind force $it" else "${it}级风" }
        if (wind != null) append(" · $wind")
    }

    fun solidGeneric(m: SolidGenericModel, english: Boolean = false): String = buildString {
        append(modelDisplayName(m.name).ifBlank { t(english, "三维实体", "3D solid") })
        append(" · ${m.geometry.type}")
        val dims = listOfNotNull(
            m.geometry.length_m?.let { if (english) "L ${fmt(it)}" else "长${fmt(it)}" },
            m.geometry.width_m?.let { if (english) "W ${fmt(it)}" else "宽${fmt(it)}" },
            m.geometry.height_m?.let { if (english) "H ${fmt(it)}" else "高${fmt(it)}" },
            m.geometry.thickness_m?.let { if (english) "T ${fmt(it)}" else "厚${fmt(it)}" },
            m.geometry.radius_m?.let { if (english) "R ${fmt(it)}" else "半径${fmt(it)}" }
        )
        if (dims.isNotEmpty()) append(" · " + dims.joinToString(" "))
        if (m.material.isNotBlank()) append(" · ${materialDisplayName(m.material, english)}")
    }

    fun shell(m: ShellVesselModel, english: Boolean = false): String = buildString {
        append(modelDisplayName(m.name).ifBlank { t(english, "薄壁容器", "Thin-wall vessel") })
        append(" · ${m.geometry.section}")
        val dims = listOfNotNull(
            m.geometry.length_m?.let { if (english) "L ${fmt(it)}" else "长${fmt(it)}" },
            m.geometry.width_m?.let { if (english) "W ${fmt(it)}" else "宽${fmt(it)}" },
            m.geometry.height_m?.let { if (english) "H ${fmt(it)}" else "高${fmt(it)}" },
            m.geometry.thickness_m?.let { if (english) "T ${fmt(it)}" else "厚${fmt(it)}" },
            m.geometry.radius_m?.let { if (english) "R ${fmt(it)}" else "半径${fmt(it)}" }
        )
        if (dims.isNotEmpty()) append(" · " + dims.joinToString(" "))
        if (m.material.isNotBlank()) append(" · ${materialDisplayName(m.material, english)}")
    }

    /** 整数显示为整数，否则保留两位小数（避免 2.0000001 这种噪声）。 */
    private fun fmt(d: Double): String =
        if (d == d.toInt().toDouble()) d.toInt().toString() else "%.2f".format(d)
}

/**
 * 候选差异控制：判断 [other] 与基准 [base] 是否足够相似（同一结构的不同合理简化）。
 *
 * - 框架/时间历程：节点数差 ≤ 3 且杆件数差 ≤ 4；
 * - 其他类型：关键几何尺寸相对偏差 ≤ 20%；
 * - 类型不同（kind 不同）直接判为不相似；
 * - 提取不到可比较标量的类型视为通过（不阻塞未来新增建模类型）。
 */
internal object CandidateDivergence {
    private const val NODE_TOL = 3
    private const val MEMBER_TOL = 4
    private const val REL_TOL = 0.20
    private const val EPS = 1e-9

    fun areSimilar(base: ModelCandidate, other: ModelCandidate, enabled: Boolean): Boolean {
        if (!enabled) return true
        if (base::class != other::class) return false
        return when (base) {
            is ModelCandidate.Frame -> frameSimilar(base.model, (other as ModelCandidate.Frame).model)
            is ModelCandidate.Transient -> frameSimilar(base.spec.frame, (other as ModelCandidate.Transient).spec.frame)
            is ModelCandidate.Flow -> dimsSimilar(keyDims(base.model), keyDims((other as ModelCandidate.Flow).model))
            is ModelCandidate.Seepage -> dimsSimilar(keyDims(base.model), keyDims((other as ModelCandidate.Seepage).model))
            is ModelCandidate.Solid -> dimsSimilar(keyDims(base.model), keyDims((other as ModelCandidate.Solid).model))
            is ModelCandidate.SolidGeneric -> dimsSimilar(keyDims(base.model), keyDims((other as ModelCandidate.SolidGeneric).model))
            is ModelCandidate.Shell -> dimsSimilar(keyDims(base.model), keyDims((other as ModelCandidate.Shell).model))
        }
    }

    private fun frameSimilar(a: FrameModel, b: FrameModel): Boolean =
        abs(a.nodes.size - b.nodes.size) <= NODE_TOL &&
            abs(a.members.size - b.members.size) <= MEMBER_TOL

    private fun relDev(a: Double, b: Double): Double = abs(a - b) / max(max(abs(a), abs(b)), EPS)

    /**
     * 同位置尺寸逐一比较：两边都非空才校验相对偏差 ≤20%；任一侧缺失（null）跳过该位置，
     * 避免 LLM 漏填可选尺寸导致候选被误杀。固定槽位保证同 kind 列表长度一致。
     */
    private fun dimsSimilar(a: List<Double?>, b: List<Double?>): Boolean {
        if (a.size != b.size) return false
        return a.zip(b).all { (x, y) -> x == null || y == null || relDev(x, y) <= REL_TOL }
    }

    private fun keyDims(m: FlowModel) =
        listOf(m.wall.height_m, m.wall.length_m, m.wall.thickness_m)

    private fun keyDims(m: SeepageModel) =
        listOf(m.dam.height_m, m.dam.crest_width_m, m.upstream_level_m, m.downstream_level_m)

    private fun keyDims(m: SolidWallModel) =
        listOf(m.wall.length_m, m.wall.height_m, m.wall.thickness_m)

    private fun keyDims(m: SolidGenericModel) = listOf(
        m.geometry.length_m, m.geometry.width_m, m.geometry.height_m,
        m.geometry.thickness_m, m.geometry.radius_m
    )

    private fun keyDims(m: ShellVesselModel) = listOf(
        m.geometry.length_m, m.geometry.width_m, m.geometry.height_m,
        m.geometry.thickness_m, m.geometry.radius_m
    )
}
