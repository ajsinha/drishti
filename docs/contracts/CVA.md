<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# CVA: credit and other valuation adjustments, the data contract

*Version 1.0 · 2026-10-25 · Shared rules: [README.md](README.md) · Model contract: [VAR.md](VAR.md) · Exposure: [EXP.md](EXP.md) ·
SA-CCR (for BA-CVA): [SACCR.md](SACCR.md) · Wrong-way risk: [WWR.md](WWR.md)*

**Status:** proposed. PLANNED for 2.10.0, not built. Every table, column, setting, kind, endpoint and code below is a proposal; names
may change before the release, and each change will be recorded in [section 16](#16-open-questions-and-the-change-table).

This is the contract of Drishti's **CVA engine**: CVA (unilateral and bilateral), DVA, FVA (funding cost and benefit), the CS01 to the
counterparty's spread, hooks for KVA and MVA, and the regulatory **BA-CVA** capital (reduced version), computed when asked for any
scope from EXP's exposure profiles, the market-data pack's credit and discount curves, and SA-CCR's EAD. It adds no input table of its
own: everything it needs is EXP's paths, market data and configuration.

## Contents

1. [Summary](#1-summary)
2. [The measure in one page](#2-the-measure-in-one-page)
3. [Inputs from upstream engines](#3-inputs-from-upstream-engines)
4. [Reference and master data read](#4-reference-and-master-data-read)
5. [Conventions specific to CVA](#5-conventions-specific-to-cva)
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

**Commands.** `CVA NSET NS-ALDERSHOT-NY <GO>`, `CVA CPTY CP-ALDERSHOT <GO>`, `CVA LE LE-NY <GO>`, `CVA BANK <GO>`; the API `/api/v1/cva`.

**Retired in the same release:** the stored kind `cva` (`CVA-*`, 76 records with `CVA`, `DVA`, `FVA`, `KVA`, `CS01`, `COMPONENTS[]`,
`SENSITIVITIES[]`) and the `CVA` field on `netting-set`.

| Part | Status |
|---|---|
| CVA unilateral and bilateral (first to default), DVA, FCA and FBA, CS01 and recovery sensitivity | PLANNED |
| KVA and MVA hooks (KVA from projected capital, MVA from `SIMM.md`'s IM) | PLANNED (hooks) |
| BA-CVA reduced (MAR50) from SA-CCR EAD; SA-CVA out of scope (needs upstream CVA sensitivities) | PLANNED |
| Proxy credit curves for counterparties without CDS | PLANNED |
| Computed kind `cva-result`, reactive connector `cva-engine` (Python) | PLANNED |

## 2. The measure in one page

**What it answers:** *what is the price of the counterparty's default risk in our trades, of our own, and of funding them?* For the CVA desk
(pricing and hedging), finance (the fair-value adjustment), and regulatory capital (BA-CVA).

**In words.** CVA is the expected loss from the counterparty's default: the sum over future periods of the discounted expected exposure
times the probability that the counterparty defaults in that period, times the loss given default. DVA is the same for the bank's own
default on the negative exposure. FVA charges the cost of funding the positive exposure and credits the benefit of the negative one.
Each is computed per netting set from EXP's EE and ENE profiles (or per path, for wrong-way risk in WWR.md) and adds up over netting sets
and counterparties.

**Methodology.** Standard market practice (Gregory, *The xVA Challenge*): independence of exposure and default (wrong-way risk is
[WWR.md](WWR.md)'s), risk-neutral default probabilities from CDS curves, discounting by the OIS curve. Regulatory capital per the Basel CVA
risk framework (MAR50), basic approach, reduced version.

## 3. Inputs from upstream engines

CVA has **no input table of its own**. It reads:

| Input | Contract | What |
|---|---|---|
| `exposure-path`, `simulation-run` | [EXP.md](EXP.md) section 3 | EE, ENE per netting set and primary date (through `exposure-result`); the run should be `RISK_NEUTRAL` (EXP open question 4) |
| `saccr-trade` and its result | [SACCR.md](SACCR.md) | EAD and effective maturity per netting set for BA-CVA |
| `credit-curve` (market-data pack) | the pack's contract | `POINTS[]` (`TENOR`, `SPREAD` in bp, `HAZARD`, `SURVIVAL`), `RECOVERY`, `CURRENCY` |
| `ir-curve` (market-data pack) | the pack's contract | the USD OIS discount curve (`POINTS[]`) |
| SIMM IM (`SIMM.md`) | | IM profile for the MVA hook |

Cadence: whenever its inputs change (the reactive triggers, §9.4). Due: after the exposure run and the end-of-day market data.

## 4. Reference and master data read

| Kind | Key | Read for |
|---|---|---|
| `counterparty` (core) | `CREDIT_CURVE`, `RATING`, `SECTOR`, `COUNTRY`, `KYC.PD_1Y` | the curve, or the proxy's inputs |
| `credit-curve` | `CDS-<issuer>` | spreads, hazards, survival, recovery |
| the bank's own curve | `drishti.ccr.cva.own-curve` | DVA, bilateral CVA, FBA |
| `ir-curve` | `drishti.ccr.cva.discount-curve` | DF(t) |
| funding curve | `drishti.ccr.cva.funding-spread` (a curve id or a flat spread) | FVA |

## 5. Conventions specific to CVA

| Convention | Rule |
|---|---|
| Sign | **valuation adjustments, from the bank's side**: CVA ≤ 0 (a cost), DVA ≥ 0 (a benefit), FCA ≤ 0, FBA ≥ 0, as the stored kind did; BA-CVA capital ≥ 0 |
| Units | USD; CS01 in USD per +1 bp parallel spread move; recovery sensitivity in USD per +1% recovery |
| Time | years ACT/365 from the business date; EXP's primary dates |
| LGD | 1 − `RECOVERY` of the counterparty's curve (market LGD); a setting may override per counterparty type |
| Exposure basis | EE and ENE at the grid date in future USD, discounted here by DF(t_k) (when EXP's run is `VALUE_BASIS: FUTURE`) |

## 6. The computation

### 6.1 Parameters (`drishti.ccr.cva.*`)

| Setting | Default | Meaning |
|---|---|---|
| `mode` | `unilateral` | `unilateral` or `bilateral` (first to default) |
| `ee-point` | `end` | `end`: EE*(t_k) per period; `mid`: (EE*(t_{k−1}) + EE*(t_k))/2 (the Basel advanced CVA formula's averaging) |
| `pd-source` | `survival` | `survival` (the curve's `SURVIVAL` points, log-linear between), `hazard` (piecewise-constant `HAZARD`), `spread` (λ = s / LGD) |
| `own-curve` | empty | the bank's CDS curve id; empty: DVA and bilateral off |
| `discount-curve` | the USD OIS curve id | |
| `funding-spread` | `0.0050` | flat, or a curve id |
| `proxy-curves` | a table by rating × sector × region → spread (bp) | for counterparties without `CREDIT_CURVE` (§6.5) |
| `kva.hurdle`, `kva.enabled` | `0.10`, `false` | the KVA hook |
| `ba-cva.rho`, `ba-cva.ds`, `ba-cva.alpha` | `0.5`, `0.65`, `1.4` | MAR50 |
| `ba-cva.risk-weights` | the table of §6.6 | |

### 6.2 Default probabilities and discount factors

```
S_C(t)        survival of the counterparty to t (pd-source)          S_B(t) the bank's own (own-curve)
ΔPD_C(k)      = S_C(t_{k−1}) − S_C(t_k)                              t_0 = 0
DF(t)         from the discount curve
EE*(t_k)      = EE(t_k) · DF(t_k)                 ENE*(t_k) = ENE(t_k) · DF(t_k)      (EXP's primary dates, after collateral)
```

### 6.3 CVA, DVA, FVA

```
unilateral   CVA = −LGD_C · Σ_k EE*(t_k) · ΔPD_C(k)
             DVA = +LGD_B · Σ_k |ENE*(t_k)| · ΔPD_B(k)
bilateral    CVA = −LGD_C · Σ_k EE*(t_k)  · ΔPD_C(k) · S_B(t_k)            (the bank has not defaulted first)
             DVA = +LGD_B · Σ_k |ENE*(t_k)| · ΔPD_B(k) · S_C(t_k)
funding      FCA = −Σ_k EE*(t_k)  · s_F(t_k) · Δt_k                         (optionally × S_C(t_k) · S_B(t_k))
             FBA = +Σ_k |ENE*(t_k)| · s_F(t_k) · Δt_k
             FVA = FCA + FBA
ee-point mid: replace EE*(t_k) by (EE*(t_{k−1}) + EE*(t_k)) / 2, EE*(t_0) = the current exposure
```

**Hooks** (shown only when enabled, labelled *indicative*): **KVA** = −Σ_k K(t_k) · hurdle · Δt_k · DF(t_k), with K(t) the projected
capital of the set (8% × RW × EAD(t), EAD(t) from EXP's remaining EEPE × alpha); **MVA** = −Σ_k IM(t_k) · (s_F − r_IM) · Δt_k · DF(t_k)
with IM(t) from `SIMM.md` (constant at today's IM until an IM profile exists).

### 6.4 Sensitivities

| Figure | Computation |
|---|---|
| `CS01` | CVA with the counterparty's spreads + 1 bp at every tenor (hazards rebuilt as λ = s / LGD between points), minus CVA |
| `REC01` | CVA with recovery + 1% (LGD − 1%), minus CVA (with spreads fixed, so the implied PD moves) |
| `DVA_CS01` | the same for the own curve |
| Market-factor sensitivities (rates, FX, vol) | **not computed**: they need the exposure re-simulated under bumped markets (upstream); a future `cva-sensitivity` input for SA-CVA (open question 3) |

### 6.5 Proxy credit curves

A counterparty without `CREDIT_CURVE` gets a flat spread from `proxy-curves` by its rating, sector and region (the region from `COUNTRY`),
recovery 40%, and is flagged `proxy`. Without a rating: the counterparty's `KYC.PD_1Y` as a flat hazard λ = −ln(1 − PD_1Y), flagged.

### 6.6 BA-CVA, reduced version (MAR50)

For each counterparty c, over its netting sets NS (SA-CCR EAD, effective maturity M_NS in years):

```
DF_NS      = (1 − exp(−0.05 · M_NS)) / (0.05 · M_NS)
SCVA_c     = (1/α) · RW_c · Σ_NS M_NS · EAD_NS · DF_NS                   α = 1.4
K_reduced  = sqrt( (ρ · Σ_c SCVA_c)² + (1 − ρ²) · Σ_c SCVA_c² )          ρ = 0.5
K_BA-CVA   = DS · K_reduced                                               DS = 0.65
```

M_NS: the notional-weighted remaining maturity of the set's derivatives (`saccr-trade.MATURITY_DATE`), floored at 1 and capped at 5 years
(open question 4). Risk weights RW_c by sector and credit quality (MAR50; checked against the text when built):

| Sector | Investment grade | High yield and not rated |
|---|---|---|
| Sovereigns, central banks, multilateral development banks | 0.5% | 2.0% |
| Local government, government-backed non-financials, education, public administration | 1.0% | 4.0% |
| Financials, including government-backed financials | 5.0% | 12.0% |
| Basic materials, energy, industrials, agriculture, manufacturing, mining and quarrying | 3.0% | 7.0% |
| Consumer goods and services, transportation and storage, administrative and support services | 3.0% | 8.5% |
| Technology, telecommunications | 2.0% | 5.5% |
| Health care, utilities, professional and technical activities | 1.5% | 5.0% |
| Other sector | 5.0% | 12.0% |

**Non-additive:** K_reduced is a square root over counterparties: a scope's BA-CVA is computed over the counterparties in the scope;
it is not the sum of its parts' BA-CVA. The full BA-CVA (with eligible hedges) and SA-CVA are out of this contract (open question 3).

### 6.7 Additivity and per-node aggregates

CVA, DVA, FCA, FBA, CS01 are **linear in EE and ENE**, and EE adds across netting sets, so **every xVA figure adds over netting sets and
counterparties**: Drishti computes them per netting set at date load and a scope is a sum (VaR-style node aggregates, microseconds per
answer). A partial scope (a desk's share of a set) is computed from the partial EE profile (EXP.md section 8). BA-CVA is not additive
(§6.6). WWR-adjusted CVA (path-wise) is WWR.md's.

### 6.8 Where it runs

Python engine `drishti_ccr.cva:CvaEngine` on the `risk` pool: per netting set the profile (50 dates × EE, ENE), the curve points and DF:
about 100,000 × 50 rows at a large bank, split across workers, results summed. Computed once per date for every set (the batch class,
after EXP's load), then served from the per-set aggregates; a what-if (a candidate trade from EXP's what-if) recomputes the touched sets.

## 7. What Drishti builds

| Structure | Content | Size, large bank |
|---|---|---|
| per netting set xVA | CVA, DVA, FCA, FBA, CS01, REC01, CVA_BIL, DVA_BIL, KVA, MVA (float64) | 100,000 × 10 × 8 = 8 MB |
| per counterparty curve | S(t_k), ΔPD(k) on the primary dates, LGD, proxy flag | 20,000 × 50 × 2 × 8 = 16 MB |
| DF(t_k) | | bytes |
| BA-CVA per counterparty | SCVA_c, RW_c | 1 MB |

No disk structure beyond EXP's; the answers are cached as NSET's.

## 8. Scopes

All of NSET.md section 8. Default parts: the bank → legal entities → counterparties → netting sets; a netting set → its EE contributions
by trade (EXP.md section 6.6, giving each trade's CVA contribution: CVA is linear in EE).

## 9. What Drishti answers

### 9.1 The computed kind `cva-result`

| Field | Type | Unit | Meaning |
|---|---|---|---|
| `SCOPE`, `CANONICAL`, `SCOPE_KIND`, `LABEL`, `BUSINESS_DATE` | | | |
| `MODE` | text | | `unilateral` or `bilateral` |
| `CVA`, `DVA`, `FCA`, `FBA`, `FVA`, `BCVA` (= CVA + DVA) | amount | USD | §6.3 |
| `KVA`, `MVA` | amount | USD | hooks (empty when off) |
| `CS01`, `REC01`, `DVA_CS01` | amount | USD per bp / per % | §6.4 |
| `BA_CVA` | amount | USD | K_BA-CVA over the scope's counterparties |
| `LGD`, `CREDIT_CURVE`, `SPREAD_5Y`, `PROXY` | rate, text, rate, boolean | | for one counterparty |
| `PROFILE` | record list | | per primary date: `TENOR`, `T`, `DF`, `EE`, `ENE`, `PD` (ΔPD_C), `SURVIVAL`, `CVA_CONTRIBUTION`, `DVA_CONTRIBUTION` |
| `BY_COUNTERPARTY`, `BY_NETTING_SET` | record list | | `ID`, `CVA`, `DVA`, `FVA`, `CS01`, `BA_CVA_SCVA` |
| `PROVENANCE` | record | | NSET's, plus EXP's run, the curves (id, date, proxy) and the settings used |

### 9.2 The API (`/api/v1/cva`, planned)

| Method and path | Parameters | Answer |
|---|---|---|
| `GET /scope` | `scope`, `asOf`, `mode`, `eePoint` | §9.1 |
| `GET /scope/contributions` | `scope`, `by` (`counterparty`, `netting-set`, `trade`, `tenor`) | rows `{key, label, cva, dva, fva, cs01}` |
| `GET /ba-cva` | `scope`, `asOf` | `{k, kReduced, counterparties: [{id, sector, quality, rw, scva}]}` |
| `POST /what-if` | `{scope, candidates}` (EXP's what-if body) or `{spreadShift, recovery}` | xVA before and after, incremental CVA |
| captures, status, reload | as NSET | |

CSV: `GET /scope/contributions?format=csv`.

### 9.3 Published official results (`cva-official`, `cva-official-cpty`)

The two tables of [NSET.md section 9.5](NSET.md#95-published-official-results-nset-official-nset-official-cpty), with the shared columns
and `CVA`, `DVA`, `FCA`, `FBA`, `FVA`, `BCVA`, `CS01`, `REC01`, `BA_CVA` (DOUBLE, USD), `MODE` (STRING), `RUN_ID` (STRING). Node rows sum
the xVA of the node's netting sets; `BA_CVA` of a node is computed over its counterparties (not summed). The `-cpty` rows add
`CREDIT_CURVE`, `SPREAD_5Y`, `LGD`, `PROXY`. This is the table finance reads for the day's CVA reserve.

### 9.4 The reactive connector and the engine (proposed)

```yaml
# config/connectors/cva-engine.yaml                             (planned, 2.10.0)
plugin: reactive
mode: reactive
description: CVA, DVA, FVA, CS01 and BA-CVA for any counterparty scope
kinds:
  cva-result:
    compute: cva
    params:
      mode:     { type: enum, options: [unilateral, bilateral], default: unilateral }
      ee-point: { type: enum, options: [end, mid], default: end }
    result: { required: [CVA, DVA, FVA], types: { CVA: number, DVA: number, FVA: number } }
reads:
  - { kind: exposure-result, as: exposure, id: "{id}" }
  - { kind: saccr-result, as: saccr, id: "{id}" }
  - { kind: counterparty, as: counterparties, all: true }
  - { kind: credit-curve, as: curves, all: true }
  - { kind: ir-curve, as: discount, id: "${DRISHTI_CVA_DISCOUNT_CURVE:CRV-USD-SOFR}" }
engine:
  type: python
  entry: drishti_ccr.cva:CvaEngine
  pool: risk
  version: "1.0"
  split: profiles
  chunk-rows: 100000
  reduce: { sum: [CVA, DVA, FCA, FBA, FVA, CS01, REC01] }
triggers:
  - { on: data-version, kinds: [exposure-path, simulation-run, credit-curve, ir-curve, saccr-trade, counterparty], affects: all }
coalesce: { quiet: 1s, max-wait: 10s }
cache: { results: 2000, ttl: 1d }
limits: { timeout: 60s, wait: 100ms, max-inflight-per-user: 4 }
requires: { python: { version: "3.12", lock: requires/drishti-ccr.lock } }
```

```python
# compute/drishti-ccr/src/drishti_ccr/cva.py                    (planned, 2.10.0)
class CvaEngine(Engine):
    kinds = ("cva-result",)
    def compute(self, req, inputs, progress):
        prof = inputs.arrow("profiles")   # NETTING_SET, COUNTERPARTY, T float64, EE float64, ENE float64 (primary dates)
        curves = inputs.arrow("curves")   # COUNTERPARTY, T float64, SURVIVAL float64, LGD float64, PROXY bool   (on the grid)
        own = inputs.arrow("own_curve")   # T, SURVIVAL, LGD   (empty: DVA off)
        df = inputs.arrow("discount")     # T, DF
        ead = inputs.arrow("saccr")       # NETTING_SET, COUNTERPARTY, EAD, M, SECTOR, QUALITY
        ...                               # sections 6.2 to 6.6
        return Result(rows=[totals], tables={"by_netting_set": ..., "profile": ..., "ba_cva": ...})
```

The discount curve id above is an example; the market-data pack's USD OIS curve id is set in configuration (open question 5).

## 10. Validation, errors and data quality

| Check | Rule | Outcome |
|---|---|---|
| Curve | the counterparty's `CREDIT_CURVE` exists on the date with ≥ 2 points, survival decreasing, recovery in [0, 1) | proxy curve, flagged |
| Run | EXP's run is `RISK_NEUTRAL` | computed, flagged `real-world-exposure` |
| Discount | the discount curve exists | `DRS-5371` |
| Grid | the curve's last point before the profile's last date | flat extrapolation of hazard, flagged |
| Own curve | configured and present when `bilateral` | `DRS-5371` |

**Proposed DRS codes** (5370–5379): `DRS-5370` (400, bad scope or parameter), `DRS-5371` (409, a required curve is missing on the date).

## 11. Entitlements and audit

As NSET.md section 11. CVA by counterparty is a P&L figure of the CVA desk: the role setting may restrict `BY_COUNTERPARTY` to the desk
and finance. `ccr-view` audited.

## 12. Performance and sizing

| Operation | Target (p95, warm) |
|---|---|
| a netting set or counterparty (per-set aggregates) | 5 ms |
| the bank (sum of 100,000 sets) | 20 ms |
| full recomputation of every set after a curve load | 30 s (split over workers) |
| BA-CVA of the bank (20,000 counterparties) | 100 ms |
| what-if, one candidate | 1.5 s (EXP's what-if dominates) |

## 13. Test data and generators

No input of its own: the test data are EXP's committed paths (8 netting sets; all 76 with `--full` locally), the market-data pack's credit
curves (`CDS-*` exist for the counterparties that are also issuers; the rest use the proxy table, which the generator writes as
`drishti.ccr.cva.proxy-curves` in the shipped configuration), and an own curve `CDS-BANK` added to the market-data test data.
`ccrmath.py` (planned) holds the reference CVA and BA-CVA, tested on §15's numbers.

## 14. Retiring the stored version

`CVA-<cp>-<le>` → `CVA NSET:NS-<cp>-<le>`. Removed: the `cva` kind (kind, route, form, badge, id pattern `^CVA-` now redirecting),
`samples/cva.jsonl`, `data/testdata/counterparty-risk/cva/`, the Sutra `exposure-and-capital/cva.v1.sutra.yaml` (replaced), the Calc
snippet `cva_from_ee.py` (rewritten over `cva-result`), the CVA part of `credit_results`, `netting-set.CVA`. The stored
`SENSITIVITIES[]` to rates and FX vol are not replaced (§6.4). Guard test: `cva` must not return; no input or fundamental kind carries
`CVA`, `DVA`, `FVA`, `KVA`, `CS01`.

**Reconciliation: how the stored figures were made** (`credit_results`, 2026-10-25):

| Stored | Rule | Computed |
|---|---|---|
| `CVA` | −0.6 × Σ EE(t_k) × PD_1Y(rating) × Δt_k over the 10 heuristic buckets: the one-year PD from a rating table times the period length, no curve, no survival, no discounting | −LGD × Σ EE* × ΔPD from the counterparty's curve |
| `DVA` | −CVA × 0.35 × PD(A) / PD(counterparty) | from the own curve and ENE |
| `FVA` | −\|net MtM\| × 0.0012 | FCA + FBA from EE, ENE and the funding spread |
| `KVA` | −PFE peak × 0.004 | hook (off) |
| `CS01`, `SENSITIVITIES[]` | CVA × fixed multipliers (0.018, −0.004, …) | bumped curve (CS01 only) |

The three CVA values of the test data's `NS-ALDERSHOT-FRA` (−1,435 on the set, −1,276 on the record, −1,323 in its `COMPONENTS[]`) come
from the daily walk of separate files; the computed CVA is one number. The release notes list stored and computed per set.

## 15. A worked example

EXP.md section 15's netting set (USD millions): EE = 1.0, 1.5, 0.75 and ENE = −0.25, −0.5, −0.75 at t = 0.25, 0.5, 1.0. Counterparty: flat
hazard λ_C = 2%, LGD_C = 60%; the bank: λ_B = 1%, LGD_B = 60%; **no discounting** (DF = 1, to keep it checkable by hand).

| k | t_k | ΔPD_C = e^(−0.02 t_{k−1}) − e^(−0.02 t_k) | EE × ΔPD_C | ΔPD_B (1%) | \|ENE\| × ΔPD_B |
|---|---|---|---|---|---|
| 1 | 0.25 | 0.0049875 | 0.0049875 | 0.0024969 | 0.0006242 |
| 2 | 0.5 | 0.0049626 | 0.0074440 | 0.0024906 | 0.0012453 |
| 3 | 1.0 | 0.0098512 | 0.0073884 | 0.0049626 | 0.0037220 |
| Σ | | | 0.0198199 | | 0.0055915 |

- **CVA** = −0.6 × 0.0198199 = −0.0118919m = **−11,892 USD**
- **DVA** = +0.6 × 0.0055915 = **+3,355 USD**; **BCVA** (unilateral pair) = −8,537
- **Bilateral** (first to default): CVA = −0.6 × Σ EE·ΔPD_C·S_B(t_k) = **−11,818**; DVA = +0.6 × Σ \|ENE\|·ΔPD_B·S_C(t_k) = **+3,301**
- **CS01:** the spread implied by λ = 2% and LGD 60% is 120 bp; at 121 bp λ = 0.0201667 and CVA = −11,990: **CS01 = −98 USD per bp**
- **FVA** with a flat 50 bp funding spread, Δt = 0.25, 0.25, 0.5: FCA = −0.005 × (1.0 × 0.25 + 1.5 × 0.25 + 0.75 × 0.5) = −0.005m = **−5,000**;
  FBA = +0.005 × (0.25 × 0.25 + 0.5 × 0.25 + 0.75 × 0.5) = **+2,813**; **FVA = −2,188**

**BA-CVA (reduced)** for one counterparty in *Financials*, investment grade (RW 5%), with SACCR.md section 15's netting set (EAD
4,184,144) and M = 4 years: DF = (1 − e^(−0.2)) / 0.2 = 0.906346; SCVA = (1/1.4) × 0.05 × 4 × 4,184,144 × 0.906346 = **541,755**; with a single
counterparty K_reduced = SCVA × √(0.25 + 0.75) = 541,755; **K_BA-CVA = 0.65 × 541,755 = 352,141**.

## 16. Open questions and the change table

### 16.1 Open questions

| # | Question | Context |
|---|---|---|
| 1 | Unilateral (proposed default) or bilateral CVA for the official figure; is DVA reported? | §6.1 |
| 2 | The bank's own CDS curve and funding spread curve: which ids in the market-data pack (the demo has none) | §4 |
| 3 | SA-CVA and CVA market-factor sensitivities: an upstream `cva-sensitivity` input in a later release? | §6.4, §6.6 |
| 4 | BA-CVA effective maturity M: notional-weighted remaining maturity (proposed) or the IMM formula for approved banks | §6.6 |
| 5 | The discount curve: the USD OIS curve id in the market-data pack; multi-currency CSAs (discounting at the CSA rate) | §9.4 |
| 6 | KVA and MVA: show as hooks (off by default, proposed) or leave out until capital projection and an IM profile exist? | §6.3 |

### 16.2 The change table

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-25 | First version, PLANNED for 2.10.0; product owner's decisions of 2026-10-25 applied (load signal reloads the engine, inputs kept 7 years, published official results) |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
