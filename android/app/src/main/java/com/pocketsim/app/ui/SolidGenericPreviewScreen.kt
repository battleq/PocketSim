/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.AppContainer
import com.pocketsim.app.AppSettings
import com.pocketsim.app.LocaleManager
import com.pocketsim.app.LocaleStore
import com.pocketsim.app.R
import com.pocketsim.app.SolidGenericGeometryInput
import com.pocketsim.app.SolidGenericLoad
import com.pocketsim.app.SolidGenericModel
import com.pocketsim.app.SolidGenericResult
import com.pocketsim.app.SolidGenericSupport
import com.pocketsim.app.Units
import com.pocketsim.app.materialDisplayName
import kotlinx.coroutines.launch

/**
 * 通用三维实体模型预览确认页（box/cylinder/plate + supports + loads）：
 *  - Canvas 等轴测示意图：按比例的几何体（box 长方体 / cylinder 近似圆柱 / plate 薄板）
 *  - 模型信息卡：几何尺寸、材料、约束、载荷、网格档位
 *  - "开始计算" → engine.runSolidGeneric → 通用三维实体结果页（应力云图 + 位移/安全系数）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SolidGenericPreviewScreen(
    container: AppContainer,
    model: SolidGenericModel,
    onBack: () -> Unit,
    onResult: (SolidGenericModel, SolidGenericResult) -> Unit,
    onRegenRequest: (String) -> Unit = {}
) {
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // "不满意，修改模型"：带回对话让 AI 重建模
    var showRegenDialog by remember { mutableStateOf(false) }
    var regenFeedback by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val unknownError = stringResource(R.string.error_unknown_default)
    val english = LocaleManager.currentLanguageCode(LocalContext.current) == LocaleStore.LANG_EN

    // 网格档位：AI 未指定时按设置页的"默认网格档位"预选，用户也可在预览页直接改
    val settings by container.settingsRepository.settings.collectAsState(initial = AppSettings())
    val initialMesh = remember(model) { normalizeMeshLevel(model.mesh_level) }
    var meshLevel by remember(model) { mutableStateOf(initialMesh) }
    var meshTouched by remember(model) { mutableStateOf(false) }
    LaunchedEffect(settings.defaultMeshLevel) {
        if (!meshTouched && initialMesh == "medium") {
            meshLevel = normalizeMeshLevel(settings.defaultMeshLevel)
        }
    }

    if (showRegenDialog) {
        AlertDialog(
            onDismissRequest = { showRegenDialog = false },
            title = { Text(stringResource(R.string.solid_regen_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.solid_generic_regen_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = regenFeedback,
                        onValueChange = { regenFeedback = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        placeholder = { Text(stringResource(R.string.solid_generic_regen_placeholder)) }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val fb = regenFeedback.trim()
                        if (fb.isNotBlank()) {
                            showRegenDialog = false
                            regenFeedback = ""
                            onRegenRequest(fb)
                        }
                    },
                    enabled = regenFeedback.trim().isNotBlank()
                ) { Text(stringResource(R.string.solid_regen_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { showRegenDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.solid_preview_title)) },
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    error?.let {
                        Text(
                            text = stringResource(R.string.frame_run_failed, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            regenFeedback = ""
                            showRegenDialog = true
                        },
                        enabled = !running,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Text(stringResource(R.string.solid_modify_btn))
                    }
                    Button(
                        onClick = {
                            if (running) return@Button
                            scope.launch {
                                running = true
                                error = null
                                try {
                                    val runModel = model.copy(mesh_level = meshLevel)
                                    val result = container.engine.runSolidGeneric(runModel)
                                    onResult(runModel, result)
                                } catch (e: Exception) {
                                    error = e.message ?: unknownError
                                } finally {
                                    running = false
                                }
                            }
                        },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (running) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Text(text = stringResource(R.string.solid_running), style = MaterialTheme.typography.labelLarge)
                        } else {
                            Text(stringResource(R.string.param_btn_run))
                        }
                    }
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
            item {
                Text(
                    text = stringResource(R.string.solid_generic_preview_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Card(shape = RoundedCornerShape(4.dp)) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val defaultName = stringResource(R.string.solid_generic_default_name)
                        Text(
                            text = model.name.ifBlank { defaultName },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        SolidGenericSketch(
                            model = model,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                        )
                        Text(
                            text = stringResource(R.string.solid_generic_schematic_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Card(shape = RoundedCornerShape(4.dp)) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.solid_info_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.solid_generic_geometry_fmt, genericGeometryText(model)),
                            style = MaterialTheme.typography.bodySmall
                        )
                        val unspecified = stringResource(R.string.unspecified)
                        Text(
                            text = stringResource(R.string.solid_generic_material_fmt,
                                materialDisplayName(model.material, english).ifBlank { unspecified },
                                genericMaterialText(model)
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(
                                R.string.solid_generic_supports_fmt,
                                model.supports.size,
                                genericSupportsText(model.supports)
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(
                                R.string.solid_generic_loads_fmt,
                                model.loads.size,
                                genericLoadsText(model)
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(R.string.solid_mesh_hint),
                            style = MaterialTheme.typography.bodySmall
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            MeshLevelChip("coarse", meshLevel) { meshLevel = it; meshTouched = true }
                            MeshLevelChip("medium", meshLevel) { meshLevel = it; meshTouched = true }
                            MeshLevelChip("fine", meshLevel) { meshLevel = it; meshTouched = true }
                            MeshLevelChip("ultra", meshLevel) { meshLevel = it; meshTouched = true }
                        }
                        Text(
                            text = stringResource(R.string.solid_generic_compute_content),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.solid_generic_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                val heavy = meshLevel in listOf("fine", "ultra")
                Card(
                    shape = RoundedCornerShape(4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (heavy) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (heavy) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (heavy) stringResource(R.string.solid_generic_mesh_warn_heavy)
                                else stringResource(R.string.solid_generic_mesh_warn_normal),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (heavy) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.disclaimer_full),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 材料补充参数文案 */
@Composable
private fun genericMaterialText(model: SolidGenericModel): String {
    val parts = ArrayList<String>()
    val ePrefix = stringResource(R.string.solid_generic_mat_e_prefix)
    val nuPrefix = stringResource(R.string.solid_generic_mat_nu_prefix)
    val densityPrefix = stringResource(R.string.solid_generic_mat_density_prefix)
    val parenOpen = stringResource(R.string.solid_generic_paren_open)
    val parenClose = stringResource(R.string.solid_generic_paren_close)
    val comma = stringResource(R.string.solid_generic_comma)
    model.eMPa?.let { parts.add(ePrefix + Units.stress(it).toString()) }
    model.nu?.let { parts.add(nuPrefix + formatNum(it)) }
    model.density_kg_m3?.let { parts.add(densityPrefix + Units.density(it).toString()) }
    return if (parts.isEmpty()) "" else parenOpen + parts.joinToString(comma) + parenClose
}

/** 几何尺寸文案（按图元类型选取对应字段） */
@Composable
private fun genericGeometryText(model: SolidGenericModel): String {
    val g = model.geometry
    val cylinderFmt = stringResource(R.string.solid_generic_geom_cylinder_fmt)
    val plateFmt = stringResource(R.string.solid_generic_geom_plate_fmt)
    val boxFmt = stringResource(R.string.solid_generic_geom_box_fmt)
    return when (g.type.trim().lowercase()) {
        "cylinder" -> cylinderFmt.format(
            Units.meters(g.radius_m ?: 0.0).toString(),
            Units.meters(g.height_m ?: 0.0).toString()
        )
        "plate" -> plateFmt.format(
            Units.meters(g.length_m ?: 0.0).toString(),
            Units.meters(g.width_m ?: 0.0).toString(),
            Units.length((g.thickness_m ?: 0.0) * 1000.0).toString()
        )
        else -> boxFmt.format(
            Units.meters(g.length_m ?: 0.0).toString(),
            Units.meters(g.height_m ?: 0.0).toString(),
            Units.length((g.thickness_m ?: 0.0) * 1000.0).toString()
        )
    }
}

/** 面定位文案：语义锚点 at 显示「最小面/最大面」（英文 min/max face），否则显示数值坐标 */
private fun facePosLabel(at: String?, numeric: Double, minLabel: String, maxLabel: String): String =
    when (at?.trim()?.lowercase()) {
        "min" -> minLabel
        "max" -> maxLabel
        else -> Units.meters(numeric).toString()
    }

/** 约束列表文案 */
@Composable
private fun genericSupportsText(supports: List<SolidGenericSupport>): String {
    if (supports.isEmpty()) return stringResource(R.string.solid_generic_none)
    val sep = stringResource(R.string.solid_generic_separator)
    val allFixed = stringResource(R.string.solid_generic_support_all_fixed)
    val planeFmt = stringResource(R.string.solid_generic_support_plane_fmt)
    val pointFmt = stringResource(R.string.solid_generic_support_point_fmt)
    val outlineFmt = stringResource(R.string.solid_generic_support_outline_fmt)
    val minLabel = stringResource(R.string.solid_generic_face_min)
    val maxLabel = stringResource(R.string.solid_generic_face_max)
    return supports.joinToString(sep) { sp ->
        val dofs = if (sp.dofs.containsAll(listOf(0, 1, 2))) allFixed
        else sp.dofs.sorted().joinToString(",") { "xyz"[it].toString() }
        when (sp.type.trim().lowercase()) {
            "plane" -> planeFmt.format(sp.axis, facePosLabel(sp.at, sp.value ?: 0.0, minLabel, maxLabel), dofs)
            "point" -> pointFmt.format(
                sp.xyz?.joinToString(",") { formatNum(it) }, dofs
            )
            "outline" -> outlineFmt.format(
                sp.face?.axis ?: "?",
                facePosLabel(sp.face?.at, sp.face?.value ?: 0.0, minLabel, maxLabel),
                dofs
            )
            else -> sp.type
        }
    }
}

/** 竖直向上轴：plate 默认 z（厚度方向），其余默认 y；geometry.up_axis 可覆盖。 */
private fun defaultUpAxis(g: SolidGenericGeometryInput): String {
    val override = g.up_axis?.trim()?.lowercase()
    if (override in listOf("x", "y", "z")) return override!!
    return if (g.type.trim().lowercase() == "plate") "z" else "y"
}

/** 载荷列表文案 */
@Composable
private fun genericLoadsText(model: SolidGenericModel): String {
    if (model.loads.isEmpty()) return stringResource(R.string.solid_generic_none)
    val upAxis = defaultUpAxis(model.geometry)
    val sep = stringResource(R.string.solid_generic_separator)
    val hydroFmt = stringResource(R.string.solid_generic_load_hydro_fmt)
    val pressureFmt = stringResource(R.string.solid_generic_load_pressure_fmt)
    val gravityDirFmt = stringResource(R.string.solid_generic_load_gravity_dir_fmt)
    val gravityDefaultFmt = stringResource(R.string.solid_generic_load_gravity_default_fmt)
    val forceFmt = stringResource(R.string.solid_generic_load_force_fmt)
    val windFmt = stringResource(R.string.solid_generic_load_wind_fmt)
    val beaufortFmt = stringResource(R.string.solid_generic_load_beaufort_fmt)
    val massareaFmt = stringResource(R.string.solid_generic_load_massarea_fmt)
    val waterLabel = stringResource(R.string.solid_generic_fluid_water)
    val soilLabel = stringResource(R.string.solid_generic_fluid_soil)
    val depthFmt = stringResource(R.string.solid_generic_load_depth_fmt)
    val dirOutLabel = stringResource(R.string.solid_generic_load_dir_out)
    val dirInLabel = stringResource(R.string.solid_generic_load_dir_in)
    val massforceFmt = stringResource(R.string.solid_generic_load_massforce_fmt)
    val minLabel = stringResource(R.string.solid_generic_face_min)
    val maxLabel = stringResource(R.string.solid_generic_face_max)
    return model.loads.joinToString(sep) { ld ->
        when (ld.type.trim().lowercase()) {
            "pressure" -> {
                val f = ld.face
                // 量纲换算：展示原始量 + 说明由代码换算（不静默改写数据）
                val src = when {
                    ld.wind_speed_m_s != null ->
                        windFmt.format(ld.wind_speed_m_s.toString())
                    ld.beaufort != null ->
                        beaufortFmt.format(ld.beaufort.toString())
                    ld.mass_kg != null && ld.area_m2 != null ->
                        massareaFmt.format(ld.mass_kg.toString(), ld.area_m2.toString())
                    ld.depth_m != null -> {
                        val fluid = if ((ld.fluid ?: "water").trim().lowercase() in setOf("soil","earth","土"))
                            soilLabel else waterLabel
                        depthFmt.format(fluid, ld.depth_m.toString())
                    }
                    else -> Units.pressure(ld.magnitude ?: 0.0).toString()
                }
                val dirTxt = if ((ld.pressure_dir ?: "in").trim().lowercase() == "out")
                    dirOutLabel else dirInLabel
                val srcWithDir = "$src$dirTxt"
                if (ld.hydrostatic == true || ld.depth_m != null)
                    hydroFmt.format(f?.axis, facePosLabel(f?.at, f?.value ?: 0.0, minLabel, maxLabel), srcWithDir)
                else
                    pressureFmt.format(f?.axis, facePosLabel(f?.at, f?.value ?: 0.0, minLabel, maxLabel), srcWithDir)
            }
            "gravity" -> {
                val d = ld.direction
                if (d != null) gravityDirFmt.format(d.joinToString(",") { formatNum(it) })
                else gravityDefaultFmt.format(upAxis)
            }
            "force" -> {
                if (ld.mass_kg != null && ld.direction != null) {
                    val d = ld.direction.joinToString(",") { formatNum(it) }
                    massforceFmt.format(ld.mass_kg.toString(), d)
                } else {
                    val v = ld.vector
                    forceFmt.format(
                        v?.joinToString(",") { Units.force(it).text },
                        Units.force(1.0).unit
                    )
                }
            }
            else -> ld.type
        }
    }
}

/** 网格档位文案 */
private fun normalizeMeshLevel(raw: String?): String =
    raw?.trim()?.lowercase()?.takeIf { it in setOf("coarse", "medium", "fine", "ultra") }
        ?: "medium"

/** 网格档位选择芯片（预览页可直接改，默认值来自设置页） */
@Composable
private fun MeshLevelChip(value: String, selected: String, onSelect: (String) -> Unit) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelect(value) },
        label = {
            Text(
                text = stringResource(
                    when (value) {
                        "coarse" -> R.string.mesh_coarse
                        "fine" -> R.string.mesh_fine
                        "ultra" -> R.string.mesh_ultra
                        else -> R.string.mesh_medium
                    }
                )
            )
        }
    )
}

/**
 * 通用三维实体等轴测示意图：
 *  - box/plate：长方体（plate 较薄）
 *  - cylinder：用八棱柱近似圆柱
 *  约束面按实际 supports 高亮（琥珀色描边+斜线）；每个载荷都画方向箭头并标注数值/单位。
 */
@Composable
private fun SolidGenericSketch(model: SolidGenericModel, modifier: Modifier) {
    val g = model.geometry
    val isCylinder = g.type.trim().lowercase() == "cylinder"
    val isPlate = g.type.trim().lowercase() == "plate"
    // 统一用外接长方体的尺寸做投影（Python 坐标：x=长、y=高/宽、z=厚）
    val l = when {
        isCylinder -> (g.radius_m ?: 0.0) * 2.0
        else -> g.length_m ?: 0.1
    }.coerceAtLeast(0.1)
    val h = when {
        isPlate -> g.width_m ?: 0.1
        else -> g.height_m ?: 0.1
    }.coerceAtLeast(0.1)
    val t = when {
        isCylinder -> (g.radius_m ?: 0.0) * 2.0
        else -> g.thickness_m ?: 0.1
    }.coerceAtLeast(0.01)
    val upAxis = defaultUpAxis(g)

    val diameterFmt = stringResource(R.string.solid_generic_dim_diameter_fmt)
    val lengthFmt = stringResource(R.string.solid_dim_length_fmt)
    val widthFmt = stringResource(R.string.solid_generic_dim_width_fmt)
    val heightFmt = stringResource(R.string.solid_dim_height_fmt)
    val thicknessFmt = stringResource(R.string.solid_dim_thickness_fmt)
    val selfWeightLabel = stringResource(R.string.solid_generic_label_selfweight)

    var view by remember { mutableStateOf(View3D(yawDeg = 34.0, pitchDeg = 18.0)) }
    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        view = view.copy(
                            yawDeg = view.yawDeg + pan.x * 0.35,
                            pitchDeg = (view.pitchDeg + pan.y * 0.35).coerceIn(-85.0, 85.0),
                            zoom = (view.zoom * zoom).coerceIn(0.4f, 4f)
                        )
                    }
                }
        ) {
        // Python 坐标（x=长、y=高/宽、z=厚）→ 显示坐标（View3D 以 z 为屏幕竖直）：
        // 始终把模型的竖直轴（upAxis）映射到屏幕竖直方向，自重箭头才会真正朝下
        fun wp(x: Double, y: Double, z: Double) = when (upAxis) {
            "z" -> WorldPoint(x, y, z)
            "x" -> WorldPoint(y, z, x)
            else -> WorldPoint(x, z, y)
        }

        val corners = listOf(
            wp(0.0, 0.0, 0.0), wp(l, 0.0, 0.0), wp(l, h, 0.0), wp(0.0, h, 0.0),
            wp(0.0, 0.0, t), wp(l, 0.0, t), wp(l, h, t), wp(0.0, h, t)
        )
        val pad = 34.dp.toPx()
        val projector = SceneProjector(
            view = view,
            bounds = viewBounds(corners, view),
            canvasWidth = size.width,
            canvasHeight = size.height,
            padPx = pad
        )
        val p = corners.map { projector.project(it) }

        fun quad(a: Int, b: Int, c: Int, d: Int) = Path().apply {
            moveTo(p[a].x, p[a].y)
            lineTo(p[b].x, p[b].y)
            lineTo(p[c].x, p[c].y)
            lineTo(p[d].x, p[d].y)
            close()
        }

        // 面着色：远面（z=0）→ 底面（y=0）→ 近面（z=t）；圆柱用长方体近似，足够示意
        drawPath(quad(0, 1, 2, 3), Color(0xFF9FA8AD))
        drawPath(quad(0, 1, 5, 4), Color(0xFFCFD8DC))
        drawPath(quad(4, 5, 6, 7), Color(0xFFE3E7EA))

        val edgeColor = Color(0xFF37474F)
        listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 0,
            4 to 5, 5 to 6, 6 to 7, 7 to 4,
            0 to 4, 1 to 5, 2 to 6, 3 to 7
        ).forEach { (a, b) ->
            drawLine(edgeColor, Offset(p[a].x, p[a].y), Offset(p[b].x, p[b].y),
                strokeWidth = 1.4.dp.toPx())
        }

        // 约束面高亮：按实际 supports 高亮对应外表面（琥珀色描边 + 斜线），
        // 不再假定固定面一定是 y=0 底面。
        val supportColor = Color(0xFFFFA000)
        /** 面在哪一侧：语义锚点 at 直接判定，否则按数值与半长比较 */
        fun faceIsMax(axis: String, at: String?, value: Double): Boolean {
            val atN = at?.trim()?.lowercase()
            val dim = when (axis) { "x" -> l; "z" -> t; else -> h }
            return when {
                atN == "min" -> false
                atN == "max" -> true
                else -> value > dim / 2.0
            }
        }
        fun faceEdges(axis: String, atMax: Boolean): List<Pair<Int, Int>> = when (axis) {
            "x" -> if (atMax) listOf(1 to 2, 2 to 6, 6 to 5, 5 to 1)
                   else listOf(0 to 3, 3 to 7, 7 to 4, 4 to 0)
            "y" -> if (atMax) listOf(3 to 2, 2 to 6, 6 to 7, 7 to 3)
                   else listOf(0 to 1, 1 to 5, 5 to 4, 4 to 0)
            else -> if (atMax) listOf(4 to 5, 5 to 6, 6 to 7, 7 to 4)
                    else listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0)
        }
        fun hatchFace(axis: String, atMax: Boolean) {
            faceEdges(axis, atMax).forEach { (a, b) ->
                drawLine(supportColor, Offset(p[a].x, p[a].y), Offset(p[b].x, p[b].y),
                    strokeWidth = 2.6.dp.toPx())
            }
            // 在面的一对对边上画斜线示意“固定”
            val edges = faceEdges(axis, atMax)
            val steps = 6
            val e1 = edges[0]
            val e3 = edges[2]
            for (k in 1 until steps) {
                val f = k / steps.toFloat()
                val x1 = p[e1.first].x + (p[e1.second].x - p[e1.first].x) * f
                val y1 = p[e1.first].y + (p[e1.second].y - p[e1.first].y) * f
                val x2 = p[e3.first].x + (p[e3.second].x - p[e3.first].x) * f
                val y2 = p[e3.first].y + (p[e3.second].y - p[e3.first].y) * f
                drawLine(supportColor, Offset(x1, y1), Offset(x2, y2),
                    strokeWidth = 1.2.dp.toPx())
            }
        }
        model.supports.forEach { sp ->
            when (sp.type.trim().lowercase()) {
                "plane", "outline" -> {
                    val axis = (sp.axis ?: sp.face?.axis ?: "y").trim().lowercase()
                    val atMax = faceIsMax(
                        axis,
                        sp.at ?: sp.face?.at,
                        sp.value ?: sp.face?.value ?: 0.0
                    )
                    hatchFace(axis, atMax)
                }
                "point" -> {
                    val xyz = sp.xyz
                    if (xyz != null && xyz.size >= 3) {
                        val tp = projector.project(wp(
                            xyz[0].coerceIn(0.0, l),
                            xyz[1].coerceIn(0.0, h),
                            xyz[2].coerceIn(0.0, t)
                        ))
                        drawCircle(supportColor, 4.dp.toPx(), center = Offset(tp.x, tp.y))
                    }
                }
            }
        }

        // 载荷箭头：方向严格按载荷向量（自重默认沿竖直轴向下，集中力按 vector）
        val arrowColor = Color(0xFF1E88E5)
        val ext = maxOf(l, h, t).coerceAtLeast(1e-6)

        // 真实几何包围盒（与 AiParser.solidBounds / solid._resolve_point_at 一致），
        // 用于 point_at 语义锚点解析：box/plate 角点在原点，cylinder 轴线居中。
        val cylR = if (isCylinder) (g.radius_m ?: 0.0) else 0.0
        val bX = if (isCylinder) (-cylR to cylR) else (0.0 to l)
        val bY = (0.0 to h)
        val bZ = if (isCylinder) (-cylR to cylR) else (0.0 to t)
        /** point_at=[x,y,z] 各取 min/max/center → 世界坐标（预览渲染用，与求解侧一致） */
        fun previewPointAt(pointAt: List<String>): Triple<Double, Double, Double> {
            fun c(a: String, b: Pair<Double, Double>) = when (a.trim().lowercase()) {
                "min" -> b.first; "max" -> b.second; else -> (b.first + b.second) / 2.0
            }
            return Triple(c(pointAt[0], bX), c(pointAt[1], bY), c(pointAt[2], bZ))
        }

        fun unitVec(v: List<Double>?): Triple<Double, Double, Double>? {
            if (v == null || v.size < 3) return null
            val n = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (n < 1e-12) return null
            return Triple(v[0] / n, v[1] / n, v[2] / n)
        }

        fun axisUnit(axis: String, sign: Double): Triple<Double, Double, Double> = when (axis) {
            "x" -> Triple(sign, 0.0, 0.0)
            "z" -> Triple(0.0, 0.0, sign)
            else -> Triple(0.0, sign, 0.0)
        }

        fun drawArrow(tail: Triple<Double, Double, Double>, head: Triple<Double, Double, Double>) {
            val tp = projector.project(wp(tail.first, tail.second, tail.third))
            val hp = projector.project(wp(head.first, head.second, head.third))
            drawLine(arrowColor, Offset(tp.x, tp.y), Offset(hp.x, hp.y),
                strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
            val dx = hp.x - tp.x
            val dy = hp.y - tp.y
            val norm = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-6f)
            val ux = dx / norm
            val uy = dy / norm
            val wing = 5.dp.toPx()
            drawLine(arrowColor, Offset(hp.x, hp.y),
                Offset(hp.x - ux * wing + uy * wing * 0.55f,
                    hp.y - uy * wing - ux * wing * 0.55f),
                strokeWidth = 1.8.dp.toPx())
            drawLine(arrowColor, Offset(hp.x, hp.y),
                Offset(hp.x - ux * wing - uy * wing * 0.55f,
                    hp.y - uy * wing + ux * wing * 0.55f),
                strokeWidth = 1.8.dp.toPx())
        }

        /** 载荷面/顶面上的 3 个采样点（axis 为该面的法向轴） */
        fun surfacePoints(axis: String, atMax: Boolean): List<Triple<Double, Double, Double>> {
            val v = when (axis) {
                "x" -> if (atMax) l else 0.0
                "z" -> if (atMax) t else 0.0
                else -> if (atMax) h else 0.0
            }
            return listOf(0.25, 0.5, 0.75).map { f ->
                when (axis) {
                    "x" -> Triple(v, h * f, t * (0.5 + 0.5 * f))
                    "z" -> Triple(l * f, h * (0.5 + 0.5 * f), v)
                    else -> Triple(l * f, v, t * f)
                }
            }
        }

        // 载荷数值标签（压力 Pa、力 N、自重）
        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.sp.toPx()
            color = android.graphics.Color.parseColor("#1565C0")
        }

        model.loads.forEach { ld ->
            var labelText: String? = null
            var labelAnchor: Triple<Double, Double, Double>? = null
            when (ld.type.trim().lowercase()) {
                "gravity" -> {
                    // 自重：默认沿竖直轴向下（plate 为 -z，box/cylinder 为 -y），也可显式给 direction
                    val d = unitVec(ld.direction) ?: axisUnit(upAxis, -1.0)
                    val axis = when {
                        kotlin.math.abs(d.first) >= kotlin.math.abs(d.second) &&
                            kotlin.math.abs(d.first) >= kotlin.math.abs(d.third) -> "x"
                        kotlin.math.abs(d.third) >= kotlin.math.abs(d.second) -> "z"
                        else -> "y"
                    }
                    val comp = when (axis) {
                        "x" -> d.first
                        "z" -> d.third
                        else -> d.second
                    }
                    val pts = surfacePoints(axis, comp < 0.0)
                    pts.forEach { s ->
                        drawArrow(
                            Triple(
                                s.first - d.first * 0.35 * ext,
                                s.second - d.second * 0.35 * ext,
                                s.third - d.third * 0.35 * ext
                            ), s
                        )
                    }
                    labelText = selfWeightLabel
                    labelAnchor = pts.firstOrNull()
                }
                "pressure" -> {
                    val ax = (ld.face?.axis ?: "y").trim().lowercase()
                    val atMax = faceIsMax(ax, ld.face?.at, ld.face?.value ?: 0.0)
                    // pressure_dir："in"（默认）指向实体内部，"out" 指向外部（吸力）。
                    // 外法线在 +端面为 +轴、0 端面为 -轴；向内 = -外法线。
                    val outward = if (atMax) 1.0 else -1.0
                    val pdir = (ld.pressure_dir ?: "in").trim().lowercase()
                    val sign = if (pdir == "out") 1.0 else -1.0
                    val n = axisUnit(ax, outward * sign)
                    val pts = surfacePoints(ax, atMax)
                    pts.forEach { s ->
                        drawArrow(
                            Triple(
                                s.first - n.first * 0.35 * ext,
                                s.second - n.second * 0.35 * ext,
                                s.third - n.third * 0.35 * ext
                            ), s
                        )
                    }
                    val mag = ld.magnitude ?: 0.0
                    labelText = Units.pressure(mag).toString()
                    labelAnchor = pts.firstOrNull()
                }
                "force" -> {
                    val d = unitVec(ld.vector) ?: axisUnit(upAxis, -1.0)
                    // point_at 语义锚点优先（与 AiParser/solid 一致），其次 point 坐标，最后兜底顶面中心
                    val anchor = ld.point_at
                    val head = if (anchor != null && anchor.size == 3) {
                        previewPointAt(anchor)
                    } else {
                        val pt = ld.point
                        if (pt != null && pt.size >= 3) {
                            Triple(
                                pt[0].coerceIn(bX.first, bX.second),
                                pt[1].coerceIn(bY.first, bY.second),
                                pt[2].coerceIn(bZ.first, bZ.second)
                            )
                        } else {
                            previewPointAt(listOf("center", "max", "center"))
                        }
                    }
                    drawArrow(
                        Triple(
                            head.first - d.first * 0.35 * ext,
                            head.second - d.second * 0.35 * ext,
                            head.third - d.third * 0.35 * ext
                        ), head
                    )
                    val vec = ld.vector
                    val mag = if (vec != null && vec.size >= 3)
                        kotlin.math.sqrt(vec[0] * vec[0] + vec[1] * vec[1] + vec[2] * vec[2]) else 0.0
                    labelText = Units.force(mag).toString()
                    labelAnchor = head
                }
            }
            // 在第一个箭头尾部附近标注数值
            labelText?.let { txt ->
                labelAnchor?.let { a ->
                    val ap = projector.project(wp(a.first, a.second, a.third))
                    drawContext.canvas.nativeCanvas.drawText(
                        txt, ap.x + 6.dp.toPx(), ap.y - 6.dp.toPx(), labelPaint
                    )
                }
            }
        }

        // 尺寸标注
        val nc = drawContext.canvas.nativeCanvas
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.sp.toPx()
            color = android.graphics.Color.DKGRAY
        }
        val dimLabel = if (isCylinder) diameterFmt.format(Units.meters(l).toString()) else lengthFmt.format(Units.meters(l).toString())
        nc.drawText(dimLabel, (p[0].x + p[1].x) / 2f - 18.dp.toPx(),
            (p[0].y + p[1].y) / 2f + 22.dp.toPx(), paint)
        // 竖直方向尺寸：plate 的竖直方向是宽度，其余是高度
        val vertLabel = if (isPlate) widthFmt.format(Units.meters(h).toString()) else heightFmt.format(Units.meters(h).toString())
        nc.drawText(vertLabel, p[2].x + 8.dp.toPx(),
            (p[1].y + p[2].y) / 2f, paint)
        if (!isCylinder) {
            val thickMm = (g.thickness_m ?: t) * 1000.0
            nc.drawText(thicknessFmt.format(Units.length(thickMm).toString()),
                (p[1].x + p[5].x) / 2f + 6.dp.toPx(),
                (p[1].y + p[5].y) / 2f - 8.dp.toPx(), paint)
        }
        }
        // 视角重置（拖动/缩放后一键回到默认视角）
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { view = View3D(yawDeg = 34.0, pitchDeg = 18.0) }) {
                Text(
                    text = stringResource(R.string.frame_3d_reset),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 通用实体显示坐标三轴的尺寸文案（display x / display y / display z），
 * 与 [SolidGenericSketch] 的 wp() 轴映射口径一致；空串表示该轴跳过标注。
 * 供结果页应力云图（SolidStressPlot）复用，保证预览与结果页尺寸一致。
 */
@Composable
internal fun solidOutlineDims(model: SolidGenericModel): Triple<String, String, String> {
    val g = model.geometry
    val type = g.type.trim().lowercase()
    val isCylinder = type == "cylinder"
    val isPlate = type == "plate"
    val L = if (isCylinder) (g.radius_m ?: 0.0) * 2.0 else g.length_m ?: 0.1
    val H = if (isPlate) g.width_m ?: 0.1 else g.height_m ?: 0.1
    val T = if (isCylinder) (g.radius_m ?: 0.0) * 2.0 else g.thickness_m ?: 0.1
    val diameterFmt = stringResource(R.string.solid_generic_dim_diameter_fmt)
    val lengthFmt = stringResource(R.string.solid_dim_length_fmt)
    val widthFmt = stringResource(R.string.solid_generic_dim_width_fmt)
    val heightFmt = stringResource(R.string.solid_dim_height_fmt)
    val thicknessFmt = stringResource(R.string.solid_dim_thickness_fmt)
    fun semL() = if (isCylinder) diameterFmt.format(Units.meters(L).toString())
    else lengthFmt.format(Units.meters(L).toString())
    fun semH() = if (isPlate) widthFmt.format(Units.meters(H).toString())
    else heightFmt.format(Units.meters(H).toString())
    fun semT() = if (isCylinder) diameterFmt.format(Units.meters(T).toString())
    else thicknessFmt.format(Units.length(T * 1000.0).toString())
    return when (defaultUpAxis(g)) {
        "z" -> Triple(semL(), semH(), semT())   // display x=L, y=H, z=T
        "x" -> Triple(semH(), semT(), semL())   // display x=H, y=T, z=L
        else -> Triple(semL(), semT(), semH())  // display x=L, y=T, z=H
    }
}
