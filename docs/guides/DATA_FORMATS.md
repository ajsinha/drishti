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
# Data formats: JSON Lines is the standard

Since 2.9.4, **JSON Lines (JSONL) is Drishti's data format**: one JSON object per line, UTF-8, in a `.jsonl` file (`.jsonl.gz` where
the repository gzips large files). Every generator writes it, every loader reads it, and every export and download offers it. This
page says what that means for data coming in, data going out, and the files Drishti keeps.

## 1. The rule

- **One JSON object per line.** No array around the lines, no commas between them, no blank lines needed (a blank line is skipped).
  A document may be as deep as it likes, as long as it stays on its line.
- **UTF-8**, with or without gzip (`.jsonl.gz`).
- **Generating and loading are separate steps.** A generator writes JSONL; a loader puts it into a store. `tools/packgen/trades/make_trades.py`
  is the model: it writes `trades-<date>.jsonl.gz`, then `lake build` loads them. Running a loader never rewrites a pack's samples,
  its Sutra tests or the sample feed.

## 2. The row forms

| Form | One line is | Written by | Read by |
|---|---|---|---|
| **Document** | one entity, as its source holds it: `{"TRADE_ID": "1000015", "LEGS": [...], ...}` | `make_trades.py`, `bulk_trades.py`, `comswap_synth.py`, `note_synth.py`, every pack's `samples/<kind>.jsonl` | `lake build`, `build_demo_lakes.py`, `ingest_jsonl.py`, the file connector, the demo connector |
| **Loader row** | `{"domain", "kind", "id", "date", "doc", "columns"}`: a document (`doc`, as JSON text) of a kind on a business date, with the fields its pack promotes | `make_data.py --jsonl`, `bulk_trades.py --jsonl` | the Java loaders (PostgreSQL, Cassandra, Iceberg, DuckDB, Redis, MongoDB, Aerospike, the file connector's `JsonlLoader`) |
| **Lake row** | `{"domain", "kind", "table", "id", "date", "doc"}`: the same, naming the Delta table (`trade_endur`) | `make_data.py --lake`, `samplegen/lake.py`, the pack generators' `--lake` | `tools/samplegen/load_rows.py` |
| **Sutra test case** | `{"case": "<name>", "document": {...}}` | every generator of Sutra tests (`tests/<sutra>/cases.jsonl`, beside `expect.yaml`) | `sutra test`; the older one-`.json`-a-case folders still read in 2.9.4, with a warning |
| **Catalogue entry** | `{"kind", "id", "title", "subtitle"}` | every pack generator (`samples/catalog.jsonl`; the `catalog.json` array of earlier releases is still read) | the demo connector, the pack tools |
| **Staged lake row** | `{"id", "doc", "columns"}` | `lake build`'s staging (`<lake>/_drishti-lake/staging`), the official results' publishers | Drishti's Java lake writer (`LakeWriter`; the earlier `[id, doc, columns]` array a line still read) |
| **Seal** | `{"key", "count", "hash"}` | the collaboration file store (`<collab dir>/seals.jsonl`) | the same store (the tab-separated `seals.log` of earlier releases still read, never rewritten) |

A **restatement** is a second file of lake rows (`<name>-restated.jsonl`): its rows replace the rows of the same id on their date, as a
second commit, so *known at* shows the correction.

Where the generators put their output:

| Generator | Output |
|---|---|
| `tools/packgen/banking/make_data.py` | `config/packs/<pack>/samples/<kind>.jsonl`; `--lake ROOT`: `<ROOT>/../build/lake-rows/banking-history.jsonl` and `banking-restated.jsonl`, then loaded (`--no-load` stops there); `--jsonl FILE`: loader rows |
| `tools/samplegen/bulk_trades.py` | `<data>/build/bulk/<system>/trades-<date>.jsonl.gz`, then `lake build` (`--no-lake` stops there) |
| `tools/packgen/trades/make_trades.py` | `<data>/build/trades/<system>/trades-<date>.jsonl.gz`, then `lake build` with `--lake` |
| `tools/realdata/normalise.py` | `tools/realdata/dataset/*.jsonl` (a country, group, series or Factbook profile a line) and the `meta.json` manifest |
| the sample feed | `<data>/feeds/fixing.jsonl` (one fixing series a line: `FIX SOFR-HISTORY`) |

## 3. Reading: JSONL first, JSON still, CSV deprecated

The readers keep what they accept: the file connector reads `<kind>.jsonl` (dated `<yyyy-MM-dd>/<kind>.jsonl`) and one file per
entity (`<kind>/<id>.json`); `lake build`, `build_demo_lakes.py`, `data ingest` and the Java loaders read JSON Lines and JSON arrays
(gzipped or not); the demo connector reads `samples/<kind>.jsonl` and the older `samples/<kind>/<id>.json`.

**CSV input is deprecated** and will be removed in a later release. Today only the file connector reads CSV (a feed file
`<kind>/<id>.csv`, served as `{"rows": [...]}`): each such file logs one warning and is named in **Admin → Health**
(`UP (CSV input is deprecated: fixing/X.csv (convert to JSON Lines: drishti.py convert))`) until it is gone.

**`drishti.py convert FILE [--out X.jsonl]`** turns CSV, a JSON array (or one object) or a folder of per-entity `.json` files into
JSON Lines ([CLI_GUIDE.md](CLI_GUIDE.md#convert-csv-a-json-array-or-a-folder-of-json-files-into-json-lines)):

```bash
drishti-console/.venv/bin/python tools/drishti.py convert trades.csv                    # trades.jsonl, a line per row
drishti-console/.venv/bin/python tools/drishti.py convert data/feeds/fixing/SOFR-HISTORY.csv --rows-document --out data/feeds/fixing.jsonl
drishti-console/.venv/bin/python tools/drishti.py convert data/feeds/settlement          # settlement.jsonl, the file name as "id"
```

## 4. Going out: JSON Lines beside every export

Every export and download offers JSON Lines beside the formats it already has: **CSV, Excel, PDF and PNG all stay**, and CSV downloads
are a normal option (the deprecation is for data coming in). A JSONL download is **one row a line, keyed by the same column names
as the CSV's header**, values typed (numbers stay numbers, an empty cell is `null`), with no byte-order mark and no spreadsheet escaping.

| Where | JSON Lines |
|---|---|
| Server API | `GET /api/v1/search/jsonl`; `POST /api/v1/bi/sql` `format: jsonl`; `GET /bi/runs/{id}/result?format=jsonl`, `POST /bi/runs/{id}/export {format: jsonl}`; `POST /api/v1/export/jsonl` (reports, visuals, views); statements `format: jsonl`; subscriptions `format: jsonl` (attached); scheduled reports `format: jsonl` (a webhook gets `application/jsonl`, a folder a `.jsonl` file); `exposure/scope/profile?format=jsonl`, `exposure/captures/{id}/jsonl`, `var/captures/{id}/jsonl`. The media type is `application/jsonl`; a cluster forwards `/search/jsonl` as it does `/search/csv` |
| Console | the **E** export of a table's chart, the Pivot tab, BI runs, a BI report's Export bar, the statement editor, a view's Export menu, a panel's **{}** icon, the history page's **E** menu, search and form results, the exposure explorer (profile and captures), the VaR explorer (the path and captures), the Subscribe form's *Send as* and the scheduled report's *Format*; routes `/export/search.jsonl`, `/export/grid.jsonl`, `/export/compare/...jsonl`, `/export/<kind>/<id>/<panel>.jsonl`, `/export/history/...jsonl`, `/dist/bi/<id>.jsonl`, `/dist/view/<kind>/<id>.jsonl` |
| Command line | `drishti.py history ... --jsonl`, `drishti.py exposure profile ... --jsonl`, `bi runs result --format jsonl`, `bi runs export --format jsonl`, `bi sql --format jsonl`, `bi statement file --format jsonl` |

The compliance export's files are `.jsonl` (they were JSON Lines named `.ndjson`).

**Official results.** VaR and exposure keep publishing their official end-of-day results to Delta (`risk-results/var-official`,
`exposure-official`, `exposure-official-cpty`), and each published date is also written as JSON Lines beside them:
`<root>/risk-results/jsonl/<table>/<date>/<table>-v<table version>.jsonl`, one row a line, one file per commit (a restatement is a new
file). The setting is `drishti.var.official.jsonl` and `drishti.ccr.exposure.official.jsonl`, on by default
([CONFIGURATION.md](../admin/CONFIGURATION.md)). In the content store the row lists are JSON Lines too: `var/official/<date>-membership-v<n>.jsonl`
and `exposure/official/<date>-trades-v<n>.jsonl` (a header line, then a row a line). Records written before 2.9.4 keep their `.json`
and are read as they are: the store is append-only, nothing is rewritten. A single record or capture stays one JSON document.

## 5. What stays as it is, and the guard

Not data, so not JSONL, and they stay as they are: manifests (`testdata.yaml`, `MANIFEST.json`, `meta.json`), application state files,
map GeoJSON, run reports, and storage internals (Delta and Iceberg Parquet, the BI store, the hot files, Arrow between the server
and Python). `tools/iceberg/fixtures.py` writes real Iceberg tables on purpose: it tests the Iceberg reader itself. Human-facing
files stay too: the pack translation sheet (`pack i18n export`, CSV), and every CSV, Excel, PDF and PNG download.

The drill guards the rule: `tools/test_generators_write_jsonl.py` runs the cheap generators into a temporary folder and checks every
data file they write is JSON Lines, and reads the source of every generator for a Delta, Parquet or CSV writer or a `.json`, `.csv`,
`.tsv`, `.parquet` or `.ndjson` data file; its allow-list holds the non-data names above (`MANIFEST.json`, `meta.json`,
`cache-index.json`, `trackers.json`, `expect.yaml` …) and the loaders. `tools/test_exports_offer_jsonl.py` checks that every export
entry point (server, console route, UI menu, command line) offers JSON Lines.

## 6. Arrow to the browser, where it pays

JSON Lines is the data standard (above); **Apache Arrow** is the in-memory and wire format where it pays. Since 2.9.7 (implementation
plan, 2.9.7 row 0) it reaches the browser too, for the answers it was measured to win on, and nowhere else.

**Where Arrow is used.**

| Path | Arrow | Since |
|---|---|---|
| Java to the Python compute workers | Arrow IPC files, memory-mapped | before 2.9.7 ([PYTHON_COMPUTE.md](../architecture/PYTHON_COMPUTE.md)) |
| BI answers to the console's Python, Explore and Perspective-era clients | `?format=arrow` on `POST /bi/reports/{id}/query` and `POST /bi/mq`: the raw rows | 2.1 |
| **Any JSON answer of the two endpoints below, to any client that asks** | `Accept: application/vnd.apache.arrow.stream`: the JSON answer itself, its table as the record batch, the rest in the schema's metadata | **2.9.7** |
| **The VaR explorer's contribution tables by position and by trade, in the browser** | read with Arrow JS (vendored) into typed columns | **2.9.7** |

**Where it deliberately is not.** The **VaR hot files** stay Drishti's own layout (float32 position rows in hierarchy order, memory-mapped,
float64 node totals): summing vectors runs near the machine's memory bandwidth, which Arrow's framing would not improve. **The lake** stays
Parquet (Delta or Iceberg), Arrow's partner on disk. **Shard partials** between servers stay raw vectors of doubles. **Entity pages and
small answers** stay JSON. And the measurements below kept three browser payloads on JSON: **the BI grid**, **the VaR P&L path** and **the
search page**.

**How it works.** The server's `ArrowAnswers` (Java, beside `ArrowIpcWriter`, which writes the stream from Arrow's own message definitions)
and the console's `core/arrow_answer.py` (pyarrow) write the same stream from a JSON answer:

- the answer's table is the record batch: `rows` named by `columns` (`{"shape": "rows"}`), or a list of objects (`{"shape": "records"}`,
  with `absent` naming the keys some objects leave out, so the rebuilt object leaves them out too);
- everything else in the answer (`columns`, `meta`, `totals`, `var`, `sumComponent`, notes...) is JSON in the schema's metadata,
  `drishti.answer`; `drishti.table` says where the table goes back;
- cells are text, 64-bit integers, doubles or booleans, with nulls; text that repeats (at most half as many distinct values as cells,
  16 cells or more: desks, books, currencies, dates) is a **dictionary** column, each value once and 4-byte indices;
- an answer holding anything else (an object or a list in a cell, a column mixing text and numbers, a NaN, a key both null and left out)
  is sent as JSON, with `200`: the caller reads the `Content-Type`.

The console passes the server's Arrow through unchanged; when the server answered JSON (an answer given in time by a BI run, an older
server) it writes the Arrow itself. In the browser, **`drishtiFetchTable(url, opts)`** (`/static/js/drishti-table.js`) asks with
`Accept: application/vnd.apache.arrow.stream, application/json;q=0.9` when the page loaded Arrow JS and `application/json` otherwise,
and returns one column-oriented table whichever format came back: `names`, `length`, `column(name)` (a `Float64Array` for numbers, NaN
for a null; an array for text), `value(name, i)` (the cell as JSON has it), `answer()` (the whole JSON answer, equal cell for cell). Types:
a 64-bit integer becomes a Number when every value of its column is within 2^53 and text past it (JSON would round it); a decimal becomes
exact text; a date `YYYY-MM-DD`; a timestamp ISO text, with `Z` when the column has a time zone and without when it has none. `?tables=json`
in a page's address makes it ask JSON, to compare or to rule Arrow out of a problem. Arrow JS is **Apache Arrow JS 21.1.0**, vendored
(`/static/vendor/apache-arrow/21.1.0/`, 178,919 bytes, 47,797 gzip; [VENDOR.md](../../drishti-console/web/static/vendor/VENDOR.md)),
loaded only by the VaR explorer; nothing comes from a CDN.

**Measured** (2026-10-30; one machine, Chromium through Playwright, a scratch server and console on free ports; bytes as the console sends
them (it does not compress) and gzip -6 as a proxy in front of it would; parse is the median of 7 in the page: `JSON.parse` against reading
the stream and rebuilding the answer; click-to-painted the median of 5 after a warm one, from the click to every row in the page):

| Payload | Rows | JSON bytes (gzip) | Arrow bytes (gzip) | Parse JSON / Arrow | Click-to-painted JSON / Arrow | Kept |
|---|---:|---:|---:|---:|---:|---|
| VaR contributions of a desk **by trade** | 1,281 | 631,873 (96,318) | **244,304 (81,628)**, −61% (−15%) | 0.6 / 1.9 ms | 199 / **168 ms** | **Arrow** |
| VaR contributions of a desk **by position** | 1,173 | 692,324 (87,571) | **243,392 (69,496)**, −65% (−21%) | 0.9 / 3.1 ms | 182 / 227 ms (the run's noise: ±30 ms) | **Arrow** |
| VaR contributions by child (a desk's books) | 3 | 1,081 (463) | 2,304 (933) | 0 / 0.2 ms | 59 ms | JSON |
| VaR P&L path, the bank | 509 points | 21,607 (5,791) | 22,032 (7,312) | 0.1 / 0.4 ms | 132 ms | JSON |
| VaR P&L path, a desk | 509 points | 21,846 (5,733) | 22,360 (7,241) | 0 / 0.2 ms | 46 ms | JSON |
| VaR paths of a node's children (fetched ahead) | 2 scopes | 43,350 (11,427) | 44,608 (14,435) | 0 / 0.6 ms | (ahead of the click) | JSON |
| BI grid, a table visual, 5,000 trades × 9 columns | 5,000 | 536,160 (122,953) | 344,280 (112,656), −36% (−8%) | 1.0 / 2.1 ms | 874 / 1,324 ms | JSON |
| BI grid, 50,000 trades × 9 columns (json-max-rows raised for the test) | 50,000 | 5,355,611 (1,216,777) | 3,394,184 (1,048,434), −37% (−14%) | 9.5 / 12.7 ms | 5,981 / 5,559 ms | JSON |
| Search, `TRD limit 1000` (the most one search returns) | 1,000 | the page is HTML drawn on the server | | | | HTML |

What the numbers say. **The contribution tables by position and by trade** are mostly numbers (component, incremental and marginal VaR,
quantities, PVs) with repeated text (books, currencies, products): Arrow carries them in 35 to 39% of the bytes, the fetch through the
console took 59 to 61 ms against 81 to 91 ms, and the page paints in the same time (the parse costs 1 to 2 ms more, the table's DOM
dominates). Over a bank's network, where 400 KB more is tens of milliseconds, that is the win, so they are Arrow. **The P&L path** is 509
numbers and their 509 dates with the answer around them (histogram, worst 25, a page of ranked rows) in JSON: Arrow is no smaller and its
gzip is larger; it stays JSON. **The BI grid** is wide text (trade ids, desks, books, products, currencies, dates) and V8's `JSON.parse`
is very fast: with dictionaries Arrow is 36% smaller raw but only 8 to 14% gzip, parses slower, and the page (a run's answer converted on
the console, Arrow JS loaded) painted no sooner, slower at 5,000 rows; the paint of tens of thousands of rows is the DOM's, whatever the
format. It stays JSON (the report page was wired to Arrow on trial for the measurement, and the wiring removed); BI's `Accept` answer stays
for API clients that read Arrow. **Search** draws its results on the server as HTML (at most 1,000 rows a search): no table reaches the
browser as data, so there is nothing for Arrow to carry.

Re-measure: `DRISHTI_ARROW_BENCH=1 DRISHTI_ARROW_BENCH_OUT=<file> pytest tests/test_arrow_bench_var_browser.py` (the VaR stack and search)
and `tests/test_arrow_bench_bi_browser.py` (the BI grids; `DRISHTI_ARROW_BENCH_ROWS`, default 50,000), one at a time, from
`drishti-console`. Equality is tested in `drishti-server` `ArrowAnswersTest`, `tests/test_arrow_answer.py` (the console, negotiation and
fallback) and `tests/test_arrow_browser.py` (the explorer drawn the same from Arrow and from JSON, without Arrow JS, on a phone, no other
host asked).
