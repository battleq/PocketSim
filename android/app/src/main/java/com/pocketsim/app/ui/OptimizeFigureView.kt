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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsim.app.FrameModel
import com.pocketsim.app.ModelDiff
import com.pocketsim.app.R

/**
 * 可交互的优化效果图：单指拖动旋转（yaw/pitch）、双指捏合缩放，右上角可重置视角。
 *
 * 绘制直接复用 [OptimizeFigureRenderer.drawFrame]，与 PNG 导出共用同一套代码，
 * 因此导出图与当前视角完全一致（所见即所得）。
 */
@Composable
fun OptimizeFigureView(
    model: FrameModel,
    diff: ModelDiff,
    labels: FigureLabels,
    view: View3D,
    onViewChange: (View3D) -> Unit,
    modifier: Modifier = Modifier
) {
    // 手势 lambda 只在首次组合时创建，用 rememberUpdatedState 读到最新视角/回调
    val currentView by rememberUpdatedState(view)
    val onChange by rememberUpdatedState(onViewChange)

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val v = currentView
                        onChange(
                            v.copy(
                                yawDeg = v.yawDeg + pan.x * 0.35,
                                pitchDeg = (v.pitchDeg + pan.y * 0.35).coerceIn(-85.0, 85.0),
                                zoom = (v.zoom * zoom).coerceIn(0.4f, 4f)
                            )
                        )
                    }
                }
        ) {
            OptimizeFigureRenderer.drawFrame(
                c = drawContext.canvas.nativeCanvas,
                model = model,
                diff = diff,
                labels = labels,
                widthPx = size.width,
                heightPx = size.height,
                view = currentView
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { onChange(View3D()) }) {
                Text(
                    text = stringResource(R.string.optimize_reset_view),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
