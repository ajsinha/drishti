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
# Public data: refreshing the packs built on it

Some packs carry published public data instead of generated samples. Each of them has **its own refresh script** in
`tools/publicdata/`, run on its own whenever you want newer or wider data:

| Pack | Script | Shell | Windows |
|---|---|---|---|
| `world-economy` | `tools/publicdata/world_economy.py` | `tools/publicdata/world-economy.sh` | `tools\windows\publicdata-world-economy.ps1` |
| `news-and-markets` | `tools/publicdata/news_and_markets.py` | `tools/publicdata/news-and-markets.sh` | `tools\windows\publicdata-news-and-markets.ps1` |
| `market-data` | `tools/publicdata/official_market_data.py` | `tools/publicdata/market-data.sh` | `tools\windows\publicdata-market-data.ps1` |
| `us-equities` | `tools/publicdata/us_equities.py` | `tools/publicdata/us-equities.sh` | `tools\windows\publicdata-us-equities.ps1` |
| `gleif` | `tools/publicdata/gleif.py` | `tools/publicdata/gleif.sh` | `tools\windows\publicdata-gleif.ps1` |

Every script has the same two commands and the same options:

```bash
python3 tools/publicdata/<script>.py status                 # each source: licence, use, last refreshed, cached size
python3 tools/publicdata/<script>.py refresh --dry-run      # what would be fetched and written; changes nothing
python3 tools/publicdata/<script>.py refresh [SOURCE ...]   # fetch, normalise, write, then the pack's own check
```

| Option | Meaning |
|---|---|
| `SOURCE ...` | only these sources of the pack (default: all of them) |
| `--years N` / `--since YEAR` | how much history: the last N years, or from YEAR |
| `--scope A,B` | widen the source: more indicators, tickers, pairs (each source says what, in `refresh --help`) |
| `--out DIR` | where to write (default: below) |
| `--internal-ok` | allow internal-only data into a committed folder of the repository (internal demos only) |
| `--cache DIR` | the download cache (default `<data>/publicdata/cache/<pack>`) |
| `--max-age HOURS` | reuse a cached download younger than this (default 24); older files are fetched again |
| `--offline` | use the cache only, fetch nothing (a rebuild on a machine without internet) |
| `--pause S` | seconds between requests (default 0.4) |
| `--no-check` | skip the pack's check after writing |

`<data>` is the configurable data folder: `DRISHTI_DATA_DIR`, else `data/` in the Drishti folder (git-ignored). Exit codes:
0 done, 2 done with some downloads failed or a check problem, 1 nothing written, 3 refused (the reason is printed in words).

There is no single script for every pack, on purpose: each pack's sources, licences and output are its own.

## Sources and licences

| Pack | Source (`SOURCE`) | Licence | Use |
|---|---|---|---|
| world-economy | World Bank WDI and WGI (`worldbank`) | CC BY 4.0 | committable, with attribution |
| world-economy | IMF World Economic Outlook, DataMapper (`imf`) | IMF terms: reuse with attribution, commercial reuse needs the IMF's permission | **internal only** |
| world-economy | UNDP Human Development Index (`undp`) | CC BY 3.0 IGO | committable, with attribution |
| world-economy | CIA World Factbook, factbook.json mirror at a pinned commit (`factbook`) | public domain (mirror CC0 1.0) | committable |
| news-and-markets | Yahoo Finance quotes and daily history, through the yfinance connector (`quote`) | Yahoo terms: no redistribution | **internal only** |
| news-and-markets | Google News RSS headlines, through the news connector (`news`) | headlines belong to their publishers | **internal only** |
| market-data | ECB euro reference FX rates (`ecb-fx`) | ECB: free reuse with the source acknowledged | committable, with attribution |
| market-data | US Treasury daily par yield curve (`us-treasury`) | public domain (US Government work) | committable |
| market-data | SOFR, Federal Reserve Bank of New York (`sofr`) | NY Fed terms of use: reuse with attribution | committable, with attribution |
| market-data | Euro short-term rate, ECB (`estr`) | ECB: free reuse with the source acknowledged | committable, with attribution |
| us-equities | Wikidata: index membership and company facts (`wikidata`) | CC0 1.0 | committable |
| us-equities | Nasdaq Trader symbol directory (`nasdaq`) | public symbol directory files | committable |
| us-equities | SEC EDGAR: tickers, submissions, XBRL company facts (`sec`) | public domain (US Government work); EDGAR fair access policy | committable |
| us-equities | GLEIF LEI records (`gleif`) | CC0 1.0 | committable |
| us-equities | Yahoo Finance daily prices, through the us-equities-prices connector (`prices`) | Yahoo terms: no redistribution | **internal only** |
| gleif | GLEIF golden copy, level 2: relationship records and reporting exceptions (`golden`) | CC0 1.0 (attribution appreciated) | committable |
| gleif | GLEIF LEI API: LEI records, legal forms, countries, LEI issuers (`api`) | CC0 1.0 | committable |
| gleif | GLEIF mapping files: BIC, ISIN, MIC, OpenCorporates to LEI (`mapping`) | CC0 1.0 | committable |
| gleif | GLEIF golden copy, level 1 bulk file, for full-country pulls (`golden-lei`, never by default) | CC0 1.0 | data folder only (its size, not its licence) |

**The internal-only rule.** A source whose terms do not allow redistribution is never written into a committed folder of the
repository unless you pass `--internal-ok`; without it the script either writes to the data folder (when you did not give
`--out`, and it says so) or refuses (when `--out` names a committed folder), and explains why in words:

```text
refused: .../config/packs/world-economy is a committed folder of the repository, and these sources may not be redistributed:
  - IMF World Economic Outlook (DataMapper): the IMF's terms allow reuse with attribution, but commercial reuse needs the IMF's permission; ...
Write them to the data folder instead (leave out --out, or point it outside the repository), or pass --internal-ok
if the result stays inside your organisation (internal demos only).
```

The committed world-economy pack carries the IMF forecasts, and the committed news-and-markets snapshot carries Yahoo and Google
News data: both were the owner's choice for internal demos ([REAL_DATA.md](REAL_DATA.md), `data/testdata/README.md`). Before any
external or client use, rebuild without them (`world_economy.py refresh --no-imf`) or get permission.

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

Only the Python standard library is needed (and PyYAML, which the console's environment has). No other program is run.

## world-economy

`tools/publicdata/world_economy.py` wraps `tools/realdata/` (see [REAL_DATA.md](REAL_DATA.md)): the fetcher's downloads go
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
  `tools/packgen/world/about.yaml` and the generated tables of `REAL_DATA.md`. Commit the result.
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

## news-and-markets

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

## market-data

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

## us-equities

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

## gleif

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
4. Add a row to the table above and an entry to the CHANGELOG.

**A new pack** gets a new script: copy `official_market_data.py`, keep `pdbase.parser`, `pdbase.status` and the guard, set
`PACK`, its sources and where it writes, and add its `.sh` and `tools/windows/publicdata-<pack>.ps1` wrappers.

## Windows

The scripts are plain Python: no other program is downloaded or run. The PowerShell wrappers use the console's environment
(`drishti-console\.venv`, made by `tools\windows\start-console.ps1`), else `DRISHTI_PYTHON` or `python`, and pass every argument on:

```powershell
.\tools\windows\publicdata-world-economy.ps1 status
.\tools\windows\publicdata-world-economy.ps1 refresh --dry-run
.\tools\windows\publicdata-market-data.ps1 refresh --years 2
.\tools\windows\publicdata-news-and-markets.ps1 refresh --server http://localhost:18080
.\tools\windows\publicdata-us-equities.ps1 refresh --scope TSLA,FILINGS:40
.\tools\windows\publicdata-gleif.ps1 refresh --scope NAME:allianz,CAP:200
```

Set `$env:DRISHTI_DATA_DIR` to put the cache and the builds elsewhere.

## Not covered

- **Genomics** reference facts (`tools/packgen/genomics/reference.py`: gene coordinates, UniProt accessions, dbSNP ids) are
  typed in by hand from public references; there is no fetcher to wrap. A script would query Ensembl and UniProt.
- **Region maps** (`tools/maps/build_regions.py`, Natural Earth, public domain) are console assets, not a pack; that tool
  keeps its own download.
- **FRED** (the market-data pack's `fred-feed`) needs a personal API key and FRED's terms limit redistribution of some
  series; it is left to the live connector.

## Tests

```bash
python -m pytest -q tools/publicdata/tests                            # no network: recorded responses
DRISHTI_LIVE_FETCH=1 python -m pytest -q tools/publicdata/tests -k live   # one live fetch (the ECB's euro short-term rate)
```
