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
# Load official market data every day

ECB euro reference FX rates, the US Treasury par yield curve, SOFR and the euro short-term rate (EUR STR): the free official fixings and curves. One command pulls the source, writes the day's files under `<data>/datafeeds/market-data/<date>/` and loads them into the lake **`capitalmarket`**, replacing only the business date it pulled. Feed `market-data`, table(s) `fx-spot`, `ir-curve`, `rate-fixing`. Licence: ECB reuse with attribution; US Treasury public domain; New York Fed terms (attribution).

Run every command from the Drishti folder (the one that holds `tools`): `/opt/drishti` on Linux and `C:\drishti` on Windows in the examples. Nothing is installed for you and there is no virtual environment: use the Python you run Drishti with. The load needs Java 21 and the server jar in `lib` (`drishti.py build jar`); the server does not have to be running.

## Every day

```text
Linux     cd /opt/drishti && python3.13 tools/drishti.py feed run market-data
Windows   cd C:\drishti && py -3.13 tools\drishti.py feed run market-data
```

Look before pulling (changes nothing; prints the requests it would make):

```text
Linux     python3.13 tools/drishti.py feed run market-data --dry-run
Windows   py -3.13 tools\drishti.py feed run market-data --dry-run
```

**What you see when it worked** (a real run of this page's author, into a scratch data folder; the last line is the one a scheduler or a person reads):

```text
  loaded rate-fixing 2026-11-25: 1 rows into lake capitalmarket (java)
  loaded rate-fixing 2026-11-26: 1 rows into lake capitalmarket (java)
  loaded fx-spot 2026-11-27: 12 rows into lake capitalmarket (java)
  loaded ir-curve 2026-11-27: 1 rows into lake capitalmarket (java)
2026-11-27T19:50:44-05:00 feed ok exit=0 feed=market-data date=2026-11-27 since=2026-11-20 files=4 rows=15 load=java loaded=15 seconds=19.1
```

`exit=0` is done. The line is also appended to `<data>/datafeeds/ledger.jsonl` (Admin → Data loads → Feeds shows it). A server that is running notices the new date within its refresh time; to tell it at once add `--signal http://localhost:18480 --token-file FILE` (a token with `loads:write`).

## Schedule it

The feed's own time is 18:30 local, weekdays (`config/feeds/market-data.yaml`, `schedule:`). `--trigger schedule` only marks the ledger line as the scheduler's.

**Linux cron** (`mkdir -p $HOME/drishti-feeds-logs` once, then `crontab -e` and paste):

```text
SHELL=/bin/bash
DRISHTI_FEEDS_USER_AGENT=Example Bank market data team marketdata@bank.example
30 18 * * 1-5 cd /opt/drishti && python3.13 tools/drishti.py feed run market-data --trigger schedule >> $HOME/drishti-feeds-logs/market-data.log 2>&1
```

Put `SHELL`, the environment and the working folder in the crontab itself: cron starts with an empty environment, so a variable you exported in your login shell is not there. Use your own organisation's contact in `DRISHTI_FEEDS_USER_AGENT`: the SEC and others require a real one. `python3.13 tools/drishti.py feed schedule --cron market-data` prints the same line for this machine (with the interpreter's full path); `--systemd` prints a service and a timer instead (a missed day runs at the next boot).

**Windows Task Scheduler**, from a command prompt as the user the job runs as:

```text
setx DRISHTI_FEEDS_USER_AGENT "Example Bank market data team marketdata@bank.example"
mkdir C:\drishti-logs
schtasks /Create /F /TN "Drishti load market-data" /SC WEEKLY /D MON,TUE,WED,THU,FRI /ST 18:30 /TR "cmd /c cd /d C:\drishti && py -3.13 tools\drishti.py feed run market-data --trigger schedule >> C:\drishti-logs\market-data.log 2>&1"
schtasks /Run /TN "Drishti load market-data"
schtasks /Query /TN "Drishti load market-data" /V /FO LIST
```

`setx` takes effect in a new window. The task's *Last Run Result* is the exit code (0 ok, 1 failed, 2 refused, 3 locked, 4 partial). By hand instead: Task Scheduler → Create Task → *Triggers* New → Weekly, Monday to Friday, 18:30 → *Actions* New → Start a program `py`, arguments `-3.13 tools\drishti.py feed run market-data --trigger schedule`, **Start in** `C:\drishti` → *Run whether user is logged on or not*. On the Windows machine `py -3.13 tools\drishti.py feed schedule --windows --root C:\drishti market-data` prints the `schtasks` line for you.

## When a day fails or is late

official market data is pulled **by date**: a run reads the days after the last one it loaded (`<data>/datafeeds/market-data/state.json`), so a missed day is picked up by the next run without any option. A second run on the same day finds nothing newer and ends `exit=0 rows=0` with a line saying so: that is a good day, not a failure.

- **Run it again** (a failed run, or a late source): the same command. `state.json` is saved only when the whole run succeeded, so a failed run asks for the same days again; downloads are cached, so it resumes.
- **A missed day or several:** `feed run market-data --since 2026-11-20` re-reads the source from that date to today; the `loaded ... rows into lake` lines of the run name every business date it wrote. (`--date` alone does not re-read a source that has nothing newer: it answers *no new ... since the last run*.)
- **Replace a day:** `feed run market-data --since DATE` for that day. Each business date it writes is replaced **whole**, in every table of the feed: give the same window and options as your daily run, because a narrower one loads fewer rows for the date.
- **Take a date out:** `python3.13 tools/drishti.py lake delete --lake capitalmarket --table market/fx-spot --date 2026-11-24 --dry-run`, then again without `--dry-run` (asks to confirm; `--yes` for a script).
- **No network:** `--offline` uses the download cache only (a run that cannot find what it needs says which file).
- **The load route:** `--load java` (default, the parallel loader), `python`, `inbox` (the server loads at its next cycle) or `none` (write the files only; look at them first).

The ECB publishes its reference rates about 16:00 CET and the Treasury curve after the US close, which is why the feed runs at 18:30 local on weekdays: a run before that finds no new day and ends `rows=0`.

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
Linux     python3.13 tools/drishti.py feed status market-data --runs 3
Windows   py -3.13 tools\drishti.py feed status market-data --runs 3
```

It exits 1 when the feed's last run failed and shows the failures since the last success; a failed last run raises the alert `data-feed-failed`, which clears at the next success. Then the rows and dates in the lake:

```text
Linux     python3.13 tools/drishti.py lake status --lake capitalmarket
Windows   py -3.13 tools\drishti.py lake status --lake capitalmarket
```

```text
lake <scratch>/data/delta/capitalmarket
tables (3):
  table               rows  files  size     partitions  dates  latest date  last build
  market/fx-spot      12    1      5.0 KB   1           1      2026-11-27   2026-11-28 00:50:40Z
  market/ir-curve     1     1      2.5 KB   1           1      2026-11-27   2026-11-28 00:50:43Z
  market/rate-fixing  2     2      10.2 KB  2           2      2026-11-26   2026-11-28 00:50:37Z
lock: free
```

From a running server: `drishti.py dataload status` (waiting files, failures with reasons; the inbox route), `python3.13 tools/drishti.py data get fx-spot --date 2026-11-27 --count` (rows of a kind on a date through the API; add `--server URL --token-file FILE`) and **Admin → Data loads → Feeds**, which shows the status, the last error in words and **Run now**. `drishti.py doctor` checks Java, the jar, the Python libraries and the folders (green, yellow, red; exit 1 on a red).

## Reference

**What it writes.** Lake `capitalmarket`; symbolic table names `market/fx-spot`, `market/ir-curve`, `market/rate-fixing`. The data contract of every field: [the market-data contract page](../../contracts/packs/market-data.md). The day's files are `<data>/datafeeds/market-data/<date>/<table>.<date>.jsonl.gz` with a `manifest.json` (licence, SHA-256 and row count of each file). Settings, datasets and options: [`config/feeds/market-data.md`](../../../config/feeds/market-data.md) (generated from `config/feeds/market-data.yaml`).

**Input and date rules.** Nothing to prepare. Four sources, each with a file per business day: the ECB's `eurofxref-hist` (FX spot, 12 currency pairs against USD and EUR), the Treasury's daily par yield curve (`ir-curve`), SOFR from the New York Fed and the euro short-term rate from the ECB (both into `rate-fixing`). The first run pulls 30 days; after that each run asks for the days after the last one loaded. The first load into a lake with no entry for a table copies the pack's recommended table entry into `config/lakes/capitalmarket.yaml` (the run says so); keep that file with your site's settings.

**A real example** (an `fx-spot` row of this page's author's run (AUD/USD from the ECB fixing); the first row of the file, long values cut):

```json
{"ASK": 0.666849, "AS_OF": "2026-11-27", "BID": 0.666849, "CHANGE_1D": 0.0049823, "CONVENTIONS": {"BASE": "AUD", "FIXING": "ECB euro reference rate, 14:10 CET (crosses via EUR)", "QUOTE": "USD"}, "HISTORY": [{"DATE": "2026-11-20", "MID": 0.666586}, {"DATE": "2026-11-23", "MID": 0.661686}], "MID": 0.666849, "PAIR": "FX-AUDUSD", "PAIR_NAME": "AUD/USD", "PROVENANCE": {"LICENCE": "ECB: free reuse with the source acknowledged", "RETRIEVED_AT": "2026-11-28T00:50:27Z", "SOURCE": "ECB euro foreign exchange reference rates", "URL": "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist..."}, "SPOT_DATE": "2026-11-27"}
```

**Source and licence.** ECB euro foreign exchange reference rates (free reuse with the source acknowledged), US Treasury (public domain), the New York Fed (SOFR; reuse with attribution), ECB EUR STR. The separate `yahoo-prices` feed (daily prices, table `price-history`, weekdays 22:30) is **internal only** and **off as shipped**: turn it on in `config/feeds/yahoo-prices.yaml` only for an internal demo. Redistribution: attribution. Attribution: Source: European Central Bank; U.S. Department of the Treasury; Federal Reserve Bank of New York

**Errors and fixes.**

| You see | It means | Do this |
|---|---|---|
| *the pack's recommended entry for market.fx-spot was copied into ... capitalmarket.yaml* | first load into a lake file with no entry for the table (a notice, not an error) | keep the edited lake file with your site's settings |
| *no server jar in .../lib* or *Unbuilt* | the load needs the server jar | `python3.13 tools/drishti.py build jar` |
| HTTP 429, 5xx or a timeout in the log | the source is rate limiting or down | nothing: it retries after 2, 4, 8 s; run again later (it resumes from its cache) |
| *refused: ... committed folder ... may not be redistributed* | internal-only data headed into the repository | leave the default (the data folder); `--internal-ok` only with `--snapshot`, for an internal demo |
| fewer rows loaded than pulled | the load rejected rows | `dataload status` and the run's load report give the reason per row |

More: [tools/loaders/external/README.md](../../../tools/loaders/external/README.md) (the loader README), [WORLD_DATA_GUIDE.md](../WORLD_DATA_GUIDE.md) (the data, in depth) and [the feeds' engine](../../../tools/loaders/common/README.md).
