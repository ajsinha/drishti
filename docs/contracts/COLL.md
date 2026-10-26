<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# COLL: collateral valuation, the data contract

*Version 1.0 · 2026-10-25 · Shared rules: [README.md](README.md) · Model contract: [VAR.md](VAR.md) · CSAs and netting sets:
[NSET.md](NSET.md)*

**Status:** proposed. PLANNED for 2.10.0, not built. Every table, column, setting, kind, endpoint and code below is a proposal; names
may change before the release, and each change will be recorded in [section 16](#16-open-questions-and-the-change-table).

This is the contract of **collateral valuation**: Drishti keeps the collateral **holdings** (what asset, how much, held or posted, under
which CSA and netting set, as variation or initial margin) and computes their **values** when asked (market value from market data, FX
to USD, eligibility against the CSA, haircuts, concentration limits), for any scope. NSET (net collateral, current exposure), SACCR (C and
NICA), MC (the collateral balance a call is measured against) and EXP (today's IM and independent amounts) all read these values.

## Contents

1. [Summary](#1-summary)
2. [The measure in one page](#2-the-measure-in-one-page)
3. [Inputs from upstream engines](#3-inputs-from-upstream-engines)
4. [Reference and master data read](#4-reference-and-master-data-read)
5. [Conventions specific to collateral](#5-conventions-specific-to-collateral)
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

**Commands.** `COLL <id> <GO>` opens a collateral balance (the kept account) with its computed values; `COLL NSET NS-ALDERSHOT-NY <GO>`,
`COLL CPTY CP-ALDERSHOT <GO>`, `COLL CSA CSA-ALDERSHOT <GO>` value any scope. The API is `/api/v1/collateral`.

**Kept and computed.** The `collateral-balance` kind stays as the **account** (one per netting set, as today's `COLL-<cp>-<le>` ids); its
positions become rows of a new input table `collateral-holding`. Removed from the stored record: `HELD`, `POSTED`, `NET`,
`POSITIONS[].MARKET_VALUE`, `POSITIONS[].VALUE` (computed), and `POSITIONS[]` itself with its `HAIRCUT` (a CSA term, read from the CSA).

| Part | Status |
|---|---|
| `collateral-balance` as the account (kept, moved to the risk lake) and `collateral-holding` (new, one row per holding) | PLANNED |
| Market value, FX, eligibility, CSA haircuts, FX-mismatch haircut, concentration limits, contractual and regulatory values | PLANNED |
| Computed kind `coll-valuation`, reactive connector `coll-engine` (Java); a substitution what-if (Python) | PLANNED |

## 2. The measure in one page

**What it answers:** *what is the collateral we hold and have posted worth today, and how much of it counts?* For collateral managers
(eligibility, haircuts, concentration), credit officers (exposure after collateral), regulatory capital (C and NICA in SA-CCR).

**In words:** each holding's **market value** is its quantity times its price (bonds per 100 of face, plus accrued interest when
configured; equities per share; cash 1), converted to USD at the business date's FX. Its **eligibility** is the first rule of the CSA's
schedule it matches (asset type, currency, issuer country, rating, residual maturity, margin type). Its **contractual value** is the market
value less the rule's haircut, zero when ineligible, less any excess over the rule's concentration limit. Its **regulatory value** applies
supervisory haircuts and an FX-mismatch haircut when its currency differs from the CSA's. Held collateral counts positive, posted
negative; variation margin, initial margin and independent amounts are kept apart.

## 3. Inputs from upstream engines

### 3.1 Where they live

```
${DRISHTI_DELTA_ROOT}/risk/
├── collateral-balance/   business_date=YYYY-MM-DD/   one row per collateral account (kept; moved from collateral/collateral-balance)
├── collateral-holding/   business_date=YYYY-MM-DD/   one row per holding: asset × direction × margin type in an account   (new)
└── csa/                                              NSET.md section 3.5: eligibility schedule and haircuts
```

Source: the collateral management system, end of day after settlement (due **to be agreed**, open question 1). Load signal reloads the
date in the engines (product owner, 2026-10-25); kept 7 years.

### 3.2 `collateral-balance`: the account (kept)

| Column | JSON | Delta | Req. | Engine | Meaning | Example |
|---|---|---|---|---|---|---|
| `BALANCE_ID` | id | `id` STRING | required | R | the account | `COLL-ALDERSHOT-FRA` |
| `BUSINESS_DATE` | date | `business_date` DATE | required | R | (replaces today's `AS_OF`) | `2026-09-30` |
| `NETTING_SET`, `CSA`, `COUNTERPARTY` | id | STRING | required | R | links | `NS-ALDERSHOT-FRA`, `CSA-ALDERSHOT`, `CP-ALDERSHOT` |
| `CUSTODIAN` | text | doc | optional | – | tri-party agent or custodian | `Euroclear` |
| `REHYPOTHECATION_ALLOWED` | boolean | STRING | optional | R | may the bank reuse VM it holds | `true` |
| `IM_SEGREGATED_ACCOUNT` | text | doc | optional | – | the segregated IM account reference | `EC-IM-0042` |
| `BATCH_ID` | id | STRING | required | – | | `B-2026-09-30-01` |

### 3.3 `collateral-holding`: what is held and posted (kept, new)

**Grain:** one row per account, asset, direction, margin type and settlement status per business date. **Key:** `HOLDING_ID` =
`<BALANCE_ID>~<MARGIN_TYPE>~<DIRECTION>~<ASSET_ID>~<SETTLEMENT_STATUS>`. **Row count:** test data about 250; a large bank a few
hundred thousand.

| Column | JSON | Delta | Req. | Engine | Unit | Meaning | Example |
|---|---|---|---|---|---|---|---|
| `HOLDING_ID` | id | `id` STRING | required | – | | | `COLL-ALDERSHOT-NY~VM~HELD~US91282CJL54~SETTLED` |
| `BUSINESS_DATE` | date | `business_date` DATE | required | R | | | `2026-09-30` |
| `BALANCE_ID`, `NETTING_SET`, `CSA` | id | STRING | required | R | | the account and, denormalised, its set and CSA | `COLL-ALDERSHOT-NY` |
| `MARGIN_TYPE` | text | STRING | required | R | | `VM`, `IM`, `IA` (independent amount) | `VM` |
| `DIRECTION` | text | STRING | required | R | | `HELD` (received by the bank) or `POSTED` (delivered by the bank) | `HELD` |
| `ASSET_TYPE` | text | STRING | required | R | | `CASH`, `GOVT_BOND`, `AGENCY_BOND`, `CORP_BOND`, `COVERED_BOND`, `EQUITY`, `GOLD` | `GOVT_BOND` |
| `ASSET_ID` | id | STRING | required | R | | ISIN (bonds, equities: link `bond` or `equity`), `CASH-<ccy>` for cash | `US91282CJL54` |
| `CURRENCY` | ccy | STRING | required | R | | the asset's currency | `USD` |
| `QUANTITY` | amount | DOUBLE | required | R | face (bonds), shares, currency units (cash) | ≥ 0 | `5000000` |
| `ISSUER`, `ISSUER_COUNTRY`, `ISSUER_RATING` | id, text, text | STRING | optional | R | | when the asset is not in the security master (else read from it) | `ISS-UST`, `US`, `AA+` |
| `MATURITY_DATE` | date | STRING | optional | R | | the same rule | `2029-11-15` |
| `PRICE` | amount | DOUBLE | optional | R | per 100 (bonds), per share | an upstream price, used only when the market-data pack has none (`PRICE_SOURCE` says so) | `98.50` |
| `SEGREGATED` | boolean | STRING | optional | R | | held in a bankruptcy-remote account (IM: normally true) | `false` |
| `SETTLEMENT_STATUS` | text | STRING | required | R | | `SETTLED`, `PENDING_IN`, `PENDING_OUT` | `SETTLED` |
| `BATCH_ID` | id | STRING | required | – | | | `B-2026-09-30-01` |

**Not delivered:** market value, haircut, values, eligibility (all computed). A `HAIRCUT` sent on a holding is ignored and flagged (the
haircut is the CSA's).

## 4. Reference and master data read

| Kind | Key | Read for |
|---|---|---|
| `csa` (NSET.md) | `CSA_ID` | `CSA_CURRENCY`, `ELIGIBLE[]` rules, `MPOR_DAYS` (scaling of the FX haircut) |
| `bond` (market-data) | `BND-<ISIN>` (its `ISIN_CODE` is the holding's `ASSET_ID`) | `PRICE` (clean, per 100), `COUPON`, `MATURITY`, `ISSUER`, `RATING` |
| `equity` (market-data) | `TICKER` | `PRICE`, `CURRENCY` |
| `fx-spot` (market-data) | `FX-<ccy>USD` | `MID` at the business date |
| `issuer` (core) | `ISSUER` | country, rating, sector, LEI (concentration by issuer, wrong-way collateral in WWR.md) |
| `netting-set` | | scope |

## 5. Conventions specific to collateral

| Convention | Rule |
|---|---|
| Sign | `HELD` positive, `POSTED` negative in every total (README.md section 7) |
| Margin types | VM, IM and IA totals never net against each other; `COLLATERAL` in NSET is VM held − VM posted |
| Currency | USD; FX at the business date's mid |
| Haircut | a fraction of market value, 0 to 1 |
| Pending | `PENDING_IN` counts as held and `PENDING_OUT` as still held until settled (setting `pending: count-until-settled`) |

## 6. The computation

### 6.1 Parameters (`drishti.ccr.collateral.*`)

| Setting | Default | Meaning |
|---|---|---|
| `accrued-interest` | `false` | add accrued interest to bonds (needs coupon dates) |
| `fx-haircut` | `0.08` | the FX-mismatch haircut at a 10-day MPOR, regulatory value only (CRE22 supervisory value), scaled by √(MPOR/10) |
| `fx-haircut-contractual` | `false` | apply it to the contractual value too (some CSAs do) |
| `supervisory-haircuts` | the CRE22 table by issuer type, rating and residual maturity | the regulatory value's haircuts |
| `concentration` | `per-issuer` | how `CONCENTRATION_LIMIT` is applied (§6.4) |
| `pending` | `count-until-settled` | §5 |

### 6.2 Market value

```
MV = QUANTITY × PRICE / 100 × FX(ccy→USD)           bonds (clean price; + accrued when configured)
MV = QUANTITY × PRICE × FX                          equities, gold (price per unit)
MV = QUANTITY × FX                                  cash
```

### 6.3 Eligibility and haircut

The first rule of the CSA's `ELIGIBLE[]` whose `ASSET_TYPE` equals the holding's, whose `APPLIES_TO` covers its margin type, whose
`CURRENCIES` (if any) contain its currency, whose `ISSUER_COUNTRIES` (if any) contain its issuer's country, whose `MIN_RATING` (if any) is
at or below the issuer's rating, and whose residual-maturity band contains its residual maturity in years. No rule: **ineligible**.

```
h          = HAIRCUT of the matched rule (contractual)
h_fx       = fx-haircut × sqrt(MPOR_DAYS / 10)   when CURRENCY ≠ CSA_CURRENCY  (regulatory; contractual only if configured)
h_sup      = the supervisory haircut for the asset (regulatory)
CONTRACT_VALUE  = MV × (1 − h)                  eligible;  0 ineligible
REG_VALUE_HELD   = MV × (1 − h_sup − h_fx)        eligible held collateral
REG_VALUE_POSTED = MV × (1 + h_sup + h_fx)        posted collateral (CRE52: posted collateral increases exposure with a haircut up)
```

### 6.4 Concentration

For each CSA and issuer, with L the rule's `CONCENTRATION_LIMIT` and `TOTAL` the CSA's held contractual value before the limit: when the
issuer's value exceeds L × `TOTAL`, the excess is valued 0, taken from that issuer's holdings pro rata. Applied to held collateral only.

### 6.5 Totals

```
VM_HELD   = Σ CONTRACT_VALUE (VM, HELD)        VM_POSTED = Σ CONTRACT_VALUE (VM, POSTED)      (both ≥ 0, posted shown negative)
IM_HELD, IM_POSTED, IA_HELD, IA_POSTED          likewise
COLLATERAL (NSET)  = VM_HELD − VM_POSTED
C (SA-CCR)         = Σ REG_VALUE_HELD − Σ REG_VALUE_POSTED over VM, IM and IA, excluding posted collateral that is SEGREGATED
NICA (SA-CCR)      = Σ REG_VALUE over IM and IA held − Σ REG_VALUE over IM and IA posted that is not SEGREGATED
```

Everything is a sum over holdings: **fully additive** over holdings, accounts, netting sets, CSAs and counterparties (concentration is
applied per CSA first).

### 6.6 Where it runs

Java (`com.ash.drishti.compute.ccr.CollateralEngine`, planned), at date load: a few hundred thousand holdings, priced once. A
**substitution what-if** (replace or add holdings, see the new values and the CSA's eligibility) runs in the Python engine on the
holdings of one CSA (§9.4).

## 7. What Drishti builds

Per holding: MV, matched rule, h, h_fx, h_sup, contractual and regulatory values, flags (24 doubles and codes: about 300 bytes; 300,000
holdings 90 MB). Per account, netting set, CSA and counterparty the totals of §6.5 (float64). No disk structure beyond the lake; the
answer cache as NSET's.

## 8. Scopes

`NSET:`, `CPTY:`, `GROUP:`, `LE:` (NSET.md section 8) and the proposed `CSA:`; the collateral of a scope is the collateral of the netting
sets it covers whole (collateral is never apportioned to a partial set by default). Default parts: counterparty → CSAs → accounts →
holdings.

## 9. What Drishti answers

### 9.1 The computed kind `coll-valuation`

| Field | Type | Unit | Meaning |
|---|---|---|---|
| `SCOPE`, `CANONICAL`, `SCOPE_KIND`, `LABEL`, `BUSINESS_DATE` | | | |
| `VM_HELD`, `VM_POSTED`, `IM_HELD`, `IM_POSTED`, `IA_HELD`, `IA_POSTED` | amount | USD | contractual values (posted negative) |
| `COLLATERAL` | amount | USD | VM held − VM posted |
| `MARKET_VALUE_HELD`, `MARKET_VALUE_POSTED` | amount | USD | before haircuts |
| `C_REG`, `NICA_REG` | amount | USD | SA-CCR (§6.5) |
| `INELIGIBLE_VALUE`, `CONCENTRATION_EXCESS` | amount | USD | market value that does not count, by cause |
| `HOLDINGS` | record list | | per holding: `HOLDING_ID`, `ASSET_ID`, `ASSET`, `ASSET_TYPE`, `MARGIN_TYPE`, `DIRECTION`, `CURRENCY`, `QUANTITY`, `PRICE`, `FX`, `MARKET_VALUE`, `RULE_ID`, `HAIRCUT`, `FX_HAIRCUT`, `VALUE`, `REG_VALUE`, `ELIGIBLE`, `FLAGS` |
| `BY_ASSET_TYPE`, `BY_ISSUER`, `BY_CURRENCY` | record list | | sums |
| `PROVENANCE` | record | | NSET's, plus the prices' and FX's source and date |

### 9.2 The API (`/api/v1/collateral`, planned)

| Method and path | Parameters | Answer |
|---|---|---|
| `GET /scope` | `scope`, `asOf` | §9.1 |
| `GET /scope/holdings` | `scope`, `page`, `size`, `filter` (`ineligible`, `concentration`) | holdings, paged; `format=csv` |
| `POST /what-if` | `{csa, add: [holding rows], remove: [HOLDING_ID]}` | values before and after, eligibility of the new holdings, concentration |
| captures, status, reload | as NSET | |

### 9.3 Published official results (`collateral-official`, `collateral-official-cpty`)

The two tables of [NSET.md section 9.5](NSET.md#95-published-official-results-nset-official-nset-official-cpty), with the shared columns
and `VM_HELD`, `VM_POSTED`, `IM_HELD`, `IM_POSTED`, `IA_HELD`, `IA_POSTED`, `COLLATERAL`, `MARKET_VALUE_HELD`, `MARKET_VALUE_POSTED`,
`C_REG`, `NICA_REG`, `INELIGIBLE_VALUE`, `CONCENTRATION_EXCESS` (DOUBLE, USD), `HOLDINGS` (BIGINT). The `-cpty` table adds a row level
`csa` (one row per CSA) beside netting sets, counterparties and groups. No holding rows are published.

### 9.4 The reactive connector and engines (proposed)

```yaml
# config/connectors/coll-engine.yaml                            (planned, 2.10.0)
plugin: reactive
mode: reactive
description: Collateral values (market value, eligibility, haircuts, concentration) for any scope
kinds:
  coll-valuation:
    compute: collateral
    result: { required: [COLLATERAL, VM_HELD, VM_POSTED], types: { COLLATERAL: number } }
  coll-whatif:
    compute: collateral-whatif
    params:
      sandbox: { type: sandbox-ref, required: true }
reads:
  - { kind: collateral-holding, as: holdings, all: true }
  - { kind: collateral-balance, as: accounts, all: true }
  - { kind: csa, as: csas, all: true }
  - { kind: bond, as: bonds, all: true }                   # matched on ISIN_CODE
  - { kind: fx-spot, as: fx, all: true }
engine:
  type: java
  entry: com.ash.drishti.compute.ccr.CollateralEngine
  version: "1.0"
triggers:
  - { on: data-version, kinds: [collateral-holding, collateral-balance, csa, bond, fx-spot], affects: all }
  - { on: write-back, kinds: [collateral-holding], affects: "NSET:{payload.NETTING_SET}" }
cache: { results: 2000, ttl: 1d }
limits: { timeout: 20s, wait: 50ms, max-inflight-per-user: 4 }
```

The `coll-whatif` kind is computed by the same Java engine for one CSA (it is a few dozen holdings); a Python engine is not needed. The
Java interface is the platform's `ComputeEngine` (REACTIVE_CONNECTOR.md): `compute(req, inputs, progress)` reads the holdings as
documents, prices them and returns the record of §9.1 as one row with `HOLDINGS` as a table.

## 10. Validation, errors and data quality

| Check | Rule | Outcome |
|---|---|---|
| Enums | `MARGIN_TYPE`, `DIRECTION`, `ASSET_TYPE`, `SETTLEMENT_STATUS` valid | row rejected |
| Quantity | ≥ 0 (direction carries the sign) | row rejected |
| Price | a price from the market-data pack or `PRICE` on the row | none: market value unknown, holding flagged `unpriced`, valued 0, counted |
| FX | the currency's spot at the date | none: flagged `no-fx`, valued 0 |
| Eligibility | no matching rule | `ineligible`, valued 0, listed (`INELIGIBLE_VALUE`) |
| Links | `BALANCE_ID`, `NETTING_SET`, `CSA` exist and agree | flagged |
| Haircut sent | a `HAIRCUT` on a holding | ignored, flagged `haircut-from-csa` |

**Proposed DRS codes** (5420–5429): `DRS-5420` (400, bad scope or what-if rows), `DRS-5421` (404, a CSA without an eligibility schedule
asked in `strict` mode).

## 11. Entitlements and audit

As NSET.md section 11. Holdings (which bonds a counterparty posts) are visible at `trades` level only; aggregate-only roles see totals.

## 12. Performance and sizing

| Operation | Target (p95) |
|---|---|
| a netting set or CSA | 5 ms |
| a counterparty | 10 ms |
| the bank (300,000 holdings) | 100 ms |
| date load and pricing | 5 s |

## 13. Test data and generators

`tools/packgen/ccr/holdings.py` (planned): for each margined netting set, held or posted holdings whose total contractual value follows
the CSA's call rule at the business date (so the expected margin call is small, a few sets deliberately off: one under-collateralised by
more than the MTA, one with an ineligible corporate bond, one breaching a 25% concentration limit, one with foreign-currency cash, one
pending settlement); assets drawn from the market-data pack's bonds (G7 governments mostly) and cash; IM holdings for the `VM+IM` CSAs.
Files `data/testdata/counterparty-risk/{collateral-balance,collateral-holding}/cob-2026-09-30.jsonl.gz`.

## 14. Retiring the stored version

| Item | Change |
|---|---|
| `collateral-balance` | kept as the account; `HELD`, `POSTED`, `NET`, `AS_OF` (→ `BUSINESS_DATE`), `POSITIONS[]` removed |
| ids | `COLL-<cp>-<le>` unchanged: the account page shows `coll-valuation` at `NSET:NS-<cp>-<le>` |
| Calc snippets | `collateral_haircuts.py` reads `coll-valuation` |
| Generator | the collateral part of `credit_results` (`tools/packgen/banking/data_results.py`) replaced by `holdings.py` |
| Guard test | no input or fundamental kind carries `HELD`, `POSTED`, `NET`, `MARKET_VALUE`, `VALUE` on collateral |

**Reconciliation: how the stored balances were made.** `credit_results` sets the netting set's collateral to a random share of its net
MtM, then picks **3 random bonds** from the whole bond universe, splits the amount equally, and draws a haircut of 0.5%, 1% or 2% at
random. None of it reads the CSA. On `COLL-ALDERSHOT-FRA` (sample and test data) two of the three bonds (*Solaris Energy Corp. 2031*,
*Granite Building Materials plc 2031*) are corporate bonds, which the Aldershot CSA does not accept (cash and G7 government bonds only):
their computed value is **0**; the third, a United States Treasury maturing in 2036 (about 9 years), falls in the CSA's 5–10 year band
with a 4% haircut, not the stored 0.5%. Every stored balance will be revalued and the differences listed in the release notes.

## 15. A worked example

CSA in USD; MPOR 10 days; schedule: USD/EUR/GBP cash 0%, G7 government bonds 1–5 years 2%; corporate bonds not eligible. EUR/USD 1.10.

| Holding | Direction | Quantity | Price | MV (USD) | Rule | h | Contractual value | h_sup + h_fx | Regulatory value |
|---|---|---|---|---|---|---|---|---|---|
| EUR cash | VM held | 2,000,000 | 1 | 2,200,000 | cash 0% | 0 | **2,200,000** | 0 + 8% (EUR ≠ USD) | **2,024,000** |
| US Treasury, 3 years | VM held | 5,000,000 face | 98.50 | 4,925,000 | G7 1–5Y | 2% | **4,826,500** | 2% + 0 | **4,826,500** |
| Corporate bond | VM held | 1,000,000 face | 101.00 | 1,010,000 | none | – | **0** (ineligible) | – | **0** |
| USD cash | VM posted | 500,000 | 1 | 500,000 | cash 0% | 0 | **−500,000** | 0 | **−500,000** |

- `VM_HELD` = 2,200,000 + 4,826,500 = **7,026,500**; `VM_POSTED` = −500,000; `COLLATERAL` = **6,526,500**.
- `INELIGIBLE_VALUE` = 1,010,000.
- `C_REG` = 2,024,000 + 4,826,500 − 500,000 = **6,350,500** (the FX-mismatch haircut takes 176,000 off the EUR cash).
- With a 25% concentration limit per issuer, the Treasury (4,826,500 of 7,026,500 = 69%) would keep only 25% × 7,026,500 = 1,756,625:
  `CONCENTRATION_EXCESS` 3,069,875 (the schedule above has no limit, so it does not apply here).

## 16. Open questions and the change table

### 16.1 Open questions

| # | Question | Context |
|---|---|---|
| 1 | Due time of the holdings, and the collateral system's cut-off (before or after the day's settlements) | §3.1 |
| 2 | Accrued interest in bond collateral values (needs coupon schedules from the security master) | §6.1 |
| 3 | Regulatory haircuts: the supervisory table (CRE22) or the bank's own estimates; FX-mismatch haircut on the contractual value? | §6.1 |
| 4 | Concentration limits per issuer only, or also per asset type, country and currency? | §6.4 |
| 5 | Tri-party collateral: allocations by the agent as holdings rows (proposed), or a schedule-level balance? | §3.3 |

### 16.2 The change table

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-25 | First version, PLANNED for 2.10.0; product owner's decisions of 2026-10-25 applied (load signal reloads the engine, inputs kept 7 years, published official results) |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
