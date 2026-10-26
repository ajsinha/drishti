<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# GRP: counterparty groups and their usage, the data contract

*Version 1.0 · 2026-10-25 · Shared rules: [README.md](README.md) · Model contract: [VAR.md](VAR.md) · Netting and scopes:
[NSET.md](NSET.md) · Exposure: [EXP.md](EXP.md) · Limits: [LIM.md](LIM.md) · GLEIF: [packs/gleif.md](packs/gleif.md)*

**Status:** proposed. PLANNED for 2.10.0, not built. Every table, column, setting, kind, endpoint and code below is a proposal; names
may change before the release, and each change will be recorded in [section 16](#16-open-questions-and-the-change-table).

This is the contract of **counterparty groups**: Drishti keeps the groups the credit function maintains (their members and why each is a
member) and resolves the **connected group** of any counterparty when asked, from those members and from GLEIF's parent relationships;
then every counterparty measure answers for the group as a scope (`GROUP:<id>`), and the group's limits are checked on it.

## Contents

1. [Summary](#1-summary)
2. [The measure in one page](#2-the-measure-in-one-page)
3. [Inputs from upstream engines](#3-inputs-from-upstream-engines)
4. [Reference and master data read](#4-reference-and-master-data-read)
5. [Conventions specific to groups](#5-conventions-specific-to-groups)
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

**Commands.** `GRP <group id> <GO>` opens a group: its members (kept and from GLEIF), its usage under every measure, its limits;
`GRP CPTY CP-ALDERSHOT <GO>` opens the connected group of a counterparty. Every measure takes `GROUP:<id>` as a scope (`EXP GROUP
GRP-MERIDIAN <GO>`). The API is `/api/v1/groups`.

**Kept and computed.** `counterparty-group` stays: its members (with the reason for each) and its id. Removed: `UTILISATION` (computed),
and `LIMIT` moves to `credit-limit` as a limit with scope `GROUP:<id>` (LIM.md; today it is the sum of the members' limits, a derived
figure, open question 1).

| Part | Status |
|---|---|
| `counterparty-group` moved to the risk lake, members with a relation and a source | PLANNED |
| Connected group: kept members ∪ GLEIF parent links (ultimate or direct chain), with conflict rules | PLANNED |
| Group usage under every measure (EXP paths summed across members; EAD, MtM, collateral summed), member breakdown | PLANNED |
| Computed kind `group-usage`, reactive connector `grp-engine` (Java resolver; measures by their engines) | PLANNED |

## 2. The measure in one page

**What it answers:** *which counterparties are one risk, and how much of the group's limit do they use together?* For credit officers
(group limits), large-exposure reporting (Basel LEX30: connected counterparties through control or economic interdependence), and the
pre-deal check (a trade with one subsidiary uses the group's limit).

**In words:** a counterparty's connected group is the union of (a) the group the credit function assigned it to, with members related by
*control* or *economic interdependence*, and (b) every counterparty whose LEI shares its ultimate accounting-consolidation parent in
GLEIF (an active relationship). The group's usage is each measure computed for `GROUP:<id>`, the union of the members' trades: amounts that
add (MtM, collateral, EAD, EE) are summed; PFE is the quantile of the members' exposures **summed per path**, which can be larger than the
sum of their PFEs or smaller.

## 3. Inputs from upstream engines

### 3.1 Where it lives

`${DRISHTI_DELTA_ROOT}/risk/counterparty-group/business_date=YYYY-MM-DD/` (moved from `reference/counterparty-group`), one row per group.
Source: the counterparty master or the credit system; daily snapshot; due **to be agreed**; load signal reloads the date (product owner,
2026-10-25); kept 7 years. GLEIF data stays in the gleif pack's connector (`gleif-store`).

### 3.2 `counterparty-group`: the kept members

| Column | JSON | Delta | Req. | Engine | Meaning | Example |
|---|---|---|---|---|---|---|
| `GROUP_ID` | id | `id` STRING | required | R | | `GRP-MERIDIAN` |
| `BUSINESS_DATE` | date | `business_date` DATE | required | R | | `2026-09-30` |
| `NAME` | text | STRING | required | – | | `Meridian Group` |
| `ULTIMATE_PARENT_LEI` | id | STRING | optional | R | the group's GLEIF anchor; members found through it join (§6.1) | `5493001KJTIIGC8Y1R12` |
| `MEMBERS[]` | record list | doc | required | R | below | |
| `BATCH_ID` | id | STRING | required | – | | |

| `MEMBERS[]` field | Type | Req. | Meaning | Example |
|---|---|---|---|---|
| `ID` | id | required | the counterparty | `CP-MERIDIAN-RE` |
| `RELATION` | text | required | `PARENT`, `CONTROL` (subsidiary or controlled entity), `ECONOMIC_DEPENDENCE` (LEX30: a member's failure would cause the other's), `MANUAL` | `CONTROL` |
| `SOURCE` | text | optional | `CREDIT_FILE`, `GLEIF`, `REGISTRY` | `CREDIT_FILE` |
| `EXCLUDE` | boolean | optional | the credit function excludes this counterparty even if GLEIF connects it (with `REASON`) | `false` |
| `REASON` | text | optional | | `ring-fenced SPV, no recourse` |
| `NAME`, `RATING` | text | optional | display, from the counterparty master (today copied: kept only as display) | |

## 4. Reference and master data read

| Kind | Key | Read for |
|---|---|---|
| `counterparty` (core) | `COUNTERPARTY_ID` | `LEI`, `GROUP` (the counterparty's assigned group), name, rating, sector, country |
| `lei-entity` (gleif) | `LEI` | `DIRECT_PARENT`, `ULTIMATE_PARENT`, `ENTITY_STATUS`, `PARENT_EXCEPTIONS` |
| `lei-relationship` (gleif) | `RELATIONSHIP_ID` | `CHILD_ENTITY`, `PARENT_ENTITY`, `LEVEL` (`Direct`, `Ultimate`), `RELATIONSHIP_STATUS`, `START` |
| `lei-group` (gleif) | `GROUP_ID` | display of the GLEIF tree |
| every counterparty measure's computed kind | scope | usage |

## 5. Conventions specific to groups

| Convention | Rule |
|---|---|
| Group id | a kept group's `GROUP_ID`; a group found only through GLEIF is named by its ultimate parent's LEI (`GROUP:5493001KJTIIGC8Y1R12`) |
| One group per counterparty | a counterparty belongs to at most one connected group on a date; conflicts are resolved by `precedence` and flagged (§6.2) |
| Usage | in USD, per measure, as the measures define it |

## 6. The computation

### 6.1 Resolving the connected group

```
kept(G)      = { m.ID : m ∈ G.MEMBERS, not m.EXCLUDE }
anchor(G)    = G.ULTIMATE_PARENT_LEI, or the ultimate parent (GLEIF) of the members' LEIs when they agree
gleif(G)     = { c : c.LEI's ultimate parent = anchor(G) via ACTIVE relationships }          (gleif-levels: ultimate)
             or the transitive closure of ACTIVE Direct relationships from anchor(G)        (gleif-levels: direct-chain)
connected(G) = kept(G) ∪ gleif(G) − { members with EXCLUDE }
```

Counterparties without an LEI, or whose LEI is not in GLEIF, join only through `kept`. A GLEIF parent exception (`PARENT_EXCEPTIONS`:
*natural persons*, *non-consolidating*) stops the chain there.

### 6.2 Conflicts

| Case | Rule (`drishti.ccr.groups.precedence`, default `kept`) |
|---|---|
| a counterparty kept in group A whose GLEIF ultimate parent anchors group B | stays in A; flagged `CONFLICT` on both groups |
| a counterparty in two kept groups | the first by `GROUP_ID`; flagged (a data error) |
| the counterparty master's `GROUP` differs from the kept group holding it | the kept group wins; flagged |

### 6.3 Usage

For each measure M asked: M at scope `GROUP:<id>` = M over the union of the members' netting sets (NSET.md section 8). Concretely:

| Measure | Group figure | Additive over members? |
|---|---|---|
| net MtM, collateral, CE (NSET) | Σ members | yes |
| SA-CCR EAD, RWA | Σ members' netting sets | yes |
| EE, ENE, EPE (EXP) | Σ members | yes |
| PFE, EEPE, peaks (EXP) | quantile/max of the members' exposures summed per path | **no** |
| CVA (CVA.md) | Σ members (each with its own credit curve) | yes (no default correlation inside a group: open question 4) |
| limit usage (LIM) | the group's limits evaluated on the group scope | per limit |

**Member breakdown:** each member's own figure, its share of an additive group figure, and for PFE its contribution (EXP.md section 6.6:
the mean of its exposure over the paths around the group's quantile at the group's peak date, scaled to add up to the group's PFE).

### 6.4 Where it runs

The resolver is **Java** (`com.ash.drishti.compute.ccr.GroupResolver`, planned): a graph over a few hundred thousand GLEIF relationships
and the counterparties, built once per date, answering `GROUP:` scopes in microseconds. The usage figures come from each measure's own
engine at the group scope; group exposure matrices are summed at date load for every kept group (EXP.md section 7.3).

## 7. What Drishti builds

| Structure | Content | Size, large bank (20,000 counterparties, 3 million GLEIF relationships) |
|---|---|---|
| LEI index | LEI → counterparty ids | 2 MB |
| parent map | child LEI → direct parent, ultimate parent (active only) | 3 million × 2 × 4-byte codes + dictionary: about 150 MB |
| group table | group → members (ordinal sets over counterparties), anchor, flags | 5 MB |
| group exposure statistics | EXP's per-group statistics (computed at load) | 5,000 groups × 50 dates × 5 × 8 = 10 MB |

## 8. Scopes

`GROUP:<id>` resolves to `CPTY:` of every connected member (NSET.md section 8). `CPTY:<id>` stays the counterparty alone. A legal entity's
share of a group: `GROUP:<id> & LE:<le>`.

## 9. What Drishti answers

### 9.1 The computed kind `group-usage`

| Field | Type | Unit | Meaning |
|---|---|---|---|
| `GROUP_ID`, `NAME`, `ANCHOR_LEI`, `BUSINESS_DATE` | | | |
| `MEMBERS` | record list | | `ID`, `NAME`, `LEI`, `RELATION`, `SOURCE` (`kept`, `gleif`, `both`), `RATING`, `NET_MTM`, `CE`, `EAD`, `EPE`, `PEAK_PFE`, `PFE_CONTRIBUTION`, `FLAGS` |
| `NET_MTM`, `CE`, `COLLATERAL`, `EAD`, `EPE`, `PEAK_PFE`, `PEAK_PFE_DATE`, `CVA` | amount | USD | group figures (§6.3) |
| `SUM_MEMBER_PFE` | amount | USD | Σ members' own peak PFE, for comparison (not a group figure) |
| `LIMITS` | record list | | the group's limits with usage and status (LIM.md) |
| `TREE` | record list | | the GLEIF tree of the members (`LEI`, `PARENT`, `LEVEL`, `NAME`, `IS_COUNTERPARTY`) |
| `FLAGS` | text list | | `CONFLICT`, `NO_LEI` (members without one), `LAPSED` (lapsed GLEIF registrations used) |
| `PROVENANCE` | record | | NSET's, plus the GLEIF file date and the resolution settings |

### 9.2 The API (`/api/v1/groups`, planned)

| Method and path | Parameters | Answer |
|---|---|---|
| `GET /{groupId}` | `asOf`, `measures` (default `nset,saccr,exposure,limits`) | §9.1 |
| `GET /of/{counterpartyId}` | `asOf` | the connected group of a counterparty (its id and members) |
| `GET /conflicts` | `asOf` | every conflict of §6.2 |
| captures, status, reload | as NSET | |

### 9.3 Published official results (`group-official-cpty`)

Group figures name counterparties, so a group publishes only to the restricted table of
[NSET.md section 9.5](NSET.md#95-published-official-results-nset-official-nset-official-cpty) (row level `group`): `MEMBERS` (BIGINT),
`NET_MTM`, `CE`, `COLLATERAL`, `EAD`, `EPE`, `PEAK_PFE`, `SUM_MEMBER_PFE`, `CVA` (DOUBLE, USD), `PEAK_PFE_DATE` (DATE), `LIMIT_STATUS`
(STRING, the worst of its limits), `MEMBER_IDS` (STRING, JSON text). There is no node-level `group-official` table (a hierarchy node has
no group figure of its own; the node tables of the other measures cover it).

### 9.4 The reactive connector (proposed)

```yaml
# config/connectors/grp-engine.yaml                             (planned, 2.10.0)
plugin: reactive
mode: reactive
description: Connected counterparty groups (kept members and GLEIF parents) and their usage under every measure
kinds:
  group-usage:
    compute: group
    params:
      gleif-levels: { type: enum, options: [ultimate, direct-chain, none], default: ultimate }
      measures:     { type: text, default: "nset,saccr,exposure,limits" }
    result: { required: [MEMBERS] }
reads:
  - { kind: counterparty-group, as: groups, all: true }
  - { kind: counterparty, as: counterparties, all: true }
  - { kind: lei-relationship, as: relationships, all: true }
  - { kind: nset-figures, as: nset, id: "GROUP:{id}" }
  - { kind: saccr-result, as: saccr, id: "GROUP:{id}" }
  - { kind: exposure-result, as: exposure, id: "GROUP:{id}" }
  - { kind: limit-usage, as: limits, id: "GROUP:{id}" }
engine:
  type: java
  entry: com.ash.drishti.compute.ccr.GroupEngine
  version: "1.0"
triggers:
  - { on: data-version, kinds: [counterparty-group, counterparty, lei-relationship, lei-entity], affects: all }
cache: { results: 2000, ttl: 1d }
limits: { timeout: 60s, wait: 100ms, max-inflight-per-user: 4 }
```

## 10. Validation, errors and data quality

| Check | Rule | Outcome |
|---|---|---|
| Members exist | every `MEMBERS[].ID` is a counterparty on the date | unknown members listed, ignored |
| One group | a counterparty in two kept groups | flagged (§6.2) |
| LEI format | 20 characters, ISO 17442 check digits | `NO_LEI` for that member |
| GLEIF freshness | the GLEIF data date within `drishti.ccr.groups.gleif-max-age-days` (7) | flagged `stale-gleif` |
| Cycles | a GLEIF parent cycle | broken at the first repeat, flagged |

**Proposed DRS codes** (5360–5369): `DRS-5360` (400, bad group id or parameters), `DRS-5361` (404, no group for the counterparty when
asked `strict`).

## 11. Entitlements and audit

As NSET.md section 11. A group's figures include members the viewer may not cover: those members are counted in the totals only when the
viewer has `aggregate` on them, otherwise excluded and counted under `HIDDEN` (the group's figure is then marked partial). Restricted-list
members are never named.

## 12. Performance and sizing

| Operation | Target (p95, warm) |
|---|---|
| resolve a group | 1 ms |
| a group with 20 members, every measure | 300 ms (EXP's group statistics precomputed) |
| date load (resolver graph) | 10 s with the full GLEIF relationship file |

## 13. Test data and generators

Today the demo's 24 counterparties sit in 23 groups, only `GRP-MERIDIAN` has more than one member, and **none of the counterparties' LEIs
(synthetic, `samplegen.ids.lei(name)`) is in the gleif pack's data**, so a GLEIF-connected group cannot form. `tools/packgen/ccr/terms.py`
(planned) will:
- give five counterparties the LEIs of real entities present in the committed GLEIF data, two of them sharing an ultimate parent, so one
  group forms through GLEIF alone and one has a `CONFLICT`;
- keep `GRP-MERIDIAN` (two members, `CONTROL`) and add an `ECONOMIC_DEPENDENCE` member to one group;
- add `ULTIMATE_PARENT_LEI` to the kept groups that have one;
- write the group limits as `credit-limit` rows with scope `GROUP:<id>` (LIM.md).

Note: GLEIF data is public (the gleif pack's README states its terms); using real LEIs for synthetic counterparties is a test fixture
only, as the demo's other look-alikes.

## 14. Retiring the stored version

| Item | Change |
|---|---|
| `counterparty-group.UTILISATION` | removed (computed: `group-usage`) |
| `counterparty-group.LIMIT` | moved to `credit-limit` (scope `GROUP:<id>`) |
| `MEMBERS[].NAME`, `RATING` | display only (from the counterparty master) |
| ids | `GRP-<x>` unchanged; the page shows `group-usage` |
| Calc snippet | `group_exposure.py` (core) reads `group-usage` |
| Guard test | no fundamental kind carries `UTILISATION` |

**Reconciliation.** `reference` in `tools/packgen/banking/data_results.py` sets a group's `LIMIT` to the **sum of its members' limits**
and `UTILISATION` to Σ members' used / Σ members' limits: the group has no limit of its own and its usage is a sum of member PFE peaks
(LIM.md section 14). The computed group PFE is the quantile of the summed member paths; for the 22 single-member groups the computed
figures equal the member's, for `GRP-MERIDIAN` they will differ.

## 15. A worked example

Group G: parent P (LEI `L-P`), subsidiaries S1 (`L-S1`, GLEIF ultimate parent `L-P`) and S2 (kept, `CONTROL`), and M (kept,
`ECONOMIC_DEPENDENCE`, no LEI). Exposures (USD millions) on 4 paths at the group's peak date, after netting and collateral in each member's
netting sets:

| Member | Source | E on paths 1–4 | EE | PFE 75% (3rd of 4) | EAD |
|---|---|---|---|---|---|
| S1 | gleif | 2, 0, 1, 1 | 1.0 | 1 | 2.0 |
| S2 | kept | 0, 3, 0, 0 | 0.75 | 0 | 1.5 |
| P | both (kept and anchor) | 0, 0, 0, 0 | 0 | 0 | 0.3 |
| M | kept | 0, 0, 0, 0 | 0 | 0 | 0 |
| **Group** | | **2, 3, 1, 1** | **1.75** (= 1.0 + 0.75) | sorted 1, 1, 2, 3 → **2** | **3.8** (sum) |

EE and EAD add; the group's PFE (2) is **twice the sum of the members' PFEs** (1 + 0 = 1): S2's exposure is zero on three paths, so its
own 75% PFE is 0, but on path 2 it lifts the group. A group limit of 2.2 is therefore at 91% (`WARNING`), although each member looks small.

## 16. Open questions and the change table

### 16.1 Open questions

| # | Question | Context |
|---|---|---|
| 1 | The group's limit: a `credit-limit` row with scope `GROUP:<id>` (proposed), or kept on the group record? Today it is the sum of the members' limits | §1, §14 |
| 2 | GLEIF levels: ultimate parent (proposed) or the direct-parent chain; include lapsed registrations? | §6.1 |
| 3 | Economic interdependence (LEX30) is manual: does it come from the credit file (the `counterparty-group` feed) only? | §3.2 |
| 4 | Group CVA: members' defaults are correlated; ignore (proposed, CVA per member) or model a group-level default? | §6.3 |
| 5 | Members a viewer does not cover: exclude and mark partial (proposed), or refuse the group answer? | §11 |

### 16.2 The change table

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-25 | First version, PLANNED for 2.10.0; product owner's decisions of 2026-10-25 applied (load signal reloads the engine, inputs kept 7 years, published official results in the restricted table) |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
