<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Drishti documentation

Most of these documents are also the console's in-app help (`/help`). They are rendered there as they
are, so fixing a file here fixes the help.

## New here? Read these, in order

1. **[QUICKSTART.md](QUICKSTART.md)**: ten minutes from a fresh clone to your first live view, commands only.
2. **[USER_GUIDE.md](USER_GUIDE.md)**: every feature of the console, each with a worked example.
3. **[PACKS.md](PACKS.md)**: which industries are available, the commands each one adds, and how to build your own.

If the quickstart goes too fast, [GETTING_STARTED.md](GETTING_STARTED.md) walks the same ground with every step
explained. If you will change the code, continue with [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md).

## If you want to…

### Use Drishti

| If you want to… | Read |
|---|---|
| Get it running in ten minutes | [QUICKSTART.md](QUICKSTART.md) |
| Install and start it with every step explained | [GETTING_STARTED.md](GETTING_STARTED.md) |
| Find your way around the top bar and its menus | [USER_GUIDE.md › The top bar](USER_GUIDE.md#the-top-bar) |
| Read Drishti from a script, a notebook or Excel with a personal API token | [CLIENTS.md](CLIENTS.md) |
| Open a view, and learn the command line and keys | [USER_GUIDE.md › The command line](USER_GUIDE.md#the-command-line), [Keyboard](USER_GUIDE.md#keyboard) |
| List entities to pick from (`TRD MX-200000`, `CPTY north`, `TRD productType=Revolver`) | [USER_GUIDE.md › Pick lists](USER_GUIDE.md#pick-lists-when-a-command-names-several-entities) |
| Page through a table, or walk it with the keyboard | [USER_GUIDE.md › Tables](USER_GUIDE.md#tables-paging-and-the-keyboard) |
| Understand what a view is showing you | [USER_GUIDE.md › Reading a view](USER_GUIDE.md#reading-a-view) |
| Find entities by value (`TRD where mtm > 1m …`) | [USER_GUIDE.md › Search by value](USER_GUIDE.md#search-by-value) |
| Look at a past date, or compare two dates | [USER_GUIDE.md › Business dates](USER_GUIDE.md#business-dates-live-or-a-day-in-the-past), [Compare](USER_GUIDE.md#compare-what-changed) |
| See what depends on an entity (F8) | [Impact guide](../console/web/guides/impact.md) |
| Download CSV or JSON, print, or share a link | [USER_GUIDE.md › Export, print and share](USER_GUIDE.md#export-print-and-share) |
| Watch a list live, or be alerted when a figure crosses a line | [Monitors and alerts guide](../console/web/guides/monitors-and-alerts.md) |
| Put several views on one screen | [Workspaces guide](../console/web/guides/workspaces.md) |
| Change your theme, landing page or password | [USER_GUIDE.md › Your settings](USER_GUIDE.md#your-settings) |
| Choose which packs you see | [USER_GUIDE.md › Domain packs](USER_GUIDE.md#domain-packs-choosing-what-you-see) |
| Find the commands for a pack | [PACKS.md › The packs that ship](PACKS.md#the-packs-that-ship), and the pack's own guide under *Help → Domain packs* |

### Change how screens look

| If you want to… | Read |
|---|---|
| Learn the layout grammar from scratch, lesson by lesson | [RACHANA_GUIDE.md](RACHANA_GUIDE.md) |
| Learn what a Sutra is and write one | [Sutra guide](../console/web/guides/sutra-guide.md) |
| Edit a Sutra with live preview and submit it for review | [Sutra Studio tutorial](../console/web/guides/sutra-studio.md) |
| Lay out nested documents (lists inside lists) | [Nested documents tutorial](../console/web/guides/nested-data.md) |
| Choose the right panel kind | [Panel kinds](../console/web/guides/panel-kinds.md) |
| Look up a key, format, expression or problem code | [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md) |
| Get completion for Sutras in your own editor (the JSON Schema of the language) | [API_GUIDE.md](API_GUIDE.md#catalogue-about-packs-sources-sutras) (`GET /api/v1/rachana/schema`) |
| Convert Markdown Sutras (`*.sutra.md`) from before 1.11 | [runbooks/sutra-broken.md](runbooks/sutra-broken.md#step-1a-a-sutramd-or-plain-yaml-file-drs-2004-drs-2009) |
| Understand the screens Drishti draws with no Sutra | [INFERENCE.md](INFERENCE.md) |

### Add an industry or data

| If you want to… | Read |
|---|---|
| Build a pack by hand, from an empty folder to a working view | [PACKS.md › Writing a pack by hand](PACKS.md#writing-a-pack-by-hand-step-by-step) |
| Load packs, switch them off and on, assign them to users | [PACKS.md](PACKS.md#turning-packs-on) |
| Generate a full pack from Python (Sutras, samples, guide, lake) | [Build a domain pack tutorial](../console/web/guides/build-a-pack.md) |
| Connect your data, step by step (files, PostgreSQL, Delta Lake, Kafka, S3, REST, …) | [CONNECTOR_GUIDE.md](CONNECTOR_GUIDE.md) |
| Look up every connector's settings, or write a new plugin | [PLUGIN_GUIDE.md](PLUGIN_GUIDE.md) |
| Understand live updates (SSE, frames, reconnects) | [LIVE.md](LIVE.md) |

### Run and administer it

| If you want to… | Read |
|---|---|
| Create users, give roles and packs, read the audit log | [USER_MANAGEMENT.md](USER_MANAGEMENT.md) |
| Define roles, or switch a pack off for everyone | [USER_GUIDE.md › Administration](USER_GUIDE.md#administration) |
| Look up any setting and its environment variable | [CONFIGURATION.md](CONFIGURATION.md) |
| Deploy, secure and monitor it; keep the lake bounded | [OPERATIONS.md](OPERATIONS.md) |
| Fix something that is not working | [TROUBLESHOOTING.md](TROUBLESHOOTING.md), then the runbooks below |
| Know how fast it is, and how that is measured | [PERFORMANCE.md](PERFORMANCE.md) |

### Develop on it

| If you want to… | Read |
|---|---|
| Build, test and change the code; recipes for common changes | [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) |
| Understand the design: pipeline, grammar, inference, modules | [ARCHITECTURE.md](ARCHITECTURE.md) |
| Call the REST API, or read the ViewModel contract | [API_GUIDE.md](API_GUIDE.md) (OpenAPI at `http://localhost:18480/api/docs/ui`) |
| Know why something is the way it is | [adr/](adr/README.md): the architecture decision records |
| See how it was built, wave by wave, and what is still open | [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) |
| See the four reference mockups the views reproduce | [requirements/](requirements/) |
| See what changed in each release | [../CHANGELOG.md](../CHANGELOG.md), [../RELEASE_NOTES.md](../RELEASE_NOTES.md) |

## Every document, and when to read it

### In this folder

| Document | Read this when… |
|---|---|
| [QUICKSTART.md](QUICKSTART.md) | you want Drishti running in ten minutes and need only the commands |
| [GETTING_STARTED.md](GETTING_STARTED.md) | you are installing for the first time and want each step explained, with what you should see |
| [USER_GUIDE.md](USER_GUIDE.md) | you use the console: top bar, command line, pick lists, tables, views, dates, search, export, monitors, alerts, workspaces, settings, Studio, administration |
| [PACKS.md](PACKS.md) | you load, switch, assign, change, test or build a domain pack, or need any `pack.yaml` key |
| [RACHANA_GUIDE.md](RACHANA_GUIDE.md) | you are learning to write Sutras and want a tutorial that builds one up step by step |
| [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md) | you are writing a Sutra and need the exact key, panel option, format, expression or problem code |
| [INFERENCE.md](INFERENCE.md) | a view looks different from what you expected and *How this view was built* says `inference` |
| [CONNECTOR_GUIDE.md](CONNECTOR_GUIDE.md) | you are connecting your own data and want a worked, step-by-step path for your store |
| [PLUGIN_GUIDE.md](PLUGIN_GUIDE.md) | you need every setting of a connector, the data layout it expects, or you are writing a new source plugin |
| [LIVE.md](LIVE.md) | you need to know how values tick: streams, frames, coalescing, reconnects, one channel per tab |
| [USER_MANAGEMENT.md](USER_MANAGEMENT.md) | you create users, define roles, assign packs, reset passwords, set up single sign-on or read the audit log |
| [CONFIGURATION.md](CONFIGURATION.md) | you need a setting's name, default and environment variable |
| [OPERATIONS.md](OPERATIONS.md) | you deploy, secure, monitor, back up or upgrade a server and console |
| [PERFORMANCE.md](PERFORMANCE.md) | you want the measured numbers, to measure your own installation, or to tune it |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | something is not working: each problem has what you see, how to check and the fix |
| [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) | you change Drishti's code: layout, build, tests, gates, and recipes |
| [ARCHITECTURE.md](ARCHITECTURE.md) | you want to understand how the pieces fit: pipeline, grammar, inference, graph, modules |
| [API_GUIDE.md](API_GUIDE.md) | you call the REST API from a program, or need the ViewModel contract |
| [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) | you want to know which waves shipped in which release, and what is still open |

### Runbooks (`runbooks/`)

| Runbook | Read this when… |
|---|---|
| [source-down.md](runbooks/source-down.md) | a connector is down, slow, idle or failed to start; views fail with `DRS-1003`/`DRS-1004`; links say *pending* |
| [sutra-broken.md](runbooks/sutra-broken.md) | a Sutra has problems, an edit does not show, or a view says `inference only` where you expected a Sutra |
| [live-latency-high.md](runbooks/live-latency-high.md) | live views are slow, late or frozen, or the live dot stays amber |
| [sign-in.md](runbooks/sign-in.md) | users cannot sign in, are locked out, or are refused (`DRS-5010`, `DRS-5002`) |

### In-app guides (`../console/web/guides/`, also under *Help*)

| Guide | Read this when… |
|---|---|
| [sutra-guide.md](../console/web/guides/sutra-guide.md) | you write your first Sutra |
| [sutra-studio.md](../console/web/guides/sutra-studio.md) | you edit a Sutra in the browser and submit it for review |
| [nested-data.md](../console/web/guides/nested-data.md) | your documents hold lists inside lists |
| [panel-kinds.md](../console/web/guides/panel-kinds.md) | you choose between table, ladder, chart, surface and the other panel kinds |
| [build-a-pack.md](../console/web/guides/build-a-pack.md) | you generate a whole pack from Python |
| [impact.md](../console/web/guides/impact.md) | you use or configure F8 impact |
| [monitors-and-alerts.md](../console/web/guides/monitors-and-alerts.md) | you set up watchlists and alert rules |
| [workspaces.md](../console/web/guides/workspaces.md) | you arrange several live views on one screen |

### Records

| Folder | Read this when… |
|---|---|
| [adr/](adr/README.md) | you want the reason behind a design decision (seventeen records; ADR-017 made Sutras YAML only) |
| [requirements/](requirements/) | you want the four reference mockups the first views reproduced |

## Words you will meet

| Word | Meaning |
|---|---|
| **Entity** | One thing you can open: a trade, a netting set, a gene. It has a **kind** (`trade`) and an **id** (`MX-20000001`) |
| **Mnemonic** | The short code you type for a kind: `TRD` for trade |
| **Pick list** | The table you get when a command names several entities (`TRD MX-200000`); one match opens directly |
| **View** | The screen for one entity: title, strip, panels, links |
| **Strip** | The row of key figures under a view's title |
| **Panel** | One box in a view: a table, a chart, label/value pairs |
| **Sutra** | A layout for one kind of entity: one YAML file, `<name>.v<N>.sutra.yaml`, starting with `rachana: 1` |
| **Rachana** | The grammar Sutras are written in; **Rachana-EL** is its expression language (`$.mtm > 0`) |
| **Inference** | Drishti laying out a view by itself from the shape of the data |
| **Pack** | An industry's commands, layouts, links, roles and sample data, in `packs/<name>/` |
| **Source / connector** | Where documents come from: the demo samples, Delta Lake, PostgreSQL, Kafka, … |
| **Business date** | The day you are looking at: **Live** (today, ticking) or a picked past date |
| **Known at** | A moment in time; the data as it was known then, before later corrections |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary and confidential.*
