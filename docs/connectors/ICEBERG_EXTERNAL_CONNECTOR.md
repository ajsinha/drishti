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
# The external Iceberg connector: tables Drishti does not write

`iceberg-external` serves Apache Iceberg tables that **other engines write** (Spark, Flink, Trino, Snowflake, AWS Glue, dbt,
pyiceberg), in their own layout, as Drishti kinds: a table is a kind, a row is an entity's document. It reads through
**drishti-iceberg**, Drishti's own Iceberg engine with no Hadoop ([DRISHTI_ICEBERG.md](../architecture/DRISHTI_ICEBERG.md)), so it
runs on Windows too, and it **never writes**: the tables' owners maintain them with their own engines.

For tables Drishti's own loader writes (an `id`, a `doc` column, `business_date`, promoted columns, sorted by id) use the `iceberg`
connector ([ICEBERG_CONNECTOR.md](ICEBERG_CONNECTOR.md)): it reads that layout faster. The design of both is section 15 of
DRISHTI_ICEBERG.md. Every setting is also in [CONFIGURATION.md](../admin/CONFIGURATION.md#iceberg-external--iceberg-tables-drishti-does-not-write).

## Contents

1. [What it does](#1-what-it-does)
2. [A connector file](#2-a-connector-file)
3. [Rows as documents](#3-rows-as-documents)
4. [Ids, dates and modes](#4-ids-dates-and-modes)
5. [Searches, type-ahead and reverse links](#5-searches-type-ahead-and-reverse-links)
6. [One entity at scale](#6-one-entity-at-scale)
7. [Deletes and time travel](#7-deletes-and-time-travel)
8. [Discovery: tables auto](#8-discovery-tables-auto)
9. [Make a pack](#9-make-a-pack)
10. [Settings](#10-settings)
11. [Limits](#11-limits)
12. [How it is tested](#12-how-it-is-tested)

## 1. What it does

| | `iceberg` (Drishti tables) | `iceberg-external` (anyone's tables) |
|---|---|---|
| Tables | written by `IcebergLoader` in Drishti's layout | written by Spark, Flink, Trino, Snowflake, Glue, dbt, pyiceberg, in any layout |
| The document | the `doc` column | **the row** as JSON, or a JSON column (`document: column:<name>`) |
| The id | the `id` column | one column or several (`id: [SOURCE_SYSTEM, TRADE_ID]`, joined by `id-separator`, `:`); by default the table's **identifier fields** |
| The business date | `business_date` | a date column, a timestamp's day (`date-from: day`, `date-zone`), by default the partition spec's date; none: **current only** |
| Searchable fields | the columns the pack promotes | **every primitive column**, nested struct fields to `depth` (2) |
| Writes | the loader and maintenance | **never** |

Catalogs are the `iceberg` connector's: path-based tables under `root` (local disk, Windows paths included, or `s3://`), a REST
catalog (`catalog: rest`: Apache Polaris, Snowflake Open Catalog, Glue's Iceberg endpoint, Unity Catalog, Nessie, Lakekeeper,
Gravitino), or a table named by its metadata file (`metadata-location.<kind>`).

## 2. A connector file

```yaml
# config/connectors/risk-mart.yaml: tables a Spark pipeline writes to a Polaris catalog
plugin: iceberg-external
kinds: [trade, counterparty, market-quote]
settings:
  catalog: rest
  uri: https://polaris.example.com/api/catalog
  warehouse: risk
  credential: ${POLARIS_CLIENT_ID}:${POLARIS_CLIENT_SECRET}
  engine: native
  tables:
    trade:
      table: risk_mart.trades                       # namespace.table
      id: [SOURCE_SYSTEM, TRADE_ID]                 # MUREX:20000017
      date: BUSINESS_DATE                           # a date column
      mode: snapshot                                # or effective (a row only when the entity changes)
      columns: { exclude: [ETL_LOAD_TS, _FILE], rename: { cpty_id: COUNTERPARTY } }
      links: { COUNTERPARTY: counterparty, PORTFOLIO: portfolio }   # columns that name entities of other kinds
    counterparty:
      table: refdata.counterparties
      id: CPTY_ID                                   # no date column, no date partition: current only
    market-quote:
      table: market.quotes
      date: QUOTE_TS
      date-from: day                                # the business date of a timestamp
      date-zone: Europe/London
```

`tables:` sits under `settings:` (nesting is flattened to `tables.trade.table`, `tables.trade.links.COUNTERPARTY`, …, as in every
connector file). A ready-made file is [`config/connectors.examples/other/iceberg-external-risk-mart.yaml`](../../config/connectors.examples/other/iceberg-external-risk-mart.yaml).
For path-based tables `table: risk_mart.trades` is the folder `<root>/risk_mart/trades`. Its current version is the one its
`metadata/version-hint.text` names (`vN.metadata.json`, as Iceberg's Hadoop tables and the `iceberg` loader write them), or, for a table a
catalog wrote (pyiceberg, a JDBC or Glue catalog, Spark through one: `00003-<uuid>.metadata.json` and no hint), the metadata file with the
highest sequence number, as Iceberg's own readers take it; a later commit by that catalog is seen at the next refresh. A folder with
neither is `no table risk_mart.trades`, and Health says which files it looked for.

## 3. Rows as documents

| Iceberg type | In the document |
|---|---|
| boolean, int, long, float, double | JSON boolean and number |
| decimal(P,S) | a number; `decimals: string` keeps full precision as text |
| date, time, timestamp, timestamptz | ISO-8601 text: `2026-09-30`, `18:30:00`, `2026-09-30T18:30:00`, `2026-09-30T18:30:00Z` (a fraction of a second only when there is one, six digits) |
| string, uuid | text |
| fixed, binary | Base64 text (or left out with `binary: omit`) |
| struct | an object |
| list | an array |
| map | an object when its keys are text, else an array of `{key, value}` |

Column names keep their case; `columns.rename` maps them to the names a pack uses and `columns.exclude` leaves columns out. A
column is matched by its Iceberg field id, so a column the table's owner renames keeps its values, and each snapshot is read with
the schema it had (time travel shows the old names).

## 4. Ids, dates and modes

**The id.** `id` names one column or several; several are joined by `id-separator` (`:`): `MUREX:T-007`. Without `id`, the table's
identifier fields (Iceberg v2's `identifier-field-ids`, which Snowflake, Flink upserts and pyiceberg set). A table with neither is
not served: Health and Admin → Connectors name it (`… no id: the table has no identifier fields; set tables.<kind>.id`).

**The business date.** `date` names a date column, or a timestamp column with `date-from: day` (its day in `date-zone`, UTC by
default; a timestamp without a zone is its own day). Without `date`, the partition spec's date: an identity partition of a date
column, or `day()` of a timestamp. Without either, the kind is **current only**: every read is the current snapshot, whatever
date is asked, and documents carry no business date. A file whose partition is the date belongs to that day exactly; otherwise its
days come from the date column's bounds in the manifest (or from reading that column), and its rows are checked one by one.

**Modes.** `snapshot` (the default): every entity every date; a read for a date takes the newest day on or before it, within
`lookback-days`. `effective`: a row when the entity changes; a read takes each entity's latest row on or before the date.

## 5. Searches, type-ahead and reverse links

Every primitive column is a searchable field without a pack declaring it (nested struct fields to `depth`: `ADDRESS.CITY`):
`columnar(kind)` lists them, and a day's fields are read from those columns alone (no document is built) and kept
(`columns-cache-mb`). Type-ahead lists the newest day's ids (every day's for `effective` kinds); a table with no date is bounded by
`max-index-rows` (10,000,000) and marked `partial` beyond it. `links` names columns whose values are ids of other kinds: a
reverse lookup (*who refers to CP-1?*) reads those columns.

## 6. One entity at scale

- A table **sorted or bucketed by its id** (a sort order, a `bucket(id)` partition, or files whose id bounds do not overlap):
  the day's files are pruned with the id by their partitions and column bounds, and the remaining file is read with the id as a
  Parquet row-group filter (and the page index): one row group.
- An **unsorted** table pays once per snapshot and day: the id columns of the day's files are read into an **id index** (id to
  file, about 60 B an id, `id-index-mb`, 1024; least-used days dropped first), and later reads open the one file. Health suggests
  sorting the table when one day's index passes `id-index-warn-rows` (5,000,000).
- Footers, manifests and delete files are read once and kept (the native engine's caches, ICEBERG_CONNECTOR.md section 17).

## 7. Deletes and time travel

Merge-on-read deletes from Spark, Flink and Trino are applied by every read: **position deletes** of the file's partition at or
after its data sequence number, **equality deletes** of its partition (or global ones) after it. *Known at* reads the snapshot that
was current then, from the snapshot log, with that snapshot's schema; expired snapshots are no longer reachable.

## 8. Discovery: tables auto

```yaml
settings:
  catalog: rest
  uri: https://polaris.example.com/api/catalog
  tables: auto
  namespace: risk_mart
```

Every table of the namespace becomes a kind: its name in lower case with `-` for `_` (`TRADE_EVENTS` is `trade-events`), its id
from its identifier fields, its date from its partition spec. A table with no id is listed in Health (and Admin → Connectors) as
needing one and is not served until `tables.<kind>.id` names it. Kinds configured under `tables.<kind>` are served beside the
discovered ones.

## 9. Make a pack

A kind's fields come from the table's schema, so a starter pack can be drafted from it. `StarterSchemas` writes each served kind's
document as a JSON Schema, with the id (`x-drishti-role: key`), the business date (`x-drishti-role: date`) and the `links`
(`x-drishti-link`), and the pack maker turns the schemas into a pack (the kind, its fields, a Sutra listing them, links), to edit
like any pack ([SCHEMA_TO_PACK.md](../guides/SCHEMA_TO_PACK.md)):

```bash
java -cp "$(./mvnw -q -o dependency:build-classpath -pl plugins/drishti-plugin-iceberg-external -Dmdep.outputFile=/dev/stdout):plugins/drishti-plugin-iceberg-external/target/classes" \
  com.ash.drishti.plugin.icebergext.StarterSchemas --out build/risk-mart-schemas \
  --set catalog=rest --set uri=https://polaris.example.com/api/catalog --set tables=auto --set namespace=risk_mart
tools/drishti.py pack make --schema build/risk-mart-schemas --name risk-mart
```

A *Make a pack* button on Admin → Connectors for this connector is planned; until then the two commands above do the same.

## 10. Settings

| Setting | Default | Meaning |
|---|---|---|
| `catalog`, `root`, `uri`, `warehouse`, `credential`, `token`, `scope`, `oauth2-server-uri`, `prefix`, `catalog.<key>`, `metadata-location.<kind>`, `relocate`, `vended-credentials`, `s3.*`, `tls.*`, `proxy.*` | as the `iceberg` connector's | where the tables are ([ICEBERG_CONNECTOR.md section 15](ICEBERG_CONNECTOR.md#15-settings)) |
| `engine` | `auto` | `native` or `auto`: both read through drishti-iceberg; `library` is refused (it is the `iceberg` connector's only) |
| `tables.<kind>.table` | the kind | `namespace.table` (dots: namespace levels) |
| `tables.<kind>.id`, `id-separator` | the identifier fields, `:` | the id columns and how several are joined |
| `tables.<kind>.date`, `date-from`, `date-zone` | the partition spec's date, none, `UTC` | the business date |
| `tables.<kind>.mode` | `snapshot` | `snapshot` or `effective` |
| `tables.<kind>.document` | `row` | `row`, or `column:<name>` for a JSON column |
| `tables.<kind>.columns.exclude`, `columns.rename.<column>` | none | columns left out; their names in documents |
| `tables.<kind>.links.<column>` | none | the kind a column's values name |
| `tables.<kind>.depth` | `2` | how deep nested structs are searchable |
| `tables`, `namespace` | none | `tables: auto`: every table of `namespace` |
| `decimals`, `binary` | `number`, `base64` | how those types appear in documents |
| `id-index-mb`, `id-index-warn-rows`, `max-index-rows` | 1024, 5,000,000, 10,000,000 | [section 6](#6-one-entity-at-scale), [section 5](#5-searches-type-ahead-and-reverse-links) |
| `refresh-seconds`, `lookback-days`, `doc-cache-mb`, `columns-cache-mb`, `max-concurrent-reads` | 10, 10, 256, 1024, 16 | as the `iceberg` connector's |
| `source-name` | `iceberg-external` | the name in provenance and Health |

## 11. Limits

- **Read-only.** Nothing is written; maintenance (compaction, expiring snapshots) is the owners'.
- **Local disk and S3 only** (drishti-iceberg): tables in Azure, Google Cloud Storage or HDFS are not read; a REST catalog with an
  S3-compatible store in front of them works.
- **Format version 3, encrypted tables, ORC and Avro data files** are refused by name.
- **Effective kinds are not read as columns** (each entity's latest row would have to be resolved across days).
- **An unsorted table's first lookup of a day** reads that day's id columns (section 6); sort or bucket the table by id for
  single-row-group reads from the first lookup.
- **Branches and tags** other than `main` are not read.

## 12. How it is tested

`IcebergExternalPluginTest` serves the shapes of DRISHTI_ICEBERG.md section 15.5 from the pyiceberg-written fixtures
(`tools/iceberg/fixtures.py`): a Spark-style trade table (partitioned by the day of a timestamp, unsorted, a merge-on-read
position delete added by the Iceberg library), a Snowflake-style counterparty table (identifier fields, a nested struct, a map,
decimals, timestamptz, an equality delete), a current-only reference table, a table renamed and evolved, an unsorted table read
through its id index, and `tables: auto` naming a table without an id. Each checks one entity, a search over a column, type-ahead,
a reverse link and time travel, and compares the documents with pyiceberg's rows converted by the rules of section 3.
