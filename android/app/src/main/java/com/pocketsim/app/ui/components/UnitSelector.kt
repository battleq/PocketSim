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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pocketsim.app.LocaleStore
import com.pocketsim.app.R
import com.pocketsim.app.Units

/**
 * 单位制选择器：公制（mm / N / MPa） / 英制（in / lb / psi）。
 * 切换后写入 [LocaleStore] 并重建 Activity，使整页输出按新单位制重新格式化。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UnitSelector(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val store = LocaleStore(context)
    // 未手动设置时展示默认（跟随系统 locale）对应的档位
    val current = store.rawUnitSystem ?: when (Units.defaultSystem(context)) {
        com.pocketsim.app.UnitSystem.IMPERIAL -> LocaleStore.UNIT_IMPERIAL
        else -> LocaleStore.UNIT_METRIC
    }

    val options = listOf(
        LocaleStore.UNIT_METRIC to stringResource(R.string.unit_system_metric),
        LocaleStore.UNIT_IMPERIAL to stringResource(R.string.unit_system_imperial)
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
                        Units.select(context, value)
                        activity?.recreate()
                    }
                },
                label = { Text(label) }
            )
        }
    }
}
