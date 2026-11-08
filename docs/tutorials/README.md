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
# Drishti tutorials

Hands-on, one worked example per major feature. Every tutorial uses the demo data that ships with Drishti: the **demo lakes** that `drishti.py lake demo` builds from
[data/testdata](../../data/testdata/README.md) (sixty business days of trades from three booking systems, ENDUR, CALYPSO and MUREX, with their counterparties,
netting sets, market data and risk), the public data of the world packs, and a few small files in [data/](data/). Every field is UPPER_SNAKE (`TRADE_ID`,
`COUNTERPARTY`, `QUANTITY`), as production data is. Each step says what to do **in the UI**, gives the real command and its real output **from the command line** wherever a
command exists, shows the YAML it produces, shows a real screenshot, and tells you what you should see. Each ends with next steps and what to do when something goes wrong.
A tutorial that covers several related tasks is in parts (Part A, Part B ...); each part can be done on its own day, and its steps are numbered on from the part before.

They are written for someone who has never used Drishti. The reference documents ([USER_GUIDE.md](../guides/USER_GUIDE.md), [CLI_GUIDE.md](../guides/CLI_GUIDE.md) and the rest of [guides/](../guides/)) say everything;
the tutorials say what to do first.

## The tutorials

The tutorials are grouped in tracks, numbered in track order; inside a track, read them in the order listed.

### Start

For everyone: sign in, find anything, share it, see an entity over time. Begin here.

| # | Tutorial | You will | Time | Status |
|---|---|---|---|---|
| 1 | [Your first 15 minutes](01-first-15-minutes.md) | Part A: install, build the demo lakes, start, sign in, open a trade, search, try the form, look at BI, make a token; Part B: mnemonics, type-ahead, links, a past date against Live, Compare; Part C: a search by form with regular expressions, amounts, dates and checkbox dropdowns, saved as a search and a monitor; Part D: share a filtered view, discuss it, mention a colleague, read the inbox; Part E: an entity over time (`TRD 1000015 30`, `3M`, `YTD`), changes only and DIFF, a day as it was, an equity's chart, a pack's `history:` block | 1 h 50 min | written |

### Risk engines

VaR, exposure, FRTB-SA and SIMM, each from the bank (or the hand-built cases) down to a trade, with its playground; the computed groupings and analytics.

| # | Tutorial | You will | Time | Status |
|---|---|---|---|---|
| 2 | [VaR from the bank to a trade](02-var-from-the-bank-to-a-trade.md) | Part A: build the VaR test data, open the bank's VaR and drill to a trade, the P&L path and contributions, scopes, derived positions, baskets, captures and compare, history and backtest, the hot file, VaR as a kind, the what-if, intraday, limit letters, ten days, the scenario matrix, the regulatory backtest, the VaR Playground; Part B: parametric VaR beside historical and the fat-tail gap; Part C: the same VaR on Iceberg | 1 h 30 min | written |
| 3 | [Exposure from the bank to a trade](03-exposure-from-the-bank-to-a-trade.md) | the bank's exposure profile, a legal entity's counterparty board, a counterparty and its netting sets, collateral, a trade alone and in its netting set, capture and compare, the pre-deal check by `paths` and by the `addon` proxy, the official end-of-day record | 35 min | written |
| 4 | [FRTB-SA from the hand-built cases to a desk](04-frtb-sa.md) | the hand-built cases (`FRSA EXAMPLE`), the three correlation scenarios, the playground (`PLAY FRTB`) and its workbook of live formulas, a recipe replayed to the cent, a desk of the demo data | 25 min | written |
| 5 | [SIMM from the hand-built examples to a netting set](05-simm.md) | install a parameter version, the hand-built examples (`SIMM EXAMPLE`), a netting set both ways, the playground (`PLAY SIMM`) and its workbook, a recipe replayed, the margin against the counterparty's call | 25 min | written |
| 6 | [Groupings and analytics, computed](06-groupings-and-analytics.md) | an ownership group and every group, a country, a SIC sector and the currency rule, a company's margin and growth, a World Bank region, correlations with a window and an estimator, an equity's beta, PXH off and internal, the guard | 25 min | written |

### BI and analysis

The pivot engine, reports and dashboards, the Notebook and Python, the AI assistant, and corrections with four eyes.

| # | Tutorial | You will | Time | Status |
|---|---|---|---|---|
| 7 | [Pivot, cubes and explain](07-pivot-cubes-and-explain.md) | Part A: pivot the trades, their legs and VaR by desk with totals that are never sums, provenance, a chart, Excel with formulas, Exact / ≈, rollups and Live, a comment on a cell; Part B: a custom function proven by its harness and approved, why did it move, what-if; Part C: the MR cube (`MCUBE`), its lens, components, the DV01 ladder | 1 h 20 min | written |
| 8 | [Reports and dashboards](08-reports-and-dashboards.md) | Part A: a first report in BI Studio, published, filtered, on a phone, two facts, relative dates, models; Part B: several datasets in one report; Part C: statements and field parameters; Part D: refresh, subscriptions, alerts, exports, expected values; Part E: scheduled searches and subscriptions to a view; Part F: live BI and boards for a wall; Part G: governance (history, review, certification, lineage, a bundle moved with a dry run) | 5 h 10 min | written |
| 9 | [The Notebook and Python](09-notebook-and-python.md) | a Python measure and visual, the Notebook (SQL on the server, a Python cell, promote), two models, Vega-Lite, tests that ship with an item, Calc, raw trades prepared step by step in the Transform editor | 60 min | written |
| 10 | [The AI assistant](10-ai-assistant.md) | the mock provider turned on safely, Test connection, the AI policy, report from words, ask the data and explain a number, the policy saying no, the providers, suggestions and the dataset helper | 30 min | written |
| 11 | [Correct a trade and approve it: write-back](11-correct-and-approve.md) | editing switched on, a form that submits, a notional corrected and reviewed, four eyes in the Approvals queue, the change applied everywhere, a stale change conflicted, `drishti.py change` | 20 min | written |

### Data in and out

A day of bank data, the world's public data, connecting a source, a lake of your own, and data out to any client.

| # | Tutorial | You will | Time | Status |
|---|---|---|---|---|
| 12 | [A day of bank data: load it daily](12-a-day-of-bank-data.md) | expectations, the data-load signal from the command line and the API, a failed batch, the history and what is due, an alert for a missing day, a day taken back out | 30 min | written |
| 13 | [World data: economies, companies, funds, entities and news](13-world-data.md) | Part A: a counterparty's economy, its outlook, its peers and its government; Part B: a US company and its five identifiers; Part C: funds and ETFs (fact sheet, look-through, against the index, `HELD`, `TRACK`, refresh); Part D: who owns whom from GLEIF; Part E: a listed company's quote and headlines | 1 h 10 min | written |
| 14 | [Public sector data and free news](14-public-sector-data.md) | the six feeds (World Bank, FRED and the Bank of Canada, Statistics Canada, OECD, official press releases, GDELT), a country's development, a central bank, national statistics, official news, world events, the feeds scheduled daily | 30 min | written |
| 15 | [Connect your data](15-connect-your-data.md) | a file connector in Admin → Connectors and from the command line, no password in a connector, TLS, a proxy, a pool, Test and retry | 30 min | written |
| 16 | [Load your own data into a new lake](16-load-your-own-data-into-a-new-lake.md) | Part A: a new lake (`config/lakes/risklake.yaml`), routed, loaded with `.ready` files, the connectors pointed at it, a load undone; Part B: one file per lake, append tables, `lake check`; Part C: the data inbox; Part D: news held in memory only; Part E: the risk lake and the official results on Iceberg | 1 h 45 min | written |
| 17 | [Data out: from a token to a pandas DataFrame of a desk's VaR scenario P&L](17-data-out-with-arrow-flight.md) | Arrow Flight on, a token with `data:read`, a desk's scenario P&L in pandas, the same in Java and through JDBC, a live stream resumed, Admin → Data out | 20 min | written |

### Build

Tables and models, packs and screens, and Drishti inside your own applications.

| # | Tutorial | You will | Time | Status |
|---|---|---|---|---|
| 18 | [Virtual tables and models](18-tables-and-models.md) | trade legs as a table, its rows over samples, a column in dollars and a looked-up desk, rules per reader, a model over it, BI's samples migrated with a dry run | 30 min | written |
| 19 | [Build a pack and a screen](19-build-a-pack-and-a-screen.md) | Part A: a pack from a schema and a folder, deployed and rolled back, `pack make`; Part B: a screen in the Build workbench and on a live page with the Sutra designer (Customize, Describe, Outline, Grid); Part C: computed panels on a reactive connector | 2 h 10 min | written |
| 20 | [Embed Drishti](20-embed-drishti.md) | a host application registered, a view and a report in its page under a strict CSP, tokens and limits, search and About elements, React, Vue and Angular samples | 40 min | written |

### Operate

Production settings, backups, health and upgrades; several servers as one.

| # | Tutorial | You will | Time | Status |
|---|---|---|---|---|
| 21 | [Run it in production](21-run-it-in-production.md) | security on, your own administrator, roles and BI powers, tokens, backups, heap and pools, health, upgrades and roll back, taking something out safely | 60 min | written |
| 22 | [A cluster of three (Drishti\*net)](22-a-cluster-of-three.md) | `config/net/` with the CLI and the UI, three servers from one seed, a trade only one holds, a console with several servers, who may reach which node, drain and stop a node | 45 min | written |

### Trader: your first 10 minutes

A path through steps of the tutorials above, for someone who trades or watches a desk, on a server someone else runs (with the VaR data built: tutorial 2,
step 1).

1. Sign in and find your way around: [tutorial 1, steps 3 and 4](01-first-15-minutes.md#step-3--sign-in).
2. Open a trade, follow its links, and look at another day: [tutorial 1, steps 12 to 15](01-first-15-minutes.md#step-12--open-one-entity-or-pick-from-many).
3. See what changed on a trade over the last 30 days: [tutorial 1, steps 27 to 29](01-first-15-minutes.md#step-27--a-range-after-the-id).
4. Your desk's VaR, down to a trade, and who makes it: [tutorial 2, steps 2 to 4](02-var-from-the-bank-to-a-trade.md#step-2--drill-down-from-the-bank-to-a-trade).
5. What a hedge would do before you deal: [tutorial 2, step 11](02-var-from-the-bank-to-a-trade.md#step-11--what-would-a-hedge-do-the-what-if).
6. Your trades by product, as a pivot: [tutorial 7, steps 1 and 2](07-pivot-cubes-and-explain.md#step-1--a-first-pivot-of-the-trades).

## What you need once

Every tutorial needs only [tutorial 1, Part A](01-first-15-minutes.md#part-a--install-start-and-look-around): Java 21 or newer, Python 3.13 ([the minimum](../guides/GETTING_STARTED.md#step-1--install-the-prerequisites)), `uv`, git, the demo lakes built (`lake demo`, about 15 minutes), and
the server and console started with sign-in on: the quick-start environment of [data/testdata/README.md](../../data/testdata/README.md), which the IDE run configurations
**Build demo lakes**, **Drishti server (demo lakes)** and **Drishti console** set up in three clicks. Later tutorials say what else they need. For the command line you need a
personal API token (tutorial 1, Step 9) in `DRISHTI_TOKEN`.

Run commands from the repository root with the console's Python (`drishti-console/.venv/bin/python tools/drishti.py ...`). Where a tutorial shows output, it was captured from
a fresh clone on the seven-day demo lakes, built on Saturday 17 October 2026 (**2026-10-07 to 2026-10-16**), unless the tutorial says
otherwise. The lakes hold sixty business days (the test data's 2026-07-08 to 2026-09-30), so a captured count or value may differ on yours. `lake demo`
moves the days to end on *your* today's business date, so your dates differ; the ids (`MX-20000001`, `1000001`, `CLY-3000157`, `CP-NORTHPLAINS`) and the 5,000 trades per
booking system on the last day are the same on every machine. `MX-20000001` is cancelled part-way through the sixty days (its last seven days read `CANCELLED`): a
tutorial that shows it live was captured before that. For history, the trades amended most (ten times each) are ENDUR `1000279` and `1001049`, CALYPSO
`CLY-4000509` and MUREX `MX-30001745`.

The pictures are made, not drawn: `tools/docs/screenshots.py --guide tNN` (one script per picture folder, `tools/docs/shots/tNN.py`) drives a scratch stack with a real browser and writes them to `img/tNN/`. The folders keep the names of the pieces the tutorials were built from: tutorial 1 shows `t01`, `t02`, `t03`, `t08` and `t21`; tutorial 2 `t25`, `t43` and `t29`; tutorial 7 `t33`, `t37` and `t38`; tutorial 8 `t09` to `t13`, `t18` and `t20`; tutorial 13 `t28`; tutorial 16 `t45`, `t34`, `t36`, `t31` and `t29`; tutorial 19 `t05`, `t06` and `t23`; each other tutorial one folder. Some scripts build what they need first: `t25` its own VaR lake, `t29` an Iceberg risk lake, `t30` and `t32` a lake with the sensitivities and the toy SIMM file, `t31` a server with the news held in memory. The pictures of `t20` and `t21` were captured separately, those of tutorial 19's Sutra designer are the Rachana reference's own (`docs/guides/img/designer/`), those of `t23` are made by `drishti-console/tests/test_reactive_panels_browser.py`. When the screens change, run it again. A step marked *(screenshot filled in the heavy run: ...)* has its picture taken in the next screenshot run.

## The sample data

| File or folder | What it is | Used by |
|---|---|---|
| `data/desk-quotes/<date>/desk-quote.jsonl` | ten business days of six desk quotes each (kind `desk-quote`: `quote` belongs to the news-and-markets pack) | tutorials 15 and 19 |
| `data/schemas/desk-quote.schema.json` | the JSON Schema of a desk quote | tutorial 19, Part A |
| `data/connectors/` | a file connector, a PostgreSQL connector aimed at a port where nothing listens, and a file Drishti refuses | tutorial 15 |
| `data/desks/*.json`, `data/desk-book.sutra.yaml` | three desks with currencies and trades, and the screen designed for them | tutorial 19, Part B |
| `data/slicer-kinds.report.yaml` | a report with a list, a dropdown and a range slicer and a top-N donut | tutorial 8, Part A |
| `data/trades-daily.dataset.yaml`, `data/trades-daily.model.yaml`, `data/desk-check.report.yaml`, `data/smtp-catcher.py` | a dataset that refreshes itself every business morning, the model over it, a report that says what its numbers must be; a mail catcher for trying subscriptions and alerts | tutorial 8, Part D |
| `data/live-desk.mq.yaml`, `data/night-desk.report.yaml` | a model query over the live table, with its SQL; a board of three live tiles (a report with a board page) | tutorial 8, Part F |
| `data/rates-trades.dataset.yaml`, `data/trades-model.model.yaml`, `data/trades-report.report.yaml` | a dataset, the model over it and the report on the model, to review, certify and change | tutorial 8, Part G |
| `data/notional-by-desk.python.yaml`, `data/desk-python.report.yaml` | a Python visual and a report that uses it | tutorial 9 |
| `data/endur-trades-cleaned.python.yaml`, `data/endur-trades-cleaned.dataset.yaml` | the Python item the Transform editor writes for ENDUR trades, cleaned, and its dataset | tutorial 9 |
| `data/ai/mock.yaml`, `data/ai/local-model.yaml` | the mock AI provider with rules for the tutorials, and a provider with nothing listening | tutorial 10 |
| `data/pools.py` | the connection-pool sum | tutorial 21 |
| `data/cp-risk.model.yaml`, `data/cp-risk-view.report.yaml`, `data/cp-table.python.yaml`, `data/cp-table.dataset.yaml`, `data/cp-risk-extracts.notebook.yaml` | one model with two facts, a report on it, a Python Transform and its dataset over two model queries, and the notebook of the join trap | tutorial 8, Part B |
| `data/desk-params.model.yaml`, `data/monthly-statement.report.yaml` | a model with two field parameters and a statement on it | tutorial 8, Part C |
| `data/risklake/make_day.py` | a day of synthetic, Endur-style trades, positions' scenario PVs (509 scenarios) and VaR nodes, named by the load rules, with each file's rows and SHA-256 | tutorial 16, Part A |

Everything else is the demo lake (`data/delta/demo`, built by `lake demo`) and the packs you already have. The `world-economy` pack holds published data from the World Bank, UNDP, the CIA World Factbook and the IMF
([EXTERNAL_DATA.md (world-economy)](../guides/EXTERNAL_DATA.md#the-world-economy-data-in-detail)); the IMF part is for internal demonstration only, so the tutorials use the world-economy pack.
The us-equities, funds and gleif packs (tutorials 6 and 13) hold published data from SEC EDGAR, Wikidata, the Nasdaq Trader directory and GLEIF, and the
news-and-markets pack a captured snapshot of Yahoo Finance and Google News (internal only). Where every external source comes from, its
licence and the script that refreshes it, with examples: [External data](../guides/EXTERNAL_DATA.md).
