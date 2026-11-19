# PocketSim 授权与归属声明（NOTICE）

> [English](NOTICE.md) · 中文

Copyright (c) 2024–2026 PocketSim 口袋仿真. All rights reserved.

本仓库采用**分层授权**：开源部分与专有部分适用范围不同，以本文件说明为准。
仓库根目录 `LICENSE` 为 GNU Affero General Public License v3.0（AGPL-3.0）原文。

## 1. 适用范围

| 路径 | 授权 | 说明 |
|---|---|---|
| `python/pocketsim/` | **AGPL-3.0-or-later** | 有限元求解引擎（frame / frame3d / solid / flow / seepage / transient / bridge 等） |
| `python/materials.json`、`python/materials_us.json` | **AGPL-3.0-or-later** | 随引擎分发的材料数据 |
| `python/tests/`、`python/examples/` | **AGPL-3.0-or-later** | 测试与示例代码 |
| `android/`（含 Kotlin/Compose 源码、资源、构建配置） | **专有（All Rights Reserved）** | 客户端界面、AI 交互、激活与额度逻辑、图片识别链路 |
| `screenshots/`、`docs/`、`tools/` | **专有（All Rights Reserved）**，另有标注除外 | 文档与展示素材 |

`android/` 下各源文件头部标注 `SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary`；
`python/` 下各源文件头部标注 `SPDX-License-Identifier: AGPL-3.0-or-later`。

## 2. 引擎的附加许可（AGPL-3.0 第 7 条）

作为 AGPL-3.0 第 7 条项下的附加许可：

- 你可以将 `python/pocketsim/` 引擎与独立模块链接、嵌入或打包，并以你选择的条款
  分发合成后的应用（包括闭源应用），**无需**将你自己的应用代码按 AGPL 授权；
- 但该附加许可**仅限引擎与其它模块的链接边界**，不改变引擎自身的 AGPL 义务：
  你若修改引擎并向他人分发，仍须按 AGPL 提供引擎的完整对应源代码；
- 若你通过修改后的引擎提供网络服务，AGPL 第 13 条仍然适用，须向网络用户提供
  修改版的完整对应源代码；
- 该附加许可**不适用于** `android/` 目录下的 PocketSim 客户端代码，该部分仍为专有代码。

## 3. 商业授权

以下行为**不在** AGPL 授权范围内，须事先取得书面商业授权（见 `LICENSE-COMMERCIAL`）：

- 将本引擎或客户端以闭源方式商用分发、OEM 预装、捆绑销售；
- 在不愿履行 AGPL 源码开放义务的前提下提供云端/网络服务；
- 移除或规避版权、授权、商标标识；
- 使用「PocketSim」「口袋仿真」名称、图标进行推广或冒充官方版本。

商业授权需单独签署书面协议；`LICENSE-COMMERCIAL` 仅为授权政策说明，不构成授权本身。

## 4. 商标

「PocketSim」「口袋仿真」名称、Logo 与应用图标为 PocketSim 的标识。
开源许可仅授予著作权层面的权利，**不授予任何商标权**。你可以如实说明软件来源，
但不得以官方名义发布修改版，也不得使用官方图标造成混淆。

## 5. 贡献

提交 Pull Request、补丁或代码片段即表示你同意：

- 你的贡献按 AGPL-3.0-or-later 授权给本项目及所有下游用户；
- 你同时授予 PocketSim 版权持有人一项非独占、永久、全球、免许可费的再许可权，
  以便在商业授权版本中使用你的贡献；
- 你保证贡献为原创或已获得必要授权，且不侵犯第三方权利。

## 6. 第三方组件

- Chaquopy（MIT）、scikit-fem（BSD）、SciPy（BSD）、NumPy（BSD-3-Clause）、
  OpenBLAS（BSD-3-Clause）、reportlab（BSD · ReportLab 变体）、Pillow（HPND）、
  charset-normalizer（MIT）、FreeType（FTL）、libjpeg（IJG）、
  libc++（Apache-2.0 WITH LLVM-exception）、libquadmath（LGPL-2.1-or-later）、
  libgfortran（GPL-3.0-or-later，附 GCC Runtime Library Exception）、
  AndroidX/Compose（Apache-2.0）、OkHttp（Apache-2.0）、kotlinx.serialization（Apache-2.0）
  等第三方组件遵循其各自许可，不受本文件影响。
- 完整清单与许可原文见 App 内「设置 → 开源许可」（可逐项查看全文），
  许可证文本存放于 `android/app/src/main/assets/licenses/`。

## 7. 免责声明

本软件按「现状」提供，不附带任何明示或默示担保。仿真结果仅供日常生活参考，
不可用于专业工程、人身安全或重大财产决策。详见 AGPL-3.0 第 15–17 条及 App 内免责声明。
