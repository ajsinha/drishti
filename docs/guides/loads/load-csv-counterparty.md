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
# Load counterparties, agreements, netting sets, collateral and credit limits every day

The counterparty-risk inputs the bank sends: counterparty groups and counterparties, master agreements, CSAs (with their eligible collateral), netting sets, collateral balances (with their positions) and credit limits (with their buckets). The exposure and counterparty engines read them. One command converts and checks the day's CSV files kind by kind, in dependency order, and loads them into the lake: **a day that fails leaves the lake as it was** for the kind that failed, and the kinds after it are not loaded.

Run every command from the Drishti folder (the one that holds `tools`): `/opt/drishti` on Linux and `C:\drishti` on Windows in the examples. Nothing is installed for you and there is no virtual environment: use the Python you run Drishti with. Loading needs Java 21 and the server jar in `lib` (`drishti.py build jar`); converting needs neither. A date in an example is an example: use the business date of your file.

| Kind | Your file (any name; `--csv`) | Child file | Table written | Load first | Key |
|---|---|---|---|---|---|
| `counterparty-group` | `counterparty-group.csv` | - | `reference/counterparty-group` | legal-entity | `GROUP_ID` |
| `counterparty` | `counterparty.csv` | - | `reference/counterparty` | legal-entity, counterparty-group | `COUNTERPARTY_ID` |
| `agreement` | `agreement.csv` | - | `legal/agreement` | counterparty | `AGREEMENT_ID` |
| `csa` | `csa.csv` | `ELIGIBLE=csa.ELIGIBLE.csv` | `legal/csa` | agreement | `CSA_ID` |
| `netting-set` | `netting-set.csv` | - | `legal/netting-set` | csa, counterparty, legal-entity | `NETTING_SET_ID` |
| `collateral-balance` | `collateral-balance.csv` | `POSITIONS=collateral-balance.POSITIONS.csv` | `(see `lake status`)` | netting-set | `BALANCE_ID` |
| `credit-limit` | `credit-limit.csv` | `BUCKETS=credit-limit.BUCKETS.csv` | `legal/credit-limit` | counterparty | `LIMIT_ID` |

## Every day

1. **Write the day file once** (`/site/counterparty.yaml`, `C:\site\counterparty.yaml`, kept with your site's settings, not in the product). It says which file is which kind; `{YYYYMMDD}` in a name is replaced by the date. `tools/loaders/mrloader/days/sample.yaml` is the full example (all fifteen kinds).

```yaml
format: 1
lake: demo
kinds:
  counterparty-group: {csv: "counterparty-group.csv"}
  counterparty:       {csv: "counterparty.csv"}
  agreement:          {csv: "agreement.csv"}
  csa:                {csv: "csa.csv", child: {ELIGIBLE: "csa.ELIGIBLE.csv"}}
  netting-set:        {csv: "netting-set.csv"}
  collateral-balance: {csv: "collateral-balance.csv", child: {POSITIONS: "collateral-balance.POSITIONS.csv"}}
  credit-limit:       {csv: "credit-limit.csv", child: {BUCKETS: "credit-limit.BUCKETS.csv"}}
required: [counterparty]
```

2. **Drop the day's files** in one folder (`/data/mr/2026-11-27/`), then check and load:

```text
Linux     cd /opt/drishti
          python3.13 tools/loaders/mrloader/mr_run.py --date 2026-11-27 --from /data/mr/2026-11-27 --day /site/counterparty.yaml --dry-run
          python3.13 tools/loaders/mrloader/mr_run.py --date 2026-11-27 --from /data/mr/2026-11-27 --day /site/counterparty.yaml --load --lake demo --server http://localhost:18480
Windows   cd C:\drishti
          py -3.13 tools\loaders\mrloader\mr_run.py --date 2026-11-27 --from D:\mr\2026-11-27 --day C:\site\counterparty.yaml --dry-run
          py -3.13 tools\loaders\mrloader\mr_run.py --date 2026-11-27 --from D:\mr\2026-11-27 --day C:\site\counterparty.yaml --load --lake demo --server http://localhost:18480
```

`--dry-run` converts and checks and writes nothing. In place of `--load` use `--inbox` to hand the files to the running server (they are copied to `data/dataload/inbox`, files first and markers last, in dependency order, and the node that owns the data load loads them). **The business date is the `--date` you type**, never a date in a file name; a file with no rows for it, or rows with another date, is refused with the count. `--keep-going` delivers the kinds that do not depend on a refused one.
**One kind only:** `python3.13 tools/loaders/mrloader/csv_load.py --kind credit-limit --csv FILE --child BUCKETS=credit-limit.BUCKETS.csv --date 2026-11-27 --load --lake demo` (`--template` prints a kind's header and an example row; `--kinds` lists every kind; `--limit N` is a trial).
**The lake must describe the table.** A writer never creates a table the lake file does not describe: a load into a table with no entry stops with *lake X has no table for DOMAIN.TABLE: add its entry to config/lakes/X.yaml*. The shipped `demo` lake has the entries of the standard kinds; `risklake` ships empty (`tables: {}`), so copy the entries you need from the worked example in [DATA_LAKE_GUIDE.md section 2.1](../DATA_LAKE_GUIDE.md#21-a-worked-example-configlakesrisklakeyaml) once, keep the file with your site's settings, and point the connectors at the lake (`config/connectors/*.yaml`, `lake:`). The commands below say `--lake demo`; use your lake's name.

**What you see** (real runs of this page's author on the shipped synthetic samples, date 2026-11-20, into a scratch data folder). The dry run:

```text
kind                   status        read  written rejected  links seconds
--------------------------------------------------------------------------
counterparty-group     DONE             4        4        0      0       0
counterparty           DONE             8        8        0      0       0
agreement              DONE             8        8        0      0       0
csa                    DONE             6        6        0      0       0
netting-set            DONE             8        8        0      0       0
collateral-balance     DONE             6        6        0      0       0
credit-limit           DONE             8        8        0      0       0
```

Exit code 0. Then a real load of this page's author into the shipped `demo` lake: the first four kinds loaded; the fifth stopped on the demo lake file's missing entry (see Errors), and **the kinds after a failed one are not loaded**:

```text
2026-11-27T19:57:03-05:00 lake-build ok exit=0 run=20261127T195700.289-3617182 domain=reference kind=counterparty-group engine=java-loader mode=overwrite-dates rows=4 rejected=0 dates=1 partitions=1 seconds=2.9
2026-11-27T19:57:06-05:00 lake-build ok exit=0 run=20261127T195703.443-3617382 domain=reference kind=counterparty engine=java-loader mode=overwrite-dates rows=8 rejected=0 dates=1 partitions=1 seconds=3.034
2026-11-27T19:57:09-05:00 lake-build ok exit=0 run=20261127T195706.671-3617588 domain=legal kind=agreement engine=java-loader mode=overwrite-dates rows=8 rejected=0 dates=1 partitions=1 seconds=3.098
2026-11-27T19:57:12-05:00 lake-build ok exit=0 run=20261127T195709.993-3617774 domain=legal kind=csa engine=java-loader mode=overwrite-dates rows=6 rejected=0 dates=1 partitions=1 seconds=2.966
lake build: lake demo has no table for legal.netting-set: add its entry to /home/ashutosh/IdeaProjects/drishti-lanes/i8-load-guides/config/lakes/demo.yaml (tables: [compute-demo.path-book, compute-demo.path-str
2026-11-27T19:57:14-05:00 lake-build failed exit=1 run=20261127T195713.161-3617975 mode=overwrite-dates seconds=0.933 error="lake demo has no table for legal.netting-set: add its entry to /home/ashutosh/IdeaPro
2026-11-27T19:57:14 ERROR lake build of netting-set.2026-11-20.jsonl.gz exited 1: the files after it were not loaded
kind                   status        read  written rejected  links seconds
--------------------------------------------------------------------------
counterparty-group     DONE             4        4        0      0       0
counterparty           DONE             8        8        0      0       0
agreement              DONE             8        8        0      0       0
csa                    DONE             6        6        0      0       0
netting-set            DONE             8        8        0      0       0
collateral-balance     DONE             6        6        0      0       0
credit-limit           DONE             8        8        0      0       0
```

Rejected rows are written to `mr-<date>/<kind>.<date>.rejects.csv` with a code and the row's number.

## Schedule it

Schedule it **after your extract has landed** (the load does not wait for a file that has not come):

**Linux cron** (`crontab -e`; mind that `%` must be written `\%` in a crontab):

```text
SHELL=/bin/bash
45 18 * * 1-5 cd /opt/drishti && d=$(date +\%F) && python3.13 tools/loaders/mrloader/mr_run.py --date $d --from /data/mr/$d --day /site/counterparty.yaml --load --lake demo --server http://localhost:18480 >> $HOME/drishti-mr-counterparty.log 2>&1
```

Cron starts with an empty environment: put the working folder (`cd /opt/drishti`) and any variable in the line or the crontab, as above. 

**Windows Task Scheduler.** Save the job as a script, then register it:

```powershell
# C:\drishti\jobs\load-csv-counterparty.ps1
$d = Get-Date -Format yyyy-MM-dd
Set-Location C:\drishti
py -3.13 tools\loaders\mrloader\mr_run.py --date $d --from "D:\mr\$d" --day C:\site\counterparty.yaml --load --lake demo --server http://localhost:18480 *>> C:\drishti-logs\mr-counterparty.log
exit $LASTEXITCODE
```

```text
mkdir C:\drishti-logs
schtasks /Create /F /TN "Drishti load counterparty" /SC WEEKLY /D MON,TUE,WED,THU,FRI /ST 18:45 /TR "powershell -NoProfile -ExecutionPolicy Bypass -File C:\drishti\jobs\load-csv-counterparty.ps1"
schtasks /Run /TN "Drishti load counterparty"
schtasks /Query /TN "Drishti load counterparty" /V /FO LIST
```

The task's *Last Run Result* is the script's exit code. By hand: Task Scheduler → Create Task → *Triggers* New (weekly, Monday to Friday, at the time your extract lands plus a margin) → *Actions* New → Start a program `powershell`, arguments `-NoProfile -ExecutionPolicy Bypass -File C:\drishti\jobs\load-csv-counterparty.ps1`, **Start in** `C:\drishti` → *Run whether user is logged on or not*.

## When a day fails or is late

- **Run it again:** the same command. Each file replaces its kind's date **whole**; other dates are untouched, so a repeat never doubles rows.
- **A day that came late, or several days, oldest first:**

  ```bash
  for d in 2026-11-25 2026-11-26 2026-11-27; do
    python3.13 tools/loaders/mrloader/mr_run.py --date $d --from /data/mr/$d --day /site/counterparty.yaml --load --lake demo || break
  done
  ```

  ```powershell
  foreach ($d in "2026-11-25","2026-11-26","2026-11-27") {
    py -3.13 tools\loaders\mrloader\mr_run.py --date $d --from "D:\mr\$d" --day C:\site\counterparty.yaml --load --lake demo
    if ($LASTEXITCODE -ne 0) { break }
  }
  ```

- **Replace (restate) a date:** run it with that `--date` and the corrected files; to change one row, send the whole file again. A kind with a `RESTATEMENT` field takes `--restatement 1`, `2`, ... in `csv_load.py`.
- **Remove a date:** `python3.13 tools/drishti.py lake delete --lake demo --kind credit-limit --date 2026-11-24 --dry-run`, then without `--dry-run`; `mr_run.py --date D --from DIR --day FILE --delete-plan` prints every delete line of the day, vectors first. `python3.13 tools/drishti.py lake maintain` reclaims the space.
- **A master that skips a day** is answered from its last delivery; a row left out of today's file does **not** disappear: delete its earlier dates, or keep sending it with its status.
- **A kind that was refused** (masters and limits refuse on any rejected row) loaded nothing: fix its file and run the day again; `--keep-going` delivers the kinds that do not depend on it.
- A scheduler reads the exit code: **0** done, **1** refused or failed (one line says why), **2** loaded with rejected rows, **3** another load holds the lake's lock (wait and repeat).

## Check it loaded

```text
Linux     python3.13 tools/drishti.py lake status --lake demo
Windows   py -3.13 tools\drishti.py lake status --lake demo
```

```text
  table                         rows  files  size    partitions  dates  latest date  last build
  legal/agreement               8     1      2.9 KB  1           1      2026-11-18   2026-11-28 00:45:41Z
  legal/csa                     6     1      3.2 KB  1           1      2026-11-18   2026-11-28 00:45:45Z
  reference/counterparty        8     1      3.6 KB  1           1      2026-11-18   2026-11-28 00:45:37Z
  reference/counterparty-group  4     1      2.2 KB  1           1      2026-11-18   2026-11-28 00:45:33Z
```

(Real rows of this page's author's scratch load.) Expect one table row per kind with the run's `written` count and your date. With a server: `python3.13 tools/drishti.py dataload status` (the same as Admin → Data loads → Inbox), `python3.13 tools/drishti.py data get credit-limit --date 2026-11-27 --count`, and `python3.13 tools/drishti.py doctor` for the machine.

## Reference

**What it writes.** Tables reference/counterparty-group, reference/counterparty, legal/agreement, legal/csa, legal/netting-set, legal/credit-limit in the lake you name, symbolic `<domain>/<kind>`. The column-by-column format of every kind, its mapping and a sample: [CSV_FORMATS.md](../../contracts/CSV_FORMATS.md) ([`counterparty-group`](../../contracts/CSV_FORMATS.md#counterparty-group-counterparty-group-from-csv), [`counterparty`](../../contracts/CSV_FORMATS.md#counterparty-counterparty-from-csv), [`agreement`](../../contracts/CSV_FORMATS.md#agreement-master-agreement-from-csv), [`csa`](../../contracts/CSV_FORMATS.md#csa-credit-support-annex-from-csv), [`netting-set`](../../contracts/CSV_FORMATS.md#netting-set-netting-set-from-csv), [`collateral-balance`](../../contracts/CSV_FORMATS.md#collateral-balance-collateral-balance-from-csv), [`credit-limit`](../../contracts/CSV_FORMATS.md#credit-limit-credit-limit-from-csv) are its sections; the contracts are in the packs' contract pages). The files the loader writes are `<kind>.<date>.jsonl.gz` plus a `.ready` marker naming the lake.
**Input and file naming.** One CSV per kind (and one per child list), any name: the day file names them. Delimiter, encoding, decimal and thousands separators, date format and null words are **declared** in the mapping's `csv:` block over `kinds/shared/csv.yaml`, never guessed. A bank's own column names go in a mapping file of your own (a copy of `kinds/<kind>.yaml` with the `from:` columns renamed), named in the day file as `mapping: /site/mappings/<kind>.yaml`; never edit the shipped ones. Export from the source system; do not round-trip a file through Excel (long ids become `2.00001E+11`, dates are rewritten to the machine's locale): import every column as text if you must.

**A real example** (the shipped synthetic samples in `tools/loaders/mrloader/samples/`):

`counterparty.csv`:

```text
COUNTERPARTY_ID,NAME,LEI,RATING,SECTOR,COUNTRY,TYPE,GROUP,CREDIT_LIMIT,LEGAL_ENTITY,KYC_STATUS,KYC_RISK_RATING,KYC_LAST_REVIEW,EMIR_CLASSIFICATION,PD_1Y
CP-ALDERSHOT-FUND,Aldershot Pension Fund,54934B9136D3D0B07F46,AA-,Pensions,GB,Pension fund,GRP-ALDERSHOT,LIM-ALDERSHOT-FUND,LE-GMO-SPOG,Approved,Standard,2026-03-14,FC,0.0004
CP-ALDERSHOT-TRUST,Aldershot Pension Trust,5493A195A4236E865B56,A+,Pensions,GB,Pension fund,GRP-ALDERSHOT,LIM-ALDERSHOT-TRUST,LE-CDADMM-H4705,Approved,Standard,2026-03-14,FC,0.0007
```

`netting-set.csv`:

```text
NETTING_SET_ID,COUNTERPARTY,AGREEMENT,CSA,CREDIT_LIMIT,COLLATERAL_BALANCE,LEGAL_ENTITY,NETTING_ENFORCEABLE
NS-ALDERSHOT-FUND,CP-ALDERSHOT-FUND,AGR-ALDERSHOT-FUND-ISDA,CSA-ALDERSHOT-FUND,LIM-ALDERSHOT-FUND,COLL-ALDERSHOT-FUND,LE-GMO-SPOG,Y
NS-ALDERSHOT-TRUST,CP-ALDERSHOT-TRUST,AGR-ALDERSHOT-TRUST-ISDA,CSA-ALDERSHOT-TRUST,LIM-ALDERSHOT-TRUST,COLL-ALDERSHOT-TRUST,LE-CDADMM-H4705,Y
```

**Source and licence.** Your own extracts; the samples are synthetic and fictitious (`mr_gen.py`, seeded), and real extracts are never committed.

**Errors and fixes.**

| You see | It means | Do this |
|---|---|---|
| *lake demo has no table for legal.netting-set* (the same for `legal.credit-limit`) | the shipped `demo` lake file lists these two kinds under another domain name (`credit.netting-set`, `credit.credit-limit`), so the load of the pack's own table name is refused; seen on 2026-11-27 and **not fixed here** | add the entry for the table the message names to `config/lakes/<lake>.yaml` (`legal.netting-set: {id: NETTING_SET_ID}`, `legal.credit-limit: {id: LIMIT_ID}`), keep the file with your site's settings, run the day again; or load into a lake whose file already has them |
| reject `BAD_DATE` / `BAD_NUMBER` | a locale date, a currency symbol, a decimal comma | export ISO dates and plain numbers, or declare `csv: {dates: DD/MM/YYYY}` / `decimal: ","` in your mapping |
| reject `MISSING_REQUIRED`, `EXCEL_NUMBER_ID` | an empty required cell, an id turned into a number | fix the source and send again |
| *no server jar in .../lib* | the tools read the packs from the jar | `python3.13 tools/drishti.py build jar` |
| *lake ...: a lake's name, lower-case letters...* | you gave a folder to `--lake` | give the name: `--lake demo` |
| Windows: *can't open file*, a path cut at a space | an unquoted path with a space | put the path in quotes |

More: [BANK_DATA_GUIDE.md section 7](../BANK_DATA_GUIDE.md#7-the-daily-run-csv-extracts-to-var-and-exposure-inputs) (the daily run), [the mrloader README](../../../tools/loaders/mrloader/README.md) and the other CSV families: [organisation](load-csv-organisation.md), [counterparty](load-csv-counterparty.md), [limits and factors](load-csv-limits-and-factors.md), [sensitivities and P&L](load-csv-sensitivities-and-pnl.md).
