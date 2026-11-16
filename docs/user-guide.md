# User guide

> English · [中文](user-guide.zh-CN.md)

> **PocketSim in one line:** describe a part → check the model the AI built → run it → read a three-tier verdict.

## 1. Install and first run

1. Download the APK from [Releases](https://github.com/battleq/PocketSim/releases) and install it (Android 8.0+, arm64).
2. On first launch the app shows a short disclaimer. Read it — it defines what the results may and may not be used for.
3. Open **Settings** and configure an AI provider (section 3). You can skip this if you only use templates and manual parameters.

## 2. The home screen

| Area | What it does |
|---|---|
| **Describe your problem** | Type one sentence, e.g. "80 cm pine shelf on the wall, 10 kg of books at the far end — how much will it sag?" |
| **AI modelling** | Builds a model from your text and any attached photos |
| **Example scenarios** | Ready-made questions grouped by *structure / thermal / wind & seepage / 3D solid / thin-wall / buried pipe*. Tap one to fill it in. |
| **AI skills** | Optional presets: *conservative check* (worst-case load, conservative values) or *quick estimate* (simplified model, order-of-magnitude answer) |
| **Model selection / Material definitions** | Manual route: pick a template and enter parameters without AI |
| **Photo** | Attach one or more photos to the description |

Tips that make photo modelling noticeably better:

- One part per photo. A table full of clutter confuses the geometry extraction.
- Put something of known size in frame (a ruler, a coin, a standard brick) and mention it in the text.
- State the material and the load in words — "pine", "10 kg", "bolted at both ends". AI estimates these when missing, but your numbers beat its estimates.

## 3. Configuring AI

**Settings → provider preset** fills in a Base URL and model name; you paste the API key.

| Provider | Default Base URL | Notes |
|---|---|---|
| Kimi | `https://api.moonshot.cn/v1` | Always thinks; use *reasoning effort* and *high-speed* to control speed |
| Doubao (Volcano Ark) | `https://ark.cn-beijing.volces.com/api/v3` | Thinking disabled by default for speed |
| DeepSeek | `https://api.deepseek.com` | Thinking disabled automatically |
| Custom | any | OpenAI `/chat/completions` or Anthropic `/v1/messages`, including Claude relays |

Field notes:

- **API Base URL** — the part before the endpoint path. `…/v1` is enough; the app appends the rest. A full endpoint URL is also accepted and used as-is.
- **Model name** — exactly as the provider documents it.
- **Vision model** — used only when a message carries a photo. Stored per provider. Leave empty to reuse the main model.
- **Reasoning effort** — Kimi only: `low` fastest, `high` balanced (default), `max` strongest but slowest.
- **High-speed** — Kimi only: switches to the `-highspeed` model variant, roughly 2–3× faster at about 2× tokens.
- **Request timeout** — exactly what you set, in seconds. The app never silently extends it.
- **First-round candidates** — 1 to 3 models generated from the same input. 1 is cheapest and fastest.
- **Candidate divergence** — filters out candidates that differ too much from the baseline.

## 4. Modes

| Mode | Use it for |
|---|---|
| **Generic structure · auto** | Beams and frames — shelves, brackets, racks, rails, small frames (≤ 60 nodes, ≤ 80 members) |
| **3D solid** | Walls, slabs, glass panels, boxes, cylinders — anything where a surface is loaded |
| **Thin-wall container** | Cylindrical silos and rectangular tanks with equal wall thickness |
| **Buried pipe** | PVC-type pipes under soil and traffic load (engineering formulas, not FEM) |

Wind and seepage cases come in through the example list and the AI route.

## 5. Building a model

1. Type the question, attach photos if you have them, pick an AI skill if you want one.
2. Tap **AI modelling**. If something essential is missing, it asks instead of guessing — answer briefly and it continues.
3. You land on the **model preview**. Check, in this order:
   - **Geometry** — span, height, thickness. Wrong by 10× is the most common failure.
   - **Supports** — is the end really fixed, or just resting on something?
   - **Loads** — magnitude, direction and where they act.
   - **Material** — the modulus drives deflection almost linearly.
4. Fix anything wrong. For a field-level change, use follow-up ("make the span 1.2 m") rather than rebuilding.

## 6. Picking a candidate

When *first-round candidates* is above 1, you get up to three models built from the same input, each with a wireframe thumbnail. Pick the one that matches reality, then preview it. Going back from a preview returns to the picker, so you can compare.

## 7. Reading the results

| Element | How to read it |
|---|---|
| **Verdict** | *Safe / borderline / not recommended* — the headline, based on the safety factor |
| **Max deflection** | Compare it to something physical: 2 mm on a 800 mm shelf is invisible; 20 mm is not |
| **Stress contour** | Look at *where* the peak is and whether it sits at a support or corner (stress singularity) |
| **Member forces** | Axial, shear and moment per member — use these to size a section by hand if you want |
| **Mesh / DOF / solve time** | Shows what was actually computed |

Two habits that prevent most misreadings:

- A hot spot exactly at a fixed support or a re-entrant corner is often a **singularity**, not real stress. Blunt it in your head; don't design to it.
- Changing the mesh level and getting a very different number means the answer is **not converged**. Refine before trusting it.

## 8. Follow-up and recompute

**Continue asking / edit & recompute** changes fields, not the whole model. "What if the load doubles?" or "make it aluminium" re-solves with that one change. This is faster and more trustworthy than re-describing the problem.

## 9. AI optimization advice

**AI optimization advice** proposes structural changes — usually adding a brace, changing a section, moving a support — and verifies each one with the solver.

- It reports the problem, the scheme, the expected effect and the numbered change list.
- The before/after figure marks changes with numbered callouts; drag to rotate, pinch to zoom.
- **Export effect image (PNG)** saves the current view.
- A scheme that only changes the load or relaxes a support is flagged as invalid rather than presented as an optimization.

Use it as a prompt for your own thinking, not as a final design.

## 10. Export

- **Export PDF report** — model, loads, results and the verdict, for your own records.
- **Share** — shares a result image, watermarked "for reference only".

## 11. Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| `HTTP 404 unsupported_endpoint` | Endpoint path wrong or doubled | Put the Base URL up to `/v1` and let the app complete it |
| `429` / rate limit | Provider throttling | Retry; drop candidates to 1 |
| Request timed out | Slow model or heavy photo | Raise timeout, send fewer/smaller photos, use high-speed |
| App aborts when sending a photo | Large base64 upload on a slow link | One photo at a time |
| "Unknown error" | Provider returned an unreadable body | Check key, quota and model name |
| Model rejected before solving | Missing support / rigid-body mode | Add or fix the supports |
| Absurdly large displacement | Mechanism, or a unit mistake | Check supports first, then mm vs m |

## 12. Limits worth remembering

- Linear elastic only. No plasticity, contact, large deformation, dynamics or fatigue.
- Rectangular and circular sections only; no I/L/U/T profiles.
- 3D solid mode uses constant-strain tetrahedra: **deflection in bending is under-predicted**. Use beam modes for beam-like parts, or refine the mesh.
- Not for certification. See [limitations.md](limitations.md) and [validation.md](validation.md).
