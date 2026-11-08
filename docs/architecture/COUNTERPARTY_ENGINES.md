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
# Counterparty engines: SA-CCR, the Exposure playground, FVA and the CCR cube core

Status: design, not built. The data contracts are written (`status: planned`); the engines, screens and playgrounds below are the
design to build. The product owner decided every open point on 2026-11-02 ([section 14](#14-decisions)). The plan's rows are in [the implementation plan](IMPLEMENTATION_PLAN.md#2912-counterparty-engines-proposed).
Companion to [SACCR.md](../contracts/SACCR.md) (the SA-CCR contract this document narrows to a build), [NSET.md](../contracts/NSET.md)
(netting sets and CSAs), [COLL.md](../contracts/COLL.md) (collateral values), [CVA.md](../contracts/CVA.md) (the xVA formulas, FVA among
them), [LIM.md](../contracts/LIM.md) (limits), [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md) (the exposure engine, built),
[CCR_CUBE_LENS.md](LENSES.md#7-ccr-cube-lens) (the CCR cube: landing A with tabs B and C, decided) and the
[VaR Playground](../guides/VAR_GUIDE.md) (the playground frame every metric here reuses).

Drishti computes counterparty figures the way it computes VaR: **from trade-grain inputs, when asked, for any scope, never stored as a
figure.** SA-CCR exposure at default comes from one classification row per derivative (`saccr-trade`) and the netting set's terms;
expected and potential future exposure come from the bank's simulated paths (`exposure-path`); FVA comes from the exposure profile and a
funding spread. Every figure opens in a playground that shows the same calculation as spreadsheet formulas, and the CCR cube pivots the
results over counterparties, groups, desks and tenors.

## Contents

- [1. Scope and principles](#1-scope-and-principles)
- [2. What each engine computes, from which fields](#2-what-each-engine-computes-from-which-fields)
- [3. SA-CCR end to end](#3-sa-ccr-end-to-end)
- [4. The Exposure playground: EE, EPE, EEPE and PFE for one netting set](#4-the-exposure-playground-ee-epe-eepe-and-pfe-for-one-netting-set)
- [5. FVA: funding cost and benefit](#5-fva-funding-cost-and-benefit)
- [6. The CCR cube core: data flow](#6-the-ccr-cube-core-data-flow)
- [7. API, command line and screens](#7-api-command-line-and-screens)
- [8. The playgrounds](#8-the-playgrounds)
- [9. The tests that prove it: hand-built cases](#9-the-tests-that-prove-it-hand-built-cases)
- [10. Engine and data tests](#10-engine-and-data-tests)
- [11. Parameters as configuration (drafts)](#11-parameters-as-configuration-drafts)
- [12. Mock-ups: the screens to build](#12-mock-ups-the-screens-to-build)
- [13. The playgrounds' explanations](#13-the-playgrounds-explanations)
- [14. Decisions](#14-decisions)

## 1. Scope and principles

| Part | What it is | Reference |
|---|---|---|
| SA-CCR engine | exposure at default (EAD) of a derivative netting set by the standardised approach: replacement cost, add-ons by asset class and hedging set, the multiplier, alpha | Basel Framework CRE52 (BCBS first) |
| SA-CCR playground (`PLAY SACCR`) | one netting set's EAD worked trade by trade and hedging set by hedging set, every derived cell a formula | the VaR Playground's frame |
| Exposure playground (`PLAY EXP`) | one netting set's EE, ENE, EEE, PFE at a quantile, EPE, EEPE, CE, informational IMM EAD: the simulated paths as data, every step after them a formula | Basel CRE50 (definitions), CRE53 (effective EE, effective EPE, alpha) |
| FVA engine and playground (`PLAY FVA`) | funding cost (FCA) on EE and funding benefit (FBA) on ENE, discounted, by the funding spread | market practice (IFRS 13 fair value; Gregory, *The xVA Challenge*) |
| CCR cube core | the CCR cube on the shared cube core: the limit board (counterparty × tenor bucket), the treemap, the PFE fan with the limit line, allocated exposure by desk, the group roll-up over the legal tree, the netting-set drill, the pre-deal overlay | [CCR_CUBE_LENS.md](LENSES.md#7-ccr-cube-lens) sections 6 to 8 and 12 |

**Principles.**

1. **Classification is delivered, formulas are Drishti's.** The regulatory classification of a trade (asset class, hedging set, direction,
   option terms) is an input the bank signs off (`saccr-trade`, [SACCR.md section 3.2](../contracts/SACCR.md#32-decision-an-input-table-with-drishti-computing-the-formula-parts));
   every number that follows (supervisory duration, delta, maturity factor, effective notional, add-ons, RC, multiplier, EAD) is computed.
   A delivered row that carries a computed figure is refused.
2. **Computed, never stored.** EAD, EE, PFE, FVA are computed for the scope asked and kept only as official captures. The stored `sa-ccr`
   kind retires when the SA-CCR engine is built; the stored `cva` kind's FVA field is not read.
3. **Parameters are configuration, with their source.** Supervisory factors, correlations, option volatilities, MPOR floors and alpha live
   in a versioned parameter file naming its CRE52 paragraph per value (section 11). A changed file is a new version; every answer names the
   version it used.
4. **Every figure explains itself.** Each EAD shows its hedging sets and trades; each PFE its paths at the quantile; each FVA its terms per
   date. Each opens in its playground (section 8), where every derived cell is a spreadsheet formula and a check cell matches the engine to
   the cent.
5. **Nothing is guessed.** A derivative without a classification row is counted `unclassified` and its set marked `incomplete`; a
   securities financing trade is counted `excluded`; a margined set with missing CSA terms is treated as unmargined and flagged. Never a
   silent zero.

## 2. What each engine computes, from which fields

| Engine | Computes | Reads (kind: fields) |
|---|---|---|
| **SA-CCR** | per trade: S, E, M, T in years, SD, d, δ, MF (margined and unmargined), D, IR maturity bucket; per set: V, C, NICA, RC, add-ons per hedging set and asset class, AddOn_agg, multiplier, PFE, EAD (margined, unmargined, capped), RWA hook | `saccr-trade`: `TRADE_ID`, `DRIVER`, `NETTING_SET`, `ASSET_CLASS`, `HEDGING_SET`, `TRANSACTION_TYPE`, `REFERENCE`, `IS_INDEX`, `CREDIT_QUALITY`, `DIRECTION`, `NOTIONAL_USD`, `QUANTITY`, `UNDERLYING_PRICE`, `START_DATE`, `END_DATE`, `MATURITY_DATE`, `OPTION_TYPE`, `BOUGHT_SOLD`, `EXERCISE_DATE`, `STRIKE`, `UNDERLYING_FORWARD`, `SHIFT`, `ATTACHMENT`, `DETACHMENT`, `CLEARED`, `DERIVED_BY`; `netting-set`: `CSA`, `MARGINED`, `CLEARED`, `NETTING_ENFORCEABLE`, `NETTING_TYPE`; `netting-set-member`: `TRADE_ID`, `SPECIFIC_WWR`; `csa`: `THRESHOLD_THEM`, `MTA_THEM`, `MPOR_DAYS`, `MARGIN_FREQUENCY_DAYS`, `DISPUTES_LAST_2Q`, `ILLIQUID_COLLATERAL`, `SEGREGATED_IM`; `valuation`: `MTM` per member; COLL's computed `C_REG`, `NICA_REG`; `counterparty`: type and rating (RWA hook) |
| **Exposure** (built; the playground reads it) | per path and primary date: V, L, collateral C, E, N; per date: EE, ENE, PFE_q, EEE, uncollateralised EE; scalars: CE, EPE, EEPE, peak PFE and its date, peak EE, `EAD_IMM`; Euler contributions per trade | `simulation-run`: `RUN_ID`, `CALIBRATION`, `PATHS`, `GRID_DATES`, `GRID_ROLE`, `LOOKBACK_OF`, `LOOKBACK_DAYS`, `VALUE_BASIS`; `exposure-path`: `TRADE_ID`, `NETTING_SET`, `GRID_INDEX`, `MTM`, `STATUS`; `csa`: `THRESHOLD_THEM`, `THRESHOLD_US`, `MTA_THEM`, `MTA_US`, `INDEPENDENT_AMOUNT`, `ROUNDING`, `ROUNDING_METHOD`, `MPOR_DAYS`, `MARGIN_FREQUENCY_DAYS`; `collateral-balance` / `collateral-holding`: today's VM held − posted after haircut, `IM_HELD`; `valuation`: today's `MTM` (CE) |
| **FVA** | per primary date: DF, s_F, EE*, ENE*, the FCA and FBA terms; per set: FCA, FBA, FVA; sums over sets | the exposure engine's EE and ENE profile per netting set (after collateral); `ir-curve`: the discount curve's `POINTS[]`; the funding spread (`drishti.ccr.fva.funding-spread`: flat or a curve id); optionally `credit-curve` `SURVIVAL` points of the counterparty and of the bank (survival weighting, off by default) |
| **CCR cube** | cells and totals of EE, EPE, PFE, CE, limit use, headroom, EAD (SA-CCR), FVA over any rows (group, counterparty, legal entity, desk, rating, sector, country) and columns (tenor bucket, measure); allocated EE and EPE per trade; group trees; the collateral waterfall | the exposure engine's per-set path matrices and statistics; `credit-limit`: `SCOPE`, `MEASURE`, `CONFIDENCE`, `BUCKETS[].FROM_TENOR`, `TO_TENOR`, `LIMIT`, `WARNING_AT`; `counterparty`, `counterparty-group`, the legal tree; the VaR hierarchies (desk, book); `saccr-result`, `fva-result` per set |

## 3. SA-CCR end to end

Paragraph numbers follow the consolidated Basel Framework, chapter CRE52 (the version in force from 1 January 2023); the build checks each
reference against the published text and the parameter file carries them (section 11).

### 3.1 The netting set's header

For netting set *n* on the business date:

| Symbol | Meaning | From |
|---|---|---|
| V | Σ `MTM` of the set's in-scope derivative members (SFTs and cash products excluded) | `valuation` |
| C | the haircut value of net collateral held: VM, IA and IM held minus posted, **at regulatory (supervisory) haircuts** (decision 1; the exposure engine keeps the CSA's contractual haircuts and both pages say which); includes NICA | COLL `C_REG` |
| NICA | net independent collateral amount: IA and IM held minus IA and IM posted that is not segregated (CRE52.15 to CRE52.17) | COLL `NICA_REG` |
| TH | `THRESHOLD_THEM`: the exposure the counterparty may run before it must post | `csa` (converted to USD at today's FX) |
| MTA | `MTA_THEM`: the counterparty's minimum transfer amount | `csa` |
| margined | the set's `MARGINED` (a CSA with VM) with TH, MTA, MPOR present; else unmargined and flagged | `netting-set`, `csa` |

A set whose `NETTING_ENFORCEABLE` is false is not a set: every member is its own unmargined set (`NS-STANDALONE~<TRADE_ID>`). A member
flagged `SPECIFIC_WWR` is taken out into its own set, with no netting benefit (decision 7).

### 3.2 Time parameters (CRE52.30 to CRE52.33)

| Symbol | Meaning | Floor | Unit |
|---|---|---|---|
| M_i | maturity: the latest date the trade may still be active (`MATURITY_DATE`) | 10 business days | business days / 250 for MF |
| S_i | start of the referenced period (`START_DATE`, IR and credit) | 0 | ACT/365 years |
| E_i | end of the referenced period (`END_DATE`, IR and credit; also the IR maturity bucket) | 10 business days | ACT/365 years |
| T_i | the latest contractual exercise date (`EXERCISE_DATE`, options) | | ACT/365 years |

### 3.3 Replacement cost (CRE52.3 to CRE52.19)

```
unmargined   RC = max(V − C, 0)                                  CRE52.10
margined     RC = max(V − C, TH + MTA − NICA, 0)                 CRE52.18
```

The middle term of the margined RC is the largest exposure the bank can carry without a margin call: the threshold plus the minimum
transfer amount, less the independent collateral it holds.

### 3.4 Supervisory duration and adjusted notional (CRE52.34 to CRE52.37)

```
SD_i = ( exp(−0.05 · S_i) − exp(−0.05 · E_i) ) / 0.05                IR and credit
d_i  = NOTIONAL_USD_i · SD_i                                          IR and credit
d_i  = NOTIONAL_USD_i                                                 FX: the foreign leg in USD (two non-USD legs: the larger)
d_i  = QUANTITY_i · UNDERLYING_PRICE_i   (or NOTIONAL_USD_i)          equity, commodity
```

### 3.5 Supervisory delta (CRE52.38 to CRE52.41)

```
LINEAR, BASIS, VOLATILITY      δ = +1 LONG, −1 SHORT in the primary risk factor
OPTION (σ supervisory option volatility of the sub-class, λ = SHIFT or 0):
        d1 = ( ln((P + λ) / (K + λ)) + ½ · σ² · T ) / ( σ · √T )
        bought call +Φ(d1)     sold call −Φ(d1)     bought put −Φ(−d1)     sold put +Φ(−d1)
CDO_TRANCHE (A = ATTACHMENT, D = DETACHMENT):
        δ = +15 / ((1 + 14·A) · (1 + 14·D))  long credit quality (protection sold);  − the same when bought
```

P is `UNDERLYING_FORWARD` and K is `STRIKE`, in the same unit. λ (CRE52.41) lets the formula work on rates that can be negative; P + λ and
K + λ must be positive (a row failing it is refused). A sold option's delta has the opposite sign of the bought one: selling a call is short
the underlying.

### 3.6 Maturity factor and the margin period of risk (CRE52.42 to CRE52.53)

```
unmargined   MF_i = √( min(max(M_i, 10 bd), 250 bd) / 250 bd )                      CRE52.48
margined     MF   = 1.5 · √( MPOR_n / 250 )                                          CRE52.52, one value for every trade of the set
MPOR_n       = max(floor_n, MPOR_DAYS) + F − 1,   F = MARGIN_FREQUENCY_DAYS           CRE52.50 (as SACCR.md section 6.3; decision 2)
floor_n      = 5 cleared · 10 bilateral · 20 when the set has more than 5,000 trades or ILLIQUID_COLLATERAL      CRE52.50
               doubled when DISPUTES_LAST_2Q > 2                                                                 CRE52.51
```

The CSA's `MPOR_DAYS` excludes the remargining period (NSET.md states it), so the remargining lag F − 1 is added after the floor
(decision 2): the conservative reading.

### 3.7 Effective notional, hedging sets and add-ons

`D_i = δ_i · d_i · MF_i` is the signed effective notional of a trade. Trades are grouped by asset class (CRE52.25, CRE52.26) and hedging
set (CRE52.27 to CRE52.29); a basis transaction or a volatility transaction forms its own hedging set with SF × 0.5 or SF × 5.

```
IR (CRE52.54 to CRE52.57)   per currency h, maturity buckets by E: b1 E < 1y, b2 1y ≤ E ≤ 5y, b3 E > 5y
     D_b  = Σ_{i ∈ h, b} D_i
     EN_h = √( D1² + D2² + D3² + 1.4·D1·D2 + 1.4·D2·D3 + 0.6·D1·D3 )               (ir-bucket-offset off: |D1| + |D2| + |D3|)
     AddOn_IR = Σ_h SF_IR · EN_h

FX (CRE52.58, CRE52.59)     per currency pair h:     AddOn_FX = Σ_h SF_FX · | Σ_{i ∈ h} D_i |

Credit (CRE52.60 to CRE52.62), Equity (CRE52.63 to CRE52.65)   per reference entity or index k (`REFERENCE`):
     AddOn_k     = SF_k · Σ_{i ∈ k} D_i                                                (signed)
     AddOn_class = √( (Σ_k ρ_k · AddOn_k)² + Σ_k (1 − ρ_k²) · AddOn_k² )                ρ 0.5 single name, 0.8 index

Commodity (CRE52.66 to CRE52.71)   per hedging set h (ENERGY, METALS, AGRICULTURAL, OTHER), per commodity type k:
     AddOn_k  = SF_k · Σ_{i ∈ k} D_i
     AddOn_h  = √( (ρ · Σ_k AddOn_k)² + (1 − ρ²) · Σ_k AddOn_k² ),  ρ = 0.4
     AddOn_COM = Σ_h AddOn_h

AddOn_agg = AddOn_IR + AddOn_FX + AddOn_CREDIT + AddOn_EQUITY + AddOn_COM                CRE52.24
```

The supervisory factors, correlations and option volatilities are CRE52.72's table, in the parameter file (section 11.1).

### 3.8 Multiplier and PFE (CRE52.20 to CRE52.23)

```
multiplier = min( 1, f + (1 − f) · exp( (V − C) / (2 · (1 − f) · AddOn_agg) ) ),  f = 0.05        CRE52.23  (1 when AddOn_agg = 0)
PFE        = multiplier · AddOn_agg                                                              CRE52.20
```

The multiplier is 1 whenever V − C ≥ 0. Below zero (the set out of the money, or over-collateralised) it falls towards the floor 5%: a
set the bank owes money on, or holds surplus collateral against, is less likely to become an exposure.

### 3.9 EAD, alpha, the cap and the RWA hook

```
EAD            = α · (RC + PFE),  α = 1.4                                                         CRE52.1
margined set   EAD = min( EAD_margined, EAD_unmargined )                                          the cap on margined sets (CRE52.1 to CRE52.2)
               EAD_unmargined: the same set with RC = max(V − C, 0) and each trade's unmargined MF
RWA (hook)     = EAD · RW(counterparty)                                                           informational; the bank's capital engine owns RWA
```

`CAPPED` is true when the unmargined figure is the smaller. The cap binds for short-dated margined sets with a large threshold: their
unmargined maturity factors are below 1 while the margined RC carries the whole TH + MTA − NICA (case S5).

### 3.10 Scopes, incremental and standalone EAD

EAD **adds over netting sets**: a counterparty's EAD is the sum of its sets', a legal entity's the sum of the sets it faces. Nothing below
a set adds (square roots, absolute values, the multiplier). A scope that cuts a set (a desk inside a counterparty) is computed on the trades
in the scope as if they were the set and flagged `partial`. **Incremental EAD** adds candidate `saccr-trade` rows (with an estimated MtM, 0
for an at-market trade) to the set's aggregates and recomputes V, multiplier, RC, PFE and EAD: `INCREMENTAL_EAD = EAD_AFTER − EAD_BEFORE`.
**Standalone EAD** is a trade's EAD as its own unmargined set (V its MtM, C = 0). Per-trade figures never add up to the set's EAD and are
never shown as a decomposition.

### 3.11 Refusals, exclusions and flags

| Case | Outcome |
|---|---|
| `TRANSACTION_TYPE`, `ASSET_CLASS`, `DIRECTION`, `CREDIT_QUALITY`, `HEDGING_SET` invalid; option or tranche terms incomplete; P + λ or K + λ not positive; S > E or E > M | the row is refused with its DRS code ([SACCR.md section 10](../contracts/SACCR.md#10-validation-errors-and-data-quality)) and counted |
| a live derivative member without a `DRIVER` 0 row | counted `unclassified` on every page; the set `incomplete`; its EAD excludes it and says so; `strict=true` refuses the answer (`DRS-5411`). A bank that reports from Drishti may switch on the conservative stand-in instead (`drishti.ccr.saccr.unclassified: stand-in`: the trade's notional at the largest SF of its asset class, unmargined, flagged `STAND_IN`; decision 3) |
| an SFT or cash product | ignored, counted `excluded` (out of SA-CCR; CRE22 covers SFTs) |
| a margined set without TH, MTA or MPOR | computed unmargined, flagged `MARGIN_TERMS_MISSING` |
| a row computed by the configured rules | `DERIVED_BY: rules`, counted in provenance |

### 3.12 Where it runs

| Work | Where |
|---|---|
| per trade: S, E, M, T, SD, δ, both MFs, both D; per set and (asset class, hedging set, sub-key): Σ D margined and unmargined | Java at date load (`com.ash.drishti.banking.compute.ccr.SaccrAggregates`, planned): additive sums, 64 bytes an aggregate row |
| RC, EN, add-ons, multiplier, PFE, EAD, the cap; what-if; standalone | Python `drishti_ccr.saccr:SaccrEngine` on the `risk` pool, Arrow in and out, split by netting set (sums reduce) |
| the reference implementation for tests | `tools/packgen/ccr/ccrmath.py` (the exposure maths already there; SA-CCR added), parity-tested against both |

## 4. The Exposure playground: EE, EPE, EEPE and PFE for one netting set

The exposure engine is built ([EXPOSURE_GUIDE.md section 18](../guides/EXPOSURE_GUIDE.md#18-the-computation)); the playground shows its
calculation for one netting set: **the simulated paths are the data, every step after them a formula.** Drishti does not simulate markets:
the paths are the bank's (`exposure-path`).

### 4.1 The time grid

The run's `GRID_DATES` with their `GRID_ROLE`: `PRIMARY` dates where exposure is reported, `LOOKBACK` dates a margin period of risk before
them (`LOOKBACK_OF`). Time in years ACT/365 from the business date t₀; `Δt_k = t_k − t_{k−1}` over primary dates. A primary date whose
look-back would fall on or before t₀ has no look-back point: its collateral is today's.

### 4.2 Netting and collateral on each path

```
V(p,k)    = Σ_i MTM_i(p,k)                                               net value of the set on path p at grid date k
target(v) = max(v − TH_them, 0) − max(−v − TH_us, 0) + IA                the collateral the CSA calls at value v
lagged    C(p,k) = R( target(V(p, ℓ(k))) ),  held side: C = max(C − MTA_them, 0) when C > 0
current   C(p,k) = C₀ (today's VM held − posted, after haircut)
none      C(p,k) = 0
E(p,k)    = max(V − C − IM, 0)        N(p,k) = min(V − C, 0)             IM held today on every path (im-model: current)
```

An independent amount is collateral held on every path, unmargined sets included, and Config says so (decision 8). R is the CSA's
rounding applied to the change from today's collateral (deliveries up, returns down). The MPOR of the CSA plus F − 1 is
compared with the run's `LOOKBACK_DAYS`; when they differ the run's look-back is used and the answer flagged `MPOR_MISMATCH`.

### 4.3 Statistics (CRE50 definitions; CRE53 for effective EE, EEPE and alpha)

```
EE(k)    = (1/P) Σ_p E(p,k)                                expected exposure
ENE(k)   = (1/P) Σ_p N(p,k)                                expected negative exposure (≤ 0)
PFE_q(k) = the ⌈P·q − 10⁻⁹⌉-th smallest of E(·,k)           rank-rule kth; or interpolated
EEE(k)   = max(EEE(k−1), EE(k)),  EEE(0) = CE              effective EE, never falls
T        = min(1 year, the longest remaining maturity in the set)
EPE      = Σ_{t_k ≤ T} EE(k) · (min(t_k, T) − t_{k−1}) / T
EEPE     = Σ_{t_k ≤ T} EEE(k) · (min(t_k, T) − t_{k−1}) / T
PEAK_PFE = max_k PFE_q(k), the earliest date on a tie      PEAK_EE = max_k EE(k)
CE       = max(MTM₀ − C₀ − IM, 0)                          today
EAD_IMM  = α · EEPE,  α = 1.4                              informational only (needs supervisory approval of the bank's model)
```

### 4.4 Contributions at a date (Euler)

```
w_i(p,k) = MTM_i(p,k) / V(p,k)   (0 when V = 0)
a_i(p,k) = ( MTM_i(p,k) − w_i(p,k) · (C(p,k) + IM) ) · 1{E(p,k) > 0}       Σ_i a_i(p,k) = E(p,k) on every path
EE_i(k)  = (1/P) Σ_p a_i(p,k)                                              Σ_i EE_i(k) = EE(k)
```

The same shares are the CCR cube's allocated exposure (section 6.3).

### 4.5 Sizes

A netting set holds 1 to a few thousand trades, P = 200 (the demo) to 5,000 paths and G = 13 to 100 primary dates plus their look-backs.
The Data tab carries one column per (trade, grid date) and one row per path. Over the limits, the largest trades by |EE contribution at
the peak date| get their own columns and **Rest of set** carries the summed MTM of the others per grid date, so V, and therefore every
figure, is still the set's to the cent (as VaR's Rest of scope). Limits in section 8.2.

## 5. FVA: funding cost and benefit

### 5.1 What it is

Uncollateralised positive exposure must be funded by the bank at its own funding spread over the risk-free (OIS) rate; negative exposure
funds the bank. FVA prices both over the life of the netting set, from the same EE and ENE profile as CVA ([CVA.md section 6.3](../contracts/CVA.md#63-cva-dva-fva)):

```
EE*(t_k)  = EE(t_k) · DF(t_k)          ENE*(t_k) = ENE(t_k) · DF(t_k)          EE and ENE after collateral, VALUE_BASIS FUTURE
FCA       = − Σ_k EE*(t_k)   · s_F(t_k) · Δt_k        [· S_C(t_k) · S_B(t_k) when funding-survival is on]
FBA       = + Σ_k |ENE*(t_k)| · s_F(t_k) · Δt_k        [· S_C(t_k) · S_B(t_k)]
FVA       = FCA + FBA
```

Signs follow CVA.md section 5: valuation adjustments from the bank's side, FCA ≤ 0 (a cost), FBA ≥ 0 (a benefit). Units: USD.

### 5.2 The parts

| Part | Rule | Setting (`drishti.ccr.fva.*`) |
|---|---|---|
| Funding spread s_F(t) | a curve id (points by tenor), linear in t between points, flat beyond; the demo names a synthetic, labelled funding curve in the market-data pack and a bank names its own; the flat spread is the fallback when no curve is named (decision 12) | `funding-spread` (default `CRV-USD-FUNDING`; fallback `0.0050`) |
| Discount factor DF(t) | from the discount curve's points, log-linear in DF between points; DF = 1 when `VALUE_BASIS` is `DISCOUNTED` | `discount-curve` (the USD OIS curve id) |
| EE point | `end`: EE*(t_k) for the period (t_{k−1}, t_k]; `mid`: the average of both ends, EE*(t₀) = CE | `ee-point` (default `end`) |
| Survival weighting | off: funding until maturity; on: × S_C(t_k) · S_B(t_k), funding stops at either default (decision 11: off by default) | `funding-survival` (default `false`) |
| FBA | reported (symmetric funding) or left out (asymmetric: FCA only); symmetric by default, and DVA, when built, states its overlap with FBA (decision 11) | `fba` (default `true`) |
| Run | the exposure run's `CALIBRATION`: in production FVA on a `REAL_WORLD` run is refused; the demo profile computes it and flags `REAL_WORLD_RUN` on every number until a risk-neutral demo run exists (decision 10) | `require-risk-neutral` (default `true`; the demo profile `false`) |
| Exposure | EE and ENE after the CSA (collateralised), as CVA.md: collateral received can be reused to fund, so a set with a CSA has little FVA (decision 13) | |

### 5.3 The EPE reading

With a flat spread and DF = 1, `FCA = −s_F · Σ_k EE(t_k) · Δt_k = −s_F · T_life · EPE_life`, where EPE_life is the time average of EE
over the whole life of the set (not the regulatory first year). Main shows this reading beside FCA: it is the plan's "EPE × funding
spread" and explains FVA in one line. FBA reads the same with |ENE|.

### 5.4 Additivity and where it runs

FCA, FBA and FVA are linear in EE and ENE, which add across netting sets, so FVA adds over sets and counterparties: computed per set at date
load from the per-set profile, a scope is a sum. A partial scope uses its partial profile. The computation runs in the Python engine
`drishti_ccr.xva:XvaEngine` (planned; the CVA family later adds CVA and DVA to the same engine) on the `risk` pool; the computed kind is
`fva-result` with `/api/v1/fva` (decision 9). When CVA is built, `cva-result` reads FVA by reference to `fva-result` rather than
carrying a second copy ([CVA.md section 16](../contracts/CVA.md#16-open-questions-and-the-change-table)).

## 6. The CCR cube core: data flow

The CCR cube is the shared cube core ([MR_CUBE_LENS.md section 4](LENSES.md#65-the-shared-cube-core-mr-and-ccr), MC2 and
MCP1) with CCR measures. The layouts are decided: **landing A (limit board) with tabs B (netting-set drill) and C (pre-deal and XVA)**
([CCR_CUBE_LENS.md section 6.7](LENSES.md#74-mock-ups-and-build-checklist)).

### 6.1 The flow of a date

```
exposure-path ──(built: ExposureStore, per-set V and E matrices at date load)──┐
simulation-run, csa, collateral, valuation ────────────────────────────────────┤
                                                                               ▼
      per netting set n:  E_n(p,k), N_n(p,k), today's CE                       the exposure engine (built)
             │                 │
             │                 ├── per-set EE, ENE profile ──► FVA per set (section 5) ──► fva-result, additive
             │                 │
             │                 └── a_i(p,k) per trade (Euler, section 4.4) ──► allocated EE_i(k), EPE_i at load (CC2), cached
             │                                                                     └── summed by desk, book, legal entity, limit letter
             ▼
      scope matrices: E_S(p,k) = Σ_{n ∈ S} E_n(p,k)    (counterparty, group over the legal tree (CC3), legal entity, bank)
             │
             └── statistics last: PFE_q, peak, EPE, EEPE  ──► limit board per bucket, use, headroom, binding tenor (CC4)
                                                         ──► cube cells and totals (CC1): each total the quantile of its own summed matrix

saccr-trade ──(SaccrAggregates at load)──► SaccrEngine per set ──► saccr-result, EAD additive over sets ──► the cube's EAD measure
credit-limit (regenerated on the computed PFE, RL9) ──► the board's limits and the fan's step line
```

### 6.2 What the cube serves (CC1, CCP1)

| Endpoint | What | From |
|---|---|---|
| `POST /api/v1/cube/ccr/query` | a view: scope; rows (group, counterparty, legal entity, desk, rating, sector, country); columns (tenor bucket or measure); measures EE, EPE, PFE, CE, limit use, headroom, **EAD (SA-CCR)**, **FVA** → cells and totals | per-set matrices and the per-set additive results |
| `GET /api/v1/cube/ccr/limits?scope=…` | the board: peak PFE per limit bucket, limit, use, status, minimum headroom and its tenor | LIM section 6.2 and 6.3 over the computed PFE |
| `GET /api/v1/cube/ccr/allocation?scope=…&by=desk` | allocated EE and EPE (and the PFE share at the scope's peak) by desk, book, legal entity or trade | the date's allocation cache |
| `GET /api/v1/cube/ccr/group-tree?group=…&membership=connected` | the legal tree with each node's PFE, EPE and limit use | `connected(G)` (GRP.md section 6.1), members summed per path |
| `GET /api/v1/cube/ccr/waterfall?nset=…&tenor=…` | the collateral waterfall at a tenor: EE uncollateralised, VM held, posted at risk, IM held, TH and MTA effect, EE; the MPOR effect (`lagged` against `current`) | the set's matrices under each collateral model |

**Totals rule.** Additive measures (EE, ENE, EPE, CE, EAD, FVA) sum their children; non-additive ones (PFE, peak PFE, EEPE, limit use)
are computed on the cell's own summed path matrix. FVA is one of landing A's measure chips, and the counterparty's FVA is a tile of C beside
incremental EAD (decision 15). A group's PFE is the quantile of its members summed per path, never the sum of the
members' peaks (section 9, case X2).

### 6.3 Allocation (CC2), limits (CC4), groups (CC3)

- **Allocation** is the Euler share of section 4.4 for every trade of every set at every primary date, computed at date load per owner
  (decided) and cached: per trade × date, 8 bytes; summed by any hierarchy. Desks add up to the bank's EE exactly at every date (RL6, TL2).
- **Limits by tenor** read `credit-limit` with `BUCKETS[]` (`FROM_TENOR`, `TO_TENOR`, `LIMIT`, buckets (FROM, TO]); usage of a bucket is
  the largest PFE_q at a primary date inside it; `use = usage / LIMIT`, `headroom = LIMIT − usage`, status by `WARNING_AT` (0.8 default)
  as LIM section 6.3. The demo's limits are regenerated from the computed PFE (CCP2, RL9) in three buckets, 0–1Y, 1–5Y and 5Y+ (decision
  14; the contract keeps any number of buckets), so most read 30% to 70%, three near the warning level and one over.
- **Group roll-up** over a synthetic legal tree for the demo (CCP2, kept apart from the real GLEIF data): each node's matrix is the sum of
  its members' sets per path; the quantile last.

### 6.4 The proof-of-concept rows

CCP1 (the endpoints above), CCP2 (`credit_limits.py`, `legal_tree.py`), CCP3 (the lens `ccrcube`, `/ccube`, landing A with tabs B and C),
CCP4 (the pre-deal overlay over the built `addon` and `paths` what-if), CCP5 (tests: allocation adds up as a property test, group sums per
path, board buckets, browser), CCP6 (the exposure guide's cube chapter, a tutorial, screenshots, the demo script), as
[CCR_CUBE_LENS.md section 12](LENSES.md#721-the-preset-and-the-proof-of-concept). With SA-CCR computed in the same
release, the lens control A3's measure **EAD SA-CCR** is live (not greyed), the kind `saccr-result` is no longer hidden in the lens, and
C1's **Incremental EAD (SA-CCR)** tile is answered by SA-CCR's what-if.

**As built: the exposure what-if in Java** (IMPLEMENTATION_PLAN 2.9.12 row 5). The `addon` and `paths` what-if that CCP4 overlays are
both the server's exposure engine's: `POST /api/v1/risk/exposure/what-if` computes the exact method with `ExposureWhatIfMath`
(`drishti-compute-banking`, `com.ash.drishti.banking.compute.ccr`) over `ExposureMath`'s collateral and statistics, the scope and each joined netting set
read as the asker, no sandbox, no compute pool, no Python ([EXPOSURE_GUIDE.md section 19.5](../guides/EXPOSURE_GUIDE.md#195-the-exact-what-if-in-the-java-engine)).
The request and the answer are unchanged; reference answers on four netting sets under the three collateral models and both rank rules
are given to the cent (`ExposureWhatIfMathTest`). The pre-deal check is the word `EXP`'s (the explorer's Pre-deal tab, the Trading
landing's check, C's pre-deal tab); there is no separate what-if kind or command.

## 7. API, command line and screens

All planned; the names follow the VaR and exposure APIs and are fixed at build.

| What | API | Command | CLI |
|---|---|---|---|
| SA-CCR of a scope | `GET /api/v1/saccr/scope?scope=…&asOf=…` | `SACCR NSET:NS-ALDERSHOT-NY <GO>`, `SACCR CPTY:…`, `SACCR LE:…` | `drishti.py saccr show` |
| SA-CCR contributions | `GET /api/v1/saccr/scope/contributions?scope=…&by=netting-set\|asset-class\|hedging-set\|trade` | in `SACCR` | `drishti.py saccr contributions` |
| A trade's SA-CCR terms | `GET /api/v1/saccr/trade/{tradeId}` | `SACCR TRD:…` | `drishti.py saccr trade` |
| Incremental EAD | `POST /api/v1/saccr/what-if` | in the pre-deal tab (C) | `drishti.py saccr what-if` |
| FVA of a scope | `GET /api/v1/fva/scope?scope=…` | `FVA NSET:…`, `FVA CPTY:…`, `FVA BANK` | `drishti.py fva show` |
| The playgrounds | `/api/v1/saccr/playground`, `/api/v1/risk/exposure/playground`, `/api/v1/fva/playground` (and `.xlsx`, `/estimate`, `/template`) | `PLAY SACCR <netting set>`, `PLAY EXP <netting set>`, `PLAY FVA <netting set>` | `drishti.py play saccr`, `play exp`, `play fva` |
| The CCR cube | section 6.2 | `CCUBE BANK`, `CCUBE GROUP:…`, `CCUBE NSET:…` | `drishti.py cube ccr` |
| Official captures | `saccr-official`, `saccr-official-cpty` (SACCR.md section 9.5); `fva-official` per set and counterparty | — | `drishti.py saccr capture` |

**Computed kinds:** `saccr-result` (SACCR.md section 9.1), `fva-result` (section 5.4; fields `SCOPE`, `BUSINESS_DATE`, `RUN_ID`, `FCA`,
`FBA`, `FVA`, `FUNDING_SPREAD`, `EE_POINT`, `FUNDING_SURVIVAL`, `NETTING_SETS`, `BY_NETTING_SET[]`, `TERMS[]` per date, `FLAGS`,
`PROVENANCE`). The stored `sa-ccr` kind retires with the SA-CCR engine (SACCR.md section 14).

**Screens:** the SA-CCR page (replacing the stored Sutra), the FVA figures on the netting set and counterparty pages, the CCR cube's
landing and tabs (decided), the three playgrounds on the VaR Playground's frame.

## 8. The playgrounds

Every metric ships with its playground ([VAR_PLAYGROUND.md section 13](../guides/VAR_GUIDE.md#314-the-playground-frame-every-metrics-playground)): the grid
with the tall explanation panel beside it, the formula bar, Export to Excel from a version-controlled template with real formulas and
check cells, the ALL CHECKS PASS band at the top of Main. Each playground below adds its model (the cached values), its generator (the tabs
and formulas through `SeriesLayout`), its inputs as the person and its endpoints.

**Functions.** The workbooks need, beyond today's common set (`XlsxFormulas.ALLOWED`): `EXP`, `LN`, `NORM.S.DIST` (supervisory delta),
`SUMIFS` and `COUNTIFS` (hedging-set sums). All four are in Excel, LibreOffice Calc and Google Sheets; they are added to the set with the
first of these playgrounds, and the formula scanner keeps refusing anything else.

### 8.1 SA-CCR playground (`PLAY SACCR`)

Scope: one netting set (default), or up to 20 whole sets of one counterparty side by side (`drishti.ccr.saccr.playground.max-sets`;
decision 5; EAD adds over sets, so the counterparty total is a plain sum on Main); up to
5,000 trade rows in the browser and the export (`max-trades`; above it the export offers one asset class at a time). The figures are the
engine's for the same date, regulation and parameter version.

| Tab | What it holds | Typed or formula |
|---|---|---|
| **Main** | the title band, the check band, the netting set's header and every result beside the engine's figure (below) | header typed; results formulas |
| **Config** | the parameter file actually used (below) | typed |
| **Data** | the `saccr-trade` rows of the set as delivered (every column of SACCR.md section 3.3), one row per trade and driver, and each member's `MTM` | values |
| **Trades** | per row: S, E, M, T in years from the dates; SD; d; d1 and δ (options, tranches); MF unmargined and margined; D both; the IR bucket; the sub-key (currency, pair, reference, commodity type) | formulas only |
| **Hedging sets** | per (asset class, hedging set, sub-key): Σ D (`SUMIFS` over Trades), SF, ρ; IR: D1, D2, D3 and EN; FX: \|Σ D\|; credit and equity: AddOn_k, the systematic and idiosyncratic terms; commodity: per hedging set; the add-on of each, both bases | formulas only |
| **Explain** | the method in words, every term of Main and Config, worked numbers (section 13) | text |
| **Provenance** | date, batches, data versions, the parameter file's version and checksum, refused, excluded and unclassified rows with their codes, rows `DERIVED_BY: rules`, who exported it and when | values |

**Main shows**, top to bottom: the title (product, netting set, counterparty, legal entity, date, regulation); ALL CHECKS PASS or the
failed check; **the header**: `MARGINED`, `CLEARED`, trades counted, excluded, unclassified; V (Σ MTM, a formula over Data), C, NICA, TH
(`THRESHOLD_THEM`), MTA (`MTA_THEM`), `MPOR_DAYS`, `MARGIN_FREQUENCY_DAYS`, `DISPUTES_LAST_2Q`, `ILLIQUID_COLLATERAL` (typed), the MPOR floor
and MPOR (formulas), MF margined (formula); **the results** as a table with columns *Margined*, *Unmargined*, *Engine*, *Check*: RC; AddOn
IR, FX, CREDIT, EQUITY, COMMODITY; AddOn_agg; the exponent `(V − C) / (2 (1 − f) AddOn_agg)`; multiplier; PFE; EAD; then **EAD (after the
cap)**, `CAPPED`, alpha, the RWA hook (risk weight typed, RWA a formula, labelled informational); **the checks**: every result equals the
engine's to the cent; AddOn_agg equals the sum of the classes; the classes equal their hedging sets; every Data row is classified; V
equals Σ MTM.

**Config shows**, each value with its CRE52 paragraph: alpha (CRE52.1); the multiplier floor f (CRE52.23); the supervisory-duration rate
0.05 (CRE52.34); the IR bucket bounds 1 and 5 years and the cross-bucket weights 1.4 and 0.6 (CRE52.57) with the `ir-bucket-offset`
switch; the supervisory table by asset class and sub-class: SF, ρ, option σ (CRE52.72); the basis factor 0.5 and volatility factor 5
(CRE52.28, CRE52.29); the MPOR floors 10, 5 and 20 business days, the 5,000-trade threshold and the dispute rule (CRE52.50, CRE52.51); 250
business days a year (MF) and 365 days (SD, δ); the CDO delta constants 15 and 14 (CRE52.40); the regulation, the file's version and its
checksum.

### 8.2 Exposure playground (`PLAY EXP`)

Scope: one netting set. Paths: all of the run's in the export up to `max-paths` (default 5,000); 200 in the browser grid (decision 6; the
size estimate is shown before the download, as VaR's). Trades: up to 50
columns in the browser and 500 in the export by default (choices 50, 500, 2,000, All within the column limit), the rest in Rest of set.

| Tab | What it holds | Typed or formula |
|---|---|---|
| **Main** | title, check band, the run and the choices; the profile table per primary date; the headline figures, each beside the engine's (below) | choices typed; results formulas |
| **Config** | the CSA terms and the run's grid (below) | typed |
| **Data** | MTM per (trade, grid date), paths as rows; Rest of set per grid date; today's MTM per trade | values |
| **Netting** | V(p,k) per primary and look-back date: `SUM` across the date's trade columns | formulas only |
| **Collateral** | per primary date: target of the look-back value, the MTA, rounding and the collateral model chosen | formulas only |
| **Exposure** | per primary date: E, N and the uncollateralised max(V, 0) | formulas only |
| **Stats** | per primary date: EE (`AVERAGE`), ENE, PFE at each q (`SMALL` with `ROUNDUP(P·q − 1e−9, 0)`), EEE (`MAX` of the previous and EE), Δt, the EPE and EEPE weights | formulas only |
| **Contributions** | at the chosen date: w, a and EE per trade; the sum against EE | formulas only |
| **Explain**, **Provenance** | as every playground; Provenance adds the run id, calibration, seed, model version and the `MPOR_MISMATCH` flag | text, values |

**Main shows:** the netting set, counterparty, run (`RUN_ID`, `CALIBRATION`, P), the business date; the choices `Confidence` (one or
more q), `RankRule` (`kth`), `CollateralModel` (`lagged`), `ContributionDate`; the **profile table** with columns t, Δt, EE, ENE, PFE at
each q, EEE, EE uncollateralised, collateral effect (EE − EE uncollateralised); the **headline**: CE, EPE, EEPE, T, peak PFE at each q and
its date, peak EE and its date, `EAD_IMM` (labelled informational); each with Engine and Check; **the checks**: every figure equals the
engine's; contributions add up to EE; the path count equals P; every Data cell a number; shown trades plus Rest of set equal the set.

**Config shows:** `THRESHOLD_THEM`, `THRESHOLD_US`, `MTA_THEM`, `MTA_US`, `INDEPENDENT_AMOUNT`, `ROUNDING`, `ROUNDING_METHOD` (the CSA, in
USD), `MPOR_DAYS`, `MARGIN_FREQUENCY_DAYS`, the run's `LOOKBACK_DAYS`, today's collateral C₀ (after haircut), IM held; the grid: every
`GRID_DATES` entry with its role, look-back pointer and t in years; alpha 1.4; the horizon cap 1 year; the longest remaining maturity.

### 8.3 FVA playground (`PLAY FVA`)

Scope: one netting set, or a counterparty's sets side by side (FVA adds). Two modes: `profile` (default: EE and ENE per primary date are
data, read from the exposure engine) and `paths` (the Exposure playground's tabs are included and EE and ENE are formulas over the paths:
the whole chain from simulated values to FVA in one workbook, within the Exposure playground's limits).

| Tab | What it holds | Typed or formula |
|---|---|---|
| **Main** | title, check band, the choices; FCA, FBA, FVA, the EPE reading, each beside the engine's (below) | formulas |
| **Config** | the funding spread points, the discount curve points, the survival inputs (below) | typed |
| **Data** | per primary date: t, EE, ENE (profile mode), and the run's id and calibration | values |
| **Terms** | per primary date: Δt, DF (log-linear in the curve's points), s_F (linear between spread points), S_C and S_B (when on), EE*, ENE*, the FCA and FBA terms | formulas only |
| **Explain**, **Provenance** | as every playground; Provenance names the curves and their versions and the flag `REAL_WORLD_RUN` when it applies | text, values |

**Main shows:** the scope, date, run and calibration; the choices `EePoint`, `FundingSurvival`, `IncludeFba`; **FCA**, **FBA**, **FVA**
with Engine and Check; the **EPE reading** (Σ EE·Δt, T_life, EPE_life, s_F·T_life·EPE_life, shown when the spread is flat, as an
explanation and not a result); **the checks**: each figure equals the engine's to the cent; FVA = FCA + FBA; the terms add up to FCA and
FBA.

**Config shows:** the funding spread (flat value or the curve's points: tenor, spread in decimals) and its source; the discount curve's
points (tenor, DF) and its id; the counterparty's and the bank's survival points (when funding survival is on); `ee-point`;
`funding-survival`; `fba`; the sign convention.

### 8.4 Check cells

As the VaR Playground (section 8 there): a figure equals the engine's when `ABS(sheet − engine) < 0.005`; the parts add up to the whole;
counts agree. Changing an input turns the engine checks red by design (the engine answered the original question) while the add-up checks
stay green.

## 9. The tests that prove it: hand-built cases

Each case is small enough to check with a calculator. Each becomes an engine unit test (Java aggregates and Python formulas), a parity test
of `ccrmath.py`, a playground check-cell test (the workbook recalculated by headless LibreOffice equals the engine to the cent) and a row of
the drill. **Years are given directly** (the unit tests call the per-trade functions with S, E, M, T in years; the date-level fixtures use
dates whose year fractions are stated beside them). Amounts in USD; intermediate figures to six significant decimals, results to the
whole USD. SD = (e^(−0.05 S) − e^(−0.05 E)) / 0.05.

### Case S1: one IR swap, unmargined

USD payer swap (`ASSET_CLASS: IR`, `HEDGING_SET: USD`, `TRANSACTION_TYPE: LINEAR`, `DIRECTION: LONG`), `NOTIONAL_USD` 100,000,000,
S = 0, E = M = 5 years. V = +1,200,000, C = 0.

| Step | Computation | Value |
|---|---|---|
| SD | (1 − e^(−0.25)) / 0.05 | 4.423984 |
| d | 100,000,000 × 4.423984 | 442,398,434 |
| δ, MF | LONG; M ≥ 1 year | +1, 1 |
| D (bucket b2, 1y ≤ E ≤ 5y) | | +442,398,434 |
| EN_USD = \|D2\| | one bucket | 442,398,434 |
| AddOn_IR | 0.5% × 442,398,434 | **2,211,992** |
| RC | max(1,200,000 − 0, 0) | 1,200,000 |
| multiplier | V − C > 0 | 1 |
| PFE | | 2,211,992 |
| **EAD** | 1.4 × (1,200,000 + 2,211,992) | **4,776,789** |

**Variant V = −1,200,000:** RC = 0; exponent = −1,200,000 / (2 × 0.95 × 2,211,992) = −0.285525; multiplier = 0.05 + 0.95 × e^(−0.285525)
= **0.764039**; PFE = 1,690,047; **EAD = 2,366,066**.

### Case S2: IR offsets across maturity buckets

One USD hedging set, unmargined, V = −2,000,000, C = 0.

| Trade | Direction | Notional | S, E = M | SD | d | MF | D | Bucket |
|---|---|---|---|---|---|---|---|---|
| T1 payer | LONG | 200,000,000 | 0, 0.5 | 0.493802 | 98,760,352 | √0.5 = 0.707107 | +69,834,115 | b1 |
| T2 receiver | SHORT | 100,000,000 | 0, 3 | 2.785840 | 278,584,047 | 1 | −278,584,047 | b2 |
| T3 payer | LONG | 50,000,000 | 0, 10 | 7.869387 | 393,469,340 | 1 | +393,469,340 | b3 |

| Term | Value |
|---|---|
| D1² | 4.876804 × 10¹⁵ |
| D2² | 7.760907 × 10¹⁶ |
| D3² | 1.548181 × 10¹⁷ |
| 1.4 · D1 · D2 | −2.723654 × 10¹⁶ |
| 1.4 · D2 · D3 | −1.534600 × 10¹⁷ |
| 0.6 · D1 · D3 | 1.648655 × 10¹⁶ |
| EN_USD = √Σ | **270,359,047** |
| AddOn_IR = 0.5% × EN | **1,351,795** |
| without offsets (`ir-bucket-offset: false`): 0.5% × (\|D1\| + \|D2\| + \|D3\|) = 0.5% × 741,887,502 | 3,709,438 |
| RC | 0 |
| exponent = −2,000,000 / (2 × 0.95 × 1,351,795) | −0.778692 |
| multiplier = 0.05 + 0.95 × e^(−0.778692) | **0.486056** |
| PFE | 657,048 |
| **EAD** = 1.4 × (0 + 657,048) | **919,867** |

### Case S3: FX forwards, two hedging sets

Unmargined, V = +800,000, C = 0. `NOTIONAL_USD` is the foreign leg in USD at 1.10 USD per EUR and 150 JPY per USD.

| Trade | Hedging set | Direction | d | M | MF | D |
|---|---|---|---|---|---|---|
| F1 buy EUR 50m forward | `EURUSD` | LONG | 55,000,000 | 0.75 | √0.75 = 0.866025 | +47,631,397 |
| F2 sell EUR 20m forward | `EURUSD` | SHORT | 22,000,000 | 2 | 1 | −22,000,000 |
| F3 sell JPY 4.5bn forward (buy USD 30m) | `JPYUSD` | SHORT | 30,000,000 | 1.5 | 1 | −30,000,000 |

| Figure | Computation | Value |
|---|---|---|
| EURUSD | 4% × \|47,631,397 − 22,000,000\| = 4% × 25,631,397 | 1,025,256 |
| JPYUSD | 4% × \|−30,000,000\| | 1,200,000 |
| AddOn_FX | | **2,225,256** |
| RC, multiplier | 800,000; 1 | |
| **EAD** | 1.4 × (800,000 + 2,225,256) | **4,235,358** |

The two EUR forwards offset fully inside their pair; the JPY forward offsets nothing (another pair).

### Case S4: a sold equity index call (negative supervisory delta) and a single-name swap

Unmargined, V = −1,200,000 (the sold option is a liability), C = 0.

| Step | Computation | Value |
|---|---|---|
| E1 sold call on an equity index | P = 5,000, K = 5,200, T = M = 0.5, σ = 75% (index), `QUANTITY` 10,000 | |
| ln(P/K) | ln(5,000 / 5,200) | −0.039221 |
| ½σ²T | 0.5 × 0.5625 × 0.5 | 0.140625 |
| σ√T | 0.75 × 0.707107 | 0.530330 |
| d1 | (−0.039221 + 0.140625) / 0.530330 | 0.191210 |
| Φ(d1) | | 0.575819 |
| δ (sold call: −Φ(d1)) | | **−0.575819** |
| d | 10,000 × 5,000 | 50,000,000 |
| MF | √0.5 | 0.707107 |
| D | −0.575819 × 50,000,000 × 0.707107 | −20,358,289 |
| AddOn_index (SF 20%, ρ 0.8) | 0.20 × −20,358,289 | −4,071,658 |
| E2 long total return swap on one share | d = 30,000,000, M = 1, δ +1, MF 1, SF 32%, ρ 0.5 | AddOn = +9,600,000 |
| systematic | 0.8 × (−4,071,658) + 0.5 × 9,600,000 | 1,542,674 |
| systematic² | | 2.379842 × 10¹² |
| idiosyncratic | 0.36 × 4,071,658² + 0.75 × 9,600,000² | 7.508822 × 10¹³ |
| AddOn_EQUITY | √(2.379842 × 10¹² + 7.508822 × 10¹³) | **8,801,594** |
| RC | V − C < 0 | 0 |
| exponent | −1,200,000 / (2 × 0.95 × 8,801,594) | −0.071757 |
| multiplier | 0.05 + 0.95 × e^(−0.071757) | **0.934219** |
| PFE | | 8,222,616 |
| **EAD** | 1.4 × 8,222,616 | **11,511,662** |

The same terms as a **sold put** give δ = +Φ(−d1) = **+0.424181**: selling a put is long the underlying.

### Case S5: a margined set where TH + MTA − NICA binds and the cap binds

Bilateral, daily margin (`MARGIN_FREQUENCY_DAYS` 1), `MPOR_DAYS` 10, `DISPUTES_LAST_2Q` 0; `THRESHOLD_THEM` 5,000,000, `MTA_THEM`
500,000; an independent amount of 1,000,000 held, not segregated: C = NICA = 1,000,000 (no VM: V is under the threshold). V = +3,000,000.

| Trade | d | M | MF unmargined | D unmargined | D margined (MF 0.3) |
|---|---|---|---|---|---|
| M1 USD payer swap 100m, S 0, E = M 0.75 (SD 0.736112) | 73,611,165 | 0.75 | 0.866025 | 63,749,139 | 22,083,349 |
| M2 EURUSD forward, buy EUR (USD 40m) | 40,000,000 | 0.25 | 0.5 | 20,000,000 | 12,000,000 |

| Figure | Margined | Unmargined |
|---|---|---|
| MPOR | max(10, 10) + 1 − 1 = 10 days | – |
| MF | 1.5 × √(10/250) = 0.3 | per trade |
| AddOn_IR | 0.5% × 22,083,349 = 110,417 | 0.5% × 63,749,139 = 318,746 |
| AddOn_FX | 4% × 12,000,000 = 480,000 | 4% × 20,000,000 = 800,000 |
| AddOn_agg | 590,417 | 1,118,746 |
| RC | max(3.0m − 1.0m, 5.0m + 0.5m − 1.0m, 0) = **4,500,000** | max(2.0m, 0) = 2,000,000 |
| multiplier | 1 | 1 |
| EAD | 1.4 × 5,090,417 = 7,126,583 | 1.4 × 3,118,746 = **4,366,244** |
| **EAD after the cap** | min(7,126,583, 4,366,244) = **4,366,244**, `CAPPED` true | |

**Variant: three disputes in the last two quarters** (`DISPUTES_LAST_2Q` 3): the floor doubles to 20 days, MPOR = 20, MF = 1.5 × √(20/250)
= 0.424264, AddOn_agg = 834,975, margined EAD = 1.4 × (4,500,000 + 834,975) = 7,468,966; the cap still binds at 4,366,244.

### Case S6: a credit index hedge against a single name

Unmargined, V = +500,000, C = 0.

| Trade | Reference, quality | Direction | Notional, S, E | SD | D | SF, ρ | AddOn_k |
|---|---|---|---|---|---|---|---|
| C1 bought protection on an IG index | index, `IG` | SHORT (−1) | 100m, 0, 5 | 4.423984 | −442,398,434 | 0.38%, 0.8 | −1,681,114 |
| C2 sold protection on one name | single, `BBB` | LONG (+1) | 40m, 0, 3 | 2.785840 | +111,433,619 | 0.54%, 0.5 | +601,742 |

| Figure | Computation | Value |
|---|---|---|
| systematic | 0.8 × (−1,681,114) + 0.5 × 601,742 | −1,044,020 |
| systematic² | | 1.089979 × 10¹² |
| idiosyncratic | 0.36 × 1,681,114² + 0.75 × 601,742² | 1.288982 × 10¹² |
| AddOn_CREDIT | √(2.378960 × 10¹²) | **1,542,388** |
| RC, multiplier | 500,000; 1 | |
| **EAD** | 1.4 × (500,000 + 1,542,388) | **2,859,343** |

### Case S7: a bought payer swaption with a shifted delta

USD, `TRANSACTION_TYPE: OPTION`, `OPTION_TYPE: CALL` (a payer swaption is a call on the rate), `BOUGHT_SOLD: BOUGHT`, notional 100m, exercise
T = 1, underlying swap S = 1, E = M = 6; P = 2.00%, K = 2.50%, `SHIFT` λ = 1.00%, σ = 50%. V = +900,000 (the premium's value), C = 0.

| Step | Computation | Value |
|---|---|---|
| ln((P + λ)/(K + λ)) | ln(0.030 / 0.035) | −0.154151 |
| d1 | (−0.154151 + 0.125) / 0.5 | −0.058301 |
| δ = Φ(d1) | | **0.476754** |
| without the shift | d1 = (ln(0.8) + 0.125) / 0.5 = −0.196287 | δ = 0.422193 |
| SD | (e^(−0.05) − e^(−0.30)) / 0.05 | 4.208224 |
| d | 100,000,000 × 4.208224 | 420,822,408 |
| D (bucket b3, MF 1) | 0.476754 × 420,822,408 | 200,628,889 |
| AddOn_IR | 0.5% × 200,628,889 | **1,003,144** |
| **EAD** | 1.4 × (900,000 + 1,003,144) | **2,664,402** |

### Case X1: the Exposure playground, one netting set, five paths

Two trades A and B, P = 5 paths, primary dates t = 0.25, 0.5, 0.75, 1.0 (Δt = 0.25 each), longest maturity 2 years (T = 1). CSA:
`THRESHOLD_THEM` = `THRESHOLD_US` = 1, `MTA_THEM` = 0.5, IA 0, no rounding, collateral `lagged`. Today: V₀ = 0.4, C₀ = 0, so CE = 0.4.
Amounts in USD millions. L is the set's value at the look-back date on the same path.

| Path | A (t₁ … t₄) | B (t₁ … t₄) | V = A + B | L | C = lagged collateral | E = max(V − C, 0) | N = min(V − C, 0) |
|---|---|---|---|---|---|---|---|
| 1 | 2, 3, 4, 3 | −1, −1, 0, 1 | 1, 2, 4, 4 | 0.5, 1.5, 3, 4 | 0, 0, 1.5, 2.5 | 1, 2, 2.5, 1.5 | 0, 0, 0, 0 |
| 2 | −1, 0, 1, 2 | 1, 2, −2, −1 | 0, 2, −1, 1 | 0, 1.5, 0, 0 | 0, 0, 0, 0 | 0, 2, 0, 1 | 0, 0, −1, 0 |
| 3 | 1, −1, −2, −1 | 0, 2, 1, −1 | 1, 1, −1, −2 | 1, 1, 0, −1.5 | 0, 0, 0, −0.5 | 1, 1, 0, 0 | 0, 0, −1, −1.5 |
| 4 | 3, 4, 2, 5 | −2, −1, 1, −2 | 1, 3, 3, 3 | 0.5, 2.5, 3.5, 2.5 | 0, 1.0, 2.0, 1.0 | 1, 2, 1, 2 | 0, 0, 0, 0 |
| 5 | 0, 1, 3, 1 | 1, 0, −1, 2 | 1, 1, 2, 3 | 1, 1, 1.5, 2.5 | 0, 0, 0, 1.0 | 1, 1, 2, 2 | 0, 0, 0, 0 |

Collateral: target(L) = max(L − 1, 0) − max(−L − 1, 0), then the held side less the MTA: path 1 at t₂, target 0.5 → 0 (under the MTA);
path 5 at t₃, 0.5 → 0; path 4 at t₂, 1.5 → 1.0; path 3 at t₄, L = −1.5 → −0.5 (the bank posts 0.5; no MTA on the posted side).

| Figure | t₁ = 0.25 | t₂ = 0.5 | t₃ = 0.75 | t₄ = 1.0 |
|---|---|---|---|---|
| EE | 4/5 = **0.8** | 8/5 = **1.6** | 5.5/5 = **1.1** | 6.5/5 = **1.3** |
| ENE | 0 | 0 | −0.4 | −0.3 |
| PFE 80% (⌈5 × 0.8⌉ = 4th smallest) | 1 | 2 | 2 | 2 |
| PFE 95% (⌈4.75⌉ = 5th: the largest) | 1 | 2 | **2.5** | 2 |
| EEE (EEE(0) = CE = 0.4) | 0.8 | 1.6 | 1.6 | 1.6 |
| EE uncollateralised | 0.8 | 1.8 | 1.8 | 2.2 |
| collateral effect | 0 | −0.2 | −0.7 | −0.9 |

- **EPE** = (0.8 + 1.6 + 1.1 + 1.3) × 0.25 / 1 = **1.2**
- **EEPE** = (0.8 + 1.6 + 1.6 + 1.6) × 0.25 / 1 = **1.4**
- **Peak PFE 95%** = 2.5 at t₃; **peak PFE 80%** = 2 at t₂ (the earliest on a tie); peak EE = 1.6 at t₂
- **EAD_IMM** (informational) = 1.4 × 1.4 = **1.96**

### Case X2: allocation, limits and a group (the CCR cube)

**Allocation at t₂ in case X1** (all five paths have E > 0; only path 4 holds collateral, C = 1.0 against V = 3, so each trade keeps
1 − 1/3 of its value there):

| Path | A | B | a_A | a_B | Σ = E |
|---|---|---|---|---|---|
| 1 | 3 | −1 | 3 | −1 | 2 |
| 2 | 0 | 2 | 0 | 2 | 2 |
| 3 | −1 | 2 | −1 | 2 | 1 |
| 4 | 4 | −1 | 2.666667 | −0.666667 | 2 |
| 5 | 1 | 0 | 1 | 0 | 1 |
| mean | | | **1.133333** | **0.466667** | **1.6 = EE(t₂)** |

**Limits by tenor** on case X1's PFE 95% with buckets (0, 0.5] limit 2.2 and (0.5, 1] limit 2.0, `WARNING_AT` 0.8: bucket 1 usage
max(1, 2) = 2.0, use 90.9%, `WARNING`; bucket 2 usage max(2.5, 2) = 2.5, use **125%**, `BREACH`, headroom −0.5 at t₃ (the binding tenor).

**A group of two netting sets** at one date, P = 4: set G1's exposures 4, 0, 1, 0 and set G2's 0, 3, 1, 0. PFE 75% (⌈3⌉ = 3rd smallest):
G1 alone 1 (0, 0, 1, 4), G2 alone 1 (0, 0, 1, 3), the sum of the peaks 2; the group summed per path is 4, 3, 2, 0 → 3rd smallest **3**.
EE adds (1.25 + 1.0 = 2.25 = the group's 9/4); PFE does not, in either direction: the cube's total is the quantile of the summed paths.

### Case F1: FVA on case X1's profile

EE = 0.8, 1.6, 1.1, 1.3 and ENE = 0, 0, −0.4, −0.3 (USD millions); Δt = 0.25; `ee-point: end`.

**(a) Flat 50 bp, DF = 1:** FCA = −0.005 × (0.8 + 1.6 + 1.1 + 1.3) × 0.25 = **−6,000**; FBA = +0.005 × (0.4 + 0.3) × 0.25 = **+875**;
**FVA = −5,125**. The EPE reading: Σ EE·Δt = 1.2 = T_life × EPE_life (T_life 1, EPE_life 1.2), and 0.005 × 1.2m = 6,000.

**(b) Flat 50 bp, DF = e^(−0.04 t):**

| k | t | DF | EE* | FCA term | \|ENE*\| | FBA term |
|---|---|---|---|---|---|---|
| 1 | 0.25 | 0.990050 | 0.792040 | −990.05 | 0 | 0 |
| 2 | 0.50 | 0.980199 | 1.568318 | −1,960.40 | 0 | 0 |
| 3 | 0.75 | 0.970446 | 1.067490 | −1,334.36 | 0.388178 | 485.22 |
| 4 | 1.00 | 0.960789 | 1.249026 | −1,561.28 | 0.288237 | 360.30 |
| Σ | | | | **FCA −5,846.09** | | **FBA 845.52** |

**FVA = −5,000.57.**

**(c) A term spread** (40 bp to 0.5 years, 60 bp after, the same DF): FCA terms −792.04, −1,568.32, −1,601.24, −1,873.54 →
FCA **−5,835.13**; FBA terms 582.27, 432.36 → **1,014.62**; **FVA −4,820.51** (terms shown to the cent, sums from full precision).

**(d) Survival weighting on** (flat hazards: counterparty 2%, bank 1%; S_C · S_B = 0.992528, 0.985112, 0.977751, 0.970446), flat 50 bp,
the same DF: FCA **−5,733.68**, FBA **824.07**, **FVA −4,909.60**.

**Cross-check:** CVA.md section 15's profile (EE 1.0, 1.5, 0.75; ENE −0.25, −0.5, −0.75; Δt 0.25, 0.25, 0.5; 50 bp, DF = 1) gives FCA
−5,000, FBA +2,812.50, FVA −2,187.50: the same formulas, a second fixture.

### Case S8: the regulators' examples

Where the Basel Committee or a supervisor publishes SA-CCR worked netting sets (the BCBS 279 annex examples), each is added as a case with
its source, the inputs typed from the publication (SACCR.md open question 4).

## 10. Engine and data tests

- **Parameter file:** every value present and in range; every SF positive; ρ in [0, 1]; option σ positive; the MPOR floors integers; every
  value carries its paragraph; a file missing any refuses to load.
- **Per trade:** SD, δ (each option and tranche case, the shift), MF at the floors (10 business days, 1 year), D's sign by direction.
- **Aggregation:** IR buckets at the boundaries (E = 1 and E = 5 fall in b2); `ir-bucket-offset: false`; FX pairs; credit and equity
  references; commodity hedging sets; basis and volatility sets with their factors.
- **Set level:** RC both forms; the multiplier at V − C = 0 (exactly 1) and far below (towards 0.05); AddOn_agg = 0 (multiplier 1, EAD = α
  RC); the cap; a not-enforceable set split into standalone sets; SFTs excluded; an unclassified member flagged.
- **Scopes:** a counterparty's EAD is the sum of its sets'; a cut set is `partial`; incremental EAD of an at-market trade; standalone EAD.
- **Exposure playground:** the workbook's figures equal `ExposureMath` for every demo netting set with paths (74 sets), every collateral
  model, q 95% and 99%; Rest of set keeps V exact.
- **FVA:** linear in EE (twice the profile, twice the FVA); additive over sets; a flat spread equals the EPE reading; a `REAL_WORLD` run
  flagged or refused by the setting.
- **Cube:** allocation adds up to EE on random paths (a property test); group sums per path; the board's buckets (FROM, TO]; totals as
  quantiles of summed matrices; the bank's peak PFE equal to `EXP BANK`.
- **Demo data:** every live derivative member of a `DERIVATIVES` set has a `saccr-trade` row (derived by rules, `DERIVED_BY: rules`); SFTs
  have none; the regenerated limits' utilisation inside the planned band.
- **Playgrounds:** headless LibreOffice recalculation: every check cell passes; the formula scanner finds no function outside the common
  set as extended in section 8.

## 11. Parameters as configuration (drafts)

Drafts here; at build they become files of the counterparty-risk pack's configuration, loaded and checked at start, with their checksum in
every answer's provenance. One file per regulation; a jurisdiction's variant is its own file. After BCBS the next file is OSFI CAR
chapter 4 (`saccr-osfi.yaml`, as the FRTB order); the US final rule and EU CRR2 follow when a client asks (decision 4).

### 11.1 SA-CCR (`config/packs/banking/counterparty-risk/capital-parameters/saccr-bcbs.yaml`, draft, not loaded)

```yaml
regulation: BCBS
source: Basel Framework, CRE52 (in force from 2023-01-01)
version: "1.0"
alpha:                   { value: 1.4,  ref: CRE52.1 }
cap-at-unmargined:       { value: true, ref: CRE52.1 }
multiplier-floor:        { value: 0.05, ref: CRE52.23 }
supervisory-duration-rate: { value: 0.05, ref: CRE52.34 }
ir-buckets:              { bounds-years: [1, 5], ref: CRE52.56 }
ir-bucket-weights:       { b1b2: 1.4, b2b3: 1.4, b1b3: 0.6, ref: CRE52.57 }
basis-factor:            { value: 0.5, ref: CRE52.28 }
volatility-factor:       { value: 5,   ref: CRE52.29 }
cdo-delta:               { numerator: 15, slope: 14, ref: CRE52.40 }
time:                    { mf-days-per-year: 250, sd-day-count: ACT/365, min-maturity-days: 10, ref: CRE52.31 }
mpor-floors-days:        { bilateral: 10, cleared: 5, large-or-illiquid: 20, large-netting-set-trades: 5000, ref: CRE52.50 }
dispute-doubling:        { disputes-over: 2, factor: 2, ref: CRE52.51 }
supervisory-table:                                   # CRE52.72: SF, correlation, supervisory option volatility
  IR:        { sf: 0.005,  sigma: 0.50 }
  FX:        { sf: 0.04,   sigma: 0.15 }
  CREDIT:
    single:  { rho: 0.5, sigma: 1.00, sf: { AAA: 0.0038, AA: 0.0038, A: 0.0042, BBB: 0.0054, BB: 0.0106, B: 0.016, CCC: 0.06 } }
    index:   { rho: 0.8, sigma: 0.80, sf: { IG: 0.0038, SG: 0.0106 } }
  EQUITY:
    single:  { sf: 0.32, rho: 0.5, sigma: 1.20 }
    index:   { sf: 0.20, rho: 0.8, sigma: 0.75 }
  COMMODITY:
    ELECTRICITY: { sf: 0.40, rho: 0.4, sigma: 1.50 }
    default:     { sf: 0.18, rho: 0.4, sigma: 0.70 }     # oil, gas, metals, agricultural, other
```

Settings stay as SACCR.md section 6.1 (`drishti.ccr.saccr.*`): `regulation` names the file; `alpha-overrides` per counterparty;
`ir-bucket-offset`; `risk-weights` for the RWA hook; `derive` (`rules` on the demo); `unclassified` (`exclude` by default, `stand-in`;
decision 3); `specific-wwr` (`own-set`, decision 7).

### 11.2 Exposure playground (`drishti.ccr.exposure.playground.*`, draft)

```yaml
drishti.ccr.exposure.playground:
  max-paths: 5000              # all of a bank run; more is refused with the reason
  browser-paths: 200           # rows in the browser grid
  browser-trades: 50           # trade columns in the browser; the rest in Rest of set
  export-choices: [50, 500, 2000, All]
  export-default: 500
  export-max-cells: 20000000   # as VaR's
  contribution-date: peak      # or a grid date
```

### 11.3 FVA (`drishti.ccr.fva.*`, draft)

```yaml
drishti.ccr.fva:
  enabled: true
  funding-spread: CRV-USD-FUNDING    # a curve id (points: TENOR, SPREAD in decimals; decision 12), or a flat spread such as 0.0050
  spread-interpolation: linear       # in t between points, flat beyond
  discount-curve: CRV-USD-OIS        # DF log-linear between points
  ee-point: end                      # or mid
  funding-survival: false            # true: × S_C(t) · S_B(t), with the counterparty's and the bank's curves
  own-curve: ""                      # the bank's credit curve, needed only with funding-survival
  fba: true                          # false: FCA only (asymmetric funding)
  require-risk-neutral: true         # the demo profile sets false; FVA on another run is flagged REAL_WORLD_RUN
  playground: { max-sets: 20, mode: profile }   # or paths
```

### 11.4 SA-CCR playground (`drishti.ccr.saccr.playground.*`, draft)

```yaml
drishti.ccr.saccr.playground:
  max-sets: 20
  max-trades: 5000
  browser-trades: 500
```

## 12. Mock-ups: the screens to build

Decided already: the CCR cube's landing A with tabs B and C ([CCR_CUBE_LENS.md section 6.7](LENSES.md#74-mock-ups-and-build-checklist)),
and the playgrounds' frame ([VAR_PLAYGROUND.md section 16](../guides/VAR_GUIDE.md#313-the-var-playgrounds-workbook-cell-by-cell)). **Still to make before
the build (mock-ups first):** the SA-CCR page that replaces the stored Sutra (three options: the EAD waterfall from RC and add-ons by class
with the hedging-set table; the netting-set list with the selected set's breakdown; the counterparty's sets side by side), and the FVA
figures on the netting-set and counterparty pages (the term bars per date beside CVA's future bars). Each option self-contained HTML, dark
by default and light with `#light`, copied to `docs/lenses/mockups/<screen>/` with its build checklist.

**Drawn and decided (product owner, 2026-11-03; the general rule: every option built, A the default, as views of one screen over one API):**
SA-CCR ([A](../lenses/mockups/saccr/A.html) the EAD waterfall, the default; [B](../lenses/mockups/saccr/B.html) the netting-set list with
the selected set's breakdown; [C](../lenses/mockups/saccr/C.html) a counterparty's sets side by side) and FVA
([A](../lenses/mockups/fva/A.html) the FVA section of the netting-set page with term bars beside CVA's, the default;
[B](../lenses/mockups/fva/B.html) the netting sets by FVA; [C](../lenses/mockups/fva/C.html) the counterparty page). The CCR cube's A, B
and C are all built too, A the landing.

## 13. The playgrounds' explanations

The words of each playground's Explain tab are written ahead of the engines, in the VaR Playground's format (a line `## <title>`, then
its text; `#` lines are comments), so `PlaygroundTemplate.explain()` reads them:

| File | Covers |
|---|---|
| [`playground/saccr/template/explain.txt`](../../drishti-banking/src/main/resources/playground/saccr/template/explain.txt) | in plain words: what SA-CCR answers, why a bank must hold it, the tabs, signs and units, the regulation and its parameters; the steps from the classification row to EAD after the cap, each with a worked number from cases S1 to S7; every term of Main, Config, Data, Trades, Hedging sets and Provenance with its CRE52 paragraph |
| [`playground/exposure/template/explain.txt`](../../drishti-banking/src/main/resources/playground/exposure/template/explain.txt) | in plain words: what exposure answers, why paths, the tabs, signs and units; the steps from the paths to EE, PFE, EPE, EEPE and the contributions, worked with case X1 and X2; every term of Main, Config, Data, Netting, Collateral, Exposure, Stats, Contributions and Provenance |
| [`playground/fva/template/explain.txt`](../../drishti-banking/src/main/resources/playground/fva/template/explain.txt) | in plain words: what FVA answers, why funding costs money, the tabs, signs and units; the steps from the profile to FCA, FBA and FVA, worked with case F1 (a) to (d); every term of Main, Config, Data, Terms and Provenance |

The cell references (`{Name}`, `{Name:v}`) are added when each generator names its Main cells.

## 14. Decisions

The product owner decided every point below on 2026-11-02, taking each recommended answer. The sections above apply them; each says
"decision N" where it does.

| # | Question | Decision (product owner, 2026-11-02) | Applied in |
|---|---|---|---|
| 1 | C and NICA in SA-CCR: collateral at supervisory haircuts (COLL's `C_REG`, `NICA_REG`, CRE22) or at the CSA's contractual haircuts (the exposure engine's basis)? | **Supervisory** (`C_REG`, `NICA_REG`): CRE52 asks for the haircut value of collateral under the standardised approach; the exposure engine keeps contractual haircuts, and both pages say which | 3.1 |
| 2 | MPOR for SA-CCR: `max(floor, MPOR_DAYS) + F − 1` as SACCR.md writes it (the CSA's `MPOR_DAYS` taken as excluding the remargining lag), or `max(floor + F − 1, MPOR_DAYS)` (taken as including it)? | **As written** (`max(floor, MPOR_DAYS) + F − 1`), with the CSA field's definition stated in NSET.md as "excluding the remargining period"; conservative when in doubt | 3.6 |
| 3 | A live derivative without a `saccr-trade` row: exclude it and mark the set `incomplete` (SACCR.md), or charge a conservative stand-in (the trade's notional at the largest SF of its asset class, unmargined)? | **Exclude and flag** by default (`incomplete`, counted on every page); the conservative stand-in as a setting for banks that report from Drishti | 3.11, 11.1 |
| 4 | The jurisdiction after BCBS: OSFI CAR chapter 4 (as the FRTB order), the US final rule, or EU CRR2? | **OSFI CAR chapter 4** next, as its own parameter file; US and EU when a client asks | 11 |
| 5 | The SA-CCR playground's scope: one netting set only, or a counterparty's sets side by side? | **One set by default, up to 20 sets of one counterparty side by side** (EAD adds over sets, so the counterparty total is a plain sum on Main) | 8.1 |
| 6 | The Exposure playground's size: all paths of the run in the export (up to 5,000) and 500 trades with Rest of set, 200 paths and 50 trades in the browser? | **Yes** (section 11.2); the estimate is shown before the download as VaR's | 8.2 |
| 7 | Specific wrong-way risk trades (`SPECIFIC_WWR`) in SA-CCR: each in its own netting set (out of the set's netting), or left in the set until WWR is built? | **Own netting set** when the flag is set (conservative, no netting benefit); the demo has no flagged trade | 3.1, 11.1 |
| 8 | Unmargined sets of the exposure engine with an IA: the Exposure playground shows IA as collateral held on every path (as built)? | **Yes**, as built; stated on Config | 4.2 |
| 9 | FVA's computed kind before CVA exists: a new `fva-result` (with `/api/v1/fva`), or `cva-result` (CVA.md) with only FCA, FBA and FVA filled until the CVA family is built? | **`fva-result`** now, from an xVA engine that CVA and DVA join later; CVA.md is amended then so `cva-result` reads FVA by reference rather than carrying a second copy | 5.4; CVA.md |
| 10 | FVA on the demo's `REAL_WORLD` run: refuse, or compute and flag? | **Refuse in production by default** (`require-risk-neutral: true`); the demo profile computes and flags `REAL_WORLD_RUN` on every number until a risk-neutral demo run exists | 5.2 |
| 11 | Funding survival weighting and FBA: funding until maturity with FBA reported (symmetric), or survival-weighted, or FCA only? | **Symmetric, not survival-weighted** by default (simplest to explain and to check); both switches available; DVA, when built, states its overlap with FBA | 5.2 |
| 12 | The funding spread source: the flat 50 bp setting, or a funding curve in the market-data pack? | **A synthetic, labelled funding curve in the demo market-data pack** (a few points), the flat setting as the fallback; a bank names its own curve id | 5.2, 11.3 |
| 13 | FVA on the collateralised EE (after the CSA) or on the uncollateralised EE? | **Collateralised EE** (as CVA.md): collateral received can be reused to fund; a set with a CSA has little FVA, as in practice | 5.2 |
| 14 | The demo's credit-limit buckets: three (0–1Y, 1–5Y, 5Y+, as the mock-ups) or the five of today's stored limits? | **Three** for the regenerated demo limits; the contract keeps any number of buckets | 6.3 |
| 15 | FVA as a CCR cube measure (additive, beside EE and EAD) on landing A's measure chips? | **Yes**, with the counterparty's FVA in C's tiles beside incremental EAD | 6.2 |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
