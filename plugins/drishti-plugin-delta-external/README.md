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
---
connector: delta-external
category: plugin
summary: Serves plain Delta tables (Spark, Databricks, delta-rs, Trino; any columns, no id/doc shape) as Drishti kinds, the document built on read from the mapped columns; tables by folder under a root or by name in Unity Catalog, with Unity's vended credentials.
capabilities:
- fetch
- search
- history
- reverse-lookup
- bulk-scan
settings:
- tables.*
- tables
- namespace
- root
- unity.host
- unity.token
- unity.client-id
- unity.client-secret
- unity.oauth-token-uri
- unity.catalog
- unity.schema
- unity.vend
- unity.renew-before-seconds
- unity.timeout-seconds
- history-versions
- decimals
- binary
- id-index-mb
- id-index-warn-rows
- max-index-rows
- refresh-seconds
- lookback-days
- doc-cache-mb
- columns-cache-mb
- max-concurrent-reads
- max-queued-reads
- source-name
- s3.endpoint
- s3.region
- s3.path-style
- s3.access-key
- s3.secret-key
- s3.read-block-kb
- tls.*
- proxy.*
auth: Local files need none; S3 takes s3.access-key and s3.secret-key (${ENV} or file:) or the AWS chain; Unity Catalog takes unity.token, or unity.client-id with unity.client-secret (OAuth machine-to-machine), and then vends each table's temporary S3 credentials (renewed before they expire).
licence: Drishti's own readers (drishti-lakehouse over Delta Kernel and drishti-iceberg, no Hadoop I/O), with Jackson and the AWS SDK for Java v2 (all Apache-2.0), bundled.
windows: Works the same on Windows (no Hadoop and no winutils.exe; Windows paths under root are fine).
doc: docs/connectors/LAKEHOUSE_CONNECTORS.md
---
# The delta-external connector

Plugin `delta-external` (`plugins/drishti-plugin-delta-external`): Delta tables as their owners wrote them, read in place. A table is a
kind, a row is a document, every column is searchable without a pack declaring it. For tables in Drishti's own `id`/`doc` layout use
`delta`, which reads that layout faster.

## What it reads

Delta tables found by folder under `root` (local disk or `s3://`; `tables.<kind>.table: mart.trades` is `<root>/mart/trades`), or by
name in Unity Catalog (`unity.host`; `tables.<kind>.table: main.trading.trades`), where Unity says the table is, with the temporary
credentials Unity vends for it. The Delta log is replayed by Delta Kernel (checkpoints; a reader feature Kernel does not know is refused by
name); column mapping (name or id), deletion vectors (rows deleted in place are left out), INT96 timestamps, structs, arrays and maps are
read. The last `history-versions` versions answer *known at*. It **never writes**. Change data feed and streaming are not read. Unity
tables in Azure (ADLS) or Google Cloud Storage are refused in words for now (under construction).

## Settings

<!-- settings:begin -->
| Setting | Type | Default | Meaning |
|---|---|---|---|
| `tables.*` | string |  | Per kind (the columns mapped, the document built on read): tables.<kind>.table (namespace.table: folders under root, or catalog.schema.table in Unity Catalog), id (columns, joined by id-separator), id-separator (:), date (default: the date partition column), date-from (day), date-zone (UTC), mode (snapshot\|effective), document (row\|column:<name>), columns.exclude, columns.rename.<column>, links.<column> (a kind), depth (2); or tables: auto with namespace |
| `tables` | string |  | auto: every table of the namespace is a kind (its name in lower case with -); each still needs its id (tables.<kind>.id) |
| `namespace` | string |  | The namespace of tables: auto (folders under root, or catalog.schema) |
| `root` | path |  | The folder or s3:// prefix the tables are under (tables.<kind>.table names the folders below it); not with unity.host |
| `unity.host` | string |  | Unity Catalog's workspace or server (https://<workspace>.cloud.databricks.com, http://localhost:8080): tables are then named catalog.schema.table and found where Unity says, read with its vended credentials (DATA_LAKE_GUIDE.md section 10) |
| `unity.token` | string |  | A bearer token for Unity Catalog (a personal access token or a service principal's): ${ENV_VAR} or file:/path (secret: give `${ENV_VAR}` or `file:/path`, never the value) |
| `unity.client-id` | string |  | OAuth machine-to-machine: the service principal's client id (with unity.client-secret) |
| `unity.client-secret` | string |  | OAuth machine-to-machine: the client secret, ${ENV_VAR} or file:/path (secret: give `${ENV_VAR}` or `file:/path`, never the value) |
| `unity.oauth-token-uri` | string |  | Where OAuth tokens come from (default: <unity.host>/oidc/v1/token) |
| `unity.catalog` | string |  | The catalog of a table named schema.table or table |
| `unity.schema` | string |  | The schema of a table named table |
| `unity.vend` | boolean | `true` | Read with the table's temporary credentials from Unity (credential vending); false: with this connector's own s3.* settings or the AWS chain |
| `unity.renew-before-seconds` | int | `300` | Vended credentials are renewed this long before they expire; the old ones serve while Unity cannot be reached, until they really expire |
| `unity.timeout-seconds` | int | `30` | Each call to Unity Catalog's API at most |
| `history-versions` | int | `30` | How many recent Delta versions of each table are kept for known-at reads |
| `decimals` | one of number, string | `number` | Decimals in documents as JSON numbers, or as text keeping full precision |
| `binary` | one of base64, omit | `base64` | Binary columns in documents as Base64 text, or left out |
| `id-index-mb` | int | `1024` | Memory for the id indexes of tables not sorted by their id (about 60 B per id; least-used days dropped first) |
| `id-index-warn-rows` | int | `5000000` | Health suggests sorting a table by its id when one day's id index passes this |
| `max-index-rows` | int | `10000000` | Most rows type-ahead and column reads take from one day (or a table with no date); partial beyond it |
| `refresh-seconds` | int | `10` | How often each table's current version is checked |
| `lookback-days` | int | `10` | How far back a snapshot kind looks for the newest date on or before the one asked |
| `doc-cache-mb` | int | `256` | Memory for recently read documents |
| `columns-cache-mb` | int | `1024` | Memory for a day's searchable columns |
| `max-concurrent-reads` | int | `16` | Documents read at once at most |
| `max-queued-reads` | int | `4096` | Document reads that may wait for a slot; more are refused at once, and a read whose caller has gone leaves the queue |
| `source-name` | string | `delta-external` | The connector's name in provenance (delta-external) |
| `s3.endpoint` | string |  | S3-compatible endpoint |
| `s3.region` | string |  | S3 region (also for Unity tables in S3: the bucket's region) |
| `s3.path-style` | boolean | `true` | Path-style S3 addressing |
| `s3.access-key` | string |  | S3 access key: ${ENV_VAR} or file:/path (secret: give `${ENV_VAR}` or `file:/path`, never the value) |
| `s3.secret-key` | string |  | S3 secret key: ${ENV_VAR} or file:/path (secret: give `${ENV_VAR}` or `file:/path`, never the value) |
| `s3.read-block-kb` | int | `1024` | The smallest ranged GET |

Every connector also takes `source-name`, `mode.*`, `domain`, `stale-after`, `union` (docs/connectors/CONNECTOR_FILES.md). It takes the shared `tls.*` settings (docs/connectors/TLS.md). It takes the shared `proxy.*` settings for an outbound HTTP proxy (CONNECTOR_DEVELOPER_GUIDE.md).
<!-- settings:end -->

## Data structure

Depends on the source; mapping via `tables.<kind>.*`, as for `iceberg-external`:

| Delta type | In the document |
|---|---|
| boolean, byte, short, integer, long, float, double | JSON boolean and number |
| decimal(P,S) | a number; `decimals: string` keeps full precision as text |
| date, timestamp, timestamp_ntz | ISO-8601 text (`2026-09-30`, `2026-09-30T10:01:00Z`) |
| string | text |
| binary | Base64 text (or left out with `binary: omit`) |
| struct / array / map | an object / an array / an object when keys are text, else an array of `{key, value}` |

- **Document:** the whole row (`document: row`, the default) or one JSON column (`document: column:<name>`); `columns.exclude` and
  `columns.rename` adjust it.
- **Id rule:** `tables.<kind>.id`, one column or several joined by `id-separator` (`:`). A Delta table has no identifier fields, so the
  id is always named.
- **Business date:** `date` (a date column, or a timestamp with `date-from: day`); by default the date partition column (Spark's
  `PARTITIONED BY (business_date)`); with neither the kind is current only.
- **Mode:** `snapshot` (every entity every day) or `effective` (a row when the entity changes). `links.<column>: <kind>` names columns
  that hold other kinds' ids, for reverse lookups.
- **One entity:** the day's files pruned by the log's statistics; a table sorted or Z-ordered by its id reads one row group, an unsorted
  one builds an id index per day once (about 60 B an id, `id-index-mb`).

## Examples

```yaml
# config/connectors/trade-mart.yaml: a Spark job's Delta tables in S3
plugin: delta-external
kinds: [trade]
settings:
  root: s3://bank-lakehouse/marts
  s3.region: eu-west-2                              # no keys: the task role
  tables:
    trade:
      table: risk.trades                            # s3://bank-lakehouse/marts/risk/trades
      id: [SOURCE_SYSTEM, TRADE_ID]
      links: { COUNTERPARTY_ID: counterparty }
```

```yaml
# config/connectors/trade-unity.yaml: the same tables named in Unity Catalog
plugin: delta-external
kinds: [trade]
settings:
  unity.host: https://adb-1234567890.12.azuredatabricks.net
  unity.client-id: ${UC_CLIENT_ID}
  unity.client-secret: ${UC_CLIENT_SECRET}
  s3.region: eu-west-2
  tables:
    trade: {table: main.risk.trades, id: TRADE_ID}
```

Ready-made files: `config/connectors.examples/other/delta-external-spark-mart.yaml` and `delta-external-unity.yaml`.

## Troubleshooting

- `DEGRADED: trade: no table mart.trades: <root>/mart/trades/_delta_log is not there`: fix `root` or `tables.<kind>.table`.
- `… no id: the table has no identifier fields; set tables.<kind>.id`: name the id columns.
- `… table not found: Unity Catalog at <host> has no main.risk.trades`, `… no grant: … an owner grants USE CATALOG, USE SCHEMA and
  SELECT`, `… credential vending is off for …: the metastore must allow external data access, and the principal needs EXTERNAL USE
  SCHEMA`, `… refused the sign-in (HTTP 401)`: Unity's answer, in words; the grant is Unity's to give.
- `… is a VIEW: a view has no files of its own to read`: point the kind at the tables the view reads.

## See also

- [The full document of delta-external and delta-sharing](../../docs/connectors/LAKEHOUSE_CONNECTORS.md) and the [lakehouse section of the data lake
  guide](../../docs/guides/DATA_LAKE_GUIDE.md#10-connecting-to-an-existing-lakehouse-databricks-unity-catalog).
- [The iceberg-external connector](../../docs/connectors/ICEBERG_EXTERNAL_CONNECTOR.md): the same reader and mapping, for Iceberg tables.
- [The connector developer's guide](../../docs/connectors/CONNECTOR_DEVELOPER_GUIDE.md) (the README standard).
