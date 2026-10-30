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
# Editions and lenses: one setting for a focused Drishti

**Status: built (release 2.9.6; Drishti World built 2026-10-29 for 2.9.7).** The first edition is **`risk`**, branded **Drishti Risk**: a
one-stop shop to manage risk, VaR and exposure first. The second is **`world`**, **Drishti World**: the public-data packs (section 12). The how-to guide is [EDITIONS_GUIDE.md](../admin/EDITIONS_GUIDE.md); this document is the design as built. The decisions
of the product owner are in section 9.

## 1. The idea

Drishti stays one product with one code base. An **edition** is a named bundle of settings that turns it into a focused product:

```yaml
# the servers (every member of a cluster); the console learns it from the server's about answer
drishti.edition: risk               # blank: the full Drishti, exactly as without editions
```

The value names a file, `config/editions/risk.yaml`, that sets in one place the packs, the commands people may use, the menus and features
shown, Home, the help centre, the Admin pages, the BI datasets and the branding. The site's configuration still wins over the edition file
key by key, so a bank adjusts one item without forking the edition. Switching edition is a configuration change and a restart, never a code
change.

A **lens** (section 8) is the same file opened by one person on a full instance, for their own session, with no restart.

| | Edition | Lens |
|---|---|---|
| Set by | configuration and a restart (`drishti.edition`) | a link, per person ("Open Drishti Risk") |
| Scope | the whole instance | one person's session (kept in their settings) |
| Packs | only the edition's (`allow-only`) | all stay loaded |
| Controlled by | the administrator | the site's `drishti.lenses`; who sees the link: RBAC later (`lens.roles`, read, not wired) |
| Secures anything? | refuses packs; hides commands and pages | **no: it hides, it does not secure** |

## 2. What existed (audit, release 2.9.4) and what changed

| Area | Before | Built |
|---|---|---|
| **Packs** | `DRISHTI_PACKS` chose the packs; the default list was in four places (Java, Python, two YAML files) | one list, `config/editions/default-packs.yaml` (copied into the `drishti-packs` jar at build, read by the console from disk); `drishti.packs.enabled` defaults to the word `default`: the edition's packs, else that list (set empty it is still empty: no packs); `allow-only` refuses other packs at start, in Admin → Packs, Deploy archive, the registry and the folder watcher |
| **Commands** | Admin → Commands switched a mnemonic off for everyone, which made its kind unreadable | an edition **command allow-list**, separate from kind access (`EditionCommands`): hidden from the command line, FUNC, the type-ahead, the packs' examples, the entity page and search (`404 DRS-4023`); the engines still read every kind. The reserved and console words (BI, RUPAKA, FORM, FUNC, WHAT, DATAMAP, VAR, EXP) switch on and off, with their pages |
| **Menus** | the top bar was a list in a template; Views, Build, Help and the footer were fixed; the placeholder a trading example | `drishti-console/config/menus.yaml`: every menu item and footer link with an id and `requires:`; the edition lists what it shows per menu; the placeholder from configuration |
| **Features** | about 15 console and 25 server flags; no switch for Build, Forms, Data map, Workspaces, Monitors, Alerts, Lakes; `studio.enabled` read nowhere | `config/editions/features.yaml` maps each feature an edition names to the flags of each side; new console flags `features.build`, `forms`, `datamap`, `workspaces`, `monitors`, `alerts`, `lakes`, `reports`, `collaboration`; `studio.enabled` wired |
| **Home** | hero examples and facts followed packs; the rest fixed | sections switch off (`home.sections`), cards from the edition, and three Drishti Risk landings (section 7) |
| **Help and Knowledge** | one fixed catalogue; any document reachable by its slug | categories and Knowledge pages follow the edition (Knowledge pages tagged by pack in `help.yaml`), pack guides follow its packs, unlisted slugs refused (`help.unlisted-slugs: refuse`) |
| **Admin** | every tab always there | tabs, menu items and routes follow `admin.pages`; a hidden one answers 404 |
| **BI** | datasets, models, reports and boards did not follow packs | reduced to the edition's datasets and models (`EditionBi`): a report or board follows its dataset or model, in the catalogue and by id |
| **Branding** | server and console branded separately; the eye inline; favicon and manifest fixed files | the console takes the server's product and tagline from its about answer (one source; the console's own `ui.product` still wins); `ui.logo`, `ui.favicon`, `ui.apple_touch_icon`; a generated `/manifest.webmanifest`; `{product}` in help titles and summaries |

## 3. The edition file

One YAML file per edition in `config/editions/` (folder `drishti.editions.dir`, `DRISHTI_EDITIONS_DIR`; the console's `editions.dir`). The
shipped one, `config/editions/risk.yaml`, abridged:

```yaml
edition: risk
title: Drishti Risk
packs:
  enabled: [core, market-data, trading, counterparty-risk, market-risk]
  allow-only: true
commands:
  allow: [CPTY, GRP, LE, BOOK, DESK, TRDR, CCP, TRD, CTRD, PTRD, LTRD, FX, HIER, PORT, POS, PNLV, SPV, FXS, SSET, VLIM, VRES, VWIF,
          VOFF, AGR, CSA, NSET, LIM, COLL, MC, XRUN, XPATH, EXP, XWIF, XOFC]
  words: { BI: on, RUPAKA: off, FORM: off, FUNC: on, WHAT: on, DATAMAP: on, VAR: on, EXP: on }
features: { bi: edition, var: on, exposure: on, calc: off, pyconsole: off, python: off, designer: off, studio: off, build: off,
            forms: off, datamap: on, layouts: on, workspaces: on, monitors: on, alerts: on, reports: on, lakes: on, ai: off, embed: off }
bi:
  datasets: [market-risk-var-latest, market-risk-var-official, counterparty-exposure-counterparties, ...]
  models: [market-risk-var, counterparty-exposure, live-exposure]
menus:
  views: [terminal, search, functions, datamap, var, exposure, reports, workspaces, monitors, alerts]
  help: [help-centre, knowledge, install, about]
  footer: [help, about, release-notes]
  placeholder: "VAR BANK, VAR DESK RATES, EXP CPTY:CP-ALDERSHOT, VRES DESK:RATES …  then <GO>"
home:
  landing: desk
  landings: [desk, command, map]
  landing-by-role: {}
  sections: { stats: off, grammar: off, pipeline: off, capabilities: off, views: off }
  cards: [...]
help: { categories: [knowledge, start, use, data, operate], knowledge: [market-risk, counterparty-risk, core, trading, market-data],
        unlisted-slugs: refuse }
admin: { pages: [users, roles, packs, commands, tokens, access, connectors, artifacts, loads, lakes, audit, net, health, caches, bi] }
branding: { product: Drishti Risk, tagline: VaR and exposure from your own pricing data }
```

Optional blocks: `server:` and `console:` hold raw settings of either side (a preset like any other, under the site's configuration);
`commands.also-allow` adds commands without restating the list (the site's way to add one).

### How each side reads it

- **Server.** `EditionEnvironmentPostProcessor` (`drishti-packs`, order `HIGHEST_PRECEDENCE + 11`: right after the configuration files are
  read, before the packs' post-processor) loads the file as one property source placed **just above the product's own `application.yaml`
  and below everything the site sets** (application.local.yaml, the packs overlay, environment variables, arguments). It holds the file's
  keys under `drishti.edition-spec.*` (so `drishti.edition-spec.commands.also-allow[0]=FRTB` in the site's configuration wins), and the
  settings the edition presets: `drishti.packs.enabled`, `drishti.branding.product`/`tagline`, each feature's flags (through
  `features.yaml`), the raw `server:` block. A preset of a setting the product writes as `${VARIABLE:default}` keeps the variable:
  `DRISHTI_PACKS` set still wins. `Editions.of(env)` gives the `Edition` record every server class asks; blank gives `Edition.NONE`,
  which answers "allowed" to every question. A missing file or a name that is not a plain word stops the start with what to do.
- **Console.** The console learns the edition (and the lenses the server offers, and its product name and tagline) from
  `/public/about` at start and on every check of `core/backend_watch.py` (`Editions.learn`), reads the same file and puts its presets as a
  settings layer between `application.yaml` and the site's own (`Settings.layer`); the site's `edition:` block overrides the file key by
  key. `core/edition.py` answers every "is this shown?" question; `core/edition_gate.py` answers 404 for a hidden page.

## 4. What Drishti Risk shows

| Area | Shown | Hidden |
|---|---|---|
| **Packs** | core, market-data, trading, counterparty-risk, market-risk | world economy, news and markets, US equities, GLEIF, funds, the example packs (refused: `DRS-1017`) |
| **Commands** | VaR (`VAR`, `VRES`, `VWIF`, `VOFF`, `VLIM`, `HIER`, `PORT`, `POS`, `PNLV`, `SPV`, `FXS`, `SSET`), exposure (`EXP`, `XRUN`, `XPATH`, `XWIF`, `XOFC`, `AGR`, `CSA`, `NSET`, `LIM`, `COLL`, `MC`), reference data (`CPTY`, `GRP`, `LE`, `BOOK`, `DESK`, `TRDR`, `CCP`), trades (`TRD`, `CTRD`, `PTRD`, `LTRD`), `FX`; the words `BI`, `FUNC`, `WHAT`, `DATAMAP` | `FRTB`, `STR`, `PNL`, `SCN`, `TVAL`, `CVA`, `SACCR`, `SIMM`, the curves and surfaces, `ISS`, `CAL`, `CLR`; the words `FORM` and `RUPAKA` |
| **Menus** | Views: Terminal, Search, Functions, Data map, VaR, Exposure, Reports, Workspaces, Monitors, Alerts; BI; Admin (its pages); Help: Help centre, Knowledge, Install and run, About | Build; Forms; the competitive analysis link in the footer |
| **Features** | BI (the edition's datasets), VaR, exposure, layouts, workspaces, monitors, alerts, reports, lakes, the data map | Calc, the Python console and Python visuals, the Sutra designer, Studio, Build, Forms, AI, embedding |
| **Home** | the Drishti Risk landings (section 7); signed out, today's Home without its general sections | the grammar, the seven stages, the capabilities, the showcase, the stats |
| **Help** | Knowledge (market risk, counterparty credit risk, trades, entities, market data, data and contracts), Start, Use, Data, Operate | the BI and Build categories, other packs' Knowledge pages, documents not in the catalogue |
| **Admin** | users, roles, packs, commands, tokens, access, connectors, artifacts, data loads, lakes, audit, net, health, caches, BI settings | AI, collaboration, embedding, edits, compute (404) |
| **BI** | datasets `market-risk-var-latest`, `market-risk-var-official`, `counterparty-exposure-*` (five), `live-exposure`, `live-exposure-netting`; models `market-risk-var`, `counterparty-exposure`, `live-exposure`; the reports and boards on them | every other dataset, model, report and board |
| **Branding** | **Drishti Risk**, with Drishti's name and eye mark | nothing, by default |

The engines are unchanged: VaR and exposure of any scope, any currency, intraday, official records, the what-if, the API and the CLI work
under the edition exactly as on the full product (the `*RiskEditionTest` classes run their whole suites again with the edition on).

## 5. What was built (by item)

1. **The edition layer.** `drishti-packs`: `edition/Edition.java` (the record), `edition/Editions.java` (reading, the default pack list,
   lenses), `edition/EditionEnvironmentPostProcessor.java`; `PackPaths.enabled(env)` (`default`: the edition's, else the default list);
   `config/editions/default-packs.yaml` (the one list, copied into the jar by `drishti-packs/pom.xml`). Console: `core/edition.py`,
   `core/config.py` (`Settings.layer`, `Settings.site`), learning in `core/backend_watch.py` and the app's start.
2. **Packs.** `allow-only`: `PackEnvironmentPostProcessor` (start), `PackReloader.load` and `desired` (Admin → Packs, the folder
   watcher), `PackDeployService.stage` (Deploy archive, a failed check), `RegistryController.install` (the registry): `403 DRS-1017`.
3. **Commands.** `server/edition/EditionCommands.java` and `EditionContext.java`; `CommandController` (typed, the type-ahead, local and
   peers'), `ViewController`, `SearchController`, `CommandCatalog` and `FunctionsController` (FUNC, Admin → Commands), `CatalogController`
   (the packs' examples and kinds): `404 DRS-4023`. Console words: `routes/terminal_routes.py` (`/go`), `routes/api_routes.py` (the
   type-ahead's reserved words), route gating in `core/edition_gate.py` (`/bi`, `/forms`, `/func`, `/what`, `/datamap`, `/var`, `/exposure`).
4. **Menus and footer as data.** `drishti-console/config/menus.yaml`, `core/menus.py`, `terminal/_topbar.html`, `_footer.html`;
   `requires:` grammar: `admin`, `bi`, `bi-admin`, `designer`, `edition`, `feature:<name>`, `word:<WORD>`, `admin-page:<id>`,
   `pack:<name>`; the placeholder `ui.placeholder` / `menus.placeholder`.
5. **BI.** `server/edition/EditionBi.java`, applied in `BiService.catalogueItems` and `BiService.itemOf`: datasets, models, reports, boards,
   Python visuals and notebooks follow `bi.datasets` and `bi.models`; the BI and Build help categories follow `help.categories`.
6. **Feature flags.** `config/editions/features.yaml`; console `features.*`; `studio.enabled` wired (`/studio`).
7. **Home.** `home.sections` in `landing.html`; `home.cards`; the landings (section 7); the hook `home.landing`.
8. **Help and Knowledge.** `routes/help_routes.py` (`shown`, `categories_for`), `core/guides.py` (`Guide.packs`), `packs:` on the Knowledge
   entries of `config/help.yaml`.
9. **Admin.** `admin/_nav.html` and the Admin menu follow `admin.pages`; `core/edition_gate.py` answers 404 for a hidden page's routes.
10. **Branding.** `ui.logo` (the `brand_mark` macro), `ui.favicon`, `ui.apple_touch_icon`, `/manifest.webmanifest` (`home_routes.py`); the
    server's product and tagline in `/public/about` and learned by the console; `{product}` in `help.yaml` titles and summaries (the
    `product` filter). The guard tests (`test_branding_guard.py`, `BrandingLiteralTest`) are unchanged and pass; `test_edition.py` checks
    a configured logo, favicon and the manifest's name.
11. **Tests.** Section 10.
12. **Docs.** This document, [EDITIONS_GUIDE.md](../admin/EDITIONS_GUIDE.md), CONFIGURATION (the edition keys), OPERATIONS (Editions),
    API_GUIDE and TROUBLESHOOTING (`DRS-1017`, `DRS-4023`).

## 6. In a cluster

One value, `drishti.edition`, on every server; each reads the same file (it ships in every image under `config/editions/`). A member that
runs an edition publishes it in its capability digest (the service `edition`); Admin → Net warns when the members' editions differ
(`CapabilityTable.warnings`, "the nodes run different editions"). Each member shows and refuses what its own edition says, so a mixed
cluster is inconsistent until fixed: set the same value everywhere and restart the odd one. VaR sharding is untouched: the edition hides
commands from people, never a kind an engine reads (`EditionShardClusterTest`: two members and a reference, sharded VaR equal to one
server's to the cent, under `risk`). The lens is kept in the person's settings (the server's user store), so every member and every
console worker sees it, and the server is told on each call (`X-Drishti-Lens`).

## 7. Drishti Risk's Home: three landings

The product owner chose all three mock-ups (2026-10-29): **A "desk"** (headline tiles: VaR and ES of BANK, the backtest, exposure EPE and
peak PFE, limits in breach, counterparties and netting sets; VaR by desk with limit use; exposure by counterparty against its credit limit;
quick cards), the default; **B "command"** (a big command line, the edition's command chips, a status strip, the person's VaR baskets, their
monitors, links to learn); **C "map"** (the hierarchy with VaR and limit use per node and a switch between the hierarchies the engine holds,
a limit-use heat map, a "Today" feed of intraday batches, official records and breaches).

- **Built how.** Each edition names its landings, the template that draws them and their titles (`home.landings`, `home.template`,
  `home.titles`; a file that names no template keeps Drishti Risk's, and only a `home/<name>_landing.html` template is taken). Drishti
  Risk's: one template, `home/risk_landing.html`, and `static/js/risk-landing.js` / `static/css/risk-landing.css`; every figure is the
  engines' own answer through the console's `/api/var/...` and `/api/exposure/...` (`scope`, `scope/history`, `tree`, `intraday/streams`,
  `official`, `baskets`). They are not Sutras: a Sutra lays out one entity's view, and these pages span the engines with no entity; a new
  "home" Sutra kind would be needed, which the product owner may ask for later. No new panel kind was added (the heat map is a CSS grid
  of links). The theme's own variables draw it in every theme; one column on a phone.
- **What the engines do not serve yet** (left out or replaced): exposure (EPE) per node of a VaR hierarchy (the map shows VaR and limit use
  only); credit-limit use per desk (the heat map shows each node's VaR limit use); the "recent" commands of mock-up B (the person's
  baskets and monitors instead); the time of an official capture (the feed names the date captured).
- **The choice.** `home.landings` lists the landings offered, `home.landing` the default; links on Home switch, and the choice is kept in
  the person's settings (`homeLanding`, one value, read back by the server's `/api/v1/me/settings` since the 2.9.6 fix): a landing the
  current edition does not offer (World's `explorer` seen through Risk) gives that edition's default, and `map`, both editions' third
  landing, follows the person from one to the other. `home.landing-by-role: {role: landing}` is read and wins over the choice when configured: the
  hook for role-based access later. `?landing=today` (the link "The classic Home") shows the product's Home with the edition's sections.

## 8. Lenses

`drishti.lenses: risk` on a full instance (blank by default: no lens link, the full product as before) offers the risk edition as a lens;
`drishti.lenses: risk,world` offers both (each person sees **Open Drishti Risk** and **Open Drishti World**; inside one, **Leave …** first,
then **Switch to …** the other: opening a lens while in another switches, one settings patch).
The link **Open Drishti Risk** (in Views and on Home) sets the person's `lens` setting; from then on, on every page and every server, the
console shows them the edition's menus, words, help, Home landings, Admin pages and branding, and tells the server on each call
(`X-Drishti-Lens: risk`) so the command line, FUNC, the type-ahead and BI follow it too (`EditionContext.current`). **Leave Drishti Risk**
clears it. No restart; every pack stays loaded; other people are unaffected. An instance that runs an edition offers no lens.

**A lens hides; it does not secure.** Data access stays with roles, entitlements and masks: a person who types a hidden command's API
call directly gets what their roles allow. `lens.roles` (who sees the link, which roles start in the lens) is documented as a hook for
role-based access later; it is not wired.

## 9. Decisions (product owner)

1. The first edition is `risk`, **Drishti Risk**: VaR and exposure first; liquidity, climate and more join as more lines in the same file.
2. Branding: Drishti's name and `{eye}` mark kept; the product name is Drishti Risk; white-labelling possible through site configuration.
3. BI is reduced to the edition's datasets: results, limit use, backtests (fields of the VaR results), exposure results and official records.
4. Commands are hidden, never renamed.
5. Fully clustered: one value on the servers; the console learns it from the about answer.
6. Landing: the three mock-ups built (section 7); Admin pages and menus are hidden by configuration, not new screens.
7. Without an edition Drishti behaves exactly as before (tested: `EditionNoneAndLensServerTest`, `test_edition.py`, and every existing suite).
8. Lenses (2026-10-29): a per-person "Open Drishti Risk" on a full instance; hides, does not secure; RBAC later.
9. Drishti World (2026-10-29): a second edition, `world`, for the public-data packs; the three World mock-ups built; two lenses side by
   side (`risk,world`); IMF and Yahoo data shown inside the bank, outside it needs a decision.

## 10. Tests

| Test | What it proves |
|---|---|
| `EditionsTest` (drishti-packs) | the default list is the one file; the layer's place (under the site, above the product's defaults); presets; a site variable still wins; a missing file stops the start; lenses |
| `EditionRiskServerTest` | a server with `risk`: About and branding, the five packs, `DRS-1017` on Admin → Packs, FUNC and the type-ahead, `DRS-4023` typed and by page, the packs' examples, BI datasets and reports |
| `EditionNoneAndLensServerTest` | no edition: the default packs, every command and dataset, no edition layer; a lens header focuses one request only |
| `*RiskEditionTest` (seven classes) | the VaR suites (scopes, contributions, baskets, captures, what-if, currencies, intraday, hierarchies) and exposure suites, and both official-records-in-BI suites, all again under `risk` |
| `EditionShardClusterTest` | two Drishti*net members with `risk`: the same edition everywhere, no Admin → Net warning, sharded VaR equal to one server's |
| `test_edition.py` (console) | the full product unchanged (every menu link of before); every surface under `risk`; the three landings and the remembered choice; the role hook; the lens for one person and not another; no lens with an edition; logo, favicon, manifest |
| `test_edition_browser.py` | the three landings filled from the engines' answers, at phone and desktop widths, in every theme |
| `EditionWorldServerTest` | a server with `world`: About and branding, the five public-data packs, `DRS-1017` for another, the packs' own commands in FUNC, the type-ahead and the examples, the world-economy BI datasets and report only |
| `EditionTwoLensesServerTest` | `drishti.lenses=risk,world`: both offered; the lens and the landing kept by the real settings API and read back; that person's commands and BI focused on World or Risk (`DRS-4023` for the other's), switching by one patch, others unaffected |
| `test_edition.py` (World) | every surface under `world`; its three landings, the remembered choice, the recents of the edition; `/api/home/search` refusing hidden commands and carrying the lens; two lenses, opening, switching, leaving |
| `test_edition_world_browser.py` | World's three landings filled from server-shaped answers, at 390 and 1280 px, in every theme with the teal accent, no sideways scroll |

## 11. Later editions

The same mechanism serves other focused products without new code: liquidity and climate join `risk` as more lines once their measures
exist; a **funds** edition (us-equities, gleif, funds) or a **data platform** edition (BI and Build without the risk packs) is one more file
in `config/editions/`. Drishti World (section 12) was built exactly that way: one file, its landings, no change to the engines.

## 12. Drishti World (built 2026-10-29, for 2.9.7)

The product owner asked for a second edition on the same mechanism (2026-10-29): **`world`**, branded **Drishti World**, the public-data
packs in one product. It is one file, [`config/editions/world.yaml`](../../config/editions/world.yaml), plus its landings; nothing in the
engines changed. The IMF figures and the Yahoo prices in these packs are shown inside the bank (product owner, 2026-10-29); showing the
edition outside it still needs a decision.

| Area | Shown | Hidden |
|---|---|---|
| **Packs** | world-economy, news-and-markets, us-equities, gleif, funds (allow-only; none of their links leads into core or market-data) | core, market-data, trading, counterparty-risk, market-risk, the example packs (`DRS-1017`) |
| **Commands** | the packs' own 32: `ECON`, `MACRO`, `FCST`, `LABR`, `FISC`, `ECG`, `REGE`, `JUR`, `PARTY`, `OFFC`, `ELEC`, `REGN`, `SOCI`; `TOPIC`, `NEWS`, `CO`, `QUOTE`; `USEQ`, `IDX`, `SIC`, `EDGAR`, `FILING`, `FACT`, `PXH`; `LEI`, `LEIG`, `LEIR`, `LEIC`, `LEIM`; `FUND`, `HELD`, `TRACK`; the words `BI`, `FORM`, `FUNC`, `WHAT`, `DATAMAP` | `VAR`, `EXP` and every risk command; `RUPAKA` |
| **Menus** | Views: Terminal, Search, Forms, Functions, Data map, Reports, Workspaces, Monitors, Alerts; BI; Admin; Help | VaR, Exposure, Build |
| **Features** | BI (the edition's datasets), forms, the data map, layouts, workspaces, monitors, alerts, reports, lakes | VaR, exposure, Calc, the Python console and visuals, the designer, Studio, Build, AI, embedding |
| **Help** | Knowledge (Economies; Companies, funds and ETFs; Entities and ownership; the glossary; data and contracts), Start, Use, BI, Data, Operate | Build; the risk packs' Knowledge pages |
| **BI** | datasets `world-economics-economies`, `region-economics-regions`; models `world-economics`, `region-economics`; their reports | every other dataset, model, report and board |
| **Admin** | as Drishti Risk | as Drishti Risk |
| **Branding** | **Drishti World**, "Economies, markets, companies and funds from public data", Drishti's name and eye | |

### Its Home: three landings

The product owner approved all three mock-ups (2026-10-29), drawn in the terminal look with a **teal accent** (a deeper teal on the light
themes) so World reads apart from Risk at a glance. One template, `home/world_landing.html`, with `static/js/world-landing.js` and
`static/css/world-landing.css`; `home.titles` names them.

- **A "desk", World desk** (the default): tiles (World GDP growth from the World country group, GDP-weighted; quotes up and down in the
  market snapshot; the biggest mover; new SEC filings on the feed's latest day; funds and ETFs with the largest); **Economies that moved**
  (the twelve largest economies, this year's growth against the year before, from each one's growth path, largest move first); **Today's
  news** by topic (each of `home.topics`' latest stories, newest first); quick cards for `ECON`, `USEQ`, `LEIG` and `FUND`.
- **B "explorer", Explorer**: a big command line; the chips of `home.chips`; a counts strip (economies, US companies, legal entities, funds
  and ETFs, filings, news topics); Recently opened (the person's own command history, the edition's commands only), Knowledge, Data map
  and Learn cards.
- **C "map", World map**: the world by **region**, **income group**, **G7** or **G20**, and by **growth**, **inflation** or **debt to
  GDP**: each group's figures weighted by GDP from its economies' own (`ECON`), the G7 and G20 as their member economies; the leader marked;
  each tile opens its country group or economy. **US sectors** (the companies' revenue by SIC division), the **largest funds** (net
  assets, the index each tracks from `TRACK`, holdings), **What changed** (the IMF outlook's vintage, the filings on the feed's latest day,
  the latest annual report, the market snapshot, GLEIF on file).

**How it reads.** One thin read-only route, `/api/home/search` (`routes/edition_routes.py`): the server's own search grammar
(`ECON where GDP_USD_BN > 0 order by GDP_USD_BN desc limit 12`), refused with `404 DRS-4023` for a command the person's edition or lens does
not show, and the server filters again by the lens it is told (`X-Drishti-Lens`). Entities are read through the existing `/api/raw`. Each
panel paints when its answer arrives; one refused or empty says so in place.

**What the packs do not serve** (left out or replaced by the nearest real figure): the S&P 500 level and USD/CAD (the market snapshot
holds twenty equity quotes, no index and no FX: the tiles show the quotes' breadth and the biggest mover instead); elections in the next 90
days (an election's next date is free text: the tile shows funds and ETFs instead); G7, G20 and emerging-market country groups (the pack's
groups are the World Bank regions, income groups and the World: G7 and G20 are shown as their member economies, and the income groups stand
for "emerging markets"); a debt aggregate per group (computed, GDP-weighted, from the members' `DEBT_TO_GDP`); "since yesterday" changes
(no change feed: What changed shows the latest of each source); new GLEIF entities and parent changes (the GLEIF counts on file instead);
"3 companies changed sector" (not served); the IMF outlook's count of revised economies (its vintage instead).

### Two lenses

`drishti.lenses: risk,world` offers both on a full instance (the product owner's stack runs it). In Views and on Home each person sees
**Open Drishti Risk** and **Open Drishti World**, each with its edition's description; inside one, **Leave …** comes first and **Switch to
…** the other (no need to leave first). The lens and the landing are the person's settings, kept and read back by the server.
