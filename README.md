<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

<p align="center"><img src="docs/requirements/drishti-logo.png" alt="Drishti" width="560"></p>

# Drishti

**Any data. Any domain. One grammar.**

Drishti looks at data, analyses it and explains it. It is built from **three blocks**: a domain-free **kernel** (connectors and lakes,
screens written as short YAML **Sutras** in one grammar, **Rachana**, the pivot and BI engines, the cluster), a **console** in front of
one or many kernels, and **modules**, signed `.dmod` packages that bring an industry: banking brings a bank's risk, capital markets
brings listed companies and funds, the economy module brings economies, governments, central banks, official statistics and news. One
build makes several products, the **distributions**: Drishti Risk, Drishti Finance, Drishti Genomics (a sample) and Drishti All.

**For a bank, Drishti aggregates risk over the engines the bank already runs, explainably and under control.** Pricing and simulation
systems deliver trade-grain inputs (scenario P&L vectors, exposure paths, sensitivities); Drishti computes **VaR, ES, counterparty
exposure, the pre-deal check, FRTB-SA and SIMM on demand, for any scope** from the whole bank to an ad-hoc set of trades. Every figure
says where it came from, how it was calculated and why it moved; every figure obeys roles, masks, four eyes and an audit trail; and
nothing that is not built is shown as built.

The motto is **lean & streamlined**: one implementation of each job, one grammar for every expression, one set of JavaScript libraries
for every screen, one way to package a module.

**New to Drishti?** [HOW_IT_FITS.md](HOW_IT_FITS.md) ([PDF](HOW_IT_FITS.pdf)) shows how the pieces fit together, one picture per idea: the
three blocks, a request's journey, modules, data bindings, placement, a module's life, distributions and every configuration file.

```
VAR BANK <GO>                                  →  VaR and ES of the bank, down the hierarchy to a trade, contributions that add up
EXP CPTY:CP-ALDERSHOT <GO>                     →  exposure from the simulator's paths: EE, PFE, EPE, EEPE
PREDEAL CP-ALDERSHOT LIKE 1000001 250M <GO>    →  approve, refer or decline a new trade against the limit and its buckets
FRSA BANK <GO>                                 →  FRTB-SA capital: SBM, DRC and RRAO, by class and bucket
SIMM <GO>                                      →  initial margin per netting set, to collect and to post
PIVOT <GO>                                     →  any table or measure as a pivot, every total computed from its own members
WDI WDI-CAN <GO>                               →  a country's development indicators, with the world map by GDP per head
GENE TP53 <GO>                                 →  (Drishti Genomics) a gene's variants, drawn by the sample module's own visual
```

## Contents

[The three building blocks](#the-three-building-blocks) · [Modules](#modules) · [Build a module](#build-a-module) ·
[Deploy and place modules](#deploy-and-place-modules) · [Distributions](#distributions) · [Risk](#risk) · [Analyse](#analyse) ·
[Build](#build) · [Data in](#data-in) · [Data out](#data-out) · [Operate](#operate) · [Run it](#run-it) ·
[Measured performance](#measured-performance) · [Documentation](#documentation) · [Project facts](#project-facts) ·
[By the numbers](#by-the-numbers)

## The three building blocks

![The three building blocks: people use a console; the console calls one or many kernel nodes as the person; modules plug a console part into the console and a server part into the kernel; the kernel reads the site's lakes and sources through bindings and connectors](docs/how-it-fits/building-blocks.svg)

How they fit, step by step: [HOW_IT_FITS.md](HOW_IT_FITS.md). The design, with diagrams: [KERNEL_AND_MODULES.md](docs/architecture/KERNEL_AND_MODULES.md) ([PDF](docs/architecture/KERNEL_AND_MODULES.pdf)).

| Block | What it is | What it owns |
|---|---|---|
| **The kernel** | the server: Spring Boot on Java 21, domain-free | connectors and their bindings, lakes, data loads and `.ready` files, Rachana, the pivot, BI and aggregation engines, Explain, the access registry, the cluster (Drishti\*net) and placement, audit; it hosts the **server part** of each module (Java, Python compute, packs, REST) |
| **The console** | the web tier (Python, FastAPI), in front of one kernel or a cluster | the shell (header, lenses, themes, keys, Home, Help, search), Drishti Elements and the Sutra renderer, the BI and pivot screens, Admin; it hosts the **console part** of each module (declared pages, templates, static files, a domain Elements library) under `/m/<id>/` |
| **Modules** | `.dmod` packages, built apart from both | packs, Java and Python compute, functions, lenses, Home cards, Help, pages and visuals; a module **declares the data it needs and never brings connectors or credentials** |

The glue: one module runtime (`drishti-module-runtime`: reads and verifies a `.dmod`, one class loader per module, the live route table,
unloading) used by the kernel and the console alike; the **distribution file** (which modules, brand, theme, lenses, Home and Help); the
**Module SDK**; and **placement** (which nodes run which part of a module).

Two server jars come out of one build: the **full jar** (`drishti-server-*-exec.jar`, the default) is the kernel with the first-party
modules installed at the first start (`drishti.modules.shipped`), and the **thin jar** (`packaging/drishti-kernel-app/target/drishti-kernel-*-exec.jar`)
is the kernel alone. Maven modules depend in one direction: the kernel imports no domain and no module reaches another's code; a test
fails the build otherwise.

## Modules

**A `.dmod`** is a documented zip anyone can produce: `module.yaml` (the manifest: id, version, publisher, the kernel API it needs, its
parts, functions, lenses, data needs, API prefix) first, then the module's folders (`java/`, `console/`, `data/`, `packs/`, `docs/` …) and
`META-INF/DMOD.MF`, the SHA-256 of every file ([KERNEL_AND_MODULES.md section 3](docs/architecture/KERNEL_AND_MODULES.md#3-the-module-package-dmod)).
Before it loads, the kernel verifies the hash list and the manifest and runs the **fifteen conformance checks (C1–C15)**: no kernel
internals, declared jars only, contracts for its kinds, no leaked state after unload and the rest. A refused module is absent, never half
loaded.

- **First-party origin.** A module that says `origin: first-party` is checked against the `first-party.yaml` the build ships (its id and
  its hash list's hash). Only such a module may carry a `kernel-internals:` **allowance** (the kernel classes it may still reach, a list
  that only shrinks, honoured by C1 and its class loader), and a module with one loads only on the **exact kernel build** stamped into it
  ("built for kernel X, this is Y: install the matching module"). `module inspect`, `drishti.py module list` and Health show the origin.
- **On or off.** A module is on when it is installed, licensed and enabled. Switched off, it loads no engine, job, pack or API, and its
  screens, menus, commands, Help pages and Build items are gone for everyone
  ([ADMIN_GUIDE.md section 22](docs/guides/ADMIN_GUIDE.md#22-modules-on-or-off)).
- **Functions and lenses.** A function is a command word (`VAR`, `TRD`, `WDI` …), the finest unit of access, each switchable on its own
  ([ADMIN_GUIDE.md section 20](docs/guides/ADMIN_GUIDE.md#20-functions-func-every-command-and-turning-one-on-or-off)); a lens is what a
  person works in: its modules, functions, Home cards and chips ([ADMIN_GUIDE.md section 12](docs/guides/ADMIN_GUIDE.md#12-what-a-lens-is)).
  Who may do what is decided in one place, the access registry: licence → module → lens → function → permission
  ([ACCESS_MODEL.md](docs/architecture/ACCESS_MODEL.md)).
- **Gated functions explain themselves.** A function that is on but whose required data nothing serves opens the needs-data page
  (*Exposure needs data: exposure-path is not bound*, with links to Admin → Data bindings and Help); its API answers `409 DRS-1035` with
  the need and the command line says the same words.

**The modules that ship**: four first-party modules installed by default, each packed into a `.dmod` by the normal build (`packaging/dist`) and passing C1–C15:

| Module | Folder | Brings | Lenses |
|---|---|---|---|
| **banking** (the one with an allowance) | `modules/banking/` | VaR and ES (historical and parametric, backtests, the market-risk cube), counterparty exposure and the pre-deal check, sensitivities, FRTB-SA, SIMM, official results; the trading, market-risk and counterparty-risk packs; the drishti-risk library; requires market-data | Risk, Trading, CRO, the two risk cubes |
| **market-data** | `modules/market-data/` | prices, curves, volatilities and FX, and their functions; on whenever banking or capital markets is | Market |
| **capital-markets** | `modules/capital-markets/` | US equities (SEC EDGAR filings and facts, indices), funds and ETFs | Markets |
| **economy** | `modules/economy/` | economies, forecasts, governments and news; the public sector: World Bank indicators, central banks, Statistics Canada, the OECD, official press releases, GDELT world events; the World page | World, Public sector |
| **genomics** (a sample third party's module, not installed by default) | `modules/genomics/` | a VCF reader inside the module, the kinds `variant`, `sample`, `gene`, `cohort`, the functions `VARIANT`, `GENE`, `COHORT`, synthetic cohorts, its own visual | Research |

The kernel keeps what every domain uses: connectors and lakes, Sutras, search, the Pivot and BI, reports, tables and models, the Notebook,
approvals, alerts, Explain, Admin and Help, GLEIF's legal entities as reference data, and the CIO and Oversight lenses.

## Build a module

A module is built against the kernel API, apart from the kernel ([DEVELOPER_GUIDE.md section 1.6](docs/guides/DEVELOPER_GUIDE.md#16-the-module-sdk),
[KERNEL_AND_MODULES.md section 8](docs/architecture/KERNEL_AND_MODULES.md#8-the-module-sdk-and-the-developer-path)). **The Module SDK**
is the kernel API jar, the archetype `drishti-module-archetype` (a project that builds green on its first `mvn verify`: a function with its
route and page, a kind with its contract and data needs, a REST API, a Rachana function, a menu entry, a Home card, an AI tool, Help, a
guide, a tutorial and a test), the parent `drishti-module-parent`, the Maven plugin and the conformance suite. The packer and the checks are
one implementation, reached **three ways**:

| Way | For | Commands |
|---|---|---|
| **Maven** | a project from the archetype | `mvn verify`: `drishti:validate`, `drishti:assemble` (`target/dmod/<id>-<version>.dmod`), `drishti:conformance` |
| **The standalone tool** | Gradle, Bazel, a script, or `javac` and a folder | `java -jar drishti-module-tool.jar pack <folder> [--jar <jar>]`, then `check <.dmod>` and `inspect <.dmod>` |
| **The command line** | Linux, macOS and Windows, with Java and Python only | `python3 tools/drishti.py module pack <folder>`, `module check`, `module inspect` |

- **What a module can contribute** while it is loaded, withdrawn when it unloads: kind providers, Rachana functions, compute engines,
  approval sources, explainers, pivot sources, Health figures, official results, AI tools, `@ModuleApi` REST controllers (mounted under
  `/api/v1/<prefix>/`), and a console part declared in `console/plugin.yaml`: pages over the kernel's page and the Elements macros, menus,
  words, Home cards and Help ([DEVELOPER_GUIDE.md section 1.7](docs/guides/DEVELOPER_GUIDE.md#17-what-a-module-can-contribute)).
- **The custom-visual SDK.** A module that needs a chart Drishti does not have ships it as a kind of drishti-chart's registry in its own
  Elements library, built from drishti-chart's primitives, so it gets the theme, tooltips, Explain on a mark, export and the keyboard
  like every chart; `mvn drishti:visual -Dname=<name>` is the template, and the Elements gallery shows it while the module is loaded
  ([DEVELOPER_GUIDE.md section 1.8](docs/guides/DEVELOPER_GUIDE.md#18-a-modules-own-visual-the-custom-visual-sdk)).
- **The genomics sample**, step by step: a third party's module made from the archetype on the kernel API and the plugin API only,
  with a VCF connector inside it and its own lollipop visual ([DEVELOPER_GUIDE.md section 1.9](docs/guides/DEVELOPER_GUIDE.md#19-the-genomics-sample-step-by-step)).

## Deploy and place modules

- **Two folders, as in Tomcat**, under the data folder ([ADMIN_GUIDE.md section 22.1](docs/guides/ADMIN_GUIDE.md#221-module-packages-auto-deploy-and-the-shared-lib-folder)):
  `modules/` (`drishti.modules.path`) takes `.dmod` packages and **auto-deploys** them within seconds (verified, checked, installed,
  loaded; taking the file out removes the module), on for a single node and off in a cluster; `lib/` (`drishti.lib.path`) takes jars every
  connector and module may use (JDBC drivers, connector plugins, a company's libraries), read once at start into one class loader under the
  kernel's and listed with their SHA-256 in Health. **Development mode** (`drishti.modules.dev`) loads a module's exploded build folder
  and reloads it on every rebuild; it is refused in a cluster and under the production profile, which loads only verified packages.
- **Data bindings.** A module names the kinds it needs; the site decides which connector or lake table serves each one, in
  `config/bindings.yaml`. **Admin → Data bindings** shows every kind with its state (`BOUND`, `AUTO`, `DEMO`, `STALE`, `MISMATCH`,
  `UNBOUND`, `COMPUTED`), the modules and functions that need it; a change takes two administrators and applies without a restart
  ([ADMIN_GUIDE.md section 23](docs/guides/ADMIN_GUIDE.md#23-data-bindings), [KERNEL_AND_MODULES.md section 5](docs/architecture/KERNEL_AND_MODULES.md#5-data-needs-and-connector-binding)).
- **Placement** (`config/placement.yaml`) says which nodes run each part of a module, by resource class and `max-nodes`; heavy work is
  never spread to every node by accident; first-party modules default to every node. **Admin → Cluster → Modules** shows each part on each
  node; a placement is planned by one administrator and applied by another, at once and without a restart
  ([CLUSTER_GUIDE.md section 1.9](docs/guides/CLUSTER_GUIDE.md#19-module-placement)).
- **Deploy**, cluster-first: a `.dmod` is uploaded once to the module store (checked, kept by id and version); a **plan** says what happens
  on each placed node; a **second administrator applies** it in **two phases** (every placed node stages and verifies the package, then
  all activate; any failure rolls every node back). An **upgrade rolls** node by node while the others serve the old version, with no
  failed request. **Unload** is hot and leak-checked: the module's API answers `410 DRS-1028`, its pages say it is no longer installed, and
  saved Sutras, reports, dashboards, baskets, searches and alert rules are kept and marked ***needs module X*** until a reinstall restores
  them. **Admin → Modules** and **Admin → Deploy**, or `drishti.py module plan|deploy|upgrade|apply|remove|status`
  ([CLUSTER_GUIDE.md section 1.10](docs/guides/CLUSTER_GUIDE.md#110-deploying-a-module), [ADMIN_GUIDE.md section 25](docs/guides/ADMIN_GUIDE.md#25-modules-and-deploy)).

## Distributions

A distribution is one file, `config/distributions/drishti.<name>.yaml`: the product's brand, theme, modules, lenses, menus, Home and Help.
One kernel build and one console build make every one of them, and what a distribution leaves out does not exist in it: its commands,
words, menus, Home cards, Help pages and the catalogue the AI reads (*DRS-4023 VRES is not in Drishti Finance*)
([DISTRIBUTIONS.md](docs/architecture/DISTRIBUTIONS.md) ([PDF](docs/architecture/DISTRIBUTIONS.pdf)), [ADMIN_GUIDE.md section 24](docs/guides/ADMIN_GUIDE.md#24-white-label-distributions)).

| Distribution | For | Modules | Lenses | Start |
|---|---|---|---|---|
| **Drishti Risk** | traders, risk analysts and managers, middle office, model validation | banking, market-data | Risk (default), Trading, Market | `bin/start-all.sh --distribution risk` |
| **Drishti Finance** | investment and research analysts, economists, fund analysts; no VaR or any risk number | capital-markets, economy, market-data | Markets (default), World | `bin/start-all.sh --distribution finance` |
| **Drishti Genomics** | the sample that proves the kernel carries no finance | genomics (dropped into `modules/`) | Research | `bin/start-all.sh --distribution genomics` |
| **Drishti All** | developers, QA, demos; the default when none is named | every module | every lens | `bin/start-all.sh` |

- **Any start**: `--distribution <name|path>` on `bin/start-all`, `start-server` and `start-console` (`.sh`, `.ps1`, `.cmd`), or
  `DRISHTI_DISTRIBUTION`, or `drishti.distribution`; a path loads a site's own file (its own brand), beside the shipped ones.
- **Two consoles over one cluster**: a console started with its own distribution keeps it whatever the servers run, so a stack file's
  `consoles:` list (`{ name: risk, port: 17480, distribution: risk }`, `{ name: finance, port: 17481, distribution: finance }`) gives
  Drishti Risk and Drishti Finance side by side; `bin/status.sh` shows each process's distribution.
- **The IDEs**: the IntelliJ run configurations in `run-configs/` include `Drishti server (Risk)`, `(Finance)`, `(All)` and `Drishti
  console (Risk)`, `(All)`.
- **Admin → White label** shows every distribution side by side; Health shows the running one and which of its modules loaded.
  Step by step for each, on Linux, macOS and Windows:
  [DEPLOYMENT_AND_QA_GUIDE.md section 6.7](docs/DEPLOYMENT_AND_QA_GUIDE.md#67-starting-a-white-labelled-instance).

## Risk

The banking module's engines run on the server, in Java, for any scope; the console only draws their answers.

- **VaR and ES** for the bank, a desk, a book, a counterparty, a portfolio, a basket or an expression (`BANK - DESK:CREDIT`): drill from
  the bank to a trade with contributions that add up, hierarchies from rows or from the trades' own columns, limits with one rule
  (within, near, breach), any reporting currency, the 509- or 250-scenario window, stressed and 10-day VaR, intraday updates, the official
  end-of-day record and backtests ([VAR_GUIDE.md](docs/guides/VAR_GUIDE.md), [tutorial 2](docs/tutorials/02-var-from-the-bank-to-a-trade.md));
  regulatory reporting to the cell in [REGULATORY_VAR_REPORTING.md](docs/guides/REGULATORY_VAR_REPORTING.md).
- **The VaR page** (the VaR explorer): a scope tree that shows **only the chosen scope** (the whole bank, a node and below with a
  breadcrumb up, or a basket's own positions by the hierarchy you pick) with VaR, ES, contribution and use of limit per node; the switch
  that includes matured positions (the matured share beside each node); **Explain ›** on every row and wherever VaR or ES shows;
  **Why it moved** between the date on screen and a **compare date you pick** (previous day, 1 week, month end, any date), the
  waterfall first and its biggest contributors beside it (`VAR <scope> EXPLAIN vs <date>`); and the playground of the chosen scope.
- **Parametric VaR** (`PVAR`) beside historical, as a cross-check and never the official figure
  ([VAR_GUIDE.md section 3.12](docs/guides/VAR_GUIDE.md#312-parametric-var-beside-historical-pvar)).
- **The market-risk cube** (`MCUBE`): VaR by desk and risk class as a Pivot preset, each cell the VaR engine's answer for its scope
  ([PIVOT_GUIDE.md section 29](docs/guides/PIVOT_GUIDE.md#29-the-mr-cube-preset)).
- **Counterparty exposure** (EE, PFE, EPE, EEPE) from the simulator's paths with netting and collateral, and **the pre-deal check**
  (`PREDEAL`): a new trade added to its netting set path by path and answered approve, refer or decline against the credit limit and each
  tenor bucket, audited ([EXPOSURE_GUIDE.md](docs/guides/EXPOSURE_GUIDE.md), [section 18.9](docs/guides/EXPOSURE_GUIDE.md#189-the-pre-deal-check),
  [tutorial 3](docs/tutorials/03-exposure-from-the-bank-to-a-trade.md)).
- **FRTB-SA** for any VaR scope ([FRTB_SA_GUIDE.md](docs/guides/FRTB_SA_GUIDE.md), [tutorial 4](docs/tutorials/04-frtb-sa.md)) and **SIMM**
  per netting set; ISDA's calibration is licensed, so the parameter file ships empty
  ([SIMM_GUIDE.md section 4](docs/guides/SIMM_GUIDE.md#4-the-licensed-parameter-file), [tutorial 5](docs/tutorials/05-simm.md)).
- **Groupings and analytics** computed on request: ownership groups, sectors, correlations, an equity's beta
  ([GROUPINGS_ANALYTICS_GUIDE.md](docs/guides/GROUPINGS_ANALYTICS_GUIDE.md), [tutorial 6](docs/tutorials/06-groupings-and-analytics.md)).

**Explained**: every computed number has one Explain (the formula, the inputs and lineage, why it moved between two dates in steps
that add up, a workbook with live formulas), and VaR, FRTB-SA and SIMM each have a playground (`PLAY VAR`, `PLAY FRTB`, `PLAY SIMM`).
**Controlled**: roles, row rules and field masks apply to every screen, pivot, report and Flight read; corrections, designs, settings and
report sign-offs go through one Approvals queue with four eyes. Anything that depends on unbuilt work is registered in
`config/under-construction.yaml` and shown with an *Under construction* chip or hidden ([UNDER_CONSTRUCTION.md](docs/admin/UNDER_CONSTRUCTION.md)).

## Analyse

- **The terminal**: type-ahead over every source, linked views of any entity, impact (F8), the raw document (F9), any past business
  day, history and compare, live views, one keymap and one search (Alt+K) ([USER_GUIDE.md](docs/guides/USER_GUIDE.md),
  [tutorial 1](docs/tutorials/01-first-15-minutes.md)).
- **Search** by value (`TRD where QUANTITY > 200m order by QUANTITY desc`), in plain words without any AI service, or with a form;
  monitors and alerts on one alert engine.
- **The Pivot** (`PIVOT`): any kind, table or measure, every total computed from its own members, custom functions, why a figure moved,
  what-if, Excel with formulas; **Pivot → Report** turns a view into a report ([PIVOT_GUIDE.md](docs/guides/PIVOT_GUIDE.md),
  [tutorial 7](docs/tutorials/07-pivot-cubes-and-explain.md)).
- **BI depth** on the models of the table layer: many-to-many relationships through a bridge, filtering both ways, Rachana measures with
  filter context, a converter from DAX measures, the decomposition tree, linked brushing between visuals, sunburst, icicle and the
  calendar heatmap ([REPORTS_GUIDE.md](docs/guides/REPORTS_GUIDE.md), [TABLES_GUIDE.md section 12](docs/guides/TABLES_GUIDE.md#12-measures-in-a-model-filter-context-and-what-is-refused)).
- **Reports and dashboards** on the table layer: reports and boards, statements, schedules, subscriptions, publishing and governance
  ([REPORTS_GUIDE.md](docs/guides/REPORTS_GUIDE.md), [tutorial 8](docs/tutorials/08-reports-and-dashboards.md)).
- **The Notebook**: SQL on the server and Python on its one sandboxed Python runtime ([NOTEBOOK_GUIDE.md](docs/guides/NOTEBOOK_GUIDE.md),
  [tutorial 9](docs/tutorials/09-notebook-and-python.md)).
- **AI**, off as shipped: Ask about a page and BI from words on any of the supported provider types, MCP servers' tools behind Drishti's
  policy, masking and audit of every call ([AI_GUIDE.md](docs/guides/AI_GUIDE.md), [tutorial 10](docs/tutorials/10-ai-assistant.md)).
- **Corrections and approvals**: correct a record in a form, approve it in the one queue, the edit log and the audit trail
  ([APPROVALS_GUIDE.md](docs/guides/APPROVALS_GUIDE.md), [tutorial 11](docs/tutorials/11-correct-and-approve.md)).
- **One grammar**: Rachana paths and expressions in Sutras, search, the Pivot, reports and custom functions; every function in one
  catalogue ([FUNCTIONS.md](docs/guides/FUNCTIONS.md), [RACHANA_REFERENCE.md](docs/guides/RACHANA_REFERENCE.md)).

## Build

- **Tables and models** written once over any kind, lake table or query, with keys, rules and masks, edited in Build › Tables & models
  with the YAML live beside the form ([TABLES_GUIDE.md](docs/guides/TABLES_GUIDE.md), [tutorial 18](docs/tutorials/18-tables-and-models.md)).
- **Packs and screens**: a pack from your JSON Schema or JSON Lines; a screen as a Sutra, designed in the Build workbench or on a live page;
  where there is no Sutra the inference engine lays the screen out from the data; forms and phones
  ([BUILD_GUIDE.md](docs/guides/BUILD_GUIDE.md), [tutorial 19](docs/tutorials/19-build-a-pack-and-a-screen.md)). The Build home (`/build`)
  lists everything people build, what it uses and what uses it.
- **Drishti Elements**: the console's JavaScript and CSS as eleven versioned libraries (core, inputs, grid, chart with maps, data, tree,
  graph, insight, designer, renderer, and the banking module's risk). Every screen and every designer is drawn with them, guard tests
  fail the build when a page draws its own controls, and the gallery (`/elements`) shows each component live, with the Elements Playground
  to try one on your own data; a module adds its own domain library and visuals
  ([ELEMENTS.md](docs/architecture/ELEMENTS.md), [ELEMENTS_API.md](docs/guides/ELEMENTS_API.md)).
- **Embed** live views, panels and reports in your own web pages, or the libraries in your own application; they ship as files, a
  release archive with SRI hashes, npm tarballs or a WebJar, never from a CDN ([EMBED_GUIDE.md](docs/guides/EMBED_GUIDE.md),
  [tutorial 20](docs/tutorials/20-embed-drishti.md)).
- **Extend**: a connector, a plugin or a module ([Build a module](#build-a-module)), and the REST API ([DEVELOPER_GUIDE.md](docs/guides/DEVELOPER_GUIDE.md),
  [API_REFERENCE.md](docs/guides/API_REFERENCE.md)).

## Data in

- **Connectors**: Delta Lake and Apache Iceberg (Drishti's own engines, local disk or S3, no Hadoop), JDBC databases, DuckDB, MongoDB,
  Redis, Aerospike, Cassandra, Kafka, ActiveMQ, RabbitMQ, files, S3, REST and public feeds: the light ones built in (`plugins/drishti-connectors`: `demo`, `file`, `rest`, `feed`, `news`, `yfinance`),
  each with a driver or native library a plugin in `plugins/drishti-plugin-*/`: `jdbc`, `delta`, `iceberg`, `iceberg-external`, `duckdb`, `mongodb`, `redis`, `aerospike`, `cassandra`, `kafka`, `activemq`, `rabbitmq`, `s3`;
  each with its README ([DATA_GUIDE.md](docs/guides/DATA_GUIDE.md#8-connectors), [tutorial 15](docs/tutorials/15-connect-your-data.md)).
- **Lakes, each in its own folder** and described by one lake file (`config/lakes/<lake>.yaml`):

  | Lake | Holds |
  |---|---|
  | `demo` | the demo data, built from the committed test data (`drishti.py lake demo`); demo and real data never mix |
  | `risklake` | a bank's own risk data (shipped empty) |
  | `capitalmarket` | prices, curves, volatilities, US equities and funds |
  | `worldlake` | the World Bank, the IMF, the OECD, central banks' and national statistics' series |
  | `newslake` | official press releases, GDELT world events, market headlines |
  | `referencelake` | GLEIF's legal entities |
  | `regulators` | regulators' publications (shipped empty) |

  The banking module adds the official VaR and exposure records; which table serves each kind a module needs is a
  [data binding](#deploy-and-place-modules). In-memory lakes (`mem://`) hold data that never touches disk.
- **Loading**: JSON Lines is the standard. A plain file is one table and one business date; a `.ready` file says it is complete and names
  its lake; a tar archive loads all or nothing; every row carries its load id, so `drishti.py lake undo <load-id>` removes exactly one
  load. The data inbox loads what your ETL drops, and the parallel Java loader handles large loads
  ([DATA_LAKE_GUIDE.md](docs/guides/DATA_LAKE_GUIDE.md), [tutorial 16](docs/tutorials/16-load-your-own-data-into-a-new-lake.md)).
- **A day of bank data**: what a bank sends each day and the daily cycle ([tutorial 12](docs/tutorials/12-a-day-of-bank-data.md),
  [the data contracts](docs/contracts/README.md), one per command word).
- **drishti-feeds** pulls public data every day, one feed file per source, with incremental pulls, a run ledger and an alert when a run
  fails: the World Bank, central banks, Statistics Canada, the OECD, official news, GDELT, GLEIF, US equities, funds and market data
  ([EXTERNAL_DATA.md](docs/guides/EXTERNAL_DATA.md), [FUNDS_GUIDE.md](docs/guides/FUNDS_GUIDE.md),
  [PUBLIC_SECTOR_GUIDE.md](docs/guides/PUBLIC_SECTOR_GUIDE.md), [tutorial 13](docs/tutorials/13-world-data.md),
  [tutorial 14](docs/tutorials/14-public-sector-data.md)).

## Data out

- **Arrow Flight and Flight SQL** (off as shipped): data, models, pivot views and VaR's scenario P&L vectors to Python, Java, JDBC and
  ODBC clients, read only, with the reader's entitlements and masks ([FLIGHT_GUIDE.md](docs/guides/FLIGHT_GUIDE.md),
  [FLIGHT_REFERENCE.md](docs/guides/FLIGHT_REFERENCE.md), [tutorial 17](docs/tutorials/17-data-out-with-arrow-flight.md); client
  examples in [examples/clients/](examples/clients/README.md)).
- **Export data** (`EXPORT`): any dataset, a simple selection, a day as `.jsonl.gz`, streamed and audited.
- **The REST API** under `/api/v1` with API tokens ([API_REFERENCE.md](docs/guides/API_REFERENCE.md)).

## Operate

- **Install, configure, deploy, monitor, troubleshoot**: by hand, with systemd, Docker Compose or AWS ECS, and on Windows with Java and
  Python only; promotion and roll back by copying; the runbooks; Prometheus metrics ([OPERATIONS_GUIDE.md](docs/guides/OPERATIONS_GUIDE.md),
  [tutorial 21](docs/tutorials/21-run-it-in-production.md)).
- **People and access**: passwords or OpenID Connect, roles per kind, field masks, API tokens, an access log and an audit trail; off by
  default for a developer machine, on for any shared one ([ADMIN_GUIDE.md](docs/guides/ADMIN_GUIDE.md)).
- **Configuration**: every setting in three tiers with its value in effect and where it came from; a change is a proposal a second
  administrator approves ([CONFIGURATION.md](docs/admin/CONFIGURATION.md)).
- **Clusters** (Drishti\*net, off as shipped): servers join by gossip on a port of their own, any member answers any request, and VaR
  shards across members; modules are placed on chosen nodes and deployed by plan → apply; sizing and tuning ([CLUSTER_GUIDE.md](docs/guides/CLUSTER_GUIDE.md), [tutorial 22](docs/tutorials/22-a-cluster-of-three.md)).
- **Engines and native maths**: each compute engine on or off per node; OpenBLAS bundled for Linux and Windows x86-64, off as shipped
  ([ADMIN_GUIDE.md section 21](docs/guides/ADMIN_GUIDE.md#21-compute-engines-on-or-off),
  [OPERATIONS_GUIDE.md section 14.9](docs/guides/OPERATIONS_GUIDE.md#149-native-libraries-blas)).

## Run it

With a 64-bit Java 21 (JDK) and [uv](https://docs.astral.sh/uv/) for the console's Python (Maven ships with the repository), from the
repository root ([OPERATIONS_GUIDE.md section 2](docs/guides/OPERATIONS_GUIDE.md#2-install-and-run-on-one-machine) has every step):

```bash
# 1. Build the runnable server (the kernel, with the first-party modules it installs at the first start)
./mvnw -q package -DskipTests -pl :drishti-app -am

# 2. The console's Python environment (it also runs tools/drishti.py)
uv venv --python 3.13 drishti-console/.venv
uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements.txt

# 3. The demo lake, from the committed test data in data/testdata
drishti-console/.venv/bin/python tools/drishti.py lake demo

# 4. The server on :18480 and the console on :17480, in the background, with the demo's settings
bin/start-all.sh --demo          # Windows: bin\start-all.cmd --demo; add --distribution risk|finance|genomics for one product
bin/status.sh                    # both UP, with the addresses to open
bin/stop-all.sh                  # when you are done
```

Open the console's address that `start-all` prints, sign in as `drishti-dev-admin` (password `drishti-dev-admin123`: the demo's
development account, never for a shared machine) and try `TRD MX-20000001`, `VAR BANK`, `EXP BANK`, `PREDEAL` and `PIVOT`
([DEPLOYMENT_AND_QA_GUIDE.md](docs/DEPLOYMENT_AND_QA_GUIDE.md) walks it on Windows, Linux and macOS, with what you should see).

- **Starting and stopping**: the scripts of [bin/](bin/README.md) (`start-server`, `start-console`, `stop-server`, `stop-console`,
  `status`, `start-all`, `stop-all`, `start-cluster`, `stop-cluster`), each as `.sh` (Linux, macOS), `.ps1` and `.cmd` (Windows); every
  option, a cluster node by node with `--node`, the console pointed at a cluster's entry node with `--server`, a distribution with
  `--distribution` and a second console in one data folder with `--name`:
  [DEPLOYMENT_AND_QA_GUIDE.md section 6](docs/DEPLOYMENT_AND_QA_GUIDE.md#6-start-the-server-and-the-console).

- **The command line** is `tools/drishti.py`, run with the console's Python: lakes and loads, feeds, Elements, approvals, VaR, exposure
  and more ([CLI_REFERENCE.md](docs/guides/CLI_REFERENCE.md), [COMMAND_CATALOG.md](docs/guides/COMMAND_CATALOG.md)).
- **A local cluster of three** with its consoles, from one file: `bin/start-cluster.sh` with [drishti-stack/stack.yaml](drishti-stack/README.md)
  (its `consoles:` list may start a Drishti Risk and a Drishti Finance console over the same cluster).
- **Other ways**: [Windows](docs/guides/OPERATIONS_GUIDE.md#4-drishti-on-windows) (Java and Python only),
  [Docker Compose](docs/guides/OPERATIONS_GUIDE.md#54-docker-compose) and the
  [IDEs](docs/guides/OPERATIONS_GUIDE.md#3-other-ways-to-build-and-run-maven-intellij-idea-and-pycharm).

## Measured performance

Measured on one developer workstation; the method, the commands to run each again and the limits of each figure are in
[BENCHMARK.md](docs/admin/BENCHMARK.md).

| Run | Result | Where |
|---|---|---|
| VaR, 200,000 positions, one server (`-Xmx8g`) | first open of a date 4.5 s; 50 users 270 requests/s, 0 errors; heap with two dates open 881 MB | [§4.2](docs/admin/BENCHMARK.md#42-one-server-and-the-three-node-cluster), [§4.3](docs/admin/BENCHMARK.md#43-users-50-100-and-200) |
| VaR, 200,000 positions, three-node cluster | the bank's VaR bit-equal to one server | [§4.2](docs/admin/BENCHMARK.md#42-one-server-and-the-three-node-cluster) |
| VaR, 1,000,000 positions, one server (`-Xmx6g`) | first open of a date 20.9 s; 50 users 183 requests/s, 0 errors; heap with two dates open 2.2 GB | [§4.2](docs/admin/BENCHMARK.md#42-one-server-and-the-three-node-cluster), [§4.3](docs/admin/BENCHMARK.md#43-users-50-100-and-200) |
| Many VaR scopes at once, 1,000,000 positions | 100 overlapping sets 3.1x faster summed together on OpenBLAS; 100 desks 3.3x on Java; results identical or within 4.5e-16 | [§17](docs/admin/BENCHMARK.md#17-many-var-scopes-at-once-blas) |
| 1,000,000 trades a day on Delta | search over every trade 134 ms (2,017 matches); open a trade 48 ms cached | [§8.1](docs/admin/BENCHMARK.md#81-a-million-trades-a-day-on-delta) |
| 35.7 million trades over 30 dates, 50 users | 179 requests/s, 0 failures; a day of 400,000 trades restated in 2 min 54 s while 30 users read | [§8.3](docs/admin/BENCHMARK.md#83-thirty-days-and-a-day-restated-while-serving) |
| The parallel Java loader, 10,000 positions × 510 scenarios | 0.19 to 0.29 s warm, 34,000 to 52,000 rows/s | [§7.2](docs/admin/BENCHMARK.md#72-the-java-parallel-loader-per-stage) |

## Documentation

Each major feature has **one guide and one tutorial**; every tutorial step shows the screen, the command line and the YAML, on the
shipped demo data. The console's Help centre holds the same pages, grouped by domain and lens, and shows each person only the pages of
the modules they may use. The map of every document is [docs/README.md](docs/README.md).

| You want to… | Guides | Tutorials |
|---|---|---|
| find anything, pivot, report, analyse | [USER_GUIDE](docs/guides/USER_GUIDE.md), [PIVOT_GUIDE](docs/guides/PIVOT_GUIDE.md), [REPORTS_GUIDE](docs/guides/REPORTS_GUIDE.md), [NOTEBOOK_GUIDE](docs/guides/NOTEBOOK_GUIDE.md), [AI_GUIDE](docs/guides/AI_GUIDE.md), [APPROVALS_GUIDE](docs/guides/APPROVALS_GUIDE.md) | [1](docs/tutorials/01-first-15-minutes.md), [7](docs/tutorials/07-pivot-cubes-and-explain.md), [8](docs/tutorials/08-reports-and-dashboards.md), [9](docs/tutorials/09-notebook-and-python.md), [10](docs/tutorials/10-ai-assistant.md), [11](docs/tutorials/11-correct-and-approve.md) |
| measure risk | [VAR_GUIDE](docs/guides/VAR_GUIDE.md), [REGULATORY_VAR_REPORTING](docs/guides/REGULATORY_VAR_REPORTING.md), [EXPOSURE_GUIDE](docs/guides/EXPOSURE_GUIDE.md), [FRTB_SA_GUIDE](docs/guides/FRTB_SA_GUIDE.md), [SIMM_GUIDE](docs/guides/SIMM_GUIDE.md), [GROUPINGS_ANALYTICS_GUIDE](docs/guides/GROUPINGS_ANALYTICS_GUIDE.md) | [2](docs/tutorials/02-var-from-the-bank-to-a-trade.md), [3](docs/tutorials/03-exposure-from-the-bank-to-a-trade.md), [4](docs/tutorials/04-frtb-sa.md), [5](docs/tutorials/05-simm.md), [6](docs/tutorials/06-groupings-and-analytics.md) |
| work with bank, world and public data | [FUNDS_GUIDE](docs/guides/FUNDS_GUIDE.md), [PUBLIC_SECTOR_GUIDE](docs/guides/PUBLIC_SECTOR_GUIDE.md), [EXTERNAL_DATA](docs/guides/EXTERNAL_DATA.md) | [12](docs/tutorials/12-a-day-of-bank-data.md), [13](docs/tutorials/13-world-data.md), [14](docs/tutorials/14-public-sector-data.md) |
| bring data in and take it out | [DATA_GUIDE](docs/guides/DATA_GUIDE.md), [DATA_LAKE_GUIDE](docs/guides/DATA_LAKE_GUIDE.md), [FLIGHT_GUIDE](docs/guides/FLIGHT_GUIDE.md) | [15](docs/tutorials/15-connect-your-data.md), [16](docs/tutorials/16-load-your-own-data-into-a-new-lake.md), [17](docs/tutorials/17-data-out-with-arrow-flight.md) |
| understand the kernel, modules and distributions | [HOW_IT_FITS](HOW_IT_FITS.md) ([PDF](HOW_IT_FITS.pdf)), [KERNEL_AND_MODULES](docs/architecture/KERNEL_AND_MODULES.md) ([PDF](docs/architecture/KERNEL_AND_MODULES.pdf)), [DISTRIBUTIONS](docs/architecture/DISTRIBUTIONS.md) ([PDF](docs/architecture/DISTRIBUTIONS.pdf)), [DEVELOPER_GUIDE section 1.6](docs/guides/DEVELOPER_GUIDE.md#16-the-module-sdk) | [the genomics sample, step by step](docs/guides/DEVELOPER_GUIDE.md#19-the-genomics-sample-step-by-step) |
| build tables, packs, screens and modules; embed; extend | [QUICKSTART_DEVELOPERS](docs/QUICKSTART_DEVELOPERS.md) ([PDF](docs/QUICKSTART_DEVELOPERS.pdf)), [TABLES_GUIDE](docs/guides/TABLES_GUIDE.md), [BUILD_GUIDE](docs/guides/BUILD_GUIDE.md), [EMBED_GUIDE](docs/guides/EMBED_GUIDE.md), [DEVELOPER_GUIDE](docs/guides/DEVELOPER_GUIDE.md) | [18](docs/tutorials/18-tables-and-models.md), [19](docs/tutorials/19-build-a-pack-and-a-screen.md), [20](docs/tutorials/20-embed-drishti.md) |
| install, operate, scale, administer | [DEPLOYMENT_AND_QA_GUIDE](docs/DEPLOYMENT_AND_QA_GUIDE.md) ([PDF](docs/DEPLOYMENT_AND_QA_GUIDE.pdf)), [OPERATIONS_GUIDE](docs/guides/OPERATIONS_GUIDE.md), [CLUSTER_GUIDE](docs/guides/CLUSTER_GUIDE.md) ([PDF](docs/guides/CLUSTER_GUIDE.pdf)), [ADMIN_GUIDE](docs/guides/ADMIN_GUIDE.md) | [21](docs/tutorials/21-run-it-in-production.md), [22](docs/tutorials/22-a-cluster-of-three.md) |

**References**, one home each: [CONFIGURATION](docs/admin/CONFIGURATION.md), [API_REFERENCE](docs/guides/API_REFERENCE.md),
[CLI_REFERENCE](docs/guides/CLI_REFERENCE.md), [COMMAND_CATALOG](docs/guides/COMMAND_CATALOG.md), [FUNCTIONS](docs/guides/FUNCTIONS.md),
[RACHANA_REFERENCE](docs/guides/RACHANA_REFERENCE.md), [ELEMENTS_API](docs/guides/ELEMENTS_API.md), [FLIGHT_REFERENCE](docs/guides/FLIGHT_REFERENCE.md),
[VIEWMODEL](docs/architecture/VIEWMODEL.md), [BENCHMARK](docs/admin/BENCHMARK.md), [the data contracts](docs/contracts/README.md) and
[the Knowledge primers](docs/knowledge/README.md). The design is [ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md) and
[DATA_ARCHITECTURE.md](docs/architecture/DATA_ARCHITECTURE.md); what changed and when, [RELEASE_NOTES.md](RELEASE_NOTES.md) and
[CHANGELOG.md](CHANGELOG.md).

## Project facts

- **Kernel**: Java 21 (64-bit), Spring Boot on virtual threads; modules loaded from `.dmod` packages, one class loader each; identity and user content in SQLite by default or PostgreSQL, one complete
  schema file per database and no migrations.
- **Console**: Python, FastAPI and Jinja2; a module's console part is declared in its own `console/` folder and hosted under `/m/<id>/`;
  every front-end asset
  vendored or one of the Elements libraries, under a strict content security policy; it works on phones.
- **The source tree**: the Maven modules sit in subject folders, `kernel/`, `compute/`, `lake/`, `net/`, `plugins/`, `modules/`,
  `packaging/`, `tests/` and `drishti-sdk/` ([DEVELOPER_GUIDE.md section 1.1](docs/guides/DEVELOPER_GUIDE.md#11-java-modules)); a module's
  folder is its artifact id.
- **Contributing**: work on `develop`; `./mvnw -q verify` and the console tests
  (`drishti-console/.venv/bin/python -m pytest -q drishti-console/tests`) must pass; no source file over 2,000 lines; every file carries
  the copyright header (`python3 tools/license_headers.py --fix`); every change updates the documents it affects and the changelog in
  the same push; the documentation gates (`drishti-console/tests/test_docs_gates.py`, `config/docs-features.yaml`) keep one guide and one
  tutorial per feature.
- **The drill** (`tools/drill.sh`): the build, every test suite, the page walk of every page with its layout checks and **visual
  snapshots** of about thirty key pages (1366 and 1920 px, dark and light) compared with committed golden images, each change listed
  with a diff image; goldens are refreshed only by hand ([DEVELOPER_GUIDE.md section 2.6](docs/guides/DEVELOPER_GUIDE.md#26-the-drill-toolsdrillsh),
  [TEST_HARNESS.md section 3.20](docs/architecture/TEST_HARNESS.md#320-the-visual-snapshots)).

## By the numbers

<!-- code-volume:begin -->

*Measured 2026-11-11 from the files the repository tracks (`python3 tools/docs/code_volume.py`; sample data and vendored libraries not counted).*

| Language | Lines | Files |
|---|---|---|
| Java, product | 450k | 2,263 |
| Java, tests | 173k | 1,079 |
| Python, product (console, tools, loaders, feeds) | 147k | 745 |
| Python, tests | 110k | 631 |
| JavaScript, product (console and Elements) | 131k | 505 |
| JavaScript, tests | 5k | 24 |
| CSS | 19k | 119 |
| HTML (templates and pages) | 40k | 367 |
| YAML: configuration, Sutras, data contracts | 114k | 1,465 |
| Markdown: guides, tutorials, design | 227k | 325 |

About **786k lines of product code** and **287k lines of tests** (37% of the product code), with **227k lines of documentation**.

| What | Count |
|---|---|
| Maven modules | 48 in 9 subject folders: `compute/`, `drishti-sdk/`, `kernel/`, `lake/`, `modules/`, `net/`, `packaging/`, `plugins/`, `tests/` |
| Modules (`.dmod`) | 5: banking, capitalmarkets, economy, genomics, market-data |
| Lenses | 11: world, markets, publicsector, cio, oversight, risk, trading, market, cro, mr-cube, ccr-cube |
| Functions a module declares | 121 (48 banking, 12 capitalmarkets, 35 economy, 3 genomics, 23 market-data); every other command word is the kernel's |
| Console routes | 667 |
| Server REST handlers | 817 |
| Drishti Elements | 11 libraries, 196 ES modules |
| Domain packs | 17: banking 4, capitalmarkets 2, core 3, economy 8 |
| Sutras in the shipped packs | 248 |
| Connectors | 13 plugins (a driver or native library each) and 9 built-in types |
| Feeds | 13 |
| Lake files | 9 |
| Test files | 1,579: Java 985, console 259, browser 184, tools 127, JavaScript 24 |
| Documents under docs/ | 191 (32 guides, 22 tutorials) |

<!-- code-volume:end -->

## Legal

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.

This software is **proprietary and confidential**. Copying, use, modification, distribution or
disclosure without the express prior written permission of the copyright holder is prohibited.
See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
