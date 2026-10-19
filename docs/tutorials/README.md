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
[data/testdata](../../data/testdata/README.md) (seven business days of trades from three booking systems, ENDUR, CALYPSO and MUREX, with their counterparties,
netting sets, market data and risk), the public data of the world-economy pack, and a few small files in [data/](data/). Every field is UPPER_SNAKE (`TRADE_ID`,
`COUNTERPARTY`, `QUANTITY`), as production data is. Each step says what to do **in the UI**, gives the real command and its real output **from the command line** wherever a
command exists, shows the YAML it produces, shows a real screenshot, and tells you what you should see. Each ends with next steps and what to do when something goes wrong.

They are written for someone who has never used Drishti. The reference documents ([USER_GUIDE.md](../guides/USER_GUIDE.md), [CLI_GUIDE.md](../guides/CLI_GUIDE.md) and the rest of [guides/](../guides/)) say everything;
the tutorials say what to do first.

## The tutorials

| # | Tutorial | You will | Time | Status |
|---|---|---|---|---|
| 1 | [Your first 15 minutes](01-first-15-minutes.md) | install, build the demo lakes, start, sign in, open a trade, search, try the form, look at BI, make a token | 15 min | written |
| 2 | [Look up anything](02-look-up-anything.md) | use mnemonics, type-ahead, links, a past date against Live, Compare | 20 min | written |
| 3 | [Query by form](03-query-by-form.md) | combine criteria, regular expressions, amounts, dates and checkbox dropdowns; save a search and a monitor | 20 min | written |
| 4 | [Connect your data](04-connect-your-data.md) | write a connector file, test it, handle TLS, a proxy, retries and the connection pool | 30 min | written |
| 5 | [Build a pack from your data](05-build-a-pack.md) | turn a schema and a folder into a pack, deploy it, roll it back, do it with `pack make` | 30 min | written |
| 6 | [Design a screen](06-design-a-screen.md) | design panels, tabs that hold panels, a slider, column filters and the About text | 40 min | written |
| 7 | [Load data daily](07-load-data-daily.md) | send the data-load signal, set expectations, see late data, alert on a missing load | 30 min | written |
| 8 | [Share and collaborate](08-share-and-collaborate.md) | share a filtered view, discuss it, mention a colleague, read the inbox | 25 min | written |
| 9 | [Reports](09-reports.md) | schedule a search, subscribe a colleague, read the history, use the Reports menu | 25 min | written |
| 10 | [Your first BI report](10-first-bi-report.md) | build a report on a dataset (cards, a map you drill from world to country to region, a bar, a slicer), cross-filter it, publish it; filter like Power BI; the Counterparty risk sample; a relative-date slicer, the entity view, a tablet layout | 45 min | written |
| 11 | [BI that runs itself](11-bi-that-runs-itself.md) | a dataset that refreshes itself, a subscription (and what Run now says with no mail server, and how to set mail up), an alert on a number, export to PDF, PNG, Excel and CSV; expected values; charts in the workbook, a narrative summary, a webhook and a text | 40 min | written |
| 12 | [Live BI and boards](12-live-bi-and-boards.md) | live data that moves together, a live table, a report that follows it (pause and resume), the Desk wall in kiosk mode; the history of a live table, your own board in the board designer, a chart of the desk by currency (the server groups, the browser draws) and a wall of Python | 30 min | written |
| 13 | [Govern BI](13-govern-bi.md) | history and diff, review with four-eyes, certify, lineage, the refusal of a breaking change, an environments bundle with a dry run | 45 min | written |
| 14 | [Python and Analyse](14-python-and-analyse.md) | a Python measure and a Python visual, then Analyse: slice, SQL, a Python cell, a notebook, promote; two datasets, Vega-Lite, tests that ship with an item, Analyse in the browser, Calc's `drishti_bi` and Graphic Walker | 40 min | written |
| 15 | [AI in Drishti](15-ai-in-drishti.md) | turn on the mock provider safely, the AI policy, report from words, ask the data, explain this number, the designer's suggestions and plain-words checks, the dataset helper, Test connection; the provider types | 30 min | written |
| 16 | [Embed Drishti](16-embed-drishti.md) | register a host application, show a view and a report in its page under a strict CSP, tokens, limits; search and About elements, React, Vue and Angular samples | 40 min | written |
| 17 | [Run it in production](17-run-it-in-production.md) | security on, your own administrator, roles and BI powers, tokens, backups, heap and connection pools, health, the type-ahead index and its reload, temp folders, upgrades, taking a pack, connector or BI file out safely | 60 min | written |
| 18 | [Several datasets in one report](18-several-datasets-in-one-report.md) | one model with two facts (trade valuations and netting sets) built in the model designer, MTM and exposure in one visual, why rows are never joined across facts (the inflated total) and what the model refuses, a measure over both facts, the same as a Python Transform, two readers with different rules, the join in Analyse, and a Power BI comparison | 60 min | written |
| 19 | [A cluster of three (Drishti\*net)](19-a-cluster-of-three.md) | write `config/net/` with the CLI and the UI, start three servers from one seed, open a trade only one holds through another, Explain the route, nodes per role, a console with several servers, drain a node, stop one and carry on, saved searches that follow you | 45 min | written |

## What you need once

Tutorials 1 to 3 need only [tutorial 1](01-first-15-minutes.md): Java 21 or newer, Python 3.11 or newer, `uv`, git, the demo lakes built (`lake demo`, about a minute), and
the server and console started with sign-in on: the quick-start environment of [data/testdata/README.md](../../data/testdata/README.md), which the IDE run configurations
**Build demo lakes**, **Drishti server (demo lakes)** and **Drishti console** set up in three clicks. Later tutorials say what else they need. For the command line you need a
personal API token (tutorial 1, Step 9) in `DRISHTI_TOKEN`.

Run commands from the repository root with the console's Python (`drishti-console/.venv/bin/python tools/drishti.py ...`). Where a tutorial shows output, it was captured from
a fresh clone of this version (2.3.0) on the demo lakes, built on Saturday 17 October 2026: the seven business days run from **2026-10-07 to 2026-10-16**. `lake demo` moves
the days to end on *your* today's business date, so your dates differ; the counts (15,000 trades a day, 5,000 per booking system) and ids (`MX-20000001`, `1000001`,
`CLY-3000157`, `CP-NORTHPLAINS`) are the same on every machine.

The pictures are made, not drawn: `tools/docs/screenshots.py --guide t01` (to `t18`) drives a scratch stack with a real browser and writes them to `img/`. When the screens change, run it again.

## The sample data

| File or folder | What it is | Used by |
|---|---|---|
| `data/desk-quotes/<date>/desk-quote.jsonl` | ten business days of six desk quotes each (kind `desk-quote`: `quote` belongs to the news-and-markets pack) | tutorials 4 and 5 |
| `data/schemas/desk-quote.schema.json` | the JSON Schema of a desk quote | tutorial 5 |
| `data/connectors/` | a file connector, a PostgreSQL connector aimed at a port where nothing listens, and a file Drishti refuses | tutorial 4 |
| `data/desks/*.json`, `data/desk-book.sutra.yaml` | three desks with currencies and trades, and the screen designed for them | tutorial 6 |
| `data/slicer-kinds.report.yaml` | a report with a list, a dropdown and a range slicer and a top-N donut | tutorial 10 |
| `data/trades-daily.dataset.yaml`, `data/trades-daily.model.yaml`, `data/desk-check.report.yaml`, `data/smtp-catcher.py` | a dataset that refreshes itself every business morning, the model over it, a report that says what its numbers must be; a mail catcher for trying subscriptions and alerts | tutorial 11 |
| `data/live-desk.mq.yaml`, `data/night-desk.report.yaml` | a model query over the live table, with its SQL; a board of three live tiles (a report with a board page, 2.4.0) | tutorial 12 |
| `data/rates-trades.dataset.yaml`, `data/trades-model.model.yaml`, `data/trades-report.report.yaml` | a dataset, the model over it and the report on the model, to review, certify and change | tutorial 13 |
| `data/notional-by-desk.python.yaml`, `data/desk-python.report.yaml` | a Python visual and a report that uses it | tutorial 14 |
| `data/ai/mock.yaml`, `data/ai/local-model.yaml` | the mock AI provider with rules for the tutorials, and a provider with nothing listening | tutorial 15 |
| `data/world-economics-21.dataset.yaml` | the World economics dataset in its 2.1 form, which the upgrade converts to a model | tutorial 17 |
| `data/pools.py` | the connection-pool sum | tutorial 17 |

Everything else is the demo lakes (`data/delta`, built by `lake demo`) and the packs you already have. The `world-economy` packs hold published data from the World Bank, UNDP, the CIA World Factbook and the IMF
([REAL_DATA.md](../guides/REAL_DATA.md)); the IMF part is for internal demonstration only, so the tutorials use the world-economy pack.
