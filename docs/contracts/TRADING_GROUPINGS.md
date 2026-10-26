<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# CTRD, PTRD, LTRD, ITRD, the trading groupings: the data contract

*Version 1.0 · 2026-10-25 · **Status:** proposed (PLANNED for 2.9.0) · Shared rules: [README.md](README.md) · Model contract:
[VAR.md](VAR.md) · Figures from [TVAL.md](TVAL.md) and [VAR.md](VAR.md)*

This is the planned data contract of the four **trading groupings**: the pages that list every trade with a counterparty (CTRD), in a
portfolio or book (PTRD), booked by one of the bank's legal entities (LTRD), or with a leg on an index (ITRD). Today they are stored
summaries made by a `derived` connector over the trade master, capped at 1,000 member trades. From 2.9.0 they are **one computed page
over the scope engine**: every trade, paged, sorted and filtered on the server, for any business date, with the scope's valuation
(TVAL) and VaR beside it. Everything here is a **proposal**; names may change, and each change will be recorded in
[section 16](#16-open-questions-and-the-change-table). The shared rules are in [README.md](README.md); the scope grammar, captures,
baskets and entitlements are VaR's ([VAR.md](VAR.md)).

**Sources read for this contract (2026-10-25):** `config/packs/trading/pack.yaml` (the connector `trade-pages`, its four kinds'
`group-by`, `max-members: 1000`, `fields` and `rows`), `config/packs/trading/contracts/{counterparty-trades,portfolio-trades,legal-entity-trades,index-trades,trade}.contract.yaml`,
the Sutras `config/packs/trading/sutras/pages/*-trades.v1.sutra.yaml`, the trade test data `data/testdata/trades/{endur,calypso,murex}/`
(15,000 trades on 2026-09-30).

## Contents

1. [Summary](#1-summary)
2. [The measure in one page](#2-the-measure-in-one-page)
3. [Inputs from upstream engines](#3-inputs-from-upstream-engines)
4. [Reference and master data read](#4-reference-and-master-data-read)
5. [Conventions specific to the groupings](#5-conventions-specific-to-the-groupings)
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

**What it is.** For any set of trades named by a scope, the **trade list** (every trade, every status, paged and sorted on the
server, with a status filter), the **profile** (counts and notional by product type, index, currency, source system and status;
maturities; upcoming payments in 7, 30 and 90 days from the business date; payments by month) and the **figures** (MtM from TVAL, VaR
and ES from the VaR engine; in 2.10.0 exposure and limits). The four commands become four entry points to the same page:

| Command | Old kind (retired) | New entry | Scope |
|---|---|---|---|
| `CTRD <id>` | `counterparty-trades` | the trade-scope page | `CPTY:<id>` |
| `PTRD <id>` | `portfolio-trades` | the trade-scope page | `BOOK:<id>` (the booking book; §8.2) |
| `LTRD <id>` | `legal-entity-trades` | the trade-scope page | `LE:<id>` (the trade's booking `LEGAL_ENTITY`) |
| `ITRD <id>` | `index-trades` | the trade-scope page | `INDEX:<id>` (new word, §8.1) |

Any other scope opens the same page (`TRADES DESK:RATES & CPTY:CP-SUMMIT <GO>` proposed, open question 1).

**Retired in the same release:** the four stored kinds, their entries in the trading pack's `trade-pages` (`plugin: derived`)
connector, their Sutras and contracts. The new computed kind is `trade-scope`.

| Part | Status | Where |
|---|---|---|
| Trade index per business date over the trade master (all statuses), shared with the measure framework | PLANNED | §7 |
| Scope words `CPTY:`, `LE:` (2.8.0), `INDEX:`, `TRDR:` (2.9.0) | PLANNED | §8 |
| Paging, sorting, filtering on the server; no member cap; any business date | PLANNED | §6, §9 |
| Profile figures (counts, notional, maturities, payments) computed from the trades of the date | PLANNED | §6 |
| MtM from TVAL, VaR and ES from the VaR engine | PLANNED | §6.4 |
| Retirement of the four kinds and the `trade-pages` connector | PLANNED | §14 |

## 2. The measure in one page

**What it answers:** *which trades do we have with this counterparty (in this book, this legal entity, on this index), what are they,
when do they pay, and what are they worth and risking?* For relationship managers, traders, middle office and credit officers.

**The computation in words.** Resolve the scope to a set of trades on the business date (every status unless filtered), then:
count and sum the trades' terms (notional of the first leg, as today), group them, sort and page them, and sum their events' amounts
in windows after the business date. Everything is additive or a list operation; the figures come from the TVAL and VaR engines over
the **live** trades of the same set.

## 3. Inputs from upstream engines

**None of its own.** The groupings read the trade master (fundamental, through the trading pack's connectors) and, for figures, the
TVAL and VaR engines' inputs ([TVAL.md section 3](TVAL.md#3-inputs-from-upstream-engines), [VAR.md section 3](VAR.md#3-inputs-from-upstream-engines)).
The fields read from each trade:

| Field (trade contract) | Type | Used for | Example |
|---|---|---|---|
| `TRADE_ID`, `SOURCE_SYSTEM`, `TRADE_VERSION` | id, text | the row, the source filter | `1000001`, `ENDUR`, `5` |
| `TRADE_STATUS` | text | the status filter and counts; live = `NEW`, `VALIDATED`, `AMENDED` | `VALIDATED` |
| `COUNTERPARTY` | id | `CPTY:` | `CP-HARBORPT` |
| `PORTFOLIO`, `TT_BOOK` | id | `BOOK:` (the booking book: `TT_BOOK`, else `PORTFOLIO`, §8.2) | `BOOK-COMM-2` |
| `LEGAL_ENTITY` | id | `LE:` | `LE-NY` |
| `LEGS[].INDEX.NAME` | text | `INDEX:` (every leg, not only the first two: §8.1) | `IDX-CRUDE-M1` |
| `TRADER_ID` | id | `TRDR:` | `TRDR-FROSSI` |
| `PRODUCT_TYPE`, `PRODUCT_GROUP`, `BUY_SELL_CODE`, `CURRENCY`, `TRADE_DATE`, `MATURITY_DATE` | text, date | columns, groupings, maturity profile | `ComSwap`, `S`, `USD` |
| `LEGS[0].NOTIONAL` | amount (trade currency) | `NOTIONAL` (as today: the first leg's notional; open question 3) | `72000.0` |
| `EVENTS[].EVENT_DATE`, `.AMOUNT`, `.CURRENCY`, `.EVENT_TYPE`, `.PAYMENT_TYPE` | date, amount | payments in windows and by month | `2026-10-05`, `-125000.0` |

**As of a business date.** The trade master is dated by the lake's `business_date` (snapshot and daily changes). A trade *on date D*
is its newest version with `business_date` ≤ D. The 2.8.0 measure framework builds this per date for the trade index; the groupings use
the same index.

## 4. Reference and master data read

| Master (kind) | Key | What for |
|---|---|---|
| `trade` (TRD) | `TRADE_ID` | every row |
| `counterparty` (CPTY) | `COUNTERPARTY` | the page's header (name, LEI, rating, country of risk) |
| `lei-entity` (LEI) | `LEI` | `GROUP:` (the connected group, through GLEIF parents) |
| `legal-entity` (LE), `book`, `desk` | ids | headers and labels |
| `index` (market-data pack) | `INDEX_NAME` | the index's name, group and sub-group (`LEGS[].INDEX.GROUP`) |
| `fx-rate` (market-data pack) | pair, date | notional and payments in USD (§6.2) |
| `netting-set` (NSET) | `TRADE_IDS` | the trade's netting set column |

## 5. Conventions specific to the groupings

| Convention | Rule |
|---|---|
| Statuses | the **list and profile** cover every status (with a filter, default all, as today); the **figures** (MtM, VaR) cover live trades only (`NEW`, `VALIDATED`, `AMENDED`, maturity on or after the date) |
| Notional | the first leg's notional in the trade's currency (today's rule); the page sums it in **USD** at the date's close (today's pages add notionals of different currencies: §13.3) |
| Payment windows | `0 ≤ EVENT_DATE − D ≤ n` days for n = 7, 30, 90, with **D the business date**, not the wall clock (today's `trade-pages` uses `today()`: §13.3) |
| Payment sign | as the event's `AMOUNT` (the bank's view: received positive, paid negative); summed in USD |
| Index of a trade | every distinct `LEGS[].INDEX.NAME`; a trade with two indexes is in both `INDEX:` scopes and counted once in each |
| Sort | stable; ties by `TRADE_ID` |
| Empty | a trade with no counterparty, book or legal entity is in no `CPTY:`, `BOOK:` or `LE:` scope and counted under `unmapped` |

## 6. The computation

### 6.1 The list

Resolve the scope on D to a trade set *S*; apply the filters (status, source system, product type, currency, maturity range, a
`TWHERE:` rule, [TVAL.md open question 4](TVAL.md#161-open-questions)); sort by any column of §3 (or `MTM`, `PV`, `VAR_COMPONENT` from
the figures); return page *p* of size *n* (≤ 1,000) with `total` = |filtered *S*|. No member cap.

### 6.2 The profile

| Figure | Formula over the filtered set (unless *all* is stated) |
|---|---|
| `TRADE_COUNT` | \|S\| |
| `BY_STATUS` | count per `TRADE_STATUS` over the **unfiltered** set (so the filter's choices show their counts) |
| `NOTIONAL_USD` | Σ \|`LEGS[0].NOTIONAL`\| × fx(ccy → USD, D) |
| `BY_PRODUCT_TYPE`, `BY_INDEX`, `BY_CURRENCY`, `BY_SOURCE_SYSTEM` | count and `NOTIONAL_USD` per group |
| `SOURCE_SYSTEMS`, `PRODUCT_TYPES`, `CURRENCIES` | distinct values (as the retired records) |
| `MATURITY_PROFILE` | count and notional per remaining-maturity bucket (`0-1Y`, `1-2Y`, `2-5Y`, `5-10Y`, `10Y+`) |
| `LAST_MATURITY` | max `MATURITY_DATE` |
| `PAY_7D`, `PAY_30D`, `PAY_90D` | Σ USD of `EVENTS[].AMOUNT` with 0 ≤ `EVENT_DATE` − D ≤ 7, 30, 90 days |
| `PAY_7D_EVENTS`, … | count of those events |
| `PAYMENTS_BY_MONTH` | Σ USD and count per calendar month of `EVENT_DATE`, the next 24 months |

All of these are additive or distinct-counts over trades: computed per answer from the trade index (§7), never stored.

### 6.3 Parameters (`drishti.trades` settings, proposed)

| Setting | Default | Meaning |
|---|---|---|
| `drishti.trades.page-max` | `1000` | largest page |
| `drishti.trades.live-statuses` | `[NEW, VALIDATED, AMENDED]` | the figures' trades |
| `drishti.trades.payment-windows` | `[7, 30, 90]` | days |
| `drishti.trades.months` | `24` | months of the payments chart |
| `drishti.trades.index-legs` | `all` | `all` legs, or `first-two` (today's rule) |
| `drishti.trades.hot-dates` | `2` | dates whose trade index stays loaded |

### 6.4 The figures

| Figure | From | Over |
|---|---|---|
| `MTM`, `MTM_GROSS`, `DV01` | TVAL ([TVAL.md section 6](TVAL.md#6-the-computation)) | the live trades of *S* |
| `VAR`, `ES` | VaR ([VAR.md section 6](VAR.md#6-the-computation)) | the same set; a scope VaR cannot answer (a trade without a vector) is counted `missing` |
| Exposure, limits | `EXP.md`, `LIM.md` (2.10.0) | the counterparty's netting sets |

The page asks each engine with the same canonical scope text and date; each figure carries its own provenance. A figure an engine is
switched off for (Admin → Commands) is shown *not active*, never 0.

## 7. What Drishti builds

| Structure | Content | Size (1 M trades) |
|---|---|---|
| Trade index of a date | the measure framework's index extended with **all statuses** (VaR's holds live trades only): ordinal → id, int codes for the columns of §3, `MATURITY_DATE` and `TRADE_DATE` as int days, `NOTIONAL_USD` as float64 | about 100 B a trade: 100 MB |
| Postings by key | per `COUNTERPARTY`, book, `LEGAL_ENTITY`, `INDEX`, `TRADER_ID`: the ordinals (a sorted int array per key, or a bitset when large) | 4 B per trade per key: 20 MB (5 keys; an index posting per leg index) |
| Event table | per trade, its future events within 24 months: (ordinal, day, amount USD); sorted by day | about 20 B an event; 5 future events a trade: 100 MB |
| Per-key profile cache | the profile of each `CPTY:`, `BOOK:`, `LE:`, `INDEX:` key computed on first ask, kept for the date | small |

The events' USD amounts use the date's FX close at load. The trade index of a date is built once (the load signal of a trade
connector rebuilds it; an intraday trade change through the stream or a write-back patches it, open question 4).

## 8. Scopes

### 8.1 Words added (proposed)

| Term | Meaning | Reads |
|---|---|---|
| `CPTY:<id>` | the trades with that counterparty (planned in 2.8.0, [VAR.md section 8.5](VAR.md#85-planned-scope-words)) | `COUNTERPARTY` |
| `LE:<id>` | the trades booked by the bank's legal entity: the trade's **booking `LEGAL_ENTITY`** is the source of truth (product owner, 2026-10-25) | `LEGAL_ENTITY` |
| `INDEX:<name>` | **new**: the trades with any leg on the index (`LEGS[].INDEX.NAME`) | the trade's legs |
| `TRDR:<id>` | **new**: the trades of a trader | `TRADER_ID` |
| `STATUS:<s>[,<s>…]` | **new, list scope only**: the trades with those statuses; combined with `&` (`CPTY:X & STATUS:CANCELLED`) | `TRADE_STATUS` |

`INDEX`, `TRDR` and `STATUS` are letters only, as the grammar requires ([VAR.md section 8.2](VAR.md#82-grammar-as-the-parser-accepts-it));
index names upper-case as every argument (`INDEX:uscpi` is `INDEX:USCPI`; the committed names are all upper case).

### 8.2 `BOOK:` and the trade's book

VaR resolves `BOOK:` through the `MGMT` hierarchy over each trade's `TT_BOOK`. The committed ENDUR trades (5,000) have `TT_BOOK` empty
and the book in `PORTFOLIO`; CALYPSO and MUREX have both equal. Proposed: **the booking book is `TT_BOOK`, else `PORTFOLIO`**, applied
when the trade index is built, so `PTRD BOOK-COMM-2` and `VAR BOOK:BOOK-COMM-2` see the same trades (open question 2).

### 8.3 Statuses in scopes

Measure scopes (VaR, TVAL, FRTB …) resolve to **live** trades. The trade-scope page resolves the same text over **all statuses** and
applies the status filter itself. A scope term naming an id (`TRD:`) finds the trade in any status.

## 9. What Drishti answers

### 9.1 The API (proposed)

Base `/api/v1/trades`, JSON, as the caller.

| Method and path | Parameters | Answer |
|---|---|---|
| `GET /scope` | `scope`, `asOf`, `status` (list; default all), `source`, `product`, `currency`, `maturityFrom`, `maturityTo`, `filter` | the profile (§9.2) without rows |
| `GET /scope/rows` | the same, plus `page` (0), `size` (default 100, ≤ 1,000), `sort` (`MATURITY_DATE`, `-NOTIONAL_USD`, `MTM` …; several, comma separated), `columns` | `{total, page, size, rows}` |
| `GET /scope/rows.csv` | the same filters and sort | every row, streamed (no cap; audited) |
| `GET /scope/payments` | `scope`, `asOf`, `months` | `PAYMENTS_BY_MONTH` and the next 200 events `{TRADE_ID, EVENT_DATE, EVENT_TYPE, AMOUNT, CURRENCY, AMOUNT_USD}` |
| `GET /scope/figures` | `scope`, `asOf` | `{tval: <TVAL totals>, var: <VaR headline>}`, each with provenance, or `notActive` |
| `GET /keys` | `word` (`CPTY`, `BOOK`, `LE`, `INDEX`, `TRDR`), `asOf`, `q` (prefix) | the ids present on the date with their trade counts (for search and pickers) |

### 9.2 The profile and the computed kind

Kind `trade-scope` (id: the canonical scope, filters after `~`: `CPTY:CP-SUMMIT~status=VALIDATED,AMENDED`). Proposed record:

| Field | Type | Meaning |
|---|---|---|
| `SCOPE`, `CANONICAL`, `SCOPE_KIND`, `LABEL`, `BUSINESS_DATE` | text, date | as VaR |
| `TRADE_COUNT`, `LIVE_COUNT` | integer | after filters; live among them |
| `BY_STATUS` | record list | `STATUS`, `COUNT` (unfiltered) |
| `NOTIONAL_USD` | amount | §6.2 |
| `SOURCE_SYSTEMS`, `PRODUCT_TYPES`, `CURRENCIES` | text list | distinct |
| `BY_PRODUCT_TYPE`, `BY_INDEX`, `BY_CURRENCY`, `BY_SOURCE_SYSTEM`, `MATURITY_PROFILE` | record list | `KEY`, `COUNT`, `NOTIONAL_USD` |
| `LAST_MATURITY` | date | |
| `PAY_7D`, `PAY_7D_EVENTS`, `PAY_30D`, `PAY_30D_EVENTS`, `PAY_90D`, `PAY_90D_EVENTS` | amount (USD), integer | §6.2 |
| `PAYMENTS_BY_MONTH` | record list | `MONTH`, `AMOUNT_USD`, `EVENTS` |
| `ROWS` | record list | the **first page** only (100 rows, sorted by `MATURITY_DATE`); the rest through the API, never inside the record |
| `PROVENANCE` | record | the trade connectors' data versions per source system, the FX date, unknown ids, hidden trades, the time |

Note: the retired records' `TRADE_IDS` (every member id) and `TRADES` (every member row) are **not** fields of the new record: a
counterparty with 10,000 trades would carry them all in every read. The rows are paged.

```yaml
# config/connectors/trade-scope.yaml                       (proposed, 2.9.0)
plugin: reactive
mode: reactive
description: Every trade of a scope (CTRD, PTRD, LTRD, ITRD and any scope), paged, with its profile
kinds:
  trade-scope:
    compute: trade-scope
    params:
      status:   { type: text, default: "" }            # comma separated; empty: every status
      source:   { type: text, default: "" }
      product:  { type: text, default: "" }
    result: { required: [SCOPE, BUSINESS_DATE, TRADE_COUNT], types: { TRADE_COUNT: number } }
engine:
  type: java
  entry: com.ash.drishti.compute.measure.trades.TradeScopeEngine     # proposed
  version: "1.0"
triggers:
  - { on: data-version, kinds: [trade], affects: all }
  - { on: live, kinds: [trade] }
  - { on: write-back, kinds: [trade], affects: subscribed }
coalesce: { quiet: 500ms, max-wait: 5s }
cache: { results: 5000, ttl: 1d }
limits: { timeout: 10s, wait: 50ms, max-inflight-per-user: 8 }
```

### 9.3 Official end-of-day captures and the published table

The groupings' money figures are TVAL's and VaR's, published in their own tables (`risk-results/tval-official/`,
`risk-results/var-official/`). The groupings publish only their profile figures by node to
`${DRISHTI_DELTA_ROOT}/risk-results/trades-official/` ([README.md section 11](README.md#11-what-drishti-answers-computed-kinds-the-api-captures),
product owner 2026-10-25): one row per node of both hierarchies per business date, the shared columns (`BUSINESS_DATE`, `HIERARCHY`,
`NODE_ID`, `NODE_NAME`, `PARENT_ID`, `LEVEL`, `CAPTURE_ID`, `RESULT_VERSION`, `INPUT_VERSIONS`, `COMPUTED_AT`, `CONTRACT_VERSION`) and:

| Column | Type | Meaning |
|---|---|---|
| `TRADE_COUNT`, `LIVE_COUNT` | BIGINT | every status; live statuses |
| `NEW_COUNT`, `VALIDATED_COUNT`, `AMENDED_COUNT`, `CANCELLED_COUNT`, `MATURED_COUNT` | BIGINT | by status |
| `NOTIONAL_USD` | DOUBLE | §6.2, live trades |
| `PAY_7D`, `PAY_30D`, `PAY_90D` | DOUBLE | USD, from the business date (§6.2) |
| `PAY_7D_EVENTS`, `PAY_30D_EVENTS`, `PAY_90D_EVENTS` | BIGINT | |
| `COUNTERPARTY_COUNT` | BIGINT | distinct counterparties of the node's live trades |

Counterparty, index and trader keys are not nodes and are not published; no trade rows are published.

### 9.4 The page

The page (mock-ups first, [IMPLEMENTATION_PLAN.md](../architecture/IMPLEMENTATION_PLAN.md#the-computed-measures-roadmap-280-to-2120-product-owner-2026-10-25))
shows the header of the scope's master (counterparty, book, legal entity, index), the figures, the profile charts and the trade grid
(server paging, column sort, status chips with counts, CSV).

## 10. Validation, errors and data quality

| Check | Outcome (codes *proposed* where new) |
|---|---|
| A scope that does not parse; `size` above `page-max`; an unknown `sort` column | `400 DRS-5001` |
| The caller may not open `trade`; aggregate-only probing (§11) | `403 DRS-5002` |
| A trade connector cannot be read for D | `502 DRS-1003`; the page shows the other systems' trades with *ENDUR trades of <date> could not be read* |
| A trade with no FX close for its currency on D | notional and payments of that trade in the trade's currency only, counted `fxMissing`; `NOTIONAL_USD` excludes it and says so |
| Two versions of one trade with the same `business_date` | the higher `TRADE_VERSION` wins; counted `duplicateVersions` in provenance |
| A trade id in two source systems | kept apart by `SOURCE_SYSTEM` (ids are unique per system); counted `crossSystemIds` (open question 5) |
| `CPTY:`, `LE:` or `INDEX:` id unknown on D | resolves to nothing, listed in `provenance.unknown`; `200` with 0 trades |
| More than `drishti.trades.csv-max` (proposed 2,000,000) rows in a CSV | `413 DRS-5303` (proposed) *the export has <n> rows; narrow the scope* |

## 11. Entitlements and audit

The list shows trades, so it follows VaR's `positions` level: a role whose `drishti.var.access` (or the proposed common
`drishti.measures.access`, open question 6) puts a node at `aggregate` sees the profile's counts and sums for that node and no rows. A
scope naming trades (`TRD:`) or a counterparty with trades outside the viewer's nodes is computed over the visible trades; the rest are
counted `hidden`. Masked fields (a masked `COUNTERPARTY` or `TRADER_ID`) are masked in rows, sort keys (sorted as *masked*) and group
keys. **Audit:** `trades-export` for every CSV, `trades-view` for scopes of more than 10,000 trades.

## 12. Performance and sizing

| Operation | Target (p95, warm, 1 M trades) |
|---|---|
| profile of `CPTY:` with 50,000 trades | 50 ms |
| a page of 100 rows sorted by maturity, scope of 50,000 | 30 ms (top-k over the set) |
| `LE:` of 600,000 trades, page and profile | 300 ms |
| CSV of 50,000 rows | 2 s streamed |
| trade index of a date from the lake | 10 s for 1 M trades with their legs and events (the events are the cost; open question 4) |

## 13. Test data and generators

### 13.1 What is used

No new input. The groupings read the committed trades (`data/testdata/trades/`, 15,000 trades: ENDUR, CALYPSO, MUREX 5,000 each, 60
business dates) and, for the figures, TVAL's and VaR's test data. Test fixtures: a 50-trade sample with two counterparties, two
books, two indexes on the second leg, every status, events inside and outside each window.

### 13.2 How the retired records were made

`config/packs/trading/pack.yaml`, connector `trade-pages` (`plugin: derived`, `refresh: 60s`, `store: disk`): for each kind,
`group-by` one expression over the trade master (`$.COUNTERPARTY`, `$.PORTFOLIO`, `$.LEGAL_ENTITY`, `nonblank($.LEGS[0].INDEX.NAME,
$.LEGS[1].INDEX.NAME)`), `members: TRADE_IDS` capped at **1,000** (`max-members`), `fields` (`count`, `sum first($.LEGS).NOTIONAL`,
`distinct`, `max $.MATURITY_DATE`, `PAY_nD` sums over events with `days(today(), @.EVENT_DATE)` between 0 and n) and `rows` (13 columns
per trade, `EVENTS` included).

### 13.3 What the current data shows (checked 2026-10-25)

| Finding | Detail |
|---|---|
| **The 1,000-member cap truncates** | 24 counterparties, 30 books, 4 legal entities and 57 indexes on 2026-09-30. Over 1,000 trades: `CP-SUMMIT` (1,860), `CP-NORTHPLAINS` (1,352); `BOOK-COMM-3` (2,029), `BOOK-COMM-2` (1,993), `BOOK-COMM-1` (1,968); `LE-NY` (**10,722**: LTRD shows under a tenth of it), `LE-LDN` (3,276). No index exceeds 1,000 (largest `USCPI`, 558) |
| **Payments use the wall clock** | `PAY_7D` … count events from `today()`, not from the business date: an old date's page shows the windows of the day it is viewed |
| **Notional mixes currencies** | `NOTIONAL` sums the first leg's notional in each trade's currency (USD 8,562 trades, EUR 2,252, JPY 1,359 … of the valuations' 15,000) |
| **Index from the first two legs only** | `nonblank($.LEGS[0]…, $.LEGS[1]…)`: a trade with its index on a third leg is in no ITRD record, and a trade with two indexes only in the first |
| **Statuses** | 2,352 `CANCELLED` and 2,249 `MATURED` of 15,000 are listed with the live ones; the status filter is on the page, the counts are not split |
| **The book** | ENDUR's 5,000 trades have `TT_BOOK` empty; PTRD groups by `PORTFOLIO`, VaR by `TT_BOOK` (§8.2) |

### 13.4 Tests (planned)

`TradeScopeEngineTest` (Java: profile = brute force over the fixture; paging exact; sort stable; windows from the business date; an
index on any leg), `test_trade_scope_api.py` (console: CTRD/PTRD/LTRD/ITRD open the page with the right scope, old links redirect),
the guard test (the four kinds and `trade-pages` absent).

## 14. Retiring the stored version

### 14.1 Id mapping

| Old | New |
|---|---|
| `CTRD CP-SUMMIT`, `/v/counterparty-trades/CP-SUMMIT` | `/v/trade-scope/CPTY:CP-SUMMIT` |
| `PTRD BOOK-COMM-2`, `/v/portfolio-trades/BOOK-COMM-2` | `/v/trade-scope/BOOK:BOOK-COMM-2` |
| `LTRD LE-NY`, `/v/legal-entity-trades/LE-NY` | `/v/trade-scope/LE:LE-NY` |
| `ITRD USCPI`, `/v/index-trades/USCPI` | `/v/trade-scope/INDEX:USCPI` |
| a graph link from a trade's `COUNTERPARTY`, `PORTFOLIO`, `LEGAL_ENTITY` or index to the old kinds | the same link to the trade-scope page |

The four mnemonics stay (familiar to users) and open the page with their scope word; the scope text is the page's id.

### 14.2 What is removed in 2.9.0

| Item | Removed |
|---|---|
| Kinds | `counterparty-trades`, `portfolio-trades`, `legal-entity-trades`, `index-trades` from `config/packs/trading/pack.yaml` (`kinds`, routes, `columns`, `form` entries) |
| Connector | `trade-pages` (`plugin: derived`) and its settings; its disk store under the data folder is deleted at first start |
| Sutras | `config/packs/trading/sutras/pages/{counterparty-trades,portfolio-trades,legal-entity-trades,index-trades}.v1.sutra.yaml` |
| Contracts | the four `config/packs/trading/contracts/*-trades.contract.yaml` (a contract for the computed `trade-scope` replaces them) |
| Docs | their sections in the trading pack's README, guide (`config/packs/trading/guides/trading.md`) and `about.yaml` |
| Guard test | fails when one of the four kinds or a `derived` connector over `trade` with `max-members` returns |

### 14.3 Reconciliation

For every key of the retired kinds on the last date, the new page must give the same `TRADE_COUNT` (no cap: larger for the seven keys
of §13.3), the same distinct lists, and the same `NOTIONAL` when summed in native units per currency; `PAY_nD` differ by the
business-date rule (both computed and listed side by side in the release notes for 2026-09-30).

## 15. A worked example

Business date 2026-09-30. Five trades of counterparty `CP-A` (FX close: EUR → USD 1.10):

| Trade | Status | Book | LE | Ccy | Leg notionals and indexes | Events (date: amount) |
|---|---|---|---|---|---|---|
| T1 | VALIDATED | BOOK-1 | LE-NY | USD | leg 1: 10,000,000 (no index); leg 2: index `SOFR` | 2026-10-03: −50,000; 2026-12-15: −52,000 |
| T2 | AMENDED | BOOK-1 | LE-NY | EUR | leg 1: 5,000,000, `EURIBOR3M` | 2026-10-20: 20,000 (EUR) |
| T3 | CANCELLED | BOOK-2 | LE-LDN | USD | leg 1: 2,000,000 | none |
| T4 | MATURED | BOOK-2 | LE-NY | USD | leg 1: 1,000,000 | 2026-09-29: 5,000 (in the past) |
| T5 | NEW | BOOK-2 | LE-LDN | USD | leg 1: 3,000,000; leg 3: index `SOFR` | 2026-11-30: 7,500 |

`CTRD CP-A` (`CPTY:CP-A`, every status):

| Figure | Computation | Value |
|---|---|---|
| `TRADE_COUNT` | T1–T5 | 5 |
| `BY_STATUS` | | VALIDATED 1, AMENDED 1, CANCELLED 1, MATURED 1, NEW 1 |
| `NOTIONAL_USD` | 10,000,000 + 5,000,000 × 1.10 + 2,000,000 + 1,000,000 + 3,000,000 | **21,500,000** |
| `PAY_7D` | events with 0 ≤ day − 2026-09-30 ≤ 7: T1 on 10-03 | −50,000 (1 event) |
| `PAY_30D` | + T2 on 10-20 (20,000 EUR = 22,000 USD) | −28,000 (2 events) |
| `PAY_90D` | + T5 on 11-30 (61 days), + T1 on 12-15 (76 days) | −72,500 (4 events) |
| `INDEX:SOFR` | T1 (leg 2) and T5 (leg 3: missed by today's two-leg rule) | 2 trades |
| Live figures | T1, T2, T5 (live statuses) | MtM and VaR of these three |

With the filter `status=VALIDATED,AMENDED,NEW` the list shows T1, T2, T5 (`total` 3), and `BY_STATUS` still shows all five counts.

## 16. Open questions and the change table

### 16.1 Open questions

| # | Question | Context |
|---|---|---|
| 1 | A general command for any scope's trade list (`TRADES <scope>`, or `TRD` with a scope), beside the four entry points? | §1 |
| 2 | The booking book: `TT_BOOK`, else `PORTFOLIO` (and should the trade generator fill `TT_BOOK` on ENDUR trades)? | §8.2 |
| 3 | Notional: keep the first leg's notional, or a product-specific notional (the engine's `NOTIONAL` from `valuation`)? | §3, §6.2 |
| 4 | Intraday: should a streamed or written-back trade change patch the date's trade index at once (and the events), or at the next load? | §7, §12 |
| 5 | Trade ids that repeat across source systems: is (`SOURCE_SYSTEM`, `TRADE_ID`) the key everywhere, or are ids globally unique? | §10 |
| 6 | One common access setting for every measure (`drishti.measures.access`) instead of one per measure? | §11 |
| 7 | Should the default status filter be *live* (risk view) rather than *all* (today's operations view)? | §5 |

### 16.2 The change table

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-25 | First version (PLANNED for 2.9.0): the trade-scope page over the scope engine, the words `INDEX:`, `TRDR:`, `STATUS:`, the retirement of CTRD, PTRD, LTRD, ITRD and `trade-pages` |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
