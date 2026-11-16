# PocketSim · Android

> English · [中文](README.zh-CN.md)

Turn everyday mechanics questions into a 30-second finite element simulation:
Kotlin + Jetpack Compose (Material3) for the UI, **Chaquopy-embedded Python 3.10 + scikit-fem**
for offline solving, with an optional OpenAI-compatible LLM for "natural language → parameters"
understanding and follow-up questions.
Two computation modes: **scenario templates** (nail-in-wall load / cantilever shelf /
simply supported laminated board — parameterized templates) and
**general structures** (new in v0.2.0: arbitrary bar/beam structures modeled directly by the AI
as a 2D frame model, solved locally after preview confirmation — a good fit for bookshelves,
tables, drying racks, etc.).
v0.3.0 adds: **photo-based modeling** (attach 1–3 photos of the structure; a vision model
recognizes it and builds the model, with unclear details confirmed through follow-up text
questions) and **result follow-up** (ask questions about the computed results, or have the AI
modify loads/positions/materials and recompute after confirmation). Since v0.3.1,
**general structures is the default mode and photo input has moved up to the home screen** —
you can attach photos while describing the problem; photos plus text together form the
complete input.

> ⚠️ **Disclaimer**: Simulation results in this app are for everyday-life reference only and
> must not be used for any professional engineering purpose (see the full in-app disclaimer and
> the persistent notice on the results screen; shared images automatically carry a
> "reference only" watermark).

---

## Tech stack & version rationale

| Component | Version | Notes |
|---|---|---|
| AGP | 8.5.2 | Compatible with Gradle 8.14; Chaquopy 17 supports AGP 7.3–9.4 |
| Gradle | 8.14 | See `gradle/wrapper/gradle-wrapper.properties` |
| Kotlin | 1.9.24 | Compose Compiler 1.5.14 pairs strictly with 1.9.24 (official compatibility matrix) |
| Compose BOM | 2024.06.00 | Material3 |
| Chaquopy | **17.1.0** | Latest stable release (2026-10), free and open source (Apache-2.0), resolved via Maven Central |
| kotlinx.serialization | 1.6.3 | Kotlin ↔ Python JSON contract |
| OkHttp | 4.12.0 | OpenAI-compatible `/chat/completions` |
| DataStore Preferences | 1.1.1 | Settings persistence |

**Version rationale for Chaquopy 17.1.0** (newer than the commonly assumed "16.x"):
- Official docs: https://chaquo.com/chaquopy/doc/17.1/current/index.html
- Supports AGP 7.3–9.4 (covers this project's 8.5.2), Python 3.10–3.15 (this project uses 3.10), minSdk ≥ 24 (this project: 26);
- Supports 16 KB page-size devices and Gradle configuration cache;
- Official Kotlin DSL form: `chaquopy { defaultConfig { version / pip { install(...) } }; sourceSets { getByName("main") { srcDir(...) } } }`.

---

## Project structure

```
pocketsim/android/
├── settings.gradle.kts            # Repository & module declarations
├── build.gradle.kts               # Root plugin versions (AGP/Kotlin/Chaquopy)
├── gradle.properties              # JVM args, AndroidX switches
├── gradle/wrapper/
│   └── gradle-wrapper.properties  # Gradle 8.14 distribution URL (wrapper scripts generated, see below)
├── docs/
│   └── solver-decision.md         # Solver selection decision record (Route A final)
├── README.md
└── app/
    ├── build.gradle.kts           # Chaquopy config + dependencies (core of this project)
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml       # INTERNET permission, FileProvider (image sharing)
        │   ├── java/com/pocketsim/app/
        │   │   ├── MainActivity.kt        # Single Activity + custom navigation stack
        │   │   ├── Models.kt             # Kotlin ↔ Python JSON contract (@Serializable)
        │   │   ├── PythonBridge.kt        # Chaquopy wrapper (FeaEngine interface)
        │   │   ├── AppContainer.kt       # Manual dependency container
        │   │   ├── SettingsRepository.kt # DataStore settings
        │   │   ├── LlmClient.kt          # OpenAI-compatible HTTP client (AiClient interface;
        │   │   │                         #   v0.3.0 multimodal messages + auto-switch to vision model when images attached)
        │   │   ├── AiParser.kt           # LLM output → structured parsing (template/frame modes, incl. one retry)
        │   │   ├── ChatSession.kt        # Conversation-understanding state machine (pure Kotlin, unit-testable; v0.3.0 sends with images)
        │   │   ├── FrameFollowUpSession.kt # Result follow-up state machine (v0.3.0: Q&A / modify-conditions / clarify branches)
        │   │   ├── FollowUpLogic.kt      # Missing-parameter follow-up strategy (≤2 items/round, ≤2 rounds)
        │   │   ├── MaterialLookup.kt     # AI web lookup of material parameters → store
        │   │   ├── ParamForm.kt          # Parameter confirmation form state (pure Kotlin, unit-testable)
        │   │   ├── SafetyLogic.kt        # Safety grading / source labels (pure Kotlin, unit-testable)
        │   │   └── ui/                   # Home/Chat/ParamConfirm/Result/Settings/ManualMode
        │   │                             # + FramePreview/FrameResult/FrameFollowUp (v0.3.0)
        │   │                             # + components/ (shared chat-bubble components)
        │   └── res/                      # strings.xml (Chinese copy), themes, adaptive icon
        └── test/java/com/pocketsim/app/  # JVM unit tests (no device required)
            ├── AiParserTest.kt           # Incl. frame-mode parseFrame/validateFrameModel cases
            ├── ModelsTest.kt
            ├── SafetyLogicTest.kt
            ├── ParserFlowTest.kt
            ├── ParamFlowTest.kt          # FeaEngine fake: param flow → solve → result interpretation + ChatSession frame mode
            ├── FollowUpSessionTest.kt    # v0.3.0: multimodal message shape, image sending, follow-up three branches
            └── UiScreenshotTest.kt       # Robolectric real rendering screenshots (replays real Python solve results)
```

**The Python sources are not in this directory**: in `app/build.gradle.kts`,
`chaquopy { sourceSets { getByName("main") { srcDir("../../python") } } }`
points to `pocketsim/python/` one level up in the repo (containing the `pocketsim` package,
delivered by the Python-side task).
The APK can still be built before that directory is in place, but entering a solve at runtime
will report an engine error.

---

## Build prerequisites

| Dependency | Requirement | Purpose |
|---|---|---|
| Android Studio | Koala (2024.1.1) or newer | Recommended IDE build path |
| JDK | 17 | Required by AGP 8.x |
| Android SDK | 34 (compileSdk/targetSdk) | SDK Platform 34 + Build-Tools |
| Local Python | **3.10** (major.minor must match the app's embedded version) | Since Chaquopy 17, `buildPython` enforces the same version; used for build-time pip resolution. 3.10 chosen because the scipy wheel (1.8.1) in Chaquopy's official repo supports at most cp310 |
| Physical device (recommended) | arm64-v8a, **8 GB+ RAM** | 100k-DOF solve target ≤60 s; emulators (x86_64) work but are slow |

### Generating the Gradle wrapper

The repo only commits `gradle-wrapper.properties` (wrapper scripts and jar are generated artifacts). Before the first build, pick one:

```bash
# Option 1: any Gradle 8.x installed locally
gradle wrapper --gradle-version 8.14

# Option 2: open the android/ directory in Android Studio; generate when prompted during Sync
```

---

## Build steps

```bash
# 1. Debug APK
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# 2. Install on a connected device
./gradlew installDebug

# 3. JVM unit tests (needs nothing beyond a device / SDK 34)
./gradlew testDebugUnitTest
```

### What happens on the first build (Chaquopy)

On the first (or post-clean) build, Chaquopy will:

1. Download the Android build of the **Python 3.10 runtime** (arm64-v8a + x86_64, ~30–60 MB each);
2. Resolve and download pip dependencies online: **numpy 1.26.2 / scipy 1.8.1** (Chaquopy official prebuilt Android wheels, cp310),
   **scikit-fem** (pure-Python package, PyPI wheel);
3. Package the `pocketsim` package from `../../python` into the APK's assets.

Total roughly **10–30 minutes and hundreds of MB of network & disk traffic**, a one-time cost;
subsequent incremental builds use the cache.
To build in an offline environment, first complete one build on a networked machine and bring
its Gradle cache over.

---

## Desktop UI simulation verification (no emulator/device needed)

`UiScreenshotTest` uses **Robolectric + Compose to render the real UI on the JVM and take
screenshots**; the replay data comes from `app/src/test/resources/snapshot/*.json` — real solve
results generated offline by the Python engine (`pocketsim.bridge`), so each screenshot =
real engine output × real UI rendering:

```bash
./gradlew :app:testDebugUnitTest
# Output: app/build/robo-screenshots/*.png (1080×2340)
# The build dir is wiped by clean; the project keeps a synced copy in ../screenshots/
```

Covered screens: Home / Manual mode / Parameter confirmation (prefilled with the original
3 kg-on-a-nail example) / Results (contour + metric cards) / Results with the plain-language
analysis expanded / Second scenario (cantilever shelf) / **Frame model preview (wireframe +
support/load symbols)** / **Frame results (metric cards + stress-colored deformed shape)** /
**Frame results scrolled down (member force details)** / **Frame result follow-up screen
(v0.3.0: summary card + guiding bubbles + input row)**.
To regenerate the replay data:

```bash
cd ../python && python -m pocketsim.cli demo-snapshots   # or generate manually per the UiScreenshotTest comments
```

> For an interactive preview: open this project in Android Studio, pick any `@Composable`
> function (e.g. `ResultScreen`) and click Split/Design next to the line number for a static
> preview; connect an emulator for full interaction.

## Deploying to a physical device

```bash
# Option 1: USB cable + adb (enable "Developer options → USB debugging" on the phone)
./gradlew installDebug          # or adb install app/build/outputs/apk/debug/app-debug.apk

# Option 2: transfer the APK directly
# Debug APK: app/build/outputs/apk/debug/app-debug.apk (both ABIs, ~92 MB, for debugging)
# Release APK: app/build/outputs/apk/release/app-release.apk (arm64 only, ~54 MB, formally signed)
# Send it to the phone (WeChat/cloud drive/LAN all work), tap to install in the file manager,
# allow "unknown sources" when the system prompts (paths vary slightly on HarmonyOS/ColorOS etc.).
```

Requirements: Android 8.0+ (minSdk 26), arm64-v8a (covers current mainstream devices), ~500 MB
free space (the app occupies ~300–400 MB installed, including the Python runtime and scientific
libraries). The first launch extracts the Python environment; a 3–8 s cold start is normal.

> **Switching from debug → release**: the two have different signatures, so uninstall any
> installed debug build before installing release (LLM keys etc. in settings must be re-entered
> once); future release-to-release upgrades can install over directly.
> The release signing key lives only in the local `keystore/` (gitignored, **never committed**);
> to produce a release APK from a fresh clone you must supply your own key — see the notes at
> the signing config in `app/build.gradle.kts`.

### On-device verification checklist (walk through this after installing)

1. **First launch**: open the app, grant storage permission (if prompted); wait for the Python
   environment to finish extracting (3–8 s on first launch).
2. **Reproduce the original example**: on Home, type
   "在墙上打进一个钉子，钉子上悬挂3KG重物，墙和钉子是否能够承重"
   ("drive a nail into a wall, hang a 3 kg weight — can the wall and nail bear it?").
   Without AI configured, tap "离线智能预填" (offline smart prefill) or
   "手动选模板 → 墙上钉挂重" (manual template → nail-in-wall load); confirm the parameter page
   prefills the hanging load at 3.0 kg (matching desktop screenshot 03), then tap
   "开始计算" (start calculation).
3. **Result check**: metrics should match the desktop — max deflection ≈ 0.06 mm,
   max stress ≈ 103 MPa, safety factor ≈ 2.29 (safe); the contour can switch between
   "等效应力/位移" (von Mises stress / displacement); expand "AI 分析过程" (AI analysis)
   for the plain-language reading.
4. **Performance**: from tapping "开始计算" to results should be < 10 s (medium mesh level);
   if the phone heats up noticeably or it takes over 60 s, record the device model and timing
   and report it.
5. **Optional**: configure an OpenAI-compatible LLM on the settings page (gear icon, top right),
   then go back to Home and type natural language directly to exercise the full
   AI-parse → parameter-confirm → solve chain.
6. **IME fix verification (v0.2.0)**: on the AI chat screen, tap the input box and type with a
   pinyin IME; the input box should move up with the keyboard and not be covered (same for the
   parameter-confirmation / settings / home input boxes).
7. **General-structure mode (v0.2.0)**: Home defaults to the "通用结构" (general structures)
   mode. Enter something like "用松木 30×40 木方做一个靠墙的单立柱双层书架，宽 400、高 1500，
   下层放一箱 15 公斤书，上层摆满约 25 公斤书" (a wall-mounted two-tier single-post bookshelf of
   30×40 mm pine battens, 400 wide × 1500 tall, a 15 kg box of books on the lower tier and
   ~25 kg of books on the upper tier). After AI modeling, the preview page opens: check the
   wireframe (members/supports/load symbols match your description) → start calculation → the
   results page should show the deformed shape (with a zoom slider), the safety grade, and
   member force details.
8. **Photo-based modeling (from v0.3.0; input moved to Home in v0.3.1)**: Home already defaults
   to the "通用结构" mode — while typing the description, tap "添加结构照片" (add structure
   photos) and pick 1–3 images (thumbnails can be removed via ×); tap "AI 理解" (AI understand)
   and the photos go out together with the text; the AI should model from the photos, and any
   dimensions/sections/materials it cannot see are confirmed via follow-up text questions.
   On the chat screen you can still tap "+" left of the input box to add photos. (The settings
   page needs a vision-capable model configured, e.g. Volcano Ark `doubao-seed-2.1-pro`; leave
   the vision model empty to reuse the main model.)
9. **Result follow-up (v0.3.0)**: at the bottom of the frame results page, tap
   "继续追问 / 修改重算" (continue follow-up / modify & recompute) →
   ① asking "安全系数是什么意思" ("what does safety factor mean?") should get a plain-language
   answer; ② saying "重量加到 30 公斤" ("increase the weight to 30 kg") or "换成钢材"
   ("switch to steel") — the AI modifies the model and jumps to the preview confirmation page
   automatically; after confirming, a new result is recomputed.

---

## LLM key configuration entry

Configured in the app's **Settings page** (gear icon at the top right of Home), persisted via
DataStore — no code changes needed:

- **API Base URL**: an OpenAI-compatible service address (the app appends `/chat/completions`),
  e.g. `https://api.example.com/v1`;
- **API Key**: Bearer authentication;
- **Model name**: e.g. `gpt-4o-mini`;
- **Vision model** (v0.3.0, optional): the multimodal model used when photos are attached;
  leave empty to reuse the main model. Vision models such as Volcano Ark `doubao-seed-2.1-pro`
  can be filled in with the model ID directly;
- Default mesh level, request timeout in seconds.

When unconfigured or offline, the AI chat screen prompts and guides you to the **manual template**
mode (fully functional, just without natural-language understanding).

---

## Python-side interface contract

Kotlin and Python exchange data strictly as **JSON strings** across the boundary (avoiding
dynamic-proxy type pitfalls); field definitions live in
`app/src/main/java/com/pocketsim/app/Models.kt` (`@Serializable` + `@SerialName`).

| Python entry | Signature | Returns |
|---|---|---|
| `pocketsim.bridge.list_templates` | `() -> str` | `[TemplateInfo, ...]` JSON array |
| `pocketsim.bridge.run_scenario` | `(request_json: str) -> str` | `FeaResult` JSON |
| `pocketsim.bridge.run_frame` | `(model_json: str, data_dir: str) -> str` | `FrameResult` JSON (2D beam-frame) |
| `pocketsim.bridge.list_materials` | `(data_dir: str) -> str` | `[MaterialRecord, ...]` JSON array |
| `pocketsim.bridge.upsert_material` | `(record_json: str, data_dir: str) -> str` | `MaterialRecord` JSON |
| `pocketsim.bridge.export_frame_pdf` | `(model_json: str, result_json: str, filepath: str, data_dir: str) -> str` | Exports a PDF report, returns the file path |
| `pocketsim.parser.parse_problem` | `(text: str) -> dict` | Parsed result dict (crossed via `json.dumps` on the Chaquopy side) |

`pocketsim.bridge` is an Android-only adapter layer (calling engine / materials / templates
internally): field names and units are mapped to the Kotlin model (e.g. `max_von_mises_mpa` →
`max_stress_MPa`, render data m → mm); solve failures are uniformly raised as `ValueError`
(translated to `FeaEngineException` on the Kotlin side).

The `run_scenario` request (Kotlin `ScenarioRequest` serialization):

```json
{
  "template_id": "cantilever_shelf",
  "params": { "length_mm": 400.0, "load_kg": 3.0, "material_name": "松木" },
  "mesh_level": "medium",
  "data_dir": "/data/user/0/com.pocketsim.app/files"
}
```

Key `FeaResult` fields: `metrics` (`max_deflection_mm` / `max_stress_MPa` / `safety_factor` /
`verdict`), `extra_checks`, `assumptions`, `render` (`nodes`/`tris`/`values`/`disp` contour
data), `timing`, `mesh` (incl. `adjusted` downgrade/refinement flag), `theory_check`
(analytical-solution self-check, target error ≤5%), `analogy`, `narrative` (plain-language
"analysis" paragraph list, generated offline), `params_effective`.
Safety grading thresholds are strictly identical to the Python side:
**sf ≥ 2.0 safe; 1.2 ≤ sf < 2.0 caution; sf < 1.2 dangerous**.

The `run_frame` (general-structures mode) request is a Kotlin `FrameModel` serialization
(coordinates in mm, x right, y up):

```json
{
  "name": "单立柱双层书架",
  "nodes":    [{"id": 1, "x": 0, "y": 0}],
  "members":  [{"id": 1, "i": 1, "j": 2, "width_b": 30, "depth_h": 40, "material": "松木"}],
  "supports": [{"node": 1, "type": "fixed"}],
  "loads":    [{"type": "force", "node": 2, "fx": 0, "fy": -294},
               {"type": "udl", "member": 1, "qy": -0.6125}]
}
```

Supports: `fixed` / `pinned` / `roller`; concentrated forces in N (downward = negative fy),
distributed loads in N/mm (downward = negative qy); temperature change `thermal` (°C, positive
delta_temp = heating, reference 20°C, e.g. "a steel pipe heated to 60°C" → delta_temp=40).
Key `FrameResult` fields: `max_disp_mm` (with `max_disp_desc` location description),
`max_stress_MPa` and `governing_member`, `safety_factor`/`verdict` (same grading as above),
`buckling_ratio`/`buckling_note` (Euler buckling utilization warning at ≥50%, dangerous at
≥100%), `thermal` (thermal analysis section: `has_thermal`/`max_delta_temp`/`over_temp`/`note`/
`by_member`; verdict="危险" (dangerous) when a member's temperature exceeds the material's heat
limit), `members` (per-member N/M/stress/utilization/`delta_temp`), `polylines` (deformed
polylines: original coordinates + displacement components, scaled up by the UI), `narrative`
(plain-language analysis paragraphs), `solve_ms`.
Before solving, Python validates reference integrity and mechanisms/insufficient constraints;
errors are raised as `ValueError` with Chinese messages.
Thermal precision boundary: uniform temperature per member; no conduction gradients, transients
or thermal shock.

The `parse_problem` return contract (offline rule-based parsing, no network or LLM key needed):

```json
{ "ok": true, "template_id": "cantilever_shelf",
  "params": { "length_mm": 400 }, "missing": ["load_kg"], "confidence": 0.86 }
{ "ok": false, "reason": "..." }
```

---

## Offline prefill & plain-language analysis

Two **fully offline** (no network, no LLM key) helper capabilities, both implemented by the
Python side inside Chaquopy:

1. **Plain-language analysis (`narrative`)**: the top level of `run_scenario`'s return adds
   `narrative`, a list of deterministically generated plain-language "analysis" paragraphs.
   The results page's "AI 分析过程" (AI analysis) card renders it paragraph by paragraph first;
   if empty it falls back to step text generated by on-device rule templates (older Python
   without the field is still compatible). The content is rule-based statements over the
   computed results — **not** generative-AI output — so it works offline and is stable and
   reproducible.

2. **Offline smart prefill (`pocketsim.parser.parse_problem`)**: when AI is not configured
   (button shown on Home) or the AI endpoint is unavailable (button shown in the chat error
   bar), a rule parser identifies the template and parameters from the user's description and
   jumps directly to the parameter confirmation page: extracted parameters carry the source
   label "离线识别" (offline recognition, `ParamSource.OFFLINE`, honestly distinguished from
   AI-extracted), parameters the parser did not obtain are marked "待补充" (to be filled in),
   and the user supplies or confirms them before calculating. On recognition failure
   (`ok=false` / unknown template / engine error), it prompts "未能识别，请手动选择模板"
   ("not recognized — please pick a template manually") and stays on the current page.

**Limits of rule-based prefill**: the parser is an offline ruleset based on keyword and numeric
extraction, covering only common Chinese phrasings of the built-in templates; when it cannot
recognize something or parameters are incomplete, it will not guess — either it prompts manual
template selection, or it marks missing items as to-be-filled on the parameter confirmation
page. For complex phrasing, still configure the AI endpoint (conversation understanding) or
use manual mode.

---

## Known limitations (v1)

1. **Physics scope**: linear elastic, 2D, static, small deformation. Scenario-template mode
   covers 3 templates (cantilever shelf / nail-in-wall load / simply supported laminated board);
   general-structures mode covers planar beam-frame structures (everyday structures with ≤25
   nodes / 40 members). AI modeling quality depends on prompt constraints; abnormal models are
   caught by the double safety net of manual preview confirmation + Python validation.
   3D and nonlinearity belong to later phases (evolution route: Route B in
   `docs/solver-decision.md`).
2. **scipy dependency caveats**: Chaquopy ships official prebuilt wheels for scipy, but **not
   for every version combination**. If pip resolution fails at build time, try in order:
   - a) Pin a version with a Chaquopy wheel available in `pip {}` of `app/build.gradle.kts`
     (availability list: https://chaquo.com/pypi-licenses/ );
   - b) Local wheel: `pip { install("scipy", "-r", "requirements.txt") }` or
     `install("/path/to/scipy-xxx-cp311-android.whl")`;
   - c) Switch to the pure-numpy solver branch (the Python side replaces scipy.sparse solving
     with numpy dense factorization, acceptable performance at 2D scale).
3. **R8/obfuscation**: disabled during the v1 skeleton phase (`isMinifyEnabled = false`); the
   rules file is ready (keep `com.chaquo.**`, kotlinx.serialization); regression testing is
   needed before enabling it later.
4. **Python solves cannot be cancelled mid-run** (C-level blocking); timeout protection is
   implemented by the Python side's automatic mesh downgrade, and the Kotlin side only cancels
   the wait.
5. **Results are for life reference only**: material parameters are representative engineering
   values (not guaranteed values), and boundary conditions are teaching-level simplifications.

---

## Unit tests

```bash
./gradlew testDebugUnitTest
```

Seven pure-JVM test classes (no Android framework or device dependency):

- `AiParserTest`: LLM-output parsing (valid/invalid JSON, schema validation, retry path) +
  follow-up trimming ≤2 + frame mode (`parseFrame` fence/field/ready validation,
  `validateFrameModel` all branches, retry path);
- `ModelsTest`: JSON deserialization of models like `FeaResult` (incl. `narrative`), with
  default-value tolerance, and the `parse_problem` success/failure contract;
- `SafetyLogicTest`: safety grading boundary values and source labels (incl. "离线识别" offline
  recognition);
- `ParamFlowTest`: a `FeaEngine` fake drives the full ParamConfirm → solve →
  result-interpretation chain, the `ChatSession` missing-parameter follow-up → completion →
  ready state-machine flow, and frame mode (follow-up → model ready → `onFrameReady` callback,
  guidance when rounds are exhausted);
- `ParserFlowTest`: a `ProblemParser` fake drives the full offline-prefill chain — template hit
  on successful recognition, parameter backfill, `OFFLINE` source marking, missing filtering and
  "待补充" marking, and the failure paths returning null for unrecognized / unknown template /
  engine exception;
- `FollowUpSessionTest` (v0.3.0): multimodal request-body shape (text-only without images /
  text+image_url array with images), `ChatSession` image sending and photos-only sending,
  `FrameFollowUpSession`'s three branches (pure Q&A / condition change emitting a complete new
  model callback / unclear-intent follow-up) + JSON validation failure guidance + free-text
  fallback + interface exception fatalError;
- `UiScreenshotTest`: Robolectric + Compose rendering 10 real 1080×2340 screenshots on the JVM
  (replaying real solve results generated offline by the Python engine, incl. four frame-mode
  shots: preview / results / member details / follow-up page; the results page is a LazyColumn,
  so member force details require scrollToNode before they can be captured).
