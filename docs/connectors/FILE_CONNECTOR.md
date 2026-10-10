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
# The file connector: JSON lines on disk, from a demo to a large book

The `file` connector is the simplest store Drishti reads: plain files in a folder. No database, no cluster, no
service to run. Each business day of a kind is one file of JSON lines, one entity per line, which any tool can write
(a script, an export job, `jq`, a spreadsheet macro) and any person can open in a text editor. This document explains
the layout, how the connector serves it quickly even when a day holds many thousands of entities, how to load it, its
limits, and every setting.

For the other stores see [DELTA_CONNECTOR.md](DELTA_CONNECTOR.md), [POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md) and
[AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md); the engine side (how searches, derived kinds and impact use a day's
columns) is in [DELTA_CONNECTOR.md, section 7](DELTA_CONNECTOR.md#7-searches-pick-lists-derived-kinds-and-impact-over-columns).
To build demo data see [DEMO_DATA.md](DEMO_DATA.md).

## Contents

1. [When to use it](#1-when-to-use-it)
2. [The layout](#2-the-layout)
3. [What a line holds](#3-what-a-line-holds)
4. [Declaring promoted fields](#4-declaring-promoted-fields)
5. [Loading](#5-loading)
6. [How the connector reads](#6-how-the-connector-reads)
7. [Dates: snapshot and effective kinds](#7-dates-snapshot-and-effective-kinds)
8. [Memory and size](#8-memory-and-size)
9. [Measured results](#9-measured-results)
10. [Limits and trade-offs](#10-limits-and-trade-offs)
11. [Diagnosing](#11-diagnosing)
12. [Settings](#12-settings)
13. [The older layout: a file per entity](#13-the-older-layout-a-file-per-entity)

---

## 1. When to use it

| Use it for | Prefer another store for |
|---|---|
| a demo on a laptop, a training environment | a book of a million entities a day kept for years (Delta Lake, PostgreSQL) |
| data a team exports as files every night | data that changes during the day and must show at once (Kafka, Redis) |
| reference data kept by hand (books, desks, limits) | many writers at once |
| a first connector, before a database is chosen | queries other systems also run against the same store |

It holds tens of thousands of entities a day comfortably and hundreds of thousands with enough memory; its reads stay
fast because it never reads a whole day to answer one question ([section 6](#6-how-the-connector-reads)).

## 2. The layout

```
<root>/                                   DRISHTI_FILES_ROOT, e.g. ./data/files
└── trading/                              the data domain (the connector's `domain` setting)
    ├── 2026-09-28/
    │   └── trade.jsonl                   every trade of 28 September, one per line
    ├── 2026-09-29/
    │   └── trade.jsonl
    └── 2026-09-30/
        └── trade.jsonl
└── reference/
    ├── 2026-09-30/
    │   ├── counterparty.jsonl
    │   └── book.jsonl
    └── desk.jsonl                        undated: the same for every business date
```

- **One file per kind per business day**, named after the kind, in a folder named after the date (`yyyy-MM-dd`).
- **An undated file** (`<kind>.jsonl` directly in the domain folder) serves every date: right for data that does not
  change by day.
- Lines may be in any order; the connector sorts what it needs in memory.

## 3. What a line holds

A line is one JSON object. Two forms are read, and a file may mix them:

**The loaders' row** (what `make_data.py --jsonl`, `bulk_trades.py --jsonl` and every Drishti loader read and write):

```json
{"domain": "trading", "kind": "trade", "id": "MX-20000001", "date": "2026-09-30",
 "doc": "{\"tradeId\": \"MX-20000001\", \"productType\": \"IRS_FIXFLOAT\", \"mtm\": 1875863, …}",
 "columns": {"mtm": 1875863.0, "book": "BOOK-RATES-3", "counterparty.id": "CP-MERIDIAN-RE", …}}
```

`doc` is the entity's document, as a JSON string or as an object; `columns` carries the pack's promoted fields, so the
connector can index them without reading the document. A row without `columns` has its promoted fields read from
`doc`, in either form.

**A plain document per line**, the simplest file anyone can write:

```json
{"id": "BOOK-RATES-3", "name": "Rates book 3", "desk": "DESK-RATES", "currency": "USD"}
{"id": "BOOK-FX-1", "name": "FX book 1", "desk": "DESK-FX", "currency": "USD"}
```

The id is the field named by `id-field` (`id` by default); promoted fields are read from the document by their paths.

**One line per id.** When an id appears on several lines of a day's file, its **last** line is the entity, as a reload
of the file would leave it: the view, searches, pick lists, derived kinds and reverse lookups all read that line, and
the entity is counted once. The ids repeated are counted (`duplicateIds` in the cache statistics) and named in Health.

**`NaN` and `Infinity`.** Python's `json.dumps` writes `NaN`, `Infinity` and `-Infinity` by default, which strict
JSON does not allow. The connector reads them and takes them as **no value**: a promoted number is empty (as a missing
field is, so `mtm < 0` does not match it and sums leave it out), and the document opened holds `null` there.

**A promoted field of mixed types.** A field that is a number on most lines and text on others (`"mtm": "N/A"`) keeps
each value as the document holds it: numbers as numbers (`1e20` stays `1e20`, it is not clamped to a long), text as
text. A search answered from the columns therefore orders and compares it exactly as one that reads documents
(numbers in order, then text; `mtm > 10000000000000000000` matches the huge numbers and not `"N/A"`), and sums leave the text out.

**Lines that cannot be read** are skipped, not the day: a truncated line, a line that is not a JSON object, a line
without an id, a line longer than `max-document-mb` (64 MB), or a line nested deeper than `max-nesting-depth`
(1000). Each is counted with its line number and why, logged once per version of the file, and shown in Health; the
rest of the day is served. A search answered from such a day carries the reason (see 6.5). (A row with `columns` is
indexed without parsing its `doc`; if that document itself breaks a limit, its columns serve searches and opening it
fails with the limit named.)

## 4. Declaring promoted fields

As for every store, the pack declares the fields searches use, on the connector that stores the kind
(`layout.<kind>.columns`; see [DELTA_CONNECTOR.md, section 4](DELTA_CONNECTOR.md#4-declaring-the-layout-in-a-pack) for
how to choose them). The `files` profile (`SPRING_PROFILES_ACTIVE=files`) switches the banking packs' store connectors
to this plugin and keeps the packs' settings, so the trading pack's 19 promoted trade fields apply unchanged:

```yaml
# drishti-server application-files.yaml (the profile)
drishti:
  sources:
    connectors:
      trading-store: { plugin: file, settings: { root: "${DRISHTI_FILES_ROOT:${drishti.data.dir:./data}/files}" } }
      # … reference-store, market-store, risk-store, credit-store, collateral-store alike
```

The connector takes `<root>/<domain>` as its folder, `domain` coming from the pack (`trading`).

## 5. Loading

```bash
tools/load-files.sh                              # the samples: 1,791 documents x 10 business days, into data/files
tools/load-files.sh --trades 10000               # and a book of 10,000 trades a day for 3 days
tools/load-files.sh /tmp/files --trades 50000    # somewhere else, a larger book
SPRING_PROFILES_ACTIVE=files DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

```
files: wrote 17,910 rows into 460 files under data/files in 0 s
files: wrote 30,000 rows into 3 files under data/files in 2 s
```

`JsonlLoader` (in the plugin) reads the loaders' rows from a file or standard input and copies each line, as it is,
into `<root>/<domain>/<date>/<kind>.jsonl`:

- **A file the stream reaches is replaced whole.** Its lines go to `<kind>.jsonl.tmp` beside it, moved into place in
  one atomic rename at the end, so a running server never reads half a day.
- **Dates are guarded.** A row dated after tomorrow in the business zone (`--future-days N`, 1; `--zone Z`, else
  `DRISHTI_BUSINESS_ZONE`, else `America/New_York`) is not written; the first few are named on standard error and the
  load ends with an error once the other files are in place.
- **At most 256 files are open at once**; a file closed early is reopened for appending.
- A row that would land outside the root (a domain or kind like `../x`) is refused.

Writing files yourself needs no loader: any process that writes a complete `<kind>.jsonl` (ideally to a temporary
name, then renamed) is enough. The connector notices the new file on its next rescan (`rescan-seconds`, 30) and
re-indexes a changed file the next time it is read.

## 6. How the connector reads

### 6.1 Finding the files

Every `rescan-seconds` (30) the connector lists the domain folder: the dated folders and the `.jsonl` files in each,
and the undated `.jsonl` files. Listing folders is cheap; no file is opened.

### 6.2 The index of a day

The first time a day's file is needed, the connector reads it once and keeps an **index**:

- every id, sorted, with the byte offset and length of its line;
- the kind's promoted fields, as columns (numbers as `double[]`, text as `String[]` with repeated values shared).

The file is cut at line ends into up to 8 segments (one per 64 MB) indexed at once on as many threads, each reading
4 MB blocks; a line's envelope is parsed without parsing its document. Indexes are kept by memory (`index-cache-mb`,
1024) and rebuilt when the file is replaced or its size or modification time changes. The newest day of every kind is
indexed during the rescan, in the background, so type-ahead and the first search find it ready.

An index is kept even when its file had unreadable lines, or could not be opened at all (an empty day that Health
names, for example a file without read permission): the file is read again when it changes, not on every request.
`purgeCaches` forgets every index.

### 6.3 Opening one entity

`TRD MX-20000017`: a binary search of the day's sorted ids gives the line's offset and length, and one positioned read
returns exactly that line. No other line is read, however large the file.

The index keeps its file open, so a read always uses offsets into the file the index was built from: a file replaced
by a rename (as the loader does) is still served whole from the old file until the next read sees the change and builds
a new index. A file rewritten in place is caught by checking that the line read is the id asked for; the index is then
built again and the read repeated. A day therefore holds one open file while its index is cached (each day counts at
least 1 MB against `index-cache-mb`, so at most 1,024 files are open by default).

### 6.4 Type-ahead

From memory: the newest day's ids of every kind (all days' ids for effective kinds), in Drishti's sorted type-ahead
index.

### 6.5 Searches, pick lists, derived kinds and impact

From the day's promoted columns in the index, exactly as on Delta Lake: a search is exact over every entity of the day
(`partial: false`) and reads no document. When the day's file had unreadable lines, the columns say so
(`ColumnSet.incomplete()`: "1 unreadable line in trading/2026-09-30/trade.jsonl (line 201: …)"), for the answer to say
`partial` with that reason. A day whose file cannot be read at all is a failure, not "not held": reads of it fail
with `DRS-1003`, and searches over it are `partial` and name the connector and why; another store's data is not used
in its place.

### 6.6 Reverse lookups

"Which trades reference netting set `NS-SUMMIT-NY`?" comes from the promoted text columns: every value equal to the id.
A kind without promoted fields is answered by reading its lines, at most `max-load-rows` (200,000), and matching the id
as a JSON string. A kind in `effective` mode (counterparties, credit limits) is looked up in each entity's version on
the date, as a read shows it: the days on or before the date are read newest first and each entity only in its newest
line, at most `max-load-rows` lines in all. Impact (F8) on a netting set therefore lists its counterparty and
credit-limit groups from files as from any other store.

## 7. Dates: snapshot and effective kinds

| `mode.<kind>` | Meaning | A read on a date |
|---|---|---|
| `snapshot` (default) | every entity every business day | the kind's newest dated file on or before the date (within `lookback-days`, 10); an entity not in it is gone. Else the undated file, if any. |
| `effective` | a line only when an entity changes | the entity's line in the newest file on or before the date that holds it |

A date older than every file (or beyond the lookback) is not held, so the next store configured for the kind is asked:
recent days can come from files and older ones from Delta Lake. A date a dated snapshot file serves **is** held, and
the files are authoritative for it: an entity that day's file does not list is gone on that date, and the store
behind is not asked for it (a trade the recent files dropped is not brought back from the lake; the view gives
`DRS-1001` naming the connector, as a search of the date gives no match). `effective` kinds, undated files and the
older per-entity layout cannot tell what a date holds, so an entity they do not have passes to the next store. A kind the connector has no file or folder of at all is not held on any date (the shipped `file` connector serves every kind until files appear, and is passed over).

## 8. Memory and size

| Per 10,000 trades a day | Size |
|---|---|
| one day's file | 78 MB (about 7.8 KB a trade, the documents as JSON text) |
| one day's index: ids and offsets | about 1 MB |
| one day's index: 19 promoted fields | about 2.5 MB |

Estimated for a million trades a day: a file of about 7.8 GB, an index of about 75 MB of ids and 230 MB of columns,
and indexing a day in roughly 20–30 seconds on 8 threads. Files compress well (`gzip` about 8:1), but compressed files
cannot be read by offset, so the connector reads them uncompressed.

## 9. Measured results

On 2026-10-01, a developer workstation, the banking packs' samples plus 10,000 trades a day for three business days
(274 MB of files), a Drishti server with the `files` profile and `-Xmx2g`, times over HTTP:

| Question | Time |
|---|---|
| write the samples / 30,000 trade-days | under 1 s / 2 s (9 s with generation) |
| server start, every kind's newest day indexed | 8 s |
| type-ahead `TRD CLY-40000` | 50 ms |
| open a trade (view), first / again | 70 ms / 21 ms |
| a trade's document | 18 ms |
| a trade on a past day: first (the day is indexed) / again | 190–240 ms / 17 ms |
| `TRD where mtm < -50m order by mtm` | 27 ms (21 matches of 10,000) |
| `TRD where currency = 'USD' and notional > 500m …` | 26 ms (113 matches) |
| `TRD book=BOOK-RATES-3` | 17 ms (689 matches) |
| desk P&L | 23 ms |
| impact of a netting set (1,282 trades) | 96 ms |
| server heap in use | 321 MB |

Every search answered over all 10,000 trades (`partial: false`).

**The scaling curve, measured with the scale benchmark (2026-10-01).** `tools/bench/scale.sh`
([SCALE_BENCHMARK.md](../admin/SCALE_BENCHMARK.md)) loaded 10,000, 25,000 and 50,000 trades a day over three business
days (plus the banking samples) and asked the same questions over HTTP each time: a 24-thread laptop shared with other
work, a Drishti server with `-Xmx2g`, the `files` profile. Medians of 25 requests; every search exact (`partial: false`,
`scanned` equal to the trades a day). The last column carries a straight-line fit (R² beside it) to a million trades a
day: **an extrapolation from the measured points, not a measurement**; "flat" means the measure does not grow with the
book. This store has not been measured at a million trades a day; the last column is the best figure there is, and only
an order of magnitude.

| | 10,000 | 25,000 | 50,000 | R² | 1,000,000 (**extrapolated**) |
|---|---|---|---|---|---|
| type-ahead `TRD CLY-400` (ms) | 8.7 | 8.9 | 7.8 | 0.01 | flat, about 8.2 ms |
| open a trade (view), first time (ms) | 3.5 | 3.4 | 4.3 | 0.00 | flat, about 4.1 ms |
| a trade's document, today (ms) | 2.0 | 2.4 | 1.1 | 0.39 | flat, about 1.8 ms |
| a trade's document, a past day (ms) | 1.1 | 0.9 | 2.7 | 0.77 | 40.4 ms |
| `TRD where mtm < -50m order by mtm` (ms) | 1.1 | 2.1 | 4.0 | 0.99 | 69.7 ms |
| `TRD where currency = 'USD' and notional > 500m …` (ms) | 1.6 | 3.9 | 7.8 | 1.00 | 154 ms |
| `TRD book=BOOK-RATES-3` (ms) | 1.8 | 4.3 | 7.7 | 0.95 | 131 ms |
| pick list `TRD END-1100` (ms) | 0.8 | 2.0 | 3.2 | 0.98 | 55.4 ms |
| desk P&L `DESK-RATES` (ms) | 4.8 | 5.2 | 3.5 | 0.73 | flat, about 4.8 ms |
| impact of `NS-SUMMIT-NY` (ms) | 16.8 | 19.0 | 17.1 | 0.08 | flat, about 17.9 ms |
| a search on another day, first (ms) | 192 | 213 | 353 | 0.47 | 2,904 ms (weak fit) |
| a search on another day, again (ms) | 1.1 | 2.3 | 4.8 | 0.99 | 87.5 ms |
| load (the whole script) (s) | 9.2 | 11.2 | 15.4 | 1.00 | 164 s |
| store size (MB) | 310 | 688 | 1,322 | 1.00 | 25,362 MB |
| server live heap after a full GC (MB) | 88.9 | 102 | 122 | 1.00 | 893 MB |

Run-to-run variance, requests per second with 8 clients, server start and the other stores side by side:
[SCALE_BENCHMARK.md › Results](../admin/SCALE_BENCHMARK.md#4-results).

## 10. Limits and trade-offs

- **The first read of a day indexes it**: a moment for a small day, tens of seconds for a million lines (an estimate;
  0.2–0.35 s for 10,000 to 50,000 lines in the scale benchmark). The newest day is indexed in the background; older days
  on first use.
- **One process writes.** Files have no transactions: write a day to a temporary name and rename it, as the loader
  does.
- **No compression on disk** (offsets must address the plain file); a large book takes several times the disk of
  Delta Lake or PostgreSQL.
- **Only promoted fields are fast** for searches; others read documents (20,000 at most, `partial`).
- **No time travel** (*known at*): a rewritten file replaces the day. A read *as known at* an instant of a date the
  files may hold is refused with `DRS-1007` (400) naming the connector, never answered with today's file.

## 11. Diagnosing

`GET /api/v1/admin/health`:

```json
{"name": "trading-store", "health": "UP", "cache": {"jsonlKinds": 1, "jsonlDays": 10, "indexedDays": 1, "ids": 10000,
 "indexBuilds": 1, "unreadableLines": 0, "duplicateIds": 0, "unreadableFiles": 0}}
```

A file with problems keeps the connector UP and names the file (the three newest such files, then a count):

```
UP (1 unreadable line in trading/2026-09-30/trade.jsonl (line 201: Unexpected end-of-input in VALUE_STRING);
    3 duplicate ids in trading/2026-09-29/trade.jsonl, the last line of each kept (MX-1, MX-2, MX-3))
```

The same is logged once, as a warning, each time such a file is indexed.

| Symptom | Likely cause | What to do |
|---|---|---|
| `DOWN: no directory …` | the root (or `<root>/<domain>`) does not exist | create it, or fix `root`/`DRISHTI_FILES_ROOT` |
| a new file is not served | the next rescan has not run | wait `rescan-seconds` (30) |
| a kind is missing from type-ahead | its file cannot be read, or its lines have no id field | see Health; set `id-field` for plain documents |
| Health: `unreadable lines in …` | lines truncated, not JSON objects, without an id, or beyond `max-document-mb`/`max-nesting-depth` | fix or rewrite those lines; raise the limit for large documents. The day is served without them, and searches over it say `partial: true` with the reason |
| Health: `duplicate ids in …` | an id on several lines of one day | the last line is served; write each id once |
| Health: `unreadable file …` | the file cannot be opened (permissions) | fix it; it is read again when it changes |
| views of a date fail with `DRS-1003 <connector> failed reading …` | that day's file is there but cannot be opened (permissions, an I/O error) | fix the file; the read is not passed to a store behind this one, whose data may differ. A day's file that is gone is "not held" and the next store answers |
| searches say `partial: true` | a field the query reads is not promoted | add it to `layout.<kind>.columns`, and to the rows' `columns` |
| the first read of an old day is slow | the day is being indexed | expected once; raise `index-cache-mb` to keep more days |

## As a connector file

A connector is a site resource: one YAML file in `config/connectors/`, and the file name is the connector's name. The settings of this document go under `settings:` in that file, with nesting flattened to dotted keys (`layout: {trade: {columns: [...]}}` is `layout.trade.columns`); `${ENV_VAR}` placeholders are resolved when the connector starts, and a credential is only ever an `${ENV_VAR}` or a `file:/path` reference. A pack names the connectors it reads through and may suggest a template; the server writes the template to the file once, at the first start, and the file is then the site's. A complete file:

```yaml
# config/connectors/site-quotes.yaml
plugin: file
kinds: [quote]
description: Quotes from the desk's drop folder
settings:
  root: /srv/desk/lake
  rescan-seconds: '30'
  lookback-days: '10'
```

The file is applied to the running server within seconds, without a restart, and is edited in the editor of your choice, in **Admin → Connectors** (a form generated from this document's settings, a YAML tab, **Test connection**, history) or with `drishti.py connector apply`. The folder, the format, live reload, precedence and the deprecated `drishti.sources.connectors` form are in [CONNECTOR_FILES.md](CONNECTOR_FILES.md). Ready-made files for this store are in [`config/connectors.examples/files/`](../../config/connectors.examples/files).

## 12. Settings

| Setting | Default | Meaning |
|---|---|---|
| `root` | `data/feeds` | the folder; with `domain`, the connector reads `<root>/<domain>` |
| `domain` | none | the data-domain folder under the root |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective` |
| `lookback-days` | `10` | how far back a snapshot read looks for the newest day on or before the date asked |
| `layout.<kind>.columns` | none | the promoted paths |
| `id-field` | `id` | the id of a plain document per line |
| `rescan-seconds` | `30` | how often the folders are listed again |
| `index-cache-mb` | `1024` | memory for days' indexes |
| `max-load-rows` | `200000` | lines a reverse lookup reads for a kind without promoted link fields |
| `max-document-mb` | `64` | the longest line (and so document, or string in it); a longer line is skipped and counted |
| `max-nesting-depth` | `1000` | the deepest nesting of objects and arrays in a line; a deeper line is skipped and counted |
| `source-name` | `file` | the name shown in provenance and Health |

`JsonlLoader` takes the input (`FILE` or `-`), the root, `--future-days N` and `--zone Z`; `tools/load-files.sh [root] [--trades N] [--days D]` runs
it.

## 13. The older layout: a file per entity

The connector still reads one file per entity, `<root>/<kind>/<id>.json` (or `.csv`) and dated
`<root>/<yyyy-MM-dd>/<kind>/<id>.json`, as the public-data feeds use (`data/feeds/fixing/SOFR-HISTORY.csv`). It suits
a handful of documents; for more than a few thousand entities a day use JSON lines, since a file per entity means a
file system entry per entity per day. When a kind has JSON-lines files they are read first.

---

## Appendix: walk-through and worked examples

Worked examples of the older file-per-entity layout ([section 13](#13-the-older-layout-a-file-per-entity)). For
more than a handful of entities use JSON lines ([section 2](#2-the-layout), [section 5](#5-loading)).

### Walk-through, step by step

#### The situation

Your credit system writes one JSON file per credit limit every evening into a shared folder,
`/srv/drops/limits`. You want them in Drishti as `credit-limit` entities (mnemonic `LIM`, from the
`counterparty-risk` pack), with history by date.

#### The data

The kind is the folder name, the id is the file name without `.json` (or `.csv`). A folder named
`yyyy-MM-dd` holds that business date's files; files outside a date folder are undated.

```text
/srv/drops/limits/
├── credit-limit/
│   └── LIM-HARBOURVIEW.json          undated: served when no dated file is found
├── 2026-09-29/
│   └── credit-limit/
│       └── LIM-HARBOURVIEW.json      the limit as at 29 September
└── 2026-09-30/
    └── credit-limit/
        └── LIM-HARBOURVIEW.json      the limit as at 30 September
```

`2026-09-30/credit-limit/LIM-HARBOURVIEW.json`:

```json
{
  "limitId": "LIM-HARBOURVIEW",
  "counterpartyName": "Harbourview Capital LLP",
  "counterparty": "CP-HARBOURVIEW",
  "measure": "PFE 95 peak",
  "limit": 250000000.0,
  "used": 187500000,
  "utilisation": 0.75,
  "status": "Within limit",
  "approvedBy": "Credit Risk Committee",
  "reviewDue": "2027-03-31"
}
```

A CSV file needs a header row and becomes `{"rows": [...]}`, numbers and booleans typed. `make_data.py` writes one
(nothing under `data/` is in git): `data/feeds/fixing/SOFR-HISTORY.csv` starts

```text
date,rate,volume_bn
2026-09-01,3.95,1910
2026-09-02,3.95,1879
```

and is served as `{"rows": [{"date": "2026-09-01", "rate": 3.95, "volume_bn": 1910}, …]}`.

#### Configure it

**Site form**, the connector file `config/connectors/limits-drop.yaml` (the file name is the connector's name):

```yaml
plugin: file                       # the file plugin
kinds: [credit-limit]              # serve only credit limits from this folder
description: Credit limits dropped by the limits system
settings:
  root: ${LIMITS_DROP_DIR:/srv/drops/limits}   # the folder; an environment variable can move it
  rescan-seconds: '30'             # how often new date folders and new ids (for search) are listed
  lookback-days: '5'               # a picked date may fall back at most 5 days to an older date folder
```

**Pack form** (`config/packs/<your-pack>/pack.yaml`), the same connector suggested by a pack as a template (written to the file above at the first start if
the site has none):

```yaml
connectors:
  limits-drop:                             # one entry per connector
    plugin: file
    kinds:
    - credit-limit                         # a list: a file's `kinds:` replaces it whole
    settings:
      root: ${LIMITS_DROP_DIR:/srv/drops/limits}
      rescan-seconds: 30
      lookback-days: 5
```

No route is needed: the pack routes `credit-limit` to `credit-store` (the lake), and the lake simply does not hold
`LIM-HARBOURVIEW`, so the read passes on to `limits-drop`. Add `routes: { credit-limit: limits-drop }` only if the
folder should be asked *before* the lake for every credit limit.

The shipped `application.yaml` also runs the plugin as itself (see below); for the simplest setup, point
`DRISHTI_FEEDS` at your folder.

#### Try it

```bash
mkdir -p /tmp/limits/credit-limit /tmp/limits/2026-09-30/credit-limit
cat > /tmp/limits/2026-09-30/credit-limit/LIM-HARBOURVIEW.json <<'EOF'
{"limitId": "LIM-HARBOURVIEW", "counterpartyName": "Harbourview Capital LLP", "counterparty": "CP-HARBOURVIEW",
 "measure": "PFE 95 peak", "limit": 250000000.0, "used": 187500000, "utilisation": 0.75, "status": "Within limit"}
EOF
LIMITS_DROP_DIR=/tmp/limits java -jar drishti-server/target/drishti-server-*-exec.jar
```

```bash
curl -s "http://localhost:18480/api/v1/entities/credit-limit/LIM-HARBOURVIEW/raw?asOf=2026-09-30" | jq -c .provenance
```

You should see `"source":"limits-drop"`, `"live":false` and `"businessDate":"2026-09-30"`; `generation` is the
file's modification time in milliseconds.

#### In the terminal

Type `LIM LIM-HARB`: the type-ahead offers `LIM-HARBOURVIEW` with the subtitle `credit-limit · limits-drop`. Press
Enter (or type `LIM LIM-HARBOURVIEW <GO>`). *How this view was built* names `limits-drop`. With the full layout above,
pick 29 September in the top bar and the 29 September file answers. Had the 30 September folder lacked the file, a
read for 30 September would take 29 September's, and the view would say *Latest data on or before 2026-09-30 is from
2026-09-29.* For a date with no date folder on or before it within `lookback-days`, the undated `credit-limit/`
folder answers (if it has the file), and the view says *No data held for …: the current data of
limits-drop, a source that keeps no dates*, because that document has no business date.

#### Health, and when the folder goes away

`health` is `UP`, or `DOWN: no directory /srv/drops/limits` when the folder is missing (an unmounted share). There is
no connection to lose: each read opens the file afresh. While the folder is missing, reads find nothing
(`DRS-1001 no source holds credit-limit/LIM-HARBOURVIEW`); when it comes back, reads work at once and search catches
up at the next rescan.

#### Common errors

| You see | Cause | Fix |
|---|---|---|
| `DRS-1001 no source holds credit-limit/LIM-X` | the file is not at `<root>/<kind>/<id>.json` or `<root>/<date>/<kind>/<id>.json` | check the kind folder spelling (`credit-limit`, not `credit_limit` or `limits`) |
| a new date folder is ignored for a few seconds | date folders are listed every `rescan-seconds` | wait, or lower `rescan-seconds` |
| the type-ahead does not offer a new file | search is rebuilt every `rescan-seconds` | wait; the read itself works at once |
| an old date shows undated data | the picked date is more than `lookback-days` after the newest date folder holding the file | raise `lookback-days`, or keep a file per date |
| an id with `/` or `..` is not found | ids that would leave the root are refused | use plain ids |

### Configuration by example

End-of-day files dropped in a folder by another system: JSON documents or CSV tables, optionally one folder per
business date. No database, no service; a rewritten file is newer data.

**Configuration.** The shipped `application.yaml` runs it as itself:

```yaml
drishti:
  sources:
    plugins:
      file:
        enabled: true
        settings:
          root: ${DRISHTI_FEEDS:${drishti.data.dir:./data}/feeds}   # the folder to serve
          source-name: feed-file                # shown in provenance (the route name stays `file`)
          rescan-seconds: 30                    # how often the search index and the dated folders are re-listed
          lookback-days: 10                     # how far back a picked date may fall to an older dated folder
```

A site (or a pack, as a suggested template) can run more folders as named connectors, each one a file. `config/connectors/eod-futures.yaml`:

```yaml
plugin: file
kinds: [settlement]                           # only this kind is read from the folder
settings:
  root: ${EOD_FUTURES_DIR:/data/eod/futures}
  lookback-days: '5'
```

and the pack that reads it names it and routes the kind (`config/packs/<pack>/pack.yaml`):

```yaml
connectors: [eod-futures]
routes:
  settlement: eod-futures                     # try this connector first for settlements
```

Every setting is in [section 12](#12-settings).

**The data.**

```text
data/feeds/
├── fixing/
│   └── SOFR-HISTORY.csv            undated: kind "fixing", id "SOFR-HISTORY"
├── settlement/
│   └── CL-DEC26.json               undated: kind "settlement", id "CL-DEC26"
├── 2026-09-29/
│   └── settlement/CL-DEC26.json    dated: the settlement for 29 September
└── 2026-09-30/
    └── settlement/CL-DEC26.json
```

The kind, the id, the CSV form and the dated folders are as in the walk-through above.

Business dates: a read for a date takes the newest dated folder on or before it (within `lookback-days`) that has
the file, then the undated folder; Live takes the newest dated folder that has it, then the undated one. The
document's business date is its folder's date (none for the undated folder). Generation is the file's modification
time. An id containing `..` or a path separator that would leave the root is refused.

**Try it.** `data/feeds/fixing/SOFR-HISTORY.csv` ships with the repository; add your own files and wait for the
next rescan (search only; reads find new files at once).

**What the user sees.** `FIX SOFR-HISTORY <GO>` with the finance pack (its `FIX` mnemonic is kind `fixing`); laid
out by inference. Health: `UP`, or `DOWN: no directory <root>`.
