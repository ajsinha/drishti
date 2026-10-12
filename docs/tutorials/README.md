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

Hands-on, one worked example each. Every tutorial uses the demo data that ships with Drishti (the trades, counterparties and netting sets of the banking packs, the public data of the
politics-society pack, and a few small files in [data/](data/)). Each step says what to do, gives the real command and its real output wherever a command exists, shows a real screenshot, and tells you what
you should see. Each ends with next steps and what to do when something goes wrong.

They are written for someone who has never used Drishti. The reference documents ([USER_GUIDE.md](../guides/USER_GUIDE.md), [CLI_GUIDE.md](../guides/CLI_GUIDE.md) and the rest of [guides/](../guides/)) say everything;
the tutorials say what to do first.

## The tutorials

| # | Tutorial | You will | Time | Status |
|---|---|---|---|---|
| 1 | [Your first 15 minutes](01-first-15-minutes.md) | install, start, sign in, open a trade, search, try the form, look at BI, make a token | 15 min | written |
| 2 | [Look up anything](02-look-up-anything.md) | use mnemonics, type-ahead, links, a past date against Live, Compare | 20 min | written |
| 3 | [Query by form](03-query-by-form.md) | combine criteria, regular expressions, amounts, dates and checkbox dropdowns; save a search and a monitor | 20 min | written |
| 4 | [Connect your data](04-connect-your-data.md) | write a connector file, test it, handle TLS, a proxy, retries and the connection pool | 30 min | written |
| 5 | [Build a pack from your data](05-build-a-pack.md) | turn a schema and a folder into a pack, deploy it, roll it back, do it with `pack make` | 30 min | written |
| 6 | [Design a screen](06-design-a-screen.md) | design panels, tabs that hold panels, a slider, column filters and the About text | 40 min | written |
| 7 | [Load data daily](07-load-data-daily.md) | send the data-load signal, set expectations, see late data, alert on a missing load | 30 min | written |
| 8 | [Share and collaborate](08-share-and-collaborate.md) | share a filtered view, discuss it, mention a colleague, read the inbox | 25 min | written |
| 9 | [Reports](09-reports.md) | schedule a search, subscribe a colleague, read the history, use the Reports menu | 25 min | written |
| 10 | [Your first BI report](10-first-bi-report.md) | build a report on a dataset (cards, a map you drill from world to country to region, a bar, a slicer), cross-filter it, publish it; filter like Power BI; the Counterparty risk sample | 45 min | written |
| 11 | [BI that runs itself](11-bi-that-runs-itself.md) | a dataset that refreshes itself, a subscription (and what Run now says with no mail server, and how to set mail up), an alert on a number, export to PDF, PNG, Excel and CSV | 40 min | written |
| 12 | [Live BI and boards](12-live-bi-and-boards.md) | live data that moves together, a live table, a report that follows it (pause and resume), the Desk wall in kiosk mode | 30 min | written |
| 13 | [Govern BI](13-govern-bi.md) | history and diff, review with four-eyes, certify, lineage, the refusal of a breaking change, an environments bundle with a dry run | 45 min | written |
| 14 | [Python and Analyse](14-python-and-analyse.md) | a Python measure and a Python visual, then Analyse: slice, SQL, a Python cell, a notebook, promote | 40 min | written |
| 15 | [AI in Drishti](15-ai-in-drishti.md) | turn on the mock provider safely, the AI policy, report from words, ask the data, explain this number, Test connection; the provider types | 30 min | written |
| 16 | [Embed Drishti](16-embed-drishti.md) | register a host application, show a view and a report in its page under a strict CSP, tokens, limits | 40 min | written |
| 17 | [Run it in production](17-run-it-in-production.md) | security on, your own administrator, roles and BI powers, tokens, backups, heap and connection pools, health, upgrades, taking a pack, connector or BI file out safely | 60 min | written |

## What you need once

Tutorials 1 to 3 need only [tutorial 1](01-first-15-minutes.md): Java 21 or newer, Python 3.11 or newer, `uv`, git, and the server and console started with sign-in on. Later tutorials say what else they need.
Run commands from the repository root. Where a tutorial shows output, it was captured from a real server of this version (2.0.0); numbers that depend on the generated sample data (counts, MTM) may differ a little on your machine.

The pictures are made, not drawn: `tools/docs/screenshots.py --guide t01` (to `t17`) drives a scratch stack with a real browser and writes them to `img/`. When the screens change, run it again.

## The sample data

| File or folder | What it is | Used by |
|---|---|---|
| `data/desk-quotes/<date>/quote.jsonl` | ten business days of six desk quotes each | tutorials 4 and 5 |
| `data/schemas/quote.schema.json` | the JSON Schema of a quote | tutorial 5 |
| `data/connectors/` | a file connector, a PostgreSQL connector aimed at a port where nothing listens, and a file Drishti refuses | tutorial 4 |
| `data/desks/*.json`, `data/desk-book.sutra.yaml` | three desks with currencies and trades, and the screen designed for them | tutorial 6 |
| `data/slicer-kinds.report.yaml` | a report with a list, a dropdown and a range slicer and a top-N donut | tutorial 10 |
| `data/trades-daily.dataset.yaml`, `data/smtp-catcher.py` | a dataset that refreshes itself every business morning; a mail catcher for trying subscriptions and alerts | tutorial 11 |
| `data/trades-model.dataset.yaml`, `data/trades-report.report.yaml` | a dataset and the report on it, to review, certify and break | tutorial 13 |
| `data/notional-by-desk.python.yaml`, `data/desk-python.report.yaml` | a Python visual and a report that uses it | tutorial 14 |
| `data/ai/mock.yaml`, `data/ai/local-model.yaml` | the mock AI provider with rules for the tutorials, and a provider with nothing listening | tutorial 15 |
| `data/pools.py` | the connection-pool sum | tutorial 17 |

Everything else is the demo data of the packs you already have. The `politics-society` and `economics` packs hold published data from the World Bank, UNDP, the CIA World Factbook and the IMF
([REAL_DATA.md](../guides/REAL_DATA.md)); the IMF part is for internal demonstration only, so the tutorials use the politics-society pack.
