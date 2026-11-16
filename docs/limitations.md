# Capabilities & Limitations

> For teaching, DIY and quick estimation only. **Not for formal engineering design.**
> Every item below reflects the current implementation under `python/pocketsim/`.
> Chinese version: [limitations.zh-CN.md](limitations.zh-CN.md).

## Material
- Linear elastic, small strain only (isotropic E, ν).
- Not supported: plasticity, large deformation, contact, friction, creep, fatigue, anisotropic materials.

## Analysis types
- Supported: linear static (2D frame, 3D beam, 2D/3D solids, thin-walled shells via MITC4); Euler buckling check; quasi-static thermal history; seepage (Darcy); flow; buried pipe ring deflection, buckling and ring stress (analytical, non-FEM).
- Not supported: dynamic time history, modal / frequency analysis, seismic response spectrum, nonlinear buckling, eigenvalue buckling, strong thermo-mechanical coupling, fluid–structure interaction.

## Sections & geometry
- Beam sections: rectangle (solid or equal-wall hollow) and circle (solid or tube) only.
- Not supported: hot-rolled sections (I, L, U, T), arbitrary or built-up sections, thin-walled open sections, variable sections.
- 3D solids: `box`, `cylinder` and `plate` primitives only.
- Not supported: holes, chamfers, fillets, multi-body assemblies, boolean operations, imported STEP/STL.
- Holes in members: rectangular members only. **Circular and tube members cannot have holes.**

## Elements
- 3D solids: 4-node linear tetrahedra (TET4), constant strain.
- 2D solids: 6-node quadratic triangles (P2).
- Beams: Euler–Bernoulli (shear deformation ignored); 3 DOF per node in 2D, 6 in 3D.
- Shells: 4-node MITC4 (Mindlin–Reissner) for flat-bottom thin-walled vessels (cylindrical silos, rectangular tanks), with through-thickness stress recovery.
- Not supported: TET10 quadratic tetrahedra, hexahedra, membrane elements.

## Boundary conditions
- Ideal supports only: fixed, pinned, roller (3D adds `roller_x` and `roller_y`). Solids support plane, profile and point constraints.
- All six rigid-body modes must be eliminated, otherwise the model is rejected at the modeling stage ([L-05] analytical rank precheck, no mesh needed).
- Not supported: elastic springs, rigid regions and MPCs, preload, constraint equations, prescribed displacements.

## Loads
- Supported: point force, uniform pressure, self-weight, hydrostatic pressure, soil pressure, wind pressure (wind speed or Beaufort → q = ½ρv²), uniform temperature change.
- Unit conversion (kg → Pa, Beaufort → wind pressure, water depth → hydrostatic pressure) is done by code; the AI only supplies raw quantities.
- Not supported: moving loads, cyclic or dynamic loads, heat-flux and convection boundaries, prestress, initial stress, concentrated torque on solids.

## Scale & solver
- 2D frame: nodes ≤ 60, members ≤ 80 (`MAX_NODES` / `MAX_MEMBERS`); thermal history: 5–40 time steps.
- 2D solids: DOF ≤ 50k uses a direct solver (`spsolve`); larger uses CG with Jacobi preconditioning.
- 3D solids: DOF threshold 30k; mesh levels `coarse / medium / fine / ultra` (fine and ultra may take seconds to tens of seconds on a phone).
- Post-solve checks: NaN or inf displacement (singular or under-constrained); maximum displacement above half the body diagonal (mechanism); support-reaction vs. load residual > 5% (error); CG non-convergence (lower the mesh level).
- Not supported: nonlinear iteration (Newton–Raphson), eigenvalue solvers.

## Post-processing
- Outputs: displacement (with the node of maximum displacement), von Mises and maximum principal stress, safety factor, reactions, contour plots. Frames: member forces (axial, shear, moment), deflection curve, Euler buckling check. Thin-walled shells: wall von Mises stress and tension/compression verdict.
- Stress singularities at constrained edges and corners are handled by volume-averaging only the inner elements, so contour plots avoid false hot spots.
- Not supported: mode shapes and animation, principal-stress trajectories, stress linearization (membrane/bending separation needed for pressure-vessel classification).

## AI modeling scope
- The AI builds models through a structured JSON contract, limited to: frames, 3D beams, wind-load walls, seepage, thermal history, 3D solid walls, generic 3D solids, thin-walled shells.
- The AI uses semantic anchors (`at = min / max`; coordinates are resolved by code) and leaves unit conversion to code. It must ask when material or load is unclear; it never defaults silently.
- AI output passes multi-layer validation (schema, physics semantics, rigid-body rank precheck). On failure it retries with a fix sheet that shows the actual value and the legal range (1 structural retry, 2 semantic retries).
- Explicitly unsupported by the AI, which asks instead (`ready = false`): holes, chamfers and horizontal pipes on generic 3D solids. The AI suggests switching to beam mode.
