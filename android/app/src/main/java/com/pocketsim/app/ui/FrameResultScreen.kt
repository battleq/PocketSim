/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.AppContainer
import com.pocketsim.app.AppJson
import com.pocketsim.app.FrameModel
import com.pocketsim.app.FramePolyline
import com.pocketsim.app.FrameResult
import com.pocketsim.app.OptimizeGate
import com.pocketsim.app.R
import com.pocketsim.app.SafetyLogic
import com.pocketsim.app.Units
import com.pocketsim.app.modelDisplayName
import com.pocketsim.app.SavedProject
import com.pocketsim.app.ui.components.ColorLegend
import com.pocketsim.app.ui.components.ColorRamp
import com.pocketsim.app.ui.components.MetricCard
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * 框架求解结果页（P1）：
 *  - 指标三联卡（最大变形/最大应力+受力最大杆件/安全系数+颜色分级）
 *  - 变形图 Canvas：原始形状虚线 + 变形形状实线（按杆件应力着色）+ 放大系数滑杆
 *  - 稳定性提醒（欧拉屈曲）、通俗分析过程（Python narrative）、杆件受力明细
 *  - 底部"继续追问/修改重算"入口（v0.3.0）+ 常驻免责声明
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameResultScreen(
    container: AppContainer,
    model: FrameModel,
    result: FrameResult,
    onBack: () -> Unit,
    /** 进入结果追问页（答疑 / 改条件重算） */
    onFollowUp: () -> Unit = {},
    /** 进入 AI 优化建议页（仅注意/危险时由内部门控显示按钮） */
    onOptimize: () -> Unit = {},
    /** 返回首页（清空路由栈重新进首页，不是新建项目欢迎页） */
    onGoHome: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deformScale by remember { mutableStateOf(20f) }
    var exporting by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    /** 把当前模型+结果保存到本地项目库（启动首页"打开老项目"可回放） */
    fun saveProject() {
        if (saving) return
        saving = true
        saveMessage = null
        scope.launch {
            try {
                val modelJson = AppJson.encodeToString(FrameModel.serializer(), model)
                val resultJson = AppJson.encodeToString(FrameResult.serializer(), result)
                val project = SavedProject(
                    id = System.currentTimeMillis(),
                    name = modelDisplayName(result.model_name).ifBlank { context.getString(R.string.frame_model_default_name) },
                    kind = "frame",
                    modelJson = modelJson,
                    resultJson = resultJson,
                    verdict = result.verdict,
                    maxStressMPa = result.max_stress_MPa,
                    safetyFactor = result.safety_factor,
                    createdAt = System.currentTimeMillis()
                )
                container.projectRepository.save(project)
                saveMessage = context.getString(R.string.frame_saved_message)
            } catch (e: Exception) {
                saveMessage = context.getString(R.string.frame_save_failed_fmt, e.message ?: context.getString(R.string.error_unknown_default))
            } finally {
                saving = false
            }
        }
    }

    /** 生成 PDF 并保存到公共 Downloads 目录，完成后弹出分享/打开 */
    fun exportPdf() {
        if (exporting) return
        exporting = true
        exportMessage = null
        scope.launch {
            try {
                // 先写到 App 缓存目录，再用 MediaStore 复制到公共 Downloads
                val cacheFile = java.io.File(context.cacheDir,
                    "pocketsim_${System.currentTimeMillis()}.pdf")
                container.engine.exportFramePdf(model, result, cacheFile.absolutePath)
                val saved = savePdfToDownloads(context, cacheFile, result.model_name)
                exportMessage = if (saved != null) {
                    context.getString(R.string.frame_pdf_saved_fmt, saved)
                } else {
                    context.getString(R.string.frame_pdf_generated_fmt, cacheFile.name)
                }
            } catch (e: Exception) {
                exportMessage = context.getString(R.string.frame_export_failed_fmt, e.message ?: context.getString(R.string.error_unknown_default))
            } finally {
                exporting = false
            }
        }
    }

    val grade = SafetyLogic.grade(result.safety_factor).takeIf { it != SafetyLogic.Grade.UNKNOWN }
        ?: SafetyLogic.gradeFromVerdict(result.verdict)

    Scaffold(
        topBar = {
            TopAppBar(
                // 顶栏展示模型名（AI 建模时起的描述性名称），无名称时回退通用标题
                title = { Text(modelDisplayName(result.model_name).ifBlank { stringResource(R.string.frame_result_title) }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 保存到我的项目
                    OutlinedButton(
                        onClick = ::saveProject,
                        enabled = !saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text(if (saving) stringResource(R.string.frame_saving) else stringResource(R.string.frame_save_to_projects))
                    }
                    saveMessage?.let {
                        Text(
                            text = it,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        )
                    }
                    // 导出 PDF 报告
                    Button(
                        onClick = ::exportPdf,
                        enabled = !exporting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (exporting) stringResource(R.string.frame_pdf_exporting)
                            else stringResource(R.string.frame_pdf_export)
                        )
                    }
                    exportMessage?.let {
                        Text(
                            text = it,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        )
                    }
                    // 继续追问/修改重算 与 返回首页 并排一行：
                    // 新增按钮后底栏变高会压缩结论滚动区，合并一行把高度还给内容（v1.2.35）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onFollowUp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.frame_followup_entry))
                        }
                        Button(
                            onClick = onGoHome,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.frame_back_home))
                        }
                    }
                    // 免责声明常驻底部（与场景结果页一致）
                    Text(
                        text = stringResource(R.string.disclaimer_full),
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- 结论横幅：安全系数低于 2.0 时置顶醒目提示（危险红 / 注意黄） ----
            if (grade == SafetyLogic.Grade.DANGER || grade == SafetyLogic.Grade.CAUTION) {
                item {
                    val danger = grade == SafetyLogic.Grade.DANGER
                    // 深色模式下必须换深底浅字，否则浅色横幅 + 默认浅色文字会看不清
                    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                    val bannerBg = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
                    val bodyColor = MaterialTheme.colorScheme.onSurface
                    val subtleColor = MaterialTheme.colorScheme.onSurfaceVariant
                    Card(
                        shape = RoundedCornerShape(4.dp),
                        colors = CardDefaults.cardColors(containerColor = bannerBg)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(
                                        if (danger) R.string.verdict_banner_danger_title
                                        else R.string.verdict_banner_caution_title
                                    ),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = accent
                                )
                                // 优化入口内嵌在警告横幅内：不新增内容项、不挤压下方图表
                                if (OptimizeGate.shouldOffer(grade)) {
                                    // 用带填充背景的 Button（默认 primary 容器色 + 圆角），
                                    // 与横幅背景形成明显对比，一眼能看出是按钮
                                    Button(onClick = onOptimize) {
                                        Text(stringResource(R.string.optimize_button))
                                    }
                                }
                            }
                            Text(
                                text = stringResource(
                                    if (danger) R.string.verdict_banner_danger_body
                                    else R.string.verdict_banner_caution_body,
                                    formatNum(result.safety_factor)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = bodyColor
                            )
                            Text(
                                text = stringResource(
                                    R.string.verdict_banner_stress_fmt,
                                    Units.stress(result.max_stress_MPa).toString(),
                                    result.governing_member
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = subtleColor
                            )

                            // ---- 薄弱部位 + 破坏形式（利用率超 1 的杆件，最多 3 根） ----
                            val modelMembers = model.members.associateBy { it.id }
                            val weak = result.members
                                .filter { (it.util ?: 0.0) > 1.0 }
                                .sortedByDescending { it.util ?: 0.0 }
                                .take(3)
                            val topUtil = result.members.mapNotNull { it.util }.maxOrNull() ?: 0.0
                            if (weak.isNotEmpty()) {
                                val memberDefault = stringResource(R.string.frame_member_default)
                                Text(
                                    text = stringResource(R.string.verdict_banner_weak_header),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = accent
                                )
                                weak.forEach { m ->
                                    val mm = modelMembers[m.id]
                                    val util = m.util ?: 0.0
                                    val strength = if (util > 0.0) m.sigma_MPa / util else 0.0
                                    val form = when {
                                        m.M_max_Nmm > 0.0 -> R.string.verdict_banner_form_bending
                                        m.compress_kN > 0.0 -> R.string.verdict_banner_form_compress
                                        else -> R.string.verdict_banner_form_axial
                                    }
                                    Text(
                                        text = stringResource(
                                            R.string.verdict_banner_weak_fmt,
                                            m.id, mm?.i ?: 0, mm?.j ?: 0,
                                            m.material.ifBlank { memberDefault }, m.section,
                                            stringResource(form),
                                            Units.stress(m.sigma_MPa).toString(),
                                            Units.stress(strength).toString(),
                                            formatNum(util)
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = bodyColor
                                    )
                                }
                            }

                            // ---- 加强建议（危险或任何杆件超载时给出，含量化截面方案） ----
                            if (danger || topUtil > 1.0) {
                                Text(
                                    text = stringResource(R.string.verdict_banner_advice_header),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = accent
                                )
                                Text(
                                    text = stringResource(
                                        R.string.verdict_banner_advice_load_fmt,
                                        formatNum(max(topUtil, 1.0)),
                                        formatNum(100.0 / (max(topUtil, 1.0) * 1.5))
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = bodyColor
                                )
                                // 截面加粗：要达到安全系数 1.5（σ ≤ 强度/1.5）需要的受弯高度倍数
                                val topMember = weak.firstOrNull()?.let { modelMembers[it.id] }
                                if (topMember != null && topMember.depth_h > 0.0) {
                                    val k = sqrt(max(topUtil, 1.0) / 0.667)
                                    if (k <= 3.0) {
                                        val hNew = ceil(topMember.depth_h * k / 5.0) * 5.0
                                        Text(
                                            text = stringResource(
                                                R.string.verdict_banner_advice_section_fmt,
                                                Units.length(topMember.depth_h).toString(),
                                                Units.length(hNew).toString(),
                                                sectionDim(topMember.width_b, topMember.depth_h),
                                                sectionDim(topMember.width_b, hNew),
                                                formatNum(k * k)
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = bodyColor
                                        )
                                    } else {
                                        Text(
                                            text = stringResource(
                                                R.string.verdict_banner_advice_section_big_fmt,
                                                formatNum(k),
                                                Units.length(topMember.depth_h).toString(),
                                                Units.length(ceil(topMember.depth_h * k / 10.0) * 10.0).toString()
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = bodyColor
                                        )
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.verdict_banner_advice_span),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = bodyColor
                                )
                                Text(
                                    text = stringResource(R.string.verdict_banner_advice_material),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = bodyColor
                                )
                            }
                        }
                    }
                }
            }

            // ---- 指标三联卡 ----
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val dispQ = Units.displacement(result.max_disp_mm)
                    val stressQ = Units.stress(result.max_stress_MPa)
                    MetricCard(
                        title = stringResource(R.string.metric_max_deflection),
                        value = dispQ.text,
                        unit = dispQ.unit,
                        footer = result.max_disp_desc,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = stringResource(R.string.metric_max_stress),
                        value = stressQ.text,
                        unit = stressQ.unit,
                        footer = stringResource(R.string.frame_governing_fmt, result.governing_member),
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = stringResource(R.string.metric_safety),
                        value = if (result.safety_factor > 0.0) formatNum(result.safety_factor)
                        else stringResource(R.string.safety_unknown),
                        unit = "",
                        accent = gradeColor(grade),
                        footer = gradeLabel(grade),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ---- 变形图 ----
            item {
                Card(shape = RoundedCornerShape(4.dp)) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.frame_deform_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (is3dPolyline(result)) {
                            Frame3DPlot(
                                model = model,
                                result = result,
                                deformScale = deformScale,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                            )
                        } else {
                            FrameDeformPlot(
                                result = result,
                                deformScale = deformScale,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                            )
                        }
                        val legendStress = Units.stress(result.max_stress_MPa)
                        ColorLegend(
                            minLabel = "0 " + legendStress.unit,
                            maxLabel = "${legendStress.text} ${legendStress.unit}"
                        )
                        Text(
                            text = stringResource(R.string.deform_scale_fmt, deformScale.toInt()),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = deformScale,
                            onValueChange = { deformScale = it },
                            valueRange = 1f..200f
                        )
                        Text(
                            text = stringResource(
                                if (is3dPolyline(result)) R.string.frame_3d_hint
                                else R.string.frame_deform_note
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- 稳定性（屈曲）提醒 ----
            if (result.buckling_note.isNotBlank()) {
                item {
                    Card(shape = RoundedCornerShape(4.dp)) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.frame_buckling_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFB26A00)
                            )
                            Text(
                                text = result.buckling_note,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // ---- 温度分区（热分析） ----
            result.thermal?.let { th ->
                if (th.has_thermal) {
                    item {
                        Card(shape = RoundedCornerShape(4.dp)) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.frame_thermal_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(
                                        R.string.frame_thermal_max_dt_fmt,
                                        formatNum(th.max_delta_temp)
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (th.by_member.isNotEmpty()) {
                                    val thermalMemberFmt = stringResource(R.string.frame_thermal_member_fmt)
                                    val enumSep = stringResource(R.string.common_enum_sep)
                                    val perMember = th.by_member.entries
                                        .sortedBy { it.key.toIntOrNull() ?: 0 }
                                        .joinToString(enumSep) { (mid, dt) ->
                                            thermalMemberFmt.format(
                                                mid,
                                                (if (dt > 0) "+" else "") + formatNum(dt)
                                            )
                                        }
                                    Text(
                                        text = stringResource(R.string.frame_thermal_note_fmt, perMember),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (th.over_temp && th.note.isNotBlank()) {
                                    Text(
                                        text = stringResource(R.string.frame_thermal_over_temp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFC62828)
                                    )
                                    Text(
                                        text = th.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFC62828)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- 分析过程（Python narrative，通俗段落） ----
            if (result.narrative.isNotEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(4.dp)) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.frame_narrative_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            result.narrative.forEach { paragraph ->
                                Text(
                                    text = paragraph,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // ---- 杆件受力明细 ----
            item { FrameMembersCard(model, result) }

            // ---- 规模与耗时 ----
            item {
                Text(
                    text = stringResource(
                        R.string.frame_info_fmt,
                        result.n_nodes, result.n_members, result.n_supports,
                        formatNum(result.solve_ms)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 截面尺寸文本：公制 "30×40"，英制 "1.18×1.57 in"（数值按当前单位制换算） */
private fun sectionDim(b: Double, h: Double): String =
    if (Units.isImperial) {
        "${Units.num(b / 25.4)}×${Units.num(h / 25.4)} in"
    } else {
        "${formatNum(b)}×${formatNum(h)}"
    }

/** 杆件受力明细卡：每根杆件的截面/应力/利用率/轴力/弯矩（节点连接取自原始模型） */
@Composable
private fun FrameMembersCard(model: FrameModel, result: FrameResult) {
    val tensionLabel = stringResource(R.string.frame_member_tension)
    val compressionLabel = stringResource(R.string.frame_member_compression)
    val memberTitleFmt = stringResource(R.string.frame_member_title_fmt)
    val memberNodesFmt = stringResource(R.string.frame_member_nodes_fmt)
    val vmStressFmt = stringResource(R.string.frame_detail_vm_stress_fmt)
    val stressFmt = stringResource(R.string.frame_detail_stress_fmt)
    val utilFmt = stringResource(R.string.frame_detail_util_fmt)
    val axialFmt = stringResource(R.string.frame_detail_axial_fmt)
    val myFmt = stringResource(R.string.frame_detail_my_fmt)
    val mzFmt = stringResource(R.string.frame_detail_mz_fmt)
    val torsionFmt = stringResource(R.string.frame_detail_torsion_fmt)
    val maxMomentFmt = stringResource(R.string.frame_detail_max_moment_fmt)
    val compressionFmt = stringResource(R.string.frame_detail_compression_fmt)
    val deltaTFmt = stringResource(R.string.frame_detail_delta_t_fmt)

    Card(shape = RoundedCornerShape(4.dp)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.frame_members_force_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            result.members.forEachIndexed { index, m ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val conn = model.members.firstOrNull { it.id == m.id }
                        ?.let { memberNodesFmt.format(it.i, it.j) }.orEmpty()
                    Text(
                        text = memberTitleFmt.format(m.id, m.material, m.section) + conn,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    val detail = buildString {
                        val sigmaShown = if (m.sigma_vm_MPa > 0.0) m.sigma_vm_MPa else m.sigma_MPa
                        val is3dMember = m.My_max_Nmm > 0.0 || m.Mz_max_Nmm > 0.0
                        append((if (is3dMember) vmStressFmt else stressFmt).format(Units.stress(sigmaShown).toString()))
                        m.util?.let { append(utilFmt.format(formatNum(it * 100))) }
                        append(
                            axialFmt.format(
                                Units.forceK(m.N_N).toString(),
                                if (m.N_N >= 0) tensionLabel else compressionLabel
                            )
                        )
                        if (is3dMember) {
                            // 3D：绕两个截面轴分别给出包络弯矩，并补充扭矩
                            append(myFmt.format(Units.momentK(m.My_max_Nmm).toString()))
                            append(mzFmt.format(Units.momentK(m.Mz_max_Nmm).toString()))
                            if (m.torsion_Nmm > 0.0) {
                                append(torsionFmt.format(Units.momentK(m.torsion_Nmm).toString()))
                            }
                        } else {
                            append(maxMomentFmt.format(Units.momentK(m.M_max_Nmm).toString()))
                        }
                        if (m.compress_kN > 0.0) {
                            append(compressionFmt.format(Units.forceK(m.compress_kN * 1000.0).toString()))
                        }
                        m.delta_temp?.let {
                            append(deltaTFmt.format((if (it > 0) "+" else "") + formatNum(it)))
                        }
                    }
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// =====================================================================
// 变形图绘制
// =====================================================================

/**
 * 变形折线图（Compose Canvas）：
 *  - polylines 每点 [x, y, dx, dy]（mm；变形位置 = 原坐标 + 位移 × 放大系数）；
 *  - 原始形状虚线灰（放大 >1× 时叠加），变形形状实线按杆件应力着色（ColorRamp）；
 *  - y 轴翻转、等比缩放居中（包围盒取原始 ∪ 变形的并集）。
 */
@Composable
private fun FrameDeformPlot(
    result: FrameResult,
    deformScale: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val polylines = result.polylines
        if (polylines.isEmpty()) return@Canvas

        fun ox(pt: List<Double>) = pt.getOrElse(0) { 0.0 }.toFloat()
        fun oy(pt: List<Double>) = pt.getOrElse(1) { 0.0 }.toFloat()
        fun dx(pt: List<Double>) = pt.getOrElse(2) { 0.0 }.toFloat()
        fun dy(pt: List<Double>) = pt.getOrElse(3) { 0.0 }.toFloat()

        // 同一对节点上重复建了两根杆件时，图上会叠出两条线（例如一条蓝、一条红）：
        // 按原始端点坐标分组，每组只保留应力最大的一条，避免多画一根假的"多余杆件"
        fun sigmaOf(id: Int) = result.members.firstOrNull { it.id == id }?.sigma_MPa ?: 0.0
        val drawn = LinkedHashMap<List<Long>, FramePolyline>()
        polylines.forEach { pl ->
            if (pl.pts.isEmpty()) return@forEach
            val a = pl.pts.first()
            val b = pl.pts.last()
            val key = listOf(
                (min(ox(a), ox(b)) * 10.0).roundToLong(),
                (min(oy(a), oy(b)) * 10.0).roundToLong(),
                (max(ox(a), ox(b)) * 10.0).roundToLong(),
                (max(oy(a), oy(b)) * 10.0).roundToLong()
            )
            val prev = drawn[key]
            if (prev == null || sigmaOf(pl.member) > sigmaOf(prev.member)) drawn[key] = pl
        }
        // 端点不完全相同、但几何上重叠的杆件（如层板与横撑错开几毫米建模）：
        // 图上会叠出第二根"平直蓝线"，按应力降序过滤，只画应力较大的一根
        val kept = mutableListOf<FramePolyline>()
        drawn.values.sortedByDescending { sigmaOf(it.member) }.forEach { pl ->
            if (kept.none { overlapsGeometrically(it, pl) }) kept.add(pl)
        }

        // 包围盒：原始 ∪ 变形
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        polylines.forEach { pl ->
            pl.pts.forEach { pt ->
                val x0 = ox(pt); val y0 = oy(pt)
                val x1 = x0 + dx(pt) * deformScale
                val y1 = y0 + dy(pt) * deformScale
                minX = min(minX, min(x0, x1)); maxX = max(maxX, max(x0, x1))
                minY = min(minY, min(y0, y1)); maxY = max(maxY, max(y0, y1))
            }
        }
        val spanX = (maxX - minX).coerceAtLeast(1e-6f)
        val spanY = (maxY - minY).coerceAtLeast(1e-6f)

        val pad = 14.dp.toPx()
        val scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
        val offX = (size.width - spanX * scale) / 2f
        val offY = (size.height - spanY * scale) / 2f
        fun px(x: Float) = offX + (x - minX) * scale
        fun py(y: Float) = offY + (maxY - y) * scale

        val maxSigma = result.max_stress_MPa.coerceAtLeast(1e-9)

        // 原始形状（虚线灰）
        if (deformScale > 1.05f) {
            val origPath = Path()
            kept.forEach { pl ->
                if (pl.pts.isEmpty()) return@forEach
                origPath.moveTo(px(ox(pl.pts.first())), py(oy(pl.pts.first())))
                pl.pts.drop(1).forEach { pt -> origPath.lineTo(px(ox(pt)), py(oy(pt))) }
            }
            drawPath(
                origPath,
                Color.Gray.copy(alpha = 0.55f),
                style = Stroke(
                    1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )
            )
        }

        // 变形形状（按杆件应力着色）
        kept.forEach { pl ->
            if (pl.pts.size < 2) return@forEach
            val sigma = sigmaOf(pl.member)
            val t = (sigma / maxSigma).toFloat()
            val path = Path()
            path.moveTo(px(ox(pl.pts.first()) + dx(pl.pts.first()) * deformScale),
                py(oy(pl.pts.first()) + dy(pl.pts.first()) * deformScale))
            pl.pts.drop(1).forEach { pt ->
                path.lineTo(px(ox(pt) + dx(pt) * deformScale), py(oy(pt) + dy(pt) * deformScale))
            }
            drawPath(path, ColorRamp.colorAt(t), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/**
 * 判断两根杆件的**原始**几何是否重叠（同一直线上且长度大部分重合）：
 * 容差 3 mm 垂距、夹角 ≤ ~10°、较短杆重合比例 ≥ 60%。
 * 用于合并 AI 建模时错开几毫米建出的重复杆件，避免图上多画一根平直蓝线。
 */
internal fun overlapsGeometrically(
    a: FramePolyline,
    b: FramePolyline,
    tolMm: Float = 3f,
    minOverlap: Float = 0.6f
): Boolean {
    if (a.pts.size < 2 || b.pts.size < 2) return false
    fun gx(p: List<Double>) = p.getOrElse(0) { 0.0 }.toFloat()
    fun gy(p: List<Double>) = p.getOrElse(1) { 0.0 }.toFloat()
    val a1x = gx(a.pts.first()); val a1y = gy(a.pts.first())
    val a2x = gx(a.pts.last()); val a2y = gy(a.pts.last())
    val ax = a2x - a1x; val ay = a2y - a1y
    val bx = gx(b.pts.last()) - gx(b.pts.first()); val by = gy(b.pts.last()) - gy(b.pts.first())
    val la = hypot(ax, ay); val lb = hypot(bx, by)
    if (la < 1e-3f || lb < 1e-3f) return false
    val ux = ax / la; val uy = ay / la
    // 方向平行（允许 ~10° 误差）
    if (abs(ux * (bx / lb) + uy * (by / lb)) < 0.985f) return false
    // 两端点到 a 所在直线的垂距
    fun perp(px: Float, py: Float) = abs((px - a1x) * -uy + (py - a1y) * ux)
    if (perp(gx(b.pts.first()), gy(b.pts.first())) > tolMm) return false
    if (perp(gx(b.pts.last()), gy(b.pts.last())) > tolMm) return false
    // 沿 a 方向的一维重合比例
    fun proj(px: Float, py: Float) = (px - a1x) * ux + (py - a1y) * uy
    val t1 = proj(gx(b.pts.first()), gy(b.pts.first()))
    val t2 = proj(gx(b.pts.last()), gy(b.pts.last()))
    val lo = max(0f, min(t1, t2)); val hi = min(la, max(t1, t2))
    return (hi - lo).coerceAtLeast(0f) / min(la, lb) >= minOverlap
}

// =====================================================================
// PDF 导出辅助
// =====================================================================
/**
 * 把缓存目录的 PDF 复制到公共 Downloads 目录（MediaStore，无需存储权限）。
 * 返回保存的显示文件名；失败返回 null（调用方可提示用户去缓存目录取）。
 */
internal fun savePdfToDownloads(
    context: android.content.Context,
    source: java.io.File,
    modelName: String
): String? {
    val resolver = context.contentResolver
    val safeName = (modelName.ifBlank { context.getString(R.string.frame_analysis_default_name) })
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
    val filename = "${context.getString(R.string.app_name)}_${safeName}_${System.currentTimeMillis()}.pdf"

    return try {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: return null

        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        filename
    } catch (e: Exception) {
        null
    }
}
