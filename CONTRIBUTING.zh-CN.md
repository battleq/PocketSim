# 参与贡献

> [English](CONTRIBUTING.md) · 中文

感谢你对 PocketSim 的关注！本项目欢迎各类贡献，尤其是**材料库数据**、
**场景模板**和**Bug 报告**——这些是社区能最快帮上忙的地方。

## 可以贡献什么

| 类型 | 怎么提 | 说明 |
|---|---|---|
| 🧱 新材料数据 | Issue「材料数据建议」 | 国标/美标/行业材料的 E、强度、密度、线膨胀系数、耐温上限 |
| 🏗️ 新场景模板 | Issue「新场景请求」 | 日常生活里的结构/热/渗流场景（如「鱼缸水压」「帐篷抗风」） |
| 🐛 Bug 报告 | Issue「Bug 报告」 | 请附机型、Android 版本、复现步骤与截图 |
| 📖 文档/翻译 | PR 或 Issue | README、注释、错误提示的中英对照与润色 |
| 🧪 算例验证 | Discussion「Show and tell」 | 用专业软件（ANSYS/ABAQUS 等）交叉验证结果，附对比 |

## 贡献流程

1. **先搜索**：在 Issues 与 Discussions 中确认没有重复内容。
2. **开 Issue 或 Discussion**：描述清楚你的想法/问题，附上必要截图。
3. **讨论确认**：维护者会在 3 个工作日内回复；若涉及求解器改动，先讨论方案再动手。
4. **提交 PR**：代码贡献请基于 `main` 分支，通过全部现有测试（`python -m pytest`、
   Android JVM 单测）。
5. **签署 DCO**：提交时需带 `Signed-off-by` 行（`git commit -s`），
   表明你的贡献为原创且同意按项目授权条款许可。

## 授权与再许可

提交代码、数据或文档即表示你同意：

- 你的贡献按对应路径的授权许可（`python/` 为 AGPL-3.0-or-later，
  `android/` 为专有，文档按 CC BY 4.0）授权给本项目及所有下游用户；
- 你同时授予 PocketSim 版权持有人一项**非独占、永久、全球、免许可费的再许可权**，
  以便在商业授权版本中使用你的贡献；
- 你保证贡献为原创或已获得必要授权，不侵犯第三方权利。

材料库数据贡献（`materials.json` / `materials_us.json`）同样适用上述条款；
请尽量在 Issue 中附上数据来源（标准号或参考文献），便于复核。

## 代码规范

- Python：遵循 `python/tests/` 现有风格，函数需有类型注解；新增求解路径须带测试。
- Kotlin：遵循 `android/app/src/main/java/com/pocketsim/app/` 现有风格；
  UI 改动需附截图。
- 提交信息：Conventional Commits（`feat:` / `fix:` / `docs:` / `chore:`）。

## 行为准则

保持尊重与专业。本项目不接受人身攻击、政治争议或与项目无关的内容。
维护者有权删除违规内容并封禁相关账号。
