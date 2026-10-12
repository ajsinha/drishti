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
| 10 | Business intelligence: your first report | build a report on a dataset in Rūpaka and publish it | | coming |
| 11 | Alerts on numbers | thresholds, changes, hysteresis and delivery | | coming |
| 12 | Boards and live tiles | a wall of live tiles for a desk | | coming |
| 13 | Python on a view and in a report | Calc and the Python visual | | coming |
| 14 | Put a report in another web page | Drishti Elements and embedding | | coming |
| 15 | Ask the data | the AI features of BI, with a mock model | | coming |
| 16 | Users, roles and masks | who sees what | | coming |
| 17 | Run it for real | operate, monitor, back up and upgrade | | coming |

(The titles of 10 to 17 are the plan and may change as they are written.)

## What you need once

Tutorials 1 to 3 need only [tutorial 1](01-first-15-minutes.md): Java 21 or newer, Python 3.11 or newer, `uv`, git, and the server and console started with sign-in on. Later tutorials say what else they need.
Run commands from the repository root. Where a tutorial shows output, it was captured from a real server of this version (1.22.0); numbers that depend on the generated sample data (counts, MTM) may differ a little on your machine.

The pictures are made, not drawn: `tools/docs/screenshots.py --guide t01` (to `t09`) drives a scratch stack with a real browser and writes them to `img/`. When the screens change, run it again.

## The sample data

| File or folder | What it is | Used by |
|---|---|---|
| `data/desk-quotes/<date>/quote.jsonl` | ten business days of six desk quotes each | tutorials 4 and 5 |
| `data/schemas/quote.schema.json` | the JSON Schema of a quote | tutorial 5 |
| `data/connectors/` | a file connector, a PostgreSQL connector aimed at a port where nothing listens, and a file Drishti refuses | tutorial 4 |
| `data/desks/*.json`, `data/desk-book.sutra.yaml` | three desks with currencies and trades, and the screen designed for them | tutorial 6 |

Everything else is the demo data of the packs you already have. The `politics-society` and `economics` packs hold published data from the World Bank, UNDP, the CIA World Factbook and the IMF
([REAL_DATA.md](../guides/REAL_DATA.md)); the IMF part is for internal demonstration only, so the tutorials use the politics-society pack.
