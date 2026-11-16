# Copyright (c) 2024-2026 PocketSim 口袋仿真
# SPDX-License-Identifier: AGPL-3.0-or-later - see /LICENSE and /NOTICE.md

"""结构化矩形三角网格生成与三档网格档位。

- make_rect_mesh：矩形域结构化三角网格（每个矩形格划分为 2 个三角形）；
- MESH_LEVELS：coarse / medium / fine 三档系数（0.5x / 1x / 2x），
  模板给出基准 nx、ny，按档位系数缩放，并做下限保护（nx、ny ≥ 4）。

单位约定：坐标为 SI（米）。
"""
from __future__ import annotations

from typing import Tuple

import numpy as np
from skfem import MeshTri

# 网格档位：模板基准 (nx, ny) 的缩放系数
MESH_LEVELS = {
    "coarse": 0.5,   # 粗档：基准的 0.5 倍
    "medium": 1.0,   # 中档：基准的 1 倍（默认）
    "fine": 2.0,     # 细档：基准的 2 倍
}

# 网格分段数下限保护
MESH_MIN_SEGMENTS = 4


def apply_mesh_level(base_nx: int, base_ny: int, level: str) -> Tuple[int, int]:
    """按档位系数缩放基准分段数，返回 (nx, ny)，下限保护 nx、ny ≥ 4。

    level 也可以直接是 (nx, ny) 元组（内部/基准测试用），此时原样返回并做下限保护。
    """
    if isinstance(level, str):
        if level not in MESH_LEVELS:
            raise ValueError(f"未知网格档位 {level!r}，可选：{sorted(MESH_LEVELS)}")
        factor = MESH_LEVELS[level]
        nx = int(round(base_nx * factor))
        ny = int(round(base_ny * factor))
    else:
        nx, ny = int(level[0]), int(level[1])
    nx = max(MESH_MIN_SEGMENTS, nx)
    ny = max(MESH_MIN_SEGMENTS, ny)
    return nx, ny


def make_rect_mesh(
    nx: int,
    ny: int,
    x_len: float,
    y_len: float,
    x0: float = 0.0,
    y0: float = 0.0,
) -> MeshTri:
    """生成矩形域 [x0, x0+x_len] × [y0, y0+y_len] 的结构化三角网格。

    nx、ny 分别为 x、y 方向的分段数（每个矩形格 2 个三角形）。
    返回 scikit-fem 的 MeshTri 对象。
    """
    nx, ny = int(nx), int(ny)
    if nx < 2 or ny < 2:
        raise ValueError(f"网格分段数过小：nx={nx}, ny={ny}（均需 ≥ 2）")
    if x_len <= 0.0 or y_len <= 0.0:
        raise ValueError(f"域尺寸必须为正：x_len={x_len}, y_len={y_len}")
    xs = np.linspace(float(x0), float(x0 + x_len), nx + 1)
    ys = np.linspace(float(y0), float(y0 + y_len), ny + 1)
    return MeshTri.init_tensor(xs, ys)
