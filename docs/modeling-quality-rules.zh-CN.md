# PocketSim 建模质量规则（Modeling Quality Rules）

> 版本：v4（2026-11-20，随「优化方案体系级改造」轮更新，新增 O 类与 §四·六）｜上一版：v3（2026-11-19，新增 P 类与 §7）｜适用范围：AI 建模全链路（ChatSession、
> FrameFollowUpSession、模板流 ParamForm/run_scenario）与全部求解入口（frame/frame3d/flow/
> seepage/transient/solid/solid_generic/shell_vessel/shell_buried_pipe）。
> 本文是规则的唯一现行版本；历史 .doc 仅存档，不再维护。

## 一、六条总原则

1. **AI 只描述物理意图，推导一律交给确定性代码**（DIM-01）。
   用户/AI 提供源字段（风速或风级、受风面积、质量、埋深……），力、压强、等效载荷
   由代码按公式换算。禁止让 LLM 自行算出"等效集中力"再填入模型——LLM 数值换算
   不可靠，且错了无从追溯。
2. **校验失败必须可行动**：每条硬校验的报错信息携带实际值、合法范围与修改指引
   （"确定性修改单"），可直接作为重试提示词喂回 AI，指导其只改报错字段。
3. **宁可 fail-fast，不做静默猜测**：量级来源冲突（如 pressure 同时填 magnitude 与
   beaufort）硬拒绝并列出冲突项，而不是按优先级悄悄选一个。
4. **防线覆盖所有路径，不留旁路**：单模型、多候选、修改模型、模板流都必须经过
   同一套校验/预检/埋点（STB-01、LOG-01）。任何新增入口默认继承全部防线。
5. **重试与修正必须有预算**：分级重试各有上限，且叠加后受全局尝试上限兜底
   （RTR-01），防止失控循环消耗 LLM 调用。
6. **质量必须可观测**：每次求解的输入、结果、错误分类写入案例埋点（LOG-01），
   线上失败案例可回溯、可归类，驱动规则迭代。规则本身也要有编号与强度分级。

## 二、七层防线

| # | 防线 | 职责 | 实现位置 |
|---|------|------|----------|
| 1 | 提示词工程 | 建模约定、单位契约（frame 用 mm、solid/shell 用 m）、机构/近机构规避、wind 等源字段用法 | `ChatSession`、`FrameFollowUpSession` system prompt |
| 2 | 解析结构校验 | JSON 可解析、输出契约（ready/kind/model）、schema 严格解码、模式 kind 匹配 | `FRAME_OUTPUT_SCHEMA`、`AiParser.parseFrame`、`checkRequiredKind` |
| 3 | 物理语义校验 | 带编号的几何/载荷/约束/材料规则（§四），抛 `AiSemanticException`/`AiParseException` | `AiParser.validate*`、Python `pocketsim.*` 校验 |
| 4 | 分级重试 | 结构错误重试 1 次（给完整格式示例）；语义错误按修改单重试 ≤2 次；全局 ≤3 次 | `AiParser.parseFrameWithRetry` |
| 5 | 稳定性预检 | 几何不变性（机构/近机构）在出候选前判定：多候选剔除、单模型换 seed 重试 | `ChaquopyEngine.checkFrameStability`、`ChatSession.send`/候选过滤 |
| 6 | 求解层兜底与后处理奇异处理 | 数值有效性（NaN/Inf）、位移量级、合力+合力矩平衡校核（EQ-01）、应力奇异点排除读数 | `solid.py`、`solver.py`、`frame.py`（失稳提示）、`flow/seepage.py`（奇点限幅） |
| 7 | 案例埋点闭环 | 全入口写 case_log（含 `_case_meta` 会话上下文与错误分类），驱动规则迭代 | `bridge.py`、`case_log.py`、`PythonBridge.kt`、`Models.kt` |

防线顺序即执行顺序：前一层拦不住的，后一层必须拦住；任何一层放行都必须留下证据（日志/埋点）。

## 三、规则书写格式（四要素 + 强度分级）

每条规则按四要素书写：

- **Condition（触发条件）**：什么输入/状态触发本规则；
- **Action（处置动作）**：拒绝、警告、换算、剔除、重试……；
- **Criterion（判据）**：可执行的数值边界或逻辑判据（禁止"合理""适当"等模糊词）；
- **Evidence（证据要求）**：报错/日志必须携带的信息（实际值、合法范围、定位标签）。

强度分级：

- **M（Must）**：硬拒绝，阻断求解，进入重试修改单；
- **S（Should）**：警告或降级处理，不阻断，但必须在结果/日志中明示；
- **I（Info）**：仅记录观测，不改变行为。

## 四、规则目录

### G 类：几何

| 编号 | 强度 | Condition | Action / Criterion / Evidence |
|------|------|-----------|-------------------------------|
| G-01 | M | 实体/埋地管几何退化：任一尺寸 ≤0 或 <0.1 mm，或各向尺寸比超限、壁厚≥外径 | 拒绝。判据：`minDim ≥ 1e-4 m`；长厚比 ≤ 上限；`t < D`。Evidence：给出最大/最小实际尺寸（4 位有效数字）与单位提示 |
| G-02 | M | 网格档位/几何类型/显式分段非法（非 coarse/medium/fine/ultra，nx 非正整数） | 拒绝并列出合法枚举值 |
| G-03 | M | 挖孔非法（axis 非 x/y/z、radius≤0、center 越界、孔超过截面半宽一半、挖空整个构件）；圆柱暂不支持开孔；CG 迭代未收敛 | 拒绝。收敛失败 Evidence：建议改粗网格/简化几何/增大约束 |
| G-04 | M | 尺寸数量级异常（疑似 m/mm 单位混淆）或超出物理范围（墙高 0.2~30 m 等） | 拒绝。Evidence：指出"注意单位是米/毫米"并给出该字段合法区间 |

### L 类：载荷与约束

| 编号 | 强度 | Condition | Action / Criterion / Evidence |
|------|------|-----------|-------------------------------|
| L-01 | M | 约束点/加载面/温度杆件不在实体边界或模型内 | 拒绝。Evidence：给出你填的坐标与实体实际范围 |
| L-02 | M | 加载面退化为线或点（受力面积为 0） | 拒绝，提示检查几何或更换加载面 |
| L-03 | M | 载荷数值越界或量级来源冲突：pressure 五选一（magnitude/wind_speed_m_s/beaufort/mass_kg/depth_m）、force 二选一（vector 或 mass_kg+direction）、wind 二选一（wind_speed_m_s/beaufort）；零向量力、非单位方向向量、风速/风级/温度/时间步数越界 | 拒绝并列出冲突来源或合法区间。方向向量判据：模长 1.0±0.001 |
| L-04 | M | `fluid`（静水/土压力分布）缺 `depth_m` | 拒绝：压强换算必须有深度 |
| L-05 | M | 约束不足（刚体运动未消除，模型是机构）或过约束/重复约束（重叠 dofs） | 拒绝。Evidence：指明缺失/重复的自由度方向 |
| L-06 | S | 细长受压杆欧拉利用率 >50%（强度仍安全） | 不阻断，结果附失稳提示 `buckling_note`，narrative 中说明 |

### M / MAT 类：材料

| 编号 | 强度 | Condition | Action / Criterion / Evidence |
|------|------|-----------|-------------------------------|
| M-01 | M | 模型引用材料缺失（material 为空）、材料库无此名、内联材料属性越界（E≤0、ν∉(-1,0.5)、ρ≤0、强度≤0） | 拒绝。Evidence：列出材料库可选名称，或提示"在材料页面自定义材料后再求解" |
| MAT-01 | M | 材料入库（联网检索与手动添加共用同一套范围，Kotlin `MaterialValidation` 与 Python `materials._validate_record_values` 互为镜像） | 拒绝入库，不静默接受后污染求解。判据：E 0.5~1 500 000 MPa；ν 0~0.49；ρ 50~25 000 kg/m³；强度 0.01~5 000 MPa（密度/强度可选，提供才校验）。Evidence：指明哪个参数、实际值、"超出合理范围" |

### DIM 类：量纲与换算（总原则 1 的落地）

| 编号 | 强度 | Condition | Action / Criterion / Evidence |
|------|------|-----------|-------------------------------|
| DIM-01 | M | 载荷/边界条件可由源字段推导（风载、重物质量、静水压力、覆土荷载） | 模型契约只接受源字段，换算公式固定在代码侧：风载 `F = cp·½ρv²A`（ρ=1.225 kg/m³，cp 默认 1.2，蒲福风级查表换算，`frame/frame3d` 的 `type=wind`）；`mass_kg → mg`；`depth_m → ρgh`。AI/用户不填推导后的力值。3D 风载 dir 只允许水平 x/y（竖直为重力方向） |
| DIM-02 | M | 单位契约：frame/frame3d 几何用 mm；solid/solid_generic/shell/buried_pipe 用 m；温度 °C；压强 Pa | 校验按单位契约给区间（见 G-04），报错文案必须复述单位（"240 毫米应写 0.24"） |

### EQ 类：求解层平衡校核（最后一道物理防线）

| 编号 | 强度 | Condition | Action / Criterion / Evidence |
|------|------|-----------|-------------------------------|
| EQ-01 | M | 每次静力求解完成后：①合力平衡——支反力合力 + 外载合力残差相对 >5%；②**合力矩平衡**——对质心的外力矩与支反力矩残差相对 >5%（`solid.py`，P2-6 补齐力矩项） | 抛"静力不平衡/力矩不平衡"拒绝出结果。判据分母：力取 `max(‖外载合力‖,1e-30)`；矩取 `max(‖外力矩‖, ‖外载合力‖×对角线, 1e-12)`——纯温度等自平衡载荷两侧≈0 天然豁免，不误报。Evidence：打印两侧矢量与相对残差百分比 |
| EQ-02 | M | 解的数值有效性：位移含 NaN/Inf，或最大位移远超实体对角线尺寸（约束不足/机构征兆） | 拒绝出结果，提示检查约束与载荷 |

### STB / RTR / LOG 类：流程防线

| 编号 | 强度 | Condition | Action / Criterion / Evidence |
|------|------|-----------|-------------------------------|
| STB-01 | M | 建模产出 Frame 候选后、呈现给用户前做几何不变性预检，**覆盖所有路径**：多候选路径剔除不稳候选（基准不稳换 seed 重试 1 次，仍不稳保留兜底）；单模型路径不稳→`seed+77` 换 seed 重试 1 次，重试就绪且稳定才采用；修改模型强锚定跳过；预检自身异常按稳定放行 | 判据：`checkFrameStability` 返回机构/近机构即不稳。Evidence：预检结果进 case_log |
| RTR-01 | M | `parseFrameWithRetry` 重试预算：结构/契约错误重试 ≤1 次；语义错误按确定性修改单重试 ≤`MAX_SEMANTIC_RETRIES`(2) 次；两类叠加受全局上限 `MAX_TOTAL_ATTEMPTS`(3)（含首轮）兜底 | 语义耗尽抛 `AiRetryExhaustedException`（attempts + firstError 供埋点）；结构耗尽原样抛保留类型。Evidence：埋点记录总尝试次数与首轮错误 |
| LOG-01 | M | **全部求解入口**（run_frame/run_flow/run_seepage/run_transient/run_solid/run_solid_generic/run_shell/run_buried_pipe/run_scenario）埋点：Kotlin 侧在序列化模型注入 `_case_meta`（session_id、user_text、input_form，键名与 Python `model.pop("_case_meta")` 契约一致），调试包 `case_log_enabled="1"` 时写 case_log.jsonl | 埋点自身失败绝不影响主流程（I 级实现，M 级覆盖要求）。错误按关键词归类到 G/L/M 等编号。模板流无会话上下文时只传开关、不注入 `_case_meta`；候选注入后 anchorBlock 用 `case_meta=null` 防上下文串染 |

## 四·五、P 类：提示词与成本（v3 新增，初步依据见 §7）

| 编号 | 强度 | Condition | Action / Criterion / Evidence |
|------|------|-----------|-------------------------------|
| P-01 | M | 静态前缀（system prompt + 输出 schema）是每次调用输入的绝对主体（实测前缀占输入 86–99%；FRAME_AUTO+schema ≈ 6.4k tok） | 新增建模规则**优先落 schema 约束或代码侧校验**（联动 C-01），提示词散文只兜底；若新增散文使任一通道静态前缀超过 7k tokens，必须先裁剪/去重/下沉代码侧，不得直接堆叠。Evidence：`PromptTokenBudgetTest` 表1 |
| P-02 | M | 静态前缀必须**逐调用完全一致**：无时间戳、随机顺序、轮次相关内容；动态内容一律放 user 消息 | 理由：月之暗面对重复前缀自动上下文缓存（5min TTL，命中 ¥1.3 vs 未命中 ¥13/MTok，差 10 倍）；重试、多候选、5 分钟内追问全部复用同一前缀。Evidence：真机 llm_stats cached_tokens（P-05） |
| P-03 | S | 思考强度分档：单调用成本由思考 token 主导。**真机实测（2026-11-19，v1.2.38 debug，K3.1-pro）**：low 档思考 488–759 tok（harness 假设 1k 偏高）、快 40–50%、缓存命中 98%，但调用层首次失败率 ~18%（11 次 2 败）且同请求 3 次建模仅 1 次合格；highspeed 更快但质量无提升、token 费约 2 倍（性价比为负，不做默认） | **已实施（2026-11-19）**：默认档 low（`SettingsRepository.llmReasoningEffort`），任何失败的重试自动升 high 兜底——`AiParser.parseFrameWithRetry` 第 2 次尝试起、`ChatSession` 单模型/候选失稳换 seed 重试经 `AiClient.withReasoningEffort("high")`；非 Kimi 服务该调用返回自身、行为不变。后续调档须附 llm_stats.jsonl 前后对比（P-05） |
| P-04 | M | 调用次数乘数预算：任务成本 ≈ 调用次数 ×（前缀+输出+思考） | 候选 × 重试的最坏乘数（候选 3 × 全局 3 次尝试 + 失稳重试 ≈ 8 调用/任务）必须作为成本区间写进产品决策；`candidateCount` 与 `MAX_TOTAL_ATTEMPTS` 不得脱离成本表单独上调。Evidence：`PromptTokenBudgetTest` 表3 |
| P-05 | M | 成本可观测性：每次 LLM 调用记录 usage（prompt/completion/reasoning/cached tokens + 时延），调试包写 `{dataDir}/llm_stats.jsonl`（开关同 caseLogEnabled，正式包零开销） | 任何提示词/候选/重试策略改动必须附改动前后调用级对比数据；离线估算（±30%）只作初步参考，以真机 usage 为准。状态：**采集层已实施**（2026-11-19）——`LlmClient.kt` 解析 OpenAI/Anthropic 两口径 usage（Kimi 官方域名流式自动带 `stream_options.include_usage`），`AppContainer.kt` 仅调试包落盘；调用带 `tag`（frame_model/followup/template_parse/material_lookup/optimize/polish）区分通道；失败调用也记 ok=false（时延已知、token 未知）。待真机采集数据替换 §7 假设档位 |
| P-06 | S | 历史瘦身：当前 assistant 完整模型 JSON 写入 LLM history，后续每次调用重复付费 | 目标：历史只存结果摘要（kind+关键字段），完整模型存会话状态；实施前需埋点验证多轮一次通过率不劣化。状态：未实施 |

## 四·六、O 类：优化策略（v4 新增）

适用 AI 优化通道（`OptimizeSession`，tag=optimize，frame/solid_generic）：计算结论为「注意/危险」（安全系数不足）时产出的优化方案必须遵守下列策略规则。背景：此前方案几乎只从"加大受力截面 + 更换材料"入手，属于最笨的两档手段；改变受力机制的体系级改造（简支→连续、梁→桁架等）往往以少量材料换取数倍承载力，必须优先。optimize 通道静态前缀现约 1k tok，远低于 P-01 的 7k 上限，策略菜单可直接落提示词散文（`PROMPT_ZH`/`PROMPT_EN`）。

| 编号 | 强度 | Condition | Action / Criterion / Evidence |
|------|------|-----------|-------------------------------|
| O-01 | M | 安全系数不足时产出优化方案 | 按三级优先级思考：**体系级**（改变受力机制/结构体系）＞**构件级**（加大截面、增设斜撑/加劲肋、改善连接）＞**材料级**（换更高强度材料，仅兜底）。判据：首个方案必须是结构形式调整且保留原材料；不得以"加大截面/换材料"为唯一手段；换材料只允许在结构手段用尽后出现。Evidence：方案 desc/changes 写明所用手段层级 |
| O-02 | M | 诊断为弯曲/挠度/屈曲主导（大跨受弯构件等），或要求多方案（≥2 个） | 先评估体系级改造菜单：①简支→连续（增设中间支座或延伸多跨，削减跨中正弯矩）；②梁→桁架/拱（弯曲转为轴力拉压，发挥材料轴向承载力）；③增设中柱/支撑缩短计算跨度；④改变传力路径（增设斜撑/拉杆把荷载引向更刚的路径）。判据：多方案时至少一个为体系级改造；该结构确实无法体系级改造时退化为"至少一个方案只改结构形式、不换材料"。Evidence：desc 说明受力机制的改变及其为何更高效 |
| O-03 | M | 体系级改造方案进入有限元重算验证 | 必须通过 `OptimizeDiff` 防作弊校验：新增中间支撑/杆件合法（`supportRelaxed` 只拦"减少原有约束"）；杆件拆分（梁→桁架）按几何匹配+共线子段识别为"修改"而非整段新增；载荷签名（`loadSignature`）不得变化。判据：`loadOrSupportChanged=false` 且重算可解。Evidence：校验失败原因在 HUD 明示；减载/松约束一律判作弊 |

## 五、新增/变更入口的检查单

新增一个求解器或建模路径时，必须逐项确认：

1. 模型 dataclass/序列化是否带 `case_meta`（`@SerialName("_case_meta")`）并在 ready 路径注入（LOG-01）；
2. `PythonBridge.kt` 对应 run_* 是否传 `caseLogTag()`，`bridge.py` 是否 pop `_case_meta` 并走统一埋点包装（LOG-01）；
3. 校验规则是否编号、带 Evidence，错误能否直接作为重试修改单（§三格式）；
4. 是否接入稳定性预检（Frame 类）或求解层平衡校核（连续体类）（STB-01、EQ-01）；
5. 可推导载荷是否只暴露源字段、换算在代码侧（DIM-01）；
6. 重试路径是否受全局预算约束（RTR-01）；
7. 是否有对应测试：Python 闭式解/校核测试 + Kotlin 校验/注入测试。
8. 是否改动静态前缀/思考档/调用乘数？改动前跑 `PromptTokenBudgetTest` 对照 P 类规则（P-01/P-02/P-04），改动后按 P-05 补对比数据。
9. 优化通道方案是否遵守 O 类三级策略（体系级优先、材料兜底，O-01/O-02），体系级改造能否通过 `OptimizeDiff` 防作弊校验（O-03）？

## 六、代码镜像位置速查

| 防线/规则 | Kotlin | Python |
|-----------|--------|--------|
| 契约与 schema | `FrameOutputSchema.kt`、`Models.kt` | `bridge.py` |
| 语义校验 G/L/M | `AiParser.kt`（`validateFrameModel`、`validateSolidGenericModel` 等） | `solid.py`、`frame.py`、`buried_pipe.py`、`materials.py` |
| 材料校验 MAT-01 | `MaterialLookup.kt`（`MaterialValidation`） | `materials.py`（`_validate_record_values`） |
| 风载换算 DIM-01 | `AiParser.kt`（wind 校验）、`Models.kt`（FrameLoad 源字段） | `frame.py`、`frame3d.py`（`type=wind`）、`flow.py`（`speed_from_beaufort`、`AIR_DENSITY`、`CP_NET`） |
| 平衡校核 EQ-01 | — | `solid.py`（solve_solid 输出 applied/reaction 力与力矩） |
| 稳定性预检 STB-01 | `ChatSession.kt`（send 单模型路径、候选过滤）、`ChaquopyEngine.checkFrameStability` | `frame.py`（机构/奇异报错） |
| 重试预算 RTR-01 | `AiParser.parseFrameWithRetry`（`MAX_SEMANTIC_RETRIES`、`MAX_TOTAL_ATTEMPTS`） | — |
| 案例埋点 LOG-01 | `PythonBridge.kt`、`ChatSession.newCaseMeta()` | `bridge.py`、`case_log.py` |
| 提示词与成本 P 类 | `ChatSession.kt`（提示词常量）、`FrameOutputSchema.kt`、`LlmClient.kt`（reasoning_effort、usage 采集 `extractUsage`/`LlmCallStat`）、`AppContainer.kt`（llm_stats.jsonl 落盘接线）、`SettingsRepository.kt`（默认档）、`PromptTokenBudgetTest.kt`（离线测量）、`LlmClientTest.kt`（采集层测试） | — |
| 优化策略 O 类 | `OptimizeLogic.kt`（`OptimizeSession` `PROMPT_ZH`/`PROMPT_EN` 策略段与硬性约束、`OptimizeDiff` 防作弊校验） | — |

## 七、初步测量数据（2026-11-19，JVM harness 离线估算）

**口径与来源**：定价取月之暗面公开价（platform.kimi.com/docs/pricing/chat，kimi-k3.1-pro：输入 ¥13/MTok、输出 ¥68/MTok、缓存命中 ¥1.3/MTok；思考 token 按输出计费）。token 估算按官方口径 1 token ≈ 1.5–2 汉字（CJK 0.6 tok/字、ASCII 0.3 tok/字），误差 ±30%，以真机 usage 为准。测量工具：`android/app/src/test/java/com/pocketsim/app/PromptTokenBudgetTest.kt`。

**表1 静态输入规模（tok，含材料注入，schema ≈1441 tok）**：FRAME_AUTO 4923｜FRAME3D 3741｜SOLID_GENERIC 2655｜SHELL_VESSEL 1355｜BURIED_PIPE 815｜SOLID_FORCE 572。

**表2 单任务成本要点（¥）**：
- T1 简单平面框架单调用：low(1k 思考) ¥0.171 / high(3k) ¥0.307 / max(6k) ¥0.511。high 档构成：思考 ¥0.204（66%）+ 输入前缀 ¥0.083（27%）+ 正文输出 ¥0.020（7%）。
- 前缀占输入 86–99%（照片任务除外）；输出正文普遍 <600 tok。
- T2 追问第 2 轮输入仅 +49 tok——追问场景历史增量很小，成本大头仍是每调用重付前缀（靠缓存解决，非靠删历史）。

**表3 乘数**：候选 3（无重试）T1：无缓存 ¥0.92 / 有缓存 ¥0.77；最坏路径 ≈8 调用/任务 ≈ ¥2.4（high、无缓存）。

**成本杠杆排序**：①思考档位（P-03）＞②调用次数（P-04）＞③前缀缓存命中（P-02）＞④前缀长度裁剪（P-01）。删提示词散文省的是最便宜的项且有质量风险，必须最后做、用数据做。

**复现命令**：
```powershell
gradle :app:testDebugUnitTest --tests "com.pocketsim.app.PromptTokenBudgetTest"
```

**编码坑（已根治，2026-11-19）**：本机工作区含中文路径时，Gradle daemon 以 UTF-8 写 test worker 的 classpath argfile，而 JDK17 启动器按本地编码(GBK)解码 → 中文路径条目乱码，全部单元测试类 ClassNotFoundException。永久修复：`android/gradle.properties` 的 `org.gradle.jvmargs` 已移除 `-Dfile.encoding=UTF-8`（daemon 默认按系统编码写 argfile，与启动器一致；源码/资源编码由 AGP/kotlinc 显式 UTF-8，不依赖 file.encoding）。全量 24 个测试类 0 失败验证通过。**勿再往 jvmargs 加回该标志。**

**真机 usage 数据口径（P-05 采集层，待实测替换上表假设）**：调试包每次 LLM 调用在 `{filesDir}/llm_stats.jsonl` 追加一行：`{ts, tag, model, provider, effort, stream, ms, ok, retried, usage:{prompt, completion, reasoning, cached}}`。聚合要点：①按 `tag+effort` 分组求 reasoning/prompt 分布，替换表2 的 1k/3k/6k 假设档位；②`cached/prompt` 比例验证 P-02 缓存命中；③`ok=false` 与 `retried` 占比量化 P-04 乘数。拉取：`adb shell run-as <包名> cat files/llm_stats.jsonl > llm_stats.jsonl`（与 case_log.jsonl 同目录、同方式）。
