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
# Load your scenario PV and portfolio files into the Delta lake, and remove older scenario PV dates

This is the short, step-by-step way for the PVF files: a day's per-path PV file, the positions file and the portfolio file become the
**scenario PVs** the VaR engine reads, in a Delta lake. Every figure below is made up; your files stay on your machine and are never
committed. The columns of every kind and the mapping rules are in [tools/mrloader/README.md](../../tools/mrloader/README.md); the same
loader inside a whole day of CSV extracts (limits, desks, exposure) is [the daily runbook](../../tools/mrloader/docs/DAILY_RUNBOOK.md).

Commands are written for **Linux** and **Windows**. `PY` stands for the Python of Drishti's environment:

| | `PY` is | a path looks like |
|---|---|---|
| Linux | `drishti-console/.venv/bin/python` | `tools/mrloader/pvf_run.py` |
| Windows (PowerShell, no `.exe` of ours) | `drishti-console\.venv\Scripts\python.exe` | `tools\mrloader\pvf_run.py` |

Run every command from the install folder (the one holding `tools/`). On Windows write your own paths with `\` and put a path that has a
space in quotes; the examples use `data/pvf/...`, which Windows also accepts.

## 1. What each file is and where it goes

| Your file | What it is | Becomes | Business date comes from |
|---|---|---|---|
| `pvf_path_YYYYMMDD.csv` | one row per position and path (`position_id, valid_dt, risk_metric_cd, dataset_cd, shock_group_nm, val, path_num, histo_valid_dt`): path -1 is the base PV, paths 0..250 the 251 scenario PVs | **scenario-pv** (table `risk/scenario-pv`): one record per position with its vector | `--date`; only rows whose `valid_dt` is that date are read |
| `pos_YYYYMMDD.csv` | the day's positions: links each position to its portfolio, gives currency, trade, amount | joined into every scenario-pv record (no table of its own) | the same `--date` |
| `portfolio_d2.csv` | PORTFOLIO_D2: the portfolios, desks, limits | joined into scenario-pv (desk, book, entity); as its own **book** table with `mr_run.py` | rows valid on `--date` (`valid_dt <= date < valid_to_dt`) |
| (made by the run) | the date's 251 scenario days | **scenario-set** (`risk/scenario-set`), loaded first | `--date` |
| `fx_YYYYMMDD.csv` (optional, yours) | scenario FX per currency ([pvf_fx_scenario.py](../../tools/mrloader/pvf_fx_scenario.py) `--help`: long or wide form) | **fx-scenario** (`risk/fx-scenario`), loaded between the set and the PVs | `--date` |

The data contracts and sample records of the kinds: scenario-pv ([contract](../contracts/packs/market-risk.md#spv-scenario-pvs-of-a-position-or-trade-one-pv-per-scenario-its-currency-scenario-pv), [sample](../contracts/samples/market-risk.md#scenario-pv)), scenario-set ([contract](../contracts/packs/market-risk.md#sset-scenario-set-scenario-set), [sample](../contracts/samples/market-risk.md#scenario-set)) and fx-scenario ([contract](../contracts/packs/market-risk.md#fxs-scenario-fx-of-a-currency-one-rate-per-scenario-fx-scenario), [sample](../contracts/samples/market-risk.md#fx-scenario)).

`path_pv_final.csv`, `path_pv_final.jsonl` and `bad_positions_audit.log` in `data/pvf` are the older single-script output: the loader does not
read them. **The date is always the `--date` you type**, never the file name's digits (the name is only a convention); a file with no rows
for that date is refused in one line, not loaded empty. Every output file is named `<table>.<date>.jsonl.gz` and the lake takes the date
from that name, so a date can be loaded again (it replaces that date as a whole) without touching the others.

## 2. One-time setup

1. **Prepare the machine** (Python environment, settings file, data folder): `python3 tools/drishti.py setup` (Windows `py tools\drishti.py setup`).
   It writes `~/drishti-envs/dev.ini` (Windows `%USERPROFILE%\drishti-envs\dev.ini`). Details: [DEPLOYMENT_AND_QA_GUIDE.md section 1.6](../DEPLOYMENT_AND_QA_GUIDE.md#16-the-settings-file-and-drishtipy-setup).
2. **Build or place the server jar.** The loader asks the jar where the packs and contracts are, even to convert only:
   `PY tools/drishti.py build jar` puts `drishti-server-<version>-exec.jar` in `lib/`. Without it every script stops with *no server jar in .../lib*.
   Loading needs Java 21 (`DRISHTI_JAVA_HOME` or `drishti.java.home` in the settings file).
3. **Choose the lake, by name.** `--lake` takes a *name* (`demo`, `risklake`), not a folder. A name's file is `config/lakes/<name>.yaml` and the
   tables are written under `<data>/delta/<name>/`:

   | Lake | Use it for | Folder |
   |---|---|---|
   | `demo` | the shipped demo (60 synthetic days). The server reads it as it is. Your first try only | `data/delta/demo/` |
   | `risklake` | **your real files**. Ships empty | `data/delta/risklake/` |

   `risklake` is read by nothing until you point the market-risk connectors at it. Write `config/connectors/risk-store.yaml` with only
   what changes (Admin → Connectors writes the same file), then restart the server:

   ```yaml
   template: pack market-risk
   settings:
     lake: risklake
     lake-tables: risk.scenario-pv,risk.scenario-set,risk.fx-scenario,risk.var-limit
     refresh-seconds: 30
   ```

   Check with `PY tools/drishti.py lake check`. Why, and going back to the demo: [DATA_LAKE_GUIDE.md section 6](DATA_LAKE_GUIDE.md#6-pointing-drishti-at-a-lake).
4. Every command below that loads or deletes takes `--settings` **before the command name** when your settings file is not the default:
   `PY tools/drishti.py --settings ~/drishti-envs/dev.ini lake ...` (Windows `--settings %USERPROFILE%\drishti-envs\dev.ini`). The
   `pvf_*.py` and `mr_run.py` scripts take `--settings FILE` after their own name.

## 3. Check a day's files (before anything is loaded)

Convert one day into checked files, writing nothing to the lake. A first trial on the first 2,000 positions takes seconds; the full
extract (200,000 positions, 37 million path rows) takes minutes and a few GB of memory.

```bash
PY tools/mrloader/pvf_run.py --pv data/pvf/pvf_path_20261029.csv --portfolio data/pvf/portfolio_d2.csv --positions data/pvf/pos_20261029.csv --date 2026-10-29 --dry-run
PY tools/mrloader/pvf_run.py --pv data/pvf/pvf_path_20261029.csv --portfolio data/pvf/portfolio_d2.csv --positions data/pvf/pos_20261029.csv --date 2026-10-29 --out data/pvf/work
PY tools/mrloader/pvf_validate.py data/pvf/work/mr-2026-10-29/scenario-pv.2026-10-29.jsonl.gz data/pvf/work/mr-2026-10-29/scenario-set.2026-10-29.jsonl.gz
```

The first line checks inputs, columns and mapping. The second writes `data/pvf/work/mr-2026-10-29/` (`--out` is where `mr-<date>/` goes; default
is next to the PV file). The third checks every record against the contract. What you should see:

```text
pvf run of 2026-10-29: 200 positions in, 13 excluded by the join, 0 by the mapping, 187 records written; ...
...
records: {'scenario-pv': 187, 'scenario-set': 1}
OK: every record holds to its contract (with the known gaps above)
```

The "known gaps" lines (`MATURITY_DATE`, `PRICER`...) are fields the PVF source does not have: normal. Look at what was left out, in the
folder `mr-<date>/`:

| File | Read it when |
|---|---|
| `pvf_joined.<date>.audit.csv` | positions left out: `MISSING_PORTFOLIO` (its portfolio is not in PORTFOLIO_D2 on that date), `MISSING_BASE_PV` (no path -1) |
| `pvf_joined.<date>.fills.csv` | positions with a gap in their paths, filled by `--fill-policy forward` (default), `base` or `refuse` |
| `scenario-pv.<date>.rejects.csv` | records the contract refused, with the reason and the fix |
| `scenario-pv.<date>.summary.json` | the run: rows in and out, seconds, peak memory |

Exit code 0 means done; 1 means refused with one line saying why (`pvf_validate.py` also exits 1 on a bad record).

The 251 scenario days are, by default, the 251 weekdays before the date (no holiday calendar). If your set follows your own calendar, give
the days once per date: `--scenario-dates data/pvf/scenario_dates_20261029.txt` (one `YYYY-MM-DD` a line, oldest first).

## 4. Load one day, then several days in order

### 4.1 One day: look first, then load

`--load` loads each file with Drishti's own loader, in the order **scenario set, FX, PVs**, and stops at the first failure. `--plan` says
what each load would do and writes nothing to the lake.

```bash
PY tools/mrloader/pvf_run.py --pv data/pvf/pvf_path_20261029.csv --portfolio data/pvf/portfolio_d2.csv --positions data/pvf/pos_20261029.csv --date 2026-10-29 --load --plan --lake risklake
PY tools/mrloader/pvf_run.py --pv data/pvf/pvf_path_20261029.csv --portfolio data/pvf/portfolio_d2.csv --positions data/pvf/pos_20261029.csv --date 2026-10-29 --load --lake risklake --server http://localhost:18480
```

Add `--fx data/pvf/fx_20261029.csv` to build the date's scenario FX in the same run (a trade in a currency with no FX is rejected by the
VaR engine). `--server` tells a running server the date landed, so it drops its caches; leave it off when the server is stopped (it notices
within 30 seconds anyway). If the server asks for a token: `--token-file FILE`.

**Or hand the files to a running server** (the UI way): add `--inbox` instead of `--load`. The files are copied, then their `.ready` markers, to
`data/dataload/inbox`, and the node that owns the data load loads them. Watch **Admin → Data loads → Inbox**: each file moves to the outbox with
its report, or to `failed/` with the reason. The `.ready` marker names the lake (`--lake risklake`).

### 4.2 Several days, oldest first

Run the same command for each date, **oldest first**, so each date's set comes before its PVs. A date loaded again replaces itself only.

Linux:

```bash
for d in 20261027 20261028 20261029; do
  iso="${d:0:4}-${d:4:2}-${d:6:2}"
  PY tools/mrloader/pvf_run.py --pv data/pvf/pvf_path_$d.csv --portfolio data/pvf/portfolio_d2.csv --positions data/pvf/pos_$d.csv \
     --date $iso --load --lake risklake --server http://localhost:18480 || break
done
```

Windows (PowerShell):

```powershell
foreach ($d in "20261027","20261028","20261029") {
  $iso = $d.Substring(0,4) + "-" + $d.Substring(4,2) + "-" + $d.Substring(6,2)
  drishti-console\.venv\Scripts\python.exe tools\mrloader\pvf_run.py --pv data\pvf\pvf_path_$d.csv --portfolio data\pvf\portfolio_d2.csv `
     --positions data\pvf\pos_$d.csv --date $iso --load --lake risklake --server http://localhost:18480
  if ($LASTEXITCODE -ne 0) { break }
}
```

**The portfolio as a table of its own.** The portfolio (book) data is already inside every scenario-pv record. To also have the `book`
table, use the one-day driver with a two-line day file (`/path/to/site/pvf-day.yaml`):

```yaml
format: 1
lake: risklake
kinds:
  book:        {csv: "portfolio_d2.csv", source: pvf}
  scenario-pv: {run: pvf, pv: "pvf_path_{YYYYMMDD}.csv", positions: "pos_{YYYYMMDD}.csv", portfolio: "portfolio_d2.csv"}
required: [scenario-pv]
```

```bash
PY tools/mrloader/mr_run.py --date 2026-10-29 --from data/pvf --day /path/to/site/pvf-day.yaml --dry-run
PY tools/mrloader/mr_run.py --date 2026-10-29 --from data/pvf --day /path/to/site/pvf-day.yaml --load --lake risklake
```

### 4.3 Check that it landed

**Row counts and dates per table**:

```bash
PY tools/drishti.py lake status --lake risklake
```

In the UI: **Admin → Data loads → Lakes**, then the table's name (`risk/scenario-pv`) lists each business date with its rows. Expect one
scenario-pv row per record written (187 in the example above), one scenario-set row per date, and the dates you loaded, newest last.

**A VaR on that date.** With the server running and `risklake` pointed at (section 2), in the UI open the **VaR** page (`VAR` in the command
bar, or `/v/var/BANK`) and pick the date; **SPV** and **SSET** open the scenario PVs and the sets as records. From the terminal:

```bash
PY tools/drishti.py var show BANK --date 2026-10-29
PY tools/drishti.py var show DESK:RATES --date 2026-10-29
```

A date with no scenario set, or with vectors that are not as long as its set, is refused by the engine and the message says which.

## 5. Remove older scenario PV dates

### 5.1 Remove named dates or a range: `lake delete`

Remove from the **vectors first, the set last**, because a PV without its set cannot be used. For one old date:

```bash
PY tools/drishti.py lake delete --lake risklake --kind scenario-pv  --date 2026-10-27 --dry-run
PY tools/drishti.py lake delete --lake risklake --kind scenario-pv  --date 2026-10-27 --server http://localhost:18480
PY tools/drishti.py lake delete --lake risklake --kind fx-scenario  --date 2026-10-27 --server http://localhost:18480
PY tools/drishti.py lake delete --lake risklake --kind scenario-set --date 2026-10-27 --server http://localhost:18480
```

For a range (both ends included), or several dates:

```bash
PY tools/drishti.py lake delete --lake risklake --kind scenario-pv --from 2026-10-01 --to 2026-10-23 --dry-run
PY tools/drishti.py lake delete --lake risklake --kind scenario-pv --date 2026-10-26,2026-10-27
```

Skip the `fx-scenario` line if you never loaded FX. `PY tools/mrloader/mr_run.py --date D --from DIR --day FILE --delete-plan` prints the
lines for a whole day, in the right order, and runs nothing. A set alone can also be removed with `pvf_scenario_set.py --date D --remove --lake risklake`.

The **safety**:

- It says what it would delete first (rows per date), then **asks you to type the table's name**. `--dry-run` stops after the counts and
  changes nothing: always run it first. `--yes` skips the question (scripts only).
- A date the table does not hold is refused in words and nothing is deleted. It deletes whole business dates in **one commit**: a reader
  sees all of a date or none of it. It takes the table's lock (exit 3: another run holds it) and writes a line to the lake's run log.
- It tells the server (`--server URL`, or `--no-signal`), so the VaR engine drops the dates at once.
- **What can be undone.** `lake undo` cannot bring a deleted date back. The way back is to load the date again (section 4). Until
  `lake maintain` vacuums, the removed files are still on disk and the table can be read as it was before (time travel); that is a rescue
  for an expert, not a step.
- There is no "max drop share" on `lake delete`: it deletes exactly the dates you named. The share guard belongs to `lake maintain` (below).

### 5.2 Reclaim the space: `lake maintain`

A delete removes rows, not disk space. `lake maintain` compacts the small files, writes a checkpoint and **vacuums** the files nothing
uses (after `vacuum-hours`, default 168 = 7 days, so time travel still reaches back that far).

```bash
PY tools/drishti.py lake maintain --once --tables risk/scenario-pv --dry-run     # what it would do: files and MB before and after
PY tools/drishti.py lake maintain --once --tables risk/scenario-pv,risk/scenario-set,risk/fx-scenario
```

It prints one JSON line per table and exits 0 when all succeeded. In the UI: **Admin → Data loads → Lakes → Maintain** (tick the tables;
**Dry run** shows the same). It reads `<data>/lakes/lake-maintenance.yaml`, the file the page writes. It maintains only tables Drishti's
loader built.

### 5.3 Keep only the last N days from now on

Two ways; pick one.

**A. A scheduled delete (simplest to reason about).** The day after each load, delete the one date that has just left your window. For
a window of 30 business days, with the date to drop worked out by you or your scheduler, it is the `lake delete` of section 5.1 for that
single date (Linux cron or Windows Task Scheduler runs the command).

**B. The retention setting of `lake maintain`.** In `<data>/lakes/lake-maintenance.yaml` (or **Admin → Data loads → Lakes**, tick the
tables, **Settings**, *Retention (business days)*):

```yaml
lakes:
  - root: data/delta/risklake
    domains: ["risk"]
    retain-years: {risk: 0}          # see below: without this the risk domain keeps 7 years
    max-drop-share: 0.5
    tables:
      risk/scenario-pv:  {keep-business-days: 30, vacuum-hours: 168}
      risk/scenario-set: {keep-business-days: 30, vacuum-hours: 168}
      risk/fx-scenario:  {keep-business-days: 30, vacuum-hours: 168}
```

The rule: **today plus the last N business days (Monday to Friday) are kept**, counted back from today, never from the newest date in the
table; older business dates are deleted at the next maintain run (`--as-of YYYY-MM-DD` counts from another day, for a trial).
Two guards you must know:

- **The 7-year floor.** The `risk` domain keeps seven years of dates whatever a table says: `keep-business-days: 30` is silently raised to
  1,827 and the run says so (`retention-floor`). `retain-years: {risk: 0}` on the lake turns the floor off on purpose; leave it out if
  you want the compliance floor, and then use way A.
- **The drop share.** A run that would delete more than `max-drop-share` (0.5) of a table's rows deletes **nothing** and reports the table
  failed. This protects you from a typo (a window of 3 instead of 30) and from a first run on years of history: shorten the window in steps,
  or run `lake delete` for the old range first. `--force-drop` overrides it, once, on purpose.

Run it on a schedule with `lake maintain --daemon` or the console's schedules ([OPERATIONS_GUIDE.md section 16](OPERATIONS_GUIDE.md#162-maintain-from-the-page-or-the-command-line)). The last runs: `lake maintain --history`.

## 6. When something goes wrong

| You see | It means | Do this |
|---|---|---|
| *no server jar in .../lib* | the tools read the packs from the jar | `PY tools/drishti.py build jar` |
| *has no usable path rows of 2026-10-28 (41,127 of another VALID_DT ...)* | `--date` is not the date inside the file | use the date of the `valid_dt` column |
| *lake ...: a lake's name, lower-case letters...* | you gave a folder to `--lake` of the loader | give the name: `--lake risklake` |
| `MISSING_PORTFOLIO` in the audit | positions whose portfolio is not in PORTFOLIO_D2 on that date | send the right portfolio file for the date; the others load |
| `MISSING_BASE_PV` in the audit | positions with no path -1 | the source must send it; the position is left out |
| the VaR page shows nothing for the date | the connectors still read `demo`, or no set/FX for the date | section 2 step 3; load the set (every `pvf_run` does) and the FX |
| *loaded into risklake, but no connector reads it* | the lake is loaded but not pointed at | section 2 step 3, restart the server |
| the same date twice | harmless: the second load replaces the date; unchanged bytes are the same | nothing |
| `lake delete` exit 3 | another load or maintain holds the lock | wait, run it again |
| `lake delete` exit 2 | deleted, but the server could not be told | the server notices within 30 s; or restart the call with the right `--server` |
| Windows: a path cut at a space, or *can't open file* | a path with a space, unquoted | put the path in quotes; nothing but the environment's `python.exe` and Java is needed |
| Windows: `drishti.py` is not found | not in the install folder | `cd` to the folder holding `tools\` |
| Windows: `Activate.ps1` is blocked | script policy | do not activate: call `drishti-console\.venv\Scripts\python.exe` directly, as above |
| memory error on the full extract | one chunk is too large | `--chunk 20000` (default 50,000) |
