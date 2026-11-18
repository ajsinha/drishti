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
# pvf: a PVF extract to Drishti scenario PVs

A one-off tool, run directly by path from the repository root. It turns a PVF extract (per-path present values of every position under
each historical-VaR path, with the positions and their portfolios) into the records of the market-risk pack's **scenario-pv** kind, the
form of scenario P&L the VaR engine reads, in the layout Drishti's loaders take.

## The command

```bash
python3 tools/pvf/pvf_run.py \
    --pv data/pvf/pvf_path_20261029.csv \
    --portfolio data/pvf/portfolio_d2.csv \
    --positions data/pvf/pos_20261029.csv \
    --date 2026-10-29
```

That one command joins, maps (`tools/pvf/mapping.yaml`), checks every record against the contract, and writes everything under
`data/pvf/pvf-2026-10-29/` (`--out DIR` puts `pvf-<date>/` elsewhere; the default is the PV file's folder). Try it first on a slice and
without writing:

```bash
python3 tools/pvf/pvf_run.py --pv data/pvf/pvf_path_20261029.csv --portfolio data/pvf/portfolio_d2.csv \
    --positions data/pvf/pos_20261029.csv --date 2026-10-29 --dry-run          # check inputs, columns and mapping; write nothing
python3 tools/pvf/pvf_run.py --pv data/pvf/pvf_path_20261029.csv --portfolio data/pvf/portfolio_d2.csv \
    --positions data/pvf/pos_20261029.csv --date 2026-10-29 --limit 2000       # the first 2,000 positions only
```

Then check the result and load it (section "Loading into Drishti"):

```bash
python3 tools/pvf/pvf_validate.py data/pvf/pvf-2026-10-29/pvf-2026-10-29.tar
```

**Why three input files.** `--positions` is required: the PV file carries only `position_id` (with `valid_dt`, `risk_metric_cd`,
`dataset_cd`, `shock_group_nm`, `val`, `path_num`, `histo_valid_dt`), no portfolio, currency, trade or amount. The positions file is what
links a position to its portfolio and gives the record its desk, book, legal entity, currency, quantity and trade; a PV file alone cannot
be joined. **`--date`** (YYYY-MM-DD, checked) is the business date: only PV rows whose `valid_dt` is that day are read, it is every record's
`BUSINESS_DATE`, `VALUATION_DATE` and `BASE_VALUATION_DATE`, and it names the output folder and files.

## Prepare your environment

Python 3.11 or later with Polars, NumPy and PyYAML, in the console's environment or any other:

```bash
pip install -r drishti-console/requirements.txt -r drishti-console/requirements-polars.txt
```

No Java, no server and no network are needed to convert; loading uses Drishti's own loader (below).

## The scripts

| Script | Does |
|---|---|
| `tools/pvf/pvf_run.py` | **the driver**: join, map, check, write the plain JSONL and the loadable layout, one summary |
| `tools/pvf/pvf_join.py` | the join on its own: the three CSV files to one JSON Lines row per position, the audit, the fill list |
| `tools/pvf/pvf_export_gz.py` | the mapping on its own: a joined file to scenario-pv records as `.jsonl.gz` parts, the scenario set, a `.tar` |
| `tools/pvf/pvf_validate.py` | every record of a file, folder or `.tar` against the scenario-pv and scenario-set contracts |
| `tools/pvf/pvf_gen.py` | a synthetic extract in the same CSV shape (`--positions 2000`), seeded: for trials and tests |

Each has `--help` with examples; `pvf_join.py`, `pvf_export_gz.py` and `pvf_run.py` have `--dry-run` and a limit (`--limit-positions`,
`--limit`, `--limit`). The modules beside them: `common.py` (the UPPER_SNAKE rule, dates, deterministic `.gz` and `.tar` writers,
checksums), `contract.py` (the contracts, read from `modules/banking/packs/market-risk/contracts/`, never copied), `mapping.py` (the
mapping grammar and the scenario set). `test_pvf.py` tests them on small hand-made extracts (`pytest tools/pvf`).

The separate steps, as the driver runs them:

```bash
python3 tools/pvf/pvf_join.py --portfolio data/pvf/portfolio_d2.csv --positions data/pvf/pos_20261029.csv \
    --paths data/pvf/pvf_path_20261029.csv --out data/pvf/pvf-2026-10-29/pvf_joined.2026-10-29.jsonl
python3 tools/pvf/pvf_export_gz.py --joined data/pvf/pvf-2026-10-29/pvf_joined.2026-10-29.jsonl \
    --out data/pvf/pvf-2026-10-29 --jsonl data/pvf/pvf-2026-10-29/scenario_pv.2026-10-29.jsonl
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

6. **Deterministic**: rows in `POSITION_ID` order; the same inputs give the same bytes (the `.gz` and `.tar` too).
7. **Names**: every attribute name the tools write is UPPER_SNAKE (`src_position_id` → `SRC_POSITION_ID`, `Fo_portfolio_nm` →
   `FO_PORTFOLIO_NM`), at every depth, enforced in code and by a test; a PORTFOLIO_D2 column whose name the positions already have gets
   `_D2` (`PORTFOLIO_NM_D2`). Values are never changed by the name rule.

On the owner's 2,000-position slice the join gave the same `base_pv`, the same 251-value vectors and the same bad positions as the
original `pvf_join.py` (whose output was `path_pv_final.jsonl` and `bad_positions_audit.log`).

## The mapping, field by field

`tools/pvf/mapping.yaml` maps a joined row to a scenario-pv record
([contract](../../modules/banking/packs/market-risk/contracts/scenario-pv.contract.yaml)). The export refuses a mapping that names a
field the contract does not have, leaves a required field without a rule (unless `omit_required` names it with its reason) or breaks the
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
| `SCENARIO_PV` | the vector | paths `0..250` | in `scenario_set.path_order` |
| `STATUS` | const `ok` | | the PVs are supplied; fills are reported (`PATHS_FILLED`), a real price is not relabelled `proxied` |
| `COMPUTED_AT` | `{$computed_at}` | the PV file's last-modified time, UTC | the extract has no run time; `--computed-at` to give it |
| `BATCH_ID` | `B-<date>-PVF` | | one batch per date (DRS-5203); `--batch-id` for another |
| `RESTATEMENT` | `0` | | `--restatement 1` for a re-send of the date |
| `RUN_KIND` | `FULL` | | the day's full run |
| `MATURITY_DATE`, `TRADE_STATUS`, `PRICER`, `MODEL_VERSION` | **left out** (`omit_required`) | none in the extract | required by the contract, not needed by the engine (no maturity: not matured; no status: live; pricer and model: audit only); invented values would mislead |
| `SECURITY_ID`, `COUNTERPARTY`, `TAGS`, `TRADE_CURRENCY`, `TRADE_VERSIONS` | left out | none | optional |

**Every other source attribute** rides beside the contract's fields, UPPER_SNAKE, values as they are (`pass_through: columns: "*"`):
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
date's scenario-set record (`scenario-set/<date>/cob.jsonl.gz`) with `SCENARIO_SET_ID` `HS-PVF-251`, `SCENARIO_COUNT` 251 and 251
`SCENARIO_DATES`, and every record names it. A vector that is not 251 long is audited (`BAD_VALUE`), never padded or cut.

- **Days**: the extract does not say which historical day each path is (`histo_valid_dt` is one date for every row). By default the
  days are the 251 weekdays before the business date, with no holiday calendar, and the set's `DESCRIPTION` says they are assumed. Give
  the real days with `--scenario-dates FILE` (one `YYYY-MM-DD` a line, oldest first, all before the date; exactly 251).
- **Order**: `scenario_set.path_order: oldest-first` takes path 0 as the oldest day; `newest-first` reverses the vector.
- **A lake that already has the date's set**: `--no-scenario-set`, and `--scenario-set-id` with its id. One date has one normal set, and
  the date's other scenario P&L (P&L vectors, other systems' scenario PVs) must use the same set and the same batch.

## The outputs

Under `<out>/pvf-<date>/`:

| File | What |
|---|---|
| `pvf_joined.<date>.jsonl` | the join: one row per position, every source attribute, `BASE_PV`, `PATH_PV`, `PATH0_FROM_BASE`, `PATHS_FILLED` |
| `pvf_joined.<date>.audit.csv`, `.fills.csv`, `.summary.json` | the join's exclusions, fills and counts |
| `scenario_pv.<date>.jsonl` | the scenario-pv records with the source attributes, one plain JSON Lines file |
| `scenario-pv/<date>/cob.part-NNN.jsonl.gz` | the same records, 20,000 a part (`--rows-per-part`), `POSITION_ID` order |
| `scenario-set/<date>/cob.jsonl.gz` | the date's scenario set |
| `pvf-<date>.tar`, `pvf-<date>.tar.ready` | both folders in one archive, and its marker for the data inbox (`folders`, `mode: replace`, `rows`, `sha256`) |
| `pvf-<date>.sha256` | the SHA-256 of every part and of the `.tar` (`sha256sum -c pvf-<date>.sha256`) |
| `pvf-<date>.export-audit.csv`, `.export-summary.json` | rows the mapping or the contract refused; the export's counts |
| `pvf-<date>.summary.json` | the whole run: rows in and out at each step, audited by reason, fills, seconds, peak memory |

A re-run replaces the date's parts. Exit codes: `0` done (the audits list what was left out), `1` refused (one line saying why), `2` usage.

## Loading into Drishti

The `.tar` follows the load rules (`docs/guides/DATA_LAKE_GUIDE.md` section 4.4): each top folder is a kind (`scenario-pv`,
`scenario-set`), each subfolder a business date. Load it into a lake with Drishti's loader:

```bash
python3 tools/drishti.py lake build --from data/pvf/pvf-2026-10-29/pvf-2026-10-29.tar --lake risklake --plan    # what would happen
python3 tools/drishti.py lake build --from data/pvf/pvf-2026-10-29/pvf-2026-10-29.tar --lake risklake
```

or drop `pvf-<date>.tar` and then `pvf-<date>.tar.ready` into the data inbox (`<data>/dataload/inbox`; `--lake NAME` on the export writes
`lake:` into the `.ready` file). The plain file loads on its own too (table and date from its name):
`lake build --from data/pvf/pvf-2026-10-29/scenario_pv.2026-10-29.jsonl --lake risklake --kind scenario-pv --pack market-risk`.

For VaR on the date the lake also needs what the engine reads beside the PVs: the currencies' scenario FX (all USD here: none needed),
and no other scenario P&L of the date in another set or batch.

## Performance and memory

- The PV file is scanned once, streaming: memory follows the (position, path) pairs kept (about 32 bytes each: about 1.2 GB for the
  owner's 37 million rows) plus one chunk of the dense matrix (`--chunk` positions x 251 x 8 bytes: 100 MB at 50,000), not the 3.8 GB file.
- The 2,000-position slice of the owner's extract (426,894 path rows): join and export in about 1.2 seconds, peak memory about 400 MB.
- The full extract (200,000 positions): expect a few minutes and a few GB; the summary records the seconds and the peak memory. Keep
  local trials to `--limit 2000`.

## Mapping decisions for the owner

1. `SCENARIO_SET_ID` `HS-PVF-251` and the **scenario days** (assumed weekdays) until the real path dates are known.
2. **Path order**: is path 0 the oldest or the newest day?
3. `POSITION_ID` prefix `POS-PVF-`, `INSTRUMENT` built from product, currency and trade.
4. `SOURCE_SYSTEM` from the `src_position_id` prefix (`CAL` → `CALYPSO`), and names for the `system_id` numbers.
5. `DIVISION` from `pnl_sheet_nm` while `business_group_nm` is empty; `DESK` from `portfolio_group_nm` while `desk_nm` is empty.
6. The four contract-required fields left out (`MATURITY_DATE`, `TRADE_STATUS`, `PRICER`, `MODEL_VERSION`), and keeping the source
   attributes beside the contract's fields.
7. `COMPUTED_AT` from the PV file's last-modified time.
8. The fill policy (`forward`, the original rule) given that fills understate risk.
9. QUANTITY/DIRECTION: the amount is unsigned (option B, decided 2026-11-18); name the sign column when the extract gains one.
