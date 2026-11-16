# Mesh Levels & Performance: A Deep Dive

> This document records the measured breakdown of "why the ultra level is ~9× slower than fine", as input for future optimization.
> For the summary and the four-level quick-reference table, see [performance-benchmarks.md](performance-benchmarks.md). Chinese version: [mesh-performance-analysis.zh-CN.md](mesh-performance-analysis.zh-CN.md).

## Measured Data

The same aquarium model (glass plate 600×400×8 mm), timings across the four levels on this machine:

| Level | Nodes | DOFs | Tetrahedra | Total time |
|---|---:|---:|---:|---:|
| coarse | 405 | 1,215 | 1,536 | 56 ms |
| medium | 1,575 | 4,725 | 7,056 | 353 ms |
| fine | 3,969 | 11,907 | 19,200 | 1.8 s |
| ultra | 10,933 | 32,799 | 56,448 | 17.4 s |

Ultra is **9.4×** the time of fine.

## Root Cause (three layers)

### 1. ~2.75× more unknowns

In-plane segmentation grows from 20 to 28 and thickness layers from 8 to 12, giving ~2.94× the element count of the fine level.

### 2. Fill-in of the sparse direct solver makes time grow super-linearly

We still use `spsolve` (sparse LU factorization). 3D tetrahedral meshes produce heavy **fill-in** during factorization; the cost grows roughly with DOFs raised to the power **1.5–2**.

Measured: DOFs 11,907 → 32,799 (2.75×), while solve time 649 ms → 6,403 ms (9.9×) — **most of the time is spent in matrix factorization**.

Assembly also grows proportionally (203 ms → 589 ms) but is negligible next to factorization.

### 3. Double partitioning doubles the work again

To eliminate diagonal artifacts, the same model is solved twice — once along the primary diagonal and once along the mirrored diagonal — and nodal results are averaged. Assembly, factorization and stress recovery are all ×2.

## Conclusions

- The fine → ultra timing jump comes mainly from **fill-in of the direct solver**, not from degraded code efficiency.
- **fine** is enough for everyday estimation; pick ultra only when you truly need the extra accuracy — it is noticeably slower on a phone.

## Possible optimizations (not implemented)

1. **Reduce segmentation**: drop the ultra level from `28×28×12` to `24×24×10` — roughly 60% of the elements, cutting time to about 1/3. The cost is slightly less contour detail; accuracy must be verified first.
2. **Switch solver**: for very large models, use a properly preconditioned iterative method (e.g. CG + incomplete-LU preconditioning), trading the fill-in cost of factorization for scalable iterative cost.

> Note: both options change contour detail. Run an accuracy regression against a fixed set of cases before implementing either.
