<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# ISDA SIMM initial margin: the data contract

*Version 1.0 · 2026-10-25 · **Status:** proposed (PLANNED for 2.9.0) · Shared rules: [README.md](README.md) · Model contract:
[VAR.md](VAR.md) · Inputs shared with [FRTB.md](FRTB.md) (the `sensitivity` table and the sensitivity store)*

This is the planned data contract of Drishti's **ISDA SIMM** engine: the Standard Initial Margin Model of the International Swaps and
Derivatives Association for uncleared derivatives, computed when asked for a **netting set** (the margin), and for any other scope as
an analytic, from the same per-trade sensitivities FRTB reads, mapped to ISDA's Common Risk Interchange Format (CRIF). It replaces the
stored kind `simm`. Everything here is a **proposal** for the 2.9.0 build; each change will be recorded in
[section 16](#16-open-questions-and-the-change-table). The methodology is ISDA SIMM **version 2.6** as the first parameter set, with
the yearly versions as configuration (§6.1).

**Sources read (2026-10-25):** `config/packs/counterparty-risk/contracts/simm.contract.yaml`, the pack's `samples/simm.jsonl` (18
records), `data/testdata/counterparty-risk/simm/` (24 records on 2026-07-08, every later file empty),
`tools/packgen/banking/data_results.py` (`credit_results`), the counterparty-risk pack's `netting-set`, `csa` and `agreement` samples.

## Contents

1. [Summary](#1-summary)
2. [The measure in one page](#2-the-measure-in-one-page)
3. [Inputs from upstream engines](#3-inputs-from-upstream-engines)
4. [Reference and master data read](#4-reference-and-master-data-read)
5. [Conventions specific to SIMM](#5-conventions-specific-to-simm)
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

**What it is.** The initial margin (IM) the bank should **collect** from and **post** to a counterparty under a netting set, by
ISDA SIMM, with the breakdown by product class, risk class, measure (delta, vega, curvature, base correlation), bucket and risk factor;
the **incremental IM** of a trade before it is dealt; a **reconciliation view** against the counterparty's or the custodian's CRIF and
IM by risk class and bucket; and the same model as an analytic over any scope (a desk's SIMM, not a margin).

**Commands.** `SIMM <scope> <GO>` (`SIMM NSET NS-ALDERSHOT-FRA <GO>`, `SIMM CPTY CP-ALDERSHOT <GO>`: every netting set of the
counterparty, each computed alone); the API `/api/v1/simm`; the computed kinds `simm-margin` and `simm-whatif`.

| Part | Status | Where |
|---|---|---|
| Inputs: the shared `sensitivity` table (FRTB.md §3.2) read through the CRIF mapping (§3.2); SIMM-only vertices (`GRID` `SIMM`) | PLANNED | §3 |
| Add-on inputs (`simm-addon`) and the counterparty's CRIF for reconciliation (`crif-external`) | PLANNED | §3.3, §3.4 |
| SIMM parameter tables per version (yearly), as configuration the bank loads under its ISDA licence | PLANNED | §6.1 |
| Netting-set aggregates of net sensitivities in the sensitivity store | PLANNED | §7 |
| The SIMM formula (Python engine), collect and post per regulatory regime, incremental IM in a sandbox | PLANNED | §6, §9 |
| Retirement of `simm`: `SIMM-<cp>` opens `SIMM CPTY:CP-<cp>` | PLANNED | §14 |

## 2. The measure in one page

**What it answers:** *how much initial margin should we collect and post on this netting set today, what drives it, does it agree
with the counterparty's number, and what would this new trade add?* For collateral and margin operations (the daily IM call),
credit officers, traders (pre-deal IM cost) and the IM dispute desk.

**The method in words (ISDA SIMM).**

1. Each trade's sensitivities, in CRIF terms (risk type, qualifier, bucket, labels, amount), are **netted per risk factor** over the
   netting set's trades in scope of the regime (additive).
2. Each net sensitivity is weighted by its risk weight and by a **concentration factor** that grows when the net exposure to one
   qualifier or currency exceeds a threshold.
3. Within a bucket, weighted sensitivities aggregate with the correlations ρ (and, between interest-rate sub-curves, φ) into K_b; across
   buckets with γ (and g, a ratio of concentration factors) into the risk class's **delta margin**; likewise **vega margin**,
   **curvature margin** (from the vegas, scaled by the option's expiry) and **base-correlation margin**.
4. A risk class's margin is the sum of its four margins. Within each **product class** (RatesFX, Credit, Equity, Commodity), the risk
   classes aggregate with the correlations ψ; the product classes **add**.
5. Add-ons (notional-based, fixed, or a product-class multiplier) are added. Collect and post are computed per regulatory regime; the
   margin is the largest over the regimes that apply.

**Additive and not additive.** Net sensitivities per risk factor (and per regime, per product class) are additive and kept per
netting set and per hierarchy node. Everything from the concentration factor on is not: it runs at query time.

## 3. Inputs from upstream engines

### 3.1 The tables

```
${DRISHTI_DELTA_ROOT}/risk/
├── sensitivity/      the shared table (FRTB.md §3.2): rows with GRID COMMON or SIMM               (PLANNED)
├── simm-addon/       one row per trade with an add-on                                             (PLANNED, optional)
└── crif-external/    the counterparty's or the custodian's CRIF and IM, for reconciliation        (PLANNED, optional)
```

No separate SIMM sensitivity table: SIMM reads [`sensitivity`](FRTB.md#32-sensitivity-one-row-per-trade-and-risk-factor-the-shared-table)
with `GRID` in (`COMMON`, `SIMM`) and maps each row to CRIF (§3.2). A bank whose engine produces CRIF files loads them into
`sensitivity` with the inverse mapping (`drishti.py lake build` with the `crif` reader, proposed). Retention 7 years; the load signal
reloads the date (product owner, 2026-10-25).

### 3.2 The CRIF mapping: Drishti columns and ISDA CRIF

**Columns.**

| ISDA CRIF field | Drishti `sensitivity` column | Rule |
|---|---|---|
| `TradeID` | `TRADE_ID` | as is |
| `PortfolioID` | the netting set | from the netting-set master (§4), not on the row |
| `ProductClass` | `PRODUCT_CLASS` | `RatesFX`, `Credit`, `Equity`, `Commodity`; empty: from the trade's `PRODUCT_GROUP` (§6.2) |
| `RiskType` | (`RISK_CLASS`, `FACTOR_TYPE`, `MEASURE`) | table below |
| `Qualifier` | `QUALIFIER` | currency for IR and FX; issuer (and seniority) for credit; issuer for equity; commodity for commodity |
| `Bucket` | `SIMM_BUCKET` | SIMM's buckets (IR: none, the currency is the bucket; credit qualifying 1–12 and Residual; equity 1–12 and Residual; commodity 1–17); `Residual` as text |
| `Label1` | `TENOR` | lower-case CRIF tenor (`2w`, `1m`, `3m`, `6m`, `1y`, `2y`, `3y`, `5y`, `10y`, `15y`, `20y`, `30y`); for vega: the option expiry |
| `Label2` | `CURVE` | IR: the SIMM sub-curve name, through the bank's curve map (`SOFR` → `OIS` …, configuration); credit: `Sec` for securitisation; empty otherwise |
| `Amount`, `AmountCurrency` | `AMOUNT`, `AMOUNT_CURRENCY` | as is |
| `AmountUSD` | `AMOUNT_USD` | as is (CRIF's `AmountUSD` is the field SIMM uses) |
| `CollectRegulations`, `PostRegulations` | `COLLECT_REGULATIONS`, `POST_REGULATIONS` | as is |
| `EndDate` | `MATURITY_DATE` | for schedule-IM checks |

**Risk types.**

| `RISK_CLASS` / `FACTOR_TYPE` | `delta` | `vega` | SIMM risk class |
|---|---|---|---|
| `GIRR` / `CURVE` | `Risk_IRCurve` | `Risk_IRVol` | Interest rate |
| `GIRR` / `INFLATION` | `Risk_Inflation` | `Risk_InflationVol` | Interest rate |
| `GIRR` / `XCCY_BASIS` | `Risk_XCcyBasis` | | Interest rate |
| `CSR_NS`, `CSR_CTP` / `SPREAD` | `Risk_CreditQ` | `Risk_CreditVol` | Credit qualifying |
| `CSR_SEC` / `SPREAD` | `Risk_CreditNonQ` | `Risk_CreditVolNonQ` | Credit non-qualifying |
| `CSR_CTP` (`MEASURE` `base-corr`) | `Risk_BaseCorr` | | Credit qualifying |
| `EQ` / `SPOT`, `REPO` | `Risk_Equity` (repo per the version's rule, open question 3) | `Risk_EquityVol` | Equity |
| `COMM` / `PRICE` | `Risk_Commodity` (SIMM has no commodity tenor: the tenors of a qualifier are summed) | `Risk_CommodityVol` | Commodity |
| `FX` / `SPOT` | `Risk_FX` | `Risk_FXVol` (qualifier: the pair) | FX |

An optional column `SIMM_RISK_TYPE` on a row overrides the table (for a product the bank classifies differently, for example a
credit index as non-qualifying). Curvature rows (`curvature-up`, `curvature-down`) and `gamma`, `theta` are not read by SIMM.

**Units.** CRIF delta amounts are the P&L for 1 bp (IR, credit) or 1% (equity, commodity, FX), as `AMOUNT_USD` is
([FRTB.md section 3.2.1](FRTB.md#321-units-and-signs-of-amount_usd)); vega amounts are σ·∂V/∂σ in both. No conversion. **Grids:**
SIMM reads `COMMON` and `SIMM` rows, so an IR delta at `2w` and `1m` (SIMM only) is included and FRTB-only rows (none expected for
IR delta; FRTB's vega grid) are not.

### 3.3 `simm-addon` (optional)

| Column | Type | Req. | Meaning | Example |
|---|---|---|---|---|
| `TRADE_ID`, `BUSINESS_DATE` | id, date | required | | `MX-20000311`, `2026-09-30` |
| `ADDON_TYPE` | text | required | `NOTIONAL` (CRIF `Notional` with `AddOnNotionalFactor`), `FIXED` (`AddOnFixedAmount`), `MULTIPLIER` (`ProductClassMultiplier`) | `NOTIONAL` |
| `PRODUCT_CLASS` | text | required for `MULTIPLIER` | | `Equity` |
| `QUALIFIER` | text | optional | the product for a notional factor | `Variance swap` |
| `AMOUNT_USD` | amount | required | notional (USD), fixed amount (USD), or the multiplier (a number ≥ 1) | `25000000.0` |
| `FACTOR` | rate | required for `NOTIONAL` | the agreed factor (fraction) | `0.0532` |
| `BATCH_ID` | id | required | | |

### 3.4 `crif-external`: the other side's numbers (optional)

The counterparty's (or a third-party custodian's or reconciliation service's) CRIF rows and IM per netting set, as received, for the
reconciliation view (§9.4). Columns: the CRIF fields of §3.2 in Drishti's names, plus `NETTING_SET`, `SOURCE` (`COUNTERPARTY`,
`CUSTODIAN`, `VENDOR`), `DIRECTION` (`COLLECT`, `POST` from the bank's view), `IM_TOTAL` and `IM_BY_RISK_CLASS` on a header row
(`ROW_TYPE` `HEADER` or `CRIF`). Not an input to the margin: Drishti never computes from it, it compares.

### 3.5 Delivery and completeness

As FRTB ([FRTB.md section 3.5](FRTB.md#35-delivery-and-completeness)), plus: every live trade of a netting set in scope of a SIMM
regime has at least one row; a trade under schedule IM (not SIMM) is flagged on the netting set (§4) and left out.

## 4. Reference and master data read

| Master (kind) | Key | What for |
|---|---|---|
| `netting-set` (NSET, counterparty-risk pack; contract `NSET.md`, written separately) | `NETTING_SET`, `TRADE_IDS` | which trades a netting set holds (the margin's scope); `NSET:` |
| `csa` (CSA) | `CSA` | the IM terms: SIMM version agreed, IM threshold, minimum transfer, rounding, regimes (collect and post), schedule or SIMM, the calculation agent; the expected call itself belongs to `MC.md` (2.10.0) |
| `agreement` (AGR) | `AGREEMENT` | the master agreement (two-way IM) |
| `counterparty` (CPTY), `lei-entity` (LEI) | `COUNTERPARTY` | `CPTY:`, `GROUP:`; the regime of the counterparty |
| `trade` (TRD) | `TRADE_ID` | live status, `PRODUCT_GROUP` (→ product class), uncleared flag (cleared trades are out of SIMM) |
| `issuer`, `equity`, commodity master | `QUALIFIER` | `SIMM_BUCKET` derivation and checks (open question 4 of FRTB.md) |
| SIMM parameter tables | version | §6.1 |

## 5. Conventions specific to SIMM

| Convention | Rule |
|---|---|
| Sensitivity sign | the bank's P&L for the up-move (as FRTB); **post** is computed on the negated sensitivities (the counterparty's view, §6.6) |
| IM | reported positive; `collect` is what the bank should receive, `post` what it should give |
| Currency | USD (`AmountUSD`); the call currency of the CSA (`IM_CURRENCY`) shown converted at the date's close |
| Version | the netting set's agreed SIMM version from the CSA; else `drishti.simm.default-version` |
| Calculation date | SIMM uses the sensitivities of the business date; ISDA's calibration date of the version is in its parameter file |
| Rounding | figures whole USD in answers; the CSA's rounding applies only to the expected call (2.10.0) |

## 6. The computation

### 6.1 Parameters: the SIMM version tables

ISDA publishes SIMM's risk weights, correlations, concentration thresholds and scaling factors yearly (version 2.6 effective
December 2023, then 2.7, …). Drishti keeps **one file per version**, configuration only, never in code:
`config/simm/simm-<version>.yaml` (proposed), with the version's effective date and a hash, and every table SIMM needs:

| Table | Holds |
|---|---|
| `ir.rw` | per currency volatility group (regular, low, high) × tenor: delta risk weights (bp); inflation and cross-currency basis weights |
| `ir.rho`, `ir.phi`, `ir.gamma` | tenor correlations; the sub-curve correlation φ; inter-currency γ |
| `credit-q`, `credit-nonq` | risk weights per bucket; correlations (same and different issuer, residual); base-correlation weight |
| `equity`, `commodity`, `fx` | risk weights per bucket (FX: per volatility group pair), correlations ρ per bucket, γ between buckets |
| `vega` | vega risk weights per class (and the historical volatility ratio, HVR) |
| `curvature` | the scaling of each class's curvature margin |
| `concentration` | delta thresholds T_b per class and bucket (USD per bp or per 1%); vega thresholds VT_b |
| `psi` | the 6 × 6 risk-class correlation matrix within a product class |
| `regimes` | the regulator codes recognised |

**Licence.** ISDA SIMM is licensed by ISDA; its parameter values come with the methodology document. Drishti ships the file **format**
and a test file of illustrative values, and the bank loads the licensed files (open question 1). The worked example (§15) uses
**illustrative** values, not ISDA's.

### 6.2 Product classes

Each trade belongs to one product class, from `PRODUCT_CLASS` on its rows or, when empty, from its `PRODUCT_GROUP` by configuration:
Rates, Inflation, Money market, FX, Securities financing → `RatesFX`; Credit, Fixed income → `Credit`; Equity, Structured equity →
`Equity`; Commodity → `Commodity` (a trade's rows all take the trade's class; a hybrid trade is classed by its primary risk, as ISDA's
rule). A sensitivity of any risk class may sit in any product class (an equity swap's interest-rate delta is in `Equity`).

### 6.3 Delta margin of a risk class

For netting set (or scope) *S*, regime *g*, product class *p*, risk class *r*:

```
s_k        = Σ_{t ∈ S, in regime g, class p} AMOUNT_USD_{t,k}                    net per risk factor (additive)
CR_b       = max(1, sqrt( |Σ_{k∈b} s_k| / T_b ))       IR: per currency b, the sum over every IR delta (curve, inflation, basis)
CR_k       = max(1, sqrt( |Σ_{j∈q} s_j| / T_b ))       other classes: per qualifier q of bucket b
WS_k       = RW_k × s_k × CR                            (the CR of its currency or qualifier)
K_b        = sqrt( Σ_k WS_k² + Σ_k Σ_{l≠k} ρ_kl f_kl WS_k WS_l )        IR: ρ_kl × φ_ij for sub-curves i, j; f = 1
                                                                          others: f_kl = min(CR_k, CR_l) / max(CR_k, CR_l)
S_b        = max( min( Σ_{k∈b} WS_k, K_b ), −K_b )      (always bounded in SIMM)
DeltaMargin_r = sqrt( Σ_b K_b² + Σ_b Σ_{c≠b} γ_bc g_bc S_b S_c ) + K_residual
              g_bc = min(CR_b, CR_c) / max(CR_b, CR_c) for IR, 1 otherwise; the Residual bucket (credit, equity, commodity) added outside
```

### 6.4 Vega and curvature

```
VR_k       = VRW_r × Σ_{expiries j} AMOUNT_USD_{k,j}          (vega rows: σ·∂V/∂σ; IR, inflation: per currency and tenor)
VCR_b      = max(1, sqrt( |Σ VR| / VT_b ))                    vega concentration
VegaMargin_r = as §6.3 with VR_k × VCR in place of WS_k (the class's vega correlations)

CVR_k      = Σ_j SF(t_j) × AMOUNT_USD_{k,j}                  SF(t) = 0.5 × min(1, 14 / t_days), t the option expiry
K_b        = sqrt( Σ_k CVR_k² + Σ_k Σ_{l≠k} ρ²_kl CVR_k CVR_l )
θ          = min( Σ_k CVR_k / Σ_k |CVR_k|, 0 )
λ          = ( Φ⁻¹(0.995)² − 1 ) × (1 + θ) − θ                Φ⁻¹(0.995) = 2.5758
CurvatureMargin_r = max( Σ_k CVR_k + λ × sqrt( Σ_b K_b² + Σ_b Σ_{c≠b} γ²_bc S_b S_c ), 0 ) × scale_r      S_b bounded as §6.3
```

Curvature comes from the vegas (no curvature revaluation needed; that is FRTB's). `BaseCorrMargin` (credit qualifying) is a delta-style
aggregation of the `base-corr` rows by index family.

### 6.5 Up to the total

```
IM_r       = DeltaMargin_r + VegaMargin_r + CurvatureMargin_r + BaseCorrMargin_r
SIMM_p     = sqrt( Σ_r IM_r² + Σ_r Σ_{s≠r} ψ_rs IM_r IM_s )                product class p
SIMM       = Σ_p SIMM_p
AddOn      = Σ notional × factor + Σ fixed + Σ_p (multiplier_p − 1) × SIMM_p
IM         = SIMM + AddOn
```

### 6.6 Collect, post and regimes

| Figure | Rule |
|---|---|
| `IM_COLLECT` | for each regime *g* in the trades' `COLLECT_REGULATIONS` (the bank's regulators): IM over the trades in scope of *g*; the largest over the regimes (the regime and its IM reported) |
| `IM_POST` | for each regime in `POST_REGULATIONS`: IM over those trades with **every sensitivity negated** (the counterparty's view); the largest |
| No regimes on any row | one computation over every SIMM trade of the netting set, `regime` `NONE` |
| Delta and vega | symmetric (negating every s gives the same margin) |
| Curvature | **not** symmetric (θ and the floor), so collect and post differ for option-heavy netting sets |

### 6.7 Incremental IM before dealing

`IM(S + T) − IM(S)` for a netting set *S* and a candidate trade *T* whose sensitivities the requester supplies (a CRIF fragment, a
sandbox; or the sensitivities of a booked trade moved from elsewhere). Computed in a private sandbox (REACTIVE_CONNECTOR's
`sandbox-ref`); never stored except as a capture. Also the **marginal IM** of each existing trade: the Euler-style allocation
Σ_k s_k(t) × ∂IM/∂s_k (concentration factors held fixed; scaled to add up, as FRTB's contributions).

### 6.8 Settings (`drishti.simm`, proposed)

| Setting | Default | Meaning |
|---|---|---|
| `drishti.simm.enabled` | `true` | |
| `drishti.simm.default-version` | `2.6` | when the CSA names none |
| `drishti.simm.versions-dir` | `config/simm` | the licensed parameter files |
| `drishti.simm.curve-map` | `{}` | bank curve names → SIMM sub-curve labels |
| `drishti.simm.product-class-map` | §6.2 | `PRODUCT_GROUP` → product class |
| `drishti.simm.analytic-scopes` | `true` | allow SIMM over non-netting-set scopes (marked *analytic*) |
| `drishti.simm.access` | `{}` | VaR's model |

## 7. What Drishti builds

| Structure | Content | Size (1 M trades, 50,000 netting sets) |
|---|---|---|
| The sensitivity store | shared with FRTB ([FRTB.md section 7](FRTB.md#7-what-drishti-builds)): factor dictionary with `SIMM_BUCKET`, CRIF risk type and sub-curve label as attributes, rows by trade | shared |
| Netting-set index | netting set → trade ordinals (from `netting-set` on the date) and its CSA terms | 4 B per trade + 200 B per set: 14 MB |
| Netting-set aggregates | per netting set × regime × product class: a sparse vector of net SIMM sensitivities (rows of `GRID` `COMMON` and `SIMM`) | about 200 factors per set: 50,000 × 200 × 12 B = **120 MB** |
| Add-on sums | per netting set: Σ notional × factor, Σ fixed, multipliers | small |
| Answer cache | per (netting set, date, data version, version, view): the IM breakdown | 2 KB an answer |

Netting-set aggregates are built at load from the trades' rows (one pass), so the daily run of every netting set needs no scan of
trade rows; a what-if adds the sandbox's rows to a copy of one set's vector.

## 8. Scopes

| Scope | Meaning |
|---|---|
| `NSET:<id>` | **the margin**: the netting set's SIMM trades, its CSA's version and regimes |
| `CPTY:<id>`, `GROUP:<id>` | every netting set of the counterparty (or group) **each computed alone**, listed with their sum (SIMM does not net across netting sets) |
| Any other scope (`DESK:`, `BOOK:`, `BASKET:` …) | an **analytic**: SIMM of that set of trades as if one netting set, default version, no regimes; the answer says *analytic, not a margin* |

Additive: net sensitivities per factor (per netting set and node). Not additive: everything else.

## 9. What Drishti answers

### 9.1 The API (proposed)

Base `/api/v1/simm`, JSON, as the caller.

| Method and path | Parameters | Answer |
|---|---|---|
| `GET /scope` | `scope`, `asOf`, `version` (default the CSA's), `direction` (`both`, `collect`, `post`) | the headline (§9.2); for `CPTY:` one per netting set |
| `GET /scope/breakdown` | `scope`, `asOf`, `direction`, `level` (`product`, `risk`, `measure`, `bucket`, `factor`) | the tree of §6.3–6.5 with every intermediate (WS, CR, K_b, S_b) |
| `GET /scope/crif.csv` | `scope`, `asOf` | the scope's trade-level CRIF (ISDA's CSV layout), for the counterparty or a vendor |
| `GET /scope/reconcile` | `scope` (a netting set), `asOf`, `source` | §9.4 |
| `POST /what-if` | `{scope, add: [CRIF or sensitivity rows], remove: [trade ids]}` | IM before, after, and the incremental IM by risk class |
| `GET /scope/contributions` | `scope`, `asOf`, `by` (`trade`, `book`, `desk`) | marginal IM per part (§6.7) |
| captures, compare, status, reload | | as VaR; captures `SMCAP-<yyyymmdd>-<n>` keep the net CRIF |

### 9.2 The headline and the computed kind

Kind `simm-margin`, id the canonical scope (`NSET:NS-ALDERSHOT-FRA`, `~version=2.6`). Proposed record:

| Field | Type | Meaning |
|---|---|---|
| `SCOPE`, `CANONICAL`, `BUSINESS_DATE`, `NETTING_SET`, `COUNTERPARTY`, `CSA` | text, date | |
| `ANALYTIC` | boolean | true when the scope is not one netting set |
| `SIMM_VERSION` | text | `2.6` |
| `IM_COLLECT`, `IM_POST` | amount (USD) | §6.6 |
| `COLLECT_REGIME`, `POST_REGIME` | text | the binding regimes |
| `SIMM`, `ADDON` | amount | for each direction: `SIMM_COLLECT`, `ADDON_COLLECT`, `SIMM_POST`, `ADDON_POST` |
| `BY_PRODUCT_CLASS` | record list | `PRODUCT_CLASS`, `COLLECT`, `POST` |
| `BY_RISK_CLASS` | record list | `PRODUCT_CLASS`, `RISK_CLASS`, `DELTA`, `VEGA`, `CURVATURE`, `BASE_CORR`, `TOTAL` (collect side; post in the breakdown) |
| `IM_CURRENCY`, `IM_COLLECT_CCY`, `IM_POST_CCY` | text, amount | in the CSA's currency |
| `TRADES`, `TRADES_SCHEDULE_IM`, `MISSING_TRADES` | integer | |
| `PROVENANCE` | record | VaR's, plus `sensitivities` batches, `simmFile` (version, hash), `csa`, `regimes` |

Kind `simm-whatif` (sandbox): `IM_BEFORE`, `IM_AFTER`, `INCREMENTAL` per direction and risk class.

### 9.3 Reactive connector and Python engine (proposed)

```yaml
# config/connectors/simm-engine.yaml                       (proposed, 2.9.0)
plugin: reactive
mode: reactive
description: ISDA SIMM initial margin of a netting set (or an analytic scope)
kinds:
  simm-margin:
    compute: simm-margin
    params:
      version:   { type: text, default: "" }                      # empty: the CSA's
      direction: { type: enum, options: [both, collect, post], default: both }
    result: { required: [SCOPE, BUSINESS_DATE, IM_COLLECT, IM_POST], types: { IM_COLLECT: number, IM_POST: number } }
  simm-whatif:
    compute: simm-whatif
    params:
      sandbox: { type: sandbox-ref, required: true }
reads:
  - { kind: simm-net, as: net, id: "{id}" }          # proposed Java kind: per regime × product class, net SIMM sensitivities with CRIF attributes
  - { kind: csa, as: csa, id: "{inputs.net.CSA}" }
  - { kind: simm-addon-net, as: addon, id: "{id}" }
engine:
  type: python
  entry: drishti_risk.simm:SimmEngine
  pool: risk
  version: "1.0"
  split: net                                          # a CPTY: or GROUP: scope is split by netting set over the workers
triggers:
  - { on: data-version, kinds: [sensitivity, netting-set, csa, simm-addon], affects: all }
  - { on: write-back, kinds: [trade, netting-set], affects: subscribed }
  - { on: schedule, cron: "0 30 6 * * MON-FRI", zone: America/New_York }      # the daily IM run of every netting set (official captures)
coalesce: { quiet: 500ms, max-wait: 5s }
cache: { results: 50000, ttl: 1d }
limits: { timeout: 120s, wait: 100ms, max-inflight-per-user: 4 }
requires: { python: { version: "3.12", lock: requires/drishti-risk.lock } }
```

```python
# compute/drishti-risk/src/drishti_risk/simm.py                  (proposed)
class SimmEngine(Engine):
    kinds = ("simm-margin", "simm-whatif")
    def compute(self, req, inputs, progress):
        net = inputs.arrow("net")     # NETTING_SET, REGIME, DIRECTION_SET (collect|post), PRODUCT_CLASS, RISK_TYPE, QUALIFIER,
                                      # BUCKET, LABEL1, LABEL2, AMOUNT_USD (net); one row per factor
        csa = inputs.documents("csa")
        params = load_simm(version)   # the licensed file of §6.1
        ...
        return Result(rows=[headline], tables={"breakdown": ..., "factors": ...})   # factors with WS, CR and the gradient
```

### 9.4 Reconciliation view

For a netting set and a `crif-external` source: IM total and by product class, risk class and bucket, side by side (Drishti, other,
difference, difference %), and the CRIF lines that differ (missing trades on either side, different buckets, amounts beyond a
tolerance `drishti.simm.recon-tolerance`, default 1% or 10,000 USD). The view answers *why* (*trade MX-… has no EUR 10y delta in the
counterparty's CRIF*), never re-computes the counterparty's number.

### 9.5 Official end-of-day captures and the published table

The daily IM run (the schedule trigger) captures every netting set; the hierarchy nodes are captured as analytics. Published to
`${DRISHTI_DELTA_ROOT}/risk-results/simm-official/` ([README.md section 11](README.md#11-what-drishti-answers-computed-kinds-the-api-captures),
product owner 2026-10-25): one row per node of both hierarchies per business date, the shared columns (`BUSINESS_DATE`, `HIERARCHY`,
`NODE_ID`, `NODE_NAME`, `PARENT_ID`, `LEVEL`, `CAPTURE_ID`, `RESULT_VERSION`, `INPUT_VERSIONS`, `COMPUTED_AT`, `CONTRACT_VERSION`) and:

| Column | Type | Meaning |
|---|---|---|
| `SIMM_VERSION` | STRING | the version used for the node (the default version: a node is an analytic) |
| `IM_ANALYTIC` | DOUBLE | the node's SIMM as one portfolio (§8, analytic), USD |
| `IM_COLLECT_SUM`, `IM_POST_SUM` | DOUBLE | Σ over the netting sets whose trades are all under the node of their margins (a netting set split across nodes is counted at the lowest node holding all its trades) |
| `NETTING_SET_COUNT` | BIGINT | netting sets counted in the sums |
| `IR`, `CREDIT_Q`, `CREDIT_NONQ`, `EQUITY`, `COMMODITY`, `FX` | DOUBLE | `IM_ANALYTIC` by risk class (before ψ) |
| `TRADE_COUNT`, `MISSING_TRADES` | BIGINT | |

The netting sets' margins are not nodes and are not in this table: they are published by the margin process of 2.10.0 (`MC.md`,
written separately) with the expected calls (open question 6). No trade or CRIF rows are published.

## 10. Validation, errors and data quality

Codes are **proposed** (range `DRS-533x`).

| Check | Rule | Outcome |
|---|---|---|
| Mapping | every SIMM-read row maps to a CRIF risk type (§3.2) | row excluded from SIMM, counted `unmappedRiskType` |
| Bucket | `SIMM_BUCKET` present and valid for the version (`Residual` allowed) | excluded, counted (`DRS-5331` *no SIMM bucket for <qualifier>*) |
| Tenor | `TENOR` a CRIF tenor | excluded, counted |
| Netting set | the trade belongs to exactly one netting set on the date | a trade in two: kept in both, flagged `DRS-5332`; in none: in no `NSET:` scope |
| Version | the CSA's version has a parameter file | `422 DRS-5333` *SIMM 2.4 is not installed* |
| Regimes | regime codes in the version's list | unknown codes ignored, listed |
| Cleared trades | a cleared trade with SIMM rows | excluded (cleared trades are margined by the CCP) |
| Requests, rights, compute | | `400 DRS-5001`, `403 DRS-5002`, `DRS-9202`/`9203` |

## 11. Entitlements and audit

VaR's model with `drishti.simm.access`, the gate kind `sensitivity`, and **per counterparty**: a role limited to counterparties (credit
officers of a coverage group) sees `NSET:` and `CPTY:` scopes of those counterparties only (proposed `drishti.simm.counterparties`,
open question 5). The trade-level CRIF CSV is a trade-level export: `positions` level only, audited (`simm-export`). `simm-what-if` and
`simm-capture` audited.

## 12. Performance and sizing

| Operation | Target (p95, warm) |
|---|---|
| IM of one netting set (200 factors) | 150 ms (Python worker, 2.3 ms of transport) |
| every netting set of a counterparty (20 sets) | 400 ms (split over the pool) |
| the daily run of 50,000 netting sets | 10 minutes on 16 workers (about 0.2 s a set, both directions, all regimes) |
| incremental IM of a candidate trade | 200 ms |
| the bank as an analytic | 2 s |

## 13. Test data and generators

### 13.1 Planned

| Item | Plan |
|---|---|
| Sensitivities | FRTB.md §13.1's generator also writes the `SIMM` grid rows (IR `2w`, `1m`; vega on SIMM expiries) and `SIMM_BUCKET` |
| Netting sets | the committed `netting-set` records (membership `TRADE_IDS`) restricted to live trades; CSAs gain `SIMM_VERSION`, regimes and the IM threshold (fundamental fields, generator `tools/packgen/banking/data_results.py`) |
| Parameter file | `config/simm/simm-test.yaml`: **illustrative** values in the version 2.6 layout (not ISDA's), so the repository holds no licensed numbers |
| `crif-external` | for two netting sets, the generated CRIF with deliberate differences (a missing trade, a bucket change) |
| Reference test | `test_simm_reference.py` (proposed): an independent small implementation and the worked example; with a licensed file, ISDA's published unit tests (the SIMM test portfolios) run in the bank's environment only |

### 13.2 How the retired figures were made

`tools/packgen/banking/data_results.py`, `credit_results`, **per counterparty** (not per netting set):

| Field | Rule |
|---|---|
| `TOTAL` | `round(Σ |MtM| of the counterparty's netting sets' trades × 0.08 + 1,000,000)`: not sensitivity-based |
| `RISK_CLASSES[]` | fixed shares of the total: Interest rate 45%, Credit qualifying 12%, Equity 18%, Commodity 8%, FX 17% (summing to 100%: no ψ diversification, every counterparty has all five) |
| `POSTED`, `RECEIVED` | `TOTAL × 0.97` and `TOTAL × 1.02` |
| `NETTING_SET` | the **first** netting set of the counterparty, though the total covers all of them |
| `VERSION` | the text `ISDA SIMM 2.7` |

The test data recomputes over the scaled universe (`SIMM-ALDERSHOT`: 7,068,571 in the sample, 249,266,327 in the test data).

### 13.3 What the current data shows (checked 2026-10-25)

18 records in the sample, 24 in the test data (one per counterparty), dated only by the 2026-07-08 snapshot; the version label is
**2.7** while this contract's first parameter set is 2.6 (open question 2). No CSA in the samples names a SIMM version or regimes.

## 14. Retiring the stored version

| Old | New |
|---|---|
| `SIMM-<cp>` (`SIMM-ALDERSHOT`) | `SIMM CPTY:CP-ALDERSHOT`: each of its netting sets, computed alone (`/v/simm-margin/CPTY:CP-ALDERSHOT`) |
| `/v/simm/<id>` | the same, by redirect |

**Removed in 2.9.0:** the kind `simm` from `config/packs/counterparty-risk/pack.yaml` (kind, route, layout, form, id pattern `^SIMM-`),
`samples/simm.jsonl`, `data/testdata/counterparty-risk/simm/`, the Sutra `config/packs/counterparty-risk/sutras/collateral-and-margin/simm.v1.sutra.yaml`,
the SIMM part of `credit_results`, the contract `simm.contract.yaml`, the about and README sections; mnemonic `SIMM` opens the computed
page. **Guard test:** fails when `simm` returns or an input carries `IM`, `SIMM_TOTAL`, `POSTED`, `RECEIVED`.

**Reconciliation.** No factor relates the stored totals to SIMM (§13.2). The release notes list per counterparty the stored `TOTAL`
and the engine's collect and post per netting set on 2026-09-30 (with the illustrative parameter file the demo ships), and say that
demo IM figures are illustrative until a licensed file is loaded.

## 15. A worked example

**Illustrative parameters** (not ISDA's): IR delta RW USD 5y 50 bp, 10y 51 bp, EUR 2y 54 bp; ρ(5y, 10y) = 0.94; γ(USD, EUR) = 0.30;
FX delta RW 8 (per 1% amount); ψ(IR, FX) = 0.25; concentration thresholds high enough that CR = 1, except in the last step. One
netting set, product class `RatesFX`, the same trades as [FRTB.md section 15](FRTB.md#15-a-worked-example) plus an FX exposure:

| Factor (CRIF) | Net `AMOUNT_USD` | RW | WS = RW × s × CR |
|---|---|---|---|
| `Risk_IRCurve` USD `5y` `OIS` | +10,000 per bp | 50 | 500,000 |
| `Risk_IRCurve` USD `10y` `OIS` | −6,000 per bp | 51 | −306,000 |
| `Risk_IRCurve` EUR `2y` `OIS` | +4,000 per bp | 54 | 216,000 |
| `Risk_FX` EUR | +20,000 per 1% | 8 | 160,000 |

| Step | Computation | Value |
|---|---|---|
| K_USD | sqrt(500,000² + 306,000² − 2 × 0.94 × 500,000 × 306,000) | 236,634.74 |
| K_EUR | one factor | 216,000 |
| S_USD, S_EUR | Σ WS bounded by ±K: 194,000 (inside ±236,634.74); 216,000 | 194,000; 216,000 |
| DeltaMargin_IR | sqrt(236,634.74² + 216,000² + 2 × 0.30 × 194,000 × 216,000) | **357,483.43** |
| DeltaMargin_FX | one factor | **160,000** |
| SIMM_RatesFX | sqrt(357,483.43² + 160,000² + 2 × 0.25 × 357,483.43 × 160,000) | **426,606.46** |
| SIMM | one product class | **426,606** = IM_COLLECT = IM_POST (delta only: symmetric) |

**With concentration.** If the USD delta threshold is 3,000 USD per bp: CR_USD = max(1, sqrt(|10,000 − 6,000| / 3,000)) = 1.154701;
the USD weighted sensitivities become 577,350.27 and −353,338.36, K_USD = 273,242.26, S_USD = 224,011.90, g(USD, EUR) =
min(1.1547, 1) / max(1.1547, 1) = 0.866025, DeltaMargin_IR = sqrt(273,242.26² + 216,000² + 2 × 0.30 × 0.866025 × 224,011.90 × 216,000)
= **382,700.58**, and SIMM = **450,195.27**: the concentration adds 23,589.

**What is kept:** the netting set's aggregate holds the four net amounts (per regime and product class); every line above is computed
from them at query time. **Incremental IM** of a candidate trade with USD 10y +6,000 per bp: the USD 10y net goes to 0, K_USD = 500,000,
S_USD = 500,000, DeltaMargin_IR = sqrt(500,000² + 216,000² + 2 × 0.3 × 500,000 × 216,000) = 601,212.11 (CR 1), up from 357,483.43:
the candidate adds IM, because the existing 10y short was a hedge.

## 16. Open questions and the change table

### 16.1 Open questions

| # | Question | Context |
|---|---|---|
| 1 | **ISDA licence:** does the bank (or Drishti's distribution) hold a SIMM licence? Proposed: Drishti ships the format and illustrative values only; the bank installs the licensed files | §6.1 |
| 2 | Which versions to install first (2.6 as briefed; the stored data says 2.7; the CSAs name the agreed version) and how a version change on its effective date is handled (both versions computed for a week?) | §5, §13.3 |
| 3 | Equity repo and other risk-type details per version: follow the CRIF specification of the installed version; who confirms the mapping table §3.2? | §3.2 |
| 4 | Is the CRIF produced upstream (then loaded into `sensitivity` by Drishti's CRIF reader) or does Drishti map the risk engine's sensitivities (this contract's default)? | §3.1 |
| 5 | Entitlements by counterparty coverage (credit officers): a new setting, or a general per-counterparty entitlement in 2.10.0? | §11 |
| 6 | Where netting-set margins are published: in 2.10.0 with the expected call (`MC.md`), or a `simm-netting-set-official` table in 2.9.0? | §9.5 |
| 7 | Schedule IM (the regulatory grid) for netting sets that use it: in scope of SIMM.md, or of `MC.md`? | §3.5 |
| 8 | The reconciliation sources (`crif-external`): which vendors or custodians, and in what format? | §3.4 |

### 16.2 The change table

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-25 | First version (PLANNED for 2.9.0): SIMM from the shared sensitivities through the CRIF mapping, versioned parameter files, collect and post per regime, incremental IM, reconciliation, the published `simm-official` table, the retirement of `simm` |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
