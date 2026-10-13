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
# Drishti BI: the target design

*Status: target design, written 2026-10-12 after wave W1 was built and QA'd, before any more BI code. Design only;
section 13 lists the decisions the product owner still has to take.* This is the **single authoritative design** of
Drishti's business intelligence (Rūpaka). Where it overlaps another document, this one wins:

| Document | What still stands | What this design supersedes |
|---|---|---|
| [BI_MODEL_ARCHITECTURE.md](BI_MODEL_ARCHITECTURE.md) | decisions D1–D6, §2a shared models, §2b mini database rooted in packs, §2c servers compute, §3 experience rules | its wave plan (§4) and risks (§5): replaced by section 12 here |
| [BI_MODEL_SPEC.md](BI_MODEL_SPEC.md) | the W1 formats (dataset, model, MQ v1, implicit models, codes 9100–9199), §12 tiers, §13 worked example | §5.4 "compilation to the existing engine" as the *end state* (it is the W1 path; section 4 is the target); §5.8's W3 promises are restated in 4.6 |
| [BI_PARITY_PLAN.md](BI_PARITY_PLAN.md) | the gap analysis (§2), what we do not copy (§6) | its roadmap (§5): mapped to components and waves in sections 11 and 12 |
| [RUPAKA.md](RUPAKA.md) | positioning, the BI page, as-built history (§20) | §7 query engine, §13.2 "two targets" (browser Python), §17 architecture diagram |
| [LIVE_BI.md](LIVE_BI.md) | the BI stream contract (producers own their arithmetic) | nothing; standing queries (4.6) consume what it produces |
| Guides (DATASETS, REPORTS, BOARDS, BI_GOVERNANCE, PYTHON_BI, ANALYSE, BI_AI, DISTRIBUTION) | how 2.1/W1 behave today | the browser paths they describe (Pyodide, DuckDB-Wasm, console SQLite) end in B |

Contents: [1](#1-principles-and-non-goals) principles · [2](#2-conceptual-model-and-vocabulary) concepts ·
[3](#3-languages) languages · [4](#4-query-architecture) queries · [5](#5-security-architecture) security ·
[6](#6-scalability-and-performance) scale · [7](#7-governance-and-lifecycle) governance ·
[8](#8-authoring-ux-architecture) authoring UX · [9](#9-ai-architecture) AI · [10](#10-apis-and-integration) APIs ·
[11](#11-gap-check-against-power-bi) Power BI gaps · [12](#12-w1-as-built-against-this-design-and-the-revised-wave-plan)
W1 vs design and the waves A, B, C · [13](#13-decisions-for-the-product-owner) decisions

**Wave labels.** The build is three waves, each a release (section 12): **A** = 2.2.0 *Foundation and languages*,
**B** = 2.3.0 *Calculation, live and scale*, **C** = 2.4.0 *Authoring experience, AI and integration*. W1 is the
already-built first wave of 2.2.0. A component marked (A), (B) or (C) is delivered in that wave.

---

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
| P10 | **Designer friendliness is paramount** | the designers write the YAML; every refusal carries a fix; nothing in the UI is a dead end (BI_MODEL_ARCHITECTURE §3) |
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
  limit `drishti.bi.model.max-datasets: 200`).
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
| `report` | `rachana, report, version, model, perspective, measures (local), parameters (local, B), pages[visuals, filters, layout: grid \| board], theme, interactions, bookmarks` | REPORTS.md, R6/R7 |
| `alert`, `subscription`, `notebook` | `model` + an MQ or a visual reference + their own delivery keys | DISTRIBUTION.md, ANALYSE.md |
| `python` | `python, interface, inputs, output, packages, params, limits, source, tests` | PYTHON_BI.md, 3.5 |
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

#### 3.3.4 Function catalogue (summary; the full reference is EXPRESSIONS.md)

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

* **`docs/guides/EXPRESSIONS.md`** (created in A, grown each wave): the grammar of 3.3.2; the scopes; **every
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
  cases, since-wave) feeds validation, completion, `describe` for AI and the function tables of EXPRESSIONS.md (a
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
| **Aggregation (rollup) matching** | replaces `Aggregate(fact, by, measures)` + its scans by a rollup read when the rollup's `by` covers fields, filters and **row-rule columns**, and holds the measures' components | a rollup is per dataset version and generation; row-rule columns must be in `by`, else no match (security first) |
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
| Object-level (hidden) | `security.hidden-for` (dataset or model) | the column/measure does not exist for the reader: absent from `describe`, `DRS-9152` "not found" when named (indistinguishable from a typo, on purpose) | no |
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
| Tests | data tests (unique, not-null, references-exist), measure tests, golden questions, DSQL tests, Python manifest tests; all gate publish; run in CI with `drishti.py bi test` | built/A |
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
| Never lose work | server-side draft autosave (every change, debounced 2 s) + local journal; stale-save detection by item etag (`409` with a merge view); leaving with unsaved changes asks |
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
BI_MODEL_ARCHITECTURE §3; its findings are fixed before the drill.

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
| CLI | `drishti.py bi check \| test \| query --mq \| sql \| migrate \| bundle` | user token | built/extended |

**Versioning.** REST under `/api/v1` (additive changes only; a breaking change is `/v2`). MQ by `mq:` (1 → 2 is
additive: a v1 query is a valid v2 query); DSQL by `sql:` (1); item formats by `rachana:` (2). A retired form answers
`410` with the equivalent (as `POST /datasets/{id}/query` will after A).

---

## 11. Gap check against Power BI

From BI_PARITY_PLAN §2 and §4 (top gaps), mapped to the component of this design that delivers each, and the wave.

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
   `drishti.py bi migrate --models` ([BI_MODEL_SPEC.md 8.3.1](BI_MODEL_SPEC.md#831-as-built-22-wave-a-task-6)), the samples are converted and proven
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
6. **Docs** (with the server lane's input): `docs/guides/EXPRESSIONS.md` (3.3.8: grammar, every function, error codes,
   40+ recipes, Python/DAX differences), `SQL.md` (DSQL reference), DATASETS.md v2, MODELS.md, API guide; tutorials
   10–18 migrated (UI, CLI, YAML).

**Accepted when:** (1) every shipped sample, test and tutorial 10–18 runs on datasets and models in the UI alone; the
v1 format and old expression syntax are refused; the equivalence harness is green apart from its allow-list; (2) the
same question as a visual, an MQ and a DSQL statement gives one plan hash, one answer and one cache entry per security
signature; (3) a dataset with subset, filter, explicit grain, an expression column, a Python column and grouping, and an
SQL view dataset, are made in the wizard without typing YAML; (4) a hidden column is absent from `describe`, DSQL and
AI; a report-only reader is refused outside the envelope; (5) the plan's security property tests pass; (6) a test
fails if EXPRESSIONS.md and `functions.yaml` disagree, and all 40+ recipes run as tests on the demo lake; (7) the QA
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
6. **Docs:** EXPRESSIONS.md filter-context and balance chapters and recipes; a live and standing-queries guide; an
   operations guide for scale settings and metrics; tutorials updated.

**Accepted when:** (1) % of total, ignore-a-slicer, a YTD calculation group over ten measures and a what-if shock equal
an independent computation; closing balance per month is right across a holiday; (2) a board, a report and an alert
over one model tick live, answer as of a past date, and equal a full recompute after random update streams (property
test); (3) no BI page computes: no Wasm or Pyodide is loaded (test), CSP without `'wasm-unsafe-eval'`; (4) two Java
nodes and two consoles serve one model, and standing queries survive the loss of a node; (5) the 500-viewer load test
meets 6.8 on a real server; (6) explain shows tiers, layers and times; (7) QA findings fixed before the drill.

#### Wave C — Authoring experience, AI and integration → 2.4.0

**Server lane:**

1. **Report schema v2 (R6) and boards as layouts (R7):** typed `format` per visual family (JSON Schema), `theme` items,
   `interactions` matrix, sync groups, tooltip pages, small multiples, matrix depth, conditional formatting rules;
   `layout: board` replaces the `board` kind (converter).
2. **AI grounding:** `ai:` metadata in `describe`; golden questions run per provider; AI emits MQ/DSQL only; measures
   from plain words and reports from intent as YAML drafts through review; MCP tools of section 9.
3. **Integration:** PostgreSQL-wire read-only endpoint (decision 5); Excel feed; embed tokens bound to envelopes;
   SDK events.
4. **Governance:** environment parameter rules in bundles; two-way git sync (decision 8); usage dataset over the audit
   log; sensitivity labels inherited by exports; comments on visuals with captured state.

**Console lane:**

1. **BI Studio as one product:** the shell of 8.1; format pane generated from schemas; themes; conditional formatting;
   interactions editor; tooltip pages; small multiples; the matrix.
2. **AI UI:** ask, explain a change, design from intent, a measure from words → draft → review.
3. **Feeds and embedding UI:** per-user connection details for SQL tools, the Excel feed wizard, embed configuration.
4. **Governance UI:** deployment between environments, usage report, labels, comments.
5. **Full docs and tutorials pass:** every BI guide rewritten to this design; tutorials with UI, CLI and YAML.

**Accepted when:** (1) a Power BI author does the parity task list (BI_PARITY_PLAN §4 items 6–9, 13) without YAML;
(2) golden questions pass on the mock and one real provider, and an AI answer uses only measures the user may see;
(3) psql and Excel answers equal the MQ answers per user, masked; (4) a bundle with parameter rules moves a model and
its reports from dev to test; (5) the docs pass is complete; (6) QA findings fixed before the drill.

**After C:** Python data-prep experience (step preview, snippets, profiling: BI_PARITY_PLAN P1–P10), paginated
reports, a second engine and an Excel add-in (3.0).

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
