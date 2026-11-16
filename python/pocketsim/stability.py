"""支座稳定性（机构 / 近机构）检测。

支座不足或拓扑可变时，自由自由度刚度阵 K_ff 存在近零特征值：直接求解会得到
nan 或天文数字位移，用户看到的只是"求解失败"或毫无意义的大变形。这里在求解前
对 K_ff 做特征值判定，把"机构 / 近机构"定位到具体节点与自由度方向，给出可操作
的中文提示。

判定依据（ratio = 最小特征值 / 最大特征值）：
- ratio <= MECH_TOL        → 机构（真刚体模态，数值上≈0）
- MECH_TOL < ratio <= NEAR_MECH_TOL → 近机构（弱刚度，会产生异常大变形）
- ratio >  NEAR_MECH_TOL   → 稳定

阈值由 26 个模型的实测分布定（见 .trae/documents/p0_frame_stability_plan.md
第七节）：健康模型实测地板 ratio = 1.01e-9（5m 高细刚架，可正常计算），
NEAR_MECH_TOL = 1e-10 留有 10× 裕度，不会误拦合法的柔结构；旧阈值 1e-11 太松，
近机构（如 ratio 2.5e-11）会漏检并算出放大数十倍的无意义变形，本次收紧。
"""
from dataclasses import dataclass
from typing import Optional, Sequence

import numpy as np

DOF_LABELS_2D = ("ux（水平）", "uy（竖向）", "rz（转动）")
DOF_LABELS_3D = (
    "ux（水平 x）", "uy（水平 y）", "uz（竖向）",
    "rx（绕 x 转动）", "ry（绕 y 转动）", "rz（绕 z 转动）",
)

# 机构：ratio <= MECH_TOL（真刚体模态，数值≈0）；距健康地板 1e-9 有 5 个数量级，零误报
MECH_TOL = 1e-14
# 近机构：MECH_TOL < ratio <= NEAR_MECH_TOL；抓漏网的弱刚度模型，距健康地板 1e-9 有 10× 裕度
NEAR_MECH_TOL = 1e-10


@dataclass
class StabilityVerdict:
    """稳定性判定结果。

    level: "stable" | "mechanism" | "near_mechanism"
    reason: 稳定时为 None；否则为带节点/方向定位的中文修复指引
    """
    level: str
    reason: Optional[str] = None


def _localize_soft_mode(v: np.ndarray,
                        free_dofs: np.ndarray,
                        nodes: Sequence[dict],
                        labels: Sequence[str],
                        dofs_per_node: int) -> tuple:
    """取最小特征值振型中绝对值最大的分量，定位到节点编号与自由度方向。"""
    mode = v[:, 0]
    idx = int(np.argmax(np.abs(mode)))
    gdof = int(free_dofs[idx])
    node_i, dof_i = divmod(gdof, dofs_per_node)
    node_id = nodes[node_i].get("id", node_i + 1) if node_i < len(nodes) else node_i + 1
    direction = labels[dof_i] if dof_i < len(labels) else f"自由度 {dof_i}"
    return node_id, direction


def classify_stability(k_ff: np.ndarray,
                       free_dofs: np.ndarray,
                       nodes: Sequence[dict],
                       labels: Sequence[str],
                       dofs_per_node: int) -> StabilityVerdict:
    """判定自由自由度刚度阵是否机构 / 近机构，并定位薄弱方向。

    Args:
        k_ff: 自由自由度刚度阵（dense，对称）
        free_dofs: 自由自由度在整体自由度向量中的下标（np.flatnonzero(free)）
        nodes: 节点列表（含 "id"），用于把自由度映射回节点编号
        labels: 各自由度方向的中文名
        dofs_per_node: 每个节点的自由度数（2D=3，3D=6）

    Returns:
        StabilityVerdict：level 为 stable/mechanism/near_mechanism，不稳定时带 reason
    """
    n = int(k_ff.shape[0]) if hasattr(k_ff, "shape") else len(k_ff)
    if n == 0:
        return StabilityVerdict("stable")
    k = np.asarray(k_ff, dtype=float)
    try:
        # eigh 按升序返回实对称阵特征值；规模 ≤360（3D 60 节点），毫秒级
        w, v = np.linalg.eigh(k)
    except np.linalg.LinAlgError:  # pragma: no cover - 数值库异常时交给求解器兜底
        return StabilityVerdict("stable")

    w_max = float(np.max(np.abs(w)))
    if w_max <= 0.0:
        return StabilityVerdict(
            "mechanism",
            "结构整体刚度为 0（可能是节点坐标重合或截面尺寸异常），请检查模型几何与截面尺寸")

    ratio = float(w[0]) / w_max
    if ratio > NEAR_MECH_TOL:
        return StabilityVerdict("stable")

    node_id, direction = _localize_soft_mode(v, free_dofs, nodes, labels, dofs_per_node)
    if ratio <= MECH_TOL:
        return StabilityVerdict(
            "mechanism",
            f"结构为机构（支座约束不足 / 几何可变）：节点 {node_id} 可沿 {direction} 方向自由变形。"
            f"请增加支座、把滚轴改为铰接/固定，或补充斜撑使结构几何不变。")
    return StabilityVerdict(
        "near_mechanism",
        f"结构接近机构（几何可变 / 刚度不足，会产生异常大变形）：节点 {node_id} 沿 {direction} "
        f"方向刚度过小。请补充斜撑、加强支座约束或把滚轴改为铰接/固定，使结构几何不变。")


def check_mechanism(k_ff: np.ndarray,
                    free_dofs: np.ndarray,
                    nodes: Sequence[dict],
                    labels: Sequence[str],
                    dofs_per_node: int) -> Optional[str]:
    """兼容旧接口：稳定返回 None；机构或近机构都返回中文错误描述（求解前拦截用）。

    新代码（如 bridge 预检）请直接用 classify_stability 以区分 level。
    """
    return classify_stability(k_ff, free_dofs, nodes, labels, dofs_per_node).reason
