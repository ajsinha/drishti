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

Hands-on, one worked example each. Every tutorial uses the demo data that ships with Drishti: the **demo lakes** that `drishti.py lake demo` builds from
[data/testdata](../../data/testdata/README.md) (sixty business days of trades from three booking systems, ENDUR, CALYPSO and MUREX, with their counterparties,
netting sets, market data and risk), the public data of the world-economy pack, and a few small files in [data/](data/). Every field is UPPER_SNAKE (`TRADE_ID`,
`COUNTERPARTY`, `QUANTITY`), as production data is. Each step says what to do **in the UI**, gives the real command and its real output **from the command line** wherever a
command exists, shows the YAML it produces, shows a real screenshot, and tells you what you should see. Each ends with next steps and what to do when something goes wrong.

They are written for someone who has never used Drishti. The reference documents ([USER_GUIDE.md](../guides/USER_GUIDE.md), [CLI_GUIDE.md](../guides/CLI_GUIDE.md) and the rest of [guides/](../guides/)) say everything;
the tutorials say what to do first.

## The tutorials

The tutorials are grouped in tracks; inside a track, read them in the order listed. A tutorial's number is its name, not its place: it stays the same when tutorials are added or merged, so links and pictures keep working.


### Start

For everyone: sign in, find anything, share it. Begin with tutorial 1.

| # | Tutorial | You will | Time | Status |
|---|---|---|---|---|
| 1 | [Your first 15 minutes](01-first-15-minutes.md) | install, build the demo lakes, start, sign in, open a trade, search, try the form, look at BI, make a token | 15 min | written |
| 2 | [Look up anything](02-look-up-anything.md) | use mnemonics, type-ahead, links, a past date against Live, Compare | 20 min | written |
| 3 | [Query by form](03-query-by-form.md) | combine criteria, regular expressions, amounts, dates and checkbox dropdowns; save a search and a monitor | 20 min | written |
| 21 | [An entity over time](21-an-entity-over-time.md) | a range after an id (`TRD 1000015 30`, `3M`, `YTD`, `FROM … TO`), the history table with what moved tinted and restated days marked, changes only and DIFF, opening a day as it was, an equity's chart and headline numbers, the `history:` block of a pack, export and a link that pins the dates, the limits and errors; UI, `drishti.py history` and the YAML at every step | 30 min | written |
| 8 | [Share and collaborate](08-share-and-collaborate.md) | share a filtered view, discuss it, mention a colleague, read the inbox | 25 min | written |
| 9 | [Reports](09-reports.md) | schedule a search, subscribe a colleague, read the history, use the Reports menu | 25 min | written |

### Risk engines

VaR, exposure, FRTB-SA and SIMM, each from the bank (or the hand-built cases) down to a trade, with its playground.

| # | Tutorial | You will | Time | Status |
|---|---|---|---|---|
| 25 | [VaR from the bank to a trade](25-var-from-the-bank-to-a-trade.md) | build the VaR test data into a lake, open the bank's VaR and drill to a trade in place, read the P&L path and contributions that add up, write scopes (a desk minus a book plus a portfolio, a rule), see positions derived by the pack's rules, save a basket, capture and compare it, read the history and backtest, see the date's memory-mapped hot file, read VaR as a kind (`VRES`) and ask the Python engine what a hedge would do (`drishti.py var whatif`); UI, CLI and YAML at every step | 40 min | written |
| 43 | [Parametric VaR beside historical: the fat-tail gap](43-parametric-var-beside-historical.md) | the VaR explorer's Parametric toggle (`PVAR BANK` opens it on) and its three tiles, a desk's **?** read line by line (z·σ − μ with the desk's numbers), the normal curve over the P&L histogram and the N toggle, the tail bin by bin against the normal, the Euler components adding up to the unit and the correlation matrix, `pvar()` beside `var()` in the MR cube, the VaR Playground's Parametric tab and its workbook; UI, CLI and JSON or YAML at every step | 25 min | written |
| 38 | [The MR cube: desks by risk class, with the right totals](38-the-mr-cube-on-the-pivot-engine.md) | the landing (`MCUBE <GO>`): desks by risk class with totals checked against `VAR BANK` and the diversification; a cell's tail, provenance and why it moved; the lens (10 days, 97.5%, stressed, a currency, a scope); a re-pivot by currency and a desk opened down to its trades; tab B's components and waterfall; tab C's DV01 ladder and a what-if side by side; a view saved, shared and exported to Excel; UI, CLI and JSON at every step | 25 min | written |
| 26 | [Exposure from the bank to a trade](26-exposure-from-the-bank-to-a-trade.md) | build the demo lake's exposure run, open the bank's exposure profile, a legal entity's counterparty board with limit use, CP-ALDERSHOT and its netting sets with `+ - &` scopes, NS-ALDERSHOT-LDN's collateral (posted collateral raising early exposure) and trades, a trade alone against its contribution in its netting set, a capture compared with now, a pre-deal unwind by the exact `paths` method (the Python engine, `XWIF`) and by the `addon` proxy, and the official end-of-day record; UI, CLI and YAML at every step | 35 min | written |
| 30 | [FRTB-SA from the hand-built cases to a desk](30-frtb-sa-from-the-cases-to-a-desk.md) | the capital of the design's hand-built cases (`FRSA EXAMPLE`) and one bucket checked by hand, the three correlation scenarios and the binding one, the bucket drill, the playground (`PLAY FRTB`): every method choice a switch, a switch compared side by side with the difference explained by step, the workbook of live formulas recomputed in a spreadsheet with a switch flipped in Config, a recipe saved and replayed to the cent (`PLAY FRTB RUN <id>`), and the rates desk of the demo data with its contributions; UI, CLI and YAML at every step | 25 min | written |
| 32 | [SIMM from the hand-built examples to a netting set](32-simm-from-the-examples-to-a-netting-set.md) | install a SIMM parameter version (the toy file of the tests, not ISDA's) and see the shipped empty structure refused, the hand-built examples (`SIMM EXAMPLE`) checked by hand, a demo netting set to collect and post with its concentration factors, the playground (`PLAY SIMM`): a switch compared in words, the workbook of live formulas with the side and the switches flipped in Config, a recipe replayed to the cent (`PLAY SIMM RUN <id>`), the margin against the counterparty's call; UI, CLI and YAML at every step | 25 min | written |

### BI and analysis

Reports and models, the pivot engine, Python, AI, and the computed analytics of the public-data packs.

| # | Tutorial | You will | Time | Status |
|---|---|---|---|---|
| 44 | [Virtual tables and models](44-virtual-tables-and-models.md) | make trade legs a virtual table (with a column in dollars and a desk looked up), put a model over it, and migrate BI's samples to the table layer with a dry run first | 30 min | written |
| 10 | [Your first BI report](10-first-bi-report.md) | in BI Studio, build a report on a dataset (cards, a map you drill from world to country to region, a bar, a slicer), cross-filter it, publish it; filter like Power BI; the Counterparty risk sample; a relative-date slicer, the entity view, a tablet layout | 45 min | written |
| 11 | [BI that runs itself](11-bi-that-runs-itself.md) | a dataset that refreshes itself, a subscription (and what Run now says with no mail server, and how to set mail up), an alert on a number, export to PDF, PNG, Excel and CSV; expected values; charts in the workbook, a narrative summary, a webhook and a text | 40 min | written |
| 12 | [Live BI and boards](12-live-bi-and-boards.md) | live data that moves together, a live table, a report that follows it (pause and resume), the Desk wall in kiosk mode; the history of a live table, your own board in the report designer (BI Studio), a chart of the desk by currency (the server groups, the browser draws) and a wall of Python | 30 min | written |
| 13 | [Govern BI](13-govern-bi.md) | history and diff, review with four-eyes, certify, lineage, the refusal of a breaking change, an environments bundle with a dry run | 45 min | written |
| 18 | [Several datasets in one report](18-several-datasets-in-one-report.md) | one model with two facts (trade valuations and netting sets) built in the model designer, MTM and exposure in one visual, why rows are never joined across facts (the inflated total) and what the model refuses, a measure over both facts, the same as a Python Transform, two readers with different rules, the join in Analyse, and a Power BI comparison | 60 min | written |
| 20 | [Statements and field parameters](20-statements-and-field-parameters.md) | a model with field parameters of fields and of measures, a chart the reader switches from DESK to PORTFOLIO and from MTM to NOTIONAL (Build and the parameter slicer), a statement page with its paper, header and footer, page breaks, repeated column titles and groups kept together, previewed page by page in the Paper tab, the PDF and Excel from `drishti.py bi statement`, and one statement per person from one subscription | 40 min | written |
| 14 | [Python and Analyse](14-python-and-analyse.md) | a Python measure and a Python visual, then Analyse: slice, SQL, a Python cell, a notebook, promote; two datasets, Vega-Lite, tests that ship with an item, where a SQL cell runs, Calc's `drishti_bi` and Graphic Walker; raw trades prepared step by step in the Transform editor and your own Python in the browser's Python console | 70 min | written |
| 15 | [AI in Drishti](15-ai-in-drishti.md) | turn on the mock provider safely, the AI policy, report from words, ask the data, explain this number, the designer's suggestions and plain-words checks, the dataset helper, Test connection; the provider types | 30 min | written |
| 33 | [The pivot engine: trades by product, legs by desk, then VaR by desk](33-the-pivot-engine.md) | a first pivot of the demo trades (`PIVOT trade`), a row opened lazily and another expanded by a different field, the top N with its Others checked by hand, a cell's provenance and rows, the trades' legs pivoted with a document path (each trade's quantity counted once), VaR by desk whose total is not the sum of the desks (`PIVOT var`), a chart that never stacks VaR, a view saved, shared and exported to Excel with its outline and formulas, the switches Exact / Auto / ≈, rollups and Live, a comment on a cell; UI, CLI and JSON or YAML at every step | 30 min | written |
| 37 | [Custom functions, "why did it move" and what-if](37-custom-functions-and-explain.md) | a formula in a view, a function in the playground proven by the harness (split-and-merge equals one pass), an SQL macro rewritten into states or holistic, saved, approved by someone else, used and withdrawn, the difference between two products in steps that add up, a what-if beside the original, Python on, BI's totals from the engine; UI, CLI and JSON or YAML at every step | 25 min | written |
| 27 | [Groupings and analytics, computed](27-groupings-and-analytics.md) | an ownership group computed from its legal entities with no member cap (its tree, levels and the GLEIF size), every group and the type-ahead by name, a country with every one of its entities, a SIC sector whose totals add the USD reporters only, a company's net margin and revenue growth worked out on the page, a World Bank region from its economies (GDP-weighted growth, median inflation), a factor set's correlations from price history with a window and an estimator (Pearson, Ledoit-Wolf), an equity's beta against its index from both price histories, PXH's analytics off as shipped and internal only, and the guard that keeps the stored copies out; UI, CLI and YAML at every step | 25 min | written |
| 28 | [Funds and ETFs](28-funds-and-etfs.md) | turn the funds pack on, read a fund's fact sheet (size, cost, concentration, returns, flows, service providers), look through it to every holding, its derivatives and counterparties, set it against its index with computed weights and active share, find funds by share class ticker, index or any field, see every fund holding a company (HELD, and USEQ's Held by funds tab) and the funds tracking an index (TRACK, and IDX), refresh the SEC data on Linux and Windows; UI, CLI and YAML at every step | 25 min | written |

### Data in and out

Connect a source, load files every day, configure lakes, hold a feed in memory only, and take data out to any client.

| # | Tutorial | You will | Time | Status |
|---|---|---|---|---|
| 4 | [Connect your data](04-connect-your-data.md) | write a connector file, test it, handle TLS, a proxy, retries and the connection pool | 30 min | written |
| 7 | [Load data daily](07-load-data-daily.md) | send the data-load signal, set expectations, see late data, alert on a missing load | 30 min | written |
| 36 | [Load files automatically with the data inbox](36-load-files-with-the-data-inbox.md) | turn the data inbox on (`config/engines/dataload.yaml`, Admin → Engines), route a file by a rule in `config/dataload.yaml`, drop a day of Endur trades with its `.ready` marker and watch it on Admin → Data loads → Inbox from waiting to loaded (`drishti.py dataload status`, `run`), release two files with `ready.all`, a file no rule matches failed, the rule added and the file retried (`drishti.py dataload retry`), a duplicate skipped, the outbox reports and the ledger; UI, CLI and YAML at every step | 20 min | written |
| 34 | [One file per lake](34-one-file-per-lake.md) | the demo lake's file, a connector naming it, a load through it and a contradicting `--partition-by` refused, an append table loading one batch once, `lake check`, migrating an older connector file | 15 min | written |
| 29 | [The risk lake and the official results on Iceberg](29-var-on-iceberg.md) | move the VaR inputs from Delta to Iceberg with `build_demo_lakes.py --store risk=iceberg`, publish the official results to Iceberg (a table keeps its format), see VaR and the limit letters equal Delta's to the cent, each table's Format in Admin → Lakes, BI's `var-results` reader following the format (Reset to pack default for a file `lake demo` wrote), and go back to Delta; UI, CLI and YAML at every step | 20 min | written |
| 31 | [News held in memory only](31-news-in-memory-only.md) | move the news-and-markets pack into the server's memory (`root: mem://news`), define the store and its four tables, watch the replenish jobs fill them (`drishti.py lake memory status`, `jobs`, `refill`), read the pages and download as before, see a scheduled report refused (`DRS-5219`), the cap, eviction and Health, and go back to the disk lake; UI, CLI and YAML at every step | 15 min | written |
| 35 | [Data out: from a token to a pandas DataFrame of a desk's VaR scenario P&L](35-data-out-with-arrow-flight.md) | Arrow Flight turned on, a token with `data:read`, a desk's VaR scenario P&L into pandas and its positions' vectors, the same from Java and JDBC, a live intraday stream resumed, Admin → Data out with Stop; UI, CLI and code at every step | 20 min | written |

### Build

Packs, screens, computed panels, forms that write back, and Drishti inside your own applications.

| # | Tutorial | You will | Time | Status |
|---|---|---|---|---|
| 5 | [Build a pack from your data](05-build-a-pack.md) | turn a schema and a folder into a pack, deploy it, roll it back, do it with `pack make` | 30 min | written |
| 6 | [Design a screen: in the workbench and on a live page](06-design-a-screen.md) | Part A, the Build workbench: design panels from your own documents, tabs that hold panels, a slider, column filters, the About text and a map, checked by `sutra lint`; Part B, the Sutra designer on a live book page: Customize, a linked record on a click (another pack's credit limits), refresh every 30 s with pause and freshness, let people edit beside the table and in a New drawer, your page and Reset, the same screen from one Describe line, Outline and Grid | 75 min | written |
| 23 | [Computed panels: a reactive connector](23-computed-panels.md) | switch on the compute workers and the compute-demo sample pack, a book's P&L path computed by a Python engine over a pool's workers and by its Java twin, panels drawn computing with progress and filled in place, typed parameters on a panel's source, the same computation from `drishti.py compute run`, a parameter refused in words, a write-back event that recomputes what a screen watches; UI, CLI and YAML at every step | 25 min | written |
| 24 | [Correct a trade and approve it: write-back](24-correct-a-trade-and-approve-it.md) | switch editing on, the book desk's form that submits (`save:`), correct a trade's notional, the review and confirm sheet (what you saw, what is there now, your change, where it goes, who approves, what it recomputes), four eyes in Review → Changes, the change applied and shown to a second browser, *Edited here* in How this view was built, a stale change conflicted, `drishti.py change`; UI, CLI and YAML at every step | 20 min | written |
| 16 | [Embed Drishti](16-embed-drishti.md) | register a host application, show a view and a report in its page under a strict CSP, tokens, limits; search and About elements, React, Vue and Angular samples | 40 min | written |

### Operate

Production settings, backups, health and upgrades; several servers as one.

| # | Tutorial | You will | Time | Status |
|---|---|---|---|---|
| 17 | [Run it in production](17-run-it-in-production.md) | security on, your own administrator, roles and BI powers, tokens, backups, heap and connection pools, health, the type-ahead index and its reload, temp folders, upgrades, taking a pack, connector or BI file out safely | 60 min | written |
| 19 | [A cluster of three (Drishti\*net)](19-a-cluster-of-three.md) | write `config/net/` with the CLI and the UI, start three servers from one seed, open a trade only one holds through another, Explain the route, nodes per role, a console with several servers, drain a node, stop one and carry on, saved searches that follow you | 45 min | written |

### Trader: your first 10 minutes

A path through steps of the tutorials above, for someone who trades or watches a desk, on a server someone else runs (with the VaR data built: tutorial 25,
step 1).

1. Sign in and find your way around: [tutorial 1, steps 3 and 4](01-first-15-minutes.md#step-3--sign-in).
2. Open a trade, follow its links, and look at another day: [tutorial 2, steps 3 to 6](02-look-up-anything.md#step-3--open-one-entity-or-pick-from-many).
3. See what changed on a trade over the last 30 days: [tutorial 21, steps 1 to 3](21-an-entity-over-time.md#step-1--a-range-after-the-id).
4. Your desk's VaR, down to a trade, and who makes it: [tutorial 25, steps 2 to 4](25-var-from-the-bank-to-a-trade.md#step-2--drill-down-from-the-bank-to-a-trade).
5. What a hedge would do before you deal: [tutorial 25, step 11](25-var-from-the-bank-to-a-trade.md#step-11--what-would-a-hedge-do-the-what-if).
6. Your trades by product, as a pivot: [tutorial 33, steps 1 and 2](33-the-pivot-engine.md#step-1--a-first-pivot-of-the-trades).

## What you need once

Tutorials 1 to 3 need only [tutorial 1](01-first-15-minutes.md): Java 21 or newer, Python 3.13 ([the minimum](../guides/GETTING_STARTED.md#step-1--install-the-prerequisites)), `uv`, git, the demo lakes built (`lake demo`, about 15 minutes), and
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

The pictures are made, not drawn: `tools/docs/screenshots.py --guide tNN` (one script per tutorial, `tools/docs/shots/tNN.py`) drives a scratch stack with a real browser and writes them to `img/tNN/`. Some build what they need first: `t25` its own VaR lake, `t29` an Iceberg risk lake, `t30` and `t32` a lake with the sensitivities and the toy SIMM file, `t31` a server with the news held in memory. The pictures of tutorials 20 and 21 were captured separately, those of tutorial 6's part B are the Rachana reference's own (`docs/guides/img/designer/`), those of tutorial 23 are made by `drishti-console/tests/test_reactive_panels_browser.py`. When the screens change, run it again.

## The sample data

| File or folder | What it is | Used by |
|---|---|---|
| `data/desk-quotes/<date>/desk-quote.jsonl` | ten business days of six desk quotes each (kind `desk-quote`: `quote` belongs to the news-and-markets pack) | tutorials 4 and 5 |
| `data/schemas/desk-quote.schema.json` | the JSON Schema of a desk quote | tutorial 5 |
| `data/connectors/` | a file connector, a PostgreSQL connector aimed at a port where nothing listens, and a file Drishti refuses | tutorial 4 |
| `data/desks/*.json`, `data/desk-book.sutra.yaml` | three desks with currencies and trades, and the screen designed for them | tutorial 6, part A |
| `data/slicer-kinds.report.yaml` | a report with a list, a dropdown and a range slicer and a top-N donut | tutorial 10 |
| `data/trades-daily.dataset.yaml`, `data/trades-daily.model.yaml`, `data/desk-check.report.yaml`, `data/smtp-catcher.py` | a dataset that refreshes itself every business morning, the model over it, a report that says what its numbers must be; a mail catcher for trying subscriptions and alerts | tutorial 11 |
| `data/live-desk.mq.yaml`, `data/night-desk.report.yaml` | a model query over the live table, with its SQL; a board of three live tiles (a report with a board page) | tutorial 12 |
| `data/rates-trades.dataset.yaml`, `data/trades-model.model.yaml`, `data/trades-report.report.yaml` | a dataset, the model over it and the report on the model, to review, certify and change | tutorial 13 |
| `data/notional-by-desk.python.yaml`, `data/desk-python.report.yaml` | a Python visual and a report that uses it | tutorial 14 |
| `data/endur-trades-cleaned.python.yaml`, `data/endur-trades-cleaned.dataset.yaml` | the Python item the Transform editor writes for ENDUR trades, cleaned, and its dataset | tutorial 14 |
| `data/ai/mock.yaml`, `data/ai/local-model.yaml` | the mock AI provider with rules for the tutorials, and a provider with nothing listening | tutorial 15 |
| `data/pools.py` | the connection-pool sum | tutorial 17 |
| `data/cp-risk.model.yaml`, `data/cp-risk-view.report.yaml`, `data/cp-table.python.yaml`, `data/cp-table.dataset.yaml`, `data/cp-risk-extracts.notebook.yaml` | one model with two facts, a report on it, a Python Transform and its dataset over two model queries, and the Analyse notebook of the join trap | tutorial 18 |
| `data/desk-params.model.yaml`, `data/monthly-statement.report.yaml` | a model with two field parameters and a statement on it | tutorial 20 |

Everything else is the demo lakes (`data/delta`, built by `lake demo`) and the packs you already have. The `world-economy` pack holds published data from the World Bank, UNDP, the CIA World Factbook and the IMF
([EXTERNAL_DATA.md (world-economy)](../guides/EXTERNAL_DATA.md#the-world-economy-data-in-detail)); the IMF part is for internal demonstration only, so the tutorials use the world-economy pack.
The us-equities and gleif packs (tutorial 27) hold published data from SEC EDGAR, Wikidata, the Nasdaq Trader directory and GLEIF, and the
news-and-markets pack a captured snapshot of Yahoo Finance and Google News (internal only). Where every external source comes from, its
licence and the script that refreshes it, with examples: [External data](../guides/EXTERNAL_DATA.md).
