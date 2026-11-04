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

# Native lake writes: loading millions of rows a second (research)

**Status: research, not planned for a release.** No code is written. This document records the target, where the time goes today,
the options with a bundled native library, and the measurements that would decide between them. A design follows only after the
measurements and the product owner's decision.

## 1. The goal

Load data into the lake (Delta first, Iceberg after) at **millions of rows a second** on one server, through the data inbox and the
loaders, with the same guarantees as today: JSON Lines in, the lake configuration's partitions and load mode (replace by default,
append when configured), one commit per table and date, the same table contents to the last bit, and the server told when data has
moved.

## 2. Where we are

From [BENCHMARK.md](../admin/BENCHMARK.md) sections 7.1 and 7.2 (the VaR scenario vectors, 510 doubles a row):

| Measure | Today |
|---|---|
| The Python path, 400,000 vector rows (200,000 positions, two dates) | 68.4 s: about 5,800 rows a second |
| The Java loader, the same 400,000 rows | 7.2 s: about 55,000 rows a second |
| The Java loader, 2,000,000 vector rows (1,000,000 positions, two dates) | 20.0 s: about 100,000 rows a second |
| The Java parallel loader, 10,000 positions, warm | 34,000 to 52,000 rows a second (180 to 280 MB/s of JSON) |
| Parse, per thread (JSON → record, 510 doubles) | 30,000 rows a second (about 165 MB/s of JSON) |
| Parquet write, per thread (Snappy, BYTE_STREAM_SPLIT, ZSTD) | 24,500 rows a second |
| Commit | 8 ms a date |

The commit is not the cost; **parsing JSON and writing Parquet are.** Any faster path has to make those two faster.

## 3. What "millions a second" means in bytes

The answer depends on how wide a row is.

| Row | JSON a row | Parquet a row | At 1,000,000 rows a second |
|---|---|---|---|
| A VaR scenario vector (510 doubles) | about 5.4 KB | about 1.4 KB | **5.4 GB/s of JSON parsed** (510 million numbers a second) and 1.4 GB/s written |
| A trade or position (about 30 fields) | about 0.5 KB | about 0.1 KB | 0.5 GB/s of JSON parsed and 0.1 GB/s written |

- **Narrow rows** (trades, positions, market data): millions a second on one server is realistic with a vectorized, multi-threaded
  engine.
- **Vector rows**: one million a second means parsing about half a billion decimal numbers a second. That needs every core of a large
  server, and even then text is the limit. **For vectors, the input format matters more than the library.** A binary input
  (Arrow IPC or Parquet, for example through Arrow Flight `DoPut`) skips the parsing entirely. That conflicts with the rule that JSON
  Lines is the data standard; section 7 describes an encoding that keeps the rule (Base64 vectors inside JSON Lines) and the
  binary file formats, which are open questions (section 9).

## 4. Delta's own limits

These hold whatever library writes the files.

- **One commit per batch, not per row.** Delta commits are serialised, and each one is a log entry. Millions of rows a second means
  large batches (one table and date, or a time window) committed once. That is already how the loaders work.
- **File size.** Files of 128 MB to 1 GB keep reads fast; many small files slow every reader. A fast writer must still produce few,
  large files (or compact after).
- **Replace mode.** A replace removes the partition's old files and adds the new ones in one commit. Any library chosen must support
  that, not only blind appends.
- **Concurrent writers** to one table need Delta's optimistic concurrency; one writer per table and date avoids the question.

## 5. The options

| Option | What it is | Speed it could reach | Fit |
|---|---|---|---|
| **A. DuckDB for JSON → Parquet, Delta Kernel Java commits** | DuckDB's Java (JDBC) jar bundles its native engine for Linux and Windows. Its JSON reader and Parquet writer are vectorized and multi-threaded. Drishti keeps writing the Delta log with Delta Kernel Java (what it uses today), so replace mode, partitions and the lake configuration are unchanged | to be measured; the likeliest large gain for the least work | **Recommended first spike.** The same bundled-native pattern as OpenBLAS (Java fallback, loud warning, Admin switch). Its extensions normally download at run time; offline machines need them bundled |
| **B. delta-rs (Rust) through our own JNI or FFM bridge** | The Rust Delta library behind Python's `deltalake` (our test fixtures use it). There is no Java binding; .NET's binding passes data through Arrow's C Data Interface without copying | high for writing; it does not parse JSON itself (Arrow batches go in) | Most effort: a Rust bridge of our own, a Rust toolchain in the build, native libraries per platform to maintain. Only if A disappoints |
| **C. delta-kernel-rs** | Delta's official Rust kernel, with a C interface | high | Its write path supports mainly blind appends today; replace mode is the default here. Not ready |
| **D. DuckDB's Delta extension writing the table itself** | `INSERT INTO` an attached Delta table (added in 2026) | high | Appends only, as documented; replace mode and the lake configuration's settings would sit outside Drishti. Not recommended over A |
| **E. simdjson-java** | A pure-Java SIMD JSON parser | parse stage only, perhaps 2 to 4 times | No native code, low risk. Already named in BENCHMARK.md as the parse stage's candidate. Worth doing whatever else is chosen |
| **F. A binary input path** | Arrow IPC or Parquet files in the inbox, or Arrow Flight `DoPut` | the fastest for vector rows: no parsing | Against the JSON Lines rule; a decision for the product owner |
| **G. Base64 vectors inside JSON Lines** | the vector field carries its numbers as Base64 binary (section 7) | about 5 to 10 times on the vectors' parse | Keeps the JSON Lines rule |

## 6. What any native path must keep

- **The same table, to the bit.** The spike compares the tables written by today's loader and by the candidate: schema, partitions,
  row count, every value, and the files' statistics.
- **A pure-Java fallback.** If the native library does not load, the loader runs on today's Java path: a loud warning, health
  DEGRADED, an Admin switch to choose Java, never a failure to start (the rules decided for OpenBLAS).
- **Windows and offline.** A DLL unpacked by Java is allowed on the Windows QA machine; nothing may be downloaded at run time.
- **Memory bounded** by the loader's existing budget; the native engine's own memory limit set from it.
- **The lake configuration** stays the one source of partitions, load mode and table settings.

## 7. The input encoding: why text is the limit for vectors

For a VaR vector row, most of the loader's time is spent turning 510 decimal numbers into binary doubles: about half a billion numbers
for 1,000,000 positions and one date. A faster engine (option A) does the same work with more cores and less overhead; it cannot skip
it. Estimates, to be measured (section 8):

| 1,000,000 positions, one date | Time | Gain |
|---|---|---|
| Today: the pure-Java loader, JSON Lines text (measured: 20.0 s for two dates) | about 10 s | |
| Option A: DuckDB JSON → Parquet, Delta Kernel Java commits | about 3 to 5 s | about 2 to 3 times |
| A binary vector encoding (below) with option A or a tuned Java pipeline | about 1 to 2 s | about 5 to 10 times |
| Narrow rows (trades, about 30 fields), 1,000,000 rows: today and option A | about 2 to 3 s; about 0.5 to 1 s | about 3 to 5 times |

The encodings that keep JSON's document model:

| Encoding | What it is | Reading the vectors | JSON Lines rule |
|---|---|---|---|
| **JSON Lines text** (today) | numbers as decimal text | the baseline | kept |
| **Base64 vectors inside JSON Lines** | still one JSON document a line; the vector field holds the Base64 of its numbers as little-endian binary, the other fields are ordinary JSON: `{"POSITION_ID": "P-1", "PNL_ENC": "f64le", "PNL": "AAAA…"}` | about 5 to 10 times faster (Base64 decodes at gigabytes a second; no decimal parsing) | **kept**: it is plain JSON Lines with one encoded field |
| **CBOR, Smile or MessagePack** (binary JSON) | the same documents in a binary file format, numbers stored as raw 8-byte doubles; Jackson reads CBOR and Smile in pure Java | about 5 to 10 times faster | an exception: the same data model, a different file format |
| **Arrow IPC or Parquet** | columnar binary, written straight to Parquet | the fastest: no parsing | an exception: not JSON |

**Base64 vectors inside JSON Lines** is the most promising: it keeps the rule and removes most of the parse cost.

- **float32 or float64.** The server already holds vectors as float32. A `f32le` encoding halves the vector's size (510 numbers:
  about 2.7 KB of Base64 against 5.4 KB of text). The choice is the feed's, named in the field (`PNL_ENC`), and the lake column's
  type follows the lake configuration as today.
- **Both forms accepted.** The loader reads a text array or an encoded field, so existing feeds keep working; the data contract names
  the field's encodings.
- **Exactness.** `f64le` carries the double exactly; `f32le` rounds once, as the server's float32 store already does. The decimal text
  of today is converted to the same doubles, so a table loaded from text and from `f64le` is identical to the bit.
- **The other loaders** (Python, the in-memory lake, Iceberg) and the generators follow the same encoding, one reader each
  (no second format machinery).

## 8. The measurements that decide

A spike, run on an idle machine with the user's stack down:

1. The same inputs as BENCHMARK.md 7.1: the 400,000-row and 2,000,000-row VaR files, plus a narrow-row file (2,000,000 trades).
2. Each input in three encodings: JSON Lines text, Base64 vectors (`f64le` and `f32le`), and CBOR.
3. For each of today's Java loader, option A, and option E: rows a second end to end, per stage (decompress, parse, write, commit),
   peak memory, the number and size of files written, and the table compared to the bit.
4. Thread scaling: 1, 4, 8, 16 and 24 threads.
5. Option B only if A is under half of the target on narrow rows.

The results go into BENCHMARK.md, with a recommendation.

## 9. Open questions for the product owner

1. **The target per row shape.** Millions a second for narrow rows looks reachable; for 510-number vectors it needs a binary vector
   encoding or a very large server. Which shapes must reach it?
2. **Base64 vectors inside JSON Lines** (`f64le`, `f32le`): adopt as an accepted form of the JSON Lines standard?
3. **A binary file format** (CBOR, or Arrow IPC and Parquet through Arrow Flight `DoPut`) for the vectors: an exception to the JSON
   Lines rule, or not?
4. **Iceberg** at the same speed in the same step, or Delta first?
5. **When** to run the spike: proposed after 2.9.11.c's benchmark, on the same idle-machine slot.

## 10. Sources

- delta-rs, with Python and Ruby bindings: <https://github.com/fvaleye/delta-rs>
- delta-dotnet, .NET bindings on delta-rs through the Arrow C Data Interface: <https://github.com/delta-incubator/delta-dotnet>
- delta-kernel-rs: <https://github.com/delta-io/delta-kernel-rs>; its C interface: <https://docs.delta.io/kernel/rust/ffi/overview>
- DuckDB's Delta extension: <https://duckdb.org/docs/current/core_extensions/delta>; writes added in 2026:
  <https://duckdb.org/2026/05/07/delta-uc-updates>
