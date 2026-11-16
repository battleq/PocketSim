package com.pocketsim.app

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 设置项：候选数量默认 1 / 范围 1..3 / 持久化；差异控制默认开（AC-1 / AC-2）。
 * 用 Robolectric 提供真实 Context，DataStore 落到应用缓存目录。
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryCandidateTest {

    private fun repo() = SettingsRepository(ApplicationProvider.getApplicationContext())

    // TR-1.1：默认值
    @Test
    fun `默认候选数1 差异控制开启`() {
        assertEquals(1, AppSettings().candidateCount)
        assertTrue(AppSettings().candidateDivergenceEnabled)
        assertFalse("高速建模默认关闭，保持当前质量", AppSettings().highSpeedMode)
        assertEquals("优化方案数量默认 1 个", 1, AppSettings().optimizeSuggestionCount)
    }

    // TR-1.2：越界 coerceIn + 持久化往返
    @Test
    fun `候选数越界被 coerce 到 1 至 3 且持久化`() = runTest {
        val r = repo()
        r.setCandidateCount(0)
        assertEquals(1, r.current().candidateCount)
        r.setCandidateCount(5)
        assertEquals(3, r.current().candidateCount)
        r.setCandidateCount(2)
        assertEquals(2, r.current().candidateCount)

        r.setCandidateDivergenceEnabled(false)
        assertFalse(r.current().candidateDivergenceEnabled)

        // 新建实例读回，验证持久化
        val r2 = repo()
        assertEquals(2, r2.current().candidateCount)
        assertFalse(r2.current().candidateDivergenceEnabled)

        r2.setCandidateDivergenceEnabled(true)
        assertTrue(r2.current().candidateDivergenceEnabled)

        // 高速建模开关持久化往返
        r2.setHighSpeedMode(true)
        assertTrue(r2.current().highSpeedMode)
        val r3 = repo()
        assertTrue("新实例应读回高速建模开启", r3.current().highSpeedMode)
        r3.setHighSpeedMode(false)
        assertFalse(r3.current().highSpeedMode)
    }
}
