<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti platform. Proprietary: see LICENSE. -->
# Documentation consolidation (release i7k, documentation only)

**Design only, 2026-11-24. No file is moved by this document.** Owner: "385 documents is a lot ... it is very hard to keep such a
large corpus consistent", "it is so hard to grasp such a large number of docs".

Every number below is counted from the Drishti repository at tag `v2.9.11.i_7j` (`git ls-tree -r`, `git show`), and from the four
product repositories at their i7b tags.

## 0. The answer in one table

Today: **400 tracked Markdown files, 224,475 lines**. 308 of them are edited by hand in whole or in part.

| Class | What it is | Today | Target |
|---|---|---|---|
| **Hand-written documents** | a document with one purpose, one audience, held to every gate | 282 (these three rows together) | **84 files = 74 documents** (the 11 BI pages are one guide, as `test_docs_gates.py` already counts a guide folder) |
| Designs not built | "design, not built"; frozen until built, then folded into a guide and deleted | | 5, in `docs/plans/` |
| Folder notes | says what a folder is and how to run what is in it, nothing about the product | | 7 |
| Connector READMEs | one per connector type under the README standard: hand-written text plus generated settings blocks | 22 | 22, shorter |
| Records | `CHANGELOG`, `RELEASE_NOTES`, `THIRD-PARTY-NOTICES`, `VENDOR.md` | 4 | 4 |
| **Generated references** | written by a tool from YAML or code, checked by `--check`, never edited | 81 (the pack and template READMEs only in part) | **82**, all whole |
| Not documentation | the vendored Elements copy, the module archetype, a test fixture, two sample pack guides | 11 | 11 |
| **Total tracked `*.md`** | | **400** | **215** |

**187 files go** (123 merged into a kept document and deleted, 64 deleted outright), 2 are new (one generated page, one folder note),
7 are renamed or moved. Files edited by hand, in whole or in part, fall from 308 to 122. The 64 outright deletions alone carry 42,961 lines, 20,154 of them
one generated page of samples (`docs/contracts/samples/trades.md`).

The lever is not shorter guides. It is three rules: **(a)** a pack, a module, a connector template and a contract describe themselves
in YAML and their page is generated; **(b)** a guide per feature absorbs every pack guide, module guide and tool README about that
feature; **(c)** a folder may carry a note, never a procedure.

Decisions the owner may want to overrule are in section 8; the lanes go ahead with the defaults there.

## 1. The target corpus

### 1.1 Hand-written documents (84 files, 74 documents)

| # | Document | Its single purpose | Audience |
|---|---|---|---|
| 1 | `README.md` | what Drishti is, the three blocks, where to start | everyone |
| 2 | `HOW_IT_FITS.md` (+PDF) | the picture: kernel, console, modules, products | newcomers |
| 3 | `docs/README.md` | the index: the Help map (generated block) and "if you want to" | everyone |
| 4 | `docs/DEPLOYMENT_AND_QA_GUIDE.md` (+PDF) | **install, first start and verify**, on Windows, Linux and macOS; the QA checklist | installers, QA |
| 5 | `docs/QUICKSTART_DEVELOPERS.md` (+PDF) | clean machine to first change; a path of links, owns no fact | new developers |
| 6 | `docs/guides/USER_GUIDE.md` | every feature of the console (absorbs sharing and discussion) | users |
| 7–17 | `docs/guides/bi/` (one guide, 11 pages): `README`, `PIVOT`, `PIVOT_SCALE_AND_CUBES`, `TABLES_AND_MODELS` (absorbs models for reports), `REPORTS`, `REPORT_FILE_FORMAT_STATEMENTS`, `LIVE_REPORTS_AND_BOARDS`, `DISTRIBUTION_AND_ALERTS`, `GOVERNANCE_AND_AI`, `NOTEBOOK_AND_PYTHON`, `GROUPINGS_AND_ANALYTICS` | business intelligence, guide and tutorial together (README absorbs the Power BI comparison) | analysts |
| 18 | `docs/guides/AI_GUIDE.md` | the assistant, tools, MCP, providers | everyone, admin |
| 19 | `docs/guides/APPROVALS_GUIDE.md` | corrections and the one approvals queue | operations, risk |
| 20 | `docs/guides/LIVE_RESULTS_GUIDE.md` | live results and blotters | users, builders |
| 21 | `docs/guides/VAR_GUIDE.md` | VaR: method, contract, screens, playground, tutorial (absorbs the market-risk pack guide) | market risk |
| 22 | `docs/guides/REGULATORY_VAR_REPORTING.md` | regulatory VaR reporting to cell level | risk, regulatory reporting |
| 23 | `docs/guides/EXPOSURE_GUIDE.md` | counterparty exposure (absorbs the counterparty-risk pack guide) | credit risk |
| 24 | `docs/guides/FRTB_SA_GUIDE.md` | FRTB-SA | capital |
| 25 | `docs/guides/SIMM_GUIDE.md` | SIMM | margin |
| 26 | `docs/guides/BANK_DATA_GUIDE.md` (renamed from `PVF_LOADING.md`) | **what a bank sends each day and how it is loaded**: PVF files, the CSV datasets, the daily runbook, trade products, market data, source systems | data teams, operators |
| 27 | `docs/guides/WORLD_DATA_GUIDE.md` | world and public data, every external source, the refresh scripts (absorbs 11 pack guides) | research, operators |
| 28 | `docs/guides/DATA_GUIDE.md` | connect: connectors in use, the load signal, formats, export | builders, operators |
| 29 | `docs/guides/DATA_LAKE_GUIDE.md` | lakes, the inbox, load rules, the data folder | builders, data teams |
| 30 | `docs/guides/FLIGHT_GUIDE.md` | data out (Arrow Flight) | developers |
| 31 | `docs/guides/BUILD_GUIDE.md` | packs and screens | builders |
| 32 | `docs/guides/RACHANA_REFERENCE.md` | every key, panel, expression and code of the screen language as Drishti runs it | builders |
| 33 | `docs/guides/examples/README.md` | the Sutra gallery: one section per example (absorbs 13 caption files) | builders |
| 34 | `docs/guides/EMBED_GUIDE.md` | embed Drishti in a page | developers |
| 35 | `docs/guides/DEVELOPER_GUIDE.md` | extend: connector, plugin, module; the code rules (absorbs the compute and asset-prefix notes) | developers |
| 36 | `docs/guides/IDE_GUIDE.md` | **every launcher and IDE step** (IntelliJ, PyCharm, VS Code) | developers |
| 37 | `docs/guides/API_REFERENCE.md` | every endpoint (absorbs the Help → Developer text) | developers |
| 38 | `docs/guides/CLI_REFERENCE.md` | **every `drishti.py` command**, its options and output | operators, CI |
| 39 | `docs/guides/OPERATIONS_GUIDE.md` | run it: production on Linux (absorbs `PRODUCTION_DEPLOYMENT.md`), monitoring, runbooks, troubleshooting | operators |
| 40 | `docs/guides/CLUSTER_GUIDE.md` | several servers as one, AWS, sizing (absorbs `deploy/**` READMEs) | operators |
| 41 | `docs/guides/ADMIN_GUIDE.md` | people, access, lenses, editions, switches | administrators |
| 42–52 | `docs/tutorials/NN-*.md` (11): first 15 minutes, AI assistant, correct and approve, a day of bank data, connect your data, load into a new lake, data out, build a pack and a screen, embed, run in production, a cluster of three | one worked example per feature, UI + CLI + YAML at every step | learners |
| 53–56 | `docs/knowledge/README.md`, `risk.md`, `data.md`, `world.md` | what the words mean; the glossary | everyone |
| 57 | `docs/admin/BENCHMARK.md` | every benchmark run and how to repeat it | operators, sales |
| 58 | `docs/strategy/COMPETITIVE_ANALYSIS.md` | the internal competitive analysis (absorbs the landscape) | owner |
| 59 | `docs/architecture/ARCHITECTURE.md` | how the system is built | developers |
| 60 | `docs/architecture/DATA_ARCHITECTURE.md` | how data is stored, loaded and read | developers |
| 61 | `docs/architecture/KERNEL_AND_MODULES.md` | kernel, modules, distributions, the access model (absorbs 3 documents) | developers |
| 62 | `docs/architecture/THIN_CONSOLE_DESIGN.md` | the console: thin tier, shared screens, Elements in Drishti (absorbs 2) | developers |
| 63 | `docs/architecture/LENSES.md` | the lens product document, with the mock-up index and build checklists | owner, developers |
| 64 | `docs/architecture/VIEWMODEL.md` | the ViewModel contract | embed developers |
| 65 | `docs/architecture/TEST_HARNESS.md` | how Drishti is tested; the documentation gates | developers |
| 66 | `docs/architecture/DECISIONS.md` (from `adr/`) | the 21 decision records, one section each | developers |
| 67 | `docs/architecture/FUNCTION_CATALOGUE.md` | each function's meaning, example and equivalents (the generator of `FUNCTIONS.md` reads it) | analysts, developers |
| 68 | `docs/contracts/README.md` | the shared rules of every data contract | data teams |
| 69–82 | `docs/contracts/{COLL,CVA,FRTB,GRP,LIM,MC,NSET,PNL,SACCR,SIMM,STR,TRADING_GROUPINGS,TVAL,WWR}.md` | the data contract of one computed measure each | the bank's data teams |
| 83 | `data/testdata/README.md` | the test data and the demo lakes | developers, QA |
| 84 | `modules/genomics/docs/guide.md` | the one module guide: the sample domain outside finance (absorbs its tutorial and 2 pack guides) | sales, module authors |

**Designs not built (5, moved to `docs/plans/`, unchanged):** `COUNTERPARTY_ENGINES`, `FINANCIAL_CRIME`, `IFRS9_ECL`,
`HIGH_PERFORMANCE_NATIVE`, `FIGMA_INTEGRATION`. After the move `docs/architecture/` holds only what is built, and a gate can say so.

**Connector READMEs (22):** `plugins/drishti-plugin-*/README.md` (13) and `docs/connectors/types/*/README.md` (9). The one home of
each connector. They stay one file per type because the server and the console serve them by type name
(`ConnectorTypeReadmeController`, `drishti-console/core/readmes.py`). They shrink: settings tables only from the generated block; no
install, start or secrets procedure (a link to `DATA_GUIDE.md`).

**Folder notes (7):** `config/bi/README.md`, `lib/README.md`, `external_lib/README.md`, `drishti-console/README.md`,
`examples/clients/README.md`, `tools/elements-demo/README.md`, `deploy/aws/README.md` (new, from three). Rule: at most 60 lines; what
the folder holds, how to run what is in it, a link to the document that owns the subject.

### 1.2 Generated references (82)

| Reference | Files | Written by | From |
|---|---|---|---|
| `docs/admin/CONFIGURATION.md` | 1 | `tools/settings_catalogue.py` | the two `application.yaml` files, the `@ConfigurationProperties` classes, the settings catalogue |
| `docs/admin/UNDER_CONSTRUCTION.md` | 1 | `tools/under_construction.py` | `config/under-construction.yaml` |
| `docs/guides/COMMAND_CATALOG.md` | 1 | `tools/command_catalog.py` | every pack's `pack.yaml` (mnemonics) and the contracts' classes |
| `docs/guides/FUNCTIONS.md` | 1 | `FunctionCataloguePage` (Java; its test fails on a difference) | the function registries |
| `docs/contracts/packs/*.md` | 20 | `tools/contracts.py docs` | `<pack>/contracts/<kind>.contract.yaml`; **new:** one JSON example per kind from `data/testdata` (replaces `contracts/samples/`) |
| `docs/contracts/CSV_FORMATS.md` (new) | 1 | `tools/mrloader/kind_docs.py` | `tools/mrloader/kinds/<kind>.yaml` (replaces 15 pages) |
| Pack `README.md` | 19 | `tools/packgen/readme.py` | `pack.yaml`, the samples, the Sutras, `config/about.yaml`; **new:** the intro is `about.yaml`'s `intro:` (replaces 19 `*.intro.md`) |
| Module `docs/README.md` | 13 | the module tooling under `tools/packgen/` (lane C confirms which of the 13 a tool writes today; one `--check` for all) | the module manifest (packs, kinds, engines, what it needs) |
| `config/connectors.examples/*/README.md` | 12 | `tools/connector_readmes.py` | the template YAML files (their header comments) and the settings catalogue; today only blocks are generated |
| `config/feeds/*.md` | 13 | `tools/feeds/feedreadme.py` | the feed YAML (source, licence, internal-only flag, schedule) |
| `docs/tutorials/README.md` | 1 | `tools/docs/help_map.py` (one more block) | `config/docs-features.yaml` and each tutorial's title |

Rule for all of them: the first line says "generated by X from Y; do not edit"; the tool has `--check`; one static test runs every
`--check`. Hand-written text inside a generated page is not allowed any more (CONFIGURATION.md's hand-written chapters move to
`OPERATIONS_GUIDE.md` or become `description:` fields of the catalogue).

### 1.3 What is deleted (187 files)

| Family | Files | Why |
|---|---|---|
| Pack guides (`**/packs/*/guides/*.md`) | 22 | a pack's facts are its generated README and contract; its story is in the feature's guide |
| Pack intro fragments (`tools/packgen/**/*.intro.md`) | 19 | the text lives in the pack's own `about.yaml` |
| `docs/contracts/samples/` | 20 | one example per kind inside the contract page; 34,858 lines of generated samples go |
| `tools/mrloader/docs/<kind>.md` | 15 | one generated page |
| Decision records | 21 | one `DECISIONS.md` |
| Sutra example captions | 13 | sections of the gallery's README |
| Placeholders under `data/` | 13 | nine lines each; set-up creates the folders (to prove first) |
| Module guides and tutorial | 8 | six of them are the same 42 lines with a different name |
| Example pack guides | 7 | sample prose nobody keeps current; one stays as the sample |
| Finished plans (`docs/plans/`, `DOCS_PLAN.md`) | 8 | done work; git keeps it |
| Architecture documents folded | 6 | into `KERNEL_AND_MODULES`, `THIN_CONSOLE_DESIGN`, the genomics guide |
| Mock-up READMEs and the two example pages of a design | 10 | into `LENSES.md` and `KERNEL_AND_MODULES.md` |
| Folder READMEs with procedures | 18 | procedure into its guide, at most a note stays |
| Others (`PRODUCTION_DEPLOYMENT`, two strategy pages, two console guides, one BI page, one embed README) | 7 | folded into the owner document |

## 2. The mapping: every one of the 400 files

Classes: **keep** (hand-written target), **generate**, **merge** (content into the named document, then the file is deleted),
**delete**, **rename**, **move**. "Lines" are today's. "Lane" is section 5's. "Code" is how many times a file that goes is named in
non-Markdown files (code comments, tests, YAML): each must be changed in the same commit. The 80 rows cover 400 files; a script
(`map.py`, rules in order, first match wins) proved that every tracked `*.md` matches exactly one row.

| # | Files today | Count | Lines | Action | New home | Lane | Code |
|---|---|---|---|---|---|---|---|
| 1 | `CHANGELOG.md`, `RELEASE_NOTES.md`, `THIRD-PARTY-NOTICES.md` | 3 | 11,015 | keep (record) | keep: the history and legal records (the only place version history lives) | A |  |
| 2 | `README.md`, `HOW_IT_FITS.md` | 2 | 576 | keep | keep | A |  |
| 3 | `bin/README.md` | 1 | 68 | merge | merge into `docs/guides/OPERATIONS_GUIDE.md` (commands); `bin/` goes (it holds only this file) | A | 0 |
| 4 | `compute/drishti-compute/README.md` | 1 | 86 | merge | merge into `docs/guides/DEVELOPER_GUIDE.md` | A | 0 |
| 5 | `config/bi/README.md` | 1 | 67 | keep (folder note) | keep as a folder note | C |  |
| 6 | `config/bi/{python,reports}/README.md` | 2 | 32 | merge | merge into `config/bi/README.md` | C | 0 |
| 7 | `config/connectors.examples/*/README.md` | 12 | 1,327 | generate | generate whole (today: hand text + generated blocks) by `tools/connector_readmes.py` from the template files and the settings catalogue | C |  |
| 8 | `config/feeds/*.md` | 13 | 1,776 | generate | keep generated (`tools/feeds/feedreadme.py`); no hand edits | C |  |
| 9 | `config/packs/core/*/README.md` | 3 | 920 | generate | generate whole by `tools/packgen/readme.py` (intro from `config/about.yaml`) | C |  |
| 10 | `config/packs/core/compute-demo/guides/compute-demo.md` | 1 | 74 | merge | merge into `docs/guides/bi/NOTEBOOK_AND_PYTHON.md` (only what the pack README lacks) | A | 0 |
| 11 | `config/packs/core/core/guides/{core,banking-data-model}.md` | 2 | 284 | merge | merge into `docs/guides/BANK_DATA_GUIDE.md` | A | 1 |
| 12 | `config/packs/core/gleif/guides/gleif.md` | 1 | 176 | merge | merge into `docs/guides/WORLD_DATA_GUIDE.md` | A | 0 |
| 13 | `data/dataload/**/README.md` (6), `data/delta/*/README.md` (7) | 13 | 127 | delete | delete: nine-line placeholders; the folder map is `DATA_LAKE_GUIDE.md` "The data folder" (lane C first proves that set-up creates each folder) | C | 0 |
| 14 | `data/testdata/README.md` | 1 | 398 | keep | keep (the one home of the test data and the demo lakes) | C |  |
| 15 | `deploy/aws/{delta-s3,iceberg,s3-tables}/README.md` | 3 | 204 | merge | procedures into `docs/guides/CLUSTER_GUIDE.md` (AWS); one folder note `deploy/aws/README.md` remains | A | 0 |
| 16 | `deploy/stack/README.md` | 1 | 172 | merge | merge into `docs/guides/CLUSTER_GUIDE.md` (the local cluster of three) | A | 0 |
| 17 | `docs/DEPLOYMENT_AND_QA_GUIDE.md`, `docs/QUICKSTART_DEVELOPERS.md`, `docs/README.md` | 3 | 2,884 | keep | keep | A |  |
| 18 | `docs/PRODUCTION_DEPLOYMENT.md` | 1 | 826 | merge | merge into `docs/guides/OPERATIONS_GUIDE.md` as its part "Production on Linux" | A | 2 |
| 19 | `docs/admin/BENCHMARK.md` | 1 | 2,123 | keep | keep (the one benchmark document) | B |  |
| 20 | `docs/admin/CONFIGURATION.md`, `docs/admin/UNDER_CONSTRUCTION.md` | 2 | 6,834 | generate | keep generated (`tools/settings_catalogue.py`, `tools/under_construction.py`) | B |  |
| 21 | `docs/architecture/{ACCESS_MODEL,DISTRIBUTIONS,DOMAIN_MODULES}.md` | 3 | 753 | merge | merge into `KERNEL_AND_MODULES.md` (as built) | B | 192 |
| 22 | `docs/architecture/{ARCHITECTURE,DATA_ARCHITECTURE,KERNEL_AND_MODULES,LENSES,VIEWMODEL,TEST_HARNESS,THIN_CONSOLE_DESIGN,FUNCTION_CATALOGUE}.md` | 8 | 17,261 | keep | keep (same names: code and tests cite them) | B |  |
| 23 | `docs/architecture/{COUNTERPARTY_ENGINES,FINANCIAL_CRIME,IFRS9_ECL,HIGH_PERFORMANCE_NATIVE,FIGMA_INTEGRATION}.md` | 5 | 3,151 | move (design, not built) | move to `docs/plans/` unchanged ("design, not built"; frozen until built, then folded into a guide and deleted) | B | 25 |
| 24 | `docs/architecture/DOCS_PLAN.md` | 1 | 337 | delete | delete (its own text says so); section 5 (the gates) moves into `TEST_HARNESS.md` | B | 2 |
| 25 | `docs/architecture/{ELEMENTS_IN_DRISHTI,SHARED_SCREENS}.md` | 2 | 634 | merge | merge into `THIN_CONSOLE_DESIGN.md` (the console: thin tier, shared screens, Elements) | B | 83 |
| 26 | `docs/architecture/GENOMICS_SHOWCASE.md` | 1 | 344 | merge | merge the as-built part into `modules/genomics/docs/guide.md`, delete (its status line says "nothing is built"; the pack exists) | B | 18 |
| 27 | `docs/architecture/adr/0*.md` | 21 | 954 | merge | merge into `docs/architecture/DECISIONS.md`, one section each, numbers kept as anchors | B | 2 |
| 28 | `docs/architecture/adr/README.md` | 1 | 42 | rename | becomes `docs/architecture/DECISIONS.md` | B | 0 |
| 29 | `docs/architecture/kernel-and-modules/**/*.md` | 3 | 126 | merge | merge the mock-up list into `KERNEL_AND_MODULES.md` section 17; delete the two example pages (real modules replace them) | B | 1 |
| 30 | `docs/architecture/mockups/capacity/README.md` | 1 | 154 | delete | delete (built; the page is documented in `OPERATIONS_GUIDE.md`) | B | 0 |
| 31 | `docs/connectors/types/*/README.md` | 9 | 2,514 | keep (connector README) | keep, one per built-in connector type; shrink to the standard | C |  |
| 32 | `docs/contracts/README.md` + the 14 computed-measure contracts (`COLL` … `WWR`) | 15 | 8,148 | keep | keep, one per measure (never merged) | B |  |
| 33 | `docs/contracts/packs/*.md` | 20 | 10,879 | generate | keep generated (`tools/contracts.py docs`); each kind gains its one JSON example | B |  |
| 34 | `docs/contracts/samples/*.md` | 20 | 34,858 | delete | delete: the generator writes one example per kind into `contracts/packs/<pack>.md` | B | 0 |
| 35 | `docs/guides/*.md` (the other 23) | 23 | 63,295 | keep | keep, same names | A |  |
| 36 | `docs/guides/COMMAND_CATALOG.md`, `docs/guides/FUNCTIONS.md` | 2 | 752 | generate | keep generated (`tools/command_catalog.py`, `FunctionCataloguePage`) | B |  |
| 37 | `docs/guides/PVF_LOADING.md` | 1 | 301 | rename | becomes `docs/guides/BANK_DATA_GUIDE.md` (feature R6, the bank's data) | A | 2 |
| 38 | `docs/guides/bi/*.md` (the other 11) | 11 | 11,619 | keep | keep (one guide, a folder of pages) | A |  |
| 39 | `docs/guides/bi/MODELS_AND_DATASETS_FOR_REPORTS.md` | 1 | 1,021 | merge | merge into `docs/guides/bi/TABLES_AND_MODELS.md` | A | 1 |
| 40 | `docs/guides/examples/README.md` | 1 | 40 | keep | keep; gains one section per example | A |  |
| 41 | `docs/guides/examples/<example>.md` (13 captions) | 13 | 346 | merge | merge into `docs/guides/examples/README.md`, one section each | A | 0 |
| 42 | `docs/guides/examples/embed/README.md` | 1 | 49 | merge | merge into `docs/guides/EMBED_GUIDE.md` | A | 0 |
| 43 | `docs/guides/examples/pack/helpdesk/guides/helpdesk.md` | 1 | 37 | keep (not documentation) | keep: the sample pack of tutorial 19 | A |  |
| 44 | `docs/knowledge/*.md` | 4 | 1,832 | keep | keep | A |  |
| 45 | `docs/lenses/mockups/*/README.md` | 6 | 424 | merge | merge into `docs/architecture/LENSES.md` (mock-up index and build checklists); the mock-up pages stay | B | 0 |
| 46 | `docs/plans/*.md` (the 7 of i7a, G4, the split, launchers, CSV loaders) | 7 | 4,940 | delete | delete: done work; git keeps it | B | 50 |
| 47 | `docs/strategy/BI_AND_POWER_BI.md` | 1 | 197 | merge | merge into `docs/guides/bi/README.md` (customer-facing) | A | 1 |
| 48 | `docs/strategy/COMPETITIVE_ANALYSIS.md` | 1 | 725 | keep | keep (internal) | A |  |
| 49 | `docs/strategy/COMPETITIVE_LANDSCAPE.md` | 1 | 253 | merge | merge into `COMPETITIVE_ANALYSIS.md` | A | 0 |
| 50 | `docs/tutorials/NN-*.md` | 11 | 6,463 | keep | keep (11 tutorials) | A |  |
| 51 | `docs/tutorials/README.md` | 1 | 143 | generate | generate (the tracks table from `config/docs-features.yaml` and each tutorial's title, by `tools/docs/help_map.py`) | A |  |
| 52 | `drishti-console/README.md` | 1 | 64 | keep (folder note) | keep as a folder note | C |  |
| 53 | `drishti-console/web/guides/developer.md` | 1 | 101 | merge | merge into `docs/guides/API_REFERENCE.md` (its opening section; Help → Developer shows that section) | A | 2 |
| 54 | `drishti-console/web/guides/sharing-and-discussion.md` | 1 | 41 | merge | merge into `docs/guides/USER_GUIDE.md` | A | 0 |
| 55 | `drishti-console/web/static/vendor/VENDOR.md` | 1 | 62 | keep (record) | keep (the vendor record) | C |  |
| 56 | `drishti-console/web/static/vendor/drishti-elements/3.3.2/**` | 5 | 595 | keep (not documentation) | untouched: the vendored product | C |  |
| 57 | `drishti-sdk/drishti-module-archetype/**/docs/*.md` | 3 | 31 | keep (not documentation) | untouched: the scaffolding a new module starts from | C |  |
| 58 | `examples/clients/README.md` | 1 | 107 | keep (folder note) | keep as the one example README | C |  |
| 59 | `examples/clients/{excel,java}/README.md` | 2 | 160 | merge | merge into `examples/clients/README.md` | C | 0 |
| 60 | `examples/packs/*/guides/*.md` (the other 7) | 7 | 774 | delete | delete (the example packs keep their YAML; their `config/help.yaml` entry goes) | C | 8 |
| 61 | `examples/packs/finance/guides/getting-started.md` | 1 | 84 | keep (not documentation) | keep: the sample of a pack guide | C |  |
| 62 | `lib/README.md`, `external_lib/README.md` | 2 | 65 | keep (folder note) | keep as folder notes | C |  |
| 63 | `lib/ext/README.md`, `external_lib/python/README.md` | 2 | 42 | merge | merge into the folder note above each | C | 0 |
| 64 | `modules/*/docs/README.md` | 12 | 327 | generate | generate whole from the module manifest | C |  |
| 65 | `modules/*/packs/*/README.md` | 16 | 5,917 | generate | generate whole by `tools/packgen/readme.py` (intro from `config/about.yaml`) | C |  |
| 66 | `modules/banking/packs/counterparty-risk/guides/counterparty-risk.md` | 1 | 208 | merge | merge into `docs/guides/EXPOSURE_GUIDE.md` (only what it lacks) | A | 0 |
| 67 | `modules/banking/packs/market-risk/guides/market-risk.md` | 1 | 176 | merge | merge into `docs/guides/VAR_GUIDE.md` (only what it lacks) | A | 0 |
| 68 | `modules/banking/packs/trading/guides/*.md` (2), `modules/market-data/packs/market-data/guides/*.md` (2) | 4 | 1,122 | merge | merge into `docs/guides/BANK_DATA_GUIDE.md` | A | 6 |
| 69 | `modules/{capital-markets,economy}/packs/*/guides/*.md` | 10 | 1,254 | merge | merge into `docs/guides/WORLD_DATA_GUIDE.md` | A | 0 |
| 70 | `modules/genomics/docs/guide.md` | 1 | 114 | keep | keep: the one module guide (the sample domain) | A |  |
| 71 | `modules/genomics/docs/tutorial.md` | 1 | 86 | merge | merge into `modules/genomics/docs/guide.md` | A | 0 |
| 72 | `modules/genomics/packs/*/guides/*.md` | 2 | 194 | merge | merge into `modules/genomics/docs/guide.md` | A | 0 |
| 73 | `modules/source-*/docs/guide.md` (6), `modules/trading/docs/guide.md` | 7 | 411 | merge | merge into `docs/guides/BANK_DATA_GUIDE.md` ("Source systems") | A | 0 |
| 74 | `plugins/drishti-connectors/src/test/resources/RECORDED.md` | 1 | 17 | keep (not documentation) | untouched: a test fixture note | C |  |
| 75 | `plugins/drishti-plugin-*/README.md` | 13 | 6,173 | keep (connector README) | keep, one per connector plugin; shrink to the standard | C |  |
| 76 | `testing/connectors/README.md` | 1 | 66 | merge | merge into `docs/architecture/TEST_HARNESS.md` | B | 0 |
| 77 | `tools/dev/asset_prefix_docs.md` | 1 | 70 | merge | merge into `docs/guides/DEVELOPER_GUIDE.md` | A | 3 |
| 78 | `tools/elements-demo/README.md` | 1 | 101 | keep (folder note) | keep as a folder note | C |  |
| 79 | `tools/mrloader/README.md`, `docs/README.md`, `docs/DAILY_RUNBOOK.md`, `samples/README.md` | 4 | 628 | merge | merge into `docs/guides/BANK_DATA_GUIDE.md` | A | 3 |
| 80 | `tools/mrloader/docs/<kind>.md` (15) | 15 | 1,771 | delete | replace by ONE generated `docs/contracts/CSV_FORMATS.md` (`tools/mrloader/kind_docs.py`, a section per kind) | B | 1 |
| 81 | `tools/packgen/**/*.intro.md` | 19 | 1,153 | merge | the text moves into each pack's `config/about.yaml` (`intro:`); the files go | C | 7 |

Totals: keep 82 + rename 2 (hand-written) · move 5 (designs) · connector READMEs 22 · folder notes 6 (+1 new) · records 4 ·
generate 81 (+1 new) · not documentation 11 · merge 123 · delete 64 = 400.

## 3. One home per fact

### 3.1 Who owns what

| Kind of fact | The one home | Everyone else |
|---|---|---|
| Install, prerequisites, first start, verify (all three systems) | `DEPLOYMENT_AND_QA_GUIDE.md` | links; `OPERATIONS_GUIDE.md` sections 2 and 4 become links |
| Production: services, TLS, secrets, database, backup, upgrade, runbooks | `OPERATIONS_GUIDE.md` | links |
| Cluster, AWS, sizing | `CLUSTER_GUIDE.md` | links |
| A `drishti.py` command: options, output, exit codes | `CLI_REFERENCE.md` | a guide may show the one command line it needs; a tutorial shows the command of its step |
| A mnemonic (console command) | generated `COMMAND_CATALOG.md` | named, never tabled |
| A setting: key, default, environment variable | generated `CONFIGURATION.md` | named inline; no table of settings anywhere else |
| A launcher, an IDE step | `IDE_GUIDE.md` | links |
| The build from source | `QUICKSTART_DEVELOPERS.md` (the path) and `DEVELOPER_GUIDE.md` (the detail) | links |
| A risk measure: method, formulas, screens, playground | its guide (`VAR`, `EXPOSURE`, `FRTB_SA`, `SIMM`); regulatory use in `REGULATORY_VAR_REPORTING.md` | Knowledge explains the words only |
| A measure's input data | its contract (`docs/contracts/<MEASURE>.md`) | the guide links |
| A field of a kind, its type, an example | generated `docs/contracts/packs/<pack>.md` | nobody repeats a field table |
| A CSV dataset's columns | generated `docs/contracts/CSV_FORMATS.md` | `BANK_DATA_GUIDE.md` links |
| A connector: settings, behaviour, limits | its README | `DATA_GUIDE.md` says how connectors are used, never a connector's settings |
| A pack: kinds, commands, sources, licence | its generated README | the feature guide tells the story and links |
| An external source: licence, internal-only, schedule | generated `config/feeds/<feed>.md` | `WORLD_DATA_GUIDE.md` links; the IMF notice comes from the feed YAML |
| A function | generated `FUNCTIONS.md` (+ `FUNCTION_CATALOGUE.md` for examples) | links |
| An endpoint | `API_REFERENCE.md` | links |
| A screen-language key or panel | `RACHANA_REFERENCE.md` | `BUILD_GUIDE.md` teaches, the reference lists |
| A benchmark figure | `BENCHMARK.md` | a guide quotes at most the headline and links |
| What changed, what was fixed | `CHANGELOG.md`, `RELEASE_NOTES.md` | never in a guide (gate exists) |
| A design decision | `DECISIONS.md` | links |
| A design not built | `docs/plans/<NAME>.md` | never described as available |

### 3.2 The check that catches a duplicated procedure

New: `tools/docs/dup_check.py`, run by `test_docs_gates.py` (static, seconds). It reads the hand-written corpus (the manifest of 4.3).

1. **Command runs.** From every fenced block it takes the command lines (`python3 tools/drishti.py …`, `py -3.13 tools\drishti.py …`,
   `mvn …`, `pip …`, `git clone …`), normalises them (system prefix, paths, versions) and forms runs of three in a row. The same run in
   two documents fails, unless one of the two is a tutorial (a tutorial must show its commands) or the pair is listed in
   `config/docs-owners.yaml` with a reason.
2. **Owned commands.** `config/docs-owners.yaml` names patterns that belong to one home: `drishti.py setup` and installer lines →
   `DEPLOYMENT_AND_QA_GUIDE`; `systemctl`, backup and upgrade lines → `OPERATIONS_GUIDE`; `start|stop cluster` → `CLUSTER_GUIDE`;
   `.run.xml` names → `IDE_GUIDE`. Such a line in a fenced block elsewhere (tutorials excepted) fails.
3. **Settings tables.** A table row whose first cell is a `drishti.*` key or a `DRISHTI_*` variable, outside `CONFIGURATION.md` and
   the connector READMEs' generated blocks, fails.
4. **Repeated text.** Paragraphs, numbered lists and tables are normalised and hashed in windows of 40 words. A window that appears
   in two hand-written documents fails; the message names both places.
5. **Folder notes.** A folder note over 60 lines, or one that names a setting or a `drishti.py` verb other than the one that runs its
   folder, fails.

It runs in report mode from step 0 (the count is the baseline each lane must bring down) and fails from the last step on.

## 4. Help, links, tests, PDFs

### 4.1 Help keeps working

How Help works today: `drishti-console/config/help.yaml` lists pages (`doc:` is a path under `docs/`, otherwise a console guide in
`web/guides/`); modules add pages through `modules/*/docs/help.yaml`, packs through `config/help.yaml`; `tools/docs/help_map.py`
writes the map in `docs/README.md` (55 pages in 8 sections today) and `test_docs_help_map.py` checks it; `help.yaml` also holds 52
context-help addresses (F1 and the `?` links) that name a guide **and a numbered section**.

| Change | What is done |
|---|---|
| `pvf-loading` → `BANK_DATA_GUIDE.md` | new slug `bank-data-guide`; the old slug joins the old-address redirects (operator how-tos need no Help page of their own; this one is the feature guide of R6) |
| `data-samples` (`contracts/samples/README.md`) | removed; redirect to the contracts page |
| `bi-report-models` | removed; redirect to `bi-tables-models` |
| `bi-and-power-bi` | removed; redirect to `bi-guide#drishti-bi-and-power-bi-compared` |
| `sharing-and-discussion` (console guide) | removed; redirect to `user-guide#share-a-view-with-a-note` |
| `developer` (console guide) | the page stays; its text is the opening section of `API_REFERENCE.md` (a few lines in `routes/help_routes.py`) |
| Sutra examples gallery | reads each example's section of `examples/README.md` instead of `<example>.md` (`help_routes.py`, one function) |
| Module Help (`modules/*/docs/help.yaml`) | pages that named pack guides or `guide.md` name the feature guide's section, or the generated README |
| Pack Help (`config/help.yaml` of 19 packs and 7 example packs) | the guide entry goes; Help → Domain packs shows the pack's generated README (the server already serves it: `PackOverviewController`) |
| The 52 context-help addresses | **no numbered section of a kept guide is renumbered**; merged text goes in as new sections at the end of a part. `test_help_links.py` proves every address |
| The map and the counts | `python3 tools/docs/help_map.py`; `CAPS["help"]` in `test_docs_gates.py` drops from 67 to the new count |

### 4.2 Links and names in code

- 8,493 relative links between Markdown files today. `test_docs_rot.py` (every relative link and anchor, every repository path, every
  command, setting and error code named) and `test_help_links.py` are the proof; they run after every lane and at the end.
- **Kept documents keep their names.** Tests name `VAR_GUIDE.md` 144 times, `ARCHITECTURE.md` 145, `TEST_HARNESS.md` 80,
  `RACHANA_REFERENCE.md` 71; code comments cite `docs/guides/bi/README.md section N` in dozens of classes. Only two files are renamed.
- Files that go are named about 410 times in non-Markdown files; 275 of these are five architecture documents (`ACCESS_MODEL` in 73
  files, `SHARED_SCREENS` 63, `DOMAIN_MODULES` 55, `DISTRIBUTIONS` 46, `ELEMENTS_IN_DRISHTI` 9), almost all in code comments.
- One table drives every rename: `tools/docs/moved.yaml` (old path → new path and anchor). It feeds the Help redirects, the sweep of
  code comments (`tools/docs/moved.py --apply`) and the hint the link check prints. The sweep is one commit; `moved.py --verify`
  proves that in every non-Markdown file it touched only comment lines and string literals of tests changed.
- A merged document's headings become headings of the same words in the new home, so `X.md#anchor` maps to `Y.md#anchor` by rule.

### 4.3 Tests changed in the same change

| Test | Change |
|---|---|
| `drishti-console/tests/test_docs_gates.py` | caps: guides 24, tutorials 11, references as counted, knowledge 4, help as counted; new: `docs/architecture/` holds no "not built" document; runs `dup_check.py`; its docstring stops naming `DOCS_PLAN.md` |
| `config/docs-features.yaml` | R6's guide becomes `guides/BANK_DATA_GUIDE.md`; references lose nothing |
| `config/docs-corpus.yaml` (new) + `test_docs_rot.py::test_the_corpus_is_the_whole_documentation` | the manifest: every tracked `*.md` with its class and, for a generated one, its tool. A file not in it fails; class counts over the targets of section 0 fail. This is what stops the corpus from growing back |
| `test_docs_help_map.py`, `test_help*.py` | follow `help.yaml`; `test_help_developer.py` reads the section of `API_REFERENCE.md` |
| `test_docs_pdfs.py`, `tools/docs/md_to_pdf.py` | the list of PDFs (4.4) |
| `tools/test_readmes.py`, `tools/packgen/common/readme.py` | a pack README is whole from the generator with the intro from `about.yaml`; no `*.intro.md` lookup; template READMEs whole from the generator |
| Module conformance (`ModuleConformance`, default `docs/README.md`) | unchanged: the generated README is at the same path |
| `FunctionCataloguePageTest` | unchanged (both files keep their paths) |
| Contract tests (`tools/contracts.py check`, `docs --check`) | the page carries the examples; no `samples/` folder |
| Tests that name `docs/plans/LAUNCHER_REVIEW.md` and other deleted plans (about 50 mentions) | name the document that took the rule over (`IDE_GUIDE.md`, `TEST_HARNESS.md`) |
| `tools/docs/shots/*.py` | untouched unless a picture's document moved (pictures stay under `docs/guides/img/`) |

### 4.4 PDFs

Seven tracked PDFs are made from Markdown by `tools/docs/md_to_pdf.py`, and a test fails when one is older than its source, so every
edit of a source means a new binary in git. Rule: **a PDF exists only for a document handed to someone who has no running Drishti
yet.** Everything else is read in Help, and `md_to_pdf.py docs/X.md out.pdf` makes any PDF on request.

| PDF | Decision |
|---|---|
| `HOW_IT_FITS.pdf`, `docs/DEPLOYMENT_AND_QA_GUIDE.pdf`, `docs/QUICKSTART_DEVELOPERS.pdf` | keep |
| `docs/PRODUCTION_DEPLOYMENT.pdf`, `docs/architecture/DISTRIBUTIONS.pdf` | drop (the source is merged away) |
| `docs/architecture/KERNEL_AND_MODULES.pdf`, `docs/guides/CLUSTER_GUIDE.pdf` | drop (internal design; operators have Help) |
| `docs/publications/*.pdf` (3 presentations) | untouched (no Markdown source) |

The platform's `START_HERE_<OS>.pdf` files point at `DEPLOYMENT_AND_QA_GUIDE`, which keeps its name.

## 5. Execution: three Sonnet lanes, no file in two lanes

Worktrees `~/IdeaProjects/drishti-lanes/i7k-a`, `i7k-b`, `i7k-c` (never inside the host folder), builds with
`-Dmaven.repo.local=$HOME/drishti-bench-scratch/i7k-m2` through `heavy.sh`. Every file of section 2 has exactly one lane.

| Lane | Owns (paths) | Files | Work |
|---|---|---|---|
| **A, guides and Help** | root `*.md`; `docs/*.md`; `docs/guides/**` (except the two generated pages); `docs/tutorials/`; `docs/knowledge/`; `docs/strategy/`; `drishti-console/web/guides/`; every `**/packs/*/guides/*.md` and `modules/*/docs/{guide,tutorial}.md` with the pack and module `help.yaml` beside them; `bin/`, `deploy/`, `compute/drishti-compute/README.md`, `tools/dev/`, `tools/mrloader/{README,docs/README,docs/DAILY_RUNBOOK,samples/README}.md`; `help.yaml`, `docs-features.yaml`, `help_routes.py`, `test_help*`, `test_docs_help_map`, `test_docs_rot` | 124 (61 merged away) | the feature guides absorb pack guides, module guides and tool READMEs; `BANK_DATA_GUIDE`; install facts to one home; Help |
| **B, architecture, contracts, generated references** | `docs/architecture/**`, `docs/contracts/**`, `docs/admin/**`, `docs/lenses/**`, `docs/plans/**`, `docs/guides/{COMMAND_CATALOG,FUNCTIONS}.md`, `testing/connectors/README.md`, `tools/mrloader/docs/<kind>.md`; `tools/docs/**`, `tools/contracts.py`, `tools/mrloader/kind_docs.py`, `tools/settings_catalogue.py`; `test_docs_gates`, `test_docs_pdfs`, the manifest | 135 (81 away) | architecture 46 → 9; `DECISIONS.md`; samples into the contract pages; `CSV_FORMATS.md`; PDFs; step 0 and the gates |
| **C, READMEs under the standard** | `modules/**/README.md`, `config/**` (except pack guides), `plugins/**`, `docs/connectors/**`, `data/**`, `examples/**`, `lib/`, `external_lib/`, `drishti-console/README.md`, `tools/packgen/**`, `tools/connector_readmes.py`, `tools/elements-demo/`, `tools/test_readmes.py` | 141 (45 away) | pack and module READMEs generated whole; intros into `about.yaml`; template READMEs generated; connector READMEs shrunk; placeholders and example guides removed |

**Order**

| Step | Who | What | Proof before the next step |
|---|---|---|---|
| 0 | B, alone, one commit | the manifest with today's 400 files, `moved.yaml` (empty), `moved.py`, `dup_check.py` in report mode, `merge_audit.py`; the baselines printed | static tests green; nothing moved |
| 1 | A, B, C in parallel | each lane's merges and generators; each adds its rows to `moved.yaml` (appended lines, the one shared file) and fixes links **inside its own files** | per lane: its generators' `--check`, `merge_audit.py` for its merges, the README tests (C), the contract checks (B) |
| 2 | merge C, then B, then A | A is last because it owns `help.yaml` and `docs/README.md` | after each merge: `test_docs_rot`, `test_help_links` |
| 3 | A, one commit | `moved.py --apply` over code comments and tests; `help_map.py`; `dup_check.py` switched to fail; caps set to the counts reached | section 5's checks, once |
| 4 | Fable | gate review (reads only), then the owner's OK | |

**The checks that prove it** (run once at step 3; no full drill, since no product behaviour changes)

| Check | Command or test |
|---|---|
| The corpus is the manifest; class counts at target | `test_docs_rot.py::test_the_corpus_is_the_whole_documentation`; `git ls-files '*.md' \| wc -l` = 215 |
| Nothing was lost in a merge | `tools/docs/merge_audit.py`: every heading and every fenced block of a merged source is in a kept document, or in the lane's log "dropped as a duplicate of <place>" |
| Links | `test_docs_rot.py` (links, anchors, paths, commands, settings, codes), `test_help_links.py` |
| Help | `tools/docs/help_map.py --check`, `test_docs_help_map.py`, `test_help.py`, `test_help_developer.py`, the Help browser tests (`test_help_hub_browser`, `test_help_configuration_browser`) on one server |
| READMEs | `tools/test_readmes.py`, `tools/packgen/readme.py --all --check`, `tools/connector_readmes.py`, module conformance |
| Generated references | one static test running every `--check`: settings, commands, contracts, feeds, under-construction, CSV formats, tutorials index; `FunctionCataloguePageTest` |
| The docs gate | `test_docs_gates.py` with the new caps, `dup_check.py` failing at zero |
| PDFs | `test_docs_pdfs.py` on the three kept |
| Code untouched in behaviour | `moved.py --verify` (comment lines only), `mvn clean compile` and `python -m compileall` once |

## 6. Risks, and what NOT to merge

**Do not merge**

- `VAR_GUIDE`, `REGULATORY_VAR_REPORTING`, `EXPOSURE_GUIDE`, `FRTB_SA_GUIDE`, `SIMM_GUIDE`: one per measure, methodology and
  formulas untouched. Customers' risk and model-validation people read these; the regulatory document stays one document to cell level
  (owner rule). A pack guide merged into them adds only what they lack.
- The 14 computed-measure contracts and `contracts/README.md`: one per measure, never folded into a guide. Eight of them (`COLL`,
  `CVA`, `GRP`, `LIM`, `MC`, `NSET`, `SACCR`, `WWR`) say "PLANNED, not built" in their status line; that line stays the truth.
- The 22 connector READMEs: one per type (the server, the console and the assistant read them by name).
- `CHANGELOG.md` and `RELEASE_NOTES.md`: the only place for history; nothing from them enters a guide.
- `RACHANA_REFERENCE.md` (9,108 lines) against the Rachana product's `spec/1/SPEC.md`, and `VIEWMODEL.md` against Rachana's: they
  overlap, but cutting a customer reference needs a line-by-line study. Not in i7k; listed for the release after.
- The tutorials: none merged, no step thinned.

**Risks**

| Risk | Guard |
|---|---|
| A fact is lost in a merge (123 files, 12,735 lines) | `merge_audit.py`; the lane's log of every dropped block with the place that already says it; the Fable gate samples the log |
| Context help breaks (52 addresses with section numbers) | no renumbering in kept guides; `test_help_links.py` |
| About 410 mentions in code, 275 for five architecture documents | `moved.yaml` and one mechanical commit, verified comment-only; fallback in section 8 (D1) |
| Guides grow past their budgets (`VAR_GUIDE` is 7,137 lines against 6,500 today) | merges add only what is missing; budgets warn, they do not fail; a guide that passes 2,000 lines a page becomes a folder, as BI did |
| A generated README loses what the UI or the assistant reads | the README standard's fixed sections and front matter stay; `test_readmes.py`; the pack overview page in the Help browser test |
| A licence or the IMF "internal only" notice is dropped when a pack guide merges into `WORLD_DATA_GUIDE` | the notice is generated from the feed and pack YAML into the README and the feed page; the guide links it |
| Version history or a bug enters a customer guide from an architecture document or a decision record | the existing history gate covers every customer document; architecture stays internal |
| A fresh clone lacks `data/` folders once the placeholders go ("no hidden files" forbids a dot file) | lane C first proves `drishti.py setup` and the loaders create each folder; if one is not created, one `data/README.md` stays instead |
| A design's status line is wrong (`GENOMICS_SHOWCASE.md` says nothing is built; the pack is in `modules/genomics/packs/`) | lane B reads each status against the tree before moving or merging |
| Two lanes edit one file | the path lists of section 5 are disjoint; `moved.yaml` is append-only; shared tests have one owner each |

## 7. The four products

Same three rules. Counts from the i7b tags (`git ls-files '*.md'`).

| Product | Today | Target | What changes |
|---|---|---|---|
| **Tools** 1.1.0 | 13 files, 781 lines | **4** | the nine guides of 32 to 103 lines become one `docs/GUIDE.md`; `ORIGINS.md` deleted (history is the CHANGELOG's); keep `README`, `CHANGELOG`, `docs/BUILD_AND_IDE.md` |
| **Lake** 0.9.1 | 19 files, 2,352 lines | **5 + 3 fixtures** | six guides → one `docs/GUIDE.md`; the two designs → one `docs/DESIGN.md` as built; `BENCHMARK.md`, `java/README.md`, `tools/split/README.md` into the README; `docs/contracts/*` deleted (the same two pages are the test fixtures); keep `README`, `CHANGELOG`, `BUILD_AND_IDE`; `SPLIT_EXEMPTIONS.md` and the two fixture pages stay as records |
| **Elements** 3.3.2 | 18 files, 28,509 lines | **6 + `API.md` generated + 3 vendor records** | `USING`, `THEMING`, `EVENTS`, `VIEWS`, `TUTORIALS` → one `docs/GUIDE.md`; `VIEWS_DESIGN` into `DESIGN.md`; the two migration pages → one; `docs/README.md` into the README; `SUTRA_FOR_EVERYONE.md` moves to Rachana (the language's home); `API.md` (24,121 lines) is declared generated and checked |
| **Rachana** 1.0.1 | 69 files, 17,232 lines | **about 30** | the specification (`spec/1/**`, 5 files), `LICENSE-SPEC.md` and the conformance format stay as they are (normative); seven guides → one `docs/GUIDE.md`; four tutorials stay; `docs/plans/` (8) and `docs/reports/` (2) deleted except the paused i8 plan; `docs/design/` 12 → `DESIGN.md`, `DECISIONS.md` and the library catalogue generated from the library files (`libraries/*/README.md` likewise); the small package and tool READMEs become folder notes |

One home across products: the screen language is Rachana's, the loader and the storage formats are Lake's, the widgets are
Elements'. A Drishti guide says how Drishti uses them and links; it does not explain them again. Each product gets the same manifest
test and `dup_check.py` (they live in Tools). The products are a fourth, small piece of work after the Drishti lanes (one Sonnet lane,
four repositories, each its own commit), not part of the three lanes.

## 8. Decisions for the owner (the lanes proceed with the defaults)

| # | Question | Default | The other choice |
|---|---|---|---|
| D1 | Five architecture documents are named in up to 246 code files. Merge them and sweep the comments? | yes, one mechanical commit, verified comment-only | keep the five: 89 hand-written files instead of 84, no code file touched |
| D2 | PDFs | keep 3, drop 4 | keep all whose source survives (5) |
| D3 | Pack intro text in `config/about.yaml` (`intro:`, at most 40 lines) | yes: the README is then whole from the pack's own YAML | keep the 19 `*.intro.md` files |
| D4 | Seven example pack guides and thirteen `data/` placeholders | delete | leave as they are (20 more files, none a document) |
| D5 | Two small console changes (the gallery reads sections; Help → Developer reads a section of `API_REFERENCE.md`) in a documentation-only release | yes, about 30 lines with their tests | no code: 14 more files stay |
| D6 | `config/feeds/*.md` (13 generated) as one generated page | not now (first check who reads them one by one) | fold to one: 12 fewer files |
