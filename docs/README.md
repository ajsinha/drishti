<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Drishti documentation

Most of these documents are also the console's in-app help (`/help`). They are rendered there as they
are, so fixing a file here fixes the help.

## New here? Read these three, in order

1. **[GETTING_STARTED.md](GETTING_STARTED.md)**: install, build, start, and open your first views, step by step.
2. **[USER_GUIDE.md](USER_GUIDE.md)**: every feature of the console, each with a worked example.
3. **[PACKS.md](PACKS.md)**: which industries are available, and the commands each one adds.

## If you want to…

### Use Drishti

| If you want to… | Read |
|---|---|
| Install and start Drishti on your machine | [GETTING_STARTED.md](GETTING_STARTED.md) |
| Open a view, and learn the command line and keys | [USER_GUIDE.md › The command line](USER_GUIDE.md#the-command-line), [Keyboard](USER_GUIDE.md#keyboard) |
| Understand what a view is showing you | [USER_GUIDE.md › Reading a view](USER_GUIDE.md#reading-a-view) |
| Find entities by value (`TRD where mtm > 1m …`) | [USER_GUIDE.md › Search by value](USER_GUIDE.md#search-by-value) |
| Look at a past date, or compare two dates | [USER_GUIDE.md › Business dates](USER_GUIDE.md#business-dates-live-or-a-day-in-the-past), [Compare](USER_GUIDE.md#compare-what-changed) |
| See what depends on an entity (F8) | [Impact guide](../console/web/guides/impact.md) |
| Download CSV or JSON, print, or share a link | [USER_GUIDE.md › Export, print and share](USER_GUIDE.md#export-print-and-share) |
| Watch a list live, or be alerted when a figure crosses a line | [Monitors and alerts guide](../console/web/guides/monitors-and-alerts.md) |
| Put several views on one screen | [Workspaces guide](../console/web/guides/workspaces.md) |
| Change your theme, landing page or password | [USER_GUIDE.md › Your settings](USER_GUIDE.md#your-settings) |
| Find the commands for a pack | [PACKS.md › The packs that ship](PACKS.md#the-packs-that-ship), and the pack's own guide under *Help → Domain packs* |

### Change how screens look

| If you want to… | Read |
|---|---|
| Learn what a Sutra is and write one | [Sutra guide](../console/web/guides/sutra-guide.md) |
| Edit a Sutra with live preview and submit it for review | [Sutra Studio tutorial](../console/web/guides/sutra-studio.md) |
| Lay out nested documents (lists inside lists) | [Nested documents tutorial](../console/web/guides/nested-data.md) |
| Choose the right panel kind | [Panel kinds](../console/web/guides/panel-kinds.md) |
| Look up a key, format, expression or problem code | [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md) |
| Understand the screens Drishti draws with no Sutra | [INFERENCE.md](INFERENCE.md) |

### Add an industry or data

| If you want to… | Read |
|---|---|
| Enable packs, assign them to users, or write a pack | [PACKS.md](PACKS.md) |
| Generate a full pack from Python (Sutras, samples, guide, lake) | [Build a domain pack tutorial](../console/web/guides/build-a-pack.md) |
| Connect a database, REST service, Kafka, S3, Delta Lake… | [PLUGIN_GUIDE.md](PLUGIN_GUIDE.md) |
| Understand live updates (SSE, frames, reconnects) | [LIVE.md](LIVE.md) |

### Run and administer it

| If you want to… | Read |
|---|---|
| Create users, give roles and packs, read the audit log | [USER_MANAGEMENT.md](USER_MANAGEMENT.md) |
| Look up any setting and its environment variable | [CONFIGURATION.md](CONFIGURATION.md) |
| Deploy, secure and monitor it; keep the lake bounded | [OPERATIONS.md](OPERATIONS.md) |
| Fix something that is not working | [TROUBLESHOOTING.md](TROUBLESHOOTING.md), then the runbooks: [source down](runbooks/source-down.md), [Sutra broken](runbooks/sutra-broken.md), [live latency high](runbooks/live-latency-high.md), [sign-in problems](runbooks/sign-in.md) |
| Know how fast it is, and how that is measured | [PERFORMANCE.md](PERFORMANCE.md) |

### Develop on it

| If you want to… | Read |
|---|---|
| Understand the design: pipeline, grammar, inference, modules | [ARCHITECTURE.md](ARCHITECTURE.md) |
| Call the REST API, or read the ViewModel contract | [API_GUIDE.md](API_GUIDE.md) (OpenAPI at `http://localhost:18480/api/docs/ui`) |
| Know why something is the way it is | [adr/](adr/README.md): the architecture decision records |
| See how it was built, wave by wave | [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) |
| See the four reference mockups the views reproduce | [requirements/](requirements/) |
| See what changed in each release | [../CHANGELOG.md](../CHANGELOG.md), [../RELEASE_NOTES.md](../RELEASE_NOTES.md) |

## Words you will meet

| Word | Meaning |
|---|---|
| **Entity** | One thing you can open: a trade, a netting set, a gene. It has a **kind** (`trade`) and an **id** (`T-10001`) |
| **Mnemonic** | The short code you type for a kind: `TRD` for trade |
| **View** | The screen for one entity: title, strip, panels, links |
| **Strip** | The row of key figures under a view's title |
| **Panel** | One box in a view: a table, a chart, label/value pairs |
| **Sutra** | A layout for one kind of entity, written as a Markdown document with a `sutra` block |
| **Rachana** | The grammar Sutras are written in; **Rachana-EL** is its expression language (`$.mtm > 0`) |
| **Inference** | Drishti laying out a view by itself from the shape of the data |
| **Pack** | An industry's commands, layouts, links, roles and sample data, in `packs/<name>/` |
| **Source / connector** | Where documents come from: the demo samples, Delta Lake, PostgreSQL, Kafka, … |
| **Business date** | The day you are looking at: **Live** (today, ticking) or a picked past date |
| **Known at** | A moment in time; the data as it was known then, before later corrections |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary and confidential.*
