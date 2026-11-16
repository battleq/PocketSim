# Validation against closed-form solutions

> English · [中文](validation.zh-CN.md)

> Every number below was produced by running the solver shipped in `python/pocketsim/`
> on **2026-11-16**, and compared against textbook closed-form solutions.
> Nothing here is estimated or rounded in the project's favour — including the cases that look bad.

## Environment

| Item | Value |
|---|---|
| OS | Windows (x86_64) |
| Python | 3.12.7 |
| scikit-fem | 12.0.2 |
| numpy / scipy | 2.5.2 / 1.18.1 |

The app runs the same solver code under Python 3.10 (Chaquopy). The measurements below were taken on a desktop interpreter, not on a phone; the numerics come from the same library APIs, so results should agree to numerical precision rather than being independently re-measured on device.

## Results

### Beam elements — 2D frame and 3D frame

Material: pine (E = 10 000 MPa) for 2D, mild steel (E = 206 000 MPa) for 3D. Section 25 × 40 mm (2D) and 50 × 100 mm (3D). Euler–Bernoulli theory, shear deformation neglected.

| Case | Closed form | PocketSim | Error |
|---|---|---|---|
| 2D cantilever, tip load P = 49 N, L = 300 mm | 0.33075 mm | 0.33075 mm | 0.00 % |
| 2D cantilever, UDL w = 0.2 N/mm | 0.151875 mm | 0.151875 mm | 0.00 % |
| 2D simply supported, mid load P = 49 N | 0.0206719 mm | 0.0206719 mm | 0.00 % |
| 2D simply supported, UDL w = 0.2 N/mm | 0.0158203 mm | 0.0158203 mm | 0.00 % |
| 3D cantilever, vertical tip load P = 49 N, L = 1000 mm | 0.0190291 mm | 0.0190291 mm | 0.00 % |
| 3D cantilever, lateral tip load P = 49 N | 0.0761165 mm | 0.0761165 mm | 0.00 % |

The zero error is expected, not impressive: the beam element implements the same Euler–Bernoulli theory the closed forms come from, so agreement is exact to floating-point precision.

### 3D solid (TET4, constant strain)

**Patch test — uniaxial compression.** Box 2.0 × 0.3 × 0.3 m, pressure 1 MPa on the free face, opposite face fully fixed, coarse mesh.

| Quantity | Closed form | PocketSim | Error |
|---|---|---|---|
| Max compressive stress | 1.000 MPa | 1.0064 MPa | 0.64 % |
| Reaction force Fx (A = 0.09 m²) | 90 000 N | 90 000 N | 0.00 % |

**Bending-dominated case — cantilever box.** 2.0 × 0.3 × 0.3 m steel box, fixed at one end, 1 kN tip load. Closed form δ = PL³/3EI = 0.0192 mm.

| Mesh level | PocketSim | Error |
|---|---|---|
| `coarse` | 0.00450 mm | **+76.5 %** |
| `medium` | 0.01020 mm | +46.8 % |
| `fine` | 0.01370 mm | +28.6 % |
| `ultra` | 0.01570 mm | +18.1 % |

This is the uncomfortable number, and it should be read carefully. Constant-strain tetrahedra are **too stiff in bending**: with only a few elements through the thickness, the linear strain field cannot represent a bending curvature, so the deflection is under-predicted. It converges, but slowly — halving the element size roughly halves the error.

What this means in practice:

- Deflection reported by the **3D solid** mode is a **lower bound** for bending-dominated parts (a wall under wind pressure is exactly that case).
- Use solid mode for **stress distribution and safety grading**, not for a precise deflection figure.
- For beam- and column-like parts, use the beam modes: they are exact for this class of problem.
- If you need a deflection number from solid mode, raise the mesh level and check that it stops changing.

### Shell (MITC4, Reissner–Mindlin)

Square plate, a = 1 m, t = 10 mm, E = 210 GPa, ν = 0.3, uniform pressure q = 1 kPa, 20 × 20 mesh. Flexural rigidity D = Et³/12(1−ν²).

| Case | Closed form | PocketSim | Error |
|---|---|---|---|
| Clamped edges, w = 0.00126 qa⁴/D | 6.552 × 10⁻⁵ m | 6.580 × 10⁻⁵ m | 0.43 % |
| Simply supported edges, w = 0.00406 qa⁴/D | 2.1112 × 10⁻⁴ m | 2.1157 × 10⁻⁴ m | 0.21 % |

The MITC4 shell is the accurate element in this project for plate and wall bending.

## Reproducing this

The closed-form cases above are asserted as unit tests in the repository:

```bash
cd python
python -m pytest tests/test_frame.py tests/test_frame3d.py tests/test_solid.py tests/test_shell.py -q
```

The TET4 bending sweep is not part of the regular test suite (it is a study, not an assertion). Reproduce it with:

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

## What is not covered here

- **Seepage, wind flow, thermal history, buried pipe.** These use engineering formulas and 2D field solvers rather than a closed-form benchmark; they are covered by self-consistency tests (equilibrium, monotonicity, conservation) in `python/tests/`, not by this table.
- **Stress concentrations.** TET4 on a coarse mesh will under-predict a peak; refine locally before trusting a hot-spot value.
- **Anything beyond linear elasticity.** Plasticity, contact, large deformation and dynamics are out of scope — see [limitations.md](limitations.md).

## Related

- [why-pocketsim.md](why-pocketsim.md) — what the project is for, and why a chatbot is not a solver
- [limitations.md](limitations.md) — supported elements, sections and loads
- [performance-benchmarks.md](performance-benchmarks.md) — mesh sizes and solve times
