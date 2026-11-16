#
# Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
# SPDX-License-Identifier: BSD-3-Clause OR Commercial
#
"""合成场景验证：已知尺寸木板 + A4 参照纸，扫描不同拍摄角度。

合成方式：在平面上贴纹理（木墙 + A4 纸 + 木板），用针孔相机模型做透视投影
（真实单应），再叠噪声与模糊；然后跑 measure.py，比较测量值与真值。

运行：python3 bench.py
产物：/tmp/photomeasure/*.png（叠加可视化）、stdout 表格。
"""

from __future__ import annotations

import math
import os

import cv2
import numpy as np

from measure import measure, A4_MM

OUT_DIR = "/tmp/photomeasure"
S_TEX = 2.0                 # 纹理：2 px/mm
WIN = (-200.0, -200.0, 900.0, 900.0)   # 平面窗口 (x0,y0,x1,y1) mm
A4 = (0.0, 0.0, 210.0, 297.0)
CARD_MM = (85.6, 53.98)                # ISO/IEC 7810 ID-1（银行卡/购物卡）
PLANK = (300.0, 60.0, 700.0, 660.0)    # 400 × 600 mm


def _rot(axis: str, deg: float) -> np.ndarray:
    t = math.radians(deg)
    c, s = math.cos(t), math.sin(t)
    if axis == "y":
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def _texture(ref: str = "a4", radius_mm: float = 3.18) -> tuple[np.ndarray, tuple]:
    x0, y0, x1, y1 = WIN
    w = int((x1 - x0) * S_TEX)
    h = int((y1 - y0) * S_TEX)
    rng = np.random.default_rng(7)
    tex = np.zeros((h, w, 3), np.uint8)
    tex[:, :] = (96, 118, 138)                       # 木墙底色（BGR）
    grain = rng.normal(0, 9, (h, w, 1)).repeat(3, axis=2)
    tex = np.clip(tex.astype(np.float32) + grain, 0, 255).astype(np.uint8)
    for gx in range(0, w, 40):                       # 木纹竖条
        cv2.line(tex, (gx, 0), (gx, h), (78, 98, 116), 2)

    def to_px(x, y):
        return int((x - x0) * S_TEX), int((y - y0) * S_TEX)

    if ref == "a4":
        # A4 白纸 + 边框 + 几行“打印文字”
        p0 = to_px(A4[0], A4[1]); p1 = to_px(A4[2], A4[3])
        cv2.rectangle(tex, p0, p1, (246, 246, 246), -1)
        cv2.rectangle(tex, p0, p1, (70, 70, 70), 2)
        for i in range(1, 9):
            yy = p0[1] + int((p1[1] - p0[1]) * i / 10)
            cv2.line(tex, (p0[0] + 14, yy), (p1[0] - 14 - i * 8, yy), (120, 120, 120), 2)
    else:
        # ID-1 卡（可带圆角）+ 卡面深色图案；放在木板旁边（真实用法：卡贴着被测物）
        cw, ch = CARD_MM
        cxo, cyo = 200.0, 80.0        # 卡紧贴木板左侧（间隙 14 mm）——真实用法
        q0 = to_px(cxo, cyo); q1 = to_px(cxo + cw, cyo + ch)
        rr = int(radius_mm * S_TEX)
        cv2.rectangle(tex, q0, q1, (246, 246, 246), -1)
        if rr > 0:
            for cq in (q0, (q1[0] - 2 * rr, q0[1]), (q0[0], q1[1] - 2 * rr), (q1[0] - 2 * rr, q1[1] - 2 * rr)):
                cv2.rectangle(tex, cq, (cq[0] + 2 * rr, cq[1] + 2 * rr), (96, 118, 138), -1)
                cv2.circle(tex, (cq[0] + rr, cq[1] + rr), rr, (246, 246, 246), -1)
        cv2.rectangle(tex, to_px(cxo + 4, cyo + 4), to_px(cxo + cw - 4, cyo + ch * 0.75), (170, 170, 170), -1)
    # 木板（木色：高饱和，不会被误判为白纸）
    q0 = to_px(PLANK[0], PLANK[1]); q1 = to_px(PLANK[2], PLANK[3])
    cv2.rectangle(tex, q0, q1, (58, 104, 158), -1)
    cv2.rectangle(tex, q0, q1, (30, 62, 96), 3)
    for gx in range(q0[0] + 12, q1[0], 34):
        cv2.line(tex, (gx, q0[1] + 6), (gx, q1[1] - 6), (44, 84, 132), 3)
    return tex, (x0, y0)


def render(yaw_deg: float, tilt_deg: float = 0.0, width=1600, height=1200,
           f=1400.0, dist=1500.0, occlusion: float = 0.0, noise: float = 1.6,
           ref: str = "a4"):
    """渲染一张合成照片；返回 (图像, 真值, 木板四角投影)。"""
    K = np.array([[f, 0, width / 2], [0, f, height / 2], [0, 0, 1]], float)
    look_at = np.array([350.0, 330.0, 0.0])          # 纸与木板的中心
    R = _rot("y", yaw_deg) @ _rot("x", tilt_deg)
    center = look_at - dist * R[:, 2]                # 相机绕观察点公转，构图不跑偏

    def project(xy_mm):
        P = np.array([[p[0], p[1], 0.0] for p in xy_mm], float) - center
        Pc = P @ R                                  # 世界→相机（R 为正交阵）
        uvw = Pc @ K.T
        return (uvw[:, :2] / uvw[:, 2:3])

    tex, (tx0, ty0) = _texture(ref)
    win_mm = [(tx0, ty0), (tx0 + tex.shape[1] / S_TEX, ty0),
              (tx0 + tex.shape[1] / S_TEX, ty0 + tex.shape[0] / S_TEX), (tx0, ty0 + tex.shape[0] / S_TEX)]
    H = cv2.getPerspectiveTransform(np.array(win_mm, np.float32),
                                    project(win_mm).astype(np.float32))
    # 纹理像素 → 世界毫米：mm = px / S + 窗口原点
    M = H @ np.array([[1 / S_TEX, 0, tx0], [0, 1 / S_TEX, ty0], [0, 0, 1]], float)
    img = cv2.warpPerspective(tex, M, (width, height), flags=cv2.INTER_AREA,
                              borderValue=(40, 40, 40))

    if occlusion > 0:
        # 遮挡：在纸上盖一块深色物体（覆盖左侧 1/4 宽度）
        x0, y0, x1, y1 = A4
        occ = [(x0 - 5, y0 + 40), (x0 + (x1 - x0) * occlusion, y0 + 30),
               (x0 + (x1 - x0) * occlusion + 8, y1 - 20), (x0 - 5, y1 - 40)]
        pts = project(occ).astype(np.int32)
        cv2.fillPoly(img, [pts], (48, 52, 60))
        cv2.polylines(img, [pts], True, (20, 20, 20), 2)

    if noise > 0:
        img = np.clip(img.astype(np.float32) + np.random.default_rng(11).normal(0, noise, img.shape), 0, 255)
        img = img.astype(np.uint8)
    img = cv2.GaussianBlur(img, (3, 3), 0.6)

    plank_px = project([(PLANK[0], PLANK[1]), (PLANK[2], PLANK[1]),
                        (PLANK[2], PLANK[3]), (PLANK[0], PLANK[3])])
    truth = {"w": PLANK[2] - PLANK[0], "h": PLANK[3] - PLANK[1]}
    return img, truth, plank_px.astype(np.float32)


def run_case(name: str, yaw: float, occ=0.0, ref="a4", **kw):
    img, truth, plank_px = render(yaw, occlusion=occ, ref=ref, **kw)
    bbox = (plank_px[:, 0].min(), plank_px[:, 1].min(), plank_px[:, 0].max(), plank_px[:, 1].max())
    # 模拟“用户框选”：把真实外接框放大 8%
    dx, dy = (bbox[2] - bbox[0]) * 0.08, (bbox[3] - bbox[1]) * 0.08
    rough = (bbox[0] - dx, bbox[1] - dy, bbox[2] + dx, bbox[3] + dy)
    res = measure(img, rough_bbox=rough, ref_mm=CARD_MM if ref == "card" else A4_MM)

    if res.ok:
        ew = abs(res.width_mm - truth["w"]) / truth["w"] * 100
        eh = abs(res.height_mm - truth["h"]) / truth["h"] * 100
        err = max(ew, eh)
    else:
        ew = eh = err = float("nan")

    os.makedirs(OUT_DIR, exist_ok=True)
    vis = img.copy()
    if res.paper_corners.any():
        cv2.polylines(vis, [res.paper_corners.astype(np.int32)], True, (0, 255, 0), 3)
    cv2.rectangle(vis, (int(rough[0]), int(rough[1])), (int(rough[2]), int(rough[3])), (255, 160, 0), 2)
    cv2.imwrite(f"{OUT_DIR}/{name}.png", vis)

    print(f"| {name} | {yaw} | {'' if res.ok else '失败'} "
          f"| {res.width_mm:.0f} × {res.height_mm:.0f} | {truth['w']:.0f} × {truth['h']:.0f} "
          f"| {ew:.1f}% / {eh:.1f}% | q={res.quality:.2f} {res.reason} |")
    return err


if __name__ == "__main__":
    print("| 场景 | 偏航角° | 结果 | 测量(mm) | 真值(mm) | 宽误差/高误差 | 备注 |")
    print("|---|---|---|---|---|---|---|")
    errs = []
    errs.append(run_case("front", 0))
    for yaw in (15, 30, 45, 55):
        errs.append(run_case(f"yaw{yaw}", yaw))
    errs.append(run_case("yaw30_lowres", 30, width=800, height=600, f=700))
    errs.append(run_case("yaw30_occl25", 30, occ=0.25))
    errs.append(run_case("yaw70", 70))
    valid = [e for e in errs if not math.isnan(e)]
    print(f"\n有效样本 {len(valid)}/{len(errs)}，最大误差 {max(valid):.1f}%，"
          f"平均 {sum(valid)/len(valid):.1f}%")

    print("\n### 参照物换成 ID-1 卡（85.6×53.98 mm，圆角 3.18 mm，距离 1000 mm）\n")
    print("| 场景 | 偏航角° | 结果 | 测量(mm) | 真值(mm) | 宽误差/高误差 | 备注 |")
    print("|---|---|---|---|---|---|---|")
    card_errs = []
    for yaw in (0, 15, 30, 45, 55):
        card_errs.append(run_case(f"card_yaw{yaw}", yaw, ref="card", dist=1000))
    cv = [e for e in card_errs if not math.isnan(e)]
    if cv:
        print(f"\n有效样本 {len(cv)}/{len(card_errs)}，最大误差 {max(cv):.1f}%，"
              f"平均 {sum(cv)/len(cv):.1f}%")
