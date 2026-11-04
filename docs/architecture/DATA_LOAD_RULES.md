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

# Data load rules: table, pack, business date and mode of every load

**Status: decided (product owner, 2026-11-04).** One set of rules for every way data reaches the lake: the Python command line
(`drishti.py lake build`, `tools/ingest_jsonl.py`), the Java parallel loader (`drishti-lakeload`) and the data inbox
(`drishti-dataload`, [DATALOAD_INBOX.md](DATALOAD_INBOX.md)). Both languages read one shared list of example names with their expected
outcome (`tools/lake/testdata/file-dates/names.json`), so the two cannot drift.

## 1. Principles

1. **The business date comes from outside the data, never from a row.** It is the Delta (and Iceberg) partition, so whoever sends the
   data decides it: the command line, the `.ready` file, the file's name, or today. A row's own date field is never used to choose
   the partition or to refuse a file; at most the report counts rows whose own date field differs.
2. **A plain file is one table and one business date.** A `.jsonl` file, or its `.gz` or `.zip`, never holds several tables or
   several dates. A **tar archive** is the one container for several tables and several dates.
3. **The `.ready` file is the control file.** It says that the data file is complete, and it can override what the name says.
4. **Nothing is guessed.** A name, an archive or a `.ready` file that does not follow these rules is refused, before anything is
   written, with every reason and what to do.
5. **Every load can be undone.** Every row carries the id of the load that wrote it.

## 2. What arrives

| Arrives | Table and date from | Example |
|---|---|---|
| A plain file: `.jsonl`, `.jsonl.gz`, `.jsonl.zip` (a zip of one table's files) | the name | `trades.2026-11-03.jsonl.gz` |
| A tar archive: `.tar`, `.tgz`, `.tar.gz`, `.tar.zip` | its folders, `<table>/<YYYY-MM-DD>/<files>.jsonl[.gz]` | `eod.tar.gz` holding `trades/2026-11-03/…` and `deals/2026-11-03/…` |
| `<file>.ready` | the control file of one data file (section 5) | `trades.2026-11-03.jsonl.gz.ready` or `trades.2026-11-03.ready` |
| `ready.all` | releases every data file in the inbox for one cycle | |

### 2.1 The name

```
<table>[.<date>].<extension>
```

- **Table**: letters, digits, `_` and `-`; never a `.`. Stored lowercase (Windows paths do not tell `Trades` from `trades`).
- **Date**, when present, is the second dot-separated part: `YYYY-MM-DD` or `YYYYMMDD`, a real calendar date, not in the future.
- **Extension**: `.jsonl`, `.jsonl.gz`, `.jsonl.zip`, `.tar`, `.tgz`, `.tar.gz`, `.tar.zip`; also `.ndjson`, `.json` and `.zst` (`.ndjson.gz`,
  `.jsonl.zst`, ...) and a bare `.gz` or `.zip` (`trades.2026-11-03.gz`); case does not matter.
- Anything else in the second part (neither a date nor an extension) is refused: `trades.final.jsonl` says *"final" is neither a
  date (YYYY-MM-DD) nor an extension*.

| Name | Table | Date |
|---|---|---|
| `trades.jsonl` | trades | business today |
| `trades.2026-11-03.jsonl` | trades | 2026-11-03 |
| `trades.20261103.jsonl.gz` | trades | 2026-11-03 |
| `trades.2026-11-03.jsonl.zip` | trades | 2026-11-03 |
| `trade_endur.2026-11-03.jsonl.gz` | trade_endur | 2026-11-03 |
| `eod.tar.gz` | from its folders | from its folders |
| `trades.2026-13-03.jsonl` | refused: not a date | |
| `trades.final.jsonl` | refused | |

**Business today** is the business date the server uses: today in the server's business time zone, and on a Saturday or Sunday the
Friday before.

## 3. How each value is decided

First match wins.

| Value | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|
| **Business date** | command line `--as-of` | `.ready` `business-date:` | | the name's date | business today |
| **Table** | `--table` | `.ready` `table:` | an inbox rule's `table:` | the name's table | (no table: refused) |
| **Pack** (its lake, domain and layout) | `--pack` | `.ready` `pack:` | an inbox rule's `pack:` and `kind:` | the pack that already has a table of that name | a pack of that name with a single table |
| **Mode** | `--mode` | `.ready` `mode:` | | the inbox's `default-mode` | **append** |

An inbox rule (`config/dataload.yaml`: a glob on the file name to a pack, kind and table) applies when no `.ready` or command-line
value is given, before the name's table is looked up.

- A table found in **two** packs is refused unless the pack is given; the refusal lists the candidates.
- An **unknown** table is refused, listing the known ones, unless creating it is allowed (section 7).
- The load report says where each value came from: *date 2026-11-03 from the name; pack core from the .ready file*.

### 3.1 Tar archives

The folders decide each table and date, and **nothing overrides them**: `--as-of`, `--table`, or `business-date:`/`table:` in the
archive's `.ready` file are refused (*a tar archive names its own tables and dates*). For each table folder, the pack is decided:

1. `folders:` in the archive's `.ready` file (`folders: {trades: core, deals: trading}`);
2. else the pack that already has a table of that name;
3. else a pack of that name with a single table;
4. else the whole archive is refused, naming the folder. Two packs holding that table: refused unless `folders:` settles it.

Different tables of one archive may belong to different packs, so one archive may write to several lakes.

## 4. The load, step by step

Nothing is written before step 4.

1. **Plan.** Every value of section 3, with its source. A tar is listed without unpacking it: each first-level folder a table, each
   second-level folder a date. `--plan` on the command line, and the inbox's preview, stop here and show it.
2. **Check.**
   - The name follows section 2.1; the `.ready` file holds only known keys with valid values.
   - `sha256` and `rows` from the `.ready` file match.
   - A tar's layout is exact: no file at the top, no third folder level, every second-level folder a date, no absolute path, no `..`,
     no links. Operating-system litter is ignored (`__MACOSX/`, `._*`, `.DS_Store`, `Thumbs.db`).
   - Every table resolves to one pack (or is allowed as new); every table has its id field.
   - A failure sends the whole file (with its `.ready`) to `failed/`, with every problem listed.
3. **Empty.** An empty file, an empty archive, or an empty date folder is **ignored**: nothing is written, no partition is touched,
   and the report says *empty: ignored*.
4. **Write.** One commit per table and date.
   - Files of one table and date in one cycle (two plain files, or several files in one date folder) go into one commit.
   - Every row gets `_load_id` (one id per data file, shared by every commit of an archive) and `_source_file`.
   - A table made before these two columns gains them on its first load: two nullable columns are added to its schema (Delta: one
     metadata commit, as a merge-schema write does; Iceberg: the columns added). No file is rewritten. Its earlier rows have no load id
     and count as the oldest load, so a newer load's copy of an id wins. An undo reads only the `_load_id` column of files without
     statistics for it; for a file written before the column, that read touches only the footer.
   - An archive commits its tables and dates in a fixed order (table, then date).
   - A store loader's retention (`--keep-days`, `--keep-months`, `--ttl-days`) counts back from the newest business date of the
     load: a plain file's date, or a tar's newest folder. It never counts from the business today or from the newest date the store
     holds. `--as-of` means only the business date of a plain file.
5. **Roll back on failure.** If a commit of an archive fails, the commits already made by that load are undone (section 6.3), so an
   archive loads completely or not at all. The report says what failed and what was rolled back.
6. **Tell and move.** The server gets one data-load signal per pack, table and date. The data file, its `.ready` file and the
   report go to `outbox/<day>/<run>/`.

Archives are read as a stream, one table and date at a time: a large archive needs neither its size in memory nor on disk.

## 5. The `.ready` file

YAML or JSON. Empty is fine: it then only says the data file is complete.

```yaml
business-date: 2026-11-03      # override of the date (refused for a tar)
table: trades                  # override of the table (refused for a tar)
pack: core                     # the pack whose lake and layout the table uses
kind: trade                    # the pack's kind, when the pack has several and the table name does not say
connector: trade-endur         # the connector, when the pack has several for that kind
mode: append                   # append (the default) or replace
new-table: true                # allow this load to create a table that does not exist yet
key: TRADE_ID                  # the id field (a new table needs it; default: the pack's key, else "id")
folders: {trades: core, deals: trading}   # a tar only: table folder -> pack
sha256: 9f2c...                # checked before loading
rows: 1250000                  # checked before loading (a tar: the total of every file)
hold: true                     # do not load yet: the file waits until released (Admin, or the key removed)
```

- **An unknown key is refused**, so a typo never goes unnoticed.
- **Pairing.** A `.ready` file is named after its data file in full (`trades.2026-11-03.jsonl.gz.ready`) or without the data
  extension (`trades.2026-11-03.ready`, `eod.tar.ready`). A short name that could release two data files (`trades.ready` beside
  `trades.jsonl` and `trades.jsonl.gz`) releases neither, and the inbox says why. A data file without its `.ready` is never touched,
  unless `ready.all` is in the inbox.
- **The command-line loaders read it too.** A `.ready` file beside a file given on the command line is honoured, below the command
  line's own options: one override mechanism everywhere.
- A bad `.ready` file (not YAML or JSON, an unknown key, a bad date, a checksum or row count that does not match) sends its data file
  to `failed/` before anything is written.

## 6. Modes, repeats and corrections

### 6.1 Append (the default)

The rows are added to the table and date. Within one load, rows with the same id follow the loader's existing rule; across loads, an
id may occur more than once on a date, and readers take the row of the **newest load**.

### 6.2 Replace

The table and date are replaced in one commit: the way to correct a day (`mode: replace` in the `.ready` file, or `--mode replace`).

### 6.3 Repeats and undo

- **The same file again** (the same SHA-256) is not loaded twice: it goes to the outbox marked *already loaded in run …*.
- **Undo a load**: `drishti.py lake undo <load-id>` or Admin → Data loads → Undo removes exactly that load's rows, in one commit per
  table and date (a replace is undone by restoring the date's previous version). An archive's commits are undone together.

## 7. New tables

- Allowed only when asked for: `new-table: true` in the `.ready` file or `--new-table`; and only when the inbox allows new tables
  at all (`new-tables: true`). Otherwise an unknown table is refused, listing the known tables.
- The table is created in the pack's lake and domain (the pack from section 3), with the generic layout (`id`, `doc`,
  `business_date`, `_load_id`, `_source_file`), partitioned by business date.
- Its id field: `key:`, `--key`, else a field named `id`; none: refused.
- The report says *new table X created; no pack serves it yet*. The data is in the lake; a pack declares it as a kind when it is
  wanted on screen.

## 8. Controls

| Where | What |
|---|---|
| **Inbox settings** (`drishti.dataload.*`, [CONFIGURATION.md](../admin/CONFIGURATION.md)) | `default-mode` (append), `new-tables` (whether a load may create a table at all), the business time zone, the largest file accepted, the cycle interval |
| **Admin → Data loads** | the files waiting, each with its plan (table, pack, date, mode, and where each came from); hold and release; retry a failed file; undo a load; the engine's switch |
| **Command line**, the same options in Python and Java | `--as-of`, `--table`, `--pack`, `--kind`, `--mode`, `--new-table`, `--key`, `--plan` |

## 9. Audit

- **Every load has a report**: the file and its SHA-256, the plan with the source of each value, the rows per table and date, the
  rows skipped or refused and why, the rows whose own date field differs (informational), what was committed, rolled back or ignored.
- **Every partition can be traced** to the loads that wrote it through `_load_id` and `_source_file`.
- **The ledger** keeps the history of each file: claimed, loaded, skipped, ignored (empty), failed, rolled back, undone.

## 10. Our own data and tools

- The generators and refresh scripts write dated names (`<table>.<YYYY-MM-DD>.jsonl.gz`), or tars with date folders where one
  output holds several dates (for example a desk's daily P&L over a year).
- The demo lake builder, the VaR test data, the benchmarks and the intraday tools load those names, or pass `--as-of` explicitly;
  callers that need a date replaced say `mode: replace`.
- The conversion is checked: the same rows per table and date before and after (counts and checksums), the same demo lake, the same
  VaR.

## 11. Decisions

| # | Question | Decision |
|---|---|---|
| L1 | Where does the business date come from? | Outside the data only: command line, `.ready`, the name, business today; never a row |
| L2 | What may one file hold? | A plain file: one table, one date. A tar: any tables and dates, as `<table>/<YYYY-MM-DD>/` folders |
| L3 | The name | `<table>[.<date>].<extension>`; no `.` in a table name; YYYY-MM-DD or YYYYMMDD |
| L4 | Overrides | Command line > `.ready` > name; a tar's tables and dates cannot be overridden |
| L5 | Empty files and archives | Ignored, nothing touched |
| L6 | Pack of a table | `--pack`/`.ready` (`folders:` for a tar) > the pack that has the table > a pack of that name with one table |
| L7 | Default mode | Append; replace on request |
| L8 | An archive that fails part-way | Rolled back: all or nothing |
| L9 | New tables | Allowed when asked for (`new-table: true`) and allowed by the inbox |
| L10 | Undo | By load id, from the command line and Admin |
