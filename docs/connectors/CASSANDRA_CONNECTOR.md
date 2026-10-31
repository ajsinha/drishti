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
# The Apache Cassandra connector: a book of trades a day, for years

This document explains how Drishti reads a kind from **Apache Cassandra** (and ScyllaDB and DataStax Astra, which speak the same
protocol): one entity in a few milliseconds, type-ahead from memory, searches over every trade of a day from a few narrow columns, and
seven years of history at a cost Cassandra's disks handle. It covers the table layout, the loader and its generations (a day appears
whole or not at all), what the connector reads for each question, retention, sizing, security, measured numbers, limits and every
setting.

Read it if you run Drishti on Cassandra, load Cassandra for Drishti, or change the connector. The design note it was built from,
CASSANDRA_CONNECTOR_DESIGN.md, was deleted (in git at tag v2.9.5); the engine side (columns, searches, derived kinds,
impact) is shared with Delta Lake and explained in [DELTA_CONNECTOR.md, section 7](DELTA_CONNECTOR.md#7-searches-pick-lists-derived-kinds-and-impact-over-columns).
The closest connector built before it is [Aerospike](AEROSPIKE_CONNECTOR.md).

## Contents

1. [Which Cassandras](#1-which-cassandras)
2. [The table layout](#2-the-table-layout)
3. [Declaring which fields are columns](#3-declaring-which-fields-are-columns)
4. [Loading](#4-loading)
5. [How the connector reads](#5-how-the-connector-reads)
6. [Retention: TTL or dropped days](#6-retention-ttl-or-dropped-days)
7. [Seven years: sizing, and Cassandra with a lake](#7-seven-years-sizing-and-cassandra-with-a-lake)
8. [Memory in the Drishti server](#8-memory-in-the-drishti-server)
9. [Measured results](#9-measured-results)
10. [Limits and trade-offs](#10-limits-and-trade-offs)
11. [Diagnosing](#11-diagnosing)
12. [Security: TLS, log-ins and connections](#security-tls-log-ins-and-connections)
13. [As a connector file](#as-a-connector-file)
14. [Settings](#14-settings)
15. [Checklist for production](#15-checklist-for-production)
16. [Walk-through, step by step](#walk-through-step-by-step)

---

## 1. Which Cassandras

The plugin (`plugins/drishti-plugin-cassandra`, plugin name `cassandra`) is built on the Apache Cassandra Java driver 4 (pure Java: no
native library, the same on Windows and Linux) with its own pool, load balancing and retries.

| Store | Works | Notes |
|---|---|---|
| Apache Cassandra 4.0, 4.1, 5.0 | yes | Cassandra 5 is tested (the dated-source contract, the loader, TLS, Delta equality) |
| ScyllaDB | yes | the same protocol and CQL; ScyllaDB 6.2 is tested with the dated-source contract |
| DataStax Astra / DSE | yes | Astra through its secure connect bundle and a token (`secure-connect-bundle`, `token`) |
| Amazon Keyspaces | wired, not yet checked | SigV4 log-in through AWS's driver plug-in (`sigv4: true`), TLS required, `LOCAL_QUORUM`; checked by hand before it is listed as supported |
| Azure Cosmos DB for Apache Cassandra | expected | not checked |

No secondary index is used (SAI or otherwise): every read names a whole partition key and searches run on columns in Drishti, so the
connector behaves the same on every store above.

## 2. The table layout

A data domain (`trading`) is a **keyspace**. Each kind has one data table, and the domain shares two small tables:

```sql
-- one row per entity per business day (the loader's CQL, with --create)
CREATE TABLE "trading"."trade" (
    "business_date" date, "generation" int, "bucket" smallint, "id" text,
    "doc" text,                                    -- the whole JSON document, exactly what a view shows
    "trade_id" text, "portfolio" text, "quantity" double, "legs__currency" text,   -- the promoted fields
    PRIMARY KEY (("business_date", "generation", "bucket"), "id")
) WITH compression = {'class': 'ZstdCompressor'} AND comment = 'drishti kind: trade';

-- the days each entity has (an effective kind's latest day on or before a date, in one read)
CREATE TABLE "trading"."entity_days" ("kind" text, "id" text, "business_date" date,
    PRIMARY KEY (("kind", "id"), "business_date")) WITH CLUSTERING ORDER BY ("business_date" DESC);

-- the catalogue: each kind's days, and which generation of each readers follow
CREATE TABLE "trading"."kind_days" ("kind" text, "business_date" date, "generation" int, "buckets" int, "rows" bigint,
    "loaded_at" timestamp, PRIMARY KEY ("kind", "business_date")) WITH CLUSTERING ORDER BY ("business_date" DESC);
```

Why each part is shaped this way:

- **A partition per day per bucket.** Cassandra wants partitions well under 100 MB. A day's rows are spread over `buckets` partitions
  (128 by default) by `murmur3(id) mod buckets` (Murmur3 x86 32-bit, seed 0, of the id's UTF-8 bytes: any client can compute it). A million
  trades of about 7 KB in 128 buckets is about 7,800 rows and 55 MB a partition. The bucket count is recorded with each day in `kind_days`,
  so it can grow for a larger book without touching old days.
- **`id` is the clustering key**: one entity is one row of one partition; a partition's ids come out sorted.
- **Promoted fields as columns** let searches, pick lists, derived kinds, impact and reverse lookups read a day's few narrow columns and
  never the documents. Names follow the Delta rule in lower case (`LEGS.CURRENCY` → `legs__currency`; other characters become `_`; a name
  longer than 48 characters is cut and given 4 hex digits of its SHA-1; `id` or `doc` as a path becomes `f_id`, `f_doc`). Numbers are
  `double`, everything else `text`.
- **`generation`** is part of the partition key so that a reload writes new partitions beside the old ones ([section 4](#4-loading)).
- **The table comment** `drishti kind: <kind>` tells the connector which kind a table holds (a kind such as `netting-set` becomes the
  table `netting_set`), so the kinds are learnt from the schema, never by scanning.
- **`kind_days`** lists each kind's days without reading a data row; readers follow it.

## 3. Declaring which fields are columns

The pack declares the fields to promote on the connector that stores the kind, exactly as for Delta Lake and Aerospike
(`layout.<kind>.columns`; see [DELTA_CONNECTOR.md, section 4](DELTA_CONNECTOR.md#4-declaring-the-layout-in-a-pack) for how to choose them).
The loader takes the promoted values from its input (`columns` in each JSON line), so the connector and the loader agree as long as both
come from the pack. A path the connector is told to promote but the table has no column for is simply not offered as a column: searches
on it read documents. Effective kinds are never read as columns (their days hold changes only).

## 4. Loading

`tools/load-cassandra.sh` (Windows: `tools\windows\load-cassandra.ps1`) loads the seven packs' sample data and optionally a generated
book through `CassandraLoader` (in the plugin); `drishti.py data load --store cassandra` runs it.

```bash
tools/load-cassandra.sh localhost:9042 datacenter1 --create --create-keyspace 1                   # the samples x 10 days
tools/load-cassandra.sh localhost:9042 datacenter1 --create --trades 10000 --days 3               # and a book, streamed
tools/load-cassandra.sh cass1:9042,cass2:9042 dc-east --ttl-days 90 --setting tls.enabled=true \
    --setting tls.ca-file=/etc/drishti/tls/ca.pem --username loader --password "$PW"            # a secured cluster
```

The loader's heap is `DRISHTI_LOADER_HEAP` (default `4g`); its input file is written under the data folder and removed after the load.
The last line names the rows, the days (a kind's business day counts once per kind) and the rate, e.g. for the measured book of
[section 9](#9-measured-results): `cassandra: loaded 30,000 rows (3 days) in 49.7 s (604 rows/s)`.

How the loader works:

- **Its input is JSON lines**, one per entity per day: `{"domain", "kind", "id", "date", "doc", "columns": {path: value}}`, the format of
  `make_data.py --jsonl` and `bulk_trades.py --jsonl` (the Aerospike loader's too). The script sends the samples and the book as **one
  stream**, so each business day is loaded once.
- **A day appears whole or not at all.** Each day of the input gets a new **generation** (the seconds since 2020, or the current one plus
  one if that is larger). The day's rows go to the new generation's partitions; its `entity_days` rows are written; and only when every
  write has succeeded is the `kind_days` row naming the new generation written. Readers follow `kind_days`, so they read the old
  generation until that moment and the new one after it. The old generation's partitions are then deleted: `buckets` whole-partition
  deletes, never row by row. If a write fails, no day of the load is switched and the old ones are still read. A load that stops part way
  leaves partitions no reader follows (the next load of the day takes a newer generation; see [section 10](#10-limits-and-trade-offs)).
- **Writes** are prepared statements at `LOCAL_QUORUM` (`--consistency`), at most `--in-flight` (256) at once, grouped per partition
  into **unlogged batches** of at most `--batch-rows` (32) rows or 256 KB, never across partitions (Cassandra's own advice: a
  single-partition batch is one mutation).
- **`--create`** creates the tables (with `IF NOT EXISTS`) and adds a column for a newly promoted field (`ALTER TABLE … ADD`). Without it
  the loader prints the CQL for a database administrator and stops before writing anything into a table that lacks it. The keyspace's
  replication is the administrator's: `--create-keyspace RF` creates one with SimpleStrategy for a development node only.
- **`--buckets N`** (128), or `--buckets KIND=N` for one kind, sets the partitions per day for new loads; the loader warns when a
  partition passes 100 MB and names the value to use.
- **Dates are guarded** as by every loader (`LoadGuard`): a row dated after tomorrow in the business zone is quarantined and the load ends
  with an error once the good rows are in (`--future-days`, `--zone`).
- **Progress** every 100,000 rows; at the end the rows, days and seconds.

**Your own loader** (a Spark job, a Kafka consumer) writes the same three things in the same order: the day's rows under a new
generation, the `entity_days` rows, then the `kind_days` row (`generation`, `buckets`, `rows`, `loaded_at`); then it deletes the old
generation's partitions. `CassandraLayout` has the CQL and the bucket function, and can be called from Java.

## 5. How the connector reads

Every statement is prepared once and names a whole partition key; the driver routes it to a replica that owns the partition. Nothing
scans a table: no `ALLOW FILTERING`, no token range (`CassandraPartitionKeyTest` checks every statement the connector and the loader
prepare against the live tables' partition keys).

### 5.1 The catalogue

Every `refresh-seconds` (60) the connector reads each kind's `kind_days` partition (a few hundred small rows for years of history). A
kind's ids for type-ahead are re-read only when its newest day or that day's generation changed. The newest day's promoted columns are
then read in the background, so the first search finds them ready.

### 5.2 Opening one entity

`TRD MX-20000017` on 2026-09-30:

1. Choose the day: for a `snapshot` kind, the kind's newest day on or before the date asked, within `lookback-days` (10), from the
   catalogue (no read); for an `effective` kind, the entity's latest days on or before it from `entity_days` (one read), the first the
   catalogue lists (a later day still being loaded, or dropped by retention, is passed over).
2. Read one row: `SELECT "doc" … WHERE "business_date" = ? AND "generation" = ? AND "bucket" = ? AND "id" = ?`.

A document read is kept in memory (`doc-cache-mb`, 64), keyed by the day's generation, so a reload is never served stale.

### 5.3 Type-ahead

From the ids of the newest day (an effective kind: of every day, at most `max-load-rows`), read bucket by bucket and held in Drishti's
sorted `HitIndex`. A trade written after the last refresh is readable at once and offered by type-ahead after `refresh-seconds`.

### 5.4 A day's columns

Searches, pick lists, derived kinds, impact and reverse lookups read a **column set**: the ids and promoted columns of every bucket of
the day's generation, `scan-threads` (8) partitions at a time and `page-size` (5,000) rows a page; the document column is never selected.
It is kept by memory (`columns-cache-mb`, 1024) per day and generation. A day the catalogue does not list (older than the TTL, dropped,
or never loaded) answers "not held", so the next connector for the kind is asked.

### 5.5 Reverse lookups

"Which trades reference netting set `NS-SUMMIT-NY`?" comes from the day's promoted text columns. A kind without promoted columns reads
that day's documents (an effective kind: each entity's latest on or before the date), at most `max-load-rows` (200,000).
`reverse-index: false` turns reverse lookups off.

### 5.6 Documents of a day, inventory, versions

Derived pages read a snapshot kind's day in one pass (`documents`), bucket by bucket. **Admin → Packs → Test connection** shows each
day's row count from `kind_days` (`inventory`). The kind's data version (for the BI store) is its days and their generations: a new day,
a reload or a dropped day changes it; one day's version is its generation, so a load of another day leaves a stored layer valid.

**Live changes.** The connector refreshes; it does not push. Cassandra's change data capture is per node and on disk; to push, put a
Kafka connector in front of it (Drishti reads Kafka).

### History queries

`TRD 1000015 30` ([DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#2-history-queries-an-entity-over-time)) lists the entity's days with one single-partition range read
of `entity_days` (`kind = ? AND id = ? AND business_date >= ? AND business_date <= ?`), keeping only days `kind_days` still names (an
`entity_days` row may outlive its data), then reads one document per day by its partition (date, generation, bucket computed here),
eight at once. The documents cannot come from one query: the business date is part of the data table's partition key. One generation is
kept, so `versions` is 1 and `knownAt` is refused (`DRS-1007`).

## 6. Retention: TTL or dropped days

| Way | How | When |
|---|---|---|
| **TTL** | `--ttl-days N`: every row, `entity_days` and `kind_days` row is written `USING TTL`; with `--create`, new tables get `TimeWindowCompactionStrategy` with one-day windows, so whole SSTables expire together | a fixed retention; nothing to run |
| **Dropped days** | `--keep-days N`: after the load, each loaded kind keeps its newest N days on or before `--as-of` (today); older days' partitions and `kind_days` rows are deleted | a retention that changes; Amazon Keyspaces (its own TTL instead of a compaction choice) |

Dropping more than half of a kind's days needs `--force-drop` (`--max-drop-share`), as for every loader. `entity_days` rows may outlive
their day's data; such a day is not in the catalogue, so a read passes over it. Only whole partitions (and catalogue rows) are ever
deleted, so reads never wade through row tombstones.

## 7. Seven years: sizing, and Cassandra with a lake

Estimates for a million trades a day, documents of about 7 KB compressing to about 1–1.5 KB with ZSTD:

| History in Cassandra | Rows | Data, one copy | With replication factor 3 |
|---|---|---|---|
| 90 business days | 90 million | about 110 GB | about 330 GB |
| 1 year (250 days) | 250 million | about 310 GB | about 0.9 TB |
| 7 years (1,760 days) | 1.76 billion | about 2.2 TB | about 6.6 TB |

Unlike Aerospike, Cassandra keeps its indexes on disk, so seven years needs disk, not memory. Most sites still keep **recent days in
Cassandra and history in Iceberg or Delta Lake**: configure the Cassandra connector first for the kind and the lake connector behind it;
a read or search on a day Cassandra does not hold falls through to the lake, as with Aerospike
([AEROSPIKE_CONNECTOR.md, section 7](AEROSPIKE_CONNECTOR.md#7-seven-years-sizing-and-aerospike-with-delta-lake)).

## 8. Memory in the Drishti server

| Held | Per million trades (estimate) | Bounded by | Default |
|---|---|---|---|
| type-ahead ids | about 150 MB | the newest day's ids | — |
| a day's column set (19 fields) | about 230 MB | `columns-cache-mb` | 1024 MB |
| documents read | — | `doc-cache-mb` | 64 MB |
| the catalogue | negligible | — | — |

## 9. Measured results

**Measured at 10,000 trades a day (the local limit), on 2026-10-20**, with `CassandraScaleBench` (in the plugin's tests; run it with
`-Ddrishti.cassandra.bench=<file>` over `bulk_trades.py --trades 10000 --days 3 --jsonl <file>`): a 24-thread workstation shared with
other work, one Apache Cassandra 5.0.6 node in Docker with a 1 GB heap, 30,000 production-shaped trade documents (about 18 KB each as
JSON lines, 546 MB in all) over three business days, 128 buckets a day, 16 promoted columns with values (two more promoted paths are
null in every trade, so they get no column). Times are at the connector (no HTTP), medians of 25 (5 for whole-day reads):

| Question | Time |
|---|---|
| load 30,000 trade-days, 128 buckets, 256 writes in flight | 49.7 s (about 600 rows and 11 MB a second) |
| connector start: catalogue and the newest day's 10,000 ids ready | 1.8 s |
| type-ahead `mx-3000` | under 0.1 ms (from memory) |
| a trade's document, first read / again (cached) | 8.9 ms / 0.2 ms |
| a trade's document on a past day, first read | 4.9 ms |
| a day's 16 promoted columns (10,000 rows), first read / again | 541 ms / 0.1 ms |
| reverse lookup of a portfolio from the promoted columns | 8.7 ms |
| a day's 10,000 documents in one pass, parsed (derived pages) | 4.2 s |
| inventory (`kind_days`) | 1.9 ms |

Equality with Delta Lake is checked by `CassandraDeltaEqualityTest` (the Delta connector's sample lake copied into Cassandra; every read
path answers the same). Not measured here: a million trades a day (the design's [sizing](#7-seven-years-sizing-and-cassandra-with-a-lake)
is an estimate) and a multi-node cluster; the loader's rate on one Docker node is bounded by that node.

## 10. Limits and trade-offs

- **No time travel** (*known at*): Cassandra keeps no past versions; a read with `knownAt` on a day it holds fails with `DRS-1007`
  naming the connector, as for every store without versions.
- **Only promoted fields are fast**; others are searched in documents (20,000 at most, `partial`).
- **A stopped load leaves orphan partitions** (a generation `kind_days` never named). They are never read; they expire with a TTL, or
  delete them with `DELETE FROM <ks>.<kind> WHERE business_date = ? AND generation = ? AND bucket = ?` for each bucket.
- **Two loads of the same day at once** are not coordinated: the later `kind_days` write wins and the other's partitions become orphans.
  Load a day from one place.
- **The first search on a day other than the newest** waits for that day's columns (a day is read once per generation).
- **An effective kind's type-ahead** reads every day's ids (at most `max-load-rows`); keep effective kinds small, as they are.
- **Large partitions** if `buckets` is too low for a book: the loader warns and names a value.

## 11. Diagnosing

Admin → Health shows the connector; Admin → Caches its figures (`kinds`, `daysIndexed`, `ids`, `columnSets`, `documents`); Admin →
Connectors → the connector → Pool its connections.

| Symptom | Likely cause | What to do |
|---|---|---|
| `DOWN: connecting to Cassandra at …` | the driver cannot open a session. The connector starts anyway and the driver keeps trying in the background (one session: a retry, a stop or deleting the connector file closes the one being built, its reconnection included); the connector also opens one connection of its own to the first contact point (again at most every 30 s) and adds what it found after a colon, below. With nothing added, the node answers CQL: look at `local-datacenter` (`nodetool status`) and the log-in | the rows below |
| `… : no connection to <host:port> (ConnectException: Connection refused): check contact-points, the port and that the node runs` | wrong `contact-points`, a closed port, the node down | `nc -z <host> 9042` |
| `… : TLS to <host:port> failed (…): the node asks for a client certificate (mutual TLS) and none is set: set tls.cert-file and tls.key-file (or tls.keystore) …` | the node has `require_client_auth: true` and the connector gives no certificate | set the client certificate ([TLS.md](TLS.md)) |
| `… : TLS to <host:port> failed (…): the node refused the client certificate: …` | the client certificate is not signed by an authority in the node's truststore | a certificate from the node's authority, or add its CA to the node |
| `… : TLS to <host:port> failed (…): the node's certificate is not trusted: set tls.ca-file (or tls.truststore) …` | the connector does not trust the node's authority | `tls.ca-file` or `tls.truststore` |
| `… : TLS to <host:port> failed (…): the node's certificate does not name <host> …` | the host name check failed | connect by a name the certificate holds, or `tls.verify-hostname: false` |
| `… : TLS to <host:port> failed (…): the node does not speak TLS on this port …` | `tls.enabled: true` against a plain port | `tls.enabled: false`, or the TLS port |
| `… : TLS: the node at <host:port> did not answer CQL in plain text and closed the connection, so it requires TLS (client_encryption_options) …` | a connector without TLS against a node that demands it | `tls.enabled: true` with `tls.ca-file`, and the client certificate when it asks for one |
| `DOWN: keyspace … does not exist` | the keyspace is named after the domain unless `keyspace` is set | set `keyspace`, or load it |
| `DOWN: keyspace … has no kind_days table` | nothing was loaded in the connector's layout | `tools/load-cassandra.sh --create`, or run the printed CQL |
| `UP (2 of 3 nodes up)` | a node is down; reads go to the replicas left | the driver reconnects by itself |
| a new day does not appear | the catalogue is re-read every `refresh-seconds`; or the load failed before `kind_days` was written | wait, or Admin → Caches → purge; check the loader's last line |
| searches say `partial: true` | a field the query reads is not a promoted column | add it to `layout.<kind>.columns` and reload with `--create` |
| loader: `… does not exist: run the CQL above` | no `--create` | have an administrator run the printed CQL, or pass `--create` |
| loader: `retention would drop …` | `--keep-days` would drop more than half of the days | check `--as-of` and the dates, or pass `--force-drop` |
| loader warning `a partition holds about … MB` | `buckets` too low for the book | load with the `--buckets KIND=N` it names |

## Security: TLS, log-ins and connections

| Setting | Meaning for Cassandra |
|---|---|
| `tls.enabled: true` | TLS on (the cluster's `client_encryption_options`); `tls.*` without it is a start-up error. |
| `tls.ca-file` / `tls.truststore` | Trust the cluster's CA. |
| `tls.cert-file` + `tls.key-file`, or `tls.keystore` | The client certificate, for `require_client_auth: true`. |
| `tls.verify-hostname` | `true` by default: the node's certificate must name the address the driver connects to (the contact point, then each node's rpc address); give the certificates those names or IP addresses, or set `false` (a warning at every start). |
| `tls.protocols`, `tls.cipher-suites` | Applied to every connection. |
| `username`, `password` | Password log-in (`PasswordAuthenticator`); `password` is `${ENV}` or `file:`. |
| `secure-connect-bundle`, `token` | DataStax Astra: the bundle carries the addresses, the data centre and TLS; the token is the password (user `token`). |
| `sigv4`, `sigv4-region` | Amazon Keyspaces: AWS's SigV4 plug-in (`aws-sigv4-auth-cassandra-java-driver-plugin`, not shipped: put its jar in the plugin folder) with the default AWS credential chain. |

- **No HTTP proxy**: the native protocol is TCP. `proxy.*` settings are refused with that reason; a site that needs one uses a TCP tunnel.
- **Read-only by default**: the connector's role needs only `SELECT` on the keyspace; the loader's needs `MODIFY` (and `CREATE`, `ALTER`
  with `--create`).
- **Connections**: `connections-per-node` (2) per node, each carrying many requests; at most `max-concurrent-reads` (256) in flight,
  further ones queue in the driver; `request-timeout-ms` (5,000) per statement; speculative executions off unless
  `speculative-executions` is set (every read is idempotent). The pool page shows connections open and those busy with requests.

TLS is tested against Cassandra 5 in Docker with client encryption and `require_client_auth: true` (`CassandraTlsTest`): the connector
reads over mutual TLS, reports the client certificate's expiry in Health, and says `DOWN` without a client certificate.

## As a connector file

```yaml
# config/connectors/trade-endur.yaml
plugin: cassandra
kinds: [trade]
description: Endur trades in Cassandra
settings:
  domain: trading                                   # the keyspace
  contact-points: ${DRISHTI_CASSANDRA_CONTACT_POINTS:localhost:9042}
  local-datacenter: ${DRISHTI_CASSANDRA_DATACENTER:datacenter1}
  username: ${DRISHTI_CASSANDRA_USERNAME:}         # empty: a node with no sign-in
  password: ${DRISHTI_CASSANDRA_PASSWORD:}
  layout.trade.columns: TRADE_ID,SOURCE_SYSTEM,PRODUCT_TYPE,PORTFOLIO,COUNTERPARTY,CURRENCY,QUANTITY
```

Ready-made files: `config/connectors.examples/cassandra/` (the six banking domains), `other/cassandra-tls.yaml` (mutual TLS) and
`other/cassandra-astra.yaml` (Astra).

## 14. Settings

Every setting, with defaults: [CONFIGURATION.md › cassandra](../admin/CONFIGURATION.md#cassandra). The connector form in Admin →
Connectors is generated from the same catalogue.

## 15. Checklist for production

- The keyspace created by an administrator with `NetworkTopologyStrategy` and the replication you need; the connector's role has `SELECT`
  only; the loader's role `MODIFY`.
- `buckets` sized so a partition stays well under 100 MB (about 7,800 rows of 7 KB at 128 buckets for a million a day).
- TLS on, with certificates that name the addresses the driver connects to.
- Retention chosen: `--ttl-days` with time windows, or `--keep-days` from a scheduled load.
- One loader per kind and day; its exit status checked (a failed load switches nothing).
- A lake connector behind Cassandra for days older than it keeps.
- `local-datacenter` set to the data centre nearest the Drishti server; `consistency: LOCAL_QUORUM` unless staleness is acceptable.

## Walk-through, step by step

1. **Run Cassandra.** A development node: `docker run -d --name cass -p 9042:9042 cassandra:5.0.6` (ScyllaDB: `scylladb/scylla:6.2
   --smp 1 --developer-mode 1`). Wait until `cqlsh` answers.
2. **Load the samples and a small book**: `tools/load-cassandra.sh localhost:9042 datacenter1 --create --create-keyspace 1 --trades 10000 --days 3`.
   The last line reads `cassandra: loaded … rows (… days)`.
3. **Point the domains at Cassandra**: `cp config/connectors.examples/cassandra/*.yaml config/connectors/` (or one file through Admin →
   Connectors → New, plugin `cassandra`). The server picks the files up without a restart. `trade-endur.yaml` replaces the trading pack's
   Delta connector of that name (kind `trade` routes to it) and serves every system's trades the loader wrote; the files' `username` and
   `password` default to empty (`${DRISHTI_CASSANDRA_USERNAME:}`, `${DRISHTI_CASSANDRA_PASSWORD:}`), which is a development node with no
   sign-in: set both for a node that asks for one. If you keep the Delta `trade-endur` disabled and name the Cassandra file otherwise
   (`trading-store`), the kind is still read from it: an id it does not hold answers `DRS-1001 no source holds trade/… (asked
   trading-store; trade-endur, which kind 'trade' is routed to, is disabled)`.
4. **Check**: Admin → Connectors → `trade-endur` → Test connection lists the days and their rows; Admin → Health says `UP`.
5. **Use it**: `TRD ` in the command line offers the trades; open one; a search such as `TRD PORTFOLIO=BOOK-RATES-3` reads the day's
   columns.
6. **Reload a day** with the same command: readers switch to the new generation when the load ends, never seeing half a day.
