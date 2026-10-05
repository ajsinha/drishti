<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Drishti documentation

Most of these documents are also the console's in-app help (`/help`). They are rendered there as they
are, so fixing a file here fixes the help.

## New here? Read these, in order

1. **[QUICKSTART.md](guides/QUICKSTART.md)**: ten minutes from a fresh clone to your first live view, commands only.
2. **[USER_GUIDE.md](guides/USER_GUIDE.md)**: every feature of the console, each with a worked example.
3. **[PACKS.md](guides/PACKS.md)**: which industries are available, the commands each one adds, and how to load them; building your own is [PACK_DEVELOPER_GUIDE.md](guides/PACK_DEVELOPER_GUIDE.md).

If the quickstart goes too fast, [GETTING_STARTED.md](guides/GETTING_STARTED.md) walks the same ground with every step
explained. If you will change the code, continue with [DEVELOPER_GUIDE.md](guides/DEVELOPER_GUIDE.md).

## How these documents are organised

| Folder | What is in it |
|---|---|
| [guides/](guides/) | getting started, the user, developer, API and Rachana guides, packs, clients and troubleshooting |
| [connectors/](connectors/) | connecting your data: the connector and plugin guides, a design document per store (Delta Lake, PostgreSQL, Aerospike, files …) and demo data |
| [admin/](admin/) | running Drishti: operations, configuration, users and roles, performance, and the runbooks |
| [qa/](qa/) | quality assurance runs: each with its activity logs, findings by severity, and the scripts that reproduce them |
| [architecture/](architecture/) | how Drishti is built: the architecture, design notes (inference, live updates), the implementation plan and the decision records (ADRs) |

## If you want to…

### Use Drishti

| If you want to… | Read |
|---|---|
| Get it running in ten minutes | [QUICKSTART.md](guides/QUICKSTART.md) |
| Install and start it with every step explained | [GETTING_STARTED.md](guides/GETTING_STARTED.md) |
| Install and run it on Windows (PowerShell, no Hadoop) | [WINDOWS.md](guides/WINDOWS.md) |
| Run and debug the server in IntelliJ IDEA and the console in PyCharm | [IDE_GUIDE.md](guides/IDE_GUIDE.md) |
| Find your way around the top bar and its menus | [USER_GUIDE.md › The top bar](guides/USER_GUIDE.md#the-top-bar) |
| Read Drishti from a script, a notebook or Excel with a personal API token | [CLIENTS.md](guides/CLIENTS.md) |
| Run Python on the view you are looking at (`Alt+C`): re-price, recompute, pivot, chart | [PYTHON_CALC.md](guides/PYTHON_CALC.md) |
| Open a view, and learn the command line and keys | [USER_GUIDE.md › The command line](guides/USER_GUIDE.md#the-command-line), [Keyboard](guides/USER_GUIDE.md#keyboard) |
| List entities to pick from (`TRD MX-200000`, `CPTY north`, `TRD productType=Revolver`) | [USER_GUIDE.md › Pick lists](guides/USER_GUIDE.md#pick-lists-when-a-command-names-several-entities) |
| Page through a table, or walk it with the keyboard | [USER_GUIDE.md › Tables](guides/USER_GUIDE.md#tables-sorting-filtering-paging-and-the-keyboard) |
| Understand what a view is showing you | [USER_GUIDE.md › Reading a view](guides/USER_GUIDE.md#reading-a-view) |
| Find entities by value (`TRD where mtm > 1m …`) | [USER_GUIDE.md › Search by value](guides/USER_GUIDE.md#search-by-value) |
| Look at a past date, or compare two dates | [USER_GUIDE.md › Business dates](guides/USER_GUIDE.md#business-dates-live-or-a-day-in-the-past), [Compare](guides/USER_GUIDE.md#compare-what-changed) |
| See what depends on an entity (F8) | [Impact guide](../console/web/guides/impact.md) |
| Download CSV or JSON, print, or share a link | [USER_GUIDE.md › Export, print and share](guides/USER_GUIDE.md#export-print-and-share) |
| Send a view to a colleague with a note, read your inbox, discuss a number | [USER_GUIDE.md › Share a view with a note](guides/USER_GUIDE.md#share-a-view-with-a-note), [Your inbox](guides/USER_GUIDE.md#your-inbox), [Discussion](guides/USER_GUIDE.md#discussion) |
| Follow a share and a comment end to end (rights, masks, audit, retention) | [HOW_IT_FITS.md §3.10](architecture/HOW_IT_FITS.md#310-share-and-discussion-end-to-end) |
| Watch a list live, or be alerted when a figure crosses a line | [Monitors and alerts guide](../console/web/guides/monitors-and-alerts.md) |
| Put several views on one screen | [Workspaces guide](../console/web/guides/workspaces.md) |
| Change your theme, landing page or password | [USER_GUIDE.md › Your settings](guides/USER_GUIDE.md#your-settings) |
| Choose which packs you see | [USER_GUIDE.md › Domain packs](guides/USER_GUIDE.md#domain-packs-choosing-what-you-see) |
| Find the commands for a pack | [PACKS.md › The packs that ship](guides/PACKS.md#the-packs-that-ship), and the pack's own guide under *Help → Domain packs* |

### Change how screens look

| If you want to… | Read |
|---|---|
| Learn to build screens in the workbench, step by step, with a picture of each step | [BUILD_WORKBENCH_TUTORIAL.md](guides/BUILD_WORKBENCH_TUTORIAL.md) |
| Learn what a Sutra is and write one, from the first panel to a tested screen | [SUTRA_DEVELOPER_GUIDE.md](guides/SUTRA_DEVELOPER_GUIDE.md) |
| Choose or configure a panel kind | [PANEL_KINDS.md](guides/PANEL_KINDS.md) |
| Add a new panel kind to Drishti | [PANEL_DEVELOPER_GUIDE.md](guides/PANEL_DEVELOPER_GUIDE.md) |
| Edit a Sutra with live preview and submit it for review | [Screen designer guide](guides/SCREEN_DESIGNER.md) |
| Lay out nested documents (lists inside lists) | [Nested documents tutorial](../console/web/guides/nested-data.md) |
| Choose the right panel kind | [PANEL_KINDS.md](guides/PANEL_KINDS.md) |
| Build a new panel kind, end to end (grammar, engine, console, workbench, tests) | [PANEL_DEVELOPER_GUIDE.md](guides/PANEL_DEVELOPER_GUIDE.md) |
| Look up a key, format, expression or problem code | [RACHANA_REFERENCE.md](guides/RACHANA_REFERENCE.md) |
| Get completion for Sutras in your own editor (the JSON Schema of the language) | [API_GUIDE.md](guides/API_GUIDE.md#catalogue-about-packs-sources-sutras) (`GET /api/v1/rachana/schema`) |
| Convert Markdown Sutras (`*.sutra.md`) from before 1.11 | [runbooks/sutra-broken.md](admin/runbooks/sutra-broken.md#step-1a-a-sutramd-or-plain-yaml-file-drs-2004-drs-2009) |
| See how connectors, packs and Sutras fit together, and how the UI is bound to the backend at runtime | [HOW_IT_FITS.md](architecture/HOW_IT_FITS.md) |
| Understand the screens Drishti draws with no Sutra | [INFERENCE.md](architecture/INFERENCE.md) |
| Design of the Screen Builder (JSON files → shape → visual designer → Sutra) | [SCREEN_BUILDER.md](architecture/SCREEN_BUILDER.md) |
| The Build workbench: Designs, one page for data, canvas, YAML and tests, and the plan | [BUILD_WORKBENCH.md](architecture/BUILD_WORKBENCH.md) |
| Design of *About this page*: context-aware help built from the page's data, pack glossaries, the explain API and the plan | [CONTEXT_HELP.md](architecture/CONTEXT_HELP.md) |
| Collaboration: share a view with a note, comment threads anchored to the data, the inbox, email, retention and legal hold; the design, what was built and the plan | [COLLABORATION.md](architecture/COLLABORATION.md) |

### Add an industry or data

| If you want to… | Read |
|---|---|
| Build a pack, from an empty folder to a working view | [PACK_DEVELOPER_GUIDE.md](guides/PACK_DEVELOPER_GUIDE.md) |
| Load packs, switch them off and on, assign them to users | [PACKS.md](guides/PACKS.md#turning-packs-on) |
| Generate a full pack from Python (Sutras, samples, guide, lake) | [PACK_DEVELOPER_GUIDE.md › Generators](guides/PACK_DEVELOPER_GUIDE.md) |
| Choose a connector, write a new plugin and test it | [CONNECTOR_DEVELOPER_GUIDE.md](connectors/CONNECTOR_DEVELOPER_GUIDE.md) |
| Connect your data, step by step, with every setting of the connector | the per-connector docs under [connectors/](connectors/), one per store |
| Understand live updates (SSE, frames, reconnects) | [LIVE.md](architecture/LIVE.md) |

### Run and administer it

| If you want to… | Read |
|---|---|
| Create users, give roles and packs, read the audit log | [USER_MANAGEMENT.md](admin/USER_MANAGEMENT.md) |
| Define roles, or switch a pack off for everyone | [USER_GUIDE.md › Administration](guides/USER_GUIDE.md#administration) |
| Look up any setting and its environment variable | [CONFIGURATION.md](admin/CONFIGURATION.md) |
| Install Calc's Python runtime, offline too, and decide who may use it | [PYTHON_CALC.md › Installing](guides/PYTHON_CALC.md#12-installing-the-python-runtime), [Roles](guides/PYTHON_CALC.md#9-roles-who-may-use-calc) |
| Deploy, secure and monitor it; keep the lake bounded | [OPERATIONS.md](admin/OPERATIONS.md) |
| Fix something that is not working | [TROUBLESHOOTING.md](guides/TROUBLESHOOTING.md), then the runbooks below |
| Know how fast it is, and how that is measured | [PERFORMANCE.md](admin/PERFORMANCE.md) |
| Compare the stores at several sizes, see how each scales, and run the scale benchmark yourself | [SCALE_BENCHMARK.md](admin/SCALE_BENCHMARK.md) |
| Serve millions of entities a day for years from Delta Lake | [DELTA_CONNECTOR.md](connectors/DELTA_CONNECTOR.md) |
| Run the server on Windows, or choose the Delta engine (native or Hadoop) | [WINDOWS.md](guides/WINDOWS.md), [DELTA_CONNECTOR.md › Engines](connectors/DELTA_CONNECTOR.md#16-engines-native-and-hadoop) |
| Serve millions of entities a day from Aerospike, with recent history there and years in Delta Lake | [AEROSPIKE_CONNECTOR.md](connectors/AEROSPIKE_CONNECTOR.md) |
| Serve millions of entities a day from PostgreSQL | [POSTGRES_CONNECTOR.md](connectors/POSTGRES_CONNECTOR.md) |
| Serve data from plain JSON-lines files, the simplest store | [FILE_CONNECTOR.md](connectors/FILE_CONNECTOR.md) |
| Serve documents kept as JSON objects in S3, MinIO or another S3-compatible store | [S3_CONNECTOR.md](connectors/S3_CONNECTOR.md) |
| Read entities from an in-house HTTP/JSON service, one request per view | [REST_CONNECTOR.md](connectors/REST_CONNECTOR.md) |
| Show live entities from Kafka topics, with history from a lake | [KAFKA_CONNECTOR.md](connectors/KAFKA_CONNECTOR.md) |
| Show live entities from ActiveMQ queues or topics | [ACTIVEMQ_CONNECTOR.md](connectors/ACTIVEMQ_CONNECTOR.md) |
| Show live entities from RabbitMQ queues | [RABBITMQ_CONNECTOR.md](connectors/RABBITMQ_CONNECTOR.md) |
| Serve public rates and FX (NY Fed SOFR, ECB €STR and FX, US Treasury, FRED) | [FEEDS_CONNECTOR.md](connectors/FEEDS_CONNECTOR.md) |
| Understand the sample data every pack ships, and its live ticks | [DEMO_CONNECTOR.md](connectors/DEMO_CONNECTOR.md) |
| Read your own database with your own SQL: several queries per kind | [JDBC_QUERIES.md](connectors/JDBC_QUERIES.md) |
| Serve millions of entities a day from Apache Iceberg (Snowflake, Glue, Polaris, Trino) | [ICEBERG_CONNECTOR.md](connectors/ICEBERG_CONNECTOR.md) |
| Serve a large book from one embedded DuckDB file, with no database server | [DUCKDB_CONNECTOR.md](connectors/DUCKDB_CONNECTOR.md) |
| Serve entities from MongoDB documents | [MONGODB_CONNECTOR.md](connectors/MONGODB_CONNECTOR.md) |
| Serve today and recent days from Redis memory, with live updates | [REDIS_CONNECTOR.md](connectors/REDIS_CONNECTOR.md) |
| Build demo data in any store, small to a million trades a day | [DEMO_DATA.md](connectors/DEMO_DATA.md) |

### Develop on it

| If you want to… | Read |
|---|---|
| Build, test and change the code; recipes for common changes | [DEVELOPER_GUIDE.md](guides/DEVELOPER_GUIDE.md) |
| Understand the design: pipeline, grammar, inference, modules | [ARCHITECTURE.md](architecture/ARCHITECTURE.md) |
| See how server-side pricing (QuantLib) will work, and what is next on the roadmap | [QUANT_SERVICE.md](architecture/QUANT_SERVICE.md), [IMPLEMENTATION_PLAN.md › Roadmap](architecture/IMPLEMENTATION_PLAN.md#roadmap) |
| Call the REST API, or read the ViewModel contract | [API_GUIDE.md](guides/API_GUIDE.md) (OpenAPI at `http://localhost:18480/api/docs/ui`) |
| Know why something is the way it is | [adr/](architecture/adr/README.md): the architecture decision records |
| See how it was built, wave by wave, and what is still open | [IMPLEMENTATION_PLAN.md](architecture/IMPLEMENTATION_PLAN.md) |
| See the four reference mockups the views reproduce | [requirements/](requirements/) |
| See what changed in each release | [../CHANGELOG.md](../CHANGELOG.md), [../RELEASE_NOTES.md](../RELEASE_NOTES.md) |

## Every document, and when to read it

### In this folder

| Document | Read this when… |
|---|---|
| [QUICKSTART.md](guides/QUICKSTART.md) | you want Drishti running in ten minutes and need only the commands |
| [GETTING_STARTED.md](guides/GETTING_STARTED.md) | you are installing for the first time and want each step explained, with what you should see |
| [IDE_GUIDE.md](guides/IDE_GUIDE.md) | you run the server from IntelliJ IDEA and the console from PyCharm: JDK and interpreter setup, run configurations, debugging, reloading changes, tests in the IDE |
| [WINDOWS.md](guides/WINDOWS.md) | you run Drishti on Windows: a JDK 21 or newer (25 recommended) and Python, building or copying the jar, the native Delta engine (no Hadoop, no `winutils.exe`), the PowerShell scripts, what does not work there, troubleshooting |
| [USER_GUIDE.md](guides/USER_GUIDE.md) | you use the console: top bar, command line, pick lists, tables, views, dates, search, export, monitors, alerts, workspaces, settings, the Build workbench, administration |
| [PACKS.md](guides/PACKS.md) | you load, switch, assign or change a domain pack |
| [PACK_DEVELOPER_GUIDE.md](guides/PACK_DEVELOPER_GUIDE.md) | you build a pack or need any `pack.yaml` key: kinds, links, roles, Sutras, tests, generators, signing; a worked help-desk pack |
| [PYTHON_CALC.md](guides/PYTHON_CALC.md) | you run Python on a view (`Alt+C`): the `drishti` module, output, packs' snippets, roles, the security model, installing the runtime, measured sizes and speed, limits |
| [BUILD_WORKBENCH_TUTORIAL.md](guides/BUILD_WORKBENCH_TUTORIAL.md) | you are new to the Build workbench: four complete projects with a picture of each step |
| [SUTRA_DEVELOPER_GUIDE.md](guides/SUTRA_DEVELOPER_GUIDE.md) | you are learning to write Sutras: anatomy, matching, labels, paths, source, row groups, testing and CI, the checklist |
| [RACHANA_REFERENCE.md](guides/RACHANA_REFERENCE.md) | you are writing a Sutra and need the exact key, panel option, format, expression or problem code |
| [PANEL_KINDS.md](guides/PANEL_KINDS.md) | you choose, configure or debug a panel: all twenty-one kinds, what each means, how it behaves, an example and a picture |
| [PANEL_DEVELOPER_GUIDE.md](guides/PANEL_DEVELOPER_GUIDE.md) | you add a new panel kind to Drishti |
| [HOW_IT_FITS.md](architecture/HOW_IT_FITS.md) | you are new and want the whole working: how connectors, packs and Sutras relate, and how a command becomes a live screen |
| [INFERENCE.md](architecture/INFERENCE.md) | a view looks different from what you expected and *How this view was built* says `inference` |
| [CONNECTOR_DEVELOPER_GUIDE.md](connectors/CONNECTOR_DEVELOPER_GUIDE.md) | you choose a connector, write a new source plugin, or test one with the testkit contracts; each connector's settings and walk-through are in its own `*_CONNECTOR.md` |
| [LIVE.md](architecture/LIVE.md) | you need to know how values tick: streams, frames, coalescing, reconnects, one channel per browser, shared by its tabs |
| [USER_MANAGEMENT.md](admin/USER_MANAGEMENT.md) | you create users, define roles, assign packs, reset passwords, set up single sign-on or read the audit log |
| [CONFIGURATION.md](admin/CONFIGURATION.md) | you need a setting's name, default and environment variable |
| [OPERATIONS.md](admin/OPERATIONS.md) | you deploy, secure, monitor, back up or upgrade a server and console |
| [PERFORMANCE.md](admin/PERFORMANCE.md) | you want the measured numbers, to measure your own installation, or to tune it |
| [SCALE_BENCHMARK.md](admin/SCALE_BENCHMARK.md) | you choose a store or size a server: every store measured at 10,000, 25,000 and 50,000 trades a day, linear scaling fits, labelled extrapolations to a million, and the harness (`tools/bench/`) to run it at a million on a real server |
| [DELTA_CONNECTOR.md](connectors/DELTA_CONNECTOR.md) | you run Drishti over a large Delta Lake (a million trades a day for seven years): layout, writers, reads, memory, maintenance, measurements, and the two engines (native without Hadoop, or Hadoop) |
| [FILE_CONNECTOR.md](connectors/FILE_CONNECTOR.md) | you serve data from JSON-lines files: the layout, one file per kind per day, the index of a day, loading, sizes, measurements |
| [POSTGRES_CONNECTOR.md](connectors/POSTGRES_CONNECTOR.md) | you run Drishti on PostgreSQL at scale: partitioned table, promoted columns, COPY loader, monthly retention, sizing, measurements |
| [DEMO_DATA.md](connectors/DEMO_DATA.md) | you need demo data in Delta Lake, PostgreSQL or Aerospike, small for a laptop or a million trades a day |
| [AEROSPIKE_CONNECTOR.md](connectors/AEROSPIKE_CONNECTOR.md) | you run Drishti on Aerospike at scale: record layout, loader, partition-parallel scans, TTL retention, sizing, measurements |
| [JDBC_QUERIES.md](connectors/JDBC_QUERIES.md) | you read your own schema with SQL: the entity, its parts, ids, columns and reverse queries, parameters, naming, performance |
| [ICEBERG_CONNECTOR.md](connectors/ICEBERG_CONNECTOR.md) | you run Drishti over Apache Iceberg: the layout, path-based and REST catalogs, delete files, time travel, the loader and maintenance, sizing, measurements |
| [DUCKDB_CONNECTOR.md](connectors/DUCKDB_CONNECTOR.md) | you run Drishti on one embedded DuckDB file: the layout, the loader and its file swap, the shared read-only instance, read paths, retention, sizing, memory, measurements |
| [MONGODB_CONNECTOR.md](connectors/MONGODB_CONNECTOR.md) | you run Drishti on MongoDB: a document per entity per day, the narrow columns collection, indexes, parallel day reads, retention, sharding, measurements |
| [REDIS_CONNECTOR.md](connectors/REDIS_CONNECTOR.md) | you run Drishti on Redis: key layout, zstd dictionaries, column chunks, live pub/sub, TTL, sizing, measurements |
| [S3_CONNECTOR.md](connectors/S3_CONNECTOR.md) | you serve documents from S3 or an S3-compatible store: the key layout, listing and caching, credentials, cost of large buckets |
| [REST_CONNECTOR.md](connectors/REST_CONNECTOR.md) | you read entities from an HTTP/JSON service: paths, headers, generations, timeouts, what it cannot do, load on the service |
| [KAFKA_CONNECTOR.md](connectors/KAFKA_CONNECTOR.md) | you serve live entities from Kafka: state and ticks modes, the index and disk cache, offsets, ordering, restarts, reconnection, pairing with a lake |
| [ACTIVEMQ_CONNECTOR.md](connectors/ACTIVEMQ_CONNECTOR.md) | you serve live entities from ActiveMQ: queues and durable topics, the state store, its durability and disk budget, acknowledgement, failover, sizing |
| [RABBITMQ_CONNECTOR.md](connectors/RABBITMQ_CONNECTOR.md) | you serve live entities from RabbitMQ: queues and bindings, prefetch and acknowledgement, the state store, its durability and disk budget, recovery, sizing |
| [FEEDS_CONNECTOR.md](connectors/FEEDS_CONNECTOR.md) | you switch on the public feeds: each source's URL and parsing, entities and ids, schedules, history, offline mirrors |
| [DEMO_CONNECTOR.md](connectors/DEMO_CONNECTOR.md) | you want to know what the sample connector serves, how it ticks, how packs supply samples, and how to switch it off |
| [TROUBLESHOOTING.md](guides/TROUBLESHOOTING.md) | something is not working: each problem has what you see, how to check and the fix |
| [DEVELOPER_GUIDE.md](guides/DEVELOPER_GUIDE.md) | you change Drishti's code: layout, build, tests, gates, and recipes |
| [qa/2026-10-01](qa/2026-10-01/README.md) | you want the adversarial QA of 1.13.0: what was tested, every finding by severity with reproduction steps, what held up, and the proposed fix order |
| [qa/2026-10-03](qa/2026-10-03/README.md) | you want the adversarial QA of the Build workbench (round 2, before 1.14.0): what was tested, every finding by severity, and how each was fixed |
| [CONTEXT_HELP.md](architecture/CONTEXT_HELP.md) | you want to know how *About this page* will explain a view (what it shows, what each number means, where the data came from, why the layout, where next): the PageContext, the explain API, pack `about.yaml`, masking rules and the build plan (proposed) |
| [COLLABORATION.md](architecture/COLLABORATION.md) | you want to know how *share with a note* and comment threads work and why: pins to the business date and generation, recipients' own rights, masks on user text, the inbox and email outbox, retention, legal hold, export, with a note on what was built differently from the design (steps 1 to 7 built; chat bridges and snapshots, phase 2, are not) |
| [QUANT_SERVICE.md](architecture/QUANT_SERVICE.md) | you want to know how server-side pricing (QuantLib) will work with Drishti and reach the screen: the design and phases (proposed, on the roadmap) |
| [ARCHITECTURE.md](architecture/ARCHITECTURE.md) | you want to understand how the pieces fit: pipeline, grammar, inference, graph, modules |
| [API_GUIDE.md](guides/API_GUIDE.md) | you call the REST API from a program, or need the ViewModel contract |
| [IMPLEMENTATION_PLAN.md](architecture/IMPLEMENTATION_PLAN.md) | you want to know which waves shipped in which release, and what is still open |

### Runbooks (`runbooks/`)

| Runbook | Read this when… |
|---|---|
| [source-down.md](admin/runbooks/source-down.md) | a connector is down, slow, idle or failed to start; views fail with `DRS-1003`/`DRS-1004`; links say *pending* |
| [sutra-broken.md](admin/runbooks/sutra-broken.md) | a Sutra has problems, an edit does not show, or a view says `inference only` where you expected a Sutra |
| [live-latency-high.md](admin/runbooks/live-latency-high.md) | live views are slow, late or frozen, or the live dot stays amber |
| [sign-in.md](admin/runbooks/sign-in.md) | users cannot sign in, are locked out, or are refused (`DRS-5010`, `DRS-5002`) |

### In-app guides (`../console/web/guides/`, also under *Help*)

| Guide | Read this when… |
|---|---|
| [SCREEN_DESIGNER.md](guides/SCREEN_DESIGNER.md) | you design or edit a Sutra in the browser, save it and submit it for review |
| [nested-data.md](../console/web/guides/nested-data.md) | your documents hold lists inside lists |
| [impact.md](../console/web/guides/impact.md) | you use or configure F8 impact |
| [monitors-and-alerts.md](../console/web/guides/monitors-and-alerts.md) | you set up watchlists and alert rules |
| [workspaces.md](../console/web/guides/workspaces.md) | you arrange several live views on one screen |

### Records

| Folder | Read this when… |
|---|---|
| [adr/](architecture/adr/README.md) | you want the reason behind a design decision (seventeen records; ADR-017 made Sutras YAML only) |
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
