# Module rationalisation (i8 H2): modules by data family, packs as the switch

Status: DESIGN ONLY, 2026-11-27. Checked against `origin/i8/develop` at `8744ed071` and `origin/r2/var-routes` at `7d53f41d0`.
Nothing here is built. Every "file:line" is a path in the drishti repository at that commit.

Owner rules this design obeys:

- A module is the delivery and licensing unit. A PACK is the on/off switch in Admin.
- No data migration. Configuration files are read-only for the server.
- Data is data: no lake folder or table is renamed or moved. A pack or module only DECLARES the tables it reads, by name.
- Tables are known only by SYMBOLIC NAMES in code, packs, Sutras, engine inputs and contracts. Configuration resolves each
  name to its path/URI, format and schema per installation (demo lake, owner lake, a bank's external lake whose layout Drishti
  does not control). Resolved paths are visible in Admin.
- One way per job. R2 (`r2/var-routes`) is not disturbed.

## 0. Summary of the decisions proposed

1. Six modules replace twelve: `trading-systems`, `market-data`, `reference-data`, `external-data`, `news-and-events`, and the
   risk module; `genomics` is unchanged. The kernel keeps no domain pack.
2. **Keep the module id `banking`; change only its title to "Risk"** (section 4.1). A real id rename is a separate, optional step
   after R2's switch.
3. **Every pack keeps its name.** Pack names are in BI table names, in `DRISHTI_PACKS`, in the saved switch state and in load
   rules; kinds, mnemonics, connector names and lake tables keep their names too. The move is then a move of folders and
   manifests, not of data or settings.
4. The per-pack switch exists but is incomplete: five gaps (section 2).
5. A new step, the **table catalogue**, makes the symbolic-name rule true: one resolution point from table name to
   path/format/schema. It is independent of the module move and can go first (section 3).

## 1. Before → after

### 1.1 Today (evidence: each `module.yaml`, each `pack.yaml`)

| Module (id) | Packs | Kinds | Connectors | Sutras |
|---|---|---|---|---|
| `banking` | trading, market-risk, counterparty-risk | 5 + 19 + 15 = 39 | 2 + 7 + 7 = 16 | 142 (murex 74, calypso 51, endur 5, pages 5, common fragments 7) + 10 + 10 |
| `trading` | none (blotter `trade.live`, Java `trading-service`) | 0 | 0 | 0 |
| `source-endur`, `-calypso`, `-murex`, `-wss`, `-swapone`, `-ici` | none | 0 | 2 each (`trade-<sys>`, `trade-<sys>-live`) + a map + a load rule | 0 |
| `market-data` | market-data | 22 | 8 | market-data folder |
| `economy` | world-economy, world-development, national-statistics, oecd, central-banks, official-news, gdelt-events, news-and-markets | 13+2+1+2+2+2+1+4 = 27 | 3+1+1+1+1+1+1+3 = 12 | 13+2+1+2+2+2+1+9 = 32 |
| `capitalmarkets` (folder `capital-markets`) | funds, us-equities | 3 + 7 | 1 + 3 | 4 + 12 |
| `genomics` | genomics, genomics-showcase, protein-engineering | 7 + 7 (+ module kinds) | 1 + 1 | 7 + 7 |
| kernel (`config/packs/core`) | core, gleif, compute-demo | 10 + 5 + 4 | 1 + 2 + 3 | 10 + 9 + 1 |

Where the reference kinds live today: `counterparty, counterparty-group, issuer, ccp, legal-entity, book, desk, trader, calendar,
clearing-account` are the kernel pack `core` (`config/packs/core/core/pack.yaml`, connector `reference-store`, tables
`reference.*`). `lei-entity, lei-group, lei-relationship, lei-country, lei-mapping` are the kernel pack `gleif`. Hierarchies are not a
kind: they are options of the engines (`config/engines/market-risk.yaml`, `options.hierarchies`) over promoted columns.

### 1.2 After

| Module (id) | Packs (names unchanged unless marked NEW) | Kinds | Connectors | Sutras | Requires |
|---|---|---|---|---|---|
| `trading-systems` | `trading` (the one trade kind, its 4 derived kinds, the blotter, `trading-service` Java); NEW `endur`, `calypso`, `murex`, `wss`, `swapone`, `ici` (each: its 2 connectors, its native→canonical map, its load rule, its deal screens, its Help page) | 5 (the source packs own no kind: they `extends: [trading]`) | 2 + 12 | trading 12 (pages 5, fragments 7); murex 74; calypso 51; endur 5; wss, swapone, ici 0 | reference-data, market-data |
| `market-data` | market-data | 22 | 8 | as today | reference-data |
| `reference-data` | `core`, `gleif` | 10 + 5 | 1 + 2 | 10 + 9 | kernel only |
| `external-data` | world-economy, world-development, national-statistics, oecd, central-banks, funds, us-equities | 13+2+1+2+2+3+7 = 30 | 3+1+1+1+1+1+3 = 11 | 13+2+1+2+2+4+12 = 36 | kernel only (optional: reference-data, for the us-equities ↔ LEI link) |
| `news-and-events` | official-news, gdelt-events, news-and-markets | 2 + 1 + 4 | 1 + 1 + 3 | 2 + 1 + 9 | kernel only (optional: market-data, as economy has today) |
| `banking`, title "Risk" | market-risk, counterparty-risk (later: liquidity, climate) | 19 + 15 | 7 + 7 | 10 + 10 | trading-systems, market-data, reference-data |
| `genomics` | unchanged | | | | kernel only |
| kernel | none. `compute-demo` (a sample) moves to `examples/packs` beside `logistics` | | | | |

The `compute.engines` list (var, exposure, sensitivities, frtb-sa, simm, mr-cube) and the mediation of the Drishti Risk
engines stay in the risk module's manifest, untouched.

Notes on the table:

- "Screens per source system" already are per system in the repository: `modules/banking/packs/trading/sutras/{murex,calypso,endur}`.
  They become each source pack's `sutras/` folder. They match on `SOURCE_SYSTEM` and `PRODUCT_TYPE` (for example
  `sutras/murex/zc-swap.v1.sutra.yaml`, its description).
- A pack holding Sutras for a kind another pack owns is already done: `counterparty-risk/sutras/counterparty-and-legal/`
  beside the kernel's `core/sutras/counterparty-and-legal/`. Step S0 proves it for `murex` over `trade` before S3 starts.
- The module id `capitalmarkets` and the id `economy` disappear. Neither has module settings keys in use that this check
  found; their config switch files (`config/modules/economy.yaml`, `capitalmarkets.yaml`) are replaced by two new ones.

## 2. The per-pack switch: it exists; five gaps

What exists (one switch, saved in the identity database, so cluster-wide and no configuration file is written):

- API: `PUT /api/v1/admin/packs/{name}` → `PackAccess.setEnabled`
  (`kernel/drishti-server/.../api/PackAdminController.java:233`, `.../security/PackAccess.java:230-250`), state in
  `kernel/drishti-identity/.../PackStateStore.java`. Admin → Artifacts calls the same (`admin/ArtifactService.java:129-188`).
- It refuses to switch off a pack another enabled pack extends, and switches on what a pack extends (`PackAccess.java:238-249`).
- Off removes the pack's kinds and mnemonics for everyone (`PackAccess.kindAllowed`, `:349`), the catalogue
  (`api/CatalogController.java:122`), the console's pack answer (`console/ConsoleController.java:245`), Drishti*net routing
  (`net/NetRouting.java:99`) and the digest (`net/DigestService.java:365`). Menus follow because items carry
  `requires: ['cmd:TRD']` (`modules/banking/module.yaml`, the menus).

Gaps, each a design item of step S2:

| # | Gap | Evidence | Design |
|---|---|---|---|
| G1 | Off does not stop the pack's connectors. A second machinery does that: the lifecycle's suspend (`POST /api/v1/admin/packs/{name}/lifecycle/suspend`), which stops the connectors only this pack names | `PackAccess.java:235-250` only writes the state; `kernel/drishti-packs/.../PackLifecycles.java:260`, `server/lifecycle/PackLifecycleActions.java:73`, `LifecycleController.java:105` | One switch: off = the saved state + the lifecycle's suspend of the pack's own connectors; on = resume. The lifecycle endpoint stays as the operator's temporary pause; Admin shows one state |
| G2 | Help does not follow the switch. Help sections are per module; a guide's packs are compared with the distribution's `packs.enabled`, never with the Admin state | `modules/banking/docs/help.yaml` (one section per module); `drishti-console/routes/help_routes.py:53-56` | `help.yaml` pages take an optional `pack:`; `shown()` also asks the server's enabled packs (already in the `PUT` answer: `enabledPacks`) |
| G3 | Loading does not follow the switch. A load rule names its pack and connector, but nothing was found that skips a rule whose pack is off | `modules/source-murex/dataload.yaml` (`pack: trading`, `connector: trade-murex`); rules gathered at `kernel/drishti-api/.../resolution/ModuleTemplates.java:127` | A rule of a pack that is off is not matched; the file stays in the inbox with the reason in words ("pack murex is off"). To be confirmed in S0 |
| G4 | A source pack owns no kind, so "its kinds disappear" hides nothing of it. Its Sutras, connectors, map and load rule must follow the pack, not the kind's owner | Sutras are chosen by kind; the gate is by kind owner (`PackAccess.ownerOf`, `:368`) | Sutra and connector-template registration is by contributing pack; a Sutra of a pack that is off is not offered. The trade rows of that system stop arriving (G1) and already-loaded rows stay readable only if the row rule allows (section 2.1) |
| G5 | A pack cannot carry a map or a load rule today: both are module manifest keys, and a connector's `map:` is read relative to the module's folder | `kernel/drishti-module-runtime/.../DmodManifest.java:67` (`dataload`, `connectors`, `mappings`); `kernel/drishti-engine/.../source/SourceShape.java:33-52` ("read relative to the connector's module") | `pack.yaml` takes `dataload:` and `mappings:`; a map is anchored to `${pack.dir}` (owner rule: named roots). One resolver, as today (`PackResolver`) |

Not found broken, to be covered by one test in S2: BI datasets of a pack's kinds (`pack.<pack>.<kind>`) and saved workspaces when
the pack is off.

### 2.1 Access per source system

Two controls, both existing in principle:

- The pack switch: the system is used at this site or not (connectors, screens, map, Help, loading).
- Row rules on the trade's `SOURCE_SYSTEM` for who sees which system's trades: `Entitlements.rowRule(principal, kind)`
  (`kernel/drishti-server/.../security/Entitlements.java:192-222`). Every source connector already stamps the field
  (`modules/source-murex/connectors/trade-murex.yaml`: `set: { SOURCE_SYSTEM: MUREX }`), and it is a promoted column of every trade
  table. The engines honour row rules (`config/engines/market-risk.yaml:51`, `visibility: row-rules`).

No new mechanism is needed; S3 adds one shipped example role per system and a test.

## 3. Tables by symbolic name: the table catalogue

### 3.1 What exists today

A symbolic layer exists and is mostly complete for reading:

- A kind is the name Sutras, contracts and engine inputs use (`config/engines/market-risk.yaml`, `inputs: { scenario-pv: { kinds: [...] } }`).
  No Sutra, pack manifest or engine input in the tree names a lake path (searched `pack.yaml`, `sutras/`, module connectors).
- A connector names tables symbolically: `lake: demo`, `lake-tables: trading.trade_murex`
  (`modules/source-murex/connectors/trade-murex.yaml`). A lake file lists the tables and the root (`config/lakes/*.yaml`).
- Table names are data-family names (`trading.*`, `risk.*`, `reference.*`, `market.*`, `credit.*`, `legal.*`, `collateral.*`,
  `macro.*`), not module or pack ids. **No lake folder is derived from a module id.** The module move changes none of them.
- Binding a kind to an external table works today for an Iceberg catalogue: `plugin: iceberg-external` with `tables: { trade:
  { table: risk_mart.trades, id: [...], columns: { rename: ... } } }` (`config/connectors.examples/other/iceberg-external-risk-mart.yaml`).

What is missing: the name → path step is a convention, not configuration. The path is always `<lake root>/<namespace>/<table>`:

| Where the layout is assumed | File:line |
|---|---|
| A connector's table path = root + "/" + domain + "/" + (`table.<kind>` or the kind) | `kernel/drishti-engine/.../source/SourceRegistry.java:1051-1053` |
| The Delta reader: `<root>/<domain>/<kind>/business_date=…` | `plugins/drishti-plugin-delta/.../DeltaSourcePlugin.java:64, 289-294` |
| The file reader: root + domain | `plugins/drishti-connectors/.../file/FileSourcePlugin.java:110` |
| Browser loads write root + domain | `kernel/drishti-server/.../loads/PackDataLoader.java:120-122` |
| Iceberg path namespaces: namespace dots → folders | `plugins/drishti-plugin-iceberg-external/.../IcebergExternalPlugin.java:135-136` |
| Official results: the folder `risk-results` as a constant | `modules/banking/.../official/OfficialSink.java:39`, `.../exposure/ExposurePublisher.java:42`, `.../official/LakeAppends.java:34`, `kernel/drishti-packs/.../OfficialReaders.java:85` |
| The loaders: root + "/" + domain + "/" + table | `tools/lake/loaderows.py:98`, `tools/lake/icebergwrite.py:83`, `lake/drishti-lake-host/.../LakeRowsLoad.java:92` |
| Engine files naming a lake root directly | `config/engines/var.yaml:146`, `config/engines/exposure.yaml:74` |

So an external Delta table at an arbitrary URI can be bound only by composing root + domain + `table.<kind>` so that the three
parts happen to join to it. There is no per-table `path:`.

Names derived from a module or pack id (none is a lake path; all are listed because the owner asked):

| What | File:line | Effect on this plan |
|---|---|---|
| BI table name = pack name + "." + kind | `compute/drishti-tables/.../catalog/PackTables.java:62-63`; dataset id `pack.<pack>.<kind>` at `kernel/drishti-server/.../bi/model/PackModels.java:461` | Reason for decision 3 (packs keep their names). No kind changes pack in this plan |
| Settings prefix `drishti.module.<id>.` | `kernel/drishti-packs/.../module/ModuleSettingsSchema.java:36` | Section 4.1 |
| A module's own data folder `<data>/modules/<id>` and Python site `<data>/compute/modules/<id>/site` | `kernel/drishti-server/.../modules/host/ModuleWiring.java:163`, `ModulePython.java:65` | Only genomics uses it (`VcfConnector.java:67`); genomics does not move |
| `${drishti.module.dir.banking:}/packs/market-risk/capital` | `config/engines/frtb-sa.yaml:22` | Unchanged while the id stays `banking` |
| Configuration folders named by domain: `config/lakes/banking/`, `config/tables/banking/` (28 files, tables `banking.*`), `config/reports/banking/`, `config/bi/reports/banking/`, `config/lenses/banking/`; `reports-dir` at `config/engines/var.yaml:157` | as named | Unchanged while the id and domain stay `banking`. The `banking.*` BI table names are written out in each file, so they would survive a folder move anyway |
| Demo input folders by pack: `data/testdata/<pack>/` | `data/testdata/` | Unchanged: packs keep their names |

### 3.2 The design: one resolution point

- **Where the map lives: the lake files** (`config/lakes/<lake>.yaml`), which already list every table by symbolic name. No new
  file kind. Each table entry may carry its own location; without one, the present rule (root/namespace/table) applies, so the
  demo lake and the owner's lake need no change and no data moves.

```yaml
# config/lakes/bank.yaml  (a site file; the server only reads it)
lake: bank
format: delta                         # the default of the tables below
root: s3://bank-lake/curated          # optional once every table names its path
tables:
  trading.trade_murex:
    path: s3://fo-data/mx/trades_v7   # any URI; nothing joined to it
    format: delta                     # delta | iceberg | files
    contract: trade@1                 # the schema: a kind's contract, or columns: written here
    promote: [TRADE_ID, SOURCE_SYSTEM, PRODUCT_TYPE]
  risk.scenario-pv:
    catalog-table: risk_mart.scenario_pv   # an Iceberg catalogue table instead of a path
    format: iceberg
```

- **The one resolver**: `TableCatalogue.resolve(lake, name) → { uri, format, contract, how: "configured" | "by convention" }`, in
  the kernel beside `ConfigResolution` (`kernel/drishti-api/.../resolution/`). Every site in the table of 3.1 asks it and stops
  joining strings: the connectors (Delta, Iceberg, files), the loaders (Java and the one Python path), the official-results
  writers and readers (which then name `risk-results.var-official` in the lake file as any other table), and the engine files
  (`var.yaml:146`, `exposure.yaml:74` name a lake, not a root).
- **A guard test** in the style of the explicit-paths guard: outside `TableCatalogue`, no main code joins a lake root with a
  namespace or a table.
- **Admin shows the resolved path**: Admin → Lakes and Admin → Connectors show, per table, the symbolic name, the resolved URI,
  the format, the contract and "configured" or "by convention". The bindings view already shows a resolved table
  (`kernel/drishti-server/.../bindings/BindingService.java:336`, from `SourceRegistry.tableOf`); it reads the catalogue instead.
- **Packs declare, never locate**: a pack's `lake: tables:` block stays the declaration of the names it reads, with promoted
  columns. Any pack may declare any existing table name; a table has no owner module.
- Storage parity: the same three keys for Delta, Iceberg and the file store, with one parity test.

This step (S1) does not depend on the module move and the move does not depend on it; it is first in the order because it is the
larger risk and touches no file R2 touches except the two engine files' one line each (done after R2 merges, see section 6).

## 4. What breaks and how each is handled

### 4.1 The id: `risk` or `banking` with the title "Risk"

Measured cost of renaming the id:

- `drishti.module.banking.*`: 291 uses in 125 files (`docs/admin/CONFIGURATION.md` 43, `docs/guides/VAR_GUIDE.md` 31, tests,
  `tools/settings_schemas.yaml`, the capacity planner `kernel/drishti-server/src/main/resources/capacity/planner.yaml:586`).
  Every site's settings file names these keys; the server may not rewrite them.
- `modules/banking` paths: 550 uses in 147 files. Java package `com.ash.drishti.banking`: 417 files. The word in 1,371 files.
- `domain: banking` in 20 files, including the market-data module's own menus and pack (`modules/market-data/module.yaml`,
  `packs/market-data/pack.yaml`): the console's domain grouping is shared across modules (`edition/EditionConfiguration.java:170`).
- The config switch `config/modules/banking.yaml`, `drishti.modules.banking.enabled`, the licence feature `banking`, the
  artefact names `banking-1.0.0.dmod`, `drishti-banking.jar` (`ModulePack.java:836`).
- R2: `r2/var-routes` changes 18 files under `modules/banking/java/banking-service` and adds 25 lines naming
  `drishti.module.banking`; a rename now conflicts with every one of them.

What users already see is "Risk": `console.domain-label: Risk`, `api.prefix: risk`, the lens `risk`, the distribution
`drishti.risk`, Help title "Drishti Risk Help".

**Recommendation: keep the id `banking`, set `title: Risk`** (and `about`, the Help section title, the module README). It is
safer by a wide margin: no settings key, path, package or test changes, and R2 is untouched. If the owner still wants the id
`risk`, do it as step S7 after R2's switch, as a clean rename using the mechanism the manifest already has for retired keys
(`modules/banking/module.yaml`, the `settings:` block: "a site that sets it stops the start, naming the new key"). That is one
way, no alias, no migration; the upgrade note is one line per key and the start itself tells the operator.

### 4.2 The rest

| Thing | What happens | Handling |
|---|---|---|
| Lake folders and tables | Nothing. Names are data-family names; no code derives one from a module id (section 3.1) | A test asserts the set of table names before and after the move is identical |
| Pack names, kinds, mnemonics, connector names | Unchanged | The same test for the catalogue: every kind, word and connector before = after |
| Saved pack switch state, user pack assignments | Unchanged (keyed by pack name) | None |
| `config/modules/*.yaml` switches | `trading.yaml`, six `source-*.yaml`, `economy.yaml`, `capitalmarkets.yaml` name modules that no longer exist; four new ids appear | Clean rule, no alias: a switch file of an unknown module id stops the start with the new id and the pack to switch instead (`source-murex` → pack `murex` of `trading-systems`). Upgrade note. `drishti.modules.<id>.enabled` the same |
| Module requirement cycle | None after the move: banking Java imports nothing of `com.ash.drishti.trading` and the reverse (0 files each way); the derived trade kinds are configuration (`trade-pages`, plugin `derived`) | The conformance check already orders `requires`; add the graph of section 5 as a test |
| Menus | `blotter` moves to trading-systems; Books, Desks, Traders, Counterparties items (core kinds) move to reference-data; Positions, Risk, Exposure stay. A module may add columns to another module's menu by its id (`modules/market-data/module.yaml`, the comment on menus), used only in the allowed direction | Sitemap snapshot test |
| `functions:` and `provides.kinds.trading` in `modules/banking/module.yaml` (TRD, CTRD, PTRD, LTRD, ITRD) | Move to the trading-systems manifest | One edit of the manifest, after R2 merges (R2 changes this file) |
| `data/needs.yaml` | banking's needs keep `trade` (now provided by trading-systems); source modules' needs merge | Regenerated |
| Help | Sections per module change: Trading systems, Reference data, External data, News and events, Risk. Guide file names and slugs do not change (`guides/BANK_DATA_GUIDE.md`, `VAR_GUIDE.md` …), so links survive | `help.yaml` per new module; G2 adds `pack:` |
| Licence features | `trading` covers trading-systems; `economy` and `capitalmarkets` → `external-data`, `news-and-events` | Owner decision D5 |
| Tests | `testing/drishti-modules-it` (22 files naming banking stay valid); tests naming `source-*`, `economy`, `capitalmarkets` module ids change; console tests `drishti-console/tests` (72 files name banking: unchanged) | Part of each step; no new suite |
| Generators | `tools/packgen/banking/`, `tools/connector_readmes`, `tools/docs`, module and pack READMEs, `CONFIGURATION.md` | Regenerated in S6; `tools/packgen/banking/kept/` keeps its name |
| Distributions | Recomposed, section 4.3 | S6 |
| Kernel | `config/distributions/kernel.yaml` `packs: [core]` → `[]`; `kernel-packs:` of `drishti.genomics.yaml` already `[]` | Finance distributions list `reference-data` as required |
| R2 | Untouched by S1, S2, S4, S5. S3 and S6 edit `modules/banking/module.yaml` and `config/engines/*.yaml` lines R2 also edits | Order in section 6 |

### 4.3 Distributions after

| Distribution | Modules |
|---|---|
| `drishti.risk` | banking (Risk), trading-systems, market-data, reference-data. The six source packs are off until the site switches one on |
| `drishti.finance` | external-data, news-and-events, market-data, reference-data |
| `drishti.public-sector` (new, decision D4) | external-data, news-and-events |
| `drishti.genomics` | genomics |
| `drishti.all` | all |

## 5. Dependency graph after

```
                         kernel (no domain pack)
        ┌───────────────┬──────────┴───────────┬────────────────┐
  reference-data   external-data        news-and-events      genomics
        ▲   ▲        (optional → reference-data)  (optional → market-data)
        │   └──────────────┐
   market-data             │
        ▲   ▲              │
        │   └── trading-systems   (packs: trading ◄ endur, calypso, murex, wss, swapone, ici)
        │              ▲
        └──── banking "Risk" ────┘   (packs: market-risk, counterparty-risk → trading, market-data, core)
```

One-way, no cycle. Pack level (`extends`, from the manifests): trading → core, market-data; market-data → core; market-risk →
market-data, trading; counterparty-risk → trading. Two optional edges exist and stay optional: news-and-events → market-data
(economy has `requires.optional: market-data` today) and us-equities ↔ gleif (a link by kind, no `extends`).

## 6. Execution steps, ordered against R2

Sizes are Sonnet lane-hours, with targeted tests only; the one full run is the i8 drill. S1 and S2 need an Opus pass on the
kernel parts (marked).

| Step | What | Size | R2 |
|---|---|---|---|
| S0 | Proofs before any move: a `murex` pack with Sutras over `trade` loads and switches; a load rule of an off pack; BI datasets with a pack off. Read-only spike in a lane worktree | 2 h | independent |
| S1 | The table catalogue (section 3.2): per-table `path`/`format`/`contract` in lake files, `TableCatalogue`, the ten call sites, Admin columns, guard test, parity test, DATA_LAKE_GUIDE section | 14 h (6 Opus) | independent, except `var.yaml:146` and `exposure.yaml:74`: those two lines after R2 merges |
| S2 | The one pack switch: G1–G5, the pack-level `dataload:` and `mappings:`, `${pack.dir}`, tests | 10 h (4 Opus) | independent |
| S4 | `reference-data` module: move `core` and `gleif` out of `config/packs/core`; `compute-demo` to `examples/packs`; kernel packs empty; the menus' reference items | 5 h | independent |
| S5 | `external-data` and `news-and-events` from `economy` + `capital-markets`; World and Markets lenses re-homed; refresh scripts' paths | 6 h | independent |
| — | **Wait: `r2/var-routes` merged into `i8/develop`** | | |
| S3 | `trading-systems`: merge `trading` + six `source-*` modules; move pack `trading` out of banking; create the six source packs (connectors, map, load rule, Sutras, Help page); move the trade words and menus out of the banking manifest; example roles by `SOURCE_SYSTEM` | 12 h | after the merge (edits `modules/banking/module.yaml`) |
| S6 | Title "Risk"; distributions; unknown-module-id start refusal; regenerate READMEs, CONFIGURATION.md, module guides; KERNEL_AND_MODULES.md; upgrade note; the before = after tests of 4.2 | 7 h | after the merge |
| S7 (optional, D1) | Rename the id `banking` → `risk`: folder, package, artefacts, settings keys with `retired:`, config folders, docs | 14 h (4 Opus) | only after R2's switch and the removal of the in-tree VaR path |

Total without S7: about 56 lane-hours; three lanes in parallel for S1, S2 and S4+S5, then S3 and S6 in turn. A Fable gate
after S2 (kernel changes) and before the drill, as for every milestone.

## 7. Owner decisions, with the default taken if nothing is said

| # | Decision | Default |
|---|---|---|
| D1 | The risk module's id: keep `banking` with the title "Risk", or rename to `risk` | Keep `banking`; S7 not planned |
| D2 | Packs keep their names (`core` stays `core` inside `reference-data`; `trading` stays `trading`) | Yes |
| D3 | Old module switch files and `drishti.modules.<old id>.enabled`: refuse the start naming the new id and pack, no alias | Yes (clean rule, one way) |
| D4 | A `drishti.public-sector` distribution (external-data + news-and-events) | Add it |
| D5 | Licence features: `trading` for trading-systems; new `external-data` and `news-and-events` replacing `economy` and `capitalmarkets` | As stated |
| D6 | The table catalogue lives in the lake files (per-table `path`, `format`, `contract`), not in a new file | Lake files |
| D7 | S1 (table catalogue) before the module move | Yes: first, in parallel with S2 |
| D8 | Source packs off by default in every distribution; the demo profile switches on endur, calypso, murex | Yes |
| D9 | `compute-demo` leaves the kernel for `examples/packs` | Yes |
| D10 | Start S1, S2, S4, S5 while R2 is in flight, or wait for the i8 resume word | Wait for the owner's word (i8 is paused) |

## 8. Not checked in this pass

- G3 (load rule of an off pack) and the BI-with-pack-off behaviour were not traced to the end; S0 settles both.
- The `drishti-lake` product repository (`com.ash.lake.config.ConnectorLake`, `Lakes`) was not read; the lake-file parser lives
  there, so S1 includes a change in that product and its release tag.
- Counts of tests that name the retired module ids were not taken per file.
