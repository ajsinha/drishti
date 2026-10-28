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
# External data: where it comes from and how to refresh it

Most of what Drishti ships is **synthetic** test data (fictitious banks, trades and risk, written by generators) or is **computed** by
Drishti's engines when asked (VaR, exposure, groupings, correlations, beta). A few packs carry **external data** instead: published data
fetched from a public source. This page lists every external source of every pack, where it comes from, its licence, whether it may
leave the organisation, the script that refreshes it, what that script writes and where, how to check the result and how to work
offline, with worked examples on Linux and on Windows.

Each pack's own guide says, kind by kind, where its data comes from (its *Data retrieval* section) and gives the data contract of every
kind (*Data contracts*); this page is the one place for the external sources themselves.

!!! warning "Internal use only: IMF data and Yahoo prices"
    Two kinds of external data are for **internal demonstrations only**: the **IMF World Economic Outlook** figures in the
    world-economy pack (the IMF's terms need its permission for commercial reuse) and anything from **Yahoo Finance** (quotes and
    price history: Yahoo's terms forbid redistribution), together with the **Google News** headlines (they belong to their publishers).
    The scripts never write them into a committed folder unless you pass `--internal-ok`. Raise it with the owner before any client
    or external use, or rebuild without them (`world_economy.py refresh --no-imf`; leave the Yahoo connectors off).

## Every external source at a glance

| Pack | `SOURCE` | Where it comes from | Licence | Use |
|---|---|---|---|---|
| world-economy | `worldbank` | World Bank WDI and WGI, `https://api.worldbank.org/v2` | CC BY 4.0 | committable, with attribution |
| world-economy | `imf` | IMF World Economic Outlook, DataMapper, `https://www.imf.org/external/datamapper/api/v1` | IMF terms: reuse with attribution, commercial reuse needs the IMF's permission | IMF data: **internal only** |
| world-economy | `undp` | UNDP Human Development Index, `https://hdr.undp.org/data-center/documentation-and-downloads` | CC BY 3.0 IGO | committable, with attribution |
| world-economy | `factbook` | CIA World Factbook, the factbook.json mirror at a pinned commit, `https://github.com/factbook/factbook.json` | public domain (mirror CC0 1.0) | committable |
| news-and-markets | `quote` | Yahoo Finance quotes and daily history, through the yfinance connector, `https://finance.yahoo.com` | Yahoo terms: no redistribution | Yahoo prices: **internal only** |
| news-and-markets | `news` | Google News RSS headlines, through the news connector, `https://news.google.com/rss` | headlines belong to their publishers | Google News: **internal only** |
| market-data | `ecb-fx` | ECB euro reference FX rates, `https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist.zip` | ECB: free reuse with the source acknowledged | committable, with attribution |
| market-data | `us-treasury` | US Treasury daily par yield curve, `https://home.treasury.gov/resource-center/data-chart-center/interest-rates` | public domain (US Government work) | committable |
| market-data | `sofr` | SOFR, Federal Reserve Bank of New York, `https://markets.newyorkfed.org/api/rates/secured/sofr` | NY Fed terms of use: reuse with attribution | committable, with attribution |
| market-data | `estr` | Euro short-term rate, ECB, `https://data-api.ecb.europa.eu/service/data/EST` | ECB: free reuse with the source acknowledged | committable, with attribution |
| us-equities | `wikidata` | Wikidata: company facts (headquarters, founded, website, LEI) and the date a member joined its index, `https://query.wikidata.org/sparql` | CC0 1.0 | committable |
| us-equities | `trackers` | SEC Form N-PORT of SPY, QQQ and DIA: the S&P 500, Nasdaq-100 and Dow members (read from the funds cache, no download) | public domain (US Government work) | committable |
| us-equities | `nasdaq` | Nasdaq Trader symbol directory, `https://www.nasdaqtrader.com/dynamic/SymDir/` | public symbol directory files | committable |
| us-equities | `sec` | SEC EDGAR: tickers, submissions, XBRL company facts, `https://data.sec.gov` | public domain (US Government work); EDGAR fair access policy | committable |
| us-equities | `gleif` | GLEIF LEI records, `https://api.gleif.org/api/v1` | CC0 1.0 | committable |
| us-equities | `prices` | Yahoo Finance daily prices, through the us-equities-prices connector | Yahoo terms: no redistribution | Yahoo prices: **internal only** |
| gleif | `golden` | GLEIF golden copy, level 2: relationship records and reporting exceptions, `https://goldencopy.gleif.org` | CC0 1.0 (attribution appreciated) | committable |
| gleif | `api` | GLEIF LEI API: LEI records, legal forms, countries, LEI issuers, `https://api.gleif.org/api/v1` | CC0 1.0 | committable |
| gleif | `mapping` | GLEIF mapping files: BIC, ISIN, MIC, OpenCorporates to LEI, `https://mapping.gleif.org` | CC0 1.0 | committable |
| gleif | `golden-lei` | GLEIF golden copy, level 1 bulk file, for full-country pulls (never by default) | CC0 1.0 | data folder only (its size, not its licence) |
| funds | `tickers` | SEC ticker files: fund share classes and listed trusts, `https://www.sec.gov/files/company_tickers_mf.json`, `company_tickers_exchange.json` | public domain (US Government work) | committable |
| funds | `nasdaq` | Nasdaq Trader symbol directory (which tickers are ETFs), `https://www.nasdaqtrader.com/dynamic/SymDir/` | public symbol directory files | committable |
| funds | `nport` | SEC Form N-PORT data sets (holdings, derivatives, counterparties, flows, monthly returns; about 450 MB a quarter), `https://www.sec.gov/data-research/sec-markets-data/form-n-port-data-sets` | public domain (US Government work); SEC fair access policy | committable (the downloads stay in the data folder) |
| funds | `ncen` | SEC Form N-CEN data sets (ETF facts, service providers, authorised participants), `https://www.sec.gov/data-research/sec-markets-data/form-n-cen-data-sets` | public domain (US Government work) | committable |
| funds | `rr` | SEC mutual fund prospectus risk/return summary data sets (expense ratio, turnover, calendar-year returns), `https://www.sec.gov/data-research/sec-markets-data/mutual-fund-prospectus-riskreturn-summary-data-sets` | public domain (US Government work) | committable |
| funds | `gleif` | GLEIF golden copy level 2: fund relationships (managed by, sub-fund of, feeder to), `https://goldencopy.gleif.org` | CC0 1.0 | committable |

A new pack built on external data adds its rows here and a section of its own below (the funds pack, SEC N-PORT and N-CEN, came
this way). The market-data pack's committed samples and test data are synthetic: its official sources are optional and
always go to the data folder. Every other pack (core, trading, market-risk, counterparty-risk, compute-demo) holds synthetic or
computed data only, and has no external source.

## The scripts

Each pack built on external data has **its own refresh script** in `tools/publicdata/`, with a shell wrapper beside it and a
PowerShell wrapper in `tools/windows/`. There is no single script for every pack, on purpose: each pack's sources, licences and
output are its own.

| Pack | Script | Linux and macOS | Windows |
|---|---|---|---|
| `world-economy` | `tools/publicdata/world_economy.py` | `tools/publicdata/world-economy.sh` | `tools\windows\publicdata-world-economy.ps1` |
| `news-and-markets` | `tools/publicdata/news_and_markets.py` | `tools/publicdata/news-and-markets.sh` | `tools\windows\publicdata-news-and-markets.ps1` |
| `market-data` | `tools/publicdata/official_market_data.py` | `tools/publicdata/market-data.sh` | `tools\windows\publicdata-market-data.ps1` |
| `us-equities` | `tools/publicdata/us_equities.py` | `tools/publicdata/us-equities.sh` | `tools\windows\publicdata-us-equities.ps1` |
| `gleif` | `tools/publicdata/gleif.py` | `tools/publicdata/gleif.sh` | `tools\windows\publicdata-gleif.ps1` |
| `funds` | `tools/publicdata/funds.py` | `tools/publicdata/funds.sh` | `tools\windows\publicdata-funds.ps1` |

Every script has the same commands and the same options. `--help` on the script, on `status` and on `refresh` prints its worked
examples and its wrappers:

```bash
python3 tools/publicdata/<script>.py status                 # each source: licence, use, last refreshed, cached size (offline)
python3 tools/publicdata/<script>.py refresh --dry-run      # what would be fetched and written; changes nothing
python3 tools/publicdata/<script>.py refresh [SOURCE ...]   # fetch, normalise, write, then the pack's own check
python3 tools/publicdata/<script>.py refresh --help         # the options, the sources, what --scope takes, the examples
```

| Option | Meaning |
|---|---|
| `SOURCE ...` | only these sources of the pack (default: all of them except those marked "never by default") |
| `--years N` / `--since YEAR` | how much history: the last N years, or from YEAR |
| `--scope A,B` | widen the source: more indicators, tickers, pairs (each source says what, in `refresh --help`) |
| `--out DIR` | where to write (default: below) |
| `--internal-ok` | allow internal-only data into a committed folder of the repository (internal demos only) |
| `--cache DIR` | the download cache (default `<data>/publicdata/cache/<pack>`) |
| `--max-age HOURS` | reuse a cached download younger than this (default 24); older files are fetched again |
| `--offline` | use the cache only, fetch nothing (a rebuild on a machine without internet) |
| `--pause S` | seconds between requests (default 0.4) |
| `--no-check` | skip the pack's check after writing |

`<data>` is the configurable data folder: `DRISHTI_DATA_DIR`, else `data/` in the Drishti folder (git-ignored). Exit codes: 0 done,
2 done with some downloads failed or a check problem, 1 nothing written, 3 refused (the reason is printed in words).

The scripts are plain Python (the standard library and PyYAML, which the console's environment has); no other program is
downloaded or run. The wrappers use the console's environment (`drishti-console/.venv`, made by `tools/windows/start-console.ps1` on
Windows), else `python3` (`DRISHTI_PYTHON` or `python` on Windows), and pass every argument on unchanged.

## Worked examples

The output below is real, from a fresh clone (the paths are shortened to `~/drishti`). `status` and `refresh --dry-run` change
nothing and need no network: run them first, always.

### status: what the pack holds, offline

```console
$ tools/publicdata/world-economy.sh status
SOURCE     LICENCE                                           USE            LAST REFRESHED  CACHED
worldbank  CC BY 4.0                                         committable    never           -
imf        IMF terms: attribution; no commercial reuse with  internal only  never           -
undp       CC BY 3.0 IGO                                     committable    never           -
factbook   Public domain; the factbook.json mirror is CC0 1  committable    never           -

pack world-economy: writes the committed dataset and pack with --internal-ok; else <data>/publicdata/world-economy
cache ~/drishti/data/publicdata/cache/world-economy
manifest ~/drishti/data/publicdata/world-economy/manifest.json
internal only: imf: the IMF's terms allow reuse with attribution, but commercial reuse needs the IMF's permission; the owner kept it for internal demos only (2026-10-10)
```

```console
$ python3 tools/publicdata/news_and_markets.py status
committed snapshot quote: snapshot-2026-10-18.jsonl.gz, 20 documents, 130.6 KB
committed snapshot news: snapshot-2026-10-18.jsonl.gz, 71 documents, 141.4 KB
SOURCE  LICENCE                                           USE            LAST REFRESHED  CACHED
quote   Yahoo terms of service: personal use, no redistr  internal only  never           -
news    Headlines and links belong to their publishers    internal only  never           -

pack news-and-markets: writes data/testdata with --internal-ok; else <data>/publicdata/news-and-markets/testdata
cache ~/drishti/data/publicdata/cache/news-and-markets
manifest ~/drishti/data/publicdata/news-and-markets/manifest.json
internal only: quote: Yahoo's terms forbid redistributing its prices; kept as a dated demo snapshot only
internal only: news: the headlines belong to their publishers; shown for demonstration only
```

```console
$ python3 tools/publicdata/official_market_data.py status
SOURCE       LICENCE                                       USE          LAST REFRESHED  CACHED
ecb-fx       ECB: free reuse with the source acknowledged  committable  never           -
us-treasury  Public domain (US Government work)            committable  never           -
sofr         NY Fed terms of use: reuse with attribution   committable  never           -
estr         ECB: free reuse with the source acknowledged  committable  never           -

pack market-data: writes <data>/publicdata/market-data (fx-spot.jsonl, ir-curve.jsonl, rate-fixing.jsonl)
cache ~/drishti/data/publicdata/cache/market-data
manifest ~/drishti/data/publicdata/market-data/manifest.json
```

```console
$ python3 tools/publicdata/us_equities.py status
committed pack: 586 companies, data retrieved 2026-10-24
SOURCE    LICENCE                                           USE            LAST REFRESHED  CACHED
wikidata  CC0 1.0                                           committable    never           -
trackers  Public domain (U.S. Government work)              committable    never           -
nasdaq    Public symbol directory files (Nasdaq Trader)     committable    never           -
sec       Public domain (U.S. Government work); EDGAR fair  committable    never           -
gleif     CC0 1.0                                           committable    never           -
prices    Yahoo terms of service: personal use, no redistr  internal only  never           -

pack us-equities: writes config/packs/us-equities; a widened build <data>/publicdata/us-equities/packs/us-equities; prices <data>/publicdata/us-equities/prices
cache ~/drishti/data/publicdata/cache/us-equities
manifest ~/drishti/data/publicdata/us-equities/manifest.json
internal only: prices: Yahoo's terms forbid redistributing its prices; the pack ships none and keeps the connector off (internal demos only)
```

```console
$ python3 tools/publicdata/gleif.py status
committed pack: 9937 entities, golden copy of 2026-10-25
SOURCE      LICENCE                                           USE          LAST REFRESHED  CACHED
golden      CC0 1.0 (attribution appreciated)                 committable  never           -
api         CC0 1.0 (attribution appreciated)                 committable  never           -
mapping     CC0 1.0 (the BIC file with SWIFT, the ISIN file   committable  never           -
golden-lei  CC0 1.0 (attribution appreciated)                 committable  never           -

pack gleif: writes config/packs/gleif; a widened build <data>/publicdata/gleif/packs/gleif
cache ~/drishti/data/publicdata/cache/gleif
manifest ~/drishti/data/publicdata/gleif/manifest.json
```

"LAST REFRESHED never" and "CACHED -" mean this machine has not refreshed yet: the committed data (the first lines, where a pack
has them) is what the pack serves.

### refresh --dry-run: what a refresh would do, changing nothing

```console
$ python3 tools/publicdata/world_economy.py refresh --dry-run
note: the build carries IMF data (the IMF's terms allow reuse with attribution, but commercial reuse needs the IMF's permission; the owner kept it for internal demos only (2026-10-10)), so it goes to the data folder; --internal-ok refreshes the committed pack
dry run: world-economy, sources worldbank, imf, undp, factbook, years 2015-2025
  worldbank (CC BY 4.0; committable): 51 request(s), cache ~/drishti/data/publicdata/cache/world-economy (empty)
    https://api.worldbank.org/v2/country?format=json&per_page=400
    https://api.worldbank.org/v2/country/all/indicator/NY.GDP.MKTP.CD?format=json&date=2015:2025&per_page=20000
    https://api.worldbank.org/v2/country/all/indicator/NY.GDP.PCAP.CD?format=json&date=2015:2025&per_page=20000
  imf (IMF terms: attribution; no commercial reuse without permission; internal only): 9 request(s), cache ~/drishti/data/publicdata/cache/world-economy
    https://www.imf.org/external/datamapper/api/v1/indicators
    https://www.imf.org/external/datamapper/api/v1/BCA_NGDPD
    https://www.imf.org/external/datamapper/api/v1/GGXCNL_NGDP
  undp (CC BY 3.0 IGO; committable): 1 request(s), cache ~/drishti/data/publicdata/cache/world-economy (empty)
    https://hdr.undp.org/sites/default/files/2025_HDR/HDR25_Composite_indices_complete_time_series.csv
  factbook (Public domain; the factbook.json mirror is CC0 1.0; committable): 3 request(s), cache ~/drishti/data/publicdata/cache/world-economy (empty)
    https://api.github.com/repos/factbook/factbook.json/commits/master
    https://api.github.com/repos/factbook/factbook.json/git/trees/<sha>?recursive=1
    https://raw.githubusercontent.com/factbook/factbook.json/<sha>/<region>/<gec>.json (about 260 files)
  then: normalise -> ~/drishti/data/publicdata/world-economy; run the pack generator; then its --check
```

```console
$ python3 tools/publicdata/official_market_data.py refresh --dry-run
dry run: market-data -> ~/drishti/data/publicdata/market-data
  ecb-fx (ECB: free reuse with the source acknowledged): fx-spot.jsonl from 2025-10-26; https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist.zip
  us-treasury (Public domain (US Government work)): ir-curve.jsonl from 2025-10-26; https://home.treasury.gov/resource-center/data-chart-center/interest-rates/daily-treasury-rates.csv/2025/all?type=daily_treasury_yield_curve
  sofr (NY Fed terms of use: reuse with attribution): rate-fixing.jsonl from 2025-10-26; https://markets.newyorkfed.org/api/rates/secured/sofr/search.json?startDate=2025-10-26&endDate=2026-10-26
  estr (ECB: free reuse with the source acknowledged): rate-fixing.jsonl from 2025-10-26; https://data-api.ecb.europa.eu/service/data/EST/B.EU000A2X2A25.WT?startPeriod=2025-10-26&format=csvdata
```

```console
$ python3 tools/publicdata/us_equities.py refresh --dry-run
dry run: us-equities, sources wikidata, nasdaq, sec, gleif
  wikidata (CC0 1.0; committable): 4 request(s), cache ~/drishti/data/publicdata/cache/us-equities
    https://query.wikidata.org/sparql (current members of DJIA, Q180816)
    https://query.wikidata.org/sparql (current members of NDX, Q507306)
    https://query.wikidata.org/sparql (current members of SP500, Q242345)
  nasdaq (Public symbol directory files (Nasdaq Trader); committable): 2 request(s), cache ~/drishti/data/publicdata/cache/us-equities
    https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt
    https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt
  sec (Public domain (U.S. Government work); EDGAR fair access policy; committable): 3 request(s), cache ~/drishti/data/publicdata/cache/us-equities
    https://www.sec.gov/files/company_tickers_exchange.json
    https://data.sec.gov/submissions/CIK##########.json (about 500 companies, 20 filings each)
    https://data.sec.gov/api/xbrl/companyfacts/CIK##########.json (9 concepts, 10 years)
  gleif (CC0 1.0; committable): 1 request(s), cache ~/drishti/data/publicdata/cache/us-equities
    https://api.gleif.org/api/v1/lei-records/<LEI> (or ?filter[entity.legalName]=<name>), one a company
  then: make.py -> the committed pack (config/packs/us-equities); then make.py --check
```

```console
$ python3 tools/publicdata/gleif.py refresh --dry-run
dry run: gleif, sources golden, api, mapping
  golden (CC0 1.0 (attribution appreciated); committable): 3 request(s) or file(s), cache ~/drishti/data/publicdata/cache/gleif
    https://goldencopy.gleif.org/api/v2/golden-copies/publishes/latest
    rr full file, csv (about 24 MB zipped)
    repex full file, csv (about 62 MB zipped)
  api (CC0 1.0 (attribution appreciated); committable): 2 request(s) or file(s), cache ~/drishti/data/publicdata/cache/gleif
    https://api.gleif.org/api/v1/lei-records?filter[lei]=<200 LEIs> (one request per 200 entities; the count is known once the golden copy is reduced)
    https://api.gleif.org/api/v1/entity-legal-forms, /countries, /lei-issuers (paged, about 20 requests)
  mapping (CC0 1.0 (the BIC file with SWIFT, the ISIN file with ANNA); committable): 4 request(s) or file(s), cache ~/drishti/data/publicdata/cache/gleif
    https://mapping.gleif.org/api/v2/bic-lei/latest
    https://mapping.gleif.org/api/v2/isin-lei/latest
    https://mapping.gleif.org/api/v2/mic-lei/latest
    https://mapping.gleif.org/api/v2/oc-lei/latest
  then: make.py -> the committed pack (config/packs/gleif); then make.py --check
```

The news-and-markets dry run lists the twenty `QUOTE-` ids and the 71 `NEWS-` ids it would capture (91 calls, at least 2 seconds apart),
and says that the snapshot goes to `<data>/publicdata/news-and-markets/testdata` unless you pass `--internal-ok`.

### refresh SOURCE: one source at a time

```bash
python3 tools/publicdata/official_market_data.py refresh estr               # the euro short-term rate only: one request, a few seconds
python3 tools/publicdata/world_economy.py refresh worldbank undp            # the openly licensed sources only, into the data folder
python3 tools/publicdata/us_equities.py refresh sec --offline               # rebuild the SEC part from the download cache
python3 tools/publicdata/gleif.py refresh mapping                           # the BIC, ISIN, MIC and OpenCorporates files again
```

The habit: `status`, then `refresh --dry-run`, then `refresh` with the sources you need.

A refresh prints each step (fetch, normalise, write, check) and ends with what it wrote; its manifest
(`<data>/publicdata/<pack>/manifest.json`) keeps the last 20 runs, and `status` then shows `LAST REFRESHED` and `CACHED`.

### On Windows

The same commands through the PowerShell wrappers, from the Drishti folder; the output is the same, with Windows paths:

```powershell
.\tools\windows\publicdata-world-economy.ps1 status
.\tools\windows\publicdata-world-economy.ps1 refresh --dry-run
.\tools\windows\publicdata-market-data.ps1 refresh estr
.\tools\windows\publicdata-market-data.ps1 refresh --years 2
.\tools\windows\publicdata-news-and-markets.ps1 refresh --server http://localhost:18080
.\tools\windows\publicdata-us-equities.ps1 refresh --dry-run
.\tools\windows\publicdata-us-equities.ps1 refresh --scope TSLA,FILINGS:40
.\tools\windows\publicdata-gleif.ps1 status
.\tools\windows\publicdata-gleif.ps1 refresh --scope NAME:allianz,CAP:200
```

Set `$env:DRISHTI_DATA_DIR` to put the cache and the builds elsewhere. `Get-Help .\tools\windows\publicdata-gleif.ps1 -Examples`
shows each wrapper's examples. Nothing but Python is needed: no `.exe` is downloaded or run.

## The internal-only rule

A source whose terms do not allow redistribution is never written into a committed folder of the repository unless you pass
`--internal-ok`; without it the script either writes to the data folder (when you did not give `--out`, and it says so) or refuses
(when `--out` names a committed folder), and explains why in words:

```text
refused: .../config/packs/world-economy is a committed folder of the repository, and these sources may not be redistributed:
  - IMF World Economic Outlook (DataMapper): the IMF's terms allow reuse with attribution, but commercial reuse needs the IMF's permission; ...
Write them to the data folder instead (leave out --out, or point it outside the repository), or pass --internal-ok
if the result stays inside your organisation (internal demos only).
```

The committed world-economy pack carries the IMF forecasts, and the committed news-and-markets snapshot carries Yahoo and Google
News data: both were the owner's choice for internal demos ([the world-economy data in detail](#the-world-economy-data-in-detail), `data/testdata/README.md`). The
us-equities pack ships **no** prices: its Yahoo price connector is off as shipped. Before any external or client use, rebuild without
them (`world_economy.py refresh --no-imf`) or get permission.

## How external data reaches the server

1. **The refresh script** fetches into its download cache and writes the pack's data: for us-equities and gleif one gzipped JSON
   Lines snapshot per kind, `data/testdata/<pack>/<kind>/snapshot-<date>.jsonl.gz`, listed in `data/testdata/testdata.yaml`; for
   world-economy the normalised dataset (`tools/realdata/dataset/`) and the pack's samples; for news-and-markets the snapshot in
   `data/testdata/news-and-markets/`; for market-data JSON Lines files in the data folder.
2. **The lake.** `python3 tools/drishti.py lake demo` (or `python3 tools/build_demo_lakes.py`) reads `data/testdata/testdata.yaml`
   and writes every dataset into `<data>/delta/<domain>/<kind>/`. The market-data files go in with `tools/ingest_jsonl.py` (below).
3. **The server** reads each kind through the pack's Delta connector (`us-equities-store`, `gleif-store`, `macro-store`,
   `civic-store`, `news-store`, `market-store`), never through the demo connector. Restart it, or let it pick the tables up at its
   next refresh.

**Checking.** The script runs the pack's own check after writing (`make.py --check` for us-equities and gleif, the pack generator's
`--check` for world-economy, the snapshot read back for news-and-markets, the document checks for market-data). Then
`python3 tools/contracts.py check` compares every kind's data contract with the data, and in the console `USEQ <GO>`, `LEI <GO>`,
`ECON <GO>`, `QUOTE <GO>` or `FX <GO>` lists the kind.

**Offline.** Everything committed works without a network: the demo machine never fetches. A refresh on a machine without internet
uses the download cache only (`--offline`; copy the cache folder from a machine that has one). The live connectors that reach the
internet (news, yfinance, us-equities-prices, the public-data feeds) are off as shipped and never start on a machine configured
offline (`drishti.offline.plugins`).

## world-economy

**Sources:** `worldbank` (World Bank WDI and WGI, CC BY 4.0), `imf` (IMF WEO, **internal only**), `undp` (UNDP HDI, CC BY 3.0 IGO),
`factbook` (CIA World Factbook, public domain). The regional figures of `region-economy` are typed into the generator by hand from US
Census Bureau and BEA figures (illustrative, rounded): there is no fetch for them.

`tools/publicdata/world_economy.py` wraps `tools/realdata/` (see [the world-economy data in detail](#the-world-economy-data-in-detail)): the fetcher's downloads go
through the shared client into the same cache layout, then the normaliser, the pack generator
(`tools/packgen/world/make.py`) and its `--check` run as `realdata.py all` would.

```bash
python3 tools/publicdata/world_economy.py status
python3 tools/publicdata/world_economy.py refresh --dry-run
python3 tools/publicdata/world_economy.py refresh                    # a full build in <data>/publicdata/world-economy
python3 tools/publicdata/world_economy.py refresh --internal-ok      # refresh the committed dataset, pack, glossary, tests and guide tables
python3 tools/publicdata/world_economy.py refresh --no-imf           # no IMF data at all; always the data folder
python3 tools/publicdata/world_economy.py refresh --internal-ok --offline --cache ~/.cache/drishti-realdata   # rebuild from an older realdata cache
```

Where it writes:

- With `--internal-ok` (and no widening): the committed `tools/realdata/dataset/`, `config/packs/world-economy/`,
  `tools/packgen/world/about.yaml` and the generated tables of this page's world-economy section (`tools/realdata/realdata.py docs`). Commit the result.
- Otherwise: `<data>/publicdata/world-economy/` with `sources.yaml` (what this build used), `dataset/`, `packs/world-economy/`
  and `about/`. Serve that pack with `DRISHTI_PACKS_DIR` or copy what you need.

**Widening.** `--since 2000` or `--years 25` fetches a longer window; `--scope` adds indicators:

```bash
python3 tools/publicdata/world_economy.py refresh worldbank --since 2000 --scope SP.DYN.LE00.IN:social,EN.GHG.ALL.PC.CE.AR5
python3 tools/publicdata/world_economy.py refresh imf --scope GGXWDG_NGDP:fiscal
```

A scope item is a World Bank indicator code (it has dots) or an IMF WEO code, optionally `:group` (economy, labour, fiscal,
social, governance; default economy). The name, unit and definition come from the source's own metadata. A widened build
always goes to the data folder. To widen the committed pack, add the indicator to `tools/realdata/sources.yaml` (one line, with
its name, unit and definition) and run `refresh --internal-ok`.

**Check it:** the refresh runs `tools/packgen/world/make.py --check`; `ECON IND <GO>` opens India's economy, `FCST <GO>` the IMF
forecasts. **Offline:** `refresh --offline` rebuilds from the cache.

### The world-economy data in detail

(This was REAL_DATA.md.) The `world-economy` pack carries **published public data**, not samples: about 213 economies and 216 countries from the World Bank, the IMF, UNDP and
the CIA World Factbook. Every number is a published value (a percentage stays a percentage, as the source states it) and every text is as the source publishes it (HTML
removed). It is not marked `sample: true`. (It was two packs, `economics` and `politics-society`, until they were merged into `world-economy`;
the two halves keep their names below.)

The data is fetched and normalised by a repeatable tool, `tools/realdata/`, and committed to git as a small normalised dataset (`tools/realdata/dataset/`, 2.4 MB) plus the
generated pack documents. The raw downloads are cached outside the repository and are never committed.

> **Licence note: IMF data is for internal demonstration only.** The IMF World Economic Outlook data in this pack (the `economic-forecast` documents, and the
> `debt` and `net_lending` series that feed each economy's debt ratio and fiscal position) is kept for **internal demos only**. The IMF's terms allow reuse with
> attribution but require its **permission for commercial use** (copyright@imf.org). Before the pack is shown to a client, shipped, sold or used commercially,
> either obtain that permission or build without the IMF: `python3 tools/realdata/realdata.py write --no-imf` (see [Building without the IMF](#building-without-the-imf)).
> The World Bank (CC BY 4.0), UNDP (CC BY 3.0 IGO) and CIA Factbook (public domain) data needs only attribution.

![The economy of India](img/realdata/economy-india.jpg)

#### Sources, licences and attribution

<!-- realdata:begin sources -->
| Source | Dataset and vintage | Licence | Terms |
|---|---|---|---|
| World Bank, World Development Indicators | World Development Indicators (updated 2026-10-08) | CC BY 4.0 | https://datacatalog.worldbank.org/public-licenses#cc-by |
| World Bank, Worldwide Governance Indicators | Worldwide Governance Indicators (updated 2026-09-25) | CC BY 4.0 | https://datacatalog.worldbank.org/public-licenses#cc-by |
| International Monetary Fund, World Economic Outlook (DataMapper) | World Economic Outlook (April 2026 edition) | IMF Copyright and Terms of Use: reuse with attribution; commercial reuse needs the IMF's permission | https://www.imf.org/en/About/copyright-and-terms |
| UNDP, Human Development Report: composite indices time series | Human Development Index time series (2025 report) | CC BY 3.0 IGO | https://creativecommons.org/licenses/by/3.0/igo/ |
| CIA, The World Factbook (factbook.json mirror, CC0) | The World Factbook (mirror commit 144d6977b2) | Public domain (U.S. Government work); the factbook.json mirror is CC0 1.0 | https://github.com/factbook/factbook.json/blob/master/LICENSE.md |
<!-- realdata:end sources -->

Attribution text (also in [THIRD-PARTY-NOTICES.md](../../THIRD-PARTY-NOTICES.md), and in each document's *Data source and licence* panel):

- The World Bank: World Development Indicators (CC BY 4.0). The World Bank: Worldwide Governance Indicators (CC BY 4.0).
- Source: International Monetary Fund, World Economic Outlook database, https://www.imf.org/external/datamapper. The packs transform the data (units, rounding to six
  significant digits, joins with other sources); the IMF's terms ask that material changes be stated, which this guide and the panel do.
- UNDP, Human Development Report 2025 data (CC BY 3.0 IGO).
- Central Intelligence Agency, The World Factbook (public domain). The Factbook is read through the CC0 `factbook.json` mirror, pinned to a commit (recorded in each
  document's dataset line), because the CIA site offers no stable download of the whole book.

Caveats to read before reusing the data. **IMF:** the terms allow downloading, copying and derivative works with attribution, but ask for permission before *commercial*
reuse and prohibit bulk automated downloads without permission; the tool makes one request per series (eight in all) with a pause, and the data is shown here for a
demonstration. Ask copyright@imf.org before any commercial use. **UNDP:** the HDR data page states CC BY 3.0 IGO; check the page before redistribution. **World Bank:** each
indicator also has its own upstream source (for example SIPRI for military spending, the Inter-Parliamentary Union for women in parliament, ILO for labour data) that WDI
credits; the *About this series* panel shows the World Bank code to look it up.

#### What is in the pack

| Half of world-economy | Kind | Mnemonic | Documents | One document is |
|---|---|---|---|---|
| economics | `economy` | `ECON` | 213 | a country: GDP, growth, inflation, unemployment, debt, current account, sectors, links to its region, income group, labour market, fiscal position, IMF forecast and 16 indicators |
| economics | `macro-indicator` | `MACRO` | 3,119 | one headline statistic of one country: history 2015 to the latest year, rank among countries, definition |
| economics | `economic-forecast` | `FCST` | 192 | the IMF WEO series 2015 to 2031 (growth, inflation, unemployment, current account, debt, net lending, GDP), outturn and projections |
| economics | `labour-market` | `LABR` | 185 | unemployment, participation, youth and female labour, jobs and output by sector |
| economics | `fiscal-position` | `FISC` | 192 | IMF debt and net lending, World Bank revenue, expense, military spending |
| economics | `country-group` | `ECG` | 12 | a World Bank region, income group or the world, computed from its member economies when asked (since 2.9.2, [GROUPINGS_ANALYTICS_GUIDE.md](GROUPINGS_ANALYTICS_GUIDE.md)): output, growth, inflation and its largest economies |
| economics | `region-economy` | `REGE` | 59 | a state or territory (ISO 3166-2, `AU-NSW`, `US-CA`) with population and GDP: an **illustrative sample, rounded from public figures, not for reporting** (`tools/realdata/dataset/regions.json`, not fetched), behind the BI sample report *Region economics* |
| politics | `jurisdiction` | `JUR` | 216 | a country: government type, capital, legislature seats by party, indicators, links to office holders, elections, parties and region |
| politics | `office-holder` | `OFFC` | 430 | the chief of state and head of government as the Factbook publishes them |
| politics | `election` | `ELEC` | 378 | the most recent lower-chamber, upper-chamber and executive election: system, term, seats by party, next election |
| politics | `party` | `PARTY` | 282 | a party that won seats in the lower chamber at the most recent election, with the chamber's full result |
| politics | `region` | `REGN` | 7 | a World Bank region: countries, population, life expectancy, government types |
| politics | `social-indicator` | `SOCI` | 4,297 | one social or governance statistic of one country with a regional comparison |

Removed because no open, global dataset backs them: central-bank decisions, trade flows (bilateral), price baskets, opinion polls, bills and candidates. The old
fictional polities (Valdoria, Kestria) and the invented economies are gone.

Identifiers use the ISO 3166 alpha-3 code: `ECON-IND`, `MACRO-BRA-GDP-GROWTH`, `FCST-JPN-WEO`, `LABR-NGA`, `FISC-USA`, `ECG-SAS`, `JUR-USA`, `OFFC-JPN-HOG`,
`ELEC-BRA-LOW`, `PARTY-IND-01`, `REGN-SSF`, `SOCI-NGA-WGI-LAW`. The link graph is: country ↔ region ↔ income group ↔ indicators ↔ forecast, labour and fiscal documents, and
(politics) jurisdiction ↔ region, office holders, elections, parties, indicators.

![A jurisdiction: India](img/realdata/jurisdiction-india.jpg)

##### Provenance in every document

Each document has a `provenance` object (shown by the *Data source and licence* panel) and the same four facts in `_meta`:

<!-- realdata:begin provenance -->
```json
"provenance": {"source": "International Monetary Fund, World Economic Outlook (DataMapper)", "dataset": "World Economic Outlook (April 2026 edition)",
  "retrievedAt": "2026-10-10", "licence": "IMF Copyright and Terms of Use: reuse with attribution; commercial reuse needs the IMF's permission",
  "attribution": "Source: International Monetary Fund, World Economic Outlook database, https://www.imf.org/external/datamapper.",
  "url": "https://www.imf.org/external/datamapper"}
```
<!-- realdata:end provenance -->

![An IMF forecast with its provenance](img/realdata/forecast-japan.jpg)

#### Coverage

Years: World Bank and UNDP series 2015 to 2025 (a series shows the years the source has; the latest year differs by country and indicator), IMF series 2015 to 2031.
Countries are the 217 economies the World Bank lists as countries or territories (Channel Islands has no Factbook profile, so 216 jurisdictions and 213 economies with
output data). Examples of how many countries have data: GDP 213, real growth 212, inflation 190, unemployment 187, government debt (IMF) 190, Gini 151, HDI 193, rule of
law 207, CO2 per capita 203, reserves 177. A missing value is simply absent from the series: nothing is estimated or filled in. Rankings count only countries whose latest
value is from 2022 or later.

Size: the world-economy pack is about 19 MB (its economics half 6.9 MB, its politics half 12.1 MB) and the normalised dataset 2.4 MB (21.4 MB of text in all, under the 25 MB limit; git compresses it to
about a fifth). Sample documents are written without indentation (`compact_json` in the pack generator) to keep it small.

##### More countries and groups

The same Sutras serve every country: the United States and Nigeria below, Brazil's government, and the World Bank's South Asia group with its largest economies.

![The economy of the United States](img/realdata/economy-usa.jpg)
![The economy of Nigeria](img/realdata/economy-nigeria.jpg)
![A jurisdiction: Brazil](img/realdata/jurisdiction-brazil.jpg)
![A World Bank region as a country group](img/realdata/group-south-asia.jpg)

#### Indicators

##### Economy, labour and fiscal (world-economy pack, economics half)

<!-- realdata:begin indicators-economics -->
| Key | Indicator | Unit | Source and code | Countries with data |
|---|---|---|---|---|
| `gdp` | GDP, current prices | USD bn | World Bank WDI `NY.GDP.MKTP.CD` | 213 |
| `gdp_pc` | GDP per capita, current prices | USD | World Bank WDI `NY.GDP.PCAP.CD` | 213 |
| `gdp_growth` | Real GDP growth | % a year | World Bank WDI `NY.GDP.MKTP.KD.ZG` | 212 |
| `inflation` | Inflation, consumer prices | % a year | World Bank WDI `FP.CPI.TOTL.ZG` | 190 |
| `unemployment` | Unemployment rate | % of labour force | World Bank WDI `SL.UEM.TOTL.ZS` | 187 |
| `population` | Population | millions | World Bank WDI `SP.POP.TOTL` | 217 |
| `current_account` | Current account balance | % of GDP | World Bank WDI `BN.CAB.XOKA.GD.ZS` | 192 |
| `reserves` | Total reserves incl. gold | USD bn | World Bank WDI `FI.RES.TOTL.CD` | 177 |
| `trade` | Trade | % of GDP | World Bank WDI `NE.TRD.GNFS.ZS` | 188 |
| `exports` | Exports of goods and services | % of GDP | World Bank WDI `NE.EXP.GNFS.ZS` | 188 |
| `imports` | Imports of goods and services | % of GDP | World Bank WDI `NE.IMP.GNFS.ZS` | 188 |
| `fdi` | Foreign direct investment, net inflows | % of GDP | World Bank WDI `BX.KLT.DINV.WD.GD.ZS` | 200 |
| `co2` | CO2 emissions per capita | t CO2e | World Bank WDI `EN.GHG.CO2.PC.CE.AR5` | 203 |
| `real_interest` | Real interest rate | % a year | World Bank WDI `FR.INR.RINR` | 136 |
| `agriculture` | Agriculture, value added | % of GDP | World Bank WDI `NV.AGR.TOTL.ZS` | 202 |
| `industry` | Industry, value added | % of GDP | World Bank WDI `NV.IND.TOTL.ZS` | 205 |
| `services` | Services, value added | % of GDP | World Bank WDI `NV.SRV.TOTL.ZS` | 204 |
| `lfp` | Labour force participation rate | % of population 15+ | World Bank WDI `SL.TLF.CACT.ZS` | 187 |
| `youth_unemployment` | Youth unemployment rate | % of labour force 15-24 | World Bank WDI `SL.UEM.1524.ZS` | 187 |
| `employment_ratio` | Employment to population ratio | % of population 15+ | World Bank WDI `SL.EMP.TOTL.SP.ZS` | 187 |
| `female_lfp` | Female labour force participation rate | % of women 15+ | World Bank WDI `SL.TLF.CACT.FE.ZS` | 187 |
| `vulnerable` | Vulnerable employment | % of employment | World Bank WDI `SL.EMP.VULN.ZS` | 187 |
| `emp_agriculture` | Employment in agriculture | % of employment | World Bank WDI `SL.AGR.EMPL.ZS` | 187 |
| `emp_industry` | Employment in industry | % of employment | World Bank WDI `SL.IND.EMPL.ZS` | 187 |
| `emp_services` | Employment in services | % of employment | World Bank WDI `SL.SRV.EMPL.ZS` | 187 |
| `debt` | General government gross debt | % of GDP | IMF WEO `GGXWDG_NGDP` | 190 |
| `net_lending` | General government net lending/borrowing | % of GDP | IMF WEO `GGXCNL_NGDP` | 193 |
| `tax_revenue` | Tax revenue | % of GDP | World Bank WDI `GC.TAX.TOTL.GD.ZS` | 150 |
| `revenue` | Revenue, excluding grants | % of GDP | World Bank WDI `GC.REV.XGRT.GD.ZS` | 150 |
| `expense` | Expense | % of GDP | World Bank WDI `GC.XPN.TOTL.GD.ZS` | 150 |
| `military` | Military expenditure | % of GDP | World Bank WDI `MS.MIL.XPND.GD.ZS` | 159 |
<!-- realdata:end indicators-economics -->

##### Society and governance (world-economy pack, politics half)

<!-- realdata:begin indicators-society -->
| Key | Indicator | Unit | Source and code | Countries with data |
|---|---|---|---|---|
| `life_expectancy` | Life expectancy at birth | years | World Bank WDI `SP.DYN.LE00.IN` | 217 |
| `hdi` | Human Development Index | index 0-1 | UNDP HDR `hdi` | 193 |
| `fertility` | Fertility rate | births per woman | World Bank WDI `SP.DYN.TFRT.IN` | 217 |
| `infant_mortality` | Infant mortality rate | per 1,000 live births | World Bank WDI `SP.DYN.IMRT.IN` | 196 |
| `under5_mortality` | Under-5 mortality rate | per 1,000 live births | World Bank WDI `SH.DYN.MORT` | 196 |
| `urban` | Urban population | % of population | World Bank WDI `SP.URB.TOTL.IN.ZS` | 217 |
| `pop_growth` | Population growth | % a year | World Bank WDI `SP.POP.GROW` | 217 |
| `gini` | Gini index | index 0-100 | World Bank WDI `SI.POV.GINI` | 151 |
| `poverty` | Extreme poverty headcount | % of population | World Bank WDI `SI.POV.DDAY` | 151 |
| `literacy` | Adult literacy rate | % of people 15+ | World Bank WDI `SE.ADT.LITR.ZS` | 119 |
| `secondary_enrolment` | Secondary school enrolment | % gross | World Bank WDI `SE.SEC.ENRR` | 191 |
| `internet` | Individuals using the Internet | % of population | World Bank WDI `IT.NET.USER.ZS` | 208 |
| `electricity` | Access to electricity | % of population | World Bank WDI `EG.ELC.ACCS.ZS` | 215 |
| `health_spend` | Current health expenditure | % of GDP | World Bank WDI `SH.XPD.CHEX.GD.ZS` | 193 |
| `education_spend` | Government expenditure on education | % of GDP | World Bank WDI `SE.XPD.TOTL.GD.ZS` | 190 |
| `women_parliament` | Women in national parliament | % of seats | World Bank WDI `SG.GEN.PARL.ZS` | 193 |
| `wgi_voice` | Voice and accountability | index -2.5 to 2.5 | World Bank WGI `GOV_WGI_VA.EST` | 207 |
| `wgi_stability` | Political stability and absence of violence | index -2.5 to 2.5 | World Bank WGI `GOV_WGI_PV.EST` | 207 |
| `wgi_effectiveness` | Government effectiveness | index -2.5 to 2.5 | World Bank WGI `GOV_WGI_GE.EST` | 205 |
| `wgi_regulatory` | Regulatory quality | index -2.5 to 2.5 | World Bank WGI `GOV_WGI_RQ.EST` | 205 |
| `wgi_law` | Rule of law | index -2.5 to 2.5 | World Bank WGI `GOV_WGI_RL.EST` | 207 |
| `wgi_corruption` | Control of corruption | index -2.5 to 2.5 | World Bank WGI `GOV_WGI_CC.EST` | 207 |
<!-- realdata:end indicators-society -->

The definitions and units are in each pack's *About this page* glossary (`config/about.yaml`, from `tools/packgen/*/about.yaml`, which `tools/realdata/about.py` generates) and in each macro or social indicator's
*About this series* panel. World Governance Indicators are estimates from about -2.5 (weak) to 2.5 (strong).

![FORM world-economy economy: growth above 6% among economies over USD 100 bn](img/realdata/form-economics.jpg)

`FORM world-economy economy` searches the real data (the picture was taken when the economics half was a pack of its own, `FORM economics`): the form above finds six economies larger than USD 100 bn growing above 6% (Ethiopia, India, Ireland, Kazakhstan,
Uzbekistan, Viet Nam in the April 2026 data).

#### The tool: `tools/realdata/`

```
python3 tools/realdata/realdata.py fetch     [--cache DIR] [--refresh] [--pause S]   download the raw data (network)
python3 tools/realdata/realdata.py normalise [--cache DIR]                           raw cache -> tools/realdata/dataset/*.jsonl (and meta.json)
python3 tools/realdata/realdata.py about                                              sources.yaml + about/*.kinds.yaml -> tools/packgen/*/about.yaml
python3 tools/realdata/realdata.py write                                              dataset -> the pack, its glossary and its Sutra tests
python3 tools/realdata/realdata.py tests                                              the pack's Sutra tests only (config/packs/*/tests)
python3 tools/realdata/realdata.py docs                                               the generated tables of this guide
python3 tools/realdata/realdata.py all       [--cache DIR] [--refresh]                fetch, normalise, about, write, docs: everything committed
python3 tools/realdata/realdata.py check     [--cache DIR]                           fail if anything committed differs from what `all` would write
```

`all` from the cached raw data regenerates **everything committed for the pack** byte for byte: the normalised dataset, the glossary source
(`tools/packgen/*/about.yaml`), the pack (`pack.yaml`, Sutras, samples, guides, help, `config/about.yaml`), the Sutra tests (`tests/`) and the tables of this guide
between its `<!-- realdata:begin ... -->` markers (sources, the two indicator tables with their country counts, the provenance example). Only `config/formats.yaml` is kept by hand.
`check` fails on any drift: it re-derives the dataset from the cache (skipped, with a note, when there is no cache), runs the generator (`tools/packgen/world/make.py`) with `--check` (pack, glossary and
tests) and compares the guide tables. A run on the committed state prints:

```
$ python3 tools/realdata/realdata.py check
dataset: no raw cache at /home/ci/.cache/drishti-realdata, not re-derived     (with a cache: nothing)
world-economy: <n> files up to date
```

##### Building without the IMF

```
python3 tools/realdata/realdata.py all --no-imf [--out DIR]     # fetch (never the IMF), normalise, write the pack
python3 tools/realdata/realdata.py write --no-imf               # from the committed dataset, filtered
python3 tools/realdata/realdata.py check --no-imf               # the --no-imf packs are what the tool would write
```

`--no-imf` builds a World Bank, UNDP and Factbook pack set: the IMF is not contacted, the `economic-forecast` kind and its 192 documents are gone, the IMF `debt` and
`net_lending` series are gone (so an economy has no `debtToGdp` and no `outlook` link), the `fiscal-position` kind keeps only the World Bank revenue, expense and military
shares (`debt`, `balance` and the two paths are removed), and the glossary, FORM, examples, guide and provenance never name the IMF as a source. The politics half
never used IMF data and is unchanged. The one remaining word is the World Bank's definition of *Total reserves* ("... reserve position in the IMF ..."), which describes the
reserves, not IMF data. The committed dataset, glossary, tests and this guide are never touched: the pack goes to `--out` (default `~/.cache/drishti-realdata/no-imf/packs`) and a
`--no-imf` dataset to `~/.cache/drishti-realdata/no-imf/dataset`; copy the `world-economy` directory into `config/packs/` of the build you ship.

| File | Role |
|---|---|
| `sources.yaml` | the sources (name, API, licence, attribution) and every indicator (code, unit, scale, definition); edit this to add an indicator |
| `fetch.py` | the downloader: one request per World Bank indicator, per IMF series and per Factbook file, a pause between requests, retries, files already cached are kept |
| `normalise.py` | pure parsing functions (World Bank, IMF, UNDP, Factbook) and the run that writes `dataset/*.jsonl` (one country, group, series or Factbook profile a line) and the `meta.json` manifest |
| `build_docs.py` | the document builders the pack generator calls (`economics()`, `politics()`, one per half); `Data(no_imf=True)` drops the IMF |
| `about.py`, `about/*.kinds.yaml` | the glossary source: the vocabulary comes from `sources.yaml`, the per-kind and per-field text from `about/<pack>.kinds.yaml` (edit there, not in `tools/packgen/*/about.yaml`) |
| `pack_tests.py`, `pack_tests.yaml` | the pack's Sutra tests: which documents ship as tests, what each expects, the help-coverage ratchet |
| `doc_snippets.py` | the generated tables of this guide |
| `packrun.py` | what the pack generator runs: glossary, pack and tests, written or (`--check`) compared |
| `tests/` | `test_normalise.py` against small recorded fixtures (`tests/fixtures/`), `test_build_docs.py` against the committed dataset, `test_pipeline.py` (reproducibility, drift, `--no-imf`); no network |

The cache defaults to `~/.cache/drishti-realdata` (outside the repository; use `--cache` to move it). A full fetch is about 50 World Bank calls, 8 IMF calls, one UNDP file and
about 260 Factbook files, and takes 10 to 20 minutes at the default pause; the World Bank API is sometimes slow. Run it rarely and with the default pause.

##### Refreshing

The everyday way is the pack's own refresh script, `tools/publicdata/world_economy.py` (status, dry run, `--years`, `--scope`,
the internal-only rule for the IMF data, Windows wrappers): see [PUBLIC_DATA.md](PUBLIC_DATA.md). It runs the commands below for you.

```
python3 tools/realdata/realdata.py all --refresh      # download again, normalise, regenerate glossary, packs, tests and guide tables
python3 tools/realdata/realdata.py check              # clean when everything committed matches
python3 tools/packgen/world/make.py --check           # clean when the pack matches the dataset
python3 tools/packgen/politics/make.py --check
python3 tools/drishti.py pack check config/packs/world-economy config/packs/world-economy
python3 -m pytest tools/realdata/tests
```

Review the diff of `tools/realdata/dataset/` (that is the change in the world), and update the vintage notes if a source changed its format. The UNDP file name carries
the report year (the `csv:` address under `undp:` in `sources.yaml`): move it when a new HDR is published. The Factbook mirror is pinned to the commit that was current when fetched; a
refresh pins the newest.

How the Factbook is read: *Government type*, *Capital*, *Independence*, *Executive branch* (chief of state, head of government, how chosen, last and next election),
*Legislative branch* (the lower and upper chamber: seats, system, term, parties elected and seats per party), *Political parties*, *Area*, *Languages*, *Religions* and the
*Exchange rates* currency code. A Factbook profile is matched to a World Bank country by its Internet country code (the ISO 3166 alpha-2 code, with a few exceptions) and then by
name; unmatched profiles are listed in `meta.json` (`factbookUnmatched`: dependencies and uninhabited territories the World Bank does not list). Texts such as office holders
(`President Droupadi MURMU (since 25 July 2022)`) are shown as published: the tool splits off only the *since* date, it does not parse names.

#### Generated outputs stay consistent

`tools/packgen/world/make.py` (joining the halves `tools/packgen/economics/make.py` and `tools/packgen/politics/make.py`) writes `pack.yaml`, the Sutras, `samples/`, `guides/`, `config/help.yaml` and `config/about.yaml`;
`--check` fails if any differs. They also write `config/about.yaml`'s source and the pack `tests/` (see `about.py` and `pack_tests.py`). Only `config/formats.yaml` (the `pc1` and `pc2` formats that add a `%` sign to values already in percent) is kept by hand. The
pack tests are one to five real countries per kind (India, the United States, Brazil, Nigeria, Japan) with `expect.yaml` (`noErrors`, `nonEmpty`, help coverage 100%); which ones is in `tools/realdata/pack_tests.yaml`.

```
$ python3 tools/drishti.py pack check config/packs/world-economy config/packs/world-economy
== economics: sutra lint: exit 0
== economics: sutra test: exit 0
     economy: help coverage 8/8 (100%)
     ...
== world-economy: sutra lint: exit 0
== world-economy: sutra test: exit 0
```

![Brazil's lower-chamber election](img/realdata/election-brazil.jpg)
![A governance indicator: Nigeria, rule of law](img/realdata/indicator-nigeria-rule-of-law.jpg)

The pictures are made by `tools/docs/shots/realdata.py` (`drishti-console/.venv/bin/python tools/docs/screenshots.py --guide realdata`) on a server started with
`DRISHTI_PACKS=world-economy`.

## news-and-markets

**Sources:** `quote` (Yahoo Finance quotes and daily history, **internal only**) and `news` (Google News RSS headlines, **internal
only**). The topics and listed companies are hand-written reference data (`config/packs/news-and-markets/tools/make_samples.py`).

`tools/publicdata/news_and_markets.py` wraps `tools/testdata/capture_market.py` (also `drishti.py testdata capture-market`): the
documents come through Drishti's own live connectors, so they have exactly the shape the Sutras read. It starts a scratch server
from the built jar (`mvn -q -DskipTests package` first), or uses `--server URL`. Both sources are internal only.

```bash
python3 tools/publicdata/news_and_markets.py status                  # also shows the committed snapshot
python3 tools/publicdata/news_and_markets.py refresh --dry-run
python3 tools/publicdata/news_and_markets.py refresh                 # a snapshot in <data>/publicdata/news-and-markets/testdata
python3 tools/publicdata/news_and_markets.py refresh --internal-ok   # replace the committed snapshot in data/testdata
python3 tools/drishti.py lake demo                                   # then rebuild the demo lake from it
```

- `--years N` / `--since YEAR` choose the quote history: the smallest range the connector accepts that covers it (1y, 2y, 5y,
  10y, else max).
- `--scope TSLA,NVDA,NEWS-Tesla` adds tickers (quotes) and news ids to the pack's twenty companies and fifteen topics.
- `--delay` (at least 2 seconds), `--server`, `--jar`, `--port`, `--token-file` pass through to the capture.
- The check reads the snapshot back: ids unique and sorted, every document stamped as a snapshot.
- There is no download cache here (the server fetches); a failed call is retried after 5, 10, 20 and 40 seconds, and a rerun
  replaces the snapshot.

**Check it:** `QUOTE QUOTE-AAPL <GO>` shows the captured quote with its snapshot stamp. **Offline:** the committed snapshot is what the
demo serves; the live `news` and `yfinance` connectors are off as shipped.

## market-data

**Sources:** `ecb-fx` (ECB euro reference rates), `us-treasury` (the par yield curve), `sofr` (New York Fed), `estr` (ECB); all
openly licensed with attribution. The pack's committed samples and test data are synthetic (`tools/testdata/make_testdata.py`); these
official sources are optional. FRED (the `fred-feed` connector) needs a personal API key and is left to the live connector.

`tools/publicdata/official_market_data.py` captures the official sources the pack's feed connectors read live
(`ecb-fx-feed`, `us-treasury-feed`, `nyfed-sofr-feed`, `ecb-estr-feed`; off by default), so the data works offline. The pack's
committed samples are synthetic and generated, and are never overwritten: the output always goes to the data folder or to a
folder outside the repository.

```bash
python3 tools/publicdata/official_market_data.py refresh                       # a year of history into <data>/publicdata/market-data
python3 tools/publicdata/official_market_data.py refresh ecb-fx --years 5 --scope USDMXN,EURSEK
python3 tools/publicdata/official_market_data.py refresh us-treasury sofr --since 2020
```

It writes one file a kind, in the pack's own shapes (UPPER_SNAKE): `fx-spot.jsonl` (`FX-EURUSD` and eleven more pairs, crosses
through EUR, with daily `HISTORY`), `ir-curve.jsonl` (`CRV-USD-UST-PAR`: the latest par curve and the 2Y, 10Y and 30Y history)
and `rate-fixing.jsonl` (`FIX-SOFR`, `FIX-ESTR`, newest fixing first). A rerun of one source keeps the other sources' documents
in the same file. Every document carries `PROVENANCE` (source, URL, licence, retrieval time). Load them into the lake:

```bash
uv run --with deltalake --with pyarrow --with pyyaml python tools/ingest_jsonl.py --from data/publicdata/market-data \
    --domain market --key fx-spot=PAIR,ir-curve=CURVE_ID,rate-fixing=INDEX_ID --date AS_OF --lake data/delta
```

**Check it:** the script checks every document it wrote; after loading, `FIX FIX-SOFR <GO>` shows the fixing history. **Offline:**
`refresh --offline` rebuilds the files from the cache.

## us-equities

**Sources:** `trackers` (the index members: the stocks the tracking ETFs SPY, QQQ and DIA report in their newest Form N-PORT, read
from the funds pack's download cache, so refresh `funds` first; public domain), `wikidata` (headquarters, founded, website, LEI and
the date a member joined its index, CC0), `nasdaq` (the listing market), `sec` (companies, SIC codes, filings, financial facts; public
domain), `gleif` (each company's LEI, CC0), and `prices` (Yahoo Finance daily prices, **internal only**, never part of a default
refresh).

**Why the tracking ETFs.** Index providers' constituent lists are proprietary. A fund that tracks an index must report every holding
to the SEC, so its equity holdings are the index's members on its report date (each index page names the fund, the date and the
filing). Until 2.9.3 the members came from Wikidata, whose lists were out of date: its S&P 500 lacked Exxon Mobil, BlackRock and
about 80 others and still listed 44 former members. A company Wikidata still lists but no tracking ETF holds stays in the pack, in no
index; a holding the SEC no longer lists (taken private or merged since the report) is named in the index page's note.

`tools/publicdata/us_equities.py` wraps the pack's generator, `tools/packgen/us_equities/fetch.py` (the network) and `make.py`
(the build, offline): a refresh of the committed pack writes exactly what `make.py` writes, then runs `make.py --check`. The four
company sources are openly licensed, so a plain refresh rebuilds the committed data. SEC EDGAR gets the
User-Agent it asks for, `Drishti research ajsinha@gmail.com` (set `DRISHTI_SEC_USER_AGENT` to your own), and fewer than 8 requests a
second (its limit is 10); GLEIF and Wikidata about one a second.

**Where the refresh writes.** The documents follow the standard data pattern: one gzipped JSON Lines snapshot per kind,
`data/testdata/us-equities/<kind>/snapshot-<date>.jsonl.gz`, listed in `data/testdata/testdata.yaml`; the pack's `samples/` folder keeps
only a few reference documents and its guide and README are rebuilt. The server reads the data from the demo lake through the pack's
Delta connector `us-equities-store`, never through the demo connector, so after a refresh rebuild the lake:
`python3 tools/drishti.py lake demo` (or `python3 tools/build_demo_lakes.py`).

```bash
python3 tools/publicdata/us_equities.py status                           # also the committed pack's companies and retrieval date
python3 tools/publicdata/us_equities.py refresh --dry-run
python3 tools/publicdata/us_equities.py refresh                          # the committed pack (about 1,100 SEC and 500 GLEIF calls)
python3 tools/publicdata/us_equities.py refresh --offline                # rebuild the committed pack from the cache only
python3 tools/publicdata/us_equities.py refresh --scope TSLA,CIK:1326380,FILINGS:40,YEARS:15
                                                                         # a widened build in <data>/publicdata/us-equities
python3 tools/publicdata/us_equities.py refresh prices --server http://localhost:18080 --scope AAPL,MSFT
                                                                         # internal only: a price snapshot in the data folder
```

- **The cache** (`<data>/publicdata/cache/us-equities`, or `--cache`) holds what `fetch.py` keeps: the Wikidata query results,
  the Nasdaq files, the SEC ticker file, each company's submissions (metadata of the recent filings) and its XBRL facts reduced
  to the annual values of the pack's concepts, and the GLEIF records. A file stays until it is removed, so a broken run resumes;
  `--max-age HOURS` fetches files older than that again (the old one is put back if the new fetch fails). Every file is in
  `cache-index.json` (URL, time, sha256, size).
- **`--scope`** widens the build (comma separated): a ticker (`TSLA`) or `CIK:<n>` adds a company (listed under a `SCOPE`
  membership, "Added with --scope (not an index)"); `INDEX:<CODE>=<Wikidata QID>[=<name>]` adds an index's current members
  (the QID of the index item in Wikidata); `FILINGS:<n>` keeps more recent filings a company (default 20);
  `YEARS:<n>` or `--years N` more fiscal years (default 10); `CONCEPT:<NAME>=<tag>[|<tag>...][@<unit>][@instant]` another
  annual XBRL concept (`CONCEPT:CASH=CashAndCashEquivalentsAtCarryingValue@USD@instant`).
- **A widened build goes to the data folder**: `<data>/publicdata/us-equities/packs/us-equities`, a whole pack (the hand-written
  manifest, Sutras and help copied, the reference samples and guide generated), with its snapshot beside it in
  `<data>/publicdata/us-equities/testdata/us-equities/<kind>/`, checked with `make.py --check --pack`. Its cache is
  `scoped/<hash>` inside the cache, seeded from the plain cache less what the scope changes (submissions for `FILINGS`, facts for
  `YEARS` and `CONCEPT`, the Wikidata members for `INDEX`). `--into-pack` writes a widened build over the committed pack, on
  purpose only; without it `--out config/packs/us-equities` with a scope is refused.
- **Prices are internal only.** The pack ships no prices; the `prices` source reads `price-history/PX-<ticker>` through the
  pack's `us-equities-prices` connector of a running server where you switched it on (`DRISHTI_US_EQUITIES_PRICES_ENABLED=true`,
  `--server`, `--token-file`, `--delay` at least 2 s, `--limit N`). It is never part of a default refresh and writes
  `<data>/publicdata/us-equities/prices/price-history/snapshot-<date>.jsonl.gz`, each document stamped as a snapshot; into a
  committed folder only with `--internal-ok`. Each ticker is cached under `prices/` in the cache, so `--offline` rebuilds it.

**Check it:** `make.py --check` (run by the refresh; without a cache it checks the committed snapshot); `USEQ AAPL <GO>` opens a
company. **Offline:** `refresh --offline`.

## gleif

**Sources:** `golden` (the golden copy's level 2 files), `api` (the LEI API), `mapping` (the BIC, ISIN, MIC and OpenCorporates
mapping files), and `golden-lei` (the level 1 bulk file, for full-country pulls into the data folder only). Everything GLEIF publishes
is CC0 1.0.

`tools/publicdata/gleif.py` wraps the pack's generator, `tools/packgen/gleif/fetch.py` (the network) and `make.py` (the build,
offline): a refresh of the committed pack writes exactly what `make.py` writes, then runs `make.py --check`, and an offline refresh
from an unchanged cache reproduces the committed data byte for byte. Everything GLEIF publishes is CC0 1.0 (attribution
appreciated). GLEIF gets the User-Agent `Drishti research ajsinha@gmail.com` (set `DRISHTI_GLEIF_USER_AGENT` to your own) and at
most one API call a second (GLEIF allows 60 a minute).

**Where the refresh writes.** As for us-equities: one snapshot per kind, `data/testdata/gleif/<kind>/snapshot-<date>.jsonl.gz`,
listed in `data/testdata/testdata.yaml`, served from the demo lake by the pack's Delta connector `gleif-store` (never the demo
connector); `samples/` keeps a few reference documents. Rebuild the lake after a refresh: `python3 tools/drishti.py lake demo`.

```bash
python3 tools/publicdata/gleif.py status                           # also the committed pack's entities and golden-copy date
python3 tools/publicdata/gleif.py refresh --dry-run                # the universe today, the downloads; changes nothing
python3 tools/publicdata/gleif.py refresh                          # the committed pack: ~90 MB of golden copy and mapping files, ~70 API calls
python3 tools/publicdata/gleif.py refresh --offline                # rebuild the committed pack from the cache only
python3 tools/publicdata/gleif.py refresh --scope GROUP:784F5XWPLTWKTBV3E584,CAP:600
                                                                   # Goldman Sachs' whole group, in <data>/publicdata/gleif
python3 tools/publicdata/gleif.py refresh golden golden-lei api mapping --scope COUNTRY:LU --since 2025
                                                                   # every Luxembourg entity updated since 2025, from the bulk file
```

- **The sources.** `golden`: the golden copy's level 2 files, the relationship records (`rr`, about 24 MB zipped) and the
  reporting exceptions (`repex`, about 62 MB), downloaded whole and reduced into `reduced/` (the consolidation relationships;
  the exceptions of the pack's entities). `api`: the LEI record of every entity of the pack, 200 a request (`filter[lei]`), and
  the code lists (entity legal forms, countries, LEI issuers). `mapping`: the BIC, ISIN, MIC and OpenCorporates mapping files
  (the ISIN file is about 33 MB zipped), reduced to the pack's entities. `golden-lei`: the level 1 bulk file (about 480 MB
  zipped), only for `COUNTRY:` pulls into the data folder; never part of a default refresh, never into the committed pack.
- **The universe.** The seeds are the us-equities companies with an LEI (from that pack's committed snapshot,
  `data/testdata/us-equities/us-company/`) and the banks,
  insurers, asset managers and market infrastructures pinned in `fetch.py` (`SEEDS`, LEIs resolved once by legal name). Each
  seed brings its ownership group: its ultimate parent and every entity it consolidates, level by level, at most 50 a group
  (`GROUP_CAP`; a group page says when GLEIF has more).
- **The cache** (`<data>/publicdata/cache/gleif`, or `--cache`) keeps every download as served (`golden/`, `mapping/`,
  `api/records/` batches) and the reductions (`reduced/`); a file stays until removed, so a broken run resumes, and only LEIs
  not yet cached are asked for. `--max-age HOURS` fetches the golden copy, mapping files and code lists again when older (the old
  one is put back if the new fetch fails). Every download is in `cache-index.json`.
- **`--scope`** widens the build (comma separated): `LEI:<lei>` or `GROUP:<lei>` adds that entity's group; `COUNTRY:<XX>` the
  entities with a legal address in the country (the LEI API, at most `--max-pages` pages of 200, default 10; every one with
  `golden-lei`); `NAME:<text>` what GLEIF's full-text search finds (one page of 200); `TICKERS-FROM:us-equities` the us-equities
  companies of the widened us-equities build's snapshot in the data folder when there is one (or
  `TICKERS-FROM:<a us-company .jsonl or .jsonl.gz>`);
  `CAP:<n>` up to n entities a group. `--years N` / `--since YEAR` keep only the `COUNTRY:` and `NAME:` entities whose LEI record
  was updated in that period.
- **A widened build goes to the data folder**: `<data>/publicdata/gleif/packs/gleif`, a whole pack (the hand-written manifest,
  Sutras and help copied, reference samples and guide generated), with its snapshot in `<data>/publicdata/gleif/testdata/gleif/`,
  checked with `make.py --check --pack`. `--into-pack` writes it over the
  committed pack, on purpose only (never with `golden-lei`); without it `--out config/packs/gleif` with a scope is refused.

**Check it:** `make.py --check` (run by the refresh); `LEI 8I5DZWZKVSZI1NUHU748 <GO>` opens an entity, `LEIG ALL <GO>` every group.
**Offline:** `refresh --offline` reproduces the committed data from the cache byte for byte.

## funds

**Sources:** `tickers` (the SEC's fund share class and listed-company ticker files), `nasdaq` (the Nasdaq Trader symbol directory:
which tickers are ETFs), `nport` (the newest quarter of the SEC's Form N-PORT data sets), `ncen` (the newest four quarters of the Form
N-CEN data sets), `rr` (the newest four quarters of the prospectus risk/return summary data sets) and `gleif` (GLEIF's level 2
relationship records, reused from the gleif pack's cache when it has them). The SEC's data sets are U.S. public domain; GLEIF's are
CC0 1.0.

`tools/publicdata/funds.py` downloads the data sets into its cache and runs the pack's generator, `tools/packgen/funds/make.py` (the
build, offline: it streams the N-PORT tables once and keeps the 200 largest funds and ETFs by net assets that have a ticker), then
`make.py --check`. SEC requests use exactly the us-equities pack's User-Agent setting (`DRISHTI_SEC_USER_AGENT`, else
`tools/packgen/us_equities/fetch.py`'s default) and one request at a time.

**The downloads are large** (an N-PORT quarter is about 450 MB, four quarters of risk/return summaries about 200 MB, N-CEN 6 to 16 MB
a quarter) and stay in `<data>/publicdata/cache/funds`, never in git; a file younger than `--max-age` hours (a week by default) is
reused. **Where the refresh writes:** the committed snapshot, gzipped JSON Lines under `data/testdata/funds/<kind>/` (the fund kind
in parts of about 1.2 MB, 5.5 MB in all), listed in `data/testdata/testdata.yaml` and served from the demo lake by the pack's Delta
connector `funds-store`; `samples/`, the README, the guide and the glossary (`config/about.yaml`) of `config/packs/funds`. Rebuild
the lake after a refresh: `python3 tools/drishti.py lake demo`.

```bash
python3 tools/publicdata/funds.py status                           # the committed funds, each source's licence, last refresh and cache size
python3 tools/publicdata/funds.py refresh --dry-run                # the quarters and files a refresh would fetch; changes nothing
python3 tools/publicdata/funds.py refresh                          # the committed pack: about 700 MB of downloads, then make.py and --check
python3 tools/publicdata/funds.py refresh --offline                # rebuild the committed pack from the downloads only
python3 tools/publicdata/funds.py refresh ncen rr --since 2025     # every quarter of the census and the summaries since 2025
python3 tools/publicdata/funds.py refresh --scope FUNDS:300,HOLDINGS:1000
                                                                   # the 300 largest, 1,000 holdings a fund, in <data>/publicdata/funds
```

On Windows: `.\tools\windows\publicdata-funds.ps1 status`, `.\tools\windows\publicdata-funds.ps1 refresh --dry-run`,
`.\tools\windows\publicdata-funds.ps1 refresh`; on Linux and macOS `tools/publicdata/funds.sh` takes the same arguments.

- **The 200.** Every fund's newest N-PORT report in the quarter (amendments win over the report they amend), ranked by net assets;
  a fund needs a ticker (a share class in `company_tickers_mf.json`, or, for a unit investment trust such as SPY or DIA, its
  listed ticker in `company_tickers_exchange.json`). Money market funds file Form N-MFP, not N-PORT, so they are not among them.
- **The holdings.** A fund keeps its 600 largest holdings by value (every S&P 500 fund whole) and the totals of the rest; the
  reverse look-through (`HELD`) counts every holding. The index tab's weights are computed from SEC data (shares outstanding times
  the price the filings imply) because index weights are proprietary.
- **`--scope`** widens the build: `FUNDS:<n>` the n largest funds, `HOLDINGS:<n>` the holdings kept a fund. A widened build goes to
  `<data>/publicdata/funds` (`packs/funds` and `testdata/funds`); `--into-pack` writes it over the committed pack, on purpose only.

**Check it:** `make.py --check` (run by the refresh); `FUND QQQ <GO>` opens a fund, `HELD AAPL <GO>` the funds holding Apple.
**Offline:** `refresh --offline` rebuilds the committed data from the downloads; without them `make.py --check` checks the committed
snapshot against what it derives. The guide is [FUNDS_GUIDE.md](FUNDS_GUIDE.md) (Part III: where the data comes from).

## What every refresh does

Each source is a small plug-in class (`pdbase.Source`): **fetch** (download into the cache), **normalise** (parse what was
cached, count the rows), **write** (the pack's files). The shared library `tools/publicdata/pdbase.py` gives every script:

- **Polite HTTP**: a `User-Agent` naming Drishti and a contact, at least `--pause` seconds between requests, retries after 2, 4
  and 8 seconds (or the server's `Retry-After`) on 429, 5xx and network errors; a 404 is not retried.
- **A download cache** under `<data>/publicdata/cache/<pack>/`: the raw files exactly as served, plus `cache-index.json`
  (URL, fetched time, sha256, size of each). A file younger than `--max-age` is reused, and the index is saved every 25
  downloads, so a run that breaks **resumes** where it stopped. A file asked for with another URL (a wider `--years`) is fetched again.
- **A manifest** per pack, `<data>/publicdata/<pack>/manifest.json` (and a copy in `--out` when that is outside the
  repository): each source's title, URL, licence, use, fetched time and row counts; the files written with their sha256 and size;
  the last 20 runs. `status` reads it.
- **Idempotent writes**: the same cache gives the same bytes. A refresh of the committed world-economy pack from an unchanged
  cache is a no-op in git.
- **The pack's check** after writing (skip with `--no-check`).
- **The same help**: `pdbase.parser` gives every script the same options, and every subcommand's `--help` ends with the script's
  worked examples (its docstring's) and its two wrappers.

## Adding a source

A source is a class with its licence and three steps. Here is SONIA from the Bank of England, added to
`official_market_data.py` (the Bank's statistics may be reused with acknowledgement):

```python
class Sonia(MarketSource):
    name, title, kind = "sonia", "Sterling Overnight Index Average (Bank of England)", "rate-fixing"
    url = "https://www.bankofengland.co.uk/markets/sonia-benchmark"
    licence, licence_url = "Bank of England: reuse with acknowledgement", "https://www.bankofengland.co.uk/legal"
    cache_prefixes = ("sonia/",)
    CSV = ("https://www.bankofengland.co.uk/boeapps/database/_iadb-fromshowcolumns.asp?csv.x=yes&SeriesCodes=IUDSOIA"
           "&Datefrom={d:%d/%b/%Y}&Dateto=now&CSVF=TN&UsingCodes=Y")

    def plan(self, run):
        return [self.CSV.format(d=self.first(run))]

    def fetch(self, run):                      # the shared client: User-Agent, pause, retries, cache
        run.http.get(self.plan(run)[0], run.cache.folder / "sonia" / "sonia.csv", accept="text/csv")

    def normalise(self, run):                  # a pure parser, tested against a recorded response
        rows = csv.reader(io.StringIO((run.cache.folder / "sonia" / "sonia.csv").read_text()))
        obs = [(datetime.strptime(d, "%d %b %Y").date().isoformat(), float(v), None) for d, v in list(rows)[1:]]
        doc = fixing_doc("FIX-SONIA", "Sterling Overnight Index Average", "Bank of England", "GBP", obs, self, self.plan(run)[0], B.now())
        self.docs = [doc] if doc else []
        return {"rows": len(obs), "documents": len(self.docs)}

SOURCES = [EcbFx(), UsTreasury(), Sofr(), Estr(), Sonia()]
```

Then:

1. Record a trimmed real response in `tools/publicdata/tests/fixtures/` and test the parser in
   `tools/publicdata/tests/test_publicdata.py` (no network in tests).
2. If its terms forbid redistribution, set `use = B.INTERNAL` and `why_internal = "..."` in words: the guard does the rest.
3. If `--scope` can widen it, set `scope_help` and read `run.scope` in `fetch`/`normalise`.
4. Add a row to the table at the top of this page, an entry to `tools/packgen/common/data_retrieval.yaml` (so the pack guide's
   *Data retrieval* section names it) and an entry to the CHANGELOG.

**A new pack** gets a new script: copy `official_market_data.py`, keep `pdbase.parser`, `pdbase.status` and the guard, set
`PACK`, its sources and where it writes; add its pack to `pdbase.WRAPPERS`, its `.sh` and `tools/windows/publicdata-<pack>.ps1`
wrappers, its rows and section on this page, and its sources to `data_retrieval.yaml`.
`tools/publicdata/tests/test_scripts.py` fails until all of these are there.

## Not covered

- **Genomics** reference facts (`tools/packgen/genomics/reference.py`: gene coordinates, UniProt accessions, dbSNP ids) are
  typed in by hand from public references; there is no fetcher to wrap. A script would query Ensembl and UniProt.
- **Region maps** (`tools/maps/build_regions.py`, Natural Earth, public domain) are console assets, not a pack; that tool
  keeps its own download.
- **FRED** (the market-data pack's `fred-feed`) needs a personal API key and FRED's terms limit redistribution of some
  series; it is left to the live connector.

## Tests

```bash
python -m pytest -q tools/publicdata/tests                            # no network: recorded responses, the help, the wrappers, status offline
DRISHTI_LIVE_FETCH=1 python -m pytest -q tools/publicdata/tests -k live   # one live fetch (the ECB's euro short-term rate)
```
