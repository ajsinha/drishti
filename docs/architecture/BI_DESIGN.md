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
# Drishti BI: the design

*Status: the authoritative design, built wave by wave.* Written 2026-10-12 as the target design, after wave W1 was built and QA'd and
before any more BI code; since then waves A, B, C, N, D and R were built and released (2.2.0 to 2.6.7) and wave W (write-back, its own
release) in 2.8.0; section 12.2 records each wave as planned with *Built* notes; wave I (AI in BI beyond stage 2) is not built, and
section 13 lists the decisions the product owner still has to take. This is the **single authoritative design** of Drishti's business
intelligence (Rūpaka); since 2.9.7 it also holds, as sections 15 to 17, the W1 model specification (BI_MODEL_SPEC), the original Rūpaka
design with every phase as built (RUPAKA) and the design of BI runs (BI_RUNS). Where it overlaps another document, this one wins:

| Document | What still stands | What this design supersedes |
|---|---|---|
| [Section 14](#14-the-model-architecture-decisions-d1-to-d6) (from BI_MODEL_ARCHITECTURE.md, deleted; in git at tag v2.9.5) | decisions D1–D6, §2a shared models, §2b mini database rooted in packs, §2c servers compute, §3 experience rules | its wave plan and risks: replaced by section 12 here, deleted (BI_MODEL_ARCHITECTURE_WAVES.md, in git at tag v2.9.5) |
| [section 15](#15-the-model-layer-the-specification-of-wave-w1) | the W1 formats (dataset, model, MQ v1, implicit models, codes 9100–9199), §12 tiers, §13 worked example | §5.4 "compilation to the existing engine" as the *end state* (it is the W1 path; section 4 is the target); §5.8's W3 promises are restated in 4.6 |
| BI_PARITY_PLAN.md (a 2.1 study, deleted; in git at tag v2.9.5) | the item ids the code cites (P1–P10, R1–R8, numbered gaps), listed in [section 11.1](#111-the-21-studys-item-ids) | everything else: its gap analysis is replaced by section 11, its roadmap (§5) by section 12 |
| [section 16](#16-rupaka-the-original-design-and-every-phase-as-built) | positioning, the BI page, as-built history (§20) | §7 query engine and §17 architecture diagram (replaced; the 2.1 text, RUPAKA_2_1_ENGINE.md, was deleted: in git at tag v2.9.5), §13.2 "two targets" (browser Python) |
| LIVE_BI.md (a 2026-10-06 note, deleted; in git at tag v2.9.5) | producers own their arithmetic: a streaming producer's results served as a kind ([DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#results-computed-by-a-streaming-producer)) | nothing; standing queries (4.6) consume what it produces |
| Guides (DATASETS, REPORTS, BOARDS, BI_GOVERNANCE, PYTHON_BI, ANALYSE, BI_AI, DISTRIBUTION) | how 2.1/W1 behave today | the browser paths they describe (Pyodide, DuckDB-Wasm, console SQLite) end in B |


**Wave labels.** The build is three waves, each a release (section 12): **A** = 2.2.0 *Foundation and languages*,
**B** = 2.3.0 *Calculation, live and scale*, **C** = 2.4.0 *Authoring and analysis*; then **N** = 2.5.0 *Drishti*net*, **D** = 2.6.0
*Statements, field parameters and connectors* (named *Sharing and integration* until 2026-10-20), **R** = 2.6.7 *BI runs* (with the Drishti\*net transport items, chart any table and mixed Delta and Iceberg storage), **W** = 2.7.0 *Write-back*, **I** = 2.8.0 *AI* and **E** = 2.9.0 *QA findings and production readiness* (section 12.2, as agreed on
2026-10-17 and re-ordered on 2026-10-18, 2026-10-19 and 2026-10-20; send and push and integration after 3.0.0). W1 is the
already-built first wave of 2.2.0. A component marked (A), (B) or (C) is delivered in that wave; one marked (C) in the
sections below that is about integration arrives in D, about AI in I, and about governance in E.

---

## Contents

- [1. Principles and non-goals](#1-principles-and-non-goals)
- [2. Conceptual model and vocabulary](#2-conceptual-model-and-vocabulary)
- [3. Languages](#3-languages)
- [4. Query architecture](#4-query-architecture)
- [5. Security architecture](#5-security-architecture)
- [6. Scalability and performance](#6-scalability-and-performance)
- [7. Governance and lifecycle](#7-governance-and-lifecycle)
- [8. Authoring UX architecture](#8-authoring-ux-architecture)
- [9. AI architecture](#9-ai-architecture)
- [10. APIs and integration](#10-apis-and-integration)
- [11. Gap check against Power BI](#11-gap-check-against-power-bi)
- [12. W1 as built against this design, and the revised wave plan](#12-w1-as-built-against-this-design-and-the-revised-wave-plan)
- [13. Decisions for the product owner](#13-decisions-for-the-product-owner)
- [14. The model architecture: decisions D1 to D6](#14-the-model-architecture-decisions-d1-to-d6)
- [15. The model layer: the specification of wave W1](#15-the-model-layer-the-specification-of-wave-w1)
    - [0. Reading guide and terms](#0-reading-guide-and-terms)
    - [1. The dataset](#1-the-dataset)
    - [2. The data model](#2-the-data-model)
    - [3. Implicit models](#3-implicit-models)
    - [4. Consumers bind to one model](#4-consumers-bind-to-one-model)
    - [5. The model query language v1](#5-the-model-query-language-v1)
    - [6. Worked examples](#6-worked-examples)
    - [7. Pack-generated datasets (D3)](#7-pack-generated-datasets-d3)
    - [8. Migration](#8-migration)
    - [9. UX flows](#9-ux-flows)
    - [10. W1 build plan](#10-w1-build-plan)
    - [11. Open questions for the product owner](#11-open-questions-for-the-product-owner)
    - [12. Query execution tiers *(planner: W3; the source and Java tiers exist in W1)*](#12-query-execution-tiers-planner-w3-the-source-and-java-tiers-exist-in-w1)
    - [13. Worked example, end to end: two packs, five datasets, one model, one report](#13-worked-example-end-to-end-two-packs-five-datasets-one-model-one-report)
- [16. Rupaka: the original design and every phase as built](#16-rupaka-the-original-design-and-every-phase-as-built)
    - [1. Positioning against Power BI](#1-positioning-against-power-bi)
    - [2. Principles](#2-principles)
    - [3. Concepts](#3-concepts)
    - [4. Entering BI: the top bar, `BI <GO>` and URLs](#4-entering-bi-the-top-bar-bi-go-and-urls)
    - [5. The BI page](#5-the-bi-page)
    - [6. Datasets: the semantic model](#6-datasets-the-semantic-model)
    - [Where BI files live](#where-bi-files-live)
    - [7. The query engine](#7-the-query-engine)
    - [8. Reports and visuals](#8-reports-and-visuals)
    - [9. The designer](#9-the-designer)
    - [10. Workspaces, publishing and the catalogue](#10-workspaces-publishing-and-the-catalogue)
    - [11. Access, security and governance](#11-access-security-and-governance)
    - [12. Streaming BI](#12-streaming-bi)
    - [13. Python in BI: interfaces, not scripts](#13-python-in-bi-interfaces-not-scripts)
    - [14. Generative AI hooks (off by default)](#14-generative-ai-hooks-off-by-default)
    - [15. Distribution: export, subscriptions, alerts, embedding, mobile](#15-distribution-export-subscriptions-alerts-embedding-mobile)
    - [16. Administration and operations](#16-administration-and-operations)
    - [17. Architecture](#17-architecture)
    - [18. Performance targets](#18-performance-targets)
    - [19. Non-goals for the first release](#19-non-goals-for-the-first-release)
    - [20. Build plan](#20-build-plan)
    - [21. Open decisions](#21-open-decisions)
- [17. BI runs: every BI query as a bounded, visible job (built in 2.6.7)](#17-bi-runs-every-bi-query-as-a-bounded-visible-job-built-in-267)
    - [1. The decision](#1-the-decision)
    - [2. What exists and what is new](#2-what-exists-and-what-is-new)
    - [3. The pipeline](#3-the-pipeline)
    - [4. The planner and the estimate](#4-the-planner-and-the-estimate)
    - [5. The tiers](#5-the-tiers)
    - [6. The inline wait](#6-the-inline-wait)
    - [7. The Runs tab](#7-the-runs-tab)
    - [8. The result store](#8-the-result-store)
    - [9. Masks and sharing](#9-masks-and-sharing)
    - [10. Fairness and admission](#10-fairness-and-admission)
    - [11. Cancellation](#11-cancellation)
    - [12. Restart and failure](#12-restart-and-failure)
    - [13. The cluster](#13-the-cluster)
    - [14. API and CLI](#14-api-and-cli)
    - [15. Settings: `config/runs/runs.yaml`](#15-settings-configrunsrunsyaml)
    - [16. Error codes](#16-error-codes)
    - [17. Metrics and Admin](#17-metrics-and-admin)
    - [18. Build steps (two builders)](#18-build-steps-two-builders)
    - [19. Tests](#19-tests)
    - [20. Docs to update when built](#20-docs-to-update-when-built)
    - [21. Open questions](#21-open-questions)

## 1. Principles and non-goals

### 1.1 Principles (the product owner's fixed requirements, made architectural)

| # | Principle | What it forces in the design |
|---|---|---|
| P1 | **Power BI-like, and better where it matters** (correctness, security, live, governance) | a semantic model with star, measures, field properties, perspectives, roles; plus grain safety, security before arithmetic, standing queries, YAML with review |
| P2 | **Four languages only**: YAML (structure), the Drishti expression language (DEL: measures, derived columns, filters, row rules), read-only Drishti SQL over models, Python | no fifth language (no M, no DAX, no MDX, no JavaScript in items); every language compiles to **one logical plan** (3.6) |
| P3 | **The servers compute every number; the browser draws** | the browser receives answers and chart specifications only; no Pyodide, no DuckDB-Wasm, no client-side totals, sorts or pivots (BI_MODEL_SPEC §12) |
| P4 | **A model is a mini database rooted in packs** | every dataset traces to pack kinds; the model is the unit of query, security, contract, cache and audit |
| P5 | **Reports, boards, alerts, notebooks, subscriptions reference one model; never own it** | consumers hold layout and local measures only; *Used by* is an index the server keeps |
| P6 | **Data is not copied** | the only stored data is disposable: refresh layers, rollups, result caches, standing-query state. Deleting all of it changes no answer, only latency |
| P7 | **Streaming by nature** | time (live / as-of / window) is a parameter of every plan; every query can be subscribed; maintenance is incremental where the algebra allows |
| P8 | **Security before arithmetic, enforced on the server at every tier** | row rules and masks bind to the dataset, are injected into the plan before optimisation, and cannot be removed by any language construct |
| P9 | **AI only through the model query layer** | AI writes MQ or SQL and YAML drafts; it never reads rows or connectors; its numbers come from the engine as the asking user |
| P10 | **Designer friendliness is paramount** | the designers write the YAML; every refusal carries a fix; nothing in the UI is a dead end (section 14.6) |
| P11 | **Clean, manageable, scalable, with controls** | every limit is a setting, every query is admitted, budgeted and observable; every item has an owner, version, contract and tests |

### 1.2 Non-goals (what we will not build)

| Not built | Why | What we do instead |
|---|---|---|
| A Power Query / M clone, a step-based prep tool, a gateway | decided 2026-10-12; a second language to secure and test | Python Transforms and Python derived columns on the console backend, with step preview and snippets (after C); DEL derived columns for the simple cases |
| Computation in the browser (Pyodide, DuckDB-Wasm, client totals) | different devices, different answers; security would leave the server | the browser draws; a personal browser Python console is *future work, not scheduled* |
| Raw SQL to connector tables from users | bypasses grain, security, meanings, audit | Drishti SQL over **datasets and models** only (3.4); a connector-query dataset (author-written SQL, reviewed, bound dates only) stays the one place SQL reaches a source, and only an author's reviewed item does it |
| DAX compatibility, MDX/XMLA | DAX's context transition and bidirectional ambiguity are a source of wrong numbers | DEL with an explicit, closed filter-context algebra (3.3.5) and a published DAX → DEL mapping |
| Write-back, planning, input forms that change data | out of scope for BI | a separate, gated design if ever needed |
| A marketplace of custom visuals, arbitrary JavaScript | breaks CSP and "nothing in a report executes" | the vetted visual catalogue, Vega-Lite specs and Python visuals returning data, rendered by the console |
| Publish to web (anonymous) | incompatible with masks | embedding with tokens (10.5) |
| Physical denormalisation inside a model | repeats rows, breaks grain | flat outputs exist only as *answers* (extracts, exports, feeds) |
| A second storage engine of our own (VertiPaq-like) | P6, and DuckDB over Parquet is enough at our scale | rollups (aggregations), refresh layers and result caches, all disposable |

---

## 2. Conceptual model and vocabulary

### 2.1 The picture

```
 WORKSPACE (owner team, members with roles, audience) ─────────────────────────────────────── contains items
 │
 │  PACKS (installed per site; not items of a workspace)
 │  ┌──────────── pack trading ─────────────┐   ┌──────── pack counterparty-risk ─────────┐
 │  │ kind trade ── connector trading-store │   │ kind netting-set ── connector credit-st. │   vocabulary, keys,
 │  │ kind book, desk (references)          │   │ kind credit-limit, collateral-balance    │   references, calendars,
 │  └──────────────┬────────────────────────┘   └───────────────┬──────────────────────────┘   entitlements
 │                 │ generated, read-only, one per kind (D3)    │
 │  DATASETS       ▼                                            ▼
 │   pack.trading.trade ◄─ extends ── trades-live (subset, where, grain, derive)
 │   pack.counterparty-risk.netting-set          pfe-limits (extends credit-limit, derive headroom)
 │   desk-limits (connector query: reviewed SQL)  pnl-vs-exposure (python transform over MQ answers)
 │   cpty-league (SQL view over a model: Drishti SQL)
 │                 │ referenced by alias, never copied
 │  MODEL          ▼                                     (a mini database)
 │   counterparty-cockpit@1.4.0  = datasets + relationships + measures + hierarchies + perspectives
 │                                 + calc groups + parameters + security (only tighter) + contract + ai + tests
 │                 │ referenced by @major, never owned           ▲ read right ("Build") needed to author on it
 │  CONSUMERS      ▼                                             │
 │   report ─ board ─ alert ─ notebook ─ subscription ─ export ─ embed ─ feed (SQL, Excel) ─ AI ─ MCP
 │   (each: exactly one model; layout, local measures, filters; asks only through MQ / Drishti SQL)
 │
 └─ PYTHON ITEMS (transform, column, measure, visual, cell): code, run only by the console backend's sandbox
```

### 2.2 Vocabulary

| Term | Definition | Item kind / file | Owner of |
|---|---|---|---|
| **pack** | an installed domain package: kinds, connectors, vocabulary (meanings, units), references (graph), calendars, entitlements | not a BI item | the *roots*: keys, meanings, security of kinds |
| **kind** | an entity type of a pack (`trade`, `netting-set`), served by one or more connectors | pack YAML | the physical data (in its source) |
| **dataset** | one governed **table** over a root: a pack kind, a connector query, a Python transform, an SQL view or another model's answer; narrowed by `extends` (subset, `where`, grain, derive, group) | `dataset`, `config/bi/datasets/*.dataset.yaml` | grain, keys, columns, meanings, time mode, refresh, row rules, masks, hidden columns |
| **generated dataset** | the automatic dataset of a pack kind (`pack.<pack>.<kind>`), certified with the pack, read-only | generated | the pack |
| **model** | datasets (by alias, at a version range) + relationships + measures + hierarchies + perspectives + calc groups + parameters + calendars + security (only tighter) + contract + `ai:` + tests/golden | `model`, `config/bi/models/*.model.yaml` | the semantics consumers rely on |
| **implicit model** | the model made (not stored) for a consumer naming one dataset; promotable | none (computed) | — |
| **perspective** | a named subset of a model's fields and measures for a team or task; a view, not a security boundary | inside a model | — |
| **consumer** | an item that asks a model: report, board, alert, notebook, subscription, export, embed, feed, AI conversation, MCP session | its own kind | layout, local measures, page filters |
| **model query (MQ)** | the one structured query object (versioned) | YAML/JSON, `mq: 1` (v2 in B) | — |
| **Drishti SQL (DSQL)** | read-only SQL whose tables are a model's aliases or datasets and whose measures are `MEASURE('name')` | text, `sql: 1` | — |
| **DEL** | the Drishti expression language (a safe, compiled subset of Python expression syntax, 3.3) | inside YAML, MQ `define`, DSQL expressions | — |
| **Python item** | sandboxed code with a typed interface | `python`, `config/bi/python/*.python.yaml` | code, manifest tests |
| **workspace** | a container of items with members (admin, member, contributor, viewer) and an audience | — | access to items (not to data: data access is security, section 5) |
| **reader** | the person (or service principal) on whose behalf a query runs | — | — |
| **security signature** | the hash of a reader's *effective* security on a model (resolved row predicates, masks, hidden columns) | computed | cache sharing and audit (5.6) |
| **generation** | a monotonic version of a dataset's data (a load, a live batch, a refresh) | computed | cache validity, standing-query cursors |

### 2.3 Cardinalities and invariants

* A pack has many kinds; a kind has exactly one generated dataset per site; any number of user datasets extend it.
* A dataset belongs to any number of models (by reference, at a semver range). A model has 1..N datasets (default
  limit `drishti.bi.model.max-datasets: 200`, planned: no such limit is built yet).
* A consumer references **exactly one** model at a major version (D2). The only exception remains BI_MODEL_SPEC §4.3
  report links across owners. A board is one model (BI_MODEL_SPEC §11 Q3).
* Every item has a stable id, an owner, a workspace, a semver version, a lifecycle (`draft`, `published`,
  `deprecated`, `retired`) and an audit trail.
* Lineage is total: report → model → dataset → … → pack kind → connector. Check refuses a dataset whose root cannot be
  traced (A).

---

## 3. Languages

Four languages, one plan. Each has a single parser on the Java server (the console never parses DEL or DSQL for
meaning; it uses `/validate` endpoints for completion and errors).

```
  YAML items ──► item parser ──► catalogue (datasets, models, consumers, python)        ┐
                                                                                          │ resolve
  DEL text (in YAML, MQ define, DSQL expressions) ──► DEL parser ──► typed expression AST ├──────────►  LOGICAL PLAN
  MQ (YAML/JSON) ──► MQ parser + validator ──► semantic query ───────────────────────────┤             (one IR, 3.6)
  DSQL text ──► SQL parser ──► SQL AST ──► binder ──► semantic query (+ relational ops) ──┤
  Python items ──► manifest parser ──► Py nodes (transform / column / measure / visual) ─┘
```

### 3.1 YAML: the structure language

YAML is for **structure**: what exists, how it relates, who may see it, what is promised. It never carries
procedural logic (that is DEL, SQL or Python). Every kind has a **published JSON Schema** (`/api/v1/bi/schemas/<kind>`,
generated from the Java records), used by Check, the YAML tab's completion, the CLI and CI.

| Kind | Top-level keys (v2) | Spec |
|---|---|---|
| `dataset` | `rachana, dataset, version, id, title, description, owner, source \| extends, columns, where, grain, derive, group, time, refresh, security, ai, tests, rollups` | BI_MODEL_SPEC §1; derivation keys below (A) |
| `model` | `rachana, model, version, id, datasets, relationships, measures, hierarchies, perspectives, calc-groups, parameters, calendars, security, contract, ai, golden, tests, rollups, lifecycle, layout` | BI_MODEL_SPEC §2 |
| `report` | `rachana, report, version, model, perspective, measures (local), parameters (local, B), pages[visuals, filters, layout: grid \| board], theme, interactions, bookmarks` | BI_GUIDE.md, R6/R7 |
| `alert`, `subscription`, `notebook` | `model` + an MQ or a visual reference + their own delivery keys | BI_GUIDE.md, USER_GUIDE.md |
| `python` | `python, interface, inputs, output, packages, params, limits, source, tests` | BI_GUIDE.md, 3.5 |
| `theme`, `app` (later) | styling, navigation | C, C |

Rules that keep YAML manageable: one item per file; stable ids written by designers, never typed; unknown keys are a
warning; comments and key order survive a designer round trip (8.4); every key a later wave fills is reserved now
(refused with `DRS-9129` until its wave) so formats change once.

**Dataset derivation (A), the full shape:**

```yaml
rachana: 2
dataset: live-trades
version: 1.0.0
extends: pack.trading.trade@^1          # or source: { ... } for a new root
columns: [tradeId, desk, book, currency, notional, mtm, status, updatedAt]   # subset; keys cannot be dropped
where: "status == 'LIVE'"                # DEL, row scope
grain: { one-row-per: tradeId, keep: latest by updatedAt }                  # explicit de-dup, never a silent distinct
derive:
  notionalUsd: { expr: "notional * fx(currency, 'USD')", type: decimal, meaning: amount, unit: { currency: USD } }
  tenorBucket: { python: buckets.by_tenor, inputs: [maturityDate], type: string, meaning: category }
  deskName:    { expr: "related(desk.name)", type: string }                 # one hop along a pack reference (A)
group:                                    # optional: a coarser grain, declared
  by: [desk, currency]
  columns: { notionalUsd: sum, mtm: sum, trades: count }
  grain: { says: One row per desk and currency, keys: [desk, currency] }
security: { masked: [book] }              # only adds
```

### 3.2 The four languages at a glance

| | YAML | DEL | Drishti SQL | Python |
|---|---|---|---|---|
| Purpose | structure, contracts, security declarations | scalar and aggregate calculations, filters, row rules | ad-hoc and view queries over a model | prep, statistics, quant, custom visuals |
| Who writes it | designers (people rarely) | authors in the expression editor; AI drafts | analysts, developers, external tools, AI | quants, data engineers; AI drafts |
| Evaluated | — | compiled into the plan (SQL in DuckDB/source) | compiled into the same plan | console backend sandbox, as a plan node |
| Can it read data the reader may not see? | — | no: compiled after security | no: same plan, same security | no: inputs are masked MQ answers |
| Side effects | — | none (closed language) | none (read-only by grammar) | none outside the scratch folder (sandbox) |

### 3.3 DEL: the Drishti expression language (Python-like)

**Product-owner requirement (2026-10-12): DEL reads like Python.** DEL v2 is a **safe, side-effect-free subset of
Python's expression grammar**: every DEL v2 expression is a syntactically valid Python expression (it parses with
`ast.parse(text, mode="eval")`), but only the constructs below are accepted, and it is **compiled on the Java server
into the plan, never executed by a Python interpreter**. It replaces the Rachana-EL-flavoured syntax of W1 (`&&`,
`?:`, `[Measure]`, positional conditions) through one converter (3.3.7). Items declare `expressions: 2` (the default
for new items from A).

#### 3.3.1 Scopes: row, rule, aggregate, context

| Scope | Where | May reference | Aggregates? | Result |
|---|---|---|---|---|
| **row** | `derive.*.expr`, `columns.*.expr`, dataset `where`, `grain.keep`, MQ/DSQL row filters | columns of its own dataset (bare `notional` or `trades.notional`); `related(desks.name)`, one many-to-one hop (A); `param.x` (B) | no (`DRS-9127` "an aggregate belongs in a measure") | one value per row |
| **rule** | `security.rows[].where` | own dataset's columns; `user.<attribute>` | no | `True`/`False` per row; **never removable** by any function |
| **aggregate** | `measures.*.expr`, MQ `define`, `having`, report local measures | `alias.column` **inside an aggregate**, `measure('name')`, `param.x` | yes; a bare column outside an aggregate is `DRS-9127` with the fix "wrap it: `sum(trades.mtm)`" | one value per group of the answer |
| **context** *(B)* | the first argument of `calc`, `share_of`, `parent_of`, `as_of` | as aggregate | yes | one value per group, in a modified filter context (3.3.5) |

**Row context** is "this row of this dataset": `notional * fx(currency, 'USD')` is computed for each trade.
**Aggregate context** is "the rows of this group, after security and filters": `sum(trades.mtm)` in the bar for desk
RATES is the sum over the RATES trades this reader may see.

#### 3.3.2 Grammar (the accepted subset of Python)

```
expr        := ifexpr
ifexpr      := orexpr ["if" orexpr "else" expr]                 # a if cond else b
orexpr      := andexpr ("or" andexpr)*
andexpr     := notexpr ("and" notexpr)*
notexpr     := "not" notexpr | comparison
comparison  := arith (compop arith)*                           # chained 0 < x <= 10 allowed
compop      := "==" | "!=" | "<" | "<=" | ">" | ">=" | "in" | "not in" | "is" | "is not"   # is / is not only with None
arith       := term (("+" | "-") term)*
term        := factor (("*" | "/" | "//" | "%") factor)*
factor      := ("-" | "+") factor | power
power       := primary ["**" factor]
primary     := atom | call | field
field       := NAME ("." NAME)*                                 # trades.mtm, trades.tradeDate.month, user.desks, param.shock
call        := FUNCNAME "(" [arg ("," arg)* [","]] ")"          # catalogue functions only (3.3.4); never a method call
arg         := expr | NAME "=" expr                             # keywords: where= over= ignore= keep= via= by= desc= to= at= unit=
atom        := NUMBER | STRING | "True" | "False" | "None" | "ALL" | list | "(" expr ")"
list        := "[" [expr ("," expr)* [","]] "]"                 # for in / not in, and field lists
STRING      := '…' | "…"            (no f-strings, bytes or triple quotes)
NUMBER      := 12 | 1.5 | 1e6 | 1_000_000
comment     := "#" … end of line    (in YAML block scalars: expr: |)
```

**Refused** (`DRS-9127`, naming the construct): assignment and walrus, `lambda`, comprehensions, generators,
`import`, `await`, subscripts and slices, method calls (`trades.mtm.sum()`), `*args`/`**kwargs`, f-strings, dict, set
and tuple literals, attribute access other than `alias.column[.datepart]`, `user.x`, `param.x`, `fn.x(…)`, and any name
outside the field, measure and function catalogues. Bounds kept: 2,000 characters (4,000 in block form), depth 64.
**Dates** are `date('2026-10-09')` (and `timestamp('…')`); **null** is `None`.

**Measure references: `measure('name')` — final.**

| Option | Example | Verdict |
|---|---|---|
| `[MTM]` brackets (W1) | `[P&L 1d] / [Exposure]` | not Python (a list literal), clashes with list literals for `in` |
| bare identifiers | `MTM / Exposure` | Python-like, but most measure names are not identifiers (`P&L 1d`, `Exposure (EE peak)`), and bare names collide with aliases and functions as models grow |
| **`measure('name')`** | `measure('P&L 1d') / measure('EE peak')` | **chosen**: valid Python, any name works, unambiguous, mirrors DSQL `MEASURE('name')`, accepts a stable id (`measure('m_mtm')`); the editor's completion writes it, so its length costs nothing |

The designer shows measure references as chips and writes `measure('…')` in the YAML; a rename keeps the binding by
stable id and the server rewrites the text.

#### 3.3.3 Values and edge cases (every function follows these)

| Case | Rule |
|---|---|
| `None` | arithmetic with `None` gives `None`; comparisons with `None` are `False` (use `is None`); aggregates skip `None`; `count(x)` counts non-`None` |
| division | `/` is floating point and `None` on a zero divisor (never an error, never infinity); `//` and `%` likewise |
| empty set | `sum`, `avg`, `min`, `max` of no rows are `None`; `count` and `distinct` are `0` |
| masked values | a masked column reads as the text `•••` in row scope; aggregates over it are `None` and the answer marks them masked; a filter, sort or `in` on it is `DRS-9161` |
| currencies | summing a per-row-currency amount across currencies is the currency guard (`DRS-9158`); `convert(…)` or the currency in the group lifts it |
| types | integer with decimal gives decimal; `date - date` gives integer days; text with number is `DRS-9127` (no implicit casts); `in [...]` items must match the field's type |
| booleans | `and`, `or`, `not` and `… if c else …` take booleans only (no truthiness of numbers or text) |

#### 3.3.4 Function catalogue (summary; the full reference is BI_LANGUAGE_REFERENCE.md)

| Family | Signatures | Scope | Status |
|---|---|---|---|
| scalar | `abs(x)`, `round(x, n=0)`, `coalesce(a, b, …)`, `upper(s)`, `lower(s)`, `len(s)`, `contains(s, part)`, `startswith(s, p)`, `endswith(s, p)`, `date(s)`, `year(d)`, `quarter(d)`, `month(d)`, `day(d)`, `week(d)`, `days_between(a, b)`, `business_days_between(a, b)` | row, aggregate | built (renamed) / A |
| conversion | `fx(ccy, to, at=None)`; `convert(agg, to='USD', at='as-of' \| 'trade-date')` | row / aggregate | A, B |
| lookup | `related(alias.column)` | row | A |
| aggregate | `sum(x, where=None)`, `avg`, `min`, `max`, `count(alias \| x, where=None)`, `distinct(x, where=None)`, `ratio(num, den)`, `percentile(x, p)`, `avg_by(agg, by=[fields])` (two-level, B) | aggregate | built (`where=` keyword new) |
| time intelligence | `ytd(agg, date)`, `mtd`, `qtd`, `prior_period(agg, date)`, `same_period_last_year(agg, date)`, `running(agg, date)`; `fiscal_ytd(agg, date)` (B) | aggregate | built |
| windows (over the answer) | `lag(agg, n, order, by=[…])`, `lead`, `rank(agg, desc=True, by=[…])`, `dense_rank`, `percent_rank`, `moving_avg(agg, n, order, by=[…])`, `moving_sum`, `moving_min`, `moving_max`, `cumulative(agg, date)` | aggregate | built (keywords new) |
| balances | `last(agg, date)`, `first(agg, date)`, `opening(agg, date, period='month')`, `closing(agg, date, period='month')`, `avg_over(agg, date)` | aggregate | B |
| filter context | `calc(agg, where=…, ignore=[…] \| ALL, keep=[…], via='rel_id')`, `share_of(agg, over=[…] \| ALL)`, `parent_of(agg, hierarchy='name')` | context | B |
| time travel | `as_of(agg, date_expr)`, `shift_date(d, n, unit='business-day')`, `asof()` | context | B |
| user functions | `fn.name(…)` from a `functions` library item: macro-expanded and type-checked | any | B |

#### 3.3.5 Filter context: `calc` (designed now, built in B)

A measure is evaluated per group of the answer in a **filter context** of four layers:

```
  1. SECURITY  row rules and masks per dataset for this reader     fixed: no function can touch it
  2. QUERY     MQ where / DSQL WHERE / report, page, visual filters, slicers, drill, cross-filter
  3. GROUP     the current group's values of the answer's fields (desk == 'RATES', month == 9)
  4. MEASURE   the measure's own where= conditions and calc() edits
```

`calc` is the one CALCULATE-like function; it edits **layers 2 and 3 only**:

| Keyword | Meaning | DAX pattern | Compiles to |
|---|---|---|---|
| `where=cond` | add conditions (AND) | `CALCULATE(x, KEEPFILTERS(…))` | `FILTER (WHERE …)` on the aggregate |
| `ignore=[fields]`, `ignore=ALL` | drop query and group filters on those fields, or all | `REMOVEFILTERS`, `ALL` | a grouped sub-plan at the coarser grain, joined back on the remaining fields |
| `keep=[fields]` | drop every query and group filter except those | `ALLEXCEPT` | `ignore` with the complement |
| `via='rel_id'` | use an inactive relationship (the active one for the same pair is switched off) | `USERELATIONSHIP` | relationship substitution in the sub-plan |

```yaml
measures:
  MTM:              { expr: "sum(trades.mtm)" }
  Open MTM:         { expr: "sum(trades.mtm, where=trades.status == 'Live')" }
  MTM all desks:    { expr: "calc(measure('MTM'), ignore=[desks.name])" }          # ignores the desk slicer
  MTM % of total:   { expr: "share_of(measure('MTM'), over=ALL)" }                 # of what *you* may see
  MTM % of desk:    { expr: "share_of(measure('MTM'), over=[books.name])" }        # a book's share within its desk
  MTM % of parent:  { expr: "parent_of(measure('MTM'), hierarchy='Desk and book')" }
  MTM by settle:    { expr: "calc(measure('MTM'), via='r_5')" }
  MTM day on day:   { expr: "measure('MTM') - as_of(measure('MTM'), shift_date(asof(), -1))" }   # B
```

Rules: (1) **security is never in scope**, so `ignore=ALL` means "all *you* may see"; (2) closed and compositional,
nesting depth ≤ 3; (3) **no context transition**: iteration is explicit (`avg_by`); (4) every sub-plan keeps grain
safety and fan-out refusal; (5) the incremental class is derived per function (4.6); (6) **calculation groups** are
templates in the same syntax (`YTD: "ytd(SELECTED, calendar.date)"`, `SELECTED` being the measure the item applies
to), expanded at bind time; **parameters** bind as `param.name` and are part of the cache key.

#### 3.3.6 How DEL differs from Python and from DAX

| | Python | DEL | DAX |
|---|---|---|---|
| Execution | interpreted | compiled into the plan (SQL); never run as code | engine-evaluated |
| Loops, assignment, side effects | yes | none | none (`VAR` only) |
| `sum(x)` | over an iterable | over the group's rows, after security | `SUM(col)` |
| Conditional aggregate | — | `sum(x, where=cond)` | `CALCULATE(SUM(x), cond)` |
| Null | `None`, arithmetic raises | `None` propagates, never raises | `BLANK()` with coercions |
| Division by zero | `ZeroDivisionError` | `None` | `DIVIDE` or an error |
| Truthiness | any value | booleans only | coercions |
| Context override | — | `calc(…)`; security outside it | `CALCULATE`, with context transition |

#### 3.3.7 Today's syntax → DEL v2, and the converter

| W1 / 2.1 syntax | DEL v2 |
|---|---|
| `sum(trades.mtm, trades.status == 'Live')` | `sum(trades.mtm, where=trades.status == 'Live')` |
| `[P&L 1d] / [Exposure (EE peak)]` | `measure('P&L 1d') / measure('Exposure (EE peak)')` |
| `c ? a : b`, `if(c, a, b)` | `a if c else b` |
| `&&`, `\|\|`, `!` | `and`, `or`, `not` |
| `a = b` (equality) | `a == b` |
| `x in ('A', 'B')` | `x in ['A', 'B']` |
| `true`, `false`, `null` | `True`, `False`, `None` |
| `trades.desk in user.desks` | unchanged (already valid) |
| `startsWith(s, p)`, `length(s)` | `startswith(s, p)`, `len(s)` |
| `rank(sum(x), 'asc')`, `lag(agg, 1, d, trades.desk)` | `rank(sum(x), desc=False)`, `lag(agg, 1, d, by=[trades.desk])` |

**Converter** (`drishti.py bi migrate --expressions`, A): parse every expression with the W1 `ExprParser`, print the
AST as DEL v2 (a printer, never text substitution), re-parse with the v2 parser and assert both compile to **identical
SQL** for every shipped item (the equivalence harness of BI_MODEL_SPEC §8.6). It rewrites YAML in place keeping
comments, sets `expressions: 2`, and is reversible from saved states. The old syntax is read with a warning
(`DRS-9100`, "old expression syntax: run the converter") until the end of A, then refused.

#### 3.3.8 Documentation and in-product help (a deliverable of every wave that touches DEL)

* **`docs/guides/BI_LANGUAGE_REFERENCE.md`** (created in A, grown each wave): the grammar of 3.3.2; the scopes; **every
  function** with signature, description, return type, two or more examples and its edge cases (nulls, masked values,
  currencies, empty sets, types); filter-context rules with before/after tables; every `DRS` code an expression can
  raise with cause and fix; a **cookbook of 40+ finance and risk recipes** (MTM and P&L by desk, open vs matured,
  notional in USD at trade date and at as-of, % of desk, % of total you may see, counterparty rank, top-10 exposure,
  limit utilisation and headroom, collateral cover, percentile VaR-style, YTD/MTD/prior period, same period last year
  on a holiday calendar, moving averages, day-on-day change, closing balance per month, average daily balance,
  exposure by tenor bucket, ageing buckets, weighted average rate, coupon and duration, DV01 by currency, breach
  counts, …); how DEL differs from Python and from DAX, with a DAX → DEL mapping table.
* **In-product help:** the ExpressionEditor completes fields (with type, meaning, format), measures (inserting
  `measure('…')`), functions (signature, one-line description, one example, a link to the guide) and keyword
  arguments; hovering shows the function's card; problems appear inline with the fix as a button; a *Try it* strip
  shows the value for the current selection (a server answer, as the author).
* **One catalogue**: `bi/expr/functions.yaml` on the server (name, signature, scopes, description, examples, edge
  cases, since-wave) feeds validation, completion, `describe` for AI and the function tables of BI_LANGUAGE_REFERENCE.md (a
  test fails when the guide and the catalogue disagree).

### 3.4 Drishti SQL (DSQL): read-only SQL over models

**What it is.** A SQL dialect whose **tables are the aliases of one model** (or the datasets of an implicit model),
whose **measures are `MEASURE('name')`**, and which compiles into **the same logical plan as MQ**. It is never sent
to a connector as text; a connector receives only the fragments the planner pushes down (4.4).

```sql
-- sql: 1   model: counterparty-cockpit@1   (the scope is set by the endpoint or the item, not inside the text)
SELECT counterparties.counterpartyName      AS counterparty,
       MEASURE('MTM')                       AS mtm,
       MEASURE('PFE peak')                  AS pfe,
       SUM(trades.notional)                 AS notional_local       -- an inline aggregate = a local measure
FROM   trades                                                     -- tables: model aliases
JOIN   counterparties                                              -- no ON: the model's relationship is used
WHERE  trades.currency IN ('USD', 'EUR')
  AND  trades.tradeDate >= DATE '2026-10-01'
GROUP  BY counterparties.counterpartyName
HAVING MEASURE('MTM') < 0
ORDER  BY pfe DESC
LIMIT  20
```

**The dialect** (ANSI SELECT subset; one parser, section 13 decision 1):

| Allowed | Refused (`DRS-9170`–`9179`, reserved for DSQL) |
|---|---|
| one `SELECT` or `WITH … SELECT`; `UNION [ALL]` of compatible selects | any DDL/DML, `ATTACH`, `COPY`, `PRAGMA`, `SET`, multiple statements |
| `FROM` model aliases; `JOIN` without `ON`, or `ON` equal to a model relationship; `LEFT JOIN` along a relationship | joins on arbitrary predicates between aliases (`DRS-9172`: "use a relationship, or ask the model owner for one") |
| `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`, `LIMIT/OFFSET`, `CASE`, `CAST`, `COALESCE`, date/text functions mapped to DEL | table functions, `read_parquet`, file or URL access, user-defined functions, `random()` in a grouped key |
| aggregates `SUM AVG MIN MAX COUNT COUNT(DISTINCT) PERCENTILE_CONT` on alias columns (become local measures) | aggregates over measures (`SUM(MEASURE('x'))`; use a DEL measure) |
| `MEASURE('name')`, `MEASURE("name")`, by stable id `MEASURE('m_mtm')` | a measure in `WHERE` (use `HAVING`) |
| window functions over the **result** (`RANK() OVER (ORDER BY mtm)`) | window functions over rows of a dataset (would need row access: use a derived column or Python) |
| CTEs and subqueries in `FROM` whose bodies are themselves DSQL over the same model | references to another model (one model per query, D2); a reference to a connector, kind or file |
| `AS OF DATE '2026-10-09'` / `FOR LIVE` / `FOR WINDOW LAST 1 HOUR` table clause (time, one per query) | two different times in one query (W1–A; mixed as-of comparisons are DEL `as_of` in B) |
| `SELECT … FROM trades` without `GROUP BY` = `shape: rows` (an extract, with its row cap) | `SELECT DISTINCT` on a fact without grouping (a silent de-dup changes grain: use `GROUP BY`) |

**How it compiles.** The binder resolves every name against the model (`describe` as the caller, so hidden columns
do not exist and masked ones are typed as masked). Then:

* An **MQ-shaped** statement (one grouped select over model fields, measures, filters, having, order, limit) becomes
  exactly one MQ; `meta.query` in the answer shows that MQ. Most SQL a person writes is MQ-shaped.
* A **composite** statement (CTEs, `UNION`, subqueries, result windows) becomes a plan whose **leaves are semantic
  aggregates** (each an MQ, with security applied) and whose inner nodes are relational operators over those answers
  (`Join`, `Union`, `Project`, `Filter`, `Window`, `Sort`, `Limit`) executed on the Java tier in DuckDB over the leaf
  answers. Because leaves are already secured and masked, a composite query can never see more than its leaves; a
  join or filter on a masked value is refused (`DRS-9161`).

**Where DSQL is allowed:**

| Place | Scope | Wave |
|---|---|---|
| Analyse SQL cells (replacing the console's SQLite and the browser's DuckDB-Wasm) | the notebook's model | B |
| A **SQL view dataset** (`source: { sql: "...", model: x@1 }`): a reviewed DSQL query whose answer is a table | one model | A |
| `POST /api/v1/bi/sql`, the CLI `drishti.py bi sql`, tests (`tests: [{ sql: …, expect: … }]`) | one model | A |
| The SQL endpoint for external tools (wire protocol) and the Excel feed | one model per connection/catalog | C |
| AI and MCP (`query_model_sql`) | one model | C |
| **Not** in measures, row rules, derived columns (DEL is the language there) | — | — |

### 3.5 Python: sandboxed workers on the console backend

**Item interfaces** (all typed, all with manifest tests, all run only by the console backend's runner):

| Interface | Function | Input | Output | Used by | Plan node |
|---|---|---|---|---|---|
| `transform` | `run(frames: dict[str, DataFrame], params) -> DataFrame` | named MQ answers (masked, as the reader) | a table matching declared `columns:` | a dataset `source: python` | `PyTransform` (a leaf producing a table) |
| `column` *(A)* | `derive(frame, params) -> Series` (vectorised) | the declared input columns of the dataset, masked | one column of declared type | dataset `derive: { python: … }` | `PyColumn` after the dataset's secure scan |
| `measure` | `compute(table, params) -> float` per group (today) → `compute_groups(frame, by, params) -> DataFrame` (R8, B) | the group's rows of declared inputs, masked | one number per group | model measures; any visual (R8) | `PyAggregate` after grouping |
| `visual` | `render(table, params) -> spec` | the visual's MQ answer | ECharts/Vega-Lite spec, SVG, PNG (data, never HTML) | python visuals | `PyRender` (presentation, still on the server) |
| `cell` | free code over `extracts` | the notebook's MQ answers | frames, charts, text | Analyse notebooks | `PyCell` |

**Invariants:**

1. **Inputs are always masked answers of the plan**, never connector reads: a masked column arrives as `•••` (or
   null for measures over it), hidden columns do not exist, row rules have already filtered. Python cannot unmask what
   it never received.
2. **Python is a plan node, not a side channel.** Its inputs are sub-plans; its output re-enters the plan typed by
   the manifest; the planner places it on the console tier; results are cached by (item version, input generations,
   security signature, params).
3. **Sandbox** (PYTHON_BI §7, kept and hardened): isolated interpreter, empty environment, no network (namespace +
   audit hook), scratch folder only, CPU/memory/time/output limits, package allow-list. Target change (decision 7): a
   **fork-server** per console process (a pre-imported pandas/numpy parent that forks one child per call), keeping
   one-process-per-call isolation while removing interpreter start-up from latency.
4. **No browser Python.** Report and Analyse Python run on the console backend (B). A personal browser console is
   future work and, if ever built, will receive only MQ answers like any other consumer.

### 3.6 MQ: the one query object, and where all four languages meet

MQ v1 (BI_MODEL_SPEC §5) stays the **contract** for UI, API, AI, MCP. It is also the **semantic leaf** of the logical
plan: DSQL compiles to MQ (or to MQ leaves plus relational nodes); DEL is carried inside MQ (`define`, measures, row
filters); Python nodes take MQ answers in and give typed tables out.

MQ v2 (B) adds only what the later language features need, all reserved in v1:

```yaml
mq: 2
model: counterparty-cockpit@1
perspective: credit-officer          # optional: field list restricted to a perspective (names outside are DRS-9153)
params: { shock: -0.1 }              # what-if parameters (B)
calc: { Time: YTD }                  # calc-group items applied to the selected measures (B)
select: { fields: [counterparties.sector], measures: [MTM, MTM % of desk] }
time: { as-of: 2026-10-09 }          # or live: true | window: { last: 1h }
options: { explain: true, budget: { millis: 2000, rows: 1000000 } }
```

**The logical plan (the one IR).** Every request, whatever the language, becomes a tree of these nodes:

| Node | Meaning | Produced by |
|---|---|---|
| `SecureScan(dataset, reader, time)` | one dataset's rows for this reader at this time: row rules ANDed, masks projected, hidden columns removed | the security rewriter (5.4), always, for every dataset touched |
| `Derive(exprs)` / `PyColumn(item)` | row-scope columns | dataset `derive`, `columns.*.expr` |
| `Dedup(keys, keep)`, `Regroup(by, aggs)` | explicit grain, grouping | dataset `grain`, `group` |
| `SemiJoin(fact, dim, rel)` | propagate a dimension's filter (security or query) to a fact | relationships, rule propagation |
| `Aggregate(fact, by, measures)` | a measure's leaves at the query's fields, along many-to-one paths only | MQ measures split per fact (grain) |
| `Meet(parts, on)` | full outer join of per-fact aggregates on shared fields | multi-fact queries |
| `Context(sub-plan, edits)` | a sub-plan in a modified filter context (B) | DEL context functions |
| `Window`, `Having`, `Sort`, `Limit`, `Totals(levels)` | post-aggregation | MQ, DSQL |
| `Rel(op)` | relational ops over secured answers (Join/Union/Project/Filter) | composite DSQL |
| `PyTransform`, `PyAggregate`, `PyRender`, `PyCell` | Python nodes on the console tier | Python items |
| `Answer(columns, meta)` | typed, masked rows + meta (generations, tiers, warnings) | always |

```
   "SELECT sector, MEASURE('MTM'), MEASURE('PFE peak') ... AS OF DATE '2026-10-09'"   ─┐
   { mq: 1, select: { fields: [counterparties.sector], measures: [MTM, PFE peak] } } ──┤ same plan, same cache key
   report bar visual (fields dragged)  ──► VisualMq ──► MQ ───────────────────────────┘
                                │
             Answer ◄─ Meet(on sector) ◄─┬─ Aggregate(trades, by sector, MTM) ◄─ SemiJoin ◄─ SecureScan(trades, hana)
                                         │                                                  SecureScan(counterparties, hana)
                                         └─ Aggregate(netting_sets, by sector, PFE peak) ◄─ SemiJoin ◄─ SecureScan(netting_sets, hana)
```

Canonicalisation (sorted keys, ids instead of names, resolved relative dates) makes the **same question in any
language produce the same plan hash**, so caches and standing queries are shared across SQL, MQ and the designer.

---

## 4. Query architecture

### 4.1 The pipeline

```
 request (MQ | DSQL | visual | alert | AI | MCP)
   │
   ▼
 ┌──────────┐  ┌──────────┐  ┌───────────┐  ┌──────────┐  ┌───────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐
 │ 1 Parse  │─►│ 2 Resolve│─►│ 3 Bind    │─►│ 4 Secure │─►│ 5 Guard   │─►│ 6 Optimise│─►│ 7 Place  │─►│ 8 Run   │─► Answer
 │ MQ/DSQL  │  │ model@v, │  │ names→ids │  │ rewriter │  │ fan-out,  │  │ pushdown, │  │ tiers,   │  │ async,  │
 │ DEL      │  │ contract,│  │ types,    │  │ per      │  │ currency, │  │ rollups,  │  │ fragments│  │ deadline│
 │          │  │ access   │  │ scopes    │  │ dataset  │  │ semi-add. │  │ cache     │  │          │  │ budgets │
 └──────────┘  └──────────┘  └───────────┘  └──────────┘  └───────────┘  └──────────┘  └──────────┘  └─────────┘
      DRS-9150      9151/9153      9152/9154/9155   (cannot fail;   9156/9158/9159/9160   (never changes   9022–9027
      9170–9179                                     adds nodes)                           an answer)
```

| Stage | Component (target package `server/bi/plan/…`) | Notes |
|---|---|---|
| 1 Parse | `MqParser` (built), `DelParser` v2 (Python-like subset, A; replaces `ExprParser`), `DsqlParser` (A) | pure, cached by text hash |
| 2 Resolve | `ModelResolver`: model at version, perspective, contract, consumer envelope (5.3) | flattening cached per model version |
| 3 Bind | `Binder`: names and ids to typed fields; DEL scopes checked; local measures merged | produces the *semantic query* |
| 4 Secure | `SecurityRewriter`: every dataset reference becomes `SecureScan(reader)`; propagation as `SemiJoin` | runs **before** optimisation, so the optimiser can only move security, never drop it |
| 5 Guard | `Guards`: fan-out (`9160`), currency (`9158`), semi-additive (`9159`), reachability (`9156`) | warnings in W1, refusals from A |
| 6 Optimise | `Optimiser`: rule-based (below) | every rule is answer-preserving and property-tested |
| 7 Place | `TierPlanner`: assigns fragments to source / Java / console | simple cost model from layer metadata |
| 8 Run | `Executor`: fragments in parallel on virtual threads; admission, deadline, cancellation, budgets (6.3) | streams Arrow between tiers |

### 4.2 The optimiser (rule-based, answer-preserving)

| Rule | What it does | Guard |
|---|---|---|
| Predicate pushdown | moves `where` and `SemiJoin` filters into `SecureScan` and then into the source fragment | a predicate on a masked column is refused earlier (stage 4/5) |
| Projection pruning | reads only the columns the plan uses (plus row-rule columns) | mask projection stays |
| Grain split | measures split into per-fact `Aggregate`s (built: `SqlCompiler.expandMixed`) | fan-out guard |
| **Aggregation (rollup) matching** | replaces `Aggregate(fact, by, measures)` + its scans by a rollup read when the rollup's `by` covers fields, filters and **row-rule columns**, and holds the measures' components | a rollup is per dataset version and generation; the fact's row-rule columns must be in `by`, else no match (security first). A dimension whose rules restrict nothing for the reader (`True` for their roles, no filter) is not joined at all; a dimension joined only for its rules (a `SemiJoin`) keeps the match when the fact joins it directly, many-to-one, on a column in `by`: each rollup row belongs to one dimension row, so the rule keeps or drops it whole (engine `QueryService#route` and plan agree; tested equal to the columns layer for every reader) |
| Cache lookup | before running any subtree whose canonical hash, security signature and generation vector match a cached answer (6.1) | — |
| Common subplan | shares identical subtrees across the visuals of one page request and across standing queries (4.6) | same signature only |
| Context lowering *(B)* | `Context` nodes to filtered aggregates (`where=`) or coarser sub-plans + `Meet` (`ignore`, `share_of`) | — |
| Top-N resolution | `top` filters as their own grouped sub-plan feeding an `in` filter (built) | — |
| Source capability | pushes `Aggregate` into the source when its connector declares SQL and every scan of the fragment is in one store (today's `direct` rule) | otherwise aggregates in Java/DuckDB |

### 4.3 Physical tiers

| Tier | Fragments it runs | Engine | Moves |
|---|---|---|---|
| **Source** (via the pack's connector) | `SecureScan` (+ pushed filters, projection, sometimes `Aggregate`) | the connector's SQL (DuckDB over Delta/Parquet, PostgreSQL), or the connector's fetch | the smallest result the source can give |
| **Java server** | everything else: refresh-layer and rollup reads, `Derive`, `Dedup`, `SemiJoin`, `Aggregate`, `Meet`, `Context`, `Window`, `Totals`, `Rel`, masks, standing-query state | embedded DuckDB per query (bounded memory), live tables in memory | Arrow batches |
| **Console backend** | `Py*` nodes; rendering exports and chart specs; formatting | pyrunner fork-server workers | Arrow in, Arrow/JSON out |
| **Browser** | none (draws) | — | — |

**Refresh layers** are a physical choice for `SecureScan`, not a semantic one: read the columns layer (Parquet per
business date, disposable), the live table (in memory), or the source (`direct`). The answer's `meta.layer` and
`explain` say which.

### 4.4 Execution

* **Admission** first (6.3): a query gets a slot or waits (bounded) or is refused `DRS-9022` with the reason.
* Fragments run concurrently on virtual threads; source fragments use the connector's pool (6.6); each fragment has
  a deadline derived from the query's budget; cancelling the request (page left, slicer changed) cancels its
  fragments (the console sends `DELETE /mq/run/{id}` or closes the stream).
* Arrow flows between tiers; the Java tier materialises at most `drishti.bi.query.max-memory-mb` per query (DuckDB
  `memory_limit`, spill to the scratch dir), else `DRS-9024`.
* **Answer** = rows + totals + meta (`generation` vector, tiers, layer, rollup, cache hit, warnings, canonical MQ, plan
  hash, security signature id *not* the predicates).

### 4.5 Time: live, as-of and windows in one plan

Time is a **plan parameter bound per `SecureScan`**, resolved before the cache key is computed:

| Request | Each dataset's scan reads | Notes |
|---|---|---|
| `time: { as-of: D }` | snapshot/history datasets: partition D (or the latest ≤ D with `as-of: latest-load`); live datasets: their history sampled at end of D, else refused `DRS-9157` | immutable answer: cacheable without expiry until a back-dated load bumps D's generation |
| `time: { live: true }` | live datasets: the in-memory current state; snapshot datasets: the current business date (BI_MODEL_SPEC §1.5 mixed modes) | the answer carries the generation vector; subscribable |
| `time: { window: { from, to } }` | snapshot partitions in the range (≤ `max-days`) | time intelligence uses the model's calendar |
| `time: { window: { last: 1h } }` | history datasets (sampled live or temporal source) | slides when subscribed |
| DEL `as_of(agg, shift_date(asof(), -1))` *(B)* | a `Context` sub-plan whose scans are bound to another date | "today vs yesterday" in one visual |

Because time is bound per scan, **the same plan answers now, as of a date, and over a window**; a model mixes a live
fact with snapshot dimensions by binding each scan to the right partition.

*Not in scope:* knowledge time ("as it was known at 10:00 yesterday", bitemporal). The generation vector records which
data was used, so an answer is reproducible while the layers still hold it (decision 10).

### 4.6 Standing queries and incremental maintenance

Every MQ/DSQL can be **asked** or **subscribed** (BI_MODEL_SPEC §5.8, endpoints built in W1 with full recompute). The
target (B):

```
 connector push / load / refresh ──► dataset generation g+1 + change set Δ (upserts, deletes by key)
                                            │
                       StandingQueryRegistry: subscriptions grouped by (plan hash, security signature)
                                            │  one evaluation per group, however many subscribers share it
                                            ▼
             Maintainer per plan: apply Δ through the plan's operators (rules below), or recompute touched groups
                                            │
              Answer diff (upserts/deletes of answer rows) ──► fan-out to subscribers (throttled, latest wins)
```

| Operator / measure class | Incremental rule |
|---|---|
| `SecureScan` | Δ filtered by the reader's row rules, masked (rules are re-evaluated per changed row) |
| `SemiJoin` on a dimension | a dimension change that flips a key's visibility → re-evaluate the fact groups of that key |
| `sum`, `count`, `avg` (sum+count) | by difference |
| `min`, `max`, `distinct`, `percentile` | recompute the touched groups from the dataset (live table or layer) |
| `Meet`, `Totals` | touched keys only |
| `Context` (`ignore`, `share_of`) | the touched group plus its coarser group(s) |
| windows, time intelligence, Python | recompute on the throttled tick |

**Controls:** throttle per subscription (≥ 100 ms, default 250 ms), latest-wins mailboxes (nothing queues), global
and per-user caps (`DRS-9181/9182`), overrun → doubled throttle and `throttled` status, a per-model **maintenance
budget** (CPU ms per second) beyond which the model's subscriptions degrade to tick-based recompute and say so.
**Correctness:** a subscribed answer equals the asked answer at the same cursor (property test over random update
streams, B acceptance). **Security changes** (a role or `user.*` attribute changes) invalidate the reader's
signature: their subscriptions are re-planned, never patched.

### 4.7 Explain

`options.explain: true` (MQ) or `EXPLAIN SELECT …` (DSQL) returns the plan, annotated after execution:

```yaml
plan: p_8c1f…     signature: s_hana_2   generation: { trades: g-1874, netting_sets: g-77, counterparties: g-12 }
- Answer [rows 18, 41 ms, cache miss]
  - Meet on [counterparties.sector]                     tier: java     3 ms
    - Aggregate trades by sector: MTM                   tier: source   trading-store  22 ms  rows 412 → 18
        SecureScan trades  rule: desk in (DESK-RATES)  pushed   layer: columns 2026-10-09
        SemiJoin counterparties (sector)                pushed
    - Aggregate netting_sets by sector: PFE peak        tier: java     rollup by_cpty  4 ms  (rollup matched)
  warnings: []   guards: fan-out ok, currency ok (USD fixed)
```

Shown under every visual in the designer (*Query* panel: MQ, DSQL equivalent, plan), in Analyse, and to AI (as a
summary). Masked predicates and other readers' rules are never shown: an explain shows *that* a rule applied, and
its text only to people with the `bi-steward` power.

---

## 5. Security architecture

### 5.1 The layers

| Layer | Declared in | Enforced as | Can a consumer change it? |
|---|---|---|---|
| Pack entitlement | the pack (kinds a role may open) | `SecureScan` returns no rows for a kind the reader may not open | no |
| Dataset row rules | `dataset.security.rows` (DEL rule scope, own columns) | ANDed into `SecureScan` | no |
| Dataset masks | `security.masked`, pack `redact` | projection: value → `•••` before any aggregation; masked dims not grouped; measures over masked columns null; filter/sort/top on them `DRS-9161` | no |
| Object-level (hidden) | `security.hidden-for` (dataset or model) | the column/measure does not exist for the reader: absent from `describe`, `DRS-9163` "not available to your role" when a query names it (never its definition or columns); a column hidden makes the measures and `define`s over it unavailable too, and Check warns `DRS-9132` when a hidden measure's columns stay visible to its roles | no |
| Model rules | `model.security.rows.<alias>` (only tighter: AND) | ANDed into the same `SecureScan` | no |
| Model masks | `model.security.masked` (only adds) | same as dataset masks | no |
| Propagation | `propagate: dimension-to-fact` | `SemiJoin` from a restricted dimension into facts along active many-to-one relationships | no |
| The safeguard | `may-see-all` notes | publish refused `DRS-9130` without a rule or a note; the note is audited | no |
| Item access | workspace roles, audience | catalogue | — |
| **Model read ("Build")** | workspace membership or the model's audience | needed to author any consumer on the model or send free MQ/DSQL to it | — |
| **Consumer envelope** | the published consumer (report, board, embed) | a reader with access to a report but *not* model read may run only the MQs that report can generate (5.3) | — |

### 5.2 Enforcement at every tier and in every language

| Path | Where security is applied | Why it cannot be bypassed |
|---|---|---|
| MQ | stage 4 rewriter → `SecureScan` | the optimiser has no rule that removes a `SecureScan` predicate; property test "ANDing never adds a row" |
| DSQL | identical: DSQL binds to the semantic query, then stage 4 | no name in DSQL resolves to a connector, file or kind; composite SQL operates on secured leaves |
| DEL context functions | security is layer 1, outside the editable layers | `calc(…, ignore=ALL)` is defined over layers 2–3 only |
| Source tier | row-rule predicates pushed as `WHERE` with bound parameters; masks applied in the pushed projection or immediately on arrival in Java, **before** any aggregation | the source answer for a reader is never cached without the signature |
| Rollups | a rollup matches only if its `by` holds every row-rule column of the reader's rules; masked columns are not stored in rollup groups for masked readers (the rollup is read and masked like a scan) | security first, then speed |
| Python | inputs are secured answers; worker gets nothing else (no tokens, no network) | the worker cannot call back into the server |
| Standing queries | evaluated as the subscriber; grouped only with identical signatures | signature change re-plans |
| Exports, subscriptions | rendered per recipient | each recipient's own plan |
| AI, MCP | MQ/DSQL as the asking user or token's user; `describe` shows only what they may see | the model provider never receives rows unless the `aggregates` policy is on, and then only that user's secured aggregates |
| Caches | key includes the security signature | users with *different* effective security never share an entry |

### 5.3 Model read ("Build") and the consumer envelope

Power BI separates *Read* (view reports) from *Build* (make new content on the model). Drishti does the same:

* **Model read** (workspace member or model audience): may open the model in the designer, author consumers, run
  free MQ/DSQL, use Analyse and the SQL endpoint on it.
* **Report view only**: the report's published version defines an **envelope**, the set of fields, measures, filters
  and parameters its visuals, slicers and drills can produce. The server accepts from such a reader only MQs inside the
  envelope (checked in stage 2, `DRS-9151` "this report cannot ask that"). So a report can be shared widely without
  opening the model to free querying. (Decision 2.)

### 5.4 The security signature

```
signature(reader, model@version) = hash(
   sorted for each dataset alias: the reader's matching row predicates with user.* resolved to values,
   the effective mask set, the effective hidden set, pack entitlements touched )
```

Two readers with the same desks, roles and masks get the **same signature** and therefore share result caches, rollup
answers and standing-query evaluations; the signature never reveals the predicates (it is an opaque id in meta and
audit). This is the main lever that lets many viewers scale (6.1). (Decision 3.)

### 5.5 Audit

Kept (BI_GOVERNANCE §8) and extended: every query writes `bi-query` with model id and version, consumer id, reader,
signature id, plan hash, tiers, rows, millis, cache hit, refusals; `bi-model-may-see-all` on publish; `bi-sql` keeps
the DSQL text (masked literals stay as written by the user); `bi-python-run` with item version and input generations;
`bi-ai` with the MQ/DSQL the AI produced. The audit log is itself a dataset (`pack.drishti.audit-bi`) so usage and
security reviews are reports (C).

---

## 6. Scalability and performance

### 6.1 Caching layers (all disposable)

| Layer | Holds | Key | Invalidated by | Where |
|---|---|---|---|---|
| L0 parse/plan cache | parsed DEL/MQ/DSQL, flattened models, plan templates | text hash; model version | model publish | per Java node (Caffeine) |
| **L1 result cache** | answers | **canonical plan hash + model version + security signature + resolved time + generation vector** | any generation in the vector moving (never by time-to-live for as-of answers) | per node; optional shared tier later (decision 6) |
| L2 refresh layers | Parquet per dataset/table/business date (columns layer); live tables in memory | dataset signature + date | a load signal for that kind and date; a dataset change | shared filesystem/object store (columns), node memory (live) |
| L3 **aggregations** (rollups) | per-date components of declared measures at a coarser grain; live rollups maintained incrementally | dataset version + rollup + date | as L2 | as L2 |
| L4 Python results | transform outputs, measure groups | item version + input plan hashes + generations + signature + params | any of them | console node (bounded, LRU) |
| L5 standing-query state | per (plan, signature) group: the current answer and operator state | — | unsubscribed for `linger` (30 s) | the Java node owning the model's shard |

**Aggregations, Power BI-style and better.** Rollups are declared per model fact (BI_MODEL_SPEC §2.7) and matched by
the optimiser (4.2). Added in B: **suggested aggregations** from the query log (fields × measures most asked, cost
saved), shown to the model owner as a one-click draft with a test that the rollup and the columns layer agree.
Rollups for `distinct` (HyperLogLog sketches, flagged approximate) and percentiles (t-digest) are an option the
owner must opt into, since they are approximate.

### 6.2 Refresh

Kept: business-date partitions, after-load and scheduled refresh, eager rebuild. Added: **back-dated refresh** (a load
signal for an earlier date bumps that date's generation only); precompute on refresh for declared "hot" queries per
signature (the report's visuals for its top signatures), so the first reader of the day gets cache hits.

### 6.3 Concurrency, fairness and memory

*2.6.7 (BI runs, [section 17, its section 10](#10-fairness-and-admission)):* admission has a cap per work class
(`drishti.bi.exec.background-share`, `standing-share`, 0.25 each), so background work (runs, dataset refresh, which now enters admission as
`BACKGROUND`) never holds the slots interactive questions need; background fragments take at most `fragments-share` of a connector's pool. A busy
interactive question is `DRS-9025`.

| Control | Setting (default) | Behaviour |
|---|---|---|
| global query slots | `drishti.bi.exec.slots` (2 × cores) | fragments beyond wait in a fair queue |
| per-user concurrency | `per-user` (4) | the fifth waits; a user cannot starve others |
| per-model share | `per-model` (50% of slots) | one hot model cannot take the server |
| weighted fair queue | interactive > subscriptions refresh > background (refresh, precompute, tests) | background yields |
| queue wait | `queue-ms` (2000) | then `DRS-9022` "busy, retry", with `Retry-After` |
| per-query memory | `max-memory-mb` (512), DuckDB `memory_limit` + spill | `DRS-9024` past the spill budget |
| per-query time | `max-millis` (30 s interactive, 300 s background) | cancel all fragments, `DRS-9023` |
| rows out | `max-rows` grouped 100k, extract 1M (streamed Arrow) | `DRS-9025` or truncated with `meta.truncated` |
| Python | workers per console (2 → cores/2 with fork-server), queue 5 s | `DRS-9074` |
| standing queries | max per node, per user; maintenance budget per model | `DRS-9181/9182`, degrade to tick |

**Back-pressure for live:** one coalescing mailbox per client stream (latest answer wins, as today's `FrameMailbox`);
a subscriber who does not read is skipped forward; a model whose maintenance overruns moves to tick mode; a connector
that floods is sampled by the live table (`rows`, `age` limits).

### 6.4 Large models and many consumers

* **Many datasets per model:** the planner touches only datasets the plan references (pruning by reachability); model
  flattening is lazy per alias; the designer pages the diagram (perspectives, search). A model of 200 datasets with 2,000
  measures must validate in < 1 s (A acceptance on a generated model).
* **Many consumers per model:** *Used by* is an index updated on publish; impact analysis reads the envelopes
  (5.3), so "who uses measure X" is a lookup, not a scan of every report.
* **Many viewers of one report:** identical signatures share L1 and standing-query state; a 500-viewer morning on
  one report with 5 distinct signatures is 5 evaluations per visual, not 500.

### 6.5 Horizontal scale

```
                ┌─────────── load balancer (sticky by session for SSE only) ───────────┐
                ▼                                                                       ▼
       console #1 (FastAPI, stateless)                                        console #2 …
       pyrunner fork-server, L4 local                                         (any console talks to any Java node)
                │  REST/Arrow + SSE                                                     │
                ▼                                                                       ▼
       Java #1 ── owns model shards {A, C}                          Java #2 ── owns model shards {B, D}
       L0, L1 local; L5 for its shards; live tables for its shards   …
                │                                                                       │
                └──────────────── shared state ─────────────────────────────────────────┘
                  · item store (catalogue, versions, drafts, audit) — the existing BI store/DB
                  · generation bus: dataset generations + change notices (load signals, live batches) — small, ordered
                  · columns layer and rollups on a shared filesystem / object store (Parquet, immutable per generation)
                  · subscription directory: model shard → owning node (consistent hashing; rebalanced on join/leave)
```

| Concern | Rule |
|---|---|
| Asked queries | **stateless**: any Java node answers any MQ; L1 is local (a miss on another node recomputes from shared layers) |
| Standing queries and live tables | **sticky by model shard**: one node owns a model's live tables and its standing queries; others forward subscribe calls; failover replays from the connector's resync and the latest layer |
| Generations | published on the bus; every node's L1 checks the vector before serving |
| Console | stateless apart from SSE sessions (sticky) and the Python result cache (local, disposable) |
| Python runner | per console, or the separate runner service (`bi.python.runner-url`) for heavy sites |

Built in B (scale wave); until then a site runs one Java node and one or more consoles. The design keeps every
wave A–C component honest about which state is shared (no static singletons holding generations or subscriptions).

### 6.6 Connectors

Per connector: a **fragment pool** (max concurrent source fragments, default 8), a circuit breaker fed by connector
health (open → `DRS-9026` fast, with the connector named), per-connector timeouts, and a **pushdown capability
descriptor** (SQL dialect, can aggregate, can filter on which types, can stream). Fragment SQL is generated from the
plan with bound parameters only; no text from a request ever reaches a source.

### 6.7 Cost controls

Before running, the planner **estimates** rows and bytes per fragment from layer metadata (`meta.json` row counts,
partition sizes, rollup sizes) and refuses or asks to confirm above `budget` (`DRS-9022` with the estimate and the
fix: "add a date filter" or "use rollup by_desk"). Workspaces may carry a daily **query budget** (CPU seconds) for
background work (refresh, precompute, subscriptions), visible on the Admin BI usage report.

### 6.8 SLAs and observability

| Target (reference server) | p95 |
|---|---|
| cached visual (L1 hit) | < 50 ms |
| visual on a rollup | < 150 ms |
| visual on the columns layer, 10 M rows | < 500 ms |
| report page of 8 visuals, cold | < 2 s |
| live change → screen | < 250 ms charts, < 100 ms grid |
| DSQL MQ-shaped vs the same MQ | equal latency ± 5 ms (it is the same plan) |
| Python transform call overhead (fork-server) | < 30 ms |
| 500 concurrent viewers, one model | no SLA breach (B load test, on a real server, never locally) |

Metrics (Micrometer → Prometheus; tags `model`, `tier`, `layer`, `kind`):
`drishti_bi_query_seconds`, `drishti_bi_query_rows`, `drishti_bi_cache_requests_total{result}`,
`drishti_bi_admission_wait_seconds`, `drishti_bi_refusals_total{code}`, `drishti_bi_fragment_seconds{tier,connector}`,
`drishti_bi_sq_active`, `drishti_bi_sq_lag_seconds`, `drishti_bi_sq_mode{incremental|tick}`,
`drishti_bi_python_seconds{item}`, `drishti_bi_layer_rebuild_seconds`. Admin → BI usage is a shipped report over the
audit and query logs: slowest visuals, most-asked fields (feeds aggregation suggestions), refusals by code, cache hit
rate per model.

---

## 7. Governance and lifecycle

| Mechanism | Design | Status |
|---|---|---|
| Items | dataset, model, report, board (→ report layout, R7), alert, subscription, notebook, python, theme, app | built except theme/app |
| Stable ids | every dataset, column, measure, hierarchy, relationship, perspective, visual; written by designers; consumers store ids beside names | W1 (datasets, measures); A complete |
| Versions | semver per item; consumers pin `@major`; the designer proposes the bump from the diff | W1 |
| Contract | `contract.exposes`, deprecations with `use:`; breaking-change detection (removed/retyped field, changed measure meaning, relationship removed, grain changed) needs a major | W1 detects ids; A full |
| Review | four-eyes, steward approval, reject with reason (kept) | built |
| Tests | data tests (unique, not-null, references-exist), measure tests, golden questions, DSQL tests, Python manifest tests; all gate publish; run in CI with `drishti.py bi item tests` | built/A |
| Endorsement | promoted, certified; revoked automatically on an incompatible change (kept) | built |
| Lineage | report/visual → model → measure → dataset → column → pack kind → connector; boards, alerts, notebooks included | A |
| Impact | from envelopes (5.3): exactly which consumers use a field/measure; a proposed change lists them and offers rebinding | A |
| Deployment | bundles (kept) + **environment parameter rules** (connector and workspace substitutions per target), dry run, rollback | C |
| YAML in git | `config/bi/**` files are first-class items (watched, validated); stored items export to files; two-way workspace ↔ branch sync | export built; sync C (decision 8) |
| Lifecycle | draft → published → deprecated (`replaced-by`) → retired (refused after a grace period, consumers listed) | W1 keys; A behaviour |

---

## 8. Authoring UX architecture

### 8.1 One product, four designers

```
 BI Studio (one shell: left rail = catalogue and workspace; top = item tabs; right = properties; bottom = Query/Problems)
 ├── Dataset wizard   pick a pack kind → subset/filter/grain/derive → preview (server, as the author) → save
 ├── Model designer   diagram (facts, dimensions), relationships (suggested from packs), measures, hierarchies,
 │                    perspectives, security (Preview as …), contract, tests, Used by
 ├── Report designer  fields pane (from describe, by perspective) → visuals → format → interactions → publish
 └── Board designer   = report designer with layout: board (R7): tiles, kiosk, live
 Shared components: FieldList · ExpressionEditor (DEL) · QueryPanel (MQ ⇄ DSQL ⇄ plan) · Preview · YamlTab ·
                    ProblemsPanel (code + fix) · History (saved states, diff) · Share/Publish
```

| Rule | Mechanism |
|---|---|
| One field list everywhere | built from `GET /models/{id}/describe` as the caller; display names, folders, formats, descriptions, data categories, default summarisation **inherited from the model**; a report never re-declares them |
| Perspectives | a report (or user) picks a perspective; the field list shows only its fields; MQ carries `perspective:` |
| Suggestions from packs | relationships from `references:`, measures from `aggregate:` defaults, hierarchies from pack graphs, formats from vocabulary units, the time mode from the connector |
| Validate as you type | the ExpressionEditor and QueryPanel call `/validate` (DEL, MQ, DSQL) debounced; problems show beside the control with the `fix` as a button |
| Preview is a server answer | always as the author (masks on), with the plan's tier and time under it |
| Never lose work | server-side draft autosave (every change, debounced 2 s) + local journal; stale-save detection by item etag (`409` with a merge view); leaving with unsaved changes asks. **Status (wave A QA):** built in the model designer (`PUT /items/{id}` with `baseEtag` 2 s after the last change, *Draft saved at …*; a `DRS-9007` stops the autosave and opens the merge view, theirs and yours per top-level part, a part only one side changed preselected; the browser journal stays as the backup); the focused field commits before every save in all designers and the wizard. The report and board designers keep Save with the stale-save choice (Reload, a copy, Overwrite) |
| Undo everywhere | a command stack per item tab (100 steps), including YAML tab edits |
| Accessibility and phone | every designer usable at 390 px (single-column mode: properties as a sheet), keyboard-only, 44 px targets, screen-reader names; the diagram has a list view equivalent |

### 8.2 YAML round trip

The designers edit a **document model**, not text: a YAML concrete syntax tree (comments, key order, anchors kept)
with typed accessors per kind. A designer edit is a minimal tree patch; the YAML tab shows and edits the same tree;
unknown keys and comments survive; stable ids are inserted by the server on save. Invalid YAML typed in the tab keeps
the designer read-only on that item until it parses (never discarded). The console renders, the **server validates**
(the same parser as publish), so the designer can never accept what publish would refuse.

### 8.3 Experience gate

Each release ends with an **experience QA wave** (power users: designers, workspaces, packs, connectors) judged against
section 14.6; its findings are fixed before the drill.

---

## 9. AI architecture

```
 question ─► policy (on? provider? who? mode) ─► grounding pack: describe(model, as user) + ai: metadata
           + perspective + golden examples (questions + MQ, no values) ─► provider ─► MQ or DSQL draft
           ─► /validate (as user) ─► on refusal: code + fix sent back once ─► run as user ─► answer (engine numbers)
           ─► optional wording call (only under the `aggregates` policy, secured aggregates only)
```

| Element | Design |
|---|---|
| Grounding | `describe` as the user (hidden absent, masked marked), `ai:` synonyms/units/examples/hints, perspective, model description, top golden questions as few-shot (question → MQ) |
| Output | AI writes **MQ or DSQL** (never SQL to sources); authoring outputs are **YAML drafts** (dataset, model change, measure, report, Python transform) that go through Check, tests and four-eyes review with provenance `ai:{provider, prompt id}` |
| Golden questions | per model: words → expected MQ and expected value; run as tests on every publish (query part from W1) and as an AI regression suite per provider (C) |
| MCP tools | `list_models`, `describe_model`, `query_model(mq)`, `query_model_sql(sql)`, `explain_query`, `explain_change(measure, from, to)`; all as the token's user; envelope rules apply to embed tokens |
| Policy | off by default; per site and workspace; provider allow-list; `labels-only` (default) or `aggregates`; rate limits per user; every call audited with the produced MQ/DSQL |
| Never | rows to a provider, connector names or paths, masked or hidden field names, AI-computed numbers |

---

## 10. APIs and integration

| Surface | Endpoints / protocol | Auth and scope | Wave |
|---|---|---|---|
| REST items | `/api/v1/bi/items`, `/datasets`, `/models`, `/reports`, `/bundle`, `/schemas/<kind>` | bearer token; workspace roles | built (+schemas A) |
| MQ | `POST /bi/mq`, `/models/{id}/query`, `/mq/validate`, `/models/{id}/describe`, `/mq/subscribe`, `/mq/stream` (SSE), `DELETE /mq/run/{id}` | model read or envelope | built (cancel B) |
| DSQL over HTTP | `POST /bi/sql { model, sql, format: json\|arrow\|csv }`, `/sql/validate`, `EXPLAIN` | model read | A |
| SQL wire endpoint | PostgreSQL wire protocol (read-only, one database per model, schemas = perspectives) for psql/ODBC/JDBC, BI tools, Python | per-user tokens as passwords; signature per session | C (decision 5) |
| Excel feed | OData-like JSON/CSV over `/bi/feeds/{model}/{query}` for Excel's Power Query web connector; later an Office.js add-in | token per user; refresh in Excel re-asks as that user | C; add-in 3.0 |
| Embedding | `<drishti-report>` web component; embed tokens scoped to a report **envelope** and an optional fixed filter; JS events | token | built; envelope A |
| MCP | the tools of section 9 | token's user | C |
| CLI | `drishti.py bi model check`, `bi item tests`, `bi mq`, `bi sql`, `bi migrate`; bundles move through *Environments* in the BI rail and the API ([BI_GUIDE.md section 32, its section 7](../guides/BI_GUIDE.md#7-environments-bundles)) | user token | built/extended |

**Versioning.** REST under `/api/v1` (additive changes only; a breaking change is `/v2`). MQ by `mq:` (1 → 2 is
additive: a v1 query is a valid v2 query); DSQL by `sql:` (1); item formats by `rachana:` (2). A retired form answers
`410` with the equivalent (as `POST /datasets/{id}/query` will after A).

---

## 11. Gap check against Power BI

From the 2.1 study's §2 and §4 (top gaps; the study was deleted, in git at tag v2.9.5; its item ids are [11.1](#111-the-21-studys-item-ids)), mapped to the component of this design that delivers each, and the wave.

| Power BI capability | Drishti component | Wave | Better than Power BI because |
|---|---|---|---|
| Semantic model, star, model view | model item + model designer | W1/A | packs pre-wire keys, meanings, relationships; fan-out refused |
| Shared semantic model, thin reports, Build permission | consumers reference one model; model read + envelope (5.3) | W1/A | envelope lets reports be shared without opening the model |
| Field properties, display folders, perspectives | model field properties, `describe`, perspectives | A | inherited by every consumer and by AI |
| Calculated columns | DEL `derive` (+ `related`), Python `column` | A | typed, with meanings; Python sandboxed |
| Calculated tables / dataflows | `extends` + grain/group, SQL view datasets, Python transforms | A | reviewed, tested, rooted in packs |
| Power Query | **not built**; Python transforms + step preview + snippets + profiling | after C (2.5) | tests and sandbox; no second language |
| Many-to-many, bidirectional | bridges and `direction: both` as `SemiJoin` | A | never join expansion; fan-out invariant |
| Inactive relationships, role-playing dates | `calc(…, via=…)` (DEL) | B | — |
| CALCULATE, ALL, ALLEXCEPT, % of total | DEL `calc`, `share_of`, `parent_of` (3.3.5) | B | security outside the editable context; no context transition |
| Calculation groups, field/what-if parameters | calc-groups, parameters, `param.*`, MQ `calc`/`params` | B | compile-time; part of cache keys |
| Time intelligence, fiscal calendars | DEL time functions + model `calendars` from packs | B | business-day and holiday aware |
| Semi-additive balances | `last/first/opening/closing`, additivity declarations | A (guard), B | declared additivity drives true totals |
| Import / DirectQuery / composite / hybrid | time modes + refresh layers + tier planner | B | live is native; one plan mixes live and snapshot |
| Aggregations (user and automatic) | rollups + optimiser matching + suggestions | A/B | security-aware matching |
| Incremental refresh | per-date layers, back-dated refresh | B | — |
| Streaming datasets / real-time | standing queries with incremental maintenance | B | the same report answers live and as-of |
| RLS, OLS | dataset rules, model only-tighter, masks, hidden-for, safeguard | W1/A | masks before arithmetic; propagation explicit; publish safeguard |
| DAX query view, Performance analyzer | QueryPanel (MQ ⇄ DSQL ⇄ plan), explain per visual | B | the plan shows tiers and security |
| XMLA endpoint, external tools | DSQL HTTP + PostgreSQL wire | A/C | one model per connection, per-user security |
| Analyze in Excel | Excel feed, then add-in | C/3.0 | per-user masked refresh |
| Copilot, Q&A | AI through MQ/DSQL, golden questions, MCP | C | numbers from the engine; masks in prompts; regression-tested |
| Format pane, themes, conditional formatting | report schema v2, theme items | C | typed schemas; YAML-native |
| Tooltip pages, small multiples, edit interactions, matrix depth | report schema v2, interactions | C | — |
| Deployment pipelines, Git integration | bundles + parameter rules, git sync | C | review and tests gate publish |
| Usage metrics, sensitivity labels, comments | audit-as-dataset report, labels on datasets, threads on visuals | C | — |
| Paginated reports | `layout: paginated` | 2.7 | — |
| Scale-out, capacity | horizontal design (6.5) | B | no capacity licence |

Where we remain behind by choice: no Power Query UI, no DAX, no custom-visual marketplace, no publish-to-web, no
VertiPaq-class in-memory engine (we rely on DuckDB, rollups and caches; B's load test decides whether that holds).

### 11.1 The 2.1 study's item ids

The code, the guides and the CHANGELOG cite items of the 2.1 parity study by id (for example *P3*, *R6*, *gap 7*). The study itself was deleted
(it is in git at tag v2.9.5); this is what each id names. The data-prep items are not this design's principles P1 to P9 of section 1.

**Data-prep items P1 to P10** (the Python path instead of Power Query; built ones are in [BI_GUIDE.md › Python in BI: measures, visuals and data prep written in Python, section 17](../guides/BI_GUIDE.md#17-data-prep-the-transform-editor-240)):

| Id | Item |
|---|---|
| P1 | Step history and preview in the Transform editor: the frame after each step, run on a sample as the author |
| P2 | A snippets library of tested, parameterised pandas transforms (append, unpivot, pivot, fill down, split, dedupe, as-of join) |
| P3 | A profiling view of an extract or a Transform's output (nulls, distinct values, minimum and maximum, top values, histogram, type guess) |
| P4 | AI-written Transforms: "describe the reshape" gives a draft over the inputs' schemas, checked and run on a sample |
| P5 | Connector-kind inputs: a Transform reads a pack kind through a connector, as the reader, before any dataset exists |
| P6 | Precompute on refresh for configured roles, and a warm pool of sandboxed workers |
| P7 | Transform parameters bound to the report's what-if or field parameters |
| P8 | An incremental Transform mode: run only for the new business date and append |
| P9 | Promote several Analyse cells into a Transform with the cells as steps |
| P10 | A declared output schema compared on every run, with a drift warning |

**Redesign items R1 to R8** (breaking changes, each with a converter for the samples):

| Id | Item |
|---|---|
| R1 | One table model for every dataset: every table has one `source:` (connector, model query, SQL, Python) |
| R2 | Measure language v2 with an explicit filter context |
| R3 | Stable ids for fields and measures, so renames are safe |
| R4 | Relationships as filter propagation (direction, cardinality, active), implemented as semi-joins |
| R5 | Calendars as a model object (pack calendar, fiscal year, retail 4-4-5) |
| R6 | Report schema v2: a typed `format:` per visual family, theme items, interactions and parameters |
| R7 | Boards as a report layout (`layout: board`) |
| R8 | Python measures in queries, as a step after aggregation |

**Gaps 1 to 15** (the study's top gaps, ranked by value and effort): 1 what-if and numeric parameters; 2 filter-context functions (share of
total, ignore, with); 3 calculation groups; 4 semi-additive and balance measures; 5 inactive relationships and role-playing dates;
6 conditional formatting depth; 7 matrix depth (expand and collapse at every level, subtotals, stepped layout); 8 format pane depth and themes
as items; 9 field parameters; 10 Excel live access; 11 the Python data-prep experience (P1 to P4); 12 fiscal and custom calendars from the
pack calendar; 13 tooltip pages, small multiples, edit interactions, sync-slicer groups; 14 comments on reports and usage metrics;
15 a semantic SQL endpoint.

---

## 12. W1 as built against this design, and the revised wave plan

### 12.1 What W1 built, kept, adjusted or replaced

Paths are under `drishti-server/src/main/java/com/ash/drishti/server/bi/` (server) and `drishti-console/` (console).

| As built (W1 and 2.1) | Verdict | Change, and wave |
|---|---|---|
| `layer/` DatasetDef, ModelDef and their parsers, ModelChecker (safeguard 9130), Contracts, Semver, StableIds, PackDatasets, ResolvedDataset, ImplicitModels | **keep** | extend: derivation keys, field properties, perspectives, `hidden-for`, root tracing, full breaking-change detection (A) |
| `mq/` MqParser, MqValidator, MqController, ModelCatalog, ConsumerQueries, ModelConsumers, ModelRoles | **keep** | MQ stays the contract; v2 keys (B) |
| `mq/MqCompiler` → `layer/Flattener` → v1 `dataset.Dataset` → `engine/SqlCompiler` | **adjust** | MqCompiler emits the **logical plan** (`bi/plan`, A); the Flattener and v1 `Dataset` disappear; SqlCompiler and SqlEmitter remain as the SQL generator for `Aggregate`/`Meet` fragments |
| `dataset/ExprParser`, `Node` (Rachana-EL syntax) | **replace** | `bi/expr` DEL v2 (Python-like) parser, catalogue, type and scope checker (A); the old parser lives on only inside the converter |
| `engine/QueryService` result cache (key: dataset key, text hash, generations, request, matched rules **plus user attributes**, masks, 15 min TTL) | **adjust** | key = plan hash + model version + **security signature** + resolved time + generation vector; no TTL for as-of answers (A) |
| `engine/QueryService` admission (fair semaphore 8, per user 3, 2 s wait), DuckDB 768 MB / 2 threads per engine | **keep** | add work classes, per-model share, memory per query, cost estimates (B) |
| `engine/QueryService.route` rollup routing (one table, no measure filters, by ⊇ fields + rule columns) | **keep** | becomes the optimiser's rollup-matching rule; multi-fact plans match per `Aggregate` (A) |
| `engine/Dialect` (DuckDB, PostgreSQL; unknown → DuckDB) | **adjust** | connector capability descriptors; an unknown dialect means *no pushdown*, never a guessed dialect (B) |
| `engine/LiveTables`, `LiveAggregate`, `LiveHistory` | **keep** | the base of incremental maintenance (B) |
| `mq/StandingQueries` (polling loop, full recompute, in-memory map) | **replace** | push on a generation bus, grouped by (plan, signature), incremental rules, shard ownership (B) |
| `dataset/Composition`, `FrameService` composed datasets, `CompatReader`, v1 dataset files | **replace** | migrated to models and Python/SQL-view datasets; deleted (A) |
| `transform/ProcessTransformRunner` (Java starts the console's `transform_cli.py`) | **adjust** | Py nodes are called on the console backend over HTTP, one path for every Python interface (B) |
| `engine/QueryService.stats()` AtomicLong counters | **replace** | Micrometer metrics of 6.8 (B) |
| `poc/` (phase 0, still compiled) | **delete** | (A) |
| console `core/pyrunner.py`, `pyrunner_worker.py` (one process per call, audit hook, rlimits, netns) | **keep** | fork-server, `column` interface, `compute_groups` (B) |
| console Analyse SQL (SQLite on the console, DuckDB-Wasm in the browser), `web/static/js/bi-explore-duck.js`, Pyodide (`calc-worker.js`, `static/calc/bi_driver.py`) for BI | **replace** | DSQL on the server (A); browser paths deleted (B) |
| console `routes/model_routes.py`, `core/model_layer.py`, `bi-model-designer.js`, `bi-wizard*.js` | **keep** | grow into the designers of section 8 (A, C) |

### 12.2 The three waves

Each wave is one release, built by **two parallel builders** (a **server lane** and a **console lane**), in the order
listed inside each lane, with targeted tests between tasks (10K-trade lake at most, one heavy process at a time). Each
wave ends with: **one experience QA round** by power users (findings fixed) → **docs and tutorials pass** → **one
drill** → tag. The server lane publishes request/response types on day one of each task so the console lane can build
against them.

**Release order (product owner, 2026-10-20):** 2.6.6 (QA clean) → **2.6.7 BI runs** (wave R, [section 17](#17-bi-runs-every-bi-query-as-a-bounded-visible-job-built-in-267), with gossip off the busy path, HTTP/2 between nodes, chart any table,
[RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#chart-any-table-the-design), and mixed Delta and Iceberg storage, [DEMO_DATA.md](../connectors/DEMO_DATA.md#mixed-delta-and-iceberg-storage)) →
2.7.0 write-back (item 0 the one Drishti database, [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#6-the-one-drishti-database-design-not-built)) → 2.8.0 AI and Sajha → the real-life QA → 2.9.0 →
3.0.0. **Since then:** 2.6.6 and 2.6.7 are released (2026-10-21); 2.7.0 is Sutra Next ([RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#sutra-next-fragments-events-children-sources-layout-and-forms), released
2026-10-24; the UI Board stays parked on branch `ui-board-preview`), so wave W (write-back) is **2.8.0** and wave I (AI) **2.9.0**; the
real-life QA and wave E follow. The current order is [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md#roadmap)'s roadmap.

#### Wave A — Foundation and languages → 2.2.0

**Server lane (paste as the brief):**

1. **DEL v2.** New package `bi/expr/`: `DelParser` (the Python subset of 3.3.2, hand-written recursive descent, bounds
   2,000/4,000 chars, depth 64), `DelAst`, `Scopes` (row, rule, aggregate; context reserved), `Types`, `FunctionCatalog`
   loaded from `src/main/resources/bi/expr/functions.yaml` (name, signature, scopes, description, examples, edge cases,
   since). Semantics of 3.3.3 in `engine/SqlEmitter`. Endpoints `POST /api/v1/bi/expr/validate`, `GET /bi/expr/functions`.
   Converter `drishti.py bi migrate --expressions` (W1 `ExprParser` AST → v2 printer), asserting identical SQL for every
   shipped item. Old syntax warns `DRS-9100`; refused at the end of A.
2. **Logical plan.** New package `bi/plan/`: node records of 3.6, `Canonicalizer` (plan hash), `SecurityRewriter`
   (`SecureScan`, `SemiJoin` propagation), `Guards`, `Optimiser` (pushdown, pruning, grain split, rollup matching, cache
   lookup, top-N), `Lowering` to `SqlCompiler` fragments, `SecuritySignature`, `ResultCache` (6.1 L1 key). `MqCompiler`
   emits plans; Flattener and v1 `Dataset` removed at the end of the lane. Property tests: no rule drops a security
   predicate; ANDing never adds a row; rollup vs columns equality.
3. **Drishti SQL.** New package `bi/sql/`: parser (decision 1), `SqlBinder` (names via `describe` as the caller),
   MQ-shaped → MQ, composite → `Rel` nodes over secured leaves (DuckDB on the Java tier), refusals `DRS-9170`–`9179`;
   `POST /bi/sql`, `/bi/sql/validate`, `EXPLAIN`; `source: { sql, model }` datasets; `tests: [{ sql, expect }]`; CLI
   `drishti.py bi sql`.
4. **Dataset derivation and roots.** `layer/DatasetDef(+Parser)`: `columns` subset, `where`, `grain: { one-row-per, keep }`,
   `derive` (expr, python via the existing Transform runner until B), `group`; plan nodes `Derive`, `Dedup`, `Regroup`,
   `related()`; root tracing (Check refuses an untraceable root, `DRS-9110`); lineage report → pack kind → connector.
5. **Model semantics and access.** Field properties (display name, description, format, folder, hidden, sort-by, data
   category, default summarisation) in `ModelDef` and `describe`; perspectives; `hidden-for` (OLS); the consumer
   **envelope** (5.3) computed on publish and enforced in `ModelResolver`; full breaking-change detection; impact and
   *Used by* from envelopes.
6. **Migration and removal.** Convert every `config/bi/**` sample to datasets and models; delete `CompatReader`, v1
   dataset files, `dataset/Composition`, composed-dataset code in `FrameService`, `poc/`; `POST /datasets/{id}/query`
   answers `410` with the MQ equivalent.
   *As built:* the stored items convert themselves at start (`drishti.bi.migrate.on-start`: old-syntax expressions to DEL v2, then `rachana: 1`
   datasets to datasets and models; audited, the text before kept, what cannot be converted listed and never changed); the converter is
   `drishti.py bi migrate --models` ([section 15 8.3.1](#831-as-built-22-wave-a-task-6)), the samples are converted and proven
   equivalent per reader (`ConvertedSamplesTest`); the two live reports stay on their 2.1 datasets until W3 (a report on a model follows live data
   through standing queries). The deletions are one commit applied after the console lane (they take the 2.1 dataset query route away).

**Console lane (paste as the brief):**

1. **ExpressionEditor** (`web/static/js/bi-expr-editor.js`, new; used by `bi-model-designer.js`, `bi-wizard-steps.js`,
   report local measures in `bi-design-panes.js`): completion of fields, `measure('…')`, functions with signature and
   example from `/bi/expr/functions`, keyword arguments; inline problems with the fix as a button; *Try it* preview.
2. **Dataset wizard v2** (`bi-wizard.js`, `bi-wizard-steps.js`, `routes/model_routes.py`, `core/model_layer.py`): steps
   pick root → columns → filter → grain/de-dup → derive (expression or Python item) → group → preview (server) → save;
   an *SQL view* path with the DSQL editor.
3. **Model designer completion** (`bi-model-designer.js`): field properties pane, perspectives, `hidden-for`, contract
   diff with the semver bump proposed, *Used by* and impact, *Preview as* any role.
4. **Query panel and Analyse on DSQL** (`bi-design-panes.js`, `routes/explore_routes.py`, `core/explore.py`): MQ ⇄ DSQL
   view under each visual; Analyse SQL cells sent to `/bi/sql` (server is the default engine; the browser engine stays
   selectable only until B).
5. **Fields everywhere from `describe`** (`bi-design.js`, `bi-report.js`): one field list with inherited properties and
   perspective filter; YAML round trip on a concrete syntax tree (comments and order kept) in `core/model_layer.py`.
6. **Docs** (with the server lane's input): `docs/guides/BI_LANGUAGE_REFERENCE.md` (3.3.8: grammar, every function, error codes,
   40+ recipes, Python/DAX differences), `BI_LANGUAGE_REFERENCE.md` (DSQL reference), BI_GUIDE.md v2, MODELS.md, API guide; tutorials
   10–18 migrated (UI, CLI, YAML).

**Accepted when:** (1) every shipped sample, test and tutorial 10–18 runs on datasets and models in the UI alone; the
v1 format and old expression syntax are refused; the equivalence harness is green apart from its allow-list; (2) the
same question as a visual, an MQ and a DSQL statement gives one plan hash, one answer and one cache entry per security
signature; (3) a dataset with subset, filter, explicit grain, an expression column, a Python column and grouping, and an
SQL view dataset, are made in the wizard without typing YAML; (4) a hidden column is absent from `describe`, DSQL and
AI; a report-only reader is refused outside the envelope; (5) the plan's security property tests pass; (6) a test
fails if BI_LANGUAGE_REFERENCE.md and `functions.yaml` disagree, and all 40+ recipes run as tests on the demo lake; (7) the QA
round's findings are fixed before the drill.

#### Wave B — Calculation, live and scale → 2.3.0

**Server lane:**

1. **Filter context.** `Context` node and its lowering; `calc`, `share_of`, `parent_of`, `avg_by`; calculation groups;
   parameters (what-if and field) with `param.*`; MQ v2 (`params`, `calc`, `perspective`).
2. **Time and balances.** `last`, `first`, `opening`, `closing`, `avg_over`; model `calendars:` from the pack calendar
   with fiscal years; `calc(…, via=)`; `as_of`, `shift_date`; `convert(…, at=)`; the semi-additive refusal.
3. **Time modes and standing queries.** Generation bus (push, replaces polling in `mq/StandingQueries`);
   `StandingQueryRegistry` grouped by (plan, signature); `Maintainer` with the rules of 4.6; maintenance budget,
   back-pressure, overrun handling; mixed live/snapshot binding; back-dated refresh.
4. **Tier planner and Python nodes.** `TierPlanner`; connector capability descriptors (replacing the `Dialect`
   fallback); fragment pools and circuit breakers; `Py*` nodes called on the console backend (replacing
   `ProcessTransformRunner`); Python measures in any query (R8); `explain` annotated with tiers and timings.
5. **Scale.** Work classes and per-model shares, per-query memory, cost estimates and budgets, Micrometer metrics
   (6.8), model-shard ownership and the subscription directory for several Java nodes, no static shared state; a
   load test of 500 viewers on a real server (never locally).
6. **Apache Cassandra connector** (added 2026-10-20, [CASSANDRA_CONNECTOR.md](../connectors/CASSANDRA_CONNECTOR.md)).
7. **drishti-iceberg**, Drishti's own Iceberg engine with no Hadoop (added 2026-10-20, [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#3-drishti-iceberg-drishtis-own-apache-iceberg-engine), steps I1–I5).

**Console lane:**

1. **No browser compute.** Delete `bi-explore-duck.js`, the BI uses of `calc-worker.js` and `static/calc/bi_driver.py`,
   DuckDB-Wasm and Pyodide from report and Analyse pages; manifests' `target` is server only; Analyse Python cells
   run on the backend; BI pages lose `'wasm-unsafe-eval'`.
2. **Runner.** Fork-server in `core/pyrunner.py` / `pyrunner_worker.py`; the Py-node endpoint the Java planner calls;
   the L4 result cache; the `column` interface and `compute_groups`.
3. **Calculation UI.** `calc`, `share_of`, balances in the ExpressionEditor (catalogue-driven); editors for calculation
   groups, parameters (with parameter slicers) and calendars; `via` in the measure editor.
4. **Live everywhere.** Reports, boards and alerts use pushed `/mq/stream`; state chips (live, throttled, stale);
   a time picker (now, as of, window) in every consumer.
5. **Explain and usage.** The explain panel under each visual; the shipped *Admin BI usage* report.
6. **Docs:** BI_LANGUAGE_REFERENCE.md filter-context and balance chapters and recipes; a live and standing-queries guide; an
   operations guide for scale settings and metrics; tutorials updated.

**Accepted when:** (1) % of total, ignore-a-slicer, a YTD calculation group over ten measures and a what-if shock equal
an independent computation; closing balance per month is right across a holiday; (2) a board, a report and an alert
over one model tick live, answer as of a past date, and equal a full recompute after random update streams (property
test); (3) no BI page computes: no Wasm or Pyodide is loaded (test), CSP without `'wasm-unsafe-eval'`; (4) two Java
nodes and two consoles serve one model, and standing queries survive the loss of a node; (5) the 500-viewer load test
meets 6.8 on a real server; (6) explain shows tiers, layers and times; (7) QA findings fixed before the drill.

#### Wave C — Authoring and analysis → 2.4.0

Agreed with the product owner on 2026-10-17 (this split replaces the earlier C/D plan: AI, integration and governance moved to
D and E; Python data prep and the browser Python console moved into C).

**Server lane:**

1. **Report schema v2 (R6) and boards as layouts (R7):** typed `format` per visual family (JSON Schema), `theme` items,
   `interactions` matrix, sync groups, tooltip pages, small multiples, matrix depth, conditional formatting rules;
   `layout: board` replaces the `board` kind (converter).
   *Built (2.4.0):* the format schemas in YAML (`bi/report/format/`, `FormatSchemas`, `GET /bi/reports/format-schemas`; a bad 2.4 value is `DRS-9032`, a
   bad 2.3 value a warning), `format.rules` (rule on another measure, colour scales, icons, colour per value), theme items (`ThemeParser`, `ThemeService`,
   `DRS-9036`, shipped Corporate and Print), `pages[].interactions` (filter, highlight, none; highlight for line, area and combo), `format.syncGroup`,
   tooltip pages, the `split` well, matrix totals at every level; `layout: board` with `tile` visuals (`ReportBoards`, `BoardConverter`), stored boards
   converted at start (step `boards`, `BoardUpgrade`), board files read as reports, `POST /bi/boards/convert`, `POST /bi/migrate/boards`,
   `drishti.py bi migrate --boards` ([BI_GUIDE.md](../guides/BI_GUIDE.md#20-report-schema-v2-in-one-report-240), [BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 23](../guides/BI_GUIDE.md#23-boards-report-pages-of-live-tiles)).
2. **Python data prep** (BI_PARITY_PLAN P1–P10): Transforms reading connectors directly, warm workers, a tested snippets
   library (as-of joins for prices and FX, unpivot, type fixes), profiling of a Transform's input and output, a step-by-step
   run (each step's rows and profile) for the editor. **Built (2.4.0):** a pack kind through a connector as an input, as the
   reader (`POST /api/v1/bi/python/input`); the fork-server started with the console; 21 snippets each with an example run in
   pandas and in the sandbox; the profile of inputs, every step and the output; the step-by-step run. Not built: P4 (wave I, now 2.9.0),
   precompute on refresh (rest of P6), P7 to P10 ([BI_GUIDE.md › Python in BI: measures, visuals and data prep written in Python, section 17](../guides/BI_GUIDE.md#17-data-prep-the-transform-editor-240)).
3. **The browser Python console's server side:** masked extracts of a model for the browser, promotion of a result to run
   on the console backend. **Built (2.4.0):** `POST /pyconsole/extract` (the reader's masked rows, `bi.pyconsole.max_rows`) and
   `/pyconsole/promote` (the code run in the console's cell sandbox over the extract rebuilt for the reader, kept as a notebook draft).

**Console lane:**

1. **BI Studio as one product:** the shell of 8.1; format pane generated from the schemas; themes; conditional formatting;
   interactions editor; tooltip pages; small multiples; the matrix.
   *Built (2.4.0):* the Studio shell around the report, model and theme designers (`bi-studio.js`: the Explorer with New, the open items, the dock with
   Problems and Query), the generated Format pane (`bi-format-pane.js`) and the rules editor (`bi-format-rules.js`), the theme designer
   (`bi-theme-designer.js`), Edit interactions in Format and on the canvas, tooltip pages (`bi-tooltips.js`), small multiples (`bi-multiples.js`), the matrix
   at every level (`bi-matrix.js`), board pages in the viewer and the designer (`bi-report-board.js`, `bi-design-v2.js`); the acceptance (1) is
   `drishti-console/tests/test_bi_studio_browser.py` ([BI_GUIDE.md section 26](../guides/BI_GUIDE.md#26-bi-studio-authoring-as-one-product-wave-c-240)).
   Not in it: a report visual bound to a field parameter (gap 9: field parameters are in the model and its parameter slicers since 2.3.0, but a report's
   well cannot name `param.<name>` yet; moved to wave D, 2.6.0, below).
2. **Transform editor:** step by step with a preview and profile at each step, snippets picker, profiling panel, no YAML.
   **Built (2.4.0):** `/bi/transform` (inputs, steps from the snippets or by hand, a preview and a profile after each step, the
   YAML written and read back, two drafts saved); Analyse's Profile tab shares the panel.
3. **The browser Python console:** personal, just-in-time analysis on a masked extract of a model (Pyodide); promoted
   results run on the console backend. **Built (2.4.0):** `/pyconsole`, outside `/bi` (not a BI page: P3 holds), Calc's worker,
   Promote compares the browser's and the backend's answers ([USER_GUIDE.md › Analyse: slice, SQL and Python, section 16](../guides/USER_GUIDE.md#16-the-browser-python-console)).
4. **Docs and tutorials** for all of the above (UI, CLI and YAML).

**Accepted when:** (1) a Power BI author does the parity task list (BI_PARITY_PLAN §4 items 6–9, 13) without YAML; (2) a
Transform reading a connector is built step by step with a preview at each step and no YAML typing; (3) the browser
console analyses a masked extract and a result is promoted; (4) the docs pass is complete; (5) QA findings fixed before
the drill.

#### Wave N — Drishti*net → 2.5.0

Decided by the product owner on 2026-10-18: Drishti*net is built straight after 2.4.0, as a release of its own, then
fully tested. On 2026-10-19 the rest was split: sharing and integration (with field parameters) is wave D, 2.6.0; AI is
wave I, 2.7.0; the real-life QA follows 2.7.0 and wave E is 2.8.0. Re-ordered again the same day: write-back is wave W,
2.7.0 ([WRITE_BACK.md](../guides/WRITE_BACK.md#16-the-design-and-its-reasons)); AI becomes 2.8.0, the real-life QA follows it, and wave E is 2.9.0. On 2026-10-20 send and push and
integration left wave D for after 3.0.0, the Sajha MCP connector moved to 2.8.0, and the Cassandra connector and drishti-iceberg joined wave D.

**Server lane:** N1–N7 of [CLUSTER_DEPLOYMENT.md §16](../admin/CLUSTER_DEPLOYMENT.md#16-build-plan): membership by gossip over HTTP and
`peers.json`; capability digests; security (cluster key or mutual TLS, key rollover, on-behalf-of tokens, masks on both
nodes, nodes per role); view and data forwarding with the newest pack served and peer-only packs; scatter-gather search,
type-ahead and grouped pages; live relay and BI routing; user state across nodes and console failover. Configured like
connectors, in `config/net/` (section 14 there).

**Console lane:** Admin → Net (members, capability map, routing with *Explain*, access, security, files;
[CLUSTER_DEPLOYMENT.md §13](../admin/CLUSTER_DEPLOYMENT.md#13-admin-management-and-observability)); `backend.urls` failover; docs and a tutorial
(UI, CLI and YAML).

**Full testing:** in-process clusters with a fake clock (joins, leaves, crashes, a split and its healing), every routing
path, masks twice, scatter-gather against a single-node answer, browser tests of Admin → Net, and a soak on real processes:
three servers on scratch ports (shared and separate lakes, overlapping packs), one killed and restarted under
`tools/bench/users.py` load.

**Carried from 2.4.0 into the end of 2.5.0** (product owner, 2026-10-19, to release 2.4.0 sooner): a full rehearsal of every
browser suite (the live and embed suites and a whole-console run over the console's channel relay), the pictures of
tutorial 14 (25–29, on a demo lake rebuilt with `NOTIONAL`) and of tutorial 16 (11, the React sample after `fetch-vendor.sh`),
and the two `test_tree_rows.py` browser tests that failed once in a full console run (they pass alone).

**Accepted when:** (1) three servers started from one seed act as one to a console connected to any of them; (2) a
stopped server makes answers partial and named, never wrong, and a restarted one rejoins from its file; (3) a masked field
stays masked whichever node holds the data, and a role never reaches a node outside its list; (4) the soak runs with 0
wrong answers; (5) QA findings fixed before the drill.

#### Wave D — Statements, field parameters and connectors → 2.6.0

**First, before any feature: no known bug** (product owner, 2026-10-19). Done in 2.6.0 (each passing 5 runs in a row under load, or
fixed at a product cause): `test_bi_gov_browser`, `test_pack_new_browser`, `test_bi_reports_browser`, `test_bi_wizard_and_controls_browser`,
`test_form_browser`, `test_bi_wave_a_browser`, `test_bi_live_browser`, `test_tree_rows.py`, `test_bi_python_browser`,
`test_bi_reports_cross_browser`, `test_controls_browser`, `test_embed_report_browser`; the Drishti*net gap that two nodes disabling the
last administrators at the same moment could leave none enabled (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, section 11); members advertise only real addresses.
Moved to 2.6.5 (ends with a full drill): `test_showcase_clean_browser[map]` (the workbench of the map example now and then never loads:
`window.drishtiWorkbench` unset after 120 s, a script error at start suspected) and `test_embed_elements_browser`
(`test_pivot_regroups_without_saving_inside_the_element[firefox|webkit]`: the live netting set redraws its panels on every tick, so the
Pivot tab is replaced before a pointer click settles; an in-one-step click was written but not yet proven).

**Server lane:**

1. **Paginated reports and statements:** page-exact layouts (headers, footers, page breaks, repeated table headers),
   parameters, per-recipient bursting through subscriptions, PDF and Excel output, masked per reader.
   *Built (2.6.0):* `layout: statement` pages and the report's `statement:` paper (`StatementSpec`, `DRS-9038`), the layout (`StatementLayout`: rows of
   bands, automatic and explicit page breaks, repeated column titles, keep-together bands and `groupBy` groups, headers and footers with tokens, a page
   limit), PDF written by the server (`StatementPdf`, no library) and Excel with the paper's print setup, the period and the model's parameters, bursting
   through subscriptions (one statement per recipient, made as them), `POST /bi/reports/statement/preview` and `/file`, `drishti.py bi statement`
   ([BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 24](../guides/BI_GUIDE.md#24-statements-paginated-reports-page-exact-one-per-reader)); the acceptance (1) is `StatementApiTest`.
2. ~~**Send and push**~~: **out of 2.6.0, after the 3.0.0 release** (product owner, 2026-10-20): Send to Outlook and Teams,
   SharePoint push from Forms, connector dump (TEAMS_EMAIL_SHAREPOINT.md) stay designed, not scheduled.
3. ~~**Integration**~~: **out of 2.6.0, after the 3.0.0 release** (product owner, 2026-10-20): PostgreSQL-wire read-only
   endpoint (decision 5), Excel feed, embed tokens bound to envelopes, SDK events.
4. ~~**Sajha MCP connector**~~: **moved to wave I, 2.8.0 (now 2.9.0)** (product owner, 2026-10-20: it belongs to the release that does the AI work).
5. **Field parameters in report wells** (wave C gap 9, decided 2026-10-19): a report visual's well names `param.<name>` and
   the reader's parameter slicer switches the field or measure it draws; checked by the report schema.
   *Built (2.6.0):* `FieldParams` (`DRS-9037`), the report state's `params` (every model query of a report takes them as MQ v2 `params`), a field
   parameter of measures in MQ `select.measures` and `sort`; `FieldParamsApiTest` ([BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 21](../guides/BI_GUIDE.md#21-field-parameters-in-a-well-260)).
6. **Apache Cassandra connector** (added 2026-10-20, [CASSANDRA_CONNECTOR.md](../connectors/CASSANDRA_CONNECTOR.md)).
   *Built (2.6.0):* plugin `cassandra` (Cassandra 4 and 5, ScyllaDB, Astra), its loader (a day appears whole) and every read path, equal to the
   Delta connector over the same data ([CASSANDRA_CONNECTOR.md](../connectors/CASSANDRA_CONNECTOR.md)).
7. **drishti-iceberg**, Drishti's own Iceberg engine with no Hadoop (added 2026-10-20, [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#3-drishti-iceberg-drishtis-own-apache-iceberg-engine), steps I1–I5),
   *Built (2.6.0)*: `drishti-lakeio`, `drishti-iceberg` and the `native` engine of the Iceberg plugin (DATA_ARCHITECTURE.md).

**Console lane:**

1. **Statement designer** for paginated reports, previewed page by page.
   *Built (2.6.0):* Format → Layout → statement, the designer's **Paper** tab (`bi-statement.js`: paper, header and footer, period, and the server's
   pages drawn one at a time), each band's Format → Statement (from the format schemas); `test_bi_wave_d_browser.py`.
2. ~~**Feeds and embedding UI**~~: after the 3.0.0 release, with integration and send and push.
3. **Field parameters in the Studio's wells**, with a browser test.
   *Built (2.6.0):* Build offers the model's field parameters per well kind, the model designer's field parameters may list measures, the viewer draws
   the server's `wells`; the acceptance (3) is `test_bi_wave_d_browser.py` (DESK to PORTFOLIO, MTM to NOTIONAL).
4. **Docs and tutorials** (UI, CLI and YAML; BI_GUIDE.md and BI_GUIDE for field parameters).

**Accepted when:** (1) a monthly statement bursts to ten recipients, each masked, page-exact in PDF; (2) ~~psql and Excel
answers equal the MQ answers per user, masked~~ (with integration, after 3.0.0); (3) a report visual switches its field through a
field-parameter slicer; (4) the Cassandra connector answers as the Delta connector over the same data; (5) QA findings fixed before the drill.

#### Wave R — BI runs, the transport between nodes and chart any table → 2.6.7

Released in 2.6.7 (2026-10-21); the server lane of chart any table (saved charts, the `chart` panel, Open in BI) is not built yet
([RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#chart-any-table-the-design)). The text below is the plan as decided.

Decided by the product owner on 2026-10-20 ([section 17](#17-bi-runs-every-bi-query-as-a-bounded-visible-job-built-in-267)): super scale is the architecture at every data size, and
every BI query goes through one bounded asynchronous pipeline (submit → plan → admission → run → store → notify). An answer within
about 2 s is drawn inline as today; a longer one becomes a visible job in the **Runs** tab of the BI Workspace, with a count on the
bell. The planner picks one of three tiers from metadata only (interactive; known heavy, from products and rollups; ad-hoc heavy, a
background run), and dashboards' interactions never become jobs. It also fixes QA3A-01's class of problem: unbounded work in
the request path.

**Server lane:** R1–R8 of [section 17 §18.1](#181-server-lane-a): the contract and stubs; class caps in `Admission`
with refresh as background work; the metadata-only planner (`SourcePlugin.rowEstimate`, the estimate before any layer build);
`RunService` with the inline wait and `Prefer: respond-async`; the Parquet result store keyed by fingerprint, data version and
security signature; sharing (same, re-masked or recomputed per recipient) and promotion; restart and the cluster; notices and
metrics.

**Console lane:** R1c–R6c of [section 17 §18.2](#182-console-lane-b): the Runs tab, the run card and confirm dialog,
the bell, `drishti.py bi runs`, Admin → BI → Runs, help cards and the browser test.

**Also in 2.6.7** (product owner, 2026-10-20):

* **Gossip off the busy path** ([CLUSTER_DEPLOYMENT.md, as built, 2.6.7](../admin/CLUSTER_DEPLOYMENT.md#the-transport-between-nodes-as-built-267)): on the
  sender, gossip and probes get their own small HTTP client pool and a platform thread (today they share `PeerHttp`'s clients with data
  forwarding, and the round runs on a virtual thread); on the receiver, `/api/v1/net/gossip` and the probes are handled on a reserved
  path that heavy BI work cannot crowd out. Accepted when a heavy BI burst on one node gives **no false *suspect* anywhere**.
* **HTTP/2 for peer data forwarding** (same section): `PeerHttp` is HTTP/1.1 today; forwarded views, history, searches and the live relay
  move to HTTP/2 (gossip stays on its own HTTP/1.1 client). Tested across views, history, search and live relay, with a soak comparison
  against 2.6.6.
* **Chart any table** ([RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#chart-any-table-the-design)): a Chart icon and K on every table (Sutra panels, search results and pick
  lists, history, BI tables), one shared `table-chart.js` over the pivot engine and ECharts; chart all N through the panel records, the
  search pivot or a BI run; save, export, Promote to Sutra as a new `chart` panel kind, Open in BI. Build steps C1–C10, one console and
  one server builder.
* **Mixed Delta and Iceberg storage** ([DEMO_DATA.md](../connectors/DEMO_DATA.md#mixed-delta-and-iceberg-storage)): each source system's trades in Delta or
  Iceberg, chosen per system by `build_demo_lakes.py --store <system>=delta|iceberg`, proven by an equality test against all-Delta.

**Accepted when:** (1) a question answered within the inline wait looks exactly as in 2.6.6; (2) a burst of heavy runs leaves
interactive p95 within 1.5× its baseline with no `DRS-9025` (BI_RUNS 19.5); (3) a shared run never shows a recipient a value
their masks hide; (4) a restart loses no run silently; (5) a heavy BI burst on one node gives no false *suspect* anywhere; (6) the
HTTP/2 soak is no worse than HTTP/1.1 in errors and p95; (7) every table type charts in all ten themes and no masked value reaches a
chart or its export (TABLE_CHARTS 19); (8) QA findings fixed before the drill.

#### Wave W — Write-back → 2.8.0

Editing and saving fields on a Sutra page ([WRITE_BACK.md](../guides/WRITE_BACK.md#16-the-design-and-its-reasons), phases W1–W5): `edit:` in the Sutra, change requests
applied by a transaction handler over a fixed number of ordered lanes (one change at a time per record, lanes in parallel), version
checks against lost updates, four eyes, overlays and Drishti-owned kinds first (approved overlays flow into the lake), one source
amendment as a pilot, the change log kept 7 years (configurable), lanes owned on the Drishti*net ring.

0. **The one Drishti database** (item 0, first; [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#6-the-one-drishti-database-design-not-built), decided 2026-10-20): identity and everything relational
   Drishti owns in one database, SQLite by default and PostgreSQL long term; no migrations (two complete schema files, a parity test,
   a version stamp, `drishti.py db copy`); configured in `config/db/database.yaml` (planned, not built).
- **The edit log on it:** `drishti_edit_lane` (ownership by conditional UPDATE) and `drishti_edit_log` (acknowledged after commit,
  ordered by seq, every apply fenced by epoch); SQLite on one node, PostgreSQL or S3 conditional writes across a cluster, or one edit
  primary; the lake never written per edit; source commands through the outbox and connector `writes:`; off by default
  ([WRITE_BACK.md](../guides/WRITE_BACK.md#16-the-design-and-its-reasons) sections 5, 7, 11, 12).

**Accepted when:** two users editing one field never silently overwrite each other; 100,000 concurrent changes over 1,000,000
entities apply in per-record order; an approved overlay appears in the next lake build; the per-record guarantee holds with a node
killed mid-run and with a network split (only the current epoch writes); a 2.6.5 data folder starts unchanged and read-only; QA
findings fixed before the drill.

#### Wave I — AI → 2.9.0

**Server lane:**

1. **AI grounding:** `ai:` metadata in `describe`; golden questions run per provider; AI emits MQ/DSQL only; measures
   from plain words, reports from intent and AI-drafted Transforms (P4) as drafts through review; MCP tools of section 9.
2. **GenUI:** screens an AI agent composes from governed Sutra fragments, bound to live data as the viewer, streamed to
   other applications through `<drishti-genui>` ([CLIENT_RENDERER.md](CLIENT_RENDERER.md#21-genui-screens-composed-for-an-ai-agent-design-not-built)).
3. **Sajha MCP connector** (moved from 2.6.0 on 2026-10-20): Drishti reaches sajhamcpserver's tools as an MCP client.

**Console lane:**

1. **AI UI:** ask, explain a change, design from intent, a measure from words → draft → review.
2. **AI management:** Admin → AI rebuilt (providers and models, features, data protection with a masked-prompt preview,
   MCP servers and tools, agents, usage and cost with budgets, golden questions, audit, playground), Review → AI drafts and
   My AI ([AI.md](../admin/AI.md#3-ai-management-design-not-built)).
3. **GenUI element and its management** (Admin → GenUI, Build → Fragments, Build → Compose;
   [CLIENT_RENDERER.md §11.8](CLIENT_RENDERER.md#118-managing-genui-in-the-console-new)).
4. **Docs and tutorials** (UI, CLI and YAML).

**Accepted when:** (1) golden questions pass on the mock and one real provider, and an AI answer uses only measures the
user may see; (2) an agent composes a GenUI screen that streams into a host page with live ticks, masked for its viewer;
(3) QA findings fixed before the drill.

**After I: the product owner's real-life QA on 2.8.0.** An isolated, full, real-life QA (real users' tasks on real-shaped
data, outside the builders' own tests). Its findings are triaged first and fixed in wave E before anything else in it.

#### Wave E — QA findings and production readiness → after 2.9.0

1. **Real-life QA findings** first (server and console).
2. **Governance:** environment parameter rules in bundles and deployment between environments; two-way git sync
   (decision 8); usage dataset over the audit log and its report; sensitivity labels inherited by exports; comments on
   visuals with captured state.
3. **3.0 groundwork:** a second query engine behind the tier planner (connector capability descriptors), and the Excel
   add-in's API (the add-in itself in 3.0).
4. **Deferred small items:** designer block groups (Open block, editable `with:`), the netting-set panel title count,
   icons missing from the bundled set, DRS-6011 start-up noise for masks of packs not loaded.

**Accepted when:** (1) every real-life QA finding is fixed or explicitly deferred by the product owner; (2) a bundle with
parameter rules moves a model and its reports from dev to test; (3) QA findings fixed before the drill.

**After E:** 3.0 (the Excel add-in, the second engine in production, scale on real servers).

### 12.3 Risks

| Risk | Mitigation |
|---|---|
| Wave A is large (two new languages, a plan IR, a migration) | the plan IR lands first behind the existing SqlCompiler; DSQL compiles to MQ for most statements; migration is the lane's last task with the equivalence harness |
| DEL v2 converter changes meaning | AST-to-AST printing and identical-SQL assertion for every shipped item |
| Incremental maintenance bugs give wrong live numbers | the subscribed = asked property test over random streams gates B; tick mode as the fallback |
| Several Java nodes add operational weight | single-node remains the default; the design only forbids hidden shared state |
| Browser removal makes Analyse slower | server DSQL plus result caches; measure before the drill |


---

## 13. Decisions for the product owner

| # | Decision | Recommendation |
|---|---|---|
| 1 | **DSQL parser**: Apache Calcite's parser only (no Calcite planner), DuckDB's `json_serialize_sql`, or hand-written | **Calcite parser only**: mature ANSI grammar, independent of the execution engine; we bind and plan ourselves |
| 2 | **Consumer envelope**: report-only readers may run only the queries their report can produce (5.3) | **Yes**: share reports widely without opening the model to free querying |
| 3 | **Security signature**: readers with identical effective security share caches and standing-query evaluations (5.4) | **Yes**: the main lever for many viewers; signatures are opaque |
| 4 | **Measure references** in DEL: `measure('name')` (3.3.2) | **Confirm `measure('name')`** (taken as final so wave A can start) |
| 5 | **SQL wire protocol** for outside tools: PostgreSQL wire, Arrow Flight SQL, or HTTP only | **PostgreSQL wire in C** (psql, ODBC/JDBC, Excel, BI tools); HTTP DSQL from A; Flight SQL later on demand |
| 6 | **Horizontal scale**: stateless asked queries, standing queries and live tables sticky to a model shard, result cache per node | **Yes**; no shared result cache until measurements ask for one |
| 7 | **Python workers**: per-call process (today), warm pool, or fork-server | **Fork-server**: keeps one process per call, removes start-up latency |
| 8 | **Git**: two-way sync between a workspace and a branch | **Two-way in C**; until then export to files and bundles |
| 9 | **No dual support**: the v1 dataset format and old expression syntax are refused at the end of A | **Yes**: converters plus saved states make it reversible |
| 10 | **Knowledge time** ("as known at"): not built; as-of business date only, with generation vectors for reproducibility | **Not built now**; revisit with a regulator use case |

## 14. The model architecture: decisions D1 to D6

These decisions were agreed with the product owner on 2026-10-12 in BI_MODEL_ARCHITECTURE.md, since deleted (in git at tag v2.9.5);
they still stand, and this section is their home. Its wave plan W1 to W16 was replaced by [section 12](#12-w1-as-built-against-this-design-and-the-revised-wave-plan)
(the original, BI_MODEL_ARCHITECTURE_WAVES.md, was deleted: in git at tag v2.9.5), and the expression language is now Python-like ([section 3.3](#33-del-the-drishti-expression-language-python-like)).

*Agreed with the product owner on 2026-10-12.* They settled the BI architecture before more features are added, and
replaced the roadmap part of the 2.1 parity study (BI_PARITY_PLAN.md, deleted: in git at tag v2.9.5; its item ids are [section 11.1](#111-the-21-studys-item-ids); the gap check is now [section 11](#11-gap-check-against-power-bi)). Most of it was
built in 2.2.0 to 2.4.0 (BI_DESIGN.md section 12.2 records what). Drishti is not in production, so these are breaking changes on purpose; the foundation stays **YAML,
Python, datasets and data models**. No Power Query-like tool: Python is the data-prep path. The design
specification of wave W1 (the dataset and model YAML, the model query language v1, pack-generated datasets, the
migration, the UX flows and the build plan) is [section 15](#15-the-model-layer-the-specification-of-wave-w1).

### 14.1 Decisions

| # | Decision |
|---|---|
| D1 | Vocabulary: a **dataset** is one governed table; a **data model** is datasets + relationships + measures. (Today's "dataset" YAML becomes a data model.) |
| D2 | **One model per report** (and per board, notebook, alert, subscription, feed). Report-level links remain only for a report that spans models owned by different teams. |
| D3 | Packs **publish a dataset for every kind** automatically, governed with the pack; users mostly start at the model step. |
| D4 | The model architecture comes **first** (2.2), before calculation and authoring depth. |
| D5 | **One model query language** (versioned YAML/JSON) is the only way the UI, the API, AI and MCP ask a model for data. |
| D6 | Every dataset has a **time mode** (`live`, `snapshot`, `history`); `live` is the default where the pack's connector streams. |

### 14.2 The three layers

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

### 14.3 A model is a mini database, always rooted in packs

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

### 14.4 Shared models, the way Power BI does it — and further

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

### 14.5 The servers compute, the browser draws

Every number is computed by the Java server, the console backend (FastAPI, Python) or the source behind a pack's
connector; the browser only draws the answer it receives, so every device (an iPhone's Safari, a PC's Chrome) shows the
same answer. Today's browser paths (Python visuals, measures and Analyse cells through Pyodide, DuckDB-Wasm slicing)
were to move to the servers in W3 (wave B, 2.3.0; [section 12.2](#122-the-three-waves) says what moved); a personal Python console in the browser came in 2.4.0 ([USER_GUIDE.md › Analyse: slice, SQL and Python, section 16](../guides/USER_GUIDE.md#16-the-browser-python-console)). Details: section 15, its section 12.

### 14.6 The experience comes first

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

## 15. The model layer: the specification of wave W1

*Status: agreed by the product owner on 2026-10-12 (all eight open questions of section 11 accepted with the recommended
answers, question 1 with the security safeguard of section 2.6). Wave W1's server package is built (the item kinds and their
checks, pack-generated datasets, implicit models, the model query language with its API and standing queries, the
compatibility reader; packages S1, S2, S3, S6 and the implicit models of S5); the connector-query, Python and model sources
(S4), the consumers on MQ (S5), the console (C1, C2), the converter (M1) and the guides (D1) follow.* This is the specification behind wave **W1
"Layers and the query language"** of BI_MODEL_ARCHITECTURE.md, now [section 14](#14-the-model-architecture-decisions-d1-to-d6) (decisions D1 to D6, the
experience rules of its section 3). It fixes the **formats** W1 introduces so that W2 to W5 extend them without a
second breaking change: every key that a later wave fills is already named here, marked with the wave that gives it
behaviour. The gap analysis it answers was the 2.1 study (BI_PARITY_PLAN.md, deleted: in git at tag v2.9.5; today's gap check is [section 11](#11-gap-check-against-power-bi)); the current shapes it replaces are
[BI_GUIDE.md](../guides/BI_GUIDE.md#30-datasets-the-yaml-the-expression-language-and-the-security-rules), [BI_GUIDE.md](../guides/BI_GUIDE.md#31-reports-the-yaml-the-visuals-and-how-they-behave), [BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 23](../guides/BI_GUIDE.md#23-boards-report-pages-of-live-tiles),
[USER_GUIDE.md](../guides/USER_GUIDE.md#analyse-slice-sql-and-python), [BI_GUIDE.md](../guides/BI_GUIDE.md#34-python-in-bi-measures-visuals-and-data-prep-written-in-python), [BI_GUIDE.md section 29](../guides/BI_GUIDE.md#29-ai-in-bi-report-from-words-ask-the-data-explain-this-number) and
[BI_GUIDE.md](../guides/BI_GUIDE.md#32-publishing-and-governance-versions-review-certification-lineage-impact-environments).


### 0. Reading guide and terms

| Term | Meaning in this spec |
|---|---|
| **dataset** | one governed **table** (D1): a source, a grain, columns with types and meanings, a time mode, refresh, security. Item kind `dataset`, file `config/bi/datasets/<id>.dataset.yaml` |
| **data model** (model) | datasets in a star, relationships, measures, hierarchies, model security, a contract. Item kind `model`, file `config/bi/models/<id>.model.yaml` |
| **implicit model** | the model Drishti makes for a consumer that names one dataset only (section 3) |
| **model query** (MQ) | the one request shape for data (D5), `mq: 1` (section 5) |
| **consumer** | report, board, notebook, alert, subscription, export, embed, feed, AI, MCP: each bound to exactly one model (D2) |
| **field** | `<alias>.<column>` or `<alias>.<column>.<part>` of a model (alias = the dataset's name inside the model) |
| **stable id** | an immutable id of a column, measure, hierarchy or dataset inside its item (`c_…`, `m_…`, `h_…`), written back into the YAML by the designers (R3 of BI_PARITY_PLAN) |
| **W1 … W5** | the waves of release 2.2.0; a key marked *(W3)* parses in W1, is validated in W1, and gets its behaviour in W3 |

**Language versions.** New items are `rachana: 2`. A `rachana: 1` dataset file is read in W1 by a **compatibility
reader** (section 8.4) and removed in W4 (no dual support after W4, as BI_PARITY_PLAN section 5.0 requires).

**Code ranges.** New problem codes are `DRS-9100` to `DRS-9199` (they were unused when this was written): datasets 9100–9119, models 9120–9139,
consumers 9140–9149, the query language 9150–9179, standing queries 9180–9189, migration 9190–9199. Existing codes keep
their meaning (`DRS-9020` and `DRS-9021` are still what the engine answers for a refused or masked query; the MQ
validator answers the more precise 915x code first).

**Format conventions.** Every item keeps the catalogue keys of today (`title`, `description`, `owner`, `tags`,
`endorsement`, `audience`, `sample`) and the governance of today (drafts, four-eyes, publish, saved states, impact).

---

### 1. The dataset

A dataset is **one table**. It never joins (joins are relationships in a model) and never declares measures (measures
live in models; a dataset declares **measure defaults** per column so a model gets the right aggregation for free).

#### 1.1 Top-level keys

| Key | Required | Meaning |
|---|---|---|
| `rachana: 2` | yes | language version |
| `dataset` | yes | name: letters, digits, `.`, `_`, `-`, up to 64 (generated datasets: `pack.<pack>.<kind>`, section 7) |
| `version` | yes | **semver** of the dataset's contract (`1.0.0`); the designer bumps it (section 2.8 rules apply) |
| `id` | written | stable id of the dataset (`ds_7f3a…`), written on first save, never changed |
| `source` | yes | exactly one of `pack`, `connector`, `python`, `model` (1.2) |
| `grain` | yes | what one row is, in words and keys (1.3) |
| `columns` | yes* | the columns (1.4). *Generated from the source for `pack`; declared for `python` (a contract) and `connector` queries |
| `time` | no | the time mode, as-of and history (1.5). Default: `live` where the source streams, else `snapshot` (D6) |
| `refresh` | no | when the dataset's layers are rebuilt (1.5) |
| `security` | no | `rows` (row rules) and `masked` (1.6) |
| `ai` | no | synonyms, units, examples, hints (1.7) *(behaviour W5)* |
| `tests` | no | expected values and data tests (1.8) |
| `extends` | no | another dataset this one narrows (1.9) |
| `rollups` | no | stored or live aggregates of this table (unchanged meaning from BI_GUIDE.md › Datasets: the YAML, the expression language and the security rules, section 7; `measures:` here are `{agg, column}` pairs, not names) |
| `layout` | no | designer-only (ignored by the language) |

Unknown top-level keys are a warning (`DRS-9100`), as today.

#### 1.2 Sources

```yaml
# A. A pack kind, read through a connector (the common case; also what packs generate, section 7)
source:
  pack: trading                 # optional: which pack's definition of the kind (default: the pack that declares it)
  kind: trade
  connector: trading-store      # optional: a connector by logical name; empty = whichever serves the kind (as today)
  each: legs                    # optional: one row per element of this array (as BI_GUIDE.md › Datasets: the YAML, the expression language and the security rules, section 2)

# B. A connector query: a SQL text run in a SQL-capable connector (DuckDB, PostgreSQL), parameterised only by the as-of date
source:
  connector: risk-warehouse
  query: |
    SELECT desk_id, limit_type, limit_amount, currency, as_of
    FROM limits WHERE as_of = :asOf
  params: { asOf: as-of }       # the only bindable values: as-of, business-date, from, to. Never text from a user

# C. A Python Transform: a table out of model queries (replaces the "derived dataset")
source:
  python: pnl-vs-exposure       # a Python item, interface transform
  inputs:                       # each input is a MODEL QUERY (section 5), asked as the reader
    pnl:      { model: trading-risk@1, select: { fields: [counterparties.id, counterparties.name], measures: ["P&L 1d", Trades] } }
    exposure: { model: counterparty-exposure@1, select: { fields: [counterparties.id, counterparties.sector], measures: [Exposure (EE peak), Exposure (PFE peak)] } }
  params: { floor: 1.0 }
  where: server                 # always the console backend's sandboxed runner (no browser computation, section 12)
# 2.4.0: an input may instead be a pack kind read straight through a connector, as the reader (its entitlements, row rules and masks):
#   trades: { kind: trade, pack: trading, connector: trade-endur, fields: [TRADE_ID, PORTFOLIO, NOTIONAL], where: [...], limit: 50000 }
# (docs/guides/BI_GUIDE.md › Python in BI: measures, visuals and data prep written in Python, section 17.1: the kind's pack-generated dataset through its implicit model; a named connector must keep the fields as columns)

# D. Another model's output: one model query, its answer is the table (replaces simple "composed" sources)
source:
  model: trading-risk@1
  query: { select: { fields: [desks.id, trades.currency], measures: [MTM, Notional] } }
```

| Source | Grain from | Columns from | Time modes allowed | Security applied |
|---|---|---|---|---|
| `pack` | the kind (one entity per business date, or one array element) | the pack (section 7): promoted columns, query-form fields, vocabulary | `live`, `snapshot`, `history` | the kind's entitlements, the dataset's rules and masks |
| `connector` query | declared `grain.keys` (checked by a uniqueness test) | declared, or read from the query's result types on **Check** | `snapshot`, `history` | the connector's entitlement (the reader may open the connector's packs), the dataset's rules and masks |
| `python` | declared `grain.keys` | declared `columns:` (a contract; a missing column is `DRS-9075` as today) | `snapshot` (kept per reader and input version, as today) | each input (a model query, or since 2.4.0 a pack kind through a connector) runs **as the reader**; the dataset's own rules on top |
| `model` | the query's `fields` | the query's fields and measures (measures arrive as columns with their additivity carried over) | `snapshot`, `live` (when the source model is live: a standing query, W3) | the inner query runs as the reader; the dataset's own rules on top |

A `connector` query is **text the author writes**, reviewed like any item; its only parameters are the four named
dates, bound, never interpolated. A connector without a SQL target refuses it (`DRS-9102`).

#### 1.3 Grain and keys

```yaml
grain:
  says: One row per trade per business date      # shown in the designer, the catalogue and to AI
  keys: [tradeId]                                # columns that identify a row within one business date / one live state
  date: businessDate                             # the column that is the row's date (snapshot, history); built in for pack sources
```

`keys` are checked by a **grain test** that Check and publish run (a duplicate key over a sample date is `DRS-9104`,
a warning for `python` and `model` sources until W2, an error from W2). The grain is what lets a model refuse fan-out
(section 2.3) without guessing.

#### 1.4 Columns

```yaml
columns:
  tradeId:  { type: string, role: key, id: c_01 }
  desk:     { type: string, role: dimension, references: desk, id: c_02 }       # references: a kind (from the pack's graph.fields): drives relationship suggestions
  currency: { type: string, role: dimension, meaning: currency-code, id: c_03 }
  notional: { type: decimal, role: amount, unit: { currency: currency }, additive: true, aggregate: sum, format: amount0, id: c_04 }
  mtm:      { type: decimal, role: amount, unit: { currency: USD }, additive: true, aggregate: sum, format: signed0, id: c_05 }
  eePeak:   { type: decimal, role: amount, unit: { currency: USD }, additive: { over-time: last }, aggregate: sum, id: c_06 }   # semi-additive (W2/W7)
  utilisation: { type: decimal, role: ratio, additive: false, aggregate: max, format: pct, id: c_07 }
  tradeDate: { type: date, role: date, id: c_08 }
  mtmK:     { type: decimal, expr: "mtm / 1000", role: amount, unit: { currency: USD }, id: c_09 }               # calculated column, as today
  counterpartyName: { type: string, path: counterparty.name, role: attribute, of: counterpartyId, id: c_10 }
```

| Key | Meaning |
|---|---|
| `type` | `string integer decimal boolean date timestamp` (as today) |
| `path`, `expr` | as today (BI_GUIDE.md › Datasets: the YAML, the expression language and the security rules, section 2); `expr` is a row-level calculated column |
| `role` | `key`, `dimension` (group and filter by it), `attribute` (describes a key: `of:` names it; used for fan-out reasoning), `date`, `amount`, `quantity`, `ratio`, `text`. Drives the designer's defaults (a `dimension` goes on an axis, an `amount` into values) |
| `meaning` | a word from the pack vocabulary (`about.yaml`), copied from the pack for generated datasets (`term`, `means`, `unit`, `sign`); shown everywhere a column is named |
| `references` | the kind this column holds ids of (pack `graph.fields`); the source of relationship suggestions |
| `unit` | `{ currency: <column or ISO code> }` for money, `{ of: <text> }` otherwise. **The currency guard** (section 5.6) reads it |
| `additive` | `true` (sum anywhere), `{ over-time: last \| first \| avg \| none }` (semi-additive: sums across other dimensions, not across dates), `false` (never summed). Default by role: amounts and quantities `true`, ratios `false` |
| `aggregate` | the default aggregation the model's **default measure** uses (`sum avg min max count distinct`) |
| `format` | a format name of the pack |
| `id` | stable id, written by the designer / converter |

Built-in columns are kept: `id`, `businessDate` (snapshot and history), `index` (`each`), `updateSeq`, `updatedAt`
(live). They are not declared.

#### 1.5 Time mode, as-of and refresh (D6)

```yaml
time:
  mode: snapshot            # live | snapshot | history
  as-of: business-date      # snapshot: which date a query without one reads: business-date (latest with rows, as today) | latest-load
  keep: 400d                # snapshot and history: how far back the columns layer is kept (default: drishti.bi.query.max-days)
  live:                     # mode live: the in-memory table, as BI_GUIDE.md › Datasets: the YAML, the expression language and the security rules, section 7.1 (rows, age)
    rows: 5000
    age: 2h
  history:                  # mode history (or a live dataset's sampled history, today's live.history)
    every: 10s              #   sampled from the live table every 10 s ...
    keep: 360               #   ... newest 360 samples
    by: [desk]
refresh: "business-days 06:30"    # the words of BI_GUIDE.md › Datasets: the YAML, the expression language and the security rules, section 7.2; after-load; or { schedule, on-load }
```

| Mode | The table is | A query may ask | Default when |
|---|---|---|---|
| `live` | the current state, kept by key, pushed by the connector (today's `mode: live`) | *now*; *the last hour* through its history | the pack's connector for the kind can push (`subscribe`) |
| `snapshot` | one partition per business date (today's `mode: cached`, and `direct` where the connector is SQL) | *as of a date*, a date range | otherwise |
| `history` | a time-indexed table of samples or of a temporal source (rows valid from, to) | *a window* (`last 1h`, `from … to`) | never by default |

`direct` storage stays a property of the snapshot dataset (`time: { mode: snapshot, storage: direct }`) and keeps
today's rule (every direct dataset of one query in one store). A model **may mix modes** *(W3)*: a live fact with
snapshot dimensions is read with the dimensions as of today's business date.

#### 1.6 Security at the dataset

```yaml
security:
  rows:
    - { role: desk-head, where: "trades.desk in user.desks" }   # the table is called by the dataset's own name in its rules: here `trades`
    - { role: [risk, analyst, admin], where: "true" }
  masked: [counterpartyName]
  hidden-for: { roles: [external], columns: [trader] }          # (W2) object-level: the column does not exist for these readers
```

Rules and masks keep **today's semantics** (BI_GUIDE.md › Datasets: the YAML, the expression language and the security rules, section 6): OR of the reader's matching rules, no rule = no
rows, masks before aggregation, `•••` dimensions not grouped on, measures over a masked column null, filter or sort on
one `DRS-9021`. What changes is **where they bind**: a rule names only its own dataset's columns (`DRS-9106` otherwise),
so it can be applied to that table **before any join or aggregation**, wherever the dataset is used. Section 2.6 says how
a rule on a dimension reaches the facts.

#### 1.7 `ai:` metadata *(behaviour W5)*

```yaml
ai:
  synonyms: { mtm: [mark to market, market value, MV], desk: [trading desk] }
  examples: ["MTM by desk", "trades in EUR this week"]
  hints: ["A trade's notional is in its own currency; convert before adding across currencies."]
  exclude: [internalNote]        # never offered to a model, even by name
```

#### 1.8 Tests

Today's `tests:` keep their form (BI_GUIDE.md › Datasets: the YAML, the expression language and the security rules, section 8.1) with `measure:` replaced by an inline aggregate
(`{ agg: count }`, `{ agg: sum, column: mtm }`), plus **data tests** that need no model:

```yaml
tests:
  - { name: Unique trade per date, unique: [tradeId] }                        # the grain test (1.3)
  - { name: Desk is always set, not-null: [desk] }
  - { name: Desk exists, references-exist: { column: desk, dataset: pack.core.desk } }   # (W2) referential test
  - { name: The book holds 750 trades, agg: count, expect: 750, date: 2026-10-09 }
```

Since 2.2.0 each runs as Drishti SQL on the dataset's implicit model, as the test's `role:` (else the person checking), on the text being checked
(`POST /api/v1/bi/items/{id}/tests`, the dataset page's *Expected values*): `agg` (with `column`, and the expected-value keys `expect`, `tolerance`,
`by`, `row`, `filters`, `date`) compares one figure; `unique` passes when no key has two rows (else it names the worst key and its count);
`not-null` counts the empty values of each column; `references-exist` compares the distinct values of `column` with the `key` (default `id`) of
`dataset`. A failing test refuses the publish (`DRS-9043`; a bi-admin may force it), for a model's SQL `tests:` too.

#### 1.9 `extends`: narrowing a dataset without copying it

```yaml
dataset: trades-eu
extends: pack.trading.trade@1      # all columns, grain, time, security of the base
where: "currency in ('EUR', 'GBP', 'CHF')"     # a row filter (a dataset filter, not a security rule)
columns:                           # add calculated columns, override title/format/meaning; cannot remove a key
  notionalM: { type: decimal, expr: "notional / 1000000", role: amount, unit: { currency: currency } }
security: { masked: [trader] }     # only adds (only tighter, as for a model)
```

`extends` is how an author customises a **pack-generated** dataset (which is read-only, section 7).

#### 1.10 Full examples

The generated dataset of the trading pack's `trade` (what a person sees on its YAML tab; written by Drishti, not typed):

```yaml
rachana: 2
dataset: pack.trading.trade
version: 1.0.0                    # = the trading pack's version (section 7.3)
id: ds_pack_trading_trade
title: Trade
description: Trades in 125 products across rates, FX, credit, equity, commodities, inflation, fixed income, securities financing, money markets and structured products.
owner: pack:trading
endorsement: certified            # certified by the pack (section 7.4)
generated: { by: pack, pack: trading, packVersion: 1.0.0 }
source: { pack: trading, kind: trade }            # connector: whichever serves the kind (trading-store in the demo)
grain: { says: One row per trade per business date, keys: [tradeId], date: businessDate }
time: { mode: snapshot, as-of: business-date }    # the demo Delta connector does not push; with a pushing connector: live
refresh: { on-load: true }
columns:
  tradeId:          { type: string,  role: key, meaning: trade-id, id: c_tradeId }
  desk:             { type: string,  role: dimension, references: desk, id: c_desk }
  book:             { type: string,  role: dimension, references: book, id: c_book }
  nettingSet:       { type: string,  role: dimension, references: netting-set, id: c_nettingSet }
  counterpartyId:   { type: string,  path: counterparty.id, role: dimension, references: counterparty, id: c_counterpartyId }
  counterpartyName: { type: string,  path: counterparty.name, role: attribute, of: counterpartyId, id: c_counterpartyName }
  productType:      { type: string,  role: dimension, id: c_productType }
  productName:      { type: string,  role: attribute, of: productType, id: c_productName }
  direction:        { type: string,  role: dimension, meaning: direction, id: c_direction }
  assetClass:       { type: string,  role: dimension, id: c_assetClass }
  currency:         { type: string,  role: dimension, meaning: currency, id: c_currency }
  status:           { type: string,  role: dimension, id: c_status }
  notional:         { type: decimal, role: amount, meaning: notional, unit: { currency: currency }, additive: true, aggregate: sum, format: amount0, id: c_notional }
  mtm:              { type: decimal, role: amount, unit: { currency: USD }, additive: true, aggregate: sum, format: signed0, id: c_mtm }
  pnl1d:            { type: decimal, role: amount, unit: { currency: USD }, additive: true, aggregate: sum, format: signed0, id: c_pnl1d }
  dv01:             { type: decimal, path: risk.dv01, role: amount, unit: { currency: USD }, additive: true, aggregate: sum, format: signed0, id: c_dv01 }
  tradeDate:        { type: date, role: date, meaning: tradeDate, id: c_tradeDate }
  maturityDate:     { type: date, role: date, meaning: maturityDate, id: c_maturityDate }
security:
  masked: [counterpartyName]       # from drishti.security.redact and the pack's sensitive fields
ai:
  synonyms: { mtm: [mark to market], pnl1d: [daily P&L, one-day P&L] }
tests:
  - { name: One row per trade, unique: [tradeId] }
```

A user dataset over a connector query, and a Python dataset (the migrated `desk-pnl-vs-exposure-py`):

```yaml
rachana: 2
dataset: desk-limits
version: 1.0.0
title: Desk limits
owner: risk-control
source:
  connector: risk-warehouse
  query: "SELECT desk_id, limit_type, limit_amount, currency FROM limits WHERE as_of = :asOf"
  params: { asOf: as-of }
grain: { says: One row per desk and limit type per date, keys: [desk_id, limit_type] }
time: { mode: snapshot }
refresh: "business-days 06:30"
columns:
  desk_id:      { type: string, role: dimension, references: desk }
  limit_type:   { type: string, role: dimension }
  limit_amount: { type: decimal, role: amount, unit: { currency: currency }, additive: { over-time: last }, aggregate: sum, format: amount0 }
  currency:     { type: string, role: dimension, meaning: currency }
tests: [{ name: One limit per desk and type, unique: [desk_id, limit_type] }]
---
rachana: 2
dataset: pnl-vs-exposure-py
version: 1.0.0
title: Desk P&L vs counterparty exposure (Python)
source:
  python: pnl-vs-exposure
  inputs:
    pnl:      { model: trading-risk@1, select: { fields: [counterparties.id, counterparties.name], measures: ["P&L 1d", Trades] } }
    exposure: { model: counterparty-exposure@1, select: { fields: [counterparties.id, counterparties.sector], measures: [Exposure (EE peak), Exposure (PFE peak)] } }
  params: { floor: 1.0 }
grain: { says: One row per counterparty, keys: [counterparty] }
time: { mode: snapshot }
columns:
  counterparty:     { type: string,  role: key, references: counterparty }
  name:             { type: string,  role: attribute, of: counterparty }
  sector:           { type: string,  role: dimension }
  desk_pnl:         { type: decimal, role: amount, unit: { currency: USD }, aggregate: sum }
  trades:           { type: integer, role: quantity, aggregate: sum }
  ee_peak:          { type: decimal, role: amount, unit: { currency: USD }, aggregate: sum }
  pfe_peak:         { type: decimal, role: amount, unit: { currency: USD }, aggregate: sum }
  pnl_per_exposure: { type: decimal, role: ratio, additive: false, aggregate: avg }
  exposure_rank:    { type: decimal, role: ratio, additive: false, aggregate: min }
```

(In the converted samples the Python transform's input renames, `as Desk P&L`, become column renames in the Transform
itself; the MQ `select` has no `as`, the answer's columns are the field and measure names.)

#### 1.11 Dataset problems

| Code | Meaning |
|---|---|
| `DRS-9100` | structure: unknown key (warning), missing `source`, two sources, bad name, bad `version` (not semver) |
| `DRS-9101` | a column: unknown type, role, aggregate, additivity; `of:` naming no column; `references:` naming no kind |
| `DRS-9102` | a source the server cannot use: no such pack/kind/connector, a query on a connector without a SQL target, a parameter that is not one of the four dates |
| `DRS-9103` | a time mode the source cannot give (`live` on a connector that cannot push and has no resync; `live` on `each`; `history` with no history block) |
| `DRS-9104` | the grain: `keys` naming no column, or (test) a duplicate key |
| `DRS-9105` | `extends`: base not found, removes a key, or loosens security |
| `DRS-9106` | security: a rule naming another dataset's columns, a mask naming no column |
| `DRS-9107` | a generated dataset edited (it is read-only; use `extends`) |
| `DRS-9108` | a Python or model source: an input query that does not validate (the 915x code of the inner query is attached), or (when read) an answer without a declared column |
| `DRS-9109` | a model source that reaches its own model again (`model:a -> dataset:x -> model:a`): refused by Check, the bind and at run time |

---

### 2. The data model

A model puts datasets in a star and gives them measures. It never copies data: every dataset is read through its own
layers, with its own security, and the model adds relationships, measures, hierarchies and tighter security.

#### 2.1 Top-level keys

| Key | Meaning |
|---|---|
| `rachana: 2`, `model`, `version` (semver), `id` | as for a dataset; `model` is the name (`trading-risk`) |
| `datasets` | the datasets it uses, each with an **alias** (the name fields use), a version range and a `role` (2.2) |
| `relationships` | 2.3 |
| `measures` | 2.4 |
| `calc-groups` | *(W6)* placeholder, parsed and kept, refused in W1 with `DRS-9129` if non-empty |
| `hierarchies` | as today (levels are model fields) |
| `parameters` | *(W6)* placeholder: what-if and field parameters, parsed, refused if non-empty in W1 (`DRS-9129`) |
| `calendars` | *(W7)* placeholder: the business calendar the model's dates use (default `drishti.business-date.calendar`) |
| `security` | model security, **only tighter** (2.6) |
| `contract` | what consumers may use, with stable ids and semver (2.8) |
| `ai` | model-level synonyms, descriptions, examples *(W5)* |
| `golden` | golden questions *(W5; parsed and run as tests from W1 when they carry `query:`)* (2.9) |
| `tests` | expected values over model measures (today's `tests:` form) |
| `rollups` | aggregate tables over one fact (today's rollups, per fact alias) |
| `lifecycle` | `draft`, `published`, `deprecated` (with `replaced-by:`), `retired` (2.8) |
| `layout` | where the designer draws each dataset |

#### 2.2 Datasets and star roles

```yaml
datasets:
  trades:         { dataset: pack.trading.trade@^1, role: fact }
  netting_sets:   { dataset: pack.counterparty-risk.netting-set@^1, role: fact }
  counterparties: { dataset: pack.core.counterparty@^1, role: dimension }
  desks:          { dataset: pack.core.desk@^1, role: dimension }
  books:          { dataset: pack.core.book@^1, role: dimension }
```

* The alias (`trades`) is what fields and expressions use (`trades.mtm`): letters, digits, underscore. The converter
  keeps today's table names as aliases, so `trades.desk` still means the same.
* `@^1` is a semver range (`@1.2.0` exact, `@^1` compatible with 1, `@~1.2`); without one, the latest published
  version is pinned on save (the designer writes the pin). A source dataset publishing a new **major** does not move the
  model until the model is edited (impact analysis lists it).
* `role`: `fact` (has measures, the many side), `dimension` (described, grouped by), `bridge` *(W2/R4: many-to-many
  resolution)*. The designer infers it (a dataset whose keys others reference is a dimension; one with amounts is a
  fact) and the author may change it. A dataset with no relationship to the others is an **island**: allowed with a
  warning (`DRS-9121`), it can still be used alone in a visual (Open question 3).

#### 2.3 Relationships

```yaml
relationships:
  - { id: r_1, from: trades.desk,             to: desks.id,          cardinality: many-to-one }
  - { id: r_2, from: trades.counterpartyId,   to: counterparties.id, cardinality: many-to-one }
  - { id: r_3, from: netting_sets.counterparty, to: counterparties.id, cardinality: many-to-one }
  - { id: r_4, from: trades.nettingSet,       to: netting_sets.id,   cardinality: many-to-one, active: false }   # fact-to-fact: inactive by default
  - { id: r_5, from: trades.settleDate,       to: calendar.date,     cardinality: many-to-one, active: false, name: by settle date }   # (W7) role-playing
```

| Key | Meaning |
|---|---|
| `from`, `to` | the many side and the one side (as today); both fields of the model, same type |
| `cardinality` | `many-to-one` (default), `one-to-one`, `one-to-many` (stored reversed), `many-to-many` *(W2: through a `bridge`)* |
| `direction` | `single` (default: filters flow from the one side to the many side) or `both` *(W2/R4: semi-join)* |
| `active` | `true` (default) or `false`; an inactive relationship is used only by a measure that names it *(W7: `via:`)* |
| `suggested` | written by the designer for a relationship it proposed and the author accepted: `{ from: pack-reference \| values \| name, match: 0.98 }` (provenance) |

**Validation (W1).** Both fields exist and share a type (`DRS-9122`). The one side must be the dataset's **grain key**
(or a column the grain test proves unique): a relationship whose `to` is not unique is refused (`DRS-9123`, the
model-level fan-out refusal; *W2* adds the full refusal of any path that repeats rows). At most one **active** path
between any two datasets (`DRS-9124`, ambiguity: make one inactive). A fact-to-fact relationship is allowed but the
designer proposes it inactive and suggests the shared dimension instead.

#### 2.4 Measures

```yaml
measures:
  MTM:            { id: m_mtm, expr: "sum(trades.mtm)", format: signed0, description: Mark-to-market in USD, signed. }
  Notional:       { id: m_notional, expr: "sum(trades.notional)", format: amount0, currency: trades.currency }
  Trades:         { id: m_trades, expr: "count(trades)" }
  Exposure (EE peak): { id: m_ee, expr: "sum(netting_sets.eePeak)", format: amount0, additive: { over-time: last } }
  MTM per 1m notional: { id: m_mtm1m, expr: "ratio(sum(trades.mtm), sum(trades.notional)) * 1000000", format: signed2 }
  P&L per exposure: { id: m_ratio, expr: "[P&L 1d] / [Exposure (EE peak)]", format: signed2 }    # across two facts: each at its grain (W2 rule, built today)
  Desk P&L volatility: { id: m_vol, python: desk-pnl-volatility, inputs: [trades.pnl1d], by: [trades.desk] }   # Python measure, as today
  MTM by settle date: { id: m_mtm_s, expr: "sum(trades.mtm)", via: r_5 }   # (W7) inactive relationship
```

* The **expression language is unchanged** (BI_GUIDE.md › Datasets: the YAML, the expression language and the security rules, section 4: aggregates, `[Measure]`, time intelligence,
  windows), with `table` meaning a dataset alias.
* `currency:` names the column (or ISO code) the measure's amounts are in when the column's `unit` is per row; the
  currency guard reads it (5.6). `additive:` defaults from the column (1.4); a measure of measures is non-additive
  unless it says so. These feed **true totals** (BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 3.2) instead of today's guess from the aggregate.
* **Default measures.** A model gets, without writing them, one measure per fact column whose `aggregate` is set
  (`Sum of notional`, `Count of trades`), marked `implicit: true`, hidden from the contract unless promoted. The
  designer writes them out when the author renames or formats one.

#### 2.5 Calculation groups and parameters *(placeholders)*

```yaml
calc-groups:            # (W6) templates applied to any measure; refused if non-empty in W1
  Time:
    items: { YTD: "ytd(${measure}, trades.tradeDate)", Prior: "prior_period(${measure}, trades.tradeDate)" }
parameters:             # (W6) what-if and field parameters; refused if non-empty in W1
  shock: { kind: number, default: 0, min: -0.5, max: 0.5, step: 0.01 }
```

They are named now so the contract (2.8), the query language (`params:` in 5.2) and stable ids reserve their place.

#### 2.6 Model security: only tighter

```yaml
security:
  rows:
    trades:                                          # per dataset alias: a rule names only that alias's columns
      - { role: desk-head, where: "trades.desk in user.desks" }
      - { role: [risk, analyst, admin], where: "true" }
  masked: [counterparties.rating]                    # adds masks; cannot unmask
  propagate: dimension-to-fact                       # the default and only W1 value
  may-see-all: { netting_sets: [desk-head] }         # the safeguard's explicit note (below): desk-heads see every netting set
```

* **Dataset rules first, always.** For each reader, every dataset in a query is filtered by **its own** rules and
  masked by its own masks before it is joined or aggregated (D: "security is applied per reader at the dataset").
* **Model rules are a second filter on the same dataset.** `rows.<alias>` has the dataset semantics (the reader's
  matching rules are ORed; a reader with no matching rule gets no rows of that alias) and the result is **ANDed** with
  the dataset's own rules, applied at the dataset before any join. AND can only remove rows, so a model is tighter by
  construction. A rule naming another alias's columns is `DRS-9125`, as is an `unmask` key or a mask list that tries
  to remove a dataset mask.
* **Propagation (dimension to fact).** A row rule on a **dimension** filters the facts that reference it along active
  many-to-one relationships (a fact row whose key points at a dimension row the reader may not see is not counted).
  A rule on a fact never filters a dimension or another fact. This replaces today's fail-closed refusal: today a
  desk-head's rule on `trades` made exposure by counterparty refused (BI_GUIDE.md › Datasets: the YAML, the expression language and the security rules, section 6); with security at the
  dataset, netting sets (which carry no rule) answer, and trades answer for the desk-head's desks only. This is a
  deliberate change of answer, listed in section 8.5.
* A row rule naming `user.*` attributes is evaluated on the server as today; the reader's rule set is part of the
  cache key as today.
* **The safeguard** (the product owner's condition for accepting security per dataset, Open question 1). A role is
  *restricted* in a model when a row rule of the model, or of one of its datasets, names it with a condition other than
  `true`. Every dataset of the model that **no row rule restricts at all** (neither its own nor the model's) is open to
  such a role, which then sees every one of its rows. **Check flags** each such dataset and role (`DRS-9130`, a warning,
  and the list `exposures` in the Check answer), and **the model cannot be published** (`409 DRS-9130`) until that dataset
  gets a rule (in the dataset, or under the model's `security.rows.<alias>`) **or** the model carries an explicit note
  `security.may-see-all: { <alias>: [<role>, ...] }`. The publish that relies on a note records it in the audit log
  (`bi-model-may-see-all`, naming the model version, the datasets and the roles). A dataset whose rules simply do not
  mention a role is not flagged: that role sees none of its rows (no matching rule, no rows). The sample of section 2.10
  therefore says `may-see-all` for `netting_sets`, `counterparties`, `desks` and `books` for `desk-head`.

#### 2.7 Hierarchies, rollups, tests

Unchanged in form from BI_GUIDE.md (sections 5, 7, 8.1); levels and `by:` use model fields; a rollup names its fact
(`table: trades`) and lives in the model (a rollup is an aggregate of the model's measures).

#### 2.8 The contract: semver and stable ids

```yaml
version: 1.3.0
contract:
  exposes: all                 # all (default) | listed: only the fields and measures below may be used by consumers
  fields:   [desks.name, counterparties.sector, trades.currency, trades.tradeDate]
  measures: [MTM, Notional, Trades, Exposure (EE peak)]
  deprecated: { "MTM median": { since: 1.2.0, use: MTM } }
lifecycle: published
```

* Every column (dataset), measure, hierarchy and relationship has an **id**, written once; consumers **store ids**
  next to names (`{ field: desks.name, id: ds_desk/c_name }` in the saved YAML, written by the designer). A rename
  changes the name only; consumers keep working and show the new name (R3).
* **Breaking change** = a contract field or measure removed, its type changed, a measure's `expr` changed in meaning
  (as BI_GUIDE.md › Publishing and governance: versions, review, certification, lineage, impact, environments, section 6 "alters"), a relationship removed or made inactive, security loosened is impossible,
  a grain key changed. Publishing a breaking change needs a new **major** version (`DRS-9126` otherwise; W1 detects
  removed and renamed ids; W2 adds the full breaking-change detection and refusal). A minor adds; a patch changes
  descriptions, formats and `ai:`.
* A **contract that exposes less** is breaking too: `exposes: all` made `listed`, or a field or measure taken out of the lists while the model
  still has it (consumers may use only what the contract exposes). A measure renamed under the same id is the same measure.
* **One version string, one content:** every publish of a change carries a new version (`DRS-9126` says which bump: a patch for words, formats or a
  hidden field, a minor for an addition, a major for a breaking change), and the version never goes down.
* Consumers bind to a **major** (`model: trading-risk@1`). A new major does not move them: a reference pinned `@1` keeps reading the latest published
  1.x (from the item's history, `drishti.bi.versions-keep`; its own layers), `@2` and the bare name read 2.x; impact lists them and the designer offers
  to move each (with a diff of what it uses).
* **One model per name**: a workspace holds one model of a name, and the catalogue one published model of a name (`DRS-9004` on a save, move or
  publish that would make a second).

#### 2.9 `ai:` and golden questions *(W5)*

```yaml
ai:
  description: Trades, counterparties and netting-set exposure across three packs.
  synonyms: { "Exposure (EE peak)": [EE, expected exposure, exposure] }
golden:
  - question: "What is the MTM of the rates desk?"
    query: { select: { measures: [MTM] }, where: [{ field: desks.id, op: eq, value: DESK-RATES }] }
    expect: { value: 161766771, tolerance: 0.5% }
```

A golden question is a model query (5) plus an expected answer; from W1 it runs as a **test** (its query part); from W5
the AI must produce an equivalent query from the words.

#### 2.10 Full example: `trading-risk` as a model

```yaml
rachana: 2
model: trading-risk
version: 1.0.0
id: md_trading_risk
title: Trading and counterparty risk
owner: risk-analytics
endorsement: promoted
sample: true
datasets:
  trades:         { dataset: pack.trading.trade@^1, role: fact }
  netting_sets:   { dataset: pack.counterparty-risk.netting-set@^1, role: fact }
  counterparties: { dataset: pack.core.counterparty@^1, role: dimension }
  desks:          { dataset: pack.core.desk@^1, role: dimension }
  books:          { dataset: pack.core.book@^1, role: dimension }
relationships:
  - { from: trades.desk,               to: desks.id,          suggested: { from: pack-reference } }
  - { from: trades.book,               to: books.id,          suggested: { from: pack-reference } }
  - { from: trades.counterpartyId,     to: counterparties.id, suggested: { from: pack-reference } }
  - { from: netting_sets.counterparty, to: counterparties.id, suggested: { from: pack-reference } }
  - { from: trades.nettingSet,         to: netting_sets.id,   active: false }
measures:
  MTM:                 { expr: "sum(trades.mtm)", format: signed0 }
  Notional:            { expr: "sum(trades.notional)", format: amount0, currency: trades.currency }
  "P&L 1d":            { expr: "sum(trades.pnl1d)", format: signed0 }
  Trades:              { expr: "count(trades)" }
  Counterparties:      { expr: "distinct(trades.counterpartyId)" }
  MTM per 1m notional: { expr: "ratio(sum(trades.mtm), sum(trades.notional)) * 1000000", format: signed2 }
  Worst trade MTM:     { expr: "min(trades.mtm)", format: signed0 }
  MTM median:          { expr: "percentile(trades.mtm, 0.5)", format: signed0 }
  MTM ytd:             { expr: "ytd(sum(trades.mtm), trades.tradeDate)", format: signed0 }
  MTM rank:            { expr: "rank(sum(trades.mtm))" }
  MTM 3-day average:   { expr: "moving_avg(sum(trades.mtm), 3, trades.tradeDate)", format: signed0 }
  Open MTM:            { expr: "sum(trades.mtm, trades.status == 'Live')", format: signed0 }
  Exposure (EE peak):  { expr: "sum(netting_sets.eePeak)",  format: amount0 }
  Exposure (PFE peak): { expr: "sum(netting_sets.pfePeak)", format: amount0 }
  Netting sets:        { expr: "count(netting_sets)" }
  P&L per exposure:    { expr: "[P&L 1d] / [Exposure (EE peak)]", format: signed2 }    # replaces the composed dataset's measure
hierarchies:
  Trade date:    [trades.tradeDate.year, trades.tradeDate.quarter, trades.tradeDate.month, trades.tradeDate]
  Desk and book: [desks.name, books.name]
  Counterparty:  [counterparties.sector, counterparties.name]
rollups:
  - { name: by_desk_currency, table: trades, by: [desk, currency, assetClass], measures: [MTM, Notional, "P&L 1d", Trades, MTM per 1m notional] }
security:
  rows:
    trades:
      - { role: desk-head, where: "trades.desk in user.desks" }
      - { role: [risk, analyst, admin], where: "true" }
  masked: [counterparties.name, counterparties.rating]     # trades.counterpartyName is masked by the pack dataset already
  may-see-all: { netting_sets: [desk-head], counterparties: [desk-head], desks: [desk-head], books: [desk-head] }   # the safeguard (2.6), audited on publish
tests:
  - { name: The book holds 750 trades, measure: Trades, expect: 750 }
```

The pack datasets are read-only (section 7), so the old file's row rule becomes a **model** rule on `trades` (only
tighter: ANDed with the pack dataset, which has none). An organisation that wants the same rule in every model over
trades writes it once in a dataset that `extends` the pack's (1.9) and uses that dataset instead.

#### 2.11 Model problems

| Code | Meaning |
|---|---|
| `DRS-9120` | structure: bad name, version, alias; a dataset not found or not at the pinned version |
| `DRS-9121` | an island dataset (warning) |
| `DRS-9122` | a relationship naming no field, or fields of different types |
| `DRS-9123` | a relationship's one side is not unique (not the grain key and not proven unique): it would repeat rows |
| `DRS-9124` | two active paths between two datasets (ambiguous) |
| `DRS-9125` | model security looser than a dataset's |
| `DRS-9126` | a breaking change without a new major version |
| `DRS-9127` | a measure: today's `DRS-9011`/`9012`/`9013` conditions, reported with the model path |
| `DRS-9128` | a contract naming a field or measure the model lacks |
| `DRS-9129` | a W6/W7 placeholder (`calc-groups`, `parameters`, `via`) used before its wave |
| `DRS-9130` | the security safeguard (2.6): a dataset no row rule restricts, in a model where a role is restricted elsewhere, with no `may-see-all` note for that role (a warning in Check; the publish is refused) |

---

### 3. Implicit models

A consumer that names **one dataset** (`dataset: pack.trading.trade`) gets an **implicit model**. Rules:

1. **Made, not stored.** It is computed from the dataset when it is read: id `implicit:<dataset id>@<major>`, one
   dataset whose alias is the dataset's last name part with `-` turned into `_` (`pack.trading.trade` is aliased
   `trade`, `trades-basic` keeps its table alias `trade` through the converter), role `fact`.
2. **Measures:** the default measures of section 2.4 (one per column with an `aggregate`), plus `Count of <alias>`.
   Names are stable and documented: `Count of trade`, `Sum of notional`, `Average of utilisation`.
3. **Pack dimensions come along, one hop.** For a pack-generated dataset, every column with `references:` whose kind
   has a pack-generated dataset adds that dataset as a **dimension** with an active many-to-one relationship
   (`trade.desk → desk.id`). One hop only, never facts. This is what makes "MTM by desk name" work from a bare trade
   dataset without a model step (Open question 2).
4. **Security:** the datasets' own; nothing added.
5. **Visible and promotable.** Wherever a consumer shows its model (the designer's Fields pane header, the report's
   About drawer, lineage), an implicit model reads *Implicit model of Trade* with **Make it a model**: that writes the
   implicit model out as a draft `model` item (the YAML of section 2, with `implicit-from:` provenance) and rebinds the
   consumer to it in one undoable edit. A consumer's local measures (4.2) move into the new model on request.
6. **Contract:** an implicit model's version is its dataset's version; its contract is everything (`exposes: all`).
7. **Not allowed on an implicit model:** model security, relationships beyond rule 3, calculation groups. Wanting any of
   them is the moment to press *Make it a model*; the designer says so beside the control the author tried.

---

### 4. Consumers bind to one model

#### 4.1 The binding key

Every consumer has exactly one `model:` (D2):

```yaml
model: trading-risk@1          # a model by name or catalogue id, with a major (the designer writes the pin)
# or
dataset: pack.trading.trade    # shorthand: the implicit model of this dataset (section 3)
```

| Consumer | Today binds to | Binds to (W1 for queries, W4 for the YAML) | Note |
|---|---|---|---|
| report | `dataset:` + per-visual `dataset:` + `links:` | `model:` (visual `dataset:` removed; `links:` only as 4.3) | every visual query is an MQ against the report's model |
| board | tiles name report visuals | `model:` on the board, checked: every tile's report is on that model (`DRS-9141`) | tiles from another owner's model are the 4.3 exception |
| notebook | `dataset:` + `extracts[].dataset` + fields | `model:`; each extract is an MQ with `shape: rows` (5.1) | Analyse slices are MQs too |
| alert | `source.type: visual \| dataset \| field \| tile` | `visual` (model via the report), `model` (`{ model, query }`, replaces `dataset`), `field`, `tile` | `query` is an MQ that must answer one number |
| subscription | report / page / visual + state | unchanged target; the model comes from the report | each recipient's render is MQs as that recipient |
| export, embed | report visual | unchanged; MQ underneath | |
| Excel / SQL feed *(W13)* | — | `model:` + MQ | |
| AI ask / explain, MCP *(W5)* | report's dataset, `QueryRequest` | `model:` + MQ only | 5.9 |
| Python dataset input | `derived.inputs[].dataset` + by/measures | `source.inputs.<name>` is an MQ | 1.2 C |

#### 4.2 Local measures (allowed) and joins (never)

```yaml
model: trading-risk@1
measures:                                  # report-local measures: expressions over the MODEL's measures and fields
  Loss share: { expr: "ratio([Open MTM], [MTM])", format: pct }
  Big trades: { expr: "count(trades, trades.notional > 100000000)" }
```

* A local measure uses the model's measures and fields and the expression language; it **cannot** name a dataset the
  model lacks, declare a relationship, or change security (it was designed as DRS-9142; as built a bad local measure is `DRS-9154`, `MQ_DEFINE`). It travels inside the MQ (`define:`, 5.1), so
  the engine handles it exactly like a model measure, as the reader.
* The designer marks local measures and offers *Move into the model* (a draft change of the model, reviewed like any
  model change) when the author owns the model or can propose to it.

#### 4.3 The cross-owner exception: report links

A report may **link** a second model only when the two models have **different owners** (D2):

```yaml
model: trading-risk@1
also:
  - { model: treasury-liquidity@2 }            # validated: its owner differs from trading-risk's (designed as DRS-9143; this check was not built)
links:
  - fields: [counterparties.id, treasury-liquidity:counterparty.id]
pages:
  - id: main
    visuals:
      - { id: lcr, type: card, model: treasury-liquidity@2, wells: { values: [LCR] } }
```

Today's link semantics are kept (BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 16: a choice on a linked field reaches the other model's visuals;
no link, no reach). A visual still reads **one** model. When both models share an owner the check refuses the link and
the designer offers **Add these datasets to the model** instead.

#### 4.4 Consumer problems

| Code | Meaning |
|---|---|
| `DRS-9140` | a consumer names no model, or two (`model:` and `dataset:`) |
| `DRS-9141` | a board tile, notebook extract or alert names a field or report on another model |
| DRS-9142 | a local measure that names something outside the model, or declares a join (never used: `DRS-9154` was built instead) |
| DRS-9143 | a link between two models with the same owner (use one model); this check was not built |
| `DRS-9144` | a consumer pinned to a model major that no longer exists (retired) |

---

### 5. The model query language v1

The **model query** (MQ) is the only request for data (D5). The UI's visuals, the API, Analyse, alerts, Python inputs,
AI and MCP all send it; the server validates it, compiles it to the engine and answers in one shape. It is **versioned**
(`mq: 1`) and changed only by a reviewed change to this section.

#### 5.1 The object

YAML or JSON, the same keys:

```yaml
mq: 1
model: trading-risk@1               # name or catalogue id, @major or @exact; the implicit model: dataset:<id>
define:                             # optional local measures (4.2)
  Loss share: "ratio([Open MTM], [MTM])"
select:
  fields:   [counterparties.sector, trades.tradeDate.month]   # group by (names or stable ids)
  measures: [MTM, Exposure (EE peak), Loss share]
where:                              # row filters, ANDed
  - { field: trades.currency, op: in, values: [USD, EUR] }
  - { field: trades.tradeDate, op: relative, window: last-5-business-days }
  - { field: counterparties.id, op: top, n: 10, by: Exposure (EE peak), dir: desc }
having:                             # filters on measures, after aggregation
  - { measure: MTM, op: lt, value: 0 }
time:                               # one of
  as-of: 2026-10-09                 #   a business date (default: the model's as-of, 1.5)
  # window: { from: 2026-10-01, to: 2026-10-09 }     # a date range (snapshot), at most drishti.bi.query.max-days
  # window: { last: 1h }                             # history
  # live: true                                       # the current state of live datasets
sort:  [{ by: MTM, desc: true }]
limit: 100
totals: { levels: [[], [counterparties.sector]] }     # true totals per level (or: grand | subtotals)
drill:  { hierarchy: Counterparty, path: [Banking] }  # drill-down position: fields and eq filters follow
shape: grouped                      # grouped (default) | rows (no aggregation: an extract, "show the rows")
params: {}                          # (W6) what-if values; refused if non-empty in W1
options: { layer: auto, explain: false, format: json }   # auto | columns | rollup | direct; json | arrow
```

| Key | Rule |
|---|---|
| `mq` | `1` |
| `model` | resolved through the catalogue as the caller (they must be able to open it); `@1` picks the latest published 1.x |
| `define` | local measures; a name may not shadow a model measure |
| `select.fields` | model fields (`alias.column[.part]`) or stable ids (`ds_…/c_…`); a hierarchy level is a field |
| `select.measures` | model or `define` measures, by name or id; at least one field or measure |
| `where[].op` | today's `eq ne in not-in lt le gt ge between contains starts-with is-null not-null`, plus **`relative`** (`window:` a RelativeDates name: `last-N-days`, `last-N-business-days`, `this-month`, `previous-quarter`, …, computed on the server from the as-of date and the model's calendar) and **`top`** (`n`, `by` a measure, `dir`) |
| `having` | `{ measure, op, value \| values }`: today's "filter whose field is a measure", made explicit |
| `time` | exactly one of `as-of`, `window`, `live`; absent: the model's default (today: the latest business date with rows) |
| `totals` | `grand` (no fields), `subtotals` (every prefix of `fields`), or explicit `levels` (each a subset of `fields`). Only non-additive measures are computed per level; additive ones are summed by the client (BI_GUIDE.md 3.2, now driven by declared additivity) |
| `drill` | a hierarchy of the model and the values clicked; the compiler replaces the hierarchy's field by the next level and adds `eq` filters |
| `shape: rows` | no measures; **one row per row** of the fields' **one** dataset (plus many-to-one attributes), duplicates kept (never `DISTINCT`: the sums of a rows extract equal the measures), with its security; cut at `bi.explore.max_rows`; used by Analyse extracts and *Show the rows* |
| `limit` | 1 to the server maximum; default 500 grouped, 10,000 rows |

#### 5.2 The answer

```json
{ "mq": 1, "model": { "id": "md_trading_risk", "name": "trading-risk", "version": "1.0.0" },
  "columns": [ { "name": "counterparties.sector", "id": "ds_cpty/c_sector", "kind": "field", "type": "string", "masked": false },
               { "name": "MTM", "id": "m_mtm", "kind": "measure", "type": "decimal", "format": "signed0", "additive": true, "masked": false } ],
  "rows": [ ["Agriculture", 20378377.0] ],
  "totals": [ { "by": [], "rows": [[ "…" ]] } ],
  "meta": { "asOf": "2026-10-09", "dates": ["2026-10-09"], "layer": "columns", "rollup": null, "parts": 2,
            "tables": ["trades", "netting_sets", "counterparties"], "masked": [], "warnings": [],
            "cached": false, "millis": 41, "generation": "g-1873", "query": { "…": "the canonical MQ that ran" }, "sql": null } }
```

`meta.generation` is the data generation of every dataset read (the standing-query cursor, 5.8). Arrow keeps today's
`X-Bi-*` headers and adds `X-Bi-Model` and `X-Bi-Generation`.

#### 5.3 Validation (before any data is read)

In order; the first failing step answers, with every problem of that step and its path:

1. **Shape** (`DRS-9150`): unknown key, wrong type, `mq` missing or not 1, two `time` forms.
2. **Model** (`DRS-9151`): not found, not readable by the caller, retired major, a draft the caller may not query.
3. **Names** (`DRS-9152`): a field, measure, hierarchy or id the model lacks; one outside the model's `contract`
   (`DRS-9153`, with the deprecation hint when it is deprecated).
4. **Local measures** (`DRS-9154`): parsed and checked as model measures (today's 9011 conditions).
5. **Filters** (`DRS-9155`): an operator not allowed for the type, a value of the wrong type (never cast into SQL), an
   unknown relative window, `top` without `n`/`by`, a `having` on a non-measure, a drill past the last level.
6. **Reachability** (`DRS-9156`): every field, filter and row rule must be reachable many-to-one from each measure's
   fact along active relationships (today's 9020 rule), so several facts meet only on shared dimensions.
7. **Time** (`DRS-9157`): a window over the limit, `live` with no live dataset, a history window on a model without a
   history, an as-of in the future.
8. **Correctness guards**: different currencies in one sum (`DRS-9158`, 5.6); a semi-additive measure across dates
   (`DRS-9159`: a date window, a measure `additive: { over-time: last }` and no date field: W1 answers the last date's
   value with a warning, W2 refuses unless asked for `last`); a path that repeats rows (`DRS-9160`, 5.5).
9. **Security** (`DRS-9161`; the HTTP answer keeps `403` and also names `DRS-9021`): a filter, sort or `top` on a field
   masked for the caller.
10. **Python** (`DRS-9162`): a Python measure in a grouped MQ (as today: a python visual runs it; R8 later).

A valid MQ can still be refused by the engine for limits (`DRS-9022` to `9025`) and source failures (`9026`, `9027`).
`POST /api/v1/bi/mq/validate` runs steps 1 to 9 without reading data (the designer and AI use it on every edit).

#### 5.4 Compilation to the existing engine

W1 adds a **front end**, not a new engine. The MQ compiler flattens a model into today's in-memory `Dataset` record
(each model dataset becomes a `Table`, each relationship a `Relationship`, measures as they are, security merged per
table) and an MQ into one or more `QueryRequest`s, and calls today's `QueryService` and `SqlCompiler` where it can:

| Step | What happens | Existing code reused |
|---|---|---|
| 1 Resolve | the model at its pinned version; ids to names; `define` merged into the measure map | `Resolver`, `DatasetValidator` (expressions) |
| 2 Flatten | model → engine `Dataset`: a `Table` per dataset (source, `time.mode` → today's `mode`: snapshot → `cached` or `direct`, live → `live`, the history block → `Live.History`), columns, relationships, measures, hierarchies, rollups. Cached per model version | `dataset.Dataset` records |
| 3 Security | per reader: each table's rules = dataset rules **AND** model rules; masks = union; dimension rules propagated to facts as semi-joins (`fact.key IN (SELECT id FROM dim WHERE rule)`) | `SqlCompiler.plan`'s `Reader` (row rules, masks), changed so a rule binds to its own table: the old "join the rule's tables into every query" and its fail-closed refusal go |
| 4 Relative dates and top-N | `relative` windows from the as-of and the calendar; each `top` resolved by its own grouped request in the context of the other filters, becoming an `in` filter (`"\u0000none"` when empty) | `RelativeDates`, `VisualQueries.resolveTop` and its `TopValues` callback |
| 5 Drill | the hierarchy position becomes a field substitution and `eq` filters | `VisualQueries.drill` |
| 6 Per-grain plan | measures split into leaves per fact (`expandMixed`, `grains`, `factParts`); each fact part grouped at the query's fields | `SqlCompiler` grain splitting |
| 7 Meeting of facts | parts full-outer-joined on the shared fields (null equals null), then `having`, sort and limit | `SqlCompiler.compileMulti` |
| 8 Guards | fan-out (5.5), currency (5.6), semi-additive (5.3 step 8) | new, in the MQ compiler, before step 6 |
| 9 Layer | rollup, columns, live or direct, as today | `QueryService.route`, `Layers`, `TableRelations` |
| 10 Totals | one extra request per level for the non-additive measures | `Totals` (its regular-expression guess replaced by declared additivity) |
| 11 Answer | rows, totals, meta (adds `model`, `asOf`, `generation`, stable ids, the canonical MQ) | the result cache key gains the model version and the canonical MQ's hash |

`python` and `model` dataset sources run their inner MQs **as the reader** and stage the result as today's composed and
derived frames are staged (`QueryService.runFramed`, the Transform runner and its per-reader cache); the frame becomes a
table of the flattened dataset, so it can have relationships. The composed `from:` / `join:` machinery
(`dataset.Composition`) is retired in W4: a join is a relationship now.

#### 5.5 Fan-out refusal

A row repeats when a query joins from a fact along a path whose far side is not unique. The model check refuses a
relationship whose `to` is not a grain key (`DRS-9123`), and the planner follows only many-to-one paths from a
measure's fact (today's rule). A field reachable only one-to-many from a measure's fact is `DRS-9160` with a fix (*"group
by a field both reach"*). `shape: rows` with fields of two facts is refused the same way. W2 extends the model check to
every path (bridges, `direction: both` as semi-joins).

#### 5.6 The currency guard

A measure over a column with a **per-row currency** (`unit: { currency: <column> }`, or the measure's `currency:`) may
be summed only within one currency. The compiler adds `count(distinct <currency>)` per group to the same query and
flags (`DRS-9158`) any group that adds rows of several currencies, **unless** the currency column is a field of the
query, a `where` pins it to one value, or the measure is a conversion measure (`convert: { to: USD, rates: <dataset> }`,
W2). In W1 the flag is a **warning** in `meta.warnings` naming the groups (the designer shows it on the visual); in W2 it
refuses. Columns with a fixed currency (`unit: { currency: USD }`) are always addable. In the samples, `Notional`
(trade currency) is the measure the guard catches: *Notional by desk* warns, *Notional by desk and currency* does not.

#### 5.7 Errors in the answer

```json
{ "status": 422, "code": "DRS-9156", "path": "select.fields[0]",
  "detail": "trades.tradeId cannot group Exposure (EE peak): netting_sets does not reach trades many-to-one. Group by a field both reach (counterparties.sector, counterparties.name) or ask MTM alone.",
  "fix": { "replace": "select.fields[0]", "with": ["counterparties.sector", "counterparties.name"] } }
```

Every refusal carries a **`fix`** where one exists (experience rule *feedback where you look*): the designer shows it
as a one-click change beside the control; the AI repair loop sends it back to the model once.

#### 5.8 Standing queries

Every MQ may be **asked** (one answer) or **subscribed** (kept current):

```
POST   /api/v1/bi/mq/subscribe   { "query": { …MQ… }, "throttle": "1s", "deliver": "rows" | "diff" | "value" }
       → { "subscription": "sq_…", "answer": { … }, "cursor": "g-1873" }
GET    /api/v1/bi/mq/stream?ids=sq_a,sq_b          (SSE)
       event: answer  data: { "subscription": "sq_a", "cursor": "g-1874", "rows": [...] | "diff": { "upsert": [...], "delete": [...] }, "totals": … }
       event: status  data: { "subscription": "sq_a", "state": "live | stale | throttled | failed", "lag": "120ms" }
DELETE /api/v1/bi/mq/subscribe/sq_a
```

| Rule | Behaviour |
|---|---|
| Who | a subscription is evaluated **as its owner** every time; the stream carries that reader's answer only |
| When | it re-evaluates when a dataset it reads changes generation (a live batch, a load, a refresh) |
| Incremental, per measure *(W3)* | `sum`, `count` (and `avg` as a sum and a count): **by difference** from the changed rows; `min`, `max`, `distinct`: **recompute the touched groups**; a measure of measures: from its parts; percentile, windows, time intelligence, Python: **recompute on the throttled tick**. The rule of each measure is derived from its expression and shown in the model designer (*updates by difference / per group / on a tick*) |
| Throttle | `throttle` (default `drishti.bi.live.frame`, 250 ms; at least 100 ms): at most one answer per period; the latest state wins, nothing queues (today's rule) |
| Back-pressure | a client that does not read is skipped to the newest answer; over `drishti.bi.mq.max-subscriptions` is `DRS-9181`, over the per-user cap `DRS-9182`; a subscription whose evaluation overruns its throttle three times in a row moves to twice the throttle and says `throttled` |
| Same numbers | a subscribed answer equals the asked answer at the same cursor (W3's acceptance compares them after random update streams) |
| Time | `live` follows changes; `as-of` a past date does not change (subscribing to one is `DRS-9183`); `window: { last: 1h }` slides |

In W1 the **endpoints are built** and answer by full recompute on each generation change (today's "tick, then the
client asks again", moved to the server, which also removes one round trip per visual). The incremental rules are W3.
Alerts with `trigger: live` become standing queries with `deliver: value`; boards and live report visuals subscribe one
MQ per visual over one stream.

#### 5.9 One language for every caller

| Caller | How it uses MQ |
|---|---|
| Report viewer and designer | the server turns a visual and the page state into an MQ (`VisualQueries` becomes `VisualMq`, keeping its order of authority); `POST /reports/{id}/query` keeps its body and returns the MQ it ran under `query` |
| Analyse | Slice and extracts send MQs (`shape: rows` for extracts); a notebook stores them; the console's `explore_routes.extract_for` posts an MQ instead of a dataset query |
| Alerts | `source.type: model` stores an MQ; `BiValueReader` runs it; `visual` sources run the visual's MQ |
| API | `POST /api/v1/bi/mq` (a full MQ), `POST /api/v1/bi/models/{id}/query` (an MQ without `model`), `POST /api/v1/bi/mq/validate`, `GET /api/v1/bi/models/{id}/describe` (fields, measures, hierarchies, types, formats, meanings, contract, `ai:`, as the caller: masked fields marked, hidden ones absent). `POST /datasets/{id}/query` with today's body is accepted until W4 and translated to an MQ on the implicit model |
| AI *(W5; W1 moves ask to MQ)* | the model is shown `describe` and writes an MQ; the server validates it (5.3), runs it as the user, and on a refusal sends back the code and the `fix` once; numbers always come from the engine |
| MCP *(W5)* | tools `describe_model(model)`, `query_model(model, mq)`, `explain_change(model, measure, from, to)`, all as the token's user |
| CLI | `drishti.py bi mq file.yaml [--model <model>] [--json]` (as built; the design named it bi query, with CSV output) |

---

### 6. Worked examples

All over the shipped samples as converted in section 8. The numbers quoted are real answers already documented in
BI_GUIDE.md, BI_GUIDE.md (section 29, AI in BI) and BI_GUIDE.md; the converted samples must give the same (10.3), except where 8.5 says otherwise.

**1. A card: trades in the book** (*trades-overview*, visual `trades`; the model `trades-basic`).

```yaml
{ mq: 1, model: trades-basic@1, select: { measures: [Trades] } }
```

One fact part, `count(*)` over `trade`; one row. Additive, so no totals request.

**2. MTM by desk with its rank, top 5** (BI_GUIDE.md 4.3).

```yaml
mq: 1
model: trading-risk@1
select: { fields: [trades.desk], measures: [MTM, MTM rank] }
sort: [{ by: MTM, desc: true }]
limit: 5
```

Answered from the rollup `by_desk_currency` (one fact, `desk` in its `by`, `MTM` a stored component, `rank` the one
window a rollup holds): `DESK-RATES 161,766,771 · 1`, `DESK-COMM 16,954,390 · 2`, `DESK-STRUCT 15,784,130 · 3` …

**3. Notional by trade date, the last 30 days** (a relative-date slicer).

```yaml
mq: 1
model: trades-basic@1
select: { fields: [trade.tradeDate], measures: [Notional] }
where: [{ field: trade.tradeDate, op: relative, window: last-30-days }]
sort: [{ by: trade.tradeDate }]
time: { window: { from: 2026-09-09, to: 2026-10-08 } }
```

Step 4 turns the window into `trade.tradeDate >= 2026-09-09 and < 2026-10-09` (bound values). The currency guard warns
(`Notional` adds trade currencies); the designer offers *Split by currency* or *Filter one currency*.

**4. A matrix with true totals** (*trades-overview* page `products`: desk by currency).

```yaml
mq: 1
model: trades-basic@1
select: { fields: [trade.desk, trade.currency], measures: [Trades, MTM, Average MTM per trade] }
totals: subtotals
```

`Trades` and `MTM` are additive (the client sums them); `Average MTM per trade` is a measure of measures, so the
server runs one request per level (`[]`, `[trade.desk]`) for it alone.

**5. Drill down a hierarchy** (*trades-overview* `by-desk`, a click on DESK-FX).

```yaml
mq: 1
model: trades-basic@1
select: { fields: [trade.desk], measures: [MTM] }
drill: { hierarchy: Desk and book, path: [DESK-FX] }
```

Compiled as `fields: [trade.book]` with `trade.desk eq DESK-FX`.

**6. Two facts in one visual through a shared dimension** (BI_GUIDE.md 3.1).

```yaml
mq: 1
model: trading-risk@1
select: { fields: [counterparties.sector], measures: [MTM, Trades, Exposure (EE peak), Netting sets] }
sort: [{ by: counterparties.sector }]
limit: 4
```

Two fact parts (`trades`, `netting_sets`), each grouped by the sector through its own many-to-one path, full-outer-joined
on it: `Agriculture 20,378,377 · 35 · 59,019,457 · 4`, `Airlines 15,984,208 · 33 · 73,868,165 · 4`, `Asset management
70,776,928 · 153 · 289,738,661 · 15`, `Banking 23,279,594 · 94 · 240,271,956 · 12`. As the desk-head `hana` the trades
part is cut to her desks **before** grouping and the exposure part answers in full (today: refused; see 8.5).

**7. The ten largest counterparties, ranked by an unmasked key** (*counterparty-risk* `by-counterparty`).

```yaml
mq: 1
model: trading-risk@1
select: { fields: [counterparties.name], measures: [Exposure (EE peak), Exposure (PFE peak)] }
where: [{ field: counterparties.id, op: top, n: 10, by: Exposure (EE peak), dir: desc }]
```

Step 4 asks `{fields: [counterparties.id], measures: [Exposure (EE peak)], sort desc, limit 10}` as the reader, then
filters `counterparties.id in (…)`. For `ann` (no raw power) the name reads `•••`; ranking by the name instead is
`DRS-9161` with `fix: { by: counterparties.id }`.

**8. P&L against exposure in one model** (replaces the composed `desk-pnl-vs-exposure` and its two-dataset report).

```yaml
mq: 1
model: trading-risk@1
select: { fields: [counterparties.id, counterparties.name], measures: ["P&L 1d", Trades, Exposure (EE peak), P&L per exposure] }
having: [{ measure: Trades, op: gt, value: 0 }]
```

`P&L per exposure` is `[P&L 1d] / [Exposure (EE peak)]`, each side at its own grain, then divided. As `rita`: 18
counterparties, P&L 2,671,113 and EE 1,447,544,943 in total (BI_GUIDE.md 11.3). The `having` keeps the old left join's
meaning (only counterparties with trades the reader sees), so `hana` gets 17 counterparties, P&L 1,060,102 and EE
1,356,300,321, as today. Without it she would see exposure for all 18 (8.5).

**9. A live desk, subscribed** (*live-trading-desk* `by-desk`).

```yaml
mq: 1
model: live-trading@1
select: { fields: [trade.desk], measures: [Trades, Notional, MTM, Worst trade] }
time: { live: true }
```

Subscribed with `throttle: 250ms`. W1 recomputes it on each generation from the live rollup `byDeskBook` (layer `live`;
the 90 trades of `DESK-COMM` from kept groups, as documented). From W3 `Trades`, `Notional`, `MTM` move by difference and
`Worst trade` per touched group.

**10. The last hour, and a measure as of a past date** (*live-trading-desk* `last-hour`; *counterparty-risk* `mtm-ytd`).

```yaml
- { mq: 1, model: live-trading@1, select: { fields: [history.ts], measures: [MTM history, Losses history] }, time: { window: { last: 1h } }, sort: [{ by: history.ts, desc: true }], limit: 5 }
- { mq: 1, model: trading-risk@1, select: { measures: [MTM ytd] }, time: { as-of: 2026-09-30 } }
```

The first reads the live dataset's sampled history (`history.ts`, one column per sampled measure; BI_GUIDE.md 7.1); a
reader with a row rule on `trade` is refused as today (`DRS-9156`: `history` does not reach `trade`). The second binds
`ytd` to 2026-01-01 … 2026-09-30 and reads the snapshot of 30 September.

**A refusal: fan-out.** `{ mq: 1, model: trading-risk@1, select: { fields: [trades.tradeId], measures: [Exposure (EE peak)] } }`
is `422 DRS-9156` with the message and `fix` of 5.7 (today: `DRS-9020`, no fix).

---

### 7. Pack-generated datasets (D3)

#### 7.1 What a pack kind gives, automatically

For **every kind of every enabled pack** that a connector serves, the server keeps one generated dataset. Nothing is
written to `config/bi`: the datasets are derived when packs load (and reload), held in the catalogue as read-only
items, and regenerated when the pack, its connector layout or its vocabulary changes.

| Part of the dataset | Comes from (what exists today) |
|---|---|
| name, id | `pack.<pack>.<kind>`, `ds_pack_<pack>_<kind>` |
| title, description | the kind's mnemonic label (`mnemonics.<M>.label`) and the pack's description, else the kind's name in words |
| source | `{ pack, kind }`; the connector is the pack's route for the kind (`Pack.routes()`), else whichever serves it |
| columns | the connector's **promoted columns** for the kind (`layout.<kind>.columns`, `SourcePlugin.columnar`), else the paths of the pack's sample documents; types by sampling as `PackModels.typeOf` does today. Nested paths get camel-case names (`counterparty.id` → `counterpartyId`, the converter's and today's wizard's rule) |
| roles | `id`-typed and `references` columns are `dimension`; decimals are `amount`; dates `date`; the query-form field types (`amount`, `enum`, `id`, `date`) refine it where the pack has a query form |
| `references` | `graph.fields` of the pack and of every loaded pack (`ReferenceCatalog.kindOfField`): `desk → desk`, `counterparty.id → counterparty` |
| `meaning`, `unit`, sign | the pack's `about.yaml` vocabulary (`term`, `means`, `unit`, `sign`) for a column whose name or path is a vocabulary entry; `unit: trade currency` becomes `unit: { currency: currency }` when the kind has a `currency` column |
| `format` | `graph.impact.formats` and the pack's `formats.yaml` |
| grain | one row per entity id per business date (`keys: [id]`, plus a declared business key such as `tradeId` when its values equal the id on the sample) |
| time | `live` when the kind's connector can push (`subscribe`), else `snapshot`; `refresh: { on-load: true }` |
| security | the kind's entitlements (who may open the pack and kind); masks from `drishti.security.redact` and any field the pack marks sensitive |
| arrays | one extra dataset per array field the pack lists under a new optional bi arrays key in the pack (not built; such datasets are written by hand with `each:`) (`pack.trading.trade.legs`, `pack.trading.trade.schedule`), each with `each:` and a `references: trade` back to the parent |
| tests | the grain test |

A pack may refine the result with an **optional** `bi:` block in `pack.yaml` (no pack needs one):

```yaml
bi:
  datasets:
    trade:
      grain: { keys: [tradeId] }
      columns: { mtm: { unit: { currency: USD } }, notional: { additive: true } }
      exclude: [internalNote]
      time: snapshot                 # force a mode
  arrays: { trade: [legs, schedule] }
  skip: [desk-pnl]                   # kinds that should not get a dataset
```

#### 7.2 Naming

* `pack.<pack>.<kind>` and `pack.<pack>.<kind>.<array>`; the pack's code is not used (names must read well in YAML).
* Aliases in models default to the kind (`trade`, `netting_set`, `counterparty`); the designer pluralises on request.
* A user dataset may not start with `pack.` (`DRS-9100`).

#### 7.3 Versioning with the pack

* The dataset's `version` **is the pack's version** (`trading 1.0.0` → `pack.trading.trade@1.0.0`).
* On a pack upgrade the server compares the old and new generated datasets with the contract rules of 2.8: a removed or
  retyped column in a pack **minor** or **patch** is a pack defect; the pack loader warns on Health and in Admin → Packs,
  and impact analysis lists every model and consumer that uses the column, before the upgrade is activated (Admin →
  Packs shows *This upgrade breaks 2 models*).
* Models pin `@^<major>`; a pack major moves no model until it is edited.

#### 7.4 Governance

* **Owner** `pack:<pack>`; **endorsement** `certified` (by the pack), revoked if the pack is unsigned or edited locally.
* **Read-only** in every UI (`DRS-9107` for an edit through the API); customise by `extends` (1.9).
* Lineage shows a generated dataset as the root under the pack and its connector; Admin → Packs lists the datasets of
  each pack and the models that use them.
* Disabling a pack or a kind hides its datasets; models that use them show *dataset unavailable* and their queries
  answer `DRS-9151` naming the pack.
* A pack's own BI content (`PackBi`: reports and datasets a pack ships) is imported as today; its datasets become
  models over the pack's generated datasets when the pack is rebuilt with the converter (8.3).

---

### 8. Migration

#### 8.1 Concept map

| Today (2.1) | From W1/W4 | How |
|---|---|---|
| dataset YAML with `tables:` (the model of today) | a **model** + one **dataset** per table (pack-generated when the table is a plain pack kind, else a user dataset) | D1: "today's dataset YAML becomes a data model" |
| a table `source: { connector, kind }` with the columns of the pack | `pack.<pack>.<kind>` (generated); a table with extra calculated columns or a narrower column list → a user dataset `extends` it | 7 |
| a table over a connector with a column set the pack does not promote | a user dataset `source: { pack, kind, connector }` with its own columns | 1.2 A |
| `each:` tables | `pack.<pack>.<kind>.<array>` datasets (the pack lists the array) or user datasets with `each` | 7.1 |
| `mode: cached / direct / live` | `time.mode: snapshot` (`storage: direct`) / `live` | 1.5 |
| `live.history` | `time.history` on the live dataset | 1.5 |
| `measures`, `hierarchies`, `rollups`, `tests` | in the model, unchanged | 2 |
| `relationships` | in the model, with `suggested:` provenance when a pack reference matches | 2.3 |
| `security.rows` / `masked` | model `security.rows.<alias>` (tighter than the pack dataset); masks of the pack's redact list stay in the dataset, others go to the model | 2.6 |
| `refresh:` (dataset-wide or per table) | per dataset `refresh:`; a model has none (it reads its datasets' layers) | 1.5 |
| composed dataset (`from:` + `join:`) | the sources' datasets **in one model** with relationships to shared dimensions; the composed measures become model measures; a left join's "only rows of the left source" becomes a `having` in the consumers that need it | 6 example 8 |
| derived dataset (`derived:` + Python Transform) | a **Python dataset** (`source.python`, inputs are MQs) used by a model (implicit or explicit) | 1.2 C |
| report `dataset:` | `model:` | 4.1 |
| visual `dataset:` + `links:` | removed when both datasets end in one model; kept as 4.3 only across owners | 4.3 |
| board (reports' tiles) | `model:` on the board | 4.1 |
| notebook `dataset:` + extracts | `model:` + MQ extracts | 4.1 |
| alert `type: dataset` | `type: model` with an MQ | 4.1 |
| `POST /datasets/{id}/query` | `POST /models/{id}/query` (MQ); the old route translates until W4 | 5.9 |
| Python measure (`python:`, `inputs`, `by`) | unchanged, in the model | 2.4 |
| Python item `inputs: [datasets]` | `inputs: [<name>]` naming the Python dataset's MQ inputs | 1.2 C |

#### 8.2 Every shipped sample

*As built (2.2 wave A): the samples are converted by the converter itself (8.3.1) and committed under `config/bi/models` and `config/bi/datasets`.
Where it differs from this plan: the datasets are user datasets `<old>-<table>` (not pack datasets), the derived sample is `desk-pnl-vs-exposure-py-result`
plus the model `desk-pnl-vs-exposure-py`, the composed sample's `Collateral` measure joins `trading-risk` too, the two live reports stay on their live
datasets until W3, the boards are unchanged (they name reports), and the notebook and the Transform's `inputs:` are manual steps for the console.*

| File (config/bi) | Becomes |
|---|---|
| `datasets/trades-basic` | model `trades-basic` over `pack.trading.trade` (alias `trade`); the calculated column `mtmInMillions` moves to a user dataset `trades-basic-trade` (`extends: pack.trading.trade@^1`); measures (with the Python measure), hierarchy as written |
| `datasets/trading-risk` | model `trading-risk` (2.10) over five pack datasets; the desk-head rule becomes a model rule on `trades`; masks `counterparties.name`, `counterparties.rating` in the model; `counterpartyName` masked by the pack dataset; rollup kept; `trades.nettingSet → netting_sets.id` kept but **inactive** (two facts meet on `counterparties`) |
| `datasets/trading-cashflows` | model `trading-cashflows` over `pack.trading.trade`, `pack.trading.trade.legs`, `pack.trading.trade.schedule` (aliases `trades`, `legs`, `cashflows`; the column `index_name` stays a renamed path in a user dataset because `index` is reserved); relationships as written |
| `datasets/live-trading` | dataset `live-trading-trade` (`source: { pack: trading, kind: trade }`, `time: { mode: live, live: { rows: 5000 }, history: { every: 10s, keep: 360, measures: [MTM, Losses, Trades] } }`, masks `trader`); model `live-trading` with the measures, hierarchy and the live rollup |
| `datasets/live-exposure` | dataset `live-exposure-netting` (live) + model `live-exposure` |
| `datasets/counterparty-exposure` | model `counterparty-exposure` over `pack.counterparty-risk.netting-set` and `pack.core.counterparty` (kept as a separate model with another owner, so the cross-owner link of 4.3 has a sample) |
| `datasets/desk-pnl-vs-exposure` (composed) | **removed**; its measure `P&L per exposure` moves into `trading-risk` (2.10). Its question is example 8 |
| `datasets/desk-pnl-vs-exposure-py` (derived) | Python dataset `pnl-vs-exposure-py` (1.10) and its implicit model; the Transform's `inputs:` become the dataset's MQ inputs |
| `datasets/world-economics`, `region-economics` | models over `pack.world-economy.economy` and `pack.economics.region-economy` (aliases `economies`, `regions`); `Average growth` and the other averages are marked `additive: false` |
| `reports/trades-overview`, `cashflow-ladder`, `python-desk-risk`, `world-economics`, `region-economics`, `live-trading-desk`, `live-exposure`, `counterparty-risk` | `dataset:` → `model:` of the same name at `@1`; fields unchanged because aliases keep today's table names |
| `reports/pnl-vs-exposure` | `model: trading-risk@1`; `joined.x` fields → `counterparties.x`; `Desk P&L` → `P&L 1d` (title kept); the two visuals with `dataset: counterparty-exposure` read the same model; `links:` removed; every visual gets `having: Trades > 0` as a page filter on a measure, to keep the left-join meaning (example 8) |
| `boards/desk-wall` | one board over two models (`live-trading`, `live-exposure`): converted with a new model `live-desk` containing both live datasets as facts (an island pair, `DRS-9121` warning) and the two reports rebound to it (Open question 3) |
| `boards/python-wall` | `model: trades-basic@1` (its report's) |
| `notebooks/pnl-vs-exposure-pandas` | `model: trading-risk@1`; extracts `{ name, query: { shape: rows, select: { fields: [...] } } }`: the trades fields and the netting-set fields as two row extracts of one model |
| `python/pnl-vs-exposure` | `inputs: [pnl, exposure]` (the Python dataset's input names); the function's renames of `Desk P&L` follow the MQ's column names (`P&L 1d`) |
| `python/desk-pnl-volatility`, `pnl-histogram`, `pnl-vega` | unchanged |
| tutorials 10 to 18 and their data | rewritten in W4 against the converted samples (UI first, then CLI and YAML) |

#### 8.3 The converter

`drishti.py bi migrate [--dir config/bi] [--out DIR] [--dry-run] [--items workspace|all]` and
`POST /api/v1/bi/migrate/preview` (bi-admin). Behaviour:

1. **Reads** every `rachana: 1` item (shipped files and stored items, published and draft), in dependency order
   (composed and derived after their sources).
2. **Plans** per item: which generated datasets match each table (same pack kind and every declared column is a
   generated column with the same path and type → use the pack dataset; otherwise write a user dataset, `extends` when
   the difference is only additions); the model; the consumers' rewrites. Names: the model keeps the old dataset's
   name (so `trading-risk` stays `trading-risk`), user datasets are `<old>-<table>`.
3. **Checks** every output with the validators of sections 1, 2 and 4 and runs the **equivalence probe** (8.6) when a
   server is reachable.
4. **Writes** a plan report (`migrate-report.md` in `--out`, and the same as JSON from the API): every item, what it
   became, every name change, every behaviour difference (8.5) and every manual step. `--dry-run` stops here.
5. **Applies**: shipped files are written to `--out` (default: in place, the old files removed; Git shows the diff);
   stored items get a new saved state with origin `migrate`, published items are republished as the next version by
   the bi-admin running it (audited `bi-migrated`), drafts stay drafts. Stable ids are generated and written.
6. **Idempotent**: a `rachana: 2` item is skipped; a second run changes nothing. Comments in old YAML files are kept
   where the key survives (the converter edits a YAML tree that keeps comments), and dropped with the key otherwise.
7. Refuses (`DRS-9190`) an item whose old form is invalid, and (`DRS-9191`) a composed join it cannot express as
   relationships (a join on non-key columns of both sides): the report lists it with the Python-dataset alternative.

##### 8.3.1 As built (2.2 wave A, task 6)

The converter is `com.ash.drishti.server.bi.migrate.ModelConverter` (pure; `DatasetSplitter`, `Folding`, `ReportRewrite`), reached three ways:

| Way | What it converts | Security of the restricted roles (below) |
|---|---|---|
| `drishti.py bi migrate --models DIR [--dry-run] [--out D] [--keep-old] [--report F]` (`POST /api/v1/bi/migrate/models/convert`, `bi`) | a BI folder's files; written in place (old dataset files removed unless `--keep-old`) or under `--out` | `NOTE` |
| `drishti.py bi migrate --models [--dry-run]` (`POST /api/v1/bi/migrate/models`, `bi-admin`) | the server's stored items | `CLOSED` |
| the start-up run (`drishti.bi.migrate.on-start`, after the expression migration) | the server's stored items, as `system` | `CLOSED` |

Decisions taken while building it, against the plan above:

* **Datasets are user datasets, never a pack dataset.** Every table becomes `<old>-<table>` (`_` written `-`) with the table's own `source`
  (`connector`, `kind`, `each`), columns (`path`, `type`, `format`, `description`, calculated `expr` with bare columns, `key: true` as `role: key`),
  time (`cached` is the default snapshot, `direct` is `storage: direct`, `live` with `live.rows/age` and `history`), refresh and masks. Step 2's "use
  the pack dataset when every column matches" would give a model every column of the pack dataset, including columns the old file never showed; the
  converter never widens what a model shows, so a site that wants the pack datasets rebinds by hand (`dataset: pack.trading.trade`).
* **Security moves to the datasets.** A rule naming one table is that dataset's rule (its columns bare, `desk in user.desks`); a `True` rule is given to
  every dataset, so a role the old file did not name still sees nothing; a rule naming two tables is refused (`DRS-9190`). A role restricted on one
  table and named nowhere else (the desk-head on `netting_sets`) asked a question 2.1 **refused**. `NOTE` (the shipped samples, section 8.2) gives it
  every row of the other datasets and writes `security.may-see-all` (the allow-listed change of 8.5); `CLOSED` (stored items, converted unattended)
  gives it `{ role: desk-head, where: "False" }` there, so it keeps seeing nothing, and the report says how to open it. Nothing is widened silently.
* **Two facts joined by a relationship** (`trades.nettingSet -> netting_sets.id`): kept **inactive**, so the facts meet on their shared dimensions.
* **Composed** datasets fold into a *host*: a source whose model already reads every table the other sources use (same connector, kind, `each`, and
  at least their columns with the same paths and types). The composed dataset's own measures (not the engine's generated frame measures) and any source
  measure the host lacks move into the host (renamed `<name> (<source>)` when the name means something else there); consumers bind to the host with the
  frame's names renamed (`joined.name` to `counterparties.name`, `Desk P&L` to `P&L 1d`); a visual that read another source of the composed dataset reads
  the host too, so the report's `links:` go. A `left` (or `inner`) join adds the report filter `{ field: <left count measure>, op: gt, value: 0 }`.
  No host: `DRS-9191`.
* **Derived** datasets become a Python dataset `<old>-result` (`source.python`, each input a model query `{ model, select }` on the converted input's
  model) and a model of the old name over it under the alias `result`, so its measures and `result.*` fields keep their meaning. The Transform now
  receives each column named by the last part of its field or measure name (`counterparties.id` is `id`, `P&L 1d`), not the `as` names: a manual step (the shipped `pnl-vs-exposure` accepts both).
* **Reports** are rewritten in their text (comments kept): `rachana: 2`, `dataset:` to `model: <name>@<major>`, renames in wells, filters, sorts,
  formats and bookmarks (never in titles or texts). A report on a dataset with a **live** table stays on the dataset (manual step) until a report on a
  model follows live data (standing queries, W3), and that dataset's file is kept. **Boards** name reports and are unchanged; **notebooks** and Python
  Transforms that name datasets are manual steps until the console reads them from models.
* **Stored items**: the dataset item **becomes** the model item (same id, so references by id hold; its 2.1 text is a saved state of its history); each
  new dataset is an item of the same workspace, owner and audience, published when the old dataset was; reports are rebound through the same rewrite as
  the expression migration (text before kept, a published report published again). A dataset whose draft is ahead of its published version, a name already
  taken, or a refused item is never changed and is listed (Admin, BI settings; `GET /bi/migrate/problems`, step `models`).
* **Idempotent**: a `rachana: 2` item is skipped; a converted folder converted again writes the same text.

Captured (`drishti.py bi migrate --models config/bi --dry-run`, the shipped samples; the report's table, abridged):

```
datasets/desk-pnl-vs-exposure.dataset.yaml: removed -> folded into the model trading-risk@1
datasets/counterparty-exposure.dataset.yaml: converted -> model counterparty-exposure@1 over [counterparty-exposure-netting-sets, counterparty-exposure-counterparties]
    writes datasets/counterparty-exposure-netting-sets.dataset.yaml
    writes datasets/counterparty-exposure-counterparties.dataset.yaml
    writes models/counterparty-exposure.model.yaml
...
reports/live-trading-desk.report.yaml: manual
    manual: the report reads the live dataset live-trading (now also the model live-trading@1): it stays on the dataset until a report on a model follows live data (standing queries, wave W3); then bind it with model: live-trading@1
reports/pnl-vs-exposure.report.yaml: converted -> trading-risk@1
...
26 file(s) read, 16 converted, 0 refused; would write 32 file(s)
```

**Equivalence (8.6) as built:** `ConvertedSamplesTest` converts the 2.1 samples (kept under `drishti-server/src/test/resources/bi/samples-2.1`) and
asks every converted model what the 2.1 dataset was asked (each measure alone and by each hierarchy's first level, sorted, as of a date, with a measure
filter and a field filter) as `rita`, `hana` (desk-head) and `ann` (no raw power: masked): row for row equal, with one allow-list entry (8.5: a
desk-head's question about another fact is answered as risk sees it instead of refused). The composed sample is compared with the left join of its two
sources' 2.1 answers, asked of the host with `Trades > 0`; the derived sample is a Python dataset, which only the console runs. The test also asserts that
the files under `config/bi` are exactly the converter's output.

#### 8.4 The W1 compatibility reader

*Removed in 2.2 (wave A, task 6), with `dataset/Composition`, the composed and derived datasets' code in `FrameService` and the phase 0 proof of
concept: a `rachana: 1` dataset is no longer read as a model (`DRS-9151` names the converter), a `from:` or `derived:` dataset parses with `DRS-9190` (its text
is still read, for the converter), and `POST /datasets/{id}/query` answers `410` with the equivalent model query. A 2.1 dataset with a live table keeps
serving its live reports through the engine until W3. What follows is the design as it was.*

So that W1 can be accepted before W4 migrates the files, a `rachana: 1` dataset with `tables:` is **read as a model**
whose datasets are inline (one anonymous dataset per table, under the table's name) and a composed or derived
`rachana: 1` dataset as an implicit model of one dataset. Reports, alerts and Analyse then ask it through MQ. The reader
is deleted in W4 with the old shapes.

**Security in the compatibility reader is not split** (decided with the safeguard of 2.6): the old file's rules keep their
version-1 meaning (joined into every query, refusing what they cannot reach), and the query runs through the same engine
path as the old dataset query, so an old file answers **exactly** as before through either path (the equivalence test
asserts it for every shipped sample, as `rita`, `hana` and `ann`). Binding the rules per dataset changes answers (8.5) and
needs the safeguard's review of every open dataset, so it happens when the converter (M1/W4) writes the model, never
silently when an old file is read.

#### 8.5 What breaks (on purpose)

| Change | Who notices | Mitigation |
|---|---|---|
| Security binds to the dataset: a row rule on one fact no longer refuses queries about another fact (desk-head and exposure) | a desk-head sees exposure for every counterparty where today the question was refused (`trading-risk`) or limited by a left join (composed sample) | the converter adds `having: Trades > 0` where the composed sample relied on the join; a model that must hide another fact adds its own rule on it |
| Composed datasets are gone; `joined.*` fields are renamed | reports, alerts, bookmarks over `desk-pnl-vs-exposure` | the converter rewrites them; stable ids prevent it next time |
| `POST /datasets/{id}/query` body | API scripts | translated until W4, then `410` with the MQ equivalent in the message |
| visual `dataset:` and same-owner `links:` | reports over two datasets | the converter merges into one model |
| dataset-level measures | nobody downstream (they move to the model of the same name) | |
| a user dataset name starting `pack.` | none shipped | rename |
| `rachana: 1` files after W4 | sites with their own YAML | the converter, run once |
| Totals of measures declared `additive: false` that the regular expression considered additive (and the reverse) | matrices | they become right; the equivalence test lists each changed cell |

#### 8.6 Equivalence

For every sample report visual, every dataset test and the sample alerts and extracts, the old path (2.1
`QueryRequest` through the old dataset) and the new path (MQ through the converted model) are asked as `rita` (risk),
`hana` (desk-head), `ann` (analyst without raw) on the 10K-trade demo lake; rows are compared exactly for counts and
strings and within 1e-9 relative for decimals; the differences of 8.5 are an explicit allow-list in the test, each with
its reason.

---

### 9. UX flows

Per the experience rules (BI_DESIGN.md section 14.6): no dead ends, no YAML typing, direct manipulation,
never lose work, feedback beside the control, right defaults, phone width and keyboard. Wireframes are text; `[ ]` is a
button, `( )` a choice, `▸` a next step offered.

#### 9.1 Start: what do you want to see?

```
BI home  ─────────────────────────────────────────────────────────────
  Start from your data
  ┌ Trades (Trading pack)            certified · live        [Use] ┐
  │ Netting sets (Counterparty risk) certified · snapshot    [Use] │
  │ Counterparties (Banking core)    certified · snapshot    [Use] │
  └ … search all datasets ________                               ┘
  Or from a model:  Trading and counterparty risk · promoted   [Use]
  ▸ New dataset (connector query or Python)    ▸ New model
```

*Use* on a dataset opens a new report on its implicit model (9.4); on a model, the report designer on it. Pack datasets
are listed first because they exist without anyone building them (D3).

#### 9.2 The dataset wizard (mostly automatic)

Only for what a pack does not already give: a connector query, a Python table, another model's output, or a narrowed
pack dataset.

```
New dataset ─ Step 1 of 3: Where does the table come from?
  ( ) A pack kind, narrowed or with extra columns   → choose: [Trades ▾]
  ( ) A query in a SQL connector                     → choose: [risk-warehouse ▾]   (only SQL connectors listed; others greyed with the reason)
  ( ) A Python Transform                             → choose an item [▾] or [Write one]  (opens the Python editor, returns here)
  ( ) The answer of a model                          → choose a model [▾], then pick fields and measures (the report field picker)
                                                                       [Next ▸]
Step 2 of 3: Check the table   (filled in for you: run on a sample as you, masks applied)
  Column        Type      Role         Meaning (from the pack)     Unit
  desk_id       string    dimension ▾  Desk ▾  (references desk)   —
  limit_amount  decimal   amount ▾     —                           [currency ▾]
  Grain: one row per  [desk_id ✕] [limit_type ✕]   ✔ unique on 2026-10-09 (412 rows)
  Time: (•) snapshot ( ) history      Refresh: [business days ▾] at [06:30]
  ⚠ limit_amount looks semi-additive (a balance): [Treat as a balance] [It adds over time]
                                                                       [◂ Back] [Next ▸]
Step 3 of 3: Save
  Name [desk-limits]  Title [Desk limits]  Workspace [Risk control ▾]
  Who may see which rows: [+ Add a row rule]   Masked columns: [+]
  ▸ The YAML (for review)          [Save draft]  [Save and publish]
  After saving: ▸ Use it in a new model   ▸ Add it to model "Trading and counterparty risk"   ▸ Start a report on it
```

Every step keeps its draft in the session (refresh, Back) and asks before leaving. The grain is checked as soon as keys
are chosen; a duplicate shows the offending key values beside the chips with *Add a key* or *Make it a Python dataset to
aggregate*.

#### 9.3 The model designer (the Model view becomes the editor)

```
Model: Trading and counterparty risk  v1.0.0 draft   [Check] [Save] [Propose ▾]   ⤺ ⤻
┌ Datasets ──────┐ ┌ Canvas ───────────────────────────────────────────────┐ ┌ Inspector ──────────────┐
│ search ____    │ │   [desks]◂─────┐                                       │ │ Relationship            │
│ Trades     +   │ │   [books]◂───┐ │                                       │ │ trades.counterpartyId → │
│ Netting sets + │ │          [ trades (fact) ]──┐                          │ │ counterparties.id       │
│ Counterparties+│ │                             ▾                          │ │ many-to-one · active    │
│ Desks       +  │ │          [counterparties (dimension)]                  │ │ from the pack reference │
│ …              │ │                             ▴                          │ │ 98% of values match     │
│ [+ New dataset]│ │          [ netting sets (fact) ]─┘                     │ │ [Make inactive] [Remove]│
└────────────────┘ │  Suggested: trades.nettingSet → netting sets  (pack)   │ └─────────────────────────┘
                   │             [Add as inactive] [Dismiss]                │
                   └────────────────────────────────────────────────────────┘
 Tabs: Canvas · Measures · Hierarchies · Security · Tests · Contract · AI · YAML
```

* **Datasets**: drag one onto the canvas (or `+`); its role is inferred (fact or dimension) and editable on the box.
  Adding a dataset immediately proposes its relationships from **pack references** (`references:`), then from values
  (match rate) and names; each is one click (*Add*), and an ambiguous second path is proposed inactive with the reason.
* **Relationships**: drag a column onto another, as today; the inspector shows cardinality, direction (single in W1;
  *both* greyed: "arrives in W2"), active, the match rate and the provenance.
* **Measures tab**: a list with *New measure* → pick a column → the default aggregation, format and additivity come
  from the dataset (a balance column defaults to *last over time*); a formula box with field completion and the
  server's check beside it; *Preview* shows the measure by a suggested dimension, as you, in place. Each measure shows
  how it will update live (*by difference / per group / on a tick*) and the facts it reads.
* **Security tab**: the datasets' rules read-only (with *Open the dataset*), then *Add a rule for this model*: role,
  then a condition built from a field picker and `user.` attributes (no typing), and **Preview as** a person or role
  showing the rows and masks they get. The tab says "rules here can only remove rows".
* **Tests tab**: expected values and golden questions; *Add from a preview* turns any preview figure into a test.
* **Contract tab**: what consumers use (from impact), the version the next publish needs (*this removes MTM median:
  2.0.0*), deprecations.
* Check runs on every edit (debounced) and pins problems on the canvas box or the measure row they belong to, with
  the `fix` as a button. Undo and redo cover every action; the YAML tab is two-way, for experts.

#### 9.4 A report from a dataset (implicit model) or from a model

```
New report ─ on: (•) Trades (implicit model)  ( ) a model [▾]
Designer: Fields pane header: "Implicit model of Trades  [Make it a model]"
  ▾ trade      desk · book · currency · tradeDate (▸ year, month) · Sum of notional · Sum of mtm · Count of trade
  ▾ desk       name · head                      (came along from the pack reference)
  ▸ Report measures [+]                         (local measures: expressions over the model)
Drag "Sum of mtm" onto the canvas → a card. Drag "desk.name" onto it → a bar of MTM by desk (right default).
⚠ beside a visual: "Notional adds several currencies"  [Split by currency] [Filter one currency]
Trying to add Netting sets: "This report reads one model. [Make it a model and add Netting sets]"  (never a dead end)
```

Every visual's query is an MQ the author can see (*Query* in the visual's menu: the MQ, the answer's meta, *Copy as
YAML*, *Open in Analyse*), which teaches the language without requiring it.

#### 9.5 Promote, move and consume

* *Make it a model* (implicit → explicit), *Move into the model* (local measure → model), *Add these datasets to the
  model* (instead of a same-owner link): each is one undoable action that produces a draft for review.
* Alert me, Subscribe, Analyse this, Export: on any visual, carrying its MQ; nothing to choose about datasets.

---

### 10. W1 build plan

#### 10.1 Scope (from BI_MODEL_ARCHITECTURE.md W1)

The dataset and model item types; pack-generated datasets; connector and Python datasets; implicit models; MQ v1
(this spec, validator, compiler, API); security at the dataset, model security only tighter. **Not in W1** (formats only):
several-fact correctness guarantees beyond today's (W2), currency guard refusal (W2), incremental standing queries (W3),
consumer YAML migration and the designers' full form (W4), AI on MQ beyond *ask* (W5).

#### 10.2 Work packages (at most two builders at once)

| WP | Builder | Size | What | Depends on |
|---|---|---|---|---|
| **S1** Items and parsers | server | M | item kinds `dataset` (v2) and `model` (`BiService.KINDS`, `BiFolder` `models/`, catalogue, governance, bundles); parsers and validators for sections 1, 2 (codes 9100–9139); stable ids written on save; the compatibility reader (8.4) | — |
| **S2** Pack-generated datasets | server | M | generation from packs (7.1) reusing `PackModels` column discovery and `ReferenceCatalog`; vocabulary meanings; regeneration on pack reload; read-only; Admin → Packs listing | S1 |
| **S3** MQ v1 | server | L | MQ parser, validator (5.3) with `fix`, flattener, compiler front end (5.4) over `SqlCompiler`; per-dataset security and dimension propagation; top-N, relative, drill moved from `VisualQueries` into MQ; totals by additivity; currency and semi-additive warnings; `/mq`, `/models/{id}/query`, `/describe`, `/mq/validate`; old `/datasets/{id}/query` translated | S1 |
| **S4** Connector and Python datasets | server | M | `source.connector` query (SQL target, bound dates); `source.python` and `source.model` with MQ inputs via `runFramed` and the Transform runner | S3 |
| **S5** Consumers on MQ | server | M | `VisualMq` (reports, exports, subscriptions, embeds through it); alerts `type: model` and visual sources via MQ; AI *ask* emits MQ; implicit models (section 3) | S3 |
| **S6** Standing-query endpoints | server | S | `/mq/subscribe`, `/mq/stream` with full recompute on generation change, throttle, caps (5.8) | S3 |
| **C1** Console on MQ | console | M | designer previews, slicer values, Analyse Slice and extracts (`explore_routes.extract_for`) send MQ; *Query* panel per visual; implicit-model header and *Make it a model* | S3 (API stub first) |
| **C2** Dataset wizard v2 and model view as editor (first cut) | console | M | the three-step wizard (9.2) for connector, Python, model and `extends`; the Model view edits a model: datasets panel, pack-reference suggestions, roles, measures tab with defaults, security tab with *Preview as* | S1, S2 |
| **M1** Converter and equivalence harness | migration | M | `drishti.py bi migrate` (8.3) on a temporary copy, the plan report, the equivalence harness (8.6); no shipped file changed in W1 | S1, S3 |
| **D1** Docs and tutorials | docs | M | new guides `BI_GUIDE.md` (v2) and `MODELS.md`, `MODEL_QUERY.md` (MQ reference), API and CLI; tutorial 10 gets a new first part "From a pack dataset to a report in five minutes" (UI, CLI, YAML); the rest of 10–18 move in W4 | each WP as it lands |

**Order with two builders.** Builder A (server): S1 → S3 → S5 → S6. Builder B: S2 → C2 → S4 → C1 → M1. D1 follows each
WP (written by the builder of the WP, reviewed in the experience wave). S3 publishes the MQ request/response types first
(day one of S3) so C1 can start against them.

#### 10.3 Test strategy

* **Unit** (fast, every WP): parsers and validators with every code of 9100–9183, one test per row of the tables in
  sections 1.11, 2.11, 4.4, 5.3; the flattener (model → engine `Dataset`) compared to the old dataset objects of every
  sample; MQ canonicalisation (the cache key) stable under key order.
* **Security matrix**: for each sample model, `rita`, `hana`, `ann` and a reader with no role: every visual's MQ; masks
  before aggregation; dimension propagation; model rules only tighter (a property test that ANDing never adds a row).
* **Equivalence** (8.6): old vs new over every shipped sample report visual, dataset test, the sample alert sources and
  extracts, on the **10K-trade demo lake** (never larger locally), one heavy process at a time; the 8.5 allow-list is
  explicit.
* **Acceptance test (W1)**: one test that asks the **same MQ** through a report visual, an alert (`type: visual` and
  `type: model`) and an Analyse extract-and-slice, as three readers, and asserts identical, per-reader masked numbers.
* **Browser** (Playwright, at phone and desktop width, keyboard-only pass): implicit-model report from a pack dataset
  without typing; wizard happy path and each dead-end check; model designer add-dataset → suggested relationship →
  measure → preview; *Make it a model*.
* **No drill during the wave**: targeted tests between WPs; one drill at the end of release 2.2.0, per the drill rule.

#### 10.4 Acceptance criteria

1. (W1, from BI_MODEL_ARCHITECTURE.md) **a report, an alert and an Analyse slice ask the same model through the same
   query language and get identical, per-reader masked answers** (10.3 acceptance test, three readers).
2. Every enabled pack kind with a serving connector has a generated dataset, visible in the catalogue, read-only,
   versioned with its pack.
3. A connector-query dataset and a Python dataset can be made in the wizard without typing YAML, used in a model and
   queried.
4. Every shipped sample answers through MQ (via the compatibility reader) with the equivalence harness green apart from
   the 8.5 allow-list.
5. Model security is provably only tighter (property test) and dataset rules apply before any join.
6. Every refusal of 5.3 is shown beside the control that caused it, with its `fix` where one exists (experience review).
7. The experience wave's power-user findings are fixed before the drill.

---

### 11. Open questions for the product owner

**Decided on 2026-10-12: all eight accepted with the recommended answer.** Question 1 is accepted **with the security
safeguard** of section 2.6: Check flags any dataset of a model that has no row rule while a role is restricted elsewhere
in the same model, and the model cannot be published until that dataset gets a rule or an explicit, audited
`may-see-all: [role]` note (`DRS-9130`).

| # | Question | Recommended answer |
|---|---|---|
| 1 | **Security binding.** With rules at the dataset, a desk-head sees **all** netting-set exposure (today refused). Accept that, or keep a "fact rule hides other facts" option? | Accept; it is D-consistent and explainable. Offer a model rule on the other fact when a site wants it, and have the converter keep the composed sample's meaning with `having`. **Accepted with the safeguard (2.6, `DRS-9130`)** |
| 2 | **Implicit models bring pack dimensions** (one hop by pack reference). Always, or only when asked? | Always, one hop, dimensions only: it is what makes the first report useful with no model step |
| 3 | **Boards over two models** (`desk-wall`). One model per board strictly, or one model per board **page**? | One model per board, with islands allowed in a model (warning); convert `desk-wall` to a `live-desk` model. Revisit if real boards need more |
| 4 | **Generated dataset ids** `pack.<pack>.<kind>`: is the pack name stable enough, or use the pack code (`TRDS`)? | The pack name: readable in YAML and MQ; a pack rename is a major anyway |
| 5 | **The currency guard in W1**: warning only (refusal in W2), or refuse from W1? | Warning in W1 (it touches many shipped visuals over `Notional`); refuse in W2 as planned |
| 6 | **Old query API**: keep `POST /datasets/{id}/query` translated until W4, or remove in W1? | Keep until W4 (the console, Python `drishti_bi` and scripts move in W4), then `410` with the MQ equivalent |
| 7 | **Converter on stored items**: republish published items automatically as the bi-admin, or leave them as drafts for owners to publish? | Republish automatically (audited, reversible by restoring the saved state): a half-migrated catalogue is a dead end for readers |
| 8 | **MQ in YAML and JSON**, both forever, and the name "model query" (`mq: 1`) for users? | Both (YAML in files and tests, JSON on the wire); users see "query" in the UI, "model query" in the docs |

### 12. Query execution tiers *(planner: W3; the source and Java tiers exist in W1)*

A model is a small database rooted in packs: its datasets are the tables, references and relationships are the keys and
joins, measures are its stored calculations, row rules and masks are its grants, and the **model query (MQ, section 5)**
is its query language. Every panel, card, table, chart, slicer, alert, tile, export and Analyse slice is one MQ. An MQ
names **fields and measures only**; the joins come from the model's relationships, so a report says *what*, the model
knows *how*.

**The servers do the work; the browser draws.** Product-owner decision (2026-10-12): computing in the browser risks
different answers on different devices (an iPhone's Safari is not a PC's Chrome), so every number is computed on the
servers, and the browser only renders the answer it is given.

| Tier | Runs | Work | Why there |
|---|---|---|---|
| **1. Source** (through the pack's connector) | DuckDB over a Delta or Parquet lake, SQL to PostgreSQL, a stream's own rollups | column choice, row filters (including the reader's row rules on that dataset), aggregation at the dataset's own grain when the source can do it | the data does not move; the source scans its own data fastest |
| **2. Java server** (the Drishti engine) | the Spring Boot server | the MQ planner; joins between datasets of different connectors; per-grain aggregation the source cannot do; the meeting of facts through shared dimensions; fan-out refusal; the currency guard; **security** (row rules and masks per dataset before any join); totals; sort, limit, top-N; **standing queries** kept current incrementally; caches and refresh layers | the only place that sees every dataset of a query and can enforce security |
| **3. Console backend** (FastAPI, Python) | the console server | Python datasets, Python measures and **Python visuals** in the sandboxed runner (masked input only, CPU/memory/time limits); rendering of exports (PDF, PNG, Excel, CSV); narrative text; shaping the answer for the page (formats, labels, chart specification) | Python and rendering run the same way for every reader, whatever their device |
| **4. Browser** | the reader's browser | **presentation only**: draw the chart or table it is given, hover tooltips, scroll; every interaction that changes numbers (a slicer, a sort, a drill, a cross-filter, a what-if value) sends a new MQ to the servers | no number is computed here, so every device shows the same answer |

**Rules.**

1. **Every number comes from tiers 1–3.** The browser never sums, filters, sorts, pivots or recalculates; the same MQ
   gives the same answer on any device. (Answers are cached per reader on the server, so interactions stay fast.)
2. **Security never leaves the servers;** the browser receives only what the reader may see, already masked.
3. **Python runs on the console backend** (sandboxed), never in a report's browser page. A design does not choose
   where code runs.
4. **Live:** a subscribed MQ stays on the Java server, which pushes the **changed rows of the answer** to the page; the
   browser replaces them on screen (it does not recompute).
5. **`options.explain: true`** returns the plan: which parts ran at which tier, the source queries, rows moved, time per
   part. The designer shows it under each visual.

**Future work, not scheduled: a Python console in the browser.** A person's own Pyodide console connected to a model
for just-in-time analytics is a possible later addition. Until then there is no browser computation at all: Analyse's
Python cells run on the console backend's sandbox like every other Python item.

### 13. Worked example, end to end: two packs, five datasets, one model, one report

The whole architecture in one example, using two shipped packs: **trading** (kind `trade`) and **counterparty-risk**
(kinds `netting-set`, `credit-limit`, `collateral-balance`). Field names are the packs' real ones.

#### 13.1 The picture

```
 PACKS (kinds + connectors; the data stays here)
 ┌──────────────── trading ────────────────┐     ┌────────────────────── counterparty-risk ──────────────────────┐
 │ kind trade  (trading-store: Delta lake) │     │ netting-set, credit-limit (credit-store)                      │
 │                                         │     │ collateral-balance (collateral-store)                         │
 └───────────────────┬─────────────────────┘     └──────────────┬───────────────┬──────────────────┬─────────────┘
                     │                                          │               │                  │
 DATASETS (one per kind, automatic; plus one derived)           │               │                  │
   trades ◄─ pack.trading.trade                 netting-sets ◄──┘   limits ◄────┘    collateral ◄──┘
   (fact)                                       (fact)             (fact)            (fact)
                                         counterparties ◄─ derived from credit-limit: one row per counterparty
                                         (dimension, shared)
                     │
 DATA MODEL  counterparty-cockpit@1  (datasets + relationships + measures + security; versioned; owned by risk-analytics)
                     │
 CONSUMERS (each references the model; none owns it)
   report "Counterparty cockpit"   board "Risk wall"   alert "Headroom below 10%"   Analyse notebook   subscription
```

#### 13.2 The five datasets

Three are generated by the packs and used as they are (section 7); you never write them. Their essentials:

| Dataset | From | Grain (one row per) | Columns used | Role |
|---|---|---|---|---|
| `pack.trading.trade` | trading / `trade` | trade per business date | `tradeId` key, `nettingSet` → netting-set, `counterpartyId` → counterparty, `desk`, `productType`, `currency`, `notional`, `mtm`, `pnl1d`, `tradeDate` | fact |
| `pack.counterparty-risk.netting-set` | counterparty-risk / `netting-set` | netting set | `nettingSetId` key, `counterparty`, `eePeak`, `pfePeak`, `netMtm`, `cva` | fact |
| `pack.counterparty-risk.collateral-balance` | counterparty-risk / `collateral-balance` | collateral balance | `balanceId` key, `nettingSet` → netting-set, `held`, `posted`, `net` | fact |

Two are written by a person (in the wizard, which writes this YAML):

```yaml
# 4. limits: the PFE credit limits only, with a derived headroom column
rachana: 2
dataset: pfe-limits
title: PFE credit limits
extends: pack.counterparty-risk.credit-limit@^1
where: measure = 'PFE'                                 # row filter: one measure of limit
columns: [limitId, counterparty, counterpartyName, limit, used, utilisation, status]
derive:
  headroom: { expr: "limit - used", type: decimal, meaning: amount, unit: { currency: USD }, additive: true, format: amount0 }
grain: { says: One row per PFE limit, keys: [limitId] }
```

```yaml
# 5. counterparties: a shared dimension derived from the limits, with explicit de-duplication
rachana: 2
dataset: counterparties
title: Counterparties
extends: pack.counterparty-risk.credit-limit@^1
columns: [counterparty, counterpartyName, reviewDue]
grain: { says: One row per counterparty, one-row-per: counterparty, keep: latest by reviewDue }   # never a silent distinct
columns-meta:
  counterparty:     { role: key, meaning: counterparty-id }
  counterpartyName: { role: attribute, of: counterparty }
security:
  masked: [counterpartyName]                           # names are sensitive: shown as ••• without the raw power
```

#### 13.3 The model

```yaml
rachana: 2
model: counterparty-cockpit
version: 1.0.0
title: Counterparty cockpit
owner: risk-analytics
datasets:
  trades:         { dataset: pack.trading.trade@^1,                         role: fact }
  netting_sets:   { dataset: pack.counterparty-risk.netting-set@^1,         role: fact }
  collateral:     { dataset: pack.counterparty-risk.collateral-balance@^1,  role: fact }
  limits:         { dataset: pfe-limits@^1,                                 role: fact }
  counterparties: { dataset: counterparties@^1,                             role: dimension }
relationships:                                          # suggested from the packs' references; accepted with one click
  - { from: trades.counterpartyId,       to: counterparties.counterparty }   # many-to-one, crosses the two packs
  - { from: netting_sets.counterparty,   to: counterparties.counterparty }
  - { from: limits.counterparty,         to: counterparties.counterparty }
  - { from: trades.nettingSet,           to: netting_sets.nettingSetId }      # trades roll up to netting sets
  - { from: collateral.nettingSet,       to: netting_sets.nettingSetId }
measures:
  MTM:               { expr: "sum(trades.mtm)",           format: signed0 }
  "P&L 1d":          { expr: "sum(trades.pnl1d)",         format: signed0 }
  Trades:            { expr: "count(trades)" }
  EE peak:           { expr: "sum(netting_sets.eePeak)",  format: amount0 }
  PFE peak:          { expr: "sum(netting_sets.pfePeak)", format: amount0 }
  Collateral held:   { expr: "sum(collateral.held)",      format: amount0 }
  PFE limit:         { expr: "sum(limits.limit)",         format: amount0 }
  Headroom:          { expr: "sum(limits.headroom)",      format: amount0 }
  Limit used:        { expr: "ratio(sum(limits.used), sum(limits.limit))", format: pct1 }
  Collateral cover:  { expr: "[Collateral held] / [EE peak]", format: pct0 }   # two facts, each at its own grain
hierarchies:
  Counterparty: [counterparties.counterpartyName, netting_sets.nettingSetId]
security:
  rows:
    trades:
      - { role: desk-head, where: "trades.desk in user.desks" }
      - { role: [risk, analyst, admin], where: "true" }
  may-see-all: { netting_sets: [desk-head], collateral: [desk-head], limits: [desk-head], counterparties: [desk-head] }
                                                       # the safeguard (2.6): exposure is shared across desks by policy; audited
tests:
  - { name: Headroom is limit minus used, measure: Headroom, expect-equals: "[PFE limit] - sum(limits.used)" }
ai:
  synonyms: { Headroom: [room under the limit, spare limit], "P&L 1d": [daily P&L] }
```

What the model guarantees, without anyone writing a join:

* **MTM and PFE peak per counterparty in one visual** are each aggregated at their own grain (trades, netting sets),
  then meet through `counterparties`. Summing a joined trade-by-netting-set table instead would repeat each netting
  set's exposure once per trade (the trap of tutorial 18).
* **A filter on a shared dimension filters every fact;** a filter on one fact's own column filters **only that fact**.
  `trades.currency = USD` narrows MTM and P&L, not exposure or limits; the visual's footnote says so.
* **Security per dataset:** a desk-head's rule cuts `trades` to their desks before any join; exposure, collateral and
  limits are shown in full to them because the model says so explicitly (`may-see-all`, audited); `counterpartyName` is
  `•••` for readers without the raw power, in every report on this model.

#### 13.4 The report (it references the model; it owns nothing but its layout and local measures)

```yaml
rachana: 2
report: counterparty-cockpit-report
title: Counterparty cockpit
model: counterparty-cockpit@1          # the one model it is rooted in
measures:                               # local, "this report only"; can be moved into the model
  Headroom after collateral: { expr: "[Headroom] + [Collateral held]", format: amount0 }
pages:
  - name: Overview
    visuals:
      - { id: total-mtm, type: card,  wells: { value: [MTM] } }
      - { id: total-headroom, type: card, wells: { value: [Headroom] } }
      - { id: by-cpty, type: bar,    wells: { axis: [counterparties.counterpartyName], values: [MTM, PFE peak] } }
      - { id: limits, type: table,   wells: { columns: [counterparties.counterpartyName, PFE limit, Limit used, Headroom, Headroom after collateral] },
          filters: [{ field: counterparties.counterparty, op: top, n: 10, by: Limit used, dir: desc }] }
      - { id: ccy, type: slicer,     wells: { field: [trades.currency] }, format: { mode: dropdown } }
      - { id: concentration, type: python, wells: { values: [counterparties.counterpartyName, PFE peak] } }   # runs on the console backend
```

Other consumers on the **same model** (none owns it; the model's *Used by* lists them all):

```yaml
alert: headroom-low
source: { type: model, model: counterparty-cockpit@1,
          query: { select: { measures: [Limit used] }, where: [{ field: counterparties.counterparty, op: eq, values: [CPTY-ALDERSHOT] }] },
          reduce: value }
rule: { above: 0.9 }                    # tell me when Aldershot uses more than 90% of its PFE limit
```

A board tile, a subscription of the report, and an Analyse notebook (`model: counterparty-cockpit@1`, `shape: rows`
extracts) are written the same way.

#### 13.5 What happens when Rita (risk) and Hana (desk head, rates desk) open the report

**The bar `by-cpty`** sends this MQ (the designer shows it under the visual):

```yaml
mq: 1
model: counterparty-cockpit@1
select: { fields: [counterparties.counterpartyName], measures: [MTM, PFE peak] }
where:  [{ field: trades.currency, op: in, values: [USD] }]       # Hana picked USD in the slicer
time:   { as-of: 2026-10-09 }
```

The planner splits it:

| Step | Tier | What runs |
|---|---|---|
| 1 | source: trading-store (DuckDB over the Delta lake) | `select counterpartyId, sum(mtm) from trades where businessDate = '2026-10-09' and currency = 'USD' and desk in ('DESK-RATES') group by counterpartyId` — Hana's row rule is pushed down as part of the scan; for Rita the `desk in` clause is absent |
| 2 | source: credit-store | `sum(pfePeak) group by counterparty` over netting sets (no currency filter: a fact's own filter does not reach other facts) |
| 3 | source: credit-store | counterparties: one row per counterparty, latest by `reviewDue` |
| 4 | server | join the two aggregates to `counterparties` on the counterparty key (each already at counterparty grain: no fan-out possible); mask `counterpartyName` for Hana (no raw power) → `•••`; apply the visual's sort and limit |
| 5 | console backend | shape the answer for the bar chart (labels, formats, the chart specification) |
| 6 | browser | draw the bars and show tooltips; a click on a bar (cross-filter) or a new slicer value sends a new MQ |

**What each sees:** Rita gets every counterparty, names shown, MTM over all desks. Hana gets the same PFE peaks
(`may-see-all`), MTM only from the rates desk's USD trades, and `•••` instead of names. Both answers come from the same
model and the same MQ; only the reader differs.

**The table `limits`** runs entirely on credit-store plus the server (no trades are read); its local measure
`Headroom after collateral` is computed on the server after `Headroom` and `Collateral held` are aggregated at their
own grains. **The Python visual** runs on the console backend in the sandbox on the masked rows; for Hana it
receives `•••` names, never the real ones, and every device shows the same picture. **The alert** runs the same MQ path on the server as its owner, every check.

**Live:** if the trading pack's connector streams (time mode `live`), the bar's MQ is subscribed: the server keeps the
MTM aggregate current by applying each new trade as a difference to its counterparty's sum, and pushes only the changed
bars to the page, which redraws them. PFE peak, from a snapshot dataset, stays as of the business date until the next load.

#### 13.6 What you did, and what you did not have to do

* You chose four pack datasets, wrote two small datasets in a wizard (a filter, a derived column, an explicit
  de-duplication), accepted suggested relationships, named ten measures, and built a report by dragging fields.
* You did **not** write a join, copy any data, write security per report, or decide where anything runs. The packs gave
  the keys, meanings and references; the model gave the joins and the measures; the servers computed every number;
  security followed the data from the pack to every consumer.

## 16. Rupaka: the original design and every phase as built

Status: design, **built in part**. Phases 0 to 7 and 9 of section 20 and the AI stage are built (summary at 2.1.0 in [section 20.15](#2015-as-built-at-210-and-what-is-still-not-built)); the proof of concept (phase 0) was removed in 2.2, and since 2.3.0 (wave B, every number computed on the servers) no BI page loads Perspective or DuckDB-Wasm. Waves A to C and later BI work follow this document. Owner: the BI package of the server (`com.ash.drishti.server.bi`, with `dataset`, `engine`, `report`, `board` and, until 2.2, `poc` beside it), the console's BI routes, templates and scripts (`routes/bi_routes.py`, `web/templates/bi/`, `web/static/js/bi*.js`), the existing source layer, identity, collaboration and Elements.
Companion notes: [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#results-computed-by-a-streaming-producer) (streaming result producers: the heavy case this design leaves to a producer), [AI.md](../admin/AI.md#4-server-side-pricing-the-quant-service-proposed) (server-side pricing, proposed) and the Project Rūpaka SRS that this
design adopts and adapts.

**How to read this document.** Sections 1 to 19 are the **design**: they say what Rūpaka should be and, where building changed the design, say **As built** in place. Section 20 is the build plan and, for each of phases 1 to 5, a **"as built"** subsection that lists exactly what exists and what does not; when a design section and an "as built" subsection differ, the subsection is right. The user documents are
[BI_GUIDE.md](../guides/BI_GUIDE.md) (start here), [tutorial 10](../tutorials/10-first-bi-report.md), [BI_GUIDE.md](../guides/BI_GUIDE.md#30-datasets-the-yaml-the-expression-language-and-the-security-rules), [BI_GUIDE.md](../guides/BI_GUIDE.md#31-reports-the-yaml-the-visuals-and-how-they-behave), [BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 23](../guides/BI_GUIDE.md#23-boards-report-pages-of-live-tiles) and [BI_GUIDE.md](../guides/BI_GUIDE.md#32-publishing-and-governance-versions-review-certification-lineage-impact-environments). The module names of the 2.1 design (`drishti-bi-core`, `-query`, `-live`, `-ai`; its diagram was replaced and is archived, section 17) were never modules: the code is one server package, with the same one-way dependencies.

**The goal.** Drishti becomes a business-intelligence platform as nimble and as capable as commercial BI products, with
**Power BI** as the benchmark, while keeping what Drishti already does that they do not: live data at
terminal speed, one grammar for every screen, masks and audit on every value that leaves the server, and a click from
any number down to the record behind it. BI gets its own place in the product, **the BI page**, separate from the
streaming terminal, reached from the top bar or by typing `RUPAKA <GO>`.

**The decisions in one paragraph.** The server stays the authority on data: queries run on the server (DuckDB over the
lake and the connectors), results leave as **masked Apache Arrow**, and the browser slices what the user may see without
asking again. Reports, datasets and workspaces are **YAML documents in the Rachana grammar**, edited by a Power BI-style
designer that writes the same YAML (two views of one draft, as the Build workbench already does). Streaming visuals use
**FINOS Perspective**; charts use the **ECharts** the console already vendors; free exploration uses **Graphic Walker**
(the component behind PygWalker). **Python** is a first-class, interface-driven extension (transforms, measures, visuals)
that runs in a sandboxed server runner or in the browser (Pyodide), behind one interface. **Generative AI** comes later (stage 2 for BI, stage 3 for the whole UI, section 20.1), through one provider interface,
**off by default**; everything works without it. Publishing goes through workspaces,
review and a catalogue with endorsement, and access is the same roles, packs, masks and row rules as the rest of Drishti.

### 1. Positioning against Power BI

Power BI is the reference because it is what our users already know: workspaces, semantic models, reports with pages
and visuals, a designer with fields, visualisations, format and filter panes, publishing to apps, row-level security,
refresh schedules, subscriptions, alerts, Q&A and an AI assistant. Rūpaka must feel familiar to a Power BI author in
the first ten minutes, and must win clearly where Drishti is strong.

| Area | Power BI (what users expect) | Rūpaka (what we build) | Verdict |
|---|---|---|---|
| Authoring | Desktop designer, fields / visuals / format / filters panes, pages | The same four panes in the browser, plus a YAML tab that is the same draft; no desktop install | **Match**, browser-only is a plus |
| Semantic model | Tables, relationships, measures (DAX), hierarchies, formats | Datasets in YAML: tables, relationships, measures and calculated columns in a closed expression language (Rachana-EL, extended for aggregation), hierarchies, formats, descriptions | **Match** for common models; no DAX compatibility |
| Data modes | Import (refresh), DirectQuery, streaming datasets (limited) | **Live** (every change, at terminal speed), **cached** (server DuckDB, refreshed by schedule or by a data-load signal), **direct** (pushed down to the source) | **Beat** on live |
| Streaming visuals | Limited tiles, a few seconds' refresh | Perspective grids and pivots, ECharts, hundreds of updates a second per view | **Beat** |
| Drill to detail | Drill-through to another report page | Drill-through to another page **and** to the Drishti terminal view of the record (the trade, the netting set), with its links, history and provenance | **Beat** |
| Security | Row-level security, object-level security, sensitivity labels | Row rules per dataset, field masks (already in Drishti, applied in every channel), classification tags, kinds per role | **Match**; masks reach emails, chat bridges and snapshots too |
| Governance | Workspaces, apps, endorsement, lineage, deployment pipelines | Workspaces, catalogue entries, endorsement, lineage to connectors, versions with four-eyes publish, environments by pack bundles | **Match** |
| Collaboration | Comments, subscriptions, Teams sharing | Drishti shares, threads, @mentions, email digests, chat bridges, legal holds, exports | **Beat** on compliance |
| AI | Q&A, Copilot (cloud, licensed) | Provider-neutral hooks (any OpenAI-compatible or Anthropic endpoint, or on-premises), off by default, masks applied to prompts | **Match** in kind; **beat** on control |
| Python / R | Python and R visuals and scripts (desktop runtime, gateway) | Typed Python interfaces (transforms, measures, visuals) running sandboxed on the server or in the browser | **Beat** on safety and reuse |
| Embedding | Embedded analytics (licensed, iframe-based) | `<drishti-report>` Web Component, no iframe, token exchange, always masked | **Match**, simpler |
| Deployment | Cloud service, gateway for on-premises data | Runs entirely on your servers, next to your data; Java 21 and a Python console | **Different**: on-premises first |
| Paginated reports | Report Builder | Print layouts of a report page and PDF export | **Partial** (section 19) |
| Excel integration | Analyze in Excel | CSV export; XLSX export in a later phase | **Behind** at first |

**Where we must not lose.** Familiar authoring, the visuals people use every day (bar, line, area, combo, scatter,
table, matrix, card, KPI, gauge, slicer, map), filters at visual, page and report level, cross-filtering, drill-down,
bookmarks, export, subscriptions, and a good catalogue. Everything in this list is in the first two phases of section 20.

### 2. Principles

1. **The server decides what leaves.** Every query runs with the user's identity on the server; row rules and masks are
   applied before a row is sent. The browser analyses only what that user may see. No raw lake access from browsers.
2. **One grammar.** Datasets, reports, boards and workspaces are Rachana documents: versioned, validated by schema, tested,
   reviewed, diffed and shipped from the BI folder (`config/bi`, below) like Sutras. Nothing is code that can run arbitrary instructions.
3. **Live is the default, not a special mode.** Any visual over a live source updates as the source changes, at the frame
   rate the viewer can read.
4. **Every number has a way down.** From a total to the rows behind it, and from a row to its Drishti terminal view.
5. **Nimble.** The BI page opens in under a second; heavy engines (Perspective, DuckDB-Wasm, Graphic Walker, Pyodide) load
   only when a page uses them. Everything is vendored; nothing comes from a CDN.
6. **AI-optional.** Every capability works without a language model; AI features are hooks that an administrator turns on.
7. **Familiar, then better.** A Power BI author recognises every pane; a Drishti user recognises every key.

### 3. Concepts

| Concept | What it is | Power BI's nearest |
|---|---|---|
| **Workspace** | A container for datasets, reports and boards, with members and roles. Each user has a **personal workspace** (drafts, private); teams have **shared workspaces** | Workspace (and My workspace) |
| **Dataset** | A semantic model: tables from connectors (by their logical name), relationships, measures, calculated columns, hierarchies, formats, descriptions, row rules; a storage mode per table | Semantic model |
| **Measure** | A named aggregation in the closed expression language (`sum($.notional)`, `ratio(sum($.pnl), sum($.notional))`), or a Python measure (section 13) | Measure (DAX) |
| **Report** | Pages of visuals over one or more datasets, with filters at three levels, bookmarks and drill-through targets | Report |
| **Visual** | One chart, table, matrix, card, slicer or Perspective grid, bound to fields and measures | Visual |
| **Board** | **As built (phase 5):** a wall of tiles, each one visual of a report, with alert colours and a kiosk mode ([BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 23](../guides/BI_GUIDE.md#23-boards-report-pages-of-live-tiles)). The design's "live sorted list of many result rows" (LIVE_BI.md section 7.2, a deleted 2026-10-06 note, in git at tag v2.9.5) is a different, still proposed thing | Streaming dashboard tile |
| **Catalogue entry** | A published report or board with an audience, a description, an owner, tags and an endorsement | App / Endorsed content |
| **Endorsement** | `promoted` (an owner says it is good) or `certified` (a data steward says it is authoritative) | Promoted / Certified |
| **Refresh** | For cached tables: a schedule, a data-load signal (`POST /api/v1/packs/{pack}/loads`), or both | Scheduled refresh |

### 4. Entering BI: the top bar, `BI <GO>` and URLs

- **Top bar.** A new item **BI** (icon `bar-chart-line`) beside **Views**, with a menu: *BI home*, *My
  workspace*, *Catalogue*, *New report*, *Datasets*, and the user's three most recent reports. Shown to anyone with the
  `bi` power (section 11); admins see *BI settings* in Admin. **As built:** the menu holds *BI home*, *My workspace*, *Catalogue*, *Recent* (three items of any kind) and, for a `bi-admin`, *Administer: BI settings*; the bar's order is Views, BI, Forms, Reports, Build, Admin, Help; the **Reports** menu also lists *BI reports* (the catalogue filtered to reports). The rail's *Datasets* and *Designer* are pages of their own, not menu entries.
- **Command line.** `BI <GO>` opens the BI home page (`RUPAKA` is kept as an alias for every form below). `BI <report-or-board> <GO>` opens a report (type-ahead offers
  report titles and ids the user may open, as it offers entity ids today). `BI WORKSPACE <GO>` opens my workspace and
  `BI CATALOGUE <GO>` the catalogue (type-ahead completes both after `BI `). `BI` and `RUPAKA` are reserved keywords: a pack
  cannot define a mnemonic with either name (pack checks refuse it), and case does not matter (`rupaka <GO>` and `bi <GO>` work).
- **URLs.** `/bi` (home), `/bi/w/<workspace>` , `/bi/r/<report>[/<page>]`, `/bi/d/<dataset>`, `/bi/catalogue`,
  `/bi/design/<draft>`; filters and bookmarks in the query string (`?f=region:EMEA`) so a link reproduces what was seen. **As built:** also `/bi/workspaces`, `/bi/i/<item>`, `/bi/datasets`, `/bi/m/<dataset>` (the Model view), `/bi/b/<board>`, `/bi/environments`, `/bi/explore` (Analyse, phase 9: 20.9) and `/admin/bi`; a drill-through carries its value as `?carry=` and a board's kiosk is `?kiosk=1` ([BI_GUIDE.md](../guides/BI_GUIDE.md) section 2).
- **A separate shell.** The BI page shares the top bar, sign-in, themes, inbox and help with the terminal, but has its
  own layout: a left navigation rail, a wide canvas and its own keys. The terminal's F-key footer is not shown. From any
  BI visual, *Open in terminal* goes to the record's view; from a terminal view, *Analyse in BI* opens a report over the
  kind with the current filters.

### 5. The BI page

```
┌ top bar: DRISHTI · command line · date · Live · Views BI Forms Reports Build Admin Help · bell · user ───────────┐
│ ┌ rail ───────┐ ┌ canvas ──────────────────────────────────────────────────────────────────────────────────────┐ │
│ │ Home        │ │ Home: Recent · Favourites · Certified for you · Shared with me · What changed                 │ │
│ │ Catalogue   │ │  ┌ card ────────────┐ ┌ card ────────────┐ ┌ card ────────────┐                             │ │
│ │ My workspace│ │  │ Desk P&L (live)  │ │ Counterparty     │ │ Liquidity daily  │   each: owner, endorsement,  │ │
│ │ Workspaces ▸│ │  │ ✔ certified      │ │ exposure         │ │ refreshed 06:12  │   freshness, open / favourite │ │
│ │ Datasets    │ │  └──────────────────┘ └──────────────────┘ └──────────────────┘                             │ │
│ │ Designer    │ │                                                                                              │ │
│ │ Explore     │ │                                                                                              │ │
│ └─────────────┘ └──────────────────────────────────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

| Section | Shows | Who |
|---|---|---|
| **Home** | Recent, favourites, certified reports the user may open, items shared with them, what changed since their last visit | everyone with `bi` |
| **Catalogue** | Every published report and board the user may open; search (title, description, tags, fields used), filters (domain or pack, workspace, owner, endorsement, live or cached, freshness), sort by use or by date | everyone with `bi` |
| **My workspace** | Personal drafts, datasets and reports; publish from here to a shared workspace | authors (`bi-author`) |
| **Workspaces** | Shared workspaces the user belongs to, their content and members | members |
| **Datasets** | Datasets the user may build on, with lineage (connectors, sources, kinds), refresh state, row rules summary | authors |
| **Designer** | The report designer (section 9) | authors |
| **Explore** | Free exploration of a dataset with Graphic Walker; *Save as visual* to a draft | authors and analysts |

Keys on the BI page: `/` search the catalogue, `g h` home, `g c` catalogue, `g w` my workspace, `n` new report, `?` the
keys. All of it works at phone width (section 15). **As built:** the rail also has *Environments* (phase 4), *Analyse* (phase 9, lean: 20.9) takes the place of *Explore*, and the keys are `/`, `g h`, `g c`, `g w` and `?` (there is no `n`).

### 6. Datasets: the semantic model

A dataset is a YAML document (`<drishti.bi.dir>/datasets/<id>.dataset.yaml`, by default `config/bi/datasets/`, or in a workspace store):

```yaml
rachana: 1
dataset: desk-risk
version: 3
title: Desk risk and P&L
description: Trades, desks and counterparties with live MTM and daily P&L.
tables:
  trades:
    source: { connector: trading-lake, kind: trade }      # a connector by its logical name (CONNECTOR_FILES.md)
    mode: cached                                          # live | cached | direct
    refresh: { schedule: "0 */15 * * *", on-load: true }  # cron in the business zone, and on a data-load signal (built: 20.7)
    columns:
      tradeId:   { type: string, key: true }
      desk:      { type: string }
      cpty:      { path: $.counterparty.id, type: string }
      notional:  { type: decimal, format: amount0 }
      mtm:       { type: decimal, format: signed0 }
      tradeDate: { type: date }
  desks:
    source: { connector: desk-totals, kind: desk-pnl }
    mode: live
relationships:
  - { from: trades.desk, to: desks.desk, cardinality: many-to-one }
measures:
  Notional:      { expr: "sum(trades.notional)", format: amount0 }
  MTM:           { expr: "sum(trades.mtm)", format: signed0, description: "Mark-to-market in USD, signed" }
  MTM per 1m:    { expr: "ratio(sum(trades.mtm), sum(trades.notional)) * 1000000", format: signed2 }
  Trades:        { expr: "count(trades)" }
  P&L 1d (py):   { python: risk.measures.pnl_attribution, inputs: [trades.mtm, trades.desk] }   # section 13
hierarchies:
  Date: [tradeDate.year, tradeDate.quarter, tradeDate.month, tradeDate]
security:
  rows:                                                   # row rules: evaluated on the server for each reader
    - { role: desk-head, where: "trades.desk in user.desks" }
    - { role: risk, where: "true" }
  masked: [cpty]                                          # shown as ••• without `raw`, as everywhere in Drishti
```

- **Expression language.** Rachana-EL grows aggregation and window functions (`sum`, `avg`, `min`, `max`, `count`,
  `distinct`, `ratio`, `percentile`, `running`, `lag`, `rank`, time intelligence `ytd`, `mtd`, `same_period_last_year`)
  with an explicit filter context. It stays closed and side-effect-free; it compiles to SQL for DuckDB (section 7).
  No DAX compatibility: a short mapping table for Power BI authors is in the user guide.
- **Storage modes.** `live`: the table is the current state of a live kind (TopicHub, [streaming producers](DATA_ARCHITECTURE.md#results-computed-by-a-streaming-producer)); `cached`: copied
  into the server's DuckDB on refresh; `direct`: every query is pushed to the source (JDBC, DuckDB, Delta). A dataset may mix
  them; the designer shows each visual's freshness.
- **Lineage** is derived: dataset → tables → connectors → plugins → sources, and reports → datasets, shown in the catalogue.

#### 6.1 Where the data comes from: the same connectors

Rūpaka reads through the **same connectors as the rest of Drishti, by their logical names**
(`config/connectors/<name>.yaml`, CONNECTOR_FILES.md): one place for credentials, TLS, health, the Admin → Connectors page
and the audit of what reads where. There is no BI-only path to the data. What BI adds is two optional connector
capabilities for reading whole tables instead of one record:

| Access | Used by | Connector interface |
|---|---|---|
| One record (`fetch`, `subscribe`) | terminal views, live updates, drill to record | exists |
| Columns of a kind for a date (`columns`) | searches, derived kinds | exists (Delta's promoted columns) |
| **Bulk scan**: chosen columns, filters and a date range, as column batches | the columns layer (7.1), `cached` tables | **built** (phase 2), optional: `Optional<ArrowScan> scan(ScanRequest)`; Delta, DuckDB, JDBC (table mode) and file implement it over their promoted columns (`ColumnScans`); Parquet-only, Iceberg and Kafka are not built: they fall back to record reads |
| **Pushdown**: the compiled SQL runs in the source | `direct` tables over SQL stores | **built** (phase 2), optional: `Optional<SqlTarget> sqlTarget()` (a relation and a connection of the source's own pool, not an `ATTACH`); the DuckDB and PostgreSQL connectors provide it |

A connector with neither falls back to record-by-record reads, and the designer warns that a large table will be slow.
What Rūpaka writes (the columns and rollup layers) goes to a BI store that is itself a configured connector (`bi-store`,
by default a local Parquet/Delta folder; S3 by configuration), so it is managed like every other source.

#### 6.2 The data model: from pack entities to a relationship diagram

As in Power BI, reports sit on a **data model** (the dataset), and the data model sits on connectors:
connectors (by logical name) → data model (tables, relationships, measures, hierarchies, row rules) → reports. Reports
never bind to connectors directly.

- **Packs expose their entities.** Every kind of every pack the author may access is offered as a table, with the
  fields its Sutras, shape and about text describe. A pack's link graph (trade → counterparty, trade → netting set) is
  read as ready-made relationships, so a model started from pack kinds is already connected.
- **One model, many packs.** A data model may combine kinds from any packs (trades from `trading`, counterparties from
  `counterparty-risk`, exposures from `market-risk`); cross-pack links become cross-pack relationships.
- **Inference fills the gaps.** For kinds without declared links, the dataset helper proposes relationships from the data:
  a column whose values match another kind's key, with the match rate and the cardinality it found. Proposals are
  accepted one by one; nothing changes on its own. (This is profiling, not AI: stage 1.)
- **The Model view** in the designer is the relationship diagram: tables as boxes with their columns, measures and
  hierarchies; drag a column onto another to relate them; each line shows cardinality (one-to-one, one-to-many,
  many-to-one, many-to-many) and filter direction (single or both); a relationship can be inactive and used by a measure
  explicitly. The diagram and the YAML (section 6) are two views of the same draft; the layout is saved with it. *Built in phase 3 (20.3), except the explicit use of an inactive relationship by a measure and the filter direction `both` in queries.*
- **BI only.** Model relationships drive joins and cross-filtering in BI. The terminal keeps navigating by pack links;
  editing a model never changes a pack.
- **Access per pack.** A reader sees a model's tables only from the packs assigned to them (and not from packs switched
  off, or sample packs hidden from them); a table they may not see returns no rows and the visual says so. Row rules and
  masks apply per kind. Lineage lists every pack and connector behind a model, so switching a pack off shows the reports
  it affects first.

### Where BI files live

BI content has one home, the **BI folder**: `drishti.bi.dir` (environment variable `DRISHTI_BI_DIR`), `./config/bi` by default,
relative to the server's working directory. It sits beside the other two things a site owns, `config/connectors/` (the
connectors) and `config/packs/` (the packs); runtime output goes under `data/` (`drishti.data.dir`).

```
config/bi/
  README.md
  datasets/   <id>.dataset.yaml   section 6
  reports/    <id>.report.yaml    section 8
  boards/     <id>.board.yaml     phase 5 ([BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 23](../guides/REPORTS.md#23-boards-report-pages-of-live-tiles))
  notebooks/  <id>.notebook.yaml  stored, not run (phase 9)
  python/                         section 13: typed Python items (measures, visuals and Transforms; the Source interface is not built) with their manifests: <id>.python.yaml (phase 6: 20.8)
```

- **Never inside a pack.** A pack describes data (kinds, Sutras, links, vocabulary). A dataset may combine kinds from several
  packs and a report several datasets, so BI content is deployed, versioned and switched on by itself; a pack does not mention
  BI, and switching a pack off never deletes a report (the visuals over its kinds say they have no rows).
- **Datasets name connectors** by logical name ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)), as packs do.
- **Workspace drafts and published versions** live in the identity database (section 21, decision 1); the folder holds the
  reviewed content shipped with a site.
- The folders may be empty or absent. The `datasets/` files are parsed, validated and queried (phase 2, section 20.2), the `reports/` files drawn (phase 3, 20.3) and the `boards/` files drawn (phase 5, 20.5); `notebooks/` files are stored items, not run. The folder is watched live ([BI_GUIDE.md](../guides/BI_GUIDE.md) section 10).

### 7. The query engine

The engine is **DuckDB embedded in the server**: a model query compiles to one parameterised SQL statement, the reader's row rules are predicates they cannot remove, results are masked column by column and cached per reader, and every limit is a setting with its `DRS-9xxx` code. Since 2.3.0 every number is computed on the servers and the browser only draws. The current design is [section 4](#4-query-architecture); the 2.1 text (Arrow for Perspective and DuckDB-Wasm in the browser) was replaced and deleted (RUPAKA_2_1_ENGINE.md, in git at tag v2.9.5).

#### 7.1 Data at scale: JSON in Delta and Kafka

Most of the data Drishti serves is **JSON documents** in Delta Lake (partitioned by `business_date`) and on Kafka topics.
DuckDB is a fast single-node, column-oriented engine that spills to disk; it handles very large tables when it reads
**columns** and prunes partitions and row groups, but a query that has to parse a JSON document column costs in proportion
to the bytes of JSON, every time. The architecture therefore answers each query from the smallest layer that can:

| Layer | Content | Written by | Used for |
|---|---|---|---|
| **Raw** | JSON documents in Delta, as today | the site's ETL | drill to the full record; rare ad-hoc fields |
| **Columns** | per kind, the fields that datasets declare, typed (Parquet/Delta), partitioned by business date | **Drishti**, incrementally, when a data-load signal (DATA_GUIDE.md) says a date landed: JSON is parsed once per load, not per query | almost every BI query |
| **Rollups** | per dataset, pre-aggregations for the common group-bys | Drishti, after the columns of that date | dashboards answered in milliseconds (aggregate-aware routing, as Power BI's aggregations) |
| **Hot** | the current state of live kinds (Kafka state mode, streaming producers), bounded by the dataset's window | the live path, in memory | live tables and visuals |
| **Continuous** | heavy streaming aggregates | a producer (Pravaha, Flink), [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#results-computed-by-a-streaming-producer) | live totals over large streams |

The query planner rewrites a visual's query to the first layer that can answer it (rollup, then columns, then raw), and
joins the hot state with today's columns for live tables. Drishti's own searches already work this way: the Delta
connector's promoted columns are why a search over a million trades a day answers in about 134 ms (PERFORMANCE.md).

**Engine interface.** The compiler emits SQL against an `AnalyticEngine` interface. **DuckDB** (embedded, no extra
process) is the default. A dataset too large for one node sets `engine: <name>` and its `direct` tables are pushed down to
a distributed engine the site already runs (Trino or Starburst, Spark or Databricks SQL, ClickHouse, StarRocks) through
a connector, with Drishti still adding row rules and applying masks to the results. Reports and the designer do not change.

**Sizing rule of thumb** (to be replaced by measurements, [PERFORMANCE.md method 9](../admin/PERFORMANCE.md#9-many-users-soak-runs-and-memory-profiling)): one DuckDB server for datasets whose
*columns* layer fits a few terabytes and whose heavy queries run at modest concurrency; a distributed engine beyond that.
The phase 0 POC measured the JSON, columns and rollup paths side by side (RUPAKA_POC.md, since deleted: in git at tag v2.9.5).

### 8. Reports and visuals

*Phase 3 builds this section for ECharts, cards, tables and matrices, slicers and the interactions; wave 3 (20.7) adds the map, funnel, histogram, box plot and bubble and true totals; the Python visual is built in 20.8; the entity view is not. What is built is in 20.3 and 20.7; the reference is [BI_GUIDE.md](../guides/BI_GUIDE.md#31-reports-the-yaml-the-visuals-and-how-they-behave).*

A report is `<drishti.bi.dir>/reports/<id>.report.yaml` (by default `config/bi/reports/`): pages, each a 12-column grid of visuals; filters at report, page and visual
level; bookmarks; drill-through targets; a theme.

| Visual | Engine | Notes |
|---|---|---|
| Bar, column, line, area, combo, scatter, bubble, pie / donut, waterfall, funnel, treemap, heatmap, histogram, box | ECharts (vendored) | cross-filter on click; drill-down along hierarchies |
| Card, multi-row card, KPI (value, target, trend), gauge | Drishti panels (`metric`, `gauge`) | conditional formats |
| Table, matrix (with subtotals, expand/collapse) | Drishti `table` / `pivot`, or Perspective for large or live | export, conditional formats, data bars |
| Live grid, live pivot, live chart | **FINOS Perspective** (WebAssembly, Apache-2.0) | hundreds of updates a second; group, split, sort, filter in the visual |
| Slicer (list, dropdown, range, relative date, hierarchy) | Drishti | sync across pages |
| Map (points, regions) | ECharts geo with vendored map data | no external tiles by default |
| Text, image, shape, button (navigate, bookmark, drill-through) | Drishti | |
| Python visual | the Python runner (section 13; built: 20.8) | returns a declarative chart (an ECharts option, SVG or PNG), never HTML |
| Entity view | a Drishti Sutra view of one record, in a report | the terminal inside BI |

Interactions: cross-filter and cross-highlight between visuals on a page; drill-down and up along hierarchies; drill-through
to another page with filters carried; *Show as table*; *Show the rows* (a table of the underlying rows, masked); *Open in
terminal* on any row whose key is a Drishti kind.

#### 8.1 Interactive Sutras and slicers: controls, view state and filters

The terminal's Sutras and Rūpaka's reports share **one model of interaction**, so it is built once and works in both.

**Controls.** A Sutra or a report page declares `controls:`; each is placed in the strip, a panel header or a side bar.

| Kind | Use | Options from |
|---|---|---|
| `dropdown`, `multi-select` | a leg, a currency, desks | a fixed list; the record's own data (`$.legs[*].id`); a dataset column (distinct values the reader may see); a lookup kind |
| cascading | region → country → book: options depend on another control | an expression over the parent control's value |
| `toggle`, `segmented` | Pay / Receive / Both | a fixed list |
| `date`, `date-range` | a business date; paid between; relative ranges (`next-90d`, `this-month`, `ytd`) | the calendar of the pack |
| `number-range`, `slider`, `top-n` | notional between; the top 10 | bounds from data or fixed |
| `search` | contains, across chosen columns | the columns named |

A control has an `id`, a `label`, `options`, a `default`, `remember: user | none` and `required`. Its value is read in the
closed expression language as `ctl.<id>`. The plan's sketch (what was built differs in two places: the condition is `where: "within(@.payDate, ctl.when)"`, a function, not an operator, and the title is a template, `"Cashflows · leg ${ctl.leg}"`; see *As built (delivery 2)* below):

```yaml
controls:
  - { id: leg, kind: dropdown, label: Leg, options: "$.legs[*].id", default: first }
  - { id: when, kind: date-range, label: Paid between, default: next-90d }
panels:
  - id: cashflows
    kind: table
    rows: "$.legs[?(@.id == ctl.leg)].cashflows"
    where: "@.payDate within ctl.when"
    title: "Cashflows · leg {ctl.leg}"
```

**Behaviour.**
- Changing a control re-renders only the panels whose expressions read it; the rest do not move.
- **Live data stays live:** each new frame is filtered through the current control values before it reaches the screen.
- **View state is in the URL** (`?ctl.leg=L2&ctl.when=2026-10-01..2026-12-31`), so a link, a share, a snapshot and a
  subscription reproduce exactly what was seen (a sent share keeps the values and the column filters, see 8.1 "as built"); `remember: user` restores the last choice for that Sutra or report.
- **Cross-filtering:** a panel may declare `on-select: { filter: [cashflows], by: leg }`, so clicking a row or a chart bar
  sets a control; in Rūpaka every visual cross-filters its page by default, as Power BI's visuals do.
- **Where filtering runs:** in the browser for panels already loaded in full; on the server (the query or the source) for
  large tables and datasets, with the same expression, so the results are the same either way.

**Excel-style column filters on every table** (no Sutra needed). Each column header of a table, matrix or pivot has a
filter menu, as in Excel's AutoFilter: a searchable checklist of distinct values with counts, *(Select all)* and
*(Blanks)*; number conditions (equals, not equal, greater, less, between, top 10 / bottom 10, above or below average);
date conditions (today, this week, this month, last 7 days, between, before, after); text conditions (equals, contains,
begins with, ends with, does not contain); sort ascending and descending. Filters combine across columns; a filtered
column shows a funnel; one key clears them all. Column filters are part of the view state (URL, shares) and become a
report's visual-level filters in Rūpaka. They extend today's single *Filter rows* box, which stays as the quick search.

**As built (delivery 1).** The column filters are in the terminal (`static/js/colfilter.js`, called by `tables.js` and `tree-rows.js`;
`colfilter.css`; also inside `<drishti-view>`). Decisions taken: the checklist lists only the values the other columns' filters leave,
with counts; a column uses its ticked values or one condition; a masked value (`•••`) is never listed, all masked cells are one
*(masked)* entry and never satisfy a condition; types are detected from the cells (80% numbers or ISO dates); in a matrix a row
matches on its own cells and its ancestors stay open; the URL form is `?f.<panel>.<column heading>=in:a,b | gt:1m | bt:1~9 | top:10 |
today ...` (`~b` Blanks, `~m` masked) and is written with `history.replaceState`; remembering per user is off (a `<body
data-remember-filters="on">` or a browser flag turns it on); `Alt+Shift+X` clears a panel's filters. Not yet built: a server-paged
table (none exists today: every table holds the rows the server sent), so the filter runs in the browser and every change is
announced as the table event `drishti:column-filter` ({panel, filters in the URL form}) for a server-side pager to take; a column
filter on the nested-group pivot and in the Pivot tab (its own Filters zone filters source fields before grouping, the Excel report
filter); `controls:` and `ctl.*` (delivery 2, below). Rūpaka's visual-level filters will reuse `matches()` and the URL form.

**As built (delivery 2: `controls:` and view state in the terminal).** Documented for use in
[RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#controls), [USER_GUIDE.md](../guides/USER_GUIDE.md#controls-on-a-screen-choose-what-a-panel-shows)
and [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#the-controls-tab). What exists, against the plan above:

- *Grammar.* `controls:` (at most 12) with the kinds `dropdown`, `multi-select`, `cascading` (a `parent` and options that read `ctl.<parent>`),
  `toggle`, `segmented`, `date`, `date-range`, `number-range`, `top-n`, `search`; fields `id`, `label`, `options` (a list, an expression over the record,
  or `{ kind: <entity kind> }`), `default`, `remember: user | none`, `required`, `placement: strip | header | side` (header names its `panel`), `parent`,
  `min`, `max`; problem codes `DRS-2034` to `DRS-2039`. Rachana-EL gained `ctl.<id>`, the projection `[*]` (`$.legs[*].id`) and the functions `within`
  and `oneOf`; panels gained `where`, a `limit` that may read a control, and `on-select: { by, field, filter }`.
- *Where it runs.* The view pipeline evaluates with the request's values (`?ctl.<id>=...` on the view and stream endpoints), so a panel's rows,
  title and totals are computed on the server by the same expression; `&panels=a,b` builds only those panels. Live frames are built with the values the
  stream was opened with. Options are read from the record as the caller may see it (a masked value is never an option, a condition on a masked
  value never holds). Relative dates (`next-90d`, `this-month`, `ytd`, `next-5bd`) resolve on the request's business date and the **server's** business
  calendar, not a per-pack calendar, and a range always holds its resolved dates.
- *Console.* One script, `static/js/controls.js`, draws every kind (native selects and inputs, a checklist, a switch, a radio group with arrow keys; 44 px on
  touch; both themes), also inside `<drishti-view>`. A change asks `GET /v/{kind}/{id}/panels` for **only the panels that read the control** (plus the
  strip and the controls: a cascading child's options move with its parent) and swaps them in place. Measured on a warm local server, an interest-rate
  swap: the whole view 17.0 ms and 12.8 KB; the one panel 10.1 ms and 5.5 KB (the server still fetches the document and resolves the layout; the saving is
  binding and sending the other panels). Values are in the address (`?ctl.legs=2&ctl.when=...`, only for controls the reader touched) and in the Share
  button's link; the live subscription moves with them. A click or Enter on a row or bar of a panel with `on-select` sets the control (cross-filtering).
- *Remembering.* There is **no per-user view-preference store** on the server (the personal settings are a fixed list), so `remember: user` keeps the
  choice in the browser (`localStorage`, per signed-in user and Sutra), not per account.
- *Workbench.* A Controls tab (cards, YAML two ways, one `setControls` operation per change, undo and revisions, the real controls working over the
  previewed sample).
- *Shipped.* `irs-fixfloat` (its cashflows by leg and by date range) and `netting-set` (its member trades by asset class, product and how many, with the asset-class
  bars selecting), both generated by `tools/packgen/banking/make_sutras.py`.
- *Slider.* `kind: slider` (`min`, `max`, `step` fixed or read from the data; `range: true` for two handles, value `from..to`); the netting set's member
  trades have a notional threshold. Arrow keys, `PageUp`/`PageDown`, `Home`/`End`, drag; `role=slider` with `aria-valuenow`; 44 px handles on touch.
- *Sent shares, snapshots.* A share stores the sender's **view state**: the `ctl.*` values and the `f.<panel>.<column>` filters (query text, at most
  `drishti.collab.share.max-view-state` characters; a nullable column `view_state` on `drishti_share`, old rows have none). The recipient opens the view
  with them, the picture is built with the control values and the filters (the server applies the same filter grammar to the table rows, after the
  recipient masks), and the email carries that picture. Masks are per recipient as before.
- *Not built:* options from a dataset column (Rūpaka datasets); the view-level `title:` reading a control (a panel `title` does; if
  it did, the page reloads); the Pivot tab and the CSV download follow the controls (they read every row); control values stored with a scheduled
  subscription (there is no scheduled delivery yet); a server-paged table (the controls run on the server, but no table is paged by it).

**Safety and access.** Controls only select or filter; they never write. Options and filters are expressions in the closed
language, never code. Masks apply: a dropdown or a filter checklist never lists a masked value to a reader without `raw`,
and row rules decide which values exist at all. Every control works from the keyboard (the checklist with arrows, space
and type-ahead), at 44 px on touch screens, and is announced to screen readers.

**Order of delivery.** (1) Excel-style column filters on every table, matrix and pivot in the terminal (useful at once, no
grammar change); (2) `controls:` and `ctl.*` in Sutras, with URL view state (**built**, see above); (3) cross-filtering (**built** for tables and bars through `on-select`); (4) the same model as
Rūpaka slicers and the visual, page and report filters of the designer.

#### 8.2 Forms: the same criteria in the terminal and in BI

`FORM <pack> <GO>` (query by form, built in the terminal) and Rūpaka's filters are one model. A pack's `form:` section
(fields, types, operators, defaults, groups) is the **filter vocabulary** of its kinds:

- **Reports.** A report page may declare a *form panel*: the same typed inputs (text with regex, number and amount ranges,
  business-date ranges, enums, id lists), placed as the page's filter pane. Submitting it sets the page's filters; it is
  a control set (section 8.1) whose values compile to the dataset query's predicates, with row rules and masks applied.
- **Datasets.** A dataset table built from a pack kind inherits that kind's form fields as its default filters and slicers;
  the dataset may rename, hide or add fields.
- **Designers.** The Sutra designer's *Form* tab and the BI designer's *Filters* pane edit the same field model (type,
  operators, label, default, required, group), so a field defined once is offered in both.
- **Drill.** A form's results grid opens the record's Sutra view on its key, and a BI visual's *Show the rows* opens the
  same grid, so the two paths meet.

### 9. The designer

*Phase 3 builds the four panes, the YAML tab, data while designing, Check and undo. Expected-value tests (20.11) and promotion of an Analyse cell to a visual (20.13) were built later; a schema-completion popup in the YAML tab is not built (a strip of suggestions stands in). What is built is in 20.3; the guide is [BI_GUIDE.md](../guides/BI_GUIDE.md) section 15.*

Power BI authors find the same panes in the same places:

```
┌ pages: [Overview] [By desk] [+]                                     Undo Redo · Preview · YAML · Save · Publish ┐
│ ┌ canvas (12-col grid, snap, align, distribute) ──────────────────────┐ ┌ Filters ┐ ┌ Visualisations ┐ ┌ Fields ─┐ │
│ │  ┌ KPI ┐ ┌ KPI ┐ ┌ KPI ┐                                           │ │ report  │ │ ▦ ▤ ◔ ▥ ... │ │ ▸ trades│ │
│ │  └─────┘ └─────┘ └─────┘                                           │ │ page    │ │ Build: axis, │ │   desk  │ │
│ │  ┌ bar: MTM by desk ──────┐ ┌ live grid ─────────────────┐         │ │ visual  │ │ values, legend│ │   mtm  │ │
│ │  └────────────────────────┘ └────────────────────────────┘         │ │         │ │ Format: …    │ │ ▸ desks │ │
│ └────────────────────────────────────────────────────────────────────┘ └─────────┘ └──────────────┘ └─────────┘ │
└─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Fields pane:** the dataset's tables, columns, measures and hierarchies with search; drag onto the canvas creates the
  best visual for the field (number → card, date + number → line, category + number → bar), as Power BI does.
- **Visualisations pane:** the catalogue of section 8; *Build* (field wells: axis, values, legend, tooltips, drill-through)
  and *Format* (titles, colours from the theme, labels, axes, conditional formats).
- **Filters pane:** visual, page and report filters; basic, advanced and top-N.
- **YAML tab:** the same draft as text with schema completion (as the Build workbench's YAML tab); edits in either view
  appear in the other at once.
- **Data while designing:** every visual renders real data (a sample for large tables, labelled), with the query time shown.
- **Quality:** a *Check* button runs the report's tests (expected values on sample data), accessibility checks (contrast,
  alt text, keyboard order) and performance hints (a visual over 1 s).
- **Explore → visual:** an exploration in Graphic Walker can be saved as a visual in the draft.
- **Keys and accessibility:** every action has a key; the canvas is operable without a mouse (as layout mode is today).

#### 9.1 The analyst's workbench: Power BI and Jupyter in one place

Authors build reports; analysts also want to **slice, look at the raw data and compute**. The **Analyse** view of any
dataset, report visual or terminal search result gives them, in one tabbed workspace and without leaving the browser:

| Tab | What | Engine |
|---|---|---|
| **Slice** | drag fields to rows, columns, values and filters; pivot, sort, filter, top-N, totals; every change instant | DuckDB-Wasm on the extract already received (no server round trip); Perspective for live data |
| **Data** | the rows as a fast grid (millions virtualised), column profiles (type, nulls, distinct, min/max, histogram), search; a row opens its **JSON document** and its Drishti terminal view | Perspective grid; profiles computed locally |
| **SQL** | SQL cells over the loaded tables, results as tables or charts | DuckDB-Wasm |
| **Python** | notebook cells (Python in the browser) over the same tables as `pyarrow` / pandas / polars where available, with plots; `pygwalker.walk(table)` for drag-and-drop exploration | Pyodide in a Web Worker (the runtime Calc bundles), loaded on first use |
| **Explore** | Graphic Walker drag-and-drop over the tables | Graphic Walker |
| **Notes** | Markdown cells between the others | the console |

- **One data model across tabs.** The extract is held once as Arrow in the browser; the Slice, Data, SQL, Python and
  Explore tabs all see the same tables, and a result from any tab can become a new table for the others (`df` in Python
  appears as a table in SQL).
- **Local and governed.** Everything runs on the data the server sent for this user: row rules and masks were applied
  before it arrived, so local analysis cannot reveal more than the user may see. Extract size is capped per dataset
  (as built, `bi.explore.max_rows` and `max_fields` in the console); a larger question goes back to the server as a query.
- **From exploration to production.** A Slice becomes a visual in a report draft; a SQL cell becomes a dataset measure or
  table; a Python cell becomes a **Transform, Measure or Visual** draft (section 13) with its inputs captured as a test
  fixture, so notebook work turns into reviewed, tested, shared code instead of a lost notebook.
- **Saved as a notebook.** The whole workspace (cells, layout, the query that made the extract) saves to the user's
  workspace and can be shared, discussed (Drishti threads) and re-run on today's data.
- **Nimble.** The Slice and Data tabs open in under a second on a 1 million-row extract; Pyodide (large) and Graphic
  Walker load only when their tab is first opened, with progress shown; keys for everything (`Shift+Enter` runs a cell,
  `Alt+1`…`Alt+6` switch tabs).

#### 9.2 Ad-hoc quant in the analyst workbench

Analysts need pricing and risk next to the data, as Bloomberg's BQuant offers over Bloomberg data, but over the firm's
own records. The workbench therefore has two kinds of Python cell:

| Cell runs | For | Libraries |
|---|---|---|
| **in the browser** (Pyodide, Web Worker) | slicing, pandas, quick plots on the extract already received | the packages Pyodide provides |
| **on the console** (the sandboxed Python workers of section 13.2) | quant: pricing, Greeks, scenarios, VaR/ES, exposure and XVA | the vendored wheel set plus the quant engine |

**The quant engine is an interface,** `QuantEngine` in `drishti-console/core/quant/` (price, sensitivities, scenarios,
VaR/ES, exposure/XVA; Drishti records in through per-pack adapters, Arrow tables out), with **QLRisk** (the owner's
QuantLib-based risk library: pricing and sensitivities, VaR/ES/FRTB, PFE, SA-CCR, CVA/FVA, SIMM) as its first provider,
installed from a pinned wheel, and a mock provider for tests. QuantLib is native code, so the engine runs only in the
console's workers, never in the browser. Results become workbench tables, report visuals or Sutra panels, masked and
audited like every result. The server's quant service (AI.md) implements the same interface later, so ad-hoc
quant and production pricing share one path. Status: designed; built when QLRisk's API is declared stable.

### 10. Workspaces, publishing and the catalogue

- **Workspace roles:** `viewer` (open published content), `contributor` (create and edit drafts), `member` (publish),
  `admin` (members and settings). A workspace may require **review before publish** (four eyes: the Build workbench's
  review flow, reused).
- **Lifecycle:** draft (personal or shared) → proposed → published version N. Viewers always see the published version;
  authors keep editing the draft. Versions are kept, diffed and restorable.
- **Catalogue entry:** title, description, owner, workspace, tags, audience (roles, users, packs), endorsement, the datasets
  and connectors behind it, freshness, usage (views in the last 30 days). Audience decides who sees it in the catalogue;
  dataset row rules and masks still decide what each person sees inside it.
- **Endorsement:** owners mark `promoted`; data stewards (a `bi-steward` power) mark `certified`. Certified content is
  pinned first in search and on Home.
- **Environments:** a workspace's content exports as a pack bundle (the existing pack deploy with preview and rollback), so
  development, test and production are pack deployments, not copies by hand. **As built (phase 4):** a BI bundle is **its own checksummed file** with a dry-run import (`DRS-9041`, `9042`), not part of a pack archive, and there is no rollback beyond restoring the saved state an import keeps ([BI_GUIDE.md › Publishing and governance: versions, review, certification, lineage, impact, environments, section 7](../guides/BI_GUIDE.md#7-environments-bundles)).
- **Lineage and impact:** changing a dataset shows every report and visual that uses each changed field before saving.

### 11. Access, security and governance

| Control | How |
|---|---|
| Who may use BI | new powers on roles: `bi` (open BI and published content), `bi-author` (designer, personal workspace), `bi-steward` (certify, manage datasets), `bi-admin` (BI settings) |
| Which content | workspace membership plus the catalogue entry's audience |
| Which rows | the dataset's row rules, evaluated on the server with the user's identity (roles, desks, packs) |
| Which fields | Drishti's field masks (`drishti.security.redact`), applied to every result, export, subscription email, chat bridge, snapshot and AI prompt |
| Which kinds | the role's kinds, as today: a dataset table over a kind a user may not open returns no rows for them |
| Aggregates that leak | proposed, not built: `masked-from` and a minimum group size for masked inputs ([DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#results-computed-by-a-streaming-producer)) |
| Export | per catalogue entry: allowed formats and a row cap; every export audited |
| Classification | tags on datasets and reports (`internal`, `confidential`, `restricted`), shown on every page and export |
| Audit | who opened, filtered, exported, published, certified; the access log gains BI reads |
| Embedding | `<drishti-report>` through the existing token exchange; always masked |

### 12. Streaming BI

Phase 5 is built; section 20.5 lists exactly what, and this section says how it works and where the first design changed. The user references are [BI_GUIDE.md](../guides/BI_GUIDE.md#71-live-tables-phase-5) (live tables), [BI_GUIDE.md](../guides/BI_GUIDE.md#13-live-visuals)
(live visuals, Perspective), [BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 23](../guides/BI_GUIDE.md#23-boards-report-pages-of-live-tiles) and [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#live-bi-a-reports-live-tables) (the stream).

- A `live` table is the current state of a live kind: the engine reads the kind once, subscribes to each entity through the **existing TopicHub** (the same topics live views use; a dataset that names a connector subscribes through it), and applies each change to an in-memory DuckDB table **by key**, in
  batches (100 ms, merged per entity), in one transaction with the retention. **As built:** a row whose declared columns did not change is skipped; every live table has `updateSeq` and `updatedAt`; a table that is not used for ten minutes is closed.
- Visual updates are **coalesced** and **back-pressured**. **As built:** the server tells a browser only that a report's tables *changed* (`tick`, at most one per 250 ms, the latest state, never a queue; `status` every 5 s as the heartbeat); the page asks its visuals again, as the person looking, in one round at a time (at most every 250 ms, three
  queries at a time), drawing over the old picture in place. The first design's per-visual frame rates (4 a second for charts, up to 30 for Perspective grids) became **one cadence for a report**; a Perspective grid applies its changed rows at the same cadence (the retest, in RUPAKA_POC.md section 12, since deleted, showed Perspective itself taking 1,000 updates a second at 60 frames).
  Per-reader row rules and masks apply to **every answer**, never to the stream: the stream carries no data.
- Aggregations over live tables: **as built, measures are recomputed over the in-memory table on each query** (a few milliseconds at 10,000 rows), cached per live generation. The first design's **incrementally maintained aggregates** (sum, count, min, max by group adjusted by each change) are **built for the rollups a dataset declares** (20.11); heavy continuous
  aggregation belongs in a producer (Pravaha, Flink), [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#results-computed-by-a-streaming-producer).
- Time windows: **as built, a live table is bounded by retention** (`live: { rows, age }` and `drishti.bi.live.max-rows`); a visual-level time window is the rolling chart's point window, kept **in the browser**. The server keeps no history of an aggregate.
- Perspective: **as built, the first read is Arrow** (masked on the server) **and updates are JSON rows by key** (the rows whose `updateSeq` is above the last answer's; the whole set again when rows left the table), pivoted by Perspective in the browser on the rows the person may see. Perspective charts (d3fc) and the settings panel are built (20.13).
- Boards: a wall of tiles from several reports, kiosk mode, rotation and per-tile alert colouring (section 20.5), and notifications from a tile (section 20.6, part 2).

### 13. Python in BI: interfaces, not scripts

Python is supported the way a careful platform supports plug-ins: **typed interfaces**, packaged and versioned code,
tests, and a sandbox. Authors never paste scripts into a visual.

#### 13.1 The interfaces

```python
# drishti_bi (a small, dependency-free package shipped with Drishti)
from typing import Protocol
import pyarrow as pa

class Transform(Protocol):
    """A table in, a table out: cleaning, enrichment, reshaping. Declared output schema; deterministic."""
    output_schema: pa.Schema
    def apply(self, table: pa.Table, params: dict) -> pa.Table: ...

class Measure(Protocol):
    """A vectorised aggregation over grouped inputs: one value per group."""
    output_type: pa.DataType
    def compute(self, groups: pa.Table, inputs: list[str], params: dict) -> pa.Array: ...

class Visual(Protocol):
    """Data in, a declarative chart out (an ECharts option dict or a Drishti panel spec): never HTML or script."""
    def render(self, table: pa.Table, params: dict, theme: dict) -> dict: ...

class Source(Protocol):
    """Optional: rows from a Python-only source (an API, a model's output), as Arrow batches."""
    schema: pa.Schema
    def batches(self, params: dict): ...          # an iterator of pa.RecordBatch
```

- Implementations live in the BI folder's `python/` package (`config/bi/python/`; or a workspace's, for drafts), registered in YAML by dotted name
  (`risk.measures.pnl_attribution`), with a `manifest` of parameters, required packages and the execution targets it supports.
- Each implementation ships **tests** (inputs and expected outputs as small Arrow files) that the BI check runs.

#### 13.2 Where it runs: one interface, two targets

| Target | When | Isolation |
|---|---|---|
| **Server runner** (`drishti-pyrunner`: worker subprocesses the console starts on demand and stops when idle; nothing extra to deploy) | cached and direct datasets, scheduled refresh, anything shared by many viewers | each call in a worker process with CPU time, memory and wall-clock limits; no network unless the manifest declares hosts and an admin allows them; read-only file system except a scratch folder; an allow-list of packages; inputs and outputs are Arrow only |
| **Browser** (Pyodide, the runtime Calc already bundles) | exploration and personal analysis on data the user already received | the browser sandbox, in a Web Worker; loaded on demand |

The dataset or visual says `python: <name>` and, optionally, `run: server | browser | auto`; the engine picks the target
(`auto`: server for shared content, browser for personal exploration) and calls the same interface. Results are masked
again on the way out of the server runner, so Python cannot reveal a masked field.

#### 13.3 Packages

The server runner uses a fixed, vendored wheel set per release (pyarrow, numpy, pandas, polars where available,
scipy, statsmodels, scikit-learn; listed and licence-checked); a workspace cannot install packages. The browser target uses
the packages Pyodide provides; a manifest that needs a package missing there runs on the server only.

#### 13.4 PygWalker and notebooks

Calc (the in-browser Python) gains a `drishti_bi` client: `ds = bi.dataset("desk-risk"); t = ds.query(...)` returns the
masked Arrow table the user may see, and `pygwalker.walk(t)` opens PygWalker on it for those who prefer Python. A
notebook exploration can be saved as a Transform or Visual draft with its tests.

### 14. Generative AI hooks (off by default)

This section is **stage 2** (section 20.1): nothing in it is built in stage 1, BI without AI.

One provider interface (`AiProvider`: chat with tool calls, embeddings optional) with implementations for
OpenAI-compatible endpoints, Anthropic, and on-premises models; configured by an administrator, **off** unless turned on.

> **Built (1.18):** this interface now exists as the `drishti-ai` module ([AI.md](../admin/AI.md#1-model-providers): `LlmProvider`,
> `LlmModel`, `EmbeddingModel`, `LlmRegistry`, `LlmPolicy`, one `config/ai/<name>.yaml` per provider, a Spring AI adapter for
> OpenAI-compatible endpoints, Anthropic, and a mock) with a matching `core/ai/` in the console, plus an MCP client
> ([AI.md](../admin/AI.md#2-the-mcp-client-other-servers-tools-behind-drishtis-policy)). "Ask about this page" already runs on it. The hooks in this table are stage 2 and build on that layer: a hook calls
> the `LlmGateway` with its own feature name, so masking, audit, limits and "which roles may use which hook" come from the policy.
> **Built (stage 2, first slice):** *report from words*, *ask the data* and *explain this number* (section 20.1, "Stage 2 as built"; [BI_GUIDE.md section 29](../guides/BI_GUIDE.md#29-ai-in-bi-report-from-words-ask-the-data-explain-this-number)).
> Narrative summaries, the designer copilot and the dataset helper are built (section 20.14).
Every feature below has a non-AI equivalent that works out of the box.

| Hook | What it does | Guardrails | Without AI |
|---|---|---|---|
| **Report from words** | "Live MTM by desk with a trend and the top counterparties" → a draft report (YAML) | the draft is validated by the report schema and the expression language, opens in the designer as a draft, is never published without a person | the designer's field-to-visual suggestions |
| **Ask the data** (Q&A) | a question → a query over a dataset → a visual | only the dataset's model is sent (names, types, descriptions, statistics), not rows; the query runs with the user's row rules | the plain-words parser Drishti already has, extended to datasets |
| **Explain this number** | why a value moved: contributions by dimension, then a narrative | contributions are computed by Drishti; the model only words them; masked fields never sent | the contribution table alone |
| **Narrative summary** | a paragraph for a page (for subscriptions) | numbers inserted by Drishti from computed results, not generated | none |
| **Designer copilot** | suggest visuals, formats, measures | suggestions are diffs to the draft, accepted one by one | rule-based suggestions |
| **Dataset helper** | propose relationships, descriptions, measures from the schema and statistics | proposals only | profiling (`dataprofile` exists) |

All prompts and answers are audited (with masks applied), rate-limited and costed per user and per workspace;
an administrator can restrict which workspaces and roles may use each hook.

### 15. Distribution: export, subscriptions, alerts, embedding, mobile

- **Export:** a page as PDF or PNG; a visual's data as CSV (later XLSX); within the entry's export policy and row cap.
- **Subscriptions:** a report page by email on a schedule or after a data load, rendered for each recipient with their
  masks and row rules (the outbox and digest machinery exists), with an optional narrative summary.
- **Alerts:** a condition on a measure (`MTM < -15m`, a KPI off target) evaluated on refresh or live; to the bell, inbox,
  email and chat bridges (the alert engine exists).
- **Embedding:** `<drishti-report report="…" page="…">` and `<drishti-visual>` through Drishti Elements (token exchange,
  origin-bound, always masked), with the same container-query responsiveness. **Built:** `<drishti-visual>` is the `visual` attribute of `<drishti-report>` (section 20.6, part 3).
- **Mobile:** every report has an automatic phone layout (visuals stacked by importance) and an optional hand-made one in
  the designer; touch targets of 44 px; the BI page passes the phone sweep.

### 16. Administration and operations

- **Admin → BI settings:** powers per role, workspace creation policy, export defaults, AI hooks on or off per feature,
  Python runner limits and package set, query limits, cache sizes, refresh concurrency.
- **Health:** refresh state per dataset (last success, duration, rows, next run, failures with codes), query latency
  percentiles, runner pool, cache hit rates.
- **Usage:** views per report and user, slow visuals, unused content (candidates to archive).
- **Backups:** workspaces, datasets and reports are documents in the identity database and on disk; included in the existing
  backup and export procedures.
- **Load and memory:** the method of [PERFORMANCE.md, method 9](../admin/PERFORMANCE.md#9-many-users-soak-runs-and-memory-profiling) gains a BI persona (report
  viewers) and the runner's memory budget.

### 17. Architecture

**No new server processes unless they are needed.** The BI code runs inside the existing Drishti server (one package, `com.ash.drishti.server.bi`, with DuckDB embedded) and the console (its BI routes, templates and scripts); the only extra processes are the Python runner's short-lived, sandboxed worker subprocesses (one per call, at most `bi.python.workers` at once: 20.8). When measured load calls for it (section 18), two scale-out options exist, both off by default: the runner as its own service on another host (`bi.python.runner-url`), and more Drishti servers behind a load balancer (PERFORMANCE.md, "Scaling out"). The target architecture is [section 4](#4-query-architecture) and [section 3.5](#35-python-sandboxed-workers-on-the-console-backend). The 2.1 diagram (Perspective, DuckDB-Wasm, Graphic Walker, the module names `drishti-bi-core`, `-query`, `-live`, `-ai`) was replaced and deleted (RUPAKA_2_1_ENGINE.md, in git at tag v2.9.5).

### 18. Performance targets

| Measure | Target |
|---|---|
| BI home page, first paint | < 1 s; no heavy engine loaded |
| Cached visual over 10 M rows (server DuckDB), p95 | < 500 ms; cache hit < 50 ms |
| Report page of 8 visuals, all cached, p95 | < 1.5 s to complete |
| Live visual, source change to screen, p99 | < 250 ms (charts, coalesced); < 100 ms (Perspective grid) |
| Perspective grid sustained updates | 1,000 rows a second per visual without dropped frames below 45 fps |
| Python runner call overhead | < 50 ms for a small table; server-side limits enforced |
| Concurrent BI viewers per server | measured with the capacity method of [PERFORMANCE.md](../admin/PERFORMANCE.md#9-many-users-soak-runs-and-memory-profiling); target 500 viewers on the reference server |

### 19. Non-goals for the first release

DAX compatibility; a desktop authoring tool; paginated (pixel-perfect) report authoring beyond print layouts; Excel
pivot connectivity (an XMLA-like endpoint); R scripts; custom JavaScript visuals (only the vetted catalogue and Python
visuals returning declarative specs); cloud-hosted multi-tenant service; write-back actions (planned, gated: the Rūpaka SRS
section 3.4 pattern, server-side, four-eyes, off by default).

### 20. Build plan

Phases 1 to 5 each have an "as built" subsection below (20.0 is phase 1, 20.2 phase 2, 20.3 phase 3, 20.4 phase 4, 20.5 phase 5: the numbers are kept because code and documents cite them; 20.1, the three stages, follows them). Phases 6, 7 and 9 and the later waves A to D are built too (20.7 to 20.14); there is no phase 8. Each "Not built, and not faked" paragraph below is the state when that part was written: **20.15 is the one current summary and the list of what is still not built**.

| Phase | What | Size | Done when |
|---|---|---|---|
| 0 | **Proof of concept**: Perspective live grid fed by Drishti's live path; masked Arrow out of a server DuckDB query; DuckDB-Wasm slicing it; CSP and size measured | M | a page shows a live pivot at target rates and a cached query under target, with masks proven |
| 0a | **Interactive Sutras** (sections 8.1, 8.2; query by form is built first, in the terminal): Excel-style column filters on every table, then `controls:` and `ctl.*` with URL view state, then cross-filtering | M | a desk filters a trade's cashflows by leg and date from a dropdown, and any table by column filters, by mouse, keyboard and touch. **Built** in the terminal (column filters: delivery 1; controls, URL view state and cross-filtering: delivery 2); the Rūpaka side (slicers on a report) is built (20.3) |
| 1 | **Shell and catalogue**: BI top bar item, `RUPAKA <GO>`, `/bi` home, catalogue, workspaces (personal and shared), powers, audit | M | a published report appears for its audience only |
| 2 | **Datasets and query engine**: dataset YAML, cached and direct modes, the columns and rollup layers (section 7.1), the engine interface, expression language aggregation, compile to DuckDB, row rules, masks, Arrow delivery, caching, refresh by schedule and data load | L | the reference dataset answers every visual of the demo report within targets for three roles with different rows. **Built** (refresh by schedule came with 20.7; the rest of what 20.2 listed with 20.11 and 20.13); the sample reports that answer on it are phase 3 (20.3) |
| 3 | **Reports and the designer**: report YAML, the visual catalogue, filters, cross-filter, drill-down and through, the four panes, YAML tab, preview with data, check | L | a Power BI author rebuilds a supplied reference report without help in under an hour (usability test). **Built** except the usability test and the items listed in 20.3 |
| 4 | **Publishing and governance**: review before publish, versions, endorsement, lineage, impact of dataset changes, pack bundles for environments | M | certified content and lineage shown; deployment by bundle with rollback. **Built**, including bundles inside a pack archive and rollback of an import (20.4, 20.12) |
| 5 | **Streaming BI**: live tables, incremental aggregates, Perspective visuals, coalescing and backpressure, boards as pages | M | live targets met under the LOAD_AND_MEMORY capacity run. **Built**, including incremental aggregates by group (20.11); the LOAD_AND_MEMORY capacity run was not made (20.5) |
| 6 | **Python**: the interfaces, the server runner and sandbox, browser target, manifests and tests, Calc client and PygWalker **Built** (20.8, 20.10, 20.13): the Measure, Visual and Transform interfaces, runner, sandbox, browser target, manifests, shipped tests, Python on boards and in alerts, Vega-Lite output and the Calc client with Graphic Walker; the Source interface, a warm pool and PygWalker itself are not built | M | a Transform, a Measure and a Visual ship in a pack with tests and run on both targets |
| 7 | **Distribution**: export, subscriptions, alerts on measures, `<drishti-report>`, phone layouts | M | a subscription email renders per recipient with masks. **Built** (20.6, parts 1 to 3) |
| 9 | **Analyse**: the analyst workbench (Slice, Data, SQL, Python, Explore, Notes) over masked extracts, save as notebook, promote cells to visuals, measures and Python interfaces | L | an analyst slices a 1 M-row extract, runs a Python cell on it and turns it into a tested Transform, all in the browser. **Built** lean (20.9) and with the browser's own SQL and promotion to a visual (20.13) |

Every phase ships with tests (unit, console, browser at phone width), documentation with real captures, and its numbers
measured, as every Drishti feature does. Phases 0 to 3 are the minimum lovable BI; 4 to 9 make it a Power BI alternative.

#### 20.0 Phase 1, as built

Phase 1 (shell and catalogue) is built; the user guide is [BI_GUIDE.md](../guides/BI_GUIDE.md). **Built:** the **BI** top-bar menu (people with the `bi` power) and the reserved
`RUPAKA` keyword (`RUPAKA <GO>`, `RUPAKA <report> <GO>`, `RUPAKA WORKSPACE`, `RUPAKA CATALOGUE`, `RUPAKA SETTINGS`, any case, with type-ahead; the pack loader and
`pack check` refuse a pack mnemonic named `RUPAKA` or `FORM`); the shell at `/bi`, `/bi/w/<workspace>`, `/bi/catalogue`, `/bi/workspaces`, `/bi/i/<item>` with its own layout
(left rail Home, Catalogue, My workspace, Workspaces, Datasets, Designer, Explore, and from phase 4 Environments; keys `/`, `g h`, `g c`, `g w`, `?`), working at 390 px with 44 px targets in every theme;
workspaces (personal per user, shared with roles viewer, contributor, member, admin; a creation policy; optional review before publish); catalogue entries (title, description,
owner, workspace, tags, audience of roles, users and packs, endorsement promoted and certified, freshness, 30-day views); **items as typed placeholders** (`dataset`, `report`,
`board`, `notebook`) whose YAML body is stored, versioned and published but **not executed**; the lifecycle draft, proposed, published with version history and restore, review
reusing the `approve` power and four-eyes; Home (recent, favourites, certified for you, shared with me) and the Catalogue (search, filters, sort, certified first), with audience and
role checks on the server; storage in the identity database (SQLite or PostgreSQL) and **shipped, read-only content in `drishti.bi.dir`, watched live** (`drishti.bi.watch`: added,
changed and deleted files are picked up with no restart; a bad file keeps its last good version and is reported in Admin, BI settings and on Health); the powers `bi`, `bi-author`,
`bi-steward`, `bi-admin` on roles and Admin, BI settings; the API `/api/v1/bi/**` with token scopes `bi:write`, `bi:approve`, `bi:admin`; an audit event for every change; DRS-9001 to
DRS-9006; `drishti.py bi workspace|catalogue list|get|publish`. **Not built in phase 1 (datasets and the query engine are phase 2, section 20.2):** everything of phases 3 to 9; the Designer and
Explore rail entries are placeholders; "what changed since your last visit" on Home; searching by fields used and filtering by live or cached; the version diff; a workspace as a pack
bundle; dataset lineage and impact; per-entry export rules and classification tags (section 11 controls that need data); `Analyse in BI`. The shipped-file version number counts changes
since the server started and only the current body is kept. (Phases 2 to 5, below, built what this list defers, except what each lists as not built; notebooks are still stored, not run.)

#### 20.2 Phase 2, as built

Phase 2 (datasets and the query engine) is built; the references are [BI_GUIDE.md](../guides/BI_GUIDE.md#30-datasets-the-yaml-the-expression-language-and-the-security-rules) (the YAML, the expression language, security), [BI_GUIDE.md](../guides/BI_GUIDE.md) section 13, the walk-through
[tutorial 10](../tutorials/10-first-bi-report.md), [API_GUIDE.md](../guides/API_GUIDE.md) and [CONFIGURATION.md](../admin/CONFIGURATION.md) (`drishti.bi.query`). Measurements were in the proof of concept's notes, RUPAKA_POC.md section 11, since deleted (in git at tag v2.9.5).

**Built.**

* **The dataset model (section 6).** The YAML with tables (from pack kinds by connector logical name, across packs; `each:` makes an array of the document a table), columns (path, type, format, key, calculated), relationships
  (cardinality, direction, active), measures, hierarchies, `security.rows` per role and `security.masked`, and `rollups`; a parser and validator that report every problem with its DRS code and the path (`DRS-9010` structure, `9011` expression, `9012`
  reference, `9013` reserved and not built). Stored as BI items (phase 1 store: drafts, versions, review; an invalid dataset cannot be published) and shipped in `config/bi/datasets` (watched live). The expression language is Rachana-EL's syntax with
  aggregation (`sum avg min max count distinct ratio percentile`, a condition as the last argument, `[Measure]` references, `in`, `user.<attr>` in rules, date parts), compiled to SQL by its **own** compiler (the Rachana-EL closures evaluate documents, not columns).
* **The model from packs (6.2).** Every kind the reader may open is offered as a table with the connector's promoted columns typed from the data (else from the pack's sample documents); the packs' link graph (`graph.fields`) becomes relationships, across packs;
  relationships are **proposed by value matching** with the match rate, cardinality and a few unmatched values (profiling, no AI), from the promoted columns or, for connectors that promote none, from documents. API `/bi/model/kinds|start|infer`; a minimal read-only
  model page lists tables, relationships, measures, rules and layers (the diagram is the Model view of 20.3).
* **Connector capabilities (6.1).** Optional SPI methods `scan(ScanRequest)` (column batches in the Arrow type model, without Arrow's memory libraries) and `sqlTarget()`; Delta, DuckDB, JDBC table mode and file scan over their promoted columns, DuckDB and JDBC expose a SQL
  target; everything else falls back to record reads with a warning ([CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md#bulk-reads-for-bi-scan-and-sqltarget)).
* **The query engine (7, 7.1).** `AnalyticEngine` with the embedded DuckDB (no extension downloaded at run time: Parquet and the connector, not the `delta` extension). A visual query (fields, measures, filters, dates, order) compiles to one **parameterised** SQL
  statement: names are checked against the model and quoted, **every value is bound**, joins follow many-to-one relationships from the measures' table, the reader's row rules are predicates the query cannot remove, masked columns are replaced by `•••` and never grouped on.
  **Layers:** the **columns layer** (a typed Parquet file per table per business date in the BI store, built from the connector once and again when a data-load signal names the date, only that date, only the datasets that read the kind) and **rollups** (components of additive
  measures per date; **aggregate-aware routing**: rollup, else columns, else the source for `direct`; the answer reports the layer). `direct` tables push the query into a SQL store; a **live** table is a short-lived snapshot of the connector's current state. Results leave as masked
  **Arrow IPC** (the phase 0 writer, moved into the engine package) or JSON for small answers; the **result cache** is keyed by dataset version, query, the reader's row rules and masks and the data generation; **limits** (time, rows, bytes, server and per-user concurrency) are settings with
  `DRS-9022` to `9025`; engine and source failures are `9026` and `9027`.
* **API and pages.** `/api/v1/bi/datasets`, `/{id}`, `/validate`, `/{id}/query`, `/{id}/layers`, `/{id}/refresh`, `/model/*`, `/engine`; the console's Datasets list, Start-from-packs and one dataset's page with a query preview grid. The phase 0 query endpoint now runs its typed
  and rollup layouts through this engine. Three **sample datasets** (`trades-basic`, `trading-risk`, `trading-cashflows`) with a tutorial (phase 5 added the two live ones).
* **Phase 0 adjustments.** Static files are served compressed (gzip, Brotli when installed; the 36 MB wasm is 8 MB). The BI-page style policy is **decided**: strict `style-src 'self'` on every BI page, no `unsafe-inline`; the hash list the POC page needs for Perspective's `<style>`
  elements stays on the POC page only, and phase 5 generated its list on the real pages (`tools/perspective-style-hashes.py`, removed with Perspective in 2.3.0: four hashes, not six) with a Chromium test that fails when a library update adds one. **Perspective retest: done in phase 5** (in RUPAKA_POC.md section 12, since deleted): the second-tab trap does not reproduce on the report page in Chromium 153, Chrome 155, Firefox 155 or WebKit 26.6; the fallback (Drishti's own table) stays in force for a tab where WebAssembly fails.

**Not built, and not faked.** *(Wave 3, 20.7, built what this list first deferred: refresh by schedule, and time intelligence with `running`; the window functions `lag` and `rank` are built in 20.11.)* Python measures **inside a query** (a python visual runs them: 20.8); (queries whose measures aggregate several unrelated fact tables are built: 20.11); filter direction `both`, many-to-many semantics and inactive relationships used by a measure; live tables as an incrementally updated in-memory table fed by the hot path
(built in phase 5, section 20.5); the BI store as a configured connector (a folder today; mount S3 to put it there); engines other than DuckDB (the interface exists); pushdown of scan filters into Delta and file storage; lineage, impact and classification tags (phase 4); the designer, the Model diagram
and reports (built in phase 3, section 20.3). The columns layer is **per dataset table**, not shared per kind as 7.1 sketches (a kind read by two datasets is read twice).

#### 20.3 Phase 3, as built

Phase 3 (reports, the visual catalogue, filters and interactions, the designer, the Model view) is built; the reference is [BI_GUIDE.md](../guides/BI_GUIDE.md#31-reports-the-yaml-the-visuals-and-how-they-behave), the pages are [BI_GUIDE.md](../guides/BI_GUIDE.md) sections 14 to 16, the walk-through
is [tutorial 10](../tutorials/10-first-bi-report.md), the API is [API_GUIDE.md](../guides/API_GUIDE.md) and the settings are `drishti.bi.report.*` in [CONFIGURATION.md](../admin/CONFIGURATION.md).

**Built.**

* **The report model (section 8).** `config/bi/reports/<id>.report.yaml` and `report` items: pages, a 12-column grid (`at: { x, y, w, h }`), visuals with `wells`, filters at report, page and visual level, bookmarks, drill-through pages, a theme
  (`default`, `colour-blind`, `mono`). A parser, a validator against the dataset and a **writer** that produces the YAML the way a person writes it (flow style for `at`, `wells` and filters, the reference's key order, quotes only where needed;
  every sample report round-trips). Problems come back whole, with path and code: `DRS-9030` structure, `9031` a name the dataset or report lacks, `9032` a visual built wrongly, `9033` reserved and not built, `9034` the dataset, `9035` the state
  of a visual query. Publishing a report checks its structure. Stored as BI items and shipped in `config/bi/reports` (watched live).
* **The visual catalogue.** 21 types at phase 3 (30 today, with map, funnel, histogram, box, bubble, python, entity-view, Vega-Lite and the live and Perspective visuals; BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 3): bar, column, line, area, combo, scatter, pie, donut, waterfall, treemap, heatmap (ECharts, vendored, tooltips drawn on the canvas so the strict style policy holds), card, multi-row card, KPI (with a sparkline), gauge,
  table (sort, data bars, conditional colours, key columns that open the entity in the terminal), matrix (subtotals, collapse, totals for measures that add up), slicers (list, dropdown through the shared multi-select, range), text, image, button.
* **Queries.** Each visual is one dataset query built on the server from the visual and the page's state, in a fixed order of authority (report, page, visual, slicers, selections on other visuals, drill-through values, the drill path), run through the
  phase 2 engine as the reader (their row rules and masks); **top-N filters** are resolved first in the context of the other filters; a measure may be filtered (a comparison on the total). `POST /bi/reports/{id}/query`, `/query` for drafts, `/validate`,
  `/format`, `/catalogue`, `/dataset`, `GET /bi/reports/{id}`.
* **Interactions.** Cross-filter and **highlight** (bar and column charts ask twice, the whole and the highlighted answer), drill-down and up along a dataset hierarchy, **drill-through** to a page with the value carried in the URL, **Show the rows**
  (every column of the table behind the visual, masked as for the reader), **Show as a table**, **Open in the terminal**, bookmarks, a business-date picker, filter chips, keyboard operation (marks by arrows, `t`, `r`, `d`, `u`, `Esc`), a phone layout (one
  column, 44 px targets). The page asks **three queries at a time** (the per-person limit of phase 2) and retries a refusal for busy.
* **The designer (section 9).** Fields (tables, columns with date parts, measures, hierarchies, search; drag or button), Visualizations (the catalogue, **Build** wells with chips, reorder and selects, **Format** with theme colours, number formats, conditional
  colours, data bars, slicer mode and scope), Filters (basic with values from the data, advanced, top-N, at three levels), page tabs (add, rename, move, delete, drill-through target), the canvas (drag and resize on the grid with overlaps pushed down, **fully keyboard
  operable**), the **YAML tab** two ways with a strip of suggestions at the cursor, **real data and the query time on every visual**, **Check** (validation plus accessibility, layout and performance warnings), undo and redo (100 steps), save, save a copy
  (shipped content opens read-only), a start page. A field dropped on the canvas makes the best visual (measure: card; column: table) and improves it as more arrive (category and measure: bar; date and measure: line; two categories and a measure: matrix).
* **The Model view (section 6.2).** Tables as boxes with their columns, relationships as lines with `1` and `*` and the direction in the title, drag a column onto another to relate (a form confirms the guessed cardinality), edit cardinality, direction and
  active, remove; **proposals from the data** accepted one by one; positions saved in the YAML under `layout:`; the YAML beside it, rewritten only in `relationships:` and `layout:`. `POST /bi/datasets/validate` now also returns the parsed model.
* **Samples and documentation.** Three sample reports (`trades-overview`, `counterparty-risk`, `cashflow-ladder`; `sample: true`), [BI_GUIDE.md](../guides/BI_GUIDE.md#31-reports-the-yaml-the-visuals-and-how-they-behave), the tutorial's designer chapters with pictures, `BI <report> <GO>` opens the viewer, the rail's
  Designer is live.

**Measured** (a laptop, the generated Delta lake of the tutorial: 750 trades a day, three business days; the numbers are what the page shows under each visual): opening *Trades overview* (8 queries, three at a time) took 487 ms to the last visual on the first open
after the server started (the sum of the queries 18 ms, the slowest 4 ms), 244 ms with every answer from the cache; *Counterparty risk* 281 ms and 212 ms; *Cashflow ladder* 299 ms and 255 ms. Selecting a mark re-asks the other seven visuals: 200 ms to the last
answer. No visual of a sample report reached the one-second hint of Check.

**Not built, and not faked.** Perspective visuals and live grids, pivots and charts (phase 5: the grid and the pivot are built, the Perspective charts and settings panel came with 20.13); the visual `shape` (refused with `DRS-9033`; `entity-view` is built in 20.11; the `python` visual is built in 20.8; `bubble`, `funnel`, `histogram`, `box` and `map` are built in wave 3: 20.7); (the relative-date slicer is built: 20.11); a report over
**several datasets** (built: 20.10); true totals for measures that do not add up (built in wave 3: 20.7); highlight for charts other than bar and column (the others are filtered); capturing a bookmark's slicer
choices from the page (written in the YAML); (a tablet layout and the *Check* that runs expected values are built: 20.11); a schema-completion popup in the YAML tab (a strip of suggestions instead);
the designer's *Explore to visual* (built from Analyse as *promote to visual*: 20.13) and the **usability test** the phase asks for (an hour, a Power BI author, a reference report); embedding (phase 7: built, section 20.6 part 3); a filter direction of `both`, many-to-many semantics and inactive
relationships used by a measure (the engine joins as written, 20.2); several fact tables in one visual's query (20.2). The Model view lays out by a simple rule (the most connected table in the middle) and does not route lines around boxes.

#### 20.4 Phase 4, as built

Phase 4 (publishing and governance) is built; the reference is [BI_GUIDE.md](../guides/BI_GUIDE.md#32-publishing-and-governance-versions-review-certification-lineage-impact-environments), the pages are [BI_GUIDE.md](../guides/BI_GUIDE.md) section 17, the walk-through is [tutorial 13](../tutorials/13-govern-bi.md), the API is [API_GUIDE.md](../guides/API_GUIDE.md) and the settings are `drishti.bi.governance.*` in [CONFIGURATION.md](../admin/CONFIGURATION.md). It extends the phase 1 publish-with-review rather than replacing it.

**Built (sections 10 and 11).**

* **Versions.** Every change of an item's body is a *saved state* (author, time, message, origin) in two new identity tables (`drishti_bi_revision`, `drishti_bi_event`; created on start); the phase 1 *published versions* stay as they were and the published one is pinned: readers see it, authors their draft. History list, side-by-side line diff of any two of `draft`, `published`, `vN`, `rN`, restore as a new draft.
* **Review.** Request review (= propose) tells the reviewers through the inbox the shares and comments use; a **bi-steward** reviews in any workspace, a workspace member with the `approve` power as before; the review page shows the diff, Check, the impact of the change and a data preview answered as the reviewer (their row rules and masks); approve and reject carry comments, kept in the item's review record; four-eyes (`DRS-9005`) holds; the author is told.
* **Certification and endorsement.** Promoted and certified badges in the catalogue, search and Home; certified only by a steward and first in the default sort; **certification falls automatically** when a dataset is published with a removed or renamed field, measure or hierarchy (the dataset itself, and certified reports that use a removed name), with a warning, an inbox notice and a review-record entry. It does not travel in a bundle.
* **Lineage and impact.** Dataset: tables, pack kinds, packs, connectors, and the reports that read it with the names they use; report: dataset, names used, tables behind them. Impact analysis by name (fields `table.column`, measures, hierarchies; a rename is a removal and an addition), used on the review page, on publish and approve, and on import: publishing a dataset change that breaks **published** reports is refused (`DRS-9040`) unless a `bi-admin` forces it; the refusal names the reports the caller may open and counts the rest.
* **Environments.** A BI bundle (one checksummed JSON file) of items with their published versions, sample flags, audience, owner and the datasets their reports read; import is a dry run first, lists create, update, unchanged, conflict and invalid, maps workspaces and owners, refuses the whole import when anything is in the way (`DRS-9041`, `9042`), and needs `bi-steward`.
* **Audit.** Publish, approve, reject, certify, revoke, restore, export and import are in the audit log.

**Not built, and not faked.** (BI content in pack archives, rollback of an import, rename recognition, impact of a changed type or meaning and lineage past the connector were built later: 20.12.) Impact on a relationship or a row rule; lineage for boards and notebooks; threaded comments on a diff line; chains of several reviewers; re-certification schedules; "what changed since your last visit"; the Model view does not yet call the impact of unsaved YAML (the API does). Saved states begin at the upgrade.

#### 20.5 Phase 5, as built

Phase 5 (streaming BI) is built; the references are [BI_GUIDE.md 7.1](../guides/BI_GUIDE.md#71-live-tables-phase-5), [BI_GUIDE.md 13](../guides/BI_GUIDE.md#13-live-visuals), [BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 23](../guides/BI_GUIDE.md#23-boards-report-pages-of-live-tiles), [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#live-bi-a-reports-live-tables),
[BI_GUIDE.md](../guides/BI_GUIDE.md) section 18, the walk-through [tutorial 12](../tutorials/12-live-bi-and-boards.md), the API [API_GUIDE.md](../guides/API_GUIDE.md), the settings `drishti.bi.live.*` and the console's `bi.live.*`, `bi.board.rotate_seconds` in [CONFIGURATION.md](../admin/CONFIGURATION.md),
and the Perspective retest, in RUPAKA_POC.md section 12, since deleted (in git at tag v2.9.5). Codes `DRS-9050` to `9053`.

**Built.**

* **Live dataset tables (section 12).** `mode: live` with `live: { rows, age }` retention; the engine (`LiveTables`, `LiveTable`, `LiveFeed`) reads the kind once, subscribes through the TopicHub (or the named connector), applies changes **by key** to an in-memory DuckDB table in batched
  transactions with the retention, skips rows that did not change, resyncs the listing every 30 s (new entities are followed; a connector that cannot push is read again), counts rows, entities, pushing subscriptions, applied, skipped, evicted and deleted, reports `starting`, `live`, `stale`, `failed`, and closes a table idle for ten minutes. Two implicit columns, `updateSeq` and `updatedAt`.
  **Row rules and masks are applied by the query compiler on every query**, as the reader; the table knows no reader. A dataset's page and `GET /bi/datasets/{id}/layers` say how each live table is doing; `GET /bi/engine` counts them. A table over an array is refused (`DRS-9050`). With `drishti.bi.live.enabled: false` a live table is the phase 2 snapshot.
* **Live visuals.** A report over a live dataset has a stream (`GET /bi/reports/{id}/live`: `hello`, `tick`, `status`; per-server and per-person limits, `DRS-9051`) that reaches the browser over the console's one channel (`bilive:<report id>`). The viewer redraws the page's visuals in place (a chart keeps its instance, a table its scroll position, a card flashes), **coalesced** (one round at
  a time, at most every 250 ms) and **back-pressured** (the server sends the latest state, never a queue); a live indicator with the last update time, **Pause** and **Resume**, and the states **Stale**, **Feed failing**, **Disconnected** and **Paused (tab in background)**; a failed update keeps the old picture marked as old. Cards, KPIs, bars, columns, lines, tables and the rest update this way; a **rolling chart** (`live-chart`) collects a point per update in the browser.
* **Perspective.** `perspective-grid` and `perspective-pivot` over the rows of one table: first read as **masked Arrow**, updates as the rows whose `updateSeq` is above the last answer's (JSON, by key; the whole set again when rows left), Perspective pivoting in the browser; a plain-table fallback when WebAssembly cannot start. **CSP resolved properly**: a report or board page that holds a Perspective visual
  gets, in its own response only, `'wasm-unsafe-eval'` and the SHA-256 of the **four** styles Perspective 3.8.0 writes (`core/perspective_csp.py`); every other BI page keeps `style-src 'self'` and no WebAssembly; the engine's worker is a script of this origin; the hash list was measured on the real pages by `tools/perspective-style-hashes.py` and checked by a Chromium test (all removed in 2.3.0, when Perspective was no longer vendored). The retest: RUPAKA_POC.md section 12, since deleted (in git at tag v2.9.5).
* **Boards.** `config/bi/boards/<id>.board.yaml` and `board` items: pages of tiles, each a visual of a report on the report grid, per-tile **alert colouring** by threshold (warn and crit, in words as well as colour), **kiosk** (full screen, menus hidden) with **auto-rotating pages**, the live indicator and pause, Perspective and rolling-chart tiles; a parser with every problem coded (`DRS-9052`, reserved `notify` as `DRS-9053`); the publish check; the API (`GET /bi/boards/{id}`, `POST /bi/boards/validate`). No external notifications.
* **Samples.** The live datasets `live-trading` and `live-exposure` (over the demo ticker's trades and netting sets), the reports **Live trading desk** and **Live exposure**, the board **Desk wall**; tutorial 12 with real screenshots.
* **Measured** (this machine, the demo source changing 360 of 750 trades about twice a second, so about 800 row changes a second into one live table with queries answered at p50 15 ms and p95 24 ms over HTTP meanwhile; Perspective at 10,000 rows): RUPAKA_POC.md section 12, since deleted (in git at tag v2.9.5).

**Not built, and not faked.**

* **Incrementally maintained aggregates by group** (a change adjusting running sums, counts, minimums and maximums): built for the rollups a dataset declares over a live table (20.11); measures that are not plain stored aggregates are recomputed over the rows on each query and cached per live generation. Heavy continuous aggregation beyond that belongs in a producer ([DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#results-computed-by-a-streaming-producer)).
* **The "live targets met under the LOAD_AND_MEMORY capacity run"**: the done-when of the phase table. No million-row capacity run was made (local data stays at 10,000 rows a day); what was measured was in RUPAKA_POC.md section 12, since deleted (in git at tag v2.9.5).
* **A history of every update of a live table** (append mode): a live table holds current state with retention. A bounded history of its *aggregates* is built (20.12, `live.history`); the rolling chart's points still live in the browser from the moment the page opened. Time buckets (`second`, `minute`) on `updatedAt` are not date parts.
* **Per-visual frame rates** (the design's 4 and 30 a second): one cadence for a report.
* **Perspective charts** (d3fc) and the settings panel are built (20.13); not built: a Perspective visual over several tables, a Perspective visual in the designer's canvas (a note is shown).
* **Notifications** from a tile are built (section 20.6, part 2); `notifications` and `subscriptions` stay refused (`DRS-9053`). The **designer for boards** is built (20.12); interaction between tiles and slicers on a board are not. Boards in lineage and impact analysis; a board item can be put in a bundle, but its reports are not added as dependencies and its body is not checked on import ([BI_GUIDE.md › Publishing and governance: versions, review, certification, lineage, impact, environments, section 7](../guides/BI_GUIDE.md#7-environments-bundles)).
* **Live tables over arrays** (`each`), in `direct` queries, and a Delta, file or JDBC connector that pushes (only the demo source, Kafka and Redis implement `subscribe`; others are read again every 30 s).
* Safari was tested through Playwright's WebKit build (26.6), not Safari itself.

#### 20.6 Phase 7, part 1, as built

Phase 7 (distribution) is built in parts. Part 1 is **export and subscriptions**; part 2, **alerts and board notifications**, follows below it; the references are [BI_GUIDE.md](../guides/BI_GUIDE.md#33-distribution-scheduled-reports-export-subscriptions-alerts-and-embedding), [BI_GUIDE.md](../guides/BI_GUIDE.md) section 19, the walk-through
[tutorial 11](../tutorials/11-bi-that-runs-itself.md), the API [API_GUIDE.md](../guides/API_GUIDE.md#export-and-subscriptions-apiv1), the settings `drishti.distribution.*` and the retry area
`drishti.retry.distribution.*` in [CONFIGURATION.md](../admin/CONFIGURATION.md). Codes `DRS-9060` and `DRS-9061`.

**Built.**

* **Export** of a report, a page or one visual, and of a Sutra view, as **Excel** (a sheet per visual or table-like panel, typed cells, a frozen bold header, an `About` sheet), **CSV**, **PDF** (paged, a running
  header with the title and the as-of date, a footer, no row split by a page edge, a repeated table header) and **PNG**. **No headless browser**: the project has none in production, so the server draws the files itself with the JDK
  (Java2D for the picture; a small PDF 1.4 writer; the `.xlsx` as zipped XML), nothing downloaded and nothing vendored. Masks and row rules are the requesting user's, because every number comes from the same
  engine call the screen makes. Limits are settings (rows, bytes, time, visuals, size).
* **Subscriptions** to a report, a page, a visual or a view, with the view state (slicers, selections, drill path, carried values, the business-date rule `today`, `previous` or `load`; a view's controls and filters),
  on a schedule (`daily`, `weekdays`, `business-days`, `weekly`, `monthly`, each business-day aware with the pack calendar; `hourly`, `cron`) or **after a data load** (the load signal), as the email itself, a PDF or an Excel file, to users and roles
  (never to outside addresses: they have no rights to render with). **Each recipient gets their own render**, with their masks and row rules; a recipient who cannot open it is skipped with a reason. Pause, resume, run now, a history with each
  delivery's status, **retries with the shared retry policy**, and an audit trail.
* **The console**: *Export* on the report bar and the view header, *Subscribe*, **Reports** in the top bar with the scheduled reports and the subscriptions in one list (existing scheduled reports unchanged), *All subscriptions* for administrators; phone-sized.
* **The known gap closed**: an email, or a bridge post, that names a panel uses the **title the sender's view showed** (the filtered title), not the unfiltered one.

**Not built, and not faked.**

* **Embedding** and the hand-made **phone layout** are built (section 20.6, part 3); **narrative summaries** are built (20.14).
* **Pixel-faithful pages**: the PDF and PNG draw the tables and, for a chart, bars of its first measure; colours, heatmaps, scatters and maps are shown as their data. A view is drawn by the snapshot layout (charts included).
* **Pivot tables inside the Excel workbook** (charts are built: 20.14); **a notice in the inbox when a delivery fails for good** (the history and the audit have it).
* **A real-mail test**: tests use a local fake SMTP (GreenMail in Java; a small server in the Chromium test), by design.

##### 20.6, part 2: alerts on measures, fields and board tiles, as built

References: [BI_GUIDE.md › Distribution: scheduled reports, export, subscriptions, alerts and embedding, section 4](../guides/BI_GUIDE.md#4-alerts-on-numbers), [BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 23](../guides/BI_GUIDE.md#notifications-a-tile-tells-people-when-it-turns-red), the walk-through
[tutorial 11](../tutorials/11-bi-that-runs-itself.md), the API [API_GUIDE.md](../guides/API_GUIDE.md#alerts-on-numbers-apiv1), the settings `drishti.alerts.measure.*` in
[CONFIGURATION.md](../admin/CONFIGURATION.md#drishtialertsmeasure--alerts-on-numbers-and-board-notifications). Code `DRS-9062`; `DRS-9053` now refuses only `notifications` and `subscriptions`.

**Built.**

* **An alert on a number**: a visual of a report, a measure of a dataset (with filters) or a field of a Sutra entity; conditions **threshold** (`above`, `below`, `between`, `outside`), **change** (percent or absolute, `rises`, `falls`, `moves`, against the previous look
  or the previous business day) and **no data** (a source has not landed for today's business date by a time). It looks on a **schedule** (the subscription words), **after a data load** (the load signal, after `load-delay`) or, for a source with a live table or a
  pushing Sutra source, **on change after a quiet time** (`debounce`, with a `live-max-wait` ceiling). It is also looked at once when it is saved.
* **Per-person evaluation.** The number is read **as the person it is for**, through the same engine calls as the screen (the report service, the dataset engine, the field masks), so their row rules and masks apply; a masked number is never judged and never
  sent. The state (armed or active, when it last fired, the last number) and the history are kept per person. A pure state machine (`AlertLogic`) decides, with no I/O, and is tested on its own.
* **Hysteresis and cooldown** against flapping; a breach inside the cooldown is held back and fires when it ends if it still holds. Pause and resume (a resumed alert is armed afresh), *Look now*, a preview that saves nothing, and an audit trail (`alert.*`).
* **Delivery** to the **inbox** (an inbox row of type `alert`, with the bell and a toast), to **email** (users only, to the address on their account; never to an address typed into an alert) and to the **chat bridges** (a new route event `alert`; a post
  that names the alert and the condition and holds **no number**, one per alert per cooldown). The history says what each channel did.
* **Board notifications.** `notify:` on a tile's `alert` (and defaults at the top of the board) makes the tile an alert for **every person who may read the board**, each judged on their own number: level `crit` or `warn`, channels `inbox`, `email`, `chat`, a
  `trigger`, a `cooldown`, a `severity`. The board's YAML is the definition; nothing about the definition is stored. `DRS-9053` no longer refuses `notify`.
* **The console**: *Alert me* on every visual and in a view's number dialog (a Sutra field); the **Alerts** page integrates the new list with the older per-entity rules (no second page); the form, the edit page with the history, an *Alerts* tab in the inbox, entries in the **Reports** menu
  and an administrators' overview of everyone's alerts and of the board tiles that notify (`/alerts/all`); phone-sized (no sideways scroll, 44 px targets).
* **Subscriptions refuse outside addresses** altogether (the administrator setting is gone); an old address is skipped at send with the reason in the history.

**Not built, and not faked.**

* **Escalation**, **shared** alerts (a board's audience is the only way an alert reaches more than its owner), quiet hours, an alert on several numbers at once, forecasts.
* (Webhook and SMS channels, retries of a failed alert email, tile hysteresis and narrative summaries are built: 20.12 and 20.14.)
* A subscription **delivery failure** is still not an inbox notice (the history and the audit have it).

##### 20.6, part 3: embedding and phone layouts, as built (phase 7 complete)

References: [BI_GUIDE.md › Distribution: scheduled reports, export, subscriptions, alerts and embedding, section 5](../guides/BI_GUIDE.md#5-embedding-a-report-in-another-web-application) (embedding), [BI_GUIDE.md](../guides/BI_GUIDE.md#14-phone-and-tablet-layouts) (`layouts.phone`), [CLIENT_RENDERER.md › Drishti Elements: the design, section 18](CLIENT_RENDERER.md#18-drishti-report-a-published-report-in-another-web-application)
(the element), the developer guide [CLIENT_RENDERER.md of the guides](CLIENT_RENDERER.md#19-drishti-elements-developer-guide), the walk-through [tutorial 16](../tutorials/16-embed-drishti.md), the API [API_GUIDE.md](../guides/API_GUIDE.md#embedding-a-report-embedv1reports),
the settings `drishti.embed.scopes` (`[embed:report]`) and the console's `embed.lifecycle` in [CONFIGURATION.md](../admin/CONFIGURATION.md). No new code: a refused report is `DRS-8005`, a retired API `DRS-8006`.

**Built.**

* **`<drishti-report>`**, a Drishti Element beside `<drishti-view>`: a **published** report, one page of it (`page`) or one visual of it (`visual`; the `<drishti-visual>` of section 15 is this attribute), in a Shadow DOM with **no iframe**, under a strict host policy (adopted style
  sheets, no inline script or style, nothing written as markup: the element builds its DOM from the report's model and the query results). It signs in as `<drishti-view>` does (the host's token provider, an embed token on every call) and is **always masked**. Slicers, the page's tabs,
  the business date, bookmarks, highlight or filter on a click, **cross-filtering** between its visuals, "show as a table" and the live indicator are the console viewer's, drawn by the same code (`bi-visuals.js`, `bi-grids.js`, `bi-frame.js`). **Live**: a report over a live table follows
  it over the page's one embed connection (`bilive:<report>` on the embed channel), each tick asking the visuals again as the person looking. Attributes: `report`, `page`, `visual`, `filters` (initial slicers, date, selections and mode as JSON), `chrome` (`full` or `bare`),
  `theme`, `live`, `server`, `label`; the property `filters` reads and sets them; events `drishti:loaded`, `filter-changed`, `page-changed`, `masked`, `tick`, `state`, `error`, `token-needed`; methods `selectPage`, `clearFilters`, `refresh`, `reload`, `pause`, `resume`.
* **Admin → Embedding governs which hosts may embed which reports.** The existing allow-list of a host application (`kinds`) takes `report:<id>` (one report) and `report:*` (every report); the new scope `embed:report` opens the three server paths a report needs
  (`GET /api/v1/bi/reports/*`, `POST .../*/query` and `GET .../*/live`; a POST that reads). A host whose list names other kinds only is refused `DRS-8005` for a report; authoring paths (`dataset`, `validate`, `format`, a draft's `query`) are never open to an embed token. Only a **published** report
  is embedded (`DRS-8005` otherwise). Masks, row rules and the three-queries-at-a-time limit are the server's, per call, for the token's user.
* **Per-report phone layouts.** A report may declare `layouts: { phone: { <page id>: { order, hide, span } } }`: at **640 px and narrower** the page shows the visuals in `order` first (the rest follow in the grid's reading order), leaves out those in `hide` (they are **not queried**), and gives a visual
  `span` grid rows of height (1 to 40; the width is the whole screen). A page with no entry keeps today's automatic single column. The viewer (the screen's width), `<drishti-report>` (**the element's own width**, so a report in a narrow card of a wide page uses it) and the designer's preview
  share one file (`bi-phone.js`). The server parses and checks it (`DRS-9031` for a page or visual that does not exist, a repeated or both ordered and hidden visual, a height out of range) and writes it back in the YAML.
* **The designer's Phone tab.** For the page being edited: the visuals in phone order, **drag** a row (or its arrows) to reorder, a **Hide** box, a **Rows** height, and a **390 px** preview drawn to scale beside it; one history step per change, *Back to automatic* removes the entry; a visual or page the layout names that is later
  deleted or renamed is dropped from it by the designer itself. The sample report *Live trading desk* ships with a phone layout.
* **Drishti Elements steps 11 to 15** ([CLIENT_RENDERER.md › Drishti Elements: the design, section 16](CLIENT_RENDERER.md#steps-11-to-15-as-built-2026-10-11)): the foreign-origin host page test, the versioned library with `integrity.json` and the npm package folder with TypeScript declarations, the developer guide and ADR, `<drishti-panel>`.

**Not built, and not faked.**

* **Perspective in an embedded report.** A Perspective grid or pivot needs WebAssembly, which a strict host policy does not give; the element shows the note the designer shows (it is drawn in the console viewer). Drill down, drill through, "alert me", "rows behind this visual" and the Export and Subscribe bar are the viewer's, not the element's.
* **Two elements of one report cross-filtering each other** (each element is its own page), a hand-made phone layout **per theme or per device size other than the phone**, a layout in the board designer (boards are not part of embedding).
* **A published-only check in the server for an embed token's direct calls**: the console refuses an unpublished report's definition; the host application's `kinds` list is the boundary the server enforces.

#### 20.7 BI essentials (wave 3), as built

References: [BI_GUIDE.md](../guides/BI_GUIDE.md#30-datasets-the-yaml-the-expression-language-and-the-security-rules) (section 4.1 time intelligence, 7.2 scheduled refresh), [BI_GUIDE.md](../guides/BI_GUIDE.md#31-reports-the-yaml-the-visuals-and-how-they-behave) (3.1 the new visuals, 3.2 true totals), [BI_GUIDE.md](../guides/BI_GUIDE.md) sections 13 and 15, [tutorial 10](../tutorials/10-first-bi-report.md),
[API_GUIDE.md](../guides/API_GUIDE.md#datasets-and-the-query-engine-apiv1bi-phase-2), the settings `drishti.bi.refresh.*`, `drishti.bi.report.distribution-rows` and `totals-max-rows` in [CONFIGURATION.md](../admin/CONFIGURATION.md). It moves five items from "not built" to built.

**Built.**

* **Scheduled refresh of datasets** (6 and 7.1 sketched `refresh: { schedule }`). A dataset's `refresh:` (and a table's `refresh.schedule`) takes the vocabulary of subscriptions: `daily 06:00`, `weekdays`, `business-days 07:30` with the pack's holiday calendar, `weekly`, `monthly ... business`, `hourly`, a cron (six fields, or the five the sketch showed), and **`after-load`**.
  A scheduler (`DatasetRefresher`, a virtual-thread ticker, `drishti.bi.refresh.tick`) rebuilds the columns and rollup layers of the published datasets that are due, on its own thread so a failure is seen; a failure is retried with the shared retry policy (`drishti.retry.bi.*`) up to `max-attempts` and then left `failed` until the next time. `after-load` runs on the load signal and takes the dataset's
  on-load rebuild over. The dataset page's Refresh section and `GET /api/v1/bi/datasets/{id}/refresh` show the schedule, status, last and next run, rows, time and failures; the state is in memory.
* **Time intelligence** (6: "time intelligence `ytd`, `mtd`, `same_period_last_year`"). `ytd`, `mtd`, `qtd`, `prior_period`, `same_period_last_year` over a date column, and `running`: `ytd(sum(x), table.date)` restricts the aggregate to a window that ends at the query's **as-of date** (the end of its dates); the windows are worked out in Java from the pack's calendar
  (the prior period is the same number of **business days** before; last year's window is pulled in to business days) and **bound as parameters** of a `FILTER (WHERE ...)`; `running` is a window over the grouped rows along the date field of the visual. Validated (`DRS-9011` for a wrong shape, `DRS-9012` for a rollup that lists one), not rollup material, masks and row rules as for any measure.
  (`lag`, `rank` and the other window functions are built in 20.11.)
* **Visuals** (8). `map` (countries shaded by an ISO country code, or points at a latitude and longitude; the country file is **vendored**, Natural Earth 1:110m, countries only, 177 features, 176 KB: no tiles, no CDN, no outside request; served to `<drishti-report>` at `/embed/v1/world-countries.json`), `funnel`, `histogram`, `box`, `bubble`. The histogram and box plot ask the engine for the measure of **every member** of the axis
  (`drishti.bi.report.distribution-rows`) and the server bins or summarises it (equal-width bands; quartiles by linear interpolation, whiskers at 1.5 interquartile ranges, outliers), so the screen, the export (PDF, PNG, Excel, CSV: bars for a funnel, a histogram and a map by country, tables for the rest) and a subscription show one set of numbers. In the visual catalogue (`GET /bi/reports/catalogue`), the designer
  (Charts group, Build wells, Format **Bars**), the viewer and the element; masks apply (a masked measure is blank). A value the map does not hold is named under it.
* **True totals** (8: "true subtotals for measures that do not add up"). A matrix with `subtotals: true` and a table with `totals: true` get, with the answer, the engine's totals for the measures that do not add up (`totals`: one entry per level, as the reader, under the same filters); the page sums only the measures that add up.
* **The sample.** *Counterparty risk* gains a map of the exposure by country, a year-to-date MTM card (`MTM ytd` in `trading-risk`) and a histogram of the trades' MTM.

**Not built, and not faked.** Regions below the country, a tile map or a map service, a choropleth by anything but a country code or name; a histogram with bands of your own; a refresh of an earlier date than the current business date by schedule. (The window functions, the refresh state across a restart and the Refresh now button are built: 20.11.)

#### 20.8 Python in BI (phase 6), as built

References: [BI_GUIDE.md](../guides/BI_GUIDE.md#34-python-in-bi-measures-visuals-and-data-prep-written-in-python) (the reference), [BI_GUIDE.md](../guides/BI_GUIDE.md#30-datasets-the-yaml-the-expression-language-and-the-security-rules) section 4.2, [BI_GUIDE.md](../guides/BI_GUIDE.md#31-reports-the-yaml-the-visuals-and-how-they-behave) section 3.3, [tutorial 14](../tutorials/14-python-and-analyse.md),
[API_GUIDE.md](../guides/API_GUIDE.md#reports-apiv1bireports-phase-3), the settings `drishti.bi.report.python-*` and the console's `bi.python.*` in [CONFIGURATION.md](../admin/CONFIGURATION.md); tests `PythonBiTest`, `PythonBiApiTest`, `test_pyrunner.py`,
`test_bi_python.py`, `test_bi_python_browser.py`.

**Built.**

* **Interfaces (13.1).** A **Measure** (`compute(table, params)`, called once per group with a pandas table of that group's rows, returns a number) and a **Visual** (`render(table, params)`, returns an ECharts option, an SVG or PNG bytes: data, never HTML; `formatter`-type keys that could carry markup are removed, an SVG with script or links is refused). Written as pandas, not as `pyarrow`: the table is
  handed over as Arrow and converted. **Transform** (a Python-made table as a dataset) was built afterwards: section 20.10. **Source** stays out of scope.
* **Manifest (13.1).** A Python item's body: `python` (name), `interface`, `entry`, `inputs`, `packages`, `target` (`server`, `browser`, `both`), `timeout`, `memory`, `output`, `params`, `source`. Parsed and checked by the server (`PythonManifest`, `DRS-9070`) on validate and on publish; the console
  adds the source checks the server cannot make (it parses, defines its entry, imports only what it lists: `POST /bi/python/check`, the item page's button). The decision of 13.1 to keep implementations in `config/bi/python/` as a *package* with *tests* was changed: **a Python item is a BI item** (a file `config/bi/python/<id>.python.yaml`, or a workspace item of kind `python`) so it has versions, review (phase 4
  governance), an audience and appears in the catalogue and in bundles with no new machinery. A dotted name (`risk.measures.pnl_attribution`) became the item's id, file name or title. Tests shipped with an item are built (20.13).
* **In datasets and reports (13.2, 8).** A dataset measure `{ python, inputs, by }` (all of one table); a query that names it is `DRS-9013`, because the SQL engine cannot run Python. A new visual type `python` (catalogue group *python*, wells `columns` and `values`) asks the server for the rows of one table (the Perspective way: the table's own rows with its `id`, at most
  `drishti.bi.report.python-rows`) under the page's filters, masked and row-ruled as the caller, and shows a Python measure as bars, or what a Visual item returns; `format: { python, params, run }`. The server resolves the item for the caller (`POST /api/v1/bi/reports/{id}/python`: a viewer runs the **published** version, someone who edits the item the draft).
* **Server runner (13.2, decision row 5).** Worker subprocesses of the console, **one per call** (not a warm pool), `python -I`, empty environment, a scratch folder, `RLIMIT_CPU` and `RLIMIT_AS` and a file-size limit, a wall-clock kill of the process group, an audit hook (no sockets, processes, loader; writes only in the scratch folder, reads only in the Python installation and the scratch folder), an import guard
  (the manifest's packages, on the console's allow-list, and a short list of safe standard-library modules), a **network namespace** with no interfaces when the machine allows `unshare --user --net` (`bi.python.network_namespace`), a pool of `bi.python.workers` (2) with a bounded wait, input and output size limits. Failures are `DRS-9071` to `9074`; the traceback is for `bi-author` only. The output is derived from the rows the server
  masked for the reader, so it cannot reveal a masked field; the "masked again on the way out" of 13.2 is therefore not needed and **not built**. The sandbox is defence in depth, not a kernel sandbox ([BI_GUIDE.md](../guides/BI_GUIDE.md#34-python-in-bi-measures-visuals-and-data-prep-written-in-python) section 7 lists what is enforced and what is not).
* **Browser target (13.2).** The same item in Pyodide in **Calc's own worker** (`calc-worker.js`, the vendored runtime, loaded on the first browser run), on the JSON the viewer's own query returned; `static/calc/bi_driver.py` runs it. The report page keeps the strict policy: only the worker's response has `'wasm-unsafe-eval'`. `run: auto` asks the server first and falls back to the browser. The browser has numpy, pandas, scipy and statsmodels, not pyarrow.
* **The sample.** *Desk P&L risk (Python)*: the Python measure **Desk P&L volatility** (in `trades-basic`) and a Python histogram of one-day P&L on the trades sample, both items in `config/bi/python/`; the designer offers the visual under *Python*, with Format for the item, *Run on* and the parameters.
* **Measured.** A server call is about 220 to 320 ms on the development machine (a process start and the pandas import): the **< 50 ms** of section 18 is **not met**, and a warm pool is the fix; a first browser run takes about 3.3 s.

**Not built, and not faked.** (Built since: the Transform, 20.10; tests shipped with an item, Vega-Lite, Python on boards and in alerts, and Calc's `drishti_bi` client with Graphic Walker, 20.13.) The Source interface; a Python measure inside a SQL query or a chart's measures; Python in subscriptions and embedded reports (an export of a python visual is its input rows); a warm worker pool and the separate runner service (`bi.python.runner-url`); a kernel-level sandbox; PygWalker itself and notebooks in Calc (13.4); scikit-learn and polars in the default allow-list; the designer's live preview of a python visual (it shows a placeholder).

#### 20.1 Three stages

The work is done in three stages, in this order; each is complete and useful on its own.

| Stage | Scope | Phases | AI |
|---|---|---|---|
| **1. BI** | Everything in this document except section 14, starting with interactive Sutras (8.1): datasets, the query engine and its layers, reports, the designer, workspaces and catalogue, streaming BI, Python interfaces, distribution, the analyst workbench | 0, 0a, 1 to 7 and 9 above | **none**: no feature depends on a language model, and none is built |
| **2. Generative AI driven BI** | Section 14: the provider interface (off by default) and the BI hooks: report from words, ask the data, explain this number, narrative summaries, designer copilot, dataset helper | A1 provider interface, guardrails, audit and cost limits (M); A2 report from words and designer copilot (M); A3 ask the data and explain this number (M); A4 narratives and dataset helper (S) | optional, administrator-enabled; every hook has a non-AI equivalent from stage 1 |
| **3. Generative AI driven UI** | The terminal and BI screens themselves composed from words: a Sutra or a workspace generated from a request and the data's shape, dynamic forms (the Rūpaka SRS's GenUI), conversational navigation, layouts that adapt to the task; all validated by the same schemas and published through the same review | designed after stage 2, from what stage 2 teaches | optional, as stage 2 |

**Stage 1 keeps the seams, not the features.** It defines no AI code, but its designs leave the places stage 2 plugs into:
every report, dataset and Sutra is a schema-validated document (so a generated one is checked like a written one); every
change goes through drafts and review (so nothing generated is published without a person); queries carry the user's row
rules and masks (so a prompt can only ever see what that user may see); and the designer edits a draft as a list of
changes (so suggestions can arrive as diffs).

##### Stage 2 as built (A2 report from words, A3 ask the data and explain this number)

Reference: [BI_GUIDE.md section 29](../guides/BI_GUIDE.md#29-ai-in-bi-report-from-words-ask-the-data-explain-this-number); the layer under it: [AI.md](../admin/AI.md#1-model-providers) sections 7 and 10; tests `BiAiTest`, `test_bi_ai.py`, `test_bi_ai_browser.py` (mock provider only).

* **Where.** `com.ash.drishti.server.bi.ai` (`BiAiService`, `BiAiController`, `BiAiPrompts`, `BiAiProperties`): `GET /api/v1/bi/ai/status`, `POST /draft`, `/ask`, `/explain`. They call the
  layer through `LLMFactory.model()` under the feature names `bi-draft`, `bi-ask`, `bi-explain`, so the policy (masks, audit, limits, roles) applies and the call counts in Admin -> AI. Off with
  `drishti.ai.enabled` (`DRS-4015`); settings `drishti.bi.ai.*` (model, `values`, limits); new code `DRS-4020` (an answer that cannot be used). A personal token needs `ai:use`.
* **The prompt is the dataset model**: table, column and measure names, types and descriptions, hierarchies, the person's words. Never a row, a value, a measure definition, or a field masked for the
  reader. `values: labels-only` (default) keeps it so for the whole flow; `values: aggregates` lets a second call word the **reader's own** unmasked aggregate result.
* **Report from words.** The model is given the grammar (visual types and wells from `VisualCatalogue`, the 12-column grid); its YAML has `dataset:` pinned to the report's dataset and goes through
  the report parser and checker (`BiReportService.validate`), one repair round with the problem list, else `DRS-4020` and nothing shown. The designer's *Describe a report* shows the passing draft
  and puts it in the YAML tab on request; nothing is saved or published (the seam of 20.1: "every report is a schema-validated document, every change goes through drafts and review").
* **Ask the data.** The model answers one JSON query; `QueryRequest.of` and the engine validate and bind it, and run it **as the caller** (`DatasetService.queryItem`: row rules, masks, limits;
  `ask-rows` caps the limit). The visual is chosen by Drishti from the query's shape (card, line over a date, bar, table); the query is always shown.
* **Explain this number.** `BiReportService.base` rebuilds the visual's query under the page's state; lineage comes from the parsed dataset (measure, columns read, table kind and connector);
  the breakdown is the engine's (the visual's own fields, then the text columns of the tables the measure reads; a dimension masked for the reader is dropped); in `aggregates` mode one call words
  it.
* **Not built:** change-over-time explanations; follow-up questions; streaming. (Stage 2's narrative summary for subscriptions, designer copilot and dataset helper are built: section 20.14.)

### 21. Open decisions

| # | Decision | Options | Recommendation |
|---|---|---|---|
| 1 | Where BI documents live | identity database; files in the BI folder; both | **Both**: the BI folder `config/bi` for shipped content (reviewed, versioned in git, never inside a pack), the database for workspace drafts and published versions |
| 2 | The measure language | extend Rachana-EL; adopt a DAX subset | **Extend Rachana-EL**: one closed language, already validated and documented; a DAX mapping table for authors |
| 3 | Streaming grid engine | Perspective; Drishti's own table panel only | **Perspective** for live and large grids (Apache-2.0, WebAssembly), Drishti panels for the rest |
| 4 | Exploration component | Graphic Walker; PygWalker in Pyodide | **Graphic Walker** directly; PygWalker inside Calc for Python users |
| 5 | Python runner host | inside the console process; worker subprocesses of the console; a separate service | **Worker subprocesses of the console, started on demand**: no extra deployment, and a runaway transform never stalls the web process; a separate service only when load measurements call for it |
| 6 | Command keyword for BI | `BI`; `RUP`; `RUPAKA` | **`BI`** (reserved, as is `RUPAKA`, kept as an alias), with `BI WORKSPACE` and `BI CATALOGUE` |
| 7 | Product name in the UI | "BI"; "Rūpaka" | **"BI"** in the top bar, "Drishti Rūpaka" in documentation and marketing |

#### 20.9 Analyse (phase 9, lean), as built

References: [USER_GUIDE.md](../guides/USER_GUIDE.md#analyse-slice-sql-and-python) (the reference), [AI.md](../admin/AI.md#4-server-side-pricing-the-quant-service-proposed) section 16 (the console's `QuantEngine`), [BI_GUIDE.md](../guides/BI_GUIDE.md) section 21, [tutorial 14](../tutorials/14-python-and-analyse.md),
the console's `bi.explore.*` in [CONFIGURATION.md](../admin/CONFIGURATION.md); tests `test_bi_explore.py` and `test_bi_explore_browser.py`.

**Built (a lean slice of the phase).**

* **The page** at `/bi/explore` (the rail's *Analyse*, no longer a placeholder): open a dataset (or, over the route, one report visual's data) as a **masked extract**: the server's dataset query *as the reader* (masks, row rules), the table's own rows with
  their `id`, cut to `bi.explore.max_rows`, rebuilt on every call and never kept. Tabs **Slice**, **SQL**, **Python**; keyboard- and phone-friendly (field chips with R, C, V, F buttons as well as drag).
* **Slice** in plain JavaScript over the extract the page holds (rows, columns, values with `sum`, `avg`, `count`, `min`, `max`, filters).
* **SQL** cells over the table `extract` on the **console**, read-only (an authorizer: one SELECT), with limits on text, time and rows (`DRS-9070`, `9072`, `9073`).
* **Python** cells over `df` with `result` drawn as a table, a chart (ECharts option, SVG, PNG) or a value, plus `quant`, on the phase 6 runner (server target, `bi-author`) or in Pyodide in Calc's worker (browser target); the Analyse page keeps the strict policy.
* **Notebooks** as a BI item kind `notebook` (the kind already existed as a placeholder), body YAML checked by the console (`POST /bi/explore/notebook/check`), saved and versioned through the item API; **Promote**: a Python cell to a Python Measure or Visual draft item, a slice to a report draft
  (matching dataset measures by expression).
* **`QuantEngine`** (`drishti-console/core/quant/`): `price(instrument, **params)` with a stand-in (Black-Scholes, bond price, NPV) and a documented slot for QLRisk (not imported, not modified); `quant.price(...)` in a server Python cell.
* A test that the server's `drishti.bi.report.python-packages` and the console's `bi.python.packages` list the same packages.

**Decisions that differ from the design above.** At first DuckDB-Wasm was not used (it was not vendored), so Slice was JavaScript and SQL ran on the console in SQLite; since 20.13 both run in the browser in vendored DuckDB-Wasm, with those two as the fallback. The
page itself still has no WebAssembly: only DuckDB's own worker compiles it. Python cells are for `bi-author`. The codes are the Python items' (`DRS-9070` to `9074`); no new code.

**Not built, and not faked.** Perspective and Graphic Walker over an Analyse extract (DuckDB-Wasm in the page is built: 20.13); the *Data* and *Notes* tabs; outputs saved with a notebook, scheduled notebook runs, parameters; *Analyse this* from a report visual or a terminal view; cell tests (promotion of a join cell to a Transform: section 20.10); grand totals in Slice; the real quant engine and its sensitivities, scenarios, VaR and exposure.


#### 20.10 Several datasets (composed datasets, Python Transforms, reports over several datasets), as built

References: [BI_GUIDE.md](../guides/BI_GUIDE.md#30-datasets-the-yaml-the-expression-language-and-the-security-rules) section 11 (the reference), [BI_GUIDE.md](../guides/BI_GUIDE.md#34-python-in-bi-measures-visuals-and-data-prep-written-in-python) section 13, [BI_GUIDE.md](../guides/BI_GUIDE.md#31-reports-the-yaml-the-visuals-and-how-they-behave) section 16, [USER_GUIDE.md](../guides/USER_GUIDE.md#analyse-slice-sql-and-python) section 2, [BI_GUIDE.md](../guides/BI_GUIDE.md) section 13,
tutorials [10](../tutorials/10-first-bi-report.md) and [14](../tutorials/14-python-and-analyse.md), [API_GUIDE.md](../guides/API_GUIDE.md#datasets-and-the-query-engine-apiv1bi-phase-2), the settings `drishti.bi.transform.*` and the console's `bi.explore.max_extracts` in
[CONFIGURATION.md](../admin/CONFIGURATION.md); tests `ComposedDatasetApiTest`, `TransformServiceTest`, `SampleDatasetsTest`, `test_pyrunner_transform.py`, `test_bi_explore_multi.py`, `test_bi_multi_dataset_browser.py`.

The question ("more than one dataset in BI, joined, even by Python") is answered at three levels, and the one rule that decides every design choice is **each source is asked as the reader, before anything is joined**.

**Built.**

* **Composed datasets (YAML).** `from: { alias: { dataset, by, measures | fields, key?, filters? } }` and `join: [{ left, right, on, type: inner|left|full, allow-repeat? }]`, plus the dataset's own measures over the joined columns (`[Desk P&L] / [Exposure (EE peak)]`). The checked result is an ordinary `Dataset` with one table, `joined`, whose columns are the sources' grouping columns and measures (typed from the source models; a source measure is also a measure of the dataset, a `sum` of its column). The engine asks each source through its own `QueryService` query as the reader (so the source's row rules, masks and entitlements apply inside it), stages the answers in DuckDB and compiles the dataset's query over the join (`FrameSql`, `QueryService.runFramed`); the sources run before the composed query takes a place of its own (they are queries too). Join keys must be columns of one type (`DRS-9014`); a join that repeats rows is warned about (`DRS-9015`, from each source's grouping columns or its declared `key`); a dataset that reads itself is refused (`DRS-9016`); a join on a column a source masks for the reader is refused (`403 DRS-9021`: a masked column is one constant). What a source masks is masked in the joined table, as are the measures made from it. The result cache is keyed by the version of every source (text and layer generation, recursively).
* **Python Transform (server target).** A Python item with `interface: transform` (`inputs` are datasets; `run(*frames)` or a class `Transform`), and **derived datasets** (`derived: { transform, inputs, params }`, `columns:` declared or learned from the last good frame). `FrameService` asks the inputs as the reader and hands them as Arrow; `TransformService` runs the Transform through `ProcessTransformRunner`, which starts the console's `core/transform_cli.py`, which runs `core/pyrunner.py`'s sandboxed worker (so no new sandbox: the same process, no network, limits and audit hook) with `interface: transform`; the frame comes back as Parquet, is checked (rows, bytes, declared columns, usable names) and is **kept per reader and version of the inputs** (`cache-ttl`, `cache-entries`), with a bounded number of runs at once. `POST /datasets/{id}/transform/run` runs it for an author and remembers the columns. Refresh (`refresh:`) follows the sources' loads and drops what was kept.
* **Reports over several datasets.** A visual's own `dataset:`; the report's `links: [{ fields: [a, b] }]`; each visual is checked and queried against its own dataset; a slicer, a selection, a carried value or a report/page filter reaches a visual of another dataset only through a link (`DatasetSet.translate`), and not at all when none connects them. The viewer indexes each visual's dataset; the designer has Dataset and Links fields.
* **Analyse.** `extracts:` in a notebook; `extract("name")` in a Python cell (the worker reads the further extracts before the sandbox closes) and a table per extract in SQL; *Promote to a Transform* writes a transform draft from a cell that joins extracts.
* **Governance.** Impact analysis sets a change to a source dataset against the composed and derived datasets that name what is removed, and against reports by what each visual reads of the dataset; lineage lists a composed dataset's sources upstream and the dependants below; the Model view draws sources, joins and Transform.
* **Samples, built both ways.** `counterparty-exposure` (the second dataset), `desk-pnl-vs-exposure` (composed), `pnl-vs-exposure` + `desk-pnl-vs-exposure-py` (the Transform and the derived dataset), the report `pnl-vs-exposure` (a slicer and a donut that read the second dataset, a link) and the notebook `pnl-vs-exposure-pandas`, all `sample: true`. A test compares the composed table, the derived table and an independent join of the two sources.

**Decisions that differ from, or settle, the design above.** The Transform's *server target* is run by the **server** starting the console's runner (the server owns the data and the masks, and alerts, subscriptions and refresh need a frame with no browser to ask for it), through a command-line entry that reuses the existing sandboxed runner rather than a second runner. A Transform's inputs are **datasets asked as the reader**, not Arrow tables from the engine, which keeps the one rule. The result is **per reader**, not shared: sharing would need a proof that no reader's masks differ. The composed table is built by staging the sources' answers (bounded by `compose-source-max-rows`) rather than pushing the join into the sources' layers: simple and correct first, at the cost of asking the sources for their whole answer (a filter on the joined table is applied after the join). Source measures are `sum`s over the joined rows, so ratios belong in the composed dataset's own measures.

**Not built, and not faked.** Joins other than on equal columns; pushing a visual's filter down into the sources; rollups and relationships on a composed dataset; a Transform result shared between readers, a Transform run in the browser, and re-running a Transform for every reader on a schedule (a refresh drops what was kept); the **Source** interface; a visual that reads two datasets at once; links with a transformation; the designer's Fields pane and drag-and-drop for a visual's own dataset (its wells are written in the YAML tab); further extracts in the browser target of Analyse.

#### 20.11 The BI long tail (wave A), as built

Nine items that the earlier phases listed as not built, now built; each is described where its users read it ([BI_GUIDE.md](../guides/BI_GUIDE.md#30-datasets-the-yaml-the-expression-language-and-the-security-rules), [BI_GUIDE.md](../guides/BI_GUIDE.md#31-reports-the-yaml-the-visuals-and-how-they-behave)).

* **Window functions in the measure language.** `lag`, `lead`, `rank`, `dense_rank`, `percent_rank`, `moving_avg`, `moving_sum`, `moving_min`, `moving_max` and `cumulative`, with an order column and optional partition columns that are **fields of the visual**. The emitter writes DuckDB window SQL (`... OVER (PARTITION BY ... ORDER BY ... ROWS BETWEEN n-1 PRECEDING AND CURRENT ROW)`) over the grouped rows, so the row rules and filters are already applied (a rank is among the rows the reader may see) and a masked order column masks the measure; the offsets and frame sizes are validated whole numbers, every other value is a bound parameter. The designer's Fields pane badges them. BI_GUIDE.md 4.3. (`DRS-9013` is now only a Python measure named in a query.)
* **Several unrelated fact tables in one query.** When the measures of a query aggregate tables that no many-to-one path joins, the compiler groups the measures by the fact that holds them, writes the ordinary single-fact query for each (its own row rules, filters, masks), and full-outer-joins the answers on the query's fields (`IS NOT DISTINCT FROM`), applying measure filters, sort and limit to the joined rows: no fact is repeated by another's rows. A field a fact cannot reach, a measure that mixes facts, a window measure and a row rule a fact cannot honour are refused. BI_GUIDE.md 3.1. (The existing *Trading and counterparty risk* model reaches netting sets from trades many-to-one, so its exposure measures are still aggregated over the joined trade rows; that model's fan-out is a separate matter.)
* **Incremental live measures.** `LiveAggregate` keeps, for each rollup declared on a live table, the sums, counts, minimums and maximums by group and business date in a small engine table, written in the transaction of the batch that changed the live table. A replaced, deleted or evicted row first leaves its old group (sums and counts move by the difference); a lost minimum or maximum is read again for **that group alone**. The routing that answers a query from a stored rollup reads it, so `meta.layer` is `live` and `meta.rollup` the rollup's name; measures that are not plain stored aggregates, and `layer: columns`, recompute. `drishti.bi.live.incremental-aggregates` switches it off. BI_GUIDE.md 7.1.
* **Refresh state that survives a restart.** The jobs' state and the last `drishti.bi.refresh.history` runs of each are written to `<BI store>/refresh/state.json` after every run and planned time; an interrupted run is shown as such and a time that passed while the server was down runs once at start-up. The dataset page has **Refresh now** (bi-author, audited), the last and next run and **Recent refreshes**. BI_GUIDE.md 7.2.
* **The relative-date slicer.** `format.mode: relative-date` with named windows (`last-N-days|business-days|weeks|months`, `this-` and `previous-` `week|month|quarter|year`, `all`), worked out **on the server** from the report's as-of date and the pack's business-day calendar before the visual's query is built, so a screen, an export and a subscription agree; its `default` applies when no one chose. The `DRS-9033` reservation is removed. BI_GUIDE.md 6.1.
* **Tablet layouts.** `layouts.tablet.<page>` (`order`, `hide`, `span`, `wide`) beside `layouts.phone`; a tablet is wider than 640 px and up to 1024 px, two half-width columns with `wide` visuals across. The shared `bi-phone.js` decides for the viewer, `<drishti-report>` (library 1.3.0) and the designer's new Tablet tab. BI_GUIDE.md 14.1.
* **Expected values.** `tests: [{ visual | measure, by, row, column, filters, date, expect (a number or a range), tolerance, role }]` on a report or a dataset (`ExpectedValue`), run by `ExpectedValueRunner` as the caller or as a role (the caller's own, or any for a bi-steward; the load is the caller's, the query the role's). `POST /api/v1/bi/items/{id}/tests`, the designer's Check and the dataset page show each as pass, fail or error; **publish refuses a failing or unrunnable test with `DRS-9043` unless a bi-admin forces it** (`drishti.bi.governance.refuse-failing-tests`). BI_GUIDE.md 17, BI_GUIDE.md 8.1.
* **The entity view.** `type: entity-view` with a `key` well: when another visual selects one value of that field, the viewer shows the console's own view page of that entity (`/v/<kind>/<id>?embed=1`) in a frame of the visual's size. It is built for the person looking (their masks and rights); the visual asks the dataset nothing and an export leaves it out. BI_GUIDE.md 3.4.
* **Region sample data.** The pack kind `region-economy` of the `world-economy` pack (Australian states and territories and US states, **illustrative rounded figures, labelled as such**) and the sample *Region economics* dataset and report, which drill from the world map to the regions and shade them.

**Not built, and not faked.** A window function in a filter, with the measures of another fact table, or inside a rollup other than a plain `rank`; a measure that mixes two unrelated fact tables in one expression; incremental groups for a measure that is not a plain sum, count, minimum or maximum of a stored column; a large-screen layout; the entity view in the designer's canvas and in an embedded element (a note stands in).

#### 20.12 Governance beyond removal, live history and the board designer (wave B), as built

* **Grain (a correctness fix).** Measures of different tables in one query are each aggregated at **their own table's grain** and joined on the query's grouping, as the several-facts path does, so a measure of the "one" side of a join (netting-set exposure, a desk count) is never repeated by the "many" side's rows. Before, `MTM` (trades) beside `Exposure (EE peak)` (netting sets, which trades reach many-to-one) added each netting set once per trade: 763 M for a sector whose true figure is 59 M. The grain of a measure is the first of its tables that reaches the others many-to-one; measures with the same grain share one single-fact query; a field, filter or row rule that is not reachable many-to-one from a measure's grain table refuses the query (`DRS-9020`) instead of repeating a figure. No shipped sample report puts a trade measure and a netting-set measure in one visual, so no sample figure changed. `GrainTest`, `MultiFactTest`.
* **Renames, changed meaning and type.** `Surface.Offer` now carries the `path`/`expr` and type of each field and the expression of each measure; a removed name whose meaning reappears under an added name is a **rename**, a kept name whose type or meaning changed is an **alter**. The impact says `[renamed to ...]`, lists the published reports that use altered names (shown, not refused), and offers `applyRenames`: `ReportRenamer` rewrites the names a stored report uses (wells, filters, sorts, hierarchy, drill-through; never titles or ids) and `BiRenames` publishes the rewritten text as the report's next version in the same step as the dataset.
* **Import log and rollback.** Every import writes `ImportLog` (one JSON file per import in `drishti.bi.governance.imports-dir`): the earlier state of each item it overwrote (row and published versions) or that it created, and the revision it left. `BiBundles.rollback` puts them back as a new saved state (origin `rollback`), refuses with nothing changed when an item was edited since (a bi-admin may force), and is audited.
* **BI content in pack archives.** `config/bi/<kinds>/` in a pack becomes a bundle (`PackBi`, `BiBundles.packBundle`) and goes through the import: a dry run in the upload check, a real import into the shared workspace `packs` at the deploy (published, the pack as audience, never certified), kept for rollback; a refused import does not undo the deploy. A pack with no `sutras/` folder now passes the Sutra checks as "nothing to lint".
* **Lineage past the connector.** `ConnectorSource` adds, under each table, the settings of its connector file that name the place (table, topic, path, bucket, host ...), with credentials dropped and, for people who are not administrators, the values masked.
* **History of a live table.** `live.history` makes a bounded, time-indexed table of sampled measures (`LiveHistory`, sampled by `LiveTables` on a timer without counting as use), queryable as the dataset table `history` with generated `<measure> history` measures; masks follow the underlying columns, a row rule the history cannot honour refuses the query. The sample *Live trading desk* has a last-hour line of it.
* **Tile alert hysteresis.** `alert.hysteresis` on a board tile: the colour and the notification drop a level only when the value is back inside the threshold by that much (`TileLevel`, `bi-live.js`), the same state machine as a measure alert.
* **The board designer.** `/bi/design/board/<id>`: the grid of tiles with drag, resize and keys, pages, kiosk rotation, a threshold alert (with hysteresis and notify) for each tile, the YAML as a second view (`POST /boards/format`, the writer the report designer uses), Save as a draft and Publish through the item page, so review, impact and audit apply.
* **One temp folder for each process.** `drishti-<pid>` (server) and `drishti-console-<pid>` (console) under `DRISHTI_TMP_DIR` / `DRISHTI_CONSOLE_TMP_DIR`, set as `java.io.tmpdir` / `tempfile.tempdir` before libraries load, cleaned on stop, left-over ones listed on Health (never deleted automatically, since 2.2.0), one helper for every temp file (`TempFiles`, `core/tmpdir.py`) with tests that forbid any other, shown in Admin, Health.

**Not built, and not faked.** (A measure that combines measures of different grains, `[MTM] / [Exposure]`, was a gap here; it is built in 20.13.) Renames of a column with no `path:` or `expr:`; rewriting a composed or derived dataset or a shipped report to a rename; a history that survives a restart or an idle close; per-group history for a measure that is not additive (sum across groups is shown as the total); a visual preview of the data inside the board designer's tiles.

#### 20.13 Python everywhere, Vega-Lite, the browser's own SQL and promotion (wave C), as built

* **A measure that mixes grains (a correctness fix).** A measure whose expression only combines other measures (`[MTM] / [Exposure]`, `[A] / ([B] + 1)`) and whose components have different grains is no longer one aggregate of one base table (which repeated the one-side figure once per many-side row). `SqlCompiler.expandMixed` replaces it by the measures it is made of (those with aggregates of their own, found through any chain of pure combinations); each is aggregated **at its own grain at the query's grouping** by the several-facts machinery of 20.12, the parts are joined, and the expression is worked out **over the joined results** (`SqlEmitter.overJoined` writes each measure name as its part's column). Filters on such a measure and sorts work the same way; a mask on any component masks the result. A measure with aggregates of its own *and* another table's measure in one expression is not split (it has no grain to split by). `GrainTest` (a brute-force check of trades per desk by region: 1, 2 and the twice-that second-level combination) and `MultiFactTest` (the `Mixed` measure of two unrelated facts is now answered, as the two measures divided). The 20.12 "not built" note about it is removed.
* **Vega-Lite.** `type: vega-lite` (group `chart`; wells `axis`, `legend`, `values`; `format.spec`), and a Python visual's `output: vega-lite`. Drawn with vendored Vega 6.4.0, Vega-Lite 6.5.0, vega-embed 7.3.0 and **vega-interpreter** (`ast: true`, `expr: vegaInterpreter`): the expression tree is walked, never compiled, so the page keeps the strict policy; vega-embed's own `<style>` is switched off (`defaultStyle: false`), a loader that rejects every URL, canvas, no actions, no tooltip plug-in. `data.url`, `url`, `loader` are refused at design time (`VegaSpecs`, `DRS-9032`), in the Python worker (`DRS-9073`) and in the page. `bi-vega.js`; no new server endpoint.
* **Tests shipped with a Python item.** `tests:` in the manifest (`PythonTests`: shape checked by the server, `DRS-9070` with the path; inline `input` frames or a dataset `sample`, `params`, `by`, `expect`, `tolerance`; measures, visuals (`kind` and dotted `has` paths) and transforms). The console runs them in the sandbox (`core/pytests.py`): **Check** shows each as passed, failed or could-not-run; **publish and approve through the console** are refused with `DRS-9076` while one fails (a bi-admin may `force`). Because only the console has Python, a call straight to the server's API is not gated (said in the docs, not hidden).
* **Python on boards and in alerts.** A board tile may be a python visual (`bi-board.js` runs it through `bi-python.js`; no tile alert of its own). An alert's measure may be a **Python measure** (`PythonAlertReader`): the server builds the person's rows (the engine applies their row rules and masks), a masked column makes the reading masked and nothing runs, and `TransformRunner.measure` (`ProcessTransformRunner`, the same process boundary a Transform uses: `transform_cli.py` in `measure` mode over `core/pyrunner.py`) answers a number for each group, reduced to one. No new endpoint and no shared secret: the server starts the console's runner itself, as for Transforms (the alternative of the server calling the console over HTTP was built first and removed: a second authenticated door for no gain).
* **Calc's `drishti_bi`.** `query` / `datasets` / `describe` (and `_async` forms) ask the BI engine through the page's `/bi/api/datasets/...` as the person (masks and row rules the server's), returning DataFrames. **`drishti_bi.walk(df)`** shows **Graphic Walker 0.5.3** (vendored, PygWalker's front end; **PygWalker itself cannot be vendored offline**: a Jupyter package that needs a server and the internet) in an `<iframe sandbox="allow-scripts">` whose page has its own policy (`unsafe-eval`, inline styles, `connect-src 'none'`, `sandbox`): the only place the console allows them, and a frame that has no cookie, storage or way to reach the console. The bundle is built from the npm package (`tools/vendor-js/`); its map chart's `react-leaflet` (Hippocratic-2.1) is replaced by a stand-in so none of that code is vendored; the library's service hosts are rewritten to `blocked.invalid`.
* **DuckDB-Wasm in Analyse.** Slice (one `GROUP BY`, finished by the same code as the JavaScript pivot) and SQL cells run in the browser on the extract the page holds, with the server's SQLite and the JavaScript pivot as the fallback (this was removed in 2.3.0 with DuckDB-Wasm: not installed, `bi.explore.browser_engine: server`, a start slower than `browser_start_seconds`, further extracts). The **page keeps the strict policy**: only DuckDB's worker compiles WebAssembly, under the worker policy of that one file; after the load `enable_external_access = false` and `lock_configuration = true`, one read-only statement, a runaway query has its worker ended. `bi-explore-duck.js`; a Chromium test compares the two engines' tables and SQL results.
* **Promote to a report visual.** `core/promote_report.py`: a slice (with its filters) or a SQL cell (read for plain columns, `GROUP BY`, `sum/avg/count/min/max`, simple conditions; the rest named in the notes) becomes a `matrix`, `table` or `card` in a new report draft or **added to an existing draft** (read by the server's checker, written again by the designer's writer, saved as the draft). A value is a dataset measure only when one is exactly that aggregate.
* **Perspective charts and the settings panel.** `perspective-chart` (the d3fc plugin; `format.plugin`) and a **Settings** button on every Perspective visual; **Save this view into the report** writes the viewer's state into the draft as `format.view` (`plugin, plugin_config, columns, group_by, split_by, aggregates, sort, filter, expressions, columns_config`; validated). A page that holds a chart gets the two more style hashes the plugin writes (`CHART_STYLE_HASHES`); the open panel sets about a dozen `style` attributes the policy refuses (`style-src-attr`), which are logged and harmless.
* **Codes.** `DRS-9076` (a Python item's tests failed; console), `DRS-9077` (a Vega-Lite specification not drawn; in the frame).

**Not built, and not faked.** A measure with aggregates of its own beside another table's measure in one expression is still one aggregate (use measures); Vega-Lite and Python visuals in `<drishti-report>`; a Python tile's threshold alert (use an alert on the measure); a gate on a publish made straight to the server's API (the sandbox is the console's); Python alerts are not cheap (a process for each person and look: prefer a schedule or a load to live); Graphic Walker's map chart; Graphic Walker over an Analyse extract or a report visual; a further extract in the browser SQL engine; a style-attribute-clean settings panel (the viewer sets inline sizes).

#### 20.14 AI, distribution and embedding (wave D), as built

* **Narrative summaries.** `narrative: true` on a subscription. `SubscriptionService.Narrator` (implemented by `AiNarrator` over `BiAiService.narrate`) is asked per recipient with the facts of **that recipient's own render** (`AiNarrator.facts`: title, as-of, each section with the columns masked for them left out, at most `narrative-rows` rows), through the LLM layer under the feature `subscription-narrative` and as the recipient, so the AI policy's masks, roles, limits and audit apply; `drishti.bi.ai.values` must be `aggregates` (else the mail is sent without it, the delivery records `narrative: skipped: …`). Refused at save while the AI layer is off. The mock provider is the test double.
* **Designer copilot and dataset helper** (`BiAiCopilot`): `POST /bi/ai/suggest` (the draft machinery of report-from-words with a suggestion prompt, run through `reports.validate`; each visual comes back as the YAML `ReportYaml.visual` writes; taken ids are renamed), `explain-checks` (the checker's problems paired with explanations and fixes; no model call when there is no problem) and `dataset-helper` (relationships of the same type across tables, measures that read only known columns, descriptions for undescribed columns; each checked against the model). Nothing is applied by the server: the designer adds a visual with `D.change` (undoable), the Model view writes accepted items into the YAML.
* **Charts in Excel.** `XlsxCharts` writes DrawingML (`xl/drawings`, `xl/charts`, the sheet's relationship and content types) for a chart section (`ExportDoc.Section.kind` carries the visual type): bar, column, line, area, pie, doughnut; masked columns are not series; first column the category (a date column a date axis); at most 500 points and 6 series. `XlsxChartsTest` parses every part as XML and (with `-Ddrishti.test.soffice=true`) converts the workbook with LibreOffice.
* **Alert delivery retries** (`DeliveryRetry`, the shared `RetryPolicy` area `alerts`, `drishti.alerts.measure.max-attempts`): the first attempt is made at once, later ones on virtual threads that rewrite the history entry (`email: sent (attempt 3 of 4)`, `emailAttempts`); permanent failures are not retried; pending retries are in memory only.
* **Webhook and text channels** (`com.ash.drishti.server.notify`): `NotifySettings` (`drishti.notify.*`), `NotifyChannels` (the JDK HTTP client with the connectors' TLS and proxy settings, no redirects, no URL in a message; webhook HMAC-SHA256 signature over `timestamp.body`; an SMS gateway described by templates), `PhoneBook` (the person's own number in their preferences), `GET /notify/options` and `PUT /me/phone`. Alerts: `delivery.webhooks`, `delivery.sms`; subscriptions: `webhooks`, `sms`; a webhook is one post per alert per cooldown / per run and holds no figure; a text holds a title and a link (an alert's one-line message only with `sms.include-values`). **Escalation is not built.**
* **Embedding.** Elements 1.4.0 adds `<drishti-about>` (the sanitised About body, scope `embed:about`) and `<drishti-search>` (scope `embed:search`: `GET /api/v1/embed/search`, the type-ahead masked and cut to the application's kinds, `limits.searches-per-user-per-minute`), `core/element_api.py` and the guide's tables regenerated, the package rebuilt; React 18, Vue 3 and Angular 18 sample host pages (`docs/guides/examples/embed/`, framework builds fetched by `fetch-vendor.sh`, never committed).
* **Codes.** None new: the AI hooks keep `DRS-4015..4020`, subscriptions `DRS-9061`, alerts `DRS-9062`, embedding `DRS-8001..8006`.

#### 20.15 As built at 2.1.0, and what is still not built

This is the one summary at 2.1.0. Since then (2.3.0, wave B) Perspective and DuckDB-Wasm were removed: 2.1 Perspective visuals are drawn as Drishti's grid of the server's answer, and Analyse's Slice and SQL run on the console and the server ([VENDOR.md](../../drishti-console/web/static/vendor/VENDOR.md)). Sections 20.0 to 20.14 are the record of how each part was built; a "Not built, and not faked" paragraph in any of them is the state at the time it was written, and where a later section built the item the paragraph says so. Where they differ, this section is right.

**As built.**

| Part | Built | Reference |
|---|---|---|
| Shell and catalogue (phase 1) | BI menu and `BI` keyword, workspaces with four roles, typed items (`dataset`, `report`, `board`, `notebook`, `python`), versions, review with four eyes, endorsement, audit | [BI_GUIDE.md](../guides/BI_GUIDE.md) |
| Datasets (phase 2, waves A and B) | dataset YAML, expression language, cached, direct and live tables, row rules and masks, time intelligence, **window functions**, **several unrelated facts in one query**, **grain-correct measures** (also a measure that combines measures of different grains), scheduled refresh with **state that survives a restart** and Refresh now, composed and derived datasets, a Python Transform | [BI_GUIDE.md](../guides/BI_GUIDE.md#30-datasets-the-yaml-the-expression-language-and-the-security-rules) |
| Reports and the designer (phase 3, waves A, C) | 30-odd visual types including map, funnel, histogram, box, bubble, entity view, **Vega-Lite** and **Perspective charts**; filters, slicers including the **relative-date slicer**, cross-filter, drill; designer with a phone and a **tablet** tab; **expected-value tests**; the Model view | [BI_GUIDE.md](../guides/BI_GUIDE.md#31-reports-the-yaml-the-visuals-and-how-they-behave) |
| Governance (phase 4, wave B) | saved states and diffs, review, certification, lineage (past the connector), impact including **renames and changed type or meaning**, bundles, **BI in pack archives**, **import rollback** | [BI_GUIDE.md](../guides/BI_GUIDE.md#32-publishing-and-governance-versions-review-certification-lineage-impact-environments) |
| Streaming (phase 5, waves A and B) | live tables kept by key, **incremental live measures**, a **history of a live table**, Perspective grid, pivot and chart with the settings panel, boards with **tile hysteresis** and a **board designer**, kiosk | [BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 23](../guides/BI_GUIDE.md#23-boards-report-pages-of-live-tiles) |
| Python (phase 6, wave C) | Measure, Visual and Transform interfaces, sandboxed runner, browser target, **tests shipped with an item**, **Python on boards and in alerts**, Vega-Lite output, Calc's **`drishti_bi`** and **Graphic Walker** | [BI_GUIDE.md](../guides/BI_GUIDE.md#34-python-in-bi-measures-visuals-and-data-prep-written-in-python), [USER_GUIDE.md](../guides/USER_GUIDE.md#calc-python-on-any-view) |
| Distribution (phase 7, wave D) | PDF, PNG, Excel (with **real charts**) and CSV exports, subscriptions per recipient with optional **narrative summaries**, alerts on measures with **retries**, **webhook and text** channels, `<drishti-report>`, **`<drishti-about>`**, **`<drishti-search>`**, React, Vue and Angular sample pages | [BI_GUIDE.md](../guides/BI_GUIDE.md#33-distribution-scheduled-reports-export-subscriptions-alerts-and-embedding), [CLIENT_RENDERER.md](CLIENT_RENDERER.md#19-drishti-elements-developer-guide) |
| Analyse (phase 9, wave C) | masked extracts, Slice and SQL in the browser (**DuckDB-Wasm**), Python cells, notebooks, **promotion to a report visual**, several extracts | [USER_GUIDE.md](../guides/USER_GUIDE.md#analyse-slice-sql-and-python) |
| Stage 2, AI | report from words, ask the data, explain a number, **designer copilot**, **dataset helper**, narrative summaries; off by default, under the AI policy | [BI_GUIDE.md section 29](../guides/BI_GUIDE.md#29-ai-in-bi-report-from-words-ask-the-data-explain-this-number) |
| Operations | one temp folder per process; the multi-day type-ahead index with disk segments and reload | [OPERATIONS.md](../admin/OPERATIONS.md) |

**Still not built, and not faked.** Nothing below is hinted at in the product as working.

* **Python:** the **Source** interface; a Python measure inside a SQL query or a chart's measures; Python and Vega-Lite visuals in subscriptions and in an embedded report; a threshold alert on a Python tile (alert on the measure instead); a **warm worker pool** and the separate runner service; a kernel-level sandbox; PygWalker itself (Graphic Walker is its front end) and notebooks in Calc; R.
* **Analyse:** a *Data* tab and *Notes* cells; million-row extracts; outputs saved with a notebook, scheduled notebooks, parameters; *Analyse this* from a report visual or a terminal view; Perspective and Graphic Walker over an extract; further extracts in the browser engine; the real quant engine.
* **Datasets and reports:** joins other than on equal columns in a composed dataset, and a visual's filter pushed down into the sources; a visual that reads two datasets at once; a window function in a filter or a measure that mixes aggregates of its own with another table's measure in one expression; the `shape` visual; tile maps and regions below the first level; a large-screen layout; a usability test of the designer; a schema-completion popup in the YAML tab; other engines than DuckDB; the BI store as a configured connector; classification tags.
* **Live and boards:** interaction between tiles, slicers on a board and per-tile refresh; live tables over arrays and in `direct` queries; a history of every update (a sampled history of measures is built); a history that survives a restart; a Perspective visual over several tables or in the designer's canvas; a data preview in the board designer's tiles; the LOAD_AND_MEMORY capacity run at a million rows.
* **Governance:** threaded comments on a diff line; approval chains of several reviewers; scheduled re-certification; impact on boards and notebooks, on relationships and row rules; renames of a column with no `path:` or `expr:`; "what changed since your last visit" on Home; searching the catalogue by the fields a dataset uses.
* **Distribution and embedding:** **escalation** of an alert; alerts shared between people, quiet hours, an alert on several numbers, forecasts; pixel-faithful PDF and PNG; Excel pivot tables; an inbox notice when a delivery fails for good; webhook or text channels for a board tile; drill, alert and export chrome, Perspective and two-element cross-filtering in an embedded report; Elements step 15 (workspaces on `<drishti-view>`, a SharedWorker).
* **AI:** "why did it move" between two dates, follow-up questions that remember the last, streaming answers, a BI hook that calls the MCP client, and stage 3 (a generated UI).

## 17. BI runs: every BI query as a bounded, visible job (built in 2.6.7)

Status: **built and released in 2.6.7** (2026-10-21; the steps as built are in section 18.2b, the console contract as built in 14.5).
Decided by the product owner on 2026-10-20. Since 2.7.0 run records live in the content store
([DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#5-the-content-store-user-content-every-node-reaches) section 9), so every Drishti\*net member sees the same runs. Builds on this document (sections 4.1 the pipeline, 6.1 caches, 6.3 concurrency, 6.5 horizontal scale, 6.7
cost controls), [section 16](#16-rupaka-the-original-design-and-every-phase-as-built) section 7 (the query engine), [CLUSTER_DEPLOYMENT.md](../admin/CLUSTER_DEPLOYMENT.md#5-super-scale-500-gb-of-json-a-day-for-seven-years-design-not-built) (S4 products and rollups, S5 slices
and owners, S6 split queries), [CLUSTER_DEPLOYMENT.md](../admin/CLUSTER_DEPLOYMENT.md#1-drishtinet-many-servers-one-drishti) (several servers), [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#6-the-one-drishti-database-design-not-built) (where relational state
lives) and the statement bursting of [BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 24](../guides/BI_GUIDE.md#24-statements-paginated-reports-page-exact-one-per-reader), whose per-recipient masking this reuses.

### 1. The decision

The product owner, 2026-10-20:

1. **Super scale is the architecture at every data size.** There is one code path. On a laptop one node owns every slice; on a
   cluster many nodes own a share each. Heavy external engines (Amazon Athena, Trino, Spark) are optional plug-ins the planner *may*
   use and are never required.
2. **Every BI query goes through one asynchronous pipeline:** submit → plan (size estimate) → admission → run → store the result →
   notify. The console waits briefly (about 2 s, a setting). A run that finishes within that time is drawn inline exactly as today and
   no job is visible. A longer one becomes a visible job ("Running: about 4 min, we'll notify you") and the person keeps working.
   A dashboard's slicers, cross-filters and drills stay instant: products, rollups and cached layers answer them, and they never
   create a visible job. Live (ticking) views stay streams, not jobs.
3. **Three execution tiers, chosen by the planner from metadata only.** The planner reads rows per day per table from the Delta log
   or manifests, the days asked and which products exist. It never scans to estimate.
   * *interactive*: bounded work (one date, one entity, or a product covers the question);
   * *known heavy*: answered from products and rollups written at load (S4) or by scheduled refresh;
   * *ad-hoc heavy*: a background job, split over nodes (S6) or sent to an optional distributed engine. The person sees the
     estimate and confirms it above a threshold.
4. **A Runs tab in the BI Workspace**, with a count on the bell when a run finishes. For each run it shows the report or question,
   who ran it, its parameters and filters, the as-of date and data version, the status (queued, running with progress, done, failed,
   cancelled), the duration, the rows and the estimate. The actions are open the result, re-run (on the same data or newer data),
   cancel, export, schedule, and promote to a dataset snapshot or a report. The same actions are in the CLI
   (`drishti.py bi runs …`) and the API.
5. **Rules.** A result is keyed by query fingerprint plus data version and reused across users with the same rights. Masks hold:
   a result is computed as its runner, and a shared run is re-masked or recomputed for each recipient, as bursting does.
   Results are kept 30 days unless promoted, and Admin shows the storage they use. Fairness comes from per-user queue limits and
   per-server concurrency, with heavy runs in the background class so they never slow interactive users. Estimates are honest.
   Cancelling a run frees its resources at once. After a server restart, every queued or running job is resumed or marked failed with
   a clear reason, never lost without a word. In a cluster a job runs on the node or nodes that own its data, and any console or
   server shows its status.
6. **This fixes a class of problem.** QA round 3's P1 (QA3A-01: unbounded work in the request path ran a server out of memory)
   becomes a bounded queue.

Section 2 explains what in today's code these decisions meet, and what had to change because of it.

### 2. What exists and what is new

#### 2.1 Today's query path

```
 console (bi-report.js, bi-report-board.js, Analyse, the SQL editor)
   │  B.enqueue (three at a time; a 429 retried 8 times)   →  /bi/api/... (routes/bi_routes.py proxy, waits up to 35 s)
   ▼
 drishti-server
   BiReportController  POST /api/v1/bi/reports/{id}/query     ┐
   MqController        POST /api/v1/bi/mq                     ├─► MqService / BiReportService / SqlService
   SqlController       POST /api/v1/bi/sql                    ┘        │ Planner → LogicalPlan (hash), SecuritySignature, ResultCache (L1)
                                                                       ▼
                                                              QueryService.query
                                                                 cache (Caffeine, cacheKey)
                                                                 Admission.enter(user, model, WorkClass, 2 s)   DRS-9025
                                                                 run(): Layers.columns (TableBuilder builds missing dates from the source)
                                                                        estimate(...)                           DRS-9029
                                                                        Layers.rollup / SQL on DuckDbEngine (watchdog, 30 s)  DRS-9024
                                                                 cache.put
```

The request thread does all of it, start to end, and the browser holds the connection open.

#### 2.2 What each piece gives this design

| Piece (real class or file) | What it does today | How BI runs uses it |
|---|---|---|
| `BiReportController`, `MqController`, `SqlController`, `StatementController` | synchronous endpoints; the answer shapes of BI_MODEL_SPEC 5.2 | kept, shapes unchanged. They submit to the pipeline and wait for it (section 6) |
| `BiReportService`, `MqService`, `SqlService` | every front end ends in one `LogicalPlan` (BI_DESIGN 4.1) | the plan hash (`Canonicalizer.hash`) is the run's fingerprint |
| `QueryService` | cache, admission, layers, SQL, limits | becomes the run's *executor* for the local engine; its `estimate` moves before any layer build (2.3) |
| `Admission` | slots (`drishti.bi.query.max-concurrent`, 8), a person's share (`max-concurrent-per-user`, 3, interactive work only), a model's share (`drishti.bi.exec.per-model-share`, 0.5), work classes `INTERACTIVE` > `STANDING` > `BACKGROUND`, a wait (`admission`, 2 s) then `DRS-9025` | the one gate. Gains a cap per class so background never holds the slots interactive work needs (section 10) |
| `QueryContext` (`WorkClass`, `budgetRows`, `budgetMillis`) | the class and budget of the work on a thread | a run sets `BACKGROUND` and its budget; the planner reads it |
| `ExecProperties` (`drishti.bi.exec.*`) | `interactive` 30 s, `background` 300 s, `max-estimated-rows` 200 M, `max-fragments` 8 per connector, `node`, `nodes` | the time limits per class; `max-estimated-rows` stays the refusal for synchronous callers, while an asynchronous caller above it gets a run (section 6) |
| `Layers`, `TableBuilder` | Parquet partitions per dataset, table and date under `<data>/bi-store`, each with `meta.json` (rows, signature, data version) | the planner reads `meta.json` without building (new `Layers.peek`, section 4) |
| `DuckDbEngine` | one in-process DuckDB, `memory-limit` 768 MB, `threads` 2, a watchdog per statement | an interactive engine as today, plus a second instance for runs (section 10.3) |
| `ResultCache` (L1), `SecuritySignature` | answers keyed by plan hash + signature + generation, in memory | stays the hot tier; the result store (section 8) is the durable tier under it |
| `SourceGateway.dataVersion`, `SourcePlugin.dataVersion(kind, date)` | a cheap version of one date's data (Delta: the date's files stamp) | the data version in a result's key |
| `DatasetRefresher` | scheduled and after-load refresh of columns and rollups; state in memory | its rebuilds become `BACKGROUND` work under admission (2.3); the *known heavy* tier relies on them |
| Rollups (`Rollups`, `rollups/<dataset>/<name>/date=…`) | per-date aggregates the optimiser matches | the *known heavy* tier |
| `LiveTables`, `GET /reports/{id}/live` (SSE), `StandingQueries` | live tables and ticks; a tick carries no data and the client asks again | unchanged; live questions are never stored or made visible jobs |
| `StatementService`, `SubscriptionService` | a statement or report rendered per recipient, as that recipient | the pattern for sharing a run (section 9); scheduled sends become background runs |
| `SubscriptionSchedule` | `daily 07:00`, `business-days 07:30`, `monthly last 18:00 business`, `after-load TRD` | the schedule language of a scheduled run |
| `InboxService`, `InboxHub`, `BiNotifier.tell` | the inbox and the bell; rows are pointers, never data | a finished run tells its runner (type `bi-run`) |
| `BiExporter`, `ViewExporter`, `XlsxWriter`, `CsvWriter` | exports of views and reports | export of a run's result |
| `ShardDirectory` | rendezvous hashing of a model key over alive nodes | the node that runs a model's heavy runs (section 13) |
| identity schema files `drishti-identity/src/main/resources/db/schema-sqlite.sql`, `schema-postgres.sql` | every relational table Drishti owns; applied idempotently at start | two new tables, `drishti_bi_run` and `drishti_bi_run_schedule` (section 3.4) |
| `drishti-console/routes/bi_routes.py` (`_ALLOWED`, `QUERY_WAIT_SECONDS` 35 s), `web/static/js/bi-lib.js` (`B.api`, `B.enqueue`, `MAX_PARALLEL` 3) | the console's BI proxy and request queue | gains `runs`, the async request header and the run card (section 6) |
| `drishti-console/web/templates/bi/workspace.html` | the workspace page, sections *Items* and *Members* | becomes three tabs: *Items*, *Runs*, *Members* (section 7) |

#### 2.3 What the real code forced

The decisions meet six facts in the code. Each one changes the design here, and the build fixes each one first.

| Fact in the code | Consequence for the design |
|---|---|
| `QueryService.run` builds the layers (`Layers.columns`, which reads the source for every missing date) **before** `estimate` checks the cost. The "cost budget before anything runs" runs after the most expensive part. | The estimate moves in front of every build and reads metadata only (`Layers.peek`, `SourcePlugin.rowEstimate`). This is step R3 and the core of "never a scan to estimate". |
| No `SourcePlugin` reports row counts. `dataVersion` exists; a row count does not. | New SPI `SourcePlugin.rowEstimate(kind, date)` (default empty). Delta answers from the add actions of the date (`numRecords` in their statistics, else file bytes ÷ a learned bytes-per-row), and Iceberg from manifest `record_count`. Other connectors answer from the last built partition's `meta.json` or from history (section 4.2). |
| `WorkClass.BACKGROUND` is declared and **never set** by any caller. `DatasetRefresher` calls `QueryService.refresh`, which never enters `Admission`. `Admission` sorts waiters by class but gives every class the same slots, so eight 300-second background queries would hold all eight slots and every interactive query would wait 2 s and get `DRS-9025`. | `Admission` gains a cap per class (`background-share`, `standing-share`). Refresh and runs enter as `BACKGROUND`. Interactive work keeps a reserve that background work cannot take (section 10). |
| `DuckDbEngine` is one DuckDB instance with one `memory_limit` (768 MB) and `threads` (2) shared by every query. A heavy aggregate that spills slows every interactive query on the node. | Runs execute on a **second DuckDB instance** with its own memory limit and threads (`engine.memory-limit`, `engine.threads`). Interactive work keeps its own. |
| `GenerationBus` generations are per process and start again at every start. | A durable result is keyed by the **data version vector** (`dataVersion` of every table and date read), not the generation. The L1 cache keeps using generations. |
| `ShardDirectory.cluster(...)` is called only in `TwoNodesTest`; production never wires model ownership to Drishti*net. S5 (slices) and S6 (split queries) of SUPER_SCALE are designs, not built. No external engine adapter exists. | 2.6.7 wires `ShardDirectory` to Drishti*net membership for runs (R7). An ad-hoc heavy run executes on the model's owning node through the local engine. *Split* (S6) and *external* engines are slots behind a `RunEngine` interface that 2.6.7 defines but does not fill (section 5.4). |

Four smaller facts also shaped the design:

* **"Dataset snapshot" does not exist as a stored result.** `snapshot` is a time mode of `DatasetDef.Time`. Promoting a run therefore
  adds a table source type, `run` (`DatasetDef.Source.RUN`), that reads a promoted result's Parquet (section 9.4).
* **The one Drishti database is item 0 of write-back (2.7.0 when this was written, now 2.8.0)**, not built yet. Run records cannot wait for it, and DATA_ARCHITECTURE.md forbids a feature
  opening its own SQLite file. They go into today's identity database through its two schema files, as new tables (which needs no
  migration); DATA_ARCHITECTURE.md's move carries them with everything else.
* **Synchronous callers exist**: `drishti.py bi mq`, the Python client, Elements, subscriptions and the console. They expect `200`
  and today's body. The asynchronous answer is opt-in with the standard `Prefer: respond-async` header (RFC 7240). Without it an
  endpoint waits as today.
* **QA3A-01 was a load-signal storm, not a BI query.** Sixty data-load signals each started a full `derived pages` pass. Virtual
  threads parked in `DeltaSourcePlugin.doc` on `slots.acquireUninterruptibly()` after their callers had timed out, and the backlog
  ran the heap out. Runs bound BI query work and add the rule that *work whose caller is gone is cancelled* (section 11), including
  interruptible waits for connector slots. The load-signal path needs the same coalescing. That is a 2.6.6 fix and is noted, not
  designed, here.

### 3. The pipeline

#### 3.1 The flow

```
   submit ──► plan ──► admit ──► run ──► store ──► notify
     │          │         │        │        │         │
     │          │         │        │        │         └─ inbox row (type bi-run) + bell, only for a visible run
     │          │         │        │        └─ Parquet under <data>/bi-runs/results, keyed (section 8); never for live
     │          │         │        └─ the chosen RunEngine: local DuckDB (interactive engine or run engine), split, external
     │          │         └─ RunQueue (per user, per server) then Admission (class caps, slots, person and model shares)
     │          └─ RunPlanner: fingerprint, data version, reuse lookup, estimate, tier, engine, confirm?
     └─ RunService.submit(question, caller, options) → a Run handle (in memory) and a future
```

`RunService` (new, `com.ash.drishti.server.bi.runs`) owns the flow. Every BI question enters through it: report visuals, model
queries, SQL, statements and subscription sends. A question is the `LogicalPlan` with its resolved time, its caller and its options.

#### 3.2 States

| State | Meaning | Visible in Runs | Stored row |
|---|---|---|---|
| `planned` | fingerprint, estimate and tier known; nothing has run | no | no |
| `awaiting-confirm` | ad-hoc heavy above `confirm.above-seconds` or `confirm.above-rows`; the person has been shown the estimate | yes (with *Confirm* and *Discard*) | yes |
| `queued` | waiting in `RunQueue` or in `Admission` | once visible | once visible |
| `running` | holds an admission ticket; `progress` moves | once visible | once visible |
| `storing` | the engine finished; the Parquet is being written and renamed | once visible | once visible |
| `done` | the result is in the store (or was served inline) | once visible | once visible |
| `reused` | the same key's result existed; nothing ran | once visible | once visible |
| `failed` | ended with a code and a reason | once visible | once visible |
| `cancelled` | stopped by its runner, an administrator, a newer question for the same visual, or a gone caller | once visible | once visible |
| `expired` | the result was removed by retention or the storage budget; the record is kept | yes | yes |

A run becomes **visible** when it crosses the inline wait (section 6), when it is submitted asynchronously (`Prefer: respond-async`
with `wait=0`, the CLI's `bi runs submit`, a schedule), or when it needs confirmation. A run that finishes inside the wait is never
visible: it writes no row and sends no notice. Its result still goes to the store when it is worth keeping (section 8.3).

#### 3.3 Transitions

```
                    ┌──────────── reuse hit (same key, same or re-maskable rights) ───────────────► reused
                    │
 submit ─► planned ─┼── ad-hoc heavy, above confirm threshold ─► awaiting-confirm ─┬─ confirm ─► queued
                    │                                                            ├─ discard ─► cancelled
                    │                                                            └─ confirm-ttl (15 min) ─► cancelled
                    │
                    ├── interaction question that is ad-hoc heavy ─► failed (DRS-9089, with the estimate; nothing ran)
                    │
                    └── otherwise ─► queued ─► running ─► storing ─► done
                                       │         │          │
                                       │         │          └─ write failed / store full ─► failed (DRS-9088)
                                       │         ├─ engine error, time limit, row/byte limit ─► failed (DRS-9024/9027/9022/9023/9028)
                                       │         ├─ cancel ─► cancelled
                                       │         └─ node stopped ─► (after restart) queued (attempt + 1) | failed (DRS-9085)
                                       ├─ cancel ─► cancelled
                                       ├─ queue full at submit ─► failed (DRS-9081), never queued
                                       └─ node stopped ─► (after restart) queued | failed (DRS-9085)

 done ─► expired (retention or budget)          done | reused ─► (re-run) a new run, linked by rerunOf
```

Every transition is one method of `RunService`, under the run's lock. Each writes the row first, if the run is visible, and then
publishes the event (section 3.5). The allowed transitions are a table in `RunState`, and an illegal one throws, which the unit tests
cover (section 19).

#### 3.4 The run record

The `drishti_bi_run` table goes in both identity schema files. A parity test keeps them identical.

```sql
CREATE TABLE IF NOT EXISTS drishti_bi_run (
    id            TEXT PRIMARY KEY,          -- r-<node>-<ulid>: the node that owns it is in the id (section 13)
    runner        TEXT NOT NULL,             -- the user it is computed as
    owner         TEXT NOT NULL,             -- who sees and manages it (the runner, or a schedule's owner)
    workspace     TEXT,                      -- the item's workspace, for the workspace's Runs tab
    source_type   TEXT NOT NULL,             -- report | board | mq | sql | statement | subscription | schedule
    source_id     TEXT,                      -- the item id (report, board, model), when there is one
    source_ref    TEXT,                      -- page/visual, or the item version
    question      TEXT NOT NULL,             -- the canonical MQ (JSON) that ran: replayable
    params        TEXT,                      -- slicers, filters, parameters, drill path (JSON, as the reader sent them)
    as_of         TEXT,                      -- the resolved dates (JSON list, or "from..to")
    data_version  TEXT,                      -- hash of the data version vector; the vector itself is in the result's answer.json
    fingerprint   TEXT NOT NULL,             -- the plan hash
    signature     TEXT NOT NULL,             -- SecuritySignature of the runner
    result_key    TEXT,                      -- section 8.2
    tier          TEXT NOT NULL,             -- interactive | known-heavy | ad-hoc-heavy
    engine        TEXT NOT NULL,             -- local | split | external:<name>
    state         TEXT NOT NULL,
    progress      REAL,                      -- 0..1, null when unknown
    phase         TEXT,                      -- building 12 of 60 dates | scanning | storing
    estimate      TEXT NOT NULL,             -- JSON: rows, bytes, seconds low/high, basis (section 4.4)
    rows_out      INTEGER,
    bytes_out     INTEGER,
    code          TEXT,                      -- DRS code of a failure or cancellation
    reason        TEXT,                      -- one plain sentence
    node          TEXT NOT NULL,             -- the node running it
    attempt       INTEGER NOT NULL DEFAULT 1,
    lease_until   TIMESTAMP,                 -- renewed by the running node (section 13.3)
    submitted_at  TIMESTAMP NOT NULL,
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP,
    expires_at    TIMESTAMP,                 -- null while promoted or pinned
    rerun_of      TEXT,
    shared_from   TEXT,                      -- a recipient's recomputed run points at the original
    group_id      TEXT,                      -- the visuals of one page draw that became runs
    promoted_to   TEXT                       -- the dataset or report item a promotion made
);
CREATE INDEX IF NOT EXISTS drishti_bi_run_owner ON drishti_bi_run (owner, submitted_at);
CREATE INDEX IF NOT EXISTS drishti_bi_run_state ON drishti_bi_run (node, state);
CREATE INDEX IF NOT EXISTS drishti_bi_run_key ON drishti_bi_run (result_key);

CREATE TABLE IF NOT EXISTS drishti_bi_run_share (
    run_id TEXT NOT NULL, recipient TEXT NOT NULL, recipient_run TEXT, shared_by TEXT NOT NULL, shared_at TIMESTAMP NOT NULL,
    PRIMARY KEY (run_id, recipient));

CREATE TABLE IF NOT EXISTS drishti_bi_run_schedule (
    id TEXT PRIMARY KEY, owner TEXT NOT NULL, question TEXT NOT NULL, params TEXT, source_type TEXT NOT NULL, source_id TEXT,
    schedule TEXT NOT NULL,                   -- SubscriptionSchedule text: business-days 07:30, after-load TRD
    data TEXT NOT NULL DEFAULT 'latest',      -- latest | the same as-of rule as the run it was made from
    enabled INTEGER NOT NULL DEFAULT 1, last_run TEXT, next_at TIMESTAMP, created_at TIMESTAMP NOT NULL);
```

As built (R1), the tables also hold: `drishti_bi_run.title` (shown in Runs), `rows_read` (the actual rows read), `last_opened`
(retention counts from the last open, 8.4) and `reuse_of` (the run whose stored result a reused or joined run reads);
`drishti_bi_run_share.mode` (`same`, `re-masked`, `recomputed`); `drishti_bi_run_schedule.run_id` and `title` (the run it was made
from). The index names follow the file's `ix_` convention (`ix_bi_run_owner`, `ix_bi_run_state`, `ix_bi_run_key`,
`ix_bi_run_share_recipient`, `ix_bi_run_schedule_owner`). `SchemaParityTest` (drishti-it) keeps the two schema files identical in
tables, columns, nullability and indexes.

No data value is ever written to these tables. `question` and `params` hold names, operators and the values the reader typed into
filters, which the run's owner already knows. `params` is shown only to people who may see the run.

#### 3.5 Events

`RunService` publishes `RunEvent(runId, state, progress, phase)` on an in-process bus. Subscribers:

* the console's waiter for the inline answer (section 6);
* `GET /api/v1/bi/runs/events` (SSE): the person's own runs. The Runs tab and the bell listen to it, with one stream per browser
  tab, under `drishti.bi.live.max-streams-per-user`;
* `RunNotifier`: on `done`, `failed` or `cancelled` (when the person did not cancel it) of a visible run, one inbox row through
  `BiNotifier.tell` (type `bi-run`, kind `bi`, entityId the run id, summary "Run finished: Desk P&L by month, 2026-01-02 to
  2026-09-30 (182 rows, 3 min 40 s)"). Names and counts only, never a value, as every inbox row.

### 4. The planner and the estimate

`RunPlanner` (new) runs after the front end has produced the `LogicalPlan` and before anything reads data. It is pure apart from
metadata lookups, and it takes milliseconds.

#### 4.1 Inputs

| Input | Where it comes from | Cost |
|---|---|---|
| the plan: tables, measures, grain, filters, rollup match | `Planner`, `Optimiser` (the rollup the optimiser would choose) | in memory |
| the dates asked | the plan's resolved time (`as-of`, a window, `RelativeDates`), the business calendar | in memory |
| rows per table per date | in order: (1) `Layers.peek` reads the partition's `meta.json` when the partition is valid; (2) `SourcePlugin.rowEstimate(kind, date)`; (3) the table's learned rows per day (`RunHistory`, below); (4) unknown | one small file read, a log lookup, or memory |
| whether each partition is built | `Layers.peek`: valid (signature and data version match) or to build | as above |
| products | rollups declared on the model and built for the dates (`Layers.peek` on `rollups/…`); SUPER_SCALE S4 products (link indexes, group totals) once they exist, through the same `peek` | as above |
| data version vector | `SourceGateway.dataVersion(connector, kind, date)` per table and date | cheap by contract (SourcePlugin) |
| rates | `RunRates`: rows per second learned per connector and path (scan, record read) for builds, and per engine for scans; seeded from settings | memory |
| live tables | `LiveTables` | memory |

`SourcePlugin.rowEstimate(kind, date)` is new, with a default of `Optional.empty()`. Implementations:

| Connector | Answer |
|---|---|
| Delta (`DeltaSourcePlugin`) | from `DeltaTable.Layout.files` of the date: the sum of `numRecords` in each add action's statistics; for files without statistics, file bytes ÷ bytes per row (learned from files that have both) |
| Iceberg (`drishti-plugin-iceberg`, `drishti-iceberg` `ManifestReader`) | the sum of `record_count` of the date's data files in the current snapshot's manifests |
| DuckDB, PostgreSQL, JDBC | optional: the store's table statistics (`reltuples`, `duckdb_tables().estimated_size`) ÷ the dates the store holds, flagged `basis: store-stats` |
| others | empty: steps (3) and (4) apply |

`RunHistory` keeps, per (dataset table, connector), an exponentially weighted mean of rows per business day seen in built
partitions (`meta.json` rows) and finished runs. It lives in memory and is rebuilt at start from the `meta.json` files the BI store
already holds.

#### 4.2 Formulas

For a plan `P` over tables `T` and dates `D`:

```
 rows(t, d)      = peek(t, d).rows                if the partition is valid
                 | rowEstimate(t.kind, d)         else, if the source answers
                 | history(t).rowsPerDay          else, if known
                 | unknown

 covered(P)      = the optimiser's rollup (or S4 product) for P exists and is valid for every d in D

 readRows(P)     = Σ_{d∈D} rollupRows(r, d)                               if covered(P)
                 | Σ_{t∈T} Σ_{d∈D} rows(t, d)                             otherwise

 buildRows(P)    = Σ_{t∈T} Σ_{d∈D, partition to build} rows(t, d)         (0 when covered)

 seconds(P)      = buildRows / rate.build(connector of t, path)           summed per connector, divided by its fragment share
                 + readRows  / rate.scan(engine)
                 + fixed.overhead (0.05 s)

 bytes(P)        = readRows × bytesPerRow(columns read)                   bytesPerRow from the partitions' Parquet footers, else type widths
 rowsOut(P)      = min(limit, Π grain cardinalities)                      cardinalities from the dimensions' layers, else readRows
```

Unknown rows are never treated as zero. If any `rows(t, d)` is unknown, the estimate is `basis: unknown` and the question is
treated as ad-hoc heavy until it has run once (section 4.4).

#### 4.3 Decisions

| Decision | Rule (settings in section 15) |
|---|---|
| tier | section 5.1 |
| engine | section 5.4 |
| confirm | ad-hoc heavy **and** (`seconds.high` > `confirm.above-seconds` (60 s) **or** `readRows` > `confirm.above-rows` (500 M) **or** `basis: unknown`) |
| refuse | `readRows` > the largest engine's `max-rows` (local: `engine.max-read-rows`, 5 B) and no split or external engine is configured: `DRS-9086` with the estimate and the fix (narrow the dates, use a rollup grain, configure an engine) |
| reuse | a stored result with the same key (section 8.2) is valid: `reused`, nothing runs |

#### 4.4 Honest estimates

The estimate is shown as a range with its basis, never as a single false-precise number.

```json
"estimate": {
  "readRows": 1840000000, "buildRows": 410000000, "bytes": 62000000000, "rowsOut": 4320,
  "seconds": {"low": 150, "high": 330}, "basis": "delta-stats",
  "dates": 182, "datesToBuild": 41, "covered": false,
  "why": "182 business dates of trade_endur (about 10.1 M rows a day; 41 dates not built yet); no rollup has grain desk × month"
}
```

* The range is `seconds × [low-factor, high-factor]`. The factors start at `[0.5, 2.0]` and narrow to the 10th and 90th percentiles of
  the measured actual ÷ estimate ratio once `RunRates` holds at least 20 runs for that engine.
* `basis` is `layers` (every partition built), `delta-stats`, `iceberg-manifests`, `store-stats`, `history` or `unknown`. With
  `unknown` the console says "We cannot estimate this yet: it reads tables whose size we do not know. It may take a long time."
* Every finished run records its actual rows, bytes and seconds next to its estimate. Admin shows how accurate estimates are (section 17).
* Progress is honest too. `progress` is measured, never guessed: built dates ÷ dates to build while building, then rows scanned ÷
  `readRows` (DuckDB's `query_progress()` on the run's connection), else `null`. The console then shows "running", not a bar.

### 5. The tiers

#### 5.1 Choosing the tier

```
                        ┌───────────── live table in the plan? ── yes ──► INTERACTIVE, never stored, never visible (a stream)
 plan ──► RunPlanner ───┤
                        ├── covered(P) by a rollup or product, all dates valid ──► KNOWN HEAVY if readRows(raw) > interactive.max-rows
                        │                                                         else INTERACTIVE
                        └── not covered:
                              seconds.high ≤ interactive.max-seconds (10 s) and readRows ≤ interactive.max-rows (50 M)
                              and datesToBuild ≤ interactive.max-build-dates (2)  ──► INTERACTIVE
                              otherwise                                           ──► AD-HOC HEAVY
```

| Tier | Typical question | Engine and class | Visible job? | Stored? |
|---|---|---|---|---|
| **interactive** | one date; one entity; a page's visuals on the current business date; a slicer, cross-filter or drill on a covered question | the interactive DuckDB, `INTERACTIVE` class, `drishti.bi.exec.interactive` (30 s) | only if it outlives the inline wait (section 6), and never for interactions | if `store.keep-interactive` and the run took over `store.min-seconds` (1 s) |
| **known heavy** | a year of desk P&L by month where the rollup by desk × month exists; a counterparty page over a year from S4 group totals | the interactive DuckDB over rollup partitions, `INTERACTIVE` class (reads are small) | as interactive | as interactive |
| **ad-hoc heavy** | a year at a grain no rollup has; raw rows over many dates; a table never built | the run engine (section 10.3), `BACKGROUND` class, `drishti.bi.exec.background` (raised to `run.max-duration`, 4 h, for runs) | yes, unless it finishes inside the wait | always |

#### 5.2 Known heavy depends on products being there

A question is *known heavy* only when its products exist for **every** date asked. When some dates lack the rollup (a new date not yet
refreshed, a rollup declared yesterday), the planner does not silently fall back to raw rows inline:

* if the missing dates' raw rows fit the interactive bounds, it reads the rollup for the built dates and the raw layer for the rest
  in one plan (the optimiser already mixes layers per date), still interactive;
* otherwise it is ad-hoc heavy, and the run's `why` names the missing rollup dates. `DatasetRefresher` is asked to build them
  (`BACKGROUND`), so the next asker gets *known heavy*.

Rollups are written by scheduled refresh (`DatasetRefresher`, `refresh: after-load`) today, and by the loaders (S4) once SUPER_SCALE
step S4 lands. The planner does not care which wrote them; it reads `peek`.

#### 5.3 Interactions never become jobs

A request from a slicer, cross-filter or drill carries `interaction: true`. The console sets it in `stateFor` in `bi-report.js` when
the page's state changed by an interaction, not on the page's first draw. Such a question:

* runs only if the planner says *interactive* or *known heavy*;
* if the planner says *ad-hoc heavy*, it fails at once with `DRS-9089` and the estimate. Nothing ran. The visual shows "This
  selection needs a background run (about 3 to 6 min)" with **Run in background**, which submits the same question as a visible
  run;
* if it is still running at `drishti.bi.exec.interactive` (30 s), it is cancelled with `DRS-9024`, as today. It never crosses into a
  visible job;
* a newer interaction on the same visual (`supersedes: <page>/<visual>`) cancels the older one at once (section 11).

This keeps the decision ("dashboards stay instant") as a guarantee rather than a hope. An interaction is either fast because a
product covers it or refused at once with the way forward.

#### 5.4 Engines

```java
public interface RunEngine {
    String name();                                         // local | split | external:athena | external:trino | external:spark
    boolean accepts(RunPlan p);                            // the tables are readable by it; the plan's operators are supported
    Limits limits();                                       // max read rows, max seconds, max result bytes
    Execution start(RunPlan p, ResultSink sink, CancelToken c);   // streams Arrow batches into the sink; honours cancel
}
```

| Engine | 2.6.7 | Chosen when |
|---|---|---|
| `local` | **built**: `QueryService` on the interactive DuckDB (interactive, known heavy) or on the run DuckDB (ad-hoc heavy) | always available; the only engine on a laptop |
| `split` | interface only; filled by SUPER_SCALE S6 (each slice owner aggregates its share, the coordinator merges partial sums, counts and sketches) | ad-hoc heavy, the model's tables are `ownership: sliced`, and more than one node is alive |
| `external:<name>` | interface only; adapters are 3.0 groundwork (BI_DESIGN 12.2 wave E item 3, "a second query engine behind the tier planner") | ad-hoc heavy, `local` and `split` would exceed their limits (or `prefer-external-above-seconds`), and an adapter is configured whose `accepts` holds |

Row rules and masks are applied **before** a plan leaves for any engine (`SecurityRewriter`, stage 4), so an external engine sees
only predicates and projections already restricted for the runner. An external engine never receives an unrestricted plan.

### 6. The inline wait

#### 6.1 How a request waits

```
 browser ──POST /bi/api/reports/{id}/query  Prefer: respond-async, wait=2 ──► console proxy ──► server
                                                                                                  │
                                RunService.submit ─► future                                       │
                                        │                                                         │
           done within 2 s ─────────────┤──► 200 + today's body (the run stays invisible) ─────────┤
           not done at 2 s ─────────────┘──► 202 + {run} ; the run becomes visible, keeps going ───┘
           ad-hoc heavy, needs confirm ─────► 409 DRS-9082 + {estimate, confirmToken}
```

* The wait happens on the server (`wait` in `Prefer`, capped by `inline.max-wait`, 10 s), so no polling is needed for the common
  case. Its default is `inline.wait` (2 s).
* An ad-hoc heavy question that does not need confirmation is answered `202` **at once** without waiting. Its estimate already says
  it will not finish in 2 s.
* Without `Prefer: respond-async` an endpoint behaves as today: it waits up to its time limit (`drishti.bi.exec.interactive`) and
  answers `200` or a problem. The run is still a run (planned, admitted, bounded, stored when worth it) but never visible, whatever
  its tier, so a script that worked in 2.6.6 works unchanged. Its limits are today's too: past `drishti.bi.exec.max-estimated-rows`
  it gets `DRS-9029`, now with `run: {submit: "POST /api/v1/bi/runs"}` in the problem so a script knows how to ask for it as a run,
  and past the time limit `DRS-9024`. The difference is that the estimate now comes before any layer build (2.3), so the refusal
  costs nothing.
* The console proxy (`routes/bi_routes.py`) passes `Prefer` through. For asynchronous calls it waits `wait + 5 s` instead of
  `QUERY_WAIT_SECONDS` (35 s).

#### 6.2 What the person sees

A visual whose query became a run shows a run card in place of its chart, and the rest of the page keeps working:

```
 ┌─ MTM by desk and month ─────────────────────────────────────────────┐
 │                                                                     │
 │   ◔  Running: about 3 to 6 min                                      │
 │      182 business days of trade_endur, 41 to build first            │
 │      Building 12 of 41 dates ▓▓▓▓▓░░░░░░░░░░  29 %                  │
 │                                                                     │
 │      We'll put it on the bell when it is ready. You can leave.      │
 │      [ Cancel ]  [ Open in Runs ]                                   │
 └─────────────────────────────────────────────────────────────────────┘
```

A question that needs confirmation shows the estimate first:

```
 ┌─ Confirm a background run ───────────────────────────────────────────┐
 │ MTM by desk and month, 2026-01-02 to 2026-09-30                      │
 │                                                                      │
 │ About 1.8 billion rows over 182 business days (Delta statistics).    │
 │ Expected 3 to 6 minutes on this server. No rollup has desk × month;  │
 │ adding one to the model makes this instant next time.                │
 │                                                                      │
 │ [ Run in background ]   [ Narrow the dates ]   [ Not now ]           │
 └──────────────────────────────────────────────────────────────────────┘
```

When the run finishes while the page is open, the card turns into the chart without a reload. The page's SSE (`/runs/events`)
delivers `done`, and the visual asks `GET /runs/{id}/result?format=arrow`. When the person has left, the bell shows the count and
the inbox row links to the report with `?run=<id>`, which draws the visual from the stored result.

A page draw that makes several runs (three heavy visuals) groups them under one `group_id`. The Runs tab shows one row for the page,
which expands to its visuals, and the bell rings once, when the last of them finishes.

#### 6.3 What does not change

* Visuals whose queries finish in time look and behave exactly as today. No card, no row, no notice.
* `B.enqueue` and `MAX_PARALLEL` (3) stay. A `202` frees the browser's slot at once, so a heavy visual no longer holds one of the
  three for 30 s.
* Live views: the SSE `live` stream and its ticks are unchanged. A tick's re-ask is an interactive question and is never stored.

### 7. The Runs tab

#### 7.1 Where

* `/bi/w/me` (My workspace) and `/bi/w/<workspace>` get a tab strip: **Items · Runs · Members**. The Runs tab of My workspace
  shows the person's own runs and runs shared with them. The Runs tab of a shared workspace shows the runs of that workspace's items
  that the person may see (section 9.1).
* The BI rail gets **Runs**, a link to `/bi/w/me?tab=runs`, with a small count of the person's running runs.
* `BI RUNS <GO>` in the top bar opens it.
* The bell's count includes finished-run notices, as it does every other notice (`inbox.js`, `drishti:unread`).

#### 7.2 The list

```
 My workspace                                                                    [Items] [Runs] [Members]
 ───────────────────────────────────────────────────────────────────────────────────────────────────────────
  Runs   Status [All ▾]  Source [All ▾]  Who [Me ▾]  Since [30 days ▾]   Search [ desk pnl        ]  ⟳ live
 ┌──┬──────────────────────────────┬──────────┬────────────┬──────────────────┬──────────┬─────────┬────────┬─────┐
 │  │ Question                     │ Status   │ As of      │ Data             │ Duration │ Rows    │ Est.   │     │
 ├──┼──────────────────────────────┼──────────┼────────────┼──────────────────┼──────────┼─────────┼────────┼─────┤
 │ ◔│ MTM by desk and month        │ Running  │ 01-02…09-30│ delta v412       │ 1 m 52 s │ –       │ 3–6 m  │ ⋯   │
 │  │ Desk P&L · page Overview     │ 29 %     │            │                  │          │         │        │     │
 │ ✓│ Notional by counterparty     │ Done     │ 2026-09-30 │ delta v412       │ 41 s     │ 1,204   │ 30–90 s│ ⋯   │
 │  │ mq · trading                 │          │            │                  │          │         │        │     │
 │ ↺│ VaR by book (shared by anna) │ Done ⇄   │ 2026-09-30 │ delta v410       │ –        │ 88      │ –      │ ⋯   │
 │ ✕│ Raw trades 2025              │ Failed   │ 2025       │ –                │ 4 m 03 s │ –       │ 2–9 m  │ ⋯   │
 │  │ DRS-9028 the run ran out of its memory budget (4 GB): narrow the dates or add a rollup                  │
 │ ⊘│ Cash ladder 90 days          │ Cancelled│ 2026-09-30 │ –                │ 12 s     │ –       │ 1–2 m  │ ⋯   │
 │ ⌛│ Exposure by legal entity     │ Expired  │ 2026-08-29 │ delta v388       │ 2 m 10 s │ 52      │        │ ⋯   │
 └──┴──────────────────────────────┴──────────┴────────────┴──────────────────┴──────────┴─────────┴────────┴─────┘
  Storage: 1.4 GB of your results · kept 30 days unless promoted                                 1–6 of 48  [More]
```

* *Data* is the source's version in words (`delta v412`, a Delta table version; `iceberg snap 7…`; `layers 2026-10-20 07:31` when
  only built layers were read). Hovering it lists every table and date version (the vector).
* *Est.* is the range the person was shown. A finished run's tooltip shows "estimated 3 to 6 min, took 4 min 12 s".
* ⇄ marks a run shared with the person. Its row says by whom, and whether they see the original result (same rights), a re-masked
  one, or their own recomputation (section 9).
* Rows update live from `/runs/events`. There is no polling.

#### 7.3 One run

```
 ┌─ MTM by desk and month ──────────────────────────────────────────────────────────────────────── ✕ ┐
 │ Done · 4 min 12 s · 4,320 rows · 1.1 MB                    [Open] [Re-run ▾] [Export ▾] [⋯]       │
 │                                                                                                   │
 │ Source       Report "Desk P&L", page Overview, visual "MTM by desk and month"   (open the report) │
 │ Run by       ajsinha, computed as ajsinha · 2026-10-20 09:14 on node java-2                       │
 │ Filters      DESK in (RATES-NY, RATES-LDN) · PRODUCT_TYPE ≠ FX_SPOT                               │
 │ Parameters   currency = USD · period = this year                                                  │
 │ As of        2026-01-02 to 2026-09-30 (182 business dates)                                        │
 │ Data         trade_endur delta v412 · desk_dim layers 2026-10-20 07:31            (all versions)  │
 │ Estimate     3 to 6 min, 1.8 B rows read (Delta statistics) · actual 4 min 12 s, 1.79 B rows      │
 │ Tier         ad-hoc heavy · local engine (run engine) · background                                │
 │ Kept until   2026-11-19 (30 days)                      [Keep longer] [Promote ▾] [Delete result]  │
 │ Shared with  bob (re-masked: NOTIONAL hidden for him), risk-team (3 recomputed)      [Share…]     │
 │ Schedule     —                                                                     [Schedule…]    │
 └───────────────────────────────────────────────────────────────────────────────────────────────────┘
```

| Action | What it does | Who |
|---|---|---|
| **Open** | the report with `?run=<id>` (the visual drawn from the stored result), or for MQ/SQL a result grid (`/bi/runs/<id>`) with the Analyse grid's sort, filter and pivot, all done client-side on the stored rows | anyone who may see the run |
| **Re-run ▾ → on the same data** | a new run with the same question and the same as-of dates. For sources that keep versions (Delta, Iceberg) it reads the recorded versions (`X-Drishti-Known-At` = the original's `submitted_at`). For others, when today's data version differs, it says so first: "2026-09-30 of trade_endur changed since (v410 → v412); the answer may differ" | the runner |
| **Re-run ▾ → on the newest data** | the same question with relative dates resolved again (`this year`, `latest`) | the runner |
| **Cancel** | section 11 | the runner, a bi-admin |
| **Export ▾** | CSV, Excel, Parquet, and PDF for a report visual, from the stored result through `BiExporter` (no recomputation). Masked cells stay masked | anyone who may see the result |
| **Schedule…** | a `drishti_bi_run_schedule` row with a `SubscriptionSchedule` expression. Each firing is a background run and a notice. Email is the existing subscription's job and is offered as a link to it | the runner |
| **Promote ▾ → dataset snapshot** | section 9.4 | a bi-author |
| **Promote ▾ → report** | a draft report in the person's workspace with this question as a table visual (MQ/SQL runs), or the run's report pinned to the run's as-of dates | a bi-author |
| **Share…** | section 9.3 | the runner |
| **Keep longer / Delete result** | sets `expires_at` (up to `retention.max-days`), or removes the result now (the row stays, `expired`) | the runner, a bi-admin |
| **Confirm / Discard** | for `awaiting-confirm` | the runner |

### 8. The result store

#### 8.1 On disk

Everything is under the configurable data folder, with visible names only:

```
 <drishti.data.dir>/bi-runs/
   results/
     7f/7f3a…c1/                       ← result key (section 8.2), sharded by its first two hex digits
       result.parquet                   ← the rows: Parquet, zstd, row groups of 64 K rows, typed as the answer's columns
       answer.json                      ← the answer without rows: columns (ids, types, additivity, masks), totals, meta,
                                          the data version vector, the canonical question, the signature id, created, bytes
   snapshots/
     desk-pnl-2026/v3/result.parquet    ← promoted results (section 9.4): never expire, outside the budget
     desk-pnl-2026/v3/answer.json
   tmp/
     r-java-2-01JA…/                    ← a result being written; renamed into results/ when complete; swept at start
```

* The path is `runs.store.dir`, with default `${drishti.data.dir}/bi-runs`. On AWS (EFS with identical paths on every node,
  CLUSTER_DEPLOYMENT.md) the folder is shared and any node can read any result. Otherwise results live on the node that ran them, and
  the others forward (section 13).
* **Parquet** is the stored format: compact, typed, and read back by DuckDB with no parsing. **Arrow IPC** is the wire format: a
  result is served by DuckDB reading `result.parquet` (`LIMIT/OFFSET` for a page) into `ArrowIpcWriter`, the way layers are
  served today. JSON is served for results up to `drishti.bi.query.json-max-rows` (5,000).
* The engine writes the result **directly**. The local engine uses DuckDB `COPY (…) TO 'tmp/<run>/result.parquet'` on the run's
  connection, so rows never pass through the Java heap. This is the lesson of QA3A-01.
* A file is written under `tmp/` and renamed into place (`FileSwap`, which handles Windows' held-open files), so a reader never
  sees half a result.

#### 8.2 The key

```
 resultKey = sha256( planHash                      -- Canonicalizer.hash(LogicalPlan): question, model at its fingerprint
                   | resolvedTime                  -- the dates actually read ("latest" resolved; as of D, each table's latest date on or before D)
                   | dataVersionVector             -- sorted (connector, kind, date) → SourcePlugin.dataVersion(kind, date)
                   | securitySignature             -- SecuritySignature.of(flat model, reader)
                   | engineSemantics )             -- "v1": bumped if an engine change alters answers (approximate sketches, rounding)
```

* **The resolved dates are in the key** (2.6.7 patch): as of D each table reads its latest date on or before D (a report's `as-of`
  may align them or pin a date: BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 7.1), and the dates the planner resolves for each table are part of the key (an
  MQ or SQL question's version, a report question's canonical text), so a reused answer is always for the same dates.
* The **data version vector** replaces the generation for durable keys (section 2.3). A restated date has a new version, so a new
  key, so the old result is never served for new data.
* Two readers with the **same signature** (the same rows, masks and denied tables) share a result. That is the decision's
  "reused across users with the same rights", and the same rule the L1 `ResultCache` already follows.
* A source that cannot give a data version (`dataVersion` empty) makes the result **not reusable**. It is stored for its runner
  (open, export, promote) but never served for another submit. The run detail says "this source has no data version: re-run to
  refresh".

#### 8.3 Reuse

| Situation | What happens |
|---|---|
| submit, a `done` result with the same key exists | `reused` at once; the run row points at the existing result; `rows_out` copied; no admission, no engine |
| same plan and data, different signature | section 9.2 (re-mask or recompute) |
| an interactive question that took under `store.min-seconds` (1 s) | not stored (the L1 cache serves repeats); storing costs more than recomputing |
| an interactive question that took longer | stored when `store.keep-interactive` (true): the next reader's slow first draw becomes a reuse |
| live tables in the plan | never stored |

The L1 `ResultCache` stays in front, in memory: a hit there is microseconds. The store is the tier under it. An L1 miss with a store
hit loads `result.parquet` (milliseconds) and puts it in L1 when it is under L1's entry weight.

#### 8.3a Fewer duplicate runs (product owner, 2026-10-20)

Five rules keep the same work from running twice. The first two are the key and the join (8.2, 8.3, 13.3); the other three were
added on the product owner's word.

| Rule | What it does | Where |
|---|---|---|
| **Same key, reuse** | the same question on the same data for the same rights opens the stored result | 8.2, 8.3 |
| **Join a running twin** | a submit whose key matches a running run follows it (single-flight) instead of starting another | 13.3 |
| **Normalise before hashing** | `Canonicalizer` sorts filter values and conjuncts, fills parameter and well defaults, resolves relative dates (`this year`, `last month`, `latest`) to the dates read, and drops aliases and visual-only properties (column order, titles, formats) that do not change the rows, so `region in (EU, US)` and `region in (US, EU)` hash alike. A test lists equivalent pairs that must hash the same and near pairs that must not | 8.2 (`planHash`) |
| **"This already ran"** | before an ad-hoc heavy run starts, the confirm step looks for a `done` run of the same plan and signature on **older** data. When one exists it offers *Open that result* (with its as-of date and age) or *Run on the newest data* (the estimate). Many people need only yesterday's answer | 6.2 confirm dialog, `GET /api/v1/bi/runs?plan=<hash>&latest=1` |
| **Repeated heavy questions become products** | the run log counts ad-hoc heavy runs per (model, grain, filters shape). Past `suggest.min-runs` (default 5 in 7 days) and `suggest.min-seconds` (default 60), the model's owner sees *"asked 37 times this week, about 4 min each: make it a rollup refreshed at load"* in the model designer and Admin → Runs, with the rollup YAML drafted for review (BI_DESIGN 6.1 *suggested aggregations*); once the rollup exists those questions are known heavy and answer instantly | Admin → Runs, model designer; in 2.6.7 |

Measured, not assumed: Admin → Runs shows the **reuse rate** (answered from a stored result), the **join rate** (attached to a
running twin), the **already-ran choice rate** and the top repeated heavy questions with their suggestion status (section 17).

#### 8.4 Retention and limits

| Rule | Setting (default) |
|---|---|
| A result is kept for `retention.days` (30) after it was last opened, unless promoted or pinned | `retention.days`, `retention.max-days` (365, the most *Keep longer* allows) |
| Total size of unpromoted results | `store.max-bytes` (20 GB). Over it, the sweeper removes the least recently opened unpromoted results first; their rows become `expired` |
| One result | `store.max-result-rows` (50,000,000) and `store.max-result-bytes` (2 GB). Past either the run fails `DRS-9086` with the fix (aggregate, add a limit). Detected while writing, so the run stops at once |
| Per person | `store.max-bytes-per-user` (2 GB). Over it, that person's oldest results go first; nobody else's |
| Promoted snapshots | outside the budget; listed separately in Admin |
| Sweeper | every `retention.sweep` (1 h) and at start; also removes `tmp/` folders whose run is no longer running |

When an expired run is opened, its card says "This result was removed on 2026-11-19 (kept 30 days). Re-run on the same data or
the newest data", with both buttons.

### 9. Masks and sharing

#### 9.1 Who sees a run

| Who | Sees the run row | Sees the result |
|---|---|---|
| the runner (owner) | yes | yes (it was computed as them) |
| a recipient of a share | yes (marked ⇄) | their own version (9.3) |
| a member of the workspace of the run's item | only with `runs.workspace-visibility: members` (default `owner-and-shared`); question and status, never the result | no, unless shared |
| a bi-admin | every row, for operations (Admin → BI → Runs) | no; the admin sees state, size and node, never values |

#### 9.2 Computed as the runner

A run is executed with the runner's `QueryService.Caller`: their roles, attributes, row rules, masks and denied tables. Nothing is
computed "as the system". A schedule runs as its owner, checked again at every firing: a person who lost access gets a failed run
with `DRS-9021`, not someone else's rights. A subscription or statement send runs as each recipient, as `SubscriptionService`
and `StatementService` do today. The run's `runner` is the recipient, and its `owner` is the subscription's owner.

#### 9.3 Sharing a run

Sharing never hands over the runner's result as such. For each recipient, `RunSharing` decides:

```
 recipient's SecuritySignature on the model ── equal to the runner's? ── yes ─► SAME: the recipient reads the stored result
          │
          no
          ▼
 the row part equal? (SecuritySignature.Alias rules, denied, hidden: the same for every alias the plan reads)
 and the recipient's masked columns ⊇ the runner's?
 and the canonical question validates as the recipient (MqValidator: no DRS-9161 MQ_MASKED, no DRS-9163 MQ_NOT_FOR_ROLE)?
 and every measure is additive, with no limit, top filter or totals (when an extra masked column is a grouping field)?
          │
          ├── yes ─► RE-MASK: the stored result is served with the recipient's extra masked columns replaced by the mask
          │          (the value + masked flag); a masked grouping field also COLLAPSES its groups into one, the
          │          additive measures summed, exactly as the engine answers that recipient; no recomputation
          │
          └── no  ─► RECOMPUTE: a new run in the recipient's name (runner = recipient, shared_from = the original,
                     BACKGROUND class), with the same question, as-of dates and data versions; it is visible to the
                     recipient and notifies them when done; its estimate is the original's actuals
```

* **Re-mask** is sound only when the rows are identical (the same row rules) and the recipient masks *more* columns. It is not purely
  column-wise: the engine does not group by a masked column, so for that recipient the groups of a masked grouping field collapse into
  one. Re-masking therefore masks the value **and** collapses those groups, **summing the additive measures**; that is exact only when every
  measure is additive and the question has no limit, top filter or totals. **Anything else is recomputed** (a non-additive measure, a
  limit, a top filter, totals), as is a measure whose input column is masked for the recipient (refused by `MqValidator`), a SQL statement
  and a report visual (`same` or `recomputed` only). As built: 18.2b, R6.
* **Recompute** is the bursting pattern of `StatementService`: one computation per recipient as that recipient.
* Sharing to a role (`role:risk-team`) expands to its members when the share is made, like `SubscriptionService.expand`. Each member
  gets a share row. Members with equal signatures share one recomputation (the key is the same), so ten readers of one desk cost one
  run, not ten.
* A share row is a pointer. The recipient's inbox row says "anna shared a run with you: VaR by book, 2026-09-30", as every share does.
* Unsharing removes the share row and the recipient's access. A recomputed run the recipient owns stays theirs.

#### 9.3a A report visual's answer is shared by access profile

A report visual's finished answer is keyed by the viewer's **access profile**, not by the person: their `SecuritySignature` on the
model the visual reads (the row part and the mask part: roles, masks, row rules with `user.*` resolved), the report's definition,
the data version vector and the resolved as-of dates. Two viewers with the same access profile share one computation: the second
is `reused` (the result is stored) or joins the running one (single-flight). A viewer with another mask or row rule never gets it,
whichever asks first. The answer stays **personal** (the viewer's name and roles, no data version: never served to another) when
the visual depends on who views it, or when that cannot be told:

* the report, the visual's wells, the page state or the query names the viewer: `user.<attribute>`, `me`, `@me`, `${user}`,
  `USERNAME()`, `CURRENT_USER` (`ViewerIdentity`; a match anywhere counts, a title included);
* the model reads answers asked as the viewer (a personal model: unbuilt or other-model sources), a row rule by `user.name` (its
  resolved predicate is the person's own, so the signature is too), a live or version-1 dataset, a python visual;
* the visual cannot be sized from metadata, or a source gives no data version.

#### 9.4 Promoting to a dataset snapshot

A promotion copies `result.parquet` and `answer.json` to `snapshots/<name>/v<N>/` and creates a **draft dataset** in the
promoter's workspace:

```yaml
rachana: 2
dataset: desk-pnl-2026
title: Desk P&L 2026 (snapshot of run r-java-2-01JA…, as of 2026-01-02 to 2026-09-30)
time: {mode: snapshot}
tables:
  - name: result
    source: {run: desk-pnl-2026, version: 3}       # new source type: DatasetDef.Source.RUN, read-only Parquet
    columns: …                                     # from answer.json: names, types, roles, additivity, format
provenance: {run: r-java-2-01JA…, computedAs: ajsinha, signature: 5c1e…, dataVersion: …, question: …}
```

* The rows are what the promoter could see. Columns masked for them are stored as masks and stay masked for every reader, flagged
  `masked: always` in the dataset.
* The snapshot dataset follows the normal lifecycle (draft → review → publish, `BiGovernance`). Its own `rules:` and `masks:` apply
  to its readers on top. A reviewer sees the provenance, including `computedAs`, so publishing data computed with broad rights to
  narrow readers is a visible, reviewed decision, never an accident.
* Promotion needs the `bi-author` power and `runs.promote.enabled` (true).
* A new version (re-promote after a re-run) writes `v<N+1>`. Earlier versions are kept for `runs.promote.versions-keep` (5).

**As built (2.6.7).** `POST /api/v1/bi/runs/{id}/promote {name?, title?, to: dataset|report}` (`RunPromotion`):

* Only the run's **runner** promotes it (it holds their view), with `bi-author` and `promote.enabled`; a share's recipient cannot. Only a
  model query or a SQL statement's table is promoted (a report visual or a statement is refused, `400`). The run object's `can.promote` says so.
* The snapshot is `snapshots/<name>/v<N>/answer.json` under `store.dir`: the stored answer **as the promoter reads it** (`RunService.result`
  with their signature), so a column masked for them holds the mask in every row, never a value. The rows are stored as JSON (as results
  are, 18.2b R5), not `result.parquet`.
* The draft dataset (`rachana: 2`, one table, the dataset's own alias) is created in the promoter's personal workspace through the BI
  items, so its review and publish are the normal ones:

  ```yaml
  rachana: 2
  dataset: desk-pnl-2026
  version: 1.0.0
  title: "Desk P&L (snapshot of run r-java-2-01JA…, as of 2026-01-02 to 2026-09-30)"
  source: { run: desk-pnl-2026, version: 3, token: 5c1e… }
  columns:
    desk: { type: string, path: "trades.desk", role: dimension }
    pnl:  { type: decimal, path: "PnL", role: amount, aggregate: sum }
    trader: { type: string, path: "trades.trader", role: dimension }
  grain: { keys: [desk, trader] }
  security:
    masked: [trader]          # masked for the promoter: masked always
  provenance: { run: r-java-2-01JA…, computedAs: ajsinha, signature: …, dataVersion: …, asOf: …, question: … }
  ```

  Column names are the answer's last part, lower-cased (`trades.desk` is `desk`), with `path:` naming the answer's column; fields are
  dimensions and the grain's keys, additive measures `amount`/`sum`, others `ratio`/`avg`.
* **`Layers` reading `snapshots/` is built as a staged table** (the mechanism of model and SQL-view sources): `DatasetSources` reads the
  snapshot through `ModelCatalog.Snapshots` and stages it like any inner answer; the model's relationships, measures, the dataset's own
  rules and masks then apply on top. The version is part of the staged fingerprint, so a new version is never served from an old cache.
* **Who may read a snapshot:** only the dataset it was promoted to. The snapshot keeps the dataset's name and a random **token**, written in
  that dataset's `source:`; a dataset of another name, or without the token, gets `DRS-9108` ("this dataset may not read it"), so another
  author cannot point a dataset of theirs at someone else's snapshot. The draft is the promoter's only until it is published through
  review, where `provenance.computedAs` is visible. A snapshot name belongs to its first promoter (`409 DRS-9004` for anyone else).
* Promoting again under the same name writes `v<N+1>`, saves a new draft of **the same dataset item** pinned to it, and keeps the newest
  `promote.versions-keep`. The run's `promotedTo` is `name@N`.
* `to: report` also makes a draft report on `model: dataset:<name>` with one table of every column.

Tests: `RunPromotionTest` (masked stored as the mask and declared masked; the YAML parses; only the promoted dataset reads it; versions
and the same item; a report run refused) and `ModelAcceptanceTest` order 18 (end to end: run, promote to a report, the report's table
equals the run's result, another reader cannot open the draft, promoted again is version 2 of the same item).

### 10. Fairness and admission

#### 10.1 Three gates

```
 submit ─► RunQueue (per node)                ─► Admission (per node, the engine's gate)   ─► engine
           · per user: queue.per-user (5)          · slots: drishti.bi.query.max-concurrent (8)
             visible runs queued + running         · class caps (new): background-share 0.25 → 2 slots,
             → DRS-9081 at submit                    standing-share 0.25 → 2 slots
           · per node: queue.max (200)             · INTERACTIVE: per user max-concurrent-per-user (3)
             → DRS-9081 at submit                  · per model: per-model-share 0.5 → 4 slots
           · workers: runs.workers (2)             · wait: admission (2 s) for INTERACTIVE → DRS-9025;
             runs started at once                    BACKGROUND waits in RunQueue, never refused for busy
```

* **`RunQueue`** (new) holds visible and background runs, ordered by fair share: the next run started is the oldest one of the
  person with the fewest runs currently running. It hands at most `runs.workers` runs at a time to `Admission` as `BACKGROUND`.
  Interactive questions do not pass through `RunQueue`. They go straight to `Admission` as today, so a burst of heavy runs
  cannot even delay their queueing.
* **`Admission`** gains `classCap(WorkClass)`. `grant()` skips a waiter whose class is at its cap. With 8 slots, background
  work never holds more than 2 and standing maintenance never more than 2, so **at least 4 slots are always free for interactive
  work**. The test of section 19.5 proves it.
* `DatasetRefresher` sets `QueryContext.with(new Ctx(BACKGROUND, …))` around `QueryService.refresh` and enters `Admission` (today
  it does neither). Refresh and runs share the background cap.

#### 10.2 The work classes, mapped

| Work | Class | Gate | Time limit | Counts against |
|---|---|---|---|---|
| a visual, MQ or SQL question (interactive or known heavy) | `INTERACTIVE` | `Admission`, 2 s wait | `drishti.bi.exec.interactive` (30 s) | the person's 3, the model's share |
| a dashboard interaction | `INTERACTIVE` | as above; ad-hoc heavy refused at once (DRS-9089) | 30 s | as above |
| a standing query's maintenance (`StandingQueryRegistry`) | `STANDING` | `Admission`, `standing-share` | unchanged | the model's share |
| an ad-hoc heavy run, a recomputation for a recipient, a scheduled run, a subscription or statement send | `BACKGROUND` | `RunQueue` then `Admission`, `background-share` | `run.max-duration` (4 h) | the person's `queue.per-user`, the model's share |
| a dataset refresh (`DatasetRefresher`) | `BACKGROUND` | `Admission`, `background-share` (no `RunQueue`: it is not a person's run) | `drishti.bi.exec.background` (300 s) per date | the model's share |

#### 10.3 Resources beyond slots

| Resource | Control |
|---|---|
| DuckDB memory and threads | ad-hoc heavy runs execute on the **run engine**, a second `DuckDbEngine` instance with `engine.memory-limit` (2 GB) and `engine.threads` (2). Its spill folder is `<data>/tmp/server/duckdb-runs`. The interactive instance keeps `drishti.bi.query.memory-limit` and `threads` for itself. A spilling run slows only runs |
| connector fragments | runs' layer builds take at most `fragments-share` (0.5) of each connector's `max-fragments` (`FragmentPools`), so interactive builds of a single date always find a slot |
| JVM heap | results stream engine → Parquet (8.1). Layer builds already stream batches (`scan-batch-rows`). A run's Java-side state is its record and progress counters |
| disk | the result store's budgets (8.4); DuckDB's `max_temp_directory_size` on the run engine (`engine.max-spill`, 20 GB) |
| CPU | the run engine's `threads`; `runs.workers` caps concurrent runs |

The interactive engine is untouched by runs: its memory, threads, slots and connector share are reserved. That is how "heavy runs never
slow interactive users" is kept as a property rather than a hope. The CPU is the one shared resource left, and it is bounded by
`engine.threads` × `runs.workers`.

### 11. Cancellation

Cancelling a run frees everything it holds at once:

| Phase | How it is stopped | Freed |
|---|---|---|
| `awaiting-confirm`, `queued` in `RunQueue` | removed from the queue | the queue place |
| waiting in `Admission` | its waiter removed under the lock (`changed.signalAll()`); new `Admission.Waiter.cancel()` | the place in line |
| building layers (`TableBuilder` via `FragmentPools`) | the run's `CancelToken` interrupts its virtual threads; connector reads check the token between batches; a waiting fragment leaves `FragmentPools` (interruptible acquire, never `acquireUninterruptibly`); the half-written partition's temp file is deleted (`TableBuilder` writes under a temporary name already) | fragment slots, the connector's semaphore, memory of the batch |
| scanning (DuckDB) | `Statement.cancel()` on the run's connection, the call the `DuckDbEngine` watchdog already makes at the time limit | DuckDB threads, memory, spill files |
| storing | the `tmp/<run>` folder deleted | disk |
| `split` / `external` | `Execution.cancel()`: each slice owner's fragment cancelled with the run's id; an external engine's query cancelled with its own API | remote resources |

* The admission ticket is closed in a `finally` (it is `AutoCloseable` today), so a cancelled run's slot goes to the next waiter in
  the same instant.
* A cancel answers `202` with the run in `cancelled` as soon as the token is set. The run row says `cancelled` once every phase above
  has let go, which takes milliseconds for a scan and at most one batch for a build. Admin shows any run whose resources took longer
  than `cancel.grace` (5 s) to be released.
* **A gone caller cancels invisible work.** When an invisible run's HTTP request is closed (the browser navigated, the console's
  proxy timed out), the run is cancelled. A newer question with the same `supersedes` key cancels the older one. A visible run
  is not cancelled by its caller leaving: the person was told it would continue.
* Code `DRS-9083` is the answer when a cancelled run's result is asked for. The reason names who cancelled it ("cancelled by you",
  "cancelled by an administrator (ops1)", "replaced by a newer selection", "the page was closed").

### 12. Restart and failure

#### 12.1 At start

Before the server accepts requests, `RunRecovery` reads this node's rows in `queued`, `running` or `storing`:

| Row state at start | Rule | Result |
|---|---|---|
| `queued` (or `awaiting-confirm`) | the question still plans: the model and items exist, the runner still has access, the model's fingerprint is unchanged or `restart.requeue-changed-model` | back in `RunQueue` in its old order; reason "requeued after the server restarted at 09:41" |
| `queued`, does not plan | | `failed`, `DRS-9085`, reason naming what changed ("the model trading was changed while this run waited") |
| `running` / `storing`, `attempt < restart.max-attempts` (2) and `restart.resume-running` (true) | runs are reads, so starting again from the beginning is always safe; any layer partitions already built are kept (they are the BI store's, validated as always) | `queued`, `attempt + 1`; reason "started again after the server restarted (attempt 2 of 2)"; the estimate is reduced by the partitions already built |
| `running`, attempts exhausted | | `failed`, `DRS-9085`: "the server stopped while this ran, twice; it may be too large for this server: narrow it or ask an administrator" |
| a `tmp/` folder without a running row | | deleted |

Every row it changes produces the usual event and, for a visible run, an inbox row: "Your run *MTM by desk and month* was
started again after a server restart", or the failure. Nothing is dropped without a word.

#### 12.2 Failures while running

| Failure | Code | Run detail says |
|---|---|---|
| engine time limit (`run.max-duration`) | `DRS-9024` | the limit, and the estimate it was given |
| DuckDB out of memory on the run engine (spill budget exhausted) | `DRS-9028` | the run engine's budget (`engine.memory-limit`, `engine.max-spill`) and the fix |
| result past `store.max-result-rows` or `max-result-bytes` | `DRS-9086` | the limit and "aggregate it, or add a limit" |
| source failed or breaker open | `DRS-9026` | the connector, from `FragmentPools`' breaker |
| store disk full, nothing evictable | `DRS-9088` | the folder and its budget |
| the runner lost access mid-run (role removed) | `DRS-9021` | checked again before `done`; the result is deleted |

A failed run keeps its partial progress information (dates built) so a re-run's estimate is honest.

### 13. The cluster

#### 13.1 Where a run executes

```
 console ─► any server X ─► RunService.submit
                              │
                              ├─ interactive / known heavy: X answers it (asked queries are stateless, BI_DESIGN 6.5)
                              │
                              └─ ad-hoc heavy: owner = ShardDirectory.owner(model key) over Drishti*net's alive members
                                     X = owner ─► X runs it
                                     X ≠ owner ─► forward the submit to the owner (Drishti*net view forwarding:
                                                  the same endpoint, X-Drishti-Net-Hop: 1, the on-behalf-of token);
                                                  the owner creates the run (id r-<owner>-<ulid>) and answers 202
```

* **The model's owner** runs its heavy runs because it already holds that model's warm layers and live tables (BI_DESIGN 6.5).
  Ownership is rendezvous hashing, so every node agrees on the owner without talking, and a node leaving moves only its own models.
  2.6.7 wires `ShardDirectory` to Drishti*net's member table (alive, not suspect) for this, the wiring production lacks today.
* **With S6 built** (`engine: split`), the owner is the coordinator. It sends each slice owner its fragment (SUPER_SCALE 10.3 and
  11.3: partial sums, counts and sketches) and merges them. Each slice owner admits its fragment as `BACKGROUND` under its own caps,
  so a cluster-wide burst is bounded on every node.
* **One node** (a laptop) owns every model. The same code runs every path with `owner = self`.

#### 13.2 Status from any node

| Identity database | How any node shows a run |
|---|---|
| shared (PostgreSQL, DRISHTI_NET section 19 *Identity across nodes*) | every node reads `drishti_bi_run` directly; list, get and events need no forwarding. Events are relayed: each node subscribes to the owner's `/runs/events` for the runs its consoles watch, one upstream stream per node, as live entity streams are relayed today |
| per node (SQLite) | `get`, `result`, `cancel` and actions on `r-<node>-…` are forwarded to `<node>` (the id names it); the person's list is scattered to the alive nodes and merged by `submitted_at` (as a search is). A node that does not answer is listed in `nodes.failed`, and its runs show "node java-3 is not reachable: its runs' state is unknown until it returns" |

Results are read where they are. On a shared folder (EFS) a node reads `result.parquet` itself; otherwise `GET /runs/{id}/result` is
forwarded to the run's node and streamed back.

#### 13.3 A node dying mid-run

* The running node renews `lease_until` every `lease.renew` (10 s), for `lease.ttl` (45 s).
* **Shared database:** when Drishti*net marks the node dead (`dead-after`, 10 s) *and* its lease has lapsed, the model's new
  owner adopts the run. A conditional update (`UPDATE … SET node = ?, attempt = attempt + 1, state = 'queued' WHERE id = ? AND
  node = ? AND lease_until < now`) means exactly one node wins. The run starts again under the rules of 12.1 (attempts, access,
  model), and the runner is told "started again on java-1 after java-3 stopped". Only one node can own a run at a time: a node that
  comes back with a lapsed lease finds the row is no longer its own and abandons its copy (its `tmp/` is swept).
* **Shared content store (the default since the content store, DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9):** the row is a document every node reads
  (`bi/runs/rows/<id>.json`). The lease is a compare-and-set on it: `renew` writes only while the row names this node, `adopt` only while
  it is still the dead node's with `lease_until` past, so exactly one node wins without a shared database. A late save from the node that
  lost the run (another node holds a higher attempt) is dropped. Results are in the store too, so a run's status and result read from any node.
* **Per-node database** (`drishti.bi.runs.store: local` on a node whose identity database is its own): nobody else can see the row. The run's state is reported as unknown (13.2) until the node returns, and
  12.1 then applies. The person can re-run it at once from another node ("Re-run elsewhere" on an unreachable run: a new run on
  the current owner, with `rerun_of` set).
* A forwarded submit whose owner dies before answering is retried once on the new owner within the caller's wait. Runs are reads, so
  a duplicate is harmless and is removed by the key (the second finds the first's result or its running row with the same key and
  joins it).

**Joining identical work.** Two submits with the same result key while the first is still running do not run twice. The second joins
the first: a new row for its own runner, in state `running`, which follows the first and turns `reused` when it finishes. This is
single-flight, the rule `Layers` already applies to partition builds.

#### 13.4 As built (2.6.7)

* **Names.** In a cluster a run is named after the Drishti*net member (`r-<node id>-<ulid>`, `NetService.plannedNodeId`, known before
  the node joins), so its id names its node. Outside a cluster, as before (`drishti.bi.exec.node`, else `local`).
* **Owner** (`RunCluster`): `ShardDirectory` over every member known, a member that is not `alive` owning nothing; both nodes agree
  without talking. A submit (`POST /runs`) that is ad-hoc heavy, or asks `wait: 0`, for a model another node owns is sent whole to the owner
  for the caller (signed, `X-Drishti-Net-Hop: 1`, an on-behalf-of token: computed there with the caller's roles, row rules and masks);
  its answer comes back as it is (`202 {run}` with the owner's id). An owner that cannot be reached, or answers `502`/`503`, leaves the run
  here (runs are reads: running it here is correct). Interactive and known heavy questions are answered where asked.
* **A run's own calls** (`/runs/{id}/result`, `cancel`, `export`, `share`, `promote`, every action) go to the node that holds the run
  (`RunForwardFilter`): the row's `node` when the row is readable here (a shared identity database), else the node the id names. `GET
  /runs/{id}` is answered here when the row is readable here. A node that does not answer gives `502 DRS-5052` ("its state is unknown
  until it returns; re-run it here"). A forwarded request is answered from its node only.
* **The list** (`GET /runs`) is scattered to the other members alive and merged by `submittedAt` (de-duplicated by id, so a shared
  database lists each run once); a node that does not answer is in `nodes.failed` with the reason. **Events:** `/runs/events` relays the
  `run` events of the caller's runs on every other member alive when the stream opens.
* **Leases and adoption** (13.3): a run becoming visible takes `lease_until = now + lease.ttl`; every `lease.renew` the node renews its
  runs (`RunRecords.renew`, a conditional update on `node`); a run whose row another node holds now is abandoned (its work interrupted,
  nothing saved). With a shared database, each node adopts the runs of members that are not alive whose lease lapsed and whose model it
  owns now, by one conditional update (`RunRecords.adopt`: `node`, `attempt + 1`, `queued`, `WHERE node = <dead> AND lease_until < now`),
  then starts them again under 12.1 (attempts, access as the runner now), the reason "started again on <node> after <dead> stopped".
  With a per-node database nobody else sees the row: its state is unknown (above) until the node returns. With the content store (the
  default) the same steps are compare-and-sets on the run's document (`ContentRunRecords`), and `RunForwardFilter` is kept for nodes whose
  runs store is `local`.
* Not built: the event relay does not pick up a member that joins after the stream opened (the console reopens the stream); with S6 the
  owner as coordinator (13.1, `engine: split`).
* `RunClusterTest` (two servers in one JVM over one identity database, ports from `-Ddrishti.net.it.ports`): a heavy run submitted on the
  node that does not own the model runs on the owner and its status, result and list answer from either node; a masked reader's run keeps
  the mask across the forward; the owner stopped with an unfinished run of its: the other node adopts it once (attempt 2) and finishes it.

### 14. API and CLI

#### 14.1 Endpoints

All are under `/api/v1/bi/runs`, as the token's user, with the `bi:write` scope for changes. Every answer has
`Cache-Control: no-store`.

| Method | Path | Body / query | Answer |
|---|---|---|---|
| `POST` | `/api/v1/bi/runs` | `{source, options}` (14.2) | `200` `{run, answer}` done within the wait; `202` `{run}` (with `Location`); `409` `DRS-9082` `{estimate, confirmToken}` |
| `POST` | `/api/v1/bi/runs/estimate` | `{source}` | `200` `{tier, engine, estimate, reuse: {key, runId} | null, confirm: bool}`; runs nothing |
| `GET` | `/api/v1/bi/runs` | `?mine=true&shared=true&workspace=&state=&source=&q=&since=&limit=50&after=` | `200` `{runs: [run], next, storage: {bytes, results}, nodes: {failed: []}}` |
| `GET` | `/api/v1/bi/runs/{id}` | | `200` `run` (14.3) |
| `GET` | `/api/v1/bi/runs/{id}/result` | `?format=json\|arrow&offset=0&limit=5000` | `200` the answer shape of BI_MODEL_SPEC 5.2 (JSON) or an Arrow stream with `X-Bi-Rows`, `X-Bi-Masked`, `X-Bi-Run`; `409` `DRS-9083` not done; `410` `DRS-9084` expired |
| `GET` | `/api/v1/bi/runs/events` | SSE | `hello` (the person's active runs), then `run` events `{id, state, progress, phase, rowsOut, code}`; `status` heartbeat every `drishti.bi.live.status` |
| `POST` | `/api/v1/bi/runs/{id}/confirm` | `{confirmToken}` | `202` `run` (queued) |
| `POST` | `/api/v1/bi/runs/{id}/cancel` | | `202` `run` |
| `POST` | `/api/v1/bi/runs/{id}/rerun` | `{data: "same"\|"latest", fresh?, wait?}` | as `POST /runs`; the run object adds `rerunOf`, `rerunData` (`same` or `latest`; the run's `data` keeps its data versions) and `fresh`. As built: `same` (the default) asks a one-date MQ or report visual as of the run's resolved date, `latest` drops that date so it resolves again now through the same as-of rule (a range, a SQL statement and a statement are asked as they were); `fresh: true` skips reuse and the single-flight join (the key, and so the access profile, is unchanged: it never shares across profiles), the run's owner only (`403 DRS-5002`) |
| `POST` | `/api/v1/bi/runs/{id}/export` | `{format: "csv"\|"xlsx"\|"parquet"\|"pdf"}` | `200` the file (`Content-Disposition`) |
| `POST` | `/api/v1/bi/runs/{id}/share` | `{to: ["bob", "role:risk-team"]}` | `200` `{shares: [{recipient, mode: "same"\|"re-masked"\|"recomputed", recipientRun}]}` |
| `DELETE` | `/api/v1/bi/runs/{id}/share/{recipient}` | | `204` |
| `POST` | `/api/v1/bi/runs/{id}/promote` | `{name?, title?, to: "dataset"\|"report"}` (as built, 9.4) | `201` `{snapshot, version, dataset: {name, item}, report: {item}\|null, yaml}` |
| `POST` | `/api/v1/bi/runs/{id}/keep` | `{until}` or `{days}` | `200` `run` |
| `DELETE` | `/api/v1/bi/runs/{id}/result` | | `204` (the row stays, `expired`) |
| `POST` | `/api/v1/bi/runs/{id}/schedule` | `{schedule: "business-days 07:30", data: "latest"}` | `201` `schedule` |
| `GET` | `/api/v1/bi/runs/schedules` | | `200` `{schedules: [schedule]}` |
| `PUT` / `DELETE` | `/api/v1/bi/runs/schedules/{id}` | `{schedule?, enabled?}` | `200` / `204` |
| `GET` | `/api/v1/bi/runs/settings` | | `200` `{inlineWait: "PT2S", maxWait: "PT10S", confirmAboveSeconds: 60, retentionDays: 30, eventsUrl}` (what the console needs) |
| `GET` | `/api/v1/bi/admin/runs` | bi-admin: `?node=&state=&user=` | `200` `{queue: {byClass, byUser}, running: [run], storage: {...}, accuracy: {...}, nodes: [...]}` |
| `POST` | `/api/v1/bi/admin/runs/{id}/cancel` | bi-admin | `202` `run` |

The existing endpoints (`POST /api/v1/bi/reports/{id}/query`, `POST /api/v1/bi/reports/query`, `POST /api/v1/bi/mq`,
`POST /api/v1/bi/models/{id}/query`, `POST /api/v1/bi/sql`, the statement endpoints) accept `Prefer: respond-async[, wait=<seconds>]`
and the body fields `interaction` (bool) and `supersedes` (string). With `Prefer` they answer as `POST /runs` does. Without it they
answer as today.

#### 14.2 Submit body

```json
{
  "source": {
    "type": "report",                       // report | board | mq | sql | statement
    "id": "rpt:desk-pnl", "page": "overview", "visual": "mtm-by-desk-month",
    "state": { "slicers": {"DESK": ["RATES-NY", "RATES-LDN"]}, "params": {"currency": "USD"}, "drill": [] }
    // or  "type": "mq",  "mq": { "mq": 1, "model": "trading", "select": {...}, "time": {"window": {"from": "2026-01-02", "to": "2026-09-30"}} }
    // or  "type": "sql", "sql": "SELECT desk, sum(mtm) FROM trading.trades GROUP BY desk", "model": "trading"
  },
  "options": {
    "wait": "PT2S",                         // the inline wait; 0 for fire-and-forget; capped by inline.max-wait
    "confirm": false,                       // true: accept the confirmation in advance (the CLI's --yes)
    "confirmToken": null,
    "interaction": false,
    "supersedes": null,                     // "<report>/<page>/<visual>": cancels an older question with the same key
    "title": null,                          // shown in Runs; default: the visual's title, else the question in words
    "data": "latest",                       // latest | "known-at:<instant>" (a re-run on the same data)
    "format": "json"                        // json | arrow, for an answer returned inline
  }
}
```

#### 14.3 The run object (the contract between the two builders)

```json
{
  "id": "r-java-2-01JAC4Q8Z6T3M4R9B0X1Y2Z3W4",
  "title": "MTM by desk and month",
  "source": {"type": "report", "id": "rpt:desk-pnl", "title": "Desk P&L", "page": "overview", "visual": "mtm-by-desk-month",
             "href": "/bi/r/rpt:desk-pnl/overview?run=r-java-2-01JAC4Q8Z6T3M4R9B0X1Y2Z3W4"},
  "runner": "ajsinha", "owner": "ajsinha", "workspace": "me",
  "params": {"slicers": {"DESK": ["RATES-NY", "RATES-LDN"]}, "params": {"currency": "USD"}, "filters": [{"field": "PRODUCT_TYPE", "op": "!=", "values": ["FX_SPOT"]}]},
  "asOf": {"from": "2026-01-02", "to": "2026-09-30", "dates": 182},
  "data": {"version": "a91c…", "words": "trade_endur delta v412 · desk_dim layers 2026-10-20 07:31",
           "tables": [{"table": "trade_endur", "connector": "trade-endur", "versions": "v412 (182 dates)"}]},
  "tier": "ad-hoc-heavy", "engine": "local", "class": "background",
  "state": "running", "progress": 0.29, "phase": "building 12 of 41 dates",
  "estimate": {"readRows": 1840000000, "buildRows": 410000000, "bytes": 62000000000, "rowsOut": 4320,
               "seconds": {"low": 150, "high": 330}, "basis": "delta-stats", "dates": 182, "datesToBuild": 41, "covered": false,
               "why": "182 business dates of trade_endur …; no rollup has grain desk × month"},
  "actual": {"rowsRead": null, "rowsOut": null, "bytesOut": null, "seconds": 112},
  "code": null, "reason": null,
  "node": "java-2", "attempt": 1,
  "submittedAt": "2026-10-20T09:14:03Z", "startedAt": "2026-10-20T09:14:04Z", "finishedAt": null, "expiresAt": null,
  "reuse": null,                            // {"of": "<run id>", "mode": "same"|"re-masked"} when reused
  "shared": {"from": null, "with": [{"recipient": "bob", "mode": "re-masked", "recipientRun": null}]},
  "group": null, "rerunOf": null, "promotedTo": null,
  "can": {"open": false, "cancel": true, "rerun": true, "export": false, "share": false, "promote": false, "schedule": true, "keep": false, "delete": false},
  "links": {"self": "/api/v1/bi/runs/r-java-2-…", "result": "/api/v1/bi/runs/r-java-2-…/result", "events": "/api/v1/bi/runs/events"}
}
```

As built (BIR-09), `data.tables` has one entry per table the planned question reads, `{table, connector, dates, rows, versions}` (`rows` null
when a date's size is unknown; `versions` like `v411, v412 (2 dates)`, `no version (1 date)`), and `data.words` puts them in one line
(`trades 13 K rows, v411, v412 (2 dates) · desks, no version (1 date)`). They come from the footprint at plan time and are kept with the
run's estimate (`RunDataWords`; no new column), so a question that was not planned has `words` null and `tables` empty.

As built (BIR-10), a report visual's run is titled with the visual's title (else the report's) unless `title` was given, and its
`source.title` is the report's title, kept with the row at submit (`BiReportService.titles`).

`can` is computed by the server for the caller, so the console never decides rights. As built (BIR-11) it offers only what this server can
do now: `share`, `export` and `promote` are built; `export` is false when distribution is off and for a statement run (its result is its
file); `rerun` is offered for mq, sql, report and statement runs (what can be asked again), `schedule` for mq, sql and report runs. As built (R1), `can` also carries `confirm`
(true for the runner of an `awaiting-confirm` run), an added field. Fields are never removed. A new field may be
added, and the console ignores fields it does not know.

#### 14.5 The console contract as built (server lane, 2.6.7)

The console lane (`bi-runs.js`, `bi-lib.js`, `runs_admin.html`) was built against 14.1 to 14.3. These points settle what the server answers:

1. **`409 DRS-9082`** carries `estimate`, `confirmToken`, `runId`, `run` (the run object, state `awaiting-confirm`, its `confirmToken` set) and
   `confirm` (the path). Either confirm it with `POST /api/v1/bi/runs/{runId}/confirm {confirmToken}`, or ask the question again with
   `confirm: true` and the `confirmToken` (in the body of `/bi/mq`, `/models/{id}/query`, `/bi/sql`, `/reports/{id}/query`, or in `options` of
   `POST /runs`): the waiting run is confirmed, no second run starts.
2. **`200` with `Prefer`** on the existing endpoints is **today's body**, with the run's id in the `Drishti-Run` header (and `X-Bi-Run`, kept).
   `POST /runs` answers `{run, answer}`.
3. **The run object** has `can.discard` (with `can.confirm`, the owner of an `awaiting-confirm` run) and `confirmToken`, set only for that owner
   while it awaits confirmation, else null.
4. **The list** takes `since=7d|30d|12h` or an ISO date or instant (anything else is `400`), and `state=` comma-separated (`state=done,reused`).
5. **`GET /api/v1/bi/admin/runs`** answers `now.slots.{interactive,standing,background}.{used,cap}`, `now.admissionWaitP95Ms`,
   `queue.{byClass,byUser,oldestSeconds,oldestUser,queued,workers}`, `running`, `storage.{resultsBytes,maxBytes,results,oldest,snapshotsBytes,
   snapshots,topUsers[{user,bytes}]}`, `accuracy.{medianRatio,p10,p90,samples,basis,unknownShare}`, `inline.{inlineShare,jobMedianSeconds}`,
   `settings.{file,inlineWait,confirmAboveSeconds,retentionDays}`, `nodes[{node,alive}]` and `suggestions`. `admissionWaitP95Ms` is the
   interactive admission wait over the last 5 minutes (null when nothing was admitted); `accuracy.basis` is `{<basis>: {medianRatio, samples}}`;
   `snapshots*` count `snapshots/` (empty until promotion is built); `suggestions` is 8.3a's list
   `[{id, model, grain, filters, runs, medianSeconds, status, rollup, yaml, words, decidedBy, item}]`, accepted or dismissed with
   `POST /api/v1/bi/admin/runs/suggestions/{id}/accept|dismiss`.
6. **The run object** has `schedule` (the owner's schedule of the run, else null) and `source.model` (the model of an mq or sql run, else null).
7. **The events stream**'s first event is `hello` with `{runs: [...]}` (the caller's unfinished runs).
8. **A re-ask after `DRS-9089`** with `interaction: false, confirm: true` is accepted: it is planned as a normal question, confirmed in advance.
9. **`/reports/{id}/query`** with `Prefer` takes `interaction` and `supersedes` beside `page`, `visual` and `state`; without `Prefer` it is
   unchanged (both checked in `ModelAcceptanceTest`). Two faults were found and fixed: a slicer's second re-ask on the same visual failed
   `DRS-9080` because the first, finished and invisible, was "cancelled" (the parameter-slicer timeout), and an MQ with `options.explain` went
   through the pipeline and lost its SQL (the explain panel); it now answers at once.

#### 14.4 CLI

A new module, `tools/biruns.py`, is registered under `drishti.py bi` like `biitem.py`. It uses the same `Api`, `CliError` and
`say_json`.

```
drishti.py bi runs list [--state running|done|failed|…] [--source report|mq|sql] [--since 7d] [--all-nodes] [--json]
drishti.py bi runs get RUN [--json]
drishti.py bi runs submit (--mq FILE | --sql TEXT --model M | --report ID --page P --visual V [--state FILE])
                          [--wait 2s] [--yes] [--title T]            # prints the run id, or the answer when done in time
drishti.py bi runs estimate (same source options)                    # tier, engine, estimate; runs nothing
drishti.py bi runs wait RUN [--timeout 30m]                          # follows /runs/events; exit 0 done, 3 failed, 4 cancelled
drishti.py bi runs result RUN [--format csv|json|arrow|parquet] [--out FILE]
drishti.py bi runs cancel RUN
drishti.py bi runs rerun RUN [--data same|latest] [--wait 2s]
drishti.py bi runs export RUN --format xlsx --out FILE
drishti.py bi runs share RUN --to bob --to role:risk-team
drishti.py bi runs promote RUN [--to dataset|report] [--name NAME] [--title TITLE]
drishti.py bi runs keep RUN --days 90
drishti.py bi runs schedule RUN "business-days 07:30" [--data latest]
drishti.py bi runs schedules [list|disable ID|enable ID|delete ID]
drishti.py bi runs storage                                            # bi-admin: Admin's storage numbers
```

`drishti.py bi mq FILE` and `drishti.py bi sql` gain `--async [--wait 2s] [--yes]`, which sends `Prefer: respond-async` (and with `--yes` `confirm: true`) and prints the run id
when the answer is `202`.

### 15. Settings: `config/runs/runs.yaml`

There are more than a handful of settings, so they get their own file, following `config/connectors/`, `config/net/` and
the planned `config/db/database.yaml` (DATA_ARCHITECTURE.md › The one Drishti database (design, not built), section 7; it was not built when runs shipped). `application.yaml` keeps only the pointer:

```yaml
drishti:
  bi:
    runs:
      file: ${DRISHTI_BI_RUNS_FILE:./config/runs/runs.yaml}   # BI runs (section 17); ready-made in config/runs.examples/
```

The two class caps belong to the engine's gate and sit beside its other settings in `application.yaml` (`drishti.bi.exec.*`):

```yaml
    exec:
      background-share: 0.25     # the most of the slots background work (runs, refresh) holds at once
      standing-share: 0.25       # the most standing-query maintenance holds at once
      fragments-share: 0.5       # the most of a connector's max-fragments runs' layer builds hold
```

The shipped `config/runs/runs.yaml` (every key has its default in `RunsProperties`, so a missing file means the defaults):

```yaml
enabled: true                      # false: every question runs as before 2.6.7 (synchronous), no Runs tab
inline:
  wait: 2s                         # how long a request waits before its run becomes a visible job
  max-wait: 10s                    # the most a caller may ask for (Prefer: wait=)
interactive:                       # the bounds of the interactive tier
  max-seconds: 10                  # the estimate's high end
  max-rows: 50000000               # rows read
  max-build-dates: 2               # partitions it may build from the source
confirm:
  above-seconds: 60                # ad-hoc heavy runs above this ask first
  above-rows: 500000000
  unknown: true                    # an estimate with basis unknown asks first
  ttl: 15m                         # an unconfirmed run is discarded after this
queue:
  per-user: 5                      # a person's visible runs queued + running (DRS-9081)
  max: 200                         # all queued runs on this node (DRS-9081)
workers: 2                         # runs executing at once on this node
run:
  max-duration: 4h                 # a run's time limit (DRS-9024)
engine:                            # the run engine: a second DuckDB instance for ad-hoc heavy runs
  memory-limit: 2GB
  threads: 2
  max-spill: 20GB
  max-read-rows: 5000000000        # past it, and no split or external engine: DRS-9086
  prefer-external-above-seconds: 0 # 0: never prefer an external engine while local fits
engines: {}                        # external engines (3.0 groundwork): athena: {…}, trino: {…}, spark: {…}
estimate:
  low-factor: 0.5                  # the range before 20 runs of history narrow it
  high-factor: 2.0
  rates:                           # seeds, replaced by measured rates
    scan-rows-per-second: 50000000      # DuckDB over Parquet layers
    build-rows-per-second: 300000       # a connector's column scan into a layer
    record-rows-per-second: 2000        # the record-by-record path
store:
  dir: ${drishti.data.dir:./data}/bi-runs
  max-bytes: 20GB                  # unpromoted results on this node
  max-bytes-per-user: 2GB
  max-result-rows: 50000000        # DRS-9086
  max-result-bytes: 2GB            # DRS-9086
  min-seconds: 1                   # an interactive answer faster than this is not stored
  keep-interactive: true
retention:
  days: 30                         # after the last open, unless promoted or kept
  max-days: 365                    # the most "Keep longer" allows
  sweep: 1h
restart:
  resume-running: true             # a run interrupted by a restart starts again
  max-attempts: 2                  # then it fails with DRS-9085
  requeue-changed-model: false     # a queued run whose model changed fails instead of running on the new model
lease:
  renew: 10s
  ttl: 45s
cancel:
  grace: 5s                        # Admin flags a cancelled run whose resources are not released by then
workspace-visibility: owner-and-shared   # owner-and-shared | members (members see others' run rows, never results)
promote:
  enabled: true
  versions-keep: 5
notify:
  on: [done, failed]               # also: cancelled-by-others (always on), started-again (always on)
```

The console needs no new setting. It reads `GET /api/v1/bi/runs/settings`.

### 16. Error codes

New codes in the BI engine's range `DRS-90xx`, in the free block `DRS-9080` to `DRS-9089`. `ErrorCode.java` adds them and API_GUIDE's
and TROUBLESHOOTING's tables follow (the docs tests require both).

| Code | Name | HTTP | Meaning | Fix shown |
|---|---|---|---|---|
| `DRS-9080` | `RUN_NOT_FOUND` | 404 | no such run, or not one the caller may see | check the id; a shared run needs its share |
| `DRS-9081` | `RUN_QUEUE_FULL` | 429 | the caller has `queue.per-user` runs queued or running, or the node's queue is at `queue.max` | wait for one to finish or cancel one; `Retry-After` |
| `DRS-9082` | `RUN_CONFIRM` | 409 | ad-hoc heavy above the confirm threshold; the body carries the estimate and a `confirmToken` | confirm, narrow the dates, or add a rollup |
| `DRS-9083` | `RUN_NOT_DONE` | 409 | the result of a run that is queued, running, failed or cancelled | wait, or read its reason |
| `DRS-9084` | `RUN_EXPIRED` | 410 | the result was removed by retention or the storage budget | re-run on the same or the newest data |
| `DRS-9085` | `RUN_INTERRUPTED` | 503 | the server or node stopped while the run waited or ran, and it could not start again (attempts, access, a changed model) | re-run; if it repeats, narrow it |
| `DRS-9086` | `RUN_TOO_LARGE` | 413 | the estimate exceeds every available engine's limits, or the result exceeds `store.max-result-rows`/`max-result-bytes` | aggregate, narrow, add a rollup, or configure an engine |
| `DRS-9087` | `RUN_NODE_UNREACHABLE` | 503 | the node that owns the run did not answer | try later, or re-run elsewhere |
| `DRS-9088` | `RUN_STORE_FULL` | 507 | the result store is at its budget and nothing can be removed | an administrator frees space or raises `store.max-bytes` |
| `DRS-9089` | `RUN_NEEDED` | 409 | a dashboard interaction would need a background run; nothing ran; the body carries the estimate | *Run in background*, or ask for a rollup at this grain |

Existing codes keep their meanings: `DRS-9025` (an interactive question found no slot in time), `DRS-9029` (a synchronous caller asked
an ad-hoc heavy question; now with a `run` hint), `DRS-9024` (a time limit), `DRS-9028` (memory), `DRS-9021` (masked or no access).

### 17. Metrics and Admin

#### 17.1 Metrics (Micrometer, beside `BiMetrics`)

| Metric | Tags | What |
|---|---|---|
| `drishti.bi.runs.submitted` (counter) | `tier`, `source` | questions submitted |
| `drishti.bi.runs.finished` (counter) | `tier`, `outcome` (done, reused, failed, cancelled), `code` | outcomes |
| `drishti.bi.runs.inline` (counter) | `result` (inline, job) | how often the inline wait was enough |
| `drishti.bi.runs.seconds` (timer) | `tier`, `engine` | run time |
| `drishti.bi.runs.queue` (gauge) | `class` | queued runs |
| `drishti.bi.runs.running` (gauge) | `engine` | running runs |
| `drishti.bi.runs.estimate.ratio` (distribution) | `basis`, `engine` | actual ÷ estimated seconds |
| `drishti.bi.runs.reuse` (counter) | `mode` (same, re-masked, recomputed, joined) | reuse |
| `drishti.bi.runs.store.bytes` (gauge) | `kind` (results, snapshots) | storage |
| `drishti.bi.runs.cancel.release` (timer) | | time from cancel to every resource freed |
| `drishti.bi.admission.wait` (existing) | add `class` | the interactive wait under background load: the fairness signal |

#### 17.2 Admin → BI → Runs (`/admin/bi/runs`, bi-admin)

```
 Admin · BI · Runs                                                               node [all ▾]   ⟳ live
 ────────────────────────────────────────────────────────────────────────────────────────────────────
  Now        interactive 3/8 slots · standing 1/2 · background 2/2 (cap) · queue 7 (4 people)
             interactive admission wait p95 (5 min) 12 ms                     ✓ background not starving it
  Running    r-java-2-01JA…  ajsinha  MTM by desk and month     java-2  building 12/41  1 m 52 s   [Cancel]
             r-java-1-01JA…  bob      Raw trades Q3             java-1  scanning 61 %   3 m 10 s   [Cancel]
  Queued     5 runs · oldest 2 m (carol) · per person: carol 3, dave 2
  Storage    results 11.2 GB of 20 GB (1,904 results, oldest 2026-09-21) · snapshots 3.4 GB (12)
             top users: ajsinha 1.9 GB · bob 1.7 GB · carol 1.2 GB                          [Sweep now]
  Estimates  last 7 days: median actual/estimate 1.08, 90 % within 0.6–1.9 (delta-stats); 14 % basis unknown
  Inline     81 % of questions answered inline; 19 % became jobs (median 3 m 40 s)
  Settings   config/runs/runs.yaml · inline 2 s · confirm above 60 s · retention 30 days     (read-only here)
```

The existing `GET /api/v1/bi/engine` (`QueryService.stats`) also reports `admission.byClass` and the run engine's memory.

### 18. Build steps (two builders)

The server lane is builder A (pipeline, planner, store). The console lane is builder B (Runs tab, inline wait, CLI). Builder A
publishes the contract (14.3 and the endpoint table, as stubs answering fixed shapes) on day one, so builder B builds against it.
Targeted tests only between steps, on the 10K-trade lake at most, one heavy process at a time. One drill at the end of the release.

#### 18.1 Server lane (A)

| Step | Work | Done when |
|---|---|---|
| **R1** contract | `com.ash.drishti.server.bi.runs`: `Run`, `RunState` (the transition table), `RunJson` (14.3), `RunsProperties` + `config/runs/runs.yaml` + `config/runs.examples/`; `ErrorCode` `DRS-9080`–`9089`; `RunsController` with every endpoint of 14.1 answering stub shapes; the `drishti_bi_run`, `drishti_bi_run_share` and `drishti_bi_run_schedule` tables in both identity schema files (parity test) | the console builds against the stubs; `RunStateTest`; schema parity |
| **R2** fairness | `Admission` class caps (`background-share`, `standing-share`), `Waiter.cancel()`; `DatasetRefresher` → `BACKGROUND` through `Admission`; `FragmentPools` `fragments-share` and interruptible acquire; the run engine (second `DuckDbEngine`) | `AdmissionClassCapTest`: 8 background waiters never take more than 2 slots and an interactive waiter is admitted at once |
| **R3** planner | `RunPlanner`, `RunRates`, `RunHistory`; `Layers.peek`; `SourcePlugin.rowEstimate` (default empty) with Delta (`DeltaTable.Layout` add-action stats) and Iceberg (`ManifestReader` `record_count`); `QueryService.estimate` moved before every `Layers.columns` build | `RunPlannerTest` (tiers, confirm, refuse); an estimate reads no data (counted connector reads = 0) |
| **R4** pipeline | `RunService` (submit, futures, inline wait, visibility, `supersedes`, gone-caller cancel, single-flight join), `RunQueue`, `CancelToken` through `QueryService`, `TableBuilder`, `DuckDbEngine`; `Prefer: respond-async` on `BiReportController`, `MqController`, `SqlController`, `StatementController`; `interaction` → `DRS-9089` | `RunServiceTest`; the existing BI tests pass unchanged (synchronous behaviour kept) |
| **R5** store | `RunStore` (key, `COPY TO` Parquet, `answer.json`, `FileSwap`, paging to JSON and Arrow), reuse, `RunSweeper` (retention, budgets, `tmp/`) | `RunStoreTest`; a stored result reads back equal to the inline answer |
| **R6** sharing and promotion | `RunSharing` (same, re-mask, recompute; `SecuritySignature` row part and mask part exposed), role expansion; promote: `DatasetDef.Source.RUN`, its parser and `Layers` reading `snapshots/`; promote to report; `RunSchedules` on `SubscriptionSchedule`; export through `BiExporter` | `RunSharingTest` (section 19.3) |
| **R7** restart and cluster | `RunRecovery`; leases; `ShardDirectory` wired to Drishti*net members; forwarding of submit, get, result, cancel; scatter of list; event relay; adoption by conditional update | `RunRecoveryTest`, `RunClusterTest` (two nodes in one JVM, as `TwoNodesTest`) |
| **R8** notify and metrics | `RunNotifier` through `BiNotifier.tell`; metrics of 17.1; `GET /api/v1/bi/admin/runs`; `/engine` additions | notices carry no values (test) |

#### 18.2 Console lane (B)

| Step | Work | Done when |
|---|---|---|
| **R1c** Runs tab | `workspace.html` tab strip (Items · Runs · Members, `?tab=`); `bi-runs.js`: the list, filters, live rows from `/runs/events`, the run drawer and every action, as the run's `can` allows; the rail link and count; `BI RUNS <GO>`; `routes/bi_routes.py` `_ALLOWED` gains `runs` | against the stubs; `test_bi_runs_page.py` |
| **R2c** inline wait | `bi-lib.js` `B.api` sends `Prefer: respond-async, wait=<inlineWait>` for visual, MQ and SQL queries and handles `202`, `409 DRS-9082` and `409 DRS-9089`; `bi-report.js` and `bi-report-board.js`: the run card, progress, cancel, drawing from the result on `done`, `?run=<id>`; `interaction` and `supersedes` from `stateFor`; the confirm dialog; Analyse and the SQL editor likewise; the proxy's wait for asynchronous calls | `test_bi_runs_inline.py` (the proxy and the JS contract) |
| **R3c** bell and inbox | `bi-run` notices render with their link (`inbox.js`, the inbox template); the group rings once | test |
| **R4c** CLI | `tools/biruns.py` (14.4), `bi mq` / `bi sql` `--async`, completion (`tools/completion.py`) | `tools/test_cli_biruns.py` |
| **R5c** Admin and help | `/admin/bi/runs` (17.2); help cards in `drishti-console/config/help.yaml` for the Runs tab, the run card and the confirm dialog | `test_help.py`, `test_help_links.py` |
| **R6c** browser test | section 19.6 | passes in the drill |

#### 18.2a Built early (R1 types and the R3 maths, not wired)

`com.ash.drishti.server.bi.runs` holds `Run`, `RunState`, `Tier`, `RunId`, `DataVersionVector`, `ResultKey`, `PlannerSettings`,
`RunEstimate`, `RunEstimator` and `QuestionNormaliser`, with `RunStateTest`, `ResultKeyTest`, `RunEstimatorTest` and
`QuestionNormaliserTest`; `DRS-9080`..`9089` are in `ErrorCode`. Coding them corrected the design in these places:

* **Transitions the diagram of 3.3 lacks:** `planned → running` and `running → reused | failed` (a twin joining a running run, 13.3);
  `storing → cancelled` (section 11 cancels while storing); `storing → queued` (12.1 restarts `storing` rows); `reused → expired` (the
  shared result is swept); `queued → failed` and `awaiting-confirm → failed` (DRS-9085 at restart); `running → done` for an inline answer
  that is not stored. 12.1's "queued (or awaiting-confirm) → back in RunQueue" is wrong for an unconfirmed run: it stays
  `awaiting-confirm` (or fails), it is never queued without its confirmation.
* **The basis** of a partly built plan is the source behind the most *unbuilt* rows (built partitions are certain), which gives
  `delta-stats` in 4.4's example; *layers* only when nothing is to build.
* **"Divided by its fragment share"** in 4.2 is read as: build seconds per table ÷ the fragments a run may use at once
  (`max-fragments × fragments-share`, at least 1).
* **`rowsOut`** is also capped by `readRows` (the product of grain cardinalities can pass the rows read), and saturates.
* **Relative dates are RelativeDates' names** (`this-year`, `previous-month`, `last-5-business-days`): 8.3a's "last month" is
  `previous-month`. A `relative` filter normalises to `between [from, to]` exactly as `MqCompiler` compiles it; `all` to no filter.
* **Normalising sorts the grain and the measures,** so the stored result's column order (and its row order when the question has no
  `sort`) can differ from the asker's: the result reader must project the asker's own order. `between` bounds, `sort` and
  `totals.levels` keep their order; `mq` (the language version) is dropped.

#### 18.2b As built (server lane)

| Step | Status | What was built |
|---|---|---|
| R1 | built | `RunsProperties` (reads `config/runs/runs.yaml`, every key defaulted; examples in `config/runs.examples/`: `laptop.yaml`, `cluster.yaml`, `off.yaml`), `RunsConfiguration`, `RunJson` (14.3), `RunProblem` (a problem with extra properties and `Retry-After`), `RunsController` (every endpoint of 14.1), the three tables in both schema files, `SchemaParityTest`; `Run` gained `title`, `reuseOf`, `rowsRead` and a `Builder` |
| R2 | built, except the run engine | `Admission` class caps (`classCap`, `background-share` and `standing-share` from `drishti.bi.exec`, `byClass` in its stats; an interrupted waiter leaves the line at once, which is how a cancelled run's waiter is removed); `ExecProperties` `backgroundShare`, `standingShare`, `fragmentsShare`; `QueryService.refreshInBackground` (the `BACKGROUND` class, through admission), used by `DatasetRefresher`; `FragmentPools` gives background work at most `fragments-share` of a connector's pool. **Not built:** the second DuckDB instance for runs (section 10.3): runs execute on the one engine, bounded by the class cap and `workers` |
| R3 | built | `RunPlanner` (footprint → `RunEstimator` estimate → decision), `RunRates` (scan and build rates and the actual ÷ estimate ratios per engine, learnt from finished runs; the range factors narrow after `minRatioSamples`), `RunHistory` (rows per day per table, an exponentially weighted mean of the built partitions the planner peeks at; in memory, learnt again after a start); `Layers.peek` and `peekRollup` (`meta.json` only, nothing built, no data file opened); `SourcePlugin.rowEstimate(kind, date)` (default empty) with `RowEstimate(rows, basis)` in drishti-api, Delta from the add actions of the current snapshot (`numRecords` of their statistics, files without statistics by bytes ÷ the bytes per row of those with), Iceberg from the manifests' `record_count` of the date's data files (an upper bound when there are delete files), `SourceGateway.rowEstimate` summing a union's connectors; `QueryService.footprint` (`Footprints`: tables, dates, rows with their basis, built or not, the rollup the optimiser would choose and its valid partitions, the data version vector), reached from `MqService.footprint` (a `top` filter, which needs data to compile, and staged tables give no footprint). **The synchronous `DRS-9029` now comes from the footprint before any layer build** (the check after the build stays, for dates nobody can size); it covers the question's own range, not the extra ranges a time-intelligence measure compiles. Tiers, `DRS-9082` (a visible `awaiting-confirm` row and a token; `POST /runs/{id}/confirm`, or the question asked again with `confirmToken`), `DRS-9086` and `DRS-9089` at plan time; an ad-hoc heavy run without confirmation answers `202` at once. An interaction whose size is unknown is not refused `DRS-9089`: it runs interactively, bounded by the interactive limit (refusing every first slicer on a connector that cannot count would break dashboards). **Report visuals are planned (BIR-06):** the visual's main question, as the report asks it of its model (`BiReportService.footprint`, `DatasetService.footprint`, `ModelGateway.footprint` and `footprintModel` in `ConsumerQueries`), is sized as an MQ is: ad-hoc heavy answers `202` at once, above the thresholds `409 DRS-9082` (confirmed by token or `POST /runs/{id}/confirm`), an interaction `409 DRS-9089`, past every engine `DRS-9086`. A visual of **unknown** size is neither asked to confirm nor refused (`RunSources.reportPlan`): it waits inline and becomes a run past the wait, as before, so a page on a connector that cannot count does not ask for every visual (the same rule as an interaction of unknown size). Totals and highlight queries are not sized. Without `Prefer` nothing changes (`ModelAcceptanceTest` order 19, `RunServiceTest`). `RunPlannerTest`; `rowEstimate` tests in `DeltaLayoutTest` and `IcebergSourcePluginTest` |
| R4 | built | `RunService` (submit, inline wait, visibility, `supersedes`, cancel by interrupt, single-flight join, `interaction`), `RunQueue` (fair share, `workers`, `queue.max`), `RunRecords` (JDBC over the identity database, or memory), `RunSources` (mq, sql and report questions computed as the caller), `Prefer: respond-async` on `MqController` (both endpoints), `SqlController`, `BiReportController` (JSON; Arrow, EXPLAIN and an MQ with `options.explain` stay synchronous). Without `Prefer` every endpoint is unchanged (`ModelAcceptanceTest` order 17 asks all four endpoints both ways and compares the answers, for a raw and a masked reader). **Gone caller:** when the request thread waiting for an invisible run is interrupted, the run is cancelled and its work thread interrupted (`cancel.release` metric); Spring MVC does not tell a blocked request that its connection closed, so a closed browser tab is noticed only when the container interrupts the request (an async timeout), not at once. A superseded twin that already finished no longer fails the newer question with `DRS-9080` (the fault behind the console's parameter-slicer test). `StatementController` (preview and file) takes `Prefer` too: a statement is a run with a personal signature; inside the wait the same answer (the file, with `Drishti-Run`), past it `202` and a result of `{format, filename, contentType, pages, bytes}` (`StatementApiTest`) |
| R5 | built, JSON results | `RunStore`: `results/<xx>/<key>/answer.json` (the answer with its rows, the signature it was computed for) and `owner.txt` (whose budget it counts against) under `store.dir`, written beside their names and moved with `FileSwap`; served only to the same signature; paging; `sweep` (retention by last open, **`store.max-bytes-per-user`: a person's least recently opened results go first, nobody else's**, `store.max-bytes`, stale temporary files) every `retention.sweep`; `usage()` gives bytes, count, the oldest open and the five largest users. The key is `sha256(question \| signature \| data version \| v1)`. **The data version of a planned MQ or SQL question is the hash of its data version vector** (every table and date read, `SourcePlugin.dataVersion(kind, date)`) with the model's hash, so a stored result is reused after a restart while the data is unchanged and never once a date is restated; a source without a version or a live table makes it not reusable. Questions without a footprint keep the in-process generation (a boot id is part of it). A report visual is keyed by the viewer's **access profile** (9.3a: `BiReportService.accessProfile`, the model signature, the report's definition, the data version vector and resolved dates), so viewers with the same roles, masks and row rules reuse or join one run; one that names the viewer (`ViewerIdentity`), a personal model, a live or version-1 dataset, or one without a data version keeps a personal signature and no data version (`RunSourcesReportTest`, `ViewerIdentityTest`, `ModelAcceptanceTest` order 20). Not built: Parquet via `COPY TO`, Arrow results |
| R6 | built | **Sharing** (`RunSharing`, `RunShares` over `drishti_bi_run_share`): `SecuritySignature.rowPart` (rules, denied layers, hidden columns, denied kinds; the reader for a personal model) and `maskPart` (`alias.column`) decide per recipient: `same` (equal signature), `re-masked` (equal row part, the recipient's masks ⊇ the runner's, the question validates as the recipient) or `recomputed` (a new run as the recipient, `shared_from` the original, the background class; equal signatures join one computation). **Corrected while building:** masking on output is *not* what the engine does for a grouping field: the engine does not group by a masked column, so its groups collapse into one. Re-masking therefore masks the value **and** collapses the groups, summing the measures, which is exact only when every measure is additive and there is no limit, top filter or totals; otherwise the share is recomputed. A SQL statement or a report visual is `same` or `recomputed`. **The decision is taken again at every read** as the recipient is now, so a recipient whose rights narrowed is refused `DRS-9021` ("re-run it"), never shown the wider answer. `role:x` expands to its members when shared; a person who may not read the model gets no share. The recipient sees the run in their list and `GET` (`asRecipient`), reads `/result` and exports their own version; the owner's run object lists `shared.with`. Notices name the sharer and the title only (`RunSharingTest`, 19.3: an admin sharing with a viewer is re-masked and equals the viewer's direct query; a viewer sharing with an admin is recomputed as the admin; other desks recomputed with no row of the runner's; a role of three, two alike, computes twice). **Export:** `POST /runs/{id}/export {format: csv|xlsx}` through `BiExporter.ofAnswer`, `CsvWriter`, `XlsxWriter` (`RunExportTest`). **Schedules** (`RunSchedules` over `drishti_bi_run_schedule`, `SubscriptionSchedule`'s grammar without `after-load`): each firing asks again as the owner now; an owner who lost access gets a failure and a notice; checked every 30 s; the run object's `schedule` (`RunSchedulesTest`). **Promotion** (9.4 as built): `DatasetDef.Source.RUN` (`source: {run, version, token}`) and its parser, the snapshot read as a staged table through `ModelCatalog.Snapshots`, `POST /runs/{id}/promote` to a draft dataset and optionally a draft report, the snapshot bound to its dataset by name and token, versions kept per `promote.versions-keep` (`RunPromotionTest`, `ModelAcceptanceTest` order 18) |
| R7 | built (13.4) | **Cluster:** `RunCluster` (`ShardDirectory` over Drishti*net members), forwarding of submit to the owner and of a run's calls to its node (`RunForwardFilter`), the list scattered and merged, events relayed, leases renewed and adoption by conditional update (`RunClusterTest`). **Restart:** `RunService.recover` at start: every unfinished row of this node is replayed as its runner (`Principals.of`, attempt + 1, up to `restart.max-attempts`) or failed `DRS-9085` with a reason (a plain reason, "…while this run ran, and it could not be started again": the console adds the fix, BIR-13); each change is published and told. **Statement runs replay too** (2.6.7 A4): the preview or file is made again as its runner from the request it was asked with (`RunSources.statements`, `StatementController.fileAsRun`), no longer `DRS-9085`; a replayed run's signature is the runner's now, so its result reads back (`StatementReplayTest`). **A run waiting for its confirmation keeps waiting** across a restart or an adoption (BIR-08): its question is bound again as its runner, with a new token (the owner's run object carries it), until `confirm.ttl` from when it was asked; past that, or when it cannot be asked again, it is cancelled quietly (`DRS-9083`, "not confirmed within … min" or "the confirmation lapsed when the server restarted; nothing ran") with no bell, since nothing ran (`RunServiceTest`) |
| R8 | mostly | `bi-run` notices through `BiNotifier.tell` on done, failed and cancelled-by-others (names and counts only, tested); metrics `drishti.bi.runs.submitted`, `finished`, `inline`, `reuse`, `seconds`, `estimate.ratio` (distribution, tags `basis`, `engine`), `cancel.release` (timer); `GET /api/v1/bi/admin/runs` in the shape of 14.5 (`RunAdmin`); the gauges `drishti.bi.runs.queue` (class), `running` (engine), `store.bytes` (kind `results`, `snapshots`); `GET /api/v1/bi/engine` adds `runs` (workers, running, queued, the engine runs execute on: `shared`, no second instance yet); `now.admissionWaitP95Ms` (`Admission.interactiveWaitP95Ms`), `accuracy.basis` and `storage.snapshots*` filled (`RunGaugesTest`). **8.3a suggestions built** (`RunSuggestions`): finished ad-hoc heavy model queries counted per model, grain and filters' shape (field and operator, never a value) in `suggest.window`; past `suggest.min-runs` with a median of `suggest.min-seconds` a suggestion with its rollup drafted; accept adds the rollup to the model's draft under `rollups:` (comments kept, reviewed as any change), dismiss; decisions in `<store.dir>/suggestions.json`; `built` once the model has a rollup over that table at that grain (`RunSuggestionsTest`). Not shown in the model designer yet (console lane) |

#### 18.3 Together

**R9:** the load test (19.5), the docs of section 20, the tutorial, then the experience QA round and one drill.

### 19. Tests

#### 19.1 Unit

* `RunStateTest`: every allowed transition, and every illegal one throws.
* `RunPlannerTest`: a fixture model and lake of 10K trades over 60 days. One date gives interactive; a year covered by a rollup gives
  known heavy; a year at a grain no rollup has gives ad-hoc heavy; a missing rollup date with small raw rows gives interactive
  (mixed); `rowEstimate` empty plus no history gives `basis: unknown` and confirm; above `engine.max-read-rows` gives `DRS-9086`.
  **The estimate opens no data file** (a counting `SourceGateway`).
* `RowEstimateTest` (Delta, Iceberg): numRecords summed per date; files without stats fall back to bytes per row; equal to the true
  count on the fixture.
* `AdmissionClassCapTest`: the class caps, `Waiter.cancel()`, and interactive preferred under every mix.
* `RunStoreTest`: the key changes with each component; a restated date makes a new key; a source without a data version is never
  reused; Parquet round trip equals the answer (types, masks, totals); `FileSwap` replace under a held file.
* `RunSweeperTest`: retention by last open, budgets per node and per user, promoted results kept, `tmp/` swept.

#### 19.2 The inline-versus-job threshold

* A question that takes 1.5 s with `inline.wait: 2s` answers `200` with today's body, writes no row and sends no notice.
* One that takes 2.5 s answers `202` at 2 s. Its row appears, it finishes, the notice arrives, and the result equals the synchronous
  answer.
* An ad-hoc heavy question answers `202` at once (it does not wait 2 s).
* `inline.wait: 0` makes everything a job; `enabled: false` makes everything synchronous as in 2.6.6.
* An interaction that is ad-hoc heavy answers `DRS-9089` within 50 ms and nothing runs (counted engine calls = 0).
* An interaction still running at the interactive limit is cancelled and never becomes visible.
* Without `Prefer`, every existing BI test passes unchanged.
* The time is controlled by a test `RunEngine` that sleeps a set time, so the threshold is tested exactly without heavy data.

#### 19.3 Masks on shared runs

On the fixture with the pack's desk rules and masks (`PRICE`, `TRADER_ID`, `NAME`, as the soak uses):

* runner and recipient with the same desks and masks: `same`, and the recipient reads the identical result;
* recipient with the same desks and more masks: `re-masked`; the recipient's extra columns are the mask on every row, never a value
  (checked cell by cell), and the runner's view is unchanged;
* recipient with other desks: `recomputed`; the recipient's rows equal a direct query as the recipient, row for row; no row of the
  runner's desks appears;
* a recipient whose masked column feeds a measure: validation refuses re-mask and the run is recomputed (or refused `DRS-9161` for
  the recipient, as a direct query would be);
* a share to a role with three members, two with equal signatures: two recomputations, not three;
* a notice body and a run row never contain a data value (scan for the fixture's values);
* a promoted snapshot keeps the promoter's masks for every reader and applies its own rules on top.

#### 19.4 Restart and cancellation

* Restart with one run queued, one running and one storing (`RunRecoveryTest` stops the service between phases): queued are
  requeued in order; running starts again with `attempt 2` and finishes; a second interruption makes `DRS-9085`; `tmp/` is swept;
  each change is notified; nothing stays `running`.
* A queued run whose model changed fails with the model named.
* Cancel in each phase (queue, admission wait, building, scanning, storing): the admission slot is free in under 100 ms; the
  connector's fragment pool is back to full; DuckDB's connection is closed; no `tmp/` remains; `drishti.bi.runs.cancel.release`
  p99 is under `cancel.grace`.
* A gone caller: closing the HTTP connection of an invisible run cancels it (counted engine work stops).
* Cluster (`RunClusterTest`, two nodes in one JVM over a shared SQLite in tests and a per-node SQLite): the owner runs it; the other
  node shows its status and result; killing the owner mid-run makes the new owner adopt it (shared database) or shows *unknown* (per
  node); a returning old owner abandons its copy.

#### 19.5 Load: heavy runs never starve interactive queries

Within the local safety rule (10K trades at most, one heavy process): heaviness comes from **many dates and cold layers**, plus a
`DelayingSourceGateway` that makes each layer build take 2 s and each run-engine scan 5 s. No large data is used.

```
 baseline   : 20 interactive probes (one date, one desk; layers warm) at 5/s for 60 s       → p50, p95, refusals
 burst      : 40 ad-hoc heavy runs from 10 users submitted in 5 s (each 60 cold dates)
              + the same interactive probes for 120 s
 assert     : probe p95 under burst ≤ baseline p95 × 1.5 + 50 ms
              zero DRS-9025 among probes
              background slots never above the cap (sampled every 100 ms)
              queue.per-user refusals (DRS-9081) for users past 5, none for others
              every run ends done or DRS-9081; heap after GC within 20 % of baseline; no virtual-thread growth
              (count sampled: the QA3A-01 signature)
```

It runs as `drishti-it` `BiRunsLoadIT` (excluded from the default build, run in the drill), with its report in
`docs/admin/PERFORMANCE.md`.

#### 19.6 Browser test

`test_bi_runs_browser.py` on the demo server:

1. Open the Desk P&L report; the visuals on one date draw inline with no run card (as today).
2. Set the period to *this year* on a visual at a grain no rollup has: the confirm dialog shows a range and its basis; *Run in
   background* shows the run card with progress.
3. Move to another page; the bell's count goes up when the run finishes; the inbox row opens the report with `?run=`, drawn from the
   result.
4. A slicer on a covered visual answers instantly with no card; a slicer that would need a run shows *Run in background*.
5. The Runs tab lists the run with as-of, data version, estimate and actual; re-run on the same data reuses it (`reused`); cancel a
   second run and its row says *Cancelled by you*.
6. Share with a viewer; signed in as the viewer, the run shows masked columns as the mask.
7. Keyboard only: tabs, the list and the drawer are reachable; status changes are announced (`aria-live`).

### 20. Docs to update when built

| Doc | What |
|---|---|
| [BI_GUIDE.md](../guides/BI_GUIDE.md) | runs: the inline wait, the run card, the Runs tab, estimates and tiers, sharing, promotion, schedules |
| [USER_GUIDE.md](../guides/USER_GUIDE.md) | the bell's run notices; `BI RUNS <GO>` |
| [API_GUIDE.md](../guides/API_GUIDE.md) | the endpoints of 14.1, `Prefer: respond-async`, the run object, `DRS-9080`–`9089` in the error table |
| [CLI_GUIDE.md](../guides/CLI_GUIDE.md) | `drishti.py bi runs …`, `bi mq --async` |
| [CONFIGURATION.md](../admin/CONFIGURATION.md) | `config/runs/runs.yaml`, `drishti.bi.runs.file`, `drishti.bi.exec.background-share`, `standing-share`, `fragments-share` |
| [TROUBLESHOOTING.md](../guides/TROUBLESHOOTING.md) | every new code; "my run says unknown estimate"; "my run was started again"; "the result expired" |
| [PERFORMANCE.md](../admin/PERFORMANCE.md), [PERFORMANCE.md, method 9](../admin/PERFORMANCE.md#9-many-users-soak-runs-and-memory-profiling) | the run engine's memory; the load test's numbers |
| [OPERATIONS.md](../admin/OPERATIONS.md), [OPERATIONS.md](../admin/OPERATIONS.md#18-drishtinet-running-several-servers-as-one) | Admin → BI → Runs; runs in a cluster |
| [BI_GUIDE.md](../guides/BI_GUIDE.md#30-datasets-the-yaml-the-expression-language-and-the-security-rules) | `source: {run: …}` snapshot datasets |
| BI_DESIGN 6.3 | the class caps; correct its code column (busy is `DRS-9025`, not `DRS-9022`) |
| `drishti-console/config/help.yaml` | cards for the Runs tab, the run card, the confirm dialog, the snapshot dataset |
| a tutorial (planned; not written yet, the how-to is [BI_GUIDE.md section 28](../guides/BI_GUIDE.md#28-background-runs-long-bi-questions); number 22 went to the designer tutorial) | a year of P&L at a new grain: estimate, confirm, keep working, the bell, open, share, promote, add the rollup and see it become instant; every step in UI, CLI and YAML |
| CHANGELOG, RELEASE_NOTES | 2.6.7 |

### 21. Open questions

1. **Per-node SQLite clusters.** Status from any node needs scatter and forwarding (13.2). Should 2.6.7 require a shared identity
   database for clusters that use runs, or accept the *unknown* state until the one Drishti database makes sharing the norm?
   *Answered after 2.6.7:* run records moved to the content store, which every member shares (DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9).
2. **Store interactive results by default?** `store.keep-interactive: true` makes slow first draws reusable for everyone with the
   same rights, at the cost of disk. It could stay true, or only for certified reports.
3. **Visibility in shared workspaces.** `owner-and-shared` (default) or `members` (run rows visible to workspace members, results
   never)?
4. **Which external engine first** (3.0 groundwork): Trino (on-premises, the Delta connector) or Athena (AWS)?
5. ~~**Rollup suggestions from runs.**~~ Decided (product owner, 2026-10-20): in 2.6.7, with normalisation and the already-ran prompt (section 8.3a).
