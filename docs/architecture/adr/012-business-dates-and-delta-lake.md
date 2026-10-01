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
# ADR-012: Every read is for a business date; history lives in Delta Lake

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

## Context
Risk and trading users look at end-of-day data: yesterday's MTM, last Friday's exposure, the curve on a given
date. Views were "whatever the source has now". Sources hold history very differently: a lake keeps
partitions and versions, a database has a date column, a feed drops a folder per day.

## Decision
- **Live or a picked date.** The top bar carries a business date. **Live** (the default) is the current business
  date on the configured calendar (USNY: weekends and holidays roll back to the previous business day), and
  views stream. **A picked date is a static snapshot**, even when it is today. The console only stores the
  choice (a cookie) and sends it as `X-Drishti-As-Of`. It never reads the lake or any data files.
- **One SPI type.** `AsOf(businessDate, knownAt, live)` reaches every read: `fetch`, `reverse` and `search` have
  dated overloads that default to the undated methods, so existing plugins keep working. A source declares
  `SourceCapabilities.dated`. Dated sources stamp `Provenance.businessDate`; undated ones leave it empty, and the
  view says "not a dated source" instead of passing current data off as history.
- **Routing.** For a picked date, dated sources are tried first; for live, the configured order stands (so live
  ticks come from live sources and history comes from the lake).
- **Delta Lake through Delta Kernel** (Java, no Spark), the `delta` plugin: `<root>/<domain>/<kind>/`, one table
  per kind, `(id, doc)` rows partitioned by `business_date`. Snapshot tables take the newest partition on or
  before the date (within a lookback), effective tables the last change on or before it. `knownAt` uses Delta
  time travel ("as known at", before a restatement). The root is configurable (default `./data/delta`); each domain
  or pack keeps its own folder.
- **Named connectors** (`drishti.sources.connectors.<name>: {plugin, settings, kinds}`) run a plugin several times,
  such as one lake per domain.
- The file plugin reads `<root>/<yyyy-MM-dd>/<kind>/<id>.json`; the JDBC plugin binds `:asOf` in its queries.

## Consequences
History costs no extra code per domain. A pack writes its data as dated Delta tables, and every Sutra, link,
impact, suggestion and monitor follows the date. Live streaming is untouched. The Delta plugin brings Hadoop's
client libraries (shaded) into the server; Unity Catalog's client is excluded because its log4j binding
conflicts with Logback. Maya's `maya_delta` (Python: delta-rs plus a pure fallback) inspired the layout and the
time-travel semantics. Drishti's reader is Java, because the backend owns all data access.
