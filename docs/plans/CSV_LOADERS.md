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
# CSV loaders for the market-risk datasets: design (2026-11-18)

**Design only: no code is written by this document.** The owner's request: scripts like the PVF ones (then tools/pvf) to populate "portfolio, position,
counterparty, netting set, limits, sensitivities: each dataset for market risk"; the input is always CSV; each dataset needs a document;
the scripts run every day so that VaR and exposure are accurate.

The former tools/pvf folder is being renamed `tools/mrloader` (market-risk loader). Every path below uses the new name; the PVF scripts keep their
`pvf_` file names inside it, because PVF is one source format among others.

## Contents

1. [The recommendation in short](#1-the-recommendation-in-short)
2. [What exists today, and what it already settles](#2-what-exists-today-and-what-it-already-settles)
3. [The kinds: which get a CSV loader, which never](#3-the-kinds-which-get-a-csv-loader-which-never)
4. [Portfolios and positions: what the owner supplies](#4-portfolios-and-positions-what-the-owner-supplies)
5. [The contracts are not ready for a bank's data](#5-the-contracts-are-not-ready-for-a-banks-data)
6. [The tool: one generic loader](#6-the-tool-one-generic-loader)
7. [The mapping file of a kind](#7-the-mapping-file-of-a-kind)
8. [Three complete mapping files](#8-three-complete-mapping-files)
9. [CSV conventions, including what Excel does to a file](#9-csv-conventions-including-what-excel-does-to-a-file)
10. [Validation and the reject report](#10-validation-and-the-reject-report)
11. [Checks across kinds](#11-checks-across-kinds)
12. [The daily run: repeat, restate, add, remove](#12-the-daily-run-repeat-restate-add-remove)
13. [The inbox order and the dependency graph](#13-the-inbox-order-and-the-dependency-graph)
14. [One way: how this sits beside the loaders that exist](#14-one-way-how-this-sits-beside-the-loaders-that-exist)
15. [Documentation: one generated page per kind, one runbook](#15-documentation-one-generated-page-per-kind-one-runbook)
16. [Samples](#16-samples)
17. [Tests](#17-tests)
18. [Sizes and build order](#18-sizes-and-build-order)
19. [Risks](#19-risks)
20. [Decisions for the owner](#20-decisions-for-the-owner)

## 1. The recommendation in short

- **One generic loader, not one script per dataset.** `tools/mrloader/csv_load.py --kind K --csv F --date D` reads a CSV, applies the
  kind's mapping file (`tools/mrloader/kinds/<kind>.yaml`), checks every record against the kind's data contract and writes three
  files per kind and business date: the plain `<kind>.<date>.jsonl`, the loadable `<kind>.<date>.jsonl.gz` and its `.ready` marker,
  with a reject report and a summary beside them. A new dataset is a mapping file, a sample and a generated page; no new code.
- **Simple scripts that convert, and load nothing.** The data inbox does the load. Two optional hooks end a run: `--inbox` copies the
  file and its marker into the inbox; `--load --lake NAME` calls the existing `drishti.py lake build --engine java`. Neither is a second
  loader ([section 6.5](#65-the-two-hooks-after-the-files-are-written)).
- **One command a day.** `tools/mrloader/mr_run.py --date D --from DIR --inbox` runs every kind the day's folder holds, in dependency
  order, checks the kinds against each other, and hands the files to the data inbox. `pvf_run.py` stays as the scenario-PV step of it.
- **Sixteen kinds get a loader, in three waves** ([section 3](#3-the-kinds-which-get-a-csv-loader-which-never)). Wave 1 is what VaR
  needs beyond the PVs; wave 2 is what exposure reads; wave 3 is the large vector kinds and the trades.
- **Positions and portfolios are not files of their own.** A position reaches Drishti on its scenario-PV row (the PVF run already does
  this, at position grain); a portfolio is the book column of those rows. Neither has a kind to load
  ([section 4](#4-portfolios-and-positions-what-the-owner-supplies)).
- **No computed figure is ever loaded.** A CSV column holding a usage, a utilisation, a charge or a roll-up is refused or ignored, by
  name, with the reason.
- **One prerequisite must be done first:** the contracts of the master kinds were drafted from the demo data and refuse a real bank's
  rows ([section 5](#5-the-contracts-are-not-ready-for-a-banks-data)). A contract review of the sixteen kinds is the first work item.
- **The page of each kind is generated** from its contract and its mapping file, and a test fails when they drift. One hand-written
  daily runbook sits beside them.

## 2. What exists today, and what it already settles

The former tools/pvf (now `tools/mrloader`) turns three PVF CSV files into scenario-PV records. Its parts, and what the generic loader
takes from each unchanged:

| Part | What it does | Reused as |
|---|---|---|
| `mapping.yaml`, `mapping.py` | the mapping grammar: `const`, `from` (a priority list), `template`, `regex`, `map`/`else`, `sign`, `sign_from`, `slug`, `upper`, `prefix`, `type`, `default`; `omit_required` with reasons; `pass_through` | the grammar of every kind's mapping file, extended ([section 7](#7-the-mapping-file-of-a-kind)) |
| `contract.py` | reads a contract's YAML (never a copy), checks a record: required, types, enums, patterns, UPPER_SNAKE names, no unknown field | the one record checker, made general: any kind, every contract type, nested records |
| `common.py` | UPPER_SNAKE rule, strict dates, deterministic `.gz` and `.tar` writers, checksums, timers | as it is |
| `pvf_export_gz.py` | records to parts, one tar per kind, the `.ready` marker (`folders`, `mode: replace`, `rows`, `sha256`), audit and summary | the writing step, moved to a shared module (`package.py`) and changed to one `.jsonl.gz` and its `.ready` per kind ([section 6.6](#66-what-the-pvf-scripts-should-change)) |
| `pvf_run.py` | the driver; `--inbox` hands each archive, then its marker, to the data inbox, the scenario set first | `hooks.py` (the hand-over and the direct load), with the order read from the mapping files |
| `pvf_join.py` | the streaming join of 37 million path rows into one row per position with its vector | stays PVF's own: it is the one place a long file becomes a vector |
| `pvf_validate.py` | checks any file, folder or tar against the contracts | `validate.py`, for every kind |
| `pvf_gen.py` | a seeded synthetic extract | `mr_gen.py`: the whole day's CSV set ([section 16](#16-samples)) |

A parallel builder is adding `pvf_scenario_set.py` and `pvf_fx_scenario.py`. They are the first two kinds beyond scenario PVs and should
land as mapping files of the generic loader when it exists ([section 18](#18-sizes-and-build-order), step 4).

Rules already decided, which this design follows without reopening:

- JSON Lines is the data standard. CSV is read at the edge and never reaches a lake, a connector or the server.
- Attribute names are UPPER_SNAKE at every depth; values are never changed by the name rule.
- Scripts run directly by path. They are not `drishti.py` commands.
- A business date is replaced as a whole; a removal is `drishti.py lake delete`.
- Drishti never prices and never stores a computed figure in an input table.
- Delta, Iceberg and the file store behave the same: the scripts write JSON Lines and the lake's own loader does the rest.
- The scripts are simple and load nothing themselves (owner, 2026-11-18): they end at files the data inbox takes.
- No drill for this tool: its tests and the pre-merge gate only.

## 3. The kinds: which get a CSV loader, which never

Every contract under `modules/banking/packs/{market-risk,counterparty-risk,trading}/contracts` and `config/packs/core/core/contracts`
was read. "Shape" says what the CSV must express: **flat** (one row a record), **record** (a nested object, from prefixed columns),
**list** (a list in one cell), **children** (a list of records, from a second CSV or repeated rows), **vector** (a long file of one value
a row).

### 3.1 Wave 1: VaR

| Kind (mnemonic) | Key | Lake table | Shape | Why it matters to VaR |
|---|---|---|---|---|
| `scenario-set` (SSET) | `SCENARIO_SET_ID` (`^HS-`) | `risk.scenario-set` | flat + list (`SCENARIO_DATES`) | the scenario days every vector is indexed by; required |
| `scenario-pv` (SPV) | `SCENARIO_PV_ID` (`^SPV-`) | `risk.scenario-pv` | vector | the PVs; **built** (`pvf_run.py`); carries the positions and the hierarchy columns |
| `fx-scenario` (FXS) | `FX_SCENARIO_ID` (`^FXS-`) | `risk.fx-scenario` | vector, small (one row a currency) | converts PVs in other currencies; same scenario set and batch as the PVs |
| `var-limit` (VLIM) | `LIMIT_ID` (`VL-<NODE_ID>`) | `risk.var-limit` | flat | the Limit column and limit use, per hierarchy node or system basket |
| `risk-factor` (RFAC) | `RISK_FACTOR` (`^(IR\|INF\|CR\|FX\|EQ\|CM\|VOL)/`) | `risk.risk-factor` | flat + list (`SOURCE_NAMES`) | the dictionary that turns the bank's factor names into the standard ones; on change |
| `position-sensitivity` (SENS) | `SENSITIVITY_ID` (`SENS-<id>-<batch>`) | `risk.position-sensitivity` | children (`SENSITIVITIES[]`: one a factor and measure) | the sensitivities the FRTB SA and SIMM engines read; position or trade grain |
| `desk-pnl` (DPNL) | `DESK_PNL_ID` (`DPNL-<desk>-<date>`) | `risk.desk-pnl` | flat | actual and hypothetical P&L: without it there is no backtest |
| `legal-entity` (LE) | `ENTITY_ID` (`^LE-`) | `reference.legal-entity` | flat | the names behind the LEGAL tree's nodes |
| `desk` (DESK) | `DESK_ID` (`^DESK-`) | `reference.desk` | flat + list (`ASSET_CLASSES`) | the names behind the desk nodes |
| `book` (BOOK) | `BOOK_ID` (`^BOOK-`) | `reference.book` | flat | the names behind the book nodes; this is "portfolio" ([section 4](#4-portfolios-and-positions-what-the-owner-supplies)) |

The minimum for a correct VaR number is the first three (or two, when every PV is in USD). The trees are built from the columns of the
scenario-PV rows, so the three masters add names and pages, not accuracy; they are cheap, and wave 2 needs `legal-entity`.

### 3.2 Wave 2: exposure

| Kind (mnemonic) | Key | Lake table | Shape | What the exposure engine reads |
|---|---|---|---|---|
| `counterparty-group` (GRP) | `GROUP_ID` (`^GRP-`) | `reference.counterparty-group` | flat (members come from the counterparties' `GROUP`) | the group's name and its own approved limit |
| `counterparty` (CPTY) | `COUNTERPARTY_ID` (`^CP-`) | `reference.counterparty` | flat + record (`KYC`) | name, rating, country, group, the legal entity facing it |
| `agreement` (AGR) | `AGREEMENT_ID` (`^AGR-`) | `legal.agreement` | flat + record (`ELECTIONS`) | the master agreement and whether close-out netting holds |
| `csa` (CSA) | `CSA_ID` (`^CSA-`) | `legal.csa` | flat + children (`ELIGIBLE[]`) | thresholds, minimum transfer amounts, independent amount, margin frequency, margin period of risk |
| `netting-set` (NSET) | `NETTING_SET_ID` (`^NS-`) | `credit.netting-set` | flat | the terms and links only: counterparty, legal entity, agreement, CSA, `NETTING_ENFORCEABLE` |
| `collateral-balance` (COLL) | `BALANCE_ID` (`^COLL-`) | `collateral.collateral-balance` | children (`POSITIONS[]`: asset, direction, market value, haircut) | today's collateral held and posted |
| `credit-limit` (LIM) | `LIMIT_ID` (`^LIM-`) | `credit.credit-limit` | flat + children (`BUCKETS[]`: bucket and limit) | the approved limit and its measure |
| `trade-valuation` (TVAL) | `VALUATION_ID` | `risk.trade-valuation` | flat | each trade's netting set, status and MtM today: this is how the engine knows a set's members |

### 3.3 Wave 3: the large vector kinds and the trades

| Kind | Shape | Note |
|---|---|---|
| `simulation-run` (XRUN), `exposure-path` (XPATH) | vector; the largest table (trade × grid date × paths) | needs a streaming join like `pvf_join.py`; the extract's shape is not known yet ([decision 9](#20-decisions-for-the-owner)) |
| `pnl-vector` (PNLV) | vector | the same path as scenario PVs with another mapping; only when a system sends P&L instead of PVs |
| `pnl-history` (PNLH) | vector + list | stressed VaR and long history; one row a node |
| `trade` (TRD) | children (`LEGS[]`, `EVENTS[]`), one table per source system | optional for both numbers; the source-system modules are the way trades arrive ([section 14](#14-one-way-how-this-sits-beside-the-loaders-that-exist)) |

### 3.4 On request only

`finance-pnl`, `capital-input`, `default-risk`, `residual-risk`, `regulatory-desk`, `risk-type-map`, `trade-cash-flow`, `volcker-trade`,
`stress-scenario`, `margin-call`, `trader`, `issuer`, `ccp`, `clearing-account`, `calendar`. Each is a mapping file, a sample and a
generated page once the loader exists; none changes a VaR or exposure number today.

### 3.5 Never

| Kinds | Why |
|---|---|
| **Computed** (`var-result`, `var-official`, `exposure-result`, `exposure-official-cpty`, `cpty-mtm`, `nset-mtm`, `stress-scenario-pnl`, `counterparty-trades`, `portfolio-trades`, `legal-entity-trades`, `index-trades`) | Drishti computes them when asked. Loading one would put a stored figure beside the engine's |
| **Drishti-owned** (`position`, `system-basket`) | Drishti keeps them: positions are derived or supplied on the PV rows, system baskets live in the basket store ([section 4](#4-portfolios-and-positions-what-the-owner-supplies)) |
| **Stored results of engines Drishti now has or will have** (`frtb-sensitivity`, `simm`, `sa-ccr`, `cva`, `stress-result`, `pnl-explain`) | each is another system's answer (a charge, an initial margin, an EAD, a CVA). `frtb-sensitivity` is a book's FRTB charge, not sensitivities: the FRTB SA engine computes it from `position-sensitivity`. The owner's "sensitivities" are `position-sensitivity` |

The loader refuses these kinds by their contract's `class:` (`computed`, `drishti-owned`) and by a short list in its configuration for
the third row, naming the reason and what to load instead.

## 4. Portfolios and positions: what the owner supplies

### 4.1 Portfolio

Drishti has no `portfolio` kind; a file of that name is refused at load. What a bank calls a portfolio is one of three things:

| The bank means | In Drishti | How it is supplied |
|---|---|---|
| the booking portfolio of a position (PORTFOLIO_D2's `portfolio_nm`) | the **book**: the columns `TT_BOOK` and `PORTFOLIO` on every scenario-PV row (`BOOK-PORT-100000`) | by the PV rows themselves. The `book` master adds its name, desk and classification: a `book` CSV, wave 1 |
| the reporting trees (division, desk, book; legal entity; limit letter) | the **hierarchies**, built by the server from the promoted columns of the date's PV rows (`DIVISION`, `LEGAL_ENTITY`, `DESK`, `TT_BOOK`, `LIMIT_LETTER`) | nothing to load: there is no hierarchy feed. A wrong tree is fixed in the PV mapping, never by a second file |
| a named set of positions someone watches (`PF-USD-RATES`) | a **system basket**, kept in Drishti's basket store | created in *Construct your basket* or imported as YAML by a holder of `var:system-baskets`. Only its VaR limit is data: a `var-limit` row with `HIERARCHY` `PORTFOLIO` |

So `portfolio_d2.csv` is read twice: by the PV run (for the book and desk of each position, as today) and by the `book` loader (one row a
book). Both use the same id rule, kept in one place ([section 7.4](#74-shared-fragments-one-id-rule-for-every-kind)).

### 4.2 Position

`position` is `drishti-owned`: no position table is delivered, and none will be. The rule has two halves:

- **A source that sends trades** gets its positions derived by the server, from the trades' rows and the pack's `positions:` rules.
- **A source that sends positions whole** sends them as its scenario-PV (or P&L-vector) rows with `GRAIN: POSITION` and its own
  `POSITION_ID`. The row carries what places the position: legal entity, division, desk, book, product, currency, quantity, direction,
  and optionally the trades it holds. The server never re-derives such a position.

The PVF extract is the second case, and `pvf_run.py` already does it: `pos_YYYYMMDD.csv` joined to `portfolio_d2.csv` gives each row its
attributes, and every other source column rides along. **The owner's positions file is therefore already loaded, as part of the
scenario-PV run.** A position with no PVs has no VaR and no page: there is nothing to load it into.

What the owner should do: keep sending `pos_YYYYMMDD.csv` with the PV file; fix position attributes in the PV mapping; and use the same
`POSITION_ID` (`POS-PVF-<position_id>`) in every other position-grain file, which the shared id rule guarantees. For exposure, the
per-trade equivalent is `trade-valuation` (wave 2).

## 5. The contracts are not ready for a bank's data

The contracts of the engine inputs written by hand (`scenario-pv`, `var-limit`, `position-sensitivity`, `risk-factor`, `desk-pnl`,
`exposure-path`) are sound. The contracts of the masters were drafted by `contract bootstrap` from the demo data, and each still lists
its review points. Three kinds of problem would refuse or distort a real file:

| Problem | Examples | What it does to a bank's CSV |
|---|---|---|
| **Roll-ups and usage marked required** | `book`: `TRADE_COUNT`, `MTM`, `DV01`, `TOP_TRADES[]`. `desk`: `MTM`, `BOOKS[]`, `POSITIONS[]`. `legal-entity`: `DESKS[]`, `BOOKS[]` with P&L and MtM. `counterparty`: `NET_MTM`, `NETTING_SETS[]`, `HIERARCHY`. `netting-set`: `TRADE_COUNT`, `NET_MTM`, `COLLATERAL`, `LIMIT`, `CVA`, `TRADES[]`, `BY_ASSET[]`. `credit-limit`: `USED`, `UTILISATION`, `STATUS`, `BUCKETS[].USED`. `counterparty-group`: `UTILISATION`. `collateral-balance`: `HELD`, `POSTED`, `NET` | a master file from a bank has none of these; every row would be rejected. They are figures Drishti should compute, and the rule already says a computed figure is never stored in an input table |
| **Enumerations taken from the demo's few values** | `legal-entity.NAME` and `LEI` (the four demo entities), `REGULATOR`; `agreement.GOVERNING_LAW` (English, New York); `csa.MARGIN_TYPE`, `ELIGIBLE[].ASSET`; currencies limited to seven; `trade.TRANSIT`; `credit-limit.STATUS` | a real name, law or currency is refused |
| **Links required to results of other engines** | `netting-set`: `CVA_RESULT`, `SACCR`, `SIMM`; `counterparty`: `NEWS_ID`, `ECONOMY` | a bank has no such ids |

Two id patterns also deserve a look: `trade.TRADE_ID` (`^(MX|CLY)-\d+$`) and the trade ids nested in the masters.

**Recommended:** a contract review of the sixteen kinds before any mapping file is written, as the first work item. For each field:
required stays only when the engine needs it; a roll-up becomes `derived` (the contract format already has this presence: Drishti fills
it, an upstream value is ignored); a demo enumeration is removed or widened; a link to another engine's result becomes optional. The
demo data keeps loading (it has every field); the generated contract pages follow by `contract docs`.

Until then the loader's `omit_required` (PVF already uses it for four fields) can name each such field with its reason, and the generated
page shows them as known gaps. That is a stop-gap, not the design: sixteen kinds each with a list of exceptions would hide the real
state of the contracts.

A consequence the owner should expect: pages that show a stored roll-up today (a book's MtM, a netting set's trade table, a limit's
usage) show nothing for CSV-loaded masters until the server computes those figures (the parked NSET, LIM, GRP, COLL and TVAL contracts
say "terms kept, figures computed"). The exposure and VaR numbers do not depend on them.

## 6. The tool: one generic loader

### 6.1 Why generic

| | One generic loader + a mapping file per kind | One script per kind |
|---|---|---|
| A new dataset | a YAML file, a sample, a generated page | a new script, its tests, its page by hand |
| CSV rules (dates, numbers, Excel quirks) | one reader, fixed once | sixteen readers that drift |
| Validation and the reject report | one format the owner learns once | sixteen formats |
| The owner's rule "one way per job" | met | broken sixteen times |
| A kind that needs real code | the vector join stays a separate step in front of the same pipeline (PVF today) | the only case where a script of its own is right |

A script per kind would be justified only if the kinds needed different logic. They do not: they differ in columns, keys and shapes,
which is what a mapping file says. The one real exception, a long file folded into vectors, is already isolated in `pvf_join.py`.

### 6.2 The folder

```text
tools/mrloader/
├── README.md                 the tool, the conventions, the commands (hand-written, short)
├── csv_load.py               ONE kind: CSV -> checked <kind>.<date>.jsonl, .jsonl.gz, .ready (the generic loader)
├── mr_run.py                 ONE day: every kind of a folder, in order, cross-checked, --inbox (the daily driver)
├── validate.py               any file, folder or tar against its kind's contract             (was pvf_validate.py)
├── kind_docs.py              writes or checks docs/<kind>.md from contract + mapping
├── mr_gen.py                 a seeded synthetic day of CSVs                                  (was pvf_gen.py, widened)
├── pvf_run.py, pvf_join.py, pvf_export_gz.py     the PVF source format: the vector join and its driver
├── csvread.py                the one CSV reader (section 9)
├── mapping.py, contract.py, common.py            as today, made general
├── package.py, hooks.py      the three files and their checksums; --inbox (copy) and --load (calls drishti.py lake build)
├── links.py                  the checks across kinds (section 11)
├── kinds/
│   ├── <kind>.yaml           the canonical mapping of a kind: CSV columns named as the contract's fields
│   ├── <kind>.<source>.yaml  a source's own columns: scenario-pv.pvf.yaml (today's mapping.yaml), book.pvf.yaml ...
│   └── shared/<name>.yaml    fragments several kinds include: the id rules, the placement columns
├── days/
│   └── <site>.yaml           the day file: which CSV of the folder is which kind (section 12.1)
├── samples/                  one small synthetic CSV per kind, and its expected JSON Lines
├── docs/
│   ├── <kind>.md             GENERATED, one per kind
│   └── DAILY_RUNBOOK.md      hand-written
└── tests/                    (test_pvf.py moves here)
```

### 6.3 The commands

```bash
# one kind, checked and written under <out>/mr-2026-10-29/
python3 tools/mrloader/csv_load.py --kind var-limit --csv data/mr/var_limits_20261029.csv --date 2026-10-29

# the same, a source's own column names, nothing written
python3 tools/mrloader/csv_load.py --kind book --source pvf --csv data/pvf/portfolio_d2.csv --date 2026-10-29 --dry-run

# a kind with a child file
python3 tools/mrloader/csv_load.py --kind position-sensitivity --csv data/mr/sens_positions_20261029.csv \
    --child SENSITIVITIES=data/mr/sens_20261029.csv --date 2026-10-29

# helpers
python3 tools/mrloader/csv_load.py --kind counterparty --template > counterparty.csv    # the header row and one example row
python3 tools/mrloader/csv_load.py --kinds                                               # every kind with a mapping, its inputs and order

# the day: every kind the folder holds, in order, cross-checked, handed to the inbox
python3 tools/mrloader/mr_run.py --date 2026-10-29 --from data/mr/2026-10-29 --day tools/mrloader/days/site.yaml --inbox
```

Options every command shares, with PVF's meaning: `--date` (YYYY-MM-DD, checked, required), `--out DIR`, `--dry-run`, `--limit N`,
`--mapping FILE` (another mapping file, by explicit path), `--lake NAME` (written into the `.ready`), `--inbox [DIR]`,
`--load` (with `--lake`), `--batch-id`, `--restatement N`, `--computed-at`, `--strict`, `--verbose`. Exit codes as PVF: `0` done (the
reject report lists what was left out), `1` refused (one line saying why), `2` usage; with `--load`, the exit code of `lake build`
when it is not zero.

### 6.4 The pipeline of one kind

```text
CSV(s) ──read──> rows of text ──map──> records ──check──> good records ──write──> <kind>.<date>.jsonl
        csvread.py             mapping.py          contract.py              package.py    <kind>.<date>.jsonl.gz  + .ready
        dialect, header        fields, children,   types, required, enum,                 <kind>.<date>.rejects.csv
        (never typed here)     records, lists      pattern, key unique                    <kind>.<date>.summary.json
```

1. **Before a row is read:** the mapping is checked against the contract (every required field has a rule or a named omission; no rule
   names an unknown field); the CSV's header is checked against the mapping (every column a rule needs is there). Either failing
   refuses the run in one line.
2. **Read:** every cell is text; the reader only applies the dialect ([section 9](#9-csv-conventions-including-what-excel-does-to-a-file)).
3. **Map:** each field's rule gives a value, typed as the contract says. Children are grouped under their parent.
4. **Check:** the record against the contract, then the kind's rules over the whole date (unique key; the rules a mapping names).
5. **Write:** the records in key order to the plain file and, with a deterministic gzip, to the loadable file; then its `.ready`. The
   same inputs give the same bytes.

Reading uses Polars with every column as text (no type inference), as `pvf_join.py` does: a master of a few thousand rows is instant,
and a sensitivity file of several million rows streams.

**The files of one kind and date**, under `<out>/mr-<date>/` (`--out`; default: the CSV's folder):

| File | What | Goes to the inbox |
|---|---|---|
| `<kind>.<date>.jsonl` | the records, plain JSON Lines, to read and to diff (`--no-plain` leaves it out for a very large kind) | no |
| `<kind>.<date>.jsonl.gz` | the same records, gzipped: the file the loader takes | yes |
| `<kind>.<date>.jsonl.gz.ready` | the marker (below) | yes, after its file |
| `<kind>.<date>.rejects.csv`, `<kind>.<date>.summary.json` | [section 10](#10-validation-and-the-reject-report) | no |
| `mr-<date>.sha256` | the SHA-256 of every `.jsonl.gz` of the day (`sha256sum -c`) | no |

**The name is the routing.** The data inbox reads a plain file's table and business date from its name,
`<table>.<YYYY-MM-DD>.<extension>` (the lake guide's load-name rules): `var-limit.2026-10-29.jsonl.gz` is the table of the kind
`var-limit` on 2026-10-29, with no rule and nothing in the marker needed to say so (`_` and `-` compare alike). So the scripts name
every file by its kind and `--date`, and a master, which has no date field, is dated by its name. A trade file is named for its
system's table (`trade_endur.<date>.jsonl.gz`), since the kind `trade` has several.

**The marker** carries only what the name cannot say and what guards the load:

```yaml
# var-limit.2026-10-29.jsonl.gz.ready
pack: market-risk            # the kind's pack (from the contract), so routing never depends on a site's rules
kind: var-limit
mode: replace                # the date is replaced as a whole: a re-run is safe
rows: 26                     # checked by the inbox before anything is written
sha256: 5e1e6523226a718f836f725d58650af2de53973add4a7733622e3fe5fb6df266
lake: risklake               # only with --lake; else the site's routing rule or drishti.dataload.lake decides
```

It never carries `business-date:` or `table:` (the name says both, and two sources of one fact can disagree).

**One file a kind and date, and when a tar is still right.** A plain file is one table and one date, loaded in one commit. Every kind
of waves 1 and 2 fits one file, the owner's 200,000 scenario-PV rows included (about 250 MB gzipped, streamed by the loader). A tar is
kept for one case only: a kind whose date must be split into several parts (`--rows-per-part N`, off by default). Parts dropped as
separate files could be taken by two inbox cycles, and under replace the second would take out the first; a tar of
`<kind>/<date>/part-NNNN.jsonl.gz` loads completely or not at all. `--tar` asks for it; nothing writes one by default.

### 6.5 The two hooks after the files are written

Both are optional, mutually exclusive, and run only when every kind of the run was converted and checked.

| Hook | What it does | What it is not |
|---|---|---|
| `--inbox [DIR]` | copies each `.jsonl.gz` into the data inbox (`data/dataload/inbox`, or `DIR`) under a temporary visible name, renames it into place, and only then writes its `.ready`. The server's inbox loads it at its next cycle | not a load: the script returns before anything is in the lake |
| `--load --lake NAME` | runs, per file, in dependency order, the existing command: `python3 tools/drishti.py lake build --engine java --from <out>/mr-<date>/<kind>.<date>.jsonl.gz --lake NAME --pack P --kind K --mode overwrite-dates` (and `--signal URL` when `--server URL` is given); prints its output; stops at the first non-zero exit and returns it. `--load --plan` passes `--plan` and writes nothing | not a second loader: no table, file format or lake layout is known to the script. It needs Java and the server jar, as `lake build` does |

Without either, the run ends with the lines to do it by hand (the copy into the inbox, or the `lake build` line), as PVF's README shows
today.

### 6.6 What the PVF scripts should change

A recommendation, to make PVF the first user of the same rules rather than an exception to them:

| Today | Recommended |
|---|---|
| `scenario-pv/<date>/cob.part-NNN.jsonl.gz` in `scenario-pv-<date>.tar`, with `scenario-pv-<date>.tar.ready` | `scenario-pv.<date>.jsonl.gz` with `scenario-pv.<date>.jsonl.gz.ready`: one file, no parts. `--rows-per-part` and `--tar` remain for a date too large for one file |
| `scenario-set/<date>/cob.jsonl.gz` in `scenario-set-<date>.tar` | `scenario-set.<date>.jsonl.gz` and its `.ready` |
| the plain file `scenario_pv.<date>.jsonl` | `scenario-pv.<date>.jsonl` (the kind's own spelling, as every other kind) |
| `pvf-<date>.sha256`, `pvf-<date>.export-audit.csv`, `.export-summary.json` | `mr-<date>.sha256`, `scenario-pv.<date>.rejects.csv` (the join's audit and the export's audit in the one reject format), `scenario-pv.<date>.summary.json` |
| the marker's `folders:` (a tar's key) | `pack:`, `kind:`, `mode`, `rows`, `sha256`, `lake` |
| `to_inbox` ranks archives by the name's `kind-` prefix, with a constant order | `hooks.py` ranks files by the mapping files' `after:` lists |
| the README's loading section: one `lake build --from …tar` per kind, because a tar of two folders is refused by `lake build` | `--load --lake NAME`, or the two `lake build` lines on the `.jsonl.gz` files; the two-folder problem is gone with the tars |
| the output folder `pvf-<date>/` | `mr-<date>/`, shared with the other kinds, so the checks across kinds find the PVs |

The join's own outputs (`pvf_joined.<date>.jsonl`, its fills and summary) stay as they are: they are PVF's working files, not load
files. The parallel builder's `pvf_scenario_set.py` and `pvf_fx_scenario.py` should write the new layout from the start.

## 7. The mapping file of a kind

A mapping file says how one CSV shape becomes one kind. Its grammar is PVF's, with four additions (inputs, nested fields, lists, shared
fragments).

### 7.1 Every key

| Key | Meaning | Default |
|---|---|---|
| `format` | the mapping format's version (`1`) | required |
| `kind` | the contract's kind; the contract file is found by kind through the one pack resolver, never by a path written here | required |
| `pack` | only when two packs have the kind | the one pack that has it |
| `source` | the name of the CSV shape this file maps (`canonical`, `pvf`, …); selected by `--source` | `canonical` |
| `about` | one sentence for the generated page | required |
| `after` | the kinds that must be delivered before this one; the inbox order is their topological order ([section 13](#13-the-inbox-order-and-the-dependency-graph)) | none |
| `csv` | the dialect: `delimiter`, `encoding`, `decimal`, `thousands`, `dates`, `timestamps`, `timezone`, `nulls`, `booleans`, `skip-rows`, `footer` ([section 9](#9-csv-conventions-including-what-excel-does-to-a-file)) | the shared defaults (`kinds/shared/csv.yaml`) |
| `inputs` | the CSV files: `main` (one row a record, or one row a child when `group-by` is set), `lookup` files joined by a key, `children` files grouped into a list ([section 7.2](#72-inputs-one-file-or-several)) | one `main` file |
| `key` | how the record's key is built when the CSV has none (a template); the contract names the key field | the contract's key column |
| `run` | run values for templates (`{$batch_id}`, `{$computed_at}`, `{$restatement}`, `{$source_system}`); each has a command-line option | per kind |
| `use` | shared fragments to include ([section 7.4](#74-shared-fragments-one-id-rule-for-every-kind)) | none |
| `fields` | one rule per contract field ([section 7.3](#73-a-fields-rule)) | required |
| `omit-required` | a required contract field left out on purpose, with its reason (shown on the page and by `validate.py`) | none |
| `refuse-columns` | CSV columns that are computed figures: named with the reason, never loaded ([section 10.4](#104-computed-columns)) | none |
| `pass-through` | source columns kept beside the contract's fields: `columns` (`"*"` or a list), `exclude`, `collision-prefix` | none kept |
| `on-reject` | `refuse` (any rejected row refuses the whole run) or `skip` (rejected rows are listed, the rest loads), and `max-rejects` | `refuse` for masters and limits, `skip` for the large kinds |
| `checks` | rules over the date's records beyond the contract: `unique`, `one-value` (one batch), `links` ([section 11](#11-checks-across-kinds)) | the contract's |

Keys are written with hyphens, as the lake and `.ready` files are (`omit-required`, `pass-through`); the loader reads PVF's present
underscore spellings too until `scenario-pv.pvf.yaml` is rewritten, then refuses them.

### 7.2 Inputs: one file or several

```yaml
inputs:
  main:     {option: --csv, required: [POSITION_ID]}                      # one row a record
  lookup:
    PORTFOLIO: {option: --portfolio, on: PORTFOLIO_ID, valid: {from: VALID_DT, to: VALID_TO_DT}, suffix: _D2}
  children:
    SENSITIVITIES: {option: --child SENSITIVITIES, on: POSITION_ID, empty: reject}
```

- **`lookup`** joins another CSV to each main row by a key, for attributes the main file lacks (a position's portfolio). `valid` keeps
  only the rows valid on the date. A missing or ambiguous match rejects the row (`MISSING_LOOKUP`, `AMBIGUOUS_LOOKUP`). These are
  `pvf_join.py`'s portfolio rules, stated as configuration.
- **`children`** groups a second CSV's rows under their parent as a list of records, in file order (or `sort:`). A child whose parent is
  not in the main file is rejected (`ORPHAN_CHILD`); a parent with no child is rejected or kept as `empty:` says.
- **One long file instead of two:** `main: {group-by: POSITION_ID}` reads a file where the parent's columns repeat on every child row.
  The parent's columns must agree on every row of a group (else `CONFLICTING_PARENT`, naming the column and the two values).
- **A vector** is not an input role here. A long file folded into a fixed-length vector is the streaming join's job (`pvf_join.py`);
  its output is then mapped by the same grammar. A small vector kind (`fx-scenario`: one row a currency) may give its values in one
  cell or one column a scenario, with `type: vector` and `split`.

### 7.3 A field's rule

PVF's rule, in the same order of application, with the additions marked **new**:

| Key | Meaning |
|---|---|
| `const: V` | a fixed value |
| `from: COL` or `[COL1, COL2]` | the first non-empty column (names upper-cased; a lookup's column carries its suffix) |
| `template: "A-{COL}"` | `{COL}` a column, `{$name}` a run value, `{@FIELD}` a field mapped above; empty when any part is empty |
| `regex`, `map` + `else`, `sign`, `sign_from`, `slug`, `upper`, `prefix`, `default` | as PVF |
| `type` | `text`, `id`, `integer`, `amount`, `rate`, `date`, `timestamp`, `boolean`, `vector`, `text list`, `id list`, `date list`. Default: the contract's type. **new:** every contract type |
| `split: "\|"` | **new**: one cell to a list (`A\|B\|C`); each element trimmed, empty elements dropped |
| `dates`, `decimal`, `percent` | **new**: this column's own format where it differs from the file's (`percent: true`: `5%` or `5` becomes `0.05`) |
| `pad: {width: 5, with: "0"}` | **new**: restores leading zeros a spreadsheet removed (`107` to `00107`) |
| `lower`, `trim` | **new**: for text whose case or spaces matter to a key |

**Nested fields.** A record field is written with a dot (`KYC.STATUS`), a child's field under its list (`SENSITIVITIES[].VALUE`); the
loader checks each against the contract's `nested:` section. A record whose every part is empty is left out.

A required field that ends empty rejects the row (`MISSING_REQUIRED`); an optional one is left out of the record. A value not in a
`map` without `else` rejects the row (`UNMAPPED_VALUE`): never guessed.

### 7.4 Shared fragments: one id rule for every kind

The same source value must give the same id in every kind, or nothing links: a position's `BOOK-PORT-100000` in its PV row, its
sensitivity row, the `book` master and a limit's node id. So the rules that build ids live once and are included:

```yaml
# kinds/shared/ids.pvf.yaml: the id rules of the PVF source. Changing one changes every kind that includes it.
LEGAL_ENTITY: {from: LEGAL_ENTITY, slug: true, prefix: LE-, default: LE-UNKNOWN}
DIVISION:     {from: [BUSINESS_GROUP_NM, PNL_SHEET_NM], slug: true, prefix: DIV-, default: DIV-UNKNOWN}
DESK:         {from: [DESK_NM, PORTFOLIO_GROUP_NM], slug: true, prefix: DESK-, default: DESK-UNKNOWN}
TT_BOOK:      {from: PORTFOLIO_NM_D2, slug: true, prefix: BOOK-}
POSITION_ID:  {template: "POS-PVF-{POSITION_ID}"}
```

A mapping includes it with `use: [shared/ids.pvf]`, and a field says `{use: TT_BOOK}`, or `{use: TT_BOOK, as: BOOK_ID}` when the kind's
own field has another name. These five rules are today's lines of `mapping.yaml`, moved; `scenario-pv.pvf.yaml` includes them, so the
PVs and every later kind cannot disagree. A test loads the sample day and checks that every link resolves.

## 8. Three complete mapping files

These assume the contract review of [section 5](#5-the-contracts-are-not-ready-for-a-banks-data); where they would differ without it,
the `omit-required` block shows the stop-gap.

### 8.1 `kinds/counterparty.yaml` (a master: flat, one nested record)

```yaml
format: 1
kind: counterparty                       # config/packs/core/core/contracts/counterparty.contract.yaml, key COUNTERPARTY_ID (^CP-)
source: canonical
about: "The bank's counterparties: one row each, from the client master. Names, ratings and links only; no exposure figure."
after: [legal-entity, counterparty-group]
on-reject: refuse                        # a missing counterparty is a silent gap in every exposure answer: fix the file, run again

inputs:
  main: {option: --csv, required: [COUNTERPARTY_ID, NAME]}

fields:
  COUNTERPARTY_ID: {from: COUNTERPARTY_ID, slug: true, prefix: CP-}     # "Aldershot Fund" or "CP-ALDERSHOT-FUND": both give CP-ALDERSHOT-FUND
  NAME:            {from: NAME}
  LEI:             {from: LEI, upper: true}                             # 20 characters; checked by the contract's pattern
  RATING:          {from: RATING}
  SECTOR:          {from: SECTOR}
  COUNTRY:         {from: COUNTRY, upper: true}                         # ISO 3166-1 alpha-2
  TYPE:            {from: TYPE}
  COUNTRY_OF_RISK: {from: [COUNTRY_OF_RISK, COUNTRY], upper: true}      # the country when no country of risk is given
  UEN:             {from: UEN}                                          # text: a leading zero is kept
  GROUP:           {from: GROUP, slug: true, prefix: GRP-}
  CREDIT_LIMIT:    {from: CREDIT_LIMIT, slug: true, prefix: LIM-}
  LEGAL_ENTITY:    {from: LEGAL_ENTITY, slug: true, prefix: LE-}        # optional: the bank's entity that faces it
  CREDIT_CURVE:    {from: CREDIT_CURVE, prefix: CDS-}                   # optional
  KYC.STATUS:              {from: KYC_STATUS}
  KYC.RISK_RATING:         {from: KYC_RISK_RATING, map: {STANDARD: Standard, ENHANCED: Enhanced, S: Standard, E: Enhanced}, upper: true}
  KYC.LAST_REVIEW:         {from: KYC_LAST_REVIEW, type: date}
  KYC.EMIR_CLASSIFICATION: {from: EMIR_CLASSIFICATION}
  KYC.PD_1Y:               {from: PD_1Y, type: rate, percent: false}    # a fraction: 0.0004 is 0.04%

refuse-columns:                          # figures Drishti computes: a CSV column of this name is reported, never loaded
  NET_MTM: "the sum of the netting sets' MtM is computed from the trade valuations"
  USED: "limit usage is computed by the exposure engine"

omit-required:                           # only until the contract review marks them derived or optional
  NET_MTM: "a roll-up of the netting sets; not supplied by a client master"
  NETTING_SETS: "the netting sets name their counterparty; the reverse list is derived"
  HIERARCHY: "the legal graph is derived from group, agreement, CSA and netting set"
  ECONOMY: "needs the alpha-3 country code; derived from COUNTRY by the server"
  NEWS_ID: "the demo's news link; a bank has none"

pass-through: {columns: "*", exclude: [], collision-prefix: SRC_}       # other client-master columns ride along, UPPER_SNAKE
```

Its CSV (the template `--template` writes):

```csv
COUNTERPARTY_ID,NAME,LEI,RATING,SECTOR,COUNTRY,TYPE,COUNTRY_OF_RISK,UEN,GROUP,CREDIT_LIMIT,LEGAL_ENTITY,KYC_STATUS,KYC_RISK_RATING,KYC_LAST_REVIEW,EMIR_CLASSIFICATION,PD_1Y
CP-ALDERSHOT,Aldershot Pension Fund,5493001KJTIIGC8Y1R12,AA-,Pensions,GB,Pension fund,GB,UEN0012345,GRP-ALDERSHOT,LIM-ALDERSHOT,LE-LDN,Approved,Standard,2026-03-14,FC,0.0004
```

### 8.2 `kinds/var-limit.yaml` (an engine input: flat, a built key, a cross-check)

```yaml
format: 1
kind: var-limit                          # modules/banking/packs/market-risk/contracts/var-limit.contract.yaml, key LIMIT_ID (VL-<NODE_ID>)
source: canonical
about: "The VaR limit of a hierarchy node or a system basket, for one business date. The full set of limits every day."
after: [scenario-pv]                     # a limit's node exists only when the date's positions build it
on-reject: refuse

inputs:
  main: {option: --csv, required: [HIERARCHY, LIMIT_VAR]}

fields:
  HIERARCHY: {from: HIERARCHY, upper: true}                             # MGMT, LEGAL, LL ... or PORTFOLIO for a system basket
  NODE_ID:                                                              # given whole (MGMT~DESK-RATES), or built from HIERARCHY and NODE
    from: [NODE_ID]
    default: "{@HIERARCHY}~{NODE}"                                      # NODE: DESK-RATES, LE-NY/DESK-RATES, LL-0003; a basket gives NODE_ID (PF-...)
  LIMIT_ID:  {template: "VL-{@NODE_ID}"}
  LIMIT_VAR: {from: LIMIT_VAR, type: amount}                            # USD, positive
  CURRENCY:  {from: CURRENCY, upper: true, default: USD}
  HORIZON:   {from: HORIZON, upper: true, default: 1D}                  # 1D or 10D
  CONFIDENCE: {from: CONFIDENCE, type: rate}                            # 0.99; 99 is read as 0.99 by the engine
  BUSINESS_DATE: {template: "{$business_date}", type: date}             # --date: the file is the date's whole set
  VALID_FROM: {from: VALID_FROM, type: date, default: "{$business_date}"}
  VALID_TO:  {from: VALID_TO, type: date}                               # empty: open-ended

refuse-columns:
  VAR: "a node's VaR is computed by the engine as the page opens"
  LIMIT_USE: "limit use is computed"
  UTILISATION: "limit use is computed"

checks:
  unique: [LIMIT_ID]
  links:
    NODE_ID: {to: var-node, from-kind: scenario-pv, policy: warn}       # section 11: every node must be one the date's positions build
```

```csv
HIERARCHY,NODE,LIMIT_VAR,CURRENCY,HORIZON,CONFIDENCE,VALID_FROM,VALID_TO
MGMT,DESK-RATES-TRADING-LON,4100000,USD,1D,0.99,2026-01-02,
LEGAL,LE-GMO-SPOG/DESK-RATES-TRADING-LON,2500000,USD,1D,0.99,2026-01-02,
PORTFOLIO,,1200000,USD,1D,0.99,2026-06-01,
```

The third row has no `NODE` and no `NODE_ID`: it is rejected with *row 4, column NODE_ID: empty; a PORTFOLIO limit names its basket
(PF-…) in NODE_ID*, which is the behaviour to show in the page's troubleshooting table.

### 8.3 `kinds/position-sensitivity.yaml` (parents and children, two files)

```yaml
format: 1
kind: position-sensitivity               # modules/banking/packs/market-risk/contracts/position-sensitivity.contract.yaml, key SENSITIVITY_ID
source: canonical
about: "The sensitivities of each position (or trade) to its risk factors, from the bank's risk run: one record a position, its sensitivities a list."
after: [risk-factor, scenario-pv]        # the dictionary resolves the factor names; the positions are those of the PVs
on-reject: skip                          # a large file: bad rows are listed, the rest loads
max-rejects: 1%                          # more than this refuses the run

run:
  source_system: ""                      # --source-system: required (no default: the tool never invents the system)
  batch_id: "B-SENS-{$business_date}-01" # one full run per date and source; --batch-id for another
  computed_at: "{$main_file_modified}"   # the file's last-modified time, UTC; --computed-at to give the run's own
  restatement: 0                         # --restatement 1, 2 ... for a re-send of the date

inputs:
  main:                                  # one row a position (or trade): where it sits
    option: --csv
    required: [GRAIN, LEGAL_ENTITY, DESK, TT_BOOK, PRODUCT_GROUP, PRODUCT_TYPE, CURRENCY]
  children:
    SENSITIVITIES:                       # one row a position, risk factor and measure
      option: --child SENSITIVITIES
      on: [POSITION_ID, TRADE_ID]        # the first the main row has
      required: [RISK_FACTOR, MEASURE, VALUE]
      empty: reject                      # a live position with no sensitivity is a failed row upstream, not an empty list
      unique: [RISK_FACTOR, MEASURE]     # never the same factor and measure twice in one record

fields:
  GRAIN:         {from: GRAIN, upper: true}                             # POSITION or TRADE
  POSITION_ID:   {from: POSITION_ID, prefix: POS-}                      # required when GRAIN is POSITION: the id of its scenario-PV row
  TRADE_ID:      {from: TRADE_ID}                                       # required when GRAIN is TRADE
  TRADE_IDS:     {from: TRADE_IDS, split: "|", type: id list}
  SOURCE_SYSTEM: {template: "{$source_system}"}
  SENSITIVITY_ID: {template: "SENS-{@POSITION_ID}-{$batch_id}", default: "SENS-{@TRADE_ID}-{$batch_id}"}
  BUSINESS_DATE: {template: "{$business_date}", type: date}
  BATCH_ID:      {template: "{$batch_id}"}
  RUN_KIND:      {const: FULL}
  COMPUTED_AT:   {template: "{$computed_at}", type: timestamp}
  RESTATEMENT:   {template: "{$restatement}", type: integer}
  STATUS:        {from: STATUS, lower: true, default: ok}               # ok, proxied, failed
  PROXY_REASON:  {from: PROXY_REASON}
  PRICER:        {from: PRICER}
  MODEL_VERSION: {from: MODEL_VERSION}
  LEGAL_ENTITY:  {from: LEGAL_ENTITY, slug: true, prefix: LE-}
  DIVISION:      {from: DIVISION, slug: true, prefix: DIV-}
  DESK:          {from: DESK, slug: true, prefix: DESK-}
  TT_BOOK:       {from: TT_BOOK, slug: true, prefix: BOOK-}
  LIMIT_LETTER:  {from: LIMIT_LETTER}
  FRTB_DESK:     {from: FRTB_DESK}
  PRODUCT_GROUP: {from: PRODUCT_GROUP}
  PRODUCT_TYPE:  {from: PRODUCT_TYPE}
  TRADE_STATUS:  {from: TRADE_STATUS, upper: true}
  MATURITY_DATE: {from: MATURITY_DATE, type: date}
  CURRENCY:      {from: CURRENCY, upper: true}
  VOL_CONVENTION: {from: VOL_CONVENTION, upper: true}
  SENSITIVITIES[].RISK_FACTOR: {from: RISK_FACTOR}                      # the standard name, or the bank's own: the dictionary's SOURCE_NAMES resolve it
  SENSITIVITIES[].MEASURE:     {from: MEASURE, upper: true}             # DV01, CS01, IE01, DELTA, VEGA, CURVATURE_UP, CURVATURE_DOWN, GAMMA, BETA, BASE_CORR
  SENSITIVITIES[].VALUE:       {from: VALUE, type: amount}              # signed, a gain positive; empty or not a number rejects the record: never zero
  SENSITIVITIES[].CURRENCY:    {from: [VALUE_CURRENCY, CURRENCY], upper: true}
  SENSITIVITIES[].TENOR:       {from: TENOR}
  SENSITIVITIES[].SHOCK_SIZE:  {from: SHOCK_SIZE, type: rate}           # required on the two curvature measures
  SENSITIVITIES[].IMPLIED_VOL: {from: IMPLIED_VOL, type: rate}

checks:
  unique: [SENSITIVITY_ID]
  one-value: [BATCH_ID]
  when:                                                                 # the contract's conditional rules, stated once here
    - {if: {GRAIN: POSITION}, need: [POSITION_ID]}
    - {if: {GRAIN: TRADE}, need: [TRADE_ID]}
    - {if: {"SENSITIVITIES[].MEASURE": [CURVATURE_UP, CURVATURE_DOWN]}, need: ["SENSITIVITIES[].SHOCK_SIZE"]}
  links:
    POSITION_ID: {to: scenario-pv, field: POSITION_ID, policy: warn}    # a sensitivity for a position the PVs do not have
    "SENSITIVITIES[].RISK_FACTOR": {to: risk-factor, field: [RISK_FACTOR, SOURCE_NAMES], policy: warn}
```

Its two files:

```csv
GRAIN,POSITION_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PRODUCT_GROUP,PRODUCT_TYPE,TRADE_STATUS,MATURITY_DATE,CURRENCY
POSITION,POS-PVF-123456,GMO_SPOG,MARKETS,RATES TRADING-LON,PORT_100000,Rates,IRS,VALIDATED,2031-06-15,USD
```

```csv
POSITION_ID,RISK_FACTOR,MEASURE,VALUE,VALUE_CURRENCY,TENOR
POS-PVF-123456,IR/CURVE/USD/SOFR/5Y,DV01,-1843.20,USD,5Y
POS-PVF-123456,IR/CURVE/USD/SOFR/10Y,DV01,-9120.75,USD,10Y
```

For the PVF source, `position-sensitivity.pvf.yaml` reads the parents from the files the PV run already takes
(`--csv pos_YYYYMMDD.csv`, `lookup: PORTFOLIO` on `portfolio_d2.csv`) and includes `shared/ids.pvf`, so a position's sensitivities and
its PVs carry the same ids and the same placement without a second positions file.

## 9. CSV conventions, including what Excel does to a file

The rule behind every line: **the file's format is declared, never guessed cell by cell.** A value that does not fit the declared
format rejects its row with the reason; nothing is silently reinterpreted. The defaults are in `kinds/shared/csv.yaml`; a mapping's
`csv:` block or a field's rule overrides them.

### 9.1 The file

| Topic | Rule |
|---|---|
| Encoding | UTF-8. A byte-order mark is removed (Excel's "CSV UTF-8" writes one). `csv.encoding: cp1252` for Excel's plain "CSV" on Windows. A byte that is not valid in the declared encoding refuses the file, naming the row, and suggests the other encoding |
| Delimiter | `,` by default. `csv.delimiter: ";"` (Excel in European locales), `"\t"`, `"\|"`, or `auto`: chosen from the header row among these four only, and reported in the summary. A first line `sep=;` (Excel's hint) is honoured and skipped |
| Quoting | RFC 4180: a field in double quotes may hold the delimiter, a line break and `""` for a quote. An unclosed quote refuses the file, naming the row it opened on |
| Line ends | LF or CRLF |
| Header | the first row, required. Names are trimmed, a non-breaking space becomes a space, and they are compared upper-cased with every other character as `_` (`Legal Entity`, `legal_entity` and `LEGAL-ENTITY` are one column). Two columns that become the same name refuse the file. A column the mapping needs and the file lacks refuses it before a row is read, listing the missing and the near matches |
| Unknown columns | kept by `pass-through` when the mapping says so; else listed once in the summary (`IGNORED_COLUMNS`), never an error |
| Rows to skip | `csv.skip-rows: N` (title lines above the header), `csv.footer: "^Total"` (a totals row: a regular expression on the first cell). Blank rows are ignored. A row with more or fewer cells than the header is rejected (`BAD_ROW_SHAPE`) |
| Row numbers | every message gives the row as a spreadsheet shows it: the header is row 1 (plus skipped rows), the first data row is row 2 |

### 9.2 Values

| Topic | Rule |
|---|---|
| Empty | an empty cell is null. So are the words of `csv.nulls` (default `NULL`, `null`, `N/A`, `#N/A`, `NaN`, `-`), compared after trimming. `0` is a value. An Excel error (`#REF!`, `#VALUE!`, `#DIV/0!`, `#NAME?`) is never null: it rejects the row (`EXCEL_ERROR`) |
| Spaces | leading and trailing spaces and non-breaking spaces are removed; inner spaces are kept |
| Text and ids | kept as written. An id is always text: `000123` stays `000123` |
| Numbers | `csv.decimal: "."` and `csv.thousands: ","` by default (`1,234,567.89`); a European file declares `decimal: ","`, `thousands: "."` or `" "`. Accepted: a leading `+` or `-`, an exponent (`1.2E+07`), an accounting negative in brackets (`(1,234.50)`), a trailing minus (`1234.50-`). Refused: a currency symbol or code in the cell, a thousands separator in the wrong place (`1,23.4`), and any separator the file did not declare |
| Integers | a whole number; `12.0` is accepted, `12.5` is refused |
| Rates | a fraction (`0.05`). `5%` is refused unless the field says `percent: true`, which then reads `5%` and `5` as `0.05` |
| Dates | ISO `YYYY-MM-DD` and `YYYYMMDD` always. Any other format must be declared: `csv.dates: DD/MM/YYYY` (so `29/10/2026`), `MM/DD/YYYY`, `DD-MMM-YYYY` (`29-Oct-2026`), `DD.MM.YYYY`. One format a file (a field may override). A two-digit year is refused. A value that is not a real day in the declared format is rejected: `03/04/2026` is never read both ways. A date with a midnight time (`2026-10-29 00:00:00`, as Excel and databases export) is accepted as the date. An Excel serial number (`46324`) is refused unless `dates: excel-serial` |
| Timestamps | ISO-8601 with a zone (`2026-10-29T06:10:00Z`). A timestamp without a zone needs `csv.timezone:` (`America/Toronto`) and is written in UTC |
| Booleans | `true`/`false`, `Y`/`N`, `YES`/`NO`, `1`/`0`, `T`/`F`, any case (`csv.booleans` to change). Anything else rejects the row |
| A list in a cell | `split: "\|"` on the field: `A\|B\|C`. The separator must differ from the file's delimiter unless the cell is quoted |
| A nested record | prefixed columns (`KYC_STATUS` to `KYC.STATUS`), as the mapping says |
| A list of records | a child file or repeated rows ([section 7.2](#72-inputs-one-file-or-several)); never JSON in a cell |

### 9.3 What a spreadsheet silently changes

A file opened and saved in Excel can be damaged without any sign in the file's format. The loader cannot undo these; it detects what it
can and says so in words the owner can act on.

| Damage | Example | What the loader does |
|---|---|---|
| A long id turned into a number | `200001234567` saved as `2.00001E+11` | a value in an `id` field matching `^\d(\.\d+)?E\+\d+$` rejects the row (`EXCEL_NUMBER_ID`): *looks like a spreadsheet turned an id into a number; export the column as text* |
| Leading zeros lost | transit `00107` saved as `107` | `pad:` on the field restores a fixed width; without it the contract's pattern rejects the row |
| A code turned into a date | a desk named `MAR-26`, a tenor `1-3` saved as `01-Mar` | caught only when the contract has a pattern or enumeration; the generated page warns for such fields |
| More than 15 digits rounded | `1234567890123456789` saved as `1234567890123450000` | not detectable; the page says: never open an id column as a number |
| Dates rewritten to the machine's locale | `2026-10-29` saved as `29/10/2026` | rejected under the ISO default, with the hint to declare `csv.dates: DD/MM/YYYY` |
| A formula's text | `=A2*2`, `'0123` (the text prefix) | a cell starting with `=` rejects the row (`FORMULA`); a leading `'` is removed |
| Trailing empty columns and rows | `,,,` after the last column, thousands of blank rows | empty header cells at the end are dropped with their columns; blank rows ignored |

The runbook's first advice follows from this table: export from the source system, do not round-trip through Excel; when a file must be
edited in Excel, import every column as text.

## 10. Validation and the reject report

### 10.1 Three levels

| Level | Checks | A failure |
|---|---|---|
| **The run** (before a row is read) | the mapping against the contract; the header against the mapping; the dialect; `--date` a real day | refuses the run: exit 1, one line, nothing written |
| **The row** | every field's rule and type; required; enumeration; pattern; the conditional rules (`checks.when`); UPPER_SNAKE names | rejects the row: listed in the reject report |
| **The date** (all rows together) | the key unique; one batch; the kind's own rules (a scenario set's days in order; every vector as long as the set); links ([section 11](#11-checks-across-kinds)) | a duplicate key or a broken date rule refuses the run; a link warns or refuses as its policy says |

`on-reject` decides what rejected rows do to the run. For masters and limits the default is `refuse`: the files are small, and a
missing counterparty or limit is a silent gap in every later answer, so the owner fixes the file and runs again. For the large kinds the
default is `skip` with `max-rejects`: the good rows load and the report lists the rest, as PVF does today.

### 10.2 The reject report

`<kind>.<date>.rejects.csv`, one line per problem (a row with three problems has three lines), in row order:

| Column | Content |
|---|---|
| `FILE` | the CSV's name |
| `ROW` | the row number as a spreadsheet shows it |
| `KEY` | the record's key when it could be built, so the row is found in the source system |
| `COLUMN` | the CSV column the value came from (the first of a priority list that had a value) |
| `FIELD` | the contract's field (`SENSITIVITIES[3].VALUE` names the element) |
| `VALUE` | the cell as it was, cut to 80 characters |
| `REASON` | one of a fixed list of codes (below) |
| `WHY` | a sentence: what is wrong and what is accepted |
| `FIX` | what to do, when the loader can say |

```csv
FILE,ROW,KEY,COLUMN,FIELD,VALUE,REASON,WHY,FIX
cpty_20261029.csv,17,CP-BRAMLEY,KYC_LAST_REVIEW,KYC.LAST_REVIEW,14/03/2026,BAD_DATE,not a date in the file's format YYYY-MM-DD,declare csv.dates: DD/MM/YYYY in the mapping or export ISO dates
cpty_20261029.csv,23,,COUNTERPARTY_ID,COUNTERPARTY_ID,,MISSING_REQUIRED,required and empty,give the counterparty's id
cpty_20261029.csv,41,CP-OAKHAM,LEI,LEI,5493001KJTIIGC8Y1R,BAD_PATTERN,18 characters; an LEI has 20 (ISO 17442),
cpty_20261029.csv,58,CP-ALDERSHOT,COUNTERPARTY_ID,COUNTERPARTY_ID,CP-ALDERSHOT,DUPLICATE_KEY,the same key as row 12,keep one row per counterparty
```

The codes, one list for every kind: `MISSING_REQUIRED`, `BAD_NUMBER`, `BAD_INTEGER`, `BAD_DATE`, `BAD_TIMESTAMP`, `BAD_BOOLEAN`,
`BAD_ENUM` (lists the accepted values), `BAD_PATTERN`, `UNMAPPED_VALUE`, `BAD_ROW_SHAPE`, `EXCEL_ERROR`, `EXCEL_NUMBER_ID`, `FORMULA`,
`DUPLICATE_KEY`, `DUPLICATE_CHILD`, `ORPHAN_CHILD`, `NO_CHILDREN`, `CONFLICTING_PARENT`, `MISSING_LOOKUP`, `AMBIGUOUS_LOOKUP`,
`CONDITION` (a `when` rule), `BAD_VECTOR` (length or a value not a number), `UNKNOWN_LINK` (section 11). PVF's present audit reasons
(`MISSING_PORTFOLIO`, `MISSING_BASE_PV`, `INCOMPLETE_PATHS`, …) keep their names in the same file format.

### 10.3 The summary

The console ends with a few lines, and `<kind>.<date>.summary.json` holds the same in full:

```text
counterparty 2026-10-29: 1,204 rows read, 1,200 written, 4 rejected (BAD_DATE 1, MISSING_REQUIRED 1, BAD_PATTERN 1, DUPLICATE_KEY 1)
REFUSED: on-reject is refuse for counterparty. Fix the 4 rows listed in data/mr/mr-2026-10-29/counterparty.2026-10-29.rejects.csv
         first: row 17, column KYC_LAST_REVIEW: 14/03/2026 is not a date in the file's format YYYY-MM-DD
```

It also carries: the dialect used (delimiter, encoding, formats), columns ignored, columns refused as computed, known contract gaps
(`omit-required`), which column of each priority list fed a field, the link checks' counts, seconds and peak memory.

### 10.4 Computed columns

A bank's extract often carries figures beside the terms: a limit file with usage, a netting-set file with exposure. `refuse-columns`
names them per kind. Such a column is not loaded and the summary says so once (*USED: not loaded; limit usage is computed by the
exposure engine*). With `--strict` its presence refuses the run. The record checker additionally refuses any field whose contract
presence is `derived`, so a mapping cannot route a figure in by another name.

## 11. Checks across kinds

Each contract lists its links (`netting-set.COUNTERPARTY` to `counterparty`, `csa.AGREEMENT` to `agreement`, …). The loader reads them
from the contract, so the checks need no list of their own; a mapping only sets a policy or adds an engine-specific check.

**What is compared.** A link's value is looked up in the target kind's output of the same run: the day's folder `mr-<date>/` holds
every kind's `<kind>.<date>.jsonl.gz`, so `mr_run.py` checks after all kinds are converted and before anything is
delivered. A kind run alone with
`csv_load.py` is checked against what the folder already holds, or `--against DIR` (yesterday's outputs, for a master not re-sent
today); a target not found at all is reported as *not checked*, never as passed.

**The policy.** The contracts' rule is that a row whose key no master knows is kept and flagged, and the engine counts it as unmapped.
So the default is `warn`: the summary and `links-<date>.csv` list every unresolved value with its count and first rows, and the run
continues. `policy: refuse` (per link) and `--strict-links` (the whole run) turn a miss into a refusal. Recommended as `refuse` from the
start for the links whose miss changes a number:

| Link | A miss means | Policy |
|---|---|---|
| `scenario-pv.SCENARIO_SET_ID`, `fx-scenario.SCENARIO_SET_ID` to the date's `scenario-set` | the engine refuses the date | refuse |
| a currency of the PVs without an `fx-scenario` row (and not USD) | those positions cannot be converted | refuse |
| `trade-valuation.NETTING_SET` to `netting-set` | the trade's exposure is outside every set | refuse |
| `netting-set.CSA` to `csa`, `collateral-balance.NETTING_SET` to `netting-set` | a margined set treated as unmargined; collateral ignored | refuse |
| `netting-set.COUNTERPARTY` to `counterparty`, `credit-limit.COUNTERPARTY` | exposure not attributed; limit never compared | refuse |
| `var-limit.NODE_ID` to a node the date's PV rows build | a limit that is never shown | warn |
| `position-sensitivity.POSITION_ID` to the PVs' positions; the reverse (a PV position with no sensitivities) | incomplete FRTB and SIMM answers | warn, both counts in the summary |
| a sensitivity's `RISK_FACTOR` to the dictionary's names or `SOURCE_NAMES` | the factor is left out of the charge | warn, the unknown names listed by count |
| every other link (book, desk, legal entity, agreement, group) | a page link that does not open | warn |

**The limit's node check** needs the tree, not a table: the loader reads the pack's `hierarchies:` block (the level columns and whether
ids are leaf or path) and builds the node ids from the date's scenario-PV records, the way the server does. It reads the server's
configuration; it does not copy the rule. A test compares its node ids for the demo date with the server's.

**Mutual links** (`csa.NETTING_SETS` and `netting-set.CSA`; `agreement.CSAS` and `csa.AGREEMENT`) are why the checks run after all kinds
are converted and not kind by kind. Where a CSV gives only one direction, the other is not invented by the loader
([decision 6](#20-decisions-for-the-owner)).

## 12. The daily run: repeat, restate, add, remove

### 12.1 The day file and the driver

The owner's extracts keep their own names. A day file says which is which, once:

```yaml
# tools/mrloader/days/site.yaml: which file of the day's folder feeds which kind. {YYYYMMDD} and {YYYY-MM-DD} are the run's --date.
format: 1
lake: risklake                              # optional: written into every .ready and used by --load; a kind may name another (lakes:)
kinds:
  legal-entity:         {csv: "legal_entity.csv"}
  desk:                 {csv: "desk.csv"}
  book:                 {csv: "portfolio_d2.csv", source: pvf}
  scenario-set:         {csv: "scenario_dates_{YYYYMMDD}.csv"}
  fx-scenario:          {csv: "fx_scenario_{YYYYMMDD}.csv"}
  scenario-pv:          {run: pvf, pv: "pvf_path_{YYYYMMDD}.csv", positions: "pos_{YYYYMMDD}.csv", portfolio: "portfolio_d2.csv"}
  var-limit:            {csv: "var_limits_{YYYYMMDD}.csv"}
  risk-factor:          {csv: "risk_factors.csv", optional: true}        # on change: absent on most days
  position-sensitivity: {csv: "pos_{YYYYMMDD}.csv", source: pvf, portfolio: "portfolio_d2.csv", child: {SENSITIVITIES: "sens_{YYYYMMDD}.csv"}}
  counterparty:         {csv: "cpty_{YYYYMMDD}.csv"}
  netting-set:          {csv: "netting_sets_{YYYYMMDD}.csv"}
required: [scenario-set, scenario-pv]       # the day is refused without these; another missing kind is reported and skipped
```

`mr_run.py --date D --from DIR --day FILE [--inbox]` then:

1. finds each kind's files; a `required` kind missing refuses the day, an `optional` one is skipped silently, any other is reported;
2. converts every kind in dependency order (the scenario PVs through `pvf_run`'s join); a kind refused stops the day before delivery
   unless `--keep-going`, which delivers the kinds that do not depend on it;
3. runs the checks across kinds;
4. writes `mr-<date>.summary.json` and prints one table: kind, rows read, written, rejected, link warnings, seconds;
5. with `--inbox`, hands the files over in order; with `--load`, runs `lake build` for each in the same order
   ([sections 6.5](#65-the-two-hooks-after-the-files-are-written) and [13](#13-the-inbox-order-and-the-dependency-graph)).

Nothing is delivered until every kind has been converted and checked: a day that fails leaves the lake as it was.

### 12.2 The same day twice

- **Replace per date.** Every `.ready` says `mode: replace`: loading a kind for a date replaces that date's rows of that kind in one
  commit. Running the day again with the same files leaves the same data.
- **The same bytes.** Records are written in key order with a deterministic gzip, so an unchanged input gives an identical file and
  checksum. The summary of a second run says which kinds are unchanged. The inbox itself never loads twice a file whose SHA-256 it
  has loaded while the lake still holds its rows, so handing an unchanged file over again is harmless.
- **The date comes from `--date`**, never from the clock or an input file's name. It names every output file, which is where the
  inbox reads it; a kind whose rows carry `BUSINESS_DATE` gets the same date in every row, and a master (no date field) is dated by
  its file's name alone (`counterparty.2026-10-29.jsonl.gz`). A row whose own date column differs is rejected, so a file of the wrong
  day cannot replace the right one.
- **Masters are sent every day**, as full snapshots under the date: one row per counterparty per business date is what the contracts
  say, and replace per date keeps it simple. `optional: true` is for the kinds the contract calls "on change" (`risk-factor`), where a
  date without a delivery keeps the last. Whether a master may also skip a day must be confirmed against the reference connectors
  before the runbook promises it ([section 18](#18-sizes-and-build-order), step 0).

### 12.3 Batch ids and restatements

| Field | Kinds that carry it | Rule |
|---|---|---|
| `BATCH_ID` | `scenario-pv`, `pnl-vector`, `fx-scenario` | one per date for the full run (`B-<date>-PVF` today); the FX rows must carry the PVs' batch, so the day file gives one `batch-id` to all three |
| `BATCH_ID` | `position-sensitivity` | its own (`B-SENS-<date>-01`): one full run per date and source |
| `SOURCE_BATCH` | `scenario-set` | the PVs' batch |
| `RESTATEMENT` | `scenario-pv`, `pnl-vector`, `position-sensitivity` | `0` for the date's first delivery |
| `COMPUTED_AT`, `AS_OF` | the same, and `desk-pnl` | the source file's last-modified time in UTC unless given; a restated row carries a later one |

**A restatement** is the day run again for a past date with corrected files and `--restatement 1` (then 2, …): the same batch id, the
restatement number on every row that has the field, a new `COMPUTED_AT`. Replace per date puts the restated set in the lake; the lake's
time travel still reads the date as it was known before. Kinds without these fields are simply sent again. The summary of a restatement
compares with the previous run's summary when it is still in the folder: rows added, removed, changed, per kind.

**A partial correction is not a mode.** To change three counterparties, send the whole counterparty file again. Append exists in the
lake for intraday deltas; the daily CSV run does not use it, so that a date always equals one file.

### 12.4 Add and remove

| The owner wants to | Command |
|---|---|
| add a kind to a date already loaded | `csv_load.py --kind K --csv F --date D --inbox` or `--load --lake L` (checked against the folder's other kinds) |
| replace one kind of a date | the same: replace per date |
| remove a kind's date | `python3 tools/drishti.py lake delete --lake risklake --kind K --date D` (`--dry-run` first); the server is told and drops the date |
| remove one row | send the kind's file again without it |
| remove a whole day | `lake delete` for each kind; `mr_run.py --date D --delete-plan` prints the lines, vectors first and masters last, and runs nothing |

The scripts never delete. Removal has one way, the lake's own, and each kind's generated page prints its exact line.

## 13. The inbox order and the dependency graph

```text
                       ┌──────────────┐
                       │ legal-entity │
                       └──┬────────┬──┘
                 ┌────────┘        └──────────────┐
              ┌──▼───┐                  ┌─────────▼──────────┐
              │ desk │                  │ counterparty-group │
              └──┬───┘                  └─────────┬──────────┘
              ┌──▼───┐                     ┌──────▼───────┐
              │ book │                     │ counterparty │
              └──┬───┘                     └──┬────────┬──┘
                 │                  ┌─────────┘        └────────┐
 ┌──────────────┐│            ┌─────▼─────┐             ┌───────▼──────┐
 │ scenario-set ││            │ agreement │             │ credit-limit │
 └──┬────────┬──┘│            └─────┬─────┘             └──────────────┘
    │        │   │               ┌──▼──┐
┌───▼─────────┐  │               │ csa │
│ fx-scenario │  │               └──┬──┘
└───┬─────────┘  │          ┌───────▼─────┐
    │   ┌────────┘          │ netting-set │
┌───▼───▼─────┐             └──┬───────┬──┘
│ scenario-pv │      ┌─────────┘       └────────┐
└──┬───────┬──┘ ┌────▼───────────────┐  ┌───────▼─────────┐     ┌────────────────┐
   │       │    │ collateral-balance │  │ trade-valuation │     │ simulation-run │
   │       │    └────────────────────┘  └─────────────────┘     └───────┬────────┘
┌──▼──────┐│  ┌─────────────┐                                   ┌───────▼───────┐
│var-limit││  │ risk-factor │                                   │ exposure-path │
└─────────┘│  └──────┬──────┘                                   └───────────────┘
        ┌──▼─────────▼─────────┐        ┌──────────┐
        │ position-sensitivity │        │ desk-pnl │  (after desk)
        └──────────────────────┘        └──────────┘
```

The order of delivery is the topological order of the mapping files' `after:` lists, with a fixed tie-break (masters, then the scenario
kinds, then the large kinds). It replaces the `INBOX_ORDER` constant of `pvf_run.py`:

| # | Kind | # | Kind |
|---|---|---|---|
| 1 | `legal-entity` | 11 | `risk-factor` |
| 2 | `desk` | 12 | `scenario-set` |
| 3 | `book` | 13 | `fx-scenario` |
| 4 | `counterparty-group` | 14 | `scenario-pv` (or `pnl-vector`) |
| 5 | `counterparty` | 15 | `var-limit` |
| 6 | `agreement` | 16 | `position-sensitivity` |
| 7 | `csa` | 17 | `trade-valuation` |
| 8 | `netting-set` | 18 | `simulation-run` |
| 9 | `collateral-balance` | 19 | `exposure-path` |
| 10 | `credit-limit` | 20 | `desk-pnl` |

What the order is for, and what it is not:

- **Hard:** the scenario set before the vectors and the scenario FX (the engine sizes a date's vectors from its set), and
  `simulation-run` before `exposure-path`. These are the only orders a load depends on.
- **Soft:** the rest. The lake does not enforce links, so a netting set loaded before its counterparty is not refused. The order makes
  each load's signal find its masters already there, so the engines reload a date once with everything, rather than answering for a
  minute with half of it.
- **The hand-over** (`--inbox`) has two phases, so that a day arrives together. First every `.jsonl.gz` is copied under a temporary
  visible name and renamed into place: a file without its marker is never touched by the inbox. Then the `.ready` markers are written,
  quickly, in the order above. The inbox takes the whole ready batch of a cycle at once, oldest first, and loads it table by table in
  one run, so the day's kinds are normally one run and the engines reload the date once. A cycle that starts between two markers takes
  the earlier kinds first, which the order makes safe.
- **`--inbox --wait`** additionally waits for each file's report in the inbox's outbox (`<file>.loaded.json`) and stops, naming the
  reason, at the first that lands in `failed/` (`<file>.failed.json`).
- **The direct load** (`--load`) runs `lake build` one file after another in the same order and stops at the first failure.
- **To confirm at build** ([section 18](#18-sizes-and-build-order), step 0): the order in which one inbox run loads the tables of its
  batch. If it is not the order the files became ready in, the scenario set's marker is written first and the rest only after its report
  (`--wait` for that one file), which costs one cycle.

## 14. One way: how this sits beside the loaders that exist

| Existing piece | Its job | Relation to the CSV loader |
|---|---|---|
| **The data inbox and `drishti.py lake build`** | the only way rows enter a lake: JSON Lines in, Delta, Iceberg or files out; routing, business date, replace, undo, signals | unchanged, and the only way still. The scripts stop at a `.jsonl.gz` and its `.ready`; `--inbox` copies them, `--load` calls `lake build --engine java`. They never write a table or know a storage format. Storage parity is therefore inherited, not re-implemented |
| **`drishti.py lake delete`** | removes a kind's dates | the one way to remove; the loader only prints the line |
| **`drishti.py convert`** | CSV, a JSON array or a folder to JSON Lines, cells typed by guessing, no contract | stays the general format converter for data without a contract. For a kind that has a mapping file its help and the data guide point to `csv_load.py`. The two share `csvread.py`, so there is one CSV reader |
| **"CSV input is deprecated" (data guide)** | no connector or loader reads CSV | still true, and this design keeps it true: CSV exists only in front of the loader, as a bank's extract format. The data guide gets one paragraph saying so |
| **`tools/contracts.py`** | the contracts: schema, drift against the data, generated pages | the loader reads the same YAML files through its finder. `mrloader/contract.py` is the one Python checker of a *record* against a contract; `contracts.py check` does not check records one by one today, and should call it rather than grow a second |
| **Source-system modules** (`source-endur`, `source-calypso`, `source-murex`, `source-wss`, `source-swapone`, `source-ici`) | a trading system's trades into the one `trade` kind: connector templates, a field map (`mappings/<system>-trade.yaml`), inbox routing rules | the way trades arrive, and it stays so. Trades are not in waves 1 or 2: neither number needs the trade table. If a system can only give trades as CSV (wave 3), the CSV goes through `csv_load.py --kind trade --source <system>` to that system's table, with the mapping file kept **in the source module** (`modules/source-<system>/mappings/`), so a system's field knowledge has one home |
| **The source modules' `map:` grammar** (canonical field: native path) | renames native JSON fields on the server | a second, smaller mapping grammar already exists. They do different jobs (a rename inside the server; a typed conversion of a file outside it), so they are not merged now. The loader's rule keys are chosen so that the source modules' `map:` could later be expressed in the same grammar ([decision 10](#20-decisions-for-the-owner)) |
| **`tools/packgen`, `build_demo_lakes`** | the demo's synthetic data | untouched. `mr_gen.py` writes CSV *inputs* for trials and tests only; no generator's data file is CSV |

## 15. Documentation: one generated page per kind, one runbook

### 15.1 The page of a kind

**Recommended: `tools/mrloader/docs/<kind>.md`, generated** by `tools/mrloader/kind_docs.py` from the kind's contract and its mapping
file(s); `kind_docs.py --check` fails when a page differs from what would be generated, and runs in the test suite. A page written by
hand would drift from the mapping on the first change; a generated one cannot. This is the method the contract pages
(`contract docs`) and the guides' dataset blocks (`guide_data.py`) already use.

Every page has the same sections, so the owner learns one layout:

1. **What it is and who reads it**: the mapping's `about`, the contract's description, the engines and screens that read the kind, and
   whether a number depends on it.
2. **The command**: the exact `csv_load.py` line with this kind's options, the dry run, and the kind's line in a day file.
3. **The CSV, column by column**: a table from the mapping and the contract: column, required, type and accepted format, the field it
   becomes, the rule in words (*upper-cased, prefixed `CP-`*), the contract's description and example. One table per input file.
4. **A sample**: the committed sample CSV's first rows and the JSON Lines record they become.
5. **What is not loaded**: the refused computed columns and the omitted contract fields, each with its reason.
6. **Checks**: the kind's row rules, date rules and links with their policies; the reject codes it can give, with an example message.
7. **Before and after**: the kinds to load first, the kinds that depend on it.
8. **Repeat, restate, remove**: the three lines for this kind, the `lake delete` line included.
9. **Spreadsheet warnings**: only for this kind's fields at risk (long ids, leading zeros, codes that look like dates).
10. **Source variants**: one sub-section per `<kind>.<source>.yaml`, with that source's column table.

The text between the tables comes from the mapping (`about`, the reasons) and the contract (descriptions, examples, notes); nothing on a
page is typed into the generator. The comments beside a mapping's rules become the "Decision" column, as in today's README table.

`kind_docs.py` also writes `tools/mrloader/docs/README.md`: every kind with a loader, its wave, inputs and order (the table of
[section 3](#3-the-kinds-which-get-a-csv-loader-which-never), kept current), and the kinds that never get one with the reason.

### 15.2 The runbook

`tools/mrloader/docs/DAILY_RUNBOOK.md`, written by hand, short, opening with *Prepare your environment*:

- the files expected each day and the day file;
- the one command; a dry run first; reading the summary table;
- reading a reject report and the five commonest fixes;
- what to do when a kind is refused (fix and run again; `--keep-going`);
- checking the load (the inbox's `loaded/` and `failed/`, Admin → Data loads) and that VaR and exposure answer for the date;
- restating a past date; removing a kind or a day;
- the advice on spreadsheets.

### 15.3 Where the other documents change

One home per fact: the pages above hold the CSV facts, and the guides link to them.

| Document | Change |
|---|---|
| `tools/mrloader/README.md` | the tool and its conventions ([sections 6](#6-the-tool-one-generic-loader), [7](#7-the-mapping-file-of-a-kind), [9](#9-csv-conventions-including-what-excel-does-to-a-file), [10](#10-validation-and-the-reject-report)); the PVF content moves to the generated `scenario-pv` page's PVF variant and a short `pvf` section for the join rules |
| `docs/guides/DATA_GUIDE.md` (section 3) | one paragraph: CSV in front of the loader; link |
| `docs/guides/DATA_LAKE_GUIDE.md` (section 3) | a row "from CSV extracts" in *Loading*; link |
| `docs/guides/VAR_GUIDE.md` (section 36), `docs/guides/EXPOSURE_GUIDE.md` (section 29) | each dataset's entry gains "from CSV: link", through `guide_data.yaml` |
| `docs/contracts/README.md` | section 9 (*Delivery*) names the CSV route |
| Help | one listed page, "Loading from CSV", linking the runbook and the kinds' index |
| `CHANGELOG.md` | with each wave |

## 16. Samples

- **One synthetic CSV per kind** in `tools/mrloader/samples/<kind>.csv` (and its child file where there is one): ten to twenty rows,
  made-up names, no value taken from any real extract. Beside each, `<kind>.expected.jsonl`: the records the loader must produce.
- **The samples form one consistent day.** The same four legal entities, six desks, twelve books, eight counterparties and twenty
  positions appear in every file, so the day loads together and every link resolves. This is what the tests and the pages use.
- **They are generated, then committed.** `mr_gen.py --day DIR --positions 20 --seed 7` writes the whole day; `--positions 2000` writes a
  trial day of realistic size, never committed. The committed samples are regenerated only on purpose, and a test checks they still
  equal the generator's output.
- **Spreadsheet-damaged fixtures** live under `tests/fixtures/`, one small file per row of
  [section 9.3](#93-what-a-spreadsheet-silently-changes) and per dialect (semicolon and decimal comma, a byte-order mark, cp1252, a
  `sep=` line, quoted line breaks, a totals row).
- **Real extracts stay local** under `data/mr/` and `data/pvf/` (ignored by git), as the Endur samples and the PVF files do.

## 17. Tests

| Test | What it proves |
|---|---|
| The reader, table-driven | every row of [sections 9.1](#91-the-file) to [9.3](#93-what-a-spreadsheet-silently-changes): the accepted forms give the value, the refused forms give their code and message |
| The mapping grammar | each rule key, the order of application, nested fields, lists, children, lookups, shared fragments; a bad mapping file is refused before a row is read, naming every problem |
| A golden per kind | `samples/<kind>.csv` gives exactly `<kind>.expected.jsonl`, and every record passes its contract |
| A guard over the kinds | every mapping file has a sample, an expected output and a generated page; every kind of [section 3.5](#35-never) is refused with its reason; no mapping maps a `derived` field |
| Coverage of the contract | every required field of every mapped kind has a rule or a named omission; an omission whose field the contract no longer requires fails (so the stop-gap of [section 5](#5-the-contracts-are-not-ready-for-a-banks-data) shrinks by itself) |
| The reject report | a fixture with one of each problem gives the expected lines: row, column, field, code |
| Links | the sample day resolves every link; a fixture with a broken link of each policy warns or refuses; the limit-node ids equal the server's for the demo date |
| Idempotence | the day run twice gives identical bytes; a restatement changes only the restated fields |
| File names and markers | every output is `<kind>.<date>.<ext>`; `lake build --plan` on each sample file reads the table and the date from the name; a marker has only the known keys, and its `rows` and `sha256` are the file's |
| The hooks | `--inbox`: every file in place before the first marker, the markers in topological order; `--load`: the exact `lake build` command line per file (the command is replaced by a recorder in the test), stopping at the first failure |
| Pages | `kind_docs.py --check` |
| Names | no lower-case attribute name at any depth in any output |
| Loading, small | the sample day loaded into a scratch lake with `--load` (twenty positions, seconds): skipped where the server jar is not built |

**No drill for this tool** (owner, 2026-11-18): its tests and the pre-merge gate are the whole proof. Everything but the last row is
plain Python, runs in seconds and needs no Java, no server and no network; the local size limit (at most a few thousand positions,
one heavy job at a time) is kept by construction. Storage parity needs no test here: the scripts write one format, JSON Lines, and the
lake loader's own parity tests cover what happens next. That a loaded day answers VaR and exposure is checked by hand with the runbook
the first time each wave is used on the owner's stack.

## 18. Sizes and build order

Sizes: **S** up to about 150 lines with tests, **M** about 150 to 500, **L** more. A mapping file is 40 to 80 lines; a sample is data.

| Step | Work | Size | Depends on |
|---|---|---|---|
| 0 | **Confirmations, no code:** how the reference connectors answer a date with no delivery of a master; which lake each kind's table is in on the owner's stack; the order in which one inbox run loads the tables of its batch; the shapes of the owner's real CSVs for limits, sensitivities and counterparties (their headers only) | S | the owner's files |
| 1 | **Contract review** of the sixteen kinds ([section 5](#5-the-contracts-are-not-ready-for-a-banks-data)): presence, enumerations, links; the demo data still passes; contract pages regenerated | M | decision 2 |
| 2 | **The rename and the split:** tools/pvf to `tools/mrloader`; `package.py` and `hooks.py` out of the PVF scripts, writing `<kind>.<date>.jsonl`, `.jsonl.gz` and `.ready` ([section 6.6](#66-what-the-pvf-scripts-should-change)) with `--inbox` and `--load`; `contract.py` general (any kind by name, every type, nested records, `derived` refused); PVF's tests follow the new layout | M | the rename landing |
| 3 | **The generic loader:** `csvread.py`; the mapping additions (inputs, nested fields, lists, fragments, `after`, `on-reject`, `checks`); `csv_load.py`; the reject report and summary; `validate.py` | L (about 1,200 lines with tests) | 2 |
| 4 | **Wave 1, flat kinds:** `var-limit`, `desk-pnl`, `legal-entity`, `desk`, `book` (+ `book.pvf`), `risk-factor`; `scenario-set` and `fx-scenario` moved onto mapping files from the parallel builder's scripts; `scenario-pv.pvf.yaml` including `shared/ids.pvf` | M (eight mapping files, samples) | 1, 3 |
| 5 | **Pages and samples:** `kind_docs.py`, `mr_gen.py`, the committed sample day, the guards | M | 4 |
| 6 | **Children:** `position-sensitivity` (+ its PVF variant), with the streaming group-by for several million rows | M | 3 |
| 7 | **The day:** `links.py`, `mr_run.py`, the day file, the ordered hand-over with `--wait`, the ordered `--load`, the runbook | M | 4 |
| 8 | **Wave 2:** `counterparty-group`, `counterparty`, `agreement`, `csa`, `netting-set`, `collateral-balance`, `credit-limit`, `trade-valuation` | M (eight mapping files, samples) | 1, 3, 7 |
| 9 | **Wave 3**, each on its own once its extract is known: `exposure-path` with `simulation-run` (a streaming join, as PVF's); `pnl-vector`; `pnl-history`; `trade` from CSV | L, M, S, M | decisions 9 and 10 |

### 18.1 Step 0: what the code settles (2026-11-18)

**A date with no delivery of a master.** The masters are read through Delta connectors (`reference-store` of the core pack:
counterparty, counterparty-group, legal-entity, book, desk ...; `legal-store`: agreement, csa) set to `mode.<kind>: effective`. An
effective table answers a date with **each entity's newest row on or before that date, with no look-back limit**
(`DeltaSourcePlugin.candidates`). So a master that skips a day is answered from its last delivery, and the server says so per source
(`SourceRouter.carried`: *reference-store: no load for 2026-10-30, showing 2026-10-29*). Two consequences for the runbook:

- a master may skip a day safely; sending it every day remains the recommendation (decision 13), so the date shown is the date asked;
- **a row left out of today's full file does not disappear**: under `effective`, an entity missing from the newest date is still
  answered from an older one. Removing an entity from a master therefore needs its older dates removed or a status column, not only a
  file without it. The `snapshot` tables (the default: `credit-store` netting-set and credit-limit, `collateral-store`, every
  `risk-store` kind including `risk-factor`) answer from the newest partition on or before the date **within 10 days**
  (`lookback-days`); there, a full file is the whole truth of its date, and a kind not sent for more than 10 days answers nothing.
  `risk-factor` (contract: "a date without a delivery keeps the last") is a snapshot table: re-send it at least every 10 days, or set
  `mode.risk-factor: effective` on the site's connector.

**The order in which one inbox run loads its batch.** `Inbox.look` takes the whole ready batch, sorted by **the data file's last-modified
time, then its name** (not the marker's time). `Cycle.run` groups the plain files by route (pack, kind, table, lake, mode ...), keeping
the order of each group's first file, and loads the groups **one after another** in that order; tars come after every plain file. So the
hand-over orders the load by the order in which it **copies the data files**: `hooks.py` copies the `.jsonl.gz` files in dependency order
(each a new modification time), then writes the markers in the same order. A file is taken only when it and its marker have been
unchanged for `stable-seconds` (30), so the markers written within a second land in one cycle. The `--wait` fallback of
[section 13](#13-the-inbox-order-and-the-dependency-graph) is not needed for the order; it stays for a caller that wants each report.

**The marker's keys.** `ReadyNote.KEYS`: `business-date`, `table`, `pack`, `kind`, `connector`, `mode` (`append` or `replace`),
`new-table`, `key`, `folders`, `sha256`, `rows`, `hold`, `lake`; an unknown key is refused. The marker of
[section 6.4](#64-the-pipeline-of-one-kind) uses only known keys.

**The owner's headers.** Only the PVF headers are available (`portfolio_d2.csv`, `pos_YYYYMMDD.csv`); their columns are what
`kinds/shared/ids.pvf.yaml`, `scenario-pv.pvf.yaml` and `book.pvf.yaml` read. Every other kind ships with its canonical mapping
(`kinds/<kind>.yaml`, the CSV columns named as the contract's fields) and a synthetic sample; a bank's own column names are a mapping
file of its own, given by explicit path (`--mapping FILE`, decision 11).

In total about 2,500 to 3,000 lines of Python with tests for steps 2 to 7, plus the mapping files, samples and generated pages. Steps
4, 5 and 6 can run in parallel once step 3 is in; step 8 is almost only configuration. After step 7 the owner has the daily command for
VaR; after step 8, for exposure's masters. Each step is proven by its tests and the pre-merge gate (no drill), ships with its pages
and CHANGELOG entry, and every file stays far below the
2,500-line cap (the largest, `mapping.py`, should be split into the grammar and the inputs if it passes 600).

The first thing worth building for the owner's daily use is small: steps 2 and 3 with `var-limit` alone give a real second kind end to
end, and prove the design before sixteen mapping files depend on it.

## 19. Risks

| Risk | Effect | Answer |
|---|---|---|
| The master contracts refuse real data | wave 2 cannot load, or loads behind long exception lists | step 1 before any master mapping; the coverage test makes the exceptions shrink |
| Pages show empty roll-ups for CSV-loaded masters | a book page without MtM, a netting set without its trade table | stated in each kind's page; the figures are the parked computed contracts' job (NSET, LIM, GRP, COLL, TVAL), not the loader's. The owner decides when those come back |
| Ids disagree between kinds | limits never shown, sensitivities with no position, exposure not attributed | one shared id fragment per source; the link checks; the sample day's all-links test |
| A day half delivered | the engines answer for a short time from part of the day | convert and check everything before the first hand-over; every file in place before the first marker, so one inbox cycle takes the day; `--wait` |
| A kind too large for one file | parts taken by two inbox cycles, the second replacing the first | one file a kind and date by default; a split date goes as one tar (`--tar`), which loads completely or not at all |
| A spreadsheet damages a file silently | wrong ids or dates loaded | the detections of section 9.3; the patterns of the contracts; the runbook's advice. Rounded long ids cannot be detected: say so plainly |
| The reader's rules grow into guessing | a value read differently than the owner meant | the rule "declared, never guessed": each new convenience is a declared option with a test, or it is refused |
| The mapping grammar grows into a programming language | mappings nobody can read | no expressions, no conditions in rules beyond `map` and the priority list; a kind that needs logic gets a step in front (as the PVF join), not a cleverer rule |
| Two CSV readers, two record checkers | the "one way" rule broken over time | `convert` and the loader share `csvread.py`; `contracts.py` calls `mrloader/contract.py` |
| A large sensitivity or exposure file exhausts memory | the run or the machine fails | streaming read and group-by in key order, memory stated in the summary as PVF does; local trials limited to a few thousand positions |
| Masters must be sent daily but one day's file is missing | the date has no counterparties | step 0 confirms what a missing day means; `required:` in the day file makes the choice explicit per kind |
| The loader's copy of a server rule drifts (the node ids of limits) | a false warning or a missed one | it reads the pack's `hierarchies:` block; a test compares with the server for the demo date; the policy is warn, never refuse |
| The owner's real column names are not known yet | the canonical CSV may not fit the extracts | step 0 collects the headers; a `<kind>.<source>.yaml` per extract costs a file, not code |

## 20. Decisions for the owner

| # | Decision | Recommendation |
|---|---|---|
| 1 | One generic loader with a mapping file per kind, or a script per kind | **The generic loader** (`csv_load.py`) with `kinds/<kind>.yaml`, and one daily driver (`mr_run.py`). PVF's join stays its own step |
| 2 | Review the contracts of the sixteen kinds first (roll-ups to `derived`, demo enumerations removed, result links optional), or load behind `omit-required` lists | **Review first.** It is a moderate piece of work, it is owed anyway (each contract still lists it as a review point), and it removes the largest risk |
| 3 | The first wave | **Wave 1 as listed** (scenario set, FX, PVs, limits, risk factors, sensitivities, desk P&L, legal entity, desk, book), then wave 2 for exposure. Start with `var-limit` alone to prove the loader |
| 4 | "Portfolio" and "position" files | **No kind for either.** Positions arrive on the scenario-PV rows (already so); the portfolio file feeds the `book` master; system baskets stay in Drishti with only their limits as data |
| 5 | `frtb-sensitivity` and the other stored results (`simm`, `sa-ccr`, `cva`, `stress-result`, `pnl-explain`) | **Never by this loader**: they are another system's answers. Sensitivities are `position-sensitivity` |
| 6 | Reverse links a CSV does not carry (a counterparty's list of netting sets, a CSA's netting sets, a desk's books) | **Not built by the loader.** Each is derived from the forward link and belongs to the server; the contract review marks them `derived`. The loader stays a converter |
| 7 | A rejected row in a master or limit file | **Refuse the whole kind** (`on-reject: refuse`), fix and run again; `skip` with a ceiling only for the large kinds. Unresolved links: **warn**, except those of section 11 that change a number, which refuse |
| 8 | The load files | **Per kind and date: `<kind>.<date>.jsonl`, `<kind>.<date>.jsonl.gz` and its `.ready`** (owner, 2026-11-18), named so the inbox routes them by name; a tar only for a date split into parts. The PVF scripts change to the same ([section 6.6](#66-what-the-pvf-scripts-should-change)) |
| 14 | Loading from the script | **Two optional hooks, no loader of its own:** `--inbox` (copy into the data inbox) and `--load --lake NAME` (calls `drishti.py lake build --engine java`). Without either the run prints the lines |
| 9 | Exposure paths from CSV | **Wait for the extract's shape.** It is the largest table and needs a join of its own, like PVF's; ask the owner for the header and a sample of the simulator's file |
| 10 | Trades from CSV, and the source modules' own `map:` grammar | **Trades stay with the source-system modules**; CSV trades only on request, through the same loader with the mapping kept in the source module. Do not merge the two grammars now |
| 11 | Where a site's own mapping and day files live | the shipped canonical files in `tools/mrloader/kinds/`; a bank's own given by explicit path (`--mapping`, `--day`), kept with the site's settings, never in a hidden or implied place |
| 12 | Where the pages live | **`tools/mrloader/docs/`**, generated, with one Help page and the guides linking to them. The alternative, a docs/guides/csv folder, puts them with the other guides but away from the mapping files they are generated from |
| 13 | Masters every day or on change | **Every day, full snapshot**, as the contracts' grain says; `risk-factor` on change. Revisit after step 0 |
