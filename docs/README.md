<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Drishti documentation

Most of these documents are also the console's in-app help (`/help`). The help centre lists **67 pages in 8 sections** (`drishti-console/config/help.yaml`; the table below is checked against it), each a tile of the help centre's front door that opens its own page: *Get started*, *Risk measures*, *Analytics and BI*, *Data*, *Build*, *Operate*, *Reference* and *Knowledge*; a ninth, *Internal*, is for administrators only.
**The tutorials teach, the guides are reference:** every step-by-step walk-through lives in [tutorials/](tutorials/README.md); a guide says what a feature is, every option and every code, and points to the tutorial that
shows it. The architecture documents, the per-plugin connector documents, the runbooks and the QA records stay in the repository (and open in the help centre when a page links to them), listed below. An address from
before the consolidation (for example `/help/quickstart` or `/help/connector-kafka`) redirects to the page or section that took the subject over.

## New here? Read these, in order

1. **[DEPLOYMENT_AND_QA_GUIDE.md](DEPLOYMENT_AND_QA_GUIDE.md)** (*Deploy and verify*, also as a PDF): Windows, Linux and macOS step by step, what you should see at each step, the QA checklist, a cluster of three; the details behind each step are in [OPERATIONS_GUIDE.md](guides/OPERATIONS_GUIDE.md#2-install-and-run-on-one-machine).
2. **[tutorials/](tutorials/README.md)**: the hands-on tutorials, in tracks, on the shipped demo data, with the exact commands, real output and real screenshots. Start with [your first 15 minutes](tutorials/01-first-15-minutes.md).
3. **[USER_GUIDE.md](guides/USER_GUIDE.md)**: every feature of the console, as a reference. **[BUILD_GUIDE.md, Part V](guides/BUILD_GUIDE.md#31-the-packs-that-ship-turning-them-on-who-sees-which)**: which industries are available and how to load them; building your own is [BUILD_GUIDE.md](guides/BUILD_GUIDE.md).

If you will change the code, start with [QUICKSTART_DEVELOPERS.md](QUICKSTART_DEVELOPERS.md), then [DEVELOPER_GUIDE.md](guides/DEVELOPER_GUIDE.md).

## The help centre, section by section

| Section | Pages |
|---|---|
| **Get started** | [How Drishti fits together](../HOW_IT_FITS.md) · [User guide](guides/USER_GUIDE.md) · Sharing and collaboration (console guide) · [Tutorials](tutorials/README.md) |
| **Risk measures** | [VaR guide](guides/VAR_GUIDE.md) · [VaR tutorial: from the bank to a trade](guides/VAR_GUIDE.md#part-v--tutorial-var-from-the-bank-to-a-trade) · [Regulatory VaR reporting: Canada and the US](guides/REGULATORY_VAR_REPORTING.md) · [Exposure guide](guides/EXPOSURE_GUIDE.md) · [Exposure tutorial: from the bank to a trade](guides/EXPOSURE_GUIDE.md#part-iv--tutorial-exposure-from-the-bank-to-a-trade) · [SIMM guide](guides/SIMM_GUIDE.md) · [SIMM tutorial: from the hand-built examples to a netting set](guides/SIMM_GUIDE.md#14-tutorial-simm-from-the-hand-built-examples-to-a-netting-set) · [FRTB-SA guide](guides/FRTB_SA_GUIDE.md) · [FRTB-SA tutorial: from the hand-built cases to a desk](guides/FRTB_SA_GUIDE.md#17-tutorial-frtb-sa-from-the-hand-built-cases-to-a-desk) |
| **Analytics and BI** | [Reports and dashboards](guides/BI_GUIDE.md#part-3--reports-and-dashboards) · [Drishti BI and Power BI compared](guides/BI_GUIDE.md#part-6--drishti-bi-and-power-bi-compared) · [Reports and dashboards tutorial](guides/BI_GUIDE.md#tutorial-reports-and-dashboards) · [Notebook and Python](guides/BI_GUIDE.md#part-4--the-notebook-and-python) · [Notebook tutorial: Python and the Notebook](guides/BI_GUIDE.md#tutorial-the-notebook-and-python) · [Corrections and approvals](guides/APPROVALS_GUIDE.md) · [Approvals tutorial: correct a trade and approve it](tutorials/11-correct-and-approve.md) · [Pivot guide](guides/BI_GUIDE.md#part-1--pivot-cubes-and-explain) · [Pivot, cubes and explain tutorial](guides/BI_GUIDE.md#tutorial-pivot-cubes-and-explain) · [Groupings and analytics guide](guides/BI_GUIDE.md#part-5--groupings-and-analytics) · [Groupings and analytics tutorial](guides/BI_GUIDE.md#tutorial-groupings-and-analytics-computed) · [Funds and ETFs guide](guides/FUNDS_GUIDE.md) · [World data tutorial: economies, companies, funds, entities and news](tutorials/13-world-data.md) · [Public sector guide](guides/PUBLIC_SECTOR_GUIDE.md) · [Public sector tutorial](tutorials/14-public-sector-data.md) |
| **Data** | [Data guide: connectors, the load signal, formats, external data, Export data](guides/DATA_GUIDE.md) · [Connect your data tutorial](tutorials/15-connect-your-data.md) · [Data lake and data load guide](guides/DATA_LAKE_GUIDE.md) · [Lake tutorial: load your own data into a new lake](tutorials/16-load-your-own-data-into-a-new-lake.md) · [A day of bank data: load it daily](tutorials/12-a-day-of-bank-data.md) · [External data feeds](guides/EXTERNAL_DATA.md) · [Test data samples, field by field](contracts/samples/README.md) · [Virtual tables and models](guides/BI_GUIDE.md#part-2--virtual-tables-and-models) · [Virtual tables tutorial: trade legs as a table, a model, BI migrated](guides/BI_GUIDE.md#tutorial-virtual-tables-and-models) · [Data out: Drishti's data to any client with Arrow Flight](guides/FLIGHT_GUIDE.md) · [Data out tutorial: from a token to pandas](tutorials/17-data-out-with-arrow-flight.md) |
| **Build** | [Packs and screens](guides/BUILD_GUIDE.md) · [Build tutorial: a pack and a screen](tutorials/19-build-a-pack-and-a-screen.md) · [Sutra examples](guides/examples/README.md) · [Developer quickstart](QUICKSTART_DEVELOPERS.md) · [Developer guide: connectors, plugins and the API](guides/DEVELOPER_GUIDE.md) · [Embed {product} in your pages](guides/EMBED_GUIDE.md) · [Embed tutorial: a view in your page](tutorials/20-embed-drishti.md) |
| **Operate** | [Configuration](admin/CONFIGURATION.md) · [Administration: people, access and lenses](guides/ADMIN_GUIDE.md) · [AI: model providers and MCP servers](guides/AI_GUIDE.md) · [AI tutorial: Ask, tools and model providers](tutorials/10-ai-assistant.md) · [Deployment and QA guide](DEPLOYMENT_AND_QA_GUIDE.md) · [Install and operate](guides/OPERATIONS_GUIDE.md) · [Operations tutorial: run it in production](tutorials/21-run-it-in-production.md) · [Clusters and scale](guides/CLUSTER_GUIDE.md) · [Cluster tutorial: a cluster of three](tutorials/22-a-cluster-of-three.md) · [Benchmark: every measurement and how to run it](admin/BENCHMARK.md) |
| **Reference** | [API reference](guides/API_REFERENCE.md) · Developer: API references (console guide) · [ViewModel contract](architecture/VIEWMODEL.md) · [Rachana reference](guides/RACHANA_REFERENCE.md) · [Functions](guides/RACHANA_REFERENCE.md#every-function-generated-from-the-registries) · [Command line](guides/CLI_REFERENCE.md) · [Release notes](../RELEASE_NOTES.md) |
| **Knowledge** | [Knowledge home and glossary](knowledge/README.md) · [Risk](knowledge/risk.md) · [Data](knowledge/data.md) · [World](knowledge/world.md) |
| In the repository only | [architecture/](architecture/ARCHITECTURE.md) (design notes and [decision records](architecture/adr/README.md)), the internal [competitive landscape: kernel and modules](strategy/COMPETITIVE_ANALYSIS.md#part-ii--kernel-and-modules-against-plug-in-platforms) (does any product share Drishti's kernel and module architecture), the per-plugin [connector documents](connectors/), the [runbooks](guides/OPERATIONS_GUIDE.md#201-source-down-or-slow), the [QA history](../CHANGELOG.md); the AG Grid migration guide ([moving an AG Grid Enterprise pivot app to drishti-grid](guides/EMBED_GUIDE.md#14-moving-an-ag-grid-enterprise-pivot-app), named in the Elements gallery); [Using Drishti Elements in your app](guides/EMBED_GUIDE.md#13-drishti-elements-libraries-in-your-own-app) (install, pick libraries, theme, connect to a server) |
## How these documents are organised

| Folder | What is in it |
|---|---|
| [knowledge/](knowledge/README.md) | what the words of a bank's data mean and how Drishti's data connects them: one page per domain, the glossary (`glossary.yaml`, `WHAT <term>`) |
| [tutorials/](tutorials/README.md) | the hands-on tutorials, in tracks (Start, Risk engines, BI and analysis, Data in and out, Build, Operate): a worked example each, on the demo data, with commands, real output and screenshots |
| [guides/](guides/) | one document per subject: install and run, the user guide (with Calc, the Notebook and query by form), business intelligence (the BI guide, the BI language reference), the data guide (loads, formats, lakes, schema to pack) and external data (every public source and its script), packs (with the developer guide), the funds guide, the groupings and analytics guide, VaR and exposure, the Sutra guide and the Rachana reference (with panels, the screen designer and the screen designs), write-back, the command line, API and troubleshooting; `examples/` holds working Sutras |
| [connectors/](connectors/) | the connector developer guide and the READMEs of the connector types built into the engine (derived, net, reactive); connecting your data is DATA_GUIDE.md sections 8 to 11 |
| [contracts/](contracts/README.md) | the data contracts: the shared rules (keys, types, dates, signs, vectors, validation), the computed measures' contracts, and [the contract of every command](contracts/packs/README.md) (one YAML file per mnemonic beside its pack) |
| [admin/](admin/) | running Drishti: operations (with Drishti\*net, compute workers and lake maintenance), configuration, users and roles, editions, AI (model providers and MCP), performance, and the runbooks |
| [lenses/mockups/](lenses/mockups/) | the lens mock-ups; the lens product document is [architecture/LENSES.md](architecture/LENSES.md): one product document per lens: Drishti Risk, Trading, Market and World (built), the proposed Drishti Oversight for market risk oversight, the proposed MR Cube and CCR Cube (risk cubes on one shared core, with their data contracts) the proposed CRO lens (the Chief Risk Officer's appetite view, with its mock-ups and build checklist) and the proposed CIO lens (the risk data estate and BCBS 239, with its mock-ups and build checklist); who uses each, their questions, what it answers today, the gaps and the proposals |
| [qa/](../CHANGELOG.md) | quality assurance: the history of every run (findings, fixes, commits) and the Windows QA script |
| [architecture/](architecture/) | how Drishti is built: the architecture, the BI design (with the W1 model specification, Rūpaka and BI runs), the data architecture (live updates, history queries, Iceberg, reactive connectors, the content store, the database), the client renderer (with Elements and GenUI), the ViewModel contract, collaboration, VaR sharding, resources and scale, cluster deployment (with Drishti\*net, AWS, the Delta lake in S3 and super scale), the implementation plan, [high-performance native integration and Java 25](architecture/HIGH_PERFORMANCE_NATIVE.md) (the roadmap: BLAS, the Vector API, native libraries, embedded columnar engines), the [scale strategy](guides/CLUSTER_GUIDE.md#14-scale-strategy-for-one-server) (millions of trades a day, a year of history) and the decision records (ADRs) |
| [requirements/](requirements/), [publications/](publications/) | the reference mockups and logo; the design-and-evidence presentation |

There is no archive folder. An obsolete design is deleted: git keeps its history, so its live content moves into the current document
first, and a document that still mentions it names the release tag where it last lived (for example "in git at tag v2.9.5"), as text,
not as a link.

## If you want to…

### Use Drishti

| If you want to… | Read |
|---|---|
| Install and start it, from a clean machine | [OPERATIONS_GUIDE.md](guides/OPERATIONS_GUIDE.md#2-install-and-run-on-one-machine) |
| Learn it by doing, from your first 15 minutes | [tutorials/](tutorials/README.md) |
| Tell Drishti a batch of data has landed (API, CLI, Airflow, cron, DishtaYantra), flag late data | [DATA_GUIDE.md › Telling Drishti new data has landed](guides/DATA_GUIDE.md#1-telling-drishti-new-data-has-landed) |
| Open business intelligence: `RUPAKA <GO>`, the catalogue, workspaces, publish and review, certify | [BI_GUIDE.md](guides/BI_GUIDE.md#part-3--reports-and-dashboards) |
| Learn BI by building a report, then making it run itself, live, governed, in Python and with AI | [tutorials 10 to 15, 18 and 20](tutorials/README.md) |
| Author in **BI Studio**: the Explorer, open items as tabs, the Problems and Query dock, the generated Format pane, conditional formatting, themes and the theme designer, Edit interactions, tooltip pages, small multiples, the matrix | [BI_GUIDE.md › BI Studio](guides/BI_GUIDE.md#r8-bi-studio-and-the-report-designer), [BI_GUIDE.md › Format](guides/BI_GUIDE.md#r101-the-format-pane) |
| Prepare data in Python step by step (**BI → Transforms**: inputs through a pack's connector, a snippets library, a preview and a profile after every step) | [BI_GUIDE.md › Data prep](guides/BI_GUIDE.md#n5-data-prep-the-transform-editor), [the Notebook tutorial, step 12](guides/BI_GUIDE.md#step-12--prepare-raw-trades-step-by-step-the-transform-editor) |
| Work on a masked extract of a model in SQL and Python cells, run on the server (**BI → Notebook**, `/bi/notebook`) | [USER_GUIDE.md › The Notebook](guides/USER_GUIDE.md#the-notebook) |
| Write a measure, a derived column, a filter or a row rule in the Python-like expression language, or query a model in SQL | [RACHANA_REFERENCE.md › Rachana-EL expressions](guides/RACHANA_REFERENCE.md#rachana-el-expressions), [API_REFERENCE.md › Drishti SQL](guides/API_REFERENCE.md#8-drishti-sql-apiv1bisql) |
| Read a dataset model, query it, see row rules and masks at work with four people, start a model from the packs | [BI_GUIDE.md › Datasets: the YAML, the expression language and the security rules, section 17](guides/BI_GUIDE.md#r59-worked-examples-the-shipped-models-row-rules-and-masks) |
| Write or read a report: pages, visuals, wells, filters, slicers, drill-down and drill-through, the viewer, how a visual becomes a query, DRS-9030 to 9035 | [BI_GUIDE.md › Reports: the YAML, the visuals and how they behave](guides/BI_GUIDE.md#r9-the-report-file-pages-visuals-filters-and-slicers) |
| Publish BI safely: versions and diffs, review, certification, lineage, impact of a dataset change, bundles between environments, DRS-9040 to 9042 | [BI_GUIDE.md › Publishing and governance: versions, review, certification, lineage, impact, environments](guides/BI_GUIDE.md#r17-publishing-and-governance-versions-review-certification-lineage-impact-environments) |
| Export a report or view as PDF, PNG, Excel or CSV; subscribe people by email, each with their own masks, DRS-9060 and 9061 | [BI_GUIDE.md › Distribution: scheduled reports, export, subscriptions, alerts and embedding](guides/BI_GUIDE.md#r18-distribution-the-reports-page-export-and-subscriptions) |
| Build live BI: boards (report pages of live tiles beside the report's own visuals, kiosk, tile alerts), live tables, live visuals, DRS-9050 to 9053 | [BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 23](guides/BI_GUIDE.md#r14-boards-report-pages-of-live-tiles) |
| Write or read a dataset: tables, relationships, measures, the expression language, row rules, masks, layers | [BI_GUIDE.md › Datasets: the YAML, the expression language and the security rules](guides/BI_GUIDE.md#r5-models-and-datasets-what-a-report-reads) |
| Ship packs, Sutras and data to servers by copying: bundle, verify, deploy, promote, roll back | [OPERATIONS_GUIDE.md](guides/OPERATIONS_GUIDE.md#8-deploy-promote-and-roll-back-by-copying) |
| Install and run it on Windows (PowerShell, no Hadoop) | [OPERATIONS_GUIDE.md: Windows](guides/OPERATIONS_GUIDE.md#4-drishti-on-windows) |
| Run and debug the server in IntelliJ IDEA and the console in PyCharm | [OPERATIONS_GUIDE.md (IDE)](guides/OPERATIONS_GUIDE.md#3-other-ways-to-build-and-run-maven-intellij-idea-and-pycharm) |
| Demo or QA on Windows with your own ENDUR files, offline | [DEPLOYMENT_AND_QA_GUIDE.md › Offline QA](DEPLOYMENT_AND_QA_GUIDE.md#11-offline-qa-with-your-own-trade-files) (a QA script) |
| Build a pack from JSON Lines, check it in CI, load data and drive the Screen Designer's review from a terminal | [CLI_REFERENCE.md](guides/CLI_REFERENCE.md) |
| Find the right tool (command, script or page) for a job, load a large JSON export into a Delta lake, schedule loads and maintenance | [CLI_REFERENCE.md](guides/CLI_REFERENCE.md) |
| Find your way around the top bar and its menus | [USER_GUIDE.md › The top bar](guides/USER_GUIDE.md#the-top-bar) |
| Read Drishti from a script, a notebook or Excel with a personal API token | [API_REFERENCE.md (clients)](guides/DEVELOPER_GUIDE.md#113-clients-scripts-notebooks-and-excel) |
| Run Python on the view you are looking at (`Alt+C`): re-price, recompute, pivot, chart | [USER_GUIDE.md › Calc: Python on any view](guides/USER_GUIDE.md#calc-python-on-any-view) |
| Open a view, and learn the command line and keys | [USER_GUIDE.md › The command line](guides/USER_GUIDE.md#the-command-line), [Keyboard](guides/USER_GUIDE.md#keyboard) |
| List entities to pick from (`TRD MX-200000`, `CPTY north`, `TRD PRODUCT_TYPE=FX_QUANTO`) | [USER_GUIDE.md › Pick lists](guides/USER_GUIDE.md#pick-lists-when-a-command-names-several-entities) |
| Page through a table, or walk it with the keyboard | [USER_GUIDE.md › Tables](guides/USER_GUIDE.md#tables-sorting-filtering-paging-and-the-keyboard) |
| Filter a table like Excel: tick values, conditions, dates, top 10 | [USER_GUIDE.md › Filter a table like Excel](guides/USER_GUIDE.md#filter-a-table-like-excel) |
| Understand what a view is showing you | [USER_GUIDE.md › Reading a view](guides/USER_GUIDE.md#reading-a-view) |
| Find entities by value (`TRD where QUANTITY > 200m …`) | [USER_GUIDE.md › Search by value](guides/USER_GUIDE.md#search-by-value) |
| Fill in a form instead of writing a search (`FORM trading <GO>`, Forms in the Views menu) | [USER_GUIDE.md › Query by form](guides/USER_GUIDE.md#query-by-form) |
| Choose what a panel shows with dropdowns, toggles and sliders; read a trade down to its fixings | [USER_GUIDE.md › Controls](guides/USER_GUIDE.md#controls-on-a-screen-choose-what-a-panel-shows), [A deep trade](guides/USER_GUIDE.md#a-deep-trade-legs-cashflows-and-fixings) |
| Read what is in the world-economy pack, where it comes from and under what licence (IMF data: internal demonstrations only) | [EXTERNAL_DATA.md (world-economy)](guides/EXTERNAL_DATA.md#the-world-economy-data-in-detail) |
| Pull external data into the lakes every day (run, schedule on Linux and Windows, monitor, write a feed), and where each source comes from (licence, internal-only flag) | [EXTERNAL_DATA.md](guides/EXTERNAL_DATA.md) |
| Look at a past date, or compare two dates | [USER_GUIDE.md › Business dates](guides/USER_GUIDE.md#business-dates-live-or-a-day-in-the-past), [Compare](guides/USER_GUIDE.md#compare-what-changed) |
| See what depends on an entity (F8) | [Impact guide](guides/USER_GUIDE.md#f8--impact) |
| Download CSV or JSON, print, or share a link | [USER_GUIDE.md › Export, print and share](guides/USER_GUIDE.md#export-print-and-share) |
| Send a view to a colleague with a note, read your inbox, discuss a number | [USER_GUIDE.md › Share a view with a note](guides/USER_GUIDE.md#share-a-view-with-a-note), [Your inbox](guides/USER_GUIDE.md#your-inbox), [Discussion](guides/USER_GUIDE.md#discussion) |
| Follow a share and a comment end to end (rights, masks, audit, retention) | [ARCHITECTURE.md §31, Share and Discussion end to end](architecture/ARCHITECTURE.md#3111-share-and-discussion-end-to-end) |
| Watch a list live, or be alerted when a figure crosses a line | [Monitors and alerts guide](guides/USER_GUIDE.md#monitors) |
| Put several views on one screen | [Workspaces guide](guides/USER_GUIDE.md#workspaces) |
| Change your theme, landing page or password | [USER_GUIDE.md › Your settings](guides/USER_GUIDE.md#your-settings) |
| Choose which packs you see | [USER_GUIDE.md › Domain packs](guides/USER_GUIDE.md#domain-packs-choosing-what-you-see) |
| Ask a model about the page you are on (off unless an administrator turns it on); connect model providers and MCP servers | [USER_GUIDE.md › Ask about this page](guides/USER_GUIDE.md#ask-about-this-page-when-your-administrator-has-turned-it-on), [AI_GUIDE.md › Model providers](guides/AI_GUIDE.md#1-model-providers), [AI_GUIDE.md › The MCP client: other servers' tools, behind Drishti's policy](guides/AI_GUIDE.md#2-the-mcp-client-other-servers-tools-behind-drishtis-policy) |
| Find the commands for a pack | [BUILD_GUIDE.md › The packs that ship](guides/BUILD_GUIDE.md#311-the-packs-that-ship), and the pack's own guide under *Help → Domain packs* |

### Change how screens look

| If you want to… | Read |
|---|---|
| Learn to build screens in the workbench, step by step, with a picture of each step | [tutorial 19, Part B](tutorials/19-build-a-pack-and-a-screen.md#part-b--design-a-screen-in-the-workbench-and-on-a-live-page) |
| Change a live page in plain words (Customize, Describe, Outline, Grid): a linked record on a click, refresh, let people edit | [tutorial 19](tutorials/19-build-a-pack-and-a-screen.md#on-a-live-page-the-sutra-designer), [RACHANA_REFERENCE.md › Screen designer and the Build workbench, section 31](guides/RACHANA_REFERENCE.md#31-the-sutra-designer-customize-describe-outline-and-grid) |
| Fragments of the UI kit, events, children, per-panel sources, `layout:` and `form:`: the grammar of every screen | [RACHANA_REFERENCE.md › Sutra Next: fragments, events, children, sources, layout and forms](guides/RACHANA_REFERENCE.md#sutra-next-fragments-events-children-sources-layout-and-forms), [BUILD_GUIDE.md section 11a](guides/BUILD_GUIDE.md#201-fragments-events-children-refresh-and-forms) |
| Learn what a Sutra is and write one, from the first panel to a tested screen | [BUILD_GUIDE.md](guides/BUILD_GUIDE.md#11-what-a-sutra-is-and-when-one-applies) |
| Choose or configure a panel kind | [RACHANA_REFERENCE.md › Panels: the twenty-two kinds, and developing a new one](guides/RACHANA_REFERENCE.md#panels-the-twenty-two-kinds-and-developing-a-new-one) |
| Add a new panel kind to Drishti | [RACHANA_REFERENCE.md: developing a panel kind](guides/RACHANA_REFERENCE.md#developing-a-panel-kind) |
| Edit a Sutra with live preview and submit it for review | [Screen designer guide](guides/RACHANA_REFERENCE.md#screen-designer-and-the-build-workbench) |
| Lay out nested documents (lists inside lists) | [Nested documents tutorial](guides/BUILD_GUIDE.md#6-sutras-over-deep-documents) |
| Choose the right panel kind | [RACHANA_REFERENCE.md › Panels: the twenty-two kinds, and developing a new one](guides/RACHANA_REFERENCE.md#panels-the-twenty-two-kinds-and-developing-a-new-one) |
| Build a new panel kind, end to end (grammar, engine, console, workbench, tests) | [RACHANA_REFERENCE.md: developing a panel kind](guides/RACHANA_REFERENCE.md#developing-a-panel-kind) |
| Look up a key, format, expression or problem code | [RACHANA_REFERENCE.md](guides/RACHANA_REFERENCE.md) |
| Get completion for Sutras in your own editor (the JSON Schema of the language) | [API_REFERENCE.md](guides/API_REFERENCE.md#216-catalogue-about-packs-sources-sutras) (`GET /api/v1/rachana/schema`) |
| Convert Markdown Sutras (`*.sutra.md`) to YAML | [runbooks/sutra-broken.md](guides/OPERATIONS_GUIDE.md#step-1a-a-sutramd-or-plain-yaml-file-drs-2004-drs-2009) |
| See how connectors, packs and Sutras fit together, and how the UI is bound to the backend at runtime | [ARCHITECTURE.md, Walk through one request](architecture/ARCHITECTURE.md#5-walk-through-one-request) |
| Understand the screens Drishti draws with no Sutra | [RACHANA_REFERENCE.md › Inference: the screens Drishti draws with no Sutra](guides/RACHANA_REFERENCE.md#inference-the-screens-drishti-draws-with-no-sutra) |
| See which Canadian and US regulatory reports need VaR or ES (OSFI BCAR and Pillar 3, FFIEC 102, Volcker and others), item by item with formulas, controls, the bank's data contracts, what Drishti covers and the roadmap | [REGULATORY_VAR_REPORTING.md](guides/REGULATORY_VAR_REPORTING.md) |
| The Screen Builder's shape engine, auto-design and API (JSON files → shape → design → Sutra) | [RACHANA_REFERENCE.md › Screen designer and the Build workbench, section 32](guides/RACHANA_REFERENCE.md#32-reference-the-shape-engine-auto-design-and-the-builder-api) |
| The Build workbench: Designs, one page for data, canvas, YAML and tests, and the plan | [RACHANA_REFERENCE.md › The Build workbench: the design](guides/RACHANA_REFERENCE.md#the-build-workbench-the-design) |
| Design of *About this page*: context-aware help built from the page's data, pack glossaries, the explain API and the plan | [RACHANA_REFERENCE.md › About this page: the design of context help](guides/RACHANA_REFERENCE.md#about-this-page-the-design-of-context-help) |
| Design of Drishti Rūpaka, BI inside Drishti benchmarked against Power BI | [ARCHITECTURE.md §28](architecture/ARCHITECTURE.md#28-business-intelligence-on-the-table-layer), [ARCHITECTURE.md §28 › Rupaka: the original design and every phase as built](architecture/ARCHITECTURE.md#28-business-intelligence-on-the-table-layer) |
| See what market risk oversight (the second line) needs from Drishti, why VaR moved day over day, breaches and escalation, and the proposed Drishti Oversight lens | [OVERSIGHT_LENS.md](architecture/LENSES.md#5-oversight-lens) |
| See what it takes to build a market risk cube like FIS Adaptiv's artiQ, better: a pivot with correct non-additive VaR and ES totals, sensitivities in the lake, FRTB SA and IMA, pre-deal pricing, every data contract, the screens, and the point-by-point comparison with FIS | [MR_CUBE_LENS.md](architecture/LENSES.md#6-mr-cube-lens) |
| See the counterparty credit risk cube on the same core: allocated exposure, the GLEIF group roll-up, limits by tenor with headroom, CVA and SA-CVA, SA-CCR, IMM, SIMM, wrong-way risk, collateral views, pre-deal pricing on paths, every data contract | [CCR_CUBE_LENS.md](architecture/LENSES.md#7-ccr-cube-lens) |
| See the Chief Risk Officer's lens: every risk appetite metric against its board limit (floors as headroom), what changed overnight and why, breaches and temporary limit increases to approve, the top 10, the world, data health and "safe to quote", Ask, the board pack; its mock-ups, the build checklist and every dataset and contract it needs | [CRO_LENS.md](architecture/LENSES.md#8-cro-lens) |
| See the Chief Information Officer's lens: is the risk data estate on time, trustworthy, secure, affordable and used; feeds against their service levels and the 7am-ready forecast, data quality per dataset, a BCBS 239 scorecard with its evidence, platform health, capacity, usage, security and change, the evidence pack; its mock-ups, the build checklist and every dataset and contract it needs | [CIO_LENS.md](architecture/LENSES.md#9-cio-lens) |
| **The BI target design** (authoritative): principles, the four languages (YAML, the Python-like expression language, Drishti SQL, Python) and the one logical plan, query, security, scale, governance, authoring UX, AI, APIs, the Power BI gap check and the waves A, B, C | [ARCHITECTURE.md §28](architecture/ARCHITECTURE.md#28-business-intelligence-on-the-table-layer) |
| Collaboration: share a view with a note, comment threads anchored to the data, the inbox, email, retention and legal hold; the design, what was built and the plan | [ARCHITECTURE.md §31](architecture/ARCHITECTURE.md#31-collaboration-write-back-and-approvals) |
| Show Drishti's live views and BI reports inside another web application without iframes (Drishti Elements: `<drishti-view>`, `<drishti-panel>`, `<drishti-report>`, token exchange, npm package) | [guides/EMBED_GUIDE.md](guides/EMBED_GUIDE.md#5-the-elements-one-by-one) (developer guide), [guides/EMBED_GUIDE.md](architecture/ARCHITECTURE.md#30-the-client-renderer-and-drishti-elements) (design) |
| Arrange a report for a phone or a tablet (`layouts.phone`, `layouts.tablet`: order, hide, height, wide; the designer's Phone and Tablet tabs) | [BI_GUIDE.md](guides/BI_GUIDE.md#r84-phone-and-tablet-layouts) |
| Say what a number should be (`tests:`: expected values that Check runs as a role and that stop a publish) | [BI_GUIDE.md](guides/BI_GUIDE.md#r912-expected-values-in-a-report) |
| Show "last 30 days" or "this quarter to date" (a relative-date slicer, business-day aware) | [BI_GUIDE.md](guides/BI_GUIDE.md#r99-slicers) |
| Rank, lag and moving averages in a measure (window functions) | [BI_GUIDE.md](guides/BI_GUIDE.md#r54-measures-the-expression-language) |
| Put live BI results (aggregates, pivots, rankings streamed by Pravaha, Flink or a custom BI server) in front of people as live views and boards | [DATA_ARCHITECTURE.md, live BI](architecture/DATA_ARCHITECTURE.md#live-bi-a-reports-live-tables) and [results computed by a streaming producer](architecture/DATA_ARCHITECTURE.md#results-computed-by-a-streaming-producer) |

### Add an industry or data

| If you want to… | Read |
|---|---|
| Build a pack, from an empty folder to a working view | [BUILD_GUIDE.md](guides/BUILD_GUIDE.md) |
| Make a pack from JSON Schema files and/or a folder of JSON Lines (Build → New pack, or the command line) | [DATA_GUIDE.md › Schema to pack: generate a pack from JSON Schema](guides/BUILD_GUIDE.md#8-a-pack-from-a-json-schema-build--new-pack) |
| Load packs, switch them off and on, assign them to users | [BUILD_GUIDE.md](guides/BUILD_GUIDE.md#312-turning-packs-on) |
| Generate a full pack from Python (Sutras, samples, guide, lake) | [BUILD_GUIDE.md › Generators](guides/BUILD_GUIDE.md) |
| Define a connector as a file, change it without a restart, manage it in Admin → Connectors | [guides/DATA_GUIDE.md section 9](guides/DATA_GUIDE.md#9-connector-files-and-admin--connectors) |
| Choose a connector, write a new plugin and test it | [DEVELOPER_GUIDE.md](guides/DEVELOPER_GUIDE.md#10-connectors-choosing-combining-and-writing-one) |
| Choose a connector and see a connector file for each plugin | [guides/DATA_GUIDE.md section 8](guides/DATA_GUIDE.md#8-connectors) (a section per plugin; each links to its full document under [connectors/](connectors/)) |
| Connect your own data by doing it once | [tutorial 15](tutorials/15-connect-your-data.md) |
| Know the shape of a command's data (fields, types, keys, links, checks) or draft the contract of a new kind | [contracts/packs/README.md](contracts/packs/README.md) and [contracts/README.md](contracts/README.md#16-contracts-of-every-command) (`drishti.py contract`) |
| Understand live updates (SSE, frames, reconnects) | [DATA_ARCHITECTURE.md › Live updates](architecture/DATA_ARCHITECTURE.md#1-live-updates) |

### Run and administer it

| If you want to… | Read |
|---|---|
| Create users, give roles and packs, read the audit log | [ADMIN_GUIDE.md](guides/ADMIN_GUIDE.md) |
| Turn a command (FRTB, EQV, …) or a pack off for everyone, with a reason; every server of a cluster follows | [ADMIN_GUIDE.md (Functions)](guides/ADMIN_GUIDE.md#20-functions-func-every-command-and-turning-one-on-or-off) |
| Define roles, or switch a pack off for everyone | [USER_GUIDE.md › Administration](guides/USER_GUIDE.md#administration) |
| Look up any setting of any kind (server, console, connectors, packs, editions, stores) and its environment variable: the one configuration document, also at Help → Configuration | [CONFIGURATION.md](admin/CONFIGURATION.md) |
| Run an instance as Drishti Risk (an edition), offer it as a lens, choose its Home landing, white-label it | [ADMIN_GUIDE.md › Lenses](guides/ADMIN_GUIDE.md#12-what-a-lens-is) |
| Install Calc's Python runtime, offline too, and decide who may use it | [USER_GUIDE.md › Installing](guides/USER_GUIDE.md#12-installing-the-python-runtime), [Roles](guides/USER_GUIDE.md#9-roles-who-may-use-calc) |
| Deploy, secure and monitor it; keep the lake bounded | [OPERATIONS_GUIDE.md](guides/OPERATIONS_GUIDE.md), [OPERATIONS_GUIDE.md › Lake maintenance: Admin → Lakes → Maintain and `lake maintain`](guides/OPERATIONS_GUIDE.md#16-lake-maintenance-and-scheduling); a cluster: [CLUSTER_GUIDE.md](guides/CLUSTER_GUIDE.md) |
| Run several servers as one cluster (Drishti\*net): `config/net/`, Admin → Cluster, the cluster key, routing, the console's failover | [CLUSTER_GUIDE.md › Several servers as one](guides/CLUSTER_GUIDE.md#1-several-servers-as-one-drishtinet), [tutorial 22](tutorials/22-a-cluster-of-three.md) |
| Run Python computations for connectors in worker pools: `config/compute/compute.yaml`, Admin → Compute, approving what a connector requires (four eyes), as many workers as you want, fan-out over nodes | [OPERATIONS_GUIDE.md › Compute workers](guides/OPERATIONS_GUIDE.md#15-compute-workers) |
| Fix something that is not working | [OPERATIONS_GUIDE.md](guides/OPERATIONS_GUIDE.md#21-troubleshooting), then the runbooks below |
| Know how fast it is, and how that is measured | [CLUSTER_GUIDE.md](guides/CLUSTER_GUIDE.md#16-tuning) |
| Know how many users a server holds, whether memory stays flat over a day, and how to load-test it | [BENCHMARK.md › Many users, soaks and memory](admin/BENCHMARK.md#213-many-users-soaks-and-memory) |
| Read every benchmark (VaR on one server and a cluster, exposure, the loaders, trades at scale, the stores compared), run each again, and see the extrapolations to 2.3M and 10M positions | [BENCHMARK.md](admin/BENCHMARK.md) |
| Lay a report out on paper: statements, page-exact PDF and Excel, one per recipient | [BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 24](guides/BI_GUIDE.md#r12-statements-paginated-reports-page-exact-one-per-reader), [the reports tutorial, Part C](guides/BI_GUIDE.md#part-c--statements-and-field-parameters) |
| See any entity over time (`TRD 1000279 60`, `EQ EQ-CSCA 3M`): one row per business day, changes only, DIFF | [USER_GUIDE › History](guides/USER_GUIDE.md#history-an-entity-over-time), [tutorial 1, Part E](tutorials/01-first-15-minutes.md#part-e--an-entity-over-time), [DATA_ARCHITECTURE.md › History queries: an entity over time](architecture/DATA_ARCHITECTURE.md#2-history-queries-an-entity-over-time) |
| Choose a theme (ten, three of them Terminal hues) | [USER_GUIDE.md › Themes](guides/USER_GUIDE.md#themes) |
| Serve millions of entities a day for years from Delta Lake | [DELTA_CONNECTOR.md](../plugins/drishti-plugin-delta/README.md) |
| Run the server on Windows, or choose the Delta engine (native or Hadoop) | [OPERATIONS_GUIDE.md: Windows](guides/OPERATIONS_GUIDE.md#4-drishti-on-windows), [DELTA_CONNECTOR.md › Engines](../plugins/drishti-plugin-delta/README.md#engines-native-and-hadoop) |
| Serve millions of entities a day from Aerospike, with recent history there and years in Delta Lake | [AEROSPIKE_CONNECTOR.md](../plugins/drishti-plugin-aerospike/README.md) |
| Read from Apache Cassandra, ScyllaDB or DataStax Astra: the table layout, the loader, days that appear whole | [CASSANDRA_CONNECTOR.md](../plugins/drishti-plugin-cassandra/README.md) |
| Serve millions of entities a day from PostgreSQL | [POSTGRES_CONNECTOR.md](../plugins/drishti-plugin-jdbc/README.md) |
| Serve data from plain JSON-lines files, the simplest store | [FILE_CONNECTOR.md](../plugins/drishti-plugin-file/README.md) |
| Serve documents kept as JSON objects in S3, MinIO or another S3-compatible store | [S3_CONNECTOR.md](../plugins/drishti-plugin-s3/README.md) |
| Read entities from an in-house HTTP/JSON service, one request per view | [REST_CONNECTOR.md](../plugins/drishti-plugin-rest/README.md) |
| Show live entities from Kafka topics, with history from a lake | [KAFKA_CONNECTOR.md](../plugins/drishti-plugin-kafka/README.md) |
| Send connectors, AI providers and MCP servers through an outbound HTTP proxy (login, no-proxy list, https via CONNECT) | [DATA_GUIDE.md section 10: proxy](guides/DATA_GUIDE.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers) |
| Connect a connector over TLS: private CA, PEM or PKCS12/JKS files, mutual TLS, Confluent Cloud, SASL | [DATA_GUIDE.md section 10](guides/DATA_GUIDE.md#10-tls-and-the-outbound-proxy), [KAFKA_CONNECTOR.md › Security](../plugins/drishti-plugin-kafka/README.md#security-tls-sasl-and-confluent) |
| Show live entities from ActiveMQ queues or topics | [ACTIVEMQ_CONNECTOR.md](../plugins/drishti-plugin-activemq/README.md) |
| Show live entities from RabbitMQ queues | [RABBITMQ_CONNECTOR.md](../plugins/drishti-plugin-rabbitmq/README.md) |
| Serve public rates and FX (NY Fed SOFR, ECB €STR and FX, US Treasury, FRED) | [FEEDS_CONNECTOR.md](../plugins/drishti-plugin-feeds/README.md) |
| Write a connector that computes its data on demand, and see two (news headlines for a name, a quote for a ticker) | [NEWS.md](../plugins/drishti-plugin-news/README.md), [YFINANCE.md](../plugins/drishti-plugin-yfinance/README.md), [DEVELOPER_GUIDE.md › Connectors that compute data on demand](guides/DEVELOPER_GUIDE.md#1011-connectors-that-compute-data-on-demand) |
| Compute a kind when a screen asks for it (typed parameters, a Python or Java engine, progress on the panel, recomputed on events) | [REACTIVE_CONNECTOR.md](connectors/types/reactive/README.md), [tutorial 19, Part C](tutorials/19-build-a-pack-and-a-screen.md#part-c--computed-panels-a-reactive-connector) |
| Measure VaR and ES for any set of trades, drill from the bank to a trade, capture and backtest, size the memory | [VAR_GUIDE.md](guides/VAR_GUIDE.md) (Part II is the data contract), [the VaR tutorial, Part A](guides/VAR_GUIDE.md#part-a--historical-var-from-the-bank-to-a-trade) |
| Compute FRTB-SA market-risk capital for any scope from the positions' sensitivities, critique each method choice and reproduce a run | [FRTB_SA_GUIDE.md](guides/FRTB_SA_GUIDE.md), [the FRTB-SA tutorial](guides/FRTB_SA_GUIDE.md#17-tutorial-frtb-sa-from-the-hand-built-cases-to-a-desk) |
| Compute ISDA SIMM initial margin per netting set (collect and post) from the same sensitivities, install a licensed version, compare with the counterparty's call | [SIMM_GUIDE.md](guides/SIMM_GUIDE.md), [the SIMM tutorial](guides/SIMM_GUIDE.md#14-tutorial-simm-from-the-hand-built-examples-to-a-netting-set) |
| Pivot market risk on the pivot engine (`MCUBE`): desks by risk class with totals re-aggregated (never summed), components, a cell's tail and why it moved, the DV01 ladder and a what-if | [BI_GUIDE.md section P29](guides/BI_GUIDE.md#p29-the-mr-cube-preset), [the pivot tutorial, Part C](guides/BI_GUIDE.md#part-c--the-mr-cube-desks-by-risk-class-with-the-right-totals) |
| Pivot any table, or VaR, with nested rows opened lazily and every total made from its own members; chart a selection; export to Excel with outline and formulas only where a figure adds up | [BI_GUIDE.md](guides/BI_GUIDE.md#part-1--pivot-cubes-and-explain), [the pivot tutorial, Part A](guides/BI_GUIDE.md#part-a--the-pivot-engine-trades-by-product-legs-by-desk-then-var-by-desk) |
| Write your own aggregation function (formula, SQL macro, Python, Java), prove it with the harness, share it after approval; explain why a figure moved in steps that add up; try a what-if | [BI_GUIDE.md sections P19 to 22](guides/BI_GUIDE.md#p19-custom-functions), [the pivot tutorial, Part B](guides/BI_GUIDE.md#part-b--custom-functions-why-did-it-move-and-what-if) |
| Measure counterparty exposure (EE, PFE, EPE, EEPE) for any scope, drill from the bank to a trade, collateral and netting, pre-deal what-if, official results | [EXPOSURE_GUIDE.md](guides/EXPOSURE_GUIDE.md) (Part II is the data contract), [the exposure tutorial](guides/EXPOSURE_GUIDE.md#part-iv--tutorial-exposure-from-the-bank-to-a-trade) |
| Switch on the Yahoo Finance quote connector | [YFINANCE.md](../plugins/drishti-plugin-yfinance/README.md) |
| See what the sample connector serves and how to switch it off | [DEMO_CONNECTOR.md](../plugins/drishti-plugin-demo/README.md) |
| Connect your data in the short version (a connector file) | [guides/DATA_GUIDE.md section 8](guides/DATA_GUIDE.md#8-connectors) |
| Understand the sample data every pack ships, and its live ticks | [DEMO_CONNECTOR.md](../plugins/drishti-plugin-demo/README.md) |
| Read your own database with your own SQL: several queries per kind | [JDBC_QUERIES.md](../plugins/drishti-plugin-jdbc/README.md#query-mode-the-queries) |
| Serve millions of entities a day from Apache Iceberg (Snowflake, Glue, Polaris, Trino) | [ICEBERG_CONNECTOR.md](../plugins/drishti-plugin-iceberg/README.md) |
| Serve Iceberg tables your Spark or Snowflake pipelines already write | [ICEBERG_EXTERNAL_CONNECTOR.md](../plugins/drishti-plugin-iceberg-external/README.md) |
| Serve a large book from one embedded DuckDB file, with no database server | [DUCKDB_CONNECTOR.md](../plugins/drishti-plugin-duckdb/README.md) |
| Serve entities from MongoDB documents | [MONGODB_CONNECTOR.md](../plugins/drishti-plugin-mongodb/README.md) |
| Serve today and recent days from Redis memory, with live updates | [REDIS_CONNECTOR.md](../plugins/drishti-plugin-redis/README.md) |
| Build demo data in any store, small to a million trades a day | [guides/DATA_GUIDE.md section 11](guides/DATA_GUIDE.md#11-the-demo-data) |

### Develop on it

| If you want to… | Read |
|---|---|
| Build, test and change the code; recipes for common changes | [BUILD_GUIDE.md › Part VIII. Changing Drishti's code: the developer guide](guides/DEVELOPER_GUIDE.md) |
| Understand the design: pipeline, grammar, inference, modules | [ARCHITECTURE.md](architecture/ARCHITECTURE.md) |
| See how server-side pricing (QuantLib) will work, and what is next on the roadmap | [AI_GUIDE.md › Server-side pricing: the quant service (proposed)](guides/AI_GUIDE.md#4-server-side-pricing-the-quant-service-proposed), [IMPLEMENTATION_PLAN.md › Roadmap](architecture/IMPLEMENTATION_PLAN.md#roadmap) |
| Call the REST API, or read the ViewModel contract | [API_REFERENCE.md](guides/API_REFERENCE.md) (OpenAPI at `http://localhost:18480/api/docs/ui`) |
| Know why something is the way it is | [adr/](architecture/adr/README.md): the architecture decision records |
| See how it was built, wave by wave, and what is still open | [IMPLEMENTATION_PLAN.md](architecture/IMPLEMENTATION_PLAN.md) |
| See the performance roadmap: Java 25, native libraries (BLAS, compression, JSON), embedded columnar engines (DuckDB, Polars) | [HIGH_PERFORMANCE_NATIVE.md](architecture/HIGH_PERFORMANCE_NATIVE.md) |
| See the four reference mockups the views reproduce | [requirements/](requirements/) |

## Every document, and when to read it

Every file under `docs/`, by folder. Documents marked *moved* are short pointers kept so old links still open.

### Guides (`guides/`)

| Document | Read this when… |
|---|---|
| [OPERATIONS_GUIDE.md](guides/OPERATIONS_GUIDE.md) | you install, configure, deploy, secure, monitor, back up or upgrade Drishti: install and run on one machine with each step explained, Maven and the IDEs, Windows, installing for other people, deploy, promote and roll back, the runbooks, troubleshooting and the error codes |
| [OPERATIONS_GUIDE.md (IDE)](guides/OPERATIONS_GUIDE.md#3-other-ways-to-build-and-run-maven-intellij-idea-and-pycharm) | you run the server from IntelliJ IDEA and the console from PyCharm: JDK and interpreter setup, run configurations, debugging, reloading changes, tests in the IDE |
| [DEPLOYMENT_AND_QA_GUIDE.md › Offline QA](DEPLOYMENT_AND_QA_GUIDE.md#11-offline-qa-with-your-own-trade-files) (a QA script) | you demo or QA on a Windows machine without internet, with your own ENDUR JSONL files replacing the synthetic ENDUR trades: setup, data, start, checks, a ten-page demo script |
| [OPERATIONS_GUIDE.md: Windows](guides/OPERATIONS_GUIDE.md#4-drishti-on-windows) | you run Drishti on Windows: a JDK 21 or newer (production runs 21) and Python, building or copying the jar, the native Delta engine (no Hadoop, no `winutils.exe`), the PowerShell scripts, what does not work there, troubleshooting |
| [USER_GUIDE.md](guides/USER_GUIDE.md) | you use the console: top bar, command line, pick lists, tables, views, dates, search, export, monitors, alerts, workspaces, settings, the Build workbench, administration |
| [OPERATIONS_GUIDE.md](guides/OPERATIONS_GUIDE.md#21-troubleshooting) | something is not working: each problem has what you see, how to check and the fix |
| [API_REFERENCE.md (clients)](guides/DEVELOPER_GUIDE.md#113-clients-scripts-notebooks-and-excel) | you read Drishti from a script, a notebook or Excel: personal API tokens and scopes, the Python client, Power Query |
| [USER_GUIDE.md › Calc: Python on any view](guides/USER_GUIDE.md#calc-python-on-any-view) | you run Python on a view (`Alt+C`): the `drishti` module, output, packs' snippets, roles, the security model, installing the runtime, measured sizes and speed, limits |
| [BUILD_GUIDE.md › Part V. Domain packs: the packs that ship, turning them on, who sees which](guides/BUILD_GUIDE.md#31-the-packs-that-ship-turning-them-on-who-sees-which) | you load, switch, assign or change a domain pack |
| [COMMAND_CATALOG.md](guides/COMMAND_CATALOG.md) | you want every command in one list: the command line's own words, the packs' codes and every mnemonic, with what it does, an example, its kind of data and whether it moves to the compute model (generated) |
| [BUILD_GUIDE.md](guides/BUILD_GUIDE.md) | you build a pack or need any `pack.yaml` key: kinds, links, roles, Sutras, tests, generators, signing; a worked help-desk pack |
| [PUBLIC_SECTOR_GUIDE.md](guides/PUBLIC_SECTOR_GUIDE.md) | you use or run the public sector (the economy module and its Public sector lens): its lens and functions (WDI, CBANK, NSTAT, OECD, PRESS, GDELT ...), the six packs and their data contracts, the six daily feeds (World Bank, FRED and the Bank of Canada, Statistics Canada, OECD, official press releases, GDELT), every source's licence and what is internal only; the companion is [tutorial 14](tutorials/14-public-sector-data.md) |
| [FUNDS_GUIDE.md](guides/FUNDS_GUIDE.md) | you use or refresh the funds pack: the 200 largest US mutual funds and ETFs from SEC data (FUND, the look-through, HELD, TRACK), the data contracts of `fund`, `fund-holder` and `index-funds`, the sources, licences and refresh script; the companion is [tutorial 13, Part C](tutorials/13-world-data.md#part-c--funds-and-etfs) |
| [BI_GUIDE.md](guides/BI_GUIDE.md#part-5--groupings-and-analytics) | you use the groupings and analytics computed when asked (LEIG, LEIC, SIC, ECG, CORR, EQB, the USEQ ratios, FACT changes, IDX totals, the PXH analytics): each command, its contract, the reconciliation and how it is computed; the companion is [the groupings tutorial](guides/BI_GUIDE.md#tutorial-groupings-and-analytics-computed) |
| [DATA_GUIDE.md › Schema to pack: generate a pack from JSON Schema](guides/BUILD_GUIDE.md#8-a-pack-from-a-json-schema-build--new-pack) | you have JSON Schema (and perhaps JSON Lines) and want a whole pack: the rules, the `x-drishti-*` annotations, Build → New pack, the command line |
| [CLI_REFERENCE.md](guides/CLI_REFERENCE.md) | you work from a terminal or a pipeline: `tools/drishti.py` (`sutra`, `pack new|check|about-check|publish|install`, `data ingest|load|landed|loads`, `server packs`, `design create|check|propose|approve`), every option with real output, recipes, JSON formats, exit codes |
| [DATA_GUIDE.md › Telling Drishti new data has landed](guides/DATA_GUIDE.md#1-telling-drishti-new-data-has-landed) | your ETL has loaded a batch and Drishti should refresh, verify, check alerts, tell people and flag late data: the load API and CLI, expectations, the Data loads page, the token scope, recipes |
| [OPERATIONS_GUIDE.md](guides/OPERATIONS_GUIDE.md#8-deploy-promote-and-roll-back-by-copying) | you ship what you built to servers: bundle, verify, deploy by copying or from Admin → Packs, promote, roll back; Admin → Artifacts (install and uninstall safely) |
| [EXTERNAL_DATA.md (world-economy)](guides/EXTERNAL_DATA.md#the-world-economy-data-in-detail) | you use or refresh the real public data in the world-economy pack: sources, licences (IMF data: internal demonstrations only), the pipeline that regenerates them |
| [EXTERNAL_DATA.md](guides/EXTERNAL_DATA.md) | the External data help page: every external source of every pack (URL, licence, internal only or not), the feed that pulls it (`drishti.py feed run <feed>`, `--snapshot` for the committed demo data), the internal-only rule |
| [BI_GUIDE.md](guides/BI_GUIDE.md#part-3--reports-and-dashboards) | what BI is and is not built, the BI menu and keyword, the four powers, workspace roles, items and versions, audiences, shipped files, BI settings, the CLI |
| [the reports tutorial, Part A](guides/BI_GUIDE.md#part-a--your-first-bi-report) | build a report from scratch in the designer (cards, a map, a bar, a slicer), view, click and drill, publish, filter like Power BI, slicers across packs, a phone, two datasets in one report, relative dates, a tablet and the entity view |
| [RACHANA_REFERENCE.md › Rachana-EL expressions](guides/RACHANA_REFERENCE.md#rachana-el-expressions) | the Python-like expression language (DEL) of measures, derived columns, filters and row rules: grammar, every function, problems and fixes, recipes |
| [API_REFERENCE.md › Drishti SQL](guides/API_REFERENCE.md#8-drishti-sql-apiv1bisql) | read-only Drishti SQL over one model: `MEASURE('name')`, joins along relationships, `AS OF`, `EXPLAIN`, SQL view datasets |
| [BI_GUIDE.md › Datasets: the YAML, the expression language and the security rules](guides/BI_GUIDE.md#r5-models-and-datasets-what-a-report-reads) | the YAML reference, aggregates and functions, security, modes, layers and rollups, the query, DRS-9010 to 9027, what is not built |
| [BI_GUIDE.md › Reports: the YAML, the visuals and how they behave](guides/BI_GUIDE.md#r9-the-report-file-pages-visuals-filters-and-slicers) | the YAML reference, the 30 visuals and their wells (Vega-Lite included; the three 2.1 Perspective types drawn as Drishti's grid), filters and top-N, format (schema v2: typed per visual family, conditional formatting, themes), interactions, tooltip pages, small multiples, the matrix, how the page's state becomes a dataset query, what people can do, checking, security, what is not built |
| [BI_GUIDE.md › Publishing and governance: versions, review, certification, lineage, impact, environments](guides/BI_GUIDE.md#r17-publishing-and-governance-versions-review-certification-lineage-impact-environments) | saved states and published versions, the diff, who reviews and what they see, when certification falls, what impact analysis compares, the bundle and its dry run, the audit actions, what is not built |
| [BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 24](guides/BI_GUIDE.md#r12-statements-paginated-reports-page-exact-one-per-reader) | statement pages of a report (`layout: statement`): paginated, page-exact paper (size, orientation, margins, header and footer with page numbers, page breaks, repeated column titles, groups kept together), the period and parameters, PDF and Excel, one statement per recipient through subscriptions, the statement designer (the Paper tab), the API and `drishti.py bi statement`, DRS-9038 |
| [BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 23](guides/BI_GUIDE.md#r14-boards-report-pages-of-live-tiles) | board pages of a report (`layout: board`) and their tiles, how 2.3 boards were converted, alerts, the viewer and kiosk, who sees what, the checks and their codes, settings, the API, what is not built (live tables: [BI_GUIDE.md](guides/BI_GUIDE.md#r57-live-tables); live visuals and Perspective: [BI_GUIDE.md](guides/BI_GUIDE.md#r131-a-live-report)) |
| [tutorial 19, Part B](tutorials/19-build-a-pack-and-a-screen.md#part-b--design-a-screen-in-the-workbench-and-on-a-live-page) | you are new to the Build workbench: a screen from your own documents, with a picture of each step |
| [tutorial 19](tutorials/19-build-a-pack-and-a-screen.md#on-a-live-page-the-sutra-designer) | you want to change a live page with the Sutra designer: Customize, Describe, Outline and Grid |
| [RACHANA_REFERENCE.md › Screen designer and the Build workbench](guides/RACHANA_REFERENCE.md#screen-designer-and-the-build-workbench) | you design or edit a Sutra in the browser (the Sutra designer, or the Build workbench), save it and submit it for review |
| [BUILD_GUIDE.md](guides/BUILD_GUIDE.md#11-what-a-sutra-is-and-when-one-applies) | you are learning to write Sutras: anatomy, matching, labels, paths, source, row groups, testing and CI, the checklist |
| [RACHANA_REFERENCE.md](guides/RACHANA_REFERENCE.md) | you are writing a Sutra and need the exact key, panel option, format, expression or problem code |
| [RACHANA_REFERENCE.md › Panels: the twenty-two kinds, and developing a new one](guides/RACHANA_REFERENCE.md#panels-the-twenty-two-kinds-and-developing-a-new-one) | you choose, configure or debug a panel: all twenty-two kinds, what each means, how it behaves, an example and a picture |
| [RACHANA_REFERENCE.md: developing a panel kind](guides/RACHANA_REFERENCE.md#developing-a-panel-kind) | you add a new panel kind to Drishti |
| [BUILD_GUIDE.md › Part VIII. Changing Drishti's code: the developer guide](guides/DEVELOPER_GUIDE.md) | you change Drishti's code: layout, build, tests, gates, and recipes |
| [API_REFERENCE.md](guides/API_REFERENCE.md) | you call the REST API from a program, or need the ViewModel contract |

### Pointers to guides that moved

| Document | Read this when… |
|---|---|
| [RACHANA_REFERENCE.md › Panels: the twenty-two kinds, and developing a new one](guides/RACHANA_REFERENCE.md#panels-the-twenty-two-kinds-and-developing-a-new-one) | you followed an old link to the panels guide (PANEL_KINDS.md and PANEL_DEVELOPER_GUIDE.md, both now in RACHANA_REFERENCE.md) |
| [BUILD_GUIDE.md](guides/BUILD_GUIDE.md#11-what-a-sutra-is-and-when-one-applies) | you followed an old link to the Rachana guide (RACHANA_GUIDE.md, now BUILD_GUIDE.md) |
| [BUILD_GUIDE.md › Testing](guides/BUILD_GUIDE.md#24-testing-expectyaml-sutra-lint-sutra-test-and-pack-check) | you followed an old link to the headless Sutra tool (SUTRA_CLI.md): testing is section 15 there; the tool itself is [CLI_REFERENCE.md section 15](guides/CLI_REFERENCE.md#the-headless-sutra-tool) |
| [IMPLEMENTATION_PLAN.md](architecture/IMPLEMENTATION_PLAN.md) | you followed an old link to architecture/TESTDATA_FROM_SEEDS_PLAN.md (every old link and help address goes to the section that took it over) |
| [LENSES.md](guides/ADMIN_GUIDE.md#12-what-a-lens-is) | you followed an old link to admin/EDITIONS_GUIDE.md, architecture/LENSES.md (every old link and help address goes to the section that took it over) |
| [APPROVALS_GUIDE.md](guides/APPROVALS_GUIDE.md) | you followed an old link to architecture/APPROVALS_GUIDE.md, guides/WRITE_BACK_GUIDE.md (every old link and help address goes to the section that took it over) |
| [USER_GUIDE.md](guides/USER_GUIDE.md) | you followed an old link to guides/ANALYSE.md, guides/PYTHON_CALC.md, guides/QUERY_BY_FORM.md (every old link and help address goes to the section that took it over) |
| [AI_GUIDE.md](guides/AI_GUIDE.md) | you followed an old link to architecture/AI_MANAGEMENT.md, architecture/AI_PROVIDERS.md, architecture/MCP_CLIENT.md, architecture/QUANT_SERVICE.md (every old link and help address goes to the section that took it over) |
| [OPERATIONS_GUIDE.md](guides/OPERATIONS_GUIDE.md) | you followed an old link to admin/COMPUTE.md, admin/LAKE_MAINTENANCE.md, admin/NET.md (every old link and help address goes to the section that took it over) |
| [CLUSTER_GUIDE.md](guides/CLUSTER_GUIDE.md) | you followed an old link to admin/CLUSTER_DEPLOYMENT.md (clusters, AWS and scale, now its appendices), architecture/AWS_DELTA_LAKE.md, architecture/AWS_DEPLOYMENT.md, architecture/DRISHTI_NET.md, architecture/SCALE_STRATEGY.md, architecture/SUPER_SCALE.md (every old link and help address goes to the section that took it over) |
| [BUILD_GUIDE.md](guides/BUILD_GUIDE.md) | you followed an old link to guides/DEVELOPER_GUIDE.md, guides/PACKS.md (every old link and help address goes to the section that took it over) |
| [DATA_GUIDE.md](guides/DATA_GUIDE.md) | you followed an old link to guides/DATA_FORMATS.md, guides/DATA_LOADS.md, guides/EXTERNAL_LAKES.md, guides/SCHEMA_TO_PACK.md (every old link and help address goes to the section that took it over) |
| [EXTERNAL_DATA.md](guides/EXTERNAL_DATA.md) | you followed an old link to guides/PUBLIC_DATA.md (listed as External data) |
| [DATA_ARCHITECTURE.md](architecture/DATA_ARCHITECTURE.md) | you followed an old link to architecture/CONTENT_STORE.md, architecture/DATABASE.md, architecture/DRISHTI_ICEBERG.md, architecture/HISTORY_QUERIES.md, architecture/LIVE.md, architecture/REACTIVE_CONNECTORS.md (every old link and help address goes to the section that took it over) |
| [EMBED_GUIDE.md](guides/EMBED_GUIDE.md) | you followed an old link to architecture/ELEMENTS.md, architecture/GENUI.md, guides/ELEMENTS.md (every old link and help address goes to the section that took it over) |
| [RACHANA_REFERENCE.md](guides/RACHANA_REFERENCE.md) | you followed an old link to architecture/BUILD_WORKBENCH.md, architecture/CONTEXT_HELP.md, architecture/INFERENCE.md, architecture/SUTRA_NEXT.md, architecture/TABLE_CHARTS.md, guides/PANELS.md, guides/SCREEN_DESIGNER.md (every old link and help address goes to the section that took it over) |
| [RACHANA_REFERENCE.md › Rachana-EL expressions](guides/RACHANA_REFERENCE.md#rachana-el-expressions) | you followed an old link to guides/EXPRESSIONS.md, guides/SQL.md (every old link and help address goes to the section that took it over) |
| [BI_GUIDE.md](guides/BI_GUIDE.md#part-3--reports-and-dashboards) | you followed an old link to guides/BI_GOVERNANCE.md, guides/DATASETS.md, guides/DISTRIBUTION.md, guides/PYTHON_BI.md, guides/REPORTS.md (every old link and help address goes to the section that took it over) |
| [ARCHITECTURE.md §28](architecture/ARCHITECTURE.md#28-business-intelligence-on-the-table-layer) | you followed an old link to architecture/BI_MODEL_SPEC.md, architecture/BI_RUNS.md, architecture/RUPAKA.md (every old link and help address goes to the section that took it over) |

### Architecture and design (`architecture/`)

| Document | Read this when… |
|---|---|
| [ARCHITECTURE.md](architecture/ARCHITECTURE.md) | you want to understand how the pieces fit: pipeline, grammar, inference, graph, modules |
| [RACHANA_REFERENCE.md › Inference: the screens Drishti draws with no Sutra](guides/RACHANA_REFERENCE.md#inference-the-screens-drishti-draws-with-no-sutra) | a view looks different from what you expected and *How this view was built* says `inference` |
| [VIEWMODEL.md](architecture/VIEWMODEL.md) | you read or render the JSON the server returns for every entity screen: every field, the data of all 22 panel kinds, live frames and patches, masks, versioning and the JSON Schema (`GET /api/v1/viewmodel/schema`) |
| [DATA_ARCHITECTURE.md › Live updates](architecture/DATA_ARCHITECTURE.md#1-live-updates) | you need to know how values tick: streams, frames, coalescing, reconnects, one channel per browser, shared by its tabs |
| [RACHANA_REFERENCE.md › The Build workbench: the design](guides/RACHANA_REFERENCE.md#the-build-workbench-the-design) | you want the design of the Build workbench: Designs, one page for data, canvas, YAML and tests, and the plan |
| [RACHANA_REFERENCE.md › About this page: the design of context help](guides/RACHANA_REFERENCE.md#about-this-page-the-design-of-context-help) | you want the design of *About this page*: the page's data, pack glossaries and `about.yaml`, the explain API, the Ask layer and the plan |
| [ARCHITECTURE.md §31](architecture/ARCHITECTURE.md#31-collaboration-write-back-and-approvals) | you want to know how *share with a note* and comment threads work and why: pins to the business date and generation, recipients' own rights, masks on user text, the inbox and email outbox, retention, legal hold, export, with a note on what was built differently from the design (steps 1 to 7 built; chat bridges and snapshots, phase 2, are not) |
| [ARCHITECTURE.md §28 › Rupaka: the original design and every phase as built](architecture/ARCHITECTURE.md#28-business-intelligence-on-the-table-layer) | you want the design of Drishti Rūpaka, BI inside Drishti benchmarked against Power BI: the BI page (`RUPAKA <GO>`), datasets, the query engine, reports, the designer, workspaces and catalogue, streaming BI, Python interfaces, AI hooks off by default, build plan (design) |
| [ARCHITECTURE.md §28](architecture/ARCHITECTURE.md#28-business-intelligence-on-the-table-layer) | you want the single authoritative BI design (built wave by wave): principles and non-goals, the Python-like expression language, Drishti SQL, Python and MQ meeting in one logical plan, query architecture, security at every tier, caching and scale, governance, the designers, AI, APIs, the Power BI gap check, W1 as built, the waves A, B, C and the release order after them: N (Drishti\*net), D (sharing and integration), I (AI), E (QA findings and production readiness) |
| [ARCHITECTURE.md §28 › The model layer: the specification](architecture/ARCHITECTURE.md#281-the-table-layer-and-models-in-rachana) | you want the design specification of wave W1: the dataset and data model YAML, implicit models, how consumers bind to one model, the model query language v1 (validation, compilation, standing queries, ten worked examples), pack-generated datasets, the migration of every sample, the UX flows and the W1 build plan (design, not built) |
| [EMBED_GUIDE.md › Drishti Elements: the design](architecture/ARCHITECTURE.md#30-the-client-renderer-and-drishti-elements) | you want the design of Drishti Elements: live views and BI reports inside other web applications as Web Components, no iframes; the embed API, token exchange, host registration, `<drishti-report>`, and which build steps are done |
| [guides/EMBED_GUIDE.md](guides/EMBED_GUIDE.md#5-the-elements-one-by-one) | you are embedding Drishti in your own application: a five-minute start, the token exchange (Node, Java, Python), versions, SRI and npm, the element reference, a cookbook, troubleshooting by code |
| [EMBED_GUIDE.md › GenUI: screens composed for an AI agent (design, not built)](architecture/ARCHITECTURE.md#30-the-client-renderer-and-drishti-elements) | you want the design of GenUI (not built; parked): an AI agent composes a screen from governed Sutra fragments with typed slots, Drishti binds it to live data as the viewer and streams it to another application through `<drishti-genui>` |
| [AI_GUIDE.md › AI management (design, not built)](guides/AI_GUIDE.md#3-ai-management-design-not-built) | you want the design of AI management (not built; parked): every AI setting, use and review in the console (providers, features, data protection, MCP tools, usage and cost, golden questions, audit, playground, AI drafts) |
| [DATA_ARCHITECTURE.md › History queries: an entity over time](architecture/DATA_ARCHITECTURE.md#2-history-queries-an-entity-over-time) | you want the design of history queries: `TRD 1000015 30 <GO>` shows an entity over its last 30 business days with data; the range grammar, the document and market screens, the pack's `history:` block, `GET /api/v1/history/{kind}/{id}`, the connector SPI and each store's fast path, limits, masks, Drishti\*net, build steps H1 to H14 for two builders, tests |
| [RACHANA_REFERENCE.md › Sutra Next: fragments, events, children, sources, layout and forms](guides/RACHANA_REFERENCE.md#sutra-next-fragments-events-children-sources-layout-and-forms) | you want the design of Sutra Next (built, write-back included): one grammar for every screen; the fragment library (`<name>.v<N>.fragment.yaml` in the Drishti UI kit, `config/ui-kit`, loaded whatever packs are enabled, with its assets and brand: `GET /api/v1/ui-kit`), the `doc:` docstring every fragment and Sutra carries and its lint (`DRS-2055`), composite fragments, the ir-curve screen rebuilt from fragments, `GET /api/v1/sutras/library`; then events, multi-pack sources, nested children, refresh control, forms, write actions, dynamic screens, the layout grid and the editor |
| [APPROVALS_GUIDE.md › The design and its reasons](architecture/ARCHITECTURE.md#31-collaboration-write-back-and-approvals) | you want the design of write-back (W1 to W7 built and built): editing and saving fields on a Sutra page, change requests in a fixed number of ordered lanes (one at a time per record), a durable shared edit log fenced by epoch across a cluster, version checks, four eyes, overlays folded into the next lake build, source commands through the outbox and connector `writes:`, off by default and controlled by roles, the change log kept 7 years |
| [REACTIVE_CONNECTOR.md](connectors/types/reactive/README.md) | you write or run a reactive connector (R1 to R4, built): the file (typed parameters, inputs read as the viewer, a Python engine over a pool's workers or a Java engine), reading a computed kind from a panel, the states a panel shows, triggers, private sandboxes, the HTTP API and the measured overhead |
| [DATA_ARCHITECTURE.md › Reactive connectors](architecture/DATA_ARCHITECTURE.md#4-reactive-connectors) | you want the design of reactive connectors (R1 to R4 built, R5 and R6 planned): connectors that compute their answer when asked and again on events (an applied write-back, a live feed, a new load, a schedule); the four connector modes; typed parameters on a panel's source; point-in-time stamps, ordering and idempotence; two users' answers never mixed; coalescing and incremental recompute; one as-of per screen and failure shown per panel; Drishti\*net |
| [BI_GUIDE.md](guides/BI_GUIDE.md#part-4--the-notebook-and-python) | you want the design of the compute workers (C1 to C3 built and built; C4 to C6 planned): out-of-process Python and Java workers, a connector's pinned requirements in locked environments approved by an administrator, the pricer interface (QLRisk as default, design only until its API is stable; QuantLib's own Java binding in an isolated worker), sandboxing and Windows |
| [VAR_GUIDE.md Part III](guides/VAR_GUIDE.md#24-where-the-engine-lives) | you want how the VaR engine works and stores data: where it lives, one business date in memory, the tiered memory and the hot file, sizes and the benchmark, sharding (planned), the P&L vector in the lake (the layout options compared and chosen, the rolling window, restatements, precision), and the design record (cubes, the overnight precomputation, phased delivery V0 to V6); the data model's positions and portfolios are its sections 15 and 5 |
| [DATA_ARCHITECTURE.md › The one Drishti database (design, not built)](architecture/DATA_ARCHITECTURE.md#6-the-one-drishti-database-design-not-built) | you want the design of the one Drishti database (design, not built): identity, the edit log and everything relational Drishti owns in one database, SQLite by default and PostgreSQL long term, no migrations (two complete schema files, a parity test, a version stamp and a copy command), configured in a file of its own; all planned, none of it in the code yet |
| [DATA_ARCHITECTURE.md › The content store: user content every node reaches](architecture/DATA_ARCHITECTURE.md#5-the-content-store-user-content-every-node-reaches) | you want to know where user content every cluster node reaches is kept: the content store (get, compare-and-set put, list, delete, changes), its filesystem, SQLite, PostgreSQL and S3 backends in `config/store/store.yaml`, Designs as its first user with the one-time import, and the ordered list of what moves next |
| [ARCHITECTURE.md §28 › BI runs: every BI query as a bounded, visible job](architecture/ARCHITECTURE.md#287-bi-runs-every-bi-query-as-a-bounded-visible-job) | you want the design of BI runs (built and built): every BI query through one bounded asynchronous pipeline (submit, plan with a metadata-only estimate, admission, run, store, notify), answered inline within about 2 s or as a visible job; interactive, known-heavy and ad-hoc-heavy tiers; the Runs tab in the BI Workspace and the bell; results keyed by fingerprint, data version and security signature, re-masked or recomputed when shared; fairness over the work classes, cancellation, restart and the cluster; super scale as the architecture at every size |
| [RACHANA_REFERENCE.md › Chart any table: the design](guides/RACHANA_REFERENCE.md#chart-any-table-the-design) | you want the design of chart any table (built and built): a Chart icon and K on every table (Sutra panels, search results and pick lists, history, BI tables) opening one shared `drishti_table-chart.js` popup over the pivot engine and ECharts; line, bar, area, pie, scatter and combo with series, split, Top N and two axes; smart defaults; chart all N through the panel records, the search pivot or a BI run; masks, themes and keys; save, export, Promote to Sutra as a `chart` panel, Open in BI |
| [PARALLEL_LOADER.md](architecture/DATA_ARCHITECTURE.md#10-the-parallel-lake-loader-drishti-lakeload) | you load millions of JSON Lines rows (2.3M scenario PVs) into Delta or Iceberg: the parallel lake loader (drishti-lakeload): the multi-threaded pipeline, the memory budget and spill, one commit per date, the shard-aligned layout and load manifest, measured at 10K and sized for 2.3M |
| [DATA_ARCHITECTURE.md › drishti-iceberg: Drishti's own Apache Iceberg engine](architecture/DATA_ARCHITECTURE.md#3-drishti-iceberg-apache-iceberg-on-iceberg-core-with-no-hadoop) | you want the design of drishti-iceberg: Drishti's own Apache Iceberg engine with no Hadoop, so Iceberg runs on Windows; what of the spec it covers, catalogs, reads, commits, interop tests, the two Iceberg connectors |
| [CLUSTER_GUIDE.md](guides/CLUSTER_GUIDE.md) | **the deployment reference** for a clustered Drishti: every role (edge, consoles, coordinators, VaR and exposure shard owners, BI, data-serving, loaders, compute, database, lake) with counts and sizes at pilot, 2.3M and 10M positions; gossip (SWIM), rendezvous placement, handover, drain and every failure with its timing; VaR sharding (built) and exposure sharding (designed); high availability, load balancing and zero-downtime upgrades; on-premises and AWS targets; the bill of materials; built against planned; and in its appendices Drishti\*net in full, Drishti on AWS, the Delta lake in S3, the scale strategy and the super-scale design |
| [CLUSTER_GUIDE.md › Drishti on AWS: ECS, EFS and S3](guides/CLUSTER_GUIDE.md#12-drishti-on-aws-ecs-efs-and-s3) | you deploy Drishti on AWS (scripts in `deploy/`: the ECS images in `deploy/docker/ecs/`, access points, Cloud Map, task and service definitions in `deploy/aws/ecs/`): ECS with the server and the console as separate containers, every written path on EFS at the same path (read-only root), lakes in S3, Drishti*net on task addresses through Cloud Map |
| [CLUSTER_GUIDE.md › A high-performance Delta lake on AWS, past 200 TB](guides/CLUSTER_GUIDE.md#13-the-delta-lake-in-s3-at-scale) | you set up the Delta lake for Drishti in Amazon S3 so it stays fast past 200 TB: why S3 and not EFS, first-time setup step by step with the scripts of `deploy/aws/` (buckets, IAM, conditional-write check, tiers by business date, EFS access points, Cloud Map, ECS tasks, schedules, alarms), the layout and partition keys, ids and promoted columns, file and row-group sizes, the daily load (`lake build` then `lake publish`), backfill, restatement, maintenance, caches on EFS or NVMe, sizing, cost and runbooks; what is built and what is planned |
| [ARCHITECTURE.md › Super scale (design, not built)](architecture/ARCHITECTURE.md#3213-super-scale-design-not-built) | you want the design for 500 GB a day kept seven years (not built, release not decided): 500 GB of JSON a day; one Delta lake in S3 shared by many servers and consoles, with diagrams: tables by year, a loader fleet with a day register, products computed when loading, range maps instead of id maps, each server owning a share of (table, day) slices on the Drishti*net ring |
| [architecture/LENSES.md › The design](architecture/LENSES.md#10-the-lens-mechanism-the-design) | you want the design of editions and lenses as built: one setting (`drishti.edition`) for a focused product such as Drishti Risk, the edition file, what each side reads, the three Risk landings, the lens for one person |
| [CLUSTER_GUIDE.md › Drishti*net: many servers, one Drishti](guides/CLUSTER_GUIDE.md#1-several-servers-as-one-drishtinet) | you want the design of Drishti*net (the operator's guide is [CLUSTER_GUIDE.md › Several servers as one](guides/CLUSTER_GUIDE.md#1-several-servers-as-one-drishtinet), section 19 says what was built): several servers that find each other by gossip over HTTP, tell each other the packs and kinds they serve, and answer any client as one, forwarding what they cannot serve themselves |
| [DATA_ARCHITECTURE.md section 13](architecture/DATA_ARCHITECTURE.md#13-var-sharding-how-it-is-built) | you want the design of VaR sharding across Drishti*net (off by default): the shard map by legal entity and desk, owners by rendezvous hashing with replicas and no consensus protocol, each owner building its shards from the lake, the scatter-gather with its exactly-once check, handover, drain, failure cases, settings, and the measure-agnostic machinery VaR plugs into |
| [CLUSTER_GUIDE.md](guides/CLUSTER_GUIDE.md#15-sizing-memory-cpu-and-disk) | you want the one design for how Drishti uses memory, CPU and disk (design, not built: the diet D1–D10 and O1–O9): where every kind of data lives, every cache and memory-mapped file today with its bound (the unbounded ones flagged), freshness without a signal, the mapped skeleton, one cache budget, disk caps and a free-space guard, Admin → Caches and Health → Disk, the GC per role on Java 21 and 25, capacity at 2.3M and 10M positions, the benchmark and soak method, and the decisions |
| [AI_GUIDE.md › Model providers](guides/AI_GUIDE.md#1-model-providers) | you configure or build on the model providers: one interface, one file per provider in `config/ai/`, off by default, the admin page and the API |
| [AI_GUIDE.md › The MCP client: other servers' tools, behind Drishti's policy](guides/AI_GUIDE.md#2-the-mcp-client-other-servers-tools-behind-drishtis-policy) | you connect MCP servers (their tools, resources and prompts) behind Drishti's policy: one file per server in `config/mcp/`, off by default |
| [AI_GUIDE.md › Server-side pricing: the quant service (proposed)](guides/AI_GUIDE.md#4-server-side-pricing-the-quant-service-proposed) | you want the design of server-side pricing (QuantLib) and how it would reach the screen (proposed, not built; ADR-019); its deployment shape is replaced by the compute workers of [BI_GUIDE.md](guides/BI_GUIDE.md#part-4--the-notebook-and-python) |
| [VAR_PLAYGROUND.md](guides/VAR_GUIDE.md) | you want the design of the VaR Playground (design, not built; planned): every VaR figure worked in a grid with an explanation panel and exported as an .xlsx with real formulas (Main, Config, Data, FX, PnL, Explain, Provenance) that recalculates in Excel, LibreOffice and Google Sheets and equals the engine to the cent; up to 500 positions from the VaR screen's scope; the playground family and the rule that every metric ships with its playground |
| [IMPLEMENTATION_PLAN.md, 2.9.20](architecture/IMPLEMENTATION_PLAN.md#2920-ifrs-9-expected-credit-loss-proposed) | you want the gist of the design of the IFRS 9 expected credit loss engine (design, not built; proposed for 2.9.20): staging, PD, LGD, EAD, weighted scenarios, overlays, POCI, why the provision moved, the data contracts, the playground and the hand-built cases |
| [IMPLEMENTATION_PLAN.md, 2.9.21 and 2.9.22](architecture/IMPLEMENTATION_PLAN.md#2921-and-2922-financial-crime-aml-fraud-fcu-proposed) | you want the gist of the design of the financial crime engines (design, not built; proposed for 2.9.21 and 2.9.22): trade surveillance, transaction monitoring and threshold tuning, network analytics, customer risk rating, sanctions and PEP screening, fraud scoring, alerts and cases, FINTRAC reporting, rule effectiveness and data protection |
| [IMPLEMENTATION_PLAN.md](architecture/IMPLEMENTATION_PLAN.md) | you want to know which waves shipped in which release, what is still open, the release rules and the themed releases 2.9.8 to 2.9.14 (decided 2026-10-31), and 2.9.7's Drill B |

### Lenses (`lenses/`)

| Document | Read this when… |
|---|---|
| [architecture/LENSES.md](architecture/LENSES.md) | you want the lenses side by side: who each is for, its status, its document |
| [RISK_LENS.md](architecture/LENSES.md#1-risk-lens) | you want Drishti Risk as a product: what risk managers near the desks ask (the morning, intraday, the close, month-end), what the lens answers today, the gaps and the proposals (RL rows) |
| [TRADING_LENS.md](architecture/LENSES.md#2-trading-lens) | you want Drishti Trading as a product: what traders and desk heads ask, what the lens answers today (my books, my P&L, my VaR and limits, the new-trade check), the gaps and the proposals (TL rows) |
| [MARKET_LENS.md](architecture/LENSES.md#3-market-lens) | you want Drishti Market as a product: what the market-data team asks, what the lens answers today (the desk, the explorer, the quality checks), the gaps and the proposals (ML rows) |
| [WORLD_LENS.md](architecture/LENSES.md#4-world-lens) | you want Drishti World as a product: what macro, research and context users ask, what the lens answers today, the gaps and the proposals (WL rows) |
| [OVERSIGHT_LENS.md](architecture/LENSES.md#5-oversight-lens) | you want what market risk oversight (the second line, the CRO's function) needs from Drishti (design, not built): their questions by day, month and year, each need with its status (built, partly, planned, not built), design sketches for the gaps (VaR change explanation that adds up exactly, breaches and escalation, one limit catalogue, sensitivity limits, the RNIV register, concentration, stress, liquidity), the proposed Drishti Oversight lens and its three landings, position sensitivities (what the lake holds, what is needed, a proposed `position-sensitivity` contract, volume), the roadmap rows RO1 to RO15 |
| [CRO_LENS.md](architecture/LENSES.md#8-cro-lens) | you want the proposed CRO lens (design, not built; layouts decided): what the Chief Risk Officer asks each morning and before the board, what Drishti answers today, the screens and every interaction, the four mock-ups with a build checklist per panel, the complete list of data with contracts for the risk appetite framework, liquidity, capital, financed emissions, breaches, actions and temporary limit increases, the lens and edition design, a proof of concept after the cube POCs, the CRO rows |
| [CIO_LENS.md](architecture/LENSES.md#9-cio-lens) | you want the proposed CIO lens (design, not built; layouts decided): what the Chief Information Officer, the data office and the security team ask, what Drishti records today (loads, expectations, contracts, data versions, audit and access logs, the cluster), the screens, the four mock-ups with a build checklist per panel, the complete list of data with contracts for feed service levels, feed status, dependencies, data-quality results, platform metrics, cloud cost, usage events, entitlements, secrets, incidents, changes and the BCBS 239 assessment, BCBS 239 principle by principle, the lens and edition design, a proof of concept, the CIO rows |

### Data contracts (`contracts/`)

| Document | Read this when… |
|---|---|
| [contracts/README.md](contracts/README.md) | you deliver data to Drishti or build a computed measure: the rules every contract shares (the common risk lake, keys, types, dates, signs, vectors, delivery, validation, retirement) and the contracts of the computed measures |
| [contracts/samples/README.md](contracts/samples/README.md) | you write the production query or ETL job that must emit a data type: one page per type with a real record as folded JSON, every field explained, the fields of other rows and a checklist (generated by `tools/testdata/sample_docs.py`) |
| [contracts/packs/README.md](contracts/packs/README.md) | you want the shape of any command's data: every mnemonic with its contract (class, status, what replaces it), a page per pack (generated by `drishti.py contract docs`) |

### Connectors (`connectors/`)

| Document | Read this when… |
|---|---|
| [guides/DATA_GUIDE.md section 8](guides/DATA_GUIDE.md#8-connectors) | you want the short version of connecting data: choose a connector, write it as a connector file |
| [DEVELOPER_GUIDE.md](guides/DEVELOPER_GUIDE.md#10-connectors-choosing-combining-and-writing-one) | you choose a connector, write a new source plugin, or test one with the testkit contracts; each connector's settings and walk-through are in its own `*_CONNECTOR.md` |
| [guides/DATA_GUIDE.md section 9](guides/DATA_GUIDE.md#9-connector-files-and-admin--connectors) | you define connectors: one YAML file each in `config/connectors`, an example per store, how packs and files fit, live reload, Admin → Connectors, the API and CLI, moving off `application.yaml` |
| [DATA_GUIDE.md section 10](guides/DATA_GUIDE.md#10-tls-and-the-outbound-proxy) | a connector must talk TLS: the shared `tls.*` settings, PEM, PKCS12 and JKS, mutual TLS, converting certificates, expiry warnings, rotation, troubleshooting |
| [DATA_GUIDE.md section 10: proxy](guides/DATA_GUIDE.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers) | a site reaches the internet only through a forward proxy: `proxy:` per connector, AI provider or MCP server, the site default `drishti.http.proxy.*`, no-proxy, https via CONNECT, masking, what each connector does, troubleshooting |
| [DEMO_CONNECTOR.md](../plugins/drishti-plugin-demo/README.md) | you want to know what the sample connector serves, how it ticks, how packs supply samples, and how to switch it off |
| [guides/DATA_GUIDE.md section 11](guides/DATA_GUIDE.md#11-the-demo-data) | you need demo data in Delta Lake, PostgreSQL or Aerospike, small for a laptop or a million trades a day |
| [FILE_CONNECTOR.md](../plugins/drishti-plugin-file/README.md) | you serve data from JSON-lines files: the layout, one file per kind per day, the index of a day, loading, sizes, measurements |
| [DELTA_CONNECTOR.md](../plugins/drishti-plugin-delta/README.md) | you run Drishti over a large Delta Lake (a million trades a day for seven years): layout, writers, reads, memory, maintenance, measurements, and the two engines (native without Hadoop, or Hadoop) |
| [ICEBERG_CONNECTOR.md](../plugins/drishti-plugin-iceberg/README.md) | you run Drishti over Apache Iceberg: the layout, path-based and REST catalogs, delete files, time travel, the loader and maintenance, sizing, measurements, and the two engines (native without Hadoop, or the Iceberg library) |
| [ICEBERG_EXTERNAL_CONNECTOR.md](../plugins/drishti-plugin-iceberg-external/README.md) | you serve Iceberg tables other engines write (Spark, Snowflake, Glue, Flink, dbt): rows as documents, ids and dates from columns or the table's metadata, every column searchable, an id index for unsorted tables, read-only |
| [DUCKDB_CONNECTOR.md](../plugins/drishti-plugin-duckdb/README.md) | you run Drishti on one embedded DuckDB file: the layout, the loader and its file swap, the shared read-only instance, read paths, retention, sizing, memory, measurements |
| [POSTGRES_CONNECTOR.md](../plugins/drishti-plugin-jdbc/README.md) | you run Drishti on PostgreSQL at scale: partitioned table, promoted columns, COPY loader, monthly retention, sizing, measurements |
| [JDBC_QUERIES.md](../plugins/drishti-plugin-jdbc/README.md#query-mode-the-queries) | you read your own schema with SQL: the entity, its parts, ids, columns and reverse queries, parameters, naming, performance |
| [MONGODB_CONNECTOR.md](../plugins/drishti-plugin-mongodb/README.md) | you run Drishti on MongoDB: a document per entity per day, the narrow columns collection, indexes, parallel day reads, retention, sharding, measurements |
| [REDIS_CONNECTOR.md](../plugins/drishti-plugin-redis/README.md) | you run Drishti on Redis: key layout, zstd dictionaries, column chunks, live pub/sub, TTL, sizing, measurements |
| [AEROSPIKE_CONNECTOR.md](../plugins/drishti-plugin-aerospike/README.md) | you run Drishti on Aerospike at scale: record layout, loader, partition-parallel scans, TTL retention, sizing, measurements |
| [CASSANDRA_CONNECTOR.md](../plugins/drishti-plugin-cassandra/README.md) | you run Drishti on Apache Cassandra, ScyllaDB or Astra: table layout, generations, the loader, retention, sizing, measurements |
| [S3_CONNECTOR.md](../plugins/drishti-plugin-s3/README.md) | you serve documents from S3 or an S3-compatible store: the key layout, listing and caching, credentials, cost of large buckets |
| [REST_CONNECTOR.md](../plugins/drishti-plugin-rest/README.md) | you read entities from an HTTP/JSON service: paths, headers, generations, timeouts, what it cannot do, load on the service |
| [FEEDS_CONNECTOR.md](../plugins/drishti-plugin-feeds/README.md) | you switch on the public feeds: each source's URL and parsing, entities and ids, schedules, history, offline mirrors |
| [NEWS.md](../plugins/drishti-plugin-news/README.md) | you switch on the news connector or write one like it: ids, the filtering steps, settings, privacy, failures, how it is built |
| [YFINANCE.md](../plugins/drishti-plugin-yfinance/README.md) | you switch on the Yahoo Finance quote connector (samples only): ids, figures, settings, failures |
| [KAFKA_CONNECTOR.md](../plugins/drishti-plugin-kafka/README.md) | you serve live entities from Kafka: state and ticks modes, the index and disk cache, offsets, ordering, restarts, reconnection, pairing with a lake; TLS, SASL, Confluent and Schema Registry |
| [ACTIVEMQ_CONNECTOR.md](../plugins/drishti-plugin-activemq/README.md) | you serve live entities from ActiveMQ: queues and durable topics, the state store, its durability and disk budget, acknowledgement, failover, sizing |
| [RABBITMQ_CONNECTOR.md](../plugins/drishti-plugin-rabbitmq/README.md) | you serve live entities from RabbitMQ: queues and bindings, prefetch and acknowledgement, the state store, its durability and disk budget, recovery, sizing |
| [DEVELOPER_GUIDE.md](guides/DEVELOPER_GUIDE.md#10-connectors-choosing-combining-and-writing-one) | you followed an old link to the plugin guide (moved to DEVELOPER_GUIDE.md) |

### Running it (`admin/`)

| Document | Read this when… |
|---|---|
| [ADMIN_GUIDE.md](guides/ADMIN_GUIDE.md) | you create users, define roles, assign packs, reset passwords, set up single sign-on or read the audit log |
| [CONFIGURATION.md](admin/CONFIGURATION.md) | you need any setting's name, default, environment variable and meaning: the one and only configuration document, every key of every kind generated from the files (`tools/settings_catalogue.py`), searchable in the console at Help → Configuration |
| [CLUSTER_GUIDE.md › Tuning](guides/CLUSTER_GUIDE.md#16-tuning) | you want the server's targets, to measure your own installation, or to tune it (every measured benchmark: [BENCHMARK.md](admin/BENCHMARK.md)) |
| [ADMIN_GUIDE.md › Lenses](guides/ADMIN_GUIDE.md#12-what-a-lens-is) | you run an instance as Drishti Risk (`drishti.edition`), switch back, offer the risk lens, choose a Home landing, customise or white-label an edition, or make your own |
| [CLUSTER_GUIDE.md › Several servers as one](guides/CLUSTER_GUIDE.md#1-several-servers-as-one-drishtinet) | you run several servers as one cluster (Drishti\*net; real addresses only and the last administrator kept across nodes): a cluster of three, the files of `config/net/`, membership, capability digests, security, routing, the console and several servers, Admin → Cluster, drain, sizing, troubleshooting |
| [OPERATIONS_GUIDE.md › Compute workers](guides/OPERATIONS_GUIDE.md#15-compute-workers) | you run compute workers (steps C1 to C3, built): pools and how many workers (per pool and per node), fair scheduling, fan-out over Drishti\*net nodes, limits and the sandbox, `requires:`, locked environments and their four-eyes approval, Admin → Compute, Windows, the load test |
| [OPERATIONS_GUIDE.md › Lake maintenance: Admin → Lakes → Maintain and `lake maintain`](guides/OPERATIONS_GUIDE.md#16-lake-maintenance-and-scheduling) | you keep Delta tables bounded and fast: retention, compaction, checkpoints and vacuum from Admin → Lakes or `lake maintain`, schedules, history |
| [BENCHMARK.md](admin/BENCHMARK.md) | you choose a store or size a server, or want any measured figure: what is measured and why, the harness and the exact commands, the machine and data; the 200,000-position VaR run on one server and a three-node sharded cluster (50 to 200 users, Java 21 and 25, G1 and ZGC), the VaR store, exposure, the Python and Java loaders, trades at scale, every store at 10,000 to 50,000 trades a day with linear fits checked against a million, memory and heap, and labelled extrapolations to 2.3M and 10M positions |

### Runbooks (`admin/runbooks/`)

| Runbook | Read this when… |
|---|---|
| [source-down.md](guides/OPERATIONS_GUIDE.md#201-source-down-or-slow) | a connector is down, slow, idle or failed to start; views fail with `DRS-1003`/`DRS-1004`; links say *pending* |
| [sutra-broken.md](guides/OPERATIONS_GUIDE.md#202-a-sutra-is-broken-or-an-edit-does-not-show) | a Sutra has problems, an edit does not show, or a view says `inference only` where you expected a Sutra |
| [live-latency-high.md](guides/OPERATIONS_GUIDE.md#203-live-latency-high-or-live-views-stop-ticking) | live views are slow, late or frozen, or the Live button keeps its amber ring |
| [sign-in.md](guides/OPERATIONS_GUIDE.md#204-users-cannot-sign-in) | users cannot sign in, are locked out, or are refused (`DRS-5010`, `DRS-5002`) |

### In-app guides (`../drishti-console/web/guides/`, also under *Help*)

| Guide | Read this when… |
|---|---|
| [nested-data.md](guides/BUILD_GUIDE.md#6-sutras-over-deep-documents) | your documents hold lists inside lists |
| [impact.md](guides/USER_GUIDE.md#f8--impact) | you use or configure F8 impact |
| [monitors-and-alerts.md](guides/USER_GUIDE.md#monitors) | you set up watchlists and alert rules |
| [workspaces.md](guides/USER_GUIDE.md#workspaces) | you arrange several live views on one screen |
| [sharing-and-discussion.md](../drishti-console/web/guides/sharing-and-discussion.md) | you send a view with a note, read your inbox or discuss a number |
| [layout-mode.md](guides/USER_GUIDE.md#layout-mode-arrange-a-view-your-way) | you arrange a view's panels for yourself (`Alt+L`) |
| [pivot-tab.md](guides/USER_GUIDE.md#pivot-slice-a-table-your-way) | you slice a table with Pivot (the pivot screen) |
| [screen-builder.md](guides/RACHANA_REFERENCE.md#1-start-bring-data) | you turn sample JSON files into a shape, the first two steps of the builder |

### Examples (`guides/examples/`)

Twelve working Sutras with their data, introduced in [guides/examples/README.md](guides/examples/README.md); each has the Sutra, a JSON document and a note, and opens as a copy in the workbench.

| Note | Shows |
|---|---|
| [all-panels-showcase.md](guides/examples/README.md#all-panels-showcase) | All twenty-two panel kinds on one screen, reading one self-contained bond trade. |
| [exposure-profile.md](guides/examples/README.md#exposure-profile) | Expected and potential future exposure over tenor, against a limit. |
| [linked-sources.md](guides/examples/README.md#linked-sources) | Panels that read other entities with `source: "link(id, kind)"`: the counterparty's netting sets, a netting set's exposure and a desk's pivot, all fetched live. |
| [map.md](guides/examples/README.md#map) | A country on the map: the world with the country highlighted, and the same country's regions beside it. |
| [market-charts.md](guides/examples/README.md#market-charts) | Market data: daily bars, a line of closes and a volatility surface. |
| [metric.md](guides/examples/README.md#metric) | A desk's headline numbers as big-number tiles: one large formatted, toned figure each, with a small change, a unit and a caption. |
| [operations-status.md](guides/examples/README.md#operations-status) | The back office on one screen: where a trade stands and what comes next. |
| [pivot-row-groups.md](guides/examples/README.md#pivot-row-groups) | A pivot whose `by` is a list: desks hold books, books hold product families, each level with a subtotal. |
| [pnl-explain.md](guides/examples/README.md#pnl-explain) | Where a trade's P&L came from, and how sensitive it is. |
| [relationships.md](guides/examples/README.md#relationships) | How entities relate: a group and its agreements as a graph, netting sets as tabs, and the entities a document refers to. |
| [risk-distribution.md](guides/examples/README.md#risk-distribution) | How risk is spread: scenario P&L as a histogram with VaR lines, books as points, a limit as a dial. |
| [tree-table.md](guides/examples/README.md#tree-table) | Tables and ladders whose rows hold their own children: an organisation of units, each with sub-units, to any depth. |
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
| 011-sutras-are-markdown-documents.md: ADR-011: Sutras are Markdown documents |
| [012-business-dates-and-delta-lake.md](architecture/adr/012-business-dates-and-delta-lake.md): ADR-012: Every read is for a business date; history lives in Delta Lake |
| [013-sutras-go-live-through-review.md](architecture/adr/013-sutras-go-live-through-review.md): ADR-013: Sutras go live through review |
| [014-single-sign-on-verified-by-the-server.md](architecture/adr/014-single-sign-on-verified-by-the-server.md): ADR-014: Single sign-on, verified by the server |
| [015-packs-inherit.md](architecture/adr/015-packs-inherit.md): ADR-015: Packs inherit, and the more specific pack wins |
| [016-one-console-many-servers.md](architecture/adr/016-one-console-many-servers.md): ADR-016: One console, many servers; the user picks one and signs in to it |
| [017-sutras-are-yaml.md](architecture/adr/017-sutras-are-yaml.md): ADR-017: Sutras are YAML, through and through |
| [018-signed-pack-registry.md](architecture/adr/018-signed-pack-registry.md): ADR-018: Packs come from a signed, versioned registry |
| [019-pricing-is-a-quantlib-service-behind-the-server.md](architecture/adr/019-pricing-is-a-quantlib-service-behind-the-server.md): ADR-019: Pricing is a QuantLib service behind the Drishti server |
| [020-collaboration-records-live-in-the-identity-database.md](architecture/adr/020-collaboration-records-live-in-the-identity-database.md): ADR-020: Collaboration records live in the identity database; the notice is a link, never  |
| [021-embedding-is-html-over-the-wire.md](architecture/adr/021-embedding-is-html-over-the-wire.md): ADR-021: Embedding is HTML over the wire for views, and the viewer's own code for reports |

### Quality assurance runs (`qa/`)

One summary of every run (what was tested, findings by severity, how each was fixed, the commits); the round reports, activity logs and
scripts are in the repository's history. The Windows QA script is a live procedure and stays.

| Document | What it holds |
|---|---|
| [qa/QA_HISTORY.md](../CHANGELOG.md) | every QA round, soak and documentation verification (2026-10-01 to 2026-10-21): when, on what, what it found and what was fixed, with the commits; the round reports are in git |
| [DEPLOYMENT_AND_QA_GUIDE.md › Offline QA](DEPLOYMENT_AND_QA_GUIDE.md#11-offline-qa-with-your-own-trade-files) | the QA and demo script for an offline Windows machine with your own ENDUR files |

### Records

| Folder | Read this when… |
|---|---|
| [requirements/](requirements/) | you want the four reference mockups the first views reproduced, and the logo and icon |
| [publications/](publications/) | you want the decks (each with a PDF beside it, each slide's notes naming their sources; estimates footnoted with how they were derived): **Drishti-Design-and-Evidence.pptx** (the idea, the design, editions and lenses, data and lakes, BI, risk computed on demand, security, the evidence, operating it, where it is going), **Drishti-VaR-Engine.pptx** (how VaR works, with 10-day and stressed VaR and the scenario matrix, data in, computed attributes, how it scales, the measured evidence and capacity for a sharded cluster, storage and deployment, operating it, the Risk, Market and World editions, regulatory reporting, the roadmap in order) and **Drishti-on-AWS.pptx** (the lake on S3 in Delta or Iceberg: how it works, the format choice, the risk lake, evidence, operating it, with setup, columns and runbooks in its appendix) |

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
| **Rachana** | The grammar Sutras are written in; **Rachana-EL** is its expression language (`$.QUANTITY > 0`) |
| **Inference** | Drishti laying out a view by itself from the shape of the data |
| **Pack** | An industry's commands, layouts, links, roles and sample data, in `config/packs/<domain>/<name>/` |
| **Source / connector** | Where documents come from: the demo samples, Delta Lake, PostgreSQL, Kafka, … A connector is one YAML file in `config/connectors` |
| **Rūpaka (BI)** | Business intelligence inside Drishti: datasets, reports, boards; the `BI` menu and keyword (`RUPAKA` is its older name) |
| **Form** | A pack's search form for a kind: `FORM <pack> <GO>` |
| **Business date** | The day you are looking at: **Live** (today, ticking) or a picked past date |
| **Known at** | A moment in time; the data as it was known then, before later corrections |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary and confidential.*
