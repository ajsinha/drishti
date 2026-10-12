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
# Rūpaka next: the model architecture and the wave plan (2.2 to 3.0)

*Agreed with the product owner on 2026-10-12.* This settles the BI architecture before more features are added, and
replaces the roadmap part of [BI_PARITY_PLAN.md](BI_PARITY_PLAN.md) (its gap analysis still stands). Nothing here is
built yet. Drishti is not in production, so these are breaking changes on purpose; the foundation stays **YAML,
Python, datasets and data models**. No Power Query-like tool: Python is the data-prep path. The design
specification of wave W1 (the dataset and model YAML, the model query language v1, pack-generated datasets, the
migration, the UX flows and the build plan) is [BI_MODEL_SPEC.md](BI_MODEL_SPEC.md).

## 1. Decisions

| # | Decision |
|---|---|
| D1 | Vocabulary: a **dataset** is one governed table; a **data model** is datasets + relationships + measures. (Today's "dataset" YAML becomes a data model.) |
| D2 | **One model per report** (and per board, notebook, alert, subscription, feed). Report-level links remain only for a report that spans models owned by different teams. |
| D3 | Packs **publish a dataset for every kind** automatically, governed with the pack; users mostly start at the model step. |
| D4 | The model architecture comes **first** (2.2), before calculation and authoring depth. |
| D5 | **One model query language** (versioned YAML/JSON) is the only way the UI, the API, AI and MCP ask a model for data. |
| D6 | Every dataset has a **time mode** (`live`, `snapshot`, `history`); `live` is the default where the pack's connector streams. |

## 2. The three layers

1. **Dataset**: one table from a pack kind, a connector query, a Python Transform or another model's output. It owns
   grain and keys, column types and meanings (from the pack), additivity, time mode, refresh and as-of, masks and row
   rules. Defined once, reused by many models.
2. **Data model**: datasets in a **star** (facts and shared dimensions; never physically denormalised: a flat view is an
   *output* for Excel, Python or export), relationships (cardinality, direction, active/inactive), measures,
   calculation groups, hierarchies, parameters, model security (only tighter than the datasets'), `ai:` metadata,
   expected-value tests and golden questions, an owner, a lifecycle, a **semver contract** (the fields and measures
   consumers may use) and stable ids.
3. **Consumers**: report, board, notebook, alert, subscription, export, embed, Excel/SQL feed, AI and MCP — each bound
   to exactly one model; a report may add local measures, never joins. A report on one dataset gets an **implicit
   model**, visible and promotable.

Correctness by construction: each measure aggregates at its own grain; facts meet through shared dimensions (one visual
across facts); a join that repeats rows is refused; additivity is declared (additive, semi-additive over time,
non-additive); amounts in different currencies are never summed without a conversion. Security is applied per reader
at the dataset, before any aggregation or join. Every model query is a **standing query**: asked once it answers;
subscribed, it is kept current incrementally (sums and counts by difference, min/max/distinct by group recompute,
anything else on a throttled tick) and the same report answers *now*, *as of a business date* and *the last hour*.

AI never reads raw data: it writes model queries (D5), checked like a person's and run as the asking user; numbers
always come from the engine. AI drafts datasets, models, measures, reports and Transforms as YAML that go through Check,
tests and four-eyes review with their provenance recorded. Models are exposed to outside agents as MCP tools.

## 2a. Shared models, the way Power BI does it — and further

A report **references** a model; it never owns it. One model is the root of many reports, boards, notebooks, alerts,
subscriptions and feeds (Power BI's "thin reports over a shared semantic model"). Consequences, all enforced:

- An implicit model is **one shared model per dataset**, not a copy per report; "Make it a model" creates or reuses a
  standalone model and rebinds reports by reference.
- The model page lists **Used by** (every consumer), and a model with consumers cannot be deleted or unpublished
  without its impact shown and its consumers handled.
- Using a model needs **read on the model** (Power BI's *Build* permission): a workspace member or its audience.
- A report's local measures are "this report only" and can be **moved to the model** as a reviewed change.

Taken from Power BI's model view and made governed: the diagram of tables with fact and dimension roles; relationships
with cardinality, cross-filter direction and active/inactive; **field properties set once in the model and inherited by
every report** (display name, description, format, display folder, hidden, sort-by column, data category such as
currency, country or URL, default summarisation); measures with a home table and display folders; hierarchies;
calculation groups and parameters (W6); **perspectives** (a named subset of the model for a team or a report);
roles for row security (here per dataset, tightened by the model); endorsement (promoted, certified); lineage and
impact. Where Drishti goes further: the packs fill most properties in automatically (meanings, keys, references),
security is applied before aggregation, joins that repeat rows are refused, every change is YAML with review and tests,
and one model serves live and as-of answers.

## 3. The experience comes first

Adoption depends on how friendly the designers are and how obvious each workflow is, so every wave is held to these
rules, and judged by power users before it ships:

- **No dead ends:** every page says what to do next; nothing a person can start is impossible to finish in the UI.
- **No YAML typing needed:** the designers write the YAML; the YAML tab is for experts and review. Python runs on the
  server (sandboxed) or in the browser (WebAssembly), whichever the task needs, behind the same item.
- **Direct manipulation:** drag fields, visuals and tiles; edit titles in place; suggestions from the packs (datasets,
  relationships, measures, links) one click away; undo everywhere.
- **Never lose work:** drafts kept across refresh and Back; stale saves detected; leaving asks first.
- **Feedback where you look:** errors and results beside the control, in plain words, with the fix.
- **Right defaults:** sensible visuals for the fields chosen, measures with the right aggregation and format, the
  business date and the reader's security applied without asking.
- **Every screen at phone width,** keyboard-only, 44 px targets.

Each release adds an **experience wave**: the adversarial QA by power users (designers, workspaces, packs,
connectors) plus a usability review against these rules, and its findings are fixed before the drill.

## 4. Waves

Each release: builders (at most two at once) → adversarial QA by power users → fixes → one drill → tag. Every wave
updates its docs and tutorials with UI, CLI and YAML (never thinner) and migrates the shipped samples.

### 2.2.0 — the model architecture

| Wave | Scope | Accepted when |
|---|---|---|
| **W1 Layers and the query language** | the dataset and model item types; pack-generated datasets (D3); connector and Python datasets; implicit models; the model query language v1 (spec, validator, compiler to the engine) and its API; security at the dataset, model security only tighter | a report, an alert and an Analyse slice ask the same model through the same query language and get identical, per-reader masked answers |
| **W2 The star, shared models and correctness** | Used-by lists, delete/unpublish impact, Build-like read right, move a local measure to the model; field properties inherited by every report (display name, description, format, folder, hidden, sort-by, data category, default summarisation); perspectives; several facts with shared dimensions in one visual; additivity declarations; the currency guard; relationship suggestions from pack entity references; fan-out refusal at model level; model contracts with semver and breaking-change detection; stable ids | MTM and exposure per counterparty in one visual equal an independent per-grain computation; a breaking model change is refused without a major version |
| **W3 Time modes and standing queries** | `live`/`snapshot`/`history` datasets; standing queries for every consumer; incremental rules per measure; throttling and back-pressure; as-of time travel; live and snapshot mixed in one model | a board, a report and an alert over one model tick live, answer as of a past date, and match a full recompute |
| **W4 Consumers, designers and migration** | reports, boards, notebooks, alerts, subscriptions, exports and embeds bound to models; the dataset wizard (mostly automatic) and the **model designer** (the Model view becomes the editor of relationships, measures, security, tests); composed and derived datasets migrated into models and Python datasets; report links reduced (D2); every sample and tutorial 10–18 migrated | every shipped sample and tutorial runs on the new layers in the UI alone; nothing needs hand-written YAML |
| **W5 AI grounding** | `ai:` metadata (synonyms, units, examples, hints); golden questions run as tests; AI ask/explain through the query language only; MCP tools `describe_model`, `query_model`, `explain_change` | golden questions pass on the mock and on one real provider; an AI answer can only use measures the user may see |

### 2.3.0 — calculation depth

| Wave | Scope |
|---|---|
| **W6 Filter context** | measure-level filter overrides (% of total, ignore a slicer, CALCULATE-style), calculation groups, what-if and field parameters |
| **W7 Time and balances** | balance measures (closing, opening, last value), fiscal and holiday calendars per model, inactive relationships chosen per measure (trade, settle, value dates) |
| **W8 AI measures** | a measure written in plain words becomes model YAML with tests, reviewed like any change |

### 2.4.0 — authoring depth

| Wave | Scope |
|---|---|
| **W9 Format and themes** | a full format pane, themes, conditional formatting (rules, scales, icons), a deeper matrix |
| **W10 Interactions** | tooltip pages, small multiples, per-visual interaction control, drill-through improvements |
| **W11 AI design** | "design a report for …" and "explain this change" (decomposition through hierarchies and as-of comparison) |

### 2.5.0 — Python and outside tools

| Wave | Scope |
|---|---|
| **W12 Python data prep** | step-by-step preview, a tested snippets library (as-of joins for prices and FX), a profiling view, AI-written Transforms, Transforms reading connectors directly, warm workers |
| **W13 Feeds** | an Excel feed and a read-only SQL endpoint over models, both per-reader masked |

### 2.6.0 — service and operations

| Wave | Scope |
|---|---|
| **W14 Service** | comments on reports, usage metrics, sensitivity labels, deployment between environments |
| **W15 Live AI** | AI narratives on live alerts and subscriptions, rate-limited, masked, suggest-only |

### 2.7.0 and 3.0

| Release | Scope |
|---|---|
| 2.7.0 | **W16** paginated reports and statements |
| 3.0.0 | a scale test on a real server, a second query engine, an Excel add-in |

## 5. Risks

- The migration (W4) touches every sample, tutorial and BI test: it is its own wave and is drilled on its own.
- Standing queries (W3) need care with memory and back-pressure: W3 includes a load test with many subscribers.
- The query language (D5) is a contract for UI, AI, API and MCP at once: it is versioned from v1 and changed only by
  a reviewed spec change.
