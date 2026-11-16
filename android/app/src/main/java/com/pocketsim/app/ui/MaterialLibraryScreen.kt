/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.AppContainer
import com.pocketsim.app.LocaleManager
import com.pocketsim.app.LocaleStore
import com.pocketsim.app.MaterialRecord
import com.pocketsim.app.Quantity
import com.pocketsim.app.R
import com.pocketsim.app.Units
import kotlinx.coroutines.launch

/**
 * 材料库页面：
 * - 列出所有材料（内置 / 联网 / 自定义）及其有限元参数
 * - 联网检索材料参数（AI 联网能力）
 * - 手动添加自定义材料（E / ν / 密度 / 强度）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialLibraryScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val materials = remember { mutableStateListOf<MaterialRecord>() }
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    // 待确认删除的用户材料（联网检索 / 手动添加）
    var pendingDelete by remember { mutableStateOf<MaterialRecord?>(null) }

    // 自定义材料表单
    var customName by remember { mutableStateOf("") }
    var customCategory by remember { mutableStateOf("") }
    var customE by remember { mutableStateOf("") }
    var customNu by remember { mutableStateOf("") }
    var customDensity by remember { mutableStateOf("") }
    var customStrength by remember { mutableStateOf("") }
    var customStrengthType by remember { mutableStateOf("") }
    var customNotes by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 当前语言对应的材料体系：英文 → 美标（us），其余 → 国标（gb）
    val materialSystem = if (LocaleManager.currentLanguageCode(context) == LocaleStore.LANG_EN) "us" else "gb"
    val englishUi = LocaleManager.currentLanguageCode(context) == LocaleStore.LANG_EN

    // 预取字符串资源（lambda/协程内不能调用 stringResource）
    val savedLabel = stringResource(R.string.material_saved)
    val failFmt = stringResource(R.string.material_search_fail)
    val nameRequiredMsg = stringResource(R.string.material_name_required)
    val eRequiredMsg = stringResource(R.string.material_e_required)
    val nuRequiredMsg = stringResource(R.string.material_nu_required)
    val categoryDefault = stringResource(R.string.material_category_custom)
    val strengthTypeDefault = stringResource(R.string.material_strength_unknown)
    val deletedFmt = stringResource(R.string.material_deleted)

    suspend fun refresh() {
        loading = true
        try {
            materials.clear()
            materials.addAll(container.engine.listMaterials(materialSystem))
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.material_title), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.material_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- 联网检索 ----
            Card(
                shape = RoundedCornerShape(4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.material_search_web),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        stringResource(R.string.material_lookup_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.material_search_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    FilledTonalButton(
                        onClick = {
                            val q = searchQuery.trim()
                            if (q.isBlank()) return@FilledTonalButton
                            searching = true
                            statusMsg = null
                            scope.launch {
                                try {
                                    val rec = container.materialLookup.lookup(q, englishUi)
                                    container.engine.upsertMaterial(rec)
                                    statusMsg = "$savedLabel：${rec.name}（E=${Units.stress(rec.eMPa)}，ν=${rec.nu}）"
                                    refresh()
                                } catch (e: Exception) {
                                    statusMsg = failFmt.format(e.message ?: context.getString(R.string.error_unknown_default))
                                } finally {
                                    searching = false
                                }
                            }
                        },
                        enabled = !searching && searchQuery.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (searching) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(16.dp).height(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (searching) stringResource(R.string.material_searching) else stringResource(R.string.material_search_btn))
                    }
                    statusMsg?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // ---- 手动添加自定义材料 ----
            Card(
                shape = RoundedCornerShape(4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.material_add_custom),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        stringResource(R.string.material_manual_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = customName, onValueChange = { customName = it },
                        label = { Text(stringResource(R.string.material_name)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customCategory, onValueChange = { customCategory = it },
                        label = { Text(stringResource(R.string.material_category)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = customE, onValueChange = { customE = it },
                            label = { Text(stringResource(R.string.material_e_fmt, Units.stress(1.0).unit)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = customNu, onValueChange = { customNu = it },
                            label = { Text(stringResource(R.string.material_nu)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = customDensity, onValueChange = { customDensity = it },
                            label = { Text(stringResource(R.string.material_density_fmt, Units.density(1.0).unit)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = customStrength, onValueChange = { customStrength = it },
                            label = { Text(stringResource(R.string.material_strength_fmt, Units.stress(1.0).unit)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = customStrengthType, onValueChange = { customStrengthType = it },
                        label = { Text(stringResource(R.string.material_strength_type)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customNotes, onValueChange = { customNotes = it },
                        label = { Text(stringResource(R.string.material_notes)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            val name = customName.trim()
                            val e = customE.trim().toDoubleOrNull()
                            val nu = customNu.trim().toDoubleOrNull()
                            if (name.isBlank()) { statusMsg = nameRequiredMsg; return@OutlinedButton }
                            if (e == null) { statusMsg = eRequiredMsg; return@OutlinedButton }
                            if (nu == null) { statusMsg = nuRequiredMsg; return@OutlinedButton }
                            val rec = MaterialRecord(
                                id = name,
                                name = name,
                                category = customCategory.trim().ifBlank { categoryDefault },
                                eMPa = Units.toMPa(e),
                                nu = nu,
                                density_kg_m3 = customDensity.trim().toDoubleOrNull()?.let { Units.toKgPerM3(it) },
                                strength_MPa = customStrength.trim().toDoubleOrNull()?.let { Units.toMPa(it) },
                                strength_type = customStrengthType.trim().ifBlank { strengthTypeDefault },
                                notes = customNotes.trim(),
                                source = "user"
                            )
                            scope.launch {
                                container.engine.upsertMaterial(rec)
                                statusMsg = "$savedLabel：$name"
                                refresh()
                                customName = ""; customCategory = ""; customE = ""
                                customNu = ""; customDensity = ""; customStrength = ""
                                customStrengthType = ""; customNotes = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.material_save))
                    }
                    statusMsg?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // ---- 材料列表 ----
            Text(
                stringResource(R.string.material_all_fmt, materials.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                materials.sortedByDescending { it.source }.forEach { mat ->
                    MaterialCard(
                        m = mat,
                        onDelete = if (mat.deletable) ({ pendingDelete = mat }) else null
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // 删除确认：仅联网检索 / 手动添加的用户材料可删，内置材料不显示删除入口
    val deleting = pendingDelete
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.material_delete_title)) },
            text = { Text(stringResource(R.string.material_delete_confirm, deleting.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            val ok = runCatching { container.engine.deleteMaterial(deleting.id) }
                                .getOrDefault(false)
                            if (ok) {
                                statusMsg = deletedFmt.format(deleting.name)
                                refresh()
                            }
                        }
                    }
                ) { Text(stringResource(R.string.material_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.skill_close))
                }
            }
        )
    }
}

@Composable
private fun MaterialCard(m: MaterialRecord, onDelete: (() -> Unit)? = null) {
    val sourceLabel = when (m.source) {
        "builtin" -> stringResource(R.string.material_source_builtin)
        "web" -> stringResource(R.string.material_source_web)
        else -> stringResource(R.string.material_source_user)
    }
    val sourceColor = when (m.source) {
        "builtin" -> MaterialTheme.colorScheme.onSurfaceVariant
        "web" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    Card(
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(m.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(sourceColor.copy(alpha = 0.15f), RoundedCornerShape(2.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(sourceLabel, style = MaterialTheme.typography.labelSmall, color = sourceColor, fontSize = 10.sp)
                }
                if (m.category.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(m.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onDelete != null) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.material_delete),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ParamLine("E", Units.stress(m.eMPa))
                ParamLine("ν", Quantity(m.nu, ""))
                m.density_kg_m3?.let { ParamLine("ρ", Units.density(it)) }
            }
            m.strength_MPa?.takeIf { it > 0.0 }?.let { s ->
                Text(
                    stringResource(R.string.material_strength_value_fmt, m.strength_type, Units.stress(s).toString()),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            m.notes.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ParamLine(label: String, q: Quantity) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(
            q.text,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        if (q.unit.isNotBlank()) {
            Spacer(Modifier.width(3.dp))
            Text(q.unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}
