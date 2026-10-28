<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# TVAL, trade valuation totals: the data contract

*Version 1.0 · 2026-10-25 · **Status:** proposed, not built (planned for 2.9.0, parked on 2026-10-26 when 2.9.0 shipped exposure instead; no release set) · Shared rules: [README.md](README.md) · Model contract:
[VAR_GUIDE.md Part II](../guides/VAR_GUIDE.md#14-the-contract-at-a-glance) · Roadmap: [IMPLEMENTATION_PLAN.md](../architecture/IMPLEMENTATION_PLAN.md#the-computed-measures-roadmap-280-to-2120-product-owner-2026-10-25)*

This is the planned data contract of **TVAL**: the valuation of any set of trades, summed and broken down when asked, from one
valuation row per trade that an upstream engine delivers. It replaces the stored kind `trade-valuation`. Everything here is a
**proposal** for the 2.9.0 build: names may still change, and each change will be recorded in [section 16](#16-open-questions-and-the-change-table).
The shared rules (the common risk lake, keys, types, dates, signs, delivery, validation, answers, entitlements, versioning, retirement)
are in [README.md](README.md); the scope grammar, captures, baskets, official end-of-day captures and the entitlement model are VaR's
([VAR_GUIDE.md Part II](../guides/VAR_GUIDE.md#14-the-contract-at-a-glance)) and are referred to, not repeated.

**Sources read for this contract (2026-10-25):** `config/packs/market-risk/contracts/trade-valuation.contract.yaml`, the pack's
`samples/trade-valuation.jsonl` (765 records), `data/testdata/market-risk/trade-valuation/` (a snapshot of 12,076 on 2026-07-08 and
59 daily files, 15,000 on 2026-09-30), `tools/packgen/banking/make_data.py` (`valuations`), `tools/packgen/banking/data_trades.py`,
`tools/testdata/universe.py`, the trading pack's `trade` contract.

## Contents

1. [Summary](#1-summary)
2. [The measure in one page](#2-the-measure-in-one-page)
3. [Inputs from upstream engines](#3-inputs-from-upstream-engines)
4. [Reference and master data read](#4-reference-and-master-data-read)
5. [Conventions specific to TVAL](#5-conventions-specific-to-tval)
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

**What it is.** The mark-to-market (MtM), one-day P&L, DV01 and notional of **any set of trades**, summed and broken down by any
column of the trades (desk, book, counterparty, netting set, product, currency, maturity bucket, legal entity …), in USD or any
reporting currency, for any business date. The per-trade values stay **inputs** from the upstream valuation engine (the boundary of
[IMPLEMENTATION_PLAN.md](../architecture/IMPLEMENTATION_PLAN.md#the-computed-measures-roadmap-280-to-2120-product-owner-2026-10-25));
Drishti computes everything above the trade.

**Commands.** `TVAL <scope> <GO>` (`TVAL DESK RATES <GO>`, `TVAL CPTY CP-SUMMIT <GO>`, `TVAL TRD 1000001 <GO>`); the API
`/api/v1/tval`; the computed kind `valuation-total`.

**Who builds on it.** TVAL is the base of the other measures' MtM figures: the trading groupings' totals
([TRADING_GROUPINGS.md](TRADING_GROUPINGS.md)), P&L explain's actual P&L ([PNL.md](PNL.md)), and in 2.10.0 the netting-set figures,
the expected margin call and SA-CCR's replacement cost (`NSET.md`, `MC.md`, `SACCR.md`, written separately).

**Retired in the same release:** the stored kind `trade-valuation` (TVAL, ids `TVAL-<TRADE_ID>`), its samples, test data, Sutra and
generator; the per-trade values move to the input table `valuation` of the common risk lake (§3, §14).

| Part | Status | Where |
|---|---|---|
| Input table `valuation` (one row per trade per business date) in the common risk lake | PLANNED | §3 |
| The valuation index per date (columnar, trade ordinals shared with the measure framework) and per-node aggregates | PLANNED | §7 |
| Totals and breakdowns over any scope, any business date, any reporting currency; day-over-day change | PLANNED | §6, §9 |
| Computed kind `valuation-total`, API `/api/v1/tval`, captures, CSV | PLANNED | §9 |
| Retirement of `trade-valuation`: `TVAL-<id>` opens `TVAL TRD:<id>` | PLANNED | §14 |

## 2. The measure in one page

**What it answers:** *what is this set of trades worth today, how did that change since yesterday, and where does the value sit?*
For traders and desk heads (MtM of a book or a counterparty), risk and finance (gross and net MtM, DV01 by desk), operations (the
value of a netting set before collateral), and every other measure that needs a trade's MtM.

**The formula in words.**

1. The upstream engine values each live trade at the close of the business date, in USD and in the trade's own currency.
2. A scope's **MtM** is the sum of its trades' MtM (USD). Its **positive MtM** and **negative MtM** are the sums of the trades that are
   worth something to the bank and of those the bank owes; **gross MtM** is the sum of absolute values.
3. The same sum gives the scope's **one-day P&L** as delivered, its **DV01**, its **notional** (gross, absolute), and its counts.
4. A **breakdown** is the same sums grouped by one or two columns of the trades.
5. The **day-over-day change** is the scope's MtM on the date minus its MtM on the previous business date, over the trades of
   either date, with new and gone trades shown apart.
6. **Another reporting currency:** each USD total times the date's closing FX rate USD → target (one rate per date; the sum of
   converted values equals the converted sum).

Every figure of TVAL is **additive** over trades: no rule above the sum. That is why TVAL has no Python engine (§6.4).

## 3. Inputs from upstream engines

### 3.1 Where they live

```
${DRISHTI_DELTA_ROOT}/risk/
└── valuation/        business_date=YYYY-MM-DD/part-*.parquet     one row per trade per business date      (PLANNED, 2.9.0)
```

Served by the common connector `risk-store` (`plugin: delta`, domain `risk`; [README.md section 2](README.md#2-the-common-risk-lake)).
The physical layout follows VaR's ([VAR_GUIDE.md section 15.1](../guides/VAR_GUIDE.md#151-where-they-live)): `id` STRING (the key below), `doc` STRING (the
whole row as JSON, for audit), `business_date` DATE (the partition) and the promoted columns marked **P** below.

### 3.2 `valuation`: one row per trade per business date (PLANNED)

**Grain:** one row per trade, business date and valuation run. **Key:** `VALUATION_KEY` = `<TRADE_ID>` (unique per date); the natural
key is (`BUSINESS_DATE`, `TRADE_ID`). **Rows:** every trade alive on the date (statuses `NEW`, `VALIDATED`, `AMENDED`, maturity on or
after the date; [VAR_GUIDE.md section 23.1, open question 1](../guides/VAR_GUIDE.md#231-open-questions-of-the-contract)), plus trades that matured or were cancelled **on** the date (so
P&L explain sees their last value, §3.4). **Partitioning:** `business_date`; rows sorted by `TT_BOOK`, then `TRADE_ID` (proposed;
the engine does not depend on the order). **Cadence:** once per business date after the close; intraday rows are open question 2.
**Due:** to be agreed (open question 1). **Retention:** 7 years (product owner, 2026-10-25), Delta time travel kept for the same.

Req. is **required**, **optional** or **derived** ([README.md section 5](README.md#5-types-names-and-encodings)). P = promoted to a
Delta column (the engine reads it without parsing the document).

| Column | Type (Delta) | Req. | P | Unit, sign | Meaning | Example |
|---|---|---|---|---|---|---|
| `VALUATION_KEY` | id (`id` STRING) | required | P | | the lake key: the trade id | `1000001` |
| `BUSINESS_DATE` | date (`business_date`) | required | P | | the close of business valued | `2026-09-30` |
| `TRADE_ID` | id (STRING) | required | P | | the booking system's trade id, text even when all digits | `1000001` |
| `SOURCE_SYSTEM` | text | required | P | | `ENDUR`, `CALYPSO`, `MUREX`, … | `ENDUR` |
| `TRADE_VERSION` | text | optional | P | | the trade version valued; P&L explain compares it with the previous date's | `5` |
| `MTM` | amount (DOUBLE) | required | P | USD; positive = an asset of the bank | the trade's mark-to-market in USD, clean of nothing (dirty: accrued included) | `4121280.0` |
| `MTM_NATIVE` | amount (DOUBLE) | optional | P | `MTM_CURRENCY` | the MtM in the trade's valuation currency (display and FX checks only) | `4121280.0` |
| `MTM_CURRENCY` | text (ISO 4217) | optional | P | | currency of `MTM_NATIVE` | `USD` |
| `FX_TO_USD` | rate (DOUBLE) | optional | | USD per unit of `MTM_CURRENCY` | the rate the engine used; checked against the market-data close (§10) | `1.0` |
| `PNL_1D` | amount (DOUBLE) | optional | P | USD; gain positive | the engine's own one-day P&L of the trade (clean, as the engine defines it); TVAL sums it, P&L explain computes its own (§6.3) | `29441.0` |
| `DV01` | amount (DOUBLE) | optional | P | USD per +1 bp parallel shift of every rate curve | the trade's parallel PV01 (the bucketed figures are in `sensitivity`, [FRTB.md](FRTB.md#32-sensitivity-one-row-per-trade-and-risk-factor-the-shared-table)) | `-1250.0` |
| `CS01` | amount (DOUBLE) | optional | P | USD per +1 bp of every credit spread | parallel credit-spread PV01 | `0.0` |
| `NOTIONAL` | amount (DOUBLE) | optional | P | USD, absolute | the trade's current notional in USD (the first leg's, as the engine defines it) | `167969743.2` |
| `ACCRUED` | amount (DOUBLE) | optional | | USD | accrued interest included in `MTM` | `12500.0` |
| `PREMIUM_SETTLED` | amount (DOUBLE) | optional | | USD | upfront cash settled on the date (new trades), from the bank's side | `0.0` |
| `STATUS` | text | required | P | | `ok`, `proxied` (valued by a proxy model) or `failed` (then `MTM` null) | `ok` |
| `PRICER`, `MODEL_VERSION` | text | optional | | | pricer and version | `endur-mtm`, `21.3` |
| `MARKET_DATA_SET` | id | optional | | | the market-data snapshot the run used | `MD-2026-09-30-EOD` |
| `COMPUTED_AT` | timestamp | optional | | UTC | when the trade was valued | `2026-09-30T22:30:00Z` |
| `BATCH_ID` | id | required | P | | the run; one per date per source system | `B-VAL-2026-09-30-01` |
| `RESTATEMENT` | integer | optional | | | 0 first run, 1, 2, … | `0` |
| `TT_BOOK`, `DESK`, `LEGAL_ENTITY`, `COUNTERPARTY`, `NETTING_SET`, `PRODUCT_TYPE`, `PRODUCT_GROUP`, `CURRENCY`, `TRADER_ID`, `MATURITY_DATE`, `TRADE_STATUS` | as the trade | **derived** | P | | denormalised from the trade master on the date when the row is loaded (§7.1); an upstream value is kept in `doc` for audit and compared (§10), never used | `BOOK-COMM-2` … |

**What is no longer sent** (it was on the retired `trade-valuation` record): `VALUATION_ID` (`TVAL-<id>`: the key is the trade id),
`VALUATION_DATE` (it is `BUSINESS_DATE`), `BUY_SELL_CODE`, `TRADE_DATE` and the other trade attributes (read from the trade master;
they are derived above).

### 3.3 Cadence, completeness and the delivery sequence

| Item | Contract |
|---|---|
| Cadence | once per business date (USNY calendar) after the close; several source systems may deliver separately, each with its own `BATCH_ID` |
| Due time | **to be agreed** (open question 1) |
| Complete when | every live trade of the trade master on the date has a row (`ok`, `proxied` or `failed`), per source system |
| One run | one `BATCH_ID` per source system per date |
| Restatement | rewrite the date (whole, per source system); `RESTATEMENT` + 1; a new `BATCH_ID` |
| Signal | `POST /api/v1/packs/market-risk/loads {kind: valuation, businessDate, status, rows, batchId}`; **the signal reloads the date in the TVAL engine automatically** (product owner, 2026-10-25), so a restated date is never stale |

### 3.4 Trades that end on the date

A trade that matures, is cancelled, novated or terminated on the business date keeps a row on that date with its last value (`MTM`
usually 0 after settlement, `PREMIUM_SETTLED` or a cash flow in `pnl-event`, [PNL.md](PNL.md#33-pnl-event-what-changed-between-t1-and-t)).
It is not in the next date. TVAL's totals count it on the date only when `include-ended: true` (default `false`: the totals are of live
trades; the day-over-day change shows it under *gone*).

## 4. Reference and master data read

| Master (kind) | Key | What for |
|---|---|---|
| `trade` (TRD; connectors `trade-endur`, `trade-calypso`, `trade-murex`) | `TRADE_ID` | live status, book (`TT_BOOK`, else `PORTFOLIO`: open question 5), counterparty, legal entity, product, currency, maturity, trader, version; the derived columns of §3.2 |
| `netting-set` (NSET) | `TRADE_IDS` | the trade's netting set (the trade master has none; the retired record carried `NETTING_SET`) |
| `risk-hierarchy` (HIER) | `NODE_ID`, `BOOKS` | the nodes and their books, as VaR ([VAR_GUIDE.md section 15.4](../guides/VAR_GUIDE.md#154-risk-hierarchy-the-trees-and-their-limits-as-built)) |
| `fx-rate` (market-data pack) | currency pair, date | USD → reporting currency at the close of the date (§6.2) |
| `counterparty` (CPTY), `lei-entity` (LEI), `legal-entity` (LE), `book`, `desk` | ids | labels and the `CPTY:`, `GROUP:`, `LE:` scopes |

## 5. Conventions specific to TVAL

| Convention | Rule |
|---|---|
| MtM sign | positive: the trade is an asset of the bank (the counterparty owes); negative: a liability ([README.md section 7](README.md#7-currencies-units-and-signs)) |
| Clean or dirty | `MTM` is **dirty** (accrued included); `ACCRUED` may be shown apart; open question 3 |
| P&L sign | gain positive |
| DV01, CS01 | USD change of `MTM` for **+1 bp** parallel, so a receiver swap has a positive DV01 |
| Currency | every input amount in USD; `MTM_NATIVE` for display only; a reporting currency other than USD converts the USD totals (§6.2) |
| Rounding | figures rounded to whole units in answers and CSV; sums in float64 |
| Notional | absolute USD; summed gross; never netted |
| Failed rows | a `failed` trade is counted (`failed`) and contributes nothing; never taken as 0 MtM silently |

## 6. The computation

### 6.1 Totals

For a scope with trade set *S* on date *D* (live trades unless `include-ended`):

| Figure | Formula |
|---|---|
| `MTM` | Σ_{t∈S} MTM_t |
| `MTM_POSITIVE` | Σ_{t∈S} max(MTM_t, 0) |
| `MTM_NEGATIVE` | Σ_{t∈S} min(MTM_t, 0) |
| `MTM_GROSS` | Σ_{t∈S} \|MTM_t\| = `MTM_POSITIVE` − `MTM_NEGATIVE` |
| `PNL_1D`, `DV01`, `CS01`, `ACCRUED` | Σ over the trades that carry the value; `n` of them reported |
| `NOTIONAL` | Σ \|NOTIONAL_t\| |
| `TRADES`, `VALUED`, `PROXIED`, `FAILED`, `MISSING` | counts: trades in scope; with an `ok` or `proxied` row; `proxied`; `failed`; with no row on the date |

Positive and negative MtM are additive **per trade** (the positive part is taken trade by trade). The netting-set exposure
`max(Σ MtM, 0)` is **not** a TVAL figure: it nets inside a netting set first and belongs to `NSET.md` (2.10.0).

### 6.2 Reporting currency

`ccy=EUR`: every amount figure × r(D), r(D) = the market-data pack's close of USD → EUR on D (`fx-rate`, the mid). One rate per answer,
stated in provenance (`fxRate`, `fxSource`, `fxDate`). A date with no rate answers `422 DRS-5302` (proposed, §10). Breakdowns by
`CURRENCY` show the USD (or reporting) value of each trade currency's trades, never a sum of native amounts in different currencies;
`MTM_NATIVE` is summed only inside one currency group (`nativeTotal`, present only when the grouping is by `CURRENCY` or `MTM_CURRENCY`).

### 6.3 Day-over-day change

For dates D₀ (the previous business date, or `from=`) and D₁:

| Figure | Formula |
|---|---|
| `MTM_CHANGE` | MTM(S, D₁) − MTM(S, D₀), *S* resolved on each date |
| `CONTINUING` | Σ over trades in both dates of (MTM_{t,D₁} − MTM_{t,D₀}) |
| `NEW` | Σ over trades only in D₁ of MTM_{t,D₁} |
| `GONE` | − Σ over trades only in D₀ of MTM_{t,D₀} |
| check | `MTM_CHANGE` = `CONTINUING` + `NEW` + `GONE` exactly |

This is the MtM bridge, not P&L: cash flows and fees are P&L explain's ([PNL.md](PNL.md)). `PNL_1D` as delivered is shown beside
it with the difference (`PNL_1D` − `CONTINUING`), which flags an engine whose one-day P&L does not reconcile to its own MtM (§13.4
shows the test data's does not).

### 6.4 Where it runs

Java only, in the server's measure framework (2.8.0): additions of doubles over trade ordinals and node aggregates. There is **no
Python engine** for TVAL: no rule above the sum is non-additive. The reactive connector (§9.5) uses a Java engine so that TVAL is an
ordinary kind like the others; what-if (a trade added to a scope) uses the upstream value of a booked trade or, for an unbooked trade,
waits for pricing through QLRisk (out of this contract).

### 6.5 Parameters (the `drishti.tval` settings, proposed)

| Setting | Default | Meaning |
|---|---|---|
| `drishti.tval.enabled` | `true` | the engine and its API |
| `drishti.tval.kinds.valuation` | `valuation` | the input kind |
| `drishti.tval.live-statuses` | `[NEW, VALIDATED, AMENDED]` | statuses counted as live (the same list as VaR's positions) |
| `drishti.tval.include-ended` | `false` | count trades ending on the date |
| `drishti.tval.hot-dates` | `3` | dates kept loaded (two for a day-over-day change, one spare) |
| `drishti.tval.max-groups` | `5000` | most groups a breakdown returns (the rest summed as *other*) |
| `drishti.tval.maturity-buckets` | `0-1Y, 1-2Y, 2-5Y, 5-10Y, 10Y+` | the `MATURITY_BUCKET` grouping (from the remaining life on the date) |
| `drishti.tval.access` | `{}` | per role and node: `positions` or `aggregate` (VaR's model, [VAR_GUIDE.md section 9](../guides/VAR_GUIDE.md#9-who-sees-what-entitlements-and-audit)) |

## 7. What Drishti builds

### 7.1 The valuation index of a date

At the date's load (first request, or the load signal):

| Structure | Content |
|---|---|
| Trade ordinals | the date's trade index of the measure framework (the same ordinals VaR uses: sorted by legal entity, desk, book, position, trade) |
| Columns | float64 arrays by ordinal: `MTM`, `PNL_1D`, `DV01`, `CS01`, `NOTIONAL`, `ACCRUED` (NaN = not delivered); a byte per ordinal for `STATUS` |
| Text columns | int codes with dictionaries (as VaR's `TRADE_TEXTS`) for every breakdown column: the derived columns of §3.2 plus `MATURITY_BUCKET` |
| Node aggregates | per hierarchy node: Σ of each amount column, Σ positive and negative MtM, the counts (12 doubles a node) |
| Membership | `OrdinalSet` per resolved scope (VaR's bitsets), cached with the answer |

The planner is VaR's ([VAR_GUIDE.md section 25](../guides/VAR_GUIDE.md#25-one-business-date-in-memory)): a scope that is a node answers from
its aggregate; a node minus a few trades subtracts them; otherwise one pass over the set's ordinals. A breakdown is one pass over the
set, accumulating into an array indexed by the group code.

### 7.2 Sizes

| Item | Per trade | 1 M trades | Test data (≤ 10,000) |
|---|---|---|---|
| amount columns (6 × float64) | 48 B | 48 MB | 0.5 MB |
| text codes (14 × int32) | 56 B | 56 MB | 0.6 MB |
| status, flags | 2 B | 2 MB | |
| ids and index (shared with VaR) | | | |
| node aggregates | 12 × 8 B per node | 0.5 MB for 5,000 nodes | |
| **a date** | **about 110 B** | **about 110 MB** | about 1 MB |

Three hot dates of a 1 M-trade bank hold about 330 MB. No memory tiering is needed; a date is reloaded from the lake in about 2 s
(estimate: 1 M rows × 20 promoted columns).

## 8. Scopes

Every scope of [VAR_GUIDE.md section 5](../guides/VAR_GUIDE.md#5-scopes-portfolios-and-baskets), including the planned `CPTY:`, `GROUP:`, `NSET:`, `LE:` (the trade's booking
`LEGAL_ENTITY`, product owner 2026-10-25), and the words [TRADING_GROUPINGS.md](TRADING_GROUPINGS.md#8-scopes) adds (`INDEX:`,
`TRDR:`). **Everything is additive**, so every scope is answered exactly from node aggregates and runs. `WHERE:` rules see the
position columns (VaR's) and, for TVAL, the trade columns of §7.1 (`MTM`, `NOTIONAL`, `MATURITY_BUCKET`, …) through `TWHERE:` (open
question 4: one rule word over trades, or VaR's position rule extended).

## 9. What Drishti answers

### 9.1 The API (proposed)

Base `/api/v1/tval`, JSON, as the caller; `scope` and `asOf` as VaR; errors RFC 7807 with `code`.

| Method and path | Parameters | Answer |
|---|---|---|
| `GET /scope` | `scope`, `asOf`, `ccy` (default `USD`), `includeEnded` | the totals (§9.2) |
| `GET /scope/breakdown` | `scope`, `asOf`, `ccy`, `by` (one or two of `DESK`, `TT_BOOK`, `LEGAL_ENTITY`, `COUNTERPARTY`, `NETTING_SET`, `PRODUCT_GROUP`, `PRODUCT_TYPE`, `CURRENCY`, `SOURCE_SYSTEM`, `TRADER_ID`, `MATURITY_BUCKET`, `child`), `sort` (`mtm`, `-mtm`, `gross`, `name`), `limit` | groups with the totals of §6.1 each |
| `GET /scope/trades` | `scope`, `asOf`, `ccy`, `page`, `size` (≤ 1,000), `sort` (any column), `filter` (a `TWHERE` rule) | the trades with their values, paged, no cap on the total |
| `GET /scope/change` | `scope`, `asOf`, `from` (default the previous business date), `ccy` | the bridge of §6.3, with `new` and `gone` trades (first 200 each) |
| `POST /captures`, `GET /captures`, `GET /captures/{id}`, `GET /captures/{id}/csv`, `GET /compare` | as VaR ([VAR_GUIDE.md section 6](../guides/VAR_GUIDE.md#6-captures-and-compare)) | captures of TVAL answers (`TVCAP-<yyyymmdd>-<n>`) |
| `GET /status`, `POST /reload` | | as VaR |

### 9.2 The totals

| Field | Type | Meaning |
|---|---|---|
| `scope`, `canonical`, `kind`, `label`, `node`, `businessDate` | string | as VaR's headline |
| `currency` | string | the reporting currency |
| `mtm`, `mtmPositive`, `mtmNegative`, `mtmGross` | integer | §6.1 |
| `pnl1d`, `dv01`, `cs01`, `notional`, `accrued` | integer or null | §6.1; null when no trade of the scope carries the value |
| `trades`, `valued`, `proxied`, `failed`, `missing`, `ended` | integer | counts |
| `children` | list | default parts (`by=child`): `{key, scope, label, mtm, mtmGross, dv01, trades}` |
| `provenance` | object | VaR's fields ([VAR_GUIDE.md section 11.2](../guides/VAR_GUIDE.md#112-the-headline-get-scope-and-its-provenance)) plus `valuation` (`valuation · batches B-… · data version …`), `fxRate`, `fxSource`, `fxDate`, `batchIds` (one per source system) |

### 9.3 Trades (paged)

`{total, page, size, rows: [{TRADE_ID, SOURCE_SYSTEM, PRODUCT_TYPE, TT_BOOK, COUNTERPARTY, CURRENCY, MATURITY_DATE, TRADE_STATUS,
MTM, MTM_NATIVE, MTM_CURRENCY, PNL_1D, DV01, NOTIONAL, STATUS}]}`; sorted server-side; `total` is exact (no member cap).

### 9.4 CSV

`GET /scope/trades.csv` (every row, streamed, same columns) and `GET /scope/breakdown.csv`; the first lines `# scope:`, `# businessDate:`,
`# currency:`, `# fxRate:`, `# dataVersion:`. A role at `aggregate` level gets the breakdown CSV only.

### 9.5 The computed kind and its reactive connector (proposed)

Kind `valuation-total`, id = the canonical scope (parameters after `~`: `DESK:RATES~ccy=EUR`), served by:

```yaml
# config/connectors/tval-engine.yaml                         (proposed, 2.9.0)
plugin: reactive
mode: reactive
description: Valuation totals of any scope (TVAL), from the per-trade valuation table
kinds:
  valuation-total:
    compute: valuation-total
    params:
      ccy:          { type: text, default: USD }
      include-ended: { type: bool, default: false }
      by:           { type: enum, options: [child, DESK, TT_BOOK, LEGAL_ENTITY, COUNTERPARTY, NETTING_SET, PRODUCT_TYPE, CURRENCY, MATURITY_BUCKET], default: child }
    result: { required: [SCOPE, BUSINESS_DATE, MTM, TRADES], types: { MTM: number, TRADES: number } }
engine:
  type: java
  entry: com.ash.drishti.compute.measure.tval.TvalEngine      # proposed: the measure framework's TVAL engine
  version: "1.0"
triggers:
  - { on: data-version, kinds: [valuation, trade, risk-hierarchy], affects: all }
  - { on: write-back, kinds: [trade], affects: subscribed }
coalesce: { quiet: 250ms, max-wait: 2s }
cache: { results: 2000, ttl: 1d }
limits: { timeout: 10s, wait: 50ms, max-inflight-per-user: 8 }
```

Proposed record of `valuation-total`:

| Field | Type | Meaning |
|---|---|---|
| `SCOPE`, `CANONICAL`, `SCOPE_KIND`, `LABEL`, `BUSINESS_DATE`, `CURRENCY` | text, date | |
| `MTM`, `MTM_POSITIVE`, `MTM_NEGATIVE`, `MTM_GROSS`, `PNL_1D`, `DV01`, `CS01`, `NOTIONAL` | amount | §6.1 |
| `TRADES`, `VALUED`, `PROXIED`, `FAILED`, `MISSING` | integer | |
| `BREAKDOWN` | record list | `KEY`, `SCOPE`, `LABEL`, `MTM`, `MTM_GROSS`, `DV01`, `TRADES` per group of `by` |
| `PROVENANCE` | record | §9.2 in UPPER_SNAKE |

**Caching:** the answer cache key is VaR's (date, data version of `valuation` and `trade`, view signature, canonical scope, parameters);
the reactive cache holds the record. A load signal on `valuation` or `trade` drops the date's answers.

### 9.6 Official end-of-day captures and the published table

When a date's `valuation` rows are complete, Drishti captures every node of both hierarchies (`MGMT`, `LEGAL`) as the official record
(VaR's mechanism, [VAR_GUIDE.md section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results)) and publishes it to `${DRISHTI_DELTA_ROOT}/risk-results/tval-official/`
([README.md section 11](README.md#11-what-drishti-answers-computed-kinds-the-api-captures), product owner 2026-10-25): one row per node
per business date, the shared columns (`BUSINESS_DATE`, `HIERARCHY`, `NODE_ID`, `NODE_NAME`, `PARENT_ID`, `LEVEL`, `CAPTURE_ID`,
`RESULT_VERSION`, `INPUT_VERSIONS`, `COMPUTED_AT`, `CONTRACT_VERSION`) and TVAL's figures:

| Column | Type | Meaning |
|---|---|---|
| `CURRENCY` | STRING | `USD` (the published table is always in USD) |
| `MTM`, `MTM_POSITIVE`, `MTM_NEGATIVE`, `MTM_GROSS` | DOUBLE | §6.1, USD |
| `MTM_CHANGE`, `MTM_CHANGE_CONTINUING`, `MTM_CHANGE_NEW`, `MTM_CHANGE_GONE` | DOUBLE | the bridge from the previous business date (§6.3) |
| `PNL_1D`, `DV01`, `CS01`, `NOTIONAL` | DOUBLE | sums as delivered (null when no trade of the node carries the value) |
| `TRADE_COUNT`, `VALUED_COUNT`, `PROXIED_COUNT`, `FAILED_COUNT`, `MISSING_COUNT` | BIGINT | §6.1 |

No trade rows are published; the per-trade values stay in the input table `valuation`.

## 10. Validation, errors and data quality

Codes marked *proposed* do not exist yet; the release assigns them (the range `DRS-530x` is proposed for TVAL and the groupings).

| Check | Rule | Outcome |
|---|---|---|
| Required columns | `TRADE_ID`, `BUSINESS_DATE`, `SOURCE_SYSTEM`, `MTM` (unless `failed`), `STATUS`, `BATCH_ID` | row rejected |
| Finite | `MTM`, `PNL_1D`, `DV01`, `NOTIONAL` finite | row rejected |
| `STATUS` | `failed` ⇔ `MTM` null | row rejected |
| Unique | one row per (`BUSINESS_DATE`, `TRADE_ID`) | load fails |
| One run | one `BATCH_ID` per (`BUSINESS_DATE`, `SOURCE_SYSTEM`) | that system's date not loaded; the engine answers the previous date for its trades with a warning (`DRS-5301` proposed: *the valuations of <system> on <date> mix runs*) |
| Trade known | `TRADE_ID` in the trade master on the date | row kept, flagged, counted `unmapped` |
| Completeness | live trades without a row | `ready` but *incomplete*; counted `missing` per scope |
| FX | \|`MTM_NATIVE` × `FX_TO_USD` − `MTM`\| ≤ max(1, 10⁻⁶ × \|`MTM`\|), and `FX_TO_USD` within 0.5% of the market-data close | row kept, flagged `fx-mismatch` |
| Derived columns | an upstream `TT_BOOK`, `DESK` … different from the trade master's | ignored, counted `attribute-mismatch` in the load report |
| Reporting currency | no rate for the date | `422 DRS-5302` (proposed) *no USD/<ccy> close on <date>* |
| Bad request | unknown `by`, `sort` or `ccy` | `400 DRS-5001` (the existing code) |
| Forbidden | engine off, kind refused, aggregate-only probing | `403 DRS-5002` |
| No data | no `valuation` rows for any date | `404 DRS-1001` |

## 11. Entitlements and audit

As VaR ([VAR_GUIDE.md section 9](../guides/VAR_GUIDE.md#9-who-sees-what-entitlements-and-audit)), with its own setting `drishti.tval.access` and the kind `valuation`
as the gate (a role that may not open `valuation` may not use TVAL). An `aggregate` role sees totals and breakdowns by node, desk and
book, never `GET /scope/trades`, a breakdown by `COUNTERPARTY` or `TRADER_ID` below its nodes (open question 6), or the trades CSV.
Masked fields (a masked `COUNTERPARTY`) are masked in rows and breakdown keys (*masked* as one group). **Audit:** `tval-view` for scopes
of more than `drishti.tval.audit-trades` (10,000) trades and every CSV export; `tval-capture`.

## 12. Performance and sizing

| Operation | Target (p95, warm, 1 M trades) | Basis |
|---|---|---|
| totals of a node | 5 ms | node aggregate |
| totals of an ad-hoc scope of 100,000 trades | 30 ms | one pass, 6 columns |
| breakdown of the bank by counterparty (50,000 groups) | 200 ms | one pass + sort |
| a page of 100 trades sorted by MtM, of a scope of 1 M | 300 ms | partial sort (top-k) over the set |
| day-over-day change of a desk | 50 ms | two dates hot |
| load of a date | 3 s | 1 M rows, 20 promoted columns |

Measured numbers will be recorded in this section after the build (a `TvalScaleBenchmark` beside `VarScaleBenchmark`).

## 13. Test data and generators

### 13.1 Planned test data

| Item | Plan |
|---|---|
| Folder | `data/testdata/market-risk/valuation/cob-<date>.jsonl.gz`, one file per business date, listed in `data/testdata/testdata.yaml` |
| Dates | the last **5** business dates (2026-09-24 to 2026-09-30) for the day-over-day change and P&L explain; open question 7 |
| Trades | the full live set the VaR generator uses (about 9,572 on 2026-09-30; at most 10,000 trades in any local test data, as [VAR_GUIDE.md section 22.1](../guides/VAR_GUIDE.md#221-what-the-repository-ships-verified-2026-10-25-version-13)) |
| Generator | a new `tools/packgen/risk/valuations.py` (proposed), run by `tools/testdata/make_testdata.py`; deterministic (Python `Random` seeded by text, as `tools/packgen/var/factors.py`) |
| Consistency | **MtM moves with the VaR factor model:** MTM_{t,D} = MTM_{t,D−1} + the trade's P&L under the day's own factor move (the 510th element of its VaR path, `make_var_data.py`), so `PNL_1D` = the day's MtM change for continuing trades and P&L explain reconciles (PNL.md §13) |
| `--check` | regenerates into a temporary folder and fails when a committed file differs |

### 13.2 How the retired figures were made (for the reconciliation)

`tools/packgen/banking/make_data.py` (`valuations`) writes one `TVAL-<TRADE_ID>` per trade of every source system:

| System | MTM | PNL_1D | DV01 | DESK |
|---|---|---|---|---|
| CALYPSO, MUREX | the banking generator's model value of the trade (`data_trades.py`: the legs' PV, or notional × N(0, 0.012)) | MTM × N(0, 0.04) | the model's `dv01` risk number (sign × notional × years × 0.9e-4 × U(0.85, 1.05)) | the trade's desk |
| ENDUR | notional × N(0, 0.015) from `Random("tval/<id>")` | MTM × N(0, 0.04) | **0** | **always `DESK-COMM`** |

The test data (`tools/testdata/universe.py`) clones the sample trades with amounts scaled by a factor between 0.2 and 5 and walks the
MtM day by day (`_meta.walk.MTM`); `PNL_1D` is **not** walked in the daily files.

### 13.3 Test plan

| Test | Checks |
|---|---|
| `tools/packgen/risk/test_valuations.py` | one row per live trade; MtM change = the trade's day move; ids unique; deterministic |
| `TvalBookTest` (Java) | node aggregates = brute force; positive + negative = net; gross = positive − negative; breakdowns add up to the total |
| `TvalApiTest` | paging is exact, sort stable, FX conversion, `422` without a rate, aggregate-only refusals |
| Guard test (README section 14) | `trade-valuation` absent; `MTM_TOTAL`-like computed columns absent from input tables |

### 13.4 What the current data shows (checked 2026-10-26, 2.9.2)

- The 2026-09-30 file holds 15,000 valuations (CALYPSO 5,000, MUREX 5,000, ENDUR 5,000), every status included (`CANCELLED` 2,352,
  `MATURED` 2,249). Σ MTM = 3,210,381,877 USD; Σ PNL_1D = 90,804,863 USD. Since 2.9.2 each valuation values its trade's version of the
  day: its `TRADE_STATUS` is the trade's, and `NOTIONAL`, `MTM`, `PNL_1D` and `DV01` follow the first leg's notional
  (`TVAL-1000001`: AMENDED, notional 209,962,179, as the trade's version 7; it valued version 1 at 167,969,743 before).
- **`PNL_1D` does not reconcile to the MtM:** `TVAL-1000001` moves from 5,114,799 (2026-09-29) to 5,151,600 (2026-09-30), +36,801,
  while its `PNL_1D` is −347,255 on both days. The demo walks the MtM only.
- The desks `DESK-MM` and `DESK-SFT` appear on no valuation; all 5,000 ENDUR valuations are on `DESK-COMM` by construction.
- The pack's sample (765 records) has `NETTING_SET` empty; the test data has it filled.

## 14. Retiring the stored version

### 14.1 Id mapping

| Old | New |
|---|---|
| `TVAL-<TRADE_ID>` (`TVAL-1000001`) | `TVAL TRD:<TRADE_ID>` (`/v/valuation-total/TRD:1000001`): the trade's own valuation, with its book's and desk's totals as ancestors |
| `/v/trade-valuation/<id>` | the same, by redirect |
| a search `TVAL where PORTFOLIO = 'X'` (Calc snippets) | `TVAL BOOK:X` or `GET /api/v1/tval/scope/trades?scope=BOOK:X` |

### 14.2 What is removed in 2.9.0

| Item | Removed |
|---|---|
| Kind `trade-valuation` | `pack.yaml` kind, route to `risk-store`, layout, form, id pattern `^TVAL-`, `graph.fields` entries and the pack's `impact.measures` entry for it; mnemonic `TVAL` now opens the computed page |
| Data | `config/packs/market-risk/samples/trade-valuation.jsonl`, `data/testdata/market-risk/trade-valuation/`, its `testdata.yaml` entries |
| Sutra | `config/packs/market-risk/sutras/market-risk/trade-valuation.v1.sutra.yaml` (replaced by the TVAL page) |
| Generator | `valuations()` in `tools/packgen/banking/make_data.py` (replaced by §13.1) |
| Contract | `config/packs/market-risk/contracts/trade-valuation.contract.yaml` (a contract for `valuation`, class `input`, replaces it) |
| About and README | the `trade-valuation` sections of the market-risk `about.yaml` glossary and README |
| Readers | the Calc snippets that search `TVAL` (`frtb_girr_delta.py`, …) move to the TVAL API |
| Guard test | fails when `trade-valuation` returns |

### 14.3 Reconciliation

The computed per-trade MtM **is** the input (no change for a single trade). Totals change only by what the stored data did not
support: the reconciliation lists, per desk, Σ MTM from the last stored `trade-valuation` date against TVAL on the same date from
`valuation` (identical by construction when the new generator copies the old MtM), and explains every difference by: ENDUR desks
(the stored data put every ENDUR deal on `DESK-COMM`; TVAL reads the desk from the trade's book), statuses (the stored file holds
cancelled and matured trades; TVAL counts live ones) and the walk of §13.4.

## 15. A worked example

Three trades on 2026-09-30, reporting in USD then EUR (USD → EUR close 0.9200):

| Trade | Book | Desk | Ccy | MTM_NATIVE | FX_TO_USD | MTM (USD) | DV01 | NOTIONAL (USD) |
|---|---|---|---|---|---|---|---|---|
| T1 | BOOK-RATES-1 | DESK-RATES | USD | 250,000 | 1.0000 | 250,000 | 1,200 | 50,000,000 |
| T2 | BOOK-RATES-1 | DESK-RATES | EUR | −100,000 | 1.0870 | −108,700 | −800 | 30,000,000 |
| T3 | BOOK-FX-1 | DESK-FX | GBP | 40,000 | 1.2500 | 50,000 | 0 | 20,000,000 |

| Figure | Scope `BANK` | Scope `DESK:RATES` |
|---|---|---|
| MTM | 250,000 − 108,700 + 50,000 = **191,300** | 250,000 − 108,700 = **141,300** |
| MTM_POSITIVE | 300,000 | 250,000 |
| MTM_NEGATIVE | −108,700 | −108,700 |
| MTM_GROSS | 408,700 | 358,700 |
| DV01 | 400 | 400 |
| NOTIONAL | 100,000,000 | 80,000,000 |
| MTM in EUR (`ccy=EUR`) | 191,300 × 0.92 = **175,996** | 141,300 × 0.92 = 129,996 |

Breakdown of `BANK` by `CURRENCY` (USD values): USD 250,000; EUR −108,700 (native −100,000); GBP 50,000.

**Day-over-day.** On 2026-09-29 the bank held T1 at 240,000, T2 at −110,000 and a trade T0 at −5,000 that matured on 2026-09-30; T3 is
new on 2026-09-30.

| Figure | Computation | Value |
|---|---|---|
| MTM(D₀) | 240,000 − 110,000 − 5,000 | 125,000 |
| CONTINUING | (250,000 − 240,000) + (−108,700 − (−110,000)) | 11,300 |
| NEW | T3 | 50,000 |
| GONE | −(−5,000) | 5,000 |
| MTM_CHANGE | 191,300 − 125,000 | **66,300** = 11,300 + 50,000 + 5,000 |

## 16. Open questions and the change table

### 16.1 Open questions

| # | Question | Context |
|---|---|---|
| 1 | Due time of the valuation batch per source system (for example 06:00 New York the next business day) | §3.3 |
| 2 | Intraday valuations: a second, intraday table (`valuation-intraday`) or rows with a timestamp on the same table? | §3.2 |
| 3 | `MTM` dirty (accrued included) or clean; does every source system agree? | §5 |
| 4 | A rule language over trades (`TWHERE:`), or VaR's position rule extended to trade columns? | §8 |
| 5 | The trade's book: `TT_BOOK`, else `PORTFOLIO`? The committed ENDUR trades have `TT_BOOK` empty and the book in `PORTFOLIO` | §4 |
| 6 | May an `aggregate` role see a breakdown by counterparty of the nodes it may see? | §11 |
| 7 | How many business dates of valuations the test data ships (5 proposed: about 0.5 MB compressed a date) | §13.1 |
| 8 | Keep the input table's old name `trade-valuation` (class `input`) instead of `valuation`? The rule (README section 14) forbids a retired kind returning; the new name avoids ambiguity | §3, §14 |
| 9 | Is `CS01` and `ACCRUED` delivered by every engine, or optional per source system? | §3.2 |

### 16.2 The change table

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-25 | First version (PLANNED for 2.9.0): the `valuation` input table, totals and breakdowns over any scope, the retirement of `trade-valuation` |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
