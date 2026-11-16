#
# Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
# SPDX-License-Identifier: BSD-3-Clause OR Commercial
#
"""单张照片尺寸估算原型：A4 纸做平面标尺 → 四点单应矫正 → 轮廓测量。

算法全部为开源实现：
  - OpenCV 4/5（Apache-2.0）：白纸检测、approxPolyDP 四边形拟合、getPerspectiveTransform、
    warpPerspective、Canny 边缘、minAreaRect。
  - 单应矫正 = 平面单视图度量（homography rectification，Criminisi 2000 的平面情形），
    无需相机内参、无需标定板。

用法（库）：
    m = measure(image_bgr, rough_bbox=(x0, y0, x1, y1))
    m.width_mm, m.height_mm, m.px_per_mm, m.paper_corners, m.quality

前提（物理限制，非算法限制）：
  1. 参照纸与被测物在同一平面上；
  2. A4 四角完整可见、不被遮挡；
  3. 平面与视线夹角 ≳30°（偏离正对 ≤60°），否则分辨率不足；
  4. 只能测投影尺寸：垂直该平面的厚度测不出。
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import cv2
import numpy as np

A4_MM = (210.0, 297.0)


@dataclass
class MeasureResult:
    ok: bool
    width_mm: float = 0.0
    height_mm: float = 0.0
    angle_deg: float = 0.0
    px_per_mm: float = 0.0
    quality: float = 0.0            # 0~1，纸张检测质量（四边形规整度 + 白度覆盖率）
    paper_corners: np.ndarray = field(default_factory=lambda: np.zeros((4, 2), np.float32))
    plane_bbox_mm: tuple = (0.0, 0.0, 0.0, 0.0)
    reason: str = ""


def _order_quad(pts: np.ndarray) -> np.ndarray:
    """四个角点按 左上→右上→右下→左下 排序（图像坐标系）。"""
    pts = pts.reshape(4, 2).astype(np.float32)
    s = pts.sum(axis=1)
    d = np.diff(pts, axis=1).ravel()
    return np.array(
        [pts[np.argmin(s)], pts[np.argmin(d)], pts[np.argmax(s)], pts[np.argmax(d)]],
        np.float32,
    )


def _refine_quad(contour: np.ndarray, quad: np.ndarray) -> np.ndarray:
    """用四条边的直线拟合求角点交点，抵消圆角/像素抖动带来的角点偏差。

    参照物（尤其银行卡）四角是圆角，approxPolyDP 得到的角点会系统性内缩；
    小参照物 + 远景时这种内缩会被透视放大成百分之几十的误差。
    改为对每条边只取“中段、贴近边线”的轮廓点做最小二乘直线拟合，
    再求相邻直线交点，可恢复真实直角顶点。
    """
    pts = contour.reshape(-1, 2).astype(np.float32)
    lines = []
    for i in range(4):
        a, b = quad[i], quad[(i + 1) % 4]
        d = b - a
        L = float(np.linalg.norm(d))
        if L < 1e-6:
            return quad
        d = d / L
        n = np.array([-d[1], d[0]])
        rel = pts - a
        t = rel @ d
        s = rel @ n
        sel = (t > 0.15 * L) & (t < 0.85 * L) & (np.abs(s) < 0.03 * L)
        if int(sel.sum()) < 10:
            return quad
        vl = cv2.fitLine(pts[sel], cv2.DIST_L2, 0, 0.01, 0.01).ravel()
        lines.append((np.array([vl[2], vl[3]], float), np.array([vl[0], vl[1]], float)))
    corners = []
    for i in range(4):
        p1, d1 = lines[i]
        p2, d2 = lines[(i + 1) % 4]
        den = d1[0] * d2[1] - d1[1] * d2[0]
        if abs(den) < 1e-9:
            return quad
        t = ((p2[0] - p1[0]) * d2[1] - (p2[1] - p1[1]) * d2[0]) / den
        corners.append(p1 + t * d1)
    refined = np.array(corners, np.float32)
    # 拟合失败保护：面积不许与初值差太多
    if cv2.contourArea(refined) < 0.6 * cv2.contourArea(quad):
        return quad
    return refined


def detect_paper(img: np.ndarray, min_area_frac: float = 0.003,
                 min_side_px: float = 30.0, min_quality: float = 0.55
                 ) -> tuple[np.ndarray, float, str]:
    """检测白色矩形参照物（A4 纸 / 银行卡）。返回 (四角点, 质量 0~1, 失败原因)。

    min_area_frac：参照物最小面积占全图比例（卡比纸小，默认放宽到 0.3%）；
    min_side_px：短边最少像素（再小就无法可靠标定）。
    """
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    # 白纸：亮且低饱和（纸张泛黄也能覆盖到 S<90）
    raw = cv2.inRange(hsv, np.array([0, 0, 150]), np.array([180, 90, 255]))
    # 形态学核必须小：卡只有 ~85×54 mm，7×7 闭运算迭代 2 次会把边缘推偏 2~3 px，
    # 对这个尺寸的参照物相当于 2~3% 的系统误差。
    mask = cv2.morphologyEx(raw, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8), iterations=1)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))

    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    img_area = float(img.shape[0] * img.shape[1])
    best = None
    for c in sorted(contours, key=cv2.contourArea, reverse=True)[:8]:
        area = cv2.contourArea(c)
        if area < min_area_frac * img_area:      # 太小：不是参照物
            continue
        peri = cv2.arcLength(c, True)
        for eps in (0.02, 0.03, 0.05):
            approx = cv2.approxPolyDP(c, eps * peri, True)
            if len(approx) != 4 or not cv2.isContourConvex(approx):
                continue
            quad = _order_quad(approx)
            # 规整度：凸四边形面积 / 最小外接矩形面积
            rect = cv2.minAreaRect(quad)
            rect_area = rect[1][0] * rect[1][1]
            if rect_area <= 0:
                continue
            if min(rect[1]) < min_side_px:       # 短边太小：标定误差会放大
                continue
            fill = area / rect_area
            # 白度覆盖率 + 亮度：四边形内白色像素比例、平均亮度
            poly = np.zeros(mask.shape, np.uint8)
            cv2.fillPoly(poly, [approx.astype(np.int32)], 255)
            inter = cv2.countNonZero(cv2.bitwise_and(mask, poly))
            white = inter / max(1, cv2.countNonZero(poly))
            mean_v = float(cv2.mean(hsv[:, :, 2], mask=poly)[0]) / 255.0
            if mean_v < 0.55:            # 纸面必须够亮（木色/深色物体不会被当成纸）
                continue
            quality = 0.35 * min(1.0, fill) + 0.25 * white + 0.40 * min(1.0, mean_v / 0.85)
            if best is None or quality > best[1]:
                best = (quad, quality, c)
            break
    if best is None:
        return np.zeros((4, 2), np.float32), 0.0, "未找到四边形白纸（亮度/遮挡/角度问题）"
    quad, quality, contour = best
    quad = _order_quad(_refine_quad(contour, quad))
    # 注意：不要用 cornerSubPix。参照物是圆角矩形时，圆角处存在大量角点响应，
    # 亚像素精修会把顶点吸到圆角上（实测卡片误差从 10% 恶化到 35%）。
    # 边线拟合（_refine_quad）已经用整条边的稠密点做最小二乘，精度更稳。
    if quality < 0.55:
        return quad, quality, f"纸张检测质量偏低（{quality:.2f}），尺寸误差会放大"
    return quad, quality, ""


def _plane_homography(quad: np.ndarray,
                      ref_mm: tuple = A4_MM) -> tuple[np.ndarray, tuple[float, float]]:
    """由参照物四角求「平面毫米坐标 → 图像像素」单应；返回 (H, 参照物毫米尺寸)。"""
    tl, tr, br, bl = quad
    long_mm, short_mm = max(ref_mm), min(ref_mm)
    # 照片里较长的一边对应参照物的长边（竖持相机 + 中等斜拍时成立；
    # 偏航只压缩水平方向，不会把长短边关系翻转）
    top = float(np.linalg.norm(tr - tl))
    left = float(np.linalg.norm(bl - tl))
    if top > left:
        w_mm, h_mm = long_mm, short_mm       # 横放
    else:
        w_mm, h_mm = short_mm, long_mm       # 竖放
    dst = np.array([[0, 0], [w_mm, 0], [w_mm, h_mm], [0, h_mm]], np.float32)
    H = cv2.getPerspectiveTransform(dst, quad)
    return H, (w_mm, h_mm)


def rectify(img: np.ndarray, quad: np.ndarray, bounds_mm: tuple,
            px_per_mm: float = 3.0, ref_mm: tuple = A4_MM) -> tuple[np.ndarray, np.ndarray]:
    """把图像矫正成「平面正视图」，画布覆盖 bounds_mm=(x0,y0,x1,y1)。

    返回 (正视图, H_mm2img)；正视图像素 = (平面毫米 - bounds 原点) × px_per_mm。
    """
    H, _ = _plane_homography(quad, ref_mm)
    x0, y0, x1, y1 = bounds_mm
    canvas_w = max(8, int(round((x1 - x0) * px_per_mm)))
    canvas_h = max(8, int(round((y1 - y0) * px_per_mm)))
    # 正视图像素 → 毫米（+bounds 原点）→ 图像像素；warpPerspective 需要 图像→正视图
    scale = np.array([[1.0 / px_per_mm, 0, x0], [0, 1.0 / px_per_mm, y0], [0, 0, 1.0]])
    M = np.linalg.inv(H @ scale)
    view = cv2.warpPerspective(img, M, (canvas_w, canvas_h), flags=cv2.INTER_LINEAR)
    return view, H


def _bbox_to_plane_mm(bbox_px, H):
    """图像 bbox → 平面毫米坐标的四角。"""
    x0, y0, x1, y1 = bbox_px
    pts = np.array([[[x0, y0], [x1, y0], [x1, y1], [x0, y1]]], np.float32)
    return cv2.perspectiveTransform(pts, np.linalg.inv(H)).reshape(4, 2)


def source_px_per_mm(quad: np.ndarray, ref_mm: tuple = A4_MM) -> float:
    """由参照物四角估计照片本身的分辨率（px/mm），用于选矫正视图分辨率，避免上采样。"""
    tl, tr, br, bl = quad
    long_mm, short_mm = max(ref_mm), min(ref_mm)
    w_mm, h_mm = (long_mm, short_mm) if np.linalg.norm(tr - tl) > np.linalg.norm(bl - tl) else (short_mm, long_mm)
    top = float(np.linalg.norm(tr - tl)) / w_mm
    left = float(np.linalg.norm(bl - tl)) / h_mm
    return max(0.2, min(top, left))


def measure(img: np.ndarray, rough_bbox=None, px_per_mm: float | None = None,
            ref_mm: tuple = A4_MM, min_area_frac: float = 0.003) -> MeasureResult:
    """完整测量：检测参照物 → 矫正 → 在矫正视图里精修轮廓 → 输出毫米尺寸。

    rough_bbox：被测物粗略包围框（用户点选/框选，图像像素）。为空时退化为整幅图。
    px_per_mm：矫正视图分辨率；默认按照片自身的 px/mm 自适应（避免上采样把边缘糊掉）。
    ref_mm：参照物真实尺寸（A4=210×297，银行/购物卡=85.6×53.98）。
    """
    quad, quality, reason = detect_paper(img, min_area_frac=min_area_frac)
    if quality <= 0:
        return MeasureResult(ok=False, quality=quality, reason=reason)

    _, (paper_w, paper_h) = _plane_homography(quad, ref_mm)
    if rough_bbox is None:
        rough_bbox = (0, 0, img.shape[1], img.shape[0])
    if px_per_mm is None:
        px_per_mm = float(np.clip(source_px_per_mm(quad, ref_mm), 0.8, 3.0))
    H, _ = _plane_homography(quad, ref_mm)
    mm_bbox = _bbox_to_plane_mm(rough_bbox, H)

    # 画布 = 纸张 ∪ 框选区域 + 余量
    pad = 40.0
    x0 = min(0.0, float(mm_bbox[:, 0].min())) - pad
    y0 = min(0.0, float(mm_bbox[:, 1].min())) - pad
    x1 = max(paper_w, float(mm_bbox[:, 0].max())) + pad
    y1 = max(paper_h, float(mm_bbox[:, 1].max())) + pad
    view, H = rectify(img, quad, (x0, y0, x1, y1), px_per_mm, ref_mm)

    ox0, oy0 = mm_bbox[:, 0].min(), mm_bbox[:, 1].min()
    ox1, oy1 = mm_bbox[:, 0].max(), mm_bbox[:, 1].max()
    vx0 = int(max(0, (ox0 - x0) * px_per_mm)); vy0 = int(max(0, (oy0 - y0) * px_per_mm))
    vx1 = int(min(view.shape[1] - 1, (ox1 - x0) * px_per_mm))
    vy1 = int(min(view.shape[0] - 1, (oy1 - y0) * px_per_mm))
    if vx1 - vx0 < 8 or vy1 - vy0 < 8:
        return MeasureResult(ok=False, quality=quality, reason="被测区域过小或超出矫正视图")

    roi = view[vy0:vy1, vx0:vx1]
    gray = cv2.GaussianBlur(cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY), (5, 5), 0)
    roi_area = float(roi.shape[0] * roi.shape[1])
    best = None
    # 对比度未知（木纹浅、墙面同色都可能），多档阈值取“最像物体外框”的闭合轮廓
    for lo, hi in ((30, 90), (15, 45), (60, 180), (90, 240)):
        edges = cv2.Canny(gray, lo, hi)
        edges = cv2.dilate(edges, np.ones((3, 3), np.uint8), iterations=2)
        edges = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, np.ones((9, 9), np.uint8), iterations=2)
        contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        for c in contours:
            area = cv2.contourArea(c)
            if area < 0.25 * roi_area or area > 1.05 * roi_area:
                continue
            rect = cv2.minAreaRect(c)
            if best is None or area > best[0]:
                best = (area, rect)
        if best is not None:
            break

    if best is None:
        # 轮廓没闭合：退回用框选范围（精度按框选精度）
        w_mm = (vx1 - vx0) / px_per_mm
        h_mm = (vy1 - vy0) / px_per_mm
        return MeasureResult(
            ok=True, width_mm=w_mm, height_mm=h_mm, px_per_mm=px_per_mm,
            quality=quality * 0.6, paper_corners=quad,
            plane_bbox_mm=(ox0, oy0, ox1, oy1),
            reason="未闭合轮廓，按框选范围估算",
        )

    rect = best[1]
    w_mm = min(rect[1]) / px_per_mm
    h_mm = max(rect[1]) / px_per_mm
    return MeasureResult(
        ok=True, width_mm=w_mm, height_mm=h_mm, angle_deg=rect[2], px_per_mm=px_per_mm,
        quality=quality, paper_corners=quad,
        plane_bbox_mm=(x0 + (vx0 + rect[0][0] - rect[1][0] / 2) / px_per_mm,
                       y0 + (vy0 + rect[0][1] - rect[1][1] / 2) / px_per_mm,
                       x0 + (vx0 + rect[0][0] + rect[1][0] / 2) / px_per_mm,
                       y0 + (vy0 + rect[0][1] + rect[1][1] / 2) / px_per_mm),
    )


def angular_resolution(incidence_deg: float) -> float:
    """解析：平面与视线夹角 θ 时，视线方向上的空间分辨率按 1/sinθ 劣化。"""
    return 1.0 / max(1e-6, math.sin(math.radians(incidence_deg)))
