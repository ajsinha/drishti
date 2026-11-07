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
# Plain lakehouse tables: delta-external and delta-sharing

Two connectors read tables **other engines write, in their own columns**, without the `id`/`doc` shape Drishti's own loaders write and
without a shaped copy beside them:

- **`delta-external`**: plain Delta tables (Spark, Databricks, delta-rs, Trino), found by folder under `root` (local disk or `s3://`) or
  **by name in Unity Catalog** (`catalog.schema.table`, with Unity's vended credentials);
- **`delta-sharing`**: tables another organisation **shares through the open Delta Sharing protocol**, with a profile file and a bearer
  token: no storage keys.

Both map the columns in the connector file (the id, the business date, which columns show) and Drishti **builds the document view on
read**: a table is a kind, a row is an entity's document, every column is searchable. They use the reader of `iceberg-external`
([ICEBERG_EXTERNAL_CONNECTOR.md](ICEBERG_EXTERNAL_CONNECTOR.md)): drishti-lakehouse shows a Delta table, or a shared one, as an Iceberg
table, so the rules of rows as documents, ids, dates, modes, searches, scale and limits are **that document's** (sections 3 to 7 and
11), and they are not repeated here. Both only read.

Where the lakehouse fits as a whole (paths, Unity's Iceberg endpoint, shaped tables): [DATA_LAKE_GUIDE.md section
10](../guides/DATA_LAKE_GUIDE.md#10-connecting-to-an-existing-lakehouse-databricks-unity-catalog). Every setting:
[delta-external](../../plugins/drishti-plugin-delta-external/README.md#settings),
[delta-sharing](../../plugins/drishti-plugin-delta-sharing/README.md#settings), and
[CONFIGURATION.md](../admin/CONFIGURATION.md).

## Contents

1. [delta-external: plain Delta tables](#1-delta-external-plain-delta-tables)
2. [Unity Catalog: tables by name](#2-unity-catalog-tables-by-name)
3. [delta-sharing: shared tables](#3-delta-sharing-shared-tables)
4. [How a Delta table is read](#4-how-a-delta-table-is-read)
5. [Limits](#5-limits)
6. [How it is tested](#6-how-it-is-tested)

## 1. delta-external: plain Delta tables

```yaml
# config/connectors/trade-mart.yaml
plugin: delta-external
kinds: [trade, counterparty]
settings:
  root: s3://bank-lakehouse/marts              # local folders work too; no keys: the task role, else s3.access-key: ${…}
  s3.region: eu-west-2
  tables:
    trade:
      table: risk.trades                       # <root>/risk/trades
      id: [SOURCE_SYSTEM, TRADE_ID]            # MUREX:20000017
      columns: {exclude: [_ETL_LOAD_TS]}       # the rest of the row is the document
      links: {COUNTERPARTY_ID: counterparty}   # reverse lookups
    counterparty:
      table: refdata.counterparties
      id: CPTY_ID                              # no date column: current only
```

| Per kind (`tables.<kind>.`) | Default | Meaning |
|---|---|---|
| `table` | the kind | `namespace.table`: folders under `root` |
| `id`, `id-separator` | none (a Delta table has no identifier fields), `:` | the id columns; always named |
| `date`, `date-from`, `date-zone` | the date partition column | the business date (`PARTITIONED BY (business_date)` needs nothing) |
| `columns.exclude`, `columns.rename.<column>` | none | what the document leaves out; names in it |
| `document` | `row` | `column:<name>`: a JSON column is the document |
| `mode`, `links.<column>`, `depth` | `snapshot`, none, `2` | as `iceberg-external` |

`tables: auto` with `namespace: risk` serves every table of the folder (each still needs its `id`). A table without a `_delta_log`,
or without an id named, is not served and Health says why: `trade: no table risk.trades: s3://…/risk/trades/_delta_log is not there`,
`trade (risk.trades): no id: … set tables.trade.id`.

## 2. Unity Catalog: tables by name

With `unity.host` set, `tables.<kind>.table` is a Unity Catalog name. Drishti asks Unity's open REST API for the table's **storage
location** and its **temporary credentials** (credential vending), reads the Delta log and files there, and renews the credentials
`unity.renew-before-seconds` (300) before they expire (the old ones serve while Unity is away, until they really expire).

```yaml
# config/connectors/trade-unity.yaml
plugin: delta-external
kinds: [trade]
settings:
  unity.host: https://adb-1234567890.12.azuredatabricks.net
  unity.client-id: ${UC_CLIENT_ID}            # OAuth machine-to-machine; or unity.token: ${UC_TOKEN}
  unity.client-secret: ${UC_CLIENT_SECRET}
  unity.catalog: main
  s3.region: eu-west-2                         # the tables' bucket region
  tables:
    trade: {table: risk.trades, id: TRADE_ID}  # main.risk.trades
```

Secrets are `${ENV}` or `file:` references, as every connector's; they are never written in a file, a log or a message. **Grants are
Unity's**: the principal needs `USE CATALOG`, `USE SCHEMA`, `SELECT`, and `EXTERNAL USE SCHEMA` for vending; Drishti adds no
permission of its own (how Unity's grants meet Drishti's roles is 2.9.11.i's security discussion, in the
[under-construction registry](../admin/UNDER_CONSTRUCTION.md)). Refusals are said in words: `table not found`, `no grant`, `credential
vending is off`, `refused the sign-in`, `a view has no files`, `not Delta`. The shaped (`id`/`doc`) form of the same, for the `delta`
connector, is [DELTA_CONNECTOR.md section 18](DELTA_CONNECTOR.md#18-tables-named-in-unity-catalog).

## 3. delta-sharing: shared tables

```yaml
# config/connectors/vendor-prices.yaml
plugin: delta-sharing
kinds: [price]
settings:
  profile: /etc/drishti/shares/vendor.share
  tables:
    price: {table: market_data.eod.prices, id: [ISIN, CURRENCY], date: PRICE_DATE}   # share.schema.table
  hints.market_data.eod.prices: PRICE_DATE >= 2026-01-01
```

The **profile file** is the one a provider hands out (`shareCredentialsVersion` 1, `endpoint`, `bearerToken`), with one change: the
token is an **environment reference**, never the token itself:

```json
{"shareCredentialsVersion": 1, "endpoint": "https://sharing.vendor.example/delta-sharing/", "bearerToken": "${VENDOR_SHARING_TOKEN}"}
```

A profile holding the token itself is refused in words. Without a profile: `endpoint` and `bearer-token: ${…}`. The connector lists the
shares, schemas and tables the token may see, asks for a table's files at its current version (`POST …/query`, with the
`hints.<share.schema.table>` comparisons as the protocol's predicate hints, so the server may leave out files that cannot match), and
reads their Parquet through the **pre-signed URLs** by ranged GETs. A URL is asked for again a minute before it expires, or when the
store refuses it; the files keep stable names inside Drishti, so caches survive a renewal. A new version of the table is seen at the next
refresh (`refresh-seconds`). Refusals in words: `refused the bearer token (HTTP 401): it is wrong or expired`, `not shared with this
recipient (HTTP 404)`, `no access (HTTP 403)`.

**Out of scope:** change data feed and streaming. **Not yet** (registry): a share's history (every *known at* reads the current
version), tables shared in the `delta` response format (with deletion vectors or column mapping), OAuth profiles (version 2).

## 4. How a Delta table is read

drishti-lakehouse turns a Delta table into what the Iceberg reader plans with, version by version:

- **The log** is replayed by Delta Kernel (checkpoints, protocol checks: a reader feature Kernel does not know is refused by name); the
  last `history-versions` (30) versions become snapshots, at their commit times, for *known at*. A share serves its current version only.
- **The schema**: each column gets a field id (its column-mapping id when the table has one) and a name-mapping entry naming its
  **physical** column, so files written without field ids (Spark, Databricks, delta-rs) are read by name, and column mapping by name or id
  works. Types: string, the integers, float, double, decimal, boolean, binary, date, timestamp (INT96 too), timestamp_ntz, structs,
  arrays, maps; `variant` is refused by name. Every version is read with the current schema.
- **The files**: each `add` is a file with its partition values (partition columns become identity partitions: their values come from
  the log, not the file) and the log's statistics as column bounds (a string bound of 32 characters or more is not trusted), so a day's
  files and one entity's files are pruned as in an Iceberg table.
- **Deletion vectors** (Databricks' rows deleted in place) become position deletes of their file: those rows are left out.

## 5. Limits

- **Read-only**; maintenance (`OPTIMIZE`, `VACUUM`) is the owners'. A version vacuumed away cannot be read for *known at*.
- **Stores:** local disk and S3 (and S3-compatible ones); Unity tables in Azure (ADLS) or Google Cloud Storage are refused in words for
  now (registry); a share reads whatever store its URLs point to.
- **Ids:** a Delta table has no identifier fields: `tables.<kind>.id` is always named.
- The rest is `iceberg-external`'s (an unsorted table's first lookup of a day builds its id index; effective kinds are not read as
  columns): [ICEBERG_EXTERNAL_CONNECTOR.md section 11](ICEBERG_EXTERNAL_CONNECTOR.md#11-limits).

## 6. How it is tested

No real cloud. drishti-lakehouse writes a **Spark-shaped Delta table** in the test (Parquet without field ids, INT96 timestamps, a
struct and an array, partitioned by `business_date`, two commits with statistics; also with column mapping by name and with a deletion
vector) and reads it as Iceberg (`DeltaAsIcebergTest`); a **mock Unity Catalog** answering as Databricks does (tables, vended AWS
credentials that expire, OAuth M2M, the refusals) with the table in S3Mock in the test JVM (`UnityCatalogTest`: reads, renewal, the
old keys while Unity is away, every refusal); a **mock Delta Sharing server** serving the table's Parquet at pre-signed-style links that
expire and honour `Range` (`DeltaSharingTest`: listing, hints, ranged reads, renewal near expiry and after an early refusal, the
refusals, profiles). The connectors are tested end to end in `DeltaExternalPluginTest` (documents, dates, search, columns, reverse
lookups, a row deleted in place, Unity names, refusals) and `DeltaSharingPluginTest`; plain Iceberg tables in `IcebergExternalPluginTest`.
Not yet run against a Databricks workspace or a provider's sharing server.
