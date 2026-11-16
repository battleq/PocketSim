/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.pocketsim.app.AiSkill
import com.pocketsim.app.AppContainer
import com.pocketsim.app.LocaleManager
import com.pocketsim.app.LocaleStore
import com.pocketsim.app.R
import com.pocketsim.app.Units
import com.pocketsim.app.ui.components.DisclaimerBanner
import com.pocketsim.app.ui.components.engineeringChipColors
import kotlinx.coroutines.launch
import java.io.File

/**
 * 工作区主页：免责声明条 + 问题输入 + 建模模式切换 + 附图 + 示例输入。
 * AI 直接按描述（可附 1-3 张结构照片）建立梁杆框架模型；
 * 默认自动判别平面/空间，也可手动切到「三维结构」强制空间建模。
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenChat: (String, Boolean, List<String>, Boolean, Boolean, Boolean, Boolean, Boolean, AiSkill?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMaterialLibrary: () -> Unit
) {
    var problemText by remember { mutableStateOf("") }
    // AI 润色：手动触发，成功后可用“撤销润色”恢复原文
    var polishing by remember { mutableStateOf(false) }
    var polishError by remember { mutableStateOf<String?>(null) }
    var beforePolish by remember { mutableStateOf<String?>(null) }
    // 通用结构下分自动 / 强制三维：自动由 AI 判别平面还是空间（P3D-5），
    // 强制三维则输出 dim=3 空间框架（P3D-3）
    var threeDim by remember { mutableStateOf(false) }
    // 三维模式下再分：空间梁元（frame dim=3）/ 三维实体 TET4（solid_wall）
    var forceSolid by remember { mutableStateOf(false) }
    // 三维实体下再分：墙体专用（solid_wall）/ 通用三维实体（solid_generic）
    var forceSolidGeneric by remember { mutableStateOf(false) }
    // 独立模式：薄壁容器（水箱/料仓），与梁元/实体并列，不依赖 threeDim
    var forceShell by remember { mutableStateOf(false) }
    // 独立模式：埋地管（覆土+车载工程公式），与梁元/实体/容器并列，不依赖 threeDim
    var forceBuriedPipe by remember { mutableStateOf(false) }
    // 示例分类：承重结构 / 温度·时间历程 / 风与渗流（每类 2-3 条，避免一屏堆十条）
    var exampleCat by remember { mutableStateOf(0) }
    // AI 技能：本次建模附加的行为指令（内置 3 条 + 自定义），默认不附加
    val customSkills by container.skillRepository.custom.collectAsState(initial = emptyList())
    var selectedSkillId by remember { mutableStateOf<String?>(null) }
    var showSkillManager by remember { mutableStateOf(false) }
    val allSkills = AiSkill.BUILT_INS + customSkills
    val selectedSkill = allSkills.firstOrNull { it.id == selectedSkillId }
    // 示例文案跟随单位制：英制用 *_imperial 数组（数值已换算为英尺/英寸/磅）
    val imperial = Units.isImperial
    val exampleCats = listOf(
        R.string.home_example_cat_structure to
            if (imperial) R.array.home_examples_structure_imperial else R.array.home_examples_structure,
        R.string.home_example_cat_thermal to
            if (imperial) R.array.home_examples_thermal_imperial else R.array.home_examples_thermal,
        R.string.home_example_cat_fluid to
            if (imperial) R.array.home_examples_fluid_imperial else R.array.home_examples_fluid,
        R.string.home_example_cat_solid to
            if (imperial) R.array.home_examples_solid_imperial else R.array.home_examples_solid,
        R.string.home_example_cat_shell to
            if (imperial) R.array.home_examples_shell_imperial else R.array.home_examples_shell,
        // 埋地管走工程公式法（不开 FEM），必须单独成类并自带模式，否则会被当成薄壁容器去要筒高和填料
        R.string.home_example_cat_buried to
            if (imperial) R.array.home_examples_buried_imperial else R.array.home_examples_buried
    )
    // 薄壁容器 / 三维实体 / 埋地管模式下，其他分类示例无法用对应求解器运行，只保留对应分类；其余模式显示全部。
    val visibleCats = when {
        forceShell -> listOf(exampleCats[4])
        forceSolidGeneric -> listOf(exampleCats[3])
        forceBuriedPipe -> listOf(exampleCats[5])
        else -> exampleCats
    }
    val currentCat = if (forceShell || forceSolidGeneric || forceBuriedPipe) 0 else exampleCat

    // 附图：base64 JPEG 数据 URI + 缩略图，随"AI 理解"一起进入对话，
    // 与文字描述互相配合形成完整输入（照片定几何/连接方式，文字补材料/荷载/支承）
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attached by remember {
        mutableStateOf<List<Pair<String, androidx.compose.ui.graphics.ImageBitmap>>>(emptyList())
    }
    var importing by remember { mutableStateOf(false) }
    // PhotoPicker：新系统原生选择器，旧系统自动回落，均无需存储权限
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(3)
    ) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            importing = true
            val room = 3 - attached.size
            attached = attached + uris.take(room).mapNotNull { compressToDataUri(context, it) }
            importing = false
        }
    }
    // 相机拍照：保存到 cacheDir/camera/，成功后压缩为数据 URI 加入附图
    var captureUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            val uri = captureUri
            if (uri != null) scope.launch {
                importing = true
                val room = 3 - attached.size
                if (room > 0) {
                    compressToDataUri(context, uri)?.let { attached = attached + it }
                }
                importing = false
            }
        }
        captureUri = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).imePadding()) {  // edge-to-edge 下让出 IME 高度
            DisclaimerBanner()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ---- 问题输入（悬浮卡片）：输入 + 润色 + 模式 + 技能 + 附图 + 发起 ----
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 卡片标题：工程标签风
                        Text(
                            text = stringResource(R.string.home_problem_label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedTextField(
                            value = problemText,
                            onValueChange = { problemText = it },
                            placeholder = { Text(stringResource(R.string.home_problem_placeholder_frame)) },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // ---- AI 润色 + 附图入口：整理口语描述 + 拍照/选图 ----
                        // FlowRow：按内容宽度排布，一行放不下时自动换行，
                        // 避免窄屏把「拍照」按钮挤扁导致图标与文字不居中（真机反馈修复）
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        polishing = true
                                        polishError = null
                                        try {
                                            val polished = container.textPolisher.polish(
                                                problemText,
                                                english = LocaleManager.currentLanguageCode(context) == LocaleStore.LANG_EN
                                            )
                                            beforePolish = problemText
                                            problemText = polished
                                        } catch (e: Exception) {
                                            polishError = e.message ?: context.getString(R.string.polish_failed_default)
                                        } finally {
                                            polishing = false
                                        }
                                    }
                                },
                                enabled = problemText.isNotBlank() && !polishing
                            ) {
                                if (polishing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(" " + stringResource(R.string.polish_action))
                            }
                            if (beforePolish != null) {
                                TextButton(
                                    onClick = {
                                        problemText = beforePolish.orEmpty()
                                        beforePolish = null
                                        polishError = null
                                    }
                                ) { Text(stringResource(R.string.polish_undo)) }
                            }
                            // 相册选图：图标 + 文字，按钮放大便于点击
                            OutlinedButton(
                                onClick = {
                                    pickImages.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                enabled = attached.size < 3 && !importing,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 10.dp, vertical = 4.dp
                                )
                            ) {
                                Icon(
                                    Icons.Filled.PhotoLibrary,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.chat_add_photo))
                            }
                            // 相机拍照：图标 + 文字
                            OutlinedButton(
                                onClick = {
                                    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                                    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
                                    val uri = FileProvider.getUriForFile(
                                        context, "${context.packageName}.fileprovider", file
                                    )
                                    captureUri = uri
                                    takePicture.launch(uri)
                                },
                                enabled = attached.size < 3 && !importing,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 10.dp, vertical = 4.dp
                                )
                            ) {
                                Icon(
                                    Icons.Filled.CameraAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.home_take_photo))
                            }
                        }
                        polishError?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        // ---- 附图缩略图：照片 + 文字构成完整输入 ----
                        if (attached.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                attached.forEachIndexed { idx, item ->
                                    Box {
                                        Image(
                                            bitmap = item.second,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        )
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(2.dp)
                                        ) {
                                            IconButton(
                                                onClick = { attached = attached.filterIndexed { i, _ -> i != idx } },
                                                modifier = Modifier.size(20.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.Close,
                                                    contentDescription = stringResource(R.string.chat_remove_photo),
                                                    modifier = Modifier.size(12.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                if (importing) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    val imgs = attached.map { it.first }
                                    attached = emptyList()
                                    onOpenChat(problemText, true, imgs, threeDim, forceSolid, forceSolidGeneric, forceShell, forceBuriedPipe, selectedSkill)
                                },
                                // 文字与照片至少有一样即可发起
                                enabled = problemText.isNotBlank() || attached.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary,
                                    contentColor = MaterialTheme.colorScheme.onTertiary
                                ),
                                modifier = Modifier.weight(1f)
                            ) { Text(stringResource(R.string.home_btn_ai)) }
                        }

                        Text(
                            text = stringResource(R.string.home_hint_ai_off),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

                        // ---- 建模参数（可选）：四个可折叠分区，标题为按钮，点击展开内容 ----
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.home_params_box_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                // 模型选择
                                CollapsibleSection(title = stringResource(R.string.home_section_model)) {
                                    // FlowRow：英文长文案（General structure · auto）在窄屏会挤占
                                    // 同行第二个 chip 的宽度，导致「3D structure」逐词竖排、错位；
                                    // 放不下时自动换行可保证每个 chip 按内容宽度排布
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        FilterChip(
                                            selected = !threeDim && !forceShell && !forceBuriedPipe,
                                            onClick = {
                                                threeDim = false
                                                forceSolid = false
                                                forceSolidGeneric = false
                                                forceShell = false
                                                forceBuriedPipe = false
                                            },
                                            colors = engineeringChipColors(),
                                            label = { Text(stringResource(R.string.home_mode_frame)) }
                                        )
                                        FilterChip(
                                            selected = threeDim && !forceShell && !forceBuriedPipe,
                                            onClick = {
                                                threeDim = true
                                                forceShell = false
                                                forceBuriedPipe = false
                                            },
                                            colors = engineeringChipColors(),
                                            label = { Text(stringResource(R.string.home_mode_frame_3d)) }
                                        )
                                        FilterChip(
                                            selected = forceShell,
                                            onClick = {
                                                forceShell = true
                                                forceBuriedPipe = false
                                                threeDim = false
                                                forceSolid = false
                                                forceSolidGeneric = false
                                            },
                                            colors = engineeringChipColors(),
                                            label = { Text(stringResource(R.string.home_mode_shell)) }
                                        )
                                        FilterChip(
                                            selected = forceBuriedPipe,
                                            onClick = {
                                                forceBuriedPipe = true
                                                threeDim = false
                                                forceSolid = false
                                                forceSolidGeneric = false
                                                forceShell = false
                                            },
                                            colors = engineeringChipColors(),
                                            label = { Text(stringResource(R.string.home_mode_buried_pipe)) }
                                        )
                                    }
                                    if (threeDim && !forceShell) {
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            FilterChip(
                                                selected = !forceSolidGeneric,
                                                onClick = {
                                                    forceSolid = false
                                                    forceSolidGeneric = false
                                                },
                                                colors = engineeringChipColors(),
                                                label = { Text(stringResource(R.string.home_mode_3d_beam)) }
                                            )
                                            FilterChip(
                                                selected = forceSolidGeneric,
                                                onClick = {
                                                    forceSolid = false
                                                    forceSolidGeneric = true
                                                },
                                                colors = engineeringChipColors(),
                                                label = { Text(stringResource(R.string.home_mode_3d_solid)) }
                                            )
                                        }
                                        Text(
                                            text = if (forceSolidGeneric) stringResource(R.string.home_mode_3d_solid_hint)
                                                   else stringResource(R.string.home_mode_3d_beam_hint),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (forceShell) {
                                        Text(
                                            text = stringResource(R.string.home_mode_shell_hint),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                // 材料定义
                                CollapsibleSection(title = stringResource(R.string.home_section_material)) {
                                    OutlinedButton(
                                        onClick = onOpenMaterialLibrary,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Filled.Science, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.home_material_entry))
                                    }
                                }

                                // AI技能
                                CollapsibleSection(title = stringResource(R.string.home_skill_header)) {
                                    val skillChips: List<@Composable () -> Unit> = buildList {
                                        add {
                                            FilterChip(
                                                selected = selectedSkillId == null,
                                                onClick = { selectedSkillId = null },
                                                colors = engineeringChipColors(),
                                                label = { Text(stringResource(R.string.home_skill_none)) }
                                            )
                                        }
                                        allSkills.forEach { skill ->
                                            add {
                                                FilterChip(
                                                    selected = selectedSkillId == skill.id,
                                                    colors = engineeringChipColors(),
                                                    onClick = {
                                                        selectedSkillId = if (selectedSkillId == skill.id) null else skill.id
                                                    },
                                                    label = { Text(skillDisplayName(skill)) }
                                                )
                                            }
                                        }
                                        add {
                                            FilterChip(
                                                selected = false,
                                                onClick = { showSkillManager = true },
                                                colors = engineeringChipColors(),
                                                label = { Text(stringResource(R.string.home_skill_custom)) }
                                            )
                                        }
                                    }
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        skillChips.forEach { it() }
                                    }
                                    selectedSkill?.brief?.takeIf { it.isNotBlank() }?.let {
                                        Text(
                                            text = skillDisplayBrief(selectedSkill),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                // 示例场景
                                CollapsibleSection(title = stringResource(R.string.home_examples_header)) {
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        visibleCats.forEachIndexed { idx, (labelRes, _) ->
                                            FilterChip(
                                                selected = currentCat == idx,
                                                onClick = { exampleCat = idx },
                                                colors = engineeringChipColors(),
                                                label = { Text(stringResource(labelRes)) }
                                            )
                                        }
                                    }
                                    val catIsSolid = visibleCats[currentCat].first == R.string.home_example_cat_solid
                                    val catIsShell = visibleCats[currentCat].first == R.string.home_example_cat_shell
                                    val catIsBuried = visibleCats[currentCat].first == R.string.home_example_cat_buried
                                    stringArrayResource(visibleCats[currentCat].second).forEach { example ->
                                        ExampleCard(
                                            text = example,
                                            onClick = {
                                                problemText = example
                                                // 点「三维实体」分类的示例时自动切到三维实体模式，
                                                // 否则会带着实体问题走梁杆提示词，AI 直接答非所问
                                                if (catIsSolid) {
                                                    threeDim = true
                                                    forceSolid = false
                                                    forceSolidGeneric = true
                                                    forceShell = false
                                                    forceBuriedPipe = false
                                                } else if (catIsShell) {
                                                    forceShell = true
                                                    threeDim = false
                                                    forceSolid = false
                                                    forceSolidGeneric = false
                                                    forceBuriedPipe = false
                                                } else if (catIsBuried) {
                                                    // 埋地管不是薄壁容器：切到工程公式法，避免被要求补筒高/填料
                                                    forceBuriedPipe = true
                                                    forceShell = false
                                                    threeDim = false
                                                    forceSolid = false
                                                    forceSolidGeneric = false
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- 自定义技能管理（弹窗）----
                if (showSkillManager) {
                    SkillManagerDialog(
                        customSkills = customSkills,
                        onDismiss = { showSkillManager = false },
                        onSave = { skill ->
                            scope.launch {
                                val saved = container.skillRepository.save(skill)
                                if (saved != null) selectedSkillId = saved.id
                            }
                        },
                        onDelete = { id ->
                            scope.launch {
                                container.skillRepository.delete(id)
                                if (selectedSkillId == id) selectedSkillId = null
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * 可折叠分区：标题为按钮，点击展开/收起内容。
 * 四个分区（模型选择 / 材料定义 / AI技能 / 示例场景）统一使用 primary 描边色。
 */
@Composable
private fun CollapsibleSection(
    title: String,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        OutlinedButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) stringResource(R.string.home_collapse) else stringResource(R.string.home_expand),
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 180f else 0f)
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 8.dp, end = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        }
    }
}

/** 示例输入卡片：点击把示例整句填入输入框，便于快速体验 */
@Composable
private fun ExampleCard(text: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.home_example_fill),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** 内置技能在界面上的本地化名称（底层 skill 仍保留中文指令供 AI 使用） */
@Composable
internal fun skillDisplayName(skill: AiSkill): String = when (skill.id) {
    "builtin.conservative" -> stringResource(R.string.home_skill_conservative_name)
    "builtin.quick_estimate" -> stringResource(R.string.home_skill_quick_name)
    else -> skill.name
}

/** 内置技能说明的本地化文案 */
@Composable
internal fun skillDisplayBrief(skill: AiSkill): String = when (skill.id) {
    "builtin.conservative" -> stringResource(R.string.home_skill_conservative_brief)
    "builtin.quick_estimate" -> stringResource(R.string.home_skill_quick_brief)
    else -> skill.brief
}
