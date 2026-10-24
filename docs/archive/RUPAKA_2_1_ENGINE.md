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
# Rūpaka as designed for 2.1: the query engine and the architecture diagram

> **Archived: replaced by [BI_DESIGN.md](../architecture/BI_DESIGN.md) section 4 (query architecture) and section 3.5 (Python workers).** These are the opening of section 7 and section 17 of [RUPAKA.md](../architecture/RUPAKA.md) as written for 2.1, kept as history: since 2.3.0 every number is computed on the servers, and Perspective, DuckDB-Wasm and Graphic Walker are no longer part of BI.

## 7. The query engine

- **Server-side DuckDB** (already a Drishti plugin) is the analytical engine: cached tables are DuckDB tables (on disk,
  per dataset, refreshed atomically); direct tables are attached (`ATTACH` for PostgreSQL and Delta, scanners for Parquet
  and Iceberg) or fetched through the connector when no attachment exists; live tables are an in-memory Arrow table kept
  current from the live path.
- **Compilation.** A visual's query (fields, measures, filters, the user's row rules) compiles to one SQL statement with
  parameters, never string concatenation of user input. Row rules are added as predicates the user cannot remove.
- **Delivery.** Results leave as **Apache Arrow IPC** (compact, typed, read without parsing by Perspective and
  DuckDB-Wasm) with masks applied column by column; small results may also be JSON for simple visuals.
- **Caching.** Results are cached per (dataset version, query, row-rule set, data generation); a data-load signal or a
  refresh invalidates exactly what it changed.
- **Limits.** Per-query timeout, row and byte caps, and a concurrency limit per user and per server, all in settings;
  over a limit the visual says so with a `DRS-9xxx` code.
- **In the browser,** DuckDB-Wasm is optional: it lets a user slice an extract they already received (pivot, filter,
  re-aggregate) with no round trip. It never connects to a store.

## 17. Architecture

**No new server processes unless they are needed.** The BI modules run inside the existing Drishti server (Java) and the
console (Python); DuckDB is embedded in the server. The only extra processes are the Python runner's short-lived worker
subprocesses, started by the console when a Python transform, measure or visual runs (as built, one per call and gone when it ends, at most
`bi.python.workers` at once, default 2: 20.8; no idle workers and no `idle-seconds`). When measured load calls for it (section 18,
LOAD_AND_MEMORY), two scale-out options exist, both off by default: the runner as its own service on another host
(`bi.python.runner-url`), and more Drishti servers behind a load balancer, as PERFORMANCE.md "Scaling out" describes.

```
 browser (BI page)                                   console (FastAPI)                     server (Java 21)
 ─────────────────                                   ─────────────────                     ────────────────
 designer, catalogue, home     ── HTML over the wire ──  /bi routes, templates   ── REST ──  drishti-bi-core: datasets,
 visuals: ECharts, panels        Arrow (masked)           auth, CSRF, CORS                   reports, workspaces, catalogue,
 Perspective (on demand)       ◄─ live channel (SSE) ──   channel hub              ◄─ SSE ─   publish, endorsement, audit
 DuckDB-Wasm (on demand)                                  pyrunner pool  ◄── Arrow ──►      drishti-bi-query: compile,
 Graphic Walker (on demand)                               (sandboxed Python)                 DuckDB, caches, row rules,
 Pyodide / Calc (on demand)                                                                  masks, Arrow out
                                                                                             drishti-bi-live: live tables,
                                                                                             incremental aggregates
                                                                                             sources: connectors by name,
                                                                                             TopicHub, LIVE_BI producers
                                                                                             drishti-bi-ai: provider SPI (off)
```

New server modules depend one way (bi-core → bi-query → engine and identity), no controller outside `drishti-server`,
files under 1,500 lines, settings for every limit; the console adds `bi/` routes and templates, vendored assets loaded
per page; the Python runner is a separate process family with its own health.
