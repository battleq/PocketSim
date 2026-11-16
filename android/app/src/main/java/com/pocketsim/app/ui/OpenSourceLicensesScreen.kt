/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */
package com.pocketsim.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.OpenSourceLicenses
import com.pocketsim.app.R

/**
 * 开源许可声明页 — LGPL 合规必备。
 *
 * 透明披露闭源 App 在运行时动态链接的所有开源组件及其 License，
 * 尤其是 copyleft（LGPL）组件。每个组件可查看 assets/licenses/ 下的许可证全文，
 * 满足 BSD/MIT/Apache/LGPL 的再分发声明义务。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    // 当前查看的许可证：组件名 + assets 路径
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.oss_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- PocketSim 自有代码声明 ----------
            item {
                Text(
                    text = stringResource(R.string.oss_self),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(OpenSourceLicenses.selfLicenses) { (path, lic) ->
                DepRow(name = path, license = lic, isCopyleft = false, scope = "", note = "")
            }

            // ---------- 第三方依赖 ----------
            item {
                Text(
                    text = stringResource(R.string.oss_third),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.oss_copyleft_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            items(OpenSourceLicenses.all) { dep ->
                DepRow(
                    name = dep.name,
                    version = dep.version,
                    license = dep.license,
                    isCopyleft = dep.isCopyleft,
                    scope = dep.scope,
                    note = dep.note,
                    onViewLicense = dep.licenseAsset?.let { asset ->
                        { viewing = dep.name to asset }
                    }
                )
            }
        }
    }

    // 许可证全文弹窗（滚动阅读，等宽字体便于对照条款）
    viewing?.let { (name, asset) ->
        val context = LocalContext.current
        val licenseText = remember(asset) { readLicenseAsset(context, asset) }
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(name) },
            text = {
                Text(
                    text = licenseText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { viewing = null }) {
                    Text(stringResource(R.string.skill_close))
                }
            }
        )
    }
}

/** 从 assets 读取许可证全文；文件缺失时给出可诊断提示而非崩溃。 */
private fun readLicenseAsset(context: Context, path: String): String =
    runCatching {
        context.assets.open(path).bufferedReader().use { it.readText() }
    }.getOrElse { "License text not found: $path" }

@Composable
private fun DepRow(
    name: String,
    version: String = "",
    license: String,
    isCopyleft: Boolean,
    scope: String = "",
    note: String = "",
    onViewLicense: (() -> Unit)? = null
) {
    val bg = if (isCopyleft) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.38f)
             else MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (version.isNotEmpty()) "$name · $version" else name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = license + if (isCopyleft) " ⚠" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (isCopyleft) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (scope.isNotEmpty()) {
            Text(
                text = scope,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (note.isNotEmpty()) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onViewLicense != null) {
            TextButton(
                onClick = onViewLicense,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(stringResource(R.string.oss_view_license), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
