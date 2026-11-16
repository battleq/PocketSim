/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketsim.app.MeshInfo
import com.pocketsim.app.R

/**
 * 网格自动调整提示条：
 *  - adjusted == "downgraded"：黄底，"已自动降级网格"（超时/低内存自动降粗）
 *  - adjusted == "refined"  ：蓝底，"已自动加密网格"（收敛自检差异 >10% 自动加密）
 */
@Composable
fun DowngradeNotice(mesh: MeshInfo, modifier: Modifier = Modifier) {
    val adjusted = mesh.adjusted ?: return
    val isDowngrade = adjusted == "downgraded"

    val bg = if (isDowngrade) Color(0xFFFFF8E1) else Color(0xFFE3F2FD)
    val fg = if (isDowngrade) Color(0xFF7A5900) else Color(0xFF0D47A1)
    val iconTint = if (isDowngrade) Color(0xFFB26A00) else Color(0xFF1565C0)

    val titleRes = if (isDowngrade) R.string.notice_downgraded else R.string.notice_refined
    val desc = if (isDowngrade) stringResource(R.string.notice_downgraded_desc) else ""

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        color = bg,
        contentColor = fg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(imageVector = Icons.Filled.Warning, contentDescription = null, tint = iconTint)
            Column {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                mesh.adjusted_reason?.let {
                    Text(
                        text = stringResource(R.string.notice_reason_fmt, it),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (desc.isNotEmpty()) {
                    Text(text = desc, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
