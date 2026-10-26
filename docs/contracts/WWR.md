<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# WWR: wrong-way risk, the data contract

*Version 1.0 · 2026-10-25 · Shared rules: [README.md](README.md) · Model contract: [VAR.md](VAR.md) · Exposure paths: [EXP.md](EXP.md) ·
CVA: [CVA.md](CVA.md) · Groups: [GRP.md](GRP.md)*

**Status:** proposed. PLANNED for 2.10.0, not built. Every table, column, setting, kind, endpoint and code below is a proposal; names
may change before the release, and each change will be recorded in [section 16](#16-open-questions-and-the-change-table).

This is the contract of **wrong-way risk**, a new measure: exposure that rises as the counterparty's credit quality falls. **Specific**
wrong-way risk comes from the trades themselves (protection bought from a counterparty on its own name or its group's, collateral issued
by the counterparty); **general** wrong-way risk from the market (exposure and the counterparty's default probability driven by the same
factors). Drishti flags both, measures general wrong-way risk on EXP's paths (correlation, exposure conditional on default, CVA with
path-wise default probabilities, stressed exposure), and gives specific wrong-way trades a jump-to-default exposure. It defines one
optional shared input, `credit-path`.

## Contents

1. [Summary](#1-summary)
2. [The measure in one page](#2-the-measure-in-one-page)
3. [Inputs from upstream engines](#3-inputs-from-upstream-engines)
4. [Reference and master data read](#4-reference-and-master-data-read)
5. [Conventions specific to wrong-way risk](#5-conventions-specific-to-wrong-way-risk)
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

**Commands.** `WWR CPTY CP-ALDERSHOT <GO>`, `WWR NSET NS-ALDERSHOT-NY <GO>`, `WWR LE LE-NY <GO>` (every flagged counterparty of the legal
entity, worst first); the API `/api/v1/wwr`. The mnemonic `WWR` is new (Admin → Commands switches it like any other).

**Nothing is retired**: there is no stored wrong-way kind. The Calc snippet `wrong_way_flags.py` (heuristic flags from reference data) is
superseded by the computed kind.

| Part | Status |
|---|---|
| Specific WWR flags from reference data (trades and collateral referencing the counterparty or its connected group) | PLANNED |
| Jump-to-default exposure of specific WWR trades, and their removal from the netting set (CRE53) | PLANNED |
| General WWR on paths: `credit-path` (optional input) or the Hull–White model without it; correlation, conditional EE, WWR CVA and ratio, stressed EE | PLANNED |
| Computed kind `wwr-result`, reactive connector `wwr-engine` (Python) | PLANNED |

## 2. The measure in one page

**What it answers:** *is this counterparty's exposure largest exactly when it is most likely to default, and by how much does that raise
its CVA and our loss in default?* For credit officers (approval, limits), the CVA desk, and regulatory review (CRE53 asks banks to identify
and monitor specific and general wrong-way risk).

**In words.**
1. **Specific:** a trade is flagged when its reference entity, underlying or issuer is the counterparty or a member of its connected group
   (GRP.md): protection bought from a bank on itself, a put on the counterparty's own shares bought from it, a repo or collateral in its own
   bonds. Its exposure in default is its **jump-to-default** value (the reference entity defaults with the counterparty), and it is treated
   as its own netting set.
2. **General, with credit paths:** the upstream simulator also writes the counterparty's hazard rate on every path of the same run. On each
   path the probability of default in a period is computed from that path's hazard; the **WWR CVA** weights each path's exposure by its own
   default probability. Its ratio to the CVA under independence is the **WWR ratio**; the correlation of exposure and hazard across paths,
   the **EE conditional on default** and the **EE on the worst credit paths** describe it.
3. **General, without credit paths:** the Hull–White model makes the hazard a function of the exposure on the path,
   λ(p, t) = exp(a(t) + b · E(p, t)), with a(t) calibrated so the average survival matches the market curve and b a per-sector setting; b = 0
   is independence.

## 3. Inputs from upstream engines

### 3.1 Where they live

```
${DRISHTI_DELTA_ROOT}/risk/
├── credit-path/      business_date=YYYY-MM-DD/run_id=<RUN_ID>/   optional: one row per credit name × grid date: hazard over the P paths
└── exposure-path/, simulation-run/                               EXP.md section 3 (the same run, the same path order)
```

### 3.2 `credit-path`: counterparty credit on the exposure paths (optional, shared)

**Grain:** one row per credit name (a counterparty or a credit curve) and grid date per run. **Key:** `CREDIT_PATH_ID` =
`<RUN_ID>~<NAME>~<GRID_INDEX>`. **When:** the run's `CREDIT_PATHS` is true. **Row count:** counterparties simulated × G (a large bank:
20,000 × 100 = 2 million rows, 20,000 × 100 × 5,000 × 4 bytes = **40 GB** a date in float32, a fiftieth of the exposure paths). Path *p* is
the same market path as in `exposure-path`: the simulator must draw credit and market factors jointly.

| Column | JSON | Delta | Req. | Engine | Unit | Meaning | Example |
|---|---|---|---|---|---|---|---|
| `CREDIT_PATH_ID` | id | `id` STRING | required | – | | | `XR-2026-09-30-01~CP-ALDERSHOT~7` |
| `BUSINESS_DATE` | date | `business_date` DATE | required | R | | | `2026-09-30` |
| `RUN_ID` | id | STRING (partition) | required | R | | the `simulation-run` | `XR-2026-09-30-01` |
| `NAME_TYPE` | text | STRING | required | R | | `COUNTERPARTY` or `CREDIT_CURVE` (one curve shared by several counterparties) | `COUNTERPARTY` |
| `NAME` | id | STRING | required | R | | the counterparty or `CDS-…` curve id | `CP-ALDERSHOT` |
| `GRID_INDEX`, `GRID_DATE` | integer, date | DOUBLE, STRING | required | R | | as EXP | `7`, `2026-12-31` |
| `HAZARD` | vector | `list<float>` | required | R | per year | the counterparty's default intensity on each path, piecewise constant from the previous grid date to this one, length P | `[0.0182, 0.0247, …]` |
| `SPREAD` | vector | `list<float>` | optional | – | bp | the simulated 5-year CDS spread (for display and for flags) | `[118.0, 151.5, …]` |
| `STATUS`, `BATCH_ID` | text, id | STRING | required | – | | | `ok`, `B-2026-09-30-01` |

Vectors follow EXP.md section 3.3 (float32 allowed). Cadence, due time, load signal (reloads the engine automatically) and retention follow
EXP; at 40 GB a date the 7-year question is the same (EXP open question 2), proposed: credit paths kept with the exposure paths' windows,
WWR statistics every date for 7 years.

## 4. Reference and master data read

| Kind | Read for |
|---|---|
| `exposure-result` and the netting sets' path matrices (EXP.md) | E(p, k), EE |
| `counterparty` (core) | `LEI`, `SECTOR`, `COUNTRY`, `TYPE`, `CREDIT_CURVE`, `GROUP` |
| `group-usage` resolver (GRP.md) | the connected group's members and LEIs |
| `saccr-trade` (SACCR.md) | `REFERENCE`, `ASSET_CLASS`, `TRANSACTION_TYPE`, `BOUGHT_SOLD`, `DIRECTION`, notional, option terms (the specific checks and jump-to-default) |
| `trade` (trading) | `LEGS[].INDEX`, underlying and issuer fields where the classification has none |
| `collateral-holding` (COLL.md) | collateral issued by the counterparty or its group, or by its sovereign |
| `issuer`, `lei-entity`, `lei-mapping` | issuer → LEI → group; ISIN → LEI |
| `credit-curve` | the market curve (independence CVA, Hull–White calibration) |
| `netting-set-member.SPECIFIC_WWR` | the bank's own specific flags (NSET.md section 3.3) |

## 5. Conventions specific to wrong-way risk

| Convention | Rule |
|---|---|
| Exposure | as EXP (after netting and collateral, E ≥ 0) |
| Hazard | per year; S_p(t_k) = exp(−Σ_{j≤k} λ(p, j) Δt_j) |
| Correlation | Pearson across paths at a grid date; the summary ρ̄ is the Δt-weighted mean over the first year |
| WWR ratio | CVA_WWR / CVA_independent (> 1: wrong way, < 1: right way) |
| Sign of CVA | as CVA.md (≤ 0) |

## 6. The computation

### 6.1 Parameters (`drishti.ccr.wwr.*`)

| Setting | Default | Meaning |
|---|---|---|
| `model` | `credit-paths` when the run has them, else `hull-white` | |
| `hull-white.b` | by sector: financials 0.0, sovereigns 0.0, … (all 0 = independence until calibrated); per counterparty override | the WWR parameter (per USD million of exposure) |
| `ratio-threshold` | `1.2` | flag `GENERAL` above |
| `rho-threshold` | `0.3` | flag `GENERAL` above |
| `stress-quantile` | `0.95` | the worst credit paths for stressed EE |
| `specific.match` | `[lei, group, issuer, isin]` | how a reference is matched to the counterparty |
| `specific.remove-from-netting-set` | `true` | CRE53 treatment |
| `collateral-check` | `true` | flag collateral issued by the counterparty, its group, or its sovereign (for a sovereign or a domestic bank) |

### 6.2 Specific wrong-way risk

A trade *i* of counterparty *c* is **specific** when any of:
- `saccr-trade.REFERENCE` (credit, equity) resolves to *c* or a member of *c*'s connected group (by LEI, issuer or ISIN through `lei-mapping`);
- the trade's issuer or underlying (bond, equity, loan) does;
- the bank has set `netting-set-member.SPECIFIC_WWR` (always wins).

A collateral holding of *c*'s account is **wrong-way collateral** when its issuer is *c* or its group (or *c*'s sovereign when *c* is a
sovereign-dependent bank, `collateral-check`).

**Jump-to-default exposure** of a specific trade (exposure if the reference entity defaults with the counterparty; R the reference's recovery):

| Trade | JTD (≥ 0) |
|---|---|
| credit protection bought, single name on *c*'s group | notional × (1 − R) − premium leg's value (the protection is worthless) |
| equity put bought on *c*'s shares, or equity swap receiving *c*'s performance short | quantity × strike (puts) or the swap's value with the share at 0 |
| a bond of *c*'s group bought forward or under a TRS from *c* | notional × (1 − R) |
| other | max(MtM, 0) (no JTD model): flagged `jtd-not-modelled` |

With `remove-from-netting-set`, each specific trade is its own netting set: its EAD is its JTD (SA-CCR: α × JTD, open question 2), its
exposure is no longer netted with the rest, and EXP's figures of the remaining set are recomputed (exact with the trade's paths).

### 6.3 General wrong-way risk on paths

For netting set or counterparty scope S with exposure E(p, k) (EXP §6.4) and hazard λ(p, k):

```
S_p(t_k)    = exp(−Σ_{j ≤ k} λ(p, j) Δt_j)                ΔPD_p(k) = S_p(t_{k−1}) − S_p(t_k)
ρ(k)        = corr_p( E(p,k), λ(p,k) )                     Pearson across the P paths
EE(k)       = (1/P) Σ_p E(p,k)
EE_DEF(k)   = Σ_p E(p,k) ΔPD_p(k) / Σ_p ΔPD_p(k)            exposure expected in default in period k
EE_STRESS(k)= mean of E(p,k) over paths with λ(p,k) ≥ its stress-quantile across paths
CVA_WWR     = −LGD · Σ_k DF(t_k) · (1/P) Σ_p E(p,k) ΔPD_p(k)
CVA_IND     = −LGD · Σ_k DF(t_k) · EE(k) · ΔPD_market(k)    (CVA.md's CVA from the market curve)
WWR_RATIO   = CVA_WWR / CVA_IND
EEPE_DEF    = EEPE computed on EE_DEF                       an "alpha-like" view: EEPE_DEF / EEPE
FLAGS       GENERAL when WWR_RATIO > ratio-threshold or ρ̄ > rho-threshold;  RIGHT_WAY when WWR_RATIO < 1 / ratio-threshold
```

**Hull–White without credit paths:** λ(p, k) = exp(a(k) + b · E(p, k)), with a(k) solved per grid date so that (1/P) Σ_p S_p(t_k) equals
the market survival S(t_k) (a one-dimensional root search per date); then the formulas above.

### 6.4 Additivity

Nothing in §6.3 is additive across counterparties (each uses its own hazard; ratios and correlations do not add). CVA_WWR adds across
counterparties, and across the netting sets of one counterparty (it is linear in E per path given the counterparty's ΔPD_p); a scope's
CVA_WWR is the sum of its counterparties'. Specific flags and JTD amounts add.

### 6.5 Where it runs

Python engine `drishti_ccr.wwr:WwrEngine`, per counterparty: its summed exposure matrix (P × G_primary, from EXP's hot file: 2 MB) and its
hazard matrix (2 MB), numpy. Computed for every counterparty once per date after EXP's load (batch class, split over workers: 20,000
counterparties × a few ms); interactive for what-ifs and parameter changes.

## 7. What Drishti builds

| Structure | Content | Size, large bank |
|---|---|---|
| hazard matrices | per counterparty, float32 P × G (from `credit-path`), in a per-date hot file beside EXP's (`<hot-folder>/<date>/<RUN_ID>.chot`, the same layout) | 20,000 × 5,000 × 100 × 4 = 40 GB on disk, mapped |
| statistics per counterparty | ρ(k), EE_DEF(k), EE_STRESS(k) on primary dates, CVA_WWR, CVA_IND, ratio, flags | 20,000 × 50 × 3 × 8 + … = 25 MB |
| specific flags | per trade and holding: flag, matched reference, JTD | 1,000,000 × 32 bytes = 32 MB |

## 8. Scopes

`CPTY:` is the natural scope (hazard is per counterparty). `NSET:` uses the netting set's exposure with its counterparty's hazard. `GROUP:`,
`LE:`, `BANK`: the list of counterparties with their flags, sums of CVA_WWR and JTD, worst ratio first. A partial scope uses EXP's partial
exposure.

## 9. What Drishti answers

### 9.1 The computed kind `wwr-result`

| Field | Type | Unit | Meaning |
|---|---|---|---|
| `SCOPE`, `CANONICAL`, `SCOPE_KIND`, `LABEL`, `BUSINESS_DATE`, `MODEL` | | | `credit-paths` or `hull-white` (with b) |
| `FLAGS` | text list | | `SPECIFIC`, `GENERAL`, `RIGHT_WAY`, `WRONG_WAY_COLLATERAL`, `jtd-not-modelled` |
| `SPECIFIC_TRADES`, `SPECIFIC_JTD` | integer, amount | USD | count and Σ JTD |
| `WRONG_WAY_COLLATERAL` | amount | USD | value of collateral issued by the counterparty or its group |
| `RHO_1Y` | rate | | ρ̄ |
| `CVA_WWR`, `CVA_IND`, `WWR_RATIO` | amount, amount, rate | USD | §6.3 |
| `EEPE`, `EEPE_DEF`, `EEPE_STRESS` | amount | USD | |
| `PROFILE` | record list | | per primary date: `TENOR`, `T`, `EE`, `EE_DEF`, `EE_STRESS`, `RHO`, `MEAN_HAZARD` |
| `SPECIFIC` | record list | | per flagged trade or holding: `TRADE_ID` or `HOLDING_ID`, `REFERENCE`, `MATCHED_BY` (`lei`, `group`, `issuer`, `isin`, `bank-flag`), `RELATION` (the counterparty itself, parent, subsidiary), `JTD`, `MTM` |
| `BY_COUNTERPARTY` | record list | | above a counterparty: `COUNTERPARTY`, `FLAGS`, `WWR_RATIO`, `CVA_WWR`, `SPECIFIC_JTD` |
| `PROVENANCE` | record | | NSET's, plus EXP's run, `credit-path` presence, the model and its parameters |

### 9.2 The API (`/api/v1/wwr`, planned)

| Method and path | Parameters | Answer |
|---|---|---|
| `GET /scope` | `scope`, `asOf`, `model`, `b` (Hull–White override) | §9.1 |
| `GET /flags` | `scope` (default `BANK`), `flag` | every flagged counterparty and trade |
| `POST /what-if` | `{scope, candidates}` (EXP's body) | the candidate's effect on the ratio and specific flags (a pre-deal warning used by LIM.md's check) |
| captures, status, reload | as NSET | |

### 9.3 Published official results (`wwr-official`, `wwr-official-cpty`)

The two tables of [NSET.md section 9.5](NSET.md#95-published-official-results-nset-official-nset-official-cpty). Node rows:
`SPECIFIC_TRADES` (BIGINT), `SPECIFIC_JTD`, `WRONG_WAY_COLLATERAL`, `CVA_WWR`, `CVA_IND` (DOUBLE, USD), `FLAGGED_COUNTERPARTIES`
(BIGINT). Counterparty rows (restricted) add `FLAGS` (STRING, comma-separated), `RHO_1Y`, `WWR_RATIO`, `EEPE`, `EEPE_DEF`,
`EEPE_STRESS` (DOUBLE), `MODEL` (STRING).

### 9.4 The reactive connector and the engine (proposed)

```yaml
# config/connectors/wwr-engine.yaml                             (planned, 2.10.0)
plugin: reactive
mode: reactive
description: Specific and general wrong-way risk for any counterparty scope
kinds:
  wwr-result:
    compute: wwr
    params:
      model: { type: enum, options: [auto, credit-paths, hull-white], default: auto }
      b:     { type: decimal, min: -1, max: 1 }                  # Hull–White override; empty: the configured value
    result: { required: [FLAGS, WWR_RATIO] }
reads:
  - { kind: exposure-result, as: exposure, id: "{id}" }
  - { kind: credit-path, as: hazard, vectors: [HAZARD], all: true }
  - { kind: saccr-trade, as: classification, all: true }
  - { kind: collateral-holding, as: holdings, all: true }
  - { kind: counterparty, as: counterparties, all: true }
  - { kind: credit-curve, as: curves, all: true }
  - { kind: group-usage, as: group, id: "{id}" }
engine:
  type: python
  entry: drishti_ccr.wwr:WwrEngine
  pool: risk
  version: "1.0"
  split: counterparties
  chunk-rows: 200
  reduce: { sum: [CVA_WWR, CVA_IND, SPECIFIC_TRADES, SPECIFIC_JTD, WRONG_WAY_COLLATERAL] }
triggers:
  - { on: data-version, kinds: [exposure-path, credit-path, saccr-trade, collateral-holding, counterparty, credit-curve, lei-relationship], affects: all }
coalesce: { quiet: 1s, max-wait: 10s }
cache: { results: 2000, ttl: 1d }
limits: { timeout: 120s, wait: 100ms, max-inflight-per-user: 2 }
requires: { python: { version: "3.12", lock: requires/drishti-ccr.lock } }
```

```python
# compute/drishti-ccr/src/drishti_ccr/wwr.py                    (planned, 2.10.0)
class WwrEngine(Engine):
    kinds = ("wwr-result",)
    def compute(self, req, inputs, progress):
        expo = inputs.arrow("exposure")   # COUNTERPARTY, GRID_INDEX, T float64, E fixed_size_list<float32>[P]  (summed sets)
        haz = inputs.arrow("hazard")      # COUNTERPARTY, GRID_INDEX, HAZARD fixed_size_list<float32>[P]     (or empty: Hull–White)
        curve = inputs.arrow("curves")    # COUNTERPARTY, T, SURVIVAL, LGD
        spec = inputs.arrow("specific")   # TRADE_ID or HOLDING_ID, COUNTERPARTY, REFERENCE_LEI, MATCHED_BY, NOTIONAL, RECOVERY, TERMS
        ...                               # sections 6.2 and 6.3
        return Result(rows=[totals], tables={"by_counterparty": ..., "profile": ..., "specific": ...})
```

## 10. Validation, errors and data quality

| Check | Rule | Outcome |
|---|---|---|
| Same run | `credit-path.RUN_ID` = the exposure run; same P | credit paths ignored, model falls back to Hull–White, flagged |
| Vector length, finiteness | as EXP | row rejected |
| Hazard | ≥ 0 | row rejected |
| Calibration | the mean path survival within 5% of the market curve at each date (credit-paths) | flagged `hazard-off-market` |
| Coverage | counterparties with exposure but no credit path | Hull–White for them, flagged |
| References | a reference that resolves to no LEI | not matched, counted `unresolved-references` |

**Proposed DRS codes** (5480–5489): `DRS-5480` (400, bad scope or parameter), `DRS-5481` (409, the Hull–White calibration does not
converge for a counterparty: the answer lists it and uses independence).

## 11. Entitlements and audit

As NSET.md section 11. Specific flags reveal trades and holdings: shown at `trades` level only. A wrong-way flag on a counterparty may be
sensitive in itself (credit committee material): the role setting can restrict `FLAGS` and `SPECIFIC` to credit risk. `ccr-view`
audited.

## 12. Performance and sizing

| Operation | Target (p95, warm) |
|---|---|
| a counterparty (statistics precomputed) | 10 ms |
| a counterparty recomputed (new b, what-if) | 200 ms |
| every counterparty after the date load (20,000) | 10 minutes, batch class, split over workers |
| specific flags of the bank | 5 s at load |

## 13. Test data and generators

`tools/packgen/ccr/paths.py` (planned) writes `credit-path` rows for the 8 committed netting sets' counterparties on the same paths:
λ(p, k) = λ₀ · exp(β · X_c(p, k) − ½ β² t_k) with X_c the asset-class factor of EXP's model, λ₀ from the counterparty's curve, β > 0 for one
counterparty whose trades are long that factor (general wrong way), β < 0 for one (right way), 0 for the rest. `terms.py` adds one specific
case (a single-name CDS bought from a bank counterparty on a member of its own group, through the GLEIF-connected group of GRP.md) and one
wrong-way collateral holding. Sizes: 8 counterparties × 200 paths × 26 dates, about 0.2 MB compressed.

## 14. Retiring the stored version

Nothing stored to retire. The Calc snippet `config/packs/counterparty-risk/python/wrong_way_flags.py` (heuristic flags from sector and
country, reading `TVAL` and `trade` one by one, capped at 1,000 trades) is rewritten over `wwr-result`; its sector and country heuristics
become the **general flag without a model** (`GENERAL_HEURISTIC`, shown only when neither credit paths nor a calibrated b exist).

## 15. A worked example

EXP.md section 15's netting set (USD millions; E at t = 0.25, 0.5, 1.0; LGD 60%; no discounting). The run also carries the counterparty's
hazard, constant over time on each path: path 1 **4%**, path 2 **1%**, path 3 **3%**, path 4 **0%** (mean 2%, the market curve's flat
hazard). The paths with high exposure (1 and 3) are the paths where the counterparty is weak: wrong way.

| Path | E (t₁, t₂, t₃) | λ | ΔPD (t₁, t₂, t₃) = e^(−λ t_{k−1}) − e^(−λ t_k) |
|---|---|---|---|
| 1 | 2, 3, 1 | 4% | 0.0099502, 0.0098512, 0.0194092 |
| 2 | 0, 1, 0 | 1% | 0.0024969, 0.0024906, 0.0049626 |
| 3 | 1, 2, 2 | 3% | 0.0074719, 0.0074161, 0.0146664 |
| 4 | 1, 0, 0 | 0% | 0, 0, 0 |

| k | (1/4) Σ_p E · ΔPD_p | EE | EE_DEF = Σ E·ΔPD / Σ ΔPD | ρ(E, λ) |
|---|---|---|---|---|
| 1 (0.25) | 0.0068431 | 1.0 | 1.374 | 0.671 |
| 2 (0.5) | 0.0117191 | 1.5 | 2.373 | 0.990 |
| 3 (1.0) | 0.0121855 | 0.75 | 1.249 | 0.763 |

- **CVA_WWR** = −0.6 × (0.0068431 + 0.0117191 + 0.0121855) = −0.0184486m = **−18,449 USD**
- **CVA_IND** (CVA.md section 15, flat 2%) = **−11,892 USD**
- **WWR_RATIO** = 18,449 / 11,892 = **1.55** > 1.2: flag **GENERAL**; ρ at 6 months 0.99
- **EE_STRESS** at q = 0.75 (the worst quarter of credit paths, here path 1): 2, 3, 1 against EE 1.0, 1.5, 0.75: twice the average exposure
- **EEPE_DEF** with EE_DEF = 1.374, 2.373, 1.249 (running max 1.374, 2.373, 2.373; Δt 0.25, 0.25, 0.5): 0.3435 + 0.5933 + 1.1865 = **2.12**,
  against EEPE 1.375: a ratio of 1.54, close to the CVA ratio

**A specific case:** the counterparty is a bank, and the set also holds a single-name CDS on the bank's own parent (found through GLEIF),
protection bought, notional 10m, recovery 40%, MtM +0.2m. Flag **SPECIFIC**, matched by `group`; JTD = 10m × (1 − 0.4) − 0 = **6.0m**; the
CDS leaves the netting set and is its own set with exposure 6.0m in default (SA-CCR treatment: open question 2).

## 16. Open questions and the change table

### 16.1 Open questions

| # | Question | Context |
|---|---|---|
| 1 | Will the upstream simulator produce joint credit paths (`credit-path`)? If not, Hull–White with which b per sector, calibrated by whom? | §3.2, §6.3 |
| 2 | Specific WWR under SA-CCR (the bank uses SA-CCR, not IMM): separate netting set with EAD = α × JTD (proposed, following CRE53's spirit), or flags only? | §6.2, SACCR.md |
| 3 | Thresholds for the `GENERAL` flag (ratio 1.2, ρ 0.3) and who reviews the flags (credit committee, a monthly report) | §6.1 |
| 4 | Sovereign–bank wrong-way risk (a domestic bank's collateral in its sovereign's bonds): flag only, or a haircut add-on in COLL? | §6.1 |
| 5 | WWR-adjusted CVA as the official CVA, or a monitoring figure beside it (proposed)? | §6.3, CVA.md |

### 16.2 The change table

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-25 | First version, PLANNED for 2.10.0; product owner's decisions of 2026-10-25 applied (load signal reloads the engine, inputs kept 7 years with EXP's retention question, published official results) |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
