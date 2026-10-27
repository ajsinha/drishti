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
| **Catalogue entry** | `{"kind", "id", "title", "subtitle"}` | every pack generator (`samples/catalog.jsonl`) | the demo connector, the pack tools |

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

Not data, so not JSONL, and they stay as they are: manifests (`testdata.yaml`, `MANIFEST.json`, `meta.json`), application state files,
map GeoJSON, run reports, and storage internals (Delta and Iceberg Parquet, the BI store, the hot files, Arrow between the server
and Python). `tools/iceberg/fixtures.py` writes real Iceberg tables on purpose: it tests the Iceberg reader itself.
