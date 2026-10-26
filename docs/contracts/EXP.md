<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# EXP: exposure profiles (EE, EPE, EEPE, PFE), the data contract

*Version 1.0 · 2026-10-25 · Shared rules: [README.md](README.md) · Model contract: [VAR.md](VAR.md) · Netting and the shared
counterparty tables: [NSET.md](NSET.md)*

**Status:** proposed. PLANNED for 2.10.0, not built. Every table, column, setting, kind, endpoint and code below is a proposal; names
may change before the release, and each change will be recorded in [section 16](#16-open-questions-and-the-change-table).

This is the contract of Drishti's **exposure engine**: what an upstream exposure simulator delivers (every trade's MtM on every Monte
Carlo path at every date of a time grid), what Drishti builds from it (netting-set path matrices, collateral on each path, tiered
memory), and what it answers (EE, ENE, EPE, effective EE and EPE, PFE at any confidence, peaks, contributions, incremental figures before
dealing) for any scope. It defines the two **shared path tables**, `simulation-run` and `exposure-path`, which [CVA.md](CVA.md),
[LIM.md](LIM.md), [GRP.md](GRP.md) and [WWR.md](WWR.md) read too.

## Contents

1. [Summary](#1-summary)
2. [The measure in one page](#2-the-measure-in-one-page)
3. [Inputs from upstream engines](#3-inputs-from-upstream-engines)
4. [Reference and master data read](#4-reference-and-master-data-read)
5. [Conventions specific to exposure](#5-conventions-specific-to-exposure)
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

**What it is.** Counterparty credit exposure over time: on each simulated path, the netting set's value after netting and after the
collateral it would hold, floored at zero; then across paths the expected exposure (EE), expected negative exposure (ENE), the time
averages EPE and effective EPE (EEPE), and potential future exposure (PFE) at a confidence such as 95% or 99%, with the peak. For a
netting set, a counterparty, a connected group, a legal entity or any ad-hoc scope, and for a scope plus candidate trades (pre-deal).

**Commands.** `EXP NSET NS-ALDERSHOT-FRA <GO>`, `EXP CPTY CP-ALDERSHOT <GO>`, `EXP GROUP GRP-ALDERSHOT <GO>`; the API `/api/v1/exposure`.

**Retired in the same release:** the stored kind `exposure-profile` (`EXP-*` records with `EPE`, `EEPE`, `PFE_PEAK`, `BUCKETS[]`), and
the profile figures on `netting-set` (NSET.md section 14). Old ids open the computed page (section 14).

| Part | Status |
|---|---|
| `simulation-run` (one row per simulation run and date: the grid, the paths, the seed, the model) | PLANNED |
| `exposure-path` (one row per trade × grid date: a vector of MtM over the paths) | PLANNED |
| Netting per path, collateral per path with the margin period of risk, EE, ENE, EPE, EEE, EEPE, PFE, peaks, contributions | PLANNED |
| Tiered memory: netting-set path matrices in memory-mapped per-date files, trade paths read only for drills and what-ifs | PLANNED |
| Incremental exposure before dealing (exact with candidate paths of the same run; a conservative proxy without) | PLANNED |
| Computed kind `exposure-result`, reactive connector `exposure-engine` (Java) and `exposure-whatif` (Python) | PLANNED |

## 2. The measure in one page

**What it answers:** *how much could this counterparty owe us if it defaulted at a future date, on average and in a bad case, after
netting and collateral?* For credit officers (limits are set on PFE), CVA desks (EE feeds CVA), regulatory capital (EEPE under an
internal model), and traders (what a new trade does to a counterparty's PFE before they deal).

**In words:**
1. The upstream simulator draws P market paths (2,000 to 5,000 in a bank) on a time grid of G dates (50 to 100, including *look-back*
   dates a margin period of risk before the main ones), revalues every live trade on every path and date, and writes one row per trade
   and grid date: a vector of P MtM values. **Every trade uses the same paths** (path *p* is the same market for all trades), so trade
   values add per path.
2. Drishti sums the trades of each netting set per path and date: the **netting-set value** V(p, t).
3. On a margined netting set Drishti computes the **collateral** each path would hold at t, from the CSA's thresholds, minimum transfer
   amount and independent amount applied to the set's value at the look-back date t − MPOR (the collateral lags by the margin period of
   risk).
4. The **exposure** on a path is E(p, t) = max(V − C, 0); across netting sets of one counterparty exposures **add per path**.
5. **EE(t)** is the mean of E over the paths; **PFE_q(t)** is its q-quantile; **EPE** and **EEPE** are time averages over the first year.

**Methodology.** Monte Carlo exposure simulation post-processing as in Basel CRE53 (internal models method: EE, effective EE, EEPE,
alpha 1.4) and the industry's PFE practice for limits. Drishti does **not** simulate markets or price trades: the paths are inputs
(README.md section 3). QLRisk may later produce them ([PYTHON_COMPUTE.md](../architecture/PYTHON_COMPUTE.md)).

## 3. Inputs from upstream engines

### 3.1 Where they live

```
${DRISHTI_DELTA_ROOT}/risk/
├── simulation-run/   business_date=YYYY-MM-DD/                   one row per simulation run and date: the grid and the run's identity
├── exposure-path/    business_date=YYYY-MM-DD/run_id=<RUN_ID>/   one row per trade × grid date: MTM over the P paths  (the large table)
├── credit-path/      business_date=YYYY-MM-DD/run_id=<RUN_ID>/   optional: counterparty credit on the same paths (WWR.md)
└── netting-set/, netting-set-member/, csa/, trade-valuation/     NSET.md
```

| Kind | Key | Grain | Read by | Cadence | Due |
|---|---|---|---|---|---|
| `simulation-run` | `RUN_ID` | run × date | EXP, CVA, LIM, GRP, WWR | daily | before the paths (to be agreed) |
| `exposure-path` | `PATH_ROW_ID` | trade × grid date × run | EXP, CVA, LIM, GRP, WWR | daily | **to be agreed** (open question 1); a large bank's run often lands overnight, 04:00–07:00 local |

### 3.2 `simulation-run`: the run's identity and grid (reference row)

**Grain:** one row per run and business date. **Row count:** 1 per date (a second, *stressed* calibration run is open question 4).
The paths of `exposure-path` are ordered by this row exactly as the P&L vectors are ordered by the scenario set (VAR.md section 3.3).

| Column | JSON | Delta | Req. | Engine | Unit | Meaning | Example |
|---|---|---|---|---|---|---|---|
| `RUN_ID` | id | `id` STRING | required | R | | the run; stamped on every answer | `XR-2026-09-30-01` |
| `BUSINESS_DATE` | date | `business_date` DATE | required | R | | the close the run starts from (t₀) | `2026-09-30` |
| `MARKET_DATA_DATE` | date | doc | required | R | | the market data the run calibrated to (normally the business date) | `2026-09-30` |
| `MODEL` | text | doc | required | – | | the scenario model | `Hull-White 1F rates, GBM FX and equity, Schwartz 1F commodity` |
| `MODEL_VERSION` | text | doc | required | – | | | `xsim-7.3.1` |
| `CALIBRATION` | text | STRING | required | R | | `RISK_NEUTRAL` (CVA, pricing) or `REAL_WORLD` (PFE, limits); `STRESSED` for a stressed calibration | `RISK_NEUTRAL` |
| `PATHS` | integer | DOUBLE | required | R | | P, the length of every `MTM` vector | `5000` |
| `SEED` | text | doc | required | – | | the generator's seed; with `MODEL_VERSION`, market data and trade versions the run is reproducible upstream | `0x5eed-20260930` |
| `ANTITHETIC` | boolean | doc | optional | – | | paths 2k and 2k+1 are antithetic pairs | `false` |
| `GRID_DATES` | date list | doc | required | R | | the G grid dates, strictly increasing, all after `BUSINESS_DATE`; index *k* of every row's `GRID_INDEX` | `["2026-10-01", "2026-10-07", …]` |
| `GRID_ROLE` | text list | doc | required | R | | per grid date: `PRIMARY` (exposure is reported) or `LOOKBACK` (only for collateral) | `["LOOKBACK", "PRIMARY", …]` |
| `LOOKBACK_OF` | integer list | doc | required | R | | per grid date: for a `LOOKBACK` point, the index of the `PRIMARY` point it serves; −1 for a primary point | `[1, -1, …]` |
| `LOOKBACK_DAYS` | integer | doc | required | R | business days | the distance of every look-back point before its primary point (the run's MPOR) | `10` |
| `VALUE_BASIS` | text | doc | required | R | | `FUTURE` (each value in USD of its grid date; Drishti discounts for CVA) or `DISCOUNTED` (in today's USD by the run's numeraire) | `FUTURE` |
| `CURRENCY` | ccy | doc | required | R | | must be `USD` | `USD` |
| `PRECISION` | text | doc | required | R | | `float32` or `float64`: the `MTM` vectors' type (§5) | `float32` |
| `CREDIT_PATHS` | boolean | doc | optional | R | | the run also wrote `credit-path` rows (WWR.md) | `true` |
| `TRADES`, `ROWS` | integer | doc | required | R | | rows written, for completeness | `998412`, `41302877` |
| `BATCH_ID`, `COMPUTED_AT` | id, timestamp | STRING, doc | required | – | | | `B-2026-09-30-01` |

**The grid.** Recommended (not required): daily to 2 weeks, weekly to 3 months, monthly to 1 year, quarterly to 5 years, semi-annual to
10 years, annual to 30 years (about 50 primary dates), each with one look-back date `LOOKBACK_DAYS` before it, shared where two coincide.
Drishti reports exposure at primary dates only; t₀ (the business date) comes from `trade-valuation` (the current MtM), not from the paths.

### 3.3 `exposure-path`: every trade's MtM on every path (the large input)

**Grain:** one row per trade, grid date and run. **Key:** `PATH_ROW_ID` = `<RUN_ID>~<TRADE_ID>~<GRID_INDEX>`; natural key
(`BUSINESS_DATE`, `RUN_ID`, `TRADE_ID`, `GRID_INDEX`). **Sparse by maturity:** a trade has rows only for grid dates up to its last
cash flow; **an absent row means the trade is worth 0 on every path at that date** (allowed only after `MATURITY_DATE` + 10 business days,
otherwise the trade is incomplete, §10). **Row count:** Σ over trades of their live grid dates: about 40 million a date at 1,000,000 trades
and 50 primary + 50 look-back dates (average remaining maturity short of the grid's end).

**Why this grain** (decided here, open to review): one row per trade × grid date keeps a row to one vector of P floats (20 KB at P =
5,000 in float32, a good Parquet page), lets maturity drop rows instead of storing zeros, and lets Drishti read one grid date of a
netting set without the others. One row per trade with a P × G matrix would make 2 MB rows and store every zero after maturity.

| Column | JSON | Delta | Req. | Engine | Unit | Meaning | Example |
|---|---|---|---|---|---|---|---|
| `PATH_ROW_ID` | id | `id` STRING | required | – | | `<RUN_ID>~<TRADE_ID>~<GRID_INDEX>` | `XR-2026-09-30-01~CLY-4000879~7` |
| `BUSINESS_DATE` | date | `business_date` DATE | required | R | | partition | `2026-09-30` |
| `RUN_ID` | id | STRING (partition `run_id`) | required | R | | the `simulation-run` | `XR-2026-09-30-01` |
| `TRADE_ID` | id | STRING | required | R | | the booking system's id, text | `CLY-4000879` |
| `SOURCE_SYSTEM` | text | STRING | required | R | | | `CALYPSO` |
| `TRADE_VERSION` | text | doc | optional | – | | the trade version the simulator priced | `5` |
| `NETTING_SET` | id | STRING | required | R | | denormalised from `netting-set-member` (checked, NSET.md section 10); empty: standalone | `NS-ALDERSHOT-FRA` |
| `COUNTERPARTY` | id | STRING | required | R | | denormalised | `CP-ALDERSHOT` |
| `LEGAL_ENTITY` | id | STRING | required | R | | the trade's booking legal entity | `LE-FRA` |
| `GRID_INDEX` | integer | DOUBLE | required | R | | *k*, index into `GRID_DATES` | `7` |
| `GRID_DATE` | date | STRING | required | – | | `GRID_DATES[k]`, for people and checks | `2026-12-31` |
| `MTM` | vector | `list<float>` (or `list<double>`) | required | R | USD | the trade's MtM on each path at the grid date, bank's side, an asset positive, length P, path order of the run | `[1203455.5, -88213.0, …]` (5,000) |
| `CASHFLOW` | vector | `list<float>` | optional | R | USD | net cash flow the trade pays the bank between the previous grid date and this one, per path (positive received); for settlement risk and collateral spikes (open question 6) | `[0, 0, 25000.0, …]` |
| `STATUS` | text | STRING | required | R | | `ok`, `proxied` (an approximate model), `failed` (`MTM` null) | `ok` |
| `PRICER` | text | doc | optional | – | | | `xsim-hw1f-swap` |
| `BATCH_ID` | id | STRING | required | R | | the run's batch; equal to the `simulation-run`'s | `B-2026-09-30-01` |

**The vector** (README.md section 8, with one stated difference):

| Property | Contract |
|---|---|
| Type | `list<float>` (float32) **or** `list<double>`, as `PRECISION` of the run says. **Differs from README.md section 8** (float64 only): exposure paths are about 4,000 times VaR's vectors (§12.2), float32 halves the lake and keeps about 7 significant digits (a 50m MtM to about ±3 USD); every sum in Drishti is float64 |
| Length | P = `PATHS` of the run, every row of the run |
| Order | element *p* is path *p* of the run, the same market path for every trade and every `credit-path` row |
| Currency, basis | USD; `VALUE_BASIS` of the run |
| Missing | no `null`, `NaN` or infinity inside a vector; a trade the simulator could not value has `STATUS: failed` and `MTM` null on every row |

**Partitioning, sort and files (proposed):** partitioned by `business_date` and `run_id`; within a date, **sorted by `NETTING_SET`,
`TRADE_ID`, `GRID_INDEX`** so a netting set is contiguous and Drishti can aggregate it in one streaming pass; files about 512 MB,
row groups about 128 MB; `MTM` with Parquet `BYTE_STREAM_SPLIT` and ZSTD (level 3). Statistics on `NETTING_SET` and `GRID_INDEX`
allow pruning. The demo lake (`lake build`) writes the same columns with the writers' defaults.

### 3.4 Cadence, delivery and retention

| Item | Contract |
|---|---|
| Cadence | once per business date (the overnight exposure run); an intraday re-run is a new `RUN_ID` on the same date (the newest complete run answers; open question 4) |
| Complete when | every live member trade of every netting set has rows for every grid date up to its maturity (`ok`, `proxied` or `failed`), and `ROWS` of the run equals the rows written |
| One run | one `RUN_ID` and one `BATCH_ID` per run; a vector of another length rejects the row |
| Load signal | `POST /api/v1/packs/counterparty-risk/loads {kind: exposure-path, businessDate, batchId, rows}` reloads the date in the exposure engine automatically (product owner, 2026-10-25) |
| Restatement | a new `RUN_ID` (or the same `RUN_ID` rewritten whole); the old run stays readable by time travel |
| Retention | **7 years** was decided for input vectors (VaR, 2026-10-25). For exposure paths that is about 3.5 PB at a large bank: section 12.3 proposes what is kept at full resolution and what summarised (**open question 2 for the product owner**) |

## 4. Reference and master data read

| Kind | Key | Read for |
|---|---|---|
| `netting-set`, `netting-set-member`, `csa` (NSET.md) | `NETTING_SET`, `CSA` | membership (checks), margined or not, thresholds, MTA, IA, rounding, MPOR, IM |
| `collateral-holding` (COLL.md) | `NETTING_SET` | today's collateral (the `current` collateral model, independent amounts, IM held) |
| `trade-valuation` (`TVAL.md`) | `TRADE_ID` | t₀ exposure (today's MtM) and the check that path means start near it |
| `trade` | `TRADE_ID` | maturity (sparse rows), status |
| `counterparty`, `counterparty-group` | | `CPTY:` and `GROUP:` scopes (GRP.md) |
| `ir-curve` (market-data) | `CRV-USD-…` | discount factors when `VALUE_BASIS` is `FUTURE` and a discounted figure is asked (CVA.md) |

## 5. Conventions specific to exposure

| Convention | Rule |
|---|---|
| Exposure sign | E ≥ 0, positive when the counterparty owes the bank; ENE ≤ 0 (what the bank owes them), reported negative |
| Paths | P equally weighted paths; antithetic pairs are just paths |
| Quantile | PFE_q(t) = the ⌈P·q − 10⁻⁹⌉-th smallest exposure at t (`rank-rule: kth`, as VaR's), or interpolated (`rank-rule: interpolated`); q in (0.5, 1) |
| Time | years as ACT/365 from the business date; Δt_k = t_k − t_{k−1} over primary dates, t₀ = the business date |
| First year | for EPE and EEPE the averaging horizon is T = min(1 year, the longest remaining maturity in the netting set) (CRE53) |
| Precision | inputs float32 or float64; every sum and statistic in float64; the hot matrices float32 (§7) |
| Currency | USD; `VALUE_BASIS: FUTURE` values are not discounted for EE and PFE (exposure at the date, as limits want) |

## 6. The computation

### 6.1 Parameters (planned settings, `drishti.ccr.exposure.*`)

| Setting | Default | Meaning |
|---|---|---|
| `confidence` | `[0.95, 0.99]` | PFE confidences computed (any q may be asked per request) |
| `limit-confidence` | `0.95` | the PFE that LIM uses by default |
| `rank-rule` | `kth` | §5 |
| `collateral-model` | `lagged` | `lagged` (§6.3), `current` (today's collateral held constant), `none` |
| `im-model` | `current` | initial margin held kept at today's amount on every path, `none`; `SIMM.md` paths later |
| `alpha` | `1.4` | IMM EAD = alpha × EEPE (informational: §6.5) |
| `contribution-window` | `21` | paths around the PFE quantile averaged for PFE contributions (§6.6) |
| `hot-dates` | `2` | dates with mapped path matrices |
| `memory-budget-gb` | `0` (a quarter of the heap for statistics; matrices are mapped, not on the heap) | |
| `hot-folder` | `${drishti.data.dir}/ccr/hot` | where the per-date files go (local disk, §7.2) |

### 6.2 Netting per path

For netting set *n*, path *p* and grid index *k* (primary or look-back):

```
V_n(p,k) = Σ_{i ∈ n} MTM_i(p,k)                (absent row: 0; float64 accumulation)
```

A standalone trade (no enforceable netting set) is its own set. Additive across trades: the per-path sum is exactly VaR's vector sum,
applied G times.

### 6.3 Collateral on each path (margined netting sets)

With the CSA's terms (NSET.md section 3.5) converted to USD at today's FX (`TH_them`, `TH_us`, `MTA_them`, `MTA_us`, `IA`, rounding *r*),
and ℓ(k) the look-back point of primary point *k* (`LOOKBACK_OF`):

```
target(v)      = max(v − TH_them, 0) − max(−v − TH_us, 0) + IA          the collateral the CSA calls for at value v
lagged model   C_n(p,k) = R( target(V_n(p, ℓ(k))) )                     collateral at t_k = what was called at t_k − MPOR
               held side shortened by the counterparty's MTA:   if C > 0: C = max(C − MTA_them, 0)
R(x)           rounding of the CSA (deliveries up, returns down: applied to x as the change from today's collateral)
current model  C_n(p,k) = C_n(today)    (COLL.md's VM held − posted, constant)
none           C_n(p,k) = 0
IM             IM_n = IM held today (COLL.md) on every path (im-model: current); IM posted, segregated, adds nothing
E_n(p,k)       = max(V_n(p,k) − C_n(p,k) − IM_n, 0)
N_n(p,k)       = min(V_n(p,k) − C_n(p,k), 0)
```

When the CSA's `MPOR_DAYS` differs from the run's `LOOKBACK_DAYS`, Drishti uses the run's look-back points and flags the answer
(`MPOR_MISMATCH`, open question 3). With `MARGIN_FREQUENCY_DAYS` = F > 1 the effective MPOR is MPOR + F − 1 (CRE53): flagged the same way.
Unmargined sets: C = IA held (or 0).

### 6.4 Statistics of a scope

A scope's **exposure matrix** is the sum over the netting sets it covers of their exposure matrices (exposures add per path across netting
sets; partial sets are first recomputed from the trades in the scope, as NSET.md section 6.1):

```
E_S(p,k) = Σ_{n ∈ S} E_n(p,k)            N_S(p,k) = Σ_{n ∈ S} N_n(p,k)          over primary k only

EE(k)    = (1/P) Σ_p E_S(p,k)                       expected exposure             additive across netting sets
ENE(k)   = (1/P) Σ_p N_S(p,k)                       expected negative exposure    additive across netting sets
PFE_q(k) = the ⌈P·q⌉-th smallest of E_S(·,k)         potential future exposure     NOT additive (a quantile)
EEE(k)   = max(EEE(k−1), EE(k)), EEE(0) = EE(0)     effective EE (non-decreasing) NOT additive (a running max)
EPE      = Σ_{t_k ≤ T} EE(k) Δt_k / T                expected positive exposure   additive across netting sets (linear in EE)
EEPE     = Σ_{t_k ≤ T} EEE(k) Δt_k / T               effective EPE                NOT additive
PEAK_PFE = max_k PFE_q(k), and its date             MPFE                          NOT additive
PEAK_EE  = max_k EE(k)                                                            NOT additive
CE       = E at t₀ = Σ_n max(MTM_n − C_n − IM_n, 0)  current exposure (NSET.md)
```

EE(0), EEE(0) use the t₀ exposure (CE). For a counterparty or group with several netting sets, T for EPE and EEPE is the longest
remaining maturity in the scope, capped at 1 year. Sums and means in float64.

### 6.5 Regulatory figures (informational)

| Figure | Formula | Note |
|---|---|---|
| `EAD_IMM` | `alpha × EEPE` per netting set (alpha 1.4, CRE53) | only meaningful with supervisory approval of the bank's model; shown labelled *informational* |
| `EAD_IMM_STRESSED` | `alpha × max(EEPE, EEPE_stressed)` | needs a `STRESSED` run on the date (open question 4) |
| margined shortcut | EEPE of a margined set ≤ EEPE of the same set unmargined | checked and flagged |

### 6.6 Contributions (decomposition)

| Figure | Formula | Adds up to |
|---|---|---|
| EE contribution of trade *i* in set *n*, uncollateralised | `c_i(k) = (1/P) Σ_p MTM_i(p,k) · 1{V_n(p,k) > 0}` | EE_n(k) exactly (Euler, continuous marginal allocation) |
| EE contribution with collateral | `c_i(k) = (1/P) Σ_p (MTM_i(p,k) − w_i(p,k) C_n(p,k)) · 1{E_n(p,k) > 0}`, `w_i` = MTM_i / V_n on the path (0 when V_n = 0) | EE_n(k) |
| EPE contribution | Σ_k c_i(k) Δt_k / T | EPE |
| EE of a netting set in a counterparty | EE_n(k) | EE of the counterparty (additive) |
| PFE contribution of a part *j* | `−` mean of the part's E (sets) or MTM (trades) over the `contribution-window` paths ranked around the quantile at the peak date, scaled to add up to PEAK_PFE (as VaR's component VaR, VAR.md section 6.4) | PEAK_PFE |
| Incremental PFE of a part | PEAK_PFE(scope) − PEAK_PFE(scope without the part) | does not add up |

### 6.7 Pre-deal: incremental exposure

| Method | When | Computation |
|---|---|---|
| `paths` (exact) | the candidate's paths on **the same run** (same `RUN_ID`, same P, same path order) come with the request or from the upstream what-if service | the candidate's `MTM_c(p,k)` is added to its netting set's V, collateral and E recomputed, then the scope's figures; incremental = with − without, per figure and per primary date |
| `addon` (conservative proxy) | no candidate paths | the candidate is treated as its own netting set (no netting benefit, no collateral): `E_c(k) = max(MTM_c,0) + z_q · SF · d · δ · sqrt(min(t_k, M))` added to the PFE profile, with SA-CCR's supervisory factor SF, adjusted notional d, delta δ (SACCR.md section 6) and z_0.95 = 1.645; flagged `proxy` |

The `paths` method runs in the Python engine (§9.5) on the netting sets the candidate touches (2 MB per set matrix), well inside
interactive time.

### 6.8 Where it runs

| Work | Where | Why |
|---|---|---|
| Building V and E matrices per netting set at date load | Java (`drishti-compute`, planned `com.ash.drishti.compute.ccr.ExposureStore`), streaming the sorted `exposure-path` table, fanned out across the cluster's nodes by netting-set ranges | billions of float additions; SIMD loops; no copy to Python |
| Statistics of a scope (sum of matrices, means, quantiles) | Java | per request: sum of the scope's set matrices (P × G), then a sort of P values per date |
| What-if with candidate paths, other collateral models, stressed runs, custom quantile methods, path analytics | Python engine on the compute pool (`risk` pool), Arrow in and out | heavy or rarely used; the scope's few matrices are shipped, never the date |

## 7. What Drishti builds

### 7.1 In memory (per date, `CcrBook` plus the exposure store)

| Structure | Content | Size, large bank (100,000 sets, P = 5,000, G = 100: 50 primary + 50 look-back) |
|---|---|---|
| grid | dates, roles, look-back map, Δt | bytes |
| per set statistics | EE, ENE, PFE at each configured q, EEE over primary dates (float64) | 100,000 × 50 × 5 × 8 = **200 MB** |
| per counterparty statistics | the same over its sets (EE, ENE additive; PFE, EEE from the summed matrix, computed at load) | 10,000 × 50 × 5 × 8 = 20 MB |
| set index | set → offset in the hot file, CSA terms, flags | 10 MB |
| answer cache | recent answers (profiles, 50 points each) | 50 MB |

### 7.2 On disk: the hot exposure file per date (proposed)

`<hot-folder>/<YYYY-MM-DD>/<RUN_ID>.xhot`, little-endian, written once after the first load of the date, memory-mapped, the layout
style of VaR's hot file (VAR.md section 7.3): a header (magic `DRSHEXP1`, version, business date, P, G, primary count, sets, run id,
data version of the date's `exposure-path` files, batch id), a section table with CRC-32C, and sections:

| Section | Content | Size (100,000 sets, P = 5,000, G = 100) | Size (P = 2,000, G = 50) |
|---|---|---|---|
| `GRID` | grid dates, roles, look-back map | bytes | bytes |
| `SETS` | per set: id, counterparty, legal entity, CSA terms, offsets | 20 MB | 20 MB |
| `VMAT` | per set, V as **float32 P × G**, path-major within the set, 64-byte aligned (needed for what-ifs and other collateral models) | 100,000 × 5,000 × 100 × 4 = **200 GB** | 100,000 × 2,000 × 50 × 4 = **40 GB** |
| `EMAT` | per set, E (default collateral model) float32 P × G_primary | **100 GB** | 20 GB |
| `STAT` | the per set statistics of §7.1, float64 | 0.2 GB | 0.04 GB |

**What must stay on disk:** the matrices (100 to 300 GB a date at full size) are never on the heap: mapped pages only, the OS keeps what
is used. A counterparty's answer touches its own sets' `EMAT` (2 MB a set at P = 5,000 and 50 primary dates), so an interactive answer
reads megabytes. A bank-wide PFE (all 100,000 sets summed per path) is a 100 GB scan: a batch job (BI run), not interactive.

**Sharding (cluster):** each node maps the sets of the counterparties it owns (contiguous ranges of counterparties, aligned to groups
so a group is on one node where possible), two replicas; a group spanning nodes is summed by the coordinator (each node returns its
partial E matrix of P × 50 float64 = 2 MB).

**Trade paths** (1 to 4 TB a date) stay **in the lake only**. They are read for: contributions by trade (§6.6), a partial scope (a desk's
share of a set), and the removal of a trade in a what-if. One netting set's trades are contiguous in the lake's sort order, so such a read
is a range of row groups (a set of 1,000 trades at P = 5,000 and 100 dates: 2 GB float32, about 2 seconds from local NVMe; a
remote object store slower: open question 5 for a per-set trade matrix in the hot file).

### 7.3 Load of a date

1. Read `simulation-run`; check P, G, roles.
2. Stream `exposure-path` in the lake's sort order (netting set, trade, grid index), one set at a time: accumulate V in float64 per set,
   write float32 to `VMAT`, compute C and E per §6.3, write `EMAT`, compute the set's statistics.
3. Sum each counterparty's set matrices for its statistics (and each group's, GRP.md).
4. Publish the date (the previous date stays until no request uses it). A load is one job per node over its shard; progress on Admin →
   Data loads.

### 7.4 Caching

Answers cached by (canonical scope, date, run id, data versions, parameters, view signature), at most 1,000; the data-load signal of
`exposure-path`, `simulation-run`, `netting-set-member`, `csa` or `collateral-holding` reloads the date and drops its answers.

## 8. Scopes

Scope grammar of VAR.md section 8 with the counterparty terms of NSET.md section 8 (`NSET:`, `CPTY:`, `GROUP:`, `LE:`).

| Scope | How the exposure matrix is formed | Cost |
|---|---|---|
| `NSET:<id>` | the set's `EMAT` (statistics precomputed) | microseconds |
| `CPTY:<id>` | Σ of its sets' `EMAT` (statistics precomputed at load) | microseconds; a few ms for contributions |
| `GROUP:<id>` | Σ of its counterparties' sets | ms |
| `LE:<id>` | EE, ENE, EPE: sums of set statistics; PFE: a batch job | ms / job |
| a scope that cuts sets (`DESK:RATES & CPTY:CP-X`) | per touched set, V recomputed from the scope's trades (lake read), then collateral and E as if those trades were the set (`partial`) | seconds |
| `TRD:<ids>` (a trade alone) | the trade as its own set, uncollateralised | ms (lake read) |

**Additive:** V per path across trades; E per path across netting sets; EE, ENE, EPE across netting sets. **Not additive:** PFE, peaks,
EEE, EEPE, contributions to them.

## 9. What Drishti answers

### 9.1 The computed kind `exposure-result`

**Id:** the canonical scope text, with parameters after `~` as reactive kinds do (`CPTY:CP-ALDERSHOT~q=0.99`).

| Field | Type | Unit | Meaning |
|---|---|---|---|
| `SCOPE`, `CANONICAL`, `SCOPE_KIND`, `LABEL`, `BUSINESS_DATE` | text, date | | as NSET |
| `RUN_ID`, `PATHS`, `CALIBRATION` | text, integer, text | | the run |
| `CONFIDENCE` | rate | | q of the PFE fields |
| `CE`, `EPE`, `EEPE`, `PEAK_EE`, `PEAK_PFE`, `PEAK_PFE_DATE` | amount, date | USD | §6.4 |
| `EAD_IMM` | amount | USD | informational (§6.5) |
| `MPOR_DAYS`, `COLLATERAL_MODEL` | integer, text | | what was applied |
| `PROFILE` | record list | | per primary date: `TENOR` (label, `1M`), `DATE`, `T` (years), `EE`, `ENE`, `EEE`, `PFE` (at `CONFIDENCE`), `PFE_99`, `EE_UNCOLL` |
| `BY_NETTING_SET` | record list | | for a counterparty or larger: `NETTING_SET`, `EPE`, `PEAK_EE`, `PEAK_PFE`, `PFE_CONTRIBUTION` |
| `FLAGS` | text list | | `partial`, `MPOR_MISMATCH`, `proxy`, `incomplete`, `stale-run` |
| `PROVENANCE` | record | | NSET's plus `RUN_ID`, `MODEL`, `MODEL_VERSION`, `SEED`, `FAILED_TRADES`, `PROXIED_TRADES`, `TIER` (`hot`, `lake`) |

Standard tenor labels in `PROFILE`: the primary grid dates, labelled by the nearest of `1W`, `2W`, `1M`, `2M`, `3M`, `6M`, `9M`, `1Y`, …,
`30Y` (a grid date between labels keeps its date).

### 9.2 The API (`/api/v1/exposure`, planned)

| Method and path | Parameters | Answer |
|---|---|---|
| `GET /scope` | `scope`, `asOf`, `q` (0.95), `collateral` (`lagged`), `run` (newest complete) | §9.1 |
| `GET /scope/profile` | as above, `dates` (`primary` or `all`) | `PROFILE` only, for charts |
| `GET /scope/distribution` | `scope`, `date` (a grid date), `bins` (42) | the exposure distribution at the date: `histogram {edges, counts}`, `quantiles {0.5, 0.9, 0.95, 0.975, 0.99}`, `mean`, `zeroShare` |
| `GET /scope/contributions` | `scope`, `by` (`child`, `netting-set`, `trade`, `asset`), `figure` (`EE`, `EPE`, `PFE`), `date` | rows `{key, scope, label, kind, value, pct, incremental}` (§6.6) |
| `POST /what-if` | body `{scope, candidates: [{tradeId, nettingSet, counterparty, paths: {runId, arrow: <base64 Arrow IPC of FixedSizeList<float32>[P] × G> } or {upstreamRef} }], method: paths|addon, q}` | `{base, with, incremental: {epe, peakPfe, profile[]}, method, flags}` |
| `POST /captures`, `GET /captures[/{id}[/csv]]`, `GET /compare` | as VaR | captures of exposure answers (the profile and, optionally, the scope's E matrix summary, never the trade paths) |
| `GET /status`, `POST /reload` | | runs loaded, hot files, sizes |

### 9.3 CSV

`GET /scope/profile?format=csv`: `tenor,date,t,ee,ene,eee,pfe,pfe_99,ee_uncoll`. Captures' CSV: the `# field: value` lines, then the
profile.

### 9.4 The reactive connector (proposed file, Java engine)

```yaml
# config/connectors/exposure-engine.yaml                        (planned, 2.10.0)
plugin: reactive
mode: reactive
description: Exposure profiles (EE, EPE, EEPE, PFE) for any counterparty scope, from the exposure paths
kinds:
  exposure-result:
    compute: exposure
    params:
      q:          { type: decimal, default: 0.95, min: 0.5, max: 0.9999 }
      collateral: { type: enum, options: [lagged, current, none], default: lagged }
      run:        { type: text, default: "" }                    # empty: the newest complete run of the date
    result: { required: [EPE, PEAK_PFE, PROFILE], types: { EPE: number, PEAK_PFE: number } }
reads:
  - { kind: simulation-run, as: run, all: true }
  - { kind: exposure-path, as: paths, vectors: [MTM], all: true }   # pinned by data version; read by the engine's store, not per request
  - { kind: netting-set-member, as: members, all: true }
  - { kind: csa, as: csas, all: true }
  - { kind: collateral-holding, as: holdings, all: true }
engine:
  type: java
  entry: com.ash.drishti.compute.ccr.ExposureEngine
  version: "1.0"
triggers:
  - { on: data-version, kinds: [simulation-run, exposure-path, netting-set-member, csa, collateral-holding], affects: all }
coalesce: { quiet: 1s, max-wait: 10s }
cache: { results: 1000, ttl: 1d }
limits: { timeout: 60s, wait: 50ms, max-inflight-per-user: 4, max-scope-netting-sets: 20000 }
```

### 9.5 The Python engine (what-if and analytics)

```yaml
# config/connectors/exposure-whatif.yaml                        (planned, 2.10.0)
plugin: reactive
mode: reactive
kinds:
  exposure-whatif:
    params:
      sandbox: { type: sandbox-ref, required: true }               # the candidate trades and their paths
      q:       { type: decimal, default: 0.95, min: 0.5, max: 0.9999 }
      method:  { type: enum, options: [paths, addon], default: paths }
reads:
  - { kind: exposure-result, as: base, id: "{id}" }
engine:
  type: python
  entry: drishti_ccr.exposure:ExposureWhatIfEngine
  pool: risk
  version: "1.0"
limits: { timeout: 30s, wait: 100ms, max-inflight-per-user: 2 }
requires: { python: { version: "3.12", lock: requires/drishti-ccr.lock } }
```

The engine interface (worker SDK, PYTHON_COMPUTE.md section 8), planned package `compute/drishti-ccr`:

```python
# compute/drishti-ccr/src/drishti_ccr/exposure.py               (planned, 2.10.0)
from drishti_compute import Engine, Request, Inputs, Progress, Result

class ExposureWhatIfEngine(Engine):
    kinds = ("exposure-whatif",)

    def compute(self, req: Request, inputs: Inputs, progress: Progress) -> Result:
        grid = inputs.arrow("grid")          # GRID_INDEX int32, T float64, ROLE string, LOOKBACK_OF int32
        sets = inputs.arrow("set_paths")     # NETTING_SET string, GRID_INDEX int32, V fixed_size_list<float32>[P]  (the touched sets only)
        csa = inputs.arrow("csa")            # NETTING_SET, TH_THEM, TH_US, MTA_THEM, MTA_US, IA, ROUNDING, MPOR_DAYS, IM_HELD (USD, float64)
        cand = inputs.arrow("candidates")    # TRADE_ID, NETTING_SET, GRID_INDEX, MTM fixed_size_list<float32>[P]
        ...                                  # V' = V + candidates; C, E per section 6.3; statistics per section 6.4
        return Result(rows=[{"EPE": ..., "PEAK_PFE": ..., "INCREMENTAL_PEAK_PFE": ...}],
                      tables={"profile": profile_table})   # TENOR, DATE, T, EE, ENE, EEE, PFE, EE_BASE, PFE_BASE
```

Deterministic (no random numbers); a missing candidate path or a different P is `ComputeError("the candidate's paths are not of run
XR-…: 2,000 paths, the run has 5,000")`.

### 9.6 Published official results (`exposure-official`, `exposure-official-cpty`)

The two tables of [NSET.md section 9.5](NSET.md#95-published-official-results-nset-official-nset-official-cpty) (node rows; restricted
netting-set, counterparty and group rows), with the shared columns and these figures:

| Column | Type | Meaning |
|---|---|---|
| `RUN_ID`, `PATHS`, `CALIBRATION` | STRING, BIGINT, STRING | the run |
| `CE`, `EPE`, `EEPE`, `PEAK_EE`, `PEAK_PFE_95`, `PEAK_PFE_99`, `EAD_IMM` | DOUBLE | USD (§6.4, §6.5) |
| `PEAK_PFE_DATE` | DATE | |
| `PFE_95_1Y`, `PFE_95_5Y`, `EE_1Y`, `EE_5Y` | DOUBLE | the profile at standard tenors, for warehouse queries without arrays |
| `PROFILE` | STRING | the profile (`TENOR`, `DATE`, `EE`, `ENE`, `EEE`, `PFE_95`, `PFE_99`) as JSON text: the shared table has no vector columns |
| `NETTING_SETS`, `FAILED_TRADES` | BIGINT | |

Node rows: EE, ENE and EPE are sums over the node's netting sets; PFE and EEPE of a node sum the paths of its netting sets (a batch step of
the official capture, §7.2), so a node's PFE is the PFE of everything it faces, not a sum of PFEs. No paths are published.

## 10. Validation, errors and data quality

| Check | Rule | Outcome |
|---|---|---|
| Vector length | `len(MTM) = PATHS` of the run | row rejected |
| Finiteness | no `null`, `NaN`, infinity | row rejected |
| Grid | `GRID_DATES` increasing, after the business date; every `LOOKBACK` maps to a `PRIMARY`; `LOOKBACK_OF` lengths agree | run not loaded |
| Index | `0 ≤ GRID_INDEX < G`; `GRID_DATE` = `GRID_DATES[GRID_INDEX]` | row rejected |
| Unique | one row per (`RUN_ID`, `TRADE_ID`, `GRID_INDEX`) | load fails |
| Sparse rows | no row after maturity is fine; a missing row **before** `MATURITY_DATE` + 10 business days | trade `incomplete`, its set flagged |
| Membership | `NETTING_SET` equals `netting-set-member` | membership wins, flagged |
| Coverage | every live member trade has rows | missing trades counted, sets flagged `incomplete` |
| t₀ consistency | the mean of `MTM` at the first grid date within a tolerance of the trade's `trade-valuation.MTM` (default 5% of notional-scaled vol, or 10% of |MTM| when larger) | warning listed per trade (an upstream mismatch of trade version or market data) |
| One run | one `BATCH_ID` per run; `RUN_ID` matches the run row | run not loaded; previous answers with a warning |

**Proposed DRS codes** (5340–5349): `DRS-5340` (400, bad scope or parameter: `q`, `collateral`, `date` not a grid date), `DRS-5341`
(409, the date's run is inconsistent or incomplete and the request asked `strict=true`), `DRS-5342` (400, what-if candidates not on the
run: P or grid differ), `DRS-5343` (503, the scope needs a batch job: a bank-wide PFE; the answer names the job), `DRS-1001` (404, no
run for the date), `DRS-1003` (502, unreadable input).

## 11. Entitlements and audit

As NSET.md section 11 (counterparty coverage, restricted list, masks, aggregate-only). In addition: a role at `aggregate` for a
counterparty sees its profile but not `BY_NETTING_SET` rows below its level nor trade contributions; captures never store trade paths;
the what-if sandbox is private (REACTIVE_CONNECTOR.md); `ccr-view`, `ccr-whatif` and `ccr-capture` are audited with scope, run and
counts.

## 12. Performance and sizing

### 12.1 Targets (p95, warm)

| Operation | Target |
|---|---|
| a netting set or counterparty profile (precomputed statistics) | 10 ms |
| a group of 20 counterparties (sum of their matrices, P = 5,000, 50 dates) | 150 ms |
| contributions by netting set of a counterparty | 100 ms |
| contributions by trade of a set of 1,000 trades (lake read) | 5 s |
| pre-deal, exact, one candidate on a set of 1,000 trades | 1 s |
| pre-deal, proxy | 50 ms |
| load a date, large bank, 8 nodes (2 TB float32 of paths) | 30 minutes, shown as a job |

### 12.2 Sizes

| Item | Formula | P = 2,000, G = 50 | P = 5,000, G = 100 |
|---|---|---|---|
| trade paths (lake), dense, 1,000,000 trades | trades × P × G × 4 bytes (float32) | 400 GB | 2.0 TB |
| the same, float64 | × 2 | 800 GB | 4.0 TB |
| sparse by maturity (about 40% of grid dates live on average, a proposal to verify) | × 0.4 | 160 GB | 0.8 TB |
| netting-set V matrices (hot), 100,000 sets | sets × P × G × 4 | 40 GB | 200 GB |
| netting-set E matrices (hot), primary dates | sets × P × G/2 × 4 | 20 GB | 100 GB |
| statistics per set | sets × G/2 × 5 × 8 | 0.1 GB | 0.2 GB |
| for comparison: VaR vectors, 1,000,000 trades | trades × 509 × 8 | 4.07 GB | |

### 12.3 Seven-year retention: cost and a proposal (open question 2)

The product owner decided 7 years for VaR's input vectors. 7 years is about 1,764 business dates:

| What | Per date (P = 5,000, G = 100) | 7 years | Proposal |
|---|---|---|---|
| trade paths, float32, dense | 2.0 TB | **3.5 PB** | keep at full resolution for **20 business days** and **each month-end for 1 year** (about 32 dates, 64 TB); older dates: dropped, reproducible upstream from the run record (`MODEL_VERSION`, `SEED`, market data date, trade versions) |
| trade paths, sparse | 0.8 TB | 1.4 PB | as above (26 TB) |
| netting-set V matrices | 200 GB | 353 TB | kept **month-ends for 7 years** (84 × 200 GB = 17 TB) and every date for 1 year (50 TB) |
| statistics per set and counterparty (EE, ENE, EEE, PFE 95/99 per primary date), float64 | 0.22 GB | 0.4 TB | **every date, 7 years** |
| trade EE contributions per primary date, float32 | 1,000,000 × 50 × 4 = 0.2 GB | 0.35 TB | **every date, 7 years** (audit of who drove the exposure) |
| official end-of-day captures (counterparty profiles) | about 0.05 GB | 0.1 TB | 7 years |
| `simulation-run` rows | KB | MB | 7 years (reproducibility) |

At P = 2,000 and G = 50 every number above is a fifth. Under the proposal the standing storage is about 150 TB at full size (instead of
3.5 PB); the question for the product owner is whether trade-level paths older than the windows above may be summarised (open question 2).

## 13. Test data and generators

| Item | Plan (deterministic, small: at most 10,000 trades locally) |
|---|---|
| Generator | `tools/packgen/ccr/paths.py` (planned), called by `make_ccr_data.py` |
| Model | one standard-normal factor walk per asset class (rates, FX, credit, equity, commodity), correlated with a fixed matrix; for trade *i* in class *c*: `MTM_i(p,k) = A_i(t_k) · (MTM_i + s_c · N_i · (ρ X_c(p,k) + sqrt(1−ρ²) ε_i(p,k)))`, `A_i` the amortisation (1 − t/M_i, 0 after maturity), `s_c` a class volatility per unit notional, `N_i` the USD notional, ρ = 0.7; Python's `Random` seeded by text (`ccr|<RUN_ID>|<class>`, `ccr|<TRADE_ID>`) |
| Grid | 13 primary dates (`1W`, `2W`, `1M`, `2M`, `3M`, `6M`, `9M`, `1Y`, `2Y`, `3Y`, `5Y`, `7Y`, `10Y`) and 13 look-back dates 10 business days before each |
| Committed | **8 netting sets** chosen to cover margined and unmargined, positive and negative MtM, one SFT set, one wrong-way set: about 600 trades × 200 paths × 26 dates = 3.1 million values, whole USD, about 8 MB compressed, in `data/testdata/counterparty-risk/exposure-path/cob-2026-09-30.part-00N.jsonl.gz` and `simulation-run/cob-2026-09-30.jsonl.gz` |
| Local full set | `make_ccr_data.py --full` writes every live member trade (at most 10,000) × 500 paths × 26 dates into the data folder (about 0.5 GB, never committed), for benchmarks |
| Checks | `make_ccr_data.py --check` (as VaR's); `test_ccr_data.py`: vector lengths, sparse rows only after maturity, path 0 at the first grid date near t₀ MtM, the reference statistics in `ccrmath.py` equal the engine's (parity test in `drishti-compute`) |

## 14. Retiring the stored version

### 14.1 Id mapping and removals

| Old | New |
|---|---|
| `EXP-<cp>-<le>` (`exposure-profile`, 76 records) | `EXP NSET:NS-<cp>-<le>` (the same netting set) |
| `netting-set.PROFILE[]`, `EE_PEAK`, `PFE_PEAK` | `exposure-result` at `NSET:<id>` |
| `counterparty.PFE_PEAK` | `exposure-result` at `CPTY:<id>` (`PEAK_PFE`) |
| `exposure-profile.PATHS`, `MODEL`, `CONFIDENCE`, `RUN_DATE` | `simulation-run` (`PATHS`, `MODEL`, `BUSINESS_DATE`) and the request's `q` |

Removed: the `exposure-profile` kind (pack kind, route, form, badge, id pattern `^EXP-` now redirecting), `samples/exposure-profile.jsonl`,
`data/testdata/counterparty-risk/exposure-profile/`, the Sutra `exposure-and-capital/exposure-profile.v1.sutra.yaml` (replaced by the
computed page), the Calc snippets `exposure_profile_stats.py` and `collateral_mpor_mc.py` (rewritten over `exposure-result`), the
generator's `profile()` and the `exposure-profile` part of `credit_results` in `tools/packgen/banking/data_results.py`. Guard test:
`exposure-profile` must not return; no input or fundamental kind carries `EPE`, `EEPE`, `PFE_PEAK`, `EE_PEAK`.

### 14.2 Reconciliation: how the stored profile was made

`data_results.py` `profile(members, collateral)` (2026-10-25) does not simulate. For each of 10 tenors it sets
`EE = max(net − collateral, 0) · exp(−t/4) + vol · sqrt(max(t, 1/52)) · 0.4` with `vol = Σ_alive |notional| · SF · 0.35`, `PFE_95 = 2.2 · EE + 0.05 · vol`,
`PFE_99 = 2.9 · EE + 0.07 · vol`, `ENE` symmetrical. Then:

| Stored | Rule used | Computed rule | Effect |
|---|---|---|---|
| `EPE` | the simple mean of the 10 bucket EEs (0 to 10Y, not time-weighted) | time-weighted mean of EE over the first year | stored mixes 10 years of short buckets; not comparable |
| `EEPE` | the **largest** EE of the first five buckets (0, 1M, 3M, 6M, 1Y) | time-weighted mean of the running maximum | stored ≥ computed for any profile that is not flat |
| `PFE_95` | a multiple of EE | the 95% quantile of path exposures | different model |
| `PATHS: 5000` | a label: no paths exist | the run's `PATHS` | |
| collateral | the set's random collateral share, not lagged | lagged CSA collateral per path | stored EE ignores thresholds and MPOR |

Example on the test data's `NS-ALDERSHOT-FRA`: stored `EPE` 1,464,164 and `EEPE` 3,244,739 (the 1M bucket's EE), with `EE` falling from
3.24m at 1M to 1.31m at 1Y. Applied to that same stored profile, the rules of §6.4 give a first-year EPE of 1.74m (3.24 × 1/12 + 2.68 ×
2/12 + 1.48 × 3/12 + 1.31 × 6/12) and an EEPE of 3.24m: the stored EEPE matches only because this profile peaks at its first bucket. The
release notes will show stored against computed for every one of the 76 sets.

## 15. A worked example

**One netting set** of two trades A and B, **4 paths**, **3 primary dates** t₁ = 0.25, t₂ = 0.5, t₃ = 1.0 years. Amounts in USD millions.
The CSA has thresholds TH_them = TH_us = 1, MTA 0, IA 0, no rounding. The look-back values L are the set's value at t − MPOR on the same
path (given; in a real run they are the look-back grid dates).

| Path | A (t₁, t₂, t₃) | B (t₁, t₂, t₃) | V = A + B | L (look-back) |
|---|---|---|---|---|
| 1 | 3, 5, 2 | −1, −1, 1 | **2, 4, 3** | 1, 2, 3 |
| 2 | −2, −1, −3 | 1, 2, 1 | **−1, 1, −2** | −1, 0, −1 |
| 3 | 3, 4, 6 | −2, 0, −1 | **1, 4, 5** | 0, 3, 4 |
| 4 | 0, −2, −1 | 1, 0, −2 | **1, −2, −3** | 1, −1, −3 |

**Collateral** C = max(L − 1, 0) − max(−L − 1, 0): path 1: 0, 1, 2; path 2: 0, 0, 0; path 3: 0, 2, 3; path 4: 0, 0, −2 (we posted 2 at t₃).
**Exposure** E = max(V − C, 0) and negative exposure N = min(V − C, 0):

| Path | E (t₁, t₂, t₃) | N (t₁, t₂, t₃) |
|---|---|---|
| 1 | 2, 3, 1 | 0, 0, 0 |
| 2 | 0, 1, 0 | −1, 0, −2 |
| 3 | 1, 2, 2 | 0, 0, 0 |
| 4 | 1, 0, 0 | 0, −2, −1 (V − C = −3 + 2 = −1) |

| Figure | t₁ = 0.25 | t₂ = 0.5 | t₃ = 1.0 |
|---|---|---|---|
| EE = mean E | (2+0+1+1)/4 = **1.0** | (3+1+2+0)/4 = **1.5** | (1+0+2+0)/4 = **0.75** |
| ENE = mean N | −0.25 | −0.5 | −0.75 |
| PFE 75% = 3rd smallest of 4 (⌈4 × 0.75⌉ = 3) | sorted 0,1,1,2 → **1** | 0,1,2,3 → **2** | 0,0,1,2 → **1** |
| PFE 95% (⌈3.8⌉ = 4th: the largest) | 2 | 3 | 2 |
| EEE (running max of EE) | 1.0 | 1.5 | 1.5 |
| EE without collateral = mean max(V, 0) | 1.0 | 2.25 | 2.0 |

With Δt = 0.25, 0.25, 0.5 and T = 1 year (t₀ ignored here, CE = 0):
- **EPE** = (1.0 × 0.25 + 1.5 × 0.25 + 0.75 × 0.5) / 1 = **1.0**
- **EEPE** = (1.0 × 0.25 + 1.5 × 0.25 + 1.5 × 0.5) / 1 = **1.375** (the running max keeps 1.5 after t₂: EEPE > EPE)
- **PEAK_PFE (75%)** = 2 at t₂; without collateral it would be 4 (sorted 0, 1, 4, 4 at t₂): collateral halves the peak.
- **EAD_IMM** (informational) = 1.4 × 1.375 = **1.925**.
- **EE contribution** at t₂ without collateral: paths with V > 0 are 1, 2, 3 (V = 4, 1, 4); A contributes (5 − 1 + 4)/4 = 2.0, B (−1 + 2 + 0)/4 =
  0.25; 2.0 + 0.25 = 2.25 = the uncollateralised EE at t₂.

[CVA.md](CVA.md) section 15 prices CVA from this profile; [WWR.md](WWR.md) section 15 adds credit paths to it.

## 16. Open questions and the change table

### 16.1 Open questions

| # | Question | Context |
|---|---|---|
| 1 | Due time of the exposure run and of its paths; who runs the simulator (a vendor engine, an in-house one, QLRisk later) | §3.4 |
| 2 | **Retention of exposure paths** (7 years decided for VaR vectors): full trade-level paths for 7 years is about 3.5 PB at P = 5,000 and G = 100. Accept the tiers of §12.3 (full trade paths 20 days and month-ends for a year, netting-set matrices month-ends for 7 years, statistics and trade contributions every date for 7 years), or another split? | §12.3 |
| 3 | When a CSA's MPOR differs from the run's look-back: use the run's look-back with a flag (proposed), or require the simulator to write a look-back per MPOR in use (5, 10, 20 days)? | §6.3 |
| 4 | Several runs on one date: a `STRESSED` calibration for EEPE (CRE53), a `REAL_WORLD` run for PFE and a `RISK_NEUTRAL` one for CVA. Which runs does the bank produce, and which is the default for each measure? | §3.2, §6.5 |
| 5 | Keep a per-set trade matrix in the hot file (fast trade contributions and what-if removals, but 2 TB a date at full size), or always read trades from the lake (proposed)? | §7.2 |
| 6 | Path cash flows (`CASHFLOW`): needed for settlement-risk spikes and for collateral between grid dates; required, optional (proposed), or not at all? | §3.3 |
| 7 | Float32 paths in the lake (a stated difference from README.md section 8): accept? | §3.3 |
| 8 | Pre-deal paths: will the upstream simulator offer an on-demand what-if service on the overnight run (same paths), and through which interface? | §6.7 |

### 16.2 The change table

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-25 | First version, PLANNED for 2.10.0; product owner's decisions of 2026-10-25 applied (load signal reloads the engine, 7-year retention stated with a cost and a proposal, `LE:` from the trade's booking legal entity, published official results) |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
