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
# Load GLEIF legal entities (LEI) every day

The Global LEI Foundation's golden copy, the LEI records of the pack's universe and the ISIN, BIC and MIC to LEI mappings: the core reference data of every counterparty. One command pulls the source, writes the day's files under `<data>/datafeeds/gleif/<date>/` and loads them into the lake **`referencelake`**, replacing only the business date it pulled. Feed `gleif`, table(s) `lei-entity`, `lei-relationship`, `lei-mapping`. Licence: CC0 1.0 (attribution appreciated).

Run every command from the Drishti folder (the one that holds `tools`): `/opt/drishti` on Linux and `C:\drishti` on Windows in the examples. Nothing is installed for you and there is no virtual environment: use the Python you run Drishti with. The load needs Java 21 and the server jar in `lib` (`drishti.py build jar`); the server does not have to be running.

## Every day

```text
Linux     cd /opt/drishti && python3.13 tools/drishti.py feed run gleif
Windows   cd C:\drishti && py -3.13 tools\drishti.py feed run gleif
```

Look before pulling (changes nothing; prints the requests it would make):

```text
Linux     python3.13 tools/drishti.py feed run gleif --dry-run
Windows   py -3.13 tools\drishti.py feed run gleif --dry-run
```

**What you see.** The dry run (a real output of this page's author) prints the requests the run would make and ends with its closing line:

```text
  dry run: gleif, sources golden, api, mapping
    golden (CC0 1.0 (attribution appreciated); committable): 3 request(s) or file(s), cache <scratch>/data2/da
    ...
2026-11-27T19:52:37-05:00 feed dry-run exit=0 feed=gleif date=2026-11-27 files=0 rows=0 seconds=1.1
```

The golden copy is about 24 MB (relationships) and 62 MB (exceptions) zipped, plus the mapping files: The real run therefore was not repeated for this page; it ends with the same closing line as every feed, with the counts of the day:

```text
  loaded lei-entity 2026-11-27: ... rows into lake referencelake (java)
2026-11-27T05:31:11-05:00 feed ok exit=0 feed=gleif date=2026-11-27 files=3 rows=4,377 load=java loaded=4,377 seconds=41.2
```

`exit=0` is done. The line is also appended to `<data>/datafeeds/ledger.jsonl` (Admin → Data loads → Feeds shows it). A server that is running notices the new date within its refresh time; to tell it at once add `--signal http://localhost:18480 --token-file FILE` (a token with `loads:write`).

## Schedule it

The feed's own time is 05:30 local, daily (`config/feeds/gleif.yaml`, `schedule:`). `--trigger schedule` only marks the ledger line as the scheduler's.

**Linux cron** (`mkdir -p $HOME/drishti-feeds-logs` once, then `crontab -e` and paste):

```text
SHELL=/bin/bash
DRISHTI_FEEDS_USER_AGENT=Example Bank market data team marketdata@bank.example
30 5 * * * cd /opt/drishti && python3.13 tools/drishti.py feed run gleif --trigger schedule >> $HOME/drishti-feeds-logs/gleif.log 2>&1
```

Put `SHELL`, the environment and the working folder in the crontab itself: cron starts with an empty environment, so a variable you exported in your login shell is not there. Use your own organisation's contact in `DRISHTI_FEEDS_USER_AGENT`: the SEC and others require a real one. `python3.13 tools/drishti.py feed schedule --cron gleif` prints the same line for this machine (with the interpreter's full path); `--systemd` prints a service and a timer instead (a missed day runs at the next boot).

**Windows Task Scheduler**, from a command prompt as the user the job runs as:

```text
setx DRISHTI_FEEDS_USER_AGENT "Example Bank market data team marketdata@bank.example"
mkdir C:\drishti-logs
schtasks /Create /F /TN "Drishti load gleif" /SC DAILY /ST 05:30 /TR "cmd /c cd /d C:\drishti && py -3.13 tools\drishti.py feed run gleif --trigger schedule >> C:\drishti-logs\gleif.log 2>&1"
schtasks /Run /TN "Drishti load gleif"
schtasks /Query /TN "Drishti load gleif" /V /FO LIST
```

`setx` takes effect in a new window. The task's *Last Run Result* is the exit code (0 ok, 1 failed, 2 refused, 3 locked, 4 partial). By hand instead: Task Scheduler → Create Task → *Triggers* New → Daily, 05:30 → *Actions* New → Start a program `py`, arguments `-3.13 tools\drishti.py feed run gleif --trigger schedule`, **Start in** `C:\drishti` → *Run whether user is logged on or not*. On the Windows machine `py -3.13 tools\drishti.py feed schedule --windows --root C:\drishti gleif` prints the `schtasks` line for you.

## When a day fails or is late

GLEIF legal entities (LEI) is pulled as a **snapshot**: every run reads the source's current state and files it under the business date. There is no gap to fill after a missed day.

- **Run it again** (failed, or you want fresher data): the same command. The business date's files and its rows in `referencelake` are replaced **whole**; other dates are untouched.
- **A missed day:** nothing to catch up. Run today's; to have the day filed anyway, `feed run gleif --date 2026-11-24` writes the source's current state under that date.
- **Several days:** one run is enough. Filing the same snapshot under several dates is possible (`--date` once per date, oldest first) but gives the same rows.
- **Take a date out:** `python3.13 tools/drishti.py lake delete --lake referencelake --table gleif/lei-entity --date 2026-11-24 --dry-run`, then again without `--dry-run` (asks to confirm; `--yes` for a script).
- **No network:** `--offline` uses the download cache only (a run that cannot find what it needs says which file).
- **The load route:** `--load java` (default, the parallel loader), `python`, `inbox` (the server loads at its next cycle) or `none` (write the files only; look at them first).

GLEIF publishes three times a day; the 00:00 UTC file is there by the feed's 05:30 run, so a run that fails in the morning can simply be repeated at midday.

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
Linux     python3.13 tools/drishti.py feed status gleif --runs 3
Windows   py -3.13 tools\drishti.py feed status gleif --runs 3
```

It exits 1 when the feed's last run failed and shows the failures since the last success; a failed last run raises the alert `data-feed-failed`, which clears at the next success. Then the rows and dates in the lake:

```text
Linux     python3.13 tools/drishti.py lake status --lake referencelake
Windows   py -3.13 tools\drishti.py lake status --lake referencelake
```

It lists each table of the lake with its rows, files, business dates and last build; expect the `gleif/lei-entity`, `gleif/lei-relationship`, `gleif/lei-mapping` rows on today's date.

From a running server: `drishti.py dataload status` (waiting files, failures with reasons; the inbox route), `python3.13 tools/drishti.py data get lei-entity --date 2026-11-27 --count` (rows of a kind on a date through the API; add `--server URL --token-file FILE`) and **Admin → Data loads → Feeds**, which shows the status, the last error in words and **Run now**. `drishti.py doctor` checks Java, the jar, the Python libraries and the folders (green, yellow, red; exit 1 on a red).

## Reference

**What it writes.** Lake `referencelake`; symbolic table names `gleif/lei-entity`, `gleif/lei-relationship`, `gleif/lei-mapping`. The data contract of every field: [the gleif contract page](../../contracts/packs/gleif.md). The day's files are `<data>/datafeeds/gleif/<date>/<table>.<date>.jsonl.gz` with a `manifest.json` (licence, SHA-256 and row count of each file). Settings, datasets and options: [`config/feeds/gleif.md`](../../../config/feeds/gleif.md) (generated from `config/feeds/gleif.yaml`).

**Input and date rules.** Nothing to prepare: the feed downloads GLEIF's golden copy (`goldencopy.gleif.org`), the LEI API (`api.gleif.org`) and the mapping files (`mapping.gleif.org`), reduces them to the pack's universe and writes `lei-entity`, `lei-relationship` and `lei-mapping` under the run's business date (default today). A scope other than the default is a source option: `feed run gleif -- --scope GROUP:784F5XWPLTWKTBV3E584`.

**A real example** (a `lei-entity` row of the committed demo snapshot, `data/testdata/gleif`; the first row of the file, long values cut):

```json
{"LEI": "010PWNH4K3BLIC3I7R03", "NAME": "GESTION PLACEMENTS DESJARDINS INC.", "LEGAL_FORM": "Société par actions par Loi sur les sociétés par actions", "JURISDICTION": "CA-QC", "LEI_COUNTRY": "CA", "COUNTRY_NAME": "Canada", "LEGAL_ADDRESS": "27e-2 Complexe Desjardins, Tour Est, H5B 1E4 Montreal, C...", "LEGAL_CITY": "Montreal", "HQ_ADDRESS": "200 rue des Commandeurs, G6V 6R2 Lévis, CA-QC, CA", "REGISTERED_AS": "1140542631", "ENTITY_STATUS": "ACTIVE", "CREATED": "1994-06-06"}
```

**Source and licence.** GLEIF (https://www.gleif.org): golden copy, LEI API and mapping files; CC0 1.0, attribution appreciated. Redistribution: allowed. Attribution: Contains LEI data from the Global Legal Entity Identifier Foundation (GLEIF), CC0 1.0

**Errors and fixes.**

| You see | It means | Do this |
|---|---|---|
| exit 1 *golden: GLEIF golden copy: the latest publish could not be read* | the golden copy was not reachable (no network, or GLEIF down; seen when run offline) | run again later; behind a proxy set `HTTPS_PROXY`; `--offline` only works once the files are cached |
| `lei-entity` rows with `RENEWAL_LATE: true` | GLEIF says the LEI lapsed: a fact about the entity, not a load error | none; the renewal fields carry the reason |
| *no server jar in .../lib* or *Unbuilt* | the load needs the server jar | `python3.13 tools/drishti.py build jar` |
| HTTP 429, 5xx or a timeout in the log | the source is rate limiting or down | nothing: it retries after 2, 4, 8 s; run again later (it resumes from its cache) |
| *refused: ... committed folder ... may not be redistributed* | internal-only data headed into the repository | leave the default (the data folder); `--internal-ok` only with `--snapshot`, for an internal demo |
| fewer rows loaded than pulled | the load rejected rows | `dataload status` and the run's load report give the reason per row |

More: [tools/loaders/external/README.md](../../../tools/loaders/external/README.md) (the loader README), [WORLD_DATA_GUIDE.md](../WORLD_DATA_GUIDE.md) (the data, in depth) and [the feeds' engine](../../../tools/loaders/common/README.md).
