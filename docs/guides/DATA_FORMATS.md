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
