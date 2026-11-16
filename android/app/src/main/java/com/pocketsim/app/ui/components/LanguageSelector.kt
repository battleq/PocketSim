/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pocketsim.app.LocaleStore
import com.pocketsim.app.R

/**
 * 语言选择器：系统跟随 / 中文 / 英文。
 * 切换后写入 [LocaleStore] 并重建 Activity 以立即生效。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LanguageSelector(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val store = LocaleStore(context)
    val current = store.language

    val options = listOf(
        LocaleStore.LANG_SYSTEM to stringResource(R.string.lang_system),
        LocaleStore.LANG_ZH to stringResource(R.string.lang_zh),
        LocaleStore.LANG_EN to stringResource(R.string.lang_en)
    )

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = current == value,
                onClick = {
                    if (current != value) {
                        store.language = value
                        // 切换语言后重建 Activity，attachBaseContext 会重新应用新 Locale
                        activity?.recreate()
                    }
                },
                label = { Text(label) }
            )
        }
    }
}
