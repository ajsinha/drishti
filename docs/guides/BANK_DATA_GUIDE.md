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
# Bank data: what a bank sends each day and how it is loaded (PVF files, CSV datasets, trade products, market data, source systems)

This is the short, step-by-step way for the PVF files: a day's per-path PV file, the positions file and the portfolio file become the
**scenario PVs** the VaR engine reads, in a Delta lake. Every figure below is made up; your files stay on your machine and are never
committed. The columns of every kind and the mapping rules are in [tools/mrloader/README.md](#mrloader-market-risk-loaders-the-csv-datasets-of-var-and-exposure-pvf-scenario-pvs-scenario-sets-scenario-fx); the same
loader inside a whole day of CSV extracts (limits, desks, exposure) is [the daily runbook](#the-daily-run-csv-extracts-to-var-and-exposure-inputs).

Commands are written for **Linux** and **Windows**. `PY` stands for the Python setup prepared (`drishti.python`):

| | `PY` is | a path looks like |
|---|---|---|
| Linux | `python3` (the Python setup prepared) | `tools/mrloader/pvf_run.py` |
| Windows (PowerShell, no `.exe` of ours) | `py` | `tools\mrloader\pvf_run.py` |

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

1. **Prepare the machine** (Python environment, settings file, data folder): `python3 tools/drishti.py setup` (Windows `py -3.13 tools\drishti.py setup`).
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
  py -3.13 tools\mrloader\pvf_run.py --pv data\pvf\pvf_path_$d.csv --portfolio data\pvf\portfolio_d2.csv `
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
| memory error on the full extract | one chunk is too large | `--chunk 20000` (default 50,000) |

## mrloader: market-risk loaders (the CSV datasets of VaR and exposure, PVF scenario PVs, scenario sets, scenario FX)

### The CSV loader: one kind at a time

`tools/mrloader/csv_load.py` turns one dataset's CSV into checked records of its kind, written as the data inbox and `lake build` take
them; it loads nothing itself. Every kind is a mapping file (`kinds/<kind>.yaml`: the CSV columns named as the contract's fields) and a
generated page (`tools/mrloader/docs/<kind>.md`); the day's runbook is `tools/mrloader/docs/DAILY_RUNBOOK.md`.

```bash
python3 tools/mrloader/csv_load.py --kind var-limit --csv data/mr/var_limits_20261029.csv --date 2026-10-29             # convert and check
python3 tools/mrloader/csv_load.py --kind var-limit --csv data/mr/var_limits_20261029.csv --date 2026-10-29 --inbox     # ... and hand it to the data inbox
python3 tools/mrloader/csv_load.py --kind var-limit --csv data/mr/var_limits_20261029.csv --date 2026-10-29 --load --lake risklake
python3 tools/mrloader/csv_load.py --kind var-limit --template                                                          # the CSV's header and an example row
python3 tools/mrloader/csv_load.py --kinds                                                                              # every kind with a mapping
python3 tools/mrloader/validate.py data/mr/mr-2026-10-29                                                                # any kind's files against its contract
python3 tools/mrloader/mr_run.py --date 2026-10-29 --from data/mr/2026-10-29 --day /path/to/site/day.yaml --inbox       # the whole day, in order
```

| Script | Does |
|---|---|
| `tools/mrloader/csv_load.py` | **one kind**: its CSV (and child files) to checked records; `--inbox`, `--load` |
| `tools/mrloader/mr_run.py` | **one day**: every kind of the day's folder (a day file says which file is which), in dependency order (the scenario PVs through `pvf_run.py`), checked against each other (`links.py`), then handed over or loaded in that order; `--delete-plan` |
| `tools/mrloader/validate.py` | any kind's files against its contract |
| `tools/mrloader/kind_docs.py` | writes (`--check`: checks) the generated page of every kind, `docs/<kind>.md`, and the index `docs/README.md` |
| `tools/mrloader/mr_gen.py` | a seeded synthetic day of every kind's CSV (the committed samples; `--with-pvf` adds the day's PVF extract) |

The kinds shipped: wave 1 for VaR (`legal-entity`, `desk`, `book` and `book.pvf`, `risk-factor`, `var-limit`, `desk-pnl`,
`position-sensitivity`; the scenario PVs, set and FX through the PVF scripts below) and wave 2 for exposure (`counterparty-group`,
`counterparty`, `agreement`, `csa`, `netting-set`, `collateral-balance`, `credit-limit`, `trade-valuation`). Wave 3 (exposure paths,
P&L vectors and history, trades from CSV) waits for the extracts' shapes. The pages: `tools/mrloader/docs/README.md`.

It writes `<kind>.<date>.jsonl`, `<kind>.<date>.jsonl.gz` and its `.ready`, `<kind>.<date>.rejects.csv` and `<kind>.<date>.summary.json`
under `<out>/mr-<date>/`. **The file's format is declared, never guessed**: the delimiter, encoding, decimal and thousands separators, the
date format and the null words are set in the mapping's `csv:` block over `kinds/shared/csv.yaml`; a cell that does not fit rejects its
row with a reason code (`BAD_DATE`, `BAD_NUMBER`, `EXCEL_NUMBER_ID`, `MISSING_REQUIRED` ...). A rejected row of a master or a limit file
refuses the whole kind (`on-reject: refuse`: fix the file and run again); the large kinds skip the row up to `max-rejects`. The shipped
mappings read canonical columns; **a bank's own column names are a mapping file of its own, given by explicit path** (`--mapping FILE`,
kept with the site's settings), never edited into the shipped ones. Kinds that are computed or another system's answer (`var-result`,
`frtb-sensitivity`, `simm`, `portfolio` ...) are refused with the reason and what to load instead (`kinds/shared/never.yaml`).

### The PVF loader

The loaders are run directly by path from the repository root (the `pvf_*.py` names are the PVF source format; the folder is `tools/mrloader`; the owner's files stay in `data/pvf/`). The PVF loader turns a PVF extract (per-path present values of every position under
each historical-VaR path, with the positions and their portfolios) into the records of the market-risk pack's **scenario-pv** kind, the
form of scenario P&L the VaR engine reads, in the layout Drishti's loaders take.

### The command

```bash
python3 tools/mrloader/pvf_run.py \
    --pv data/pvf/pvf_path_20261029.csv \
    --portfolio data/pvf/portfolio_d2.csv \
    --positions data/pvf/pos_20261029.csv \
    --date 2026-10-29
```

That one command joins, maps (`tools/mrloader/kinds/scenario-pv.pvf.yaml`), checks every record against the contract, and writes
everything under `data/pvf/mr-2026-10-29/` (`--out DIR` puts `mr-<date>/` elsewhere; the default is the PV file's folder). Try it first on
a slice and without writing:

```bash
python3 tools/mrloader/pvf_run.py --pv data/pvf/pvf_path_20261029.csv --portfolio data/pvf/portfolio_d2.csv \
    --positions data/pvf/pos_20261029.csv --date 2026-10-29 --dry-run          # check inputs, columns and mapping; write nothing
python3 tools/mrloader/pvf_run.py --pv data/pvf/pvf_path_20261029.csv --portfolio data/pvf/portfolio_d2.csv \
    --positions data/pvf/pos_20261029.csv --date 2026-10-29 --limit 2000       # the first 2,000 positions only
```

Then check the result and load it (section "Loading into Drishti"):

```bash
python3 tools/mrloader/pvf_validate.py data/pvf/mr-2026-10-29/scenario-pv.2026-10-29.jsonl.gz data/pvf/mr-2026-10-29/scenario-set.2026-10-29.jsonl.gz
```

**Why three input files.** `--positions` is required: the PV file carries only `position_id` (with `valid_dt`, `risk_metric_cd`,
`dataset_cd`, `shock_group_nm`, `val`, `path_num`, `histo_valid_dt`), no portfolio, currency, trade or amount. The positions file is what
links a position to its portfolio and gives the record its desk, book, legal entity, currency, quantity and trade; a PV file alone cannot
be joined. **`--date`** (YYYY-MM-DD, checked) is the business date: only PV rows whose `valid_dt` is that day are read, it is every record's
`BUSINESS_DATE`, `VALUATION_DATE` and `BASE_VALUATION_DATE`, and it names the output folder and files.

### Prepare your environment

Python 3.13 (3.11 is the floor `setup` checks) with Polars, NumPy and PyYAML, in the Python you run Drishti with. Install the two pip commands of
[DEPLOYMENT_AND_QA_GUIDE.md section 1.6](../DEPLOYMENT_AND_QA_GUIDE.md#16-the-settings-file-and-drishtipy-setup), then the Polars extras, the same way
(from `drishti-console`; `--find-links` the wheel pack, or your package index when the pack does not carry them):

```bash
python3 -m pip install -r requirements-polars.txt      # Windows: py -3.13 -m pip install -r requirements-polars.txt
```

No Java, no server and no network are needed to convert; loading uses Drishti's own loader (below).

### The scripts

| Script | Does |
|---|---|
| `tools/mrloader/pvf_run.py` | **the driver**: join, map, check, write one file per kind and date with its marker, one summary; `--inbox` or `--load` |
| `tools/mrloader/pvf_join.py` | the join on its own: the three CSV files to one JSON Lines row per position, the audit, the fill list |
| `tools/mrloader/pvf_export_gz.py` | the mapping on its own: a joined file to `scenario-pv.<date>.jsonl.gz` (+ `.ready`) and the date's scenario set |
| `tools/mrloader/pvf_scenario_set.py` | **add or remove** a date's scenario set (every day; section "Scenario set") |
| `tools/mrloader/pvf_fx_scenario.py` | **add or remove** a date's scenario FX from a daily CSV (section "Scenario FX") |
| `tools/mrloader/pvf_validate.py` | every record of a file, folder or `.tar` against the scenario-pv and scenario-set contracts |
| `tools/mrloader/pvf_gen.py` | a synthetic extract in the same CSV shape (`--positions 2000`), seeded: for trials and tests |

Each has `--help` with examples; `pvf_join.py`, `pvf_export_gz.py` and `pvf_run.py` have `--dry-run` and a limit (`--limit-positions`,
`--limit`, `--limit`). The modules beside them: `common.py` (the UPPER_SNAKE rule, dates, checksums), `package.py` (the files of a kind
and date and their markers), `hooks.py` (`--inbox` and `--load`), `rejects.py` (the reject report), `contract.py` (any kind's contract, found
by the kind under the packs, never copied), `mapping.py` (the mapping grammar and the scenario set). The mapping is
`kinds/scenario-pv.pvf.yaml`; its id rules (legal entity, division, desk, book, position) are in `kinds/shared/ids.pvf.yaml`, shared by
every kind of the PVF source. `test_pvf.py` tests them on small hand-made extracts (`pytest tools/mrloader`).

The separate steps, as the driver runs them:

```bash
python3 tools/mrloader/pvf_join.py --portfolio data/pvf/portfolio_d2.csv --positions data/pvf/pos_20261029.csv \
    --paths data/pvf/pvf_path_20261029.csv --out data/pvf/mr-2026-10-29/pvf_joined.2026-10-29.jsonl
python3 tools/mrloader/pvf_export_gz.py --joined data/pvf/mr-2026-10-29/pvf_joined.2026-10-29.jsonl --out data/pvf/mr-2026-10-29
```

### The inputs

| File | Columns read | Types |
|---|---|---|
| `pvf_path_YYYYMMDD.csv` (the PV file) | `position_id`, `path_num`, `val`, `valid_dt` and the filters `risk_metric_cd` (`PV`), `dataset_cd` (`HistoVAR`), `shock_group_nm` (`ALL`) | id Int64, path Int32, val Float64, the rest text |
| `pos_YYYYMMDD.csv` (positions) | every column; `position_id` and `portfolio_id` required | ids Int64, `position_amt` Float64, the rest text as it is (`trade_id` too) |
| `portfolio_d2.csv` (PORTFOLIO_D2) | every column; `portfolio_id` required; `valid_dt`/`valid_to_dt` when present | `portfolio_id` Int64, the rest text as it is |

Types are explicit, never inferred: a value that is not a number where one is required refuses the run, naming the file.

### The join rules

1. **The PV file is scanned, never loaded whole** (Polars `scan_csv`, streaming engine): only the columns above, only the rows of the
   date and the three filters, only paths `-1..--max-path` (250), only non-empty values. The rows dropped, and why, are counted in the
   summary (`PATH_ROWS`: other date or set, out-of-range paths, empty values, duplicate rows, PV rows of positions not in the positions file).
2. **One portfolio row per position**: PORTFOLIO_D2 rows valid on the date (`valid_dt <= date < valid_to_dt`) only.
3. **`BASE_PV`** is the value of path `-1`. **`PATH_PV`** holds paths `0..250` (251 values) in path order.
4. **A missing path is filled by `--fill-policy`**:
   - `forward` (the default, the original rule): path 0 takes `BASE_PV` when the extract has none, then each missing path takes the
     previous path's value;
   - `base`: every missing path takes `BASE_PV`;
   - `refuse`: a position with any missing path is excluded (`INCOMPLETE_PATHS`).

   A filled value means *no change* in that scenario and **understates the position's risk**. Every row says so (`PATH0_FROM_BASE`,
   `PATHS_FILLED`); `pvf_joined.<date>.fills.csv` lists every position with a fill (most filled first: `POSITION_ID`, `PATH0_FROM_BASE`,
   `PATHS_FILLED`, `PATHS_SUPPLIED`); the summary's `FILLS` counts them (positions with path 0 from `BASE_PV`, positions with other paths
   filled, the paths filled and their share of all, positions by band of paths filled, the top 20).
5. **Excluded and audited, never written** (`pvf_joined.<date>.audit.csv`: `POSITION_ID`, `REASON`, `DETAIL`, one row per reason):

   | REASON | When |
   |---|---|
   | `MISSING_PORTFOLIO` | the position's `portfolio_id` is not in PORTFOLIO_D2, or has no row valid on the date (the detail says which) |
   | `AMBIGUOUS_PORTFOLIO` | the portfolio has more than one row valid on the date |
   | `MISSING_BASE_PV` | the position has no path `-1` on the date |
   | `CONFLICTING_PATH_VALUES` | one of its paths carries two different values (the paths are listed); equal repeats are only counted |
   | `DUPLICATE_POSITION` | its `position_id` is in the positions file more than once |
   | `INCOMPLETE_PATHS` | `--fill-policy refuse` and a path is missing |

6. **Deterministic**: rows in `POSITION_ID` order; the same inputs give the same bytes (the `.jsonl.gz` too).
7. **Names**: every attribute name the tools write is UPPER_SNAKE (`src_position_id` → `SRC_POSITION_ID`, `Fo_portfolio_nm` →
   `FO_PORTFOLIO_NM`), at every depth, enforced in code and by a test; a PORTFOLIO_D2 column whose name the positions already have gets
   `_D2` (`PORTFOLIO_NM_D2`). Values are never changed by the name rule.

On the owner's 2,000-position slice the join gave the same `base_pv`, the same 251-value vectors and the same bad positions as the
original `pvf_join.py` (whose output was `path_pv_final.jsonl` and `bad_positions_audit.log`).

### The mapping, field by field

`tools/mrloader/kinds/scenario-pv.pvf.yaml` maps a joined row to a scenario-pv record
([contract](../../modules/banking/packs/market-risk/contracts/scenario-pv.contract.yaml)). The export refuses a mapping that names a
field the contract does not have, leaves a required field without a rule (unless `omit-required` names it with its reason) or breaks the
grammar, before reading a row.

| Field | Rule | From the extract | Decision |
|---|---|---|---|
| `VALUE_TYPE` | const `PV` | | scenario PVs, not a P&L vector |
| `GRAIN` | const `POSITION` | | the extract is keyed by position |
| `POSITION_ID` | `POS-PVF-{POSITION_ID}` | `position_id` | the extract's surrogate id, prefixed so it cannot meet another system's ids |
| `TRADE_IDS` | `[TRADE_ID]` | `trade_id` | the one trade a position carries (navigation only); no `TRADE_ID` at position grain |
| `CURRENCY` | upper | `currency_cd` | the contract allows CAD, EUR, USD; another is audited (`CONTRACT`) |
| `SOURCE_SYSTEM` | regex `^([A-Za-z]+):` on `src_position_id`, map `CAL→CALYPSO`, else `SYSTEM-{SYSTEM_ID}` | `src_position_id`, `system_id` | `system_id` numbers have no names yet |
| `SCENARIO_PV_ID` | `SPV-{@POSITION_ID}-{$scenario_set_id}` | | `^SPV-`, unique per date |
| `INSTRUMENT` | `{TV_PRODUCT_TYPE} {CURRENCY_CD} {TRADE_ID}` | | required; the extract has no instrument name |
| `BUSINESS_DATE`, `VALUATION_DATE`, `BASE_VALUATION_DATE` | the date | `--date` | the engine refuses a valuation date other than the business date |
| `SCENARIO_SET_ID` | `{$scenario_set_id}` | | `HS-PVF-251` by default (section "The scenario set") |
| `LEGAL_ENTITY` | slug, prefix `LE-` | `legal_entity` | `GMO_SPOG` → `LE-GMO-SPOG`; `^LE-` |
| `DIVISION` | first of `business_group_nm`, `pnl_sheet_nm`; slug, `DIV-` | | `business_group_nm` is empty in the extract |
| `DESK` | first of `desk_nm`, `portfolio_group_nm`; slug, `DESK-` | | `desk_nm` is empty; `RATES TRADING-LON` → `DESK-RATES-TRADING-LON` |
| `TT_BOOK`, `PORTFOLIO` | slug, `BOOK-` | `portfolio_nm` of PORTFOLIO_D2 | `PORT_100000` → `BOOK-PORT-100000`; `^BOOK-` |
| `LIMIT_LETTER` | first of `limit_letter_nm`, `loss_limit_nm` | | optional; empty in the extract: left out (the (Blank) node) |
| `STRATEGY` | as is | `strategy` | optional; left out when empty |
| `PRODUCT_GROUP`, `PRODUCT_TYPE` | as is | `tv_product_type` | required both; no product grouping is invented |
| `QUANTITY` | first of `QUANTITY`, `QTY`, `POSITION_AMT`; number; optional `sign_from` | `position_amt` today | a priority list: a signed quantity column when the extract has one, else the amount. **The current extract has no quantity column, so QUANTITY is `position_amt` as given, and every `position_amt` is positive (an unsigned amount or notional; no buy/sell column).** The summary counts which column fed QUANTITY (`QUANTITY_SOURCE`) and says `position_amt unsigned: N of N positive`. `sign_from` (left unset) signs the amount by a buy/sell column when one arrives (a value in neither list is audited) |
| `DIRECTION` | the sign of `QUANTITY` | | LONG when zero or more, SHORT negative (the contract's rule). With an unsigned amount every row is LONG: **not meaningful for this source**. VaR does not depend on QUANTITY or DIRECTION: it reads `BASE_PV` and `SCENARIO_PV`, which carry their own signs |
| `BASE_PV` | number | path `-1` | |
| `SCENARIO_PV` | the vector | paths `0..250` | in `scenario-set.path-order` |
| `STATUS` | const `ok` | | the PVs are supplied; fills are reported (`PATHS_FILLED`), a real price is not relabelled `proxied` |
| `COMPUTED_AT` | `{$computed_at}` | the PV file's last-modified time, UTC | the extract has no run time; `--computed-at` to give it |
| `BATCH_ID` | `B-<date>-PVF` | | one batch per date (DRS-5203); `--batch-id` for another |
| `RESTATEMENT` | `0` | | `--restatement 1` for a re-send of the date |
| `RUN_KIND` | `FULL` | | the day's full run |
| `MATURITY_DATE`, `TRADE_STATUS`, `PRICER`, `MODEL_VERSION` | **left out** (`omit-required`) | none in the extract | required by the contract, not needed by the engine (no maturity: not matured; no status: live; pricer and model: audit only); invented values would mislead |
| `SECURITY_ID`, `COUNTERPARTY`, `TAGS`, `TRADE_CURRENCY`, `TRADE_VERSIONS` | left out | none | optional |

**Every other source attribute** rides beside the contract's fields, UPPER_SNAKE, values as they are (`pass-through: columns: "*"`):
`SRC_POSITION_ID`, `PORTFOLIO_ID`, `CURRENCY_CD`, `PORTFOLIO_NM`, `TRANSIT_CD`, `POSITION_AMT`, `TV_PRODUCT_TYPE`, `MF1_PNL_TYPE`,
`PATH0_FROM_BASE`, `PATHS_FILLED` and every PORTFOLIO_D2 column. A source column whose name is a contract field is kept as `PVF_<NAME>`
(`PVF_POSITION_ID` the extract's own id, `PVF_TRADE_ID`, `PVF_LEGAL_ENTITY`, `PVF_STRATEGY`). `PATH_PV`, `BASE_PV` and `BUSINESS_DATE`
are not repeated. The contract's delivery note asks for exactly its fields; the loader keeps the extra attributes and the engine ignores
them. `pvf_validate.py` lists them, and `--strict` treats them (and the omitted required fields) as errors.

#### The mapping grammar

A field's rule, applied in this order: one of `const: V`, `from: COL` (or a list: the first non-empty), `template: "A-{COL}"` (`{COL}` a
joined column, `{$name}` a run value, `{@FIELD}` a field mapped above; empty when any part is empty); then `regex` (the first group),
`map` with `else` (a value not in the map takes `else`; without `else` the row is audited `UNMAPPED_VALUE`), `sign`
(`negative`/`zero`/`positive`), `sign_from` (`column`, the `negative` and `positive` values, optional `only_for` columns of the
`from` list: the magnitude signed by that column), `slug` (upper case, other characters one `-`) or `upper`, `prefix`, `type` (`text`, `number`, `integer`,
`date`, `list`, `vector`; default: the contract's), `default`. A required field that ends empty audits the row (`MISSING_REQUIRED`).

### The scenario set

The engine indexes every vector of a date by that date's scenario set: index *i* is `SCENARIO_DATES[i]`, and a vector of another length
is refused, never padded. The demo's set has 509 scenarios; this extract has 251 paths. So the export **declares the set**: it writes the
date's scenario-set record (`scenario-set.<date>.jsonl.gz`) with `SCENARIO_SET_ID` `HS-PVF-251`, `SCENARIO_COUNT` 251 and 251
`SCENARIO_DATES`, and every record names it. A vector that is not 251 long is audited (`BAD_VALUE`), never padded or cut.

- **Days**: the extract does not say which historical day each path is (`histo_valid_dt` is one date for every row). By default the
  days are the 251 weekdays before the business date, with no holiday calendar, and the set's `DESCRIPTION` says they are assumed. Give
  the real days with `--scenario-dates FILE` (one `YYYY-MM-DD` a line, oldest first, all before the date; exactly 251).
- **Order**: in the PVF extract **path 0 is the latest scenario day** (owner, 2026-11-18), so `scenario-set.path-order` is `newest-first`: the
  vector is reversed to Drishti's order, where index i is SCENARIO_DATES[i], oldest first. (`oldest-first` would keep it as it is.)
- **A lake that already has the date's set**: `--no-scenario-set`, and `--scenario-set-id` with its id. One date has one normal set, and
  the date's other scenario P&L (P&L vectors, other systems' scenario PVs) must use the same set and the same batch.

### The outputs

Under `<out>/mr-<date>/`, the folder every kind of the date shares (docs/plans/CSV_LOADERS.md section 6.4):

| File | What |
|---|---|
| `pvf_joined.<date>.jsonl` | the join: one row per position, every source attribute, `BASE_PV`, `PATH_PV`, `PATH0_FROM_BASE`, `PATHS_FILLED` |
| `pvf_joined.<date>.audit.csv`, `.fills.csv`, `.summary.json` | the join's exclusions, fills and counts |
| `scenario-pv.<date>.jsonl` | the scenario-pv records with the source attributes, plain JSON Lines, `POSITION_ID` order (`--no-plain`: left out) |
| `scenario-pv.<date>.jsonl.gz` (+ `.ready`) | the same records, gzipped: the file that is loaded. Its marker: `pack`, `kind`, `mode: replace`, `rows`, `sha256` (and `lake` with `--lake`) |
| `scenario-set.<date>.jsonl`, `.jsonl.gz` (+ `.ready`) | the date's scenario set |
| `fx-scenario.<date>.jsonl`, `.jsonl.gz` (+ `.ready`) | with `--fx`: the date's scenario FX |
| `mr-<date>.sha256` | the SHA-256 of every loadable file (`sha256sum -c mr-<date>.sha256`) |
| `scenario-pv.<date>.rejects.csv` | rows the mapping or the contract refused, in the reject format of every kind (`FILE,ROW,KEY,COLUMN,FIELD,VALUE,REASON,WHY,FIX`; `KEY` is the position, `ROW` the joined file's line) |
| `scenario-pv.<date>.summary.json` | the whole run: rows in and out at each step, rejected by reason, fills, seconds, peak memory |

**The name is the routing**: the data inbox and `lake build` read the table and the business date from `<table>.<YYYY-MM-DD>.jsonl.gz`.
There is no tar by default; `--rows-per-part N` splits a date too large for one file into parts packed as one
`scenario-pv-<date>.tar` (`scenario-pv/<date>/part-NNNN.jsonl.gz`), which loads completely or not at all. A re-run replaces the date's
files. Exit codes: `0` done (the audits list what was left out), `1` refused (one line saying why), `2` usage; with `--load`, the exit
code of `lake build` when it is not zero.

### Loading into Drishti

The scripts convert and load nothing themselves. Two optional hooks end a run, after every file is written and checked:

```bash
# hand the files to the data inbox the running server watches (data/dataload/inbox, or --inbox DIR)
python3 tools/mrloader/pvf_run.py --pv data/pvf/pvf_path_20261029.csv --portfolio data/pvf/portfolio_d2.csv \
    --positions data/pvf/pos_20261029.csv --date 2026-10-29 --inbox

# or load them now: one `drishti.py lake build --engine java` per file, the scenario set first, stopping at the first failure
python3 tools/mrloader/pvf_run.py ... --load --lake risklake            # --plan: what each load would do, nothing written
```

`--inbox` copies every data file first (each under a visible temporary name, renamed into place), the scenario set, then the FX, then
the PVs, and only then writes the markers in the same order: the inbox loads one batch by the data files' modification times, so the
date's set is loaded before its vectors. The node that owns the data load (`DRISHTI_DATALOAD_ENABLED`, drishti-main in the stack) must be
running; the summary lists what was delivered under `INBOX`. Without either hook the run ends with the lines to do it by hand:

```bash
python3 tools/drishti.py lake build --engine java --from data/pvf/mr-2026-10-29/scenario-set.2026-10-29.jsonl.gz --lake risklake --kind scenario-set --mode overwrite-dates
python3 tools/drishti.py lake build --engine java --from data/pvf/mr-2026-10-29/scenario-pv.2026-10-29.jsonl.gz --lake risklake --kind scenario-pv --mode overwrite-dates
```

For VaR on the date the lake also needs what the engine reads beside the PVs: the currencies' scenario FX (all USD here: none needed),
and no other scenario P&L of the date in another set or batch.

### Scenario set: add and remove

The scenario set lists the historical days behind every scenario: index *i* of every scenario-PV vector, P&L vector and FX scenario of
the date is `SCENARIO_DATES[i]`, **oldest first**. The engine holds **one normal set per business date, used only for that date**, and
refuses a vector of another length (never padded). Make it every day, before the date's FX and PVs. These scripts are run by path
(not a `drishti.py` command).

**Add.** The days come from a file, one `YYYY-MM-DD` a line (`--order oldest-first` default, or `newest-first`; a file that is not in the
order it says is refused), or `--weekdays N` (the N weekdays before the date, no holiday calendar):

```bash
python3 tools/mrloader/pvf_scenario_set.py --date 2026-11-18 --dates data/pvf/scenario_dates_20261118.txt            # Linux / macOS
py -3.13 tools\mrloader\pvf_scenario_set.py --date 2026-11-18 --dates data\pvf\scenario_dates_20261118.txt               # Windows
python3 tools/mrloader/pvf_scenario_set.py --date 2026-11-18 --dates FILE --order newest-first --dry-run             # check only
python3 tools/mrloader/pvf_scenario_set.py --date 2026-11-18 --weekdays 251 --inbox --lake risklake                  # hand it to the inbox
python3 tools/mrloader/pvf_scenario_set.py --date 2026-11-18 --weekdays 251 --load --lake risklake                   # or load it now
```

It checks, before writing anything: no repeated day, every day before the business date, `SCENARIO_COUNT` = the number of days,
`WINDOW_START`/`WINDOW_END` = the first and last day, and every field of the scenario-set contract (`SCENARIO_SET_ID`, `BUSINESS_DATE`,
`SCENARIO_DATES`, `SCENARIO_COUNT`, `WINDOW_START`, `WINDOW_END`, `HORIZON_DAYS` (`--horizon-days`, 1), `REVALUATION` (`--revaluation`,
`full`), `SOURCE_BATCH`, `DESCRIPTION`, `SET_TYPE`). It writes `data/pvf/mr-<date>/scenario-set.<date>.jsonl.gz` and its `.ready`
(and the plain `.jsonl`; `--out DIR` elsewhere, `--lake NAME` names the lake in the `.ready`, `--inbox [DIR]` copies the file then its
`.ready` to the data inbox, `--load --lake NAME` loads it now). The id and batch default to the PV mapping's run values (`HS-PVF-251`,
`B-<date>-PVF`) so the date's PV export names this set; `--scenario-set-id` and `--batch-id` override. A length other than the mapping's
`scenario-set.count` (251) is warned about: the PVs must have as many values as the set has days. PVF's path 0 is the **latest** day
(`path-order: newest-first`): the PV export reverses its
vectors into this set's oldest-first order, so a dates file in either order gives the same set.

`--set-type stress` writes a stress set (`SET_TYPE STRESS`, id `HS-STRESS-<count>`, any past days) as `scenario-set-stress.<date>.jsonl.gz`
with `mode: append` (its marker names the kind `scenario-set`, so the name does not route it), beside the normal set (load the normal set
first).

**Remove.** Whole dates only, through Drishti's own `lake delete` (no second deleter):

```bash
python3 tools/mrloader/pvf_scenario_set.py --date 2026-11-18 --remove --lake risklake --dry-run      # what would go
python3 tools/mrloader/pvf_scenario_set.py --date 2026-11-18 --remove --lake risklake --server http://<host>:18480 --token-file ~/drishti-token
python3 tools/mrloader/pvf_scenario_set.py --date 2026-11-18 --remove --lake risklake --yes --no-signal    # scripts; tell no server
```

(`--settings FILE` is passed to `drishti.py`; the token needs the `loads:write` scope.) **What it does to VaR:** without the date's
set the date's scenario-pv and fx-scenario rows cannot be used (the engine sizes every vector from the set), so VaR on that date answers
*not loaded* until a set is added again; the stress set of the date goes too. See `docs/DEPLOYMENT_AND_QA_GUIDE.md` section 9.14.

### Scenario FX: add and remove

The VaR engine converts each scenario's PV with the currency's FX under that scenario: P&L(s) = PV(s) x FX(s) - PV(base) x FX(base).
One row per currency and date, `USD per 1 <CCY>`; a currency without a row is rejected on its trades (DRS-5207) and cannot be a reporting
currency. **USD is never sent** (it is 1 by definition) and is refused.

**The file you produce each day**, either form (header row; names in any case; `#` comment lines allowed; rows and columns in any order):

```text
long   CURRENCY,SCENARIO_DATE,RATE                one row per currency and day; the row dated the business date is today's rate
       EUR,2026-11-18,1.0850
       EUR,2026-11-17,1.0851
wide   CURRENCY,RATE_TODAY,2025-12-02,2025-12-03,...      one row per currency, one column per scenario day (heading = the day)
       EUR,1.0850,1.0871,1.0840,...
```

Each rate is placed **by its day**, so `RATES` follow the set's `SCENARIO_DATES` whatever the file's order. A currency with a missing day,
a day the set does not have, no rate for today, a repeated row, or a rate that is not a positive number refuses the whole file, listing
every problem (nothing is written).

**Add.** It joins to the date's set: the one just made (`data/pvf/mr-<date>/scenario-set.<date>.jsonl.gz`), or `--scenario-set PATH` (a set
`.jsonl[.gz]`, `.tar` or folder), or `--dates FILE [--order ...] --scenario-set-id ID`:

```bash
python3 tools/mrloader/pvf_fx_scenario.py --date 2026-11-18 --fx data/pvf/fx_20261118.csv                  # Linux / macOS
py -3.13 tools\mrloader\pvf_fx_scenario.py --date 2026-11-18 --fx data\pvf\fx_20261118.csv                     # Windows
python3 tools/mrloader/pvf_fx_scenario.py --date 2026-11-18 --fx FILE --scenario-set path/scenario-set.2026-11-18.jsonl.gz --inbox
```

It writes `fx-scenario.<date>.jsonl.gz` + `.ready` and the plain `.jsonl` (`FX_SCENARIO_ID` `FXS-<CCY>-<set id>`, `RATE` today, `RATES` per day, `QUOTE`, `SOURCE`
(`--source`), `BATCH_ID` = the set's batch). With `--inbox` the set must already be loaded.

**Remove.** `lake delete` deletes whole dates only, so **removing a date's FX removes every currency of that date** (there is no
per-currency removal; add the date again with the full file):

```bash
python3 tools/mrloader/pvf_fx_scenario.py --date 2026-11-18 --remove --lake risklake --dry-run
python3 tools/mrloader/pvf_fx_scenario.py --date 2026-11-18 --remove --lake risklake --yes --no-signal
```

Without the date's FX, trades priced in a non-USD currency are rejected and that currency cannot be asked as a reporting currency
(section 9.14 of the deployment guide shows `lake delete` and its server signal).

### Every day: set, FX, PVs in order

Order matters: **set, then FX, then PVs** (the PVs and FX are sized from the set). Either one driver call:

```bash
python3 tools/mrloader/pvf_run.py --pv data/pvf/pvf_path_20261118.csv --portfolio data/pvf/portfolio_d2.csv \
    --positions data/pvf/pos_20261118.csv --date 2026-11-18 --scenario-dates data/pvf/scenario_dates_20261118.txt \
    --fx data/pvf/fx_20261118.csv --inbox
```

(set from `--scenario-dates` oldest first, or the weekday default; the FX is checked against its days before the PV file is read; the
inbox gets set, FX, PVs in that order) or the three scripts, each with `--inbox`:

```bash
python3 tools/mrloader/pvf_scenario_set.py --date 2026-11-18 --dates data/pvf/scenario_dates_20261118.txt --inbox
python3 tools/mrloader/pvf_fx_scenario.py  --date 2026-11-18 --fx data/pvf/fx_20261118.csv --inbox
python3 tools/mrloader/pvf_run.py --pv ... --portfolio ... --positions ... --date 2026-11-18 --no-scenario-set --scenario-set-id HS-PVF-251 --inbox
```

On Windows use `py -3.13 tools\mrloader\<script>.py` and backslashes. Synthetic samples to try it on (a 251-day dates file, EUR GBP JPY CAD AUD CHF
in both forms) are in `tools/mrloader/samples/`. To try a load: build set and FX from the samples with `--load --lake demo` and a settings file whose `[lake.demo] root=` is a
scratch folder, `lake status`, then remove with `--remove ... --yes --no-signal`.

### Performance and memory

- The PV file is scanned once, streaming: memory follows the (position, path) pairs kept (about 32 bytes each: about 1.2 GB for the
  owner's 37 million rows) plus one chunk of the dense matrix (`--chunk` positions x 251 x 8 bytes: 100 MB at 50,000), not the 3.8 GB file.
- The 2,000-position slice of the owner's extract (426,894 path rows): join and export in about 1.2 seconds, peak memory about 400 MB.
- The full extract (200,000 positions): expect a few minutes and a few GB; the summary records the seconds and the peak memory. Keep
  local trials to `--limit 2000`.

### Mapping decisions for the owner

1. `SCENARIO_SET_ID` `HS-PVF-251` and the **scenario days** (assumed weekdays) until the real path dates are known.
2. **Path order**: settled — path 0 is the latest day (`newest-first`).
3. `POSITION_ID` prefix `POS-PVF-`, `INSTRUMENT` built from product, currency and trade.
4. `SOURCE_SYSTEM` from the `src_position_id` prefix (`CAL` → `CALYPSO`), and names for the `system_id` numbers.
5. `DIVISION` from `pnl_sheet_nm` while `business_group_nm` is empty; `DESK` from `portfolio_group_nm` while `desk_nm` is empty.
6. The four contract-required fields left out (`MATURITY_DATE`, `TRADE_STATUS`, `PRICER`, `MODEL_VERSION`), and keeping the source
   attributes beside the contract's fields.
7. `COMPUTED_AT` from the PV file's last-modified time.
8. The fill policy (`forward`, the original rule) given that fills understate risk.
9. QUANTITY/DIRECTION: the amount is unsigned (option B, decided 2026-11-18); name the sign column when the extract gains one.

## The CSV loaders' kinds

*Generated by `tools/mrloader/kind_docs.py`: do not edit.* One page per kind; the day's runbook is [DAILY_RUNBOOK.md](BANK_DATA_GUIDE.md); the tool and its conventions: [the README](BANK_DATA_GUIDE.md).

| # | Kind | Wave | Pack | Inputs | Load first | Sources |
|---|---|---|---|---|---|---|
| 1 | [`legal-entity`](../../tools/mrloader/docs/legal-entity.md) | 1 | `core` | `--csv` | - | canonical |
| 2 | [`desk`](../../tools/mrloader/docs/desk.md) | 1 | `core` | `--csv` | `legal-entity` | canonical |
| 3 | [`book`](../../tools/mrloader/docs/book.md) | 1 | `core` | `--csv` | `desk` | canonical, pvf |
| 4 | [`counterparty-group`](../../tools/mrloader/docs/counterparty-group.md) | 2 | `core` | `--csv` | `legal-entity` | canonical |
| 5 | [`counterparty`](../../tools/mrloader/docs/counterparty.md) | 2 | `core` | `--csv` | `legal-entity`, `counterparty-group` | canonical |
| 6 | [`agreement`](../../tools/mrloader/docs/agreement.md) | 2 | `counterparty-risk` | `--csv` | `counterparty` | canonical |
| 7 | [`csa`](../../tools/mrloader/docs/csa.md) | 2 | `counterparty-risk` | `--csv`, `--child ELIGIBLE=` | `agreement` | canonical |
| 8 | [`netting-set`](../../tools/mrloader/docs/netting-set.md) | 2 | `counterparty-risk` | `--csv` | `csa`, `counterparty`, `legal-entity` | canonical |
| 9 | [`collateral-balance`](../../tools/mrloader/docs/collateral-balance.md) | 2 | `counterparty-risk` | `--csv`, `--child POSITIONS=` | `netting-set` | canonical |
| 10 | [`credit-limit`](../../tools/mrloader/docs/credit-limit.md) | 2 | `counterparty-risk` | `--csv`, `--child BUCKETS=` | `counterparty` | canonical |
| 11 | [`risk-factor`](../../tools/mrloader/docs/risk-factor.md) | 1 | `market-risk` | `--csv` | - | canonical |
| 12 | [`var-limit`](../../tools/mrloader/docs/var-limit.md) | 1 | `market-risk` | `--csv` | `scenario-pv` | canonical |
| 13 | [`position-sensitivity`](../../tools/mrloader/docs/position-sensitivity.md) | 1 | `market-risk` | `--csv`, `--child SENSITIVITIES=` | `risk-factor`, `scenario-pv` | canonical |
| 14 | [`trade-valuation`](../../tools/mrloader/docs/trade-valuation.md) | 2 | `market-risk` | `--csv` | `netting-set`, `book`, `desk` | canonical |
| 15 | [`desk-pnl`](../../tools/mrloader/docs/desk-pnl.md) | 1 | `market-risk` | `--csv` | `desk` | canonical |

The scenario PVs, the scenario set and the scenario FX of the PVF source are written by `pvf_run.py`, `pvf_scenario_set.py` and `pvf_fx_scenario.py` (the [README](BANK_DATA_GUIDE.md)).

### Never loaded from CSV

| Kind | Why |
|---|---|
| `frtb-sensitivity` | a book's FRTB charge, another system's answer: the FRTB SA engine computes it from position-sensitivity (load that) |
| `simm` | an initial margin, another system's answer: the SIMM engine computes it from position-sensitivity (load that) |
| `sa-ccr` | an exposure at default, another system's answer: computed from the trades, netting sets and collateral |
| `cva` | a CVA, another system's answer: computed from the exposure paths and the credit curves |
| `stress-result` | a stress P&L, another system's answer: computed from the stress scenarios and the positions |
| `pnl-explain` | a P&L explain, another system's answer: computed from the sensitivities and the market moves |
| `portfolio` | Drishti has no portfolio kind: a booking portfolio is the book (load book), a reporting tree is built from the scenario-PV rows, a watched set of positions is a system basket kept in Drishti |
| every kind of class `computed` or `drishti-owned` | Drishti computes or keeps it |

## The daily run: CSV extracts to VaR and exposure inputs

One command a day turns the day's CSV extracts into checked files of each kind and hands them to the data inbox, in the order the engines
need them. This page is the operator's; each kind's columns and rules are on its own page ([the kinds](BANK_DATA_GUIDE.md)). The PVF files alone (scenario PVs, load several days, remove old dates) are step by step in [PVF_LOADING.md](BANK_DATA_GUIDE.md).

### Prepare your environment

From the repository root (or the install folder), Python 3.13 (3.11 is the floor) with PyYAML, in the Python you run Drishti with (the two pip commands of
[DEPLOYMENT_AND_QA_GUIDE.md section 1.6](../DEPLOYMENT_AND_QA_GUIDE.md#16-the-settings-file-and-drishtipy-setup)); Polars and NumPy for the scenario PVs of the PVF
source, from `drishti-console`:

```bash
python3 -m pip install -r requirements-polars.txt      # Windows: py -3.13 -m pip install -r requirements-polars.txt
```

The scripts are run by path. Converting needs no Java, no server and no network; `--load` uses Drishti's own `lake build` (Java and the
server jar); `--inbox` needs the node that owns the data load running (`DRISHTI_DATALOAD_ENABLED`).

### 1. The files and the day file

Every day the extracts land in one folder, for example `data/mr/2026-10-29/`. A **day file** says which file is which kind, once
(`tools/mrloader/days/sample.yaml` is the sample's; a site's own lives with its settings and is given by path):

```yaml
format: 1
lake: risklake
kinds:
  legal-entity:         {csv: "legal_entity.csv"}
  desk:                 {csv: "desk.csv"}
  book:                 {csv: "portfolio_d2.csv", source: pvf}
  scenario-pv:          {run: pvf, pv: "pvf_path_{YYYYMMDD}.csv", positions: "pos_{YYYYMMDD}.csv", portfolio: "portfolio_d2.csv", fx: "fx_{YYYYMMDD}.csv"}
  var-limit:            {csv: "var_limits_{YYYYMMDD}.csv"}
  risk-factor:          {csv: "risk_factors.csv", optional: true}
  position-sensitivity: {csv: "sens_positions_{YYYYMMDD}.csv", child: {SENSITIVITIES: "sens_{YYYYMMDD}.csv"}, source-system: MUREX}
  desk-pnl:             {csv: "desk_pnl_{YYYYMMDD}.csv"}
required: [scenario-pv]
```

A kind whose extract has the bank's own column names gets `mapping: /path/to/site/mappings/var-limit.yaml` in its entry: a copy of the
shipped `tools/mrloader/kinds/<kind>.yaml` with the `from:` columns renamed (and the `csv:` block for the file's delimiter, decimal and
date format). Never edit the shipped mappings.

### 2. The one command

```bash
python3 tools/mrloader/mr_run.py --date 2026-10-29 --from data/mr/2026-10-29 --day /path/to/site/day.yaml --dry-run   # check everything first
python3 tools/mrloader/mr_run.py --date 2026-10-29 --from data/mr/2026-10-29 --day /path/to/site/day.yaml --inbox     # then convert and hand over
```

It converts every kind of the folder in dependency order (masters, then the scenario set and PVs, then limits and sensitivities), checks
the kinds against each other, and only then hands every file to the inbox: a day that fails leaves the lake as it was. It prints one
table:

```text
kind                   status        read  written rejected  links seconds
legal-entity           DONE             4        4        0      0       0
var-limit              DONE            13       13        0      0       0
```

`links` counts link values that resolve to nothing (a limit on a node the day's positions do not build, a sensitivity on an unknown
factor); each is listed in `mr-<date>/links-<date>.csv`. A link that changes a number (a netting set's CSA, a valuation's netting set)
refuses the day; the rest warn.

### 3. Reading a reject report

Each kind writes `mr-<date>/<kind>.<date>.rejects.csv`, one line per problem: `FILE, ROW` (as the spreadsheet shows it), `KEY`, `COLUMN`,
`FIELD`, `VALUE`, `REASON`, `WHY`, `FIX`. The commonest fixes:

| REASON | Usually | Fix |
|---|---|---|
| `BAD_DATE` | dates rewritten to the machine's locale (`29/10/2026`) | export ISO dates, or declare `csv: {dates: DD/MM/YYYY}` in the site's mapping |
| `BAD_NUMBER` | a currency symbol, or a European decimal comma | export plain numbers, or declare `decimal: ","` and `thousands: "."` |
| `MISSING_REQUIRED` | an empty cell in a column the engine needs | fill it in the source system and export again |
| `EXCEL_NUMBER_ID` | a long id turned into `2.00001E+11` | export the id column as text |
| `DUPLICATE_KEY` | the same record twice | keep one row per key; a duplicate always refuses the kind |

### 4. When a kind is refused

Masters and limits refuse the whole kind on any rejected row (`on-reject: refuse`): fix the file and run the day again. The large
kinds (sensitivities) skip a bad row up to their `max-rejects`. `--keep-going` delivers the kinds that do not depend on a refused one;
without it, nothing is delivered.

### 5. Checking the load

The inbox moves each file to `data/dataload/outbox/<day>/` with its report (`<file>.loaded.json`), or to `failed/` with the reason;
**Admin → Data loads** shows the same. Then open VaR for the date (the limits show in the Limit column) and, for exposure, a
counterparty's page.

### 6. Again, restate, remove

- **The same day again:** run the same command. Every file replaces its kind's date as a whole; unchanged files have the same bytes.
- **Restate a past date:** run it with that `--date` and the corrected files; the kinds with a `RESTATEMENT` field take
  `--restatement 1` (then 2 ...) in `csv_load.py`. To change one row, send the whole file again.
- **One kind only:** `python3 tools/mrloader/csv_load.py --kind var-limit --csv FILE --date 2026-10-29 --inbox`.
- **Remove a kind's date:** `python3 tools/drishti.py lake delete --lake risklake --kind var-limit --date 2026-10-29 --dry-run`, then
  without `--dry-run`. **Remove a whole day:** `mr_run.py --date 2026-10-29 --from DIR --day FILE --delete-plan` prints every line, vectors first.
- **A master that skips a day** is answered from its last delivery (the reference tables are read as *effective*), and the page says
  *no load for the date, showing* the earlier one. A row left out of today's master file does **not** disappear from those tables:
  remove it with `lake delete` of its earlier dates, or keep sending it with its status.

### 7. Spreadsheets

Export from the source system; do not round-trip a file through Excel. When a file must be edited in Excel, import every column as text:
Excel silently turns long ids into numbers, drops leading zeros, rewrites dates to the machine's locale and rounds numbers beyond 15
digits. The loader refuses what it can detect (`EXCEL_NUMBER_ID`, `EXCEL_ERROR`, `FORMULA`, `BAD_DATE`); rounding it cannot see.

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
## Samples

**Synthetic.** Made up for trying the scripts; not market data and not anyone's extract (the owner's real files live in the git-ignored
`data/pvf/`). Business date 2026-11-18, 251 scenario days.

| File | What |
|---|---|
| `scenario_dates_2026-11-18.txt` | the 251 weekdays before 2026-11-18, oldest first, one per line (`pvf_scenario_set.py --dates`) |
| `fx_2026-11-18.csv` | scenario FX, **wide** form: EUR, GBP, JPY, CAD, AUD, CHF; `RATE_TODAY` and one column per scenario day |
| `fx_long_2026-11-18.csv` | the same rates in **long** form: `CURRENCY,SCENARIO_DATE,RATE`, today's rate on the row dated 2026-11-18 |
| `<kind>.csv`, `<kind>.expected.jsonl` | **the sample day** of the CSV kinds (legal-entity, desk, book, risk-factor, var-limit, desk-pnl, position-sensitivity and its child file `position-sensitivity.SENSITIVITIES.csv`; the exposure masters counterparty-group, counterparty, agreement, csa (+ `csa.ELIGIBLE.csv`), netting-set, collateral-balance (+ `collateral-balance.POSITIONS.csv`), credit-limit (+ `credit-limit.BUCKETS.csv`) and trade-valuation), written by `mr_gen.py --day tools/mrloader/samples`: four legal entities, six desks, twelve books, twenty positions, four groups and eight counterparties that every file shares, so every link resolves; beside each, the records `csv_load.py` must make of it (`--computed-at 2026-11-18T22:00:00Z --source-system SYNTHETIC-RISK`). `tools/mrloader/days/sample.yaml` is its day file |

```bash
python3 tools/mrloader/pvf_scenario_set.py --date 2026-11-18 --dates tools/mrloader/samples/scenario_dates_2026-11-18.txt
python3 tools/mrloader/pvf_fx_scenario.py  --date 2026-11-18 --fx tools/mrloader/samples/fx_2026-11-18.csv --dates tools/mrloader/samples/scenario_dates_2026-11-18.txt --scenario-set-id HS-PVF-251
```

## Trading pack

Trades in 125 products across rates, FX, credit, equity, commodities, inflation, fixed income, securities financing, money markets and structured products.

**Extends:** `core`, `market-data` · **Extended by:** `market-risk`, `counterparty-risk` · **Kinds:** 1

Enable it with `DRISHTI_PACKS=trading` (the packs it extends come with it; where they differ, the more specific wins).

### Finding things

Every kind has a mnemonic (see *Kinds*). Type it with an identifier, the start of one, or a search, then `<GO>`. Shown here for trades; the same works for every mnemonic.

| Command | Does |
|---|---|
| `TRD <id> <GO>` | Opens that trade. A bare identifier works too: its prefix (`MX-…`, `CLY-…`, `1000001…`) tells Drishti the kind. |
| `TRD <start of an id> <GO>` | A pick list: one match opens, several give a table with the kind's key fields. `*` is a wildcard, and case never matters. |
| `TRD <field>=<value> <GO>` | Lists by field value, for example `TRD PRODUCT_TYPE=IRS_FIXFLOAT`. Compare with `<` and `>`, combine with `and`, sort with `order by <field> desc`. |
| `TRD <GO>` | Lists every trade. |
| `FORM trading <GO>` | The pack's search form: boxes for the key fields of each kind instead of a written search. |
| `TRDS <GO>` | The pack's overview: its kinds, their mnemonics and how many documents each holds. |

### Where the data comes from

Each kind is read from the Delta Lake folder of its **data domain** (`data/delta/demo/<domain>/<kind>/`, partitioned by business date). Pick a date in the top bar to see that day; **Live** reads the streaming source first.

| Connector | Folder | Domain | Tables | Kinds |
|---|---|---|---|---|
| `trade-endur` | `data/delta/demo/trading/trade_endur/` | Trades booked in ENDUR | snapshot (every business date), a union with the other two | `trade` |
| `trade-calypso` | `data/delta/demo/trading/trade_calypso/` | Trades booked in CALYPSO | snapshot (every business date), a union with the other two | `trade` |
| `trade-murex` | `data/delta/demo/trading/trade_murex/` | Trades booked in MUREX | snapshot (every business date), a union with the other two | `trade` |

#### One trade view, three source systems

Each booking system lands in a table of its own and is read by its own connector; the three serve the kind `trade` together (`union: true`): a trade opens from whichever system holds it, and a search, a type-ahead, the search form's drop-downs and a BI table read all three, each row saying its `SOURCE_SYSTEM`. Build the three tables, with a week of business days and the day's amendments, with the Python you run Drishti with (Windows `py -3.13`), `python3 tools/packgen/trades/make_trades.py --trades 3000 --days 7 --lake data/delta/demo`.

#### Live trades and pages over many trades

`trading-stream` (Kafka, **off by default**; switch it on with `DRISHTI_STREAM_TRADING=true`) streams the day's new trade versions, which **Live** in the top bar reads first. `trade-pages` is a *derived* connector: from the trades of every system it computes the pages of a counterparty (`CTRD`), a portfolio (`PTRD`), a legal entity (`LTRD`) and an index (`ITRD`): the live trades, payments in the next 7, 30 and 90 days, notional by product type and by index, the maturity ladder and payments by month, refreshed every minute. Try `CTRD CP-ALDERSHOT <GO>`.

#### Screens built from fragments, and the book desk

Every screen of the pack is built from fragments: the Drishti UI kit's (`config/ui-kit/`, such as `row-picker@1`, `cashflow-ladder@1`, `data-provenance@1`) and the pack's own, named `trading/<name>@<version>` (`trading/deal-ticket@1`, `trading/trade-table@1`, `trading/payments-metrics@1`); `sutra lint config/packs` checks them all. The **book desk** (`/v/portfolio-trades/BOOK-RATES-2?sutra=book-desk`) is a master-detail screen across three packs: click a trade and its cash flows open under the row, its counterparty (core) and credit limit (counterparty-risk) fill the right column, and the trades can be edited beside the table or started in a New trade drawer (kept as drafts). See the Sutra developer guide, section 11a, and the forms guide.

#### Risk on every trade page

Every deal screen (Endur, Calypso and Murex) has a **Risk** group, the UI kit's `risk-links@1` (panel code `RISK`): the trade's **P&L vector** (`PNLV PV-<trade>-HS-509`, the market-risk pack's [`pnl-vector`](../contracts/samples/market-risk.md#pnl-vector) ([contract](../contracts/packs/market-risk.md#pnlv-pl-vector-one-pl-per-scenario-pnl-vector))), its **VaR** (`VRES TRD:<trade>`; `VAR TRD <trade>` opens it in the VaR explorer) and its **exposure** (`EXP TRD:<trade>`). They are real links: the VaR and exposure engines set `PNL_VECTOR_ID`, `VAR_RESULT_ID` and `EXPOSURE_RESULT_ID` on the view of the trade when the day's vectors and the exposure run hold it, so the linked entities list them too; when they do not, the row says why (*not live on <date>: its status is CANCELLED; no P&L vector*, *no exposure paths on <date>*). The Sutras get it from the generator (`tools/packgen/trades/sutras.py`); a picture is in the [VaR guide, section 3.7](VAR_GUIDE.md#37-the-portfolio-and-position-pages).

Load real data into each domain's folder, or point a domain at a database instead by overriding its connector in the site configuration. For a demonstration lake built from the generated samples, with the Python you run Drishti with (Windows `py -3.13`), run `python3 tools/packgen/banking/make_data.py --lake data/delta/demo`: it writes the history as JSON Lines rows (`data/build/lake-rows`), then loads them (generating and loading are separate steps, [Data formats](DATA_GUIDE.md#2-data-formats-json-lines-is-the-standard)).

### Kinds

| Kind | Name | Mnemonic | Identifiers | Id field | What it is |
|---|---|---|---|---|---|
| `trade` | Trade | `TRD` | `MX-…`, `CLY-…`, `1000001…` | `TRADE_ID` | A trade from any source system (ENDUR, CALYPSO, MUREX) in the common schema; its PRODUCT_TYPE selects its Sutra. |

#### Trade (`trade`)

A trade from any source system (ENDUR, CALYPSO, MUREX) in the common schema; its PRODUCT_TYPE selects its Sutra.

Every source system writes the same 47 upper-snake attributes, nested the same way; `PRODUCT_TYPE` chooses the deal's Sutra (one per product type, see *Trading products*; Endur's five are `ComSwap`, `ComFut`, `Cash`, `CallNot`, `Commodity`).

```text
SOURCE_SYSTEM, SOURCE_UPDATE_TIME, TRADE_DATE, INPUT_DATE, TRADE_ID, TRADE_STATUS, TRADE_ACTION, TRADE_VERSION, TRANSACTION_ID, PACKAGE_ID, QUANTITY, PRICE, BUY_SELL_CODE, PRODUCT_TYPE, PRODUCT_SUB_TYPE, PRODUCT_GROUP, PRODUCT_SUB_GROUP, OPTION_TYPE, STRIKE_PRICE, COUNTERPARTY, COUNTERPARTY_UEN, COUNTERPARTY_COUNTRY_OF_RISK, ORIGINAL_COUNTERPARTY, ORIGINAL_COUNTERPARTY_UEN, INTERNAL_EXTERNAL_CODE, TRANSIT, LEGAL_ENTITY, PORTFOLIO, TRADER_ID, TRADER_FIRST_NAME, TRADER_LAST_NAME, START_DATE, MATURITY_DATE, REGISTRY_DELIVERY_DATE, CURRENCY, DELIVERY_VERSUS_PAYMENT, STRATEGY, INTERNAL_TRADING_STRATEGY, NY_CURVE_FLAG, VAULT_LOCATION_ALLOCATION, PRECIOUS_METAL_FLAG, TT_GROUP, TT_BOOK, LEASE_PERPETUITY_FLAG, SETTLEMENT_DATE,
LEGS [{LEG_ID, LEG_TYPE, LEG_ECONOMIC_TYPE, CURRENCY, LEASE_CURRENCY, FLOAT_SPREAD, PRICE_UNIT, NOTIONAL, DISCOUNT_INDEX, SPOT_CONVERSION_FACTOR, PHYSICAL_LOCATION_NAME, INDEX_LOCATION_NAME, RESET_FIXING_SHIFT, RESET_HOLIDAY_SCHEDULE, RESET_CONVENTION, EMISSIONS_VINTAGE_MIN, EMISSIONS_VINTAGE_MAX, EMISSIONS_VINTAGE_DELIVERY_TYPE, SETTLEMENT_TYPE, NY_CURVE_FLAG, NUM_RRDC_REC, NUM_PROFILE_REC, DAILY_VOLUME, INDEX {NAME, GROUP, SUB_GROUP},
       CASH_FLOWS [{FLOW_ID, FLOW_TYPE, NOTIONAL, RATE, FLOAT_SPREAD, START_DATE, END_DATE, PAYMENT_DATE, RATE_DETERMINATION_DATE, FIRST_RESET_DATE, LAST_RESET_DATE, STRIKE_PRICE, RESETS [{RESET_ID, ACCRUAL, START_DATE, END_DATE, RESET_DATE, ACCRUAL_START_DATE, ACCRUAL_END_DATE, RATE}]}]}],
EVENTS [{LEG_ID, EVENT_ID, EVENT_NUMBER, EVENT_TYPE, PAYMENT_TYPE, EVENT_DATE, AMOUNT, CURRENCY, SEQUENCE_NUMBER, VAULT_LOCATION_ALLOCATION, NY_CURVE_FLAG}]
```

### Who can see it

Role `trader`: kinds `trade`, [`ir-curve`](../contracts/samples/market-data.md#ir-curve) ([contract](../contracts/packs/market-data.md#crv-interest-rate-curve-ir-curve)), [`repo-curve`](../contracts/samples/market-data.md#repo-curve) ([contract](../contracts/packs/market-data.md#repo-repo-curve-repo-curve)), [`fx-spot`](../contracts/samples/market-data.md#fx-spot) ([contract](../contracts/packs/market-data.md#fx-fx-spot-rate-fx-spot)), [`fx-forward-curve`](../contracts/samples/market-data.md#fx-forward-curve) ([contract](../contracts/packs/market-data.md#fxf-fx-forward-curve-fx-forward-curve)), [`fx-vol-surface`](../contracts/samples/market-data.md#fx-vol-surface) ([contract](../contracts/packs/market-data.md#fxv-fx-volatility-surface-fx-vol-surface)), [`ir-vol-cube`](../contracts/samples/market-data.md#ir-vol-cube) ([contract](../contracts/packs/market-data.md#irv-swaption-volatility-cube-ir-vol-cube)), [`cap-vol-surface`](../contracts/samples/market-data.md#cap-vol-surface) ([contract](../contracts/packs/market-data.md#cpv-capfloor-volatility-surface-cap-vol-surface)), `equity`, [`equity-index`](../contracts/samples/market-data.md#equity-index) ([contract](../contracts/packs/market-data.md#eqx-equity-index-equity-index)), [`dividend-curve`](../contracts/samples/market-data.md#dividend-curve) ([contract](../contracts/packs/market-data.md#div-dividend-curve-dividend-curve)), [`equity-vol-surface`](../contracts/samples/market-data.md#equity-vol-surface) ([contract](../contracts/packs/market-data.md#eqv-equity-volatility-surface-equity-vol-surface)), [`credit-curve`](../contracts/samples/market-data.md#credit-curve) ([contract](../contracts/packs/market-data.md#cds-credit-curve-credit-curve)), [`inflation-index`](../contracts/samples/market-data.md#inflation-index) ([contract](../contracts/packs/market-data.md#inf-inflation-index-inflation-index)), [`inflation-curve`](../contracts/samples/market-data.md#inflation-curve) ([contract](../contracts/packs/market-data.md#infc-inflation-curve-inflation-curve)), `commodity`, [`commodity-curve`](../contracts/samples/market-data.md#commodity-curve) ([contract](../contracts/packs/market-data.md#cmdc-commodity-forward-curve-commodity-curve)), [`commodity-vol-surface`](../contracts/samples/market-data.md#commodity-vol-surface) ([contract](../contracts/packs/market-data.md#cmdv-commodity-volatility-surface-commodity-vol-surface)), [`rate-fixing`](../contracts/samples/market-data.md#rate-fixing) ([contract](../contracts/packs/market-data.md#fix-rate-index-fixings-rate-fixing)), `bond`, [`correlation-matrix`](../contracts/samples/market-data.md#correlation-matrix) ([contract](../contracts/packs/market-data.md#corr-correlation-matrix-correlation-matrix)), `counterparty`, [`counterparty-group`](../contracts/samples/core.md#counterparty-group) ([contract](../contracts/packs/core.md#grp-counterparty-group-counterparty-group)), `issuer`, `book`, `desk`, `trader`, [`legal-entity`](../contracts/samples/core.md#legal-entity) ([contract](../contracts/packs/core.md#le-bank-legal-entity-legal-entity)), `calendar`, `agreement`, `csa`, `ccp`, [`clearing-account`](../contracts/samples/core.md#clearing-account) ([contract](../contracts/packs/core.md#clr-clearing-account-clearing-account)), [`desk-pnl`](../contracts/samples/market-risk.md#desk-pnl) ([contract](../contracts/packs/market-risk.md#dpnl-desk-pl-actual-and-hypothetical-daily-desk-pnl)).
Role `trade-editor`: kinds `trade`.

### F8 impact

Amounts at stake: `trade` by `first($.LEGS).NOTIONAL`.

### Data retrieval

Where each kind's data comes from, how it reaches the lake and how to check it. Three origins: **external** data, fetched from a public source by its data feed (each source, its licence and worked examples on the [External data](WORLD_DATA_GUIDE.md) help page); **synthetic** test data (or hand-written reference data), written by a generator with fictitious names; **computed** by a Drishti engine when it is asked, never stored by a source.

| Command | Kind | Origin | How it reaches Drishti |
|---|---|---|---|
| `TRD` | `trade` | synthetic: `python3 tools/testdata/make_testdata.py` | `data/testdata/trades/endur/`, `data/testdata/trades/calypso/`, `data/testdata/trades/murex/`, `data/testdata/trades/wss/`, `data/testdata/trades/swapone/`, `data/testdata/trades/ici/` → `lake demo` → connector `trade-endur`, `trade-calypso`, `trade-murex`, `trade-wss`, `trade-swapone`, `trade-ici` |
| `CTRD` | `counterparty-trades` | computed by Drishti: the derived connector trade-pages over the kind trade; see the derived connector's settings in the trading pack's pack.yaml (`trade-pages`) | nothing to load: computed from its inputs when asked |
| `PTRD` | `portfolio-trades` | computed by Drishti: the derived connector trade-pages over the kind trade; see the derived connector's settings in the trading pack's pack.yaml (`trade-pages`) | nothing to load: computed from its inputs when asked |
| `LTRD` | `legal-entity-trades` | computed by Drishti: the derived connector trade-pages over the kind trade; see the derived connector's settings in the trading pack's pack.yaml (`trade-pages`) | nothing to load: computed from its inputs when asked |
| `ITRD` | `index-trades` | computed by Drishti: the derived connector trade-pages over the kind trade; see the derived connector's settings in the trading pack's pack.yaml (`trade-pages`) | nothing to load: computed from its inputs when asked |

#### Generators

| Generator | Writes | Check |
|---|---|---|
| `python3 tools/testdata/make_testdata.py` | 5,000 trades a booking system into data/testdata/trades/{endur,calypso,murex}/ in the common 47-attribute UPPER_SNAKE schema (tools/packgen/trades/schema.py) | `python3 tools/testdata/make_testdata.py --check` |

#### Into the lake, checking, offline

- **The lake.** `python3 tools/drishti.py lake demo` reads `data/testdata/testdata.yaml` and writes every dataset (test data, the packs' samples and the committed public-data snapshots) into `<data>/delta/<domain>/<kind>/`, where the pack's Delta connector serves it; a computed kind needs nothing loaded. `lake build` loads your own large JSON exports the same way ([Command line](CLI_REFERENCE.md)).
- **Checking.** `python3 tools/contracts.py check` compares every contract with the pack and its data; each generator's `--check` (above) fails when the committed files differ from what it would write; in the console, `<MNEMONIC> <GO>` lists a kind and the pack's code opens its overview with the count of each kind.
- **Offline.** Everything committed works without a network: the test data, the samples and the public-data snapshots are in the repository and `lake demo` reads only them. A data feed runs offline from its download cache (`--offline`); a connector that reaches the internet (news, yfinance, the public-data feeds) is off as shipped and never starts on a machine configured offline (`drishti.offline.plugins`).

## Trading products

The trading pack covers **125 products** in ten asset classes. Each has its own Sutra, matched on the trade's `PRODUCT_TYPE`, laid out as a deal screen (ticket, legs side by side with their flows and resets, events, payments); Calypso and Murex book them. The table says what each product is in the model the samples were generated from: its terms, schedule, the market data it is valued with and its risk measures (the valuation is market-risk's trade valuation, `TVAL`). `TRD PRODUCT_TYPE=<product type> <GO>` lists the trades in one product.

| Asset class | Products |
|---|---|
| Rates | 26 |
| FX | 14 |
| Credit | 12 |
| Equity | 22 |
| Commodity | 15 |
| Inflation | 6 |
| Fixed income | 13 |
| Money market | 5 |
| Securities financing | 7 |
| Structured | 5 |

### Rates

| Product | PRODUCT_TYPE | Family | Schedule | Market data | Risk |
|---|---|---|---|---|---|
| Interest rate swap (fixed/float) | `IRS_FIXFLOAT` | swap | cashflows | [`ir-curve`](../contracts/samples/market-data.md#ir-curve) ([contract](../contracts/packs/market-data.md#crv-interest-rate-curve-ir-curve)), `ir-curve`, [`rate-fixing`](../contracts/samples/market-data.md#rate-fixing) ([contract](../contracts/packs/market-data.md#fix-rate-index-fixings-rate-fixing)) | DV01 |
| Overnight index swap | `OIS` | swap | cashflows | `ir-curve`, `rate-fixing` | DV01 |
| Basis swap (float/float) | `BASIS_SWAP` | swap | cashflows | `ir-curve`, `ir-curve`, `ir-curve` | DV01 |
| Cross-currency swap (non-MtM) | `XCCY_SWAP` | swap | cashflows | `ir-curve`, `ir-curve`, `ir-curve`, [`fx-spot`](../contracts/samples/market-data.md#fx-spot) ([contract](../contracts/packs/market-data.md#fx-fx-spot-rate-fx-spot)) | DV01, FX delta |
| Cross-currency swap (MtM resetting) | `XCCY_SWAP_MTM` | swap | cashflows | `ir-curve`, `ir-curve`, `ir-curve`, `fx-spot` | DV01, FX delta |
| Zero-coupon swap | `ZC_SWAP` | swap | cashflows | `ir-curve`, `ir-curve` | DV01 |
| Amortising swap | `AMORT_SWAP` | swap | amortization | `ir-curve`, `ir-curve` | DV01 |
| Accreting swap | `ACCRETING_SWAP` | swap | amortization | `ir-curve`, `ir-curve` | DV01 |
| Forward-starting swap | `FWD_START_SWAP` | swap | cashflows | `ir-curve`, `ir-curve` | DV01 |
| Constant maturity swap | `CMS_SWAP` | swap | cashflows | `ir-curve`, `ir-curve`, [`ir-vol-cube`](../contracts/samples/market-data.md#ir-vol-cube) ([contract](../contracts/packs/market-data.md#irv-swaption-volatility-cube-ir-vol-cube)) | DV01, Vega |
| Forward rate agreement | `FRA` | linear | fixings | `ir-curve`, `ir-curve` | DV01 |
| Short-term interest rate future | `STIR_FUTURE` | future | — | `ir-curve` | DV01 |
| Government bond future | `BOND_FUTURE` | future | — | `ir-curve`, `bond`, [`repo-curve`](../contracts/samples/market-data.md#repo-curve) ([contract](../contracts/packs/market-data.md#repo-repo-curve-repo-curve)) | DV01 |
| Interest rate cap | `CAP` | option | fixings | `ir-curve`, `ir-curve`, [`cap-vol-surface`](../contracts/samples/market-data.md#cap-vol-surface) ([contract](../contracts/packs/market-data.md#cpv-capfloor-volatility-surface-cap-vol-surface)) | DV01, Vega |
| Interest rate floor | `FLOOR` | option | fixings | `ir-curve`, `ir-curve`, `cap-vol-surface` | DV01, Vega |
| Interest rate collar | `COLLAR` | option | fixings | `ir-curve`, `ir-curve`, `cap-vol-surface` | DV01, Vega |
| European swaption | `SWAPTION_EUR` | option | — | `ir-curve`, `ir-curve`, `ir-vol-cube` | DV01, Vega |
| Bermudan swaption | `SWAPTION_BERM` | option | exercise | `ir-curve`, `ir-curve`, `ir-vol-cube` | DV01, Vega |
| American swaption | `SWAPTION_AMER` | option | — | `ir-curve`, `ir-curve`, `ir-vol-cube` | DV01, Vega |
| CMS cap / floor | `CMS_CAP` | option | fixings | `ir-curve`, `ir-curve`, `ir-vol-cube` | DV01, Vega |
| CMS spread option | `CMS_SPREAD_OPT` | option | fixings | `ir-curve`, `ir-curve`, `ir-vol-cube`, [`correlation-matrix`](../contracts/samples/market-data.md#correlation-matrix) ([contract](../contracts/packs/market-data.md#corr-correlation-matrix-correlation-matrix)) | DV01, Vega |
| STIR future option | `STIR_FUT_OPT` | option | — | `ir-curve`, `cap-vol-surface` | DV01, Vega |
| Callable swap | `CALLABLE_SWAP` | exotic | exercise | `ir-curve`, `ir-curve`, `ir-vol-cube` | DV01, Vega |
| Range accrual swap | `RANGE_ACCRUAL_SWAP` | exotic | observations | `ir-curve`, `ir-curve`, `ir-vol-cube` | DV01, Vega |
| Snowball swap | `SNOWBALL_SWAP` | exotic | observations | `ir-curve`, `ir-curve`, `ir-vol-cube` | DV01, Vega |
| Target redemption note (rates) | `TARN_RATES` | exotic | observations | `ir-curve`, `ir-curve`, `ir-vol-cube` | DV01, Vega |

**Interest rate swap (fixed/float)** (`IRS_FIXFLOAT`): Exchanges fixed for floating RFR-compounded payments. Terms: `fixedRate` (Fixed rate), `payFrequency` (Pay frequency), `dayCount` (Day count), `businessDay` (Business day).

**Overnight index swap** (`OIS`): Fixed against compounded overnight RFR. Terms: `fixedRate` (Fixed rate), `payFrequency` (Pay frequency), `dayCount` (Day count), `businessDay` (Business day).

**Basis swap (float/float)** (`BASIS_SWAP`): Two floating legs on different indices plus a spread. Terms: `spread` (Spread), `index1` (Leg 1 index), `index2` (Leg 2 index).

**Cross-currency swap (non-MtM)** (`XCCY_SWAP`): Exchanges principal and interest in two currencies. Terms: `otherCurrency` (Other currency), `fxRateAtStart` (FX at start), `xccyBasis` (Cross-currency basis).

**Cross-currency swap (MtM resetting)** (`XCCY_SWAP_MTM`): Notional on one leg resets to the prevailing FX rate each period. Terms: `otherCurrency` (Other currency), `resetFrequency` (Notional reset), `xccyBasis` (Cross-currency basis).

**Zero-coupon swap** (`ZC_SWAP`): Single fixed payment at maturity against compounded floating. Terms: `zeroRate` (Zero rate), `compounding` (Compounding).

**Amortising swap** (`AMORT_SWAP`): Notional amortises on a schedule, typically matching a loan. Terms: `fixedRate` (Fixed rate), `payFrequency` (Pay frequency), `dayCount` (Day count), `businessDay` (Business day), `amortisation` (Amortisation).

**Accreting swap** (`ACCRETING_SWAP`): Notional grows over time, e.g. for construction finance. Terms: `fixedRate` (Fixed rate), `payFrequency` (Pay frequency), `dayCount` (Day count), `businessDay` (Business day), `accretionRate` (Accretion).

**Forward-starting swap** (`FWD_START_SWAP`): Swap whose accrual starts at a future date. Terms: `fixedRate` (Fixed rate), `payFrequency` (Pay frequency), `dayCount` (Day count), `businessDay` (Business day), `forwardStart` (Forward start).

**Constant maturity swap** (`CMS_SWAP`): Floating leg pays a swap rate of fixed tenor, needing convexity adjustment. Terms: `cmsTenor` (CMS tenor), `spread` (Spread).

**Forward rate agreement** (`FRA`): Cash-settled agreement on a future period's rate. Terms: `fraRate` (FRA rate), `startPeriod` (Period), `fixingDate` (Fixing date).

**Short-term interest rate future** (`STIR_FUTURE`): Exchange-traded future on a 3-month RFR period (e.g. SR3). Terms: `contract` (Contract), `lots` (Lots), `tradePrice` (Trade price).

**Government bond future** (`BOND_FUTURE`): Future on a basket of deliverable government bonds. Terms: `contract` (Contract), `lots` (Lots), `ctd` (Cheapest to deliver), `conversionFactor` (Conversion factor).

**Interest rate cap** (`CAP`): Series of caplets paying when the floating rate exceeds the strike. Terms: `strike` (Strike), `capFrequency` (Caplet frequency), `premium` (Premium).

**Interest rate floor** (`FLOOR`): Series of floorlets paying when the floating rate is below the strike. Terms: `strike` (Strike), `floorFrequency` (Floorlet frequency), `premium` (Premium).

**Interest rate collar** (`COLLAR`): Long cap and short floor, usually zero-cost. Terms: `capStrike` (Cap strike), `floorStrike` (Floor strike).

**European swaption** (`SWAPTION_EUR`): Option to enter a swap at a fixed rate on one date. Terms: `payerReceiver` (Payer / receiver), `strike` (Strike), `expiryDate` (Expiry), `underlyingTenor` (Swap tenor), `settlement` (Settlement), `premium` (Premium).

**Bermudan swaption** (`SWAPTION_BERM`): Swaption exercisable on several dates. Terms: `payerReceiver` (Payer / receiver), `strike` (Strike), `firstExercise` (First exercise), `exerciseFrequency` (Exercise frequency).

**American swaption** (`SWAPTION_AMER`): Swaption exercisable at any time before expiry. Terms: `payerReceiver` (Payer / receiver), `strike` (Strike), `expiryDate` (Expiry).

**CMS cap / floor** (`CMS_CAP`): Caps or floors on a constant-maturity swap rate. Terms: `cmsTenor` (CMS tenor), `strike` (Strike), `capFloor` (Cap / floor).

**CMS spread option** (`CMS_SPREAD_OPT`): Option on the spread between two CMS rates (e.g. 10Y–2Y). Terms: `longTenor` (Long tenor), `shortTenor` (Short tenor), `strike` (Strike).

**STIR future option** (`STIR_FUT_OPT`): Listed option on a short-term interest rate future. Terms: `contract` (Contract), `lots` (Lots), `premiumTicks` (Premium (ticks)).

**Callable swap** (`CALLABLE_SWAP`): Swap one party may cancel on call dates. Terms: `fixedRate` (Fixed rate), `payFrequency` (Pay frequency), `dayCount` (Day count), `businessDay` (Business day), `callable` (Callable by), `firstCall` (First call).

**Range accrual swap** (`RANGE_ACCRUAL_SWAP`): Coupon accrues only on days the index is inside a range. Terms: `lowerBound` (Lower bound), `upperBound` (Upper bound), `accrualRate` (Accrual rate).

**Snowball swap** (`SNOWBALL_SWAP`): Each coupon depends on the previous one plus a spread minus the index. Terms: `initialCoupon` (Initial coupon), `snowballSpread` (Spread).

**Target redemption note (rates)** (`TARN_RATES`): Terminates once cumulative coupons reach a target. Terms: `target` (Target coupon), `couponFormula` (Coupon).

### FX

| Product | PRODUCT_TYPE | Family | Schedule | Market data | Risk |
|---|---|---|---|---|---|
| FX spot | `FX_SPOT` | linear | — | `fx-spot` | FX delta |
| FX forward | `FX_FORWARD` | linear | — | `fx-spot`, [`fx-forward-curve`](../contracts/samples/market-data.md#fx-forward-curve) ([contract](../contracts/packs/market-data.md#fxf-fx-forward-curve-fx-forward-curve)), `ir-curve` | FX delta, DV01 |
| Non-deliverable forward | `FX_NDF` | linear | — | `fx-spot`, `fx-forward-curve`, `ir-curve` | FX delta |
| FX swap | `FX_SWAP` | swap | cashflows | `fx-spot`, `fx-forward-curve`, `ir-curve` | FX delta, DV01 |
| FX vanilla option | `FX_OPTION` | option | — | `fx-spot`, `fx-forward-curve`, [`fx-vol-surface`](../contracts/samples/market-data.md#fx-vol-surface) ([contract](../contracts/packs/market-data.md#fxv-fx-volatility-surface-fx-vol-surface)) | FX delta, Vega, Gamma |
| FX digital option | `FX_DIGITAL` | option | — | `fx-spot`, `fx-vol-surface` | FX delta, Vega |
| FX barrier option | `FX_BARRIER` | exotic | observations | `fx-spot`, `fx-vol-surface` | FX delta, Vega, Gamma |
| FX double-barrier option | `FX_DOUBLE_BARRIER` | exotic | observations | `fx-spot`, `fx-vol-surface` | FX delta, Vega |
| FX one-touch / no-touch | `FX_TOUCH` | exotic | observations | `fx-spot`, `fx-vol-surface` | FX delta, Vega |
| FX average-rate option | `FX_ASIAN` | exotic | observations | `fx-spot`, `fx-vol-surface` | FX delta, Vega |
| FX target redemption forward | `FX_TARF` | exotic | observations | `fx-spot`, `fx-vol-surface` | FX delta, Vega |
| FX accumulator | `FX_ACCUMULATOR` | exotic | observations | `fx-spot`, `fx-vol-surface` | FX delta, Vega |
| FX variance swap | `FX_VARIANCE_SWAP` | exotic | observations | `fx-spot`, `fx-vol-surface` | Vega |
| Quanto option | `FX_QUANTO` | exotic | — | `fx-spot`, `fx-vol-surface`, `correlation-matrix` | FX delta, Vega |

**FX spot** (`FX_SPOT`): Exchange of two currencies at T+2. Terms: `rate` (Rate), `valueDate` (Value date).

**FX forward** (`FX_FORWARD`): Exchange at an agreed rate on a future date. Terms: `rate` (Forward rate), `valueDate` (Value date), `forwardPoints` (Forward points).

**Non-deliverable forward** (`FX_NDF`): Cash-settled forward in USD on a restricted currency. Terms: `rate` (Forward rate), `fixingDate` (Fixing date), `fixingSource` (Fixing source).

**FX swap** (`FX_SWAP`): Spot or near leg reversed on a far date. Terms: `nearRate` (Near rate), `farRate` (Far rate), `swapPoints` (Swap points).

**FX vanilla option** (`FX_OPTION`): Right to exchange at a strike on expiry. Terms: `optionType` (Option type), `exerciseStyle` (Exercise), `expiryDate` (Expiry), `premium` (Premium), `strike` (Strike), `deltaAtTrade` (Delta at trade).

**FX digital option** (`FX_DIGITAL`): Fixed payout if the rate is beyond the strike at expiry. Terms: `optionType` (Type), `strike` (Strike), `payout` (Payout), `expiryDate` (Expiry).

**FX barrier option** (`FX_BARRIER`): Vanilla option knocked in or out when a barrier trades. Terms: `optionType` (Option type), `exerciseStyle` (Exercise), `expiryDate` (Expiry), `premium` (Premium), `strike` (Strike), `barrierType` (Barrier), `barrier` (Barrier level), `rebate` (Rebate).

**FX double-barrier option** (`FX_DOUBLE_BARRIER`): Knocked out if either of two barriers trades. Terms: `optionType` (Option type), `exerciseStyle` (Exercise), `expiryDate` (Expiry), `premium` (Premium), `strike` (Strike), `lowerBarrier` (Lower barrier), `upperBarrier` (Upper barrier).

**FX one-touch / no-touch** (`FX_TOUCH`): Pays if the barrier is (or is not) touched before expiry. Terms: `touchType` (Type), `barrier` (Barrier), `payout` (Payout), `expiryDate` (Expiry).

**FX average-rate option** (`FX_ASIAN`): Payoff on the average rate over fixings. Terms: `optionType` (Option type), `exerciseStyle` (Exercise), `expiryDate` (Expiry), `premium` (Premium), `strike` (Strike), `averaging` (Averaging).

**FX target redemption forward** (`FX_TARF`): Series of leveraged forwards ending when a profit target is hit. Terms: `strike` (Strike), `target` (Target (pips)), `leverage` (Leverage).

**FX accumulator** (`FX_ACCUMULATOR`): Buys (or sells) daily at a discount until knock-out. Terms: `strike` (Strike), `knockOut` (Knock-out), `dailyAmount` (Daily amount).

**FX variance swap** (`FX_VARIANCE_SWAP`): Exchanges realised against strike variance of an FX rate. Terms: `varianceStrike` (Variance strike (vol pts)), `vegaNotional` (Vega notional).

**Quanto option** (`FX_QUANTO`): Option on a foreign underlying paid in domestic currency at a fixed rate. Terms: `optionType` (Option type), `exerciseStyle` (Exercise), `expiryDate` (Expiry), `premium` (Premium), `strike` (Strike), `quantoRate` (Quanto rate).

### Credit

| Product | PRODUCT_TYPE | Family | Schedule | Market data | Risk |
|---|---|---|---|---|---|
| Single-name CDS | `CDS_SINGLE` | cds | cashflows | [`credit-curve`](../contracts/samples/market-data.md#credit-curve) ([contract](../contracts/packs/market-data.md#cds-credit-curve-credit-curve)), `ir-curve` | CS01, Jump to default |
| CDS index (CDX / iTraxx) | `CDS_INDEX` | cds | cashflows | `credit-curve`, `ir-curve` | CS01, Jump to default |
| Index tranche | `CDS_TRANCHE` | cds | cashflows | `credit-curve`, `ir-curve`, `correlation-matrix` | CS01, Jump to default |
| CDS index option | `CDS_SWAPTION` | option | — | `credit-curve`, `ir-curve` | CS01, Vega |
| First-to-default basket | `FIRST_TO_DEFAULT` | cds | constituents | `credit-curve`, `ir-curve`, `correlation-matrix` | CS01, Jump to default |
| Total return swap on a bond | `TRS_BOND` | swap | cashflows | `bond`, `ir-curve`, `repo-curve` | CS01, DV01 |
| Total return swap on a loan index | `TRS_LOAN_INDEX` | swap | cashflows | `ir-curve`, `ir-curve` | CS01 |
| Credit-linked note | `CLN` | note | coupons | `credit-curve`, `ir-curve` | CS01, Jump to default, DV01 |
| Term loan | `TERM_LOAN` | loan | amortization | `ir-curve`, `ir-curve`, `credit-curve` | CS01, DV01 |
| Revolving credit facility | `REVOLVER` | loan | — | `ir-curve`, `ir-curve`, `credit-curve` | CS01 |
| Credit spread option | `CREDIT_SPREAD_OPT` | option | — | `bond`, `credit-curve`, `ir-curve` | CS01, Vega |
| Unfunded risk participation | `RISK_PARTICIPATION` | cds | — | `credit-curve`, `ir-curve` | CS01, Jump to default |

**Single-name CDS** (`CDS_SINGLE`): Protection on one reference entity against a running coupon. Terms: `protection` (Protection), `coupon` (Coupon), `seniority` (Seniority), `docClause` (Doc clause), `upfront` (Upfront).

**CDS index (CDX / iTraxx)** (`CDS_INDEX`): Protection on a standard index of names. Terms: `protection` (Protection), `index` (Index), `coupon` (Coupon), `version` (Version).

**Index tranche** (`CDS_TRANCHE`): Protection on a slice of index losses between attachment points. Terms: `index` (Index), `attachment` (Attachment), `detachment` (Detachment), `protection` (Protection).

**CDS index option** (`CDS_SWAPTION`): Option to buy (payer) or sell (receiver) index protection. Terms: `payerReceiver` (Payer / receiver), `strikeSpread` (Strike spread), `expiryDate` (Expiry).

**First-to-default basket** (`FIRST_TO_DEFAULT`): Pays on the first default among a small basket of names. Terms: `basketSize` (Names), `spread` (Spread).

**Total return swap on a bond** (`TRS_BOND`): Pays the bond's total return against funding. Terms: `fundingSpread` (Funding spread), `resetFrequency` (Reset).

**Total return swap on a loan index** (`TRS_LOAN_INDEX`): Total return of a leveraged-loan index against SOFR. Terms: `index` (Index), `fundingSpread` (Funding spread).

**Credit-linked note** (`CLN`): Note whose principal is reduced by a credit event on a reference entity. Terms: `coupon` (Coupon), `issuePrice` (Issue price).

**Term loan** (`TERM_LOAN`): Drawn loan amortising or bullet, floating over SOFR. Terms: `margin` (Margin), `facilityType` (Facility), `amortisation` (Amortisation).

**Revolving credit facility** (`REVOLVER`): Committed facility drawn and repaid at will. Terms: `commitment` (Commitment), `drawn` (Drawn), `commitmentFee` (Commitment fee), `margin` (Margin).

**Credit spread option** (`CREDIT_SPREAD_OPT`): Option on a bond's spread over its benchmark. Terms: `strikeSpread` (Strike spread), `optionType` (Type), `expiryDate` (Expiry).

**Unfunded risk participation** (`RISK_PARTICIPATION`): Takes a share of the default risk on a loan for a fee. Terms: `participation` (Participation), `fee` (Fee).

### Equity

| Product | PRODUCT_TYPE | Family | Schedule | Market data | Risk |
|---|---|---|---|---|---|
| Equity position | `EQ_CASH` | position | — | `equity`, [`dividend-curve`](../contracts/samples/market-data.md#dividend-curve) ([contract](../contracts/packs/market-data.md#div-dividend-curve-dividend-curve)) | Delta |
| Equity forward | `EQ_FORWARD` | linear | — | `equity`, `dividend-curve`, `ir-curve` | Delta |
| Single-stock future | `EQ_FUTURE_SINGLE` | future | — | `equity`, `dividend-curve` | Delta |
| Equity index future | `EQ_INDEX_FUTURE` | future | — | [`equity-index`](../contracts/samples/market-data.md#equity-index) ([contract](../contracts/packs/market-data.md#eqx-equity-index-equity-index)), `dividend-curve` | Delta |
| Listed equity option | `EQ_OPTION_LISTED` | option | — | `equity`, [`equity-vol-surface`](../contracts/samples/market-data.md#equity-vol-surface) ([contract](../contracts/packs/market-data.md#eqv-equity-volatility-surface-equity-vol-surface)), `dividend-curve` | Delta, Gamma, Vega, Theta |
| OTC equity option | `EQ_OPTION_OTC` | option | — | `equity`, `equity-vol-surface`, `dividend-curve`, `ir-curve` | Delta, Gamma, Vega, Theta |
| Index option | `EQ_INDEX_OPTION` | option | — | `equity-index`, `equity-vol-surface` | Delta, Gamma, Vega, Theta |
| Equity barrier option | `EQ_BARRIER` | exotic | observations | `equity`, `equity-vol-surface`, `dividend-curve` | Delta, Gamma, Vega |
| Equity Asian option | `EQ_ASIAN` | exotic | observations | `equity`, `equity-vol-surface`, `dividend-curve` | Delta, Vega |
| Equity digital option | `EQ_DIGITAL` | option | — | `equity`, `equity-vol-surface`, `dividend-curve` | Delta, Vega |
| Cliquet (ratchet) | `EQ_CLIQUET` | exotic | observations | `equity-index`, `equity-vol-surface` | Delta, Vega |
| Autocallable note | `AUTOCALLABLE` | exotic | observations | `equity-index`, `equity-vol-surface`, `correlation-matrix` | Delta, Vega, Gamma |
| Reverse convertible | `REVERSE_CONVERTIBLE` | note | coupons | `equity`, `equity-vol-surface`, `dividend-curve` | Delta, Vega |
| Variance swap | `EQ_VARIANCE_SWAP` | exotic | observations | `equity-index`, `equity-vol-surface` | Vega |
| Volatility swap | `EQ_VOL_SWAP` | exotic | observations | `equity-index`, `equity-vol-surface` | Vega |
| Dividend swap | `DIVIDEND_SWAP` | swap | — | `equity-index`, `dividend-curve` | Delta |
| Equity total return swap | `EQ_TRS` | swap | cashflows | `equity`, `dividend-curve`, `ir-curve` | Delta, DV01 |
| Portfolio (basket) swap | `PORTFOLIO_SWAP` | swap | constituents | `equity-index`, `dividend-curve`, `ir-curve` | Delta, DV01 |
| Contract for difference | `CFD` | linear | — | `equity`, `dividend-curve` | Delta |
| Equity warrant | `WARRANT` | option | — | `equity`, `equity-vol-surface` | Delta, Vega |
| Convertible bond | `CONVERTIBLE_BOND` | bond | coupons | `equity`, `equity-vol-surface`, `credit-curve`, `ir-curve` | Delta, CS01, Vega |
| Basket option | `EQ_BASKET_OPTION` | exotic | constituents | `equity-index`, `equity-vol-surface`, `correlation-matrix` | Delta, Vega |

**Equity position** (`EQ_CASH`): Long or short shares in a listed stock. Terms: `quantity` (Quantity), `averagePrice` (Average price).

**Equity forward** (`EQ_FORWARD`): Buy or sell shares at a fixed price on a future date. Terms: `forwardPrice` (Forward price), `quantity` (Quantity), `settlementDate` (Settlement).

**Single-stock future** (`EQ_FUTURE_SINGLE`): Exchange-traded future on one stock. Terms: `contract` (Contract), `lots` (Lots).

**Equity index future** (`EQ_INDEX_FUTURE`): Exchange-traded future on an equity index (ES, NQ, FESX…). Terms: `contract` (Contract), `lots` (Lots), `tradePrice` (Trade price).

**Listed equity option** (`EQ_OPTION_LISTED`): Exchange-traded option on a stock. Terms: `optionType` (Option type), `exerciseStyle` (Exercise), `expiryDate` (Expiry), `strike` (Strike), `lots` (Contracts).

**OTC equity option** (`EQ_OPTION_OTC`): Bilateral option on a stock. Terms: `optionType` (Option type), `exerciseStyle` (Exercise), `expiryDate` (Expiry), `strike` (Strike), `quantity` (Quantity).

**Index option** (`EQ_INDEX_OPTION`): Option on an equity index (SPX, SX5E, NKY…). Terms: `optionType` (Option type), `exerciseStyle` (Exercise), `expiryDate` (Expiry), `strike` (Strike), `contracts` (Contracts).

**Equity barrier option** (`EQ_BARRIER`): Option activated or extinguished at a barrier. Terms: `optionType` (Option type), `exerciseStyle` (Exercise), `expiryDate` (Expiry), `strike` (Strike), `barrierType` (Barrier), `barrier` (Barrier level).

**Equity Asian option** (`EQ_ASIAN`): Payoff on the average of observed prices. Terms: `optionType` (Option type), `exerciseStyle` (Exercise), `expiryDate` (Expiry), `strike` (Strike), `averaging` (Averaging).

**Equity digital option** (`EQ_DIGITAL`): Fixed payout if the stock finishes beyond the strike. Terms: `optionType` (Type), `strike` (Strike), `payout` (Payout), `expiryDate` (Expiry).

**Cliquet (ratchet)** (`EQ_CLIQUET`): Sum of capped and floored periodic returns. Terms: `localCap` (Local cap), `localFloor` (Local floor), `globalFloor` (Global floor).

**Autocallable note** (`AUTOCALLABLE`): Redeems early when the underlier is above a trigger on an observation date. Terms: `autocallTrigger` (Autocall trigger), `couponRate` (Coupon), `knockInBarrier` (Knock-in barrier), `memory` (Memory coupon).

**Reverse convertible** (`REVERSE_CONVERTIBLE`): High coupon; redeems in shares if the stock falls below a barrier. Terms: `coupon` (Coupon), `barrier` (Barrier).

**Variance swap** (`EQ_VARIANCE_SWAP`): Exchanges realised variance for a strike. Terms: `varianceStrike` (Variance strike (vol pts)), `vegaNotional` (Vega notional).

**Volatility swap** (`EQ_VOL_SWAP`): Exchanges realised volatility for a strike. Terms: `volStrike` (Vol strike), `vegaNotional` (Vega notional).

**Dividend swap** (`DIVIDEND_SWAP`): Pays realised index dividends against a fixed strike. Terms: `dividendStrike` (Dividend strike), `period` (Period).

**Equity total return swap** (`EQ_TRS`): Pays a stock's total return against funding. Terms: `fundingSpread` (Funding spread), `resetFrequency` (Reset), `quantity` (Quantity).

**Portfolio (basket) swap** (`PORTFOLIO_SWAP`): Total return on a custom basket of stocks. Terms: `constituents` (Constituents), `fundingSpread` (Funding spread).

**Contract for difference** (`CFD`): Cash-settled exposure to a stock's price move. Terms: `quantity` (Quantity), `openPrice` (Open price).

**Equity warrant** (`WARRANT`): Issuer-listed long-dated call or put. Terms: `optionType` (Option type), `exerciseStyle` (Exercise), `expiryDate` (Expiry), `strike` (Strike), `ratio` (Ratio).

**Convertible bond** (`CONVERTIBLE_BOND`): Bond convertible into the issuer's shares. Terms: `coupon` (Coupon), `conversionRatio` (Conversion ratio), `conversionPrice` (Conversion price).

**Basket option** (`EQ_BASKET_OPTION`): Option on a weighted basket of stocks. Terms: `optionType` (Option type), `exerciseStyle` (Exercise), `expiryDate` (Expiry), `strike` (Strike), `basketSize` (Stocks).

### Commodity

| Product | PRODUCT_TYPE | Family | Schedule | Market data | Risk |
|---|---|---|---|---|---|
| Commodity future | `CMD_FUTURE` | future | — | [`commodity-curve`](../contracts/samples/market-data.md#commodity-curve) ([contract](../contracts/packs/market-data.md#cmdc-commodity-forward-curve-commodity-curve)) | Delta |
| Commodity forward | `CMD_FORWARD` | linear | — | `commodity-curve`, `ir-curve` | Delta |
| Commodity swap | `CMD_SWAP` | swap | cashflows | `commodity-curve`, `ir-curve` | Delta |
| Commodity basis swap | `CMD_BASIS_SWAP` | swap | fixings | `commodity-curve`, `ir-curve` | Delta |
| Option on commodity future | `CMD_FUT_OPTION` | option | — | `commodity-curve`, [`commodity-vol-surface`](../contracts/samples/market-data.md#commodity-vol-surface) ([contract](../contracts/packs/market-data.md#cmdv-commodity-volatility-surface-commodity-vol-surface)) | Delta, Gamma, Vega |
| Average price option (APO) | `CMD_APO` | exotic | fixings | `commodity-curve`, `commodity-vol-surface` | Delta, Vega |
| Commodity swaption | `CMD_SWAPTION` | option | — | `commodity-curve`, `commodity-vol-surface` | Delta, Vega |
| Commodity spread option | `CMD_SPREAD_OPT` | exotic | — | `commodity-curve`, `commodity-vol-surface`, `correlation-matrix` | Delta, Vega |
| Calendar spread | `CMD_CALENDAR_SPREAD` | linear | — | `commodity-curve` | Delta |
| Commodity index swap | `CMD_INDEX_SWAP` | swap | cashflows | `commodity-curve`, `ir-curve` | Delta |
| Power swap | `POWER_SWAP` | swap | fixings | `commodity-curve`, `ir-curve` | Delta |
| Gas swing option | `GAS_SWING` | exotic | exercise | `commodity-curve`, `commodity-vol-surface` | Delta, Vega |
| Emissions allowance future | `EMISSIONS_FUTURE` | future | — | `commodity-curve` | Delta |
| Weather derivative (HDD/CDD) | `WEATHER_HDD` | exotic | observations | `ir-curve` | Delta |
| Freight forward agreement | `FREIGHT_FFA` | linear | fixings | `commodity-curve` | Delta |

**Commodity future** (`CMD_FUTURE`): Exchange-traded future (CL, NG, GC, HG, C, W…). Terms: `contract` (Contract), `lots` (Lots), `tradePrice` (Trade price).

**Commodity forward** (`CMD_FORWARD`): OTC agreement to buy or sell at a fixed price. Terms: `forwardPrice` (Forward price), `quantity` (Quantity), `deliveryMonth` (Delivery).

**Commodity swap** (`CMD_SWAP`): Fixed price against the average of a floating index, with a fee and, on some trades, a location differential: three or four legs. Terms: `fixedPrice` (Fixed price), `quantityPerPeriod` (Quantity per period), `averaging` (Averaging).

**Commodity basis swap** (`CMD_BASIS_SWAP`): Floating against floating on two locations or grades. Terms: `spread` (Spread), `location` (Locations).

**Option on commodity future** (`CMD_FUT_OPTION`): Listed option on a commodity future. Terms: `optionType` (Option type), `exerciseStyle` (Exercise), `expiryDate` (Expiry), `strike` (Strike), `lots` (Lots).

**Average price option (APO)** (`CMD_APO`): Option on the monthly average price. Terms: `optionType` (Option type), `exerciseStyle` (Exercise), `expiryDate` (Expiry), `strike` (Strike), `averagingPeriod` (Averaging).

**Commodity swaption** (`CMD_SWAPTION`): Option to enter a commodity swap. Terms: `payerReceiver` (Payer / receiver), `strike` (Strike), `expiryDate` (Expiry).

**Commodity spread option** (`CMD_SPREAD_OPT`): Option on the spread between two commodities. Terms: `spreadType` (Spread), `strike` (Strike).

**Calendar spread** (`CMD_CALENDAR_SPREAD`): Long one delivery month, short another. Terms: `nearMonth` (Near month), `farMonth` (Far month), `lots` (Lots).

**Commodity index swap** (`CMD_INDEX_SWAP`): Total return on a commodity index (BCOM, S&P GSCI). Terms: `index` (Index), `fundingSpread` (Fee).

**Power swap** (`POWER_SWAP`): Fixed price against the hourly or daily power index. Terms: `hub` (Hub), `shape` (Shape), `mwh` (Volume (MWh)).

**Gas swing option** (`GAS_SWING`): Flexible daily volume within limits at a fixed price. Terms: `hub` (Hub), `minDaily` (Min daily (MMBtu)), `maxDaily` (Max daily (MMBtu)).

**Emissions allowance future** (`EMISSIONS_FUTURE`): Future on EU or UK carbon allowances. Terms: `contract` (Contract), `lots` (Lots).

**Weather derivative (HDD/CDD)** (`WEATHER_HDD`): Pays on heating or cooling degree days at a station. Terms: `station` (Station), `index` (Index), `strike` (Strike (degree days)), `tick` (Tick).

**Freight forward agreement** (`FREIGHT_FFA`): Cash-settled forward on a shipping route index. Terms: `route` (Route), `days` (Days), `fixedRate` (Rate ($/day)).

### Inflation

| Product | PRODUCT_TYPE | Family | Schedule | Market data | Risk |
|---|---|---|---|---|---|
| Zero-coupon inflation swap | `ZC_INFL_SWAP` | swap | cashflows | `ir-curve`, [`inflation-curve`](../contracts/samples/market-data.md#inflation-curve) ([contract](../contracts/packs/market-data.md#infc-inflation-curve-inflation-curve)), [`inflation-index`](../contracts/samples/market-data.md#inflation-index) ([contract](../contracts/packs/market-data.md#inf-inflation-index-inflation-index)) | IE01, DV01 |
| Year-on-year inflation swap | `YOY_INFL_SWAP` | swap | cashflows | `ir-curve`, `inflation-curve`, `inflation-index` | IE01, DV01 |
| Inflation cap / floor | `INFL_CAP_FLOOR` | option | fixings | `ir-curve`, `inflation-curve`, `inflation-index` | IE01, Vega |
| Inflation-linked bond | `INFL_LINKED_BOND` | bond | coupons | `ir-curve`, `inflation-curve`, `inflation-index` | IE01, DV01 |
| Inflation asset swap | `INFL_ASSET_SWAP` | swap | cashflows | `ir-curve`, `inflation-curve`, `inflation-index` | IE01, DV01 |
| Limited price index swap | `LPI_SWAP` | swap | cashflows | `ir-curve`, `inflation-curve`, `inflation-index` | IE01, Vega |

**Zero-coupon inflation swap** (`ZC_INFL_SWAP`): Fixed compounded rate against CPI growth at maturity. Terms: `fixedRate` (Fixed (breakeven)), `indexLag` (Index lag), `baseIndex` (Base index).

**Year-on-year inflation swap** (`YOY_INFL_SWAP`): Annual CPI growth against a fixed rate. Terms: `fixedRate` (Fixed rate), `indexLag` (Index lag).

**Inflation cap / floor** (`INFL_CAP_FLOOR`): Caps or floors on year-on-year inflation. Terms: `capFloor` (Cap / floor), `strike` (Strike).

**Inflation-linked bond** (`INFL_LINKED_BOND`): Government bond with CPI-indexed principal (TIPS, linkers). Terms: `coupon` (Coupon), `couponFrequency` (Coupon frequency), `faceAmount` (Face amount), `cleanPrice` (Clean price), `yield` (Yield), `indexRatio` (Index ratio).

**Inflation asset swap** (`INFL_ASSET_SWAP`): Linker packaged with a swap to pay floating. Terms: `assetSwapSpread` (Asset swap spread).

**Limited price index swap** (`LPI_SWAP`): Inflation capped and floored each year (UK LPI 0–5%). Terms: `lpiFloor` (Floor), `lpiCap` (Cap).

### Fixed income

| Product | PRODUCT_TYPE | Family | Schedule | Market data | Risk |
|---|---|---|---|---|---|
| Government bond | `GOVT_BOND` | bond | coupons | `ir-curve`, `bond` | DV01 |
| Corporate bond | `CORP_BOND` | bond | coupons | `ir-curve`, `bond`, `credit-curve` | DV01, CS01, Jump to default |
| Floating rate note | `FRN` | bond | coupons | `ir-curve`, `bond`, `credit-curve` | CS01, DV01 |
| Zero-coupon bond | `ZERO_BOND` | bond | — | `ir-curve`, `bond` | DV01 |
| Callable bond | `CALLABLE_BOND` | bond | exercise | `ir-curve`, `bond`, `ir-vol-cube` | DV01, CS01, Vega |
| Puttable bond | `PUTTABLE_BOND` | bond | exercise | `ir-curve`, `bond`, `ir-vol-cube` | DV01, CS01, Vega |
| Amortising bond | `AMORT_BOND` | bond | amortization | `ir-curve`, `bond` | DV01, CS01 |
| Covered bond | `COVERED_BOND` | bond | coupons | `ir-curve`, `bond` | DV01, CS01 |
| Municipal bond | `MUNI_BOND` | bond | coupons | `ir-curve`, `bond` | DV01, CS01 |
| Agency MBS pass-through | `MBS_PASSTHROUGH` | securitized | amortization | `ir-curve`, `ir-curve`, `ir-vol-cube` | DV01, Vega |
| CMO tranche | `CMO_TRANCHE` | securitized | amortization | `ir-curve`, `ir-curve`, `ir-vol-cube` | DV01, Vega |
| Asset-backed security | `ABS` | securitized | amortization | `ir-curve`, `credit-curve` | DV01, CS01 |
| CLO tranche | `CLO_TRANCHE` | securitized | amortization | `ir-curve`, `credit-curve` | CS01, DV01 |

**Government bond** (`GOVT_BOND`): Fixed-coupon sovereign bond (UST, Bund, Gilt, JGB). Terms: `coupon` (Coupon), `couponFrequency` (Coupon frequency), `faceAmount` (Face amount), `cleanPrice` (Clean price), `yield` (Yield).

**Corporate bond** (`CORP_BOND`): Fixed-coupon bond of a corporate issuer. Terms: `coupon` (Coupon), `couponFrequency` (Coupon frequency), `faceAmount` (Face amount), `cleanPrice` (Clean price), `yield` (Yield), `zSpread` (Z-spread), `seniority` (Seniority).

**Floating rate note** (`FRN`): Coupon resets over an RFR or term rate. Terms: `quotedMargin` (Quoted margin), `faceAmount` (Face amount), `cleanPrice` (Clean price).

**Zero-coupon bond** (`ZERO_BOND`): Issued at a discount, redeemed at par. Terms: `faceAmount` (Face amount), `cleanPrice` (Price), `yield` (Yield).

**Callable bond** (`CALLABLE_BOND`): Issuer may redeem early on call dates. Terms: `coupon` (Coupon), `couponFrequency` (Coupon frequency), `faceAmount` (Face amount), `cleanPrice` (Clean price), `yield` (Yield), `firstCall` (First call), `callPrice` (Call price), `oas` (OAS).

**Puttable bond** (`PUTTABLE_BOND`): Holder may sell back to the issuer on put dates. Terms: `coupon` (Coupon), `couponFrequency` (Coupon frequency), `faceAmount` (Face amount), `cleanPrice` (Clean price), `yield` (Yield), `firstPut` (First put), `putPrice` (Put price).

**Amortising bond** (`AMORT_BOND`): Principal repaid on a schedule. Terms: `coupon` (Coupon), `couponFrequency` (Coupon frequency), `faceAmount` (Face amount), `cleanPrice` (Clean price), `yield` (Yield), `averageLife` (Average life (years)).

**Covered bond** (`COVERED_BOND`): Bank bond backed by a cover pool of mortgages. Terms: `coupon` (Coupon), `couponFrequency` (Coupon frequency), `faceAmount` (Face amount), `cleanPrice` (Clean price), `yield` (Yield), `coverPool` (Cover pool).

**Municipal bond** (`MUNI_BOND`): US state or local government bond, often tax-exempt. Terms: `coupon` (Coupon), `couponFrequency` (Coupon frequency), `faceAmount` (Face amount), `cleanPrice` (Clean price), `yield` (Yield), `taxStatus` (Tax status), `sector` (Sector).

**Agency MBS pass-through** (`MBS_PASSTHROUGH`): Pool of mortgages passing through principal and interest (TBA, specified pools). Terms: `agency` (Agency), `couponRate` (Coupon), `wac` (WAC), `cpr` (CPR), `factor` (Pool factor).

**CMO tranche** (`CMO_TRANCHE`): Tranche of a collateralised mortgage obligation (PAC, IO, PO…). Terms: `trancheType` (Tranche), `wal` (WAL (years)).

**Asset-backed security** (`ABS`): Securitised auto loans, credit cards or student loans. Terms: `collateral` (Collateral), `tranche` (Tranche), `wal` (WAL (years)).

**CLO tranche** (`CLO_TRANCHE`): Tranche of a collateralised loan obligation. Terms: `tranche` (Tranche), `discountMargin` (Discount margin).

### Money market

| Product | PRODUCT_TYPE | Family | Schedule | Market data | Risk |
|---|---|---|---|---|---|
| Treasury bill | `T_BILL` | money | — | `ir-curve` | DV01 |
| Commercial paper | `COMMERCIAL_PAPER` | money | — | `ir-curve`, `credit-curve` | DV01, CS01 |
| Certificate of deposit | `CERT_DEPOSIT` | money | — | `ir-curve` | DV01 |
| Term deposit / placement | `DEPOSIT` | money | — | `ir-curve` | DV01 |
| Call account | `CALL_ACCOUNT` | money | — | `ir-curve`, `rate-fixing` | DV01 |

**Treasury bill** (`T_BILL`): Short-term discount sovereign paper. Terms: `discountRate` (Discount rate), `faceAmount` (Face amount).

**Commercial paper** (`COMMERCIAL_PAPER`): Short-term unsecured corporate promissory note. Terms: `discountRate` (Discount rate), `rating` (Short-term rating).

**Certificate of deposit** (`CERT_DEPOSIT`): Bank time deposit in negotiable form. Terms: `rate` (Rate).

**Term deposit / placement** (`DEPOSIT`): Interbank deposit or placement. Terms: `rate` (Rate), `placedTaken` (Placed / taken).

**Call account** (`CALL_ACCOUNT`): Overnight or call balance at an RFR-linked rate. Terms: `rate` (Rate), `noticeDays` (Notice (days)).

### Securities financing

| Product | PRODUCT_TYPE | Family | Schedule | Market data | Risk |
|---|---|---|---|---|---|
| Repurchase agreement | `REPO` | sft | collateral | `repo-curve`, `bond` | DV01, Exposure |
| Reverse repo | `REVERSE_REPO` | sft | collateral | `repo-curve`, `bond` | DV01, Exposure |
| Tri-party repo | `TRIPARTY_REPO` | sft | collateral | `repo-curve` | Exposure |
| Buy / sell-back | `BUY_SELL_BACK` | sft | collateral | `repo-curve`, `bond` | DV01, Exposure |
| Securities lending | `SEC_LENDING` | sft | collateral | `equity`, `repo-curve` | Exposure, Delta |
| Securities borrowing | `SEC_BORROWING` | sft | collateral | `equity`, `repo-curve` | Exposure, Delta |
| Margin loan | `MARGIN_LOAN` | sft | collateral | `ir-curve`, `equity-index` | Exposure |

**Repurchase agreement** (`REPO`): Cash borrowed against collateral, repurchased at a later date. Terms: `repoRate` (Repo rate), `haircut` (Haircut), `term` (Term).

**Reverse repo** (`REVERSE_REPO`): Cash lent against collateral. Terms: `repoRate` (Repo rate), `haircut` (Haircut), `term` (Term).

**Tri-party repo** (`TRIPARTY_REPO`): Repo with collateral managed by a tri-party agent. Terms: `repoRate` (Repo rate), `agent` (Agent), `basket` (Collateral basket).

**Buy / sell-back** (`BUY_SELL_BACK`): Economically a repo, documented as two cash trades. Terms: `repoRate` (Implied repo), `haircut` (Haircut).

**Securities lending** (`SEC_LENDING`): Lend securities against collateral for a fee. Terms: `fee` (Lending fee), `collateralType` (Collateral), `rebateRate` (Rebate).

**Securities borrowing** (`SEC_BORROWING`): Borrow securities (e.g. to cover shorts) against collateral. Terms: `fee` (Borrow fee), `collateralType` (Collateral).

**Margin loan** (`MARGIN_LOAN`): Loan against a portfolio of securities with maintenance margin. Terms: `rate` (Rate), `ltv` (Loan to value), `maintenanceMargin` (Maintenance margin).

### Structured

| Product | PRODUCT_TYPE | Family | Schedule | Market data | Risk |
|---|---|---|---|---|---|
| Range accrual note | `RANGE_ACCRUAL_NOTE` | note | observations | `ir-curve`, `ir-curve`, `ir-vol-cube` | DV01, Vega |
| Capital-protected note | `CAPITAL_PROTECTED_NOTE` | note | observations | `equity-index`, `equity-vol-surface`, `ir-curve` | Delta, Vega, DV01 |
| FX-rates hybrid (PRDC) | `HYBRID_FX_RATES` | exotic | observations | `fx-spot`, `fx-vol-surface`, `ir-curve`, `correlation-matrix` | FX delta, DV01, Vega |
| Worst-of note | `WORST_OF_NOTE` | note | observations | `equity-index`, `equity-vol-surface`, `correlation-matrix` | Delta, Vega |
| Credit basket note | `CREDIT_BASKET_NOTE` | note | constituents | `credit-curve`, `ir-curve`, `correlation-matrix` | CS01, Jump to default |

**Range accrual note** (`RANGE_ACCRUAL_NOTE`): Coupon accrues on days an index stays in range. Terms: `coupon` (Max coupon), `lowerBound` (Lower bound), `upperBound` (Upper bound).

**Capital-protected note** (`CAPITAL_PROTECTED_NOTE`): Zero plus a call option on an index. Terms: `protection` (Capital protection), `participation` (Participation), `cap` (Cap).

**FX-rates hybrid (PRDC)** (`HYBRID_FX_RATES`): Power reverse dual-currency coupon linked to an FX rate. Terms: `couponFormula` (Coupon), `callable` (Callable).

**Worst-of note** (`WORST_OF_NOTE`): Coupon and redemption depend on the worst of several stocks. Terms: `coupon` (Coupon), `basketSize` (Stocks), `barrier` (Barrier).

**Credit basket note** (`CREDIT_BASKET_NOTE`): Nth-to-default note on a small basket. Terms: `coupon` (Coupon), `nth` (Nth default), `basketSize` (Names).

## The trading module

The module ships the trade blotter (`/live/trade`) and its binding; the steps below are specific to trades. The engine behind the blotter, the page,
editing, the declaration, the API and the settings are the platform's: the *Live results and blotters* guide (Help → Guides; `docs/guides/LIVE_RESULTS_GUIDE.md`).

### 1. Switch it on (tutorial)

Run these from the Drishti folder (the repository root, or the install folder of a release).

```bash
# 1. the module is on (Admin -> Modules, or the distribution lists it)
drishti.py module list | grep trading
# trading  1.0.0  first-party  content  on

# 2. the trade kind is bound with a live source (section 2), then the blotter answers
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" $B/live | jq '.sets[].id'
# "trade"
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" "$B/live/trade/rows?limit=3" | jq '{total, live, rows: [.rows[].id]}'
# {"total": 4120, "live": true, "rows": ["MX-20004324", "MX-20004261", "MX-20004205"]}
```

### 2. Bind the trades

The blotter reads the `trade` kind through `config/bindings.yaml`, like every module:

```yaml
bindings:
  trade:
    read: [trade-endur, trade-murex]   # the durable store: the snapshot
    union: true
    live: trade-kafka                  # the messaging connector: the changes, merged in by key and version
```

The Kafka connector reads its topic as the latest record per trade (`kind.<topic>: trade`, `id-field.<topic>: TRADE_ID`); RabbitMQ and
ActiveMQ work the same way through their own connectors. Every change of the kind reaches the blotter as it is consumed.

To land the day's live trades in the **in-memory lake** as well (for BI and the engines that read tables), a memory table reads the Kafka
connector's state in one pass and keeps a day of it:

```yaml
# config/engines/memory-lake.yaml (excerpt; DATA_LAKE_GUIDE.md, the in-memory lake)
drishti.lake.memory.tables:
  - name: trades-live
    store: livelake                       # config/lakes/livelake.yaml, root: mem://livelake
    source: { connector: trade-kafka, kind: trade }
    mode: merge
    key: [TRADE_ID]
    columns: [PORTFOLIO, TRADER_ID, TRADE_STATUS]
    signal: true                          # refilled when the connector's last update moves
    retention: { ttl: 1d, on: SOURCE_UPDATE_TIME }   # rows older than a day are dropped by the compaction
```

### 3. Edit a trade (tutorial)

1. Select the trade; its panel opens with the fields you may change (Book, Price, Quantity; Status for middle office).
2. Change the book, give a reason, **Submit**. The change goes to the write-back with the version you saw.
3. Where the site asks for four eyes (`config/edit/policy.yaml`, `source: one`), it waits in **Approvals** for another person; otherwise it
   goes to its lane at once.
4. The outbox sends it to the source once; the source's new version comes back through the live binding and the row ticks.

From the command line, the same request:

```bash
curl -s -X POST -H "Authorization: Bearer $DRISHTI_TOKEN" -H 'Content-Type: application/json' $B/live/trade/edit -d '{
  "entity": "MX-20004163", "basedOn": 7, "reason": "booked in the wrong book: ticket 4411",
  "items": [{"field": "PORTFOLIO", "from": "BOOK-IRS-1", "to": "BOOK-IRS-2"}]}'
# {"changes": [{"id": "chg-01M5V8...", "kind": "trade", "entity": "MX-20004163", "target": "source", "state": "awaiting", "needed": 1}]}
```

**A stale edit is refused.** When the trade moved since you saw it, the answer is `409 DRS-9303`:
*trade MX-20004163 is at version 8, changed by R.DIAZ at 10:02:11; you edited version 7: open it again and edit from what is there*.
The same guard runs again when the change is applied at the source (the *Live results and blotters* guide, section 8), so a change that raced another one is refused there too.

### 4. Source systems

The `trade` kind is one canonical kind (the trading pack's `trade` contract; every row carries `SOURCE_SYSTEM`). Each trading system that
feeds it is a **source module of its own**, `modules/source-<system>/`, which requires this module and switches on and off alone
(`config/modules/source-<system>.yaml`, `enabled: false`; off, the system's trades leave the kind and nothing else changes). Six ship:
`source-endur`, `source-calypso`, `source-murex` (their data is already in the canonical schema) and `source-wss` (treasury, FX and
money markets), `source-swapone` (OTC swaps) and `source-ici` (a generic cross-asset booking system; its asset class was not stated, so it
is mocked as one). The data of the last three is **mocked** (`tools/samplegen/src_*.py`; each in its own native field style; no
positions and no scenario P&L, so the VaR does not move).

A module's manifest names its parts with one set of keys: `connectors` (the folder of connector templates), `mappings` (kind to map file),
`dataload` (the inbox rules file) and `sections` (what it adds to the kind's page). These templates are the one home of the system's
connectors: the trading pack declares none of them. A file dropped in the inbox (`swapone-2026-11-03.jsonl.gz`; no `.ready` is needed, the
rule's `date-pattern` reads the date from the name) goes by the module's rule to its own table `trading.trade_swapone`, its connector's `set:` stamps `SOURCE_SYSTEM = SWAPONE` into each stored row, and `TRD` lists
it; switch `source-swapone` off and it is gone, on and it is back.

A module ships: `connectors/` (read from Delta, Iceberg and files; a Kafka live stream whose `writes:` is the write-back of
the *Live results and blotters* guide, section 8), `mappings/<system>-trade.yaml` (the native fields to the canonical ones), `dataload.yaml` (the inbox routing rules, in the
format of `config/dataload.yaml`; the inbox reads the site's rules first) and a short guide. A connector's source options:

| Option | Meaning |
|---|---|
| `lake-tables` | the table read: a table of the system's own, or one several systems share |
| `where` | a predicate that selects this system's rows of a shared table |
| `set` | constants stamped into the rows this connector reads and into the rows the inbox stores (`SOURCE_SYSTEM`) |
| `map` | the native-to-canonical field map (the module's `mappings/` file) |

**The map file** (`mappings/<system>-trade.yaml`, `format: 1`): under `map:` each line is a canonical field and where its value comes from.
Real feeds nest and rename, so one line can be:

| Form | Meaning |
|---|---|
| `TRADE_ID: SWAP_ID` | the native field `SWAP_ID` |
| `QUANTITY: PAY_LEG.NOTIONAL` | a dotted path into a nested native document |
| `from: [a, b]` or `first-of: [a, b]` | the first of the paths that is present and not blank |
| `scale: 0.01` | multiply the number (a rate in percent to a fraction) |
| `cast: text \| number \| integer \| boolean \| date` | the type |
| `as: yyyymmdd \| yyyymmddhhmmss \| epoch-millis \| epoch-seconds \| iso` | a date or time written another way (a date becomes `YYYY-MM-DD`, a time an ISO UTC instant) |
| `values: {LIVE: NEW}` | a code translated (a code not listed stays as read) |
| `value: X` | a constant |

```yaml
map:
  TRADE_ID: SWAP_ID
  QUANTITY: PAY_LEG.NOTIONAL
  PRICE: { first-of: [PAY_LEG.FIXED_RATE_PCT, REC_LEG.FIXED_RATE_PCT], scale: 0.01 }
  TRADE_DATE: { from: TRADE_DT, as: yyyymmdd }
```

The older form (`fields:`, native field to canonical field) still works. A map that cannot be used refuses the connector (`DRS-5034`, shown
on Admin -> Connectors). A field built from a nested path, alternatives, a scale or a reading is served from the documents, not from
columns. `drishti.py pack new --source-for KIND` proposes dotted paths for nested native fields.

**The date of a file in the inbox.** The module's rule names it from the file name: `date-from: name` with `date-pattern`, a regular
expression with one capture group around the date, `-(\d{4}-\d{2}-\d{2})\.` for `swapone-2026-11-03.jsonl.gz`. A date written
`20261103` takes `date-format: YYYYMMDD`. A `.ready` file's `business-date:` still wins; a name the pattern does not match is refused.

**One table a trading system, always** (Endur, Calypso, Murex, WSS, SwapOne and ICI: `trading.trade_<system>`): the connector reads
its own table, no `where` is needed, and a day's file of one system replaces that system's date and nothing else.

```yaml
# config/connectors/trade-endur.yaml
plugin: delta
settings:
  lake: main
  lake-tables: trading.trade_endur
  set: { SOURCE_SYSTEM: ENDUR }
  map: mappings/endur-trade.yaml
```

The kind reads them all as a union (`config/demo/bindings.yaml`, written by `drishti.py lake demo`, on by default; `--no-bindings` leaves it out):

## Market data pack

Curves, volatility surfaces, FX, equities, credit, inflation, commodities, fixings and bonds.

**Extends:** `core` · **Extended by:** `trading`, `market-risk` · **Kinds:** 20

Enable it with `DRISHTI_PACKS=market-data` (the packs it extends come with it; where they differ, the more specific wins).

### Finding things

Every kind has a mnemonic (see *Kinds*). Type it with an identifier, the start of one, or a search, then `<GO>`. Shown here for interest rate curves; the same works for every mnemonic.

| Command | Does |
|---|---|
| `CRV <id> <GO>` | Opens that interest rate curve. A bare identifier works too: its prefix (`CRV-…`) tells Drishti the kind. |
| `CRV <start of an id> <GO>` | A pick list: one match opens, several give a table with the kind's key fields. `*` is a wildcard, and case never matters. |
| `CRV <field>=<value> <GO>` | Lists by field value. Compare with `<` and `>`, combine with `and`, sort with `order by <field> desc`. |
| `CRV <GO>` | Lists every interest rate curve. |
| `FORM market-data <GO>` | The pack's search form: boxes for the key fields of each kind instead of a written search. |
| `MKT <GO>` | The pack's overview: its kinds, their mnemonics and how many documents each holds. |

### Where the data comes from

Each kind is read from the Delta Lake folder of its **data domain** (`data/delta/demo/<domain>/<kind>/`, partitioned by business date). Pick a date in the top bar to see that day; **Live** reads the streaming source first.

| Connector | Folder | Domain | Tables | Kinds |
|---|---|---|---|---|
| `market-store` | `data/delta/demo/market/` | Market data: curves, surfaces, prices, fixings | snapshot (every business date) | `ir-curve`, `repo-curve`, `fx-spot`, `fx-forward-curve`, `fx-vol-surface`, `ir-vol-cube`, `cap-vol-surface`, `equity`, `equity-index`, `dividend-curve`, `equity-vol-surface`, `credit-curve`, `inflation-index`, `inflation-curve`, `commodity`, `commodity-curve`, `commodity-vol-surface`, `rate-fixing`, `bond`, `correlation-matrix` |

#### Public data feeds

Real market data from public sources, each its own connector and **off by default**. Switch one on with its environment variable (or `enabled: true` in the connector's file). Feed data has its own identifiers, so it never collides with the bank's own, and it keeps its history, so a picked date shows that day's value.

| Connector | Switch | Kind | What |
|---|---|---|---|
| `nyfed-sofr-feed` | `DRISHTI_FEED_NYFED_SOFR=true` | `rate-fixing` | SOFR from the New York Fed: FIX-SOFR-NYFED |
| `ecb-estr-feed` | `DRISHTI_FEED_ECB_ESTR=true` | `rate-fixing` | Euro short-term rate from the ECB: FIX-ESTR-ECB |
| `ecb-fx-feed` | `DRISHTI_FEED_ECB_FX=true` | `fx-spot` | ECB euro reference rates, with USD crosses: FX-EURUSD-ECB, FX-USDJPY-ECB, … |
| `us-treasury-feed` | `DRISHTI_FEED_US_TREASURY=true` | `ir-curve` | US Treasury daily par yield curve: CRV-USD-UST |
| `fred-feed` | `DRISHTI_FEED_FRED=true` | `rate-fixing` | FRED series (needs FRED_API_KEY): FIX-FRED-DGS10, FIX-FRED-DFF |

Load real data into each domain's folder, or point a domain at a database instead by overriding its connector in the site configuration. For a demonstration lake built from the generated samples, with the Python you run Drishti with (Windows `py -3.13`), run `python3 tools/packgen/banking/make_data.py --lake data/delta/demo`: it writes the history as JSON Lines rows (`data/build/lake-rows`), then loads them (generating and loading are separate steps, [Data formats](DATA_GUIDE.md#2-data-formats-json-lines-is-the-standard)).

### Kinds

| Kind | Name | Mnemonic | Identifiers | Id field | What it is |
|---|---|---|---|---|---|
| `ir-curve` | Interest rate curve | `CRV` | `CRV-…` | `CURVE_ID` | Discounting (OIS), projection (IBOR/term RFR), government or basis curve for a currency. |
| `repo-curve` | Repo curve | `REPO` | `REPO-…` | `CURVE_ID` | General-collateral or specials repo rates by term. |
| `fx-spot` | FX spot rate | `FX` | `FX-…` | `PAIR` | Spot rate for a currency pair with bid/ask. |
| `fx-forward-curve` | FX forward curve | `FXF` | `FXF-…` | `CURVE_ID` | Forward points by tenor for a pair. |
| `fx-vol-surface` | FX volatility surface | `FXV` | `FXV-…` | `SURFACE_ID` | Implied vols by expiry and delta (ATM, 25D RR/BF). |
| `ir-vol-cube` | Swaption volatility cube | `IRV` | `IRV-…` | `CUBE_ID` | Normal (bp) swaption vols by expiry × tenor. |
| `cap-vol-surface` | Cap/floor volatility surface | `CPV` | `CPV-…` | `SURFACE_ID` | Cap vols by maturity and strike. |
| `equity` | Equity | `EQ` | `EQ-…` | `TICKER` | A listed stock: price, dividends, sector and identifiers. |
| `equity-index` | Equity index | `EQX` | `EQX-…` | `INDEX_ID` | Index level with its largest constituents. |
| `dividend-curve` | Dividend curve | `DIV` | `DIV-…` | `CURVE_ID` | Expected dividends by year (implied from dividend futures and forwards). |
| `equity-vol-surface` | Equity volatility surface | `EQV` | `EQV-…` | `SURFACE_ID` | Implied vols by expiry and moneyness. |
| `credit-curve` | Credit curve | `CDS` | `CDS-…` | `CURVE_ID` | CDS par spreads by tenor, recovery and implied default probabilities. |
| `inflation-index` | Inflation index | `INF` | `INF-…` | `INDEX_ID` | Published CPI fixings (US CPI-U NSA, HICPxT, UK RPI…). |
| `inflation-curve` | Inflation curve | `INFC` | `INFC-…` | `CURVE_ID` | Zero-coupon breakevens by tenor, with monthly seasonality. |
| `commodity` | Commodity | `CMD` | `CMD-…` | `COMMODITY_ID` | A traded commodity with its benchmark, unit and venue. |
| `commodity-curve` | Commodity forward curve | `CMDC` | `CMDC-…` | `CURVE_ID` | Futures settlement prices by delivery month. |
| `commodity-vol-surface` | Commodity volatility surface | `CMDV` | `CMDV-…` | `SURFACE_ID` | Implied vols by delivery month and moneyness. |
| `rate-fixing` | Rate index fixings | `FIX` | `FIX-…` | `INDEX_ID` | Published overnight and term rate fixings (SOFR, €STR, SONIA…). |
| `bond` | Bond (security master) | `BND` | `BND-…` | `ISIN` | A listed bond: terms, price, yield, spreads and analytics. |
| `correlation-matrix` | Correlation matrix | `CORR` | `CORR-…` | `MATRIX_ID` | A set of factors whose pairwise correlations basket, quanto and spread products use: the correlations are computed from the factors' price history when asked (since 2.9.2). |

#### Interest rate curve (`ir-curve`)

Discounting (OIS), projection (IBOR/term RFR), government or basis curve for a currency.

**Fields its Sutra reads:** `CURRENCY`, `CURVE_TYPE`, `INDEX`, `TEN_Y`, `SLOPE_2S_10S`, `POINTS`, `AS_OF`, `FORWARDS`.

**Links:** `CALENDAR` → Calendar (`calendar`), `FIXING_INDEX` → Fixing index (`rate-fixing`).

#### Repo curve (`repo-curve`)

General-collateral or specials repo rates by term.

**Fields its Sutra reads:** `CURRENCY`, `COLLATERAL`, `OVERNIGHT`, `AS_OF`, `POINTS`.

#### FX spot rate (`fx-spot`)

Spot rate for a currency pair with bid/ask.

**Fields its Sutra reads:** `PAIR`, `MID`, `BID`, `ASK`, `CHANGE_1D`, `SPOT_DATE`, `HISTORY`, `CONVENTIONS`.

**Links:** `FX_FORWARD_CURVE` → Forward curve (`fx-forward-curve`), `FX_VOL_SURFACE` → Vol surface (`fx-vol-surface`).

#### FX forward curve (`fx-forward-curve`)

Forward points by tenor for a pair.

**Fields its Sutra reads:** `PAIR`, `SPOT`, `POINTS_3M`, `POINTS_1Y`, `POINTS`.

**Links:** `FX_SPOT` → Spot (`fx-spot`).

#### FX volatility surface (`fx-vol-surface`)

Implied vols by expiry and delta (ATM, 25D RR/BF).

**Fields its Sutra reads:** `PAIR`, `ATM_1M`, `ATM_1Y`, `RR_1Y`, `GRID`.

**Links:** `FX_SPOT` → Spot (`fx-spot`).

#### Swaption volatility cube (`ir-vol-cube`)

Normal (bp) swaption vols by expiry × tenor.

**Fields its Sutra reads:** `CURRENCY`, `MODEL`, `V_1Y_10Y`, `V_5Y_5Y`, `GRID`.

#### Cap/floor volatility surface (`cap-vol-surface`)

Cap vols by maturity and strike.

**Fields its Sutra reads:** `CURRENCY`, `ATM_5Y`, `GRID`.

#### Equity (`equity`)

A listed stock: price, dividends, sector and identifiers.

**Fields its Sutra reads:** `NAME`, `PRICE`, `CHANGE_1D`, `MARKET_CAP`, `SECTOR`, `EXCHANGE`, `CURRENCY`, `HISTORY`, `IDENTIFIERS`, `OHLC`.

**Links:** `ISSUER` → Issuer (`issuer`), `EQUITY_VOL_SURFACE` → Vol surface (`equity-vol-surface`), `DIVIDEND_CURVE` → Dividends (`dividend-curve`), `EQUITY_INDEX` → Index (`equity-index`).

#### Equity index (`equity-index`)

Index level with its largest constituents.

**Fields its Sutra reads:** `NAME`, `LEVEL`, `CHANGE_1D`, `CONSTITUENT_COUNT`, `CURRENCY`, `CONSTITUENTS`, `HISTORY`.

**Links:** `EQUITY_VOL_SURFACE` → Vol surface (`equity-vol-surface`), `DIVIDEND_CURVE` → Dividends (`dividend-curve`).

#### Dividend curve (`dividend-curve`)

Expected dividends by year (implied from dividend futures and forwards).

**Fields its Sutra reads:** `UNDERLIER`, `DIVIDEND_YIELD`, `POINTS`.

**Links:** `UNDERLYING_EQUITY` → Underlier (`equity`), `UNDERLYING_INDEX` → Underlier (`equity-index`).

#### Equity volatility surface (`equity-vol-surface`)

Implied vols by expiry and moneyness.

**Fields its Sutra reads:** `UNDERLIER`, `ATM_1M`, `ATM_1Y`, `SKEW_1Y`, `GRID`.

#### Credit curve (`credit-curve`)

CDS par spreads by tenor, recovery and implied default probabilities.

**Fields its Sutra reads:** `ISSUER_NAME`, `SPREAD_5Y`, `RECOVERY`, `SENIORITY`, `DOC_CLAUSE`, `PD_1Y`, `POINTS`.

**Links:** `ISSUER` → Reference entity (`issuer`).

#### Inflation index (`inflation-index`)

Published CPI fixings (US CPI-U NSA, HICPxT, UK RPI…).

**Fields its Sutra reads:** `NAME`, `LATEST`, `YOY`, `LAG`, `FIXINGS`.

**Links:** `INFLATION_CURVE` → Inflation curve (`inflation-curve`).

#### Inflation curve (`inflation-curve`)

Zero-coupon breakevens by tenor, with monthly seasonality.

**Fields its Sutra reads:** `INDEX`, `BE_5Y`, `BE_10Y`, `POINTS`, `SEASONALITY`.

**Links:** `INFLATION_INDEX` → Index (`inflation-index`).

#### Commodity (`commodity`)

A traded commodity with its benchmark, unit and venue.

**Fields its Sutra reads:** `NAME`, `FRONT`, `UNIT`, `EXCHANGE`, `SECTOR`, `SPEC`, `OHLC`.

**Links:** `COMMODITY_CURVE` → Forward curve (`commodity-curve`), `COMMODITY_VOL_SURFACE` → Vol surface (`commodity-vol-surface`).

#### Commodity forward curve (`commodity-curve`)

Futures settlement prices by delivery month.

**Fields its Sutra reads:** `COMMODITY`, `FRONT`, `SHAPE`, `ROLL`, `POINTS`.

**Links:** `COMMODITY_REF` → Commodity (`commodity`).

#### Commodity volatility surface (`commodity-vol-surface`)

Implied vols by delivery month and moneyness.

**Fields its Sutra reads:** `COMMODITY`, `ATM_FRONT`, `GRID`.

#### Rate index fixings (`rate-fixing`)

Published overnight and term rate fixings (SOFR, €STR, SONIA…).

**Fields its Sutra reads:** `NAME`, `LATEST`, `ADMINISTRATOR`, `TENOR`, `FIXINGS`.

#### Bond (security master) (`bond`)

A listed bond: terms, price, yield, spreads and analytics.

**Fields its Sutra reads:** `ISSUER_NAME`, `COUPON`, `MATURITY`, `PRICE`, `YIELD`, `Z_SPREAD`, `MOD_DURATION`, `RATING`, `TERMS`, `COUPONS`, `HISTORY`.

**Links:** `ISSUER` → Issuer (`issuer`), `BENCHMARK_CURVE` → Benchmark curve (`ir-curve`).

#### Correlation matrix (`correlation-matrix`)

A set of factors whose pairwise correlations basket, quanto and spread products use: the correlations are computed from the factors' price history when asked (since 2.9.2).

**Fields its Sutra reads:** `NAME`, `FACTORS`.

**Links:** `EQUITY_IDS` → Equity (`equity`), `FX_IDS` → FX spot (`fx-spot`).

### Who can see it

No extra roles: any user assigned the pack (or a pack that extends it) can open its kinds.

### Data retrieval

Where each kind's data comes from, how it reaches the lake and how to check it. Three origins: **external** data, fetched from a public source by its data feed (each source, its licence and worked examples on the [External data](WORLD_DATA_GUIDE.md) help page); **synthetic** test data (or hand-written reference data), written by a generator with fictitious names; **computed** by a Drishti engine when it is asked, never stored by a source.

| Command | Kind | Origin | How it reaches Drishti |
|---|---|---|---|
| `CRV` | `ir-curve` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/); official public data optional: US Treasury daily par yield curve (public domain (US Government work)) (`drishti.py feed run market-data`, to the data folder; read live by the feed connectors, off as shipped) | `data/testdata/market-data/ir-curve/` → `lake demo` → connector `market-store` |
| `REPO` | `repo-curve` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/market-data/repo-curve/` → `lake demo` → connector `market-store` |
| `FX` | `fx-spot` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/); official public data optional: ECB euro reference FX rates (ECB: free reuse with the source acknowledged) (`drishti.py feed run market-data`, to the data folder; read live by the feed connectors, off as shipped) | `data/testdata/market-data/fx-spot/` → `lake demo` → connector `market-store` |
| `FXF` | `fx-forward-curve` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/market-data/fx-forward-curve/` → `lake demo` → connector `market-store` |
| `FXV` | `fx-vol-surface` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/market-data/fx-vol-surface/` → `lake demo` → connector `market-store` |
| `IRV` | `ir-vol-cube` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/market-data/ir-vol-cube/` → `lake demo` → connector `market-store` |
| `CPV` | `cap-vol-surface` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/market-data/cap-vol-surface/` → `lake demo` → connector `market-store` |
| `EQ` | `equity` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/market-data/equity/` → `lake demo` → connector `market-store` |
| `EQX` | `equity-index` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/market-data/equity-index/` → `lake demo` → connector `market-store` |
| `DIV` | `dividend-curve` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/market-data/dividend-curve/` → `lake demo` → connector `market-store` |
| `EQV` | `equity-vol-surface` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/market-data/equity-vol-surface/` → `lake demo` → connector `market-store` |
| `CDS` | `credit-curve` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/market-data/credit-curve/` → `lake demo` → connector `market-store` |
| `INF` | `inflation-index` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/market-data/inflation-index/` → `lake demo` → connector `market-store` |
| `INFC` | `inflation-curve` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/market-data/inflation-curve/` → `lake demo` → connector `market-store` |
| `CMD` | `commodity` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/market-data/commodity/` → `lake demo` → connector `market-store` |
| `CMDC` | `commodity-curve` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/market-data/commodity-curve/` → `lake demo` → connector `market-store` |
| `CMDV` | `commodity-vol-surface` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/market-data/commodity-vol-surface/` → `lake demo` → connector `market-store` |
| `FIX` | `rate-fixing` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/); official public data optional: SOFR, Federal Reserve Bank of New York (NY Fed terms of use: reuse with attribution); Euro short-term rate (ECB) (ECB: free reuse with the source acknowledged) (`drishti.py feed run market-data`, to the data folder; read live by the feed connectors, off as shipped) | `data/testdata/market-data/rate-fixing/` → `lake demo` → connector `market-store` |
| `BND` | `bond` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/market-data/bond/` → `lake demo` → connector `market-store` |
| `CORR` | `correlation-matrix` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/market-data/correlation-matrix/` → `lake demo` → connector `market-store` |
| `CORRC` | `correlation-result` | computed by Drishti: the reactive connector correlation-engine (java engine com.ash.drishti.banking.compute.analytics.CorrelationEngine); see the [Groupings and analytics guide](bi/README.md) | nothing to load: computed from its inputs when asked |
| `EQB` | `equity-beta` | computed by Drishti: the reactive connector equity-analytics (java engine com.ash.drishti.banking.compute.analytics.BetaEngine); see the [Groupings and analytics guide](bi/README.md) | nothing to load: computed from its inputs when asked |

#### External sources

| Source | Where | Licence | Internal only | Refresh |
|---|---|---|---|---|
| US Treasury daily par yield curve | https://home.treasury.gov/resource-center/data-chart-center/interest-rates | public domain (US Government work) | no | `python3 tools/drishti.py feed run market-data -- us-treasury` |
| ECB euro reference FX rates | https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist.zip | ECB: free reuse with the source acknowledged | no | `python3 tools/drishti.py feed run market-data -- ecb-fx` |
| SOFR, Federal Reserve Bank of New York | https://markets.newyorkfed.org/api/rates/secured/sofr | NY Fed terms of use: reuse with attribution | no | `python3 tools/drishti.py feed run market-data -- sofr` |
| Euro short-term rate (ECB) | https://data-api.ecb.europa.eu/service/data/EST | ECB: free reuse with the source acknowledged | no | `python3 tools/drishti.py feed run market-data -- estr` |

Run these from the Drishti folder (the repository root, or the install folder of a release).

```bash
python3 tools/drishti.py feed status market-data                                         # its last runs: last success, rows, failures (offline: the run ledger)
python3 tools/drishti.py feed run market-data --dry-run                                  # what would be fetched and loaded; changes nothing
python3 tools/drishti.py feed run market-data                                            # pull today's data and load it into the lake `capitalmarket` (what the daily schedule runs)
python3 tools/drishti.py feed run market-data -- ecb-fx --years 5 --scope USDMXN,EURSEK  # more history and more pairs
```

The same with the Python you run Drishti with: `python3 tools/drishti.py feed run market-data` on Linux and macOS, `py -3.13 tools\drishti.py feed run market-data` on Windows (every argument is passed on); the daily schedule entries: `drishti.py feed schedule --cron` (or `--systemd`, `--windows`) ([WORLD_DATA_GUIDE.md](WORLD_DATA_GUIDE.md)).

#### Generators

| Generator | Writes | Check |
|---|---|---|
| `python3 tools/testdata/make_testdata.py` | sixty business days of every dataset of the seven packs into data/testdata/<pack>/<kind>/ (the universe: tools/testdata/universe.py; deterministic, fictitious names) | `python3 tools/testdata/make_testdata.py --check` |
| `python3 tools/packgen/banking/make_data.py` | the pack's few reference documents in samples/ (from the taxonomy, tools/packgen/banking/taxonomy.py) | `python3 tools/packgen/banking/make_data.py --check` |

#### Into the lake, checking, offline

- **The lake.** `python3 tools/drishti.py lake demo` reads `data/testdata/testdata.yaml` and writes every dataset (test data, the packs' samples and the committed public-data snapshots) into `<data>/delta/<domain>/<kind>/`, where the pack's Delta connector serves it; a computed kind needs nothing loaded. `lake build` loads your own large JSON exports the same way ([Command line](CLI_REFERENCE.md)).
- **Checking.** `python3 tools/contracts.py check` compares every contract with the pack and its data; each generator's `--check` (above) fails when the committed files differ from what it would write; in the console, `<MNEMONIC> <GO>` lists a kind and the pack's code opens its overview with the count of each kind.
- **Offline.** Everything committed works without a network: the test data, the samples and the public-data snapshots are in the repository and `lake demo` reads only them. A data feed runs offline from its download cache (`--offline`); a connector that reaches the internet (news, yfinance, the public-data feeds) is off as shipped and never starts on a machine configured offline (`drishti.offline.plugins`).

## Market-data catalogue

The market-data kinds and how trades use them. Trades link to market data through their market-data fields (`discountCurve`, `fxVolSurface`, …), which is how F8 impact on a curve finds every trade valued with it. `<mnemonic> <GO>` lists every object of a kind, and `<mnemonic> <id> <GO>` opens one.

### Interest rate curve (`ir-curve`)

Discounting (OIS), projection (IBOR/term RFR), government or basis curve for a currency.

**Mnemonic:** `CRV` · **Identifiers:** `CRV-…` · **Id field:** `CURVE_ID`

**Fields its Sutra reads:** `CURRENCY`, `CURVE_TYPE`, `INDEX`, `TEN_Y`, `SLOPE_2S_10S`, `POINTS`, `AS_OF`, `FORWARDS`.

### Repo curve (`repo-curve`)

General-collateral or specials repo rates by term.

**Mnemonic:** `REPO` · **Identifiers:** `REPO-…` · **Id field:** `CURVE_ID`

**Fields its Sutra reads:** `CURRENCY`, `COLLATERAL`, `OVERNIGHT`, `AS_OF`, `POINTS`.

### FX spot rate (`fx-spot`)

Spot rate for a currency pair with bid/ask.

**Mnemonic:** `FX` · **Identifiers:** `FX-…` · **Id field:** `PAIR`

**Fields its Sutra reads:** `PAIR`, `MID`, `BID`, `ASK`, `CHANGE_1D`, `SPOT_DATE`, `HISTORY`, `CONVENTIONS`.

### FX forward curve (`fx-forward-curve`)

Forward points by tenor for a pair.

**Mnemonic:** `FXF` · **Identifiers:** `FXF-…` · **Id field:** `CURVE_ID`

**Fields its Sutra reads:** `PAIR`, `SPOT`, `POINTS_3M`, `POINTS_1Y`, `POINTS`.

### FX volatility surface (`fx-vol-surface`)

Implied vols by expiry and delta (ATM, 25D RR/BF).

**Mnemonic:** `FXV` · **Identifiers:** `FXV-…` · **Id field:** `SURFACE_ID`

**Fields its Sutra reads:** `PAIR`, `ATM_1M`, `ATM_1Y`, `RR_1Y`, `GRID`.

### Swaption volatility cube (`ir-vol-cube`)

Normal (bp) swaption vols by expiry × tenor.

**Mnemonic:** `IRV` · **Identifiers:** `IRV-…` · **Id field:** `CUBE_ID`

**Fields its Sutra reads:** `CURRENCY`, `MODEL`, `V_1Y_10Y`, `V_5Y_5Y`, `GRID`.

### Cap/floor volatility surface (`cap-vol-surface`)

Cap vols by maturity and strike.

**Mnemonic:** `CPV` · **Identifiers:** `CPV-…` · **Id field:** `SURFACE_ID`

**Fields its Sutra reads:** `CURRENCY`, `ATM_5Y`, `GRID`.

### Equity (`equity`)

A listed stock: price, dividends, sector and identifiers.

**Mnemonic:** `EQ` · **Identifiers:** `EQ-…` · **Id field:** `TICKER`

**Fields its Sutra reads:** `NAME`, `PRICE`, `CHANGE_1D`, `MARKET_CAP`, `SECTOR`, `EXCHANGE`, `CURRENCY`, `HISTORY`, `IDENTIFIERS`, `OHLC`.

### Equity index (`equity-index`)

Index level with its largest constituents.

**Mnemonic:** `EQX` · **Identifiers:** `EQX-…` · **Id field:** `INDEX_ID`

**Fields its Sutra reads:** `NAME`, `LEVEL`, `CHANGE_1D`, `CONSTITUENT_COUNT`, `CURRENCY`, `CONSTITUENTS`, `HISTORY`.

### Dividend curve (`dividend-curve`)

Expected dividends by year (implied from dividend futures and forwards).

**Mnemonic:** `DIV` · **Identifiers:** `DIV-…` · **Id field:** `CURVE_ID`

**Fields its Sutra reads:** `UNDERLIER`, `DIVIDEND_YIELD`, `POINTS`.

### Equity volatility surface (`equity-vol-surface`)

Implied vols by expiry and moneyness.

**Mnemonic:** `EQV` · **Identifiers:** `EQV-…` · **Id field:** `SURFACE_ID`

**Fields its Sutra reads:** `UNDERLIER`, `ATM_1M`, `ATM_1Y`, `SKEW_1Y`, `GRID`.

### Credit curve (`credit-curve`)

CDS par spreads by tenor, recovery and implied default probabilities.

**Mnemonic:** `CDS` · **Identifiers:** `CDS-…` · **Id field:** `CURVE_ID`

**Fields its Sutra reads:** `ISSUER_NAME`, `SPREAD_5Y`, `RECOVERY`, `SENIORITY`, `DOC_CLAUSE`, `PD_1Y`, `POINTS`.

### Inflation index (`inflation-index`)

Published CPI fixings (US CPI-U NSA, HICPxT, UK RPI…).

**Mnemonic:** `INF` · **Identifiers:** `INF-…` · **Id field:** `INDEX_ID`

**Fields its Sutra reads:** `NAME`, `LATEST`, `YOY`, `LAG`, `FIXINGS`.

### Inflation curve (`inflation-curve`)

Zero-coupon breakevens by tenor, with monthly seasonality.

**Mnemonic:** `INFC` · **Identifiers:** `INFC-…` · **Id field:** `CURVE_ID`

**Fields its Sutra reads:** `INDEX`, `BE_5Y`, `BE_10Y`, `POINTS`, `SEASONALITY`.

### Commodity (`commodity`)

A traded commodity with its benchmark, unit and venue.

**Mnemonic:** `CMD` · **Identifiers:** `CMD-…` · **Id field:** `COMMODITY_ID`

**Fields its Sutra reads:** `NAME`, `FRONT`, `UNIT`, `EXCHANGE`, `SECTOR`, `SPEC`, `OHLC`.

### Commodity forward curve (`commodity-curve`)

Futures settlement prices by delivery month.

**Mnemonic:** `CMDC` · **Identifiers:** `CMDC-…` · **Id field:** `CURVE_ID`

**Fields its Sutra reads:** `COMMODITY`, `FRONT`, `SHAPE`, `ROLL`, `POINTS`.

### Commodity volatility surface (`commodity-vol-surface`)

Implied vols by delivery month and moneyness.

**Mnemonic:** `CMDV` · **Identifiers:** `CMDV-…` · **Id field:** `SURFACE_ID`

**Fields its Sutra reads:** `COMMODITY`, `ATM_FRONT`, `GRID`.

### Rate index fixings (`rate-fixing`)

Published overnight and term rate fixings (SOFR, €STR, SONIA…).

**Mnemonic:** `FIX` · **Identifiers:** `FIX-…` · **Id field:** `INDEX_ID`

**Fields its Sutra reads:** `NAME`, `LATEST`, `ADMINISTRATOR`, `TENOR`, `FIXINGS`.

### Bond (security master) (`bond`)

A listed bond: terms, price, yield, spreads and analytics.

**Mnemonic:** `BND` · **Identifiers:** `BND-…` · **Id field:** `ISIN`

**Fields its Sutra reads:** `ISSUER_NAME`, `COUPON`, `MATURITY`, `PRICE`, `YIELD`, `Z_SPREAD`, `MOD_DURATION`, `RATING`, `TERMS`, `COUPONS`, `HISTORY`.

### Correlation matrix (`correlation-matrix`)

A set of factors whose pairwise correlations basket, quanto and spread products use: the correlations are computed from the factors' price history when asked (since 2.9.2).

**Mnemonic:** `CORR` · **Identifiers:** `CORR-…` · **Id field:** `MATRIX_ID`

**Fields its Sutra reads:** `NAME`, `FACTORS`.

### How products select market data

| Trade field | Kind | Selected by |
|---|---|---|
| `basisCurve` | [`ir-curve`](../contracts/samples/market-data.md#ir-curve) ([contract](../contracts/packs/market-data.md#crv-interest-rate-curve-ir-curve)) | `basis:{ccy}` |
| `benchmarkCurve` | `ir-curve` | `govt:{ccy}` |
| `capVolSurface` | [`cap-vol-surface`](../contracts/samples/market-data.md#cap-vol-surface) ([contract](../contracts/packs/market-data.md#cpv-capfloor-volatility-surface-cap-vol-surface)) | `cap:{ccy}` |
| `commodityCurve` | [`commodity-curve`](../contracts/samples/market-data.md#commodity-curve) ([contract](../contracts/packs/market-data.md#cmdc-commodity-forward-curve-commodity-curve)) | `{commodity}` |
| `commodityVolSurface` | [`commodity-vol-surface`](../contracts/samples/market-data.md#commodity-vol-surface) ([contract](../contracts/packs/market-data.md#cmdv-commodity-volatility-surface-commodity-vol-surface)) | `{commodity}` |
| `correlation` | [`correlation-matrix`](../contracts/samples/market-data.md#correlation-matrix) ([contract](../contracts/packs/market-data.md#corr-correlation-matrix-correlation-matrix)) | `{corr}` |
| `creditCurve` | [`credit-curve`](../contracts/samples/market-data.md#credit-curve) ([contract](../contracts/packs/market-data.md#cds-credit-curve-credit-curve)) | `{issuer}` |
| `discountCurve` | `ir-curve` | `ois:{ccy}` |
| `dividendCurve` | [`dividend-curve`](../contracts/samples/market-data.md#dividend-curve) ([contract](../contracts/packs/market-data.md#div-dividend-curve-dividend-curve)) | `{equity}` |
| `equityVolSurface` | [`equity-vol-surface`](../contracts/samples/market-data.md#equity-vol-surface) ([contract](../contracts/packs/market-data.md#eqv-equity-volatility-surface-equity-vol-surface)) | `{equity}` |
| `equityVolSurface` | `equity-vol-surface` | `{index}` |
| `fixingIndex` | [`rate-fixing`](../contracts/samples/market-data.md#rate-fixing) ([contract](../contracts/packs/market-data.md#fix-rate-index-fixings-rate-fixing)) | `rfr:{ccy}` |
| `forwardCurve` | `ir-curve` | `proj:{ccy}` |
| `fxForwardCurve` | [`fx-forward-curve`](../contracts/samples/market-data.md#fx-forward-curve) ([contract](../contracts/packs/market-data.md#fxf-fx-forward-curve-fx-forward-curve)) | `{pair}` |
| `fxSpot` | [`fx-spot`](../contracts/samples/market-data.md#fx-spot) ([contract](../contracts/packs/market-data.md#fx-fx-spot-rate-fx-spot)) | `{pair}` |
| `fxVolSurface` | [`fx-vol-surface`](../contracts/samples/market-data.md#fx-vol-surface) ([contract](../contracts/packs/market-data.md#fxv-fx-volatility-surface-fx-vol-surface)) | `{pair}` |
| `inflationCurve` | [`inflation-curve`](../contracts/samples/market-data.md#inflation-curve) ([contract](../contracts/packs/market-data.md#infc-inflation-curve-inflation-curve)) | `{infl}` |
| `inflationIndex` | [`inflation-index`](../contracts/samples/market-data.md#inflation-index) ([contract](../contracts/packs/market-data.md#inf-inflation-index-inflation-index)) | `{infl}` |
| `repoCurve` | [`repo-curve`](../contracts/samples/market-data.md#repo-curve) ([contract](../contracts/packs/market-data.md#repo-repo-curve-repo-curve)) | `{ccy}` |
| `swaptionVolCube` | [`ir-vol-cube`](../contracts/samples/market-data.md#ir-vol-cube) ([contract](../contracts/packs/market-data.md#irv-swaption-volatility-cube-ir-vol-cube)) | `swaption:{ccy}` |
| `underlyingBond` | `bond` | `{bond}` |
| `underlyingEquity` | `equity` | `{equity}` |
| `underlyingIndex` | [`equity-index`](../contracts/samples/market-data.md#equity-index) ([contract](../contracts/packs/market-data.md#eqx-equity-index-equity-index)) | `{index}` |

## Core pack

The parties and organisation every pack builds on: counterparties (with country of risk and UEN), groups, issuers, CCPs, legal entities, books and portfolios, desks, traders and holiday calendars.

**Extends:** nothing (it is the base) · **Extended by:** `market-data`, `trading` · **Kinds:** 10

Enable it with `DRISHTI_PACKS=core` (the packs it extends come with it; where they differ, the more specific wins).

### Finding things

Every kind has a mnemonic (see *Kinds*). Type it with an identifier, the start of one, or a search, then `<GO>`. Shown here for counterparties; the same works for every mnemonic.

| Command | Does |
|---|---|
| `CPTY <id> <GO>` | Opens that counterparty. A bare identifier works too: its prefix (`CP-…`) tells Drishti the kind. |
| `CPTY <start of an id> <GO>` | A pick list: one match opens, several give a table with the kind's key fields. `*` is a wildcard, and case never matters. |
| `CPTY <field>=<value> <GO>` | Lists by field value. Compare with `<` and `>`, combine with `and`, sort with `order by <field> desc`. |
| `CPTY <GO>` | Lists every counterparty. |
| `FORM core <GO>` | The pack's search form: boxes for the key fields of each kind instead of a written search. |
| `CORE <GO>` | The pack's overview: its kinds, their mnemonics and how many documents each holds. |

### Where the data comes from

Each kind is read from the Delta Lake folder of its **data domain** (`data/delta/demo/<domain>/<kind>/`, partitioned by business date). Pick a date in the top bar to see that day; **Live** reads the streaming source first.

| Connector | Folder | Domain | Tables | Kinds |
|---|---|---|---|---|
| `reference-store` | `data/delta/demo/reference/` | Reference data: parties, organisation, calendars | effective (a row when an entity changes) | `counterparty`, `counterparty-group`, `issuer`, `ccp`, `legal-entity`, `book`, `desk`, `trader`, `calendar`, `clearing-account` |

Load real data into each domain's folder, or point a domain at a database instead by overriding its connector in the site configuration. For a demonstration lake built from the generated samples, with the Python you run Drishti with (Windows `py -3.13`), run `python3 tools/packgen/banking/make_data.py --lake data/delta/demo`: it writes the history as JSON Lines rows (`data/build/lake-rows`), then loads them (generating and loading are separate steps, [Data formats](DATA_GUIDE.md#2-data-formats-json-lines-is-the-standard)).

### Kinds

| Kind | Name | Mnemonic | Identifiers | Id field | What it is |
|---|---|---|---|---|---|
| `counterparty` | Counterparty | `CPTY` | `CP-…` | `COUNTERPARTY_ID` | A legal entity the bank trades with: identifiers (LEI, UEN), rating, sector, country of risk and parent group. |
| `counterparty-group` | Counterparty group | `GRP` | `GRP-…` | `GROUP_ID` | Economic group of related counterparties (obligor group). |
| `issuer` | Issuer | `ISS` | `ISS-…` | `ISSUER_ID` | Issuer of bonds and reference entity for CDS. |
| `ccp` | Central counterparty | `CCP` | `CCP-…` | `CCP_ID` | A clearing house with its default fund and margin model. |
| `legal-entity` | Bank legal entity | `LE` | `LE-…` | `ENTITY_ID` | The bank's own booking entity. |
| `book` | Book | `BOOK` | `BOOK-…` | `BOOK_ID` | A trading book: owner desk, trades and risk. |
| `desk` | Desk | `DESK` | `DESK-…` | `DESK_ID` | A trading desk: books, limits and risk results. |
| `trader` | Trader | `TRDR` | `TRDR-…` | `TRADER_ID` | A trader and their books. |
| `calendar` | Holiday calendar | `CAL` | `CAL-…` | `CALENDAR_ID` | Business-day calendar (USNY, TARGET, GBLO, JPTO…). |
| `clearing-account` | Clearing account | `CLR` | `CLR-…` | `ACCOUNT_ID` | An account at a CCP holding cleared trades and margin. |

#### Counterparty (`counterparty`)

A legal entity the bank trades with: identifiers (LEI, UEN), rating, sector, country of risk and parent group.

**Fields its Sutra reads:** `NAME`, `LEI`, `RATING`, `SECTOR`, `COUNTRY_OF_RISK`, `UEN`, `TYPE`, `NET_MTM`, `EXPOSURE_ENGINE`, `NETTING_SETS`, `HIERARCHY`.

**Links:** `GROUP` → Group (`counterparty-group`), `CREDIT_LIMIT` → Credit limit ([`credit-limit`](../contracts/samples/counterparty-risk.md#credit-limit) ([contract](../contracts/packs/counterparty-risk.md#lim-credit-limit-credit-limit))), `CREDIT_CURVE` → Credit curve ([`credit-curve`](../contracts/samples/market-data.md#credit-curve) ([contract](../contracts/packs/market-data.md#cds-credit-curve-credit-curve))).

#### Counterparty group (`counterparty-group`)

Economic group of related counterparties (obligor group).

**Fields its Sutra reads:** `NAME`, `MEMBERS`, `LIMIT`, `UTILISATION`.

#### Issuer (`issuer`)

Issuer of bonds and reference entity for CDS.

**Fields its Sutra reads:** `NAME`, `RATING`, `SECTOR`, `COUNTRY`, `BONDS`.

**Links:** `CREDIT_CURVE` → Credit curve (`credit-curve`), `EQUITY` → Equity (`equity`).

#### Central counterparty (`ccp`)

A clearing house with its default fund and margin model.

**Fields its Sutra reads:** `NAME`, `DEFAULT_FUND`, `IM_POSTED`, `MARGIN_MODEL`, `ACCOUNTS`.

#### Bank legal entity (`legal-entity`)

The bank's own booking entity.

**Fields its Sutra reads:** `NAME`, `LEI`, `JURISDICTION`, `REGULATOR`, `VAR_ENGINE`, `DESKS`.

#### Book (`book`)

A trading book: owner desk, trades and risk.

**Fields its Sutra reads:** `NAME`, `DESK_NAME`, `TRADE_COUNT`, `MTM`, `DV01`, `TOP_TRADES`.

**Links:** `DESK` → Desk (`desk`).

#### Desk (`desk`)

A trading desk: books, limits and risk results.

**Fields its Sutra reads:** `NAME`, `HEAD`, `BOOKS`, `VAR_ENGINE`, `MTM`, `POSITIONS`.

**Links:** `LEGAL_ENTITY` → Legal entity (`legal-entity`).

#### Trader (`trader`)

A trader and their books.

**Fields its Sutra reads:** `NAME`, `DESK_NAME`, `TRADE_COUNT`, `BOOKS`.

**Links:** `DESK` → Desk (`desk`).

#### Holiday calendar (`calendar`)

Business-day calendar (USNY, TARGET, GBLO, JPTO…).

**Fields its Sutra reads:** `NAME`, `HOLIDAYS_2026`, `HOLIDAYS_2027`, `NEXT_HOLIDAY`, `NEXT_HOLIDAY_DATE`, `HOLIDAYS`.

#### Clearing account (`clearing-account`)

An account at a CCP holding cleared trades and margin.

**Fields its Sutra reads:** `CCP_NAME`, `IM`, `VM_TODAY`, `EXCESS`, `TRADES`.

**Links:** `CCP` → CCP (`ccp`).

### Who can see it

No extra roles: any user assigned the pack (or a pack that extends it) can open its kinds.

### Data retrieval

Where each kind's data comes from, how it reaches the lake and how to check it. Three origins: **external** data, fetched from a public source by its data feed (each source, its licence and worked examples on the [External data](WORLD_DATA_GUIDE.md) help page); **synthetic** test data (or hand-written reference data), written by a generator with fictitious names; **computed** by a Drishti engine when it is asked, never stored by a source.

| Command | Kind | Origin | How it reaches Drishti |
|---|---|---|---|
| `CPTY` | `counterparty` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/core/counterparty/` → `lake demo` → connector `reference-store` |
| `GRP` | `counterparty-group` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/core/counterparty-group/` → `lake demo` → connector `reference-store` |
| `ISS` | `issuer` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/core/issuer/` → `lake demo` → connector `reference-store` |
| `CCP` | `ccp` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/core/ccp/` → `lake demo` → connector `reference-store` |
| `LE` | `legal-entity` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/core/legal-entity/` → `lake demo` → connector `reference-store` |
| `BOOK` | `book` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/core/book/` → `lake demo` → connector `reference-store` |
| `DESK` | `desk` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/core/desk/` → `lake demo` → connector `reference-store` |
| `TRDR` | `trader` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/core/trader/` → `lake demo` → connector `reference-store` |
| `CAL` | `calendar` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/core/calendar/` → `lake demo` → connector `reference-store` |
| `CLR` | `clearing-account` | synthetic: `python3 tools/testdata/make_testdata.py` (and `python3 tools/packgen/banking/make_data.py` for samples/) | `data/testdata/core/clearing-account/` → `lake demo` → connector `reference-store` |

#### Generators

| Generator | Writes | Check |
|---|---|---|
| `python3 tools/testdata/make_testdata.py` | sixty business days of every dataset of the seven packs into data/testdata/<pack>/<kind>/ (the universe: tools/testdata/universe.py; deterministic, fictitious names) | `python3 tools/testdata/make_testdata.py --check` |
| `python3 tools/packgen/banking/make_data.py` | the pack's few reference documents in samples/ (from the taxonomy, tools/packgen/banking/taxonomy.py) | `python3 tools/packgen/banking/make_data.py --check` |

#### Into the lake, checking, offline

- **The lake.** `python3 tools/drishti.py lake demo` reads `data/testdata/testdata.yaml` and writes every dataset (test data, the packs' samples and the committed public-data snapshots) into `<data>/delta/<domain>/<kind>/`, where the pack's Delta connector serves it; a computed kind needs nothing loaded. `lake build` loads your own large JSON exports the same way ([Command line](CLI_REFERENCE.md)).
- **Checking.** `python3 tools/contracts.py check` compares every contract with the pack and its data; each generator's `--check` (above) fails when the committed files differ from what it would write; in the console, `<MNEMONIC> <GO>` lists a kind and the pack's code opens its overview with the count of each kind.
- **Offline.** Everything committed works without a network: the test data, the samples and the public-data snapshots are in the repository and `lake demo` reads only them. A data feed runs offline from its download cache (`--offline`); a connector that reaches the internet (news, yfinance, the public-data feeds) is off as shipped and never starts on a machine configured offline (`drishti.offline.plugins`).

## The banking data model

How the five banking packs' entities refer to each other. Every arrow is a reference field in a document, so each is a link in the view, a hop for F7, and a path for F8 impact.

```text
legal entity ─► desk ─► book ─► trade ◄─ trader
                                 │
counterparty ─► agreement ─► CSA  ├─► netting set ─► exposure (computed), CVA, SA-CCR, collateral, margin calls
      │                          │        └─► credit limit (per counterparty), SIMM
      └─► group, credit curve    └─► market data: curves, surfaces, spots, equities, credit, inflation, commodities
desk ─► VaR ─► stress results ◄─ stress scenario        book ─► FRTB sensitivities, P&L explain
```

### Every reference field

| Field | Label | Opens kind | Pack | Data domain |
|---|---|---|---|---|
| `AGREEMENT` | Agreement | `agreement` | `counterparty-risk` | `legal` |
| `BASIS_CURVE` | Basis curve | [`ir-curve`](../contracts/samples/market-data.md#ir-curve) ([contract](../contracts/packs/market-data.md#crv-interest-rate-curve-ir-curve)) | `market-data` | `market` |
| `BENCHMARK_CURVE` | Benchmark curve | `ir-curve` | `market-data` | `market` |
| `BOOK` | Book | `book` | `core` | `reference` |
| `CALENDAR` | Calendar | `calendar` | `core` | `reference` |
| `CAP_VOL_SURFACE` | Cap vol surface | [`cap-vol-surface`](../contracts/samples/market-data.md#cap-vol-surface) ([contract](../contracts/packs/market-data.md#cpv-capfloor-volatility-surface-cap-vol-surface)) | `market-data` | `market` |
| `CCP` | CCP | `ccp` | `core` | `reference` |
| `CLEARING_ACCOUNT` | Clearing account | [`clearing-account`](../contracts/samples/core.md#clearing-account) ([contract](../contracts/packs/core.md#clr-clearing-account-clearing-account)) | `core` | `reference` |
| `COLLATERAL_BALANCE` | Collateral | [`collateral-balance`](../contracts/samples/counterparty-risk.md#collateral-balance) ([contract](../contracts/packs/counterparty-risk.md#coll-collateral-balance-collateral-balance)) | `counterparty-risk` | `collateral` |
| `COLLATERAL_BALANCES` | Collateral | `collateral-balance` | `counterparty-risk` | `collateral` |
| `COMMODITY_CURVE` | Commodity curve | [`commodity-curve`](../contracts/samples/market-data.md#commodity-curve) ([contract](../contracts/packs/market-data.md#cmdc-commodity-forward-curve-commodity-curve)) | `market-data` | `market` |
| `COMMODITY_REF` | Commodity | `commodity` | `market-data` | `market` |
| `COMMODITY_VOL_SURFACE` | Commodity vol surface | [`commodity-vol-surface`](../contracts/samples/market-data.md#commodity-vol-surface) ([contract](../contracts/packs/market-data.md#cmdv-commodity-volatility-surface-commodity-vol-surface)) | `market-data` | `market` |
| `CORRELATION` | Correlation | [`correlation-matrix`](../contracts/samples/market-data.md#correlation-matrix) ([contract](../contracts/packs/market-data.md#corr-correlation-matrix-correlation-matrix)) | `market-data` | `market` |
| `COUNTERPARTY` | Counterparty | `counterparty` | `core` | `reference` |
| `CREDIT_CURVE` | Credit curve | [`credit-curve`](../contracts/samples/market-data.md#credit-curve) ([contract](../contracts/packs/market-data.md#cds-credit-curve-credit-curve)) | `market-data` | `market` |
| `CREDIT_LIMIT` | Credit limit | [`credit-limit`](../contracts/samples/counterparty-risk.md#credit-limit) ([contract](../contracts/packs/counterparty-risk.md#lim-credit-limit-credit-limit)) | `counterparty-risk` | `credit` |
| `CSA` | CSA | `csa` | `counterparty-risk` | `legal` |
| `CSAS` | CSA | `csa` | `counterparty-risk` | `legal` |
| `CVA_RESULT` | CVA | `cva` | `counterparty-risk` | `credit` |
| `DESK` | Desk | `desk` | `core` | `reference` |
| `DISCOUNT_CURVE` | Discount curve | `ir-curve` | `market-data` | `market` |
| `DIVIDEND_CURVE` | Dividend curve | [`dividend-curve`](../contracts/samples/market-data.md#dividend-curve) ([contract](../contracts/packs/market-data.md#div-dividend-curve-dividend-curve)) | `market-data` | `market` |
| `EQUITY` | Equity | `equity` | `market-data` | `market` |
| `EQUITY_IDS` | Equity | `equity` | `market-data` | `market` |
| `EQUITY_INDEX` | Index | [`equity-index`](../contracts/samples/market-data.md#equity-index) ([contract](../contracts/packs/market-data.md#eqx-equity-index-equity-index)) | `market-data` | `market` |
| `EQUITY_VOL_SURFACE` | Equity vol surface | [`equity-vol-surface`](../contracts/samples/market-data.md#equity-vol-surface) ([contract](../contracts/packs/market-data.md#eqv-equity-volatility-surface-equity-vol-surface)) | `market-data` | `market` |
| `FIXING_INDEX` | Fixing index | [`rate-fixing`](../contracts/samples/market-data.md#rate-fixing) ([contract](../contracts/packs/market-data.md#fix-rate-index-fixings-rate-fixing)) | `market-data` | `market` |
| `FORWARD_CURVE` | Forward curve | `ir-curve` | `market-data` | `market` |
| `FX_FORWARD_CURVE` | Fx forward curve | [`fx-forward-curve`](../contracts/samples/market-data.md#fx-forward-curve) ([contract](../contracts/packs/market-data.md#fxf-fx-forward-curve-fx-forward-curve)) | `market-data` | `market` |
| `FX_IDS` | FX spot | [`fx-spot`](../contracts/samples/market-data.md#fx-spot) ([contract](../contracts/packs/market-data.md#fx-fx-spot-rate-fx-spot)) | `market-data` | `market` |
| `FX_SPOT` | Fx spot | `fx-spot` | `market-data` | `market` |
| `FX_VOL_SURFACE` | Fx vol surface | [`fx-vol-surface`](../contracts/samples/market-data.md#fx-vol-surface) ([contract](../contracts/packs/market-data.md#fxv-fx-volatility-surface-fx-vol-surface)) | `market-data` | `market` |
| `GROUP` | Group | [`counterparty-group`](../contracts/samples/core.md#counterparty-group) ([contract](../contracts/packs/core.md#grp-counterparty-group-counterparty-group)) | `core` | `reference` |
| `INFLATION_CURVE` | Inflation curve | [`inflation-curve`](../contracts/samples/market-data.md#inflation-curve) ([contract](../contracts/packs/market-data.md#infc-inflation-curve-inflation-curve)) | `market-data` | `market` |
| `INFLATION_INDEX` | Inflation index | [`inflation-index`](../contracts/samples/market-data.md#inflation-index) ([contract](../contracts/packs/market-data.md#inf-inflation-index-inflation-index)) | `market-data` | `market` |
| `ISSUER` | Reference entity | `issuer` | `core` | `reference` |
| `LEGAL_ENTITY` | Legal entity | [`legal-entity`](../contracts/samples/core.md#legal-entity) ([contract](../contracts/packs/core.md#le-bank-legal-entity-legal-entity)) | `core` | `reference` |
| `NETTING_SET` | Netting set | [`netting-set`](../contracts/samples/counterparty-risk.md#netting-set) ([contract](../contracts/packs/counterparty-risk.md#nset-netting-set-netting-set)) | `counterparty-risk` | `credit` |
| `NETTING_SETS` | Netting set | `netting-set` | `counterparty-risk` | `credit` |
| `ORIGINAL_COUNTERPARTY` | Original counterparty | `counterparty` | `core` | `reference` |
| `PORTFOLIO` | Portfolio | `book` | `core` | `reference` |
| `REPO_CURVE` | Repo curve | [`repo-curve`](../contracts/samples/market-data.md#repo-curve) ([contract](../contracts/packs/market-data.md#repo-repo-curve-repo-curve)) | `market-data` | `market` |
| `SACCR` | SA-CCR | [`sa-ccr`](../contracts/samples/counterparty-risk.md#sa-ccr) ([contract](../contracts/packs/counterparty-risk.md#saccr-sa-ccr-exposure-sa-ccr)) | `counterparty-risk` | `credit` |
| `SCENARIO` | Scenario | [`stress-scenario`](../contracts/samples/market-risk.md#stress-scenario) ([contract](../contracts/packs/market-risk.md#scn-stress-scenario-stress-scenario)) | `market-risk` | `risk` |
| `SIMM` | SIMM | `simm` | `counterparty-risk` | `collateral` |
| `SWAPTION_VOL_CUBE` | Swaption vol cube | [`ir-vol-cube`](../contracts/samples/market-data.md#ir-vol-cube) ([contract](../contracts/packs/market-data.md#irv-swaption-volatility-cube-ir-vol-cube)) | `market-data` | `market` |
| `TRADER` | Trader | `trader` | `core` | `reference` |
| `TRADER_ID` | Trader | `trader` | `core` | `reference` |
| `TRADE_ID` | Trade | `trade` | `trading` | `trading` |
| `TT_BOOK` | Book | `book` | `core` | `reference` |
| `TT_GROUP` | Desk | `desk` | `core` | `reference` |
| `UNDERLYING_BOND` | Underlying bond | `bond` | `market-data` | `market` |
| `UNDERLYING_EQUITY` | Underlying equity | `equity` | `market-data` | `market` |
| `UNDERLYING_INDEX` | Underlying index | `equity-index` | `market-data` | `market` |

### Packs and data domains

| Pack | Extends | Reads domains |
|---|---|---|
| `core` | — | `reference` |
| `market-data` | `core` | `market` |
| `trading` | `core`, `market-data` | `trading` |
| `market-risk` | `market-data`, `trading` | `risk` |
| `counterparty-risk` | `trading` | `legal`, `credit`, `collateral` |

## Source systems

One module per trading system keeps its trades in one table of their own and maps them to the one trade kind. Endur below is the full procedure; the other five modules follow it step for step and differ only in the table at the end of this section.

### Source Endur

Endur is the commodity and energy trading and risk system. Its trades arrive in the common UPPER_SNAKE trade schema, so the mapping is the identity.

#### What the module ships

| Folder | What |
|---|---|
| `connectors/` | cassandra, delta, delta-s3, files, iceberg, kafka, s3tables: copy one to `config/connectors/` under the name its first line gives |
| `mappings/endur-trade.yaml` | the native fields to the canonical trade fields |
| `dataload.yaml` | the inbox routing rules |

#### Bind it

```yaml
# config/connectors/trade-endur.yaml (from connectors/delta.yaml)
plugin: delta
settings:
  lake: main
  lake-tables: trading.trade_endur          # a table of its own: no where: needed
  set: { SOURCE_SYSTEM: ENDUR }
  map: mappings/endur-trade.yaml
```

and in `config/bindings.yaml` add `trade-endur` to the `trade` kind's `read:` list (with `union: true`); the Kafka template, bound as `live:`,
ticks the blotter and writes edits back.

#### Load a file

The inbox takes `endur-<date>.jsonl.gz` by this module's rule (the site's own rules are read first):

```bash
cp endur-2026-11-03.jsonl.gz data/dataload/inbox/
touch data/dataload/inbox/endur-2026-11-03.jsonl.gz.ready      # the marker goes in last: a file without it is never touched
```

The file is JSON Lines in Endur's own native style; the connector's `set:` stamps `SOURCE_SYSTEM = ENDUR` into each stored row.

### Source Calypso

Calypso is the capital markets trading and risk system. Its trades arrive in the common UPPER_SNAKE trade schema, so the mapping is the identity.

The module `source-calypso` binds as Endur does (`config/connectors/trade-calypso.yaml`, `set: { SOURCE_SYSTEM: CALYPSO }`, mapping `mappings/calypso-trade.yaml`) and takes `calypso-<date>.jsonl.gz` in the inbox.

### Source ICI

ICI is a generic cross-asset booking system. What it trades was not stated, so it is MOCKED as a generic cross-asset booking system (rates, FX, equity, credit and commodity tickets; PascalCase sections Header, Instrument, Parties, Dates; YYYYMMDD dates).

The module `source-ici` binds as Endur does (`config/connectors/trade-ici.yaml`, `set: { SOURCE_SYSTEM: ICI }`, mapping `mappings/ici-trade.yaml`) and takes `ici-<date>.jsonl.gz` in the inbox.

### Source Murex

Murex is the cross-asset trading and risk platform. Its trades arrive in the common UPPER_SNAKE trade schema, so the mapping is the identity.

The module `source-murex` binds as Endur does (`config/connectors/trade-murex.yaml`, `set: { SOURCE_SYSTEM: MUREX }`, mapping `mappings/murex-trade.yaml`) and takes `murex-<date>.jsonl.gz` in the inbox.

### Source SwapOne

SwapOne is an OTC swaps system (mocked here: a synthetic look-alike, snake_case, a swap as a pay leg and a receive leg, epoch-millisecond update times).

The module `source-swapone` binds as Endur does (`config/connectors/trade-swapone.yaml`, `set: { SOURCE_SYSTEM: SWAPONE }`, mapping `mappings/swapone-trade.yaml`) and takes `swapone-<date>.jsonl.gz` in the inbox.

### Source WSS

WSS is a treasury, FX and money-markets dealing system (mocked here: a synthetic look-alike, camelCase, one deal a document, buy and sell legs).

The module `source-wss` binds as Endur does (`config/connectors/trade-wss.yaml`, `set: { SOURCE_SYSTEM: WSS }`, mapping `mappings/wss-trade.yaml`) and takes `wss-<date>.jsonl.gz` in the inbox.
