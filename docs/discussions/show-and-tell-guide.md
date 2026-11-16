# Show and tell posting guide

> English · [中文](show-and-tell-guide.zh-CN.md)

> Share the everyday mechanics cases you've solved with PocketSim so more people see
> that "you can run finite elements on a phone". Quality posts get added to the README
> case library, and their authors join the acknowledgments list.

## Why share

- Helps the author calibrate the solver's real-world limits (real scenarios are worth more than synthetic tests)
- Shows other users that "this scenario can be simulated too"
- A chance to be turned into a video by the official accounts (Bilibili / Xiaohongshu), with credit

## What a good Show and tell post looks like

### Recommended structure

1. **One-sentence scenario**: "Balcony drying rack holding 3 wet quilts — will it break?"
2. **Real photos** (1–2): let people see what the object is and its rough dimensions
3. **Model screenshot**: the model preview in the app (nodes, members, supports, loads)
4. **Result screenshot**: stress contour + safety factor + key numbers
5. **One-sentence conclusion**: "Safety factor 1.8 — fine for daily use; stacking a 4th quilt would be risky"
6. **(Optional) Cross-check**: comparison against hand calculations or other software, and the error

### Good example

> 📌 **"Fish tank glass 600×400×8 mm — will it crack when full of water?"**
>
> - Scenario: a 60 cm home aquarium, 8 mm glass, ~96 L when full
> - Model: bottom edges fully fixed; hydrostatic pressure (linearly distributed) on the panel
> - Result: max principal stress 18 MPa, safety factor 2.8 (tempered glass flexural strength ≈ 50 MPa)
> - Conclusion: safe for daily use; but a hard object striking the edges makes stress concentration spike.

### Not recommended

- Just a result image with no scenario description
- Serious engineering questions involving personal safety (consult a professional engineer — don't make decisions with this app)
- Content unrelated to PocketSim

## How to post

1. Go to Discussions → **Show and tell**
2. Click **New discussion**
3. Title format: `[Case] one-sentence scenario` (e.g. `[Case] Balcony drying rack holding 3 wet quilts`)
4. Write the body following the structure above, with images
5. Add labels (if available): `life-case` / `cross-check` / `thermal` / `wind-load`

## Criteria for joining the official case library

- A real scenario, with photos
- Clear model and result screenshots
- Plain-language conclusions, no exaggeration
- (Bonus) cross-checked with hand calculations or other software

Accepted authors appear in the "Community contributions" acknowledgments list in the README.
