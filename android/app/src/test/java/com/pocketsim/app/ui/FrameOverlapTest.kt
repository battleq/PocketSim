/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 */

package com.pocketsim.app.ui

import com.pocketsim.app.FramePolyline
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 变形图重复杆件合并：重叠判定（同线、错开几毫米、部分重叠） */
class FrameOverlapTest {

    private fun pl(vararg pts: Pair<Double, Double>) = FramePolyline(
        member = 1,
        pts = pts.map { listOf(it.first, it.second, 0.0, 0.0) }
    )

    @Test
    fun 同一直线且大部分重合返回真() {
        val shelf = pl(0.0 to 1500.0, 800.0 to 1500.0)
        val brace = pl(30.0 to 1502.0, 770.0 to 1502.0)
        assertTrue(overlapsGeometrically(shelf, brace))
    }

    @Test
    fun 平行但错开较远返回假() {
        val shelf = pl(0.0 to 1500.0, 800.0 to 1500.0)
        val other = pl(0.0 to 1400.0, 800.0 to 1400.0)
        assertFalse(overlapsGeometrically(shelf, other))
    }

    @Test
    fun 相交但不共线返回假() {
        val shelf = pl(0.0 to 1500.0, 800.0 to 1500.0)
        val post = pl(0.0 to 0.0, 0.0 to 1500.0)
        assertFalse(overlapsGeometrically(shelf, post))
    }

    @Test
    fun 仅端点相接不算重叠() {
        val left = pl(0.0 to 1500.0, 400.0 to 1500.0)
        val right = pl(400.0 to 1500.0, 800.0 to 1500.0)
        assertFalse(overlapsGeometrically(left, right))
    }
}
