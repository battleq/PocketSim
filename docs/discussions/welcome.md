# Welcome to PocketSim 👋

> English · [中文](welcome.zh-CN.md)

> **PocketSim**: an AI + finite-element "everyday mechanics verifier" on your phone.
> Snap a photo + one sentence, and AI builds a model that answers "will my drying rack pole break?".
> All mechanics calculations run locally on your phone; geometry and load data are never uploaded.

This is the project's official Discussions area — come here to chat, ask questions, and share your cases.

## A note from the maintainer

PocketSim is still in its early days: a sub-70-MB APK that handles modest beam, solid and vessel problems. Next to mature commercial FEA suites, it is a drop in the ocean — but a drop has never tried to outsize the ocean. Its point is different: when you wonder whether the balcony drying rack will hold or your aquarium glass is thick enough, you shouldn't have to open ANSYS and relearn FEA first. One photo, one sentence, an answer on the spot. With every release, this little drop catches a bit more of the ocean's light.

## What you can do here

| Category | Purpose |
|---|---|
| **📌 Announcements** | Releases, major updates, event notices (maintainers only) |
| **💬 General** | Chat, ideas, usage experiences |
| **❓ Q&A** | How-to questions and error help (please read the README first) |
| **✨ Show and tell** | Share everyday mechanics cases you've solved, with result images |
| **💡 Ideas** | Discuss new features, scenarios, and materials |
| **🛠️ Development** | Technical discussions on solvers, architecture, and contributing |

## Quick start

1. **Download**: get the latest APK from the [GitHub Releases](https://github.com/battleq/PocketSim/releases) page; grant camera permission after installing.
2. **Model**: pick a scenario template on the home screen, or just take a photo / write a description and let AI build the model.
3. **Get results**: tap "Run" — the local solver returns stress, deformation, safety factor, and a plain-language verdict.
4. **Follow up with AI**: on the results page you can ask "what if I hang twice the weight?" — it re-solves without rebuilding the model.

## Disclaimer (important)

Simulation results from this app are **for everyday reference only** and must not be used for
building structures, bridges, pressure vessels, aerospace, special equipment, or any other
professional engineering domain involving personal or property safety. For serious engineering
reports, consult a licensed engineer or use professional software/services.

## Privacy

- **BYOK mode**: AI modeling requires your own OpenAI-compatible API key; images/text are sent
  directly to the LLM provider you choose and **never pass through PocketSim's servers**.
- **Local computation**: all finite-element solving runs on your phone; geometry, material,
  and load data are never uploaded.
- **No data collection**: the app does not collect or store your models, photos, or API keys
  (the key lives only in the on-device DataStore and can be cleared at any time).

## Before you ask

- Read the [README](https://github.com/battleq/PocketSim#readme)
- Search existing Issues and Discussions for the same question
- Report bugs through the [issue templates](https://github.com/battleq/PocketSim/issues/new/choose),
  not in Discussions

## Code of conduct

Stay respectful and professional. Personal attacks, political disputes, and content unrelated to the project are not accepted.
