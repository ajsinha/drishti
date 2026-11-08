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
# Public sector: government data and free news

The **public sector** is government and official data, and free news, from public sources: part of the **economy module**, with a lens
of its own (Public sector). Six packs, each filled every day by its own feed, show a country's development, a central bank's rates, a national statistic, an OECD
member's cycle, the press releases of central banks, regulators and governments, and what the world's news said about a country yesterday.
Every source's licence is recorded; what may not be redistributed is marked internal only.

The hands-on companion is [Tutorial 14](../tutorials/14-public-sector-data.md). The feeds machinery (run, load, schedule, monitor, write a
feed) is the [External data guide](EXTERNAL_DATA.md); every column of every kind is in the data contracts (section 4).

## Contents

1. [The module, its lens and its functions](#1-the-module-its-lens-and-its-functions)
2. [Turn it on, or off](#2-turn-it-on-or-off)
3. [The screens](#3-the-screens)
4. [The packs and their data contracts](#4-the-packs-and-their-data-contracts)
5. [The feeds](#5-the-feeds)
6. [Licences and internal-only data](#6-licences-and-internal-only-data)
7. [Changing what is pulled](#7-changing-what-is-pulled)
8. [Tests](#8-tests)
9. [Troubleshooting](#9-troubleshooting)
10. [Later sources](#10-later-sources)

## 1. The module, its lens and its functions

| Part | What it is |
|---|---|
| Module | `economy`: content only (packs, functions, lenses; no Java of its own), declared in the core server's manifest (`drishti-server/src/main/resources/META-INF/drishti/module.yaml`) beside world-economy and news-and-markets, and switched by `config/modules/economy.yaml` ([ACCESS_MODEL.md](../architecture/ACCESS_MODEL.md)). One module (the owner, 2026-11-07): the public sector is not licensed or switched apart from the economy |
| Lens | **Public sector** (`publicsector`, in `META-INF/drishti/lenses.yaml`): the ten functions below; offered while the economy module is on |
| World lens | carries the free news too: `PRESS`, `PREL` and `GDELT` |
| Packs | `world-development`, `central-banks`, `national-statistics`, `oecd`, `official-news`, `gdelt-events`, each with `domain: economy` |
| Lakes | `worldlake` (development, central banks, statistics, OECD) and `newslake` (official news, GDELT): their lake files ship with exactly these packs' tables |
| Feeds | one a pack, `config/feeds/<pack>.yaml`, daily, on drishti-feeds (section 5) |

The functions (command words), each owned by the economy module and gated by the access registry:

| Word | Kind | Opens |
|---|---|---|
| `WDI` | [`development-profile`](../contracts/samples/world-development.md#development-profile) ([contract](../contracts/packs/world-development.md#wdi-development-profile-development-profile)) | a country's development profile (World Bank) |
| `WDIS` | [`development-series`](../contracts/samples/world-development.md#development-series) ([contract](../contracts/packs/world-development.md#wdis-development-indicator-series-development-series)) | one World Bank indicator of one country by year |
| `CBANK` | [`central-bank`](../contracts/samples/central-banks.md#central-bank) ([contract](../contracts/packs/central-banks.md#cbank-central-bank-central-bank)) | a central bank: policy rate, last move, series |
| `CBSER` | [`rate-series`](../contracts/samples/central-banks.md#rate-series) ([contract](../contracts/packs/central-banks.md#cbser-central-bank-series-rate-series)) | one central-bank or market series (FRED, Bank of Canada) |
| `NSTAT` | [`statistics-series`](../contracts/samples/national-statistics.md#statistics-series) ([contract](../contracts/packs/national-statistics.md#nstat-national-statistic-statistics-series)) | one Statistics Canada series by vector |
| `OECD` | [`oecd-country`](../contracts/samples/oecd.md#oecd-country) ([contract](../contracts/packs/oecd.md#oecd-oecd-country-oecd-country)) | an OECD member's latest measures, the members on a map |
| `OECDS` | [`oecd-series`](../contracts/samples/oecd.md#oecd-series) ([contract](../contracts/packs/oecd.md#oecds-oecd-series-oecd-series)) | one OECD measure of one member by period |
| `PRESS` | [`release-source`](../contracts/samples/official-news.md#release-source) ([contract](../contracts/packs/official-news.md#press-official-press-releases-release-source)) | a publisher's latest press releases, each a link |
| `PREL` | [`press-release`](../contracts/samples/official-news.md#press-release) ([contract](../contracts/packs/official-news.md#prel-press-release-press-release)) | one press release |
| `GDELT` | [`event-country`](../contracts/samples/gdelt-events.md#event-country) ([contract](../contracts/packs/gdelt-events.md#gdelt-world-events-by-country-event-country)) | a country's day in the world's news |

The packs' own codes open their overviews: `WDEV`, `CBNK`, `STCAN`, `OECDP`, `ONEWS`, `GDLT`.

## 2. Turn it on, or off

The economy module is **on** as shipped (installed with the core server, licensed by the development licence, `enabled: true`). The
public-sector packs load when the server's pack list names them:

```bash
DRISHTI_PACKS=core,world-economy,world-development,central-banks,national-statistics,oecd,official-news,gdelt-events \
  java -jar drishti-server/target/drishti-server-*-exec.jar
```

and a person opens the Public sector lens from Views (`drishti.lenses: risk,world,publicsector`).

**Off** is the economy module off (Admin → Modules, or `config/modules/economy.yaml` `enabled: false`, or
`DRISHTI_MODULES_ECONOMY_ENABLED=false`, then a restart): all eight of its packs and their connectors do not load, its functions (these
ten and the economy's own) are gone everywhere (command line, type-ahead, search, menus, the APIs behind them, `404 DRS-1026`), and the
World and Public sector lenses are not offered. Banking, capital markets and GLEIF are unaffected. A single function is switched off on
Admin → Commands. The guarantees are `EconomyModuleOffTest` and `PublicSectorLensTest`.

## 3. The screens

Every screen is a Sutra built from the Drishti UI kit (`config/ui-kit/fragments`: `series-line`, `region-map`, `ranked-bars`,
`source-footer`) and the kit's panel kinds (tables, key-value lists), drawn by Drishti Elements (drishti-chart with its maps,
drishti-grid). No screen has code of its own. A web address in a cell is a link that opens the original in a new tab.

| Screen | Strip | Panels |
|---|---|---|
| `WDI WDI-CAN` | country, region, income group, capital, population, GDP per head | the indicators with their latest year (a row opens its series); the **world map shaded by GDP per head** (a country opens its profile); source and licence |
| `WDIS WDIS-IND-NY_GDP_MKTP_KD_ZG` | indicator, latest, unit, year, previous, change | the history by year (chart); the World Bank's definition; F7 the country |
| `CBANK CBANK-FED` | policy rate, as of, last move and its size in basis points, currency | the policy rate over two years (chart); the bank's series with latest value and change (a row opens it); press releases one link away |
| `CBSER CBSER-FRED-DGS10` | latest, unit, date, change on the day and the year, high, low | the history (chart); what the series is; F7 the bank |
| `NSTAT NSTAT-V41690973` | latest, unit, period, change, change %, on the year %, release time | the history (chart); table, vector, frequency, scale |
| `OECD OECD-CAN` | leading indicator, unemployment, inflation, GDP growth | the measures (a row opens its history); the **members on a map shaded by inflation** |
| `OECDS OECDS-USA-CLI` | measure, latest, unit, period, change | the history (chart); the dataflow; F7 the member |
| `PRESS PRESS-FED` | publisher, type, country, releases kept, latest, last pull, internal only | the **latest releases newest first** (a title opens the release, the address the original); releases by category; the terms of reuse |
| `PREL <id>` | publisher, published, category, country, internal only | the title, the feed's summary, the original's address, the publisher |
| `GDELT GDELT-USA` | day, events, mentions, average tone, tone against the window, Goldstein, conflict % | tone by day (chart); the day's events by CAMEO category; the **world map shaded by tone**; the most-mentioned stories, each a link |

## 4. The packs and their data contracts

Every kind has a data contract (`config/packs/<pack>/contracts/<kind>.contract.yaml`): its key, its grain, its storage (connector,
lake table), its cadence (daily, the feed's schedule) and every field with its type, presence, unit, an example and its meaning. The
contracts are the one place for the data; the pages below are generated from them (`tools/contracts.py docs`), and each kind's worked
example (a real record as JSON, every field explained) is under [docs/contracts/samples](../contracts/samples/README.md).

| Pack | Kinds (each: its example and its contract) | Contracts | Lake table |
|---|---|---|---|
| `world-development` | [`development-profile`](../contracts/samples/world-development.md#development-profile) ([contract](../contracts/packs/world-development.md#wdi-development-profile-development-profile)) (key `PROFILE_ID`, `WDI-<ISO3>`), [`development-series`](../contracts/samples/world-development.md#development-series) ([contract](../contracts/packs/world-development.md#wdis-development-indicator-series-development-series)) (`SERIES_ID`, `WDIS-<ISO3>-<code>`) | [world-development.md](../contracts/packs/world-development.md) | `worldlake/development/<kind>` |
| `central-banks` | [`central-bank`](../contracts/samples/central-banks.md#central-bank) ([contract](../contracts/packs/central-banks.md#cbank-central-bank-central-bank)) (`BANK_ID`, `CBANK-<id>`), [`rate-series`](../contracts/samples/central-banks.md#rate-series) ([contract](../contracts/packs/central-banks.md#cbser-central-bank-series-rate-series)) (`SERIES_ID`, `CBSER-<FRED or BOC>-<code>`) | [central-banks.md](../contracts/packs/central-banks.md) | `worldlake/centralbank/<kind>` |
| `national-statistics` | [`statistics-series`](../contracts/samples/national-statistics.md#statistics-series) ([contract](../contracts/packs/national-statistics.md#nstat-national-statistic-statistics-series)) (`SERIES_ID`, `NSTAT-V<vector>`) | [national-statistics.md](../contracts/packs/national-statistics.md) | `worldlake/statistics/<kind>` |
| `oecd` | [`oecd-country`](../contracts/samples/oecd.md#oecd-country) ([contract](../contracts/packs/oecd.md#oecd-oecd-country-oecd-country)) (`COUNTRY_ID`, `OECD-<ISO3>`), [`oecd-series`](../contracts/samples/oecd.md#oecd-series) ([contract](../contracts/packs/oecd.md#oecds-oecd-series-oecd-series)) (`SERIES_ID`, `OECDS-<ISO3>-<measure>`) | [oecd.md](../contracts/packs/oecd.md) | `worldlake/oecd/<kind>` |
| `official-news` | [`release-source`](../contracts/samples/official-news.md#release-source) ([contract](../contracts/packs/official-news.md#press-official-press-releases-release-source)) (`SOURCE_ID`, `PRESS-<publisher>`), [`press-release`](../contracts/samples/official-news.md#press-release) ([contract](../contracts/packs/official-news.md#prel-press-release-press-release)) (`RELEASE_ID`, `PREL-<publisher>-<hash>`) | [official-news.md](../contracts/packs/official-news.md) | `newslake/officialnews/<kind>` |
| `gdelt-events` | [`event-country`](../contracts/samples/gdelt-events.md#event-country) ([contract](../contracts/packs/gdelt-events.md#gdelt-world-events-by-country-event-country)) (`EVENT_COUNTRY_ID`, `GDELT-<ISO3>`) | [gdelt-events.md](../contracts/packs/gdelt-events.md) | `newslake/gdelt/<kind>` |

Every document carries `PROVENANCE`: the source, the dataset and its vintage, the retrieval date, the licence, the attribution, the
address and `INTERNAL_ONLY`. Each pack's README (`config/packs/<pack>/README.md`) lists its kinds' data structures; its guide
(`config/packs/<pack>/guides/<pack>.md`, a Help page) ends with the generated *Data contracts* and *Data retrieval* sections.

**One record.** Each kind's example (a real record, every field explained) is its section on the data-sample pages, linked from the table above; the fields are its contract.

The packs' `samples/` keep a few documents (from the feeds' recorded fixtures, `python3 tools/packgen/publicsector/make.py`) for the
renderer's tests; they are never served beside the lake: demo and real data never mix
([DATA_LAKE_GUIDE.md section 6](DATA_LAKE_GUIDE.md)).

## 5. The feeds

Each pack has one feed on drishti-feeds: a feed file `config/feeds/<pack>.yaml` (every key: [External data](EXTERNAL_DATA.md))
and a Feed class in `tools/feeds/sources/`. Each pulls only what is new, writes one JSON Lines file a kind and business date with its
manifest (rows, SHA-256), and is loaded into its lake, replacing that date (a re-run is idempotent).

| Feed | Source | Default selection (`options:`) | Incremental by | Daily at (local) |
|---|---|---|---|---|
| `world-development` | World Bank WDI API v2 (the one fetcher of WDI: world-economy reads its output) | every economy, 15 years, 10 headline indicators in the profile (population, GDP per head, growth, inflation, unemployment, life expectancy, poverty, electricity access, internet use, central government debt); as series also every `kind: wdi` code of `tools/realdata/sources.yaml` back to its `years.first` (`series-from`) and the world, its 7 regions and 4 income groups (`aggregates`, series only) | the WDI source's last-updated date: unchanged, nothing written | 06:30 |
| `central-banks` | FRED API, Bank of Canada Valet | the Fed: target range upper limit, effective rate, 2- and 10-year Treasuries, 10y–2y, CPI, unemployment, total assets; the Bank of Canada: target rate, CORRA, 2- and 10-year yields, USD/CAD; two years of history | each series from 10 days before its last observation | weekdays 18:15 |
| `national-statistics` | Statistics Canada WDS | seven vectors: CPI (raw and seasonally adjusted), unemployment, monthly GDP, population, housing starts, the overnight rate; 60 periods | each vector from a quarter before its last period; a newer release time | weekdays 09:00 |
| `oecd` | OECD Data Explorer SDMX | the 38 members; the composite leading indicator, unemployment, CPI inflation, quarterly GDP growth; from 2020 | from three periods before each measure's last | 07:00 |
| `official-news` | publishers' RSS, Atom, the Federal Register API | the Fed, the ECB, the Bank of Canada, the Bank of England, the SEC, OSFI, the Federal Register, the Canada Gazette (Parts I and II); 90 days, 100 a publisher | a release already seen is never added again | 19:00 |
| `gdelt-events` | GDELT 1.0 daily event files | events by country (the first actor's CAMEO country), 30 days of history, countries with 20 events or more, the top 5 stories | the days after the last one read (at most 7) | 04:30 |

Run them as any feed:

```bash
python3 tools/drishti.py feed list                               # every feed: lake, route, schedule, licence, datasets
python3 tools/drishti.py feed run world-development --dry-run    # what it would ask; nothing changes
python3 tools/drishti.py feed run world-development              # pull, write <data>/datafeeds/world-development/<date>/, load into worldlake
python3 tools/drishti.py feed run official-news --load inbox     # the same through the data inbox: the server loads it
python3 tools/drishti.py feed status gdelt-events                # its last runs from the ledger
python3 tools/drishti.py feed schedule --cron world-development central-banks national-statistics oecd official-news gdelt-events
```

The wrappers are `tools/feeds/feed.sh` (Linux, macOS) and `.\tools\windows\feed.ps1` (Windows); Admin → Data loads → Feeds shows each
run and offers Run now. Exit codes: 0 ok, 1 failed, 2 refused, 3 locked, 4 partial.

**Keys.** Only FRED needs one: a free key from https://fred.stlouisfed.org/docs/api/api_key.html in `DRISHTI_FRED_API_KEY` where the feed
runs (the feed file names the variable, never the value). Without it the central-banks feed leaves the FRED series out, says so in its
log, and loads the Bank of Canada's.

**What a feed carries on without.** One publisher whose feed is down (official-news) or a day GDELT did not publish: the run goes on with
the others and the publisher's page says *failed: kept releases shown*. Every other failed request fails the run, its state is not saved,
and the next run asks again from the same point.

## 6. Licences and internal-only data

Each feed file records every source's licence and terms in `options.sources` (`licence`, `terms`, `redistributable`, `internal-only`,
`why-internal`, `attribution`) and a summary in `licence:`; every document repeats its source's in `PROVENANCE`. The reading of each
site's terms is the owner's, confirmed on 2026-11-07.

| Source | Licence and terms | Redistributable | Internal only |
|---|---|---|---|
| World Bank WDI | CC BY 4.0 | yes, with attribution | no |
| FRED | FRED API terms; the default series are US government works (public domain); a series FRED marks as third-party copyright needs its own flag | yes (default series) | no |
| Bank of Canada (Valet, press releases) | free to use, copy and distribute with attribution; inside a paid service, tell buyers it is free on bankofcanada.ca | yes, with attribution | no |
| Statistics Canada | Statistics Canada Open Licence | yes, with attribution | no |
| OECD | OECD terms (CC BY 4.0) | yes, with attribution | no |
| Federal Reserve Board, SEC, US Federal Register | public domain (US government works) | yes, citing the source | no |
| European Central Bank | free use with the ECB cited; inside a paid product, buyers told it is free | yes, with attribution | no |
| Bank of England | personal or internal non-commercial use; other re-use needs its permission | no | **yes** |
| OSFI | non-commercial reproduction only; commercial redistribution needs its permission | no | **yes** |
| Canada Gazette (Parts I and II) | Government of Canada terms: non-commercial reproduction | no | **yes** |
| GDELT | free and open for any use, with a citation | yes, with attribution | no |

An internal-only release carries `INTERNAL_ONLY: true` (the publisher's page says why), stays in the organisation's own lake, and
Export data refuses it while `internal-only-data` is off. The links in GDELT's top stories point to third-party articles: Drishti keeps
the address, never the article.

## 7. Changing what is pulled

Every selection is the feed file's `options:`; a change takes effect at the next run (`--set name=value` overrides one for one run).

```yaml
# config/feeds/world-development.yaml: one more indicator (its World Bank code, the profile field it fills, its unit)
options:
  indicators:
    - { code: SH.XPD.CHEX.GD.ZS, field: HEALTH_SPENDING, unit: "% of GDP" }
```

| To | Edit |
|---|---|
| more World Bank indicators or fewer countries | `world-development`: `indicators` (profile fields), `countries` (`all`, or ISO3 codes), `years`, `aggregates`; a series-only indicator the world-economy pack uses too: a `kind: wdi` line in `tools/realdata/sources.yaml` (`series-from`) |
| another FRED or Valet series | `central-banks`: a `series` entry `{source, code, bank, title, unit}`; another bank: a `banks` entry |
| another Statistics Canada vector | `national-statistics`: a `vectors` entry `{vector, title, unit, topic, table-title, frequency}` |
| another OECD measure | `oecd`: a `measures` entry `{id, field, title, unit, frequency, dataflow, key}` (the Data Explorer's Developer API gives the dataflow and the key) |
| another publisher | `official-news`: an `options.sources` entry with its `feed` address, `format` (RSS or Atom; `federal-register` for that API), `type`, `country` and its terms and flags |
| a longer GDELT window | `gdelt-events`: `history-days`, `min-events`, `top-stories` |

A new profile field (`HEALTH_SPENDING`) also belongs in the pack's contract and `config/about.yaml`; `python3 tools/contracts.py check`
says what is missing.

## 8. Tests

All run without the network, on recorded responses (`tools/feeds/tests/fixtures/publicsector/`, recorded on 2026-11-07 for the business
date 2026-11-06 with a narrower selection, `selection.yaml`):

| Test | Checks |
|---|---|
| `tools/feeds/tests/test_publicsector.py` | each feed's documents against their contracts (fields, types, keys, patterns); the files, manifest and the inbox's `.ready`; the second run of the day finds nothing new; the runner and its ledger; no FRED key: the Bank of Canada still loads; one publisher down never fails the others; every source's licence; internal-only releases; the parsers (RSS 2.0, RSS 1.0, Atom, debug text before the XML, SDMX-JSON, GDELT's columns); the samples and READMEs current |
| `tools/test_empty_lakes.py` | `worldlake` and `newslake` hold exactly these packs' tables, only these packs read them, the other named lakes ship empty |
| `EconomyModuleOffTest`, `PublicSectorLensTest` (drishti-banking) | the economy module off: its eight packs, connectors, functions and both lenses gone, banking, capital markets and GLEIF unaffected; on: the six packs, their connectors, functions and the lens there |
| `drishti-console/tests/test_public_sector_browser.py` | the feeds' recorded output loaded into a scratch worldlake and newslake, a real server and console: each screen drawn with its map, chart, table and links |
| `tools/test_readmes.py`, `tools/test_pack_guides.py`, `tools/contracts.py check` | the READMEs, the pack guides and the contracts against the packs and their data |

## 9. Troubleshooting

| You see | Because | Do |
|---|---|---|
| `WDI WDI-CAN` says the entity is not found | the feed has not run, or loaded into another lake | `drishti.py feed status world-development`; run it; check `config/lakes/worldlake.yaml` is the lake the server reads |
| `CBANK CBANK-FED` has no policy rate | the FRED key is not set where the feed runs | set `DRISHTI_FRED_API_KEY`, run `feed run central-banks --since <date>` |
| a publisher shows *failed: kept releases shown* | its feed was unreachable or changed its address | open its `feed:` address; correct it in `config/feeds/official-news.yaml` |
| the GDELT map is empty for a country | fewer than `min-events` events that day | lower `min-events`; a country's events depend on the news |
| `WDI` is unknown on the command line | the module is off, or the packs are not in the server's pack list | Admin → Modules; `DRISHTI_PACKS` |
| `feed run` exits 2 | the feed file is wrong (an unknown key is named) | fix the key it names |

## 10. Later sources

Agreed with the owner, not built yet: the IMF (internal only), the UN SDG and population data, ECB and Bank of England statistics, the BIS,
the BLS, BEA and Census, Eurostat, US Treasury Fiscal Data, USAspending, FDIC and FFIEC, OSFI's returns, the CFPB. Each joins as a feed of
an existing pack (national statistics, central banks) or a new pack of the module, with its licence recorded the same way.
