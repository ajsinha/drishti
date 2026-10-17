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

`data/testdata/` is the one folder under `data/` that is committed: seven business days of every dataset the seven packs read,
production-shaped and fictitious, from which **one command builds the Delta lakes** the packs' connectors read. Clone, build, start,
demo: within minutes, on a laptop.

```
data/testdata/
  testdata.yaml                 the manifest: business dates, and per dataset its pack, kind, connector, key, partitions, files and counts
  trades/endur/                 5,000 ENDUR trades: snapshot-2026-09-22.jsonl.gz, changes-2026-09-23.jsonl.gz ... changes-2026-09-30.jsonl.gz
  trades/calypso/               5,000 CALYPSO trades
  trades/murex/                 5,000 MUREX trades
  core/<kind>/                  counterparties, groups, legal entities, books, desks, traders, calendars, issuers, CCPs, clearing accounts
  market-data/<kind>/           curves, vol surfaces and cubes, FX, fixings, indices, equities, commodities, bonds, correlations
  counterparty-risk/<kind>/     agreements, CSAs, netting sets, collateral balances, margin calls, credit limits, exposure, CVA, SA-CCR, SIMM
  market-risk/<kind>/           trade valuations, FRTB sensitivities, VaR, stress scenarios and results, P&L explain
```

Written by `tools/testdata/make_testdata.py` (deterministic; `--check` says whether the files are current), guarded by
`tools/testdata/test_testdata.py`, read by `drishti.py lake demo` (`tools/lake/demo.py`).

## Quick start: five steps

1. **Clone** the repository (`git clone …`, then `cd drishti`).
2. **Install** once: the console's Python with its requirements (PyYAML, deltalake and pyarrow among them),
   `python3 -m venv drishti-console/.venv && drishti-console/.venv/bin/pip install -r drishti-console/requirements.txt`,
   and the server jar: the run configuration **Drishti build server jar** (or `./mvnw -q package -DskipTests -pl drishti-server -am`).
3. **Build the demo lakes**: the run configuration **Build demo lakes**, or

   ```bash
   drishti-console/.venv/bin/python tools/drishti.py lake demo
   ```

   It rebuilds the seven daily snapshots of each dataset, writes them under `data/delta/<domain>/<kind>/` (the trades under
   `data/delta/trading/trade_endur`, `trade_calypso` and `trade_murex`, partitioned by business date and `PRODUCT_TYPE`; every other
   table by business date), checks the seven packs' connector files and prints what to start next. Measured on a 24-core laptop with
   an NVMe disk: **48 s, 775 MB peak memory**, 64 tables (16 of them static reference data built from the world-economy and
   news-and-markets packs' samples).
4. **Start** the server and the console: the run configurations **Drishti server** and **Drishti console** (or the compound **Drishti
   QA (server + console)**). The server reads `data/delta` by default (`DRISHTI_DELTA_ROOT` moves it).
5. **Open the demo pages** (the console on port 17480 by default):

   | Page | What it shows |
   |---|---|
   | [/v/trade/1000001](http://localhost:17480/v/trade/1000001) | an ENDUR ComSwap: the ticket, legs side by side, cash flows opening to their resets, the payment ladder |
   | [/v/trade/MX-20000001](http://localhost:17480/v/trade/MX-20000001) | a MUREX fixed/float swap |
   | [/v/trade/CLY-3000157](http://localhost:17480/v/trade/CLY-3000157) | a CALYPSO government bond |
   | [/v/counterparty-trades/CP-NORTHPLAINS](http://localhost:17480/v/counterparty-trades/CP-NORTHPLAINS) | a look-alike energy counterparty: live trades, payments due in 7/30/90 days, notional by product, maturity ladder |
   | [/v/netting-set/NS-NORTHPLAINS-NY](http://localhost:17480/v/netting-set/NS-NORTHPLAINS-NY) | its netting set: every member trade (`TRADE_IDS`), exposure, CVA, collateral |
   | [/v/counterparty/CP-VAULTGUARD](http://localhost:17480/v/counterparty/CP-VAULTGUARD) | the vault look-alike: UEN, country of risk, legal entity, netting sets |
   | [/bi](http://localhost:17480/bi) | the shipped BI reports and models, now over seven business days (pick a date, or a range in Analyse) |

   **Compare across days**: on a trade page pick the date `2026-09-22`, then **Compare** with `2026-09-30`; an amended trade (about 6%
   of each book is amended each day: the trades whose `TRADE_VERSION` moved) shows its changed rates, notionals, status or added fees.

## The datasets

Seven business days, 2026-09-22 to 2026-09-30 (USNY calendar). Every document is UPPER_SNAKE, the shape the packs read; every
counterparty is fictitious (`CP-…`). Columns: the first day's documents, the documents new or changed on each later day, the documents
on the last day, and the folder's compressed size (MB).

| Folder | Pack · kind | Key | Connector → table | Partitions | Day 1 | Changes a day | Day 7 | MB |
|---|---|---|---|---|---|---|---|---|
| `trades/endur` | trading · `trade` | `TRADE_ID` | `trade-endur` → `trading/trade_endur` | business_date, PRODUCT_TYPE | 4,980 | 145–189 | 5,000 | 3.92 |
| `trades/calypso` | trading · `trade` | `TRADE_ID` | `trade-calypso` → `trading/trade_calypso` | business_date, PRODUCT_TYPE | 4,936 | 287–367 | 5,000 | 2.49 |
| `trades/murex` | trading · `trade` | `TRADE_ID` | `trade-murex` → `trading/trade_murex` | business_date, PRODUCT_TYPE | 4,933 | 275–324 | 5,000 | 7.62 |
| `counterparty-risk/agreement` | counterparty-risk · `agreement` | `AGREEMENT_ID` | `legal-store` → `legal/agreement` | business_date | 24 | 0–0 | 24 | 0.00 |
| `market-data/bond` | market-data · `bond` | `ISIN` | `market-store` → `market/bond` | business_date | 32 | 32–32 | 32 | 0.07 |
| `core/book` | core · `book` | `BOOK_ID` | `reference-store` → `reference/book` | business_date | 30 | 30–30 | 30 | 0.06 |
| `core/calendar` | core · `calendar` | `CALENDAR_ID` | `reference-store` → `reference/calendar` | business_date | 4 | 0–0 | 4 | 0.00 |
| `market-data/cap-vol-surface` | market-data · `cap-vol-surface` | `SURFACE_ID` | `market-store` → `market/cap-vol-surface` | business_date | 7 | 0–0 | 7 | 0.00 |
| `core/ccp` | core · `ccp` | `CCP_ID` | `reference-store` → `reference/ccp` | business_date | 5 | 0–0 | 5 | 0.00 |
| `core/clearing-account` | core · `clearing-account` | `ACCOUNT_ID` | `reference-store` → `reference/clearing-account` | business_date | 5 | 2–2 | 5 | 0.01 |
| `counterparty-risk/collateral-balance` | counterparty-risk · `collateral-balance` | `BALANCE_ID` | `collateral-store` → `collateral/collateral-balance` | business_date | 76 | 76–76 | 76 | 0.03 |
| `market-data/commodity` | market-data · `commodity` | `COMMODITY_ID` | `market-store` → `market/commodity` | business_date | 8 | 0–0 | 8 | 0.01 |
| `market-data/commodity-curve` | market-data · `commodity-curve` | `CURVE_ID` | `market-store` → `market/commodity-curve` | business_date | 8 | 8–8 | 8 | 0.01 |
| `market-data/commodity-vol-surface` | market-data · `commodity-vol-surface` | `SURFACE_ID` | `market-store` → `market/commodity-vol-surface` | business_date | 8 | 0–0 | 8 | 0.00 |
| `market-data/correlation-matrix` | market-data · `correlation-matrix` | `MATRIX_ID` | `market-store` → `market/correlation-matrix` | business_date | 3 | 0–0 | 3 | 0.00 |
| `core/counterparty` | core · `counterparty` | `COUNTERPARTY_ID` | `reference-store` → `reference/counterparty` | business_date | 24 | 24–24 | 24 | 0.05 |
| `core/counterparty-group` | core · `counterparty-group` | `GROUP_ID` | `reference-store` → `reference/counterparty-group` | business_date | 23 | 22–23 | 23 | 0.01 |
| `market-data/credit-curve` | market-data · `credit-curve` | `CURVE_ID` | `market-store` → `market/credit-curve` | business_date | 16 | 0–0 | 16 | 0.00 |
| `counterparty-risk/credit-limit` | counterparty-risk · `credit-limit` | `LIMIT_ID` | `credit-store` → `credit/credit-limit` | business_date | 24 | 24–24 | 24 | 0.02 |
| `counterparty-risk/csa` | counterparty-risk · `csa` | `CSA_ID` | `legal-store` → `legal/csa` | business_date | 24 | 0–0 | 24 | 0.00 |
| `counterparty-risk/cva` | counterparty-risk · `cva` | `RESULT_ID` | `credit-store` → `credit/cva` | business_date | 76 | 74–76 | 76 | 0.04 |
| `core/desk` | core · `desk` | `DESK_ID` | `reference-store` → `reference/desk` | business_date | 10 | 10–10 | 10 | 1.16 |
| `market-data/dividend-curve` | market-data · `dividend-curve` | `CURVE_ID` | `market-store` → `market/dividend-curve` | business_date | 15 | 0–0 | 15 | 0.00 |
| `market-data/equity` | market-data · `equity` | `TICKER` | `market-store` → `market/equity` | business_date | 10 | 10–10 | 10 | 0.11 |
| `market-data/equity-index` | market-data · `equity-index` | `INDEX_ID` | `market-store` → `market/equity-index` | business_date | 5 | 5–5 | 5 | 0.01 |
| `market-data/equity-vol-surface` | market-data · `equity-vol-surface` | `SURFACE_ID` | `market-store` → `market/equity-vol-surface` | business_date | 15 | 0–0 | 15 | 0.00 |
| `counterparty-risk/exposure-profile` | counterparty-risk · `exposure-profile` | `PROFILE_ID` | `credit-store` → `credit/exposure-profile` | business_date | 76 | 76–76 | 76 | 0.14 |
| `market-risk/frtb-sensitivity` | market-risk · `frtb-sensitivity` | `RESULT_ID` | `risk-store` → `risk/frtb-sensitivity` | business_date | 30 | 0–0 | 30 | 0.00 |
| `market-data/fx-forward-curve` | market-data · `fx-forward-curve` | `CURVE_ID` | `market-store` → `market/fx-forward-curve` | business_date | 12 | 12–12 | 12 | 0.01 |
| `market-data/fx-spot` | market-data · `fx-spot` | `PAIR` | `market-store` → `market/fx-spot` | business_date | 12 | 12–12 | 12 | 0.02 |
| `market-data/fx-vol-surface` | market-data · `fx-vol-surface` | `SURFACE_ID` | `market-store` → `market/fx-vol-surface` | business_date | 12 | 0–0 | 12 | 0.00 |
| `market-data/inflation-curve` | market-data · `inflation-curve` | `CURVE_ID` | `market-store` → `market/inflation-curve` | business_date | 4 | 0–0 | 4 | 0.00 |
| `market-data/inflation-index` | market-data · `inflation-index` | `INDEX_ID` | `market-store` → `market/inflation-index` | business_date | 4 | 0–0 | 4 | 0.00 |
| `market-data/ir-curve` | market-data · `ir-curve` | `CURVE_ID` | `market-store` → `market/ir-curve` | business_date | 28 | 28–28 | 28 | 0.05 |
| `market-data/ir-vol-cube` | market-data · `ir-vol-cube` | `CUBE_ID` | `market-store` → `market/ir-vol-cube` | business_date | 7 | 0–0 | 7 | 0.00 |
| `core/issuer` | core · `issuer` | `ISSUER_ID` | `reference-store` → `reference/issuer` | business_date | 16 | 0–0 | 16 | 0.00 |
| `core/legal-entity` | core · `legal-entity` | `ENTITY_ID` | `reference-store` → `reference/legal-entity` | business_date | 4 | 4–4 | 4 | 0.01 |
| `counterparty-risk/margin-call` | counterparty-risk · `margin-call` | `CALL_ID` | `collateral-store` → `collateral/margin-call` | business_date | 152 | 0–0 | 152 | 0.00 |
| `counterparty-risk/netting-set` | counterparty-risk · `netting-set` | `NETTING_SET_ID` | `credit-store` → `credit/netting-set` | business_date | 76 | 76–76 | 76 | 1.04 |
| `market-risk/pnl-explain` | market-risk · `pnl-explain` | `RESULT_ID` | `risk-store` → `risk/pnl-explain` | business_date | 30 | 0–0 | 30 | 0.00 |
| `market-data/rate-fixing` | market-data · `rate-fixing` | `INDEX_ID` | `market-store` → `market/rate-fixing` | business_date | 7 | 7–7 | 7 | 0.01 |
| `market-data/repo-curve` | market-data · `repo-curve` | `CURVE_ID` | `market-store` → `market/repo-curve` | business_date | 7 | 7–7 | 7 | 0.00 |
| `counterparty-risk/sa-ccr` | counterparty-risk · `sa-ccr` | `CALC_ID` | `credit-store` → `credit/sa-ccr` | business_date | 76 | 0–0 | 76 | 0.01 |
| `counterparty-risk/simm` | counterparty-risk · `simm` | `CALC_ID` | `collateral-store` → `collateral/simm` | business_date | 24 | 0–0 | 24 | 0.00 |
| `market-risk/stress-result` | market-risk · `stress-result` | `RESULT_ID` | `risk-store` → `risk/stress-result` | business_date | 80 | 0–0 | 80 | 0.00 |
| `market-risk/stress-scenario` | market-risk · `stress-scenario` | `SCENARIO_ID` | `risk-store` → `risk/stress-scenario` | business_date | 8 | 0–0 | 8 | 0.00 |
| `market-risk/trade-valuation` | market-risk · `trade-valuation` | `VALUATION_ID` | `risk-store` → `risk/trade-valuation` | business_date | 14,849 | 14,877–15,000 | 15,000 | 4.47 |
| `core/trader` | core · `trader` | `TRADER_ID` | `reference-store` → `reference/trader` | business_date | 20 | 0–0 | 20 | 0.00 |
| `market-risk/var` | market-risk · `var` | `RESULT_ID` | `risk-store` → `risk/var` | business_date | 10 | 0–0 | 10 | 0.03 |

Built from the packs' samples (static reference data already in the repository, the same on every day):

| Pack | Connector → tables |
|---|---|
| `world-economy` samples | `macro-store` → `macro/`: `country-group`, `economic-forecast`, `economy`, `fiscal-position`, `labour-market`, `macro-indicator`, `region-economy` |
| `world-economy` samples | `civic-store` → `civic/`: `election`, `jurisdiction`, `office-holder`, `party`, `region`, `social-indicator` |
| `news-and-markets` samples | `news-store` → `news/`: `listed-company`, `news-topic` |

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

**Everything else** reconciles on the last day: a netting set's `NET_MTM` is the sum of its trades' valuations, exposure profiles follow
their maturities, CVA uses that exposure and the counterparty's credit curve, limits use the PFE, books and desks add up their trades,
a counterparty lists its netting sets. Earlier days move the market-sensitive numbers (MTM, DV01, rates, prices, exposure, CVA …) back
by a deterministic walk and carry their own date in `AS_OF`, `VALUATION_DATE` and `RUN_DATE`; a valuation exists from its trade's
booking day; a netting set's `TRADE_IDS` lists the trades booked by that day.

**Files.** One document a line, gzip, sorted by key: `snapshot-<first day>.jsonl.gz`, then `changes-<day>.jsonl.gz` for each later day
(the documents new or different that day, whole; an empty file when nothing changed). A file that would pass 9 MB is split into
`.part-001`, `.part-002` … (`--part-mb`). A day's state is the snapshot with every change up to that day applied by key.

## The manual way: one dataset at a time

`lake demo` is `lake build` run for every dataset and day. By hand, build a day's state into the table its connector reads (the
manifest names the pack, kind, connector, key and partitions of each dataset); the first day's state is its snapshot:

```bash
# the first day of the ENDUR trades, into the table the trade-endur connector reads (trading/trade_endur)
drishti-console/.venv/bin/python tools/drishti.py lake build --from data/testdata/trades/endur/snapshot-2026-09-22.jsonl.gz \
    --pack config/packs/trading --kind trade --connector trade-endur --key TRADE_ID --as-of 2026-09-22 \
    --partition-by PRODUCT_TYPE --lake data/delta

# a kind of another pack (core/counterparty: connector reference-store, domain reference)
drishti-console/.venv/bin/python tools/drishti.py lake build --from data/testdata/core/counterparty/snapshot-2026-09-22.jsonl.gz \
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
python3 tools/testdata/make_testdata.py           # about 20 s; rewrites the dataset folders and testdata.yaml (this README stays)
python3 tools/testdata/make_testdata.py --check   # fails when the files differ from what would be written
python3 -m unittest -q tools/testdata/test_testdata.py
```

The generator builds on the banking generators (`tools/packgen/banking`, `tools/packgen/trades`): change those, regenerate the
samples, then the test data. The world-economy and news-and-markets datasets are those packs' samples; the world-economy figures
include IMF series, so they are for internal demonstrations only.
