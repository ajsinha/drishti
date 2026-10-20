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
# The Apache Cassandra connector: design

Status: **design, not built.** Release not decided (product owner, 2026-10-20). Related:
[AEROSPIKE_CONNECTOR.md](../connectors/AEROSPIKE_CONNECTOR.md) (the closest connector built: a key-value store for recent
days), [DELTA_CONNECTOR.md](../connectors/DELTA_CONNECTOR.md) section 7 (how the engine uses column sets),
[DRISHTI_ICEBERG.md](DRISHTI_ICEBERG.md) (the other design of the same day).

## Contents

1. [The ask](#1-the-ask) · 2. [A connector, not an engine of our own](#2-a-connector-not-an-engine-of-our-own) ·
3. [Which Cassandras](#3-which-cassandras) · 4. [The table layout](#4-the-table-layout) ·
5. [How the connector reads](#5-how-the-connector-reads) · 6. [Loading and replacing a day](#6-loading-and-replacing-a-day) ·
7. [Retention](#7-retention) · 8. [Seven years: sizing, and Cassandra with a lake](#8-seven-years-sizing-and-cassandra-with-a-lake) ·
9. [Security and connections](#9-security-and-connections) · 10. [Settings](#10-settings) · 11. [Tests](#11-tests) ·
12. [Build plan](#12-build-plan) · 13. [Limits, risks and open questions](#13-limits-risks-and-open-questions)

## 1. The ask

The product owner (2026-10-20): a connector for **Apache Cassandra**. Many banks run Cassandra (or ScyllaDB, DataStax
Astra, Amazon Keyspaces) as the fast store behind trading and risk systems. Drishti should read a kind from it exactly as
it reads from Aerospike, MongoDB or Delta Lake: open one entity, type-ahead, searches, pick lists, reverse lookups,
derived kinds and impact, on any business day it holds, a million entities a day, for years.

## 2. A connector, not an engine of our own

Unlike Iceberg ([DRISHTI_ICEBERG.md](DRISHTI_ICEBERG.md)), Cassandra needs **no engine of Drishti's own**. The Apache
Cassandra Java driver (4.x, `java-driver-core`) is pure Java: no Hadoop, no native library, the same on Windows and
Linux. Reimplementing the native protocol would add risk and gain nothing. So this is an ordinary plugin,
**`plugins/drishti-plugin-cassandra`**, implementing `SourcePlugin` like the Aerospike plugin, with the driver's own
connection pool, load balancing and retries.

## 3. Which Cassandras

| Store | Works | Notes |
|---|---|---|
| Apache Cassandra 4.0, 4.1, 5.0 | yes | the reference; tested |
| ScyllaDB | yes | speaks the same protocol and CQL; tested; Scylla's shard-aware driver is an option, not needed |
| DataStax Astra / DSE | yes | Astra through its secure connect bundle and a token |
| Amazon Keyspaces | yes, with limits | SigV4 log-in (AWS's driver plug-in), TLS required, `LOCAL_QUORUM`; no compaction choice (its own TTL instead), batches limited |
| Azure Cosmos DB for Apache Cassandra | expected | through its Cassandra API; checked before it is promised |

The design uses **no secondary index** (SAI or otherwise): every read is by partition key, and searches run on column
sets in Drishti. So it works the same on every store above, including Keyspaces, which has no secondary indexes.

## 4. The table layout

A data domain (`trading`) is a **keyspace**; each kind has one data table, and the domain shares two small tables:

```sql
-- one row per entity per business day
CREATE TABLE trading.trade (
    business_date date,
    generation    int,          -- which load of the day (section 6)
    bucket        smallint,     -- murmur3(id) mod buckets: spreads a day over several partitions
    id            text,
    doc           text,         -- the whole JSON document, exactly what a view shows
    trade_id      text,         -- promoted fields (the pack's layout.trade.columns), text or double
    product_type  text,
    portfolio     text,
    counterparty  text,
    quantity      double,
    legs__currency text,        -- a promoted nested path (LEGS.CURRENCY: a dot becomes __, as in Delta)
    PRIMARY KEY ((business_date, generation, bucket), id)
) WITH compression = {'class': 'ZstdCompressor'};

-- the days each entity has (for effective kinds, and to answer "which days does this trade have")
CREATE TABLE trading.entity_days (
    kind text, id text, business_date date,
    PRIMARY KEY ((kind, id), business_date)
) WITH CLUSTERING ORDER BY (business_date DESC);

-- the catalogue: each kind's business days, and which generation of each is current
CREATE TABLE trading.kind_days (
    kind text, business_date date, generation int, rows bigint, loaded_at timestamp,
    PRIMARY KEY (kind, business_date)
) WITH CLUSTERING ORDER BY (business_date DESC);
```

Why each part is shaped this way:

- **A partition per day per bucket.** Cassandra wants partitions well under about 100 MB. A million trades of about
  7 KB in `buckets: 128` partitions is about 7,800 rows and 55 MB uncompressed each (estimate), and adding a day writes
  only new partitions, never rewriting old ones. `buckets` is per kind and fixed for a day once written (it is
  recorded with the generation), so it can grow later for larger books.
- **`id` is the clustering key**, so a partition is sorted by id: one entity is one partition read of one row, and a
  day's ids come out sorted per bucket (merged for type-ahead).
- **Promoted fields as columns** let searches, pick lists, derived kinds, impact and reverse lookups read a day's few
  narrow columns, never documents. Names follow the Delta rule (dot → `__`) in lower case, since CQL folds unquoted
  names; the layout class keeps the mapping, as `AerospikeLayout` does for bins.
- **`entity_days`** answers "the latest day of this entity on or before D" in one read (`… WHERE kind = ? AND id = ?
  AND business_date <= ? LIMIT 1`), without scanning; seven years is about 1,760 small rows.
- **`kind_days`** tells the connector which days exist and which **generation** of each is current, without reading
  any data row.

## 5. How the connector reads

All statements are prepared once; the driver routes each to a replica that owns the partition (token-aware).

| Read | How | Expected cost (estimate) |
|---|---|---|
| **The catalogue** (every `refresh-seconds`, 60) | `kind_days` per kind: one small partition | a few ms |
| **One entity** on day D | choose the day (`snapshot` kind: the kind's newest day on or before D within `lookback-days`; `effective` kind: `entity_days`); then one row: `SELECT doc … WHERE business_date = ? AND generation = ? AND bucket = ? AND id = ?` | 2–5 ms on a LAN, then the document cache |
| **Type-ahead** | the newest day's `id` column, every bucket read in parallel, merged into Drishti's sorted `HitIndex`; rebuilt when `kind_days` shows a new day or generation | a million ids: seconds, in the background |
| **A day's columns** (searches, pick lists, derived kinds, impact) | `id` and the promoted columns of each bucket of the day, `scan-threads` (8) partitions at a time, paged (`page-size`, 5,000); the document column is never selected | a million rows of 19 fields: about 230 MB kept (`columns-cache-mb`) |
| **Reverse lookups** | from the day's promoted text columns, as on Aerospike; a kind without promoted columns reads at most `max-load-rows` (200,000) documents, else reports no referrers | |
| **Documents of a day** (`documents`) | bucket by bucket, paged | |

Partitions read by reverse lookups and searches belong to one day, so nothing ever scans the whole table (no
`ALLOW FILTERING`, no token-range scan of the table). A read of a day `kind_days` does not list answers "not held", so
the next connector for the kind is asked.

**Live changes.** The connector refreshes; it does not push. (Cassandra's change data capture is per node and on disk,
not a feed a client subscribes to; a Kafka connector in front of Cassandra's CDC is the way to push, and Drishti already
reads Kafka.)

## 6. Loading and replacing a day

The loader (`CassandraLoader`, in the plugin; `tools/load-cassandra.sh` and `tools/windows/load-cassandra.ps1`, the same
options as the other loaders: sample data, `--trades N --days D`, `--keep-days N`) writes with prepared statements, many
at once (bounded, `--in-flight` 256), grouped per partition (unlogged batches only within one partition, as Cassandra
recommends), at `LOCAL_QUORUM`.

**A day appears whole or not at all.** The loader writes a day under a new `generation` (the current one plus one),
writes the day's `entity_days` rows, and only then writes the day's `kind_days` row naming the new generation. Readers
follow `kind_days`, so they see the old generation until the new one is complete, then switch. The old generation's
partitions are deleted afterwards, a few whole-partition deletes (cheap tombstones), never row by row. Reloading a
day is the same steps.

**The schema.** With `--create` the loader creates the keyspace's tables (and adds a promoted column with `ALTER TABLE …
ADD`); without it, it prints the CQL for a database administrator and refuses to load into tables that do not match.
The keyspace itself (replication) is always the administrator's.

## 7. Retention

| Way | When |
|---|---|
| **TTL**: the loader writes with `USING TTL` (`--ttl-days N`) on a table with `TimeWindowCompactionStrategy` (one-day windows) | fixed retention; whole SSTables expire, nothing to run (the classic Cassandra time-series pattern) |
| **Deleting days**: `--keep-days N` deletes the oldest days' partitions and `kind_days` rows | retention that changes, or Keyspaces (its own TTL setting instead of compaction choice) |

As on Aerospike, `entity_days` rows may outlive their day's data: a read for such a day finds nothing and answers "not
held", so the next connector is asked. Dropping more than half of a kind's days needs `--force-drop`, as the lake
loaders' `LoadGuard` requires.

## 8. Seven years: sizing, and Cassandra with a lake

Estimates for a million trades a day, documents of about 7 KB compressing to about 1–1.5 KB with ZSTD:

| History in Cassandra | Rows | Data, one copy | With replication factor 3 |
|---|---|---|---|
| 90 business days | 90 million | about 110 GB | about 330 GB |
| 1 year (250 days) | 250 million | about 310 GB | about 0.9 TB |
| 7 years (1,760 days) | 1.76 billion | about 2.2 TB | about 6.6 TB |

Cassandra holds seven years (its indexes are on disk, unlike Aerospike's in-memory primary index), but most sites
will keep **recent days in Cassandra and history in Iceberg or Delta Lake**, exactly as with Aerospike: configure the
Cassandra connector first for the kind and the lake connector behind it; a read or search on a day Cassandra does not
hold falls through to the lake.

## 9. Security and connections

- **TLS** through Drishti's shared TLS settings (trust store, client certificate, host-name check), as every connector
  ([TLS.md](../connectors/TLS.md)).
- **Log-in**: user name and password (`${ENV_VAR}` references), Astra's secure connect bundle and token, Keyspaces' SigV4
  plug-in (AWS default credential chain).
- **No HTTP proxy**: the native protocol is TCP; a site that needs one uses a TCP tunnel. The connector says so when a
  proxy is configured.
- **Connections**: the driver's pool (`connections-per-node`), shown in **Admin → Connectors → this connector → Pool**
  (`poolStats`); nodes up and down in `health()`; a request timeout per statement; the driver's speculative executions
  off by default (reads are idempotent, so they can be turned on).
- **Read-only by default**: the connector's role needs only `SELECT` on the keyspace; the loader's role needs `MODIFY`
  (and `CREATE`, `ALTER` with `--create`).

## 10. Settings

| Setting | Default | Meaning |
|---|---|---|
| `contact-points` | required | `host:port` list (`9042`) |
| `local-datacenter` | required | the data centre the driver prefers (driver 4 needs it) |
| `keyspace` | the domain | the keyspace |
| `consistency` | `LOCAL_QUORUM` | read consistency; `LOCAL_ONE` for speed where staleness is acceptable |
| `buckets` | `128` | partitions per day for new loads (per kind with `buckets.<kind>`) |
| `page-size`, `scan-threads` | `5000`, `8` | paging and parallel partitions for a day's columns |
| `request-timeout-ms` | `5000` | per statement |
| `refresh-seconds`, `lookback-days`, `columns-cache-mb`, `doc-cache-mb`, `max-concurrent-reads`, `max-load-rows`, `reverse-index`, `index.days` | as for Aerospike | the same meaning |
| `username`, `password`, `secure-connect-bundle`, `token`, `sigv4`, `tls.*` | none | section 9 |

## 11. Tests

- **The dated-source contract** (`DatedSourceContract`, as every lake and store connector) against Cassandra 5 and
  ScyllaDB in Testcontainers (their own containers only; no existing container is touched); skipped without Docker.
- **Equality with Delta**: the sample data loaded into both; every read path (one entity, type-ahead, a day's columns,
  reverse lookups, derived kinds, impact) answers the same.
- **A day appears whole**: a reader polling during a reload never sees a mix of generations or a half-loaded day.
- **Partition bounds**: no statement without a full partition key, enforced by a test over the plugin's prepared
  statements (no `ALLOW FILTERING`).
- **Failure**: a node stopped mid-read (the driver retries on another replica); the whole cluster down (the connector
  reports it in Health and the next connector for the kind answers).
- **Windows**: the plugin's tests need nothing but the driver; one run on a Windows laptop before release.
- **Speed**: the Aerospike measurement table repeated for Cassandra at 10,000 trades a day (the local limit).

## 12. Build plan

| Step | Content | Done when |
|---|---|---|
| C1 | **Layout and loader**: `CassandraLayout`, `CassandraLoader` (generations, `--create`, TTL, `--keep-days`), the shell and PowerShell scripts, `tools/drishti.py` loader entry | the sample data loads into Cassandra 5 and ScyllaDB |
| C2 | **Reads**: catalogue, one entity, type-ahead, a day's columns, reverse lookups, documents, caches, `inventory`, `latestDate`, `dataVersion` | the dated-source contract and the equality test pass |
| C3 | **Security and stores**: TLS, log-ins, Astra bundle, Keyspaces SigV4, pool and health, the connector form's settings (`settingSpecs`) | TLS test; Keyspaces checked by hand before it is listed as supported |
| C4 | **Docs and pack wiring**: `CASSANDRA_CONNECTOR.md` (in the shape of the Aerospike document), example connector files under `config/connectors.examples/cassandra/`, CONNECTORS, CONFIGURATION, GETTING_STARTED, help cards, CHANGELOG | the release drill |

Size: about the Aerospike plugin. One builder.

## 13. Limits, risks and open questions

- **No time travel** (*known at*) in the first cut: Cassandra keeps write times per cell but not past versions. A
  later step could keep each generation's `loaded_at` and serve "known at" from the generation current then, until it is
  deleted.
- **Searches need promoted columns**, as on every connector; other fields are searched in documents (20,000 at most,
  `partial`).
- **Large partitions** if `buckets` is set too low for a book: the loader warns when a partition passes 100 MB and
  suggests a value.
- **Tombstones**: only whole partitions are ever deleted; row deletes are never issued, so reads never wade through them.
- **Open for the product owner**: the release; whether Amazon Keyspaces and Cosmos DB are in the first supported list
  or follow later.
