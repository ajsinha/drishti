<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# STR, stress P&L: the data contract

*Version 1.0 · 2026-10-25 · **Status:** proposed (PLANNED for 2.9.0) · Shared rules: [README.md](README.md) · Model contract:
[VAR.md](VAR.md) · Sensitivities: [FRTB.md](FRTB.md#32-sensitivity-one-row-per-trade-and-risk-factor-the-shared-table)*

This is the planned data contract of **STR**: the P&L of any set of trades under a stress scenario, computed when asked, either by
**summing per-trade full-revaluation P&L** that the upstream engine delivers for the bank's defined scenarios, or by **applying a
scenario's shocks to the trades' sensitivities**, for any scenario, including one a user defines on the spot. It replaces the stored
kind `stress-result`. The scenario definitions (SCN, `stress-scenario`) stay **fundamental**; this contract extends their shocks so a
machine can apply them. Everything here is a **proposal** for the 2.9.0 build; each change will be recorded in
[section 16](#16-open-questions-and-the-change-table).

**Sources read (2026-10-25):** `config/packs/market-risk/contracts/{stress-result,stress-scenario}.contract.yaml`, the samples
`stress-result.jsonl` (80) and `stress-scenario.jsonl` (8), `data/testdata/market-risk/{stress-result,stress-scenario}/` (snapshot of
2026-07-08, every later file empty), `tools/packgen/banking/data_results.py` (`SCENARIOS`, `market_results`), the Calc snippets
`stress_from_sensitivities.py` and `stress_heatmap.py`.

## Contents

1. [Summary](#1-summary)
2. [The measure in one page](#2-the-measure-in-one-page)
3. [Inputs from upstream engines](#3-inputs-from-upstream-engines)
4. [Reference and master data read](#4-reference-and-master-data-read)
5. [Conventions specific to STR](#5-conventions-specific-to-str)
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

**What it is.** Stress P&L for any scope and scenario: the scope's total, its breakdown by child, book, position, trade or risk
factor, its use of the stress limit, and the **stress matrix** (every scenario of a set against the scope's children). Two methods,
side by side when both exist:

| Method | Inputs | Scenarios | Additive |
|---|---|---|---|
| **Full revaluation** | `scenario-pnl`: per trade × scenario P&L from the upstream engine | the bank's defined scenarios (the stress programme) | yes: a sum over trades |
| **Sensitivity-based** | `sensitivity` (delta, gamma, vega) and the scenario's shocks | any scenario: defined, edited or made up by a user on the spot | yes per scenario: Σ over trades of (sensitivity × shock); the shocks are fixed per scenario |

**Commands.** `STR <scope> <GO>` (`STR DESK COMM <GO>` opens the matrix; `STR DESK COMM SCN-COVID2020 <GO>` one scenario); `SCN`
stays the scenario page; the API `/api/v1/stress`; computed kinds `stress-pnl` and `stress-matrix`.

| Part | Status | Where |
|---|---|---|
| Input table `scenario-pnl` (trade × scenario full-revaluation P&L) | PLANNED | §3.2 |
| `stress-scenario` kept, with machine-readable shocks (additive change) | PLANNED | §3.3 |
| User scenarios (Drishti-owned, in the content store) | PLANNED | §3.4 |
| Stress limits in a `risk-limit` table (fundamental) | PLANNED | §3.5 |
| Per-node scenario vectors (Java), sensitivity × shock (Python, and Java for the defined set) | PLANNED | §6, §7 |
| Retirement of `stress-result`: `STR-<scenario>-<desk>` opens `STR DESK:DESK-<desk>` at `SCN-<scenario>` | PLANNED | §14 |

## 2. The measure in one page

**What it answers:** *what would this set of trades lose if this scenario happened, which parts lose most, and are we within the
limit?* For risk managers (the stress programme, limits), the board and regulators (named historical and hypothetical scenarios),
traders and desk heads (what-if scenarios built on the spot: *rates +50, equities −10*).

**The formula in words.**

1. **Full revaluation:** the upstream engine reprices every live trade under each defined scenario; a trade's stress P&L is the new
   value minus today's. A scope's stress P&L is the sum over its trades.
2. **Sensitivity-based:** a scenario is a list of shocks to risk factors (rates +200 bp, equities −34%, equity vol +45 points …). A
   trade's P&L is Σ delta × shock + ½ Σ gamma × shock² + Σ vega × relative vol shock over the factors the scenario shocks. A scope's
   is the sum.
3. **Limit use:** the scope's loss divided by its stress limit for that scenario (or for any scenario).
4. **The matrix:** every scenario of a set × the scope's children; the worst scenario and the worst child.

Instantaneous shocks: no time passes, no rebalancing, no carry; a scenario's `HORIZON` describes how its shocks were calibrated.

## 3. Inputs from upstream engines

### 3.1 The tables

```
${DRISHTI_DELTA_ROOT}/risk/
├── scenario-pnl/       business_date=YYYY-MM-DD/   one row per trade × defined scenario (full revaluation)      (PLANNED)
├── stress-scenario/    business_date=YYYY-MM-DD/   one row per scenario: the definition and its shocks          (exists; shocks extended)
├── risk-limit/         business_date=YYYY-MM-DD/   one row per limit: measure × scope × scenario                (PLANNED, fundamental)
└── sensitivity/        the shared table (FRTB.md §3.2)                                                          (PLANNED)
```

Retention 7 years; the load signal of each reloads the date (product owner, 2026-10-25).

### 3.2 `scenario-pnl`: one row per trade and scenario

**Grain:** one row per trade, defined scenario and business date. **Key:** `<TRADE_ID>~<SCENARIO_ID>`. **Partitioning:**
`business_date`; sorted by `SCENARIO_ID` then `TRADE_ID` (proposed: the engines often run scenario by scenario). **Rows:** live
trades × the scenarios of the date's stress programme (§3.3 `IN_PROGRAMME`); 1 M trades × 50 scenarios = 50 M rows a date (about
600 MB compressed, estimate). **Cadence:** daily, or weekly for an expensive programme (`STRESS_CADENCE` on the scenario); a scope asked
on a date without rows uses the latest date that has them and says so.

| Column | Type (Delta) | Req. | P | Unit, sign | Meaning | Example |
|---|---|---|---|---|---|---|
| `SCENARIO_PNL_KEY` | id (`id`) | required | P | | `<TRADE_ID>~<SCENARIO_ID>` | `MX-20000209~SCN-COVID2020` |
| `BUSINESS_DATE` | date | required | P | | the base date the trades were valued on | `2026-09-30` |
| `TRADE_ID` | id | required | P | | | `MX-20000209` |
| `SCENARIO_ID` | id | required | P | | a `stress-scenario` id | `SCN-COVID2020` |
| `SCENARIO_VERSION` | integer | required | P | | the scenario definition's version the engine applied (§3.3) | `3` |
| `PNL` | amount (DOUBLE) | required | P | USD, gain positive | V(shocked) − V(base), full revaluation | `-182340.0` |
| `BASE_MTM` | amount | optional | | USD | V(base): must equal the trade's `valuation.MTM` of the date (checked) | `4121280.0` |
| `STATUS` | text | required | P | | `ok`, `proxied` (a proxy model), `sensitivity` (the engine itself fell back to sensitivities), `failed` (`PNL` null) | `ok` |
| `BATCH_ID` | id | required | P | | one per scenario run and source system | `B-STR-2026-09-30-01` |
| `MODEL_VERSION`, `COMPUTED_AT`, `RESTATEMENT` | text, timestamp, integer | optional | | | | |

### 3.3 `stress-scenario`: definitions kept, shocks made machine-readable

The kind stays **fundamental** (SCN). Its current fields stay (`SCENARIO_ID`, `NAME`, `TYPE`, `SEVERITY`, `HORIZON`, `SHOCKS[]` with
`FACTOR` and `SHOCK`). Added (an additive, minor change of its contract):

| Field | Type | Req. | Meaning | Example |
|---|---|---|---|---|
| `VERSION` | integer | required (new) | +1 on every change of the shocks; `scenario-pnl` rows name the version they applied | `3` |
| `IN_PROGRAMME` | boolean | optional | the upstream engine revalues it (full revaluation rows expected) | `true` |
| `STRESS_CADENCE` | text | optional | `daily`, `weekly`, `monthly` | `daily` |
| `OWNER`, `APPROVED_BY`, `APPROVED_ON` | text, date | optional | governance | `market-risk`, `Risk Committee`, `2026-03-02` |
| `SHOCKS[].RISK_CLASS` | text | required for sensitivity use | `GIRR`, `CSR_NS`, `CSR_SEC`, `EQ`, `COMM`, `FX`, or `*` | `EQ` |
| `SHOCKS[].FACTOR_TYPE` | text | optional | as in `sensitivity` (`CURVE`, `SPREAD`, `SPOT`, `PRICE` …); empty: every type of the class | `SPOT` |
| `SHOCKS[].MEASURE` | text | optional | `delta` (default: the shock moves the factor) or `vega` (the shock moves the factor's implied volatility) | `delta` |
| `SHOCKS[].QUALIFIER` | text | optional | a value, a list (`USD,CAD`), a pattern (`*`), or a **group** (`G10`, `EM`, `IG`, `HY`, `ENERGY` …, defined in configuration) | `*` |
| `SHOCKS[].CURVE`, `SHOCKS[].BUCKET` | text | optional | narrow further (`FRTB_BUCKET`) | |
| `SHOCKS[].TENORS` | record list | optional | a tenor-dependent shock: `[{TENOR: 2Y, SHOCK: 25}, {TENOR: 10Y, SHOCK: 100}]`, linear in years between, flat outside | |
| `SHOCKS[].SHOCK_TYPE` | text | required for sensitivity use | `ABSOLUTE` (in `UNIT`) or `RELATIVE` (a fraction of the level) | `RELATIVE` |
| `SHOCKS[].UNIT` | text | required for `ABSOLUTE` | `bp` (rates, spreads), `vol-pt` (implied volatility points), `pct` (a percentage-point move of a quoted percentage) | `bp` |
| `SHOCKS[].SHOCK` | number | required | the move: −0.34 is −34% for `RELATIVE`; 200 is +200 bp for `ABSOLUTE` `bp` | `-0.34` |

**First match wins:** a factor takes the first shock whose selectors match it, so a specific shock (USD rates) is listed before a
general one (all rates). A factor no shock matches is not moved.

**The existing eight scenarios translated** (proposed; the current `SHOCK` values carry no unit, open question 1):

| Current `FACTOR`, `SHOCK` | Proposed selectors and shock |
|---|---|
| `Equities` −0.34 | `EQ`, `SPOT`, `*`, `RELATIVE`, −0.34 |
| `Credit spreads (IG)` 1.35 | `CSR_NS`, `SPREAD`, group `IG`, `ABSOLUTE`, `bp`, +135 (read as percentage points) |
| `USD rates` −1.2 | `GIRR`, `CURVE`, `USD`, `ABSOLUTE`, `bp`, −120 |
| `Oil` −0.65 | `COMM`, `PRICE`, group `OIL`, `RELATIVE`, −0.65 |
| `Equity vol` 45.0 | `EQ`, `*`, `MEASURE` `vega`, `ABSOLUTE`, `vol-pt`, +45 |
| `USD vs G10` 0.12 | `FX`, `SPOT`, group `G10`, `RELATIVE`, −0.12 (USD up 12% is each G10 currency's USD value down; open question 2) |
| `2Y rates` 0.25, `10Y rates` 1.00 | `GIRR`, `CURVE`, `*`, `TENORS` [2Y: +25, 10Y: +100] bp |
| `Breakevens` 0.80 | `GIRR`, `INFLATION`, `*`, `ABSOLUTE`, `bp`, +80 |

### 3.4 User scenarios (Drishti-owned)

A user defines a scenario on the page (or by API) with the same `SHOCKS[]` schema. It is **Drishti-owned** (README section 3), stored
in the content store at `stress/scenarios/<owner>/<name>.json` with versions, shared like a basket (users or roles, read-only), and
addressed as `USCN:<owner>/<name>` wherever a scenario id is accepted. It is computed by the sensitivity method only (no upstream
revaluation exists for it). Promotion into the programme is a governance step outside Drishti (the definition is exported as a
`stress-scenario` row for the upstream engine).

### 3.5 `risk-limit`: limits by measure and scope (fundamental)

The stored `stress-result` carried `LIMIT` (a negative number). Limits are fundamental, so they move to a table of their own,
proposed as **one limit table for every market-risk measure** (VaR's `LIMIT_VAR` on the hierarchy may join it later, open question 4):

| Column | Type | Req. | Meaning | Example |
|---|---|---|---|---|
| `LIMIT_ID` | id | required | | `LIM-STR-DESK-COMM-ANY` |
| `MEASURE` | text | required | `STR` (others later: `VAR`, `FRTB`, `DV01` …) | `STR` |
| `SCOPE` | text | required | a scope text (usually a node: `DESK:DESK-COMM`) | `DESK:DESK-COMM` |
| `SCENARIO_ID` | id | optional | the scenario it applies to; empty: **every** scenario (the worst is checked) | `` |
| `LIMIT` | amount | required | USD, **positive: the largest loss allowed** | `75700000` |
| `WARNING_AT` | ratio | optional | early warning (default 0.8) | `0.8` |
| `APPROVED_BY`, `VALID_FROM`, `VALID_TO` | text, date | optional | | |

## 4. Reference and master data read

| Master (kind) | Key | What for |
|---|---|---|
| `trade` (TRD), `risk-hierarchy` (HIER) | `TRADE_ID`, `NODE_ID` | live trades, nodes |
| `valuation` ([TVAL.md](TVAL.md#32-valuation-one-row-per-trade-per-business-date-planned)) | `TRADE_ID` | the base MtM (check of `BASE_MTM`; scenario value = base + P&L) |
| `stress-scenario` (SCN) | `SCENARIO_ID`, `VERSION` | definitions and shocks |
| qualifier groups (configuration) | group name | `G10`, `EM`, `IG`, `HY`, `OIL`, `METALS` … → qualifiers |
| `issuer`, `equity` masters | `QUALIFIER` | ratings and regions for groups (`IG`, `EM`) |

## 5. Conventions specific to STR

| Convention | Rule |
|---|---|
| P&L sign | gain positive, loss negative |
| Limit | positive (a loss amount); `LIMIT_USE` = max(−P&L, 0) / `LIMIT`; a gain uses 0% |
| Units of shocks | §3.3; applied to `sensitivity` amounts in their units ([FRTB.md section 3.2.1](FRTB.md#321-units-and-signs-of-amount_usd)) |
| Method shown | every figure says `full` or `sensitivity`; the two are never mixed in one total unless `fallback: sensitivity` is asked (§6.4), and then the count of trades on each method is shown |
| Currency | USD |

## 6. The computation

### 6.1 Full revaluation

```
STR_full(S, j) = Σ_{t ∈ S} PNL_{t,j}                     j a defined scenario; trades without a row counted `missing`
```

Additive: per node, a vector of P&L over the date's defined scenarios (§7).

### 6.2 Sensitivity-based, for a defined scenario

For each factor *k* of the date (the factor dictionary, [FRTB.md section 7.1](FRTB.md#71-the-factor-dictionary-shared)), the scenario
gives a move x_k in the factor's unit (0 when no shock matches): `bp` for bp factors; `100 × RELATIVE` for % factors (a −34% equity
shock is x = −34 units of 1%); for vega factors, the **relative** vol move r_k = `RELATIVE`, or `ABSOLUTE vol-pt` / `IMPLIED_VOL`
(points converted with the row's implied vol; no implied vol: the shock is not applied and counted `volUnconverted`).

```
P&L_t(j) = Σ_k delta_{t,k} × x_k  +  ½ Σ_k gamma_{t,k} × x_k²  +  Σ_k vega_{t,k} × r_k
STR_sens(S, j) = Σ_{t ∈ S} P&L_t(j) = Σ_k δ_k(S) x_k + ½ Σ_k Γ_k(S) x_k² + Σ_k ν_k(S) r_k
```

The second line is the point: with the scope's **net** sensitivities (additive, from the sensitivity store) a scenario costs one sparse
dot product, so any scenario on any scope is fast, and the per-node aggregate of a **defined** scenario is a number per node.

### 6.3 Sensitivity-based stress, scenarios defined on the spot

The same formula with a user's shocks (§3.4) or an edited copy of a defined scenario; computed by the Python engine (§9.5) on the
scope's net sensitivities (`sensitivity-net`, [FRTB.md section 9.5](FRTB.md#95-the-reactive-connectors-proposed)), or in Java for
simple shocks (open question 5). Breakdown by risk factor: δ_k x_k, ½Γ_k x_k², ν_k r_k per factor.

### 6.4 Combining and comparing

| Option | Meaning |
|---|---|
| `method=full` (default for a defined scenario) | full revaluation only; missing trades listed |
| `method=sensitivity` | sensitivity-based only |
| `method=both` | both totals and their difference per child (the **approximation error**: nonlinearity, cross effects, factors the shocks do not reach) |
| `fallback=sensitivity` | full revaluation where a row exists, sensitivity-based for the trades without (counted) |

### 6.5 The matrix and limits

`STR_matrix(S)` = the scenarios of a set (the programme, or a list) × the children of S: every cell by §6.1 or §6.2. **Worst
scenario** = argmin over j of STR(S, j). **Limit use** for scope S, scenario j: the limits whose `SCOPE` resolves to the same trade set
(canonical text match) and whose `SCENARIO_ID` is j or empty; `LIMIT_USE` = max(−STR(S, j), 0) / `LIMIT`; status `ok`, `warning`
(≥ `WARNING_AT`), `breach` (> 1).

### 6.6 Settings (`drishti.stress`, proposed)

| Setting | Default | Meaning |
|---|---|---|
| `drishti.stress.enabled` | `true` | |
| `drishti.stress.default-method` | `full` | for defined scenarios; user scenarios are always `sensitivity` |
| `drishti.stress.gamma` | `true` | include ½Γx² |
| `drishti.stress.grid` | `FRTB` | the sensitivity rows used (`COMMON` + `FRTB`) |
| `drishti.stress.groups` | the qualifier groups | §4 |
| `drishti.stress.max-scenarios` | `500` | scenarios per matrix |
| `drishti.stress.access` | `{}` | VaR's model |

## 7. What Drishti builds

| Structure | Content | Size (1 M trades, 50 defined scenarios) |
|---|---|---|
| Scenario-P&L matrix | float64 [trade ordinal × scenario], column-major (one column per scenario), NaN for missing | 1 M × 50 × 8 B = **400 MB** (float32 hot copy 200 MB, sums in float64, as VaR's tiering) |
| Node vectors (full) | per node, Σ over its trades per scenario | 5,000 nodes × 50 × 8 B = 2 MB |
| Node vectors (sensitivity, defined scenarios) | per node, STR_sens per defined scenario, computed at load from the node's net sensitivities | 2 MB |
| Shock vectors | per defined scenario, x_k and r_k over the factor dictionary (sparse) | small |
| The sensitivity store | shared ([FRTB.md section 7.2](FRTB.md#72-the-sensitivity-store-shared)) | shared |

A scope that is a node answers from its vectors; an ad-hoc scope sums its trades' columns (one pass per scenario asked) or, for the
sensitivity method, nets its sensitivities once and dots them with each scenario's shocks.

## 8. Scopes

Every scope of [VAR.md section 8](VAR.md#8-scopes) and its planned words, plus `INDEX:` and `TRDR:`
([TRADING_GROUPINGS.md](TRADING_GROUPINGS.md#81-words-added-proposed)). Everything in STR is additive over trades for a fixed scenario;
only the worst-scenario and limit-use figures are taken after the sum.

## 9. What Drishti answers

### 9.1 The API (proposed)

Base `/api/v1/stress`, JSON, as the caller.

| Method and path | Parameters | Answer |
|---|---|---|
| `GET /scope` | `scope`, `asOf`, `scenario` (an `SCN-` id or `USCN:`), `method`, `fallback` | the headline (§9.2) |
| `GET /scope/matrix` | `scope`, `asOf`, `scenarios` (a list, or `programme`), `by` (`child`, `book`, `desk`), `method` | cells, worst per row and column, limit status |
| `GET /scope/contributions` | `scope`, `asOf`, `scenario`, `by` (`child`, `position`, `trade`, `factor`) | parts sorted by P&L ascending |
| `GET /scenarios`, `GET /scenarios/{id}` | | the defined and the caller's user scenarios |
| `POST /scenarios`, `PUT /scenarios/{owner}/{name}`, `DELETE …` | body `{name, title, shocks, sharedWith}` | user scenarios (content store) |
| `POST /what-if` | `{scope, shocks}` (an unsaved scenario) | the headline and the factor breakdown |
| captures, compare, status, reload | | as VaR; captures `STCAP-<yyyymmdd>-<n>` keep the scenario definition and the scope's per-factor P&L |

### 9.2 The headline and the computed kinds

Kind `stress-pnl`, id `<canonical scope>~scenario=<id>` (and `~method=…`). Proposed record:

| Field | Type | Meaning |
|---|---|---|
| `SCOPE`, `CANONICAL`, `BUSINESS_DATE`, `SCENARIO_ID`, `SCENARIO_NAME`, `SCENARIO_VERSION` | text, date, integer | |
| `METHOD` | text | `full`, `sensitivity`, `both`, `full+fallback` |
| `PNL` | amount | the scope's stress P&L, USD |
| `PNL_FULL`, `PNL_SENSITIVITY`, `APPROXIMATION` | amount | with `both` |
| `LIMIT`, `LIMIT_USE`, `LIMIT_STATUS`, `LIMIT_ID` | amount, ratio, text | §6.5 |
| `CHILDREN` | record list | `KEY`, `SCOPE`, `LABEL`, `PNL` |
| `BY_FACTOR` | record list | (sensitivity) `RISK_CLASS`, `QUALIFIER`, `TENOR`, `MEASURE`, `SHOCK`, `PNL` (top 50) |
| `TRADES`, `MISSING`, `FAILED`, `ON_FALLBACK` | integer | |
| `PROVENANCE` | record | VaR's plus `scenarioPnl` batches, `sensitivities` batches, the scenario's version and hash |

Kind `stress-matrix`, id `<scope>~scenarios=programme`: `SCENARIOS[]`, `ROWS[]` (`KEY`, `LABEL`, `CELLS[]`), `WORST_SCENARIO`,
`WORST_PNL`, `BREACHES[]`.

### 9.3 Reactive connectors (proposed)

```yaml
# config/connectors/stress-engine.yaml                     (proposed, 2.9.0)
plugin: reactive
mode: reactive
description: Stress P&L of any scope: full revaluation sums (Java) and sensitivity shocks (Python)
kinds:
  stress-pnl:
    compute: stress-pnl
    params:
      scenario: { type: text, required: true }                      # SCN-… or USCN:owner/name
      method:   { type: enum, options: [full, sensitivity, both], default: full }
      fallback: { type: bool, default: false }
    result: { required: [SCOPE, BUSINESS_DATE, SCENARIO_ID, PNL], types: { PNL: number } }
  stress-matrix:
    compute: stress-matrix
    params:
      scenarios: { type: text, default: programme }
      by:        { type: enum, options: [child, book, desk], default: child }
engine:
  type: java
  entry: com.ash.drishti.compute.measure.stress.StressEngine         # proposed: full sums, defined-scenario sensitivity vectors, limits
  version: "1.0"
triggers:
  - { on: data-version, kinds: [scenario-pnl, sensitivity, stress-scenario, risk-limit], affects: all }
cache: { results: 5000, ttl: 1d }
limits: { timeout: 30s, wait: 50ms, max-inflight-per-user: 4 }
```

```yaml
# config/connectors/stress-whatif.yaml                     (proposed, 2.9.0)
plugin: reactive
mode: reactive
description: Sensitivity-based stress for scenarios defined on the spot
kinds:
  stress-whatif:
    compute: stress-whatif
    params:
      scenario: { type: text, required: false }                     # a USCN: id …
      sandbox:  { type: sandbox-ref, required: false }               # … or an unsaved scenario in a sandbox
reads:
  - { kind: sensitivity-net, as: net, id: "{id}~grid=FRTB" }
engine:
  type: python
  entry: drishti_risk.stress:StressEngine
  pool: risk
  version: "1.0"
limits: { timeout: 30s, wait: 100ms, max-inflight-per-user: 4 }
requires: { python: { version: "3.12", lock: requires/drishti-risk.lock } }
```

Python engine: inputs `net` (Arrow, the scope's net delta, gamma, vega per factor with `IMPLIED_VOL` where delivered, as a weighted
mean over the scope's trades: open question 3) and the scenario's shocks (JSON in the request); outputs one headline row and a
`factors` table (per factor: shock, δx, ½Γx², νr, P&L).

### 9.4 Official end-of-day captures and the published table

After a date's `scenario-pnl` (and `sensitivity`) are complete, Drishti captures every node × every programme scenario and publishes
to `${DRISHTI_DELTA_ROOT}/risk-results/stress-official/` ([README.md section 11](README.md#11-what-drishti-answers-computed-kinds-the-api-captures),
product owner 2026-10-25): **one row per node, scenario and business date** (the scenario is part of the row's grain), the shared
columns (`BUSINESS_DATE`, `HIERARCHY`, `NODE_ID`, `NODE_NAME`, `PARENT_ID`, `LEVEL`, `CAPTURE_ID`, `RESULT_VERSION`, `INPUT_VERSIONS`,
`COMPUTED_AT`, `CONTRACT_VERSION`) and:

| Column | Type | Meaning |
|---|---|---|
| `SCENARIO_ID`, `SCENARIO_VERSION`, `SCENARIO_NAME` | STRING, BIGINT, STRING | |
| `METHOD` | STRING | `full` (or `sensitivity` for a scenario outside the full programme) |
| `PNL` | DOUBLE | USD, gain positive |
| `PNL_SENSITIVITY` | DOUBLE | the sensitivity-based figure beside it (null when not computed) |
| `LIMIT`, `LIMIT_USE`, `LIMIT_STATUS` | DOUBLE, DOUBLE, STRING | §6.5 |
| `TRADE_COUNT`, `MISSING_TRADES` | BIGINT | |

User scenarios are never published. No trade rows are published.

## 10. Validation, errors and data quality

Codes are **proposed** (range `DRS-531x`).

| Check | Rule | Outcome |
|---|---|---|
| Scenario known | `SCENARIO_ID` exists in `stress-scenario` on the date | row rejected |
| Version | `SCENARIO_VERSION` = the definition's `VERSION` | the scenario's full figures flagged *stale definition* (`DRS-5311`) |
| Unique | one row per (`BUSINESS_DATE`, `TRADE_ID`, `SCENARIO_ID`) | load fails |
| Base | \|`BASE_MTM` − `valuation.MTM`\| ≤ max(1, 10⁻⁶ × \|MTM\|) | flagged `baseMismatch` |
| Finite, status | `PNL` finite; null ⇔ `failed` | row rejected |
| Shocks | every shock used by the sensitivity method has `RISK_CLASS`, `SHOCK_TYPE` (and `UNIT` when absolute) | the scenario is refused for the sensitivity method: `422 DRS-5312` *the shock 'Equities' has no risk class* |
| Unconverted vol shocks | an absolute vol shock on a factor without `IMPLIED_VOL` | not applied, counted `volUnconverted` |
| A scenario with nothing matched | no factor of the scope matches any shock | `200`, P&L 0, `matchedFactors: 0` and a warning |
| User scenario | name rules as baskets; shocks valid | `400 DRS-5001` |
| Rights, compute | | `403 DRS-5002`, `DRS-9202`/`9203` |

## 11. Entitlements and audit

VaR's model with `drishti.stress.access`, gate kinds `scenario-pnl` (full) and `sensitivity` (sensitivity method). User scenarios: the
owner and those it is shared with (as baskets, [VAR.md section 9.5](VAR.md#95-baskets)). **Audit:** `stress-view` for node scopes and
scopes of more than 10,000 trades, `stress-scenario-save` (user scenarios), `stress-capture`, `stress-export`.

## 12. Performance and sizing

| Operation | Target (p95, warm, 1 M trades, 50 scenarios) |
|---|---|
| one scenario, a node | 5 ms (node vector) |
| the matrix of a desk (50 scenarios × 20 books) | 50 ms |
| one scenario, ad-hoc scope of 100,000 trades (full) | 20 ms |
| a user scenario on a desk (sensitivity, Python) | 300 ms |
| a user scenario on the bank | 1 s |
| load of a date | 15 s (50 M rows) |

## 13. Test data and generators

### 13.1 Planned

| Item | Plan |
|---|---|
| `scenario-pnl` | `tools/packgen/risk/stress.py` (proposed): for each live trade (≤ 10,000) and each of the 8 scenarios, the full P&L = the sensitivity-based P&L (from the trade's generated sensitivities, FRTB.md §13.1, and the scenario's translated shocks) **plus** a deterministic nonlinear remainder for options (the factor model's short-gamma term at the shock size), so `method=both` shows a realistic approximation error |
| `stress-scenario` | the 8 scenarios with `VERSION` 1 and the structured shocks of §3.3 (generator: `SCENARIOS` in `data_results.py`, extended) |
| `risk-limit` | one `STR` limit per desk (any scenario) and a few per scenario; positive amounts |
| Dates | 2026-09-30 only (one date) |
| Size | 8 × 9,572 = about 77,000 rows, about 1 MB compressed |

### 13.2 How the retired figures were made

`tools/packgen/banking/data_results.py`, `market_results`, per desk and scenario:

| Field | Rule |
|---|---|
| `BY_BOOK[].PNL` | `round(−book_var(b) × severity × U(0.4, 1.3))`, severity 4.2 (Severe), 2.1 (Moderate), 0.9 (Mild): **always a loss**, unrelated to the scenario's shocks |
| `PNL` | Σ of the books |
| `LIMIT` | `−round(desk limit × 4, −5)` (the desk's random VaR limit × 4): the same limit for every scenario of a desk |
| `SHOCKS` | written on `stress-scenario` and never used to compute anything |

### 13.3 What the current data shows (checked 2026-10-25)

80 records (8 scenarios × 10 desks), all negative (from −1.19 m to −38.2 bn in the test data). `STR-RATES-UP200-COMM` loses 1.54 bn on
the commodity desk; `STR-RATES-UP200-RATES` loses 0.39 bn; nothing in the stored data can gain under a scenario. The sample and the
test data disagree by the universe's scaling (`STR-COVID2020-COMM`: −79.1 m in the sample, −3.04 bn in the test data; limit −75.7 m and
−3.09 bn). The shocks have no units (`USD rates` −1.2; `Equity vol` 45.0).

## 14. Retiring the stored version

### 14.1 Id mapping

The old id is `STR-<scenario suffix>-<desk suffix>`; the desk suffix is the last hyphen-separated part, the scenario suffix the rest:

| Old | New |
|---|---|
| `STR-COVID2020-COMM` | `STR DESK:DESK-COMM` at `SCN-COVID2020` (`/v/stress-pnl/DESK:DESK-COMM~scenario=SCN-COVID2020`) |
| `STR-RATES-UP200-RATES` | `DESK:DESK-RATES` at `SCN-RATES-UP200` |
| `STR-EM-CRISIS-FX` | `DESK:DESK-FX` at `SCN-EM-CRISIS` |
| `/v/stress-result/<id>` | the same, by redirect; an id whose scenario or desk does not resolve opens `STR BANK` with a notice |

### 14.2 What is removed in 2.9.0

| Item | Removed |
|---|---|
| Kind `stress-result` | `config/packs/market-risk/pack.yaml` kind, route, layout, form, id pattern `^STR-`, `graph.fields` entries; the mnemonic `STR` opens the computed page |
| Data | `samples/stress-result.jsonl`, `data/testdata/market-risk/stress-result/`, its `testdata.yaml` entries |
| Sutra | `config/packs/market-risk/sutras/market-risk/stress-result.v1.sutra.yaml` (the SCN Sutra stays, linking to the computed page) |
| Calc snippets | `stress_from_sensitivities.py`, `stress_heatmap.py` (replaced by the matrix and the factor breakdown) |
| Generator | the stress part of `market_results` |
| Contract | `stress-result.contract.yaml` (contracts for `scenario-pnl`, `risk-limit` and the computed kinds replace it; `stress-scenario`'s contract gains the fields of §3.3) |
| Guard test | fails when `stress-result` returns, or an input or fundamental kind carries `STRESS_PNL`, `BY_BOOK` figures |

### 14.3 Reconciliation

No relation exists between the stored and the computed numbers (§13.2). The release notes list, per desk and scenario on 2026-09-30,
the stored `PNL` and the computed full and sensitivity figures, and explain the differences: stored figures are random losses scaled by
a severity label; the computed ones apply the shocks to the trades (hedges and gains appear, a rates shock barely moves a commodity
desk). The old negative `LIMIT` becomes a positive `risk-limit` row per desk (the same absolute value), so limit use is comparable.

## 15. A worked example

Book `BOOK-X`: T1 a USD swap, T2 an equity option. Sensitivities (USD; units of FRTB.md §3.2.1):

| Trade | Factor | delta | gamma | vega (σ·∂V/∂σ) | `IMPLIED_VOL` |
|---|---|---|---|---|---|
| T1 | GIRR USD `CURVE` (one tenor here) | +10,000 per bp | −2 per bp² | | |
| T2 | EQ `SPOT` ACME | +30,000 per 1% | +2,000 per (1%)² | +50,000 | 0.25 |

Scenario `SCN-X` (defined, in the programme): USD rates +100 bp; equities −20% (`RELATIVE` −0.20); equity vol +10 points (`ABSOLUTE`
`vol-pt` 10).

| Trade | Term | Computation | P&L |
|---|---|---|---|
| T1 | delta | 10,000 × 100 | 1,000,000 |
| T1 | gamma | ½ × (−2) × 100² | −10,000 |
| T2 | delta | 30,000 × (−20) | −600,000 |
| T2 | gamma | ½ × 2,000 × (−20)² | 400,000 |
| T2 | vega | 50,000 × (10 points / 25 points) = 50,000 × 0.4 | 20,000 |
| **Book, sensitivity** | | 990,000 − 180,000 | **810,000** |

Full revaluation rows delivered: T1 +985,000, T2 −210,000 → **775,000**. `method=both`: approximation 810,000 − 775,000 = 35,000
(T1 5,000, T2 30,000: higher-order terms the sensitivities miss).

**Limits.** A `risk-limit` of 500,000 on `BOOK:BOOK-X` for any scenario; under `SCN-X` the book gains, so `LIMIT_USE` = 0. Under a
user scenario `USCN:asha/rates-down` (USD rates −100 bp, nothing else): T1 = 10,000 × (−100) + ½ × (−2) × (−100)² = −1,010,000; T2 = 0;
the book loses 1,010,000: `LIMIT_USE` = 1,010,000 / 500,000 = **2.02, breach**. Only the sensitivity method exists for the user
scenario.

**What is kept per node:** the book's full-revaluation vector over the programme ({SCN-X: 775,000, …}), its sensitivity vector over the
defined scenarios ({SCN-X: 810,000, …}), and its net sensitivities (for any other scenario).

## 16. Open questions and the change table

### 16.1 Open questions

| # | Question | Context |
|---|---|---|
| 1 | The units of the eight current scenarios' shocks (are `USD rates` −1.2 and `Credit spreads (IG)` 1.35 percentage points, i.e. −120 bp and +135 bp?) | §3.3 |
| 2 | FX shock convention: is "USD +15% vs all" each currency's USD value −15% (proposed), or −13.04% (1/1.15 − 1)? | §3.3 |
| 3 | Vega conversion for absolute vol shocks: per trade (`IMPLIED_VOL` on each row, exact) or from a scope-level mean (cheaper, approximate)? Proposed: per trade at load for defined scenarios; scope mean for what-if, labelled | §6.2, §9.3 |
| 4 | One `risk-limit` table for every measure (and VaR's `LIMIT_VAR` moved into it), or limits per measure? | §3.5 |
| 5 | Should simple user scenarios run in Java (no Python worker) for speed? | §6.3 |
| 6 | Cadence of the full-revaluation programme (daily for all scenarios?) and its due time | §3.2 |
| 7 | Reverse stress testing (the scenario that breaches a limit) and multi-step scenarios: out of 2.9.0? | §2 |

### 16.2 The change table

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-25 | First version (PLANNED for 2.9.0): `scenario-pnl`, structured shocks on `stress-scenario`, user scenarios, `risk-limit`, full and sensitivity methods, the published `stress-official` table, the retirement of `stress-result` |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
