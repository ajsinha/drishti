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

# Value at risk

> **Skeleton** of the VaR page of the [market-risk section](README.md). Everything on this page is **built** unless a line says
> **i6**. The facts are those of the code (`modules/banking/banking-compute/…/compute/var/`,
> `modules/banking/banking-service/…/banking/var/`, `config/engines/var.yaml`) and of the two guides that hold the full text
> today: [VAR_GUIDE.md](../../guides/VAR_GUIDE.md) and
> [REGULATORY_VAR_REPORTING.md](../../guides/REGULATORY_VAR_REPORTING.md). When this page moves to `guides/market-risk/`, the
> guide's text moves under these headings and the guide's path stays as an alias.

## Contents

1. [What VaR answers](#1-what-var-answers)
2. [Theory](#2-theory)
3. [The mathematics](#3-the-mathematics)
4. [Worked examples](#4-worked-examples)
5. [Data contracts](#5-data-contracts)
6. [Loading: a business date from the lake to memory](#6-loading-a-business-date-from-the-lake-to-memory)
7. [Hierarchies, scopes and positions](#7-hierarchies-scopes-and-positions)
8. [Currencies](#8-currencies)
9. [Limits](#9-limits)
10. [Explain and why it moved](#10-explain-and-why-it-moved)
11. [Backtesting](#11-backtesting)
12. [Official end-of-day results](#12-official-end-of-day-results)
13. [Intraday](#13-intraday)
14. [Shards across nodes](#14-shards-across-nodes)
15. [Performance](#15-performance)
16. [Reading results](#16-reading-results)
17. [Settings, refusals and error codes](#17-settings-refusals-and-error-codes)
18. [What i6 adds](#18-what-i6-adds)
19. [Tutorial](#19-tutorial)

## 1. What VaR answers

*How much could this set of trades lose on a bad day?* For any scope (the bank, a desk, a book, a position, a trade, a basket, any
filter), on any business date, as the person looking. Historical simulation: the bank's systems value each position under each
past day's market move; Drishti sums those values over the scope and reads the loss at the confidence asked.

Today's text: [VAR_GUIDE.md sections 1 and 2](../../guides/VAR_GUIDE.md#1-the-question-var-answers).

## 2. Theory

- Historical simulation and what it assumes (the recent past is the distribution; positions held constant).
- Why the scenario P&L vector is the one input: every figure on this page is a reading of a scope's summed vector.
- VaR is not additive; a hierarchy node's figure is computed from the node's own summed vector, never from its children's VaRs.
- Expected shortfall, and why a tail mean is steadier than one rank.
- What VaR does not say (the loss beyond the quantile; liquidity; a regime the window does not hold).

## 3. The mathematics

With `v` the scope's vector of length `N` (one P&L per scenario, positive for a gain), `o` the scenario indices ordered worst
first (ties by index) and `α` the confidence. `VarMath`, `VarCalc`.

**The P&L vector of a position** (`VarIntake`), from either form the bank sends:

```
P&L vector form (pnl-vector):   v_i(s) = PNL_i(s)                                   in USD as sent
Scenario PV form (scenario-pv): v_i(s) = SCENARIO_PV_i(s) · FX_c(s) − BASE_PV_i · FX_c(base)      c the row's currency
```

**VaR**, two rank rules (`drishti.var.rank-rule`, `kth-worst` as shipped):

```
kth-worst     k = clamp(ceil(N·(1−α) − 1e-9), 1, N)          VaR = −v[o[k−1]]
interpolated  pos = N·(1−α); lo = max(1, floor(pos)); hi = min(N, lo+1); w = clamp(pos − lo, 0, 1)
              VaR = −((1−w)·v[o[lo−1]] + w·v[o[hi−1]])
```

**Expected shortfall** (never interpolated):

```
k_ES = clamp(ceil(N·(1−α_ES) − 1e-9), 1, N)        ES = −(1/k_ES)·Σ_{r=1..k_ES} v[o[r−1]]
```

At the shipped window of 250: VaR 99% is the 3rd worst, ES 97.5% the mean of the 7 worst. On a whole set of 509: the 6th worst
and the 13 worst.

**The 10-day figures** (`VarLens`; `drishti.var.ten-day-method`, `sqrt10` as shipped), both from the same 1-day vector:

```
sqrt10   every scenario P&L × √10; VaR and ES are the 1-day figures × √10 exactly
sum      window i = v[i] + … + v[i+9], added left to right: N − 9 overlapping windows (250 give 241); VaR and ES on the windows
```

**Stressed VaR** (`drishti.var.stress`): the same formulas on the date's stress scenario set (a scenario set of `SET_TYPE STRESS`,
today's positions under the moves of a fixed 12-month stress period); the regulatory average is over 12 weeks of official records.

**The stress window** (`StressWindows`, `drishti.var.stress-window`): over a node's long-history P&L (`pnl-history`), every window
of 250 days is valued as a stressed VaR; the 5 worst non-overlapping windows are listed, and an alert is raised when the worst
exceeds the window in use by more than 5%.

**Parametric VaR and ES** (`ParametricVar`; a cross-check, never official): the normal fitted to the scope's own summed vector.

```
μ = (1/N) Σ v_j        σ = sqrt( Σ (v_j − μ)² / (N − 1) )        (or EWMA weights, λ = 0.94)
VaR = z·σ − μ,  z = Φ⁻¹(α)        ES = σ·φ(z_ES)/(1 − α_ES) − μ        (at 97.5%: σ × 2.3378 − μ)
fat-tail gap = historical VaR − parametric VaR;   component_i = z·cov(P_i, P)/σ − μ_i   (the parts add up exactly)
```

**Decomposition** (component, incremental and marginal VaR, component ES): [VaR decomposition](VAR_DECOMPOSITION.md).

Today's text: [VAR_GUIDE.md section 18](../../guides/VAR_GUIDE.md#18-the-computation).

## 4. Worked examples

- **By hand, ten scenarios** (one book, two positions, α = 80%): VaR, ES, the components adding up, a hedge with a negative
  incremental VaR ([VAR_GUIDE.md section 2.1](../../guides/VAR_GUIDE.md#21-by-hand-ten-scenarios)).
- **On the demo data** (2026-09-30, the bank, USD, the whole set of 509; `VarTestDataPrecisionTest`):

| Lens | VaR | Rank | ES (97.5%) |
|---|---:|---|---:|
| 1-day, 99% | 112,041,646 | 6th worst of 509 | 160,143,809 |
| 10-day √10, 99% | 354,306,794 | 6th worst of 509 | 506,419,190 |
| 10-day sum, 99% | 274,544,283 | 5th worst of 500 windows | 303,557,727 |

- **Stressed VaR**: one bond under three stress days ([VAR_GUIDE.md section 18.8](../../guides/VAR_GUIDE.md#188-stressed-var)).
- **The 10-day windows by hand**: `1, 2, … 14` give `55, 65, 75, 85, 95` (`VarLensTest`).
- **Parametric**: the worked example of [VAR_GUIDE.md section 18.16](../../guides/VAR_GUIDE.md#1816-parametric-var-signs-the-gap-the-decomposition-and-a-worked-example).

Each is a test and a default case of the VaR Playground (`PLAY VAR`).

## 5. Data contracts

| Input (command) | Kind | Grain and key | What it is for | Required |
|---|---|---|---|---|
| Scenario PVs (SPV) | `scenario-pv` | one row per **position** (`GRAIN POSITION`) or trade, date and scenario set; `SCENARIO_PV_ID` | the PV under each scenario and the base PV, in the row's currency | one of the two forms |
| P&L vectors (PNLV) | `pnl-vector` | the same grain; `VECTOR_ID` | the P&L under each scenario, in USD | one of the two forms |
| Scenario FX (FXS) | `fx-scenario` | one row per currency, date and set; `FX_SCENARIO_ID` | USD per unit today and under each scenario; else derived from the FX history | with scenario PVs in another currency |
| Scenario sets (SSET) | `scenario-set` | one row per set and date (the normal set; the stress set); `SCENARIO_SET_ID` | the scenario days, oldest first: the order of every vector | yes |
| VaR limits (VLIM) | `var-limit` | one row per hierarchy node with a limit, per date; `LIMIT_ID` | limit use, near and breach | no |
| Desk P&L (DPNL) | `desk-pnl` | one row per desk and date; `DESK_PNL_ID` | backtesting | for backtesting |
| Long-history P&L (PNLH) | `pnl-history` | one row per node and date; `HISTORY_ID` | the stress window | for the stress window |
| Positions (POS), system baskets (PORT) | `position`, `system-basket` | Drishti-owned: positions are derived, never delivered; baskets are kept in Drishti | scopes | — |

A source system sends one form (PV or P&L) and one grain (trade or position) on one date; a position or trade is in one kind or
the other, never both. A row carries its hierarchy columns (`LEGAL_ENTITY`, `DIVISION`, `DESK`, `TT_BOOK`, `LIMIT_LETTER`) itself.

**One line of each** (vectors cut to three values; a row carries the whole set):

```json
{"VALUE_TYPE":"PV","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-COMM-1~XCH-ComFut-EUR-03","CURRENCY":"EUR","SOURCE_SYSTEM":"ENDUR","SCENARIO_PV_ID":"SPV-POS-BOOK-COMM-1~XCH-ComFut-EUR-03-HS-509","BUSINESS_DATE":"2026-09-30","SCENARIO_SET_ID":"HS-509","TRADE_STATUS":"AMENDED","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-COMM","TT_BOOK":"BOOK-COMM-1","LIMIT_LETTER":"LL-0001","PORTFOLIO":"BOOK-COMM-1","STRATEGY":"","PRODUCT_GROUP":"Commodity","PRODUCT_TYPE":"ComFut","SECURITY_ID":"XCH-ComFut-EUR-03","INSTRUMENT":"ENGY-EXCH-FUT EUR 2026-11","MATURITY_DATE":"2026-12-31","COUNTERPARTY":"","DIRECTION":"LONG","QUANTITY":8750.0,"TAGS":"","BASE_PV":8252.13,"VALUATION_DATE":"2026-09-30","BASE_VALUATION_DATE":"2026-09-30","SCENARIO_PV":[-94382.46,93080.07,58121.8],"STATUS":"ok","PRICER":"demo-factor-model","MODEL_VERSION":"demo-2","COMPUTED_AT":"2026-09-30T06:00:00Z","BATCH_ID":"B-2026-09-30-01","RESTATEMENT":0,"RUN_KIND":"FULL","TRADE_IDS":["1001385","1001840","1004234"]}
{"VALUE_TYPE":"PNL","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-COMM-1~XCH-CMD_FUTURE-USD-03","CURRENCY":"USD","SOURCE_SYSTEM":"MUREX","VECTOR_ID":"PNL-POS-BOOK-COMM-1~XCH-CMD_FUTURE-USD-03-HS-509","BUSINESS_DATE":"2026-09-30","SCENARIO_SET_ID":"HS-509","TRADE_STATUS":"AMENDED","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-COMM","TT_BOOK":"BOOK-COMM-1","LIMIT_LETTER":"LL-0001","PORTFOLIO":"BOOK-COMM-1","STRATEGY":"","PRODUCT_GROUP":"Commodity","PRODUCT_TYPE":"CMD_FUTURE","SECURITY_ID":"XCH-CMD_FUTURE-USD-03","INSTRUMENT":"Commodity future USD 2027-09","MATURITY_DATE":"2027-09-10","COUNTERPARTY":"CP-SUMMIT","DIRECTION":"LONG","QUANTITY":122.0,"TAGS":"","TRADE_CURRENCY":"USD","REPORTING_CCY":"USD","PV":1595886,"PNL":[-471736.0,508921.0,273690.0],"STATUS":"ok","PRICER":"demo-factor-model","MODEL_VERSION":"demo-2","COMPUTED_AT":"2026-09-30T06:00:00Z","BATCH_ID":"B-2026-09-30-01","RESTATEMENT":0,"RUN_KIND":"FULL","TRADE_IDS":["MX-20000291","MX-30002954"]}
{"FX_SCENARIO_ID":"FXS-AUD-HS-509","BUSINESS_DATE":"2026-09-30","SCENARIO_SET_ID":"HS-509","CURRENCY":"AUD","QUOTE":"USD per 1 AUD","RATE":0.661,"RATES":[0.6636463229,0.6635844635,0.6613830351],"SOURCE":"demo-factor-model","BATCH_ID":"B-2026-09-30-01"}
{"LIMIT_ID":"VL-LL~LL-0003","HIERARCHY":"LL","NODE_ID":"LL~LL-0003","LIMIT_VAR":4100000,"CURRENCY":"USD","HORIZON":"1D","CONFIDENCE":0.99,"BUSINESS_DATE":"2026-09-30","VALID_FROM":"2026-01-02","VALID_TO":null}
```

The first is a scenario-PV row of a position, the second a P&L-vector row of a position, then a currency's scenario FX and a
node's limit. Every field of every kind, and a line of `scenario-set`, `desk-pnl`, `pnl-history` and the published `var-official`:
[the contract pages](../../contracts/packs/market-risk.md) and [their samples](../../contracts/samples/market-risk.md).

**What the intake refuses** (`VarIntake`; each row counted and listed in every answer's provenance, never padded): a null or NaN
in a vector (`DRS-5206`: a scenario that failed to price is never filled with zero), a currency with no scenario FX (`DRS-5207`),
a length other than the set's, no base PV, a valuation date other than the business date (`DRS-5201`), a payload that is not its
`VALUE_TYPE` or an id that does not match its grain (`DRS-5208`), a form or grain other than the system's pin (`DRS-5209`), two
forms or grains from one system on one date (`DRS-5210`). A date of mixed runs (batch ids, scenario sets) is not loaded
(`DRS-5202`, `DRS-5203`).

Today's text: [VAR_GUIDE.md Part II](../../guides/VAR_GUIDE.md#14-the-contract-at-a-glance).

## 6. Loading: a business date from the lake to memory

**The window.** `drishti.var.scenario-window` is **250 as shipped**: the newest 250 scenarios of the date's set (or `all`). Every
row is cut to it at load, P&L vectors, scenario PVs and scenario FX alike; a shorter set is used whole with a warning; below 20
is refused at start. The provenance says *scenario window: newest 250 of 509*; the hot file's digest includes it.

**The tiers** (`drishti.var.tier.mode: mapped` as shipped):

| Tier | Holds | Precision |
|---|---|---|
| The lake (Delta or Iceberg) | every vector, every date | float64, as delivered |
| The hot file, one per loaded date, on local disk, memory-mapped | one row per position in hierarchy order, the rows of the trades of positions of several trades, every book's and node's aggregate, columns, ids, nodes | rows float32, aggregates float64 |
| The heap | the open dates' columns, ids, links, nodes, the index of the aggregates | every sum in float64 |

**The first request for a date** (`VarLoader`, `VarBook.Builder`, `VarHotFile`):

1. The digest of the date's inputs (the data versions of `pnl-vector`, `scenario-pv`, `scenario-set`, the position rules, the
   hierarchies' configuration, the naming kinds' and `var-limit`'s versions). If `<data folder>/var/hot/<date>/inputs-<digest>.vhot`
   exists, it is mapped: no lake read.
2. Otherwise the scenario set is read and checked; every vector row streams through `VarIntake` to a float64 spill file (the heap
   holds no vector); the trades are sorted by `LEGAL_ENTITY`, `DESK`, `TT_BOOK`, the cell columns, `POSITION_ID`, `TRADE_ID`, so a
   position, a cell and a book are each one contiguous run; every book and node is summed in float64; each position's row is
   written once as float32; the file is moved into place and mapped.
3. The Parquet read decodes on several threads (`drishti.var.read.threads`) and delivers in file order.

**The life of a date.** At most `hot-dates` (10) are open, the least recently used leaving first; the date just used, the newest
date and the pinned dates stay. A closed date's file stays on disk (`disk-dates` 60) and maps again without a lake read. An open
date compares its lake version every `version-check` (30 s) and reloads in the background when it moved.

**At start** the newest date is opened, and with `preload-previous-date` (true as shipped) the business date before it too, after
the newest, so the first *Why it moved* after a restart compares two open dates.

**A date with no load is never answered silently from an older one**: the answer is the newest earlier date with a warning
(`asOfNotice`), and the official capture of such a date is skipped with that reason.

Today's text: [VAR_GUIDE.md sections 25 to 27a](../../guides/VAR_GUIDE.md#25-one-business-date-in-memory).

## 7. Hierarchies, scopes and positions

- **Hierarchies are built from promoted columns** of the rows (`HierarchySpec`, `Hierarchies`): `MGMT` (division, desk, book),
  `LEGAL` (legal entity, desk, book) and the limit letters (`LL`: limit letter, desk) in the market-risk pack; any number. A level
  column must be a promoted column; an empty value sits under a (Blank) node; a table that does not promote a level column puts
  every row there with a warning (`DRS-5211`).
- **Every node's vector is summed at load** from its books or cells; a node answers from its aggregate.
- **Positions are derived, never delivered** (`PositionRules`): trade-grain rows are grouped by the pack's rules; a position-grain
  row is used as it is.
- **Scopes** (`ScopeExpr`, `ScopeResolver`): `BANK`, `DESK:`, `BOOK:`, `POS:`, `TRD:`, `PORT:`, `BASKET:`, `CAPTURE:`, `WHERE:`,
  combined with `+`, `-`, `&`. Any other scope than a node sums position rows on every core.
- **System baskets and people's baskets** are kept in Drishti.
- **Who sees what** (`VarAccess`): every answer is over the trades the person may see; roles that see figures and no positions.

Today's text: [VAR_GUIDE.md sections 4, 5, 9 and 15.14](../../guides/VAR_GUIDE.md#5-scopes-system-baskets-and-baskets).

## 8. Currencies

Every answer takes a reporting currency (`ccy=EUR`; none: USD). The USD book is built when the date loads; a book in another
currency is built from it with each trade's USD value and the scenario FX (`CurrencyBook`, `ScenarioFx`): for the currencies of
`drishti.var.currencies` (USD, EUR, CAD, JPY, GBP as shipped) in the background after the USD book, kept for the newest 5 dates;
any other the first time it is asked. Per-trade-currency totals of every node (`CurrencyTotals`). Official records are captured in
`drishti.var.official.currencies` (USD as shipped; USD is the only one published).

Today's text: [VAR_GUIDE.md sections 15.10 and 15.11](../../guides/VAR_GUIDE.md#1510-reporting-currency).

## 9. Limits

`var-limit` rows give a node's limit; use is VaR at the configured confidence over the limit. One rule for near and breach
(`drishti.risk.limit-near: 0.8`: near from 80%, a breach over 100%), shared by every limit of the banking module (`RiskLimits`).
Changing the confidence changes limit use.

## 10. Explain and why it moved

- **Explain a number** (`VarNumberExplainer`): any figure opens its scenarios, the rank read, the parts and the inputs' versions.
- **Why it moved** (`VarExplain`; `VAR EXPLAIN`, `GET /api/v1/explain?kind=var`): the change between two dates in causes that add
  up exactly, each a full VaR state, in the order `restatement, hierarchy, trades-out, window, market, trades-in`; the window as
  two lines (the oldest scenario dropped, the newest added); amendments as their own steps where the feed marks them; the 10
  largest contributors per step. A read, never stored (kept 10 minutes).
- **The provenance** of every answer: the date, the inputs' versions and batch ids, the window, rows read and refused, the node
  that computed it.

Today's text: [VAR_GUIDE.md section 3.11](../../guides/VAR_GUIDE.md#3-the-explorer-screen-by-screen).

## 11. Backtesting

- **History of a node** (`VarViews.history`): from its official records (each day's P&L against the previous record's VaR), or a
  replay of today's members over the window, labelled *not a regulatory backtest*.
- **For regulation** (`VarBacktesting`; `VAR <scope> BACKTEST`, `GET /api/v1/risk/var/backtesting`): the official VaR of the day
  before at 99% and 97.5% against the bank's actual and hypothetical P&L (`desk-pnl`) and Drishti's risk-theoretical P&L (the
  official record's `hypoPnl`), for the bank, a division, a legal entity or a desk, over 250 tested days.

```
exception   = P&L_i < −VaR_q(i−1), for APL, HPL and RTPL
zone        = green 0–4, amber 5–9, red 10+          multiplier = the configured table by the count
count read  = actual, hypothetical, or the higher    (drishti.regulatory.backtesting.count)
```

- Kupiec's and Christoffersen's tests; fewer than 250 tested days answer *short*.
- **i6**: the internal-models multiplier table, the desk rule (12 and 30 exceptions), the day's p-value, the exact RTPL
  ([P&L explain](PNL_EXPLAIN.md)).

Today's text: [REGULATORY_VAR_REPORTING.md section 4.6](../../guides/REGULATORY_VAR_REPORTING.md#46-backtesting).

## 12. Official end-of-day results

When a date's inputs are complete, the server captures every node of every hierarchy as the official record (`VarOfficial`,
`VarOfficialStore`): after the load signal, at start for the newest date without one, or by an administrator. A restated date is
captured anew with the next result version; earlier versions are kept. Published as rows of `var-official` (command VOFF) under
`<root>/risk-results/var-official`, Delta or Iceberg, append-only, also as JSON Lines; kept 7 years; never read back by an engine.
The official record is 1-day at the configured confidence, and states its scenario window.

Today's text: [VAR_GUIDE.md section 19](../../guides/VAR_GUIDE.md#19-official-end-of-day-results).

## 13. Intraday

A pricing system sends changed trades during the day (`RUN_KIND INTRADAY`: amended, new or cancelled, each batch its own
`BATCH_ID`, the latest `COMPUTED_AT` per trade wins; a cancel is a row whose status is not live, its vector null). They arrive as
files or on a Kafka topic of JSON Lines, are appended to the lake as a batch per window, and are applied as a layer over the
date's book (`VarIntraday`, `OverlayVectors`); `provenance.intraday.batches` names the batches an answer includes.

In this code line the topic is read by the VaR engine's own stream (`VarPvStream`, `VarPvWindow`, a window of 5 minutes). **In
release i5 the landing moves to the kernel's live lander** (step L5 of
[LIVE_CONNECTORS_DESIGN.md](../LIVE_CONNECTORS_DESIGN.md): a live companion of the risk store, the newest row per key, one batch
per kind and date), and the engine's own stream classes are deleted; this section is rewritten from that code when it is merged.

Today's text: [VAR_GUIDE.md section 8.4](../../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes).

## 14. Shards across nodes

Off by default. Switched on, a date's rows are cut into shards by legal entity and desk (a desk is never split), owned by members
through rendezvous hashing; each owner builds only its shards' hot files; every member puts the date together from its own
shards and the others' skeletons, so every node's aggregate is the same bits everywhere; any other scope is summed by the shards'
owners and added in shard order, VaR and ES taken once. No live owner of a needed shard answers `503 DRS-5215`, never a partial
VaR (`VarShards`, `ShardAssembly`, `ShardSkeleton`). Equal to one server to the cent on three members, Delta and Iceberg
(`VarShardClusterTest`).

Today's text: [VAR_GUIDE.md section 28](../../guides/VAR_GUIDE.md#28-sharding-across-drishtinet).

## 15. Performance

Measured figures; all of them, with the method and the machine, are in [BENCHMARK.md](../../admin/BENCHMARK.md) sections 4, 5 and 12.

| Measure | Measured |
|---|---|
| A node's VaR and ES (its aggregate) | 0.48 ms |
| The whole bank summed from its float32 rows, 100,000 trades | 3.4 ms (about 60 GB/s on 24 threads) |
| A basket of 50,000 scattered positions | 2.6 ms, plus about 45 ms to resolve the ids |
| Reopening a date from its hot file, 100,000 trades | 23 ms |
| A date's first open with the default read threads | 3.5 to 4.5 s at 200,000 positions; 25 to 27 s at 1,000,000 |
| The hot file of a date, 100,000 trades | 237.7 MB |
| The heap of an open date at 1,000,000 positions | 0.72 GB on average (`-Xmx6g` serves 1,000,000 positions with two dates in use) |

## 16. Reading results

| Door | What |
|---|---|
| The explorer | the VaR page: the tree, the headline, the P&L path, contributions, history, captures and compare, the what-if |
| Commands | `VAR <scope>`, `VAR EXPLAIN`, `PVAR`, `SMAT`, `SWIN`, `PLAY VAR` |
| The API | `/api/v1/risk/var/…` |
| The computed kind | `var-result` (command VRES), by the reactive connector `var-engine` |
| BI | the `banking.market_risk_var_` tables; the market-risk cube |
| The playground | `PLAY VAR`: every figure in a grid and a workbook of real formulas |
| The command line | `drishti.py var …` |

## 17. Settings, refusals and error codes

Every setting is in `config/engines/var.yaml` and listed in [CONFIGURATION.md](../../admin/CONFIGURATION.md). The engine is
switched on or off as a whole (`drishti.engines.var.enabled`); off, everything its manifest owns is gone. The error codes are in
the API reference's table.

## 18. What i6 adds

| Addition | Page |
|---|---|
| Decomposition as a computed kind, any promoted column as the grouping, incremental VaR of every part in one pass, the stability band, official rows | [VaR decomposition](VAR_DECOMPOSITION.md) |
| Liquidity-adjusted VaR | [Liquidity-adjusted VaR](LIQUIDITY_ADJUSTED_VAR.md) |
| Concentration measures | [Concentration risk](CONCENTRATION.md) |
| The backtest's internal-models table, desk rule and p-value | [section 11](#11-backtesting) |
| A contract interface other engines call (`VarContract`) | [the design, section 4](../MARKET_RISK_I6_DESIGN.md#4-contracts-between-engines-how-frtb-sa-and-simm-stop-holding-varservice) |

## 19. Tutorial

*VaR from the bank to a trade*: today in [VAR_GUIDE.md](../../guides/VAR_GUIDE.md#tutorial-var-from-the-bank-to-a-trade); it moves
here unchanged.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
