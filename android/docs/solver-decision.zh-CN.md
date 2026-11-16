# 求解器选型决策记录（ADR-001）

> [English](solver-decision.md) · 中文

- **状态**：已定案 —— 路线 A（Chaquopy + scikit-fem）
- **日期**：2026-10-25
- **范围**：口袋物理仿真 App 的设备端 FEA 求解引擎（v1：线弹性 2D；后续 3D/非线性另立 ADR）
- **关联**：`/workspace/.trae/specs/add-pocket-fea-android-app/spec.md`（产品 Spec）

---

## 1. 背景与约束

App 需要在**无网络、无服务器**的前提下完成有限元求解（隐私 + 可用性要求），
且 v1 验收目标为：

- 与理论解误差 ≤ 5%；
- 10 万自由度求解 ≤ 60s（中端真机，8GB RAM）；
- 安装包可接受范围内（数百 MB 级）。

同时项目已有一套 Python 原型（`/workspace/pocket-fea`，scikit-fem 实现，
含悬臂搁板/墙上钉挂重/简支层板三模板），验证过精度与工程化路径。

## 2. 候选路线

| | 路线 A：Chaquopy + scikit-fem | 路线 B：CalculiX（NDK） | 路线 C：FEniCSx |
|---|---|---|---|
| 形态 | App 内嵌 Python 解释器，纯 Python 求解栈 | C/C++ 求解器交叉编译为 .so，JNI 调用 | dolfinx + PETSc/MPI 全栈移植 |
| 与原型复用 | **直接复用 Python 原型代码** | 需重写输入卡片转换层 | 需重写 UFL/Python 驱动层 |
| 依赖链 | numpy/scipy/skfem 均有 Android wheel | calculix 源码 + NDK 工具链 | PETSc、MPI、HDF5 等原生依赖链 |
| 3D/非线性 | 弱（内存与性能受限） | **强**（CalculiX 原生能力） | 强 |
| 集成成本 | 低（Gradle 插件级） | 高（自维护 NDK 构建） | **不可行**（见 §4） |

## 3. 决策：路线 A（Chaquopy + scikit-fem）

### 3.1 定案理由

1. **集成成本低且已验证**：Chaquopy 17.1.0（2026-10 发布，免费开源 Apache-2.0，
   Maven Central `com.chaquo.python`）对 AGP 7.3–9.4 一等支持（本工程 AGP 8.5.2）；
   numpy/scipy 官方提供 Android 预编译 wheel，scikit-fem 为纯 Python 包，
   构建配置仅需 `pip { install(...) }` 三行。
2. **代码复用**：求解内核与 Python 原型同栈同语言（`pocketsim` 包），
   算法修复、模板新增在桌面与 App 端零成本同步；原型已跑通的
   材料库、模板注册、安全分级约定（sf ≥ 2.0 安全 / 1.2 ≤ sf < 2.0 注意 / < 1.2 危险）
   直接平移。
3. **二阶单元精度达标**：原型采用 CST 常应变三角形单元 + 网格收敛加密，
   与材料力学理论解（悬臂挠度 δ=PL³/3EI 等）对比误差 ≤ 5%，
   详见证数报告：`../python/benchmark_report.zh-CN.md`（由 Python 侧任务生成，
   含各模板 FEA vs 理论解误差表与网格收敛性数据）。
4. **性能可达标**：2D 线弹性 10 万自由度对应稀疏对称方程组，
   scipy（SuperLU）在 arm64 上求解秒级；超时保护由 Python 侧
   网格自动降级机制兜底（结果页展示"已自动降级网格"提示）。

### 3.2 已知代价（接受）

- Python 运行时 + 科学栈使 APK 增大 ~150–250 MB（两个 ABI）——生活工具型 App 可接受；
- Python 求解为 C 层阻塞，不可中途取消——以降级机制替代取消；
- scipy wheel 版本受 Chaquopy 仓库覆盖限制——备用方案见 README「已知限制」第 2 条。

## 4. 不采用 FEniCSx 的原因（路线 C 否决）

FEniCSx（dolfinx）的求解层依赖 **PETSc + MPI（及 BLAS/LAPACK/HDF5）完整工具链**：

- 这些库面向 HPC 集群设计，无 Android 官方支持，需自行交叉编译整条依赖链
  （含 MPI 运行时在单进程 App 沙箱内的适配），工程量与维护风险远超收益；
- Chaquopy 的 pip 仓库不提供这些包的 Android wheel；
- 其核心优势（大规模并行、弱形式自动微分）对 v1 的 2D 线弹性场景无增益。

**结论：依赖链无法在 App 内打包，否决。**

## 5. 路线 B（CalculiX / NDK）：3D 与非线性阶段的演进方向

v1 不做，但保留为既定演进路线，触发条件与预研要点：

- **触发条件**：v2 需求进入 3D 实体建模、几何非线性、接触或弹塑性时；
- **预研要点**：CalculiX `ccx` 以 NDK 交叉编译为 arm64 .so，JNI 封装作业提交接口；
  前后处理（网格、云图）仍留在 Python/Kotlin 侧，复用 v1 的数据管道
  （FeaResult JSON 契约不变，仅替换 `pocketsim.engine` 后端实现）；
- **过渡策略**：路线 A 与 B 可并存（模板级开关），2D 模板继续走 scikit-fem。

## 6. 决策后果与行动项

| 项 | 内容 | 状态 |
|---|---|---|
| Kotlin 侧 | `FeaEngine` 接口 + `ChaquopyEngine` 实现，JSON 字符串跨边界 | 已落地（`PythonBridge.kt`） |
| Python 侧 | `pocketsim.bridge.run_scenario` 等四个入口（见 README 契约表） | 已交付（`bridge.py` 适配层 + 105 项测试） |
| 精度证数 | `../python/benchmark_report.zh-CN.md`（误差 ≤5%、收敛性、10 万 DOF 计时） | 已交付（桌面基准；真机复测待做） |
| 风险跟踪 | scipy wheel 可用性 → 备用方案三选一（README 已知限制 §2） | 跟踪中 |
