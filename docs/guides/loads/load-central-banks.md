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
# Load central bank rates every day

Policy rates and market series of the central banks: the Federal Reserve through FRED and the Bank of Canada. One command pulls the source, writes the day's files under `<data>/datafeeds/central-banks/<date>/` and loads them into the lake **`worldlake`**, replacing only the business date it pulled. Feed `central-banks`, table(s) `central-bank`, `rate-series`. Licence: FRED API terms (the default series: US government works, public domain); Bank of Canada terms (free use with attribution).

Run every command from the Drishti folder (the one that holds `tools`): `/opt/drishti` on Linux and `C:\drishti` on Windows in the examples. Nothing is installed for you and there is no virtual environment: use the Python you run Drishti with. The load needs Java 21 and the server jar in `lib` (`drishti.py build jar`); the server does not have to be running.

## Every day

```text
Linux     cd /opt/drishti && python3.13 tools/drishti.py feed run central-banks
Windows   cd C:\drishti && py -3.13 tools\drishti.py feed run central-banks
```

Look before pulling (changes nothing; prints the requests it would make):

```text
Linux     python3.13 tools/drishti.py feed run central-banks --dry-run
Windows   py -3.13 tools\drishti.py feed run central-banks --dry-run
```

**What you see when it worked** (a real run of this page's author, into a scratch data folder; the last line is the one a scheduler or a person reads):

```text
  FRED left out: set DRISHTI_FRED_API_KEY (a free key: https://fred.stlouisfed.org/docs/api/api_key.html); the Bank of Canada runs
  loaded central-bank 2026-11-27: 2 rows into lake worldlake (java)
  loaded rate-series 2026-11-27: 5 rows into lake worldlake (java)
2026-11-27T19:43:35-05:00 feed ok exit=0 feed=central-banks date=2026-11-27 files=2 rows=7 load=java loaded=7 seconds=10.7
```

`exit=0` is done. The line is also appended to `<data>/datafeeds/ledger.jsonl` (Admin → Data loads → Feeds shows it). A server that is running notices the new date within its refresh time; to tell it at once add `--signal http://localhost:18480 --token-file FILE` (a token with `loads:write`).

## Schedule it

The feed's own time is 18:15 local, weekdays (`config/feeds/central-banks.yaml`, `schedule:`). `--trigger schedule` only marks the ledger line as the scheduler's.

**Linux cron** (`mkdir -p $HOME/drishti-feeds-logs` once, then `crontab -e` and paste):

```text
SHELL=/bin/bash
DRISHTI_FEEDS_USER_AGENT=Example Bank market data team marketdata@bank.example
15 18 * * 1-5 cd /opt/drishti && python3.13 tools/drishti.py feed run central-banks --trigger schedule >> $HOME/drishti-feeds-logs/central-banks.log 2>&1
```

Put `SHELL`, the environment and the working folder in the crontab itself: cron starts with an empty environment, so a variable you exported in your login shell is not there. Use your own organisation's contact in `DRISHTI_FEEDS_USER_AGENT`: the SEC and others require a real one. `python3.13 tools/drishti.py feed schedule --cron central-banks` prints the same line for this machine (with the interpreter's full path); `--systemd` prints a service and a timer instead (a missed day runs at the next boot).

`DRISHTI_FRED_API_KEY` (optional) goes into the crontab as `DRISHTI_FRED_API_KEY=...` and on Windows into `setx DRISHTI_FRED_API_KEY "..."`; keep it out of any file in the repository.

**Windows Task Scheduler**, from a command prompt as the user the job runs as:

```text
setx DRISHTI_FEEDS_USER_AGENT "Example Bank market data team marketdata@bank.example"
mkdir C:\drishti-logs
schtasks /Create /F /TN "Drishti load central-banks" /SC WEEKLY /D MON,TUE,WED,THU,FRI /ST 18:15 /TR "cmd /c cd /d C:\drishti && py -3.13 tools\drishti.py feed run central-banks --trigger schedule >> C:\drishti-logs\central-banks.log 2>&1"
schtasks /Run /TN "Drishti load central-banks"
schtasks /Query /TN "Drishti load central-banks" /V /FO LIST
```

`setx` takes effect in a new window. The task's *Last Run Result* is the exit code (0 ok, 1 failed, 2 refused, 3 locked, 4 partial). By hand instead: Task Scheduler → Create Task → *Triggers* New → Weekly, Monday to Friday, 18:15 → *Actions* New → Start a program `py`, arguments `-3.13 tools\drishti.py feed run central-banks --trigger schedule`, **Start in** `C:\drishti` → *Run whether user is logged on or not*. On the Windows machine `py -3.13 tools\drishti.py feed schedule --windows --root C:\drishti central-banks` prints the `schtasks` line for you.

## When a day fails or is late

central bank rates is pulled **when the source has something new**: the run compares with `<data>/datafeeds/central-banks/state.json` and, when there is nothing newer, ends `exit=0 rows=0` with a line saying so. That is a good day, not a failure.

- **Run it again** (a failed run): the same command; `state.json` is saved only when the whole run succeeded, and downloads are cached.
- **A missed day or several:** `feed run central-banks --since 2026-11-20` re-reads the source from that date to today. (`--date` alone does not re-read a source that has nothing newer.)
- **Replace a day:** `feed run central-banks --since DATE`: the business date it writes is replaced **whole**, in every table of the feed, so give the same window as your daily run, not a narrower one.
- **Take a date out:** `python3.13 tools/drishti.py lake delete --lake worldlake --table centralbank/central-bank --date 2026-11-24 --dry-run`, then again without `--dry-run` (asks to confirm; `--yes` for a script).
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
Linux     python3.13 tools/drishti.py feed status central-banks --runs 3
Windows   py -3.13 tools\drishti.py feed status central-banks --runs 3
```

```text
FEED                 LAST RUN             STATUS   LAST SUCCESS         DATE        ROWS  SECONDS  FAILURES
central-banks        2026-11-27T19:49:19  ok       2026-11-27T19:49:19  2026-11-27  7     8.2      0
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

From a running server: `drishti.py dataload status` (waiting files, failures with reasons; the inbox route), `python3.13 tools/drishti.py data get central-bank --date 2026-11-27 --count` (rows of a kind on a date through the API; add `--server URL --token-file FILE`) and **Admin → Data loads → Feeds**, which shows the status, the last error in words and **Run now**. `drishti.py doctor` checks Java, the jar, the Python libraries and the folders (green, yellow, red; exit 1 on a red).

## Reference

**What it writes.** Lake `worldlake`; symbolic table names `centralbank/central-bank`, `centralbank/rate-series`. The data contract of every field: [the central-banks contract page](../../contracts/packs/central-banks.md). The day's files are `<data>/datafeeds/central-banks/<date>/<table>.<date>.jsonl.gz` with a `manifest.json` (licence, SHA-256 and row count of each file). Settings, datasets and options: [`config/feeds/central-banks.md`](../../../config/feeds/central-banks.md) (generated from `config/feeds/central-banks.yaml`).

**Input and date rules.** Nothing to prepare. The Bank of Canada series need no key; the FRED series need a free key in the environment, `DRISHTI_FRED_API_KEY` (https://fred.stlouisfed.org/docs/api/api_key.html): a key written in a file is refused, and without it the run says *FRED left out* and loads the Bank of Canada alone. Each run asks only for observations newer than the last successful run.

**A real example** (a `rate-series` row of this page's author's run (the Bank of Canada overnight target); the first row of the file, long values cut):

```json
{"CENTRAL_BANK": "CBANK-BOC", "CHANGE": 0.0, "CHANGE_1Y": 0.25, "CODE": "V39079", "DESCRIPTION": "Also called the policy interest rate, the average rate t...", "FREQUENCY": "Business daily", "HIGH": 3.75, "HISTORY": [{"DATE": "2024-11-27", "VALUE": 3.75}, {"DATE": "2024-11-28", "VALUE": 3.75}], "LATEST": 2.5, "LATEST_DATE": "2026-11-26", "LOW": 2.25, "NAME": "Bank of Canada target for the overnight rate"}
```

**Source and licence.** FRED (Federal Reserve Bank of St. Louis) and the Bank of Canada Valet API; each series keeps its owner's terms, attribution required. Redistribution: attribution. Attribution: Source: FRED, Federal Reserve Bank of St. Louis; Bank of Canada.

**Errors and fixes.**

| You see | It means | Do this |
|---|---|---|
| *FRED left out: set DRISHTI_FRED_API_KEY* | no FRED key in this window's environment | set the variable where the job runs (`setx` on Windows, a `NAME=value` line in the crontab); the Bank of Canada part loaded anyway |
| exit 2 *key ... not set* | a feed that requires its key | set the variable the message names |
| *no server jar in .../lib* or *Unbuilt* | the load needs the server jar | `python3.13 tools/drishti.py build jar` |
| HTTP 429, 5xx or a timeout in the log | the source is rate limiting or down | nothing: it retries after 2, 4, 8 s; run again later (it resumes from its cache) |
| *refused: ... committed folder ... may not be redistributed* | internal-only data headed into the repository | leave the default (the data folder); `--internal-ok` only with `--snapshot`, for an internal demo |
| fewer rows loaded than pulled | the load rejected rows | `dataload status` and the run's load report give the reason per row |

More: [tools/loaders/external/README.md](../../../tools/loaders/external/README.md) (the loader README), [WORLD_DATA_GUIDE.md](../WORLD_DATA_GUIDE.md) (the data, in depth) and [the feeds' engine](../../../tools/loaders/common/README.md).
