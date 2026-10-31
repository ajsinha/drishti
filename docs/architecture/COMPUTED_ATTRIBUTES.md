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
# Computed and derived attributes in the market-risk and counterparty-risk packs

**Status: analysis, decided (section 11), and partly built in 2.9.7.** The quick wins Q1 to Q5 and the engine step E0 are built: see
[section 13, As built](#13-as-built-297) for what was applied, the findings it fixed, the reconciliation it measured and what is still
open. Everything else is the analysis as written: every "today" statement cites the file it was read from (as it was before 2.9.7, where
section 13 says it changed); every "should", every formula marked *proposal*, and every YAML block marked *PROPOSAL, NOT APPLIED* that
section 13 does not list is a recommendation for the engines still to come.

**The request (product owner):** "Prepare a document on what you think should be computed or derived attributes across the various
Sutras in the market-risk pack, and then in the counterparty credit risk pack. That will bring greater consistency."

**How it relates to the existing designs.** The measure contracts in [docs/contracts](../contracts/README.md) (TVAL, STR, FRTB, SIMM,
PNL, NSET, SACCR, COLL, MC, LIM, GRP, CVA, WWR) already design each engine in depth, with status *proposed*. This document does not
replace them. It works **attribute by attribute, Sutra by Sutra**: what each number on each page is today, what it should be, how it
is produced, and where two pages disagree. Where a contract already defines the formula, this document restates it in short form and
points to the contract's section; where no contract covers an attribute, the formula here is new and marked as such.

## Contents

1. [Purpose and principle](#1-purpose-and-principle)
2. [How each class is built in Drishti today](#2-how-each-class-is-built-in-drishti-today)
3. [Shared definitions used by every formula](#3-shared-definitions-used-by-every-formula)
4. [Market-risk pack, kind by kind](#4-market-risk-pack-kind-by-kind)
5. [Counterparty credit risk pack, kind by kind](#5-counterparty-credit-risk-pack-kind-by-kind)
6. [Findings: duplicates and inconsistencies](#6-findings-duplicates-and-inconsistencies)
7. [Cross-pack consistency rules](#7-cross-pack-consistency-rules-proposal)
8. [Proposed derived kinds and Sutra expressions (illustrations)](#8-proposed-derived-kinds-and-sutra-expressions-illustrations)
9. [Recommended build order, retirement and reconciliation](#9-recommended-build-order-retirement-and-reconciliation)
10. [Modelling choices for the product owner](#10-modelling-choices-for-the-product-owner)
11. [Open questions](#11-open-questions-decided)
12. [Sources read](#12-sources-read)
13. [As built (2.9.7)](#13-as-built-297)

---

## 1. Purpose and principle

### 1.1 Three classes of attribute

| Class | Definition | Who produces it | Stored? | Examples in these packs |
|---|---|---|---|---|
| **Fundamental** | A fact delivered by a source system or an upstream engine, at the grain that engine owns. Drishti cannot work it out from anything else it holds. | Source systems (legal, collateral, limits, booking), upstream pricing and simulation engines | **Yes** | a trade's `MTM`, `DV01`; a CSA's thresholds; a collateral holding's asset and direction; a limit amount; an exposure path; a P&L vector |
| **Derived** | A deterministic function of fields Drishti already holds: a sum, a count, a max, a difference, a ratio, a pick. Same inputs, same answer, no model, no parameters beyond a filter. | Drishti, from configuration: a Sutra expression (inside one document) or a derived kind (across documents) | **No** | netting-set net MtM = Σ trade MtM; utilisation = used ÷ limit; held collateral = Σ holdings' values; trade count |
| **Computed** | Needs an engine: scenarios, paths, quantiles, correlations, regulatory formulas with parameter tables, netting per path, any scope, any date, what-if, per-reader entitlements. | Drishti's engines (reactive connectors, Java or Python) | **No** (only as an immutable capture or a published official result) | VaR, ES, EE, PFE, EPE, SA-CCR EAD, SIMM IM, FRTB charge, CVA, expected margin call |

The contracts' four data kinds ([docs/contracts/README.md section 3](../contracts/README.md#3-four-kinds-of-data): fundamental,
input, drishti-owned, computed) map onto these three: *fundamental* and *input* are fundamental here; *drishti-owned* covers both
Drishti's derived records (positions) and users' definitions (portfolios, baskets); *computed* is computed here. This document adds the
**derived** class as its own step because it is cheaper than an engine and can be configured today.

### 1.2 The rule for deciding which

Ask the questions in order; the first "yes" decides.

| # | Question | If yes |
|---|---|---|
| 1 | Is it delivered by a system of record and not reproducible from anything Drishti holds (a term, an approval, a booked fact, a price, a trade-level value from an upstream engine)? | **Fundamental**: keep it stored, with a contract |
| 2 | Is it a pure function (sum, count, min, max, distinct, difference, ratio, pick) of fields of **one** document? | **Derived, in the Sutra** (Rachana-EL), never stored on the document |
| 3 | Is it a pure, additive function of fields of **several** documents of one kind grouped by a key (all trades of a netting set, all stress results of a scenario), shared alike by every reader? | **Derived kind** (`plugin: derived`) |
| 4 | Does it need any of: a non-additive statistic (quantile, square root of correlated sums, running max), a parameter table (supervisory factors, SIMM weights), per-path or per-scenario arithmetic, FX by scenario, a join across kinds with rules (CSA terms × collateral × MtM), an arbitrary scope (`DESK:X & CPTY:Y`), a what-if, or per-reader entitlements? | **Computed** by an engine |

Two corollaries:

- **A derived attribute over a computed input is computed.** `UTILISATION = USED ÷ LIMIT` is a division, but `USED` for a PFE limit is a
  quantile of summed paths, so the utilisation of a PFE limit belongs to the LIM engine. A Sutra may still show the division of two
  numbers already on the page as a *check* (section 8.3).
- **Membership is fundamental; totals are not.** Which trade is in which netting set is a legal fact (NSET.md section 3.3). The net MtM of
  the set is a derived total.

### 1.3 Why stored copies of derived or computed numbers cause inconsistency

| Failure | What happens | Seen in Drishti |
|---|---|---|
| **Two numbers for one thing** | The stored total and the total of the members differ, and the reader cannot tell which is right | `netting-set.LIMIT` differs from the counterparty's `credit-limit.LIMIT` on all 76 netting sets of the test data, and both are drawn as "the limit" (F7) |
| **Stale** | The members move and the stored total does not | In the live demo, `trade-valuation.MTM` and the netting set's `TRADES[].MTM` walk independently (`_meta.walk` in `config/packs/market-risk/samples/trade-valuation.jsonl` and `config/packs/counterparty-risk/samples/netting-set.jsonl`), so the net MtM drifts away from the trades it claims to sum (F2) |
| **Not reproducible** | Nobody can rebuild the number from its inputs, so nobody can explain it | `netting-set.UTILISATION` matches no ratio of any of the set's own fields on any of the 76 sets (F8) |
| **Masks and entitlements bypassed** | A stored total over trades the reader may not see leaks their sum | A stored `NET_MTM` is visible to anyone who can open the netting set, whatever trades they are entitled to; the VaR and exposure engines compute "as the caller" ([DATA_ARCHITECTURE.md › Reactive connectors, section 4](DATA_ARCHITECTURE.md#4-what-a-reactive-connector-is): inputs are read as the requesting user) |
| **Wrong method frozen in** | A stored figure fixes a method that the measure's definition forbids | the group utilisation adds PFE peaks of members (F17); SIMM's total adds risk-class margins without the ψ correlations (F20) |

The roadmap's own example: the stored `VAR-COMM` read 446.9m while the engine computed 1.97m for the same scope
([IMPLEMENTATION_PLAN.md, "The computed-measures roadmap"](IMPLEMENTATION_PLAN.md#the-computed-measures-roadmap-280-to-2120-product-owner-2026-10-25)).

---

## 2. How each class is built in Drishti today

Five mechanisms exist and are built. Each has a place; the error to avoid is using a heavier one than the attribute needs, or a lighter
one than it needs.

| Mechanism | Built example | Where it runs | When it refreshes | Scope | Entitlements | Limits | Cost |
|---|---|---|---|---|---|---|---|
| **Sutra expression** (Rachana-EL in a `bind:`, `title:`, `when:`) | `size($.DEFINITION.MEMBERS)` in `config/packs/market-risk/sutras/market-risk/portfolio.v1.sutra.yaml` (panel `definition`, "Members named"); `max($.TRADES[*].NOTIONAL)` in the netting-set Sutra's slider (`netting-set.v1.sutra.yaml` controls) | In the server, as the page is built from one document | Every time the page renders | One document only | The reader's, on the document already fetched | Closed function set: `size`, `sum`, `min`, `max`, `abs`, `first`, `last`, `flatten`, `group`, `coalesce`, `nonblank`, `days`, `today`, comparisons and arithmetic; no `sqrt`, `exp`, `round` ([RACHANA_REFERENCE.md, Functions](../guides/RACHANA_REFERENCE.md)) | Negligible |
| **Derived kind** (`plugin: derived`) | `config/packs/trading/pack.yaml` lines 191–240: `counterparty-trades` grouped by `$.COUNTERPARTY` from `trade`; also `gleif-groupings`, `us-equities-groupings`, `macro-groupings` | In the server (`drishti-engine/.../source/derived/DerivedSourcePlugin.java`), reading every member through the router | Once per business date and data version, else every `refresh` (trading: `60s`; default `30s`); a reader never waits for a rebuild (served stale, refreshed behind) | Fixed: one page per group key (plus `also-group-by` keys) | **Built once per date and shared by every reader** (`DerivedSourcePlugin.java` javadoc): not per-reader | `max-scan` 50,000 documents read per kind by default (`PACK_DEVELOPER_GUIDE.md` › Part VII. Groupings and analytics, computed, section 24); `max-members` caps the listed members and rows, not the aggregates (`DerivedKind.java` javadoc); aggregates `count`, `sum`, `avg`, `median`, `min`, `max`, `distinct`, `first`; `where`, `then`, `sort`, `tree`; no join to another kind | One pass over the kind per date; pages in heap or on disk |
| **Reactive engine** (`plugin: reactive`) | `var-engine` (`config/packs/market-risk/pack.yaml` lines 345–390, Java `VarResultEngine`), `var-whatif` (Python), `exposure-engine` (`config/packs/counterparty-risk/pack.yaml` lines 269–334, Java `ExposureResultEngine`), `exposure-whatif` (Python) | Java in the server or Python on the compute pools ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md)) | On request; cached (`results: 2000, ttl: 1h` for VaR); recomputed on `data-version` triggers of the listed input kinds, coalesced (`quiet: 250ms, max-wait: 2s` VaR; `1s / 10s` exposure) | Any scope in the scope grammar, any business date, parameters (`ccy`, `q`, `collateral`) | **As the requesting user** | `timeout: 60s`, `max-inflight-per-user` 4 (VaR, EXP) or 2 (what-if); exposure `max-scope-netting-sets` 20,000 (EXPOSURE_GUIDE.md section 18.1) | Memory-mapped hot files per date; milliseconds warm |
| **Engine figures on a stored page** | `VarFigures` puts the VaR engine's answer under `$.VAR_ENGINE` on the position and portfolio pages; `ExposureFigures` puts the exposure engine's under `$.EXPOSURE_ENGINE` on the netting-set and counterparty pages (`drishti-server/.../var/VarFigures.java`, `.../exposure/ExposureFigures.java`; EXPOSURE_GUIDE.md section 22) | Server, as the page opens | As the engine's cache | The page's own entity as a scope (`POS:`, `PORT:`, `NSET:`, `CPTY:`) | As the reader | As the engine | As the engine |
| **BI measure** | `config/bi/models/trading-risk.model.yaml`: `MTM: sum(trades.MTM)`, `Exposure (PFE peak): sum(cpty_exposure.PEAK_PFE_95)` with a "never a sum of PFEs" description | The BI engine over datasets (`refresh: { on-load: true }`) | On load of the dataset | Any slice of a dataset, for analysis | Dataset row filters and masks (`trading-risk-trades.dataset.yaml`: `desk-head` rows, `masked: [COUNTERPARTY]`) | Additive measures only are safe; a non-additive figure must come from an engine's published result, as the exposure measures do | Columnar scan |

**Where each class belongs:**

| Class | First choice | Second choice | Never |
|---|---|---|---|
| Fundamental | stored kind with a contract | — | worked out by a page |
| Derived within one document | Sutra expression | — | stored beside its inputs |
| Derived across documents | derived kind, bound by a panel's `source:` (as `config/packs/compute-demo/sutras/path-book.v1.sutra.yaml` binds `path-sum` with `$src`) | the measure's engine when per-reader totals or arbitrary scopes are needed | stored on a member or a parent record |
| Computed | reactive engine; engine figures on the stored page | BI only over the engine's published official results | stored on a fundamental record; summed in BI when non-additive |

**The real derived-kind pattern** (verbatim excerpt, `config/packs/trading/pack.yaml` lines 191–214):

```yaml
  trade-pages:
    plugin: derived
    kinds:
    - counterparty-trades
    - portfolio-trades
    - legal-entity-trades
    - index-trades
    settings:
      refresh: 60s
      store: disk
      counterparty-trades:
        from: trade
        group-by: $.COUNTERPARTY
        id-field: COUNTERPARTY
        members: TRADE_IDS
        max-members: 1000
        rows-field: TRADES
        fields:
          TRADE_COUNT: count
          NOTIONAL: sum first($.LEGS).NOTIONAL
          SOURCE_SYSTEMS: distinct $.SOURCE_SYSTEM
          PRODUCT_TYPES: distinct $.PRODUCT_TYPE
          CURRENCIES: distinct $.CURRENCY
          LAST_MATURITY: max $.MATURITY_DATE
```

(The `NOTIONAL` above sums first-leg notionals across currencies; its Sutra labels it honestly, "Notional (all currencies)",
`config/packs/trading/sutras/.../counterparty-trades.v1.sutra.yaml` line 40. Section 7.3 proposes a rule for this.)

---

## 3. Shared definitions used by every formula

These are the definitions the formulas in sections 4, 5 and 8 assume. Each is a **proposal** to adopt pack-wide; today they differ (F11, F12).

| Symbol | Definition (proposal) | Source of today's rule |
|---|---|---|
| *d* | the business date asked for (`asOf`), else the newest date present | [contracts README section 6](../contracts/README.md#6-dates-as-of-and-restatements) |
| **Live**(*t*, *d*) | `TRADE_STATUS` ∈ {`NEW`, `VALIDATED`, `AMENDED`} **and** `MATURITY_DATE` ≥ *d* (or empty) | the market-risk pack's `positions.live-statuses` (`config/packs/market-risk/pack.yaml` line 736) and its "has not matured" rule (comment, lines 730–734); NSET.md section 6.2 `drishti.ccr.live-statuses` |
| MTM(*t*, *d*) | `trade-valuation.MTM` of trade *t* on *d*, USD, dirty, bank's side, positive = asset | `config/packs/market-risk/contracts/trade-valuation.contract.yaml` (`MTM`, unit USD) |
| NS(*t*, *d*) | the netting set of *t* on *d*: today `trade-valuation.NETTING_SET`; once NSET is built, `netting-set-member` | NSET.md section 3.3 |
| FX(*a*→*b*, *d*) | the market-data pack's close mid for *d* | TVAL.md section 6.2 (`fx-rate`), COLL.md section 5 |
| Σ over a set | float64 accumulation; a member with no value is **counted as missing**, never as 0 | TVAL.md section 5 ("never taken as 0 MtM silently") |
| ccy(*x*) | **the currency every sum over *x* is made in** (product owner, decided): **USD by default**; configurable per entity, so a netting set (or counterparty, group, limit, desk…) can ask for its sums in CAD or any other currency. Every member is converted at FX(member currency → ccy(*x*), *d*) **before** it is added; a member that cannot be converted (no rate on *d*) is counted as missing, never added unconverted. See *Sum currency* below | decided by the product owner; replaces open question 1 |
| Fraction | a ratio is stored and passed as a fraction (0.667), formatted `pct` only on screen | `netting-set.contract.yaml` `UTILISATION` unit "fraction of the limit" |

### Sum currency: one strategy for every sum (decided)

The product owner's rule, for every sum or aggregate in both packs (net MtM, gross MtM, collateral, exposure, limits used, SA-CCR
components, stress P&L, P&L explain, notionals, counterparty and group totals):

1. **Default: USD.** The pack-wide default reporting currency for sums is USD (a setting, so a site can change the default itself).
2. **Per-entity override by configuration.** A configuration maps an entity to its sum currency, so a netting set, counterparty,
   group, credit limit, desk or any other aggregating entity can ask for its figures in CAD, EUR or any currency. Proposed shape
   (illustration, not applied):

   ```yaml
   sums:
     currency: USD                      # the default for every sum
     overrides:                         # by kind, then by id or by a rule
       netting-set:
         NS-MAPLE-TOR: CAD
         "where: $.LEGAL_ENTITY == 'LE-TOR'": CAD
       counterparty:
         CP-RHEINBANK: EUR
       credit-limit: { from-field: LIMIT_CURRENCY }   # or take it from a field the record carries
   ```

   Resolution order: an explicit id, then a rule, then a field on the record (`from-field`), then the default. The chosen currency is
   shown beside every total and in "how this was built".
3. **Convert, then add.** Each member is converted at FX(member currency → sum currency, *d*) from the market-data pack's close mid
   for the business date, then summed. Never add amounts in different currencies; never convert a total that was summed unconverted.
4. **Through the chain.** A parent sum converts its children from *their* currency into its own: a counterparty in USD whose netting
   set reports in CAD converts that set's CAD total at FX(CAD→USD, *d*), or (equivalently, and preferred for exactness) re-sums the
   members directly in USD. Ratios (utilisation = used ÷ limit) are taken with both amounts in the same currency first.
5. **Which mechanism.** An engine (NSET, LIM, GRP, COLL, STR, PNL…) converts naturally. A derived kind cannot today (it has no join
   to FX): until it gains a conversion step (a proposal: a `convert: {to: <sum currency>, rate: fx-spot}` option on derived fields),
   a derived total over members in more than one currency is labelled *not converted* and the converted figure comes from the engine.
6. **Reconciliation.** A computed total in a non-USD currency reconciles against the stored USD figure after converting at FX(*d*);
   the tolerance is set per attribute (section 9).

---

## 4. Market-risk pack, kind by kind

Legend for every table in sections 4 and 5:

- **Today:** *S* stored field on the record; *E* Sutra expression; *D* derived kind; *X* engine (reactive or engine figures); *—* not shown.
- **Should be:** *F* fundamental; *Dv* derived; *C* computed.
- **Priority:** **P1** wrong or inconsistent today, fix first; **P2** correct today but stored where it should be derived or computed;
  **P3** cosmetic or naming.
- **Grain / scope:** the grain the value belongs to, and the scopes it should be answerable for.

The pack has 16 kinds (`config/packs/market-risk/pack.yaml` lines 25–41) and 7 Sutras
(`config/packs/market-risk/sutras/market-risk/`). Kinds without a Sutra (pnl-vector, scenario-pv, fx-scenario, scenario-set,
risk-hierarchy, var-limit, var-result, var-whatif, var-official) open on the generic page; their fields are listed by role.

### 4.1 `trade-valuation` (TVAL)

Sutra `trade-valuation.v1.sutra.yaml` (strip lines 36–43, panel `valuation` lines 45–62); badge/impact measure `$.MTM`
(`pack.yaml` line 153); history columns `MTM, PNL_1D, DV01, NOTIONAL` (`pack.yaml` lines 719–720).

| Shown as | Field | Today | Should be | How | Grain / scope | Pri. |
|---|---|---|---|---|---|---|
| Trade, Source, Product, Group, Portfolio, Desk, Counterparty, Currency, Status, Maturity | `TRADE_ID`, `SOURCE_SYSTEM`, `PRODUCT_TYPE`, `PRODUCT_GROUP`, `PORTFOLIO`, `DESK`, `COUNTERPARTY`, `CURRENCY`, `TRADE_STATUS`, `MATURITY_DATE` | S | F (copies of the trade master) | validated against `trade` on *d* (contracts README section 10, "Keys resolve") | trade × date | P3 |
| MTM, MTM (USD) | `MTM` | S | **F** | upstream engine; the plan's boundary keeps trade-level MtM an input (`tools/contracts.policy.yaml` notes, `trade-valuation`) | trade × date | — |
| P&L 1D, P&L 1D (USD) | `PNL_1D` | S | F as delivered, **plus a derived check** | check: `PNL_1D(t,d) − (MTM(t,d) − MTM(t,d₋₁))`, shown beside it (TVAL.md section 6.3). On the test data the two agree on 201 of 15,000 trades on 2026-09-30 (F14) | trade × date | P2 |
| DV01, DV01 (USD) | `DV01` | S | F | upstream | trade × date | — |
| Notional | `NOTIONAL` | S | F | upstream; unit disputed (F10) | trade × date | P1 |
| Valued | `VALUATION_DATE` | S | F | must equal *d* (all 15,000 rows do) | trade × date | — |
| "(USD)" in the labels | literal in the Sutra (lines 59–61) | literal | Dv | `'MTM (' + $.MTM_CURRENCY + ')'` (E), from the stored `MTM_CURRENCY` (`pack.yaml` layout line 194) | — | P3 |
| *(not shown)* totals over a scope: MtM, positive, negative, gross, notional, DV01, counts | — | — (BI sums all rows, F12) | **C** (TVAL) | TVAL.md section 6.1: `MTM(S)=Σ_{t∈S} MTM_t`; `MTM_POSITIVE=Σ max(MTM_t,0)`; `MTM_NEGATIVE=Σ min(MTM_t,0)`; `NOTIONAL=Σ|NOTIONAL_t|`; counts valued, proxied, failed, missing. As a quick win, by netting set and counterparty as derived kinds (section 8.1) | any scope | P1 |

**How the TVAL totals are computed (proposal; TVAL.md section 6):**

| Aspect | Rule |
|---|---|
| Inputs | `trade-valuation` rows of *d* (`MTM`, `PNL_1D`, `DV01`, `NOTIONAL`, `TRADE_STATUS`, `MATURITY_DATE`, scope columns) |
| Mechanism | quick win: derived kinds keyed by `NETTING_SET`, `COUNTERPARTY`, `PORTFOLIO` (section 8.1); full: the TVAL Java engine for any scope (TVAL.md section 6.4) |
| When | derived kind: per date and data version; engine: on request, recomputed on the load signal of `trade-valuation` |
| Edge cases | cancelled and matured rows excluded by **Live**; a trade without a row on *d* counted `missing`; `MTM_CURRENCY` ≠ the reporting currency: converted at FX(*d*) (today every row is USD); masked trades: the derived kind cannot exclude them per reader (section 2), the engine can |
| Validation | Σ over the trades of a netting set equals the stored `netting-set.NET_MTM` on 76 of 76 sets today when **Live** omits the maturity test, and on 55 of 76 when it includes it (F11); the 21 differences are 827 member trades past their maturity date |

### 4.2 `position` (POS), derived by Drishti

Sutra `position.v1.sutra.yaml` (strip lines 41–48); contract class `drishti-owned`
(`config/packs/market-risk/contracts/position.contract.yaml`: "derived (never stored)"); served by `var-positions`
(`pack.yaml` lines 330–334, 459); rules `positions:` (`pack.yaml` lines 735–746). This kind is already the target pattern.

| Shown as | Field | Today | Should be | How | Grain / scope | Pri. |
|---|---|---|---|---|---|---|
| Direction | `DIRECTION` | Dv at load | Dv | `LONG` if `QUANTITY ≥ 0` else `SHORT` (position contract, nested `DIRECTION`) | position × date | — |
| Quantity | `QUANTITY` | Dv at load | Dv | `Σ_{t∈pos} QUANTITY_t` (signed) | position × date | — |
| Market value | `MARKET_VALUE` | Dv at load | Dv | `Σ_{t∈pos} PV_t` (contract: "the sum of the trades' present values"); equals Σ MTM of the same trades (PV = MTM on 1,772 of 1,772 trades checked, F13) | position × date | — |
| Trades | `TRADE_COUNT` | Dv at load | Dv | `count(t ∈ pos)`; `LONG_TRADES`, `SHORT_TRADES` likewise | position × date | — |
| Notional (gross) | `NOTIONAL` | Dv at load | Dv, **renamed** | `Σ |QUANTITY_t|` (contract: "the sum of the trades' quantities without their signs"): a gross quantity, not a notional (F15); label "Gross quantity" | position × date | P3 |
| VaR 99% 1D, ES 97.5%, Component VaR in its book, Book VaR, Share of the book's VaR, trade VaR table, distribution | `VAR_ENGINE.*` | X (`VarFigures`) | C | VAR_GUIDE.md section 18 | any scope | — |
| Trades table: Quantity, PV, Trade currency, Counterparty | `TRADES[]` | Dv at load | Dv | copied from each trade's vector row | trade | — |

### 4.3 `portfolio` (PORT)

Sutra `portfolio.v1.sutra.yaml` (strip lines 42–50); contract class `input`; stored definition only (`tools/contracts.policy.yaml`
`removed: portfolio`).

| Shown as | Field | Today | Should be | How | Pri. |
|---|---|---|---|---|---|
| Positions, Long, Short, Gross MV, Net MV | `VAR_ENGINE.POSITION_COUNT`, `LONG_COUNT`, `SHORT_COUNT`, `GROSS_MV`, `NET_MV` | X | C (scope resolution needs the engine; the arithmetic is additive) | `GROSS_MV = Σ |MARKET_VALUE_p|`, `NET_MV = Σ MARKET_VALUE_p` over the resolved positions (VAR_GUIDE.md section 11) | — |
| VaR 99% 1D, ES 97.5%, components, distribution, ranked bars | `VAR_ENGINE.*` | X | C | VAR_GUIDE.md section 18 | — |
| Limit use | `VAR_ENGINE.LIMIT_USE` | X | C | `VAR ÷ LIMIT_VAR`, a fraction (`var-official.contract.yaml` `LIMIT_USE`: "VAR / LIMIT") | P3 (name, F9) |
| Type, Owner, Node, Rule, Rolls up to, Valid from | `TYPE`, `OWNER`, `DEFINITION.*`, `ROLLS_UP_TO.ID`, `VALID_FROM` | S | F | user definition | — |
| Members named | `size($.DEFINITION.MEMBERS)` | E | Dv | as is | — |
| VaR limit | `LIMIT_VAR` | S | F | the third home for VaR limits (F6) | P2 |

### 4.4 Engine inputs without a Sutra: `pnl-vector`, `scenario-pv`, `fx-scenario`, `scenario-set`

| Kind | Fields | Today | Should be | Note | Pri. |
|---|---|---|---|---|---|
| `pnl-vector` | `PNL[]`, `PV`, `QUANTITY`, `DIRECTION` | S | F | the engine input (VAR_GUIDE.md section 15.2). `PV` equals `trade-valuation.MTM` (F13). Option PVs break the sign convention on the test data (F21) | P1 (signs) |
| `pnl-vector`, `scenario-pv` | `DESK`, `TT_BOOK`, `LIMIT_LETTER`, `PORTFOLIO`, `COUNTERPARTY`, `PRODUCT_*`, `LEGAL_ENTITY`, `TRADE_STATUS`, `MATURITY_DATE` | S (denormalised) | F copies, validated | must equal the trade master on *d*; `TRADE_STATUS` already differs from `trade-valuation` in the samples (`MX-20000274`: `AMENDED` in `samples/pnl-vector.jsonl`, `VALIDATED` in `samples/trade-valuation.jsonl`) | P2 |
| `scenario-pv` | `BASE_PV`, `SCENARIO_PV[]` (trade currency) | S | F | P&L in reporting currency *r* is computed: `P&L_r(s) = PV(s)·FX_c(s)/FX_r(s) − PV(base)·FX_c(base)/FX_r(base)` (VAR_GUIDE.md section 15.10) | — |
| `fx-scenario` | `RATE`, `RATES[]` | S | F | engine input | — |
| `scenario-set` | `SCENARIO_DATES[]` | S | F | engine input, defines vector order | — |

### 4.5 `risk-hierarchy` (HIER) and `var-limit` (VLIM)

| Field | Today | Should be | How | Pri. |
|---|---|---|---|---|
| `NODE_ID`, `HIERARCHY`, `LEVEL`, `PARENT_ID`, `NAME`, `REF` | S | F | — | — |
| `CHILDREN[]` | S | Dv | `{n : PARENT_ID(n) = node}`; VAR_GUIDE.md section 15.4 already calls it informational ("the engine uses `PARENT_ID`") | P3 |
| `BOOKS[]` ("every book beneath the node") | S | Dv | union of the leaf nodes' books under the node | P3 |
| `risk-hierarchy.LIMIT_VAR` (MGMT, LEGAL) | S | F, **one home** | a limit is a fact; but it lives in three kinds (F6) | P2 |
| `var-limit.LIMIT_VAR`, `CURRENCY` (LL) | S | F | the proposed single home for every VaR limit, with a currency | P2 |

### 4.6 `var-result` (VRES), `var-whatif` (VWIF), `var-official` (VOFF)

All **computed** and built: `var-result` by the Java `VarResultEngine` (`pack.yaml` lines 345–390), `var-whatif` by the Python engine
(lines 391–452), `var-official` the published official captures (lines 302–329), read only. They are the reference pattern for every
other measure in this document: computed when asked, provenance on every answer (VAR_GUIDE.md section 11.2, "How this number was built"),
captures, an official end-of-day record that engines never read back.

| Field | Note | Pri. |
|---|---|---|
| `VAR`, `ES`, `PNL[]`, contributions | C, VAR_GUIDE.md section 18 | — |
| `LIMIT`, `LIMIT_USE` | `LIMIT_USE = VAR ÷ LIMIT` (fraction). Same idea as CCR's `UTILISATION` under another name (F9) | P3 |
| `CURRENCY` | the reporting currency parameter `ccy` (`var-result.contract.yaml` line 63) | — |

### 4.7 `stress-scenario` (SCN)

Sutra `stress-scenario.v1.sutra.yaml`. Everything is **fundamental** (`NAME`, `TYPE`, `SEVERITY`, `SHOCKS[].FACTOR`, `SHOCKS[].SHOCK`,
`HORIZON`); STR.md keeps SCN fundamental. One gap, P2: `SHOCKS[].SHOCK` has no unit field (the sample mixes `-0.34` for equities, `1.35`
for credit spreads, `45.0` for equity vol: `samples/stress-scenario.jsonl`); STR.md section 3.3 needs units to apply shocks to
sensitivities.

### 4.8 `stress-result` (STR)

Sutra `stress-result.v1.sutra.yaml` (strip lines 34–37, panel `books`).

| Shown as | Field | Today | Should be | How | Grain / scope | Pri. |
|---|---|---|---|---|---|---|
| Scenario | `SCENARIO_NAME` | S | Dv (a copy) | `link($.SCENARIO, 'stress-scenario')` and the scenario's `NAME`; a copy of `stress-scenario.NAME` | — | P3 |
| P&L | `PNL` | S | **C** (STR) | full revaluation: `STR(S, j) = Σ_{t∈S} PNL_{t,j}` from per-trade scenario P&L; or by sensitivities `Σ_k δ_k x_k + ½ Σ_k Γ_k x_k² + Σ_k ν_k r_k` (STR.md sections 6.1–6.2). Today the stored total equals `Σ BY_BOOK[].PNL` on 80 of 80 records | desk × scenario today; any scope × any scenario | P2 |
| P&L by book | `BY_BOOK[]` | S | C | the same, by `TT_BOOK` | book × scenario | P2 |
| Limit | `LIMIT` | S | F (the limit), with **sign fixed** | stored **negative** on 80 of 80 records (`-75,700,000`, `stress-result.contract.yaml` line 74), while STR.md section 5 and every other limit in both packs are positive (F5) | desk × scenario | P1 |
| *(not shown)* limit use, breach | — | — | Dv / C | `LIMIT_USE = max(−PNL, 0) ÷ |LIMIT|` (STR.md section 5); `BREACH = LIMIT_USE > 1`; 22 of 80 records are in breach today and the page does not say so | desk × scenario | P1 |
| *(not shown)* bank or division total per scenario | — | — | Dv now, C later | `Σ_{desk} PNL(desk, j)` (additive): derived kind `stress-scenario-pnl` (section 8.1) | scenario | P2 |

**STR computation card (proposal):** inputs `stress-result` today, later per-trade scenario P&L and `sensitivity` rows; mechanism derived
kind now, STR engine later (STR.md section 6); when per date and on the load signal; edge cases: a desk with no result for a scenario is
*missing*, not 0; a scenario defined after the run has no full-revaluation figure and falls back to sensitivities only when asked
(`fallback=sensitivity`, STR.md section 6.4); validation: derived bank total against Σ of the stored desk rows (exact by construction),
then the STR engine's full revaluation against the stored `PNL` per desk with the difference shown.

### 4.9 `frtb-sensitivity` (FRTB)

Sutra `frtb-sensitivity.v1.sutra.yaml` (strip lines 35–40).

| Shown as | Field | Today | Should be | How | Grain / scope | Pri. |
|---|---|---|---|---|---|---|
| Book | `BOOK` | S | F | — | — | — |
| Sensitivities table | `SENSITIVITIES[]` (`RISK_CLASS`, `BUCKET`, `MEASURE`, `VALUE`) | S | F, **at trade grain** | per-trade sensitivities in the lake (FRTB.md section 3); today per book and bucket | trade × factor | P2 |
| Delta charge, Vega charge, Curvature | `DELTA_CHARGE`, `VEGA_CHARGE`, `CURVATURE_CHARGE` | S | **C** (FRTB SBM, BCBS MAR21) | `WS_k = RW_k · s_k`; `K_b = sqrt(max(0, Σ WS_k² + ΣΣ ρ_kl WS_k WS_l))`; `S_b = Σ WS_k`; charge `= sqrt(Σ K_b² + ΣΣ γ_bc S_b S_c)` with the alternative `S_b` when negative; curvature from up/down revaluations (FRTB.md sections 6.1–6.4) | any scope | P1 (F18) |
| SBM total | `TOTAL` | S | **C** | `SBM = max_{c∈{low,medium,high}} Σ_classes (Delta^c + Vega^c + Curvature^c)` (MAR21.6–7; FRTB.md section 6.5). Today `TOTAL = DELTA + VEGA + CURVATURE` on 30 of 30 (one scenario only), and `CURVATURE_CHARGE` is non-zero while no record has a curvature row (F18) | any scope | P1 |
| Charge by risk class | `BY_CLASS[]` | S | C | per class, binding scenario | any scope | P2 |

**Never derived:** FRTB charges are not additive across books or desks (square roots of correlated sums). A derived kind may add the
**net sensitivities** per factor across books (additive, `then: group(flatten($.ROWS[*].SENSITIVITIES), 'BUCKET', 'VALUE')`), never the
charges. A BI measure over charges must not use `sum`.

### 4.10 `pnl-explain` (PNL)

Sutra `pnl-explain.v1.sutra.yaml` (strip lines 35–39, waterfall lines 42–52 with `unit: USD`).

| Shown as | Field | Today | Should be | How | Grain / scope | Pri. |
|---|---|---|---|---|---|---|
| Date | `DATE` | S | F | — | — | — |
| Actual | `ACTUAL` | S | **C** (PNL) | per trade: continuing `MTM_T − MTM_{T−1} + Σ cash`; new `MTM_T + upfront + fees`; ended `Σ cash − MTM_{T−1}` (PNL.md section 6.1). The contract's review point asks the same (`tools/contracts.policy.yaml` review `pnl-explain`) | any scope | P2 |
| Explained | `EXPLAINED` | S | C | `Σ_class Σ_k δ_k x_k + ½ Σ γ_k x_k² + Σ ν_k r_k + θ (+ amendments + fees)` (PNL.md section 6.2) | any scope | P2 |
| Unexplained | `UNEXPLAINED` | S | Dv of two computed | `ACTUAL − EXPLAINED`; tolerance `|U| ≤ max(10,000, 10% |ACTUAL|)` (PNL.md section 6.4) | any scope | P2 |
| Attribution bars | `ATTRIBUTION[]` | S | C | by factor class | any scope | P1 (F19) |
| P&L waterfall | `EXPLAIN_STEPS[]` | S | C, **from the same components as Attribution** | the waterfall and the bars must be two views of one decomposition; today they disagree on "New trades" on 30 of 30 records (F19) | any scope | P1 |
| "USD" in the waterfall | literal (`unit: USD`) | literal | Dv | from a currency field the record does not carry (F10) | — | P3 |

---

## 5. Counterparty credit risk pack, kind by kind

The pack has 14 kinds (`config/packs/counterparty-risk/pack.yaml` lines 24–38) and 10 Sutras in three folders. The core pack's
`counterparty` and `counterparty-group` carry counterparty-risk figures too and are included (5.11, 5.12).

### 5.0 The dependency chain (as it should be; proposal)

```
 FUNDAMENTAL                                DERIVED / COMPUTED                                   CONSUMERS
 trade-valuation.MTM ─────┐
 netting-set-member ──────┼──▶ NSET: NET_MTM, GROSS ±, CE_UNCOLL ──▶ counterparty ──▶ group ──▶ pages, BI
 netting-set (terms) ─────┤                 │
 csa (terms) ─────────────┼──▶ MC: expected call ◀──────────────────┤
 collateral holdings ─────┴──▶ COLL: MV, value after haircut ───────┤
 market data (prices, FX) ─────────▲                                ▼
 exposure-path, simulation-run ───────────────────▶ EXP: EE, ENE, PFE, EPE, EEPE ──▶ LIM: used, utilisation, headroom, status ──▶ GRP
 saccr-trade (trade terms) ───────────────────────▶ SACCR: RC, add-ons, multiplier, EAD ──▶ LIM (EAD limits), BA-CVA
 credit curves, discount curve ───────────────────▶ CVA: CVA, DVA, FVA, CS01 (from EXP's EE, ENE)
 sensitivities (CRIF) ────────────────────────────▶ SIMM: IM collect / post ──▶ MC (IM call)
 credit paths (optional) ─────────────────────────▶ WWR (from EXP and hazards)
```

**Today the chain runs the other way in one place:** the built exposure engine reads the current MtM and the membership from the stored
`netting-set.TRADES[]` (`drishti-server/.../exposure/ExposureLoader.java` lines 341–351), the collateral from the stored
`collateral-balance.NET` (line 398, a pre-haircut figure, F3) and the limit from the stored `credit-limit.LIMIT` (lines 385–391). EXPOSURE_GUIDE.md
section 18.4 records this ("CE uses each set's value today, the sum of its member trades' MtM in the `netting-set` record"). Retiring the
stored netting-set figures (NSET) therefore needs the exposure engine re-pointed at `trade-valuation` and `netting-set-member` first.

### 5.1 `netting-set` (NSET)

Sutra `exposure-and-capital/netting-set.v1.sutra.yaml` (strip lines 42–51, exposure chart lines 56–68 with `limit: $.LIMIT` line 64,
trades panel, `byAsset`); badge `'net ' + fmt($.NET_MTM, 'compact')` and impact measure `$.NET_MTM` (`pack.yaml` lines 147, 154); history
columns `NET_MTM, COLLATERAL, LIMIT, UTILISATION, CVA, TRADE_COUNT` (`pack.yaml` line 676). Contract `netting-set.contract.yaml`: class
fundamental, source `xva-engine`. Plan of record: `tools/contracts.policy.yaml` `parked.split.netting-set` (figures `NET_MTM, COLLATERAL,
UTILISATION, CVA, BY_ASSET`); NSET.md section 1 removes also `TRADE_COUNT`, `TRADES[]`, `LIMIT`.

| Shown as | Field | Today | Should be | How (formula) | Inputs (kind.field, grain) | Pri. |
|---|---|---|---|---|---|---|
| Trades | `TRADE_COUNT` | S | **Dv** | `count{t : NS(t,d) = ns ∧ Live(t,d)}` | `trade-valuation.NETTING_SET, TRADE_STATUS, MATURITY_DATE` (trade) | P1 |
| Net MTM (strip, badge, impact, history) | `NET_MTM` | S | **Dv** (quick win), then C (NSET, any scope) | `NET_MTM(ns,d) = Σ_{t: NS(t,d)=ns, Live(t,d)} MTM(t,d) · FX(MTM_CURRENCY_t → ccy(ns), d)` with ccy(ns) USD unless configured (section 3, *Sum currency*); today every `MTM_CURRENCY` is USD (15,000 of 15,000), so FX = 1 | `trade-valuation.MTM, MTM_CURRENCY` | P1 |
| *(new)* Gross positive / negative | — | — | Dv | `GROSS_POS = Σ max(MTM_t, 0)`, `GROSS_NEG = Σ min(MTM_t, 0)`; `NETTING_BENEFIT = GROSS_POS − max(NET_MTM, 0)`; `NGR = max(NET_MTM,0) ÷ GROSS_POS` (0 if `GROSS_POS` = 0) (NSET.md section 6.1) | same | P2 |
| Collateral (+ held, − posted) | `COLLATERAL` | S (= `collateral-balance.NET` on 76 of 76) | **C** (COLL) | VM: `C(ns) = Σ_{h∈held,VM} MV_h(1 − h_h) − Σ_{h∈posted,VM} MV_h(1 − h_h)` (contractual value, COLL.md section 6.5); quick win: `HELD_VALUE − POSTED_VALUE` from the balance's own positions (section 8.3) | `collateral-balance.POSITIONS[]` (holding); later `collateral-holding` + prices + `csa.ELIGIBLE` | P1 (F3) |
| *(new)* Current exposure | — | X (`EXPOSURE_ENGINE.CE`) | C | `CE(ns) = max(NET_MTM − C − IM_held, 0)`; never netted across sets (EXPOSURE_GUIDE.md section 18.4) | NSET, COLL | — |
| EPE (engine), PFE 95 peak (engine), exposure chart, "How the exposure was computed" | `EXPOSURE_ENGINE.*` | X | C (EXP, built) | `E(p,k) = max(V(p,k) − C(p,k) − IM, 0)`; `EE(k) = (1/P)Σ_p E(p,k)`; `PFE_q(k)` = the ⌈P·q⌉-th smallest `E(·,k)`; `EEE(k) = max(EEE(k−1), EE(k))`; `EPE = Σ_{t_k≤T} EE(k)Δt_k / T`; `EEPE` likewise on EEE; T = min(1y, longest maturity) (EXPOSURE_GUIDE.md sections 18.2–18.4; CRE53 for EEPE) | `exposure-path.MTM[]` (trade × grid date × path), `simulation-run`, `csa`, collateral | — |
| Limit (strip, chart line) | `LIMIT` | S | **remove**; read the limit from LIM | the set has no limit of its own in LIM.md: limits have a scope (counterparty, netting set, group); a netting-set limit, if wanted, is a `credit-limit` row scoped `NSET:` | `credit-limit` | P1 (F7) |
| Utilisation | `UTILISATION` | S | **C** (LIM) | `USAGE ÷ LIMIT` with USAGE per the limit's `MEASURE` (LIM.md section 6.2); not reproducible today (F8) | LIM, EXP | P1 |
| CVA | `CVA` | S (= `cva.CVA` on 76 of 76) | **C** (CVA) | `CVA = −LGD_C · Σ_k EE*(t_k) · ΔPD_C(k)`, `EE* = EE·DF` (CVA.md section 6.3); on the page: `EXPOSURE_ENGINE`-style figures from the CVA engine, never a stored copy | EXP, credit curve, discount curve | P2 (duplicate, F16) |
| Member trades table (Trade, Status, Product, Notional, Maturity, MTM) and pivot | `TRADES[]` | S | **Dv** | rows of the derived kind `nset-mtm` (section 8.1) from `trade-valuation`; `STATUS` from `TRADE_STATUS` (not `LIVE`, F11); `PRODUCT` from `PRODUCT_TYPE` (F22) | `trade-valuation` | P1 |
| `MATURITY_BUCKET` (pivot) | `TRADES[].MATURITY_BUCKET` | S | Dv | from `days(d, MATURITY_DATE)` into one bucket set used by every page (F23) | — | P2 |
| Net MTM by asset class | `BY_ASSET[]` | S | Dv | `group($.TRADES, 'PRODUCT_GROUP', 'MTM')` in the derived kind's `then` | `trade-valuation.PRODUCT_GROUP` | P2 |
| Links: counterparty, agreement, CSA | `COUNTERPARTY`, `AGREEMENT`, `CSA`, `LEGAL_ENTITY`, `NETTING_ENFORCEABLE` | S | **F** (terms) | kept (NSET.md section 3.2) | — | — |
| Links to results | `CREDIT_LIMIT`, `CVA_RESULT`, `SACCR`, `SIMM`, `COLLATERAL_BALANCE` | S | Dv (by scope) | the page opens `LIM`, `CVA`, `SACCR`, `SIMM`, `COLL` at scope `NSET:<id>` (NSET.md section 1) | — | P3 |

**NET_MTM computation card (proposal):**

| Aspect | Rule |
|---|---|
| Formula | `NET_MTM(ns,d) = Σ_{t ∈ M(ns,d)} MTM(t,d)` in the netting set's currency, `M(ns,d) = {t : NS(t,d) = ns ∧ Live(t,d)}` |
| Mechanism | now: derived kind `nset-mtm` (section 8.1), shown on the netting-set page through a panel `source:`; later: the NSET engine's `nset-figures` for any scope, as the reader (NSET.md section 6.3) |
| When | derived kind: per business date, rebuilt when `trade-valuation`'s data version moves, else every `refresh`; intraday: a new valuation load (or a live tick in the demo) changes the version and the page follows within one `refresh` |
| Missing members | a member in `netting-set-member` with no valuation row on *d*: counted in `MISSING`, total flagged *incomplete*; never 0 |
| Mixed currencies | sum only after conversion to the set's sum currency (USD unless configured; section 3, *Sum currency*); today all `MTM_CURRENCY` are USD so the sum is exact; a derived kind cannot convert (no join to `fx-rate`), so a non-USD `MTM_CURRENCY` must make the page say *not converted* (`distinct $.MTM_CURRENCY` with more than one value) |
| Masked or restricted trades | the derived kind is built once for all readers: it must only be shown to roles that may see the whole set (as the BI model restricts `netting_sets` by role); per-reader totals need the NSET engine |
| Cancelled, matured | excluded by **Live**; whether a trade past its maturity date with a live status counts is open question 3 (827 such members today) |
| Dates without data | no `trade-valuation` rows on *d*: no page, "no valuations for *d*", never yesterday's numbers under today's date |
| Validation | against the stored `NET_MTM`: equal on 76 of 76 sets with **Live** = status only; 55 of 76 with the maturity test (section 9.3) |

### 5.2 `agreement` (AGR)

Sutra `counterparty-and-legal/agreement.v1.sutra.yaml`. Every attribute (`TYPE`, `COUNTERPARTY_NAME`, `GOVERNING_LAW`, `SIGNED`,
`CLOSE_OUT_NETTING`, `ELECTIONS`) is **fundamental**. One derived copy: `COUNTERPARTY_NAME` duplicates the counterparty master's `NAME`
(P3: show `link($.COUNTERPARTY, 'counterparty')` and let the link label carry the name). `form:` in `pack.yaml` line 427 types
`COUNTERPARTY_NAME` as `number` (P3, a generator slip; also `credit-limit` `COUNTERPARTY_NAME` line 490 and `exposure-path` `COUNTERPARTY` line 623).

### 5.3 `csa` (CSA)

Sutra `collateral-and-margin/csa.v1.sutra.yaml`; badge `'threshold ' + fmt($.THRESHOLD_THEM, 'compact')` (`pack.yaml` line 146).

| Shown as | Field | Today | Should be | Note | Pri. |
|---|---|---|---|---|---|
| Margin, Threshold (us), Threshold (them), MTA, IA, Frequency, MPoR, eligible collateral and haircuts | `MARGIN_TYPE`, `THRESHOLD_US`, `THRESHOLD_THEM`, `MTA`, `INDEPENDENT_AMOUNT`, `FREQUENCY`, `MPOR`, `ELIGIBLE[]` | S | **F** | terms. Gaps for the engines (P1 before MC and SACCR): no `CSA_CURRENCY`; one `MTA` for both sides (the exposure engine falls back to it for `MTA_THEM`/`MTA_US`, `ExposureLoader.java` lines 404–405); `MPOR` is text ("10 business days"); no `ROUNDING`; `FREQUENCY` text | P1 |
| Linked netting sets, collateral balances | `NETTING_SETS[]`, `COLLATERAL_BALANCES[]` | S | Dv | the inverse of `netting-set.CSA` and `collateral-balance.CSA`; stored lists drift. In the test data one CSA per counterparty lists four netting sets in four legal entities (`samples/csa.jsonl`), open question 9 | P2 |

### 5.4 `collateral-balance` (COLL)

Sutra `collateral-and-margin/collateral-balance.v1.sutra.yaml` (description lines 19–23 "after haircuts"; strip lines 35–39; positions
table with `MARKET_VALUE`, `HAIRCUT`, `VALUE`). Plan of record: `parked.split.collateral-balance` (figures `HELD, POSTED, NET,
POSITIONS[].MARKET_VALUE, POSITIONS[].VALUE`).

| Shown as | Field | Today | Should be | How (formula) | Pri. |
|---|---|---|---|---|---|
| Asset, Direction | `POSITIONS[].ASSET`, `DIRECTION` | S | **F** (holding) | plus quantity, ISIN, currency, margin type in `collateral-holding` (COLL.md section 3) | — |
| Market value | `POSITIONS[].MARKET_VALUE` | S | **C** (COLL) | `MV = QUANTITY × PRICE/100 × FX` (bonds), `QUANTITY × PRICE × FX` (equities), `QUANTITY × FX` (cash) (COLL.md section 6.2) | P2 |
| Haircut | `POSITIONS[].HAIRCUT` | S | Dv (lookup) | the matching rule of `csa.ELIGIBLE[]` (COLL.md section 6.3); the contract asks the same (`tools/contracts.policy.yaml` review `collateral-balance`) | P2 |
| Collateral value | `POSITIONS[].VALUE` | S | Dv | `VALUE = MV × (1 − h)` when eligible, 0 otherwise; holds on every position today | P2 |
| Held (we hold) | `HELD` | S | **Dv** | **after haircut** `Σ_{Held} VALUE` (contractual, COLL.md section 6.5). Today `HELD = Σ MARKET_VALUE` (before haircut) on 76 of 76 (F3) | P1 |
| Posted (we posted) | `POSTED` | S | **Dv** | `Σ_{Posted} VALUE`; same defect | P1 |
| Net (held − posted) | `NET` | S | Dv | `HELD − POSTED` after haircut; VM, IM and IA kept apart (COLL.md section 5) | P1 |
| *(new)* Regulatory value | — | — | C | `REG_HELD = MV(1 − h_sup − h_fx)`, `REG_POSTED = MV(1 + h_sup + h_fx)` (CRE22 supervisory haircuts; `h_fx = 8% · sqrt(MPOR/10)`), feeding SA-CCR's C and NICA (COLL.md section 6.3) | P2 |
| As of | `AS_OF` | S | F | must equal *d* | — |

Across the 76 balances the stored totals exceed the after-haircut values by 74.8 million USD in total (Σ MV 6,304.4m, Σ VALUE 6,229.6m).

### 5.5 `margin-call` (MC)

Sutra `collateral-and-margin/margin-call.v1.sutra.yaml` (strip lines 35–41, workflow ladder). Plan of record: actual calls stay
fundamental; the expected call is computed beside them (`tools/contracts.policy.yaml` notes `margin-call`; MC.md).

| Shown as | Field | Today | Should be | How (formula) | Pri. |
|---|---|---|---|---|---|
| Date, Type, Direction, Amount, Status, Dispute, workflow | `CALL_DATE`, `MARGIN_TYPE`, `DIRECTION`, `AMOUNT`, `STATUS`, `DISPUTE_AMOUNT`, `EVENTS[]` | S | **F** (the actual call) | kept | — |
| *(new)* Expected call | — | — | **C** (MC) | with E = NET_MTM in CSA currency (valuation of *d* − lag), B = VM held − posted after haircut, IA: `CSA_AMOUNT = max(E + IA − TH_them, 0)` if `E + IA ≥ 0`, else `min(E + IA + TH_us, 0)`; `Δ = CSA_AMOUNT − B`; split into return (rounded down) and delivery (rounded up); each leg 0 if below the transferor's MTA (MC.md section 6.2). IM: `max(IM_COLLECT − IM_THRESHOLD, 0) − IM_HELD` (MC.md section 6.3, SIMM) | P1 |
| *(new)* Signed amount | — | — | Dv | `+AMOUNT` when `DIRECTION = 'We call'`, `−AMOUNT` when `'They call'` (MC.md section 5) | P3 |
| *(new)* Match status | — | — | C | `MATCH` within tolerance `max(1 rounding unit, 0.5% × expected)`, else `DIFFERENCE`, `NOT_CALLED`, `UNEXPECTED`, `DIRECTION_MISMATCH`, `DISPUTED` (MC.md section 6.4) | P2 |

A rough preview on the test data (expected Δ without rounding or lag, stored net MtM and pre-haircut balance): on the 76 calls dated
2026-09-30 the stored call's direction agrees with the expected direction on 41 and is the opposite on 35. Example: `MC-ALDERSHOT-FRA-1`
"They call" 1,215,000 while E = −22,673,015, B = −15,328,015 and TH_us = 10,000,000 give Δ = +2,655,000, a return to the bank
(with the balance after haircut of 2.9.7, B = −15,251,373 and Δ = +2,578,358).

### 5.6 `credit-limit` (LIM)

Sutra `exposure-and-capital/credit-limit.v1.sutra.yaml` (strip lines 35–41; buckets form with `used` read-only, reason "measured by the
exposure engine"); badge `fmt($.UTILISATION, 'pct0') + ' used'` (`pack.yaml` line 148). Plan of record: `parked.split.credit-limit`
(figures `USED, UTILISATION, STATUS, BUCKETS[].USED, BUCKETS[].UTILISATION`).

| Shown as | Field | Today | Should be | How (formula) | Pri. |
|---|---|---|---|---|---|
| Counterparty | `COUNTERPARTY_NAME` | S | Dv (copy) | the master's name via the link | P3 |
| Measure, Limit, bucket limits | `MEASURE`, `LIMIT`, `BUCKETS[].BUCKET`, `BUCKETS[].LIMIT` | S | **F** (approved) | kept, with approval and review dates | — |
| Used (bucket) | `BUCKETS[].USED` | S ("measured by the exposure engine" says the form; it is stored by `limits-system`) | **C** (LIM over EXP) | for `MEASURE = PFE`: `USAGE_b = max_{t_k ∈ b} PFE_q,S(t_k)`, S the limit's scope, paths of its netting sets summed before the quantile (LIM.md section 6.2) | P1 |
| Used | `USED` | S (= max bucket `USED` on 24 of 24) | C | `max_b USAGE_b` for PFE; for `SACCR_EAD` Σ EAD; for `CE` `Σ_n max(MtM_n − C_n, 0)` | P1 |
| Utilisation (strip, bars, badge) | `UTILISATION`, `BUCKETS[].UTILISATION` | S (= `USED ÷ LIMIT` on 24 of 24) | C (Dv of two figures, one computed) | `UTIL_b = USAGE_b ÷ LIMIT_b`, a fraction | P1 |
| Status | `STATUS` ("Within limit", "Early warning", "Breach") | S | Dv | `OK` if `UTIL < warning`, `WARNING` if `≤ 1`, `EXCESS_APPROVED` if within a temporary excess, else `BREACH`; worst bucket wins (LIM.md section 6.3). Names differ from LIM.md and STR (F9) | P2 |
| *(new)* Headroom, binding bucket | — | — | Dv | `HEADROOM_b = LIMIT_b − USAGE_b`; `HEADROOM = min_b`; the bucket attaining it | P2 |

The plan records that the stored limits "were sized on the retired profiles, so their use reads very low against the computed PFE until
LIM is brought back" (IMPLEMENTATION_PLAN.md, the EXP paragraph): the stored `USED` is already known to disagree with the built engine.

### 5.7 `sa-ccr` (SACCR)

Sutra `exposure-and-capital/sa-ccr.v1.sutra.yaml` (strip lines 35–40, add-ons bars, hedging-sets table). Plan of record:
`parked.retiring.sa-ccr` (every figure). Regulatory source: **BCBS 279, *The standardised approach for measuring counterparty credit risk
exposures*, as consolidated in the Basel Framework CRE52**; restated in SACCR.md sections 6.2–6.7.

| Shown as | Field | Today | Should be | How (formula, CRE52) | Pri. |
|---|---|---|---|---|---|
| RC | `REPLACEMENT_COST` | S | **C** | unmargined `RC = max(V − C, 0)`; margined `RC = max(V − C, TH + MTA − NICA, 0)`. V = Σ MtM of in-scope derivatives (SFTs excluded), C = haircut value of net collateral (regulatory haircuts), NICA = net independent collateral amount | P1 |
| Add-on, add-on by asset class | `ADD_ON`, `ADD_ONS[]` | S | C | per trade `D_i = δ_i · d_i · MF_i` with `d_i = notional × SD_i` (IR, credit), `SD = (e^{−0.05 S} − e^{−0.05 E})/0.05`; IR per hedging set (currency) `EN = sqrt(D1² + D2² + D3² + 1.4 D1D2 + 1.4 D2D3 + 0.6 D1D3)`, `AddOn_IR = Σ_h SF · EN_h`; FX `Σ_h SF · |Σ D|`; credit and equity `sqrt((Σ ρ_k AddOn_k)² + Σ (1 − ρ_k²) AddOn_k²)`; commodity per hedging set with ρ = 0.4; `AddOn_agg = Σ classes` (SACCR.md sections 6.4–6.6) | P1 |
| Hedging sets table | `HEDGING_SETS[]` (`EFFECTIVE_NOTIONAL`, `SF`) | S | C | the EN and SF above | P2 |
| Multiplier | `MULTIPLIER` | S | C | `min(1, f + (1 − f) · exp((V − C) / (2(1 − f) AddOn_agg)))`, f = 5% | P1 |
| EAD | `EAD` | S | C | `EAD = α · (RC + multiplier · AddOn_agg)`, α = 1.4; margined EAD capped at the unmargined EAD | P1 |
| RWA | `RWA` | S | C (hook) | `EAD × RW(counterparty)`; informational, the bank's capital engine owns RWA (SACCR.md section 6.7) | P3 |
| *(not shown)* `ALPHA` | `ALPHA` (per record, 1.4) | S | **setting** | `drishti.ccr.saccr.alpha` (the contract's review point, `tools/contracts.policy.yaml`) | P3 |

Today's stored records are internally consistent: `EAD = α(RC + multiplier · ADD_ON)`, `RC = max(NET_MTM − COLLATERAL, 0)`,
the multiplier formula and `ADD_ON = Σ ADD_ONS[]` hold on 76 of 76. They are **not consistent with the trades**: the add-on asset
classes match the asset classes of the set's trades on 7 of 76 sets (`NS-ALDERSHOT-FRA` holds only securities financing trades and shows an
interest-rate add-on), and C is the pre-haircut balance (F3, F24).

**Computation card (proposal):** inputs `saccr-trade` (one row per derivative: asset class, hedging set, S, E, M, δ inputs, notional) and
COLL's regulatory values; mechanism: Java additive aggregates per hedging set at date load, the formulas in the Python `SaccrEngine`
(SACCR.md section 6.9); when: per date and on the load signal, on request for any scope, what-if for incremental EAD; edge cases: SFTs and
cash products reported as `excluded` with a count, never as zero EAD; a netting set not enforceable: each trade its own set; missing δ
inputs: trade flagged and excluded with a count; validation: per netting set against the stored `EAD`, with the difference broken into
RC, add-on and multiplier.

### 5.8 `simm` (SIMM)

Sutra `collateral-and-margin/simm.v1.sutra.yaml` (strip lines 35–39, IM by risk class). Plan of record: `parked.retiring.simm`.
Methodology source: **ISDA SIMM Methodology** (the version named on the record), restated in SIMM.md sections 6.3–6.6.

| Shown as | Field | Today | Should be | How (formula, ISDA SIMM) | Pri. |
|---|---|---|---|---|---|
| IM by risk class | `RISK_CLASSES[].IM` | S | **C** | per risk class r: `IM_r = DeltaMargin_r + VegaMargin_r + CurvatureMargin_r + BaseCorrMargin_r`; delta: `WS_k = RW_k · s_k · CR`, `K_b = sqrt(Σ WS_k² + ΣΣ ρ_kl f_kl WS_k WS_l)`, `DeltaMargin = sqrt(Σ K_b² + ΣΣ γ_bc g_bc S_b S_c)` | P1 |
| Total IM | `TOTAL` | S (= Σ `RISK_CLASSES[].IM` on 24 of 24) | **C** | `SIMM_p = sqrt(Σ_r IM_r² + Σ_r Σ_{s≠r} ψ_rs IM_r IM_s)` per product class, `SIMM = Σ_p SIMM_p`, plus add-ons. A plain sum of risk classes ignores ψ (F20) | P1 |
| Posted, Received | `POSTED`, `RECEIVED` | S | C | `IM_POST` (sensitivities negated, largest over the post regimes), `IM_COLLECT` (largest over the collect regimes) (SIMM.md section 6.6) | P1 |
| Version | `VERSION` | S | F (the agreed version, from the CSA) | — | — |
| Grain | one record per counterparty (24) naming one `NETTING_SET` | S | C per netting set | SIMM is per netting set and regime; the stored record is per counterparty but links one set (F20) | P1 |

### 5.9 `cva` (CVA)

Sutra `exposure-and-capital/cva.v1.sutra.yaml` (strip lines 35–40, adjustments bars, sensitivities table). Plan of record:
`parked.retiring.cva`.

| Shown as | Field | Today | Should be | How (formula) | Pri. |
|---|---|---|---|---|---|
| CVA | `CVA` | S | **C** | unilateral `CVA = −LGD_C · Σ_k EE(t_k)·DF(t_k) · ΔPD_C(k)`, `ΔPD_C(k) = S_C(t_{k−1}) − S_C(t_k)`, `LGD_C = 1 − RECOVERY`; bilateral multiplies by the bank's survival `S_B(t_k)` (CVA.md section 6.3). Sign: CVA ≤ 0 (a cost), as stored | P2 |
| DVA | `DVA` | S | C | `+LGD_B · Σ |ENE*(t_k)| · ΔPD_B(k)` | P2 |
| FVA | `FVA` | S | C | `FCA = −Σ EE* · s_F · Δt`, `FBA = +Σ |ENE*| · s_F · Δt`, `FVA = FCA + FBA` | P2 |
| KVA | `KVA` | S | C (hook, *indicative*) | `−Σ K(t_k) · hurdle · Δt_k · DF(t_k)` | P3 |
| CS01 (cpty), sensitivities table | `CS01`, `SENSITIVITIES[]` | S | C | `CVA(spreads + 1 bp) − CVA`; recovery +1%; market-factor sensitivities need re-simulated exposure, not computed (CVA.md section 6.4) | P2 |
| Adjustments bars | `COMPONENTS[]` | S (= the strip on 76 of 76) | Dv | the strip's four figures as rows: a Sutra expression over the same answer, not a second stored list | P3 |
| *(new)* BA-CVA (reduced) | — | — | C | `SCVA_c = (1/α) RW_c Σ_NS M_NS EAD_NS DF_NS`, `K = sqrt((ρ Σ SCVA)² + (1 − ρ²) Σ SCVA²)`, `K_BA-CVA = 0.65 K` (Basel MAR50, CVA.md section 6.6) | P3 |

### 5.10 `simulation-run` (XRUN), `exposure-path` (XPATH), `exposure-result` (EXP), `exposure-whatif` (XWIF), `exposure-official-cpty` (XOFC)

`simulation-run` and `exposure-path` are **fundamental inputs** (contract class `input`). `exposure-path` carries denormalised
`NETTING_SET`, `COUNTERPARTY`, `DESK`, `TT_BOOK`, `ASSET_CLASS`, `PRODUCT`, `NOTIONAL`, `MATURITY_DATE` (`tools/contracts.policy.yaml`
notes): they must match the trade master and `netting-set-member`. In the samples they do not always: `CLY-3000280` has `NOTIONAL`
260,700,000 in `samples/exposure-path.jsonl` and 237,000,000 in `samples/trade-valuation.jsonl` and `samples/netting-set.jsonl` (F10).
`exposure-result`, `exposure-whatif` and `exposure-official-cpty` are **computed** and built; Sutra `exposure-result.v1.sutra.yaml` is the
model for every computed page in this pack (strip of figures, a "How it was computed" panel with business date, run, paths, confidence,
collateral model, MPOR, flags, note).

### 5.11 Core `counterparty` (CPTY figures)

Sutra `config/packs/core/sutras/.../counterparty.v1.sutra.yaml` line 44 binds `$.NET_MTM`. Contract
`config/packs/core/contracts/counterparty.contract.yaml` lines 103–107: `NET_MTM` is "the sum of the NET_MTM of its netting sets";
`NETTING_SETS[].NET_MTM` copies each set's. Both hold on the test data (`CP-ALDERSHOT`: 520,405,666 = Σ of its four sets). Plan of record:
`parked.split.counterparty` (figures `NET_MTM`, `NETTING_SETS[].NET_MTM`).

| Field | Today | Should be | How | Pri. |
|---|---|---|---|---|
| `NET_MTM` | S | Dv (quick win), then C (`CPTY:` scope of NSET) | `Σ_{ns ∈ cpty} NET_MTM(ns)`; derived kind `cpty-mtm` (section 8.1) | P1 |
| `NETTING_SETS[].NET_MTM` | S | Dv | rows of `cpty-mtm` | P1 |
| *(new)* CE uncollateralised, CE | — | Dv / C | `Σ_ns max(NET_MTM_ns, 0)`; with collateral `Σ_ns max(NET_MTM_ns − C_ns, 0)`, **never** `max(Σ NET_MTM − Σ C, 0)` (NSET.md section 6.1) | P2 |
| `CREDIT_LIMIT` (link) | S | F | — | — |

### 5.12 Core `counterparty-group` (GRP figures)

Sutra `config/packs/core/sutras/.../counterparty-group.v1.sutra.yaml` lines 37–38 bind `$.LIMIT`, `$.UTILISATION`. Contract
`counterparty-group.contract.yaml` lines 54–64: `LIMIT` is "the sum of its members' credit limits", `UTILISATION` "the members' used
amounts over LIMIT". Both hold on 23 of 23 groups. Plan of record: `parked.split.counterparty-group` (figure `UTILISATION`).

| Field | Today | Should be | How (formula) | Pri. |
|---|---|---|---|---|
| `MEMBERS[]` | S | F (kept members) + Dv (GLEIF members) | `connected(G) = kept(G) ∪ gleif(G) − excluded` (GRP.md section 6.1) | P2 |
| `LIMIT` | S (Σ of members' limits) | **F** (the group's own approved limit) | a group limit is approved for the group; the sum of members' limits is a different number (open question 7) | P1 |
| `UTILISATION` | S (Σ USED ÷ Σ LIMIT) | **C** (GRP over LIM over EXP) | `PFE_q` of the members' exposures **summed per path**, then the peak, ÷ the group limit (GRP.md section 6.3). Adding the members' PFE peaks overstates the group's PFE (F17) | P1 |

### 5.13 Wrong-way risk (WWR, new)

No kind and no Sutra today. Proposed **computed** (WWR.md): specific WWR flags (a trade whose reference is the counterparty or its group),
jump-to-default exposure, and general WWR on paths: `ρ(k) = corr_p(E(p,k), λ(p,k))`, `CVA_WWR = −LGD Σ_k DF(t_k) (1/P) Σ_p E(p,k)
ΔPD_p(k)`, `WWR_RATIO = CVA_WWR ÷ CVA_IND` (WWR.md section 6.3). Inputs: EXP's exposure matrix, hazard paths or a Hull–White
`λ = exp(a(k) + b·E)` calibrated to the market survival curve. No stored attribute to retire.

### 5.14 Computation register: every derived and computed attribute (proposal)

One row per attribute (or attribute family) that sections 4 and 5 class as derived or computed: inputs at their grain, the mechanism,
when it is computed, the edge cases and how it is reconciled with today's stored value. Abbreviations: **DK** derived kind; **SE** Sutra
expression; **RE** reactive engine (Java or Python); **EF** engine figures on the stored page; **load** at date load; **ask** on request,
cached; **trig** recomputed on the `data-version` trigger of an input kind.

**How intraday changes flow (all mechanisms).** A new load of an input kind for *d* (the load signal of contracts README section 9, or
a live tick in the demo) moves its data version. A derived kind rebuilds that date's pages on its next read after the version moves,
or after `refresh` when the source cannot report a version; readers get the previous pages meanwhile. A reactive engine drops the
date's cached answers on its `triggers:` (`data-version` of the listed kinds, coalesced) and recomputes on the next ask; subscribers are
pushed the new answer. VaR additionally takes intraday scenario PVs from files or Kafka (`pv-updates`, `config/packs/market-risk/pack.yaml`
lines 335–344, off as shipped). A Sutra expression is recomputed on every render.

**Edge-case vocabulary used below.** *Missing*: a member expected on *d* with no row: counted and shown, never 0. *Mixed ccy*: members in
more than one currency: converted at FX(*d*) by an engine; a derived kind only reports `distinct` currencies and labels the total *not
converted*. *Masked*: a trade or counterparty the reader may not see: engines compute as the reader; a derived kind is shared, so its page
is restricted to roles that see every member. *Ended*: cancelled or matured trades excluded by **Live**. *No data*: no input rows on *d*:
no figure and a notice, never the previous date's figure under *d*.

| Attribute | Formula (short) | Inputs (kind.field, grain) | Mechanism | When | Edge cases (beyond the vocabulary) | Validation against today |
|---|---|---|---|---|---|---|
| **Market risk** | | | | | | |
| TVAL totals: MTM, ±, gross, notional, DV01, PNL_1D, counts | Σ over **Live** trades of the scope (4.1) | `trade-valuation` (trade × *d*) | DK by netting set, counterparty, book now; RE (TVAL) for any scope | DK per version; RE ask + trig | `failed` rows counted, not 0; notional summed only in one currency | Σ by netting set = stored `NET_MTM` 76/76 (status-only **Live**) |
| MtM bridge (`CONTINUING`, `NEW`, `GONE`) | `MTM_CHANGE = Σ_D₁ − Σ_D₀`, split by trades in both, new, gone (TVAL.md 6.3) | `trade-valuation` on *d* and *d*₋₁ | RE (TVAL) | ask | a trade re-keyed between dates looks new + gone; amendments stay continuing | `PNL_1D − CONTINUING` per trade: 201/15,000 agree (F14) |
| Position quantity, direction, market value, counts | Σ signed quantity; sign; Σ PV; counts | `pnl-vector` / `scenario-pv` (trade) and the `positions:` rules | built: positions derived at load (`var-positions`) | load | a security with long and short trades nets; an OTC trade is its own position | built; no stored copy |
| Position "gross quantity" | Σ |quantity| | same | built (rename only) | load | — | — |
| Portfolio counts, gross and net MV | counts; Σ |MV|; Σ MV over resolved positions | `portfolio.DEFINITION`, positions | EF (`VarFigures`) | ask | overlapping portfolios: a position counts once per portfolio | built |
| VaR, ES, components, limit use | VAR_GUIDE.md 18; `LIMIT_USE = VAR ÷ LIMIT` | `pnl-vector`, `scenario-pv`, `fx-scenario`, `scenario-set`, hierarchy, limits | RE / EF (built) | ask + trig; intraday PVs | reporting currency per `ccy`; limit converted at today's rate | built; official captures |
| Hierarchy `CHILDREN`, `BOOKS` | inverse of `PARENT_ID`; union of leaf books | `risk-hierarchy` (node × *d*) | SE or engine (the VaR engine already builds children) | render | cycles and orphans flagged | compare with the stored lists |
| Stress P&L per desk, per book | full: `Σ_t PNL_{t,j}`; sensitivity: `Σ δx + ½Σ Γx² + Σ νr` (STR.md 6.1–6.2) | per-trade scenario P&L or `sensitivity` rows; `stress-scenario.SHOCKS` | RE (STR) | ask + trig | shock without a unit: not applied, counted | per desk vs stored `PNL` (method difference reported) |
| Stress bank total per scenario | `Σ_desk PNL(desk, j)` | `stress-result` (desk × scenario) | DK `stress-scenario-pnl` now | per version | a desk missing a scenario: *missing* | equals Σ stored rows by construction |
| Stress limit use, breach | `max(−PNL, 0) ÷ |LIMIT|`; breach if > 1 | `stress-result.PNL`, `LIMIT` | SE now; RE (STR) later | render | `LIMIT` = 0: no ratio | 22/80 breaches today, not shown |
| FRTB delta, vega, curvature, SBM total, by class | MAR21 (4.9) | per-trade sensitivities (trade × factor) | RE (FRTB) | ask + trig | negative `M` → alternative `S_b`; three correlation scenarios, the binding one reported | per book vs stored charges (F18 explained) |
| P&L explain actual, explained, unexplained, attribution, waterfall | PNL.md 6.1–6.4 | `trade-valuation` (*d*, *d*₋₁), cash events, sensitivities at *d*₋₁, market moves | RE (PNL) | ask (date pairs) | new, amended, ended trades handled by case; unmoved factors counted | per book vs stored `ACTUAL`; bars and waterfall from one decomposition |
| **Counterparty credit risk** | | | | | | |
| Netting set `NET_MTM`, `TRADE_COUNT`, gross ±, netting benefit, NGR, by product group | 5.1 | `trade-valuation` (trade); membership (`NETTING_SET` today, `netting-set-member` later) | DK `nset-mtm` now; RE (NSET) later | DK per version; RE ask + trig | non-enforceable netting: each trade its own set (`NS-STANDALONE~<trade>`) | 76/76 status-only; 55/76 with the maturity test |
| Counterparty `NET_MTM`, CE uncollateralised | `Σ_ns NET_MTM`; `Σ_ns max(NET_MTM, 0)` | `nset-mtm` pages | DK `cpty-mtm` now; RE (NSET, `CPTY:`) later | per version | a set whose trades name two counterparties: data error shown | stored `counterparty.NET_MTM` equals Σ of stored sets today |
| Collateral value per holding | `MV × (1 − h)`, 0 if ineligible | holdings, prices, FX, `csa.ELIGIBLE` | SE on today's `POSITIONS`; RE (COLL) later | render; load | ineligible asset; concentration limit; pending transfers | Σ VALUE vs stored HELD/POSTED: 74.8m gap (F3) |
| Held, posted, net (VM, IM, IA apart) | Σ after haircut by direction and margin type | same | SE now; RE (COLL) | render; load | VM and IM never netted together | as above |
| Regulatory collateral C, NICA | CRE22 haircuts, `h_fx` (5.4) | same, `csa` MPOR | RE (COLL) | load | segregated IM posted excluded from C | none stored today |
| Current exposure CE | `max(NET_MTM − C − IM, 0)` per set, Σ over sets | NSET, COLL | EF (built, from stored copies); RE after E0 | ask | partial sets (a desk's share): flagged `partial` | CE from stored copies vs from TVAL + COLL |
| EE, ENE, EEE, PFE_q, EPE, EEPE, peaks, EAD_IMM | EXPOSURE_GUIDE.md 18.2–18.5 | `exposure-path.MTM[]` (trade × grid × path), `simulation-run`, CSA, collateral | RE / EF (built) | ask + trig | no paths for a set: `STATUS no-paths`; MPOR ≠ look-back: flagged | built; official results published |
| Limit usage, utilisation, headroom, status (per bucket) | LIM.md 6.2–6.3 | `credit-limit` terms; EXP, SACCR, NSET answers at the limit's scope | RE (LIM) | ask; morning batch | limit 0: no ratio; review overdue: flagged | stored `USED` sized on retired profiles: report only |
| Group members, group usage | GRP.md 6.1, 6.3 | `counterparty-group`, GLEIF relationships, member scopes | RE (GRP resolver + the measures) | load; ask | a counterparty in two groups: precedence, flagged | Σ-based stored utilisation vs path-based (F17) |
| SA-CCR RC, add-ons, multiplier, PFE, EAD, RWA | CRE52 (5.7) | `saccr-trade` (trade), V from NSET, C and NICA from COLL, CSA terms | RE (SACCR): Java aggregates at load, Python formulas | load + ask | SFTs excluded with a count; MPOR floors; margined EAD capped at unmargined | per set vs stored EAD, split into RC, add-on, multiplier |
| Expected margin call (VM, IM), match status | MC.md 6.2–6.4 | NSET (E), COLL (B), `csa` terms, SIMM (IM), actual `margin-call` | RE (MC, Python) | ask; morning batch | valuation lag; rounding legs; combined MTA option | direction agrees on 41/76 calls of 2026-09-30 (rough preview) |
| SIMM IM collect / post, by risk class | ISDA SIMM (5.8) | CRIF sensitivities (trade × factor), version parameter file | RE (SIMM, Python) | ask + trig | regimes; curvature not symmetric (collect ≠ post) | per counterparty vs stored TOTAL (F20) |
| CVA, DVA, FVA, CS01, BA-CVA | CVA.md 6.2–6.6 | EXP's EE/ENE at primary dates, credit curves, discount curve | RE (CVA, Python) | ask; batch after EXP | counterparty without a curve: proxy, flagged | per set vs stored CVA (report only) |
| WWR flags, JTD, CVA_WWR, ratio | WWR.md 6.2–6.3 | EXP matrix, hazards or credit paths, references | RE (WWR, Python) | batch after EXP | `b` = 0 until calibrated: ratio 1 | new |
| Expected-call sign, limit status names, labels with currency | 5.5, 7.2, 7.3 | the record | SE | render | — | — |

---

## 6. Findings: duplicates and inconsistencies

All checks were run read-only on the committed test data, replaying each kind's `snapshot-*.jsonl.gz` then its `changes-*.jsonl.gz` in
date order to the final business date 2026-09-30 (the `cob-*` files for the single-date kinds), and on the committed `samples/`.

| # | Finding | Evidence | Effect | Fix (proposal) |
|---|---|---|---|---|
| F1 | **Net MtM is stored in four places** | `netting-set.NET_MTM`, `netting-set.TRADES[].MTM`, `counterparty.NET_MTM`, `counterparty.NETTING_SETS[].NET_MTM`, besides the source `trade-valuation.MTM` | five numbers to keep equal by hand | one derivation from `trade-valuation` (5.1, 5.11). **2.9.7:** the derived totals are shown beside the stored copies with the difference (Q1, Q2); the copies retire with NSET |
| F2 | **The live demo walks the copies independently** | `_meta.walk` moves `trade-valuation.MTM` (e.g. step 3,786 for `TVAL-CLY-3000280`) and `netting-set.TRADES.0.MTM` (step 1,816) separately (`samples/*.jsonl`); `NET_MTM` follows its own `TRADES` (`derive: sum(TRADES.*.MTM)`) | on the live demo the netting set no longer sums the trade valuations it links to | derive from `trade-valuation`. **2.9.7:** the pages show the total derived from the trade valuations beside the walked copy |
| F3 | **Collateral totals are before haircut** | `collateral-balance.HELD`/`POSTED` = Σ `MARKET_VALUE` on 76 of 76, Σ `VALUE` is lower on 76 of 76 (74.8m in total); the Sutra says "after haircuts" (`collateral-balance.v1.sutra.yaml` line 19); `netting-set.COLLATERAL` = `NET` on 76 of 76; the exposure engine and the stored SA-CCR RC both use it | collateral overstated in exposure, RC and every margin figure; COLL.md and NSET.md define collateral after haircut | `HELD = Σ VALUE` (5.4). **Fixed in 2.9.7:** `HELD`, `POSTED`, `NET` and `netting-set.COLLATERAL` after haircut (generator); a check panel recomputes them |
| F4 | **The exposure engine reads stored copies** | `ExposureLoader.java` lines 341–351 (`TRADES[].MTM`), 398 (`NET`), 385–391 (`credit-limit.LIMIT`) | the computed CE inherits F1–F3 | re-point at `trade-valuation`, COLL and LIM before retiring them. **Fixed in 2.9.7 (E0):** MtM and members from `trade-valuation`, collateral from the holdings after haircut, the limit from `credit-limit` |
| F5 | **Limit signs differ** | `stress-result.LIMIT` negative on 80 of 80 (`stress-result.contract.yaml` line 74 example −75,700,000); `credit-limit.LIMIT`, `var-limit.LIMIT_VAR`, `risk-hierarchy.LIMIT_VAR`, `portfolio.LIMIT_VAR`, `netting-set.LIMIT` positive; STR.md section 5 says positive | a "limit use" computed the same way gives opposite signs | every limit positive (7.4). **Fixed in 2.9.7:** every stress limit positive; limit use and breach shown |
| F6 | **VaR limits in three kinds** | `risk-hierarchy.LIMIT_VAR` (no currency), `var-limit.LIMIT_VAR` + `CURRENCY`, `portfolio.LIMIT_VAR` (`pack.yaml` lines 280–301; `hierarchies:` line 754–757 reads LL limits from `var-limit`, MGMT and LEGAL from `risk-hierarchy`) | three update paths for one kind of fact | `var-limit` for every node and portfolio (open question 8). **Fixed in 2.9.7:** every VaR limit in `var-limit` with its currency (portfolios as hierarchy `PORTFOLIO`) |
| F7 | **Two limits for one netting set** | `netting-set.LIMIT` ≠ the counterparty's `credit-limit.LIMIT` on 76 of 76; the netting-set Sutra draws `$.LIMIT` on the PFE chart (line 64) while the exposure engine reads `credit-limit.LIMIT` | the chart's limit line and the engine's limit are different numbers | remove `netting-set.LIMIT`; limits only in `credit-limit` with a scope |
| F8 | **Netting-set utilisation is not reproducible** | `UTILISATION` matches none of `CE ÷ LIMIT`, `|NET_MTM| ÷ LIMIT`, `max(NET_MTM,0) ÷ LIMIT` on any of 76 sets | a figure nobody can explain | computed by LIM (5.6). **Fixed in 2.9.7:** `netting-set.UTILISATION` dropped (decision 13) |
| F9 | **One idea, several names and vocabularies** | `UTILISATION` (CCR, core group) vs `LIMIT_USE` (VaR, `var-official`, `var-result`); statuses "Within limit / Early warning / Breach" (`credit-limit.contract.yaml` line 80) vs `OK/WARNING/BREACH/EXCESS_APPROVED` (LIM.md) vs `ok/warning/breach` (STR.md) | users read two words for one ratio | one name and one status set (7.2) |
| F10 | **Units contradict between contracts** | `trade-valuation.NOTIONAL` unit USD (`trade-valuation.contract.yaml` line 167) vs `netting-set.TRADES[].NOTIONAL` unit "trade currency" (`netting-set.contract.yaml` line 213), with identical values on all 10,399 member rows; Sutra labels hard-code "(USD)" though `MTM_CURRENCY` exists; `pnl-explain` waterfall says `unit: USD` with no currency field; exposure-path notional differs from the trade's in the samples | one of the two contracts is wrong; labels can lie when a currency changes | amounts carry a currency field; labels read it (7.3) |
| F11 | **"Live" has three definitions** | VaR positions: live status and not matured (`pack.yaml` lines 730–736); netting sets: live status, **including** 827 members past their maturity date, and a status `LIVE` on 844 member rows that `trade-valuation` calls `VALIDATED` or `AMENDED`; BI "Open MTM": `TRADE_STATUS != 'MATURED'`, which **includes** cancelled trades (`trading-risk.model.yaml`) | the same trade counts on one page and not on another | one **Live** predicate (section 3). **2.9.7:** one Live in the derived kinds and the exposure engine; BI's "Open MTM" still to align |
| F12 | **BI MtM includes cancelled and matured rows** | `trading-risk.model.yaml` `MTM: sum(trades.MTM)` over `trading-risk-trades` (all `trade-valuation` rows): 3,210.4m on 2026-09-30, against 3,666.4m for live statuses; the 2,352 cancelled rows carry −506.9m | BI and the pages disagree by 456m | BI datasets filter **Live**, or read TVAL's published totals |
| F13 | **PV, MTM and MARKET_VALUE name one quantity** | `pnl-vector.PV` = `trade-valuation.MTM` on 1,772 of 1,772 trades checked; `position.MARKET_VALUE` = Σ trade `PV` (`samples/position.jsonl`, 41,018,452); `collateral-balance.MARKET_VALUE` is a different thing (a security's price × quantity) | readers cannot tell whether two pages show the same number | `MTM` for a trade's value everywhere; `MARKET_VALUE` only for holdings (7.2) |
| F14 | **`PNL_1D` does not reconcile to the MtM change** | `PNL_1D = MTM(d) − MTM(d−1)` on 201 of 15,000 trades on 2026-09-30 (TVAL.md section 13.4 notes the same) | P&L explain's actual, TVAL and BI's "P&L 1d" can disagree | show the MtM bridge beside it (4.1); PNL computes ACTUAL |
| F15 | **A gross quantity labelled notional** | `position.NOTIONAL` = Σ |quantity| (`position.contract.yaml`), shown "Notional (gross)" (`position.v1.sutra.yaml` line 89) | misread as a currency amount | rename (4.2) |
| F16 | **CVA stored twice** | `netting-set.CVA` = `cva.CVA` on 76 of 76; `cva.COMPONENTS[]` repeats the strip | three copies of one number | one computed CVA (5.9) |
| F17 | **Group utilisation adds PFE peaks** | `counterparty-group.UTILISATION` = Σ members' `USED` ÷ Σ members' `LIMIT` on 23 of 23, `USED` being a PFE peak; `LIMIT` is a sum of member limits | PFE is not additive (EXPOSURE_GUIDE.md section 18.4): the group figure is overstated, and the group has no approved limit of its own | GRP computes on the summed paths (5.12). **2.9.7:** the group's own approved `LIMIT` stored (decision 7); the path-based utilisation waits for GRP |
| F18 | **FRTB curvature without curvature inputs; one scenario** | `CURVATURE_CHARGE` non-zero on 30 of 30 records with no `Curvature` row in `SENSITIVITIES[]`; `TOTAL = DELTA + VEGA + CURVATURE` on 30 of 30, no low/medium/high scenarios | a charge with no inputs behind it | FRTB engine (4.9) |
| F19 | **Two P&L breakdowns on one page disagree** | `pnl-explain`: `ATTRIBUTION` "New trades" ≠ `EXPLAIN_STEPS` "New trades" on 30 of 30 (sample `PNL-COMM-1`: −6,530 vs −9,795; the waterfall also splits Theta +3,265 out of "Carry and theta") | the bars and the waterfall tell different stories | one decomposition, two views (4.10) |
| F20 | **SIMM total is a plain sum, at the wrong grain** | `TOTAL = Σ RISK_CLASSES[].IM` on 24 of 24; one record per counterparty naming one netting set (`SIMM-ALDERSHOT` → `NS-ALDERSHOT-FRA`) while all four Aldershot netting sets link `SIMM-ALDERSHOT` | overstated IM; IM attributed to one set | SIMM engine per netting set (5.8) |
| F21 | **Option values break the sign convention** | in `pnl-vector` on 2026-09-30, 20 short warrants, 16 short short-term-rate future options and 10 short commodity future options have `PV > 0`; 23 long listed equity options have `PV ≤ 0` (README section 7: value positive for an asset) | signs of positions and exposures unreliable for these products | a sign check in validation (7.4) |
| F22 | **Product named three ways** | `trade-valuation.PRODUCT_TYPE` `TRIPARTY_REPO`; `netting-set.TRADES[].PRODUCT` and `exposure-path.PRODUCT` "Tri-party repo"; `ASSET_CLASS` in CCR vs `PRODUCT_GROUP` in market risk for the same grouping | joins and filters across pages fail | codes stored, labels from a lookup (7.2) |
| F23 | **Two maturity-bucket sets** | netting-set `MATURITY_BUCKET` 0-1Y, 1-2Y, 2-5Y, 5-10Y, 10Y+ (`netting-set.contract.yaml`; TVAL.md setting) vs credit-limit buckets 0-1Y, 1-3Y, 3-5Y, 5-10Y, 10Y+ (`samples/credit-limit.jsonl`) | a 2.5-year trade is in "2-5Y" on one page and "1-3Y" on another | one bucket set per purpose, named (open question 10). **Fixed in 2.9.7:** two named sets in `config/packs/counterparty-risk/config/maturity-buckets.yaml` |
| F24 | **SA-CCR add-ons do not follow the trades** | add-on asset classes match the set's trade asset classes on 7 of 76; securities-financing-only sets carry interest-rate add-ons; SACCR.md section 5 excludes SFTs | EAD not explainable from the trades | SACCR engine (5.7) |
| F25 | **Book named four ways** | `PORTFOLIO` (trade-valuation, pnl-vector: a book id), `TT_BOOK` (pnl-vector, position), `BOOK` (netting-set trades, frtb-sensitivity, pnl-explain, stress `BY_BOOK`), while `portfolio` is also a kind (`PF-…`) of a different thing; the TVAL Sutra links `PORTFOLIO` as a `book` (line 54) | "portfolio" means two things in one pack | `BOOK` (or `TT_BOOK`) for the book everywhere; `PORTFOLIO` only for `PF-` (open question 11) (decided: `PORTFOLIO` keeps its name; descriptions say it holds the book id; open question 11) |

**Top ten, by effect:** F3, F4, F1/F2, F7/F8, F11/F12, F17, F20, F5, F10, F19.

---

## 7. Cross-pack consistency rules (proposal)

### 7.1 One definition per quantity, in one place

| Rule | Detail |
|---|---|
| One owner | Every quantity has one producing mechanism (a stored kind for a fundamental, one derived kind or one engine for the rest). Every page, BI model and engine reads that one. |
| No copies on parents or members | A parent record (netting set, counterparty, group, portfolio) stores terms and links, never a total of its members. A member record never stores its parent's figure. |
| Copies of master attributes are validated | Denormalised attributes on inputs (`DESK`, `TT_BOOK`, `NETTING_SET`, `PRODUCT`, `NOTIONAL` on vectors and paths) are allowed for speed, and the load checks them against the master (contracts README section 10, "Keys resolve"), reporting differences as warnings. |
| The guard extends | The retirement guard (`tools/contracts.policy.yaml` `engine-columns`, contracts README section 14) already fails the build when an input carries `UTILISATION`, `PFE_PEAK` and other engine columns. Proposal: once each measure is built, add its columns (`NET_MTM`, `COLLATERAL`, `HELD`, `POSTED`, `USED`, `EAD`, `ADD_ON`, `TOTAL` for SIMM and FRTB) to `engine-columns`. |

### 7.2 Names

| Concept | One name (proposal) | Today's other names |
|---|---|---|
| A trade's value | `MTM` | `PV` (pnl-vector, position trades), `BASE_PV` (scenario-pv, trade currency: keep, it is a different currency) |
| A holding's value | `MARKET_VALUE` (before haircut), `VALUE` (after haircut) | position `MARKET_VALUE` (a Σ MTM: rename `MTM`) |
| Net value of a netting set | `NET_MTM` | — |
| Positive and negative parts | `GROSS_POSITIVE_MTM`, `GROSS_NEGATIVE_MTM` | TVAL.md `MTM_POSITIVE`, `MTM_NEGATIVE`; NSET.md `GROSS_POSITIVE`, `GROSS_NEGATIVE` (align the two contracts) |
| Used ÷ limit | `UTILISATION`, a fraction | `LIMIT_USE` (VaR) |
| Limit status | `OK`, `WARNING`, `EXCESS_APPROVED`, `BREACH` (LIM.md) | "Within limit", "Early warning", "Breach"; STR's lower case |
| Book | `TT_BOOK` (or `BOOK`) | `PORTFOLIO` (when it holds a book id), `BOOK` |
| Product | `PRODUCT_TYPE` code, `PRODUCT_GROUP` | `PRODUCT` labels, `ASSET_CLASS` |
| Trade status | the trade master's codes | `LIVE` in netting-set rows |

### 7.3 Units and currencies

| Rule | Detail |
|---|---|
| Every amount carries its currency | an amount field has a sibling currency field on the record (`MTM_CURRENCY`, `CURRENCY`), or the contract states one reporting currency for the whole record and the record carries `CURRENCY` (as `var-result` does) |
| Labels read the currency | no literal "(USD)" in a Sutra label or a chart `unit:`; `'MTM (' + $.MTM_CURRENCY + ')'` |
| Sums only in one currency | every sum is made in its sum currency (USD by default, configurable per entity; section 3, *Sum currency*): each member converted at FX(*d*) first; a total that cannot be converted is labelled "not converted"; trading's "Notional (all currencies)" is the honest label until converted totals exist |
| Fractions, not percents | ratios (utilisation, haircut, supervisory factor, confidence) stored as fractions; `pct` formats only on screen |
| Sensitivities state their bump | DV01 per +1 bp, CS01 per +1 bp, vega per vol point or relative (FRTB.md section 3.2.1) |
| Shocks state their unit | `stress-scenario.SHOCKS[]` needs a unit per row (4.7) |

### 7.4 Signs

| Quantity | Convention (contracts README section 7) | Exceptions found |
|---|---|---|
| MtM, value | positive = asset of the bank (receive positive) | option PVs (F21) |
| P&L | gain positive | — |
| Quantity | long positive | — |
| Exposure (CE, EE, PFE, EAD) | ≥ 0, counterparty owes the bank | — |
| Collateral | held positive, posted negative in every **net** figure; `HELD` and `POSTED` themselves non-negative magnitudes | consistent |
| Valuation adjustments | CVA ≤ 0, DVA ≥ 0 (CVA.md section 5) | consistent |
| VaR, ES, capital, IM | positive numbers | — |
| **Limits** | **positive magnitudes**; use = loss or exposure ÷ limit | stress `LIMIT` negative (F5) |
| Margin call | `AMOUNT ≥ 0` with `DIRECTION`; Drishti's signed form + when the counterparty transfers (MC.md section 5) | — |

Validation proposal: a load-time sign check per product (a bought option's value ≥ 0, a sold option's ≤ 0, premium aside), reported as a
data-quality warning, never a reject.

### 7.5 Dates

| Rule | Detail |
|---|---|
| One business date per answer | every derived or computed figure is for one *d*; inputs of mixed dates are refused or flagged (contracts README section 6, "one run per date") |
| As-of and known-at | `asOf` selects *d*; `knownAt` reads a table version for audit; derived kinds already have history wherever their members do (`DerivedSourcePlugin.java` javadoc) |
| Valuation lag | where a measure uses yesterday's valuation (the expected margin call, `valuation-lag-days` 1, MC.md section 5) the page says which date |
| Row dates agree | `VALUATION_DATE`, `AS_OF`, `DATE`, `BUSINESS_DATE` on a record equal *d* or the page flags the record stale |

### 7.6 Provenance on the page

Today every Sutra has `data-provenance@1` (`config/ui-kit/fragments/data-provenance.v1.fragment.yaml`): the record's source, freshness,
the Sutra and what was masked. That is **record** provenance. Computed answers also carry **figure** provenance: the VaR and exposure
pages show "How this number was built" and "How it was computed" (VAR_GUIDE.md section 11.2; `exposure-result.v1.sutra.yaml` panel `how`).

Proposal: every derived or computed figure on a page shows, on hover or in a "How it was built" panel:

| Item | Example for `NET_MTM` |
|---|---|
| Formula, in words | "Sum of the MtM of the live trades of this netting set" |
| Mechanism | derived kind `nset-mtm`, or the NSET engine |
| Inputs and their versions | `trade-valuation`, business date 2026-09-30, data version, batch id |
| Members counted, missing, excluded | 272 counted, 0 missing, 18 excluded (past maturity) |
| Currency and FX | USD, no conversion |
| Built at | timestamp; "shared by all readers" (derived) or "as you" (engine) |

A derived kind can put most of these on the page as fields (`TRADE_COUNT`, `MTM_CURRENCIES`, `VALUATION_DATES`), and a Sutra kv panel can
show them.

---

## 8. Proposed derived kinds and Sutra expressions (illustrations)

**Applied in 2.9.7, in a changed form: [section 13.1](#131-the-derived-kinds-and-the-sutra-panels-q1-to-q4) gives the configuration as
built.** The blocks below are the proposal as it was written. The two checks: (a) a derived kind may **not** read another derived kind of
its connector (it reads its members through the router, so `from: nset-mtm` in the same connector would re-enter the computation it is
part of, and a derived connector reports no data version for a second one to follow), so `cpty-mtm` uses the documented fallback and
reads `trade-valuation` itself, and takes each netting set's positive net first with `group()` over its rows, which the fallback
thought impossible; (b) the `where:` uses `||`, as illustrated. The **Live** rule is applied to rows (`LIVE`) rather than in `where:`, so
a trade of a live status past its maturity date stays visible as a data-quality warning while counting in no total.

### 8.1 Derived kinds, counterparty-risk pack

```yaml
# PROPOSAL, NOT APPLIED: a connector for config/packs/counterparty-risk/pack.yaml (connectors:), with routes nset-mtm and cpty-mtm.
  ccr-derived:
    plugin: derived
    enabled: ${DRISHTI_LAKE_ENABLED:true}
    description: 'Netting-set and counterparty MtM totals derived from the trade valuations, once per business date (no stored totals)'
    kinds: [nset-mtm, cpty-mtm]
    settings:
      refresh: 60s
      nset-mtm:                                  # one page per netting set: NET_MTM, gross positive and negative, the member trades
        from: trade-valuation
        where: "nonblank($.NETTING_SET) && ($.TRADE_STATUS == 'NEW' || $.TRADE_STATUS == 'VALIDATED' || $.TRADE_STATUS == 'AMENDED')"
                                                 # add  && days(today(), $.MATURITY_DATE) >= 0  if matured members leave (open question 3)
        group-by: $.NETTING_SET
        id-field: NETTING_SET_ID
        members: TRADE_IDS
        max-members: 100000                      # list every member (the largest test set has 1,274 trades); aggregates count all regardless
        rows-field: TRADES
        fields:
          TRADE_COUNT: count
          NET_MTM: sum $.MTM
          GROSS_POSITIVE_MTM: sum max($.MTM, 0)
          GROSS_NEGATIVE_MTM: sum min($.MTM, 0)
          NOTIONAL_GROSS: sum abs($.NOTIONAL)
          PNL_1D: sum $.PNL_1D
          DV01: sum $.DV01
          MTM_CURRENCIES: distinct $.MTM_CURRENCY        # more than one value: the page says "not converted"
          COUNTERPARTIES: distinct $.COUNTERPARTY        # must be one; more is a data error shown on the page
          COUNTERPARTY: first $.COUNTERPARTY
          VALUATION_DATES: distinct $.VALUATION_DATE     # must be [d]
          LAST_MATURITY: max $.MATURITY_DATE
        rows:
          TRADE_ID: $.TRADE_ID
          TRADE_STATUS: $.TRADE_STATUS
          PRODUCT_TYPE: $.PRODUCT_TYPE
          PRODUCT_GROUP: $.PRODUCT_GROUP
          CURRENCY: $.CURRENCY
          BOOK: $.PORTFOLIO
          NOTIONAL: $.NOTIONAL
          MATURITY_DATE: $.MATURITY_DATE
          MTM: $.MTM
        then:
          CE_UNCOLLATERALISED: 'max($.NET_MTM, 0)'
          NETTING_BENEFIT: '$.GROSS_POSITIVE_MTM - max($.NET_MTM, 0)'
          NET_TO_GROSS: '$.GROSS_POSITIVE_MTM > 0 ? max($.NET_MTM, 0) / $.GROSS_POSITIVE_MTM : 0'
          BY_PRODUCT_GROUP: "group($.TRADES, 'PRODUCT_GROUP', 'MTM')"
        sort: { TRADES: MTM desc }
      cpty-mtm:                                  # one page per counterparty, over its netting sets' pages (needs check (a))
        from: nset-mtm
        group-by: $.COUNTERPARTY
        id-field: COUNTERPARTY
        members: NETTING_SETS
        max-members: 10000
        rows-field: SETS
        fields:
          NETTING_SET_COUNT: count
          TRADE_COUNT: sum $.TRADE_COUNT
          NET_MTM: sum $.NET_MTM
          GROSS_POSITIVE_MTM: sum $.GROSS_POSITIVE_MTM
          GROSS_NEGATIVE_MTM: sum $.GROSS_NEGATIVE_MTM
          CE_UNCOLLATERALISED: sum max($.NET_MTM, 0)     # per set first, then summed: exposures never net across netting sets
        rows:
          NETTING_SET: $.NETTING_SET_ID
          NET_MTM: $.NET_MTM
          TRADE_COUNT: $.TRADE_COUNT
        sort: { SETS: NET_MTM desc }
```

Fallback if (a) fails: `cpty-mtm` from `trade-valuation` grouped by `$.COUNTERPARTY` gives `NET_MTM`, gross figures and
`NETTING_SETS: distinct $.NETTING_SET`, but not `CE_UNCOLLATERALISED` (that needs the per-set max first: the NSET engine).

### 8.2 Derived kinds, market-risk pack

```yaml
# PROPOSAL, NOT APPLIED: a connector for config/packs/market-risk/pack.yaml (connectors:), with route stress-scenario-pnl.
  mr-derived:
    plugin: derived
    enabled: ${DRISHTI_LAKE_ENABLED:true}
    kinds: [stress-scenario-pnl]
    settings:
      refresh: 60s
      stress-scenario-pnl:                        # the bank's stress P&L per scenario: desk results added (additive)
        from: stress-result
        group-by: $.SCENARIO
        id-field: SCENARIO_ID
        members: RESULT_IDS
        max-members: 1000
        rows-field: DESKS
        fields:
          DESK_COUNT: count
          PNL: sum $.PNL
          WORST_DESK_PNL: min $.PNL
          SCENARIO_NAME: first $.SCENARIO_NAME
        rows:
          DESK: $.DESK
          PNL: $.PNL
          LIMIT: abs($.LIMIT)                       # magnitudes until F5 is fixed at the source
          LIMIT_USE: 'abs($.LIMIT) > 0 ? max(-$.PNL, 0) / abs($.LIMIT) : null'
        then:
          BREACHES: "size($.DESKS[?@.LIMIT_USE > 1])"
        sort: { DESKS: PNL asc }
```

Not proposed as derived kinds: FRTB charges, SIMM, VaR, PFE, any limit use over a non-additive measure (section 1.2, question 4).
A book or desk MtM total from `trade-valuation` is the same pattern as `nset-mtm` grouped by `$.PORTFOLIO` or `$.DESK`; the plan
assigns book figures to the scope engine (`tools/contracts.policy.yaml` `parked.split.book`), so it is listed but not drafted.

### 8.3 Sutra expressions inside one document (checks and new derived fields)

These need no new kind. They show a derived figure beside the stored one, which is also the parallel-run view of section 9.3.

| Sutra | Proposed cell or column | Expression (Rachana-EL) | Purpose |
|---|---|---|---|
| `collateral-balance` | Held after haircut | `sum($.POSITIONS[?@.DIRECTION == 'Held'], 'VALUE')` | the figure F3 says `HELD` should be |
| `collateral-balance` | Posted after haircut | `sum($.POSITIONS[?@.DIRECTION == 'Posted'], 'VALUE')` | same |
| `collateral-balance` | Haircut taken | `sum($.POSITIONS, 'MARKET_VALUE') - sum($.POSITIONS, 'VALUE')` | makes the gap visible |
| `netting-set` | Members' MtM (check) | `sum($.TRADES, 'MTM')` with a tone when it differs from `$.NET_MTM` | a stored total against its members |
| `netting-set` | Current exposure (stored inputs) | `max($.NET_MTM - $.COLLATERAL, 0)` | until the engine's CE replaces it |
| `netting-set` | Computed totals panel | `source: { kind: nset-mtm, id: "$.NETTING_SET_ID" }`, cells `$src.NET_MTM`, `$src.GROSS_POSITIVE_MTM`, `$src.NETTING_BENEFIT`, `$src.TRADE_COUNT` | the derived figures beside the stored ones (pattern: `compute-demo/sutras/path-book.v1.sutra.yaml` lines 47–51) |
| `credit-limit` | Headroom | `$.LIMIT - $.USED` | new derived field |
| `credit-limit` | Utilisation (check) | `$.LIMIT > 0 ? $.USED / $.LIMIT : null` | against the stored `UTILISATION` |
| `sa-ccr` | EAD (check) | `$.ALPHA * ($.REPLACEMENT_COST + $.MULTIPLIER * $.ADD_ON)` | shows the EAD identity holds |
| `stress-result` | Limit use | `abs($.LIMIT) > 0 ? max(-$.PNL, 0) / abs($.LIMIT) : null` | breach visible on the page |
| `stress-result` | P&L by book (check) | `sum($.BY_BOOK, 'PNL')` | against `PNL` |
| `pnl-explain` | Unexplained share | `$.ACTUAL != 0 ? abs($.UNEXPLAINED) / abs($.ACTUAL) : null` | the tolerance of PNL.md section 6.4 |
| `trade-valuation` | Labels | `'MTM (' + $.MTM_CURRENCY + ')'` | F10 |
| `position` | Gross quantity | relabel `$.NOTIONAL` | F15 |

---

## 9. Recommended build order, retirement and reconciliation

### 9.1 Build order

Quick wins first (configuration only, no engine), then the engines in the plan's order. The plan's release appears once per row in the
last column.

| Step | What | Mechanism | Prerequisite | Retires (stored copies) | Where it fits |
|---|---|---|---|---|---|
| Q1 | `nset-mtm`: NET_MTM, gross ±, trade count, member rows, by product group | derived kind (8.1) + netting-set Sutra panel | decide **Live** (open question 3) | nothing yet: shown beside the stored figures | before NSET; no release assigned |
| Q2 | `cpty-mtm`: counterparty NET_MTM, CE uncollateralised | derived kind (8.1) | Q1; check (a) | nothing yet | before NSET; no release assigned |
| Q3 | Collateral after haircut, headroom, limit-use and identity checks | Sutra expressions (8.3) | none | nothing | any time; no release assigned |
| Q4 | `stress-scenario-pnl` | derived kind (8.2) | F5 decision | nothing | before STR; no release assigned |
| Q5 | Fix the data: stress limit sign, collateral totals after haircut, CSA terms numeric with currency, one maturity-bucket set, product codes | generators and contracts | product owner's decisions in section 11 | — | with the next test-data regeneration (IMPLEMENTATION_PLAN.md) |
| E0 | Re-point the exposure engine at `trade-valuation` and a membership table, and at COLL's after-haircut VM | engine change | Q1 or NSET's `netting-set-member` | `ExposureLoader`'s reads of `TRADES[].MTM`, `NET` | with NSET |
| E1 | TVAL totals for any scope | Java engine (TVAL.md) | — | BI's own MtM sums (BI reads TVAL) | 2.9.0 plan (parked) |
| E2 | STR | Java + Python (STR.md) | per-trade scenario P&L or sensitivities | `stress-result.PNL`, `BY_BOOK` | 2.9.0 plan (parked) |
| E3 | FRTB SBM | Java + Python (FRTB.md) | per-trade sensitivities | `frtb-sensitivity` charges, `TOTAL`, `BY_CLASS` | 2.9.0 plan (parked) |
| E4 | SIMM | Python (SIMM.md) | CRIF sensitivities, licensed parameter files | `simm` `TOTAL`, `POSTED`, `RECEIVED`, `RISK_CLASSES` | 2.9.0 plan (parked) |
| E5 | PNL explain | Java + Python (PNL.md) | two dates of valuations, sensitivities, market moves | `pnl-explain` figures | 2.9.0 plan (parked) |
| E6 | NSET | Java (NSET.md) | `netting-set-member`, E0 | `netting-set` `NET_MTM`, `COLLATERAL`, `UTILISATION`, `CVA`, `BY_ASSET`, `TRADES`, `TRADE_COUNT`, `LIMIT`; counterparty `NET_MTM`; Q1/Q2 derived kinds | 2.10.0 plan, order 1 |
| E7 | SACCR | Java + Python (SACCR.md) | `saccr-trade`, COLL regulatory values | `sa-ccr` (whole kind) | 2.10.0 plan, order 2 |
| E8 | COLL | Java (COLL.md) | `collateral-holding`, prices, FX | `HELD`, `POSTED`, `NET`, `MARKET_VALUE`, `VALUE` | 2.10.0 plan, order 3 |
| E9 | MC expected call | Python (MC.md) | NSET, COLL, CSA terms numeric | nothing (actual calls stay) | 2.10.0 plan, order 4 |
| E10 | LIM | Python over EXP, SACCR, NSET (LIM.md) | EXP (built), E7 | `USED`, `UTILISATION`, `STATUS`, bucket figures; `netting-set.LIMIT` | 2.10.0 plan, order 6 |
| E11 | GRP | Java resolver (GRP.md) | E10 | `counterparty-group.UTILISATION` (and a decision on `LIMIT`) | 2.10.0 plan, order 7 |
| E12 | CVA | Python (CVA.md) | EXP, credit curves | `cva` (whole kind), `netting-set.CVA` | 2.10.0 plan, order 8 |
| E13 | WWR | Python (WWR.md) | EXP, E12 | nothing (new) | 2.10.0 plan, order 9 |

Why NSET and COLL go before the counterparty engines in practice: every CCR figure starts from V (net MtM) and C (collateral). With F1–F4
in place, SA-CCR, MC, LIM and CVA would each inherit a stored, pre-haircut, independently walked input.

### 9.2 What retires, and how

The retirement rule is the plan's ([contracts README section 14](../contracts/README.md#14-retiring-the-stored-version)): links keep
working, the stored kind or its figures are removed with samples, test data, generators and Sutra bindings, the guard test is extended,
docs and CHANGELOG say so. The **derived quick wins** (Q1, Q2, Q4) are themselves replaced by the engines (E6, E2) when those ship, under
the same kind names if the product owner wants the pages to stay put (the `computed-in-place` pattern of `tools/contracts.policy.yaml`).

### 9.3 Migration and reconciliation

The product owner asks for a parallel period with stored and computed side by side. The current rule says the old and new versions
"never coexist" and the numbers are reconciled **before** the old version goes (contracts README section 14 step 3; IMPLEMENTATION_PLAN.md
retirement rule 4). Both can hold if the parallel view is a **reconciliation page during development**, not a release with two answers.
This is open question 2. The proposal:

| Stage | What the reader sees | Where |
|---|---|---|
| 1. Shadow | the derived or computed figure beside the stored one, with the difference and its cause | a "Reconciliation" panel on the stored page (8.3 expressions, a `source:` panel), shown to risk roles only |
| 2. Report | a table per measure: per record, stored, computed, difference, relative difference, cause | in the measure's guide, as PACK_DEVELOPER_GUIDE.md › Part VII. Groupings and analytics, computed, section 18 and EXPOSURE_GUIDE.md section 22.2 did |
| 3. Retire | the stored figure removed, the computed one in its place, the reconciliation table kept in the guide | the release that builds the engine |

Tolerance and causes, per attribute (proposal):

| Attribute | Tolerance | Known causes on today's data |
|---|---|---|
| `NET_MTM` | exact (≤ 1 USD) | **Live** with a maturity test removes 827 trades from 21 sets |
| `TRADE_COUNT` | exact | same |
| `COLLATERAL`, `HELD`, `POSTED` | exact after haircut | stored before haircut (F3), 74.8m in total |
| `UTILISATION` (netting set) | none possible | not reproducible (F8): reported, not reconciled |
| `USED`, `UTILISATION` (credit limit) | report only | stored sized on retired profiles (plan) |
| SA-CCR `EAD` | 1% per set, broken into RC, add-on, multiplier | collateral basis (F3), add-on classes (F24), SFT exclusion |
| SIMM `TOTAL` | report only | plain sum vs ψ aggregation (F20) |
| FRTB `TOTAL` | report only | curvature and scenarios (F18) |
| CVA | report only | depends on EXP and curves |
| Stress `PNL` | exact for the derived bank total; method difference for the engine | — |
| PNL explain `ACTUAL` | exact against TVAL's MtM bridge plus cash | `PNL_1D` vs ΔMtM (F14) |

---

## 10. Modelling choices for the product owner

These are choices, not facts. Each has a default in a contract; the product owner confirms or changes it.

| Choice | Default in the contracts | Options | Affects |
|---|---|---|---|
| PFE quantile | 95% and 99% computed; limits use 95% (EXPOSURE_GUIDE.md section 18.1 `limit-confidence`) | any q in (0.5, 1) | EXP, LIM, GRP |
| Quantile rule | `kth` | `interpolated` | VaR, EXP |
| Collateral model on paths | `lagged` (look-back by MPOR) | `current`, `none` | EXP, CVA, LIM |
| Haircut basis for exposure | contractual (CSA) | supervisory (CRE22) | NSET, EXP, MC |
| Partial netting sets (a desk's share) | collateral not apportioned (`partial-collateral: none`) | `pro-rata-positive-mtm` | NSET, EXP |
| **Live** and matured trades | status only (netting sets today) or status and maturity (VaR today) | either, one for all | every figure |
| CVA mode and averaging | unilateral; EE at period end (`ee-point: end`) | bilateral; `mid` | CVA |
| CVA discounting and PD source | DF from the USD OIS curve; survival points | hazard, spread-implied | CVA |
| LGD | 1 − market recovery of the counterparty's curve | regulatory LGD by type | CVA, WWR |
| Proxy credit curves | by rating × sector × region | — | CVA |
| SIMM version and licensed parameters | the CSA's agreed version | — | SIMM, MC |
| FRTB GIRR specified-currency reduction | off unless chosen (MAR21.44) | on | FRTB |
| SA-CCR jurisdiction | BCBS | US, EU, OSFI overlays | SACCR |
| Reporting currency for CCR | **decided:** the sum currency rule (USD by default, configurable per entity; section 3) | — | NSET, COLL, MC |
| Stress P&L method | full revaluation for defined scenarios | sensitivities, both | STR |
| P&L explain tolerance | 10,000 USD and 10% | — | PNL |

---

## 11. Open questions, decided

The product owner decided every open question on 2026-10-29 (analysis decisions only: nothing is built or changed by them).

| # | Question | Why it matters | Proposed answer |
|---|---|---|---|
| 1 | ~~In which currency are netting-set sums made?~~ **Decided (product owner):** every sum in USD by default, configurable per entity (a netting set, counterparty, group, limit… may ask for CAD or any currency); convert each member at FX(*d*), then add (section 3, *Sum currency*) | — | applies to every sum in both packs; the margin call may still be expressed in the CSA currency as the CSA requires, shown with its USD (or configured) equivalent |
| 2 | **Is a parallel period wanted** (stored and computed both on the pages for a while), given the rule that they never coexist in a release? | Section 9.3 | **Decided: as proposed.** Shadow and reconcile during development, publish the table, retire in the release |
| 3 | **Do trades past their maturity date with a live status count?** 827 netting-set members do today; VaR positions exclude them. | NET_MTM on 21 of 76 sets | **Decided: as proposed.** exclude (VaR's rule) and flag "past maturity, not matured" as a data-quality warning |
| 4 | **Are the derived quick wins (Q1–Q4) wanted before the engines?** The plan parks every measure but VaR and EXP. | They are configuration only and remove F1, F2, F7 from view | **Decided: as proposed.** yes for Q1, Q3; Q2 after check (a); Q4 with F5 |
| 5 | **Do stored values stay as a reconciliation reference after retirement?** | The guard test forbids engine columns on inputs | **Decided: as proposed.** no: keep the reconciliation table in the guide and the last stored date in Delta time travel |
| 6 | Who may see derived totals that are shared by every reader (section 2)? | per-trade entitlements | **Decided: everyone.** Derived totals are visible to every user who may open the kind; per-trade entitlements are not applied to them until the netting-set engine computes per reader (a trade-off the product owner accepts) |
| 7 | Is a group's limit its own approved amount or the sum of members' limits? | F17 | **Decided: as proposed.** its own, approved, stored on `counterparty-group` |
| 8 | Should every VaR limit live in `var-limit` (with currency), leaving `risk-hierarchy` and `portfolio` without `LIMIT_VAR`? | F6 | **Decided: as proposed.** yes |
| 9 | One CSA per counterparty covering netting sets in four legal entities (test data): intended? A CSA normally sits under one agreement between two legal entities. | MC and COLL per CSA | **Decided: as proposed.** regenerate one CSA per agreement and legal entity |
| 10 | One maturity-bucket set, or one per purpose (limits vs display)? | F23 | **Decided: as proposed.** named sets: `display` and `limit`, both in configuration |
| 11 | `PORTFOLIO` for a book id: rename to `TT_BOOK`/`BOOK` in the market-risk inputs? | F25 | **Decided: no.** `PORTFOLIO` keeps its name in the market-risk inputs; its description states that it holds the book id |
| 12 | Names and mnemonics for the derived kinds (`nset-mtm`, `cpty-mtm`, `stress-scenario-pnl`), or computed in place under the existing kinds' names? | navigation | **Decided: as proposed.** computed in place when the engine ships; separate kinds only for the quick-win period |
| 13 | Is the `netting-set.UTILISATION` formula known to the source? | F8 | **Decided: as proposed.** if not, drop it now rather than reconcile |
| 14 | Should `PNL_1D` stay delivered when PNL computes `ACTUAL`? | F14 | **Decided: as proposed.** keep as the engine's own figure, shown with the MtM bridge |

---

## 12. Sources read

| Area | Files |
|---|---|
| Market-risk pack | `config/packs/market-risk/pack.yaml`; `sutras/market-risk/*.sutra.yaml` (7); `contracts/*.contract.yaml` (16); `samples/*.jsonl` |
| Counterparty-risk pack | `config/packs/counterparty-risk/pack.yaml`; `sutras/*/*.sutra.yaml` (10); `contracts/*.contract.yaml` (14); `samples/*.jsonl` |
| Core pack | `config/packs/core/contracts/counterparty.contract.yaml`, `counterparty-group.contract.yaml`; the counterparty and counterparty-group Sutras |
| Trading pack | `config/packs/trading/pack.yaml` (connector `trade-pages`) |
| Engines and mechanisms | `docs/guides/VAR_GUIDE.md`, `docs/guides/EXPOSURE_GUIDE.md`, `docs/guides/PACK_DEVELOPER_GUIDE.md` (sections 18, 19, 24), `docs/architecture/DATA_ARCHITECTURE.md`, `docs/guides/RACHANA_REFERENCE.md` (functions), `drishti-engine/.../source/derived/DerivedKind.java`, `DerivedSourcePlugin.java`, `drishti-server/.../exposure/ExposureLoader.java`, `config/ui-kit/fragments/data-provenance.v1.fragment.yaml` |
| Plan and policy | `docs/architecture/IMPLEMENTATION_PLAN.md` ("The computed-measures roadmap"), `tools/contracts.policy.yaml`, `docs/contracts/README.md` (sections 3, 6, 7, 10, 11, 14) |
| Measure contracts | `docs/contracts/NSET.md`, `SACCR.md`, `COLL.md`, `MC.md`, `LIM.md`, `GRP.md`, `CVA.md`, `WWR.md`, `TVAL.md`, `STR.md`, `FRTB.md`, `PNL.md`, `SIMM.md` (sections 5 and 6 of each) |
| BI | `config/bi/models/trading-risk.model.yaml`, `counterparty-exposure.model.yaml`, `config/bi/datasets/trading-risk-trades.dataset.yaml` |
| Test data | `data/testdata/market-risk/*`, `data/testdata/counterparty-risk/*`, `data/testdata/core/counterparty`, `counterparty-group` (replayed read-only to 2026-09-30) |
| Regulatory texts named (not fetched) | BCBS 279 and Basel Framework CRE52 (SA-CCR), CRE53 (internal models, EEPE), CRE22 (collateral haircuts), MAR21 (FRTB sensitivities-based method), MAR50 (BA-CVA), ISDA SIMM Methodology |

---

## 13. As built (2.9.7)

What 2.9.7 built of sections 8 and 9 (the quick wins Q1 to Q5 and the engine step E0), as the product owner's decisions of section 11
direct. Everything not listed here is still the analysis.

### 13.1 The derived kinds and the Sutra panels (Q1 to Q4)

| Step | Built | Where |
|---|---|---|
| Q1 `nset-mtm` (`NSMTM`) | One page per netting set from the trade valuations of a live status (`TRADE_STATUS` NEW, VALIDATED, AMENDED, a `NETTING_SET`): `NET_MTM`, `TRADE_COUNT`, `GROSS_POSITIVE_MTM`, `GROSS_NEGATIVE_MTM`, `CE_UNCOLLATERALISED`, `NETTING_BENEFIT`, `NET_TO_GROSS`, `BY_PRODUCT_GROUP`, the member rows `TRADES[]` (each with `LIVE`), `PAST_MATURITY_COUNT`, `PAST_MATURITY_MTM`, `PAST_MATURITY`, `SUM_CURRENCY`, `CURRENCY_NOTE` | connector `ccr-derived` of the counterparty-risk pack (generated from `tools/packgen/banking/ccr_kinds.py`); contract `config/packs/counterparty-risk/contracts/nset-mtm.contract.yaml` |
| Q2 `cpty-mtm` (`CPMTM`) | One page per counterparty, from the trade valuations directly (check (a) of section 8: a derived kind cannot read another of its connector): `NET_MTM`, `TRADE_COUNT`, gross parts, `NETTING_SETS` (`group()` rows: label, value, count), `NETTING_SET_COUNT`, `CE_UNCOLLATERALISED` = Σ over the sets of max(set net, 0), by `group(...)[?@.value > 0]`, `PAST_MATURITY_COUNT` | the same connector; contract `cpty-mtm.contract.yaml` |
| Pages | Netting set: panel **Computed from the trade valuations, beside the stored figures** (`F4`: computed, stored and difference of net MtM and trades; gross parts, netting benefit, net-to-gross, uncollateralised CE, the two checks of section 8.3, past-maturity count, sum currency), **Net MTM by product group (computed)**, **Live-status trades past their maturity date** (data quality: in no total). Counterparty (the core pack's Sutra): **Computed from the trade valuations** and **Netting sets (computed)**. The strip's *Utilisation* is gone (Q5) | `tools/packgen/banking/risk_data.py` → `netting-set.v1.sutra.yaml`, `counterparty.v1.sutra.yaml` |
| Q3 checks | Panels **Checks** on: the collateral balance (held, posted and net after haircut from the positions, haircut taken), the credit limit (headroom, utilisation = used ÷ limit and its difference from the stored one), SA-CCR (the EAD identity α(RC + multiplier × add-on) and its difference, add-on = Σ by asset class), the stress result (limit use, status OK or BREACH, headroom, Σ P&L by book); the netting set's checks (members' MtM, CE from the stored figures) are in its computed panel | the same generator |
| Q4 `stress-scenario-pnl` (`SSPNL`) | One page per scenario: `PNL` (Σ desks), `DESK_COUNT`, `WORST_DESK_PNL`, `BREACHES`, `WORST_LIMIT_USE`, `DESKS[]` (P&L, positive limit, limit use, status), `BY_DESK`; shown on the stress-scenario page (**Bank P&L under this scenario**, **Desks under this scenario**) | connector `mr-derived` of the market-risk pack (`tools/packgen/banking/var_kinds.py`); contract `stress-scenario-pnl.contract.yaml` |

**Visible to everyone** (decision 6): the derived pages are built once per business date and data version and shared; every user who may
open the kind sees them. **Live** (decision 3) is one predicate in the derived kinds and the exposure engine: a live status and a maturity
date not before the valuation date; a live-status trade past its maturity date is in no total and is counted and listed as a data-quality
warning (the exposure engine flags its set `PAST_MATURITY`).

**Pictures** of the new panels are in [EXPOSURE_GUIDE.md section 22](../guides/EXPOSURE_GUIDE.md#22-retiring-the-stored-version)
(netting set, counterparty, the collateral balance's and the credit limit's checks) and [VAR_GUIDE.md section 21.4](../guides/VAR_GUIDE.md)
(the stress scenario), made by `tools/docs/shots/computed.py` over the packs' samples.

**Sum currency** (section 3). Every derived total is made in USD and says so (`SUM_CURRENCY`). The proposed `convert:` option on derived
fields was **not** built: it needs a join from a derived kind to `fx-rate` by business date, which the derived connector does not have (it
reads one source kind), so a small, clean version was not possible. A set or counterparty whose members' `MTM_CURRENCY` is not all USD
says *not converted* (`CURRENCY_NOTE`), as designed; today every `MTM_CURRENCY` is USD (15,000 of 15,000), so every total is exact. The
per-entity configuration (`sums:` of section 3) is **ready to wire, not wired**: the engines that will convert (NSET, COLL, LIM, GRP) are
not built, the exposure engine computes in USD (its paths are USD), and a derived kind cannot convert, so the block would be a setting
nothing reads. It goes in with the first engine that sums in a chosen currency.

### 13.2 The test-data fixes (Q5)

Made in the generators (`tools/packgen/banking/data_results.py`, `tools/packgen/var/make_var_data.py`), then `make_data.py` (samples) and
`tools/testdata/make_testdata.py` (data/testdata) were run again. Trades, trade valuations, P&L vectors, scenario PVs, FX scenarios,
scenario sets and exposure paths are byte for byte as before, so **VAR BANK on 2026-09-30 stays USD 112,041,646** and no VaR figure moves.

| Fix | Decision or finding | What changed in the data |
|---|---|---|
| Stress limits positive | F5 | `stress-result.LIMIT` is a positive magnitude on every record (same size) |
| Collateral after haircut | F3 | `collateral-balance.HELD`, `POSTED`, `NET` = Σ of the positions' `VALUE` (market value less haircut); `netting-set.COLLATERAL` = that `NET`. Across the 76 balances the holdings' market value is 6,304.4m and their value after haircut 6,229.6m; `HELD + POSTED` is now 6,229.6m (was 6,304.4m) |
| One CSA per agreement and legal entity | decision 9 | `CSA-<counterparty>-<legal entity>` (CSA-ALDERSHOT-FRA …), one per netting set's legal entity, with `LEGAL_ENTITY`; `netting-set.CSA`, `collateral-balance.CSA` and `margin-call.CSA` name it; `agreement.CSA` became the list `CSAS`; the counterparty's hierarchy draws each CSA under the agreement and over the set it governs |
| Numeric CSA terms with a currency | section 5.3 | `CSA_CURRENCY` (USD), `MTA_US`, `MTA_THEM`, `MARGIN_FREQUENCY_DAYS` (1), `MPOR_DAYS` (10) beside `MTA` and `FREQUENCY`; the text `MPOR` is gone. The values are the old ones, so no exposure figure moves for this |
| One maturity-bucket set per purpose | decision 10, F23 | `display` (0-1Y, 1-2Y, 2-5Y, 5-10Y, 10Y+) and `limit` (0-1Y, 1-3Y, 3-5Y, 5-10Y, 10Y+) in `config/packs/counterparty-risk/config/maturity-buckets.yaml`, read by the generator; the data's buckets did not change |
| VaR limits only in `var-limit` | decision 8, F6 | `risk-hierarchy` and `portfolio` carry no `LIMIT_VAR`; `var-limit` holds every LL, MGMT and LEGAL node's limit and, as hierarchy `PORTFOLIO`, every portfolio's, in USD; the market-risk pack's `hierarchies:` read MGMT and LEGAL limits from `var-limit` (`limits: var-limit`) and the VaR engine a portfolio's from its `PORTFOLIO` row (a record that still carries `LIMIT_VAR` is read as a fallback). Same values: no limit use moves |
| A group's own approved limit | decision 7, F17 | `counterparty-group.LIMIT` is the group's own limit: a group of several counterparties is approved for 60 to 90% of its members' limits added (never below the largest), a group of one keeps its member's. Only GRP-MERIDIAN has two members: in the samples its limit 775,000,000 → 495,000,000 and its stored utilisation (Σ used ÷ limit) 61% → 96% |
| `netting-set.UTILISATION` dropped | decision 13, F8 | the field, its strip cell, its history column and BI's *Max utilisation* (the live-exposure report's card and table now show *Trades* and the largest net MTM) |

The retirement guard (`tools/contracts.policy.yaml` `removed:`) now fails the build if `netting-set.UTILISATION`,
`risk-hierarchy.LIMIT_VAR` or `portfolio.LIMIT_VAR` return.

### 13.3 The exposure engine re-pointed (E0)

`ExposureLoader` (drishti-server) reads a netting set's members and their current MtM from `trade-valuation` (a live status, the set in
`NETTING_SET`), not the set's stored `TRADES[]`; a date without trade valuations (a bank that sends none, a test lake) reads the stored
members as before and logs it. The collateral held today is the balance's holdings after haircut (`POSITIONS[].VALUE`, held positive,
posted negative; a balance without positions gives its `NET`). The limit is the counterparty's `credit-limit.LIMIT`, as before. A member
of a live status past its maturity date is in no figure and flags its set `PAST_MATURITY`. The engine's inputs and data-version triggers
gained `trade-valuation` and `credit-limit` (`drishti.ccr.exposure.kinds.valuation`, default `trade-valuation`).

**What changed in the exposure figures, and why.** The members and their MtM are the same on the test data (the stored copies equal the
valuations there), so every change comes from the collateral after haircut (F3): a smaller balance held raises the exposure of a set the
bank is collateralised for, a smaller balance posted lowers the exposure of a set it has posted to. On 2026-09-30 (USD):

| Scope | Figure | Before | After |
|---|---|---|---|
| NS-ALDERSHOT-NY | CE / EPE / peak PFE 95% | 212,580,686 / 37,365,431 / 355,255,470 | 214,706,654 / 37,446,771 / 357,381,438 |
| NS-ALDERSHOT-LDN | EPE / EEPE (posted 112.2m → 111.1m) | 14,738,051 / 18,764,913 | 14,729,920 / 18,756,782 |
| NS-ALDERSHOT-TKY | CE / EPE | 4,984,320 / 11,991,049 | 5,543,509 / 12,002,758 |
| NS-ALDERSHOT-FRA | EPE / peak PFE 95% | 1,837,009 / 30,610,762 | 1,832,126 / 30,534,120 |
| CPTY:CP-ALDERSHOT | CE / EPE / EEPE / peak PFE 95% | 217,565,006 / 64,894,762 / 261,628,419 / 399,970,549 | 220,250,163 / 64,977,553 / 263,701,831 / 401,456,820 |
| BANK (the demo run, 74 sets) | peak PFE 95% / EPE / CE | 2,858,060,180 / 945,577,981 / 1,532,007,701 | 2,897,620,439 / 947,202,991 / 1,586,811,699 |

The parity reference (`drishti-compute/src/test/resources/ccr/reference.json`) was written again by `make_ccr_data.py`; the exposure guide,
tutorial 26 and the knowledge pages quote the new figures.

### 13.4 Reconciliation (section 9.3, stage 2)

On the test data's 2026-09-30, the derived net MtM against the stored `netting-set.NET_MTM`: **55 of 76 sets are equal**; the 21 others
hold **827 trades of a live status past their maturity date** (MtM 16.7m in total), which **Live** leaves out and the stored total counts;
with them added back all **76 of 76** agree to the dollar. `CP-ALDERSHOT`: stored 520,405,666, derived 518,314,005 (18 trades past maturity
in NS-ALDERSHOT-NY), uncollateralised CE 680,037,675. On the packs' samples (the demo source moves the dates to today, so more trades are
past maturity there) the derived total equals the trade valuations as served on every set; a set differs from its stored copy where a
member's valuation is no longer of a live status (F11). The tolerances of section 9.3 stand: net MtM and trade count exact once Live is
applied.

### 13.5 Still open

- **Retire the stored figures** (stage 3 of section 9.3) with the NSET engine: the netting set's `NET_MTM`, `TRADE_COUNT`, `TRADES[]`,
  `BY_ASSET`, `COLLATERAL` and `LIMIT` (F7: its chart still draws the set's own `LIMIT`), the counterparty's `NET_MTM`.
- `convert:` on derived fields and the `sums:` configuration (13.1).
- BI's "Open MTM" and MtM measures still sum every trade-valuation row (F12): the BI datasets should filter Live.
- SA-CCR's stored RC still uses the collateral before haircut (F24, SACCR engine); CVA and the credit limits' use keep the generator's
  own profile (parked engines).
