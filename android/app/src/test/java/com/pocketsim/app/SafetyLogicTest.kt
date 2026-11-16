package com.pocketsim.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 安全分级映射与来源标签逻辑测试（阈值与 Python 侧判定约定一致：2.0 / 1.2）。
 */
class SafetyLogicTest {

    // ------------------------------------------------------------------
    // 安全分级映射
    // ------------------------------------------------------------------

    @Test
    fun `grade 边界值`() {
        assertEquals(SafetyLogic.Grade.UNKNOWN, SafetyLogic.grade(null))
        assertEquals(SafetyLogic.Grade.UNKNOWN, SafetyLogic.grade(Double.NaN))
        assertEquals(SafetyLogic.Grade.UNKNOWN, SafetyLogic.grade(0.0))
        assertEquals(SafetyLogic.Grade.DANGER, SafetyLogic.grade(0.5))
        assertEquals(SafetyLogic.Grade.DANGER, SafetyLogic.grade(1.19))
        assertEquals(SafetyLogic.Grade.CAUTION, SafetyLogic.grade(1.2))     // 注意线下界
        assertEquals(SafetyLogic.Grade.CAUTION, SafetyLogic.grade(1.99))
        assertEquals(SafetyLogic.Grade.SAFE, SafetyLogic.grade(2.0))        // 安全线下界
        assertEquals(SafetyLogic.Grade.SAFE, SafetyLogic.grade(8.4))
    }

    @Test
    fun `gradeFromVerdict 中文结论词映射`() {
        assertEquals(SafetyLogic.Grade.SAFE, SafetyLogic.gradeFromVerdict("安全"))
        assertEquals(SafetyLogic.Grade.CAUTION, SafetyLogic.gradeFromVerdict("注意"))
        assertEquals(SafetyLogic.Grade.DANGER, SafetyLogic.gradeFromVerdict("危险"))
        assertEquals(SafetyLogic.Grade.UNKNOWN, SafetyLogic.gradeFromVerdict(null))
        assertEquals(SafetyLogic.Grade.UNKNOWN, SafetyLogic.gradeFromVerdict("其他"))
    }

    @Test
    fun `grade 与 verdict 一致性（样例指标）`() {
        // 安全系数 1.009 → 危险，与 verdict=危险 一致
        val sfGrade = SafetyLogic.grade(1.009)
        val verdictGrade = SafetyLogic.gradeFromVerdict("危险")
        assertEquals(sfGrade, verdictGrade)
    }

    // ------------------------------------------------------------------
    // 来源标签逻辑
    // ------------------------------------------------------------------

    @Test
    fun `sourceLabel 五种来源标签`() {
        assertEquals("内置", SafetyLogic.sourceLabel(ParamSource.BUILTIN))
        assertEquals("AI 提取", SafetyLogic.sourceLabel(ParamSource.AI))
        assertEquals("联网检索", SafetyLogic.sourceLabel(ParamSource.WEB))
        assertEquals("自填", SafetyLogic.sourceLabel(ParamSource.USER))
        assertEquals("离线识别", SafetyLogic.sourceLabel(ParamSource.OFFLINE))
    }

    @Test
    fun `ParamSource fromString 解析与容错`() {
        assertEquals(ParamSource.BUILTIN, ParamSource.fromString("builtin"))
        assertEquals(ParamSource.BUILTIN, ParamSource.fromString("内置"))
        assertEquals(ParamSource.AI, ParamSource.fromString("AI"))
        assertEquals(ParamSource.WEB, ParamSource.fromString("web"))
        assertEquals(ParamSource.WEB, ParamSource.fromString("联网检索"))
        assertEquals(ParamSource.USER, ParamSource.fromString("user"))
        assertEquals(ParamSource.USER, ParamSource.fromString("自填"))
        assertEquals(ParamSource.OFFLINE, ParamSource.fromString("offline"))
        assertEquals(ParamSource.OFFLINE, ParamSource.fromString("离线识别"))
        // 未知/空值容错为内置
        assertEquals(ParamSource.BUILTIN, ParamSource.fromString("whatever"))
        assertEquals(ParamSource.BUILTIN, ParamSource.fromString(null))
    }

    @Test
    fun `materialSource 材料记录来源映射`() {
        assertEquals(
            ParamSource.WEB,
            SafetyLogic.materialSource(MaterialRecord(name = "橡木", source = "web"))
        )
        assertEquals(
            ParamSource.USER,
            SafetyLogic.materialSource(MaterialRecord(name = "自定材料", source = "user"))
        )
        // 缺省 source 视为内置
        assertEquals(
            ParamSource.BUILTIN,
            SafetyLogic.materialSource(MaterialRecord(name = "松木"))
        )
    }
}
