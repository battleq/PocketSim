# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""TET4 三维实体有限元验证示例（可独立运行，不依赖 pytest）。

四个算例，全部有解析解可比：
  ① 常应变 patch test —— 单轴拉伸，TET4 应精确复现均匀应力场（机器精度）
  ② 悬臂墙风压弯曲 —— 与单位墙宽悬臂梁理论对比挠度与弯曲应力
  ③ 自重轴力柱 —— 支反力恒等于自重（严格平衡），顶部压缩量对比 1D 杆解
  ④ 网格收敛 —— 由粗到细，①②类误差应单调下降

运行：
    cd python && python examples/verify_solid.py
退出码：全部通过 0，任一超差 1。
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pocketsim import solid  # noqa: E402
from pocketsim.flow import GRAVITY  # noqa: E402

ROWS: list[tuple[str, str, float, float, float, float, bool]] = []


def record(case: str, item: str, theory: float, fea: float, unit: str,
           tol_pct: float, rel_tol: float = 0.0) -> None:
    """记录一行比对：相对误差 = |fea/theory - 1|，theory≈0 时用绝对误差。"""
    if abs(theory) > 1e-12:
        err = abs(fea / theory - 1.0) * 100.0
        ok = err <= tol_pct
    else:
        err = abs(fea)
        ok = err <= tol_pct
    ROWS.append((case, item, theory, fea, err, unit, ok))


# ---------------------------------------------------------------- ① patch test
def case_patch_test() -> None:
    """单轴拉伸：底面约束最小自由度，顶面施加均布拉应力 σ0。

    解析解（各向同性线弹性）：
        σyy = σ0（全单元），σxx=σzz=σxy=σyz=σzx=0
        uy = σ0·y/E, ux = -ν·σ0·x/E, uz = -ν·σ0·z/E
    TET4 对线性位移场精确，误差应为机器精度量级。
    """
    case = "① patch test"
    L, H, T = 1.0, 2.0, 0.5
    nx, ny, nz = 4, 6, 4
    E, nu, s0 = 10.0e9, 0.30, 2.0e6

    nodes, tets = solid.build_box_mesh(nx, ny, nz, L, H, T)
    faces = solid.boundary_faces(tets)
    top = solid.faces_on_plane(nodes, faces, 1, H)
    bottom = solid.nodes_on_plane(nodes, 1, 0.0)

    # 约束 6 个刚体模态：底面 uy + 三个角点位移
    #（A 定 tx、B 定 tz、C 定绕 y 转动；三点需含 z≠0，否则绕 y 仍可自由转动）
    corner_a = int(np.argmin(np.linalg.norm(nodes - [0.0, 0.0, 0.0], axis=1)))
    corner_b = int(np.argmin(np.linalg.norm(nodes - [L, 0.0, 0.0], axis=1)))
    corner_c = int(np.argmin(np.linalg.norm(nodes - [0.0, 0.0, T], axis=1)))
    fixed = np.unique(np.concatenate([
        (bottom[:, None] * 3 + 1).ravel(),
        [3 * corner_a, 3 * corner_b + 2, 3 * corner_c],
    ]))

    res = solid.solve_solid(
        nodes, tets, E=E, nu=nu, fixed=fixed,
        face_loads=[{"faces": top,
                     "traction_fn": lambda _p: np.array([0.0, s0, 0.0])}],
    )
    sig, u = res["sigma"], res["u"].reshape(-1, 3)

    record(case, "σyy 均值", s0, float(sig[:, 1].mean()), "Pa", 1e-6)
    record(case, "σxx|σzz 最大绝对值", 0.0, float(np.abs(sig[:, [0, 2]]).max()), "Pa", 1e-3)
    record(case, "剪应力 最大绝对值", 0.0, float(np.abs(sig[:, 3:]).max()), "Pa", 1e-3)

    top_nodes = solid.nodes_on_plane(nodes, 1, H)
    record(case, "顶面 uy", s0 * H / E, float(u[top_nodes, 1].mean()), "m", 1e-6)

    tip = int(np.argmin(np.linalg.norm(nodes - [L, H, T], axis=1)))
    record(case, "角点 ux（泊松收缩）", -nu * s0 * L / E, float(u[tip, 0]), "m", 1e-6)
    record(case, "角点 uz（泊松收缩）", -nu * s0 * T / E, float(u[tip, 2]), "m", 1e-6)
    record(case, "支反力 fy", -s0 * L * T, float(res["reaction_force"][1]), "N", 1e-6)


# ---------------------------------------------------------------- ② 悬臂墙
def case_cantilever() -> None:
    """风压悬臂墙：与单位墙宽悬臂条带梁对比。

        δ = p·H⁴ / (8·E·I),  σ = 3·p·H² / T²,  I = T³/12
    """
    case = "② 悬臂墙弯曲"
    model = {
        "kind": "solid_wall",
        "wall": {"length_m": 4.0, "height_m": 2.5, "thickness_m": 0.24},
        "material": "砖砌体",
        "pressure_Pa": 600.0,
        "mesh_level": "fine",
    }
    r = solid.run_solid(model)
    p = 600.0
    H, T = 2.5, 0.24
    E = r["material_params"]["E_MPa"] * 1e6
    I = T ** 3 / 12.0
    delta_th = p * H ** 4 / (8.0 * E * I) * 1000.0        # mm
    sigma_th = 3.0 * p * H ** 2 / T ** 2 / 1e6            # MPa

    record(case, "墙顶侧移", delta_th, r["metrics"]["top_disp_mm"], "mm", 12.0)
    record(case, "墙根弯曲应力", sigma_th, r["metrics"]["bend_MPa"], "MPa", 12.0)
    record(case, "支反力 fz", -p * 4.0 * H, r["reactions"]["fz_N"], "N", 1.0)


# ---------------------------------------------------------------- ③ 自重柱
def case_gravity_column() -> None:
    """自重轴力柱：底面全固定，仅体力（无风压）。

    严格解：支反力 fy = ρg·L·H·T（与网格无关，检验体积力一致加载）
    近 1D 解：顶部压缩量 ≈ ρg·H²/(2E)；根部平均压应力 ≈ ρg·H
    （底面全固接会略微约束泊松膨胀，故轴向量给 10% 容差）
    """
    case = "③ 自重轴力柱"
    L, H, T = 0.4, 3.0, 0.4
    nx, ny, nz = 2, 40, 2
    E, nu, rho = 3.0e9, 0.20, 1800.0

    nodes, tets = solid.build_box_mesh(nx, ny, nz, L, H, T)
    base = solid.nodes_on_plane(nodes, 1, 0.0)
    res = solid.solve_solid(
        nodes, tets, E=E, nu=nu,
        fixed=solid.fixed_dofs(nodes, base),
        body_accel=(0.0, -rho * GRAVITY, 0.0),
    )

    weight = rho * GRAVITY * L * H * T
    record(case, "支反力 fy（= +自重）", weight,
           float(res["reaction_force"][1]), "N", 1e-6)

    top = solid.nodes_on_plane(nodes, 1, H)
    u = res["u"].reshape(-1, 3)
    record(case, "顶部压缩量", -rho * GRAVITY * H ** 2 / (2.0 * E),
           float(u[top, 1].mean()), "m", 10.0)

    cent = nodes[tets].mean(axis=1)
    first = cent[:, 1] <= 1.2 * (H / ny)
    record(case, "根部平均 σyy", -rho * GRAVITY * H,
           float(res["sigma"][first, 1].mean()), "Pa", 10.0)


# ---------------------------------------------------------------- ④ 收敛
def case_convergence() -> None:
    """墙高/墙厚方向加密：挠度与弯曲应力误差应单调下降。"""
    case = "④ 网格收敛"
    p, H, T = 600.0, 2.5, 0.24
    prev = (float("inf"), float("inf"))
    # 粗网格容差放宽（常应变单元弯曲偏刚），最细档要求 ≤10%
    tol_of = {(20, 6): 35.0, (40, 8): 15.0, (64, 8): 10.0}
    for ny, nz in ((20, 6), (40, 8), (64, 8)):
        r = solid.run_solid({
            "kind": "solid_wall",
            "wall": {"length_m": 4.0, "height_m": H, "thickness_m": T},
            "material": "砖砌体",
            "pressure_Pa": p,
            "mesh": {"nx": 6, "ny": ny, "nz": nz},
        })
        d = r["theory_check"]["deflection_error_pct"]
        b = r["theory_check"]["bend_error_pct"]
        tag = f"ny={ny},nz={nz}"
        tol = tol_of[(ny, nz)]
        record(case, f"{tag} 挠度误差", 0.0, d, "%", tol)
        record(case, f"{tag} 弯曲误差", 0.0, b, "%", tol)
        # 单调性单独判定（记录为布尔行）
        ROWS.append((case, f"{tag} 误差不上升", 0.0, 0.0,
                     0.0, "", d <= prev[0] and b <= prev[1]))
        prev = (d, b)


# ---------------------------------------------------------------- 主流程
def main() -> int:
    print("口袋仿真 · TET4 三维实体有限元验证\n" + "=" * 78)
    case_patch_test()
    case_cantilever()
    case_gravity_column()
    case_convergence()

    print(f"{'算例':<12} {'比对项':<24} {'理论值':>12} {'有限元值':>12} {'误差':>10}  判定")
    print("-" * 86)
    for case, item, th, fea, err, unit, ok in ROWS:
        theory = f"{th:.6g}" if abs(th) > 1e-12 else "—"
        if unit == "%":
            err_s = f"{err:.2f}%"
        elif abs(th) > 1e-12:
            err_s = f"{err:.2e}%" if err < 0.01 else f"{err:.2f}%"
        elif th == 0.0 and fea == 0.0 and unit == "":
            err_s = "—"
        else:
            err_s = f"{err:.2e}"
        fea_s = "—" if (th == 0.0 and fea == 0.0 and unit == "") else f"{fea:.6g}"
        print(f"{case:<12} {item:<24} {theory:>12} {fea_s:>12} {err_s:>10}  "
              f"{'通过' if ok else '不通过'}")

    failed = [r for r in ROWS if not r[6]]
    print("=" * 78)
    if failed:
        print(f"结果：{len(failed)} / {len(ROWS)} 项未通过 → 实体求解器验证失败")
        return 1
    print(f"结果：全部 {len(ROWS)} 项通过 → TET4 实体求解器验证成功")

    print("\n在 App 内复现②的悬臂墙算例（首页输入）：")
    print("  「8 级大风天，3 米长、2.5 米高、24 厘米厚的砖砌体围墙会不会被吹倒？」")
    print("  或直接使用等价模型 JSON：")
    print('  {"kind":"solid","wall":{"length_m":4.0,"height_m":2.5,'
          '"thickness_m":0.24},"material":"砖砌体","pressure_Pa":600,"mesh_level":"fine"}')
    return 0


if __name__ == "__main__":
    sys.exit(main())
