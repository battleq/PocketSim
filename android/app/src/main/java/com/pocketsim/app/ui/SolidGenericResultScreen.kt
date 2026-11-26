/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.AppContainer
import com.pocketsim.app.AppJson
import com.pocketsim.app.LocaleManager
import com.pocketsim.app.LocaleStore
import com.pocketsim.app.R
import com.pocketsim.app.OptimizeGate
import com.pocketsim.app.SafetyLogic
import com.pocketsim.app.SavedProject
import com.pocketsim.app.SolidGenericModel
import com.pocketsim.app.SolidGenericResult
import com.pocketsim.app.Units
import com.pocketsim.app.materialDisplayName
import com.pocketsim.app.ui.components.ColorLegend
import com.pocketsim.app.ui.components.MetricCard
import kotlinx.coroutines.launch

/**
 * 通用三维实体求解结果页（box/cylinder/plate 等图元的应力/位移）：
 *  - 结论横幅（注意/危险）
 *  - 指标三联卡（最大位移 / 最大 von Mises / 综合安全系数）
 *  - 三维应力云图（复用 SolidStressPlot，可旋转/缩放）
 *  - 工程校核（拉/压强度）、支反力、计算假定
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolidGenericResultScreen(
    container: AppContainer,
    model: SolidGenericModel,
    result: SolidGenericResult,
    onBack: () -> Unit,
    /** 返回对话修改模型并重算（为空时不显示"修改模型"） */
    onModify: (() -> Unit)? = null,
    /** 进入 AI 优化建议页（仅注意/危险时由内部门控显示按钮） */
    onOptimize: () -> Unit = {}
) {
    val grade = SafetyLogic.grade(result.metrics.safety_factor)
        .takeIf { it != SafetyLogic.Grade.UNKNOWN }
        ?: SafetyLogic.gradeFromVerdict(result.metrics.verdict)

    val context = LocalContext.current
    val english = LocaleManager.currentLanguageCode(context) == LocaleStore.LANG_EN
    val scope = rememberCoroutineScope()
    val defaultTitle = stringResource(R.string.solid_generic_result_title)
    var saving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    /** 保存到我的项目（启动页"打开老项目"可回放） */
    fun saveProject() {
        if (saving) return
        saving = true
        saveMessage = null
        scope.launch {
            try {
                val modelJson = AppJson.encodeToString(SolidGenericModel.serializer(), model)
                val resultJson = AppJson.encodeToString(SolidGenericResult.serializer(), result)
                val defaultName = context.getString(R.string.solid_generic_default_name)
                container.projectRepository.save(
                    SavedProject(
                        id = System.currentTimeMillis(),
                        name = result.model_name.ifBlank { model.name.ifBlank { defaultName } },
                        kind = "solid_generic",
                        modelJson = modelJson,
                        resultJson = resultJson,
                        verdict = result.metrics.verdict,
                        maxStressMPa = result.metrics.max_von_mises_MPa,
                        safetyFactor = result.metrics.safety_factor,
                        createdAt = System.currentTimeMillis()
                    )
                )
                saveMessage = context.getString(R.string.frame_saved_message)
            } catch (e: Exception) {
                saveMessage = context.getString(R.string.frame_save_failed_fmt, e.message ?: "")
            } finally {
                saving = false
            }
        }
    }

    /** 导出 PDF 报告到公共 Downloads 目录 */
    fun exportPdf() {
        if (exporting) return
        exporting = true
        exportMessage = null
        scope.launch {
            try {
                val cacheFile = java.io.File(
                    context.cacheDir,
                    "pocketsim_solid_${System.currentTimeMillis()}.pdf"
                )
                container.engine.exportSolidGenericPdf(model, result, cacheFile.absolutePath)
                val saved = savePdfToDownloads(context, cacheFile, result.model_name.ifBlank { model.name })
                exportMessage = if (saved != null) {
                    context.getString(R.string.frame_pdf_saved_fmt, saved)
                } else {
                    context.getString(R.string.frame_pdf_generated_fmt, cacheFile.name)
                }
            } catch (e: Exception) {
                exportMessage = context.getString(R.string.frame_export_failed_fmt, e.message ?: "")
            } finally {
                exporting = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(result.model_name.ifBlank { model.name.ifBlank { defaultTitle } }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back)
                        )
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
                            .padding(horizontal = 16.dp, vertical = 6.dp)
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
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(if (exporting) stringResource(R.string.frame_exporting) else stringResource(R.string.frame_pdf_export))
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
                    // 修改模型：回到对话重新描述/调整条件后再算
                    onModify?.let { modify ->
                        OutlinedButton(
                            onClick = modify,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(stringResource(R.string.solid_generic_modify_btn))
                        }
                    }
                    Text(
                        text = stringResource(R.string.disclaimer_full),
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
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
            // ---- 结论横幅（注意/危险时置顶） ----
            if (grade == SafetyLogic.Grade.DANGER || grade == SafetyLogic.Grade.CAUTION) {
                item {
                    val danger = grade == SafetyLogic.Grade.DANGER
                    val (dispVal, dispUnit) = formatDisp(result.metrics.max_disp_mm)
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
                                    text = if (danger) stringResource(R.string.solid_generic_verdict_danger)
                                        else stringResource(R.string.solid_generic_verdict_caution),
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
                                    R.string.solid_generic_safety_fmt,
                                    formatNum(result.metrics.safety_factor),
                                    formatNum(result.metrics.sf_tension),
                                    formatNum(result.metrics.sf_compression)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = bodyColor
                            )
                            Text(
                                text = stringResource(
                                    R.string.solid_generic_stress_disp_fmt,
                                    Units.stress(result.metrics.max_von_mises_MPa).toString(),
                                    "${dispVal} ${dispUnit}"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = subtleColor
                            )
                        }
                    }
                }
            }

            // ---- 指标三联卡 ----
            item {
                val (dispVal, dispUnit) = formatDisp(result.metrics.max_disp_mm)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricCard(
                        title = stringResource(R.string.solid_generic_metric_disp),
                        value = dispVal,
                        unit = dispUnit,
                        footer = stringResource(R.string.solid_generic_disp_node_fmt, result.metrics.max_disp_node),
                        modifier = Modifier.weight(1f)
                    )
                    val stressQ = Units.stress(result.metrics.max_von_mises_MPa)
                    MetricCard(
                        title = stringResource(R.string.metric_max_stress),
                        value = stressQ.text,
                        unit = stressQ.unit,
                        footer = stringResource(
                            R.string.solid_generic_stress_footer_fmt,
                            Units.stress(result.metrics.max_tension_MPa).toString(),
                            Units.stress(result.metrics.max_compression_MPa).toString()
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = stringResource(R.string.metric_safety),
                        value = formatNum(result.metrics.safety_factor),
                        unit = "",
                        accent = gradeColor(grade),
                        footer = gradeLabel(grade),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ---- 三维应力云图 ----
            result.render?.let { render ->
                item {
                    Card(shape = RoundedCornerShape(4.dp)) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.solid_vonmises_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            SolidStressPlot(
                                render = render,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(300.dp),
                                dimLabels = solidOutlineDims(model)
                            )
                            val vmax = render.values.maxOrNull() ?: Units.stress(result.metrics.max_von_mises_MPa).value
                            val stressLowFmt = stringResource(R.string.solid_stress_low_fmt)
                            val stressHighFmt = stringResource(R.string.solid_stress_high_fmt)
                            ColorLegend(
                                minLabel = stressLowFmt.format(render.unit),
                                maxLabel = stressHighFmt.format("${formatNum(vmax)} ${render.unit}")
                            )
                            Text(
                                text = stringResource(R.string.solid_3d_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ---- 工程校核 ----
            item {
                Card(shape = RoundedCornerShape(4.dp)) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.solid_generic_checks_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        val checkItemFmt = stringResource(R.string.seepage_check_item_fmt)
                        val forceResistFmt = stringResource(R.string.seepage_check_force_resist_fmt)
                        result.checks.forEachIndexed { index, check ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = checkItemFmt.format(check.name, formatNum(check.safety_factor)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = if (check.pass) Color(0xFF2E7D32) else Color(0xFFC62828)
                                )
                                val vq = Units.convert(check.value, check.unit)
                                val lq = Units.convert(check.limit, check.unit)
                                Text(
                                    text = forceResistFmt.format(
                                        formatNum(vq.value),
                                        vq.unit,
                                        formatNum(lq.value)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ---- 支反力 ----
            item {
                Card(shape = RoundedCornerShape(4.dp)) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.solid_generic_reactions_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(
                                R.string.solid_generic_reactions_fmt,
                                Units.force(result.reactions.fx_N).toString(),
                                Units.force(result.reactions.fy_N).toString(),
                                Units.force(result.reactions.fz_N).toString()
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(
                                R.string.solid_generic_weight_fmt,
                                Units.forceK(result.geometry.weight_kN * 1000.0).toString(),
                                Units.volume(result.geometry.volume_m3).toString()
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- 计算假定 ----
            if (result.assumptions.isNotEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(4.dp)) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.seepage_assumptions_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            val bulletFmt = stringResource(R.string.seepage_bullet_fmt)
                            result.assumptions.forEach { a ->
                                Text(
                                    text = bulletFmt.format(a),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(
                        R.string.solid_generic_mesh_info_fmt,
                        materialDisplayName(result.material, english),
                        Units.stress(result.material_params.eMPa).toString(),
                        formatNum(result.material_params.nu),
                        result.mesh.n_elements,
                        result.mesh.n_vertices,
                        result.mesh.dof,
                        formatNum(result.timing.total_ms),
                        result.mesh.solver
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
