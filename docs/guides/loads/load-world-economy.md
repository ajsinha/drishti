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
# Load the world economy every day

Economies and their macro indicators, IMF forecasts, labour and fiscal data, governments, parties, office holders and elections: the World Bank, the IMF outlook, UNDP and the CIA World Factbook. One command pulls the source, writes the day's files under `<data>/datafeeds/world-economy/<date>/` and loads them into the lake **`worldlake`**, replacing only the business date it pulled. Feed `world-economy`, table(s) `economy`, `macro-indicator`, `economic-forecast`, `labour-market`, `fiscal-position`, `region-economy`, `jurisdiction`, `party`, `office-holder`, `election`, `region`, `social-indicator`. Licence: World Bank CC BY 4.0; IMF terms (no commercial reuse without permission); UNDP CC BY 3.0 IGO; Factbook public domain (**internal only**).

Run every command from the Drishti folder (the one that holds `tools`): `/opt/drishti` on Linux and `C:\drishti` on Windows in the examples. Nothing is installed for you and there is no virtual environment: use the Python you run Drishti with. The load needs Java 21 and the server jar in `lib` (`drishti.py build jar`); the server does not have to be running.

## Every day

```text
Linux     cd /opt/drishti && python3.13 tools/drishti.py feed run world-economy
Windows   cd C:\drishti && py -3.13 tools\drishti.py feed run world-economy
```

Look before pulling (changes nothing; prints the requests it would make):

```text
Linux     python3.13 tools/drishti.py feed run world-economy --dry-run
Windows   py -3.13 tools\drishti.py feed run world-economy --dry-run
```

**What you see.** The dry run (a real output of this page's author) prints the requests the run would make and ends with its closing line:

```text
dry run: feed world-economy (World economy (World Bank, IMF, UNDP, CIA World Factbook)), business date 2026-11-27, window 2026-10-28..2026-11-27, datasets economy, macro-indicator, economic-forecast, labour-market, fiscal-position
...
2026-11-27T19:40:52-05:00 feed dry-run exit=0 feed=world-economy date=2026-11-27 files=0 rows=0 seconds=0.3
```

The run reads four sources (the World Bank API, the IMF outlook files, UNDP and the Factbook), a few minutes. The real run therefore was not repeated for this page; it ends with the same closing line as every feed, with the counts of the day:

```text
  loaded economy 2026-11-27: ... rows into lake worldlake (java)
2026-11-27T06:55:30-05:00 feed ok exit=0 feed=world-economy date=2026-11-27 files=12 rows=... load=java loaded=... seconds=...
```

`exit=0` is done. The line is also appended to `<data>/datafeeds/ledger.jsonl` (Admin → Data loads → Feeds shows it). A server that is running notices the new date within its refresh time; to tell it at once add `--signal http://localhost:18480 --token-file FILE` (a token with `loads:write`).

## Schedule it

The feed's own time is 06:45 local, daily (`config/feeds/world-economy.yaml`, `schedule:`). `--trigger schedule` only marks the ledger line as the scheduler's.

**Linux cron** (`mkdir -p $HOME/drishti-feeds-logs` once, then `crontab -e` and paste):

```text
SHELL=/bin/bash
DRISHTI_FEEDS_USER_AGENT=Example Bank market data team marketdata@bank.example
45 6 * * * cd /opt/drishti && python3.13 tools/drishti.py feed run world-economy --trigger schedule >> $HOME/drishti-feeds-logs/world-economy.log 2>&1
```

Put `SHELL`, the environment and the working folder in the crontab itself: cron starts with an empty environment, so a variable you exported in your login shell is not there. Use your own organisation's contact in `DRISHTI_FEEDS_USER_AGENT`: the SEC and others require a real one. `python3.13 tools/drishti.py feed schedule --cron world-economy` prints the same line for this machine (with the interpreter's full path); `--systemd` prints a service and a timer instead (a missed day runs at the next boot).

**Windows Task Scheduler**, from a command prompt as the user the job runs as:

```text
setx DRISHTI_FEEDS_USER_AGENT "Example Bank market data team marketdata@bank.example"
mkdir C:\drishti-logs
schtasks /Create /F /TN "Drishti load world-economy" /SC DAILY /ST 06:45 /TR "cmd /c cd /d C:\drishti && py -3.13 tools\drishti.py feed run world-economy --trigger schedule >> C:\drishti-logs\world-economy.log 2>&1"
schtasks /Run /TN "Drishti load world-economy"
schtasks /Query /TN "Drishti load world-economy" /V /FO LIST
```

`setx` takes effect in a new window. The task's *Last Run Result* is the exit code (0 ok, 1 failed, 2 refused, 3 locked, 4 partial). By hand instead: Task Scheduler → Create Task → *Triggers* New → Daily, 06:45 → *Actions* New → Start a program `py`, arguments `-3.13 tools\drishti.py feed run world-economy --trigger schedule`, **Start in** `C:\drishti` → *Run whether user is logged on or not*. On the Windows machine `py -3.13 tools\drishti.py feed schedule --windows --root C:\drishti world-economy` prints the `schtasks` line for you.

## When a day fails or is late

the world economy is pulled as a **snapshot**: every run reads the source's current state and files it under the business date. There is no gap to fill after a missed day.

- **Run it again** (failed, or you want fresher data): the same command. The business date's files and its rows in `worldlake` are replaced **whole**; other dates are untouched.
- **A missed day:** nothing to catch up. Run today's; to have the day filed anyway, `feed run world-economy --date 2026-11-24` writes the source's current state under that date.
- **Several days:** one run is enough. Filing the same snapshot under several dates is possible (`--date` once per date, oldest first) but gives the same rows.
- **Take a date out:** `python3.13 tools/drishti.py lake delete --lake worldlake --table world-economy/economy --date 2026-11-24 --dry-run`, then again without `--dry-run` (asks to confirm; `--yes` for a script).
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
Linux     python3.13 tools/drishti.py feed status world-economy --runs 3
Windows   py -3.13 tools\drishti.py feed status world-economy --runs 3
```

It exits 1 when the feed's last run failed and shows the failures since the last success; a failed last run raises the alert `data-feed-failed`, which clears at the next success. Then the rows and dates in the lake:

```text
Linux     python3.13 tools/drishti.py lake status --lake worldlake
Windows   py -3.13 tools\drishti.py lake status --lake worldlake
```

It lists each table of the lake with its rows, files, business dates and last build; expect the `world-economy/economy`, `world-economy/macro-indicator`, `world-economy/economic-forecast`, `world-economy/labour-market`, `world-economy/fiscal-position`, `world-economy/region-economy`, `world-economy/jurisdiction`, `world-economy/party`, `world-economy/office-holder`, `world-economy/election`, `world-economy/region`, `world-economy/social-indicator` rows on today's date.

From a running server: `drishti.py dataload status` (waiting files, failures with reasons; the inbox route), `python3.13 tools/drishti.py data get economy --date 2026-11-27 --count` (rows of a kind on a date through the API; add `--server URL --token-file FILE`) and **Admin → Data loads → Feeds**, which shows the status, the last error in words and **Run now**. `drishti.py doctor` checks Java, the jar, the Python libraries and the folders (green, yellow, red; exit 1 on a red).

## Reference

**What it writes.** Lake `worldlake`; symbolic table names `world-economy/economy`, `world-economy/macro-indicator`, `world-economy/economic-forecast`, `world-economy/labour-market`, `world-economy/fiscal-position`, `world-economy/region-economy`, `world-economy/jurisdiction`, `world-economy/party`, `world-economy/office-holder`, `world-economy/election`, `world-economy/region`, `world-economy/social-indicator`. The data contract of every field: [the world-economy contract page](../../contracts/packs/world-economy.md). The day's files are `<data>/datafeeds/world-economy/<date>/<table>.<date>.jsonl.gz` with a `manifest.json` (licence, SHA-256 and row count of each file). Settings, datasets and options: [`config/feeds/world-economy.md`](../../../config/feeds/world-economy.md) (generated from `config/feeds/world-economy.yaml`).

**Input and date rules.** Nothing to prepare. One run reads World Bank (CC BY 4.0), the IMF World Economic Outlook, UNDP and the CIA World Factbook for the years 2015 to 2025 and writes the twelve tables. **The IMF part is internal only** (see below): the whole feed is flagged internal and never goes into a committed folder.

**Source and licence.** World Bank (CC BY 4.0), IMF World Economic Outlook (IMF terms: not for redistribution), UNDP, CIA World Factbook (public domain). **Internal only: never redistribute** (the feed refuses to write it into a committed folder without `--internal-ok`).

**Errors and fixes.**

| You see | It means | Do this |
|---|---|---|
| exit 2 *internal-only ... committed folder* | an attempt to write the IMF data into the repository (`--snapshot`) | load into the data folder (the default); `--internal-ok` only for an internal demo |
| *no server jar in .../lib* or *Unbuilt* | the load needs the server jar | `python3.13 tools/drishti.py build jar` |
| HTTP 429, 5xx or a timeout in the log | the source is rate limiting or down | nothing: it retries after 2, 4, 8 s; run again later (it resumes from its cache) |
| *refused: ... committed folder ... may not be redistributed* | internal-only data headed into the repository | leave the default (the data folder); `--internal-ok` only with `--snapshot`, for an internal demo |
| fewer rows loaded than pulled | the load rejected rows | `dataload status` and the run's load report give the reason per row |

More: [tools/loaders/external/README.md](../../../tools/loaders/external/README.md) (the loader README), [WORLD_DATA_GUIDE.md](../WORLD_DATA_GUIDE.md) (the data, in depth) and [the feeds' engine](../../../tools/loaders/common/README.md).
