# Contributing

> English · [中文](CONTRIBUTING.zh-CN.md)

Thanks for your interest in PocketSim! This project welcomes all kinds of contributions, especially **material library data**, **scenario templates**, and **bug reports** — these are where the community can help fastest.

## What You Can Contribute

| Type | How | Notes |
|---|---|---|
| 🧱 New material data | Issue "Material data suggestion" | E, strength, density, linear expansion coefficient, max service temperature for GB / US / industry materials |
| 🏗️ New scenario template | Issue "New scenario request" | Everyday structural / thermal / seepage scenarios (e.g. "fish tank water pressure", "tent wind resistance") |
| 🐛 Bug report | Issue "Bug report" | Please include device model, Android version, steps to reproduce, and screenshots |
| 📖 Docs / translation | PR or Issue | Chinese–English alignment and polish for README, comments, and error messages |
| 🧪 Case validation | Discussion "Show and tell" | Cross-validate results against professional software (ANSYS/ABAQUS etc.), with comparisons |

## Contribution Process

1. **Search first**: confirm there is no duplicate in Issues and Discussions.
2. **Open an Issue or Discussion**: describe your idea/problem clearly, with the necessary screenshots.
3. **Discussion & confirmation**: maintainers reply within 3 business days; if it involves solver changes, discuss the approach before implementing.
4. **Submit a PR**: code contributions should be based on the `main` branch and pass all existing tests (`python -m pytest`,
   Android JVM unit tests).
5. **Sign the DCO**: commits must carry a `Signed-off-by` line (`git commit -s`),
   certifying that your contribution is original and that you agree to license it under the project's terms.

## Licensing and Re-licensing

By submitting code, data, or documentation you agree that:

- your contribution is licensed to this project and all downstream users under the license applicable to the target path (`python/` is AGPL-3.0-or-later,
  `android/` is proprietary, documentation is CC BY 4.0);
- you additionally grant the PocketSim copyright holder a **non-exclusive, perpetual, worldwide, royalty-free right to re-license**,
  so that your contribution can be used in commercially licensed versions;
- you certify that the contribution is original or has obtained the necessary authorization, and does not infringe third-party rights.

Material library data contributions (`materials.json` / `materials_us.json`) are subject to the same terms;
please cite the data source (standard number or reference) in the Issue to facilitate verification.

## Code Style

- Python: follow the existing style in `python/tests/`; functions require type annotations; new solver paths must come with tests.
- Kotlin: follow the existing style in `android/app/src/main/java/com/pocketsim/app/`;
  UI changes must include screenshots.
- Commit messages: Conventional Commits (`feat:` / `fix:` / `docs:` / `chore:`).

## Code of Conduct

Stay respectful and professional. This project does not accept personal attacks, political disputes, or content unrelated to the project.
Maintainers reserve the right to remove violating content and ban the accounts involved.
