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
# Load scenario PVs (PVF) every day

The PVF extract is the bank's position-level scenario PVs: for each position the base PV (path -1) and the PVs under each of 251 historical scenarios (paths 0 to 250). `pvf_run.py` joins the three files of the day, maps and checks them, and loads
**`risk/scenario-pv`** (one record per position with its vector), preceded by the day's **`risk/scenario-set`** and, when you send it, **`risk/fx-scenario`**. The VaR, explain and *why it moved* engines read these tables.

Run every command from the Drishti folder (the one that holds `tools`): `/opt/drishti` on Linux and `C:\drishti` on Windows in the examples. Nothing is installed for you and there is no virtual environment: use the Python you run Drishti with. Loading needs Java 21 and the server jar in `lib` (`drishti.py build jar`); converting needs neither. A date in an example is an example: use the business date of your file. The PVF scripts also need Polars and NumPy: `python3.13 -m pip install -r drishti-console/requirements-polars.txt` (Windows `py -3.13 -m pip install -r drishti-console\requirements-polars.txt`); `drishti.py setup` prints the exact command if they are missing.

## Every day

Drop the three files in one folder (`/data/pvf`, Windows `D:\pvf`): `pvf_path_YYYYMMDD.csv`, `pos_YYYYMMDD.csv`, `portfolio_d2.csv` (and `fx_YYYYMMDD.csv` if you send FX), then check, then load. **The business date is the `--date` you type, never the digits in a file name.**

```text
Linux     cd /opt/drishti
          python3.13 tools/loaders/mrloader/pvf_run.py --pv /data/pvf/pvf_path_20261127.csv --portfolio /data/pvf/portfolio_d2.csv --positions /data/pvf/pos_20261127.csv --date 2026-11-27 --dry-run
          python3.13 tools/loaders/mrloader/pvf_run.py --pv /data/pvf/pvf_path_20261127.csv --portfolio /data/pvf/portfolio_d2.csv --positions /data/pvf/pos_20261127.csv --date 2026-11-27 --load --lake demo --server http://localhost:18480
Windows   cd C:\drishti
          py -3.13 tools\loaders\mrloader\pvf_run.py --pv D:\pvf\pvf_path_20261127.csv --portfolio D:\pvf\portfolio_d2.csv --positions D:\pvf\pos_20261127.csv --date 2026-11-27 --dry-run
          py -3.13 tools\loaders\mrloader\pvf_run.py --pv D:\pvf\pvf_path_20261127.csv --portfolio D:\pvf\portfolio_d2.csv --positions D:\pvf\pos_20261127.csv --date 2026-11-27 --load --lake demo --server http://localhost:18480
```

`--dry-run` converts nothing and writes nothing; `--plan` with `--load` says what each load would do. `--lake` is the lake your connectors read (`demo` as shipped); `--server` tells a running server that the date landed (leave it off when the server is stopped; a secured server also needs `--token-file FILE`).
Instead of loading yourself, hand the files to the running server with `--inbox` in place of `--load`: they are copied to `data/dataload/inbox` and the node that owns the data load loads them. Order is always **set, FX, PVs**; the driver does it for you and stops at the first failure.
**The lake must describe the table.** A writer never creates a table the lake file does not describe: a load into a table with no entry stops with *lake X has no table for DOMAIN.TABLE: add its entry to config/lakes/X.yaml*. The shipped `demo` lake has the entries of the standard kinds; `risklake` ships empty (`tables: {}`), so copy the entries you need from the worked example in [DATA_LAKE_GUIDE.md section 2.1](../DATA_LAKE_GUIDE.md#21-a-worked-example-configlakesrisklakeyaml) once, keep the file with your site's settings, and point the connectors at the lake (`config/connectors/*.yaml`, `lake:`). The commands below say `--lake demo`; use your lake's name.

**What you see when it worked** (a real run of this page's author on a synthetic 300-position extract made by `pvf_gen.py`, loaded into a scratch data folder; lines cut for width):

```text
INFO pvf join of 2026-11-27: positions <scratch>/pvf/pos_20261127.csv, portfolios <scratch>/pvf/portfolio_d2.csv, paths <scratch>/pvf/pvf_path_20261127.csv
INFO read 300 positions and 20 portfolio rows valid on 2026-11-27 (0.0s)
INFO scanned 63,444 path rows into 63,444 (position, path) pairs (0.0s)
INFO wrote 284 of 284 positions
INFO summary: 300 positions in, 284 written, 16 excluded {'MISSING_BASE_PV': 16}; fills (forward): 77 positions, path 0 from BASE_PV in 46, 9,999 paths (14.03% of all); 0.1s, peak memory 154.1 MB
INFO mapped 284 rows: 284 records, 0 audited {} (0.4s)
INFO running: .../tools/drishti.py lake build --engine java --from <scratch>/pvf/mr-2026-11-27/scenario-set.2026-11-27.jsonl.gz --lake demo --kind scenario-set --mode overwrite-dates
2026-11-27T19:44:55-05:00 lake-build ok exit=0 run=20261127T194451.786-3596980 domain=risk kind=scenario-set engine=java-loader mode=overwrite-dates rows=1 rejected=0 dates=1 partitions=1 seconds=3.503
INFO running: .../tools/drishti.py lake build --engine java --from <scratch>/pvf/mr-2026-11-27/scenario-pv.2026-11-27.jsonl.gz --lake demo --kind scenario-pv --mode overwrite-dates
business date  SCENARIO_SET_ID   GRAIN                  rows
2026-11-27     HS-PVF-251        POSITION                284
documents read: 284, rows written: 284, rejected: 0
2026-11-27T19:44:59-05:00 lake-build ok exit=0 run=20261127T194455.508-3597168 domain=risk kind=scenario-pv engine=java-loader mode=overwrite-dates rows=284 rejected=0 dates=1 partitions=1 seconds=3.965
INFO pvf run of 2026-11-27: 300 positions in, 16 excluded by the join, 0 by the mapping, 284 records written; 0.4s, peak memory 156.4 MB; <scratch>/pvf/mr-2026-11-27
INFO left out: 16 positions by the join, 0 rows by the mapping: see the audit files
```

Exit code 0. What was left out is in `mr-2026-11-27/` beside the output: `pvf_joined.2026-11-27.audit.csv` (positions excluded: `MISSING_PORTFOLIO`, `MISSING_BASE_PV`), `...fills.csv` (gaps in the paths filled by `--fill-policy forward|base|refuse`), `scenario-pv.2026-11-27.rejects.csv` and `scenario-pv.2026-11-27.summary.json` (rows, seconds, peak memory). A full extract (200,000 positions, about 37 million path rows) takes minutes and a few GB; try `--limit 2000` first, and `--chunk 20000` if memory is short.

If your 251 scenario days follow your own calendar, give them once per date: `--scenario-dates /data/pvf/scenario_dates_20261127.txt` (one date a line, oldest first); otherwise the 251 weekdays before the date are used. FX alone: `tools/loaders/mrloader/pvf_fx_scenario.py --date D --fx FILE --inbox`; the set alone: `tools/loaders/mrloader/pvf_scenario_set.py --date D --dates FILE --inbox`.

## Schedule it

Schedule the load **after your PVF extract has landed**, with the date computed (never typed):

**Linux cron** (`crontab -e`; mind that `%` must be written `\%` in a crontab):

```text
SHELL=/bin/bash
0 20 * * 1-5 cd /opt/drishti && d=$(date +\%Y\%m\%d) && iso=$(date +\%F) && python3.13 tools/loaders/mrloader/pvf_run.py --pv /data/pvf/pvf_path_$d.csv --portfolio /data/pvf/portfolio_d2.csv --positions /data/pvf/pos_$d.csv --date $iso --load --lake demo --server http://localhost:18480 >> $HOME/drishti-pvf.log 2>&1
```

Cron starts with an empty environment: put the working folder (`cd /opt/drishti`) and any variable in the line or the crontab, as above. 

**Windows Task Scheduler.** Save the job as a script, then register it:

```powershell
# C:\drishti\jobs\load-pvf.ps1
$d = Get-Date -Format yyyyMMdd
$iso = Get-Date -Format yyyy-MM-dd
Set-Location C:\drishti
py -3.13 tools\loaders\mrloader\pvf_run.py --pv "D:\pvf\pvf_path_$d.csv" --portfolio D:\pvf\portfolio_d2.csv `
  --positions "D:\pvf\pos_$d.csv" --date $iso --load --lake demo --server http://localhost:18480 *>> C:\drishti-logs\pvf.log
exit $LASTEXITCODE
```

```text
mkdir C:\drishti-logs
schtasks /Create /F /TN "Drishti load PVF" /SC WEEKLY /D MON,TUE,WED,THU,FRI /ST 20:00 /TR "powershell -NoProfile -ExecutionPolicy Bypass -File C:\drishti\jobs\load-pvf.ps1"
schtasks /Run /TN "Drishti load PVF"
schtasks /Query /TN "Drishti load PVF" /V /FO LIST
```

The task's *Last Run Result* is the script's exit code. By hand: Task Scheduler → Create Task → *Triggers* New (weekly, Monday to Friday, at the time your extract lands plus a margin) → *Actions* New → Start a program `powershell`, arguments `-NoProfile -ExecutionPolicy Bypass -File C:\drishti\jobs\load-pvf.ps1`, **Start in** `C:\drishti` → *Run whether user is logged on or not*.

## When a day fails or is late

- **Run it again:** the same command. Each file replaces its date **whole**; the other dates are untouched.
- **A day that came late, or several days, oldest first** (each date's set must precede its PVs):

  ```bash
  for d in 20261125 20261126 20261127; do
    iso="${d:0:4}-${d:4:2}-${d:6:2}"
    python3.13 tools/loaders/mrloader/pvf_run.py --pv /data/pvf/pvf_path_$d.csv --portfolio /data/pvf/portfolio_d2.csv \
       --positions /data/pvf/pos_$d.csv --date $iso --load --lake demo --server http://localhost:18480 || break
  done
  ```

  ```powershell
  foreach ($d in "20261125","20261126","20261127") {
    $iso = $d.Substring(0,4) + "-" + $d.Substring(4,2) + "-" + $d.Substring(6,2)
    py -3.13 tools\loaders\mrloader\pvf_run.py --pv D:\pvf\pvf_path_$d.csv --portfolio D:\pvf\portfolio_d2.csv `
       --positions D:\pvf\pos_$d.csv --date $iso --load --lake demo --server http://localhost:18480
    if ($LASTEXITCODE -ne 0) { break }
  }
  ```

- **Replace (restate) a date:** run it with that `--date` and the corrected files. To change one row, send the whole file again. The date's set, FX and PVs are replaced whole.
- **Remove a date:** `python3.13 tools/drishti.py lake delete --lake demo --kind scenario-pv --date 2026-11-24 --dry-run`, then without `--dry-run`; the set and FX: `tools/loaders/mrloader/pvf_scenario_set.py --date D --remove --lake demo --dry-run` then `--yes` (same for `pvf_fx_scenario.py`). Reclaim the space: `python3.13 tools/drishti.py lake maintain`.
- A scheduler reads the exit code: **0** done, **1** refused or failed (one line says why), **2** loaded with rejected rows, **3** another load holds the lake's lock (wait and repeat).

## Check it loaded

```text
Linux     python3.13 tools/drishti.py lake status --lake demo
Windows   py -3.13 tools\drishti.py lake status --lake demo
```

```text
lake <scratch>/data/delta/demo
tables (2):
  table              rows  files  size      partitions                 dates  latest date  last build
  risk/scenario-pv   284   1      506.1 KB  1 (GRAIN,SCENARIO_SET_ID)  1      2026-11-27   2026-11-28 00:44:59Z
  risk/scenario-set  1     1      3.2 KB    1                          1      2026-11-27   2026-11-28 00:44:55Z
lock: free
```

Expect one `scenario-pv` row per record written (the run printed the count: 284 of 300, 16 excluded and audited), one `scenario-set` row per date and your date in the list. With a server: `python3.13 tools/drishti.py dataload status` (files waiting, loaded, failed with reasons: the same as Admin → Data loads → Inbox) and a VaR on the date, `python3.13 tools/drishti.py var show BANK --date 2026-11-27` or `VAR` in the console. `python3.13 tools/drishti.py doctor` checks the machine.

## Reference

**What it writes.** `risk/scenario-set` (the 251 scenario days), `risk/fx-scenario` (when `--fx`) and `risk/scenario-pv`. The contracts: [scenario-pv](../../contracts/packs/market-risk.md#spv-scenario-pvs-of-a-position-or-trade-one-pv-per-scenario-its-currency-scenario-pv) ([sample](../../contracts/packs/market-risk.md#example-market-risk-scenario-pv)), [scenario-set](../../contracts/packs/market-risk.md#sset-scenario-set-scenario-set) and [fx-scenario](../../contracts/packs/market-risk.md#fxs-scenario-fx-of-a-currency-one-rate-per-scenario-fx-scenario). Output files are `<table>.<date>.jsonl.gz` plus a `.ready` marker naming the lake; the lake takes the date from that name.

**Input and date rules.**

| File | One row is | Becomes |
|---|---|---|
| `pvf_path_YYYYMMDD.csv` | a position and a path: `position_id, valid_dt, risk_metric_cd, dataset_cd, shock_group_nm, val, path_num, histo_valid_dt` (path -1 is the base PV, 0..250 the scenario PVs) | the PV vector of `risk/scenario-pv` |
| `pos_YYYYMMDD.csv` | a position: portfolio, currency, trade, amount | joined into every record (no table of its own: see [positions](load-positions.md)) |
| `portfolio_d2.csv` | a portfolio with its desk, group and limits, valid `valid_dt <= date < valid_to_dt` | joined in (desk, book, entity); as its own table see [portfolio](load-portfolio.md) |
| `fx_YYYYMMDD.csv` (optional) | a currency's scenario FX, long or wide | `risk/fx-scenario` |

Only rows whose `valid_dt` is the `--date` are read; a file with no rows for it is refused in one line, not loaded empty. Export from the source system; do not round-trip a file through Excel (long ids become `2.00001E+11`).

**A real example** (synthetic, from `pvf_gen.py`; the first lines of each file):

```text
pos_20261127.csv
position_id,src_position_id,portfolio_id,currency_cd,portfolio_nm,transit_cd,position_amt,trade_id,tv_product_type,mf1_pnl_type
200000281,CAL:44373897.CAL:44373897,100010,USD,GLOBAL COMMODITIES-SGP,T3301,31820140.33798409,44373897,Option,StandardBackwardFunding
200000262,CAL:45670558.CAL:45670558,100006,USD,CREDIT FLOW-HKG,T8734,-13500044.957104126,45670558,Future,StandardBackwardFunding

pvf_path_20261127.csv
position_id,valid_dt,risk_metric_cd,dataset_cd,shock_group_nm,val,path_num,histo_valid_dt
200000053,2026-11-27 00:00:00,PV,HistoVAR,ALL,14671.885500753804,153,2026-10-06 00:00:00
200000164,2026-11-27 00:00:00,PV,HistoVAR,ALL,5954.58028085387,5,2026-10-06 00:00:00
200000165,2026-11-27 00:00:00,PV,HistoVAR,ALL,1425.775729414985,34,2026-10-06 00:00:00
```

**Source and licence.** Your own extract; the shipped samples are synthetic (`pvf_gen.py`, seeded) and real extracts are never committed.

**Errors and fixes.**

| You see | It means | Do this |
|---|---|---|
| *lake demo has no table for risk.scenario-set* | the lake file has no entry for the table (seen on `risklake` as shipped) | add the entries (above) |
| *has no usable path rows of DATE (N of another VALID_DT ...)* | `--date` is not the date inside the file | use the date of the `valid_dt` column |
| `MISSING_PORTFOLIO` in the audit | the portfolio is not in PORTFOLIO_D2 on that date | send the right portfolio file; the other positions load |
| `MISSING_BASE_PV` in the audit | no path -1 for the position (16 of 300 above) | the source must send it; the position is left out |
| a trade in a currency is rejected by VaR | no FX for the date | send `fx_<date>.csv` |
| the VaR page shows nothing for the date | the connectors still read another lake, or no set or FX for the date | point `config/connectors/risk-store.yaml` at the lake; load the set and the FX |
| memory error on the full extract | one chunk too large | `--chunk 20000` (default 50,000) |
| Windows: *can't open file*, a path cut at a space | an unquoted path with a space | put the path in quotes |

More: [BANK_DATA_GUIDE.md](../BANK_DATA_GUIDE.md) (every column, the join rules and the mapping) and [the mrloader README](../../../tools/loaders/mrloader/README.md).
