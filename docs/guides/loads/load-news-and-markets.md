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
# Load news and quotes (internal use only) every day

Google News headlines and Yahoo quotes with daily history for the listed companies and topics of the pack, through the news and yfinance connectors. One command pulls the source, writes the day's files under `<data>/datafeeds/news-and-markets/<date>/` and loads them into the lake **`newslake`**, replacing only the business date it pulled. Feed `news-and-markets`, table(s) `quote`, `news`. Licence: Yahoo terms of service (no redistribution); headlines belong to their publishers (**internal only**).

Run every command from the Drishti folder (the one that holds `tools`): `/opt/drishti` on Linux and `C:\drishti` on Windows in the examples. Nothing is installed for you and there is no virtual environment: use the Python you run Drishti with. The load needs Java 21 and the server jar in `lib` (`drishti.py build jar`); the server does not have to be running.

## Every day

```text
Linux     cd /opt/drishti && python3.13 tools/drishti.py feed run news-and-markets
Windows   cd C:\drishti && py -3.13 tools\drishti.py feed run news-and-markets
```

Look before pulling (changes nothing; prints the requests it would make):

```text
Linux     python3.13 tools/drishti.py feed run news-and-markets --dry-run
Windows   py -3.13 tools\drishti.py feed run news-and-markets --dry-run
```

**What you see.** The dry run (a real output of this page's author) prints the requests the run would make and ends with its closing line:

```text
dry run: feed news-and-markets (Listed companies' quotes and headlines (Yahoo Finance, Google News; internal use only)), business date 2026-11-27, window 2026-10-28..2026-11-27, datasets quote, news -> lake newslake by java
  dry run: news-and-markets, sources quote,news, history 1y, -> <data>/../data/datafeeds/news-and-markets/work/20261128T004048-oryxpro-3584600/build
...
  internal only: Yahoo terms of service (no redistribution); headlines belong to their publishers (https://legal.yahoo.com/us/en/yahoo/terms/otos/index.html)
2026-11-27T19:40:48-05:00 feed dry-run exit=0 feed=news-and-markets date=2026-11-27 files=0 rows=0 seconds=0.2
```

The run makes about 90 requests at least 2 seconds apart (3 to 4 minutes) and starts a scratch server from the jar for the connectors (or uses a running server whose news and yfinance connectors are on). The real run therefore was not repeated for this page; it ends with the same closing line as every feed, with the counts of the day:

```text
  loaded quote 2026-11-27: 20 rows into lake newslake (java)
2026-11-27T07:34:12-05:00 feed ok exit=0 feed=news-and-markets date=2026-11-27 files=2 rows=91 load=java loaded=91 seconds=210.4
```

`exit=0` is done. The line is also appended to `<data>/datafeeds/ledger.jsonl` (Admin → Data loads → Feeds shows it). A server that is running notices the new date within its refresh time; to tell it at once add `--signal http://localhost:18480 --token-file FILE` (a token with `loads:write`).

The headlines can also be held in memory only (a memory lake), see [the in-memory tutorial](../../tutorials/18-news-held-in-memory.md); the feed itself always writes the day's files.

## Schedule it

The feed's own time is 07:30 local, daily (`config/feeds/news-and-markets.yaml`, `schedule:`). `--trigger schedule` only marks the ledger line as the scheduler's.

**Linux cron** (`mkdir -p $HOME/drishti-feeds-logs` once, then `crontab -e` and paste):

```text
SHELL=/bin/bash
DRISHTI_FEEDS_USER_AGENT=Example Bank market data team marketdata@bank.example
30 7 * * * cd /opt/drishti && python3.13 tools/drishti.py feed run news-and-markets --trigger schedule >> $HOME/drishti-feeds-logs/news-and-markets.log 2>&1
```

Put `SHELL`, the environment and the working folder in the crontab itself: cron starts with an empty environment, so a variable you exported in your login shell is not there. Use your own organisation's contact in `DRISHTI_FEEDS_USER_AGENT`: the SEC and others require a real one. `python3.13 tools/drishti.py feed schedule --cron news-and-markets` prints the same line for this machine (with the interpreter's full path); `--systemd` prints a service and a timer instead (a missed day runs at the next boot).

**Windows Task Scheduler**, from a command prompt as the user the job runs as:

```text
setx DRISHTI_FEEDS_USER_AGENT "Example Bank market data team marketdata@bank.example"
mkdir C:\drishti-logs
schtasks /Create /F /TN "Drishti load news-and-markets" /SC DAILY /ST 07:30 /TR "cmd /c cd /d C:\drishti && py -3.13 tools\drishti.py feed run news-and-markets --trigger schedule >> C:\drishti-logs\news-and-markets.log 2>&1"
schtasks /Run /TN "Drishti load news-and-markets"
schtasks /Query /TN "Drishti load news-and-markets" /V /FO LIST
```

`setx` takes effect in a new window. The task's *Last Run Result* is the exit code (0 ok, 1 failed, 2 refused, 3 locked, 4 partial). By hand instead: Task Scheduler → Create Task → *Triggers* New → Daily, 07:30 → *Actions* New → Start a program `py`, arguments `-3.13 tools\drishti.py feed run news-and-markets --trigger schedule`, **Start in** `C:\drishti` → *Run whether user is logged on or not*. On the Windows machine `py -3.13 tools\drishti.py feed schedule --windows --root C:\drishti news-and-markets` prints the `schtasks` line for you.

## When a day fails or is late

news and quotes (internal use only) is pulled as a **snapshot**: every run reads the source's current state and files it under the business date. There is no gap to fill after a missed day.

- **Run it again** (failed, or you want fresher data): the same command. The business date's files and its rows in `newslake` are replaced **whole**; other dates are untouched.
- **A missed day:** nothing to catch up. Run today's; to have the day filed anyway, `feed run news-and-markets --date 2026-11-24` writes the source's current state under that date.
- **Several days:** one run is enough. Filing the same snapshot under several dates is possible (`--date` once per date, oldest first) but gives the same rows.
- **Take a date out:** `python3.13 tools/drishti.py lake delete --lake newslake --table news/quote --date 2026-11-24 --dry-run`, then again without `--dry-run` (asks to confirm; `--yes` for a script).
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
Linux     python3.13 tools/drishti.py feed status news-and-markets --runs 3
Windows   py -3.13 tools\drishti.py feed status news-and-markets --runs 3
```

It exits 1 when the feed's last run failed and shows the failures since the last success; a failed last run raises the alert `data-feed-failed`, which clears at the next success. Then the rows and dates in the lake:

```text
Linux     python3.13 tools/drishti.py lake status --lake newslake
Windows   py -3.13 tools\drishti.py lake status --lake newslake
```

It lists each table of the lake with its rows, files, business dates and last build; expect the `news/quote`, `news/news` rows on today's date.

From a running server: `drishti.py dataload status` (waiting files, failures with reasons; the inbox route), `python3.13 tools/drishti.py data get quote --date 2026-11-27 --count` (rows of a kind on a date through the API; add `--server URL --token-file FILE`) and **Admin → Data loads → Feeds**, which shows the status, the last error in words and **Run now**. `drishti.py doctor` checks Java, the jar, the Python libraries and the folders (green, yellow, red; exit 1 on a red).

## Reference

**What it writes.** Lake `newslake`; symbolic table names `news/quote`, `news/news`. The data contract of every field: [the news-and-markets contract page](../../contracts/packs/news-and-markets.md). The day's files are `<data>/datafeeds/news-and-markets/<date>/<table>.<date>.jsonl.gz` with a `manifest.json` (licence, SHA-256 and row count of each file). Settings, datasets and options: [`config/feeds/news-and-markets.md`](../../../config/feeds/news-and-markets.md) (generated from `config/feeds/news-and-markets.yaml`).

**Input and date rules.** Nothing to prepare. The run captures 20 quotes (with a year of daily bars) and 71 news items (headlines per company and per topic) and writes `quote` and `news`. Set `DRISHTI_TOKEN` in the environment when the server it talks to is secured. **Internal only**: Yahoo's terms forbid redistribution and the headlines belong to their publishers.

**A real example** (a `quote` row of the committed demo snapshot (real Yahoo data, internal use); the first row of the file, long values cut):

```json
{"QUOTE_ID": "QUOTE-AAPL", "SYMBOL": "AAPL", "NAME": "Apple Inc.", "EXCHANGE": "NasdaqGS", "CURRENCY": "USD", "INSTRUMENT_TYPE": "EQUITY", "PRICE": 358.54, "PREVIOUS_CLOSE": 355.5799865722656, "CHANGE": 2.9600134277343955, "CHANGE_PCT": 0.832446577285874, "DAY_LOW": 355.64, "DAY_HIGH": 359.5}
```

**Source and licence.** Yahoo Finance (quotes) and Google News (headlines), through Drishti's yfinance and news connectors. **Internal only, never redistributed.** **Internal only: never redistribute** (the feed refuses to write it into a committed folder without `--internal-ok`).

**Errors and fixes.**

| You see | It means | Do this |
|---|---|---|
| no quotes in the output | no server for the connectors, or its connectors are off | start a server with the news and yfinance connectors on, or leave `options.server` empty to use a scratch one from the jar |
| *no server jar in .../lib* or *Unbuilt* | the load needs the server jar | `python3.13 tools/drishti.py build jar` |
| HTTP 429, 5xx or a timeout in the log | the source is rate limiting or down | nothing: it retries after 2, 4, 8 s; run again later (it resumes from its cache) |
| *refused: ... committed folder ... may not be redistributed* | internal-only data headed into the repository | leave the default (the data folder); `--internal-ok` only with `--snapshot`, for an internal demo |
| fewer rows loaded than pulled | the load rejected rows | `dataload status` and the run's load report give the reason per row |

More: [tools/loaders/news/README.md](../../../tools/loaders/news/README.md) (the loader README), [WORLD_DATA_GUIDE.md](../WORLD_DATA_GUIDE.md) (the data, in depth) and [the feeds' engine](../../../tools/loaders/common/README.md).
