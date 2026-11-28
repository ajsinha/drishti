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
# Load legal entities, desks and books every day

The bank's own structure: the legal entities that book, the desks under them and the books (portfolios) under the desks. They are the names behind the LEGAL hierarchy and the links every trade, position and limit carries. One command converts and checks the day's CSV files kind by kind, in dependency order, and loads them into the lake: **a day that fails leaves the lake as it was** for the kind that failed, and the kinds after it are not loaded.

Run every command from the Drishti folder (the one that holds `tools`): `/opt/drishti` on Linux and `C:\drishti` on Windows in the examples. Nothing is installed for you and there is no virtual environment: use the Python you run Drishti with. Loading needs Java 21 and the server jar in `lib` (`drishti.py build jar`); converting needs neither. A date in an example is an example: use the business date of your file.

| Kind | Your file (any name; `--csv`) | Child file | Table written | Load first | Key |
|---|---|---|---|---|---|
| `legal-entity` | `legal_entity.csv` | - | `reference/legal-entity` | - | `ENTITY_ID` |
| `desk` | `desk.csv` | - | `reference/desk` | legal-entity | `DESK_ID` |
| `book` | `book.csv` | - | `reference/book` | desk | `BOOK_ID` |

## Every day

1. **Write the day file once** (`/site/organisation.yaml`, `C:\site\organisation.yaml`, kept with your site's settings, not in the product). It says which file is which kind; `{YYYYMMDD}` in a name is replaced by the date. `tools/loaders/mrloader/days/sample.yaml` is the full example (all fifteen kinds).

```yaml
format: 1
lake: demo
kinds:
  legal-entity: {csv: "legal_entity.csv"}
  desk:         {csv: "desk.csv"}
  book:         {csv: "book.csv"}
required: [legal-entity, desk]
```

2. **Drop the day's files** in one folder (`/data/mr/2026-11-27/`), then check and load:

```text
Linux     cd /opt/drishti
          python3.13 tools/loaders/mrloader/mr_run.py --date 2026-11-27 --from /data/mr/2026-11-27 --day /site/organisation.yaml --dry-run
          python3.13 tools/loaders/mrloader/mr_run.py --date 2026-11-27 --from /data/mr/2026-11-27 --day /site/organisation.yaml --load --lake demo --server http://localhost:18480
Windows   cd C:\drishti
          py -3.13 tools\loaders\mrloader\mr_run.py --date 2026-11-27 --from D:\mr\2026-11-27 --day C:\site\organisation.yaml --dry-run
          py -3.13 tools\loaders\mrloader\mr_run.py --date 2026-11-27 --from D:\mr\2026-11-27 --day C:\site\organisation.yaml --load --lake demo --server http://localhost:18480
```

`--dry-run` converts and checks and writes nothing. In place of `--load` use `--inbox` to hand the files to the running server (they are copied to `data/dataload/inbox`, files first and markers last, in dependency order, and the node that owns the data load loads them). **The business date is the `--date` you type**, never a date in a file name; a file with no rows for it, or rows with another date, is refused with the count. `book` has a second source: the PVF extract's `portfolio_d2.csv` (see [portfolios](load-portfolio.md)).
**One kind only:** `python3.13 tools/loaders/mrloader/csv_load.py --kind book --csv FILE --date 2026-11-27 --load --lake demo` (`--template` prints a kind's header and an example row; `--kinds` lists every kind; `--limit N` is a trial).
**The lake must describe the table.** A writer never creates a table the lake file does not describe: a load into a table with no entry stops with *lake X has no table for DOMAIN.TABLE: add its entry to config/lakes/X.yaml*. The shipped `demo` lake has the entries of the standard kinds; `risklake` ships empty (`tables: {}`), so copy the entries you need from the worked example in [DATA_LAKE_GUIDE.md section 2.1](../DATA_LAKE_GUIDE.md#21-a-worked-example-configlakesrisklakeyaml) once, keep the file with your site's settings, and point the connectors at the lake (`config/connectors/*.yaml`, `lake:`). The commands below say `--lake demo`; use your lake's name.

**What you see** (real runs of this page's author on the shipped synthetic samples, date 2026-11-20, into a scratch data folder). The dry run:

```text
kind                   status        read  written rejected  links seconds
--------------------------------------------------------------------------
legal-entity           DONE             4        4        0      0       0
desk                   DONE             6        6        0      0       0
book                   DONE            12       12        0      0       0
```

Exit code 0. Then a real load of this page's author, kind by kind into a scratch data folder: `legal-entity` 4 rows, `desk` 6, `book` 12, each ending `lake-build ok exit=0 ... rows=N rejected=0`:

```text
2026-11-27T19:58:14-05:00 lake-build ok exit=0 run=20261127T195812.095-3619260 domain=reference kind=legal-entity engine=java-loader mode=overwrite-dates rows=4 rejected=0 dates=1 partitions=1 seconds=2.762
2026-11-27T19:58:18-05:00 lake-build ok exit=0 run=20261127T195815.094-3619447 domain=reference kind=desk engine=java-loader mode=overwrite-dates rows=6 rejected=0 dates=1 partitions=1 seconds=3.119
2026-11-27T19:58:21-05:00 lake-build ok exit=0 run=20261127T195818.429-3619635 domain=reference kind=book engine=java-loader mode=overwrite-dates rows=12 rejected=0 dates=1 partitions=1 seconds=2.947
kind                   status        read  written rejected  links seconds
--------------------------------------------------------------------------
legal-entity           DONE             4        4        0      0       0
desk                   DONE             6        6        0      0       0
book                   DONE            12       12        0      0       0
```

Rejected rows are written to `mr-<date>/<kind>.<date>.rejects.csv` with a code and the row's number.

## Schedule it

Schedule it **after your extract has landed** (the load does not wait for a file that has not come):

**Linux cron** (`crontab -e`; mind that `%` must be written `\%` in a crontab):

```text
SHELL=/bin/bash
30 18 * * 1-5 cd /opt/drishti && d=$(date +\%F) && python3.13 tools/loaders/mrloader/mr_run.py --date $d --from /data/mr/$d --day /site/organisation.yaml --load --lake demo --server http://localhost:18480 >> $HOME/drishti-mr-organisation.log 2>&1
```

Cron starts with an empty environment: put the working folder (`cd /opt/drishti`) and any variable in the line or the crontab, as above. 

**Windows Task Scheduler.** Save the job as a script, then register it:

```powershell
# C:\drishti\jobs\load-csv-organisation.ps1
$d = Get-Date -Format yyyy-MM-dd
Set-Location C:\drishti
py -3.13 tools\loaders\mrloader\mr_run.py --date $d --from "D:\mr\$d" --day C:\site\organisation.yaml --load --lake demo --server http://localhost:18480 *>> C:\drishti-logs\mr-organisation.log
exit $LASTEXITCODE
```

```text
mkdir C:\drishti-logs
schtasks /Create /F /TN "Drishti load organisation" /SC WEEKLY /D MON,TUE,WED,THU,FRI /ST 18:30 /TR "powershell -NoProfile -ExecutionPolicy Bypass -File C:\drishti\jobs\load-csv-organisation.ps1"
schtasks /Run /TN "Drishti load organisation"
schtasks /Query /TN "Drishti load organisation" /V /FO LIST
```

The task's *Last Run Result* is the script's exit code. By hand: Task Scheduler → Create Task → *Triggers* New (weekly, Monday to Friday, at the time your extract lands plus a margin) → *Actions* New → Start a program `powershell`, arguments `-NoProfile -ExecutionPolicy Bypass -File C:\drishti\jobs\load-csv-organisation.ps1`, **Start in** `C:\drishti` → *Run whether user is logged on or not*.

## When a day fails or is late

- **Run it again:** the same command. Each file replaces its kind's date **whole**; other dates are untouched, so a repeat never doubles rows.
- **A day that came late, or several days, oldest first:**

  ```bash
  for d in 2026-11-25 2026-11-26 2026-11-27; do
    python3.13 tools/loaders/mrloader/mr_run.py --date $d --from /data/mr/$d --day /site/organisation.yaml --load --lake demo || break
  done
  ```

  ```powershell
  foreach ($d in "2026-11-25","2026-11-26","2026-11-27") {
    py -3.13 tools\loaders\mrloader\mr_run.py --date $d --from "D:\mr\$d" --day C:\site\organisation.yaml --load --lake demo
    if ($LASTEXITCODE -ne 0) { break }
  }
  ```

- **Replace (restate) a date:** run it with that `--date` and the corrected files; to change one row, send the whole file again. A kind with a `RESTATEMENT` field takes `--restatement 1`, `2`, ... in `csv_load.py`.
- **Remove a date:** `python3.13 tools/drishti.py lake delete --lake demo --kind book --date 2026-11-24 --dry-run`, then without `--dry-run`; `mr_run.py --date D --from DIR --day FILE --delete-plan` prints every delete line of the day, vectors first. `python3.13 tools/drishti.py lake maintain` reclaims the space.
- **A master that skips a day** is answered from its last delivery; a row left out of today's file does **not** disappear: delete its earlier dates, or keep sending it with its status.
- **A kind that was refused** (masters and limits refuse on any rejected row) loaded nothing: fix its file and run the day again; `--keep-going` delivers the kinds that do not depend on it.
- A scheduler reads the exit code: **0** done, **1** refused or failed (one line says why), **2** loaded with rejected rows, **3** another load holds the lake's lock (wait and repeat).

## Check it loaded

```text
Linux     python3.13 tools/drishti.py lake status --lake demo
Windows   py -3.13 tools\drishti.py lake status --lake demo
```

```text
  table                   rows  files  size    partitions  dates  latest date  last build
  reference/book         12    1      2.7 KB  1           1      2026-11-18   2026-11-28 00:45:30Z
  reference/desk          6    1      2.7 KB  1           1      2026-11-18   2026-11-28 00:45:26Z
  reference/legal-entity  4    1      2.4 KB  1           1      2026-11-18   2026-11-28 00:45:22Z
```

(Real rows of this page's author's scratch load.) Expect one table row per kind with the run's `written` count and your date. With a server: `python3.13 tools/drishti.py dataload status` (the same as Admin → Data loads → Inbox), `python3.13 tools/drishti.py data get book --date 2026-11-27 --count`, and `python3.13 tools/drishti.py doctor` for the machine.

## Reference

**What it writes.** Tables reference/legal-entity, reference/desk, reference/book in the lake you name, symbolic `<domain>/<kind>`. The column-by-column format of every kind, its mapping and a sample: [CSV_FORMATS.md](../../contracts/CSV_FORMATS.md) ([`legal-entity`](../../contracts/CSV_FORMATS.md#legal-entity-bank-legal-entity-from-csv), [`desk`](../../contracts/CSV_FORMATS.md#desk-desk-from-csv), [`book`](../../contracts/CSV_FORMATS.md#book-book-from-csv) are its sections; the contracts are in the packs' contract pages). The files the loader writes are `<kind>.<date>.jsonl.gz` plus a `.ready` marker naming the lake.
**Input and file naming.** One CSV per kind (and one per child list), any name: the day file names them. Delimiter, encoding, decimal and thousands separators, date format and null words are **declared** in the mapping's `csv:` block over `kinds/shared/csv.yaml`, never guessed. A bank's own column names go in a mapping file of your own (a copy of `kinds/<kind>.yaml` with the `from:` columns renamed), named in the day file as `mapping: /site/mappings/<kind>.yaml`; never edit the shipped ones. Export from the source system; do not round-trip a file through Excel (long ids become `2.00001E+11`, dates are rewritten to the machine's locale): import every column as text if you must.

**A real example** (the shipped synthetic samples in `tools/loaders/mrloader/samples/`):

`legal-entity.csv`:

```text
ENTITY_ID,NAME,LEI,JURISDICTION,REGULATOR
GMO_SPOG,Drishti Bank plc,549347087B7238803C19,GB,PRA / FCA
CDADMM_H4705,Drishti Bank Canada,54930E36905CFA5EB092,CA,OSFI
```

`desk.csv`:

```text
DESK_ID,NAME,HEAD,LEGAL_ENTITY,ASSET_CLASSES
RATES TRADING-LON,Rates Trading London,A. Moreau,GMO_SPOG,Rates
FX OPTIONS-ZRH,FX Options Zurich,B. Keller,GMO_SPOG,FX
```

**Source and licence.** Your own extracts; the samples are synthetic and fictitious (`mr_gen.py`, seeded), and real extracts are never committed.

**Errors and fixes.**

| You see | It means | Do this |
|---|---|---|
| *kind refused* after a load | masters refuse the whole file on any rejected row (`on-reject: refuse`) | fix the rows named in `mr-<date>/<kind>.<date>.rejects.csv`, run the day again |
| reject `DUPLICATE_KEY` | the same ENTITY_ID, DESK_ID or BOOK_ID twice | one row per key |
| reject `BAD_DATE` / `BAD_NUMBER` | a locale date, a currency symbol, a decimal comma | export ISO dates and plain numbers, or declare `csv: {dates: DD/MM/YYYY}` / `decimal: ","` in your mapping |
| reject `MISSING_REQUIRED`, `EXCEL_NUMBER_ID` | an empty required cell, an id turned into a number | fix the source and send again |
| *no server jar in .../lib* | the tools read the packs from the jar | `python3.13 tools/drishti.py build jar` |
| *lake ...: a lake's name, lower-case letters...* | you gave a folder to `--lake` | give the name: `--lake demo` |
| Windows: *can't open file*, a path cut at a space | an unquoted path with a space | put the path in quotes |

More: [BANK_DATA_GUIDE.md section 7](../BANK_DATA_GUIDE.md#7-the-daily-run-csv-extracts-to-var-and-exposure-inputs) (the daily run), [the mrloader README](../../../tools/loaders/mrloader/README.md) and the other CSV families: [organisation](load-csv-organisation.md), [counterparty](load-csv-counterparty.md), [limits and factors](load-csv-limits-and-factors.md), [sensitivities and P&L](load-csv-sensitivities-and-pnl.md).
