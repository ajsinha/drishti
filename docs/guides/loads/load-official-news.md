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
# Load official press releases every day

Press releases of central banks and regulators: the Fed, the SEC, the US Federal Register, the ECB, the Bank of Canada, the Bank of England, OSFI and the Canada Gazette, by RSS or API. One command pulls the source, writes the day's files under `<data>/datafeeds/official-news/<date>/` and loads them into the lake **`newslake`**, replacing only the business date it pulled. Feed `official-news`, table(s) `release-source`, `press-release`. Licence: per publisher (options.sources): public domain, reuse with attribution, or internal only (Bank of England, OSFI, Canada Gazette).

Run every command from the Drishti folder (the one that holds `tools`): `/opt/drishti` on Linux and `C:\drishti` on Windows in the examples. Nothing is installed for you and there is no virtual environment: use the Python you run Drishti with. The load needs Java 21 and the server jar in `lib` (`drishti.py build jar`); the server does not have to be running.

## Every day

```text
Linux     cd /opt/drishti && python3.13 tools/drishti.py feed run official-news
Windows   cd C:\drishti && py -3.13 tools\drishti.py feed run official-news
```

Look before pulling (changes nothing; prints the requests it would make):

```text
Linux     python3.13 tools/drishti.py feed run official-news --dry-run
Windows   py -3.13 tools\drishti.py feed run official-news --dry-run
```

**What you see when it worked** (a real run of this page's author, into a scratch data folder; the last line is the one a scheduler or a person reads):

```text
  loaded press-release 2026-11-27: 196 rows into lake newslake (java)
  loaded release-source 2026-11-27: 9 rows into lake newslake (java)
2026-11-27T19:41:42-05:00 feed ok exit=0 feed=official-news date=2026-11-27 files=2 rows=205 load=java loaded=205 seconds=16.6
```

`exit=0` is done. The line is also appended to `<data>/datafeeds/ledger.jsonl` (Admin → Data loads → Feeds shows it). A server that is running notices the new date within its refresh time; to tell it at once add `--signal http://localhost:18480 --token-file FILE` (a token with `loads:write`).

Licence: the Bank of England, OSFI and the Canada Gazette items are marked internal only in their rows and in `manifest.json`: they load into your lake like the rest and must not leave your organisation.

## Schedule it

The feed's own time is 19:00 local, daily (`config/feeds/official-news.yaml`, `schedule:`). `--trigger schedule` only marks the ledger line as the scheduler's.

**Linux cron** (`mkdir -p $HOME/drishti-feeds-logs` once, then `crontab -e` and paste):

```text
SHELL=/bin/bash
DRISHTI_FEEDS_USER_AGENT=Example Bank market data team marketdata@bank.example
0 19 * * * cd /opt/drishti && python3.13 tools/drishti.py feed run official-news --trigger schedule >> $HOME/drishti-feeds-logs/official-news.log 2>&1
```

Put `SHELL`, the environment and the working folder in the crontab itself: cron starts with an empty environment, so a variable you exported in your login shell is not there. Use your own organisation's contact in `DRISHTI_FEEDS_USER_AGENT`: the SEC and others require a real one. `python3.13 tools/drishti.py feed schedule --cron official-news` prints the same line for this machine (with the interpreter's full path); `--systemd` prints a service and a timer instead (a missed day runs at the next boot).

**Windows Task Scheduler**, from a command prompt as the user the job runs as:

```text
setx DRISHTI_FEEDS_USER_AGENT "Example Bank market data team marketdata@bank.example"
mkdir C:\drishti-logs
schtasks /Create /F /TN "Drishti load official-news" /SC DAILY /ST 19:00 /TR "cmd /c cd /d C:\drishti && py -3.13 tools\drishti.py feed run official-news --trigger schedule >> C:\drishti-logs\official-news.log 2>&1"
schtasks /Run /TN "Drishti load official-news"
schtasks /Query /TN "Drishti load official-news" /V /FO LIST
```

`setx` takes effect in a new window. The task's *Last Run Result* is the exit code (0 ok, 1 failed, 2 refused, 3 locked, 4 partial). By hand instead: Task Scheduler → Create Task → *Triggers* New → Daily, 19:00 → *Actions* New → Start a program `py`, arguments `-3.13 tools\drishti.py feed run official-news --trigger schedule`, **Start in** `C:\drishti` → *Run whether user is logged on or not*. On the Windows machine `py -3.13 tools\drishti.py feed schedule --windows --root C:\drishti official-news` prints the `schtasks` line for you.

## When a day fails or is late

official press releases is pulled **when the source has something new**: the run compares with `<data>/datafeeds/official-news/state.json` and, when there is nothing newer, ends `exit=0 rows=0` with a line saying so. That is a good day, not a failure.

- **Run it again** (a failed run): the same command; `state.json` is saved only when the whole run succeeded, and downloads are cached.
- **A missed day or several:** `feed run official-news --since 2026-11-20` re-reads the source from that date to today. (`--date` alone does not re-read a source that has nothing newer.)
- **Replace a day:** `feed run official-news --since DATE`: the business date it writes is replaced **whole**, in every table of the feed, so give the same window as your daily run, not a narrower one.
- **Take a date out:** `python3.13 tools/drishti.py lake delete --lake newslake --table officialnews/release-source --date 2026-11-24 --dry-run`, then again without `--dry-run` (asks to confirm; `--yes` for a script).
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
Linux     python3.13 tools/drishti.py feed status official-news --runs 3
Windows   py -3.13 tools\drishti.py feed status official-news --runs 3
```

```text
FEED                 LAST RUN             STATUS   LAST SUCCESS         DATE        ROWS  SECONDS  FAILURES
official-news        2026-11-27T19:48:35  ok       2026-11-27T19:48:35  2026-11-27  205   8.3      0
```

It exits 1 when the feed's last run failed and shows the failures since the last success; a failed last run raises the alert `data-feed-failed`, which clears at the next success. Then the rows and dates in the lake:

```text
Linux     python3.13 tools/drishti.py lake status --lake newslake
Windows   py -3.13 tools\drishti.py lake status --lake newslake
```

```text
lake <scratch>/data/delta/newslake
tables (3):
  table                        rows  files  size      partitions  dates  latest date  last build
  gdelt/event-country          135   1      132.0 KB  1           1      2026-11-27   2026-11-28 00:48:35Z
  officialnews/press-release   196   1      76.3 KB   1           1      2026-11-27   2026-11-28 00:48:40Z
  officialnews/release-source  9     1      23.0 KB   1           1      2026-11-27   2026-11-28 00:48:44Z
lock: free
```

From a running server: `drishti.py dataload status` (waiting files, failures with reasons; the inbox route), `python3.13 tools/drishti.py data get release-source --date 2026-11-27 --count` (rows of a kind on a date through the API; add `--server URL --token-file FILE`) and **Admin → Data loads → Feeds**, which shows the status, the last error in words and **Run now**. `drishti.py doctor` checks Java, the jar, the Python libraries and the folders (green, yellow, red; exit 1 on a red).

## Reference

**What it writes.** Lake `newslake`; symbolic table names `officialnews/release-source`, `officialnews/press-release`. The data contract of every field: [the official-news contract page](../../contracts/packs/official-news.md). The day's files are `<data>/datafeeds/official-news/<date>/<table>.<date>.jsonl.gz` with a `manifest.json` (licence, SHA-256 and row count of each file). Settings, datasets and options: [`config/feeds/official-news.md`](../../../config/feeds/official-news.md) (generated from `config/feeds/official-news.yaml`).

**Input and date rules.** Nothing to prepare. The publishers are the `options.sources` of `config/feeds/official-news.yaml` (add, remove or mark one `internal-only` there, in a copy kept with your site's settings via `--config DIR`, not in code). `release-source` is one row per publisher (its feed, last pull, counts by category); `press-release` one row per release, dated by its publication.

**A real example** (a `press-release` row of this page's author's run (a Federal Reserve Board release); the first row of the file, long values cut):

```json
{"CATEGORY": "Banking and Consumer Regulatory Policy", "COUNTRY": "USA", "DATE": "2026-11-25", "INTERNAL_ONLY": false, "PROVENANCE": {"ATTRIBUTION": "Source: Board of Governors of the Federal Reserve System...", "DATASET": "Federal Reserve Board: press releases", "INTERNAL_ONLY": false, "LICENCE": "Public domain (US government work); cite the Board as th...", "RETRIEVED_AT": "2026-11-27"}, "PUBLISHED": "2026-11-25T20:00:00Z", "PUBLISHER_NAME": "Federal Reserve Board", "PUBLISHER_TYPE": "central bank", "RELEASE_ID": "PREL-FED-F0784A636C", "RELEASE_SOURCE": "PRESS-FED", ...}
```

**Source and licence.** Per publisher. Public domain: the Fed, the SEC and the Federal Register. Free with attribution: ECB, Bank of Canada. **Internal only**: Bank of England, OSFI, Canada Gazette Parts I and II. Redistribution: attribution. Attribution: Each release names its publisher (PROVENANCE.ATTRIBUTION).

**Errors and fixes.**

| You see | It means | Do this |
|---|---|---|
| one publisher's feed times out or returns nothing | that RSS source is down or moved | the other publishers still load; the closing line counts what was pulled; run again later |
| *no server jar in .../lib* or *Unbuilt* | the load needs the server jar | `python3.13 tools/drishti.py build jar` |
| HTTP 429, 5xx or a timeout in the log | the source is rate limiting or down | nothing: it retries after 2, 4, 8 s; run again later (it resumes from its cache) |
| *refused: ... committed folder ... may not be redistributed* | internal-only data headed into the repository | leave the default (the data folder); `--internal-ok` only with `--snapshot`, for an internal demo |
| fewer rows loaded than pulled | the load rejected rows | `dataload status` and the run's load report give the reason per row |

More: [tools/loaders/news/README.md](../../../tools/loaders/news/README.md) (the loader README), [WORLD_DATA_GUIDE.md](../WORLD_DATA_GUIDE.md) (the data, in depth) and [the feeds' engine](../../../tools/loaders/common/README.md).
