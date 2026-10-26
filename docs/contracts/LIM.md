<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# LIM: credit limits and their usage, the data contract

*Version 1.0 · 2026-10-25 · Shared rules: [README.md](README.md) · Model contract: [VAR.md](VAR.md) · Netting: [NSET.md](NSET.md) ·
Usage measures: [EXP.md](EXP.md), [SACCR.md](SACCR.md) · Groups: [GRP.md](GRP.md)*

**Status:** proposed. PLANNED for 2.10.0, not built. Every table, column, setting, kind, endpoint and code below is a proposal; names
may change before the release, and each change will be recorded in [section 16](#16-open-questions-and-the-change-table).

This is the contract of **credit limits**: Drishti keeps the limits the credit committee approves (the amount, the measure, the tenor
buckets, the approval and review) and computes their **usage** when asked (from EXP's PFE profile, SA-CCR's EAD, the netting sets' MtM or
settlement flows), the utilisation, the status, the headroom, and the **pre-deal check** of a candidate trade.

## Contents

1. [Summary](#1-summary)
2. [The measure in one page](#2-the-measure-in-one-page)
3. [Inputs from upstream engines](#3-inputs-from-upstream-engines)
4. [Reference and master data read](#4-reference-and-master-data-read)
5. [Conventions specific to limits](#5-conventions-specific-to-limits)
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

**Commands.** `LIM <limit id> <GO>` opens a limit with its usage; `LIM CPTY CP-ALDERSHOT <GO>` every limit on a counterparty;
`LIM LE LE-NY <GO>` the limits of a legal entity's counterparties with the breaches first. The API is `/api/v1/limits`.

**Kept and computed.** The `credit-limit` kind stays; removed: `USED`, `UTILISATION`, `STATUS`, `BUCKETS[].USED`,
`BUCKETS[].UTILISATION` (computed). A limit gains a **scope** (any scope text: a counterparty, a group, a netting set, a legal entity's
share of a counterparty) and a structured measure.

| Part | Status |
|---|---|
| `credit-limit` moved to the risk lake, with `SCOPE`, `MEASURE` enum, numeric tenor buckets, warning level, temporary excesses | PLANNED |
| Usage per measure (PFE peak by tenor bucket, EE peak, EEPE, SA-CCR EAD, net MtM, current exposure, notional, settlement), utilisation, status, headroom | PLANNED |
| Pre-deal check: approve, refer or decline a candidate trade against every limit it touches | PLANNED |
| Computed kind `limit-usage`, reactive connector `lim-engine` (Python, composing EXP and SACCR) | PLANNED |

## 2. The measure in one page

**What it answers:** *how much of each approved limit is used, where is it closest to breaching, and can we do this trade?* For credit
officers, the front office (pre-deal), and limit monitoring (breaches and early warnings every morning).

**In words:** each limit names a **scope** and a **measure**. Usage is the measure computed for the scope: for a PFE limit, the peak of
the scope's PFE profile in each tenor bucket (from [EXP.md](EXP.md), where exposures of several netting sets add per path before the
quantile is taken); for an EAD limit, the SA-CCR EAD ([SACCR.md](SACCR.md)); for a settlement limit, the largest amount due from the
counterparty on one settlement day. **Utilisation** = usage / limit, per bucket; the limit's status is that of its worst bucket:
`OK` below the warning level (80%), `WARNING` from it, `BREACH` above 100%, `EXCESS_APPROVED` within an approved temporary excess.
**Headroom** = limit − usage. The **pre-deal check** recomputes usage with the candidate added and answers approve, refer or decline.

## 3. Inputs from upstream engines

### 3.1 Where it lives

`${DRISHTI_DELTA_ROOT}/risk/credit-limit/business_date=YYYY-MM-DD/`, one row per limit (moved from `credit/credit-limit`). Source: the
credit limits system; daily snapshot; due **to be agreed** (open question 1); load signal reloads the date in the engine (product owner,
2026-10-25); kept 7 years.

### 3.2 `credit-limit`: the approved limits (kept)

| Column | JSON | Delta | Req. | Engine | Unit | Meaning | Example |
|---|---|---|---|---|---|---|---|
| `LIMIT_ID` | id | `id` STRING | required | R | | | `LIM-ALDERSHOT` |
| `BUSINESS_DATE` | date | `business_date` DATE | required | R | | | `2026-09-30` |
| `SCOPE` | text | STRING | required | R | | the scope text the limit applies to (NSET.md section 8); today's rows: `CPTY:<COUNTERPARTY>` | `CPTY:CP-ALDERSHOT` |
| `COUNTERPARTY` | id | STRING | optional | R | | the counterparty when the scope is one (navigation, masks) | `CP-ALDERSHOT` |
| `GROUP` | id | STRING | optional | R | | the group, for a group limit | |
| `PARENT_LIMIT` | id | STRING | optional | R | | the limit this one sub-allocates (a counterparty limit under a group limit) | `LIM-GRP-ALDERSHOT` |
| `MEASURE` | text | STRING | required | R | | `PFE`, `EE_PEAK`, `EEPE`, `SACCR_EAD`, `NET_MTM`, `CE`, `NOTIONAL`, `SETTLEMENT` (today the text `PFE 95 peak`) | `PFE` |
| `CONFIDENCE` | rate | DOUBLE | optional | R | | the PFE's q; default `drishti.ccr.exposure.limit-confidence` | `0.95` |
| `LIMIT` | amount | DOUBLE | required | R | `CURRENCY` | the overall limit | `337000000` |
| `CURRENCY` | ccy | STRING | required | R | | | `USD` |
| `BUCKETS[]` | record list | doc | optional | R | | tenor buckets: `BUCKET` (label), `FROM_YEARS`, `TO_YEARS` (a grid date t belongs when FROM < t ≤ TO; the first bucket also holds t₀), `LIMIT`; for `SETTLEMENT`, `FROM_DAYS`, `TO_DAYS` | `{"BUCKET": "1-3Y", "FROM_YEARS": 1, "TO_YEARS": 3, "LIMIT": 337000000}` |
| `WARNING_LEVEL` | rate | DOUBLE | optional | R | | utilisation at which the status is `WARNING` (default 0.8) | `0.8` |
| `LIMIT_TYPE` | text | STRING | optional | R | | `HARD` (a breach declines the trade) or `SOFT` (a breach refers it) | `HARD` |
| `TEMPORARY_EXCESS` | amount | DOUBLE | optional | R | | an approved excess above `LIMIT` | `0` |
| `EXCESS_EXPIRES` | date | STRING | optional | R | | | |
| `APPROVED_BY`, `APPROVED_ON`, `REVIEW_DUE` | text, date, date | doc / STRING | required | R (`REVIEW_DUE`) | | | `Credit Risk Committee`, `2026-03-19`, `2027-03-19` |
| `LIMIT_STATUS` | text | STRING | optional | R | | `ACTIVE`, `SUSPENDED`, `EXPIRED` (the limit itself, set by the limits system) | `ACTIVE` |
| `COUNTERPARTY_NAME` | text | doc | optional | – | | display (masked as the counterparty's name) | `Aldershot Pension Trust` |
| `BATCH_ID` | id | STRING | required | – | | | |

## 4. Reference and master data read

| Kind | Read for |
|---|---|
| `exposure-result` (EXP.md) | PFE, EE, EEPE profiles of the limit's scope; incremental figures for pre-deal |
| `saccr-result` (SACCR.md) | EAD of the scope; incremental EAD |
| `nset-figures` (NSET.md) | net MtM, current exposure, notional |
| `trade` (`EVENTS[]`, `LEGS[].CASH_FLOWS[]`) or a cash-flow table (`LIQUIDITY.md`, planned) | settlement amounts due per day |
| `counterparty`, `counterparty-group` (GRP.md) | scope resolution, masks |

## 5. Conventions specific to limits

| Convention | Rule |
|---|---|
| Units | usage in the limit's currency (USD) |
| Utilisation | usage / limit, a fraction (0.667 = 66.7%); empty when the limit is 0 |
| Status order | `BREACH` > `EXCESS_APPROVED` > `WARNING` > `OK`; a limit's status is its worst bucket's |
| Review | a limit past `REVIEW_DUE` is still applied and flagged `REVIEW_OVERDUE` |
| Rounding | whole USD; utilisation unrounded |

## 6. The computation

### 6.1 Parameters (`drishti.ccr.limits.*`)

| Setting | Default | Meaning |
|---|---|---|
| `warning-level` | `0.8` | when a limit gives none |
| `statuses` | `{ok: OK, warning: WARNING, breach: BREACH, excess: EXCESS_APPROVED}` | names shown |
| `settlement-days` | `5` | settlement days looked ahead |
| `pre-deal.exposure-method` | `paths` | EXP's what-if method (`paths` when the candidate has paths, else `addon`) |
| `pre-deal.refer-on-warning` | `true` | a candidate that takes a limit into `WARNING` is referred |

### 6.2 Usage per measure

For limit L with scope S, buckets b with (FROM_b, TO_b], primary grid dates t_k of EXP:

| `MEASURE` | Usage of bucket b | Overall usage | Additive over the scope's netting sets? |
|---|---|---|---|
| `PFE` | max_{t_k ∈ b} PFE_q,S(t_k) (EXP §6.4: paths of the scope's sets summed, then the quantile) | max over all t_k | no |
| `EE_PEAK` | max_{t_k ∈ b} EE_S(t_k) | max | EE yes, peak no |
| `EEPE` | – | EEPE_S | no |
| `SACCR_EAD` | – | EAD_S (Σ over netting sets) | yes |
| `NET_MTM` | – | Σ MtM | yes |
| `CE` | – | Σ_n max(MtM_n − C_n, 0) | yes (per set) |
| `NOTIONAL` | Σ \|notional\| of trades maturing in b | Σ \|notional\| | yes |
| `SETTLEMENT` | for days d in b: Σ gross amounts the counterparty pays the bank on d not settled DvP/PvP; usage = max over d | max over the next `settlement-days` | per day yes, max no |

### 6.3 Utilisation, status, headroom

```
UTIL_b      = USAGE_b / LIMIT_b                          HEADROOM_b = LIMIT_b − USAGE_b
STATUS_b    = OK                 UTIL_b < WARNING_LEVEL
              WARNING            WARNING_LEVEL ≤ UTIL_b ≤ 1
              EXCESS_APPROVED    1 < UTIL_b and USAGE_b ≤ LIMIT_b + TEMPORARY_EXCESS (not expired)
              BREACH             otherwise
STATUS_L    = the worst STATUS_b (and the overall limit's)
HEADROOM_L  = min_b HEADROOM_b; BINDING_BUCKET = its bucket
```

A sub-allocated limit (`PARENT_LIMIT`) is checked on its own; the parent is checked on its own scope's usage (GRP.md), never as a sum of
children's usages (PFEs do not add).

### 6.4 Pre-deal check

Request: the counterparty (or netting set), the candidate trade(s) with their `saccr-trade` rows and estimated MtM, and their exposure
paths on the day's run when available (EXP.md section 6.7). For every limit whose scope contains the candidate's netting set:

```
USAGE_AFTER_b = usage of the measure with the candidate (EXP what-if for PFE/EE/EEPE, SACCR what-if for EAD, sums for the rest)
INCREMENT_b   = USAGE_AFTER_b − USAGE_b
DECISION      = DECLINE   any HARD limit's bucket goes to BREACH
                REFER     a SOFT limit to BREACH, any limit to EXCESS_APPROVED, or to WARNING (refer-on-warning)
                APPROVE   otherwise
```

The answer lists each limit and bucket with before, after, increment, headroom after and status after; the method (`paths` or the
conservative `addon` proxy) is stated. **Maximum size** of a trade that still fits: for a linear trade under the `addon` method, the
headroom of the binding bucket divided by the candidate's add-on per unit notional; with `paths`, a bisection on a notional scale of the
candidate's paths (at most 12 evaluations).

### 6.5 Where it runs

Python engine `drishti_ccr.limits:LimitEngine`: it reads the measures as reactive inputs (`exposure-result`, `saccr-result`,
`nset-figures` at each limit's scope), so the heavy work stays in their engines and their caches; LIM applies buckets, statuses and the
pre-deal rules. A morning run of every limit (a large bank about 30,000) is a batch-class computation fanned out over the pool.

## 7. What Drishti builds

Per limit: its usage per bucket, status and headroom (about 20 doubles per bucket); 30,000 limits × 6 buckets × 160 bytes = 30 MB per
date. The usage depends on other measures' answers, which are cached by those engines; LIM caches its own answers (key: limit or scope,
date, the inputs' versions, view signature). No disk structure.

## 8. Scopes

A limit's own `SCOPE` is any scope text. The `limit-usage` kind is asked by scope too: `LIM CPTY:CP-ALDERSHOT` lists every limit whose
scope **contains or equals** the counterparty's (the counterparty's own limits, its group's, its netting sets'), each with its own usage.
`LE:` lists the limits of the counterparties the legal entity faces; `BANK` every limit, worst first.

## 9. What Drishti answers

### 9.1 The computed kind `limit-usage`

**Id:** a limit id (`LIM-ALDERSHOT`) or a scope text.

| Field | Type | Unit | Meaning |
|---|---|---|---|
| `LIMIT_ID`, `SCOPE`, `MEASURE`, `CONFIDENCE`, `LIMIT`, `CURRENCY` | | | from the limit |
| `BUSINESS_DATE` | date | | |
| `USAGE`, `UTILISATION`, `HEADROOM`, `STATUS`, `BINDING_BUCKET` | amount, rate, amount, text, text | USD | §6.3 |
| `BUCKETS` | record list | | `BUCKET`, `FROM`, `TO`, `LIMIT`, `USAGE`, `UTILISATION`, `HEADROOM`, `STATUS`, `PEAK_DATE` |
| `FLAGS` | text list | | `REVIEW_OVERDUE`, `EXCESS_EXPIRED`, `proxy`, `incomplete` |
| `LIMITS` | record list | | for a scope id: one row per limit as above |
| `PROVENANCE` | record | | NSET's, plus the measure answers used (their ids and versions) |

### 9.2 The API (`/api/v1/limits`, planned)

| Method and path | Parameters | Answer |
|---|---|---|
| `GET /{limitId}` | `asOf` | §9.1 for one limit |
| `GET /scope` | `scope`, `status` (filter), `asOf` | the limits touching the scope |
| `GET /breaches` | `asOf`, `level` (`WARNING` default) | every limit at or above the level, worst first |
| `POST /pre-deal` | `{counterparty | nettingSet, candidates: [{saccrTrade, mtm, paths?}], method?}` | `{decision, limits: [{limitId, bucket, before, after, increment, headroomAfter, statusAfter}], maxNotional?, method, flags}` |
| captures, status, reload | as NSET | |

CSV: `GET /breaches?format=csv`: `limit_id,scope,measure,bucket,limit,usage,utilisation,headroom,status`.

### 9.3 Published official results (`limit-official`, `limit-official-cpty`)

The node table of [NSET.md section 9.5](NSET.md#95-published-official-results-nset-official-nset-official-cpty) carries, per hierarchy
node, the counts of limits in each status among the counterparties the node faces (`LIMITS`, `OK`, `WARNINGS`, `BREACHES`,
`EXCESSES`, BIGINT) and the total headroom of SA-CCR EAD limits (`EAD_HEADROOM`, DOUBLE). The restricted `-cpty` table's natural row is
**the limit** (row level `limit`, `NODE_ID` the limit id): `SCOPE`, `MEASURE`, `CONFIDENCE`, `LIMIT`, `USAGE`, `UTILISATION`,
`HEADROOM`, `STATUS`, `BINDING_BUCKET`, and the buckets as JSON text (`BUCKETS`).

### 9.4 The reactive connector and the engine (proposed)

```yaml
# config/connectors/lim-engine.yaml                             (planned, 2.10.0)
plugin: reactive
mode: reactive
description: Credit limit usage, status and headroom; the pre-deal check
kinds:
  limit-usage:
    compute: limits
    result: { required: [STATUS], types: { USAGE: number, UTILISATION: number } }
  limit-predeal:
    compute: pre-deal
    params:
      sandbox: { type: sandbox-ref, required: true }
      method:  { type: enum, options: [paths, addon], default: paths }
reads:
  - { kind: credit-limit, as: limits, all: true }
  - { kind: exposure-result, as: exposure, ids: "{inputs.limits.SCOPE}" }
  - { kind: saccr-result, as: saccr, ids: "{inputs.limits.SCOPE}" }
  - { kind: nset-figures, as: sets, ids: "{inputs.limits.SCOPE}" }
engine:
  type: python
  entry: drishti_ccr.limits:LimitEngine
  pool: risk
  version: "1.0"
  split: limits
  chunk-rows: 500
triggers:
  - { on: data-version, kinds: [credit-limit, exposure-path, saccr-trade, trade-valuation, netting-set-member, csa, collateral-holding], affects: all }
  - { on: schedule, cron: "0 30 6 * * MON-FRI", zone: America/New_York }      # the morning limit run (batch class)
cache: { results: 5000, ttl: 1d }
limits: { timeout: 60s, wait: 100ms, max-inflight-per-user: 4 }
requires: { python: { version: "3.12", lock: requires/drishti-ccr.lock } }
```

```python
# compute/drishti-ccr/src/drishti_ccr/limits.py                 (planned, 2.10.0)
class LimitEngine(Engine):
    kinds = ("limit-usage", "limit-predeal")
    def compute(self, req, inputs, progress):
        limits = inputs.arrow("limits")      # LIMIT_ID, SCOPE, MEASURE, CONFIDENCE, LIMIT, WARNING_LEVEL, LIMIT_TYPE, TEMPORARY_EXCESS,
                                             # EXCESS_EXPIRES, BUCKETS (list<struct<BUCKET, FROM, TO, LIMIT>>)
        profiles = inputs.arrow("exposure")  # SCOPE, T float64, DATE, EE, PFE   (EXP's PROFILE per scope)
        eads = inputs.arrow("saccr")         # SCOPE, EAD
        ...                                  # sections 6.2 to 6.4
        return Result(rows=[...], tables={"buckets": ...})
```

## 10. Validation, errors and data quality

| Check | Rule | Outcome |
|---|---|---|
| Scope | `SCOPE` parses (VAR.md section 8.2) and resolves on the date | unresolved: limit flagged `unknown-scope`, usage empty |
| Measure | in the enum; `CONFIDENCE` in (0.5, 1) for `PFE` | row rejected |
| Buckets | contiguous, increasing, non-overlapping; bucket limits ≥ 0 | row rejected |
| Parent | `PARENT_LIMIT` exists; no cycles | flagged |
| Duplicates | two active limits with the same scope, measure and confidence | both kept, flagged |
| Inputs | the measure's input incomplete (EXP run incomplete, SA-CCR unclassified trades) | usage computed, flagged `incomplete` |

**Proposed DRS codes** (5350–5359): `DRS-5350` (400, bad scope, limit id or pre-deal request), `DRS-5351` (409, the pre-deal candidate
cannot be valued by the chosen method: no paths on the run and `method=paths`).

## 11. Entitlements and audit

As NSET.md section 11. A pre-deal check is audited (`ccr-predeal`: user, counterparty, candidate summary, decision, limits touched) and
answered only to callers who may see the counterparty at `trades` level; sales users may get the decision without the usage numbers
(`drishti.ccr.limits.pre-deal-detail: decision-only` per role, open question 4).

## 12. Performance and sizing

| Operation | Target (p95, warm) |
|---|---|
| one limit (measure answers cached) | 20 ms |
| a counterparty's limits | 50 ms |
| the morning run, 30,000 limits | 5 minutes (batch class, EXP statistics precomputed) |
| pre-deal, `addon` | 100 ms; `paths` 1.5 s |

## 13. Test data and generators

`tools/packgen/ccr/terms.py` (planned): one counterparty limit per counterparty with five buckets (0–1Y, 1–3Y, 3–5Y, 5–10Y, 10Y+) set from
the **computed** PFE profile × a headroom factor (most `OK`, three `WARNING`, one `BREACH`, one `EXCESS_APPROVED`, one past review), one
SA-CCR EAD limit for each bank counterparty, two group limits (GRP.md), one settlement limit. Files `data/testdata/counterparty-risk/credit-limit/`.

## 14. Retiring the stored version

| Item | Change |
|---|---|
| `credit-limit` | kept; `USED`, `UTILISATION`, `STATUS`, `BUCKETS[].USED`, `BUCKETS[].UTILISATION` removed; `MEASURE` text → enum + `CONFIDENCE`; `SCOPE` added (`CPTY:<COUNTERPARTY>` for every existing limit); bucket labels gain `FROM_YEARS`, `TO_YEARS` |
| `netting-set.LIMIT`, `UTILISATION` | removed (NSET.md section 14) |
| ids | `LIM-<cp>` unchanged; the page shows `limit-usage` |
| Calc snippets | `limit_heatmap.py` reads `limit-usage` |
| Guard test | no input or fundamental kind carries `USED`, `UTILISATION` on limits |

**Reconciliation: how the stored usage was made.** `credit_results` sets a counterparty's `USED` to the **sum of its netting sets'
stored PFE peaks** (quantiles taken at possibly different dates and then summed: neither is the counterparty's PFE, which is the
quantile of its netting sets' exposures summed per path), its `LIMIT` to that usage × U(1.2, 2.0) rounded to a million, the status from
the overall utilisation, and each bucket's usage to the overall usage × a fixed fraction (0.85, 1.0, 0.7, 0.35, 0.1) — so the 1–3Y bucket
always equals the overall usage and the others are not computed from any profile. Test data example `LIM-ALDERSHOT`: limit 8,843,000,000,
used 6,650,622,170 (the universe's scaled book), while its 1–3Y bucket shows 7,124,260,290, above the overall usage (the changes files
walk the buckets separately). The computed usage reads EXP at `CPTY:CP-ALDERSHOT`; the release notes list both.

## 15. A worked example

Use EXP.md section 15's netting set as the whole counterparty (PFE at q = 75%, USD millions): PFE(t₀) = 0, PFE(0.25) = 1, PFE(0.5) = 2,
PFE(1.0) = 1. A `PFE` limit with two buckets, `HARD`, warning level 0.8:

| Bucket | Grid dates in it | Limit | Usage = max PFE | Utilisation | Status | Headroom |
|---|---|---|---|---|---|---|
| 0–6M (0, 0.5] | t₀, 0.25, 0.5 | 2.5 | 2 | 0.80 | **WARNING** | 0.5 |
| 6M–1Y (0.5, 1] | 1.0 | 1.5 | 1 | 0.667 | OK | 0.5 |

Limit status **WARNING**, binding bucket 0–6M (equal headroom 0.5 in both; the first wins ties).

**Pre-deal, `addon` method:** a 1-year FX forward, notional 5, at market (MtM 0); SA-CCR SF = 4%, δ = 1; z₀.₇₅ = 0.674. Its proxy PFE adds
0.674 × 0.04 × 5 × √t: **0.0674** at 0.25, **0.0953** at 0.5, **0.1348** at 1.0. After: 0–6M usage 2.0953 (utilisation 0.838, `WARNING`),
6M–1Y 1.1348 (0.757, `OK`). No breach, but a bucket in `WARNING` → **REFER** (`refer-on-warning`). Maximum notional before the 0–6M
bucket breaches: headroom 0.5 / (0.674 × 0.04 × √0.5) = **26.2**.

## 16. Open questions and the change table

### 16.1 Open questions

| # | Question | Context |
|---|---|---|
| 1 | Due time of the limits feed; is the limits system also the place pre-deal decisions are recorded (write-back), or Drishti's audit only? | §3.1, §11 |
| 2 | Settlement limits: read cash flows from the trades' `EVENTS[]` (demo) or from the cash-flow table of `LIQUIDITY.md` (2.11.0)? Which products settle DvP/PvP? | §6.2 |
| 3 | Which PFE does a limit use: real-world calibration (EXP open question 4) or the CVA run? | §3.2 |
| 4 | Pre-deal detail by role (decision only for sales) | §11 |
| 5 | Bucket membership of a grid date: (FROM, TO] (proposed) or [FROM, TO)? | §3.2 |

### 16.2 The change table

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-25 | First version, PLANNED for 2.10.0; product owner's decisions of 2026-10-25 applied (load signal reloads the engine, inputs kept 7 years, published official results) |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
