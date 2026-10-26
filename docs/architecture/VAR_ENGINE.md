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
# The VaR engine: value at risk for any set of trades, with full drill-down

The data contract: [docs/contracts/VAR.md](../contracts/VAR.md) (every input column, structure and answer, checked against the code).

Status: **partly built (2.8.0, unreleased).** Waves **V0 to V3 are built** (the data, the engine, the VaR explorer, history and
backtesting, baskets, captures, entitlements and audit): what was built, how it differs from this design and the decisions taken on
its open questions are in [section 18](#18-as-built-in-280-waves-v0-to-v3); the user guide is [VAR_EXPLORER.md](../guides/VAR_EXPLORER.md).
Waves V4 to V6 (what-if pricing, intraday recomputes, the sharded store across Drishti\*net) are **planned**: what-if pricing through
QLRisk only if its API is declared stable in time (otherwise 2.8.x). Elsewhere in this design, a part section 18 does not list as
built (reactive connector, compute workers, Python package) is **planned (2.8.0)**. Related: [REACTIVE_CONNECTORS.md](REACTIVE_CONNECTORS.md) (the connector type
the engine is), [PYTHON_COMPUTE.md](PYTHON_COMPUTE.md) (where its code runs and how its libraries are approved),
[WRITE_BACK.md](WRITE_BACK.md) (a booked or amended trade recomputes the VaR it affects), [DEMO_DATA.md](../connectors/DEMO_DATA.md)
(the data pattern every pack follows). **The data model is in a companion document, [VAR_DATA_MODEL.md](VAR_DATA_MODEL.md):** positions
derived from trades, portfolios (hierarchies, rules, baskets) and membership in time, the P&L vector's layout in Delta (options
compared), partitioning, the rolling window, versions and restatements, precision, cubes, the in-memory vector store, sharding and
sizing for a large bank.

## Contents

1. [The ask](#1-the-ask)
2. [Principles](#2-principles)
3. [Words used in this document](#3-words-used-in-this-document)
4. [Where the engine lives](#4-where-the-engine-lives)
5. [Data model and storage](#5-data-model-and-storage)
6. [Scope algebra: any set of trades](#6-scope-algebra-any-set-of-trades)
7. [Measures and methods](#7-measures-and-methods)
8. [What-if: hypothetical, amended and unwound trades](#8-what-if-hypothetical-amended-and-unwound-trades)
9. [Intraday events: write-backs and feeds](#9-intraday-events-write-backs-and-feeds)
10. [Drill-down: what the user sees](#10-drill-down-what-the-user-sees)
11. [Backtesting](#11-backtesting)
12. [Entitlements and audit](#12-entitlements-and-audit)
13. [Performance and sizing for a large bank](#13-performance-and-sizing-for-a-large-bank)
14. [APIs and CLI](#14-apis-and-cli)
15. [Phased delivery](#15-phased-delivery)
16. [Tests](#16-tests)
17. [Open questions for the product owner](#17-open-questions-for-the-product-owner)
18. [As built in 2.8.0: waves V0 to V3](#18-as-built-in-280-waves-v0-to-v3)

---

## 1. The ask

The product owner (2026-10-24) wants an **enterprise-grade, fully generic VaR engine** in Drishti:

- **Any scope:** one position, a book, a desk, a portfolio, a legal entity, the whole bank, or an **artificial portfolio** (any bunch of
  trades put together by a person), and any combination of these.
- **Precomputed path P&L:** each position's P&L under each of about **509 historical scenario days** is computed once, by the bank's
  overnight risk batch (or Drishti's), and stored. Base VaR **never reprices**: it adds vectors.
- **What-if is where computation comes in:** a hypothetical trade, an amended trade or an unwound position is priced just in time
  over the same 509 scenario days, and the result joins the stored vectors. QLRisk is the default pricer; pricing is pluggable.
- **Full drill-down at any level:** the P&L path (all 509 scenario P&Ls) as a table **and** charts (distribution, sorted tail, time
  series by scenario date); VaR and ES history over time as a table **and** a chart; drill from bank to desk to book to position, and
  into any ad-hoc scope.
- **Enterprise grade:** performance, audit, entitlements and scale are first class.

## 2. Principles

1. **Vectors, not prices.** A scope's P&L vector is the sum of its positions' vectors. Every base measure (VaR, ES, contributions,
   history) is computed from vectors. Only what-if needs a pricer.
2. **One engine for every scope.** The bank, a desk and a basket of 37 trades go through the same code; a hierarchy node is only a
   scope that happens to be precomputed.
3. **Additive where it can be, honest where it cannot.** P&L vectors and ES contributions add up; VaR does not. The screens never sum
   VaRs; they show diversification explicitly.
4. **Every number reproducible.** Each result carries its stamp (input versions, scenario set, method, engine and environment,
   [REACTIVE_CONNECTORS.md section 8](REACTIVE_CONNECTORS.md#8-point-in-time-stamping-ordering-and-idempotence)), so the same number
   can be recomputed years later.
5. **Entitled as the viewer.** A person sees VaR only over positions they may see, or in aggregate where they are entitled only to
   aggregates ([section 12](#12-entitlements-and-audit)).
6. **Packs stay declarative.** The pack declares kinds, routes and screens; the computation is a reactive connector.

## 3. Words used in this document

| Word | Meaning |
|---|---|
| **position** | the unit a P&L vector is stored for: one OTC trade, or one book's net holding of a fungible instrument (the new `position` kind, [VAR_DATA_MODEL.md section 3](VAR_DATA_MODEL.md#3-positions-the-one-thing-to-add)) |
| **scenario day** | one historical day whose market move is applied to today's market; 509 of them make the **scenario set** |
| **P&L vector** | for one position and one business date, the 509 P&Ls under the scenario days, in the reporting currency |
| **scope** | any set of positions ([section 6](#6-scope-algebra-any-set-of-trades)) |
| **node** | a scope of the standard hierarchy (bank, legal entity, division, desk, book), whose vector is precomputed |
| **basket** | a saved ad-hoc scope: an artificial portfolio a person defined |
| **sandbox** | a private what-if: a base scope plus hypothetical changes, owned by one person |
| **VaR(α)** | the loss not exceeded with confidence α over the horizon, read from the scope's vector; shown as a positive number |
| **ES(α)** | the average loss in the worst (1 − α) share of scenarios |

## 4. Where the engine lives

| Part | What | Where (all planned, 2.8.0) |
|---|---|---|
| **The pack** | kinds (`position`, `risk-hierarchy`, `pnl-vector`, `scenario-set`, `var-node`, `pnl-hypothetical`, `var-result`, `var-contribution`, `var-path`, `var-history`, `what-if`), routes, Sutras (the VaR explorer, the what-if panel) | the existing **market-risk** pack (`config/packs/market-risk`), which then had the stored VaR kind (retired in 2.8.0), the `risk-store` Delta connector and the risk domain (open question 1: a separate `var` pack instead) |
| **Stored data** | positions, hierarchies, position vectors, scenario sets, precomputed node vectors, hypothetical P&L | the static connector **`risk-store`** (Delta), from `data/testdata/market-risk/<kind>/` in the demo and the bank's batch in production ([section 5.4](#54-storage-layout-and-the-data-pattern)) |
| **The computation** | scope algebra, measures, contributions, history, backtests, what-if | the reactive connector **`var-engine`** ([REACTIVE_CONNECTORS.md section 5](REACTIVE_CONNECTORS.md#5-the-connector-file)) |
| **The maths** | quantiles, ES, Euler allocation, backtests | a versioned Python package, `compute/drishti-var`, with its own tests; installed into the var-engine's locked environment ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md)) |
| **Summing large scopes** | adding millions of vectors | the **vector store** of `drishti-compute`: a generic, additive push-down next to the data ([section 13.3](#133-where-the-arithmetic-happens)) |
| **Pricing (what-if only)** | 509 scenario prices per changed trade | the pricer SPI: QLRisk by default, QuantLib-Java as the Java alternative ([PYTHON_COMPUTE.md section 9](PYTHON_COMPUTE.md#9-pricing-one-spi-two-engines)) |

```
                 ┌─────────── market-risk pack: kinds, routes, Sutras (no code) ────────────┐
                 │                                                                          │
   bank batch ──▶ data/testdata or the bank's lake ──▶ risk-store (Delta, static) ──┐        │
   (vectors)                                                                        │ reads  │
                                                                                    ▼ as user│
   panel / BI / API ──────────────────────────────────────────────────▶ var-engine (reactive)│
                                                                         │  vector store: sums│
                                                                         │  drishti-var: VaR, ES, Euler, backtests
                                                                         │  pricer SPI (what-if): QLRisk | QuantLib-Java
   write-back applied / new load / schedule ── triggers ────────────────┘  results stamped, cached, pushed
```

## 5. Data model and storage

This section lists the stored kinds; [VAR_DATA_MODEL.md](VAR_DATA_MODEL.md) designs them in depth (positions and their derivation
from trades, portfolios and membership, the layout in Delta and why, the rolling window, restatements, precision, cubes, the
in-memory store and sizing). Where the two differ, VAR_DATA_MODEL.md is the reference.

### 5.1 The scenario set (`scenario-set`)

One record per scenario set and business date.

| Field | Type | Meaning |
|---|---|---|
| `SCENARIO_SET_ID` | id | `HS-509` (historical, 509 days); `SVAR-250` for a stressed window |
| `BUSINESS_DATE` | date | the date whose positions the vectors belong to |
| `SCENARIO_DATES` | date[509] | the scenario days, oldest first; position *i* of every vector is day *i* |
| `WINDOW_START`, `WINDOW_END` | date | the window |
| `HORIZON_DAYS` | int | 1 (10-day vectors, where the batch makes them, are a separate set) |
| `WEIGHTS` | decimal[509] | optional: age weights for weighted methods |
| `REVALUATION` | text | `full` (full revaluation) or `sensitivities` (Taylor), as the batch did it |
| `SOURCE_BATCH` | text | the batch run that produced the set and its vectors |

### 5.2 The position vector (`pnl-vector`)

One record per position, business date and scenario set.

| Field | Type | Meaning |
|---|---|---|
| `VECTOR_ID` | id | `PV-<POSITION_ID>-<SCENARIO_SET_ID>` (dated by the business date, as every dated kind) |
| `POSITION_ID` | id | `POS-MX-50000001` (an OTC trade), `POS-BOOK-RATES-2~ISIN-US91282CJL6` (a fungible holding) |
| `TRADE_VERSIONS`, `SOURCE_SYSTEM` | text | the position's member trades' versions the batch priced (the position links to its trades) |
| `BUSINESS_DATE`, `SCENARIO_SET_ID` | date, id | |
| `LEGAL_ENTITY`, `DIVISION`, `DESK`, `TT_BOOK`, `PORTFOLIO`, `STRATEGY` | text | the hierarchy, as the trade has it on the date (UPPER_SNAKE, as the trades) |
| `PRODUCT_GROUP`, `PRODUCT_TYPE`, `CURRENCY`, `COUNTERPARTY` | text | attributes ad-hoc scopes filter on |
| `REPORTING_CCY` | text | the currency of the vector (one per bank, `USD` in the samples) |
| `PV` | decimal | the base value |
| `PNL` | decimal[509] | the vector, float64, in `SCENARIO_DATES` order |
| `PNL_BY_RISK_CLASS` | map | optional: `{IR: [509], FX: [509], CR: [509], EQ: [509], CO: [509]}` when the batch splits by risk class (adds up to `PNL` within tolerance) |
| `STATUS` | text | `ok`, `proxied` (priced by a proxy), `failed` (no vector: counted and shown, never silently zero) |
| `PRICER`, `MODEL_VERSION`, `COMPUTED_AT`, `BATCH_ID`, `RESTATEMENT` | text | provenance of the batch; reruns and restatements ([VAR_DATA_MODEL.md section 9](VAR_DATA_MODEL.md#9-versions-time-travel-restatements-and-reruns)) |

### 5.3 Precomputed and supporting kinds

| Kind | One record per | Holds | Why |
|---|---|---|---|
| `var-node` | node of the standard hierarchy, business date, scenario set | the node's summed `PNL[509]`, `VAR_99`, `ES_975`, position count, failed count, the result hash | the bank, a desk or a book answers instantly, and VaR history is a read, not a recompute |
| `pnl-hypothetical` | position and business date | the hypothetical P&L (static positions, next day's market), and the actual P&L where the bank provides it | backtesting ([section 11](#11-backtesting)) |
| `var-basket` | saved basket | the scope expression, owner, sharing, description | artificial portfolios kept and shared ([section 6.3](#63-baskets-saved-artificial-portfolios)) |

The computed kinds (`var-result`, `var-contribution`, `var-path`, `var-history`, `what-if`) are answers of `var-engine`, not stored
data ([REACTIVE_CONNECTORS.md section 5](REACTIVE_CONNECTORS.md#5-the-connector-file)). `var-node` is written by the overnight
precomputation ([section 13.4](#134-the-overnight-precomputation)) and is otherwise ordinary lake data.

The existing `var` kind of the market-risk pack (desk-level records with a `SCENARIO_PNL` array) stays as it is; open question 2 asks
whether it is retired once the engine runs.

### 5.4 Storage layout and the data pattern

The engine follows the data pattern every pack follows ([DEMO_DATA.md](../connectors/DEMO_DATA.md),
[the test data README](../../data/testdata/README.md)):

| Stage | Demo and tests | Production |
|---|---|---|
| source | `data/testdata/market-risk/pnl-vector/`, `…/scenario-set/`, `…/var-node/`, `…/pnl-hypothetical/`: gzipped JSON Lines, one file per business date (`snapshot-<date>.jsonl.gz`, then `changes-<date>.jsonl.gz`), written deterministically by the test-data generator | the bank's risk batch writes Parquet or JSON Lines to its landing area |
| lake | `tools/build_demo_lakes.py` builds the Delta tables under the data folder | the loader (`lake build`, `lake publish`) builds the same tables |
| connector | `risk-store` (Delta) serves the stored kinds; `DRISHTI_LAKE_ENABLED` as for every pack | the same connector, pointed at the bank's lake |

**Table layout of `pnl-vector`** (Delta, domain `risk`):

- **Partitioned** by `BUSINESS_DATE` and `SCENARIO_SET_ID`.
- **Sorted within each date** by `LEGAL_ENTITY`, `DESK`, `TT_BOOK`, `POSITION_ID`, so a desk's or a book's vectors are contiguous
  and a node read touches few files and row groups.
- **The vector as one column** `PNL: list<double>` (Parquet LIST). Rejected: 509 columns (a schema no one can read, and every
  scenario-set change a schema change); one row per scenario (509 times the rows and the index). The full comparison, with sizes and
  scan costs, is [VAR_DATA_MODEL.md section 6](VAR_DATA_MODEL.md#6-the-pl-vector-in-delta-layout-options-compared); what the lake
  tools and the Delta plugin must learn for vector columns is [section 15](VAR_DATA_MODEL.md#15-fit-with-the-repositorys-data-pattern-and-what-must-change)
  of that document.
- **Promoted columns** (the connector's `layout:`) for the hierarchy and filter attributes, so scope filters run on columns without
  decoding vectors.
- **Row groups** of about 64 MB; files of about 512 MB; no deletion vectors; `OPTIMIZE` after each load (the AWS layout guidance in
  [AWS_DELTA_LAKE.md](AWS_DELTA_LAKE.md) applies unchanged).
- **float64 in storage, float64 in sums.** Summing millions of float32 values loses whole currency units at bank scale; open question
  4 asks whether a float32 hot cache is acceptable.

**Committed demo data size.** Vectors change every day, so the demo set is kept small: about 200 positions over the sixty business
days, P&Ls rounded to whole currency units, about 0.35 MB per day compressed (about 21 MB in all), and the node vectors for the
sample hierarchy. Sizing tests generate larger sets locally (at most 10,000 positions on a workstation) and are never committed.

## 6. Scope algebra: any set of trades

### 6.1 The grammar

A scope is written as text (so it can be an id, a link, a cache key and a URL) and parsed into a tree:

| Form | Example | Means |
|---|---|---|
| node | `BANK`, `LE:LE-NY-01`, `DESK:RATES-NY`, `BOOK:BOOK-RATES-2` | a node of the standard hierarchy |
| positions | `POS:MX-50000001,CLY-30000001` | the listed positions (trades) |
| filter | `WHERE:PRODUCT_GROUP == 'IRS' and CURRENCY == 'USD'` | positions whose promoted columns match a DEL condition (the expression language of BI, [EXPRESSIONS.md](../guides/EXPRESSIONS.md)) |
| basket | `BASKET:asha/usd-hedges` | a saved scope ([6.3](#63-baskets-saved-artificial-portfolios)) |
| union | `DESK:RATES-NY + DESK:RATES-LDN` | positions in either |
| difference | `DESK:RATES-NY - POS:MX-50000001` | without |
| intersection | `DIV:MARKETS & WHERE:COUNTERPARTY == 'CP-0042'` | in both |
| what-if | `DESK:RATES-NY @ SANDBOX:01JB…` | the scope with a sandbox's changes applied ([section 8](#8-what-if-hypothetical-amended-and-unwound-trades)) |

Operators bind as in arithmetic (`&` before `+` and `-`), parentheses group. A scope is **canonicalised** before it is used (nodes
inside other nodes of a union removed, terms sorted, filters normalised), so equal scopes share a cache key.

### 6.2 Evaluating a scope fast

1. **Rewrite** the tree into: a set of whole **nodes**, plus **added positions**, minus **removed positions**, where possible (a union
   of nodes, a node minus a few trades, a node plus a basket of trades). Most real scopes take this form.
2. **Sum:** vector = Σ precomputed node vectors + Σ added position vectors − Σ removed position vectors. The cost depends on the number
   of terms, not on the size of the nodes: *Markets minus three trades* costs four vector operations.
3. **Otherwise** (a filter or an intersection that cuts across nodes): the **position index** of the date (the promoted columns of
   every position, in memory, a few hundred bytes per position) selects the positions as a bitmap of ordinals, and the vector store
   sums their vectors ([section 13.3](#133-where-the-arithmetic-happens); the planner's rule for aggregates against leaves is
   [VAR_DATA_MODEL.md section 11.3](VAR_DATA_MODEL.md#113-aggregates-or-leaves-the-planners-rule)).
4. **Entitlements** are applied as a final intersection with what the viewer may see ([section 12](#12-entitlements-and-audit)).

The positions in a scope are counted and the answer says how many were included, how many had no vector (`failed`) and how many are
outside the viewer's entitlement.

### 6.3 Baskets: saved artificial portfolios

- A **basket** is a scope with a name, an owner and a description, saved from any screen (*Save as basket*), from a selection of rows
  in any trade table (*Add to basket*), from a BI result, or written by hand.
- **Static or dynamic:** a basket of listed positions is static; a basket of nodes and filters is dynamic (it means whatever matches on
  the date viewed). The screen says which.
- **Sharing:** private by default; shared read-only with named people or roles; each reader sees it through their own entitlements.
- **Kept** in the content store ([CONTENT_STORE.md](CONTENT_STORE.md)), so it follows its owner across nodes; every change audited.

## 7. Measures and methods

All measures are computed from the scope's vector `P` (509 values, gains positive) and, for contributions, the vectors of its parts.

| Measure | Definition (historical, equal weights) | Notes |
|---|---|---|
| **VaR(α)** | −(the k-th worst value of `P`) | the rank rule is a setting: `kth-worst` with k = ⌈N(1−α)⌉ (default proposal; for N = 509 and α = 99%, k = 6) or `interpolated`; open question 3 |
| **ES(α)** | −(mean of the ⌈N(1−α)⌉ worst values) | α = 97.5% by default: the mean of the 13 worst of 509 |
| **Standalone VaR** of a part | VaR of the part's own vector | |
| **Component VaR** of a part | the part's P&L in the scenario(s) that set the scope's VaR, **smoothed** over the scenarios around rank k (a kernel of width `component.window`, default 5) | components add up to the (smoothed) VaR; unsmoothed single-scenario components are available as a choice |
| **Component ES** | the part's mean P&L over the scope's tail scenarios | adds up exactly to ES |
| **Marginal VaR** | component VaR ÷ the part's size (notional or PV, per product) | the change in VaR per unit added |
| **Incremental VaR** | VaR(scope) − VaR(scope without the part), or VaR(scope with a new trade) − VaR(scope) | the exact answer to *what does this trade do to my VaR?* |
| **Diversification** | Σ standalone VaR of the children − VaR of the scope | always shown beside a children table, so nobody adds VaRs |
| **Stressed VaR** | VaR over the `SVAR-250` set's vectors | only if the batch provides stressed vectors |

**Methods** (`method` parameter):

| Method | What changes | Needs |
|---|---|---|
| `historical` (default) | equal weights | `PNL` |
| `age-weighted` | each scenario weighted by λ^(age); the quantile is the weighted one | `PNL`, λ (default 0.99) or the set's `WEIGHTS` |
| `filtered` | each scenario's P&L rescaled by today's volatility ÷ the volatility on that day (EWMA) | `PNL`, a volatility series per scope (computed from the vectors' history) |

**Horizon:** 1 day from the vectors; 10 days from a 10-day vector set if the batch provides one, otherwise √10 scaling, labelled as
such on every screen that shows it.

## 8. What-if: hypothetical, amended and unwound trades

A **sandbox** is a person's private what-if: a base scope and a list of changes. Only its owner sees or changes it
([REACTIVE_CONNECTORS.md section 9](REACTIVE_CONNECTORS.md#9-isolation-two-users-never-see-each-others-answers)).

| Change | How its vector is obtained | Pricing needed |
|---|---|---|
| **Unwind** a position (all of it) | subtract its stored vector | **no** |
| **Partly unwind** a linear position (a cash bond, a future, an FX spot or forward, a vanilla swap scaled by notional) | scale its stored vector | **no**, if the product is marked linear in the pack's pricer mapping; otherwise priced |
| **Amend** a trade (notional, strike, maturity, rate …) | price the amended trade over the 509 scenarios; subtract the stored vector, add the new one | **yes** |
| **Add** a hypothetical trade (from a form, a copy of an existing trade, or a template) | price it over the 509 scenarios; add | **yes** |
| **Move** a position to another book | no vector change; the scopes it belongs to change | **no** |

**Pricing over the scenario days.** The pricer receives the trade, the base market of the business date (assembled by the server as
QUANT_SERVICE.md section 7 describes) and the scenario set's market moves, and returns the 509 P&Ls. QLRisk's
`MarketRiskEngine.scenario_pnl` is the default path ([PYTHON_COMPUTE.md section 9.2](PYTHON_COMPUTE.md#92-qlrisk-default-python)).
The pricer's scenario moves must be the **same moves the batch used**; a **reprice check** proves it: for a sample of existing trades
in the scope, the engine reprices them and compares with their stored vectors, and shows the agreement on the what-if panel (*parity
with the batch: 0.02%*). A scope whose check fails beyond tolerance shows its what-if numbers in amber with the reason.

**Results.** For the sandbox the engine returns: before and after VaR, ES and limit use; each change's incremental VaR (in the order
listed, and standalone); the new trade's component VaR in the scope; overlaid distributions; and a waterfall from before to after.

**From what-if to booking.** *Book via write-back…* turns a sandbox's change into a change request ([WRITE_BACK.md](WRITE_BACK.md));
it goes through the field's approval and the source's `writes:` command like any other edit. Once it is applied, the event trigger
recomputes the real scopes ([section 9](#9-intraday-events-write-backs-and-feeds)); the sandbox is left as it was.

**Without QLRisk.** If QLRisk is not declared stable in 2.8.0, what-if in 2.8.0 offers the changes that need no pricing (unwind,
partial unwind of linear positions, move) and the stand-in pricer in tests; amend and add follow in 2.8.x.

## 9. Intraday events: write-backs and feeds

Overnight vectors describe the morning's positions. During the day trades are booked, amended and cancelled. The engine keeps an
**intraday layer** on top of the stored vectors.

| Event | Trigger ([REACTIVE_CONNECTORS.md section 7](REACTIVE_CONNECTORS.md#7-triggers-computing-on-events)) | Effect on the intraday layer |
|---|---|---|
| a trade amended or booked through Drishti's write-back | `write-back` (change applied) | the trade is priced over the 509 scenarios (as for what-if); its intraday vector replaces the stored one |
| a trade booked or amended in the source system, seen on Kafka | `live` | the same |
| a trade cancelled | `write-back` or `live` | its vector is removed |
| the overnight batch publishes new vectors | `data-version` | the intraday layer of that date is dropped: the batch's vectors include the day's trades |

- **Incremental:** an event changes the vector of the trade's book and every node above it by (new − old), and every saved basket that
  contains the trade; nothing else is recomputed ([REACTIVE_CONNECTORS.md section 10.2](REACTIVE_CONNECTORS.md#102-incremental-recompute)).
- **Stamped:** an intraday result says which events it includes (`L17:3412`, `trade-kafka p3:182277`) and is labelled *intraday*;
  the official end-of-day numbers come only from the batch.
- **Per-event impact:** the submitter of a write-back receives their own change's incremental VaR, computed against the state just
  before their event, under their change id; everyone watching the scope receives the new shared number
  ([REACTIVE_CONNECTORS.md section 9.2](REACTIVE_CONNECTORS.md#92-two-traders-two-amendments-the-same-second)).
- **Without pricing:** until a pricer is available (QLRisk not yet stable), an amended or booked trade cannot get an intraday vector;
  its scopes are marked *intraday change not reflected: pricing unavailable*, and cancellations (which need no pricing) are applied.

## 10. Drill-down: what the user sees

Three static mock-ups were made for this design (shared with the product owner, kept outside the repository): the VaR explorer, the
what-if panel, and the write-back review with its recompute status.

### 10.1 The VaR explorer

Opened on any scope (a command proposed as `VARX <scope>`, from a desk or book page, from a basket, from a selection of trades):

| Area | Shows | As |
|---|---|---|
| **Hierarchy** (left) | bank → legal entity → division → desk → book → position, plus *Ad-hoc scopes* (the person's baskets and sandboxes, and *+ New scope*); each row with VaR, ES and limit use | a tree; a click moves the whole screen to that scope |
| **Breadcrumb and strip** | the path (*Bank › Markets › Rates › RATES-NY*), the business date, the pin, *intraday: 3 changes included* | text |
| **Headline** | VaR 99% 1d, ES 97.5%, sum of standalone, diversification, limit use, backtest exceptions over 250 days with the traffic light | tiles |
| **P&L path** (tab) | all 509 scenario P&Ls | **table** (scenario date, P&L, rank, top contributor at that date) **and charts**: the distribution with VaR and ES marked; the sorted tail (worst 25); the time series by scenario date |
| **VaR / ES history** (tab) | the scope's VaR and ES for each of the last 250 business days, with the hypothetical P&L and the exceptions | **table and chart** |
| **Contributions** (tab) | the scope's children (or positions, or risk classes): standalone, component, % of total, marginal, incremental | a sortable table with bars; a click drills into the child |
| **Scenario drill** | a click on a scenario (a row of the path table, a bar of a chart) opens the contributors at that scenario date: which children and positions lost most that day | a ranked table beside the chart |
| **How this number was built** | positions included, failed and hidden; the scenario set and its window; the method and rank rule; the engine, environment and pin | text, from the stamp |

**At position level** the same screen shows the position's own vector, its history, and a link to the trade (`TRD`).
**For an ad-hoc scope** it is identical; the hierarchy pane highlights the nodes the scope draws from.
**On a phone** the hierarchy becomes a drop-down, tabs stack, and tables scroll inside their panel.

### 10.2 As a Sutra (planned)

The explorer is an ordinary Sutra of the market-risk pack built from UI kit fragments, reading the reactive kinds through `source:`
with `params:` ([REACTIVE_CONNECTORS.md section 12](REACTIVE_CONNECTORS.md#12-one-screen-many-connectors)). New fragments it needs
(a distribution histogram with marked lines, a sorted-tail chart, a hierarchy tree with measures) go into the UI kit, so any pack can
use them.

```yaml
panels:
  - use: kpi-tiles@1
    with: { id: headline, rows: $src }
    source: { kind: var-result, id: ctl.scope, params: { confidence: ctl.confidence, method: ctl.method } }
  - use: distribution@1                                     # new UI kit fragment (planned)
    with: { id: dist, rows: $src.PATH, value: PNL, marks: { VaR: $src.VAR, ES: $src.ES } }
    source: { kind: var-path, id: ctl.scope }
    emits: [ { name: pick, on: bar-click, payload: { date: "@.SCENARIO_DATE" } } ]
  - use: ranked-bars@1
    with: { id: at-date, title: "Contributors on ${@dist.pick.date}", rows: $src, label: NAME, value: PNL }
    source: { kind: var-contribution, id: ctl.scope, params: { by: child, scenario: "@dist.pick.date" } }
```

### 10.3 Response times the user should feel

Drill-down must feel instant on precomputed nodes and fast on anything else; the targets are in [section 13.2](#132-targets).

## 11. Backtesting

- **Data:** the hypothetical P&L of each position for each day (`pnl-hypothetical`), from the bank's P&L system or the batch; the actual
  P&L where provided.
- **Per scope and day:** the scope's hypothetical P&L (a sum, like vectors) compared with the VaR of the previous day; a loss larger
  than VaR is an **exception**.
- **Tests** (from `drishti-var`, cross-checked with QLRisk's backtesting when it is stable): exception count over 250 days and the
  Basel traffic light (green 0–4, amber 5–9, red 10+), Kupiec's proportion-of-failures test, Christoffersen's independence test, and
  for ES the Acerbi–Székely tests.
- **Any scope:** precomputed nodes have their backtest in the overnight precomputation; an ad-hoc scope's backtest is computed on
  demand (it needs the scope's VaR for each of 250 days, [section 13.2](#132-targets)).
- **On screen:** the history tab marks exceptions on the chart and in the table; the headline shows the count and the light.

## 12. Entitlements and audit

### 12.1 Entitlements

Each role is given, per node of the hierarchy, one of three levels (planned, in the pack's entitlement policy):

| Level | May see | Example |
|---|---|---|
| `positions` | the node's numbers, its children, its positions and their vectors | a desk's risk managers on their desk |
| `aggregate` | the node's numbers and its children's, **not** its positions | a division head on the desks under them; group risk on the whole bank |
| none | nothing of the node | a trader on another desk |

- A scope is computed over the positions the viewer may see at `positions` or `aggregate` level; positions outside are excluded and
  **counted** (*312 positions are outside your entitlement and not included*). A number is therefore never wrong for its viewer: it
  says what it covers.
- Drill-down stops at the deepest level the viewer may see; positions under an `aggregate` node are never listed, never in a
  contribution table, never in a scenario drill.
- A **filter or position scope** may only name positions the viewer sees at `positions` level (otherwise a filter could probe
  individual trades through aggregates).
- Field masks apply as everywhere (a masked counterparty is `•••` in tables and cannot be filtered on).
- The cache key includes the viewer's view signature, so two viewers with different rights never share a result
  ([REACTIVE_CONNECTORS.md section 9](REACTIVE_CONNECTORS.md#9-isolation-two-users-never-see-each-others-answers)).

### 12.2 Audit

- **Every answer delivered** is an audit entry: who, scope (canonical), measures, method, pin, view signature, result hash, duration.
- **Every sandbox change and computation**, and every basket created, changed or shared.
- **Official numbers:** the overnight precomputation writes the regulatory hierarchy's VaR and ES as `var-node` records with result
  hashes; they are the numbers of record and can be recomputed from their pins (same vectors, same engine version, same environment)
  at any later date.
- **Retention:** audit entries follow the site's audit retention; official numbers are kept as long as the lake keeps the date
  (open question 6).

## 13. Performance and sizing for a large bank

### 13.1 The reference bank

| Quantity | Value used for sizing |
|---|---|
| positions (trades) per day | 2,000,000 |
| scenarios | 509 |
| hierarchy nodes | 5,000 (bank, 20 legal entities, divisions, 400 desks, 4,500 books) |
| concurrent interactive users | 300 |
| business days of vectors kept hot | 60 (the lake keeps more) |

**Storage per day:** 2,000,000 × 509 × 8 bytes = **8.1 GB** of float64 vectors (about 6 GB in Parquet with byte-stream split and
ZSTD: P&L values compress poorly); node vectors 5,000 × 509 × 8 bytes = 20 MB. Per year (252 days) about 1.5 TB of position vectors in
the lake. Sizes for 1 to 5 million positions, the whole-bank scan time and the 50,000-position ad-hoc budget are worked out in
[VAR_DATA_MODEL.md section 14](VAR_DATA_MODEL.md#14-sizing-for-a-large-bank).

### 13.2 Targets

All to be **measured** on a real server in step V6; a workstation run is limited to 10,000 positions on disk. The **measured** column
is from 2.8.0's V1 to V3 build, on a workstation (24 hardware threads, 61 GB, JDK 25): the scale benchmark
`drishti-compute/src/test/java/com/ash/drishti/compute/var/VarScaleBenchmark.java` over **500,000 positions and 1,000,000 trades
held in memory** (4.07 GB of vectors, never written to disk; `-Ddrishti.var.scale.positions=500000
-Ddrishti.var.scale.trades-per-position=2`), and the explorer's Playwright test on the demo data (`test_var_explorer_browser.py`). The
extrapolation to 2 to 5 million positions assumes summing stays memory-bound (it scales with the rows read) and four servers share the
rows, as section 13.3 and VAR_DATA_MODEL.md section 14.2 plan.

| Operation | Target (p95, warm) | Measured (2.8.0, one workstation) | Extrapolated |
|---|---|---|---|
| VaR, ES and headline of a precomputed node | 100 ms | under 1 ms from the node's aggregate; 2.6 ms for *the bank minus one desk* (the planner's aggregates) | independent of the bank's size |
| the whole bank summed from its trades (a filter that cuts across every node) | | 58 ms for 1 M trades (70 GB/s on 24 threads) | 2 M: about 0.12 s on one server, 0.03 s on four; 5 M: about 0.29 s on one, 0.07 s on four (target 0.14 s on four for 2 M) |
| P&L path (509 rows) of a node | 100 ms | the explorer: click to painted, a desk 9 to 14 ms when its answers were fetched ahead, 30 to 35 ms when not (target from the product owner: 300 ms) | |
| contributions of a node's children (up to 500) | 300 ms | 148 ms for a desk's **5,000** positions (standalone, component, incremental VaR each) | linear in the parts |
| a scope written as nodes plus or minus up to 1,000 positions | 500 ms | 2.6 ms (bank minus a desk) | |
| an ad-hoc scope of 50,000 positions (any shape) | 250 ms | 52 ms: 43 ms reading the 50,000 ids, 9 ms to sum and measure | about the same at 5 M (the sum reads only the basket's rows) |
| a filter scope over the whole bank (all 2 million positions considered) | 3 s on a three-node cluster | | |
| loading a date into memory (sort into hierarchy order, permute, sum every node) | | 3.4 s for 1 M trades (after reading); the demo's 1,673 trades: 0.6 s from the lake | minutes for 5 M, as section 13.4 says |
| VaR and ES history, 250 days, of a precomputed node | 300 ms |
| VaR history, 250 days, of an ad-hoc scope of up to 10,000 positions | 60 s, as a visible job ([BI_RUNS.md](BI_RUNS.md)), then cached |
| position-level component VaR for the whole bank | 30 s, as a job |
| what-if: one vanilla trade priced over 509 scenarios | 3 s (depends on the pricer; QLRisk to be measured) |
| what-if: 100 trades | 60 s, with progress |
| an applied write-back reflected in the shared VaR on every watcher's screen | 2 s after the change applies (coalescing included) |

### 13.3 Where the arithmetic happens

Moving gigabytes of vectors from the lake into a Python worker for every query would be too slow. So:

- The **vector store** (generic, in `drishti-compute`, planned) keeps the hot dates' position vectors in memory as dense row-major
  `double[]` chunks ([VAR_DATA_MODEL.md section 12](VAR_DATA_MODEL.md#12-serving-from-memory)), read once from the lake per date, and
  answers **sums**: "the sum of the vectors of these positions", "the sum per child of this node". Summing
  is plain additions over contiguous memory, which the JVM vectorises; it is bound by memory bandwidth (8 GB in roughly half a second
  on one modern socket, much less for a desk).
- In a cluster the vector store is **sharded** by node of the hierarchy (a desk's vectors on one server) across Drishti\*net; a sum
  over the bank is a scatter-gather of partial sums (509 numbers per server). Sums are exact to add in any order in float64 to well
  within a currency unit at this scale.
- The **Python engine** (`drishti-var`) receives only the small vectors it needs (the scope's sum, its children's sums, the vectors of
  positions it must show) and computes measures, contributions and tests.
- **Pricing** (what-if, intraday) runs in compute workers and returns vectors to the intraday layer.

Memory for the hot cache: 8.1 GB per day for the reference bank; keeping today and yesterday hot (most queries) is about 16 GB across
the cluster; older dates are read from the lake when asked.

### 13.4 The overnight precomputation

After the batch's vectors land (the `data-version` trigger), a batch-class job of `var-engine`:

1. builds the position index and loads the hot cache for the date;
2. sums every node's vector bottom-up (each position read once) and writes `var-node` records for the date;
3. computes each node's VaR, ES and backtest result;
4. recomputes every saved dynamic basket that someone opened in the last 30 days, so it is warm in the morning.

For the reference bank this is about one pass over 8 GB plus 5,000 small quantile computations: minutes, not hours.

## 14. APIs and CLI

The computed kinds are read like any kind (Sutra `source:`, BI, `GET /api/v1/views/...`) and through the compute API
([REACTIVE_CONNECTORS.md section 16](REACTIVE_CONNECTORS.md#16-the-spi-and-the-apis)). A thin, readable surface is added for programs
(all planned, 2.8.0):

| Method and path | Returns |
|---|---|
| `GET /api/v1/var/scopes/{scope}?asOf=&confidence=&horizon=&method=` | the headline result with its stamp |
| `GET /api/v1/var/scopes/{scope}/path` | the 509 scenario P&Ls with dates and ranks |
| `GET /api/v1/var/scopes/{scope}/history?days=250` | VaR, ES, hypothetical P&L and exceptions per day |
| `GET /api/v1/var/scopes/{scope}/contributions?by=child\|position\|risk-class&scenario=` | contributions, optionally at one scenario date |
| `POST /api/v1/var/baskets`, `GET`, `PATCH`, `DELETE /api/v1/var/baskets/{id}` | baskets |
| `POST /api/v1/var/sandboxes`, `PATCH /api/v1/var/sandboxes/{id}` (add or remove changes), `GET /api/v1/var/sandboxes/{id}/result` | what-if |

`{scope}` is the canonical scope text, URL-encoded. A token needs the `compute` scope. CLI (planned): `drishti.py var show <scope>`,
`var path`, `var history`, `var basket save`, `var whatif`.

## 15. Phased delivery

| Step | Delivers | Accepted when |
|---|---|---|
| V0 (built) | the stored kinds (`position`, `risk-hierarchy`, `scenario-set`, `pnl-vector`, `var-node`, `pnl-hypothetical`) in the market-risk pack; vector columns in the lake tools and the Delta plugin ([VAR_DATA_MODEL.md section 15](VAR_DATA_MODEL.md#15-fit-with-the-repositorys-data-pattern-and-what-must-change)); deterministic demo data in `data/testdata/market-risk/`; the Delta tables and `risk-store` routes; a local generator for sizing sets | the demo lake builds with the demo connector off; node vectors equal the sum of their positions |
| V1 (built, in the server: section 18) | `var-engine` (reactive connector) and `compute/drishti-var`: scope algebra, VaR, ES, path, contributions by child; the VaR explorer Sutra and its new UI kit fragments | any node and any scope of the grammar shows headline, path (table and charts) and contributions; components add up to the smoothed VaR |
| V2 (built) | history and backtesting: the overnight precomputation, `var-node` history, exceptions, traffic light, Kupiec and Christoffersen | the history tab shows 250 days for any node with exceptions marked |
| V3 (built, with captures) | baskets, entitlements by node (`positions`, `aggregate`), audit entries, official numbers | an aggregate-only viewer sees a division's numbers and never a position; every answer is audited |
| V4 | what-if: sandboxes, unwind and linear scaling (no pricing), the reprice check; **pricing through QLRisk if its API is stable** (else 2.8.x); the QuantLib-Java parity | a sandbox removing three trades matches the scope minus those trades exactly; with QLRisk, a new swap's incremental VaR is shown in under 3 s |
| V5 | intraday: `write-back` and `data-version` triggers, the intraday layer, per-event impacts, pushes ([WRITE_BACK.md section 18](WRITE_BACK.md#18-280-saving-through-connectors-triggers-and-viewers)) | two traders' amendments to one book: each sees their own impact, both see one shared number stamped with both events |
| V6 | scale: the hot vector store, sharding across Drishti\*net, the targets of [section 13.2](#132-targets) measured on a real server | the measured numbers are published in the scale document; any target missed has a stated reason and plan |

## 16. Tests

- **Additivity:** for random scopes, the scope vector equals the sum of its positions' vectors; node vectors equal their children's sums.
- **Algebra:** canonical forms of equal scopes are equal; `A + B - B` equals `A` minus the overlap rule; union, difference and
  intersection against a brute-force set implementation.
- **Measures:** VaR and ES against textbook cases and against QLRisk's `var()` and `es()` on the same vectors (when stable); component
  ES sums exactly to ES; smoothed components sum to the smoothed VaR.
- **What-if:** unwinding equals the difference scope; the reprice check flags a deliberately wrong scenario set.
- **Entitlements:** an aggregate-only viewer's every response is free of position ids; filter scopes over aggregate-only nodes are
  refused; two viewers never share a cached result.
- **Intraday:** incremental equals full recompute after random amendment sequences.
- **Data pattern:** the pack works with the demo connector off, from the Delta lake built from the committed test data.
- **Windows:** everything except QuantLib-Java (skipped with its reason; QLRisk per PYTHON_COMPUTE.md open question 1).

## 17. Open questions for the product owner

1. **Which pack?** Add the VaR kinds and screens to the existing market-risk pack (proposed), or a new `var` pack?
2. **The existing `var` kind.** Keep it as sample data beside the engine, or retire it once the engine runs?
3. **Rank rule.** For N = 509 and 99%: the 6th worst (k = ⌈N(1−α)⌉, proposed), the 5th worst, or interpolated? Does the bank have a
   house convention to match?
4. **Precision of the hot cache.** float64 everywhere (proposed; 8.1 GB per day for 2 million positions) or a float32 hot cache with
   float64 sums (half the memory, small rounding in individual vectors)?
5. **Risk-class split.** Is a per-risk-class vector (`PNL_BY_RISK_CLASS`) needed in 2.8.0? (Position grain and the other data
   questions are in [VAR_DATA_MODEL.md section 17](VAR_DATA_MODEL.md#17-open-questions-for-the-product-owner).)
6. **Retention.** How many days of position vectors should stay in the lake (the reference sizing assumes about 1.5 TB per year), and
   how long are official numbers kept (proposed: as long as the date's data)?
7. **10-day VaR.** Will the bank's batch provide 10-day vectors, or is √10 scaling acceptable (always labelled)?
8. **Who produces vectors in production?** The bank's existing risk batch (Drishti only loads them, proposed) or Drishti's own batch
   through QLRisk?
9. **Entitlement source.** Are the per-node levels (`positions`, `aggregate`) maintained in Drishti, or read from the bank's
   entitlement system?
10. **Committed demo data size.** About 200 positions over 60 days (about 21 MB compressed) is proposed; is that acceptable for the
    repository?

## 18. As built in 2.8.0: waves V0 to V3

What was built (2.8.0, unreleased), where it differs from the sections above, and why. The user guide is
[VAR_EXPLORER.md](../guides/VAR_EXPLORER.md); the data as built is [VAR_DATA_MODEL.md section 18](VAR_DATA_MODEL.md#18-as-built-in-280).

### 18.1 Where it lives

| Part | As built |
|---|---|
| the pack | the **market-risk** pack (open question 1): the kinds `position`, `portfolio`, `pnl-vector`, `scenario-set`, `risk-hierarchy` (`tools/packgen/banking/var_kinds.py`, written into `pack.yaml` by `make_packs.py`), routed to `risk-store`, and `position` to the `var-positions` connector (the engine's derived positions, section 18.6); mnemonics `POS`, `PORT`, `PNLV`, `SSET`, `HIER` (`VNODE` and the `var-node` kind retired in 2.8.0); Sutras for `portfolio` and `position` |
| the stored data | `risk-store` (Delta), the vectors in a `list<double>` column (`layout.<kind>.vectors: [PNL]`); demo data from `tools/packgen/var/make_var_data.py` |
| the engine | **in the server, in Java**: the generic vector store, ordinal sets, parallel sums and the VaR maths in the module `drishti-compute` beside the compute workers (`com.ash.drishti.compute.vector`, `com.ash.drishti.compute.var`); loading, entitlements, baskets, captures and the API in `drishti-server` (`com.ash.drishti.server.var`). Not yet a reactive connector (waves R1 to R4) and no Python package: base VaR is additions and sorts, which Java does next to the data without moving vectors to a worker |
| the explorer | a console page (`/var`, `drishti-console/routes/var_routes.py`, `web/static/js/var-explorer.js`, `var-charts.js`, `web/static/css/var.css`) over `/api/v1/var`, not a Sutra: a Sutra's `source:` reads a kind, and the parametrised sources a VaR panel needs come with reactive connectors (R2). The portfolio and position pages are Sutras, with the new UI kit fragment `pnl-distribution@1` |

**The compute boundary.** Everything in V0 to V3 is base VaR: sums of stored vectors and measures over the sums, in the server's JVM on
a bounded pool (`drishti.var.threads`). The heavy work that needs Python or a pricer (what-if pricing over the scenarios, V4; intraday
repricing, V5) is a separate request of whole trades to price, which the compute workers of PYTHON_COMPUTE.md (C1 to C3, built) serve: any number of
workers (their count is configuration, never code), one trade or a batch each, the 509 values each returns joining the intraday layer
as a vector. Nothing in the engine assumes one worker or calls one in its request path.

### 18.2 What it does

- **Scopes** (section 6): the grammar as designed, plus `TRD:` (trades), `PORT:` (a stored portfolio), `CAPTURE:` (a capture's members)
  and node aliases (`DESK:RATES` = `DESK:DESK-RATES` = `NODE:MGMT~DESK-RATES`). `WHERE:` runs a small part of DEL over the positions'
  columns (`PositionRule`; the same grammar in `tools/packgen/var/rules.py`). Scopes resolve to sets of trade ordinals (exact union,
  difference, intersection); a scope's vector is planned over the node aggregates (a whole node is its aggregate, a node minus a few
  trades its aggregate minus them, scattered trades one parallel pass); the plan never changes the result (tested against brute force).
- **Measures** (section 7): VaR by the rank rule (default k-th worst, k = ceil(N (1 − a)): the 6th of 509; `interpolated` by setting),
  ES (the mean of the 13 worst at 97.5%), standalone, component (Euler on the five scenarios around rank k, **scaled to add up to the
  VaR exactly**; component ES adds up to ES), incremental (VaR without the part, recomputed), marginal (per million of market value) and
  diversification. Historical method, equal weights, 1-day; `age-weighted` and `filtered`, 10-day and stressed sets are not built.
- **History and backtesting** (section 11): a node's official figures (from 2.8.0 part 1: its official end-of-day captures, section 18.6; before: the stored `var-node`), exceptions against the previous
  day's VaR, the Basel light (limits scaled for a shorter history), Kupiec's and Christoffersen's statistics; any other scope replays
  today's members over the last 250 scenario days (in-sample, labelled). Acerbi–Székely and the overnight precomputation job are not built
  (the demo data carries the nodes' history; the engine sums every node when a date loads).
- **Baskets** (section 6.3): saved from the explorer's *New scope*, in the content store under `var/baskets/<owner>/`, versioned,
  shared read-only with users or roles, read as `BASKET:<owner>/<name>`.
- **Captures** (new, product owner 2026-10-24): an immutable snapshot of a scope's numbers, full vector, members and provenance, in the
  content store under `var/captures/<owner>/`; listed per scope, reopened exactly, compared with another capture or with now, exported as
  CSV; `VAR CAPTURE <id>`.
- **Entitlements** (section 12): per role and node, `positions` or `aggregate` (`drishti.var.access`); a role without an entry sees
  every node when it may open `pnl-vector`. Scopes naming positions, trades or rules are refused to a viewer who sees them in aggregate
  only. Results are cached per date, data version, view and scope.
- **Audit**: every headline answer (`var-view`), capture (`var-capture`) and basket change; who, the scope, the date and version, the
  view, the VaR and ES, the members' hash and the time.
- **The API** (section 14): `GET /api/v1/var/scope?scope=` (and `/scopes/{scope}` for a scope without a slash), `/scope/path`,
  `/scope/history`, `/scope/contributions?by=&scenario=`, `/tree`, `/status`, `POST /reload`, `/baskets`, `/captures`, `/compare`. The
  sandboxes of section 8 and the CLI verbs are not built.

### 18.3 Decisions on the open questions (the design's recommended defaults)

| Question | Decision |
|---|---|
| 1. Which pack | the **market-risk** pack |
| 2. The existing `var` kind | kept beside the engine in V0 to V3; **retired** in part 1 (section 18.6): `VAR VAR-RATES` opens the explorer at `DESK:RATES` |
| 3. Rank rule | **the 6th worst of 509** (k = ceil(N (1 − a))), configurable (`drishti.var.rank-rule: interpolated`) |
| 4. Precision | **float64** in the lake, in memory and in sums; no float32 cache |
| 5. Risk-class split | **not in 2.8.0** (no `PNL_BY_RISK_CLASS`) |
| 6. Retention | **7 years** of vectors and their time travel (product owner, 2026-10-25; section 18.6) |
| 7. 10-day VaR | not built; the screens say *1 day* |
| 8. Who produces vectors | **the bank's batch**; Drishti loads them (the demo's come from a synthetic factor model) |
| 9. Entitlement source | **in Drishti** (`drishti.var.access`) |
| 10. Demo data size | **15.7 MB** from part 1: one business date of vectors for every live trade (9,572); 3.5 MB before (1,673 trades and 250 days of node figures) |
| VAR_DATA_MODEL.md 1. Position grain | **vectors at trade grain**; a position is a holding of one instrument in one book netting its trades (VAR_DATA_MODEL.md section 18) |
| VAR_DATA_MODEL.md 9. Roaring bitmaps | **not added**: a plain bitmap of the date's rows (java.util.BitSet) is small enough |

### 18.4 Scale

The engine is written for the whole bank: the vector store holds rows in `double[]` chunks of 65,536 rows (no object per row, nothing
boxed), within a memory budget (`drishti.var.memory-budget-mb`; a date that would exceed it is refused with the reason); a date's
rows are put in hierarchy order once, so every book and position is one run of ordinals; every node's vector is summed when the date
loads (the cached aggregates the planner uses); scopes are bitmaps; sums run on a bounded pool on every core, slices added in a fixed
order (the same scope gives the same bits). Section 13.2 has the measured numbers next to the targets. What is not built for scale
yet: the sharding of the store across Drishti\*net (V6), the local memory-mappable copy of a loaded date, and incremental maintenance
of node aggregates by intraday deltas (V5: with no intraday events in V0 to V3, a new batch reloads the date).

### 18.5 Tests

`drishti-compute`: `VarMathTest` (hand-made vectors: the 6th worst and the mean of 13, ties, components adding up to VaR and ES, a long
and a short netting), `VectorStoreTest` (sums against brute force across chunks, parallel equal to serial, set algebra, the budget),
`VarBookTest` (hierarchy order, node vectors equal the sums of their positions, the planner equal to brute force for random scopes,
overlaps counted once, trade, position and desk VaR consistent), `ScopeAndRuleTest`, `BacktestTest`, `VarScaleBenchmark` (opt-in).
`plugins/drishti-plugin-delta`: `DeltaVectorColumnTest` (a vector column written and read back to the bit, in bulk and in a document).
`tools/lake/test_vectors.py` (the Python writer). `tools/packgen/var/test_var_data.py` (the demo data: positions net their trades,
node vectors, portfolio membership by type). `drishti-server`: `VarApiTest` (a hand-computed lake: every measure, portfolios and
overlaps, contributions, history, entitlements, baskets, a capture read back after the data changes). `drishti-console`:
`test_var_explorer_browser.py` (the command, a drill from bank to trade in place with the paint time, the tabs, an ad-hoc basket saved
and captured, the portfolio and position pages, a phone), with the demo connector off.

### 18.6 VaR complete, part 1 (2.8.0)

The contract as built is [VAR.md](../contracts/VAR.md) (version 1.3); this section lists what changed in the engine.

| Change | As built |
|---|---|
| Stored kinds retired | `var` and `var-node` are gone (kinds, mnemonics, samples, test data, Sutras, about texts, Calc snippets, generators, contracts). `VAR <old VAR-* id>` and `/v/var/<id>` open the explorer at the matching desk (`VAR-COMM` → `DESK:COMM`), `VN-<node>` at `NODE:<node>` (console, `var_routes.py`) |
| No stored figures | `position`, `portfolio` and `desk` carry no VaR figure; their pages show the engine's, added as the page opens, as the reader (`VarFigures`, an engine `ViewAugmenter` in the view pipeline; `VAR_ENGINE` in the document drawn). A guard test (`tools/test_contracts.py`) fails the build when a retired kind or figure returns |
| Derived positions | `PositionRules` (`drishti-compute`): the pack's `positions:` section (securities by book and security, listed by book and contract, OTC one per trade; the live statuses); applied by `VarIntake` as each vector row streams in, rebuilt with every load; served as `position` by the `var-positions` source plugin |
| Load checks | `VarIntake` rejects wrong-length, null/NaN, other-currency and failed-with-vector rows (`DRS-5201`), leaves out rows not live; `VarLoader` refuses duplicate trade ids (`DRS-5202`) and mixed runs or scenario sets (`DRS-5203`: the date before is answered, with a warning); the memory budget answers `503 DRS-5204` |
| Official captures | `VarOfficial` (one capture at a time, its own thread): every MGMT and LEGAL node of a date as an official capture (`var/captures/official/`), the date's record and membership (`var/official/`), restatements as new result versions; `VarOfficialStore` serves the node history from them; `VarPublisher` appends each run to `risk-results/var-official` (Delta, append-only, 7-year retention) |
| Load signal | `LoadService.onReady`: a ready load of `pnl-vector`, `scenario-set`, `risk-hierarchy`, `portfolio` or `trade` drops the date and captures it officially; at start the newest date without a record is captured |
| Scopes | `LE:` resolves by the trades' `LEGAL_ENTITY` (mismatch with the `LEGAL` tree: a provenance warning); `CPTY:` by `COUNTERPARTY`; `POS`/`TRD`/`CPTY` ids kept as written and matched in any case; `PORT:` of a BASKET portfolio refused to aggregate-only viewers |
| Answers | `by=node`; the largest `max-parts` parts by gross size; field masks in contribution rows; provenance with every input's version, the batch ids, the rules' digest, rejections and warnings; audit of basket changes, reloads and official runs |
| Retention | the lake writers set 7-year log and deleted-file retention on `risk` and `risk-results` tables; `lake maintain` never vacuums them below it |
| Test data | every live trade valued (9,572; 7,984 derived positions), synthetic security ids, 28 portfolio definitions (`tools/packgen/var/portfolios.py`) |

Not in part 1: memory tiering and the hot file (VAR.md section 7.3), the 1 M-trade × 50-date benchmark, the Python engine and
reactive connector, the VaR guide's rewrite.

**Tests added:** `ScopeAndRuleTest` and `VarBookTest` (derived positions, ids in any case, `LE:`, `CPTY:`, duplicates), `VarApiTest`
(official captures, publishing and restatement, the load signal, page figures, a date that is not one run, duplicates), `ViewPipelineTest`
(augmenters), `LakeWriterTest` (table properties), `tools/lake/test_retention.py`, `tools/test_contracts.py` (`TheRetiredStoredVersions`),
`tools/packgen/var/test_var_data.py`, `drishti-console/tests/test_var_routes.py` (old ids).
