<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# VaR: the data contract

*Version 1.3 · 2026-10-25 · Shared rules: [README.md](README.md) · Design: [VAR_ENGINE.md](../architecture/VAR_ENGINE.md),
[VAR_DATA_MODEL.md](../architecture/VAR_DATA_MODEL.md) · User guide: [VAR_EXPLORER.md](../guides/VAR_EXPLORER.md)*

This is the data contract of Drishti's VaR engine: what an upstream risk batch delivers, what Drishti builds from it, and what Drishti
answers. Every column, type, key and formula below was checked against the code and the committed test data on 2026-10-25. Where the
designs say otherwise, the code wins and the difference is noted (**Design differs**). The shared rules of every contract (the common
risk lake, keys, types, dates, signs, vectors, delivery, validation, answers, entitlements, versioning, retirement) are in
[README.md](README.md); this page states only where VaR differs or adds to them.

**Sources of truth checked:** `tools/packgen/var/make_var_data.py`, `positions.py`, `portfolios.py`, `factors.py`, `varmath.py`, `rules.py`;
`config/packs/market-risk/pack.yaml`, `config/packs/market-risk/config/about.yaml`, the pack's `samples/`; the test data in
`data/testdata/market-risk/` and its entries in `data/testdata/testdata.yaml`; `drishti-compute` (packages
`com.ash.drishti.compute.vector` and `com.ash.drishti.compute.var`); `drishti-server` (package `com.ash.drishti.server.var`); the Delta
plugin's vector reader (`DeltaTable.vectors`, `DeltaSourcePlugin.vectorColumns`); `tools/lake/stage.py` and `tools/lake/writers.py`;
`drishti.var` in `drishti-server/src/main/resources/application.yaml`; the market-risk pack's `positions:` section. Rechecked for version
1.3 (the build "VaR complete, part 1", 2026-10-25).

## Contents

1. [Summary](#1-summary)
2. [The measure in one page](#2-the-measure-in-one-page)
3. [Inputs from upstream engines](#3-inputs-from-upstream-engines)
4. [Reference and master data read](#4-reference-and-master-data-read)
5. [Conventions specific to VaR](#5-conventions-specific-to-var)
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

**What it is.** One-day historical-simulation value at risk (VaR) and expected shortfall (ES), with their decomposition (standalone,
component, incremental and marginal VaR, diversification) and backtest, for **any set of trades**: the bank, a node of a hierarchy, a
portfolio, a position, a trade, a basket, a rule, or any union, difference or intersection of these. It is computed when asked from
one P&L vector per trade (509 scenario P&Ls), never stored except as a capture.

**Commands and pages.** `VAR <word> <id> <GO>` opens the VaR explorer at a scope (`VAR DESK RATES <GO>`, `VAR BANK <GO>`,
`VAR CAPTURE CAP-20260930-1 <GO>`); `POS` and `PORT` open the position and portfolio pages; the API is `/api/v1/var`.

**Release and status.** 2.8.0, unreleased. Waves V0 to V3 and the build *VaR complete, part 1* (2026-10-25) are **AS BUILT** and
tested: the stored `var` and `var-node` kinds are retired, no input or fundamental kind carries a VaR figure, positions are derived by
Drishti, the test data holds every live trade, official end-of-day captures are taken and published, the data-load signal reloads the
engine, the risk lake keeps 7 years, and the gaps of section 10 are closed. What is still **PLANNED** is marked so; the product owner
approved it on 2026-10-25 (names may still change; each change is recorded in [section 16](#16-open-questions-and-the-change-table)).

| Part | Status | Where |
|---|---|---|
| Trade-grain P&L vectors in a `list<double>` column (`pnl-vector`), every row checked at load | AS BUILT | §3.2, §10 |
| Scenario set, risk hierarchies (MGMT, LEGAL), portfolio definitions (HIERARCHY, RULE, BASKET) | AS BUILT | §3.3–3.5 |
| **Positions derived by Drishti** when a date loads, by the market-risk pack's `positions:` rules (securities: book + security; listed: book + contract; OTC: book + trade); signed quantity; live statuses only; served as the `position` kind by the `var-positions` connector | AS BUILT | §3.7 |
| The in-memory engine: vector store, trade ordinals, node aggregates, planner, parallel sums | AS BUILT | §7.1 |
| VaR, ES, standalone, component, incremental, marginal, diversification; backtest | AS BUILT | §6 |
| Scope grammar `BANK LE DIV DESK BOOK NODE POS TRD CPTY PORT BASKET CAPTURE WHERE`, `+ - &`; `LE:` by the trades' legal entity; ids in any case | AS BUILT | §8 |
| API `/api/v1/var`, baskets and captures in the content store, entitlements by node, field masks, audit | AS BUILT | §9, §11 |
| Retire the stored `var` kind: `VAR-*` ids and `/v/var/<id>` open the explorer at the matching scope | AS BUILT | §14 |
| Retire the stored `var-node` kind: a node's history is the engine's **official end-of-day captures** | AS BUILT | §9.6, §14 |
| No computed column on `position`, `portfolio` or the core pack's `desk`: their pages show the engine's figures, computed as they open (`VAR_ENGINE`) | AS BUILT | §9.8, §14.3 |
| Official end-of-day captures of every node of MGMT and LEGAL, published to `risk-results/var-official` | AS BUILT | §9.6 |
| The data-load signal reloads the date and captures it (decided, open question 5) | AS BUILT | §3.9 |
| 7 years of vectors and their time travel (Delta log and removed files; lake maintenance never goes below) | AS BUILT | §3.1 |
| The full trade set (9,572 live trades) and 28 portfolios in the test data | AS BUILT | §13 |
| An optional "risk position" grouping for viewing (book × product × currency × underlying × maturity quarter) | PLANNED | §3.7 |
| Tiered memory: float32 hot copy in memory-mapped per-date local files sorted by hierarchy, sums in float64, Delta stays float64, history dates opened lazily, trade vectors read only on a drill or a trade basket, sharded across nodes; benchmark 1 M trades × 50 dates | PLANNED | §7.3, §12.2 |
| The common risk lake (domain `risk`, one connector `risk-store` for every risk pack) | PLANNED (VaR's tables already there) | §3.1 |
| A Python engine and a reactive connector beside the Java engine (Java keeps the additive aggregation; Python does what-if and heavy work); the computed kinds | PLANNED | §6.6, §9.7 |
| Scope words `GROUP:` and `NSET:` | PARKED (product owner, 2026-10-25: fundamentals and VaR only) | §8.5 |

## 2. The measure in one page

**What it answers:** *how much could this set of trades lose in one day at 99% confidence, and where does that loss come from?* For
risk managers (limits, drill-down from the bank to a trade), traders (what a trade does to the desk's VaR), and auditors (every number
reproducible from its inputs and stamped with them).

**The formula in words.**

1. The upstream risk batch values each live trade under each of the N = 509 historical scenario days of the scenario set (full
   revaluation), giving the trade's **P&L vector**: element *i* is the trade's P&L in USD if the market moved as it did on scenario day
   *i*. Gains are positive.
2. A scope's vector is the **sum of its trades' vectors**, element by element (P&L adds). Long and short trades of one instrument net
   in the sum; overlapping portfolios count a trade once (set algebra on trade ordinals).
3. **VaR(α)** is minus the k-th worst value of the scope's vector, k = ⌈N (1 − α)⌉: at α = 99% the **6th worst of 509**.
4. **ES(α)** is minus the mean of the k worst values, k = ⌈N (1 − α)⌉: at α = 97.5% the **mean of the 13 worst**.
5. **Component VaR** of a part is minus its mean P&L over the scope's scenarios ranked k − 2 to k + 2 (a window of 5), scaled so that
   the parts add up to the scope's VaR exactly. **Component ES** is minus its mean over the scope's 13 ES scenarios (adds up to ES).
6. **Backtest:** a day is an exception when its hypothetical P&L is a loss larger than the previous day's VaR; over 250 days, the Basel
   traffic light, Kupiec's and Christoffersen's tests.

**Methodology.** Historical simulation, equal weights, 1-day horizon (the Basel internal-models VaR; ES at 97.5% as in FRTB IMA, without
FRTB's liquidity horizons or stressed calibration). Age-weighted and filtered methods, 10-day VaR and stressed VaR are not built.

## 3. Inputs from upstream engines

### 3.1 Where they live

All VaR inputs are Delta tables in the **common risk lake**, domain `risk`, one table per kind, served by the market-risk pack's
connector `risk-store` (`plugin: delta`, `root: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}`, `domain: risk`). The
engine reads the kinds named by `drishti.var.kinds` (defaults below), whichever connector the kind is routed to. The official results
it publishes live beside them, never read back (§9.6).

```
${DRISHTI_DELTA_ROOT}/
├── risk/                                        the inputs (engines read these)
│   ├── pnl-vector/   business_date=YYYY-MM-DD/part-*.parquet    one row per trade per date: the P&L vector and the trade's terms
│   ├── scenario-set/ business_date=YYYY-MM-DD/                  one row per scenario set per date
│   ├── risk-hierarchy/ business_date=YYYY-MM-DD/                one row per hierarchy node per date
│   └── portfolio/    business_date=YYYY-MM-DD/                  one row per portfolio definition per date
└── risk-results/
    └── var-official/ business_date=YYYY-MM-DD/                  the official end-of-day figures, a row per node and result version
```

Positions are not a table: Drishti derives them when it loads a date (§3.7). The retired `position` and `var-node` tables are gone.

**The physical table** (every kind, `tools/lake/writers.py`): `id` STRING (the kind's key value), `doc` STRING (the whole document as
JSON), `business_date` DATE (the partition), the **promoted columns** of the pack's `layout.<kind>.columns` (DOUBLE when every value of
the column is a number, else STRING) and the **vector columns** of `layout.<kind>.vectors` (`list<double>`; the document keeps the
marker `{"$vector": "PNL"}` in their place). A field not promoted lives only inside `doc`. In the column tables below, *Delta* is the
promoted column's type, or `doc` when the field is only in the document.

| Kind | `drishti.var.kinds` key (default) | Lake key (`id`) | Promoted columns (pack.yaml) | Vector columns |
|---|---|---|---|---|
| `pnl-vector` | `vector` (`pnl-vector`) | `VECTOR_ID` | 23 (§3.2) | `PNL` |
| `scenario-set` | `scenario-set` (`scenario-set`) | `SCENARIO_SET_ID` | none | none |
| `risk-hierarchy` | `hierarchy` (`risk-hierarchy`) | `NODE_ID` | `NODE_ID`, `HIERARCHY`, `LEVEL`, `PARENT_ID`, `NAME`, `LIMIT_VAR` | none |
| `portfolio` | `portfolio` (`portfolio`) | `PORTFOLIO_ID` | `PORTFOLIO_ID`, `NAME`, `TYPE`, `OWNER`, `LIMIT_VAR` | none |
| `position` (served, not stored) | `position` (`position`) | `POSITION_ID` | (the `var-positions` connector) | none |

**Partitioning, sort and files (as built).** Partitioned by `business_date` only. `lake build` sorts rows by `id` within each batch of
at most 50,000 rows (`--batch-rows`, also bounded by `--batch-mb`, 128) and writes one commit per table with Drishti's Java writer
(or one per date and batch with `deltalake`); the market-risk layouts set no `sort-by`, `file-rows` or `row-group-rows` for these
kinds. The engine does not depend on the lake's order: it sorts a date into hierarchy order in memory (§7.1). Encoding: the writers'
defaults (Snappy; no `BYTE_STREAM_SPLIT`).
**Design differs:** VAR_DATA_MODEL.md section 7 proposes partitions by `BUSINESS_DATE` and `SCENARIO_SET_ID`, rows sorted by legal
entity, desk, book and position, files of about 512 MB, row groups of about 64 MB, `BYTE_STREAM_SPLIT` and ZSTD for `PNL`; none of
these is built. The sort and the partition by scenario set remain planned; file sizing is open question 7.

**Retention: 7 years (as built; decided, open question 10).** Every table `lake build` creates in the domains `risk` and `risk-results`
keeps its Delta log and its removed files 7 years (`delta.logRetentionDuration` and `delta.deletedFileRetentionDuration` of `interval
2557 days`; both writers), so its time travel reaches back that far; the published `var-official` table is created the same way by the
server (`drishti.var.official.retention-years`, 7). Lake maintenance (`lake maintain`, Admin → Lakes) never vacuums a file nor deletes
a business date of those domains younger than 7 years, whatever a table's settings say: a lower `vacuum-hours` or `keep-business-days`
is raised (61,368 hours; 1,827 business days) and the run says so (`retention-floor`); a lake's `retain-years:` changes it per domain
([LAKE_MAINTENANCE.md](../admin/LAKE_MAINTENANCE.md)). The connector only reads, and never vacuums.

**The common risk lake (planned).** The VaR tables are already in domain `risk` behind `risk-store`. From 2.8.0 every risk pack reads
the same lake through that one connector ([README.md section 2](README.md#2-the-common-risk-lake)); nothing changes for VaR's tables.

### 3.2 `pnl-vector`: one P&L vector per trade (AS BUILT)

**Grain:** one row per **trade**, business date and scenario set. **Key:** `VECTOR_ID` (unique per date); the natural key is
(`BUSINESS_DATE`, `SCENARIO_SET_ID`, `TRADE_ID`): a trade id twice on a date refuses the date (`DRS-5202`, §10). **Row count:** every
live trade the batch valued; test data 9,572 on 2026-09-30.
**Design differs:** VAR_ENGINE.md section 5.2 and VAR_DATA_MODEL.md section 1 propose one row per *position*; the product owner chose
the trade (2026-10-24, VAR_DATA_MODEL.md section 18.1). A bank whose batch values positions may send one row per position, its
`TRADE_ID` the position's id (an OTC rule then makes it a position of its own).

"Engine" says whether the server engine reads the field: **R** (read as a column when the table keeps it promoted; `LEGAL_ENTITY`,
`DESK` and `TT_BOOK` must be promoted or the load fails), **C** (read for a check of §10 when promoted), **–** (kept for people, links
and audit).

| Column | JSON | Delta | Req. | Engine | Unit | Meaning | Example |
|---|---|---|---|---|---|---|---|
| `VECTOR_ID` | id | `id` STRING | required | – | | `PV-<TRADE_ID>-<SCENARIO_SET_ID>`; the lake key | `PV-MX-20000274-HS-509` |
| `TRADE_ID` | id | STRING | required | R | | the booking system's trade id (text even when all digits). When null the engine uses the row's `id` | `MX-20000274` |
| `SECURITY_ID` | id | STRING | for securities and listed products | R | | the security or exchange contract the trade is in: a position rule's key (§3.7). The test data's are synthetic | `SEC-WARRANT-USD-01` |
| `ISIN`, `TICKER`, `CONTRACT` | id | STRING | optional | R (when a rule names them) | | other keys a rule may name, read when promoted | |
| `INSTRUMENT` | text | STRING | optional | R | | the instrument's name, the position's label | `Equity warrant USD 2027-05` |
| `BUSINESS_DATE` | date | `business_date` DATE | required | R | | the close of business the vector belongs to | `2026-09-30` |
| `SCENARIO_SET_ID` | id | STRING | required | C | | the scenario set whose `SCENARIO_DATES` order the vector; another set's id refuses the date (`DRS-5203`) | `HS-509` |
| `SOURCE_SYSTEM` | text | doc | optional | – | | booking system | `MUREX` |
| `TRADE_VERSIONS` | text | doc | optional | – | | the trade version the batch priced (`v<TRADE_VERSION>`) | `v4` |
| `TRADE_STATUS` | text | STRING | optional | R | | the trade's status the batch priced: only the live statuses count (§3.7); none: it counts | `AMENDED` |
| `MATURITY_DATE` | date | STRING | optional | R | | a trade matured before the business date does not count | `2027-05-26` |
| `LEGAL_ENTITY` | id | STRING | required | R | | the bank's legal entity that booked the trade: `LE:` (§8.5), 1st sort key in memory | `LE-LDN` |
| `DIVISION` | id | STRING | optional | R | | division of the desk | `DIV-MARKETS` |
| `DESK` | id | STRING | required | R | | desk (2nd sort key) | `DESK-EQD` |
| `TT_BOOK` | id | STRING | required | R | | book (3rd sort key); a trade without a book is in no node | `BOOK-EQD-2` |
| `PORTFOLIO` | text | STRING | optional | R | | booking portfolio on the trade | `BOOK-EQD-2` |
| `STRATEGY` | text | STRING | optional | R | | trading strategy | `""` |
| `PRODUCT_GROUP` | text | STRING | optional | R | | product group: picks the position rule | `Equity` |
| `PRODUCT_TYPE` | text | STRING | optional | R | | product type as booked: picks the position rule | `WARRANT` |
| `CURRENCY` | ccy | STRING | optional | R | | the trade's currency (not the vector's) | `USD` |
| `COUNTERPARTY` | id | STRING | optional | R | | counterparty id: `CPTY:` (§8.5) | `CP-SUMMIT` |
| `DIRECTION` | text | STRING | optional | R | | `LONG` (quantity ≥ 0) or `SHORT` | `SHORT` |
| `QUANTITY` | amount | DOUBLE | optional | R | units or notional | signed quantity: positive bought, negative sold (the batch applies the sign of `BUY_SELL_CODE`) | `-179000000.0` |
| `TAGS` | text | STRING | optional | R | | the trade's tags joined by `\|` (`internal`, `option`): a position's are the union | `""` |
| `REPORTING_CCY` | ccy | STRING | required | C | | the vector's currency; another than `USD` rejects the row (`DRS-5201`) | `USD` |
| `PV` | amount | DOUBLE | optional | R | USD | present value on the business date (a position's market value; the size behind a trade's marginal VaR) | `3143714` |
| `PNL` | vector | `list<double>` | required | R | USD | the trade's P&L under each scenario, in `SCENARIO_DATES` order, gain positive; length = `SCENARIO_COUNT` | `[4169, -3347, -1306, …]` (509) |
| `STATUS` | text | STRING | required | C | | `ok`, `proxied` (priced by a proxy) or `failed` (then `PNL` null; a failed row with a vector is rejected) | `ok` |
| `PRICER` | text | doc | optional | – | | pricer used | `demo-factor-model` |
| `MODEL_VERSION` | text | doc | optional | – | | pricer version | `demo-2` |
| `COMPUTED_AT` | timestamp | doc | optional | – | UTC | when the batch valued the trade | `2026-09-30T22:30:00Z` |
| `BATCH_ID` | id | STRING | required | C | | the run: one per date (several refuse the date, `DRS-5203`) | `B-2026-09-30-01` |
| `RESTATEMENT` | integer | doc | optional | – | | 0 for the first run, 1, 2, … for restatements | `0` |

`POSITION_ID` and `INSTRUMENT_ID` are no longer delivered: the engine derives them (§3.7); an upstream value is not read.

**The engine's read.** `VarLoader` asks the connector for the date's rows through `SourceRouter.vectors(kind, "PNL", columns, asOf,
sink)`: `TRADE_ID`, the engine's columns of `VarBook.TRADE_TEXTS` and `TRADE_NUMBERS`, every key field a position rule names, and the
check columns (`TRADE_STATUS`, `MATURITY_DATE`, `BATCH_ID`, `SCENARIO_SET_ID`, `REPORTING_CCY`, `STATUS`), each read when the table
keeps it promoted (`SourceRouter.columnar`); a connector that cannot say is asked for the engine's own columns only. The vector is
streamed into the vector store; no document is parsed. Each row passes `VarIntake` (§10): not live, it is left out (counted as
`excluded`); a wrong length, a null or NaN, another currency or a failed row with a vector is rejected (`DRS-5201`, counted and listed in
the provenance); otherwise it gets its position (§3.7) and joins the store. A null `PNL` is a **failed** row: counted
(`provenance.failed`), never taken as zero.

**The vector** ([README.md section 8](README.md#8-vectors-and-other-arrays)):

| Property | Contract |
|---|---|
| Type | `list<double>` (Parquet LIST of DOUBLE), float64; JSON array of numbers in the test data |
| Length | N = the scenario set's `SCENARIO_COUNT` (509), the same for every row of the date |
| Order | element *i* is the P&L under the move of `SCENARIO_DATES[i]` of the scenario set of the same `BUSINESS_DATE`; oldest first |
| Currency | USD (`REPORTING_CCY`); amounts in currency units, not thousands; the test data's are whole USD |
| Sign | gain positive, loss negative, from the bank's point of view |
| Horizon | 1 day (`HORIZON_DAYS` of the scenario set) |
| Missing | no `null`, `NaN` or infinity inside a vector; a trade the batch could not value has `STATUS: failed` and `PNL` null |

### 3.3 `scenario-set`: the scenario days (AS BUILT)

**Grain:** one row per scenario set and business date. **Key:** `SCENARIO_SET_ID` (with `business_date`). **Row count:** 1 per date.
A date with two scenario sets is not loaded (`DRS-5203`; several sets per date are open question 6), nor one whose `SCENARIO_DATES` are
not strictly increasing or not all before the business date.

| Column | JSON | Delta | Req. | Engine | Meaning | Example |
|---|---|---|---|---|---|---|
| `SCENARIO_SET_ID` | id | `id` STRING | required | R | the set; stamped on every answer | `HS-509` |
| `BUSINESS_DATE` | date | `business_date` DATE | required | R | the date whose vectors the set orders | `2026-09-30` |
| `SCENARIO_DATES` | date list | doc | required | R | the N scenario days, oldest first, strictly increasing; index *i* of every vector | `["2024-09-17", …, "2026-09-29"]` (509) |
| `SCENARIO_COUNT` | integer | doc | required | – | N = length of `SCENARIO_DATES` | `509` |
| `WINDOW_START` | date | doc | required | – | `SCENARIO_DATES[0]` | `2024-09-17` |
| `WINDOW_END` | date | doc | required | – | `SCENARIO_DATES[N-1]`: the business day before `BUSINESS_DATE` | `2026-09-29` |
| `HORIZON_DAYS` | integer | doc | required | – | 1 | `1` |
| `REVALUATION` | text | doc | optional | – | `full` or `sensitivities` | `full` |
| `WEIGHTS` | number list | doc | optional | – | age weights for a weighted method (not used: equal weights) | `null` |
| `SOURCE_BATCH` | id | doc | optional | – | the batch that produced the set and its vectors | `B-2026-09-30-01` |
| `DESCRIPTION` | text | doc | optional | – | | `Historical simulation, 509 business days, equal weights` |

Scenario days are business days of the USNY calendar (`tools/packgen/var/make_var_data.py`, `business_days`).

### 3.4 `risk-hierarchy`: the trees and their limits (AS BUILT)

**Grain:** one row per node and business date. **Key:** `NODE_ID` = `<HIERARCHY>~<node>`. **Row count:** 88 in the test data (MGMT:
1 bank, 2 divisions, 10 desks, 30 books; LEGAL: 1 bank, 4 legal entities, 10 desks, 30 books). MGMT is the **primary hierarchy**: the
planner and the history use it.

| Column | JSON | Delta | Req. | Engine | Unit | Meaning | Example |
|---|---|---|---|---|---|---|---|
| `NODE_ID` | id | STRING (also `id`) | required | R | | `<HIERARCHY>~<id>` | `LEGAL~LE-NY` |
| `HIERARCHY` | text | STRING | required | R | | `MGMT` or `LEGAL` (any name works; scope words look in these two) | `LEGAL` |
| `LEVEL` | text | STRING | required | R | | `bank`, `division`, `legal-entity`, `desk`, `book` | `legal-entity` |
| `PARENT_ID` | id | STRING | required (null at the root) | R | | parent node; the engine builds children from it | `LEGAL~BANK` |
| `NAME` | text | STRING | required | R | | display name | `LE-NY` |
| `REF` | record | doc | optional | – | | the master the node is: `{KIND, ID}` | `{"KIND": "legal-entity", "ID": "LE-NY"}` |
| `CHILDREN` | id list | doc | optional | – | | child node ids (informational; the engine uses `PARENT_ID`) | `["LEGAL~DESK-COMM", …]` |
| `BOOKS` | id list | doc | required | R | | **every** book beneath the node (a book node lists itself); the node's members are these books' trades | `["BOOK-COMM-1", …]` (15) |
| `LIMIT_VAR` | amount | DOUBLE | optional | R | USD | the node's 1-day 99% VaR limit | `8500000` |
| `BUSINESS_DATE` | date | `business_date` DATE | required | R | | | `2026-09-30` |
| `VALID_FROM`, `VALID_TO` | date | doc | optional | – | | validity of the node (informational) | `2026-01-02`, `null` |

### 3.5 `portfolio`: stored portfolio definitions (AS BUILT)

**Grain:** one row per portfolio and business date. **Key:** `PORTFOLIO_ID` (`PF-…`). **Row count:** 28 in the test data. A portfolio
is a **definition**: its positions, VaR, ES and contributions are the engine's, computed when asked (the portfolio page, `PORT:<id>`,
§9.8). The figures the demo generator used to store on it were removed in 2.8.0 (§14.3).

| Column | JSON | Delta | Req. | Engine | Meaning | Example |
|---|---|---|---|---|---|---|
| `PORTFOLIO_ID` | id | STRING (also `id`) | required | R | the portfolio; scope `PORT:<id>` | `PF-RATES-DESK` |
| `NAME` | text | STRING | required | R | | `Rates desk (New York)` |
| `OWNER` | text | STRING | required | R | user or team | `rates-risk` |
| `TYPE` | text | STRING | required | R | `HIERARCHY`, `RULE` or `BASKET` | `HIERARCHY` |
| `DEFINITION` | record | doc | required | R | `{NODE: <NODE_ID>}`, `{RULE: <rule text>}` (§8.4) or `{MEMBERS: [{POSITION_ID} or {TRADE_ID}, …]}` | `{"NODE": "MGMT~DESK-RATES"}` |
| `ROLLS_UP_TO` | record | doc | optional | – | `{KIND, ID}` the portfolio reports under | `{"KIND": "desk", "ID": "DESK-RATES"}` |
| `LIMIT_VAR` | amount | DOUBLE | optional | R | the portfolio's VaR limit, USD (an input a risk manager sets) | `106000000` |
| `BUSINESS_DATE`, `VALID_FROM`, `VALID_TO` | date | `business_date` / doc | required / optional | – | | `2026-09-30`, `2026-01-02`, `null` |

A BASKET's `POSITION_ID` members name derived positions (§3.7): `POS-<TT_BOOK>~<security>` or `POS-<TT_BOOK>~<TRADE_ID>`.

**How a definition becomes a scope** (`VarDate.Portfolio.of`): HIERARCHY → `NODE:<NODE>`; RULE → `WHERE:<RULE>`; BASKET → `POS:<ids>`
and/or `TRD:<ids>` joined by ` + ` (a `TRADE_ID` member is that trade only; an empty basket is `TRD:NONE`).

### 3.6 `position`: the input table (RETIRED in 2.8.0)

Until 2.8.0 the demo generator wrote a `position` table (positions grouped by product type, currency, underlying and maturity quarter,
with VaR figures on them). It is gone: positions are derived by Drishti (§3.7), and no position carries a stored figure (§14.3).

### 3.7 Positions derived by Drishti (AS BUILT)

Drishti derives positions itself, on the server, each time it loads a business date (product owner, 2026-10-25), from the date's
`pnl-vector` rows (the trades as the batch priced them) and the **position rules**. This replaces the demo grouping of
`tools/packgen/var/positions.py` before 2.8.0.

| Rule | Contract (as built) |
|---|---|
| Live trades | `TRADE_STATUS` one of the live statuses (`NEW`, `VALIDATED`, `AMENDED`; configurable); a row with no status counts (the batch valued it); `MATURITY_DATE` before the business date: not live. A row not live is left out of the date (`provenance.excluded`) |
| Signed quantity | `QUANTITY` as delivered: `abs(QUANTITY)` when bought, `−abs(QUANTITY)` when sold (`BUY_SELL_CODE` starting with `S`): the batch applies it, as the demo generator does |
| Which rule | the first rule whose `groups` hold the trade's `PRODUCT_GROUP` or whose `types` hold its `PRODUCT_TYPE` (compared ignoring case; a rule naming neither takes every trade); configuration, never code |
| Securities | (`TT_BOOK`, the first of `SECURITY_ID`, `ISIN`, `TICKER` the trade carries): `POSITION_ID = POS-<TT_BOOK>~<security>` |
| Listed derivatives | (`TT_BOOK`, the first of `SECURITY_ID`, `CONTRACT`): `POSITION_ID = POS-<TT_BOOK>~<contract>` |
| OTC | (`TT_BOOK`, `TRADE_ID`): one position per trade; `POSITION_ID = POS-<TT_BOOK>~<TRADE_ID>`, `INSTRUMENT_ID` = the trade id. A securities or listed trade without a key field is OTC too |
| Direction | `LONG` when the net signed quantity > 0, `SHORT` < 0, `FLAT` = 0 |
| Position columns | the first trade's instrument, hierarchy, product and currency; the counterparty when every trade shares one (else empty); the union of the trades' tags; `QUANTITY` the signed sum, `NOTIONAL` the gross, `MARKET_VALUE` the trades' PV, `TRADE_COUNT`, `LONG_TRADES`, `SHORT_TRADES`; `POSITION_RULE` (`securities`, `listed`, `otc`), `SECURITY_ID`, `POSITION_TYPE` (`fungible`, `single-trade`), `STATUS` (`open`, `flat`) |
| Risk positions | an optional second grouping for viewing only (book × product type × currency × underlying × maturity quarter): **PLANNED** |

**The rules** are the market-risk pack's `positions:` section (in `pack.yaml`, kept in `tools/packgen/banking/kept/market-risk.yaml`);
when no loaded pack has one, the server's `drishti.var.positions` (§6.1; default: the live statuses, no rule, so one position per
trade). The demo's:

```yaml
positions:
  live-statuses: [NEW, VALIDATED, AMENDED]
  rules:
    - name: securities                  # bonds, notes, bills, shares and warrants: by book and security
      groups: [Fixed income]
      types: [EQ_CASH, WARRANT, CONVERTIBLE_BOND, INFL_LINKED_BOND, T_BILL, COMMERCIAL_PAPER, CERT_DEPOSIT]
      key: [SECURITY_ID, ISIN, TICKER]
    - name: listed                      # exchange-traded futures and options: by book and contract
      types: [BOND_FUTURE, STIR_FUTURE, STIR_FUT_OPT, EQ_FUTURE_SINGLE, EQ_INDEX_FUTURE, EQ_OPTION_LISTED, EQ_INDEX_OPTION, CMD_FUTURE,
              CMD_FUT_OPTION, ComFut, EMISSIONS_FUTURE]
      key: [SECURITY_ID, CONTRACT]
    - name: otc                         # everything else: one position per trade
```

**Where positions live (decided, open question 2).** In memory, with their date: rebuilt from the date's trades and the rules every time
the date is loaded, so the same inputs and rules always give the same positions (reproducible). They are not stored in the lake nor in a
database. Each **official end-of-day capture** records the membership the rules derived (every position with its trades, by book, and
each node's books) and the rules' text and digest (§9.6), so an official figure can be recomputed from its inputs years later. They are
served as the `position` kind by the market-risk pack's `var-positions` connector (`plugin: var-positions`, the engine's memory; `POS
<id>`, `/v/position/<id>`, search), with no figure on them: the position page shows the engine's (§9.8).

The served document: `POSITION_ID`, `BUSINESS_DATE`, `POSITION_RULE`, `SECURITY_ID`, `INSTRUMENT_ID`, `INSTRUMENT`, `POSITION_TYPE`,
`DIRECTION`, `QUANTITY`, `GROSS_QUANTITY`, `TRADE_COUNT`, `LONG_TRADES`, `SHORT_TRADES`, `TRADE_IDS`, `TRADES` (`TRADE_ID`, `QUANTITY`,
`DIRECTION`, `PV`, `PRODUCT_TYPE`, `COUNTERPARTY` each), `LEGAL_ENTITY`, `DIVISION`, `DESK`, `TT_BOOK`, `PORTFOLIO`, `STRATEGY`,
`COUNTERPARTY`, `PRODUCT_GROUP`, `PRODUCT_TYPE`, `CURRENCY`, `COUNTERPARTIES`, `MARKET_VALUE`, `NOTIONAL`, `TAGS`, `STATUS`,
`SCENARIO_SET_ID`, `VAR_SCOPE` (`POS:<id>`), `DERIVED_BY`.

**The test data (decided, open question 3).** The committed trades carry no security id: the demo risk batch
(`tools/packgen/var/positions.py`) gives every trade of a securities or listed rule keyed by `SECURITY_ID` a deterministic synthetic id,
a few per product type and currency (about six trades each, chosen by a hash of the trade id): `SEC-<PRODUCT_TYPE>-<CURRENCY>-<nn>` for
securities, `XCH-…` for listed. On 2026-09-30: 2,159 trades in 358 securities; 7,984 positions (359 securities, 212 listed, 7,413 OTC),
476 of them fungible and 111 netting buys and sells. A bank's batch copies the trade's own security id.

### 3.8 `var-node`: the stored node history (RETIRED in 2.8.0)

Until 2.8.0 the demo generator stored every node's VaR, ES and hypothetical P&L over 250 days (`var-node`). The table, its samples,
generator and mnemonic `VNODE` are gone: a node's history is its **official end-of-day captures** (§9.6), and `VN-<NODE_ID>` opens the
explorer at `NODE:<NODE_ID>` (§14).

### 3.9 Cadence, due time and the delivery sequence

| Item | Contract |
|---|---|
| Cadence | once per business date (USNY calendar), after the close |
| Due time | **to be agreed** (no `loads:` expectation in the market-risk pack today; open question 4) |
| Complete when | every live trade booked on the date has a `pnl-vector` row (`ok`, `proxied` or `failed`), the scenario set is written, and the hierarchy and portfolios of the date are present |
| One run | all rows of a date carry one `BATCH_ID` and the scenario set's `SCENARIO_SET_ID`; otherwise the date is not loaded (`DRS-5203`) and the date before it is answered, with a warning |
| Restatement | rewrite the whole date (`lake build` replaces a business date); `RESTATEMENT` + 1; a new `BATCH_ID` |

**Sequence** ([README.md section 9](README.md#9-delivery-writing-a-business-date)):

```
 upstream batch                                   Drishti
 ──────────────                                   ───────
 1 write scenario-set row for D ───────────────►  (lake)
 2 write pnl-vector rows for D (one BATCH_ID) ──►  (lake)
 3 write risk-hierarchy / portfolio rows for D ─►  (lake; when changed)
 4 POST /api/v1/packs/market-risk/loads           drops the connector caches of the kind, verifies the date, runs alerts, tells
   {kind, businessDate, status, rows, batchId}    people (DATA_LOADS.md); for pnl-vector, scenario-set, risk-hierarchy, portfolio
   (or drishti.py data landed)                    (and trade): the VaR engine drops date D, then captures D officially (§9.6)
 5 the next VaR request for D (or the newest) ──►  loads D: scenario set, vectors (checked, positions derived), hierarchy,
                                                   portfolios; sorts, sums every node; answers
```

**As built (decided, open question 5).** A `ready` load of an input kind (`drishti.var.kinds`: `pnl-vector`, `scenario-set`,
`risk-hierarchy`, `portfolio`) or of `trade` drops that date from the engine (the same as `POST /api/v1/var/reload` for the date, audited
`var-reload`), so a restated date is never stale, then captures the date officially in the background (§9.6): a date whose inputs are
unchanged is not captured again, a restated one gets the next result version. A **new** date is picked up without a signal (each request
asks the connector for the newest date with vectors); at start the server captures the newest date when it has no official record
(`drishti.var.official.on-start`). **Hot dates:** at most `drishti.var.hot-dates` (2) dates stay loaded, the newest first; the date just
used always stays; a dropped date's cached answers go too.

## 4. Reference and master data read

**The server engine reads no master directly** (decided 2026-10-25): everything it needs is denormalised onto the `pnl-vector` rows (the
trade's terms as the batch priced them: hierarchy, product, security, status, maturity, quantity) and the `risk-hierarchy` rows (books
per node). Positions are derived from those rows (§3.7), not from the trade master: one read per date, the version the batch priced
(`TRADE_VERSIONS`), no join across the three booking systems at load. Masters are read by the demo generator and by the pages' links.

| Master (kind) | Key | Read by | What for |
|---|---|---|---|
| `trade` (ENDUR, CALYPSO, MUREX connectors; TRD) | `TRADE_ID` | the generator (the batch's copy of each trade); the pages' links | live trades, status, maturity, book, signed quantity, product and currency, version; the security id a bank's batch copies |
| `desk` (core pack; DESK) | `DESK_ID`, `BOOKS[].ID` | generator | book → desk, desk → legal entity, desk names; the hierarchies |
| `book` (BOOK) | `TT_BOOK` | pages (links) | |
| `legal-entity` (LE) | `LEGAL_ENTITY` | generator (via desks); pages | `LE:` reads the trades' own `LEGAL_ENTITY` (§8.5) |
| `trade-valuation` (TVAL) | `VALUATION_ID` = `TVAL-<TRADE_ID>` | generator | `MTM` → `PV` |
| `counterparty` (CPTY) | `COUNTERPARTY` | pages (links) | `CPTY:` reads the trades' own `COUNTERPARTY` (§8.5) |
| `lei-entity` (LEI), `netting-set` (NSET) | `LEI`, `NETTING_SET` | `GROUP:` and `NSET:` scopes (parked) | |

Positions link to trades (`graph.fields.TRADE_IDS` → `trade`); the explorer links each trade row to `TRD:<id>` and each node to its
`NODE:` scope.

## 5. Conventions specific to VaR

| Convention | Rule |
|---|---|
| P&L sign | gain positive, loss negative ([README.md section 7](README.md#7-currencies-units-and-signs)) |
| VaR, ES | **reported as positive numbers** for a loss (VaR = −(k-th worst P&L)); a scope whose k-th worst scenario is a gain has a **negative** VaR, shown as such |
| Component VaR | signed: a hedge has a negative component; components add up to the scope's VaR |
| Currency | USD for every vector and every figure (`REPORTING_CCY`); no FX conversion in the engine |
| Rounding | the API and captures round figures to whole currency units (`Math.round`; `NaN` and infinity become `0`); `pct`, `limitUse`, `confidence` and the test statistics are unrounded doubles |
| Confidence | VaR 99% (`drishti.var.confidence`), ES 97.5% (`drishti.var.es-confidence`); a value outside (0.5, 1) falls back to the default |
| Rank rule | `kth-worst`: k = ⌈N (1 − α) − 10⁻⁹⌉, clamped to [1, N]. N = 509: k = 6 at 99% (509 × 0.01 = 5.09 → 6), 13 at 97.5% (12.725 → 13) |
| Ties | scenarios with equal P&L are ordered by index, the older scenario first (the same rule in `VarMath.order` and `varmath.order`) |
| Horizon | 1 day; no √10 scaling anywhere |
| Quantity | positive long, negative short; `DIRECTION` says the same in words |
| Ids | case: the ids of `POS`, `TRD` and `CPTY` are kept as written and matched exactly, else ignoring case (a booking system's ids are text and may be lower case; two ids that differ only in case are ambiguous and match neither); the other words' arguments are upper case, except basket names and capture ids (§8.2) |

## 6. The computation

### 6.1 Parameters (the `drishti.var` settings)

Bound by `VarProperties` (`@ConfigurationProperties("drishti.var")`); `GET /api/v1/var/status` shows the effective values.

| Setting | application.yaml | Default when unset or invalid | Meaning |
|---|---|---|---|
| `drishti.var.enabled` | `${DRISHTI_VAR_ENABLED:true}` | `true` | the engine and its API; off: every VaR request answers `403 DRS-5002` |
| `drishti.var.confidence` | `0.99` | 0.99 (outside (0.5, 1)) | VaR's α |
| `drishti.var.es-confidence` | `0.975` | 0.975 | ES's α |
| `drishti.var.rank-rule` | `kth-worst` | `kth-worst`; any value starting `interp` is `interpolated` | §6.2 |
| `drishti.var.component-window` | `5` | 5 (below 1) | scenarios component VaR averages over |
| `drishti.var.component-scale-to-var` | `true` | `true` | scale components to add up to VaR (else to the smoothed VaR) |
| `drishti.var.threads` | `${DRISHTI_VAR_THREADS:0}` | every core (0 or less) | the sum pool's threads |
| `drishti.var.parallel-rows` | `4096` | 4096 | fewest rows a sum splits across threads |
| `drishti.var.memory-budget-mb` | `${DRISHTI_VAR_MEMORY_MB:0}` | a quarter of the JVM's max heap (0 or less) | most bytes of vectors **one date** may hold (§7.2); over it the date answers `503 DRS-5204` |
| `drishti.var.rows-per-chunk` | `65536` | 65,536 | rows per `double[]` chunk of the vector store |
| `drishti.var.hot-dates` | `2` | 2 | business dates kept loaded |
| `drishti.var.history-days` | `250` | 250 (1 or less) | history and backtest window |
| `drishti.var.histogram-bins` | `42` | 42 (below 5) | bins of the P&L histogram |
| `drishti.var.max-parts` | `5000` | 5,000 | most parts a contribution table computes; more: the largest by gross size (§9.1) |
| `drishti.var.path-page` | `60` | 60 | rows per page of the ranked P&L path |
| `drishti.var.kinds.vector`, `.position`, `.hierarchy`, `.scenario-set`, `.portfolio` | the market-risk kinds | `pnl-vector`, `position`, `risk-hierarchy`, `scenario-set`, `portfolio` | the kinds read (and `position`: the kind the derived positions are served as) |
| `drishti.var.access` | `{}` | no restriction | per role and node: `positions` or `aggregate` (§11) |
| `drishti.var.positions.live-statuses`, `.rules` | `[NEW, VALIDATED, AMENDED]`, `[]` | the same | the position rules when no loaded pack has a `positions:` section (§3.7) |
| `drishti.var.official.enabled`, `.publish`, `.root`, `.retention-years`, `.on-start` | `${DRISHTI_VAR_OFFICIAL:true}`, `true`, `${DRISHTI_DELTA_ROOT:…/delta}`, `7`, `true` | the same | the official end-of-day captures and their published results (§9.6) |

### 6.2 VaR

With `v` the scope's vector (length N), `o` the scenario indices ordered worst first (ties by index), α = `confidence`:

```
kth-worst     k = clamp(ceil(N·(1−α) − 1e-9), 1, N)          VaR = −v[o[k−1]]
interpolated  pos = N·(1−α); lo = max(1, floor(pos)); hi = min(N, lo+1); w = clamp(pos − lo, 0, 1)
              VaR = −((1−w)·v[o[lo−1]] + w·v[o[hi−1]])
```

The **VaR scenario** is `o[k−1]` (reported as `k` and by the path's rank). An empty vector has VaR 0.

### 6.3 ES

```
k_ES = clamp(ceil(N·(1−α_ES) − 1e-9), 1, N)        ES = −(1/k_ES)·Σ_{r=1..k_ES} v[o[r−1]]
```

At N = 509, α_ES = 97.5%: the mean of the 13 worst. ES is not interpolated under either rank rule.

### 6.4 Decomposition of a scope into parts

A scope's **parts** are its children (§9.3: child nodes of a node; positions of a book or of an ad-hoc scope; trades of a position) or
the grouping asked (`by=position|trade|desk|book`). For a part with vector `p` and size `s` (a position's `MARKET_VALUE`, a trade's `PV`;
none for nodes and groups), `h = floor(window/2)`:

| Figure | Formula (as `VarCalc` and `VarMath` implement it) |
|---|---|
| tail | `T = o[max(1,k−h)−1 .. min(N,k+h)−1]`: the scope's scenarios ranked k−h to k+h (ranks 4 to 8 at k = 6, window 5) |
| smoothed VaR | `S = −mean_{j∈T} v[j]` |
| scale | `c = VaR / S` when `component-scale-to-var` and \|S\| > 10⁻⁹, else 1 |
| standalone VaR, ES | VaR and ES of `p` alone (its own order) |
| component VaR | `CVaR(p) = −c · mean_{j∈T} p[j]`; Σ over parts that partition the scope = VaR exactly (= S when c = 1) |
| component ES | `CES(p) = −mean_{r=1..k_ES} p[o[r−1]]` (the scope's ES scenarios); Σ = ES exactly |
| pct | `CVaR(p) / VaR` (0 when VaR = 0) |
| incremental VaR | `VaR(v) − VaR(v − p)`: the scope's VaR minus its VaR without the part, recomputed with its own order |
| marginal VaR | `CVaR(p) / abs(s) × 1,000,000` (per million of market value or PV); null without a size or for size 0 |
| diversification | `Σ standalone VaR(parts) − VaR(scope)`; null for fewer than two parts in the headline |

Parts need not partition the scope (a selection): their components then do not add up. A partial position (a scope holding some of a
position's trades) is a part whose vector is the sum of the trades it holds, its quantity theirs, `partial: true`.

**Design differs:** VAR_ENGINE.md section 7 defines marginal VaR per unit of notional or PV by product; the code uses market value for
positions and PV for trades, per million. VAR_ENGINE.md lists `age-weighted` and `filtered` methods and stressed VaR: not built.

### 6.5 History and backtest

**Two modes** (`VarViews.history`):

| Mode | When | Rows | Backtest input |
|---|---|---|---|
| `official` | the scope is exactly one node, the viewer sees every node at `positions` level, and the node has official end-of-day records (§9.6) on more than one day up to the date | the node's official figures, oldest first, at most `history-days` (250): each date's latest result version | `HYPO_PNL` against the previous row's `VAR` |
| `replay` | anything else | the last `min(history-days, N)` scenario days of the scope's **today's** vector | each day's scenario P&L against today's VaR (in-sample, labelled *not a regulatory backtest*) |

**Backtest** (`Backtest.of(pnl[], var[], α)`), both arrays oldest first:

```
for i in 1..n−1 (skipping NaN):  days += 1;  exception_i = pnl[i] < −var[i−1];  x = Σ exception_i      (day 0 is never an exception)
light:   s = min(1, days/250);   green if x ≤ round(4·s);  amber if x ≤ round(9·s);  else red
Kupiec (proportion of failures), p = 1 − α, p̂ = x/days:
         LR_POF = max(0, −2·[ (days−x)·ln(1−p) + x·ln p − ( x·ln p̂ + (days−x)·ln(1−p̂) ) ])     (0·ln 0 taken as 0)
Christoffersen (independence), transitions over i = 2..n−1 of (exception_{i−1}, exception_i):
         n00, n01, n10, n11;  π0 = n01/(n00+n01);  π1 = n11/(n10+n11);  π = (n01+n11)/(n00+n01+n10+n11)
         LR_IND = max(0, −2·[ (n00+n10)·ln(1−π) + (n01+n11)·ln π − (n00·ln(1−π0) + n01·ln π0 + n10·ln(1−π1) + n11·ln π1) ])
p-value: P(χ²₁ > LR) = erfc(sqrt(LR/2))   (Numerical Recipes' erfc, |error| < 1.2e−7)
```

**Short histories (decided 2026-10-25, open question 13: kept, documented).** The light scales its limits with the days it has: with 4
days, `round(4 × 0.016) = 0`, so a single exception is already **red**; below 14 days one exception is red, below 32 one is amber. The
screens show the light with its days, and it is not a regulatory zone before 250 days. **The hypothetical P&L of an official record**
(`HYPO_PNL`) is the node's P&L under the newest scenario day of its date's set (the move of the business day before, applied to the
date's members): a bank delivering a hypothetical P&L table of its own replaces it (planned).

### 6.6 Where it runs, and the Python engine (planned)

**As built:** in the server's JVM (`drishti-compute`, `drishti-server`): additions of stored vectors and a sort of N numbers per answer,
on a bounded pool. No Python on the path.

**Planned (2.8.0):** a **Python VaR engine** and a **reactive connector** beside the Java engine. Java keeps the additive aggregation
(resolving a scope, summing vectors, the node aggregates, the hot store); Python does what does not add: what-if pricing of trades over
the scenarios (through the compute workers and, when its API is stable, QLRisk), heavy analytics (methods beyond equal-weight
historical, stressed sets, ES backtests), and the computed kinds of §9.7. The boundary contract:

| From Java to Python | From Python to Java |
|---|---|
| the scope's canonical text, business date, scenario set id and dates, data version, the scope's vector and up to `max-parts` part vectors as Arrow (`List<float64>`, length N), the settings of §6.1 | measures and decompositions with the same field names as §9 |
| for what-if: the trades to price (ids or terms), the scenario set | one vector of N float64 per trade, joining the intraday layer as a trade vector |

## 7. What Drishti builds

### 7.1 As built: one business date in memory (`VarBook`)

`VarLoader.load(date)` reads the scenario set (checked), streams the vectors through `VarIntake` (each row checked, left out when not
live, given its position by the rules: §3.7, §10), reads the hierarchy nodes and the portfolios, checks the date is one run, then
`VarBook.Builder.build`:

```
 pnl-vector rows (lake order)                   VarBook (immutable, shared by every request)
 ─────────────────────────────                  ─────────────────────────────────────────────────────────────
 TRADE_ID, its columns, PNL[509] ──VarIntake──► VectorStore   rows = trades in hierarchy order, row-major double[] chunks
   checked; POSITION_ID derived                 tradeIds[]    ordinal → TRADE_ID;  tradeOrdinal: TRADE_ID → ordinal (and in upper case)
                     sort by LEGAL_ENTITY,      trades        Columns: 17 text columns (int code + dictionary), 2 number columns
                     DESK, TT_BOOK,             positionOf[]  ordinal → position index
                     POSITION_ID, TRADE_ID      posFrom[], posTo[]   each position = one run [from, to) of ordinals
                     (stable merge sort)        positions     Columns: POSITION_TEXTS (18), POSITION_NUMBERS (6), derived from the runs
                                                bookRange     TT_BOOK → run [from, to)
 risk-hierarchy rows  ─────────────────────►    nodes         id, hierarchy, level, name, parent, children, books, limit,
                                                              members (OrdinalSet: the union of its books' runs)
                                                aggregates    every book's and every node's vector (double[N]), summed at load
 portfolio rows       ─────────────────────►    portfolios    id → {name, type, owner, scope text, limit}  (in VarDate)
```

| Structure | Exact content |
|---|---|
| **Trade order** | sorted by `LEGAL_ENTITY`, `DESK`, `TT_BOOK`, `POSITION_ID` (text order of the values; null as empty) then `TRADE_ID`: each position and each book is a contiguous run of ordinals. The sort and the permutation of the vectors happen once per load (both copies held during the permutation) |
| **Trade columns** (`TRADE_TEXTS`) | `POSITION_ID` (derived), `LEGAL_ENTITY`, `DIVISION`, `DESK`, `TT_BOOK`, `PORTFOLIO`, `PRODUCT_GROUP`, `PRODUCT_TYPE`, `CURRENCY`, `COUNTERPARTY`, `DIRECTION`, `INSTRUMENT_ID` (derived), `SECURITY_ID`, `INSTRUMENT`, `STRATEGY`, `TAGS`, `POSITION_RULE` (int code per row, −1 for null, one dictionary per column); `TRADE_NUMBERS`: `QUANTITY`, `PV` (`double`, NaN for none) |
| **Position columns** | derived from each run (§3.7): `POSITION_TEXTS`: `INSTRUMENT_ID`, `INSTRUMENT`, `DIRECTION`, `LEGAL_ENTITY`, `DIVISION`, `DESK`, `TT_BOOK`, `PORTFOLIO`, `PRODUCT_GROUP`, `PRODUCT_TYPE`, `CURRENCY`, `COUNTERPARTY`, `STRATEGY`, `TAGS` (stored joined by a vertical bar, read as a list), `STATUS`, `SECURITY_ID`, `POSITION_RULE`, `POSITION_TYPE`; `POSITION_NUMBERS`: `QUANTITY`, `MARKET_VALUE`, `NOTIONAL`, `TRADE_COUNT`, `LONG_TRADES`, `SHORT_TRADES` |
| **Nodes** | from `risk-hierarchy`; a node's books are those of its `BOOKS` the date has trades in; children from `PARENT_ID` |
| **Aggregates** | every book's vector (one parallel pass per book run), then every node's bottom-up from the roots (a leaf node sums its books; others their children). Keyed by book name and by node id |
| **Position vectors** | summed on first use from the position's run and cached for the date's life (unbounded: at most one `double[N]` per position) |
| **Failed** | count of rows with a null vector |

**The vector store** (`VectorStore`): `width` = N; rows in `double[]` chunks of `rows-per-chunk` rows (65,536 × 509 doubles = 267 MB a
chunk; the last chunk cut to its rows); row *r* at `chunks[r / rowsPerChunk][(r % rowsPerChunk) × N ..]`. Only rows of exactly N finite
values reach it (the rest are rejected first, §10). Immutable after build; any number of readers.

**Ordinal sets** (`OrdinalSet`): a `java.util.BitSet` over the date's trade ordinals, one bit per trade (2 million trades: 250 KB);
union, difference and intersection are exact; a run of a node is a run of bits. `digest()` = a 64-bit hash of the words in hex, `:`, the
member count (the `membershipHash` of an answer). **Design differs:** VAR_DATA_MODEL.md proposes Roaring bitmaps of *position* ordinals.

**Parallel sums** (`ParallelSums`): a fixed pool of `threads` daemon threads (`drishti-vector-sum-<n>`). A set below `parallel-rows`
(4,096), a single thread, or a one-chunk store with a set below 4 × `parallel-rows` is summed on the caller's thread run by run;
otherwise the ordinal range from the set's first member is cut into `min(2 × threads, size / parallel-rows)` slices, each summed into
its own accumulator, the accumulators added in slice order: the same set gives the same bits every time.

**The planner** (`VarBook.vector(set)`), over the **MGMT** hierarchy:

```
if no book is wholly covered by the set:            one parallel pass over the set's rows (a basket, a rule)
else walk MGMT from the root:
    node not touched                    → skip
    node wholly covered (has aggregate) → + aggregate
    node missing m ≤ max(16, covered/8) → + aggregate − (the missing rows, summed from the gaps between the set's runs)
    book (or childless node)            → + the set's runs in its books
    otherwise                           → recurse into children
then + the set's trades in no MGMT book (none in a consistent batch)
```

A scope that is exactly one node is answered from that node's aggregate without planning. The plan never changes the result (tested
against brute force in `VarBookTest`); whole-number vectors give the same bits.

**Answer cache** (`VarService`): Caffeine, at most **512** answers (resolved set, vector, measures). Key: `businessDate | dataVersion |
viewSignature | canonical scope | membershipHash`. The data version is the Delta plugin's stamp of the date's files (a SHA-256 of the
partition's file paths and sizes), not the Delta table version. Views are cached per date, version and role levels (`VarAccess`).

**Load and memory.** A date's load runs once (concurrent requests share it); a failed load is retried by the next request. The budget
`memory-budget-mb` bounds the rows of **one** date's store (N × 8 bytes a row); a date that would exceed it is refused whole with the
reason (`503 DRS-5204`, §10). Up to `hot-dates` dates are loaded, so total vector memory is up to `hot-dates` × budget, plus a second
copy of one date during its permutation. A date that is not one run (`DRS-5203`) is remembered with its vector table's version and not
read again until that version changes; the date before it is answered meanwhile, with the reason in `provenance.warnings`.

### 7.2 Sizes (as built, N = 509)

| Per 1,000 trades | Bytes | Note |
|---|---|---|
| vectors (float64) | 4,072,000 (3.88 MiB) | 509 × 8 per trade; the budget's measure |
| vectors during load | + 4,072,000 | the unsorted copy while permuting |
| trade columns | 84,000 | 17 × 4-byte codes + 2 × 8-byte numbers per trade, plus the dictionaries |
| trade ids and index | about 150,000 | `String` per id and a `HashMap` entry (JVM-dependent, estimate) |
| position index | 4 per trade + per position about 120 bytes of columns, 8 of run, an id and two map entries | |
| ordinal set | 125 per set | one bit per trade of the date |
| aggregates | 4,072 per book and per node | 88 nodes + 30 books in the demo: 0.48 MB |
| position vector cache | up to 4,072 per position used | |
| a whole date | about **4.4 MB per 1,000 trades** + 4 KB per node | demo: 9,572 trades in 7,984 positions, 39 MB of vectors |

Measured (VAR_ENGINE.md section 13.2, `VarScaleBenchmark`, one workstation with 24 hardware threads, JDK 25; not re-run for this
document): 1 M trades = 4.07 GB of vectors; loading a date 3.4 s for 1 M trades after reading, 0.6 s for the demo from the lake.

### 7.3 Planned: tiered memory and the hot file (proposed on-disk contract)

Approved by the product owner (2026-10-25); the format below is **proposed** and will be fixed by the 2.8.0 build.

| Tier | Holds | Precision | When read |
|---|---|---|---|
| Lake (Delta) | every trade vector, every date | **float64** (unchanged) | at first load of a date; a trade's vector on a drill to trades or a scope naming trades |
| Hot file, one per date | **position** vectors sorted by hierarchy, node aggregates, columns | matrix **float32**, aggregates float64 | memory-mapped; written after the first load; **history dates opened lazily** (mapped when first asked, never at start) |
| Memory | the mapped pages in use, answers, node aggregates | sums always in **float64** accumulators | on demand |
| Cluster | each node maps the shards it owns: contiguous ranges aligned to legal entities and desks, two replicas | | the coordinator adds the partial sums (N doubles each) |

**Trade vectors** are read only when a scope needs a trade on its own (a drill below a position, `TRD:`, a basket naming trades, a
position held in part); everything at position level and above is served from the hot file.

**The hot file** (proposed): `<data folder>/var/hot/<YYYY-MM-DD>/<SCENARIO_SET_ID>.vhot`, little-endian, written once, never changed
(a restated date writes a new file named with its data version and the old one is removed when no request uses it).

```
offset  bytes  field
0       8      magic "DRSHVAR1"
8       4      format version (1)
12      4      flags: bit 0 float32 matrix, bit 1 trade matrix present
16      4      business date as yyyymmdd (int32)
20      4      N, the vector width (509)
24      4      P, positions (rows of the matrix)
28      4      T, trades
32      4      M, nodes (with aggregates)
36      4      S, sections
40      32     SCENARIO_SET_ID (UTF-8, zero padded)
72      64     data version of the date's pnl-vector files (UTF-8, zero padded)
136     32     BATCH_ID (UTF-8, zero padded)
168     8      written at (epoch milliseconds)
176     8      failed rows (no vector)
184     72     reserved (zero)
256     S×32   section table: {id int32, reserved int32, offset int64, length int64, crc32c int32, pad int32}
```

| Section | Content |
|---|---|
| `STRS` | the dictionaries of every text column: per column a count, then (length int32, UTF-8 bytes) per value |
| `PCOL` | position columns (`POSITION_TEXTS` as int32 codes, `POSITION_NUMBERS` as float64), P rows each, in matrix order |
| `PIDS` | position ids (length-prefixed UTF-8), P entries |
| `PRUN` | per position its trades: int32 `tradeFrom`, `tradeTo` into the trade order |
| `TCOL`, `TIDS` | trade columns (`TRADE_TEXTS` codes, `TRADE_NUMBERS`) and ids, T rows, sorted as today (§7.1) |
| `NODE` | per node: id, hierarchy, level, parent, name (codes), limit float64, its member runs over position rows (count, then from/to pairs) |
| `PMAT` | **float32 P × N, row-major**, rows in hierarchy order (legal entity, desk, book, position), 64-byte aligned |
| `AGGS` | **float64 M × N** node aggregates, summed in float64 from the lake's float64 trade vectors (exact, independent of the float32 copy) |
| `TMAT` | optional float32 T × N trade matrix (open question 8) |

**Precision.** float32 keeps about 7 significant digits per value (a P&L of 50 million carries an error of about ±3 USD); every sum is
float64 and node figures come from the float64 aggregates, so a node's VaR is the same as from the lake; a scope summed from position
rows may differ from the lake by rounding (VAR_DATA_MODEL.md section 10 estimates ±4,000 USD on a bank-level P&L of 10⁹ over 2 million
positions). Answers state the tier used (`provenance.tier`, planned).

## 8. Scopes

A scope is text, parsed by `ScopeExpr.parse`, resolved on one date by `ScopeResolver` to a set of trade ordinals. The same text is the
`scope` parameter of every endpoint, the id of the planned computed kinds, and a cache key once canonical.

### 8.1 Terms

| Term | Argument | Resolves to |
|---|---|---|
| `BANK` | none (`BANK:x` is accepted and `x` ignored) | node `MGMT~BANK` |
| `LE:<id>` | a legal entity (`LE-NY` or `NY`) | the trades whose booking `LEGAL_ENTITY` is it (§8.5); the node `LEGAL~<id>` (else `LEGAL~LE-<id>`) gives its children, limit and history |
| `DIV:<id>` | a division | `MGMT~<id>`, else `MGMT~DIV-<id>` |
| `DESK:<id>` | a desk | `MGMT~<id>`, `MGMT~DESK-<id>`, `LEGAL~<id>`, `LEGAL~DESK-<id>` (first found) |
| `BOOK:<id>` | a book | `MGMT~<id>`, else `MGMT~BOOK-<id>` |
| `NODE:<node id>` | a full node id | that node (as written, then upper case) |
| `POS:<id>[,<id>…]` | position ids | every trade of each position |
| `TRD:<id>[,<id>…]` | trade ids | those trades (an id as written, else the one id equal to it ignoring case) |
| `CPTY:<id>[,<id>…]` | counterparty ids | every trade whose `COUNTERPARTY` is one of them (compared ignoring case) |
| `PORT:<id>` | a stored portfolio | its definition's scope (§3.5), resolved recursively |
| `BASKET:<owner>/<name>` | a saved basket the caller may read | its scope, resolved recursively |
| `CAPTURE:<id>` | a capture the caller may read | `TRD:` of its members (resolved on the request's date) |
| `WHERE:<rule>` | a position rule (§8.4) | every trade of each matching position |

A node term's members are the trades of the node's books. `DESK:RATES`, `DESK:DESK-RATES` and `NODE:MGMT~DESK-RATES` are the same node.

### 8.2 Grammar (as the parser accepts it)

```
scope    := union
union    := and ( SP ('+' | '-') SP and )*          left to right; '+' and '-' have the same precedence
and      := atom ( SP '&' SP atom )*                '&' binds before '+' and '-'
atom     := '(' union ')' | ref
ref      := WORD | WORD ':' arg | 'WHERE' ':' rule
WORD     := letters only, case-insensitive: BANK LE DIV DESK BOOK NODE POS TRD CPTY PORT BASKET CAPTURE WHERE
arg      := characters up to whitespace, '(' or ')'; for POS, TRD and CPTY, whitespace next to a comma is allowed ("a, b")
rule     := characters up to an unmatched ')', or up to whitespace + one of '+', '-', '&' + whitespace, outside quotes and parentheses
SP       := whitespace (or, before an operator, a closing ')'; after it, an opening '(')
```

| Rule | Detail |
|---|---|
| Operators | `+` union, `-` difference, `&` intersection; an operator must stand between spaces (or after `)` / before `(`): `MX-20000001` is an id, `A -B` is an error |
| Precedence | `A + B - C` = (A ∪ B) − C; `A - B + C` = (A − B) ∪ C; `A + B & C` = A ∪ (B ∩ C); parentheses group |
| Case | words are case-insensitive; arguments are **upper-cased**, except `BASKET` and `CAPTURE` arguments and the ids of `POS`, `TRD` and `CPTY` (kept as written, matched exactly, else ignoring case: decided 2026-10-25) |
| Lists | only `POS`, `TRD` and `CPTY` split their argument on commas; for other words a comma is part of the id |
| Quoting | only inside `WHERE` rules: text literals in single quotes, `''` for a quote |
| Canonical text | words upper case; `POS`/`TRD`/`CPTY` ids sorted and unique (as written); the terms of a union sorted; `A - B` written `A - (B)` when B is a combination; `&` operands in parentheses unless a term or another `&` |
| Depth | `PORT`, `BASKET` and `CAPTURE` may refer to each other at most 8 deep; deeper: *the scope refers to itself* (`400`) |
| Errors | empty scope, an unknown word, a word without `:` and argument, an empty argument, an unmatched `(`, trailing text, a rule that does not parse: `400 DRS-5001` with the reason |

### 8.3 Resolution rules

| Rule | Behaviour |
|---|---|
| Unknown ids | an id, node, portfolio, basket or capture the date or the caller does not have resolves to nothing and is listed in `provenance.unknown`; the answer is still `200` (an empty scope has VaR 0) |
| Overlap | set algebra on trade ordinals: a trade in two terms of a union counts once |
| Rules and positions | `WHERE:` and `POS:` take whole positions; `TRD:` takes single trades (a position held in part is a *partial* part) |
| Entitlements | the resolved set is intersected with the trades the viewer may see; the rest are counted (`hidden`). A scope that names `POS`, `TRD`, `CPTY`, `WHERE`, `BASKET`, `CAPTURE` or a `PORT:` of a BASKET-type portfolio is refused (`403 DRS-5002`) unless every resolved trade is at `positions` level for the viewer; `CPTY:` is refused to a viewer who sees `COUNTERPARTY` masked |
| Warnings | data-quality findings about what was resolved, in `provenance.warnings`: a legal entity's trades outside its `LEGAL` node, a `LEGAL` node holding another entity's trades (§8.5) |
| Kind of a scope | `node` (exactly one node term, `LE:` included when its `LEGAL` node exists), `position`, `trade` (exactly one id), `portfolio`, `basket`, `capture`, `rule`, `counterparty`, `legal-entity` (one term of that word), else `adhoc` |
| Date | resolved on the request's date: a `CAPTURE:` or a basket of trades holds ids; trades the date does not have are `unknown` |

**Aggregate-only access** (§11): a scope that is a node or a HIERARCHY or RULE portfolio is allowed and computed over the visible
trades; parts below the node's level are not listed. `PORT:` of a BASKET-type portfolio is treated like `BASKET:` (as built,
2026-10-25): an aggregate-only viewer cannot probe a stored basket of single trades.

### 8.4 The position rule language (`WHERE:` and RULE portfolios)

The same grammar in `PositionRule` (Java) and `tools/packgen/var/rules.py` (the generator), so a rule has the same members in both.

```
rule  := or
or    := and ( 'or' and )*                 and   := not ( 'and' not )*
not   := 'not' not | '(' or ')' | cmp
cmp   := FIELD op LIT | FIELD 'in' '(' LIT (',' LIT)* ')' | LIT 'in' FIELD
op    := '==' | '!=' | '<' | '<=' | '>' | '>='
LIT   := digits ['.' digits]  (no sign: negative numbers are not supported) | 'text' ('' for a quote)
FIELD := [A-Za-z_][A-Za-z0-9_.]*           keywords and, or, not, in are case-insensitive
```

| Semantics | Detail |
|---|---|
| Fields | the position columns of §7.1 (`POSITION_TEXTS`, `POSITION_NUMBERS`); any other field is missing |
| Missing field | compares unequal to everything: `!=` is true, every other comparison false; `in` false |
| Numbers | a number literal compares numerically (a text value that parses as a number is converted) |
| Text | `==`, `<` … compare text by code point (Java `compareTo`) |
| `'x' in FIELD` | true when the list field holds `x` (`TAGS`) |
| Examples | `PRODUCT_GROUP == 'Rates' and CURRENCY in ('USD', 'CAD')`; `'hedge' in TAGS or not DIRECTION == 'LONG'`; `MARKET_VALUE > 1000000` |

### 8.5 Scope words by a trade's attribute

| Term | Meaning (as built) | Reads |
|---|---|---|
| `LE:<id>` | **the bank's legal entity (decided 2026-10-25, open question 14):** the trades whose booking `LEGAL_ENTITY` is the entity (`LE:NY` is `LE-NY` too). The `LEGAL` hierarchy rolls up by books; when its node `LEGAL~<id>` misses some of the entity's trades or holds another entity's, the answer is still the trades' entity and the difference is a data-quality warning in `provenance.warnings`. The node, when it exists, gives the scope its children, limit and official history | `LEGAL_ENTITY` of the trade vectors |
| `CPTY:<id>[,<id>…]` | the trades with that counterparty, across books and positions (compared ignoring case); refused to an aggregate-only viewer and to a viewer who sees `COUNTERPARTY` masked | `COUNTERPARTY` of the trade vectors |
| `GROUP:<id>` | **PARKED** (product owner, 2026-10-25): the trades with any member of a connected group (GLEIF parents) | `counterparty`, `lei-entity` |
| `NSET:<id>` | **PARKED**: the trades of a netting set | `netting-set` (`TRADE_IDS`) |

## 9. What Drishti answers

### 9.1 The API (as built)

Base `/api/v1/var`, JSON, as the caller (security on: the token's user; [API_GUIDE.md](../guides/API_GUIDE.md#var-engine-apiv1var-280)).
Every scope endpoint takes `scope` and the platform's `asOf=YYYY-MM-DD` (default and later than the newest date with vectors: the
newest; a date that is not one run answers the date before it, with a warning). Errors are RFC 7807 problems with `code` (§10).

| Method and path | Parameters | Answer |
|---|---|---|
| `GET /scope` | `scope`, `asOf` | the headline (§9.2) |
| `GET /scopes/{scope}` | `{scope}` in the path (no slash), `asOf` | the same |
| `GET /scope/path` | `scope`, `page` (0), `size` (0 → `path-page`; at most 509), `asOf` | the P&L path (§9.3) |
| `GET /scope/history` | `scope`, `asOf` | history and backtest (§9.4) |
| `GET /scope/contributions` | `scope`, `by` (`child` default, `node`, `position`, `trade`, `desk`, `book`), `scenario` (a scenario date), `asOf` | the parts (§9.3) |
| `GET /tree` | `hierarchy` (default `MGMT`), `asOf` | the nodes the caller sees with their figures, and the stored portfolios |
| `GET /status` | | settings (with the position rules and the official captures) and loaded dates (with any refused) |
| `POST /reload` | | administrators only: `{"reloaded": true}` (audited `var-reload`) |
| `GET /official` | `asOf` | a date's official end-of-day record (§9.6), for a viewer who sees every node (`403` otherwise; `404` when the date has none) |
| `POST /official` | `asOf` | administrators only: captures the date officially now: `{businessDate, status: captured, restated, unchanged or skipped, reason, resultVersion, nodes, published, publishError}` |
| `GET /baskets`, `POST /baskets` | body `{name, title, description, scope, sharedWith}` | baskets (§9.5) |
| `GET /baskets/{owner}/{name}`, `DELETE /baskets/{owner}/{name}` | | one basket; delete one's own (`204`) |
| `POST /captures` | body `{scope, note}`, `asOf` | `201` `{id, capturedAt, var, es, memberCount}` (§9.6) |
| `GET /captures` | `scope` (optional, compared in canonical form) | the captures the caller may read, newest first |
| `GET /captures/{id}`, `GET /captures/{id}/csv` | `part` (`components`, else the vector) | one capture; CSV |
| `GET /compare` | `a` (capture id), `b` (capture id, or `now`/empty) | the comparison (§9.6) |

**`by` and truncation (as built, decided 2026-10-25, open question 9).** `by=node` lists a node's child nodes; on a scope that is not a
node, or a node without children, it answers `400 DRS-5001` with the reason. When a scope has more positions or trades than
`max-parts`, the largest `max-parts` by gross size are computed (a position's absolute market value when the scope holds it whole, else
its trades' absolute PV, else their absolute quantities), kept in their order; the answer says `truncated: true` and `total`.

### 9.2 The headline (`GET /scope`)

| Field | Type | Meaning |
|---|---|---|
| `scope` | string | as asked |
| `canonical` | string | canonical text (§8.2) |
| `kind` | string | `node`, `position`, `trade`, `portfolio`, `basket`, `capture`, `rule` or `adhoc` |
| `label` | string | node name; `<POSITION_ID> · <INSTRUMENT>`; `<TRADE_ID> · <PRODUCT_TYPE>`; portfolio or basket name; else the canonical text |
| `node` | string or null | the node id when the scope is exactly one node |
| `businessDate` | string | the date answered (the date asked, or the newest when later) |
| `var`, `es` | integer | VaR and ES, USD, positive for a loss |
| `k`, `esCount` | integer | the VaR rank (6) and the ES count (13) |
| `scenarios` | integer | N |
| `confidence`, `esConfidence` | number | 0.99, 0.975 |
| `children`, `childKind` | integer, string | the number of default parts and their kind (`division`, `desk`, `book`, `position`, `trade`; empty for an aggregate-only viewer below a node) |
| `sumStandalone` | integer or null | Σ standalone VaR of the parts |
| `diversification` | integer or null | `sumStandalone − var` (two parts or more) |
| `limit`, `limitUse` | integer, number, or null | the node's `LIMIT_VAR` or the portfolio's; `var / limit` |
| `backtest` | object | `{days, exceptions, light, kupiecLr, kupiecPValue, christoffersenLr, christoffersenPValue}` (§6.5) |
| `ancestors` | list | `[{label, scope}]` from the MGMT root to the node (a position or trade: its book's path, then its position) |
| `provenance` | object | below |

**Provenance** (every headline, and inside each capture):

| Field | Type | Meaning |
|---|---|---|
| `scope` | string | canonical text |
| `resolved` | list of strings | how each term resolved (`node MGMT~DESK-RATES`, `rule matched 16 positions`, `portfolio PF-X = NODE:…`, `basket a/b`, `the members of capture …`) |
| `trades`, `positions` | integer | members counted (visible; positions as derived, §3.7) |
| `failed` | integer | the date's rows with no vector (whole date, not the scope) |
| `hidden` | integer | trades of the scope outside the viewer's entitlement |
| `unknown` | list of strings | ids and terms the date or caller does not have |
| `vectors` | string | `pnl-vector · batch <BATCH_ID> of <date> · data version <stamp>` |
| `scenarioSet` | string | `HS-509 · <first scenario date> .. <last>` |
| `method` | string | `historical, equal weights, 1-day, k = 6th worst of 509` (or *interpolated between the order statistics*) |
| `es` | string | `97.5%, mean of worst 13` |
| `components` | string | `Euler on the 5 scenarios around the VaR rank, scaled to add up to the VaR` |
| `engine` | string | `VaR engine in the server · drishti-compute <version> · <threads> threads` |
| `view` | string | `every node at positions level`, or `restricted by node (<hidden> trades outside your entitlement)` |
| `membershipHash` | string | the set's digest (§7.1) |
| `computedAt` | string | ISO-8601 instant of this answer |
| `computeMs`, `cache`, `loadedMs` | integer, `hit`/`miss`, integer | time of this answer, cache use, the date's load time |

| `inputs` | object | every input table's data version on the date (`pnl-vector`, `risk-hierarchy`, `scenario-set`, `portfolio`), `scenarioSet`, `batchIds`, `positionRules` (the rules' digest): README.md section 11's *input tables' versions and batch ids* (as built) |
| `rejected`, `rejections` | integer, list | rows of the date rejected at load and the first 20 reasons (`DRS-5201 <trade>: <why>`) |
| `excluded` | integer | rows of the date left out as not live (status, maturity) |
| `positionRules` | string | the position rules the date was built with (§3.7) |
| `warnings` | list of strings | the date answered in place of one that could not load (`DRS-5203`), and the scope's data-quality findings (§8.3) |

### 9.3 Path and contributions

**`GET /scope/path`:** `canonical`, `var`, `es`, `k`, `esCount`; `histogram` `{edges: integer[bins+1], counts: integer[bins]}` (equal
bins from the smallest to the largest value, bin *i* = [edges[i], edges[i+1]), the last closed); `worst`: the 25 worst
`[{rank, index, date, pnl}]`; `series`: integer[N] in scenario order; `dates`: [first, last]; `scenarioDates`: string[N]; `page`,
`pageSize`, `total` (N); `rows`: a page of the ranked scenarios `[{rank, index, date, pnl, from, fromScope, fromPnl}]` (`from*`: the
default part that lost most on that scenario); `computeMs`.

**`GET /scope/contributions`:** `canonical`; `by` (the parts' kind); `var`, `es`; `sumStandalone`, `sumComponent`, `diversification`;
`truncated` (boolean), `total` (the parts there were); with `scenario`: `scenario`, `scenarioPnl`, `scenarioRank`; `rows`; `computeMs`. Rows are sorted by
`componentVar` descending, or by `scenarioPnl` ascending when a scenario is given.

| Row field | Type | Meaning |
|---|---|---|
| `key`, `scope`, `label`, `kind`, `level` | string | the part, the scope that opens it, its name, its kind |
| `standaloneVar`, `standaloneEs`, `componentVar`, `componentEs`, `incrementalVar` | integer | §6.4 |
| `pct` | number | component VaR ÷ the scope's VaR |
| `marginalPer1m` | integer or null | §6.4 |
| `scenarioPnl` | integer | the part's P&L on `scenario` (when given) |
| position parts | | `instrument`, `direction`, `quantity`, `marketValue`, `currency`, `book`, `productType`, `securityId`, `rule`, `trades`, `partial` |
| trade parts | | `direction`, `quantity`, `productType`, `counterparty`, `position`, `book`, `pv` |
| masks | | a row attribute the viewer sees masked (`counterparty` when `COUNTERPARTY` is in `drishti.security.redact`) reads `•••` (as built, open question 11) |

Default parts (`by=child`): a node with children → its child nodes the viewer sees; a childless node (a book) → its positions; a position
→ its trades; a trade → none; any other scope → its positions. Positions and trades are listed only to a viewer at `positions` level.

### 9.4 History and the tree

**`GET /scope/history`:** `mode` (`official`, `replay`), `note`, `backtest` `{days, exceptions, light, kupiecLr, kupiecPValue,
christoffersenLr, christoffersenPValue}`, `rows` `[{date, var, es, pnl, exception}]` oldest first (§6.5).

**`GET /tree`:** `hierarchy`, `hierarchies` (the roots' hierarchy names), `businessDate`, `nodes` `[{id, scope, name, level, parent,
depth, var, es, limit, limitUse, trades, hidden, expandable}]` in depth-first order (a node is left out when the caller sees none of its
trades), `portfolios` `[{id, scope, name, type, owner}]`.

**`GET /status`:** `enabled`, `confidence`, `esConfidence`, `rankRule`, `componentWindow`, `threads`, `memoryBudgetMb`, `hotDates`,
`kinds` (string[5]), `positionRules`, `official` `{enabled, publish, retentionYears, dates}`, `loaded` `[{businessDate, trades, positions,
nodes, vectorBytes, loadMs, version, rejected, excluded, batchIds}]` and `{businessDate, refused}` for a date that could not load.

### 9.5 Baskets

Stored in the **content store** ([CONTENT_STORE.md](../architecture/CONTENT_STORE.md)) at `var/baskets/<owner>/<name>.json`, so they
follow their owner across the servers of a cluster and every version is kept. Read as `BASKET:<owner>/<name>`.

| Field | Type | Meaning |
|---|---|---|
| `id` | string | `<owner>/<name>` |
| `name` | string | the title given (`title`), else the name |
| `description` | string | |
| `type` | string | `RULE` when the scope starts with `WHERE:` and has no ` + `, else `BASKET` |
| `scope` | string | the scope text as saved (must parse) |
| `owner` | string | the user who saved it |
| `sharedWith` | string list | user names, or role names (optionally `role:<name>`): read-only |
| `createdAt`, `updatedAt` | string | ISO-8601 instants |
| `version` | integer | 1, then +1 on every save under the same name |

**Name:** slugged to lower case (`[^a-z0-9._-]` → `-`), 1 to 64 characters `[a-z0-9][a-z0-9._-]{0,63}`; `<owner>` is the content
store's form of the user name. **Who may read:** the owner, a named user, or a holder of a named role; each reader sees it through their
own entitlements. **Delete:** the owner only (`204`; anything else `404`). A **dynamic** basket (nodes, rules) means what matches on the
date viewed; a **static** one (ids) holds those ids, which a later date may not have (`unknown`).

### 9.6 Captures

An immutable record of an answer, in the content store at `var/captures/<owner>/<id>.json`. **Id:** `CAP-<yyyymmdd of the business
date>-<n>`, `n` from the content store's sequence `var/captures-seq`. Written once (`create`; an existing id is refused); there is no
update, share or delete endpoint (`sharedWith` is always empty today).

| Field | Type | Meaning |
|---|---|---|
| `id` | string | `CAP-20260930-7` |
| `scope`, `canonical`, `label`, `kind` | string | as in the headline |
| `businessDate`, `capturedAt` | string | the date answered; the instant written |
| `owner`, `sharedWith`, `note` | string, list, string | the capturer; `[]`; the note (at most 1,000 characters) |
| `var`, `es`, `k`, `esCount`, `confidence`, `esConfidence`, `limit`, `sumStandalone`, `diversification` | as the headline | |
| `components`, `componentsBy` | list, string | the contribution rows (`by=child`) and their kind |
| `worst` | list | the 25 worst scenarios `{rank, index, date, pnl}` |
| `vector` | integer[N] | the scope's vector, **rounded to whole units** |
| `scenarioSet`, `scenarioDates` | string, string[N] | |
| `members`, `memberCount` | string list, integer | every member trade id (visible to the capturer) |
| `dataVersion` | string | the vector table's stamp for the date |
| `provenance` | object | §9.2 |

**List** (`GET /captures`): `[{id, scope, canonical, label, businessDate, capturedAt, owner, note, var, es}]`, newest first.
**Compare** (`GET /compare?a=&b=`): `a`, `b` (each `{id, scope, label, businessDate, capturedAt, owner, note, var, es, memberCount,
dataVersion}` where present; `b=now` recomputes the capture's scope on the newest date), `varChange`, `esChange` (b − a),
`membersAdded`, `membersRemoved` (sorted, at most 200 each), `components` `[{key, label, a, b, change}]` by \|change\| descending.

**CSV** (`GET /captures/{id}/csv`, `text/csv`, attachment `<id>[-<part>].csv`): first the lines `# <field>: <value>` for `id`,
`scope`, `label`, `businessDate`, `capturedAt`, `owner`, `note`, `var`, `es`, `dataVersion`, `scenarioSet`; then

| `part` | Header | Rows |
|---|---|---|
| (none) | `scenario_date,pnl` | N rows in scenario order |
| `components` | `key,label,standalone_var,component_var,component_es,pct_of_var,incremental_var` | one per component row (text with a comma or quote is quoted) |

**Official end-of-day captures (as built).** When a date's inputs are complete (the date loads as one run, with trades and hierarchy
nodes), the server captures **every node of every hierarchy** (MGMT and LEGAL) as the **official** record, as the server itself (every
node at positions level): after the data-load signal of an input kind (§3.9), at start for the newest date without a record
(`drishti.var.official.on-start`), and by an administrator (`POST /official`). One capture runs at a time, in the background
(`VarOfficial`); a date captured from the same inputs (the same versions and batch ids) is not captured again (`unchanged`); a restated
date is captured anew with the next **result version**, the earlier captures kept. Kept 7 years, like the vectors (decided, open
question 10): the content store keeps every version, and nothing deletes them.

| Record | Key in the content store | Content |
|---|---|---|
| A node's official capture | `var/captures/official/<CAP-id>.json` | the capture document of §9.6 with `official: true`, `owner: drishti`, `node`, `resultVersion`, `hypoPnl`, the components (the 50 largest; `componentCount` says how many there were), the worst 25, the vector (whole units), `membership` (the date's membership record), `memberCount`, `membershipHash`, `inputs`, `provenance`. No member list of its own: `CAPTURE:<id>` resolves the node's trades from the membership record |
| The date's record | `var/official/<YYYY-MM-DD>.json` | `businessDate`, `resultVersion`, `computedAt`, `contractVersion`, `inputs` (versions, batch ids, rules digest), `positionRules`, `membership`, `published` or `publishError`, and `nodes`: per node `captureId`, `hierarchy`, `level`, `name`, `parent`, `var`, `es`, `hypoPnl`, `limit`, `limitUse`, `sumStandalone`, `diversification`, `trades`, `positions`, `exceptions250`, `light`. A restatement writes a new version of it (the store keeps the old) |
| The date's membership | `var/official/<YYYY-MM-DD>-membership-v<n>.json` | every derived position with its trades, by book; each node's books; the position rules' text and digest; trade and position counts (§3.7) |

The node history (§6.5, mode `official`) reads the dates' records: each date's latest result version. `GET /captures?scope=<a node>`
lists the node's official captures (a date's latest result version each) beside people's, for a viewer who sees every node. The backtest
of a record counts exceptions over the official history up to the day before plus the day (`exceptions250`, `light`). Captures remain the
only stored copy of a computed figure ([README.md section 11](README.md#11-what-drishti-answers-computed-kinds-the-api-captures)).

**Published official results (as built; decided 2026-10-25).** Each official capture run is also appended to
`${DRISHTI_DELTA_ROOT}/risk-results/var-official/` (`drishti.var.official.root`) for other systems ([README.md section
11](README.md#11-what-drishti-answers-computed-kinds-the-api-captures)), by Drishti's own lake writer, one Delta commit per run: one
row per node of `MGMT` and `LEGAL` per business date and result version. The table is created **append-only** (`delta.appendOnly`) with
7 years of log and removed files (`delta.logRetentionDuration`, `delta.deletedFileRetentionDuration`: `interval 2557 days`), so nothing
is ever overwritten: a restated date adds rows with the next `RESULT_VERSION`, and the latest version of a date is the rows with the
highest. No engine reads it (a guard test, `tools/test_contracts.py`, fails the build when a connector points at `risk-results`). A
publish that fails (an `s3://` root, a disk) is recorded in the date's record (`publishError`) and logged; the capture stands.

| Column | Type | Meaning |
|---|---|---|
| `business_date` | DATE (partition) | the business date (`BUSINESS_DATE` of README.md section 11: Delta's column names are case-insensitive, so the partition column is it) |
| `id`, `doc` | STRING | `<NODE_ID>@<date>#v<RESULT_VERSION>`; the row as JSON |
| `HIERARCHY`, `NODE_ID`, `NODE_NAME`, `PARENT_ID`, `LEVEL` | STRING | the node |
| `CAPTURE_ID` | STRING | the official capture the row was written from |
| `RESULT_VERSION` | BIGINT | 1, then 2, 3 … on a restatement |
| `INPUT_VERSIONS` | STRING | the inputs' versions, batch ids and position rules' digest, as JSON text |
| `COMPUTED_AT`, `CONTRACT_VERSION` | STRING | UTC instant; this contract's version (`1.3`) |
| `VAR` | DOUBLE | VaR at `CONFIDENCE`, USD, positive |
| `ES` | DOUBLE | expected shortfall at `ES_CONFIDENCE`, USD, positive |
| `CONFIDENCE`, `ES_CONFIDENCE` | DOUBLE | `0.99`, `0.975` (`drishti.var.*`) |
| `SCENARIO_SET_ID` | STRING | `HS-509` |
| `STANDALONE_SUM`, `DIVERSIFICATION` | DOUBLE | the sum of the children's VaR (positions for a book), and that sum minus `VAR` |
| `HYPO_PNL` | DOUBLE | the node's hypothetical P&L for the date, USD (the backtest's input, §6.5) |
| `LIMIT`, `LIMIT_USE` | DOUBLE | the node's VaR limit from the hierarchy, and `VAR / LIMIT` |
| `TRADE_COUNT`, `POSITION_COUNT` | BIGINT | the members counted |
| `EXCEPTIONS_250`, `TRAFFIC_LIGHT` | BIGINT, STRING | backtest exceptions over the official history (up to 250 days), and the Basel zone |

No vectors and no trade or position rows are published; the capture itself keeps the node's vector for audit.

### 9.7 The computed kinds (planned)

Served by a reactive connector (`plugin: reactive`, [REACTIVE_CONNECTOR.md](../connectors/REACTIVE_CONNECTOR.md)) as ordinary kinds, so
VaR opens by command, link (`/v/<kind>/<scope>`), API, CLI, search and AI tools with the same entitlements. The kind names follow
VAR_ENGINE.md section 5.3 (`var-result`, `var-contribution`, `var-path`, `var-history`; planned); the retired kind name `var` is not
reused (README.md section 14's guard test). **Id:** the canonical scope text. Proposed record of `var-result`:

| Field | Type | Meaning |
|---|---|---|
| `SCOPE`, `CANONICAL`, `SCOPE_KIND`, `LABEL`, `NODE` | text | as the headline's `scope`, `canonical`, `kind`, `label`, `node` |
| `BUSINESS_DATE` | date | |
| `VAR`, `ES` | amount (USD) | at `CONFIDENCE`, `ES_CONFIDENCE` |
| `CONFIDENCE`, `ES_CONFIDENCE`, `K`, `ES_COUNT`, `SCENARIOS` | number | |
| `LIMIT`, `LIMIT_USE`, `SUM_STANDALONE`, `DIVERSIFICATION` | amount, ratio | |
| `BACKTEST` | record | `DAYS`, `EXCEPTIONS`, `LIGHT`, `KUPIEC_LR`, `KUPIEC_P`, `CHRISTOFFERSEN_LR`, `CHRISTOFFERSEN_P` |
| `CHILDREN` | record list | per default part: `KEY`, `SCOPE`, `LABEL`, `KIND`, `STANDALONE_VAR`, `COMPONENT_VAR`, `COMPONENT_ES`, `INCREMENTAL_VAR`, `PCT` |
| `WORST` | record list | 25 × `RANK`, `INDEX`, `DATE`, `PNL` |
| `PNL` | vector | the scope's vector (a vector field, as in the stored kinds) |
| `PROVENANCE` | record | §9.2 in UPPER_SNAKE |

### 9.8 The pages' figures (as built)

No stored kind carries a VaR figure (§14.3). The position, portfolio and core `desk` pages show the engine's figures, computed as the
page is opened, as the reader (their entitlements by node, their field masks): the server's view pipeline adds them under `VAR_ENGINE`
(`VarFigures`, an engine `ViewAugmenter`) to the document the page draws, and to an entity a panel sources by id (the position
opened beside a portfolio's table); the stored document (`/api/v1/entities/<kind>/<id>/raw`)
never has them. When the engine cannot answer (off, no vectors, the reader may not see VaR, no signed-in viewer) `VAR_ENGINE` says why:
`{STATUS: unavailable, NOTE}`, and the page shows the rest.

| Page | `VAR_ENGINE` |
|---|---|
| position (`POS:<id>`) | `STATUS`, `SCOPE`, `BUSINESS_DATE`, `VAR`, `ES`, `CONFIDENCE`, `ES_CONFIDENCE`, `NOTE`; `TRADES` (each trade's `TRADE_ID`, `QUANTITY`, `DIRECTION`, own `VAR`, `COMPONENT_VAR` in the position); `BOOK_VAR`, `COMPONENT_VAR` and `PCT_OF_BOOK_VAR` (the position in its book); `PNL` (its 509 P&Ls) |
| portfolio (`PORT:<id>`) | the same headline with `LIMIT`, `LIMIT_USE`; `POSITIONS` (each position's instrument, direction, quantity, market value, currency, book, product, own `VAR`, `COMPONENT_VAR`, `PCT_OF_VAR`, `PARTIAL`), `POSITION_COUNT`, `LONG_COUNT`, `SHORT_COUNT`, `GROSS_MV`, `NET_MV`, `TOP_CONTRIBUTORS` (10), `BY_PRODUCT`, `BY_CURRENCY`, `BY_DIRECTION` (component VaR and net market value), `PNL` |
| desk (`DESK:<id>`) | the headline: `VAR`, `ES`, `LIMIT`, `LIMIT_USE` |

## 10. Validation, errors and data quality

### 10.1 At `lake build` (as built)

`tools/lake/stage.py` rejects a document (counted per reason, the first 50 kept with file and line) with no key, no business date, a
date that is not a date, a document that is not an object, or a vector field that is not a list of numbers (`the vector field 'PNL' is
not a list of numbers`). A `null` element inside a vector is accepted and stays null.

### 10.2 Errors the engine answers (as built)

| HTTP | Code | When | Message (abridged) |
|---|---|---|---|
| 400 | `DRS-5001` | no `scope` | *give a scope: scope=BANK, DESK:RATES, POS:<id>, TRD:<id>, PORT:<id> …* |
| 400 | `DRS-5001` | the scope does not parse (§8.2) | the parser's reason and position |
| 400 | `DRS-5001` | `by` not one of the accepted values; `by=node` on a scope without child nodes; `scenario` not a scenario date | *by is child, node, position, trade, desk or book*; *by=node lists a node's child nodes; … is not a node*; *<date> is not a scenario date of HS-509* |
| 400 | `DRS-5001` | a basket name or an empty basket scope; a capture id that exists | *a basket's name is 1-64 letters …*; *a basket has a scope …* |
| 403 | `DRS-5002` | the engine is off | *the VaR engine is switched off on this server (drishti.var.enabled)* |
| 403 | `DRS-5002` | the caller may not open `pnl-vector` | *your role may not see VaR (the kind pnl-vector)* |
| 403 | `DRS-5002` | a `POS`/`TRD`/`CPTY`/`WHERE`/`BASKET`/`CAPTURE` scope, or `PORT:` of a BASKET portfolio, over trades seen only in aggregate; `by=position` or `by=trade` for such a viewer | *… your role sees some of these only in aggregate* |
| 403 | `DRS-5002` | `CPTY:` for a viewer who sees `COUNTERPARTY` masked | *the counterparty is masked for your role …* |
| 403 | `DRS-5002` | `POST /reload` or `POST /official` by a non-administrator; `GET /official` by a viewer who does not see every node; a token without `risk:write` on a write | |
| 404 | `DRS-1001` | no P&L vectors in any connector; no scenario set for the date; no such hierarchy; no basket or capture the caller may read; no official record of the date | *no P&L vectors in any connector (kind pnl-vector): build the demo lake …*; *no scenario set for <date>* |
| 422 | `DRS-5202` | the same `TRADE_ID` twice on the date | *the P&L vectors of <date> are not loaded: the trade <id> has two P&L vectors on one business date …* |
| 422 | `DRS-5203` | the date is not one run and no earlier date has vectors (else the earlier date is answered, with the warning) | *the VaR inputs of <date> are not loaded: the vectors come from 2 runs (BATCH_ID …)* |
| 502 | `DRS-1003` | the vectors cannot be read; no connector keeps `pnl-vector.PNL` as a vector column with the columns asked | *the P&L vectors of <date> could not be read: …*; *no connector keeps pnl-vector.PNL as a vector column with the columns …* |
| 503 | `DRS-5204` | a date's vectors exceed `memory-budget-mb` (capacity: decided, open question 9) | *<n> vectors of 509 values need <m> MB; the vector store's budget is <b> MB …: the engine has no room for this date now; raise …* |

### 10.3 What the load checks (as built)

`VarIntake` checks every row as it streams in, and `VarLoader` the date as a whole. A **rejected row** is not loaded, never padded: it is
counted (`provenance.rejected`, `GET /status`) and its reason listed (`provenance.rejections`, the first 20: `DRS-5201 <trade>: <why>`).

| Check | Rule | Outcome |
|---|---|---|
| Live | `TRADE_STATUS` one of the live statuses (none: live); `MATURITY_DATE` on or after the date | not live: left out (`excluded`), not an error |
| Vector length | `len(PNL) = SCENARIO_COUNT` of the date's set | row rejected (`DRS-5201`) |
| Finiteness | no `null`, `NaN` or infinity in `PNL` | row rejected (`DRS-5201`) |
| Failed rows | `STATUS = failed` with a vector | row rejected (`DRS-5201`); a null `PNL` is a failed row, counted (`failed`) |
| Currency | `REPORTING_CCY = USD` (when promoted) | row rejected (`DRS-5201`) |
| Unique trade | one row per (`BUSINESS_DATE`, `SCENARIO_SET_ID`, `TRADE_ID`) | the date is not loaded (`422 DRS-5202`) |
| One run | one `BATCH_ID` across the date's vectors; every `SCENARIO_SET_ID` equals the set's | the date is not loaded (`DRS-5203`); the date before it is answered with the reason in `provenance.warnings`, until the date's vectors change |
| Scenario set | one set per date; `SCENARIO_DATES` strictly increasing and all before `BUSINESS_DATE` | the date is not loaded (`DRS-5203`), as above |
| Memory | the date's vectors within `memory-budget-mb` | `503 DRS-5204` (capacity) |

**Still not checked (planned):** a `TRADE_ID` live in the trade master and a `TT_BOOK` in exactly one leaf of each hierarchy (`unmapped`
in provenance); one root per hierarchy, every `PARENT_ID` existing, a node's `BOOKS` the union of its children's; a RULE portfolio naming
known fields (a missing field compares unequal: §8.4) and a BASKET's members existing (unknown members are listed in
`provenance.unknown`); completeness of the batch against the trades booked; a `TT_BOOK` in no hierarchy node (the trade is in no node,
summed only by scopes naming it); more than 100,000 hierarchy nodes or portfolios on a connector that cannot list a date whole (only the
first 100,000 are read).

## 11. Entitlements and audit

**Who may use VaR at all:** a caller who may open the vector kind (`pnl-vector`, `Entitlements.mayOpen`); otherwise every scope answers
`403`. **Per node:** `drishti.var.access.<role>` maps node ids to a level:

```yaml
drishti:
  var:
    access:
      desk-risk:   { "MGMT~DESK-RATES": positions }       # numbers, children, positions and trades of the node
      group-risk:  { "MGMT~BANK": aggregate }             # numbers of every node beneath, never a position or a trade
```

| Rule | As built |
|---|---|
| No entry for any of the caller's roles | every node at `positions` level (no restriction) |
| Several roles | their entries are merged; `positions` beats `aggregate` for the same node. A role without entries does **not** widen the others |
| Visible trades | the union of the members of the listed nodes (either level); the answer is computed over them, the rest counted as `hidden` |
| Positions level | the union of the members of the `positions` nodes; scopes naming positions, trades, counterparties, rules, baskets, BASKET portfolios or captures must stay inside it |
| Tree | a node is listed when the caller sees at least one of its trades; `expandable` when it has children or the caller sees its positions |
| History | the official node history only for a viewer at full `positions` level; others get the replay, with a note. The official captures and records likewise |
| Cache | answers are keyed by the view's signature, so two viewers with different rights never share one |
| Masks | as built (open question 11): a contribution row's attribute the viewer sees masked reads `•••` (`drishti.security.redact`, the platform's masks: `COUNTERPARTY` in trade rows); `CPTY:` is refused to a viewer who sees the counterparty masked; the page figures (§9.8) are computed as the viewer |
| Tokens | writes (`POST /baskets`, `DELETE /baskets/*/*`, `POST /captures`) need the token scope `risk:write`; `POST /reload` and `POST /official` are never open to a token |

**Audit** (`AuditLog.record(user, action, target, detail)`; best effort, a failure never hides an answer):

| Action | Written by | Target | Detail |
|---|---|---|---|
| `var-view` | every headline (`GET /scope`, `/scopes/{scope}`; also inside a capture, a compare with now, a page's figures and an official capture, as `drishti`) | the canonical scope | `date=<d> version=<stamp> view=<all or restricted> var=<n> es=<n> trades=<n> hidden=<n> members=<digest> ms=<n>` |
| `var-capture` | `POST /captures` | the canonical scope | the same, then `capture=<id>` |
| `var-basket-save`, `var-basket-delete` | `POST /baskets`, `DELETE /baskets/{owner}/{name}` | `<owner>/<name>` | `version=<n> scope=<text> sharedWith=[…]`; *deleted by its owner* |
| `var-reload` | `POST /reload`; the data-load signal (as `drishti`) | `all dates`, or the date | *an administrator's reload*; *the data-load signal of <kind>* |
| `var-official` | an official capture run (as `drishti`) | the business date | `resultVersion=<n> nodes=<n> trades=<n> inputs={…}` |

Path, history and contributions are not audited (they read what a headline already audited).

## 12. Performance and sizing

### 12.1 Measured (as built)

From VAR_ENGINE.md section 13.2 (`drishti-compute/src/test/java/com/ash/drishti/compute/var/VarScaleBenchmark.java`, 500,000 positions
and 1,000,000 trades in memory, 4.07 GB of vectors; one workstation, 24 hardware threads, JDK 25; and the explorer's browser test on
the demo data). Not re-run for this document.

| Operation | Target (p95, warm) | Measured |
|---|---|---|
| VaR, ES and headline of a node | 100 ms | under 1 ms from the node's aggregate |
| the bank minus one desk (aggregates minus a run) | 500 ms | 2.6 ms |
| the whole bank summed from its trades | | 58 ms for 1 M trades (about 70 GB/s on 24 threads) |
| contributions of a desk's 5,000 positions (standalone, component, incremental each) | 300 ms (500 parts) | 148 ms |
| an ad-hoc scope of 50,000 positions | 250 ms | 52 ms (43 ms reading the ids, 9 ms to sum and measure) |
| explorer, click to painted (a desk) | 300 ms | 9–14 ms with answers fetched ahead, 30–35 ms without |
| loading a date | | 3.4 s for 1 M trades after reading; the demo's 1,673 trades 0.6 s from the lake |

### 12.2 Planned benchmark and targets

| Item | Planned |
|---|---|
| Benchmark | **1 M trades × 50 business dates**: the tiered store of §7.3; hot file per date about 2.04 GB of float32 position vectors when every trade is its own position (1 M × 509 × 4 bytes), about 102 GB for 50 dates on local disk, only the pages in use resident; the lake keeps float64 (4.07 GB of raw vectors a date) |
| What is measured | first load of a date (lake → hot file), reopening a history date (map only), a node, a desk minus trades, a 50,000-position basket, a drill to trades (lake read of one position's trades), whole-bank sum, sharded on several nodes |
| Targets | those of VAR_ENGINE.md section 13.2, unchanged; history of an ad-hoc scope over 250 dates as a visible job |

## 13. Test data and generators

### 13.1 What the repository ships (verified 2026-10-25, version 1.3)

`tools/packgen/var/make_var_data.py` (run by `tools/testdata/make_testdata.py`) writes `data/testdata/market-risk/<kind>/` and the
pack's reference samples and Sutra tests. Counts from the files and `data/testdata/testdata.yaml`:

| Kind | Folder files | Dated | Documents | Bytes (gzip) |
|---|---|---|---|---|
| `pnl-vector` | `cob-2026-09-30.part-001.jsonl.gz` … `part-012` | 2026-09-30 | **9,572** trades (CALYPSO 4,155, MUREX 4,078, ENDUR 1,339; LONG 4,887, SHORT 4,685; AMENDED 4,781, VALIDATED 4,682, NEW 109; all `ok`), every vector 509 values; 2,159 of them in 358 securities | 15,735,874 |
| `scenario-set` | `cob-2026-09-30.jsonl.gz` | 2026-09-30 | 1 (`HS-509`, 2024-09-17 to 2026-09-29) | 1,399 |
| `risk-hierarchy` | `cob-2026-09-30.jsonl.gz` | 2026-09-30 | **88** nodes | 2,133 |
| `portfolio` | `cob-2026-09-30.jsonl.gz` | 2026-09-30 | **28** definitions | 1,756 |

The data's 30 books hold every live trade of the date (9,572; open question 1), over 10 desks and 4 legal entities (`LE-FRA`, `LE-LDN`,
`LE-NY`, `LE-TKY`). The rules derive 7,984 positions (§3.7). The 28 portfolios: one HIERARCHY portfolio per desk (`PF-<DESK>-DESK`, 10)
and per legal entity (`PF-LE-<x>`, 4) and `PF-COMM-BOOK-1`; RULE portfolios `PF-USD-RATES`, `PF-EUR-ALL`, `PF-SHORT-RISK`,
`PF-GOVT-BONDS`, `PF-LISTED`, `PF-OPTIONS`, `PF-FX-CROSSES`, `PF-LARGE-LONGS`; BASKET portfolios `PF-ASHA-UNWIND`, `PF-NETTED-FLOW`,
`PF-HEDGE-PAIRS`, `PF-TOP-SECURITIES`, `PF-PARTIAL-FILLS` (single trades of fungible positions); they overlap on purpose. Engine VaR on
2026-09-30: the bank 112,035,906 (ES 160,132,107). No position, `var` or `var-node` folder, and no VaR figure on any document.

**Repository size (as built):** the committed test data and packs grew by 11.96 MB (gzip): the vectors went from 2.20 MB to 15.74 MB;
the retired `position` (0.39 MB), `var-node` (0.83 MB) and `var` (0.03 MB) folders went; the desks lost two fields (0.02 MB).

### 13.2 How it is made

| Step | Rule (as built) |
|---|---|
| Trades | the committed trades of the 60 business dates (ENDUR, CALYPSO, MUREX), replayed to the last date; live = the pack's live statuses (`VALIDATED`, `AMENDED`, `NEW`), maturity on or after the date, book known to a desk: **9,572** trades, every one valued (`--budget 0`, the default) |
| Security ids | for a trade whose position rule keys by `SECURITY_ID` (securities, listed): a synthetic id, a few per product type and currency (about six trades each, by a hash of the trade id) (§3.7) |
| Positions | the pack's `positions:` rules, as the server applies them (`positions.Rules`): 7,984 (476 fungible, 111 netting buys and sells); for the market model, the samples and the portfolios' members only: they are not written |
| Market | about forty correlated factors over 509 + 1 days (`factors.py`): global and group factors, Student-t (4 degrees of freedom) shocks, GARCH(1,1), a 20-day stress episode at 2.5× volatility, four crash days; Python's own `Random`, seeded by text (deterministic everywhere) |
| A trade's vector | `round(q × per_unit × unit[j] + 0.06 × abs(q) × per_unit × t_j)` for j = 0..509 (`q` signed quantity, `per_unit` = a lognormal risk size by product group ÷ the position's gross quantity, `unit` the position's factor path with a short-gamma term for options, `t_j` the trade's own Student-t draws); the first 509 are `PNL` |
| Limits | a node's and a portfolio's VaR limit (an input a risk manager sets) are its VaR today × a headroom (most green, a few tight) at two significant figures |
| Portfolios | `tools/packgen/var/portfolios.py`: definitions only |
| Files | gzip level 9, `mtime` 0, split into parts of about 1.2 MB compressed; the vectors sorted by legal entity, desk, book and trade |

**`make_var_data.py --check`** regenerates into a temporary folder and fails (exit 1) when a committed file's decompressed content or a
sample differs, or a retired folder or sample is still there: *VaR demo data out of date; run tools/packgen/var/make_var_data.py*.
`tools/packgen/var/test_var_data.py` checks every live trade once with 509 finite values from one run, the securities shared and the
fungible positions the rules derive, no VaR figure on any input and the retired tables gone, the portfolios (25 to 30, all three types,
resolving and overlapping, the legal entities covering the bank), the samples being the server's derived positions, the rule language,
the position rules and the measures.

## 14. Retiring the stored version

### 14.1 The `var` kind: id mapping (as built)

The stored `var` kind held 10 desk records (`RESULT_ID` `VAR-<desk>`, with `VAR_99`, `ES_975`, `SVAR`, `LIMIT`, `EXCEPTIONS`,
`SCENARIO_PNL` of 500 values, `PNL_SERIES`, `CONTRIBUTIONS`, `DESK`). It is retired; every old id and link opens the explorer at the
matching scope (the console: `VAR <id>`, `/v/var/<id>`; `drishti-console/routes/var_routes.py`):

| Old id | New scope | Node |
|---|---|---|
| `VAR-COMM` | `DESK:COMM` | `MGMT~DESK-COMM` |
| `VAR-CREDIT` | `DESK:CREDIT` | `MGMT~DESK-CREDIT` |
| `VAR-EQD` | `DESK:EQD` | `MGMT~DESK-EQD` |
| `VAR-FI` | `DESK:FI` | `MGMT~DESK-FI` |
| `VAR-FX` | `DESK:FX` | `MGMT~DESK-FX` |
| `VAR-INFL` | `DESK:INFL` | `MGMT~DESK-INFL` |
| `VAR-MM` | `DESK:MM` | `MGMT~DESK-MM` |
| `VAR-RATES` | `DESK:RATES` | `MGMT~DESK-RATES` |
| `VAR-SFT` | `DESK:SFT` | `MGMT~DESK-SFT` |
| `VAR-STRUCT` | `DESK:STRUCT` | `MGMT~DESK-STRUCT` |

General rule: `VAR-<x>` → `DESK:<x>`; the explorer says where the old id was opened, and an id with no matching node opens it at `BANK`
with a notice. `/v/var/<anything else>` opens `BANK` with the notice. The `VAR` mnemonic is the explorer's word only (a reserved word of
the command line). **Note:** README.md section 14 gives the example *`VAR-COMM` opens `VAR DIVISION:COMM`*; in the data `VAR-COMM` was
the desk `DESK-COMM` (its `DESK` field), and the scope word for a division is `DIV:`, so the mapping above is the one built.

**`var-node`:** `VN-<NODE_ID>` → `NODE:<NODE_ID>` (`VAR VN-…`, `/v/var-node/VN-…`), its history from the official captures (§9.6).

### 14.2 Reconciliation: stored `VAR-COMM` 446.9 m against the engine's 1.97 m

| Desk | Stored `VAR_99` (test data, snapshot 2026-07-08) | Engine VaR (2026-09-30, 1.0: a sample of 60 trades a book) | Engine VaR (2026-09-30, 1.3: every live trade) | Live trades in the desk (all valued from 1.3) |
|---|---|---|---|---|
| COMM | 446,852,430 | 1,969,387 | 17,523,118 | 2,141 |
| CREDIT | 6,053,623,202 | 3,482,878 | 130,670,864 | 943 |
| EQD | 138,121,543 | 2,701,078 | 35,827,767 | 1,201 |
| FI | 2,266,982,845 | 1,086,363 | 33,274,561 | 1,013 |
| FX | 245,660,869 | 3,386,166 | 24,060,533 | 1,164 |
| INFL | 37,530,125 | 1,165,062 | 24,933,130 | 314 |
| MM | 1,301,510 | 1,407,930 | 15,446,375 | 424 |
| RATES | 94,397,741 | 3,012,892 | 74,633,680 | 1,487 |
| SFT | 109,399,249 | 169,638 | 1,536,322 | 611 |
| STRUCT | 2,698,312,800 | 2,625,449 | 9,987,431 | 274 |

**Why they differ** (from the data and the generators, 2026-10-25):

1. **A different model.** The stored figure is not historical simulation. `tools/packgen/banking/data_results.py` (`market_results`)
   sets each book's VaR to `2.33 × sqrt(Σ_trades (|MTM| × 0.004 + Σ|sensitivities| × 0.6)²)`, absolute values with no netting of longs and
   shorts and no correlation, and the desk's to `0.85 × sqrt(Σ book VaR²)`; ES = 1.13 × VaR and sVaR = 1.9 × VaR by construction; the
   500-value `SCENARIO_PNL` is drawn to match. The engine sums real (synthetic) scenario P&Ls with long and short trades netting.
2. **Different trades and sizes.** The stored figure covers the banking generator's whole desk book, whose cloned trades carry amounts
   scaled by 0.2 to 5 (`tools/testdata/universe.py`); the engine's demo values a sample of 60 trades per book (180 of COMM's 2,141 live
   trades) with a per-position risk size of about 210,000 USD a day for commodities.
3. **A different date.** The stored records are the snapshot of 2026-07-08 (every later change file is empty); the engine's are of
   2026-09-30. The pack's own `samples/var.jsonl` holds yet another value for the same id (`VAR-COMM` 10,946,782): the test data rescales
   the samples.

So the two numbers measure different things and no factor relates them; the engine's figure is the one the inputs support. With the
full trade set (1.3) every live trade is valued and each position has its own risk size, so the engine's desk figures grew (the bank
from 9.9 m to 112.0 m); the release notes restate them.

### 14.3 What is removed (as built, in the same release)

| Item | Removed |
|---|---|
| `var` kind | `pack.yaml` kind, mnemonic, route, form, badge and id pattern `^VAR-` (now: the explorer), `graph.fields.VAR_RESULT`; `samples/var.jsonl`; `data/testdata/market-risk/var/`; `sutras/market-risk/var.v1.sutra.yaml` and its Sutra tests; the about sections (English and French); the Calc snippets reading it (`var_es.py`, `var_tails.py`, `var_methods.py`, `var_component.py`, `var_backtest.py`, `var_limits.py`, `var_factor_regression.py`, and the core pack's `desk_performance.py`); the generator's VaR part of `market_results`; its contract |
| `var-node` kind | kind, route, layout, mnemonic `VNODE`, id pattern `^VN-`; samples and test data; `Demo.var_nodes()`; `VarLoader.history` and `VarBook.NodeHistory` (replaced by official captures); `drishti.var.kinds.node`; its contract |
| Computed fields on `position` | `VAR_99`, `ES_975`, `COMPONENT_VAR` (also from the layout's promoted columns), `BOOK_VAR`, `PCT_OF_BOOK_VAR`, `TRADE_QUANTITIES[].VAR_99`, `TRADE_QUANTITIES[].COMPONENT_VAR`, `PNL`: the page reads the engine's (§9.8) |
| Computed fields on `portfolio` | `VAR_99`, `ES_975`, `LIMIT_USE`, `POSITION_COUNT`, `TRADE_COUNT`, `LONG_COUNT`, `SHORT_COUNT`, `GROSS_MV`, `NET_MV`, `POSITIONS[]`, `BY_*[]`, `TOP_CONTRIBUTORS`, `ALL_TRADES`, `PNL` (and the promoted `VAR_99`, `ES_975`, `POSITION_COUNT`, the vector `PNL`): the page reads the engine's (§9.8) |
| The `position` input table | positions are derived by Drishti (§3.7), served by `var-positions` |
| The core pack's `desk` | `VAR_99` and `VAR_RESULT` on each desk record: the desk page's VaR is the engine's (`DESK:<id>`, §9.8) |
| Guard test | `tools/test_contracts.py` (`TheRetiredStoredVersions`) fails the build when `var` or `var-node` returns (a pack's kind, route, mnemonic, sample, contract, Sutra, Sutra test, test-data folder or dataset), when a figure removed from `position`, `desk` or `portfolio` returns, when an input, fundamental or Drishti-owned kind's contract or data carries a column the VaR engine produces (`tools/contracts.policy.yaml`, `engine-columns`: `VAR_99`, `ES_975`, `COMPONENT_VAR`, `BOOK_VAR`, `PCT_OF_BOOK_VAR`, `PCT_OF_VAR`, `HYPO_PNL`, `EXCEPTIONS_250`, `VAR_RESULT`, `VAR_ENGINE`), and when a connector reads `risk-results` |

## 15. A worked example

Real vectors have N = 509; here **N = 10** so every step can be checked by hand. With N = 10 the 99% rank is the worst value (k = 1),
which shows little, so this example uses **α = 80% for VaR and for ES** (k = ⌈10 × 0.2⌉ = 2) and **`component-window` = 3** (h = 1):
`drishti.var.confidence: 0.80`, `es-confidence: 0.80`, `component-window: 3`.

**The data.** One book `BOOK-X`, two positions, three trades. `T1` (long) and `T2` (short) are in position `P1` (same instrument);
`T3` is position `P2`. Scenario indices 0 to 9, oldest first; USD.

| Vector | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|---|
| `T1` | 5 | −8 | 3 | −12 | 6 | −2 | 4 | −5 | 7 | 1 |
| `T2` | −2 | 3 | −1 | 4 | −3 | 1 | −2 | 2 | −3 | 0 |
| `T3` = `P2` | −4 | 2 | −6 | 1 | −9 | 3 | −1 | 0 | 2 | −7 |
| `P1` = T1 + T2 | 3 | −5 | 2 | −8 | 3 | −1 | 2 | −3 | 4 | 1 |
| **Book** = P1 + P2 | −1 | −3 | −4 | **−7** | **−6** | 2 | 1 | −3 | 6 | **−6** |

**Order of the book**, worst first (ties by the lower index): 3 (−7), 4 (−6), 9 (−6), 2 (−4), 1 (−3), 7 (−3), 0 (−1), 6 (1), 5 (2), 8 (6).

| Figure | Computation | Value |
|---|---|---|
| VaR (k = 2) | −(2nd worst) = −v[4] | **6** |
| ES (k = 2) | −(−7 − 6) / 2 | **6.5** |
| tail (ranks 1 to 3) | scenarios 3, 4, 9 | |
| smoothed VaR S | −(−7 − 6 − 6) / 3 | 6.3333 |
| scale c | 6 / 6.3333 | 0.947368 |
| component VaR of P1 | −(−8 + 3 + 1) / 3 × c = 1.3333 × c | **1.2632** |
| component VaR of P2 | −(1 − 9 − 7) / 3 × c = 5 × c | **4.7368** |
| sum of components | 1.2632 + 4.7368 | **6 = VaR** |
| component ES of P1, P2 | −(−8 + 3)/2 = 2.5; −(1 − 9)/2 = 4 | 2.5 + 4 = **6.5 = ES** |
| standalone VaR of P1, P2 | 2nd worst of P1 (−8, −5, …) = 5; of P2 (−9, −7, …) = 7 | 5, 7 |
| diversification | 5 + 7 − 6 | **6** |
| incremental VaR of P1 | VaR(book) − VaR(P2) = 6 − 7 | **−1** (a hedge: removing it raises VaR) |
| incremental VaR of P2 | 6 − VaR(P1) = 6 − 5 | **1** |
| trades in P1 (by=trade) | component VaR T1 = −(−12 + 6 + 1)/3 × c = 1.5789; T2 = −(4 − 3 + 0)/3 × c = −0.3158 | 1.5789 − 0.3158 = 1.2632 (P1's) |

**An ad-hoc scope: the book minus one trade,** `BOOK:BOOK-X - TRD:T2` (canonical `BOOK:BOOK-X - TRD:T2`). Here the set covers no
book whole, so the planner sums `T1` and `T3` in one pass; in a bank, `DESK:X - TRD:T2` covers the desk's other books whole and the
planner takes the desk's aggregate minus `T2` (1 missing trade ≤ max(16, covered/8)). Either way the vector is:

| | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|---|
| Book − T2 = T1 + T3 | 1 | −6 | −3 | **−11** | −3 | 1 | 3 | −5 | 9 | **−6** |

Order: 3 (−11), 1 (−6), 9 (−6), 7 (−5), … → VaR = −v[1] = **6**, ES = −(−11 − 6)/2 = **8.5**. Taking out `T2` leaves the VaR at 6 (its
incremental VaR is 0) but raises ES from 6.5 to 8.5: the short trade hedged the worst day. At α = 99% (k = 1) the book's VaR would be
7, its worst value. With N = 509 the same steps use the 6th worst for VaR, the 13 worst for ES and ranks 4 to 8 for components.

## 16. Open questions and the change table

### 16.1 Open questions

| # | Question | Context |
|---|---|---|
| 1 | **Decided (product owner, 2026-10-25): every live trade, no budget.** *Live* = status `NEW`, `VALIDATED` or `AMENDED` (the pack's `live-statuses`) and maturity on or after the date, book known to a desk: 9,572 trades on 2026-09-30 (10,399 by status alone; 12,076 trade valuations, which include matured and cancelled trades). Built in 2.8.0; the vectors are 15.7 MB compressed | §13 |
| 2 | **Decided (2026-10-25): in memory**, rebuilt from the date's trades and the rules on every load (reproducible); each official capture records the date's membership and the rules (audit). No database or lake table | §3.7 |
| 3 | **Decided (2026-10-25):** the demo risk batch adds a deterministic synthetic `SECURITY_ID` to securities and listed trades (the trade master is unchanged); *securities* and *listed* are the pack's `positions:` rules (by product group and type), configuration not code | §3.7 |
| 4 | Due time of the batch (for example 06:00 New York the next business day) and the completeness rule per book | §3.9 |
| 5 | **Decided (product owner, 2026-10-25): yes.** The data-load signal of `pnl-vector`, `scenario-set`, `risk-hierarchy`, `portfolio` (and `trade`) reloads that date in the engine automatically (the same as `POST /api/v1/var/reload` for the date), so a restated date is never stale, then captures it officially. Built in 2.8.0 | §3.9 |
| 6 | Several scenario sets on one date (a stressed set `SVAR-250`): select by `SCENARIO_SET_ID` (a scope or request parameter), and which is the default? | §3.3 |
| 7 | Lake layout for the vectors at bank scale: sort by hierarchy and partition by scenario set (both designed, not built); file and row-group sizes; `BYTE_STREAM_SPLIT` + ZSTD | §3.1 |
| 8 | In the hot file, keep a float32 trade matrix (`TMAT`) or always read trades from the lake? | §7.3 |
| 9 | **Decided and built (2026-10-25):** `by=node` accepted (`400` off a node); the memory budget answers `503 DRS-5204` (capacity); the largest parts by gross size when they exceed `max-parts` | §9.1, §10 |
| 10 | **Decided (product owner, 2026-10-25): vectors are kept for 7 years** in the lake (Delta `VACUUM` and log retention set so that 7 years of dates and their time travel stay readable). Official end-of-day captures follow the same 7 years. Built in 2.8.0: the lake writers set 7-year log and file retention on `risk` and `risk-results` tables, and `lake maintain` never vacuums them below it (§3.1) | §9.6 |
| 11 | **Decided and built (2026-10-25):** the platform's field masks apply to contribution rows (`•••`); `CPTY:` is refused to a viewer who sees the counterparty masked | §11 |
| 12 | Computed kind names (`var-result` …) and field names (`VAR` with `CONFIDENCE`, or `VAR_99`): the guard test forbids `VAR_99` only on input and fundamental kinds | §9.7 |
| 13 | **Decided (2026-10-25): kept and documented**: the light scales with the days it has; screens show its days, and it is not a regulatory zone before 250 days | §6.5 |
| 14 | **Decided (product owner, 2026-10-25): `LE:` is the bank's legal entity.** The trade's booking `LEGAL_ENTITY` is the source of truth; the `LEGAL` hierarchy rolls up from it, and a trade whose `LEGAL_ENTITY` disagrees with its book's place in the `LEGAL` hierarchy is reported as a data-quality warning in the answer's provenance | §8.5 |

### 16.2 Where the code and the designs disagree (recorded 2026-10-25)

| Topic | Design | Code (wins) |
|---|---|---|
| Vector grain | per position (VAR_ENGINE.md 5.2, VAR_DATA_MODEL.md 1) | per trade (decided 2026-10-24) |
| Position grain | OTC one per trade; fungible book × instrument (VAR_DATA_MODEL.md 3) | as designed, from 2.8.0: derived by Drishti by the pack's rules (§3.7) |
| `POSITION_TYPE` values | `otc`, `fungible` | `single-trade`, `fungible`; the rule (`otc`, `securities`, `listed`) is `POSITION_RULE` |
| Lake layout | partitions by date and scenario set, sorted by hierarchy, 512 MB files, `BYTE_STREAM_SPLIT` + ZSTD | partitioned by date only, sorted by `id` per batch, writer defaults |
| Data version (the pin) | the Delta table version | a SHA-256 stamp of the date partition's files |
| Bitmaps | Roaring, over positions | `java.util.BitSet`, over trades |
| Marginal VaR | per unit of notional or PV | per million of market value (positions) or PV (trades) |
| Audit | basket changes audited | as designed, from 2.8.0 (§11) |
| `by` values | API_GUIDE.md: `child`, `node`, `position`, `trade` | `child`, `node`, `position`, `trade`, `desk`, `book` (2.8.0) |
| Truncated parts | the largest by gross exposure | as designed, from 2.8.0 (by gross size) |
| Provenance | the input tables' versions and batch ids | as designed, from 2.8.0 (`provenance.inputs`) |
| Load signal | README.md section 9: the signal reloads the date (confirmed 2026-10-25) | as designed, from 2.8.0 (and the date is captured officially) |
| `VAR-COMM` mapping | README.md section 14: `DIVISION:COMM` (corrected in 2.8.0 to `DESK:COMM`) | the record is the desk `DESK-COMM`; the division word is `DIV:` |
| Scope sections | README.md sections 4 and 8 point to *VAR.md section 9* (scopes) and *section 11* (memory) | the shared section order puts scopes in section 8 and memory in section 7 |

### 16.3 The change table

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-25 | First version: waves V0 to V3 as built, checked against the code and the test data; the rest of 2.8.0 as the planned contract |
| 1.1 | 2026-10-25 | Product owner's decisions: the data-load signal reloads the engine automatically; vectors kept 7 years; `LE:` is the bank's legal entity (the trade's booking `LEGAL_ENTITY`) |
| 1.2 | 2026-10-25 | Official end-of-day captures published to `risk-results/var-official` for other systems (product owner); official captures kept 7 years |
| 1.3 | 2026-10-25 | **AS BUILT (2.8.0, VaR complete part 1):** the stored `var` and `var-node` kinds retired (§14); computed figures removed from `position`, `portfolio` and `desk`, the pages show the engine's (§9.8), with a guard test; positions derived by Drishti by configured rules (§3.7); every live trade valued (9,572; open question 1); 28 portfolio definitions; official end-of-day captures of every node, published append-only (§9.6); the data-load signal reloads and captures (§3.9); 7-year retention (§3.1); the load's checks and `DRS-5201` to `DRS-5204` (§10); `by=node`, the largest parts, masks, BASKET portfolios refused to aggregate-only viewers, basket and reload audit, input versions and batch ids in provenance, ids in any case; scope words `LE:` (the trades' legal entity) and `CPTY:` (§8.5) |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
