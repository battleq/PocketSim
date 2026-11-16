# PocketSim Licensing and Attribution Notice (NOTICE)

> English · [中文](NOTICE.zh-CN.md)

Copyright (c) 2024–2026 PocketSim 口袋仿真. All rights reserved.

This repository uses a **tiered license**: the open-source and proprietary parts have different scopes, as described in this file.
The `LICENSE` file at the repository root is the verbatim text of the GNU Affero General Public License v3.0 (AGPL-3.0).

## 1. Scope

| Path | License | Notes |
|---|---|---|
| `python/pocketsim/` | **AGPL-3.0-or-later** | The finite element solver engine (frame / frame3d / solid / flow / seepage / transient / bridge, etc.) |
| `python/materials.json`, `python/materials_us.json` | **AGPL-3.0-or-later** | Material data distributed with the engine |
| `python/tests/`, `python/examples/` | **AGPL-3.0-or-later** | Tests and example code |
| `android/` (incl. Kotlin/Compose sources, resources, build config) | **Proprietary (All Rights Reserved)** | Client UI, AI interaction, activation and quota logic, image recognition chain |
| `screenshots/`, `docs/`, `tools/` | **Proprietary (All Rights Reserved)**, unless otherwise marked | Documentation and showcase assets |

Each source file under `android/` carries the header `SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary`;
each source file under `python/` carries the header `SPDX-License-Identifier: AGPL-3.0-or-later`.

## 2. Additional Permission for the Engine (AGPL-3.0 Section 7)

As an additional permission under AGPL-3.0 Section 7:

- you may link, embed, or bundle the `python/pocketsim/` engine with independent modules and distribute the combined work under terms of your
  choosing (including closed-source applications), **without** licensing your own application code under the AGPL;
- however, this additional permission covers **only the linking boundary between the engine and other modules** and does not alter the engine's
  own AGPL obligations: if you modify the engine and distribute the result to others, you must still provide the complete corresponding source
  code of the engine under the AGPL;
- if you provide a network service through a modified engine, AGPL Section 13 still applies, and you must make the modified engine's complete
  corresponding source code available to users interacting with it remotely;
- this additional permission does **not** apply to the PocketSim client code under `android/`, which remains proprietary.

## 3. Commercial Licensing

The following are **outside** the scope of the AGPL license and require prior written commercial licensing (see `LICENSE-COMMERCIAL`):

- commercially distributing the engine or client in closed-source form, OEM pre-installation, or bundling;
- providing cloud / network services without undertaking the AGPL's source-availability obligations;
- removing or circumventing copyright, license, or trademark notices;
- using the names "PocketSim" / "口袋仿真" or its icons for promotion, or impersonating an official version.

Commercial licensing requires a separately signed written agreement; `LICENSE-COMMERCIAL` is only a statement of licensing policy and does not itself grant a license.

## 4. Trademarks

The names "PocketSim" and "口袋仿真", the logo, and the app icon are identifiers of PocketSim.
The open-source license grants copyright-level rights only and **grants no trademark rights whatsoever**. You may truthfully describe the software's origin,
but you may not release modified versions under the official name, nor use the official icons in a way that causes confusion.

## 5. Contributions

Submitting a pull request, patch, or code snippet indicates that you agree:

- your contribution is licensed to this project and all downstream users under AGPL-3.0-or-later;
- you additionally grant the PocketSim copyright holder a non-exclusive, perpetual, worldwide, royalty-free right to re-license,
  so that your contribution can be used in commercially licensed versions;
- you certify that the contribution is original or has obtained the necessary authorization, and does not infringe third-party rights.

## 6. Third-Party Components

- Third-party components such as Chaquopy (MIT), scikit-fem (BSD), SciPy (BSD), NumPy (BSD-3-Clause),
  AndroidX/Compose (Apache-2.0), OkHttp (Apache-2.0), kotlinx.serialization (Apache-2.0)
  remain under their own licenses and are unaffected by this file.
- The full list and license texts are available in the app under "Settings → Open Source Licenses" (viewable item by item),
  and the license texts are stored in `android/app/src/main/assets/licenses/`.

## 7. Disclaimer

This software is provided "as is", without warranty of any kind, express or implied. Simulation results are for everyday-life reference only
and must not be used for professional engineering, personal safety, or major property decisions.
See AGPL-3.0 Sections 15–17 and the in-app disclaimer.
