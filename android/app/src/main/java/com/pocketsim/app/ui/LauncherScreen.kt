/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.AppContainer
import com.pocketsim.app.FrameModel
import com.pocketsim.app.FrameResult
import com.pocketsim.app.R
import com.pocketsim.app.BuriedPipeResult
import com.pocketsim.app.SavedProject
import com.pocketsim.app.ShellBuriedPipeModel
import com.pocketsim.app.ShellVesselModel
import com.pocketsim.app.SolidGenericModel
import com.pocketsim.app.SolidGenericResult
import com.pocketsim.app.Units
import com.pocketsim.app.ui.components.LanguageSelector
import com.pocketsim.app.ui.components.UnitSelector
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 启动首页：App 能力说明 + 新建项目 / 打开老项目 两个入口。
 * 新建项目进入工作区（原 HomeScreen）；打开老项目展示已保存的仿真列表，点击直接回放结果页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    container: AppContainer,
    onNewProject: () -> Unit,
    onOpenFrameProject: (FrameModel, FrameResult) -> Unit,
    onOpenSolidProject: (SolidGenericModel, SolidGenericResult) -> Unit = { _, _ -> },
    onOpenShellProject: (ShellVesselModel, SolidGenericResult) -> Unit = { _, _ -> },
    onOpenBuriedPipeProject: (ShellBuriedPipeModel, BuriedPipeResult) -> Unit = { _, _ -> }
) {
    var projects by remember { mutableStateOf<List<SavedProject>>(emptyList()) }
    var showProjects by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // 展示可回放的项目：框架模型 + 通用三维实体（场景模板链路已移除，老场景项目不再回放）
        container.projectRepository.projects.collectLatest { all ->
            projects = all.filter { it.kind == "frame" || it.kind == "solid_generic" || it.kind == "shell_vessel" || it.kind == "shell_buried_pipe" }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            // App 图标（品牌图，居中显示）
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(132.dp)
            )

            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.app_name),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.launcher_subtitle),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            // 能力说明卡：力学 + 温度 + 流体
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    FeatureRow(
                        icon = Icons.Default.Science,
                        title = stringResource(R.string.launcher_mech_title),
                        desc = stringResource(R.string.launcher_mech_desc),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(14.dp))
                    FeatureRow(
                        icon = Icons.Default.Thermostat,
                        title = stringResource(R.string.launcher_thermal_title),
                        desc = stringResource(R.string.launcher_thermal_desc),
                        tint = Color(0xFFE65100)
                    )
                    Spacer(Modifier.height(14.dp))
                    FeatureRow(
                        icon = Icons.Default.Air,
                        title = stringResource(R.string.launcher_fluid_title),
                        desc = stringResource(R.string.launcher_fluid_desc),
                        tint = Color(0xFF0277BD)
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // 新建项目
            Button(
                onClick = onNewProject,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.launcher_new_project), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(14.dp))

            // 打开老项目
            OutlinedButton(
                onClick = { showProjects = !showProjects },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(Modifier.size(10.dp))
                Text(
                    if (showProjects) stringResource(R.string.launcher_hide_projects)
                    else stringResource(R.string.launcher_open_project),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(16.dp))

            // 语言选择器
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.launcher_language_label),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(12.dp))
                LanguageSelector()
            }

            Spacer(Modifier.height(10.dp))

            // 单位制选择器
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.launcher_units_label),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(12.dp))
                UnitSelector()
            }

            Spacer(Modifier.height(16.dp))

            // 项目列表
            if (showProjects) {
                if (projects.isEmpty()) {
                    Text(
                        stringResource(R.string.launcher_no_projects),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        projects.forEach { p ->
                            SavedProjectCard(
                                project = p,
                                onClick = {
                                    try {
                                        when (p.kind) {
                                            "solid_generic" -> {
                                                val model = com.pocketsim.app.AppJson.decodeFromString(
                                                    SolidGenericModel.serializer(), p.modelJson
                                                )
                                                val result = com.pocketsim.app.AppJson.decodeFromString(
                                                    SolidGenericResult.serializer(), p.resultJson
                                                )
                                                onOpenSolidProject(model, result)
                                            }
                                            "shell_vessel" -> {
                                                val model = com.pocketsim.app.AppJson.decodeFromString(
                                                    ShellVesselModel.serializer(), p.modelJson
                                                )
                                                val result = com.pocketsim.app.AppJson.decodeFromString(
                                                    SolidGenericResult.serializer(), p.resultJson
                                                )
                                                onOpenShellProject(model, result)
                                            }
                                            "shell_buried_pipe" -> {
                                                val model = com.pocketsim.app.AppJson.decodeFromString(
                                                    ShellBuriedPipeModel.serializer(), p.modelJson
                                                )
                                                val result = com.pocketsim.app.AppJson.decodeFromString(
                                                    BuriedPipeResult.serializer(), p.resultJson
                                                )
                                                onOpenBuriedPipeProject(model, result)
                                            }
                                            else -> {
                                                val model = com.pocketsim.app.AppJson.decodeFromString(FrameModel.serializer(), p.modelJson)
                                                val result = com.pocketsim.app.AppJson.decodeFromString(FrameResult.serializer(), p.resultJson)
                                                onOpenFrameProject(model, result)
                                            }
                                        }
                                    } catch (_: Exception) { /* 损坏条目忽略 */ }
                                },
                                onDelete = {
                                    scope.launch { container.projectRepository.delete(p.id) }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.disclaimer_short),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 启动页能力说明条目：圆形图标 + 标题 + 描述 */
@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    desc: String,
    tint: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SavedProjectCard(
    project: SavedProject,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val verdictColor = when (project.verdict) {
        "危险" -> Color(0xFFC62828)
        "注意" -> Color(0xFFF57C00)
        else -> Color(0xFF2E7D32)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    project.name.ifBlank { stringResource(R.string.launcher_unnamed) },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        verdictLabel(project.verdict),
                        color = verdictColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        stringResource(R.string.launcher_project_stress_fmt, Units.stress(project.maxStressMPa).toString(), "%.2f".format(project.safetyFactor)),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            androidx.compose.material3.IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.launcher_delete))
            }
        }
    }
}
