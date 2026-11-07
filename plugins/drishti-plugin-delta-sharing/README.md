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
connector: delta-sharing
category: plugin
summary: Serves tables another organisation shares through the open Delta Sharing protocol as Drishti kinds; a profile file and a bearer token, no storage keys; the document built on read from the mapped columns.
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
- profile
- endpoint
- bearer-token
- share
- hints.*
- timeout-seconds
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
- proxy.*
auth: A bearer token from the provider's profile file, whose bearerToken is written as an environment reference (${PROVIDER_TOKEN}), or settings.bearer-token as ${ENV} or file - no storage keys, the server hands out pre-signed URLs.
licence: Drishti's own readers (drishti-lakehouse over drishti-iceberg's Parquet reader, java.net.http) with Jackson (Apache-2.0), bundled.
windows: Works the same on Windows (HTTP only, no native code).
doc: docs/connectors/LAKEHOUSE_CONNECTORS.md
---
# The delta-sharing connector

Plugin `delta-sharing` (`plugins/drishti-plugin-delta-sharing`): tables a data provider shares with the bank through the open Delta
Sharing protocol (Databricks, the open-source sharing server, or any server that speaks it), read without storage keys of the provider's.

## What it reads

The shares, schemas and tables the recipient's token may see; a table's metadata and its files at the current version, each a pre-signed
URL that expires and is renewed (asked for again) a minute before it does, or when the store refuses it. The Parquet files are read by
ranged GETs. Predicate hints (`hints.<share.schema.table>`) are sent with each query so the server may leave out files that cannot match.
**Not read:** change data feed, streaming, a share's history (every *known at* reads the current version), tables shared with deletion
vectors or column mapping in the `delta` response format, and OAuth profiles (version 2): under construction.

## Settings

<!-- settings:begin -->
| Setting | Type | Default | Meaning |
|---|---|---|---|
| `tables.*` | string |  | Per kind (the columns mapped, the document built on read): tables.<kind>.table (share.schema.table, or schema.table with share), id (columns, joined by id-separator), id-separator (:), date (default: the date partition column), date-from (day), date-zone (UTC), mode (snapshot\|effective), document (row\|column:<name>), columns.exclude, columns.rename.<column>, links.<column> (a kind), depth (2); or tables: auto with namespace |
| `tables` | string |  | auto: every table of the namespace is a kind (its name in lower case with -); each still needs its id (tables.<kind>.id) |
| `namespace` | string |  | The namespace of tables: auto (share.schema) |
| `profile` | path |  | The provider's profile file (shareCredentialsVersion 1: endpoint and bearerToken); its bearerToken must be an environment reference ("${PROVIDER_TOKEN}"), never the token itself |
| `endpoint` | string |  | Instead of a profile: the sharing server's address (https://sharing.example.com/delta-sharing) |
| `bearer-token` | string |  | Instead of a profile: the recipient's bearer token, ${ENV_VAR} or file:/path (secret: give `${ENV_VAR}` or `file:/path`, never the value) |
| `share` | string |  | The share of tables named schema.table |
| `hints.*` | string |  | Predicate hints per table (hints.<share.schema.table>): comparisons joined by and (business_date >= 2026-01-01 and region = EMEA), sent so the server may leave out files that cannot match |
| `timeout-seconds` | int | `60` | Each call to the sharing server at most |
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
| `source-name` | string | `delta-sharing` | The connector's name in provenance (delta-sharing) |

Every connector also takes `source-name`, `mode.*`, `domain`, `stale-after`, `union` (docs/connectors/CONNECTOR_FILES.md). It takes the shared `proxy.*` settings for an outbound HTTP proxy (CONNECTOR_DEVELOPER_GUIDE.md).
<!-- settings:end -->

## Data structure

As `delta-external` (the Delta types in the document, the id, the business date, the mode, links): mapping via `tables.<kind>.*`.
`tables.<kind>.table` is `share.schema.table`, or `schema.table` with `share`.

## Examples

```yaml
# config/connectors/vendor-prices.yaml: prices a market-data vendor shares
plugin: delta-sharing
kinds: [price]
settings:
  profile: /etc/drishti/shares/vendor.share         # {"shareCredentialsVersion":1,"endpoint":"…","bearerToken":"${VENDOR_SHARING_TOKEN}"}
  tables:
    price: {table: market_data.eod.prices, id: [ISIN, CURRENCY], date: PRICE_DATE}
  hints.market_data.eod.prices: PRICE_DATE >= 2026-01-01
```

A ready-made file is `config/connectors.examples/other/delta-sharing-vendor.yaml`.

## Troubleshooting

- `… holds the bearer token itself`: the profile file has the token; write `"bearerToken": "${A_VARIABLE}"` and set the variable.
- `… refused the bearer token (HTTP 401): it is wrong or expired`: ask the provider for a new profile.
- `… not found: share …, schema …, table … is not shared with this recipient (HTTP 404)`: the provider has not shared it.
- `… needs a Delta reader of version 3`: the provider shares the table with deletion vectors; ask for it without them.

## See also

- [The full document of delta-external and delta-sharing](../../docs/connectors/LAKEHOUSE_CONNECTORS.md#3-delta-sharing-shared-tables) and the [lakehouse section of the data lake
  guide](../../docs/guides/DATA_LAKE_GUIDE.md#10-connecting-to-an-existing-lakehouse-databricks-unity-catalog).
- [The connector developer's guide](../../docs/connectors/CONNECTOR_DEVELOPER_GUIDE.md) (the README standard).
