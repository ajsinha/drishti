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

**Status: built (release 2.9.6; Drishti World and Drishti Market built 2026-10-29, Drishti Trading 2026-10-30, for 2.9.7).** The first edition is **`risk`**, branded
**Drishti Risk**: a one-stop shop to manage risk, VaR and exposure first. The second is **`world`**, **Drishti World**: the public-data packs
(section 12). The third is **`market`**, **Drishti Market**: the bank's own market data (section 13). The fourth is **`trading`**, **Drishti
Trading**: a trader's or desk head's own books, P&L, VaR and limits, with "my" scoping (section 14). The how-to guide is [EDITIONS_GUIDE.md](../admin/EDITIONS_GUIDE.md); this document is the design as built. The decisions
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
          VOFF, AGR, CSA, NSET, LIM, COLL, MC, XRUN, XPATH, EXP, XWIF, XOFC, NSMTM, CPMTM]
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
| **Commands** | VaR (`VAR`, `VRES`, `VWIF`, `VOFF`, `VLIM`, `HIER`, `PORT`, `POS`, `PNLV`, `SPV`, `FXS`, `SSET`), exposure (`EXP`, `XRUN`, `XPATH`, `XWIF`, `XOFC`, `AGR`, `CSA`, `NSET`, `LIM`, `COLL`, `MC`), the derived totals (`NSMTM`, `CPMTM`), reference data (`CPTY`, `GRP`, `LE`, `BOOK`, `DESK`, `TRDR`, `CCP`), trades (`TRD`, `CTRD`, `PTRD`, `LTRD`), `FX`; the words `BI`, `FUNC`, `WHAT`, `DATAMAP` | `FRTB`, stress (`STR`, `SCN`, `SSPNL`), `PNL`, `TVAL`, `CVA`, `SACCR`, `SIMM`, the curves and surfaces, `ISS`, `CAL`, `CLR`; the words `FORM` and `RUPAKA` |
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
  landing, follows the person from one to the other. `home.landing-by-role: {role: landing}` is read and wins over the choice when configured (Drishti Trading's trading role picks the first landing below the choice: section 14): the
  hook for role-based access later. `?landing=today` (the link "The classic Home") shows the product's Home with the edition's sections.

## 8. Lenses

`drishti.lenses: risk` on a full instance (on by default since 2.9.7 as `risk,world,market,trading`, the four lenses; blank: no lens link, the full product as before) offers the risk edition as a lens;
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
10. Drishti Market (2026-10-29): a third edition, `market`, for the market-data pack; the three Market mock-ups built (the Quality
   landing with the checks the data supports, no impact figures); three lenses (`risk,world,market`).
11. Drishti Trading (2026-10-30, mock-ups approved: "show me mockups and then let us just build"): a fourth edition, `trading`, for traders
   and desk heads; the three Trading mock-ups built (Trader desk, Desk head, Explorer) with "my" scoping from configuration, a trader
   record or the person's own View as choice; four lenses (`risk,world,market,trading`), the default.

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
in `config/editions/`. Drishti World (section 12), Drishti Market (section 13) and Drishti Trading (section 14) were built exactly that way: one file, its landings, no
change to the engines.

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

## 13. Drishti Market (built 2026-10-29, for 2.9.7)

The product owner asked for a third edition on the same mechanism (2026-10-29, mock-ups approved: "mockups look cool, go ahead"):
**`market`**, branded **Drishti Market**, the bank's own market data, the curves, surfaces and fixings that feed risk and valuations. No
trades and no VaR. It is one file, [`config/editions/market.yaml`](../../config/editions/market.yaml), plus its landings and one console
helper (`core/market_landing.py`); nothing in the engines changed.

| Area | Shown | Hidden |
|---|---|---|
| **Packs** | market-data, and core (market-data extends it: issuers and holiday calendars), allow-only | trading, counterparty-risk, market-risk, the public-data packs, the example packs (`DRS-1017`) |
| **Commands** | the pack's 22: `CRV`, `REPO`, `IRV`, `CPV`, `FIX`, `INF`, `INFC`; `FX`, `FXF`, `FXV`; `CDS`, `BND`; `EQ`, `EQX`, `DIV`, `EQV`, `CMD`, `CMDC`, `CMDV`; `CORR`, `CORRC`, `EQB`; the words `BI`, `FORM`, `FUNC`, `WHAT`, `DATAMAP` | `VAR`, `EXP` and every risk and trade command; core's own (`CPTY`, `BOOK`, `CAL`, `ISS` …); `RUPAKA` |
| **Menus** | Views: Terminal, Search, Forms, Functions, Data map, Reports, Workspaces, Monitors, Alerts; BI; Admin; Help | VaR, Exposure, Build |
| **Features** | BI (the edition's datasets), forms, the data map, layouts, workspaces, monitors, alerts, reports, lakes | VaR, exposure, Calc, the Python console and visuals, the designer, Studio, Build, AI, embedding |
| **Help** | Knowledge (Market data; the glossary; data and contracts), Start, Use, BI, Data, Operate | Build; the other packs' Knowledge pages |
| **BI** | `market-data-*` datasets and the `market-data` model: the pack ships none yet, so BI offers its themes only | every VaR, exposure, trading and world dataset, model, report and board (an empty list would have offered them all) |
| **Admin** | as Drishti Risk | as Drishti Risk |
| **Branding** | **Drishti Market**, "Curves, surfaces and fixings: the bank's market data that feeds risk and valuations", Drishti's name and eye | |

### Its Home: three landings

Drawn in the terminal look with a **violet accent** (`#a78bfa` on the dark themes, `#6d28d9` on the light ones). One template,
`home/market_landing.html`, with `static/js/market-landing.js` and `static/css/market-landing.css`. The figures are worked out in the
console by `core/market_landing.py` from the pack's own answers (search, raw documents, the business date and one field's history,
`/api/v1/history/{kind}/{id}/series`), with the person's identity, lens and date box, read-only and cached for two minutes per person, and
served by `/api/home/market/desk`, `/api/home/market/moves` and `/api/home/market/quality` (`404 DRS-4023` unless the person's edition or
lens shows `CRV`).

- **A "desk", Market desk** (the default): tiles **USD OIS 10Y** (`CRV-USD-OIS`'s `TEN_Y`), **USD 2s10s** (`CRV-USD-PROJ`'s
  `SLOPE_2S_10S`), **EUR/USD 1M ATM vol** (`FXV-EURUSD`'s `ATM_1M`), **CDS 5Y, median** (every credit curve's `SPREAD_5Y`) and **Data
  exceptions** (the Quality checks' count, opening C). A day-over-day change is shown only when the history holds real data for the latest
  day and the business day before it (a point whose data date is its date, not one carried from earlier); otherwise the tile says "no
  change: no history for the day before". **USD curves**: OIS zero rates today against five business days before when the history holds
  that day (dashed), and projection; without that history, OIS against projection. **Biggest moves**: each curve's `TEN_Y`, credit curve's
  `SPREAD_5Y`, FX spot's `MID`, commodity's `FRONT` and index's `LEVEL` over 21 business days; the latest day's move in standard deviations
  of the item's own daily moves when ten or more are held (a "jump" at 3σ or more); with less history, the largest moves against the
  business day before; with none, the card says so and ranks nothing. Quick cards: `CRV CRV-USD-OIS`, `FXV FXV-EURUSD`, `IRV IRV-USD`,
  `FIX FIX-SOFR`.
- **B "explorer", Explorer**: a big command line; the chips of `home.chips`; counts by asset class (rates, FX, credit and bonds, equity and
  commodities; each command's `matched` from the server's search); the commands grouped by asset class, each with its count; Recently
  opened (the person's own history, Market's commands only), Knowledge, Data map and Learn cards.
- **C "quality", Quality**: the checks the data supports today, by severity, worst first, each item opening its command:
  1. **Stale** (warning): a document's `AS_OF` (curves and repo curves), or the date its source holds it for, older than the business date
     by more than `quality.stale-days` business days (2; weekends and the server's holidays skipped). Curves and surfaces are checked one by
     one; every other kind by its latest load (one document).
  2. **Gaps** (warning): a curve missing a tenor that every other curve of its type has (IR curves by `CURVE_TYPE`; repo, credit, FX
     forward and inflation curves by kind; a type of three or more).
  3. **Discount factors and forwards**: a discount factor that does not fall with maturity, or is outside (0, 1] (critical); a negative
     forward rate in `FORWARDS` (warning).
  4. **Vol surfaces** (critical): a vol at or below zero (risk reversals and butterflies may be negative), and ATM total variance (vol² × T)
     falling with expiry on the FX and equity surfaces (the commodity, cap and swaption grids have a different underlying per row, so
     calendar spreads are not checked there).

  Tiles: Critical, Warnings, **Clean** (the share of the items checked with no exception) and, in place of the mock-up's "VaR at stake",
  the note *Impact on VaR and valuations comes in a later release*. A **Checks run** card gives each check's items and flags.

**Not built** (planned for 2.9.9, IMPLEMENTATION_PLAN.md): the Impact card (who uses each flagged point, VaR at stake), jumps over n σ as a
quality check, and two sources disagreeing (the lake holds one source per kind today). On the committed demo data every check is clean:
the market data is consistent and its dates move with the lake's business dates.

### Three lenses

`drishti.lenses: risk,world,market` offers all three on a full instance (since Drishti Trading the default and the stack are four lenses, section 14): **Open Drishti Risk**, **Open
Drishti World** and **Open Drishti Market** in Views and on Home; inside one, **Leave …** first and **Switch to …** each of the others.
A landing chosen in one lens is kept when it is also the next one's (`desk`, `explorer`); otherwise that edition's default is shown.

| Test | What it proves |
|---|---|
| `EditionMarketServerTest` | a server with `market`: About and branding, core and market-data only, `DRS-1017` for trading, market-risk and world-economy, the 22 commands in FUNC and the type-ahead, the examples, no VaR, exposure or world BI item |
| `EditionTwoLensesServerTest` | now `risk,world,market`: three offered; the Market lens set and read back through `/api/v1/me/settings`, its 22 commands, `VRES`, `ECON`, `TRD` and `FUND` refused with `DRS-4023`, switching to World and Risk and back |
| `test_market_landing.py` | business days; a change only from real data on both days; the tiles, curves (with the week-ago line) and biggest moves in σ, and none without history; each quality check against data with known stale, gap, discount-factor, forward and surface problems; the stale threshold |
| `test_edition.py` (Market) | every surface under `market`; the three landings, the remembered choice, the recents; `/api/home/market/*` and `/api/home/search` by edition and lens; `quality.stale-days`; three lenses, opening, switching, leaving |
| `test_edition_market_browser.py` | A, B and C filled from server-shaped answers, at 390 and 1280 px, no sideways scroll, the page-width audit, every theme with the violet accent; the known problems listed worst first, each opening its command |

## 14. Drishti Trading (built 2026-10-30, for 2.9.7)

The product owner asked for a fourth edition for traders and desk heads (2026-10-30, mock-ups approved: "show me mockups and then let us
just build"): **`trading`**, branded **Drishti Trading**, a person's own books, P&L, VaR and limits, today's trades and counterparties, and
whether the next trade fits. It is one file, [`config/editions/trading.yaml`](../../config/editions/trading.yaml), its landings, one console
helper (`core/trading_landing.py`) and its read-only routes (`routes/trading_routes.py`); one server change: the person's settings keep
their View as choice (`tradingView`). Nothing in the engines changed.

| Area | Shown | Hidden |
|---|---|---|
| **Packs** | core, market-data, trading, market-risk, counterparty-risk (allow-only; trading extends core and market-data, market-risk extends market-data and trading, counterparty-risk extends trading) | the public-data packs, the sample and example packs (`DRS-1017`) |
| **Commands** | the blotter `TRD`, `CTRD`, `PTRD`, `LTRD`, `ITRD`; books and positions `BOOK`, `DESK`, `TRDR`, `POS`; risk on my book `VLIM`, `VWIF`, `VRES`, `TVAL`, `PNLV`, `HIER`, `PORT`; counterparties `CPTY`, `GRP`, `NSET`, `NSMTM`, `CPMTM`, `LIM`, `XWIF`, `EXP`; market data `CRV`, `FX`, `FXV`, `EQ`, `EQX`, `CMD`, `CMDC`, `BND`, `CDS`; the words `VAR`, `EXP`, `BI`, `FUNC`, `WHAT` | regulatory (`FRTB`, `SACCR`, `CVA`, `SIMM`), stress (`SCN`, `STR`, `SSPNL`), collateral operations (`COLL`, `MC`), the exposure engine's runs, paths and official records, VaR's scenario inputs, macro economics; the words `DATAMAP`, `FORM`, `RUPAKA`. `PSUM`, `PSTRIP` and `PBOOK` (asked for) are the compute-demo **sample** pack's synthetic P&L strips, not a trading pack: left out |
| **Menus** | Views: Terminal, Search, Functions, VaR, Exposure, Reports, Workspaces, Monitors, Alerts; BI; Admin; Help | Forms, Data map, Build |
| **Features** | VaR, exposure, BI (the edition's datasets), layouts, workspaces, monitors, alerts, reports, lakes | Calc, the Python console and visuals, the designer, Studio, Build, forms (as Drishti Risk), the data map, AI, embedding |
| **Help** | Knowledge (Trades and products, Market risk, Counterparty credit risk, Entities and ownership, Market data; the glossary; data and contracts), Start, Use, BI, Data, Operate | Build; the public-data packs' Knowledge pages |
| **BI** | datasets `trading-risk-*`, `trading-cashflows-*`, `trades-basic-trade`, `live-trading`, `live-trading-trade`, `market-risk-var-latest`, `market-risk-var-official`; their models and reports | exposure, stress, world and market-data items |
| **Admin** | as Drishti Risk | as Drishti Risk |
| **Branding** | **Drishti Trading**, "Your books, your P&L and your limits, and whether the next trade fits", Drishti's name and eye | |

### "My": who a person is, configured

The landings open on **the person's own** books or desk. Who that is, in order:

1. **Configuration.** `trading.people` in the edition file, with the console's own `trading.people` (`application.local.yaml`) added on top
   (a name in both: the console's): `{user: {trader: TRDR-ASHAH}}` (their trader's books) or `{user: {desk: DESK-COMM, role: desk-head}}`.
   User names are compared ignoring case. The demo file maps `drishti-dev-admin` to A. Shah's books.
2. **A trader record** (`trading.match-records`, on): a `trader` whose id is `TRDR-<USER>` (case, dots and dashes ignored) or whose
   `NAME` is the person's display name, read with their own search.
3. **Their own choice**: a person with neither sees a small **View as** picker (the traders and desks their search returns); the choice is
   kept in their settings (`tradingView`: `trader:<id>` or `desk:<id>`, validated by the server's settings API), as `homeLanding` is.
4. An **administrator** always sees the picker, and their choice wins over a mapping (to look at any desk); a mapped person who is not an
   administrator cannot repoint themselves (`/home/trading/view-as` refuses with `DRS-4023`).

The trader's books come from their `trader` record (`BOOKS`), a desk's from its `desk` record, both read as the person. **Which landing
opens first follows the trading role**: `trading.landing-by-role: {desk-head: desk, trader: trader}`, the same `{role: landing}` shape as
`home.landing-by-role` read by the same code, for the mapping's `role` (a desk without a role: `desk-head`; a View as desk: `desk-head`).
It sits **below the person's own choice** (`homeLanding`), so a desk head who picks the Trader desk keeps it; `home.landing-by-role` (the
access-role hook) still wins over both when configured, as for the other editions.

**It never widens access.** Every figure is read through the existing routes as the person, with their token, roles, masks, lens and date
box; scoping only chooses which books the landing asks about. A role without raw sees `TRADER_ID` masked (the server's `redact`), so the
Desk head's figures by trader say so instead of guessing (tested: `test_scoping_never_widens_access_every_read_is_the_persons_own`).

### Its Home: three landings

Drawn in the terminal look with a **green accent** (`#4fd18b` on the dark themes, `#1a7f4b` on the light ones). One template,
`home/trading_landing.html`, with `static/js/trading-landing.js` and `static/css/trading-landing.css`. The figures are worked out by
`core/trading_landing.py` and served read-only by `/api/home/trading/{me,valuations,blotter,risk,limits,pnl-var,traders}` and
`POST /api/home/trading/check` (`404 DRS-4023` unless the person's edition or lens shows `TRD`; the check also `VWIF` and `XWIF`), cached
two minutes per person, date and scope; two panels needing the same valuations wait for one read.

- **A "trader", Trader desk** (the default; traders):
  - **P&L today**: the MtM change of every trade of my books valued on both the business date and the business day before (`TVAL`, read on
    both dates; the person's date box moves both), summed. No valuations the day before: the tile says so and shows no number. The
    valuations' own `PNL_1D` is a different figure on the demo data (the pricing systems' explained P&L) and is not used.
  - **My VaR**: the VaR engine's figure of the union of my books (`BOOK:A + BOOK:B`), labelled **intraday** with the trades updated and the
    time when the engine applied intraday updates. **Limit use**: the highest of my books' VaR ÷ its `var-limit` (each book's
    `/var/scope`), with the book and limit named.
  - **DV01**: the sum of the live trades' `DV01` when any holds a number; else the tile is left out.
  - **Trades today**: the trades (`TRD`) of my books whose `SOURCE_UPDATE_TIME` is on the business date: **cancelled** (`TRADE_STATUS`
    CANCELLED), **new** (`TRADE_ACTION` NEW, or `TRADE_DATE` that day), else **amended**. On the demo data every change of a day is an
    amendment or a cancellation (no trade is booked on its business date), so "new" reads 0 there. `SOURCE_UPDATE_TIME` is no store
    column, so that search reads every trade document; on a large lake it may not answer in time (the product owner's stack: `DRS-1004`):
    then the events come from the two days' valuations (a trade valued only on the later day is new, a status that became CANCELLED or
    AMENDED is cancelled or amended), with no time of day, and the tile says so.
  - **The day**: when the lake's latest valuations are older than the business date (a lake not loaded since), the figures are of that
    day against the business day before it, and the page says "the latest valuations are of …"; a day before that only carries the same
    data (its provenance's business date is earlier) counts as no valuations that day.
  - **Check a new trade**: product (the products of my live trades), side, notional (`250m`, `1.5bn`), counterparty and book in; one answer
    (below). **Today's blotter** (newest first, each trade's notional from its valuation), **Top positions** by VaR contribution (the
    engine's `/scope/contributions?by=position`, market value and component VaR), **Quick commands** (my trader, my book's VaR, my book,
    my most traded counterparty, then the file's cards).
- **B "desk", Desk head** (desk heads; a trader sees their own desk):
  - **Desk P&L today** (as above, over the desk, with the best book), **Desk VaR** and its limit use (`DESK:<id>`), **Breaches** (books
    above their limit), **Near limit** (books at `trading.near-limit`, 80%, or more), **Largest counterparty**: its share of the desk's
    positive MtM over live trades (current exposure before netting and collateral: the exposure engine has no desk scope).
  - **Limit use by book** over the last `trading.limit-days` (5) business dates, each cell VaR ÷ limit from that date's **official VaR
    record** (`/api/v1/var/official`), every cell printing its percentage (the colour only repeats it); a date without a record shows "—"
    and is named under the grid. The official record covers every node: a role that sees only some answers "not available" in place.
  - **By trader**: the desk's traders (their records), each one's P&L and live trades from the valuations' `TRADER_ID`, and their VaR as
    the sum of their trades' component VaR in the desk (`/scope/contributions?by=trade`, which adds up to the desk's VaR). With
    `TRADER_ID` masked the P&L and VaR are "—", the records' trade counts are shown with a star, and the card says why.
  - **P&L against VaR**: the desk's official history (`/scope/history`, mode `official`: each day's hypothetical P&L against the VaR before
    it), the last `trading.pnl-var-days` (60) days, exceptions circled. A replayed history (no official records) is scenario P&L, not a
    day's: then the card is **left out** with that note.
  - **Overnight movers**: the trades whose MtM moved most since the day before, biggest first.
- **C "explorer", Explorer**: a big command line; the chips of `home.chips`; the commands grouped (blotter, books and positions, risk on
  my book, counterparties, market data); Recently opened (the person's own history, Trading's commands only).

### The new-trade check

One answer from the two engines, each asked once, as the person, with no new risk maths: the engines price a **copy of a booked trade**,
so the check picks one: a live trade of the product with a notional in my books (the counterparty's own first, then the chosen book's,
then the largest notional), and scales it to the notional (factor = notional ÷ its notional, negative when the side differs from the
copy's).

- **VaR**: `POST /api/v1/var/what-if {scope: BOOK:<book>, items: [{copyOf, factor}]}` (the VaR page's basket what-if, the Python VaR engine)
  gives VaR before and after; the book's limit (its `/var/scope`) gives limit use before and after: **fits** below `near-limit`, **near**
  from it, **breaches** above 100%; no limit: the change only. Without the Python engine the half says so (`DRS-5205`).
- **Counterparty**: `POST /api/v1/exposure/what-if {scope: CPTY:<cp>, method: paths, q: the limit's confidence, candidates: [the copy into
  the counterparty's largest netting set]}` (the exposure page's pre-deal check) gives peak PFE before and after against the counterparty's
  credit limit: fits, near or breaches. When the exact `paths` method cannot run (the Python exposure engine off, `DRS-5446`, or no netting
  set to deal into) the engine's own conservative `addon` proxy answers, labelled **proxy** with the reason.

### What the data does not give (left out or labelled, never invented)

The DV01 tile when no live trade holds a DV01; P&L today, by book and by trader, and the movers without valuations on the day before; the
P&L-against-VaR card without official history; by-trader P&L and VaR for a role that sees `TRADER_ID` masked; a book's limit use on a date
without an official record; VaR by trader beyond the engine's `max-parts` (5,000 trades: the largest, said); "new" trades on data that
books none on the day; a trade's time of day other than its `SOURCE_UPDATE_TIME`; the largest counterparty's share of **exposure** (the
exposure engine has no desk scope: the share of positive MtM instead, named so). A scope larger than `trading.max-rows` (20,000 valuations
a date) is read in part and every figure says **partial**.

### Four lenses

`drishti.lenses` defaults to `risk,world,market,trading` (the server's `application.yaml`, `drishti-stack/stack.yaml`,
`deploy/docker/compose.yaml`, `deploy/aws/env.example`): **Open Drishti Trading** in Views and on Home beside the others; inside one,
**Leave …** first and **Switch to …** each of the other three. Build and Studio are off inside it, so a page names its Sutra without the
designer link (`design_on()`).

| Test | What it proves |
|---|---|
| `EditionTradingServerTest` | `drishti.lenses=risk,world,market,trading`: the lens and the View as choice set and read back through `/api/v1/me/settings` (a bad value refused); through the lens FUNC lists the 33 commands, and `ECON`, `SCN`, `COLL` and `FRTB` answer `DRS-4023` typed or opened; BI the trading and VaR datasets only |
| `EditionTwoLensesServerTest`, `SettingsTest` | four lenses offered, switching through all four; `tradingView` kept, read back and validated |
| `test_trading_landing.py` | the mapping (edition, console wins), a trader record by id or name, the remembered choice, an administrator's choice; P&L only from valuations on both dates; paging the search; today's events; VaR and limits; the official limit use with a missing day; P&L against VaR only from an official history; by trader, and masked; the check (one what-if each, the template and factor, the proxy fallback, bad input) |
| `test_edition.py` (Trading) | every surface under `trading`; the three landings and the remembered choice; the role's landing below the person's choice and the access-role hook above it; "my" scoping mapped, from a record, View as remembered and refused to a mapped person; every read as the person (never widening); the check answer; the routes by edition and lens; four lenses; no design link inside the Trading lens |
| `test_edition_trading_browser.py` | A, B and C filled from server-shaped answers, at 390 and 1280 px, no sideways scroll, the page-width audit, no request to any host but the console, every theme with the green accent; the check box end to end |
