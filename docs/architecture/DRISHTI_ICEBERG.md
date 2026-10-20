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
# drishti-iceberg: Drishti's own Apache Iceberg engine, without Hadoop

Status: **design, being built for 2.6.0** (product owner, 2026-10-20, after send and push and integration moved past 3.0.0). Related: [ICEBERG_CONNECTOR.md](../connectors/ICEBERG_CONNECTOR.md) (the connector today),
[DELTA_CONNECTOR.md section 16](../connectors/DELTA_CONNECTOR.md#16-engines-native-and-hadoop) (the same move made for
Delta Lake), [GETTING_STARTED.md: Windows](../guides/GETTING_STARTED.md#drishti-on-windows).

## Contents

1. [The ask](#1-the-ask) · 2. [Principles](#2-principles) · 3. [Why not keep the library and drop Hadoop](#3-why-not-keep-the-library-and-only-drop-hadoop) ·
4. [The picture](#4-the-picture) · 5. [What of the protocol is covered](#5-what-of-the-protocol-is-covered) ·
6. [Catalogs](#6-catalogs) · 7. [Reading](#7-reading) · 8. [Writing and commits](#8-writing-and-commits) ·
9. [Files: local disk and S3](#9-files-local-disk-and-s3) · 10. [Engines and configuration](#10-engines-and-configuration) ·
11. [Windows](#11-windows) · 12. [Proving it keeps to the protocol](#12-proving-it-keeps-to-the-protocol) ·
13. [Build plan](#13-build-plan) · 14. [Risks and open questions](#14-risks-and-open-questions)

## 1. The ask

The product owner (2026-10-20): can Drishti have **its own Iceberg implementation that keeps to the protocol but does
not need Hadoop**? Today the Iceberg connector (`plugins/drishti-plugin-iceberg`) reads and writes through Apache
Iceberg's Java library (`iceberg-core`, `iceberg-parquet`, `iceberg-data`, `iceberg-aws`). Path-based tables go through
Hadoop's file systems, which on Windows need `winutils.exe` and `HADOOP_HOME`. So Iceberg is the one lake format that
does not run on a Windows laptop out of the box, and the loader (`tools/load-iceberg.sh`) is bash only.

Delta Lake had the same problem and solved it with `drishti-deltalake`, the native engine (`java.nio`, the AWS SDK,
parquet-java over its own input files, no Hadoop `FileSystem`, `Shell` or `Configuration`). This design does the same for
Iceberg: a module **`drishti-iceberg`** that implements the parts of the Iceberg table spec and the REST catalog
protocol Drishti uses, with no Hadoop.

## 2. Principles

- **The spec is the contract, not the library.** Tables written by `drishti-iceberg` are ordinary Iceberg tables that
  Spark, Trino, Snowflake, DuckDB and pyiceberg read; tables they write are read by `drishti-iceberg`. What it does not
  support it **refuses by name** (the table, the file, the feature), never reads wrongly.
- **No Hadoop at run time.** No `org.apache.hadoop` class is loaded; a test proves it with a class loader that refuses
  them, as `DeltaNativeNoHadoopTest` does for Delta. (Hadoop's jars may stay on the class path while parquet-java names
  its types in signatures, exactly as for the native Delta engine.)
- **Same connector, same settings.** Packs, connector files, the layout (`IcebergLayout`), the caches and every read path
  in [ICEBERG_CONNECTOR.md section 7](../connectors/ICEBERG_CONNECTOR.md#7-how-the-connector-reads) are unchanged. Only
  what sits under them changes. A user sees a new `engine` setting and nothing else.
- **Share with Delta.** File access (local disk, S3, byte ranges), the Parquet page codecs and the row-group reader
  already exist in `drishti-deltalake`; they move into a shared module and both engines use them.
- **Small and owned.** Only what Drishti needs: Parquet data files, format versions 1 and 2, path-based and REST
  catalogs. Format version 3 is a follow-up ([section 5](#5-what-of-the-protocol-is-covered)).

## 3. Why not keep the library and only drop Hadoop

The smallest change would keep `iceberg-core` and replace only the Hadoop parts: our own `FileIO` over `java.nio`, our
own path-based `TableOperations`. That was considered and is **not** the recommendation:

| | Keep the library, swap its file access | Own engine (`drishti-iceberg`) |
|---|---|---|
| Work | small | about the size of `drishti-deltalake` (about 3,200 lines today), plus a writer |
| Hadoop | `iceberg-parquet` is built on `parquet-hadoop` (Hadoop `Configuration`, codec loading); the writer path and `GenericDeleteFilter` stay tied to it, so a Hadoop-free run is not guaranteed | none loaded, proven by test |
| Dependencies | iceberg-core, iceberg-parquet, iceberg-data, iceberg-aws and what they bring | Avro (manifests), parquet-java (already used), the AWS SDK (already used), Jackson (already used) |
| Parquet versions | Iceberg's choice of Parquet must match Delta Kernel's (today 1.10.1 was picked for that reason) | one Parquet, Drishti's choice |
| Format version 3 | arrives when a library upgrade fits the rest of the server | added when Drishti needs it |
| Footer caching | Iceberg's reader re-reads the footer on every single-entity read (ICEBERG_CONNECTOR.md section 13) | footers cached per file (data files never change) |
| Risk | low | conformance: covered by the interop suite in [section 12](#12-proving-it-keeps-to-the-protocol) |

The library stays available as the `library` engine for what the native engine does not read (Azure, Google Cloud
Storage, HDFS), as `hadoop` does for Delta.

## 4. The picture

```
 IcebergSourcePlugin (unchanged read paths: id maps, single reads, column sets, type-ahead, reverse lookups)
          │
          ▼  IcebergTables  (interface: refresh, plan a snapshot, read a file, commit)
   ┌──────┴───────────────────────────────┐
   │ engine: native                        │ engine: library
   ▼                                       ▼
 drishti-iceberg                         Apache Iceberg Java (today's code, Hadoop file systems)
   ├── catalog/   path-based, static, REST (OAuth2, SigV4, vended credentials)
   ├── meta/      table metadata JSON, schemas, specs, sort orders, snapshots, refs   (Jackson)
   ├── manifest/  manifest lists and manifests                                       (Avro, read and write)
   ├── scan/      planning, partition and metric pruning, delete matching
   ├── read/      Parquet by field id, schema evolution, position and equality deletes
   └── write/     Parquet with field ids and metrics, manifests, commits, maintenance
          │
          ▼
 drishti-lakeio (shared with drishti-deltalake): Storage (local, S3), RangeReader, page codecs, footer cache
```

## 5. What of the protocol is covered

Against the Apache Iceberg table spec (format versions 1 and 2) and the REST catalog OpenAPI spec:

| Area | Covered | Refused or later |
|---|---|---|
| Table metadata | every v1/v2 field; fields it does not know are **kept as they are** when it writes new metadata | |
| Types | boolean, int, long, float, double, decimal, date, time, timestamp, timestamptz, string, uuid, fixed, binary, struct, list, map | v3 types (timestamp_ns, variant, geometry, unknown): refused, later |
| Schema evolution | by field id: add, drop, rename, reorder, int→long, float→double, decimal widening; `schema.name-mapping.default` for files without ids | |
| Partitioning | identity, bucket (the spec's Murmur3 hashing per type), truncate, year, month, day, hour, void; several specs (partition evolution) | |
| Manifests | manifest lists and manifests (Avro), data and delete manifests, status, inherited snapshot ids and sequence numbers, partition summaries, column metrics with the spec's single-value binary bounds | |
| Deletes | position deletes; equality deletes; the sequence-number rules for which delete applies to which data file | v3 deletion vectors (Puffin): later, reusing the Delta engine's roaring bitmap reader |
| Snapshots | current snapshot, snapshot log (time travel by *known at*), the `main` ref | reading a named branch or tag: later |
| Data files | Parquet | ORC, Avro data files: refused, naming the file |
| Encryption | | refused (a `key_metadata` present) |
| Catalogs | path-based (`version-hint.text`), a metadata file named directly, REST | Hive metastore, JDBC catalog (both reachable through a REST catalog in front of them) |
| Views | | not needed |

## 6. Catalogs

**Path-based** (`catalog: hadoop` today; the name stays, it no longer means Hadoop). A table is a folder:
`metadata/version-hint.text` names the current version, `metadata/vN.metadata.json` is it. With no hint the highest
`vN` is found by listing. This is the convention of Iceberg's `HadoopTables`, so tables move between the two engines
unchanged.

**A metadata file named directly** (`metadata-location: …/v12.metadata.json`): read-only, for a table someone else
owns.

**REST** (`catalog: rest`), the protocol of Apache Polaris, Snowflake Open Catalog, AWS Glue's Iceberg endpoint, Unity
Catalog, Nessie, Lakekeeper and Gravitino, over the JDK's HTTP client:

| Call | Use |
|---|---|
| `GET /v1/config` | server defaults and overrides, the `prefix` |
| OAuth2 client credentials (`/v1/oauth/tokens` or `oauth2-server-uri`), or a fixed `token` | the bearer token, refreshed before it expires |
| `GET …/namespaces/{ns}/tables/{t}` | the metadata location, the metadata, and the table's `config` (vended S3 credentials, refreshed when they expire) |
| `POST …/namespaces/{ns}/tables/{t}` | a commit: requirements (`assert-table-uuid`, `assert-ref-snapshot-id`, `assert-current-schema-id`, …) and updates (`add-snapshot`, `set-snapshot-ref`, `add-schema`, `set-properties`, `remove-snapshots`) |
| SigV4 request signing | AWS Glue, with the AWS SDK's signer (no Hadoop) |

Every setting the connector takes today (`uri`, `warehouse`, `credential`, `token`, `scope`, `oauth2-server-uri`,
`prefix`, `catalog.<key>`) keeps its meaning.

## 7. Reading

1. **Refresh** (every `refresh-seconds`): path-based, read `version-hint.text` and, when it changed, the new metadata
   JSON; REST, `loadTable` (with the metadata location's ETag, so an unchanged table costs one small answer).
2. **Plan a snapshot**: read its manifest list; drop manifests whose partition summaries cannot hold the days asked;
   read the rest on virtual threads; drop entries by partition value and column bounds; attach to each data file the
   delete files that apply (position deletes of the same partition with a sequence number at or after the file's;
   equality deletes with a sequence number after it). The result is the same per-day **layout** the connector keeps
   today, with the same per-day fingerprints.
3. **Read a file**: the footer from the footer cache (new: read once per file, since data files never change; this
   removes the larger part of a first single-entity read, ICEBERG_CONNECTOR.md section 13); columns matched **by
   field id**, not by name, so renamed columns read correctly; columns added after the file was written read as null;
   promoted types widened; row groups skipped by their statistics for `id = X`; pages decompressed by the shared codecs
   (Snappy, ZSTD, GZIP, LZ4, LZ4_RAW).
4. **Apply deletes**: position deletes as a sorted set of row positions per file; equality deletes as a set of key
   tuples per file (kept by memory, as today); `cacheStats` keeps counting `daysWithDeletes`.

Time travel by *known at* uses the snapshot log, its schema and its files, exactly as today.

## 8. Writing and commits

Writing serves the loader (`IcebergLoader`) and maintenance (`IcebergMaintenance`); the connector itself only reads.

**Data files.** parquet-java's writer over Drishti's own output files, configured with `PlainParquetConfiguration` (no
Hadoop `Configuration`) and Drishti's own compression codecs (parquet-java's `CompressionCodecFactory` is an interface;
the native engine supplies one). The Parquet schema carries Iceberg **field ids**. Column metrics (value counts, null
counts, lower and upper bounds, truncated as `write.metadata.metrics.*` says) are taken from the finished footer. Every
table property in ICEBERG_CONNECTOR.md section 3 is honoured.

**Operations**, each one commit:

| Operation | Used by |
|---|---|
| append (new data files, a new manifest) | loading a new day |
| replace a partition (old files marked deleted, new ones added) | reloading a day |
| delete whole partitions (metadata only) | `--keep-days` |
| rewrite files, validated against the snapshot read (no file of that day added or deleted since) | `--relayout` |
| rewrite manifests (one per month of business dates) | `--rewrite-manifests` |
| expire snapshots, then delete the files only they referenced | `--expire-hours` |
| add columns, set properties | `--columns`, layout properties |

**How a commit is made safe:**

| Catalog | Commit | Two writers at once |
|---|---|---|
| path-based, local disk (Windows included) | write `vN+1.metadata.json` to a temporary name, then **create** it under its final name with a hard link, which fails if the name exists; then replace `version-hint.text` | the loser sees the name taken, re-reads, re-applies its change on the new version, retries (`commit.retry.num-retries`) |
| path-based, S3 | a **conditional PUT** (`If-None-Match: *`) of `vN+1.metadata.json` | as above; a store that does not honour conditional writes is detected at start and the table is marked **single-writer** (today's rule, ICEBERG_CONNECTOR.md section 4) |
| REST | the catalog's commit with requirements | the catalog answers 409; re-read, re-apply, retry |

A re-applied commit re-validates: an append always applies again; a rewrite of a day fails (and is reported) if that
day changed in between. Metadata files older than `write.metadata.previous-versions-max` are removed after a commit,
as today. Files written by a commit that finally failed are deleted at once (no orphans from Drishti's own writers).

## 9. Files: local disk and S3

From the shared module (today's `drishti-deltalake` classes, moved):

- **Local disk**: `java.nio`, with Windows drive letters, backslashes and UNC shares (`LocalPaths`).
- **S3 and S3-compatible stores** (MinIO, Ceph): the AWS SDK v2, one pooled client per connector, `ListObjectsV2`,
  ranged GETs with the `s3.read-block-kb` read-ahead, TLS and proxy settings as today (`TlsS3FileIO`'s settings carry
  over unchanged).
- **Not read**: `abfs://`, `gs://`, `hdfs://`. A native-engine connector with such a root refuses to start, saying to
  use `engine: library`.

## 10. Engines and configuration

| `engine` | What reads and writes | Use it for |
|---|---|---|
| `native` (default once built) | `drishti-iceberg` | everything on local disk and S3; Windows |
| `library` | Apache Iceberg Java over Hadoop (today's code) | Azure, Google Cloud Storage, HDFS; a fallback |
| `auto` | `native` on Windows, `library` elsewhere | the first release, while `native` earns trust |

`DRISHTI_ICEBERG_ENGINE` sets it for every connector, as `DRISHTI_DELTA_ENGINE` does for Delta. The loader and
maintenance take `--engine`. All other settings (ICEBERG_CONNECTOR.md section 15) are unchanged.

## 11. Windows

- The connector runs with no `winutils.exe` and no `HADOOP_HOME`, and the GETTING_STARTED Windows table loses its
  Iceberg row.
- `tools/windows/load-iceberg.ps1` beside `load-delta.ps1`: sample data and `--trades N --days D`, same options as
  `load-iceberg.sh`; the spill folder defaults under the data folder, never the system temp folder.
- `tools/windows/start-server.ps1` takes `-Iceberg` to start with the Iceberg connector files.
- Tutorial: the Iceberg steps of GETTING_STARTED gain PowerShell next to bash.

## 12. Proving it keeps to the protocol

| Test | What it proves |
|---|---|
| **Interop, read**: fixture tables written by **pyiceberg** (pure Python, independent of the Java library) and by the `library` engine: v1 and v2, each transform, partition evolution, each schema change, position and equality deletes, inherited metadata, files without field ids plus a name mapping | `drishti-iceberg` reads what others write; answers equal to pyiceberg's, row for row |
| **Interop, write**: tables written by `drishti-iceberg` read back by pyiceberg, by DuckDB's `iceberg` extension and by the `library` engine | others read what Drishti writes |
| **Metadata round trip**: every fixture's metadata read and written back; unknown fields survive | nothing is lost on rewrite |
| **Engine equality**: every existing plugin test (`IcebergSourcePluginTest`, the dated-source contract, TLS, proxy, REST catalog) runs on both engines; one test compares their answers on the same lakes | same connector behaviour |
| **No Hadoop**: the connector, the loader and maintenance run in a class loader that refuses `org.apache.hadoop` | the goal itself |
| **Concurrent commits**: two writers on local disk, on MinIO, and through a REST catalog (Testcontainers, its own containers only) | no lost commit; a conflicting rewrite reported |
| **Refusals**: ORC file, encrypted table, v3 table, unknown type | refused by name, never read wrongly |
| **Windows paths**: drive letters, backslashes, UNC, on every OS; one real run on a Windows laptop before release | Windows works |
| **Speed**: the Delta engines' benchmark table (section 16 of DELTA_CONNECTOR.md) for Iceberg, both engines | native at least as fast; first single reads faster (footer cache) |

Fixture tables are small, synthetic and committed under the plugin's test resources (no real data).

## 13. Build plan

Five steps, each merged with its tests and docs; the `library` engine stays the default until step 5.

| Step | Content | Done when |
|---|---|---|
| I1 | **Shared lake I/O**: move `Storage`, `LocalStorage`, `S3Storage`, `LocalPaths`, `RangeReader`, the page codecs and the row-group reader from `drishti-deltalake` into `drishti-lakeio`; add the footer cache | every Delta test passes unchanged |
| I2 | **Read**: metadata, path-based and direct catalogs, manifests, planning with pruning, field-id Parquet reads, schema evolution, deletes, time travel; the `engine` setting | interop-read suite, engine equality, no-Hadoop test |
| I3 | **REST catalog**: config, OAuth2, loadTable with ETag, vended credentials, SigV4 | the REST catalog test on both engines |
| I4 | **Write**: Parquet with field ids and metrics, manifests, the three commit kinds, the operations of section 8; loader and maintenance on the native engine; `load-iceberg.ps1` | interop-write suite, concurrent-commit tests, maintenance tests on both engines |
| I5 | **Switch and document**: `native` the default, speed table measured, ICEBERG_CONNECTOR.md, GETTING_STARTED (Windows), TOOLS, CONFIGURATION, CHANGELOG, help cards | the Windows laptop run; the release drill |

Size: about the native Delta engine for reading (I2–I3) plus about as much again for writing (I4). One builder.

## 14. Risks and open questions

- **Equality deletes** are the most intricate part of the spec (several key columns, nulls, type promotion of the
  keys). Mitigation: the interop suite writes them with pyiceberg in every shape, and the library engine stays as a
  fallback.
- **S3 stores without conditional writes** would silently allow two path-based writers. Mitigation: a probe at start
  (two creates of a probe key; the second must be refused) and single-writer mode otherwise.
- **Parquet writer without Hadoop**: parquet-java's writer has a Hadoop-free configuration and a pluggable codec
  factory; step I4 starts with a spike that confirms no Hadoop class loads when writing zstd files.
- **Keeping up with the spec**: format version 3 (deletion vectors, row lineage, new types) and new REST endpoints.
  Each is added when a real table needs it; until then they are refused by name.
- **Open for the product owner**: the release (not decided); whether `auto` or `native` is the default in the first
  release that ships it; whether format version 3 reading (deletion vectors) is part of the first release.
