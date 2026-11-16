# FAQ

> English · [中文](faq.zh-CN.md)

## General

**What is PocketSim?**
An Android app that turns a sentence or a photo into a finite element model and solves it on the phone. It covers beams and frames, 3D solids, thin-walled containers, buried pipes, thermal history, wind and seepage.

**Is it free?**
Yes. The app is free to install. You pay only for the LLM API calls you make, to the provider you choose.

**Which Android versions?**
Android 8.0 (API 26) and newer, arm64 devices only.

**Does it work offline?**
Solving does. Building a model with AI needs a network connection to your LLM provider.

**Where is my data stored?**
Models and settings stay on the phone, in the app's private storage. The app has no server and collects nothing. Only the AI modelling step sends text and images — directly to the provider whose key you entered. See [why-pocketsim.md](why-pocketsim.md) for the privacy model.

## AI setup

**Do I need an API key?**
Yes, for AI modelling and follow-up questions. Manual mode (templates and parameter forms) works without one.

**Which providers are supported?**
Presets for Kimi, Doubao (Volcano Ark) and DeepSeek, plus a **Custom** channel that works with any OpenAI-compatible or Anthropic-compatible endpoint, including Claude relays. Pick a preset to fill in the Base URL and model name, then paste your key.

**What do I put in "API Base URL"?**
The part before the endpoint path, for example `https://api.deepseek.com` or `https://www.example.com/v1`. The app appends `/chat/completions` — or `/v1/messages` if the URL ends with `/messages`, or the domain suggests Anthropic. If you paste the full endpoint URL, it is used as-is and not appended twice.

**What is the "vision model" field?**
The model used when a message contains a photo. Leave it empty to reuse the main model. It is configured per provider, so changing it for Kimi does not affect Doubao. Fill it only if your main model cannot accept images.

**How much do the API calls cost?**
Depends on the provider and on how many candidates you generate. Settings → *first-round candidates* controls that (1–3). One candidate is cheapest.

**Kimi feels slow.**
Kimi always thinks. Two settings help: *reasoning effort* (`low` is fastest) and the high-speed toggle, which switches to the `-highspeed` model variant (roughly 2–3× faster, about 2× tokens). Also check *request timeout* — the app never extends it behind your back.

## Results

**Why a three-tier verdict instead of a number?**
Because the inputs are estimates. A safety factor of 2.4 sounds precise; with ±20 % on the load and ±30 % on the material it is not. *Safe / borderline / not recommended* is what the data actually supports.

**Why does my answer differ from ANSYS?**
Different element formulations, different mesh, different boundary idealisation. Beam modes match Euler–Bernoulli theory exactly; the 3D solid mode uses constant-strain tetrahedra, which are stiffer than reality in bending. Measured errors are in [validation.md](validation.md) — including the TET4 bending case, which is not flattering.

**The deflection from 3D solid mode looks too small.**
Expected. Constant-strain tetrahedra under-predict bending deflection; at coarse mesh it can be tens of percent. Raise the mesh level until the number stops changing, or use a beam mode for beam-like parts.

**Can I trust the stress contour?**
For trend and location of the peak, yes. For an absolute hot-spot value, refine locally first: a coarse mesh smooths stress concentrations.

## Errors

**`HTTP 404 unsupported_endpoint`.**
The endpoint path is wrong — usually a doubled suffix such as `/chat/completions/chat/completions`, or a relay that only serves `/v1/messages`. Set the Base URL to the part before the path (e.g. `https://host/v1`) and let the app complete it, or paste the exact endpoint your provider documents.

**Rate limit / `429`.**
The provider throttled the request. The app retries once after a short delay. If it still fails, lower the candidate count to 1 or wait.

**Request timed out.**
Raise *request timeout* in Settings, cut the number of photos, or switch to a faster model. Image requests are much heavier than text.

**The app aborts when I send a photo.**
Large base64 images plus a slow uplink are the usual cause. Send one photo at a time, or a smaller image.

## Scope

**Can I use it for a real building or a load-bearing structure?**
No. This is a teaching, DIY and estimation tool. It is not certified, not reviewed, and not a substitute for a licensed engineer. See the disclaimer in the app and in [limitations.md](limitations.md).

**Why can't I model an I-beam?**
Only rectangular and circular sections are implemented. Hot-rolled profiles, built-up sections and thin-walled open sections are not supported.

**Why can't I put a hole in a round tube?**
Holes are supported for rectangular members only.

**Can I import a STEP or STL file?**
No. Geometry comes from primitives, templates, or the AI-built model.

## Project

**Is it open source?**
The solver engine in `python/pocketsim/` is AGPL-3.0-or-later (plus a commercial option). The Android client is proprietary. Details in [NOTICE.md](../NOTICE.md).

**Can I contribute?**
Yes — see [CONTRIBUTING.md](../CONTRIBUTING.md). Case reports and validation data are especially welcome.

**Found a bug?**
Open an issue with the model JSON, a screenshot and the error text. The model JSON is what makes a bug reproducible.
