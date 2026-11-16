# 解析解验证

> [English](validation.md) · 中文

> 下表每个数字均由 `python/pocketsim/` 中的求解器在 **2026-11-16** 实际运行得到，
> 与材料力学 / 板壳理论的闭式解对比。没有估算，也没有为好看而取舍——包括不好看的那些。

## 运行环境

| 项目 | 值 |
|---|---|
| 操作系统 | Windows (x86_64) |
| Python | 3.12.7 |
| scikit-fem | 12.0.2 |
| numpy / scipy | 2.5.2 / 1.18.1 |

App 内置运行的是同一份求解器代码（Python 3.10 + Chaquopy）。上述数据在桌面解释器上测得，**不是在手机上**；数值来自同一套库 API，结果应一致到数值精度，但未在真机复测。

## 结果

### 梁单元 —— 2D 框架与 3D 框架

二维用松木（E = 10 000 MPa），三维用低碳钢（E = 206 000 MPa）；截面 25 × 40 mm（2D）、50 × 100 mm（3D）。理论为 Euler–Bernoulli 梁（忽略剪切变形）。

| 工况 | 闭式解 | PocketSim | 误差 |
|---|---|---|---|
| 2D 悬臂，端部集中力 P = 49 N，L = 300 mm | 0.33075 mm | 0.33075 mm | 0.00 % |
| 2D 悬臂，均布 w = 0.2 N/mm | 0.151875 mm | 0.151875 mm | 0.00 % |
| 2D 简支，跨中集中力 P = 49 N | 0.0206719 mm | 0.0206719 mm | 0.00 % |
| 2D 简支，均布 w = 0.2 N/mm | 0.0158203 mm | 0.0158203 mm | 0.00 % |
| 3D 悬臂，竖向端部力 P = 49 N，L = 1000 mm | 0.0190291 mm | 0.0190291 mm | 0.00 % |
| 3D 悬臂，水平端部力 P = 49 N | 0.0761165 mm | 0.0761165 mm | 0.00 % |

零误差是**应该的**，不构成优点：梁单元实现的就是闭式解所用的同一套 Euler–Bernoulli 理论，因此结果精确到浮点精度。

### 三维实体（TET4 常应变四面体）

**分片检验 —— 单轴受压。** 箱体 2.0 × 0.3 × 0.3 m，自由面受 1 MPa 压力，对面全固定，粗网格。

| 量 | 闭式解 | PocketSim | 误差 |
|---|---|---|---|
| 最大压应力 | 1.000 MPa | 1.0064 MPa | 0.64 % |
| 反力 Fx（截面积 0.09 m²） | 90 000 N | 90 000 N | 0.00 % |

**弯曲主导工况 —— 悬臂箱梁。** 钢质 2.0 × 0.3 × 0.3 m，一端固定，端部 1 kN。闭式解 δ = PL³/3EI = 0.0192 mm。

| 网格档位 | PocketSim | 误差 |
|---|---|---|
| `coarse` | 0.00450 mm | **+76.5 %** |
| `medium` | 0.01020 mm | +46.8 % |
| `fine` | 0.01370 mm | +28.6 % |
| `ultra` | 0.01570 mm | +18.1 % |

这个数字不好看，但请看仔细：常应变四面体在弯曲下**偏刚**。沿厚度方向只有少数单元时，线性应变场表达不了弯曲曲率，位移被低估。它会收敛，但收敛慢——单元尺寸减半，误差大致减半。

实际含义：

- **三维实体**模式给出的位移，对弯曲主导的构件是**下界**（风压下的墙体正属于这类）。
- 用实体模式看**应力分布与安全分级**，不要用它取精确挠度。
- 梁、柱类构件请用梁模式：这类问题它是精确的。
- 确实需要实体模式的位移数字时，提高网格档位，并确认结果不再变化。

### 壳（MITC4，Reissner–Mindlin）

方板 a = 1 m、t = 10 mm、E = 210 GPa、ν = 0.3、均布压力 q = 1 kPa，20 × 20 网格。抗弯刚度 D = Et³/12(1−ν²)。

| 工况 | 闭式解 | PocketSim | 误差 |
|---|---|---|---|
| 四边固支，w = 0.00126 qa⁴/D | 6.552 × 10⁻⁵ m | 6.580 × 10⁻⁵ m | 0.43 % |
| 四边简支，w = 0.00406 qa⁴/D | 2.1112 × 10⁻⁴ m | 2.1157 × 10⁻⁴ m | 0.21 % |

板与墙类弯曲问题，本项目的精度担当是 MITC4 壳单元。

## 如何复现

上表的闭式解工况在仓库里就是单元测试：

```bash
cd python
python -m pytest tests/test_frame.py tests/test_frame3d.py tests/test_solid.py tests/test_shell.py -q
```

TET4 弯曲扫描不在常规测试里（它是研究性测量，不是断言），复现代码：

```python
from pocketsim import solid

E, L, H, T, P = 206e9, 2.0, 0.3, 0.3, 1000.0
I = T * H ** 3 / 12.0
theory = P * L ** 3 / (3 * E * I) * 1000.0   # mm

for level in ("coarse", "medium", "fine", "ultra"):
    r = solid.run_solid_generic({
        "kind": "solid_generic",
        "geometry": {"type": "box", "length_m": L, "height_m": H, "thickness_m": T},
        "material": "钢",
        "supports": [{"type": "plane", "axis": "x", "value": 0.0, "dofs": "fixed"}],
        "loads": [{"type": "force", "point": [L, H / 2, T / 2], "vector": [0.0, 0.0, -P]}],
        "mesh_level": level,
    })
    print(level, r["metrics"]["max_disp_mm"], theory)
```

## 本表未覆盖的部分

- **渗流、风场、温度历程、埋地管。** 这些用工程公式与二维场求解器，没有对应的闭式基准；由 `python/tests/` 里的自洽性测试覆盖（力平衡、单调性、守恒），不属于本表。
- **应力集中。** TET4 在粗网格上会低估峰值，取热点应力前请局部加密。
- **线弹性之外的一切。** 塑性、接触、大变形、动力学不在范围内，见 [limitations.zh-CN.md](limitations.zh-CN.md)。

## 相关文档

- [why-pocketsim.zh-CN.md](why-pocketsim.zh-CN.md) —— 这个项目要解决什么，为什么聊天机器人不是求解器
- [limitations.zh-CN.md](limitations.zh-CN.md) —— 支持的单元、截面与荷载
- [performance-benchmarks.zh-CN.md](performance-benchmarks.zh-CN.md) —— 网格规模与求解耗时
