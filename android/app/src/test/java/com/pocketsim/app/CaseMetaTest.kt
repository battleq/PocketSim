/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-3 案例埋点（规则 LOG-01）：case_meta 由 ChatSession 在全部建模就绪路径注入（仅调试包），
 * 且 JSON 键名必须为 _case_meta —— Python bridge 按该键弹出用户上下文写入 case_log.jsonl；
 * 历史上 Kotlin 序列化为 case_meta、Python 弹出 _case_meta，键名不一致导致上下文静默丢失。
 */
class CaseMetaTest {

    private val readyFrame = """{"ready":true,"questions":[],"model":{"name":"悬臂搁板",
        "nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":400,"y":0}],
        "members":[{"id":1,"i":1,"j":2,"width_b":40,"depth_h":18,"material":"松木"}],
        "supports":[{"node":1,"type":"fixed"}],"loads":[{"type":"force","node":2,"fx":0,"fy":-294}]}}"""

    private val readyFlow = """{"ready":true,"questions":[],"model":null,"kind":"flow",
        "flow":{"name":"砖墙","kind":"wind_wall",
        "wall":{"height_m":2.5,"length_m":4.0,"thickness_m":0.24},
        "beaufort":10,"material":"砖砌体"}}"""

    @Test
    fun `FrameModel 序列化键名为 _case_meta 与 bridge 契约一致`() {
        val model = FrameModel(case_meta = CaseMeta("sess-1", "用户原话", "text"))
        val json = AppJson.encodeToString(FrameModel.serializer(), model)
        assertTrue("序列化必须使用 _case_meta 键", json.contains("\"_case_meta\""))
        assertFalse("不得输出旧键名 case_meta（无前导下划线）", json.contains("\"case_meta\""))
        val back = AppJson.decodeFromString(FrameModel.serializer(), json)
        assertEquals("sess-1", back.case_meta?.session_id)
        assertEquals("用户原话", back.case_meta?.user_text)
    }

    @Test
    fun `单模型 frame 就绪时调试包注入 case_meta`() = runTest {
        var ready: FrameModel? = null
        val client = FakeAiClient(listOf(readyFrame))
        val session = ChatSession(
            aiClientProvider = { client }, greeting = "你好",
            caseLogEnabled = true, onFrameReady = { ready = it }
        )
        session.send("帮我建模一个悬臂搁板挂30kg")
        val meta = ready?.case_meta
        assertNotNull("调试包应注入 case_meta", meta)
        assertEquals("帮我建模一个悬臂搁板挂30kg", meta!!.user_text)
        assertEquals("text", meta.input_form)
        assertTrue("会话 ID 非空", meta.session_id.isNotBlank())
    }

    @Test
    fun `单模型 frame 就绪时正式包不注入 case_meta`() = runTest {
        var ready: FrameModel? = null
        val client = FakeAiClient(listOf(readyFrame))
        val session = ChatSession(
            aiClientProvider = { client }, greeting = "你好",
            caseLogEnabled = false, onFrameReady = { ready = it }
        )
        session.send("建模")
        assertNull("正式包不得注入 case_meta", ready?.case_meta)
    }

    @Test
    fun `flow 就绪路径注入 case_meta`() = runTest {
        var flow: FlowModel? = null
        val client = FakeAiClient(listOf(readyFlow))
        val session = ChatSession(
            aiClientProvider = { client }, greeting = "你好",
            caseLogEnabled = true, onFlowReady = { flow = it }
        )
        session.send("10级风会不会把砖墙吹倒")
        assertEquals("10级风会不会把砖墙吹倒", flow?.case_meta?.user_text)
    }

    @Test
    fun `多候选路径每个候选模型都注入 case_meta`() = runTest {
        var got: List<ModelCandidate>? = null
        val client = FakeAiClient(List(3) { readyFrame })
        val session = ChatSession(
            aiClientProvider = { client }, greeting = "你好",
            caseLogEnabled = true,
            candidateCountProvider = { 3 },
            onCandidatesReady = { got = it }
        )
        session.send("建模")
        assertEquals(3, got!!.size)
        assertTrue("每个候选模型都应携带 case_meta",
            got!!.all { ((it as ModelCandidate.Frame).model.case_meta) != null })
    }

    @Test
    fun `修改模型锚定块不把 _case_meta 泄露进提示词`() {
        val anchor = FrameModel(name = "锚定", case_meta = CaseMeta("s", "泄露测试", "text"))
        val session = ChatSession(
            aiClientProvider = { FakeAiClient(listOf(readyFrame)) }, greeting = "你好",
            regenAnchor = anchor
        )
        val prompt = session.buildSystemPrompt()
        assertFalse("锚定 JSON 不应包含 _case_meta", prompt.contains("_case_meta"))
        assertFalse("锚定 JSON 不应包含用户原话", prompt.contains("泄露测试"))
    }
}
