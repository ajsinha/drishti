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
# The MR Cube: a market risk cube lens that answers what FIS Adaptiv's artiQ answers, and shows why

**Status: analysis and product description; design, not built.** Nothing in this document is built unless a row of
[section 5](#5-what-drishti-has-today) says so and cites the guide it was read from (develop at b22ff3850 and the `wip/drill-a`
consolidation, 2026-10-31). Every release named here is a **proposal**, not a commitment. The rows it proposes are candidates in the
[implementation plan](../architecture/IMPLEMENTATION_PLAN.md#risk-cubes-candidates-not-started) (MC1 to MC13), not started.

**The request (product owner, 2026-10-31):** what it takes to build something like FIS Adaptiv's risk cube (artiQ) in Drishti, "even
better"; then separate designs for market risk (this document) and counterparty credit risk ([CCR_CUBE_LENS.md](CCR_CUBE_LENS.md));
then: make both of them lens documents, make the data and contracts chapter exhaustive, and write everything from the angle of beating
FIS, SunGard and Adaptiv in capability and in visualisation.

**Read with:** [VAR_GUIDE.md](../guides/VAR_GUIDE.md) (the VaR engine as built), [OVERSIGHT_LENS.md](OVERSIGHT_LENS.md) (the second line,
the `position-sensitivity` contract this document adopts), [RISK_LENS.md](RISK_LENS.md) and [TRADING_LENS.md](TRADING_LENS.md) (the
lenses the cube serves), [COMPUTED_ATTRIBUTES.md](../architecture/COMPUTED_ATTRIBUTES.md) (compute, never store; the modelling defaults
of its section 10, confirmed by the product owner on 2026-10-31), [REGULATORY_VAR_REPORTING.md](../architecture/REGULATORY_VAR_REPORTING.md)
(2.9.8 and 2.9.9), [CLUSTER_DEPLOYMENT.md](../architecture/CLUSTER_DEPLOYMENT.md) (sharding), and the contracts [FRTB.md](../contracts/FRTB.md),
[STR.md](../contracts/STR.md) and [PNL.md](../contracts/PNL.md).

## Contents

1. [Purpose: what a risk cube is, and what FIS offers](#1-purpose-what-a-risk-cube-is-and-what-fis-offers)
2. [Who uses it](#2-who-uses-it)
3. [Their questions: the morning, intraday, the close, the quarter](#3-their-questions-the-morning-intraday-the-close-the-quarter)
4. [The shared cube core (MR and CCR)](#4-the-shared-cube-core-mr-and-ccr)
5. [What Drishti has today](#5-what-drishti-has-today)
6. [Screens and visualisation](#6-screens-and-visualisation)
7. [Data and contracts](#7-data-and-contracts)
8. [Measures](#8-measures)
9. [Pre-deal what-if](#9-pre-deal-what-if)
10. [Performance and scale targets](#10-performance-and-scale-targets)
11. [How this beats FIS Adaptiv / artiQ](#11-how-this-beats-fis-adaptiv--artiq)
12. [Phases (proposed)](#12-phases-proposed)
13. [Risks and data dependencies](#13-risks-and-data-dependencies)
14. [Decisions for the product owner](#14-decisions-for-the-product-owner)
15. [Glossary](#15-glossary)

**Status words,** as in the other lens documents. **Built**: on develop, documented as built. **Partly**: some of it is built; the cell
says what is missing. **Planned (row)**: a plan row names it. **Proposed (MCn)**: proposed here. **Not built**: neither.

---

## 1. Purpose: what a risk cube is, and what FIS offers

A **risk cube** is a multidimensional store of risk numbers at the lowest grain the bank produces (a trade, a position, a scenario, a
risk factor), with **dimensions** (desk, book, legal entity, currency, product, issuer, tenor, scenario day, any attribute) that a user
puts on rows and columns, and **measures** that the cube aggregates for every cell on demand. What makes a *risk* cube harder than a
business-intelligence cube is that its most important measures are **not additive**: the VaR of two desks is not the sum of their VaRs,
and a regulatory charge is a square root of correlated sums. A correct cube therefore keeps the **vectors** (scenario P&L, sensitivities
per factor) and re-aggregates them for each cell; it never sums the cells' figures.

**What FIS claims in public** (marketing and award material, not technical documentation; no independent benchmark exists):

| Claim | Where FIS or the press says it |
|---|---|
| An in-memory aggregation cube that aggregates risk across all risk types, firm-wide results down to granular factors | [Risk.net, Market risk management product of the year: FIS](https://www.risk.net/awards/7676746/market-risk-management-product-of-the-year-fis) |
| Streaming aggregation in real time; incremental and what-if impacts on a real-time basis | the same article |
| "Mixed mode": the system values deals itself for risk, and accepts front-office valuations as inputs | the same article |
| Data first written to a detail pre-aggregation store; cubes "hydrated" from it; several dates, per-user offline cubes, recovery | [FIS, FRTB Risk Aggregation: An IT Perspective (white paper)](https://www.fisglobal.com/-/media/fisglobal/files/PDF/white-paper/FRTB-Risk-Aggregation-An-IT-Perspective-White-Paper.pdf?sc_lang=en) |
| Multi-server cube distribution so users see one cube | the same white paper |
| Non-additive VaR and ES over billions of scenario P&L values; FRTB SA and IMA; on-the-fly stress; users correct erroneous data in the cube; limit governance; Excel, Tableau and XLCubed front ends; Kafka streaming | FIS product pages and brochures, as summarised in the request (not independently verified here) |
| The wider suite: XVA, SA-CCR, SIMM, SA-CVA | FIS product pages (the counterparty side is [CCR_CUBE_LENS.md](CCR_CUBE_LENS.md)) |

**Caveat.** These are vendor claims. FIS publishes no detail on how the cube shards, how it fails over, or how fast it is at a stated
size. An older SunGard "Adaptiv risk cube" was built on Microsoft SQL Server Analysis Services; nothing suggests artiQ's figures resemble
it, and this document does not use them. Where this document says "beats", it means a claim we can **show** in a demo on Drishti's own
data (the *proof* column of [section 11](#11-how-this-beats-fis-adaptiv--artiq)), not a claim about FIS's internals.

**What Drishti's answer is, in one paragraph.** Drishti already holds the hard part of a market risk cube: every position's 509-scenario
P&L vector in memory, any scope's VaR and ES re-aggregated from the vectors, components that add up exactly, intraday updates from files
and Kafka, what-if, official records and sharding equal to the bit. The **MR Cube** adds the parts a cube needs on top of that: a
**pivot** over any attributes with correct non-additive totals, **sensitivities** in the lake, **FRTB SA and IMA** computed from the
trade grain, **pre-deal pricing**, and a **visual language** (in-cell sparklines and limit bars, waterfalls, tail histograms, factor heat
maps, ladders, a time-travel slider, provenance on every cell) that a vendor cube with an Excel front end cannot offer.

---

## 2. Who uses it

| Person | What they do with the cube | Lens today |
|---|---|---|
| **Market risk manager near the desks** | morning VaR by desk × risk class, the tail scenarios, sensitivity ladders against limits, what changed | Drishti Risk ([RISK_LENS.md](RISK_LENS.md)) |
| **Second-line oversight** | bank-wide pivots, limit utilisation of every kind, why VaR moved, data trust | the proposed Oversight lens ([OVERSIGHT_LENS.md](OVERSIGHT_LENS.md)) |
| **Desk head and trader** | my books by product and currency, my VaR contribution, "what does this trade do" | Drishti Trading ([TRADING_LENS.md](TRADING_LENS.md)) |
| **Regulatory capital team** | FRTB SA by risk class and correlation scenario, IMA ES by liquidity horizon, PLA and backtesting by desk | none today; 2.9.8 and 2.9.9 build the reporting chain |
| **Product control** | hypothetical and actual P&L against the risk-theoretical P&L, restatements, corrections | none today |
| **Model validation** | the scenario set, proxies, NMRF, PLA statistics, the provenance of every number | none today |

The MR Cube is **a lens** ([EDITIONS.md section 8](../admin/EDITIONS.md#8-lenses)): `Open Drishti MR Cube` from Views and Home on a full
instance, and a cube **screen** (`MCUBE <GO>`, the command word the product owner chose on 2026-10-31) inside Drishti Risk, Trading and Oversight. It
hides what a cube user does not need (Build, Studio, the World and Market packs' menus) and keeps the VaR explorer, SMAT, the
counterparty cube and BI one click away.

---

## 3. Their questions: the morning, intraday, the close, the quarter

| When | Question | The cube's answer |
|---|---|---|
| Morning (07:30) | Where is the bank's VaR, by division × risk class, against limits? | a pivot, rows the MGMT hierarchy, columns risk class, cells VaR with a limit bar; totals re-aggregated |
| Morning | Which scenario days drive the tail, and are they new? | the tail histogram with V and E markers; SMAT one click away |
| Morning | Why did the credit desk's VaR rise 18 m overnight? | the "explain the move" panel (RO1's exact steps, then the factor overlay) |
| Intraday | What does the 10:40 batch from Murex do to the rates desk? | the cell flashes, the sparkline extends, the provenance popover names the batch |
| Intraday | What if we unwind these three swaps? Price this new one. | the what-if side by side: before, after, incremental, per cell |
| Close | Am I within my DV01 limits by currency × tenor? | the sensitivity ladder with two-sided bars |
| Close | Which issuers concentrate CS01? | the factor heat map, top N, Herfindahl |
| Month-end | FRTB SA by risk class, the binding correlation scenario | the SBM pivot with the three scenarios side by side |
| Quarter | IMA: ES by liquidity horizon, NMRF capital, PLA zones per desk | the IMA pivot and the desk zone board |
| Any time | Where did this number come from? | the provenance popover: data version, batch, scenario set, inputs, method, as-of |
| Any time | Show me last Tuesday as it was at 11:00 | the time-travel slider over data versions |

---

## 4. The shared cube core (MR and CCR)

This section is the **core** both cubes share. [CCR_CUBE_LENS.md](CCR_CUBE_LENS.md) refers to it and does not repeat it.

### 4.1 The pivot model

A **cube view** is a scope, a set of **dimensions** on rows and columns, a set of **measures**, filters, a business date (or two for a
change) and a lens (horizon, confidence, reporting currency).

- **Dimensions** come from any attribute the inputs carry (`LEGAL_ENTITY`, `DIVISION`, `DESK`, `TT_BOOK`, `LIMIT_LETTER`,
  `PRODUCT_GROUP`, `PRODUCT_TYPE`, `CURRENCY`, `COUNTERPARTY`, `TAGS`, `SOURCE_SYSTEM`), from any **hierarchy** the engine holds
  ([VAR_GUIDE section 15.14](../guides/VAR_GUIDE.md#1514-hierarchies-from-the-rows-or-from-the-trades-columns-as-built-295): MGMT, LEGAL,
  limit letters, any configured), from the **risk-factor** parts of the sensitivity contract (risk class, curve, tenor, issuer, bucket of
  any grid), from **reference data** joined by id (an issuer's sector and rating, a counterparty's country), and, for scenario measures,
  from the **scenario day**.
- **Measures** fall in three classes, and the class decides how a total is made:

| Class | Examples | How a cell and a total are made |
|---|---|---|
| **Additive** | PV, notional, DV01 by bucket, CS01, delta, vega, stress P&L of one scenario, EE (CCR) | the sum of the members; a total is the sum of its cells |
| **Vector-based, non-additive** | VaR, ES, stressed VaR, component VaR, ES by liquidity horizon, PFE (CCR) | the members' **vectors** summed element by element (float64, a fixed order), then the statistic (a rank, a tail mean); a total re-aggregates the vectors of all its members |
| **Formula, non-additive** | FRTB SBM (square roots of correlated sums), DRC, SIMM (CCR), SA-CCR add-ons (CCR) | the members' **inputs** summed to the formula's own grain (net weighted sensitivity per factor, bucket), then the formula; a total re-runs the formula on the union |

### 4.2 Correct totals for non-additive measures

**The rule: a cell or a total is always computed from the vectors or inputs of its members, never from other cells.** A pivot of desks ×
risk classes shows, in the bottom-right cell, the VaR of the union of every member of every visible cell (the bank, or the filtered
part), computed once from its own summed vector, not the sum of the row totals. Row and column totals and the grand total are each one
more scope. The cube shows the **diversification** of a row or column beside its total (Σ cells − total), so the reader sees why the
total is smaller.

This is how the VaR engine works today: any scope's vector is summed from the position rows in a fixed order, and node aggregates
equal the same sum to the bit ([VAR_GUIDE section 18.6](../guides/VAR_GUIDE.md#186-horizons-the-two-10-day-methods-and-the-confidence-chosen-as-built-297),
"sums for any scope come first"). A pivot of R × C cells is R × C + R + C + 1 scopes; each is a planner request over the date's rows.

### 4.3 Drill-through, saved views and sharing

- **Drill-through**: any cell opens its members (a desk's books, a book's positions, a position's trades, a factor's positions) with the
  same measures; the last level opens the trade (`TRD`), the position, the P&L vector (`PNLV`), or the scenario matrix (`SMAT`). The
  breadcrumb and Back work as in the VaR explorer ([VAR_GUIDE section 3.3](../guides/VAR_GUIDE.md#33-drill-down-bank-desk-book-position-trade)).
- **Saved views**: a view (its scope, dimensions, measures, filters, lens, sort, column widths, visual choices) is saved under a name,
  private or shared with a group, and opened by a link or a command (`MCUBE @asha/morning`). A shared view opens with **the reader's**
  entitlements; it is never a copy of the numbers.
- **Sharing**: the existing share with a note and comment threads ([COLLABORATION.md](../architecture/COLLABORATION.md)) work on a cube view; a
  comment anchors to a cell (dimension values and measure), so it survives a re-sort or a new date.

### 4.4 Provenance per cell

Every cell carries its provenance, opened by hovering (desktop) or long-pressing (phone): the business date, the **lake data version**
(Delta version or Iceberg snapshot of each input table), the batches included (full and intraday, in order), the scenario set and its
window, the method (historical simulation, equal weights, rank k, 1-day or 10-day and its method), the reporting currency and FX source,
the members counted, the **proxied share**, any refused or failed members by reason, the shards and who answered, and the time taken.
Today the VaR headline already returns most of this ([VAR_GUIDE section 11.2](../guides/VAR_GUIDE.md#112-the-headline-get-scope-and-its-provenance));
the cube returns it per cell, compactly (a provenance id per answer, details on demand).

### 4.5 Explain

Beside the figures, never on another screen:

- **Why it moved** (day over day, or between any two versions): RO1's fixed sequence of exact steps (restatement, hierarchy, trades out,
  window roll, market, trades in) whose differences add up to the change, each with its top contributors
  ([OVERSIGHT_LENS.md section 4.1](OVERSIGHT_LENS.md#41-ro1-var-change-explanation)), then the approximate **factor overlay** from
  sensitivities (Σ sensitivity × factor move, with its residual).
- **What drives it**: components (Euler, adding up exactly), incremental and marginal VaR
  ([VAR_GUIDE section 18.3](../guides/VAR_GUIDE.md#183-decomposition-of-a-scope-into-parts)).
- **How it was computed**: the formula with this cell's numbers in it (for SBM: the weighted sensitivities, the bucket K_b, the
  correlation scenario that binds).

### 4.6 Data correction: write-back with approvals and audit

FIS says users correct erroneous data inside the cube. Drishti does it through **write-back** ([WRITE_BACK.md](../guides/WRITE_BACK.md),
built 2.8.0: a change request, approval by a second person, an edit log, audit): a user marks a member (a P&L vector, a sensitivity
record, a trade attribute) as wrong, proposes a correction (a proxy vector, an excluded record with a reason, a corrected attribute),
and an approver applies it. The correction is **an overlay record** in the lake with its author, approver, reason and expiry; the
original stays; every cell that uses it shows a **corrected** marker and its provenance names the correction. The official run either
includes approved corrections or not, by a setting; the choice is recorded with the run. Nothing is edited in place.

### 4.7 Streaming updates

The cube subscribes to the engine's intraday updates (files appended to the lake, or the Kafka PV stream: VAR_GUIDE section 8.4, built
in 2.9.4, `VarPvStream`). A visible cell whose members changed is recomputed and **patched in place** through the live channel (the
same machinery as live views), flashing for a second with its old value on hover; a cell more than one batch behind says so. Throttle:
a view recomputes at most once per batch, coalescing bursts.

### 4.8 Export

| Format | How | Today |
|---|---|---|
| xlsx | the view as a formatted workbook (one sheet per measure, totals, provenance sheet) | BI already exports statements as xlsx ([BI_GUIDE.md](../guides/BI_GUIDE.md)); the cube reuses it |
| csv | the cells, unformatted, one row per cell | the VaR explorer exports CSV |
| Power Query | a REST endpoint per saved view, with the token in the header | the pattern exists for search ([API_GUIDE.md](../guides/API_GUIDE.md)) |
| Arrow | `Accept: application/vnd.apache.arrow.stream` on the view's answer | built for two answers in 2.9.7 ([API_GUIDE.md, answers as Arrow](../guides/API_GUIDE.md#answers-as-arrow-the-accept-header)) |
| Tableau, Power BI, XLCubed | through the REST and Arrow endpoints (Tableau's Web Data Connector, Power BI's web connector); no MDX/XMLA server | not built; decision 6 |

### 4.9 Embed and phone views (tiers)

- **Embedding**: a cube view is a ViewModel screen, so the client renderer draws it in any web page
  ([CLIENT_RENDERER.md](../architecture/CLIENT_RENDERER.md), built 2.9.7): `render(el, {kind: 'cube-view', id: 'asha/morning'})`.
- **Phone tiers** ([MOBILE_DISPLAY.md section 3](../architecture/MOBILE_DISPLAY.md#3-phone-tiers-must-glance-more-desk), design): the
  headline strip and the top-left 5 × 3 of the pivot are **must**; sparklines and the tail histogram **glance**; drill, explain and
  provenance **more**; the full pivot editor **desk** (open on desktop).

### 4.10 Lenses and permissions

The cube reads as the person: entitlements per scope and kind, masks and audit per answer, exactly as the VaR explorer
([VAR_GUIDE section 9](../guides/VAR_GUIDE.md#9-who-sees-what-entitlements-and-audit)). A cell whose members the reader may see only in
aggregate shows the aggregate and refuses drill-through with the reason. A lens hides; it does not secure.

### 4.11 Cluster execution: scatter and gather of vectors, bit-equal results

A cube view is many scopes. On a sharded cluster ([VAR_GUIDE section 28](../guides/VAR_GUIDE.md#28-sharding-across-drishtinet-as-built-296),
built 2.9.6) the coordinator splits each scope's rows by shard; each owner returns **one partial per scope**: its summed vector (for
vector measures) or its summed inputs per factor (for formula measures); the coordinator adds the partials **in shard order** and takes
the statistic once. A pivot request is batched: one call per owner carrying all the view's scopes, not one per cell. Bit-equality is the
existing invariant (partials are always the 1-day vectors, never statistics); for sensitivities the partial is a sparse map factor →
sum, also added in shard order. **No live owner of a needed shard: the cell says so (`DRS-5215`), never a partial figure.**

### 4.12 Caching and memory budgets

- **Cell cache**: keyed by (date, data version, batch list, scope text, measure, lens); invalidated by a batch touching any member
  (the existing VaR caches, `VarCaches`, extended to cube scopes).
- **Vector budget**: a pivot of 40 × 12 cells is about 500 scopes; each scope's 509-value float64 vector is 4 KB, so 2 MB a view; at
  1,518 values (normal + stressed + spare) 6 MB. Views over a configurable cell count (proposed default 5,000) are refused with the
  count, not truncated.
- **Sensitivity budget**: per date a factor dictionary (a few hundred thousand names) and per-position sparse rows in the hot file
  (proposed section `SENS`); about 0.3 to 1.7 GB a day at 2.3M positions (section 7).

### 4.13 MR versus CCR: two cubes on one core

| | MR Cube (this document) | CCR Cube ([CCR_CUBE_LENS.md](CCR_CUBE_LENS.md)) |
|---|---|---|
| Core (sections 4.1 to 4.12) | shared | shared |
| Data grain | position (or trade) × scenario; position × risk factor | trade × path × time step |
| Aggregation order | sum vectors over any members, then the statistic | **first** net per netting set and apply collateral per path (non-linear), **then** sum across netting sets, then the statistic |
| Additive across the pivot | PV, sensitivities, one scenario's P&L | EE, EPE, CE, CVA contributions |
| Non-additive | VaR, ES, SBM charges | PFE quantiles, peaks, EEPE, SA-CCR, SIMM |
| Shard key | `LEGAL_ENTITY`, `DESK` (a desk never split) | `COUNTERPARTY`, `NETTING_SET` (a netting set never split) |
| Shared inputs | **sensitivities** (`position-sensitivity`: FRTB here, SIMM and SA-CVA there) and the **QLRisk pricing bridge** (scenario P&L here, path MtMs there) | the same |

Two cubes, because the **grain and the order of aggregation differ**; one core, because the pivot, totals, provenance, explain,
corrections, streaming, export, embedding, permissions, scatter-gather and caching are the same machinery.

---

## 5. What Drishti has today

| Capability | Status | Evidence |
|---|---|---|
| VaR 99% and ES 97.5%, historical simulation, any scope, re-aggregated from vectors | **Built** | [VAR_GUIDE sections 1, 2](../guides/VAR_GUIDE.md#1-the-question-var-answers) |
| Component, incremental, marginal VaR; diversification; components adding up exactly | **Built** | [VAR_GUIDE section 18.3](../guides/VAR_GUIDE.md#183-decomposition-of-a-scope-into-parts) |
| Any confidence and window; 1-day and 10-day (√10 and the 10-day sum) | **Built** (2.9.7) | [VAR_GUIDE section 18.6](../guides/VAR_GUIDE.md#186-horizons-the-two-10-day-methods-and-the-confidence-chosen-as-built-297) |
| Scopes: hierarchies, positions, trades, rules, baskets, `+ - &` | **Built** | [VAR_GUIDE section 5](../guides/VAR_GUIDE.md#5-scopes-portfolios-and-baskets) |
| Several hierarchies (MGMT, LEGAL, limit letters, configured) | **Built** (2.9.5) | [VAR_GUIDE section 15.14](../guides/VAR_GUIDE.md#1514-hierarchies-from-the-rows-or-from-the-trades-columns-as-built-295) |
| Scenario matrix (a scope's scenarios across dates) | **Built** (2.9.7, `SMAT`) | [VAR_GUIDE section 3.9](../guides/VAR_GUIDE.md#39-the-scenario-matrix-smat-297) |
| Intraday updates from files and Kafka | **Built** (2.9.4) | [VAR_GUIDE section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes-what-if-planned-intraday-updates-as-built-in-294); `VarPvStream.java` |
| What-if: copies of booked trades scaled, in a basket | **Built** | [VAR_GUIDE section 8.5](../guides/VAR_GUIDE.md#85-the-pre-deal-what-if-in-the-basket-tray); `VarWhatIf.java` |
| Pricing a new trade over the scenarios (QLRisk) | **Not built** (parked until QLRisk's API is stable) | [VAR_GUIDE section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes-what-if-planned-intraday-updates-as-built-in-294) |
| Mixed mode: Drishti never prices; it takes the bank's P&L vectors or scenario PVs | **Built** (by design) | [VAR_GUIDE section 15.9](../guides/VAR_GUIDE.md#159-scenario-pv-scenario-pvs-per-trade-in-the-trades-currency-as-built-294) |
| Official records, captures, compare, history, backtest on derived hypothetical P&L | **Built** | [VAR_GUIDE sections 6, 7, 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results) |
| VaR limits per node, limit letters | **Built** (2.9.5) | [VAR_GUIDE section 15.15](../guides/VAR_GUIDE.md#1515-var-limit-the-nodes-var-limits-as-built-295) |
| Sharding across Drishti\*net, equal to the bit | **Built** (2.9.6) | [VAR_GUIDE section 28](../guides/VAR_GUIDE.md#28-sharding-across-drishtinet-as-built-296) |
| Delta and Iceberg inputs and results | **Built** (2.9.5) | [VAR_GUIDE](../guides/VAR_GUIDE.md) |
| Arrow answers (contributions) | **Built** (2.9.7) | [API_GUIDE.md](../guides/API_GUIDE.md#answers-as-arrow-the-accept-header) |
| Pivot over any fields with server totals (BI "Slice", matrix visual) | **Built for BI datasets**, additive measures only | [BI_GUIDE.md](../guides/BI_GUIDE.md) |
| Pivot with non-additive VaR totals over the engine | **Not built** | MC2 |
| Stressed VaR (a stress scenario set, 1-day and 10-day, `SVAR`) | **Planned** (2.9.7 row 2c, Drill B); the what-if already reports a "stressed window" figure | [VAR_GUIDE section 8.2](../guides/VAR_GUIDE.md#82-var-whatif-the-python-engine) |
| Sensitivities per position and factor | **Not built**: book-grain `frtb-sensitivity` only, demo data; `DV01` per trade on `trade-valuation` | [OVERSIGHT_LENS.md section 7.1](OVERSIGHT_LENS.md#71-what-the-lake-holds-today) |
| FRTB SA computed | **Not built** (stored book-grain charges, retiring) | [COMPUTED_ATTRIBUTES.md section 4.9](../architecture/COMPUTED_ATTRIBUTES.md#49-frtb-sensitivity-frtb); [FRTB.md](../contracts/FRTB.md) |
| FRTB IMA | **Not built**, not planned | MC8 to MC10 |
| Stress P&L by desk and scenario; the bank's P&L per scenario | **Built** stored and derived (2.9.7) | [VAR_GUIDE section 21.4](../guides/VAR_GUIDE.md#214-the-banks-stress-pl-by-scenario-derived-297) |
| Actual and hypothetical P&L feed; backtesting engine with the multiplier | **Planned** (2.9.8 rows 3, 4) | [REGULATORY_VAR_REPORTING.md section 8](../architecture/REGULATORY_VAR_REPORTING.md#8-a-proposed-roadmap) |
| VaR change explanation | **Not built**, designed (RO1) | [OVERSIGHT_LENS.md section 4.1](OVERSIGHT_LENS.md#41-ro1-var-change-explanation) |
| Write-back with approval and audit | **Built** (2.8.0), on entity forms; not on engine inputs | [WRITE_BACK.md](../guides/WRITE_BACK.md) |
| Client renderer, embedding | **Built** (2.9.7) | [CLIENT_RENDERER.md](../architecture/CLIENT_RENDERER.md) |
| Phone tiers | **Design** (2.9.7 candidate) | [MOBILE_DISPLAY.md](../architecture/MOBILE_DISPLAY.md) |

**Verified in code (2026-10-31):** `drishti-server/src/main/java/com/ash/drishti/server/var/` holds `VarService`, `VarWhatIf`,
`VarPvStream` (the Kafka window), `VarShardBooks` and `VarSharding` (shards), `VarMatrix` (SMAT), `VarOfficial`, `VarCaches`; no class
in `drishti-server` or `drishti-compute` reads sensitivities by risk factor (the only matches for "sensitivit" are outside those
packages), and there is no FRTB or IMA calculator.

---

## 6. Screens and visualisation

The cube's screens follow the house rules: keyboard first, every number printed (a chart never replaces a figure), losses in the
theme's loss colour **and** with a sign or marker (never colour alone), dark terminal theme by default, every panel a ViewModel panel so
it embeds and stacks on a phone. Mock-ups in `~/Documents/drishti-work/mockups/` (not in the repository): `mr-cube-A.html`,
`mr-cube-B.html`, `mr-cube-C.html`.

### 6.1 Three layouts (mock-ups, the product owner picks)

| Option | Layout | Best for | Mock-up |
|---|---|---|---|
| **A: Pivot first** | the pivot grid fills the screen (rows MGMT desk, columns risk class), each cell its VaR, a 20-day sparkline and a limit bar; a right rail with the selected cell's tail histogram, explain and provenance | the risk manager's morning; the Oversight lens | `mr-cube-A.html` |
| **B: Tail and attribution** | a narrower pivot on the left; on the right the VaR attribution waterfall (components by the column dimension), the scenario P&L histogram with V and E markers, and the factor heat map below | "what drives it"; the regulatory capital team | `mr-cube-B.html` |
| **C: Sensitivities and what-if** | the sensitivity ladder (DV01 currency × tenor) with two-sided limit bars; a what-if side by side (before, after, incremental per cell); the time-travel slider across the top | traders, pre-deal, intraday | `mr-cube-C.html` |

**Recommendation:** A as the default landing, B and C as tabs of the same view (one data request; switching re-uses the cell answers).

### 6.2 The visual catalogue

| Visual | What it shows | Interactions | Built today? |
|---|---|---|---|
| **Pivot grid with in-cell sparklines and limit bars** | each cell: the measure, a 20-business-day sparkline of the same cell (from official captures, intraday points dotted), a horizontal limit bar (use %, amber from the limit's warning level, red over 100%, a marker at the warning level); totals rows and columns with diversification | click a cell: select and fill the right rail; double-click: drill; hover: provenance popover; drag a dimension chip between Rows, Columns and Filters; shift-click: multi-select cells to compare | pivot: BI only (additive); sparklines and limit bars: not built |
| **VaR attribution waterfall** | a scope's VaR split into the column dimension's components (Euler, adding up exactly), sorted by size, hedges as negative bars, the total as the last bar | click a bar: drill into that part; toggle component VaR / component ES / incremental | components built; the waterfall not built |
| **Scenario P&L histogram with tail markers** | the scope's 509 scenario P&Ls in bins, the VaR scenario marked **V**, the ES tail shaded and marked **E**, the mean and today's P&L; a second series for the comparison date | hover a bin: its scenario days; click: open those days in SMAT; **drag a scenario** marker onto another scope's histogram to compare that day | the P&L path chart built; histogram not built |
| **Factor heat map** | sensitivities (or component VaR by factor group) on two factor dimensions: currency × tenor, issuer × tenor, underlying × expiry; colour by signed value, every cell printed | click: drill to positions; hover: the factor's move yesterday and its VaR-scenario move | not built |
| **Sensitivity ladder by tenor** | per currency (or curve) DV01 by tenor bucket as two-sided bars against the bucket's long and short limits; net and gross; the bucket grid chosen (FRTB, the bank's limit grid) | click a bucket: its positions; **brush** a tenor range to sum it; switch grid | not built |
| **What-if side by side** | for the same view: before, after and incremental per cell; the hypothetical trades in a tray with their standalone and incremental VaR; changed cells outlined | add a trade (copy and scale a booked one, or price new terms), remove one, toggle "show only changed" | the basket what-if built (one scope); per-cell side by side not built |
| **Time-travel slider over data versions** | a slider across the top: business dates and, within a date, each batch and correction (lake data versions); the view redraws as of that version; a second handle sets the comparison | drag the handle; **brush** a range for the sparkline window; keyboard `[` and `]` step a version | history of official captures built; per-version slider not built |
| **Provenance popover per cell** | data versions, batches, scenario set and window, method and lens, members, proxied share, refusals, shards, time; links to SMAT, the inputs and the corrections | hover (desktop), long-press (phone), `P` on the selected cell | VaR headline provenance built; per cell not built |
| **"Explain the move" panel** | RO1's waterfall of exact steps between two versions (restatement, hierarchy, trades out, window roll, market, trades in), each with its top 5 contributors; the factor overlay below with its residual | click a step: highlight the cells it moved; click a contributor: open it | designed (RO1); not built |

### 6.3 Response-time targets

| Interaction | Target (p95, warm, one date open) | Note |
|---|---|---|
| Open a saved view of 40 × 12 cells with totals (about 500 scopes) | 400 ms¹ | one batched planner request per owner |
| Re-pivot (drag a dimension) | 400 ms¹ | the same |
| Select a cell: histogram, components, provenance | 60 ms¹ | the cell's vector is already summed |
| Drill one level | 150 ms¹ | |
| Hover provenance | 20 ms¹ | the provenance id is in the answer; details cached |
| An intraday batch: visible cells patched | 1 s after the batch is applied¹ | coalesced per batch |
| What-if with three copied trades, per cell | 800 ms¹ | the Python engine; pricing a new trade adds QLRisk's time |
| Time-travel to another version of an open date | 1.5 s¹ | the version's batch rows replayed onto the date |
| SBM capital of the bank, three correlation scenarios | 2 s¹ | sums per factor are additive and cached per node |

¹ Estimates, not measurements: scaled from the VaR engine's measured node answers (under 1 ms from aggregates, VAR_GUIDE section 27)
and ad-hoc scope sums (VAR_GUIDE section 27.3), times the number of scopes in a view. To be measured with MC2 on the 10K test data and
on the product owner's server at 2.3M.

### 6.4 Keyboard first

`MCUBE <view> <GO>` opens a view; arrows move the selection; `Enter` drills; `Backspace` goes back; `R`, `C`, `F` move the focused
dimension chip to Rows, Columns, Filters; `M` picks measures; `H` toggles 1-day / 10-day; `[` `]` step data versions; `E` opens explain;
`P` provenance; `W` the what-if tray; `S` save; `X` export; `?` help. The command line accepts the scope grammar: `MCUBE DESK:RATES BY
BOOK,CCY MEASURE VAR,ES,DV01 <GO>`.

### 6.5 Phone tiers

**must**: the headline strip (VaR, ES, limit use) and the pivot's first five rows by the first measure, totals pinned; **glance**: the
sparkline column and the tail histogram as a strip; **more**: explain, provenance, the waterfall; **desk**: the pivot editor, the factor
heat map wider than 640 px, what-if. A phone user opens a saved view, never designs one.

### 6.6 Embedding

A saved view embeds with one call of the client renderer; the host page receives `drishti:navigate` events on drill and may keep the
drill in place. A dashboard tile (a cell with its sparkline and limit bar) is a ViewModel panel too.

---

## 7. Data and contracts

**This is the heart of the cube.** Every contract below follows the repository's conventions ([docs/contracts/README.md](../contracts/README.md)):
one contract YAML per kind in `config/packs/<pack>/contracts/<kind>.contract.yaml` with `kind`, `mnemonic`, `class` (`fundamental`,
`input`, `drishti-owned`, `computed`), `grain`, `key`, `dated-by`, `cadence`, `storage` (Delta or Iceberg table, `vectors`) and `fields`
(name, type, presence, unit, description, example); data delivered as **JSON Lines, gzipped**, in `data/testdata/<domain>/<kind>/*.jsonl.gz`
for the demo, loaded by `lake build` or the parallel loader into Delta (or Iceberg), partitioned by `business_date`; trade fields in
Endur-style UPPER_SNAKE names. Sizes are at **2.3M positions** (about 10M trades, the reference bank of
[CLUSTER_DEPLOYMENT.md](../architecture/CLUSTER_DEPLOYMENT.md)), estimates footnoted.

### 7.1 The contract map

| # | Contract (kind) | Class | State | Read by the MR Cube for | Section |
|---|---|---|---|---|---|
| D1 | `trade` (TRD) | fundamental | existing | trade attributes as dimensions; FRTB classification inputs | 7.2 |
| D2 | `position` (POS) | drishti-owned | existing | the position grain; market value, notional | 7.3 |
| D3 | `pnl-vector` (PNLV), `scenario-pv` (SPV), `fx-scenario` (FXS) | input | existing; **extended** | scenario P&L; the vector measures | 7.4 |
| D4 | `scenario-set` (SSET) | input | existing; **extended** | scenario days, stress set flag | 7.5 |
| D5 | `risk-hierarchy` (HIER), `var-limit` (VLIM) | input | existing | dimensions; VaR limits | 7.6 |
| D6 | `risk-limit` | fundamental | **new** (STR.md proposal, RO3) | sensitivity, stress, ES, capital limits | 7.7 |
| D7 | market data (`ir-curve`, `credit-curve`, `fx-spot`, vol surfaces …) | fundamental | existing | factor moves for explain; QLRisk pricing | 7.8 |
| D8 | `position-sensitivity` (SENS) | input | **new** | sensitivities, ladders, SBM, explain overlay, SIMM (CCR) | 7.9 |
| D9 | `risk-factor` (the factor dictionary) and `sensitivity-buckets.yaml` | fundamental + configuration | **new** | factor parts, liquidity horizons, modellability, bucket grids | 7.10 |
| D10 | `ima-pnl-vector` | input | **new** | ES by liquidity horizon, reduced set, stressed calibration, by risk class | 7.11 |
| D11 | `desk-pnl` (actual, hypothetical, risk-theoretical) | input | **new** (2.9.8 row 3) | backtesting, PLA | 7.12 |
| D12 | `stress-scenario` (SCN) and `scenario-pnl` | fundamental, input | existing; **extended**; `scenario-pnl` **new** (STR.md) | stress P&L | 7.13 |
| D13 | `default-risk` and `residual-risk` | input | **new** (FRTB.md proposals) | DRC and RRAO | 7.14 |
| D14 | `cube-correction` | drishti-owned | **new** | data corrections inside the cube | 7.15 |
| D15 | `predeal-request` and `predeal-result` | drishti-owned, computed | **new** | pre-deal what-if | 7.16 |

### 7.2 D1 `trade` (TRD): existing, read as dimensions

**Grain:** one row per trade per business date. **Key:** `TRADE_ID`. **Storage:** Delta, `<root>/trading/trade_<system>/`, partitioned by
business date (`config/packs/trading/contracts/trade.contract.yaml`, version 1.1). **Sources:** Endur, Murex, Calypso (the demo's three
booking systems). **Frequency:** daily snapshot, intraday amendments. **Size at 2.3M positions:** about 10M trades, about 3 KB a trade
as JSON with legs and events (30 GB a day of JSON, about 3 GB in Parquet)².

| Field (existing) | Type | Req. | Meaning, as the cube uses it |
|---|---|---|---|
| `TRADE_ID`, `SOURCE_SYSTEM`, `TRANSACTION_ID`, `TRADE_VERSION` | id, text | required | the member's identity; the version the vector priced |
| `TRADE_STATUS`, `TRADE_ACTION` | text | required, optional | the one live rule (status and maturity, COMPUTED_ATTRIBUTES section 10, confirmed 2026-10-31) |
| `PRODUCT_TYPE`, `PRODUCT_SUB_TYPE`, `PRODUCT_GROUP`, `PRODUCT_SUB_GROUP` | text | required/optional | dimensions; FRTB risk class defaults; RRAO's exotic flag (proposed below) |
| `LEGAL_ENTITY`, `PORTFOLIO`, `TT_GROUP`, `TT_BOOK`, `TRADER_ID` | id | required/optional | hierarchy placement |
| `COUNTERPARTY`, `COUNTERPARTY_COUNTRY_OF_RISK` | id, text | required | dimensions; the CCR cube's link |
| `CURRENCY`, `QUANTITY`, `PRICE`, `BUY_SELL_CODE`, `OPTION_TYPE`, `STRIKE_PRICE` | text, amount | required/optional | what-if terms; pre-deal pricing |
| `TRADE_DATE`, `START_DATE`, `MATURITY_DATE` | date | required | live rule; DRC maturity scaling |
| `LEGS[]`, `EVENTS[]` | record list | required | QLRisk pricing of an amended trade |

**What the MR Cube adds (extended, optional fields, proposed):** `FRTB_DESK` (the regulatory trading desk, for IMA approval and PLA:
often not the management desk), `RRAO_CLASS` (`EXOTIC`, `OTHER_RESIDUAL`, empty), `IMA_ELIGIBLE` (boolean, the desk's model approval
applies), `HEDGE_DESIGNATION` (for what-if hedge suggestions). **Validation:** an unknown `FRTB_DESK` is a warning per source; a
`RRAO_CLASS` outside the list is refused.

### 7.3 D2 `position` (POS): existing, derived by Drishti

**Grain:** one position per business date, derived when the date's vectors load (never stored), `POS-<TT_BOOK>~<security or trade>`
(`position.contract.yaml`). Fields read: `POSITION_ID`, `POSITION_RULE`, `INSTRUMENT_ID`, `QUANTITY`, `GROSS_QUANTITY`, `TRADE_IDS`,
`LEGAL_ENTITY`, `DIVISION`, `DESK`, `TT_BOOK`, `PRODUCT_GROUP`, `CURRENCY`, `MARKET_VALUE` (USD), `NOTIONAL`, `TAGS`, `STATUS`. **The cube
adds nothing to the contract**; it reads `MARKET_VALUE` for marginal VaR and as an additive measure. **Size:** 2.3M rows, about 0.8 KB a
position skeleton (CLUSTER_DEPLOYMENT section 6.7).

### 7.4 D3 `pnl-vector`, `scenario-pv`, `fx-scenario`: existing, extended

**Grain:** one row per trade (`GRAIN TRADE`) or position (`GRAIN POSITION`), business date and scenario set. **Key:** `VECTOR_ID` =
`PNL-<TRADE_ID or POSITION_ID>-<SCENARIO_SET_ID>` (`SPV-…` for scenario PVs). **Storage:** Delta or Iceberg, `<root>/risk/pnl-vector/`,
partitioned by `business_date`, sorted by book, vector column `PNL` (`SCENARIO_PV` for scenario PVs). **Sources:** the bank's risk engine
(Murex MLC, Calypso risk, Endur's risk server, or an in-house pricing grid) on the same run as the trades. **Frequency:** a full run
after the close (06:00 in the demo), intraday batches (`RUN_KIND INTRADAY`) from files or Kafka. **Size:** 2.3M × 509 × 8 bytes of
JSON-decimal text ≈ 20 GB of JSON a day; float32 in the hot file 4.7 GB; at 1,518 values (normal 509, stressed 250, spare) 14 GB in the
hot tier across shards (27 shards of 0.52 GB at `target-shard-mb: 512`, CLUSTER_DEPLOYMENT decision PO-C2).

Existing fields (`pnl-vector.contract.yaml` version 1.1, every one read): `VALUE_TYPE`, `GRAIN`, `TRADE_ID`, `POSITION_ID`, `CURRENCY`,
`SOURCE_SYSTEM`, `VECTOR_ID`, `TRADE_CURRENCY`, `SECURITY_ID`, `INSTRUMENT`, `BUSINESS_DATE`, `SCENARIO_SET_ID`, `TRADE_VERSIONS`,
`TRADE_STATUS`, `MATURITY_DATE`, `LEGAL_ENTITY`, `DIVISION`, `DESK`, `TT_BOOK`, `LIMIT_LETTER`, `PORTFOLIO`, `STRATEGY`, `PRODUCT_GROUP`,
`PRODUCT_TYPE`, `COUNTERPARTY`, `DIRECTION`, `QUANTITY`, `TAGS`, `PV` (USD), `PNL` (vector, USD, gain positive, oldest scenario first),
`STATUS` (`ok`, `proxied`, `failed`), `PRICER`, `MODEL_VERSION`, `COMPUTED_AT`, `BATCH_ID`, `RESTATEMENT`, `RUN_KIND`, `TRADE_IDS`.
Validation as built ([VAR_GUIDE section 20.3](../guides/VAR_GUIDE.md#203-what-the-load-checks-as-built)): wrong vector length,
NaN, a failed row counted never zero, one full run per date and source, one form and grain per source and date.

**Extended (proposed, optional):**

| New field | Type | Unit | Req. | Meaning |
|---|---|---|---|---|
| `RISK_CLASS_VECTORS` | record list | USD | optional | the same vector split by FRTB risk class (`RISK_CLASS`, `PNL`), when the source runs partial revaluations; enables "VaR by risk type" (2.9.9 G6) without a second table; the split need not add up to `PNL` (cross effects), the residual is reported |
| `FRTB_DESK` | id | | optional | the regulatory desk (as on the trade) |
| `PROXY_REASON` | text | | optional | why `STATUS` is `proxied` (`NO_HISTORY`, `PROXY_CURVE`, `STALE_DATA`); counted per reason on the data-trust card |

```json
{"VECTOR_ID":"PNL-MX-20000274-HS-509","VALUE_TYPE":"PNL","GRAIN":"TRADE","TRADE_ID":"MX-20000274","SOURCE_SYSTEM":"MUREX",
 "BUSINESS_DATE":"2026-09-30","SCENARIO_SET_ID":"HS-509","CURRENCY":"USD","LEGAL_ENTITY":"LE-LDN","DIVISION":"DIV-MARKETS",
 "DESK":"DESK-EQD","TT_BOOK":"BOOK-EQD-2","PORTFOLIO":"BOOK-EQD-2","PRODUCT_GROUP":"Equity","PRODUCT_TYPE":"WARRANT",
 "DIRECTION":"SHORT","QUANTITY":-179000000.0,"TRADE_STATUS":"AMENDED","MATURITY_DATE":"2027-05-26","PV":3143714,
 "PNL":[-1204.5,882.1,"… 509 values …"],"STATUS":"ok","PRICER":"demo-factor-model","MODEL_VERSION":"demo-2",
 "COMPUTED_AT":"2026-09-30T06:00:00Z","BATCH_ID":"B-2026-09-30-01","RESTATEMENT":0,"RUN_KIND":"FULL",
 "RISK_CLASS_VECTORS":[{"RISK_CLASS":"EQ","PNL":["… 509 values …"]},{"RISK_CLASS":"FX","PNL":["… 509 values …"]}]}
```

`fx-scenario` (FXS, existing, unchanged): one row per currency, date and set, `RATE` and the vector `RATES` (USD per one unit), so
scenario PVs in any currency turn into USD P&L.

### 7.5 D4 `scenario-set` (SSET): existing, extended

**Grain:** one row per scenario set per business date. **Key:** `SCENARIO_SET_ID`. Existing fields: `SCENARIO_DATES` (oldest first),
`SCENARIO_COUNT`, `WINDOW_START`, `WINDOW_END`, `HORIZON_DAYS`, `REVALUATION` (`full` or `sensitivities`), `WEIGHTS`, `SOURCE_BATCH`,
`DESCRIPTION`. **Extended (proposed):** `PURPOSE` (`NORMAL`, `STRESSED`, `IMA_CURRENT`, `IMA_STRESSED`, `STRESS_SELECTION`), `APPROVED_BY`
and `APPROVAL_ID` (the stress window's sign-off, 2.9.8 row 8), `REDUCED_FACTOR_SET` (boolean: the vectors priced with the reduced set of
MAR33's stressed calibration). Stressed VaR (2.9.7 row 2c) introduces a second set per date (`HS-STRESS-…`); the cube treats every set
as a **lens** (Normal / Stressed beside the confidence picker). **Validation:** one `NORMAL` set per date; a `STRESSED` set's window
inside the approved stress period.

### 7.6 D5 `risk-hierarchy` and `var-limit`: existing

`risk-hierarchy` (HIER): one row per node per date, key `NODE_ID` (`LEGAL~LE-NY`), fields `HIERARCHY`, `LEVEL`, `PARENT_ID`, `NAME`,
`REF`, `CHILDREN`, `BOOKS`, `BUSINESS_DATE`, `VALID_FROM`, `VALID_TO`; or built from the vectors' columns (2.9.5). `var-limit` (VLIM): key
`LIMIT_ID` = `VL-<NODE_ID>`, `HIERARCHY`, `NODE_ID`, `LIMIT_VAR` (USD), `CURRENCY`, `HORIZON` (`1D`, `10D`), `CONFIDENCE`, `BUSINESS_DATE`,
`VALID_FROM`, `VALID_TO`. **The cube adds nothing**; it draws the limit bar of a VaR cell from `var-limit` when the cell is exactly a
node and the lens matches the limit's horizon and confidence (otherwise no bar, said in the provenance: "limit not comparable").

### 7.7 D6 `risk-limit`: new (one limit table for every measure)

Proposed in [STR.md section 3.5](../contracts/STR.md#35-risk-limit-limits-by-measure-and-scope-fundamental) and RO3; the cube adopts and
extends it. **Grain:** one row per limit per business date. **Key:** `LIMIT_ID`. **Storage:** Delta, `<root>/risk/risk-limit/`.
**Sources:** the bank's limit system (often in-house; Murex and Calypso limit modules). **Frequency:** daily with the morning inputs,
and on change. **Size:** 10,000 to 100,000 limits, under 50 MB a day.

| Field | Type | Unit | Req. | Meaning |
|---|---|---|---|---|
| `LIMIT_ID` | id | | required | `LIM-<measure>-<scope>-<bucket>` |
| `MEASURE` | text | | required | `VAR`, `SVAR`, `ES`, `STR`, `DV01`, `CS01`, `VEGA`, `FX_DELTA`, `EQ_DELTA`, `CM_DELTA`, `NOTIONAL`, `STOP_LOSS`, `FRTB_SA`, `IMA_ES` |
| `SCOPE` | text | | required | a scope text (`DESK:DESK-RATES`, `LL~LL-0003/DESK-RATES`) |
| `GRID`, `BUCKET` | text | | optional | the bucket grid of `sensitivity-buckets.yaml` and the bucket (`BANK-IR-TENOR`, `5-10Y`) |
| `HORIZON`, `CONFIDENCE`, `SCENARIO_ID` | text, rate, id | | optional | the lens the limit is set for; a stress limit's scenario (empty: the worst) |
| `LIMIT_LONG`, `LIMIT_SHORT` | amount | `CURRENCY` | required (one of) | two sides for sensitivities; a VaR limit uses `LIMIT_LONG` only |
| `CURRENCY` | text | | required | ISO 4217 |
| `WARNING_AT` | rate | fraction | optional | early warning (site default 0.8, OVERSIGHT decision 8) |
| `OWNER`, `ESCALATION_CHAIN`, `APPROVED_BY`, `LIMIT_LETTER` | text, list | | optional | governance |
| `TEMPORARY`, `VALID_FROM`, `VALID_TO`, `BUSINESS_DATE` | boolean, date | | required/optional | temporary increases have an end |

```json
{"LIMIT_ID":"LIM-DV01-DESK-RATES-USD-5-10Y","MEASURE":"DV01","SCOPE":"DESK:DESK-RATES","GRID":"BANK-IR-TENOR","BUCKET":"USD/5-10Y",
 "LIMIT_LONG":2500000,"LIMIT_SHORT":2500000,"CURRENCY":"USD","WARNING_AT":0.8,"OWNER":"rates-risk","APPROVED_BY":"Market Risk Committee",
 "LIMIT_LETTER":"LL-0003","TEMPORARY":false,"VALID_FROM":"2026-01-02","BUSINESS_DATE":"2026-09-30"}
```

**Validation:** a scope that does not parse is refused; a `GRID` or `BUCKET` not in the configuration is refused; overlapping limits on
the same measure, scope and bucket refused unless one is `TEMPORARY`; `var-limit` rows are read as `MEASURE VAR` (OVERSIGHT decision 5).

### 7.8 D7 Market data: existing, read for explain and pricing

The market-data pack's contracts (`config/packs/market-data/contracts/`): `ir-curve` (key `CURVE_ID`, `CURRENCY`, `CURVE_TYPE`, `INDEX`,
`POINTS[]` 1M to 30Y, `FORWARDS[]`, `INTERPOLATION`, `DAY_COUNT`, `CALENDAR`, `AS_OF`, `BUILT_AT`, `SOURCES`), `credit-curve` (`CURVE_ID`,
`ISSUER`, `POINTS[]` 6M to 10Y, `SPREAD_5Y` bp, `RECOVERY`, `PD_1Y`, `SENIORITY`, `DOC_CLAUSE`, `CURRENCY`), `fx-spot`, `fx-forward-curve`,
`inflation-curve`, `equity`, `equity-index`, `dividend-curve`, `commodity-curve`, `repo-curve`, and the vol surfaces (`ir-vol-cube`,
`cap-vol-surface`, `fx-vol-surface`, `equity-vol-surface`, `commodity-vol-surface`). **Sources:** the bank's market-data system
(Bloomberg, Refinitiv, Markit through an in-house curve builder). **Frequency:** end of day, intraday snapshots on demand. **What the
cube adds:** nothing to the contracts; a **factor mapping** from curve points to the factor dictionary (`IR/CURVE/USD/SOFR/10Y` ↔
`CRV-USD-SOFR` point `10Y`), so the explain overlay reads each factor's move T−1 → T, and QLRisk reads the same curves for pre-deal.

### 7.9 D8 `position-sensitivity` (SENS): new

Adopted from [OVERSIGHT_LENS.md section 7.3](OVERSIGHT_LENS.md#73-a-proposed-data-contract-position-sensitivity) (decision 9 there:
a new kind that **becomes** FRTB.md's per-trade `sensitivity` input, so FRTB SA, SIMM, P&L explain, sensitivity stress and the cube read
one table). The cube adds the FRTB and SIMM bucket fields per element, the curvature inputs and the regulatory desk.

**Grain:** one record per position (or trade) per business date and batch. **Key:** `SENSITIVITY_ID` =
`SENS-<TRADE_ID or POSITION_ID>-<BATCH_ID>`. **Storage:** Delta or Iceberg, `<root>/risk/position-sensitivity/`, partitioned by
`business_date`, sorted by `TT_BOOK` then id (as the vectors); the list column `SENSITIVITIES` as a list of structs. **Test data:**
`data/testdata/market-risk/position-sensitivity/*.jsonl.gz`, generated consistent with the demo's scenario PVs. **Sources:** the same
system and run that produce the scenario PVs (Murex, Calypso, Endur risk, the bank's grid). **Frequency:** full run after the close;
intraday batches replace a position's record whole. **Size:** 23 to 115 million elements a day (10 to 50 non-zero factors a position);
0.4 to 1.5 GB gzipped JSON; 0.3 to 1.7 GB of Parquet a day; 0.5 to 3 TB over seven years³.

| Field (record) | Type | Unit | Req. | Meaning |
|---|---|---|---|---|
| `SENSITIVITY_ID` | id | | required | the key |
| `GRAIN` | text | | required | `TRADE` or `POSITION` |
| `TRADE_ID`, `POSITION_ID`, `TRADE_IDS` | id, id list | | one required | as `scenario-pv` |
| `SOURCE_SYSTEM`, `BUSINESS_DATE`, `BATCH_ID`, `RUN_KIND`, `COMPUTED_AT`, `RESTATEMENT` | text, date, id, text, timestamp, integer | | required | the run, as the vectors |
| `STATUS`, `PROXY_REASON` | text | | required, optional | `ok`, `proxied`, `failed` |
| `MODEL_VERSION`, `PRICER` | text | | optional | audit |
| `LEGAL_ENTITY`, `DIVISION`, `DESK`, `TT_BOOK`, `LIMIT_LETTER`, `FRTB_DESK` | id | | required (`FRTB_DESK` optional) | placement, as the vectors |
| `PRODUCT_GROUP`, `PRODUCT_TYPE`, `TRADE_STATUS`, `MATURITY_DATE`, `CURRENCY` | text, date | | required | the live rule; dimensions |
| `SENSITIVITIES` | record list | | required (empty only when `TRADE_STATUS` is `CANCELLED`) | the sparse list below |

| Field (element of `SENSITIVITIES[]`) | Type | Unit | Req. | Meaning |
|---|---|---|---|---|
| `RISK_FACTOR` | text | | required | the standard name (section 7.10) |
| `MEASURE` | text | | required | `DV01`, `CS01`, `IE01`, `DELTA`, `BETA`, `VEGA`, `GAMMA`, `CURVATURE_UP`, `CURVATURE_DOWN`, `BASE_CORR` |
| `VALUE` | amount | `CURRENCY` of the element | required | P&L for the measure's standard move: +1 bp (DV01, CS01, IE01), +1% of spot (DELTA), +1 vol point (VEGA); curvature: V(shocked) − V(base) for the regulatory shock |
| `CURRENCY` | text | | required | converted to the reporting currency at today's FX when summed |
| `RISK_CLASS`, `FACTOR_TYPE`, `QUALIFIER`, `CURVE`, `TENOR`, `UNDERLYING_TENOR`, `STRIKE` | text | | optional (derived from `RISK_FACTOR`) | the parts |
| `SHOCK_SIZE` | rate | bp or fraction | required on curvature | the shock applied (checked against MAR21) |
| `IMPLIED_VOL` | rate | fraction or bp | optional | for vega risk (VR = vega × σ) and absolute vol shocks |
| `FRTB_BUCKET`, `SIMM_BUCKET` | text | | optional | the source's bucket; Drishti's mapping wins when they disagree (a warning) |

```json
{"SENSITIVITY_ID":"SENS-POS-BOOK-RATES-1~MX-30000951-B-SENS-2026-09-30-01","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-RATES-1~MX-30000951",
 "SOURCE_SYSTEM":"MUREX","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-SENS-2026-09-30-01","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:10:00Z",
 "RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-RATES","TT_BOOK":"BOOK-RATES-1",
 "FRTB_DESK":"FD-USD-RATES","PRODUCT_GROUP":"Rates","PRODUCT_TYPE":"IRS","TRADE_STATUS":"VALIDATED","MATURITY_DATE":"2036-09-30","CURRENCY":"USD",
 "SENSITIVITIES":[
  {"RISK_FACTOR":"IR/CURVE/USD/SOFR/5Y","MEASURE":"DV01","VALUE":-820.0,"CURRENCY":"USD"},
  {"RISK_FACTOR":"IR/CURVE/USD/SOFR/10Y","MEASURE":"DV01","VALUE":-1250.5,"CURRENCY":"USD"},
  {"RISK_FACTOR":"VOL/IR/USD/SOFR/1Y/10Y","MEASURE":"VEGA","VALUE":310.0,"CURRENCY":"USD","IMPLIED_VOL":0.0095},
  {"RISK_FACTOR":"IR/CURVE/USD/SOFR","MEASURE":"CURVATURE_UP","VALUE":-41200.0,"CURRENCY":"USD","SHOCK_SIZE":170}]}
```

**Validation and refusals** (each counted per source, first reasons listed): a factor name that does not parse or is not mapped (element
rejected); `CURRENCY` without FX on the date (element rejected); `VALUE` null, NaN or infinite (element rejected; a failed factor is never
zero); the same factor and measure twice in a record (record rejected); one full run per date and source; curvature without
`SHOCK_SIZE`, or a shock outside MAR21's (warning); a position with vectors and no sensitivities, or the reverse (a completeness warning
by desk, never an error); `proxied` counted and its share shown beside every sum; a factor outside every bucket of a grid a limit uses
(warning). **Consistency check (new, proposed):** for a sample of positions, the first-order P&L Σ sensitivity × factor move of each
scenario against the vector's P&L (a correlation per desk on the data-trust card): it catches vectors and sensitivities from different
runs.

### 7.10 D9 The factor dictionary (`risk-factor`) and the bucket mapping (`sensitivity-buckets.yaml`): new

**The factor naming standard** (OVERSIGHT decision 10): one text name per factor, slash-separated: `IR/CURVE/<ccy>/<curve>/<tenor>`,
`INF/<index>/<tenor>`, `CR/SPREAD/<issuer, LEI preferred>/<BOND or CDS>/<tenor>`, `FX/SPOT/<ccy>`, `EQ/SPOT/<ISIN or index>`,
`EQ/REPO/<ISIN>`, `CM/PRICE/<underlying>/<location>/<tenor>`, `VOL/<class>/<qualifier>/<curve or underlying>/<expiry>[/<underlying
tenor>][/<strike>]`.

**`risk-factor` (new kind, fundamental):** the dictionary of every factor the bank uses, with the regulatory attributes IMA needs.
**Grain:** one row per factor per business date (changes only, as a slowly changing table). **Key:** `RISK_FACTOR`. **Storage:**
`<root>/risk/risk-factor/`. **Sources:** the bank's risk-factor master (often in the market-data system or the FRTB engine). **Size:** a
few hundred thousand rows, under 100 MB.

| Field | Type | Unit | Req. | Meaning |
|---|---|---|---|---|
| `RISK_FACTOR` | id | | required | the standard name |
| `RISK_CLASS`, `FACTOR_TYPE`, `QUALIFIER`, `CURVE`, `TENOR`, `UNDERLYING_TENOR`, `STRIKE` | text | | required/optional | the parts |
| `FRTB_BUCKET`, `SIMM_BUCKET` | text | | optional | defaults; the bucket file decides |
| `LIQUIDITY_HORIZON` | integer | days | required for IMA | 10, 20, 40, 60 or 120 (MAR33.12) |
| `MODELLABLE` | boolean | | required for IMA | passes the risk factor eligibility test (MAR31) on the date |
| `RFET_OBSERVATIONS_12M`, `RFET_MAX_GAP_DAYS` | integer | count, days | optional | the eligibility evidence (24 real prices a year and no 90-day window with fewer than 4, MAR31) |
| `IN_REDUCED_SET` | boolean | | optional | in the reduced set used for the stressed calibration (MAR33) |
| `PROXY_OF` | id | | optional | the factor this one is proxied by, when proxied |
| `MARKET_DATA_REF` | text | | optional | where the factor's level comes from (`CRV-USD-SOFR#10Y`), for explain and QLRisk |
| `VALID_FROM`, `VALID_TO`, `BUSINESS_DATE` | date | | required/optional | |

```json
{"RISK_FACTOR":"IR/CURVE/USD/SOFR/10Y","RISK_CLASS":"GIRR","FACTOR_TYPE":"CURVE","QUALIFIER":"USD","CURVE":"SOFR","TENOR":"10Y",
 "FRTB_BUCKET":"USD","SIMM_BUCKET":"1","LIQUIDITY_HORIZON":10,"MODELLABLE":true,"RFET_OBSERVATIONS_12M":251,"RFET_MAX_GAP_DAYS":3,
 "IN_REDUCED_SET":true,"MARKET_DATA_REF":"CRV-USD-SOFR#10Y","VALID_FROM":"2026-01-02","BUSINESS_DATE":"2026-09-30"}
```

**`sensitivity-buckets.yaml` (configuration, in the market-risk pack):** named **grids**, each mapping factors to buckets by rules on the
parts: the FRTB grids (MAR21's bucket tables, with risk weights and correlations as parameters), the SIMM grid (licensed parameters: see
section 13) and the bank's own limit grids. Each limit names its grid; a factor falls in exactly one bucket of each grid (checked at
start, overlaps refused). A **bank's factor mapping** (its names → the standard) is configuration too; an unmapped name is refused,
never guessed.

```yaml
# config/packs/market-risk/sensitivity-buckets.yaml (proposed, illustrative)
grids:
  BANK-IR-TENOR:
    applies-to: {RISK_CLASS: GIRR, MEASURE: DV01}
    by: [QUALIFIER, TENOR]
    tenor-bands: {0-2Y: [2W, 1M, 3M, 6M, 1Y, 2Y], 2-5Y: [3Y, 5Y], 5-10Y: [10Y], 10Y+: [15Y, 20Y, 30Y]}
  FRTB-GIRR:
    applies-to: {RISK_CLASS: GIRR}
    bucket: QUALIFIER            # MAR21.39: one bucket per currency
    parameters: frtb/girr.yaml   # risk weights MAR21.42, correlations MAR21.45-50
```

### 7.11 D10 `ima-pnl-vector`: new (P&L vectors per risk class and liquidity horizon)

FRTB-IMA's expected shortfall needs, per desk and date, the P&L of the portfolio under **different subsets of risk factors**: all factors
and each liquidity-horizon cascade level, the full and the reduced set, the current and the stressed period, unconstrained and per risk
class (MAR33). Drishti cannot make these from one vector: the subsets require the pricer to move only some factors.

**Grain:** one row per position (or trade), business date, scenario set, **factor subset**. **Key:** `IMA_VECTOR_ID` =
`IMA-<POSITION_ID or TRADE_ID>-<SCENARIO_SET_ID>-<SUBSET>`. **Storage:** `<root>/risk/ima-pnl-vector/`, partitioned by `business_date`
and `SCENARIO_SET_ID`, vector column `PNL`. **Sources:** the bank's FRTB IMA engine (Murex FRTB, Calypso, an in-house grid, or the FIS
engine itself). **Frequency:** daily for the current period; the stressed set at least weekly (MAR33). **Size:** with 3 calibrations
(full current, reduced current, reduced stressed) × 5 liquidity-horizon levels × (1 unconstrained + 5 risk classes) = **90 vectors a
position** at most; sparse in practice (a position touches one or two classes and its factors' horizons), about **10 to 20 vectors a
position** of 250 values: 2.3M × 15 × 250 × 4 bytes ≈ **35 GB** a date in float32 (up to 207 GB dense); only for IMA-approved desks⁴.

| Field | Type | Unit | Req. | Meaning |
|---|---|---|---|---|
| `IMA_VECTOR_ID` | id | | required | the key |
| `GRAIN`, `TRADE_ID`, `POSITION_ID`, `FRTB_DESK` | text, id | | required | the member and its regulatory desk |
| `BUSINESS_DATE`, `SCENARIO_SET_ID`, `BATCH_ID`, `COMPUTED_AT`, `RESTATEMENT`, `RUN_KIND` | | | required | as the vectors |
| `CALIBRATION` | text | | required | `FULL_CURRENT`, `REDUCED_CURRENT`, `REDUCED_STRESSED` |
| `LH_LEVEL` | integer | days | required | 10, 20, 40, 60, 120: only factors with liquidity horizon ≥ this level move (level 10 is all factors) |
| `RISK_CLASS` | text | | required | `ALL` or `GIRR`, `CSR`, `EQ`, `COMM`, `FX` (only that class's factors move) |
| `PNL` | vector | USD | required | 250 values (or the set's length), gain positive |
| `STATUS`, `PROXY_REASON` | text | | required, optional | |

```json
{"IMA_VECTOR_ID":"IMA-POS-BOOK-RATES-1~MX-30000951-IMA-CUR-250-FULL_CURRENT-L20-GIRR","GRAIN":"POSITION",
 "POSITION_ID":"POS-BOOK-RATES-1~MX-30000951","FRTB_DESK":"FD-USD-RATES","BUSINESS_DATE":"2026-09-30","SCENARIO_SET_ID":"IMA-CUR-250",
 "CALIBRATION":"FULL_CURRENT","LH_LEVEL":20,"RISK_CLASS":"GIRR","PNL":[-1820.4,"… 250 values …"],"STATUS":"ok",
 "BATCH_ID":"B-IMA-2026-09-30-01","COMPUTED_AT":"2026-09-30T07:30:00Z","RESTATEMENT":0,"RUN_KIND":"FULL"}
```

**Validation:** every (calibration, level, class) the desk's factors need present or the desk's ES is refused with the missing subsets
named; level 10 `ALL` of `FULL_CURRENT` should equal the position's normal vector over the same 250 days (checked to 0.01%, a warning);
vectors only for desks with `IMA_ELIGIBLE` positions. **Alternative (decision 4):** the source delivers desk-level vectors only (a bank
whose engine aggregates), far smaller (thousands of rows), losing position drill-through for IMA.

### 7.12 D11 `desk-pnl`: new (actual, hypothetical and risk-theoretical P&L)

The 2.9.8 row 3 feed ([REGULATORY_VAR_REPORTING section 8.2.3](../architecture/REGULATORY_VAR_REPORTING.md#823-the-pl-feed-g3)); the cube
uses it for backtesting and PLA. **Grain:** one row per regulatory desk (or trade, better) per business date. **Key:** `DESK_PNL_ID` =
`DPNL-<FRTB_DESK or TRADE_ID>-<BUSINESS_DATE>`. **Storage:** `<root>/risk/desk-pnl/`. **Sources:** product control (actual, from the
general ledger and the front office's P&L), the front office (hypothetical: yesterday's positions at today's prices), the risk engine
(risk-theoretical, RTPL, from the IMA model's factors). **Frequency:** T+1 for actual; daily. **Size:** desks × days, small; per trade 10M
rows a day, about 1 GB.

| Field | Type | Unit | Req. | Meaning |
|---|---|---|---|---|
| `DESK_PNL_ID` | id | | required | |
| `GRAIN` | text | | required | `DESK` or `TRADE` |
| `FRTB_DESK`, `DESK`, `TRADE_ID`, `LEGAL_ENTITY` | id | | required by grain | |
| `BUSINESS_DATE` | date | | required | the day the P&L is for |
| `APL` | amount | USD | required | actual P&L, without fees, commissions, reserves, net interest income (MAR32) |
| `HPL` | amount | USD | required | hypothetical P&L |
| `RTPL` | amount | USD | optional | risk-theoretical P&L; when absent Drishti uses its own from the vectors (named as such) |
| `EXCLUDED_ITEMS` | record list | USD | optional | the parts removed from actual, by type |
| `SOURCE`, `BATCH_ID`, `RESTATEMENT`, `COMPUTED_AT` | | | required | |

```json
{"DESK_PNL_ID":"DPNL-FD-USD-RATES-2026-09-30","GRAIN":"DESK","FRTB_DESK":"FD-USD-RATES","DESK":"DESK-RATES","LEGAL_ENTITY":"LE-NY",
 "BUSINESS_DATE":"2026-09-30","APL":-3120400,"HPL":-2980150,"RTPL":-3011020,"SOURCE":"PRODUCT-CONTROL","BATCH_ID":"B-PNL-2026-10-01-01",
 "RESTATEMENT":0,"COMPUTED_AT":"2026-10-01T09:00:00Z"}
```

**Validation:** one row per desk and date per source; a restatement supersedes; HPL and RTPL both present for PLA.

### 7.13 D12 `stress-scenario` (extended) and `scenario-pnl` (new)

`stress-scenario` (SCN, existing): key `SCENARIO_ID`, `NAME`, `TYPE` (historical, hypothetical), `SEVERITY`, `SHOCKS[]` (`FACTOR`,
`SHOCK`), `HORIZON` in words. **Extended (STR.md section 3.3):** `VERSION`, machine-readable shocks on the factor dictionary
(`RISK_FACTOR`, `SHOCK`, `SHOCK_UNIT` bp, %, vol points, absolute), `IN_PROGRAMME`, `STRESS_CADENCE`, `APPROVED_BY`. **`scenario-pnl`
(new, STR.md section 3.2):** one row per trade, scenario and date, key `<TRADE_ID>~<SCENARIO_ID>`, `PNL` (USD, gain positive, full
revaluation), `BASE_MTM`, `SCENARIO_VERSION`, `STATUS` (`ok`, `proxied`, `sensitivity`, `failed`), `BATCH_ID`; 1M trades × 50 scenarios =
50M rows a date, about 600 MB compressed; at 10M trades about 6 GB (estimate from STR.md). The cube's stress P&L is additive (one scenario)
and its "worst scenario" per cell is a maximum over scenarios computed per cell. Sensitivity stress (Σ sensitivity × shock) needs only
D8 and the extended shocks.

```json
{"SCENARIO_PNL_KEY":"MX-20000209~SCN-COVID2020","BUSINESS_DATE":"2026-09-30","TRADE_ID":"MX-20000209","SCENARIO_ID":"SCN-COVID2020",
 "SCENARIO_VERSION":3,"PNL":-182340.0,"BASE_MTM":4121280.0,"STATUS":"ok","BATCH_ID":"B-STR-2026-09-30-01"}
```

### 7.14 D13 `default-risk` and `residual-risk`: new (FRTB.md proposals)

From [FRTB.md sections 3.3 and 3.4](../contracts/FRTB.md#33-default-risk-the-drc-inputs-optional). **`default-risk`:** one row per trade
(or position) and obligor per date, key `<TRADE_ID>~<OBLIGOR>`: `OBLIGOR` (LEI), `SENIORITY` (`SENIOR`, `NON_SENIOR`, `EQUITY`,
`COVERED`), `CREDIT_QUALITY` (rating band for MAR22 risk weights), `DIRECTION` (long, short in default terms), `NOTIONAL` and `MTM`
(USD), `LGD` (MAR22 defaults: senior 75%, non-senior 100%, covered 25%, equity 100%), `MATURITY_DATE`, `DRC_CLASS` (`NON_SEC`, `SEC_NON_CTP`,
`SEC_CTP`). **`residual-risk`:** one row per trade, `RRAO_CLASS` (`EXOTIC`, `OTHER_RESIDUAL`), `GROSS_NOTIONAL` (USD), `EVIDENCE` (why).
Sources: the FRTB engine or the trade's classification in the booking system. Sizes small (rows only for credit and equity positions,
and residual-risk trades). Validation: an obligor without a rating band refused (no default band guessed); `LGD` outside (0, 1] refused.

### 7.15 D14 `cube-correction`: new (data corrections, Drishti-owned)

**Grain:** one row per correction. **Key:** `CORRECTION_ID`. **Storage:** Drishti's own lake area (`<root>/drishti/cube-correction/`),
written only by write-back on approval. **Size:** small.

| Field | Type | Req. | Meaning |
|---|---|---|---|
| `CORRECTION_ID` | id | required | `COR-<date>-<n>` |
| `TARGET_KIND`, `TARGET_ID` | text, id | required | the record corrected (`pnl-vector` `PNL-MX-20000274-HS-509`; `position-sensitivity` …) |
| `ACTION` | text | required | `EXCLUDE`, `REPLACE_VECTOR`, `PROXY_FROM` (another member's vector scaled), `SET_ATTRIBUTE` |
| `PAYLOAD` | record | optional | the replacement (vector, attribute and value, proxy id and factor) |
| `REASON`, `AUTHOR`, `APPROVER`, `APPROVED_AT`, `EXPIRES` | text, timestamp, date | required | governance; expiry defaults to the next business date |
| `BUSINESS_DATE`, `APPLIES_TO_OFFICIAL` | date, boolean | required | whether the official run takes it |

### 7.16 D15 `predeal-request` and `predeal-result`: new

**`predeal-request`** (Drishti-owned, private to the requester, deleted with the sandbox): `REQUEST_ID`, `SCOPES` (the view's scopes or
one), `ITEMS[]` each either `{COPY_OF, FACTOR}` (built today), or `{TERMS}` (a trade in the `trade` contract's fields and legs, priced by
QLRisk), or `{SENSITIVITIES}` (a sparse list as D8, for the delta-gamma estimate), `METHOD` (`copy`, `price`, `delta-gamma`, `auto`),
`LENS`. **`predeal-result`** (computed, never stored beyond the session): per scope `BEFORE`, `AFTER`, `CHANGE` (`VAR`, `ES`, `SVAR`,
`DV01` by bucket, `FRTB_SA`), per item `STANDALONE_VAR`, `INCREMENTAL_VAR`, `COMPONENT_VAR`, `METHOD_USED`, `PRICING_STATUS`, `FLAGS`
(`proxy`, `delta-gamma`), `PROVENANCE`.

```json
{"REQUEST_ID":"PD-2026-09-30-asha-0007","SCOPES":["DESK:DESK-RATES","BANK"],"METHOD":"auto","LENS":{"HORIZON":"1D","CONFIDENCE":0.99},
 "ITEMS":[{"TERMS":{"PRODUCT_TYPE":"IRS","CURRENCY":"USD","QUANTITY":250000000,"BUY_SELL_CODE":"B","START_DATE":"2026-10-02",
  "MATURITY_DATE":"2036-10-02","LEGS":[{"LEG_TYPE":"FIXED","RATE":0.0391},{"LEG_TYPE":"FLOAT","INDEX":"SOFR"}]}}]}
```

### 7.17 Proposed contract YAMLs (not created yet)

| File | Kind | Mnemonic (proposed) | Class | Pack | New or extended |
|---|---|---|---|---|---|
| `config/packs/market-risk/contracts/position-sensitivity.contract.yaml` | `position-sensitivity` | `SENS` | input | market-risk | new |
| `config/packs/market-risk/contracts/risk-factor.contract.yaml` | `risk-factor` | `RFAC` | fundamental | market-risk | new |
| `config/packs/market-risk/contracts/ima-pnl-vector.contract.yaml` | `ima-pnl-vector` | `IMAV` | input | market-risk | new |
| `config/packs/market-risk/contracts/desk-pnl.contract.yaml` | `desk-pnl` | `DPNL` | input | market-risk | new (2.9.8 row 3) |
| `config/packs/market-risk/contracts/risk-limit.contract.yaml` | `risk-limit` | `RLIM` | fundamental | market-risk | new (RO3) |
| `config/packs/market-risk/contracts/scenario-pnl.contract.yaml` | `scenario-pnl` | `SPNL` | input | market-risk | new (STR.md) |
| `config/packs/market-risk/contracts/default-risk.contract.yaml` | `default-risk` | `DRCI` | input | market-risk | new (FRTB.md) |
| `config/packs/market-risk/contracts/residual-risk.contract.yaml` | `residual-risk` | `RRAI` | input | market-risk | new (FRTB.md) |
| `config/packs/market-risk/contracts/cube-correction.contract.yaml` | `cube-correction` | `CCOR` | drishti-owned | market-risk | new |
| `config/packs/market-risk/contracts/frtb-sa-result.contract.yaml` | `frtb-sa-result` | `FRSA` | computed | market-risk | new (replaces stored `frtb-sensitivity`) |
| `config/packs/market-risk/contracts/ima-result.contract.yaml` | `ima-result` | `IMAR` | computed | market-risk | new |
| `pnl-vector`, `scenario-pv`, `scenario-set`, `stress-scenario`, `trade` | existing | | | | extended (optional fields above) |
| `config/packs/market-risk/sensitivity-buckets.yaml` | configuration | | | market-risk | new |

The mnemonics are placeholders: each must be checked against every pack's mnemonics at build. The CCR cube's list is in
[CCR_CUBE_LENS.md section 7](CCR_CUBE_LENS.md#7-data-and-contracts).

² Trade size from the demo's trade records (legs and events included); Parquet ratio about 10:1 from the demo lakes.
³ From OVERSIGHT_LENS.md section 7.4 (about 110 bytes an element in JSON, 10 to 15 in Parquet; 1,764 business days in seven years).
⁴ 4 bytes a float32 value; the sparse count assumes a position in one risk class with factors in two liquidity-horizon levels across three
calibrations and the unconstrained set (3 × 2 × 2 + a few); to be measured on a bank's real factor mapping.

---

## 8. Measures

### 8.1 VaR, ES and their decomposition (built; served per cell)

As built ([VAR_GUIDE section 2](../guides/VAR_GUIDE.md#2-the-measures-with-worked-numbers)): with the cell's summed vector *v* of N
scenarios sorted worst first, `VaR(α) = −v_(k)`, k = ⌈N(1−α)⌉ (the 6th worst of 509 at 99%); `ES(α) = −mean(v_(1..k_ES))` (13 worst at
97.5%); component VaR the scope's tail window mean scaled to add up (Euler); component ES exact; incremental VaR `VaR(v) − VaR(v − p)`;
marginal VaR per million. On the demo: **the bank's 1-day 99% VaR is 112,041,646 USD**, ES 160,143,809, the ten desks' VaRs sum to
367.9 m (diversification shown in the pivot's total row). Stressed VaR (2.9.7 row 2c) is the same on the stress set.

### 8.2 Sensitivities and ladders (MC1, MC3)

Additive: a cell's sensitivity to factor *f* is `Σ_{positions in cell} s_f`, converted at today's FX. Ladders sum by bucket of a grid.
Net and gross (Σ|s|) shown; utilisation against two-sided limits `use = max(net/LIMIT_LONG, −net/LIMIT_SHORT)`.

### 8.3 FRTB standardised approach (MC5 to MC7)

**Sensitivities-based method (MAR21).** For each risk class, measure (delta, vega, curvature) and bucket *b*:

```
WS_k   = RW_k · s_k                                       weighted sensitivity (delta: MAR21.4; vega: s_k = vega_k · σ_k)
K_b    = sqrt( max(0, Σ_k WS_k² + Σ_k Σ_{l≠k} ρ_kl WS_k WS_l) )            within the bucket
S_b    = max( min(Σ_k WS_k, K_b), −K_b )                  the alternative specification, used only when the sum below is negative
Δ      = sqrt( Σ_b K_b² + Σ_b Σ_{c≠b} γ_bc S_b S_c )      across buckets (with Σ_k WS_k in place of S_b unless the radicand is negative)
```

**Curvature (MAR21.5):** `CVR_k = −min( V(x+RW) − V(x) − RW·s_k , V(x−RW) − V(x) + RW·s_k )` from `CURVATURE_UP`, `CURVATURE_DOWN` and
the delta; `K_b = sqrt(max(0, Σ max(CVR_k,0)² + Σ_{l≠k} ρ_kl² CVR_k CVR_l ψ(CVR_k, CVR_l)))`, ψ = 0 when both are negative, else 1; across
buckets with γ_bc² and the same ψ.

**The three correlation scenarios (MAR21.6):** medium (the prescribed ρ, γ), high `ρ_high = min(1.25ρ, 1)`, low
`ρ_low = max(2ρ − 1, 0.75ρ)` (the same for γ); the SBM capital is the **largest** over the three scenarios of Σ over risk classes of
(delta + vega + curvature). The cube shows the three side by side and marks the binding one.

**DRC (MAR22):** per obligor, gross jump-to-default `JTD_long = max(LGD · notional + P&L, 0)`, `JTD_short = min(LGD · notional + P&L, 0)`,
scaled by maturity (floor 3 months, cap 1 year), netted by obligor and seniority; per bucket
`DRC_b = max( Σ RW_i · netJTD_long,i − WtS · Σ RW_i · |netJTD_short,i| , 0 )` with `WtS = Σ netJTD_long / (Σ netJTD_long + Σ |netJTD_short|)`;
the total is the simple sum over buckets (non-securitisations).

**RRAO (MAR23):** `Σ gross notional × 1.0%` for exotic underlyings, `× 0.1%` for other residual risks.

**Total SA** = SBM + DRC + RRAO. Non-additive across desks (square roots and maxima): every cell re-runs the formula on its members' net
weighted sensitivities, which are additive per factor (so a cell's inputs are a sparse sum, cheap to combine across shards).

### 8.4 FRTB internal models approach (MC8 to MC10)

**ES with liquidity horizons (MAR33):** with *T* = 10 days and the cascade levels LH_j ∈ {10, 20, 40, 60, 120},

```
ES = sqrt( ES_T(P)² + Σ_{j≥2} ( ES_T(P, j) · sqrt( (LH_j − LH_{j−1}) / T ) )² )
```

where `ES_T(P, j)` is the 97.5% ES of the 10-day P&L with only the factors of horizon ≥ LH_j moving (the `ima-pnl-vector` subsets, 10-day
P&L from 1-day vectors by the overlapping sum or as delivered: decision 3). **Stressed calibration:**
`ES = ES_{R,S} · max(ES_{F,C} / ES_{R,C}, 1)`. **IMCC:** `ρ · ES_unconstrained + (1 − ρ) · Σ_classes ES_class`, ρ = 0.5. **SES for
non-modellable factors:** a stress scenario per NMRF, aggregated with ρ = 0.6 for idiosyncratic credit and equity and summed for the rest
(MAR33). **DRC (IMA):** a two-factor default simulation at 99.9% over one year (MAR33): **not computed by Drishti**: a bank input
(decision 5). **Capital:** `max( IMCC_{t−1} + SES_{t−1} ; m_c · avg_60(IMCC) + avg_60(SES) ) + DRC`, `m_c = 1.5` plus the backtesting
add-on.

**Backtesting and PLA (MAR32):** per desk, exceptions of 99% and 97.5% VaR against APL and HPL over 250 days (desk thresholds 12 and 30);
PLA compares HPL with RTPL over 250 days: Spearman correlation and the Kolmogorov–Smirnov statistic; green (ρ ≥ 0.80 and KS ≤ 0.09), red
(ρ < 0.70 or KS > 0.12), amber otherwise; a red desk falls back to SA. The cube shows the desk zone board, each desk's two statistics with
their 250-day sparklines, and its exceptions.

### 8.5 Stress P&L (built stored; computed: RO9)

A cell's P&L under a scenario is additive; the worst scenario per cell is a maximum over scenarios (not additive: each cell may pick a
different scenario, said in the cell). From `scenario-pnl` (full revaluation) or Σ sensitivity × shock (labelled approximate).

---

## 9. Pre-deal what-if

| Method | When | How | Status |
|---|---|---|---|
| **copy** | the new trade resembles a booked one | a booked trade's vector scaled (1, −1, 0.5) added to the scope | **Built** ([VAR_GUIDE section 8.5](../guides/VAR_GUIDE.md#85-the-pre-deal-what-if-in-the-basket-tray)) |
| **price** (full revaluation) | new terms | QLRisk prices the trade on the base market and under each of the 509 scenario moves (`MarketRiskEngine.scenario_pnl`); the vector joins the scope like any other; sensitivities from the same pricing feed the ladder and SBM | **Not built** (QLRisk design only until its API is stable; [PYTHON_COMPUTE.md section 9.2](../architecture/PYTHON_COMPUTE.md#92-qlrisk-default-python)) |
| **delta-gamma** (fallback) | no pricer for the product, or a quick answer | from the trade's sensitivities (supplied, or from a similar booked trade): `P&L_s ≈ Σ_f s_f · Δx_{f,s} + ½ Σ_f Γ_f · Δx_{f,s}²` with Δx the factor moves of scenario *s* (from the factor dictionary's market-data references); flagged `delta-gamma`, its error shown as the gap on the same product's booked trades | **Not built** |

**The QLRisk bridge is shared with the CCR cube**: the same request prices a trade's scenario P&L here and its path MtMs there; the same
market data assembly (the server's business-date snapshot) and caching by trade terms and date. **Response target:** 2 s for a vanilla
swap's 509 scenarios on one worker (estimate⁵); the what-if per cell of a 40 × 12 view adds 0.8 s (section 6.3).

⁵ QLRisk's own timing for a vanilla swap reprice is about a millisecond; 509 scenarios plus curve rebuilding per scenario dominate. To be
measured when its API is declared stable.

---

## 10. Performance and scale targets

| Item | Target | Basis |
|---|---|---|
| Positions | 2.3M (10M trades) on the reference cluster; 10M positions with `target-shard-mb: 1024` | CLUSTER_DEPLOYMENT section 15, decision PO-C2 |
| Scenario values held | 1,518 a position (normal 509, stressed 250, spare), float32: 14 GB a date across 6 owners | CLUSTER_DEPLOYMENT section 5 |
| Sensitivity elements | 23M to 115M a day, about 0.4 to 2 GB in the hot tier⁶ | section 7.9 |
| IMA vectors | about 35 GB a date for every desk approved (sparse), held only for open dates | section 7.11 |
| A view of 500 scopes | 400 ms p95 warm | section 6.3 |
| SBM bank capital, three scenarios | 2 s; a desk 200 ms⁶ | additive sums per factor per node, formulas on a few hundred thousand factors |
| IMA ES of the bank, every subset | 5 s⁶ | 90 subset vectors summed per node, 250 values each |
| Intraday batch to patched cells | 1 s after the batch applies | section 4.7 |
| Load of the sensitivities | 1 to 3 minutes a day on 32 vCPU | OVERSIGHT_LENS section 7.4 |

⁶ Estimates: the sparse float32 element plus a 4-byte factor index is 8 bytes (23M × 8 = 0.2 GB; 115M × 8 = 0.9 GB) with dictionaries
and node aggregates doubling it; SBM and IMA times scaled from the VaR engine's measured summing rate (VAR_GUIDE section 27). Every
figure is to be measured on the 10K test data, then the product owner's server, before it is quoted outside.

---

## 11. How this beats FIS Adaptiv / artiQ

| FIS's public claim | Drishti's answer | Why Drishti's is better | The proof in a demo |
|---|---|---|---|
| In-memory aggregation across risk types, firm-wide down to granular factors | the VaR engine's in-memory vectors (built) plus sensitivities by factor (MC1) in the same cube; drill to the trade, the P&L vector and the scenario matrix | the drill does not stop at the cube's lowest grain: it reaches the trade's page, its booking history, its counterparty's exposure and the market data it was priced on | `MCUBE BANK BY DESK,RISK_CLASS` → the credit desk → a position → `SMAT` → the trade → its counterparty's PFE, in under ten clicks |
| Non-additive VaR and ES over billions of scenario P&L values | every cell and total re-aggregated from vectors in a fixed order, components adding up exactly (built); 2.3M × 1,518 values sharded | **equal to the bit** on one server or a cluster, tested (`VarShardClusterTest`); FIS publishes no such evidence | the same view on one node and on three members, every cell identical; the diversification of each total shown beside it |
| What-if and on-the-fly stress | the basket what-if (built); per-cell what-if side by side; QLRisk pricing of new terms; sensitivity stress for any shock | the what-if is per cell of the whole view, not a separate report; the hypothetical trade's own standalone, incremental and component VaR are shown; every result carries its method flag | add an unwind and a new swap, watch 480 cells update side by side with changed cells outlined |
| FRTB SA and IMA | SBM with the three scenarios, DRC, RRAO computed from trade-grain sensitivities (MC5 to MC7); IMA ES by liquidity horizon, SES, PLA and backtesting by desk (MC8 to MC10) | the formula is shown **with this cell's numbers in it**; the binding correlation scenario is marked; capital pivots by any dimension, not only the regulatory desk | click a bucket: the WS, K_b, ρ and the scenario that binds, then drill to the positions behind the largest WS |
| Streaming via Kafka | intraday batches from files or Kafka (built), patched in place in the view | each patched cell names its batch; the time-travel slider replays any batch; nothing is silently overwritten | publish a batch on the demo topic; the cell flashes; its popover names the batch; slide back to before it |
| Mixed mode: prices trades itself or accepts front-office PVs | accepts P&L vectors and scenario PVs in any currency and grain (built); prices only for pre-deal (QLRisk); delta-gamma fallback | bring your own valuations: no second pricing library to validate for the daily numbers; the front office's model is the risk model, reconciled by construction | Endur's scenario PVs in EUR beside Murex's USD vectors in one total, the FX provenance per cell |
| Users correct erroneous data inside the cube | write-back corrections as approved overlays with expiry (MC11) | four eyes, a reason, an expiry and a marker on every affected cell; the original stays; the official run says which corrections it took | propose a proxy for a failed vector, approve it as a second user, see the corrected marker and the provenance |
| Limit governance | VaR limits per node (built); one limit catalogue for every measure (RO3), breaches and escalation (RO2) | limits are drawn in the cell (bars), on the ladder and on the sparkline; a breach opens its workflow in place | a DV01 bucket at 104%, red bar, click: the breach record and its escalation chain |
| Excel, Tableau and XLCubed | xlsx and csv export; Power Query and Arrow over REST per saved view; embedding in any web page | the browser **is** the front end, with Bloomberg-grade keyboard UX; Excel refreshes the same saved view the risk manager sees, with their entitlements | refresh a Power Query of the morning view; embed the same view in an intranet page |
| Distribution across several servers | sharding with owners, replicas, handover and drain (built 2.9.6), documented with sizes and failure timings | **documented**: shard key, sizes, failure behaviour and the equality evidence are public in CLUSTER_DEPLOYMENT; FIS publishes none | kill an owner during a pivot: the replica answers, the cell's provenance shows who answered |
| (no claim) provenance | every cell's data versions, batches, scenario set, method, members, proxied share | an auditor or a regulator can reproduce any number | hover any cell |
| (no claim) explanations | why it moved (exact steps) and what drives it (components, factors) next to the figure | the morning question answered on the same screen | the 18 m overnight move of the credit desk explained in six steps |
| (no claim) open storage | Delta or Iceberg, published contracts, JSON Lines in | the bank owns its data; any tool reads the lake | open the same table in Spark or DuckDB |
| (no claim) one platform | Market, World, entity pages, BI and the CCR cube beside the MR cube | context without leaving the screen: the curve that moved, the issuer's news | from a CS01 concentration to the issuer's page, its news and its GLEIF parents |
| (no claim) phone and lenses | phone tiers, lenses per person | a head of risk reads the morning view on a phone | the morning view at 390 px |

---

## 12. Phases (proposed)

Every release below is a **proposal** for the product owner, not a commitment. The rows are in the plan's "Risk cubes" section.

| Phase | Rows | Proposed release | Depends on |
|---|---|---|---|
| 1. Sensitivities in the lake | MC1 (= RO15, the contract, loader, factor dictionary, buckets, sums) | 2.9.8 (proposed) | none; RO15's design |
| 1. The pivot | MC2 (the cube core: pivot with non-additive totals, drill, saved views, provenance per cell, export), MC3 (ladders and heat map), MC4 (sparklines, limit bars, waterfall, histogram) | 2.9.8 (proposed) | MC1 for sensitivity measures; the VaR engine (built) |
| 2. FRTB SA | MC5 (SBM delta, vega, curvature, three scenarios), MC6 (DRC), MC7 (RRAO) | 2.9.9 (proposed) | MC1; `default-risk`, `residual-risk` |
| 2. Corrections, explain | MC11 (corrections), MC12 (explain the move: RO1 plus the factor overlay) | 2.9.9 (proposed) | write-back (built); RO1 |
| 3. FRTB IMA | MC8 (ES by liquidity horizon, stressed calibration), MC9 (SES and NMRF), MC10 (PLA and desk zones) | 3.0 (proposed) | 2.9.8's P&L feed and backtesting; 2.9.7 row 2c (stressed VaR); `ima-pnl-vector` from the bank |
| 3. Pre-deal pricing | MC13 (QLRisk pricing and delta-gamma, shared with CC8) | 3.0 (proposed) | QLRisk's API declared stable |

---

## 13. Risks and data dependencies

| Risk | Effect | Mitigation |
|---|---|---|
| **IMA vectors are the bank's to produce** | without `ima-pnl-vector` subsets no ES by liquidity horizon, no stressed calibration | accept desk-level vectors (decision 4); ship the computation on synthetic subsets in the demo; say "no subsets delivered" per desk, never compute a substitute |
| **SIMM parameters are licensed by ISDA** | the shared sensitivity contract feeds SIMM (CCR cube); Drishti cannot ship the real risk weights | a client uses its own ISDA licence and loads the parameters as configuration; the demo uses made-up parameters, labelled so |
| **FRTB parameters differ by jurisdiction** (OSFI, US proposal, EU) | different risk weights, correlations, floors | parameters per jurisdiction as configuration; BCBS first (as SA-CCR, COMPUTED_ATTRIBUTES section 10) |
| Sensitivities and vectors from different runs | explain overlays and SBM disagree with VaR | the consistency check of section 7.9; same `BATCH_ID` family required |
| Pivot views of thousands of scopes | heavy requests on owners | a cell budget (section 4.12), batched requests, admission queue (CD6) |
| Regulatory desks differ from management desks | IMA by the wrong grouping | `FRTB_DESK` on trades and vectors; the FRTB hierarchy as a configured hierarchy |
| QLRisk API not stable | no pricing of new terms | copy and delta-gamma methods first; pricing behind a flag |
| Corrections misused to hide losses | governance failure | four eyes, reasons, expiry, markers on every cell, the official run's list; RO2 breaches unaffected by corrections unless approved |
| Vendor comparison claims | over-claiming against FIS | only claims shown in the demo (section 11); FIS's are marketing, said so |

---

## 14. Decisions for the product owner

1. **One lens, two documents, one core.** The MR Cube and CCR Cube as two lenses sharing one cube core (recommended), or one "Risk Cube"
   lens with an MR and a CCR tab.
2. **The default layout.** A (pivot first) as the landing with B and C as tabs (recommended), or one of B or C first; after the
   mock-ups.
3. **10-day P&L for IMA.** From the bank's 10-day vectors (recommended where the engine provides them), or Drishti's overlapping sum of
   1-day vectors (built for VaR, section 18.6 of VAR_GUIDE) labelled.
4. **IMA grain.** Position-level `ima-pnl-vector` subsets for drill-through (recommended for banks that can produce them), or desk-level
   only (smaller, no drill).
5. **IMA DRC.** A bank input (recommended), or a Drishti two-factor default simulation later.
6. **Front ends.** REST, Arrow, Power Query and embedding (recommended), or also an XMLA/MDX endpoint for Excel pivot tables and XLCubed
   (large; a product of its own).
7. **Corrections in the official run.** Approved corrections included by default with the list on the run (recommended), or excluded
   unless the run is restated.
8. **Release placement.** Sensitivities and the pivot proposed for 2.9.8 beside regulatory reporting phase 1 (recommended: the pivot
   serves the reports), or after 2.9.8; FRTB SA 2.9.9; IMA and pre-deal 3.0.
9. **Command word.** **Decided (product owner, 2026-10-31): `MCUBE`** (the CCR cube is `CCUBE`); checked against every pack's mnemonics at build.
10. **The `position-sensitivity` contract.** Adopt OVERSIGHT_LENS decision 9 as this cube's input (recommended), retiring
    `frtb-sensitivity` when MC5 lands.

---

## 15. Glossary

| Term | In plain words |
|---|---|
| **Cube** | Risk numbers at the lowest grain, aggregated for any rows and columns the user picks |
| **Non-additive measure** | One whose total is not the sum of its parts (VaR, ES, capital); computed again for every total |
| **Vector** | A trade's or position's P&L under every scenario, in a fixed order, so vectors add element by element |
| **SBM** | FRTB's sensitivities-based method: risk weights, correlations within and across buckets, three correlation scenarios |
| **DRC, RRAO** | Default risk charge; residual risk add-on |
| **IMA, ES, LH** | The internal models approach; expected shortfall at 97.5%; liquidity horizon (10 to 120 days) |
| **NMRF, SES** | A non-modellable risk factor (too few real prices) and its stressed capital add-on |
| **PLA** | P&L attribution test: is the risk model's P&L close to the front office's hypothetical P&L |
| **APL, HPL, RTPL** | Actual, hypothetical and risk-theoretical P&L |
| **Provenance** | Where a number came from: data versions, batches, method, inputs |
| **Time travel** | Showing the cube as it was at an earlier data version |
| **Delta-gamma** | A quick P&L estimate from first and second derivatives instead of full repricing |
| **Mixed mode** | FIS's term for pricing some trades itself and accepting others' valuations |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
