/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
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
    val modelJson: String? = null
)

/** AI 优化建议回复 */
data class OptimizeReply(
    val ready: Boolean = false,
    val diagnosis: String = "",
    val suggestions: List<OptimizeSuggestion> = emptyList(),
    val disclaimer: String = ""
)

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
    data object Running : OptimizeState
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

    private fun loadSignature(m: FrameModel): Set<String> =
        m.loads.map {
            listOf(
                it.type, it.node, it.member, it.fx, it.fy, it.fz,
                it.mx, it.my, it.mz, it.qx, it.qy, it.qz, it.delta_temp
            ).joinToString("|")
        }.toSet()

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
        append("\n\n【当前模型（JSON）】\n")
        append(target.modelJson)
        append("\n\n【本次分析结果摘要】\n")
        append(target.summary)
    }

    suspend fun run() {
        if (_state.value is OptimizeState.Running) return
        _state.value = OptimizeState.Running
        try {
            val client = aiClientProvider()
            val raw = client.complete(
                listOf(
                    LlmMessage("system", systemPrompt),
                    LlmMessage(
                        "user",
                        t(
                            "请给出 $count 个结构优化方案，用于把结论从警告/危险提升到安全。",
                            "Give $count structural optimization options to raise the verdict from caution/danger to safe."
                        )
                    )
                ),
                jsonMode = true
            )
            val reply = AiParser.parseOptimize(raw, target.kind)
            if (reply.suggestions.isEmpty()) {
                _state.value = OptimizeState.Failed(
                    t("AI 未返回可用的优化方案，请重试。", "AI returned no usable optimization options. Please retry.")
                )
                return
            }
            val verified = reply.suggestions.map { s -> verifyOne(s) }
            _state.value = OptimizeState.Done(reply, verified)
        } catch (e: Exception) {
            _state.value = OptimizeState.Failed(
                t("AI 优化方案生成失败：${e.readableMessage()}", "Failed to generate an optimization plan: ${e.readableMessage()}")
            )
        }
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
                  "model": { 完整的新模型，字段与原模型完全一致 }
                }
              ],
              "disclaimer": "AI 辅助建议，不能替代正规工程验算"
            }

            优化策略（必须遵守）：
            - 优先通过【结构形式】调整解决问题：增加杆件、斜撑、支撑、加劲肋，
              加大关键截面的高度或宽度，缩短跨度，改善连接节点等；
            - 目标只需把安全系数提高到【超过安全阈值（≥ 2.0）】即可，达标即止，
              不要为追求过高的安全余量而过度设计、堆砌构件或大幅放大截面；
            - 尽量保留原结构材料；只有当纯结构形式调整无法把结论提升到安全（安全系数 ≥ 2.0）时，
              才允许更换为更高强度的材料——换材料仅作兜底手段，不要作为首选；
            - 若给出多个方案，至少有一个方案只改结构形式、不更换材料。

            硬性约束（违反则方案无效）：
            1. 每个方案必须给出【完整新模型】（不是增量补丁），沿用原模型的节点/杆件编号习惯；
               新增杆件/构件必须是模型中真实存在的几何，不能只在文字里描述；
            2. 只允许改结构：加大关键截面、换更高强度材料、增设支撑/斜撑、缩短跨度、改善连接；
            3. 【严禁】减少载荷、放松或删除原有约束、降低安全要求——这属于作弊；
            4. 材料只能从材料库中选各向同性的常用材料（钢、铝合金、混凝土等）；
               禁止推荐木材、复合材料等各向异性材料（当前求解器只支持各向同性）；
            5. 优化后必须仍是一个可正常求解的模型（不能悬空、不能机构化）；
            6. 不要修改网格档位字段。
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
                {"title": "...", "desc": "...", "expected": "...", "changes": ["..."], "model": { full model }}
              ],
              "disclaimer": "AI-assisted advice; not a substitute for formal engineering verification"
            }

            Optimization strategy (must follow):
            - Prefer structural-form changes first: add members, bracing, supports,
              stiffeners; increase the depth or width of critical sections; shorten spans;
              improve connections.
            - The goal is only to raise the safety factor ABOVE the threshold (>= 2.0).
              Stop once it passes; do NOT over-design for extra margin (no piling up members
              or grossly oversized sections).
            - Keep the original material whenever possible. Only when structural-form changes
              alone cannot reach safe (safety factor >= 2.0) may you switch to a stronger
              material — material change is a fallback, not the first move.
            - If you return multiple options, at least one must change only the structural
              form and keep the original material.

            Hard constraints (violating them invalidates the option):
            1. Each option MUST provide the COMPLETE new model (not a patch), keeping the original
               node/member numbering conventions; added members must exist in the model geometry;
            2. Only structural changes are allowed: enlarge critical sections, use stronger material,
               add supports/bracing, shorten spans, improve connections;
            3. NEVER reduce loads, relax or remove existing constraints — that is cheating;
            4. Materials must be isotropic and from the built-in library (steel, aluminum, concrete);
               do NOT recommend wood or composites (the solver is isotropic-only);
            5. The optimized model must remain solvable (no floating parts, no mechanism);
            6. Do not change the mesh-level field.
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
            OptimizeSuggestion(
                title = o.str("title"),
                desc = o.str("desc"),
                expected = o.str("expected"),
                changes = (o["changes"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
                    ?: emptyList(),
                modelJson = modelObj?.let { AppJson.encodeToString(JsonObject.serializer(), it) }
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
