# Performance Benchmarks

> Reference benchmark data for PocketSim's 3D solid mesh levels. For the measured breakdown and optimization ideas on "why ultra is 9× slower than fine", see [mesh-performance-analysis.md](mesh-performance-analysis.md). Chinese version: [performance-benchmarks.zh-CN.md](performance-benchmarks.zh-CN.md).

## Mesh Levels

3D solids (walls, generic box / plate / cylinder) offer four mesh levels: `coarse / medium / fine / ultra`. The level only determines the element count and DOFs — it is independent of the device model. Solve time varies with the processor; the app's results page directly shows "Mesh · Nodes/DOFs · Elements · Solve time".

The data below was **re-measured in an x86 CI sandbox** (2026-11-01, Python 3.14.8 / numpy 2.5.3 / scipy 1.18.0 / scikit-fem 11.0.0, single thread) to verify "how much fine differs from ultra"; on a phone, multiply by ~1–3×. The test & validation device is a **Honor X70** (MTN-AN80, Snapdragon 6 Gen 4, 12 GB + 16 GB intelligent RAM, MagicOS 10.0.0.175, 2640×1200).

## Cases & Scale

| Level | Wall 3.0×2.5×0.24 m (masonry, 1200 Pa) | Box 1.0×0.2×0.2 m (steel, 200 kPa) |
|---|---|---|
| coarse | 7,200 elements / 5,166 DOFs | 1,152 elements / 975 DOFs |
| medium | 15,360 / 10,530 | 6,912 / 4,725 |
| fine | 20,160 / 13,770 | 20,736 / 12,987 |
| ultra | 40,320 / 26,442 | 55,296 / 32,487 |

## Timing (x86 sandbox, assemble / solve / total)

| Level | Wall (ms) | Box (ms) |
|---|---|---|
| coarse | 66.8 / 48.3 / 210.2 | 8.4 / 5.1 / 32.2 |
| medium | 209.3 / 210.4 / 719.3 | 64.8 / 62.8 / 281.9 |
| fine | 302.0 / 443.5 / 1,336.6 | 183.9 / 446.4 / 1,411.3 |
| ultra | 726.3 / 1,898.6 / 3,727.7 | 530.3 / 9,594.5 / 15,642.0 |

## Conclusions

- **fine → ultra**: wall elements ×2.0, DOFs ×1.92, total time ×2.79 (1.34 s → 3.73 s); max stress 0.3977 → 0.3988 MPa (0.3% diff), max displacement 2.02 → 2.07 mm (2% diff). Refining to ultra gives little gain — **fine is enough for everyday estimation**.
- **Box ultra has a timing jump**: DOFs 32,487 exceeds the 30,000 threshold, so it auto-switches to CG+Jacobi iteration — solve 0.45 s → 9.6 s (total 15.6 s). This is a solver switch, not the mesh itself getting slower; boxes / plates / cylinders should stay at medium or fine.
- **Real-device estimate** (×1–3×): wall fine ~1.4–4 s, ultra ~4–11 s; box ultra ~16–47 s (a noticeable wait on a phone). Sandbox process peak memory for wall medium / fine / ultra is 210 / 265 / 461 MB, well under the 1 GB budget.
- Re-measure method: in the app, pick the same model, run fine and ultra, then copy "Mesh · Nodes/DOFs · Elements · Solve time" from the top of the results page to get your own device's numbers.
