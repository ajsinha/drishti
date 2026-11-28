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
# Load Ensembl gene annotations every day

Ensembl gene annotations (chromosome, start, end, strand, biotype, canonical transcript, protein length) of the same gene panel as ClinVar. One command pulls the source, writes the day's files under `<data>/datafeeds/ensembl/<date>/` and loads them into the lake **`genomicslake`**, replacing only the business date it pulled. Feed `ensembl`, table(s) `gene`. Licence: Ensembl data is open (EMBL-EBI terms of use).

Run every command from the Drishti folder (the one that holds `tools`): `/opt/drishti` on Linux and `C:\drishti` on Windows in the examples. Nothing is installed for you and there is no virtual environment: use the Python you run Drishti with. The load needs Java 21 and the server jar in `lib` (`drishti.py build jar`); the server does not have to be running.

## Every day

```text
Linux     cd /opt/drishti && python3.13 tools/drishti.py feed run ensembl
Windows   cd C:\drishti && py -3.13 tools\drishti.py feed run ensembl
```

Look before pulling (changes nothing; prints the requests it would make):

```text
Linux     python3.13 tools/drishti.py feed run ensembl --dry-run
Windows   py -3.13 tools\drishti.py feed run ensembl --dry-run
```

**What you see when it worked** (a real run of this page's author, into a scratch data folder; the last line is the one a scheduler or a person reads):

```text
  loaded gene 2026-11-27: 3 rows into lake genomicslake (java)
2026-11-27T19:43:42-05:00 feed ok exit=0 feed=ensembl date=2026-11-27 files=1 rows=3 load=java loaded=3 seconds=3.9
```

`exit=0` is done. The line is also appended to `<data>/datafeeds/ledger.jsonl` (Admin → Data loads → Feeds shows it). A server that is running notices the new date within its refresh time; to tell it at once add `--signal http://localhost:18480 --token-file FILE` (a token with `loads:write`).

## Schedule it

The feed's own time is 06:10 local, daily (`config/feeds/ensembl.yaml`, `schedule:`). `--trigger schedule` only marks the ledger line as the scheduler's.

**Linux cron** (`mkdir -p $HOME/drishti-feeds-logs` once, then `crontab -e` and paste):

```text
SHELL=/bin/bash
DRISHTI_FEEDS_USER_AGENT=Example Bank market data team marketdata@bank.example
10 6 * * * cd /opt/drishti && python3.13 tools/drishti.py feed run ensembl --trigger schedule >> $HOME/drishti-feeds-logs/ensembl.log 2>&1
```

Put `SHELL`, the environment and the working folder in the crontab itself: cron starts with an empty environment, so a variable you exported in your login shell is not there. Use your own organisation's contact in `DRISHTI_FEEDS_USER_AGENT`: the SEC and others require a real one. `python3.13 tools/drishti.py feed schedule --cron ensembl` prints the same line for this machine (with the interpreter's full path); `--systemd` prints a service and a timer instead (a missed day runs at the next boot).

**Windows Task Scheduler**, from a command prompt as the user the job runs as:

```text
setx DRISHTI_FEEDS_USER_AGENT "Example Bank market data team marketdata@bank.example"
mkdir C:\drishti-logs
schtasks /Create /F /TN "Drishti load ensembl" /SC DAILY /ST 06:10 /TR "cmd /c cd /d C:\drishti && py -3.13 tools\drishti.py feed run ensembl --trigger schedule >> C:\drishti-logs\ensembl.log 2>&1"
schtasks /Run /TN "Drishti load ensembl"
schtasks /Query /TN "Drishti load ensembl" /V /FO LIST
```

`setx` takes effect in a new window. The task's *Last Run Result* is the exit code (0 ok, 1 failed, 2 refused, 3 locked, 4 partial). By hand instead: Task Scheduler → Create Task → *Triggers* New → Daily, 06:10 → *Actions* New → Start a program `py`, arguments `-3.13 tools\drishti.py feed run ensembl --trigger schedule`, **Start in** `C:\drishti` → *Run whether user is logged on or not*. On the Windows machine `py -3.13 tools\drishti.py feed schedule --windows --root C:\drishti ensembl` prints the `schtasks` line for you.

## When a day fails or is late

Ensembl gene annotations is pulled as a **snapshot**: every run reads the source's current state and files it under the business date. There is no gap to fill after a missed day.

- **Run it again** (failed, or you want fresher data): the same command. The business date's files and its rows in `genomicslake` are replaced **whole**; other dates are untouched.
- **A missed day:** nothing to catch up. Run today's; to have the day filed anyway, `feed run ensembl --date 2026-11-24` writes the source's current state under that date.
- **Several days:** one run is enough. Filing the same snapshot under several dates is possible (`--date` once per date, oldest first) but gives the same rows.
- **Take a date out:** `python3.13 tools/drishti.py lake delete --lake genomicslake --table genomics/gene --date 2026-11-24 --dry-run`, then again without `--dry-run` (asks to confirm; `--yes` for a script).
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
Linux     python3.13 tools/drishti.py feed status ensembl --runs 3
Windows   py -3.13 tools\drishti.py feed status ensembl --runs 3
```

It exits 1 when the feed's last run failed and shows the failures since the last success; a failed last run raises the alert `data-feed-failed`, which clears at the next success. Then the rows and dates in the lake:

```text
Linux     python3.13 tools/drishti.py lake status --lake genomicslake
Windows   py -3.13 tools\drishti.py lake status --lake genomicslake
```

```text
lake <scratch>/data/delta/genomicslake
tables (3):
  table             rows  files  size     partitions  dates  latest date  last build
  genomics/gene     5     2      5.6 KB   2           2      2026-11-27   2026-11-28 00:43:52Z
  genomics/sample   3     1      2.7 KB   1           1      2026-11-26   2026-11-28 00:43:56Z
  genomics/variant  572   2      70.2 KB  2           2      2026-11-27   2026-11-28 00:51:14Z
lock: free
```

From a running server: `drishti.py dataload status` (waiting files, failures with reasons; the inbox route), `python3.13 tools/drishti.py data get gene --date 2026-11-27 --count` (rows of a kind on a date through the API; add `--server URL --token-file FILE`) and **Admin → Data loads → Feeds**, which shows the status, the last error in words and **Run now**. `drishti.py doctor` checks Java, the jar, the Python libraries and the folders (green, yellow, red; exit 1 on a red).

## Reference

**What it writes.** Lake `genomicslake`; symbolic table names `genomics/gene`. The data contract of every field: `modules/genomics/packs/genomics/contracts/gene.yaml` (the module guide: [guide.md](../../../modules/genomics/docs/guide.md)). The day's files are `<data>/datafeeds/ensembl/<date>/<table>.<date>.jsonl.gz` with a `manifest.json` (licence, SHA-256 and row count of each file). Settings, datasets and options: [`config/feeds/ensembl.md`](../../../config/feeds/ensembl.md) (generated from `config/feeds/ensembl.yaml`).

**Input and date rules.** Nothing to prepare. The panel is the `genes` option of `config/feeds/ensembl.yaml` (the same symbols as `clinvar`; `--set genes=MLH1,MSH2,APC` for one run). The feed writes one `gene` row per symbol under the business date. Run `ensembl` after `clinvar` (06:10 against 06:00) so the panel's genes and variants are the same on the date.

**A real example** (a `gene` row of this page's author's run (BRCA1); the first row of the file, long values cut):

```json
{"BIOTYPE": "protein_coding", "CANONICAL_TRANSCRIPT": "ENST00000357654.9", "CHROM": "17", "DESCRIPTION": "BRCA1 DNA repair associated", "DOMAINS": [], "END": 43170245, "ENSEMBL_ID": "ENSG00000012048", "GENE": "BRCA1", "PROTEIN_LENGTH": 1863, "START": 43044292, "STRAND": "-", "VARIANTS": null}
```

**Source and licence.** Ensembl (EMBL-EBI): open, free to use and redistribute with attribution: Contains data from Ensembl (EMBL-EBI). 15 requests a second; the feed pauses 0.2 s. Redistribution: attribution. Attribution: Contains data from Ensembl (EMBL-EBI), https://www.ensembl.org

**Errors and fixes.**

| You see | It means | Do this |
|---|---|---|
| exit 1 *no gene for the panel* | none of the symbols is known to Ensembl | check the symbols; `--dry-run` shows the requests |
| *no server jar in .../lib* or *Unbuilt* | the load needs the server jar | `python3.13 tools/drishti.py build jar` |
| HTTP 429, 5xx or a timeout in the log | the source is rate limiting or down | nothing: it retries after 2, 4, 8 s; run again later (it resumes from its cache) |
| *refused: ... committed folder ... may not be redistributed* | internal-only data headed into the repository | leave the default (the data folder); `--internal-ok` only with `--snapshot`, for an internal demo |
| fewer rows loaded than pulled | the load rejected rows | `dataload status` and the run's load report give the reason per row |

More: [tools/loaders/genomics/README.md](../../../tools/loaders/genomics/README.md) (the loader README), [WORLD_DATA_GUIDE.md](../WORLD_DATA_GUIDE.md) (the data, in depth) and [the feeds' engine](../../../tools/loaders/common/README.md).
