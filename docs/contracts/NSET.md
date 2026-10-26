<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# NSET: netting sets, the data contract

*Version 1.0 · 2026-10-25 · Shared rules: [README.md](README.md) · Model contract: [VAR_GUIDE.md Part II](../guides/VAR_GUIDE.md#14-the-contract-at-a-glance) · Roadmap:
[IMPLEMENTATION_PLAN.md](../architecture/IMPLEMENTATION_PLAN.md#the-computed-measures-roadmap-280-to-2120-product-owner-2026-10-25)*

**Status:** proposed. PLANNED for 2.10.0, not built. Every table, column, setting, kind, endpoint and code below is a proposal; names
may change before the release, and each change will be recorded in [section 16](#16-open-questions-and-the-change-table).

This is the data contract of the **netting set**: the terms Drishti keeps (the agreement, the CSA, which trades belong to which
netting set) and the figures it computes when asked (net MtM, collateral, exposure peaks, limit use, CVA). It is the first of nine
counterparty-risk contracts of 2.10.0, in the order of the roadmap:

| Order | Contract | What it adds | Shared inputs it defines |
|---|---|---|---|
| 1 | **NSET.md** (this page) | netting, the scopes `NSET:`, `CPTY:`, `GROUP:`, `LE:` for counterparty data | `netting-set`, `netting-set-member`, `agreement`, `csa` |
| 2 | [SACCR.md](SACCR.md) | SA-CCR exposure at default | `saccr-trade` |
| 3 | [COLL.md](COLL.md) | collateral values | `collateral-balance` (account), `collateral-holding` |
| 4 | [MC.md](MC.md) | the expected margin call beside the actual one | `margin-call` |
| 5 | [EXP.md](EXP.md) | EE, EPE, EEPE, PFE from simulated paths | `simulation-run`, `exposure-path` |
| 6 | [LIM.md](LIM.md) | limit usage, headroom, pre-deal check | `credit-limit` |
| 7 | [GRP.md](GRP.md) | connected groups and their usage | `counterparty-group`, connection rules |
| 8 | [CVA.md](CVA.md) | CVA, DVA, FVA, BA-CVA | none (reads market data and EXP) |
| 9 | [WWR.md](WWR.md) | wrong-way risk | `credit-path` (optional) |

The shared rules of every contract are in [README.md](README.md). Where a rule is the same as VaR's (scope grammar, captures,
provenance, entitlements, the reactive connector), this page refers to [VAR_GUIDE.md Part II](../guides/VAR_GUIDE.md#14-the-contract-at-a-glance) and states only the difference.

**Sources read for this contract (2026-10-25):** `config/packs/counterparty-risk/contracts/*.contract.yaml`, its `samples/`,
`data/testdata/counterparty-risk/`, `config/packs/core/contracts/counterparty.contract.yaml` and `counterparty-group.contract.yaml`,
`config/packs/market-risk/contracts/trade-valuation.contract.yaml`, `config/packs/trading/contracts/trade.contract.yaml`,
`tools/packgen/banking/data_results.py` (`credit_results`, `reference`), `tools/testdata/universe.py`, `tools/contracts.policy.yaml`.

## Contents

1. [Summary](#1-summary)
2. [The measure in one page](#2-the-measure-in-one-page)
3. [Inputs from upstream engines](#3-inputs-from-upstream-engines)
4. [Reference and master data read](#4-reference-and-master-data-read)
5. [Conventions specific to netting sets](#5-conventions-specific-to-netting-sets)
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

**What it is.** A netting set is a set of trades with one counterparty that net on close-out under one enforceable master agreement,
usually secured by one CSA. Drishti keeps **the terms** (agreement, CSA, netting enforceability, membership) as fundamental data, and
computes **every figure** of a netting set, a counterparty, a group or a legal entity when asked: net MtM, gross positive and negative
MtM, collateral held and posted, and links to the figures of the other measures (SA-CCR EAD, EE and PFE, limit use, CVA).

**Commands.** `NSET <id> <GO>` opens the netting set (terms and computed figures, one page); `NSET CPTY CP-ALDERSHOT <GO>` opens the
netting-set figures of a counterparty, and every other scope the same way. The API is `/api/v1/nset`.

**What is retired (2.10.0, same release).** The computed fields on the stored `netting-set` record (`NET_MTM`, `COLLATERAL`, `EE_PEAK`,
`PFE_PEAK`, `UTILISATION`, `CVA`, `PROFILE[]`, `BY_ASSET[]`), the copied membership rows `TRADES[]` (with each trade's MtM),
`TRADE_COUNT`, the copied `LIMIT`, and the links to stored results (`EXPOSURE_PROFILE`, `CVA_RESULT`, `SACCR`, `COLLATERAL_BALANCE`,
`CREDIT_LIMIT`, `SIMM`). Also the computed figures on the **counterparty master**: `NET_MTM`, `PFE_PEAK` and `NETTING_SETS[].NET_MTM`
(section 14). The `netting-set` kind stays (terms); its mnemonic `NSET` stays.

| Part | Status |
|---|---|
| Terms tables in the common risk lake (`netting-set`, `agreement`, `csa`), moved from domains `credit` and `legal` to `risk` | PLANNED |
| Membership table `netting-set-member` (one row per trade per date) | PLANNED |
| The netting-set figures computed for any scope (Java, additive) | PLANNED |
| Scope words `NSET:`, `CPTY:`, `GROUP:`, `LE:` for counterparty measures | PLANNED (2.8.0 adds them to the grammar, VAR_GUIDE.md section 5.5) |
| Computed kind `nset-figures`, reactive connector `nset-engine` | PLANNED |
| Retirement of the stored figures and the counterparty master's figures; guard test | PLANNED |

## 2. The measure in one page

**What it answers:** *what do we and this counterparty owe each other today, after netting and collateral, and how is it made up?* For
credit officers (a counterparty's position across legal entities), collateral managers (what secures it), and every other
counterparty measure (they all start from the netting set).

**The figures in words.**
1. A netting set's **net MtM** is the sum of its member trades' MtM (from the trade valuations, `TVAL`). Positive: the counterparty owes
   the bank on close-out.
2. **Gross positive** and **gross negative** MtM are the sums of the positive and of the negative trade MtMs: what netting saves is
   gross positive minus max(net, 0).
3. **Collateral** is the haircut value of what the bank holds minus what it has posted under the netting set's CSA (computed by
   [COLL.md](COLL.md)); variation margin and initial margin are kept apart.
4. **Current exposure** is max(net MtM − VM collateral, 0) and the **uncollateralised exposure** max(net MtM, 0).
5. For a scope above netting sets (a counterparty, a group, a legal entity, a desk's slice): MtM and collateral **add**; current exposure
   is the **sum over netting sets** of each set's own max(·, 0): exposures never net across netting sets.
6. A trade in no enforceable netting set is its own netting set (*standalone*): its exposure is max(MtM, 0).

## 3. Inputs from upstream engines

### 3.1 Where they live

From 2.10.0 the counterparty terms move from the domains `credit` (`credit-store`) and `legal` (`legal-store`) to the common risk lake,
domain `risk`, read through the one connector `risk-store` ([README.md section 2](README.md#2-the-common-risk-lake)). The masters that
stay where they are: `trade` (trading pack), `counterparty` and `counterparty-group` (core pack; GRP.md moves the group's membership),
market data, GLEIF.

```
${DRISHTI_DELTA_ROOT}/risk/
├── agreement/            business_date=YYYY-MM-DD/   one row per master agreement             (moved from legal/agreement)
├── csa/                  business_date=YYYY-MM-DD/   one row per CSA                          (moved from legal/csa; terms made numeric)
├── netting-set/          business_date=YYYY-MM-DD/   one row per netting set: terms only      (moved from credit/netting-set; figures removed)
├── netting-set-member/   business_date=YYYY-MM-DD/   one row per trade in a netting set       (new)
├── valuation/      business_date=YYYY-MM-DD/   one row per trade: MtM                   (TVAL.md; already in risk)
└── ...                                               the other measures' tables (SACCR.md, COLL.md, MC.md, EXP.md, LIM.md, WWR.md)
```

The physical table is the one of every kind (VAR_GUIDE.md section 15.1): `id`, `doc`, `business_date`, the promoted columns. In the column
tables below **Delta** is the promoted column's type, or `doc` when the field stays in the document; **Engine** says whether the NSET
engine reads it (**R**) or it is kept for people and links (**–**).

| Kind | Key (`id`) | Grain | Cadence | Due | Shared with |
|---|---|---|---|---|---|
| `agreement` | `AGREEMENT_ID` | master agreement × date | daily snapshot (changes rarely) | to be agreed | SACCR, EXP, MC |
| `csa` | `CSA_ID` | CSA × date | daily snapshot | to be agreed | SACCR, COLL, MC, EXP, CVA |
| `netting-set` | `NETTING_SET_ID` | netting set × date | daily snapshot | to be agreed | every counterparty measure |
| `netting-set-member` | `MEMBER_ID` | trade × netting set × date | daily | with the trade valuations | every counterparty measure |
| `valuation` | `VALUATION_KEY` (the trade id) | trade × date | daily (TVAL.md) | the risk batch's | every counterparty measure |

### 3.2 `netting-set`: the terms of a netting set (kept)

**Grain:** one row per netting set per business date. **Key:** `NETTING_SET_ID`. **Row count:** the bank's netting sets (test data 76;
a large bank about 100,000). **Source:** the legal or collateral system that maps agreements to netting sets.

| Column | JSON | Delta | Req. | Engine | Unit | Meaning | Example |
|---|---|---|---|---|---|---|---|
| `NETTING_SET_ID` | id | `id` STRING | required | R | | `NS-<counterparty>-<legal entity>` in the demo; any unique text upstream | `NS-ALDERSHOT-FRA` |
| `BUSINESS_DATE` | date | `business_date` DATE | required | R | | | `2026-09-30` |
| `COUNTERPARTY` | id | STRING | required | R | | the counterparty (link `counterparty`) | `CP-ALDERSHOT` |
| `LEGAL_ENTITY` | id | STRING | required | R | | the bank's legal entity that faces the counterparty (link `legal-entity`) | `LE-FRA` |
| `AGREEMENT` | id | STRING | required | R | | the master agreement (link `agreement`) | `AGR-ALDERSHOT-ISDA` |
| `CSA` | id | STRING | optional | R | | the CSA securing it; empty: unmargined | `CSA-ALDERSHOT` |
| `NETTING_ENFORCEABLE` | boolean | STRING | required | R | | close-out netting enforceable (legal opinion) in the counterparty's jurisdiction; false: every member is treated as standalone | `true` |
| `NETTING_TYPE` | text | STRING | optional | R | | `DERIVATIVES` (OTC derivatives under ISDA), `SFT` (repo, securities lending under GMRA/GMSLA), `CROSS_PRODUCT` (a cross-product netting agreement), `CLEARED` (a clearing account) | `DERIVATIVES` |
| `PRODUCT_SCOPE` | text list | doc | optional | R | | product groups the agreement covers; a member outside it is flagged (§10) | `["Rates", "FX", "Credit", "Equity", "Commodity", "Inflation"]` |
| `MARGINED` | boolean | STRING | derived | R | | true when `CSA` is set and its `MARGIN_TYPE` includes VM | `true` |
| `CLEARED` | boolean | STRING | optional | R | | the set is a clearing account at a CCP (SA-CCR margined with MPOR 5 days) | `false` |
| `OPENED_ON` | date | doc | optional | – | | | `2015-12-10` |
| `STATUS` | text | STRING | optional | R | | `ACTIVE`, `CLOSED`; a closed set has no members | `ACTIVE` |
| `SOURCE_SYSTEM`, `BATCH_ID` | text, id | doc / STRING | optional / required | – | | the source and the load's run | `legal-docs`, `B-2026-09-30-01` |

**Removed from today's record (section 14):** `TRADE_COUNT`, `NET_MTM`, `COLLATERAL`, `EE_PEAK`, `PFE_PEAK`, `LIMIT`, `UTILISATION`,
`CVA`, `PROFILE[]`, `TRADES[]`, `BY_ASSET[]`, `TRADE_IDS`, and the result links `CREDIT_LIMIT`, `EXPOSURE_PROFILE`, `CVA_RESULT`,
`SACCR`, `SIMM`, `COLLATERAL_BALANCE` (the page opens the computed measures by scope instead).

### 3.3 `netting-set-member`: which trade is in which netting set (kept, new)

**Decision proposed:** membership is a fact of the legal and collateral systems (which agreement a trade was confirmed under), not a
by-product of pricing, so it gets **its own table**, one row per member trade. Today it is copied three times: `TRADES[]` on the
netting-set record (with each trade's MtM), `NETTING_SET` on the trade valuation, and the counterparty's `NETTING_SETS[]`. From 2.10.0
this table is the source of truth; `NETTING_SET` on `valuation` and on `exposure-path` rows is a denormalised copy checked against
it (§10).

**Grain:** one row per trade and netting set per business date. **Key:** `MEMBER_ID` = `<NETTING_SET_ID>~<TRADE_ID>`; natural key
(`BUSINESS_DATE`, `TRADE_ID`): **a trade is in at most one netting set** on a date. **Row count:** live trades with an agreement (test
data 12,556; a large bank about 1,000,000).

| Column | JSON | Delta | Req. | Engine | Meaning | Example |
|---|---|---|---|---|---|---|
| `MEMBER_ID` | id | `id` STRING | required | – | `<NETTING_SET_ID>~<TRADE_ID>` | `NS-ALDERSHOT-FRA~CLY-4000879` |
| `BUSINESS_DATE` | date | `business_date` DATE | required | R | | `2026-09-30` |
| `NETTING_SET` | id | STRING | required | R | link `netting-set` | `NS-ALDERSHOT-FRA` |
| `TRADE_ID` | id | STRING | required | R | link `trade` (text, even when all digits) | `CLY-4000879` |
| `SOURCE_SYSTEM` | text | STRING | required | R | the booking system (`TRADE_ID`s are unique per system) | `CALYPSO` |
| `COUNTERPARTY` | id | STRING | required | R | must equal the netting set's | `CP-ALDERSHOT` |
| `LEGAL_ENTITY` | id | STRING | required | R | the trade's booking legal entity; must equal the netting set's | `LE-FRA` |
| `FROM_DATE` | date | doc | optional | – | first day in the set (novation, allocation) | `2026-03-02` |
| `SPECIFIC_WWR` | boolean | STRING | optional | R | the bank has flagged the trade as specific wrong-way risk: it leaves the netting set for EAD (WWR.md) | `false` |

**Not members:** trades with status `MATURED` or `CANCELLED` (as the demo already applies, `tools/testdata/universe.py` `DEAD`), and
products no master agreement nets (cash, bonds held, loans, deposits): they are not in this table.

### 3.4 `agreement`: master agreements (kept, moved)

Unchanged from today's kind (`config/packs/counterparty-risk/contracts/agreement.contract.yaml`) except: moved to `risk/agreement/`,
`CLOSE_OUT_NETTING` becomes an enum (`ENFORCEABLE`, `NOT_ENFORCEABLE`, `UNDER_REVIEW`), and `NETTING_SETS` (id list, derived) is added
for navigation. The engines read `AGREEMENT_ID`, `COUNTERPARTY`, `CLOSE_OUT_NETTING`, `ELECTIONS.TERMINATION_CURRENCY` (the currency of
close-out, used by COLL for the FX haircut) and `ELECTIONS.SET_OFF`.

### 3.5 `csa`: credit support annex terms (kept, moved, made numeric)

**Grain:** one row per CSA per business date. **Key:** `CSA_ID`. Read by SACCR (threshold, MTA, NICA, MPOR), COLL (eligibility and
haircuts), MC (the call), EXP (collateral on each path) and CVA (via EXP). Today several terms are text (`MPOR: "10 business days"`,
`ELIGIBLE[].ASSET: "G7 government bonds 1-5Y"`) that no engine can apply: they become numbers and structured eligibility rules.

| Column | JSON | Delta | Req. | Engine | Unit | Sign | Meaning | Example |
|---|---|---|---|---|---|---|---|---|
| `CSA_ID` | id | `id` STRING | required | R | | | | `CSA-ALDERSHOT` |
| `BUSINESS_DATE` | date | `business_date` DATE | required | R | | | | `2026-09-30` |
| `AGREEMENT`, `COUNTERPARTY` | id | STRING | required | R | | | links | `AGR-ALDERSHOT-ISDA`, `CP-ALDERSHOT` |
| `MARGIN_TYPE` | text | STRING | required | R | | | `VM`, `IM`, `VM+IM` (today `VM`, `VM + IM`) | `VM` |
| `CSA_CURRENCY` | ccy | STRING | required | R | | | the base (valuation) currency of the CSA; amounts below are in it | `USD` |
| `THRESHOLD_US` | amount | DOUBLE | required | R | CSA ccy | ≥ 0 | exposure the counterparty may have to us before **we** must post | `10000000` |
| `THRESHOLD_THEM` | amount | DOUBLE | required | R | CSA ccy | ≥ 0 | exposure we may have to them before **they** must post | `10000000` |
| `MTA_US`, `MTA_THEM` | amount | DOUBLE | required | R | CSA ccy | ≥ 0 | minimum transfer amount for our deliveries, for theirs (today one `MTA`) | `500000`, `500000` |
| `INDEPENDENT_AMOUNT` | amount | DOUBLE | optional | R | CSA ccy | held + / posted − | independent amount (IA) in the bank's favour net of the bank's own | `0` |
| `ROUNDING` | amount | DOUBLE | optional | R | CSA ccy | ≥ 0 | rounding unit of a transfer; 0 none | `10000` |
| `ROUNDING_METHOD` | text | STRING | optional | R | | | `DELIVERY_UP_RETURN_DOWN` (ISDA default), `NEAREST`, `UP`, `DOWN` | `DELIVERY_UP_RETURN_DOWN` |
| `MARGIN_FREQUENCY_DAYS` | integer | DOUBLE | required | R | business days | | remargining period (today `FREQUENCY: Daily` → 1) | `1` |
| `MPOR_DAYS` | integer | DOUBLE | required | R | business days | | margin period of risk the bank assumes (today text) | `10` |
| `DISPUTES_LAST_2Q` | integer | DOUBLE | optional | R | | | margin disputes longer than the MPOR in the last two quarters (SA-CCR doubles the MPOR above 2) | `0` |
| `ILLIQUID_COLLATERAL` | boolean | STRING | optional | R | | | illiquid collateral or hard-to-replace trades in the set (MPOR floor 20 days) | `false` |
| `IM_MODEL` | text | STRING | optional | R | | | `SIMM`, `SCHEDULE`, `NONE` (IM is [SIMM.md](SIMM.md)'s) | `NONE` |
| `IM_THRESHOLD` | amount | doc | optional | R | CSA ccy | | IM threshold under the uncleared-margin rules | `50000000` |
| `SEGREGATED_IM` | boolean | doc | optional | R | | | IM held in a bankruptcy-remote account (excluded from NICA in SA-CCR) | `true` |
| `ELIGIBLE[]` | record list | doc | required | R | | | the eligibility schedule, below | |
| `NETTING_SETS`, `COLLATERAL_BALANCES` | id list | doc | derived | – | | | navigation | |

**`ELIGIBLE[]` rows** (first match wins; a holding that matches none is ineligible, COLL.md section 6):

| Field | Type | Req. | Meaning | Example |
|---|---|---|---|---|
| `RULE_ID` | id | required | | `G7-GOVT-1-5Y` |
| `ASSET_TYPE` | text | required | `CASH`, `GOVT_BOND`, `AGENCY_BOND`, `CORP_BOND`, `COVERED_BOND`, `EQUITY`, `GOLD` | `GOVT_BOND` |
| `CURRENCIES` | text list | optional | allowed currencies; empty: any | `["USD", "EUR", "GBP", "JPY", "CAD"]` |
| `ISSUER_COUNTRIES` | text list | optional | issuer countries (ISO alpha-2); `G7` expands to CA, DE, FR, GB, IT, JP, US | `["G7"]` |
| `MIN_RATING` | text | optional | lowest issuer rating (S&P scale) | `AA-` |
| `MIN_RESIDUAL_YEARS`, `MAX_RESIDUAL_YEARS` | rate | optional | residual maturity band [min, max) in years | `1`, `5` |
| `HAIRCUT` | rate | required | valuation percentage taken off the market value, 0 to 1 | `0.02` |
| `CONCENTRATION_LIMIT` | rate | optional | most of the CSA's collateral value one issuer may be, 0 to 1 | `0.25` |
| `APPLIES_TO` | text | optional | `VM`, `IM` or `BOTH` (default) | `BOTH` |

Example row (test data shape, numbers from today's sample):

```json
{"CSA_ID": "CSA-ALDERSHOT", "AGREEMENT": "AGR-ALDERSHOT-ISDA", "COUNTERPARTY": "CP-ALDERSHOT", "MARGIN_TYPE": "VM", "CSA_CURRENCY": "USD",
 "THRESHOLD_US": 10000000, "THRESHOLD_THEM": 10000000, "MTA_US": 500000, "MTA_THEM": 500000, "INDEPENDENT_AMOUNT": 0, "ROUNDING": 10000,
 "ROUNDING_METHOD": "DELIVERY_UP_RETURN_DOWN", "MARGIN_FREQUENCY_DAYS": 1, "MPOR_DAYS": 10, "IM_MODEL": "NONE",
 "ELIGIBLE": [{"RULE_ID": "CASH-G3", "ASSET_TYPE": "CASH", "CURRENCIES": ["USD", "EUR", "GBP"], "HAIRCUT": 0.0},
              {"RULE_ID": "G7-GOVT-0-1Y", "ASSET_TYPE": "GOVT_BOND", "ISSUER_COUNTRIES": ["G7"], "MAX_RESIDUAL_YEARS": 1, "HAIRCUT": 0.005},
              {"RULE_ID": "G7-GOVT-1-5Y", "ASSET_TYPE": "GOVT_BOND", "ISSUER_COUNTRIES": ["G7"], "MIN_RESIDUAL_YEARS": 1, "MAX_RESIDUAL_YEARS": 5, "HAIRCUT": 0.02},
              {"RULE_ID": "G7-GOVT-5-10Y", "ASSET_TYPE": "GOVT_BOND", "ISSUER_COUNTRIES": ["G7"], "MIN_RESIDUAL_YEARS": 5, "MAX_RESIDUAL_YEARS": 10, "HAIRCUT": 0.04}],
 "BATCH_ID": "B-2026-09-30-01"}
```

### 3.6 `valuation`: the trades' MtM (read; defined by [TVAL.md](TVAL.md))

The netting engine reads, per member trade and date: `TRADE_ID`, `SOURCE_SYSTEM`, `MTM` (USD, the bank's side, positive an asset),
`STATUS`, `NOTIONAL`, `BATCH_ID`, and the columns TVAL derives from the trade master (`TRADE_STATUS`, `PRODUCT_GROUP`, `PRODUCT_TYPE`,
`CURRENCY`, `MATURITY_DATE`, `TT_BOOK`, `DESK`, `LEGAL_ENTITY`, `COUNTERPARTY`, `NETTING_SET`). The column contract is
[TVAL.md section 3.2](TVAL.md#32-valuation-one-row-per-trade-per-business-date-planned); this contract adds one rule: **every member trade
must have a valuation on the date** (a missing one is counted as `unvalued` in provenance and the set's figures are marked incomplete,
never taken as 0).

**Alignment with TVAL.md and SIMM.md:** both derive a trade's netting set from `netting-set.TRADE_IDS` (TVAL.md section 4, SIMM.md
section 4). From 2.10.0 `TRADE_IDS` leaves the netting-set record and the membership is `netting-set-member` (§3.3): their derivation reads
that table instead (one change in each engine's loader; open question 9).

### 3.7 Cadence, due time and delivery

| Item | Contract |
|---|---|
| Cadence | once per business date; terms tables are full snapshots (the whole date is replaced, README.md section 6) |
| Due time | **to be agreed** (open question 1); it must precede the risk batch's valuations so that membership and MtM are of one date |
| Complete when | every `ACTIVE` netting set has a row; every live trade with an agreement has one membership row; every member has a valuation |
| One run | one `BATCH_ID` per date for the terms tables; the valuations carry their own (TVAL.md) |
| Load signal | `POST /api/v1/packs/counterparty-risk/loads` per table; **the signal reloads the date in every counterparty engine automatically** (product owner, 2026-10-25), so a restated date is never stale |
| Retention | 7 years in the lake (product owner, 2026-10-25, decided for inputs); Delta log and `VACUUM` retention set to keep time travel over that window |

## 4. Reference and master data read

| Master (kind, pack) | Key | Read for |
|---|---|---|
| `trade` (trading; ENDUR, CALYPSO, MUREX) | `TRADE_ID`, `SOURCE_SYSTEM` | live status, booking `LEGAL_ENTITY`, `COUNTERPARTY`, product, dates; the membership checks (§10) |
| `valuation` (market-risk, [TVAL.md](TVAL.md)) | `TRADE_ID` | MtM |
| `counterparty` (core) | `COUNTERPARTY_ID` | name, `LEI`, rating, sector, country, `GROUP`; the `CPTY:` and `GROUP:` scopes |
| `counterparty-group` (core; GRP.md) | `GROUP_ID` | the `GROUP:` scope's members |
| `lei-entity`, `lei-relationship` (gleif) | `LEI` | the connected group through parents (GRP.md) |
| `legal-entity` (core) | `LEGAL_ENTITY` | the `LE:` scope: the bank's legal entity, from the trade's booking `LEGAL_ENTITY` (product owner, 2026-10-25) |
| `collateral-balance`, `collateral-holding` (COLL.md) | `NETTING_SET`, `CSA` | the collateral figure |

## 5. Conventions specific to netting sets

| Convention | Rule |
|---|---|
| MtM sign | positive when the counterparty owes the bank (an asset), from the bank's side (README.md section 7) |
| Collateral sign | held positive, posted negative; VM and IM reported apart, never netted against each other |
| Exposure | current exposure ≥ 0; never nets across netting sets |
| Currency | USD for every figure; the trade valuations are USD (`MTM_CURRENCY`), collateral converted by COLL.md |
| Standalone | a trade in no netting set, or in a set whose `NETTING_ENFORCEABLE` is false, is its own netting set `NS-STANDALONE~<TRADE_ID>` |
| Rounding | API figures to whole USD; ratios unrounded |

## 6. The computation

### 6.1 Formulas

For a netting set *n* with member trades *i* and VM collateral *C_n* (COLL.md, haircut value, held − posted):

```
NET_MTM_n         = Σ_i MTM_i                                   additive across trades
GROSS_POSITIVE_n  = Σ_i max(MTM_i, 0)                           additive across trades
GROSS_NEGATIVE_n  = Σ_i min(MTM_i, 0)                           additive across trades
NETTING_BENEFIT_n = GROSS_POSITIVE_n − max(NET_MTM_n, 0)        ≥ 0
CE_UNCOLL_n       = max(NET_MTM_n, 0)                           not additive: per netting set
CE_n              = max(NET_MTM_n − C_n, 0)                     current exposure after VM collateral
NEG_EXPOSURE_n    = min(NET_MTM_n − C_n, 0)                     what we would owe them on close-out
NGR_n             = CE_UNCOLL_n / GROSS_POSITIVE_n              net-to-gross ratio (0 when GROSS_POSITIVE_n = 0)
```

For a scope *S* that is a union of whole netting sets (a counterparty, a group, a legal entity):

```
NET_MTM_S = Σ_n NET_MTM_n          C_S = Σ_n C_n          CE_S = Σ_n CE_n          (never max(Σ NET_MTM − Σ C, 0))
```

For a scope that cuts netting sets (a desk, a book, a basket of trades: `DESK:RATES & CPTY:CP-ALDERSHOT`), MtM sums are over the trades
in the scope, but **exposures are computed per netting set on the trades in the scope** and flagged `partial` (a desk's share of a netting
set is not a legal netting set: the figure answers *what if these were all the trades*). Collateral is not apportioned to partial sets by
default (`drishti.ccr.nset.partial-collateral: none | pro-rata-positive-mtm`, open question 4).

### 6.2 Parameters (planned settings)

| Setting | Default | Meaning |
|---|---|---|
| `drishti.ccr.enabled` | `true` | the counterparty engines and their APIs |
| `drishti.ccr.kinds.*` | `netting-set`, `netting-set-member`, `agreement`, `csa`, `valuation`, … | the kinds read (one key per input of the nine contracts) |
| `drishti.ccr.hot-dates` | `2` | business dates kept loaded (as VaR) |
| `drishti.ccr.live-statuses` | `[NEW, VALIDATED, AMENDED]` | trade statuses that count |
| `drishti.ccr.nset.standalone-when-not-enforceable` | `true` | §5 |
| `drishti.ccr.nset.partial-collateral` | `none` | §6.1 |
| `drishti.ccr.access` | `{}` | per role: counterparty coverage and level (§11) |

### 6.3 Where it runs

All of NSET is additive or a per-set `max`: **Java**, in the server (`drishti-compute`, package `com.ash.drishti.compute.ccr`, planned),
on the same in-memory date as the other counterparty measures (§7). The reactive connector `nset-engine` serves the computed kind with a
Java engine; no Python is needed. What-if (adding candidate trades) goes through the SA-CCR, EXP and LIM engines, which call this one.

## 7. What Drishti builds

### 7.1 One business date in memory (`CcrBook`, planned)

`CcrBook` is the counterparty analogue of `VarBook` (VAR_GUIDE.md section 25), shared by all nine measures, built once per date:

| Structure | Content | Size per 1,000,000 trades / 100,000 netting sets |
|---|---|---|
| trade ordinals | members sorted by `LEGAL_ENTITY`, `COUNTERPARTY`, `NETTING_SET`, `TRADE_ID`: each netting set and each counterparty is a contiguous run | 4 bytes × trades |
| trade columns | dictionary-coded `NETTING_SET`, `COUNTERPARTY`, `LEGAL_ENTITY`, `DESK`, `TT_BOOK`, `PRODUCT_GROUP`, `CURRENCY`; numbers `MTM`, `NOTIONAL`, maturity (epoch day) | about 60 MB |
| netting-set table | per set: run [from, to), counterparty code, legal entity, CSA terms (numbers), flags; aggregates `NET_MTM`, `GROSS_POSITIVE`, `GROSS_NEGATIVE`, VM held, VM posted, IM held, IM posted (float64) | about 100 bytes + 7 × 8 = 160 bytes a set: 16 MB |
| counterparty table | per counterparty: its netting-set run, group, LEI, rating; aggregates as above plus `CE` (Σ per set) | 2 MB for 10,000 counterparties |
| ordinal sets | `BitSet` over trades, as VaR (`OrdinalSet`): a scope resolves to trades, then to the netting sets it touches (whole or partial) | 125 KB per set |

Every per-set aggregate is a **sum**, so a counterparty's or a legal entity's figure is a sum over its runs, and a scope's exposure is a sum
of per-set `max` values: one pass over at most 100,000 sets (well under a millisecond in Java). Partial scopes sum the trades' MtM per touched
set from the trade columns.

### 7.2 On disk

Nothing beyond the lake: the netting-set figures are cheap to rebuild at load (a date of 1,000,000 trades loads in seconds). The
heavy per-date files of the counterparty measures are EXP's path matrices ([EXP.md section 7](EXP.md#7-what-drishti-builds)).

### 7.3 Caching

The reactive connector caches answers by (scope canonical text, date, data versions of the read kinds, view signature), at most 2,000
answers, as VaR (README.md section 11). A load signal of any read kind drops the date's answers and reloads it.

## 8. Scopes

The scope grammar is VaR's (VAR_GUIDE.md section 5): terms `BANK`, `LE:`, `DIV:`, `DESK:`, `BOOK:`, `NODE:`, `POS:`, `TRD:`, `PORT:`,
`BASKET:`, `CAPTURE:`, `WHERE:`, joined with `+`, `-` and `&`. Counterparty measures add or rely on:

| Term | Resolves to (counterparty measures) | Reads |
|---|---|---|
| `NSET:<id>[,<id>…]` | every member trade of the netting sets | `netting-set-member` |
| `CPTY:<id>[,<id>…]` | every member trade of the counterparty's netting sets, plus its standalone trades | `netting-set`, `netting-set-member`, `trade` |
| `GROUP:<id>` | `CPTY:` of every member of the connected group (GRP.md section 6) | `counterparty-group`, `counterparty`, `lei-relationship` |
| `LE:<id>` | the trades booked by the bank's legal entity: the trade's booking `LEGAL_ENTITY` is the source of truth (product owner, 2026-10-25) | `netting-set-member.LEGAL_ENTITY` |
| `CSA:<id>` | the trades of the netting sets the CSA secures (proposed: open question 3) | `netting-set` |
| `WHERE:` | position rules over trade columns, plus the counterparty columns `COUNTERPARTY_RATING`, `COUNTERPARTY_SECTOR`, `COUNTERPARTY_COUNTRY`, `NETTING_SET`, `MARGINED` (proposed) | |

**Additive and not:**

| Figure | Additive over | Rule |
|---|---|---|
| `NET_MTM`, `GROSS_POSITIVE`, `GROSS_NEGATIVE`, collateral | trades and netting sets | sum |
| `CE`, `CE_UNCOLL`, `NEG_EXPOSURE` | netting sets (not trades) | per set, then sum |
| `NGR`, `NETTING_BENEFIT` | no | ratio and difference at query time |

**Default parts** (the drill): a group → its counterparties; a counterparty → its netting sets; a legal entity → its counterparties; a
netting set → its trades; any other scope → the netting sets it touches.

## 9. What Drishti answers

### 9.1 The computed kind `nset-figures`

Served by the reactive connector `nset-engine` (§9.4). **Id:** the canonical scope text (`NSET:NS-ALDERSHOT-FRA`, `CPTY:CP-ALDERSHOT`).
The command `NSET <id> <GO>` with a netting-set id opens the kept `netting-set` record, whose page shows this kind for `NSET:<id>`.

| Field | Type | Unit | Meaning |
|---|---|---|---|
| `SCOPE`, `CANONICAL`, `SCOPE_KIND`, `LABEL` | text | | as VaR (`SCOPE_KIND`: `netting-set`, `counterparty`, `group`, `legal-entity`, `adhoc`) |
| `BUSINESS_DATE` | date | | |
| `NETTING_SETS`, `TRADES`, `PARTIAL_SETS` | integer | | counts in the scope |
| `NET_MTM`, `GROSS_POSITIVE`, `GROSS_NEGATIVE`, `NETTING_BENEFIT` | amount | USD | §6.1 |
| `VM_HELD`, `VM_POSTED`, `IM_HELD`, `IM_POSTED`, `COLLATERAL` | amount | USD | from COLL.md; `COLLATERAL` = VM held − VM posted |
| `CE`, `CE_UNCOLL`, `NEG_EXPOSURE` | amount | USD | §6.1 |
| `NGR` | rate | | net-to-gross |
| `BY_ASSET` | record list | | `ASSET` (product group), `TRADES`, `NET_MTM`, `GROSS_POSITIVE` |
| `BY_NETTING_SET` | record list | | per touched set: `NETTING_SET`, `COUNTERPARTY`, `LEGAL_ENTITY`, `CSA`, `MARGINED`, `TRADES`, `NET_MTM`, `COLLATERAL`, `CE`, `PARTIAL` |
| `MEASURES` | record | | links to the other measures at the same scope: `SACCR`, `EXP`, `LIM`, `CVA`, `COLL`, `MC`, `WWR` (scope texts) |
| `PROVENANCE` | record | | below |

**Provenance** (every counterparty answer; VaR's fields in UPPER_SNAKE plus): `INPUTS` (each read kind with its data version and
`BATCH_ID`), `UNVALUED` (members without a valuation), `UNMAPPED` (valuations naming a netting set the membership does not have),
`STANDALONE` (trades treated as their own set), `PARTIAL` (sets cut by the scope), `HIDDEN` (outside the viewer's entitlement).

### 9.2 The API (`/api/v1/nset`, planned)

| Method and path | Parameters | Answer |
|---|---|---|
| `GET /scope` | `scope`, `asOf` | the record of §9.1 as JSON (camelCase) |
| `GET /scope/contributions` | `scope`, `by` (`child` default, `netting-set`, `trade`, `asset`, `legal-entity`, `counterparty`), `asOf` | rows: `key`, `scope`, `label`, `kind`, `trades`, `netMtm`, `grossPositive`, `collateral`, `ce`, `partial` |
| `GET /scope/trades` | `scope`, `page`, `size` (≤ 1,000), `sort` (`mtm`, `-mtm`, `maturity`), `asOf` | the member trades, paged, no cap on the total: `tradeId`, `sourceSystem`, `nettingSet`, `product`, `assetClass`, `currency`, `book`, `notional`, `maturity`, `mtm`, `status` |
| `POST /captures`, `GET /captures`, `GET /captures/{id}`, `GET /captures/{id}/csv` | as VaR | captures of NSET answers (VAR_GUIDE.md section 6), kept in the content store under `ccr/captures/` |
| `GET /status`, `POST /reload` | | loaded dates, counts; reload (administrators) |

### 9.3 CSV

`GET /scope/trades?format=csv` streams every member trade (no cap); `GET /captures/{id}/csv` writes the `# field: value` header lines
then `netting_set,counterparty,legal_entity,trades,net_mtm,collateral,ce`.

### 9.4 The reactive connector (proposed file)

```yaml
# config/connectors/nset-engine.yaml                            (planned, 2.10.0)
plugin: reactive
mode: reactive
description: Netting-set figures for any scope (net MtM, gross, collateral, current exposure)
kinds:
  nset-figures:
    compute: nset-figures
    params:
      partial-collateral: { type: enum, options: [none, pro-rata-positive-mtm], default: none }
    result: { required: [NET_MTM, CE, NETTING_SETS], types: { NET_MTM: number, CE: number, NETTING_SETS: number } }
reads:                                         # as the viewer; the engine reads the date from CcrBook, these pin the versions
  - { kind: netting-set, as: sets, all: true }
  - { kind: netting-set-member, as: members, all: true }
  - { kind: valuation, as: valuations, all: true }
  - { kind: collateral-holding, as: holdings, all: true }
engine:
  type: java
  entry: com.ash.drishti.compute.ccr.NettingSetEngine
  version: "1.0"
triggers:
  - { on: data-version, kinds: [netting-set, netting-set-member, valuation, csa, collateral-holding], affects: all }
  - { on: write-back, kinds: [netting-set-member], affects: "NSET:{payload.NETTING_SET}" }
coalesce: { quiet: 250ms, max-wait: 2s }
cache: { results: 2000, ttl: 1d }
limits: { timeout: 30s, wait: 50ms, max-inflight-per-user: 4 }
```

The pack routes the kind (`routes: { nset-figures: nset-engine }`) and keeps `netting-set` routed to `risk-store`.

### 9.5 Published official results (`nset-official`, `nset-official-cpty`)

Every counterparty measure publishes its official end-of-day captures to the read-only results area of the lake
([README.md section 11](README.md#11-what-drishti-answers-computed-kinds-the-api-captures), product owner 2026-10-25; VaR's table in
[VAR_GUIDE.md section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results)). For counterparty measures the natural rows are not only hierarchy nodes, so this contract
proposes **two tables per measure**, the same for all nine counterparty contracts:

| Table | Rows per business date | Who may read the folder |
|---|---|---|
| `risk-results/<measure>-official/` | one per node of `MGMT` and `LEGAL` (the shared columns of README.md section 11): figures summed or computed over the node's trades | as any measure's results: aggregate figures by node, no counterparty named |
| `risk-results/<measure>-official-cpty/` | one per **netting set**, **counterparty** and **connected group** (proposed; open question 8) | **restricted**: counterparty-level figures name counterparties and their exposures (credit-sensitive, sometimes under information barriers); the storage grants the folder to credit risk, regulatory reporting and the warehouse's restricted zone only; restricted-list counterparties are never published there (they stay in the capture) |

The `-cpty` table has the shared columns with these meanings: `HIERARCHY` is `NSET`, `CPTY` or `GROUP`; `NODE_ID` the scope text
(`NSET:NS-ALDERSHOT-FRA`); `NODE_NAME` the netting set's or counterparty's name (subject to the `counterparty` kind's masks: a masked name
is published as the id); `PARENT_ID` the counterparty of a netting set, the group of a counterparty; `LEVEL` `netting-set`, `counterparty`
or `group`. It adds `COUNTERPARTY`, `LEI`, `LEGAL_ENTITY` (for a netting set) and `RATING` columns.

**NSET's figure columns** (both tables): `NET_MTM`, `GROSS_POSITIVE`, `GROSS_NEGATIVE`, `NETTING_BENEFIT`, `VM_HELD`, `VM_POSTED`,
`IM_HELD`, `IM_POSTED`, `COLLATERAL`, `CE`, `CE_UNCOLL`, `NEG_EXPOSURE` (DOUBLE, USD), `NGR` (DOUBLE), `NETTING_SETS`, `TRADES`,
`UNVALUED` (BIGINT). Node rows sum per-set figures over the node's netting sets (a node that cuts a netting set, a desk, publishes the
partial figures of §6.1 with `PARTIAL_SETS`).

## 10. Validation, errors and data quality

Adds to README.md section 10:

| Check | Rule | Outcome |
|---|---|---|
| One set per trade | a `TRADE_ID` (with `SOURCE_SYSTEM`) in two netting sets on one date | load fails (`DRS-5401`) |
| Same counterparty | `netting-set-member.COUNTERPARTY` = the set's `COUNTERPARTY` and = the trade's `COUNTERPARTY` | row kept, flagged, counted `mismatched` |
| Same legal entity | the member's `LEGAL_ENTITY` = the set's = the trade's booking `LEGAL_ENTITY` | row kept, flagged (the trade's wins for `LE:`) |
| Live member | the trade is live on the date (`drishti.ccr.live-statuses`, maturity on or after the date) | dead members ignored, counted |
| In product scope | the trade's `PRODUCT_GROUP` is in the set's `PRODUCT_SCOPE`; a non-derivative in a `DERIVATIVES` set | flagged `out-of-scope`; still netted unless `drishti.ccr.nset.exclude-out-of-scope` (open question 2) |
| Valued | every member has a `valuation` row on the date | counted `unvalued`; the set's answer is `incomplete` |
| Denormalised copies agree | `valuation.NETTING_SET` and `exposure-path.NETTING_SET` equal the membership | flagged; membership wins |
| Terms complete | a set with a `CSA` whose numeric terms are missing | set treated as unmargined, flagged |
| CSA link | `netting-set.CSA` exists and names the same `COUNTERPARTY` | flagged |

**Proposed DRS codes** (range 5400–5409, to be confirmed by the lead):

| HTTP | Code | When |
|---|---|---|
| 400 | `DRS-5400` | no or bad scope; unknown `by` |
| 409 | `DRS-5401` | the date's membership is inconsistent (a trade in two sets): the date is not loaded, the previous date answers with a warning |
| 404 | `DRS-1001` | no netting sets for the date |
| 403 | `DRS-5002`-style refusal | outside the viewer's counterparty coverage (§11); the shared code of the measure framework |
| 502 | `DRS-1003` | an input cannot be read |

## 11. Entitlements and audit

Counterparty data is sensitive (names, exposures, collateral, ratings; often under information barriers). Rules beyond VAR_GUIDE.md section 9:

| Rule | Proposed |
|---|---|
| Who may use NSET | a caller who may open `netting-set` and `valuation` |
| Coverage by counterparty | `drishti.ccr.access.<role>` lists counterparties, groups, legal entities, sectors or countries the role covers, each `trades` (figures, netting sets and trades), `aggregate` (figures only) or `none`; no entry: everything at `trades` |
| Restricted list | counterparties on the bank's restricted (wall-crossed) list are `none` for every role except those named; they are counted under `HIDDEN`, never named |
| Field masks | the `counterparty` kind's masks (`NAME`, `LEI`, `KYC`, `RATING`) apply in every answer, export and capture: a masked name shows the id only |
| Aggregate-only | a role at `aggregate` for a counterparty sees its figures in a legal-entity or bank answer but never its row in `BY_NETTING_SET` or the drill |
| Audit | `ccr-view` for every scope answer (user, scope, date, counts, hidden), `ccr-capture`, `ccr-export` (CSV of trades) |

## 12. Performance and sizing

| Operation | Target (p95, warm) |
|---|---|
| a netting set's figures | 5 ms |
| a counterparty with 50 netting sets | 10 ms |
| a legal entity (25,000 sets) | 50 ms |
| the bank (100,000 sets, 1,000,000 trades) | 150 ms |
| load a date (1,000,000 members, valuations, terms) | 20 s after reading |
| the trades of a netting set of 5,000 members, one page | 50 ms |

Memory: about 80 MB per date at a large bank (§7.1), shared by every counterparty measure.

## 13. Test data and generators

| Item | Plan |
|---|---|
| Generator | new package `tools/packgen/ccr/` (planned): `make_ccr_data.py` (entry, `--check` as VaR's), `terms.py` (agreements, CSAs, netting sets, membership), `paths.py` (EXP.md), `saccr.py`, `holdings.py`, `ccrmath.py` (the reference Python formulas the tests and the parity checks use). Deterministic: Python's `Random` seeded by text, gzip `mtime` 0, as `tools/packgen/var/make_var_data.py` |
| Trades | the committed trades of the trading pack, live on 2026-09-30, at most 10,000 (the local limit) |
| Netting sets | today's rule kept: one set per (counterparty, booking legal entity), `NS-<counterparty>-<LE short>`: 76 sets, 24 counterparties |
| Membership | only products a master agreement nets (derivatives in `DERIVATIVES` sets; repo and lending in separate `SFT` sets `NS-<cp>-<le>-SFT`); cash, bonds, loans and deposits are not members (today they are) |
| CSAs | numeric terms (§3.5); thresholds 0 for banks and hedge funds (as today), 10m otherwise; a few with IA, rounding 10,000, one CSA with a weekly frequency, one with disputes |
| Folders | `data/testdata/counterparty-risk/{agreement,csa,netting-set,netting-set-member}/cob-2026-09-30.jsonl.gz` (moved into the risk lake's domain by `lake demo`) |
| Tests | `tools/packgen/ccr/test_ccr_data.py`: every member is live, in one set, same counterparty and legal entity; sums reconcile; the CSA terms parse |

## 14. Retiring the stored version

### 14.1 Fields removed and how links behave

| Kind | Removed | Replaced by |
|---|---|---|
| `netting-set` | `NET_MTM`, `COLLATERAL`, `EE_PEAK`, `PFE_PEAK`, `UTILISATION`, `CVA`, `PROFILE[]`, `BY_ASSET[]` | `nset-figures`, `exposure-result` (EXP), `limit-usage` (LIM), `cva-result` (CVA) at `NSET:<id>` |
| `netting-set` | `TRADE_COUNT`, `TRADES[]`, `TRADE_IDS` | `netting-set-member`; the page's trade list is `GET /api/v1/nset/scope/trades` |
| `netting-set` | `LIMIT` (a copy of a limit, 5m to 15m, unrelated to the counterparty's `credit-limit`) | `credit-limit` rows (LIM.md), with an optional netting-set scope |
| `netting-set` | `CREDIT_LIMIT`, `EXPOSURE_PROFILE`, `CVA_RESULT`, `SACCR`, `SIMM`, `COLLATERAL_BALANCE` (links to stored results) | the page's measure links (`MEASURES` of §9.1) |
| `counterparty` (core) | `NET_MTM`, `PFE_PEAK`, `NETTING_SETS[].NET_MTM`, `NETTING_SETS[].TRADES` | `nset-figures` and `exposure-result` at `CPTY:<id>`; `NETTING_SETS[]` keeps `ID` and `AGREEMENT` (navigation) |

Old links keep working: `/v/netting-set/NS-ALDERSHOT-FRA` opens the kept record with its computed panels. The Sutra
`netting-set.v1.sutra.yaml` reads the computed kind; the Calc snippets that read the stored figures (`netting_collateral.py`,
`mtm_concentration.py`, `concentration_hhi.py` in `config/packs/counterparty-risk/python/`) read `nset-figures` instead.

**Guard test:** fails when `netting-set` or `counterparty` (or any input or fundamental kind) carries `NET_MTM`, `PFE_PEAK`, `EE_PEAK`,
`UTILISATION`, `CVA`, `EAD` (README.md section 14). The contract YAMLs already mark the netting-set figures `derived`, retiring in 2.10.0;
**this release of the contracts also marks `counterparty.NET_MTM`, `PFE_PEAK` and `NETTING_SETS[].NET_MTM` as derived, retiring in
2.10.0** (they were `required`, a classification that was clearly wrong: they are computed figures on a master).

### 14.2 Reconciliation (how the stored figures were made)

`tools/packgen/banking/data_results.py` (`credit_results`) builds every stored counterparty figure from the banking generator's trades,
and `tools/testdata/universe.py` re-runs it over the whole cloned book (amounts scaled by 0.2 to 5) for the test data:

| Stored figure | How it was made | Computed instead | Expected difference |
|---|---|---|---|
| `NET_MTM` | Σ members' `mtm` | Σ members' `MTM` from `valuation` | none for the same members and date; the test data then **walk** it daily (`_meta.walk`), so changes files drift from the members' sum |
| `COLLATERAL` | `NET_MTM × U(0.6, 0.95)` (or × U(0.5, 0.9) when negative), a random share | haircut value of the holdings (COLL.md) | different: the stored one ignores the CSA |
| `EE_PEAK`, `PFE_PEAK` | max of a closed-form heuristic profile (EXP.md section 14) | EXP.md from paths | different model |
| `UTILISATION` | `PFE_PEAK / LIMIT` with the set's own random `LIMIT` | LIM.md against `credit-limit` | different limit |
| `counterparty.PFE_PEAK` | the largest netting-set `PFE_PEAK` (max, not a sum) | EXP.md at `CPTY:`: the peak of the summed exposure paths | the stored one understates a counterparty with several sets |

**Inconsistencies found in today's test data (2026-10-25)**, explained by the walk of changes files and recorded for the release notes:
on `NS-ALDERSHOT-FRA` the stored `EE_PEAK` is 2,828,748 while its own `PROFILE[]` peaks at 3,244,739; `PFE_PEAK` is 7,246,659 while
`PROFILE[]` peaks at 8,430,769 and `UTILISATION` (0.561884 × 15m = 8.43m) uses the latter; its `exposure-profile` says 7,595,475 and
its CVA is −1,435 on the set, −1,276 on the `cva` record and −1,323 in that record's `COMPONENTS[]`. About 29% of the 12,556 members
(cash, bonds, notes, deposits, loans, commercial paper, warrants, physical commodity) are products no ISDA nets, a further 5% are repos
and securities loans (GMRA and GMSLA, not ISDA), and exchange-traded futures and listed options are cleared, not bilateral.

## 15. A worked example

One counterparty `CP-X`, two netting sets, USD. Set `NS-X-NY` is margined (CSA: VM held 6.0m after haircut); set `NS-X-LDN` is not.

| Trade | Set | MtM |
|---|---|---|
| T1 | NS-X-NY | +9.0m |
| T2 | NS-X-NY | −2.5m |
| T3 | NS-X-NY | +1.0m |
| T4 | NS-X-LDN | −4.0m |
| T5 | NS-X-LDN | +1.5m |

| Figure | NS-X-NY | NS-X-LDN | CPTY:CP-X |
|---|---|---|---|
| `NET_MTM` | 9.0 − 2.5 + 1.0 = **7.5m** | −4.0 + 1.5 = **−2.5m** | **5.0m** (sum) |
| `GROSS_POSITIVE` | 10.0m | 1.5m | 11.5m |
| `NETTING_BENEFIT` | 10.0 − 7.5 = 2.5m | 1.5 − 0 = 1.5m | 4.0m (sum of the sets'; never 11.5 − 5.0 = 6.5m) |
| `COLLATERAL` | +6.0m | 0 | 6.0m |
| `CE_UNCOLL` | 7.5m | 0 | **7.5m** (sum of per-set max; not max(5.0, 0) = 5.0m) |
| `CE` | max(7.5 − 6.0, 0) = **1.5m** | max(−2.5 − 0, 0) = **0** | **1.5m** |
| `NEG_EXPOSURE` | 0 | −2.5m | −2.5m |
| `NGR` | 7.5 / 10.0 = 0.75 | 0 / 1.5 = 0 | 7.5 / 11.5 = 0.652 |

A partial scope, `CPTY:CP-X & TRD:T1,T4` (*what if only these two*): T1 alone in `NS-X-NY` gives `CE_UNCOLL` 9.0m, T4 alone in `NS-X-LDN`
gives 0; `PARTIAL_SETS` = 2 and, with `partial-collateral: none`, `CE` = 9.0m.

## 16. Open questions and the change table

### 16.1 Open questions

| # | Question | Context |
|---|---|---|
| 1 | Due time of the terms and membership tables, and which system delivers membership (legal, collateral or the risk batch) | §3.7 |
| 2 | Non-derivatives in netting sets: the test data put cash, bonds, deposits, loans and commercial paper in ISDA netting sets. Exclude them from the membership (proposed), or keep and flag them? | §10, §13 |
| 3 | A `CSA:` scope word (margin calls and collateral are per CSA; one CSA can secure several sets) | §8 |
| 4 | Partial scopes (a desk's share of a netting set): collateral none (proposed) or pro rata to positive MtM? | §6.1 |
| 5 | SFTs: separate `SFT` netting sets (proposed) under GMRA/GMSLA, and are they in scope for the counterparty measures (SA-CCR excludes them; EXP and LIM may include them)? | §13, SACCR.md |
| 6 | Counterparty coverage model for entitlements: by counterparty, group, sector, country, legal entity, or all of them; and where the restricted list comes from | §11 |
| 7 | Retention: inputs 7 years (decided); captures of NSET answers the same? | §3.7 |
| 8 | Published counterparty-level official results: a separate restricted `<measure>-official-cpty` table (proposed) or counterparty rows in the one `<measure>-official` table; and which levels (netting set, counterparty, group) | §9.5 |
| 9 | TVAL and SIMM read membership from `netting-set.TRADE_IDS` (2.9.0); move both to `netting-set-member` in 2.10.0 (proposed), or keep `TRADE_IDS` on the netting-set record as a derived list? | §3.6 |

### 16.2 The change table

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-25 | First version, PLANNED for 2.10.0: terms and membership kept, figures computed; product owner's decisions of 2026-10-25 applied (load signal reloads the engines, inputs kept 7 years, `LE:` from the trade's booking legal entity, published official results with a restricted counterparty-level table) |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
