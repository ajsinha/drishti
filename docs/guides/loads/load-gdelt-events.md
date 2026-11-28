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
# Load GDELT world events every day

The GDELT 1.0 daily event files reduced to events and tone by country and CAMEO category (about 7 MB zipped a day, streamed and never kept). One command pulls the source, writes the day's files under `<data>/datafeeds/gdelt-events/<date>/` and loads them into the lake **`newslake`**, replacing only the business date it pulled. Feed `gdelt-events`, table(s) `event-country`. Licence: GDELT terms of use (free for any use, with a citation).

Run every command from the Drishti folder (the one that holds `tools`): `/opt/drishti` on Linux and `C:\drishti` on Windows in the examples. Nothing is installed for you and there is no virtual environment: use the Python you run Drishti with. The load needs Java 21 and the server jar in `lib` (`drishti.py build jar`); the server does not have to be running.

## Every day

```text
Linux     cd /opt/drishti && python3.13 tools/drishti.py feed run gdelt-events
Windows   cd C:\drishti && py -3.13 tools\drishti.py feed run gdelt-events
```

Look before pulling (changes nothing; prints the requests it would make):

```text
Linux     python3.13 tools/drishti.py feed run gdelt-events --dry-run
Windows   py -3.13 tools\drishti.py feed run gdelt-events --dry-run
```

**What you see when it worked** (a real run of this page's author, into a scratch data folder; the last line is the one a scheduler or a person reads):

```text
  loaded event-country 2026-11-27: 135 rows into lake newslake (java)
2026-11-27T19:41:53-05:00 feed ok exit=0 feed=gdelt-events date=2026-11-27 files=1 rows=135 load=java loaded=135 seconds=10.8
```

`exit=0` is done. The line is also appended to `<data>/datafeeds/ledger.jsonl` (Admin → Data loads → Feeds shows it). A server that is running notices the new date within its refresh time; to tell it at once add `--signal http://localhost:18480 --token-file FILE` (a token with `loads:write`).

## Schedule it

The feed's own time is 04:30 local, daily (`config/feeds/gdelt-events.yaml`, `schedule:`). `--trigger schedule` only marks the ledger line as the scheduler's.

**Linux cron** (`mkdir -p $HOME/drishti-feeds-logs` once, then `crontab -e` and paste):

```text
SHELL=/bin/bash
DRISHTI_FEEDS_USER_AGENT=Example Bank market data team marketdata@bank.example
30 4 * * * cd /opt/drishti && python3.13 tools/drishti.py feed run gdelt-events --trigger schedule >> $HOME/drishti-feeds-logs/gdelt-events.log 2>&1
```

Put `SHELL`, the environment and the working folder in the crontab itself: cron starts with an empty environment, so a variable you exported in your login shell is not there. Use your own organisation's contact in `DRISHTI_FEEDS_USER_AGENT`: the SEC and others require a real one. `python3.13 tools/drishti.py feed schedule --cron gdelt-events` prints the same line for this machine (with the interpreter's full path); `--systemd` prints a service and a timer instead (a missed day runs at the next boot).

**Windows Task Scheduler**, from a command prompt as the user the job runs as:

```text
setx DRISHTI_FEEDS_USER_AGENT "Example Bank market data team marketdata@bank.example"
mkdir C:\drishti-logs
schtasks /Create /F /TN "Drishti load gdelt-events" /SC DAILY /ST 04:30 /TR "cmd /c cd /d C:\drishti && py -3.13 tools\drishti.py feed run gdelt-events --trigger schedule >> C:\drishti-logs\gdelt-events.log 2>&1"
schtasks /Run /TN "Drishti load gdelt-events"
schtasks /Query /TN "Drishti load gdelt-events" /V /FO LIST
```

`setx` takes effect in a new window. The task's *Last Run Result* is the exit code (0 ok, 1 failed, 2 refused, 3 locked, 4 partial). By hand instead: Task Scheduler → Create Task → *Triggers* New → Daily, 04:30 → *Actions* New → Start a program `py`, arguments `-3.13 tools\drishti.py feed run gdelt-events --trigger schedule`, **Start in** `C:\drishti` → *Run whether user is logged on or not*. On the Windows machine `py -3.13 tools\drishti.py feed schedule --windows --root C:\drishti gdelt-events` prints the `schtasks` line for you.

## When a day fails or is late

GDELT world events is pulled **by date**: a run reads the days after the last one it loaded (`<data>/datafeeds/gdelt-events/state.json`), so a missed day is picked up by the next run without any option. A second run on the same day finds nothing newer and ends `exit=0 rows=0` with a line saying so: that is a good day, not a failure.

- **Run it again** (a failed run, or a late source): the same command. `state.json` is saved only when the whole run succeeded, so a failed run asks for the same days again; downloads are cached, so it resumes.
- **A missed day or several:** `feed run gdelt-events --since 2026-11-20` re-reads the source from that date to today; the `loaded ... rows into lake` lines of the run name every business date it wrote. (`--date` alone does not re-read a source that has nothing newer: it answers *no new ... since the last run*.)
- **Replace a day:** `feed run gdelt-events --since DATE` for that day. Each business date it writes is replaced **whole**, in every table of the feed: give the same window and options as your daily run, because a narrower one loads fewer rows for the date.
- **Take a date out:** `python3.13 tools/drishti.py lake delete --lake newslake --table gdelt/event-country --date 2026-11-24 --dry-run`, then again without `--dry-run` (asks to confirm; `--yes` for a script).
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
Linux     python3.13 tools/drishti.py feed status gdelt-events --runs 3
Windows   py -3.13 tools\drishti.py feed status gdelt-events --runs 3
```

```text
FEED                 LAST RUN             STATUS   LAST SUCCESS         DATE        ROWS  SECONDS  FAILURES
gdelt-events         2026-11-27T19:48:31  ok       2026-11-27T19:48:31  2026-11-27  135   4.6      0
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

From a running server: `drishti.py dataload status` (waiting files, failures with reasons; the inbox route), `python3.13 tools/drishti.py data get event-country --date 2026-11-27 --count` (rows of a kind on a date through the API; add `--server URL --token-file FILE`) and **Admin → Data loads → Feeds**, which shows the status, the last error in words and **Run now**. `drishti.py doctor` checks Java, the jar, the Python libraries and the folders (green, yellow, red; exit 1 on a red).

## Reference

**What it writes.** Lake `newslake`; symbolic table names `gdelt/event-country`. The data contract of every field: [the gdelt-events contract page](../../contracts/packs/gdelt-events.md). The day's files are `<data>/datafeeds/gdelt-events/<date>/<table>.<date>.jsonl.gz` with a `manifest.json` (licence, SHA-256 and row count of each file). Settings, datasets and options: [`config/feeds/gdelt-events.md`](../../../config/feeds/gdelt-events.md) (generated from `config/feeds/gdelt-events.yaml`).

**Input and date rules.** Nothing to prepare. Each run reads the GDELT day files after the last one processed (a day's file is complete about 06:00 UTC the next morning, so the 04:30 local run may get yesterday) and writes one `event-country` row per country and day: events, mentions, tone, conflict and cooperation shares and a week of history. The files are named by their event day; the same date again replaces it whole.

**A real example** (an `event-country` row of this page's author's run (Afghanistan, event day 2026-11-26); the first row of the file, long values cut):

```json
{"AVG_GOLDSTEIN": -0.4407, "AVG_TONE": -3.7949, "AVG_TONE_WINDOW": -3.9284, "BY_CATEGORY": [{"AVG_TONE": -2.5015, "CATEGORY": "Consult", "CODE": "04", "EVENTS": 31}, {"AVG_TONE": -6.0469, "CATEGORY": "Fight", "CODE": "19", "EVENTS": 18}], "CONFLICT_SHARE": 33.79, "COOPERATION_SHARE": 66.21, "COUNTRY_NAME": "Afghanistan", "DATE": "2026-11-26", "EVENTS": 145, "EVENTS_WINDOW": 1040, "EVENT_COUNTRY_ID": "GDELT-AFG", "HISTORY": [{"AVG_TONE": -3.5178, "DATE": "2026-11-20", "EVENTS": 186}, {"AVG_TONE": -4.0033, "DATE": "2026-11-21", "EVENTS": 102}]}
```

**Source and licence.** The GDELT Project (https://www.gdeltproject.org); free for any use with a citation: "Source: The GDELT Project". Redistribution: attribution. Attribution: Source: The GDELT Project (https://www.gdeltproject.org/).

**Errors and fixes.**

| You see | It means | Do this |
|---|---|---|
| fewer days than expected | the newest day's file is not published yet | run again after about 06:00 UTC |
| *no new GDELT day since the last run*, rows=0 | everything published is already loaded | nothing: this is the normal answer to a second run |
| *no server jar in .../lib* or *Unbuilt* | the load needs the server jar | `python3.13 tools/drishti.py build jar` |
| HTTP 429, 5xx or a timeout in the log | the source is rate limiting or down | nothing: it retries after 2, 4, 8 s; run again later (it resumes from its cache) |
| *refused: ... committed folder ... may not be redistributed* | internal-only data headed into the repository | leave the default (the data folder); `--internal-ok` only with `--snapshot`, for an internal demo |
| fewer rows loaded than pulled | the load rejected rows | `dataload status` and the run's load report give the reason per row |

More: [tools/loaders/news/README.md](../../../tools/loaders/news/README.md) (the loader README), [WORLD_DATA_GUIDE.md](../WORLD_DATA_GUIDE.md) (the data, in depth) and [the feeds' engine](../../../tools/loaders/common/README.md).
