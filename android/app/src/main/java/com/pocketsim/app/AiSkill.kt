/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import kotlinx.serialization.Serializable

/**
 * AI 技能：用户为本次建模附加的一段行为指令，告诉 AI「这次希望怎么想」。
 *
 * 内置 2 条草稿（保守校核 / 快速估算）只读；
 * 用户自定义技能可增删改，由 [SkillRepository] 持久化到 DataStore。
 */
@Serializable
data class AiSkill(
    val id: String,
    val name: String,
    /** 一句话说明适用场景，展示在首页选中技能的下方 */
    val brief: String = "",
    /** 追加给 AI 的具体行为指令 */
    val instruction: String,
    /** true = 内置只读，false = 用户自定义 */
    val builtin: Boolean = false
) {
    /**
     * 追加到建模系统提示词末尾的技能段。
     * 明确声明技能只调整取向，不覆盖 JSON 输出契约与「材料必须追问」等硬性规则。
     */
    val promptBlock: String
        get() = buildString {
            append("\n\n[本次附加技能：")
            append(name.trim())
            append("]\n")
            append(instruction.trim())
            append("\n技能只用于调整建模取向与取值策略；上文的数据契约（只输出一个 JSON）、")
            append("材料必须由用户明确给出、不得编造缺失参数等硬性规则仍然优先，不得违反。")
        }

    companion object {
        const val MAX_NAME = 20
        const val MAX_BRIEF = 60
        const val MAX_INSTRUCTION = 600

        /** 内置技能草稿 */
        val BUILT_INS: List<AiSkill> = listOf(
            AiSkill(
                id = "builtin.conservative",
                name = "保守校核",
                brief = "取最不利工况与偏保守取值，安全优先",
                instruction = """
                    以安全校核为先：
                    1. 荷载取用户给定范围的上限，截面尺寸与壁厚取下限，材料强度取偏低的代表值；
                    2. 支承方式不确定时按更不利的（铰接、滚轴、缺少侧向约束）处理，不要默认"焊死/嵌固"；
                    3. 在 model.name 里标注"保守校核"，让用户知道结论偏安全；
                    4. 不要为了结论好看而减小荷载或放大截面；关键信息缺失时仍按硬性规则追问，不得默认。
                """.trimIndent(),
                builtin = true
            ),
            AiSkill(
                id = "builtin.quick_estimate",
                name = "快速估算",
                brief = "简化模型、少杆件，先给量级结论",
                instruction = """
                    目标是快速判断量级：
                    1. 只建承受主要荷载的关键杆件，合并次要构件、省略装饰件，节点和杆件尽量少；
                    2. 简化不得改变受力路径与支承条件，不要为了少建杆件而删掉支座或斜撑；
                    3. 结论用生活化语言给出参照（如"大约能放几箱书""相当于多少公斤"），
                       并说明这是快速估算，精确结论需要细化模型；
                    4. 材料、尺寸、荷载仍必须来自用户，不得因为"先估个大概"就自行填数。
                """.trimIndent(),
                builtin = true
            )
        )

        /** 内置技能 id 集合（自定义技能不得占用） */
        val BUILT_IN_IDS: Set<String> = BUILT_INS.map { it.id }.toSet()

        /**
         * 规范化自定义技能草稿：名称与指令去空白后为空则返回 null（不可保存）；
         * id 为空时生成新 id，超长字段按上限截断，强制 builtin=false。
         */
        fun normalize(id: String, name: String, brief: String, instruction: String): AiSkill? {
            if (id in BUILT_IN_IDS) return null
            val n = name.trim().take(MAX_NAME)
            val ins = instruction.trim().take(MAX_INSTRUCTION)
            if (n.isEmpty() || ins.isEmpty()) return null
            return AiSkill(
                id = id.ifBlank { "custom-${System.currentTimeMillis()}" },
                name = n,
                brief = brief.trim().take(MAX_BRIEF),
                instruction = ins,
                builtin = false
            )
        }
    }
}
