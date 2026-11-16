/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

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
import com.pocketsim.app.SafetyLogic
import com.pocketsim.app.SavedProject
import com.pocketsim.app.ShellVesselModel
import com.pocketsim.app.SolidGenericResult
import com.pocketsim.app.Units
import com.pocketsim.app.materialDisplayName
import com.pocketsim.app.ui.components.ColorLegend
import com.pocketsim.app.ui.components.MetricCard
import kotlinx.coroutines.launch

/**
 * 薄壁容器求解结果页（水箱/料仓，壳单元 MITC4 的位移/应力）：
 * 输出 JSON 与 [SolidGenericResult] 同构，因此应力云图、指标、校核、支反力等
 * 全部复用实体结果页的同一套渲染；差异仅在标题、保存类型（kind=shell_vessel）
 * 与底部不提供 PDF 导出（暂无对应 Python 导出函数）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellResultScreen(
    container: AppContainer,
    model: ShellVesselModel,
    result: SolidGenericResult,
    onBack: () -> Unit,
    /** 返回对话修改模型并重算（为空时不显示"修改模型"） */
    onModify: (() -> Unit)? = null
) {
    val grade = SafetyLogic.grade(result.metrics.safety_factor)
        .takeIf { it != SafetyLogic.Grade.UNKNOWN }
        ?: SafetyLogic.gradeFromVerdict(result.metrics.verdict)

    val context = LocalContext.current
    val english = LocaleManager.currentLanguageCode(context) == LocaleStore.LANG_EN
    val scope = rememberCoroutineScope()
    val defaultTitle = stringResource(R.string.shell_result_title)
    var saving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    /** 保存到我的项目（启动页"打开老项目"可回放） */
    fun saveProject() {
        if (saving) return
        saving = true
        saveMessage = null
        scope.launch {
            try {
                val modelJson = AppJson.encodeToString(ShellVesselModel.serializer(), model)
                val resultJson = AppJson.encodeToString(SolidGenericResult.serializer(), result)
                val defaultName = context.getString(R.string.shell_default_name)
                container.projectRepository.save(
                    SavedProject(
                        id = System.currentTimeMillis(),
                        name = result.model_name.ifBlank { model.name.ifBlank { defaultName } },
                        kind = "shell_vessel",
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
                            Text(
                                text = if (danger) stringResource(R.string.solid_generic_verdict_danger)
                                    else stringResource(R.string.solid_generic_verdict_caution),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = accent
                            )
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

            // ---- 三维应力云图（render 与 SolidGenericResult 同构，直接复用 SolidStressPlot） ----
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
                                    .height(300.dp)
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

            // ---- 支反力 / 自重 ----
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
                                R.string.shell_weight_fmt,
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

            // ---- 结论/建议（含热冲击提示）----
            if (result.advice.isNotEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(4.dp)) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.solid_advice_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            result.advice.forEach { advice ->
                                Text(text = "· $advice", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(
                        R.string.shell_mesh_info_fmt,
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
