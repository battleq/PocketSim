/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

// =====================================================================
// AI 结构优化建议：数据契约 + 模型差异 diff + 指标对比 + 会话
// 全部为纯 Kotlin（无 Android 依赖），可直接 JVM 单测。
// =====================================================================

/** 杆件几何匹配容差（mm）：两端节点坐标差在容差内视为同一根杆件 */
private const val GEO_TOL_MM = 0.5

/** 单个优化方案（AI 产出，经 AiParser 校验后 modelJson 为规范化 JSON） */
data class OptimizeSuggestion(
    val title: String = "",
    val desc: String = "",
    val expected: String = "",
    val changes: List<String> = emptyList(),
    /** 完整优化模型 JSON；null 表示 AI 未给出可解析模型 */
    val modelJson: String? = null,
    /**
     * O-07 增量补丁 JSON 原文（输出契约 patch 字段）。
     * 解析链路会把它应用到原模型上合成完整模型并填入 [modelJson]，成功后置回 null。
     */
    val patchJson: String? = null
)

/** AI 优化建议回复 */
data class OptimizeReply(
    val ready: Boolean = false,
    val diagnosis: String = "",
    val suggestions: List<OptimizeSuggestion> = emptyList(),
    val disclaimer: String = ""
)

// ---------------------------------------------------------------------
// O-07：增量补丁输出契约——每方案只写改动，客户端在原 FrameModel 上
// 确定性合成完整新模型。v1 只允许新增与修改、禁止 delete；
// 载荷不进 patch（载荷零改动由"无载荷字段"+ O-03 loadSignature 双重保证）。
// 全部字段带默认值；序列化走 LeanPromptJson 风格（encodeDefaults=false，省略默认值）。
// ---------------------------------------------------------------------

/** 单个方案的增量补丁（对应输出契约的 patch 字段） */
@Serializable
data class OptimizePatch(
    /** 新增节点：id 必须大于原模型最大节点号且不与现有/其他新增冲突 */
    val add_nodes: List<FrameNode> = emptyList(),
    /** 新增杆件：id 必须大于原模型最大杆件号；i/j 必须指向合成后存在的节点 */
    val add_members: List<FrameMember> = emptyList(),
    /** 新增支座：挂载节点必须存在；原有支座只增不松、不改动 */
    val add_supports: List<FrameSupport> = emptyList(),
    /** 修改已有杆件：id 必须在原模型中存在；未列出的字段保持不变 */
    val modify_members: List<FrameMemberPatch> = emptyList()
)

/**
 * modify_members 元素：只写 id 与真正要改的字段，null 表示该字段不变。
 * i/j 允许改指（无 delete 约束下"把原杆件一端改到新节点"即表达"拆分"）；
 * 载荷相关字段不在其中，一律不可改。
 */
@Serializable
data class FrameMemberPatch(
    val id: Int = 0,
    val i: Int? = null,
    val j: Int? = null,
    val width_b: Double? = null,
    val depth_h: Double? = null,
    val wall_t: Double? = null,
    val material: String? = null,
    val shape: String? = null,
    val outer_diameter_mm: Double? = null
)

/**
 * O-07：在原模型上应用增量补丁，确定性合成完整新模型。
 * 校验任一失败抛 [AiParseException]（中文原因）：
 * ①新增 id 必须大于原模型对应最大编号且不与现有/其他新增冲突；
 * ②modify 引用 id 必须存在、不重复；
 * ③引用完整性：新杆件端点节点、修改后的端点节点、新支座挂载节点必须存在；
 * ④载荷原样透传（patch 无载荷字段，构造性零改动）。
 */
internal object OptimizePatchApplier {

    fun apply(base: FrameModel, patch: OptimizePatch): FrameModel {
        val nodeIds = base.nodes.mapTo(mutableSetOf()) { it.id }
        val memberIds = base.members.mapTo(mutableSetOf()) { it.id }
        val maxNodeId = nodeIds.maxOrNull() ?: 0
        val maxMemberId = memberIds.maxOrNull() ?: 0

        // ① 新增节点
        patch.add_nodes.forEach { n ->
            if (n.id <= maxNodeId)
                throw AiParseException("[O-07] 新增节点 id=${n.id} 必须大于原模型最大节点号 $maxNodeId")
            if (!nodeIds.add(n.id))
                throw AiParseException("[O-07] 新增节点 id=${n.id} 与原模型或补丁内其他节点冲突")
        }

        // ② 修改已有杆件：id 必须存在且不重复
        val modifyById = mutableMapOf<Int, FrameMemberPatch>()
        patch.modify_members.forEach { mod ->
            if (mod.id !in memberIds)
                throw AiParseException(
                    "[O-07] modify_members 引用的杆件 id=${mod.id} 在原模型中不存在" +
                        "（现有杆件号：${memberIds.sorted().joinToString(", ")}）"
                )
            if (modifyById.put(mod.id, mod) != null)
                throw AiParseException("[O-07] modify_members 中杆件 id=${mod.id} 重复")
        }

        // ③ 新增杆件：id 规则 + 端点引用完整性
        val newMembers = patch.add_members.onEach { m ->
            if (m.id <= maxMemberId)
                throw AiParseException("[O-07] 新增杆件 id=${m.id} 必须大于原模型最大杆件号 $maxMemberId")
            if (!memberIds.add(m.id))
                throw AiParseException("[O-07] 新增杆件 id=${m.id} 与原模型或补丁内其他杆件冲突")
            if (m.i !in nodeIds || m.j !in nodeIds)
                throw AiParseException("[O-07] 新增杆件 #${m.id} 的端点节点 ${m.i}→${m.j} 不存在（须先存在于原模型或 add_nodes）")
        }

        // ④ 新增支座：挂载节点必须存在（原有 supports 不动，只增不松）
        patch.add_supports.forEach { s ->
            if (s.node !in nodeIds)
                throw AiParseException("[O-07] 新增支座挂载的节点 ${s.node} 不存在")
        }

        val mergedMembers = base.members.map { mb ->
            val mod = modifyById[mb.id] ?: return@map mb
            mb.copy(
                i = mod.i ?: mb.i,
                j = mod.j ?: mb.j,
                width_b = mod.width_b ?: mb.width_b,
                depth_h = mod.depth_h ?: mb.depth_h,
                wall_t = mod.wall_t ?: mb.wall_t,
                material = mod.material ?: mb.material,
                shape = mod.shape ?: mb.shape,
                outer_diameter_mm = mod.outer_diameter_mm ?: mb.outer_diameter_mm
            )
        }
        // 修改后的端点同样校验引用完整性
        mergedMembers.forEach { m ->
            if (m.i !in nodeIds || m.j !in nodeIds)
                throw AiParseException("[O-07] 杆件 #${m.id} 修改后的端点节点 ${m.i}→${m.j} 不存在")
        }

        return base.copy(
            nodes = base.nodes + patch.add_nodes,
            members = mergedMembers + newMembers,
            supports = base.supports + patch.add_supports
            // loads 原样透传：patch 不含载荷字段，构造性保证载荷零改动（O-07）
        )
    }
}

/** 差异类型 */
enum class ChangeKind { ADDED, MODIFIED, REMOVED }

/** 单条结构差异 */
data class ModelChange(
    val kind: ChangeKind,
    val label: String,
    val detail: String
)

/**
 * 原模型 → 优化模型的确定性差异。
 *
 * 用途：①效果图高亮新增/修改构件；②"改了什么"摘要；③校验载荷/约束未被改动（防作弊优化）。
 * [addedMemberIds] / [modifiedMemberIds] 里的 id 均为**优化后模型**的杆件 id（效果图按优化后模型作图）。
 * 匹配按几何（两端节点坐标）而非 id，避免 AI 拆分/重排编号导致图文不符。
 */
data class ModelDiff(
    val changes: List<ModelChange> = emptyList(),
    /** 载荷或约束被改动（优化不允许靠减载/松约束提高安全度） */
    val loadOrSupportChanged: Boolean = false,
    val addedMemberIds: Set<Int> = emptySet(),
    val modifiedMemberIds: Set<Int> = emptySet()
) {
    val isEmpty: Boolean get() = changes.isEmpty()

    companion object {
        val EMPTY = ModelDiff()
    }
}

/** 优化前后用于对比的关键指标（应力类指标已排除奇点） */
data class OptimizeMetrics(
    val safetyFactor: Double? = null,
    val maxDispMm: Double? = null,
    val signatureStressMPa: Double? = null,
    /** 特征应力是否已排除应力奇点（Python 支持时为 true） */
    val stressExcludesSingularity: Boolean = false
)

/** 单个方案的有限元验证结果（由调用方通过引擎重算产生） */
data class VerificationResult(
    val ok: Boolean,
    val afterModelJson: String = "",
    val afterMetrics: OptimizeMetrics? = null,
    val failureReason: String = ""
)

/** 已验证的优化方案（HUD 展示单元） */
data class VerifiedOptimization(
    val suggestion: OptimizeSuggestion,
    val verified: Boolean,
    val failureReason: String = "",
    val diff: ModelDiff = ModelDiff.EMPTY,
    val before: OptimizeMetrics = OptimizeMetrics(),
    val after: OptimizeMetrics = OptimizeMetrics()
)

/** 优化会话状态 */
sealed interface OptimizeState {
    data object Idle : OptimizeState
    /** 进行中：[verified] 为已完成"请求+求解验证"的方案（按方案编号顺序，供逐个呈现） */
    data class Running(val verified: List<VerifiedOptimization> = emptyList()) : OptimizeState
    data class Done(
        val reply: OptimizeReply,
        val verified: List<VerifiedOptimization>
    ) : OptimizeState
    data class Failed(val message: String) : OptimizeState
}

/** 优化目标（仅第一期支持的 Frame / SolidGeneric） */
enum class OptimizeKind(val wire: String) {
    FRAME("frame"),
    SOLID_GENERIC("solid_generic");

    companion object {
        fun fromWire(v: String): OptimizeKind? =
            entries.firstOrNull { it.wire.equals(v.trim(), true) }
    }
}

/** 会话的固定上下文 */
data class OptimizeTarget(
    val kind: OptimizeKind,
    /** 原始模型完整 JSON */
    val modelJson: String,
    /** 结果摘要（含危险源定位，供 AI 诊断） */
    val summary: String,
    val before: OptimizeMetrics = OptimizeMetrics()
)

/**
 * 结果页入口门控：只有 注意 / 危险 才提供"AI 优化建议"。
 * 安全 / 无安全系数（UNKNOWN）一律跳过。
 */
object OptimizeGate {
    fun shouldOffer(grade: SafetyLogic.Grade): Boolean =
        grade == SafetyLogic.Grade.CAUTION || grade == SafetyLogic.Grade.DANGER
}

// ---------------------------------------------------------------------
// 结构体系画像：按梁杆模型确定性计算，喂给 AI 作为"改体系"的判断依据
// ---------------------------------------------------------------------
internal object FrameProfile {

    private const val MAX_MEMBER_LINES = 60

    private fun f0(v: Double): String = String.format(java.util.Locale.ROOT, "%.0f", v)
    private fun g3(v: Double): String = String.format(java.util.Locale.ROOT, "%.3g", v)

    fun describe(m: FrameModel, english: Boolean): String {
        if (m.nodes.isEmpty() || m.members.isEmpty()) return ""
        fun t(zh: String, en: String) = if (english) en else zh
        val is3d = m.dim == 3
        val nodes = m.nodes.associateBy { it.id }
        fun vert(n: FrameNode) = if (is3d) n.z else n.y
        val supports = m.supports.associateBy { it.node }
        val degree = mutableMapOf<Int, Int>()
        m.members.forEach { degree[it.i] = (degree[it.i] ?: 0) + 1; degree[it.j] = (degree[it.j] ?: 0) + 1 }
        val ground = m.nodes.minOf { vert(it) }
        val vAxis = if (is3d) "z" else "y"

        fun endText(id: Int): String {
            val s = supports[id]
            return when {
                s != null -> t("支座 ${s.type}", "support ${s.type}")
                (degree[id] ?: 0) <= 1 -> t("自由端", "free end")
                else -> t("连接 ${degree[id]} 杆", "joint of ${degree[id]} members")
            }
        }

        val sb = StringBuilder()
        sb.append(
            t(
                "维度 ${if (is3d) "3D" else "2D"}；竖向轴 $vAxis；地面/最低标高 $vAxis=${f0(ground)} mm；" +
                    "最大节点号 ${m.nodes.maxOf { it.id }}，最大杆件号 ${m.members.maxOf { it.id }}（新增编号从其 +1 起）\n",
                "Dimension ${if (is3d) "3D" else "2D"}; vertical axis $vAxis; ground/lowest level $vAxis=${f0(ground)} mm; " +
                    "max node id ${m.nodes.maxOf { it.id }}, max member id ${m.members.maxOf { it.id }} (new ids start at +1)\n"
            )
        )
        sb.append(t("支座：", "Supports: "))
        sb.append(
            if (m.supports.isEmpty()) t("无", "none")
            else m.supports.joinToString("，") { s ->
                val n = nodes[s.node]
                "#${s.node} ${s.type}" + (n?.let { " @(${f0(it.x)},${f0(it.y)}${if (is3d) "," + f0(it.z) else ""})" } ?: "")
            }
        )
        sb.append('\n')

        val spanTags = mutableListOf<String>()
        sb.append(t("杆件：\n", "Members:\n"))
        m.members.take(MAX_MEMBER_LINES).forEach { mb ->
            val a = nodes[mb.i] ?: return@forEach
            val b = nodes[mb.j] ?: return@forEach
            val dx = b.x - a.x
            val dy = b.y - a.y
            val dz = b.z - a.z
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            val dv = abs(vert(b) - vert(a))
            val dh = sqrt((len * len - dv * dv).coerceAtLeast(0.0))
            val orient = when {
                len < 1e-6 -> t("零长度", "zero-length")
                dv <= 0.05 * len -> t("水平", "horizontal")
                dh <= 0.05 * len -> t("竖向", "vertical")
                else -> t("斜向", "inclined")
            }
            val sec = if (english) com.pocketsim.app.ui.sectionTextOf(mb, " (tube)", " (solid round)", " (hollow)") else mb.sectionText
            val loads = m.loads.mapNotNull { ld ->
                when {
                    ld.type == "udl" && ld.member == mb.id -> "udl(${g3(ld.qx)},${g3(ld.qy)},${g3(ld.qz)}) N/mm"
                    ld.type == "area" && ld.members?.contains(mb.id) == true -> "area ${g3(ld.total_N)} N"
                    ld.type == "thermal" && ld.member == mb.id -> "thermal"
                    else -> null
                }
            }
            sb.append(
                "#${mb.id} ${mb.i}→${mb.j} L=${f0(len)} mm $orient；$sec；${materialDisplayName(mb.material, english)}；" +
                    t("端部 ", "ends ") + "${mb.i}:${endText(mb.i)} / ${mb.j}:${endText(mb.j)}" +
                    (if (loads.isEmpty()) "" else t("；载荷 ", "; loads ") + loads.joinToString(", ")) + "\n"
            )
            // 体系标签：水平受弯杆件的支承形式
            if (orient == t("水平", "horizontal")) {
                val freeI = supports[mb.i] == null && (degree[mb.i] ?: 0) <= 1
                val freeJ = supports[mb.j] == null && (degree[mb.j] ?: 0) <= 1
                val simple = { id: Int -> supports[id]?.type?.lowercase() in setOf("pinned", "roller") && (degree[id] ?: 0) <= 1 }
                when {
                    freeI xor freeJ -> spanTags += t(
                        "#${mb.id} 为悬臂（自由端 ${if (freeI) mb.i else mb.j}，悬挑 ${f0(len)} mm）→ 可考虑配方 C 斜撑/拉杆",
                        "#${mb.id} is a cantilever (free end ${if (freeI) mb.i else mb.j}, ${f0(len)} mm) → consider recipe C strut/tie"
                    )
                    simple(mb.i) && simple(mb.j) -> spanTags += t(
                        "#${mb.id} 为单跨简支梁（跨度 ${f0(len)} mm）→ 优先配方 A 增设中间支座 / 配方 B 改桁架",
                        "#${mb.id} is a single simply supported span (${f0(len)} mm) → prefer recipe A mid support / recipe B truss"
                    )
                    len >= 600 && vert(a) - ground > 1.0 -> spanTags += t(
                        "#${mb.id} 为悬空水平跨（跨度 ${f0(len)} mm，离地 ${f0(vert(a) - ground)} mm）→ 可考虑配方 A 加中柱落地",
                        "#${mb.id} is an elevated horizontal span (${f0(len)} mm, ${f0(vert(a) - ground)} mm above ground) → consider recipe A mid column"
                    )
                }
            }
        }
        if (m.members.size > MAX_MEMBER_LINES) {
            sb.append(t("……其余 ${m.members.size - MAX_MEMBER_LINES} 根略\n", "... ${m.members.size - MAX_MEMBER_LINES} more omitted\n"))
        }
        val forces = m.loads.filter { it.type == "force" && it.node != null }
        if (forces.isNotEmpty()) {
            sb.append(t("节点集中力：", "Nodal forces: "))
            sb.append(forces.joinToString("，") { "#${it.node} (${g3(it.fx)},${g3(it.fy)},${g3(it.fz)}) N" })
            sb.append('\n')
        }
        if (spanTags.isNotEmpty()) {
            sb.append(t("体系判断：\n", "System diagnosis:\n"))
            spanTags.forEach { sb.append("- ").append(it).append('\n') }
        }
        return sb.toString().trimEnd()
    }
}

// ---------------------------------------------------------------------
// 模型差异 diff
// ---------------------------------------------------------------------
object OptimizeDiff {

    /** 双语切换：英文界面下改动清单必须走英文（v1.2.35 修复） */
    private fun t(english: Boolean, zh: String, en: String): String = if (english) en else zh

    /** 按类型分发 */
    fun diff(kind: OptimizeKind, beforeJson: String, afterJson: String, english: Boolean = false): ModelDiff = try {
        when (kind) {
            OptimizeKind.FRAME -> diffFrame(
                AppJson.decodeFromString(FrameModel.serializer(), beforeJson),
                AppJson.decodeFromString(FrameModel.serializer(), afterJson),
                english
            )
            OptimizeKind.SOLID_GENERIC -> diffSolidGeneric(
                AppJson.decodeFromString(SolidGenericModel.serializer(), beforeJson),
                AppJson.decodeFromString(SolidGenericModel.serializer(), afterJson),
                english
            )
        }
    } catch (e: Exception) {
        ModelDiff.EMPTY
    }

    fun diffFrame(before: FrameModel, after: FrameModel, english: Boolean = false): ModelDiff {
        val changes = mutableListOf<ModelChange>()
        val addedMembers = linkedSetOf<Int>()
        val modifiedMembers = linkedSetOf<Int>()

        // 几何匹配：AI 常把原杆件拆成多段并改编号，用 id 做集合差会误判"整段新增"
        val beforeGeom = before.members.associate { it.id to segmentOf(before, it) }
        val afterGeom = after.members.associate { it.id to segmentOf(after, it) }
        val reusedBefore = linkedSetOf<Int>()

        after.members.forEach { a ->
            val seg = afterGeom[a.id] ?: return@forEach
            // ① 几何完全重合：同一根杆件（可能被重新编号），只有截面/材料变化才算修改
            val same = before.members.firstOrNull { b -> beforeGeom[b.id]?.sameAs(seg) == true }
            if (same != null) {
                reusedBefore += same.id
                val detail = memberChangeDetail(same, a, reportConnection = false, english = english)
                if (detail != null) {
                    modifiedMembers += a.id
                    changes += ModelChange(
                        ChangeKind.MODIFIED,
                        t(english, "修改杆件 #${a.id}", "Modify member #${a.id}"),
                        detail
                    )
                }
                return@forEach
            }
            // ② 是某根原杆件的共线子段：原杆件被拆分，计入"修改"而不是"新增"
            val host = before.members.firstOrNull { b -> beforeGeom[b.id]?.containsSub(seg) == true }
            if (host != null) {
                reusedBefore += host.id
                modifiedMembers += a.id
                val detail = memberChangeDetail(host, a, reportConnection = false, english = english)
                val base = t(english, "由原杆件 #${host.id} 拆分而来", "Split from original member #${host.id}")
                changes += ModelChange(
                    ChangeKind.MODIFIED,
                    t(english, "修改杆件 #${a.id}（拆分）", "Modify member #${a.id} (split)"),
                    if (detail == null) base else base + t(english, "；", "; ") + detail
                )
                return@forEach
            }
            // ③ 找不到任何对应几何：真正新增（如新增中柱）
            addedMembers += a.id
            val sec = com.pocketsim.app.ui.sectionTextOf(
                a,
                if (english) " (tube)" else "（圆管）",
                if (english) " (solid round)" else "（实心圆）",
                if (english) " (hollow)" else "（空心）"
            )
            val mat = materialDisplayName(a.material, english)
            changes += ModelChange(
                ChangeKind.ADDED,
                t(english, "新增杆件 #${a.id}（节点 ${a.i}→${a.j}）", "Add member #${a.id} (nodes ${a.i}→${a.j})"),
                t(english, "截面 $sec，材料 $mat", "Section $sec, material $mat")
            )
        }

        before.members.filter { it.id !in reusedBefore }.forEach { b ->
            changes += ModelChange(
                ChangeKind.REMOVED,
                t(english, "删除杆件 #${b.id}（节点 ${b.i}→${b.j}）", "Remove member #${b.id} (nodes ${b.i}→${b.j})"),
                ""
            )
        }

        // 支撑 / 载荷结构对比（用于防作弊：允许新增支撑，但不允许放松约束或减载）
        val loadsChanged = loadSignature(before) != loadSignature(after)
        // 新增支撑是合法优化手段，不计入"作弊"；只有"减少约束"或"改动载荷"才拦截
        val supportRelaxed = supportRelaxed(before, after)

        return ModelDiff(
            changes = changes,
            loadOrSupportChanged = loadsChanged || supportRelaxed,
            addedMemberIds = addedMembers,
            modifiedMemberIds = modifiedMembers
        )
    }

    /** 杆件两端的节点坐标（无序对） */
    private data class Segment(
        val ax: Double, val ay: Double, val az: Double,
        val bx: Double, val by: Double, val bz: Double
    ) {
        /** 两个端点是否与另一根杆件重合（不考虑方向） */
        fun sameAs(o: Segment): Boolean =
            (near(ax, ay, az, o.ax, o.ay, o.az) && near(bx, by, bz, o.bx, o.by, o.bz)) ||
                (near(ax, ay, az, o.bx, o.by, o.bz) && near(bx, by, bz, o.ax, o.ay, o.az))

        /** o 的两个端点是否都落在本杆件上（即 o 是本杆件的共线子段） */
        fun containsSub(o: Segment): Boolean =
            paramOn(o.ax, o.ay, o.az) != null && paramOn(o.bx, o.by, o.bz) != null

        private fun near(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double): Boolean =
            abs(x1 - x2) <= GEO_TOL_MM && abs(y1 - y2) <= GEO_TOL_MM && abs(z1 - z2) <= GEO_TOL_MM

        /** 点到本杆件所在线段的参数 t（0..1）；不在线段上则返回 null */
        private fun paramOn(px: Double, py: Double, pz: Double): Double? {
            val dx = bx - ax
            val dy = by - ay
            val dz = bz - az
            val len2 = dx * dx + dy * dy + dz * dz
            if (len2 < 1e-9) return null
            val t = ((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / len2
            if (t < -1e-6 || t > 1.0 + 1e-6) return null
            val ddx = px - (ax + dx * t)
            val ddy = py - (ay + dy * t)
            val ddz = pz - (az + dz * t)
            return if (sqrt(ddx * ddx + ddy * ddy + ddz * ddz) <= GEO_TOL_MM) t else null
        }
    }

    /** 取杆件的几何端点（节点缺失时返回 null，参与不了匹配） */
    private fun segmentOf(model: FrameModel, m: FrameMember): Segment? {
        val ni = model.nodes.firstOrNull { it.id == m.i } ?: return null
        val nj = model.nodes.firstOrNull { it.id == m.j } ?: return null
        return Segment(ni.x, ni.y, ni.z, nj.x, nj.y, nj.z)
    }

    fun diffSolidGeneric(
        before: SolidGenericModel,
        after: SolidGenericModel,
        english: Boolean = false
    ): ModelDiff {
        val changes = mutableListOf<ModelChange>()
        val b = before.geometry
        val a = after.geometry
        if (!b.type.equals(a.type, true)) {
            changes += ModelChange(ChangeKind.MODIFIED, t(english, "几何类型", "Geometry type"), "${b.type} → ${a.type}")
        }
        dimChange(t(english, "长度", "Length"), b.length_m, a.length_m)?.let { changes += it }
        dimChange(t(english, "高度", "Height"), b.height_m, a.height_m)?.let { changes += it }
        dimChange(t(english, "宽度", "Width"), b.width_m, a.width_m)?.let { changes += it }
        dimChange(t(english, "厚度", "Thickness"), b.thickness_m, a.thickness_m)?.let { changes += it }
        dimChange(t(english, "半径", "Radius"), b.radius_m, a.radius_m)?.let { changes += it }

        if (!before.material.equals(after.material, true)) {
            changes += ModelChange(
                ChangeKind.MODIFIED, t(english, "材料", "Material"),
                "${materialDisplayName(before.material, english)} → ${materialDisplayName(after.material, english)}"
            )
        }
        if (before.eMPa != after.eMPa || before.nu != after.nu) {
            changes += ModelChange(
                ChangeKind.MODIFIED, t(english, "材料常数", "Material constants"),
                "E ${before.eMPa ?: "-"} → ${after.eMPa ?: "-"} MPa" +
                    t(english, "，ν ${before.nu ?: "-"} → ${after.nu ?: "-"}", ", ν ${before.nu ?: "-"} → ${after.nu ?: "-"}")
            )
        }
        val holeCountBefore = before.geometry.holes?.size ?: 0
        val holeCountAfter = after.geometry.holes?.size ?: 0
        if (holeCountBefore != holeCountAfter) {
            changes += ModelChange(
                ChangeKind.MODIFIED, t(english, "孔洞数量", "Hole count"), "$holeCountBefore → $holeCountAfter"
            )
        }
        val loadsChanged = loadSignatureOf(before) != loadSignatureOf(after)
        val supportRelaxed = supportRelaxedOf(before, after)
        if (loadsChanged) {
            changes += ModelChange(
                ChangeKind.MODIFIED,
                t(english, "载荷被改动", "Loads modified"),
                t(english, "优化不应修改载荷", "Optimization should not change loads")
            )
        }
        return ModelDiff(
            changes = changes,
            loadOrSupportChanged = loadsChanged || supportRelaxed
        )
    }

    private fun dimChange(label: String, before: Double?, after: Double?): ModelChange? {
        if (before == after) return null
        return ModelChange(
            ChangeKind.MODIFIED, label,
            "%s → %s m".format(fmtNullable(before), fmtNullable(after))
        )
    }

    private fun fmtNullable(v: Double?): String =
        if (v == null) "-" else if (v == v.toLong().toDouble()) v.toLong().toString() else "%.3f".format(v)

    /**
     * 杆件的截面/材料变化文案；无变化返回 null。
     * [reportConnection] 为 false 时不报告节点编号变化（几何匹配下编号可能只是重排）。
     */
    private fun memberChangeDetail(
        b: FrameMember,
        a: FrameMember,
        reportConnection: Boolean = true,
        english: Boolean = false
    ): String? {
        val parts = mutableListOf<String>()
        if (b.isCircle != a.isCircle) {
            parts += if (a.isCircle)
                t(english, "截面改为圆管 ⌀${a.outer_diameter_mm}", "Section changed to circular tube ⌀${a.outer_diameter_mm}")
            else t(english, "截面改为矩形", "Section changed to rectangular")
        } else if (a.isCircle) {
            if (b.outer_diameter_mm != a.outer_diameter_mm) {
                parts += t(english, "外径 ${b.outer_diameter_mm} → ${a.outer_diameter_mm} mm",
                    "OD ${b.outer_diameter_mm} → ${a.outer_diameter_mm} mm")
            }
        } else {
            if (b.width_b != a.width_b) parts += t(english, "宽 ${b.width_b} → ${a.width_b} mm", "Width ${b.width_b} → ${a.width_b} mm")
            if (b.depth_h != a.depth_h) parts += t(english, "高 ${b.depth_h} → ${a.depth_h} mm", "Height ${b.depth_h} → ${a.depth_h} mm")
        }
        if (b.wall_t != a.wall_t) parts += t(english, "壁厚 ${b.wall_t} → ${a.wall_t} mm", "Wall ${b.wall_t} → ${a.wall_t} mm")
        if (!b.material.equals(a.material, true)) parts += t(
            english,
            "材料 ${b.material} → ${a.material}",
            "Material ${materialDisplayName(b.material, english)} → ${materialDisplayName(a.material, english)}"
        )
        if (reportConnection && (b.i != a.i || b.j != a.j))
            parts += t(english, "连接 ${b.i}→${b.j} 改为 ${a.i}→${a.j}", "Connection ${b.i}→${b.j} changed to ${a.i}→${a.j}")
        return if (parts.isEmpty()) null else parts.joinToString(if (english) "; " else "；")
    }

    private fun supportSignature(m: FrameModel): Set<String> =
        m.supports.map { "${it.node}:${it.type}:${it.fix.sorted().joinToString(",")}" }.toSet()

    /**
     * 载荷几何指纹（与编号无关）：
     * - 集中力按作用点坐标 + 分量；
     * - 均布载荷按强度集合 + 合力（q×L 求和）——体系级改造拆分杆件后，udl 随子段重新挂载，
     *   只要强度与合力不变就不算改载荷（v1.2.39 按杆件 id 比对，会把合法的连续梁/桁架方案误判为作弊）；
     * - 面/温度/风载按数值字段（不含杆件 id 列表）。
     */
    private fun loadSignature(m: FrameModel): Set<String> {
        val nodes = m.nodes.associateBy { it.id }
        val members = m.members.associateBy { it.id }
        fun r(v: Double): String = String.format(java.util.Locale.ROOT, "%.0f", v)
        fun g(v: Double): String = if (abs(v) < 1e-9) "0" else String.format(java.util.Locale.ROOT, "%.3g", v)
        val sig = mutableSetOf<String>()
        var rx = 0.0
        var ry = 0.0
        var rz = 0.0
        m.loads.forEach { l ->
            when (l.type) {
                "force" -> {
                    val n = l.node?.let { nodes[it] }
                    val at = if (n == null) "?${l.node}" else "${r(n.x)},${r(n.y)},${r(n.z)}"
                    sig += "F@$at|${g(l.fx)}|${g(l.fy)}|${g(l.fz)}|${g(l.mx)}|${g(l.my)}|${g(l.mz)}"
                }
                "udl" -> {
                    sig += "Q|${g(l.qx)}|${g(l.qy)}|${g(l.qz)}"
                    val mem = l.member?.let { members[it] }
                    val a = mem?.let { nodes[it.i] }
                    val b = mem?.let { nodes[it.j] }
                    if (a != null && b != null) {
                        val len = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y) + (b.z - a.z) * (b.z - a.z))
                        rx += l.qx * len
                        ry += l.qy * len
                        rz += l.qz * len
                    } else {
                        sig += "Q?${l.member}"
                    }
                }
                else -> sig += listOf(
                    l.type, l.total_N, l.delta_temp, l.wind_speed_m_s, l.beaufort, l.dir, l.area_m2, l.cp
                ).joinToString("|")
            }
        }
        if (rx != 0.0 || ry != 0.0 || rz != 0.0) sig += "QR|${g(rx)}|${g(ry)}|${g(rz)}"
        return sig
    }

    /** 约束被减少（原约束消失或自由度被放松）→ 视为不合规 */
    private fun supportRelaxed(before: FrameModel, after: FrameModel): Boolean {
        val afterSigs = supportSignature(after)
        return supportSignature(before).any { it !in afterSigs }
    }

    /** 数据类 toString 已覆盖全部字段，用作结构指纹（稳定且无需逐字段维护） */
    private fun supportSignatureOf(m: SolidGenericModel): Set<String> =
        m.supports.map { it.toString() }.toSet()

    private fun loadSignatureOf(m: SolidGenericModel): Set<String> =
        m.loads.map { it.toString() }.toSet()

    private fun supportRelaxedOf(before: SolidGenericModel, after: SolidGenericModel): Boolean {
        val afterSigs = supportSignatureOf(after)
        return supportSignatureOf(before).any { it !in afterSigs }
    }
}

// ---------------------------------------------------------------------
// 指标对比
// ---------------------------------------------------------------------
data class MetricDelta(
    val label: String,
    val beforeText: String,
    val afterText: String,
    /** 改善百分比（正值=变大；位移/应力取反后为改善） */
    val changePct: Double?,
    val better: Boolean
)

object OptimizeCompare {

    /** 安全系数目标线：达到即达标 */
    const val SAFE_SF = 2.0

    fun isSafeAfter(after: OptimizeMetrics): Boolean {
        val sf = after.safetyFactor ?: return false
        return sf >= SAFE_SF
    }

    /**
     * 三项指标对比。
     * 安全系数越大越好；位移、特征应力越小越好。
     */
    fun deltas(before: OptimizeMetrics, after: OptimizeMetrics, english: Boolean = false): List<MetricDelta> = listOf(
        delta(if (english) "Safety factor" else "安全系数", before.safetyFactor, after.safetyFactor, higherBetter = true, digits = 2, unit = ""),
        delta(if (english) "Max displacement" else "最大位移", before.maxDispMm, after.maxDispMm, higherBetter = false, digits = 2, unit = " mm"),
        delta(
            if (english) "Signature stress (excl. singularities)" else "特征应力（排除奇点）",
            before.signatureStressMPa, after.signatureStressMPa,
            higherBetter = false, digits = 2, unit = " MPa"
        )
    )

    private fun delta(
        label: String,
        b: Double?,
        a: Double?,
        higherBetter: Boolean,
        digits: Int,
        unit: String
    ): MetricDelta {
        val bText = b?.let { fmt(it, digits) + unit } ?: "—"
        val aText = a?.let { fmt(it, digits) + unit } ?: "—"
        val pct = if (b != null && a != null && b != 0.0) (a - b) / b * 100.0 else null
        val better = when {
            b == null || a == null -> false
            else -> if (higherBetter) a > b else a < b
        }
        return MetricDelta(label, bText, aText, pct, better)
    }

    private fun fmt(v: Double, digits: Int): String =
        if (v >= 1000) String.format("%.0f", v) else String.format("%.${digits}f", v)
}

// ---------------------------------------------------------------------
// AI 优化建议会话
// ---------------------------------------------------------------------
/**
 * 只做一次交互（不是多轮对话）：读设置里的方案数量 → 请求 AI → AiParser 校验
 * → 逐个方案有限元重算验证（由 [verifier] 注入）→ 产出可展示的已验证方案。
 *
 * verifier 由调用方用 FeaEngine 实现，便于 JVM 单测注入假实现。
 */
class OptimizeSession(
    private val aiClientProvider: suspend () -> AiClient,
    private val target: OptimizeTarget,
    private val suggestionCount: Int = 1,
    private val language: String = LocaleStore.LANG_ZH,
    /** O-06 快速模式：优化通道单独把思考强度压到 none（服务不支持时客户端自动回退） */
    private val fastMode: Boolean = true,
    private val verifier: suspend (OptimizeSuggestion) -> VerificationResult
) {

    private val english = language == LocaleStore.LANG_EN

    private fun t(zh: String, en: String): String = if (english) en else zh

    private val _state = MutableStateFlow<OptimizeState>(OptimizeState.Idle)
    val state: StateFlow<OptimizeState> = _state.asStateFlow()

    /** 请求的方案数量（合法 1..3） */
    val count: Int = suggestionCount.coerceIn(1, 3)

    val systemPrompt: String = buildString {
        append(if (english) PROMPT_EN else PROMPT_ZH)
        if (target.kind == OptimizeKind.FRAME) {
            val profile = try {
                FrameProfile.describe(AppJson.decodeFromString(FrameModel.serializer(), target.modelJson), english)
            } catch (e: Exception) {
                ""
            }
            if (profile.isNotBlank()) {
                append(if (english) "\n\n[Structural profile]\n" else "\n\n【结构体系画像】\n")
                append(profile)
            }
        }
        if (target.kind == OptimizeKind.SOLID_GENERIC) {
            // O-07：增量补丁契约只适用于梁杆模型，三维实体通道显式改回完整模型输出
            append(
                if (english) "\n\n[Note] This is a 3D solid model: the incremental patch contract above does NOT " +
                    "apply — put the COMPLETE new model in each option's model field instead."
                else "\n\n【说明】本模型为三维实体：上述增量补丁（patch）契约不适用，" +
                    "每个方案直接在 model 字段输出完整的新模型（写法与【当前模型】一致）。"
            )
        }
        append("\n\n【当前模型（JSON）】\n")
        append(leanModelJson(target.kind, target.modelJson))
        append("\n\n【本次分析结果摘要】\n")
        append(target.summary)
    }

    suspend fun run() {
        if (_state.value is OptimizeState.Running) return
        _state.value = OptimizeState.Running()
        try {
            val base = aiClientProvider()
            // O-06 快速模式：优化方案是工程经验套路，不需要深想；
            // 质量由确定性校验 + 有限元重算兜底。不支持 none 的服务在 LlmClient 内自动回退。
            val client = if (fastMode) base.withReasoningEffort("none") else base
            if (count == 1) {
                val reply = requestOnce(client, null)
                if (reply.suggestions.isEmpty()) {
                    _state.value = OptimizeState.Failed(
                        t("AI 未返回可用的优化方案，请重试。", "AI returned no usable optimization options. Please retry.")
                    )
                    return
                }
                val verified = reply.suggestions.map { s -> verifyOne(s) }
                _state.value = OptimizeState.Done(reply, verified)
            } else {
                runPipelined(client)
            }
        } catch (e: Exception) {
            _state.value = OptimizeState.Failed(
                t("AI 优化方案生成失败：${e.readableMessage()}", "Failed to generate an optimization plan: ${e.readableMessage()}")
            )
        }
    }

    private fun userMessage(slot: Int?): String {
        if (slot == null) {
            return t(
                "请给出 $count 个结构优化方案，用于把结论从警告/危险提升到安全。" +
                    "方案 1 必须是体系级改造（增设支座/中柱、改连续梁、改桁架、加撑杆或拉杆），" +
                    "不得只加大截面或更换材料。",
                "Give $count structural optimization options to raise the verdict from caution/danger to safe. " +
                    "Option 1 MUST be a system-level change (extra support/column, continuous beam, truss, strut or tie), " +
                    "not just a bigger section or a stronger material."
            )
        }
        val head = t(
            "本次只给出 1 个方案：它是共 $count 个方案中的方案 $slot，suggestions 数组只放这 1 个方案。",
            "Give exactly ONE option this time: option $slot of $count. Put only this option in suggestions. "
        )
        val rule = when (slot) {
            1 -> t(
                "方案 1 必须是体系级改造（增设支座/中柱、改连续梁、改桁架、加撑杆或拉杆），不得只加大截面或更换材料；",
                "Option 1 MUST be a system-level change (extra support/column, continuous beam, truss, strut or tie), not just a bigger section or material; "
            )
            2 -> t(
                "方案 2 应选用与最直接的体系级改造不同的配方（方案 1 通常是增设支座/中柱或加斜撑，你优先考虑改桁架或其他撑杆布置），或体系级 + 小幅截面调整；",
                "Option 2 should use a different recipe from the most obvious system-level fix (option 1 is usually an extra support/column or a brace; prefer a truss or another bracing layout), or system-level plus a small section change; "
            )
            else -> t(
                "方案 3 以构件级为主（加大截面高度优先于宽度），可配合小幅体系改造；",
                "Option 3 is mainly member-level (increase depth before width), optionally with a small system change; "
            )
        }
        return head + rule
    }

    private suspend fun requestOnce(client: AiClient, slot: Int?): OptimizeReply {
        val messages = mutableListOf(
            LlmMessage("system", systemPrompt),
            LlmMessage("user", userMessage(slot))
        )
        // O-07 埋点分组：FRAME 走 patch 优先契约（tag=optimize/patch），实体通道保持完整模型契约（tag=optimize）
        val raw = client.complete(
            messages, jsonMode = true,
            tag = if (target.kind == OptimizeKind.FRAME) "optimize/patch" else "optimize"
        )
        if (target.kind != OptimizeKind.FRAME) return AiParser.parseOptimize(raw, target.kind)
        try {
            return parseOptimizeReply(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (pe: Exception) {
            // O-07：增量补丁解析/应用失败 → 本路自动重发一次"改输出完整模型"请求。
            // 优化通道重试不升思考档（与 O-06 快速模式解耦，沿用当前客户端；
            // 升 high 仅保留在建模通道 AiParser.parseFrameWithRetry）。
            messages += LlmMessage(
                "user",
                t(
                    "上一轮的增量补丁（patch）解析或应用失败：${pe.readableMessage()}。\n" +
                        "请把该方案改为直接输出完整的新模型：suggestions 每个方案的 model 字段给完整模型 JSON" +
                        "（写法与【当前模型】一致，省略默认字段的规则不变），不要再给 patch。",
                    "The previous incremental patch failed to parse/apply: ${pe.readableMessage()}.\n" +
                        "Output the COMPLETE new model instead: put the full model JSON in each option's model field " +
                        "(same format as the current model, default-valued fields omitted as before), no patch."
                )
            )
            val raw2 = client.complete(messages, jsonMode = true, tag = "optimize/full") // O-07 回退重发单独分组，便于统计回退率
            // 仍失败 → 抛异常记本路失败（O-05 单路失败不阻塞其余路）
            return AiParser.parseOptimize(raw2, target.kind)
        }
    }

    /**
     * O-07 解析：梁杆模型优先按增量补丁（patch）契约解析——带 patch 的方案在原模型上
     * 合成完整新模型后走既有校验链路；patch 缺失或应用失败退回方案自带的完整模型（model 字段）。
     * 全部方案不合法时抛 [AiParseException]（由调用方回退一次"改输出完整模型"请求）。
     */
    private fun parseOptimizeReply(raw: String): OptimizeReply {
        val reply = OptimizeJson.parseStructure(raw)
        if (reply.suggestions.isEmpty()) return reply
        val base = try {
            AppJson.decodeFromString(FrameModel.serializer(), target.modelJson)
        } catch (e: Exception) {
            throw AiParseException("原模型解析失败，无法应用增量补丁：${e.message?.take(120)}", e)
        }
        var firstFailure: String? = null
        val validated = reply.suggestions.mapNotNull { s ->
            // ① 优先增量补丁：合成完整模型后走既有建模校验链路
            val pj = s.patchJson
            if (pj != null) {
                try {
                    val patch = AppJson.decodeFromString(OptimizePatch.serializer(), pj)
                    val fullModel = OptimizePatchApplier.apply(base, patch)
                    // v1.2.45 真机横评：qwen 等模型在 none 档会返回"合法但全空"的补丁
                    // （230 token 废稿），合成结果与原模型完全一致——按失败处理，
                    // 触发整路回退"改输出完整模型"重发，而不是静默丢弃方案。
                    if (fullModel == base) {
                        throw AiParseException("增量补丁对原模型没有任何改动（空补丁），不是有效方案")
                    }
                    val full = AppJson.encodeToString(FrameModel.serializer(), fullModel)
                    val normalized = AiParser.validateOptimizeModel(full, target.kind)
                        ?: throw AiParseException("增量补丁合成后的完整模型未通过模型校验")
                    return@mapNotNull s.copy(modelJson = normalized, patchJson = null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (firstFailure == null) firstFailure = e.readableMessage()
                    // 补丁应用失败 → 该方案退回尝试 AI 直接给出的完整模型
                }
            }
            // ② 完整模型契约（回退路径 / 三维实体通道）
            val mj = s.modelJson
            if (mj != null) {
                try {
                    val normalized = AiParser.validateOptimizeModel(mj, target.kind) ?: return@mapNotNull null
                    return@mapNotNull s.copy(modelJson = normalized)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@mapNotNull null
                }
            }
            null
        }
        if (validated.isEmpty()) {
            throw AiParseException(
                firstFailure?.let { "AI 给出的增量补丁均未通过解析与应用：$it" }
                    ?: "AI 给出的优化模型均未通过模型校验"
            )
        }
        return reply.copy(suggestions = validated)
    }

    /**
     * 多方案流水线：每路"请求 → 求解验证"独立并发——方案 1 的有限元重算
     * 与方案 2/3 的网络等待重叠，完成的方案立即进入 [OptimizeState.Running] 逐个呈现。
     */
    private suspend fun runPipelined(client: AiClient) {
        // 按槽位固定存放，展示顺序恒为方案编号顺序
        val slots = arrayOfNulls<Pair<OptimizeReply, List<VerifiedOptimization>>>(count)
        var firstError: Exception? = null
        // Kimi highspeed 等中转 QPS 配额低：并发限 2 路；任一路命中限流后，
        // 后续路启动前额外退避，避免整轮同时撞墙（实测 3 路并发失败率 62%）。
        val permits = Semaphore(2)
        val rateLimited = AtomicBoolean(false)
        coroutineScope {
            (1..count).map { slot ->
                async {
                    permits.withPermit {
                        if (slot > 1) delay(1200L * (slot - 1))
                        if (rateLimited.get()) delay(3000L)
                        try {
                            val reply = requestOnce(client, slot)
                            val verified = reply.suggestions.take(1).map { s -> verifyOne(s) }
                            slots[slot - 1] = reply to verified
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // LlmClient 将 429/rate_limit 转为含 "HTTP 429" 的提示文案
                            if (e.message?.contains("429") == true ||
                                e.message?.contains("rate_limit", ignoreCase = true) == true
                            ) {
                                rateLimited.set(true)
                            }
                            if (firstError == null) firstError = e
                        }
                        // 并发写不同下标、读侧仅用于进度展示，偶发读到旧值无碍正确性
                        _state.value = OptimizeState.Running(
                            verified = slots.filterNotNull().flatMap { it.second }
                        )
                    }
                }
            }.awaitAll()
        }
        val ok = slots.filterNotNull()
        if (ok.isEmpty()) throw firstError ?: IllegalStateException("optimize failed")
        val verifiedAll = ok.flatMap { it.second }
        if (verifiedAll.isEmpty()) {
            _state.value = OptimizeState.Failed(
                t("AI 未返回可用的优化方案，请重试。", "AI returned no usable optimization options. Please retry.")
            )
            return
        }
        val replies = ok.map { it.first }
        _state.value = OptimizeState.Done(
            OptimizeReply(
                ready = true,
                diagnosis = replies.first { it.diagnosis.isNotBlank() || it === replies.last() }.diagnosis,
                suggestions = verifiedAll.map { it.suggestion },
                disclaimer = replies.firstOrNull { it.disclaimer.isNotBlank() }?.disclaimer ?: ""
            ),
            verifiedAll
        )
    }

    private suspend fun verifyOne(suggestion: OptimizeSuggestion): VerifiedOptimization {
        if (suggestion.modelJson == null) {
            return VerifiedOptimization(
                suggestion = suggestion,
                verified = false,
                failureReason = t("AI 未给出可解析的优化模型", "AI did not provide a parsable optimized model"),
                before = target.before
            )
        }
        val v = try {
            verifier(suggestion)
        } catch (e: Exception) {
            VerificationResult(ok = false, failureReason = e.message ?: "verify failed")
        }
        if (!v.ok || v.afterMetrics == null) {
            return VerifiedOptimization(
                suggestion = suggestion,
                verified = false,
                failureReason = v.failureReason.ifBlank {
                    t("该方案未能通过有限元重算验证", "This option failed finite-element re-verification")
                },
                before = target.before
            )
        }
        val diff = OptimizeDiff.diff(target.kind, target.modelJson, v.afterModelJson, english)
        return VerifiedOptimization(
            suggestion = suggestion,
            verified = true,
            diff = diff,
            before = target.before,
            after = v.afterMetrics
        )
    }

    companion object {
        internal fun leanModelJson(kind: OptimizeKind, modelJson: String): String = try {
            when (kind) {
                OptimizeKind.FRAME -> LeanPromptJson.encodeToString(
                    FrameModel.serializer(),
                    AppJson.decodeFromString(FrameModel.serializer(), modelJson).copy(case_meta = null)
                )
                OptimizeKind.SOLID_GENERIC -> LeanPromptJson.encodeToString(
                    SolidGenericModel.serializer(),
                    AppJson.decodeFromString(SolidGenericModel.serializer(), modelJson)
                )
            }
        } catch (e: Exception) {
            modelJson
        }

        private val PROMPT_ZH = """
            你是"口袋仿真"App 的结构优化助手。下面给你一个已经算完的有限元模型与结果，
            它的结论是「注意」或「危险」（安全系数不足或位移/应力偏大）。请给出结构优化方案。

            输出契约（只输出一个 JSON 对象，不要代码围栏、不要其他文字）：
            {
              "ready": true,
              "diagnosis": "危险源诊断：哪个构件/位置、什么失效模式（弯曲/轴压/屈曲/应力集中/挠度过大）",
              "suggestions": [
                {
                  "title": "方案标题（短）",
                  "desc": "具体怎么改，一句话说清",
                  "expected": "预期改善（不要编造精确数字，给方向即可）",
                  "changes": ["改了什么"],
                  "patch": { 增量补丁：只写改动的部分，写法见下方【增量补丁契约】 }
                }
              ],
              "disclaimer": "AI 辅助建议，不能替代正规工程验算"
            }

            【增量补丁契约（优先用 patch，省掉重复整个模型的输出 token）】
            - patch 只有四组数组：add_nodes 新增节点、add_members 新增杆件、add_supports 新增支座、
              modify_members 修改已有杆件；元素写法与【当前模型】对应字段一致，省略默认值字段的规则同下；
            - modify_members 元素只写 id 加真正要改的字段：截面（width_b/depth_h/wall_t/outer_diameter_mm/shape）、
              材料（material）、端点（i/j——"把原杆件拆短、后半跨另起新杆件"就是拆分）；没写的字段一律不变；
              载荷不进 patch、一律不许改动；
            - 【最小改动硬约束】patch 里只允许出现与本次改造直接相关的构件：未被改造触碰的
              节点/杆件/支座一律不得写入（尤其禁止把全部杆件列进 modify_members"顺手复述"一遍）；
              title/desc/expected 各不超过 60 字，changes 每条不超过 30 字，不复述模型数据；
            - ID 规则（数值以【结构体系画像】里的最大编号为准）：add_nodes 的 id 必须全部大于最大节点号，
              add_members 的 id 必须全部大于最大杆件号，且互不重复、不与现有编号冲突；
              新杆件的 i/j、新支座的挂载节点必须指向合成后存在的节点；modify_members 的 id 必须在原模型中存在；
            - 只允许新增与修改，不支持 delete：不能删除任何节点/杆件/支座；
              方案若确需删除构件、或把 udl 改挂到拆分后的子杆件上（patch 表达不了改载荷），
              该方案改为在 "model" 字段直接输出完整的新模型（model 与 patch 二选一；程序会自动校验，失败即退回要求完整模型）；
            - 配方 A（简支→连续）的 patch 写法示例（原杆件 1 为 1→2、L=4000，最大节点号 2、最大杆件号 1，
              危险跨中点 x=2000，竖向集中力作用在节点上、不受拆分影响）：
              {"add_nodes":[{"id":3,"x":2000,"y":0}],"modify_members":[{"id":1,"j":3}],
              "add_members":[{"id":2,"i":3,"j":2,"width_b":100,"depth_h":200,"material":"Q235钢"}],
              "add_supports":[{"node":3,"type":"roller"}]}
              即：跨中新增节点 3 并挂滚轴支座；原杆件 1 改短为 1→3（前半跨）；
              新增杆件 2 从 3→2（后半跨），截面、材料同原杆件 1；原杆件上的集中力留在原节点不动。

            【第一步：先判断体系，再选手段】
            先读【结构体系画像】（程序按模型确定性计算），回答三个问题：
            a. 危险杆件是受弯为主（梁/悬臂/横杆）还是受轴力为主（柱/斜撑）？
            b. 它所在的跨度有多长、两端是什么支承（简支/悬臂/固端）？
            c. 跨内或跨下方有没有可以落地或连接的位置（地面 y=0、相邻柱、墙）？
            受弯为主时，截面加大只能让抗弯能力按 h² 增长，而缩短跨度让弯矩按 L² 下降、
            挠度按 L⁴ 下降——所以受弯问题【首选改体系】，不是首选加截面。

            【第二步：体系级改造配方（梁杆模型 JSON 的具体改法，照做）】
            配方 A 简支梁→连续梁（增设中间支座）——适用于跨中弯矩/挠度控制的梁：
              1) 在危险跨的中点（或三分点）新增一个节点，编号用"原最大节点号+1"起；
              2) 把原杆件删掉，换成两根共线子杆件（原 i→新节点、新节点→原 j），
                 截面、材料与原杆件完全相同，杆件编号用"原最大杆件号+1"起；
              3) 若梁下方是地面/基础：在 supports 中给新节点加 "pinned" 或 "roller"；
                 若梁悬空（如书架层板、楼板梁）：新增一根竖向柱从新节点落到下方已有节点
                 或地面新节点（地面节点加 "fixed"）；
              4) 原杆件上的 udl 必须改挂到两根子杆件上，各写一条，q 值不变；
                 原作用在该节点上的 force 保持节点坐标与数值不变。
              效果：跨度减半，跨中弯矩约降为原来的 1/4，挠度降为约 1/16。
            配方 B 梁→桁架（加腹杆形成三角形）——适用于跨度大、截面不能再加高的梁：
              1) 在梁的上方或下方平行布置一根弦杆（偏移距离取跨度的 1/8~1/6）；
              2) 把原梁按 4~6 等分拆成子杆件（同配方 A 的拆分规则），弦杆在对应位置设节点；
              3) 用竖杆 + 斜杆连接上下弦对应节点，每个格间形成三角形（不能只有矩形格）；
              4) 腹杆、弦杆截面可以比原梁小（如原梁的 0.5~0.7 倍），材料同原梁；
              5) 载荷与支座位置不变；udl 仍挂在原梁的子杆件上，q 值不变。
              效果：弯矩转化为弦杆轴力，承载力和刚度都成倍提高。
            配方 C 悬臂→加斜撑/拉杆——适用于悬臂端或节点转角弯矩控制：
              新增一根斜杆，从悬臂中部或端部节点斜向连接到下方/上方已有的支座节点或柱上节点，
              斜杆与水平夹角 30°~60°；必要时先在悬臂上加节点（按配方 A 的拆分规则）。
            配方 D 框架侧移/长柱屈曲→加斜撑或中间横撑：
              在框架格内加交叉或单斜撑；长柱在中点加节点并用横杆与相邻柱连接，计算长度减半。

            【第三步：方案组合要求】
            - 方案 1 必须是体系级改造（配方 A/B/C/D 之一，或同类改造），且【不换材料、不加大截面】；
            - 方案 2（如果要求）应是另一种体系级改造，或体系级 + 小幅截面调整的组合；
            - 方案 3（如果要求）才允许以构件级（加大截面高度优先于宽度）为主；
            - 更换材料只能在体系级与构件级都不够时作为最后补充，且需在 desc 中说明理由；
            - 目标是安全系数 ≥ 2.0，达标即止，不要堆砌构件或大幅放大截面。

            【第四步：改模型规则（改的时候直接遵守即可，不要回头逐条自查、不要反复验算）】
            - 新增的每个节点至少连接 2 根杆件，或连接 1 根杆件且有支座；不留孤立节点；
            - 拆分后的子杆件首尾相接、共线；patch 不能删除原杆件——把原杆件一端改指到新节点即为拆分；
            - 原有 supports 条目一律原样保留（节点号、type、fix 不变），只能新增；
            - force 的作用节点与数值不变；原 udl 覆盖的每一段都改挂到对应子杆件上，q 值不变；
            - members 的 i/j 必须指向存在的节点；2D 模型的新节点 z 省略或为 0；
            - changes 数组逐条写清改动（新增节点 / 拆分 / 新增支座 / 新增杆件）。

            硬性约束（违反则方案无效）：
            1. 每个方案优先给出【增量补丁 patch】（只写改动，不重复未改动的节点/杆件/支座/载荷），
               合成后 name、dim 与全部未改动内容与【当前模型】一致；
               【当前模型】已省略取默认值的字段（坐标/分量为 0、空数组、载荷 type=force、支座 type=pinned、截面 shape=rect），
               patch 元素也照此省略、不要补写；仅当方案确需删除构件或改动载荷时才在 "model" 字段给【完整新模型】；
               整个回复输出为紧凑 JSON（不缩进、不换行），文字字段简洁；
            2. 【严禁】减少载荷、改动载荷数值或位置、放松或删除原有约束——程序会自动比对，作弊方案直接作废；
            3. 材料只能从材料库中选各向同性的常用材料（钢、铝合金、混凝土等），优先沿用原材料；
               不要把原材料替换为木材、复合材料；
            4. 优化后必须仍是一个可正常求解的模型（不能悬空、不能机构化）；
            5. 不要修改网格档位字段；
            6. 若模型是三维实体（无 members），则不适用上述梁杆配方：优先加肋板/加厚危险区域/
               增大过渡圆角，再考虑整体加厚，最后才换材料。
        """.trimIndent()

        private val PROMPT_EN = """
            You are the structural optimization assistant of the "PocketSim" app. Below is a solved
            finite-element model whose verdict is "caution" or "danger" (insufficient safety factor
            or excessive displacement/stress). Propose structural optimization options.

            Output contract (a single JSON object, no code fences, no other text).
            ALL text fields (diagnosis / title / desc / expected / changes) MUST be written in English:
            {
              "ready": true,
              "diagnosis": "root cause: which member/location, which failure mode",
              "suggestions": [
                {"title": "...", "desc": "...", "expected": "...", "changes": ["..."], "patch": { incremental patch — only what changed, see PATCH CONTRACT below }}
              ],
              "disclaimer": "AI-assisted advice; not a substitute for formal engineering verification"
            }

            PATCH CONTRACT (prefer patch over re-emitting the whole model — saves output tokens):
            - A patch has exactly four arrays: add_nodes, add_members, add_supports, modify_members;
              elements are written like the matching fields of the current model, omitting default-valued fields;
            - modify_members entries carry id plus ONLY the fields actually changed: section
              (width_b/depth_h/wall_t/outer_diameter_mm/shape), material, endpoints (i/j — "shorten the original
              member and start the second half as a new member" IS the split); unlisted fields stay unchanged;
              loads are never part of a patch and never change;
            - [MINIMAL-CHANGE HARD RULE] a patch may ONLY contain members/nodes/supports directly involved in
              this option's modification — never restate untouched members (listing all members in modify_members is
              forbidden); keep title/desc/expected under 60 words each and each changes entry under 30 words,
              without restating model data;
            - ID rules (values come from the [Structural profile]): every id in add_nodes must be greater than the
              max node id; every id in add_members greater than the max member id; no duplicates and no clashes
              with existing ids; endpoints of new members and nodes of new supports must exist after merging;
              ids in modify_members must exist in the original model;
            - additions and modifications only, NO delete: never remove nodes/members/supports; if an option really
              needs deleting a member or re-attaching a udl onto the split sub-members (a patch cannot express load
              changes), put the COMPLETE new model in the "model" field instead (model XOR patch; the app validates
              and automatically re-requests the full model on failure);
            - Recipe A patch example (original member #1 is 1→2, L=4000, max node id 2, max member id 1, mid-span
              at x=2000; nodal forces are unaffected by the split):
              {"add_nodes":[{"id":3,"x":2000,"y":0}],"modify_members":[{"id":1,"j":3}],
              "add_members":[{"id":2,"i":3,"j":2,"width_b":100,"depth_h":200,"material":"Q235 steel"}],
              "add_supports":[{"node":3,"type":"roller"}]}
              i.e. add mid node 3 with a roller support; shorten member 1 to 1→3 (first half); add member 2 from
              3→2 (second half) with the same section and material as member 1; keep every force at its node.

            STEP 1 - identify the structural system first, then pick measures.
            Read the [Structural profile] (computed deterministically from the model) and answer:
            a. Is the critical member bending-dominated (beam/cantilever/rail) or axial (column/brace)?
            b. How long is its span and how is it supported (simple / cantilever / fixed)?
            c. Is there a place inside or below the span to land a support (ground y=0, column, wall)?
            For bending, a bigger section only grows capacity with h^2, while shortening the span cuts
            moment with L^2 and deflection with L^4 — so bending problems call for a SYSTEM change first.

            STEP 2 - system-level recipes (concrete JSON edits for frame models; follow them):
            Recipe A, simply supported -> continuous beam (add an intermediate support):
              1) add a node at mid-span (or third points) of the critical span; new ids start at max+1;
              2) delete the original member and replace it with two collinear sub-members
                 (i -> new node, new node -> j), same section and material; new ids start at max+1;
              3) if the beam sits over ground/foundation, add a "pinned" or "roller" support at the
                 new node; if it is elevated (shelf board, floor beam), add a vertical column from the
                 new node down to an existing node or a new ground node with a "fixed" support;
              4) re-attach every udl of the original member to BOTH sub-members, same q value;
                 keep every force at the same node coordinates and values.
              Effect: span halves, mid-span moment ~1/4, deflection ~1/16.
            Recipe B, beam -> truss (triangulate with web members):
              1) add a parallel chord above or below the beam, offset = span/8 ~ span/6;
              2) split the beam into 4~6 equal sub-members (same split rules), with matching chord nodes;
              3) connect chord nodes with verticals AND diagonals so every panel is a triangle;
              4) chords/web members may be smaller than the original beam (0.5~0.7x), same material;
              5) loads and supports unchanged; udl stays on the beam sub-members with the same q.
              Effect: bending becomes axial chord forces; capacity and stiffness multiply.
            Recipe C, cantilever -> add a strut/tie from the cantilever (mid or tip node) diagonally to an
              existing support or column node, angle 30~60 degrees; split the cantilever first if needed.
            Recipe D, frame sway / long column buckling -> add X or single bracing in the bay; for a long
              column add a mid node tied by a horizontal member to the neighbouring column.

            STEP 3 - option mix:
            - Option 1 MUST be a system-level redesign (Recipe A/B/C/D or equivalent) with NO material
              change and NO section enlargement;
            - Option 2 (if requested): another system-level redesign, or system-level + small section tweak;
            - Option 3 (if requested): may be member-level (increase depth before width);
            - Changing material is a last resort only, with the reason stated in desc;
            - Target safety factor >= 2.0; stop once reached; no piling up members or oversized sections.

            STEP 4 - model edit rules (follow them while editing; do NOT pause to re-check item by item
            or re-verify numbers — the app validates and re-solves the model automatically):
            - every new node connects to >= 2 members, or 1 member plus a support; no orphan nodes;
            - split sub-members are collinear and chained; a patch cannot delete the original member —
              re-pointing one of its ends to the new node IS the split;
            - every original supports entry is kept verbatim (node, type, fix); you may only add;
            - every force keeps node coordinates and values; every original udl length is fully covered
              by sub-member udl entries with the same q;
            - all member i/j reference existing nodes; for 2D models omit z or set it to 0;
            - "changes" lists each edit: "add node #n (x,y)", "split member #k into #a/#b",
              "add support at node #n", "add member #m".

            Hard constraints (violating them invalidates the option):
            1. Each option should provide the incremental PATCH (only what changed — do not repeat unchanged
               nodes/members/supports/loads); after merging, name, dim and everything untouched stay identical.
               The current model omits default-valued fields (zero coordinates/components, empty arrays, load type=force,
               support type=pinned, section shape=rect) — patch elements omit them too; only when an option really
               needs deleting a member or changing loads put the COMPLETE new model in the "model" field;
               whole reply as compact JSON (no indentation or line breaks), concise text;
            2. NEVER reduce loads, change load values/positions, or relax/remove existing constraints —
               the app checks this automatically and discards cheating options;
            3. Materials must be isotropic and from the built-in library (steel, aluminum, concrete);
               prefer the original material; do not switch to wood or composites;
            4. The optimized model must remain solvable (no floating parts, no mechanism);
            5. Do not change the mesh-level field;
            6. For 3D solid models (no members) the frame recipes do not apply: prefer ribs, local
               thickening of the critical zone, larger fillets, then overall thickening, material last.
        """.trimIndent()
    }
}

// ---------------------------------------------------------------------
// 优化回复 JSON 的结构解析（模型合法性校验在 AiParser.parseOptimize 中完成）
// ---------------------------------------------------------------------
internal object OptimizeJson {

    fun parseStructure(content: String): OptimizeReply {
        val jsonText = AiParser.extractJson(content)
            ?: throw AiParseException("回复中未找到 JSON 对象")
        val root = try {
            AppJson.parseToJsonElement(jsonText)
        } catch (e: Exception) {
            throw AiParseException("JSON 语法错误：${e.message?.take(120)}", e)
        }
        if (root !is JsonObject) {
            throw AiParseException("JSON 顶层必须是对象，实际是 ${root::class.simpleName}")
        }
        val ready = (root["ready"] as? JsonPrimitive)?.booleanOrNull ?: false
        val diagnosis = root.str("diagnosis")
        val disclaimer = root.str("disclaimer")
        val arr = root["suggestions"] as? JsonArray ?: JsonArray(emptyList())
        val suggestions = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val modelObj = o["model"] as? JsonObject
            val patchObj = o["patch"] as? JsonObject
            OptimizeSuggestion(
                title = o.str("title"),
                desc = o.str("desc"),
                expected = o.str("expected"),
                changes = (o["changes"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
                    ?: emptyList(),
                modelJson = modelObj?.let { AppJson.encodeToString(JsonObject.serializer(), it) },
                // O-07：增量补丁原文（LeanPromptJson 风格序列化，省略默认值字段）
                patchJson = patchObj?.let { LeanPromptJson.encodeToString(JsonObject.serializer(), it) }
            )
        }
        return OptimizeReply(ready, diagnosis, suggestions, disclaimer)
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content?.trim().orEmpty()

    /** 从结果里读数值（容错：非数字返回 null） */
    fun JsonObject.doubleOrNullSafe(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    /** 容错读取整数 */
    fun JsonObject.intOrNullSafe(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    /** 供外部复用的 payload 序列化 */
    fun encode(obj: JsonObject): String = AppJson.encodeToString(JsonObject.serializer(), obj)
}
