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
# Rūpaka next: the model architecture (decisions D1 to D6)

> **Superseded in part by [BI_DESIGN.md](BI_DESIGN.md), the single authoritative BI target design.** Decisions D1–D6
> and sections 2, 2a, 2b, 2c and 3 below still stand. The **wave plan and risks were replaced**
> by BI_DESIGN.md section 12 (waves A = 2.2.0, B = 2.3.0, C = 2.4.0 and the later ones) and are archived as history
> (section 4), and the expression language is now Python-like (BI_DESIGN.md section 3.3).

*Agreed with the product owner on 2026-10-12.* This settles the BI architecture before more features are added, and
replaced the roadmap part of the 2.1 parity study (history, archived: [BI_PARITY_PLAN.md](../archive/BI_PARITY_PLAN.md); the gap check is now [BI_DESIGN.md section 11](BI_DESIGN.md#11-gap-check-against-power-bi)). Most of it was
built in 2.2.0 to 2.4.0 (BI_DESIGN.md section 12.2 records what). Drishti is not in production, so these are breaking changes on purpose; the foundation stays **YAML,
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

## 2b. A model is a mini database, always rooted in packs

Think of a data model as a small database: its datasets are the tables, their keys and references are primary and
foreign keys, relationships are the joins it allows, measures and calculation groups are its stored calculations,
perspectives are its views, row rules and masks are its grants, and the model query language is its query language.
Unlike a free-standing database, **every model is rooted in packs**: every dataset traces back to pack kinds, so it
inherits the kinds' keys, meanings, units, references, connectors, business-date calendar and security.

- A dataset from a pack kind is rooted directly.
- A connector-query dataset must name the pack and connector it reads and the **kind it represents** (or the kinds its
  columns come from), so its meanings and security come from the pack, not from free text.
- A Python dataset's inputs are pack-rooted datasets or models, and its output columns declare their meanings.
- A model built on another model's answer inherits that model's roots.

**What a dataset can do** (rows and columns; aggregations that people slice belong in the model):

- **select** a subset of a kind's attributes (renamed for display, with descriptions; types, keys and meanings from the pack);
- **filter** rows (`where:`);
- **remove duplicates only explicitly**: `grain: { one-row-per: <key>, keep: latest by <column> | first | highest <column> }` —
  never a silent distinct, because de-duplication changes the grain;
- **derive attributes** per row with a Drishti expression (the measure language applied per row: arithmetic, `case`,
  date parts, strings, FX conversion, a lookup through a reference) or a **Python function** (vectorised; sandboxed on
  the console backend); each derived column declares its type and meaning;
- **group** to a coarser grain when needed, declaring the new grain.

Masks and row rules of the source columns carry into derived ones, and Python only ever sees masked input.

```yaml
dataset: live-trades
from: pack.trading.trade
columns: [tradeId, desk, currency, notional, mtm, updatedAt]
where: status = 'LIVE'
grain: { one-row-per: tradeId, keep: latest by updatedAt }
derive:
  notionalUsd: { expr: "notional * fx(currency, 'USD')", meaning: amount, currency: USD }
  bucket:      { python: buckets.by_tenor, meaning: category }
```

Check refuses a dataset whose roots cannot be traced to a loaded pack, and lineage always runs from a report back to
the pack kinds and their connectors. (Enforced from W2.)

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

## 2c. The servers compute, the browser draws

Every number is computed by the Java server, the console backend (FastAPI, Python) or the source behind a pack's
connector; the browser only draws the answer it receives, so every device (an iPhone's Safari, a PC's Chrome) shows the
same answer. Today's browser paths (Python visuals, measures and Analyse cells through Pyodide, DuckDB-Wasm slicing)
were to move to the servers in W3 (wave B, 2.3.0; [BI_DESIGN.md section 12.2](BI_DESIGN.md#122-the-three-waves) says what moved); a personal Python console in the browser came in 2.4.0 ([ANALYSE.md section 16](../guides/ANALYSE.md#16-the-browser-python-console)). Details: BI_MODEL_SPEC.md section 12.

## 3. The experience comes first

Adoption depends on how friendly the designers are and how obvious each workflow is, so every wave is held to these
rules, and judged by power users before it ships:

- **No dead ends:** every page says what to do next; nothing a person can start is impossible to finish in the UI.
- **No YAML typing needed:** the designers write the YAML; the YAML tab is for experts and review. Python runs on the
  servers (the console backend's sandbox); the browser runs no Python.
- **Direct manipulation:** drag fields, visuals and tiles; edit titles in place; suggestions from the packs (datasets,
  relationships, measures, links) one click away; undo everywhere.
- **Never lose work:** drafts kept across refresh and Back; stale saves detected; leaving asks first.
- **Feedback where you look:** errors and results beside the control, in plain words, with the fix.
- **Right defaults:** sensible visuals for the fields chosen, measures with the right aggregation and format, the
  business date and the reader's security applied without asking.
- **Every screen at phone width,** keyboard-only, 44 px targets.

Each release adds an **experience wave**: the adversarial QA by power users (designers, workspaces, packs,
connectors) plus a usability review against these rules, and its findings are fixed before the drill.

## 4. Waves and risks

The wave plan W1 to W16 and its risks were replaced by [BI_DESIGN.md section 12](BI_DESIGN.md#12-w1-as-built-against-this-design-and-the-revised-wave-plan); the original is history, archived in [BI_MODEL_ARCHITECTURE_WAVES.md](../archive/BI_MODEL_ARCHITECTURE_WAVES.md) (the W numbers BI_MODEL_SPEC.md uses are defined there).
