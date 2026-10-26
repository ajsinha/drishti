<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# PNL, P&L explain: the data contract

*Version 1.0 · 2026-10-25 · **Status:** proposed (PLANNED for 2.9.0) · Shared rules: [README.md](README.md) · Model contract:
[VAR.md](VAR.md) · Inputs shared with [TVAL.md](TVAL.md) (valuations) and [FRTB.md](FRTB.md) (sensitivities)*

This is the planned data contract of **PNL**, P&L explain: the day-over-day P&L of any set of trades, from the upstream engine's
valuations, attributed to market moves through the trades' sensitivities (delta, gamma, vega), to time (theta and carry), to new,
amended and ended trades, and to fees, with the **unexplained** remainder and a tolerance. It is computed when asked for any scope and
any pair of business dates. It replaces the stored kind `pnl-explain`. Everything here is a **proposal** for the 2.9.0 build; each
change will be recorded in [section 16](#16-open-questions-and-the-change-table).

**Sources read (2026-10-25):** `config/packs/market-risk/contracts/pnl-explain.contract.yaml`, the sample `pnl-explain.jsonl` (30
books), `data/testdata/market-risk/pnl-explain/` (snapshot of 2026-07-08, every later file empty), `tools/packgen/banking/data_results.py`
(`market_results`, `EXPLAIN_WEIGHTS`, `explain_steps`), `tools/packgen/banking/data_trades.py` (`pnl1d`, `pnl_explain`), the trade
valuations (TVAL.md §13.4) and the Calc snippets `pnl_explain_quality.py`, `pnl_attribution_mix.py`.

## Contents

1. [Summary](#1-summary)
2. [The measure in one page](#2-the-measure-in-one-page)
3. [Inputs from upstream engines](#3-inputs-from-upstream-engines)
4. [Reference and master data read](#4-reference-and-master-data-read)
5. [Conventions specific to P&L explain](#5-conventions-specific-to-pl-explain)
6. [The computation](#6-the-computation)
7. [What Drishti builds](#7-what-drishti-builds)
8. [Scopes](#8-scopes)
9. [What Drishti answers](#9-what-drishti-answers)
10. [Validation, errors and data quality](#10-validation-errors-and-data-quality)
11. [Entitlements and audit](#11-entitlements-and-audit)
12. [Performance and sizing](#12-performance-and-sizing)
13. [Test data and generators](#13-test-data-and-generators)
14. [Retiring the stored version](#14-retiring-the-stored-version)
15. [A worked example](#15-a-worked-example)
16. [Open questions and the change table](#16-open-questions-and-the-change-table)

## 1. Summary

**What it is.** For a scope and two business dates T−1 and T: the **actual P&L** (the change in MtM plus cash), its **risk-based
explanation** (Σ sensitivity × move, by risk class and factor; ½ gamma × move²; vega × vol move; theta), the P&L of **new**,
**amended** and **ended** trades and of **fees**, the **unexplained** rest and whether it is within tolerance; the waterfall from
yesterday's MtM to today's; the trades with the largest unexplained P&L.

**Commands.** `PNL <scope> <GO>` (`PNL BOOK BOOK-COMM-1 <GO>`, `PNL DESK RATES <GO>`; T = the date viewed, T−1 the previous business
date, or `from`); the API `/api/v1/pnl`; the computed kind `pnl-attribution`.

| Part | Status | Where |
|---|---|---|
| Inputs: `valuation` at T−1 and T (TVAL.md), `sensitivity` at T−1 (FRTB.md), new `market-move` and `pnl-event` tables | PLANNED | §3 |
| Per-trade attribution at load of a date pair; per-node sums of every component (Java; all additive) | PLANNED | §6, §7 |
| Any scope, any date pair, tolerance, top unexplained trades; what-if moves in Python | PLANNED | §6, §9 |
| Retirement of `pnl-explain`: `PNL-<book>` opens `PNL BOOK:BOOK-<book>` | PLANNED | §14 |

## 2. The measure in one page

**What it answers:** *what did we make or lose today, why, and how much can we not explain?* For product control (the daily sign-off,
the unexplained P&L), traders (their P&L by risk), risk managers (do the sensitivities describe the book: a large unexplained says no)
and auditors.

**The formula in words**, per trade:

1. **Actual** = MtM today − MtM yesterday + cash received − cash paid in between (coupons, premiums, fees, settlements).
2. **Market moves explained** with yesterday's sensitivities: Σ delta × move of each risk factor + ½ Σ gamma × move² + Σ vega × relative
   change of implied vol.
3. **Time**: theta (the P&L of a day passing with markets still), carry included.
4. **Trade events**: a **new** trade's whole day-one P&L (its MtM plus the upfront cash and fees); an **amendment**'s value change at
   yesterday's market; an **ended** trade's final cash against its last MtM.
5. **Unexplained** = actual − everything explained.

A scope's figures are the sums over its trades: **every component is additive**. Only the tolerance test and the ratios
(unexplained ÷ actual) are applied after the sum.

## 3. Inputs from upstream engines

### 3.1 The tables

```
${DRISHTI_DELTA_ROOT}/risk/
├── valuation/        TVAL.md §3.2: MTM per trade at T−1 and at T                                      (PLANNED)
├── sensitivity/      FRTB.md §3.2: delta, gamma, vega, theta per trade at T−1                         (PLANNED)
├── market-move/      business_date=YYYY-MM-DD/  one row per risk factor: its move from T−1 to T       (PLANNED, new)
└── pnl-event/        business_date=YYYY-MM-DD/  one row per trade event between T−1 and T            (PLANNED, new)
```

Retention 7 years; the load signal of each table reloads the date pair in the engine (product owner, 2026-10-25).

### 3.2 `market-move`: the move of each risk factor

**Grain:** one row per risk factor and business date T (the move from the previous business date). **Key:**
`<RISK_CLASS>~<FACTOR_TYPE>~<QUALIFIER>~<CURVE>~<TENOR>~<UNDERLYING_TENOR>~<MEASURE>` (the factor key of `sensitivity`, without the
trade and the grid). **Source:** the market-data team or the risk engine that produced the sensitivities (the same curves and
conventions; open question 1). **Rows:** every factor present in the T−1 sensitivities (about 100,000–250,000 for a large bank).

| Column | Type | Req. | Unit, sign | Meaning | Example |
|---|---|---|---|---|---|
| `MOVE_KEY` | id (`id`) | required | | the key above | `GIRR~CURVE~USD~SOFR~5Y~~delta` |
| `BUSINESS_DATE` | date | required | | T | `2026-09-30` |
| `FROM_DATE` | date | required | | T−1 (the date of the sensitivities) | `2026-09-29` |
| `RISK_CLASS`, `FACTOR_TYPE`, `QUALIFIER`, `CURVE`, `TENOR`, `UNDERLYING_TENOR` | text | as `sensitivity` | | the factor | `GIRR`, `CURVE`, `USD`, `SOFR`, `5Y`, `` |
| `MEASURE` | text | required | | `delta` (the factor's level moved) or `vega` (its implied volatility moved) | `delta` |
| `MOVE` | rate (DOUBLE) | required | **in the sensitivity's unit**: bp for bp factors; % for % factors (−3.0 = −3%); for vega the **relative** move (0.04 = vol up 4% of itself) | the move from `FROM_DATE` to `BUSINESS_DATE` | `12.0` |
| `LEVEL_FROM`, `LEVEL_TO` | rate | optional | the factor's quoted level | for display and checks (`MOVE` consistent with them) | `4.12`, `4.24` |
| `SOURCE`, `BATCH_ID` | text, id | required | | the market-data snapshot and the run | `MD-2026-09-30-EOD`, `B-MOVE-2026-09-30-01` |

### 3.3 `pnl-event`: what changed between T−1 and T

**Grain:** one row per trade event with a P&L effect in (T−1, T]. **Key:** `EVENT_KEY` = `<TRADE_ID>~<EVENT_TYPE>~<EVENT_ID>`.
**Source:** the booking or settlement systems through the risk engine (open question 2). Many events can also be **derived** by Drishti
(a trade present at T and not at T−1 is new): the table carries what cannot be derived (cash, fees, the value of an amendment).

| Column | Type | Req. | Unit, sign | Meaning | Example |
|---|---|---|---|---|---|
| `EVENT_KEY` | id (`id`) | required | | | `MX-20000311~FEE~F-889` |
| `BUSINESS_DATE` | date | required | | T: the event's P&L date | `2026-09-30` |
| `TRADE_ID` | id | required | | | `MX-20000311` |
| `EVENT_TYPE` | text | required | | `NEW`, `AMEND`, `MATURE`, `CANCEL`, `EXERCISE`, `NOVATE`, `TERMINATE`, `CASHFLOW`, `FEE` | `FEE` |
| `EVENT_ID` | id | optional | | the source system's event id (`EVENTS[].EVENT_ID` of the trade, when it is one) | `F-889` |
| `AMOUNT_USD` | amount | required for `CASHFLOW`, `FEE`, `NEW` (upfront), ended trades (final settlement) | USD, **received positive**, paid negative | the cash | `-500.0` |
| `PV_BEFORE_USD`, `PV_AFTER_USD` | amount | required for `AMEND` | USD | the trade's value before and after the amendment, both at **T−1's market** | `300000.0`, `310000.0` |
| `TRADE_VERSION_BEFORE`, `TRADE_VERSION_AFTER` | text | optional | | for `AMEND` | `4`, `5` |
| `DESCRIPTION` | text | optional | | | `Brokerage` |
| `BATCH_ID` | id | required | | | `B-EVT-2026-09-30-01` |

### 3.4 What the other tables must provide

| Table | Needed for P&L explain |
|---|---|
| `valuation` (T−1 and T) | `MTM` of every trade alive on either date; `TRADE_VERSION` (amendment detection); `PREMIUM_SETTLED` (upfront cash, if not in `pnl-event`) |
| `sensitivity` (T−1) | `delta`, `gamma`, `vega` rows on one complete grid (`drishti.pnl.grid`, default `COMMON` + `SIMM`: the finer IR grid), and one `theta` row per trade: the P&L of time passing from T−1 to the **next business date**, markets unchanged, carry and accrual included |

### 3.5 Delivery and completeness

T is complete when `valuation` at T−1 and T, `sensitivity` at T−1 and `market-move` at T are loaded (`pnl-event` may be empty). Due
time: after the T valuation batch (to be agreed, open question 3).

## 4. Reference and master data read

| Master (kind) | Key | What for |
|---|---|---|
| `trade` (TRD) | `TRADE_ID` | status and version at T−1 and T; derived events (new, matured, cancelled) |
| `risk-hierarchy` (HIER) | `NODE_ID` | nodes; the scope of a trade that moved books between T−1 and T (it counts in its T book; open question 4) |
| business-day calendar (USNY) | | the previous business date |
| `fx-rate` | | none (every amount in USD) |

## 5. Conventions specific to P&L explain

| Convention | Rule |
|---|---|
| Signs | P&L gain positive; cash received positive; `MOVE` positive is the factor going up |
| Units | `MOVE` in the unit of the matching sensitivity ([FRTB.md section 3.2.1](FRTB.md#321-units-and-signs-of-amount_usd)) |
| Dates | T−1 is the previous USNY business date unless `from=` names another; a multi-day span uses T−1's sensitivities and the summed moves (a coarser explain, labelled) |
| Clean or dirty | MtM dirty; coupons paid move MtM down and cash up: they cancel in the actual P&L; theta carries the accrual |
| Rounding | whole USD in answers; float64 sums |
| Hypothetical P&L | the actual P&L of the trades alive at T−1, less amendments and fees, without new trades (the VaR backtest's input, §6.5) |

## 6. The computation

### 6.1 Per trade

Let *A* be the trades alive at T−1, *B* those alive at T. For a trade *t*:

| Case | Actual P&L | Explained |
|---|---|---|
| **continuing** (t ∈ A ∩ B, same version) | MTM_T − MTM_{T−1} + Σ cash events | risk + theta (§6.2) |
| **amended** (t ∈ A ∩ B, version changed) | MTM_T − MTM_{T−1} + Σ cash events | `AMENDMENT` = PV_AFTER − PV_BEFORE, plus risk + theta of the **T−1** sensitivities (of the trade before the amendment, an approximation; open question 5) |
| **new** (t ∈ B, not A) | MTM_T + upfront (`AMOUNT_USD` of `NEW`, or `PREMIUM_SETTLED`) + fees | `NEW_TRADES` = the whole actual (day-one P&L) |
| **ended** (t ∈ A, not B: matured, cancelled, exercised, terminated, novated) | Σ cash on its end − MTM_{T−1} | `ENDED_TRADES` = the whole actual |
| **fees** on a continuing, amended or ended trade | in the actual | `FEES` = Σ `FEE` amounts (a new trade's fees are inside `NEW_TRADES`) |

```
UNEXPLAINED_t = ACTUAL_t − EXPLAINED_t
```

### 6.2 The risk-based explanation

For a continuing or amended trade with T−1 sensitivities and moves x_k (delta factors) and r_k (vega factors, relative):

```
DELTA_t,class = Σ_{k ∈ class} δ_{t,k} × x_k                      per risk class: GIRR, CSR, EQ, COMM, FX
GAMMA_t       = ½ Σ_k γ_{t,k} × x_k²
VEGA_t        = Σ_k ν_{t,k} × r_k                                ν = σ·∂V/∂σ, so ν × Δσ/σ is the first-order vol P&L
THETA_t       = θ_t                                              one day; for a span of n business days: θ × n (labelled)
EXPLAINED_t   = Σ_class DELTA_t,class + GAMMA_t + VEGA_t + THETA_t (+ AMENDMENT_t + FEES_t)
```

A sensitivity with no matching `market-move` row contributes 0 and is counted `unmovedFactors` (a data gap, not a zero move).

### 6.3 A scope

Every component sums over the scope's trades (resolved on **T**; ended trades by their T−1 book):

```
X(S) = Σ_{t ∈ S} X_t        X ∈ { ACTUAL, DELTA_<class>, GAMMA, VEGA, THETA, NEW_TRADES, AMENDMENTS, ENDED_TRADES, FEES, UNEXPLAINED }
```

### 6.4 Tolerance

```
within = |UNEXPLAINED(S)| ≤ max( abs-tolerance, rel-tolerance × |ACTUAL(S)| )        defaults 10,000 USD and 10%
```

Reported per scope, per child and per trade (the **top unexplained** list: the trades sorted by |UNEXPLAINED_t|). The FRTB IMA P&L
attribution test (Spearman correlation and Kolmogorov–Smirnov over 250 days of hypothetical and risk-theoretical P&L) is **not** this
measure (open question 6).

### 6.5 Hypothetical P&L and VaR

`HYPOTHETICAL(S)` = Σ over the trades of S alive at T−1 (continuing, amended, ended) of (ACTUAL_t − AMENDMENT_t − FEES_t): yesterday's
trades under today's market, no new trades, no amendments, no fees: the input VaR's backtest needs. Proposed: the official end-of-day capture of P&L explain provides each node's hypothetical P&L to VaR's official history
(replacing the stored `var-node.HYPO_PNL`; [VAR.md section 6.5](VAR.md#65-history-and-backtest)).

### 6.6 Settings (`drishti.pnl`, proposed)

| Setting | Default | Meaning |
|---|---|---|
| `drishti.pnl.enabled` | `true` | |
| `drishti.pnl.grid` | `SIMM` | the sensitivity rows used: `COMMON` + `SIMM` (finer IR tenors) or `COMMON` + `FRTB` |
| `drishti.pnl.gamma` | `true` | include ½γx² |
| `drishti.pnl.abs-tolerance` | `10000` | USD |
| `drishti.pnl.rel-tolerance` | `0.10` | of \|actual\| |
| `drishti.pnl.top-unexplained` | `50` | trades listed |
| `drishti.pnl.hot-pairs` | `2` | date pairs loaded |
| `drishti.pnl.access` | `{}` | VaR's model |

## 7. What Drishti builds

At the first request for T (or the load signal of any input), Java computes every trade's components **once** (one pass over the
T−1 sensitivity rows, each dotted with its factor's move) and stores them as columns by trade ordinal:

| Structure | Content | Size (1 M trades) |
|---|---|---|
| Trade components | float64 columns: `ACTUAL`, `DELTA_GIRR`, `DELTA_CSR`, `DELTA_EQ`, `DELTA_COMM`, `DELTA_FX`, `GAMMA`, `VEGA`, `THETA`, `AMENDMENT`, `NEW`, `ENDED`, `FEES`, `CASH`, `UNEXPLAINED`, `HYPOTHETICAL` (16) | 128 MB |
| Case per trade | a byte: continuing, amended, new, ended | 1 MB |
| Node aggregates | per node, the 16 sums | 5,000 × 128 B = 0.6 MB |
| Per-factor attribution (lazy) | for a scope asked `by=factor`: Σ δ x per factor, computed from the scope's net T−1 sensitivities and the moves | small |
| Moves | float64 by factor ordinal (the T−1 factor dictionary) | 2 MB |

The pass costs one multiply-add per sensitivity row (20 M rows: about 50 ms on one thread), so a restated input recomputes in seconds.
Trades of A ∪ B share one ordinal space for the pair (the T index plus the ended trades).

## 8. Scopes

Every scope of [VAR.md section 8](VAR.md#8-scopes) and its planned words, plus `INDEX:` and `TRDR:`. Resolved on T; an ended trade is
in a scope when it was in it on T−1. Fully additive: answered exactly from node aggregates and runs.

## 9. What Drishti answers

### 9.1 The API (proposed)

Base `/api/v1/pnl`, JSON, as the caller.

| Method and path | Parameters | Answer |
|---|---|---|
| `GET /scope` | `scope`, `asOf` (T), `from` (T−1 by default) | the headline (§9.2) |
| `GET /scope/waterfall` | the same | the steps from MtM(T−1) to MtM(T): + delta by class, gamma, vega, theta, amendments, new, ended, fees, unexplained, − cash received |
| `GET /scope/contributions` | `scope`, `asOf`, `from`, `by` (`child`, `book`, `position`, `trade`, `factor`), `sort` (`unexplained`, `actual`) | the parts with every component |
| `GET /scope/unexplained` | `scope`, `asOf`, `limit` | the top unexplained trades with their case, actual and explained |
| `POST /what-if` | `{scope, moves: [...] }` | the risk explain under other moves (a stress-like question: *what would yesterday's book have made with these moves*), Python |
| captures, compare, status, reload | | as VaR; captures `PECAP-<yyyymmdd>-<n>` (the sign-off record) |

### 9.2 The headline and the computed kind

Kind `pnl-attribution`, id `<canonical scope>~from=<T−1>` (default omitted). Proposed record:

| Field | Type | Meaning |
|---|---|---|
| `SCOPE`, `CANONICAL`, `BUSINESS_DATE`, `FROM_DATE` | text, date | T and T−1 |
| `MTM_FROM`, `MTM_TO` | amount | MtM of the scope's trades at T−1 and T |
| `ACTUAL`, `HYPOTHETICAL` | amount | §6.1, §6.5 |
| `EXPLAINED`, `UNEXPLAINED`, `UNEXPLAINED_RATIO`, `WITHIN_TOLERANCE` | amount, ratio, boolean | §6.4 |
| `DELTA_GIRR`, `DELTA_CSR`, `DELTA_EQ`, `DELTA_COMM`, `DELTA_FX`, `GAMMA`, `VEGA`, `THETA` | amount | §6.2 |
| `NEW_TRADES`, `AMENDMENTS`, `ENDED_TRADES`, `FEES` | amount | §6.1 |
| `COUNTS` | record | `CONTINUING`, `AMENDED`, `NEW`, `ENDED`, `MISSING_VALUATION`, `MISSING_SENSITIVITY`, `UNMOVED_FACTORS` |
| `CHILDREN` | record list | per child: `KEY`, `SCOPE`, `ACTUAL`, `EXPLAINED`, `UNEXPLAINED`, `WITHIN_TOLERANCE` |
| `TOP_UNEXPLAINED` | record list | `TRADE_ID`, `CASE`, `ACTUAL`, `EXPLAINED`, `UNEXPLAINED` |
| `PROVENANCE` | record | VaR's plus the batches of `valuation` (T−1, T), `sensitivity` (T−1), `market-move` (T), `pnl-event` (T) |

### 9.3 Reactive connectors and the Python engine (proposed)

```yaml
# config/connectors/pnl-engine.yaml                        (proposed, 2.9.0)
plugin: reactive
mode: reactive
description: P&L explain of any scope between two business dates
kinds:
  pnl-attribution:
    compute: pnl-attribution
    params:
      from: { type: date, required: false }                     # default: the previous business date
      by:   { type: enum, options: [child, book, position, trade, factor], default: child }
    result: { required: [SCOPE, BUSINESS_DATE, ACTUAL, UNEXPLAINED], types: { ACTUAL: number, UNEXPLAINED: number } }
engine:
  type: java
  entry: com.ash.drishti.compute.measure.pnl.PnlExplainEngine    # proposed: per-trade components at load, sums per scope
  version: "1.0"
triggers:
  - { on: data-version, kinds: [valuation, sensitivity, market-move, pnl-event, trade], affects: all }
coalesce: { quiet: 1s, max-wait: 10s }
cache: { results: 5000, ttl: 1d }
limits: { timeout: 30s, wait: 50ms, max-inflight-per-user: 4 }
```

What-if moves (`POST /what-if`) use the Python engine `drishti_risk.pnl:PnlWhatIfEngine` on the `risk` pool: inputs `net` (the scope's
T−1 net sensitivities from `sensitivity-net`, [FRTB.md section 9.5](FRTB.md#95-the-reactive-connectors-proposed)) and the moves as an
Arrow table (`MOVE_KEY`, `MOVE`); output the explain by class and factor. Nothing else in P&L explain needs Python.

### 9.4 Official end-of-day captures and the published table

When T is complete, Drishti captures every node of both hierarchies (the daily sign-off record) and publishes to
`${DRISHTI_DELTA_ROOT}/risk-results/pnl-official/` ([README.md section 11](README.md#11-what-drishti-answers-computed-kinds-the-api-captures),
product owner 2026-10-25): one row per node per business date T, the shared columns (`BUSINESS_DATE`, `HIERARCHY`, `NODE_ID`,
`NODE_NAME`, `PARENT_ID`, `LEVEL`, `CAPTURE_ID`, `RESULT_VERSION`, `INPUT_VERSIONS`, `COMPUTED_AT`, `CONTRACT_VERSION`) and:

| Column | Type | Meaning |
|---|---|---|
| `FROM_DATE` | DATE | T−1 |
| `MTM_FROM`, `MTM_TO` | DOUBLE | USD |
| `ACTUAL`, `HYPOTHETICAL`, `EXPLAINED`, `UNEXPLAINED` | DOUBLE | §6 |
| `UNEXPLAINED_RATIO`, `WITHIN_TOLERANCE` | DOUBLE, BOOLEAN | §6.4 |
| `DELTA_GIRR`, `DELTA_CSR`, `DELTA_EQ`, `DELTA_COMM`, `DELTA_FX`, `GAMMA`, `VEGA`, `THETA` | DOUBLE | §6.2 |
| `NEW_TRADES`, `AMENDMENTS`, `ENDED_TRADES`, `FEES`, `CASH` | DOUBLE | §6.1 |
| `TRADE_COUNT`, `NEW_COUNT`, `AMENDED_COUNT`, `ENDED_COUNT`, `MISSING_COUNT` | BIGINT | |

No trade rows are published.

## 10. Validation, errors and data quality

Codes are **proposed** (range `DRS-534x`).

| Check | Rule | Outcome |
|---|---|---|
| Dates | `market-move.FROM_DATE` = the T−1 of the sensitivities read | the pair is not computed: `422 DRS-5341` *the moves of <T> start on <d>, the sensitivities are of <T−1>* |
| Moves present | every factor of the T−1 sensitivities has a move | missing ones counted `unmovedFactors`; above 1% of factors the answer carries a warning |
| Move consistency | `MOVE` = `LEVEL_TO` − `LEVEL_FROM` (in bp), or their ratio − 1 (in %), within 10⁻⁶ | flagged |
| Amendments | `AMEND` events have both PVs; a version change without an `AMEND` event | the amendment is unexplained (counted `amendWithoutPv`) |
| Missing valuation | a trade alive on T−1 or T without a `valuation` row | the trade is left out of the actual and listed (`MISSING_VALUATION`); never taken as 0 |
| Missing sensitivity | a continuing trade without T−1 sensitivities | its whole actual is unexplained, counted `MISSING_SENSITIVITY` |
| Unique keys | `MOVE_KEY`, `EVENT_KEY` per date | load fails |
| Requests, rights | | `400 DRS-5001`, `403 DRS-5002` |

## 11. Entitlements and audit

VaR's model with `drishti.pnl.access`, gate kinds `valuation` and `sensitivity`. `by=trade` and the top-unexplained list need the
`positions` level. **Audit:** `pnl-view` for nodes, `pnl-capture` (the sign-off), `pnl-export`.

## 12. Performance and sizing

| Operation | Target (p95, warm, 1 M trades) |
|---|---|
| a node | 5 ms (node aggregates) |
| an ad-hoc scope of 100,000 trades | 20 ms (16 columns, one pass) |
| contributions by trade of a book (5,000 trades) | 30 ms |
| top unexplained of the bank | 100 ms (top-k) |
| `by=factor` for a desk | 100 ms (net sensitivities × moves) |
| computing a date pair | 3 s (20 M sensitivity rows, two valuation dates, events) |

## 13. Test data and generators

### 13.1 Planned

| Item | Plan |
|---|---|
| Dates | the pair 2026-09-29 → 2026-09-30 (sensitivities at 2026-09-29 from FRTB.md §13.1, valuations at both from TVAL.md §13.1) |
| `market-move` | `tools/packgen/risk/moves.py` (proposed): each factor's move = the VaR factor model's move of 2026-09-30 (the 510th element of `factors.py`'s paths), in the factor's unit |
| Consistency | the valuation generator moves each trade's MtM by its own day P&L (VaR path element 510) = delta × move + the nonlinear remainder + a small idiosyncratic term, so the explain is close and the unexplained realistic (and larger for options) |
| `pnl-event` | a few new trades on 2026-09-30 (with upfront and fees), five amendments (with both PVs), the trades that mature on 2026-09-30 (final cash), fees on 1% of trades |
| Size | about 10,000 trades; about 5,000 moves; a few hundred events: under 1 MB compressed |
| Reference test | `test_pnl_reference.py` (proposed): the worked example of §15 and brute-force sums |

### 13.2 How the retired figures were made

`tools/packgen/banking/data_results.py`, `market_results`, per book:

| Field | Rule |
|---|---|
| `ACTUAL` | Σ of the book's trades' `pnl1d`, where `pnl1d = MTM × N(0, 0.04)` (`data_trades.py`): a random number, not a change of MtM |
| `EXPLAINED` | `ACTUAL × U(0.85, 1.05)` |
| `UNEXPLAINED` | `ACTUAL − EXPLAINED` |
| `ATTRIBUTION[]` | fixed shares of `EXPLAINED`: Rates delta 42%, FX delta 12%, Credit 8%, Vega 10%, Carry and theta 20%, New trades 8% (the same for every book, a commodity book included) |
| `EXPLAIN_STEPS[]` | other fixed shares (`EXPLAIN_WEIGHTS`): Carry 14%, Roll-down 6%, Rates delta 42%, FX delta 12%, Credit 8%, Vol 10%, Theta −4%, New trades 12% (the last absorbs rounding), then Unexplained |
| `DATE` | the generator's as-of (2026-09-30), written into the 2026-07-08 snapshot |

### 13.3 What the current data shows (checked 2026-10-25)

- The record's two breakdowns **disagree**: in `PNL-COMM-1` (sample) *New trades* is −6,530 in `ATTRIBUTION` and −9,795 in
  `EXPLAIN_STEPS`; *Carry and theta* −16,326 against *Carry + Roll-down + Theta* −13,061. Both sum to `EXPLAINED`.
- Commodity books are explained 42% by rates delta and 0% by commodity risk.
- The valuations' `PNL_1D` is not the change of their MtM (TVAL.md §13.4), so the stored actual P&L cannot be reproduced from any
  committed MtM.
- 30 records, one per book; sample and test data differ by the universe's scaling (`PNL-COMM-1` actual −79,231 and −4,309,239).

## 14. Retiring the stored version

| Old | New |
|---|---|
| `PNL-<DESK>-<n>` (`PNL-COMM-1`, the book `BOOK-COMM-1`) | `PNL BOOK:BOOK-COMM-1` at T = the record's `DATE` (2026-09-30), T−1 = 2026-09-29 (`/v/pnl-attribution/BOOK:BOOK-COMM-1`) |
| `/v/pnl-explain/<id>` | the same, by redirect |

**Removed in 2.9.0:** the kind `pnl-explain` (`config/packs/market-risk/pack.yaml` kind, route, layout, form, id pattern `^PNL-`;
mnemonic `PNL` opens the computed page), `samples/pnl-explain.jsonl`, `data/testdata/market-risk/pnl-explain/`, the Sutra
`config/packs/market-risk/sutras/market-risk/pnl-explain.v1.sutra.yaml`, the Calc snippets `pnl_explain_quality.py` and
`pnl_attribution_mix.py`, the P&L part of `market_results` (and `pnl_explain` on the banking generator's trades), the contract
`pnl-explain.contract.yaml`, the about and README sections. **Guard test:** fails when `pnl-explain` returns or an input carries
`EXPLAINED`, `UNEXPLAINED`, `ATTRIBUTION`.

**Reconciliation.** The stored figures are random shares of a random number (§13.2): no reconciliation of values is possible. The
release notes show, per book, the stored `ACTUAL` beside the computed actual (MtM change plus cash) and the computed unexplained, and
state that the computed actual reconciles exactly to TVAL's MtM bridge ([TVAL.md section 6.3](TVAL.md#63-day-over-day-change)): for
every scope, ACTUAL − Σ cash − NEW-day upfronts = TVAL's `MTM_CHANGE` (a test).

## 15. A worked example

Book `BOOK-X`, T−1 = 2026-09-29, T = 2026-09-30. Moves: GIRR USD 5Y +12 bp; equity ACME −3%; ACME implied vol +4% of itself
(25% → 26%: r = 0.04); EUR (FX) +0.5%.

| Trade | Case | MtM T−1 | MtM T | Cash, events | T−1 sensitivities |
|---|---|---|---|---|---|
| A | continuing USD swap | 1,000,000 | 1,031,000 | | δ +2,500 per bp; γ −1 per bp²; θ +500 |
| B | continuing equity option | 200,000 | 185,000 | | δ +4,000 per 1%; γ +300 per (1%)²; ν +20,000; θ −2,000 |
| C | new | | 12,000 | upfront paid −10,000; fee −500 | |
| D | matured on T | 50,000 | | final cash received +50,200 | |
| E | amended (version 4 → 5) | 300,000 | 312,000 | `AMEND`: PV before 300,000, after 310,000 (T−1 market) | FX EUR δ +1,000 per 1%; θ +100 |

| Trade | Actual | Explained | Unexplained |
|---|---|---|---|
| A | 1,031,000 − 1,000,000 = 31,000 | delta 2,500 × 12 = 30,000; gamma ½ × (−1) × 12² = −72; theta 500 → 30,428 | 572 |
| B | 185,000 − 200,000 = −15,000 | delta 4,000 × (−3) = −12,000; gamma ½ × 300 × 9 = 1,350; vega 20,000 × 0.04 = 800; theta −2,000 → −11,850 | −3,150 |
| C | 12,000 − 10,000 − 500 = 1,500 | new trade 1,500 | 0 |
| D | 50,200 − 50,000 = 200 | ended 200 | 0 |
| E | 312,000 − 300,000 = 12,000 | amendment 10,000; FX delta 1,000 × 0.5 = 500; theta 100 → 10,600 | 1,400 |
| **Book** | **29,700** | **30,878** | **−1,178** |

By component: DELTA_GIRR 30,000; DELTA_EQ −12,000; DELTA_FX 500; GAMMA 1,278; VEGA 800; THETA −1,400; NEW_TRADES 1,500 (the new
trade's fee of −500 is inside it); FEES 0; ENDED_TRADES 200; AMENDMENTS 10,000; sum 30,878. **Tolerance:**
max(10,000, 10% × 29,700) = 10,000 ≥ 1,178: **within**. **Hypothetical P&L** (A, B, D, and E less its amendment):
31,000 − 15,000 + 200 + (12,000 − 10,000) = 18,200. **TVAL's bridge:** MTM_CHANGE = (1,031,000 + 185,000 + 12,000 + 312,000) − (1,000,000 +
200,000 + 50,000 + 300,000) = −10,000 = ACTUAL 29,700 − cash received 50,200 + cash paid 10,500 = −10,000. ✓

## 16. Open questions and the change table

### 16.1 Open questions

| # | Question | Context |
|---|---|---|
| 1 | Who delivers `market-move`: the market-data team, or the risk engine with its sensitivities (same curves, same conventions)? Or should Drishti derive moves from the market-data pack's curves (then the curve conventions must match the sensitivities')? | §3.2 |
| 2 | The source of `pnl-event` (fees, cash, amendment PVs): the risk engine, the settlement system, or the trade master's `EVENTS[]`? | §3.3 |
| 3 | Due time; product control's sign-off deadline | §3.5 |
| 4 | A trade that moves book between T−1 and T: in which scope does its P&L count (proposed: its T book; its T−1 book for ended trades)? | §4 |
| 5 | Amendments: are post-amendment T−1 sensitivities available (a more exact explain), or do we accept the pre-amendment approximation? | §6.1 |
| 6 | The FRTB IMA P&L attribution test (Spearman, KS over 250 days): in scope of a later release? It needs the risk-theoretical P&L per desk per day | §6.4 |
| 7 | Should P&L explain's hypothetical P&L become VaR's official backtest input (replacing `var-node.HYPO_PNL`)? | §6.5 |
| 8 | Revaluation-based (step-wise) explain from upstream steps (T−1 market and T time, then each risk class moved), beside the risk-based one? | §2 |

### 16.2 The change table

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-25 | First version (PLANNED for 2.9.0): `market-move` and `pnl-event`, per-trade attribution with additive node sums, tolerance, hypothetical P&L, the published `pnl-official` table, the retirement of `pnl-explain` |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
