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
# Test data and the demo lakes

`data/testdata/` is the one folder under `data/` that is committed: sixty business days of every dataset the seven packs read,
production-shaped and fictitious, from which **one command builds the Delta lakes** the packs' connectors read. Clone, build, start,
demo: within minutes, on a laptop.

```
data/testdata/
  testdata.yaml                 the manifest: business dates, and per dataset its pack, kind, connector, key, partitions, files and counts
  trades/endur/                 5,000 ENDUR trades: snapshot-2026-07-08.jsonl.gz, changes-2026-07-09.jsonl.gz ... changes-2026-09-30.jsonl.gz
  trades/calypso/               5,000 CALYPSO trades
  trades/murex/                 5,000 MUREX trades
  core/<kind>/                  counterparties, groups, legal entities, books, desks, traders, calendars, issuers, CCPs, clearing accounts
  market-data/<kind>/           curves, vol surfaces and cubes, FX, fixings, indices, equities, commodities, bonds, correlations
  counterparty-risk/<kind>/     agreements, CSAs, netting sets, collateral balances, margin calls, credit limits, CVA, SA-CCR, SIMM, exposure paths
  market-risk/<kind>/           trade valuations, FRTB sensitivities, VaR, stress scenarios and results, P&L explain
  news-and-markets/quote/       a REAL snapshot of the twenty companies' quotes (a year of daily bars), Yahoo Finance
  news-and-markets/news/        a REAL snapshot of the companies' and topics' headlines, Google News
  us-equities/<kind>/           REAL public data: the index companies, their filings and financial facts (SEC EDGAR, Wikidata, GLEIF)
  gleif/<kind>/                 REAL public data: LEI records, ownership groups, relationships, countries, identifiers (GLEIF, CC0)
```

Written by `tools/testdata/make_testdata.py` (deterministic; `--check` says whether the files are current), guarded by
`tools/testdata/test_testdata.py`, read by `drishti.py lake demo` (`tools/lake/demo.py`). The market snapshot under
`news-and-markets/` is the exception: real data, captured with internet by `tools/testdata/capture_market.py`
([below](#the-market-snapshot-news-and-markets-offline)), kept as it is by `make_testdata.py`; so are the us-equities and gleif
packs' public data ([below](#public-data-us-equities-and-gleif)), written by their own generators.

## Quick start: five steps

On Windows, offline, with your own ENDUR files: [How to QA in Windows Environment using Custom Data](../../docs/qa/windows-custom-data.md).

1. **Clone** the repository (`git clone …`, then `cd drishti`).
2. **Install** once: the console's Python with its requirements (PyYAML, deltalake and pyarrow among them),
   `python3 -m venv drishti-console/.venv && drishti-console/.venv/bin/pip install -r drishti-console/requirements.txt`,
   and the server jar: the run configuration **Drishti build server jar** (or `./mvnw -q package -DskipTests -pl drishti-server -am`).
3. **Build the demo lakes**: the run configuration **Build demo lakes**, or

   ```bash
   drishti-console/.venv/bin/python tools/drishti.py lake demo
   ```

   It rebuilds the sixty daily snapshots of each dataset, writes them under `data/delta/<domain>/<kind>/` (the trades under
   `data/delta/trading/trade_endur`, `trade_calypso` and `trade_murex`, partitioned by business date and `PRODUCT_TYPE`; every other
   table by business date), checks the seven packs' connector files and prints what to start next. The sixty days are moved to end on
   **today's business date** (the server's current date, so every page opens on data; `--as-of data` keeps the test data's own
   2026-07-08 to 2026-09-30, `--as-of 2026-10-30` any other end); the documents' own dates of the day (`AS_OF`, `VALUATION_DATE`,
   `RUN_DATE`, `SOURCE_UPDATE_TIME`) move with them, and so does each point of a document's embedded market series (`HISTORY`, `OHLC`,
   `FIXINGS`, `PNL_SERIES`: its `DATE`, by the same number of business days), and so do the dates a market document holds a fixed
   number of business days from its date (an FX spot's value date `SPOT_DATE`, T+2; an IR curve's pillar `POINTS[].MATURITY`), so a price history has no missing days between the series
   and the stored days. A market document's `CHANGE_1D` is the day's move of its own price (`PRICE`, `LEVEL`, `MID`), and the fields quoted next to the price move with it ([below](#the-datasets)). Measured for sixty days on a fast machine: **943 s (about 15 minutes; up to half an hour on a laptop)**,
   85 tables, about 3.4 GB on disk (14 tables are static reference data built from the world-economy and
   news-and-markets packs' samples, 2 the captured quotes and headlines, 8 the us-equities and gleif public data; since 2.9.2 the
   country groups, SIC sectors, ownership groups and LEI countries are computed from them, not stored).
4. **Start** the server and the console: the run configurations **Drishti server (demo lakes)** (the samples off,
   `DRISHTI_DEMO_ENABLED=false`, so every page reads the lakes; with the samples on, a sample answers before the lake for an id both
   hold) and **Drishti console**. The server reads `data/delta` by default (`DRISHTI_DELTA_ROOT` moves it).

   The same run configuration sets **offline mode** (`DRISHTI_OFFLINE=true`): the server never reaches the internet, the news and
   yfinance connectors stay **off (offline mode)** in Admin → Connectors, and the news-and-markets pack's quotes and headlines come
   from the committed snapshot ([below](#the-market-snapshot-news-and-markets-offline)). On a machine with internet, set it to `false`
   (and enable the live connectors) to see live quotes and headlines instead.

   **The demo ticker (tutorial 12, live BI and boards).** The lakes are daily snapshots: they do not tick, so on these servers a live
   visual's chip says **Listening** (following, nothing has changed) rather than **Live**. Tutorial 12's live trades and exposure come
   from the **demo source**, which ticks by itself about twice a second: start the server with `DRISHTI_DEMO_ENABLED=true` (the run
   configuration **Drishti server**, or edit **Drishti server (demo lakes)** and set that variable to `true`). With it on, a sample
   answers before the lake for an id both hold; the lakes still answer every other id.
5. **Open the demo pages** (the console on port 17480 by default):

   | Page | What it shows |
   |---|---|
   | [/v/trade/1000001](http://localhost:17480/v/trade/1000001) | an ENDUR ComSwap: the ticket, legs side by side, cash flows opening to their resets, the payment ladder |
   | [/v/trade/MX-20000001](http://localhost:17480/v/trade/MX-20000001) | a MUREX fixed/float swap |
   | [/v/trade/CLY-3000157](http://localhost:17480/v/trade/CLY-3000157) | a CALYPSO government bond |
   | [/v/counterparty-trades/CP-NORTHPLAINS](http://localhost:17480/v/counterparty-trades/CP-NORTHPLAINS) | a look-alike energy counterparty: live trades, payments due in 7/30/90 days, notional by product, maturity ladder |
   | [/v/netting-set/NS-NORTHPLAINS-NY](http://localhost:17480/v/netting-set/NS-NORTHPLAINS-NY) | its netting set: every member trade (`TRADE_IDS`), exposure, CVA, collateral |
   | [/v/counterparty/CP-VAULTGUARD](http://localhost:17480/v/counterparty/CP-VAULTGUARD) | the vault look-alike: UEN, country of risk, legal entity, netting sets |
   | [/bi](http://localhost:17480/bi) | the shipped BI reports and models, now over sixty business days (pick a date, or a range in Analyse) |

   **Running `lake demo` again** (new dates, regenerated data) is safe while a server runs: `lake demo` tells it (the data-load signal for
   every table and date; pass `--server URL` and a token with `loads:write`, `--token-file` or `DRISHTI_TOKEN`, when the server has
   security on), and a running server also sees each rebuilt Delta date by itself and reads it again, so BI never shows the old figures.
   With no server running it leaves `data/bi-store/lake-rebuilt.json`, and the next start clears the stored BI columns and rollups.

   **Compare across days**: on a trade page pick the first of the sixty dates `lake demo` printed, then **Compare** with the last; an
   amended trade (about 6%
   of each book is amended each day: the trades whose `TRADE_VERSION` moved) shows its changed rates, notionals, status or added fees.

## The datasets

**Ids are text; numeric ids in files are read as text.** `TRADE_ID` and every other id (`LEG_ID`, `FLOW_ID`, `RESET_ID`, `EVENT_ID`, `TRADE_IDS`) is a string in the test data and samples (ENDUR's are digits, `"1000008"`). A real extract that writes an id as a JSON number (`1000008`) is read as `"1000008"`, never `1000008.0`; text ids keep their leading zeros; no id prefix or shape is assumed (`SWP-AB_12`, `mx_2000-01`, `007123` are all ids) — `SOURCE_SYSTEM` says which system a trade comes from. An older lake whose `TRADE_ID` column was written as a number is still read (as text).

Sixty business days, 2026-07-08 to 2026-09-30 in the files (USNY calendar; `lake demo` moves them to end today unless `--as-of data`). Every document is UPPER_SNAKE, the shape the packs read; every
counterparty is fictitious (`CP-…`). Columns: the first day's documents, the documents new or changed on each later day, the documents
on the last day, and the folder's compressed size (MB).

| Folder | Pack · kind | Key | Connector → table | Partitions | Day 1 | Changes a day | Day 60 | MB |
|---|---|---|---|---|---|---|---|---|
| `trades/endur` | trading · `trade` | `TRADE_ID` | `trade-endur` → `trading/trade_endur` | business_date, PRODUCT_TYPE | 4,407 | 108–164 | 5,000 | 9.20 |
| `trades/calypso` | trading · `trade` | `TRADE_ID` | `trade-calypso` → `trading/trade_calypso` | business_date, PRODUCT_TYPE | 3,471 | 174–373 | 5,000 | 8.22 |
| `trades/murex` | trading · `trade` | `TRADE_ID` | `trade-murex` → `trading/trade_murex` | business_date, PRODUCT_TYPE | 4,198 | 214–313 | 5,000 | 24.14 |
| `counterparty-risk/agreement` | counterparty-risk · `agreement` | `AGREEMENT_ID` | `legal-store` → `legal/agreement` | business_date | 24 | 0–0 | 24 | 0.00 |
| `market-data/bond` | market-data · `bond` | `ISIN` | `market-store` → `market/bond` | business_date | 32 | 32–32 | 32 | 0.47 |
| `core/book` | core · `book` | `BOOK_ID` | `reference-store` → `reference/book` | business_date | 30 | 30–30 | 30 | 0.56 |
| `core/calendar` | core · `calendar` | `CALENDAR_ID` | `reference-store` → `reference/calendar` | business_date | 4 | 0–0 | 4 | 0.00 |
| `market-data/cap-vol-surface` | market-data · `cap-vol-surface` | `SURFACE_ID` | `market-store` → `market/cap-vol-surface` | business_date | 7 | 0–0 | 7 | 0.00 |
| `core/ccp` | core · `ccp` | `CCP_ID` | `reference-store` → `reference/ccp` | business_date | 5 | 0–0 | 5 | 0.00 |
| `core/clearing-account` | core · `clearing-account` | `ACCOUNT_ID` | `reference-store` → `reference/clearing-account` | business_date | 5 | 2–2 | 5 | 0.10 |
| `counterparty-risk/collateral-balance` | counterparty-risk · `collateral-balance` | `BALANCE_ID` | `collateral-store` → `collateral/collateral-balance` | business_date | 76 | 76–76 | 76 | 0.28 |
| `market-data/commodity` | market-data · `commodity` | `COMMODITY_ID` | `market-store` → `market/commodity` | business_date | 8 | 8–8 | 8 | 0.61 |
| `market-data/commodity-curve` | market-data · `commodity-curve` | `CURVE_ID` | `market-store` → `market/commodity-curve` | business_date | 8 | 7–8 | 8 | 0.06 |
| `market-data/commodity-vol-surface` | market-data · `commodity-vol-surface` | `SURFACE_ID` | `market-store` → `market/commodity-vol-surface` | business_date | 8 | 0–0 | 8 | 0.00 |
| `market-data/correlation-matrix` | market-data · `correlation-matrix` | `MATRIX_ID` | `market-store` → `market/correlation-matrix` | business_date | 3 | 0–0 | 3 | 0.00 |
| `core/counterparty` | core · `counterparty` | `COUNTERPARTY_ID` | `reference-store` → `reference/counterparty` | business_date | 24 | 24–24 | 24 | 0.43 |
| `core/counterparty-group` | core · `counterparty-group` | `GROUP_ID` | `reference-store` → `reference/counterparty-group` | business_date | 23 | 22–23 | 23 | 0.07 |
| `market-data/credit-curve` | market-data · `credit-curve` | `CURVE_ID` | `market-store` → `market/credit-curve` | business_date | 16 | 0–0 | 16 | 0.00 |
| `counterparty-risk/credit-limit` | counterparty-risk · `credit-limit` | `LIMIT_ID` | `credit-store` → `credit/credit-limit` | business_date | 24 | 24–24 | 24 | 0.20 |
| `counterparty-risk/csa` | counterparty-risk · `csa` | `CSA_ID` | `legal-store` → `legal/csa` | business_date | 24 | 0–0 | 24 | 0.00 |
| `counterparty-risk/cva` | counterparty-risk · `cva` | `RESULT_ID` | `credit-store` → `credit/cva` | business_date | 76 | 71–76 | 76 | 0.35 |
| `core/desk` | core · `desk` | `DESK_ID` | `reference-store` → `reference/desk` | business_date | 10 | 10–10 | 10 | 9.04 |
| `market-data/dividend-curve` | market-data · `dividend-curve` | `CURVE_ID` | `market-store` → `market/dividend-curve` | business_date | 15 | 0–0 | 15 | 0.00 |
| `market-data/equity` | market-data · `equity` | `TICKER` | `market-store` → `market/equity` | business_date | 10 | 10–10 | 10 | 0.93 |
| `market-data/equity-index` | market-data · `equity-index` | `INDEX_ID` | `market-store` → `market/equity-index` | business_date | 5 | 5–5 | 5 | 0.09 |
| `market-data/equity-vol-surface` | market-data · `equity-vol-surface` | `SURFACE_ID` | `market-store` → `market/equity-vol-surface` | business_date | 15 | 0–0 | 15 | 0.00 |
| `market-risk/frtb-sensitivity` | market-risk · `frtb-sensitivity` | `RESULT_ID` | `risk-store` → `risk/frtb-sensitivity` | business_date | 30 | 0–0 | 30 | 0.00 |
| `market-data/fx-forward-curve` | market-data · `fx-forward-curve` | `CURVE_ID` | `market-store` → `market/fx-forward-curve` | business_date | 12 | 11–12 | 12 | 0.09 |
| `market-data/fx-spot` | market-data · `fx-spot` | `PAIR` | `market-store` → `market/fx-spot` | business_date | 12 | 12–12 | 12 | 0.18 |
| `market-data/fx-vol-surface` | market-data · `fx-vol-surface` | `SURFACE_ID` | `market-store` → `market/fx-vol-surface` | business_date | 12 | 0–0 | 12 | 0.00 |
| `market-data/inflation-curve` | market-data · `inflation-curve` | `CURVE_ID` | `market-store` → `market/inflation-curve` | business_date | 4 | 0–0 | 4 | 0.00 |
| `market-data/inflation-index` | market-data · `inflation-index` | `INDEX_ID` | `market-store` → `market/inflation-index` | business_date | 4 | 0–0 | 4 | 0.00 |
| `market-data/ir-curve` | market-data · `ir-curve` | `CURVE_ID` | `market-store` → `market/ir-curve` | business_date | 28 | 28–28 | 28 | 0.41 |
| `market-data/ir-vol-cube` | market-data · `ir-vol-cube` | `CUBE_ID` | `market-store` → `market/ir-vol-cube` | business_date | 7 | 0–0 | 7 | 0.00 |
| `core/issuer` | core · `issuer` | `ISSUER_ID` | `reference-store` → `reference/issuer` | business_date | 16 | 0–0 | 16 | 0.00 |
| `core/legal-entity` | core · `legal-entity` | `ENTITY_ID` | `reference-store` → `reference/legal-entity` | business_date | 4 | 4–4 | 4 | 0.08 |
| `counterparty-risk/margin-call` | counterparty-risk · `margin-call` | `CALL_ID` | `collateral-store` → `collateral/margin-call` | business_date | 152 | 0–0 | 152 | 0.00 |
| `counterparty-risk/netting-set` | counterparty-risk · `netting-set` | `NETTING_SET_ID` | `credit-store` → `credit/netting-set` | business_date | 76 | 76–76 | 76 | 23.21 |
| `market-risk/pnl-explain` | market-risk · `pnl-explain` | `RESULT_ID` | `risk-store` → `risk/pnl-explain` | business_date | 30 | 0–0 | 30 | 0.00 |
| `market-data/rate-fixing` | market-data · `rate-fixing` | `INDEX_ID` | `market-store` → `market/rate-fixing` | business_date | 7 | 5–7 | 7 | 0.12 |
| `market-data/repo-curve` | market-data · `repo-curve` | `CURVE_ID` | `market-store` → `market/repo-curve` | business_date | 7 | 7–7 | 7 | 0.03 |
| `counterparty-risk/sa-ccr` | counterparty-risk · `sa-ccr` | `CALC_ID` | `credit-store` → `credit/sa-ccr` | business_date | 76 | 0–0 | 76 | 0.01 |
| `counterparty-risk/simm` | counterparty-risk · `simm` | `CALC_ID` | `collateral-store` → `collateral/simm` | business_date | 24 | 0–0 | 24 | 0.00 |
| `market-risk/stress-result` | market-risk · `stress-result` | `RESULT_ID` | `risk-store` → `risk/stress-result` | business_date | 80 | 0–0 | 80 | 0.00 |
| `market-risk/stress-scenario` | market-risk · `stress-scenario` | `SCENARIO_ID` | `risk-store` → `risk/stress-scenario` | business_date | 8 | 0–0 | 8 | 0.00 |
| `market-risk/trade-valuation` | market-risk · `trade-valuation` | `VALUATION_ID` | `risk-store` → `risk/trade-valuation` | business_date | 12,076 | 12,096–15,000 | 15,000 | 34.60 |
| `core/trader` | core · `trader` | `TRADER_ID` | `reference-store` → `reference/trader` | business_date | 20 | 0–0 | 20 | 0.00 |

Built from the packs' samples (static reference data already in the repository, the same on every day):

| Pack | Connector → tables |
|---|---|
| `world-economy` samples | `macro-store` → `macro/`: `economic-forecast`, `economy`, `fiscal-position`, `labour-market`, `macro-indicator`, `region-economy` |
| `world-economy` samples | `civic-store` → `civic/`: `election`, `jurisdiction`, `office-holder`, `party`, `region`, `social-indicator` |
| `news-and-markets` samples | `news-store` → `news/`: `listed-company`, `news-topic` |
| `news-and-markets/quote`, `news-and-markets/news` (captured) | `news-store` → `news/`: `quote`, `news` (below) |

**Trades.** The common 47-attribute schema of every source system (`SOURCE_SYSTEM`, `TRADE_ID`, `PRODUCT_TYPE`, `COUNTERPARTY`,
`LEGS[]` with `INDEX` and `CASH_FLOWS[]` → `RESETS[]`, `EVENTS[]`: `tools/packgen/trades/schema.py`). Ids are unique across the
systems: ENDUR plain numbers (1000001 …), CALYPSO `CLY-…`, MUREX `MX-…`.

- **ENDUR**: the trading pack's 15 sample deals, then synthetic deals of the five product types of an Endur commodity book (ComSwap,
  ComFut, Cash, CallNot, Commodity), shaped like a real extract (blanks as empty text, digit codes as text). Most are booked with six
  **look-alike counterparties** that mirror the roles of a real Endur book's counterparties, never their names: `CP-VAULTGUARD` (vault
  and custody), `CP-HARBOURLINE-LDN` (nostro bank), `CP-HOUSE` (the bank itself), `CP-METROEX` (futures exchange), `CP-PRAIRIEGAS`
  (gas exchange) and `CP-NORTHPLAINS` (energy corporate); each with a UEN, country of risk, legal entity, ISDA agreement, CSA, credit
  limit and netting sets.
- **CALYPSO and MUREX**: the pack's sample trades with their ids and content, then clones from the systems' clone ranges
  (`CLY-4000000 …`, `MX-30000000 …`) over the whole product range, amounts scaled by a factor between 0.2 and 5, counterparties
  drawn again.
- **Days**: a trade joins the book on the first business day on or after its `TRADE_DATE` (a new booking is a change of that day);
  each later day about 6% of each book is amended (a fixed rate or spread, a leg's notional, the status, an added fee or settlement)
  with `TRADE_VERSION`, `TRADE_ACTION` (`AMEND`), `TRANSACTION_ID` and `SOURCE_UPDATE_TIME` moved on.

**Everything else** reconciles on the last day, with each trade's **latest version** (its status and terms after the amendments):
a netting set holds only live trades (status `NEW`, `VALIDATED` or `AMENDED`, the market-risk pack's `live-statuses`, as VaR: a
cancelled or matured trade neither nets nor exposes) and its `NET_MTM` is the sum of their valuations; a valuation (`TVAL-<id>`) values
the trade's version of its day (`TRADE_STATUS`, and `NOTIONAL`, `MTM`, `PNL_1D`, `DV01` scaled with the first leg's notional); CVA,
limits, books and desks add up the live trades, a counterparty lists its netting sets. Earlier days move the market-sensitive numbers
(MTM, DV01, rates, prices, CVA …) back by a deterministic walk and carry their own date in `AS_OF`, `VALUATION_DATE` and `RUN_DATE`; a
valuation exists from its trade's booking day; a netting set's `TRADE_IDS` and `TRADES` rows list its trades booked by that day.
`tools/testdata/test_testdata.py` checks both rules (2.9.2).

**Market data is one picture each day** (`tools/testdata/marketdays.py`, QA2-201). An equity's `PRICE` and a commodity's `FRONT` are
the close of the document's own `OHLC` bar of that day, an index `LEVEL`, an FX `MID` and a bond `PRICE` the day's point of its own
`HISTORY`; the embedded series are the trailing window ending on the day (no point after it; points before the series' first are walked
back with its own volatility). Next to the price: an FX `BID` and `ASK` keep the spread around `MID`; a bond's `YIELD` moves against its
price by the modified duration (`dy = -dP/P / D`), `Z_SPREAD` takes the credit share of that move and `MOD_DURATION` follows the yield and
the bond's age; an equity's `MARKET_CAP` moves with its price. A fixing index's `FIXINGS` end on the day (`LATEST` the newest, `RATE_PCT`
= `RATE` × 100). The IR and repo curves of a currency move together by one shift and twist a day (`ZERO_RATE`, `QUOTE`, `DF`,
`FORWARDS`, `TEN_Y`, `SLOPE_2S_10S`, `OVERNIGHT`; `BUILT_AT` the day's); a commodity curve is scaled to its commodity's `FRONT`, an FX
forward curve to its pair's `MID`. Credit curves, vol surfaces and cubes, dividend, inflation and correlation documents keep the last
day's values. `tools/testdata/test_market_quotes.py` checks every committed day: no `MID` outside `BID`/`ASK`, no bond price and yield
up together, no headline that is not its own series' point, no curve apart from its underlier.

**Files.** One document a line, gzip, sorted by key: `snapshot-<first day>.jsonl.gz`, then `changes-<day>.jsonl.gz` for each later day
(the documents new or different that day, whole; an empty file when nothing changed). A file that would pass 9 MB is split into
`.part-001`, `.part-002` … (`--part-mb`). A day's state is the snapshot with every change up to that day applied by key.

## The VaR engine's data (market-risk, dated by its documents)

Six more market-risk folders feed the VaR engine ([VAR.md](../../docs/contracts/VAR.md),
[VAR_EXPLORER.md](../../docs/guides/VAR_EXPLORER.md)). They are written by `tools/packgen/var/make_var_data.py` (which
`make_testdata.py` runs) from the trades and desks above, and are **dated by their documents** (`dated-by: BUSINESS_DATE` in
`testdata.yaml`): no snapshot and changes, `lake demo` loads each folder in one run, every document on its own `BUSINESS_DATE`, moved
with the other business dates. 16.2 MB in all (2.9.4).

| Folder | Kind · key | Dates | Documents |
|---|---|---|---|
| `market-risk/pnl-vector` | `pnl-vector` · `VECTOR_ID` | the last day | one 509-scenario P&L vector in USD per live trade (8,233: every live trade but Endur's), with the trade's terms as the batch priced them and a synthetic `SECURITY_ID` on securities and listed trades (`PNL`, a `list<double>` column in the lake), in 10 files |
| `market-risk/scenario-pv` | `scenario-pv` · `SCENARIO_PV_ID` | the last day | 2.9.4: the Endur trades (1,339; 1,089 USD, 246 EUR, 4 CAD) sent as scenario PVs instead: each trade's PV under the 509 scenarios and its base PV, in its own currency (`SCENARIO_PV`, a `list<double>` column), in 3 files; the server converts them to USD P&L when it loads the date |
| `market-risk/fx-scenario` | `fx-scenario` · `FX_SCENARIO_ID` | the last day | 2.9.4: the scenario FX the batch supplies for AUD, CAD, CHF, EUR, GBP and JPY: USD per unit today (the `fx-spot` mid) and under each of the 509 scenarios (moved by the factor model's FX factors) |
| `market-risk/scenario-set` | `scenario-set` · `SCENARIO_SET_ID` | the last day | HS-509: the 509 scenario days |
| `market-risk/risk-hierarchy` | `risk-hierarchy` · `NODE_ID` | the last day | 88 nodes: MGMT (bank, 2 divisions, 10 desks, 30 books) and LEGAL (bank, 4 legal entities, desks, books), with VaR limits |
| `market-risk/portfolio` | `portfolio` · `PORTFOLIO_ID` | the last day | 28 portfolio definitions: HIERARCHY (one per desk and legal entity, and a book), RULE and BASKET, overlapping; no figures |

There is no `position` folder: the server derives positions from the vectors by the market-risk pack's `positions:` rules when it loads
a date (7,984 on the last day). The stored `var` and `var-node` folders were retired in 2.8.0, and no document carries a VaR figure:
the engine computes them. `python3 tools/packgen/var/make_var_data.py --check` says whether the folders match the trades;
`--budget <n> --out <a folder outside the repository>` values a sample of `n` trades a book (a smaller set for a quick local run).

## The exposure engine's data (counterparty-risk, dated by its documents)

Two counterparty-risk folders feed the exposure engine ([EXPOSURE_GUIDE.md](../../docs/guides/EXPOSURE_GUIDE.md)). They are written by
`tools/packgen/ccr/make_ccr_data.py` (which `make_testdata.py` runs) from the netting sets above, and are **dated by their documents**,
as the VaR folders are: `lake demo` loads each in one run into the risk lake's domain (`risk/`), moving the run id, the grid dates and the
rows' ids with the business dates. 6.0 MB in all (2.9.2).

| Folder | Kind · key | Dates | Documents |
|---|---|---|---|
| `counterparty-risk/simulation-run` | `simulation-run` · `RUN_ID` | the last day | XR-<date>-01: 200 paths, a grid of 24 dates (13 primary dates 1W .. 10Y and their 10-business-day look-back dates), the model |
| `counterparty-risk/exposure-path` | `exposure-path` · `PATH_ROW_ID` | the last day | 7,540 rows: every live member trade (523; status NEW, VALIDATED or AMENDED, not matured) of eight netting sets (CP-ALDERSHOT's four, NS-HARBOURLINE-LDN-NY, NS-KESTREL-FRA, NS-AURORA-TKY, NS-CASCADIA-TKY) and grid date up to its maturity, 200 path values each (the vector column `MTM`, whole USD) |

The other netting sets have no paths in the demo: their pages say so. The stored `exposure-profile` folder was retired in 2.9.0, and no
netting set or counterparty carries an exposure figure: the engine computes them. `python3 tools/packgen/ccr/make_ccr_data.py --check`
says whether the folders match the netting sets; `--full --out <a folder outside the repository>` writes every netting set that fits in
10,000 trades with 500 paths (a larger local set).

## The market snapshot (news-and-markets, offline)

The news-and-markets pack computes its quotes (Yahoo Finance, the `yfinance` connector) and headlines (Google News, the `news`
connector) when a page opens: with no internet they would be empty. So the repository keeps a **real snapshot**, captured through
those same connectors, which the pack serves from the demo lake when the live connectors are off or the server runs in offline mode:

| File | Documents | What |
|---|---|---|
| `news-and-markets/quote/snapshot-<date>.jsonl.gz` | `QUOTE-<ticker>` of the twenty companies (`QUOTE_ID`) | the quote (price, change, ranges, volume, market cap) and a year of daily bars (`HISTORY`) |
| `news-and-markets/news/snapshot-<date>.jsonl.gz` | `NEWS-<name>` and `NEWS-<name>~all` of the twenty companies and fifteen topics, and the pack's example `NEWS-Bitcoin` (`NEWS_ID`) | the headlines, the count by source and the steps that chose them |

- **Source and date.** Captured from **Yahoo Finance** (its public chart endpoint) and **Google News** (RSS search) on the UTC date
  in the file name; every document carries `SNAPSHOT` ("Snapshot captured <date> <time> UTC from …"), `SNAPSHOT_CAPTURED_AT` and
  `PROVENANCE.SNAPSHOT`, and the quote's `AS_OF` or the headlines' `FETCHED_AT` ends in *(snapshot captured <date>)*, so every page
  says it is a snapshot. `testdata.yaml` lists both files with their capture time, source, document count and size.
- **Terms.** The data belongs to its providers: prices to Yahoo Finance and the exchanges, headlines and links to Google News and the
  publishers. It is committed for demonstrations of Drishti only, never for trading, research or redistribution; Yahoo's endpoint is
  unofficial and its terms restrict use. Before showing the demo outside the organisation, raise this again.
- **In the lake.** `lake demo` writes both into `news/quote` and `news/news` (the same snapshot on each of the sixty days, never
  re-dated); the pack's `news-store` connector serves them beside the topics and companies.
- **Offline mode.** `DRISHTI_OFFLINE=true` (set by the run configuration **Drishti server (demo lakes)**) keeps the `news`, `yfinance`
  and `feed` connectors from starting, whatever their files say: nothing tries the network, and Admin → Connectors shows them
  **off (offline mode)** with `news-store` RUNNING ([CONFIGURATION](../../docs/admin/CONFIGURATION.md#drishtioffline--offline-mode-no-internet)).
  The company page (`/v/listed-company/CO-AAPL`: quote, closing-price chart, headlines), the topic pages (`/v/news-topic/TOPIC-FED`),
  `QUOTE QUOTE-MSFT`, `NEWS NEWS-Federal_Reserve` and search look as they do live. A name or ticker outside the snapshot (`NEWS-Anything`)
  is *not found* offline.
- **world-economy** needs no snapshot: its thirteen kinds are the pack's samples (real World Bank, IMF, Factbook and UNDP figures),
  already in the repository and built into `macro/` and `civic/`; it has no live connector.

**Refreshing it** (on a machine with internet; the server jar built: run configuration **Drishti build server jar**):

```bash
drishti-console/.venv/bin/python tools/drishti.py testdata capture-market            # about four minutes
drishti-console/.venv/bin/python tools/drishti.py testdata capture-market --dry-run  # what it would fetch: 20 quotes, 71 news ids
```

It starts a scratch server from the jar (its own port, 18977 by default, and folders under `<data>/build/capture-market`, removed at
the end) with only this pack, the two live connectors on (quote history `--range 1y`) and the lake off, then fetches each document
through the server's raw endpoint, two seconds apart (`--delay`, never less), trying again after 5, 10, 20 and 40 s on a 429 or a
server error. `--server URL` uses a server you run instead. It replaces the older snapshot, updates `testdata.yaml` and prints the
sizes; then run `lake demo` and commit `data/testdata`. Exit 0 all captured, 2 some ids missing (the rest written), 1 nothing.
Guarded by `tools/testdata/test_capture_market.py` (a stub server, never the internet) and `test_testdata.py`.

## Public data: us-equities and gleif

The us-equities and gleif packs carry published public data, not generated documents. It follows the same pattern as everything
here: one gzipped JSON Lines snapshot per kind, `us-equities/<kind>/snapshot-<date>.jsonl.gz` (us-company, index-membership,
filing-feed, filing, financial-fact) and `gleif/<kind>/snapshot-<date>.jsonl.gz` (lei-entity, lei-relationship, lei-mapping), about
3.8 MB in all (the SIC sectors, ownership groups and LEI countries are computed from them since 2.9.2, never stored), each listed in `testdata.yaml` with its key, capture date, source, documents and size.

- **In the lake.** `lake demo` writes each kind into `us-equities/<kind>` and `gleif/<kind>` (the same snapshot on every day, never
  re-dated); the packs' Delta connectors `us-equities-store` and `gleif-store` serve them, so both packs work with the demo connector
  off. Their `samples/` folders keep only a few reference documents for their READMEs and guides; nothing is served from there.
  Both packs are off by default: add `us-equities,gleif` to `DRISHTI_PACKS`.
- **Writing it.** The packs' generators write the snapshots and their `testdata.yaml` entries (`tools/testdata/publicsnap.py`):
  `tools/packgen/us_equities/make.py`, `tools/packgen/gleif/make.py`, run by the refresh scripts `tools/publicdata/us_equities.py`
  and `gleif.py` ([PUBLIC_DATA.md](../../docs/guides/PUBLIC_DATA.md)); `make.py --check` without a cache checks the committed
  snapshot. `make_testdata.py` keeps both folders as they are and lists them.
- **Licences.** SEC EDGAR is public domain, GLEIF and Wikidata CC0, the Nasdaq Trader directory a public file; no prices, index
  weights or GICS are included (the packs' guides).

## The manual way: one dataset at a time

`lake demo` is `lake build` run for every dataset and day. By hand, build a day's state into the table its connector reads (the
manifest names the pack, kind, connector, key and partitions of each dataset); the first day's state is its snapshot:

```bash
# the first day of the ENDUR trades, into the table the trade-endur connector reads (trading/trade_endur)
drishti-console/.venv/bin/python tools/drishti.py lake build --from data/testdata/trades/endur/snapshot-2026-07-08.jsonl.gz \
    --pack config/packs/trading --kind trade --connector trade-endur --key TRADE_ID --as-of 2026-09-22 \
    --partition-by PRODUCT_TYPE --lake data/delta

# a kind of another pack (core/counterparty: connector reference-store, domain reference)
drishti-console/.venv/bin/python tools/drishti.py lake build --from data/testdata/core/counterparty/snapshot-2026-07-08.jsonl.gz \
    --pack config/packs/core --kind counterparty --key COUNTERPARTY_ID --as-of 2026-09-22 --lake data/delta
```

A change file holds only that day's new and changed documents, so it is not a day's state on its own: later days are the snapshot with
the changes applied by key. `lake demo --only trades/endur` does that for one dataset, `--only core` for one pack.

**Pointing packs at the lakes.** Each pack's Delta connectors read `${DRISHTI_DELTA_ROOT:<data>/delta}`; at its first start the server
writes them to `config/connectors/<name>.yaml` ([CONNECTOR_FILES.md](../../docs/connectors/CONNECTOR_FILES.md)). `lake demo --lake
/elsewhere` writes the files to point there (or start the server with `DRISHTI_DELTA_ROOT=/elsewhere`); a file that points at another
folder is updated, its earlier text kept in `data/connectors/history/`. Without a lake the packs' samples answer, as before.

## Local only: `--with-real`

```bash
drishti-console/.venv/bin/python tools/drishti.py lake demo --with-real ~/Downloads/endur.json,~/Downloads/ComSwap.json
```

Adds real trade extracts to **the local lake only**: the trades themselves (into the ENDUR table; `BMO_LEGAL_ENTITY` read as
`LEGAL_ENTITY`), a counterparty record for each real counterparty (its id is the name the extract writes in `COUNTERPARTY`), an ISDA
agreement, and a netting set per counterparty and legal entity holding its real trades (`TRADE_IDS` and the trade rows). Nothing is
written to `data/testdata`; a real trade whose id the test data already uses is left out. The repository is public: real values never
go into a committed file, and `tools/testdata/test_testdata.py` fails if a real extract's trade id or counterparty name (read from
`~/Downloads` when the files are there) appears anywhere in the test data. Real extracts often have zero notionals and empty events,
so their charts are flat.

## Regenerating

```bash
python3 tools/testdata/make_testdata.py           # a few minutes; rewrites the dataset folders and testdata.yaml (this README stays)
python3 tools/testdata/make_testdata.py --check   # fails when the files differ from what would be written
python3 -m unittest -q tools/testdata/test_testdata.py tools/testdata/test_market_quotes.py
```

The generator builds on the banking generators (`tools/packgen/banking`, `tools/packgen/trades`): change those, regenerate the
samples, then the test data. It never touches `news-and-markets/` (the captured market snapshot; `capture-market` refreshes it),
`us-equities/` or `gleif/` (their generators write them). The world-economy and news-and-markets datasets are those packs' samples; the world-economy figures
include IMF series, so they are for internal demonstrations only.
