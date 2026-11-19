/*
 * 提示词 Token 预算测量 harness（初步数据，纯 JVM，不发真实 API 请求）
 *
 * 目的：在真机埋点（llm_stats.jsonl）之前，用可复现的方式量化——
 *  1) 各模式 system prompt 与 FRAME_OUTPUT_SCHEMA 的输入规模；
 *  2) 典型任务矩阵（T1-T8）的单任务输入/输出 token 构成与调用次数乘数；
 *  3) 按月之暗面公开定价（kimi-k3.1-pro：输入 ¥13/MTok、输出 ¥68/MTok、
 *     缓存命中 ¥1.3/MTok）估算成本，并对「思考 token」做敏感性分析。
 *
 * 估算口径（±30%，以真机 usage 回传为准）：
 *  - 月之暗面官方文档：1 token ≈ 1.5-2 个汉字 → 汉字按 0.6 token/字；
 *  - ASCII/JSON 文本按 0.3 token/字（BPE 对结构化文本约 3-4 字符/token）。
 *  - 思考 token（reasoning）无法离线测量：按 low/high/max = 1k/3k/6k 每调用做敏感性档位。
 *
 * 运行：gradle :app:testDebugUnitTest --tests "com.pocketsim.app.PromptTokenBudgetTest" -i
 */
package com.pocketsim.app

import org.junit.Test

class PromptTokenBudgetTest {

    // ---- 定价（月之暗面公开价，2026-11，platform.kimi.com/docs/pricing/chat）----
    private val PRICE_INPUT = 13.0      // ¥/MTok 输入（缓存未命中）
    private val PRICE_OUTPUT = 68.0     // ¥/MTok 输出（含思考 token）
    private val PRICE_CACHE_HIT = 1.3   // ¥/MTok 缓存命中输入

    /** token 估算：CJK 0.6/字，其余 0.3/字 */
    private fun est(s: String): Int {
        var cjk = 0
        for (ch in s) if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3000..0x303F ||
            ch.code in 0xFF00..0xFFEF) cjk++
        return (cjk * 0.6 + (s.length - cjk) * 0.3).toInt()
    }

    /** 材料注入的代表性规模：内置常见材料名（App 实际数量以联网/自定义为准，此处取典型值） */
    private val MATERIAL_NAMES = listOf(
        "松木", "橡木", "低碳钢", "不锈钢304", "铝合金", "混凝土C20", "混凝土C30",
        "砖砌体", "红砖", "花岗岩", "PVC", "ABS", "铜", "黄铜", "玻璃", "石膏板",
        "胶合板", "OSB板", " Douglas Fir", "SPF木方", "南方松", "Q235钢", "Q345钢",
        "欧标2020铝型材", "欧标4040铝型材", "工字钢20a", "HN300×150", "角钢L50×5"
    )

    private fun withMaterials(prompt: String): String =
        prompt.replace(
            "{{AVAILABLE_MATERIALS}}",
            "当前材料库（含内置 + 联网检索 + 用户自定义）：" +
                MATERIAL_NAMES.joinToString("、") +
                "。用户提到的材料若在此列表中，直接使用该材料名；若不在，用最接近的内置材料代替并在 model.name 或 questions 里注明。"
        )

    // ---- 代表性输出（真实模型 JSON 的量级样本）----
    private val OUT_FRAME_MODEL = """
{"ready":true,"kind":"frame","questions":[],"model":{"name":"四层书架","dim":2,
"nodes":[{"id":1,"x":0,"y":0},{"id":2,"x":0,"y":1600},{"id":3,"x":900,"y":0},{"id":4,"x":900,"y":1600},
{"id":5,"x":0,"y":400},{"id":6,"x":900,"y":400},{"id":7,"x":0,"y":800},{"id":8,"x":900,"y":800},
{"id":9,"x":0,"y":1200},{"id":10,"x":900,"y":1200}],
"members":[{"id":1,"i":1,"j":2,"width_b":30,"depth_h":40,"material":"松木"},
{"id":2,"i":3,"j":4,"width_b":30,"depth_h":40,"material":"松木"},
{"id":3,"i":1,"j":3,"width_b":30,"depth_h":25,"material":"松木"},
{"id":4,"i":5,"j":6,"width_b":30,"depth_h":25,"material":"松木"},
{"id":5,"i":7,"j":8,"width_b":30,"depth_h":25,"material":"松木"},
{"id":6,"i":9,"j":10,"width_b":30,"depth_h":25,"material":"松木"},
{"id":7,"i":2,"j":4,"width_b":30,"depth_h":25,"material":"松木"}],
"supports":[{"node":1,"type":"fixed"},{"node":3,"type":"fixed"}],
"loads":[{"type":"udl","member":4,"qy":-0.8},{"type":"udl","member":5,"qy":-0.8},{"type":"udl","member":6,"qy":-0.5}]}}"""

    private val OUT_QUESTIONS = """{"ready":false,"kind":"frame","questions":["书架的框架和层板是什么材料？（实木、颗粒板、钢、铝型材…）","每层大概放多重的书？"],"model":null}"""

    private val OUT_FRAME3D = OUT_FRAME_MODEL.replace("\"dim\":2", "\"dim\":3") +
        "，节点 z 坐标与 fz 载荷（空间版体积略增）"

    private val OUT_SOLID_GENERIC = """
{"ready":true,"kind":"solid_generic","questions":[],"model":null,"solid_generic":{
"name":"铝板受压","kind":"solid_generic","geometry":{"type":"plate","length_m":0.4,"width_m":0.3,"thickness_m":0.02},
"material":"铝合金","supports":[{"type":"plane","axis":"z","at":"min","dofs":[0,1,2]}],
"loads":[{"type":"pressure","magnitude_Pa":500000,"direction":"-z"}],"mesh_level":"medium"}}"""

    private val OUT_SHELL_VESSEL = """
{"ready":true,"kind":"shell_vessel","questions":[],"model":null,"shell":{
"name":"水箱","kind":"shell_vessel","geometry":{"section":"rect","length_m":2,"width_m":1,"height_m":3,"thickness_m":0.01},
"fill":"water","fill_depth_m":3,"material":"structural_steel","mesh_level":"medium"}}"""

    private val OUT_BURIED_PIPE = """
{"ready":true,"kind":"shell_buried_pipe","questions":[],"model":null,"shell_buried_pipe":{
"name":"埋地PVC管","kind":"shell_buried_pipe","geometry":{"outer_diameter_m":0.05,"thickness_m":0.003,"cover_depth_m":2.0},
"material":"PVC"}}"""

    private val OUT_FLOW = """{"ready":true,"kind":"flow","questions":[],"model":null,"flow":{"name":"砖墙抗风","kind":"wind_wall","wall":{"height_m":2,"length_m":3,"thickness_m":0.24},"beaufort":8,"material":"砖砌体"}}"""

    private val INSTRUCTION_CORRECT =
        "你上一条输出的 JSON 格式正确，但未通过物理建模校验：支座全部为滚轴，结构是机构无法求解；" +
            "至少一个支座改为 fixed 或 pinned。请只修改报错涉及的字段，其余字段保持不变，重新输出完整 JSON 对象。"

    private data class Call(
        val inputTok: Int,       // 不含 system/schema 前缀之外的动态部分已计入
        val prefixTok: Int,      // 其中可缓存前缀（system+schema）
        val outTok: Int,         // 不含思考
    )

    /** 单任务成本：reasoningPerCall=每调用思考 token 档位；cache=是否按 5min TTL 命中缓存前缀 */
    private fun cost(calls: List<Call>, reasoningPerCall: Int, cache: Boolean): Double {
        var c = 0.0
        for ((i, k) in calls.withIndex()) {
            val hit = cache && i > 0
            val missTok = if (hit) k.inputTok - k.prefixTok else k.inputTok
            val hitTok = if (hit) k.prefixTok else 0
            c += (missTok * PRICE_INPUT + hitTok * PRICE_CACHE_HIT +
                (k.outTok + reasoningPerCall) * PRICE_OUTPUT) / 1_000_000
        }
        return c
    }

    @Test
    fun measure() {
        val schemaTok = est(FRAME_OUTPUT_SCHEMA.toString())
        val prompts = linkedMapOf(
            "FRAME_AUTO" to withMaterials(ChatSession.FRAME_AUTO_SYSTEM_PROMPT),
            "FRAME3D" to withMaterials(ChatSession.FRAME3D_SYSTEM_PROMPT),
            "SOLID_FORCE" to withMaterials(ChatSession.SOLID_FORCE_SYSTEM_PROMPT),
            "SOLID_GENERIC" to withMaterials(ChatSession.SOLID_GENERIC_SYSTEM_PROMPT),
            "SHELL_VESSEL" to withMaterials(ChatSession.SHELL_VESSEL_SYSTEM_PROMPT),
            "BURIED_PIPE" to withMaterials(ChatSession.BURIED_PIPE_SYSTEM_PROMPT),
        )

        val sb = StringBuilder()
        sb.append("\n### 表1 各通道静态输入规模（token 估算，±30%）\n\n")
        sb.append("| 通道 | system 字符 | system tok | +schema tok | 输入占比(schema) |\n|---|---|---|---|---|\n")
        for ((name, p) in prompts) {
            val t = est(p)
            sb.append("| $name | ${p.length} | $t | ${t + schemaTok} | ${(100.0 * schemaTok / (t + schemaTok)).toInt()}% |\n")
        }

        // ---- 任务矩阵：每次调用的 input/prefix/output ----
        fun call(sys: String, userTok: Int, histTok: Int, out: String): Call {
            val pre = est(sys) + schemaTok
            return Call(pre + userTok + histTok, pre, est(out))
        }
        val fa = prompts.getValue("FRAME_AUTO")
        val u60 = est("做一个90厘米宽1米6高的四层松木书架，每层放大约40公斤的书")
        val tasks = linkedMapOf<String, Pair<String, List<Call>>>(
            "T1 简单平面框架" to ("1 调用" to listOf(call(fa, u60, 0, OUT_FRAME_MODEL))),
            "T2 缺材料追问(2轮)" to ("2 调用" to listOf(
                call(fa, u60, 0, OUT_QUESTIONS),
                call(fa, est("松木的，每层大概40公斤书"), est(OUT_QUESTIONS) + u60, OUT_FRAME_MODEL),
            )),
            "T3 照片建模(1张)" to ("1 调用" to listOf(call(fa, u60 + 1000, 0, OUT_FRAME_MODEL))),
            "T3b 照片建模(3张)" to ("1 调用" to listOf(call(fa, u60 + 3000, 0, OUT_FRAME_MODEL))),
            "T4 空间框架" to ("1 调用" to listOf(call(prompts.getValue("FRAME3D"), u60, 0, OUT_FRAME3D))),
            "T5 语义重试" to ("2 调用" to listOf(
                call(fa, u60, 0, OUT_FRAME_MODEL),
                call(fa, u60 + est(INSTRUCTION_CORRECT), est(OUT_FRAME_MODEL), OUT_FRAME_MODEL),
            )),
            "T6a 通用三维实体" to ("1 调用" to listOf(call(prompts.getValue("SOLID_GENERIC"), u60, 0, OUT_SOLID_GENERIC))),
            "T6b 薄壁容器" to ("1 调用" to listOf(call(prompts.getValue("SHELL_VESSEL"), u60, 0, OUT_SHELL_VESSEL))),
            "T6c 埋地管" to ("1 调用" to listOf(call(prompts.getValue("BURIED_PIPE"), u60, 0, OUT_BURIED_PIPE))),
            "T7 修改模型(强锚定)" to ("1 调用" to listOf(
                call(fa, est("把第二层改成钢的，承重加一倍") + est(OUT_FRAME_MODEL), 0, OUT_FRAME_MODEL),
            )),
            "T8 风吹墙(flow)" to ("1 调用" to listOf(call(fa, u60, 0, OUT_FLOW))),
        )

        sb.append("\n### 表2 任务矩阵成本（¥/任务；reasoning 档位 low/high/max=1k/3k/6k tok/调用）\n\n")
        sb.append("| 任务 | 调用 | Σ输入tok | Σ前缀tok | Σ输出tok | 无缓存 low | 无缓存 high | 无缓存 max | 有缓存 high | 有缓存 max | 前缀占比 |\n")
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n")
        for ((name, pair) in tasks) {
            val calls = pair.second
            val inSum = calls.sumOf { it.inputTok }
            val preSum = calls.sumOf { it.prefixTok }
            val outSum = calls.sumOf { it.outTok }
            fun f(r: Int, cache: Boolean) = "¥%.4f".format(cost(calls, r, cache))
            sb.append("| $name | ${pair.first} | $inSum | $preSum | $outSum | " +
                "${f(1000, false)} | ${f(3000, false)} | ${f(6000, false)} | " +
                "${f(3000, true)} | ${f(6000, true)} | ${(100.0 * preSum / inSum).toInt()}% |\n")
        }

        // ---- 候选乘数与历史增长 ----
        sb.append("\n### 表3 结构乘数\n\n")
        val t1 = tasks.getValue("T1 简单平面框架").second
        for (n in intArrayOf(1, 2, 3)) {
            val calls = List(n) { t1[0] }
            sb.append("- 候选数=$n（T1，无重试）：单任务 ≈ ${calls.size} 调用，" +
                "无缓存 high ¥%.4f，有缓存 high ¥%.4f\n"
                .format(cost(calls, 3000, false), cost(calls, 3000, true)))
        }
        val t2 = tasks.getValue("T2 缺材料追问(2轮)").second
        sb.append("- T2 第 2 轮输入比第 1 轮 +${t2[1].inputTok - t2[0].inputTok} tok（历史=追问 JSON+两轮用户文本）\n")
        sb.append("- 最坏路径（候选3 × 全局3次尝试 + 失稳重试）：≈ ${1 + 2 * 3 + 1} 调用/任务\n")

        println(sb)
    }
}
