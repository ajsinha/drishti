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
# Load world development indicators every day

World Bank development indicators by country and region: population, health, education, energy and infrastructure. One command pulls the source, writes the day's files under `<data>/datafeeds/world-development/<date>/` and loads them into the lake **`worldlake`**, replacing only the business date it pulled. Feed `world-development`, table(s) `development-profile`, `development-series`. Licence: CC BY 4.0.

Run every command from the Drishti folder (the one that holds `tools`): `/opt/drishti` on Linux and `C:\drishti` on Windows in the examples. Nothing is installed for you and there is no virtual environment: use the Python you run Drishti with. The load needs Java 21 and the server jar in `lib` (`drishti.py build jar`); the server does not have to be running.

## Every day

```text
Linux     cd /opt/drishti && python3.13 tools/drishti.py feed run world-development
Windows   cd C:\drishti && py -3.13 tools\drishti.py feed run world-development
```

Look before pulling (changes nothing; prints the requests it would make):

```text
Linux     python3.13 tools/drishti.py feed run world-development --dry-run
Windows   py -3.13 tools\drishti.py feed run world-development --dry-run
```

**What you see when it worked** (a real run of this page's author, into a scratch data folder; the last line is the one a scheduler or a person reads):

```text
  loaded development-profile 2026-11-27: 217 rows into lake worldlake (java)
  loaded development-series 2026-11-27: 8729 rows into lake worldlake (java)
2026-11-27T19:51:39-05:00 feed ok exit=0 feed=world-development date=2026-11-27 files=2 rows=8,946 load=java loaded=8,946 seconds=55.4
```

`exit=0` is done. The line is also appended to `<data>/datafeeds/ledger.jsonl` (Admin → Data loads → Feeds shows it). A server that is running notices the new date within its refresh time; to tell it at once add `--signal http://localhost:18480 --token-file FILE` (a token with `loads:write`).

## Schedule it

The feed's own time is 06:30 local, daily (`config/feeds/world-development.yaml`, `schedule:`). `--trigger schedule` only marks the ledger line as the scheduler's.

**Linux cron** (`mkdir -p $HOME/drishti-feeds-logs` once, then `crontab -e` and paste):

```text
SHELL=/bin/bash
DRISHTI_FEEDS_USER_AGENT=Example Bank market data team marketdata@bank.example
30 6 * * * cd /opt/drishti && python3.13 tools/drishti.py feed run world-development --trigger schedule >> $HOME/drishti-feeds-logs/world-development.log 2>&1
```

Put `SHELL`, the environment and the working folder in the crontab itself: cron starts with an empty environment, so a variable you exported in your login shell is not there. Use your own organisation's contact in `DRISHTI_FEEDS_USER_AGENT`: the SEC and others require a real one. `python3.13 tools/drishti.py feed schedule --cron world-development` prints the same line for this machine (with the interpreter's full path); `--systemd` prints a service and a timer instead (a missed day runs at the next boot).

**Windows Task Scheduler**, from a command prompt as the user the job runs as:

```text
setx DRISHTI_FEEDS_USER_AGENT "Example Bank market data team marketdata@bank.example"
mkdir C:\drishti-logs
schtasks /Create /F /TN "Drishti load world-development" /SC DAILY /ST 06:30 /TR "cmd /c cd /d C:\drishti && py -3.13 tools\drishti.py feed run world-development --trigger schedule >> C:\drishti-logs\world-development.log 2>&1"
schtasks /Run /TN "Drishti load world-development"
schtasks /Query /TN "Drishti load world-development" /V /FO LIST
```

`setx` takes effect in a new window. The task's *Last Run Result* is the exit code (0 ok, 1 failed, 2 refused, 3 locked, 4 partial). By hand instead: Task Scheduler → Create Task → *Triggers* New → Daily, 06:30 → *Actions* New → Start a program `py`, arguments `-3.13 tools\drishti.py feed run world-development --trigger schedule`, **Start in** `C:\drishti` → *Run whether user is logged on or not*. On the Windows machine `py -3.13 tools\drishti.py feed schedule --windows --root C:\drishti world-development` prints the `schtasks` line for you.

## When a day fails or is late

world development indicators is pulled as a **snapshot**: every run reads the source's current state and files it under the business date. There is no gap to fill after a missed day.

- **Run it again** (failed, or you want fresher data): the same command. The business date's files and its rows in `worldlake` are replaced **whole**; other dates are untouched.
- **A missed day:** nothing to catch up. Run today's; to have the day filed anyway, `feed run world-development --date 2026-11-24` writes the source's current state under that date.
- **Several days:** one run is enough. Filing the same snapshot under several dates is possible (`--date` once per date, oldest first) but gives the same rows.
- **Take a date out:** `python3.13 tools/drishti.py lake delete --lake worldlake --table development/development-profile --date 2026-11-24 --dry-run`, then again without `--dry-run` (asks to confirm; `--yes` for a script).
- **No network:** `--offline` uses the download cache only (a run that cannot find what it needs says which file).
- **The load route:** `--load java` (default, the parallel loader), `python`, `inbox` (the server loads at its next cycle) or `none` (write the files only; look at them first).

Exit codes (a scheduler can alert on them):

| Exit | Means | Do this |
|---|---|---|
| 0 | ok | nothing |
| 1 | failed: the pull (after its retries), the feed's own check or the load | read the line it printed; run again |
| 2 | refused: a bad setting, a key not set, internal-only data headed for a committed folder | fix the setting the line names |
| 3 | another run of this feed holds its lock | wait, run again (a lock whose process is gone is taken over) |
| 4 | partial: the load rejected some rows | `feed status`, then the run's load report shows each reason |

## Check it loaded

```text
Linux     python3.13 tools/drishti.py feed status world-development --runs 3
Windows   py -3.13 tools\drishti.py feed status world-development --runs 3
```

It exits 1 when the feed's last run failed and shows the failures since the last success; a failed last run raises the alert `data-feed-failed`, which clears at the next success. Then the rows and dates in the lake:

```text
Linux     python3.13 tools/drishti.py lake status --lake worldlake
Windows   py -3.13 tools\drishti.py lake status --lake worldlake
```

```text
lake <scratch>/data/delta/worldlake
tables (7):
  table                            rows   files  size      partitions  dates  latest date  last build
  centralbank/central-bank         2      1      7.2 KB    1           1      2026-11-27   2026-11-28 00:49:24Z
  centralbank/rate-series          5      1      24.3 KB   1           1      2026-11-27   2026-11-28 00:49:27Z
  development/development-profile  217    1      103.3 KB  1           1      2026-11-27   2026-11-28 00:51:36Z
  development/development-series   8,729  1      4.6 MB    1           1      2026-11-27   2026-11-28 00:51:39Z
  oecd/oecd-country                34     1      11.2 KB   1           1      2026-11-27   2026-11-28 00:49:09Z
  oecd/oecd-series                 53     1      44.8 KB   1           1      2026-11-27   2026-11-28 00:49:13Z
  statistics/statistics-series     7      1      11.3 KB   1           1      2026-11-27   2026-11-28 00:49:19Z
lock: free
```

From a running server: `drishti.py dataload status` (waiting files, failures with reasons; the inbox route), `python3.13 tools/drishti.py data get development-profile --date 2026-11-27 --count` (rows of a kind on a date through the API; add `--server URL --token-file FILE`) and **Admin → Data loads → Feeds**, which shows the status, the last error in words and **Run now**. `drishti.py doctor` checks Java, the jar, the Python libraries and the folders (green, yellow, red; exit 1 on a red).

## Reference

**What it writes.** Lake `worldlake`; symbolic table names `development/development-profile`, `development/development-series`. The data contract of every field: [the world-development contract page](../../contracts/packs/world-development.md). The day's files are `<data>/datafeeds/world-development/<date>/<table>.<date>.jsonl.gz` with a `manifest.json` (licence, SHA-256 and row count of each file). Settings, datasets and options: [`config/feeds/world-development.md`](../../../config/feeds/world-development.md) (generated from `config/feeds/world-development.yaml`).

**Input and date rules.** Nothing to prepare. The feed lists the indicators of `config/feeds/world-development.yaml`, pulls each for every economy and writes `development-profile` (one row per economy) and `development-series` (one row per indicator and economy, with its history).

**A real example** (a `development-profile` row of this page's author's run; the first row of the file, long values cut):

```json
{"CAPITAL": "Kabul", "CENTRAL_GOVERNMENT_DEBT": null, "COUNTRY_NAME": "Afghanistan", "DATA_YEAR": "2025", "ELECTRICITY_ACCESS": 87.8, "GDP_GROWTH": 1.87319280045979, "GDP_PER_CAPITA": 416.871146273728, "INCOME_GROUP": "Low income", "INDICATORS": [{"CODE": "SP.POP.TOTL", "INDICATOR": "Population, total", "LATEST": 43844111.0, "UNIT": "persons", "WDI_SERIES": "WDIS-AFG-SP_POP_TOTL"}, {"CODE": "NY.GDP.PCAP.CD", "INDICATOR": "GDP per capita (current US$)", "LATEST": 416.871146273728, "UNIT": "current USD", "WDI_SERIES": "WDIS-AFG-NY_GDP_PCAP_CD"}], "INDICATOR_COUNT": 8, "INFLATION": -6.60118564073725, ...}
```

**Source and licence.** World Bank, World Development Indicators; CC BY 4.0, attribution: The World Bank. Redistribution: attribution. Attribution: The World Bank: World Development Indicators (CC BY 4.0).

**Errors and fixes.**

| You see | It means | Do this |
|---|---|---|
| *no server jar in .../lib* or *Unbuilt* | the load needs the server jar | `python3.13 tools/drishti.py build jar` |
| HTTP 429, 5xx or a timeout in the log | the source is rate limiting or down | nothing: it retries after 2, 4, 8 s; run again later (it resumes from its cache) |
| *refused: ... committed folder ... may not be redistributed* | internal-only data headed into the repository | leave the default (the data folder); `--internal-ok` only with `--snapshot`, for an internal demo |
| fewer rows loaded than pulled | the load rejected rows | `dataload status` and the run's load report give the reason per row |

More: [tools/loaders/external/README.md](../../../tools/loaders/external/README.md) (the loader README), [WORLD_DATA_GUIDE.md](../WORLD_DATA_GUIDE.md) (the data, in depth) and [the feeds' engine](../../../tools/loaders/common/README.md).
