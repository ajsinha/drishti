<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Drishti documentation

Most of these documents are also the console's in-app help (`/help`). The help centre lists **about forty pages in six sections**: *Start* (install and run, the tutorials), *Use*, *BI*, *Data*, *Build* and *Operate*.
**The tutorials teach, the guides are reference:** every step-by-step walk-through lives in [tutorials/](tutorials/README.md); a guide says what a feature is, every option and every code, and points to the tutorial that
shows it. The architecture documents, the per-plugin connector documents, the runbooks and the QA records stay in the repository (and open in the help centre when a page links to them), listed below. An address from
before the consolidation (for example `/help/quickstart` or `/help/connector-kafka`) redirects to the page or section that took the subject over.

## New here? Read these, in order

1. **[GETTING_STARTED.md](guides/GETTING_STARTED.md)** (*Install and run*): prerequisites, build, start the server and the console, sign-in, Docker, other ways to build, and Windows.
2. **[tutorials/](tutorials/README.md)**: eighteen hands-on tutorials on the shipped demo data, with the exact commands, real output and real screenshots. Start with [your first 15 minutes](tutorials/01-first-15-minutes.md).
3. **[USER_GUIDE.md](guides/USER_GUIDE.md)**: every feature of the console, as a reference. **[PACKS.md](guides/PACKS.md)**: which industries are available and how to load them; building your own is [PACK_DEVELOPER_GUIDE.md](guides/PACK_DEVELOPER_GUIDE.md).

If you will change the code, continue with [DEVELOPER_GUIDE.md](guides/DEVELOPER_GUIDE.md).

## The help centre, section by section

| Section | Pages |
|---|---|
| **Start** | [Install and run](guides/GETTING_STARTED.md) · [Tutorials](tutorials/README.md) |
| **Use** | [User guide](guides/USER_GUIDE.md) · [Query by form](guides/QUERY_BY_FORM.md) · [Reports and subscriptions](guides/SCHEDULED_REPORTS.md) · Sharing and collaboration (console guide) · [Calc](guides/PYTHON_CALC.md) · [Scripts, notebooks and Excel](guides/CLIENTS.md) |
| **BI** | [BI guide](guides/BI_GUIDE.md) · [BI recipes](guides/BI_RECIPES.md) · [Datasets](guides/DATASETS.md) · [Reports](guides/REPORTS.md) · [Boards](guides/BOARDS.md) · [Governance](guides/BI_GOVERNANCE.md) · [Distribution](guides/DISTRIBUTION.md) · [Python in BI](guides/PYTHON_BI.md) · [Analyse](guides/ANALYSE.md) · [AI in BI](guides/BI_AI.md) |
| **Data** | [Connectors](connectors/CONNECTORS.md) (a section per plugin) · [Connector files](connectors/CONNECTOR_FILES.md) · [TLS and proxy](connectors/TLS.md) · [Data loads](guides/DATA_LOADS.md) |
| **Build** | [Packs](guides/PACKS.md) · [Pack developer guide](guides/PACK_DEVELOPER_GUIDE.md) · [Schema to pack](guides/SCHEMA_TO_PACK.md) · [Rachana guide](guides/SUTRA_DEVELOPER_GUIDE.md) · [Rachana reference](guides/RACHANA_REFERENCE.md) · [Panels](guides/PANELS.md) · [Examples](guides/examples/README.md) · [Build workbench](guides/SCREEN_DESIGNER.md) · [Connector developer guide](connectors/CONNECTOR_DEVELOPER_GUIDE.md) · [Developer guide](guides/DEVELOPER_GUIDE.md) · [Embedding](guides/ELEMENTS.md) |
| **Operate** | [Configuration](admin/CONFIGURATION.md) · [Operations](admin/OPERATIONS.md) · [Users and roles](admin/USER_MANAGEMENT.md) · [Install and uninstall safely](admin/ARTIFACTS.md) · [API](guides/API_GUIDE.md) · [Command line](guides/CLI_GUIDE.md) · [Tools](guides/TOOLS.md) · [AI providers](architecture/AI_PROVIDERS.md) · [Troubleshooting](guides/TROUBLESHOOTING.md) · [Release notes](../RELEASE_NOTES.md) |
| In the repository only | [architecture/](architecture/ARCHITECTURE.md) (design notes and [decision records](architecture/adr/README.md)), the per-plugin [connector documents](connectors/), the [runbooks](admin/runbooks/source-down.md), [qa/](qa/) |

## How these documents are organised

| Folder | What is in it |
|---|---|
| [tutorials/](tutorials/README.md) | the eighteen hands-on tutorials: a worked example each, on the demo data, with commands, real output and screenshots |
| [guides/](guides/) | install and run, the user guide, business intelligence (Rūpaka), packs and the command line, the Sutra and Rachana guides, panels, clients, API and troubleshooting; `examples/` holds working Sutras |
| [connectors/](connectors/) | connecting your data: the Connectors reference, the connector files, TLS and proxy, the developer guide, a design document per store (Delta Lake, PostgreSQL, Aerospike, files …) and demo data |
| [admin/](admin/) | running Drishti: operations, configuration, users and roles, performance, and the runbooks |
| [qa/](qa/) | quality assurance runs: each with its activity logs, findings by severity, and the scripts that reproduce them |
| [architecture/](architecture/) | how Drishti is built: the architecture, design notes (inference, live updates, Rūpaka, Elements, collaboration, the AI layer), the implementation plan and the decision records (ADRs) |
| [requirements/](requirements/), [publications/](publications/) | the reference mockups and logo; the design-and-evidence presentation |

## If you want to…

### Use Drishti

| If you want to… | Read |
|---|---|
| Install and start it, from a clean machine | [GETTING_STARTED.md](guides/GETTING_STARTED.md) |
| Learn it by doing, from your first 15 minutes | [tutorials/](tutorials/README.md) |
| Tell Drishti a batch of data has landed (API, CLI, Airflow, cron, DishtaYantra), flag late data | [DATA_LOADS.md](guides/DATA_LOADS.md) |
| Open business intelligence: `RUPAKA <GO>`, the catalogue, workspaces, publish and review, certify | [BI_GUIDE.md](guides/BI_GUIDE.md) |
| Learn BI by building a report, then making it run itself, live, governed, in Python and with AI | [tutorials 10 to 15](tutorials/README.md) |
| Read a dataset model, query it, see row rules and masks at work with four people, start a model from the packs | [BI_RECIPES.md](guides/BI_RECIPES.md) |
| Write or read a report: pages, visuals, wells, filters, slicers, drill-down and drill-through, the viewer, how a visual becomes a query, DRS-9030 to 9035 | [REPORTS.md](guides/REPORTS.md) |
| Publish BI safely: versions and diffs, review, certification, lineage, impact of a dataset change, bundles between environments, DRS-9040 to 9042 | [BI_GOVERNANCE.md](guides/BI_GOVERNANCE.md) |
| Export a report or view as PDF, PNG, Excel or CSV; subscribe people by email, each with their own masks, DRS-9060 and 9061 | [DISTRIBUTION.md](guides/DISTRIBUTION.md) |
| Build live BI: boards (a wall of live tiles, kiosk, tile alerts), live tables, live visuals, Perspective, DRS-9050 to 9053 | [BOARDS.md](guides/BOARDS.md) |
| Write or read a dataset: tables, relationships, measures, the expression language, row rules, masks, layers | [DATASETS.md](guides/DATASETS.md) |
| Ship packs, Sutras and data to servers by copying: bundle, verify, deploy, promote, roll back | [OPERATIONALISING.md](guides/OPERATIONALISING.md) |
| Install and run it on Windows (PowerShell, no Hadoop) | [GETTING_STARTED.md: Windows](guides/GETTING_STARTED.md#drishti-on-windows) |
| Run and debug the server in IntelliJ IDEA and the console in PyCharm | [IDE_GUIDE.md](guides/IDE_GUIDE.md) |
| Build a pack from JSON Lines, check it in CI, load data and drive the Screen Designer's review from a terminal | [CLI_GUIDE.md](guides/CLI_GUIDE.md) |
| Find the right tool (command, script or page) for a job, load a large JSON export into a Delta lake, schedule loads and maintenance | [TOOLS.md](guides/TOOLS.md) |
| Find your way around the top bar and its menus | [USER_GUIDE.md › The top bar](guides/USER_GUIDE.md#the-top-bar) |
| Read Drishti from a script, a notebook or Excel with a personal API token | [CLIENTS.md](guides/CLIENTS.md) |
| Run Python on the view you are looking at (`Alt+C`): re-price, recompute, pivot, chart | [PYTHON_CALC.md](guides/PYTHON_CALC.md) |
| Open a view, and learn the command line and keys | [USER_GUIDE.md › The command line](guides/USER_GUIDE.md#the-command-line), [Keyboard](guides/USER_GUIDE.md#keyboard) |
| List entities to pick from (`TRD MX-200000`, `CPTY north`, `TRD productType=Revolver`) | [USER_GUIDE.md › Pick lists](guides/USER_GUIDE.md#pick-lists-when-a-command-names-several-entities) |
| Page through a table, or walk it with the keyboard | [USER_GUIDE.md › Tables](guides/USER_GUIDE.md#tables-sorting-filtering-paging-and-the-keyboard) |
| Filter a table like Excel: tick values, conditions, dates, top 10 | [USER_GUIDE.md › Filter a table like Excel](guides/USER_GUIDE.md#filter-a-table-like-excel) |
| Understand what a view is showing you | [USER_GUIDE.md › Reading a view](guides/USER_GUIDE.md#reading-a-view) |
| Find entities by value (`TRD where mtm > 1m …`) | [USER_GUIDE.md › Search by value](guides/USER_GUIDE.md#search-by-value) |
| Fill in a form instead of writing a search (`FORM trading <GO>`, Forms in the Views menu) | [QUERY_BY_FORM.md](guides/QUERY_BY_FORM.md) |
| Choose what a panel shows with dropdowns, toggles and sliders; read a trade down to its fixings | [USER_GUIDE.md › Controls](guides/USER_GUIDE.md#controls-on-a-screen-choose-what-a-panel-shows), [A deep trade](guides/USER_GUIDE.md#a-deep-trade-legs-cashflows-and-fixings) |
| Read what is in the economics and politics-society packs, where it comes from and under what licence (IMF data: internal demonstrations only) | [REAL_DATA.md](guides/REAL_DATA.md) |
| Look at a past date, or compare two dates | [USER_GUIDE.md › Business dates](guides/USER_GUIDE.md#business-dates-live-or-a-day-in-the-past), [Compare](guides/USER_GUIDE.md#compare-what-changed) |
| See what depends on an entity (F8) | [Impact guide](guides/USER_GUIDE.md#f8--impact) |
| Download CSV or JSON, print, or share a link | [USER_GUIDE.md › Export, print and share](guides/USER_GUIDE.md#export-print-and-share) |
| Send a view to a colleague with a note, read your inbox, discuss a number | [USER_GUIDE.md › Share a view with a note](guides/USER_GUIDE.md#share-a-view-with-a-note), [Your inbox](guides/USER_GUIDE.md#your-inbox), [Discussion](guides/USER_GUIDE.md#discussion) |
| Follow a share and a comment end to end (rights, masks, audit, retention) | [HOW_IT_FITS.md §3.10](architecture/HOW_IT_FITS.md#310-share-and-discussion-end-to-end) |
| Watch a list live, or be alerted when a figure crosses a line | [Monitors and alerts guide](guides/USER_GUIDE.md#monitors) |
| Put several views on one screen | [Workspaces guide](guides/USER_GUIDE.md#workspaces) |
| Change your theme, landing page or password | [USER_GUIDE.md › Your settings](guides/USER_GUIDE.md#your-settings) |
| Choose which packs you see | [USER_GUIDE.md › Domain packs](guides/USER_GUIDE.md#domain-packs-choosing-what-you-see) |
| Ask a model about the page you are on (off unless an administrator turns it on); connect model providers and MCP servers | [USER_GUIDE.md › Ask about this page](guides/USER_GUIDE.md#ask-about-this-page-when-your-administrator-has-turned-it-on), [AI_PROVIDERS.md](architecture/AI_PROVIDERS.md), [MCP_CLIENT.md](architecture/MCP_CLIENT.md) |
| Find the commands for a pack | [PACKS.md › The packs that ship](guides/PACKS.md#the-packs-that-ship), and the pack's own guide under *Help → Domain packs* |

### Change how screens look

| If you want to… | Read |
|---|---|
| Learn to build screens in the workbench, step by step, with a picture of each step | [tutorial 6](tutorials/06-design-a-screen.md) |
| Learn what a Sutra is and write one, from the first panel to a tested screen | [SUTRA_DEVELOPER_GUIDE.md](guides/SUTRA_DEVELOPER_GUIDE.md) |
| Choose or configure a panel kind | [PANELS.md](guides/PANELS.md) |
| Add a new panel kind to Drishti | [PANELS.md: developing a panel kind](guides/PANELS.md#developing-a-panel-kind) |
| Edit a Sutra with live preview and submit it for review | [Screen designer guide](guides/SCREEN_DESIGNER.md) |
| Lay out nested documents (lists inside lists) | [Nested documents tutorial](guides/NESTED_DOCUMENTS.md) |
| Choose the right panel kind | [PANELS.md](guides/PANELS.md) |
| Build a new panel kind, end to end (grammar, engine, console, workbench, tests) | [PANELS.md: developing a panel kind](guides/PANELS.md#developing-a-panel-kind) |
| Look up a key, format, expression or problem code | [RACHANA_REFERENCE.md](guides/RACHANA_REFERENCE.md) |
| Get completion for Sutras in your own editor (the JSON Schema of the language) | [API_GUIDE.md](guides/API_GUIDE.md#catalogue-about-packs-sources-sutras) (`GET /api/v1/rachana/schema`) |
| Convert Markdown Sutras (`*.sutra.md`) from before 1.11 | [runbooks/sutra-broken.md](admin/runbooks/sutra-broken.md#step-1a-a-sutramd-or-plain-yaml-file-drs-2004-drs-2009) |
| See how connectors, packs and Sutras fit together, and how the UI is bound to the backend at runtime | [HOW_IT_FITS.md](architecture/HOW_IT_FITS.md) |
| Understand the screens Drishti draws with no Sutra | [INFERENCE.md](architecture/INFERENCE.md) |
| Design of the Screen Builder (JSON files → shape → visual designer → Sutra) | [SCREEN_BUILDER.md](architecture/SCREEN_BUILDER.md) |
| The Build workbench: Designs, one page for data, canvas, YAML and tests, and the plan | [BUILD_WORKBENCH.md](architecture/BUILD_WORKBENCH.md) |
| Design of *About this page*: context-aware help built from the page's data, pack glossaries, the explain API and the plan | [CONTEXT_HELP.md](architecture/CONTEXT_HELP.md) |
| Design of Drishti Rūpaka, BI inside Drishti benchmarked against Power BI, and what its phase 0 proof of concept measured | [RUPAKA.md](architecture/RUPAKA.md), [RUPAKA_POC.md](architecture/RUPAKA_POC.md) |
| **The BI target design** (authoritative): principles, the four languages (YAML, the Python-like expression language, Drishti SQL, Python) and the one logical plan, query, security, scale, governance, authoring UX, AI, APIs, the Power BI gap check and the waves A, B, C | [BI_DESIGN.md](architecture/BI_DESIGN.md) |
| Collaboration: share a view with a note, comment threads anchored to the data, the inbox, email, retention and legal hold; the design, what was built and the plan | [COLLABORATION.md](architecture/COLLABORATION.md) |
| Show Drishti's live views and BI reports inside another web application without iframes (Drishti Elements: `<drishti-view>`, `<drishti-panel>`, `<drishti-report>`, token exchange, npm package) | [guides/ELEMENTS.md](guides/ELEMENTS.md) (developer guide), [architecture/ELEMENTS.md](architecture/ELEMENTS.md) (design) |
| Arrange a report for a phone or a tablet (`layouts.phone`, `layouts.tablet`: order, hide, height, wide; the designer's Phone and Tablet tabs) | [REPORTS.md](guides/REPORTS.md#14-phone-and-tablet-layouts) |
| Say what a number should be (`tests:`: expected values that Check runs as a role and that stop a publish) | [REPORTS.md](guides/REPORTS.md#17-expected-values-tests) |
| Show "last 30 days" or "this quarter to date" (a relative-date slicer, business-day aware) | [REPORTS.md](guides/REPORTS.md#61-the-relative-date-slicer) |
| Rank, lag and moving averages in a measure (window functions) | [DATASETS.md](guides/DATASETS.md#43-window-functions) |
| Put live BI results (aggregates, pivots, rankings streamed by Pravaha, Flink or a custom BI server) in front of people as live views and boards | [LIVE_BI.md](architecture/LIVE_BI.md) |

### Add an industry or data

| If you want to… | Read |
|---|---|
| Build a pack, from an empty folder to a working view | [PACK_DEVELOPER_GUIDE.md](guides/PACK_DEVELOPER_GUIDE.md) |
| Make a pack from JSON Schema files and/or a folder of JSON Lines (Build → New pack, or the command line) | [SCHEMA_TO_PACK.md](guides/SCHEMA_TO_PACK.md) |
| Load packs, switch them off and on, assign them to users | [PACKS.md](guides/PACKS.md#turning-packs-on) |
| Generate a full pack from Python (Sutras, samples, guide, lake) | [PACK_DEVELOPER_GUIDE.md › Generators](guides/PACK_DEVELOPER_GUIDE.md) |
| Define a connector as a file, change it without a restart, manage it in Admin → Connectors | [CONNECTOR_FILES.md](connectors/CONNECTOR_FILES.md) |
| Choose a connector, write a new plugin and test it | [CONNECTOR_DEVELOPER_GUIDE.md](connectors/CONNECTOR_DEVELOPER_GUIDE.md) |
| Choose a connector and see a connector file for each plugin | [CONNECTORS.md](connectors/CONNECTORS.md) (a section per plugin; each links to its full document under [connectors/](connectors/)) |
| Connect your own data by doing it once | [tutorial 4](tutorials/04-connect-your-data.md) |
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
| Know how many users a server holds, whether memory stays flat over a day, and how to load-test it | [LOAD_AND_MEMORY.md](admin/LOAD_AND_MEMORY.md) |
| Compare the stores at several sizes, see how each scales, and run the scale benchmark yourself | [SCALE_BENCHMARK.md](admin/SCALE_BENCHMARK.md) |
| Serve millions of entities a day for years from Delta Lake | [DELTA_CONNECTOR.md](connectors/DELTA_CONNECTOR.md) |
| Run the server on Windows, or choose the Delta engine (native or Hadoop) | [GETTING_STARTED.md: Windows](guides/GETTING_STARTED.md#drishti-on-windows), [DELTA_CONNECTOR.md › Engines](connectors/DELTA_CONNECTOR.md#16-engines-native-and-hadoop) |
| Serve millions of entities a day from Aerospike, with recent history there and years in Delta Lake | [AEROSPIKE_CONNECTOR.md](connectors/AEROSPIKE_CONNECTOR.md) |
| Serve millions of entities a day from PostgreSQL | [POSTGRES_CONNECTOR.md](connectors/POSTGRES_CONNECTOR.md) |
| Serve data from plain JSON-lines files, the simplest store | [FILE_CONNECTOR.md](connectors/FILE_CONNECTOR.md) |
| Serve documents kept as JSON objects in S3, MinIO or another S3-compatible store | [S3_CONNECTOR.md](connectors/S3_CONNECTOR.md) |
| Read entities from an in-house HTTP/JSON service, one request per view | [REST_CONNECTOR.md](connectors/REST_CONNECTOR.md) |
| Show live entities from Kafka topics, with history from a lake | [KAFKA_CONNECTOR.md](connectors/KAFKA_CONNECTOR.md) |
| Send connectors, AI providers and MCP servers through an outbound HTTP proxy (login, no-proxy list, https via CONNECT) | [TLS.md: proxy](connectors/TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers) |
| Connect a connector over TLS: private CA, PEM or PKCS12/JKS files, mutual TLS, Confluent Cloud, SASL | [TLS.md](connectors/TLS.md), [KAFKA_CONNECTOR.md › Security](connectors/KAFKA_CONNECTOR.md#11-security-tls-sasl-and-confluent) |
| Show live entities from ActiveMQ queues or topics | [ACTIVEMQ_CONNECTOR.md](connectors/ACTIVEMQ_CONNECTOR.md) |
| Show live entities from RabbitMQ queues | [RABBITMQ_CONNECTOR.md](connectors/RABBITMQ_CONNECTOR.md) |
| Serve public rates and FX (NY Fed SOFR, ECB €STR and FX, US Treasury, FRED) | [FEEDS_CONNECTOR.md](connectors/FEEDS_CONNECTOR.md) |
| Write a connector that computes its data on demand, and see two (news headlines for a name, a quote for a ticker) | [NEWS.md](connectors/NEWS.md), [YFINANCE.md](connectors/YFINANCE.md), [CONNECTOR_DEVELOPER_GUIDE.md](connectors/CONNECTOR_DEVELOPER_GUIDE.md#connectors-that-compute-data-on-demand) |
| Switch on the Yahoo Finance quote connector | [YFINANCE.md](connectors/YFINANCE.md) |
| See what the sample connector serves and how to switch it off | [DEMO_CONNECTOR.md](connectors/DEMO_CONNECTOR.md) |
| Connect your data in the short version (a connector file) | [CONNECTORS.md](connectors/CONNECTORS.md) |
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

Every file under `docs/`, by folder. Documents marked *moved* are short pointers kept so old links still open.

### Guides (`guides/`)

| Document | Read this when… |
|---|---|
| [GETTING_STARTED.md](guides/GETTING_STARTED.md) | you want Drishti running in ten minutes and need only the commands |
| [GETTING_STARTED.md](guides/GETTING_STARTED.md) | you are installing for the first time and want each step explained, with what you should see |
| [IDE_GUIDE.md](guides/IDE_GUIDE.md) | you run the server from IntelliJ IDEA and the console from PyCharm: JDK and interpreter setup, run configurations, debugging, reloading changes, tests in the IDE |
| [GETTING_STARTED.md: Windows](guides/GETTING_STARTED.md#drishti-on-windows) | you run Drishti on Windows: a JDK 21 or newer (production runs 21) and Python, building or copying the jar, the native Delta engine (no Hadoop, no `winutils.exe`), the PowerShell scripts, what does not work there, troubleshooting |
| [USER_GUIDE.md](guides/USER_GUIDE.md) | you use the console: top bar, command line, pick lists, tables, views, dates, search, export, monitors, alerts, workspaces, settings, the Build workbench, administration |
| [TROUBLESHOOTING.md](guides/TROUBLESHOOTING.md) | something is not working: each problem has what you see, how to check and the fix |
| [CLIENTS.md](guides/CLIENTS.md) | you read Drishti from a script, a notebook or Excel: personal API tokens and scopes, the Python client, Power Query |
| [PYTHON_CALC.md](guides/PYTHON_CALC.md) | you run Python on a view (`Alt+C`): the `drishti` module, output, packs' snippets, roles, the security model, installing the runtime, measured sizes and speed, limits |
| [PACKS.md](guides/PACKS.md) | you load, switch, assign or change a domain pack |
| [PACK_DEVELOPER_GUIDE.md](guides/PACK_DEVELOPER_GUIDE.md) | you build a pack or need any `pack.yaml` key: kinds, links, roles, Sutras, tests, generators, signing; a worked help-desk pack |
| [SCHEMA_TO_PACK.md](guides/SCHEMA_TO_PACK.md) | you have JSON Schema (and perhaps JSON Lines) and want a whole pack: the rules, the `x-drishti-*` annotations, Build → New pack, the command line |
| [CLI_GUIDE.md](guides/CLI_GUIDE.md) | you work from a terminal or a pipeline: `tools/drishti.py` (`sutra`, `pack new|check|about-check|publish|install`, `data ingest|load|landed|loads`, `server packs`, `design create|check|propose|approve`), every option with real output, recipes, JSON formats, exit codes |
| [DATA_LOADS.md](guides/DATA_LOADS.md) | your ETL has loaded a batch and Drishti should refresh, verify, check alerts, tell people and flag late data: the load API and CLI, expectations, the Data loads page, the token scope, recipes |
| [OPERATIONALISING.md](guides/OPERATIONALISING.md) | you ship what you built to servers by copying: bundle, verify, deploy, promote, roll back (no API) |
| [REAL_DATA.md](guides/REAL_DATA.md) | you use or refresh the real public data in the economics and politics-society packs: sources, licences (IMF data: internal demonstrations only), the pipeline that regenerates them |
| [BI_GUIDE.md](guides/BI_GUIDE.md) | what BI is and is not built, the BI menu and keyword, the four powers, workspace roles, items and versions, audiences, shipped files, BI settings, the CLI |
| [tutorial 10](tutorials/10-first-bi-report.md) | build a report from scratch in the designer (cards, a map, a bar, a slicer), view, click and drill, publish, filter like Power BI, slicers across packs, a phone, two datasets in one report, relative dates, a tablet and the entity view |
| [DATASETS.md](guides/DATASETS.md) | the YAML reference, aggregates and functions, security, modes, layers and rollups, the query, DRS-9010 to 9027, what is not built |
| [REPORTS.md](guides/REPORTS.md) | the YAML reference, the 30 visuals and their wells (Vega-Lite and Perspective charts included), filters and top-N, format, how the page's state becomes a dataset query, what people can do, checking, security, what is not built |
| [BI_GOVERNANCE.md](guides/BI_GOVERNANCE.md) | saved states and published versions, the diff, who reviews and what they see, when certification falls, what impact analysis compares, the bundle and its dry run, the audit actions, what is not built |
| [BOARDS.md](guides/BOARDS.md) | the board file and its tiles, alerts, the viewer and kiosk, who sees what, the checks and their codes, settings, the API, what is not built (live tables: [DATASETS.md](guides/DATASETS.md#71-live-tables-phase-5); live visuals and Perspective: [REPORTS.md](guides/REPORTS.md#13-live-visuals-and-perspective-phase-5)) |
| [tutorial 6](tutorials/06-design-a-screen.md) | you are new to the Build workbench: four complete projects with a picture of each step |
| [SCREEN_DESIGNER.md](guides/SCREEN_DESIGNER.md) | you design or edit a Sutra in the browser, save it and submit it for review |
| [SUTRA_DEVELOPER_GUIDE.md](guides/SUTRA_DEVELOPER_GUIDE.md) | you are learning to write Sutras: anatomy, matching, labels, paths, source, row groups, testing and CI, the checklist |
| [RACHANA_REFERENCE.md](guides/RACHANA_REFERENCE.md) | you are writing a Sutra and need the exact key, panel option, format, expression or problem code |
| [PANELS.md](guides/PANELS.md) | you choose, configure or debug a panel: all twenty-two kinds, what each means, how it behaves, an example and a picture |
| [PANELS.md: developing a panel kind](guides/PANELS.md#developing-a-panel-kind) | you add a new panel kind to Drishti |
| [DEVELOPER_GUIDE.md](guides/DEVELOPER_GUIDE.md) | you change Drishti's code: layout, build, tests, gates, and recipes |
| [API_GUIDE.md](guides/API_GUIDE.md) | you call the REST API from a program, or need the ViewModel contract |

### Pointers to guides that moved

| Document | Read this when… |
|---|---|
| [PANELS.md](guides/PANELS.md) | you followed an old link to the panels guide (moved to PANEL_KINDS.md) |
| [SUTRA_DEVELOPER_GUIDE.md](guides/SUTRA_DEVELOPER_GUIDE.md) | you followed an old link to the Rachana guide (moved to SUTRA_DEVELOPER_GUIDE.md) |
| [SUTRA_DEVELOPER_GUIDE.md](guides/SUTRA_DEVELOPER_GUIDE.md#15-testing-expectyaml-sutra-linttestpreview-ci) | you followed an old link to the headless Sutra tool (moved to SUTRA_DEVELOPER_GUIDE.md section 15) |

### Architecture and design (`architecture/`)

| Document | Read this when… |
|---|---|
| [HOW_IT_FITS.md](architecture/HOW_IT_FITS.md) | you are new and want the whole working: how connectors, packs and Sutras relate, and how a command becomes a live screen |
| [ARCHITECTURE.md](architecture/ARCHITECTURE.md) | you want to understand how the pieces fit: pipeline, grammar, inference, graph, modules |
| [INFERENCE.md](architecture/INFERENCE.md) | a view looks different from what you expected and *How this view was built* says `inference` |
| [LIVE.md](architecture/LIVE.md) | you need to know how values tick: streams, frames, coalescing, reconnects, one channel per browser, shared by its tabs |
| [SCREEN_BUILDER.md](architecture/SCREEN_BUILDER.md) | you want the design of the Screen Builder: JSON files to a shape, to a visual design, to a Sutra |
| [BUILD_WORKBENCH.md](architecture/BUILD_WORKBENCH.md) | you want the design of the Build workbench: Designs, one page for data, canvas, YAML and tests, and the plan |
| [CONTEXT_HELP.md](architecture/CONTEXT_HELP.md) | you want the design of *About this page*: the page's data, pack glossaries and `about.yaml`, the explain API, the Ask layer and the plan |
| [COLLABORATION.md](architecture/COLLABORATION.md) | you want to know how *share with a note* and comment threads work and why: pins to the business date and generation, recipients' own rights, masks on user text, the inbox and email outbox, retention, legal hold, export, with a note on what was built differently from the design (steps 1 to 7 built; chat bridges and snapshots, phase 2, are not) |
| [RUPAKA.md](architecture/RUPAKA.md) | you want the design of Drishti Rūpaka, BI inside Drishti benchmarked against Power BI: the BI page (`RUPAKA <GO>`), datasets, the query engine, reports, the designer, workspaces and catalogue, streaming BI, Python interfaces, AI hooks off by default, build plan (design) |
| [BI_MODEL_ARCHITECTURE.md](architecture/BI_MODEL_ARCHITECTURE.md) | you want the agreed BI architecture after 2.1.0 (datasets, data models, one model per report, time modes, one model query language, AI through the model); its waves are replaced by BI_DESIGN.md |
| [BI_DESIGN.md](architecture/BI_DESIGN.md) | you want the single authoritative BI target design: principles and non-goals, the Python-like expression language, Drishti SQL, Python and MQ meeting in one logical plan, query architecture, security at every tier, caching and scale, governance, the designers, AI, APIs, the Power BI gap check, W1 as built and the waves A (2.2.0), B (2.3.0), C (2.4.0) |
| [BI_MODEL_SPEC.md](architecture/BI_MODEL_SPEC.md) | you want the design specification of wave W1: the dataset and data model YAML, implicit models, how consumers bind to one model, the model query language v1 (validation, compilation, standing queries, ten worked examples), pack-generated datasets, the migration of every sample, the UX flows and the W1 build plan (design, not built) |
| [BI_PARITY_PLAN.md](architecture/BI_PARITY_PLAN.md) | you want the proposed plan to bring Rūpaka to Power BI's feature depth: what Drishti already does better, the gaps ranked by value and effort, the redesigns and the phases 2.2 to 3.0 (a proposal, not yet scheduled) |
| [RUPAKA_POC.md](architecture/RUPAKA_POC.md) | you want what the Rūpaka phase 0 proof of concept built and measured (Perspective live grid, masked Arrow out of DuckDB, DuckDB-Wasm in the browser, CSP with WebAssembly, JSON vs typed vs rollup) and the go / adjust verdict for phase 1 (off by default) |
| [LIVE_BI.md](architecture/LIVE_BI.md) | you want Drishti as the live front end of a streaming BI producer: the result-stream contract, Kafka with no code, a `bi` plugin, boards, drill-down, consistency (design note) |
| [ELEMENTS.md](architecture/ELEMENTS.md) | you want the design of Drishti Elements: live views and BI reports inside other web applications as Web Components, no iframes; the embed API, token exchange, host registration, `<drishti-report>`, and which build steps are done |
| [guides/ELEMENTS.md](guides/ELEMENTS.md) | you are embedding Drishti in your own application: a five-minute start, the token exchange (Node, Java, Python), versions, SRI and npm, the element reference, a cookbook, troubleshooting by code |
| [AI_PROVIDERS.md](architecture/AI_PROVIDERS.md) | you configure or build on the model providers: one interface, one file per provider in `config/ai/`, off by default, the admin page and the API |
| [MCP_CLIENT.md](architecture/MCP_CLIENT.md) | you connect MCP servers (their tools, resources and prompts) behind Drishti's policy: one file per server in `config/mcp/`, off by default |
| [QUANT_SERVICE.md](architecture/QUANT_SERVICE.md) | you want the design of server-side pricing (QuantLib) and how it would reach the screen (proposed, not built; ADR-019) |
| [IMPLEMENTATION_PLAN.md](architecture/IMPLEMENTATION_PLAN.md) | you want to know which waves shipped in which release, and what is still open |

### Connectors (`connectors/`)

| Document | Read this when… |
|---|---|
| [CONNECTORS.md](connectors/CONNECTORS.md) | you want the short version of connecting data: choose a connector, write it as a connector file |
| [CONNECTOR_DEVELOPER_GUIDE.md](connectors/CONNECTOR_DEVELOPER_GUIDE.md) | you choose a connector, write a new source plugin, or test one with the testkit contracts; each connector's settings and walk-through are in its own `*_CONNECTOR.md` |
| [CONNECTOR_FILES.md](connectors/CONNECTOR_FILES.md) | you define connectors: one YAML file each in `config/connectors`, an example per store, how packs and files fit, live reload, Admin → Connectors, the API and CLI, moving off `application.yaml` |
| [TLS.md](connectors/TLS.md) | a connector must talk TLS: the shared `tls.*` settings, PEM, PKCS12 and JKS, mutual TLS, converting certificates, expiry warnings, rotation, troubleshooting |
| [TLS.md: proxy](connectors/TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers) | a site reaches the internet only through a forward proxy: `proxy:` per connector, AI provider or MCP server, the site default `drishti.http.proxy.*`, no-proxy, https via CONNECT, masking, what each connector does, troubleshooting |
| [DEMO_CONNECTOR.md](connectors/DEMO_CONNECTOR.md) | you want to know what the sample connector serves, how it ticks, how packs supply samples, and how to switch it off |
| [DEMO_DATA.md](connectors/DEMO_DATA.md) | you need demo data in Delta Lake, PostgreSQL or Aerospike, small for a laptop or a million trades a day |
| [FILE_CONNECTOR.md](connectors/FILE_CONNECTOR.md) | you serve data from JSON-lines files: the layout, one file per kind per day, the index of a day, loading, sizes, measurements |
| [DELTA_CONNECTOR.md](connectors/DELTA_CONNECTOR.md) | you run Drishti over a large Delta Lake (a million trades a day for seven years): layout, writers, reads, memory, maintenance, measurements, and the two engines (native without Hadoop, or Hadoop) |
| [ICEBERG_CONNECTOR.md](connectors/ICEBERG_CONNECTOR.md) | you run Drishti over Apache Iceberg: the layout, path-based and REST catalogs, delete files, time travel, the loader and maintenance, sizing, measurements |
| [DUCKDB_CONNECTOR.md](connectors/DUCKDB_CONNECTOR.md) | you run Drishti on one embedded DuckDB file: the layout, the loader and its file swap, the shared read-only instance, read paths, retention, sizing, memory, measurements |
| [POSTGRES_CONNECTOR.md](connectors/POSTGRES_CONNECTOR.md) | you run Drishti on PostgreSQL at scale: partitioned table, promoted columns, COPY loader, monthly retention, sizing, measurements |
| [JDBC_QUERIES.md](connectors/JDBC_QUERIES.md) | you read your own schema with SQL: the entity, its parts, ids, columns and reverse queries, parameters, naming, performance |
| [MONGODB_CONNECTOR.md](connectors/MONGODB_CONNECTOR.md) | you run Drishti on MongoDB: a document per entity per day, the narrow columns collection, indexes, parallel day reads, retention, sharding, measurements |
| [REDIS_CONNECTOR.md](connectors/REDIS_CONNECTOR.md) | you run Drishti on Redis: key layout, zstd dictionaries, column chunks, live pub/sub, TTL, sizing, measurements |
| [AEROSPIKE_CONNECTOR.md](connectors/AEROSPIKE_CONNECTOR.md) | you run Drishti on Aerospike at scale: record layout, loader, partition-parallel scans, TTL retention, sizing, measurements |
| [S3_CONNECTOR.md](connectors/S3_CONNECTOR.md) | you serve documents from S3 or an S3-compatible store: the key layout, listing and caching, credentials, cost of large buckets |
| [REST_CONNECTOR.md](connectors/REST_CONNECTOR.md) | you read entities from an HTTP/JSON service: paths, headers, generations, timeouts, what it cannot do, load on the service |
| [FEEDS_CONNECTOR.md](connectors/FEEDS_CONNECTOR.md) | you switch on the public feeds: each source's URL and parsing, entities and ids, schedules, history, offline mirrors |
| [NEWS.md](connectors/NEWS.md) | you switch on the news connector or write one like it: ids, the filtering steps, settings, privacy, failures, how it is built |
| [YFINANCE.md](connectors/YFINANCE.md) | you switch on the Yahoo Finance quote connector (samples only): ids, figures, settings, failures |
| [KAFKA_CONNECTOR.md](connectors/KAFKA_CONNECTOR.md) | you serve live entities from Kafka: state and ticks modes, the index and disk cache, offsets, ordering, restarts, reconnection, pairing with a lake; TLS, SASL, Confluent and Schema Registry |
| [ACTIVEMQ_CONNECTOR.md](connectors/ACTIVEMQ_CONNECTOR.md) | you serve live entities from ActiveMQ: queues and durable topics, the state store, its durability and disk budget, acknowledgement, failover, sizing |
| [RABBITMQ_CONNECTOR.md](connectors/RABBITMQ_CONNECTOR.md) | you serve live entities from RabbitMQ: queues and bindings, prefetch and acknowledgement, the state store, its durability and disk budget, recovery, sizing |
| [CONNECTOR_DEVELOPER_GUIDE.md](connectors/CONNECTOR_DEVELOPER_GUIDE.md) | you followed an old link to the plugin guide (moved to CONNECTOR_DEVELOPER_GUIDE.md) |

### Running it (`admin/`)

| Document | Read this when… |
|---|---|
| [USER_MANAGEMENT.md](admin/USER_MANAGEMENT.md) | you create users, define roles, assign packs, reset passwords, set up single sign-on or read the audit log |
| [CONFIGURATION.md](admin/CONFIGURATION.md) | you need a setting's name, default and environment variable |
| [OPERATIONS.md](admin/OPERATIONS.md) | you deploy, secure, monitor, back up or upgrade a server and console |
| [PERFORMANCE.md](admin/PERFORMANCE.md) | you want the measured numbers, to measure your own installation, or to tune it |
| [LOAD_AND_MEMORY.md](admin/LOAD_AND_MEMORY.md) | you need to know how many users a server holds, whether memory stays flat over a day, and how to load-test and memory-profile the server, the console and the browser (strategy, tools, gates, build plan) |
| [SCALE_BENCHMARK.md](admin/SCALE_BENCHMARK.md) | you choose a store or size a server: every store measured at 10,000, 25,000 and 50,000 trades a day, linear scaling fits, labelled extrapolations to a million, and the harness (`tools/bench/`) to run it at a million on a real server |

### Runbooks (`admin/runbooks/`)

| Runbook | Read this when… |
|---|---|
| [source-down.md](admin/runbooks/source-down.md) | a connector is down, slow, idle or failed to start; views fail with `DRS-1003`/`DRS-1004`; links say *pending* |
| [sutra-broken.md](admin/runbooks/sutra-broken.md) | a Sutra has problems, an edit does not show, or a view says `inference only` where you expected a Sutra |
| [live-latency-high.md](admin/runbooks/live-latency-high.md) | live views are slow, late or frozen, or the Live button keeps its amber ring |
| [sign-in.md](admin/runbooks/sign-in.md) | users cannot sign in, are locked out, or are refused (`DRS-5010`, `DRS-5002`) |

### In-app guides (`../drishti-console/web/guides/`, also under *Help*)

| Guide | Read this when… |
|---|---|
| [nested-data.md](guides/NESTED_DOCUMENTS.md) | your documents hold lists inside lists |
| [impact.md](guides/USER_GUIDE.md#f8--impact) | you use or configure F8 impact |
| [monitors-and-alerts.md](guides/USER_GUIDE.md#monitors) | you set up watchlists and alert rules |
| [workspaces.md](guides/USER_GUIDE.md#workspaces) | you arrange several live views on one screen |
| [sharing-and-discussion.md](../drishti-console/web/guides/sharing-and-discussion.md) | you send a view with a note, read your inbox or discuss a number |
| [layout-mode.md](guides/USER_GUIDE.md#layout-mode-arrange-a-view-your-way) | you arrange a view's panels for yourself (`Alt+L`) |
| [pivot-tab.md](guides/USER_GUIDE.md#the-pivot-tab-slice-a-table-your-way) | you slice a table in the Pivot tab |
| [screen-builder.md](guides/SCREEN_DESIGNER.md#1-start-bring-data) | you turn sample JSON files into a shape, the first two steps of the builder |

### Examples (`guides/examples/`)

Eleven working Sutras with their data, introduced in [guides/examples/README.md](guides/examples/README.md); each has the Sutra, a JSON document and a note, and opens as a copy in the workbench.

| Note | Shows |
|---|---|
| [all-panels-showcase.md](guides/examples/all-panels-showcase.md) | All twenty-two panel kinds on one screen, reading one self-contained bond trade. |
| [exposure-profile.md](guides/examples/exposure-profile.md) | Expected and potential future exposure over tenor, against a limit. |
| [linked-sources.md](guides/examples/linked-sources.md) | Panels that read other entities with `source: "link(id, kind)"`: the counterparty's netting sets, a netting set's exposure and a desk's pivot, all fetched live. |
| [market-charts.md](guides/examples/market-charts.md) | Market data: daily bars, a line of closes and a volatility surface. |
| [metric.md](guides/examples/metric.md) | A desk's headline numbers as big-number tiles: one large formatted, toned figure each, with a small change, a unit and a caption. |
| [operations-status.md](guides/examples/operations-status.md) | The back office on one screen: where a trade stands and what comes next. |
| [pivot-row-groups.md](guides/examples/pivot-row-groups.md) | A pivot whose `by` is a list: desks hold books, books hold product families, each level with a subtotal. |
| [pnl-explain.md](guides/examples/pnl-explain.md) | Where a trade's P&L came from, and how sensitive it is. |
| [relationships.md](guides/examples/relationships.md) | How entities relate: a group and its agreements as a graph, netting sets as tabs, and the entities a document refers to. |
| [risk-distribution.md](guides/examples/risk-distribution.md) | How risk is spread: scenario P&L as a histogram with VaR lines, books as points, a limit as a dial. |
| [tree-table.md](guides/examples/tree-table.md) | Tables and ladders whose rows hold their own children: an organisation of units, each with sub-units, to any depth. |
| [pack/helpdesk/guides/helpdesk.md](guides/examples/pack/helpdesk/guides/helpdesk.md) | the help-desk teaching pack's own guide, built step by step in the pack developer guide |

### Decision records (`architecture/adr/`)

The index is [adr/README.md](architecture/adr/README.md); the 20 records:

| Record |
|---|
| [001-backend-is-a-spring-boot-application.md](architecture/adr/001-backend-is-a-spring-boot-application.md): ADR-001: Backend is a spring boot application |
| [002-layout-is-sutra-plus-inference.md](architecture/adr/002-layout-is-sutra-plus-inference.md): ADR-002: Layout is sutra plus inference |
| [003-sutra-is-yaml-with-a-closed-expression-language.md](architecture/adr/003-sutra-is-yaml-with-a-closed-expression-language.md): ADR-003: Sutra is yaml with a closed expression language |
| [004-server-returns-a-view-model.md](architecture/adr/004-server-returns-a-view-model.md): ADR-004: Server returns a view model |
| [005-server-sent-events-for-live-updates.md](architecture/adr/005-server-sent-events-for-live-updates.md): ADR-005: Server sent events for live updates |
| [006-vendored-front-end-without-a-build-pipeline.md](architecture/adr/006-vendored-front-end-without-a-build-pipeline.md): ADR-006: Vendored front end without a build pipeline |
| [007-build-gates-in-tests.md](architecture/adr/007-build-gates-in-tests.md): ADR-007: Build gates in tests |
| [008-rachana-is-the-grammar-a-sutra-is-a-layout.md](architecture/adr/008-rachana-is-the-grammar-a-sutra-is-a-layout.md): ADR-008: Rachana is the grammar, a Sutra is a layout |
| [009-users-live-in-the-server.md](architecture/adr/009-users-live-in-the-server.md): ADR-009: Users live in the server |
| [010-industries-are-domain-packs.md](architecture/adr/010-industries-are-domain-packs.md): ADR-010: Industries are domain packs |
| [011-sutras-are-markdown-documents.md](architecture/adr/011-sutras-are-markdown-documents.md): ADR-011: Sutras are Markdown documents |
| [012-business-dates-and-delta-lake.md](architecture/adr/012-business-dates-and-delta-lake.md): ADR-012: Every read is for a business date; history lives in Delta Lake |
| [013-sutras-go-live-through-review.md](architecture/adr/013-sutras-go-live-through-review.md): ADR-013: Sutras go live through review |
| [014-single-sign-on-verified-by-the-server.md](architecture/adr/014-single-sign-on-verified-by-the-server.md): ADR-014: Single sign-on, verified by the server |
| [015-packs-inherit.md](architecture/adr/015-packs-inherit.md): ADR-015: Packs inherit, and the more specific pack wins |
| [016-one-console-many-servers.md](architecture/adr/016-one-console-many-servers.md): ADR-016: One console, many servers; the user picks one and signs in to it |
| [017-sutras-are-yaml.md](architecture/adr/017-sutras-are-yaml.md): ADR-017: Sutras are YAML, through and through |
| [018-signed-pack-registry.md](architecture/adr/018-signed-pack-registry.md): ADR-018: Packs come from a signed, versioned registry |
| [019-pricing-is-a-quantlib-service-behind-the-server.md](architecture/adr/019-pricing-is-a-quantlib-service-behind-the-server.md): ADR-019: Pricing is a QuantLib service behind the Drishti server |
| [020-collaboration-records-live-in-the-identity-database.md](architecture/adr/020-collaboration-records-live-in-the-identity-database.md): ADR-020: Collaboration records live in the identity database; the notice is a link, never  |

### Quality assurance runs (`qa/`)

Each run has a README (what was tested, findings by severity, how each was fixed), activity logs and the scripts that reproduce the findings.

| Document | What it holds |
|---|---|
| [qa/2026-10-01/README.md](qa/2026-10-01/README.md) | 2026-10-01: the summary of the run |
| [qa/2026-10-01/data/FINDINGS.md](qa/2026-10-01/data/FINDINGS.md) | 2026-10-01/data: findings by severity, with steps to reproduce |
| [qa/2026-10-01/data/LOG.md](qa/2026-10-01/data/LOG.md) | 2026-10-01/data: the activity log |
| [qa/2026-10-01/security/FINDINGS.md](qa/2026-10-01/security/FINDINGS.md) | 2026-10-01/security: findings by severity, with steps to reproduce |
| [qa/2026-10-01/security/LOG.md](qa/2026-10-01/security/LOG.md) | 2026-10-01/security: the activity log |
| [qa/2026-10-01/security/matrix_console.md](qa/2026-10-01/security/matrix_console.md) | 2026-10-01/security: the console route by role access matrix |
| [qa/2026-10-01/security/matrix_server.md](qa/2026-10-01/security/matrix_server.md) | 2026-10-01/security: the server endpoint by role access matrix |
| [qa/2026-10-01/ux/FINDINGS.md](qa/2026-10-01/ux/FINDINGS.md) | 2026-10-01/ux: findings by severity, with steps to reproduce |
| [qa/2026-10-01/ux/LOG.md](qa/2026-10-01/ux/LOG.md) | 2026-10-01/ux: the activity log |
| [qa/2026-10-03/README.md](qa/2026-10-03/README.md) | 2026-10-03: the summary of the run |
| [qa/2026-10-03/data/FINDINGS.md](qa/2026-10-03/data/FINDINGS.md) | 2026-10-03/data: findings by severity, with steps to reproduce |
| [qa/2026-10-03/data/LOG.md](qa/2026-10-03/data/LOG.md) | 2026-10-03/data: the activity log |
| [qa/2026-10-03/security/FINDINGS.md](qa/2026-10-03/security/FINDINGS.md) | 2026-10-03/security: findings by severity, with steps to reproduce |
| [qa/2026-10-03/security/LOG.md](qa/2026-10-03/security/LOG.md) | 2026-10-03/security: the activity log |
| [qa/2026-10-03/ux/FINDINGS.md](qa/2026-10-03/ux/FINDINGS.md) | 2026-10-03/ux: findings by severity, with steps to reproduce |
| [qa/2026-10-03/ux/LOG.md](qa/2026-10-03/ux/LOG.md) | 2026-10-03/ux: the activity log |
| [qa/2026-10-05/BEHAVIOUR.md](qa/2026-10-05/BEHAVIOUR.md) | 2026-10-05: behaviour pass: collaboration, About/Ask, time, failure modes |
| [qa/2026-10-05/DATA_SAFETY.md](qa/2026-10-05/DATA_SAFETY.md) | 2026-10-05: data-safety pass: collaboration, explain, Ask, bridges, email, snapshots |

### Records

| Folder | Read this when… |
|---|---|
| [requirements/](requirements/) | you want the four reference mockups the first views reproduced, and the logo and icon |
| [publications/](publications/) | you want the design-and-evidence presentation (`Drishti-Design-and-Evidence.pptx`) |

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
| **Pack** | An industry's commands, layouts, links, roles and sample data, in `config/packs/<name>/` |
| **Source / connector** | Where documents come from: the demo samples, Delta Lake, PostgreSQL, Kafka, … A connector is one YAML file in `config/connectors` |
| **Rūpaka (BI)** | Business intelligence inside Drishti: datasets, reports, boards; the `BI` menu and keyword (`RUPAKA` is its older name) |
| **Form** | A pack's search form for a kind: `FORM <pack> <GO>` |
| **Business date** | The day you are looking at: **Live** (today, ticking) or a picked past date |
| **Known at** | A moment in time; the data as it was known then, before later corrections |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary and confidential.*
