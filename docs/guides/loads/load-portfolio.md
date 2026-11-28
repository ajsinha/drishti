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
# Load portfolios (books) every day

Drishti has no *portfolio* kind: a booking portfolio **is the book**. The PVF extract's `portfolio_d2.csv` (PORTFOLIO_D2: the portfolios with their desks, groups and limits) loads as the **`reference/book`** table, one book per portfolio valid on the date. The same file is also joined into every scenario-PV record by [the PVF load](load-scenario-pv.md); you load the book table when you want the masters themselves in the lake (Admin pages, hierarchies, `TT_BOOK` links).

Run every command from the Drishti folder (the one that holds `tools`): `/opt/drishti` on Linux and `C:\drishti` on Windows in the examples. Nothing is installed for you and there is no virtual environment: use the Python you run Drishti with. Loading needs Java 21 and the server jar in `lib` (`drishti.py build jar`); converting needs neither. A date in an example is an example: use the business date of your file.

## Every day

```text
Linux     cd /opt/drishti
          python3.13 tools/loaders/mrloader/csv_load.py --kind book --source pvf --csv /data/pvf/portfolio_d2.csv --date 2026-11-27 --dry-run
          python3.13 tools/loaders/mrloader/csv_load.py --kind book --source pvf --csv /data/pvf/portfolio_d2.csv --date 2026-11-27 --load --lake demo
Windows   cd C:\drishti
          py -3.13 tools\loaders\mrloader\csv_load.py --kind book --source pvf --csv D:\pvf\portfolio_d2.csv --date 2026-11-27 --dry-run
          py -3.13 tools\loaders\mrloader\csv_load.py --kind book --source pvf --csv D:\pvf\portfolio_d2.csv --date 2026-11-27 --load --lake demo
```

`--source pvf` selects the PVF column names (`kinds/book.pvf.yaml`); without it the canonical `book.csv` columns are read (`BOOK_ID, NAME, DESK, ...`, the sample below). The date is the `--date` you type; only the rows valid on it (`VALID_DT <= date < VALID_TO_DT`) are read. `--inbox` instead of `--load` hands the files to the running server. A book needs its desk first: load `desk` (and `legal-entity`) before it, see [organisation](load-csv-organisation.md).

**What you see when it worked** (a real dry run of this page's author on the synthetic 20-portfolio file of `pvf_gen.py`; the load itself is the same command with `--load`, whose output is the `lake-build ok ...` line of every load):

```text
2026-11-27T19:48:00 INFO book 2026-11-27: 20 rows read, 20 written, 0 rejected
```

and, for the shipped canonical sample, a real load into a scratch data folder:

```text
2026-11-27T19:45:30-05:00 lake-build ok exit=0 run=20261127T194527.110-3598283 domain=reference kind=book engine=java-loader mode=overwrite-dates rows=12 ...
```

## Schedule it

Schedule it after the PVF extract, next to the PVF job (the PVF run itself does not load the book table). Cron, weekdays 20:05:

```text
SHELL=/bin/bash
5 20 * * 1-5 cd /opt/drishti && python3.13 tools/loaders/mrloader/csv_load.py --kind book --source pvf --csv /data/pvf/portfolio_d2.csv --date $(date +\%F) --load --lake demo --server http://localhost:18480 >> $HOME/drishti-book.log 2>&1
```

Windows (the script computes the date; register it as on [the trade page](load-trade.md#schedule-it)):

```powershell
# C:\drishti\jobs\load-book.ps1
Set-Location C:\drishti
py -3.13 tools\loaders\mrloader\csv_load.py --kind book --source pvf --csv D:\pvf\portfolio_d2.csv --date (Get-Date -Format yyyy-MM-dd) --load --lake demo --server http://localhost:18480 *>> C:\drishti-logs\book.log
exit $LASTEXITCODE
```

```text
schtasks /Create /F /TN "Drishti load books" /SC WEEKLY /D MON,TUE,WED,THU,FRI /ST 20:05 /TR "powershell -NoProfile -ExecutionPolicy Bypass -File C:\drishti\jobs\load-book.ps1"
```

## When a day fails or is late

- **Run it again, a late day, a restated day:** the same command with that `--date`; the date's books are replaced **whole**. Several days, oldest first, as in the loops of [the scenario PV page](load-scenario-pv.md#when-a-day-fails-or-is-late) with `csv_load.py` in place of `pvf_run.py`.
- **A master that skips a day** is answered from its last delivery (reference tables are read as *effective*); a book left out of today's file does **not** disappear: delete its earlier dates or keep sending it with its status.
- **Take a date out:** `python3.13 tools/drishti.py lake delete --lake demo --kind book --date 2026-11-24 --dry-run`, then without `--dry-run`.
- A kind with `on-reject: refuse` (masters, limits) **refuses the whole file on any rejected row**: fix the file, run again; nothing was loaded. A scheduler reads the exit code: **0** done, **1** refused or failed (one line says why), **2** loaded with rejected rows, **3** another load holds the lake's lock (wait and repeat).

## Check it loaded

```text
Linux     python3.13 tools/drishti.py lake status --lake demo
Windows   py -3.13 tools\drishti.py lake status --lake demo
```

```text
  table             rows  files  size    partitions  dates  latest date  last build
  reference/book    12    1      2.7 KB  1           1      2026-11-18   2026-11-28 00:45:30Z
```

(A real line from a scratch load of the canonical sample.) The row count is the run's `written`. With a server: `python3.13 tools/drishti.py dataload status`.

## Reference

**What it writes.** `reference/book`, kind `book`: [the CSV format of `book`](../../contracts/CSV_FORMATS.md#book-book-from-csv) (every column, its mapping and sample). The PVF mapping is `tools/loaders/mrloader/kinds/book.pvf.yaml`: `BOOK_ID` is built from the portfolio name (`PORT_100000` becomes `BOOK-PORT-100000`), `NAME` from the description else the name, `DESK` from `DESK_NM` else `PORTFOLIO_GROUP_NM` (`DESK-UNKNOWN` when neither).
**Input and date rules.** One CSV, a row per portfolio. Delimiter, encoding, decimals and date format are **declared** in the mapping's `csv:` block over `kinds/shared/csv.yaml`, never guessed; a cell that does not fit rejects its row with a code (`BAD_DATE`, `BAD_NUMBER`, `MISSING_REQUIRED`, `EXCEL_NUMBER_ID`, `DUPLICATE_KEY`). Names are free (`--csv` points at the file).

**A real example** (the canonical sample `tools/loaders/mrloader/samples/book.csv`):

```text
BOOK_ID,NAME,DESK,ACCOUNTING_TREATMENT,REGULATORY_BOOK,VOLCKER
PORT_100000,Rates Trading-Lon book 1,RATES TRADING-LON,Fair value (FVTPL),Trading book,Market making
PORT_100001,Rates Trading-Lon book 2,RATES TRADING-LON,Fair value (FVTPL),Trading book,Market making
PORT_100002,Fx Options-Zrh book 1,FX OPTIONS-ZRH,Fair value (FVTPL),Trading book,Market making
```

**Source and licence.** Your own extract; synthetic samples only in the repository.

**Errors and fixes.**

| You see | It means | Do this |
|---|---|---|
| reject `MISSING_REQUIRED` | an empty required cell | fix the source; send the file again |
| reject `DUPLICATE_KEY` | the same book twice | one row per portfolio |
| *kind refused* | masters refuse on any rejected row | fix the file, run again |
| *lake demo has no table for reference.book* | the lake file lacks the entry | add `reference.book: {id: BOOK_ID}` to `config/lakes/<lake>.yaml` |

More: [CSV_FORMATS.md](../../contracts/CSV_FORMATS.md), [BANK_DATA_GUIDE.md section 7](../BANK_DATA_GUIDE.md#7-the-daily-run-csv-extracts-to-var-and-exposure-inputs) and [the mrloader README](../../../tools/loaders/mrloader/README.md).
