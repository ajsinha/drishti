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
# Rūpaka next: the wave plan W1 to W16 and its risks (2.2 to 3.0)

> **Archived: replaced by [BI_DESIGN.md](../architecture/BI_DESIGN.md) section 12 (the waves A to I as planned and built).** These are sections 4 and 5 of [BI_MODEL_ARCHITECTURE.md](../architecture/BI_MODEL_ARCHITECTURE.md), agreed on 2026-10-12 and replaced soon after; kept as history because BI_MODEL_SPEC.md and the code still name the waves W1 to W16. The decisions D1 to D6 they served are current and stay in BI_MODEL_ARCHITECTURE.md.

## 4. Waves

> **Replaced** by [BI_DESIGN.md section 12](../architecture/BI_DESIGN.md#12-w1-as-built-against-this-design-and-the-revised-wave-plan).
> Kept below for the record only: the release numbers below are the old plan's. The order now (2026-10-20) is 2.5.0 Drishti\*net (released), 2.6.0
> statements, field parameters, the Cassandra connector and drishti-iceberg, 2.7.0 write-back, 2.8.0 AI (GenUI, AI management, the Sajha MCP
> connector), the real-life QA, 2.9.0 QA findings and production readiness, then 3.0; send and push and integration (W13 feeds among them) after
> 3.0.0 ([section 12.2](../architecture/BI_DESIGN.md#122-the-three-waves)).

Each release: builders (at most two at once) → adversarial QA by power users → fixes → one drill → tag. Every wave
updates its docs and tutorials with UI, CLI and YAML (never thinner) and migrates the shipped samples.

### 2.2.0 — the model architecture

| Wave | Scope | Accepted when |
|---|---|---|
| **W1 Layers and the query language** | the dataset and model item types; pack-generated datasets (D3); connector and Python datasets; implicit models; the model query language v1 (spec, validator, compiler to the engine) and its API; security at the dataset, model security only tighter | a report, an alert and an Analyse slice ask the same model through the same query language and get identical, per-reader masked answers |
| **W2 The star, shared models and correctness** | dataset derivation: column subsets, row filters, explicit grain and de-duplication rules, per-row Drishti expressions and Python-derived columns (console backend) with declared meanings, grouping to a coarser grain; every dataset rooted in packs (Check refuses an untraceable root; lineage report → pack kinds); Used-by lists, delete/unpublish impact, Build-like read right, move a local measure to the model; field properties inherited by every report (display name, description, format, folder, hidden, sort-by, data category, default summarisation); perspectives; several facts with shared dimensions in one visual; additivity declarations; the currency guard; relationship suggestions from pack entity references; fan-out refusal at model level; model contracts with semver and breaking-change detection; stable ids | MTM and exposure per counterparty in one visual equal an independent per-grain computation; a breaking model change is refused without a major version |
| **W3 Time modes, standing queries and the planner** | the MQ planner across source, Java server and console backend (browser draws only; report and Analyse Python and Wasm slicing moved to the servers); `live`/`snapshot`/`history` datasets; standing queries for every consumer; incremental rules per measure; throttling and back-pressure; as-of time travel; live and snapshot mixed in one model | a board, a report and an alert over one model tick live, answer as of a past date, and match a full recompute |
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
