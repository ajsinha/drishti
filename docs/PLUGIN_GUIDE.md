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
# Writing a source plugin

A source plugin brings entity documents from one system into Drishti. Plugins depend only on
`drishti-api` (no Spring, no Jackson), so they stay small and cannot clash with the server's libraries.

## The contract

```java
public interface SourcePlugin extends AutoCloseable {
    PluginManifest manifest();                                   // name, version, kinds, capabilities
    void start(SourceContext ctx) throws Exception;              // settings, JSON parser, scheduler
    Optional<EntityDocument> fetch(EntityRef ref) throws Exception;
    default Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf)           // dated sources override
    default Subscription subscribe(EntityRef ref, Consumer<EntityDocument> l)  // live updates
    default List<EntityRef> reverse(EntityRef target, String kind)             // e.g. netting set → trades
    default List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf)
    default List<EntityHit> search(String kind, String text, int limit)        // command type-ahead
    default List<EntityHit> search(String kind, String text, int limit, AsOf asOf)
    default String health()
}
```

- **Business dates.** `AsOf(businessDate, knownAt, live)` says which day's data is wanted and, optionally, the
  data as known at an instant (before later corrections). A source that keeps history declares
  `SourceCapabilities(live, reverseLookup, search, dated = true)`, overrides the `AsOf` overloads, and stamps
  `Provenance.businessDate` with the date its document is for (which may be earlier than the one asked, for
  example the last business day that has data). Dated sources always receive a concrete date. Undated sources
  need do nothing: the defaults ignore the date, and the view tells the user the data is current, not historic.
  For a picked date, dated sources are tried before undated ones (ADR-012).

- **Thread safety.** `fetch` is called concurrently from many virtual threads. It may block (JDBC,
  HTTP), but it must be thread-safe.
- **Provenance.** Every `EntityDocument` carries `Provenance(source, generation, fetchedAt, live)`.
  - `source` is the name users see in *How this view was built* (`aero-risk`, `eod-futures`).
  - `generation` must be monotonic: higher means newer data.
- **Search.** `search` powers the dropdown under the command line, so it must answer from memory.
  `HitIndex` in the API is a ready-made, lock-free index: call `add(...)` at start, and `replaceAll(...)` on refresh.
- **Capabilities.** Declare `SourceCapabilities(live, reverseLookup, search)` honestly. The engine only
  calls optional methods that a plugin declares.

## Registration

1. Put the plugin's fully qualified class name in `META-INF/services/com.ash.drishti.api.SourcePlugin`.
2. Either add the jar to the server's classpath, or drop it into `drishti.sources.plugin-dir`, where it
   gets its own class loader.

## Configuration

```yaml
drishti:
  sources:
    fetch-timeout: 2s
    default-route: demo             # tried first for kinds without a route
    routes: { curve: aero, trade: aero }
    plugins:
      file:
        enabled: true
        settings: { root: ./data/feeds, source-name: eod-futures, rescan-seconds: 30 }
```

**Named connectors** run a plugin more than once, each with its own settings and name:

```yaml
drishti:
  sources:
    connectors:
      finance-lake: { plugin: delta, settings: { root: ./data/delta, domain: finance } }
      risk-lake:    { plugin: delta, kinds: [netting-set, exposure-profile], settings: { domain: risk } }
```

**Every dated store passes the same contract.** `DatedSourceContract` (in `drishti-testkit`) loads one set of rows and
checks dates, snapshot and effective kinds, reverse lookups and search. The Delta Lake, PostgreSQL and Aerospike
connectors each extend it; the database tests run PostgreSQL 18 and Aerospike CE in Docker (Testcontainers) and are
skipped where Docker is not reachable.

Routing order for a kind: its configured route, then `default-route`, then any other plugin whose
manifest serves the kind (for a picked business date, dated sources first; for Live, live sources first, and a real stream before the default route's samples). Missing entities fall through to the next plugin. Errors and timeouts
surface as `DRS-1003` and `DRS-1004`.

## Built-in plugins

| Plugin | Serves | Notes |
|---|---|---|
| `demo` | The enabled packs' sample entities (`packs/<name>/samples/`): finance has the 36 behind the four mockups; logistics has shipments, containers, vessels and ports | Samples are generated by each pack's `tools/`. Supports search, reverse lookup and live ticking (`_meta.walk` random-walks the named fields). The directories come from the packs (`settings.dirs`). |
| `delta` | Delta Lake tables `<root>/<domain>/<kind>/`: `(id STRING, doc STRING)` rows partitioned by `business_date` | Read with Delta Kernel (Java, no Spark). Snapshot tables take the newest partition on or before the date; `effective` tables the last change on or before it. `knownAt` is Delta time travel: unless a table uses in-commit timestamps, Delta resolves the instant against the `_delta_log` files' modification times, so copy lakes with times preserved (`cp -p`, `rsync -t`). Reverse lookups index every identifier a document mentions. Run it as named connectors, one per domain. Build a sample lake with `tools/samplegen/lake.py`. |
| `file` | `<root>/<kind>/<id>.json` or `.csv`, and dated `<root>/<yyyy-MM-dd>/<kind>/<id>.json` (ships with `data/feeds/fixing/SOFR-HISTORY.csv`: try `FIX SOFR-HISTORY <GO>`) | Generation is the file's modification time. Identifiers that would escape the root are refused. A CSV becomes `{"rows": [...]}` with typed cells. |
| `jdbc` | Per-kind SQL (`query.<kind>` with `:id`, `:asOf`), or **table mode** (`table: trading.entities`): all kinds of a data domain in one PostgreSQL table `(kind, id, business_date, doc jsonb)`, dated, with search and reverse lookups by SQL/JSON path | The driver comes with the server for PostgreSQL. `mode.<kind>` is `snapshot` or `effective`. Load a domain with `make_data.py --postgres`. |
| `aerospike` | A set per data domain; a record per entity (`kind/id`) with a bin per business date | Reads are key lookups; the dates, identifiers and references are learned by scanning the set at start and every `refresh-seconds`. Load with `tools/load-aerospike.sh`. |
| `feed` | Public data: `nyfed-sofr`, `ecb-estr`, `ecb-fx`, `us-treasury`, `fred` | One connector per feed, declared by the market-data pack, each off until switched on. Entities carry the feed in their id (`FIX-SOFR-NYFED`). Keeps history for picked dates; a failed fetch keeps the last good data and shows in health. |
| `kafka` | Live entities from Kafka topics: an envelope `{kind, id, doc}`, or whole-document messages with `kind` and `id-field` | Reads every partition from the beginning (the topic is the state: the latest message per entity), then pushes each new message to open views. Tombstones delete. No consumer-group commits. The trading pack declares `trading-stream`, off until `DRISHTI_STREAM_TRADING=true`; `tools/samplegen/stream.py` replays and ticks the samples. |
| `rest` | An HTTP/JSON service: `base-url` + `path` per kind | Headers from settings; the generation from a response header. |
