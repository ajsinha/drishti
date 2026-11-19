<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
# Re-plan: release i7b as "the products, then Drishti proven on them"

**Status: plan for the owner, written 2026-11-19. Nothing built, nothing run, no decision taken.** It replaces the scope of
[I7B_PLAN.md](I7B_PLAN.md) (accepted 2026-11-17, 21 decisions); that document's analysis of eventing, role Homes and K5 stays valid
and is quoted here by section, not repeated.

**The owner's direction (2026-11-19).** (1) The Rachana-EL expansion (X1 to X50) and the Sutra expansion (55 additions and the
compatibility regime) are part of i7b. When i7b is done, Drishti Elements and Drishti Rachana are **products in themselves, 100%
complete**. Prebuilt **Sutra libraries** (fragments referenced, parametrised and used in an executable Sutra) ship in distinct
libraries such as core, finance and genomics. (2) i7b is **all about Rachana and Elements, and then their integration with
Drishti**, with **provable evidence** that Drishti has been migrated to the new Elements and the new Rachana. Everything else the
old i7b held goes to i8.

So the release has three parts: **A** Elements complete, **B** Rachana complete, **C** Drishti migrated onto both, with an evidence
pack. Read from git only: this worktree (`wip/i7b` at `1f2cf01a1`), `drishti-rachana` `main` at `f5fe5fe`, `drishti-elements`
`main` at `39ca755`, `design/eventing` at `a974666`, `design/views` at `c3cce3e`, `feature/events` at `8bbd95f` (the design merge
only: no library code exists yet).

**Documents this plan rests on (referenced, never copied).** In `drishti-rachana`: `docs/design/EL_EXPANSION.md` (X1 to X50,
XD1 to XD22), `SUTRA_EXPANSION.md` (the 55 additions, the regime, gates CG1 to CG10, Q1 to Q15), `SPLIT_PLAN.md`,
`COMPILER_AND_RUNTIMES.md`, `DATA_PROTOCOL.md`, `AI_GENUI.md`, `DECISIONS.md` (D1 to D32), `docs/plans/R0_R1_I8_PLAN.md` (F, P, H,
A, B increments; N1 to N14), `JS_PROOF.md`, `FRONTLOAD_REVIEW.md`, and the two being written in parallel: **`docs/design/LIBRARIES.md`**
(the library framework) and **`docs/design/LIBRARY_CATALOGUE.md`** (what each library holds). In `drishti-elements`:
`EVENTING_DESIGN.md` (branch `design/eventing`), `VIEWS_DESIGN.md` and `SUTRA_FOR_EVERYONE.md` (branch `design/views`).

Sizes are **builder-days (bd)**, the unit of every plan above. Grades: **O** Opus, **S** Sonnet, **F** Fable (design and review
only). At most **three Opus builders and one Sonnet at once**; heavy jobs one at a time.

## Contents

0. [The answer in one page](#0-the-answer-in-one-page) · 1. [What "100% complete" means](#1-what-100-complete-means-the-definition-of-done) ·
2. [The scope of i7b](#2-the-scope-of-i7b) · 3. [Part C: Drishti migrated, and the evidence](#3-part-c-drishti-migrated-and-the-evidence) ·
4. [Sizing and schedule](#4-sizing-and-schedule) · 5. [Risks, gates, tests, order](#5-risks-gates-tests-and-the-order) ·
6. [Decisions for the owner](#6-decisions-for-the-owner)

---

## 0. The answer in one page

| | Old i7b (accepted 11-17) | New i7b (this plan) |
|---|---|---|
| Holds | the events library, Drishti on `events.v1`, role Homes, K5, carry-overs, Rachana R0 | **A** Elements complete · **B** Rachana complete (R0, the split, the product, EL X1 to X50, Sutra 55 and the regime, the libraries, every runtime and host, Studio, AI) · **C** Drishti migrated onto both, proven |
| Size | 143 bd | **about 1,260 bd identified; plan on about 1,500** with design, release work and 15% contingency |
| Drishti drills | one | **three** (the split; live feeds on `events.v1`; the painter and the rest), a fourth only if one needed fixes |
| Calendar | about six working days | **about 108 working days** at the planning rate (range 63 to 188); seven milestones |
| Moves out | – | role Homes, K5, the carry-overs: to **i8**. GenUI inside Drishti stays i9 |

**Three things the owner should see before anything else.**

1. **It is ten times the release that was accepted.** The work is real and already designed in most places; the size is the sum of
   plans that were each written for a release of its own (R0 31, the split 55, the product 88, EL 391, Sutra 295) plus what no
   plan had sized (hosts, server, Studio, AI tooling, libraries, Elements completions, the migration and its evidence).
2. **Drishti gets no new feature for the whole of it.** Its tree changes three times, each time to stand on a product, each time
   with a drill and a published proof that nothing a user sees moved (except what is listed). Role Homes and K5 wait for i8.
3. **Three proofs decide the shape and all three run in the first milestone**: the JavaScript build (T1 to T10), the determinism
   probes (P2), the WebAssembly build for Python (W1 to W6). If the JavaScript proof fails, the plan changes that week, not in
   month three (section 5.1, G-A).

**The milestones** (section 4.4): **M0** proofs and foundations · **M1** the split, Drishti on pins (drill 1) · **M2** Rachana 1.0
and the painter complete (language 1.1) · **M3** language 1.2, Studio, core library · **M4** hosts, server, AI (beside M3) ·
**M5** language 1.3, finance library · **M6** language 1.4, genomics library, both products declared complete · **M7** Drishti
migrated: `events.v1` (drill 2), painter, protocol, Studio embed, level raise, fragments as libraries (drill 3), the evidence
report, tag `v2.9.11.i_7b`.

## 1. What "100% complete" means: the definition of done

A product is complete when every box below is ticked **on one tagged commit**, by a test or a generated artefact, not by a
statement. "All possible enhancements" is bounded in section 2.5: the exclusions there are what keep both products safe,
deterministic and one-way; they are part of the definition, not gaps in it.

### 1.1 Drishti Elements

| Area | Done means |
|---|---|
| **Libraries** | ☐ the eight of 2.4.0 · ☐ **drishti-events** complete per `EVENTING_DESIGN.md` (steps E1 to E12: API, three transports, sequence and replay, flow, tabs and the shared hub, offline queue, badge, inspector, mock server) · ☐ **drishti-views** complete per `VIEWS_DESIGN.md` (the painter, every kind, datasets, patches, live, controls and drills, `<elements-view>`, fluent builder, linter; not its Python and Java helpers: N11) · ☐ every painter capability the Sutra expansion classes as "ViewModel / painter" (containers, overlays, selections and brush, grid depth, in-cell visuals and display values, the chart grammar, the new kinds, windowed binding, canvas and print, context menus, key chords, drag and drop, theme variants) |
| **Contracts** | ☐ events wire protocol v1 in `CONTRACTS.md` with vectors and the runner `conformance.mjs --url` · ☐ the painter's copy of `viewmodel.schema.json` hash-equal to Rachana's · ☐ `<painter>.capabilities.json` generated · ☐ forward vectors (CG7): unknown field, kind, tone, control, effect, patch op, major |
| **Runtimes** | ☐ browsers: current and previous major of Chrome, Edge, Firefox, Safari, **tested in CI on Chromium, Firefox and WebKit** (today: Chromium only) · ☐ Node entry for the builder, validator and `sse()` · ☐ runs from `file://`, under strict CSP and Trusted Types, inside a worker where the design says so · ☐ inside Electron, JCEF and WebView2 (proved by Rachana's host SDK tests) |
| **Use from frameworks** | ☐ web components and plain classes (today) · ☐ tested examples for React, Angular and Vue in the gallery (no wrapper packages: section 2.5) |
| **Docs, gallery, playground** | ☐ generated `API.md` and `.d.ts` for every public symbol · ☐ USING, DESIGN, CONTRACTS, THEMING current · ☐ gallery page per component, the Eventing and Views sections, the benchmark page · ☐ design files deleted from the branches (one home per fact) |
| **Conformance and guards** | ☐ protocol vectors pass against the mock, the Java, Python and Node reference servers · ☐ no-host-words, no-CDN, one-version, licence, byte-budget, one-home guards · ☐ "a library's class names keep their rules" |
| **Performance** | ☐ events targets (design 9.4) · ☐ Views P1 to P10 and `tests/views_budget.json` in the gate · ☐ per-library byte budgets · ☐ budgets measured in three browsers, enforced in one |
| **Packaging and publishing** | ☐ the release tree and zip (today) · ☐ an npm package form built from the same tree (tarball; **built, not published**: decision PD14) · ☐ SHA-384 manifest · ☐ reproducible from the tag |
| **Licensing** | ☐ proprietary LICENSE (today) · ☐ `vendor/VENDOR.md` complete with notices · ☐ an SBOM generated per release |
| **Versioning** | ☐ one version for every library; semantic; `3.0.0` removes the old live names (accepted decision 2), the painter arrives as `3.1.0`; later minors add only · ☐ a release never changes · ☐ CHANGELOG |
| **Security** | ☐ strings are text, one element maker, both address allow-lists, bounds · ☐ the hostile corpus (ViewModels, frames, events frames) · ☐ events: auth, no secrets in addresses, origin checks · ☐ a written threat model · ☐ prepared for an outside review (PD15) |
| **Accessibility** | ☐ WCAG 2.2 AA per component: roles, names, keyboard, focus, live regions, contrast, reduced motion, a table or text alternative for every drawing · ☐ automated checks in the gate · ☐ an audit pass with its findings fixed |
| **Languages** | ☐ every built-in string from a message table · ☐ locale-aware formats from pinned data · ☐ right-to-left layout · ☐ English plus two proof locales, one right-to-left (PD16) |

### 1.2 Drishti Rachana

| Area | Done means |
|---|---|
| **Language** | ☐ `rachana: 1` at level **1.4**: SPEC, EBNF, JSON Schema, `rachana.lang.json`, `functions.yaml` generated and stale-checked · ☐ **X1 to X50** each with SPEC text, `since`, cases, catalogue entry · ☐ **the 55 Sutra additions** each with SPEC text, `since`, cases, compiler, binder, painter, a gallery example · ☐ levels 1.0 to 1.4 replayable (CG2) · ☐ no `rachana: 2` |
| **Compatibility regime** | ☐ language levels with no default in the Java API · ☐ minimal contract stamp · ☐ capabilities and the refuse / ignore-safe classes · ☐ reserved names and promotions · ☐ gates CG1 to CG10 on · ☐ the customer corpus mechanism · ☐ `rachana migrate` with a proof case per rule |
| **Libraries** | ☐ the framework of `LIBRARIES.md` (reference, parameters, versions, resolution, tests, generated reference pages) · ☐ the libraries of `LIBRARY_CATALOGUE.md`: **core, finance, genomics**, each fragment with a test, a gallery example and a doc entry · ☐ expression libraries (X42) by the same resolver |
| **Compiler and binder** | ☐ one source, the Java reference, in `java/rachana-{api,core,tools,data}`; no framework; the JDK allow-list guard · ☐ the binder, the patch model and the aggregate kernel in Core · ☐ profiles: `Profile.core()` and a host's |
| **Runtimes** | ☐ **JVM** (Java 21) · ☐ **JavaScript** through TeaVM: `rachana.runtime.js`, `rachana.tools.js`, in Node, Chromium, Firefox, WebKit · ☐ **Python** through WebAssembly under `wasmtime`: compile, lint, bind, no JVM, on Linux, macOS, Windows · ☐ every conformance case **byte-identical** in all of them |
| **SDKs** | ☐ Java: the public API, `rachana-data`, the host kit (serve Sutras and tables) · ☐ JavaScript: the facade, `<rachana-view>`, the table client, the bridge · ☐ Python: `drishti_rachana` with Flask and FastAPI adapters, streaming, compile and lint · ☐ "SDKs hold no semantics" guard |
| **Data Protocol** | ☐ v1 normative text and schemas · ☐ client, JSON and Arrow codecs, in-memory provider, CSV/JSON/NDJSON/Arrow readers · ☐ live over `events.v1` and the minimal SSE form · ☐ the in-process bridge transport · ☐ `protocol` cases pass against the standalone server, the Python adapters and Drishti |
| **CLI** | ☐ `rachana new, dev, lint, fix, fmt, build, preview, explain, schema, functions, migrate, test, doc, conformance, mcp` · ☐ launchers: npm (primary), PyPI, jar · ☐ `lint --format json` identical across the three (a conformance case) · ☐ SARIF |
| **Desktop hosts** | ☐ Electron (`drishti-rachana-electron`) · ☐ Swing over JCEF (`rachana-swing`; JavaFX as "renders and updates" only, D26) · ☐ .NET over WebView2 (`Rachana.WebView2`) · ☐ the same SDK surface on each; a web Sutra runs unchanged; the security rules of the design |
| **Server** | ☐ `rachana-server`: plain Java 21, one YAML file; Sutra store; file, JDBC and REST providers; the Data Protocol with live changes; pages; sign-in none, users, OpenID Connect; health and metrics · ☐ the reference host of the protocol's conformance |
| **Studio** | ☐ Rachana Studio (N14): layout, outline, grid mode, settings, YAML with problems and fixes, live preview, undo as an operation log, built only from Elements libraries on `rachana.tools.js` · ☐ the playground is the Studio on sample data · ☐ mock-ups first |
| **Editor support** | ☐ the language service (X48) · ☐ VS Code and IntelliJ integrations · ☐ formatter and linter (X47) |
| **AI** | ☐ the prompt kit generated from the catalogue (X49) · ☐ certain fixes · ☐ prefix-valid streaming compile · ☐ the evaluation harness, its task set, published figures meeting D30 · ☐ the generic MCP server (Node) · ☐ the tool pack for sajhamcpserver (Python on WASM) |
| **Docs, gallery, templates** | ☐ the docs site, the 15-minute tutorial with tested steps, the generated reference, the grammar card · ☐ ten templates, five starters · ☐ gallery with deliverables D1 (static) and D2 (Flask, FastAPI) |
| **Conformance** | ☐ coverage gate: every key, option, kind, function, format, tone, effect and code has a case · ☐ additive-only gate · ☐ differential fuzzing JVM against JavaScript · ☐ `rachana conformance run --cmd` for third parties |
| **Performance** | ☐ P1 to P11 of COMPILER_AND_RUNTIMES, a regression above 10% fails · ☐ EL test 6 (table work with the new verbs inside P4 to P7) · ☐ counted evaluation budgets (X45) |
| **Packaging and publishing** | ☐ `tools/release`: tag, build, manifest, release zip, refuses an existing tag and a missing CG8 report · ☐ jars, npm tarballs, wheels, NuGet package, **built and installable offline; public registries on the owner's word** (PD14) |
| **Licensing** | ☐ proprietary LICENSE · ☐ `LICENSE-SPEC.md` approved before the specification is published (N12) · ☐ notices for TeaVM's emitted class library, `wasmtime`, JCEF, WebView2, Electron, Elements' vendored engines · ☐ SBOM |
| **Versioning** | ☐ release `X.Y.Z` promises of the regime (6.4) · ☐ every release states the language levels and contract minors it implements · ☐ exact pins only |
| **Security** | ☐ no script, HTML, style or built address in a Sutra (section 2.5) · ☐ budgets end every evaluation · ☐ masked values never reach an output (a conformance kind) · ☐ linear-time regular expressions · ☐ hosts: page from the application's scheme only, no remote content · ☐ AI: handles only, never `url:` (D29) · ☐ a written threat model |
| **Accessibility and languages** | ☐ A1 and A2 of the Sutra expansion · ☐ X32 (languages) and X31 (Unicode) · ☐ Studio and the generated pages pass the Elements checks |
| **Public repository** | ☐ "names no host" guard on · ☐ **no Drishti data, Sutra or corpus in the repository** (PD8) · ☐ library data synthetic |

## 2. The scope of i7b

Every row is in. Sizes are from the named document where one exists; **(est.)** marks a size this plan made because no document
had one. Part C is detailed in section 3.

### 2.1 Part A: Drishti Elements complete

| # | Work | Source | bd |
|---|---|---|---|
| A1 | **drishti-events**, steps E1 to E12, releases `3.0.0-rc.1`, `rc.2`, `3.0.0` | I7B_PLAN 4.2 | 36.5 |
| A2 | **drishti-views, first usable release** (Views steps 1 to 8, 12, 13), `3.1.0` | R0_R1_I8_PLAN V1 to V5 | 35.5 |
| A3 | **drishti-views complete**: security and the hostile corpus (9), controls and drills (10), accessibility (11), linter (14), gallery section (18), speed (19), docs and guards (20). The playground (17) is Rachana Studio's | VIEWS_DESIGN 13.1 | 23 |
| A4 | Display values and tone expressions in the painter (X35's painter half) | EL_EXPANSION 5 | 6 |
| A5 | **Completions** (est.): Firefox and WebKit in CI 4 · message tables, locale data, right-to-left for every library 8 · accessibility audit and fixes 4 · npm package form and release tooling 3 · tested React, Angular, Vue examples 3 · print sheet 2 · budgets in three browsers 3 | this plan | 27 |
| | The painter work of the Sutra additions (containers, overlays, grid depth, chart grammar, kinds, windowed binding) | counted inside B6 | – |
| | **Part A** | | **128** |

### 2.2 Part B: Drishti Rachana complete

| # | Work | Source | bd |
|---|---|---|---|
| B1 | **R0**: P1 JavaScript proof, P2 determinism probes, H1 to H8 (runner, pinned cases jar, harvest, guards in report mode, language definition, `functions.yaml`, grammar proof, baselines, goldens), G1 review | R0_R1_I8_PLAN 8.2 | 31 |
| B2 | **The split** (was i8a): A1 to A16. Untangle inside Drishti, move with history (`v0.9.0`), Drishti on the pin, guards to fail, binder behind hooks and moved (`v0.10.0`), the JavaScript build (`v0.11.0`) | R0_R1_I8_PLAN 8.3 | 55 |
| B3 | **The product core** (was i8b, the Rachana side): B1 `data:` and the plan, B2 Data Protocol client, B3 `<rachana-view>`, B4 CLI and static mode (**D1**), B5 Python SDK (**D2**), B9 docs and `v1.0.0` | R0_R1_I8_PLAN 8.4 | 29 |
| B4 | **`events.v1` server halves as product code** (est.): Java (codec, session, subscriptions with wildcards, stream log, limits: what I7B_PLAN called `drishti-events-server`, S1 and S2) 6, Python 3, Node 2, each proven by Elements' vectors (PD7) | I7B_PLAN 3.1, 4.3 | 11 |
| B5 | **Rachana-EL X1 to X50**, five phases, levels 1.0 to 1.4 | EL_EXPANSION 5 | 391 |
| B6 | **The Sutra expansion**: the regime (23) and 55 additions (272), compiler, binder, painter, cases and documents together | SUTRA_EXPANSION 7 | 295 |
| B7 | **The Sutra libraries**: the framework 12, the core library 20, finance 16, genomics 12 (**placeholders**: `LIBRARY_CATALOGUE.md`'s count replaces them) | LIBRARIES.md, LIBRARY_CATALOGUE.md | 60 |
| B8 | **WebAssembly for Python** (est.): proof W1 to W6 4, the build, compile/lint/bind in the Python package, the runner on three operating systems 8 | COMPILER_AND_RUNTIMES 5 | 12 |
| B9 | **The standalone server** and the Java host kit (est.; a short design first) | COMPILER_AND_RUNTIMES 10 | 25 |
| B10 | **Desktop hosts** (est.): the bridge transport and page module 5, Electron 7, Swing over JCEF with the JavaFX check 11, .NET over WebView2 10 | COMPILER_AND_RUNTIMES 9 | 33 |
| B11 | **Rachana Studio and the playground** (mock-ups first) | R0_R1_I8_PLAN 4.8 (15 to 18) | 20 |
| B12 | **Editor integrations** on X48: VS Code, IntelliJ | SUTRA_FOR_EVERYONE 7.2 | 8 |
| B13 | **CLI completion** (est.): `migrate`, `test`, `doc`, `mcp`, SARIF, the PyPI and jar launchers | COMPILER_AND_RUNTIMES 8 | 5 |
| B14 | **AI beyond X49** (est. from SUTRA_FOR_EVERYONE 7.2 and AI_GENUI 8): certain fixes 4, prefix-valid streaming 5, evaluation harness and task set 6, generic MCP server 6, sajhamcpserver tool pack 5, "Describe" in Studio 5 | AI_GENUI | 31 |
| B15 | **Gallery, templates, starters, docs site, tutorial** | SUTRA_FOR_EVERYONE 7.2 | 17 |
| B16 | **Packaging and release tooling** for jars, npm, wheels, NuGet; SBOM; notices (est.) | – | 8 |
| B17 | **Security and accessibility assurance** (est.): differential fuzzing 5, threat model 2, Studio and generated pages audit 3 | – | 10 |
| | **Part B** | | **1,041** |

### 2.3 Part C: Drishti migrated onto both (section 3)

| # | Work | Source | bd |
|---|---|---|---|
| C0 | Baseline for the evidence, guards first, the evidence tooling and the report generator (est.) | this plan | 9.5 |
| C1 | Drishti on pinned Rachana, no compiler left: **inside B2** (A11, A12, A13, A14) plus the regime's Drishti side (corpus export, level and capabilities stated: inside B6) | – | – |
| C2 | **Live feeds on `events.v1`**: I7B_PLAN increments P0a, S3 to S8, C1 to C7 (S1 and S2 arrive as B4's jar) | I7B_PLAN 3.1, 4.3 | 34 |
| C3 | **Painter adopted** (B6), **Drishti serves the Data Protocol** (B7), **in-browser compile** on the named screens (B8, **D3**) | R0_R1_I8_PLAN 8.4 | 15 |
| C4 | **Studio embedded**, the Python designer deleted | R0_R1_I8_PLAN 4.8 | 5 |
| C5 | **Language level raised** to the released level with `migrate --check`; the profile drops the names Core now owns (XD10: `lag`, `rank`, `ytd`, `convert` …) with parity cases; `Rates` and extended `Calendars` hooks | EL_EXPANSION 5 ("about 10") | 12 |
| C6 | **Drishti's fragments on the library mechanism**: UI-kit and pack blocks become library entries behind the one resolver (est.; `LIBRARIES.md` decides the form) | – | 8 |
| C7 | Snapshot approvals, release preparation and review fixes for three drills | I7B_PLAN 4.5 | 9 |
| | **Part C** (beyond what B2 and B6 already hold) | | **92.5** |

### 2.4 What moves to i8, and what stays later

| To **i8** (nothing lost; I7B_PLAN's analysis stays its plan) | Was | bd |
|---|---|---|
| Lenses retired into role Homes (P0b mock-ups, H1 to H9), decisions 9 to 14, 16, 18, 21 | I7B_PLAN item 2 | 22.5 |
| K5: banking's kernel-internals allowance 41 → 36, decision 15 | item 3 | 2.5 |
| Carry-overs: drill lanes as systemd scopes (decision 17), `module verify` first-party by default, Download `.dmod` and its refusal test, genomics showcase padding, BI and role Homes | item 5 | 3 |
| `NetLinksTest` under load (decision 20); the genomics-vcf "not configured" alert | backlog | – |
| **To i9 (unchanged):** GenUI inside Drishti (AI_GENUI G6), built on the kit and tools that i7b ships | | about 8 |

i8 is then a small, purely Drishti release (about 28 bd and its drill). The drill-scope carry-over is the one item worth pulling
back if the owner wants steerable lanes for i7b's three drills (PD20).

### 2.5 Consciously left out of "all possible", and why

| Not in | Why | What covers the need |
|---|---|---|
| Everything in EL_EXPANSION section 4 and SUTRA_EXPANSION section 5 (scripts, loops, recursion, `eval`, I/O, HTML, CSS, colours, built addresses, implicit filter context, synonyms, writes from a document, solvers, pricing in Core …) | each one ends termination, complete checking, safety for model-written screens or byte-equal runtimes. Their absence **is** the product | the closed effect list, `let`, flows, components, host hooks and actions |
| `rachana: 2` | both expansions fit `rachana: 1` additively (XD22, Q14) | `MAJOR_2_CANDIDATES.md` keeps the list |
| Native (non-web) painters; native ports of the compiler | a second grid, pivot and chart stack per toolkit is dual machinery (D27); the `.wasm` loads in other languages | the open ViewModel specification and the conformance suite |
| JavaFX WebView at the performance budgets; CefSharp | fallback and alternative only (D26) | JCEF, WebView2 |
| ViewModel builders for Python and Java (Views steps 15, 16) | a second way to author (N11) | a Sutra |
| React, Angular, Vue wrapper packages; iOS and Android host SDKs | web components need no wrapper; no design exists for mobile hosts and the responsive painter serves phones | tested examples; `phone` and `tablet` tiers |
| A CDN of ours | the standing rule: vendored, never a CDN (ROADMAP R3's word "CDN" is to be struck) | release trees and packages |
| **Publishing** to public registries and **licensing tiers** | a commercial act, not engineering; i7b ends "ready to publish" | PD14 |
| An outside security review; a human screen-reader pass; translations beyond the proof locales | they need people or money outside the builders | prepared for, listed in the evidence as open (PD15, PD16) |
| Catalogue-driven inference | it reads Drishti's catalogue (D5) | light shape-only inference for Core is offered in PD17 |
| Any Drishti data, Sutra or pack content in `drishti-rachana` | the repository is public | synthetic data; the corpus in a private companion (PD8) |

## 3. Part C: Drishti migrated, and the evidence

### 3.1 What "migrated" means

| Claim | State at the i7b tag |
|---|---|
| Drishti **consumes released pins** | Rachana jars from `packaging/pinned/maven` at one version (`drishti.rachana.version`); the Rachana bundles and Elements under `web/static/vendor/…/<version>/`, one folder each; every hash equal to the product's release manifest; no branch, no snapshot |
| **No compiler in Drishti** | `compute/drishti-rachana` is the profile only (about, matcher, registry, wiring); the binder, patch model and list-aggregate kernel are Core's; the Python designer (a second editor of Sutra text) is deleted |
| **No painter in Drishti** | the console's renderer is an adapter over `<elements-view>` / `<rachana-view>`; its neutral drawing modules are deleted; the view-to-HTML transform of the console hop is deleted |
| **Live feeds on the new events library** | one events endpoint, every live feed a topic provider, the old channel, hub, relay and per-resource streams deleted (I7B_PLAN 3.1) |
| **Fragments on the library mechanism** | Drishti's UI-kit and pack blocks are library entries found by the one resolver |
| **The level is the released one** | Drishti states the language level and the painter capabilities of the release it pins; its profile registers no name Core owns |

**`events.v1` belongs in i7b (recommended, PD4).** Elements 3.0.0 removes `LiveClient`, `pipeToGrid`, `FeedMonitor` and
`SELECTION_EVENT`, so Drishti cannot vendor the new Elements without moving its live callers; and the painter's live patches run
only over drishti-events, so adopting the painter without it would need a bridge to the old channel: two transports, against the
motto. Deferring it would leave "migrated to the new Elements" untrue. It gets **its own drill**, before the painter, so a failure
is attributable.

### 3.2 The three Drishti steps

| Step | In milestone | What changes in Drishti | Language level | Elements vendored | Drill |
|---|---|---|---|---|---|
| **C-I The split** | M1 | H1 to H8 (test side), A1 to A9, A11 to A14: the compiler leaves; pins `v0.9.0` then `v0.10.0` | 1.0 | 2.4.0 (unchanged) | 1 → tag `v2.9.11.i_7b1` |
| *between* | M2 to M6 | **nothing merges to a release.** A branch `wip/i7b-integration` carries C2 and C3 forward against each product release candidate (CG8 runs on it); owner-directed work on `main` (the CSV loaders, fixes) merges forward | 1.0 | – | none |
| **C-II Live feeds** | M7a | C2: `events.v1` switch-over on the complete Elements; the console hop with the temporary view transform | 1.0 | the complete release | 2 → tag `v2.9.11.i_7b2` |
| **C-III Painter and the rest** | M7b | C3 painter, protocol, in-browser compile; C4 Studio embed; C5 level raise; C6 fragments; the transform deleted | the released level | the same | 3 → tag **`v2.9.11.i_7b`** |

Rules for the gap: Drishti's pin does not move between `v0.10.0` and M7 (each move costs a drill); every Rachana release
candidate still passes CG3 (the corpus) on every merge and CG8 (inside a Drishti checkout) before its tag, so the regime is
exercised about twenty times before Drishti relies on it. The pin guard's Elements comparison (R0_R1_I8_PLAN C9) compares
`formats.yaml` and `world-regions.yaml` **entries at Drishti's level**, not file hashes, or the first new format would trip it.
A11 still needs its quiet window (other Drishti work paused for that sitting).

### 3.3 The evidence pack

**The baseline** is tag `v2.9.11.i_7a`, captured in M0 before any change (C0): goldens, recordings, snapshots, page-load and
benchmark figures, line counts. **Two classes of proof**, never mixed: **exact** (bytes or numbers equal; any difference fails)
and **reviewed** (a difference is expected; every one is listed with its reason and nothing unlisted may differ).

| # | Claim | Proof | Class | Artefact |
|---|---|---|---|---|
| EV1 | The **503 shipped Sutras** and their blocks compile to the same model, plan and problem list; every reference and pack Sutra binds to the **same ViewModel and frames** | `ViewModelGoldenTest` (source mode and packaged) on goldens frozen at H8 from i7a code, after dropping `timings` and the two provenance times; **CG3** `CustomerCorpusTest` in Rachana's CI over `drishti-compat-corpus-<version>`; **CG8** gate report naming the Rachana commit and both Drishti commits. Run at C-I, C-II and C-III | exact | goldens' `MANIFEST.sha384` before and after; the CG8 reports; the corpus |
| EV2 | **Problem messages** unchanged | the documented bad Sutras' recorded lists at level 1.0 (C-I, C-II) | exact | the recorded lists |
| | … and after the **level raise** (C-III) | the same lists at the new level: only `expected`, near-match and "needs level" texts may differ | reviewed | the diff, each line classed |
| EV3 | **API answers unchanged** | `tests/answers/**` recordings re-run, `ErrorShapeTest`, `ViewModelSchemaTest`, `kernel-api-surface.txt` byte-equal across the split, `API_REFERENCE.md` regenerated | exact for existing paths | hashes before and after |
| | … added and removed paths | `/api/v1/events/**`, the Data Protocol endpoints added; the per-resource stream paths and `/embed/v1/channel` removed (accepted decisions 6, 7) | reviewed | the generated reference's diff |
| EV4 | **VaR and exposure figures exact** | `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyEquivalenceTest`, `PackagedVarGoldenTest`, the exposure golden tests (named in the pre-drill checklist), BI recordings; on the installed three-node stack: **167,372,692** (1-day 99.5%) and ES **210,131,972** | exact | test reports; the smoke log |
| EV5 | **Pages unchanged** | `test_page_walk_browser.py` (every page opens, no console error, the text search); `test_snapshots_*_browser.py` on the key pages | exact at C-I | the walk's page list; snapshot hashes |
| | … at C-II and C-III | the same tests; expected differences only: the status badge in place of the feed badge and banner (C-II), what the painter draws differently within the parity suite's limits (C-III). Each changed golden listed with its reason, approved once per step | reviewed | before, after and difference images of every changed page |
| EV6 | **Drishti holds no compiler and no painter of its own** | `RachanaCoreReachTest`, "no second copy", "public API only", the module-loader rule (fail mode); **new** `test_no_own_painter.py` (no drawing module outside the vendored folders; the renderer imports only the adapter) and `test_no_sutra_editor.py` (no YAML load-and-dump of a Sutra in Python); `test_one_transport.py` (G4); line counts from the file system | exact | guard reports; the count table (about 26,000 Java lines and the neutral renderer modules gone) |
| EV7 | **The pins are the released artefacts** | `RachanaPinGuardTest`, `test_rachana_pinned.py`, `test_elements_vendored.py`: versions, one folder each, SHA-384 of every file equal to the release manifests published by the product repositories; the tags exist | exact | the three manifests side by side |
| EV8 | **Live feeds are on `events.v1`** | `EventsConformanceTest` (vectors read from the vendored Elements, over SSE and WebSocket), `EventsGateParityTest` (who may hear what, equal to the old endpoints' refusals), `EventsIsolationTest`, `EventsClusterTest`; no `SseEmitter` outside the allow-list | exact | test reports; the parity table |
| EV9 | **Fragments are library entries** | the count of blocks before equals the library entries after; EV1 re-run after C6; one resolver (a guard) | exact | the mapping table block → library entry |
| EV10 | **Performance not worse** | H7 baselines against after: `HotPathBenchmark` within 3% (quiet machine), page-load figures, first paint / control change / one live patch on the named screens (every one at or better: B6's and B8's done-means), the drill budgets | exact against thresholds | the before / after table in `docs/admin/BENCHMARK.md` |
| EV11 | **The full drill is green** | one all-lanes drill per step on the frozen commit; the skip count equals the baseline's | exact | drill logs, lane summary |
| EV12 | **It works installed** | the smoke of I7B_PLAN 8.8 on a three-node stack built from the tree: events across nodes, two tabs one connection, an embedded view with a renewed token, the VaR figures | exact | the smoke log |

**Where it is published.**

| What | Where |
|---|---|
| The report | `docs/admin/PRODUCTS_MIGRATION_EVIDENCE.md`, **generated** by `tools/evidence/report.py` from the artefacts (never typed): one row per EV with pass or the listed differences, the three steps side by side, the versions and commits. Regenerated at each step; a stale file fails a test |
| The artefacts | `drishti-migration-evidence-<version>.zip` attached to the GitHub release of each of the three tags: manifests, CG8 reports, guard reports, test reports, snapshot before/after/difference images, benchmark raw figures, drill logs, with its own `MANIFEST.sha384` |
| The corpus | `drishti-compat-corpus-<version>`, produced by Drishti's release, held where PD8 decides; Rachana's CI replays it for ever after |
| Performance figures | `docs/admin/BENCHMARK.md` (the one benchmark document); the report links |
| The summary | `RELEASE_NOTES.md` (one paragraph and the link); the deck's chapter on the products |

The evidence tooling is written **first** (C0, in M0) and run against i7a itself to prove that "no difference" is reported when
nothing changed and that a planted difference is caught.

## 4. Sizing and schedule

### 4.1 The total

| Part | bd |
|---|---|
| A Elements | 128 |
| B Rachana | 1,041 |
| C Drishti migrated (beyond B2, B6) | 92.5 |
| **Identified** | **about 1,260** |
| Design not yet written (F): the server, desktop SDKs, Studio and workspace mock-ups, the chart grammar's detail, per-level SPEC reviews, seven pre-milestone reviews | 25 |
| Release-candidate stabilisation, 5 per tagged milestone (M1 to M6) | 30 |
| Contingency 15%: nine of the seventeen B rows are this plan's estimates; three proofs are unrun | 190 |
| **Plan on** | **about 1,500** |

For scale: i7a was 55 bd, the accepted i7b 143. By grade, roughly 55% is deep work (language semantics, the binder, the
painter's state and speed, the proofs) and 45% well-specified or mechanical (cases, hosts, adapters, documents, library content).

### 4.2 What a builder-day has been worth

| Measure | Rate | Source |
|---|---|---|
| i7a, observed | about 55 bd in one calendar day, three builders, front-loaded branches, mostly mechanical | I7B_PLAN 6 |
| The accepted i7b plan | about 24 bd a day; a **serial chain moves 4 to 5 bd a day** whatever the builder count | I7B_PLAN 6 |
| SUTRA_EXPANSION's own reading | 83 bd = "three builders, about six weeks" (about 3 bd a day) | its section 7 |

The documents disagree by a factor of eight, and nothing this size has been sustained. This plan uses **14 bd a day** across four
slots: below the i7b figure because more of the work is deep, every change runs in three runtimes, one machine carries the
browser suites, drills and benchmarks one at a time, and decisions and mock-ups wait for the owner. **The rate is measured at M0
and M1 and the forecast re-issued**; no date below is a promise before that.

### 4.3 Lanes and slots

| Lane | Owns (nobody else writes there) | Typical grade |
|---|---|---|
| **E** Elements | `drishti-elements`: events, the painter, completions, the painter half of Sutra additions | O for state, speed, security; S for kinds, gallery, docs |
| **L** language (EL) | `rachana-core` `el`, `el/check`, `agg`, `format`; `spec/1` expression parts; `conformance/1/el` | O |
| **D** document (Sutra) | `rachana-core` `model`, `parse`, `bind`, `view`; `rachana-tools`; `conformance/1/{sutra,problem,patch,edit}` | O |
| **H** hosts | `js/`, `python/`, `desktop/`, `dotnet/`, `java/rachana-{data,server,cli}`, the events server halves | S, O for the facade, push-down, streaming |
| **G** gallery and libraries | `gallery/`, `templates/`, the libraries, docs site | S |
| **X** Drishti | Drishti's tree (C-I, the integration branch, C-II, C-III) | O for A2, A5 to A9, A13, the console hop; S for the wide and mechanical |

Four slots: at most three on Opus, the fourth Sonnet; a slot with no deep work runs Sonnet (token economy). L and D share one
module, so each is a **single-writer chain**: at most one Opus in L and one in D at a time, the third Opus in E or X. Heavy jobs
(a drill, the three-browser conformance run, a benchmark, CG8, the WASM matrix) never overlap; they are queued at milestone ends
and at night. PD11 asks for a fifth, text-only slot.

### 4.4 Milestones

| | Holds | bd | Releases | Exit gate |
|---|---|---|---|---|
| **M0 Proofs and foundations** | B1 R0 (31) · EL phase 0: the 1.0 reservations and rules, X1, X2, X16, X18, X31, X45 (10) · regime before the first tag: levels, `since`, minimal stamp, capabilities, reserved names (8) · A1 E1 to E6 (19.5) · A2 Views 1 to 3 (11) · WASM proof (4) · C0 baseline and evidence tooling (3) | 86.5 | Elements `3.0.0-rc.1` | **G-A**: P1, P2 and W results written; goldens identical three runs; evidence tooling proven on i7a; G1 review |
| **M1 The split** | B2 A1 to A16 (55) · regime: corpus export, `RecordedHost`, `drishti_gate.py`, forward vectors (15) · A1 E7 to E12 (17) · A2 Views 4 to 8, 12, 13 (24.5) · C-I release work (3) | 114.5 | Rachana `v0.9.0`, `v0.10.0`, `v0.11.0` · Elements `3.0.0`, `3.1.0` · **Drishti `v2.9.11.i_7b1`** (drill 1) | **G-B**: EV1 to EV7, EV10 to EV12 for C-I; runtimes agree (JVM, Node, three browsers); guards in fail mode both sides |
| **M2 Rachana 1.0** | B3 (29) · B4 (11) · B8 build (8) · **language 1.1**: EL phase 1 (85), Sutra A1, T1, M2 (10) · A3 (23) · B13 (5) · templates and tutorial (8) · release tooling (4) | 183 | Rachana `v0.12` to `v0.14`, **`v1.0.0`** (levels 1.0, 1.1) · Elements `3.2.0` | **G-C**: D1 and D2 shown; conformance byte-equal in six runtimes (with Python); P1 to P11 and Views budgets in the gate; `LICENSE-SPEC` approved; **checkpoint 1** (rate, re-forecast) |
| **M3 Language 1.2** | EL phase 2 (140) · Sutra R2: L1, L2, C1, S1, S2, I1, I2, I3, D1, D4, F1, V2, V3, G1, M1, N1 (83) · A4 (6) · B11 Studio (20) · B12 (8) · B7 framework and core library (32) | 289 | Rachana `v1.1.0` (level 1.2) · Elements `3.3.0` | **G-D**: the screen of SUTRA_EXPANSION section 4 runs; Studio edits it; the core library's fragments each pass their tests; CG2 replays 1.0 and 1.1 |
| **M4 Hosts, server, AI** (beside M3) | B9 server (25) · B10 Electron, Swing, .NET (33) · B14 AI (31) | 89 | Rachana `v1.2.0` | **G-E**: one Sutra unchanged on the web, in Electron, JCEF and WebView2; `protocol` cases pass against the server and both Python adapters; D30's figures met or the kit held back; **checkpoint 2** |
| **M5 Language 1.3** | EL phase 3 (85) · Sutra D2, V1 (25) and the depth group (126) · B7 finance (16) | 252 | Rachana `v1.3.0` · Elements `3.4.0` | **G-F**: CG2 replays three levels; budgets hold with windowed binding and the chart grammar |
| **M6 Language 1.4, complete** | EL phase 4 (71) · Sutra P3 (28) · B7 genomics (12) · A5 (27) · B15 rest (9) · B16 rest (4) · B17 (10) | 161 | **Rachana `v1.4.0`** · **Elements `3.5.0`**: both declared complete | **G-G**: every box of section 1 ticked on the two tags; AI evaluation re-run on the 1.4 grammar |
| **M7 Drishti migrated** | C2 (34) · C3 (15) · C4 (5) · C5 (12) · C6 (8) · C0 rest (6.5) · C7 rest (6) | 86.5 | **Drishti `v2.9.11.i_7b2`** (drill 2), **`v2.9.11.i_7b`** (drill 3) | **G-H**: the evidence report complete for all three steps; nothing unlisted differs |
| | | **1,261.5** | | |

One language numbering serves both expansions (PD6): level 1.1 = EL phase 1 with A1, T1, M2; 1.2 = phase 2 with the sixteen R2
items; 1.3 = phase 3 with D2, V1 and the depth group; 1.4 = phase 4 with the P3 group. Inside M3 and M5 the level is built as
alphas by theme (state and effects; containers and overlays; tables and windows; grids and selections) and cut once.

### 4.5 Calendar

Working days from a start on 2026-11-20, overheads spread pro rata. The middle column is the plan; the others bound it.

| End of | at 24 bd a day (best seen) | **at 14 bd a day (plan)** | at 8 bd a day |
|---|---|---|---|
| M0 | day 4 | **day 7** (30 Nov) | day 13 |
| M1 | day 10 | **day 17** (14 Dec) | day 30 |
| M2 | day 19 | **day 33** (5 Jan) | day 57 |
| M3 and M4 | day 38 | **day 65** (18 Feb) | day 114 |
| M5 | day 50 | **day 86** (19 Mar) | day 151 |
| M6 | day 58 | **day 100** (8 Apr) | day 175 |
| M7, tag `v2.9.11.i_7b` | day 63 (16 Feb) | **day 108 (20 Apr 2027)** | day 188 (10 Aug) |

Dates are for five-day weeks; working every day brings the plan column's end to about 7 March 2027. M0 cannot be shorter than
the proof's time-box (P1 is five days of light machine use) and M1 not shorter than the split's chain (29 bd serial, about seven
days), whatever the rate. i8 (role Homes, K5, carry-overs) follows the tag.

### 4.6 Waves inside a milestone

| | Opus 1 | Opus 2 | Opus 3 | Sonnet |
|---|---|---|---|---|
| M0 | **P1 → P2 → WASM proof** | events E2 → E3 → E5 → E6 | painter 1 → 3; then 1.0 reservations (X1, X2, X31) into SPEC | E1, E4; H1 → H2 → H3 → H4 → H5; C0; H7, H8 on a quiet machine. F: regime text, H6 review, server and Studio designs, mock-ups |
| M1 | A2 → A5 → A6 → A7 → A8b → A13 → A14 | A8a → A9 → (after A10) A15 the JavaScript build | events E7; painter 5 → 7 → 8 | A1, A3, A4, A10's mechanics, A11 (alone, others paused), A12, A16; painter 4, 6, 12, 13; E8 to E12; regime tooling |
| M2 | L: the syntax batch (X6, X7, X11 to X14), then X2, X8, X13's checks, X45 | D: B1 → B3 → A1, T1 → X41 | H/E: B2 push-down → WASM build → Views 9, 19 | the CLI and the Python SDK (increments B4, B5), the events server halves, X47, X49, cases, Views 10, 11, 14, 18, 20, templates |
| M3 ∥ M4 | L: X20 with X46 → X23 → X44 | D: S1, S2, D1, G1 → I1 → L2, I2, I3 → C1 → M1, N1 | E: L1, V2, V3, display values → X48 | Studio, the library framework and core library, server, Electron → Swing → .NET, MCP, the tool pack, X3, X5, X33 cases |
| M5 | L: X24, X22, X26, X27, X40 | D: D2 → the depth group by theme | E: V1 the chart grammar → V4 kinds → L4 workspace | finance library, X25, X30, X50, cases, docs |
| M6 | L: X9, X10, X28, X29, X39 | D: the P3 group; `migrate` rules | E: completions (languages, right-to-left, audit) | genomics library, X31, X32, X34, docs site, packaging, fuzzing |
| M7 | X: the console hop (C2) → B6's adapter → B7 | X: S3, S4a, S7 → B8 → C5 | reads diffs; frees for fixes | C1, C2b, C3, C4, S4b, S5, S6, S8, C6, C7, the Studio embed, C6 fragments, the report |

No language change enters the compiler before `v0.10.0` is pinned (SPLIT_PLAN rule 4, Q9): in M0 and M1 the L and D lanes write
specification text and cases only.

## 5. Risks, gates, tests and the order

### 5.1 Risks

| # | Risk | Seen as | Limit |
|---|---|---|---|
| R1 | **Scope**: ten times any release yet run; the estimates (est.) are over half of Part B outside the two expansions | dates slide silently | the rate measured at M0 and M1; checkpoints at M2 and M4 where the owner re-confirms M5 and M6 (EL_EXPANSION XD1 already asks this); cut lines agreed now (5.3); every milestone tagged and usable alone |
| R2 | **The JavaScript proof fails** (T1 `java.time`, regular expressions; T2 size; T4 speed) | no `<rachana-view>`, CLI on Node, Electron, Studio, MCP server | known in M0; fallbacks of COMPILER_AND_RUNTIMES 4.3 in order; the hand-written evaluator needs the owner (D16). The JVM side of M1 is unaffected |
| R3 | **The WASM proof fails** (WASM GC under `wasmtime`, W4) | no Python compile without a JVM | known in M0; fallback B (a native library per platform) meets "no JVM" but ships native code; the JVM sidecar is never the documented path |
| R4 | **Determinism across Java, JavaScript, Python**: decimal128 arithmetic (X2) compiled to JavaScript; number and date text; text order by code point (X31); zones, Unicode and locale tables | the same Sutra shows different figures in two places | Core owns `CoreMath`, `CoreDecimal`, number and date text; every table is a pinned pack whose version is in the ViewModel; byte-equal results in the gate on every push; P2's stress list; differential fuzzing from M2 |
| R5 | **The size budget against fifty additions** (runtime ≤ 150 KB) | P10 fails at level 1.3 | Core libraries linked only when used (EL rule 10); the budget checked per level; a library that cannot fit is loaded on demand, never dropped silently |
| R6 | **Painter performance** under containers, the chart grammar, windowed tables, 10,000 updates a second | Views P3, P4 fail; EV10 fails at C-III | budgets in the gate from the painter's first day (step 3); the baseline (H7) first; `test_render_parity_browser.py`; C-III does not merge unless every figure is at or better |
| R7 | **The painter frozen too early**: CG7 forbids changing how a contract minor is drawn | no room to fix speed | semantic forward vectors from `3.1.0`; the DOM snapshots are frozen only when both products are declared complete (PD12): no host draws with it before M7 |
| R8 | **Two expansions in one module** (L and D lanes in `rachana-core`) | merge pain, a golden changed by the other lane | file ownership of 4.3; one grammar revision per level; CG1 and CG2 on every push; the syntax batch lands before any D-lane item reads new syntax |
| R9 | **A new Core name collides with Drishti's profile** (`param`, `rank`, `lag` …) | Drishti fails to start at a pin move | reserved names and promotions (CG6) from M0, before the first addition |
| R10 | **The level raise changes what a user sees** | a problem message or completion list differs | two-step evidence: exact at level 1.0, reviewed at the raise (EV2); `migrate --check` first |
| R11 | **The regime is untested until M7** | the promise fails on the day it is needed | CG3 on every merge and CG8 on every tag from `v0.9.0`; the integration branch runs each candidate |
| R12 | **The gap**: Drishti's `main` moves (CSV loaders, fixes) for months while the integration branch waits | a large merge at M7 | `main` merged into the integration branch weekly; CG8 runs against `develop` as well as the tag |
| R13 | **Desktop hosts cannot be tested here**: the Windows QA machine takes no `.exe`; WebView2 is Windows-only | .NET untested | Electron and JCEF tested on Linux under a virtual display; .NET on a hosted Windows runner (PD13) or it leaves i7b (CL5) |
| R14 | **AI evaluation needs model calls**: cost, keys, moving models | D30's figures cannot be produced | PD15; the kit ships only with figures; the rest of the release does not wait for it |
| R15 | **The public repository**: a Drishti Sutra, pack word or datum enters `drishti-rachana` (corpus, finance and genomics libraries, cases harvested from tests) | a leak | the corpus in a private companion; "names no host" with a domain-word allow-list scoped to each library's folder; synthetic data only; the history move's path allow-list reviewed before the first push |
| R16 | **Suite time**: about 3,000 cases grow past 10,000, in six runtimes | the gate takes an hour; builders stop running it | tiers (5.2); the affected-area map; a time budget per tier that is itself a gate |
| R17 | **One gate lost in the move to one events endpoint; one person hears another** | silent | I7B_PLAN R1, R2: G5 parity, G6 isolation (EV8) |
| R18 | **Token cost** of the largest programme yet | the owner's budget | Fable for designs and the seven reviews only; grade set on every builder; each milestone report carries bd delivered and remaining |

### 5.2 Test strategy

| Tier | Runs | Holds |
|---|---|---|
| Every push (minutes) | CI of each product | unit tests; conformance on JVM and Node; CG1 frozen cases, CG2 level replay, CG3 customer corpus, CG4 contract append-only, CG5 API surface, CG6 reserved names; guards; byte budgets; Elements Node tests and CG7 |
| Every merge | CI, sharded | conformance in Chromium, Firefox, WebKit ("runtimes agree"); browser tests of the changed library; the affected templates and library fragments |
| Nightly (one heavy job at a time) | the owner's machine or CI | WASM conformance on Linux, macOS, Windows; differential fuzzing; soak and frame-time tests; host SDK tests (Electron, JCEF; .NET on its runner); P1 to P11 and Views budgets; the hostile corpora |
| Every release candidate | `tools/release` | CG8 inside a Drishti checkout (tag and `develop`), CG10 speed; the release refuses to tag without the report |
| Every Drishti step | Drishti | the pre-drill checklist of I7B_PLAN section 8 (its 8.1 and 8.4 to 8.9; 8.2, 8.3 move to i8), the evidence run, the smoke on an installed stack, **one drill** |
| Per fragment and per function | generated | every library fragment has a document test (T1) and a gallery example; every function example is a case (X43) |
| Per level | once | AI evaluation on the held-out tasks (at 1.2 and 1.4); the accessibility checks; the security review of the level's additions |

Rules kept from the earlier plans: guards first, in report mode, lists that may only shrink; a test is moved, never duplicated;
generated files are regenerated, never merged; recordings and snapshots approved once per step with every file listed; a red
compatibility gate is fixed in code, never by re-recording.

### 5.3 Cut lines (agreed now, taken at a named moment, never during a drill)

| # | If | Then |
|---|---|---|
| CL1 | P1 misses a threshold in M0 | the named fallback is taken; M2's dates are re-issued; the last fallback waits for the owner |
| CL2 | W4 fails | fallback B for Python; if native code is not acceptable, Python compile and lint leave "complete" and the SDK lints through the Node CLI |
| CL3 | A13 or A14 are not green at M1's review | they ride a second Drishti release `i_7b1b` (one more drill); there is no product without the binder in Core, so they cannot wait for M7 |
| CL4 | the measured rate at checkpoint 1 is under 8 bd a day | the owner chooses: continue to M6, or declare the products complete at level 1.2 (M4) and run 1.3, 1.4 as later minors beside i8 |
| CL5 | .NET cannot be built and tested | `Rachana.WebView2` moves out, listed as open in the evidence |
| CL6 | D30's figures are not met | the prompt kit and MCP servers ship marked preview |
| CL7 | S5 (WebSocket) or S6 + C5 (polling to push) are late in C-II | I7B_PLAN CL2, CL3 |
| CL8 | the catalogue is larger than the 60 bd placeholder | core and finance in i7b in catalogue order; the remainder by the owner's choice |

### 5.4 The order, and why

1. **Proofs** (M0): they decide which product is being built.
2. **The 1.0 reservations and the regime's core** (M0): after the first tag they would be breaking (XD2 to XD4, Q3).
3. **Baseline and evidence tooling** (M0): "before" can only be recorded before.
4. **The split** (M1): frozen behaviour, nothing rides along; Drishti is on a pin from here.
5. **The JavaScript build, `<rachana-view>`, the Data Protocol, the painter** (M1, M2): everything visible needs them.
6. **Levels 1.1 to 1.4 in order**: syntax first inside each (the prompt kit, formatter and every example depend on the grammar).
7. **Libraries after components (C1) and expression libraries (X42)**: core at 1.2, finance at 1.3, genomics at 1.4.
8. **Hosts after `<rachana-view>` and the protocol; Studio after the tools bundle; AI after the 1.2 grammar** and again at 1.4.
9. **Drishti last** (M7): live feeds, then the painter, then the level and the fragments; then the report.

## 6. Decisions for the owner

| # | Decision | Recommendation |
|---|---|---|
| **PD1** | Accept i7b as parts A, B, C: about 1,260 bd identified, about 1,500 planned, about 108 working days at the planning rate (63 to 188) | **Yes**, as directed, with PD3's checkpoints. It is the largest commitment yet; the first two milestones give the real rate |
| **PD2** | Seven milestones M0 to M7 as sub-releases; Drishti tags `v2.9.11.i_7b1`, `i_7b2` and the final `v2.9.11.i_7b`; three drills | **Yes** |
| **PD3** | Checkpoints after M2 and M4: the measured rate, a re-forecast, and the owner's word before M5 and M6 start | **Yes.** Default: continue to M6. CL4 names the alternative so it needs no re-plan |
| **PD4** | `events.v1` switch-over inside i7b as integration step C-II, with its own drill, before the painter | **Yes** (3.1): the new Elements cannot be vendored without it, and the painter's live path needs it. I7B_PLAN's decisions 1 to 8 stand for it |
| **PD5** | Role Homes, K5, the carry-overs, `NetLinksTest` move to i8 with their accepted decisions (9 to 18, 20, 21); GenUI inside Drishti stays i9; Drishti gets no feature in i7b beyond owner-directed work on `main` | **Yes** |
| **PD6** | The two expansions: accept XD1 to XD22 and Q1 to Q15 now, with XD1, XD20, Q9, Q10 read as "all of it, inside i7b, in levels 1.1 to 1.4 shared by both documents" | **Yes.** XD2 to XD4 and Q1 to Q8 are needed in M0. Q9's rule survives as an order: no language change before `v0.10.0` is pinned |
| **PD7** | Where the server halves of `events.v1` live (Java, Python, Node) | **In `drishti-rachana`**, under neutral coordinates with no dependency on `rachana-core`, proven by Elements' vectors; Drishti takes the Java one as a pinned jar instead of the planned `kernel/drishti-events-server`. That repository has the three toolchains; Elements stays toolchain-free and remains the protocol's one home |
| **PD8** | The Drishti compatibility corpus: `drishti-rachana` is public | **A private companion repository from the first day** (CI checks it out; builders keep it as a sibling folder). This replaces Q6's "committed while private" |
| **PD9** | The libraries: framework and contents as `LIBRARIES.md` and `LIBRARY_CATALOGUE.md` decide; order core (M3), finance (M5), genomics (M6); 60 bd is a placeholder; finance and genomics in the public repository use synthetic data and a domain-word allow-list scoped to their folders | **Yes**; the catalogue's count re-sizes B7 (CL8) |
| **PD10** | Drishti's own blocks become library entries (C6) only after the level raise, as part of C-III | **Yes**; the form is `LIBRARIES.md`'s |
| **PD11** | A fifth slot: a second Sonnet for text-only work in the product repositories (cases, documents, library content), never in Drishti's tree, never a heavy job | **Yes**: about 15% off the calendar at little machine cost. If not, the schedule is as shown |
| **PD12** | The painter's DOM snapshots (CG7's "never changes how it draws") frozen when the products are declared complete (M6), semantic vectors from `3.1.0` | **Yes** (R7) |
| **PD13** | Testing desktop hosts: Electron and JCEF on this machine under a virtual display; .NET / WebView2 on a GitHub-hosted Windows runner | **Yes**; if a hosted runner is not acceptable, .NET leaves i7b (CL5) |
| **PD14** | Publishing: i7b ends with every package built, installable offline and "ready to publish"; public registries and licensing tiers on the owner's word later; no CDN of ours; `LICENSE-SPEC.md` approved before M2 | **Yes** |
| **PD15** | Things outside the builders: model access and a spending cap for the AI evaluation; an outside security review; a human screen-reader pass | The owner provides model access by M3; the review and the pass are arranged after M6 and **do not block** "complete" (the evidence lists them as open) |
| **PD16** | Languages: the mechanism, English, and two proof locales, one right-to-left | **Yes**; translations are content |
| **PD17** | Small additions no accepted design holds: light shape-only inference for Core ("three lines give a screen", 4 bd); Parquet in the standalone server (3 bd) | **Both in**, inside the contingency |
| **PD18** | Mock-ups first (two or three each, the owner picks): Rachana Studio and the playground, the workspace document (L4) with the desktop hosts, the docs site's front page | **Yes**; in M0 and M2 so no builder waits |
| **PD19** | The evidence pack as section 3.3: twelve items, two classes, a generated report in `docs/admin/`, a zip on each of the three releases, the tooling written first | **Yes** |
| **PD20** | Pull the drill-scope carry-over (1.5 bd, decision 17's one root command) back from i8 into M0, since i7b has three drills | **Yes** (inside the contingency); the rest of the carry-overs stay in i8 |
| **PD21** | Start M0 on acceptance without further confirmation (the proofs need the machine for about a week of light use, never beside a drill) | **Yes** |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
