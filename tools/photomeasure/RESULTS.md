# Photo-Based Size Estimation Prototype: Algorithm & Validation Results

> English · [中文](RESULTS.zh-CN.md)

> Goal: one photo → the geometric size (in millimetres) of the measured object.
> Conclusion: **feasible**. Using an A4 sheet or a bank card (ID-1) as a planar ruler, four-point
> homography rectification then contour measurement in the front view:
> A4 at 0–55° oblique shots gives **2–4%** error; shopping/bank cards give **~6%** (max 7%).
> The accuracy ceiling is set by the reference object's pixel size — the card is 3.3× smaller
> than A4, so the error is roughly 2–3× larger.

## 1. Algorithm (fully open source)

| Step | Method | Library/License |
|---|---|---|
| Reference detection | HSV whiteness mask (V≥150, S≤90) → 3×3 close/open ops → `findContours` → `approxPolyDP` quadrilateral fit → convexity/fill-ratio/mean-brightness scoring | OpenCV 4/5, Apache-2.0 |
| Corner refinement | For each edge, take dense contour points in the middle 70% within <3% edge length of the line, `fitLine` least squares; intersect adjacent lines for right-angle vertices (cancels rounded-corner inset) | OpenCV, Apache-2.0 |
| Scale annotation | A4 = 210×297 mm; ID-1 card = 85.6×53.98 mm; the longer side in the photo maps to the reference's long side | ISO/IEC 7810 / geometric constraint, no patents |
| Planar rectification | Four-point homography `getPerspectiveTransform` + `warpPerspective`, outputs a "planar front view" | OpenCV, Apache-2.0; theory: planar single-view metrology (Criminisi 2000, planar case) |
| Object contour | Canny (adaptive multi-threshold) + dilation/close → outer contour → `minAreaRect` | OpenCV, Apache-2.0 |
| Ratio conversion | Resolution-adaptive "photo's own px/mm" from the front view, computed directly from the reference's pixel edge lengths | Custom code, no dependencies |

Code:
- `measure.py`: `detect_paper` / `_refine_quad` / `rectify` / `measure` (~220 core lines)
- `bench.py`: synthetic scene generation + angle sweep + error statistics (pinhole camera projection, true homography)

## 2. Synthetic-Scene Validation (ground truth 400×600 mm board, reference on the same planar wall)

### 2.1 A4 paper (210×297 mm)

| Scenario | Yaw angle | Measured (mm) | Error (W/H) | Paper quality |
|---|---|---|---|---|
| Frontal | 0° | 416 × 618 | 4.1% / 2.9% | 1.00 |
| Oblique | 15° | 409 × 612 | 2.2% / 2.1% | 0.99 |
| Oblique | 30° | 414 × 613 | 3.4% / 2.2% | 0.99 |
| Oblique | 45° | 416 × 614 | 4.0% / 2.4% | 0.98 |
| Oblique | 55° | 414 × 620 | 3.4% / 3.3% | 0.97 |
| Low resolution (800×600) | 30° | 408 × 622 | 1.9% / 3.8% | 0.99 |
| Paper corner occluded 25% | 30° | **failed** (four corners not detected) | — | 0.00 |
| Extreme oblique | 70° | 478 × 901 (degrades to bounding-box guess, **unreliable**) | 19.4% / 50.2% | 0.58 |

- Average error ~**3.0%** for normal 0–55° scenarios; degrades rapidly beyond 60°.

### 2.2 Shopping card / bank card (ID-1, 85.6×53.98 mm, 3.18 mm corner radius)

Card placed tight to the left of the object (14 mm gap), camera distance 1000 mm (card ~100×85 px in frame).

| Scenario | Yaw angle | Measured (mm) | Error (W/H) | Card quality |
|---|---|---|---|---|
| Frontal | 0° | 428 × 638 | 7.0% / 6.4% | 0.96 |
| Oblique | 15° | 427 × 601 | 6.8% / 0.1% | 0.96 |
| Oblique | 30° | 426 × 601 | 6.5% / 0.2% | 0.95 |
| Oblique | 45° | 418 × 601 | 4.6% / 0.2% | 0.92 |
| Oblique | 55° | 424 × 602 | 5.9% / 0.4% | 0.90 |

- 5/5 all detected successfully, average error **6.1%**, max 7.0%; error biased high (systematic
  +6% on width, from 2 px contour dilation), can be tightened later.
- Key handling for rounded-corner cards: **must** intersect fitted edge lines. Using
  `approxPolyDP` vertices directly insets by 1–4 px, with measured errors up to 30–90%; also do
  not add `cornerSubPix` — corner responses cluster at the rounded corners and subpixel
  refinement pulls vertices onto the arcs, worsening error from 10% to 35% in tests.
- Morphological kernels must be small (3×3): a 7×7 close iterated twice on a small card amounts
  to a 2–3% systematic bias at the edges.
- The smaller the card, the more the same corner pixel error is "extrapolated" by the homography
  (an 85 mm card extrapolated to 400 mm amplifies ~5×).

## 3. Shooting Requirements (hard constraints)

1. Reference and object on the **same plane** (e.g. A4 taped to a bookshelf side; shopping card
   next to the object — not floating or tilted);
2. All **four corners of the reference fully visible** — nothing pressing on or occluding the corners;
3. Plane-to-view angle ≳30° (**within ≤60° of frontal**); beyond that resolution is insufficient
   and error rapidly worsens;
4. With a shopping card: short edge **≥100 px** in the photo (about ≥1/12 of frame height),
   place it as close to the object as possible (within the same frame, nothing between them),
   otherwise extrapolation error amplifies;
5. Only **projected dimensions** are measured: thickness perpendicular to the plane cannot be
   measured — needs a second view or user input;
6. Phone main-camera distortion is small enough to ignore; wide-angle/macro lenses need
   distortion correction first.

### Reference object choice

| Reference | Size | Measured error | Best for |
|---|---|---|---|
| A4 paper | 210×297 mm | 2–4% | First choice for small/medium items (books, boxes, boards) |
| Shopping/bank card | 85.6×54 mm | 5–7% | Desk-scale small items, scenes where A4 doesn't fit; must be placed very close |
| Credit/membership card | Same ID-1 | Same as above | Same as above (all ISO/IEC 7810 ID-1) |

## 4. Integration Suggestions for the App (future)

- After the photo is taken, detect the reference (A4 or ID-1 card) first: on success, propose
  suggested sizes (e.g. "width 400 ± 15 mm, height 600 ± 20 mm"); in card mode loosen the
  tolerance to ±30 mm (~7%); the user can edit;
- On failure, fall back: the user taps the two ends of the object and enters one known edge
  length (single-view metrology);
- Feed the measurement as a "suggestion" into the AI modeling prompt — not a hard constraint,
  clearly labeled "estimated";
- On Android use the official AAR `org.opencv:opencv` (Apache-2.0, offline, no privacy
  exfiltration); no network needed.

## 5. Next Steps (real-device sampling first, recommended)

Synthetic images have clean geometry; real photos add shadows, glare, curled paper edges, card
reflections, lens distortion. Recommend collecting 15–20 real photos (half A4, half shopping
card, varied angles/lighting/materials), running the same script, and gathering success-rate and
error-distribution statistics before deciding whether to enter the app.
