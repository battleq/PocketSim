# Changelog

English version: [CHANGELOG.md](CHANGELOG.md) · 中文版：[CHANGELOG.zh-CN.md](CHANGELOG.zh-CN.md). Downloads: [GitHub Releases](https://github.com/battleq/PocketSim/releases).

## v1.2.35 (2026-11-16)

- Fix: several places still showed Chinese in the English UI — the 3D solid preview's constraint/load descriptions (min/max face, wind/hydrostatic conversions), the AI optimization "Changes" list (member modifications), and the Settings Kimi reasoning-effort section. All are now localized to English.
- Fix: the frame result screen's bottom bar was made more compact (the follow-up and back-to-home buttons share one row) so the conclusion scroll area keeps its height after the Back to Home button was added.

## v1.2.34 (2026-11-16)

- Fix: in the English UI, **AI Polish** now returns English text (it previously always replied in Chinese because the polish prompt was hard-coded). All other AI channels (modeling, follow-up rerun, optimization) were verified to already follow the UI language.
- New: a **Back to Home** button at the bottom of the frame result screen, clearing the navigation stack back to the home screen (not the new-project launcher).

## v1.2.33 (2026-11-15)

- Fix: calling Kimi models hosted on third-party gateways (e.g. Volcano Ark's `kimi-k3-1-pro`) no longer fails with HTTP 400 — the app now sends the `temperature=0.6` value these gateways require, omits the unsupported `thinking` field, and uses streaming for these always-thinking models.

## v1.2.32 (2026-11-15)

First public release.

- On-device FEA on Android: 2D/3D frames, 3D solids, thin-walled containers, buried pipes, thermal history, wind and seepage, solved locally with scikit-fem.
- AI modeling from a sentence or a photo; OpenAI- and Anthropic-compatible providers with per-provider vision models.
- AI optimization advice: before/after 3D figure with numbered callouts and PNG export.
- Bilingual UI (中文 / English) and dual units (metric / imperial) end to end.
- Home screen: the **AI modeling** button now sits directly below the input box, ahead of the optional modeling-parameters section.
