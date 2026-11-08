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
# External data feeds

How Drishti pulls external data (GLEIF, the SEC, the IMF, the World Bank, central banks, news) and loads it into its lakes, every day,
from the command line or a scheduler. One module does it all, **drishti-feeds**: a feed pulls its source in Python, writes what it
pulled as JSON Lines, one gzipped file per dataset and business date, and hands the files to the loaders Drishti already has.

Where each source's data goes, what it holds and its licence are in [EXTERNAL_DATA.md](EXTERNAL_DATA.md) (Help → Data → External
data); the lakes, the `.ready` file and the data inbox are the [data lake guide](DATA_LAKE_GUIDE.md).

## Contents

1. [The module](#1-the-module)
2. [The feeds](#2-the-feeds)
3. [Run a feed](#3-run-a-feed)
4. [Loading: three routes](#4-loading-three-routes)
5. [Run it every day](#5-run-it-every-day)
6. [Monitor: status, the ledger, Admin → Feeds, alerts](#6-monitor-status-the-ledger-admin--feeds-alerts)
7. [The feed file, every key](#7-the-feed-file-every-key)
8. [Writing a feed](#8-writing-a-feed)
9. [Licences and internal-only data](#9-licences-and-internal-only-data)
10. [Tests](#10-tests)

## 1. The module

drishti-feeds is the Python package `tools/feeds/` beside the other command-line tools, with one feed file per feed in
`config/feeds/`. It is a package of the tools rather than a Maven module because the pull is Python (every source is an HTTP API or a
bulk file that Python reads with its standard library) and the load is not new code at all: the module calls `drishti.py lake build`,
which runs the parallel loader in the server's jar, or drops files into the data inbox. Nothing in it writes a lake.

| Part | File | Does |
|---|---|---|
| the Feed base class, the feed file, one run's files | `tools/feeds/feedbase.py` | section 8; checks `config/feeds/<feed>.yaml` (section 7) |
| one run end to end, the run ledger | `tools/feeds/feedrunner.py` | lock → pull → files → check → load → state → ledger → one log line |
| the three load routes | `tools/feeds/feedload.py` | section 4 |
| the schedule entries | `tools/feeds/feedschedule.py` | section 5 |
| the command line | `tools/feeds/cli.py` | `drishti.py feed list|run|status|schedule` |
| the polite HTTP client and download cache | `tools/feeds/pullkit.py` | User-Agent, pause, retries with back-off, `Retry-After`, the cache |
| each source's pull | `tools/feeds/sources/<module>.py` | one module per source (a feed file's `class:`) |
| the wrappers | `tools/feeds/feed.sh`, `tools/windows/feed.ps1` | what cron, systemd and Task Scheduler run |

Every external source of the packs is pulled this way: each pack's source module in `tools/feeds/sources/` (gleif, us-equities, funds,
world-economy, news-and-markets, market-data) is a feed's pull, and the same module refreshes its pack's committed demo snapshot with
`feed run <feed> --snapshot` (section 3.4). There is one way to pull external data.

**Where things are written**, all under the data folder (`DRISHTI_DATA_DIR`, else `./data`; never committed):

```text
data/feeds/
├── ledger.jsonl                                    one line per run, every feed (section 6)
└── gleif/
    ├── state.json                                  what the feed has pulled (section 3.2)
    ├── cache/                                      the downloads as served, with cache-index.json (a broken run resumes from it)
    ├── 2026-11-07/
    │   ├── manifest.json                           the files with their rows and SHA-256, the licence, the run
    │   ├── lei-entity.2026-11-07.jsonl.gz          <table>.<YYYY-MM-DD>.jsonl.gz: the load rules' name (table and date)
    │   ├── lei-relationship.2026-11-07.jsonl.gz
    │   └── lei-mapping.2026-11-07.jsonl.gz
    └── loads/<run>/                                the loader's summaries of each run
```

## 2. The feeds

| Feed | Source | Datasets (pack: kinds) | Lake | Use | Daily at |
|---|---|---|---|---|---|
| `gleif` | GLEIF golden copy, LEI API, mapping files | gleif: lei-entity, lei-relationship, lei-mapping | `referencelake` | CC0, redistributable | 05:30 |
| `funds` | SEC Form N-PORT, N-CEN, risk/return summaries; Nasdaq Trader; GLEIF | funds: fund, fund-holder, index-funds | `capitalmarket` | public domain | 06:15 |
| `world-economy` | World Bank WDI and WGI, IMF WEO, UNDP HDI, CIA Factbook | world-economy: economy, macro-indicator, economic-forecast, labour-market, fiscal-position, region-economy, jurisdiction, party, office-holder, election, region, social-indicator | `worldlake` | **internal only** (the IMF forecasts) | 06:45 |
| `us-equities` | SEC EDGAR; index members from SPY, DIA, QQQ and IWM N-PORT holdings (S&P 500, Dow 30, Nasdaq-100, Russell 2000); Nasdaq Trader; GLEIF; Wikidata | us-equities: us-company, index-membership, filing-feed, filing, financial-fact | `capitalmarket` | public domain, CC0 | 07:00 |
| `news-and-markets` | Yahoo Finance quotes, Google News headlines (through Drishti's own live connectors) | news-and-markets: quote, news | `newslake` | **internal only** | 07:30 |
| `market-data` | ECB euro reference rates, US Treasury par curve, SOFR, euro short-term rate | market-data: fx-spot, ir-curve, rate-fixing (each under its own date) | `capitalmarket` | attribution | weekdays 18:30 |
| `yahoo-prices` | Yahoo Finance daily prices of the us-equities companies (through a running server's connector) | us-equities: price-history | `capitalmarket` | **internal only**, off as shipped | weekdays 22:30 |

The funds feed runs before us-equities on purpose: its N-PORT download cache gives us-equities its index members.

`drishti.py feed list` prints the same from the feed files. Each feed's source, data contract (with JSON examples) and licence are its
pack's README and the [External data](EXTERNAL_DATA.md) page.

## 3. Run a feed

```bash
python3 tools/drishti.py feed run gleif
```

```text
fetch golden
...
  loaded lei-entity 2026-11-07: 1,204 rows into lake referencelake (java)
2026-11-07T06:30:41-05:00 feed ok exit=0 feed=gleif date=2026-11-07 files=3 rows=4,377 load=java loaded=4,377 seconds=41.2
```

The last line is the run's **one log line**: what a scheduler's mail or the journal shows. The same in PowerShell:
`.\tools\windows\feed.ps1 run gleif`.

| Option | Means |
|---|---|
| `<feed>` or `--all` | one feed, or every enabled feed one after another |
| `--date YYYY-MM-DD` | the business date (default today) |
| `--since YYYY-MM-DD` | pull history from this date (default: the day after the state's last date; section 3.2) |
| `--load java\|python\|inbox\|none` | the load route (default: the feed file's `load:`, `java` as shipped; section 4) |
| `--lake NAME` | load into this lake instead of the feed file's |
| `--dry-run` | say what would be fetched and loaded; nothing changes, no ledger line |
| `--snapshot` | refresh the committed demo snapshot instead (section 3.4) |
| `--offline` | the download cache only; nothing is fetched |
| `--set NAME=VALUE` | override an option of the feed file (`--set indicators='["NY.GDP.MKTP.CD"]'`) |
| `-- ARGS` | arguments for the source itself (`feed run gleif -- --scope GROUP:784F5XWPLTWKTBV3E584`) |
| `--inbox DIR`, `--lake-root DIR`, `--jar`, `--java`, `--signal URL`, `--token-file` | for the load routes (section 4) |
| `--json` | the run's ledger entry as JSON |

### 3.1 Exit codes

| Code | Means | A scheduler should |
|---|---|---|
| 0 | ok: pulled, written, loaded | nothing |
| 1 | failed: the pull (after its retries), the feed's check or the load; the error in words | alert (Drishti already did, section 6) |
| 2 | refused: a bad feed file, a key's variable not set, an unknown feed, internal-only data into a committed folder | fix the setting |
| 3 | another run of the feed holds its lock (`data/feeds/<feed>/run.lock`) | nothing; the next day's run |
| 4 | partial: some feeds of `--all` failed, or the load rejected rows | look at `feed status` |

### 3.2 Incremental pulls, resume and re-runs

- **State.** `data/feeds/<feed>/state.json` records, per dataset, the last business date pulled. The next run asks the source only for
  what is newer (`run.window()`, section 8). The state is saved **only when the whole run succeeded**, so a failed run is pulled again
  from the same point. The first run of a feed pulls `history.first-run-days` (30) days; `--since` pulls any history.
- **Resume.** Every download goes through the cache `data/feeds/<feed>/cache/` (`cache-hours`, 12 by default); a run that broke half way
  reuses what it fetched and asks only for the rest.
- **Re-runs are idempotent.** A run writes each date's files to a work folder and then replaces the date's folder whole; every route
  loads with replace-by-date. Running the same date twice leaves the same files and the same rows in the lake.
- **Rate limits.** `source.pause` seconds between requests; `source.retries` retries after 2, 4, 8 … seconds on 429, 5xx and network
  errors, or after the server's `Retry-After`.

### 3.3 The User-Agent and keys

Every request carries one User-Agent: `DRISHTI_FEEDS_USER_AGENT`, else `Drishti-feeds/1.0 (Drishti data feeds)`. The SEC asks for a
contact in it; set it to your organisation and a team mailbox:

```bash
export DRISHTI_FEEDS_USER_AGENT="Example Bank market data team marketdata@bank.example"
```

A key (an API key, a token) is only ever an environment variable named in the feed file (`keys:`, section 7); a key written in a file
is refused, and a feed whose key's variable is not set exits 2 naming it.

### 3.4 `--snapshot`: the committed demo snapshot

A feed whose pack ships a committed snapshot (the demo data in `data/testdata/<pack>/`) refreshes it with `--snapshot`: the pack's
generator writes its files where they live (the samples and guide, `data/testdata/<pack>/`), its check runs, nothing is loaded. Internal-only data
(section 9) is written into the repository only with `--internal-ok`.

```bash
python3 tools/drishti.py feed run gleif --snapshot          # config/packs/gleif (samples, guide) and data/testdata/gleif
python3 tools/drishti.py lake demo                          # then rebuild the demo lake from it
```

## 4. Loading: three routes

None of the routes writes a lake itself; each hands the run's files to what Drishti already has, and each replaces the business dates it
loads.

| Route | What runs | Needs | Use it when |
|---|---|---|---|
| `java` (default) | `drishti.py lake build --engine java`: the parallel loader `com.ash.drishti.lakeload.LakeLoad` from the server's exec jar (`-Dloader.main`), under the lake's lock, one commit per business date | Java 21 and the jar (`DRISHTI_JAR` or `drishti-server/target`) | always, unless one of the others fits better |
| `python` | `drishti.py lake build` with its default writer | the console's Python and the jar | a machine where the parallel loader is not wanted |
| `inbox` | the files dropped into the data inbox with their `.ready` files; the server loads them at its next cycle | the server running with the inbox on | the server should load (one place for every load, its Admin → Data loads screen) |

**The java and python routes** load one dataset of one date at a time (`--mode overwrite-dates`), into the lake the feed file names;
a table the lake file does not describe yet gets its pack's recommended entry first. `--lake-root DIR` writes a scratch copy;
`--signal URL` tells a server the data landed.

**The inbox route** drops into `<data>/dataload/inbox/` (`DRISHTI_DATALOAD_DIR`, or `--inbox DIR`):

- one dataset of one date: the file and its `.ready`:

  ```yaml
  # lei-mapping.2026-11-07.jsonl.gz.ready
  lake: referencelake
  pack: gleif
  kind: lei-mapping
  table: lei-mapping
  business-date: '2026-11-07'
  mode: replace
  sha256: 9c1f...
  rows: 3
  ```

- anything more: **one tar** of `<table>/<YYYY-MM-DD>/` folders, which the inbox loads completely or not at all:

  ```text
  gleif-2026-11-07.tar
  ├── lei-entity/2026-11-07/lei-entity.2026-11-07.jsonl.gz
  ├── lei-mapping/2026-11-07/lei-mapping.2026-11-07.jsonl.gz
  └── lei-relationship/2026-11-07/lei-relationship.2026-11-07.jsonl.gz
  ```

  ```yaml
  # gleif-2026-11-07.tar.ready
  lake: referencelake
  folders: {lei-entity: gleif, lei-mapping: gleif, lei-relationship: gleif}
  mode: replace
  sha256: 41d2...
  rows: 4377
  ```

The data file is written under a `.part` name and renamed when complete; the `.ready` file comes last. The inbox checks the SHA-256 and
the rows before anything is written ([DATA_LAKE_GUIDE.md section 4](DATA_LAKE_GUIDE.md#4-the-ready-file-every-key)).

## 5. Run it every day

`drishti.py feed schedule` prints ready-to-use daily entries, one per enabled feed, at the time its feed file's `schedule:` gives
(local time). Every entry runs the wrapper of its system, so the Python is the console's.

**Linux and macOS, cron:**

```bash
python3 tools/drishti.py feed schedule --cron
```

```text
# Drishti data feeds: daily (crontab -e, then paste; the times are this machine's local time)
# mkdir -p $HOME/drishti-feeds-logs   (one log file per feed; each run appends its one log line and any messages)
SHELL=/bin/bash
30 5 * * * /opt/drishti/tools/feeds/feed.sh run gleif --trigger schedule >> $HOME/drishti-feeds-logs/gleif.log 2>&1
```

**Linux, systemd** (a missed day runs at the next boot):

```bash
python3 tools/drishti.py feed schedule --systemd
```

```ini
# ---- drishti-feed-gleif.service
[Unit]
Description=Drishti data feed gleif: GLEIF legal entities, ownership and identifier mappings
Wants=network-online.target
After=network-online.target

[Service]
Type=oneshot
WorkingDirectory=/opt/drishti
ExecStart=/opt/drishti/tools/feeds/feed.sh run gleif --trigger schedule
Nice=10

# ---- drishti-feed-gleif.timer
[Unit]
Description=Run the Drishti data feed gleif daily

[Timer]
OnCalendar=*-*-* 05:30:00
Persistent=true
RandomizedDelaySec=120

[Install]
WantedBy=timers.target
```

Save each block under `~/.config/systemd/user/`, then `systemctl --user daemon-reload && systemctl --user enable --now
drishti-feed-gleif.timer`; `journalctl --user -u drishti-feed-gleif` shows each run's log line.

**Windows, Task Scheduler** (run the printed commands in a command prompt as the user the feeds run as; `--root` names the Drishti
folder on that machine when you print them elsewhere):

```bash
python3 tools/drishti.py feed schedule --windows --root 'C:\drishti'
```

```text
schtasks /Create /F /TN "Drishti feed gleif" /SC DAILY /ST 05:30 /TR "powershell.exe -NoProfile -ExecutionPolicy Bypass -File \"C:\drishti\tools\windows\feed.ps1\" run gleif --trigger schedule"
```

Task Scheduler's *Last Run Result* is the exit code (section 3.1). One task for every feed instead: `feed.ps1 run --all` (the feeds run
one after another in the order of their schedule times). `--trigger schedule` only marks the run in the ledger as the schedule's.

## 6. Monitor: status, the ledger, Admin → Feeds, alerts

```bash
python3 tools/drishti.py feed status
```

```text
FEED   LAST RUN             STATUS  LAST SUCCESS         DATE        ROWS   SECONDS  FAILURES
gleif  2026-11-07T05:30:12  ok      2026-11-07T05:30:12  2026-11-07  4,377  41.2     0
ledger: data/feeds/ledger.jsonl
```

`--runs N` adds the last N runs; `--json` gives the same for scripts. It exits 1 when a feed's last run failed.

**The ledger**, `data/feeds/ledger.jsonl`, has one JSON line per run, kept for ever:

```json
{"at": "2026-11-07T05:30:12-05:00", "feed": "gleif", "run": "20261107T103012-drishti-main-41822", "trigger": "schedule",
 "businessDate": "2026-11-07", "since": null, "lake": "referencelake", "status": "ok", "exit": 0, "files": 3, "rows": 4377,
 "dates": ["2026-11-07"], "datasets": {"lei-entity": {"rows": 1204, "table": "lei-entity"}},
 "load": {"route": "java", "status": "ok", "rows": 4377, "rejected": 0, "count": 3}, "licence": "CC0 1.0 (attribution appreciated)",
 "internalOnly": false, "seconds": 41.2}
```

**Admin → Data loads → Feeds** (`/admin/loads/feeds`, administrators) shows the same from the server's side: every feed with its lake,
schedule, last run and status, last success with its business date and rows, the duration, the failures since the last success, the
last error in words and the licence (internal only marked), then the latest runs of the ledger. Both tables are console tables (sort,
filter, keyboard). **Run now** starts `tools/drishti.py feed run <feed> --trigger admin` on the server's machine in the background,
with the server's data folder (its output in `data/feeds/<feed>/admin-run.log`); while a run of the feed holds its lock it says so
instead. The same over REST: `GET /api/v1/admin/feeds`, `POST /api/v1/admin/feeds/{feed}/run` (audited `feed.run`). The server finds
the feed files, the tools and Python by `drishti.feeds.config`, `drishti.feeds.root` and `drishti.feeds.python`
([CONFIGURATION.md](../admin/CONFIGURATION.md)).

**Alerts.** A failed (or refused, or partial) last run sets the gauge `drishti.datafeed.failed{feed}` to 1, and the platform alert rule
`data-feed-failed` (`config/alerts/platform-alerts.yaml`) opens an alert in the one alert engine: the administrators are told in their
inbox, Admin → Health and Alerts list it, and it closes by itself at the feed's next successful run. The server reads the ledger every
`drishti.feeds.sample-seconds` (60), so a failure of a cron run is seen as surely as one of Run now. The same rule for a site's
Prometheus is `DrishtiDataFeedFailed` (`deploy/prometheus/drishti-alerts.yaml`).

## 7. The feed file, every key

`config/feeds/<feed>.yaml`, named after the feed. An unknown key is refused, naming the keys there are.

```yaml
feed: gleif                            # the name: lower case, digits, dashes; the file's name
title: GLEIF legal entities, ownership and identifier mappings
description: >-                        # optional: what it holds, in a sentence or two
  The Global LEI Foundation's golden copy ...
class: gleif.GleifFeed                 # <module>.<Class>: tools/feeds/sources/<module>.py (or a module beside the feed file)
source:
  provider: GLEIF
  url: https://www.gleif.org
  pause: 1.0                           # seconds between requests (default 0.5): the rate limit
  retries: 3                           # retries after 2, 4, 8 s on 429, 5xx, network errors (default 3)
  timeout: 90                          # seconds a request may take
  cache-hours: 20                      # a download younger than this is reused (default 12)
lake: referencelake                    # the lake it loads (config/lakes/<lake>.yaml)
load: java                             # java | python | inbox | none (default java)
enabled: true                          # false: left out of --all and the schedule
schedule: {at: "05:30", days: daily}   # HH:MM local time; daily | weekdays
licence:
  name: CC0 1.0 (attribution appreciated)
  url: https://www.gleif.org/en/meta/lei-data-terms-of-use
  redistribution: allowed              # allowed | attribution | internal-only
  internal-only: false                 # true: never redistributed (section 9)
  attribution: "Contains LEI data from GLEIF, CC0 1.0"
keys:                                  # API keys: environment variables only, never values
  api_key: ${FRED_API_KEY}             # required: the run exits 2 when it is not set
  token: {env: "${SOME_TOKEN}", required: false}
history: {first-run-days: 30}          # the first run, without a state, pulls this many days
datasets:                              # each: the rows' pack and kind, and the table they load into
  lei-entity:       {pack: gleif, kind: lei-entity, key: LEI}
  lei-relationship: {pack: gleif, kind: lei-relationship, key: RELATIONSHIP_ID}
  lei-mapping:      {pack: gleif, kind: lei-mapping, key: IDENTIFIER, table: lei-mapping, dated: run}
options:                               # free settings the feed's class reads with run.option(name); --set NAME=VALUE overrides
  args: []
snapshot:                              # optional: what --snapshot does (args for the source; a note)
  note: refreshes config/packs/gleif and data/testdata/gleif
```

| Dataset key | Means |
|---|---|
| `pack`, `kind` | the pack and kind of the rows: their layout, id and contract (required) |
| `table` | the table (and the files' name); default the kind, as the demo lake names its tables |
| `key` | the rows' id field: given to the load (`--key`), so a lake entry without an id takes it; an entry with one must agree |
| `dated` | `run` (default): every row under the run's business date (a snapshot such as GLEIF's); `source`: the date the feed gives each emit (a time series) |
| `description` | a line for `feed list --json` and the docs |

## 8. Writing a feed

A feed is a feed file (section 7) and one class. The class pulls; the base class does everything else: the cache, the retries, the
dated files and their manifest, the state, the lock, the ledger, the load.

```python
# tools/feeds/sources/worldbank.py
from feedbase import Feed, FeedError

BASE = "https://api.worldbank.org/v2"


class WorldBank(Feed):
    def plan(self, run):                                       # what --dry-run prints
        start, end = run.window("indicator")
        return [f"{BASE}/country/all/indicator/{c}?date={start.year}:{end.year}" for c in run.option("indicators", [])]

    def pull(self, run):
        start, end = run.window("indicator")                   # --since; else the day after the state's last date; else history
        for code in run.option("indicators", []):
            doc = run.get_json(f"{BASE}/country/all/indicator/{code}?date={start.year}:{end.year}&format=json&per_page=20000",
                               name=f"wdi-{code}-{start.year}-{end.year}.json")
            if doc is None:
                raise FeedError(f"{code}: no answer")          # a failed request also fails the run by itself
            rows = [{"INDICATOR": code, "COUNTRY": x["countryiso3code"], "YEAR": int(x["date"]), "VALUE": x["value"]}
                    for x in doc[1] or [] if x.get("value") is not None]
            run.emit("indicator", rows)                        # under the run's business date (dated: run)
        run.mark("indicator", end)                             # saved only when the whole run succeeds
```

```yaml
# config/feeds/worldbank.yaml
feed: worldbank
title: World Bank World Development Indicators
class: worldbank.WorldBank
source: {provider: World Bank, url: "https://data.worldbank.org", pause: 0.5}
lake: worldlake
schedule: {at: "06:00", days: daily}
licence: {name: CC BY 4.0, url: "https://www.worldbank.org/en/about/legal/terms-of-use-for-datasets", redistribution: attribution,
          attribution: "The World Bank: World Development Indicators"}
datasets:
  indicator: {pack: world-economy, kind: indicator, key: INDICATOR}
options:
  indicators: [NY.GDP.MKTP.CD, SP.POP.TOTL]
```

**The API** (stable; `tools/feeds/feedbase.py`):

| Call | Gives |
|---|---|
| `run.business_date` | the run's business date (`--date`, else today) |
| `run.since` | `--since`, else `None` |
| `run.window(dataset)` | `(first, last)` dates to pull: `--since`; else the day after the dataset's last date in the state; else `last - history.first-run-days` |
| `run.option(name, default)` | the feed file's `options:` value, overridden by `--set name=value` (JSON values) |
| `run.key(name)` | a key's value from its environment variable; exits the run with 2 when a required one is not set |
| `run.get_json(url, name=None, headers=None)` | the parsed JSON, through the cache (`name`: its file name in the cache; default a hash of the URL); `None` after the retries |
| `run.get_text(...)`, `run.get_bytes(...)` | the same as text or bytes (CSV, XML, RSS, zip) |
| `run.emit(dataset, rows, business_date=None)` | rows (dicts) of a dataset of the feed file, appended to that date's file; returns how many |
| `run.mark(dataset, last)` | records the last business date pulled (saved with the state when the run succeeds) |
| `run.state` | the feed's `state.json` as a dict, for anything more (a cursor, an ETag); saved only on success |
| `run.log(text)` | a line to the run's output |
| `run.dry_run`, `run.offline` | what the command line asked |

A class may also implement `check(run)` (problems with what it wrote, in words: the run fails with them) and `snapshot(run)` (for
`--snapshot`). Rules that keep feeds alike:

- **JSON Lines only**, contract fields only: the rows are what the pack's kind contract says, nothing Drishti-internal.
- **Keys only via `run.key()`**: never logged, never written; pass them in `headers=` (the cache never stores request headers).
- **Pull, do not load.** A feed never writes a lake or the inbox itself; the route is the operator's choice.
- **Recorded fixtures for tests** (section 10), never the network.

## 9. Licences and internal-only data

Every feed file records its source's licence: its name, its terms' URL, whether the data may be redistributed and whether it is for
internal use only. It is copied into every date's `manifest.json` and every ledger line, and `feed list` shows it. A feed marked
`internal-only: true` (Yahoo's prices, the IMF's forecasts, news headlines) loads into the bank's own lakes like any other, which are
never committed or shipped; `--snapshot` writes it into the repository only with `--internal-ok`, for internal demos.

## 10. Tests

`tools/feeds/tests/` runs every feed against **recorded responses** (`tools/feeds/tests/fixtures/`), never the network: the feed file's
checks, a pull with its dated files, manifest and state, the next run pulling only what is new, an idempotent re-run, retries with
back-off, the lock, the ledger and status, the inbox route's `.ready` and tar, the java and python routes into a temporary lake (with the
server's jar), the command line and the schedule output. One `--live` smoke per feed is for a person to run by hand:

```bash
python3 -m pytest tools/feeds/tests/test_live.py --live -k gleif        # the real source, into a scratch data folder
```
