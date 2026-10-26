<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# MC: margin calls, the data contract

*Version 1.0 · 2026-10-25 · Shared rules: [README.md](README.md) · Model contract: [VAR_GUIDE.md Part II](../guides/VAR_GUIDE.md#14-the-contract-at-a-glance) · CSAs and netting sets:
[NSET.md](NSET.md) · Collateral values: [COLL.md](COLL.md)*

**Status:** proposed. PLANNED for 2.10.0, not built. Every table, column, setting, kind, endpoint and code below is a proposal; names
may change before the release, and each change will be recorded in [section 16](#16-open-questions-and-the-change-table).

This is the contract of **margin calls**: Drishti keeps the **actual calls** and their events (a workflow the collateral system owns)
and computes the **expected call** of each CSA from the netting set's MtM, the collateral balance and the CSA's terms, shown beside the
actual call with the difference explained. Variation margin is computed here; initial margin comes from ISDA SIMM, whose contract is
[SIMM.md](SIMM.md) (written beside this one).

## Contents

1. [Summary](#1-summary)
2. [The measure in one page](#2-the-measure-in-one-page)
3. [Inputs from upstream engines](#3-inputs-from-upstream-engines)
4. [Reference and master data read](#4-reference-and-master-data-read)
5. [Conventions specific to margin calls](#5-conventions-specific-to-margin-calls)
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

**Commands.** `MC <call id> <GO>` opens an actual call with its expected call beside it; `MC CSA CSA-ALDERSHOT <GO>`, `MC CPTY CP-ALDERSHOT
<GO>`, `MC LE LE-NY <GO>` show every CSA's expected call and the day's actual calls; the API is `/api/v1/margin`.

**Kept and computed.** The `margin-call` kind stays (fundamental workflow); no stored field is the expected call today, so nothing is
removed from it; it moves to the risk lake and its text values become enums (§3.2). The expected call is new and computed.

| Part | Status |
|---|---|
| `margin-call` moved to the risk lake, enums, amounts signed by direction | PLANNED |
| Expected VM call per CSA (credit support amount, delivery and return amounts, thresholds, MTA, IA, rounding) | PLANNED |
| Expected IM call from SIMM ([SIMM.md](SIMM.md)) with the IM threshold | PLANNED |
| Actual against expected: match, difference, not called, unexpected, direction mismatch; dispute analysis | PLANNED |
| Computed kind `margin-expected`, reactive connector `mc-engine` (Python) | PLANNED |

## 2. The measure in one page

**What it answers:** *what should each CSA call today, and does the actual call agree?* For collateral operations (which calls to make or
expect, which disputes are about valuation), for credit risk (under-collateralised sets), and for audit.

**In words (ISDA CSA, VM):** the **credit support amount** is the exposure (the netting set's MtM, the bank's side) plus independent
amounts, less the counterparty's threshold when positive (or plus the bank's threshold when negative), floored at zero on each side. The
difference between it and the **collateral balance** (COLL.md's contractual value, held − posted) is what must move: a **delivery amount**
when the receiving side's collateral must rise, a **return amount** when it must fall. Nothing moves below the minimum transfer amount;
amounts are rounded, deliveries up and returns down. IM: the SIMM requirement less the IM threshold, against the IM held.

## 3. Inputs from upstream engines

### 3.1 Where they live

```
${DRISHTI_DELTA_ROOT}/risk/
├── margin-call/          business_date=YYYY-MM-DD/   one row per call (kept; moved from collateral/margin-call)
├── csa/, netting-set/, netting-set-member/           NSET.md
├── collateral-holding/                               COLL.md
└── valuation/                                  TVAL.md
```

Source: the collateral management system, intraday as calls are issued and at the end of day (due **to be agreed**, open question 1).
Load signal reloads the date (product owner, 2026-10-25); kept 7 years.

### 3.2 `margin-call`: the actual calls (kept)

**Grain:** one row per call per business date (the call's latest state on that date). **Key:** `CALL_ID`.

| Column | JSON | Delta | Req. | Engine | Unit | Meaning | Example |
|---|---|---|---|---|---|---|---|
| `CALL_ID` | id | `id` STRING | required | R | | | `MC-ALDERSHOT-NY-1` |
| `BUSINESS_DATE` | date | `business_date` DATE | required | R | | | `2026-09-30` |
| `CALL_DATE` | date | STRING | required | R | | when the call was issued | `2026-09-30` |
| `VALUATION_DATE` | date | STRING | optional | R | | the close the call valued (normally the previous business day) | `2026-09-29` |
| `CSA`, `NETTING_SET`, `COUNTERPARTY` | id | STRING | required | R | | links | `CSA-ALDERSHOT`, `NS-ALDERSHOT-NY`, `CP-ALDERSHOT` |
| `MARGIN_TYPE` | text | STRING | required | R | | `VM` or `IM` (today `Variation`) | `VM` |
| `DIRECTION` | text | STRING | required | R | | `WE_CALL` (the counterparty must transfer to us) or `THEY_CALL` (today `We call`, `They call`) | `WE_CALL` |
| `CALL_TYPE` | text | STRING | optional | R | | `DELIVERY` or `RETURN` | `DELIVERY` |
| `AMOUNT` | amount | DOUBLE | required | R | `CURRENCY` | the amount called, ≥ 0 | `1450000` |
| `CURRENCY` | ccy | STRING | required | R | | | `USD` |
| `AGREED_AMOUNT` | amount | DOUBLE | optional | R | | the undisputed part | `1450000` |
| `DISPUTE_AMOUNT` | amount | DOUBLE | optional | R | | | `0` |
| `COUNTERPARTY_EXPOSURE` | amount | DOUBLE | optional | R | USD | the counterparty's valuation of the exposure, in a dispute | `11800000` |
| `STATUS` | text | STRING | required | R | | `ISSUED`, `AGREED`, `PARTIALLY_AGREED`, `DISPUTED`, `SETTLED`, `CANCELLED` | `AGREED` |
| `SETTLEMENT_DATE` | date | STRING | optional | – | | | `2026-10-01` |
| `EVENTS[]` | record list | doc | required | – | | `AT` (timestamp), `EVENT`, `BY` | |
| `BATCH_ID` | id | STRING | required | – | | | `B-2026-09-30-01` |

## 4. Reference and master data read

| Kind | Read for |
|---|---|
| `csa` (NSET.md) | `THRESHOLD_US`, `THRESHOLD_THEM`, `MTA_US`, `MTA_THEM`, `INDEPENDENT_AMOUNT`, `ROUNDING`, `ROUNDING_METHOD`, `MARGIN_FREQUENCY_DAYS`, `IM_THRESHOLD`, `CSA_CURRENCY` |
| `valuation` ([TVAL.md](TVAL.md)) | the netting set's MtM at the valuation date |
| `coll-valuation` (COLL.md) | VM and IM balances (contractual values) at the valuation date, plus pending transfers |
| `simm-margin` ([SIMM.md](SIMM.md)) | `IM_COLLECT` and `IM_POST` per netting set |
| `fx-spot` | the CSA currency |

## 5. Conventions specific to margin calls

| Convention | Rule |
|---|---|
| Exposure | the netting set's MtM from the bank's side, in the CSA currency; positive: the counterparty owes the bank |
| Balance | VM held − VM posted (contractual values, COLL.md), including transfers pending settlement |
| Amounts | the call's `AMOUNT` ≥ 0 with `DIRECTION`; Drishti's signed form: **+** when the counterparty transfers to the bank (we call), **−** when the bank transfers |
| Valuation date | the expected call for a call date D uses the valuation of D − `valuation-lag-days` (default 1) |
| Tolerance | an actual call matches when \|actual − expected\| ≤ `tolerance` (default: the larger of one rounding unit and 0.5% of the expected) |

## 6. The computation

### 6.1 Parameters (`drishti.ccr.margin.*`)

| Setting | Default | Meaning |
|---|---|---|
| `valuation-lag-days` | `1` | §5 |
| `tolerance-rel`, `tolerance-rounding-units` | `0.005`, `1` | §5 |
| `combined-mta` | `false` | under the uncleared-margin rules VM and IM share one MTA (true: the MTA applies to VM + IM together) |
| `im-source` | `simm` | `simm` ([SIMM.md](SIMM.md)), `none` |

### 6.2 Expected VM call

With E the exposure, IA the net independent amount in the bank's favour, B the balance, TH and MTA the CSA's (USD):

```
CSA_AMOUNT = max(E + IA − TH_them, 0)          when E + IA ≥ 0       (what the bank should hold)
CSA_AMOUNT = min(E + IA + TH_us, 0)            when E + IA < 0       (minus what the bank should have posted)
Δ          = CSA_AMOUNT − B                     signed: + the counterparty must transfer, − the bank must transfer

Split Δ into the two legs of the CSA:
  if Δ > 0:  RETURN to us   = min(Δ, max(−B, 0))          (they return collateral we posted)       rounded DOWN
             DELIVERY to us = Δ − RETURN                   (they deliver new collateral)            rounded UP
  if Δ < 0:  RETURN by us   = min(−Δ, max(B, 0))           (we return collateral we hold)           rounded DOWN
             DELIVERY by us = −Δ − RETURN                  (we deliver)                             rounded UP
EXPECTED   = the legs, each set to 0 when their total is below the transferor's MTA (MTA_them for Δ > 0, MTA_us for Δ < 0)
```

Rounding to `ROUNDING` with `ROUNDING_METHOD` (`DELIVERY_UP_RETURN_DOWN` as above; `NEAREST`, `UP`, `DOWN` apply to both legs).

### 6.3 Expected IM call

```
IM_REQUIRED_THEM = max(IM_COLLECT − IM_THRESHOLD, 0)        (simm-margin's IM_COLLECT, SIMM.md section 9)
IM_CALL          = IM_REQUIRED_THEM − IM_HELD                     + : we call;  − : we return IM
IM_REQUIRED_US   = max(IM_POST − IM_THRESHOLD, 0);  expected IM the counterparty calls = IM_REQUIRED_US − IM_POSTED
MTA: the CSA's, alone or combined with VM (combined-mta); rounding as VM
```

### 6.4 Actual against expected

| Status | Rule |
|---|---|
| `MATCH` | an actual call on the CSA and margin type, the same direction, \|actual − expected\| ≤ tolerance |
| `DIFFERENCE` | same direction, outside tolerance: `DIFFERENCE` = actual − expected, with the likely cause: valuation (MtM differs from the counterparty's `COUNTERPARTY_EXPOSURE`), balance (pending transfers), terms (threshold or MTA) |
| `NOT_CALLED` | expected ≥ MTA, no actual call |
| `UNEXPECTED` | an actual call when the expected is 0 (below MTA or nothing due) |
| `DIRECTION_MISMATCH` | actual and expected in opposite directions |
| `DISPUTED` | the call's status is `DISPUTED`: the dispute amount is compared with E − `COUNTERPARTY_EXPOSURE` |

### 6.5 Aggregation and where it runs

Expected and actual amounts are additive across CSAs (signed); counts and statuses are counted. Per CSA the inputs are a handful of
numbers, so the engine is **Python** (`drishti_ccr.margin:MarginEngine`), vectorised over every CSA of the scope (a large bank: about
20,000 CSAs), fed by Java with each CSA's E, B, terms and IM figures as one Arrow table.

## 7. What Drishti builds

Per CSA and date: E, IA, B (VM), IM held and posted, terms (about 20 doubles: 160 bytes; 20,000 CSAs 3 MB); per call its matched expected
call. No disk structure; answers cached as NSET's.

## 8. Scopes

`CSA:` (proposed, NSET.md section 8), `NSET:`, `CPTY:`, `GROUP:`, `LE:`: the CSAs of the netting sets covered. Default parts: a counterparty →
its CSAs → the day's calls.

## 9. What Drishti answers

### 9.1 The computed kind `margin-expected`

| Field | Type | Unit | Meaning |
|---|---|---|---|
| `SCOPE`, `CANONICAL`, `SCOPE_KIND`, `LABEL`, `BUSINESS_DATE`, `VALUATION_DATE` | | | |
| `CSAS` | integer | | CSAs in the scope |
| `EXPECTED_WE_CALL`, `EXPECTED_THEY_CALL` | amount | USD | sums of expected VM legs by direction |
| `ACTUAL_WE_CALL`, `ACTUAL_THEY_CALL` | amount | USD | sums of the day's actual VM calls |
| `EXPECTED_IM_WE_CALL`, `EXPECTED_IM_THEY_CALL`, `ACTUAL_IM_WE_CALL`, `ACTUAL_IM_THEY_CALL` | amount | USD | |
| `MATCHED`, `DIFFERENCES`, `NOT_CALLED`, `UNEXPECTED`, `DIRECTION_MISMATCH`, `DISPUTED` | integer | | counts |
| `BY_CSA` | record list | | `CSA`, `NETTING_SETS`, `EXPOSURE`, `IA`, `BALANCE`, `THRESHOLD`, `MTA`, `CSA_AMOUNT`, `DELTA`, `DELIVERY`, `RETURN`, `EXPECTED`, `DIRECTION`, `IM_REQUIRED`, `IM_HELD`, `IM_EXPECTED`, `ACTUAL_CALLS` (ids), `ACTUAL`, `STATUS`, `DIFFERENCE`, `CAUSE` |
| `PROVENANCE` | record | | NSET's, plus the valuation date used and the SIMM run |

The `margin-call` page (`MC <call id>`) shows its call with the `BY_CSA` row of its CSA.

### 9.2 The API (`/api/v1/margin`, planned)

| Method and path | Parameters | Answer |
|---|---|---|
| `GET /scope` | `scope`, `asOf`, `valuationDate` | §9.1 |
| `GET /calls/{callId}/expected` | | the call, its CSA's expected call and status |
| `POST /what-if` | `{csa, exposure?, balance?, terms?}` | the expected call under the changed inputs (a threshold change, a pending transfer) |
| captures, status, reload | as NSET | |

CSV: `GET /scope?format=csv` writes `BY_CSA`.

### 9.3 Published official results (`margin-official`, `margin-official-cpty`)

The two tables of [NSET.md section 9.5](NSET.md#95-published-official-results-nset-official-nset-official-cpty), with the shared columns
and `EXPECTED_WE_CALL`, `EXPECTED_THEY_CALL`, `ACTUAL_WE_CALL`, `ACTUAL_THEY_CALL`, the four IM equivalents (DOUBLE, USD), `CSAS`,
`MATCHED`, `DIFFERENCES`, `NOT_CALLED`, `UNEXPECTED`, `DIRECTION_MISMATCH`, `DISPUTED` (BIGINT). The `-cpty` table's natural row is the
**CSA** (row level `csa`), plus counterparty and group rows.

### 9.4 The reactive connector and the engine (proposed)

```yaml
# config/connectors/mc-engine.yaml                              (planned, 2.10.0)
plugin: reactive
mode: reactive
description: Expected margin calls (VM from the CSA, IM from SIMM) beside the actual calls
kinds:
  margin-expected:
    compute: margin
    params:
      valuation-date: { type: date }                   # empty: the business date minus valuation-lag-days
    result: { required: [CSAS, EXPECTED_WE_CALL, EXPECTED_THEY_CALL] }
reads:
  - { kind: margin-call, as: calls, all: true }
  - { kind: csa, as: csas, all: true }
  - { kind: nset-figures, as: sets, id: "{id}" }          # E per netting set (NSET.md)
  - { kind: coll-valuation, as: collateral, id: "{id}" }  # balances (COLL.md)
  - { kind: simm-margin, as: im, id: "{id}" }             # IM_COLLECT, IM_POST (SIMM.md)
engine:
  type: python
  entry: drishti_ccr.margin:MarginEngine
  pool: risk
  version: "1.0"
triggers:
  - { on: data-version, kinds: [margin-call, csa, valuation, collateral-holding], affects: all }
  - { on: write-back, kinds: [margin-call], affects: "CSA:{payload.CSA}" }
cache: { results: 2000, ttl: 1d }
limits: { timeout: 20s, wait: 50ms, max-inflight-per-user: 4 }
requires: { python: { version: "3.12", lock: requires/drishti-ccr.lock } }
```

```python
# compute/drishti-ccr/src/drishti_ccr/margin.py                 (planned, 2.10.0)
class MarginEngine(Engine):
    kinds = ("margin-expected",)
    def compute(self, req, inputs, progress):
        csa = inputs.arrow("csa_state")   # CSA, E, IA, B, TH_US, TH_THEM, MTA_US, MTA_THEM, ROUNDING, ROUNDING_METHOD,
                                          # IM_REQ_THEM, IM_REQ_US, IM_HELD, IM_POSTED, IM_THRESHOLD   (USD, float64)
        calls = inputs.arrow("calls")     # CALL_ID, CSA, MARGIN_TYPE, DIRECTION, AMOUNT (USD), STATUS, COUNTERPARTY_EXPOSURE
        ...                               # sections 6.2 to 6.4
        return Result(rows=[totals], tables={"by_csa": by_csa})
```

## 10. Validation, errors and data quality

| Check | Rule | Outcome |
|---|---|---|
| Enums | `MARGIN_TYPE`, `DIRECTION`, `CALL_TYPE`, `STATUS` | row rejected |
| Amount | ≥ 0 | row rejected |
| Links | `CSA` exists; `NETTING_SET` secured by it | flagged |
| Valuation | the netting set has valuations at the valuation date | expected call `unknown`, status `NO_VALUATION` |
| Terms | the CSA's numeric terms present | `unknown`, flagged |
| Duplicates | two open calls on one CSA, margin type and direction on one date | both kept, flagged |

**Proposed DRS codes** (5430–5439): `DRS-5430` (400, bad scope or what-if), `DRS-5431` (404, a call id unknown on the date).

## 11. Entitlements and audit

As NSET.md section 11; calls and their events are visible at `trades` level for the counterparty; `ccr-view` audited.

## 12. Performance and sizing

A CSA: 10 ms; a counterparty: 15 ms; the bank (20,000 CSAs): 300 ms; date load: seconds (the inputs are other measures' aggregates).

## 13. Test data and generators

`tools/packgen/ccr/terms.py` and `holdings.py` (planned) write the calls of the business date and the previous one **from the expected
call** of each CSA, then perturb a few on purpose: one matching, one rounded differently, one disputed with a `COUNTERPARTY_EXPOSURE`,
one missed (`NOT_CALLED`), one below MTA (`UNEXPECTED`), one IM call on a `VM+IM` CSA. Files `data/testdata/counterparty-risk/margin-call/`.

## 14. Retiring the stored version

Nothing is retired: today no stored field is the expected call (`tools/contracts.policy.yaml`, `margin-call` figures `[]`). The kind
moves to the risk lake; `MARGIN_TYPE` `Variation` → `VM`, `DIRECTION` `We call` / `They call` → `WE_CALL` / `THEY_CALL`, `STATUS`
upper-cased; ids unchanged. The Calc snippet `margin_calls.py` reads `margin-expected` for the comparison.

**Reconciliation: how the stored calls were made.** `credit_results` makes two calls per netting set with `AMOUNT = |net MtM| ×
U(0.02, 0.08)` rounded to 1,000 and the direction from the sign of the net MtM, ignoring the CSA. Against the CSA:
- sample `MC-ALDERSHOT-FRA-1` (2026-09-30): *They call 18,000*. The set's MtM is −339,550 and the bank has posted 229,552 (B = −229,552)
  under thresholds of 10m: CSA_AMOUNT = min(−339,550 + 10,000,000, 0) = 0, Δ = +229,552 (a return of our collateral) below the 500,000
  MTA: **expected nothing**; status `UNEXPECTED` (and the stored direction is the wrong way round);
- test data's `MC-ALDERSHOT-FRA-1`: *We call 366,000*, with MtM 7.76m and 5.15m held: CSA_AMOUNT = max(7.76m − 10m, 0) = 0, Δ = −5.15m:
  **expected: the bank returns 5.15m**; status `DIRECTION_MISMATCH`.
The generator of §13 makes calls consistent with the CSA, so the demo shows mostly matches and a few explained exceptions.

## 15. A worked example

CSA: TH_them = 2,000,000, TH_us = 2,000,000, MTA 500,000 both, IA 0, rounding 100,000 (`DELIVERY_UP_RETURN_DOWN`). Balance B = +9,000,000
held (contractual value).

| Case | E | CSA_AMOUNT | Δ = CSA_AMOUNT − B | Legs | Expected |
|---|---|---|---|---|---|
| A | 12,430,000 | 12,430,000 − 2,000,000 = 10,430,000 | +1,430,000 | delivery to us 1,430,000 ≥ MTA, rounded **up** | **we call 1,500,000** |
| B | 11,300,000 | 9,300,000 | +300,000 | 300,000 < MTA 500,000 | **nothing** |
| C | 9,500,000 | 7,500,000 | −1,500,000 | return by us min(1.5m, 9.0m) = 1,500,000, rounded **down** | **they call 1,500,000** (a return) |
| D | −1,000,000 | min(−1,000,000 + 2,000,000, 0) = 0 | −9,000,000 | return by us 9,000,000 | **they call 9,000,000** |

**Against actual calls:** case A with an actual *We call 1,450,000*: difference −50,000, tolerance max(100,000 × 1, 0.5% × 1,500,000 =
7,500) = 100,000 → **`MATCH`** (the counterparty rounded to the nearest 50,000). Case B with an actual *We call 300,000* → **`UNEXPECTED`**
(below the MTA).

**IM:** SIMM IM the counterparty must post 60,000,000, IM threshold 50,000,000 → required 10,000,000; IM held 8,200,000 → expected **we
call 1,800,000** (rounded up: 1,800,000).

## 16. Open questions and the change table

### 16.1 Open questions

| # | Question | Context |
|---|---|---|
| 1 | Due time and frequency of the calls feed (intraday as issued, or end of day) | §3.1 |
| 2 | Valuation lag: calls on D value at D − 1 (proposed) for every CSA, or per CSA (`VALUATION_DATE` on the call)? | §5 |
| 3 | Combined VM + IM MTA under the uncleared-margin rules: per CSA flag or a setting? | §6.1 |
| 4 | Should a `NOT_CALLED` or `DIRECTION_MISMATCH` raise an alert (ALERTS) to collateral operations? | §6.4 |

### 16.2 The change table

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-25 | First version, PLANNED for 2.10.0; product owner's decisions of 2026-10-25 applied (load signal reloads the engine, inputs kept 7 years, published official results) |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
