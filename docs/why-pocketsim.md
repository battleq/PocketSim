# Why PocketSim exists

> English · [中文](why-pocketsim.zh-CN.md)

> **Short version:** a chatbot hands you a paragraph. PocketSim hands you a picture — a stress contour and a deformed shape computed by finite elements that mark where the danger is, plus a graphical reinforcement plan when the structure is at risk.

## Why has nobody shipped physics simulation as an app before?

Finite element analysis has driven industry for decades — car crashes, wing flutter, seismic design of bridges. Yet it never reached daily life, and nobody shipped it as a phone app.

The bottleneck is not compute. A phone from 2020 is faster than the workstation that ran a 1995 crash simulation. The bottleneck is **inputs**: turning reality into a model a solver can read is a skilled trade.

The trade means being fluent at 3D CAD modelling, knowing how to simplify a real structure to fit the mechanics assumptions, knowing elastic from plastic material behaviour, knowing which parameters an anisotropic material needs, and knowing what form of constraint each boundary condition should be idealised into. CAD only needs the shape to look right. CAE needs clean solid geometry, trustworthy material parameters, and correct boundary conditions. A real bookshelf has nail holes, wood grain, gaps and chamfers — "dirty geometry" that takes hours to repair. Is pine's modulus 8 or 12 GPa? How hard does a person actually lean on a railing? What safety factor?

Every input is a guess, so every professional workflow is built around tightening those guesses: metrology, material testing, standards, review. That is the right answer for a bridge. It is a wrong answer for "will this shelf hold 20 kg of books" — and for anyone without the training, the door is simply shut at the input stage. That is why physics simulation never became an app.

**PocketSim uses AI to solve the input problem.** The AI acts as the interpreter between a human and the physics-simulation algorithm: it turns the scene you look at and the sentence you say into a model — the input a solver understands.

## Why not just ask Doubao?

This is the first question everyone asks, and it deserves a straight answer.

A large language model is very good at **language**. It is not a solver. When you ask "will this 1.2 m aluminium tube bend under 5 kg", it will produce a confident paragraph — assembled from patterns in its training data, closer to **folk experience** than to your geometry. An LLM struggles to describe the actual stress and deformation state of the thing in front of you. Four things follow:

**1. It cannot compute the number you asked for.**
Deflection of a beam is a boundary-value problem. A model with no numerical solver has two options: recall a formula from memory, or interpolate something plausible. It cannot integrate the stiffness matrix of *your* section, *your* span, *your* support conditions. The more your case departs from a textbook formula — three members instead of one, a hole, a non-uniform load — the more the answer drifts into invention.

**2. The assumptions stay invisible.**
A chatbot answer hides its own model. You do not see which end it assumed fixed, whether it included self-weight, what modulus it picked. PocketSim makes those assumptions into fields on a screen: nodes, members, section, material, supports, loads. You can read them, disagree with them, and change one of them.

**3. Failures look the same as successes.**
A wrong chatbot answer has the same fluent shape as a right one. A finite element solver fails *loudly*: a rigid-body mode is reported as a singular matrix, an unbalanced model trips an equilibrium residual check, a mechanism shows a displacement larger than the part itself. PocketSim surfaces those failures instead of hiding them.

**4. The answer is text, not a picture.**
PocketSim computes with a finite element model and shows the result graphically: a stress contour and a deformed shape point out **where the stress and the deflection are largest and most dangerous**, not just one vague number. If the check finds a safety risk, it also proposes a reinforcement plan — what to change and how, shown graphically — and re-solves so you can compare before and after. That is a capability LLMs simply do not have: they can only hand you a paragraph.

### The division of labour

The expensive part of CAE was always the translation: photo → geometry → material → loads → supports. That is exactly what language models are good at.

| Step | Who does it | Why |
|---|---|---|
| Read a photo or a sentence, pick a structural idealisation | LLM | Language and visual understanding |
| Fill in material properties, propose load magnitudes | LLM + material library | Fuzzy input, conservative defaults |
| Assemble and solve the stiffness system | Solver (scikit-fem) | Deterministic arithmetic |
| Report stress, deflection, safety factor | Solver | Derived, reproducible quantities |
| Turn the numbers into plain language | LLM | Explanation, not computation |

The rule we follow: **the AI never produces a number that the solver did not compute.** It decides *what* to model, not *what the answer is*.

### When a chatbot is enough

Sometimes it is. Rules of thumb, code clauses, "how thick is a 2×4 actually", a single simply-supported beam with one point load — a chatbot handles that faster.

Reach for a solver when the geometry stops fitting a table: several members, mixed supports, a plate or a tank wall, a load that varies over the surface, or a case where you need to see *where* the stress peaks rather than a single number.

## Why bring FEM into everyday life

Because everyday decisions are made with no numbers at all.

People hang a 30 kg drying rack on two screws, put a 200 L aquarium on a plywood cabinet, drill a hole in a balcony slab. The usual method is "it felt fine last time". The alternative is not a certified calculation — nobody will pay for one on a shelf — it is a **cheap, honest, conservative estimate**.

PocketSim is built on three deliberate choices:

1. **Risk awareness, not certified precision.** Conservative inputs (lower strength, larger loads), a safety factor, and a three-tier verdict — *safe / borderline / not recommended* — instead of a stress figure presented as truth.
2. **Assumptions on screen.** Every model is editable. If the AI read your photo wrong, you can see it and fix it before solving.
3. **Limits written down.** The element types, the sections, the load cases and the measured error against closed-form solutions are all documented — see [validation.md](validation.md) and [limitations.md](limitations.md).

The underlying method is standard finite elements: discretise, assemble, solve, recover stress. Nothing exotic. A phone can do it in under a second for the sizes this app allows.

## What PocketSim deliberately does not do

- It is **not** a substitute for professional CAE or a licensed engineer. No plasticity, no contact, no dynamics, no fatigue, no certification output.
- It does **not** upload your geometry. Solving is local; only the AI modelling step talks to the LLM provider you configure.
- It does **not** claim accuracy it has not measured. The validation page reports the errors we found, including the ones that are uncomfortable.

> If you need a number you can sign, hire an engineer and buy professional software. If you need to know whether to worry before you drill the hole, that is the gap this project aims at.

## Related reading

- [validation.md](validation.md) — measured error against closed-form solutions
- [limitations.md](limitations.md) — supported element types, sections, loads
- [faq.md](faq.md) — common questions, including "why not just ask a chatbot"
- [performance-benchmarks.md](performance-benchmarks.md) — mesh sizes and solve times
