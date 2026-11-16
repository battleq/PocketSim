# Performance Benchmark Report (On-Device Feasibility PoC Proxy Data)

> English · [中文](benchmark_report.zh-CN.md)

- Date: 2026-10-25
- Environment: Python 3.14.8 / numpy 2.5.3 / scipy 1.18.1 / scikit-fem 11.0.0
- Platform: Linux-6.18.5-x86_64-with-glibc2.39 (x86_64 Linux sandbox, CI environment)
- Case: cantilever shelf L=300 mm, t=20 mm, b=200 mm, pine (E=10 GPa), end load 98 N
- Elements: 6-node quadratic triangles (ElementVector(ElementTriP2), plane stress)

> ⚠️ Data comes from the x86 sandbox (CI environment), serving as **PoC proxy data** for Android
> on-device feasibility; ARM physical-device (Chaquopy) performance requires a dedicated
> follow-up device test (project Task 7). Empirically, ARM single-core floating-point performance
> is roughly 1/3–1/1 of this x86 environment, so timing estimates need a 1–3× conversion factor.

## Results

| Case | Mesh nx×ny | DOF | Solver | Assembly (s) | Solve (s) | Total (s) | tracemalloc peak (MB) | Process RSS peak (MB) | End deflection vs analytical |
|---|---|---|---|---|---|---|---|---|---|
| 10k | 110×11 | 10,166 | direct(spsolve) | 0.2 | 0.1 | 0.3 | 23 | 103 | 0.22% |
| 50k | 244×25 | 49,878 | direct(spsolve) | 0.7 | 1.2 | 2.0 | 115 | 308 | 0.23% |
| 100k-cg | 345×36 | 100,886 | cg+jacobi | 1.4 | 27.7 | 29.2 | 234 | 294 | 0.23% |
| 100k-direct | 345×36 | 100,886 | direct(spsolve) | 1.5 | 42.3 | 43.8 | 234 | 1088 | 0.23% |

## Conclusions

- **Time**: the heaviest case (100k-direct, 100,886 DOF, direct(spsolve)) totals **43.8 s**, ✅ within the ≤60 s budget.
  - 100k DOF **CG+Jacobi** (rtol=1e-8): total 29.2 s (solve 27.7 s), RSS peak 294 MB — both time and memory within budget; deflection error 0.23%, accuracy unaffected by the iterative method.
  - 100k DOF **direct** (spsolve + MMD ordering): total 43.8 s (✅ ≤60 s), but RSS peak 1088 MB ❌ **over the 1 GB budget** (sparse LU factors live in C-layer memory).
- **Memory**: the engine's default path (direct ≤50k DOF / auto CG above) peaks at **308 MB** RSS across all runs, ✅ within the ≤1 GB budget; the maximum over all cases (incl. forced direct) is 1088 MB. tracemalloc peaks at 234 MB (Python/numpy layer only, excluding sparse LU's C-layer allocations — use RSS for on-device assessment).
- **Accuracy consistency**: end deflection vs. Euler–Bernoulli analytical error is ≤1% on every mesh; refining the mesh does not change the conclusion (P2 elements converge well).

## On-Device (Android/Chaquopy) Deployment Notes

- This report is x86-sandbox proxy data; with a 1–3× time factor on ARM devices, the 100k-DOF CG path is expected at roughly 29–88 s — right at the 60 s budget edge. Default to the medium mesh level on-device (thousands of DOF, seconds) and offer the fine level on demand.
- 100k-scale DOF **must use the CG path** (the engine auto-switches above 50k DOF): at that scale the direct method's RSS already exceeds 1 GB (sparse LU factor memory); keep it only as a CG-failure fallback and use it at a lower mesh level.
