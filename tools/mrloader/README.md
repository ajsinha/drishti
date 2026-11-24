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
# mrloader: market-risk loaders (the CSV datasets of VaR and exposure, PVF scenario PVs, scenario sets, scenario FX)

## The CSV loader: one kind at a time

`tools/mrloader/csv_load.py` turns one dataset's CSV into checked records of its kind, written as the data inbox and `lake build` take
them; it loads nothing itself. Every kind is a mapping file (`kinds/<kind>.yaml`: the CSV columns named as the contract's fields) and a
generated page (`docs/contracts/CSV_FORMATS.md`); the day's runbook is `tools/mrloader/docs/DAILY_RUNBOOK.md`.

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

## The PVF loader

The loaders are run directly by path from the repository root (the `pvf_*.py` names are the PVF source format; the folder is `tools/mrloader`; the owner's files stay in `data/pvf/`). The PVF loader turns a PVF extract (per-path present values of every position under
each historical-VaR path, with the positions and their portfolios) into the records of the market-risk pack's **scenario-pv** kind, the
form of scenario P&L the VaR engine reads, in the layout Drishti's loaders take.

## The command

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

## Prepare your environment

Python 3.13 (3.11 is the floor `setup` checks) with Polars, NumPy and PyYAML, in the Python you run Drishti with. Install the two pip commands of
[DEPLOYMENT_AND_QA_GUIDE.md section 1.6](../../docs/DEPLOYMENT_AND_QA_GUIDE.md#16-the-settings-file-and-drishtipy-setup), then the Polars extras, the same way
(from `drishti-console`; `--find-links` the wheel pack, or your package index when the pack does not carry them):

```bash
python3 -m pip install -r requirements-polars.txt      # Windows: py -3.13 -m pip install -r requirements-polars.txt
```

No Java, no server and no network are needed to convert; loading uses Drishti's own loader (below).

## The scripts

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

## The inputs

| File | Columns read | Types |
|---|---|---|
| `pvf_path_YYYYMMDD.csv` (the PV file) | `position_id`, `path_num`, `val`, `valid_dt` and the filters `risk_metric_cd` (`PV`), `dataset_cd` (`HistoVAR`), `shock_group_nm` (`ALL`) | id Int64, path Int32, val Float64, the rest text |
| `pos_YYYYMMDD.csv` (positions) | every column; `position_id` and `portfolio_id` required | ids Int64, `position_amt` Float64, the rest text as it is (`trade_id` too) |
| `portfolio_d2.csv` (PORTFOLIO_D2) | every column; `portfolio_id` required; `valid_dt`/`valid_to_dt` when present | `portfolio_id` Int64, the rest text as it is |

Types are explicit, never inferred: a value that is not a number where one is required refuses the run, naming the file.

## The join rules

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

## The mapping, field by field

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

### The mapping grammar

A field's rule, applied in this order: one of `const: V`, `from: COL` (or a list: the first non-empty), `template: "A-{COL}"` (`{COL}` a
joined column, `{$name}` a run value, `{@FIELD}` a field mapped above; empty when any part is empty); then `regex` (the first group),
`map` with `else` (a value not in the map takes `else`; without `else` the row is audited `UNMAPPED_VALUE`), `sign`
(`negative`/`zero`/`positive`), `sign_from` (`column`, the `negative` and `positive` values, optional `only_for` columns of the
`from` list: the magnitude signed by that column), `slug` (upper case, other characters one `-`) or `upper`, `prefix`, `type` (`text`, `number`, `integer`,
`date`, `list`, `vector`; default: the contract's), `default`. A required field that ends empty audits the row (`MISSING_REQUIRED`).

## The scenario set

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

## The outputs

Under `<out>/mr-<date>/`, the folder every kind of the date shares (docs/guides/BANK_DATA_GUIDE.md section 6.4):

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

## Loading into Drishti

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

## Scenario set: add and remove

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

## Scenario FX: add and remove

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

## Every day: set, FX, PVs in order

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

## Performance and memory

- The PV file is scanned once, streaming: memory follows the (position, path) pairs kept (about 32 bytes each: about 1.2 GB for the
  owner's 37 million rows) plus one chunk of the dense matrix (`--chunk` positions x 251 x 8 bytes: 100 MB at 50,000), not the 3.8 GB file.
- The 2,000-position slice of the owner's extract (426,894 path rows): join and export in about 1.2 seconds, peak memory about 400 MB.
- The full extract (200,000 positions): expect a few minutes and a few GB; the summary records the seconds and the peak memory. Keep
  local trials to `--limit 2000`.

## Mapping decisions for the owner

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
